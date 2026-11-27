package io.github.kubyk01.util;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

public final class StrategyScanner {

    private static final String ROOT = "io/github/kubyk01/";

    private StrategyScanner() {}

    private record Header(String superName, String[] interfaces, int access) {}

    public static <T> List<T> scan(Class<T> contract, ToIntFunction<T> priority) {
        Map<String, T> found = new HashMap<>();
        for (T candidate : ServiceLoader.load(contract, contract.getClassLoader())) {
            found.putIfAbsent(candidate.getClass().getName(), candidate);
        }
        if (found.isEmpty()) {
            for (String name : implementations(contract)) {
                T instance = instantiate(contract, name);
                if (instance != null) found.putIfAbsent(name, instance);
            }
        }
        if (found.isEmpty()) {
            throw new IllegalStateException("No implementation of " + contract.getName() + " was discovered");
        }
        List<T> ordered = new ArrayList<>(found.values());
        ordered.sort(Comparator.comparingInt(priority).thenComparing(s -> s.getClass().getName()));
        return List.copyOf(ordered);
    }

    private static <T> T instantiate(Class<T> contract, String binaryName) {
        try {
            Class<?> type = Class.forName(binaryName, true, contract.getClassLoader());
            if (!contract.isAssignableFrom(type) || type.isInterface()
                    || Modifier.isAbstract(type.getModifiers())) {
                return null;
            }
            return contract.cast(type.getDeclaredConstructor().newInstance());
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("Cannot instantiate " + binaryName + " as " + contract.getName(), e);
        }
    }

    private static Set<String> implementations(Class<?> contract) {
        Map<String, Header> headers = readHeaders(contract);
        String target = contract.getName().replace('.', '/');
        Set<String> result = new LinkedHashSet<>();
        Map<String, Boolean> memo = new HashMap<>();
        for (Map.Entry<String, Header> e : headers.entrySet()) {
            if ((e.getValue().access() & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)) != 0) continue;
            if (implementsContract(e.getKey(), target, headers, memo, new HashSet<>())) {
                result.add(e.getKey().replace('/', '.'));
            }
        }
        return result;
    }

    private static boolean implementsContract(String name, String target, Map<String, Header> headers,
                                              Map<String, Boolean> memo, Set<String> visiting) {
        if (name == null) return false;
        if (name.equals(target)) return true;
        Boolean known = memo.get(name);
        if (known != null) return known;
        Header header = headers.get(name);
        if (header == null || !visiting.add(name)) return false;
        boolean result = implementsContract(header.superName(), target, headers, memo, visiting);
        for (String iface : header.interfaces()) {
            if (result) break;
            result = implementsContract(iface, target, headers, memo, visiting);
        }
        memo.put(name, result);
        return result;
    }

    private static Map<String, Header> readHeaders(Class<?> contract) {
        Map<String, Header> headers = new HashMap<>();
        for (Path location : locations(contract)) {
            try {
                if (Files.isDirectory(location)) {
                    if (!Files.isDirectory(location.resolve(ROOT))) continue;
                    try (Stream<Path> files = Files.walk(location.resolve(ROOT))) {
                        for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                            try (InputStream in = Files.newInputStream(file)) {
                                addHeader(headers, in);
                            }
                        }
                    }
                } else if (Files.isRegularFile(location)) {
                    try (JarFile jar = new JarFile(location.toFile())) {
                        for (JarEntry entry : (Iterable<JarEntry>) jar.stream()::iterator) {
                            String entryName = entry.getName();
                            if (!entryName.startsWith(ROOT) || !entryName.endsWith(".class")) continue;
                            try (InputStream in = jar.getInputStream(entry)) {
                                addHeader(headers, in);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Cannot scan " + location + " for " + contract.getName(), e);
            }
        }
        return headers;
    }

    private static void addHeader(Map<String, Header> headers, InputStream in) throws IOException {
        ClassReader reader = new ClassReader(in);
        headers.put(reader.getClassName(),
                new Header(reader.getSuperName(), reader.getInterfaces(), reader.getAccess()));
    }

    private static Set<Path> locations(Class<?> contract) {
        Set<Path> out = new LinkedHashSet<>();
        for (Class<?> anchor : List.of(StrategyScanner.class, contract)) {
            CodeSource source = anchor.getProtectionDomain() == null ? null
                    : anchor.getProtectionDomain().getCodeSource();
            URL url = source == null ? null : source.getLocation();
            if (url == null) continue;
            try {
                out.add(Path.of(url.toURI()));
            } catch (URISyntaxException | IllegalArgumentException e) {
                throw new IllegalStateException("Unsupported code source " + url, e);
            }
        }
        return out;
    }
}
