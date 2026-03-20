package dev.zenpatch.patcher

import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Injects native libraries (LSPlant + ZenPatch bridge) into APK.
 * Handles 16KB alignment required for Android 16 via ZipEntry.extra padding.
 *
 * Per the Android 16 page size requirements, all STORED (uncompressed) .so entries
 * must be aligned to 16384 (16KB) bytes. This is achieved by padding the ZipEntry
 * extra field so that the file data offset is a multiple of ALIGN_16KB.
 */
class NativeLibInjector {

    data class NativeLib(
        val arch: String,
        val name: String,
        val data: ByteArray
    )

    /**
     * Injects native libs into the APK.
     * @param inputApk Source APK
     * @param libs List of native libs to inject
     * @param outputApk Destination APK
     */
    fun inject(inputApk: File, libs: List<NativeLib>, outputApk: File) {
        Timber.d("Injecting %d native libs into %s", libs.size, inputApk.name)
        outputApk.parentFile?.mkdirs()

        // Group libs by arch
        val libsByArch = libs.groupBy { it.arch }

        ZipFile(inputApk).use { zip ->
            ZipOutputStream(outputApk.outputStream().buffered()).use { zout ->
                // Track what we've added
                val addedEntries = mutableSetOf<String>()

                // First: copy all existing entries
                for (entry in zip.entries()) {
                    if (!isEntryPathSafe(entry.name)) {
                        Timber.w("Skipping unsafe entry: %s", entry.name)
                        continue
                    }

                    val data = zip.getInputStream(entry).use { it.readBytes() }
                    writeEntry(zout, entry.name, data, entry.method == ZipEntry.STORED)
                    addedEntries.add(entry.name)
                }

                // Then: inject new native libs
                for ((arch, archLibs) in libsByArch) {
                    for (lib in archLibs) {
                        val entryName = "lib/$arch/${lib.name}"
                        if (!isEntryPathSafe(entryName)) {
                            Timber.w("Skipping unsafe lib path: %s", entryName)
                            continue
                        }

                        if (addedEntries.contains(entryName)) {
                            Timber.d("Overwriting existing lib: %s", entryName)
                        } else {
                            Timber.d("Injecting new lib: %s (%d bytes)", entryName, lib.data.size)
                        }

                        // .so files must be STORED (uncompressed) for direct mmap
                        writeAligned16KbEntry(zout, entryName, lib.data)
                        addedEntries.add(entryName)
                    }
                }
            }
        }

        Timber.d("Native lib injection complete: %s", outputApk.name)
    }

    /**
     * Writes a ZIP entry. .so files are written as STORED with 16KB alignment.
     */
    private fun writeEntry(zout: ZipOutputStream, name: String, data: ByteArray, stored: Boolean) {
        if (name.endsWith(".so")) {
            writeAligned16KbEntry(zout, name, data)
        } else {
            val entry = ZipEntry(name).apply {
                method = if (stored) ZipEntry.STORED else ZipEntry.DEFLATED
                if (stored) {
                    size = data.size.toLong()
                    compressedSize = data.size.toLong()
                    crc = computeCrc32(data)
                }
            }
            zout.putNextEntry(entry)
            zout.write(data)
            zout.closeEntry()
        }
    }

    /**
     * Writes a STORED .so entry with 16KB alignment.
     *
     * The ZIP local file header has a variable-size extra field. By setting the
     * extra field to an appropriate length, we can pad the data offset to be
     * aligned to ALIGN_16KB bytes, satisfying Android 16's page size requirements.
     *
     * Layout:
     *   [Local File Header: 30 bytes + name.len + extra.len] [File Data]
     *
     * We need: (30 + name.length + extra.length) % ALIGN_16KB == 0
     * So: extra.length = (ALIGN_16KB - (30 + name.length) % ALIGN_16KB) % ALIGN_16KB
     *
     * The extra field contains a ZIP64 extended information extra field or custom padding.
     */
    private fun writeAligned16KbEntry(zout: ZipOutputStream, name: String, data: ByteArray) {
        val crc = computeCrc32(data)
        val fixedHeaderSize = 30
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val nameLen = nameBytes.size

        // Calculate required extra length for 16KB alignment
        val offsetWithoutExtra = fixedHeaderSize + nameLen
        val remainder = offsetWithoutExtra % ALIGN_16KB
        val extraNeeded = if (remainder == 0) 0 else ALIGN_16KB - remainder

        // Build extra field: custom ZenPatch alignment marker + padding
        val extra = buildAlignmentExtra(extraNeeded)

        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = data.size.toLong()
            compressedSize = data.size.toLong()
            this.crc = crc
            this.extra = extra
        }

        zout.putNextEntry(entry)
        zout.write(data)
        zout.closeEntry()

        Timber.d("Wrote aligned entry: %s (alignment extra: %d bytes)", name, extraNeeded)
    }

    /**
     * Builds a ZIP extra field padded to the required length.
     * Uses a custom vendor tag (0x4150 = "PA" for "padding alignment").
     */
    private fun buildAlignmentExtra(requiredLength: Int): ByteArray {
        if (requiredLength == 0) return ByteArray(0)
        if (requiredLength < 4) {
            // Not enough room for a valid extra field header; pad with zeros
            return ByteArray(requiredLength)
        }
        // Extra field format: ID (2 bytes LE) + Size (2 bytes LE) + Data (Size bytes)
        val dataSize = requiredLength - 4
        val extra = ByteArray(requiredLength)
        // ID: 0x4150 (custom alignment padding marker)
        extra[0] = 0x50
        extra[1] = 0x41
        // Data size
        extra[2] = (dataSize and 0xFF).toByte()
        extra[3] = ((dataSize shr 8) and 0xFF).toByte()
        // Rest is zeros (padding)
        return extra
    }

    private fun computeCrc32(data: ByteArray): Long {
        val crc = CRC32()
        crc.update(data)
        return crc.value
    }

    private fun isEntryPathSafe(name: String): Boolean {
        if (name.contains("..")) return false
        if (name.startsWith("/")) return false
        if (name.contains("\u0000")) return false
        return true
    }

    companion object {
        const val ALIGN_16KB = 16384

        val SUPPORTED_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
    }
}
