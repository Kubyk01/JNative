package io.github.kubyk01.application.service.analyzer.reachabilityanalysis.annotation;

import lombok.Getter;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
@Getter
public final class ClassFileAnnotations {

    public record Member(String name, String descriptor, byte[] annotations,
                         byte[] parameterAnnotations, byte[] annotationDefault) {}

    private final byte[] constantPool;
    private final int constantPoolCount;
    private final byte[] classAnnotations;
    private final List<Member> members;
    private final Set<String> referencedTypes;

    private ClassFileAnnotations(byte[] constantPool, int constantPoolCount, byte[] classAnnotations,
                                 List<Member> members, Set<String> referencedTypes) {
        this.constantPool = constantPool;
        this.constantPoolCount = constantPoolCount;
        this.classAnnotations = classAnnotations;
        this.members = members;
        this.referencedTypes = referencedTypes;
    }

    public boolean isEmpty() {
        return classAnnotations == null && members.isEmpty();
    }
    public static ClassFileAnnotations parse(byte[] bytes) {
        try {
            return new Parser(bytes).parse();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final class Parser {
        private final ByteBuffer buf;
        private final byte[] bytes;
        private int[] cpOffsets;
        private final Set<String> types = new LinkedHashSet<>();

        Parser(byte[] bytes) {
            this.bytes = bytes;
            this.buf = ByteBuffer.wrap(bytes);
        }

        ClassFileAnnotations parse() {
            if (buf.getInt() != 0xCAFEBABE) throw new IllegalArgumentException("not a class file");
            buf.getShort();
            buf.getShort();
            int cpCount = u2();
            int cpStart = buf.position();
            cpOffsets = new int[cpCount];
            for (int i = 1; i < cpCount; i++) {
                cpOffsets[i] = buf.position();
                int tag = u1();
                switch (tag) {
                    case 1 -> skip(u2());
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> skip(4);
                    case 5, 6 -> { skip(8); i++; }
                    case 7, 8, 16, 19, 20 -> skip(2);
                    case 15 -> skip(3);
                    default -> throw new IllegalArgumentException("bad constant pool tag " + tag);
                }
            }
            byte[] cp = Arrays.copyOfRange(bytes, cpStart, buf.position());

            skip(6);
            skip(2 * u2());

            List<Member> members = new ArrayList<>();
            readMembers(members);
            readMembers(members);

            byte[] classAnns = null;
            int attrs = u2();
            for (int a = 0; a < attrs; a++) {
                String name = utf8(u2());
                int len = buf.getInt();
                if ("RuntimeVisibleAnnotations".equals(name)) {
                    classAnns = slice(len);
                    scanAnnotations(classAnns);
                } else {
                    skip(len);
                }
            }
            return new ClassFileAnnotations(cp, cpCount, classAnns, members, types);
        }

        private void readMembers(List<Member> out) {
            int count = u2();
            for (int i = 0; i < count; i++) {
                skip(2);
                String name = utf8(u2());
                String desc = utf8(u2());
                byte[] anns = null, params = null, dflt = null;
                int attrs = u2();
                for (int a = 0; a < attrs; a++) {
                    String attr = utf8(u2());
                    int len = buf.getInt();
                    switch (attr) {
                        case "RuntimeVisibleAnnotations" -> { anns = slice(len); scanAnnotations(anns); }
                        case "RuntimeVisibleParameterAnnotations" -> { params = slice(len); scanParameterAnnotations(params); }
                        case "AnnotationDefault" -> { dflt = slice(len); scanElementValue(ByteBuffer.wrap(dflt)); }
                        default -> skip(len);
                    }
                }
                if (anns != null || params != null || dflt != null) {
                    out.add(new Member(name, desc, anns, params, dflt));
                }
            }
        }

        private void scanAnnotations(byte[] data) {
            ByteBuffer b = ByteBuffer.wrap(data);
            int n = b.getShort() & 0xFFFF;
            for (int i = 0; i < n; i++) scanAnnotation(b);
        }

        private void scanParameterAnnotations(byte[] data) {
            ByteBuffer b = ByteBuffer.wrap(data);
            int params = b.get() & 0xFF;
            for (int p = 0; p < params; p++) {
                int n = b.getShort() & 0xFFFF;
                for (int i = 0; i < n; i++) scanAnnotation(b);
            }
        }

        private void scanAnnotation(ByteBuffer b) {
            types.add(utf8(b.getShort() & 0xFFFF));
            int pairs = b.getShort() & 0xFFFF;
            for (int i = 0; i < pairs; i++) {
                b.getShort();
                scanElementValue(b);
            }
        }

        private void scanElementValue(ByteBuffer b) {
            char tag = (char) (b.get() & 0xFF);
            switch (tag) {
                case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 's' -> b.getShort();
                case 'e' -> { types.add(utf8(b.getShort() & 0xFFFF)); b.getShort(); }
                case 'c' -> types.add(utf8(b.getShort() & 0xFFFF));
                case '@' -> scanAnnotation(b);
                case '[' -> {
                    int n = b.getShort() & 0xFFFF;
                    for (int i = 0; i < n; i++) scanElementValue(b);
                }
                default -> throw new IllegalArgumentException("bad element value tag " + tag);
            }
        }

        private String utf8(int index) {
            int off = cpOffsets[index];
            if ((bytes[off] & 0xFF) != 1) throw new IllegalArgumentException("not a UTF8 entry");
            int len = ((bytes[off + 1] & 0xFF) << 8) | (bytes[off + 2] & 0xFF);
            return decodeModifiedUtf8(bytes, off + 3, len);
        }

        private byte[] slice(int len) {
            byte[] out = new byte[len];
            buf.get(out);
            return out;
        }

        private int u1() { return buf.get() & 0xFF; }
        private int u2() { return buf.getShort() & 0xFFFF; }
        private void skip(int n) { buf.position(buf.position() + n); }
    }

    static String decodeModifiedUtf8(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder(len);
        int end = off + len;
        int i = off;
        while (i < end) {
            int c = b[i] & 0xFF;
            if (c < 0x80) {
                sb.append((char) c);
                i++;
            } else if ((c & 0xE0) == 0xC0) {
                sb.append((char) (((c & 0x1F) << 6) | (b[i + 1] & 0x3F)));
                i += 2;
            } else {
                sb.append((char) (((c & 0x0F) << 12) | ((b[i + 1] & 0x3F) << 6) | (b[i + 2] & 0x3F)));
                i += 3;
            }
        }
        return sb.toString();
    }
}
