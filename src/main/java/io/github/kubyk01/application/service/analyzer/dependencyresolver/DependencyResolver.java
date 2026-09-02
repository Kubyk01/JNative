package io.github.kubyk01.application.service.analyzer.dependencyresolver;

import io.github.kubyk01.application.service.analyzer.reachabilityanalysis.ReachabilityMetadataParser;
import io.github.kubyk01.application.service.analyzer.ssa.TypeResolver;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode;
import io.github.kubyk01.domain.analyzer.reachability.ReachabilityMetadata;
import io.github.kubyk01.domain.ir.Type;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.objectweb.asm.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

@Slf4j
@RequiredArgsConstructor
public class DependencyResolver {

    private static final String NATIVE_IMAGE_METADATA_PREFIX = "META-INF/native-image/";

    @Getter
    private final Map<String, ClassNode> classMap = new HashMap<>();
    private final Map<String, byte[]> classBytes = new HashMap<>();
    private final Map<String, Set<String>> subclasses = new HashMap<>();
    private final Set<String> missingClasses = new HashSet<>();
    @Getter
    private final ReachabilityMetadata metadata = ReachabilityMetadata.builder().build();

    @Getter
    private boolean systemFsInitialized = false;

    /**
     * Lazily loads a single system class from the JDK by its internal name.
     * If the class is not found in the JDK, an external stub is created.
     */
    public synchronized void loadSystemClass(String internalName) {
        if (classMap.containsKey(internalName)) {
            return;
        }

        byte[] bytes = null;

        try {
            InputStream is = ClassLoader.getSystemResourceAsStream(internalName + ".class");
            if (is != null) {
                bytes = is.readAllBytes();
                is.close();
            } else {
                log.debug("Class {} not found via system ClassLoader, trying fallback (if any)", internalName);
            }
        } catch (IOException e) {
            log.warn("Failed to load system class {}: {}", internalName, e.getMessage());
        }

        if (bytes != null) {
            try {
                parseClassBytes(internalName, bytes);
                return;
            } catch (IOException e) {
                log.warn("Failed to parse system class {}: {}", internalName, e.getMessage());
            }
        }

        log.debug("System class {} not loaded with bytecode, marked external", internalName);
        ClassNode stub = ClassNode.builder()
            .name(internalName)
            .superName("java/lang/Object")
            .isExternal(true)
            .build();
        classMap.put(internalName, stub);
    }

