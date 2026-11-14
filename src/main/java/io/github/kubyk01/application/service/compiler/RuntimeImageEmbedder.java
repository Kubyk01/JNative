package io.github.kubyk01.application.service.compiler;

import io.github.kubyk01.domain.ir.TargetPlatform;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class RuntimeImageEmbedder {

    static final List<String> DIRECTORIES = List.of("conf", "lib", "legal");

    private RuntimeImageEmbedder() {}

    public record Sources(Path assembly, Path table) {}

    static void validate(Path javaHome) throws IOException {
        for (String relative : List.of("release", "lib/modules", "lib/tzdb.dat",
                "conf/security/java.security", "lib/security/cacerts")) {
            Path file = javaHome.resolve(relative);
            if (!Files.isRegularFile(file) || !Files.isReadable(file) || Files.size(file) == 0) {
                throw new IOException("Target JDK runtime data is missing or unreadable: " + file);
            }
        }
        for (String relative : List.of("conf", "lib", "conf/security/policy")) {
            if (!Files.isDirectory(javaHome.resolve(relative))) {
                throw new IOException("Target JDK runtime directory is missing: " + javaHome.resolve(relative));
            }
        }
    }

    public static Sources embed(Path javaHome, List<Path> classpath, List<Path> libraries,
                                Path output, TargetPlatform target) throws IOException {
        Path root = javaHome.toAbsolutePath().normalize();
        validate(root);
        Files.createDirectories(output);
        Map<String, Path> files = new TreeMap<>();
        addFile(files, "release", root.resolve("release"));
        for (String directory : DIRECTORIES) {
            Path source = root.resolve(directory);
            if (!Files.exists(source)) continue;
            try (var entries = Files.walk(source, FileVisitOption.FOLLOW_LINKS)) {
                for (Path entry : entries.toList()) {
                    if (Files.isDirectory(entry)) continue;
                    if (!Files.isRegularFile(entry) || !Files.isReadable(entry)) {
                        throw new IOException("Cannot embed target JDK runtime file: " + entry);
                    }
                    addFile(files, relativeName(root, entry), entry);
                }
            }
        }
        List<String> classpathNames = new ArrayList<>();
        for (Path entry : classpath) {
            String name = "classpath/" + classpathNames.size() + "/" + entry.getFileName();
            addFile(files, name, entry);
            classpathNames.add(name);
        }
        List<String> libraryNames = new ArrayList<>();
        for (Path library : libraries) {
            String name = "native/" + library.getFileName();
            addFile(files, name, library);
            libraryNames.add(name);
        }
        Path data = output.resolve("jnative_runtime_image.bin");
        Path table = output.resolve("jnative_runtime_files.c");
        long offset = 0;
        try (var bytes = new BufferedOutputStream(Files.newOutputStream(data));
             var writer = Files.newBufferedWriter(table, StandardCharsets.UTF_8)) {
            writer.write("#include \"jnative_runtime.h\"\n");
            writer.write("const JNativeRuntimeFile jnative_runtime_files[] = {\n");
            for (var file : files.entrySet()) {
                int padding = (int)((16 - (offset & 15)) & 15);
                bytes.write(new byte[padding]);
                offset = Math.addExact(offset, padding);
                long size = Files.copy(file.getValue(), bytes);
                writer.write("    {" + quote(file.getKey()) + ", " + offset + "ULL, " + size + "ULL},\n");
                offset = Math.addExact(offset, size);
            }
            writer.write("};\nconst uint64_t jnative_runtime_files_count = " + files.size() + "ULL;\n");
            writer.write("const uint64_t jnative_runtime_data_size = " + offset + "ULL;\n");
            writer.write("const char* const jnative_runtime_classpath[] = {\n");
            for (String name : classpathNames) writer.write("    " + quote(name) + ",\n");
            writer.write("    0\n};\n");
            writer.write("const char* const __jnative_bundled_jni_libraries[] = {\n");
            for (String name : libraryNames) writer.write("    " + quote(name) + ",\n");
            writer.write("    0\n};\n");
        }
        String prefix = target.isDarwin() || target.isWindows() && target.triple().matches("i[3-6]86-.*") ? "_" : "";
        String symbol = prefix + "jnative_runtime_data";
        String section = target.isWindows() ? ".section .rdata,\"dr\"\n"
                : target.isDarwin() ? ".section __TEXT,__const\n" : ".section .rodata.jnative_runtime,\"a\"\n";
        Path assembly = output.resolve("jnative_runtime_image.s");
        String source = section + ".balign 16\n.globl " + symbol + "\n" + symbol + ":\n.incbin "
                + quote(data.toAbsolutePath().toString().replace('\\', '/')) + "\n";
        if (!target.isWindows() && !target.isDarwin()) source += ".section .note.GNU-stack,\"\"\n";
        Files.writeString(assembly, source, StandardCharsets.UTF_8);
        System.out.println("Embedding " + files.size() + " runtime file(s), " + classpathNames.size()
                + " classpath archive(s), " + libraryNames.size() + " native library/libraries, " + offset + " bytes");
        return new Sources(assembly, table);
    }

    private static void addFile(Map<String, Path> files, String name, Path source) throws IOException {
        if (name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0) {
            throw new IOException("Invalid embedded runtime path: " + name);
        }
        for (String component : name.split("/", -1)) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")) {
                throw new IOException("Invalid embedded runtime path: " + name);
            }
        }
        if (!Files.isRegularFile(source) || !Files.isReadable(source)) {
            throw new IOException("Cannot embed runtime file: " + source);
        }
        Path previous = files.putIfAbsent(name, source);
        if (previous != null && Files.mismatch(previous, source) != -1) {
            throw new IOException("Conflicting embedded runtime files for " + name + ": " + previous + " and " + source);
        }
    }

    private static String relativeName(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c >= 32 && c < 127 && c != '"' && c != '\\') {
                result.append((char)c);
            } else {
                result.append('\\').append((char)('0' + (c >>> 6)))
                        .append((char)('0' + (c >>> 3 & 7))).append((char)('0' + (c & 7)));
            }
        }
        return result.append('"').toString();
    }
}
