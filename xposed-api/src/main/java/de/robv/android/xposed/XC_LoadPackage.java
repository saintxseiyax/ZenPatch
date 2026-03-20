package de.robv.android.xposed;

import android.content.pm.ApplicationInfo;

/**
 * Callback class for load package events.
 */
public final class XC_LoadPackage {

    /**
     * Parameters for the handleLoadPackage callback.
     */
    public static final class LoadPackageParam {

        /** The package name of the loaded app. */
        public final String packageName;

        /** The process name of the loaded app. */
        public final String processName;

        /** ClassLoader for the loaded app (use this to load app classes). */
        public final ClassLoader classLoader;

        /** ApplicationInfo of the loaded app. */
        public final ApplicationInfo appInfo;

        /** True if this is the first time the package is being loaded in this process. */
        public final boolean isFirstApplication;

        public LoadPackageParam(
                String packageName,
                String processName,
                ClassLoader classLoader,
                ApplicationInfo appInfo,
                boolean isFirstApplication) {
            this.packageName = packageName;
            this.processName = processName;
            this.classLoader = classLoader;
            this.appInfo = appInfo;
            this.isFirstApplication = isFirstApplication;
        }
    }

    private XC_LoadPackage() {}
}
