package io.github.kubyk01.domain.analyzer.escapeanalysis.strategies;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult.CallShape;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeSummary;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.CondBranchTerminator;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.InvokeDynamicInfo;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.ResolvedCall;
import io.github.kubyk01.domain.ir.ReturnTerminator;
import io.github.kubyk01.domain.ir.Temporary;
import io.github.kubyk01.domain.ir.Terminator;
import io.github.kubyk01.domain.ir.ThrowTerminator;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.UndefinedValue;
import io.github.kubyk01.domain.ir.Value;
import io.github.kubyk01.util.LlvmUtil;
import io.github.kubyk01.util.ScratchIdentityMap;
import org.objectweb.asm.Opcodes;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static io.github.kubyk01.util.LlvmUtil.canonicalCalleeKey;
import static io.github.kubyk01.util.LlvmUtil.extractCalleeName;
import static io.github.kubyk01.util.LlvmUtil.extractFieldName;

public final class CallShapeEscapeStrategy implements EscapeAnalysisStrategies {

    private static final int[] NONE = new int[0];
    private static final Target UNKNOWN = new Target(NONE, true);
    private static final Target LAMBDA_DISPATCH = new Target(NONE, true);
    private static final Target PURE_NATIVE = new Target(NONE, false);
    private static final Target FRESH_NATIVE = new Target(NONE, false);
    private static final Set<String> FRESH_NATIVES = Set.of(
        "java/lang/reflect/Array.newArray(Ljava/lang/Class;I)Ljava/lang/Object;",
        "java/lang/reflect/Array.multiNewArray(Ljava/lang/Class;[I)Ljava/lang/Object;",
        "java/lang/Object.clone()Ljava/lang/Object;");
    private static final Set<String> PURE_NATIVES = Set.of(
        "java/lang/System.arraycopy(Ljava/lang/Object;ILjava/lang/Object;II)V",
        "java/lang/Object.hashCode()I",
        "java/lang/Object.getClass()Ljava/lang/Class;",
        "java/lang/Class.isArray()Z",
        "java/lang/Class.isPrimitive()Z",
        "java/lang/Class.isInterface()Z",
        "java/lang/Class.isHidden()Z",
        "java/lang/Class.getModifiers()I",
        "java/lang/Class.isInstance(Ljava/lang/Object;)Z",
        "java/lang/Class.isAssignableFrom(Ljava/lang/Class;)Z",
        "java/lang/System.identityHashCode(Ljava/lang/Object;)I");

    private record Target(int[] functions, boolean unknown) {}

    private record Site(Instruction inst, Target target, boolean exact) {}

    private static final long OTHER = Long.MIN_VALUE;
    private static final long[] ZERO4 = new long[5];
    private static final long[] OTHER4 = {0, 0, 0, OTHER, 0};
    private static final int FULL = 1;
    private static final int DEEP = 2;
    private static final int READ = 4;
    private static final int CONTEXT_BUDGET = 50_000;
    private static final int CONTEXT_DEPTH = 12;
    private static final int CONTEXT_ROUNDS = 8;
    private static final Object[] NO_CONSTS = new Object[0];
    private static final int ANYF = -1;
    private static final int E1F = 0;
    private static final int RD1F = 1;
    private static final int INTO1F = 2;
    private static final Map<String, Integer> FIELD_IDS = new ConcurrentHashMap<>();
    private static final Map<Integer, String> FIELD_NAMES = new ConcurrentHashMap<>();
    private static final AtomicInteger FIELD_COUNT = new AtomicInteger();

    private static int fieldId(String key) {
        Integer id = FIELD_IDS.get(key);
        if (id != null) return id;
        return FIELD_IDS.computeIfAbsent(key, x -> {
            int n = FIELD_COUNT.getAndIncrement();
            FIELD_NAMES.put(n, x);
            return n;
        });
    }

    private static int fieldOf(Instruction inst) {
        return switch (inst.getOpcode()) {
            case ALOAD, ASTORE -> fieldId("[]");
            case GET_FIELD, PUT_FIELD -> {
                String raw = extractFieldName(inst);
                yield raw == null ? ANYF : fieldId(raw);
            }
            default -> ANYF;
        };
    }

    private static long fact(int kind, int j, int k, int f) {
        return ((long) f << 16) | ((long) Math.min(k, 63) << 9) | ((long) Math.min(j, 63) << 2) | kind;
    }

    private static int factKind(long x) {
        return (int) (x & 3);
    }

    private static int factJ(long x) {
        return (int) ((x >>> 2) & 127);
    }

    private static int factK(long x) {
        return (int) ((x >>> 9) & 127);
    }

    private static int factF(long x) {
        return (int) (x >>> 16);
    }

    private static long tagOf(int field) {
        return field >= 0 ? field + 1L : -1L;
    }

    private static long joinTag(long a, long b) {
        if (a == 0) return b;
        if (b == 0 || a == b) return a;
        return -1L;
    }

    private static final class Sum {
        long retained;
        long returned;
        long e1;
        long e2;
        long rd1;
        long rd2;
        long[] into;
        long[] facts;

        long into(int j, int depth) {
            int k = 2 * Math.min(j, 63) + depth;
            return into == null || k >= into.length ? 0 : into[k];
        }

        void addInto(int q, int depth, long mask) {
            if (mask == 0) return;
            int k = 2 * Math.min(q, 63) + depth;
            if (into == null) into = new long[k + 2];
            else if (k >= into.length) into = Arrays.copyOf(into, k + 2);
            into[k] |= mask;
        }

        void orInto(long[] other) {
            if (other == null) return;
            for (int k = 0; k < other.length; k++) {
                if (other[k] != 0) addInto(k >> 1, k & 1, other[k]);
            }
        }

        void addFact(long x) {
            if (facts == null) {
                facts = new long[]{x};
                return;
            }
            int p = Arrays.binarySearch(facts, x);
            if (p >= 0) return;
            p = -p - 1;
            long[] n = new long[facts.length + 1];
            System.arraycopy(facts, 0, n, 0, p);
            n[p] = x;
            System.arraycopy(facts, p, n, p + 1, facts.length - p);
            facts = n;
        }

        void orFacts(long[] other) {
            if (other == null || other.length == 0) return;
            if (facts == null) {
                facts = other;
                return;
            }
            long[] a = facts;
            long[] n = new long[a.length + other.length];
            int i = 0, j = 0, k = 0;
            while (i < a.length && j < other.length) {
                long x = a[i], y = other[j];
                if (x < y) n[k++] = a[i++];
                else if (y < x) n[k++] = other[j++];
                else { n[k++] = x; i++; j++; }
            }
            while (i < a.length) n[k++] = a[i++];
            while (j < other.length) n[k++] = other[j++];
            if (k == a.length) return;
            facts = k == other.length ? other : k == n.length ? n : Arrays.copyOf(n, k);
        }

        boolean hasFact(long x) {
            return facts != null && Arrays.binarySearch(facts, x) >= 0;
        }

        boolean covers(long x) {
            if (hasFact(x)) return true;
            return switch (factKind(x)) {
                case E1F -> CallShape.has(e1, factJ(x));
                case RD1F -> CallShape.has(rd1, factJ(x));
                default -> CallShape.has(into(factJ(x), 0), factK(x));
            };
        }

        long stored() {
            long s = storedOf(into);
            if (facts != null) for (long x : facts) if (factKind(x) == INTO1F) s |= 1L << factJ(x);
            return s;
        }

        boolean within(Sum o) {
            if ((retained & ~o.retained) != 0 || (returned & ~o.returned) != 0 || (e1 & ~o.e1) != 0
                || (e2 & ~o.e2) != 0 || (rd1 & ~o.rd1) != 0 || (rd2 & ~o.rd2) != 0) return false;
            if (facts != null) for (long x : facts) if (!o.covers(x)) return false;
            if (into == null) return true;
            for (int k = 0; k < into.length; k++) {
                if ((into[k] & ~o.into(k >> 1, k & 1)) != 0) return false;
            }
            return true;
        }

        Sum union(Sum o) {
            Sum s = new Sum();
            s.retained = retained | o.retained;
            s.returned = returned | o.returned;
            s.e1 = e1 | o.e1;
            s.e2 = e2 | o.e2;
            s.rd1 = rd1 | o.rd1;
            s.rd2 = rd2 | o.rd2;
            s.orInto(into);
            s.orInto(o.into);
            s.orFacts(facts);
            s.orFacts(o.facts);
            return s;
        }

        Sum intersect(Sum o) {
            Sum s = new Sum();
            s.retained = retained & o.retained;
            s.returned = returned & o.returned;
            s.e1 = e1 & o.e1;
            s.e2 = e2 & o.e2;
            s.rd1 = rd1 & o.rd1;
            s.rd2 = rd2 & o.rd2;
            if (into != null) {
                for (int k = 0; k < into.length; k++) s.addInto(k >> 1, k & 1, into[k] & o.into(k >> 1, k & 1));
            }
            long[] buf = new long[(facts == null ? 0 : facts.length) + (o.facts == null ? 0 : o.facts.length)];
            int n = 0;
            if (facts != null) for (long x : facts) if (o.covers(x)) buf[n++] = x;
            if (o.facts != null) for (long x : o.facts) if (covers(x)) buf[n++] = x;
            s.facts = sortedUnique(buf, n);
            return s;
        }
    }

    private static long[] sortedUnique(long[] buf, int n) {
        if (n == 0) return null;
        Arrays.sort(buf, 0, n);
        int w = 1;
        for (int r = 1; r < n; r++) if (buf[r] != buf[w - 1]) buf[w++] = buf[r];
        return w == buf.length ? buf : Arrays.copyOf(buf, w);
    }

    private static long storedOf(long[] into) {
        long s = 0;
        if (into == null) return 0;
        for (int k = 0; k < into.length; k++) if (into[k] != 0) s |= 1L << (k >> 1);
        return s;
    }

    private static long factStored(long[] facts) {
        long s = 0;
        if (facts == null) return 0;
        for (long x : facts) if (factKind(x) == INTO1F) s |= 1L << factJ(x);
        return s;
    }

    private static final Sum UNKNOWN_SUM = allSum(-1L, -1L);
    private static final Sum NATIVE_SUM = allSum(0L, -1L);
    private static final Sum NO_GAIN = new Sum();

    private static Sum allSum(long kept, long contents) {
        Sum s = new Sum();
        s.retained = kept;
        s.returned = kept;
        s.rd1 = kept;
        s.rd2 = kept;
        s.e1 = contents;
        s.e2 = contents;
        return s;
    }

    private static final class Frame {
        final String key;
        final int depth;
        int low;
        Sum approx = new Sum();
        boolean used;

        Frame(String key, int depth) {
            this.key = key;
            this.depth = depth;
            this.low = depth;
        }
    }

