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

public final class PeSymbolReader {

    private PeSymbolReader() {}

    // PE constants
    private static final int IMAGE_DOS_SIGNATURE       = 0x5A4D;      // "MZ"
    private static final int IMAGE_NT_SIGNATURE        = 0x00004550;  // "PE\0\0"
    private static final int IMAGE_DIRECTORY_ENTRY_EXPORT = 0;
    private static final int IMAGE_DIRECTORY_ENTRY_IMPORT = 1;

    private static final int OPT_HDR_MAGIC_PE32  = 0x10B;
    private static final int OPT_HDR_MAGIC_PE32P = 0x20B;

    public static Set<String> read(Path dll) throws IOException {
        ByteBuffer image = readFile(dll);
        if (image == null) return Set.of();

        try {
            PeHeader h = parseHeaders(image);
            if (h == null) return Set.of();
            Set<String> names = readExportNames(image, h);
            return names != null ? names : Set.of();
        } catch (Exception e) {
            return Set.of();
        }
    }

    public static Set<String> readImportedLibraries(Path dll) throws IOException {
        ByteBuffer image = readFile(dll);
        if (image == null) return Set.of();

        try {
            PeHeader h = parseHeaders(image);
            if (h == null) return Set.of();
            Set<String> names = readImportNames(image, h);
            return names != null ? names : Set.of();
        } catch (Exception e) {
            return Set.of();
        }
    }

    private record SectionHeader(String name, long virtualAddress,
                                 long virtualSize, long rawOffset, long rawSize,
                                 int characteristics) {}

    private record PeHeader(long ntOffset, int machine, int optHeaderSize,
                            int numSections, long dataDirRva, int dataDirCount,
                            SectionHeader[] sections) {}

