// SPDX-License-Identifier: GPL-3.0-only
package de.robv.android.xposed.callbacks;

import java.lang.ClassLoader;

/**
 * Callback for the {@code handleLoadPackage} hook invoked when an application package
 * has been loaded into the current process.
 *
 * <p>Implement {@link de.robv.android.xposed.IXposedHookLoadPackage} and use
 * the {@link LoadPackageParam} passed to {@code handleLoadPackage} to selectively
 * install method hooks for the target app.
 */
public abstract class XC_LoadPackage extends XCallback {

    protected XC_LoadPackage() {
        super();
    }

    protected XC_LoadPackage(int priority) {
        super(priority);
    }

    /**
     * Parameters for the {@code handleLoadPackage} callback.
     *
     * <p>Provides the package name, process name, and {@link ClassLoader} for
     * the loaded package so that hooks can load the appropriate classes.
     */
    public static final class LoadPackageParam {

        /** Package name of the loaded application (e.g. {@code "com.example.app"}). */
        public final String packageName;

        /** Process name of the running process. May differ from {@code packageName}
         *  for multi-process apps. */
        public final String processName;

        /**
         * ClassLoader for the loaded package. Use this to load classes from the
         * target application without relying on the system class loader.
         */
        public final ClassLoader classLoader;

        /** Whether the loaded package is the first application in this process. */
        public final boolean isFirstApplication;

        /**
         * @param packageName Package name of the loaded application.
         * @param processName Process name of the running process.
         * @param classLoader ClassLoader for the loaded package.
         * @param isFirstApplication True if this is the primary application in the process.
         */
        public LoadPackageParam(
                String packageName,
                String processName,
                ClassLoader classLoader,
                boolean isFirstApplication) {
            this.packageName = packageName;
            this.processName = processName;
            this.classLoader = classLoader;
            this.isFirstApplication = isFirstApplication;
        }
    }
}
