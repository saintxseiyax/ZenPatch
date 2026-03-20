package dev.zenpatch.patcher

import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Analyzes APK structure without full decompilation.
 * Extracts manifest info, dex files, native libs, signature scheme, and split detection.
 */
class ApkAnalyzer {

    data class ApkInfo(
        val file: File,
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val applicationClass: String?,
        val minSdk: Int,
        val targetSdk: Int,
        val dexFiles: List<String>,
        val nativeLibs: Map<String, List<String>>,      // arch -> list of .so names
        val signatureSchemes: Set<Int>,                  // 1, 2, 3, 4
        val isSplitApk: Boolean,
        val splitName: String?,
        val usesPermissions: List<String>,
        val isDeclaredDebuggable: Boolean
    )

    /**
     * Analyzes the given APK file and returns structured metadata.
     * ZIP-slip prevention: all entry paths are validated before use.
     */
    fun analyze(apk: File): ApkInfo {
        require(apk.exists()) { "APK file does not exist: ${apk.absolutePath}" }
        require(apk.extension.lowercase() in listOf("apk", "zip")) { "Not an APK file: ${apk.name}" }

        Timber.d("Analyzing APK: %s", apk.absolutePath)

        val dexFiles = mutableListOf<String>()
        val nativeLibs = mutableMapOf<String, MutableList<String>>()
        val signatureSchemes = mutableSetOf<Int>()
        var isSplit = false
        var splitName: String? = null

        ZipFile(apk).use { zip ->
            for (entry in zip.entries()) {
                val entryName = entry.name
                // ZIP-slip prevention
                val canonical = File(apk.parentFile, entryName).canonicalPath
                if (!canonical.startsWith(apk.parentFile!!.canonicalPath)) {
                    Timber.w("Skipping potentially malicious entry: %s", entryName)
                    continue
                }

                when {
                    entryName.matches(Regex("classes\\d*\\.dex")) -> dexFiles.add(entryName)
                    entryName.startsWith("lib/") && entryName.endsWith(".so") -> {
                        val parts = entryName.split("/")
                        if (parts.size >= 3) {
                            val arch = parts[1]
                            nativeLibs.getOrPut(arch) { mutableListOf() }.add(parts[2])
                        }
                    }
                    entryName == "META-INF/MANIFEST.MF" -> signatureSchemes.add(1)
                    entryName == "split_config" -> isSplit = true
                }
            }

            // Check for APK Signature Block (v2/v3)
            detectSignatureSchemes(apk, signatureSchemes)

            // Parse AndroidManifest.xml
            val manifestEntry = zip.getEntry("AndroidManifest.xml")
            if (manifestEntry != null) {
                zip.getInputStream(manifestEntry).use { stream ->
                    val bytes = stream.readBytes()
                    val manifestData = parseAxml(bytes)
                    return ApkInfo(
                        file = apk,
                        packageName = manifestData.packageName,
                        versionCode = manifestData.versionCode,
                        versionName = manifestData.versionName,
                        applicationClass = manifestData.applicationClass,
                        minSdk = manifestData.minSdk,
                        targetSdk = manifestData.targetSdk,
                        dexFiles = dexFiles.sorted(),
                        nativeLibs = nativeLibs,
                        signatureSchemes = signatureSchemes,
                        isSplitApk = isSplit || manifestData.isSplit,
                        splitName = splitName ?: manifestData.splitName,
                        usesPermissions = manifestData.usesPermissions,
                        isDeclaredDebuggable = manifestData.debuggable
                    )
                }
            }
        }

        throw IllegalStateException("AndroidManifest.xml not found in APK: ${apk.name}")
    }

