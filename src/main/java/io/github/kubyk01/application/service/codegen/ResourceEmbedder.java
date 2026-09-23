package io.github.kubyk01.application.service.codegen;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Collects the bytes of the resources the JDK reads through
 * {@code Class.getResourceAsStream}, so they can be baked into the image.
 *
 * <p>The consumer of these bytes is the native override installed by
 * {@code LlvmGlobalEmitter.overrideMethod(...)} on
 * {@code java/lang/Class.getResourceAsStream}. In a plain JVM, that call
 * resolves through the module system to a resource lookup on
 * {@code jrt:/modules/java.base/}; in a JNative image there is no module
 * system and no jimage, so the only path that keeps the call well-defined
 * is to have the bytes already linked into the executable and indexed by a
 * flat lookup table the runtime walks.</p>
 *
 * <h2>Which files</h2>
 *
 * <p>Everything under {@code jdk/internal/icu/impl/data/icudt<NN>b/} in the
 * {@code java.base} module. On JDK 21 that is exactly four files —
 * {@code nfc.nrm}, {@code nfkc.nrm}, {@code ubidi.icu}, {@code uprops.icu}
 * — but the version number ({@code 72} on JDK 21, {@code 76} on JDK 23, …)
 * and the exact file set change from release to release. Rather than
 * spell out a list that is correct on exactly one JDK, this class
 * <em>enumerates</em> the ICU data directory and takes whatever is there.</p>
 *
 * <h2>Where the bytes come from</h2>
 *
 * <p>Two sources are tried, in order, and the first that yields a non-empty
 * result wins:</p>
 *
 * <ol>
 *   <li><b>{@code $JAVA_HOME/jmods/java.base.jmod}</b> — a plain zip whose
 *       entries are prefixed with {@code classes/}. The zip API reads it
 *       without any JDK internals, and the class-file payload of
 *       {@code ICUBinary} is irrelevant here: only the {@code .nrm} /
 *       {@code .icu} resource entries are consulted.</li>
 *
 *   <li><b>{@code jrt:/modules/java.base}</b> — the running JVM's view of
 *       the same module contents, mounted as a read-only filesystem. Used
 *       when {@code jmods/java.base.jmod} is absent, which happens on a JDK
 *       distribution that ships only the runtime image.</li>
 * </ol>
 *
 * <p>The class-loader API and {@code Class.getResourceAsStream} on an
 * {@code ICUBinary} mirror are deliberately not used: on every JDK this
 * runtime has been built against they return {@code null} for these
 * resources regardless of {@code --add-opens}, because the resources live
 * in the module image rather than on a class path.</p>
 *
 * <h2>Failure policy</h2>
 *
 * <p>An empty result is a hard error, not an empty table. A silently empty
 * {@code jnative_builtin_resources} array means every
 * {@code Class.getResourceAsStream} on an ICU resource returns null, which
 * in turn means {@code ICUBinary.getRequiredData} throws
 * {@code InternalError: Missing resource: ...} on the first
 * {@code Norm2AllModes} class load. Failing at code-generation time with a
 * message that names both attempted sources is strictly more useful than
 * failing at run time inside an unrelated JDK class.</p>
 */
public final class ResourceEmbedder {

    /**
     * Relative path of the modular archive under {@code java.home}.
     */
    private static final String JMOD_RELATIVE_PATH = "jmods/java.base.jmod";

    /**
     * Prefix of every entry in a {@code .jmod} zip. The zip format wraps the
     * module's contents one level deep; the strip below turns
     * {@code classes/jdk/internal/icu/impl/data/icudt72b/nfc.nrm} into the
     * runtime path {@code jdk/internal/icu/impl/data/icudt72b/nfc.nrm}.
     */
    private static final String JMOD_CLASSES_PREFIX = "classes/";

    /**
     * Directory inside the {@code java.base} module whose contents are the
     * target of this collection. Both the jmod reader and the jrt reader
     * use it as their filter.
     */
    private static final String ICU_DATA_PREFIX = "jdk/internal/icu/impl/data/";

    /**
     * Matches the versioned ICU data directory itself plus any file inside
     * it. The version number varies by JDK release; the trailing {@code b}
     * is part of the ICU convention ({@code b} = big-endian data layout)
     * and is present in every JDK this runtime targets.
     */
    private static final Pattern ICU_DATA_ENTRY = Pattern.compile(
        "^" + Pattern.quote(ICU_DATA_PREFIX) + "icudt\\d+b/.+$");

    /**
     * The module the ICU data lives in. Hard-coded because {@code java.base}
     * is not optional in any JVM and no other module carries a copy of the
     * ICU data this runtime needs.
     */
    private static final String MODULE_NAME = "java.base";

    private ResourceEmbedder() {
    }

    /**
     * Collects the bytes of every resource under the ICU data directory of
     * {@code java.base}, keyed by the internal path the native override will
     * later look up (no leading {@code '/'}, {@code '/'}-separated).
     *
     * <p>The result is sorted by path so the emitted IR is stable across
     * runs. Every registration is idempotent — a resource discovered through
     * the second source after having been discovered through the first is
     * not added twice.</p>
     *
     * @throws IllegalStateException if no resource could be collected from
     *         either source, or if {@code java.home} is unset
     */
    public static List<Map.Entry<String, byte[]>> collectIcuResources() {
        List<Map.Entry<String, byte[]>> resources = readFromJmod();

        if (resources.isEmpty()) {
            resources = readFromJrt();
        }

        if (resources.isEmpty()) {
            throw new IllegalStateException(
                "ResourceEmbedder: no ICU data resources could be collected. "
                    + "Tried $JAVA_HOME/" + JMOD_RELATIVE_PATH
                    + " and jrt:/modules/" + MODULE_NAME + "/" + ICU_DATA_PREFIX
                    + ". java.home = " + System.getProperty("java.home")
                    + ". Without these files the native "
                    + "Class.getResourceAsStream override returns null for "
                    + "every ICU resource, and ICUBinary.getRequiredData "
                    + "throws InternalError: Missing resource on the first "
                    + "Norm2AllModes class load.");
        }

        return resources;
    }