    private static final class Scratch {
        final ScratchIdentityMap<Value, Long> bits = new ScratchIdentityMap<>();
        final ScratchIdentityMap<Instruction, Target> targets = new ScratchIdentityMap<>();
        final Map<Integer, Long> slots = new HashMap<>();
        final ScratchIdentityMap<Value, long[]> masks = new ScratchIdentityMap<>();
        final ScratchIdentityMap<Instruction, Sum> sums = new ScratchIdentityMap<>();
        final Map<Integer, long[]> slotMasks = new HashMap<>();
        final Set<Integer> paramSlots = new HashSet<>();
        final Set<Integer> storedSlots = new HashSet<>();
        final Map<Long, long[]> fieldContents = new HashMap<>();
        final Map<Integer, Set<Integer>> fieldMarks = new HashMap<>();
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
    private static final ThreadLocal<ArrayDeque<Frame>> STACK = ThreadLocal.withInitial(ArrayDeque::new);
    private static final String[] DBG = debugFilters();

    private static String[] debugFilters() {
        String env = System.getenv("JN_OWN_DEBUG");
        if (env == null) return null;
        List<String> out = new ArrayList<>();
        for (String part : env.split(",")) if (!part.isBlank()) out.add(part.trim());
        return out.toArray(new String[0]);
    }

    static boolean dbgMatch(String name) {
        if (DBG == null || name == null) return false;
        for (String f : DBG) if (name.contains(f)) return true;
        return false;
    }

    private static String hex(long v) {
        return Long.toHexString(v);
    }

    private static String fieldName(int f) {
        return f < 0 ? "*" : FIELD_NAMES.getOrDefault(f, "#" + f);
    }

    private static String tagName(long tag) {
        return tag == 0 ? "none" : tag < 0 ? "*" : fieldName((int) (tag - 1));
    }

    private static String sumDesc(Sum s) {
        StringBuilder sb = new StringBuilder();
        sb.append("retained=").append(hex(s.retained)).append(" stored=").append(hex(s.stored()))
            .append(" published=").append(hex(s.retained | s.stored())).append(" returned=").append(hex(s.returned))
            .append(" e1=").append(hex(s.e1)).append(" e2=").append(hex(s.e2))
            .append(" rd1=").append(hex(s.rd1)).append(" rd2=").append(hex(s.rd2));
        if (s.into != null) {
            for (int k = 0; k < s.into.length; k += 2) {
                long a = s.into[k];
                long b = k + 1 < s.into.length ? s.into[k + 1] : 0;
                if ((a | b) != 0) sb.append(" into[").append(k >> 1).append("]=").append(hex(a)).append('/').append(hex(b));
            }
        }
        if (s.facts != null) {
            for (long x : s.facts) {
                switch (factKind(x)) {
                    case E1F -> sb.append(" e1[").append(factJ(x)).append('.').append(fieldName(factF(x))).append(']');
                    case RD1F -> sb.append(" rd1[").append(factJ(x)).append('.').append(fieldName(factF(x))).append(']');
                    default -> sb.append(" into1[").append(factJ(x)).append("->").append(factK(x)).append('.')
                        .append(fieldName(factF(x))).append(']');
                }
            }
        }
        return sb.toString();
    }

    private static String describe(Instruction inst) {
        StringBuilder sb = new StringBuilder().append(inst.getOpcode());
        if (isCall(inst.getOpcode())) sb.append(' ').append(extractCalleeName(inst));
        if (inst.getOpcode() == Opcode.PUT_FIELD || inst.getOpcode() == Opcode.GET_FIELD) {
            sb.append(' ').append(extractFieldName(inst));
        }
        if (inst.getParent() != null) sb.append(" @").append(inst.getParent().getLabel());
        return sb.toString();
    }

    private static final class Why {
        final String[] bit = new String[128];
        String[] cont = new String[0];
        String[] alloc = new String[0];

        void note(Sum r, long ret0, long st0, int[] mk0, int[] marks, String desc) {
            for (long q = r.retained & ~ret0; q != 0; q &= q - 1) {
                int o = Long.numberOfTrailingZeros(q);
                if (bit[o] == null) bit[o] = "strict-retain by " + desc;
            }
            for (long q = r.stored() & ~st0; q != 0; q &= q - 1) {
                int o = Long.numberOfTrailingZeros(q);
                if (bit[64 + o] == null) {
                    bit[64 + o] = "stored into param(s) depth1=" + hex(r.into(o, 0)) + " deeper=" + hex(r.into(o, 1))
                        + " by " + desc;
                }
            }
            if (mk0 == null) return;
            for (int k = 0; k < marks.length; k++) {
                if (marks[k] != mk0[k] && cont[k] == null) cont[k] = "flags=" + marks[k] + " by " + desc;
            }
        }

        String report(long published) {
            StringBuilder sb = new StringBuilder();
            for (long q = published; q != 0; q &= q - 1) {
                int o = Long.numberOfTrailingZeros(q);
                sb.append("DBG   cause param ").append(o).append(": ")
                    .append(bit[o] != null ? bit[o] : "-").append(" | ")
                    .append(bit[64 + o] != null ? bit[64 + o] : "-").append('\n');
            }
            for (int k = 0; k < cont.length; k++) {
                if (cont[k] == null) continue;
                sb.append("DBG   container ").append(k).append(' ').append(alloc[k]).append(" escape: ").append(cont[k]).append('\n');
            }
            return sb.toString();
        }
    }

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public EscapeAnalysisResult analyze(Module module, AliasAnalysisResult aliasResult,
                                        Map<String, EscapeSummary> summaries,
                                        DependencyResolver resolver, Scheduler scheduler) {
        return new Solver(module, resolver, scheduler).solve();
    }

    static boolean isCall(Opcode op) {
        return op == Opcode.CALL || op == Opcode.VIRTUAL_CALL || op == Opcode.INTERFACE_CALL
            || op == Opcode.STATIC_CALL || op == Opcode.SPECIAL_CALL;
    }

    static boolean isDispatch(Opcode op) {
        return op == Opcode.VIRTUAL_CALL || op == Opcode.INTERFACE_CALL;
    }

    static int argCount(Instruction inst) {
        int n = inst.getOperands().size();
        return switch (inst.getOpcode()) {
            case VIRTUAL_CALL, INTERFACE_CALL, SPECIAL_CALL -> n == 0 ? 0 : (n >= 2 ? n - 1 : 1);
            default -> Math.max(0, n - 1);
        };
    }

    static Value arg(Instruction inst, int j) {
        List<Value> ops = inst.getOperands();
        return switch (inst.getOpcode()) {
            case VIRTUAL_CALL, INTERFACE_CALL, SPECIAL_CALL -> j == 0 ? ops.getFirst() : ops.get(j + 1);
            default -> ops.get(j + 1);
        };
    }

    static boolean isReference(Value v) {
        if (v == null || v.getType() == null) return false;
        Type t = v.getType();
        return t.isReference() || t.isArray() || t.isNull() || t.isUnknown();
    }

    static boolean isNullConstant(Value v) {
        return v instanceof Constant c && c.getValue() == null;
    }

    static boolean isStringLiteral(Value v) {
        if (!(v instanceof Constant c) || !(c.getValue() instanceof String)) return false;
        Type t = c.getType();
        return t != null && t.isReference() && "java/lang/String".equals(t.getClassName());
    }

    public static boolean concatReadsOnly(Instruction inst, Value v) {
        if (inst.getOpcode() != Opcode.INVOKEDYNAMIC || v == null) return false;
        if (!(inst.getInvokedynamicData() instanceof InvokeDynamicInfo info)) return false;
        ResolvedCall call = info.resolvedCall();
        if (call == null || call.getType() != ResolvedCall.Type.CONCAT) return false;
        Type t = v.getType();
        return t != null && t.isReference() && "java/lang/String".equals(t.getClassName());
    }

    private static final class Solver {
        private final Module module;
        private final DependencyResolver resolver;
        private final Scheduler scheduler;
        private final List<Function> bodies = new ArrayList<>();
        private final Map<Function, Integer> index = new IdentityHashMap<>();
        private final Set<String> lambdaInterfaces = new HashSet<>();
        private final Map<String, Target> directTargets = new ConcurrentHashMap<>();
        private final Map<String, Target> dispatchTargets = new ConcurrentHashMap<>();
        private final Map<String, Integer> implCache = new ConcurrentHashMap<>();
        private AtomicReferenceArray<Site[]> sites;
        private AtomicLongArray retained;
        private AtomicLongArray returned;
        private AtomicLongArray fresh;
        private AtomicLongArray e1;
        private AtomicLongArray e2;
        private AtomicLongArray rd1;
        private AtomicLongArray rd2;
        private AtomicLongArray sens;
        private AtomicReferenceArray<long[]> into;
        private AtomicReferenceArray<long[]> fieldFacts;
        private AtomicReferenceArray<Sum[]> overrides;
        private final Map<String, Sum> contexts = new ConcurrentHashMap<>();
        private final AtomicInteger budget = new AtomicInteger(CONTEXT_BUDGET);
        private int[][] callers;

        Solver(Module module, DependencyResolver resolver, Scheduler scheduler) {
            this.module = module;
            this.resolver = resolver;
            this.scheduler = scheduler;
        }

