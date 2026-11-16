package io.github.kubyk01.domain.ir;

import lombok.Getter;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

@Getter
public abstract class Value {
    private static final VarHandle POINTS_TO;

    static {
        try {
            POINTS_TO = MethodHandles.lookup().findVarHandle(Value.class, "pointsTo", Object.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Type type;
    private final int id;
    private volatile Object pointsTo;

    private static int nextId = 0;

    protected Value(Type type) {
        this.type = type;
        this.id = nextId++;
    }

    public boolean compareAndSetPointsTo(Object expected, Object update) {
        return POINTS_TO.compareAndSet(this, expected, update);
    }

    public void clearPointsTo() {
        pointsTo = null;
    }

    @Override
    public String toString() {
        return "v" + id + "(" + type + ")";
    }
}
