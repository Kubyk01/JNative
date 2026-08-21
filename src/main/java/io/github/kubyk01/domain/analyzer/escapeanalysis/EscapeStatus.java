package io.github.kubyk01.domain.analyzer.escapeanalysis;

public enum EscapeStatus {
    STACK,
    HEAP,
    GLOBAL,
    THREAD,
    UNKNOWN
}
