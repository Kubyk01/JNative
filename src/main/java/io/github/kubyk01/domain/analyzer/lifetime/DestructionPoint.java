package io.github.kubyk01.domain.analyzer.lifetime;

import io.github.kubyk01.domain.analyzer.ir.BasicBlock;
import io.github.kubyk01.domain.analyzer.ir.Instruction;
import io.github.kubyk01.domain.analyzer.ir.Value;

/**
 * Destructor insertion point.
 * If beforeInstruction == null, the destructor is inserted at the end of the block (before the terminator).
 * objectRef is the IR value of the object to destroy.
 */
@lombok.Value
public class DestructionPoint {
    BasicBlock block;
    Instruction beforeInstruction;
    Value objectRef;
}
