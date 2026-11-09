package io.github.kubyk01.application.service.compiler;

import io.github.kubyk01.domain.ir.TargetPlatform;
import lombok.extern.slf4j.Slf4j;
import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Downloads a glibc sysroot (headers, crt objects, libc/libgcc) from the Debian archive
 * so clang can cross-compile to Linux from any host — the Linux-target counterpart of
 * installing mingw-w64 for Windows targets. Cached in ~/.jnative/sysroot after the first build.
 */
@Slf4j
final class LinuxSysroot {

    // Debian 12: glibc 2.36, so the binaries run on most distros from 2022 on.
    private static final String MIRROR = "https://deb.debian.org/debian/";
    private static final String RELEASE = "bookworm";
    private static final List<String> PACKAGES = List.of(
            "libc6", "libc6-dev", "linux-libc-dev", "libgcc-s1", "libgcc-12-dev");

    private LinuxSysroot() { }

    static String ensure(TargetPlatform target) throws IOException, InterruptedException {
        String triple = target.triple().toLowerCase(Locale.ROOT);
        if (!triple.contains("linux") || triple.contains("musl") || triple.contains("android")) {
            throw new IllegalStateException("No automatic sysroot for '" + target.triple()
                    + "' (only glibc Linux targets). Set JNATIVE_SYSROOT to a sysroot for it.");
        }
        String debArch = switch (triple.substring(0, triple.indexOf('-'))) {
            case "x86_64", "amd64" -> "amd64";
            case "aarch64", "arm64" -> "arm64";
            default -> throw new IllegalStateException("No automatic sysroot for architecture of '"
                    + target.triple() + "'. Set JNATIVE_SYSROOT to a sysroot for it.");
        };

        Path root = Paths.get(System.getProperty("user.home"), ".jnative", "sysroot", RELEASE + "-" + debArch);
        if (Files.exists(root.resolve(".complete"))) return root.toString();

        System.out.println("Downloading Linux sysroot (Debian " + RELEASE + " " + debArch + ") to " + root + " ...");
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        Map<String, String[]> debs = findPackages(http, debArch);

        // Build in a temp dir and rename, so an interrupted download never leaves a half sysroot behind.
        Files.createDirectories(root.getParent());
        Path tmp = Files.createTempDirectory(root.getParent(), root.getFileName() + ".tmp");
        Map<Path, String> symlinks = new LinkedHashMap<>();
        for (String pkg : PACKAGES) {
            String[] info = debs.get(pkg);
            if (info == null) throw new IOException("Package '" + pkg + "' not found in Debian " + RELEASE + " " + debArch);
            System.out.println("  " + pkg);
            byte[] deb = download(http, MIRROR + info[0]);
            String sha = HexFormat.of().formatHex(sha256(deb));
            if (!sha.equalsIgnoreCase(info[1])) {
                throw new IOException("SHA256 mismatch for " + info[0] + ": expected " + info[1] + ", got " + sha);
            }
            extractDeb(deb, tmp, symlinks);
        }
        materializeSymlinks(tmp, symlinks);
        rewriteLinkerScripts(tmp, root);
        Files.writeString(tmp.resolve(".complete"), RELEASE + " " + debArch + "\n");
        try {
            Files.move(tmp, root, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            if (!Files.exists(root.resolve(".complete"))) throw e; // a parallel build finished first
            deleteTree(tmp);
        }
        return root.toString();
    }

    /** package name -> {pool path, sha256}, from the release's Packages index. */
    // shortcut: trusts HTTPS for Packages.xz instead of verifying the signed InRelease; add GPG check if the mirror becomes configurable.
    private static Map<String, String[]> findPackages(HttpClient http, String debArch)
            throws IOException, InterruptedException {
        byte[] index = download(http, MIRROR + "dists/" + RELEASE + "/main/binary-" + debArch + "/Packages.xz");
        Map<String, String[]> result = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new XZInputStream(new java.io.ByteArrayInputStream(index)), StandardCharsets.UTF_8))) {
            String pkg = null, file = null, sha = null, line;
            do {
                line = r.readLine();
                if (line == null || line.isEmpty()) {
                    if (pkg != null && file != null && sha != null && PACKAGES.contains(pkg)) {
                        result.put(pkg, new String[]{file, sha});
                    }
                    pkg = file = sha = null;
                } else if (line.startsWith("Package: ")) {
                    pkg = line.substring(9).trim();
                } else if (line.startsWith("Filename: ")) {
                    file = line.substring(10).trim();
                } else if (line.startsWith("SHA256: ")) {
                    sha = line.substring(8).trim();
                }
            } while (line != null);
        }
        return result;
    }

    private static byte[] download(HttpClient http, String url) throws IOException, InterruptedException {
        HttpResponse<byte[]> resp = http.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode() + " for " + url);
        return resp.body();
    }

    // ------------------------------------------------------------------ .deb (ar) / tar

    /** A .deb is an ar archive; the files live in its data.tar.{xz,gz} member. */
    static void extractDeb(byte[] deb, Path dest, Map<Path, String> symlinks) throws IOException {
        if (deb.length < 8 || !new String(deb, 0, 8, StandardCharsets.US_ASCII).equals("!<arch>\n")) {
            throw new IOException("Not a .deb (ar) archive");
        }
        int pos = 8;
        while (pos + 60 <= deb.length) {
            String name = new String(deb, pos, 16, StandardCharsets.US_ASCII).trim();
            if (name.endsWith("/")) name = name.substring(0, name.length() - 1); // GNU ar terminator
            int size = Integer.parseInt(new String(deb, pos + 48, 10, StandardCharsets.US_ASCII).trim());
            int data = pos + 60;
            if (name.startsWith("data.tar")) {
                InputStream raw = new java.io.ByteArrayInputStream(deb, data, size);
                InputStream in = name.endsWith(".xz") ? new XZInputStream(raw)
                        : name.endsWith(".gz") ? new GZIPInputStream(raw)
                        : name.equals("data.tar") ? raw
                        : null;
                if (in == null) throw new IOException("Unsupported .deb member compression: " + name);
                extractTar(new BufferedInputStream(in), dest, symlinks);
                return;
            }
            pos = data + size + (size & 1); // ar members are 2-byte aligned
        }
        throw new IOException("No data.tar member in .deb");
    }

    /** Minimal ustar/GNU/pax reader: regular files, directories, symlinks and hardlinks. */
    static void extractTar(InputStream in, Path dest, Map<Path, String> symlinks) throws IOException {
        extractTar(in, dest, symlinks, name -> true);
    }

    /** Like above, but entries whose archive path fails {@code keep} are skipped. */
    static void extractTar(InputStream in, Path dest, Map<Path, String> symlinks,
                           java.util.function.Predicate<String> keep) throws IOException {
        byte[] hdr = new byte[512];
        String longName = null, longLink = null;
        while (readFully(in, hdr)) {
            if (isZero(hdr)) break;
            String name = longName != null ? longName : field(hdr, 0, 100);
            if (longName == null) {
                String prefix = field(hdr, 345, 155);
                if (!prefix.isEmpty() && "ustar".equals(field(hdr, 257, 5))) name = prefix + "/" + name;
            }
            String link = longLink != null ? longLink : field(hdr, 157, 100);
            longName = longLink = null;
            long size = Long.parseLong(field(hdr, 124, 12).trim().isEmpty() ? "0" : field(hdr, 124, 12).trim(), 8);
            char type = (char) (hdr[156] == 0 ? '0' : hdr[156]);

            if (type == 'L' || type == 'K' || type == 'x') {
                byte[] body = readBody(in, size);
                if (type == 'L') longName = cString(body);
                else if (type == 'K') longLink = cString(body);
                else {
                    Map<String, String> pax = parsePax(body);
                    longName = pax.get("path");
                    longLink = pax.get("linkpath");
                }
                continue;
            }

            if (!keep.test(stripDot(name))) {
                skip(in, size + pad(size));
                continue;
            }
            Path out = safeResolve(dest, name);
            switch (type) {
                case '5' -> Files.createDirectories(out);
                case '2' -> symlinks.put(out, link);
                case '1' -> symlinks.put(out, "/" + stripDot(link)); // hardlink: path relative to archive root
                case '0', '7' -> {
                    Files.createDirectories(out.getParent());
                    try (var os = Files.newOutputStream(out)) {
                        copy(in, os, size);
                    }
                    skip(in, pad(size));
                    continue;
                }
                default -> { }
            }
            skip(in, size + pad(size));
        }
    }

    /**
     * Windows can't create symlinks without admin rights, so links are replaced by copies of
     * their targets. Repeated until no progress, since links can point to other links.
     */
    static void materializeSymlinks(Path root, Map<Path, String> symlinks) throws IOException {
        Map<Path, String> pending = new LinkedHashMap<>(symlinks);
        boolean progress = true;
        while (!pending.isEmpty() && progress) {
            progress = false;
            for (var it = pending.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                Path link = e.getKey();
                String t = e.getValue();
                Path target = t.startsWith("/") ? safeResolve(root, t) : link.getParent().resolve(t).normalize();
                if (!target.startsWith(root) || !Files.exists(target)) continue;
                Files.createDirectories(link.getParent());
                if (Files.isDirectory(target)) {
                    copyTree(target, link);
                } else {
                    Files.copy(target, link, StandardCopyOption.REPLACE_EXISTING);
                }
                it.remove();
                progress = true;
            }
        }
        // Dangling links point to files from packages we don't ship (docs, other libs); harmless.
        for (var e : pending.entrySet()) log.debug("Sysroot: dangling link {} -> {}", e.getKey(), e.getValue());
    }

    /**
     * glibc's libc.so / libm.so are text linker scripts naming absolute paths
     * (GROUP ( /lib/x86_64-linux-gnu/libc.so.6 ... )). On a Windows host those resolve to
     * C:\lib\..., so they are pointed at the final sysroot location explicitly.
     */
    static void rewriteLinkerScripts(Path dir, Path finalRoot) throws IOException {
        String rootPrefix = finalRoot.toAbsolutePath().toString().replace('\\', '/');
        java.util.regex.Pattern abs = java.util.regex.Pattern.compile("(?<=[\\s(])(/(?:usr/)?lib[^\\s)]*)");
        List<Path> scripts = new ArrayList<>();
        try (Stream<Path> s = Files.walk(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".so") && Files.isRegularFile(p))
                    .forEach(scripts::add);
        }
        for (Path p : scripts) {
            if (Files.size(p) > 4096) continue; // real ELF shared objects are far larger
            String text = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
            if (!text.contains("GNU ld script")) continue;
            String fixed = abs.matcher(text).replaceAll(
                    m -> java.util.regex.Matcher.quoteReplacement("\"" + rootPrefix + m.group(1) + "\""));
            Files.writeString(p, fixed, StandardCharsets.ISO_8859_1);
        }
    }

    // ------------------------------------------------------------------ helpers

    static Path safeResolve(Path root, String name) throws IOException {
        Path p = root.resolve(stripDot(name)).normalize();
        if (!p.startsWith(root)) throw new IOException("Archive entry escapes sysroot: " + name);
        return p;
    }

    private static String stripDot(String name) {
        String n = name;
        while (n.startsWith("./") || n.startsWith("/")) n = n.substring(n.startsWith("./") ? 2 : 1);
        return n;
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path d = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(d);
                else Files.copy(p, d, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private static Map<String, String> parsePax(byte[] body) {
        // records: "<len> key=value\n"
        Map<String, String> m = new HashMap<>();
        int i = 0;
        while (i < body.length) {
            int sp = i;
            while (sp < body.length && body[sp] != ' ') sp++;
            if (sp >= body.length) break;
            int len = Integer.parseInt(new String(body, i, sp - i, StandardCharsets.US_ASCII));
            if (len <= 0) break;
            String rec = new String(body, sp + 1, i + len - sp - 2, StandardCharsets.UTF_8);
            int eq = rec.indexOf('=');
            if (eq > 0) m.put(rec.substring(0, eq), rec.substring(eq + 1));
            i += len;
        }
        return m;
    }

    private static String field(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    private static String cString(byte[] b) {
        int end = 0;
        while (end < b.length && b[end] != 0) end++;
        return new String(b, 0, end, StandardCharsets.UTF_8);
    }

    private static boolean isZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static long pad(long size) {
        return (512 - (size % 512)) % 512;
    }

    private static byte[] readBody(InputStream in, long size) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream((int) size);
        copy(in, bos, size);
        skip(in, pad(size));
        return bos.toByteArray();
    }

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int n = in.readNBytes(buf, 0, buf.length);
        if (n == 0) return false;
        if (n < buf.length) throw new IOException("Truncated tar archive");
        return true;
    }

    private static void copy(InputStream in, java.io.OutputStream out, long size) throws IOException {
        byte[] buf = new byte[8192];
        long left = size;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) throw new IOException("Truncated tar archive");
            out.write(buf, 0, n);
            left -= n;
        }
    }

    private static void skip(InputStream in, long n) throws IOException {
        long left = n;
        while (left > 0) {
            long s = in.skip(left);
            if (s <= 0) {
                if (in.read() < 0) throw new IOException("Truncated tar archive");
                s = 1;
            }
            left -= s;
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
