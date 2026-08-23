package io.github.kubyk01.application.service.analyzer.ssa;

import io.github.kubyk01.domain.analyzer.ir.BasicBlock;
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
        for (TryCatchInfo info : tryCatchBlocks) {
            BasicBlock startBlock = labelToBlock.get(info.start);
            BasicBlock endBlock = labelToBlock.get(info.end);
            BasicBlock handlerBlock = labelToBlock.get(info.handler);
            if (startBlock == null || endBlock == null || handlerBlock == null) continue;
            List<BasicBlock> tryBlocks = GraphUtils.getBlocksBetween(startBlock, endBlock);
            for (BasicBlock b : tryBlocks) {
                // addSuccessor updates both sides of the edge; the duplicate guard
                // is required for multi-catch (one handler for several ranges)
                if (!b.getSuccessors().contains(handlerBlock)) {
                    b.addSuccessor(handlerBlock);          // edge to the handler
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
