// SPDX-License-Identifier: GPL-3.0-only
package dev.zenpatch.patcher.apk

import android.util.Log
import dev.zenpatch.patcher.model.PatchOptions
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Injects the ZenPatch native bridge (`libzenpatch_bridge.so`) into the target APK
 * for every ABI that is already present in the APK, with a mandatory minimum of
 * **arm64-v8a** (required on Android 12+).
 *
 * ### 16 KB page-alignment requirement (Android 16 / NDK r27+)
 *
 * Android 16 requires all ELF shared objects inside an APK to be aligned on
 * 16 384-byte (16 KB) boundaries **within the ZIP file** — not just on 4 KB
 * boundaries.  This means:
 *
 * - The `.so` entry must use `STORED` (no deflate compression) so the OS can
 *   `mmap` it directly from the ZIP.
 * - The byte-offset of the actual file data inside the ZIP must be a multiple
 *   of 16 384.  The ZIP local-file-header includes a variable-length `extra`
 *   field that we can pad to achieve this alignment.
 *
 * See: [developer.android.com — 16 KB page size](https://developer.android.com/guide/practices/page-sizes)
 */
class NativeLibInjector {

    companion object {
        private const val TAG = "NativeLibInjector"

        /** Required alignment for native `.so` files in the ZIP (16 KB). */
        private const val SO_ALIGNMENT = 16_384

        /**
         * Size of a ZIP local-file-header **before** the filename and extra field.
         *
         * ZIP local file header layout:
         *   signature      4  (0x04034b50)
         *   version needed 2
         *   GP bit flag    2
         *   compression    2
         *   mod time       2
         *   mod date       2
         *   CRC-32         4
         *   comp size      4
         *   uncomp size    4
         *   name length    2
         *   extra length   2
         *   name           [nameLength]
         *   extra          [extraLength]
         *   data           ...
         *
         * Fixed portion = 4+2+2+2+2+2+4+4+4+2+2 = 30 bytes.
         */
        private const val LOCAL_HEADER_FIXED_SIZE = 30

        /** Recognised Android ABIs in preference order. */
        val ALL_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

        /** Fallback ABI when the APK contains no native libraries at all. */
        private const val FALLBACK_ABI = "arm64-v8a"

        /** Bridge library name injected into every ABI directory. */
        private const val BRIDGE_LIB_NAME = "libzenpatch_bridge.so"

        /**
         * Minimal valid ELF64 shared object stub (arm64) — 64-byte ELF header only.
         *
         * Fields:
         *   e_ident[16]  = ELF magic + class=ELFCLASS64 + data=LE + version=current
         *                  + OS/ABI=NONE + padding
         *   e_type       = ET_DYN (3) — shared object
         *   e_machine    = EM_AARCH64 (0xB7)
         *   e_version    = EV_CURRENT (1)
         *   remaining    = zeros (entry, phoff, shoff, flags, ehsize, …)
         *
         * ART will dlopen() this stub without crashing because it is a valid ELF
         * shared object; it just exports no symbols.
         *
         * TODO: Replace with the real pre-compiled libzenpatch_bridge.so
         *   (built by the :bridge module's CMakeLists.txt) via the build system.
         *   Place the outputs at:
         *     patcher/src/main/assets/zenpatch/lib/arm64-v8a/libzenpatch_bridge.so
         *     patcher/src/main/assets/zenpatch/lib/armeabi-v7a/libzenpatch_bridge.so
         *     patcher/src/main/assets/zenpatch/lib/x86_64/libzenpatch_bridge.so
         *     patcher/src/main/assets/zenpatch/lib/x86/libzenpatch_bridge.so
         */
        private fun buildElfStub(abi: String): ByteArray {
            // We produce a properly aligned placeholder.  The real binary is
            // supplied by the CI build; this stub keeps everything compilable.
            val elfClass: Byte = when (abi) {
                "arm64-v8a", "x86_64" -> 2  // ELFCLASS64
                else                  -> 1  // ELFCLASS32
            }
            val machine: Int = when (abi) {
                "arm64-v8a"   -> 0xB7   // EM_AARCH64
                "armeabi-v7a" -> 0x28   // EM_ARM
                "x86_64"      -> 0x3E   // EM_X86_64
                "x86"         -> 0x03   // EM_386
                else          -> 0xB7
            }
            val headerSize = if (elfClass == 2.toByte()) 64 else 52
            val buf = ByteArray(headerSize)

            // e_ident
            buf[0] = 0x7F; buf[1] = 'E'.code.toByte(); buf[2] = 'L'.code.toByte(); buf[3] = 'F'.code.toByte()
            buf[4] = elfClass          // EI_CLASS
            buf[5] = 1                 // EI_DATA: ELFDATA2LSB (little-endian)
            buf[6] = 1                 // EI_VERSION: EV_CURRENT
            buf[7] = 0                 // EI_OSABI: ELFOSABI_NONE
            // buf[8..15] = 0 (EI_ABIVERSION + padding)

            val off = 16
            // e_type = ET_DYN (3), LE16
            buf[off]   = 3; buf[off+1] = 0
            // e_machine, LE16
            buf[off+2] = (machine and 0xFF).toByte(); buf[off+3] = ((machine shr 8) and 0xFF).toByte()
            // e_version = EV_CURRENT (1), LE32
            buf[off+4] = 1
            // All remaining fields (entry, phoff, shoff, flags, sizes, counts) = 0

            return buf
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Inject native bridge libraries into the APK at [apkPath].
     *
     * Produces a new temporary APK alongside the input file.
     *
     * @param apkPath Path to the working (dex-injected) APK.
     * @param options Patching configuration (currently no native-lib-specific flags).
     * @return Absolute path to the modified APK with native libraries injected.
     */
    fun inject(apkPath: String, options: PatchOptions): String {
        val inputFile = File(apkPath)
        require(inputFile.exists()) { "APK not found: $apkPath" }

        val ts      = System.currentTimeMillis()
        val outFile = File(
            inputFile.parent ?: System.getProperty("java.io.tmpdir"),
            "${inputFile.nameWithoutExtension}_native_$ts.apk"
        )

        Log.i(TAG, "Injecting native libs into: $apkPath → ${outFile.absolutePath}")

        ZipFile(inputFile).use { srcZip ->

            // ── 1. Detect ABIs present in the APK ────────────────────────────
            val presentAbis: Set<String> = detectAbis(srcZip)
            val targetAbis: Set<String>  = if (presentAbis.isEmpty()) {
                Log.w(TAG, "No native libs found — defaulting to $FALLBACK_ABI")
                setOf(FALLBACK_ABI)
            } else {
                presentAbis
            }
            Log.d(TAG, "Target ABIs for injection: $targetAbis")

            // ── 2. Pre-compute which lib entries we will inject ───────────────
            //    key = "lib/<abi>/libzenpatch_bridge.so"
            val injectionMap: Map<String, ByteArray> = targetAbis.associate { abi ->
                val entryName = "lib/$abi/$BRIDGE_LIB_NAME"
                val bytes     = getBridgeLibBytes(abi)
                entryName to bytes
            }

            // ── 3. Build the output APK ───────────────────────────────────────
            //    We need to track the current write position to compute alignment.
            //    ZipOutputStream does not expose this, so we wrap it in a
            //    counting stream.
            val countingOut = CountingOutputStream(FileOutputStream(outFile))
            ZipOutputStream(countingOut).use { zos ->

                // ── 3a. Existing entries (copy verbatim, replacing any existing
                //         libzenpatch_bridge.so with our injected version) ──────
                srcZip.entries().asSequence().forEach { srcEntry ->
                    if (injectionMap.containsKey(srcEntry.name)) {
                        // Will be written in step 3b — skip the stale copy
                        Log.d(TAG, "Skipping existing entry to be replaced: ${srcEntry.name}")
                        return@forEach
                    }
                    copyEntry(zos, srcZip, srcEntry, countingOut)
                }

                // ── 3b. Inject bridge .so files with 16 KB alignment ──────────
                for ((entryName, bytes) in injectionMap) {
                    writeAlignedSo(zos, countingOut, entryName, bytes)
                }
            }
        }

        Log.i(TAG, "Native lib injection complete: ${outFile.absolutePath}")
        return outFile.absolutePath
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ABI detection
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Scan the APK ZIP for `lib/<abi>/*.so` entries and return the distinct ABI
     * directory names found.
     */
    private fun detectAbis(zip: ZipFile): Set<String> {
        val libRegex = Regex("^lib/([^/]+)/[^/]+\\.so$")
        return zip.entries().asSequence()
            .mapNotNull { libRegex.find(it.name)?.groupValues?.get(1) }
            .toSet()
            .also { Log.d(TAG, "Detected ABIs: $it") }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bridge library loading
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Return the bytes of `libzenpatch_bridge.so` for the given [abi].
     *
     * Resolution order:
     * 1. `assets/zenpatch/lib/<abi>/libzenpatch_bridge.so` inside the patcher
     *    module's own classpath (populated by the CI build for release builds).
     * 2. A minimal but structurally valid ELF shared-object stub (placeholder
     *    for development / build-time use).
     *
     * TODO: Provide real pre-compiled binaries via the :bridge CMake module.
     *
     * @param abi Target ABI string (e.g. "arm64-v8a").
     * @return Raw bytes of the .so file.
     */
    private fun getBridgeLibBytes(abi: String): ByteArray {
        val resourcePath = "assets/zenpatch/lib/$abi/$BRIDGE_LIB_NAME"
        val stream = runCatching {
            NativeLibInjector::class.java.classLoader
                ?.getResourceAsStream(resourcePath)
        }.getOrNull()

        if (stream != null) {
            return stream.use { it.readBytes() }.also {
                Log.d(TAG, "Loaded bridge lib for $abi from assets (${it.size} bytes)")
            }
        }

        Log.w(TAG, "Bridge lib not found for $abi at '$resourcePath' — using ELF stub. " +
                "TODO: build libzenpatch_bridge.so via :bridge CMake module.")
        return buildElfStub(abi)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ZIP writing helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Copy a single entry from [srcZip] into [zos], preserving compression
     * method and all metadata.
     *
     * Regular (non-`.so`) entries are copied with their original compression
     * method; we do not re-align them.
     */
    private fun copyEntry(
        zos: ZipOutputStream,
        srcZip: ZipFile,
        srcEntry: ZipEntry,
        counter: CountingOutputStream
    ) {
        if (!DexInjector.isSafeZipEntryName(srcEntry.name)) {
            Log.w(TAG, "Skipping unsafe ZIP entry name: '${srcEntry.name}'")
            return
        }
        val newEntry = ZipEntry(srcEntry.name).apply {
            method  = srcEntry.method
            comment = srcEntry.comment
            time    = srcEntry.time
            if (srcEntry.extra != null) extra = srcEntry.extra
            if (srcEntry.method == ZipEntry.STORED) {
                size           = srcEntry.size
                compressedSize = srcEntry.compressedSize
                crc            = srcEntry.crc
            }
        }
        zos.putNextEntry(newEntry)
        srcZip.getInputStream(srcEntry).use { it.copyTo(zos) }
        zos.closeEntry()
    }

    /**
     * Write a `.so` file into [zos] with the data offset aligned to [SO_ALIGNMENT]
     * bytes from the start of the ZIP file.
     *
     * ### Alignment mechanics
     *
     * The data offset of a ZIP entry is:
     * ```
     * dataOffset = currentPosition
     *            + LOCAL_HEADER_FIXED_SIZE
     *            + entryName.length (UTF-8 bytes)
     *            + extra.length
     * ```
     *
     * We set `entry.extra` to a zero-padded byte array of the exact size needed
     * to push `dataOffset` to the next 16 384-byte boundary **before** calling
     * `putNextEntry`.
     *
     * The entry uses `STORED` compression so the OS can `mmap` the data directly.
     *
     * @param zos        Destination ZipOutputStream.
     * @param counter    Wraps the underlying OutputStream; provides current byte position.
     * @param entryName  ZIP entry path (e.g. `lib/arm64-v8a/libzenpatch_bridge.so`).
     * @param data       Raw bytes of the `.so` file.
     */
    private fun writeAlignedSo(
        zos: ZipOutputStream,
        counter: CountingOutputStream,
        entryName: String,
        data: ByteArray
    ) {
        // Compute CRC-32 upfront — required for STORED entries
        val crc32 = CRC32().also { it.update(data) }.value

        val nameBytes = entryName.toByteArray(Charsets.UTF_8)

        // Current write position in the ZIP stream (before the local header)
        val currentPos = counter.bytesWritten

        // Initial data offset with no extra field
        val dataOffsetNoExtra = currentPos +
                LOCAL_HEADER_FIXED_SIZE +
                nameBytes.size

        // How many extra bytes are needed to align the data start?
        val remainder = (dataOffsetNoExtra % SO_ALIGNMENT).toInt()
        val paddingNeeded = if (remainder == 0) 0 else (SO_ALIGNMENT - remainder)

        Log.d(TAG, "Aligning $entryName: currentPos=$currentPos " +
                "dataOffsetNoExtra=$dataOffsetNoExtra paddingNeeded=$paddingNeeded")

        val extraPadding = ByteArray(paddingNeeded)   // zero-filled padding

        val entry = ZipEntry(entryName).apply {
            method         = ZipEntry.STORED          // must not compress .so for alignment
            size           = data.size.toLong()
            compressedSize = data.size.toLong()
            crc            = crc32
            extra          = extraPadding              // drives the alignment
        }

        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()

        Log.d(TAG, "Written aligned .so: $entryName (${data.size} bytes, padding=$paddingNeeded)")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CountingOutputStream
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * An [java.io.OutputStream] wrapper that tracks how many bytes have been written.
     *
     * Used to determine the current offset within the ZIP file so we can
     * compute the correct alignment padding.
     */
    private class CountingOutputStream(
        private val delegate: FileOutputStream
    ) : java.io.OutputStream() {

        /** Total number of bytes written so far. */
        var bytesWritten: Long = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b)
            bytesWritten++
        }

        override fun write(b: ByteArray) {
            delegate.write(b)
            bytesWritten += b.size
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            bytesWritten += len
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }
}
