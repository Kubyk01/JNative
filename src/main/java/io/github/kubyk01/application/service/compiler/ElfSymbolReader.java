package io.github.kubyk01.application.service.compiler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Minimal ELF dynamic-symbol reader.
 *
 * <p>Reads two independent pieces of the ELF dynamic linking metadata
 * straight from the file, without spawning {@code nm}, {@code objdump}
 * or {@code readelf} for every library in {@code $JAVA_HOME/lib} and
 * {@code $JAVA_HOME/lib/server}:</p>
 *
 * <ul>
 *   <li><b>The exported dynamic symbol table</b> ({@code .dynsym} plus
 *       {@code .dynstr}). Used to answer "does this shared object
 *       export symbol X?", which is how the compiler decides which JDK
 *       libraries satisfy the JNI symbols the compiled module
 *       references.</li>
 *
 *   <li><b>The {@code DT_NEEDED} list</b> (the {@code .dynamic}
 *       section plus its associated string table). Used to compute the
 *       transitive closure of JDK libraries that have to be embedded:
 *       a library whose symbols the module needs is useless on a
 *       machine without a JDK unless every library named in its own
 *       {@code DT_NEEDED} list travels with it.</li>
 * </ul>
 *
 * <p>Both readers are pure byte-parse operations over the file's
 * section headers. They handle 32-bit and 64-bit ELF, little-endian
 * and big-endian, and return empty sets for any input that is not an
 * ELF file (Mach-O, PE) or whose section headers are malformed. An
 * empty result therefore always means "this reader could not extract
 * the information", never "the file explicitly says there is none" —
 * which is the correct direction for the callers, both of which treat
 * an empty result as "skip this file" rather than "the file has no
 * dependencies".</p>
 *
 * <p>The class is stateless and thread-safe: every public method opens
 * its own {@link FileChannel}, reads a self-contained byte range, and
 * closes it. No mutable state is shared between calls, so a caller can
 * invoke both readers concurrently on different libraries without any
 * synchronisation.</p>
 */
public final class ElfSymbolReader {

    private ElfSymbolReader() {
    }

    // =========================================================================
    //  Exported-symbol reader
    // =========================================================================

    /**
     * Returns the set of symbols exported by the ELF dynamic symbol
     * table of {@code lib}.
     *
     * <p>The result contains the names of every entry in {@code .dynsym}
     * that has a valid name offset into {@code .dynstr}, including
     * entries whose section index is {@code SHN_UNDEF} (symbols the
     * library itself imports). Imported symbols are harmless in the
     * caller's set-intersection step: a name that appears both as an
     * export of one library and as an import of another does not
     * change the answer to "which library can satisfy symbol X",
     * because the satisfying library exports it too.</p>
     *
     * <p>Non-ELF inputs (Mach-O, PE) return an empty set, which makes
     * the caller fall back to the external tool if it has one. This
     * mirrors the behaviour the previous revision already had: {@code nm}
     * is not present on every build host, and the pure-Java parse is
     * the fast path that avoids spawning one subprocess per file.</p>
     *
     * @param lib the shared object to read; must be a regular file
     * @return the set of exported symbol names, or an empty set when
     *         {@code lib} is not a readable ELF file
     * @throws IOException if the file exists but cannot be opened or
     *         read
     */
    public static Set<String> read(Path lib) throws IOException {
        try (FileChannel ch = FileChannel.open(lib, StandardOpenOption.READ)) {
            ByteBuffer head = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
            ch.read(head, 0);
            head.flip();
            if (head.get(0) != 0x7f || head.get(1) != 'E'
                    || head.get(2) != 'L' || head.get(3) != 'F') {
                return Set.of();
            }
            boolean is64 = head.get(4) == 2;
            boolean le   = head.get(5) == 1;
            ByteOrder bo = le ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;

            long eShoff;
            int  eShentsize;
            int  eShnum;
            int  eShstrndx;
            if (is64) {
                ByteBuffer b = ByteBuffer.allocate(64).order(bo);
                ch.read(b, 0);
                b.flip();
                eShoff     = b.getLong(0x28);
                eShentsize = Short.toUnsignedInt(b.getShort(0x3A));
                eShnum     = Short.toUnsignedInt(b.getShort(0x3C));
                eShstrndx  = Short.toUnsignedInt(b.getShort(0x3E));
            } else {
                ByteBuffer b = ByteBuffer.allocate(52).order(bo);
                ch.read(b, 0);
                b.flip();
                eShoff     = Integer.toUnsignedLong(b.getInt(0x20));
                eShentsize = Short.toUnsignedInt(b.getShort(0x2E));
                eShnum     = Short.toUnsignedInt(b.getShort(0x30));
                eShstrndx  = Short.toUnsignedInt(b.getShort(0x32));
            }

            ByteBuffer sects = ByteBuffer.allocate(eShentsize * eShnum).order(bo);
            ch.read(sects, eShoff);
            sects.flip();

            int dynsymOff = 0, dynsymSize = 0, dynstrOff = 0, dynstrSize = 0;
            for (int i = 0; i < eShnum; i++) {
                int  base = i * eShentsize;
                int  type = sects.getInt(base + 4);
                long off  = is64
                        ? sects.getLong(base + 0x18)
                        : Integer.toUnsignedLong(sects.getInt(base + 0x10));
                long size = is64
                        ? sects.getLong(base + 0x20)
                        : Integer.toUnsignedLong(sects.getInt(base + 0x14));
                if (type == 11 /* SHT_DYNSYM */) {
                    dynsymOff  = (int) off;
                    dynsymSize = (int) size;
                } else if (type == 3 /* SHT_STRTAB */ && i != eShstrndx) {
                    if (dynstrOff == 0) {
                        dynstrOff  = (int) off;
                        dynstrSize = (int) size;
                    }
                }
            }
            if (dynsymOff == 0 || dynstrOff == 0) return Set.of();

            ByteBuffer dynsym = ByteBuffer.allocate(dynsymSize).order(bo);
            ch.read(dynsym, dynsymOff);
            ByteBuffer dynstr = ByteBuffer.allocate(dynstrSize);
            ch.read(dynstr, dynstrOff);

            int entSize = is64 ? 24 : 16;
            Set<String> out = new HashSet<>();
            for (int off = 0; off + entSize <= dynsymSize; off += entSize) {
                int nameOff = dynsym.getInt(off);
                if (nameOff <= 0 || nameOff >= dynstrSize) continue;
                int end = nameOff;
                while (end < dynstrSize && dynstr.get(end) != 0) end++;
                byte[] name = new byte[end - nameOff];
                for (int k = 0; k < name.length; k++) {
                    name[k] = dynstr.get(nameOff + k);
                }
                out.add(new String(name, StandardCharsets.US_ASCII));
            }
            return out;
        }
    }

