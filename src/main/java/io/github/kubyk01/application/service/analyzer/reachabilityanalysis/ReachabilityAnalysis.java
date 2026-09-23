package io.github.kubyk01.application.service.analyzer.reachabilityanalysis;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldReference;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodReference;
import io.github.kubyk01.domain.analyzer.reachability.ReachabilityMetadata;
import io.github.kubyk01.domain.analyzer.reflection.ReflectClassInfo;
import io.github.kubyk01.domain.analyzer.reflection.ReflectInfo;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.CondBranchTerminator;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.IndirectBranchTerminator;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.LookupSwitchTerminator;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.ReturnTerminator;
import io.github.kubyk01.domain.ir.TableSwitchTerminator;
import io.github.kubyk01.domain.ir.Terminator;
import io.github.kubyk01.domain.ir.ThrowTerminator;
import io.github.kubyk01.domain.ir.Value;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.*;

@Slf4j
@RequiredArgsConstructor
public class ReachabilityAnalysis {

    private final DependencyResolver resolver;

    @Getter
    private final Set<String> reachableClasses = new HashSet<>();

    private final Set<String> userReachedClasses = new HashSet<>();

    private final Set<String> clinitProcessed = new HashSet<>();

    @Getter
    private final Set<MethodReference> reachableMethods = new HashSet<>();

    @Getter
    private final ReflectInfo reflectInfo = new ReflectInfo();

    private final Deque<MethodReference> worklist = new ArrayDeque<>();

    private final Set<MethodReference> userReachableMethods = new HashSet<>();

    @Getter
    private final Map<MethodReference, Set<MethodReference>> callGraph = new HashMap<>();

    @Getter
    private final Set<String> instantiatedClasses = new HashSet<>();

    /**
     * All virtual/interface dispatch sites observed during analysis,
     * keyed by {@code "owner.name(descriptor)"}.
     *
     * <p>A dispatch site on an abstract class or interface cannot be
     * resolved to a concrete target until every subclass that could be
     * instantiated has been loaded. The main worklist is drained in
     * source order, which is not the same as load order: a class that is
     * only referenced from a later method (such as
     * {@code CharacterDataLatin1}, first touched by
     * {@code CharacterData.of(int)}) may not be in the class map at the
     * moment an earlier method dispatches on its abstract parent. The
     * dispatch sites recorded here are re-resolved after the worklist
     * has drained, once every transitively loaded class is visible and
     * every instantiation has been recorded. See
     * {@link #resolveVirtualDispatchFixedPoint()}.</p>
     */
    private final Set<String> virtualDispatchSites = new LinkedHashSet<>();

    /**
     * Methods whose bytecode has already been scanned for virtual-dispatch
     * sites during the post-drain fixed-point pass. Each method is scanned
     * exactly once, so the loop terminates.
     */
    private final Set<MethodReference> dispatchScanDone = new HashSet<>();

    /**
     * Classes for which the enum-method-forcing rule has already run.
     * Keeps the check a one-time operation per class even when
     * {@code addClassWithInit} is called repeatedly for the same class.
     */
    private final Set<String> enumMethodsProcessed = new HashSet<>();

    public void addInstantiatedClass(String className, boolean fromUser) {
        if (className == null || className.isEmpty()) return;
        instantiatedClasses.add(className);
        addClassWithInit(className, fromUser);
    }

    public void addClassLiteral(String className, boolean fromUser) {
        if (className == null || className.isEmpty()) return;
        reflectInfo.getOrCreateClassInfo(className);
        addClass(className, fromUser);
    }

