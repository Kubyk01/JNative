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
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * Resolves class metadata from the input (a JAR, a directory of .class
 * files, or a single .class file) and from the JDK's own modular runtime
 * image when the input references a system class.
 *
 * <h2>Thread-safety model</h2>
 *
 * <p>This class is used concurrently. The reachability walk, the clinit
 * sort, and the code-generation phase all call back into it — often in
 * parallel on the Reactor scheduler that {@link
 * io.github.kubyk01.application.service.Orchestrator} owns — in order to
 * resolve a method in a hierarchy, look up a field's owner, or force a
 * class to load. Every cache in this class is therefore a concurrent
 * container:</p>
 *
 * <ul>
 *   <li>{@link #classMap} and {@link #classBytes} are
 *       {@link ConcurrentHashMap}s. The map is lazily expanded from
 *       inside the parallel phases: {@link #getClassNode} and
 *       {@link #findMethodInHierarchy} force a class to load on cache
 *       miss, and that load writes into the same maps that other workers
 *       are reading.</li>
 *
 *   <li>{@link #subclasses} maps a supertype to the set of its direct
 *       subtypes. Both the outer map and every value set are concurrent:
 *       the outer map because new supertypes are observed while the
 *       subclass index is being queried, and the value sets because
 *       {@link #getSubclasses} iterates them while a concurrent class
 *       load may add to the same set. The earlier revision used a plain
 *       {@code HashMap<String, HashSet<String>>} and produced a
 *       {@link ConcurrentModificationException} inside
 *       {@code collectSubclasses} the first time a class was forced to
 *       load while another worker walked the subclass closure of a
 *       different class.</li>
 *
 *   <li>{@link #missingClasses} and {@link #loadedClasses} are concurrent
 *       key sets. They are written on the same lazy-load path as the two
 *       maps above and read by the same callers.</li>
 * </ul>
 *
 * <p>The JRT filesystem handle is guarded by the two {@code load*}
 * methods being {@code synchronized}; the handle itself is only assigned
 * on the first call to {@link #getJrtFileSystem()}, and every subsequent
 * caller simply reads the already-assigned field.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class DependencyResolver {

    private static final String NATIVE_IMAGE_METADATA_PREFIX = "META-INF/native-image/";

    @Getter
    private final Map<String, ClassNode> classMap = new ConcurrentHashMap<>();

    /**
     * Raw {@code .class} bytes for every class that has been parsed from
     * a real class file (as opposed to a reflection-only stub).
     *
     * <p>Concurrent because it is written on the same lazy-load path as
     * {@link #classMap} and read concurrently by the IR translator and
     * the reachability walk.</p>
     */
    private final Map<String, byte[]> classBytes = new ConcurrentHashMap<>();

    /**
     * Direct-subtype index: supertype internal name → set of direct
     * subtypes.
     *
     * <p>The value sets are {@link ConcurrentHashMap#newKeySet()} rather
     * than plain {@link HashSet}s because {@link #getSubclasses} iterates
     * them while a concurrent class load can add to the same set.
     * Iteration over a concurrent key set is weakly consistent: it never
     * throws, and a subclass added while a particular traversal is in
     * flight is simply observed by the next traversal. That is precisely
     * the semantics the callers need — a subtype that becomes known
     * after a subclass-closure query has started cannot have been
     * relevant to that query's answer.</p>
     */
    private final Map<String, Set<String>> subclasses = new ConcurrentHashMap<>();

    private final Set<String> missingClasses = ConcurrentHashMap.newKeySet();
    private final Set<String> loadedClasses  = ConcurrentHashMap.newKeySet();

    @Getter
    private final ReachabilityMetadata metadata = ReachabilityMetadata.builder().build();

    private FileSystem jrtFileSystem;

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
                classMap.putIfAbsent(internalName, stub);
            }
            return;
        }

        byte[] bytes = null;

        try (InputStream is = ClassLoader.getSystemResourceAsStream(internalName + ".class")) {
            if (is != null) {
                bytes = is.readAllBytes();
                log.debug("Loaded system class {} via ClassLoader", internalName);
            }
        } catch (IOException e) {
            log.debug("Failed to load system class {} via ClassLoader: {}", internalName, e.getMessage());
        }

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

        loadClassViaReflection(internalName);
    }

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

            for (Field field : clazz.getDeclaredFields()) {
                String desc = org.objectweb.asm.Type.getDescriptor(field.getType());
                builder.field(FieldNode.builder()
                        .name(field.getName())
                        .descriptor(desc)
                        .type(Type.fromDescriptor(desc))
                        .access(field.getModifiers())
                        .owner(internalName)                 // <-- FIX: declaring class
                        .build());
            }

            for (Method method : clazz.getDeclaredMethods()) {
                String desc = org.objectweb.asm.Type.getMethodDescriptor(method);
                org.objectweb.asm.Type retAsmType = org.objectweb.asm.Type.getReturnType(method);
                String returnDesc = retAsmType.getDescriptor();
                org.objectweb.asm.Type[] paramAsmTypes = org.objectweb.asm.Type.getArgumentTypes(method);
                List<Type> paramIrTypes = Arrays.stream(paramAsmTypes)
                        .map(t -> Type.fromDescriptor(t.getDescriptor()))
                        .collect(Collectors.toList());

                boolean isPoly = false;
                boolean isCS = false;
                for (java.lang.annotation.Annotation a : method.getDeclaredAnnotations()) {
                    String an = a.annotationType().getName();
                    if (an.equals("java.lang.invoke.MethodHandle$PolymorphicSignature")) {
                        isPoly = true;
                    } else if (an.equals("jdk.internal.reflect.CallerSensitive")
                            || an.equals("sun.reflect.CallerSensitive")) {
                        isCS = true;
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
                        .callerSensitive(isCS)
                        .build();
                builder.method(mn);

                if (isPoly) {
                    builder.polymorphicMethodName(method.getName());
                }
            }

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
            classMap.putIfAbsent(internalName, stub);
            log.warn("System class {} not found even via reflection, stub created", internalName);
        } catch (Exception e) {
            log.warn("Failed to load class {} via reflection: {}", internalName, e.getMessage());
            missingClasses.add(internalName);
            ClassNode stub = ClassNode.builder()
                    .name(internalName)
                    .superName("java/lang/Object")
                    .isExternal(true)
                    .build();
            classMap.putIfAbsent(internalName, stub);
        }
    }

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
                // Leading '/' is REQUIRED: the JRT filesystem resolves
                // relative paths against an internal root that does not
                // contain the module tree, so Files.exists() on a path
                // built by fs.getPath("modules", ...) silently returns
                // false. The absolute form is the only one that works.
                Path classPath = fs.getPath(
                        "/modules/" + moduleName + "/" + internalName + ".class");
                if (Files.exists(classPath)) {
                    byte[] bytes = Files.readAllBytes(classPath);
                    log.debug("Loaded system class {} from jrt:/{}/{}",
                            internalName, moduleName, internalName + ".class");
                    return bytes;
                }
            }
        } catch (Exception e) {
            log.debug("Failed to load system class {} via jrt:/: {}",
                    internalName, e.getMessage());
        }
        return null;
    }

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

    public synchronized void forceLoadSystemClass(String internalName) {
        ClassNode existing = classMap.get(internalName);
        if (existing != null) {
            boolean noBytes = classBytes.get(internalName) == null;
            if (existing.isExternal() || noBytes) {
                classMap.remove(internalName);
                classBytes.remove(internalName);
                missingClasses.remove(internalName);
                loadedClasses.remove(internalName);
            }
        }
        loadSystemClass(internalName);
    }

    /**
     * Discards any cached metadata and re-resolves the class from scratch,
     * preferring the JRT image or ClassLoader over reflection.
     */
    public synchronized void reloadSystemClass(String internalName) {
        classMap.remove(internalName);
        classBytes.remove(internalName);
        missingClasses.remove(internalName);
        loadedClasses.remove(internalName);
        loadSystemClass(internalName);
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
                                .owner(currentClassName[0])          // <-- FIX
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
                                if ("Ljdk/internal/reflect/CallerSensitive;".equals(desc)
                                        || "Lsun/reflect/CallerSensitive;".equals(desc)) {
                                    mb.callerSensitive(true);
                                }
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

            // Update the subtype index for this class. The value sets are
            // concurrent key sets (see the field declaration), so a
            // concurrent subclass-closure traversal in another thread
            // observes a consistent, non-throwing iteration.
            if (classNode.getSuperName() != null) {
                subclasses
                        .computeIfAbsent(classNode.getSuperName(),
                                k -> ConcurrentHashMap.newKeySet())
                        .add(name);
            }
            for (String iface : classNode.getInterfaces()) {
                subclasses
                        .computeIfAbsent(iface,
                                k -> ConcurrentHashMap.newKeySet())
                        .add(name);
            }
        } catch (Exception e) {
            System.err.println("ERROR in parseClassStream: " + e.getMessage());
            e.printStackTrace();
            throw new IOException("Failed to parse class", e);
        }
    }

    private void buildSubclassIndex() {
        for (ClassNode cn : classMap.values()) {
            if (cn.getSuperName() != null) {
                subclasses
                        .computeIfAbsent(cn.getSuperName(),
                                k -> ConcurrentHashMap.newKeySet())
                        .add(cn.getName());
            }
            for (String iface : cn.getInterfaces()) {
                subclasses
                        .computeIfAbsent(iface,
                                k -> ConcurrentHashMap.newKeySet())
                        .add(cn.getName());
            }
        }
    }

    public ClassNode getClassNode(String internalName) {
        ClassNode node = classMap.get(internalName);
        if (node != null) return node;

        loadSystemClass(internalName);
        node = classMap.get(internalName);
        if (node != null) return node;

        // The class was not resolvable through any of the load paths. If
        // no other thread has already installed a stub under this name,
        // install one now; using computeIfAbsent makes the "log once,
        // put once" pattern race-free even though the outer
        // loadSystemClass call already took the monitor for the load
        // itself.
        if (missingClasses.add(internalName)) {
            log.warn("Class not found in input: {} – treated as external", internalName);
        }
        ClassNode stub = ClassNode.builder()
                .name(internalName)
                .superName("java/lang/Object")
                .isExternal(true)
                .build();
        ClassNode previous = classMap.putIfAbsent(internalName, stub);
        return previous != null ? previous : stub;
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
                            .owner(currentClassName[0])              // <-- FIX
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
                            if ("Ljdk/internal/reflect/CallerSensitive;".equals(desc)
                                    || "Lsun/reflect/CallerSensitive;".equals(desc)) {
                                mb.callerSensitive(true);
                            }
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
                .polymorphicMethodNames(polymorphicMethodNames)
                .build();

        String name = currentClassName[0];
        if (name == null) {
            throw new IOException("Class name not found");
        }

        classMap.put(name, classNode);
        classBytes.put(name, bytes);

        if (classNode.getSuperName() != null) {
            subclasses
                    .computeIfAbsent(classNode.getSuperName(),
                            k -> ConcurrentHashMap.newKeySet())
                    .add(name);
        }
        for (String iface : classNode.getInterfaces()) {
            subclasses
                    .computeIfAbsent(iface,
                            k -> ConcurrentHashMap.newKeySet())
                    .add(name);
        }
    }

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

        for (MethodNode mn : cn.getMethods()) {
            if (mn.getName().equals(methodName) && mn.isPolymorphicSignature()) {
                if (foundOwner != null) foundOwner[0] = className;
                return mn;
            }
        }

        String superName = cn.getSuperName();
        if (superName != null && !superName.equals(className)) {
            MethodNode result = findMethodInHierarchy(superName, methodName, descriptor, foundOwner);
            if (result != null) return result;
        } else if (superName == null && !"java/lang/Object".equals(className)) {
            MethodNode result = findMethodInHierarchy("java/lang/Object", methodName, descriptor, foundOwner);
            if (result != null) return result;
        }
        for (String iface : cn.getInterfaces()) {
            if (iface.equals(className)) continue;
            MethodNode result = findMethodInHierarchy(iface, methodName, descriptor, foundOwner);
            if (result != null) return result;
        }
        return null;
    }

    public FieldNode getField(String className, String fieldName) {
        if (className == null || fieldName == null) return null;

        ClassNode cn = classMap.get(className);
        if (cn == null) {
            loadSystemClass(className);
            cn = classMap.get(className);
            if (cn == null) return null;
        }

        for (FieldNode f : cn.getFields()) {
            if (f.getName().equals(fieldName)) {
                if (f.getOwner() == null) {
                    f.setOwner(cn.getName());
                }
                return f;
            }
        }

        String superName = cn.getSuperName();
        if (superName != null && !superName.equals("java/lang/Object")) {
            FieldNode inherited = getField(superName, fieldName);
            if (inherited != null) return inherited;
        }

        for (String iface : cn.getInterfaces()) {
            FieldNode ifaceField = getField(iface, fieldName);
            if (ifaceField != null) return ifaceField;
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
        // The value set may be concurrently modified by another thread
        // that is lazily loading a class; ConcurrentHashMap.newKeySet()
        // makes this iteration weakly consistent and never throwing.
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