package io.github.kubyk01.application.service.analyzer.ssa;

import io.github.kubyk01.domain.analyzer.ir.BasicBlock;
import io.github.kubyk01.domain.analyzer.ir.Function;

import java.util.*;

/**
 * Fixed version of DominatorTree that takes exceptional edges into account.
 * DFS now visits all successors, including exceptional ones.
 */
public class DominatorTree {
    private final Map<BasicBlock, BasicBlock> idom = new HashMap<>();
    private final Map<BasicBlock, List<BasicBlock>> children = new HashMap<>();
    private final Map<BasicBlock, Set<BasicBlock>> dominanceFrontiers = new HashMap<>();

    public DominatorTree(Function function) {
        compute(function);
    }

    private void compute(Function function) {
        BasicBlock entry = function.getEntryBlock();
        if (entry == null) return;

        List<BasicBlock> vertex = new ArrayList<>();
        Map<BasicBlock, Integer> dfsNum = new HashMap<>();
        Map<BasicBlock, BasicBlock> parent = new HashMap<>();

        // Iterative DFS visiting ALL successors (including exceptional)
        Deque<BasicBlock> stack = new ArrayDeque<>();
        stack.push(entry);
        while (!stack.isEmpty()) {
            BasicBlock w = stack.pop();
            if (dfsNum.containsKey(w)) continue;
            dfsNum.put(w, vertex.size());
            vertex.add(w);
            // Use getSuccessors() instead of getNormalSuccessors()
            List<BasicBlock> succs = w.getSuccessors();
            // Reverse order to preserve determinism
            for (int i = succs.size() - 1; i >= 0; i--) {
                BasicBlock s = succs.get(i);
                if (!dfsNum.containsKey(s)) {
                    parent.put(s, w);
                    stack.push(s);
                }
            }
        }

        int n = vertex.size();
        if (n == 0) return;

        idom.put(entry, null);
        if (n == 1) {
            buildChildren();
            computeDominanceFrontiers(vertex);
            return;
        }

        int[] semi = new int[n];
        for (int i = 0; i < n; i++) semi[i] = i;

        int[] ancestor = new int[n];
        Arrays.fill(ancestor, -1);

        int[] label = new int[n];
        for (int i = 0; i < n; i++) label[i] = i;

        @SuppressWarnings("unchecked")
        List<Integer>[] bucket = new ArrayList[n];
        for (int i = 0; i < n; i++) bucket[i] = new ArrayList<>();

        for (int i = n - 1; i >= 1; i--) {
            BasicBlock w = vertex.get(i);

            for (BasicBlock pred : w.getPredecessors()) {
                Integer pi = dfsNum.get(pred);
                if (pi == null) continue;
                int u = eval(pi, semi, ancestor, label);
                if (semi[u] < semi[i]) {
                    semi[i] = semi[u];
                }
            }

            bucket[semi[i]].add(i);

            BasicBlock pw = parent.get(w);
            if (pw != null) {
                int pi = dfsNum.get(pw);
                ancestor[i] = pi;
            }

            if (pw != null) {
                int pi = dfsNum.get(pw);
                for (int v : bucket[pi]) {
                    int u = eval(v, semi, ancestor, label);
                    if (semi[u] < semi[v]) {
                        idom.put(vertex.get(v), vertex.get(u));
                    } else {
                        idom.put(vertex.get(v), pw);
                    }
                }
                bucket[pi].clear();
            }
        }

        for (int i = 1; i < n; i++) {
            BasicBlock v = vertex.get(i);
            BasicBlock idomV = idom.get(v);
            if (idomV != null && idomV != vertex.get(semi[i])) {
                BasicBlock idomIdomV = idom.get(idomV);
                if (idomIdomV != null) {
                    idom.put(v, idomIdomV);
                }
            }
        }

        buildChildren();
        computeDominanceFrontiers(vertex);
    }

    private int eval(int v, int[] semi, int[] ancestor, int[] label) {
        if (ancestor[v] == -1) {
            return v;
        }
        compress(v, semi, ancestor, label);
        return label[v];
    }

    private void compress(int v, int[] semi, int[] ancestor, int[] label) {
        int a = ancestor[v];
        if (ancestor[a] != -1) {
            compress(a, semi, ancestor, label);
            if (semi[label[a]] < semi[label[v]]) {
                label[v] = label[a];
            }
            ancestor[v] = ancestor[a];
        }
    }

    private void buildChildren() {
        for (Map.Entry<BasicBlock, BasicBlock> entry : idom.entrySet()) {
            BasicBlock child = entry.getKey();
            BasicBlock par = entry.getValue();
            if (par != null) {
                children.computeIfAbsent(par, k -> new ArrayList<>()).add(child);
            }
        }
    }

    private void computeDominanceFrontiers(List<BasicBlock> vertex) {
        for (BasicBlock block : vertex) {
            dominanceFrontiers.put(block, new LinkedHashSet<>());
        }
        for (BasicBlock block : vertex) {
            if (block.getPredecessors().size() >= 2) {
                for (BasicBlock pred : block.getPredecessors()) {
                    BasicBlock runner = pred;
                    BasicBlock idomBlock = idom.get(block);
                    while (runner != null && runner != idomBlock) {
                        dominanceFrontiers.get(runner).add(block);
                        runner = idom.get(runner);
                    }
                }
            }
        }
    }

    public List<BasicBlock> getChildren(BasicBlock block) {
        return children.getOrDefault(block, Collections.emptyList());
    }

    public Set<BasicBlock> getDominanceFrontier(BasicBlock block) {
        return dominanceFrontiers.getOrDefault(block, Collections.emptySet());
    }

    public boolean dominates(BasicBlock a, BasicBlock b) {
        BasicBlock finger = b;
        while (finger != null) {
            if (finger == a) return true;
            finger = idom.get(finger);
        }
        return false;
    }

    public BasicBlock getLCA(BasicBlock a, BasicBlock b) {
        Set<BasicBlock> ancestors = new HashSet<>();
        BasicBlock node = a;
        while (node != null) {
            ancestors.add(node);
            node = idom.get(node);
        }
        node = b;
        while (node != null) {
            if (ancestors.contains(node)) {
                return node;
            }
            node = idom.get(node);
        }
        return null;
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
