package io.github.kubyk01.domain.analyzer.reachability;

import io.github.kubyk01.domain.ir.Type;
import lombok.Getter;

import static io.github.kubyk01.domain.ir.Type.reference;

@Getter
public final class TypedValue {
    public static final TypedValue UNKNOWN = new TypedValue(Type.UNKNOWN, null, null);
    public static final TypedValue NULL = new TypedValue(Type.NULL, null, null);
    public static final TypedValue INT = new TypedValue(Type.INT, null, null);
    public static final TypedValue LONG = new TypedValue(Type.LONG, null, null);
    public static final TypedValue FLOAT = new TypedValue(Type.FLOAT, null, null);
    public static final TypedValue DOUBLE = new TypedValue(Type.DOUBLE, null, null);
    public static final TypedValue BLOCK = new TypedValue(Type.BLOCK, null, null);

    private final Type type;
    private final String className; // non-null only for reference types with known exact class
    private final Object value; // concrete constant value, if any

    private TypedValue(Type type, String className, Object value) {
        this.type = type;
        this.className = className;
        this.value = value;
    }

    public static TypedValue fromType(Type type) {
        if (type.isReference()) {
            return new TypedValue(type, null, null);
        }
        return new TypedValue(type, null, null);
    }

    public static TypedValue fromReference(String className) {
        return new TypedValue(reference(className), className, null);
    }

    public static TypedValue fromConstant(Type type, Object value) {
        return new TypedValue(type, null, value);
    }

    public boolean isExact() {
        return className != null && type.isReference();
    }

    public boolean isConstant() {
        return value != null;
    }

    @Override
    public String toString() {
        if (value != null) return "const(" + value + ")";
        if (className != null) return "exact(" + className + ")";
        return type.toString();
    }
}