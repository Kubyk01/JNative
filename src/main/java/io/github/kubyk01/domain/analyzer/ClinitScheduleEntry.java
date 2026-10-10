package io.github.kubyk01.domain.analyzer;

import io.github.kubyk01.domain.ir.Function;

/**
 * One entry of the initialization schedule computed by
 * {@link io.github.kubyk01.application.service.analyzer.Analyzer}.
 *
 * <p>The schedule is the single authoritative order in which the generated
 * {@code @main} runs every {@code <clinit>} and every VM bootstrap phase. It
 * replaces the previous pair of lists ({@code clinitFunctions} +
 * {@code bootstrapPhaseFunctions}) with a single interleaved sequence in which
 * the three phases ({@code System.initPhase1}, {@code System.initPhase2},
 * {@code System.initPhase3}) stand exactly where their dependencies require
 * them.</p>
 *
 * <p>The {@code function} field is the IR function of either a {@code <clinit>}
 * body or one of the bootstrap phases; the {@code bootstrapPhase} flag
 * distinguishes the two cases so the emitter can choose between an ordinary
 * {@code call} and a call that passes arguments, which a phase requires.</p>
 */
public record ClinitScheduleEntry(Function function, boolean bootstrapPhase) {
}