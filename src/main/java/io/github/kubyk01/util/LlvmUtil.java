package io.github.kubyk01.util;

import io.github.kubyk01.application.service.codegen.llvm.LlvmRuntime;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.Value;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public class LlvmUtil {

    /**
     * Blocks the calling thread until {@code mono} terminates, then returns
     * the produced value or rethrows the produced error.
     *
     * <p>Both terminal signals — onComplete and onError — must release the
     * latch. Reactor's contract guarantees that exactly one of the two
     * fires, but a caller that blocks on {@code latch.await()} cannot know
     * which one it is without being released. Counting down only on
     * onComplete means an error inside a parallel {@code map} (NPE,
     * ClassCast, OOM, IllegalArgumentException from
     * {@code ConcurrentHashMap.get(null)}, anything) is silently converted
     * into an infinite wait: the pipeline has already terminated, but the
     * caller's thread stays parked forever with no output and no stack.
     *
     * <p>The previous revision exhibited exactly that behaviour. The
     * decision to count down on onError as well is what makes the fix
     * possible to diagnose: the next time a worker throws, the caller
     * sees the exception and its stack instead of a hang.</p>
     */
    public static <T> T awaitMono(Mono<T> mono) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        mono.subscribe(
            value::set,
            e -> {
                error.set(e);
                latch.countDown();
            },
            latch::countDown
        );

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while awaiting Mono", e);
        }
        if (error.get() != null) {
            throw new RuntimeException("reactive pipeline failed", error.get());
        }
        return value.get();
    }

    /**
     * Blocks the calling thread until {@code flux} terminates, then returns
     * the collected elements or rethrows the produced error.
     *
     * <p>Same reasoning as {@link #awaitMono(Mono)}: the latch must be
     * released on both terminal signals. Counting down only on onComplete
     * turns every exception thrown inside a parallel phase into an
     * apparently infinite wait.</p>
     */
    public static <T> List<T> awaitFlux(Flux<T> flux) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<T>> collected = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        flux.collectList().subscribe(
                collected::set,
                e -> {
                    error.set(e);
                    latch.countDown();
                },
                latch::countDown
        );

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while awaiting Flux", e);
        }
        if (error.get() != null) {
            throw new RuntimeException("reactive pipeline failed", error.get());
        }
        return collected.get();
    }

    public static Type inferLocalType(Function func, int idx) {
        for (Parameter p : func.getParameters()) {
            if (p.getIndex() == idx) return p.getType();
        }

        // Prefer a STORE result: after IrBuilder.createStore was changed
        // to take an explicit slot type, the result carries the type that
        // the bytecode opcode mandated for the slot (ISTORE -> INT, &c),
        // not the type of the value being stored. Falling back to the
        // operand's type keeps this correct on IR produced by an older
        // build, where the result was stamped with the stored value's own
        // type.
        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (inst.getOpcode() != Opcode.STORE) continue;
                if (inst.getLocalIndex() != idx) continue;

                if (inst.getResult() != null
                    && !inst.getResult().getType().isUnknown()) {
                    return inst.getResult().getType();
                }
                if (!inst.getOperands().isEmpty()) {
                    Type operandType = inst.getOperands().getFirst().getType();
                    if (!operandType.isUnknown()) return operandType;
                }
            }
        }

        // No STORE at all: the slot can only be set outside the function
        // body (JSR/RET) or is a never-reassigned parameter that already
        // matched above. Fall back to whatever the LOADs declare.
        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (inst.getOpcode() != Opcode.LOAD) continue;
                if (inst.getLocalIndex() != idx) continue;
                if (inst.getResult() != null
                    && !inst.getResult().getType().isUnknown()) {
                    return inst.getResult().getType();
                }
            }
        }

        return Type.UNKNOWN;
    }

    public static int getElementSizeOfType(Type type) {
        if (type.isPrimitive()) {
            if (type == Type.BOOLEAN || type == Type.BYTE) return 1;
            if (type == Type.SHORT || type == Type.CHAR) return 2;
            if (type == Type.INT || type == Type.FLOAT) return 4;
            if (type == Type.LONG || type == Type.DOUBLE) return 8;
        }
        if (type.isReference() || type.isArray()) {
            return 8;
        }
        return 8;
    }

    /**
     * The field name depends on the opcode: for GET_FIELD/PUT_FIELD the field
     * constant is in operand 1, for GET_STATIC/PUT_STATIC - in operand 0.
     * The full name (including the class) is returned, which prevents name
     * collisions between static fields of different classes.
     */
    public static String extractFieldName(Instruction inst) {
        int fieldIdx = (inst.getOpcode() == Opcode.GET_STATIC
            || inst.getOpcode() == Opcode.PUT_STATIC) ? 0 : 1;
        if (inst.getOperands().size() > fieldIdx) {
            Value v = inst.getOperands().get(fieldIdx);
            if (v instanceof Constant c && c.getType().isReference()) {
                return c.getValue().toString();
            }
        }
        return "unknown";
    }

    public static String extractCalleeName(Instruction inst) {
        int idx = switch (inst.getOpcode()) {
            case VIRTUAL_CALL, INTERFACE_CALL, SPECIAL_CALL -> 1;
            default -> 0;
        };
        if (inst.getOperands().size() > idx) {
            Value v = inst.getOperands().get(idx);
            if (v instanceof Constant c && c.getType().isReference()) {
                return c.getValue().toString();
            }
        }
        return null;
    }

    public static List<Value> getCallArguments(Instruction inst) {
        Opcode op = inst.getOpcode();
        List<Value> args = new ArrayList<>();
        switch (op) {
            case VIRTUAL_CALL, INTERFACE_CALL, SPECIAL_CALL: {
                // operands = [receiver, calleeConst, arg0, arg1, ...]
                // receiver —  0; calleeConst skip.
                if (!inst.getOperands().isEmpty()) {
                    args.add(inst.getOperands().getFirst());
                }
                for (int i = 2; i < inst.getOperands().size(); i++) {
                    args.add(inst.getOperands().get(i));
                }
                break;
            }
            default: {
                // CALL / STATIC_CALL: operands = [calleeConst, arg0, arg1, ...]
                for (int i = 1; i < inst.getOperands().size(); i++) {
                    args.add(inst.getOperands().get(i));
                }
                break;
            }
        }
        return args;
    }

    public static String extractTypeName(Instruction inst) {
        for (Value v : inst.getOperands()) {
            if (v instanceof Constant c && c.getType().isReference()) {
                return c.getValue().toString();
            }
        }
        return "java/lang/Object";
    }

    public static String extractClassName(Value v) {
        if (v.getType().isReference()) {
            return v.getType().getClassName();
        } else if (v.getType().isArray()) {
            Type elem = v.getType().getElementType();
            if (elem.isReference()) return elem.getClassName();
            else return "java/lang/Object";
        }
        return "java/lang/Object";
    }

    public static boolean isAllocation(Opcode op) {
        return op == Opcode.NEW || op == Opcode.NEW_ARRAY || op == Opcode.MULTI_NEW_ARRAY;
    }

    public static String[] extractFieldOwnerAndName(Instruction inst) {
        String full = extractFieldName(inst);
        int dot = full.lastIndexOf('.');
        if (dot > 0) return new String[]{full.substring(0, dot), full.substring(dot + 1)};
        return new String[]{"", full};
    }

    /**
     * Returns {@code true} for class names that belong to the JDK, to the
     * runtime's own third-party dependencies, or to any other package that is
     * not part of the user's program.
     *
     * <p>Callers use this predicate to distinguish "user code" (the classes
     * the user actually wrote, which are of primary interest for the
     * analysis reports and the destructor pass) from the JDK and library
     * classes that the reachability walk also drags in. It is intentionally
     * a simple prefix test on a fixed list of package roots: the classifier
     * is about provenance, not about VM semantics, and a heuristic is enough
     * for every caller that consults it.</p>
     */
    public static boolean isSystemClassName(String className) {
        String dot = className.replace('/', '.');
        return dot.startsWith("java.") ||
            dot.startsWith("javax.") ||
            dot.startsWith("sun.") ||
            dot.startsWith("com.sun.") ||
            dot.startsWith("jdk.") ||
            dot.startsWith("org.objectweb.asm.") ||
            dot.startsWith("picocli.") ||
            dot.startsWith("reactor.") ||
            dot.startsWith("org.slf4j.") ||
            dot.startsWith("org.reactivestreams.") ||
            dot.startsWith("io.micrometer.") ||
            dot.startsWith("org.junit.") ||
            dot.startsWith("com.fasterxml.");
    }

    /**
     * Memoisation of {@link #computeCanonicalCalleeKey(String)}.
     *
     * <p>The key is the raw callee name in the {@code "owner.name(desc)"}
     * form; the value is the already-mangled {@code "fn_owner_name_desc"}
     * string. The set of distinct callee names is bounded by the number of
     * reachable methods in the image (tens of thousands on a full JDK
     * closure), so the cache is bounded and is not a leak.</p>
     *
     * <p>Called from the hot {@code processCall} loops of
     * {@code InterproceduralPointsTo}, {@code SummaryBuilder} and
     * {@code EscapeSummaryBuilder}. Without the cache every evaluation of
     * every function produces a fresh {@code Pattern.compile} inside
     * {@code LlvmRuntime.mangleMethod} — millions of compilations per phase,
     * visible in the worker stacks as {@code Pattern.clazz} /
     * {@code Pattern.compile} / {@code String.replaceAll}.</p>
     */
    private static final ConcurrentHashMap<String, String> CANONICAL_CALLEE_KEY_CACHE =
        new ConcurrentHashMap<>();

    /**
     * Canonicalises a callee reference to the mangled form
     * {@code fn_<owner>_<name>_<descriptor>} that keys every summary map.
     *
     * <p>The IR stores a call instruction's callee as a dotted string of the
     * form {@code <owner>.<name><descriptor>}. See
     * {@code InstructionHandlers.callMethod}, which builds the callee
     * constant as {@code owner + "." + name + desc}. Every
     * {@link io.github.kubyk01.domain.ir.Function} in the module, however,
     * is registered under its mangled name produced by
     * {@link LlvmRuntime#mangleMethod(String, String, String)}. The two
     * strings are different for the same target, so a summary map keyed by
     * one form can never be found by a lookup using the other. This helper
     * bridges the two conventions and is applied at every summary-map
     * boundary.</p>
     *
     * <p>Names that already carry a mangled or runtime prefix
     * ({@code fn_...}, {@code __jnative_...}) are returned unchanged, so
     * the helper is idempotent and safe to apply to either form. Names
     * that do not have the dotted method shape (no dot before the first
     * parenthesis, or an empty method-name part) are also returned
     * unchanged — a mismatch in that case simply fails to find a map
     * entry, which is the correct behaviour for a name that has no
     * summary anyway.</p>
     *
     * <p>Memoisation: the same input string produces the same output string,
     * and the function is called tens of millions of times per analysis run
     * with a bounded repertoire of distinct inputs (one per reachable call
     * site). Caching eliminates the repeated {@code Pattern.compile} inside
     * {@link LlvmRuntime#mangleMethod} and the repeated {@code StringBuilder}
     * allocations that would otherwise dominate the worker threads' CPU.</p>
     */
    public static String canonicalCalleeKey(String calleeName) {
        if (calleeName == null || calleeName.isEmpty()) {
            return calleeName;
        }
        String cached = CANONICAL_CALLEE_KEY_CACHE.get(calleeName);
        if (cached != null) {
            return cached;
        }
        String computed = computeCanonicalCalleeKey(calleeName);
        CANONICAL_CALLEE_KEY_CACHE.put(calleeName, computed);
        return computed;
    }

    /**
     * Core of {@link #canonicalCalleeKey(String)} without the cache. A
     * separate method so the cache hit on the hot path is exactly one
     * {@code ConcurrentHashMap.get}, with no interpolation of the
     * transformation logic.
     */
    private static String computeCanonicalCalleeKey(String calleeName) {
        if (calleeName.startsWith("fn_") || calleeName.startsWith("__jnative_")) {
            return calleeName;
        }
        int parenIdx = calleeName.indexOf('(');
        if (parenIdx <= 0) {
            return calleeName;
        }
        int dotIdx = calleeName.lastIndexOf('.', parenIdx);
        if (dotIdx <= 0) {
            return calleeName;
        }
        String owner = calleeName.substring(0, dotIdx);
        String methodPart = calleeName.substring(dotIdx + 1);
        int localParen = parenIdx - dotIdx - 1;
        if (localParen <= 0 || localParen > methodPart.length()) {
            return calleeName;
        }
        String name = methodPart.substring(0, localParen);
        String descriptor = methodPart.substring(localParen);
        return LlvmRuntime.mangleMethod(owner, name, descriptor);
    }

    /**
     * Returns {@code true} for the mangled name of a lazy {@code <clinit>}
     * wrapper emitted by the lazy-clinit instrumentation pass.
     *
     * <p>These wrappers are inserted as a {@code fn___lazy_clinit_run_<C>}
     * call at every active-use site of each cyclic class, so on a full JDK
     * image there are tens of thousands of such call edges. A wrapper's body
     * is a deterministic state machine — call the super-wrapper, call
     * {@code __jnative_clinit_enter}, call the real {@code <clinit>} body,
     * call {@code __jnative_clinit_exit} — and no wrapper returns a value or
     * accepts a parameter. A change to a wrapper's summary therefore
     * <b>cannot physically</b> affect a caller through return-value or
     * parameter-write propagation.</p>
     *
     * <p>The three worklist analyses (InterproceduralPointsTo,
     * InterproceduralEscape, EscapeSummaryBuilder) consequently skip the
     * caller edge for these wrappers. Without the skip the cascade
     * "wrapper changed -> re-enqueue its N callers -> each caller changes ->
     * re-enqueue its callers -> ..." inflates the worklist to tens of
     * millions of operations: on a full JDK image, adding 16093 triggers
     * turns a single analysis run into minutes of waiting.</p>
     */
    public static boolean isLazyClinitWrapper(String mangledName) {
        return mangledName != null
            && mangledName.startsWith("fn___lazy_clinit_run_");
    }
}