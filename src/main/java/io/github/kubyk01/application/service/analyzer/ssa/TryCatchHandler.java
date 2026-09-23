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
        for (TryCatchInfo info : tryCatchBlocks) {
            BasicBlock startBlock   = labelToBlock.get(info.start);
            BasicBlock endBlock     = labelToBlock.get(info.end);
            BasicBlock handlerBlock = labelToBlock.get(info.handler);
            if (startBlock == null || endBlock == null || handlerBlock == null) continue;

            // The handler is reached from the body by an exceptional edge,
            // not by normal flow, so it must not be enumerated as part of
            // the body. Excluding it here also prevents the handler from
            // being given a normal self-edge when it contains a throwing
            // instruction — that self-edge was the mechanism behind the
            // structural livelock in NormalizerImpl.load and
            // ScopedMemoryAccess.copyMemoryInternal.
            List<BasicBlock> tryBlocks =
                GraphUtils.getBlocksBetween(startBlock, endBlock, handlerBlock);

            for (BasicBlock b : tryBlocks) {
                if (b == handlerBlock) continue;   // explicit, belt-and-braces
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