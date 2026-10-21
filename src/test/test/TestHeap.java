/**
 * TestHeap — exercises the HEAP escape classification.
 *
 * <p>In the JNative escape analysis ({@code IntraproceduralEscape} /
 * {@code EscapeSummaryBuilder}), an allocation site is classified as
 * {@link io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeStatus#HEAP}
 * when the object it produces is stored into a <em>field of another
 * object</em>, and that object itself escapes the defining function's
 * local scope. The canonical way to trigger this in a well-defined way
 * is to accept a receiver or container as a <b>method parameter</b>
 * (parameters are conservatively promoted to {@code HEAP} by
 * {@code IntraproceduralEscape.valueEscapeStatus}) and store the fresh
 * allocation into a field of that parameter:
 *
 *     static void storePayload(Holder h, long i, int pad) {
 *         Payload p = new Payload(i, pad);
 *         h.payload = p;          // <- p becomes HEAP
 *     }
 *
 * <h2>Why the loop</h2>
 *
 * <p>Under the current lifetime design, {@code HEAP} sites get
 * <b>no per-use destructor</b>:
 * {@link io.github.kubyk01.application.service.analyzer.lifetime.LifetimeAnalyzer#computeSite}
 * short-circuits on {@code EscapeStatus.HEAP} and returns an empty
 * destruction-point list, on the theory that the object is owned by
 * the containing object and will be freed by that container's
 * destructor chain.</p>
 *
 * <p>That theory is sound when the container is destroyed promptly
 * (for example, a freshly created {@code Holder} that goes out of
 * scope at the end of the iteration). It breaks down when the
 * container is <b>long-lived and its field is overwritten
 * repeatedly</b>:</p>
 *
 * <pre>
 *     Holder h = new Holder();               // created once
 *     for (int i = 0; i < N; i++) {
 *         Payload p = new Payload(i, 32);    // fresh HEAP object
 *         h.payload = p;                     // previous p becomes orphaned
 *     }
 *     // at the end, only the LAST payload is reachable via h.payload
 * </pre>
 *
 * <p>Every intermediate {@code Payload} loses its last reference at
 * the assignment {@code h.payload = p} of the next iteration, but no
 * destructor is ever emitted for it, and no GC exists to reclaim it.
 * If the runtime's HEAP handling is correct, the process either
 * completes fast (because the memory is somehow being reused) or
 * fails with a diagnosable error; if it is not correct, the RSS grows
 * linearly with the iteration count until the kernel's OOM killer
 * fires.</p>
 *
 * <h2>What the test measures</h2>
 *
 * <p>Wall-clock rate per million iterations. A correctly handled HEAP
 * classification should complete the full run at a stable rate. A
 * leaking one will either:</p>
 * <ol>
 *   <li>slow down dramatically as the allocator starts fighting the
 *       kernel for pages, or</li>
 *   <li>fail with {@code OutOfMemoryError} at some point during the
 *       loop, or</li>
 *   <li>be killed by the OOM killer with no Java-level exception at
 *       all (visible only as a nonzero exit status).</li>
 * </ol>
 *
 * <p>Run under {@code /usr/bin/time -v} or {@code top -p $(pidof
 * TestHeap)} to observe RSS directly:</p>
 *
 * <pre>
 *     /usr/bin/time -v ./TestHeap
 * </pre>
 *
 * <p>Compare against running on a regular HotSpot JVM: there the
 * {@code Payload} objects are reclaimed by the garbage collector
 * without any explicit reference-clearing, so the same loop completes
 * with flat memory usage.</p>
 */
public final class TestHeap {

    // ------------------------------------------------------------------
    //  Data types
    // ------------------------------------------------------------------

    /**
     * The object whose allocation we want the analysis to classify as
     * HEAP. The {@code padding} array makes each instance large enough
     * that any leak shows up as a measurable RSS increase rather than
     * as a handful of missed bytes.
     */
    static final class Payload {
        final long   value;
        final byte[] padding;

        Payload(long value, int padSize) {
            this.value   = value;
            this.padding = new byte[padSize];
            if (padSize > 0) {
                // Touch the array so the JIT / LLVM backend cannot
                // eliminate the allocation as dead.
                padding[0]           = (byte)  value;
                padding[padSize - 1] = (byte) (value >>> 8);
            }
        }
    }

    /**
     * The container. Kept long-lived across the whole loop so that its
     * {@code payload} field is overwritten repeatedly.
     */
    static final class Holder {
        Payload payload;
    }

