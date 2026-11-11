package io.github.kubyk01.application.service.compiler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Writes the application's runtime classpath next to the native executable as
 * {@code <exe>.classpath/}: {@code classes/} (Spring Boot {@code BOOT-INF/classes})
 * plus every library jar copied unchanged, and a {@code classpath} file listing
 * them in order. The native runtime reads that file into {@code java.class.path},
 * so ClassLoader.getResource(s) behaves as on a JVM.
 */
public final class ClasspathExporter {

    private static final String BOOT_CLASSES = "BOOT-INF/classes/";
    private static final String BOOT_LIB = "BOOT-INF/lib/";
    private static final String CLASSPATH_INDEX = "BOOT-INF/classpath.idx";

    private ClasspathExporter() {}

    public static Path export(Path input, List<Path> scannedJars, Path exePath) throws IOException {
        Path dir = Paths.get(exePath.toAbsolutePath() + ".classpath");
        deleteRecursively(dir);
        Files.createDirectories(dir);

        List<String> entries = new ArrayList<>();
        try (JarFile jar = new JarFile(input.toFile())) {
            if (jar.getJarEntry(BOOT_CLASSES) != null || jar.getJarEntry(CLASSPATH_INDEX) != null) {
                exportClasses(jar, dir.resolve("classes"));
                entries.add("classes");
                for (String lib : libraryOrder(jar)) {
                    JarEntry entry = jar.getJarEntry(lib);
                    if (entry == null) continue;
                    String name = lib.substring(lib.lastIndexOf('/') + 1);
                    try (InputStream in = jar.getInputStream(entry)) {
                        Files.copy(in, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                    }
                    entries.add(name);
                }
            } else {
                Set<Path> jars = new LinkedHashSet<>();
                jars.add(input.toAbsolutePath().normalize());
                for (Path p : scannedJars) jars.add(p.toAbsolutePath().normalize());
                for (Path p : jars) {
                    String name = p.getFileName().toString();
                    if (entries.contains(name)) continue;
                    Files.copy(p, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                    entries.add(name);
                }
            }
        }
        Files.write(dir.resolve("classpath"), entries);
        return dir;
    }

    private static void exportClasses(JarFile jar, Path classes) throws IOException {
        Path root = classes.toAbsolutePath().normalize();
        Files.createDirectories(root);
        for (JarEntry entry : jar.stream().toList()) {
            String name = entry.getName();
            if (!name.startsWith(BOOT_CLASSES) || entry.isDirectory()) continue;
            Path target = root.resolve(name.substring(BOOT_CLASSES.length())).normalize();
            if (!target.startsWith(root)) {
                throw new IOException("Refusing jar entry outside BOOT-INF/classes: " + name);
            }
            Files.createDirectories(target.getParent());
            try (InputStream in = jar.getInputStream(entry)) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static List<String> libraryOrder(JarFile jar) throws IOException {
        Set<String> order = new LinkedHashSet<>();
        JarEntry index = jar.getJarEntry(CLASSPATH_INDEX);
        if (index != null) {
            try (InputStream in = jar.getInputStream(index)) {
                for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                    String lib = line.trim();
                    if (lib.startsWith("- ")) lib = lib.substring(2).trim();
                    if (lib.startsWith("\"") && lib.endsWith("\"") && lib.length() > 1) {
                        lib = lib.substring(1, lib.length() - 1);
                    }
                    if (lib.startsWith(BOOT_LIB) && lib.endsWith(".jar")) order.add(lib);
                }
            }
            return new ArrayList<>(order);
        }
        for (JarEntry entry : jar.stream().toList()) {
            String name = entry.getName();
            if (name.startsWith(BOOT_LIB) && name.endsWith(".jar") && name.indexOf('/', BOOT_LIB.length()) < 0) {
                order.add(name);
            }
        }
        return new ArrayList<>(order);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
