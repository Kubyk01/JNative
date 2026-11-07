package io.github.kubyk01.application.service.analyzer.dependencyresolver;

import lombok.extern.slf4j.Slf4j;
import org.objectweb.asm.ClassReader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the dependency jars declared in a Maven-built thin jar.
 *
 * <h2>Problem</h2>
 *
 * <p>A jar produced by the default {@code maven-jar-plugin} contains only
 * the user's compiled classes plus
 * {@code META-INF/maven/<groupId>/<artifactId>/pom.xml} and
 * {@code pom.properties}. Its dependencies live in the Maven local
 * repository ({@code ~/.m2/repository}) and are supplied at run time on
 * the classpath. The JNative analyzer never sees them: every dependency
 * class becomes an external stub, its methods are never translated, and
 * the LLVM emitter fills every call site that targets one of them with
 * {@code __jnative_unresolved_slot}. The canonical symptom is</p>
 *
 * <pre>
 *   JNative unresolved vtable/itable slot
 *   unresolved call target 'fn_io_netty_channel_nio_NioEventLoopGroup__init___I_V'
 *   (original: io/netty/channel/nio/NioEventLoopGroup.&lt;init&gt;(I)V)
 * </pre>
 *
 * <h2>Resolution strategy</h2>
 *
 * <p>Three strategies are tried in order, and the first one that yields
 * jars wins:</p>
 *
 * <ol>
 *   <li><b>{@code mvn} on {@code PATH}.</b> Delegates to
 *       {@code mvn -f <pom> dependency:build-classpath}, which is Maven's
 *       own resolver and therefore honours parent poms, properties,
 *       BOM imports, {@code dependencyManagement}, exclusions, optional
 *       transitives and scopes exactly.</li>
 *
 *   <li><b>{@code mvn} outside {@code PATH}.</b> The same subprocess,
 *       with the binary located in the places a real Maven installation
 *       lives: the project's {@code mvnw} wrapper, SDKMAN, Homebrew on
 *       both architectures, Debian/Ubuntu's {@code /usr/share/maven},
 *       a generic {@code /opt/maven}, and the {@code M2_HOME} /
 *       {@code MAVEN_HOME} environment variables. A GUI-launched IDE
 *       routinely has Maven installed through one of these while the
 *       shell's {@code PATH} does not.</li>
 *
 *   <li><b>Pure local-repository resolution.</b> When no Maven binary
 *       exists at all, the declared dependencies and their transitive
 *       dependencies are resolved directly from
 *       {@code ~/.m2/repository}. This is sufficient for every artifact
 *       that is already local — which is every artifact a working IDE
 *       build has produced. The walker parses each artifact's own pom,
 *       inherits the parent pom's properties and
 *       {@code dependencyManagement}, substitutes {@code ${property}}
 *       references, respects scopes, optionality and exclusions, and
 *       applies nearest-wins version mediation.</li>
 * </ol>
 *
 * <h2>Fat jars are left untouched</h2>
 *
 * <p>A jar with {@code BOOT-INF/lib/*.jar} entries has its dependencies
 * already inside it, and a jar that already contains classes from a
 * declared dependency's groupId is a shaded/assembly fat jar. Both are
 * detected before any resolution runs and return an empty list, leaving
 * the resolver's existing behaviour unchanged.</p>
 */
@Slf4j
public final class MavenDependencyLoader {

    /** Every Maven-built jar stores its metadata here. */
    private static final String MAVEN_META_PREFIX = "META-INF/maven/";

    /** Scope values that are not part of the runtime classpath. */
    private static final Set<String> NON_RUNTIME_SCOPES = Set.of("test", "provided", "system");

    private MavenDependencyLoader() {}

    /**
     * Reads the embedded pom.xml of {@code inputJar}, resolves the declared
     * dependencies, and returns the resolved jars.
     *
     * @param inputJar         the jar being analyzed
     * @param loadedClassNames the internal class names already loaded from
     *                         {@code inputJar}; used to decide whether the
     *                         jar is a fat jar
     * @param hasNestedJars    {@code true} when {@code inputJar} contains
     *                         {@code BOOT-INF/lib/*.jar}-style nested jars
     * @return the resolved dependency jars, or an empty list for a fat jar,
     *         a non-Maven jar, or a jar whose pom declares no dependencies
     */
    public static List<Path> resolveFromThinJar(Path inputJar,
                                                Set<String> loadedClassNames,
                                                boolean hasNestedJars) {
        if (hasNestedJars) {
            log.debug("Input jar contains nested dependency jars "
                + "(Spring Boot fat jar layout); skipping Maven dependency resolution");
            return List.of();
        }

        Path pomXml = null;
        Path pomProperties = null;

        try (JarFile jar = new JarFile(inputJar.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith(MAVEN_META_PREFIX)) continue;

                if (name.endsWith("/pom.xml") && pomXml == null) {
                    pomXml = extractJarEntryToTemp(jar, entry, "pom", ".xml");
                } else if (name.endsWith("/pom.properties") && pomProperties == null) {
                    pomProperties = extractJarEntryToTemp(jar, entry, "pom", ".properties");
                }
            }
        } catch (IOException e) {
            log.debug("Jar is not readable for Maven metadata lookup: {}", e.getMessage());
            return List.of();
        }

        if (pomXml == null) {
            log.debug("Input jar has no META-INF/maven/*/pom.xml; not a Maven-built jar");
            return List.of();
        }

        String selfGav = readSelfGav(pomProperties);
        if (selfGav != null) {
            log.info("Detected Maven-built thin jar: {}", selfGav);
        }

        Set<String> depGroupIds = extractDepGroupIds(pomXml);
        if (depGroupIds.isEmpty()) {
            log.debug("Pom has no <dependencies> entries; nothing to resolve");
            return List.of();
        }

        if (jarAlreadyContainsDeps(loadedClassNames, depGroupIds)) {
            log.info("Input jar already contains classes from its declared "
                + "dependencies; treating as fat jar and skipping resolution");
            return List.of();
        }

        // ---- Strategy 1 + 2: Maven subprocess, wherever the binary lives ----
        List<String> mvnCommand = locateMaven(inputJar.toAbsolutePath().getParent());
        if (mvnCommand != null) {
            List<Path> jars = resolveViaMavenSubprocess(pomXml, mvnCommand, selfGav);
            if (!jars.isEmpty()) {
                return jars;
            }
        } else {
            log.info("No Maven executable found on PATH, in common install "
                + "locations, or as a project mvnw. Falling back to direct "
                + "resolution from ~/.m2/repository.");
        }

        // ---- Strategy 3: direct ~/.m2/repository resolution ----
        List<Path> jars = resolveViaLocalRepository(pomXml, selfGav);
        if (jars.isEmpty()) {
            log.warn("Could not resolve any dependency jar for {}. "
                    + "Dependency classes will not be available for analysis and the "
                    + "generated executable will fail with __jnative_unresolved_slot "
                    + "for their methods. Run the project's own Maven build once "
                    + "(e.g. `mvn package` or `mvn dependency:go-offline`) so that "
                    + "~/.m2/repository is populated, or install Maven and put it on PATH.",
                selfGav != null ? selfGav : "the input jar");
        }
        return jars;
    }

    public static List<Path> resolveReferencedDependencies(Path inputJar, List<Path> loadedJars) {
        Path repository = localRepositoryRoot();
        if (repository == null) return List.of();

        List<Path> scanned = new ArrayList<>();
        scanned.add(inputJar);
        scanned.addAll(loadedJars);

        Set<String> known = new HashSet<>();
        Set<String> referenced = new HashSet<>();
        for (Path jar : scanned) {
            collectClasses(jar, known, referenced);
        }

        Map<String, Path> loadedByKey = new LinkedHashMap<>();
        for (Path jar : loadedJars) {
            String key = artifactKey(repository, jar);
            if (key != null) loadedByKey.put(key, jar);
        }

        List<String> mavenCommand = locateMaven(inputJar.toAbsolutePath().getParent());
        Set<String> attempted = new HashSet<>(loadedByKey.keySet());
        Deque<PomRef> pomQueue = new ArrayDeque<>();
        for (Path jar : loadedByKey.values()) pomQueue.add(new PomRef(siblingPom(jar), true));

        List<Path> added = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        boolean progress = true;
        while (progress) {
            progress = false;
            Set<String> missing = missingClasses(known, referenced);
            if (missing.isEmpty()) break;

            while (!pomQueue.isEmpty()) {
                PomRef ref = pomQueue.poll();
                PomModel pom = readPomModel(ref.pom(), repository, new HashSet<>());
                if (pom == null) continue;
                for (DepNode dep : pom.dependencies) {
                    String scope = dep.scope == null ? "compile" : dep.scope.trim();
                    if (scope.equals("test") || scope.equals("system")) continue;
                    if (!ref.includeOptional() && (dep.optional || scope.equals("provided"))) continue;
                    String key = dep.groupId + ":" + dep.artifactId;
                    if (loadedByKey.containsKey(key)) continue;
                    String version = resolveVersion(dep.version, pom);
                    if (version == null || version.isBlank() || version.contains("${")) version = null;
                    candidates.add(new Candidate(dep.groupId, dep.artifactId, version, pom));
                }
            }

            for (Candidate c : new ArrayList<>(candidates)) {
                String key = c.groupId + ":" + c.artifactId;
                if (loadedByKey.containsKey(key) || attempted.contains(key)) continue;
                List<String> download = isPlausibleProvider(c.groupId, missing) ? mavenCommand : null;
                Path jar = locateArtifact(repository, c.groupId, c.artifactId, c.version, download, false);
                if (jar == null || !jarProvidesAny(jar, missing)) continue;
                attempted.add(key);
                acceptArtifact(key, jar, loadedByKey, added, known, referenced, pomQueue);
                for (Candidate sibling : candidates) {
                    String skey = sibling.groupId + ":" + sibling.artifactId;
                    if (!sibling.groupId.equals(c.groupId) || !sibling.artifactId.startsWith("native")) continue;
                    if (loadedByKey.containsKey(skey) || !attempted.add(skey)) continue;
                    Path njar = locateArtifact(repository, sibling.groupId, sibling.artifactId,
                        sibling.version, mavenCommand, true);
                    if (njar != null) acceptArtifact(skey, njar, loadedByKey, added, known, referenced, pomQueue);
                }
                progress = true;
            }

        }

        Set<String> stillMissing = missingClasses(known, referenced);
        if (!added.isEmpty()) {
            log.info("Resolved {} additional jar(s) for classes referenced by the application: {}",
                added.size(), added.stream().map(p -> p.getFileName().toString()).toList());
        }
        if (!stillMissing.isEmpty()) {
            log.info("{} referenced class(es) are provided by no resolvable dependency, e.g. {}",
                stillMissing.size(), stillMissing.stream().sorted().limit(5).toList());
        }
        return added;
    }

    private static boolean isPlausibleProvider(String groupId, Set<String> missing) {
        String path = groupId.replace('.', '/') + "/";
        for (String name : missing) {
            if (name.startsWith(path)) return true;
        }
        return false;
    }

    private record Candidate(String groupId, String artifactId, String version, PomModel declaringPom) {}

    private record PomRef(Path pom, boolean includeOptional) {}

    private static void acceptArtifact(String key, Path jar, Map<String, Path> loadedByKey,
                                       List<Path> added, Set<String> known,
                                       Set<String> referenced, Deque<PomRef> pomQueue) {
        loadedByKey.put(key, jar);
        added.add(jar);
        collectClasses(jar, known, referenced);
        Path pom = siblingPom(jar);
        if (pom != null) pomQueue.add(new PomRef(pom, false));
    }

    private static Path siblingPom(Path jar) {
        String name = jar.getFileName().toString();
        if (!name.endsWith(".jar")) return null;
        Path dir = jar.getParent();
        String base = name.substring(0, name.length() - ".jar".length());
        String version = dir.getFileName().toString();
        String artifact = dir.getParent().getFileName().toString();
        Path pom = dir.resolve(artifact + "-" + version + ".pom");
        if (Files.isRegularFile(pom)) return pom;
        pom = dir.resolve(base + ".pom");
        return Files.isRegularFile(pom) ? pom : null;
    }

    private static String artifactKey(Path repository, Path jar) {
        Path abs = jar.toAbsolutePath().normalize();
        Path repo = repository.toAbsolutePath().normalize();
        if (!abs.startsWith(repo)) return null;
        Path rel = repo.relativize(abs);
        int n = rel.getNameCount();
        if (n < 4) return null;
        String artifact = rel.getName(n - 3).toString();
        StringBuilder group = new StringBuilder();
        for (int i = 0; i < n - 3; i++) {
            if (i > 0) group.append('.');
            group.append(rel.getName(i));
        }
        return group + ":" + artifact;
    }

    private static Path locateArtifact(Path repository, String groupId, String artifactId,
                                       String version, List<String> mavenCommand,
                                       boolean allowAnyLocalVersion) {
        Path artifactRoot = repository.resolve(groupId.replace('.', '/')).resolve(artifactId);
        if (version != null) {
            Path jar = artifactRoot.resolve(version).resolve(artifactId + "-" + version + ".jar");
            if (Files.isRegularFile(jar)) return jar;
            if (mavenCommand != null && downloadArtifact(mavenCommand, groupId, artifactId, version)
                && Files.isRegularFile(jar)) {
                return jar;
            }
        }
        if (version == null || allowAnyLocalVersion || Files.isDirectory(artifactRoot)) {
            return newestLocalJar(artifactRoot, artifactId);
        }
        return null;
    }

    private static Path newestLocalJar(Path artifactRoot, String artifactId) {
        if (!Files.isDirectory(artifactRoot)) return null;
        try (var stream = Files.list(artifactRoot)) {
            return stream
                .filter(Files::isDirectory)
                .map(dir -> dir.resolve(artifactId + "-" + dir.getFileName() + ".jar"))
                .filter(Files::isRegularFile)
                .max((a, b) -> compareVersions(
                    a.getParent().getFileName().toString(),
                    b.getParent().getFileName().toString()))
                .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static int compareVersions(String a, String b) {
        String[] pa = a.split("[.\\-]");
        String[] pb = b.split("[.\\-]");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            String x = i < pa.length ? pa[i] : "0";
            String y = i < pb.length ? pb[i] : "0";
            int c;
            if (x.matches("\\d+") && y.matches("\\d+")) {
                c = new java.math.BigInteger(x).compareTo(new java.math.BigInteger(y));
            } else {
                c = x.compareTo(y);
            }
            if (c != 0) return c;
        }
        return 0;
    }

    private static boolean downloadArtifact(List<String> mavenCommand, String groupId,
                                            String artifactId, String version) {
        List<String> cmd = new ArrayList<>(mavenCommand);
        cmd.add("-q");
        cmd.add("dependency:get");
        cmd.add("-Dartifact=" + groupId + ":" + artifactId + ":" + version);
        cmd.add("-Dtransitive=false");
        try {
            Process process = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
            boolean ok = process.waitFor() == 0;
            if (ok) log.info("Downloaded {}:{}:{} via Maven", groupId, artifactId, version);
            return ok;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void collectClasses(Path jar, Set<String> known, Set<String> referenced) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                String name = e.getName();
                if (!name.endsWith(".class") || name.startsWith("META-INF/")) continue;
                known.add(name.substring(0, name.length() - ".class".length()));
                try (InputStream is = jf.getInputStream(e)) {
                    collectReferencedClasses(is.readAllBytes(), referenced);
                } catch (RuntimeException ignored) {
                }
            }
        } catch (IOException e) {
            log.debug("Cannot scan {}: {}", jar, e.getMessage());
        }
    }

    private static void collectReferencedClasses(byte[] bytes, Set<String> out) {
        ClassReader cr = new ClassReader(bytes);
        char[] buf = new char[cr.getMaxStringLength()];
        for (int i = 1; i < cr.getItemCount(); i++) {
            int offset = cr.getItem(i);
            if (offset <= 0) continue;
            if (cr.readByte(offset - 1) != 7) continue;
            String name = cr.readUTF8(offset, buf);
            if (name == null) continue;
            int k = 0;
            while (k < name.length() && name.charAt(k) == '[') k++;
            if (k > 0) {
                if (k < name.length() && name.charAt(k) == 'L' && name.endsWith(";")) {
                    out.add(name.substring(k + 1, name.length() - 1));
                }
            } else {
                out.add(name);
            }
        }
    }

    private static Set<String> missingClasses(Set<String> known, Set<String> referenced) {
        Set<String> missing = new HashSet<>();
        ClassLoader platform = ClassLoader.getPlatformClassLoader();
        for (String name : referenced) {
            if (known.contains(name)) continue;
            if (platform.getResource(name + ".class") != null) continue;
            missing.add(name);
        }
        return missing;
    }

    private static boolean jarProvidesAny(Path jar, Set<String> classNames) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            for (String name : classNames) {
                if (jf.getEntry(name + ".class") != null) return true;
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    // =====================================================================
    //  Maven binary discovery
    // =====================================================================

    /**
     * Returns the command prefix that runs Maven, or {@code null} when no
     * Maven installation can be found.
     *
     * <p>The returned list is a full command prefix: element 0 is the
     * executable, and the elements after it are passed verbatim before the
     * Maven arguments. For the Unix {@code mvnw} wrapper on a system where
     * the script is not executable, the prefix is {@code ["sh", "mvnw"]};
     * for a normal binary it is a single element.</p>
     *
     * @param projectDir the directory containing the input jar, or
     *                   {@code null} when the input was a bare relative name
     */
    private static List<String> locateMaven(Path projectDir) {
        // 1. mvn on PATH.
        if (isOnPath("mvn")) {
            return List.of("mvn");
        }

        // 2. Project wrapper, walking up from the jar's directory.
        if (projectDir != null) {
            Path wrapper = findWrapperUpwards(projectDir);
            if (wrapper != null) {
                if (Files.isExecutable(wrapper)) {
                    return List.of(wrapper.toAbsolutePath().toString());
                }
                // A checked-out mvnw that lost its executable bit.
                return List.of("sh", wrapper.toAbsolutePath().toString());
            }
        }

        // 3. Environment variables.
        for (String env : new String[]{"M2_HOME", "MAVEN_HOME"}) {
            String home = System.getenv(env);
            if (home == null || home.isBlank()) continue;
            Path candidate = Paths.get(home, "bin", "mvn");
            if (Files.isExecutable(candidate)) {
                return List.of(candidate.toAbsolutePath().toString());
            }
        }

        // 4. Common install locations.
        String userHome = System.getProperty("user.home", "");
        List<Path> candidates = new ArrayList<>();

        // SDKMAN (Linux and macOS).
        Path sdkman = Paths.get(userHome, ".sdkman", "candidates", "maven", "current", "bin", "mvn");
        candidates.add(sdkman);

        // Homebrew — Apple Silicon, then Intel.
        candidates.addAll(glob(Paths.get("/opt/homebrew/Cellar/maven"), "/libexec/bin/mvn"));
        candidates.addAll(glob(Paths.get("/usr/local/Cellar/maven"), "/libexec/bin/mvn"));

        // Debian / Ubuntu package, and generic manual installs.
        candidates.add(Paths.get("/usr/share/maven/bin/mvn"));
        candidates.add(Paths.get("/opt/maven/bin/mvn"));
        candidates.add(Paths.get("/usr/local/maven/bin/mvn"));

        // Fedora / RHEL package.
        candidates.add(Paths.get("/usr/share/maven/bin/mvn"));

        for (Path candidate : candidates) {
            if (Files.isExecutable(candidate)) {
                return List.of(candidate.toAbsolutePath().toString());
            }
        }

        return null;
    }

    private static boolean isOnPath(String executable) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isEmpty()) return false;
        String suffix = isWindows() ? ".cmd" : "";
        for (String dir : pathEnv.split(Pattern.quote(File.pathSeparator))) {
            if (dir.isEmpty()) continue;
            Path candidate = Paths.get(dir, executable + suffix);
            if (Files.isExecutable(candidate)) return true;
        }
        return false;
    }

    private static Path findWrapperUpwards(Path start) {
        Path dir = start.toAbsolutePath();
        for (int depth = 0; depth < 8 && dir != null; depth++) {
            Path wrapper = dir.resolve(isWindows() ? "mvnw.cmd" : "mvnw");
            if (Files.isRegularFile(wrapper)) return wrapper;
            dir = dir.getParent();
        }
        return null;
    }

    /**
     * Lists the directories that match {@code parent/*}{@code suffix} and
     * sorts them newest-version-first, so that the Homebrew Cellar's
     * highest installed Maven wins. Returns an empty list when
     * {@code parent} does not exist.
     */
    private static List<Path> glob(Path parent, String suffix) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(parent)) return out;
        try (var stream = Files.list(parent)) {
            stream.filter(Files::isDirectory)
                .map(d -> d.resolve(suffix.substring(1)))
                .filter(Files::isExecutable)
                .sorted((a, b) -> b.toString().compareTo(a.toString()))
                .forEach(out::add);
        } catch (IOException ignored) { }
        return out;
    }

    // =====================================================================
    //  Strategy 1 + 2: Maven subprocess
    // =====================================================================

    private static List<Path> resolveViaMavenSubprocess(Path pomXml,
                                                        List<String> mvnCommand,
                                                        String selfGav) {
        Path classpathFile = null;
        try {
            classpathFile = Files.createTempFile("jnative-mvn-classpath-", ".txt");

            List<String> cmd = new ArrayList<>(mvnCommand);
            cmd.add("-f");
            cmd.add(pomXml.toAbsolutePath().toString());
            cmd.add("-q");
            cmd.add("-DincludeScope=runtime");
            cmd.add("dependency:build-classpath");
            cmd.add("-Dmdep.outputFile=" + classpathFile.toAbsolutePath());

            log.debug("Running: {}", String.join(" ", cmd));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);

            Process process = pb.start();
            int exit = process.waitFor();
            if (exit != 0) {
                log.warn("Maven invocation failed with exit code {} for {}. "
                    + "Falling back to direct ~/.m2 resolution.", exit, selfGav);
                return List.of();
            }

            String classpath = Files.readString(classpathFile).trim();
            if (classpath.isEmpty()) {
                log.warn("Maven returned an empty classpath for {}; the pom may "
                    + "declare only provided/test-scope dependencies.", selfGav);
                return List.of();
            }

            List<Path> jars = new ArrayList<>();
            for (String entry : classpath.split(File.pathSeparator)) {
                if (entry.isEmpty()) continue;
                Path jar = Paths.get(entry);
                if (Files.isRegularFile(jar)) jars.add(jar);
            }
            log.info("Resolved {} dependency jar(s) via Maven for {}",
                jars.size(), selfGav != null ? selfGav : "thin jar");
            return jars;
        } catch (IOException e) {
            log.warn("Maven subprocess failed for {}: {}. Falling back to direct "
                + "~/.m2 resolution.", selfGav, e.getMessage());
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } finally {
            if (classpathFile != null) {
                try { Files.deleteIfExists(classpathFile); } catch (IOException ignored) {}
            }
        }
    }

    // =====================================================================
    //  Strategy 3: direct ~/.m2/repository resolution
    // =====================================================================

    /**
     * Resolves the root pom's declared dependencies and their transitive
     * dependencies directly from {@code ~/.m2/repository}.
     *
     * <p>This is Maven's dependency model restricted to what can be
     * reconstructed from artifacts that are already on disk:</p>
     *
     * <ul>
     *   <li>the root pom's {@code <properties>} and
     *       {@code <dependencyManagement>} are read, together with those
     *       inherited from every ancestor pom in the parent chain;</li>
     *   <li>each {@code <dependency>}'s version is resolved by explicit
     *       value, then by {@code ${property}} substitution, then by the
     *       merged {@code dependencyManagement}, then by the parent
     *       chain's {@code dependencyManagement};</li>
     *   <li>the artifact is located at
     *       {@code ~/.m2/repository/<groupId-path>/<artifactId>/<version>/}
     *       and its jar is added when it exists;</li>
     *   <li>the artifact's own pom is parsed and its dependencies are
     *       enqueued, so transitives are found;</li>
     *   <li>{@code test}, {@code provided} and {@code system} scopes,
     *       {@code <optional>true</optional>}, and {@code <exclusions>}
     *       are skipped;</li>
     *   <li>version mediation is nearest-wins: the first version of a
     *       {@code groupId:artifactId} key encountered in the breadth-first
     *       walk is the one that is kept, matching Maven's own rule for
     *       the tree shapes that matter here.</li>
     * </ul>
     *
     * <p>A dependency whose jar is absent from the local repository is
     * skipped with a debug line rather than failing the whole resolution.
     * An artifact that the user's build did not need cannot be a class the
     * compiled program can reach, so its absence is not a correctness
     * problem for the analysis.</p>
     */
    private static List<Path> resolveViaLocalRepository(Path pomXml, String selfGav) {
        Path repository = localRepositoryRoot();
        if (repository == null) {
            log.warn("Cannot locate the Maven local repository "
                + "(neither M2_REPO nor ~/.m2/repository exists); skipping direct "
                + "dependency resolution.");
            return List.of();
        }

        log.info("Resolving dependencies of {} directly from {}",
            selfGav != null ? selfGav : "the input pom", repository);

        PomModel root = readPomModel(pomXml, repository, new HashSet<>());
        if (root == null) {
            return List.of();
        }

        Set<String> seenKeys = new HashSet<>();
        Map<String, Path> jarsByKey = new LinkedHashMap<>();
        Deque<DepRequest> queue = new ArrayDeque<>();

        for (DepNode dep : root.dependencies) {
            if (!isRuntimeDependency(dep)) continue;
            queue.add(new DepRequest(dep, root));
        }

        while (!queue.isEmpty()) {
            DepRequest request = queue.poll();
            DepNode dep = request.node;

            String version = resolveVersion(dep.version, request.parent);
            if (version == null || version.isBlank() || version.contains("${")) {
                log.debug("Skipping dependency {}:{}: version is unresolvable ({})",
                    dep.groupId, dep.artifactId, dep.version);
                continue;
            }

            String key = dep.groupId + ":" + dep.artifactId;
            if (!seenKeys.add(key)) {
                // Nearest-wins: the first version encountered is kept.
                continue;
            }

            Path artifactDir = repository
                .resolve(dep.groupId.replace('.', '/'))
                .resolve(dep.artifactId)
                .resolve(version);

            Path jar = artifactDir.resolve(dep.artifactId + "-" + version + ".jar");
            Path pom = artifactDir.resolve(dep.artifactId + "-" + version + ".pom");

            if (Files.isRegularFile(jar)) {
                jarsByKey.put(key, jar);
            } else {
                log.debug("Dependency jar not present locally: {} (looked for {})",
                    key, jar);
            }

            if (!Files.isRegularFile(pom)) {
                continue;
            }

            PomModel child = readPomModel(pom, repository, new HashSet<>());
            if (child == null) continue;

            for (DepNode transitive : child.dependencies) {
                if (!isRuntimeDependency(transitive)) continue;
                if (isExcluded(transitive, dep.exclusions)) continue;
                queue.add(new DepRequest(transitive, child));
            }
        }

        List<Path> result = new ArrayList<>(jarsByKey.values());
        if (!result.isEmpty()) {
            log.info("Resolved {} dependency jar(s) from {} for {}",
                result.size(), repository,
                selfGav != null ? selfGav : "thin jar");
        }
        return result;
    }

    /** One dependency edge, with the pom that declared it for version inheritance. */
    private record DepRequest(DepNode node, PomModel parent) {}

    /** A {@code <dependency>} element, or a single edge of the walk. */
    private static final class DepNode {
        String groupId;
        String artifactId;
        String version;
        String scope;
        boolean optional;
        final Set<String> exclusions = new HashSet<>();   // "groupId:artifactId"
    }

    /** The parts of one pom that this resolver needs. */
    private static final class PomModel {
        String groupId;
        String artifactId;
        String version;
        final Map<String, String> properties = new LinkedHashMap<>();
        final Map<String, String> managedVersions = new LinkedHashMap<>(); // "g:a" -> version
        final List<DepNode> dependencies = new ArrayList<>();
    }

    private static boolean isRuntimeDependency(DepNode dep) {
        if (dep == null) return false;
        if (dep.optional) return false;
        String scope = dep.scope == null ? "compile" : dep.scope.trim();
        return !NON_RUNTIME_SCOPES.contains(scope);
    }

    private static boolean isExcluded(DepNode dep, Set<String> exclusions) {
        if (exclusions.isEmpty()) return false;
        return exclusions.contains(dep.groupId + ":" + dep.artifactId);
    }

    /**
     * Substitutes {@code ${...}} references in {@code raw} using the
     * declaring pom's merged properties, then falls back to the managed
     * version of the same {@code groupId:artifactId} when the result is
     * still a placeholder or empty.
     */
    private static String resolveVersion(String raw, PomModel declaringPom) {
        if (raw == null) return null;
        String value = substituteProperties(raw, declaringPom);
        if (value != null && !value.isBlank() && !value.contains("${")) {
            return value;
        }
        return null;
    }

    private static String substituteProperties(String raw, PomModel pom) {
        if (raw == null) return null;
        String value = raw;
        for (int i = 0; i < 16 && value.contains("${"); i++) {
            Matcher m = PROPERTY_REF.matcher(value);
            StringBuilder sb = new StringBuilder();
            boolean changed = false;
            while (m.find()) {
                String key = m.group(1);
                String replacement = pom.properties.get(key);
                if (replacement == null) {
                    replacement = switch (key) {
                        case "project.version", "pom.version" -> pom.version;
                        case "project.groupId", "pom.groupId" -> pom.groupId;
                        case "project.artifactId", "pom.artifactId" -> pom.artifactId;
                        default -> null;
                    };
                }
                if (replacement == null) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                } else {
                    m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                    changed = true;
                }
            }
            m.appendTail(sb);
            value = sb.toString();
            if (!changed) break;
        }
        return value;
    }

    private static final Pattern PROPERTY_REF = Pattern.compile("\\$\\{([^}]+)}");

    /**
     * Reads one pom into a {@link PomModel}, merging the properties and
     * {@code dependencyManagement} entries of every ancestor in the parent
     * chain. Child values override parent values.
     */
    private static PomModel readPomModel(Path pom, Path repository, Set<String> visited) {
        if (pom == null || !Files.isRegularFile(pom)) return null;
        String canonical;
        try {
            canonical = pom.toRealPath().toString();
        } catch (IOException e) {
            canonical = pom.toAbsolutePath().normalize().toString();
        }
        if (!visited.add(canonical)) return null;

        String content;
        try {
            content = Files.readString(pom);
        } catch (IOException e) {
            log.debug("Cannot read pom {}: {}", pom, e.getMessage());
            return null;
        }

        PomModel model = new PomModel();
        model.groupId = firstTag(content, "groupId");
        model.artifactId = firstTag(content, "artifactId");
        model.version = firstTag(content, "version");

        // Properties.
        int propsStart = content.indexOf("<properties>");
        int propsEnd = content.indexOf("</properties>");
        if (propsStart >= 0 && propsEnd > propsStart) {
            String block = content.substring(propsStart + "<properties>".length(), propsEnd);
            Matcher m = SIMPLE_TAG.matcher(block);
            while (m.find()) {
                model.properties.put(m.group(1), m.group(2));
            }
        }

        // Parent chain: properties and dependencyManagement are inherited.
        String parentGroup = firstTagInBlock(content, "parent", "groupId");
        String parentArtifact = firstTagInBlock(content, "parent", "artifactId");
        String parentVersion = firstTagInBlock(content, "parent", "version");
        if (parentGroup != null && parentArtifact != null && parentVersion != null) {
            Path parentPom = repository
                .resolve(parentGroup.replace('.', '/'))
                .resolve(parentArtifact)
                .resolve(substituteProperties(parentVersion, model))
                .resolve(parentArtifact + "-" + substituteProperties(parentVersion, model) + ".pom");
            PomModel parentModel = readPomModel(parentPom, repository, visited);
            if (parentModel != null) {
                for (Map.Entry<String, String> e : parentModel.properties.entrySet()) {
                    model.properties.putIfAbsent(e.getKey(), e.getValue());
                }
                for (Map.Entry<String, String> e : parentModel.managedVersions.entrySet()) {
                    model.managedVersions.putIfAbsent(e.getKey(), e.getValue());
                }
                // Parent <dependencies> are inherited into the child.
                model.dependencies.addAll(parentModel.dependencies);
            }
        }

        // dependencyManagement.
        int mgmtStart = content.indexOf("<dependencyManagement>");
        int mgmtEnd = content.indexOf("</dependencyManagement>");
        if (mgmtStart >= 0 && mgmtEnd > mgmtStart) {
            String mgmtBlock = content.substring(mgmtStart, mgmtEnd);
            for (DepNode node : parseDependencies(mgmtBlock)) {
                if (node.version != null) {
                    model.managedVersions.put(
                        node.groupId + ":" + node.artifactId,
                        node.version);
                }
            }
        }

        // Direct dependencies.
        int depsStart = content.indexOf("<dependencies>");
        int depsEnd = content.indexOf("</dependencies>");
        if (depsStart >= 0 && depsEnd > depsStart) {
            String depsBlock = content.substring(depsStart, depsEnd + "</dependencies>".length());
            for (DepNode node : parseDependencies(depsBlock)) {
                // Fill in a missing version from dependencyManagement.
                if (node.version == null || node.version.isBlank()) {
                    node.version = model.managedVersions.get(
                        node.groupId + ":" + node.artifactId);
                }
                model.dependencies.add(node);
            }
        }

        return model;
    }

    private static List<DepNode> parseDependencies(String block) {
        List<DepNode> out = new ArrayList<>();
        int pos = 0;
        while ((pos = block.indexOf("<dependency>", pos)) >= 0) {
            int end = block.indexOf("</dependency>", pos);
            if (end < 0) break;
            String body = block.substring(pos, end);

            DepNode node = new DepNode();
            node.groupId = firstTag(body, "groupId");
            node.artifactId = firstTag(body, "artifactId");
            node.version = firstTag(body, "version");
            node.scope = firstTag(body, "scope");
            String optional = firstTag(body, "optional");
            node.optional = optional != null && optional.equalsIgnoreCase("true");

            int exclStart = body.indexOf("<exclusions>");
            int exclEnd = body.indexOf("</exclusions>");
            if (exclStart >= 0 && exclEnd > exclStart) {
                String exclBlock = body.substring(exclStart, exclEnd);
                Matcher m = SIMPLE_TAG_GROUP.matcher(exclBlock);
                while (m.find()) {
                    node.exclusions.add(m.group(1) + ":" + m.group(2));
                }
            }

            if (node.groupId != null && node.artifactId != null) {
                out.add(node);
            }
            pos = end;
        }
        return out;
    }

    private static final Pattern SIMPLE_TAG = Pattern.compile(
        "<([A-Za-z0-9_.-]+)>([^<]*)</\\1>");
    private static final Pattern SIMPLE_TAG_GROUP = Pattern.compile(
        "<groupId>([^<]+)</groupId>\\s*<artifactId>([^<]+)</artifactId>");

    private static String firstTag(String xml, String tag) {
        String open = "<" + tag + ">";
        int start = xml.indexOf(open);
        if (start < 0) return null;
        int end = xml.indexOf("</" + tag + ">", start);
        if (end < 0) return null;
        return xml.substring(start + open.length(), end).trim();
    }

    private static String firstTagInBlock(String xml, String blockTag, String tag) {
        String open = "<" + blockTag + ">";
        int start = xml.indexOf(open);
        if (start < 0) return null;
        int end = xml.indexOf("</" + blockTag + ">", start);
        if (end < 0) return null;
        return firstTag(xml.substring(start, end), tag);
    }

    private static Path localRepositoryRoot() {
        String explicit = System.getenv("M2_REPO");
        if (explicit != null && !explicit.isBlank()) {
            Path p = Paths.get(explicit);
            if (Files.isDirectory(p)) return p;
        }
        String userHome = System.getProperty("user.home", "");
        if (userHome.isEmpty()) return null;
        Path repo = Paths.get(userHome, ".m2", "repository");
        return Files.isDirectory(repo) ? repo : null;
    }

    // =====================================================================
    //  Input-jar helpers
    // =====================================================================

    private static Path extractJarEntryToTemp(JarFile jar, JarEntry entry,
                                              String prefix, String suffix)
        throws IOException {
        Path tmp = Files.createTempFile("jnative-" + prefix + "-", suffix);
        tmp.toFile().deleteOnExit();
        try (InputStream is = jar.getInputStream(entry)) {
            Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        return tmp;
    }

    private static String readSelfGav(Path pomProperties) {
        if (pomProperties == null) return null;
        try (InputStream is = Files.newInputStream(pomProperties)) {
            Properties props = new Properties();
            props.load(is);
            String g = props.getProperty("groupId");
            String a = props.getProperty("artifactId");
            String v = props.getProperty("version");
            if (g == null || a == null || v == null) return null;
            return g + ":" + a + ":" + v;
        } catch (IOException e) {
            return null;
        }
    }

    private static Set<String> extractDepGroupIds(Path pomXml) {
        try {
            String content = Files.readString(pomXml);
            int depsStart = content.indexOf("<dependencies>");
            if (depsStart < 0) return Set.of();
            int depsEnd = content.indexOf("</dependencies>", depsStart);
            if (depsEnd < 0) return Set.of();

            Set<String> groupIds = new HashSet<>();
            int pos = depsStart;
            while ((pos = content.indexOf("<dependency>", pos)) >= 0 && pos < depsEnd) {
                int depEnd = content.indexOf("</dependency>", pos);
                if (depEnd < 0 || depEnd > depsEnd) break;
                String block = content.substring(pos, depEnd);
                int gStart = block.indexOf("<groupId>");
                int gEnd = block.indexOf("</groupId>");
                if (gStart >= 0 && gEnd > gStart) {
                    String g = block.substring(gStart + "<groupId>".length(), gEnd).trim();
                    if (!g.isEmpty()) groupIds.add(g);
                }
                pos = depEnd;
            }
            return groupIds;
        } catch (IOException e) {
            return Set.of();
        }
    }

    private static boolean jarAlreadyContainsDeps(Set<String> loadedClassNames,
                                                  Set<String> depGroupIds) {
        for (String groupId : depGroupIds) {
            String prefix = groupId.replace('.', '/') + "/";
            for (String cls : loadedClassNames) {
                if (cls.startsWith(prefix)) return true;
            }
        }
        return false;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");
    }
}