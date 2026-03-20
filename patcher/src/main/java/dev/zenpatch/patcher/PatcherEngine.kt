package dev.zenpatch.patcher

// PatchOptions, PatchProgress and PatchStep are defined in this module (PatchModels.kt)
// to avoid a circular dependency with the :app module.
import timber.log.Timber
import java.io.File

/**
 * Orchestrates the full APK patching pipeline:
 *   analyze → merge splits → inject dex → inject native → patch manifest → sign
 *
 * Each step reports progress via the callback.
 */
class PatcherEngine {

    private val analyzer = ApkAnalyzer()
    private val splitMerger = SplitApkMerger()
    private val dexInjector = DexInjector()
    private val nativeLibInjector = NativeLibInjector()
    private val manifestEditor = ManifestEditor()
    private val apkSigner = ApkSigner()

    /**
     * Full patching pipeline.
     * @param inputApk Original APK to patch
     * @param outputApk Destination for patched APK
     * @param moduleApkPaths Paths to module APKs to embed
     * @param options Patching options
     * @param progressCallback Called with progress updates
     */
    fun patch(
        inputApk: File,
        outputApk: File,
        moduleApkPaths: List<String> = emptyList(),
        options: PatchOptions = PatchOptions(),
        progressCallback: (PatchProgress) -> Unit = {}
    ) {
        val totalSteps = 6
        val workDir = createTempDir(inputApk.parentFile ?: outputApk.parentFile!!)

        try {
            // Step 1: Analyze
            progressCallback(PatchProgress(PatchStep.ANALYZING, 0, totalSteps, 0f, "Analyzing APK structure…"))
            val apkInfo = analyzer.analyze(inputApk)
            Timber.d("APK info: pkg=%s, vcode=%d, dex=%d, abis=%s",
                apkInfo.packageName, apkInfo.versionCode, apkInfo.dexFiles.size, apkInfo.nativeLibs.keys)
            progressCallback(PatchProgress(PatchStep.ANALYZING, 0, totalSteps, 1f, "Analysis complete"))

            // Step 2: Merge splits (if any)
            val mergedApk: File
            if (apkInfo.isSplitApk) {
                progressCallback(PatchProgress(PatchStep.MERGING_SPLITS, 1, totalSteps, 0f, "Merging split APKs…"))
                mergedApk = File(workDir, "merged.apk")
                splitMerger.merge(inputApk, emptyList(), mergedApk)
                progressCallback(PatchProgress(PatchStep.MERGING_SPLITS, 1, totalSteps, 1f, "Splits merged"))
            } else {
                mergedApk = inputApk
            }

            // Step 3: Inject DEX
            progressCallback(PatchProgress(PatchStep.INJECTING_DEX, 2, totalSteps, 0f, "Injecting loader DEX…"))
            val dexInjectedApk = File(workDir, "dex_injected.apk")
            val loaderDex = DexInjector.generateLoaderDex()
            dexInjector.inject(mergedApk, loaderDex, dexInjectedApk)
            progressCallback(PatchProgress(PatchStep.INJECTING_DEX, 2, totalSteps, 1f, "DEX injected"))

            // Step 4: Inject native libs
            progressCallback(PatchProgress(PatchStep.INJECTING_NATIVE, 3, totalSteps, 0f, "Injecting native libraries…"))
            val nativeInjectedApk = File(workDir, "native_injected.apk")
            val nativeLibs = collectNativeLibs(apkInfo)
            nativeLibInjector.inject(dexInjectedApk, nativeLibs, nativeInjectedApk)
            progressCallback(PatchProgress(PatchStep.INJECTING_NATIVE, 3, totalSteps, 1f, "Native libs injected"))

            // Step 5: Patch manifest
            progressCallback(PatchProgress(PatchStep.PATCHING_MANIFEST, 4, totalSteps, 0f, "Patching AndroidManifest…"))
            val manifestPatchedApk = File(workDir, "manifest_patched.apk")
            manifestEditor.patch(
                inputApk = nativeInjectedApk,
                outputApk = manifestPatchedApk,
                moduleApkPaths = moduleApkPaths,
                debuggable = options.debuggable
            )
            progressCallback(PatchProgress(PatchStep.PATCHING_MANIFEST, 4, totalSteps, 1f, "Manifest patched"))

            // Step 6: Sign
            progressCallback(PatchProgress(PatchStep.SIGNING, 5, totalSteps, 0f, "Signing APK…"))
            val keystoreFile = if (options.keystorePath != null) File(options.keystorePath) else null
            apkSigner.sign(
                inputApk = manifestPatchedApk,
                outputApk = outputApk,
                keystore = keystoreFile,
                keystorePassword = (options.keystorePassword ?: "zenpatch").toCharArray(),
                keyAlias = options.keystoreAlias ?: "zenpatch"
            )
            progressCallback(PatchProgress(PatchStep.SIGNING, 5, totalSteps, 1f, "Signing complete"))

            progressCallback(PatchProgress(PatchStep.FINISHED, 6, totalSteps, 1f, "Patching complete! Output: ${outputApk.name}"))
            Timber.i("Patching complete: %s -> %s", inputApk.name, outputApk.name)

        } finally {
            // Clean up temp files
            workDir.deleteRecursively()
        }
    }

    private fun collectNativeLibs(apkInfo: ApkAnalyzer.ApkInfo): List<NativeLibInjector.NativeLib> {
        // In production, this loads the pre-compiled LSPlant and zenpatch_bridge .so
        // from the bridge module's assets or jniLibs.
        // For now, return empty list - actual .so files would be bundled with the patcher.
        val libs = mutableListOf<NativeLibInjector.NativeLib>()

        // Determine which ABIs the target APK supports
        val targetAbis = if (apkInfo.nativeLibs.isNotEmpty()) {
            apkInfo.nativeLibs.keys.toList()
        } else {
            listOf("arm64-v8a") // Default to arm64
        }

        // For each supported ABI, add bridge library placeholder
        // Production: load actual .so from resources/assets
        targetAbis.forEach { abi ->
            // libs.add(NativeLibInjector.NativeLib(abi, "liblsplant.so", loadResource("lsplant_$abi.so")))
            // libs.add(NativeLibInjector.NativeLib(abi, "libzenpatch_bridge.so", loadResource("bridge_$abi.so")))
        }

        return libs
    }

    private fun createTempDir(parent: File): File {
        val dir = File(parent, "zenpatch_tmp_${System.currentTimeMillis()}")
        dir.mkdirs()
        return dir
    }
}
