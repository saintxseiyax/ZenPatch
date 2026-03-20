package dev.zenpatch.manager.data

// ---------------------------------------------------------------------------
// PatchOptions, PatchProgress, PatchStep live in the :patcher module to break
// the circular dependency  :app → :patcher → :app.
// Re-export them as type-aliases so the rest of the :app codebase can keep
// using the short dev.zenpatch.manager.data.* names without any changes.
// ---------------------------------------------------------------------------
typealias PatchOptions  = dev.zenpatch.patcher.PatchOptions
typealias PatchProgress = dev.zenpatch.patcher.PatchProgress
typealias PatchStep     = dev.zenpatch.patcher.PatchStep

// ---------------------------------------------------------------------------
// App-layer models (not needed by :patcher)
// ---------------------------------------------------------------------------

/**
 * Represents an app that has been patched by ZenPatch and is tracked in the
 * local database.
 */
data class PatchedApp(
    val packageName: String,
    val appName: String,
    val originalVersionCode: Long,
    val patchedVersionCode: Long,
    /** Epoch-millisecond timestamp of when the app was last patched. */
    val patchDate: Long,
    /** Package names of Xposed modules embedded in this patched APK. */
    val modules: List<String>,
    val status: PatchStatus,
    /** Absolute path to the original (un-patched) base APK. */
    val originalApkPath: String,
    /** Absolute path to the ZenPatch-signed output APK. */
    val patchedApkPath: String,
    val signatureSpoof: Boolean = true
)

/**
 * Lifecycle status of a tracked patched application.
 */
enum class PatchStatus {
    /** Installed and the ZenPatch runtime is active for this app. */
    ACTIVE,
    /** The original app received an OS update; re-patching is required. */
    OUTDATED,
    /** The patched APK has not been installed on this device yet. */
    NOT_INSTALLED,
    /** An unrecoverable error occurred during the last patching attempt. */
    ERROR
}

/**
 * Represents an Xposed module that is installed on this device and
 * discoverable by ZenPatch via the xposed.scope metadata in its manifest.
 */
data class InstalledModule(
    val packageName: String,
    val name: String,
    val description: String,
    val version: String,
    /** Minimum XposedBridge API version required by this module. */
    val minXposedVersion: Int,
    /** Absolute path to the module's base APK on-device. */
    val apkPath: String,
    val isEnabled: Boolean,
    /** Set of package names this module is allowed to hook. Empty = all packages. */
    val targetPackages: Set<String>
)
