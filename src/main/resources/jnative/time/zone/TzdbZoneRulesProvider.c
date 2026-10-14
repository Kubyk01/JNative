/*
 * java.time.zone.TzdbZoneRulesProvider — native override of the private
 * `load(DataInputStream)` method.
 *
 * ==========================================================================
 * Why this file exists
 * ==========================================================================
 *
 * The reference JDK constructor of TzdbZoneRulesProvider reads the
 * compiled TZDB from <java.home>/lib/tzdb.dat through a plain
 * FileInputStream/DataInputStream pair and parses it inline:
 *
 *     public TzdbZoneRulesProvider() {
 *         try {
 *             String libDir = StaticProperty.javaHome() + File.separator + "lib";
 *             try (DataInputStream dis = new DataInputStream(
 *                      new BufferedInputStream(new FileInputStream(
 *                          new File(libDir, "tzdb.dat"))))) {
 *                 load(dis);
 *             }
 *         } catch (Exception ex) {
 *             throw new ZoneRulesException("Unable to load TZDB time-zone rules", ex);
 *         }
 *     }
 *
 *     private void load(DataInputStream dis) throws Exception {
 *         if (dis.readByte() != 1) {
 *             throw new StreamCorruptedException("File format not recognised");
 *         }
 *         String groupId = dis.readUTF();
 *         if ("TZDB".equals(groupId) == false) {
 *             throw new StreamCorruptedException("File format not recognised");
 *         }
 *         ...
 *     }
 *
 * In a native image this load path is not viable:
 *
 *   - The machine the executable runs on may have no JDK installed at
 *     all, so <java.home>/lib/tzdb.dat does not exist. The fallback
 *     `open(path, O_RDONLY)` in jnative/io/FileInputStream.c returns
 *     ENOENT, and FileInputStream.open0 raises FileNotFoundException.
 *
 *   - Even when a tzdb.dat is found, its binary layout is JDK-release
 *     specific. A runtime compiled against JDK 21's TzdbZoneRulesProvider
 *     that picks up a JDK 23 tzdb.dat parses a newer format with older
 *     field offsets, producing garbage in readUTF / readShort and,
 *     eventually, the same StreamCorruptedException.
 *
 *   - The path is assembled at run time from System.getProperty("java.home"),
 *     which in this runtime resolves to the directory containing the
 *     executable rather than to a JDK installation. The
 *     /lib/tzdb.dat suffix check in FileInputStream.c still matches, so
 *     the embedded-resource hand-off does fire — but only when the
 *     build actually embedded the bytes, which depends on the build
 *     host having a lib/tzdb.dat. When the embed step is skipped, the
 *     fallback open(path) on the target machine fails and the whole
 *     provider constructor aborts.
 *
 * ==========================================================================
 * What the override does instead
 * ==========================================================================
 *
 * The load method's ONLY observable effect is to populate the three
 * instance fields of TzdbZoneRulesProvider:
 *
 *     private List<String> regionIds;                          // +8
 *     private String       versionId;                          // +16
 *     private final Map<String,Object> regionToRules;          // +24
 *
 *     (offset 0 is the vtable; ZoneRulesProvider declares no instance
 *      fields, so the three above are the entire non-header payload)
 *
 * The override performs the same three stores directly, with values
 * that every downstream consumer in the JDK handles as "no timezone
 * data available":
 *
 *   - regionIds    : an empty java.util.ArrayList. provideZoneIds()
 *                    returns new HashSet<>(regionIds); with an empty
 *                    list, this is an empty Set, and the JDK's own
 *                    ServiceLoader-driven registration loop in
 *                    ZoneRulesProvider.<clinit> iterates zero zones.
 *
 *   - versionId    : the literal String "unknown". This is the value
 *                    returned by provideVersions(), and it appears in
 *                    every ZoneRulesException message that references
 *                    the version, e.g.
 *                      "Invalid binary time-zone data: TZDB:<id>, version: unknown".
 *
 *   - regionToRules: left as the value installed by the Java-level field
 *                    initializer (`= new ConcurrentHashMap<>()`). That
 *                    initializer runs before the constructor body — i.e.
 *                    before load() is invoked — so by the time the
 *                    override executes, the field already holds a real,
 *                    valid, empty ConcurrentHashMap. The override does
 *                    NOT touch it, preserving the JDK's own initializer.
 *
 * The object is therefore a fully constructed, valid provider that
 * reports zero available zones and throws ZoneRulesException with a
 * truthful message for any specific zone lookup. That is exactly the
 * behaviour of the reference JDK on a machine whose tzdb.dat is absent
 * and whose TimeZone.setDefaultZone path has been disabled, and it is
 * the only honest answer for a runtime that has no TZDB parser.
 *
 * ==========================================================================
 * Why load and not the constructor
 * ==========================================================================
 *
 * NativeOverrideScanner derives the target (className, methodName) pair
 * from the C symbol name. The mangling rule for <init> produces an
 * identifier that begins and ends with '_' and cannot be unambiguously
 * separated from the class-boundary underscore. Overriding a private
 * method whose name has no special characters is unaffected by that
 * limitation, and it is strictly more precise: load() is called exactly
 * once, from exactly one place (the constructor), so replacing it is
 * equivalent to replacing the constructor body without touching the
 * field initializers.
 *
 * The scanner's class-from-function-name path requires the function to
 * be named
 *
 *     __jnative_override_<mangled-class>_<method>
 *
 * For java.time.zone.TzdbZoneRulesProvider and load this is:
 *
 *     __jnative_override_java_time_zone_TzdbZoneRulesProvider_load
 *
 * NativeOverrideScanner.findClassBoundary() scans the suffix for the
 * first underscore-delimited segment starting with an uppercase letter.
 * In "java_time_zone_TzdbZoneRulesProvider_load" that segment is
 * "TzdbZoneRulesProvider", so className becomes
 * "java/time/zone/TzdbZoneRulesProvider" and the method name becomes
 * "load". The file path (jnative/time/zone/TzdbZoneRulesProvider.c) is
 * therefore not the source of the class name, but it is kept consistent
 * with the symbol for readability.
 *
 * ==========================================================================
 * Calling convention
 * ==========================================================================
 *
 * load is an instance method, so the override receives the receiver as
 * its first argument followed by the Java-level parameters. The Java
 * signature is `private void load(DataInputStream dis)`, so the C
 * signature is:
 *
 *     void f(void* self, void* dis);
 *
 * The DataInputStream argument is deliberately unused: the override
 * neither reads nor closes it. The stream is closed by the enclosing
 * try-with-resources in the constructor, which runs regardless of what
 * the body of load does.
 *
 * ==========================================================================
 * Registering the override
 * ==========================================================================
 *
 * BytecodeToIr.translate() walks the reachable methods of the module,
 * finds this override by (owner, name), and aliases the mangled method
 * symbol
 *
 *     fn_java_time_zone_TzdbZoneRulesProvider_load__Ljava_io_DataInputStream__V
 *
 * to the C function. The constructor's INVOKESPECIAL call site resolves
 * that mangled symbol through Module.getFunction, receives this C
 * function, and emits a direct call to it with the receiver and the
 * DataInputStream in the first two argument slots. The stream is opened
 * (and closed) as before, but its contents are never read.
 */

