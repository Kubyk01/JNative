package io.github.kubyk01.application.service.codegen.llvm;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.application.service.analyzer.ssa.GraphUtils;
import io.github.kubyk01.application.service.analyzer.ssa.TypeResolver;
import io.github.kubyk01.application.service.codegen.llvm.nativepolymorphicfunctionresolver.PolymorphicResolver;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.BranchTerminator;
import io.github.kubyk01.domain.ir.CondBranchTerminator;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.IndirectBranchTerminator;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.InvokeDynamicInfo;
import io.github.kubyk01.domain.ir.LookupSwitchTerminator;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.ResolvedCall;
import io.github.kubyk01.domain.ir.ReturnTerminator;
import io.github.kubyk01.domain.ir.TableSwitchTerminator;
import io.github.kubyk01.domain.ir.Temporary;
import io.github.kubyk01.domain.ir.Terminator;
import io.github.kubyk01.domain.ir.ThrowTerminator;
import io.github.kubyk01.domain.ir.TryCatchRange;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.Value;
import io.github.kubyk01.util.parserC.ParserC;
import io.github.kubyk01.util.parserC.ParserC.NativeMethodInfo;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static io.github.kubyk01.util.LlvmUtil.extractCalleeName;
import static io.github.kubyk01.util.LlvmUtil.extractClassName;
import static io.github.kubyk01.util.LlvmUtil.extractFieldName;
import static io.github.kubyk01.util.LlvmUtil.extractTypeName;
import static io.github.kubyk01.util.LlvmUtil.getCallArguments;
import static io.github.kubyk01.util.LlvmUtil.getElementSizeOfType;

@RequiredArgsConstructor
public class LlvmFunctionEmitter {

    private static final String LAZY_CLINIT_WRAPPER_PREFIX = "fn___lazy_clinit_run_";
    private static final String CLINIT_SUFFIX = "__clinit____V";

    private static final Set<String> DECLARED_RUNTIME_HELPERS =
            parseDeclaredRuntimeHelpers();

