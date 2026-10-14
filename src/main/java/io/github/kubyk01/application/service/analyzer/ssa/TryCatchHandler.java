package io.github.kubyk01.application.service.analyzer.ssa;

import io.github.kubyk01.domain.ir.BasicBlock;
import org.objectweb.asm.Label;

import java.util.*;

public class TryCatchHandler {

    private final Map<Label, BasicBlock> labelToBlock;
    private final List<TryCatchInfo> tryCatchBlocks = new ArrayList<>();

    public TryCatchHandler(Map<Label, BasicBlock> labelToBlock) {
        this.labelToBlock = labelToBlock;
    }

    public void addTryCatch(Label start, Label end, Label handler, String type) {
        tryCatchBlocks.add(new TryCatchInfo(start, end, handler, type));
    }

    public void handle() {
        final int n = tryCatchBlocks.size();
        if (n == 0) return;

        // -----------------------------------------------------------------
        // Pass 1: resolve every range's body against the CURRENT CFG, which
        // at this point contains no handler edges at all. Bodies are stored
        // in a parallel list so no mutation of the CFG can influence a
        // later range's body.
        // -----------------------------------------------------------------
        List<List<BasicBlock>> bodies = new ArrayList<>(n);
        for (TryCatchInfo info : tryCatchBlocks) {
            BasicBlock startBlock   = labelToBlock.get(info.start);
            BasicBlock endBlock     = labelToBlock.get(info.end);
            BasicBlock handlerBlock = labelToBlock.get(info.handler);
            if (startBlock == null || endBlock == null || handlerBlock == null) {
                bodies.add(null);
                continue;
            }
            bodies.add(GraphUtils.getBlocksBetween(
                    startBlock, endBlock, handlerBlock));
        }

        // -----------------------------------------------------------------
        // Pass 2: now that every body is fixed, install the normal
        // body -> handler edge for each range. Installing them all in a
        // second pass is what prevents range N's freshly-added handler
        // edge from contaminating range N+1's body.
        // -----------------------------------------------------------------
        for (int i = 0; i < n; i++) {
            List<BasicBlock> tryBlocks = bodies.get(i);
            if (tryBlocks == null) continue;

            TryCatchInfo info = tryCatchBlocks.get(i);
            BasicBlock handlerBlock = labelToBlock.get(info.handler);
            if (handlerBlock == null) continue;

            for (BasicBlock b : tryBlocks) {
                if (b == handlerBlock) continue;
                if (!b.getSuccessors().contains(handlerBlock)) {
                    b.addSuccessor(handlerBlock);
                }
            }
        }
    }

    private static class TryCatchInfo {
        final Label start, end, handler;
        final String type;
        TryCatchInfo(Label start, Label end, Label handler, String type) {
            this.start = start;
            this.end = end;
            this.handler = handler;
            this.type = type;
        }
    }
}