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

import java.io.ByteArrayInputStream;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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

    /**
     * True iff {@code name} denotes a Java array type, in any of the two
     * forms the resolver observes.
     *
     * <p>Two distinct subsystems build class-name strings for the
     * resolver, and each uses its own notation:</p>
     *
     * <ul>
     *   <li>JVM descriptors — {@code "[Lorg/springframework/expression/spel/SpelCompilerMode;"}
     *       — produced by {@link io.github.kubyk01.domain.ir.Type#toString()}
     *       for {@code Type.ArrayType} instances and by every code path
     *       that stringifies a resolved {@code Type}. The leading
     *       {@code '['} carries the dimensionality; reference components
     *       are wrapped in {@code L...;} and primitive components use
     *       their standard single-letter codes.</li>
     *
     *   <li>Java-style array names — {@code "bce/com/salonshub/view/SalonView[]"}
     *       — produced by {@code java.lang.Class.getName()} on an array
     *       class, and by any code that calls {@code getTypeName()}
     *       without the descriptor conversion step. The trailing
     *       {@code "[]"} pair carries the dimensionality; the base name
     *       uses the same {@code '/'} separator the resolver sees for
     *       ordinary classes.</li>
     * </ul>
     *
     * <p>Both forms reach the resolver through the same entry points —
     * {@link #getClassNode(String)}, {@link #loadSystemClass(String)},
     * {@link #forceLoadSystemClass(String)}, {@link #reloadSystemClass(String)}
     * — because the callers that produce them do not distinguish "a class"
     * from "a type whose name happens to be resolvable". Neither form
     * names a class with bytecode: array classes are synthesised by the
     * JVM at run time and have no {@code .class} file to load.</p>
     *
     * <p>The concrete symptom that motivated this check: a Spring Boot
     * fat-jar run produced 587 distinct warnings of the form</p>
     *
     * <pre>
     *   Class not found in input:
     *       [Lorg/springframework/expression/spel/SpelCompilerMode;
     *       – treated as external
     * </pre>
     *
     * <p>that buried the two genuine missing-class warnings — the
     * compile-only optional dependencies that really are absent from the
     * input — in a stream of false positives.</p>
     */
    private static boolean isArrayTypeName(String name) {
        if (name == null || name.isEmpty()) return false;
        if (name.charAt(0) == '[') return true;
        int n = name.length();
        return n >= 2 && name.charAt(n - 1) == ']' && name.charAt(n - 2) == '[';
    }

    /**
     * Builds the synthetic {@link ClassNode} that represents an array
     * type. The node is cached in {@link #classMap} under the array name
     * by every caller of this method, so the same array mentioned from N
     * call sites produces exactly one node.
     *
     * <p>The node is deliberately minimal, but each of its three
     * populated fields carries a specific, load-bearing meaning:</p>
     *
     * <ul>
     *   <li>{@code getName()} returns the array name exactly as the caller
     *       supplied it. A caller that asks for
     *       {@code "[Lorg/foo/Bar;"} and later reads the node's name back
     *       must observe the same string; anything else would make the
     *       caller's own cache keys and reachability bookkeeping diverge
     *       from the resolver's.</li>
     *
     *   <li>{@code getSuperName()} returns {@code java/lang/Object}. JLS
     *       §10.8 fixes this for every array type, including arrays whose
     *       component is a primitive. Consumers that walk the superclass
     *       chain — {@link #findMethodInHierarchy} among them — therefore
     *       resolve array method calls through {@code java/lang/Object},
     *       which is exactly where the JVM resolves them: the only
     *       methods an array has are the ones it inherits from
     *       {@code Object}.</li>
     *
     *   <li>{@code isExternal()} is {@code true}. Arrays have no bytecode,
     *       no {@code <clinit>}, no vtable of their own and no fields;
     *       every downstream pass that consumes a {@code ClassNode} checks
     *       this flag first and skips the node accordingly. Marking the
     *       node external keeps every one of those passes from attempting
     *       work that has no meaning for an array.</li>
     * </ul>
     *
     * <p>The node has no methods, no fields, no interfaces and no
     * constructors. That is not an under-approximation: an array class
     * genuinely declares nothing of its own, and the reflection machinery
     * that consumes {@code ClassNode} never reaches an array class through
     * the paths that would need those lists populated.</p>
     */
    private static ClassNode createArrayClassNode(String arrayName) {
        return ClassNode.builder()
                .name(arrayName)
                .superName("java/lang/Object")
                .isExternal(true)
                .build();
    }

    public synchronized void loadSystemClass(String internalName) {
        // Array types have no class file: they are synthesised by the JVM
        // at run time and cannot be loaded through any of the paths below.
        // Short-circuit with a synthetic ClassNode before the missing-class
        // cache and the ClassLoader / JRT / reflection probes so that
        //   (a) no spurious "not found" warning is logged — the caller
        //       that produced the array name did not ask for a class that
        //       failed to load, it asked for a type that never had a
        //       class file to begin with; and
        //   (b) the result is deterministic and independent of what the
        //       build host's classpath happens to contain.
        // See isArrayTypeName / createArrayClassNode for the full
        // rationale and the shape of the synthetic node.
        if (isArrayTypeName(internalName)) {
            classMap.computeIfAbsent(internalName,
                    DependencyResolver::createArrayClassNode);
            return;
        }

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
        // Array types have no load path and no cached bytecode to
        // refresh: the synthetic node installed by loadSystemClass() is
        // the final answer, and no amount of forcing will change it.
        // Short-circuit so a forced reload of an array name does not
        // remove the cached node only to have it reinstalled with an
        // identical shape a moment later.
        if (isArrayTypeName(internalName)) {
            classMap.computeIfAbsent(internalName,
                    DependencyResolver::createArrayClassNode);
            return;
        }

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
        // Array types have no class file to reload and no cached bytes to
        // discard; the synthetic ClassNode installed by loadSystemClass()
        // is the only representation that can exist for an array name.
        // See isArrayTypeName / createArrayClassNode.
        if (isArrayTypeName(internalName)) {
            classMap.computeIfAbsent(internalName,
                    DependencyResolver::createArrayClassNode);
            return;
        }

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
                    // Spring Boot "fat"/executable jars keep every
                    // dependency as a nested jar under BOOT-INF/lib/ (and
                    // the application's own classes under BOOT-INF/classes/,
                    // which are already handled above because their entry
                    // names still end in ".class"). Without this branch the
                    // outer enumeration only ever sees the handful of
                    // spring-boot-loader classes and the application's own
                    // classes; every Spring/Reactor/R2DBC/Liquibase class
                    // is invisible, so the resolver falls back to
                    // reflection stubs and logs "System class ... not found".
                    if (entry.getName().endsWith(".jar")) {
                        try (InputStream is = jar.getInputStream(entry)) {
                            parseNestedJarStream(is, entry.getName());
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
            // Jackson's ObjectMapper closes the stream it is given
            // (AUTO_CLOSE_SOURCE). When this method is called with the
            // shared ZipInputStream that walks a nested jar, that close
            // tears the whole archive down and every subsequent
            // getNextEntry()/read() throws "Stream closed", silently
            // dropping every remaining class in that jar. Buffering the
            // small JSON entry first hands Jackson a stream it may close
            // harmlessly. readAllBytes() on the ZipInputStream consumes
            // exactly the current entry, leaving the archive walk intact.
            byte[] bytes = is.readAllBytes();
            ReachabilityMetadata part = ReachabilityMetadataParser.parse(
                    new ByteArrayInputStream(bytes), fileName);
            metadata.merge(part);
        } catch (Exception e) {
            log.warn("Failed to parse metadata stream: {}", fileName, e);
        }
    }

    /**
     * Parses the classes and reachability metadata out of a jar nested
     * inside the input archive (the Spring Boot {@code BOOT-INF/lib/*.jar}
     * layout). The nested jar is streamed through a {@link ZipInputStream}
     * so it never has to be materialised on disk; each {@code .class}
     * entry is handed to {@link #parseClassStream(InputStream)}, which
     * consumes exactly one entry because {@code readAllBytes()} stops at
     * the entry boundary.
     */
    private void parseNestedJarStream(InputStream is, String nestedName) {
        try (ZipInputStream zis = new ZipInputStream(is)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (entryName.endsWith(".class")) {
                    try {
                        parseClassStream(zis);
                    } catch (IOException e) {
                        log.warn("Failed to parse class {} from nested jar {}: {}",
                                entryName, nestedName, e.getMessage());
                    }
                } else if (entryName.startsWith(NATIVE_IMAGE_METADATA_PREFIX)
                        && entryName.endsWith(".json")) {
                    parseMetadataStream(zis, nestedName + "!/" + entryName);
                }
                zis.closeEntry();
            }
        } catch (IOException e) {
            log.warn("Failed to parse nested jar {}: {}", nestedName, e.getMessage());
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
            // Array stubs are synthetic: the JVM's array classes are
            // subtypes of Object per JLS §10.8, but they are not classes
            // with bytecode, they are never the target of a virtual or
            // interface dispatch through the subclass index, and the
            // resolver caches one synthetic node per array-type name it
            // observes. Registering those nodes under java/lang/Object
            // would make every subclass-of-Object query return an
            // unbounded list of array types that have no relevant
            // structure, and would grow the subclass index by one entry
            // per distinct array type mentioned anywhere in the image.
            if (isArrayTypeName(cn.getName())) continue;

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
        // Array types have no class file: return a synthetic ClassNode
        // rather than probing every load path and logging a false-positive
        // "not found" warning. See isArrayTypeName /
        // createArrayClassNode for the shape of the node and why each
        // field has the value it has.
        if (isArrayTypeName(internalName)) {
            return classMap.computeIfAbsent(internalName,
                    DependencyResolver::createArrayClassNode);
        }

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

        // The name that reaches this point always comes from a real
        // ClassReader.visit callback, so it is never an array descriptor;
        // the guard is defensive and mirrors the one in buildSubclassIndex.
        if (!isArrayTypeName(name) && classNode.getSuperName() != null) {
            subclasses
                    .computeIfAbsent(classNode.getSuperName(),
                            k -> ConcurrentHashMap.newKeySet())
                    .add(name);
        }
        if (!isArrayTypeName(name)) {
            for (String iface : classNode.getInterfaces()) {
                subclasses
                        .computeIfAbsent(iface,
                                k -> ConcurrentHashMap.newKeySet())
                        .add(name);
            }
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