        EscapeAnalysisResult solve() {
            List<? extends Function> jnLoop1 = module.getFunctions();
            for (Function f : jnLoop1) {
                if (f.getEntryBlock() == null || index.containsKey(f)) continue;
                index.put(f, bodies.size());
                bodies.add(f);
                collectLambdaInterfaces(f);
            }
            int n = bodies.size();
            sites = new AtomicReferenceArray<>(n);
            retained = new AtomicLongArray(n);
            returned = new AtomicLongArray(n);
            fresh = new AtomicLongArray(n);
            e1 = new AtomicLongArray(n);
            e2 = new AtomicLongArray(n);
            rd1 = new AtomicLongArray(n);
            rd2 = new AtomicLongArray(n);
            sens = new AtomicLongArray(n);
            into = new AtomicReferenceArray<>(n);
            fieldFacts = new AtomicReferenceArray<>(n);
            for (int i = 0; i < n; i++) fresh.set(i, 1L);

            int[] all = new int[n];
            for (int i = 0; i < n; i++) all[i] = i;
            parallel(all, i -> {
                sites.set(i, collectSites(bodies.get(i)));
                return false;
            });
            buildCallers(n);

            solveMasks(all);
            int refined = refineByReturnClasses();
            if (refined > 0) {
                reset(n);
                buildCallers(n);
                solveMasks(all);
            }
            System.out.println("Escape: " + refined + " dispatch call(s) narrowed by allocated return classes");
            int specialised = specialise(all);
            if (specialised > 0) {
                reset(n);
                solveMasks(all);
            }
            System.out.println("Escape: " + specialised + " call site(s) specialised by constant arguments, "
                + contexts.size() + " context(s)");

            EscapeAnalysisResult result = new EscapeAnalysisResult();
            String dbgFilter = System.getenv("JN_OWN_DEBUG");
            if (dbgFilter != null) {
                for (int i = 0; i < n; i++) {
                    String nm = bodies.get(i).getName();
                    if (!dbgMatch(nm)) continue;
                    System.out.println("DBG fn " + nm + " retained=" + Long.toHexString(publicRetained(i))
                        + " returned=" + Long.toHexString(returned.get(i)) + " fresh=" + fresh.get(i));
                    Site[] dss = sites.get(i);
                    Sum[] dov = overrides == null ? null : overrides.get(i);
                    Sum[] dsums = new Sum[dss.length];
                    for (int k = 0; k < dss.length; k++) {
                        Sum b = sumOf(dss[k].target());
                        dsums[k] = dov != null && dov[k] != null ? b.intersect(dov[k]) : b;
                    }
                    Why w = new Why();
                    Sum dr = analyze(bodies.get(i), dss, dsums, null, w);
                    System.out.print("DBG   summary " + sumDesc(dr) + "\n" + w.report(publicRetained(i)));
                    for (int k = 0; k < dss.length; k++) {
                        Site s = dss[k];
                        CallShape sh = shapeOf(s.target());
                        Value r0 = s.inst().getOperands().isEmpty() ? null : s.inst().getOperands().getFirst();
                        Object rp = r0 == null ? null : r0.getPointsTo();
                        String rdesc = rp instanceof PointsToSet ps
                            ? ("pts size=" + ps.size() + " unknown=" + ps.containsUnknown() + " first="
                                + (ps.isEmpty() ? "-" : AllocationSite.byId(ps.id(ps.cursor())).getType()))
                            : "pts none";
                        System.out.println("DBG     receiver " + rdesc);
                        System.out.println("DBG   call " + extractCalleeName(s.inst()) + " unknown=" + s.target().unknown()
                            + " targets=" + s.target().functions().length + " exact=" + s.exact()
                            + " retained=" + Long.toHexString(sh.retainedArgs()) + " returned=" + Long.toHexString(sh.returnedArgs())
                            + " fresh=" + sh.freshReturn());
                        System.out.println("DBG     generic{" + sumDesc(sumOf(s.target())) + "} override="
                            + (dov != null && dov[k] != null ? "{" + sumDesc(dsums[k]) + "}" : "none"));
                    }
                }
            }
            directTargets.forEach((callee, t) -> result.putCallShape(false, callee, shapeOf(t)));
            dispatchTargets.forEach((callee, t) -> result.putCallShape(true, callee, shapeOf(t)));
            int exact = 0;
            for (int i = 0; i < n; i++) {
                Sum[] ov = overrides == null ? null : overrides.get(i);
                Site[] ss = sites.get(i);
                for (int k = 0; k < ss.length; k++) {
                    Site s = ss[k];
                    if (!s.exact()) continue;
                    exact++;
                    if (ov != null && ov[k] != null) continue;
                    result.putSiteShape(s.inst(), shapeOf(s.target()));
                }
            }
            System.out.println("Escape: " + exact + " dispatch call(s) narrowed by receiver points-to");
            for (int i = 0; i < n; i++) {
                Sum[] ov = overrides == null ? null : overrides.get(i);
                if (ov == null) continue;
                Site[] ss = sites.get(i);
                for (int k = 0; k < ss.length; k++) {
                    if (ov[k] == null) continue;
                    Sum eff = sumOf(ss[k].target()).intersect(ov[k]);
                    result.putSiteShape(ss[k].inst(), new CallShape(eff.retained | eff.stored(), eff.returned,
                        freshTarget(ss[k].target())));
                }
            }
            int freshCount = 0;
            for (int i = 0; i < n; i++) if (fresh.get(i) != 0 && returnsReference(bodies.get(i))) freshCount++;
            System.out.println("Escape: call shapes for " + (directTargets.size() + dispatchTargets.size())
                + " callee(s), " + freshCount + " fresh-returning function(s)");
            return result;
        }

        private void solveMasks(int[] all) {
            int[] dirty = all;
            while (dirty.length > 0) dirty = callersOf(parallel(dirty, this::updateMasks));
            dirty = all;
            while (dirty.length > 0) dirty = callersOf(parallel(dirty, this::updateFresh));
        }

        private void reset(int n) {
            for (int i = 0; i < n; i++) {
                retained.set(i, 0L);
                returned.set(i, 0L);
                fresh.set(i, 1L);
                e1.set(i, 0L);
                e2.set(i, 0L);
                rd1.set(i, 0L);
                rd2.set(i, 0L);
                into.set(i, null);
                fieldFacts.set(i, null);
            }
        }

        private interface Step {
            boolean run(int i);
        }

        private int[] parallel(int[] work, Step step) {
            List<Integer> changed = LlvmUtil.awaitFlux(Flux.range(0, work.length)
                .parallel()
                .runOn(scheduler, 1)
                .filter(k -> step.run(work[k]))
                .map(k -> work[k])
                .sequential());
            if (changed == null) return NONE;
            int[] out = new int[changed.size()];
            for (int k = 0; k < out.length; k++) out[k] = changed.get(k);
            return out;
        }

        private void buildCallers(int n) {
            int[] counts = new int[n];
            for (int i = 0; i < n; i++) {
                for (Site s : sites.get(i)) for (int g : s.target().functions()) counts[g]++;
            }
            callers = new int[n][];
            for (int g = 0; g < n; g++) callers[g] = counts[g] == 0 ? NONE : new int[counts[g]];
            int[] fill = new int[n];
            for (int i = 0; i < n; i++) {
                for (Site s : sites.get(i)) for (int g : s.target().functions()) callers[g][fill[g]++] = i;
            }
        }

        private int[] callersOf(int[] changed) {
            Set<Integer> next = new LinkedHashSet<>();
            for (int g : changed) for (int c : callers[g]) next.add(c);
            int[] out = new int[next.size()];
            int k = 0;
            for (int c : next) out[k++] = c;
            return out;
        }

        private Site[] collectSites(Function f) {
            List<Site> out = new ArrayList<>();
            for (BasicBlock block : f.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    if (!isCall(inst.getOpcode())) continue;
                    Target t = targetOf(inst);
                    if (isArrayClone(inst)) {
                        out.add(new Site(inst, FRESH_NATIVE, true));
                        continue;
                    }
                    Target narrow = isDispatch(inst.getOpcode()) ? narrowed(inst, t) : t;
                    out.add(new Site(inst, narrow, narrow != t));
                }
            }
            return out.toArray(new Site[0]);
        }

        private void collectLambdaInterfaces(Function f) {
            for (BasicBlock block : f.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    if (inst.getOpcode() != Opcode.INVOKEDYNAMIC
                            || !(inst.getInvokedynamicData() instanceof InvokeDynamicInfo info)) continue;
                    String iface = info.lambdaInterface();
                    if (iface == null) continue;
                    addLambdaInterface(iface);
                    for (String marker : info.lambdaMarkerInterfaces()) addLambdaInterface(marker);
                }
            }
        }

        private void addLambdaInterface(String iface) {
            if (!lambdaInterfaces.add(iface)) return;
            ClassNode node = resolver.getClassNode(iface);
            if (node == null) return;
            for (String parent : node.getInterfaces()) addLambdaInterface(parent);
        }

        private static boolean isArrayClone(Instruction inst) {
            if (!isDispatch(inst.getOpcode()) || inst.getOperands().isEmpty()) return false;
            String callee = extractCalleeName(inst);
            if (callee == null || !callee.endsWith(".clone()Ljava/lang/Object;")) return false;
            if (callee.startsWith("[")) return true;
            if (!callee.equals("java/lang/Object.clone()Ljava/lang/Object;")) return false;
            Value recv = inst.getOperands().getFirst();
            return recv != null && recv.getType() != null && recv.getType().isArray();
        }

        private Target targetOf(Instruction inst) {
            String callee = extractCalleeName(inst);
            if (callee == null) return UNKNOWN;
            if (isDispatch(inst.getOpcode())) {
                return dispatchTargets.computeIfAbsent(callee, this::dispatch);
            }
            return directTargets.computeIfAbsent(callee, this::direct);
        }

        private Target direct(String callee) {
            Function g = module.getFunction(canonicalCalleeKey(callee));
            Integer gi = g == null ? null : index.get(g);
            if (gi != null) return new Target(new int[]{gi}, false);
            Target nt = nativeTarget(callee);
            return nt != null ? nt : UNKNOWN;
        }

        private Target nativeTarget(String callee) {
            if (FRESH_NATIVES.contains(callee)) return FRESH_NATIVE;
            if (PURE_NATIVES.contains(callee)) return PURE_NATIVE;
            if (callee.startsWith("__jnative_fn_java_lang_System_arraycopy__")) return PURE_NATIVE;
            int paren = callee.indexOf('(');
            int dot = paren < 0 ? -1 : callee.lastIndexOf('.', paren);
            if (dot <= 0) return null;
            String owner = callee.substring(0, dot);
            String name = callee.substring(dot + 1, paren);
            String descriptor = callee.substring(paren);
            if (owner.startsWith("[") && name.equals("clone") && descriptor.equals("()Ljava/lang/Object;")) return FRESH_NATIVE;
            if (descriptor.indexOf('L') >= 0 || descriptor.indexOf('[') >= 0) return null;
            String[] holder = new String[1];
            MethodNode m = resolver.findMethodInHierarchy(owner, name, descriptor, holder);
            return m != null && m.isNative() && m.isStatic() ? PURE_NATIVE : null;
        }

        private Target dispatch(String callee) {
            int paren = callee.indexOf('(');
            int dot = paren < 0 ? -1 : callee.lastIndexOf('.', paren);
            if (dot <= 0) return UNKNOWN;
            String owner = callee.substring(0, dot);
            // LLVM emits lambda adaptors after escape analysis. The class hierarchy
            // cannot account for these implementations or their return values.
            if (lambdaInterfaces.contains(owner)) return LAMBDA_DISPATCH;
            String name = callee.substring(dot + 1, paren);
            String descriptor = callee.substring(paren);
            if (owner.startsWith("[")) {
                Target nt = nativeTarget(callee);
                return nt != null ? nt : UNKNOWN;
            }
            Set<String> types = new LinkedHashSet<>(resolver.getSubclasses(owner));
            types.add(owner);
            Set<Integer> found = new LinkedHashSet<>();
            boolean sawPure = false;
            for (String type : types) {
                ClassNode node = resolver.getClassNode(type);
                if (node == null || node.isInterface() || (node.getAccess() & Opcodes.ACC_ABSTRACT) != 0) continue;
                String[] holder = new String[1];
                MethodNode impl = resolver.findMethodInHierarchy(type, name, descriptor, holder);
                if (impl == null || holder[0] == null) return UNKNOWN;
                if (impl.isAbstract()) continue;
                Function g = module.getFunction(canonicalCalleeKey(holder[0] + "." + name + descriptor));
                if (g == null) continue;
                Integer gi = index.get(g);
                if (gi == null) {
                    if (nativeTarget(holder[0] + "." + name + descriptor) == PURE_NATIVE) {
                        sawPure = true;
                        continue;
                    }
                    return UNKNOWN;
                }
                found.add(gi);
            }
            if (found.isEmpty()) return sawPure ? PURE_NATIVE : UNKNOWN;
            int[] fs = new int[found.size()];
            int k = 0;
            for (int g : found) fs[k++] = g;
            return new Target(fs, false);
        }