    public void registerClassLiterals(Module module) {
        if (module == null) return;
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() == null) continue;
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    for (Value op : inst.getOperands()) {
                        registerClassLiteralFromValue(op);
                    }
                }
                Terminator term = block.getTerminator();
                if (term != null) {
                    for (Value op : terminatorOperandsOf(term)) {
                        registerClassLiteralFromValue(op);
                    }
                }
            }
        }
    }

    private void registerClassLiteralFromValue(Value v) {
        if (!(v instanceof Constant c)) return;
        if (!c.getType().isReference()) return;
        if (!"java/lang/Class".equals(c.getType().getClassName())) return;
        Object val = c.getValue();
        if (!(val instanceof String name) || name.isEmpty()) return;
        addClassLiteral(name, false);
    }

    private static List<Value> terminatorOperandsOf(Terminator term) {
        List<Value> out = new ArrayList<>();
        if (term instanceof ReturnTerminator rt && rt.getValue() != null) out.add(rt.getValue());
        else if (term instanceof ThrowTerminator tt && tt.getException() != null) out.add(tt.getException());
        else if (term instanceof CondBranchTerminator cbt) out.add(cbt.getCondition());
        else if (term instanceof LookupSwitchTerminator lst) out.add(lst.getKey());
        else if (term instanceof TableSwitchTerminator tst) out.add(tst.getKey());
        else if (term instanceof IndirectBranchTerminator ibt) out.add(ibt.getTargetBlock());
        return out;
    }

    Set<String> instantiatedSubclasses(String dispatchRoot) {
        Set<String> result = new HashSet<>();
        if (dispatchRoot == null) return result;
        if (instantiatedClasses.contains(dispatchRoot)) {
            result.add(dispatchRoot);
        }
        for (String sub : resolver.getSubclasses(dispatchRoot)) {
            if (instantiatedClasses.contains(sub)) {
                result.add(sub);
            }
        }
        return result;
    }

    public void applyMetadata(ReachabilityMetadata metadata) {
        if (metadata == null) return;

        for (ReachabilityMetadata.ReflectClass rc : metadata.getReflectClasses()) {
            String className = rc.getName().replace('.', '/');
            addClassWithInit(className, true);
            ReflectClassInfo info = reflectInfo.getOrCreateClassInfo(className);
            ClassNode classNode = resolver.getClassNode(className);
            if (classNode != null) {
                info.setSuperName(classNode.getSuperName());
                info.getInterfaces().addAll(classNode.getInterfaces());
            }
            if (classNode != null && !classNode.isExternal()) {
                if (rc.isAllDeclaredMethods() || rc.isAllPublicMethods()) {
                    for (MethodNode m : classNode.getMethods()) {
                        if (!m.getName().equals("<init>") && !m.getName().equals("<clinit>")) {
                            MethodReference ref = new MethodReference(className, m.getName(), m.getDescriptor());
                            info.addMethod(ref);
                            addMethod(ref, true);
                        }
                    }
                } else {
                    for (String methodSig : rc.getMethods()) {
                        int parenIdx = methodSig.indexOf('(');
                        String methodName = parenIdx > 0 ? methodSig.substring(0, parenIdx) : methodSig;
                        for (MethodNode m : classNode.getMethods()) {
                            if (m.getName().equals(methodName) && !m.getName().equals("<init>")) {
                                MethodReference ref = new MethodReference(className, m.getName(), m.getDescriptor());
                                info.addMethod(ref);
                                addMethod(ref, true);
                            }
                        }
                    }
                }
                if (rc.isAllDeclaredFields() || rc.isAllPublicFields()) {
                    for (io.github.kubyk01.domain.analyzer.dependencyresolver.FieldNode f : classNode.getFields()) {
                        FieldReference ref = new FieldReference(className, f.getName(), f.getDescriptor());
                        info.addField(ref);
                    }
                } else {
                    for (String fieldName : rc.getFields()) {
                        FieldReference ref = new FieldReference(className, fieldName, null);
                        info.addField(ref);
                    }
                }
                if (rc.isAllDeclaredConstructors() || rc.isAllPublicConstructors()) {
                    for (MethodNode m : classNode.getMethods()) {
                        if (m.getName().equals("<init>")) {
                            MethodReference ref = new MethodReference(className, "<init>", m.getDescriptor());
                            info.addConstructor(ref);
                            addMethod(ref, true);
                        }
                    }
                } else {
                    for (String ctorSig : rc.getConstructors()) {
                        for (MethodNode m : classNode.getMethods()) {
                            if (m.getName().equals("<init>") && m.getDescriptor().equals(ctorSig)) {
                                MethodReference ref = new MethodReference(className, "<init>", m.getDescriptor());
                                info.addConstructor(ref);
                                addMethod(ref, true);
                            }
                        }
                    }
                }
                // Metadata that lists constructors or reflects a class as
                // constructible is a genuine signal that the class is
                // instantiated: it is what native-image reachability
                // metadata is for. Record it so the virtual-dispatch
                // candidate filter sees the class as a possible runtime
                // type.
                if (rc.isAllDeclaredConstructors() || rc.isAllPublicConstructors()
                    || !rc.getConstructors().isEmpty()) {
                    instantiatedClasses.add(className);
                }
            }
        }
        for (ReachabilityMetadata.ProxyInterface pi : metadata.getProxyInterfaces()) {
            for (String iface : pi.getInterfaces()) {
                String className = iface.replace('.', '/');
                addClassWithInit(className, true);
                ClassNode classNode = resolver.getClassNode(className);
                if (classNode != null && !classNode.isExternal()) {
                    for (MethodNode m : classNode.getMethods()) {
                        addMethod(new MethodReference(className, m.getName(), m.getDescriptor()), true);
                    }
                }
            }
        }
        for (ReachabilityMetadata.JniClass jc : metadata.getJniClasses()) {
            String className = jc.getName().replace('.', '/');
            addClassWithInit(className, true);
            // A class handed to JNI is instantiated from native code.
            instantiatedClasses.add(className);
        }
        log.info("Applied reachability metadata: {} reflect classes, {} proxy interfaces, {} jni classes",
            metadata.getReflectClasses().size(),
            metadata.getProxyInterfaces().size(),
            metadata.getJniClasses().size());
    }

    public void analyzeFromEntry(String entryClass, String entryMethod, String entryDescriptor) {
        String internalClass = entryClass.replace('.', '/');
        if (entryDescriptor == null) {
            entryDescriptor = "([Ljava/lang/String;)V";
        }

        ClassNode systemNode = resolver.getClassNode("java/lang/System");
        if (systemNode != null && !systemNode.isExternal()) {
            boolean hasInitPhase1 = false;
            boolean hasInitPhase2 = false;
            boolean hasInitPhase3 = false;
            for (MethodNode mn : systemNode.getMethods()) {
                String n = mn.getName();
                String d = mn.getDescriptor();
                if (n.equals("initPhase1") && d.equals("()V")) {
                    hasInitPhase1 = true;
                } else if (n.equals("initPhase2") && d.equals("(ZZ)I")) {
                    hasInitPhase2 = true;
                } else if (n.equals("initPhase3") && d.equals("()V")) {
                    hasInitPhase3 = true;
                }
            }

            if (hasInitPhase1) {
                seedBootstrapMethod(new MethodReference("java/lang/System", "initPhase1", "()V"));
            }
            if (hasInitPhase2) {
                seedBootstrapMethod(new MethodReference("java/lang/System", "initPhase2", "(ZZ)I"));
            }
            if (hasInitPhase3) {
                seedBootstrapMethod(new MethodReference("java/lang/System", "initPhase3", "()V"));
            }

            if (hasInitPhase1 || hasInitPhase2 || hasInitPhase3) {
                log.info("Seeded VM bootstrap entry points: initPhase1={}, initPhase2={}, initPhase3={}",
                    hasInitPhase1, hasInitPhase2, hasInitPhase3);
            }
        } else {
            log.warn("java.lang.System could not be loaded; VM bootstrap phase entry points "
                + "(initPhase1/initPhase2/initPhase3) will not be reachable and "
                + "System.props will remain null at runtime");
        }

        // ------------------------------------------------------------------
        // java.lang.String.<clinit> must be reachable.
        //
        // String.<clinit> assigns the static final flag COMPACT_STRINGS =
        // true. Every String.coder(), String.length() and String.hashCode()
        // call reads that flag. Without the clinit, the flag stays at its
        // JVM default (false), coder() reports UTF16, and String.length()
        // returns value.length >> 1 — every LATIN1 string reports half of
        // its real byte count. Integer.parseInt on the literal "0" then
        // sees length 0, throws NumberFormatException, and the concatenated
        // message is truncated by the same bug in the StringConcatHelper
        // sizing path.
        //
        // String.<clinit> is not reached by the ordinary active-use rules:
        // a string literal is not an active use of the class (JLS §12.4.1),
        // and neither is an invocation of an instance method such as
        // length() or charAt(). The explicit trigger below adds it to the
        // worklist so it is translated and scheduled by
        // LlvmGenerator.generateMain() before any other initializer.
        // ------------------------------------------------------------------
        triggerClinit("java/lang/String", true);

        MethodReference entryPoint = new MethodReference(internalClass, entryMethod, entryDescriptor);
        addMethod(entryPoint, true);

        drainWorklist();
        resolveVirtualDispatchFixedPoint();

        log.info("Reachability analysis complete. Reachable classes: {}, methods: {}",
            reachableClasses.size(), reachableMethods.size());
        log.info("User-reached classes: {}, user-reachable methods: {}",
            userReachedClasses.size(), userReachableMethods.size());
        log.info("Instantiated classes: {}", instantiatedClasses.size());
    }

    /**
     * Adds one method to the reachable set and drives the analysis to its
     * fixed point again.
     *
     * <p>For methods the code generator needs but that the ordinary closure
     * cannot reach — the ones a native override calls directly from C, and
     * which therefore have no {@code invokevirtual} in any bytecode to seed
     * the worklist from. {@code java.io.ByteArrayInputStream.<init>([B)V} is
     * the motivating case: it is invoked from
     * {@code __jnative_make_byte_array_input_stream} in
     * {@code jnative_runtime.c}, and without it the vtable slot would hold
     * an unresolved thunk.</p>
     *
     * <p>The method is added as a system method, not a user one, so it does
     * not show up in the user-facing class/method listings.</p>
     *
     * <p>Idempotent: re-adding a method that is already reachable is a
     * no-op, and the fixed-point pass is skipped in that case.</p>
     *
     * <p>Must be called after {@link #analyzeFromEntry} — before it, the
     * worklist has not been seeded with the entry point and driving the
     * fixed point would discard the entry.</p>
     */
    public void addExtraReachableMethod(MethodReference ref) {
        if (ref == null) return;
        if (reachableMethods.contains(ref)) return;
        addMethod(ref, false);
        drainWorklist();
        resolveVirtualDispatchFixedPoint();
    }

    /**
     * Forces the {@code <clinit>} of every class in the transitive
     * superclass chain of the current reachable set into the reachable
     * set, then drives the analysis to a fixed point.
     *
     * <p>JVMS §5.5 step 5 requires that a class's superclass be initialized
     * before the class itself. No bytecode site expresses that requirement
     * directly — the superclass chain is implicit in the class hierarchy —
     * so the ordinary active-use walk never adds a superclass's
     * {@code <clinit>} to the worklist. A class that participates in the
     * image only as an ancestor (it is abstract, or nothing references it
     * directly) would therefore be missing from the module, and the JDK's
     * own initialization-order invariants would be silently violated.</p>
     *
     * <p>The concrete failure this closes:</p>
     * <pre>
     *   java.security.SecureClassLoader           (abstract, unreferenced directly)
     *       static { ClassLoader.registerAsParallelCapable(); }
     *
     *   jdk.internal.loader.BuiltinClassLoader    (extends SecureClassLoader)
     *       static { ClassLoader.registerAsParallelCapable(); }
     *
     *   ClassLoader.registerAsParallelCapable()
     *       consults ParallelLoaders.loaderTypes, whose contents are the
     *       set of subclasses that have already run their own &lt;clinit&gt;.
     * </pre>
     *
     * <p>With {@code SecureClassLoader.<clinit>} missing, the middle entry
     * is never inserted, and {@code BuiltinClassLoader.<clinit>}'s own
     * registration fails with
     * {@code java.lang.InternalError: Unable to register as parallel capable}.
     * Adding the superclass closure here makes the eager schedule in
     * {@code LlvmGenerator.generateMain} complete without any post-hoc
     * resurrection pass.</p>
     *
     * <p>Idempotent: if the closure is already present, the method returns
     * without touching the worklist.</p>
     */
    public void ensureSuperclassClinitsReachable() {
        final int MAX_PASSES = 8;
        int pass = 0;
        boolean changed = true;

        while (changed && pass++ < MAX_PASSES) {
            changed = false;

            // Walk the superclass chain of every currently-reachable class
            // and collect ancestors that are not yet in the reachable set.
            // The walk is monotone (visited guards cycles) and starts from a
            // snapshot so the iteration below sees a stable input.
            Set<String> newClasses = new HashSet<>();
            Deque<String> worklist = new ArrayDeque<>(reachableClasses);
            Set<String> visited = new HashSet<>();

            while (!worklist.isEmpty()) {
                String cls = worklist.poll();
                if (!visited.add(cls)) continue;

                ClassNode cn = resolver.getClassNode(cls);
                if (cn == null) continue;

                String sup = cn.getSuperName();
                if (sup == null
                    || sup.isEmpty()
                    || sup.equals(cls)
                    || "java/lang/Object".equals(sup)) {
                    continue;
                }
                if (!reachableClasses.contains(sup)) {
                    newClasses.add(sup);
                }
                worklist.add(sup);
            }

            for (String cls : newClasses) {
                // addClassWithInit enqueues the <clinit> only when the class
                // actually declares one and its bytecode is available; the
                // call is otherwise a no-op.
                addClassWithInit(cls, false);
                changed = true;
            }

            if (changed) {
                drainWorklist();
                resolveVirtualDispatchFixedPoint();
            }
        }

        if (pass >= MAX_PASSES) {
            log.warn("Superclass-clinit closure did not converge after {} passes; "
                + "some class initializers may be missing from the image",
                MAX_PASSES);
        }
    }

    private void drainWorklist() {
        while (!worklist.isEmpty()) {
            MethodReference current = worklist.poll();
            processMethod(current);
        }
    }

    /**
     * Virtual-dispatch fixed point.
     *
     * <p>Virtual and interface dispatches are resolved against the set of
     * subclasses that are loaded and instantiated at the moment the
     * dispatching method is processed. The worklist is drained in
     * call-discovery order, not in class-load order, so a class may be
     * loaded only after an earlier method has already dispatched on its
     * abstract parent. The canonical example in the JDK's start-up
     * path:</p>
     *
     * <pre>
     *   Integer.parseInt(String,int)
     *     └─ Character.digit(char,int)
     *          └─ Character.digit(int,int)
     *               ├─ INVOKESTATIC  CharacterData.of(int)         (adds to worklist)
     *               └─ INVOKEVIRTUAL CharacterData.digit(int,int)  (resolved NOW)
     *
     *   CharacterData.of(int)   (processed LATER)
     *     └─ GETSTATIC CharacterDataLatin1.instance   (loads CharacterDataLatin1)
     * </pre>
     *
     * <p>When the INVOKEVIRTUAL is resolved, no subclass of
     * {@code CharacterData} has been loaded yet, so the resolution
     * contributes nothing. The class is loaded moments later, but the
     * dispatch site has already been dropped. At codegen time
     * {@code LlvmGlobalEmitter.resolveVtableEntry} finds the concrete
     * override via the resolver but cannot emit a call to it, because the
     * method was never translated into the module — the vtable slot is
     * left null and the eventual dispatch traps with
     * {@code __jnative_unresolved_slot}.</p>
     *
     * <p>This pass closes the loop. After the main worklist has drained
     * every method in {@link #reachableMethods} is scanned for
     * {@code INVOKEVIRTUAL}/{@code INVOKEINTERFACE} bytecodes; each unique
     * dispatch site is recorded in {@link #virtualDispatchSites}; every
     * site is re-resolved against the now-current subclass and
     * instantiation sets; any concrete override discovered this way is
     * added to the worklist and the worklist is drained. The cycle
     * repeats until neither a new dispatch site nor a new concrete target
     * is produced, which is guaranteed because both
     * {@link #reachableMethods} and the class map grow monotonically.</p>
     */
    private void resolveVirtualDispatchFixedPoint() {
        final int MAX_PASSES = 32;
        int pass = 0;

        while (pass++ < MAX_PASSES) {
            // Step 1: scan bytecode of every method not yet scanned for
            // virtual-dispatch sites. Methods are scanned exactly once.
            boolean scannedAny = false;
            for (MethodReference ref : new ArrayList<>(reachableMethods)) {
                if (!dispatchScanDone.add(ref)) continue;
                collectDispatchSitesFromMethod(ref);
                scannedAny = true;
            }

            // Step 2: re-resolve every known site against the current
            // class map and instantiation set. Any concrete override that
            // has become visible since the previous pass is added to the
            // worklist.
            boolean addedAny = false;
            for (String site : virtualDispatchSites) {
                if (resolveVirtualDispatchSite(site)) {
                    addedAny = true;
                }
            }

            // Step 3: drain the worklist. Newly added methods will have
            // their own bytecode scanned in the next iteration.
            drainWorklist();

            if (!scannedAny && !addedAny) {
                break;
            }
        }

        if (pass >= MAX_PASSES) {
            log.warn("Virtual-dispatch fixed point did not converge after {} passes; "
                + "some vtable slots may remain unresolved", MAX_PASSES);
        }
    }

    /**
     * Reads the bytecode of {@code ref} and records every
     * {@code INVOKEVIRTUAL}/{@code INVOKEINTERFACE} site it contains in
     * {@link #virtualDispatchSites}, keyed as
     * {@code "owner.name(descriptor)"}.
     *
     * <p>The visitor here is deliberately minimal: it only cares about the
     * opcode and the target of the dispatch. All other instruction kinds
     * are ignored, so the scan is cheap even for methods with large
     * bodies. A method whose bytecode is unavailable (a class loaded only
     * via reflection, with no cached {@code .class} payload) is silently
     * skipped — such a class has no compiled body anyway, so it cannot
     * contain a dispatch we would need to translate.</p>
     */
    private void collectDispatchSitesFromMethod(MethodReference ref) {
        byte[] bytes = resolver.getClassBytes(ref.getOwner());
        if (bytes == null) return;

        try {
            ClassReader reader = new ClassReader(bytes);
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String signature, String[] exceptions) {
                    if (!name.equals(ref.getName()) || !desc.equals(ref.getDescriptor())) {
                        return null;
                    }
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner,
                                                    String mName, String mDesc,
                                                    boolean isInterface) {
                            if (opcode == Opcodes.INVOKEVIRTUAL
                                || opcode == Opcodes.INVOKEINTERFACE) {
                                virtualDispatchSites.add(owner + "." + mName + mDesc);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (Exception e) {
            log.debug("Failed to scan {} for virtual-dispatch sites: {}",
                ref, e.getMessage());
        }
    }

    /**
     * Resolves a single dispatch site against the current subclass and
     * instantiation maps.
     *
     * <p>For every <em>instantiated</em> subtype of the receiver's declared
     * owner that provides a concrete (non-abstract) implementation of the
     * dispatched method, the implementation's declaring class is added to
     * the reachable set. The instantiation filter is the primary lever
     * against the reachability walk's pessimism on a large image: without
     * it, a dispatch on {@code Object} or on a broad interface expands
     * into every loaded implementation of that method, most of which
     * belong to JDK classes the walk incidentally loaded but that the
     * program's own code never instantiates.</p>
     *
     * <p>The owner's own implementation is deliberately not revisited
     * here. It was already added to the reachable set by
     * {@code MethodBytecodeVisitor.visitMethodInsn} at the moment the
     * dispatch site was first seen; re-adding it would be a no-op but
     * would obscure the fact that the deferred pass exists solely to pick
     * up overrides on subclasses.</p>
     *
     * @return {@code true} if at least one method was newly added to the
     *         worklist, {@code false} otherwise.
     */
    private boolean resolveVirtualDispatchSite(String site) {
        // The site key is "owner.name(desc)". Java class and method names
        // cannot contain '.', '(' or ')', so the last '.' before the '('
        // is unambiguously the separator between the owner and the method
        // name.
        int parenIdx = site.indexOf('(');
        if (parenIdx <= 0) return false;
        int lastDot = site.lastIndexOf('.', parenIdx);
        if (lastDot <= 0) return false;

        String owner = site.substring(0, lastDot);
        String name  = site.substring(lastDot + 1, parenIdx);
        String desc  = site.substring(parenIdx);

        Set<String> candidates = instantiatedSubclasses(owner);
        candidates.remove(owner);
        if (candidates.isEmpty()) return false;

        boolean added = false;
        for (String target : candidates) {
            String[] foundOwner = new String[1];
            MethodNode targetMethod = resolver.findMethodInHierarchy(
                target, name, desc, foundOwner);
            if (targetMethod == null || targetMethod.isAbstract()) continue;

            String declaring = foundOwner[0];
            if (declaring == null || declaring.equals(owner)) continue;

            MethodReference ref = new MethodReference(declaring, name, desc);
            if (!reachableMethods.contains(ref)) {
                addMethod(ref, true);
                added = true;
            }
        }
        return added;
    }

    private void seedBootstrapMethod(MethodReference ref) {
        boolean newlyAdded = reachableMethods.add(ref);
        boolean newlyMarkedUser = userReachableMethods.add(ref);

        if (newlyAdded || newlyMarkedUser) {
            worklist.add(ref);
        }

        reachableClasses.add(ref.getOwner());
        userReachedClasses.add(ref.getOwner());
    }

    private MethodNode findMethodInHierarchy(ClassNode classNode, String name, String desc, String[] foundClassName) {
        if (classNode == null) {
            return null;
        }

        for (MethodNode m : classNode.getMethods()) {
            if (m.getName().equals(name) && m.getDescriptor().equals(desc)) {
                if (foundClassName != null) foundClassName[0] = classNode.getName();
                return m;
            }
        }

        for (MethodNode m : classNode.getMethods()) {
            if (m.getName().equals(name) && m.isPolymorphicSignature()) {
                if (foundClassName != null) foundClassName[0] = classNode.getName();
                return m;
            }
        }

        String superName = classNode.getSuperName();
        if (superName != null && !superName.equals(classNode.getName())) {
            ClassNode superNode = resolver.getClassNode(superName);
            if (superNode != null && superNode != classNode) {
                MethodNode result = findMethodInHierarchy(superNode, name, desc, foundClassName);
                if (result != null) return result;
            }
        } else if (superName == null && !"java/lang/Object".equals(classNode.getName())) {
            ClassNode objectNode = resolver.getClassNode("java/lang/Object");
            if (objectNode != null && objectNode != classNode) {
                MethodNode result = findMethodInHierarchy(objectNode, name, desc, foundClassName);
                if (result != null) return result;
            }
        }

        for (String iface : classNode.getInterfaces()) {
            if (iface.equals(classNode.getName())) continue;
            ClassNode ifaceNode = resolver.getClassNode(iface);
            if (ifaceNode != null && ifaceNode != classNode) {
                MethodNode result = findMethodInHierarchy(ifaceNode, name, desc, foundClassName);
                if (result != null) return result;
            }
        }

        return null;
    }

    private void processMethod(MethodReference ref) {
        String owner = ref.getOwner();
        String name = ref.getName();
        String desc = ref.getDescriptor();

        boolean userReachable = userReachableMethods.contains(ref);
        addClass(owner, userReachable);

        ClassNode classNode = resolver.getClassNode(owner);

        // A class that came from reflection (isExternal == false) has no
        // bytecode. Force a reload in that case as well, so the method
        // bodies can actually be translated later.
        if (classNode.isExternal() || resolver.getClassBytes(owner) == null) {
            resolver.forceLoadSystemClass(owner);
            classNode = resolver.getClassNode(owner);
        }

        String[] actualOwnerHolder = new String[1];
        MethodNode method = findMethodInHierarchy(classNode, name, desc, actualOwnerHolder);

        if (method == null) {
            log.warn("Method not found in class {}: {}{}", owner, name, desc);
            return;
        }

        String actualOwner = actualOwnerHolder[0];
        if (!actualOwner.equals(owner)) {
            MethodReference actualRef = new MethodReference(actualOwner, name, desc);
            if (!reachableMethods.contains(actualRef)) {
                boolean isUser = userReachableMethods.contains(ref);
                addMethod(actualRef, isUser);
            }
            return;
        }

        if (method.isAbstract() || method.isNative()) {
            return;
        }

        boolean reachableFromUser = userReachableMethods.contains(ref);
        parseBytecode(actualOwner, name, desc, reachableFromUser, ref);
    }

    private void parseBytecode(String owner, String name, String desc,
                               boolean reachableFromUser, MethodReference caller) {
        byte[] bytes = resolver.getClassBytes(owner);
        if (bytes == null) {
            log.warn("No bytecode available for class {}", owner);
            return;
        }

        MethodReference currentMethod = new MethodReference(owner, name, desc);
        MethodBytecodeVisitor visitor = new MethodBytecodeVisitor(
            resolver,
            reachableClasses,
            reflectInfo,
            this,
            currentMethod,
            reachableFromUser,
            caller
        );
        visitor.parse(bytes);
    }

    void addClass(String className, boolean fromUser) {
        if (className == null || className.isEmpty()) return;
        reachableClasses.add(className);
        if (fromUser) {
            userReachedClasses.add(className);
        }
    }

    /**
     * Marks a class as initialized and enqueues its {@code <clinit>} for
     * analysis when the class declares one and bytecode is available.
     *
     * <p>A class previously resolved via reflection does not expose
     * {@code <clinit>} through {@link ClassNode#getMethods()} and has no
     * cached {@code classBytes}. Such a {@link ClassNode} is reloaded from
     * its actual {@code .class} payload before the {@code <clinit>} check,
     * so the initializer becomes visible and can be translated.</p>
     *
     * <p>Before the {@code <clinit>} handling below, this method also runs
     * {@link #ensureEnumMethodsReachable}: an enum class's compiler-
     * generated {@code values()} and {@code valueOf(String)} methods are
     * not reachable through any ordinary bytecode path, but they are
     * needed by {@code Class.getEnumConstantsShared()} (via the synthetic
     * dispatcher emitted by {@code EnumConstantsSharedEmitter}) and by
     * {@code Enum.valueOf}. See that method for the full rationale.</p>
     */
    void addClassWithInit(String className, boolean fromUser) {
        if (className == null || className.isEmpty()) return;

        reachableClasses.add(className);
        if (fromUser) {
            userReachedClasses.add(className);
        }

        // Enum values()/valueOf(String) force. Runs before the clinit
        // short-circuit so it executes exactly once per class, even on
        // the many repeat calls that a class receives when it is used
        // from several distinct bytecode sites.
        ensureEnumMethodsReachable(className, fromUser);

        if (clinitProcessed.contains(className)) return;
        clinitProcessed.add(className);

        ClassNode cn = resolver.getClassNode(className);
        if (cn == null || cn.isInterface()) return;

        if (!cn.isExternal() && resolver.getClassBytes(className) == null) {
            resolver.reloadSystemClass(className);
            cn = resolver.getClassNode(className);
            if (cn == null || cn.isInterface()) return;
        }

        if (cn.isExternal()) {
            resolver.forceLoadSystemClass(className);
            cn = resolver.getClassNode(className);
            if (cn == null || cn.isInterface()) return;
        }

        boolean hasClinit = false;
        for (MethodNode mn : cn.getMethods()) {
            if (mn.getName().equals("<clinit>")) {
                hasClinit = true;
                break;
            }
        }
        if (!hasClinit) return;

        if (resolver.getClassBytes(className) == null) return;

        addMethod(new MethodReference(className, "<clinit>", "()V"), fromUser);
    }

    /**
     * For an enum class, ensures that its compiler-generated
     * {@code values()} and {@code valueOf(String)} static methods are
     * placed in the reachable set.
     *
     * <p>The JDK's {@code Class.getEnumConstantsShared()} consults the
     * enum's {@code values()} method. In the reference implementation this
     * happens reflectively; in this runtime it happens through the
     * synthetic dispatcher emitted by
     * {@code io.github.kubyk01.application.service.analyzer.ssa.EnumConstantsSharedEmitter}.
     * Either way the target function must exist in the module, and nothing
     * in an enum's own bytecode — including its {@code <clinit>} — ever
     * calls {@code values()}. Without this rule the method is never
     * translated, and every enum-consuming JDK path that reaches
     * {@code Class.getEnumConstantsShared} (EnumMap, EnumSet,
     * {@code Enum.valueOf}, {@code Class.getEnumConstants}) observes
     * {@code null}.</p>
     *
     * <p>{@code valueOf(String)} is forced for the same reason: it is the
     * mirror of {@code values()} in the JDK's enum machinery, and forcing
     * only one of the pair would leave {@code Enum.valueOf} broken for the
     * same classes.</p>
     *
     * <p>The rule is a one-time operation per class, guarded by
     * {@link #enumMethodsProcessed}, because {@code addClassWithInit} can
     * be invoked many times for the same class from different bytecode
     * sites.</p>
     */
    private void ensureEnumMethodsReachable(String className, boolean fromUser) {
        if (!enumMethodsProcessed.add(className)) return;

        ClassNode cn = resolver.getClassNode(className);
        if (cn == null || cn.isExternal()) return;

        // JVMS 4.1: an enum class carries ACC_ENUM in its access flags
        // and has java.lang.Enum as its direct superclass. Both
        // conditions are checked, mirroring Class.isEnum().
        final int ACC_ENUM = 0x4000;
        if ((cn.getAccess() & ACC_ENUM) == 0) return;
        if (!"java/lang/Enum".equals(cn.getSuperName())) return;

        for (MethodNode mn : cn.getMethods()) {
            if (!mn.isStatic()) continue;

            String name = mn.getName();
            String desc = mn.getDescriptor();

            boolean isValues =
                "values".equals(name)
                    && desc.startsWith("()[L");

            boolean isValueOf =
                "valueOf".equals(name)
                    && desc.startsWith("(Ljava/lang/String;)L");

            if (isValues || isValueOf) {
                addMethod(new MethodReference(className, name, desc), fromUser);
            }
        }
    }

    public void triggerClinit(String className, boolean fromUser) {
        addClassWithInit(className, fromUser);
    }

    void addMethod(MethodReference ref, boolean isUser) {
        if (reachableMethods.add(ref)) {
            worklist.add(ref);
            if (isUser) {
                userReachableMethods.add(ref);
            }
            addClass(ref.getOwner(), isUser);
        } else if (isUser && !userReachableMethods.contains(ref)) {
            userReachableMethods.add(ref);
            addClass(ref.getOwner(), true);
        }
    }

    void addMethodWithContext(MethodReference ref, boolean isUser, MethodReference caller) {
        if (caller != null) {
            callGraph.computeIfAbsent(caller, k -> new HashSet<>()).add(ref);
        }
        addMethod(ref, isUser);
    }

    void addTypeFromDescriptor(String desc, boolean fromUser) {
        if (desc == null) return;
        if (desc.startsWith("L") && desc.endsWith(";")) {
            addClass(desc.substring(1, desc.length() - 1), fromUser);
        } else if (desc.startsWith("[")) {
            String elem = desc;
            while (elem.startsWith("[")) elem = elem.substring(1);
            if (elem.startsWith("L") && elem.endsWith(";")) {
                addClass(elem.substring(1, elem.length() - 1), fromUser);
            }
        }
    }
}