package io.github.kubyk01.application.service.analyzer.escapeanalysis;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeStatus;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeSummary;
import io.github.kubyk01.domain.analyzer.ir.BasicBlock;
import io.github.kubyk01.domain.analyzer.ir.Function;
import io.github.kubyk01.domain.analyzer.ir.Instruction;
import io.github.kubyk01.domain.analyzer.ir.Module;
import io.github.kubyk01.domain.analyzer.ir.Opcode;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class InterproceduralEscape {

    private final Module module;
    private final AliasAnalysisResult aliasResult;
    private final Map<String, EscapeSummary> summaries;
    private final Map<AllocationSite, EscapeStatus> globalStatus = new HashMap<>();

    public InterproceduralEscape(Module module, AliasAnalysisResult aliasResult,
                                 Map<String, EscapeSummary> summaries) {
        this.module = module;
        this.aliasResult = aliasResult;
        this.summaries = summaries;
    }

    public Map<AllocationSite, EscapeStatus> analyze() {
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() == null) continue;
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    if (isAllocation(inst.getOpcode()) && inst.getResult() != null) {
                        PointsToSet pts = aliasResult.getPointsTo(inst.getResult());
                        for (AllocationSite site : pts.getSites()) {
                            globalStatus.put(site, EscapeStatus.STACK);
                        }
                    }
                }
            }
        }

        boolean changed = true;
        while (changed) {
            changed = false;
            for (Function func : module.getFunctions()) {
                if (func.getEntryBlock() == null) continue;
                IntraproceduralEscape intra = new IntraproceduralEscape(func, aliasResult, summaries);
                Map<AllocationSite, EscapeStatus> localStatus = intra.analyze();
                for (Map.Entry<AllocationSite, EscapeStatus> entry : localStatus.entrySet()) {
                    EscapeStatus old = globalStatus.getOrDefault(entry.getKey(), EscapeStatus.STACK);
                    if (entry.getValue().ordinal() > old.ordinal()) {
                        globalStatus.put(entry.getKey(), entry.getValue());
                        changed = true;
                    }
                }
            }
        }

        return globalStatus;
    }

    private boolean isAllocation(Opcode op) {
        return op == Opcode.NEW || op == Opcode.NEW_ARRAY || op == Opcode.MULTI_NEW_ARRAY;
    }
}
