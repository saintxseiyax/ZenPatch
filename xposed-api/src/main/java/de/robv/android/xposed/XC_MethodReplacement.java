package de.robv.android.xposed;

/**
 * A special hook that completely replaces a method's implementation.
 * Override replaceHookedMethod() to provide the new implementation.
 */
public abstract class XC_MethodReplacement extends XC_MethodHook {

    public XC_MethodReplacement() {
        super(PRIORITY_DEFAULT);
    }

    public XC_MethodReplacement(int priority) {
        super(priority);
    }

    /**
     * Called instead of the original method. Must return the replacement value.
     * @param param Hook parameters
     * @return The return value for the hooked method
     */
    protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;

    @Override
    protected final void beforeHookedMethod(MethodHookParam param) throws Throwable {
        try {
            Object result = replaceHookedMethod(param);
            param.setResult(result);
        } catch (Throwable t) {
            param.setThrowable(t);
        }
    }

    @Override
    protected final void afterHookedMethod(MethodHookParam param) throws Throwable {
        // Not called for replacements
    }

    /**
     * Convenience method: return null replacement.
     */
    public static final XC_MethodReplacement DO_NOTHING = new XC_MethodReplacement() {
        @Override
        protected Object replaceHookedMethod(MethodHookParam param) {
            return null;
        }
    };

    /**
     * Convenience method: return true replacement.
     */
    public static final XC_MethodReplacement RETURN_TRUE = new XC_MethodReplacement() {
        @Override
        protected Object replaceHookedMethod(MethodHookParam param) {
            return Boolean.TRUE;
        }
    };

    /**
     * Convenience method: return false replacement.
     */
    public static final XC_MethodReplacement RETURN_FALSE = new XC_MethodReplacement() {
        @Override
        protected Object replaceHookedMethod(MethodHookParam param) {
            return Boolean.FALSE;
        }
    };
}