    private static ByteBuffer readFile(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = ch.size();
            if (size <= 0 || size > Integer.MAX_VALUE) return null;
            ByteBuffer buf = ByteBuffer.allocate((int) size);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            // todo fix that
            while (buf.hasRemaining() && ch.read(buf) > 0) { /* keep reading */ }
            buf.flip();
            return buf;
        }
    }

    private static PeHeader parseHeaders(ByteBuffer b) {
        if (b.limit() < 64) return null;
        if (b.getShort(0) != IMAGE_DOS_SIGNATURE) return null;

        long e_lfanew = Integer.toUnsignedLong(b.getInt(0x3C));
        if (e_lfanew + 24 > b.limit()) return null;
        if (b.getInt((int) e_lfanew) != IMAGE_NT_SIGNATURE) return null;

        int machine         = Short.toUnsignedInt(b.getShort((int) e_lfanew + 4));
        int numSections     = Short.toUnsignedInt(b.getShort((int) e_lfanew + 6));
        int optHeaderSize   = Short.toUnsignedInt(b.getShort((int) e_lfanew + 20));
        int optHeaderOffset = (int) e_lfanew + 24;
        if (optHeaderOffset + optHeaderSize > b.limit()) return null;

        int magic = Short.toUnsignedInt(b.getShort(optHeaderOffset));
        int dataDirBase;
        if (magic == OPT_HDR_MAGIC_PE32P) {
            dataDirBase = optHeaderOffset + 112;
        } else if (magic == OPT_HDR_MAGIC_PE32) {
            dataDirBase = optHeaderOffset + 96;
        } else {
            return null;
        }

        int dataDirCount = b.getInt(dataDirBase - 4);
        if (dataDirCount <= 0) dataDirCount = 16;
        if (dataDirCount > 16) dataDirCount = 16;

        long dataDirRva = Integer.toUnsignedLong(b.getInt(dataDirBase));

        int sectionTableOffset = optHeaderOffset + optHeaderSize;
        SectionHeader[] sections = new SectionHeader[numSections];
        for (int i = 0; i < numSections; i++) {
            int off = sectionTableOffset + i * 40;
            if (off + 40 > b.limit()) return null;

            byte[] nameBytes = new byte[8];
            for (int k = 0; k < 8; k++) nameBytes[k] = b.get(off + k);
            int nameLen = 0;
            while (nameLen < 8 && nameBytes[nameLen] != 0) nameLen++;
            String name = new String(nameBytes, 0, nameLen, StandardCharsets.US_ASCII);

            long vsize = Integer.toUnsignedLong(b.getInt(off + 8));
            long vaddr = Integer.toUnsignedLong(b.getInt(off + 12));
            long rsize = Integer.toUnsignedLong(b.getInt(off + 16));
            long roff  = Integer.toUnsignedLong(b.getInt(off + 20));
            int chars  = b.getInt(off + 36);
            sections[i] = new SectionHeader(name, vaddr, vsize, roff, rsize, chars);
        }
        return new PeHeader(e_lfanew, machine, optHeaderSize, numSections,
                            dataDirRva, dataDirCount, sections);
    }

    private static long rvaToOffset(ByteBuffer b, PeHeader h, long rva) {
        for (SectionHeader s : h.sections) {
            if (rva >= s.virtualAddress()
                && rva < s.virtualAddress() + Math.max(s.virtualSize(), s.rawSize())) {
                long delta = rva - s.virtualAddress();
                return s.rawOffset() + delta;
            }
        }
        return -1;
    }

    private static Set<String> readExportNames(ByteBuffer b, PeHeader h) {
        int optHeaderOffset = (int) h.ntOffset() + 24;
        int magic = Short.toUnsignedInt(b.getShort(optHeaderOffset));
        int dataDirBase = (magic == OPT_HDR_MAGIC_PE32P)
            ? optHeaderOffset + 112
            : optHeaderOffset + 96;

        long exportRva = Integer.toUnsignedLong(
            b.getInt(dataDirBase + IMAGE_DIRECTORY_ENTRY_EXPORT * 8));
        long exportSize = Integer.toUnsignedLong(
            b.getInt(dataDirBase + IMAGE_DIRECTORY_ENTRY_EXPORT * 8 + 4));
        if (exportRva == 0 || exportSize == 0) return Set.of();

        long exportOff = rvaToOffset(b, h, exportRva);
        if (exportOff < 0 || exportOff + 40 > b.limit()) return Set.of();

        int  numberOfNames = b.getInt((int) exportOff + 24);
        long namePtrTableRva = Integer.toUnsignedLong(
            b.getInt((int) exportOff + 32));
        if (numberOfNames <= 0) return Set.of();
        if (numberOfNames > 1_000_000) return Set.of();

        long nameTableOff = rvaToOffset(b, h, namePtrTableRva);
        if (nameTableOff < 0) return Set.of();

        Set<String> names = new HashSet<>();
        for (int i = 0; i < numberOfNames; i++) {
            int entryOff = (int) nameTableOff + i * 4;
            if (entryOff + 4 > b.limit()) break;
            long nameRva = Integer.toUnsignedLong(b.getInt(entryOff));
            long nameOff = rvaToOffset(b, h, nameRva);
            if (nameOff < 0 || nameOff >= b.limit()) continue;

            int end = (int) nameOff;
            while (end < b.limit() && b.get(end) != 0) end++;
            byte[] raw = new byte[end - (int) nameOff];
            for (int k = 0; k < raw.length; k++) raw[k] = b.get((int) nameOff + k);
            if (raw.length > 0) {
                names.add(new String(raw, StandardCharsets.US_ASCII));
            }
        }
        return names;
    }

    private static Set<String> readImportNames(ByteBuffer b, PeHeader h) {
        int optHeaderOffset = (int) h.ntOffset() + 24;
        int magic = Short.toUnsignedInt(b.getShort(optHeaderOffset));
        int dataDirBase = (magic == OPT_HDR_MAGIC_PE32P)
            ? optHeaderOffset + 112
            : optHeaderOffset + 96;

        long importRva = Integer.toUnsignedLong(
            b.getInt(dataDirBase + IMAGE_DIRECTORY_ENTRY_IMPORT * 8));
        if (importRva == 0) return Set.of();

        long importOff = rvaToOffset(b, h, importRva);
        if (importOff < 0) return Set.of();

        Set<String> names = new LinkedHashSet<>();
        int cursor = (int) importOff;
        while (cursor + 20 <= b.limit()) {
            long nameRva = Integer.toUnsignedLong(b.getInt(cursor + 12));
            if (nameRva == 0) break;
            long nameOff = rvaToOffset(b, h, nameRva);
            if (nameOff < 0 || nameOff >= b.limit()) break;

            int end = (int) nameOff;
            while (end < b.limit() && b.get(end) != 0) end++;
            byte[] raw = new byte[end - (int) nameOff];
            for (int k = 0; k < raw.length; k++) raw[k] = b.get((int) nameOff + k);
            if (raw.length > 0) {
                names.add(new String(raw, StandardCharsets.US_ASCII));
            }
            cursor += 20;
        }
        return names;
    }
}