    private static Set<String> parseDeclaredRuntimeHelpers() {
        Set<String> names = new HashSet<>();
        for (String line : LlvmRuntime.getDeclarations().split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("declare ")) continue;
            int at = trimmed.indexOf('@');
            if (at < 0) continue;
            int paren = trimmed.indexOf('(', at);
            if (paren < 0) continue;
            names.add(trimmed.substring(at + 1, paren));
        }
        return names;
    }

    @FunctionalInterface
    private interface CallEmitter {
        void emit(StringBuilder sb, String resultReg);
    }

    private static final int JMP_BUF_SIZE = 256;

    private final Module module;
    private final LlvmGlobalEmitter globalEmitter;
    private final PolymorphicResolver polymorphicResolver;
    private final DependencyResolver resolver;

    private final LlvmValueMapper valueMapper = new LlvmValueMapper();
    private final Map<Value, String> valueSlots = new HashMap<>();
    private final Map<Value, StackAlloc> valuePointers = new HashMap<>();

    private record StackAlloc(String allocaName, String structType) {}

    private final Map<Instruction, Map<Integer, String>> phiPreloads = new HashMap<>();
    private final List<String> pendingSlotDeclarations = new ArrayList<>();
    private final Set<Value> tryCrossingValues = new HashSet<>();

    private int tmpCounter = 0;
    private int labelCounter = 0;
    private BasicBlock currentEntryBlock;
    private String currentFunctionName;

    private final Map<BasicBlock, List<TryCatchRange>> blockToTryRanges = new HashMap<>();
    private final Map<TryCatchRange, BasicBlock> handlerBlockByRange = new HashMap<>();
    private final Map<TryCatchRange, Integer> rangeOrdinals = new HashMap<>();
    private final Map<BasicBlock, Boolean> tryRegionEntry = new HashMap<>();
    private final Map<BasicBlock, Boolean> tryRegionExit = new HashMap<>();
    private final Map<RangeKey, String> catchLabelByRangeSet = new LinkedHashMap<>();
    private final Map<RangeKey, StringBuilder> catchBodyByRangeSet = new LinkedHashMap<>();

    private static final class RangeKey {
        final List<TryCatchRange> ranges;
        RangeKey(List<TryCatchRange> ranges) { this.ranges = ranges; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof RangeKey k)) return false;
            if (ranges.size() != k.ranges.size()) return false;
            for (int i = 0; i < ranges.size(); i++) {
                if (ranges.get(i) != k.ranges.get(i)) return false;
            }
            return true;
        }
        @Override public int hashCode() {
            int h = 1;
            for (TryCatchRange r : ranges) h = 31 * h + System.identityHashCode(r);
            return h;
        }
    }

    private final Map<BasicBlock, List<TryCatchRange>> trampolinePops = new HashMap<>();
    private final Map<BasicBlock, Map<BasicBlock, BasicBlock>> blockExitRedirects = new HashMap<>();

    public String emitFunction(Function func) {
        String funcName = func.getName();

        if ("fn_java_security_Provider_checkInitialized___V".equals(funcName)) {
            return "define void @" + funcName + "(i8* %param_0) {\n"
                    + "entry:\n"
                    + "  ret void\n"
                    + "}\n\n";
        }

        if ("fn_jdk_internal_misc_UnsafeConstants__clinit____V".equals(funcName)) {
            return emitUnsafeConstantsClinit();
        }

        this.currentFunctionName = func.getName();

        valueMapper.clear();
        valueSlots.clear();
        valuePointers.clear();
        phiPreloads.clear();
        pendingSlotDeclarations.clear();
        tryCrossingValues.clear();
        tryRegionEntry.clear();
        tryRegionExit.clear();
        catchLabelByRangeSet.clear();
        catchBodyByRangeSet.clear();
        trampolinePops.clear();
        blockExitRedirects.clear();

        StringBuilder signature = new StringBuilder();
        signature.append("define ")
                .append(LlvmTypeMapper.toLlvmType(func.getReturnType()))
                .append(" @").append(funcName).append("(");
        List<Parameter> params = func.getParameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) signature.append(", ");
            signature.append(LlvmTypeMapper.toLlvmType(params.get(i).getType()))
                    .append(" %param_").append(i);
        }
        signature.append(")");
        if (funcName.startsWith(LAZY_CLINIT_WRAPPER_PREFIX)
                || funcName.endsWith(CLINIT_SUFFIX)) {
            signature.append(" noinline");
        }
        signature.append(" {\n");

        for (int i = 0; i < params.size(); i++) {
            valueMapper.setValue(params.get(i), "%param_" + i);
        }

        currentEntryBlock = func.getEntryBlock();
        buildTryCatchInfo(func);
        computeTryRegions(func);
        tryCrossingValues.addAll(computeTryCrossingValues(func));

        List<String> bodies = new ArrayList<>();
        int entryIdx = 0;
        boolean firstBlock = true;
        for (int i = 0; i < func.getBlocks().size(); i++) {
            BasicBlock block = func.getBlocks().get(i);
            boolean isEntry = (block == currentEntryBlock)
                    || (currentEntryBlock == null && firstBlock);
            if (isEntry && (currentEntryBlock != null)) {
                entryIdx = i;
            } else if (isEntry) {
                entryIdx = i;
            }
            bodies.add(emitBlock(block, isEntry));
            firstBlock = false;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(signature);
        sb.append("entry:\n");

        Set<Integer> usedLocals = collectUsedLocals(func);
        for (int idx : usedLocals) {
            sb.append("  %local_").append(idx).append(" = alloca i64, align 8\n");
            sb.append("  store i64 0, i64* %local_").append(idx).append("\n");
        }

        for (int i = 0; i < params.size(); i++) {
            Parameter p = params.get(i);
            int slotIdx = p.getIndex();
            if (!usedLocals.contains(slotIdx)) continue;
            String ty  = LlvmTypeMapper.toLlvmType(p.getType());
            String ptr = newAux("param_init");
            sb.append("  ").append(ptr)
                    .append(" = bitcast i64* %local_").append(slotIdx)
                    .append(" to ").append(ty).append("*\n");
            sb.append("  store ").append(ty).append(" %param_").append(i)
                    .append(", ").append(ty).append("* ").append(ptr).append("\n");
        }

        List<TryCatchRange> ranges = func.getTryCatchRanges();
        if (ranges != null) {
            for (int i = 0; i < ranges.size(); i++) {
                sb.append("  %jmp_buf_").append(i).append(" = alloca [")
                        .append(JMP_BUF_SIZE).append(" x i8], align 16\n");
            }
        }

        for (String decl : pendingSlotDeclarations) {
            sb.append(decl);
        }

        sb.append(bodies.get(entryIdx));
        for (int i = 0; i < bodies.size(); i++) {
            if (i != entryIdx) {
                sb.append(bodies.get(i));
            }
        }

        for (StringBuilder cb : catchBodyByRangeSet.values()) {
            sb.append(cb);
        }

        sb.append("}\n\n");
        return sb.toString();
    }

    private void buildTryCatchInfo(Function func) {
        blockToTryRanges.clear();
        handlerBlockByRange.clear();
        rangeOrdinals.clear();

        List<TryCatchRange> ranges = func.getTryCatchRanges();
        if (ranges == null || ranges.isEmpty()) return;

        Map<String, BasicBlock> byLabel = new HashMap<>();
        for (BasicBlock b : func.getBlocks()) {
            byLabel.put(b.getLabel(), b);
        }

        int ordinal = 0;
        for (TryCatchRange range : ranges) {
            BasicBlock handlerBlock = byLabel.get("L" + range.handler);
            if (handlerBlock == null) continue;

            List<BasicBlock> body = range.body;

            if (body == null) {
                BasicBlock startBlock = byLabel.get("L" + range.start);
                BasicBlock endBlock   = byLabel.get("L" + range.end);
                if (startBlock == null || endBlock == null) continue;
                body = GraphUtils.getBlocksBetween(startBlock, endBlock, handlerBlock);
            }

            for (BasicBlock b : body) {
                if (b == handlerBlock) continue;
                blockToTryRanges.computeIfAbsent(b, x -> new ArrayList<>()).add(range);
            }

            handlerBlockByRange.put(range, handlerBlock);
            rangeOrdinals.put(range, ordinal++);
        }
    }

    private List<TryCatchRange> effectiveRangesFor(BasicBlock block) {
        List<TryCatchRange> all = blockToTryRanges.get(block);
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        List<TryCatchRange> filtered = new ArrayList<>(all.size());
        for (TryCatchRange r : all) {
            BasicBlock h = handlerBlockByRange.get(r);
            if (h == block) continue;
            filtered.add(r);
        }
        return filtered;
    }

    private static boolean sameRanges(List<TryCatchRange> a, List<TryCatchRange> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) return false;
        }
        return true;
    }

    private void computeTryRegions(Function func) {
        for (BasicBlock block : func.getBlocks()) {
            List<TryCatchRange> myRanges = effectiveRangesFor(block);
            if (myRanges.isEmpty()) {
                tryRegionEntry.put(block, false);
                tryRegionExit.put(block, false);
                continue;
            }
            boolean isEntry = false;
            for (BasicBlock pred : block.getPredecessors()) {
                if (!sameRanges(effectiveRangesFor(pred), myRanges)) {
                    isEntry = true;
                    break;
                }
            }
            boolean isExit = block.getTerminator() instanceof ReturnTerminator;
            if (!isExit) {
                for (BasicBlock succ : block.getSuccessors()) {
                    if (!sameRanges(effectiveRangesFor(succ), myRanges)) {
                        isExit = true;
                        break;
                    }
                }
            }
            tryRegionEntry.put(block, isEntry);
            tryRegionExit.put(block, isExit);
        }

        List<BasicBlock> snapshot = new ArrayList<>(func.getBlocks());
        for (BasicBlock block : snapshot) {
            List<TryCatchRange> myRanges = effectiveRangesFor(block);
            if (myRanges.isEmpty()) continue;
            if (!tryRegionExit.getOrDefault(block, false)) continue;

            Terminator term = block.getTerminator();
            if (term == null || term instanceof ReturnTerminator) continue;

            boolean hasInsideNormalSucc  = false;
            boolean hasOutsideNormalSucc = false;
            for (BasicBlock succ : term.getTargets()) {
                if (sameRanges(effectiveRangesFor(succ), myRanges)) hasInsideNormalSucc  = true;
                else                                                hasOutsideNormalSucc = true;
            }
            if (!hasInsideNormalSucc || !hasOutsideNormalSucc) continue;

            Map<BasicBlock, BasicBlock> redirects = new HashMap<>();
            for (BasicBlock succ : term.getTargets()) {
                if (sameRanges(effectiveRangesFor(succ), myRanges)) continue;

                BasicBlock tramp = new BasicBlock(
                        block.getLabel() + "_exit_tramp_" + (trampolinePops.size()
                                + blockExitRedirects.size() + 1));
                trampolinePops.put(tramp, myRanges);
                func.addBlock(tramp);

                List<BasicBlock> preds = succ.getPredecessors();
                int blockIdx = preds.indexOf(block);
                if (blockIdx >= 0) {
                    preds.set(blockIdx, tramp);
                }

                tramp.setTerminator(new BranchTerminator(succ));

                redirects.put(succ, tramp);
            }
            if (!redirects.isEmpty()) {
                blockExitRedirects.put(block, redirects);
            }
        }
    }

    private Set<Value> computeTryCrossingValues(Function func) {
        Set<Value> result = new HashSet<>();
        List<TryCatchRange> ranges = func.getTryCatchRanges();
        if (ranges == null || ranges.isEmpty()) return result;

        Map<String, BasicBlock> byLabel = new HashMap<>();
        for (BasicBlock b : func.getBlocks()) {
            byLabel.put(b.getLabel(), b);
        }

        BasicBlock entry = func.getEntryBlock();

        for (TryCatchRange range : ranges) {
            BasicBlock handler = byLabel.get("L" + range.handler);
            if (handler == null) continue;

            Set<BasicBlock> bodySet = new HashSet<>();
            for (Map.Entry<BasicBlock, List<TryCatchRange>> e : blockToTryRanges.entrySet()) {
                if (e.getValue().contains(range)) bodySet.add(e.getKey());
            }
            if (bodySet.isEmpty()) continue;

            Set<BasicBlock> catchReachable = new HashSet<>();
            Deque<BasicBlock> worklist = new ArrayDeque<>();
            worklist.add(handler);
            while (!worklist.isEmpty()) {
                BasicBlock cur = worklist.poll();
                if (!catchReachable.add(cur)) continue;
                for (BasicBlock succ : cur.getSuccessors()) {
                    if (!catchReachable.contains(succ)) worklist.add(succ);
                }
            }

            for (BasicBlock B : catchReachable) {
                for (Instruction inst : B.getInstructions()) {
                    for (Value op : inst.getOperands()) {
                        addIfCrossing(result, op, B, bodySet, entry);
                    }
                }
                Terminator term = B.getTerminator();
                if (term != null) {
                    for (Value op : terminatorOperands(term)) {
                        addIfCrossing(result, op, B, bodySet, entry);
                    }
                }
            }
        }
        return result;
    }

    private static void addIfCrossing(Set<Value> result,
                                      Value op,
                                      BasicBlock useBlock,
                                      Set<BasicBlock> bodyBlocks,
                                      BasicBlock entry) {
        if (!(op instanceof Temporary t)) return;
        Instruction def = t.getDefiningInstruction();
        if (def == null) return;
        BasicBlock defBlock = def.getParent();
        if (defBlock == null) return;
        if (defBlock == useBlock) return;
        if (defBlock == entry) return;
        if (!bodyBlocks.contains(defBlock)) return;
        result.add(op);
    }

    private static List<Value> terminatorOperands(Terminator term) {
        List<Value> out = new ArrayList<>();
        if (term instanceof ReturnTerminator rt) {
            if (rt.getValue() != null) out.add(rt.getValue());
        } else if (term instanceof ThrowTerminator tt) {
            if (tt.getException() != null) out.add(tt.getException());
        } else if (term instanceof CondBranchTerminator cbt) {
            if (cbt.getCondition() != null) out.add(cbt.getCondition());
        } else if (term instanceof LookupSwitchTerminator lst) {
            if (lst.getKey() != null) out.add(lst.getKey());
        } else if (term instanceof TableSwitchTerminator tst) {
            if (tst.getKey() != null) out.add(tst.getKey());
        } else if (term instanceof IndirectBranchTerminator ibt) {
            if (ibt.getTargetBlock() != null) out.add(ibt.getTargetBlock());
        }
        return out;
    }

    private Set<Integer> collectUsedLocals(Function func) {
        Set<Integer> locals = new HashSet<>();
        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (inst.getOpcode() == Opcode.LOAD || inst.getOpcode() == Opcode.STORE) {
                    locals.add(inst.getLocalIndex());
                }
            }
        }
        return locals;
    }

    private String emitBlock(BasicBlock block, boolean labelAlreadyEmitted) {
        StringBuilder sb = new StringBuilder();
        if (!labelAlreadyEmitted) {
            sb.append(llvmLabel(block)).append(":\n");
        }

        List<TryCatchRange> trampolineRanges = trampolinePops.get(block);
        if (trampolineRanges != null) {
            sb.repeat("  call void @__jnative_pop_catch()\n", trampolineRanges.size());
            Terminator trampTerm = block.getTerminator();
            if (trampTerm != null) {
                sb.append(emitTerminator(trampTerm, List.of(), Collections.emptyMap()));
            } else {
                sb.append("  unreachable\n");
            }
            return sb.toString();
        }

        List<TryCatchRange> ranges = effectiveRangesFor(block);
        boolean isEntry = tryRegionEntry.getOrDefault(block, false);
        boolean isExit  = tryRegionExit.getOrDefault(block, false);
        Terminator term = block.getTerminator();

        String bodyLabel;
        if (isEntry && !ranges.isEmpty()) {
            bodyLabel = newLabel("body");
            RangeKey key = new RangeKey(ranges);
            String catchLabel = catchLabelByRangeSet.get(key);
            if (catchLabel == null) {
                catchLabel = newLabel("catch_dispatch");
                catchLabelByRangeSet.put(key, catchLabel);
                StringBuilder cb = new StringBuilder();
                emitCatchDispatchBody(cb, catchLabel, ranges);
                catchBodyByRangeSet.put(key, cb);
            }
            emitRegionArm(sb, ranges, catchLabel, bodyLabel);
            sb.append(bodyLabel).append(":\n");
        }

        if (term != null) {
            for (BasicBlock succ : term.getTargets()) {
                int predIdx = succ.getPredecessors().indexOf(block);
                if (predIdx < 0) continue;
                for (Instruction succInst : succ.getInstructions()) {
                    if (succInst.getOpcode() != Opcode.PHI) continue;
                    if (predIdx >= succInst.getOperands().size()) continue;
                    Value phiOp = succInst.getOperands().get(predIdx);
                    if (phiOp == null) continue;

                    String slot = valueSlots.get(phiOp);
                    if (slot != null) {
                        String ty = LlvmTypeMapper.toLlvmType(phiOp.getType());
                        String tmp = newAux("phi_preload");
                        sb.append("  ").append(tmp).append(" = load ")
                                .append(ty).append(", ").append(ty).append("* ")
                                .append(slot).append("\n");
                        phiPreloads.computeIfAbsent(succInst, k -> new HashMap<>())
                                .put(predIdx, tmp);
                        continue;
                    }

                    StackAlloc ptrSlot = valuePointers.get(phiOp);
                    if (ptrSlot != null) {
                        String destTy = LlvmTypeMapper.toLlvmType(phiOp.getType());
                        String tmp = newAux("phi_preload");
                        sb.append("  ").append(tmp).append(" = bitcast ")
                                .append(ptrSlot.structType()).append("* ")
                                .append(ptrSlot.allocaName())
                                .append(" to ").append(destTy).append("\n");
                        phiPreloads.computeIfAbsent(succInst, k -> new HashMap<>())
                                .put(predIdx, tmp);
                    }
                }
            }
        }

        for (Instruction inst : block.getInstructions()) {
            sb.append(emitInstruction(inst, List.of()));
        }

        Map<BasicBlock, String> targetRedirects = Collections.emptyMap();
        if (isExit && !ranges.isEmpty()) {
            boolean isReturn = (term == null) || (term instanceof ReturnTerminator);

            boolean hasInsideNormalSucc  = false;
            boolean hasOutsideNormalSucc = false;
            if (!isReturn) {
                for (BasicBlock succ : term.getTargets()) {
                    if (sameRanges(effectiveRangesFor(succ), ranges)) hasInsideNormalSucc  = true;
                    else                                                hasOutsideNormalSucc = true;
                }
            }

            if (isReturn || (hasOutsideNormalSucc && !hasInsideNormalSucc)) {
                sb.repeat("  call void @__jnative_pop_catch()\n", ranges.size());
            } else if (hasOutsideNormalSucc && hasInsideNormalSucc) {
                Map<BasicBlock, BasicBlock> redirects = blockExitRedirects.get(block);
                if (redirects != null && !redirects.isEmpty()) {
                    targetRedirects = new HashMap<>();
                    for (Map.Entry<BasicBlock, BasicBlock> e : redirects.entrySet()) {
                        targetRedirects.put(e.getKey(), llvmLabel(e.getValue()));
                    }
                }
            }
        }

        if (term != null) {
            sb.append(emitTerminator(term, List.of(), targetRedirects));
        } else {
            Type retType = block.getFunction().getReturnType();
            if (retType.isVoid()) {
                sb.append("  ret void\n");
            } else {
                String zero = getZeroValue(retType);
                sb.append("  ret ").append(LlvmTypeMapper.toLlvmType(retType))
                        .append(" ").append(zero).append("\n");
            }
        }
        return sb.toString();
    }

    private String getZeroValue(Type type) {
        if (type == null) return "null";
        if (type == Type.FLOAT || type == Type.DOUBLE) return "0.0";
        if (type.isReference() || type.isArray() || type.isNull()
                || type.isBlock() || type.isUnknown()) {
            return "null";
        }
        if (type == Type.BOOLEAN) return "false";
        return "0";
    }

    private String emitUnsafeConstantsClinit() {
        return """
                define void @fn_jdk_internal_misc_UnsafeConstants__clinit____V() {
                entry:
                  %addr_size = call i32 @__jnative_unsafe_address_size()
                  store i32 %addr_size, i32* \
                @gv_jdk_internal_misc_UnsafeConstants_ADDRESS_SIZE0, align 4
                  %page_size = call i32 @__jnative_unsafe_page_size()
                  store i32 %page_size, i32* \
                @gv_jdk_internal_misc_UnsafeConstants_PAGE_SIZE, align 4
                  %be_i32 = call i32 @__jnative_unsafe_big_endian()
                  %be = icmp ne i32 %be_i32, 0
                  store i1 %be, i1* \
                @gv_jdk_internal_misc_UnsafeConstants_BIG_ENDIAN, align 1
                  %ua_i32 = call i32 @__jnative_unsafe_unaligned_access()
                  %ua = icmp ne i32 %ua_i32, 0
                  store i1 %ua, i1* \
                @gv_jdk_internal_misc_UnsafeConstants_UNALIGNED_ACCESS, align 1
                  %flush = call i32 @__jnative_unsafe_data_cache_line_flush_size()
                  store i32 %flush, i32* \
                @gv_jdk_internal_misc_UnsafeConstants_DATA_CACHE_LINE_FLUSH_SIZE, align 4
                  ret void
                }
                
                """;
    }

    private String llvmLabel(BasicBlock block) {
        if (block == currentEntryBlock) {
            return "entry";
        }
        return block.getLabel().replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private String newAux(String prefix) {
        return "%aux_" + prefix + "_" + (tmpCounter++);
    }

    private String newLabel(String prefix) {
        return "lbl_" + prefix + "_" + (labelCounter++);
    }

    private String stringRef(String s) {
        if (s == null || s.isEmpty()) {
            return "null";
        }
        globalEmitter.registerDeferredString(s);
        int len = LlvmRuntime.typeStringArrayLength(s);
        String g = LlvmRuntime.typeStringGlobalName(s);
        return "getelementptr inbounds ([" + len + " x i8], [" + len + " x i8]* "
                + g + ", i32 0, i32 0)";
    }

    private void emitThrowHelper(StringBuilder sb, String callee, List<TryCatchRange> ranges) {
        String ctxCallee = callee + "_ctx";
        String funcNameRef = stringRef(currentFunctionName);

        if (ranges.isEmpty()) {
            sb.append("  call void ").append(ctxCallee)
                    .append("(i8* ").append(funcNameRef).append(")\n");
            sb.append("  unreachable\n");
        } else {
            emitTryGuard(sb, ranges,
                    inner -> inner.append("  call void ").append(ctxCallee)
                            .append("(i8* ").append(funcNameRef).append(")\n"),
                    true);
        }
    }

    private void emitNpeThrowHelper(StringBuilder sb,
                                    String context,
                                    List<TryCatchRange> ranges) {
        String ctxCallee   = "@__jnative_throw_null_pointer_exception_ctx";
        String funcNameRef = stringRef(currentFunctionName);

        String extraRef;
        if (context != null && !context.isEmpty()) {
            globalEmitter.registerDeferredString(context);
            extraRef = stringRef(context);
        } else {
            extraRef = "null";
        }

        if (ranges.isEmpty()) {
            sb.append("  call void ").append(ctxCallee)
                    .append("(i8* ").append(funcNameRef)
                    .append(", i8* ").append(extraRef).append(")\n");
            sb.append("  unreachable\n");
        } else {
            emitTryGuard(sb, ranges,
                    inner -> inner.append("  call void ").append(ctxCallee)
                            .append("(i8* ").append(funcNameRef)
                            .append(", i8* ").append(extraRef).append(")\n"),
                    true);
        }
    }

    private void emitNullCheck(StringBuilder sb, Value obj, List<TryCatchRange> ranges,
                               String context) {
        if (!(obj.getType().isReference()
                || obj.getType().isArray()
                || obj.getType().isNull()
                || obj.getType().isBlock()
                || obj.getType().isUnknown())) {
            return;
        }
        String ty = LlvmTypeMapper.toLlvmType(obj.getType());

        String objRef = getLlvmValue(sb, obj);

        String chk      = newAux("npe_chk");
        String throwBlk = newLabel("throw_npe");
        String cont     = newLabel("npe_ok");

        sb.append("  ").append(chk).append(" = icmp ne ").append(ty).append(" ")
                .append(objRef).append(", null\n");
        sb.append("  br i1 ").append(chk)
                .append(", label %").append(cont)
                .append(", label %").append(throwBlk).append("\n");
        sb.append(throwBlk).append(":\n");

        emitNpeThrowHelper(sb, context, ranges);

        sb.append(cont).append(":\n");
    }

    private int getBaseElementSize(String desc) {
        String base = desc;
        while (base.startsWith("[")) {
            base = base.substring(1);
        }
        if (base.length() == 1) {
            return switch (base.charAt(0)) {
                case 'Z', 'B' -> 1;
                case 'S', 'C' -> 2;
                case 'I', 'F' -> 4;
                case 'J', 'D' -> 8;
                default -> 8;
            };
        }
        return 8;
    }

    private Type elemTypeFromConst(String s) {
        return switch (s) {
            case "boolean" -> Type.BOOLEAN;
            case "byte" -> Type.BYTE;
            case "short" -> Type.SHORT;
            case "char" -> Type.CHAR;
            case "int" -> Type.INT;
            case "long" -> Type.LONG;
            case "float" -> Type.FLOAT;
            case "double" -> Type.DOUBLE;
            default -> Type.fromDescriptor(s);
        };
    }

    private static String arrayClassDescriptorFor(String elemTypeName) {
        if (elemTypeName == null) return null;
        switch (elemTypeName) {
            case "boolean": return "[Z";
            case "byte":    return "[B";
            case "short":   return "[S";
            case "char":    return "[C";
            case "int":     return "[I";
            case "long":    return "[J";
            case "float":   return "[F";
            case "double":  return "[D";
        }
        if (elemTypeName.startsWith("[")) return "[" + elemTypeName;
        return "[L" + elemTypeName + ";";
    }

    private String emitInstruction(Instruction inst, List<TryCatchRange> ranges) {
        StringBuilder sb = new StringBuilder();
        Opcode op = inst.getOpcode();
        String resultName = null;
        if (inst.getResult() != null) {
            resultName = "%tmp_" + (tmpCounter++);
            valueMapper.setValue(inst.getResult(), resultName);
        }

        switch (op) {
            case LOAD: {
                int idx = inst.getLocalIndex();
                Type loadType = inst.getResult().getType();

                String llvmType = LlvmTypeMapper.toLlvmType(loadType);
                String ptr = newAux("ptrcast");
                sb.append("  ").append(ptr).append(" = bitcast i64* %local_").append(idx)
                        .append(" to ").append(llvmType).append("*\n");
                sb.append("  ").append(resultName).append(" = load ").append(llvmType)
                        .append(", ").append(llvmType).append("* ").append(ptr).append("\n");
                break;
            }

            case STORE: {
                Value stored = inst.getOperands().getFirst();
                int idx = inst.getLocalIndex();

                Type storedType = stored.getType();
                Type localType = (inst.getResult() != null)
                        ? inst.getResult().getType()
                        : storedType;
                if (localType.isUnknown()) {
                    localType = storedType;
                }

                String valRef = getLlvmValue(sb, stored);
                String valConverted = castValueToType(sb, valRef, storedType, localType);

                String llvmType = LlvmTypeMapper.toLlvmType(localType);
                String ptr = newAux("ptrcast");
                sb.append("  ").append(ptr).append(" = bitcast i64* %local_").append(idx)
                        .append(" to ").append(llvmType).append("*\n");
                sb.append("  store ").append(llvmType).append(" ").append(valConverted)
                        .append(", ").append(llvmType).append("* ").append(ptr).append("\n");

                if (inst.getResult() != null) {
                    if (inst.getResult() instanceof Temporary resultTmp) {
                        resultTmp.setType(localType);
                    }
                    valueMapper.setValue(inst.getResult(), valConverted);

                    String storedSlot = valueSlots.get(stored);
                    if (storedSlot != null) {
                        valueSlots.put(inst.getResult(), storedSlot);
                    }
                }
                break;
            }

            case ADD: case SUB: case MUL: case DIV: case REM:
            case AND: case OR: case XOR: case SHL: case SHR: case USHR: {
                Value left  = inst.getOperands().get(0);
                Value right = inst.getOperands().get(1);
                Type resType = inst.getResult().getType();

                if ((op == Opcode.DIV || op == Opcode.REM) && !ranges.isEmpty()) {
                    Type lt = left.getType();
                    if (lt == Type.INT || lt == Type.LONG) {
                        emitDivByZeroCheck(sb, right, ranges);
                    }
                }

                String  leftLlvm   = LlvmTypeMapper.toLlvmType(left.getType());
                String  rightLlvm  = LlvmTypeMapper.toLlvmType(right.getType());
                String  resLlvm    = LlvmTypeMapper.toLlvmType(resType);
                boolean leftIsPtr  = leftLlvm.endsWith("*");
                boolean rightIsPtr = rightLlvm.endsWith("*");
                boolean resIsPtr   = resLlvm.endsWith("*");

                boolean isBitwise = (op == Opcode.AND || op == Opcode.OR || op == Opcode.XOR
                        || op == Opcode.SHL || op == Opcode.SHR || op == Opcode.USHR);
                boolean anyPtr    = leftIsPtr || rightIsPtr || resIsPtr;

                if (isBitwise && (leftIsPtr || rightIsPtr)) {
                    Type intType = Type.LONG;
                    String lInt = castValueToType(sb, getLlvmValue(sb, left),  left.getType(),  intType);
                    String rInt = castValueToType(sb, getLlvmValue(sb, right), right.getType(), intType);
                    String llvmOp = mapArithOp(op, Type.LONG);
                    String tmp = newAux("bitwise_int");
                    sb.append("  ").append(tmp).append(" = ").append(llvmOp)
                            .append(" ").append(LlvmTypeMapper.toLlvmType(intType))
                            .append(" ").append(lInt).append(", ").append(rInt).append("\n");
                    String finalVal = castValueToType(sb, tmp, intType, resType);
                    if (resultName != null) {
                        valueMapper.setValue(inst.getResult(), finalVal);
                    }
                    break;
                }

                if (!isBitwise && anyPtr) {
                    String lInt = castValueToType(sb, getLlvmValue(sb, left),  left.getType(),  Type.LONG);
                    String rInt = castValueToType(sb, getLlvmValue(sb, right), right.getType(), Type.LONG);
                    String llvmOp = mapArithOp(op, Type.LONG);
                    String tmp = newAux("ptr_arith");
                    sb.append("  ").append(tmp).append(" = ").append(llvmOp)
                            .append(" i64 ").append(lInt).append(", ").append(rInt).append("\n");
                    String finalVal = castValueToType(sb, tmp, Type.LONG, resType);
                    if (resultName != null) {
                        valueMapper.setValue(inst.getResult(), finalVal);
                    }
                    break;
                }

                String l = castValueToType(sb, getLlvmValue(sb, left),  left.getType(),  resType);
                String r = castValueToType(sb, getLlvmValue(sb, right), right.getType(), resType);
                String llvmOp = mapArithOp(op, resType);
                sb.append("  ").append(resultName).append(" = ").append(llvmOp)
                        .append(" ").append(LlvmTypeMapper.toLlvmType(resType))
                        .append(" ").append(l).append(", ").append(r).append("\n");
                break;
            }

            case EQ: case NE: case LT: case LE: case GT: case GE: {
                Value left = inst.getOperands().get(0);
                Value right = inst.getOperands().get(1);
                Type leftType = left.getType();
                Type rightType = right.getType();
                String l = getLlvmValue(sb, left);
                String r = getLlvmValue(sb, right);

                boolean leftIsPtr = leftType.isReference() || leftType.isArray() || leftType.isNull() || leftType.isBlock();
                boolean rightIsPtr = rightType.isReference() || rightType.isArray() || rightType.isNull() || rightType.isBlock();

                if (leftIsPtr && "0".equals(r)) { r = "null"; }
                if (rightIsPtr && "0".equals(l)) { l = "null"; }

                if ((leftIsPtr && !rightIsPtr) || (!leftIsPtr && rightIsPtr)) {
                    l = castValueToType(sb, l, leftType, Type.LONG);
                    r = castValueToType(sb, r, rightType, Type.LONG);
                    leftType = Type.LONG;
                } else if (!leftIsPtr && !rightIsPtr) {
                    boolean leftIsFloat = leftType == Type.FLOAT || leftType == Type.DOUBLE;
                    boolean rightIsFloat = rightType == Type.FLOAT || rightType == Type.DOUBLE;
                    boolean leftIsInt = isIntegerType(leftType);
                    boolean rightIsInt = isIntegerType(rightType);

                    if ((leftIsFloat && rightIsInt) || (leftIsInt && rightIsFloat)) {
                        if (leftIsInt && rightIsFloat) {
                            l = castValueToType(sb, l, leftType, rightType);
                            leftType = rightType;
                        } else if (leftIsFloat && rightIsInt) {
                            r = castValueToType(sb, r, rightType, leftType);
                        }
                    } else if (leftIsInt && rightIsInt) {
                        l = castValueToType(sb, l, leftType, Type.LONG);
                        r = castValueToType(sb, r, rightType, Type.LONG);
                        leftType = Type.LONG;
                    } else if (!leftType.equals(rightType)) {
                        r = castValueToType(sb, r, rightType, leftType);
                    }
                }

                boolean isFloat = leftType == Type.FLOAT || leftType == Type.DOUBLE;
                String instr = isFloat ? "fcmp" : "icmp";
                String pred = mapCmpOp(op, isFloat);
                sb.append("  ").append(resultName).append(" = ").append(instr)
                        .append(" ").append(pred)
                        .append(" ").append(LlvmTypeMapper.toLlvmType(leftType))
                        .append(" ").append(l).append(", ").append(r).append("\n");
                break;
            }

            case CAST: {
                Value val = inst.getOperands().getFirst();
                Type srcType = val.getType();
                Type destType = inst.getResult().getType();
                String casted = castValueToType(sb, getLlvmValue(sb, val), srcType, destType);
                valueMapper.setValue(inst.getResult(), casted);
                break;
            }

            case GET_FIELD: {
                Value base = inst.getOperands().getFirst();
                Value fieldOperand = inst.getOperands().size() > 1 ? inst.getOperands().get(1) : null;

                if (fieldOperand instanceof Constant offc && offc.getType() == Type.INT) {
                    int offset = ((Number) offc.getValue()).intValue();

                    emitNullCheck(sb, base, ranges, "captured field at offset " + offset);

                    String baseI8 = emitBaseToI8Pointer(sb, base);

                    String gep = newAux("gep");
                    sb.append("  ").append(gep).append(" = getelementptr i8, i8* ")
                            .append(baseI8).append(", i32 ").append(offset).append("\n");

                    Type fieldType = inst.getResult().getType();
                    String fieldLlvm = LlvmTypeMapper.toLlvmType(fieldType);
                    String ptrCast = newAux("ptrcast");
                    sb.append("  ").append(ptrCast).append(" = bitcast i8* ").append(gep)
                            .append(" to ").append(fieldLlvm).append("*\n");
                    sb.append("  ").append(resultName).append(" = load ").append(fieldLlvm)
                            .append(", ").append(fieldLlvm).append("* ").append(ptrCast).append("\n");
                    break;
                }

                String fullFieldName = extractFieldName(inst);
                String owner;
                String fieldName;
                int lastDot = fullFieldName.lastIndexOf('.');
                if (lastDot > 0) {
                    owner = fullFieldName.substring(0, lastDot);
                    fieldName = fullFieldName.substring(lastDot + 1);
                } else {
                    owner = extractClassName(base);
                    fieldName = fullFieldName;
                }

                int offset = globalEmitter.getFieldOffset(owner, fieldName);

                emitNullCheck(sb, base, ranges,
                        "field '" + owner + "." + fieldName + "'");

                String baseI8 = emitBaseToI8Pointer(sb, base);

                String gep = newAux("gep");
                sb.append("  ").append(gep).append(" = getelementptr i8, i8* ").append(baseI8)
                        .append(", i32 ").append(offset).append("\n");

                Type fieldType = inst.getResult().getType();
                String fieldLlvm = LlvmTypeMapper.toLlvmType(fieldType);
                String ptrCast = newAux("ptrcast");
                sb.append("  ").append(ptrCast).append(" = bitcast i8* ").append(gep)
                        .append(" to ").append(fieldLlvm).append("*\n");
                sb.append("  ").append(resultName).append(" = load ").append(fieldLlvm)
                        .append(", ").append(fieldLlvm).append("* ").append(ptrCast).append("\n");
                break;
            }

            case PUT_FIELD: {
                if (inst.getOperands().size() < 3) {
                    break;
                }

                Value base = inst.getOperands().get(0);
                Value rhs  = inst.getOperands().get(2);
                Value fieldOperand = inst.getOperands().get(1);

                if (fieldOperand instanceof Constant offc && offc.getType() == Type.INT) {
                    int offset = ((Number) offc.getValue()).intValue();

                    emitNullCheck(sb, base, ranges, "captured field at offset " + offset);

                    String baseI8 = emitBaseToI8Pointer(sb, base);

                    String gep = newAux("gep");
                    sb.append("  ").append(gep).append(" = getelementptr i8, i8* ")
                            .append(baseI8).append(", i32 ").append(offset).append("\n");

                    Type fieldType = rhs.getType();
                    String fieldLlvm = LlvmTypeMapper.toLlvmType(fieldType);
                    String rhsRef = getLlvmValue(sb, rhs);
                    String rhsConverted = castValueToType(sb, rhsRef, rhs.getType(), fieldType);
                    String ptrCast = newAux("ptrcast");
                    sb.append("  ").append(ptrCast).append(" = bitcast i8* ").append(gep)
                            .append(" to ").append(fieldLlvm).append("*\n");
                    sb.append("  store ").append(fieldLlvm).append(" ").append(rhsConverted)
                            .append(", ").append(fieldLlvm).append("* ").append(ptrCast).append("\n");
                    break;
                }

                String fullFieldName = extractFieldName(inst);
                String owner;
                String fieldName;
                int lastDot = fullFieldName.lastIndexOf('.');
                if (lastDot > 0) {
                    owner = fullFieldName.substring(0, lastDot);
                    fieldName = fullFieldName.substring(lastDot + 1);
                } else {
                    owner = extractClassName(base);
                    fieldName = fullFieldName;
                }

                int offset = globalEmitter.getFieldOffset(owner, fieldName);

                emitNullCheck(sb, base, ranges,
                        "field '" + owner + "." + fieldName + "'");

                String baseI8 = emitBaseToI8Pointer(sb, base);

                String gep = newAux("gep");
                sb.append("  ").append(gep).append(" = getelementptr i8, i8* ").append(baseI8)
                        .append(", i32 ").append(offset).append("\n");

                Type fieldType = globalEmitter.getFieldType(owner, fieldName);
                if (fieldType == null) {
                    fieldType = rhs.getType();
                }
                String fieldLlvm = LlvmTypeMapper.toLlvmType(fieldType);
                String rhsRef = getLlvmValue(sb, rhs);
                String rhsConverted = castValueToType(sb, rhsRef, rhs.getType(), fieldType);
                String ptrCast = newAux("ptrcast");
                sb.append("  ").append(ptrCast).append(" = bitcast i8* ").append(gep)
                        .append(" to ").append(fieldLlvm).append("*\n");
                sb.append("  store ").append(fieldLlvm).append(" ").append(rhsConverted)
                        .append(", ").append(fieldLlvm).append("* ").append(ptrCast).append("\n");
                break;
            }

            case GET_STATIC: {
                String fieldName = extractFieldName(inst);
                String globalName = "gv_" + LlvmTypeMapper.sanitizeIdentifier(fieldName);

                String owner;
                String bareField;
                int lastDot = fieldName.lastIndexOf('.');
                if (lastDot > 0) {
                    owner = fieldName.substring(0, lastDot);
                    bareField = fieldName.substring(lastDot + 1);
                } else {
                    owner = "";
                    bareField = fieldName;
                }

                if (LlvmTypeMapper.LAZY_CLINIT_FLAG_FIELD.equals(bareField)) {
                    sb.append("  ").append(resultName)
                            .append(" = load atomic i32, i32* @").append(globalName)
                            .append(" acquire, align 4\n");
                    break;
                }

                if ("jnative_in_shutdown".equals(bareField)) {
                    sb.append("  ").append(resultName)
                            .append(" = load atomic i32, i32* @").append(globalName)
                            .append(" acquire, align 4\n");
                    break;
                }

                Type fieldType = globalEmitter.getFieldType(owner, bareField);
                if (fieldType == null) {
                    fieldType = inst.getResult().getType();
                }
                String llvmType = LlvmTypeMapper.toLlvmType(fieldType);
                sb.append("  ").append(resultName).append(" = load ")
                        .append(llvmType).append(", ")
                        .append(llvmType).append("* @").append(globalName).append("\n");
                break;
            }

            case PUT_STATIC: {
                if (inst.getOperands().size() >= 2) {
                    Value rhs = inst.getOperands().get(1);
                    String fieldName = extractFieldName(inst);
                    String globalName = "gv_" + LlvmTypeMapper.sanitizeIdentifier(fieldName);

                    String owner;
                    String bareField;
                    int lastDot = fieldName.lastIndexOf('.');
                    if (lastDot > 0) {
                        owner = fieldName.substring(0, lastDot);
                        bareField = fieldName.substring(lastDot + 1);
                    } else {
                        owner = "";
                        bareField = fieldName;
                    }

                    if (LlvmTypeMapper.LAZY_CLINIT_FLAG_FIELD.equals(bareField)) {
                        String rhsRef = getLlvmValue(sb, rhs);
                        String rhsConverted = castValueToType(sb, rhsRef, rhs.getType(), Type.INT);
                        sb.append("  store atomic i32 ").append(rhsConverted)
                                .append(", i32* @").append(globalName)
                                .append(" release, align 4\n");
                        break;
                    }

                    Type fieldType = globalEmitter.getFieldType(owner, bareField);
                    if (fieldType == null) {
                        fieldType = rhs.getType();
                    }

                    String rhsRef = getLlvmValue(sb, rhs);
                    String rhsConverted = castValueToType(sb, rhsRef, rhs.getType(), fieldType);
                    String llvmType = LlvmTypeMapper.toLlvmType(fieldType);

                    sb.append("  store ").append(llvmType).append(" ").append(rhsConverted)
                            .append(", ").append(llvmType).append("* @").append(globalName).append("\n");
                }
                break;
            }

            case VIRTUAL_CALL:
            case INTERFACE_CALL: {
                List<Value> operands = inst.getOperands();
                if (operands.size() < 2) break;

                Value receiver = operands.get(0);
                Value calleeConst = operands.get(1);
                if (!(calleeConst instanceof Constant)) break;

                String calleeName = ((Constant) calleeConst).getValue().toString();

                int dotIdx = calleeName.lastIndexOf('.');
                if (dotIdx < 0) break;
                String owner = calleeName.substring(0, dotIdx);
                String sig = calleeName.substring(dotIdx + 1);
                int parenIdx = sig.indexOf('(');
                if (parenIdx < 0) break;
                String methodName = sig.substring(0, parenIdx);
                String methodDesc = sig.substring(parenIdx);

                Type retType = inst.getResult() != null ? inst.getResult().getType() : Type.VOID;

                {
                    String[] foundOwner = new String[1];
                    io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode targetMethod =
                            resolver.findMethodInHierarchy(owner, methodName, methodDesc, foundOwner);
                    if (targetMethod != null && targetMethod.isStatic()) {
                        String actualOwner = foundOwner[0] != null ? foundOwner[0] : owner;
                        String directMangled =
                                LlvmRuntime.mangleMethod(actualOwner, methodName, methodDesc);

                        if (module.getFunction(directMangled) == null
                                || module.getFunction(directMangled).getEntryBlock() == null) {
                            ensureFunctionDeclared(actualOwner, methodName, methodDesc);
                        }

                        Function directFunc = module.getFunction(directMangled);
                        if (directFunc == null || directFunc.getEntryBlock() == null) {
                            String nativeCandidate =
                                    "__jnative_" + LlvmRuntime.mangleMethod(actualOwner, methodName, methodDesc);
                            Function nativeFunc = module.getFunction(nativeCandidate);
                            if (nativeFunc != null) {
                                directFunc = nativeFunc;
                                directMangled = nativeCandidate;
                            }
                        }

                        if (directFunc == null) {
                            throw new IllegalStateException(
                                    "VIRTUAL_CALL / INTERFACE_CALL to a static method '"
                                            + actualOwner + "." + methodName + methodDesc
                                            + "' has no IR definition and no native declaration.");
                        }

                        List<Value> args = new ArrayList<>(operands.size() - 1);
                        for (int i = 2; i < operands.size(); i++) {
                            args.add(operands.get(i));
                        }

                        final List<Value> argsForCall = args;
                        final Function calleeForCall = directFunc;
                        final String directMangledFinal = directMangled;
                        final Type retTypeForCall = retType;
                        emitCall(sb, retTypeForCall, inst.getResult(), resultName, ranges, (callSb, resultReg) -> {
                            StringBuilder argList = new StringBuilder();
                            for (int i = 0; i < argsForCall.size(); i++) {
                                if (i > 0) argList.append(", ");
                                Value arg = argsForCall.get(i);
                                Type paramType = (calleeForCall != null
                                        && i < calleeForCall.getParameters().size())
                                        ? calleeForCall.getParameters().get(i).getType()
                                        : arg.getType();
                                String val = castValueToType(callSb, getLlvmValue(callSb, arg),
                                        arg.getType(), paramType);
                                argList.append(LlvmTypeMapper.toLlvmType(paramType))
                                        .append(" ").append(val);
                            }
                            if (resultReg != null) {
                                callSb.append("  ").append(resultReg).append(" = call ")
                                        .append(LlvmTypeMapper.toLlvmType(retTypeForCall))
                                        .append(" @").append(directMangledFinal)
                                        .append("(").append(argList).append(")\n");
                            } else {
                                callSb.append("  call ")
                                        .append(LlvmTypeMapper.toLlvmType(retTypeForCall))
                                        .append(" @").append(directMangledFinal)
                                        .append("(").append(argList).append(")\n");
                            }
                        });
                        break;
                    }
                }

                if (inst.isPolymorphicSignature()) {
                    List<Value> allArgs = new ArrayList<>();
                    allArgs.add(receiver);
                    for (int i = 2; i < operands.size(); i++) {
                        allArgs.add(operands.get(i));
                    }
                    List<Value> argsWithoutReceiver = allArgs.subList(1, allArgs.size());
                    List<Type> paramTypes = argsWithoutReceiver.stream()
                            .map(Value::getType)
                            .collect(Collectors.toList());

                    NativeMethodInfo best = polymorphicResolver.findBestMatch(owner, methodName, retType, paramTypes);
                    if (best != null) {
                        String expectedDesc = buildDescriptor(retType, paramTypes);
                        boolean exactMatch      = expectedDesc.equals(best.getDescriptor());
                        boolean isVoidPtrWrapper = isVoidPtrArgsSignature(best);

                        if (exactMatch || !isVoidPtrWrapper) {
                            String funcName = best.getFullFunctionName();
                            Function func = ensurePolymorphicFunctionDeclared(best);
                            final List<Value> argsForCall = argsWithoutReceiver;
                            final Function calleeForCall = func;
                            emitCall(sb, retType, inst.getResult(), resultName, ranges, (callSb, resultReg) -> {
                                StringBuilder argList = new StringBuilder();
                                for (int i = 0; i < argsForCall.size(); i++) {
                                    if (i > 0) argList.append(", ");
                                    Value arg = argsForCall.get(i);
                                    Type paramType = (calleeForCall != null && i < calleeForCall.getParameters().size())
                                            ? calleeForCall.getParameters().get(i).getType()
                                            : arg.getType();
                                    String val = castValueToType(callSb, getLlvmValue(callSb, arg), arg.getType(), paramType);
                                    argList.append(LlvmTypeMapper.toLlvmType(paramType))
                                            .append(" ").append(val);
                                }
                                if (resultReg != null) {
                                    callSb.append("  ").append(resultReg).append(" = call ")
                                            .append(LlvmTypeMapper.toLlvmType(retType)).append(" @").append(funcName)
                                            .append("(").append(argList).append(")\n");
                                } else {
                                    callSb.append("  call ").append(LlvmTypeMapper.toLlvmType(retType))
                                            .append(" @").append(funcName).append("(").append(argList).append(")\n");
                                }
                            });
                            break;
                        } else {
                            emitPolymorphicCall(sb, allArgs, best, retType, paramTypes, ranges, resultName);
                            break;
                        }
                    }

                    String nativeSymbol = "__jnative_"
                            + LlvmRuntime.mangleMethod(owner, methodName, methodDesc);

                    Function nativeFunc = module.getFunction(nativeSymbol);
                    if (nativeFunc == null) {
                        Type nRetType = TypeResolver.descToReturnType(methodDesc);
                        List<Type> nParamTypes = TypeResolver.descToParamTypes(methodDesc);
                        nativeFunc = new Function(nativeSymbol, nRetType);
                        for (int i = 0; i < nParamTypes.size(); i++) {
                            nativeFunc.addParameter(new Parameter(nParamTypes.get(i), i));
                        }
                        module.addFunction(nativeFunc);
                    }

                    final List<Value> argsForCall = argsWithoutReceiver;
                    final Function nativeFuncForCall = nativeFunc;
                    final String nativeSymbolFinal = nativeSymbol;
                    emitCall(sb, retType, inst.getResult(), resultName, ranges, (callSb, resultReg) -> {
                        StringBuilder argList = new StringBuilder();
                        for (int i = 0; i < argsForCall.size(); i++) {
                            if (i > 0) argList.append(", ");
                            Value arg = argsForCall.get(i);
                            Type paramType = (i < nativeFuncForCall.getParameters().size())
                                    ? nativeFuncForCall.getParameters().get(i).getType()
                                    : arg.getType();
                            String val = castValueToType(callSb, getLlvmValue(callSb, arg), arg.getType(), paramType);
                            argList.append(LlvmTypeMapper.toLlvmType(paramType))
                                    .append(" ").append(val);
                        }
                        if (resultReg != null) {
                            callSb.append("  ").append(resultReg).append(" = call ")
                                    .append(LlvmTypeMapper.toLlvmType(retType)).append(" @").append(nativeSymbolFinal)
                                    .append("(").append(argList).append(")\n");
                        } else {
                            callSb.append("  call ").append(LlvmTypeMapper.toLlvmType(retType))
                                    .append(" @").append(nativeSymbolFinal).append("(").append(argList).append(")\n");
                        }
                    });
                    break;
                }

                String nativeCandidate = "__jnative_" + LlvmRuntime.mangleCallable(calleeName);
                Function nativeFunc = null;
                if (isVirtualCallDirectNativeSafe(owner, methodName, methodDesc)) {
                    nativeFunc = module.getFunction(nativeCandidate);
                }
                if (nativeFunc != null) {
                    List<Value> args = new ArrayList<>();
                    args.add(receiver);
                    for (int i = 2; i < operands.size(); i++) args.add(operands.get(i));
                    final List<Value> argsForCall = args;
                    final Function nativeFuncForCall = nativeFunc;
                    final String nativeCandidateFinal = nativeCandidate;
                    emitCall(sb, retType, inst.getResult(), resultName, ranges, (callSb, resultReg) -> {
                        StringBuilder argList = new StringBuilder();
                        for (int i = 0; i < argsForCall.size(); i++) {
                            if (i > 0) argList.append(", ");
                            Value arg = argsForCall.get(i);
                            Type paramType = (i < nativeFuncForCall.getParameters().size())
                                    ? nativeFuncForCall.getParameters().get(i).getType()
                                    : arg.getType();
                            String val = castValueToType(callSb, getLlvmValue(callSb, arg), arg.getType(), paramType);
                            argList.append(LlvmTypeMapper.toLlvmType(paramType)).append(" ").append(val);
                        }
                        if (resultReg != null) {
                            callSb.append("  ").append(resultReg).append(" = call ")
                                    .append(LlvmTypeMapper.toLlvmType(retType)).append(" @").append(nativeCandidateFinal)
                                    .append("(").append(argList).append(")\n");
                        } else {
                            callSb.append("  call ").append(LlvmTypeMapper.toLlvmType(retType))
                                    .append(" @").append(nativeCandidateFinal).append("(").append(argList).append(")\n");
                        }
                    });
                    break;
                }

                List<Type> paramTypes = TypeResolver.descToParamTypes(methodDesc);
                Type receiverType = Type.reference(owner);
                List<Value> allArgs = new ArrayList<>();
                allArgs.add(receiver);
                for (int i = 2; i < operands.size(); i++) allArgs.add(operands.get(i));

                boolean receiverIsRef = receiver.getType().isReference()
                        || receiver.getType().isArray()
                        || receiver.getType().isNull()
                        || receiver.getType().isBlock()
                        || receiver.getType().isUnknown();

                boolean receiverIsArray = receiver.getType().isArray();

                boolean isInterfaceCall = (op == Opcode.INTERFACE_CALL);

                int dispatchSlot;
                int interfaceId = -1;
                if (isInterfaceCall) {
                    interfaceId  = globalEmitter.getInterfaceId(owner);
                    dispatchSlot = globalEmitter.getInterfaceMethodSlot(owner, methodName, methodDesc);
                } else {
                    dispatchSlot = globalEmitter.getVirtualSlot(owner, methodName, methodDesc);
                }

                if (!receiverIsRef || dispatchSlot < 0 || (isInterfaceCall && interfaceId < 0)
                        || receiverIsArray) {

                    String directOwner = owner;
                    if (directOwner != null && !directOwner.isEmpty()
                            && directOwner.charAt(0) == '[') {
                        directOwner = "java/lang/Object";
                    }

                    String directMangled = LlvmRuntime.mangleMethod(
                            directOwner, methodName, methodDesc);
                    String callTarget = null;
                    Function calleeForCall = null;

                    Function plainConcrete = module.getFunction(directMangled);
                    if (plainConcrete != null) {
                        boolean isAlias = !plainConcrete.getName().equals(directMangled);
                        if (isAlias || plainConcrete.getEntryBlock() != null) {
                            callTarget = plainConcrete.getName();
                            calleeForCall = plainConcrete;
                        }
                    }

                    if (callTarget == null) {
                        String nativeMangled = "__jnative_" + directMangled;
                        Function nativeTarget = module.getFunction(nativeMangled);
                        if (nativeTarget != null) {
                            callTarget = nativeMangled;
                            calleeForCall = nativeTarget;
                        }
                    }

                    if (callTarget != null) {
                        final List<Value> argsForCall = allArgs;
                        final Function calleeForCallFinal = calleeForCall;
                        final Type receiverTypeFinal = receiverType;
                        final List<Type> paramTypesFinal = paramTypes;
                        final String callTargetFinal = callTarget;
                        emitCall(sb, retType, inst.getResult(), resultName, ranges, (callSb, resultReg) -> {
                            StringBuilder argList = new StringBuilder();
                            for (int i = 0; i < argsForCall.size(); i++) {
                                if (i > 0) argList.append(", ");
                                Value arg = argsForCall.get(i);
                                Type paramType;
                                if (calleeForCallFinal != null && i < calleeForCallFinal.getParameters().size()) {
                                    paramType = calleeForCallFinal.getParameters().get(i).getType();
                                } else if (i == 0) {
                                    paramType = receiverTypeFinal;
                                } else if (i - 1 < paramTypesFinal.size()) {
                                    paramType = paramTypesFinal.get(i - 1);
                                } else {
                                    paramType = arg.getType();
                                }
                                String val = castValueToType(callSb, getLlvmValue(callSb, arg),
                                        arg.getType(), paramType);
                                argList.append(LlvmTypeMapper.toLlvmType(paramType))
                                        .append(" ").append(val);
                            }
                            if (resultReg != null) {
                                callSb.append("  ").append(resultReg).append(" = call ")
                                        .append(LlvmTypeMapper.toLlvmType(retType))
                                        .append(" @").append(callTargetFinal)
                                        .append("(").append(argList).append(")\n");
                            } else {
                                callSb.append("  call ")
                                        .append(LlvmTypeMapper.toLlvmType(retType))
                                        .append(" @").append(callTargetFinal)
                                        .append("(").append(argList).append(")\n");
                            }
                        });
                        break;
                    }

                    String diag = "unresolved call target '" + directMangled
                            + "' (original: " + owner + "." + methodName + methodDesc
                            + ") — the method is referenced by reachable bytecode "
                            + "but has no body in the module. "
                            + "This is a reachability-analysis gap.";
                    String msgRef = stringRef(diag);
                    sb.append("  call void @__jnative_unresolved_slot(i8* ")
                            .append(msgRef).append(")\n");
                    sb.append("  unreachable\n");
                    if (inst.getResult() != null) {
                        valueMapper.setValue(inst.getResult(),
                                getDefaultValue(inst.getResult().getType()));
                    }
                    break;
                }

                emitNullCheck(sb, receiver, ranges,
                        "receiver of " + owner + "." + methodName + methodDesc);
                String receiverRef = getLlvmValue(sb, receiver);

                boolean maybeArray = true;
                Type recvTy = receiver.getType();
                if (recvTy.isReference()) {
                    String rcn = recvTy.getClassName();
                    maybeArray = (rcn == null)
                            || "java/lang/Object".equals(rcn)
                            || "java/lang/Cloneable".equals(rcn)
                            || "java/io/Serializable".equals(rcn);
                }

                String vtableStruct = newAux("vtable_struct");
                if (maybeArray) {
                    sb.append("  ").append(vtableStruct)
                            .append(" = call %JNativeVTable* "
                                    + "@__jnative_resolve_dispatch_vtable(i8* ")
                            .append(receiverRef).append(")\n");
                } else {
                    String vtableSlot = newAux("vtable_slot");
                    sb.append("  ").append(vtableSlot)
                            .append(" = bitcast i8* ").append(receiverRef)
                            .append(" to %JNativeVTable**\n");
                    sb.append("  ").append(vtableStruct)
                            .append(" = load %JNativeVTable*, %JNativeVTable** ")
                            .append(vtableSlot).append("\n");
                }

                String funcPtr;
                if (isInterfaceCall) {
                    String ifacemapFieldPtr = newAux("ifacemap_field_ptr");
                    sb.append("  ").append(ifacemapFieldPtr)
                            .append(" = getelementptr %JNativeVTable, %JNativeVTable* ")
                            .append(vtableStruct).append(", i32 0, i32 1\n");

                    String ifacemapPtr = newAux("ifacemap_ptr");
                    sb.append("  ").append(ifacemapPtr)
                            .append(" = load %JNativeIfaceMap*, %JNativeIfaceMap** ")
                            .append(ifacemapFieldPtr).append("\n");

                    String itablePtr = newAux("itable_ptr");
                    sb.append("  ").append(itablePtr)
                            .append(" = call i8** @__jnative_lookup_itable(%JNativeIfaceMap* ")
                            .append(ifacemapPtr).append(", i32 ").append(interfaceId).append(")\n");

                    String funcPtrGep = newAux("funcptr_gep");
                    sb.append("  ").append(funcPtrGep)
                            .append(" = getelementptr i8*, i8** ").append(itablePtr)
                            .append(", i32 ").append(dispatchSlot).append("\n");

                    funcPtr = newAux("funcptr");
                    sb.append("  ").append(funcPtr)
                            .append(" = load i8*, i8** ").append(funcPtrGep).append("\n");
                } else {
                    String methodsFieldPtr = newAux("methods_field_ptr");
                    sb.append("  ").append(methodsFieldPtr)
                            .append(" = getelementptr %JNativeVTable, %JNativeVTable* ")
                            .append(vtableStruct).append(", i32 0, i32 0\n");

                    String methodsArr = newAux("methods_arr");
                    sb.append("  ").append(methodsArr)
                            .append(" = load i8**, i8*** ").append(methodsFieldPtr).append("\n");

                    String funcPtrGep = newAux("funcptr_gep");
                    sb.append("  ").append(funcPtrGep)
                            .append(" = getelementptr i8*, i8** ").append(methodsArr)
                            .append(", i32 ").append(dispatchSlot).append("\n");

                    funcPtr = newAux("funcptr");
                    sb.append("  ").append(funcPtr)
                            .append(" = load i8*, i8* ").append(funcPtrGep).append("\n");
                }

                String funcType = LlvmRuntime.getFunctionType(owner, sig);
                String funcPtrCast = newAux("fptrcast");
                sb.append("  ").append(funcPtrCast).append(" = bitcast i8* ").append(funcPtr)
                        .append(" to ").append(funcType).append("\n");

                final List<Value> argsForCall = allArgs;
                final Type receiverTypeFinal = receiverType;
                final List<Type> paramTypesFinal = paramTypes;
                final String funcPtrCastFinal = funcPtrCast;
                emitCall(sb, retType, inst.getResult(), resultName, ranges, (callSb, resultReg) -> {
                    StringBuilder argList = new StringBuilder();
                    for (int i = 0; i < argsForCall.size(); i++) {
                        if (i > 0) argList.append(", ");
                        Value arg = argsForCall.get(i);
                        Type paramType;
                        if (i == 0) paramType = receiverTypeFinal;
                        else if (i - 1 < paramTypesFinal.size()) paramType = paramTypesFinal.get(i - 1);
                        else paramType = arg.getType();
                        String val = castValueToType(callSb, getLlvmValue(callSb, arg), arg.getType(), paramType);
                        argList.append(LlvmTypeMapper.toLlvmType(paramType)).append(" ").append(val);
                    }
                    if (resultReg != null) {
                        callSb.append("  ").append(resultReg).append(" = call ")
                                .append(LlvmTypeMapper.toLlvmType(retType)).append(" ").append(funcPtrCastFinal)
                                .append("(").append(argList).append(")\n");
                    } else {
                        callSb.append("  call ").append(LlvmTypeMapper.toLlvmType(retType)).append(" ")
                                .append(funcPtrCastFinal).append("(").append(argList).append(")\n");
                    }
                });
                break;
            }

            case STATIC_CALL:
            case CALL: {
                String calleeName = extractCalleeName(inst);
                if (calleeName == null) break;

                String[] foundOwner = new String[1];
                boolean calleeIsStatic = false;
                {
                    int dotIdx = calleeName.lastIndexOf('.');
                    int parenIdx = calleeName.indexOf('(');
                    if (dotIdx > 0 && parenIdx > dotIdx) {
                        String owner = calleeName.substring(0, dotIdx);
                        String methodPart = calleeName.substring(dotIdx + 1);
                        int localParenIdx = parenIdx - dotIdx - 1;
                        String methodName = methodPart.substring(0, localParenIdx);
                        String descriptor = methodPart.substring(localParenIdx);

                        io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode mn =
                                resolver.findMethodInHierarchy(owner, methodName, descriptor, foundOwner);
                        if (mn != null && mn.isStatic()) {
                            calleeIsStatic = true;
                            String actualOwner =
                                    (foundOwner[0] != null && !foundOwner[0].isEmpty())
                                            ? foundOwner[0]
                                            : owner;
                            if (!actualOwner.equals(owner)) {
                                calleeName = actualOwner + "." + methodName + descriptor;
                            }
                        }
                    }
                }

                if (!calleeIsStatic) {
                    int dotIdx = calleeName.lastIndexOf('.');
                    int parenIdx = calleeName.indexOf('(');
                    if (dotIdx > 0 && parenIdx > dotIdx) {
                        String owner = calleeName.substring(0, dotIdx);
                        String methodPart = calleeName.substring(dotIdx + 1);
                        int localParenIdx = parenIdx - dotIdx - 1;
                        String methodName = methodPart.substring(0, localParenIdx);
                        String descriptor = methodPart.substring(localParenIdx);

                        String[] look = new String[1];
                        io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode mn =
                                resolver.findMethodInHierarchy(owner, methodName, descriptor, look);
                        if (mn != null && !mn.isStatic()
                                && !owner.startsWith("java/lang/invoke")
                                && !"__jnative_get_exception_object".equals(calleeName)
                                && !calleeName.startsWith("__jnative_")) {
                            throw new IllegalStateException(
                                    "STATIC_CALL / CALL opcode carries an instance-method "
                                            + "target '" + owner + "." + methodName + descriptor
                                            + "'.  The reachability analysis or the IR builder "
                                            + "mis-classified the call site; refusing to emit "
                                            + "a direct call with a mismatched calling convention.");
                        }
                    }
                }

                if (inst.isPolymorphicSignature()) {
                    int dotIdx = calleeName.lastIndexOf('.');
                    int parenIdx = calleeName.indexOf('(');
                    if (dotIdx > 0 && parenIdx > dotIdx) {
                        String owner = calleeName.substring(0, dotIdx);
                        String methodPart = calleeName.substring(dotIdx + 1);
                        int localParenIdx = parenIdx - dotIdx - 1;
                        String methodName = methodPart.substring(0, localParenIdx);

                        Type retType = inst.getResult() != null ? inst.getResult().getType() : Type.VOID;
                        List<Value> args = getCallArguments(inst);
                        List<Type> paramTypes = args.stream().map(Value::getType).collect(Collectors.toList());

                        NativeMethodInfo best = polymorphicResolver.findBestMatch(
                                owner, methodName, retType, paramTypes);
                        if (best != null) {
                            String expectedDesc = buildDescriptor(retType, paramTypes);
                            boolean exactMatch       = expectedDesc.equals(best.getDescriptor());
                            boolean isVoidPtrWrapper = isVoidPtrArgsSignature(best);

                            if (exactMatch || !isVoidPtrWrapper) {
                                String funcName = best.getFullFunctionName();
                                Function func = ensurePolymorphicFunctionDeclared(best);
                                final List<Value> argsForCall = args;
                                final Function calleeForCall = func;
                                emitCall(sb, retType, inst.getResult(), resultName, ranges, (callSb, resultReg) -> {
                                    StringBuilder argList = new StringBuilder();
                                    for (int i = 0; i < argsForCall.size(); i++) {
                                        if (i > 0) argList.append(", ");
                                        Value arg = argsForCall.get(i);
                                        Type paramType = (calleeForCall != null && i < calleeForCall.getParameters().size())
                                                ? calleeForCall.getParameters().get(i).getType()
                                                : arg.getType();
                                        String val = castValueToType(callSb, getLlvmValue(callSb, arg), arg.getType(), paramType);
                                        argList.append(LlvmTypeMapper.toLlvmType(paramType))
                                                .append(" ").append(val);
                                    }
                                    if (resultReg != null) {
                                        callSb.append("  ").append(resultReg).append(" = call ")
                                                .append(LlvmTypeMapper.toLlvmType(retType)).append(" @").append(funcName)
                                                .append("(").append(argList).append(")\n");
                                    } else {
                                        callSb.append("  call ").append(LlvmTypeMapper.toLlvmType(retType))
                                                .append(" @").append(funcName).append("(").append(argList).append(")\n");
                                    }
                                });
                                break;
                            } else {
                                emitPolymorphicCall(sb, args, best, retType, paramTypes,
                                        ranges, resultName);
                                break;
                            }
                        }
                    }
                }

                Function calleeFunc = module.getFunction(calleeName);
                if (calleeFunc == null) {
                    calleeFunc = module.getFunction(LlvmRuntime.mangleCallable(calleeName));
                }
                String mangledCallee;
                if (calleeFunc != null) {
                    mangledCallee = calleeFunc.getName();
                } else if (calleeName.startsWith("__jnative_")) {
                    mangledCallee = calleeName;
                } else {
                    String nativeCandidate = "__jnative_" + LlvmRuntime.mangleCallable(calleeName);
                    if (module.getFunction(nativeCandidate) != null) {
                        mangledCallee = nativeCandidate;
                    } else {
                        String nativeSymbol = null;
                        Function nativeFunc = null;
                        int dotIdx = calleeName.lastIndexOf('.');
                        int parenIdx = calleeName.indexOf('(');
                        if (dotIdx > 0 && parenIdx > dotIdx) {
                            String owner = calleeName.substring(0, dotIdx);
                            String methodPart = calleeName.substring(dotIdx + 1);
                            int localParenIdx = parenIdx - dotIdx - 1;
                            String methodName = methodPart.substring(0, localParenIdx);
                            String descriptor = methodPart.substring(localParenIdx);

                            String[] nativeLookup = new String[1];
                            io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode mn =
                                    resolver.findMethodInHierarchy(owner, methodName, descriptor, nativeLookup);
                            if (mn != null && mn.isNative()) {
                                String actualOwner =
                                        nativeLookup[0] != null ? nativeLookup[0] : owner;
                                Function resolved =
                                        lookupNativeFunction(actualOwner, methodName, descriptor);
                                if (resolved != null) {
                                    nativeFunc = resolved;
                                    nativeSymbol = resolved.getName();
                                } else {
                                    nativeSymbol = "__jnative_"
                                            + LlvmRuntime.mangleMethod(actualOwner, methodName, descriptor);
                                }
                            }
                        }

                        if (nativeSymbol != null) {
                            if (nativeFunc == null && module.getFunction(nativeSymbol) == null) {
                                int paren2 = calleeName.indexOf('(');
                                String desc2 = calleeName.substring(paren2);
                                Type nRetType = TypeResolver.descToReturnType(desc2);
                                List<Type> nParamTypes = TypeResolver.descToParamTypes(desc2);
                                Function nFunc = new Function(nativeSymbol, nRetType);
                                for (int i = 0; i < nParamTypes.size(); i++) {
                                    nFunc.addParameter(new Parameter(nParamTypes.get(i), i));
                                }
                                module.addFunction(nFunc);
                            }
                            mangledCallee = nativeSymbol;
                            if (nativeFunc != null) {
                                calleeFunc = nativeFunc;
                            }
                        } else {
                            if (dotIdx > 0 && parenIdx > dotIdx) {
                                String owner = calleeName.substring(0, dotIdx);
                                String methodPart = calleeName.substring(dotIdx + 1);
                                int localParenIdx = parenIdx - dotIdx - 1;
                                String methodName = methodPart.substring(0, localParenIdx);
                                String descriptor = methodPart.substring(localParenIdx);
                                ensureFunctionDeclared(owner, methodName, descriptor);
                            }
                            mangledCallee = LlvmRuntime.mangleCallable(calleeName);
                        }
                    }
                }

                if (!isCallableSymbolDefined(mangledCallee)) {
                    String diag = "unresolved call target '" + mangledCallee
                            + "' (original: " + calleeName + ") — the method is referenced "
                            + "by reachable bytecode but was never translated into the module. "
                            + "This is almost always a reachability-analysis gap.";
                    String msgRef = stringRef(diag);
                    sb.append("  call void @__jnative_unresolved_slot(i8* ")
                            .append(msgRef).append(")\n");
                    sb.append("  unreachable\n");
                    if (inst.getResult() != null) {
                        valueMapper.setValue(inst.getResult(),
                                getDefaultValue(inst.getResult().getType()));
                    }
                    break;
                }

                List<Value> args = getCallArguments(inst);

                if (calleeFunc != null) {
                    int expected = calleeFunc.getParameters().size();
                    if (args.size() > expected) {
                        boolean removeFirst = false;
                        if (expected == 0) {
                            removeFirst = true;
                        } else {
                            Type firstArgType = args.getFirst().getType();
                            Type firstParamType = calleeFunc.getParameters().getFirst().getType();
                            if (firstArgType.isReference() && !firstParamType.isReference()) {
                                removeFirst = true;
                            }
                        }
                        if (removeFirst) {
                            args = args.subList(1, args.size());
                        }
                    }
                }

                Type retType = inst.getResult() != null ? inst.getResult().getType() : Type.VOID;
                final List<Value> argsForCall = args;
                final Function calleeForCall = calleeFunc;
                final String mangledCalleeFinal = mangledCallee;
                final Type retTypeForCall = retType;
                final boolean isJni = calleeForCall != null && calleeForCall.isJniNative();
                final boolean isStaticJni = calleeForCall != null && calleeForCall.isStaticJniNative();
                final String jniClassRef = isJni && calleeForCall.getOwnerClass() != null
                        ? globalEmitter.getRefClassGlobalName(calleeForCall.getOwnerClass())
                        : null;
                emitCall(sb, retTypeForCall, inst.getResult(), resultName, ranges, (callSb, resultReg) -> {
                    StringBuilder argList = new StringBuilder();
                    if (isJni) {
                        String env = newAux("jnienv");
                        callSb.append("  ").append(env)
                                .append(" = call i8* @__jnative_get_jnienv()\n");
                        argList.append("i8* ").append(env);
                        if (isStaticJni && jniClassRef != null) {
                            argList.append(", i8* ").append(jniClassRef);
                        }
                    }
                    for (int i = 0; i < argsForCall.size(); i++) {
                        if (!argList.isEmpty()) argList.append(", ");
                        Value arg = argsForCall.get(i);
                        Type paramType;
                        if (calleeForCall != null && i < calleeForCall.getParameters().size()) {
                            paramType = calleeForCall.getParameters().get(i).getType();
                        } else {
                            paramType = arg.getType();
                        }
                        String val = castValueToType(callSb, getLlvmValue(callSb, arg), arg.getType(), paramType);
                        argList.append(LlvmTypeMapper.toLlvmType(paramType)).append(" ").append(val);
                    }
                    if (resultReg != null) {
                        callSb.append("  ").append(resultReg).append(" = call ")
                                .append(LlvmTypeMapper.toLlvmType(retTypeForCall)).append(" @").append(mangledCalleeFinal)
                                .append("(").append(argList).append(")\n");
                    } else {
                        callSb.append("  call ").append(LlvmTypeMapper.toLlvmType(retTypeForCall))
                                .append(" @").append(mangledCalleeFinal).append("(").append(argList).append(")\n");
                    }
                });
                break;
            }

            case SPECIAL_CALL: {
                if (inst.getOperands().size() < 2) break;
                Value receiver = inst.getOperands().get(0);
                Value calleeConst = inst.getOperands().get(1);
                if (!(calleeConst instanceof Constant)) break;
                String calleeName = ((Constant) calleeConst).getValue().toString();

                if (inst.isPolymorphicSignature()) {
                    int dotIdx = calleeName.lastIndexOf('.');
                    int parenIdx = calleeName.indexOf('(');
                    if (dotIdx > 0 && parenIdx > dotIdx) {
                        String owner = calleeName.substring(0, dotIdx);
                        String methodPart = calleeName.substring(dotIdx + 1);
                        int localParenIdx = parenIdx - dotIdx - 1;
                        String methodName = methodPart.substring(0, localParenIdx);

                        List<Value> allArgs = new ArrayList<>();
                        allArgs.add(receiver);
                        for (int i = 2; i < inst.getOperands().size(); i++) {
                            allArgs.add(inst.getOperands().get(i));
                        }
                        List<Value> argsWithoutReceiver = allArgs.subList(1, allArgs.size());
                        Type retType = inst.getResult() != null ? inst.getResult().getType() : Type.VOID;
                        List<Type> paramTypes = argsWithoutReceiver.stream()
                                .map(Value::getType).collect(Collectors.toList());

                        NativeMethodInfo best = polymorphicResolver.findBestMatch(
                                owner, methodName, retType, paramTypes);
                        if (best != null) {
                            String expectedDesc = buildDescriptor(retType, paramTypes);
                            boolean exactMatch       = expectedDesc.equals(best.getDescriptor());
                            boolean isVoidPtrWrapper = isVoidPtrArgsSignature(best);

                            if (exactMatch || !isVoidPtrWrapper) {
                                String funcName = best.getFullFunctionName();
                                Function func = ensurePolymorphicFunctionDeclared(best);
                                final List<Value> argsForCall = argsWithoutReceiver;
                                final Function calleeForCall = func;
                                emitCall(sb, retType, inst.getResult(), resultName, ranges, (callSb, resultReg) -> {
                                    StringBuilder argList = new StringBuilder();
                                    for (int i = 0; i < argsForCall.size(); i++) {
                                        if (i > 0) argList.append(", ");
                                        Value arg = argsForCall.get(i);
                                        Type paramType = (calleeForCall != null && i < calleeForCall.getParameters().size())
                                                ? calleeForCall.getParameters().get(i).getType()
                                                : arg.getType();
                                        String val = castValueToType(callSb, getLlvmValue(callSb, arg),
                                                arg.getType(), paramType);
                                        argList.append(LlvmTypeMapper.toLlvmType(paramType))
                                                .append(" ").append(val);
                                    }
                                    if (resultReg != null) {
                                        callSb.append("  ").append(resultReg).append(" = call ")
                                                .append(LlvmTypeMapper.toLlvmType(retType)).append(" @").append(funcName)
                                                .append("(").append(argList).append(")\n");
                                    } else {
                                        callSb.append("  call ").append(LlvmTypeMapper.toLlvmType(retType))
                                                .append(" @").append(funcName).append("(").append(argList).append(")\n");
                                    }
                                });
                                break;
                            } else {
                                emitPolymorphicCall(sb, allArgs, best, retType, paramTypes,
                                        ranges, resultName);
                                break;
                            }
                        }
                    }
                }

                Function calleeFunc = module.getFunction(calleeName);
                if (calleeFunc == null) {
                    calleeFunc = module.getFunction(LlvmRuntime.mangleCallable(calleeName));
                }
                String mangledCallee;
                if (calleeFunc != null) {
                    mangledCallee = calleeFunc.getName();
                } else {
                    String nativeCandidate = "__jnative_" + LlvmRuntime.mangleCallable(calleeName);
                    if (module.getFunction(nativeCandidate) != null) {
                        mangledCallee = nativeCandidate;
                    } else {
                        String nativeSymbol = null;
                        Function nativeFunc = null;
                        int dotIdx = calleeName.lastIndexOf('.');
                        int parenIdx = calleeName.indexOf('(');
                        if (dotIdx > 0 && parenIdx > dotIdx) {
                            String owner = calleeName.substring(0, dotIdx);
                            String methodPart = calleeName.substring(dotIdx + 1);
                            int localParenIdx = parenIdx - dotIdx - 1;
                            String methodName = methodPart.substring(0, localParenIdx);
                            String descriptor = methodPart.substring(localParenIdx);

                            String[] nativeLookup = new String[1];
                            io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode mn =
                                    resolver.findMethodInHierarchy(owner, methodName, descriptor, nativeLookup);
                            if (mn != null && mn.isNative()) {
                                String actualOwner =
                                        nativeLookup[0] != null ? nativeLookup[0] : owner;
                                Function resolved =
                                        lookupNativeFunction(actualOwner, methodName, descriptor);
                                if (resolved != null) {
                                    nativeFunc = resolved;
                                    nativeSymbol = resolved.getName();
                                } else {
                                    nativeSymbol = "__jnative_"
                                            + LlvmRuntime.mangleMethod(actualOwner, methodName, descriptor);
                                }
                            }
                        }

                        if (nativeSymbol != null) {
                            if (nativeFunc == null && module.getFunction(nativeSymbol) == null) {
                                int paren2 = calleeName.indexOf('(');
                                String desc2 = calleeName.substring(paren2);
                                Type nRetType = TypeResolver.descToReturnType(desc2);
                                List<Type> nParamTypes = TypeResolver.descToParamTypes(desc2);
                                Function nFunc = new Function(nativeSymbol, nRetType);
                                for (int i = 0; i < nParamTypes.size(); i++) {
                                    nFunc.addParameter(new Parameter(nParamTypes.get(i), i));
                                }
                                module.addFunction(nFunc);
                            }
                            mangledCallee = nativeSymbol;
                            if (nativeFunc != null) {
                                calleeFunc = nativeFunc;
                            }
                        } else {
                            if (dotIdx > 0 && parenIdx > dotIdx) {
                                String owner = calleeName.substring(0, dotIdx);
                                String methodPart = calleeName.substring(dotIdx + 1);
                                int localParenIdx = parenIdx - dotIdx - 1;
                                String methodName = methodPart.substring(0, localParenIdx);
                                String descriptor = methodPart.substring(localParenIdx);
                                ensureFunctionDeclared(owner, methodName, descriptor);
                            }
                            mangledCallee = LlvmRuntime.mangleCallable(calleeName);
                        }
                    }
                }

                if (!isCallableSymbolDefined(mangledCallee)) {
                    String diag = "unresolved call target '" + mangledCallee
                            + "' (original: " + calleeName + ") — the method is referenced "
                            + "by reachable bytecode but was never translated into the module. "
                            + "This is almost always a reachability-analysis gap.";
                    String msgRef = stringRef(diag);
                    sb.append("  call void @__jnative_unresolved_slot(i8* ")
                            .append(msgRef).append(")\n");
                    sb.append("  unreachable\n");
                    if (inst.getResult() != null) {
                        valueMapper.setValue(inst.getResult(),
                                getDefaultValue(inst.getResult().getType()));
                    }
                    break;
                }

                List<Value> args = new ArrayList<>();
                args.add(receiver);
                for (int i = 2; i < inst.getOperands().size(); i++) {
                    args.add(inst.getOperands().get(i));
                }

                Type retType = inst.getResult() != null ? inst.getResult().getType() : Type.VOID;
                final List<Value> argsForCall = args;
                final Function calleeForCall = calleeFunc;
                final String mangledCalleeFinal = mangledCallee;
                final Type retTypeForCall = retType;
                final boolean isJni = calleeForCall != null && calleeForCall.isJniNative();
                final boolean isStaticJni = calleeForCall != null && calleeForCall.isStaticJniNative();
                final String jniClassRef = isJni && calleeForCall.getOwnerClass() != null
                        ? globalEmitter.getRefClassGlobalName(calleeForCall.getOwnerClass())
                        : null;
                emitCall(sb, retTypeForCall, inst.getResult(), resultName, ranges, (callSb, resultReg) -> {
                    StringBuilder argList = new StringBuilder();
                    if (isJni) {
                        String env = newAux("jnienv");
                        callSb.append("  ").append(env)
                                .append(" = call i8* @__jnative_get_jnienv()\n");
                        argList.append("i8* ").append(env);
                        if (isStaticJni && jniClassRef != null) {
                            argList.append(", i8* ").append(jniClassRef);
                        }
                    }
                    for (int i = 0; i < argsForCall.size(); i++) {
                        if (!argList.isEmpty()) argList.append(", ");
                        Value arg = argsForCall.get(i);
                        Type paramType;
                        if (calleeForCall != null && i < calleeForCall.getParameters().size()) {
                            paramType = calleeForCall.getParameters().get(i).getType();
                        } else {
                            paramType = arg.getType();
                        }
                        String val = castValueToType(callSb, getLlvmValue(callSb, arg), arg.getType(), paramType);
                        argList.append(LlvmTypeMapper.toLlvmType(paramType)).append(" ").append(val);
                    }
                    if (resultReg != null) {
                        callSb.append("  ").append(resultReg).append(" = call ")
                                .append(LlvmTypeMapper.toLlvmType(retTypeForCall)).append(" @").append(mangledCalleeFinal)
                                .append("(").append(argList).append(")\n");
                    } else {
                        callSb.append("  call ").append(LlvmTypeMapper.toLlvmType(retTypeForCall))
                                .append(" @").append(mangledCalleeFinal).append("(").append(argList).append(")\n");
                    }
                });
                break;
            }

            case NEW: {
                String className = extractTypeName(inst);
                String structType = globalEmitter.getStructName(className);

                if (inst.isStackAllocated()) {
                    String allocName = newAux("stack_alloc");
                    pendingSlotDeclarations.add(
                            "  " + allocName + " = alloca " + structType + ", align 8\n");

                    String gepReg = newAux("alloc_gep");
                    sb.append("  ").append(gepReg)
                            .append(" = getelementptr ").append(structType)
                            .append(", ").append(structType).append("* null, i32 1\n");

                    String sizeReg = newAux("alloc_size");
                    sb.append("  ").append(sizeReg)
                            .append(" = ptrtoint ").append(structType)
                            .append("* ").append(gepReg).append(" to i64\n");

                    String i8Ptr = newAux("stack_i8");
                    sb.append("  ").append(i8Ptr)
                            .append(" = bitcast ").append(structType)
                            .append("* ").append(allocName).append(" to i8*\n");
                    sb.append("  call void @llvm.memset.p0i8.i64(i8* ")
                            .append(i8Ptr).append(", i8 0, i64 ").append(sizeReg)
                            .append(", i1 false)\n");

                    String vtableName = globalEmitter.getVtableName(className);
                    if (vtableName != null) {
                        String vtablePtr = newAux("vtableptr");
                        sb.append("  ").append(vtablePtr)
                                .append(" = bitcast %JNativeVTable* ").append(vtableName)
                                .append(" to i8*\n");

                        String objPtrCast = newAux("objptrcast");
                        sb.append("  ").append(objPtrCast).append(" = bitcast ").append(structType)
                                .append("* ").append(allocName).append(" to i8**\n");

                        sb.append("  store i8* ").append(vtablePtr).append(", i8** ")
                                .append(objPtrCast).append("\n");
                    }

                    valuePointers.put(inst.getResult(),
                            new StackAlloc(allocName, structType));
                    valueMapper.setValue(inst.getResult(), null);
                    break;
                }

                String gepReg = newAux("alloc_gep");
                sb.append("  ").append(gepReg)
                        .append(" = getelementptr ").append(structType)
                        .append(", ").append(structType).append("* null, i32 1\n");

                String sizeReg = newAux("alloc_size");
                sb.append("  ").append(sizeReg)
                        .append(" = ptrtoint ").append(structType)
                        .append("* ").append(gepReg).append(" to i64\n");

                String allocReg = newAux("alloc");
                sb.append("  ").append(allocReg)
                        .append(" = call i8* @calloc(i64 1, i64 ").append(sizeReg).append(")\n");

                sb.append("  ").append(resultName).append(" = bitcast i8* ").append(allocReg)
                        .append(" to ").append(structType).append("*\n");

                String vtableName = globalEmitter.getVtableName(className);
                if (vtableName != null) {
                    String vtablePtr = newAux("vtableptr");
                    sb.append("  ").append(vtablePtr)
                            .append(" = bitcast %JNativeVTable* ").append(vtableName)
                            .append(" to i8*\n");

                    String objPtrCast = newAux("objptrcast");
                    sb.append("  ").append(objPtrCast).append(" = bitcast ").append(structType)
                            .append("* ").append(resultName).append(" to i8**\n");

                    sb.append("  store i8* ").append(vtablePtr).append(", i8** ")
                            .append(objPtrCast).append("\n");
                }
                break;
            }

            case NEW_ARRAY: {
                if (inst.getOperands().size() < 2) break;
                Value sizeVal = inst.getOperands().get(0);
                Value elemTypeConst = inst.getOperands().get(1);
                if (!(elemTypeConst instanceof Constant)) break;
                String elemTypeName = ((Constant) elemTypeConst).getValue().toString();
                Type elemType = elemTypeFromConst(elemTypeName);
                int elemSize = getElementSizeOfType(elemType);

                String sizeRef = coerceSizeToInt(sb, sizeVal, getLlvmValue(sb, sizeVal));

                String totalSize = newAux("total_size");
                sb.append("  ").append(totalSize).append(" = mul i32 ")
                        .append(sizeRef).append(", ").append(elemSize).append("\n");

                String totalSize64 = newAux("total_size64");
                sb.append("  ").append(totalSize64).append(" = zext i32 ")
                        .append(totalSize).append(" to i64\n");

                String allocSize = newAux("alloc_size");
                sb.append("  ").append(allocSize).append(" = add i64 ")
                        .append(totalSize64).append(", 16\n");

                String allocReg = newAux("alloc");
                sb.append("  ").append(allocReg)
                        .append(" = call i8* @calloc(i64 1, i64 ")
                        .append(allocSize).append(")\n");

                String arrClassName = arrayClassDescriptorFor(elemTypeName);
                if (arrClassName == null) arrClassName = "[Ljava/lang/Object;";
                String arrClassRef = globalEmitter.getRefClassGlobalName(arrClassName);

                String klassPtr = newAux("klass_ptr");
                sb.append("  ").append(klassPtr).append(" = bitcast i8* ").append(allocReg)
                        .append(" to %ReflectionClass**\n");
                sb.append("  store %ReflectionClass* ").append(arrClassRef)
                        .append(", %ReflectionClass** ").append(klassPtr).append("\n");

                String lenPtr = newAux("lenptr");
                sb.append("  ").append(lenPtr)
                        .append(" = getelementptr inbounds i8, i8* ").append(allocReg)
                        .append(", i64 8\n");
                String lenPtrI32 = newAux("lenptr_i32");
                sb.append("  ").append(lenPtrI32).append(" = bitcast i8* ").append(lenPtr)
                        .append(" to i32*\n");
                sb.append("  store i32 ").append(sizeRef).append(", i32* ")
                        .append(lenPtrI32).append("\n");

                String esPtr = newAux("esptr");
                sb.append("  ").append(esPtr)
                        .append(" = getelementptr inbounds i8, i8* ").append(allocReg)
                        .append(", i64 12\n");
                String esPtrI32 = newAux("esptr_i32");
                sb.append("  ").append(esPtrI32).append(" = bitcast i8* ").append(esPtr)
                        .append(" to i32*\n");
                sb.append("  store i32 ").append(elemSize).append(", i32* ")
                        .append(esPtrI32).append("\n");

                sb.append("  ").append(resultName).append(" = bitcast i8* ").append(allocReg)
                        .append(" to ")
                        .append(LlvmTypeMapper.toLlvmType(inst.getResult().getType())).append("\n");
                break;
            }

            case MULTI_NEW_ARRAY: {
                if (inst.getOperands().isEmpty()) break;
                Value descConst = inst.getOperands().getFirst();
                if (!(descConst instanceof Constant)) break;
                String desc = ((Constant) descConst).getValue().toString();

                List<Value> sizeValues = new ArrayList<>();
                for (int i = 1; i < inst.getOperands().size(); i++) {
                    sizeValues.add(inst.getOperands().get(i));
                }
                int dims = sizeValues.size();
                if (dims == 0) break;

                int elemSize = getBaseElementSize(desc);

                String sizesArray = newAux("sizes_array");
                String sizesSize = newAux("sizes_size");
                sb.append("  ").append(sizesSize).append(" = mul i32 ").append(dims).append(", 4\n");
                String sizesSize64 = newAux("sizes_size64");
                sb.append("  ").append(sizesSize64).append(" = zext i32 ").append(sizesSize).append(" to i64\n");
                sb.append("  ").append(sizesArray).append(" = call i8* @calloc(i64 1, i64 ").append(sizesSize64).append(")\n");
                String sizesI32 = newAux("sizes_i32");
                sb.append("  ").append(sizesI32).append(" = bitcast i8* ").append(sizesArray).append(" to i32*\n");
                for (int i = 0; i < dims; i++) {
                    String sizeRef = coerceSizeToInt(sb, sizeValues.get(i), getLlvmValue(sb, sizeValues.get(i)));
                    String ptr = newAux("sizes_ptr_" + i);
                    sb.append("  ").append(ptr).append(" = getelementptr i32, i32* ")
                            .append(sizesI32).append(", i32 ").append(i).append("\n");
                    sb.append("  store i32 ").append(sizeRef)
                            .append(", i32* ").append(ptr).append("\n");
                }

                String callRes = newAux("multiarr");
                int arrLen = LlvmRuntime.typeStringArrayLength(desc);
                sb.append("  ").append(callRes).append(" = call i8* @__jnative_new_multi_array(i8* getelementptr inbounds ([")
                        .append(arrLen).append(" x i8], [")
                        .append(arrLen).append(" x i8]* ")
                        .append(LlvmRuntime.typeStringGlobalName(desc)).append(", i32 0, i32 0), i32 ")
                        .append(dims).append(", i32* ").append(sizesI32).append(", i32 ")
                        .append(elemSize).append(")\n");
                sb.append("  call void @free(i8* ").append(sizesArray).append(")\n");
                if (resultName != null) {
                    sb.append("  ").append(resultName).append(" = bitcast i8* ").append(callRes)
                            .append(" to ").append(LlvmTypeMapper.toLlvmType(inst.getResult().getType())).append("\n");
                }
                break;
            }

            case FREE: {
                if (inst.getOperands().isEmpty()) {
                    break;
                }
                Value obj = inst.getOperands().getFirst();
                if (obj == null) {
                    break;
                }
                if (obj instanceof Constant c && c.getValue() == null) {
                    break;
                }
                String objRef = getLlvmValue(sb, obj);
                if ("null".equals(objRef)) {
                    break;
                }
                sb.append("  call void @__jnative_free(i8* ").append(objRef).append(")\n");
                break;
            }

            case JSR: {
                String retLabel = "entry";
                if (!inst.getOperands().isEmpty()
                        && inst.getOperands().getFirst() instanceof Constant c
                        && c.getType() == Type.BLOCK
                        && c.getValue() instanceof BasicBlock rb) {
                    retLabel = llvmLabel(rb);
                }
                String fn = inst.getParent() != null && inst.getParent().getFunction() != null
                        ? LlvmRuntime.mangleFunction(inst.getParent().getFunction().getName())
                        : "";
                sb.append("  ").append(resultName)
                        .append(" = bitcast i8* blockaddress(@").append(fn)
                        .append(", %").append(retLabel).append(") to i8*\n");
                break;
            }

            case MONITOR_ENTER: {
                Value obj = inst.getOperands().getFirst();
                emitNullCheck(sb, obj, ranges, "monitor");
                String objRef = getPointerOperand(sb, obj);
                sb.append("  call void @__jnative_monitor_enter(i8* ")
                        .append(objRef).append(")\n");
                break;
            }

            case MONITOR_EXIT: {
                Value obj = inst.getOperands().getFirst();
                emitNullCheck(sb, obj, ranges, "monitor");
                String objRef = getPointerOperand(sb, obj);
                sb.append("  call void @__jnative_monitor_exit(i8* ")
                        .append(objRef).append(")\n");
                break;
            }

            case INSTANCEOF: {
                Value obj = inst.getOperands().getFirst();
                String typeName = extractTypeName(inst);
                String objRef = getPointerOperand(sb, obj);

                if (typeName != null && typeName.startsWith("[")) {
                    sb.append("  ").append(resultName).append(" = icmp ne i8* ")
                            .append(objRef).append(", null\n");
                    break;
                }

                String typeInfoName = globalEmitter.getTypeInfoName(typeName);

                if (typeInfoName == null) {
                    sb.append("  ; WARNING: no typeInfo for ").append(typeName).append("\n");
                    sb.append("  ").append(resultName).append(" = call i1 @__jnative_instanceof(i8* ")
                            .append(objRef).append(", i8** null)\n");
                } else {
                    sb.append("  ").append(resultName).append(" = call i1 @__jnative_instanceof(i8* ")
                            .append(objRef).append(", i8** ")
                            .append(typeInfoName).append(")\n");
                }
                break;
            }

            case CHECKCAST: {
                Value obj = inst.getOperands().getFirst();
                String typeName = extractTypeName(inst);
                String objRef = getPointerOperand(sb, obj);

                if (typeName != null && typeName.startsWith("[")) {
                    sb.append("  ").append(resultName).append(" = bitcast i8* ").append(objRef)
                            .append(" to ").append(LlvmTypeMapper.toLlvmType(inst.getResult().getType()))
                            .append("\n");
                    break;
                }

                String typeInfoName = globalEmitter.getTypeInfoName(typeName);

                if (typeInfoName == null) {
                    sb.append("  ").append(resultName).append(" = bitcast i8* ").append(objRef)
                            .append(" to ").append(LlvmTypeMapper.toLlvmType(inst.getResult().getType()))
                            .append("\n");
                    break;
                }

                String isNull  = newAux("cc_isnull");
                String isInst  = newAux("cc_instof");
                String okComb  = newAux("cc_ok");
                String failLbl = newLabel("check_fail");
                String okLbl   = newLabel("check_ok");

                sb.append("  ").append(isNull).append(" = icmp eq i8* ").append(objRef).append(", null\n");
                sb.append("  ").append(isInst).append(" = call i1 @__jnative_instanceof(i8* ")
                        .append(objRef).append(", i8** ").append(typeInfoName).append(")\n");
                sb.append("  ").append(okComb).append(" = or i1 ").append(isNull)
                        .append(", ").append(isInst).append("\n");
                sb.append("  br i1 ").append(okComb)
                        .append(", label %").append(okLbl)
                        .append(", label %").append(failLbl).append("\n");
                sb.append(failLbl).append(":\n");
                emitThrowHelper(sb, "@__jnative_throw_class_cast_exception", ranges);
                sb.append(okLbl).append(":\n");
                sb.append("  ").append(resultName).append(" = bitcast i8* ").append(objRef)
                        .append(" to ").append(LlvmTypeMapper.toLlvmType(inst.getResult().getType()))
                        .append("\n");
                break;
            }

            case ARRAYLENGTH: {
                Value arr = inst.getOperands().getFirst();
                String arrRef = getLlvmValue(sb, arr);
                emitNullCheck(sb, arr, ranges, "<array>");
                String lenPtr = newAux("lenptr");
                sb.append("  ").append(lenPtr)
                        .append(" = getelementptr inbounds i8, i8* ").append(arrRef)
                        .append(", i64 8\n");
                String lenPtrI32 = newAux("lenptr_i32");
                sb.append("  ").append(lenPtrI32).append(" = bitcast i8* ").append(lenPtr)
                        .append(" to i32*\n");
                sb.append("  ").append(resultName).append(" = load i32, i32* ")
                        .append(lenPtrI32).append("\n");
                break;
            }

            case ALOAD: {
                if (inst.getOperands().size() < 2) break;
                Value arr = inst.getOperands().get(0);
                Value idx = inst.getOperands().get(1);

                emitNullCheck(sb, arr, ranges, "<array>");

                String arrPtr = asPointer(sb, arr);
                String idxRef = getLlvmValue(sb, idx);
                String idxI32 = castValueToType(sb, idxRef, idx.getType(), Type.INT);
                emitBoundsCheck(sb, arrPtr, idxI32, ranges);

                Type declaredElemType = inst.getResult().getType();
                Type elemType = declaredElemType;
                if (arr.getType().isArray()) {
                    Type arrElem = arr.getType().getElementType();
                    if (!arrElem.isUnknown()) {
                        elemType = arrElem;
                    }
                }

                if (!elemType.equals(declaredElemType)
                        && inst.getResult() instanceof Temporary resultTmp) {
                    resultTmp.setType(elemType);
                }

                int elemSize = getElementSizeOfType(elemType);
                String offset = newAux("offset");
                sb.append("  ").append(offset).append(" = mul i32 ")
                        .append(idxI32).append(", ").append(elemSize).append("\n");
                String offset64 = newAux("offset64");
                sb.append("  ").append(offset64).append(" = zext i32 ").append(offset).append(" to i64\n");
                String basePtr = newAux("baseptr");
                sb.append("  ").append(basePtr).append(" = getelementptr i8, i8* ").append(arrPtr)
                        .append(", i64 16\n");
                String elemPtr = newAux("elemptr");
                sb.append("  ").append(elemPtr).append(" = getelementptr i8, i8* ").append(basePtr)
                        .append(", i64 ").append(offset64).append("\n");
                String ptrCast = newAux("ptrcast");
                String llvmType = LlvmTypeMapper.toLlvmType(elemType);
                sb.append("  ").append(ptrCast).append(" = bitcast i8* ").append(elemPtr)
                        .append(" to ").append(llvmType).append("*\n");
                sb.append("  ").append(resultName).append(" = load ").append(llvmType)
                        .append(", ").append(llvmType).append("* ").append(ptrCast).append("\n");
                break;
            }

            case ASTORE: {
                if (inst.getOperands().size() >= 3) {
                    Value arr = inst.getOperands().get(0);
                    Value idx = inst.getOperands().get(1);
                    Value val = inst.getOperands().get(2);

                    emitNullCheck(sb, arr, ranges, "<array>");

                    String arrPtr = asPointer(sb, arr);
                    String idxRef = getLlvmValue(sb, idx);
                    String valRef = getLlvmValue(sb, val);
                    String idxI32 = castValueToType(sb, idxRef, idx.getType(), Type.INT);
                    emitBoundsCheck(sb, arrPtr, idxI32, ranges);
                    Type elemType = val.getType();
                    if (arr.getType().isArray()) {
                        Type arrElem = arr.getType().getElementType();
                        if (!arrElem.isUnknown()) {
                            elemType = arrElem;
                        }
                    }
                    int elemSize = getElementSizeOfType(elemType);
                    String offset = newAux("offset");
                    sb.append("  ").append(offset).append(" = mul i32 ")
                            .append(idxI32).append(", ").append(elemSize).append("\n");
                    String offset64 = newAux("offset64");
                    sb.append("  ").append(offset64).append(" = zext i32 ").append(offset).append(" to i64\n");
                    String basePtr = newAux("baseptr");
                    sb.append("  ").append(basePtr).append(" = getelementptr i8, i8* ").append(arrPtr)
                            .append(", i64 16\n");
                    String elemPtr = newAux("elemptr");
                    sb.append("  ").append(elemPtr).append(" = getelementptr i8, i8* ").append(basePtr)
                            .append(", i64 ").append(offset64).append("\n");
                    String ptrCast = newAux("ptrcast");
                    String llvmType = LlvmTypeMapper.toLlvmType(elemType);
                    String valConverted = castValueToType(sb, valRef, val.getType(), elemType);
                    sb.append("  ").append(ptrCast).append(" = bitcast i8* ").append(elemPtr)
                            .append(" to ").append(llvmType).append("*\n");
                    sb.append("  store ").append(llvmType).append(" ").append(valConverted)
                            .append(", ").append(llvmType).append("* ").append(ptrCast).append("\n");
                }
                break;
            }

            case PHI: {
                BasicBlock parent = getBasicBlock(inst);

                List<BasicBlock> preds = parent.getPredecessors();

                if (preds.size() != inst.getOperands().size()) {
                    throw new IllegalStateException(
                            "PHI operand count (" + inst.getOperands().size()
                                    + ") does not match predecessor count (" + preds.size()
                                    + ") in block " + parent.getLabel()
                                    + "; phi = " + inst);
                }

                Type phiType = inst.getResult().getType();
                String phiLlvm = LlvmTypeMapper.toLlvmType(phiType);

                sb.append("  ").append(resultName).append(" = phi ").append(phiLlvm).append(" ");
                for (int i = 0; i < inst.getOperands().size(); i++) {
                    if (i > 0) sb.append(", ");
                    Value phiOp = inst.getOperands().get(i);

                    String opLlvm = LlvmTypeMapper.toLlvmType(phiOp.getType());
                    if (!opLlvm.equals(phiLlvm)) {
                        throw new IllegalStateException(
                                "PHI operand type mismatch in block " + parent.getLabel()
                                        + ": phi type " + phiLlvm
                                        + ", operand " + i + " type " + opLlvm
                                        + " (value = " + phiOp + ")");
                    }

                    String valRef;
                    Map<Integer, String> preloads = phiPreloads.get(inst);
                    if (preloads != null && preloads.containsKey(i)) {
                        valRef = preloads.get(i);
                    } else if (valuePointers.containsKey(phiOp)) {
                        valRef = getLlvmValue(sb, phiOp);
                    } else if (valueSlots.containsKey(phiOp)) {
                        String mapped = valueMapper.getValue(phiOp);
                        valRef = mapped != null ? mapped : getDefaultValue(phiOp.getType());
                    } else {
                        valRef = getLlvmValue(sb, phiOp);
                    }
                    sb.append("[ ").append(valRef).append(", %")
                            .append(llvmLabel(preds.get(i))).append(" ]");
                }
                sb.append("\n");
                break;
            }

            case INVOKEDYNAMIC: {
                InvokeDynamicInfo dynInfo = (InvokeDynamicInfo) inst.getInvokedynamicData();
                ResolvedCall call = dynInfo.resolvedCall();
                if (call == null) {
                    sb.append("  ; unresolved invokedynamic\n");
                    if (inst.getResult() != null) {
                        valueMapper.setValue(inst.getResult(), "null");
                    }
                    break;
                }
                switch (call.getType()) {
                    case LAMBDA:
                        emitLambdaCreation(sb, inst, call, resultName);
                        break;
                    case CONCAT:
                        emitConcatCall(sb, inst, resultName);
                        break;
                    default:
                        sb.append("  ; unsupported invokedynamic type: ").append(call.getType()).append("\n");
                        if (inst.getResult() != null) {
                            valueMapper.setValue(inst.getResult(), "null");
                        }
                        break;
                }
                break;
            }

            case SELECT: {
                if (inst.getOperands().size() < 3 || inst.getResult() == null) {
                    sb.append("  ; malformed SELECT\n");
                    break;
                }
                Value cond     = inst.getOperands().get(0);
                Value trueVal  = inst.getOperands().get(1);
                Value falseVal = inst.getOperands().get(2);
                Type  resType  = inst.getResult().getType();

                String condRef  = getLlvmValue(sb, cond);
                String trueRef  = getLlvmValue(sb, trueVal);
                String falseRef = getLlvmValue(sb, falseVal);

                String trueConverted  = castValueToType(sb, trueRef,
                        trueVal.getType(),  resType);
                String falseConverted = castValueToType(sb, falseRef,
                        falseVal.getType(), resType);

                String llvmRes = LlvmTypeMapper.toLlvmType(resType);
                sb.append("  ").append(resultName).append(" = select i1 ")
                        .append(condRef)
                        .append(", ").append(llvmRes).append(" ").append(trueConverted)
                        .append(", ").append(llvmRes).append(" ").append(falseConverted)
                        .append("\n");
                break;
            }

            default:
                sb.append("  ; unsupported opcode: ").append(op).append("\n");
        }

        if (inst.getResult() != null
                && tryCrossingValues.contains(inst.getResult())
                && !valueSlots.containsKey(inst.getResult())) {

            resultName = valueMapper.getValue(inst.getResult());
            String needle = "  " + resultName + " = ";
            boolean resultActuallyDefined = resultName != null
                    && sb.toString().contains(needle);

            if (resultActuallyDefined) {
                Type resType = inst.getResult().getType();
                String resLlvm = LlvmTypeMapper.toLlvmType(resType);
                String slot = newAux("try_slot");
                pendingSlotDeclarations.add(
                        "  " + slot + " = alloca " + resLlvm + ", align 8\n");
                sb.append("  store ").append(resLlvm).append(" ").append(resultName)
                        .append(", ").append(resLlvm).append("* ").append(slot).append("\n");
                valueSlots.put(inst.getResult(), slot);
            }
        }

        return sb.toString();
    }

    private static BasicBlock getBasicBlock(Instruction inst) {
        BasicBlock parent = inst.getParent();
        if (parent == null) {
            throw new IllegalStateException(
                    "PHI instruction has no parent block: " + inst
                            + " (the SSA transformer must set parent on every phi)");
        }
        return parent;
    }

    private void emitPolymorphicCall(StringBuilder sb, List<Value> args,
                                     NativeMethodInfo best, Type retType,
                                     List<Type> paramTypes, List<TryCatchRange> ranges,
                                     String resultName) {
        String arrayAlloc = newAux("poly_args");
        int argCount = args.size();
        sb.append("  ").append(arrayAlloc).append(" = alloca i8*, i32 ").append(argCount).append("\n");

        for (int i = 0; i < argCount; i++) {
            Value arg = args.get(i);
            String val = getLlvmValue(sb, arg);
            String ptr;

            if (arg.getType().isPrimitive()) {
                String alloc = newAux("arg_alloc");
                String ty = LlvmTypeMapper.toLlvmType(arg.getType());
                sb.append("  ").append(alloc).append(" = alloca ").append(ty).append("\n");
                sb.append("  store ").append(ty).append(" ").append(val).append(", ").append(ty).append("* ").append(alloc).append("\n");
                String cast = newAux("arg_cast");
                sb.append("  ").append(cast).append(" = bitcast ").append(ty).append("* ").append(alloc).append(" to i8*\n");
                ptr = cast;
            } else {
                String cast = newAux("arg_cast");
                sb.append("  ").append(cast).append(" = bitcast ").append(LlvmTypeMapper.toLlvmType(arg.getType()))
                        .append(" ").append(val).append(" to i8*\n");
                ptr = cast;
            }

            String slot = newAux("slot");
            sb.append("  ").append(slot).append(" = getelementptr i8*, i8** ").append(arrayAlloc).append(", i32 ").append(i).append("\n");
            sb.append("  store i8* ").append(ptr).append(", i8** ").append(slot).append("\n");
        }

        String funcName = best.getFullFunctionName();
        ensurePolymorphicFunctionDeclared(best);

        String retLlvm = LlvmTypeMapper.toLlvmType(retType);
        if (!retType.isVoid()) {
            sb.append("  ").append(resultName).append(" = call ").append(retLlvm)
                    .append(" @").append(funcName).append("(i8** ").append(arrayAlloc).append(")\n");
        } else {
            sb.append("  call void @").append(funcName).append("(i8** ").append(arrayAlloc).append(")\n");
        }
    }

    private Function ensurePolymorphicFunctionDeclared(NativeMethodInfo info) {
        String funcName = info.getFullFunctionName();
        Function func = module.getFunction(funcName);
        if (func != null) return func;

        String desc = info.getDescriptor();
        Type retType = Type.fromDescriptor(desc.substring(desc.lastIndexOf(')') + 1));
        List<Type> paramTypes = TypeResolver.descToParamTypes(desc);

        Function newFunc = new Function(funcName, retType);
        for (int i = 0; i < paramTypes.size(); i++) {
            newFunc.addParameter(new Parameter(paramTypes.get(i), i));
        }
        module.addFunction(newFunc);
        return newFunc;
    }

    private String buildDescriptor(Type returnType, List<Type> paramTypes) {
        StringBuilder sb = new StringBuilder();
        sb.append('(');
        for (Type pt : paramTypes) {
            sb.append(typeToFullDescriptor(pt));
        }
        sb.append(')');
        sb.append(typeToFullDescriptor(returnType));
        return sb.toString();
    }

    private String typeToFullDescriptor(Type type) {
        if (type == null) {
            return "Ljava/lang/Object;";
        }
        if (type.isPrimitive()) {
            return String.valueOf(typeToDescriptorChar(type));
        }
        if (type.isArray()) {
            Type elem = type.getElementType();
            if (elem == null) {
                return "[Ljava/lang/Object;";
            }
            return "[" + typeToFullDescriptor(elem);
        }
        if (type.isReference()) {
            String cls = type.getClassName();
            if (cls == null || cls.isEmpty()) {
                return "Ljava/lang/Object;";
            }
            if (cls.charAt(0) == '[') {
                return cls;
            }
            return "L" + cls + ";";
        }
        if (type.isNull()) {
            return "Ljava/lang/Object;";
        }
        return "Ljava/lang/Object;";
    }

    private char typeToDescriptorChar(Type type) {
        if (type == Type.VOID) return 'V';
        if (type == Type.BOOLEAN) return 'Z';
        if (type == Type.BYTE) return 'B';
        if (type == Type.SHORT) return 'S';
        if (type == Type.CHAR) return 'C';
        if (type == Type.INT) return 'I';
        if (type == Type.LONG) return 'J';
        if (type == Type.FLOAT) return 'F';
        if (type == Type.DOUBLE) return 'D';
        if (type.isReference()) return 'L';
        if (type.isArray()) return '[';
        return 'L';
    }

    private boolean isVoidPtrArgsSignature(NativeMethodInfo info) {
        List<ParserC.CParameter> params = info.getParameters();
        if (params.size() != 1) return false;
        String type = params.getFirst().getType().trim();
        return type.contains("void") && type.contains("*");
    }

    private void emitLambdaCreation(StringBuilder sb, Instruction inst, ResolvedCall call, String resultName) {
        String lambdaId = call.getLambdaStructName();
        List<Value> captured = inst.getOperands();
        List<Type> capturedTypes = call.getCapturedTypes();

        String structName = globalEmitter.registerLambdaStruct(lambdaId, capturedTypes);

        String lambdaClassName = "__Lambda_" + lambdaId;
        String vtableName = globalEmitter.getVtableName(lambdaClassName);
        if (vtableName == null) {
            throw new IllegalStateException(
                    "Lambda vtable not registered for lambdaId=" + lambdaId);
        }

        String gepReg = newAux("lambda_gep");
        sb.append("  ").append(gepReg).append(" = getelementptr ")
                .append(structName).append(", ").append(structName)
                .append("* null, i32 1\n");

        String sizeReg = newAux("lambda_size");
        sb.append("  ").append(sizeReg).append(" = ptrtoint ")
                .append(structName).append("* ").append(gepReg).append(" to i64\n");

        String alloc = newAux("lambda_alloc");
        sb.append("  ").append(alloc).append(" = call i8* @calloc(i64 1, i64 ")
                .append(sizeReg).append(")\n");

        String objPtr = Objects.requireNonNullElseGet(resultName, () -> newAux("lambda_obj"));
        sb.append("  ").append(objPtr).append(" = bitcast i8* ").append(alloc)
                .append(" to ").append(structName).append("*\n");

        String vtablePtr = newAux("lambda_vtable_ptr");
        sb.append("  ").append(vtablePtr)
                .append(" = bitcast %JNativeVTable* ").append(vtableName)
                .append(" to i8*\n");

        String objVtableSlot = newAux("obj_vtable_slot");
        sb.append("  ").append(objVtableSlot).append(" = bitcast ").append(structName)
                .append("* ").append(objPtr).append(" to i8**\n");
        sb.append("  store i8* ").append(vtablePtr).append(", i8** ")
                .append(objVtableSlot).append("\n");

        int offset = 8;
        for (Value cap : captured) {
            String fieldLlvm = LlvmTypeMapper.toLlvmType(cap.getType());
            String fieldPtr = newAux("cap_ptr");
            sb.append("  ").append(fieldPtr).append(" = getelementptr i8, i8* ").append(alloc)
                    .append(", i64 ").append(offset).append("\n");
            String castPtr = newAux("cap_cast");
            sb.append("  ").append(castPtr).append(" = bitcast i8* ").append(fieldPtr)
                    .append(" to ").append(fieldLlvm).append("*\n");
            String valRef = getLlvmValue(sb, cap);
            String valConverted = castValueToType(sb, valRef, cap.getType(), cap.getType());
            sb.append("  store ").append(fieldLlvm).append(" ").append(valConverted)
                    .append(", ").append(fieldLlvm).append("* ").append(castPtr).append("\n");
            offset += getElementSizeOfType(cap.getType());
        }

        if (inst.getResult() != null && resultName != null) {
            valueMapper.setValue(inst.getResult(), objPtr);
        }
    }

    private void emitConcatCall(StringBuilder sb, Instruction inst, String resultName) {
        ResolvedCall call = ((InvokeDynamicInfo) inst.getInvokedynamicData()).resolvedCall();
        String recipe = call.getConcatFormat();
        List<Value> dynamicArgs = inst.getOperands();

        List<String> parts = new ArrayList<>();
        int dynIdx = 0;

        if (recipe == null) {
            for (Value arg : dynamicArgs) {
                parts.add(convertArgToString(sb, arg));
            }
        } else {
            Object[] constants = parseConstants(call.getPackedConstants());
            int constIdx = 0;
            StringBuilder literal = new StringBuilder();
            for (int i = 0; i < recipe.length(); i++) {
                char c = recipe.charAt(i);
                if (c == '\u0001') {
                    flushLiteral(sb, literal, parts);
                    if (dynIdx >= dynamicArgs.size()) break;
                    parts.add(convertArgToString(sb, dynamicArgs.get(dynIdx++)));
                } else if (c == '\u0002') {
                    flushLiteral(sb, literal, parts);
                    if (constIdx < constants.length) {
                        String s = String.valueOf(constants[constIdx++]);
                        if (s != null) parts.add(emitLiteralString(sb, s));
                    }
                } else {
                    literal.append(c);
                }
            }
            flushLiteral(sb, literal, parts);
        }

        if (parts.isEmpty()) {
            parts.add(emitLiteralString(sb, ""));
        }

        String concatResult = newAux("concat_result");
        sb.append("  ").append(concatResult)
                .append(" = call i8* @__jnative_concat_strings(i32 ")
                .append(parts.size());
        for (String p : parts) {
            sb.append(", i8* ").append(p);
        }
        sb.append(")\n");

        if (inst.getResult() != null && resultName != null) {
            String casted = newAux("concat_cast");
            sb.append("  ").append(casted).append(" = bitcast i8* ").append(concatResult)
                    .append(" to ")
                    .append(LlvmTypeMapper.toLlvmType(inst.getResult().getType())).append("\n");
            valueMapper.setValue(inst.getResult(), casted);
        }
    }

    private void flushLiteral(StringBuilder sb, StringBuilder literal, List<String> parts) {
        if (!literal.isEmpty()) {
            parts.add(emitLiteralString(sb, literal.toString()));
            literal.setLength(0);
        }
    }

    private String emitLiteralString(StringBuilder sb, String s) {
        return "bitcast (%struct.java_lang_String* @"
                + LlvmRuntime.stringObjectGlobalName(s) + " to i8*)";
    }

    private String convertArgToString(StringBuilder sb, Value arg) {
        Type t = arg.getType();
        String ref = getLlvmValue(sb, arg);

        if (t.isReference()) {
            String cls = t.getClassName();
            if ("java/lang/String".equals(cls)) {
                return ref;
            }
            String res = newAux("to_str");
            sb.append("  ").append(res)
                    .append(" = call i8* @__jnative_value_to_string_object(i8* ")
                    .append(ref).append(")\n");
            return res;
        }

        if (t.isArray() || t.isNull() || t.isBlock() || t.isUnknown()) {
            String res = newAux("to_str");
            sb.append("  ").append(res)
                    .append(" = call i8* @__jnative_value_to_string_object(i8* ")
                    .append(ref).append(")\n");
            return res;
        }

        if (t == Type.INT || t == Type.BYTE || t == Type.SHORT
                || t == Type.CHAR || t == Type.BOOLEAN) {
            String fn;
            if (t == Type.BYTE)         fn = "__jnative_value_to_string_byte";
            else if (t == Type.SHORT)   fn = "__jnative_value_to_string_short";
            else if (t == Type.CHAR)    fn = "__jnative_value_to_string_char";
            else if (t == Type.BOOLEAN) fn = "__jnative_value_to_string_boolean";
            else                        fn = "__jnative_value_to_string_int";
            String casted = castValueToType(sb, ref, t, Type.INT);
            String res = newAux("to_str");
            sb.append("  ").append(res).append(" = call i8* @").append(fn)
                    .append("(i32 ").append(casted).append(")\n");
            return res;
        }

        if (t == Type.LONG) {
            String res = newAux("to_str");
            sb.append("  ").append(res).append(" = call i8* @__jnative_value_to_string_long(i64 ")
                    .append(ref).append(")\n");
            return res;
        }

        if (t == Type.FLOAT) {
            String res = newAux("to_str");
            sb.append("  ").append(res).append(" = call i8* @__jnative_value_to_string_float(float ")
                    .append(ref).append(")\n");
            return res;
        }

        if (t == Type.DOUBLE) {
            String res = newAux("to_str");
            sb.append("  ").append(res).append(" = call i8* @__jnative_value_to_string_double(double ")
                    .append(ref).append(")\n");
            return res;
        }

        return emitLiteralString(sb, "");
    }

    private Object[] parseConstants(String packed) {
        if (packed == null || packed.isEmpty()) return new Object[0];
        return packed.split("\u0000", -1);
    }

    private void emitCall(StringBuilder sb, Type retType, Value resultValue, String resultName,
                          List<TryCatchRange> ranges, CallEmitter emitter) {
        if (ranges.isEmpty()) {
            emitter.emit(sb, retType.isVoid() ? null : resultName);
            return;
        }
        String slot = null;
        if (!retType.isVoid() && resultValue != null) {
            slot = newAux("call_slot");
            pendingSlotDeclarations.add("  " + slot + " = alloca "
                    + LlvmTypeMapper.toLlvmType(retType) + ", align 8\n");
        }
        final String slotRef = slot;
        emitTryGuard(sb, ranges, inner -> {
            if (!retType.isVoid()) {
                String bodyResult = newAux("body_result");
                emitter.emit(inner, bodyResult);
                if (slotRef != null) {
                    inner.append("  store ")
                            .append(LlvmTypeMapper.toLlvmType(retType)).append(" ")
                            .append(bodyResult).append(", ")
                            .append(LlvmTypeMapper.toLlvmType(retType)).append("* ")
                            .append(slotRef).append("\n");
                }
            } else {
                emitter.emit(inner, null);
            }
        }, false);
        if (slotRef != null) valueSlots.put(resultValue, slotRef);
    }

    private void emitDivByZeroCheck(StringBuilder sb, Value divisor, List<TryCatchRange> ranges) {
        String ty = LlvmTypeMapper.toLlvmType(divisor.getType());

        String divisorRef = getLlvmValue(sb, divisor);

        String chk      = newAux("div_chk");
        String throwBlk = newLabel("throw_divzero");
        String cont     = newLabel("div_ok");

        sb.append("  ").append(chk).append(" = icmp ne ").append(ty).append(" ")
                .append(divisorRef).append(", 0\n");
        sb.append("  br i1 ").append(chk)
                .append(", label %").append(cont)
                .append(", label %").append(throwBlk).append("\n");
        sb.append(throwBlk).append(":\n");
        emitThrowHelper(sb, "@__jnative_throw_arithmetic_exception", ranges);
        sb.append(cont).append(":\n");
    }

    private void emitTryGuard(StringBuilder sb,
                              List<TryCatchRange> ranges,
                              Consumer<StringBuilder> body,
                              boolean neverReturnsNormally) {
        int k = ranges.size();
        String guardBlk = newLabel("guard");
        String bodyBlk  = newLabel("guarded_body");
        String catchBlk = newLabel("guarded_catch");
        String contBlk  = neverReturnsNormally ? null : newLabel("guarded_cont");

        sb.append("  br label %").append(guardBlk).append("\n");

        sb.append(guardBlk).append(":\n");

        for (int i = k - 1; i >= 0; i--) {
            TryCatchRange r = ranges.get(i);

            String bufPtr = newAux("sj_jb");
            sb.append("  ").append(bufPtr).append(" = bitcast [").append(JMP_BUF_SIZE)
                    .append(" x i8]* %jmp_buf_").append(rangeOrdinals.get(r))
                    .append(" to i8*\n");

            String sjRet = newAux("sj_ret");
            sb.append("  ").append(sjRet)
                    .append(" = call i32 @_setjmp(i8* ").append(bufPtr).append(")\n");

            String isZero = newAux("sj_zero");
            sb.append("  ").append(isZero).append(" = icmp eq i32 ").append(sjRet)
                    .append(", 0\n");

            String armContBlk = newLabel("arm_cont");
            sb.append("  br i1 ").append(isZero)
                    .append(", label %").append(armContBlk)
                    .append(", label %").append(catchBlk).append("\n");

            sb.append(armContBlk).append(":\n");
            sb.append("  call void @__jnative_push_catch(i8* ").append(bufPtr)
                    .append(", i8** ").append(catchTypeInfoOperand(r)).append(")\n");
        }

        sb.append("  br label %").append(bodyBlk).append("\n");

        sb.append(bodyBlk).append(":\n");
        body.accept(sb);
        if (neverReturnsNormally) {
            sb.append("  unreachable\n");
        } else {
            sb.repeat("  call void @__jnative_pop_catch()\n", k);
            sb.append("  br label %").append(contBlk).append("\n");
        }

        sb.append(catchBlk).append(":\n");
        String exc = newAux("caught_exc");
        sb.append("  ").append(exc).append(" = call i8* @__jnative_get_exception_object()\n");

        String[] missLabels = new String[k];
        for (int i = 0; i < k; i++) {
            missLabels[i] = newLabel("cmiss");
        }

        for (int i = 0; i < k; i++) {
            TryCatchRange r = ranges.get(i);
            if (i > 0) {
                sb.append(missLabels[i - 1]).append(":\n");
                sb.append("  call void @__jnative_pop_catch()\n");
            }
            String hitBlk = newLabel("chit");
            String ti = r.type != null ? globalEmitter.getTypeInfoName(r.type) : null;
            if (ti != null) {
                String matches = newAux("cm");
                sb.append("  ").append(matches).append(" = call i1 @__jnative_catch_matches(i8* ")
                        .append(exc).append(", i8** ").append(ti).append(")\n");
                sb.append("  br i1 ").append(matches)
                        .append(", label %").append(hitBlk)
                        .append(", label %").append(missLabels[i]).append("\n");
            } else {
                sb.append("  br label %").append(hitBlk).append("\n");
            }
            sb.append(hitBlk).append(":\n");
            sb.repeat("  call void @__jnative_pop_catch()\n", k - i);
            BasicBlock handler = handlerBlockByRange.get(r);
            sb.append("  br label %").append(llvmLabel(handler)).append("\n");
        }

        sb.append(missLabels[k - 1]).append(":\n");
        sb.append("  call void @__jnative_pop_catch()\n");
        sb.append("  call void @__jnative_throw_exception(i8* ").append(exc).append(")\n");
        sb.append("  unreachable\n");

        if (contBlk != null) {
            sb.append(contBlk).append(":\n");
        }
    }

    private String catchTypeInfoOperand(TryCatchRange r) {
        return r.type != null ? globalEmitter.getTypeInfoName(r.type) : null;
    }

    private void emitRegionArm(StringBuilder sb,
                               List<TryCatchRange> ranges,
                               String catchLabel,
                               String bodyLabel) {
        int k = ranges.size();
        String[] armLabels = new String[k];
        for (int i = 0; i < k; i++) armLabels[i] = newLabel("arm");

        sb.append("  br label %").append(armLabels[k - 1]).append("\n");

        for (int i = k - 1; i >= 0; i--) {
            TryCatchRange r = ranges.get(i);
            int ord = rangeOrdinals.get(r);
            String arm  = armLabels[i];
            String cont = newLabel("arm_cont");

            sb.append(arm).append(":\n");
            String bufPtr = newAux("sj_jb");
            sb.append("  ").append(bufPtr).append(" = bitcast [")
                    .append(JMP_BUF_SIZE).append(" x i8]* %jmp_buf_").append(ord)
                    .append(" to i8*\n");
            String sjRet = newAux("sj_ret");
            sb.append("  ").append(sjRet).append(" = call i32 @_setjmp(i8* ")
                    .append(bufPtr).append(")\n");
            String isZero = newAux("sj_zero");
            sb.append("  ").append(isZero).append(" = icmp eq i32 ").append(sjRet)
                    .append(", 0\n");
            sb.append("  br i1 ").append(isZero)
                    .append(", label %").append(cont)
                    .append(", label %").append(catchLabel).append("\n");

            sb.append(cont).append(":\n");
            sb.append("  call void @__jnative_push_catch(i8* ").append(bufPtr)
                    .append(", i8** ").append(catchTypeInfoOperand(r)).append(")\n");
            if (i > 0) sb.append("  br label %").append(armLabels[i - 1]).append("\n");
            else       sb.append("  br label %").append(bodyLabel).append("\n");
        }
    }

    private void emitCatchDispatchBody(StringBuilder sb,
                                       String catchLabel,
                                       List<TryCatchRange> ranges) {
        sb.append(catchLabel).append(":\n");
        String exc = newAux("caught_exc");
        sb.append("  ").append(exc)
                .append(" = call i8* @__jnative_get_exception_object()\n");

        int k = ranges.size();
        String[] missLabels = new String[k];
        for (int i = 0; i < k; i++) missLabels[i] = newLabel("cmiss");

        for (int i = 0; i < k; i++) {
            TryCatchRange r = ranges.get(i);
            if (i > 0) {
                sb.append(missLabels[i - 1]).append(":\n");
                sb.append("  call void @__jnative_pop_catch()\n");
            }
            String hitBlk = newLabel("chit");
            String ti = r.type != null ? globalEmitter.getTypeInfoName(r.type) : null;
            if (ti != null) {
                String matches = newAux("cm");
                sb.append("  ").append(matches)
                        .append(" = call i1 @__jnative_catch_matches(i8* ").append(exc)
                        .append(", i8** ").append(ti).append(")\n");
                sb.append("  br i1 ").append(matches)
                        .append(", label %").append(hitBlk)
                        .append(", label %").append(missLabels[i]).append("\n");
            } else {
                sb.append("  br label %").append(hitBlk).append("\n");
            }
            sb.append(hitBlk).append(":\n");
            sb.repeat("  call void @__jnative_pop_catch()\n", k - i);
            BasicBlock handler = handlerBlockByRange.get(r);
            sb.append("  br label %").append(llvmLabel(handler)).append("\n");
        }
        sb.append(missLabels[k - 1]).append(":\n");
        sb.append("  call void @__jnative_pop_catch()\n");
        sb.append("  call void @__jnative_throw_exception(i8* ").append(exc).append(")\n");
        sb.append("  unreachable\n");
    }

    private String emitTerminator(Terminator term, List<TryCatchRange> ranges,
                                  Map<BasicBlock, String> targetRedirects) {
        StringBuilder sb = new StringBuilder();
        java.util.function.Function<BasicBlock, String> targetLabel =
                t -> targetRedirects != null ? targetRedirects.getOrDefault(t, llvmLabel(t)) : llvmLabel(t);

        if (term instanceof ReturnTerminator rt) {
            Value retVal = rt.getValue();
            Type funcRetType = term.getBlock().getFunction().getReturnType();
            if (retVal != null) {
                String valRef = getLlvmValue(sb, retVal);
                valRef = castValueToType(sb, valRef, retVal.getType(), funcRetType);
                sb.append("  ret ").append(LlvmTypeMapper.toLlvmType(funcRetType))
                        .append(" ").append(valRef).append("\n");
            } else {
                if (funcRetType.isVoid()) {
                    sb.append("  ret void\n");
                } else {
                    String zero = getZeroValue(funcRetType);
                    sb.append("  ret ").append(LlvmTypeMapper.toLlvmType(funcRetType))
                            .append(" ").append(zero).append("\n");
                }
            }
        } else if (term instanceof BranchTerminator bt) {
            sb.append("  br label %").append(targetLabel.apply(bt.getTarget())).append("\n");
        } else if (term instanceof CondBranchTerminator cbt) {
            String cond = getLlvmValue(sb, cbt.getCondition());
            sb.append("  br i1 ").append(cond)
                    .append(", label %").append(targetLabel.apply(cbt.getTrueTarget()))
                    .append(", label %").append(targetLabel.apply(cbt.getFalseTarget()))
                    .append("\n");
        } else if (term instanceof ThrowTerminator tt) {
            Value exc = tt.getException();
            String excRef = exc == null ? null : getPointerOperand(sb, exc);
            String funcNameRef = stringRef(currentFunctionName);
            if (ranges.isEmpty()) {
                if (excRef != null) {
                    sb.append("  call void @__jnative_throw_exception_ctx(i8* ")
                            .append(excRef).append(", i8* ").append(funcNameRef).append(")\n");
                } else {
                    sb.append("  call void @__jnative_throw_null_pointer_exception_ctx(i8* ")
                            .append(funcNameRef).append(")\n");
                }
                sb.append("  unreachable\n");
            } else {
                emitTryGuard(sb, ranges, inner -> {
                    if (excRef != null) {
                        inner.append("  call void @__jnative_throw_exception_ctx(i8* ")
                                .append(excRef).append(", i8* ").append(funcNameRef).append(")\n");
                    } else {
                        inner.append("  call void @__jnative_throw_null_pointer_exception_ctx(i8* ")
                                .append(funcNameRef).append(")\n");
                    }
                }, true);
            }
        } else if (term instanceof LookupSwitchTerminator || term instanceof TableSwitchTerminator) {
            Value key;
            BasicBlock defaultTarget;
            if (term instanceof LookupSwitchTerminator lst) {
                key = lst.getKey();
                defaultTarget = lst.getDefaultTarget();
            } else {
                TableSwitchTerminator tst = (TableSwitchTerminator) term;
                key = tst.getKey();
                defaultTarget = tst.getDefaultTarget();
            }
            String keyRef = getLlvmValue(sb, key);
            String keyI32 = castValueToType(sb, keyRef, key.getType(), Type.INT);
            sb.append("  switch i32 ").append(keyI32).append(", label %")
                    .append(targetLabel.apply(defaultTarget)).append(" [\n");
            if (term instanceof LookupSwitchTerminator lst) {
                for (int i = 0; i < lst.getKeys().length; i++) {
                    sb.append("    i32 ").append(lst.getKeys()[i]).append(", label %")
                            .append(targetLabel.apply(lst.getTargetsArray()[i])).append("\n");
                }
            } else {
                TableSwitchTerminator tst = (TableSwitchTerminator) term;
                for (int i = 0; i < tst.getTargetsArray().length; i++) {
                    sb.append("    i32 ").append(tst.getMin() + i).append(", label %")
                            .append(targetLabel.apply(tst.getTargetsArray()[i])).append("\n");
                }
            }
            sb.append("  ]\n");
        } else if (term instanceof IndirectBranchTerminator ibt) {
            List<BasicBlock> targets = ibt.getPossibleTargets();
            if (targets.isEmpty()) {
                sb.append("  unreachable\n");
            } else {
                String blockAddr = getLlvmValue(sb, ibt.getTargetBlock());
                sb.append("  indirectbr i8* ").append(blockAddr).append(", [");
                for (int i = 0; i < targets.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append("label %").append(targetLabel.apply(targets.get(i)));
                }
                sb.append("]\n");
            }
        } else {
            sb.append("  ; unknown terminator\n");
        }
        return sb.toString();
    }

    private String mapArithOp(Opcode op, Type type) {
        boolean isFloat = type == Type.FLOAT || type == Type.DOUBLE;
        return switch (op) {
            case ADD -> isFloat ? "fadd" : "add";
            case SUB -> isFloat ? "fsub" : "sub";
            case MUL -> isFloat ? "fmul" : "mul";
            case DIV -> isFloat ? "fdiv" : "sdiv";
            case REM -> isFloat ? "frem" : "srem";
            case AND -> "and";
            case OR  -> "or";
            case XOR -> "xor";
            case SHL -> "shl";
            case SHR -> "ashr";
            case USHR -> "lshr";
            default -> "add";
        };
    }

    private String mapCmpOp(Opcode op, boolean isFloat) {
        if (isFloat) {
            return switch (op) {
                case EQ -> "oeq";
                case NE -> "one";
                case LT -> "olt";
                case LE -> "ole";
                case GT -> "ogt";
                case GE -> "oge";
                default -> "oeq";
            };
        } else {
            return switch (op) {
                case EQ -> "eq";
                case NE -> "ne";
                case LT -> "slt";
                case LE -> "sle";
                case GT -> "sgt";
                case GE -> "sge";
                default -> "eq";
            };
        }
    }

    private String getCastOp(Type src, Type dest) {
        if (src == dest) return null;
        if (!src.isPrimitive() && src.equals(dest)) return null;

        if ((src.isReference() || src.isArray() || src.isNull()) &&
                (dest.isReference() || dest.isArray() || dest.isNull())) {
            return "bitcast";
        }

        if (isIntegerType(src) && (dest.isReference() || dest.isArray() || dest.isNull() || dest.isBlock())) {
            return "inttoptr";
        }

        if ((src.isReference() || src.isArray() || src.isNull() || src.isBlock()) && isIntegerType(dest)) {
            return "ptrtoint";
        }

        if (src.isPrimitive() && dest.isPrimitive()) {
            boolean srcInt = isIntegerType(src);
            boolean destInt = isIntegerType(dest);

            if (srcInt && destInt) {
                int srcBits = getPrimitiveSize(src);
                int destBits = getPrimitiveSize(dest);
                if (srcBits == destBits) return null;
                if (srcBits < destBits) {
                    if (src == Type.CHAR || src == Type.BOOLEAN) return "zext";
                    return "sext";
                } else {
                    return "trunc";
                }
            }

            if (srcInt && (dest == Type.FLOAT || dest == Type.DOUBLE)) {
                return "sitofp";
            }

            if ((src == Type.FLOAT || src == Type.DOUBLE) && destInt) {
                return "fptosi";
            }

            if (src == Type.FLOAT && dest == Type.DOUBLE) return "fpext";
            if (src == Type.DOUBLE && dest == Type.FLOAT) return "fptrunc";
        }

        return "bitcast";
    }

    private boolean isIntegerType(Type type) {
        return type == Type.BOOLEAN || type == Type.BYTE || type == Type.SHORT ||
                type == Type.CHAR || type == Type.INT || type == Type.LONG;
    }

    private int getPrimitiveSize(Type type) {
        if (type == Type.BOOLEAN) return 1;
        if (type == Type.BYTE)    return 8;
        if (type == Type.SHORT || type == Type.CHAR) return 16;
        if (type == Type.INT)     return 32;
        if (type == Type.LONG)    return 64;
        if (type == Type.FLOAT)   return 32;
        if (type == Type.DOUBLE)  return 64;
        return 0;
    }

    private String getDefaultValue(Type type) {
        if (type == null) return "null";
        if (type.isReference() || type.isArray() || type.isNull()
                || type.isBlock() || type.isUnknown()) {
            return "null";
        }
        if (type == Type.BOOLEAN) return "false";
        if (type == Type.BYTE || type == Type.SHORT || type == Type.CHAR
                || type == Type.INT || type == Type.LONG) return "0";
        if (type == Type.FLOAT) return "0.0";
        if (type == Type.DOUBLE) return "0.0";
        if (type.isVoid()) return "void";
        return "null";
    }

    private String getLlvmValue(StringBuilder sb, Value v) {
        if (v == null) return "null";
        if (v instanceof Constant c) {
            return constantToLlvmLiteral(c);
        }

        StackAlloc ptrSlot = valuePointers.get(v);
        if (ptrSlot != null) {
            String destTy = LlvmTypeMapper.toLlvmType(v.getType());
            String tmp = newAux("ptr_cast");
            sb.append("  ").append(tmp).append(" = bitcast ")
                    .append(ptrSlot.structType()).append("* ")
                    .append(ptrSlot.allocaName())
                    .append(" to ").append(destTy).append("\n");
            return tmp;
        }

        String slot = valueSlots.get(v);
        if (slot != null) {
            String ty = LlvmTypeMapper.toLlvmType(v.getType());
            String tmp = newAux("slot_load");
            sb.append("  ").append(tmp).append(" = load ")
                    .append(ty).append(", ").append(ty)
                    .append("* ").append(slot).append("\n");
            return tmp;
        }

        String name = valueMapper.getValue(v);
        if (name == null) {
            return getDefaultValue(v.getType());
        }
        return name;
    }

    private String coerceSizeToInt(StringBuilder sb, Value v, String valRef) {
        if ("null".equals(valRef)) {
            return "0";
        }
        if (v == null) {
            return valRef;
        }
        Type t = v.getType();
        if (t == null || t == Type.INT) {
            return valRef;
        }
        if (t.isUnknown()) {
            return "0";
        }
        return castValueToType(sb, valRef, t, Type.INT);
    }

    private String constantToLlvmLiteral(Constant c) {
        Object val = c.getValue();
        if (val == null) {
            Type type = c.getType();
            if (type.isReference() || type.isArray() || type.isNull()
                    || type.isBlock() || type.isUnknown()) {
                return "null";
            }
            if (type == Type.BOOLEAN) return "false";
            if (type == Type.BYTE || type == Type.SHORT || type == Type.CHAR
                    || type == Type.INT || type == Type.LONG) return "0";
            if (type == Type.FLOAT || type == Type.DOUBLE) return "0.0";
            return "null";
        }
        Type type = c.getType();

        if (type == Type.INT) {
            return Integer.toString(((Number) val).intValue());
        }
        if (type == Type.LONG) {
            return Long.toString(((Number) val).longValue());
        }
        if (type == Type.BYTE) {
            return Integer.toString(((Number) val).byteValue());
        }
        if (type == Type.SHORT) {
            return Integer.toString(((Number) val).shortValue());
        }
        if (type == Type.CHAR) {
            int cp;
            if (val instanceof Character ch) {
                cp = ch;
            } else if (val instanceof Number n) {
                cp = n.intValue();
            } else {
                return "0";
            }
            return Integer.toString(cp);
        }

        if (type == Type.FLOAT || type == Type.DOUBLE) {
            double d = ((Number) val).doubleValue();
            if (Double.isNaN(d)) {
                return "0x7FF8000000000000";
            } else if (Double.isInfinite(d)) {
                return d > 0 ? "0x7FF0000000000000" : "0xFFF0000000000000";
            } else {
                String s = Double.toString(d).replace('E', 'e');
                if (!s.contains(".") && !s.contains("e") && !s.contains("E")) {
                    s = s + ".0";
                }
                return s;
            }
        }

        if (type == Type.BOOLEAN) {
            return ((Boolean) val).toString();
        }

        if (type.isReference() && "java/lang/Class".equals(type.getClassName())) {
            if (val instanceof String className && !className.isEmpty()) {
                return "bitcast (%ReflectionClass* @refclass_"
                        + LlvmTypeMapper.sanitizeIdentifier(className)
                        + " to i8*)";
            }
            return "null";
        }

        if (type.isReference() && "java/lang/String".equals(type.getClassName())
                && val instanceof String s) {
            return "bitcast (%struct.java_lang_String* @"
                    + LlvmRuntime.stringObjectGlobalName(s) + " to i8*)";
        }

        if (type.isReference()
                && "__llvm_vtable_ptr__".equals(type.getClassName())) {
            String className = String.valueOf(val);
            if (className.startsWith("[")) {
                return "bitcast (%ReflectionClass* @refclass_"
                        + LlvmTypeMapper.sanitizeIdentifier(className) + " to i8*)";
            }
            return "bitcast (%JNativeVTable* @vtable_"
                    + LlvmTypeMapper.sanitizeIdentifier(className) + " to i8*)";
        }

        if (type.isReference() && val instanceof String s) {
            int len = LlvmRuntime.typeStringArrayLength(s);
            return "getelementptr inbounds ([" + len + " x i8], [" + len + " x i8]* "
                    + LlvmRuntime.typeStringGlobalName(s) + ", i32 0, i32 0)";
        }

        return "null";
    }

    private String castValueToType(StringBuilder sb, String value, Type fromType, Type toType) {
        if ("null".equals(value)) {
            if (toType.isReference() || toType.isArray() || toType.isNull() || toType.isBlock()) {
                return "null";
            }
            if (toType == Type.BOOLEAN) return "false";
            if (isIntegerType(toType)) return "0";
            if (toType == Type.FLOAT || toType == Type.DOUBLE) return "0.0";
            return "null";
        }

        boolean fromIsPtr = fromType.isReference() || fromType.isArray() || fromType.isNull() || fromType.isBlock();
        String fromLlvm = LlvmTypeMapper.toLlvmType(fromType);
        if (!fromIsPtr && fromLlvm.endsWith("*")) {
            fromIsPtr = true;
        }

        if ("0".equals(value) && fromIsPtr) {
            value = "null";
        }

        if ("0".equals(value) && (toType.isReference() || toType.isArray() || toType.isNull() || toType.isBlock())) {
            return "null";
        }
        if ("null".equals(value) && isIntegerType(toType)) {
            return "0";
        }

        if (value.matches("-?\\d+\\.0") && (toType == Type.INT || toType == Type.LONG || toType == Type.BYTE ||
                toType == Type.SHORT || toType == Type.CHAR || toType == Type.BOOLEAN)) {
            return value.substring(0, value.indexOf('.'));
        }

        String toLlvm = LlvmTypeMapper.toLlvmType(toType);
        if (fromLlvm.equals(toLlvm)) {
            return value;
        }

        boolean toPtr = toLlvm.endsWith("*");
        boolean fromInt = fromLlvm.matches("i\\d+");
        boolean toInt = toLlvm.matches("i\\d+");
        boolean fromFloat = fromType == Type.FLOAT || fromType == Type.DOUBLE;
        boolean toFloat = toType == Type.FLOAT || toType == Type.DOUBLE;

        if (fromFloat && toPtr) {
            String intCast = newAux("float_to_i64");
            String fptosiOp = "fptosi";
            sb.append("  ").append(intCast).append(" = ").append(fptosiOp)
                    .append(" ").append(fromLlvm).append(" ").append(value)
                    .append(" to i64\n");
            String ptrCast = newAux("i64_to_ptr");
            sb.append("  ").append(ptrCast).append(" = inttoptr i64 ").append(intCast)
                    .append(" to ").append(toLlvm).append("\n");
            return ptrCast;
        }

        if (fromIsPtr && toFloat) {
            String intCast = newAux("ptr_to_i64");
            sb.append("  ").append(intCast).append(" = ptrtoint ").append(fromLlvm)
                    .append(" ").append(value).append(" to i64\n");
            String floatCast = newAux("i64_to_float");
            String sitofpOp = "sitofp";
            sb.append("  ").append(floatCast).append(" = ").append(sitofpOp)
                    .append(" i64 ").append(intCast)
                    .append(" to ").append(toLlvm).append("\n");
            return floatCast;
        }

        String castOp;

        if (fromIsPtr && toInt) {
            castOp = "ptrtoint";
        } else if (fromInt && toPtr) {
            castOp = "inttoptr";
        } else if (fromIsPtr && toPtr) {
            castOp = "bitcast";
        } else if (fromInt && toInt) {
            castOp = getCastOp(fromType, toType);
            if (castOp == null) {
                int fromBits = Integer.parseInt(fromLlvm.substring(1));
                int toBits = Integer.parseInt(toLlvm.substring(1));
                if (fromBits < toBits) {
                    castOp = "sext";
                } else if (fromBits > toBits) {
                    castOp = "trunc";
                } else {
                    castOp = "bitcast";
                }
            }
        } else if (fromFloat && toFloat) {
            if (fromLlvm.equals("float") && toLlvm.equals("double")) {
                castOp = "fpext";
            } else if (fromLlvm.equals("double") && toLlvm.equals("float")) {
                castOp = "fptrunc";
            } else {
                castOp = "bitcast";
            }
        } else if (fromInt && toFloat) {
            castOp = "sitofp";
        } else if (fromFloat && toInt) {
            castOp = "fptosi";
        } else {
            castOp = "bitcast";
        }

        if (castOp.equals("bitcast")) {
            if (fromIsPtr && toInt) {
                castOp = "ptrtoint";
            } else if (fromInt && toPtr) {
                castOp = "inttoptr";
            }
        }

        String castName = newAux("cast");
        sb.append("  ").append(castName).append(" = ").append(castOp)
                .append(" ").append(fromLlvm)
                .append(" ").append(value)
                .append(" to ").append(toLlvm).append("\n");
        return castName;
    }

    private void ensureFunctionDeclared(String owner, String methodName, String descriptor) {
        String mangled = LlvmRuntime.mangleMethod(owner, methodName, descriptor);
        if (module.getFunction(mangled) != null) {
            return;
        }

        Type retType = TypeResolver.descToReturnType(descriptor);
        List<Type> paramTypes = TypeResolver.descToParamTypes(descriptor);

        boolean isStatic = false;
        {
            String[] foundOwner = new String[1];
            MethodNode target = resolver.findMethodInHierarchy(
                    owner, methodName, descriptor, foundOwner);
            if (target != null) {
                isStatic = target.isStatic();
            }
        }

        List<Type> allParams = new ArrayList<>();
        if (!isStatic) {
            allParams.add(Type.reference(owner));
        }
        allParams.addAll(paramTypes);

        Function func = new Function(mangled, retType);
        for (int i = 0; i < allParams.size(); i++) {
            func.addParameter(new Parameter(allParams.get(i), i));
        }
        module.addFunction(func);
    }

    private String asPointer(StringBuilder sb, Value v) {
        String valRef = getLlvmValue(sb, v);
        Type type = v.getType();
        if (type.isReference() || type.isArray() || type.isNull() || type.isBlock()) {
            String ptrTy = LlvmTypeMapper.toLlvmType(type);
            if (!ptrTy.equals("i8*")) {
                String castName = newAux("ptrcast");
                sb.append("  ").append(castName).append(" = bitcast ").append(ptrTy)
                        .append(" ").append(valRef).append(" to i8*\n");
                return castName;
            }
            return valRef;
        } else if (isIntegerType(type)) {
            String intVal = valRef;
            if (type == Type.INT || type == Type.SHORT || type == Type.BYTE || type == Type.CHAR || type == Type.BOOLEAN) {
                String ext = newAux("sext");
                sb.append("  ").append(ext).append(" = sext ").append(LlvmTypeMapper.toLlvmType(type))
                        .append(" ").append(valRef).append(" to i64\n");
                intVal = ext;
            }

            String ptrCast = newAux("inttoptr");
            sb.append("  ").append(ptrCast).append(" = inttoptr i64 ").append(intVal).append(" to i8*\n");
            return ptrCast;
        } else {
            String ptrCast = newAux("ptrcast");
            sb.append("  ").append(ptrCast).append(" = bitcast ").append(LlvmTypeMapper.toLlvmType(type))
                    .append(" ").append(valRef).append(" to i8*\n");
            return ptrCast;
        }
    }

    private void emitBoundsCheck(StringBuilder sb, String arrPtr, String idxI32, List<TryCatchRange> ranges) {
        String lenPtr = newAux("lenptr");
        String len = newAux("len");
        sb.append("  ").append(lenPtr)
                .append(" = getelementptr inbounds i8, i8* ").append(arrPtr)
                .append(", i64 8\n");
        String lenPtrI32 = newAux("lenptr_i32");
        sb.append("  ").append(lenPtrI32).append(" = bitcast i8* ").append(lenPtr)
                .append(" to i32*\n");
        sb.append("  ").append(len).append(" = load i32, i32* ")
                .append(lenPtrI32).append("\n");
        String chk1 = newAux("bnd_chk1");
        String chk2 = newAux("bnd_chk2");
        String ok = newAux("bnd_ok");
        String throwBlk = newLabel("throw_aioobe");
        String cont = newLabel("bnd_ok");
        sb.append("  ").append(chk1).append(" = icmp sge i32 ").append(idxI32).append(", 0\n");
        sb.append("  ").append(chk2).append(" = icmp slt i32 ").append(idxI32).append(", ").append(len).append("\n");
        sb.append("  ").append(ok).append(" = and i1 ").append(chk1).append(", ").append(chk2).append("\n");
        sb.append("  br i1 ").append(ok)
                .append(", label %").append(cont)
                .append(", label %").append(throwBlk).append("\n");
        sb.append(throwBlk).append(":\n");
        emitThrowHelper(sb, "@__jnative_throw_array_index_out_of_bounds", ranges);
        sb.append(cont).append(":\n");
    }

    /**
     * Checks whether a symbol is guaranteed to have a definition at link
     * time.
     * <p>
     * The previous revision returned {@code true} for every
     * {@code __jnative_fn_*} symbol without verifying that the owning
     * class has a JNative C file. When the analysis runs on Linux but
     * the target is Windows, the closure pulls in {@code sun.nio.ch.EPoll},
     * {@code FileDispatcherImpl}, {@code IOUtil} and {@code Net} — none
     * of which has a {@code jnative/**} C source — and the fabricated
     * symbols then fail to link. With the check below, such symbols are
     * classified as undefined and the emitter falls through to
     * {@code __jnative_unresolved_slot} (which is always defined).
     */
    private boolean isCallableSymbolDefined(String symbol) {
        if (symbol == null || symbol.isEmpty()) return false;

        if (symbol.startsWith("__jnative_override_")) {
            return true;
        }

        if (symbol.startsWith("__jnative_fn_")) {
            return isJNativeSymbolBackedByCFile(symbol);
        }

        if (symbol.startsWith("llvm.")) return true;

        if (DECLARED_RUNTIME_HELPERS.contains(symbol)) return true;

        return switch (symbol) {
            case "malloc", "free", "printf", "abort", "atexit",
                 "memcpy", "memmove", "memset", "_setjmp", "longjmp" ->
                    true;
            default -> {
                Function f = module.getFunction(symbol);
                if (f != null && f.isJniNative()) yield true;
                yield f != null && f.getEntryBlock() != null;
            }
        };
    }

    private boolean isJNativeSymbolBackedByCFile(String symbol) {
        for (String cls : resolver.getClassMap().keySet()) {
            String mangledClass = LlvmTypeMapper.sanitizeIdentifier(cls);
            String prefix = "__jnative_fn_" + mangledClass + "_";
            if (symbol.startsWith(prefix)) {
                return hasJNativeCFilesForOwner(cls);
            }
        }
        return false;
    }

    private static boolean hasJNativeCFilesForOwner(String internalName) {
        if (internalName == null || internalName.isEmpty()) return false;
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        String candidate = internalName;
        while (candidate != null && !candidate.isEmpty()) {
            String base = candidate.startsWith("java/")
                    ? "jnative/" + candidate.substring("java/".length())
                    : "jnative/" + candidate;
            for (String suffix : new String[]{".c", "Support.c"}) {
                try (InputStream is = cl.getResourceAsStream(base + suffix)) {
                    if (is != null) return true;
                } catch (IOException ignored) { }
            }
            int dollar = candidate.lastIndexOf('$');
            if (dollar < 0) break;
            candidate = candidate.substring(0, dollar);
        }
        return false;
    }

    private Function lookupNativeFunction(String owner, String methodName,
                                          String descriptor) {
        String jnative = "__jnative_"
                + LlvmRuntime.mangleMethod(owner, methodName, descriptor);
        Function f = module.getFunction(jnative);
        if (f != null) {
            return f;
        }
        String jni = LlvmRuntime.toJniSymbolName(owner, methodName, descriptor,
                countOverloads(owner, methodName));
        return module.getFunction(jni);
    }

    private int countOverloads(String owner, String methodName) {
        io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode cn =
                resolver.getClassNode(owner);
        if (cn == null) {
            return 1;
        }
        int n = 0;
        for (MethodNode mn : cn.getMethods()) {
            if (mn.getName().equals(methodName)) {
                n++;
            }
        }
        return Math.max(n, 1);
    }

    private boolean areAllInstanceFieldsFinal(String className) {
        ClassNode cls = resolver.getClassNode(className);
        if (cls == null || cls.isExternal()) return false;

        ClassNode cur = cls;
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (cur != null && !cur.isExternal() && seen.add(cur.getName())) {
            for (FieldNode f : cur.getFields()) {
                if ((f.getAccess() & org.objectweb.asm.Opcodes.ACC_STATIC) != 0) continue;
                if ((f.getAccess() & org.objectweb.asm.Opcodes.ACC_FINAL) == 0) return false;
            }
            String sup = cur.getSuperName();
            if (sup == null || sup.equals(cur.getName()) || "java/lang/Object".equals(sup)) break;
            cur = resolver.getClassNode(sup);
        }
        return true;
    }

    private String emitBaseToI8Pointer(StringBuilder sb, Value base) {
        Type t = base.getType();
        String baseRef  = getLlvmValue(sb, base);
        String baseLlvm = LlvmTypeMapper.toLlvmType(t);

        if ("i8*".equals(baseLlvm)) {
            return baseRef;
        }

        if (t.isReference() || t.isArray() || t.isNull() || t.isBlock()
                || baseLlvm.endsWith("*")) {
            String cast = newAux("base_i8");
            sb.append("  ").append(cast).append(" = bitcast ")
                    .append(baseLlvm).append(" ").append(baseRef)
                    .append(" to i8*\n");
            return cast;
        }

        if (t == Type.FLOAT) {
            String i32Reg = newAux("base_i32");
            sb.append("  ").append(i32Reg).append(" = bitcast float ")
                    .append(baseRef).append(" to i32\n");
            String i64Reg = newAux("base_i64");
            sb.append("  ").append(i64Reg).append(" = zext i32 ")
                    .append(i32Reg).append(" to i64\n");
            String ptrReg = newAux("base_i8");
            sb.append("  ").append(ptrReg).append(" = inttoptr i64 ")
                    .append(i64Reg).append(" to i8*\n");
            return ptrReg;
        }

        if (t == Type.DOUBLE) {
            String i64Reg = newAux("base_i64");
            sb.append("  ").append(i64Reg).append(" = bitcast double ")
                    .append(baseRef).append(" to i64\n");
            String ptrReg = newAux("base_i8");
            sb.append("  ").append(ptrReg).append(" = inttoptr i64 ")
                    .append(i64Reg).append(" to i8*\n");
            return ptrReg;
        }

        if (isIntegerType(t)) {
            String i64Reg;
            if (t == Type.LONG) {
                i64Reg = baseRef;
            } else {
                i64Reg = newAux("base_i64");
                String op = (t == Type.BOOLEAN) ? "zext" : "sext";
                sb.append("  ").append(i64Reg).append(" = ").append(op).append(" ")
                        .append(baseLlvm).append(" ").append(baseRef)
                        .append(" to i64\n");
            }
            String ptrReg = newAux("base_i8");
            sb.append("  ").append(ptrReg).append(" = inttoptr i64 ")
                    .append(i64Reg).append(" to i8*\n");
            return ptrReg;
        }

        throw new IllegalStateException(
                "Cannot convert value of type '" + t + "' to i8* for raw-offset field access. "
                        + "Value = " + base + ". This indicates a bug in an earlier IR pass.");
    }

    private String getPointerOperand(StringBuilder sb, Value v) {
        String val = getLlvmValue(sb, v);
        if ("0".equals(val)) {
            return "null";
        }
        return val;
    }

    private boolean isVirtualCallDirectNativeSafe(String owner, String methodName, String methodDesc) {
        String ownerInternal = owner.replace('.', '/');
        String[] foundOwner = new String[1];
        io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode mn =
                resolver.findMethodInHierarchy(ownerInternal, methodName, methodDesc, foundOwner);
        if (mn == null) return false;

        if (Modifier.isPrivate(mn.getAccess())) return true;

        String declaringClass = foundOwner[0] != null ? foundOwner[0] : ownerInternal;
        io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode declaringCn =
                resolver.getClassNode(declaringClass);
        if (declaringCn != null && Modifier.isFinal(declaringCn.getAccess())) return true;

        if (Modifier.isFinal(mn.getAccess())) return true;

        for (String subclass : resolver.getSubclasses(declaringClass)) {
            if (subclass.equals(declaringClass)) continue;
            io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode override =
                    resolver.getMethodNode(subclass, methodName, methodDesc);
            if (override != null && !override.isStatic() && !override.isAbstract()) {
                return false;
            }
        }
        return true;
    }
}