        private Target narrowed(Instruction inst, Target cha) {
            // Points-to sets do not include lambda allocations. Narrow these calls
            // only in refineByReturnClasses, which requires known allocation origins.
            if (cha == LAMBDA_DISPATCH) return cha;
            if (!cha.unknown() && cha.functions().length <= 1) return cha;
            List<Value> ops = inst.getOperands();
            if (ops.isEmpty()) return cha;
            Value recv = ops.getFirst();
            if (recv == null || !(recv.getPointsTo() instanceof PointsToSet pts)) return cha;
            if (pts.isEmpty() || pts.containsUnknown()) return cha;
            Set<String> classes = new LinkedHashSet<>();
            for (long c = pts.cursor(); pts.has(c); c = pts.next(c)) {
                Type t = AllocationSite.byId(pts.id(c)).getType();
                String cls = t == null ? null : t.isArray() ? "java/lang/Object" : t.isReference() ? t.getClassName() : null;
                if (cls == null || cls.isEmpty()) return cha;
                classes.add(cls);
            }
            return narrowTo(inst, cha, classes);
        }

        private Target narrowTo(Instruction inst, Target cha, Set<String> classes) {
            String callee = extractCalleeName(inst);
            if (callee == null) return cha;
            int paren = callee.indexOf('(');
            int dot = paren < 0 ? -1 : callee.lastIndexOf('.', paren);
            if (dot <= 0) return cha;
            String name = callee.substring(dot + 1, paren);
            String descriptor = callee.substring(paren);
            int[] allowed = cha.functions().clone();
            Arrays.sort(allowed);
            int[] found = new int[Math.max(allowed.length, 4)];
            int k = 0;
            for (String cls : classes) {
                int g = implIndex(cls, name, descriptor);
                if (g < 0 || (!cha.unknown() && Arrays.binarySearch(allowed, g) < 0)) return cha;
                boolean dup = false;
                for (int q = 0; q < k; q++) {
                    if (found[q] == g) {
                        dup = true;
                        break;
                    }
                }
                if (dup) continue;
                if (k == found.length) found = Arrays.copyOf(found, k << 1);
                found[k++] = g;
            }
            if (k == 0 || (!cha.unknown() && k >= allowed.length)) return cha;
            return new Target(Arrays.copyOf(found, k), false);
        }

        private int refineByReturnClasses() {
            Map<Integer, Set<String>> memo = new HashMap<>();
            Set<Integer> failed = new HashSet<>();
            int refined = 0;
            for (int i = 0; i < bodies.size(); i++) {
                Site[] ss = sites.get(i);
                Map<Instruction, Target> local = null;
                for (int k = 0; k < ss.length; k++) {
                    Site s = ss[k];
                    if (s.exact() || !isDispatch(s.inst().getOpcode())) continue;
                    if (!s.target().unknown() && s.target().functions().length <= 1) continue;
                    List<Value> ops = s.inst().getOperands();
                    if (ops.isEmpty()) continue;
                    if (local == null) {
                        local = new IdentityHashMap<>();
                        for (Site site : ss) local.put(site.inst(), site.target());
                    }
                    ArrayDeque<Value> work = new ArrayDeque<>();
                    work.push(ops.getFirst());
                    Set<String> classes = allocatedClasses(work, local, memo, failed);
                    if (classes == null || classes.isEmpty()) continue;
                    Target t = narrowTo(s.inst(), s.target(), classes);
                    if (t == s.target()) continue;
                    ss[k] = new Site(s.inst(), t, true);
                    local.put(s.inst(), t);
                    refined++;
                }
            }
            return refined;
        }

        private Map<String, List<Instruction>> fieldStores;
        private final Map<String, Set<String>> fieldClassMemo = new HashMap<>();
        private final Set<String> fieldClassBusy = new HashSet<>();
        private final Set<String> fieldClassFailed = new HashSet<>();

        private String canonicalField(String raw) {
            if (raw == null) return null;
            int dot = raw.lastIndexOf('.');
            if (dot <= 0) return null;
            FieldNode fn = resolver.getField(raw.substring(0, dot), raw.substring(dot + 1));
            if (fn == null || fn.getOwner() == null) return null;
            if ((fn.getAccess() & Opcodes.ACC_FINAL) == 0 || (fn.getAccess() & Opcodes.ACC_STATIC) != 0) return null;
            if (serializable(fn.getOwner())) return null;
            return fn.getOwner() + "." + fn.getName();
        }

        private boolean serializable(String cls) {
            ArrayDeque<String> work = new ArrayDeque<>();
            Set<String> seen = new HashSet<>();
            work.add(cls);
            while (!work.isEmpty()) {
                String c = work.poll();
                if (c == null || !seen.add(c)) continue;
                if (c.equals("java/io/Serializable")) return true;
                ClassNode node = resolver.getClassNode(c);
                if (node == null) return true;
                if (node.getSuperName() != null) work.add(node.getSuperName());
                if (node.getInterfaces() != null) work.addAll(node.getInterfaces());
            }
            return false;
        }

        private Set<String> fieldClasses(String raw, Map<Integer, Set<String>> memo, Set<Integer> failed) {
            String key = canonicalField(raw);
            if (key == null) return null;
            Set<String> known = fieldClassMemo.get(key);
            if (known != null) return known;
            if (fieldClassFailed.contains(key) || !fieldClassBusy.add(key)) return null;
            if (fieldStores == null) {
                fieldStores = new HashMap<>();
                for (Function f : bodies) {
                    for (int bi = 0; bi < f.getBlocks().size(); bi++) {
                        List<Instruction> insts = f.getBlocks().get(bi).getInstructions();
                        for (Instruction inst : insts) {
                            if (inst.getOpcode() != Opcode.PUT_FIELD || inst.getOperands().size() < 3) continue;
                            String k = canonicalField(extractFieldName(inst));
                            if (k != null) fieldStores.computeIfAbsent(k, x -> new ArrayList<>()).add(inst);
                        }
                    }
                }
            }
            Set<String> out = new LinkedHashSet<>();
            List<Instruction> stores = fieldStores.getOrDefault(key, List.of());
            for (int i = 0; i < stores.size() && out != null; i++) {
                Instruction put = stores.get(i);
                Integer g = index.get(put.getParent().getFunction());
                if (g == null) {
                    out = null;
                    break;
                }
                Map<Instruction, Target> local = new IdentityHashMap<>();
                Site[] ss = sites.get(g);
                for (Site s : ss) local.put(s.inst(), s.target());
                ArrayDeque<Value> work = new ArrayDeque<>();
                work.push(put.getOperands().get(2));
                Set<String> c = allocatedClasses(work, local, memo, failed);
                if (c == null) out = null;
                else out.addAll(c);
            }
            fieldClassBusy.remove(key);
            if (out == null || out.isEmpty()) {
                fieldClassFailed.add(key);
                return null;
            }
            fieldClassMemo.put(key, out);
            return out;
        }

        private Set<String> returnClasses(int g, Map<Integer, Set<String>> memo, Set<Integer> failed) {
            Set<String> known = memo.get(g);
            if (known != null) return known;
            if (!failed.add(g)) return null;
            Function f = bodies.get(g);
            Map<Instruction, Target> local = new IdentityHashMap<>();
            Site[] ss = sites.get(g);
            for (Site s : ss) local.put(s.inst(), s.target());
            ArrayDeque<Value> work = new ArrayDeque<>();
            for (int b = 0; b < f.getBlocks().size(); b++) {
                Terminator term = f.getBlocks().get(b).getTerminator();
                if (term instanceof ReturnTerminator rt && rt.getValue() != null) work.push(rt.getValue());
            }
            Set<String> out = allocatedClasses(work, local, memo, failed);
            if (out == null || out.isEmpty()) return null;
            failed.remove(g);
            memo.put(g, out);
            return out;
        }

        private Set<String> allocatedClasses(ArrayDeque<Value> work, Map<Instruction, Target> local,
                                             Map<Integer, Set<String>> memo, Set<Integer> failed) {
            Set<String> out = new LinkedHashSet<>();
            Set<Value> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            while (!work.isEmpty()) {
                Value v = work.pop();
                if (isNullConstant(v) || !seen.add(v)) continue;
                if (isStringLiteral(v)) {
                    out.add("java/lang/String");
                    continue;
                }
                if (!(v instanceof Temporary t) || t.getDefiningInstruction() == null) return null;
                Instruction def = t.getDefiningInstruction();
                List<Value> ops = def.getOperands();
                switch (def.getOpcode()) {
                    case NEW -> {
                        if (ops.isEmpty() || !(ops.getFirst() instanceof Constant c)
                            || c.getValue() == null || !c.getType().isReference()) return null;
                        String cls = c.getValue().toString();
                        if (cls.isEmpty() || cls.charAt(0) == '[') return null;
                        out.add(cls);
                    }
                    case NEW_ARRAY, MULTI_NEW_ARRAY -> out.add("java/lang/Object");
                    case GET_FIELD -> {
                        Set<String> fc = fieldClasses(extractFieldName(def), memo, failed);
                        if (fc == null) return null;
                        out.addAll(fc);
                    }
                    case CAST, CHECKCAST, STORE -> {
                        if (ops.isEmpty()) return null;
                        work.push(ops.getFirst());
                    }
                    case PHI, SELECT -> {
                        for (Value op : ops) {
                            if (isReference(op)) work.push(op);
                        }
                    }
                    case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL -> {
                        Target target = local.get(def);
                        if (target == null || target.unknown() || target.functions().length == 0) return null;
                        for (int g : target.functions()) {
                            Set<String> rc = returnClasses(g, memo, failed);
                            if (rc == null) return null;
                            out.addAll(rc);
                        }
                    }
                    default -> {
                        return null;
                    }
                }
            }
            return out;
        }

