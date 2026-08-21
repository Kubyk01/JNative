package io.github.kubyk01.application.service.inspector;

import io.github.kubyk01.domain.inspector.ClassInfo;
import io.github.kubyk01.domain.inspector.FieldInfo;
import io.github.kubyk01.domain.inspector.InspectionResult;
import io.github.kubyk01.domain.inspector.MethodInfo;
import io.github.kubyk01.port.primary.InspectorPort;
import lombok.RequiredArgsConstructor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

@RequiredArgsConstructor
public class Inspector implements InspectorPort {

    @Override
    public Mono<InspectionResult> inspectJar(Path jarPath) {
        return Mono.fromRunnable(() -> {
            System.out.println("JAR: " + jarPath);
            System.out.println("\nClasses:");
            try (JarFile jar = new JarFile(jarPath.toFile())) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.getName().endsWith(".class")) {
                        try (var is = jar.getInputStream(entry)) {
                            ClassReader reader = new ClassReader(is);
                            ClassInfo info = extractClassInfo(reader, false);
                            System.out.println("  " + info.getClassName());
                        } catch (Exception e) {
                            System.err.println("  Error parsing " + entry.getName() + ": " + e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public Mono<InspectionResult> inspectClass(Path classPath, boolean showBytecode) {
        return Mono.fromRunnable(() -> {
            try {
                byte[] bytes = Files.readAllBytes(classPath);
                ClassReader reader = new ClassReader(bytes);
                ClassInfo info = extractClassInfo(reader, showBytecode);
                System.out.println("Class: " + info.getClassName());
                System.out.println("  Super: " + info.getSuperName());
                System.out.println("  Fields:");
                for (FieldInfo f : info.getFields()) {
                    System.out.println("    " + f.getDescriptor() + " " + f.getName());
                }
                System.out.println("  Methods:");
                for (MethodInfo m : info.getMethods()) {
                    System.out.println("    " + m.getDescriptor() + " " + m.getName());
                    if (showBytecode && m.getBytecode() != null && !m.getBytecode().isEmpty()) {
                        System.out.println("      Bytecode:");
                        String[] lines = m.getBytecode().split("\n");
                        for (int i = 0; i < lines.length; i++) {
                            System.out.println("        " + i + ": " + lines[i]);
                        }
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static ClassInfo extractClassInfo(ClassReader reader, boolean showBytecode) {
        var classInfoBuilder = ClassInfo.builder();
        List<FieldInfo> fields = new ArrayList<>();
        List<MethodInfo> methods = new ArrayList<>();

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                classInfoBuilder.className(name.replace('/', '.'));
                classInfoBuilder.superName(superName != null ? superName.replace('/', '.') : "java.lang.Object");
            }

            @Override
            public org.objectweb.asm.FieldVisitor visitField(int access, String name,
                                                             String descriptor, String signature, Object value) {
                fields.add(FieldInfo.builder().name(name).descriptor(descriptor).build());
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodInfo mi = MethodInfo.builder()
                    .name(name)
                    .descriptor(descriptor)
                    .build();
                methods.add(mi);

                if (showBytecode) {
                    Textifier textifier = new Textifier();
                    MethodVisitor traceVisitor = new TraceMethodVisitor(null, textifier);
                    return new MethodVisitor(Opcodes.ASM9, traceVisitor) {
                        @Override
                        public void visitEnd() {
                            StringWriter sw = new StringWriter();
                            PrintWriter pw = new PrintWriter(sw);
                            for (Object o : textifier.getText()) {
                                pw.print(o.toString());
                            }
                            pw.flush();
                            mi.setBytecode(sw.toString());
                            super.visitEnd();
                        }
                    };
                } else {
                    return new MethodVisitor(Opcodes.ASM9) {};
                }
            }
        }, ClassReader.SKIP_DEBUG | (showBytecode ? 0 : ClassReader.SKIP_CODE));

        return classInfoBuilder
            .fields(fields)
            .methods(methods)
            .build();
    }
}