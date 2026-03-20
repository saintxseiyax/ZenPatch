// SPDX-License-Identifier: GPL-3.0-only
package dev.zenpatch.runtime.module

/**
 * Configuration metadata for a single Xposed module.
 *
 * @property packageName Package name of the module APK.
 * @property apkPath Absolute path to the module APK on disk.
 * @property entryPoints List of fully-qualified class names that implement
 *   [de.robv.android.xposed.IXposedHookLoadPackage] (read from `assets/xposed_init`).
 * @property minVersion Minimum Xposed API version required by this module.
 * @property description Human-readable description of the module.
 * @property isEnabled Whether this module is currently enabled for the host app.
 */
data class ModuleConfig(
    val packageName: String,
    val apkPath: String,
    val entryPoints: List<String>,
    val minVersion: Int,
    val description: String,
    val isEnabled: Boolean
)
