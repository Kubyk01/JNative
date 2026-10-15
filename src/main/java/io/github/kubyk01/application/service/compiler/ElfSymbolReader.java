package io.github.kubyk01.application.service.compiler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

/**
 * Minimal ELF dynamic-symbol reader.
 *
 * <p>Reads the {@code .dynsym} symbol table and its {@code .dynstr}
 * string table straight from the file, which is enough to answer
 * "does this shared object export symbol X?" without spawning
 * {@code nm} for every library in {@code $JAVA_HOME/lib}.</p>
 *
 * <p>Non-ELF inputs (Mach-O, PE) return an empty set, which makes the
 * caller fall back to the external tool.</p>
 */
public final class ElfSymbolReader {

    private ElfSymbolReader() {
    }

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
            boolean le = head.get(5) == 1;
            ByteOrder bo = le ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;

            long eShoff;
            int eShentsize;
            int eShnum;
            int eShstrndx;
            if (is64) {
                ByteBuffer b = ByteBuffer.allocate(64).order(bo);
                ch.read(b, 0);
                b.flip();
                eShoff = b.getLong(0x28);
                eShentsize = Short.toUnsignedInt(b.getShort(0x3A));
                eShnum = Short.toUnsignedInt(b.getShort(0x3C));
                eShstrndx = Short.toUnsignedInt(b.getShort(0x3E));
            } else {
                ByteBuffer b = ByteBuffer.allocate(52).order(bo);
                ch.read(b, 0);
                b.flip();
                eShoff = Integer.toUnsignedLong(b.getInt(0x20));
                eShentsize = Short.toUnsignedInt(b.getShort(0x2E));
                eShnum = Short.toUnsignedInt(b.getShort(0x30));
                eShstrndx = Short.toUnsignedInt(b.getShort(0x32));
            }

            ByteBuffer sects = ByteBuffer.allocate(eShentsize * eShnum).order(bo);
            ch.read(sects, eShoff);
            sects.flip();

            int dynsymOff = 0, dynsymSize = 0, dynstrOff = 0, dynstrSize = 0;
            for (int i = 0; i < eShnum; i++) {
                int base = i * eShentsize;
                int type = sects.getInt(base + 4);
                long off = is64
                    ? sects.getLong(base + 0x18)
                    : Integer.toUnsignedLong(sects.getInt(base + 0x10));
                long size = is64
                    ? sects.getLong(base + 0x20)
                    : Integer.toUnsignedLong(sects.getInt(base + 0x14));
                if (type == 11 /* SHT_DYNSYM */) {
                    dynsymOff = (int) off;
                    dynsymSize = (int) size;
                } else if (type == 3 /* SHT_STRTAB */ && i != eShstrndx) {
                    if (dynstrOff == 0) {
                        dynstrOff = (int) off;
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
}
