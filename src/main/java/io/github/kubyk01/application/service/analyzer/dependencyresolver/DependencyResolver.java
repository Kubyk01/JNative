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
 * Resolves class metadata from the input (JAR, directory, or single
 * .class) and from the JDK image when the input references a system class.
 * <p>
 * Thread-safe: called concurrently by the reachability walk, the clinit
 * sort, and code generation. classMap and classBytes are concurrent
 * because lazy-load writes into them while other workers read. subclasses
 * value sets are ConcurrentHashMap.newKeySet() so concurrent lazy-load
 * additions cannot break in-flight subclass-closure iteration (the earlier
 * HashMap/HashSet pair produced a CME the first time a class was forced
 * to load during another worker's getSubclasses). The JRT handle is
 * guarded by synchronizing the load methods; every subsequent caller
 * reads the already-assigned field.
 */
@Slf4j
@RequiredArgsConstructor
public class DependencyResolver {

    private record MethodKey(String owner, String name, String desc) {}
    private record MethodResult(MethodNode method, String foundOwner) {}
    private final Map<MethodKey, MethodResult> methodCache = new ConcurrentHashMap<>();

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
     * Absolute path of the top-level input archive, if {@link #scan(Path)}
     * was given a Spring Boot fat jar. Used by {@link #loadFromNestedJarIndex}
     * to reopen the outer archive and stream out the requested .class entry.
     * <p>
     * Null when the input is a plain jar, a directory, or a single .class
     * file; in those cases there are no nested jars to index.
     */
    private volatile Path inputArchive;

    private final List<Path> scannedJars = new java.util.concurrent.CopyOnWriteArrayList<>();

    public List<Path> getScannedJars() {
        return List.copyOf(scannedJars);
    }

    /**
     * Lazy index of every .class entry that lives inside a nested jar of a
     * Spring Boot fat jar.
     *
     * <p>Key   : internal class name ("com/example/Foo").
     * <p>Value : the location of the entry inside the outer archive.</p>
     *
     * <p>The index is populated by {@link #indexNestedJar} during
     * {@link #scan(Path)} — entry names are read from the nested jar's
     * central directory, no bytecode is parsed. Individual class bytes are
     * read on demand the first time {@link #loadSystemClass(String)} is
     * asked for the class and the class is not already in
     * {@link #classMap}.</p>
     *
     * <p>Entries are consumed (removed) on first successful lookup: once a
     * class has been loaded into {@link #classMap} the index entry has no
     * further use. A failed lookup also removes the entry, because the
     * subsequent fallback chain will register the class either as loaded
     * or as missing and a second attempt would only repeat the same I/O.</p>
     */
    private final Map<String, NestedClassRef> nestedClassIndex =
            new ConcurrentHashMap<>();

    /**
     * Location of one .class entry inside a nested jar of the input archive.
     *
     * <p>{@code outerJar} is the top-level archive passed to
     * {@link #scan(Path)}; {@code nestedJarEntryName} is the entry of the
     * outer archive that holds the nested jar (e.g.
     * {@code "BOOT-INF/lib/netty-common-4.1.100.Final.jar"}); and
     * {@code classEntryName} is the entry inside that nested jar
     * (e.g. {@code "io/netty/util/internal/logging/Log4JLogger.class"}).</p>
     */
    private record NestedClassRef(Path outerJar,
                                  String nestedJarEntryName,
                                  String classEntryName) {}

    /**
     * True when {@code name} denotes a Java array type, in either the
     * JVM descriptor form ({@code "[Lfoo/Bar;"}, {@code "[[I"}) or the
     * Java-name form ({@code "foo.Bar[]"}). Array classes have no
     * bytecode; they are synthesised, not loaded.
     */
    private static boolean isArrayTypeName(String name) {
        if (name == null || name.isEmpty()) return false;
        if (name.charAt(0) == '[') return true;
        int n = name.length();
        return n >= 2 && name.charAt(n - 1) == ']' && name.charAt(n - 2) == '[';
    }

    /**
     * Synthetic {@link ClassNode} for an array type: name as given,
     * superclass {@code java/lang/Object}, {@code external = true},
     * no members. Mirrors the JVM's own treatment of array classes;
     * every downstream pass skips external nodes.
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
        // cache and the ClassLoader / JRT / reflection probes.
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

        byte[] bytes = loadFromNestedJarIndex(internalName);

        if (bytes != null) {
            try {
                parseClassBytes(internalName, bytes);
                loadedClasses.add(internalName);
                return;
            } catch (IOException e) {
                log.warn("Failed to parse class {} from nested jar: {}",
                        internalName, e.getMessage());
            }
        }

        try (InputStream is = ClassLoader.getSystemResourceAsStream(internalName + ".class")) {
            if (is != null) {
                bytes = is.readAllBytes();
                log.debug("Loaded system class {} via ClassLoader", internalName);
            }
        } catch (IOException e) {
            log.debug("Failed to load system class {} via ClassLoader: {}",
                    internalName, e.getMessage());
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
                log.warn("Failed to parse system class {}: {}",
                        internalName, e.getMessage());
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
                        .owner(internalName)   // declaring class
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
            //log.warn("System class {} not found even via reflection, stub created", internalName);
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
        // Array types have no load path and no cached bytecode to refresh;
        // the synthetic node installed by loadSystemClass() is the final answer.
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
        // Array types have no class file to reload and no cached bytes to discard.
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
        this.inputArchive = path;

        if (Files.isDirectory(path)) {
            Files.walk(path)
                    .filter(p -> p.toString().endsWith(".class"))
                    .forEach(this::parseClassFile);
            Files.walk(path)
                    .filter(p -> p.toString().endsWith(".json")
                            && p.toString().contains(NATIVE_IMAGE_METADATA_PREFIX))
                    .forEach(this::parseMetadataFile);
        } else if (path.toString().endsWith(".jar")) {
            // Load the input jar's classes first (as before), so the class map is
            // fully populated before any dependency detection runs.
            scanJar(path, false);

            // Then, if the input is a Maven-built thin jar, resolve and load its
            // declared dependencies. A fat jar (Spring Boot nested jars, shaded,
            // assembly) returns an empty list and its behaviour is unchanged.
            boolean hasNestedJars = !nestedClassIndex.isEmpty();
            List<Path> dependencyJars = MavenDependencyLoader.resolveFromThinJar(
                    path, classMap.keySet(), hasNestedJars);
            if (!dependencyJars.isEmpty()) {
                System.out.println("Loading " + dependencyJars.size()
                        + " Maven dependency jar(s) declared in META-INF/maven/*/pom.xml");
                for (Path depJar : dependencyJars) {
                    System.out.println("  " + depJar.getFileName());
                    // Load the dependency's classes, skipping any class whose name
                    // is already in the map — the input jar must always win.
                    scanJar(depJar, true);
                }
                List<Path> referencedJars =
                        MavenDependencyLoader.resolveReferencedDependencies(path, dependencyJars);
                if (!referencedJars.isEmpty()) {
                    System.out.println("Loading " + referencedJars.size()
                            + " additional jar(s) that provide classes referenced by the application");
                    for (Path extraJar : referencedJars) {
                        System.out.println("  " + extraJar.getFileName());
                        scanJar(extraJar, true);
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

    /**
     * Loads the {@code .class}, nested-jar and metadata entries of one jar
     * into the class map.
     *
     * @param jarPath       the jar to read
     * @param skipExisting  when {@code true}, a class whose internal name is
     *                      already present in {@link #classMap} is skipped
     *                      instead of being re-parsed. Used when loading
     *                      dependency jars, so that a user class that shares
     *                      a fully-qualified name with a dependency class
     *                      keeps the user's version. It also avoids
     *                      re-adding the dependency's subclass edges for a
     *                      class the resolver already knows about.
     */
    private void scanJar(Path jarPath, boolean skipExisting) throws IOException {
        scannedJars.add(jarPath);
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();

                if (name.endsWith(".jar")) {
                    indexNestedJar(jar, jarPath, entry);
                    continue;
                }

                if (name.endsWith(".class")) {
                    if (skipExisting) {
                        String internalName = name.substring(0, name.length() - ".class".length());
                        if (classMap.containsKey(internalName)) {
                            continue;
                        }
                    }
                    try (InputStream is = jar.getInputStream(entry)) {
                        parseClassStream(is);
                    }
                }

                if (name.startsWith(NATIVE_IMAGE_METADATA_PREFIX)
                        && name.endsWith(".json")) {
                    try (InputStream is = jar.getInputStream(entry)) {
                        parseMetadataStream(is, name);
                    }
                }
            }
        }
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
     * Records the location of every {@code .class} entry inside a nested
     * jar. No bytecode is read: only the entry names from the central
     * directory. Metadata JSON under {@code META-INF/native-image/} is
     * parsed eagerly (small, consumed in one pass).
     */
    private void indexNestedJar(JarFile outerJar, Path outerJarPath, JarEntry nestedEntry) {
        String nestedName = nestedEntry.getName();
        try (InputStream is = outerJar.getInputStream(nestedEntry);
             ZipInputStream zis = new ZipInputStream(is)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();

                if (entryName.endsWith(".class")) {
                    String internalName =
                            entryName.substring(0, entryName.length() - 6);
                    nestedClassIndex.putIfAbsent(
                            internalName,
                            new NestedClassRef(outerJarPath, nestedName, entryName));
                } else if (entryName.startsWith(NATIVE_IMAGE_METADATA_PREFIX)
                        && entryName.endsWith(".json")) {
                    parseMetadataStream(zis, nestedName + "!/" + entryName);
                }

                zis.closeEntry();
            }
        } catch (IOException e) {
            log.warn("Failed to index nested jar {}: {}", nestedName, e.getMessage());
        }
    }

    /**
     * Reads one class's bytes from a nested jar, using the index entry.
     * Removes the entry on success or failure: the caller's fallback
     * chain will register the class either way, and a second attempt
     * would repeat the same I/O.
     */
    private byte[] loadFromNestedJarIndex(String internalName) {
        NestedClassRef ref = nestedClassIndex.remove(internalName);
        if (ref == null) {
            return null;
        }

        try (JarFile outer = new JarFile(ref.outerJar().toFile())) {
            JarEntry nestedEntry = outer.getJarEntry(ref.nestedJarEntryName());
            if (nestedEntry == null) {
                log.warn("Nested jar {} is not present in {}",
                        ref.nestedJarEntryName(), ref.outerJar());
                return null;
            }

            try (InputStream is = outer.getInputStream(nestedEntry);
                 ZipInputStream zis = new ZipInputStream(is)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.getName().equals(ref.classEntryName())) {
                        return zis.readAllBytes();
                    }
                    zis.closeEntry();
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read {} from {}!/{}: {}",
                    ref.classEntryName(), ref.outerJar(),
                    ref.nestedJarEntryName(), e.getMessage());
        }
        return null;
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
                                .classFileVersion(version)
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
                            .owner(currentClassName[0])   // declaring class
                            .constantValue(value)
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
                indexSubtype(classNode.getSuperName(), name);
            }
            for (String iface : classNode.getInterfaces()) {
                indexSubtype(iface, name);
            }
        } catch (Exception e) {
            System.err.println("ERROR in parseClassStream: " + e.getMessage());
            e.printStackTrace();
            throw new IOException("Failed to parse class", e);
        }
    }

    private void buildSubclassIndex() {
        for (ClassNode cn : classMap.values()) {
            // Array stubs are synthetic; skip them for subclass indexing.
            if (isArrayTypeName(cn.getName())) continue;

            if (cn.getSuperName() != null) {
                indexSubtype(cn.getSuperName(), cn.getName());
            }
            for (String iface : cn.getInterfaces()) {
                indexSubtype(iface, cn.getName());
            }
        }
    }

    public ClassNode getClassNode(String internalName) {
    // Array types have no class file: return a synthetic ClassNode.
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
                            .classFileVersion(version)
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
                            .owner(currentClassName[0])   // declaring class
                            .constantValue(value)
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

        // Defensive guard against array descriptors.
        if (!isArrayTypeName(name) && classNode.getSuperName() != null) {
            indexSubtype(classNode.getSuperName(), name);
        }
        if (!isArrayTypeName(name)) {
            for (String iface : classNode.getInterfaces()) {
                indexSubtype(iface, name);
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
        MethodKey key = new MethodKey(className, methodName, descriptor);
        MethodResult cached = methodCache.get(key);
        if (cached != null) {
            if (foundOwner != null) foundOwner[0] = cached.foundOwner();
            return cached.method();
        }
        MethodNode result = findMethodInHierarchyUncached(className, methodName, descriptor, foundOwner);
        if (result != null) {
            methodCache.put(key, new MethodResult(result,
                foundOwner != null ? foundOwner[0] : null));
        }
        return result;
    }

    private MethodNode findMethodInHierarchyUncached(String className, String methodName,
                                                     String descriptor, String[] foundOwner) {
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
            MethodNode r = findMethodInHierarchy(superName, methodName, descriptor, foundOwner);
            if (r != null) return r;
        } else if (superName == null && !"java/lang/Object".equals(className)) {
            MethodNode r = findMethodInHierarchy("java/lang/Object", methodName, descriptor, foundOwner);
            if (r != null) return r;
        }
        for (String iface : cn.getInterfaces()) {
            if (iface.equals(className)) continue;
            MethodNode r = findMethodInHierarchy(iface, methodName, descriptor, foundOwner);
            if (r != null) return r;
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

    private record SubclassClosure(long version, Set<String> classes) {}

    private final Map<String, SubclassClosure> subclassClosureCache = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong subclassIndexVersion =
            new java.util.concurrent.atomic.AtomicLong();

    private void indexSubtype(String parent, String child) {
        if (subclasses.computeIfAbsent(parent, k -> ConcurrentHashMap.newKeySet()).add(child)) {
            subclassIndexVersion.incrementAndGet();
        }
    }

    /** Transitive subtypes of {@code className}; read-only, cached until the subtype index changes. */
    public Set<String> getSubclasses(String className) {
        long version = subclassIndexVersion.get();
        SubclassClosure cached = subclassClosureCache.get(className);
        if (cached != null && cached.version() == version) return cached.classes();
        Set<String> result = new HashSet<>();
        collectSubclasses(className, result);
        Set<String> classes = Collections.unmodifiableSet(result);
        subclassClosureCache.put(className, new SubclassClosure(version, classes));
        return classes;
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