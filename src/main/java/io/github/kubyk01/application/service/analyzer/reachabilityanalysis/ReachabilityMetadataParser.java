package io.github.kubyk01.application.service.analyzer.reachabilityanalysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kubyk01.domain.analyzer.reachability.ReachabilityMetadata;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
public class ReachabilityMetadataParser {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static ReachabilityMetadata parse(InputStream jsonStream) throws IOException {
        return parse(jsonStream, null);
    }

    /**
     * @param fileName optional name of the metadata file (e.g. "reflect-config.json")
     *                 used to dispatch entries precisely; heuristics are used when null.
     */
    public static ReachabilityMetadata parse(InputStream jsonStream, String fileName) throws IOException {
        JsonNode root = mapper.readTree(jsonStream);
        List<ReachabilityMetadata.ReflectClass> reflectClasses = new ArrayList<>();
        List<ReachabilityMetadata.ProxyInterface> proxyInterfaces = new ArrayList<>();
        List<ReachabilityMetadata.Resource> resources = new ArrayList<>();
        List<ReachabilityMetadata.JniClass> jniClasses = new ArrayList<>();

        if (root.isArray()) {
            for (JsonNode node : root) {
                parseNode(node, reflectClasses, proxyInterfaces, resources, jniClasses, fileName);
            }
        } else if (root.isObject()) {
            if (root.has("reflectClasses")) {
                for (JsonNode n : root.get("reflectClasses")) parseReflectClass(n, reflectClasses);
            }
            if (root.has("proxyInterfaces")) {
                for (JsonNode n : root.get("proxyInterfaces")) parseProxyInterface(n, proxyInterfaces);
            }
            if (root.has("jniClasses")) {
                for (JsonNode n : root.get("jniClasses")) parseJniClass(n, jniClasses);
            }
            if (root.has("resources")) {
                parseResourcesContainer(root.get("resources"), resources);
            }
        }

        return ReachabilityMetadata.builder()
            .reflectClasses(reflectClasses)
            .proxyInterfaces(proxyInterfaces)
            .resources(resources)
            .jniClasses(jniClasses)
            .build();
    }

    private static void parseNode(JsonNode node,
                                  List<ReachabilityMetadata.ReflectClass> reflectClasses,
                                  List<ReachabilityMetadata.ProxyInterface> proxyInterfaces,
                                  List<ReachabilityMetadata.Resource> resources,
                                  List<ReachabilityMetadata.JniClass> jniClasses,
                                  String fileName) {
        if (fileName != null) {
            String lower = fileName.toLowerCase();
            if (lower.contains("proxy-config")) {
                parseProxyInterface(node, proxyInterfaces);
                return;
            }
            if (lower.contains("resource-config")) {
                parseResource(node, resources);
                return;
            }
            if (lower.contains("jni-config")) {
                parseJniClass(node, jniClasses);
                return;
            }
            if (lower.contains("reflect-config")) {
                parseReflectClass(node, reflectClasses);
                return;
            }
        }
        // Heuristic for unknown files
        if (node.has("name") && (node.has("methods") || node.has("fields")
                || node.has("constructors") || hasAnyFlag(node))) {
            parseReflectClass(node, reflectClasses);
        } else if (node.has("interfaces")) {
            parseProxyInterface(node, proxyInterfaces);
        } else if (node.has("pattern") || node.has("includes") || node.has("excludes")) {
            parseResource(node, resources);
        } else if (node.has("name")) {
            parseJniClass(node, jniClasses);
        }
    }

    private static boolean hasAnyFlag(JsonNode node) {
        return node.has("allDeclaredMethods") || node.has("allDeclaredFields")
                || node.has("allDeclaredConstructors") || node.has("allPublicMethods")
                || node.has("allPublicFields") || node.has("allPublicConstructors")
                || node.has("queryAllDeclaredMethods") || node.has("queryAllPublicMethods");
    }

