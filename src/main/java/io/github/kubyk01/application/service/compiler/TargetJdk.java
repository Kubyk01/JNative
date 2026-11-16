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
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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

public final class TargetJdk {

    private static final String API = "https://api.adoptium.net/v3/assets/latest/%d/hotspot"
            + "?architecture=%s&image_type=jdk&os=%s&vendor=eclipse";

    private TargetJdk() { }

    /** java.home of a JDK for {@code target}'s OS, or null when the running JDK already matches. */
    public static synchronized Path ensure(TargetPlatform target) throws IOException {
        if (target == null || !target.isCrossOs()) return null;
        int feature = Runtime.version().feature(); // C natives in resources are written against this JDK
        String os = target.isWindows() ? "windows" : target.isDarwin() ? "mac" : "linux";
        String t = target.triple().toLowerCase(Locale.ROOT);
        String arch = switch (t.substring(0, t.indexOf('-'))) {
            case "x86_64", "amd64" -> "x64";
            case "aarch64", "arm64" -> "aarch64";
            default -> throw new IllegalStateException("No JDK download for architecture of '" + target.triple() + "'");
        };

        Path root = Paths.get(System.getProperty("user.home"), ".jnative", "jdk",
                feature + "-" + os + "-" + arch);
        Files.createDirectories(root.getParent());
        Path lockPath = root.resolveSibling(root.getFileName() + ".lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            if (Files.exists(root.resolve(".complete"))) {
                try {
                    Path home = findJavaHome(root);
                    RuntimeImageEmbedder.validate(home);
                    return home;
                } catch (IOException e) {
                    System.out.println("Refreshing incomplete target JDK cache at " + root + ": " + e.getMessage());
                }
            }
            return download(root, feature, os, arch);
        }
    }

    private static Path download(Path root, int feature, String os, String arch) throws IOException {
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
                RuntimeImageEmbedder.validate(findJavaHome(tmp));
                Files.writeString(tmp.resolve(".complete"), name + "\n");
                install(tmp, root);
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

    private static void install(Path source, Path root) throws IOException {
        Path previous = null;
        if (Files.exists(root)) {
            previous = Files.createTempDirectory(root.getParent(), root.getFileName() + ".previous");
            Files.delete(previous);
            move(root, previous);
        }
        try {
            move(source, root);
        } catch (IOException failure) {
            if (previous != null) {
                try {
                    move(previous, root);
                } catch (IOException rollback) {
                    failure.addSuppressed(rollback);
                }
            }
            throw failure;
        }
        if (previous != null) deleteTree(previous);
    }

    private static void move(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, destination);
        }
    }

    private static boolean keep(String entry) {
        if (entry.endsWith("/release")) return true;
        for (String directory : RuntimeImageEmbedder.DIRECTORIES) {
            if (entry.contains("/" + directory + "/") || entry.endsWith("/" + directory)) return true;
        }
        return false;
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
                    .orElseThrow(() -> new IOException("Target JDK in " + root + " has no runtime image with release and lib/modules"));
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
