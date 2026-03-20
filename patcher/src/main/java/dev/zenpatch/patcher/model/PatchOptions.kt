// SPDX-License-Identifier: GPL-3.0-only
package dev.zenpatch.patcher.model

/**
 * Configuration options that control the patching pipeline.
 *
 * @property moduleApkPaths Absolute paths to Xposed module APKs to embed.
 * @property enableSignatureSpoof Inject signature-spoofing hooks into the runtime.
 * @property keystorePath Path to the keystore used for re-signing. Null = generate a debug key.
 * @property keystorePassword Password for the keystore.
 * @property keyAlias Alias of the signing key within the keystore.
 * @property keyPassword Password for the individual key entry.
 * @property keepDebuggable Preserve the manifest debuggable flag when true.
 * @property outputPath Destination path for the patched APK. Null = auto-generate alongside input.
 * @property verbose Enable verbose progress logging.
 */
data class PatchOptions(
    val moduleApkPaths: List<String> = emptyList(),
    val enableSignatureSpoof: Boolean = true,
    val keystorePath: String? = null,
    val keystorePassword: String = "zenpatch",
    val keyAlias: String = "zenpatch",
    val keyPassword: String = "zenpatch",
    val keepDebuggable: Boolean = false,
    val outputPath: String? = null,
    val verbose: Boolean = false
)
