package dev.zenpatch.patcher

/**
 * Options controlling the APK patching pipeline.
 *
 * This class lives in the :patcher module so that PatcherEngine (and the :cli module)
 * can use it without depending on :app, avoiding a circular dependency.
 *
 * The :app module's PatchOptions is a thin type alias / wrapper that delegates to this class.
 */
data class PatchOptions(
    /** Whether to enable signature-spoofing at runtime (recommended: true). */
    val enableSignatureSpoof: Boolean = true,
    /** Mark the output APK as debuggable (for development only). */
    val debuggable: Boolean = false,
    /** Package names of Xposed modules to embed into the patched APK. */
    val selectedModules: List<String> = emptyList(),
    /** Use a caller-supplied keystore instead of the auto-generated ephemeral one. */
    val useExistingKeystore: Boolean = false,
    /** Path to a PKCS #12 / JKS keystore file. Ignored when [useExistingKeystore] is false. */
    val keystorePath: String? = null,
    /** Password for the keystore / key entry. */
    val keystorePassword: String? = null,
    /** Key alias to sign with inside the keystore. */
    val keystoreAlias: String? = null
)

/**
 * A snapshot of an in-progress patching operation, delivered via the progress callback.
 *
 * @param step       The current high-level step being executed.
 * @param stepIndex  Zero-based index of the current step (0 … totalSteps-1).
 * @param totalSteps Total number of steps in the pipeline.
 * @param progress   Completion ratio for the *current* step (0.0 = started, 1.0 = done).
 * @param message    Human-readable status description (suitable for display in UI).
 * @param isError    True only when an unrecoverable error has occurred.
 * @param errorMessage Optional error detail; present only when [isError] is true.
 */
data class PatchProgress(
    val step: PatchStep,
    val stepIndex: Int,
    val totalSteps: Int,
    val progress: Float,
    val message: String,
    val isError: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Enumeration of discrete steps in the ZenPatch patching pipeline.
 *
 * The ordinal order matches the execution sequence in [PatcherEngine.patch].
 */
enum class PatchStep {
    /** Reading and validating the input APK structure. */
    ANALYZING,
    /** Merging split-APK parts (AAB-style) into a single universal APK. */
    MERGING_SPLITS,
    /** Injecting the ZenPatch loader classes.dex into the APK. */
    INJECTING_DEX,
    /** Embedding the LSPlant / bridge native libraries (.so files). */
    INJECTING_NATIVE,
    /** Rewriting AndroidManifest.xml to replace the Application class. */
    PATCHING_MANIFEST,
    /** Signing the output APK with a debug or user-supplied keystore. */
    SIGNING,
    /** Terminal state: patching finished successfully. */
    FINISHED
}