    // ------------------------------------------------------------------
    //  Workload
    // ------------------------------------------------------------------

    /**
     * Allocates a fresh {@code Payload} and stores it into the
     * parameter's field.
     *
     * <p>The store {@code h.payload = p} is what makes the escape
     * analysis mark the {@code new Payload(...)} site as
     * {@code EscapeStatus.HEAP}: {@code h} is a method parameter, so
     * {@code IntraproceduralEscape.valueEscapeStatus} promotes it to at
     * least {@code HEAP}, and the {@code PUT_FIELD} handler
     * ({@code markEscaped(rhs, baseEsc)}) propagates that status to
     * {@code p}.</p>
     */
    static void storePayload(Holder h, long i, int padSize) {
        Payload p = new Payload(i, padSize);
        h.payload = p;
    }

    // ------------------------------------------------------------------
    //  Entry point
    // ------------------------------------------------------------------

    public static void main(String[] args) {
        int iterations = 10_000_000;
        int padSize    = 32;

        if (args.length >= 1) {
            try { iterations = Integer.parseInt(args[0]); }
            catch (NumberFormatException ignored) { }
        }
        if (args.length >= 2) {
            try { padSize = Integer.parseInt(args[1]); }
            catch (NumberFormatException ignored) { }
        }

        long theoreticalLeakMB =
                ((long) iterations * (padSize + 32L)) / (1024L * 1024L);

        System.out.println("Test Heap");
        System.out.println("=============================================");
        System.out.println("  iterations        : " + iterations);
        System.out.println("  pad size per obj  : " + padSize + " bytes");
        System.out.println("  leak size if buggy: ~" + theoreticalLeakMB + " MB");
        System.out.println();
        System.out.println("Each iteration stores a fresh Payload into the field of");
        System.out.println("a long-lived Holder. The analysis classifies the Payload");
        System.out.println("allocation site as HEAP; HEAP sites get no per-use");
        System.out.println("destructor. If the runtime is correct, RSS stays flat");
        System.out.println("and the loop completes at a stable rate.");
        System.out.println();

        final Holder holder = new Holder();

        long startNs           = System.nanoTime();
        long lastCheckpointNs  = startNs;
        long lastCheckpointIter = 0L;

        try {
            for (long i = 0; i < iterations; i++) {
                storePayload(holder, i, padSize);

                long now = System.nanoTime();
                if (now - lastCheckpointNs >= 1_000_000_000L) {
                    long elapsedMs   = (now - startNs) / 1_000_000L;
                    long deltaIter   = i - lastCheckpointIter;
                    long deltaNs     = now - lastCheckpointNs;
                    long iterPerSec  = (deltaNs > 0)
                            ? (deltaIter * 1_000_000_000L) / deltaNs
                            : 0L;
                    System.out.println(
                            "  iter=" + i
                                    + "  elapsed=" + elapsedMs + " ms"
                                    + "  rate=" + iterPerSec + " iter/s");
                    lastCheckpointNs   = now;
                    lastCheckpointIter = i;
                }
            }

            long totalMs = (System.nanoTime() - startNs) / 1_000_000L;
            long finalValue = holder.payload.value;

            System.out.println();
            System.out.println("COMPLETED:");
            System.out.println("  total time  : " + totalMs + " ms");
            System.out.println("  final value : " + finalValue);
            System.out.println();
            System.out.println("The loop finished without OOM. If the rate stayed");
            System.out.println("stable across checkpoints, HEAP allocations are");
            System.out.println("being reclaimed. Verify with:");
            System.out.println("  /usr/bin/time -v ./TestHeap");

        } catch (OutOfMemoryError oom) {
            System.out.println();
            System.out.println("FAILED: OutOfMemoryError at iteration "
                    + lastCheckpointIter);
            System.out.println("HEAP allocations are leaking — the analysis");
            System.out.println("correctly classified the Payload as HEAP, but the");
            System.out.println("lifetime pass emitted no destructor for it and no");
            System.out.println("GC exists to reclaim it.");
            oom.printStackTrace();
            throw oom;
        }

        // Release the last live reference. In a leaking implementation
        // this frees the FINAL payload (through Holder's destructor
        // chain); all the other 9_999_999 remain unreachable and
        // unreclaimed.
        holder.payload = null;
    }

    private TestHeap() { }
}