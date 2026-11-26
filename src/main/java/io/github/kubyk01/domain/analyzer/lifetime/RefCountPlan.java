package io.github.kubyk01.domain.analyzer.lifetime;

import lombok.Getter;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Getter
public final class RefCountPlan {

    public static final String COUNT_FIELD = "$jnrc";

    private final Set<String> countedClasses = new LinkedHashSet<>();
    private final Set<String> countDeclarers = new HashSet<>();
    private final Map<String, Set<String>> countedFields = new HashMap<>();
    private final Set<String> countedArrayTypes = new HashSet<>();
    private final Set<String> cyclicClasses = new HashSet<>();
    private final Map<String, Set<String>> ownedArrayFields = new java.util.concurrent.ConcurrentHashMap<>();

    public boolean isCounted(String className) {
        return countedClasses.contains(className);
    }

    public boolean declaresCount(String className) {
        return countDeclarers.contains(className);
    }

    public static String symbolName(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean keep = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '$';
            sb.append(keep ? c : '_');
        }
        return sb.toString();
    }
}
