// SPDX-License-Identifier: GPL-3.0-only
package dev.zenpatch.runtime.module

import dalvik.system.PathClassLoader

/**
 * Isolated [ClassLoader] for a single Xposed module.
 *
 * Each module receives its own [ModuleClassLoader] so that:
 *  - Module code is isolated from other modules (no class conflicts).
 *  - The host app's classes are accessible via the parent loader.
 *  - Module dependencies don't leak into the host app's classloading.
 *
 * Parent delegation order: ModuleClassLoader → host app ClassLoader → boot ClassLoader.
 *
 * @param apkPath Absolute path to the module APK.
 * @param hostClassLoader ClassLoader of the host (patched) application.
 */
class ModuleClassLoader(
    apkPath: String,
    hostClassLoader: ClassLoader
) : PathClassLoader(apkPath, null, hostClassLoader) {

    companion object {
        /**
         * Create a [ModuleClassLoader] for the given [config].
         *
         * @param config Module configuration containing the APK path.
         * @param hostClassLoader ClassLoader of the host application.
         */
        fun create(config: ModuleConfig, hostClassLoader: ClassLoader): ModuleClassLoader {
            return ModuleClassLoader(config.apkPath, hostClassLoader)
        }
    }
}
