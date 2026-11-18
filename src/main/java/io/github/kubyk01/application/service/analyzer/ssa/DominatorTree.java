package io.github.kubyk01.application.service.analyzer.ssa;

import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Terminator;

import java.util.AbstractList;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

public class DominatorTree {

    private static final class Scratch {
        int[] semi = new int[64];
        int[] ancestor = new int[64];
        int[] label = new int[64];
        int[] parent = new int[64];
        int[] bucketHead = new int[64];
        int[] bucketNext = new int[64];
        int[] mark = new int[64];
        int[] pairs = new int[128];
        BasicBlock[] stack = new BasicBlock[64];
        BasicBlock[] stackParent = new BasicBlock[64];

        void ensure(int n) {
            if (semi.length >= n) return;
            int c = Integer.highestOneBit(n - 1) << 1;
            semi = Arrays.copyOf(semi, c);
            ancestor = Arrays.copyOf(ancestor, c);
            label = Arrays.copyOf(label, c);
            parent = Arrays.copyOf(parent, c);
            bucketHead = Arrays.copyOf(bucketHead, c);
            bucketNext = Arrays.copyOf(bucketNext, c);
            mark = Arrays.copyOf(mark, c);
        }
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private final BlockIndexMap index = new BlockIndexMap();
    private BasicBlock[] vertex = new BasicBlock[16];
    private int[] idom = new int[16];
    private int[] childStart = new int[17];
    private int[] childList = new int[16];
    private int[] frontierStart = new int[17];
    private int[] frontierList = new int[16];
    private int n;

    public DominatorTree() {
    }

    public DominatorTree(Function function) {
        reset(function);
    }

    public void reset(Function function) {
        index.clear();
        n = 0;
        BasicBlock entry = function.getEntryBlock();
        if (entry == null) {
            childStart[0] = 0;
            frontierStart[0] = 0;
            return;
        }
        pruneUnreachableEdges(function);
        Scratch s = SCRATCH.get();
        numberBlocks(entry, s);
        if (n == 0) return;
        computeIdoms(s);
        buildChildren();
        buildFrontiers(s);
    }

    private static void pruneUnreachableEdges(Function function) {
        List<BasicBlock> blocks = function.getBlocks();
        for (BasicBlock block : blocks) {
            List<BasicBlock> preds = block.getPredecessors();
            for (int i = preds.size() - 1; i >= 0; i--) {
                if (reaches(preds.get(i), block)) continue;
                preds.remove(i);
                List<Instruction> insts = block.getInstructions();
                for (Instruction inst : insts) {
                    if (inst.getOpcode() == Opcode.PHI && i < inst.getOperands().size()) {
                        inst.getOperands().remove(i);
                    }
                }
            }
        }
        for (BasicBlock block : blocks) {
            List<BasicBlock> succs = block.getSuccessors();
            for (int i = succs.size() - 1; i >= 0; i--) {
                if (i >= succs.size()) continue;
                BasicBlock succ = succs.get(i);
                if (!reaches(block, succ)) {
                    block.removeSuccessor(succ);
                    succs = block.getSuccessors();
                }
            }
        }
    }

    private static boolean reaches(BasicBlock pred, BasicBlock block) {
        Terminator t = pred.getTerminator();
        if (t != null) {
            for (BasicBlock tt : t.getTargets()) {
                if (tt == block) return true;
            }
        }
        return pred.getExceptionalSuccessors().contains(block);
    }

