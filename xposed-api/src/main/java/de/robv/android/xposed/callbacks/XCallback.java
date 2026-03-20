// SPDX-License-Identifier: GPL-3.0-only
package de.robv.android.xposed.callbacks;

/**
 * Base class for Xposed callbacks.
 *
 * <p>Subclasses may override {@link #getCallback()} to allow identity-based unhooking.
 * The {@code priority} field controls invocation order when multiple hooks are installed
 * on the same method (higher priority = called first before, called last after).
 */
public abstract class XCallback implements Comparable<XCallback> {

    /** Default priority – hooks installed at this priority are called in installation order. */
    public static final int PRIORITY_DEFAULT = 50;

    /** Highest built-in priority. */
    public static final int PRIORITY_HIGHEST = 100;

    /** Lowest built-in priority. */
    public static final int PRIORITY_LOWEST = -100;

    /** Priority of this callback. */
    public final int priority;

    /** Creates a callback with the default priority. */
    protected XCallback() {
        this.priority = PRIORITY_DEFAULT;
    }

    /**
     * Creates a callback with a custom priority.
     *
     * @param priority Invocation priority. Higher values are invoked earlier in {@code before}
     *                 callbacks and later in {@code after} callbacks.
     */
    protected XCallback(int priority) {
        this.priority = priority;
    }

    @Override
    public int compareTo(XCallback other) {
        // Reverse order: higher priority should appear first in sorted sets
        return Integer.compare(other.priority, this.priority);
    }
}
