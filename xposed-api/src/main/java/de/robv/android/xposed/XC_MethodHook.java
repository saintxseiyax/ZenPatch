package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * Base class for Xposed method hooks.
 * Provides before/after callback points for hooked methods.
 * Compatible with existing Xposed modules.
 */
public abstract class XC_MethodHook {

    private final int priority;

    public XC_MethodHook() {
        this(PRIORITY_DEFAULT);
    }

    public XC_MethodHook(int priority) {
        this.priority = priority;
    }

    public int getPriority() {
        return priority;
    }

    /**
     * Called before the hooked method executes.
     * @param param Hook parameters. Call param.setResult() to skip original method.
     */
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}

    /**
     * Called after the hooked method executes.
     * @param param Hook parameters including the return value.
     */
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    // ---- Priority constants ----
    public static final int PRIORITY_HIGHEST = 100;
    public static final int PRIORITY_DEFAULT = 50;
    public static final int PRIORITY_LOWEST = -100;

    /**
     * Parameters passed to before/after hooks, containing method context.
     */
    public static final class MethodHookParam {

        /** The hooked method/constructor. */
        public Member method;

        /** The object the method was called on (null for static methods). */
        public Object thisObject;

        /** Method arguments. */
        public Object[] args;

        private Object result;
        private Throwable throwable;
        private boolean returnEarly;

        public MethodHookParam() {}

        /** Returns the current result (after method execution, or set via setResult). */
        public Object getResult() {
            return result;
        }

        /** Sets the return value. For beforeHookedMethod, also skips the original call. */
        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
            this.returnEarly = true;
        }

        /** Returns any exception thrown by the method, or null. */
        public Throwable getThrowable() {
            return throwable;
        }

        /** Check if the method threw an exception. */
        public boolean hasThrowable() {
            return throwable != null;
        }

        /** Override the exception that will be thrown. */
        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
            this.returnEarly = true;
        }

        /**
         * Returns the result or re-throws the stored exception.
         */
        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }

        /** Whether to skip the original method execution. */
        public boolean isReturnEarly() {
            return returnEarly;
        }

        /** Called by the bridge to set the result from the original method. */
        public void setResultFromOriginal(Object result, Throwable throwable) {
            this.result = result;
            this.throwable = throwable;
            this.returnEarly = false;
        }
    }

    /**
     * Handle for an installed hook. Allows unhooking.
     */
    public static final class Unhook {

        private final XC_MethodHook callback;
        private final Member hookedMethod;

        public Unhook(XC_MethodHook callback, Member hookedMethod) {
            this.callback = callback;
            this.hookedMethod = hookedMethod;
        }

        public Member getHookedMethod() {
            return hookedMethod;
        }

        public XC_MethodHook getCallback() {
            return callback;
        }

        public void unhook() {
            XposedBridge.unhookMethod(hookedMethod, callback);
        }
    }
}