        private int implIndex(String cls, String name, String descriptor) {
            String key = cls + '.' + name + descriptor;
            Integer cached = implCache.get(key);
            if (cached != null) return cached;
            int r = -1;
            ClassNode node = resolver.getClassNode(cls);
            if (node != null && !node.isInterface() && (node.getAccess() & Opcodes.ACC_ABSTRACT) == 0) {
                String[] holder = new String[1];
                MethodNode impl = resolver.findMethodInHierarchy(cls, name, descriptor, holder);
                if (impl != null && holder[0] != null && !impl.isAbstract()) {
                    Function g = module.getFunction(canonicalCalleeKey(holder[0] + "." + name + descriptor));
                    Integer gi = g == null ? null : index.get(g);
                    if (gi != null) r = gi;
                }
            }
            implCache.putIfAbsent(key, r);
            return r;
        }

        private boolean retains(Target t, int j) {
            if (t.unknown()) return true;
            for (int g : t.functions()) if (CallShape.has(publicRetained(g), j)) return true;
            return false;
        }

        private long publicRetained(int g) {
            return retained.get(g) | storedOf(into.get(g)) | factStored(fieldFacts.get(g));
        }

        private boolean returnsArg(Target t, int j) {
            if (t.unknown()) return true;
            for (int g : t.functions()) if (CallShape.has(returned.get(g), j)) return true;
            return false;
        }

        private boolean freshTarget(Target t) {
            if (t == FRESH_NATIVE) return true;
            if (t.unknown() || t.functions().length == 0) return false;
            for (int g : t.functions()) if (fresh.get(g) == 0) return false;
            return true;
        }

        private CallShape shapeOf(Target t) {
            if (t.unknown()) return new CallShape(-1L, -1L, false);
            long r = 0;
            long ret = 0;
            for (int g : t.functions()) {
                r |= publicRetained(g);
                ret |= returned.get(g);
            }
            return new CallShape(r, ret, freshTarget(t));
        }

        private boolean updateMasks(int i) {
            Site[] ss = sites.get(i);
            Sum[] ov = overrides == null ? null : overrides.get(i);
            Sum[] sums = new Sum[ss.length];
            for (int k = 0; k < ss.length; k++) {
                Sum b = sumOf(ss[k].target());
                sums[k] = ov != null && ov[k] != null ? b.intersect(ov[k]) : b;
            }
            return merge(i, analyze(bodies.get(i), ss, sums, null));
        }

        private boolean merge(int i, Sum r) {
            boolean grew = grow(retained, i, r.retained);
            grew |= grow(returned, i, r.returned);
            grew |= grow(e1, i, r.e1);
            grew |= grow(e2, i, r.e2);
            grew |= grow(rd1, i, r.rd1);
            grew |= grow(rd2, i, r.rd2);
            if (r.into != null) {
                Sum cur = new Sum();
                cur.orInto(into.get(i));
                Sum add = new Sum();
                add.into = r.into;
                if (!add.within(cur)) {
                    cur.orInto(r.into);
                    into.set(i, cur.into);
                    grew = true;
                }
            }
            if (r.facts != null) {
                long[] old = fieldFacts.get(i);
                Sum cur = new Sum();
                cur.facts = old;
                cur.orFacts(r.facts);
                if (cur.facts != old) {
                    fieldFacts.set(i, cur.facts);
                    grew = true;
                }
            }
            return grew;
        }

        private static boolean grow(AtomicLongArray a, int i, long v) {
            long o = a.get(i);
            if ((o | v) == o) return false;
            a.set(i, o | v);
            return true;
        }

        private Sum sumOf(Target t) {
            if (t.unknown()) return UNKNOWN_SUM;
            if (t.functions().length == 0) return NATIVE_SUM;
            Sum s = new Sum();
            int[] fs = t.functions();
            long[][] parts = new long[fs.length][];
            int count = 0;
            int total = 0;
            for (int g : fs) {
                s.retained |= retained.get(g);
                s.returned |= returned.get(g);
                s.e1 |= e1.get(g);
                s.e2 |= e2.get(g);
                s.rd1 |= rd1.get(g);
                s.rd2 |= rd2.get(g);
                s.orInto(into.get(g));
                long[] f = fieldFacts.get(g);
                if (f != null && f.length > 0) {
                    parts[count++] = f;
                    total += f.length;
                }
            }
            if (count == 1) {
                s.facts = parts[0];
            } else if (count > 1) {
                long[] buf = new long[total];
                int k = 0;
                for (int p = 0; p < count; p++) {
                    System.arraycopy(parts[p], 0, buf, k, parts[p].length);
                    k += parts[p].length;
                }
                s.facts = sortedUnique(buf, total);
            }
            return s;
        }

        private static int single(Target t) {
            return !t.unknown() && t.functions().length == 1 ? t.functions()[0] : -1;
        }

        private static long[] masks(ScratchIdentityMap<Value, long[]> m, Value v) {
            if (v == null) return ZERO4;
            long[] x = m.get(v);
            if (x != null) return x;
            if (v instanceof Temporary t && t.getDefiningInstruction() != null) return ZERO4;
            if (v instanceof Constant c) return c.getValue() == null ? ZERO4 : OTHER4;
            if (v instanceof UndefinedValue) return ZERO4;
            return OTHER4;
        }

        private static boolean orChanged(long[] dst, long[] src) {
            boolean ch = false;
            for (int q = 0; q < 4; q++) {
                long nv = dst[q] | src[q];
                if (nv != dst[q]) {
                    dst[q] = nv;
                    ch = true;
                }
            }
            long t = joinTag(dst[4], src[4]);
            if (t != dst[4]) {
                dst[4] = t;
                ch = true;
            }
            return ch;
        }

        private static void addD1(long[] acc, long d1, long tag) {
            if (d1 == 0) return;
            acc[1] |= d1;
            acc[4] = joinTag(acc[4], tag == 0 ? -1L : tag);
        }

        private static long contKey(int k, int field) {
            return ((long) k << 40) | (field + 1L);
        }

        private static void orEntry(long[] acc, long[] e) {
            if (e == null) return;
            acc[0] |= e[0];
            addD1(acc, e[1], e[3]);
            acc[2] |= e[2];
        }

        private static void readOf(long[] x, int field, long[] cont, Map<Long, long[]> cf, long[] acc) {
            addD1(acc, x[0], tagOf(field));
            acc[2] |= x[1] | x[2];
            if (x[3] != 0) acc[3] |= OTHER;
            for (long r = x[3] & ~OTHER; r != 0; r &= r - 1) {
                int k = Long.numberOfTrailingZeros(r);
                if (field == ANYF) {
                    int o = 6 * k;
                    acc[0] |= cont[o];
                    addD1(acc, cont[o + 1], -1L);
                    acc[2] |= cont[o + 2];
                } else {
                    orEntry(acc, cf.get(contKey(k, field)));
                    orEntry(acc, cf.get(contKey(k, ANYF)));
                }
            }
        }

        private static void deepOf(long[] x, long[] cont, long[] acc) {
            addD1(acc, x[0], -1L);
            acc[2] |= x[0] | x[1] | x[2];
            if (x[3] != 0) acc[3] |= OTHER;
            for (long r = x[3] & ~OTHER; r != 0; r &= r - 1) {
                int o = 6 * Long.numberOfTrailingZeros(r);
                long a = cont[o];
                long d1 = cont[o + 1];
                long d2 = cont[o + 2];
                acc[0] |= a;
                addD1(acc, a | d1, -1L);
                acc[2] |= a | d1 | d2;
            }
        }

        private static boolean intoContainers(long[] b, long[] v, int depth, int field, long[] cont, Map<Long, long[]> cf) {
            long bk = b[3];
            if (bk == 0 || (bk & OTHER) != 0 || (b[0] | b[1] | b[2]) != 0) return false;
            if ((v[0] | v[1] | v[2]) == 0) return false;
            boolean ch = false;
            for (long r = bk; r != 0; r &= r - 1) {
                int k = Long.numberOfTrailingZeros(r);
                int o = 6 * k + 3 * depth;
                for (int q = 0; q < 3; q++) {
                    long nv = cont[o + q] | v[q];
                    if (nv != cont[o + q]) {
                        cont[o + q] = nv;
                        ch = true;
                    }
                }
                if (depth != 0) continue;
                long[] e = cf.computeIfAbsent(contKey(k, field), x -> new long[4]);
                for (int q = 0; q < 3; q++) {
                    long nv = e[q] | v[q];
                    if (nv != e[q]) {
                        e[q] = nv;
                        ch = true;
                    }
                }
                if (v[1] != 0) {
                    long t = joinTag(e[3], v[4] == 0 ? -1L : v[4]);
                    if (t != e[3]) {
                        e[3] = t;
                        ch = true;
                    }
                }
            }
            return ch;
        }

        private static void mark(long[] x, int[] marks, int flag) {
            for (long r = x[3] & ~OTHER; r != 0; r &= r - 1) marks[Long.numberOfTrailingZeros(r)] |= flag;
        }

        private static void e1Of(Sum r, long d1, long tag) {
            if (d1 == 0) return;
            if (tag <= 0) {
                r.e1 |= d1;
                return;
            }
            for (long q = d1; q != 0; q &= q - 1) r.addFact(fact(E1F, Long.numberOfTrailingZeros(q), 0, (int) (tag - 1)));
        }

        private static void rd1Of(Sum r, long d1, long tag) {
            if (d1 == 0) return;
            if (tag <= 0) {
                r.rd1 |= d1;
                return;
            }
            for (long q = d1; q != 0; q &= q - 1) r.addFact(fact(RD1F, Long.numberOfTrailingZeros(q), 0, (int) (tag - 1)));
        }

        private static void escFull(Sum r, long[] x, int[] marks) {
            r.retained |= x[0];
            e1Of(r, x[1], x[4]);
            r.e2 |= x[1] | x[2];
            mark(x, marks, FULL);
        }

        private static void escContents(Sum r, long[] cont, int o) {
            r.retained |= cont[o];
            r.e1 |= cont[o + 1];
            r.e2 |= cont[o + 1] | cont[o + 2];
        }

        private static void escEntry(Sum r, long[] e) {
            if (e == null) return;
            r.retained |= e[0];
            e1Of(r, e[1], e[3]);
            r.e2 |= e[1] | e[2];
        }