    private static void parseReflectClass(JsonNode node, List<ReachabilityMetadata.ReflectClass> out) {
        if (!node.has("name")) return;
        Set<String> methods = new HashSet<>();
        Set<String> fields = new HashSet<>();
        Set<String> constructors = new HashSet<>();

        if (node.has("methods")) {
            for (JsonNode m : node.get("methods")) {
                String methodName = m.has("name") ? m.get("name").asText() : "";
                StringBuilder sig = new StringBuilder(methodName).append('(');
                appendParams(sig, m);
                sig.append(')');
                methods.add(sig.toString());
            }
        }
        if (node.has("fields")) {
            for (JsonNode f : node.get("fields")) {
                if (f.has("name")) fields.add(f.get("name").asText());
            }
        }
        if (node.has("constructors")) {
            for (JsonNode c : node.get("constructors")) {
                StringBuilder sig = new StringBuilder("(");
                appendParams(sig, c);
                sig.append(')');
                constructors.add(sig.toString());
            }
        }

        out.add(ReachabilityMetadata.ReflectClass.builder()
            .name(node.get("name").asText())
            .methods(methods)
            .fields(fields)
            .constructors(constructors)
            .allDeclaredMethods(flag(node, "allDeclaredMethods"))
            .allDeclaredFields(flag(node, "allDeclaredFields"))
            .allDeclaredConstructors(flag(node, "allDeclaredConstructors"))
            .allPublicMethods(flag(node, "allPublicMethods"))
            .allPublicFields(flag(node, "allPublicFields"))
            .allPublicConstructors(flag(node, "allPublicConstructors"))
            .build());
    }

    private static void appendParams(StringBuilder sig, JsonNode member) {
        if (member.has("parameterTypes")) {
            boolean first = true;
            for (JsonNode p : member.get("parameterTypes")) {
                if (!first) sig.append(',');
                first = false;
                sig.append(p.asText());
            }
        }
    }

    private static boolean flag(JsonNode node, String key) {
        return node.has(key) && node.get(key).asBoolean();
    }

    private static void parseProxyInterface(JsonNode node, List<ReachabilityMetadata.ProxyInterface> out) {
        if (!node.has("interfaces")) return;
        Set<String> interfaces = new HashSet<>();
        for (JsonNode iface : node.get("interfaces")) {
            interfaces.add(iface.asText());
        }
        out.add(ReachabilityMetadata.ProxyInterface.builder()
            .interfaces(interfaces)
            .build());
    }

    private static void parseResource(JsonNode node, List<ReachabilityMetadata.Resource> out) {
        ReachabilityMetadata.Resource.ResourceBuilder rb = ReachabilityMetadata.Resource.builder();
        if (node.has("pattern")) rb.pattern(node.get("pattern").asText());
        if (node.has("includes")) rb.includes(node.get("includes").asText());
        if (node.has("excludes")) rb.excludes(node.get("excludes").asText());
        out.add(rb.build());
    }

    /**
     * Handles the standard resource-config.json shape:
     * {"resources": {"includes": [{"pattern": "..."}], "excludes": [{"pattern": "..."}]}}
     */
    private static void parseResourcesContainer(JsonNode container, List<ReachabilityMetadata.Resource> out) {
        if (container.isArray()) {
            for (JsonNode n : container) parseResource(n, out);
            return;
        }
        if (container.isObject()) {
            if (container.has("includes")) {
                for (JsonNode n : container.get("includes")) parseResource(n, out);
            }
            if (container.has("excludes")) {
                for (JsonNode n : container.get("excludes")) {
                    out.add(ReachabilityMetadata.Resource.builder()
                        .pattern(n.has("pattern") ? n.get("pattern").asText() : null)
                        .excludes(n.has("pattern") ? n.get("pattern").asText() : null)
                        .build());
                }
            }
        }
    }

    private static void parseJniClass(JsonNode node, List<ReachabilityMetadata.JniClass> out) {
        if (!node.has("name")) return;
        Set<String> methods = new HashSet<>();
        Set<String> fields = new HashSet<>();

        if (node.has("methods")) {
            for (JsonNode m : node.get("methods")) {
                if (!m.has("name")) continue;
                StringBuilder sig = new StringBuilder(m.get("name").asText()).append('(');
                appendParams(sig, m);
                sig.append(')');
                methods.add(sig.toString());
            }
        }
        if (node.has("fields")) {
            for (JsonNode f : node.get("fields")) {
                if (f.has("name")) fields.add(f.get("name").asText());
            }
        }
        out.add(ReachabilityMetadata.JniClass.builder()
            .name(node.get("name").asText())
            .methods(methods)
            .fields(fields)
            .build());
    }
}
