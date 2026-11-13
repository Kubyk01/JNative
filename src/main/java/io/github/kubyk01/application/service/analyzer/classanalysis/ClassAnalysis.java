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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
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

        for (String name : loaded) {
            if (classes.contains(name) && (resolver.getClassNode(name).getAccess() & Opcodes.ACC_ANNOTATION) != 0) {
                addProxy(List.of(name));
            }
        }
        for (List<String> interfaces : resolver.getProxyInterfaces()) {
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
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() == null) continue;
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
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
        for (FieldNode f : cn.getFields()) {
            info.addField(new FieldReference(name, f.getName(), f.getDescriptor()));
        }
        for (MethodNode m : cn.getMethods()) {
            MethodReference ref = new MethodReference(name, m.getName(), m.getDescriptor());
            if (!m.isAbstract()) methods.add(ref);
            if (m.getName().equals("<init>")) info.addConstructor(ref);
            else if (!m.getName().equals("<clinit>")) info.addMethod(ref);
        }
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