    // =========================================================================
    //  DT_NEEDED reader
    // =========================================================================

    /**
     * Returns the set of {@code DT_NEEDED} entries declared by the ELF
     * dynamic section of {@code lib}.
     *
     * <p>Each entry names a dependency by the SONAME the loader must
     * resolve when {@code dlopen} is called on {@code lib}. The set is
     * used at build time to compute the transitive closure of JDK
     * libraries that have to be embedded: a library whose own JNI
     * symbols the module needs is useless on a machine without a JDK
     * unless every library it names in {@code DT_NEEDED} travels with
     * it.</p>
     *
     * <h2>Which section is read</h2>
     *
     * <p>The reader locates the section whose type is
     * {@code SHT_DYNAMIC} (6) and follows its {@code sh_link} field to
     * the associated string table (the section whose type is
     * {@code SHT_STRTAB} (3) and whose index equals the dynamic
     * section's {@code sh_link}). It then walks the array of 16-byte
     * (64-bit) or 8-byte (32-bit) {@code Elf_Dyn} records and extracts
     * every record whose tag is {@code DT_NEEDED} (1). The record's
     * value field is an offset into the string table; the string
     * starting at that offset, up to the next NUL byte, is the SONAME
     * of one dependency.</p>
     *
     * <p>Walking stops at the first {@code DT_NULL} (tag 0) record, as
     * the ELF specification requires. Sections whose {@code sh_link}
     * does not resolve to a string table, or whose {@code sh_entsize}
     * is zero, are treated as "no information available" and produce an
     * empty set rather than an error: the caller's closure step then
     * leaves the library as-is, which is the safe direction.</p>
     *
     * <h2>Non-ELF inputs</h2>
     *
     * <p>Mach-O and PE files have no {@code .dynamic} section in the
     * ELF sense. They return an empty set. On every platform this
     * runtime targets today the build produces ELF shared objects, so
     * the empty set is only ever observed for a foreign file that the
     * compiler would have skipped anyway.</p>
     *
     * @param lib the shared object to read; must be a regular file
     * @return the set of SONAMEs the library declares as dependencies,
     *         or an empty set when {@code lib} is not a readable ELF
     *         file or has no dynamic section
     * @throws IOException if the file exists but cannot be opened or
     *         read
     */
    public static Set<String> readNeededLibraries(Path lib) throws IOException {
        try (FileChannel ch = FileChannel.open(lib, StandardOpenOption.READ)) {
            ByteBuffer head = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
            ch.read(head, 0);
            head.flip();
            if (head.get(0) != 0x7f || head.get(1) != 'E'
                    || head.get(2) != 'L' || head.get(3) != 'F') {
                return Set.of();
            }
            boolean is64 = head.get(4) == 2;
            boolean le   = head.get(5) == 1;
            ByteOrder bo = le ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;

            long eShoff;
            int  eShentsize;
            int  eShnum;
            if (is64) {
                ByteBuffer b = ByteBuffer.allocate(64).order(bo);
                ch.read(b, 0);
                b.flip();
                eShoff     = b.getLong(0x28);
                eShentsize = Short.toUnsignedInt(b.getShort(0x3A));
                eShnum     = Short.toUnsignedInt(b.getShort(0x3C));
            } else {
                ByteBuffer b = ByteBuffer.allocate(52).order(bo);
                ch.read(b, 0);
                b.flip();
                eShoff     = Integer.toUnsignedLong(b.getInt(0x20));
                eShentsize = Short.toUnsignedInt(b.getShort(0x2E));
                eShnum     = Short.toUnsignedInt(b.getShort(0x30));
            }

            if (eShentsize <= 0 || eShnum <= 0) return Set.of();

            ByteBuffer sects = ByteBuffer.allocate(eShentsize * eShnum).order(bo);
            ch.read(sects, eShoff);
            sects.flip();

            long dynOff     = 0;
            long dynSize    = 0;
            long dynstrOff  = 0;
            long dynstrSize = 0;

            for (int i = 0; i < eShnum; i++) {
                int  base = i * eShentsize;
                int  type = sects.getInt(base + 4);
                long off  = is64
                        ? sects.getLong(base + 0x18)
                        : Integer.toUnsignedLong(sects.getInt(base + 0x10));
                long size = is64
                        ? sects.getLong(base + 0x20)
                        : Integer.toUnsignedLong(sects.getInt(base + 0x14));

                if (type == 6 /* SHT_DYNAMIC */) {
                    dynOff  = off;
                    dynSize = size;

                    /*
                     * The sh_link field of a SHT_DYNAMIC section names
                     * the section index of its string table, which is
                     * always an SHT_STRTAB. The offset of sh_link
                     * within the section header differs between the
                     * 64-bit (0x28) and 32-bit (0x18) layouts.
                     */
                    int strIdx = is64
                            ? sects.getInt(base + 0x28)
                            : Short.toUnsignedInt(sects.getShort(base + 0x18));

                    if (strIdx > 0 && strIdx < eShnum) {
                        int sbase = strIdx * eShentsize;
                        dynstrOff = is64
                                ? sects.getLong(sbase + 0x18)
                                : Integer.toUnsignedLong(sects.getInt(sbase + 0x10));
                        dynstrSize = is64
                                ? sects.getLong(sbase + 0x20)
                                : Integer.toUnsignedLong(sects.getInt(sbase + 0x14));
                    }
                }
            }

            if (dynOff == 0 || dynSize <= 0 || dynstrOff == 0 || dynstrSize <= 0) {
                return Set.of();
            }

            ByteBuffer dyn = ByteBuffer.allocate((int) dynSize).order(bo);
            ch.read(dyn, dynOff);
            ByteBuffer dynstr = ByteBuffer.allocate((int) dynstrSize);
            ch.read(dynstr, dynstrOff);

            /*
             * Elf64_Dyn is { Elf64_Sxword d_tag; Elf64_Xword d_val; }
             * and Elf32_Dyn is { Elf32_Sword d_tag; Elf32_Word d_val; }.
             * Both layouts are 16 bytes on 64-bit and 8 bytes on
             * 32-bit, so the record size is the same as the entry size
             * the section header would carry if it were set. It is not
             * read from the header because some linkers leave
             * sh_entsize at zero for SHT_DYNAMIC, and the layout is
             * fixed by the ELF ABI anyway.
             */
            int entSize = is64 ? 16 : 8;

            /*
             * LinkedHashSet preserves the order in which DT_NEEDED
             * entries appear in the file. That order is exactly the
             * order the loader processes them in, and while the
             * closure algorithm below is order-insensitive, keeping
             * the source order makes a diagnostic dump of the set
             * match what the linker wrote.
             */
            Set<String> needed = new LinkedHashSet<>();

            for (int off = 0; off + entSize <= (int) dynSize; off += entSize) {
                long tag = is64
                        ? dyn.getLong(off)
                        : Integer.toUnsignedLong(dyn.getInt(off));
                long val = is64
                        ? dyn.getLong(off + 8)
                        : Integer.toUnsignedLong(dyn.getInt(off + 4));

                if (tag == 0L) break;      // DT_NULL terminates the array
                if (tag != 1L) continue;   // DT_NEEDED == 1

                int nameOff = (int) val;
                if (nameOff <= 0 || nameOff >= dynstrSize) continue;

                int end = nameOff;
                while (end < dynstrSize && dynstr.get(end) != 0) end++;

                byte[] name = new byte[end - nameOff];
                for (int k = 0; k < name.length; k++) {
                    name[k] = dynstr.get(nameOff + k);
                }
                if (name.length > 0) {
                    needed.add(new String(name, StandardCharsets.US_ASCII));
                }
            }

            return needed;
        }
    }
}