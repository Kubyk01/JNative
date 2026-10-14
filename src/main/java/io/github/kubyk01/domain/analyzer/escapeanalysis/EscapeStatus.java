package io.github.kubyk01.domain.analyzer.escapeanalysis;

/**
 * Escape classification of an allocation site.
 *
 * <h2>Ordering</h2>
 *
 * <p>The ordinal of each constant is used as a monotone "how far has this
 * object escaped" rank: {@code escapeResult} and {@code IntraproceduralEscape}
 * both keep the maximum ordinal across all the statuses a site receives.
 * The ordering below is therefore load-bearing, not decorative.</p>
 *
 * <p>{@link #HEAP} sits below {@link #RETURN} and {@link #GLOBAL} on
 * purpose. An object that is stored into a field of another object AND is
 * reachable from a returned value (or from a static field) has escaped at
 * least as far as the value it is reachable from. If {@code HEAP} won over
 * {@code RETURN}, the destructor pass would insert a per-use destructor
 * for an object that the caller still owns -- exactly the use-after-free
 * that produced the crash in
 * {@code java.security.AccessController.executePrivileged}.</p>
 */
public enum EscapeStatus {
    /** Does not escape the defining function's local scope. */
    STACK,
    /** Stored into a field of some other object (or of {@code this}). */
    HEAP,
    /** Returned to the caller; the caller owns it. */
    RETURN,
    /** Stored into a static field or otherwise globally reachable. */
    GLOBAL,
    /** Handed to another thread. */
    THREAD,
    /** Not yet determined. */
    UNKNOWN
}
