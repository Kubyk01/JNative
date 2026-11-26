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

    public static final Set<String> WEAK_CACHE_FIELDS = Set.of(
        "java/util/AbstractMap.keySet",
        "java/util/AbstractMap.values",
        "java/util/HashMap.entrySet",
        "java/util/TreeMap.entrySet",
        "java/util/TreeMap.navigableKeySet",
        "java/util/TreeMap.descendingMap",
        "java/util/TreeMap$NavigableSubMap.entrySetView",
        "java/util/TreeMap$NavigableSubMap.navigableKeySetView",
        "java/util/TreeMap$NavigableSubMap.descendingMapView",
        "java/util/IdentityHashMap.entrySet",
        "java/util/WeakHashMap.entrySet",
        "java/util/EnumMap.entrySet",
        "java/util/Hashtable.keySet",
        "java/util/Hashtable.entrySet",
        "java/util/Hashtable.values",
        "java/util/Collections$SynchronizedMap.keySet",
        "java/util/Collections$SynchronizedMap.entrySet",
        "java/util/Collections$SynchronizedMap.values",
        "java/util/Collections$UnmodifiableMap.keySet",
        "java/util/Collections$UnmodifiableMap.entrySet",
        "java/util/Collections$UnmodifiableMap.values",
        "java/util/Collections$CheckedMap.entrySet",
        "java/util/concurrent/ConcurrentHashMap.keySet",
        "java/util/concurrent/ConcurrentHashMap.values",
        "java/util/concurrent/ConcurrentHashMap.entrySet",
        "java/util/concurrent/ConcurrentSkipListMap.keySet",
        "java/util/concurrent/ConcurrentSkipListMap.values",
        "java/util/concurrent/ConcurrentSkipListMap.entrySet",
        "java/util/concurrent/ConcurrentSkipListMap.descendingMap");

    private final Set<String> countedClasses = new LinkedHashSet<>();
    private final Set<String> countDeclarers = new HashSet<>();
    private final Map<String, Set<String>> countedFields = new HashMap<>();
    private final Set<String> countedArrayTypes = new HashSet<>();

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
