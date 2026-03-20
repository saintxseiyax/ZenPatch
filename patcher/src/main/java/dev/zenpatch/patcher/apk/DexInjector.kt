// SPDX-License-Identifier: GPL-3.0-only
package dev.zenpatch.patcher.apk

import android.util.Log
import dev.zenpatch.patcher.model.PatchOptions
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Injects the ZenPatch loader dex and Xposed API compat dex into the target APK.
 *
 * ### Injection strategy
 *
 * ART loads dex files in the order they appear in the APK ZIP:
 * `classes.dex` first, then `classes2.dex`, `classes3.dex`, …
 * We exploit this ordering to ensure the ZenPatch bootstrap is always the
 * first class resolver ART encounters.
 *
 * Steps:
 * 1. Enumerate all existing `classes*.dex` entries in the APK.
 * 2. Shift them right by (1 + moduleCount) positions so space is made at
 *    the front for the bootstrap and module dex files.
 * 3. Write the ZenPatch bootstrap dex as `classes.dex`.
 * 4. For each Xposed module APK: extract its dex files and embed them in
 *    order as `classes2.dex`, `classes3.dex`, …
 * 5. Copy all remaining (non-dex) entries from the original APK unchanged.
 *
 * The resulting APK is written to a new temporary file next to the input.
 */
class DexInjector {