    private void numberBlocks(BasicBlock entry, Scratch s) {
        BasicBlock[] stack = s.stack;
        BasicBlock[] stackParent = s.stackParent;
        int top = 0;
        stack[top] = entry;
        stackParent[top++] = null;
        while (top > 0) {
            BasicBlock w = stack[--top];
            BasicBlock from = stackParent[top];
            if (index.get(w) >= 0) continue;
            int num = n;
            if (num == vertex.length) {
                vertex = Arrays.copyOf(vertex, num << 1);
                idom = Arrays.copyOf(idom, num << 1);
            }
            s.ensure(num + 1);
            vertex[num] = w;
            index.put(w, num);
            s.parent[num] = from == null ? -1 : index.get(from);
            n = num + 1;
            List<BasicBlock> succs = w.getSuccessors();
            for (int i = succs.size() - 1; i >= 0; i--) {
                BasicBlock succ = succs.get(i);
                if (index.get(succ) >= 0) continue;
                if (top == stack.length) {
                    stack = Arrays.copyOf(stack, top << 1);
                    stackParent = Arrays.copyOf(stackParent, top << 1);
                    s.stack = stack;
                    s.stackParent = stackParent;
                }
                stack[top] = succ;
                stackParent[top++] = w;
            }
        }
    }

    private void computeIdoms(Scratch s) {
        int[] semi = s.semi;
        int[] ancestor = s.ancestor;
        int[] label = s.label;
        int[] parent = s.parent;
        int[] bucketHead = s.bucketHead;
        int[] bucketNext = s.bucketNext;
        for (int i = 0; i < n; i++) {
            semi[i] = i;
            ancestor[i] = -1;
            label[i] = i;
            bucketHead[i] = -1;
            idom[i] = -1;
        }
        for (int i = n - 1; i >= 1; i--) {
            List<BasicBlock> preds = vertex[i].getPredecessors();
            for (BasicBlock pred : preds) {
                int pi = index.get(pred);
                if (pi < 0) continue;
                int u = eval(pi, semi, ancestor, label);
                if (semi[u] < semi[i]) semi[i] = semi[u];
            }
            bucketNext[i] = bucketHead[semi[i]];
            bucketHead[semi[i]] = i;

            int pw = parent[i];
            if (pw >= 0) {
                ancestor[i] = pw;
                for (int v = bucketHead[pw]; v >= 0; v = bucketNext[v]) {
                    int u = eval(v, semi, ancestor, label);
                    idom[v] = semi[u] < semi[v] ? u : pw;
                }
                bucketHead[pw] = -1;
            }
        }
        for (int i = 1; i < n; i++) {
            int d = idom[i];
            if (d >= 0 && d != semi[i]) {
                int dd = idom[d];
                if (dd >= 0) idom[i] = dd;
            }
        }
        idom[0] = -1;
    }

    private static int eval(int v, int[] semi, int[] ancestor, int[] label) {
        if (ancestor[v] == -1) return v;
        compress(v, semi, ancestor, label);
        return label[v];
    }

    private static void compress(int v, int[] semi, int[] ancestor, int[] label) {
        int a = ancestor[v];
        if (ancestor[a] != -1) {
            compress(a, semi, ancestor, label);
            if (semi[label[a]] < semi[label[v]]) label[v] = label[a];
            ancestor[v] = ancestor[a];
        }
    }

    private void buildChildren() {
        if (childStart.length < n + 1) childStart = new int[n + 1];
        if (childList.length < n) childList = new int[n];
        Arrays.fill(childStart, 0, n + 1, 0);
        for (int i = 0; i < n; i++) {
            if (idom[i] >= 0) childStart[idom[i] + 1]++;
        }
        for (int i = 0; i < n; i++) childStart[i + 1] += childStart[i];
        int[] fill = SCRATCH.get().mark;
        System.arraycopy(childStart, 0, fill, 0, n);
        for (int i = 0; i < n; i++) {
            int d = idom[i];
            if (d >= 0) childList[fill[d]++] = i;
        }
    }

