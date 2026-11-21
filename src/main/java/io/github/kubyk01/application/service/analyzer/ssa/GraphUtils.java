package io.github.kubyk01.application.service.analyzer.ssa;

import io.github.kubyk01.domain.ir.BasicBlock;

import java.util.*;

public final class GraphUtils {

    private GraphUtils() {}

    public static List<BasicBlock> getBlocksBetween(BasicBlock start, BasicBlock end) {
        return getBlocksBetween(start, end, null);
    }

    /**
     * Returns all basic blocks reachable from {@code start} without
     * passing through {@code end}, including both endpoints, and
     * excluding {@code excluded} (and, transitively, everything only
     * reachable through it).
     *
     * <p>This overload is the canonical entry point for computing the
     * body of a JVM try-range. The exclusion parameter exists for
     * exactly that caller pattern.</p>
     *
     * <p><b>Invariant:</b> the returned list never contains
     * {@code excluded}, even when {@code excluded == start} or
     * {@code excluded == end}. The earlier revision added {@code start}
     * and every polled block to the result unconditionally, and the
     * {@code excluded} filter only applied to successors — so a range
     * whose handler block was also its start block put that handler
     * into its own body, and the try-guard emitted for the handler then
     * branched back to the handler's own label, producing a structural
     * livelock with no exit. The check below makes the exclusion
     * unconditional.</p>
     */
    public static List<BasicBlock> getBlocksBetween(BasicBlock start,
                                                    BasicBlock end,
                                                    BasicBlock excluded) {
        List<BasicBlock> result = new ArrayList<>();
        if (start == null || end == null) return result;
        if (start == excluded) return result;

        Queue<BasicBlock> queue = new LinkedList<>();
        Set<BasicBlock> visited = new HashSet<>();
        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty()) {
            BasicBlock b = queue.poll();
            if (b == excluded) continue;      // belt-and-braces
            result.add(b);
            if (b == end) break;
            for (BasicBlock succ : b.getSuccessors()) {
                if (succ == excluded) continue;
                if (!visited.contains(succ)) {
                    visited.add(succ);
                    queue.add(succ);
                }
            }
        }
        return result;
    }
}