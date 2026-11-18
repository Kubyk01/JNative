package io.github.kubyk01.domain.analyzer.lifetime.strategies;

import io.github.kubyk01.application.service.analyzer.lifetime.LivenessAnalyzer;
import io.github.kubyk01.application.service.analyzer.ssa.DominatorTree;
import io.github.kubyk01.domain.analyzer.Discoverable;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult;
import io.github.kubyk01.domain.analyzer.lifetime.LifetimeAnalysisResult;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Module;
import reactor.core.scheduler.Scheduler;

import java.util.Map;
import java.util.Set;

@Discoverable
public interface LifetimeAnalysisStrategies {

    int priority();

    LifetimeAnalysisResult analyze(Module module,
                                   Set<AllocationSite> sites,
                                   AliasAnalysisResult aliasResult,
                                   EscapeAnalysisResult escapeResult,
                                   LivenessAnalyzer liveness,
                                   Map<Function, DominatorTree> domTrees,
                                   Map<Function, Map<BasicBlock, Set<BasicBlock>>> loops,
                                   Scheduler scheduler);
}