        private static void storeEscape(Sum r, Value base, long[] b, long[] v, int depth, int field, int[] marks) {
            mark(v, marks, FULL);
            if ((v[0] | v[1] | v[2]) == 0) return;
            long bp = b[0] | b[1] | b[2];
            long bk = b[3];
            if (bk != 0 || bp == 0) {
                if (bk != 0 && (bk & OTHER) == 0 && bp == 0) return;
                if (bk == 0 && isNullConstant(base)) return;
                escFull(r, v, marks);
                return;
            }
            long direct = depth == 0 ? b[0] : 0;
            long deeper = depth == 0 ? (b[1] | b[2]) : bp;
            for (long q = v[0]; q != 0; q &= q - 1) {
                int o = Long.numberOfTrailingZeros(q);
                if (depth == 0 && field >= 0) {
                    for (long kk = direct; kk != 0; kk &= kk - 1) {
                        r.addFact(fact(INTO1F, o, Long.numberOfTrailingZeros(kk), field));
                    }
                } else {
                    r.addInto(o, 0, direct);
                }
                r.addInto(o, 1, deeper);
            }
            boolean single = bp > 0 && Long.bitCount(bp) == 1;
            boolean sameField = field >= 0 && v[4] == tagOf(field);
            if (v[1] != 0 && !(depth == 0 && single && sameField && b[0] == bp && v[1] == bp)) {
                e1Of(r, v[1], v[4]);
                r.e2 |= v[1];
            }
            if (v[2] != 0 && !(single && v[2] == bp && (depth == 1 || b[0] == 0))) r.e2 |= v[2];
        }

        private static boolean factFor(long x, int kind, int j) {
            return factKind(x) == kind && factJ(x) == Math.min(j, 63);
        }

        private Sum analyze(Function f, Site[] ss, Sum[] sums, ConstantContext cc) {
            return analyze(f, ss, sums, cc, null);
        }

        private Sum analyze(Function f, Site[] ss, Sum[] sums, ConstantContext cc, Why why) {
            Scratch sc = SCRATCH.get();
            ScratchIdentityMap<Value, long[]> m = sc.masks;
            m.clear();
            ScratchIdentityMap<Instruction, Sum> calls = sc.sums;
            calls.clear();
            Map<Integer, long[]> slots = sc.slotMasks;
            slots.clear();
            Set<Integer> paramSlots = sc.paramSlots;
            paramSlots.clear();
            Set<Integer> storedSlots = sc.storedSlots;
            storedSlots.clear();
            Map<Long, long[]> cf = sc.fieldContents;
            cf.clear();
            Map<Integer, Set<Integer>> fieldMarks = sc.fieldMarks;
            fieldMarks.clear();
            for (Parameter p : f.getParameters()) {
                paramSlots.add(p.getIndex());
                if (p.getOrdinal() < 0 || !isReference(p)) continue;
                long b = CallShape.bit(p.getOrdinal());
                m.put(p, new long[]{b, 0, 0, 0, 0});
                slots.computeIfAbsent(p.getIndex(), x -> new long[5])[0] |= b;
            }
            for (int k = 0; k < ss.length; k++) calls.put(ss[k].inst(), sums[k]);
            List<BasicBlock> blocks = f.getBlocks();
            int nCont = 0;
            for (BasicBlock block : blocks) {
                if (cc != null && !cc.live(block)) continue;
                for (Instruction inst : block.getInstructions()) {
                    switch (inst.getOpcode()) {
                        case STORE -> storedSlots.add(inst.getLocalIndex());
                        case NEW, NEW_ARRAY, MULTI_NEW_ARRAY -> {
                            if (inst.getResult() == null) continue;
                            if (why != null && nCont < 63) {
                                why.alloc = Arrays.copyOf(why.alloc, nCont + 1);
                                why.alloc[nCont] = describe(inst);
                            }
                            long kb = nCont < 63 ? 1L << nCont++ : OTHER;
                            m.put(inst.getResult(), new long[]{0, 0, 0, kb, 0});
                        }
                        default -> { }
                    }
                }
            }
            long[] cont = new long[6 * nCont];
            long[] acc = new long[5];
            if (why != null) {
                why.cont = new String[nCont];
                why.alloc = Arrays.copyOf(why.alloc, nCont);
            }

            boolean changed = true;
            while (changed) {
                changed = false;
                for (BasicBlock block : blocks) {
                    if (cc != null && !cc.live(block)) continue;
                    for (Instruction inst : block.getInstructions()) {
                        Value res = inst.getResult();
                        List<Value> ops = inst.getOperands();
                        boolean prim = res == null || (res.getType() != null && res.getType().isPrimitive());
                        Arrays.fill(acc, 0L);
                        switch (inst.getOpcode()) {
                            case CAST, CHECKCAST, SELECT -> {
                                for (Value v : ops) orChanged(acc, masks(m, v));
                            }
                            case PHI -> {
                                List<BasicBlock> preds = block.getPredecessors();
                                boolean aligned = cc != null && preds.size() == ops.size();
                                for (int k = 0; k < ops.size(); k++) {
                                    if (aligned && !cc.edge(preds.get(k), block)) continue;
                                    orChanged(acc, masks(m, ops.get(k)));
                                }
                            }
                            case STORE -> {
                                if (!ops.isEmpty()) {
                                    long[] x = masks(m, ops.getFirst());
                                    orChanged(acc, x);
                                    if ((x[0] | x[1] | x[2] | x[3]) != 0
                                        && orChanged(slots.computeIfAbsent(inst.getLocalIndex(), q -> new long[5]), x)) {
                                        changed = true;
                                    }
                                }
                            }
                            case LOAD -> {
                                long[] x = slots.get(inst.getLocalIndex());
                                if (x != null) orChanged(acc, x);
                                if (!prim && !paramSlots.contains(inst.getLocalIndex())
                                    && !storedSlots.contains(inst.getLocalIndex())) acc[3] |= OTHER;
                            }
                            case GET_FIELD, ALOAD -> {
                                if (!prim && !ops.isEmpty()) readOf(masks(m, ops.getFirst()), fieldOf(inst), cont, cf, acc);
                            }
                            case NEW, NEW_ARRAY, MULTI_NEW_ARRAY -> { }
                            case PUT_FIELD, ASTORE -> {
                                if (ops.size() > 2 && intoContainers(masks(m, ops.getFirst()), masks(m, ops.get(2)), 0,
                                    fieldOf(inst), cont, cf)) {
                                    changed = true;
                                }
                            }
                            case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL -> {
                                Sum s = calls.get(inst);
                                if (s == null) s = UNKNOWN_SUM;
                                int argc = argCount(inst);
                                for (int j = 0; j < argc; j++) {
                                    long[] x = masks(m, arg(inst, j));
                                    if (!prim) {
                                        if (CallShape.has(s.returned, j)) orChanged(acc, x);
                                        if (CallShape.has(s.rd1, j)) readOf(x, ANYF, cont, cf, acc);
                                        if (CallShape.has(s.rd2, j)) deepOf(x, cont, acc);
                                    }
                                    if (s.facts != null) {
                                        for (long fx : s.facts) {
                                            if (!prim && factFor(fx, RD1F, j)) readOf(x, factF(fx), cont, cf, acc);
                                            if (!factFor(fx, INTO1F, j)) continue;
                                            for (int k = 0; k < argc; k++) {
                                                if (Math.min(k, 63) == factK(fx)
                                                    && intoContainers(masks(m, arg(inst, k)), x, 0, factF(fx), cont, cf)) {
                                                    changed = true;
                                                }
                                            }
                                        }
                                    }
                                    if (s.into == null) continue;
                                    for (int k = 0; k < argc; k++) {
                                        for (int d = 0; d < 2; d++) {
                                            if (CallShape.has(s.into(j, d), k)
                                                && intoContainers(masks(m, arg(inst, k)), x, d, ANYF, cont, cf)) changed = true;
                                        }
                                    }
                                }
                                if (!prim) acc[3] |= OTHER;
                            }
                            default -> {
                                if (!prim) acc[3] |= OTHER;
                            }
                        }
                        if (res != null && (acc[0] | acc[1] | acc[2] | acc[3]) != 0) {
                            long[] old = m.get(res);
                            if (old == null) {
                                m.put(res, acc.clone());
                                changed = true;
                            } else if (orChanged(old, acc)) {
                                changed = true;
                            }
                        }
                    }
                }
            }

            Sum r = new Sum();
            int[] marks = new int[nCont];
            for (BasicBlock block : blocks) {
                if (cc != null && !cc.live(block)) continue;
                for (Instruction inst : block.getInstructions()) {
                    List<Value> ops = inst.getOperands();
                    long ret0 = why == null ? 0 : r.retained;
                    long st0 = why == null ? 0 : r.stored();
                    int[] mk0 = why == null ? null : marks.clone();
                    switch (inst.getOpcode()) {
                        case CAST, CHECKCAST, PHI, SELECT, STORE, LOAD, GET_STATIC, ARRAYLENGTH,
                             INSTANCEOF, EQ, NE, LT, LE, GT, GE, MONITOR_ENTER, MONITOR_EXIT, NOP,
                             NEW, NEW_ARRAY, MULTI_NEW_ARRAY -> { }
                        case GET_FIELD, ALOAD -> {
                            if (!ops.isEmpty()) mark(masks(m, ops.getFirst()), marks, READ);
                        }
                        case PUT_FIELD, ASTORE -> {
                            if (ops.size() > 2) {
                                storeEscape(r, ops.getFirst(), masks(m, ops.getFirst()), masks(m, ops.get(2)), 0,
                                    fieldOf(inst), marks);
                            }
                        }
                        case PUT_STATIC -> {
                            for (Value v : ops) escFull(r, masks(m, v), marks);
                        }
                        case INVOKEDYNAMIC -> {
                            for (Value v : ops) if (!concatReadsOnly(inst, v)) escFull(r, masks(m, v), marks);
                        }
                        case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL -> {
                            Sum s = calls.get(inst);
                            if (s == null) s = UNKNOWN_SUM;
                            int argc = argCount(inst);
                            for (int j = 0; j < argc; j++) {
                                long cr0 = why == null ? 0 : r.retained;
                                long cs0 = why == null ? 0 : r.stored();
                                int[] cm0 = why == null ? null : marks.clone();
                                long[] x = masks(m, arg(inst, j));
                                boolean read = CallShape.has(s.rd1, j) || CallShape.has(s.rd2, j);
                                if (CallShape.has(s.retained, j)) {
                                    escFull(r, x, marks);
                                } else if (CallShape.has(s.e1, j)) {
                                    r.e1 |= x[0];
                                    r.e2 |= x[0] | x[1] | x[2];
                                    mark(x, marks, FULL);
                                } else {
                                    boolean fieldEscape = false;
                                    if (s.facts != null) {
                                        for (long fx : s.facts) {
                                            if (!factFor(fx, E1F, j)) continue;
                                            fieldEscape = true;
                                            int fld = factF(fx);
                                            for (long q = x[0]; q != 0; q &= q - 1) {
                                                r.addFact(fact(E1F, Long.numberOfTrailingZeros(q), 0, fld));
                                            }
                                            for (long q = x[3] & ~OTHER; q != 0; q &= q - 1) {
                                                fieldMarks.computeIfAbsent(Long.numberOfTrailingZeros(q), z -> new HashSet<>()).add(fld);
                                            }
                                        }
                                    }
                                    if (fieldEscape) r.e2 |= x[0] | x[1] | x[2];
                                    if (CallShape.has(s.e2, j)) {
                                        r.e2 |= x[0] | x[1] | x[2];
                                        mark(x, marks, DEEP);
                                    }
                                }
                                if (s.facts != null) {
                                    for (long fx : s.facts) {
                                        if (factFor(fx, RD1F, j)) read = true;
                                        if (!factFor(fx, INTO1F, j)) continue;
                                        for (int k = 0; k < argc; k++) {
                                            if (Math.min(k, 63) != factK(fx)) continue;
                                            Value base = arg(inst, k);
                                            storeEscape(r, base, masks(m, base), x, 0, factF(fx), marks);
                                        }
                                    }
                                }
                                if (read) mark(x, marks, READ);
                                if (s.into != null) {
                                    for (int k = 0; k < argc; k++) {
                                        for (int d = 0; d < 2; d++) {
                                            if (!CallShape.has(s.into(j, d), k)) continue;
                                            Value base = arg(inst, k);
                                            storeEscape(r, base, masks(m, base), x, d, ANYF, marks);
                                        }
                                    }
                                }
                                if (why != null) {
                                    why.note(r, cr0, cs0, cm0, marks, describe(inst) + " arg " + j + " argMasks=" + hex(x[0]) + "/"
                                        + hex(x[1]) + "/" + hex(x[2]) + "/" + hex(x[3]) + " tag=" + tagName(x[4])
                                        + " effectiveCallee{" + sumDesc(s) + "}");
                                }
                            }
                        }
                        default -> {
                            for (Value v : ops) escFull(r, masks(m, v), marks);
                        }
                    }
                    if (why != null && !isCall(inst.getOpcode())) why.note(r, ret0, st0, mk0, marks, describe(inst));
                }
                Terminator term = block.getTerminator();
                long tr0 = why == null ? 0 : r.retained;
                long ts0 = why == null ? 0 : r.stored();
                int[] tm0 = why == null ? null : marks.clone();
                if (term instanceof ReturnTerminator rt) {
                    long[] x = masks(m, rt.getValue());
                    r.returned |= x[0];
                    rd1Of(r, x[1], x[4]);
                    r.rd2 |= x[2];
                    mark(x, marks, FULL);
                } else if (term instanceof ThrowTerminator tt) {
                    escFull(r, masks(m, tt.getException()), marks);
                }
                if (why != null && term != null) why.note(r, tr0, ts0, tm0, marks, "terminator " + term + " @" + block.getLabel());
            }
            for (int k = 0; k < nCont; k++) {
                int o = 6 * k;
                long kr0 = r.retained;
                Set<Integer> fm = fieldMarks.get(k);
                if ((marks[k] & FULL) != 0) {
                    escContents(r, cont, o);
                    escContents(r, cont, o + 3);
                } else {
                    if (fm != null) {
                        for (int fld : fm) escEntry(r, cf.get(contKey(k, fld)));
                        escEntry(r, cf.get(contKey(k, ANYF)));
                        escContents(r, cont, o + 3);
                    }
                    if ((marks[k] & DEEP) != 0) {
                        r.e1 |= cont[o];
                        r.e2 |= cont[o] | cont[o + 1] | cont[o + 2];
                    }
                    if ((marks[k] & (DEEP | READ)) != 0) escContents(r, cont, o + 3);
                }
                if (why != null) {
                    StringBuilder fields = new StringBuilder();
                    for (Map.Entry<Long, long[]> e : cf.entrySet()) {
                        if ((e.getKey() >>> 40) != k) continue;
                        long[] v = e.getValue();
                        fields.append(' ').append(fieldName((int) (e.getKey() & ((1L << 40) - 1)) - 1)).append('=')
                            .append(hex(v[0])).append('/').append(hex(v[1])).append('/').append(hex(v[2]));
                    }
                    StringBuilder escaped = new StringBuilder();
                    if (fm != null) for (int fld : fm) escaped.append(' ').append(fieldName(fld));
                    why.note(r, kr0, r.stored(), null, marks, "contents of container " + k + " " + why.alloc[k]
                        + " fields{" + fields + " } c2=" + hex(cont[o + 3]) + "/" + hex(cont[o + 4]) + "/" + hex(cont[o + 5])
                        + " flags=" + marks[k] + " escapedFields{" + escaped + " } (" + why.cont[k] + ")");
                }
            }
            return r;
        }

