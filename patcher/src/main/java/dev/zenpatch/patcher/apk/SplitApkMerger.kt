// SPDX-License-Identifier: GPL-3.0-only
package dev.zenpatch.patcher.apk

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Merges a set of split APKs into a single universal APK before patching.
 *
 * ### Strategy (base-centric merge)
 * ZenPatch patches the **base.apk** — so that APK becomes the universal output.
 * We iterate all split APKs and copy content into the base using these rules:
 *
 * | Entry type                      | Rule                                                   |
 * |---------------------------------|--------------------------------------------------------|
 * | `AndroidManifest.xml`           | Base wins — splits' manifests are discarded            |
 * | `resources.arsc`                | Base wins — split resources are screen/locale configs  |
 * | `classes.dex` / `classesN.dex`  | Base classes keep their numbers; split dex files are   |
 * |                                 | assigned next available numbers (N+1, N+2, …)          |
 * | `lib/<abi>/<name>.so`           | All native libs are merged; base wins on collision     |
 * | Everything else                 | Copied from splits if not already present in base      |
 *
 * The merged APK is written to a temporary file next to the base APK.
 * Callers are responsible for deleting it after patching is complete.
 */
class SplitApkMerger {

    companion object {
        private const val TAG = "SplitApkMerger"

        /** Entries always taken from the base APK; never overwritten by splits. */
        private val BASE_WINS_ENTRIES = setOf(
            "AndroidManifest.xml",
            "resources.arsc"
        )

        /** STORED compression method code. */
        private const val METHOD_STORED = ZipEntry.STORED
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Merge the base APK and its splits into a single universal APK.
     *
     * The output file is a temporary file placed in the same directory as the
     * base APK, named `<baseName>_merged_<timestamp>.apk`.
     *
     * @param baseApkPath Absolute path to `base.apk`.
     * @param splitPaths  Ordered list of split APK paths to merge in.
     * @return Absolute path to the merged APK temporary file.
     * @throws IllegalArgumentException if [baseApkPath] does not exist.
     * @throws java.io.IOException on I/O failure.
     */
    fun merge(baseApkPath: String, splitPaths: List<String>): String {
        val baseFile = File(baseApkPath)
        require(baseFile.exists()) { "base.apk not found: $baseApkPath" }

        val ts       = System.currentTimeMillis()
        val outName  = "${baseFile.nameWithoutExtension}_merged_$ts.apk"
        val outFile  = File(baseFile.parent ?: System.getProperty("java.io.tmpdir"), outName)

        Log.i(TAG, "Merging ${splitPaths.size} split(s) into base: $baseApkPath")
        Log.i(TAG, "Output: ${outFile.absolutePath}")

        ZipFile(baseFile).use { baseZip ->
            // Build the set of dex file names already present in the base
            val baseDexFiles = enumerateDexEntries(baseZip)
            Log.d(TAG, "Base dex files: $baseDexFiles")

            // Determine the next available dex index (classes.dex = index 1,
            // classes2.dex = index 2, etc.)
            var nextDexIndex = computeNextDexIndex(baseDexFiles)

            // Track all entry names written so far (to avoid duplicates)
            val writtenEntries = mutableSetOf<String>()

            ZipOutputStream(FileOutputStream(outFile)).use { zos ->

                // ── Step 1: Copy all entries from base.apk ────────────────────
                Log.d(TAG, "Copying base APK entries…")
                baseZip.entries().asSequence().forEach { entry ->
                    copyEntry(baseZip, entry, entry.name, zos)
                    writtenEntries.add(entry.name)
                }
                Log.d(TAG, "Copied ${writtenEntries.size} entries from base")

                // ── Step 2: Merge each split APK ──────────────────────────────
                for (splitPath in splitPaths) {
                    val splitFile = File(splitPath)
                    if (!splitFile.exists()) {
                        Log.w(TAG, "Split APK not found, skipping: $splitPath")
                        continue
                    }
                    Log.d(TAG, "Merging split: $splitPath")

                    ZipFile(splitFile).use { splitZip ->
                        splitZip.entries().asSequence().forEach { entry ->
                            when {
                                // Base always wins for manifest and resources
                                entry.name in BASE_WINS_ENTRIES -> {
                                    Log.v(TAG, "  Skip (base wins): ${entry.name}")
                                }

                                // Dex files are always renamed to avoid collisions —
                                // even if the original name was already used by the base
                                isDexEntry(entry.name) -> {
                                    val resolvedDexName = nextDexName(nextDexIndex)
                                    Log.d(TAG, "  Dex merge: ${entry.name} → $resolvedDexName")
                                    copyEntry(splitZip, entry, resolvedDexName, zos)
                                    writtenEntries.add(resolvedDexName)
                                    nextDexIndex++
                                }

                                // Non-dex entry already present: skip
                                entry.name in writtenEntries -> {
                                    Log.v(TAG, "  Skip (already present): ${entry.name}")
                                }

                                // Everything else: copy as-is
                                else -> {
                                    Log.v(TAG, "  Copy: ${entry.name}")
                                    copyEntry(splitZip, entry, entry.name, zos)
                                    writtenEntries.add(entry.name)
                                }
                            }
                        }
                    }
                }
            }
        }

        Log.i(TAG, "Merge complete: ${outFile.absolutePath}  (${outFile.length()} bytes)")
        return outFile.absolutePath
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Entry-level helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Copy a single [ZipEntry] from [src] into [zos] under the given [targetName].
     *
     * Compression method and extra data are preserved where possible; for
     * STORED entries the CRC32 and sizes are copied verbatim (required by the
     * ZIP spec before writing any data).
     */
    private fun copyEntry(
        src: ZipFile,
        entry: ZipEntry,
        targetName: String,
        zos: ZipOutputStream
    ) {
        val newEntry = ZipEntry(targetName)
        newEntry.comment = entry.comment
        newEntry.time    = entry.time

        if (entry.method == METHOD_STORED) {
            // For STORED entries the ZIP spec requires CRC + size to be set in
            // the local header (before the data), so we must copy them.
            newEntry.method           = METHOD_STORED
            newEntry.size             = entry.size
            newEntry.compressedSize   = entry.compressedSize
            newEntry.crc              = entry.crc
        } else {
            newEntry.method = ZipEntry.DEFLATED
        }

        zos.putNextEntry(newEntry)
        src.getInputStream(entry).use { it.copyTo(zos) }
        zos.closeEntry()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Dex naming helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Regex matching classes*.dex filenames. */
    private val dexEntryRegex = Regex("^classes(\\d*)\\.dex$")

    /** True if [name] is a DEX entry (classes.dex or classesN.dex). */
    private fun isDexEntry(name: String) = dexEntryRegex.matches(name)

    /**
     * Return all dex entry names present in [zip], sorted by index.
     * "classes.dex" has implicit index 1, "classes2.dex" has index 2, etc.
     */
    private fun enumerateDexEntries(zip: ZipFile): List<String> {
        return zip.entries().asSequence()
            .map { it.name }
            .filter { isDexEntry(it) }
            .sortedWith(Comparator { a, b ->
                dexIndex(a).compareTo(dexIndex(b))
            })
            .toList()
    }

    /**
     * Extract the numeric index from a dex filename.
     * "classes.dex" → 1, "classes2.dex" → 2, "classes10.dex" → 10
     */
    private fun dexIndex(name: String): Int {
        val suffix = dexEntryRegex.find(name)?.groupValues?.get(1) ?: return 1
        return if (suffix.isEmpty()) 1 else suffix.toInt()
    }

    /**
     * Compute the next available dex index given the set of existing dex names.
     * If base has classes.dex (index 1) and classes2.dex (index 2), next = 3.
     */
    private fun computeNextDexIndex(dexNames: List<String>): Int {
        if (dexNames.isEmpty()) return 2  // classes.dex not present, start at 2
        val maxIndex = dexNames.maxOf { dexIndex(it) }
        return maxIndex + 1
    }

    /**
     * Convert a dex index back to its canonical filename.
     * 1 → "classes.dex", 2 → "classes2.dex", …
     */
    private fun nextDexName(index: Int): String =
        if (index == 1) "classes.dex" else "classes$index.dex"
}
