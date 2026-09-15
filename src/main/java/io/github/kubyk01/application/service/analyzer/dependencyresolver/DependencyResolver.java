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
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class DependencyResolver {

    private static final String NATIVE_IMAGE_METADATA_PREFIX = "META-INF/native-image/";

    @Getter
    private final Map<String, ClassNode> classMap = new HashMap<>();
    private final Map<String, byte[]> classBytes = new HashMap<>();
    private final Map<String, Set<String>> subclasses = new HashMap<>();
    private final Set<String> missingClasses = new HashSet<>();
    private final Set<String> loadedClasses = new HashSet<>();
    @Getter
    private final ReachabilityMetadata metadata = ReachabilityMetadata.builder().build();

    private FileSystem jrtFileSystem;

    /**
     * Lazily loads a system class. Tries bytecode first, then falls back to reflection.
     */
    public synchronized void loadSystemClass(String internalName) {
        if (classMap.containsKey(internalName)) {
            return;
        }
        if (missingClasses.contains(internalName)) {
            if (!classMap.containsKey(internalName)) {
                ClassNode stub = ClassNode.builder()
                    .name(internalName)
                    .superName("java/lang/Object")
                    .isExternal(true)
                    .build();
                classMap.put(internalName, stub);
            }
            return;
        }

        byte[] bytes = null;

        // 1. Try standard ClassLoader
        try (InputStream is = ClassLoader.getSystemResourceAsStream(internalName + ".class")) {
            if (is != null) {
                bytes = is.readAllBytes();
                log.debug("Loaded system class {} via ClassLoader", internalName);
            }
        } catch (IOException e) {
            log.debug("Failed to load system class {} via ClassLoader: {}", internalName, e.getMessage());
        }

        // 2. Try jrt:/ (Java 9+)
        if (bytes == null) {
            bytes = loadClassFromJrt(internalName);
        }

        if (bytes != null) {
            try {
                parseClassBytes(internalName, bytes);
                loadedClasses.add(internalName);
                return;
            } catch (IOException e) {
                log.warn("Failed to parse system class {}: {}", internalName, e.getMessage());
            }
        }

        // 3. Fallback to reflection
        loadClassViaReflection(internalName);
    }

    /**
     * Loads class metadata via Java Reflection.
     * Works reliably for system classes that are present in the runtime ClassLoader.
     * Array types (names starting with '[') are skipped – they are not needed for struct generation.
     */
    private void loadClassViaReflection(String internalName) {
        try {
            String binaryName = internalName.replace('/', '.');
            Class<?> clazz = Class.forName(binaryName, false, ClassLoader.getSystemClassLoader());

            ClassNode.ClassNodeBuilder builder = ClassNode.builder();
            builder.name(internalName)
                .superName(clazz.getSuperclass() != null ? clazz.getSuperclass().getName().replace('.', '/') : "java/lang/Object")
                .interfaces(Arrays.stream(clazz.getInterfaces())
                    .map(c -> c.getName().replace('.', '/'))
                    .collect(Collectors.toList()))
                .access(clazz.getModifiers())
                .isInterface(clazz.isInterface())
                .isExternal(false);

            // Fields
            for (Field field : clazz.getDeclaredFields()) {
                String desc = org.objectweb.asm.Type.getDescriptor(field.getType());
                builder.field(FieldNode.builder()
                    .name(field.getName())
                    .descriptor(desc)
                    .type(Type.fromDescriptor(desc))
                    .access(field.getModifiers())
                    .build());
            }

            // Methods – also collect polymorphic signature methods
            for (Method method : clazz.getDeclaredMethods()) {
                String desc = org.objectweb.asm.Type.getMethodDescriptor(method);
                org.objectweb.asm.Type retAsmType = org.objectweb.asm.Type.getReturnType(method);
                String returnDesc = retAsmType.getDescriptor();
                org.objectweb.asm.Type[] paramAsmTypes = org.objectweb.asm.Type.getArgumentTypes(method);
                List<Type> paramIrTypes = Arrays.stream(paramAsmTypes)
                    .map(t -> Type.fromDescriptor(t.getDescriptor()))
                    .collect(Collectors.toList());

                boolean isPoly = false;
                for (java.lang.annotation.Annotation a : method.getDeclaredAnnotations()) {
                    if (a.annotationType().getName().equals("java.lang.invoke.MethodHandle$PolymorphicSignature")) {
                        isPoly = true;
                        break;
                    }
                }

                MethodNode mn = MethodNode.builder()
                    .name(method.getName())
                    .descriptor(desc)
                    .returnType(Type.fromDescriptor(returnDesc))
                    .parameterTypes(paramIrTypes)
                    .access(method.getModifiers())
                    .isAbstract(Modifier.isAbstract(method.getModifiers()))
                    .isNative(Modifier.isNative(method.getModifiers()))
                    .isStatic(Modifier.isStatic(method.getModifiers()))
                    .isPolymorphicSignature(isPoly)
                    .build();
                builder.method(mn);

                if (isPoly) {
                    builder.polymorphicMethodName(method.getName());
                }
            }

            // Constructors
            for (java.lang.reflect.Constructor<?> ctor : clazz.getDeclaredConstructors()) {
                String desc = org.objectweb.asm.Type.getConstructorDescriptor(ctor);
                org.objectweb.asm.Type[] paramAsmTypes = org.objectweb.asm.Type.getArgumentTypes(desc);
                List<Type> paramIrTypes = Arrays.stream(paramAsmTypes)
                    .map(t -> Type.fromDescriptor(t.getDescriptor()))
                    .collect(Collectors.toList());

                builder.method(MethodNode.builder()
                    .name("<init>")
                    .descriptor(desc)
                    .returnType(Type.VOID)
                    .parameterTypes(paramIrTypes)
                    .access(ctor.getModifiers())
                    .isAbstract(false)
                    .isNative(false)
                    .isStatic(false)
                    .isPolymorphicSignature(false)
                    .build());
            }

            ClassNode node = builder.build();
            classMap.put(internalName, node);
            loadedClasses.add(internalName);
            log.debug("Loaded system class {} via reflection", internalName);

        } catch (ClassNotFoundException e) {
            missingClasses.add(internalName);
            ClassNode stub = ClassNode.builder()
                .name(internalName)
                .superName("java/lang/Object")
                .isExternal(true)
                .build();
            classMap.put(internalName, stub);
            log.warn("System class {} not found even via reflection, stub created", internalName);
        } catch (Exception e) {
            log.warn("Failed to load class {} via reflection: {}", internalName, e.getMessage());
            missingClasses.add(internalName);
            ClassNode stub = ClassNode.builder()
                .name(internalName)
                .superName("java/lang/Object")
                .isExternal(true)
                .build();
            classMap.put(internalName, stub);
        }
    }

    /**
     * Loads class bytes from the jrt:/ file system.
     */
    private byte[] loadClassFromJrt(String internalName) {
        try {
            FileSystem fs = getJrtFileSystem();
            if (fs == null) return null;

            Set<String> moduleNames = new HashSet<>();
            for (Module module : ModuleLayer.boot().modules()) {
                moduleNames.add(module.getName());
            }
            if (!moduleNames.contains("java.base")) {
                moduleNames.add("java.base");
            }

            for (String moduleName : moduleNames) {
                Path classPath = fs.getPath("modules", moduleName, internalName + ".class");
                if (Files.exists(classPath)) {
                    byte[] bytes = Files.readAllBytes(classPath);
                    log.debug("Loaded system class {} from jrt:/{}/{}", internalName, moduleName, internalName + ".class");
                    return bytes;
                }
            }
        } catch (Exception e) {
            log.debug("Failed to load system class {} via jrt:/: {}", internalName, e.getMessage());
        }
        return null;
    }

    /**
     * Returns the jrt:/ FileSystem, creating it if necessary.
     */
    private FileSystem getJrtFileSystem() throws IOException {
        if (jrtFileSystem != null && jrtFileSystem.isOpen()) {
            return jrtFileSystem;
        }
        try {
            jrtFileSystem = FileSystems.getFileSystem(URI.create("jrt:/"));
            return jrtFileSystem;
        } catch (FileSystemNotFoundException e) {
            Map<String, String> env = new HashMap<>();
            env.put("java.home", System.getProperty("java.home"));
            jrtFileSystem = FileSystems.newFileSystem(URI.create("jrt:/"), env);
            return jrtFileSystem;
        }
    }

    /**
     * Forces a reload of a system class (removes stub and retries).
     */
    public synchronized void forceLoadSystemClass(String internalName) {
        ClassNode existing = classMap.get(internalName);
        if (existing != null && existing.isExternal()) {
            classMap.remove(internalName);
            classBytes.remove(internalName);
            missingClasses.remove(internalName);
        }
        loadSystemClass(internalName);
    }

    // --- The rest of the class (scan, parseClassFile, etc.) remains unchanged ---

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
            final List<String> polymorphicMethodNames = new ArrayList<>();

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

                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                                if ("Ljava/lang/invoke/MethodHandle$PolymorphicSignature;".equals(desc)) {
                                    mb.isPolymorphicSignature(true);
                                    polymorphicMethodNames.add(name);
                                }
                                return super.visitAnnotation(desc, visible);
                            }

                            @Override
                            public void visitEnd() {
                                methods.add(mb.build());
                                super.visitEnd();
                            }
                        };
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
            } catch (Exception e) {
                System.err.println("ERROR during reader.accept for class " + currentClassName[0] + ": " + e);
                e.printStackTrace();
                throw e;
            }

            ClassNode classNode = builder
                .fields(fields)
                .methods(methods)
                .polymorphicMethodNames(polymorphicMethodNames)
                .build();

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

        // Try to lazily load the system class
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

    private void parseClassBytes(String internalName, byte[] bytes) throws IOException {
        ClassReader reader = new ClassReader(bytes);
        ClassNode.ClassNodeBuilder builder = ClassNode.builder();
        final String[] currentClassName = {null};
        final List<FieldNode> fields = new ArrayList<>();
        final List<MethodNode> methods = new ArrayList<>();
        final List<String> polymorphicMethodNames = new ArrayList<>();

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

                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                            if ("Ljava/lang/invoke/MethodHandle$PolymorphicSignature;".equals(desc)) {
                                mb.isPolymorphicSignature(true);
                                polymorphicMethodNames.add(name);
                            }
                            return super.visitAnnotation(desc, visible);
                        }

                        @Override
                        public void visitEnd() {
                            methods.add(mb.build());
                            super.visitEnd();
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
        } catch (Exception e) {
            throw new IOException("Failed to parse class " + internalName, e);
        }

        ClassNode classNode = builder
            .fields(fields)
            .methods(methods)
            .polymorphicMethodNames(polymorphicMethodNames) // <--- added
            .build();

        String name = currentClassName[0];
        if (name == null) {
            throw new IOException("Class name not found");
        }

        classMap.put(name, classNode);
        classBytes.put(name, bytes);

        // Update subclass index
        if (classNode.getSuperName() != null && !classNode.getSuperName().equals("java/lang/Object")) {
            subclasses.computeIfAbsent(classNode.getSuperName(), k -> new HashSet<>()).add(name);
        }
        for (String iface : classNode.getInterfaces()) {
            subclasses.computeIfAbsent(iface, k -> new HashSet<>()).add(name);
        }
    }

    /**
     * Returns a MethodNode for the given class, method name, and descriptor.
     */
    public MethodNode getMethodNode(String className, String methodName, String descriptor) {
        ClassNode cn = classMap.get(className);
        if (cn == null) return null;
        for (MethodNode mn : cn.getMethods()) {
            if (mn.getName().equals(methodName) && mn.getDescriptor().equals(descriptor)) {
                return mn;
            }
        }
        return null;
    }

    /**
     * Searches for a method in the given class and its superclasses.
     * Returns the MethodNode if found, and stores the owner class name in foundOwner (if non-null).
     */
    public MethodNode findMethodInHierarchy(String className, String methodName, String descriptor, String[] foundOwner) {
        ClassNode cn = classMap.get(className);
        if (cn == null) {
            loadSystemClass(className);
            cn = classMap.get(className);
            if (cn == null) return null;
        }
        for (MethodNode mn : cn.getMethods()) {
            if (mn.getName().equals(methodName) && mn.getDescriptor().equals(descriptor)) {
                if (foundOwner != null) foundOwner[0] = className;
                return mn;
            }
        }
        if (cn.getSuperName() != null && !cn.getSuperName().equals("java/lang/Object")) {
            return findMethodInHierarchy(cn.getSuperName(), methodName, descriptor, foundOwner);
        }
        return null;
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