    public void scan(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            Files.walk(path)
                .filter(p -> p.toString().endsWith(".class"))
                .forEach(this::parseClassFile);
            Files.walk(path)
                .filter(p -> p.toString().endsWith(".json") && p.toString().contains(NATIVE_IMAGE_METADATA_PREFIX))
                .forEach(this::parseMetadataFile);
        } else if (path.toString().endsWith(".jar")) {
            try (JarFile jar = new JarFile(path.toFile())) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.getName().endsWith(".class")) {
                        try (InputStream is = jar.getInputStream(entry)) {
                            parseClassStream(is);
                        }
                    }
                    if (entry.getName().startsWith(NATIVE_IMAGE_METADATA_PREFIX) && entry.getName().endsWith(".json")) {
                        try (InputStream is = jar.getInputStream(entry)) {
                            parseMetadataStream(is, entry.getName());
                        }
                    }
                }
            }
        } else if (path.toString().endsWith(".class")) {
            parseClassFile(path);
        } else {
            throw new IllegalArgumentException("Unsupported file type: " + path);
        }

        buildSubclassIndex();
    }

    private void parseMetadataFile(Path path) {
        try (InputStream is = Files.newInputStream(path)) {
            parseMetadataStream(is, path.getFileName().toString());
        } catch (IOException e) {
            log.warn("Failed to parse metadata file: {}", path, e);
        }
    }

    private void parseMetadataStream(InputStream is, String fileName) {
        try {
            ReachabilityMetadata part = ReachabilityMetadataParser.parse(is, fileName);
            metadata.merge(part);
        } catch (Exception e) {
            log.warn("Failed to parse metadata stream: {}", fileName, e);
        }
    }

    private void parseClassFile(Path classFile) {
        try (InputStream is = Files.newInputStream(classFile)) {
            parseClassStream(is);
        } catch (Exception e) {
            log.error("Failed to parse class file: {}", classFile, e);
        }
    }

    private void parseClassStream(InputStream is) throws IOException {
        try {
            byte[] bytes = is.readAllBytes();
            ClassReader reader = new ClassReader(bytes);

            ClassNode.ClassNodeBuilder builder = ClassNode.builder();
            final String[] currentClassName = {null};
            final List<FieldNode> fields = new ArrayList<>();
            final List<MethodNode> methods = new ArrayList<>();

            try {
                reader.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(int version, int access, String name, String signature,
                                      String superName, String[] interfaces) {
                        currentClassName[0] = name;
                        builder.name(name)
                            .superName(superName)
                            .interfaces(interfaces != null ? Arrays.asList(interfaces) : Collections.emptyList())
                            .access(access)
                            .isInterface((access & Opcodes.ACC_INTERFACE) != 0)
                            .isExternal(false);
                    }

                    @Override
                    public FieldVisitor visitField(int access, String name, String descriptor,
                                                   String signature, Object value) {
                        fields.add(FieldNode.builder()
                            .name(name)
                            .descriptor(descriptor)
                            .type(Type.fromDescriptor(descriptor))
                            .access(access)
                            .build());
                        return null;
                    }

                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        MethodNode.MethodNodeBuilder mb = MethodNode.builder()
                            .name(name)
                            .descriptor(descriptor)
                            .returnType(TypeResolver.descToReturnType(descriptor))
                            .parameterTypes(TypeResolver.descToParamTypes(descriptor))
                            .access(access)
                            .isAbstract((access & Opcodes.ACC_ABSTRACT) != 0)
                            .isNative((access & Opcodes.ACC_NATIVE) != 0)
                            .isStatic((access & Opcodes.ACC_STATIC) != 0);
                        if (exceptions != null) {
                            mb.exceptions(Arrays.asList(exceptions));
                        }
                        methods.add(mb.build());
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
            } catch (Exception e) {
                System.err.println("ERROR during reader.accept for class " + currentClassName[0] + ": " + e);
                e.printStackTrace();
                throw e;
            }

            ClassNode classNode = builder.fields(fields).methods(methods).build();
            String name = currentClassName[0];
            if (name == null) {
                System.err.println("ERROR: currentClassName is null, class not processed");
                return;
            }
            classMap.put(name, classNode);
            classBytes.put(name, bytes);
        } catch (Exception e) {
            System.err.println("ERROR in parseClassStream: " + e.getMessage());
            e.printStackTrace();
            throw new IOException("Failed to parse class", e);
        }
    }

    private void buildSubclassIndex() {
        for (ClassNode cn : classMap.values()) {
            if (cn.getSuperName() != null && !cn.getSuperName().equals("java/lang/Object")) {
                subclasses.computeIfAbsent(cn.getSuperName(), k -> new HashSet<>()).add(cn.getName());
            }
            for (String iface : cn.getInterfaces()) {
                subclasses.computeIfAbsent(iface, k -> new HashSet<>()).add(cn.getName());
            }
        }
    }

    public ClassNode getClassNode(String internalName) {
        ClassNode node = classMap.get(internalName);
        if (node != null) return node;

        // Try to lazily load the system class from the JDK
        loadSystemClass(internalName);
        node = classMap.get(internalName);
        if (node != null) return node;

        if (!missingClasses.contains(internalName)) {
            missingClasses.add(internalName);
            log.warn("Class not found in input: {} – treated as external", internalName);
        }
        ClassNode stub = ClassNode.builder()
            .name(internalName)
            .superName("java/lang/Object")
            .isExternal(true)
            .build();
        classMap.put(internalName, stub);
        return stub;
    }

    /**
     * Parses class bytes and stores the information, but does NOT recursively load
     * the superclass and interfaces. They will be loaded lazily via getClassNode
     * when needed.
     */
    private void parseClassBytes(String internalName, byte[] bytes) throws IOException {
        ClassReader reader = new ClassReader(bytes);

        ClassNode.ClassNodeBuilder builder = ClassNode.builder();
        final String[] currentClassName = {null};
        final List<FieldNode> fields = new ArrayList<>();
        final List<MethodNode> methods = new ArrayList<>();

        try {
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(int version, int access, String name, String signature,
                                  String superName, String[] interfaces) {
                    currentClassName[0] = name;
                    builder.name(name)
                        .superName(superName)
                        .interfaces(interfaces != null ? Arrays.asList(interfaces) : Collections.emptyList())
                        .access(access)
                        .isInterface((access & Opcodes.ACC_INTERFACE) != 0)
                        .isExternal(false);
                }

                @Override
                public FieldVisitor visitField(int access, String name, String descriptor,
                                               String signature, Object value) {
                    fields.add(FieldNode.builder()
                        .name(name)
                        .descriptor(descriptor)
                        .type(Type.fromDescriptor(descriptor))
                        .access(access)
                        .build());
                    return null;
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodNode.MethodNodeBuilder mb = MethodNode.builder()
                        .name(name)
                        .descriptor(descriptor)
                        .returnType(TypeResolver.descToReturnType(descriptor))
                        .parameterTypes(TypeResolver.descToParamTypes(descriptor))
                        .access(access)
                        .isAbstract((access & Opcodes.ACC_ABSTRACT) != 0)
                        .isNative((access & Opcodes.ACC_NATIVE) != 0)
                        .isStatic((access & Opcodes.ACC_STATIC) != 0);
                    if (exceptions != null) {
                        mb.exceptions(Arrays.asList(exceptions));
                    }
                    methods.add(mb.build());
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
        } catch (Exception e) {
            throw new IOException("Failed to parse class " + internalName, e);
        }

        ClassNode classNode = builder.fields(fields).methods(methods).build();
        String name = currentClassName[0];
        if (name == null) {
            throw new IOException("Class name not found");
        }

        // Register the class (before recursion, to avoid cycles)
        classMap.put(name, classNode);
        classBytes.put(name, bytes);

        // Update the subclass index (for the current class)
        if (classNode.getSuperName() != null && !classNode.getSuperName().equals("java/lang/Object")) {
            subclasses.computeIfAbsent(classNode.getSuperName(), k -> new HashSet<>()).add(name);
        }
        for (String iface : classNode.getInterfaces()) {
            subclasses.computeIfAbsent(iface, k -> new HashSet<>()).add(name);
        }

        // REMOVED recursive calls to loadSystemClass for the superclass and interfaces.
        // They will be loaded lazily via getClassNode when needed.
    }

    public FieldNode getField(String className, String fieldName) {
        ClassNode cn = classMap.get(className);
        if (cn == null) return null;
        for (FieldNode f : cn.getFields()) {
            if (f.getName().equals(fieldName)) return f;
        }
        return null;
    }

    public byte[] getClassBytes(String internalName) {
        return classBytes.get(internalName);
    }

    public Set<String> getSubclasses(String className) {
        Set<String> result = new HashSet<>();
        collectSubclasses(className, result);
        return result;
    }

    private void collectSubclasses(String className, Set<String> accumulator) {
        Set<String> direct = subclasses.getOrDefault(className, Collections.emptySet());
        for (String child : direct) {
            if (accumulator.add(child)) {
                collectSubclasses(child, accumulator);
            }
        }
    }

    public Set<String> getAllClasses() {
        return classMap.keySet();
    }

}