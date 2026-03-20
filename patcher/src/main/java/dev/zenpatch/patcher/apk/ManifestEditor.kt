// SPDX-License-Identifier: GPL-3.0-only
package dev.zenpatch.patcher.apk

import android.util.Log
import dev.zenpatch.patcher.model.ApkInfo
import dev.zenpatch.patcher.model.PatchOptions
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Modifies the binary `AndroidManifest.xml` (AXML format) embedded in a target APK.
 *
 * ### Modifications performed
 *
 * 1. **Application class replacement** — the `android:name` attribute on the
 *    `<application>` element is patched to point to
 *    `dev.zenpatch.runtime.ZenPatchAppProxy`.  The original class name is preserved
 *    so the runtime can delegate `Application` lifecycle calls to it.
 *
 * 2. **Configuration asset** — a `assets/zenpatch/config.properties` file is
 *    written (or replaced) inside the APK with the original Application class name
 *    and signature-spoof flag.  This is read at runtime by
 *    `ZenPatchAppProxy` and the `SignatureSpoof` hook.
 *
 * 3. **Debuggable flag** — optionally clears the `android:debuggable` attribute
 *    from the `<application>` element based on [PatchOptions.keepDebuggable].
 *
 * ### AXML patching strategy
 *
 * Writing a fully conformant AXML file from scratch (including string-pool
 * re-indexing, chunk-size recalculation, and resource-table updates) is extremely
 * complex.  Instead we use an **in-place binary patch** approach:
 *
 * - The string pool is scanned for an existing string that equals the original
 *   Application class name.  If found and the replacement string is no longer,
 *   the string data is overwritten in-place (the pool offsets stay valid).
 * - If the replacement string is *longer* than the original, we append a new
 *   UTF-8 string entry to the string pool (requires updating pool sizes and the
 *   string count).  We then update the `android:name` attribute's string-index
 *   field to point to the new entry.
 * - The `android:debuggable` attribute value (a 4-byte integer) is patched
 *   in-place if its current value conflicts with [PatchOptions.keepDebuggable].
 *
 * Meta-data and ContentProvider entries are **not** injected into the binary
 * manifest directly; instead they are communicated via the
 * `assets/zenpatch/config.properties` sidecar file, which is simpler and equally
 * reliable for our runtime.
 *
 * References:
 * - [AOSP ResourceTypes.h](https://cs.android.com/android/platform/superproject/+/main:frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h)
 * - [ApkTool AXmlResourceParser](https://github.com/iBotPeaches/Apktool)
 */
class ManifestEditor {

    companion object {
        private const val TAG = "ManifestEditor"

        // ── Chunk types ───────────────────────────────────────────────────────
        private const val CHUNK_STRING_POOL    = 0x0001
        private const val CHUNK_XML_START_ELEM = 0x0102

        // ── Android attribute resource IDs ────────────────────────────────────
        private const val ATTR_NAME       = 0x01010003   // android:name
        private const val ATTR_DEBUGGABLE = 0x0101000f   // android:debuggable

        // ── ZenPatch replacement constants ────────────────────────────────────
        private const val ZENPATCH_APP_PROXY = "dev.zenpatch.runtime.ZenPatchAppProxy"
        private const val CONFIG_ASSET_PATH  = "assets/zenpatch/config.properties"

        /** UTF-8 flag in ResStringPool_header.flags */
        private const val STRING_FLAG_UTF8 = 0x100
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Apply all required manifest modifications to the APK at [apkPath].
     *
     * The original APK is not modified; a new temporary APK is produced and its
     * path is returned.
     *
     * @param apkPath  Path to the working (dex + native-injected) APK.
     * @param apkInfo  Metadata from the analysis phase.
     * @param options  Patching configuration.
     * @return Absolute path to the modified APK.
     */
    fun patch(apkPath: String, apkInfo: ApkInfo, options: PatchOptions): String {
        val inputFile = File(apkPath)
        require(inputFile.exists()) { "APK not found: $apkPath" }

        val ts      = System.currentTimeMillis()
        val outFile = File(
            inputFile.parent ?: System.getProperty("java.io.tmpdir"),
            "${inputFile.nameWithoutExtension}_manifest_$ts.apk"
        )

        Log.i(TAG, "Patching manifest in: $apkPath → ${outFile.absolutePath}")
        Log.d(TAG, "Original application class: ${apkInfo.applicationClass}")

        ZipFile(inputFile).use { srcZip ->

            // ── 1. Read and patch the binary manifest ─────────────────────────
            val manifestEntry = srcZip.getEntry("AndroidManifest.xml")
                ?: error("AndroidManifest.xml not found in APK: $apkPath")
            val originalManifestBytes = srcZip.getInputStream(manifestEntry).readBytes()

            val patchedManifest = patchManifestBytes(
                originalManifestBytes,
                apkInfo.applicationClass,
                options
            )

            // ── 2. Build config.properties content ────────────────────────────
            val configProps = buildConfigProperties(apkInfo, options)

            // ── 3. Write new APK ──────────────────────────────────────────────
            ZipOutputStream(FileOutputStream(outFile)).use { zos ->

                // Copy all entries except AndroidManifest.xml and the config asset
                srcZip.entries().asSequence()
                    .filter { it.name != "AndroidManifest.xml" && it.name != CONFIG_ASSET_PATH }
                    .forEach { srcEntry ->
                        if (!DexInjector.isSafeZipEntryName(srcEntry.name)) {
                            Log.w(TAG, "Skipping unsafe ZIP entry name: '${srcEntry.name}'")
                            return@forEach
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

                // Write patched AndroidManifest.xml
                val manifestOutEntry = ZipEntry("AndroidManifest.xml").apply {
                    method = ZipEntry.DEFLATED
                }
                zos.putNextEntry(manifestOutEntry)
                zos.write(patchedManifest)
                zos.closeEntry()
                Log.d(TAG, "Written patched manifest (${patchedManifest.size} bytes)")

                // Write config.properties asset
                val configEntry = ZipEntry(CONFIG_ASSET_PATH).apply {
                    method = ZipEntry.DEFLATED
                }
                zos.putNextEntry(configEntry)
                zos.write(configProps.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                Log.d(TAG, "Written config asset: $CONFIG_ASSET_PATH")
            }
        }

        Log.i(TAG, "Manifest patching complete: ${outFile.absolutePath}")
        return outFile.absolutePath
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Manifest binary patching
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Apply in-place patches to the raw AXML bytes of `AndroidManifest.xml`.
     *
     * Patches applied:
     * - Replace or insert the `android:name` value on `<application>`.
     * - Optionally clear `android:debuggable`.
     *
     * @param bytes             Raw AXML bytes (read directly from APK ZIP).
     * @param originalAppClass  Fully-qualified original Application class name,
     *                          or null if the app had no custom Application.
     * @param options           Patching options.
     * @return Patched AXML bytes (may be a new array if string pool was extended).
     */
    private fun patchManifestBytes(
        bytes: ByteArray,
        originalAppClass: String?,
        options: PatchOptions
    ): ByteArray {
        if (bytes.size < 8) {
            Log.w(TAG, "Manifest too short to patch — returning as-is")
            return bytes
        }

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // Validate AXML magic
        val magic = buf.getInt(0)
        if (magic != 0x00080003) {
            Log.e(TAG, "Invalid AXML magic 0x${Integer.toHexString(magic)} — skipping manifest patch")
            return bytes
        }

        // Parse string pool to build an index and locate string data
        val stringPool = parseStringPool(buf) ?: run {
            Log.e(TAG, "Could not parse string pool — skipping manifest patch")
            return bytes
        }

        // Working copy we can modify
        val workBuf = bytes.copyOf()

        // ── Patch android:name on <application> ──────────────────────────────
        val appNamePatched = patchApplicationName(workBuf, stringPool, originalAppClass)

        // ── Patch android:debuggable ─────────────────────────────────────────
        if (!options.keepDebuggable) {
            patchDebuggable(appNamePatched, stringPool, enabled = false)
        }

        return appNamePatched
    }

    // ─────────────────────────────────────────────────────────────────────────
    // String pool parsing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Metadata about the string pool found in the AXML file.
     *
     * @property chunkStart     Byte offset of the string pool chunk header.
     * @property chunkSize      Total size of the string pool chunk in bytes.
     * @property stringCount    Number of string entries.
     * @property stringsStart   Offset of the first string data byte, relative to [chunkStart].
     * @property isUtf8         True if strings are encoded as UTF-8; false for UTF-16LE.
     * @property offsetsBase    Byte offset (absolute) of the string offsets array.
     * @property strings        Decoded string values.
     * @property resourceIds    The parallel resource-ID array (may be empty).
     * @property resourceIdChunkStart  Byte offset of the resource-ID chunk (or -1 if absent).
     */
    private data class StringPoolInfo(
        val chunkStart: Int,
        val chunkSize: Int,
        val stringCount: Int,
        val stringsStart: Int,
        val isUtf8: Boolean,
        val offsetsBase: Int,
        val strings: Array<String>,
        val resourceIds: IntArray,
        val resourceIdChunkStart: Int
    )

    /**
     * Walk the AXML chunk stream and extract string pool metadata.
     * Also records the resource-ID chunk location.
     *
     * @param buf ByteBuffer over the full AXML file bytes, positioned at offset 0.
     * @return [StringPoolInfo] or null on parse failure.
     */
    private fun parseStringPool(buf: ByteBuffer): StringPoolInfo? {
        var pos = 8          // skip the outer AXML file-header chunk
        var spInfo: StringPoolInfo? = null
        var resIdChunkStart = -1

        while (pos + 8 <= buf.limit()) {
            val chunkType  = buf.getShort(pos).toInt() and 0xFFFF
            val chunkSize  = buf.getInt(pos + 4)
            if (chunkSize <= 0 || pos + chunkSize > buf.limit()) break

            when (chunkType) {
                CHUNK_STRING_POOL -> {
                    val headerBase   = pos + 8
                    val stringCount  = buf.getInt(headerBase)
                    val flags        = buf.getInt(headerBase + 8)
                    val stringsStart = buf.getInt(headerBase + 12)
                    val isUtf8       = (flags and STRING_FLAG_UTF8) != 0
                    val offsetsBase  = headerBase + 20   // after ResStringPool_header (28 bytes: 8 header + 20 fields)
                    // Actually ResStringPool_header after chunk header is:
                    //   stringCount(4), styleCount(4), flags(4), stringsStart(4), stylesStart(4) = 20 bytes
                    // So offsets array starts at pos+8+20 = pos+28

                    val strings = Array(stringCount) { i ->
                        val offset    = buf.getInt(offsetsBase + i * 4)
                        val strAbsPos = pos + stringsStart + offset
                        try {
                            if (isUtf8) readUtf8String(buf, strAbsPos)
                            else        readUtf16String(buf, strAbsPos)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to read string $i at $strAbsPos: ${e.message}")
                            ""
                        }
                    }

                    // Parse resource ID table (chunk type 0x0180) — comes right after string pool
                    val nextChunkPos  = pos + chunkSize
                    val nextChunkType = if (nextChunkPos + 8 <= buf.limit())
                        buf.getShort(nextChunkPos).toInt() and 0xFFFF else -1
                    val resIds: IntArray
                    if (nextChunkType == 0x0180) {
                        val resChunkSize = buf.getInt(nextChunkPos + 4)
                        val count        = (resChunkSize - 8) / 4
                        resIds           = IntArray(count) { i -> buf.getInt(nextChunkPos + 8 + i * 4) }
                        resIdChunkStart  = nextChunkPos
                    } else {
                        resIds = intArrayOf()
                    }

                    spInfo = StringPoolInfo(
                        chunkStart            = pos,
                        chunkSize             = chunkSize,
                        stringCount           = stringCount,
                        stringsStart          = stringsStart,
                        isUtf8                = isUtf8,
                        offsetsBase           = offsetsBase,
                        strings               = strings,
                        resourceIds           = resIds,
                        resourceIdChunkStart  = resIdChunkStart
                    )
                }
                else -> { /* skip */ }
            }
            pos += chunkSize
        }

        return spInfo
    }

    // ── String reading helpers ─────────────────────────────────────────────

    private fun readUtf8String(buf: ByteBuffer, pos: Int): String {
        var p = pos
        val b0 = buf.get(p).toInt() and 0xFF
        p += if ((b0 and 0x80) != 0) 2 else 1
        val lenByte = buf.get(p).toInt() and 0xFF
        val utf8Len: Int
        if ((lenByte and 0x80) != 0) {
            utf8Len = ((lenByte and 0x7F) shl 8) or (buf.get(p + 1).toInt() and 0xFF)
            p += 2
        } else {
            utf8Len = lenByte
            p += 1
        }
        if (utf8Len == 0) return ""
        val data = ByteArray(utf8Len)
        buf.position(p)
        buf.get(data)
        return String(data, Charsets.UTF_8)
    }

    private fun readUtf16String(buf: ByteBuffer, pos: Int): String {
        var p = pos
        val f = buf.getShort(p).toInt() and 0xFFFF
        val len: Int
        if ((f and 0x8000) != 0) {
            len = ((f and 0x7FFF) shl 16) or (buf.getShort(p + 2).toInt() and 0xFFFF)
            p += 4
        } else {
            len = f
            p += 2
        }
        if (len == 0) return ""
        val data = ByteArray(len * 2)
        buf.position(p)
        buf.get(data)
        return String(data, Charsets.UTF_16LE)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Application class name patching
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Patch the `android:name` attribute on the `<application>` element to
     * [ZENPATCH_APP_PROXY].
     *
     * ### Strategy
     *
     * We walk the AXML chunk stream looking for `CHUNK_XML_START_ELEM` chunks
     * whose element name is `"application"`.  For each `android:name` attribute
     * (resource ID [ATTR_NAME]):
     *
     * - We find which string-pool index the attribute currently points to.
     * - If the target string ([ZENPATCH_APP_PROXY]) already exists in the pool,
     *   we simply update the index reference.
     * - If the original Application class string (`originalAppClass`) is in the
     *   pool and the replacement is **no longer** than it (in its encoded form),
     *   we overwrite it in-place.
     * - Otherwise we **append** a new UTF-8 string entry to the string pool,
     *   update the pool's `stringCount` and `chunkSize` fields, and then set the
     *   attribute index to the new entry.
     *
     * Appending to the string pool requires patching the following fields in
     * the AXML file:
     *   - `StringPool.chunkSize` (+= encoded length of new entry + 4 for its offset)
     *   - `StringPool.stringCount` (+= 1)
     *   - The outer file chunk-size fields of every chunk that follows the string
     *     pool (since they reference absolute offsets we shift them).  Actually,
     *     in AXML the chunk sizes are self-contained; only the file-header
     *     file-size field at offset 4 needs to be updated.
     *
     * @param bytes           Working copy of the raw AXML bytes.
     * @param sp              Parsed string pool metadata.
     * @param originalAppClass Original Application class name (may be null).
     * @return Patched bytes (may be a new, larger array if the pool was extended).
     */
    private fun patchApplicationName(
        bytes: ByteArray,
        sp: StringPoolInfo,
        originalAppClass: String?
    ): ByteArray {
        // Check if replacement is already in the pool
        val existingIdx = sp.strings.indexOfFirst { it == ZENPATCH_APP_PROXY }

        if (existingIdx >= 0) {
            Log.d(TAG, "ZenPatch proxy class already in string pool at index $existingIdx")
            // Still need to point the attribute at it
            return patchAppNameAttribute(bytes, sp, existingIdx)
        }

        // Try in-place replacement if original fits
        if (originalAppClass != null) {
            val origIdx = sp.strings.indexOfFirst { it == originalAppClass }
            if (origIdx >= 0) {
                val inPlaceResult = tryInPlaceStringReplacement(
                    bytes, sp, origIdx, ZENPATCH_APP_PROXY
                )
                if (inPlaceResult != null) {
                    Log.d(TAG, "Replaced application class in-place at string index $origIdx")
                    return inPlaceResult
                }
            }
        }

        // Append new string and update attribute index
        Log.d(TAG, "Appending new string to pool: $ZENPATCH_APP_PROXY")
        return appendStringAndPatch(bytes, sp, ZENPATCH_APP_PROXY, originalAppClass)
    }

    /**
     * Update the string-index field of the `android:name` attribute on
     * `<application>` to point to [newStringIndex] in the string pool.
     *
     * This is a pure in-place 4-byte integer write; no size changes occur.
     */
    private fun patchAppNameAttribute(
        bytes: ByteArray,
        sp: StringPoolInfo,
        newStringIndex: Int
    ): ByteArray {
        val result = bytes.copyOf()
        val buf = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)

        // Walk START_ELEMENT chunks looking for <application>
        var pos = 8
        while (pos + 8 <= result.size) {
            val chunkType = buf.getShort(pos).toInt() and 0xFFFF
            val chunkSize = buf.getInt(pos + 4)
            if (chunkSize <= 0 || pos + chunkSize > result.size) break

            if (chunkType == CHUNK_XML_START_ELEM) {
                val extStart   = pos + 16
                val nameIdx    = buf.getInt(extStart + 4)
                val elemName   = sp.strings.getOrElse(nameIdx) { "" }
                val attrCount  = buf.getShort(extStart + 12).toInt() and 0xFFFF
                val attrBase   = extStart + 16

                if (elemName == "application") {
                    for (i in 0 until attrCount) {
                        val attrOff    = attrBase + i * 20
                        val attrNameIdx = buf.getInt(attrOff + 4)
                        val attrResId  = sp.resourceIds.getOrElse(attrNameIdx) { 0 }

                        if (attrResId == ATTR_NAME) {
                            // rawValue (string index) is at attrOff + 8
                            buf.putInt(attrOff + 8, newStringIndex)
                            // Also patch the data field (used when dataType == TYPE_STRING)
                            buf.putInt(attrOff + 16, newStringIndex)
                            Log.d(TAG, "Patched android:name attribute to string index $newStringIndex")
                            return result
                        }
                    }
                    Log.w(TAG, "<application> found but no android:name attribute — cannot patch")
                    break
                }
            }
            pos += chunkSize
        }

        Log.w(TAG, "<application> element not found in manifest")
        return result
    }

    /**
     * Attempt to overwrite the string at [stringIndex] in-place with [newValue].
     *
     * This only works when [newValue] (in its encoded form) is **no longer** than
     * the original.  Excess space is filled with null bytes (which AXML parsers
     * will treat as an empty continuation).
     *
     * @return Patched byte array, or null if in-place replacement is not possible.
     */
    private fun tryInPlaceStringReplacement(
        bytes: ByteArray,
        sp: StringPoolInfo,
        stringIndex: Int,
        newValue: String
    ): ByteArray? {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val offset   = buf.getInt(sp.offsetsBase + stringIndex * 4)
        val strStart = sp.chunkStart + sp.stringsStart + offset

        if (!sp.isUtf8) {
            // UTF-16 in-place: measure original char count
            val origLen = buf.getShort(strStart).toInt() and 0xFFFF
            val newChars = newValue.length
            if (newChars > origLen) return null

            val result = bytes.copyOf()
            val resBuf = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
            // Write new length
            resBuf.putShort(strStart, newChars.toShort())
            // Write chars
            val newBytes = newValue.toByteArray(Charsets.UTF_16LE)
            System.arraycopy(newBytes, 0, result, strStart + 2, newBytes.size)
            // Pad with zeros if shorter
            if (newChars < origLen) {
                result.fill(0, strStart + 2 + newBytes.size, strStart + 2 + origLen * 2)
            }
            return result
        }

        // UTF-8 in-place
        var p = strStart
        val utf16Byte = bytes[p].toInt() and 0xFF
        p += if ((utf16Byte and 0x80) != 0) 2 else 1
        val lenByte = bytes[p].toInt() and 0xFF
        val origUtf8Len: Int
        val lenFieldSize: Int
        if ((lenByte and 0x80) != 0) {
            origUtf8Len  = ((lenByte and 0x7F) shl 8) or (bytes[p + 1].toInt() and 0xFF)
            lenFieldSize = 2
        } else {
            origUtf8Len  = lenByte
            lenFieldSize = 1
        }

        val newUtf8Bytes = newValue.toByteArray(Charsets.UTF_8)
        if (newUtf8Bytes.size > origUtf8Len) return null

        val result = bytes.copyOf()

        // Patch length byte(s)
        val lenPos = p
        if (lenFieldSize == 2) {
            result[lenPos]     = (0x80 or ((newUtf8Bytes.size shr 8) and 0x7F)).toByte()
            result[lenPos + 1] = (newUtf8Bytes.size and 0xFF).toByte()
        } else {
            result[lenPos] = (newUtf8Bytes.size and 0x7F).toByte()
        }

        // Patch UTF-16 char-count field
        val charPos  = strStart
        val newChars = newValue.length
        val origUtf16Byte = bytes[charPos].toInt() and 0xFF
        if ((origUtf16Byte and 0x80) != 0) {
            result[charPos]     = (0x80 or ((newChars shr 8) and 0x7F)).toByte()
            result[charPos + 1] = (newChars and 0xFF).toByte()
        } else {
            result[charPos] = (newChars and 0xFF).toByte()
        }

        // Overwrite string data
        val dataStart = lenPos + lenFieldSize
        System.arraycopy(newUtf8Bytes, 0, result, dataStart, newUtf8Bytes.size)
        // Zero-fill remainder
        if (newUtf8Bytes.size < origUtf8Len) {
            result.fill(0, dataStart + newUtf8Bytes.size, dataStart + origUtf8Len)
        }

        return result
    }

    /**
     * Append [newString] to the AXML string pool, then patch the `android:name`
     * attribute on `<application>` to reference the new index.
     *
     * This is the fallback path when in-place replacement is not possible
     * (i.e. the replacement string is longer than the original).
     *
     * The approach:
     * 1. Encode the new string in the pool's native encoding (UTF-8 or UTF-16).
     * 2. Build a new byte array:
     *    a. Bytes before the string-pool chunk's string data area — unchanged.
     *    b. Updated string pool:
     *       - New `stringCount` (+1) at offset 8 within the chunk header body.
     *       - New `chunkSize` (+=  encoded string size + 4 bytes for the offset).
     *       - Existing offset entries + new offset at the end of the array.
     *       - Existing string data + new string encoded at the end.
     *    c. All bytes after the string pool — unchanged.
     * 3. Update the AXML file-header's `fileSize` field (offset 4).
     * 4. Patch the `android:name` attribute to use the new string index.
     *
     * @return New (larger) AXML byte array with the attribute patched.
     */
    private fun appendStringAndPatch(
        bytes: ByteArray,
        sp: StringPoolInfo,
        newString: String,
        originalAppClass: String?
    ): ByteArray {
        // ── Encode the new string in the pool's native format ─────────────────
        val encodedString: ByteArray = if (sp.isUtf8) {
            encodeUtf8PoolString(newString)
        } else {
            encodeUtf16PoolString(newString)
        }

        // The new string's offset within the string-data region equals the
        // current size of that region (it is appended at the very end).
        val newOffset     = sp.chunkSize - sp.stringsStart

        // New chunk size accounts for: one new 4-byte offset + the encoded string bytes
        val newChunkSize  = sp.chunkSize + 4 + encodedString.size

        return buildExtendedAxml(bytes, sp, encodedString, newOffset, newChunkSize, newString, originalAppClass)
    }

    /**
     * Cleanly build a new AXML byte array with one additional string appended to
     * the string pool.
     *
     * Layout of the new array:
     * ```
     * [ bytes before string pool chunk ]
     * [ string pool chunk header (updated stringCount + chunkSize) ]
     * [ updated stringsStart offset ]
     * [ existing offsets[0..stringCount-1] ]
     * [ new offset for the appended string ]
     * [ existing string data ]
     * [ encoded new string ]
     * [ all chunks after the string pool, verbatim ]
     * ```
     *
     * Finally, the AXML file-header's `fileSize` field (bytes 4..7) is updated.
     */
    private fun buildExtendedAxml(
        bytes: ByteArray,
        sp: StringPoolInfo,
        encodedString: ByteArray,
        newOffsetValue: Int,
        newChunkSize: Int,
        newString: String,
        originalAppClass: String?
    ): ByteArray {
        val newStringIndex = sp.stringCount   // index of the appended string

        // Extra bytes: 4 (new offset) + encodedString.size (new data)
        val delta  = 4 + encodedString.size
        val result = ByteArray(bytes.size + delta)
        val resBuf = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)

        // ── Region A: bytes before the string pool chunk ──────────────────────
        System.arraycopy(bytes, 0, result, 0, sp.chunkStart)

        // ── Region B: string pool chunk header (8 bytes) ──────────────────────
        val srcBuf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // chunkType(2) + headerSize(2) + chunkSize(4)
        resBuf.putShort(sp.chunkStart,     srcBuf.getShort(sp.chunkStart))     // type
        resBuf.putShort(sp.chunkStart + 2, srcBuf.getShort(sp.chunkStart + 2)) // headerSize
        resBuf.putInt(sp.chunkStart + 4,   newChunkSize)                        // updated size

        // ── Region C: ResStringPool_header body (20 bytes: counts + flags + offsets) ──
        val headerBodySrc = sp.chunkStart + 8
        val headerBodyDst = sp.chunkStart + 8
        // Copy 20 bytes as-is first, then patch stringCount and stringsStart
        System.arraycopy(bytes, headerBodySrc, result, headerBodyDst, 20)
        // Patch stringCount (+1)
        resBuf.putInt(headerBodyDst, sp.stringCount + 1)
        // stringsStart must account for the extra 4-byte offset we're inserting
        val origStringsStart = sp.stringsStart
        resBuf.putInt(headerBodyDst + 12, origStringsStart + 4)

        // ── Region D: existing offsets array ─────────────────────────────────
        val offsetsSize = sp.stringCount * 4
        System.arraycopy(bytes, sp.offsetsBase, result, sp.offsetsBase, offsetsSize)

        // ── Region E: new offset entry (inserted right after existing offsets) ─
        val newOffsetDst = sp.offsetsBase + offsetsSize
        resBuf.putInt(newOffsetDst, newOffsetValue)

        // ── Region F: existing string data ────────────────────────────────────
        val oldStringDataStart = sp.chunkStart + origStringsStart
        val oldStringDataSize  = sp.chunkSize - origStringsStart
        val newStringDataStart = sp.chunkStart + origStringsStart + 4  // +4 for the new offset
        System.arraycopy(bytes, oldStringDataStart, result, newStringDataStart, oldStringDataSize)

        // ── Region G: new encoded string appended after existing string data ──
        val appendDst = newStringDataStart + oldStringDataSize
        System.arraycopy(encodedString, 0, result, appendDst, encodedString.size)

        // ── Region H: all chunks after the string pool ────────────────────────
        val oldSpEnd = sp.chunkStart + sp.chunkSize
        val newSpEnd = oldSpEnd + delta
        System.arraycopy(bytes, oldSpEnd, result, newSpEnd, bytes.size - oldSpEnd)

        // ── Update AXML file-header fileSize (offset 4) ───────────────────────
        resBuf.putInt(4, result.size)

        // ── Now patch the android:name attribute ──────────────────────────────
        // We need an updated StringPoolInfo to walk the new result correctly.
        val updatedSp = sp.copy(
            stringCount = sp.stringCount + 1,
            chunkSize   = newChunkSize,
            stringsStart = origStringsStart + 4,
            offsetsBase  = sp.offsetsBase,
            strings      = sp.strings + newString
        )

        return patchAppNameAttribute(result, updatedSp, newStringIndex)
    }

    // ── String encoding helpers ────────────────────────────────────────────

    /**
     * Encode [str] as an AXML UTF-8 pool string entry.
     *
     * Layout: [utf16CharCount: 1-2 bytes] [utf8ByteLen: 1-2 bytes] [utf8Data] [0x00]
     */
    private fun encodeUtf8PoolString(str: String): ByteArray {
        val utf8 = str.toByteArray(Charsets.UTF_8)
        val buf  = mutableListOf<Byte>()

        // UTF-16 char count (MUTF-8 length unit; str.length gives UTF-16 units)
        val charLen = str.length
        if (charLen > 0x7F) {
            buf.add((0x80 or ((charLen shr 8) and 0x7F)).toByte())
            buf.add((charLen and 0xFF).toByte())
        } else {
            buf.add((charLen and 0x7F).toByte())
        }

        // UTF-8 byte count
        if (utf8.size > 0x7F) {
            buf.add((0x80 or ((utf8.size shr 8) and 0x7F)).toByte())
            buf.add((utf8.size and 0xFF).toByte())
        } else {
            buf.add((utf8.size and 0x7F).toByte())
        }

        utf8.forEach { buf.add(it) }
        buf.add(0x00)   // null terminator

        return buf.toByteArray()
    }

    /**
     * Encode [str] as an AXML UTF-16LE pool string entry.
     *
     * Layout: [charCount: LE16] [utf16LE data] [0x00 0x00]
     */
    private fun encodeUtf16PoolString(str: String): ByteArray {
        val utf16 = str.toByteArray(Charsets.UTF_16LE)
        val buf   = ByteBuffer.allocate(2 + utf16.size + 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(str.length.toShort())
        buf.put(utf16)
        buf.putShort(0)
        return buf.array()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Debuggable patching
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Patch the `android:debuggable` attribute on `<application>` in-place.
     *
     * The attribute data field is a 4-byte boolean integer (0 = false, 1 = true).
     * We locate it by walking START_ELEMENT chunks and matching the resource ID
     * [ATTR_DEBUGGABLE].
     *
     * @param bytes   Working copy of the AXML bytes (modified in-place).
     * @param sp      String pool info (for decoding element and attribute names).
     * @param enabled Desired value for `android:debuggable`.
     */
    private fun patchDebuggable(
        bytes: ByteArray,
        sp: StringPoolInfo,
        enabled: Boolean
    ) {
        val buf     = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val desired = if (enabled) 1 else 0
        var pos     = 8

        while (pos + 8 <= bytes.size) {
            val chunkType = buf.getShort(pos).toInt() and 0xFFFF
            val chunkSize = buf.getInt(pos + 4)
            if (chunkSize <= 0 || pos + chunkSize > bytes.size) break

            if (chunkType == CHUNK_XML_START_ELEM) {
                val extStart  = pos + 16
                val nameIdx   = buf.getInt(extStart + 4)
                val elemName  = sp.strings.getOrElse(nameIdx) { "" }
                val attrCount = buf.getShort(extStart + 12).toInt() and 0xFFFF
                val attrBase  = extStart + 16

                if (elemName == "application") {
                    for (i in 0 until attrCount) {
                        val attrOff    = attrBase + i * 20
                        val attrNameIdx = buf.getInt(attrOff + 4)
                        val attrResId  = sp.resourceIds.getOrElse(attrNameIdx) { 0 }

                        if (attrResId == ATTR_DEBUGGABLE) {
                            val current = buf.getInt(attrOff + 16)
                            if (current != desired) {
                                buf.putInt(attrOff + 16, desired)
                                Log.d(TAG, "Patched android:debuggable: $current → $desired")
                            } else {
                                Log.d(TAG, "android:debuggable already $desired — no change")
                            }
                            return
                        }
                    }
                    Log.d(TAG, "android:debuggable not present in <application> — skipping")
                    return
                }
            }
            pos += chunkSize
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Config properties asset
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build the content of `assets/zenpatch/config.properties` that will be
     * written into the patched APK.
     *
     * The runtime reads this file to:
     * - Know the original `Application` class to delegate lifecycle calls to.
     * - Decide whether to activate signature-spoofing hooks.
     * - Load embedded Xposed modules.
     *
     * @param apkInfo Metadata from the analysis phase.
     * @param options Patching options.
     * @return Properties file content as a UTF-8 string.
     */
    private fun buildConfigProperties(apkInfo: ApkInfo, options: PatchOptions): String {
        val sb = StringBuilder()
        sb.appendLine("# ZenPatch runtime configuration")
        sb.appendLine("# Generated by ZenPatch ManifestEditor — do not edit manually")
        sb.appendLine()
        sb.appendLine("# Original Application class (may be empty if app had none)")
        sb.appendLine("original.application=${apkInfo.applicationClass ?: ""}")
        sb.appendLine()
        sb.appendLine("# Package name of the patched app")
        sb.appendLine("package.name=${apkInfo.packageName}")
        sb.appendLine()
        sb.appendLine("# Signature spoofing: return original cert to PackageManager queries")
        sb.appendLine("signature.spoof.enabled=${options.enableSignatureSpoof}")
        sb.appendLine()
        sb.appendLine("# Embedded Xposed module APK paths (semicolon-separated)")
        sb.appendLine("module.paths=${options.moduleApkPaths.joinToString(";")}")
        return sb.toString()
    }
}
