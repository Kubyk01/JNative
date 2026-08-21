package io.github.kubyk01.application.service.codegen.llvm;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldNode;
import io.github.kubyk01.domain.analyzer.ir.Module;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class LlvmGlobalEmitter {

    private final Module module;
    private final DependencyResolver resolver;
    private final AliasAnalysisResult aliasResult;
    private final LlvmTypeMapper typeMapper;

    private final Map<String, String> structNames = new HashMap<>();
    private final Map<String, Integer> fieldOffsets = new HashMap<>(); // class.field -> offset in bytes

    public String generateGlobals() {
        StringBuilder sb = new StringBuilder();

        // 1. Struct definitions for all classes (including the hierarchy)
        sb.append(generateStructs());

        // 2. Global variables for static fields
        sb.append(generateStaticFields());

        return sb.toString();
    }

    private String generateStructs() {
        StringBuilder sb = new StringBuilder();
        // Collect all classes from the resolver
        for (ClassNode cls : resolver.getClassMap().values()) {
            if (cls.isExternal()) continue;
            String structName = typeMapper.toLlvmStruct(cls.getName());
            sb.append(structName).append(" = type { ");
            List<String> fieldTypes = new ArrayList<>();

            // Superclass fields (if any)
            if (cls.getSuperName() != null && !cls.getSuperName().equals("java/lang/Object")) {
                // For simplicity the superclass is not included yet, but in reality its fields
                // should be added. The superclass fields can be obtained recursively here.
                // Skipped for brevity, to be implemented later
            }

            // Fields of the current class
            for (FieldNode field : cls.getFields()) {
                String llvmType = typeMapper.toLlvmType(io.github.kubyk01.domain.analyzer.ir.Type.REFERENCE);
                // Determine the type from the descriptor (simplified)
                if (field.getDescriptor().startsWith("L") || field.getDescriptor().startsWith("[")) {
                    llvmType = "i8*";
                } else if (field.getDescriptor().equals("I")) {
                    llvmType = "i32";
                } // etc. - full mapping omitted for brevity
                fieldTypes.add(llvmType);
            }

            sb.append(String.join(", ", fieldTypes));
            sb.append(" }\n");
            structNames.put(cls.getName(), structName);
        }
        sb.append("\n");
        return sb.toString();
    }

    private String generateStaticFields() {
        StringBuilder sb = new StringBuilder();
        Map<String, PointsToSet> staticFields = aliasResult.getGraph().getStaticFieldPointsToMap();

        for (Map.Entry<String, PointsToSet> entry : staticFields.entrySet()) {
            String fieldName = entry.getKey(); // full name, e.g. "java/lang/System.out"
            // Determine the type: all references are i8* for now
            String llvmType = "i8*";
            String globalName = "gv_" + fieldName.replace('.', '_').replace('/', '_');
            sb.append("@").append(globalName).append(" = global ").append(llvmType)
                    .append(" null, align 8\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    public String getStructName(String className) {
        return structNames.getOrDefault(className, typeMapper.toLlvmStruct(className));
    }

    public int getFieldOffset(String className, String fieldName) {
        // Compute the offset by field order (simplified, no alignment handling)
        String key = className + "." + fieldName;
        return fieldOffsets.computeIfAbsent(key, k -> {
            ClassNode cls = resolver.getClassNode(className);
            int offset = 0;
            if (cls == null) return 0;
            for (FieldNode f : cls.getFields()) {
                if (f.getName().equals(fieldName)) break;
                offset += 8; // all references and int are 8 bytes for simplicity
            }
            return offset;
        });
    }
}
