package io.github.kubyk01.application.service.compiler;

import org.objectweb.asm.ClassReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

public final class RuntimeClasspath implements AutoCloseable {

    private static final String BOOT_CLASSES = "BOOT-INF/classes/";
    private static final String BOOT_LIB = "BOOT-INF/lib/";
    private static final String CLASSPATH_INDEX = "BOOT-INF/classpath.idx";

    private final Path staging;
    private final List<Path> entries = new ArrayList<>();

    private RuntimeClasspath(Path staging) {
        this.staging = staging;
    }

    public static RuntimeClasspath create(Path input, List<Path> scannedJars, Path workspace) throws IOException {
        Files.createDirectories(workspace);
        RuntimeClasspath result = new RuntimeClasspath(Files.createTempDirectory(workspace, "classpath-"));
        try {
            Path primary = input == null ? null : input.toAbsolutePath().normalize();
            if (primary != null) result.addInput(primary, workspace.toAbsolutePath().normalize());
            Set<Path> seen = new LinkedHashSet<>();
            if (primary != null) seen.add(primary);
            for (Path jar : scannedJars) {
                Path absolute = jar.toAbsolutePath().normalize();
                if (seen.add(absolute)) result.entries.add(absolute);
            }
            return result;
        } catch (IOException | RuntimeException failure) {
            try {
                result.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public List<Path> entries() {
        return List.copyOf(entries);
    }

    private void addInput(Path input, Path workspace) throws IOException {
        if (Files.isDirectory(input)) {
            Path archive = staging.resolve("classes.jar");
            try (var walk = Files.walk(input);
                 JarOutputStream out = new JarOutputStream(Files.newOutputStream(archive))) {
                Set<String> written = new HashSet<>();
                for (Path file : walk.filter(Files::isRegularFile)
                        .filter(path -> !path.startsWith(workspace)).sorted().toList()) {
                    try (InputStream in = Files.newInputStream(file)) {
                        writeEntry(out, written, input.relativize(file).toString().replace('\\', '/'), in);
                    }
                }
            }
            entries.add(archive);
            return;
        }
        if (input.toString().endsWith(".class")) {
            byte[] bytes = Files.readAllBytes(input);
            Path archive = staging.resolve("classes.jar");
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(archive))) {
                writeEntry(out, new HashSet<>(), new ClassReader(bytes).getClassName() + ".class",
                        new ByteArrayInputStream(bytes));
            }
            entries.add(archive);
            return;
        }
        try (JarFile jar = new JarFile(input.toFile())) {
            boolean boot = jar.getJarEntry(CLASSPATH_INDEX) != null
                    || jar.stream().anyMatch(entry -> entry.getName().startsWith(BOOT_CLASSES));
            if (!boot) {
                entries.add(input);
                return;
            }
            Path classes = staging.resolve("classes.jar");
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(classes))) {
                Set<String> written = new HashSet<>();
                for (JarEntry entry : jar.stream().toList()) {
                    if (entry.isDirectory() || !entry.getName().startsWith(BOOT_CLASSES)) continue;
                    try (InputStream in = jar.getInputStream(entry)) {
                        writeEntry(out, written, entry.getName().substring(BOOT_CLASSES.length()), in);
                    }
                }
            }
            entries.add(classes);
            for (String name : libraryOrder(jar)) {
                JarEntry entry = jar.getJarEntry(name);
                if (entry == null || entry.isDirectory()) throw new IOException("Missing classpath entry: " + name);
                Path directory = staging.resolve(Integer.toString(entries.size()));
                Files.createDirectories(directory);
                Path library = directory.resolve(name.substring(name.lastIndexOf('/') + 1));
                try (InputStream in = jar.getInputStream(entry)) {
                    Files.copy(in, library);
                }
                entries.add(library);
            }
        }
    }

    private static void writeEntry(JarOutputStream out, Set<String> written, String name, InputStream in)
            throws IOException {
        if (name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0) {
            throw new IOException("Invalid classpath resource path: " + name);
        }
        for (String component : name.split("/", -1)) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")) {
                throw new IOException("Invalid classpath resource path: " + name);
            }
        }
        for (int slash = name.indexOf('/'); slash >= 0; slash = name.indexOf('/', slash + 1)) {
            String directory = name.substring(0, slash + 1);
            if (written.add(directory)) {
                out.putNextEntry(new JarEntry(directory));
                out.closeEntry();
            }
        }
        if (!written.add(name)) throw new IOException("Duplicate classpath resource: " + name);
        out.putNextEntry(new JarEntry(name));
        in.transferTo(out);
        out.closeEntry();
    }

    private static List<String> libraryOrder(JarFile jar) throws IOException {
        Set<String> order = new LinkedHashSet<>();
        JarEntry index = jar.getJarEntry(CLASSPATH_INDEX);
        if (index != null) {
            try (InputStream in = jar.getInputStream(index)) {
                for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                    String library = line.trim();
                    if (library.startsWith("- ")) library = library.substring(2).trim();
                    if (library.startsWith("\"") && library.endsWith("\"") && library.length() > 1) {
                        library = library.substring(1, library.length() - 1);
                    }
                    if (library.startsWith(BOOT_LIB) && library.endsWith(".jar")) order.add(library);
                }
            }
        }
        for (JarEntry entry : jar.stream().toList()) {
            String name = entry.getName();
            if (!entry.isDirectory() && name.startsWith(BOOT_LIB) && name.endsWith(".jar")) order.add(name);
        }
        return new ArrayList<>(order);
    }

    @Override
    public void close() throws IOException {
        try (var walk = Files.walk(staging)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