    private void buildFrontiers(Scratch s) {
        int[] pairs = s.pairs;
        int count = 0;
        int[] mark = s.mark;
        Arrays.fill(mark, 0, n, -1);
        for (int b = 0; b < n; b++) {
            List<BasicBlock> preds = vertex[b].getPredecessors();
            if (preds.size() < 2) continue;
            int stop = idom[b];
            for (BasicBlock pred : preds) {
                int runner = index.get(pred);
                if (runner < 0) continue;
                while (runner >= 0 && runner != stop) {
                    if (mark[runner] != b) {
                        mark[runner] = b;
                        if (count + 2 > pairs.length) {
                            pairs = Arrays.copyOf(pairs, pairs.length << 1);
                            s.pairs = pairs;
                        }
                        pairs[count++] = runner;
                        pairs[count++] = b;
                    }
                    runner = idom[runner];
                }
            }
        }
        int edges = count >>> 1;
        if (frontierStart.length < n + 1) frontierStart = new int[n + 1];
        if (frontierList.length < edges) frontierList = new int[Math.max(edges, frontierList.length << 1)];
        Arrays.fill(frontierStart, 0, n + 1, 0);
        for (int k = 0; k < count; k += 2) frontierStart[pairs[k] + 1]++;
        for (int i = 0; i < n; i++) frontierStart[i + 1] += frontierStart[i];
        System.arraycopy(frontierStart, 0, mark, 0, n);
        for (int k = 0; k < count; k += 2) frontierList[mark[pairs[k]]++] = pairs[k + 1];
    }

    public int size() {
        return n;
    }

    public int indexOf(BasicBlock block) {
        return index.get(block);
    }

    public BasicBlock blockAt(int i) {
        return vertex[i];
    }

    public int childCount(int i) {
        return childStart[i + 1] - childStart[i];
    }

    public int childAt(int i, int k) {
        return childList[childStart[i] + k];
    }

    public int frontierCount(int i) {
        return frontierStart[i + 1] - frontierStart[i];
    }

    public int frontierAt(int i, int k) {
        return frontierList[frontierStart[i] + k];
    }

    public List<BasicBlock> getChildren(BasicBlock block) {
        int i = index.get(block);
        if (i < 0 || childCount(i) == 0) return Collections.emptyList();
        int from = childStart[i];
        int size = childCount(i);
        return new AbstractList<>() {
            @Override
            public BasicBlock get(int k) {
                return vertex[childList[from + k]];
            }

            @Override
            public int size() {
                return size;
            }
        };
    }

    public Set<BasicBlock> getDominanceFrontier(BasicBlock block) {
        int i = index.get(block);
        if (i < 0 || frontierCount(i) == 0) return Collections.emptySet();
        int from = frontierStart[i];
        int size = frontierCount(i);
        return new AbstractSet<>() {
            @Override
            public Iterator<BasicBlock> iterator() {
                return new Iterator<>() {
                    private int k;

                    @Override
                    public boolean hasNext() {
                        return k < size;
                    }

                    @Override
                    public BasicBlock next() {
                        if (k >= size) throw new NoSuchElementException();
                        return vertex[frontierList[from + k++]];
                    }
                };
            }

            @Override
            public int size() {
                return size;
            }
        };
    }

    public boolean dominates(BasicBlock a, BasicBlock b) {
        if (a == b) return true;
        int ia = index.get(a);
        int ib = index.get(b);
        if (ib < 0) return false;
        if (ia < 0) return false;
        for (int f = ib; f >= 0; f = idom[f]) {
            if (f == ia) return true;
        }
        return false;
    }

    public BasicBlock getLCA(BasicBlock a, BasicBlock b) {
        if (a == b) return a;
        int ia = index.get(a);
        int ib = index.get(b);
        if (ia < 0 || ib < 0) return null;
        Scratch s = SCRATCH.get();
        s.ensure(n);
        int[] mark = s.mark;
        for (int f = ia; f >= 0; f = idom[f]) mark[f] = -2;
        BasicBlock found = null;
        for (int f = ib; f >= 0; f = idom[f]) {
            if (mark[f] == -2) {
                found = vertex[f];
                break;
            }
        }
        for (int f = ia; f >= 0; f = idom[f]) mark[f] = 0;
        return found;
    }

    public BasicBlock getLCA(List<BasicBlock> blocks) {
        if (blocks.isEmpty()) return null;
        BasicBlock lca = blocks.getFirst();
        for (int i = 1; i < blocks.size(); i++) {
            lca = getLCA(lca, blocks.get(i));
            if (lca == null) break;
        }
        return lca;
    }
}
