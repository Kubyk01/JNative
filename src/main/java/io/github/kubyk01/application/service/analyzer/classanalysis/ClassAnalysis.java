package io.github.kubyk01.application.service.analyzer.classanalysis;

import io.github.kubyk01.application.service.analyzer.classanalysis.annotation.ClassFileAnnotations;
import io.github.kubyk01.application.service.analyzer.classanalysis.annotation.ProxyClassGenerator;
import io.github.kubyk01.application.service.analyzer.classanalysis.annotation.runtime.JNativeProxySupport;
import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldReference;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodReference;
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
import io.github.kubyk01.util.LlvmUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.nio.charset.StandardCharsets;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Slf4j
@RequiredArgsConstructor
public class ClassAnalysis {

    private static final String SPRING_DATA_REPOSITORY = "org/springframework/data/repository/Repository";
    private static final String SPRING_TRANSACTIONAL_PROXY = "org/springframework/transaction/interceptor/TransactionalProxy";
    private static final String SPRING_ADVISED = "org/springframework/aop/framework/Advised";
    private static final String SPRING_DECORATING_PROXY = "org/springframework/core/DecoratingProxy";
    private static final Map<String, List<String>> FRAMEWORK_PROXIES = Map.of(
        "org/springframework/r2dbc/core/DefaultDatabaseClient",
        List.of("io/r2dbc/spi/Connection", "io/r2dbc/spi/Wrapped"));

    private final DependencyResolver resolver;

    @Getter
    private final Set<String> classes = new HashSet<>();

    @Getter
    private final Set<MethodReference> methods = new HashSet<>();

    @Getter
    private final ReflectInfo reflectInfo = new ReflectInfo();

    public void analyze() {
        resolver.loadAllClasses();
        resolver.getClassNode(JNativeProxySupport.class.getName().replace('.', '/'));
        List<String> loaded = new ArrayList<>(resolver.getAllClasses());
        loaded.forEach(this::register);

        List<? extends String> jnLoop1 = loaded;
        for (int jnIdx1 = 0; jnIdx1 < jnLoop1.size(); jnIdx1++) {
            String name = jnLoop1.get(jnIdx1);
            if (classes.contains(name) && (resolver.getClassNode(name).getAccess() & Opcodes.ACC_ANNOTATION) != 0) {
                addProxy(List.of(name));
            }
        }
        loaded.forEach(this::addCallSiteProxies);
        List<? extends List<String>> jnLoop2 = resolver.getProxyInterfaces();
        for (int jnIdx2 = 0; jnIdx2 < jnLoop2.size(); jnIdx2++) {
            List<String> interfaces = jnLoop2.get(jnIdx2);
            addProxy(interfaces.stream().map(i -> i.replace('.', '/')).toList());
        }
        FRAMEWORK_PROXIES.forEach((trigger, interfaces) -> {
            if (classes.contains(trigger)) addProxy(interfaces);
        });
        if (classes.contains(SPRING_DATA_REPOSITORY)) {
            for (String repo : resolver.getSubclasses(SPRING_DATA_REPOSITORY)) {
                if (repo.startsWith("org/springframework/data/") || !resolver.getClassNode(repo).isInterface()) continue;
                if (classes.contains(SPRING_TRANSACTIONAL_PROXY)) {
                    addProxy(List.of(repo, SPRING_DATA_REPOSITORY, SPRING_TRANSACTIONAL_PROXY,
                        SPRING_ADVISED, SPRING_DECORATING_PROXY));
                }
                addProxy(List.of(repo, SPRING_DATA_REPOSITORY, SPRING_ADVISED, SPRING_DECORATING_PROXY));
            }
        }
        log.info("Class analysis complete. Classes: {}, methods: {}", classes.size(), methods.size());
    }

    public void define(String internalName, byte[] bytes) {
        resolver.defineSyntheticClass(internalName, bytes);
        register(internalName);
    }

