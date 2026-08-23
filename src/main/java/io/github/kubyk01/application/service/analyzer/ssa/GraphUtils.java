package io.github.kubyk01.application.service.analyzer.ssa;

import io.github.kubyk01.domain.analyzer.ir.BasicBlock;

import java.util.*;

public final class GraphUtils {

    private GraphUtils() {}

    /**
     * Returns all basic blocks reachable from start without passing through end,
     * including both start and end. Uses simple BFS.
     */
    public static List<BasicBlock> getBlocksBetween(BasicBlock start, BasicBlock end) {
        List<BasicBlock> result = new ArrayList<>();
        if (start == null || end == null) return result;
        Queue<BasicBlock> queue = new LinkedList<>();
        Set<BasicBlock> visited = new HashSet<>();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty()) {
            BasicBlock b = queue.poll();
            result.add(b);
            if (b == end) break;
            for (BasicBlock succ : b.getSuccessors()) {
                if (!visited.contains(succ)) {
                    visited.add(succ);
                    queue.add(succ);
                }
            }
        }
        return result;
    }
}
