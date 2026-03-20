// SPDX-License-Identifier: GPL-3.0-only
package dev.zenpatch.patcher.model

/**
 * Immutable metadata extracted from an APK during the analysis phase.
 *
 * @property packageName Android package identifier (e.g. "com.example.app").
 * @property versionName Human-readable version string.
 * @property versionCode Integer version code.
 * @property minSdkVersion Minimum Android SDK version required by the APK.
 * @property targetSdkVersion Target Android SDK version.
 * @property applicationClass Original Application class name, or null if not specified.
 * @property dexFiles Ordered list of dex file names (classes.dex, classes2.dex, …).
 * @property nativeLibs Map of ABI name to list of native library filenames.
 * @property signingSchemes Set of APK signing schemes present (1, 2, 3, 4).
 * @property isSplitApk True when the APK is a split (base.apk + splits).
 * @property splitApkPaths Paths to all split APK files (empty for monolithic APKs).
 * @property isDebuggable Whether the manifest's debuggable attribute is set to true.
 */
data class ApkInfo(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdkVersion: Int,
    val targetSdkVersion: Int,
    val applicationClass: String?,
    val dexFiles: List<String>,
    val nativeLibs: Map<String, List<String>>,
    val signingSchemes: Set<Int>,
    val isSplitApk: Boolean,
    val splitApkPaths: List<String>,
    val isDebuggable: Boolean
)