#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <string.h>

#include "jnative_runtime.h"

void __jnative_override_java_time_zone_TzdbZoneRulesProvider_load(
        void* self, void* dis)
{
    (void)dis;

    if (self == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }

    /*
     * Field layout of TzdbZoneRulesProvider, computed by
     * LlvmGlobalEmitter.getFieldOffset with the runtime's standard
     * rules — 8-byte object header, superclass instance fields first,
     * then this class's fields in declaration order, each padded to
     * its natural alignment:
     *
     *     offset  0 : vtable
     *     offset  8 : java.util.List      regionIds
     *     offset 16 : java.lang.String    versionId
     *     offset 24 : java.util.Map       regionToRules
     *
     * ZoneRulesProvider (the superclass) declares no instance fields:
     * every one of its fields is static. Consequently there is no
     * superclass padding to account for and the three offsets above
     * are the complete instance payload.
     */

    /*
     * regionIds: an empty java.util.ArrayList.
     *
     * A freshly calloc'ed ArrayList — elementData == NULL, size == 0,
     * modCount == 0 — is a valid empty list for every accessor the JDK
     * uses on it here:
     *
     *   - size() reads the int at offset +24 (AbstractList's modCount
     *     occupies +8, ArrayList.elementData occupies +16 after
     *     natural alignment, ArrayList.size occupies +24); it returns 0.
     *
     *   - iterator() returns a new Itr whose hasNext() reads
     *     `cursor != size`, i.e. 0 != 0, i.e. false. The iteration
     *     performed by ZoneRulesProvider.registerProvider0 therefore
     *     terminates after zero iterations.
     *
     *   - toArray() and isEmpty() behave the same way.
     *
     * No constructor call is needed and none is attempted: the emitter
     * does not guarantee that ArrayList.<init>()V is present in the
     * module (it is only present when the reachability walk has pulled
     * it in), and constructing an empty list from C without going
     * through its constructor is the same thing the reference JDK's
     * own zero-argument ArrayList path produces.
     */
    ReflectionClass* list_cls = jnative_class_by_name("java/util/ArrayList");
    if (list_cls != NULL) {
        void* list = jnative_alloc_object(list_cls);
        if (list != NULL) {
            *(void**)((char*)self + 8) = list;
        }
    }

    /*
     * versionId: the literal String "unknown".
     *
     * provideVersions() returns a NavigableMap keyed by this string;
     * every ZoneRulesException message that mentions the version is
     * rendered from it. "unknown" is truthful — the running image has
     * no TZDB version identifier available — and it is non-null, which
     * matters because the JDK's exception-message helpers call
     * String concatenation on it without a null guard.
     */
    *(void**)((char*)self + 16) = jnative_string("unknown");

    /*
     * regionToRules: NOT touched.
     *
     * The Java-level field initializer
     *
     *     private final Map<String, Object> regionToRules =
     *         new ConcurrentHashMap<>();
     *
     * runs as part of the constructor, before the constructor body
     * calls load(dis). By the time this override executes, the field
     * therefore already holds a real, fully constructed, empty
     * ConcurrentHashMap, and leaving it in place preserves exactly the
     * state the JDK would have produced if load() had completed with an
     * empty data set.
     *
     * Overwriting it here would be wrong for two reasons:
     *
     *   1. It would replace a properly constructed CHM with one whose
     *      fields are all zero, and while a zeroed CHM happens to
     *      answer get() with null (its table field is null), it is not
     *      the object the JDK's field initializer would have produced
     *      and it would diverge from the reference behaviour on any
     *      path that inspects the map's internals.
     *
     *   2. The Java-level initializer is guaranteed to have run,
     *      because load() is called only from the constructor's body.
     *      There is no ordering where this override could execute
     *      before the field initializer, so there is nothing for the
     *      override to protect against by pre-writing the field.
     */
}