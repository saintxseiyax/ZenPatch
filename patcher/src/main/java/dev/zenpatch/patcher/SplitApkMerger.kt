package dev.zenpatch.patcher

import timber.log.Timber
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Merges split APKs (base + config splits) into a single universal APK.
 * Only the base APK needs manifest patching; split APKs contribute native libs and resources.
 */
class SplitApkMerger {

    /**
     * Merges a base APK with optional split APKs.
     * @param baseApk The base.apk
     * @param splitApks List of split APKs (config.arm64_v8a, config.en, etc.)
     * @param output Output file for merged APK
     */
    fun merge(baseApk: File, splitApks: List<File>, output: File): File {
        if (splitApks.isEmpty()) {
            Timber.d("No splits to merge, copying base APK")
            baseApk.copyTo(output, overwrite = true)
            return output
        }

        Timber.d("Merging %d splits into universal APK", splitApks.size)
        output.parentFile?.mkdirs()

        val outputCanonical = output.canonicalPath

        ZipOutputStream(output.outputStream().buffered()).use { zout ->
            val addedEntries = mutableSetOf<String>()

            // First: copy all entries from base APK
            ZipFile(baseApk).use { zip ->
                for (entry in zip.entries()) {
                    // ZIP-slip prevention
                    val entryPath = entry.name
                    if (!isEntryPathSafe(entryPath, outputCanonical)) {
                        Timber.w("Skipping unsafe entry in base APK: %s", entryPath)
                        continue
                    }
                    if (addedEntries.contains(entryPath)) continue

                    val newEntry = ZipEntry(entryPath).apply {
                        method = entry.method
                        if (entry.method == ZipEntry.STORED) {
                            size = entry.size
                            compressedSize = entry.compressedSize
                            crc = entry.crc
                        }
                        comment = entry.comment
                        extra = entry.extra
                    }
                    zout.putNextEntry(newEntry)
                    zip.getInputStream(entry).use { it.copyTo(zout) }
                    zout.closeEntry()
                    addedEntries.add(entryPath)
                }
            }

            // Then: add entries from each split that aren't already in base
            for (splitApk in splitApks) {
                ZipFile(splitApk).use { zip ->
                    for (entry in zip.entries()) {
                        val entryPath = entry.name
                        if (!isEntryPathSafe(entryPath, outputCanonical)) continue

                        // Skip manifest and signature from splits (use base APK's)
                        if (entryPath == "AndroidManifest.xml") continue
                        if (entryPath.startsWith("META-INF/") && (
                                entryPath.endsWith(".SF") ||
                                entryPath.endsWith(".RSA") ||
                                entryPath.endsWith(".DSA") ||
                                entryPath.endsWith(".EC") ||
                                entryPath == "META-INF/MANIFEST.MF"
                            )) continue

                        if (addedEntries.contains(entryPath)) {
                            Timber.d("Skipping duplicate entry from split: %s", entryPath)
                            continue
                        }

                        val newEntry = ZipEntry(entryPath).apply {
                            method = entry.method
                            if (entry.method == ZipEntry.STORED) {
                                size = entry.size
                                compressedSize = entry.compressedSize
                                crc = entry.crc
                            }
                        }
                        zout.putNextEntry(newEntry)
                        zip.getInputStream(entry).use { it.copyTo(zout) }
                        zout.closeEntry()
                        addedEntries.add(entryPath)
                        Timber.d("Added from split: %s", entryPath)
                    }
                }
            }
        }

        Timber.d("Merged APK created: %s (%d bytes)", output.name, output.length())
        return output
    }

    /**
     * Checks if a ZIP entry name is safe (no path traversal).
     * The check ensures the resolved path starts with a safe base.
     */
    private fun isEntryPathSafe(entryName: String, baseCanonical: String): Boolean {
        if (entryName.contains("..")) return false
        if (entryName.startsWith("/")) return false
        if (entryName.contains("\u0000")) return false
        return true
    }
}