    public List<String> annotatedApplicationClasses() {
        List<String> out = new ArrayList<>();
        for (String className : new TreeSet<>(classes)) {
            if (LlvmUtil.isSystemClassName(className)) continue;
            ClassFileAnnotations annotations = ClassFileAnnotations.parse(resolver.getClassBytes(className));
            if (annotations != null && annotations.getClassAnnotations() != null) out.add(className);
        }
        return out;
    }

    public Set<String> lazyOnlyClasses(String... eagerRoots) {
        Set<String> lazy = new HashSet<>(classes);
        Deque<String> work = new ArrayDeque<>(List.of(eagerRoots));
        while (!work.isEmpty()) {
            String name = work.poll();
            if (!lazy.remove(name)) continue;
            byte[] bytes = resolver.getClassBytes(name);
            ClassReader reader = new ClassReader(bytes);
            char[] buffer = new char[reader.getMaxStringLength()];
            for (int i = 1; i < reader.getItemCount(); i++) {
                int offset = reader.getItem(i);
                if (offset == 0 || bytes[offset - 1] != 7) continue;
                String ref = reader.readUTF8(offset, buffer);
                work.add(ref.charAt(0) == '['
                    ? ref.substring(ref.lastIndexOf('[') + 1).replaceAll("^L|;$", "")
                    : ref);
            }
        }
        return lazy;
    }

    public void registerClassLiterals(Module module) {
        List<? extends Function> jnLoop5 = module.getFunctions();
        for (int jnIdx5 = 0; jnIdx5 < jnLoop5.size(); jnIdx5++) {
            Function func = jnLoop5.get(jnIdx5);
            if (func.getEntryBlock() == null) continue;
            for (int blockIdx1 = 0; blockIdx1 < func.getBlocks().size(); blockIdx1++) { BasicBlock block = func.getBlocks().get(blockIdx1);
                for (int instIdx2 = 0; instIdx2 < block.getInstructions().size(); instIdx2++) { Instruction inst = block.getInstructions().get(instIdx2);
                    inst.getOperands().forEach(this::registerClassLiteral);
                }
                Terminator term = block.getTerminator();
                if (term instanceof ReturnTerminator rt) registerClassLiteral(rt.getValue());
                else if (term instanceof ThrowTerminator tt) registerClassLiteral(tt.getException());
                else if (term instanceof CondBranchTerminator cbt) registerClassLiteral(cbt.getCondition());
                else if (term instanceof LookupSwitchTerminator lst) registerClassLiteral(lst.getKey());
                else if (term instanceof TableSwitchTerminator tst) registerClassLiteral(tst.getKey());
                else if (term instanceof IndirectBranchTerminator ibt) registerClassLiteral(ibt.getTargetBlock());
            }
        }
    }

    private void registerClassLiteral(Value value) {
        if (value instanceof Constant c && c.getType().isReference()
                && "java/lang/Class".equals(c.getType().getClassName())
                && c.getValue() instanceof String name && !name.isEmpty()) {
            reflectInfo.getOrCreateClassInfo(name);
        }
    }

    private void register(String name) {
        if (name.isEmpty() || name.charAt(0) == '[' || name.endsWith("module-info")) return;
        ClassNode cn = resolver.getClassNode(name);
        if (cn.isExternal() || resolver.getClassBytes(name) == null || !classes.add(name)) return;

        ReflectClassInfo info = reflectInfo.getOrCreateClassInfo(name);
        info.setSuperName(cn.getSuperName());
        info.getInterfaces().addAll(cn.getInterfaces());
        List<? extends FieldNode> jnLoop6 = cn.getFields();
        for (int jnIdx6 = 0; jnIdx6 < jnLoop6.size(); jnIdx6++) {
            FieldNode f = jnLoop6.get(jnIdx6);
            info.addField(new FieldReference(name, f.getName(), f.getDescriptor()));
        }
        List<? extends MethodNode> jnLoop7 = cn.getMethods();
        for (int jnIdx7 = 0; jnIdx7 < jnLoop7.size(); jnIdx7++) {
            MethodNode m = jnLoop7.get(jnIdx7);
            MethodReference ref = new MethodReference(name, m.getName(), m.getDescriptor());
            if (!m.isAbstract()) methods.add(ref);
            if (m.getName().equals("<init>")) info.addConstructor(ref);
            else if (!m.getName().equals("<clinit>")) info.addMethod(ref);
        }
    }

