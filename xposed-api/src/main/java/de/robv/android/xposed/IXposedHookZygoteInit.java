package de.robv.android.xposed;

/**
 * Interface for Xposed modules that want to hook into Zygote initialization.
 *
 * NOTE: ZenPatch is a NON-ROOT framework. Zygote-level hooks are NOT supported.
 * Implementing this interface will result in UnsupportedOperationException at runtime.
 * This interface exists solely for API compatibility with existing Xposed modules.
 */
public interface IXposedHookZygoteInit {

    /**
     * Called during Zygote initialization.
     *
     * @throws UnsupportedOperationException Always thrown by ZenPatch.
     *         ZenPatch does not support Zygote-level hooking (non-root framework).
     *         Use {@link IXposedHookLoadPackage} instead.
     */
    void initZygote(StartupParam startupParam) throws Throwable;

    /**
     * Parameters for Zygote init (not used in ZenPatch).
     */
    final class StartupParam {
        public final String modulePath;

        public StartupParam(String modulePath) {
            this.modulePath = modulePath;
        }
    }
}
