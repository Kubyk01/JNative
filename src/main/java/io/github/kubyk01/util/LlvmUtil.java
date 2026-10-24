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
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public class LlvmUtil {

    /**
     * Below this batch size, {@link #parallelMapOrdered} runs the mapper
     * inline on the calling thread. The Reactor hand-off (subscribeOn
     * per chunk, scheduler worker pickup, latch signal back) costs
     * more than the mapping work for a handful of items, and in the
     * tail of the worklist the batch routinely shrinks to one or two
     * entries.
     */
    private static final int INLINE_BATCH_THRESHOLD = 8;

    /**
     * Blocks the calling thread until {@code mono} terminates, then
     * returns the produced value or rethrows the produced error.
     *
     * <p>Both terminal signals — onComplete and onError — release the
     * latch. Reactor's contract guarantees that exactly one of the two
     * fires; counting down only on onComplete would convert every
     * exception raised inside a parallel phase into an apparently
     * infinite wait, because the pipeline has already terminated but
     * the caller's thread stays parked forever.</p>
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
     * Blocks the calling thread until {@code flux} terminates, then
     * returns the collected elements or rethrows the produced error.
     *
     * <p>Same reasoning as {@link #awaitMono(Mono)}: the latch is
     * released on both terminal signals.</p>
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

    /**
     * Maps every element of {@code items} through {@code mapper} and
     * returns the results in input order, running the mapper on the
     * supplied {@link Scheduler}.
     *
     * <h2>Why not {@code Flux.parallel().runOn(scheduler)}</h2>
     *
     * <p>The previous implementation was</p>
     *
     * <pre>
     *   Flux.fromIterable(tagged)
     *       .parallel()
     *       .runOn(sched)
     *       .map(p -&gt; new Indexed&lt;&gt;(p.index(), mapper.apply(p.value())))
     *       .sequential()
     * </pre>
     *
     * <p>and paid for it in three ways on every batch:</p>
     *
     * <ol>
     *   <li>One {@code Indexed} record was allocated per item, plus one
     *       for every result, plus a whole parallel fan-out array of
     *       internal {@link reactor.util.concurrent.Queues} rails,
     *       plus the {@code ParallelMergeSequential} coordinator at
     *       the end. For the 2048-element batches that the points-to
     *       analysis submits on every iteration of its worklist, that
     *       is several thousand short-lived allocations per batch and
     *       hundreds of thousands over the run.</li>
     *
     *   <li>{@code Flux.parallel()} uses {@code availableProcessors()}
     *       as its default fan-out, not the scheduler's worker count.
     *       The caller constructed the scheduler with an explicit
     *       {@code --cores} value, so the two numbers can differ, in
     *       which case the extra rails simply queue on the scheduler
     *       and contribute nothing.</li>
     *
     *   <li>{@code .sequential()} merges the rails back through an
     *       ordering buffer that then has to be drained by the
     *       collector, so every element makes a second trip through a
     *       queue that the caller never sees the benefit of.</li>
     * </ol>
     *
     * <h2>What this implementation does instead</h2>
     *
     * <p>It partitions the input once into {@code parallel} contiguous
     * chunks, wraps each chunk in a single {@link Mono} that runs on
     * the scheduler, and writes the mapper's output directly into a
     * pre-allocated result array at the chunk's own disjoint index
     * range. That removes the per-item {@code Indexed} objects, the
     * merge queue, and the second pass over the results.</p>
     *
     * <p>The disjoint-index write is what makes the direct array write
     * safe. Each chunk is processed by exactly one thread, and a
     * chunk's thread only ever touches indices in
     * {@code [chunkStart, chunkEnd)}. Two threads never touch the same
     * index, so no synchronization is needed beyond what Reactor
     * already provides for the fan-out itself.</p>
     *
     * <p>Small batches fall through to a plain loop on the calling
     * thread: the Reactor hand-off costs more than the mapping work
     * for a handful of items, and in the tail of the worklist the
     * batch routinely shrinks to one or two entries.</p>
     */
    public static <T, R> List<R> parallelMapOrdered(
            List<T> items,
            Scheduler scheduler,
            java.util.function.Function<T, R> mapper) {

        if (items == null || items.isEmpty()) {
            return new ArrayList<>();
        }
        final int n = items.size();

        if (n < INLINE_BATCH_THRESHOLD) {
            List<R> out = new ArrayList<>(n);
            for (T item : items) {
                out.add(mapper.apply(item));
            }
            return out;
        }

        final Scheduler sched = (scheduler != null)
                ? scheduler
                : Schedulers.single();

        final int parallelism = Math.clamp(Runtime.getRuntime().availableProcessors(), 1, n);
        final int chunkSize = (n + parallelism - 1) / parallelism;
        final int chunkCount = (n + chunkSize - 1) / chunkSize;

        @SuppressWarnings("unchecked")
        final R[] results = (R[]) new Object[n];

        awaitMono(
            Flux.range(0, chunkCount)
                .flatMap(chunkIdx -> Mono.fromRunnable(() -> {
                        int start = chunkIdx * chunkSize;
                        int end   = Math.min(start + chunkSize, n);
                        for (int i = start; i < end; i++) {
                            results[i] = mapper.apply(items.get(i));
                        }
                    }).subscribeOn(sched),
                    chunkCount)
                .then()
        );

        List<R> out = new ArrayList<>(n);
        out.addAll(Arrays.asList(results).subList(0, n));
        return out;
    }

    public static Type inferLocalType(Function func, int idx) {
        for (Parameter p : func.getParameters()) {
            if (p.getIndex() == idx) return p.getType();
        }
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
        return 8;
    }

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
                if (!inst.getOperands().isEmpty()) {
                    args.add(inst.getOperands().getFirst());
                }
                for (int i = 2; i < inst.getOperands().size(); i++) {
                    args.add(inst.getOperands().get(i));
                }
                break;
            }
            default: {
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

    private static final ConcurrentHashMap<String, String> CANONICAL_CALLEE_KEY_CACHE =
        new ConcurrentHashMap<>();

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

    private static String computeCanonicalCalleeKey(String calleeName) {
        if (calleeName.startsWith("fn_") || calleeName.startsWith("__jnative_")) {
            return calleeName;
        }
        int parenIdx = calleeName.indexOf('(');
        if (parenIdx <= 0) return calleeName;
        int dotIdx = calleeName.lastIndexOf('.', parenIdx);
        if (dotIdx <= 0) return calleeName;
        String owner = calleeName.substring(0, dotIdx);
        String methodPart = calleeName.substring(dotIdx + 1);
        int localParen = parenIdx - dotIdx - 1;
        if (localParen <= 0 || localParen > methodPart.length()) return calleeName;
        String name = methodPart.substring(0, localParen);
        String descriptor = methodPart.substring(localParen);
        return LlvmRuntime.mangleMethod(owner, name, descriptor);
    }

    public static boolean isLazyClinitWrapper(String mangledName) {
        return mangledName != null
            && mangledName.startsWith("fn___lazy_clinit_run_");
    }
}