    private void addCallSiteProxies(String className) {
        byte[] bytes = resolver.getClassBytes(className);
        if (bytes == null || !new String(bytes, StandardCharsets.ISO_8859_1).contains("newProxyInstance")) return;
        org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        List<List<String>> sites = new ArrayList<>();
        Set<String> staticTypes = new LinkedHashSet<>();
        List<? extends org.objectweb.asm.tree.MethodNode> jnLoop8 = cn.methods;
        for (int jnIdx8 = 0; jnIdx8 < jnLoop8.size(); jnIdx8++) {
            org.objectweb.asm.tree.MethodNode mn = jnLoop8.get(jnIdx8);
            List<String> array = null;
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn.getOpcode() == Opcodes.ANEWARRAY && "java/lang/Class".equals(((TypeInsnNode) insn).desc)) {
                    array = new ArrayList<>();
                } else if (insn.getOpcode() == Opcodes.AASTORE && array != null) {
                    String literal = insn.getPrevious() instanceof LdcInsnNode ldc && ldc.cst instanceof Type t
                        && t.getSort() == Type.OBJECT ? t.getInternalName() : null;
                    array.add(literal);
                    if (literal != null && mn.name.equals("<clinit>")) staticTypes.add(literal);
                } else if (insn instanceof MethodInsnNode call && array != null
                        && call.owner.equals("java/lang/reflect/Proxy") && call.name.equals("newProxyInstance")) {
                    sites.add(array);
                    array = null;
                }
            }
        }
        List<? extends List<String>> jnLoop10 = sites;
        for (int jnIdx10 = 0; jnIdx10 < jnLoop10.size(); jnIdx10++) {
            List<String> site = jnLoop10.get(jnIdx10);
            List<List<String>> combos = List.of(List.of());
            List<? extends String> jnLoop11 = site;
            for (int jnIdx11 = 0; jnIdx11 < jnLoop11.size(); jnIdx11++) {
                String element = jnLoop11.get(jnIdx11);
                List<List<String>> next = new ArrayList<>();
                List<? extends List<String>> jnLoop12 = combos;
                for (int jnIdx12 = 0; jnIdx12 < jnLoop12.size(); jnIdx12++) {
                    List<String> combo = jnLoop12.get(jnIdx12);
                    for (String candidate : element != null ? List.of(element) : staticTypes) {
                        if (combo.contains(candidate)) continue;
                        List<String> extended = new ArrayList<>(combo);
                        extended.add(candidate);
                        next.add(extended);
                    }
                }
                combos = next.size() > 64 ? List.of() : next;
            }
            List<? extends List<String>> jnLoop14 = combos;
            for (int jnIdx14 = 0; jnIdx14 < jnLoop14.size(); jnIdx14++) {
                List<String> combo = jnLoop14.get(jnIdx14);
                if (!combo.isEmpty() && combo.stream().allMatch(this::isInterface)) addProxy(combo);
            }
        }
    }

    private boolean isInterface(String name) {
        ClassNode cn = resolver.getClassNode(name);
        return cn != null && cn.isInterface();
    }

    private void addProxy(List<String> interfaces) {
        try {
            ProxyClassGenerator.GeneratedProxy proxy = ProxyClassGenerator.generate(interfaces, resolver);
            define(proxy.internalName(), proxy.bytes());
        } catch (RuntimeException e) {
            log.warn("Cannot generate proxy class for {}: {}", interfaces, e.getMessage());
        }
    }
}
