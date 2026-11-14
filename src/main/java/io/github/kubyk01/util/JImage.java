package io.github.kubyk01.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public final class JImage {

    private static final int MAGIC = 0xCAFEDADA;
    private static final int HEADER_SIZE = 28;
    private static final int COMPRESSED_MAGIC = 0xCAFEFAFA;
    private static final int COMPRESSED_HEADER_SIZE = 29;

    private static final int ATTRIBUTE_MODULE = 1;
    private static final int ATTRIBUTE_PARENT = 2;
    private static final int ATTRIBUTE_BASE = 3;
    private static final int ATTRIBUTE_EXTENSION = 4;
    private static final int ATTRIBUTE_OFFSET = 5;
    private static final int ATTRIBUTE_COMPRESSED = 6;
    private static final int ATTRIBUTE_UNCOMPRESSED = 7;

    private static final int CONSTANT_UTF8 = 1;
    private static final int CONSTANT_LONG = 5;
    private static final int CONSTANT_DOUBLE = 6;
    private static final int EXTERNALIZED_STRING = 23;
    private static final int EXTERNALIZED_DESCRIPTOR = 25;
    private static final int[] CONSTANT_SIZES = {0, 0, 0, 4, 4, 8, 8, 2, 2, 4, 4, 4, 4, 0, 0, 3, 2, 4, 4, 2, 2};

    private record Location(long offset, long compressed, long uncompressed) {}

    private final Path file;
    private final ByteOrder order;
    private final long dataStart;
    private final long fileSize;
    private final byte[] strings;
    private final Map<String, Map<String, Location>> modules = new TreeMap<>();
    private final Map<String, String> packageModules = new HashMap<>();

    public static JImage open(Path javaHome) throws IOException {
        return new JImage(javaHome.resolve("lib").resolve("modules"));
    }

    private JImage(Path file) throws IOException {
        this.file = file;
        try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
            fileSize = in.length();
            if (fileSize < HEADER_SIZE) throw new IOException("Not a jimage file: " + file);
            byte[] head = new byte[HEADER_SIZE];
            in.readFully(head);
            ByteBuffer header = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
            if (header.getInt(0) != MAGIC) header.order(ByteOrder.BIG_ENDIAN);
            if (header.getInt(0) != MAGIC) throw new IOException("Not a jimage file: " + file);
            order = header.order();
            int version = header.getInt(4);
            if (version >>> 16 != 1) {
                throw new IOException("Unsupported jimage version " + (version >>> 16) + "."
                        + (version & 0xFFFF) + ": " + file);
            }
            int tableLength = header.getInt(16);
            int locationsSize = header.getInt(20);
            int stringsSize = header.getInt(24);
            long indexSize = HEADER_SIZE + tableLength * 8L + locationsSize + stringsSize;
            if (tableLength < 0 || locationsSize < 0 || stringsSize < 0
                    || indexSize > fileSize || indexSize > Integer.MAX_VALUE) {
                throw new IOException("Corrupt jimage header: " + file);
            }
            dataStart = indexSize;
            byte[] index = new byte[(int) indexSize - HEADER_SIZE];
            in.readFully(index);
            int locationsStart = tableLength * 8;
            int stringsStart = locationsStart + locationsSize;
            strings = Arrays.copyOfRange(index, stringsStart, stringsStart + stringsSize);
            ByteBuffer offsets = ByteBuffer.wrap(index, tableLength * 4, tableLength * 4).slice().order(order);
            Map<Integer, String> names = new HashMap<>();
            long[] attributes = new long[32];
            for (int i = 0; i < tableLength; i++) {
                int position = offsets.getInt(i * 4);
                if (position <= 0 || position >= locationsSize) continue;
                Arrays.fill(attributes, 0);
                position += locationsStart;
                while (position < stringsStart) {
                    int data = index[position++] & 0xFF;
                    int kind = data >>> 3;
                    if (kind == 0) break;
                    int length = (data & 7) + 1;
                    if (position + length > stringsStart) throw new IOException("Corrupt jimage location: " + file);
                    long value = 0;
                    for (int j = 0; j < length; j++) value = value << 8 | (index[position++] & 0xFF);
                    attributes[kind] = value;
                }
                String module = name(names, attributes[ATTRIBUTE_MODULE]);
                if (module.isEmpty() || module.equals("modules") || module.equals("packages")) continue;
                String parent = name(names, attributes[ATTRIBUTE_PARENT]);
                String base = name(names, attributes[ATTRIBUTE_BASE]);
                String extension = name(names, attributes[ATTRIBUTE_EXTENSION]);
                String path = (parent.isEmpty() ? "" : parent + "/") + base
                        + (extension.isEmpty() ? "" : "." + extension);
                modules.computeIfAbsent(module, k -> new TreeMap<>()).put(path, new Location(
                        attributes[ATTRIBUTE_OFFSET], attributes[ATTRIBUTE_COMPRESSED],
                        attributes[ATTRIBUTE_UNCOMPRESSED]));
                if (extension.equals("class") && !parent.isEmpty() && !parent.startsWith("META-INF")) {
                    packageModules.put(parent, module);
                }
            }
        }
    }

    public Set<String> resources(String module) {
        Map<String, Location> resources = modules.get(module);
        return resources == null ? Set.of() : Collections.unmodifiableSet(resources.keySet());
    }

    public String moduleOfPackage(String internalPackage) {
        return packageModules.get(internalPackage);
    }

    public List<String> classNames() {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, Location>> module : modules.entrySet()) {
            for (String path : module.getValue().keySet()) {
                if (!path.endsWith(".class")) continue;
                String name = path.substring(0, path.length() - ".class".length());
                int slash = name.lastIndexOf('/');
                if (slash > 0 && module.getKey().equals(packageModules.get(name.substring(0, slash)))) {
                    result.add(name);
                }
            }
        }
        return result;
    }

    public byte[] readClass(String internalName) throws IOException {
        int slash = internalName.lastIndexOf('/');
        String module = slash > 0 ? packageModules.get(internalName.substring(0, slash)) : null;
        return module == null ? null : read(module, internalName + ".class");
    }

    public byte[] read(String module, String path) throws IOException {
        Map<String, Location> resources = modules.get(module);
        Location location = resources == null ? null : resources.get(path);
        if (location == null) return null;
        boolean compressed = location.compressed() != 0;
        long size = compressed ? location.compressed() : location.uncompressed();
        if (size < 0 || size > Integer.MAX_VALUE || location.offset() < 0
                || location.offset() > fileSize - dataStart - size) {
            throw new IOException("Corrupt jimage location of /" + module + "/" + path + ": " + file);
        }
        byte[] content = new byte[(int) size];
        try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
            in.seek(dataStart + location.offset());
            in.readFully(content);
        }
        return compressed ? decompress(content) : content;
    }

    private String name(Map<Integer, String> names, long offset) throws IOException {
        if (offset > Integer.MAX_VALUE) throw new IOException("Corrupt jimage string offset: " + file);
        String cached = names.get((int) offset);
        if (cached == null) {
            cached = string((int) offset);
            names.put((int) offset, cached);
        }
        return cached;
    }

    private String string(int offset) throws IOException {
        if (offset < 0 || offset >= strings.length) throw new IOException("Corrupt jimage string offset: " + file);
        StringBuilder result = new StringBuilder();
        int i = offset;
        try {
            while (strings[i] != 0) {
                int b = strings[i++] & 0xFF;
                if (b < 0x80) {
                    result.append((char) b);
                } else if ((b & 0xE0) == 0xC0) {
                    result.append((char) ((b & 0x1F) << 6 | strings[i++] & 0x3F));
                } else {
                    result.append((char) ((b & 0x0F) << 12 | (strings[i++] & 0x3F) << 6 | strings[i++] & 0x3F));
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IOException("Unterminated jimage string: " + file, e);
        }
        return result.toString();
    }

    private byte[] decompress(byte[] content) throws IOException {
        while (content.length >= COMPRESSED_HEADER_SIZE) {
            ByteBuffer header = ByteBuffer.wrap(content, 0, COMPRESSED_HEADER_SIZE).order(order);
            if (header.getInt() != COMPRESSED_MAGIC) break;
            header.getLong();
            long uncompressed = header.getLong();
            if (uncompressed < 0 || uncompressed > Integer.MAX_VALUE) {
                throw new IOException("Corrupt compressed jimage resource: " + file);
            }
            String decompressor = string(header.getInt());
            content = switch (decompressor) {
                case "zip" -> inflate(content, (int) uncompressed);
                case "compact-cp" -> expandConstantPool(content, (int) uncompressed);
                default -> throw new IOException("Unsupported jimage decompressor '" + decompressor + "': " + file);
            };
        }
        return content;
    }

    private byte[] inflate(byte[] content, int size) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(content, COMPRESSED_HEADER_SIZE, content.length - COMPRESSED_HEADER_SIZE);
            ByteArrayOutputStream out = new ByteArrayOutputStream(size);
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new IOException("Truncated compressed jimage resource: " + file);
                }
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new IOException("Corrupt compressed jimage resource: " + file, e);
        } finally {
            inflater.end();
        }
    }

    private byte[] expandConstantPool(byte[] content, int size) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(
                content, COMPRESSED_HEADER_SIZE, content.length - COMPRESSED_HEADER_SIZE));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(size);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeLong(in.readLong());
        int count = in.readUnsignedShort();
        out.writeShort(count);
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case CONSTANT_UTF8 -> {
                    out.write(CONSTANT_UTF8);
                    out.writeUTF(in.readUTF());
                }
                case EXTERNALIZED_STRING -> {
                    out.write(CONSTANT_UTF8);
                    out.writeUTF(string(readCompressedInt(in)));
                }
                case EXTERNALIZED_DESCRIPTOR -> {
                    out.write(CONSTANT_UTF8);
                    out.writeUTF(descriptor(in));
                }
                default -> {
                    int length = tag < CONSTANT_SIZES.length ? CONSTANT_SIZES[tag] : 0;
                    if (length == 0) throw new IOException("Corrupt compressed class in jimage: " + file);
                    if (tag == CONSTANT_LONG || tag == CONSTANT_DOUBLE) i++;
                    byte[] value = new byte[length];
                    in.readFully(value);
                    out.write(tag);
                    out.write(value);
                }
            }
        }
        in.transferTo(out);
        return bytes.toByteArray();
    }

    private String descriptor(DataInputStream in) throws IOException {
        String template = string(readCompressedInt(in));
        int length = readCompressedInt(in);
        if (length < 0 || length > in.available()) throw new IOException("Corrupt compressed class in jimage: " + file);
        byte[] encoded = new byte[length];
        in.readFully(encoded);
        DataInputStream indexes = new DataInputStream(new ByteArrayInputStream(encoded));
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            result.append(c);
            if (c != 'L') continue;
            String pkg = string(readCompressedInt(indexes));
            if (!pkg.isEmpty()) result.append(pkg).append('/');
            result.append(string(readCompressedInt(indexes)));
        }
        return result.toString();
    }

    private static int readCompressedInt(DataInputStream in) throws IOException {
        int header = in.readByte();
        boolean compressed = (header & 0x80) != 0;
        int length = compressed ? header >> 5 & 3 : 4;
        int value = compressed ? header & 0x1F : header;
        for (int i = 1; i < length; i++) value = value << 8 | in.readUnsignedByte();
        return value;
    }
}
