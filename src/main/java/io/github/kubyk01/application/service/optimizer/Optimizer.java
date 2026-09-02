package io.github.kubyk01.application.service.optimizer;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Value;
import io.github.kubyk01.domain.analyzer.lifetime.LifetimeAnalysisResult;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class Optimizer {

    private final Module module;
    private final AliasAnalysisResult aliasResult;
    private final EscapeAnalysisResult escapeResult;
    private final LifetimeAnalysisResult lifetimeResult;
    private final Map<AllocationSite, Value> siteToValue;

    // Optimization settings
    @Setter
    private boolean enableScalarReplacement = true;
    @Setter
    private boolean enableDestructorSimplification = true;
    @Setter
    private boolean enableDestructorInlining = true;
    @Setter
    private boolean enableDeadDestructorElimination = true;

    public Optimizer(Module module, AliasAnalysisResult aliasResult,
                     EscapeAnalysisResult escapeResult,
                     LifetimeAnalysisResult lifetimeResult,
                     Map<AllocationSite, Value> siteToValue) {
        this.module = module;
        this.aliasResult = aliasResult;
        this.escapeResult = escapeResult;
        this.lifetimeResult = lifetimeResult;
        this.siteToValue = siteToValue;
    }

    /**
     * Runs all enabled optimizations.
     */
    public void optimize() {
        if (enableScalarReplacement) {
            log.info("Running scalar replacement...");
            ScalarReplacer scalarReplacer = new ScalarReplacer(module, aliasResult, escapeResult, siteToValue);
            scalarReplacer.replace();
        }

        if (enableDestructorSimplification || enableDestructorInlining || enableDeadDestructorElimination) {
            DestructorSimplifier simplifier = new DestructorSimplifier(
                    module, aliasResult, lifetimeResult, siteToValue,
                    enableDestructorSimplification, enableDestructorInlining, enableDeadDestructorElimination
            );
            simplifier.simplify();
        }

        log.info("Optimization completed.");
    }
}