    private fun detectSignatureSchemes(apk: File, schemes: MutableSet<Int>) {
        // APK Signing Block detection (v2/v3)
        // The APK Signing Block is located just before the Central Directory.
        apk.inputStream().buffered().use { stream ->
            val bytes = stream.readBytes()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            // Find End of Central Directory
            val eocdOffset = findEocdOffset(bytes) ?: return
            if (eocdOffset < 0) return

            buf.position(eocdOffset + 16)
            val cdOffset = buf.int.toLong()

            // Check for APK Signing Block magic before CD
            if (cdOffset < 24) return
            buf.position((cdOffset - 24).toInt())
            val blockSize = buf.long
            val blockOffset = cdOffset - blockSize - 8

            if (blockOffset < 0) return
            buf.position(blockOffset.toInt())
            val magic = ByteArray(16)
            val sizeLow = buf.long

            // Read magic at end of block
            val magicOffset = (cdOffset - 16).toInt()
            if (magicOffset + 16 <= bytes.size) {
                System.arraycopy(bytes, magicOffset, magic, 0, 16)
                val magicStr = String(magic)
                if (magicStr == "APK Sig Block 42") {
                    // Found signing block - scan ID-value pairs
                    scanSigningBlockIds(bytes, blockOffset.toInt() + 8, (cdOffset - 24).toInt(), schemes)
                }
            }
        }
    }

    private fun findEocdOffset(bytes: ByteArray): Int? {
        // Search backwards for End of Central Directory signature: 0x06054b50
        for (i in bytes.size - 22 downTo maxOf(0, bytes.size - 22 - 65535)) {
            if (i + 3 >= bytes.size) continue
            if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() &&
                bytes[i + 2] == 0x05.toByte() && bytes[i + 3] == 0x06.toByte()) {
                return i
            }
        }
        return null
    }

    private fun scanSigningBlockIds(bytes: ByteArray, start: Int, end: Int, schemes: MutableSet<Int>) {
        var pos = start
        while (pos + 12 <= end) {
            val buf = ByteBuffer.wrap(bytes, pos, 12).order(ByteOrder.LITTLE_ENDIAN)
            val pairLength = buf.long
            if (pairLength < 4 || pos + 8 + pairLength > end) break
            val id = buf.int
            when (id) {
                0x7109871a -> schemes.add(2) // v2 signature scheme ID
                0xf05368c0.toInt() -> schemes.add(3) // v3 signature scheme ID
                0x1b93ad61 -> schemes.add(3) // v3.1 rotation
            }
            pos += 8 + pairLength.toInt()
        }
    }

    private data class ManifestData(
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val applicationClass: String?,
        val minSdk: Int,
        val targetSdk: Int,
        val isSplit: Boolean,
        val splitName: String?,
        val usesPermissions: List<String>,
        val debuggable: Boolean
    )

    /**
     * Minimal AXML (Android Binary XML) parser.
     * Extracts only the attributes we care about without full decompilation.
     */
    private fun parseAxml(data: ByteArray): ManifestData {
        val parser = AXMLParser(data)
        var packageName = ""
        var versionCode = 0L
        var versionName = ""
        var applicationClass: String? = null
        var minSdk = 1
        var targetSdk = 1
        var isSplit = false
        var splitName: String? = null
        val usesPermissions = mutableListOf<String>()
        var debuggable = false

        parser.parse { event ->
            when (event.type) {
                AXMLParser.EventType.START_ELEMENT -> {
                    when (event.name) {
                        "manifest" -> {
                            packageName = event.attrs["package"] ?: ""
                            versionCode = event.attrs["android:versionCode"]?.toLongOrNull() ?: 0L
                            versionName = event.attrs["android:versionName"] ?: ""
                            splitName = event.attrs["split"]
                            isSplit = splitName != null
                        }
                        "application" -> {
                            applicationClass = event.attrs["android:name"]
                            debuggable = event.attrs["android:debuggable"] == "true"
                        }
                        "uses-sdk" -> {
                            minSdk = event.attrs["android:minSdkVersion"]?.toIntOrNull() ?: 1
                            targetSdk = event.attrs["android:targetSdkVersion"]?.toIntOrNull() ?: 1
                        }
                        "uses-permission" -> {
                            event.attrs["android:name"]?.let { usesPermissions.add(it) }
                        }
                    }
                }
                else -> {}
            }
        }

        return ManifestData(packageName, versionCode, versionName, applicationClass, minSdk, targetSdk, isSplit, splitName, usesPermissions, debuggable)
    }
}