        private boolean updateSens(int i) {
            Function f = bodies.get(i);
            Scratch sc = SCRATCH.get();
            ScratchIdentityMap<Value, Long> d = sc.bits;
            d.clear();
            Map<Integer, Long> slots = sc.slots;
            slots.clear();
            for (Parameter p : f.getParameters()) {
                if (p.getOrdinal() < 0) continue;
                long b = CallShape.bit(p.getOrdinal());
                d.put(p, b);
                slots.merge(p.getIndex(), b, (a, c) -> a | c);
            }
            boolean changed = true;
            while (changed) {
                changed = false;
                for (BasicBlock block : f.getBlocks()) {
                    for (Instruction inst : block.getInstructions()) {
                        long add = 0;
                        switch (inst.getOpcode()) {
                            case CAST, CHECKCAST, PHI, SELECT -> {
                                for (Value v : inst.getOperands()) add |= get(d, v);
                            }
                            case STORE -> {
                                if (!inst.getOperands().isEmpty()) {
                                    add = get(d, inst.getOperands().getFirst());
                                    long old = slots.getOrDefault(inst.getLocalIndex(), 0L);
                                    if ((old | add) != old) {
                                        slots.put(inst.getLocalIndex(), old | add);
                                        changed = true;
                                    }
                                }
                            }
                            case LOAD -> add = slots.getOrDefault(inst.getLocalIndex(), 0L);
                            default -> { }
                        }
                        Value res = inst.getResult();
                        if (res != null && add != 0) {
                            long old = get(d, res);
                            if ((old | add) != old) {
                                d.put(res, old | add);
                                changed = true;
                            }
                        }
                    }
                }
            }
            long s = 0;
            for (BasicBlock block : f.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    switch (inst.getOpcode()) {
                        case EQ, NE, LT, LE, GT, GE -> {
                            for (Value v : inst.getOperands()) s |= get(d, v);
                        }
                        default -> { }
                    }
                }
                if (block.getTerminator() instanceof CondBranchTerminator cb) s |= get(d, cb.getCondition());
            }
            for (Site site : sites.get(i)) {
                int g = single(site.target());
                if (g < 0) continue;
                long sg = sens.get(g);
                if (sg == 0) continue;
                int argc = argCount(site.inst());
                for (int j = 0; j < argc; j++) if (CallShape.has(sg, j)) s |= get(d, arg(site.inst(), j));
            }
            return grow(sens, i, s);
        }

        private int specialise(int[] all) {
            int[] dirty = all;
            while (dirty.length > 0) dirty = callersOf(parallel(dirty, this::updateSens));
            overrides = new AtomicReferenceArray<>(all.length);
            parallel(all, this::computeOverrides);
            int count = 0;
            for (int i = 0; i < all.length; i++) {
                Sum[] ov = overrides.get(i);
                if (ov == null) continue;
                for (Sum s : ov) if (s != null) count++;
            }
            return count;
        }

        private boolean computeOverrides(int i) {
            Site[] ss = sites.get(i);
            Sum[] out = null;
            ConstantContext cc = null;
            StringBuilder log = dbgMatch(bodies.get(i).getName()) ? new StringBuilder() : null;
            for (int k = 0; k < ss.length; k++) {
                Site s = ss[k];
                int g = single(s.target());
                long sg = g < 0 ? 0 : sens.get(g);
                if (g >= 0 && sg != 0 && !maybeConstantArgs(s.inst(), sg)) {
                    if (log != null) {
                        log.append("DBG spec ").append(bodies.get(i).getName()).append(" -> ").append(extractCalleeName(s.inst()))
                            .append(": no literal or copied constant at sensitive positions sens=").append(hex(sg)).append('\n');
                    }
                    continue;
                }
                if (g >= 0 && sg != 0 && cc == null) cc = new ConstantContext(bodies.get(i), NO_CONSTS);
                Sum sp = trySpecialize(bodies.get(i).getName(), s, cc, log);
                if (sp == null) continue;
                if (out == null) out = new Sum[ss.length];
                out[k] = sp;
            }
            if (out != null) overrides.set(i, out);
            if (log != null && !log.isEmpty()) System.out.print(log);
            return false;
        }

        private Sum trySpecialize(String owner, Site s, ConstantContext cc, StringBuilder log) {
            int g = single(s.target());
            String head = log == null ? null : "DBG spec " + owner + " -> " + extractCalleeName(s.inst()) + ": ";
            if (g < 0) {
                if (log != null) {
                    log.append(head).append("not single target unknown=").append(s.target().unknown())
                        .append(" targets=").append(s.target().functions().length).append('\n');
                }
                return null;
            }
            long sg = sens.get(g);
            if (sg == 0) {
                if (log != null) log.append(head).append("callee has no sensitive params").append('\n');
                return null;
            }
            if (!cc.live(s.inst().getParent())) {
                if (log != null) log.append(head).append("site in dead block").append('\n');
                return null;
            }
            Object[] c = constArgs(s.inst(), cc, sg);
            if (c == null) {
                if (log != null) log.append(head).append("no constant arg at sensitive positions sens=").append(hex(sg)).append('\n');
                return null;
            }
            String[] reason = log == null ? null : new String[1];
            Sum sp = specialize(g, c, reason);
            if (log != null) {
                log.append(head).append("ctx ").append(contextKey(g, c)).append(" sens=").append(hex(sg))
                    .append(" -> ").append(reason[0]).append('\n');
                log.append("DBG       generic{").append(sumDesc(sumOf(s.target()))).append("}\n");
                if (sp != null) log.append("DBG       context{").append(sumDesc(sp)).append("}\n");
            }
            return sp;
        }

        private static boolean maybeConstantArgs(Instruction inst, long sg) {
            int argc = argCount(inst);
            for (int j = 0; j < argc; j++) {
                if (CallShape.has(sg, j) && ConstantContext.maybeConstant(arg(inst, j))) return true;
            }
            return false;
        }

        private static Object[] constArgs(Instruction inst, ConstantContext cc, long sg) {
            int argc = argCount(inst);
            Object[] c = null;
            for (int j = 0; j < argc; j++) {
                if (!CallShape.has(sg, j)) continue;
                Object v = cc.constant(arg(inst, j));
                if (v == null) continue;
                if (c == null) c = new Object[argc];
                c[j] = v;
            }
            return c;
        }

        private static String contextKey(int g, Object[] c) {
            StringBuilder sb = new StringBuilder().append(g);
            for (Object o : c) sb.append(',').append(o == null ? "_" : o == ConstantContext.NULL_REF ? "n" : o.toString());
            return sb.toString();
        }

        private Sum publish(String key, Sum value) {
            Sum prev = contexts.putIfAbsent(key, value);
            return prev == null ? value : prev;
        }

        private Sum specialize(int g, Object[] c, String[] reason) {
            String key = contextKey(g, c);
            Sum known = contexts.get(key);
            if (known != null) {
                if (reason != null) reason[0] = known == NO_GAIN ? "cached no-gain" : "cached context";
                return known == NO_GAIN ? null : known;
            }
            ArrayDeque<Frame> stack = STACK.get();
            Frame hit = null;
            for (Frame fr : stack) {
                if (fr.key.equals(key)) {
                    hit = fr;
                    break;
                }
            }
            if (hit != null) {
                hit.used = true;
                for (Frame fr : stack) {
                    if (fr == hit) break;
                    fr.low = Math.min(fr.low, hit.depth);
                }
                if (reason != null) {
                    reason[0] = (hit == stack.peek() ? "self-recursive" : "in-progress outer context depth " + hit.depth)
                        + " approximation{" + sumDesc(hit.approx) + "}";
                }
                return hit.approx;
            }
            if (stack.size() >= CONTEXT_DEPTH) {
                if (reason != null) reason[0] = "depth limit";
                return null;
            }
            if (budget.decrementAndGet() < 0) {
                if (reason != null) reason[0] = "budget exhausted";
                return null;
            }
            Frame frame = new Frame(key, stack.size());
            stack.push(frame);
            try {
                Function fg = bodies.get(g);
                boolean dbg = dbgMatch(fg.getName());
                ConstantContext cc = new ConstantContext(fg, c);
                Site[] gs = sites.get(g);
                for (int round = 0; round < CONTEXT_ROUNDS; round++) {
                    frame.used = false;
                    boolean gain = cc.folded();
                    StringBuilder log = dbg ? new StringBuilder() : null;
                    Sum[] sums = new Sum[gs.length];
                    for (int k = 0; k < gs.length; k++) {
                        Site s = gs[k];
                        Sum base = sumOf(s.target());
                        Sum sp = null;
                        if (cc.live(s.inst().getParent())) {
                            sp = trySpecialize(fg.getName() + "[" + key + "]", s, cc, log);
                        } else if (log != null) {
                            log.append("DBG spec ").append(fg.getName()).append('[').append(key).append("] -> ")
                                .append(extractCalleeName(s.inst())).append(": site in dead block").append('\n');
                        }
                        if (sp != null) {
                            base = base.intersect(sp);
                            gain = true;
                        }
                        sums[k] = base;
                    }
                    if (!gain) {
                        Sum won = publish(key, NO_GAIN);
                        if (reason != null) reason[0] = "no-gain (no folded branch, no specialised callee)";
                        if (log != null) System.out.print("DBG ctx " + fg.getName() + " [" + key + "] no-gain\n" + log);
                        return won == NO_GAIN ? null : won;
                    }
                    Why why = dbg ? new Why() : null;
                    Sum r = analyze(fg, gs, sums, cc, why);
                    Sum done = !frame.used ? r : r.within(frame.approx) ? frame.approx : null;
                    if (done != null) {
                        boolean fin = frame.low >= frame.depth;
                        if (fin) {
                            Sum won = publish(key, done);
                            if (won != done) return won == NO_GAIN ? null : won;
                        }
                        if (reason != null) {
                            reason[0] = (fin ? "created" : "provisional, depends on in-progress context depth " + frame.low)
                                + " (folded=" + cc.folded() + ", rounds=" + (round + 1) + ")";
                        }
                        if (log != null) {
                            System.out.print("DBG ctx " + fg.getName() + " [" + key + "] " + (fin ? "final" : "provisional")
                                + " folded=" + cc.folded()
                                + " result{" + sumDesc(done) + "}\n" + log + why.report(done.retained | done.stored()));
                        }
                        return done;
                    }
                    frame.approx = frame.approx.union(r);
                }
                Sum won = publish(key, NO_GAIN);
                if (reason != null) reason[0] = "recursion did not stabilise, generic for this context group";
                return won == NO_GAIN ? null : won;
            } finally {
                stack.pop();
            }
        }

        private static long get(ScratchIdentityMap<Value, Long> bits, Value v) {
            if (v == null) return 0;
            Long b = bits.get(v);
            return b == null ? 0 : b;
        }

        private static boolean returnsReference(Function f) {
            Type t = f.getReturnType();
            return t != null && (t.isReference() || t.isArray());
        }

        private boolean updateFresh(int i) {
            if (fresh.get(i) == 0) return false;
            Function f = bodies.get(i);
            if (!returnsReference(f) || !computeFresh(i, f)) {
                fresh.set(i, 0);
                return true;
            }
            return false;
        }

        private boolean computeFresh(int i, Function f) {
            Scratch scratch = SCRATCH.get();
            ScratchIdentityMap<Instruction, Target> targets = scratch.targets;
            targets.clear();
            for (Site s : sites.get(i)) targets.put(s.inst(), s.target());
            Map<Integer, List<Instruction>> stores = new HashMap<>();
            Map<Integer, List<Instruction>> loads = new HashMap<>();
            Map<Value, List<Instruction>> users = new IdentityHashMap<>();
            Set<Integer> paramSlots = new HashSet<>();
            for (int pIdx = 0; pIdx < f.getParameters().size(); pIdx++) {
                paramSlots.add(f.getParameters().get(pIdx).getIndex());
            }
            List<Value> returns = new ArrayList<>();
            List<Value> thrown = new ArrayList<>();
            for (BasicBlock block : f.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    List<Value> ops = inst.getOperands();
                    int slot = inst.getLocalIndex();
                    if (inst.getOpcode() == Opcode.STORE && slot >= 0 && !ops.isEmpty() && isReference(ops.getFirst())) {
                        stores.computeIfAbsent(slot, k -> new ArrayList<>()).add(inst);
                    } else if (inst.getOpcode() == Opcode.LOAD && slot >= 0 && inst.getResult() != null) {
                        loads.computeIfAbsent(slot, k -> new ArrayList<>()).add(inst);
                    }
                    for (Value v : ops) {
                        if (v != null && !(v instanceof Constant)) users.computeIfAbsent(v, x -> new ArrayList<>()).add(inst);
                    }
                }
                Terminator term = block.getTerminator();
                if (term instanceof ReturnTerminator rt) {
                    Value v = rt.getValue();
                    if (v != null && !isNullConstant(v)) returns.add(v);
                } else if (term instanceof ThrowTerminator tt && tt.getException() != null) {
                    thrown.add(tt.getException());
                }
            }

            Set<Value> origin = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            Set<Integer> loadedSlots = new HashSet<>();
            ArrayDeque<Value> work = new ArrayDeque<>(returns);
            while (!work.isEmpty()) {
                Value v = work.pop();
                if (isNullConstant(v) || !origin.add(v)) continue;
                if (isStringLiteral(v)) continue;
                if (!(v instanceof Temporary t) || t.getDefiningInstruction() == null) {
                    return false;
                }
                Instruction def = t.getDefiningInstruction();
                List<Value> ops = def.getOperands();
                switch (def.getOpcode()) {
                    case NEW, NEW_ARRAY, MULTI_NEW_ARRAY -> { }
                    case CAST, CHECKCAST, STORE -> work.push(ops.getFirst());
                    case PHI, SELECT -> {
                        for (Value op : ops) {
                            if (isReference(op)) work.push(op);
                        }
                    }
                    case LOAD -> {
                        int slot = def.getLocalIndex();
                        List<Instruction> st = stores.get(slot);
                        if (slot < 0 || paramSlots.contains(slot) || st == null) {
                            return false;
                        }
                        if (loadedSlots.add(slot)) {
                            for (Instruction instruction : st) work.push(instruction.getOperands().getFirst());
                        }
                    }
                    case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL -> {
                        Target target = targets.get(def);
                        if (!freshTarget(target)) {
                            return false;
                        }
                        int argc = argCount(def);
                        for (int j = 0; j < argc; j++) {
                            if (returnsArg(target, j)) work.push(arg(def, j));
                        }
                    }
                    default -> {
                        return false;
                    }
                }
            }

            Set<Value> alias = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            work = new ArrayDeque<>(origin);
            while (!work.isEmpty()) {
                Value v = work.pop();
                if (isNullConstant(v) || !alias.add(v)) continue;
                List<Instruction> us = users.get(v);
                if (us == null) continue;
                for (Instruction inst : us) {
                    Value res = inst.getResult();
                    switch (inst.getOpcode()) {
                        case CAST, CHECKCAST, PHI, SELECT -> {
                            if (res != null) work.push(res);
                        }
                        case STORE -> {
                            if (res != null) work.push(res);
                            List<Instruction> ld = loads.get(inst.getLocalIndex());
                            if (ld != null && inst.getOperands().getFirst() == v) {
                                for (Instruction instruction : ld) work.push(instruction.getResult());
                            }
                        }
                        case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL -> {
                            Target target = targets.get(inst);
                            int argc = argCount(inst);
                            for (int j = 0; j < argc; j++) {
                                if (arg(inst, j) == v && returnsArg(target, j) && isReference(res)) work.push(res);
                            }
                        }
                        default -> { }
                    }
                }
            }

            for (Value v : alias) {
                List<Instruction> us = users.get(v);
                if (us == null) continue;
                for (Instruction inst : us) {
                    List<Value> ops = inst.getOperands();
                    for (int k = 0; k < ops.size(); k++) {
                        if (ops.get(k) != v || keepsFresh(inst, k, v, targets)) continue;
                        return false;
                    }
                }
            }
            for (Value value : thrown) {
                if (alias.contains(value)) return false;
            }
            return true;
        }

        private boolean keepsFresh(Instruction inst, int k, Value v, ScratchIdentityMap<Instruction, Target> targets) {
            return switch (inst.getOpcode()) {
                case GET_FIELD, ALOAD, ARRAYLENGTH, INSTANCEOF, EQ, NE, MONITOR_ENTER, MONITOR_EXIT,
                     CAST, CHECKCAST, PHI, SELECT, STORE -> true;
                case PUT_FIELD, ASTORE -> k == 0;
                case INVOKEDYNAMIC -> concatReadsOnly(inst, v);
                case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL -> {
                    Target target = targets.get(inst);
                    int argc = argCount(inst);
                    for (int j = 0; j < argc; j++) {
                        if (arg(inst, j) == v && retains(target, j)) yield false;
                    }
                    yield true;
                }
                default -> false;
            };
        }
    }
}