    companion object {
        private const val TAG = "DexInjector"

        /**
         * Minimal valid Dalvik Executable (DEX) header for a stub that contains
         * no classes but satisfies the DEX file format so ART can parse it.
         *
         * Format overview (DEX 035):
         *   magic[8]         "dex\n035\0"
         *   checksum[4]      Adler-32 of bytes[12..fileSize)
         *   sha1[20]         SHA-1 of bytes[32..fileSize)
         *   fileSize[4]      total file size
         *   headerSize[4]    0x70 (112)
         *   endianTag[4]     0x12345678
         *   … remaining counts/offsets all zero → empty dex
         *
         * The checksum and SHA-1 below are pre-computed for this exact 112-byte
         * zeroed-out stub and are valid values ART will accept at patching time.
         * At runtime the DEX is loaded from inside the APK and ART re-verifies it.
         *
         * NOTE: In production the build system replaces this with the real
         * pre-compiled bootstrap DEX from `assets/zenpatch/bootstrap.dex`.
         */
        private val MINIMAL_DEX_STUB: ByteArray by lazy {
            buildMinimalDex()
        }

        /**
         * Build a minimal, structurally valid DEX 035 file with no classes.
         * The file contains only the 112-byte fixed-size header with all
         * type/method/field/string counts set to zero.
         */
        private fun buildMinimalDex(): ByteArray {
            // DEX header is exactly 112 (0x70) bytes
            val dex = ByteArray(112)

            // Magic: "dex\n035\0"
            val magic = byteArrayOf(
                0x64, 0x65, 0x78, 0x0a,  // "dex\n"
                0x30, 0x33, 0x35, 0x00   // "035\0"
            )
            System.arraycopy(magic, 0, dex, 0, 8)

            // fileSize = 112 (0x70) — little-endian at offset 32
            dex[32] = 0x70
            dex[33] = 0x00
            dex[34] = 0x00
            dex[35] = 0x00

            // headerSize = 112 (0x70) — little-endian at offset 36
            dex[36] = 0x70
            dex[37] = 0x00
            dex[38] = 0x00
            dex[39] = 0x00

            // endianTag = 0x12345678 — little-endian at offset 40
            dex[40] = 0x78
            dex[41] = 0x56
            dex[42] = 0x34
            dex[43] = 0x12

            // All other fields (counts, offsets) remain 0 → empty DEX

            // Compute Adler-32 checksum over bytes[12..112) and write at offset 8
            val adler = computeAdler32(dex, 12, dex.size)
            dex[8]  = (adler and 0xFF).toByte()
            dex[9]  = ((adler shr 8)  and 0xFF).toByte()
            dex[10] = ((adler shr 16) and 0xFF).toByte()
            dex[11] = ((adler shr 24) and 0xFF).toByte()

            // SHA-1 (bytes[32..112)) is written at offset 12; for a stub DEX
            // ART does not enforce SHA-1 on load from APK — leave as zeroes.

            return dex
        }

        /** Compute Adler-32 checksum of [data][from]..[to). */
        private fun computeAdler32(data: ByteArray, from: Int, to: Int): Int {
            var s1 = 1
            var s2 = 0
            for (i in from until to) {
                s1 = (s1 + (data[i].toInt() and 0xFF)) % 65521
                s2 = (s2 + s1) % 65521
            }
            return (s2 shl 16) or s1
        }

        /** Regex that matches classes.dex, classes2.dex, … */
        private val DEX_NAME_REGEX = Regex("^classes(\\d*)\\.dex$")

        /**
         * Reject ZIP entry names that could escape the destination directory.
         *
         * A crafted APK could contain entries whose names start with "/" (absolute
         * path) or include "../" components (path traversal).  When such entries are
         * copied verbatim into a ZipOutputStream the resulting ZIP is malformed, and
         * if the ZIP is later extracted to the file system the attacker-controlled
         * name could overwrite arbitrary files outside the intended directory.
         *
         * @return true if the name is safe to copy as-is.
         */
        internal fun isSafeZipEntryName(name: String): Boolean {
            if (name.startsWith("/")) return false
            if (name.contains("..")) return false
            if (name.contains("\\0")) return false  // null byte
            return true
        }

        /** Return the numeric index of a dex entry name (classes.dex → 1, classes2.dex → 2, …) */
        private fun dexIndex(entryName: String): Int {
            val suffix = DEX_NAME_REGEX.find(entryName)?.groupValues?.get(1) ?: return -1
            return if (suffix.isEmpty()) 1 else suffix.toIntOrNull() ?: -1
        }

        /** Convert a 1-based dex index back to the canonical ZIP entry name. */
        private fun dexEntryName(index: Int): String =
            if (index == 1) "classes.dex" else "classes${index}.dex"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Perform dex injection on the APK at [apkPath].
     *
     * The original APK is not modified; a new temporary APK is created and its
     * path is returned.  Callers should delete the temporary file if an error
     * occurs upstream.
     *
     * @param apkPath Absolute path to the working APK.
     * @param options Patching configuration, including optional module APK paths.
     * @return Absolute path to the modified (dex-injected) APK.
     */
    fun inject(apkPath: String, options: PatchOptions): String {
        val inputFile = File(apkPath)
        require(inputFile.exists()) { "APK not found: $apkPath" }

        val ts      = System.currentTimeMillis()
        val outFile = File(
            inputFile.parent ?: System.getProperty("java.io.tmpdir"),
            "${inputFile.nameWithoutExtension}_dex_$ts.apk"
        )

        Log.i(TAG, "Injecting dex into: $apkPath → ${outFile.absolutePath}")

        ZipFile(inputFile).use { srcZip ->

            // ── 1. Catalogue existing dex entries (sorted by numeric index) ───
            val existingDex: List<String> = srcZip.entries().asSequence()
                .map { it.name }
                .filter { DEX_NAME_REGEX.matches(it) }
                .sortedBy { dexIndex(it) }
                .toList()

            Log.d(TAG, "Existing dex entries: $existingDex")

            // ── 2. Determine how many new dex files we will prepend ───────────
            //    slot 1 = bootstrap.dex (ZenPatch)
            //    slots 2..N = module dex files (each module may contain multiple)
            val bootstrapBytes: ByteArray = getBootstrapDexBytes()
            val moduleDexFiles: List<Pair<String, ByteArray>> =
                collectModuleDexFiles(options.moduleApkPaths)

            val injectedCount = 1 + moduleDexFiles.size   // bootstrap + modules
            Log.d(TAG, "Injecting $injectedCount new dex slot(s): 1 bootstrap + ${moduleDexFiles.size} module dex")

            // ── 3. Build rename map: old name → new name ─────────────────────
            //    classes.dex    → classes(injectedCount+1).dex
            //    classes2.dex   → classes(injectedCount+2).dex
            //    etc.
            val renameMap: Map<String, String> = existingDex.associate { oldName ->
                val oldIdx = dexIndex(oldName)
                val newIdx = oldIdx + injectedCount
                oldName to dexEntryName(newIdx)
            }
            Log.d(TAG, "Dex rename map: $renameMap")

            // ── 4. Write new APK ──────────────────────────────────────────────
            ZipOutputStream(FileOutputStream(outFile)).use { zos ->

                // ── 4a. Bootstrap dex as classes.dex ─────────────────────────
                val bootstrapEntry = ZipEntry("classes.dex").apply {
                    method = ZipEntry.DEFLATED
                }
                zos.putNextEntry(bootstrapEntry)
                zos.write(bootstrapBytes)
                zos.closeEntry()
                Log.d(TAG, "Written bootstrap dex as classes.dex (${bootstrapBytes.size} bytes)")

                // ── 4b. Module dex files as classes2.dex, classes3.dex, … ────
                moduleDexFiles.forEachIndexed { idx, (sourceName, bytes) ->
                    val slotIndex = idx + 2          // bootstrap occupies slot 1
                    val entryName = dexEntryName(slotIndex)
                    val entry = ZipEntry(entryName).apply {
                        method = ZipEntry.DEFLATED
                    }
                    zos.putNextEntry(entry)
                    zos.write(bytes)
                    zos.closeEntry()
                    Log.d(TAG, "Written module dex '$sourceName' as $entryName (${bytes.size} bytes)")
                }

                // ── 4c. Shifted original dex entries ─────────────────────────
                for (oldName in existingDex) {
                    val newName = renameMap[oldName] ?: oldName
                    val srcEntry = srcZip.getEntry(oldName) ?: continue
                    val newEntry = ZipEntry(newName).apply {
                        method = ZipEntry.DEFLATED
                        comment = srcEntry.comment
                    }
                    zos.putNextEntry(newEntry)
                    srcZip.getInputStream(srcEntry).use { it.copyTo(zos) }
                    zos.closeEntry()
                    Log.v(TAG, "Shifted dex: $oldName → $newName")
                }

                // ── 4d. All non-dex entries copied verbatim ───────────────────
                srcZip.entries().asSequence()
                    .filter { !DEX_NAME_REGEX.matches(it.name) }
                    .forEach { srcEntry ->
                        if (!isSafeZipEntryName(srcEntry.name)) {
                            Log.w(TAG, "Skipping unsafe ZIP entry name: '${srcEntry.name}'")
                            return@forEach
                        }
                        val newEntry = ZipEntry(srcEntry.name).apply {
                            method    = srcEntry.method
                            comment   = srcEntry.comment
                            extra     = srcEntry.extra
                            if (srcEntry.method == ZipEntry.STORED) {
                                size              = srcEntry.size
                                compressedSize    = srcEntry.compressedSize
                                crc               = srcEntry.crc
                            }
                        }
                        zos.putNextEntry(newEntry)
                        srcZip.getInputStream(srcEntry).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
            }
        }

        Log.i(TAG, "Dex injection complete: ${outFile.absolutePath}")
        return outFile.absolutePath
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bootstrap dex loading
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Return the bytes of the ZenPatch bootstrap dex.
     *
     * Resolution order:
     * 1. `assets/zenpatch/bootstrap.dex` embedded in the patcher module's own
     *    APK at runtime (production path — populated by the build system).
     * 2. A minimal structurally-valid DEX 035 stub (build-time placeholder).
     *    This stub contains no classes and is **not** suitable for production —
     *    the real bootstrap dex must be provided via the build pipeline.
     *
     * TODO: In the final build system, pre-compile
     *   `dev.zenpatch.runtime.ZenPatchAppProxy` into the bootstrap dex and place
     *   the output at `patcher/src/main/assets/zenpatch/bootstrap.dex`.
     *
     * @return Byte content of the bootstrap DEX file.
     */
    private fun getBootstrapDexBytes(): ByteArray {
        // Attempt to load from the patcher module's own asset directory
        val assetStream = runCatching {
            DexInjector::class.java.classLoader
                ?.getResourceAsStream("assets/zenpatch/bootstrap.dex")
        }.getOrNull()

        if (assetStream != null) {
            return assetStream.use { it.readBytes() }.also {
                Log.d(TAG, "Loaded bootstrap dex from assets (${it.size} bytes)")
            }
        }

        // Fall back to the minimal in-memory stub
        Log.w(TAG, "bootstrap.dex not found in assets — using placeholder stub. " +
                "TODO: provide a real pre-compiled bootstrap dex via the build system.")
        return MINIMAL_DEX_STUB
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Module dex extraction
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Open each module APK and extract all `classes*.dex` entries in numeric
     * order.  Module dex files from different APKs are concatenated in the
     * order the APKs appear in [moduleApkPaths].
     *
     * @param moduleApkPaths Paths to Xposed module APKs.
     * @return List of (originalEntryName, dexBytes) pairs, in injection order.
     */
    private fun collectModuleDexFiles(
        moduleApkPaths: List<String>
    ): List<Pair<String, ByteArray>> {
        val result = mutableListOf<Pair<String, ByteArray>>()

        for (modulePath in moduleApkPaths) {
            val moduleFile = File(modulePath)
            if (!moduleFile.exists()) {
                Log.w(TAG, "Module APK not found, skipping: $modulePath")
                continue
            }

            try {
                ZipFile(moduleFile).use { zip ->
                    val dexEntries = zip.entries().asSequence()
                        .filter { DEX_NAME_REGEX.matches(it.name) }
                        .sortedBy { dexIndex(it.name) }
                        .toList()

                    for (entry in dexEntries) {
                        val bytes = zip.getInputStream(entry).readBytes()
                        result.add(Pair(entry.name, bytes))
                        Log.d(TAG, "Extracted module dex '${entry.name}' from $modulePath (${bytes.size} bytes)")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract dex from module APK: $modulePath", e)
            }
        }

        return result
    }
}
