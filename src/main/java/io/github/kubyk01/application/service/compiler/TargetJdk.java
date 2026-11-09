package io.github.kubyk01.application.service.compiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kubyk01.domain.ir.TargetPlatform;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The JDK class library differs per OS (WinNTFileSystem vs UnixFileSystem, WindowsFileSystemProvider
 * vs LinuxFileSystemProvider, ...), so a cross-OS build must compile the TARGET's java.base, not the
 * one of the JVM running JNative. Downloads the Temurin JDK of the running feature version for the
 * target OS/arch once (~/.jnative/jdk), keeping only lib/ (the jimage + jrt-fs.jar) and release.
 */
public final class TargetJdk {

    private static final String API = "https://api.adoptium.net/v3/assets/latest/%d/hotspot"
            + "?architecture=%s&image_type=jdk&os=%s&vendor=eclipse";

    private TargetJdk() { }

    /** java.home of a JDK for {@code target}'s OS, or null when the running JDK already matches. */
    public static Path ensure(TargetPlatform target) throws IOException {
        if (target == null || !target.isCrossOs()) return null;
        int feature = Runtime.version().feature(); // C natives in resources are written against this JDK
        String os = target.isWindows() ? "windows" : target.isDarwin() ? "mac" : "linux";
        String t = target.triple().toLowerCase(Locale.ROOT);
        String arch = switch (t.substring(0, t.indexOf('-'))) {
            case "x86_64", "amd64" -> "x64";
            case "aarch64", "arm64" -> "aarch64";
            default -> throw new IllegalStateException("No JDK download for architecture of '" + target.triple() + "'");
        };

        Path root = Paths.get(System.getProperty("user.home"), ".jnative", "jdk", feature + "-" + os + "-" + arch);
        if (Files.exists(root.resolve(".complete"))) return findJavaHome(root);

        try {
            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
            String api = String.format(API, feature, arch, os);
            HttpResponse<String> meta = http.send(HttpRequest.newBuilder(URI.create(api)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (meta.statusCode() != 200) throw new IOException("HTTP " + meta.statusCode() + " for " + api);
            JsonNode assets = new ObjectMapper().readTree(meta.body());
            if (!assets.isArray() || assets.isEmpty()) throw new IOException("No Temurin " + feature + " JDK for " + os + "/" + arch);
            JsonNode pkg = assets.get(0).path("binary").path("package");
            String link = pkg.path("link").asText();
            String sha = pkg.path("checksum").asText();
            String name = pkg.path("name").asText();

            System.out.println("Downloading target JDK " + assets.get(0).path("release_name").asText()
                    + " (" + os + "/" + arch + ") to " + root + " ...");
            Files.createDirectories(root.getParent());
            Path archive = Files.createTempFile(root.getParent(), "download", name.endsWith(".zip") ? ".zip" : ".tar.gz");
            Path tmp = Files.createTempDirectory(root.getParent(), root.getFileName() + ".tmp");
            try {
                HttpResponse<Path> resp = http.send(HttpRequest.newBuilder(URI.create(link)).build(),
                        HttpResponse.BodyHandlers.ofFile(archive));
                if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode() + " for " + link);
                String actual = sha256(archive);
                if (!actual.equalsIgnoreCase(sha)) {
                    throw new IOException("SHA256 mismatch for " + name + ": expected " + sha + ", got " + actual);
                }
                extract(archive, name.endsWith(".zip"), tmp);
                Files.writeString(tmp.resolve(".complete"), name + "\n");
                try {
                    Files.move(tmp, root, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    if (!Files.exists(root.resolve(".complete"))) throw e; // a parallel build finished first
                }
            } finally {
                Files.deleteIfExists(archive);
                deleteTree(tmp);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while downloading the target JDK");
        }
        return findJavaHome(root);
    }

    /** Only lib/ (modules image, jrt-fs.jar, tzdb.dat, ...) and the release file are needed. */
    private static boolean keep(String entry) {
        return entry.contains("/lib/") || entry.endsWith("/release");
    }

    private static void extract(Path archive, boolean zip, Path dest) throws IOException {
        if (zip) {
            try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
                for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                    if (e.isDirectory() || !keep(e.getName())) continue;
                    Path out = LinuxSysroot.safeResolve(dest, e.getName());
                    Files.createDirectories(out.getParent());
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } else {
            Map<Path, String> symlinks = new LinkedHashMap<>();
            try (InputStream in = new BufferedInputStream(new GZIPInputStream(Files.newInputStream(archive), 1 << 16))) {
                LinuxSysroot.extractTar(in, dest, symlinks, TargetJdk::keep);
            }
            LinuxSysroot.materializeSymlinks(dest, symlinks);
        }
    }

    /** The archive's top dir is jdk-25.x+y (or jdk-25.x+y/Contents/Home on macOS): find it via the release file. */
    private static Path findJavaHome(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root, 4)) {
            return s.filter(p -> p.getFileName().toString().equals("release") && Files.isRegularFile(p))
                    .map(Path::getParent)
                    .filter(h -> Files.isRegularFile(h.resolve("lib").resolve("modules")))
                    .findFirst()
                    .orElseThrow(() -> new IOException("Target JDK in " + root + " has no lib/modules; delete the folder and retry"));
        }
    }

    private static String sha256(Path file) throws IOException {
        try (DigestInputStream in = new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(in.getMessageDigest().digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
