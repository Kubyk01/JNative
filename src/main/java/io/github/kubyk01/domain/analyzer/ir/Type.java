package io.github.kubyk01.domain.analyzer.ir;

import lombok.EqualsAndHashCode;
import lombok.Getter;

@Getter
@EqualsAndHashCode
public abstract class Type {
    public static final PrimitiveType VOID    = new PrimitiveType("void");
    public static final PrimitiveType BOOLEAN = new PrimitiveType("boolean");
    public static final PrimitiveType BYTE    = new PrimitiveType("byte");
    public static final PrimitiveType SHORT   = new PrimitiveType("short");
    public static final PrimitiveType CHAR    = new PrimitiveType("char");
    public static final PrimitiveType INT     = new PrimitiveType("int");
    public static final PrimitiveType LONG    = new PrimitiveType("long");
    public static final PrimitiveType FLOAT   = new PrimitiveType("float");
    public static final PrimitiveType DOUBLE  = new PrimitiveType("double");
    public static final NullType NULL         = new NullType();
    public static final BlockType BLOCK       = new BlockType();
    public static final UnknownType UNKNOWN   = new UnknownType();

    private final TypeKind kind;

    protected Type(TypeKind kind) { this.kind = kind; }

    public boolean isPrimitive()   { return kind == TypeKind.PRIMITIVE; }
    public boolean isReference()   { return kind == TypeKind.REFERENCE; }
    public boolean isArray()       { return kind == TypeKind.ARRAY; }
    public boolean isVoid()        { return this == VOID; }
    public boolean isNull()        { return this == NULL; }
    public boolean isBlock()       { return this == BLOCK; }
    public boolean isUnknown()     { return this == UNKNOWN; }

    public String getClassName() {
        throw new UnsupportedOperationException("Not a reference type");
    }

    public Type getElementType() {
        throw new UnsupportedOperationException("Not an array type");
    }

    // Factories
    public static ReferenceType reference(String className) {
        return new ReferenceType(className);
    }

    public static ArrayType array(Type elementType) {
        return new ArrayType(elementType);
    }

    public static ArrayType array(String descriptor) {
        return ArrayType.fromDescriptor(descriptor);
    }

    public static Type fromDescriptor(String desc) {
        if (desc == null || desc.isEmpty()) return UNKNOWN;
        if (desc.length() == 1) {
            return switch (desc.charAt(0)) {
                case 'Z' -> BOOLEAN;
                case 'B' -> BYTE;
                case 'S' -> SHORT;
                case 'C' -> CHAR;
                case 'I' -> INT;
                case 'J' -> LONG;
                case 'F' -> FLOAT;
                case 'D' -> DOUBLE;
                case 'V' -> VOID;
                default -> UNKNOWN;
            };
        } else if (desc.startsWith("L")) {
            String name = desc.substring(1, desc.length() - 1);
            return reference(name);
        } else if (desc.startsWith("[")) {
            return array(desc);
        } else {
            return UNKNOWN;
        }
    }

    @Override public String toString() { return kind.name(); }

    // ---- Nested classes ----
    public static class PrimitiveType extends Type {
        private final String name;
        private PrimitiveType(String name) { super(TypeKind.PRIMITIVE); this.name = name; }
        @Override public String toString() { return name; }
    }

    public static class ReferenceType extends Type {
        private final String className;
        private ReferenceType(String className) { super(TypeKind.REFERENCE); this.className = className; }
        @Override public String getClassName() { return className; }
        @Override public String toString() { return "ref(" + className + ")"; }
    }

    public static class ArrayType extends Type {
        private final Type elementType;
        private ArrayType(Type elementType) { super(TypeKind.ARRAY); this.elementType = elementType; }
        @Override public Type getElementType() { return elementType; }
        @Override public String toString() { return "array[" + elementType + "]"; }

        public static ArrayType fromDescriptor(String desc) {
            if (!desc.startsWith("[")) {
                throw new IllegalArgumentException("Not an array descriptor: " + desc);
            }

            String inner = desc.substring(1);

            if (inner.startsWith("[")) {
                return new ArrayType(fromDescriptor(inner));
            }

            return new ArrayType(Type.fromDescriptor(inner));
        }
    }

    public static class NullType extends Type {
        private NullType() { super(TypeKind.NULL); }
        @Override public String toString() { return "null"; }
    }

    public static class BlockType extends Type {
        private BlockType() { super(TypeKind.BLOCK); }
        @Override public String toString() { return "block"; }
    }

    public static class UnknownType extends Type {
        private UnknownType() { super(TypeKind.UNKNOWN); }
        @Override public String toString() { return "unknown"; }
    }

    public enum TypeKind {
        PRIMITIVE, REFERENCE, ARRAY, NULL, BLOCK, UNKNOWN
    }
}
