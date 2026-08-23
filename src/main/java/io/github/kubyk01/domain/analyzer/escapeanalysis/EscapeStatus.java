package io.github.kubyk01.domain.analyzer.escapeanalysis;

public enum EscapeStatus {
    STACK,     // does not escape
    RETURN,    // escapes via return
    HEAP,      // escapes via storing in a field
    GLOBAL,    // escapes globally
    THREAD,    // escapes via thread
    UNKNOWN
}