    // =====================================================================
    //  Primary source: $JAVA_HOME/jmods/java.base.jmod
    // =====================================================================

    private static List<Map.Entry<String, byte[]>> readFromJmod() {
        Path jmod = locateJmod();
        if (jmod == null) {
            return List.of();
        }
        try {
            return readJmodEntries(jmod);
        } catch (IOException e) {
            // The jmod exists but cannot be read (permissions, truncation,
            // a non-zip directory entry with that name). Fall through to
            // the jrt source rather than aborting: the runtime image is
            // guaranteed to be present if the JVM that is executing this
            // code managed to start.
            return List.of();
        }
    }

    private static Path locateJmod() {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isEmpty()) {
            return null;
        }
        Path candidate = Paths.get(javaHome).resolve(JMOD_RELATIVE_PATH);
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    private static List<Map.Entry<String, byte[]>> readJmodEntries(Path jmod)
            throws IOException {
        List<Map.Entry<String, byte[]>> out = new ArrayList<>();

        try (ZipFile zip = new ZipFile(jmod.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }

                String name = entry.getName();
                if (!name.startsWith(JMOD_CLASSES_PREFIX)) {
                    continue;
                }
                String stripped = name.substring(JMOD_CLASSES_PREFIX.length());
                if (!ICU_DATA_ENTRY.matcher(stripped).matches()) {
                    continue;
                }

                byte[] data;
                try (InputStream is = zip.getInputStream(entry)) {
                    data = is.readAllBytes();
                }

                out.add(new AbstractMap.SimpleImmutableEntry<>(stripped, data));
            }
        }

        out.sort(Map.Entry.comparingByKey());
        return out;
    }

    // =====================================================================
    //  Fallback source: jrt:/modules/java.base
    // =====================================================================

    private static List<Map.Entry<String, byte[]>> readFromJrt() {
        FileSystem fs;
        try {
            fs = getJrtFileSystem();
        } catch (IOException e) {
            return List.of();
        }

        Path icuDir = fs.getPath("/modules/" + MODULE_NAME + "/" + ICU_DATA_PREFIX);
        if (!Files.isDirectory(icuDir)) {
            return List.of();
        }

        List<Map.Entry<String, byte[]>> out = new ArrayList<>();

        try (DirectoryStream<Path> versions = Files.newDirectoryStream(icuDir)) {
            for (Path version : versions) {
                if (!Files.isDirectory(version)) {
                    continue;
                }
                String dirName = version.getFileName().toString();
                if (!isIcuVersionDirectory(dirName)) {
                    continue;
                }

                try (DirectoryStream<Path> files =
                         Files.newDirectoryStream(version)) {
                    for (Path file : files) {
                        if (!Files.isRegularFile(file)) {
                            continue;
                        }
                        String fileName = file.getFileName().toString();
                        String stripped = ICU_DATA_PREFIX + dirName + "/" + fileName;

                        byte[] data = Files.readAllBytes(file);
                        out.add(new AbstractMap.SimpleImmutableEntry<>(stripped, data));
                    }
                }
            }
        } catch (IOException e) {
            return List.of();
        }

        out.sort(Map.Entry.comparingByKey());
        return out;
    }

    /**
     * True iff {@code name} has the form {@code icudt<digits>b} — the
     * directory name the ICU build system uses for its data. The check is
     * spelled out rather than using a regex because it is called on every
     * entry of a small directory and the character-by-character form reads
     * more directly than the pattern.
     */
    private static boolean isIcuVersionDirectory(String name) {
        final String prefix = "icudt";
        if (name.length() <= prefix.length() + 1) {
            return false;
        }
        if (!name.startsWith(prefix)) {
            return false;
        }
        if (name.charAt(name.length() - 1) != 'b') {
            return false;
        }
        for (int i = prefix.length(); i < name.length() - 1; i++) {
            char c = name.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the JRT filesystem, creating it if the JDK has not already
     * done so. The JDK's own filesystem provider registers {@code jrt:/}
     * lazily on first access; the primary path is therefore
     * {@link FileSystems#getFileSystem(URI)}, and the creation path is only
     * taken if no provider has claimed the URI yet.
     *
     * <p>The double lookup inside the {@code catch} block handles the race
     * where two threads simultaneously discover that {@code jrt:/} is not
     * yet registered: one of them wins the {@code newFileSystem} call, the
     * other sees {@link FileAlreadyExistsException} and re-reads the
     * registered instance. The lookup form is the same one the rest of the
     * code base uses.</p>
     */
    private static FileSystem getJrtFileSystem() throws IOException {
        URI jrt = URI.create("jrt:/");

        try {
            return FileSystems.getFileSystem(jrt);
        } catch (FileSystemNotFoundException notYetRegistered) {
            Map<String, String> env = new HashMap<>();
            env.put("java.home", System.getProperty("java.home"));
            try {
                return FileSystems.newFileSystem(jrt, env);
            } catch (FileAlreadyExistsException race) {
                return FileSystems.getFileSystem(jrt);
            }
        }
    }
}
