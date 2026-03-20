// SPDX-License-Identifier: GPL-3.0-only
package dev.zenpatch.patcher.model

/**
 * Sealed hierarchy representing the outcome of a patching operation.
 *
 * Usage:
 * ```kotlin
 * when (val result = engine.patch(apkPath, options)) {
 *     is PatchResult.Success -> installApk(result.outputPath)
 *     is PatchResult.Failure -> showError(result.message, result.cause)
 *     is PatchResult.Cancelled -> showCancelledMessage()
 * }
 * ```
 */
sealed class PatchResult {

    /**
     * The patching pipeline completed successfully.
     *
     * @property outputPath Absolute path to the patched APK file.
     * @property patchedPackageName Package name of the output APK.
     * @property durationMs Total duration of the patching operation in milliseconds.
     */
    data class Success(
        val outputPath: String,
        val patchedPackageName: String,
        val durationMs: Long
    ) : PatchResult()

    /**
     * The patching pipeline failed at some stage.
     *
     * @property message Human-readable description of the failure.
     * @property cause Underlying exception, if available.
     * @property step Pipeline step at which the failure occurred.
     */
    data class Failure(
        val message: String,
        val cause: Throwable? = null,
        val step: PatchStep? = null
    ) : PatchResult()

    /** The patching operation was cancelled by the user. */
    data object Cancelled : PatchResult()
}

/** Named pipeline steps for more granular error reporting. */
enum class PatchStep {
    ANALYSE,
    DEX_INJECTION,
    NATIVE_INJECTION,
    MANIFEST_PATCHING,
    SIGNING,
    BUILD
}
