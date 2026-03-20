// SPDX-License-Identifier: GPL-3.0-only
package dev.zenpatch.patcher.apk

import android.util.Log
import dev.zenpatch.patcher.model.ApkInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Parses an APK file to extract metadata without performing a full decompile.
 *
 * Reads:
 *  - Binary AndroidManifest.xml (AXML format) — fully custom parser, no external library
 *  - Dex file enumeration (classes.dex, classes2.dex, …)
 *  - Native library ABIs and filenames
 *  - APK signing scheme version flags (v1–v4)
 *  - Split APK detection
 */
class ApkAnalyzer {

    companion object {
        private const val TAG = "ApkAnalyzer"

        // ── AXML chunk types ──────────────────────────────────────────────────
        private const val CHUNK_STRING_POOL    = 0x0001
        private const val CHUNK_XML_RESOURCE   = 0x0180
        private const val CHUNK_XML_START_NS   = 0x0100
        private const val CHUNK_XML_END_NS     = 0x0101
        private const val CHUNK_XML_START_ELEM = 0x0102
        private const val CHUNK_XML_END_ELEM   = 0x0103
        private const val CHUNK_XML_CDATA      = 0x0104

        // ── AXML value types ─────────────────────────────────────────────────
        private const val TYPE_STRING  = 0x03
        private const val TYPE_INT_DEC = 0x10  // TYPE_INT_DEC in ResTable_value
        private const val TYPE_INT_HEX = 0x11
        private const val TYPE_BOOLEAN = 0x12

        // ── Android attribute resource IDs ───────────────────────────────────
        private const val ATTR_VERSION_CODE       = 0x0101021b
        private const val ATTR_VERSION_NAME       = 0x0101021c
        private const val ATTR_MIN_SDK            = 0x0101020c
        private const val ATTR_TARGET_SDK         = 0x01010270
        private const val ATTR_NAME               = 0x01010003
        private const val ATTR_DEBUGGABLE         = 0x0101000f
        private const val ATTR_SPLIT              = 0x01010163  // android:split
        private const val ATTR_CONFIG_FOR_SPLIT   = 0x01010515  // android:configForSplit

        // ── APK Signing Block ─────────────────────────────────────────────────
        private const val APK_SIG_BLOCK_MAGIC_LO = 0x20676953204b5041L // "APK Sig "
        private const val APK_SIG_BLOCK_MAGIC_HI = 0x3234206b636f6c42L // "Block 42"
        private const val SIG_BLOCK_ID_V2  = 0x7109871a
        private const val SIG_BLOCK_ID_V3  = 0xf05368c0.toInt()
        private const val SIG_BLOCK_ID_V31 = 0x1b93ad61
        private const val SIG_BLOCK_ID_V4  = 0x42726577
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Analyse the given APK and return its metadata.
     *
     * @param apkPath Absolute path to the APK file.
     * @return [ApkInfo] if parsing succeeds, null on unrecoverable error.
     */
    fun analyse(apkPath: String): ApkInfo? {
        val file = File(apkPath)
        if (!file.exists() || !file.canRead()) {
            Log.e(TAG, "Cannot read APK: $apkPath")
            return null
        }

        return try {
            ZipFile(file).use { zip ->
                Log.d(TAG, "Analysing APK: $apkPath  (${file.length()} bytes)")

                // 1. Parse binary AndroidManifest.xml
                val manifestEntry = zip.getEntry("AndroidManifest.xml")
                    ?: run { Log.e(TAG, "No AndroidManifest.xml in $apkPath"); return null }
                val manifestBytes = zip.getInputStream(manifestEntry).readBytes()
                val manifest = parseAxml(manifestBytes)
                    ?: run { Log.e(TAG, "Failed to parse AXML manifest in $apkPath"); return null }

                // 2. Enumerate dex files
                val dexFiles = enumerateDexFiles(zip)

                // 3. Collect native libraries, grouped by ABI
                val nativeLibs = collectNativeLibs(zip)

                // 4. Detect signing schemes
                val signingSchemes = detectSigningSchemes(file, zip)

                Log.d(TAG, "APK analysis complete: pkg=${manifest.packageName} " +
                        "dex=${dexFiles.size} nativeAbi=${nativeLibs.keys} " +
                        "signs=$signingSchemes split=${manifest.isSplitApk}")

                ApkInfo(
                    packageName     = manifest.packageName,
                    versionName     = manifest.versionName,
                    versionCode     = manifest.versionCode,
                    minSdkVersion   = manifest.minSdkVersion,
                    targetSdkVersion = manifest.targetSdkVersion,
                    applicationClass = manifest.applicationClass,
                    dexFiles        = dexFiles,
                    nativeLibs      = nativeLibs,
                    signingSchemes  = signingSchemes,
                    isSplitApk      = manifest.isSplitApk,
                    splitApkPaths   = emptyList(), // resolved externally by the engine
                    isDebuggable    = manifest.isDebuggable
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unhandled exception while analysing $apkPath", e)
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Dex enumeration
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Collect all classes*.dex entries from the APK ZIP, sorted numerically.
     * e.g. classes.dex, classes2.dex, classes3.dex, …
     */
    private fun enumerateDexFiles(zip: ZipFile): List<String> {
        val dexRegex = Regex("^classes(\\d*)\\.dex$")
        return zip.entries().asSequence()
            .map { it.name }
            .filter { dexRegex.matches(it) }
            .sortedWith(Comparator { a, b ->
                val numA = dexRegex.find(a)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val numB = dexRegex.find(b)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                numA.compareTo(numB)
            })
            .toList()
            .also { Log.d(TAG, "Dex files: $it") }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Native library detection
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Collect all lib/<abi>/<name>.so entries from the APK, grouped by ABI.
     *
     * Recognised ABIs: arm64-v8a, armeabi-v7a, x86, x86_64
     */
    private fun collectNativeLibs(zip: ZipFile): Map<String, List<String>> {
        val libRegex = Regex("^lib/([^/]+)/([^/]+\\.so)$")
        val result = mutableMapOf<String, MutableList<String>>()
        zip.entries().asSequence()
            .map { it.name }
            .forEach { entryName ->
                libRegex.find(entryName)?.let { match ->
                    val abi  = match.groupValues[1]
                    val soName = match.groupValues[2]
                    result.getOrPut(abi) { mutableListOf() }.add(soName)
                }
            }
        return result.mapValues { it.value.sorted() }
            .also { Log.d(TAG, "Native libs: $it") }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Signing scheme detection
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Detect which APK signing schemes (v1–v4) are present in the given APK.
     *
     * v1 (JAR signing): META-INF/*.RSA / *.DSA / *.EC block-signing entries
     * v2/v3/v3.1/v4:    APK Signing Block immediately before the ZIP Central
     *                   Directory, identified by a 16-byte magic value and
     *                   individual block IDs.
     */
    private fun detectSigningSchemes(apkFile: File, zip: ZipFile): Set<Int> {
        val schemes = mutableSetOf<Int>()

        // v1: check for META-INF signature files
        val v1Regex = Regex("^META-INF/[^/]+\\.(RSA|DSA|EC)$", RegexOption.IGNORE_CASE)
        val hasV1 = zip.entries().asSequence().any { v1Regex.matches(it.name) }
        if (hasV1) {
            schemes.add(1)
            Log.d(TAG, "Signing: v1 detected")
        }

        // v2/v3/v3.1/v4: parse APK Signing Block from raw file bytes
        try {
            apkFile.inputStream().buffered().use { stream ->
                val bytes = stream.readBytes()
                detectV2PlusSchemes(bytes, schemes)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read APK bytes for signature block detection", e)
        }

        // v4: also check for external .idsig file
        val idSigFile = File(apkFile.parent, apkFile.name + ".idsig")
        if (idSigFile.exists()) {
            schemes.add(4)
            Log.d(TAG, "Signing: v4 detected via .idsig file")
        }

        if (schemes.isEmpty()) {
            Log.w(TAG, "No signing scheme detected for ${apkFile.name}")
        }
        return schemes
    }

    /**
     * Locate the APK Signing Block within the raw APK bytes and extract the
     * block IDs present, mapping them to scheme version numbers.
     *
     * Layout (APK Signing Block):
     *   [size_before_block:  8 bytes LE]
     *   [id-value pairs    : variable  ]
     *     each pair: [length: 8 bytes LE][id: 4 bytes LE][value: length-4 bytes]
     *   [size_of_block:      8 bytes LE] (== size_before_block)
     *   [magic:             16 bytes   ] ("APK Sig Block 42")
     *
     * The block sits immediately before the ZIP Central Directory.
     * We locate Central Directory offset from the End-of-Central-Directory record.
     */
    private fun detectV2PlusSchemes(apkBytes: ByteArray, schemes: MutableSet<Int>) {
        if (apkBytes.size < 22) return  // minimum valid ZIP

        val buf = ByteBuffer.wrap(apkBytes).order(ByteOrder.LITTLE_ENDIAN)

        // 1. Find EOCD — search backwards for signature 0x06054b50
        val eocdSignature = 0x06054b50
        var eocdOffset = apkBytes.size - 22
        while (eocdOffset >= 0) {
            if (buf.getInt(eocdOffset) == eocdSignature) break
            eocdOffset--
        }
        if (eocdOffset < 0) {
            Log.w(TAG, "EOCD not found, cannot detect v2+ signing")
            return
        }

        // Offset of Central Directory from EOCD (at eocdOffset+16)
        val cdOffset = buf.getInt(eocdOffset + 16).toLong() and 0xFFFFFFFFL

        // 2. Check for APK Signing Block magic immediately before Central Directory
        // Magic is 16 bytes: "APK Sig Block 42"
        //   Bytes 0-7:  "APK Sig " = 0x20676953204b5041 (LE)
        //   Bytes 8-15: "Block 42" = 0x3234206b636f6c42 (LE)
        val magicStart = cdOffset - 16
        if (magicStart < 0) return

        val magicLo = buf.getLong(magicStart.toInt())
        val magicHi = buf.getLong((magicStart + 8).toInt())
        if (magicLo != APK_SIG_BLOCK_MAGIC_LO || magicHi != APK_SIG_BLOCK_MAGIC_HI) {
            Log.d(TAG, "No APK Signing Block magic found — v2/v3/v4 absent")
            return
        }

        // 3. Read block size (8 bytes immediately before the magic)
        val blockSizeOffset = magicStart - 8
        if (blockSizeOffset < 0) return
        val blockSize = buf.getLong(blockSizeOffset.toInt())
        if (blockSize < 8 || blockSize > cdOffset) return

        // 4. Block starts at: cdOffset - 16(magic) - 8(size field) - blockSize
        val blockStart = cdOffset - 16 - 8 - blockSize
        if (blockStart < 0) return

        // Confirm size field at the start of the block matches
        val blockSizeCheck = buf.getLong(blockStart.toInt())
        if (blockSizeCheck != blockSize) return

        // 5. Iterate id-value pairs inside the signing block
        // Pairs start at blockStart + 8 (skip leading size field)
        var pairOffset = blockStart.toInt() + 8
        val pairsEnd   = blockStart.toInt() + 8 + blockSize.toInt() - 8 // exclude trailing size
        while (pairOffset < pairsEnd - 12) { // need at least 8(len)+4(id)
            val pairLen = buf.getLong(pairOffset).toInt()
            if (pairLen < 4 || pairOffset + 8 + pairLen > apkBytes.size) break
            val blockId = buf.getInt(pairOffset + 8)
            Log.v(TAG, "APK Signing Block ID: 0x${Integer.toHexString(blockId)}")
            when (blockId) {
                SIG_BLOCK_ID_V2  -> { schemes.add(2); Log.d(TAG, "Signing: v2 detected") }
                SIG_BLOCK_ID_V3  -> { schemes.add(3); Log.d(TAG, "Signing: v3 detected") }
                SIG_BLOCK_ID_V31 -> { schemes.add(3); Log.d(TAG, "Signing: v3.1 detected") }
                SIG_BLOCK_ID_V4  -> { schemes.add(4); Log.d(TAG, "Signing: v4 detected") }
            }
            pairOffset += 8 + pairLen
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AXML (Binary AndroidManifest.xml) parser
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Holds the data we extract from the manifest while walking the AXML tree.
     */
    private data class ManifestData(
        var packageName: String = "",
        var versionName: String = "",
        var versionCode: Long = 0L,
        var minSdkVersion: Int = 1,
        var targetSdkVersion: Int = 1,
        var applicationClass: String? = null,
        var isDebuggable: Boolean = false,
        var isSplitApk: Boolean = false
    )

    /**
     * Parse binary AndroidManifest.xml (AXML / ResXml format).
     *
     * The format consists of a sequence of typed chunks:
     *   - String pool    (0x0001): all strings referenced by index
     *   - Resource table (0x0180): maps attribute indices → Android resource IDs
     *   - XML nodes      (0x0100–0x0104): namespace events, element events, CDATA
     *
     * We walk the chunks linearly and extract the fields we care about from
     * <manifest>, <uses-sdk>, and <application> elements.
     *
     * @param bytes Raw bytes of AndroidManifest.xml
     * @return Parsed [ManifestData], or null if the file header is invalid.
     */
    private fun parseAxml(bytes: ByteArray): ManifestData? {
        if (bytes.size < 8) return null

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // AXML file header: magic word 0x00080003 + file size
        val magic = buf.getInt(0)
        if (magic != 0x00080003) {
            Log.e(TAG, "Invalid AXML magic: 0x${Integer.toHexString(magic)}")
            return null
        }

        var strings: Array<String> = emptyArray()
        var resourceIds: IntArray = intArrayOf()
        val result = ManifestData()

        // Chunk walk — position 0 is the file header (type=0x0003, headerSize=8)
        // The header IS the first chunk, so we skip its 8-byte body and continue.
        var pos = 8  // skip file header chunk (magic + fileSize)

        while (pos + 8 <= bytes.size) {
            val chunkType   = buf.getShort(pos).toInt() and 0xFFFF
            val headerSize  = buf.getShort(pos + 2).toInt() and 0xFFFF
            val chunkSize   = buf.getInt(pos + 4)

            if (chunkSize <= 0 || pos + chunkSize > bytes.size) break

            when (chunkType) {
                CHUNK_STRING_POOL -> {
                    strings = parseStringPool(buf, pos, chunkSize)
                    Log.v(TAG, "String pool: ${strings.size} strings")
                }
                CHUNK_XML_RESOURCE -> {
                    resourceIds = parseResourceTable(buf, pos, chunkSize)
                    Log.v(TAG, "Resource IDs: ${resourceIds.size}")
                }
                CHUNK_XML_START_ELEM -> {
                    parseStartElement(buf, pos, strings, resourceIds, result)
                }
                CHUNK_XML_END_ELEM, CHUNK_XML_START_NS,
                CHUNK_XML_END_NS, CHUNK_XML_CDATA -> {
                    // not needed for metadata extraction
                }
                else -> {
                    Log.v(TAG, "Unknown AXML chunk type: 0x${Integer.toHexString(chunkType)} at $pos")
                }
            }

            pos += chunkSize
        }

        if (result.packageName.isEmpty()) {
            Log.e(TAG, "packageName not found in manifest")
            return null
        }
        return result
    }

    // ── String Pool ───────────────────────────────────────────────────────────

    /**
     * Parse the ResStringPool chunk and return all strings as a String array.
     *
     * ResStringPool_header layout (after the chunk header):
     *   stringCount:    4 bytes
     *   styleCount:     4 bytes
     *   flags:          4 bytes  (bit 8 = UTF-8 flag)
     *   stringsStart:   4 bytes  (offset from chunk start to first string data)
     *   stylesStart:    4 bytes
     *   offsets[stringCount]: 4 bytes each
     *
     * Each string is either:
     *   - UTF-16LE (flags & 0x100 == 0): 2-byte length, then UTF-16LE chars, then 0x0000
     *   - UTF-8    (flags & 0x100 != 0): 1 or 2 byte UTF-16 length, 1 or 2 byte UTF-8 length,
     *              then UTF-8 bytes, then 0x00
     */
    private fun parseStringPool(buf: ByteBuffer, chunkStart: Int, chunkSize: Int): Array<String> {
        // chunk header is 8 bytes (type, headerSize, chunkSize)
        val headerBase = chunkStart + 8

        val stringCount  = buf.getInt(headerBase)
        // styleCount   = buf.getInt(headerBase + 4)  // unused
        val flags        = buf.getInt(headerBase + 8)
        val stringsStart = buf.getInt(headerBase + 12)
        // stylesStart  = buf.getInt(headerBase + 16) // unused

        val isUtf8 = (flags and 0x100) != 0

        // String offsets array starts right after the ResStringPool_header (28 bytes)
        val offsetsBase   = headerBase + 20
        val stringDataBase = chunkStart + stringsStart

        return Array(stringCount) { i ->
            val offset = buf.getInt(offsetsBase + i * 4)
            val strPos = stringDataBase + offset
            try {
                if (isUtf8) readUtf8String(buf, strPos)
                else        readUtf16String(buf, strPos)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read string at offset $strPos: ${e.message}")
                ""
            }
        }
    }

    /** Read a length-prefixed UTF-8 string from the string pool data area. */
    private fun readUtf8String(buf: ByteBuffer, pos: Int): String {
        var p = pos
        // Skip UTF-16 character count (may be 1 or 2 bytes)
        val utf16Len = buf.get(p).toInt() and 0xFF
        p += if ((utf16Len and 0x80) != 0) 2 else 1

        // Read UTF-8 byte count (may be 1 or 2 bytes)
        val utf8Byte = buf.get(p).toInt() and 0xFF
        val utf8Len: Int
        if ((utf8Byte and 0x80) != 0) {
            utf8Len = ((utf8Byte and 0x7F) shl 8) or (buf.get(p + 1).toInt() and 0xFF)
            p += 2
        } else {
            utf8Len = utf8Byte
            p += 1
        }
        if (utf8Len == 0) return ""
        val data = ByteArray(utf8Len)
        buf.position(p)
        buf.get(data)
        return String(data, Charsets.UTF_8)
    }

    /** Read a length-prefixed UTF-16LE string from the string pool data area. */
    private fun readUtf16String(buf: ByteBuffer, pos: Int): String {
        var p = pos
        val firstLen = buf.getShort(p).toInt() and 0xFFFF
        val charLen: Int
        if ((firstLen and 0x8000) != 0) {
            charLen = ((firstLen and 0x7FFF) shl 16) or (buf.getShort(p + 2).toInt() and 0xFFFF)
            p += 4
        } else {
            charLen = firstLen
            p += 2
        }
        if (charLen == 0) return ""
        val data = ByteArray(charLen * 2)
        buf.position(p)
        buf.get(data)
        return String(data, Charsets.UTF_16LE)
    }

    // ── Resource ID Table ─────────────────────────────────────────────────────

    /**
     * Parse the ResXMLTree_node chunk of type 0x0180 (XML Resource Map).
     * Returns an IntArray where index i holds the Android resource ID for
     * the i-th string in the string pool.
     */
    private fun parseResourceTable(buf: ByteBuffer, chunkStart: Int, chunkSize: Int): IntArray {
        val dataStart = chunkStart + 8  // skip 8-byte chunk header
        val count     = (chunkSize - 8) / 4
        return IntArray(count) { i -> buf.getInt(dataStart + i * 4) }
    }

    // ── Start Element ─────────────────────────────────────────────────────────

    /**
     * Parse a START_ELEMENT chunk and update [result] with any recognised fields.
     *
     * ResXMLTree_attrExt layout (after the 16-byte node header):
     *   ns:          4 bytes (string index)
     *   name:        4 bytes (string index)
     *   attrStart:   2 bytes
     *   attrSize:    2 bytes (should be 20)
     *   attrCount:   2 bytes
     *   idIndex:     2 bytes
     *   classIndex:  2 bytes
     *   styleIndex:  2 bytes
     *
     * Each attribute (ResXMLTree_attribute, 20 bytes):
     *   ns:          4 bytes (string index)
     *   name:        4 bytes (string index)
     *   rawValue:    4 bytes (string index, or 0xFFFFFFFF)
     *   valueSize:   2 bytes
     *   res0:        1 byte
     *   dataType:    1 byte
     *   data:        4 bytes
     */
    private fun parseStartElement(
        buf: ByteBuffer,
        chunkStart: Int,
        strings: Array<String>,
        resourceIds: IntArray,
        result: ManifestData
    ) {
        // Node header: type(2), headerSize(2), chunkSize(4), lineNumber(4), comment(4) = 16 bytes
        val nodeHeaderSize = 16
        val extStart = chunkStart + nodeHeaderSize

        if (extStart + 16 > buf.limit()) return

        buf.getInt(extStart)  // nsIdx: namespace string index — not used for element name lookup
        val nameIdx   = buf.getInt(extStart + 4)
        val attrCount = buf.getShort(extStart + 12).toInt() and 0xFFFF

        val elementName = strings.getOrElse(nameIdx) { "" }

        // Attributes start after the attrExt header (16 bytes)
        val attrBase = extStart + 16
        val attrSize = 20  // each ResXMLTree_attribute is exactly 20 bytes

        for (i in 0 until attrCount) {
            val attrOffset = attrBase + i * attrSize
            if (attrOffset + attrSize > buf.limit()) break

            // val attrNsIdx   = buf.getInt(attrOffset)      // not used
            val attrNameIdx    = buf.getInt(attrOffset + 4)
            val attrRawValIdx  = buf.getInt(attrOffset + 8)
            // val attrValueSize = buf.getShort(attrOffset + 12) // always 8
            val attrDataType   = buf.get(attrOffset + 15).toInt() and 0xFF
            val attrData       = buf.getInt(attrOffset + 16)

            // Determine the Android resource ID for this attribute (if mapped)
            val attrResId = if (attrNameIdx >= 0 && attrNameIdx < resourceIds.size)
                resourceIds[attrNameIdx] else 0

            val attrNameStr = strings.getOrElse(attrNameIdx) { "" }

            // Helper to get string value
            fun strValue(): String = when {
                attrDataType == TYPE_STRING && attrRawValIdx >= 0 ->
                    strings.getOrElse(attrRawValIdx) { "" }
                attrDataType == TYPE_STRING ->
                    strings.getOrElse(attrData) { "" }
                else -> strings.getOrElse(attrRawValIdx) { "" }
            }

            when (elementName) {
                "manifest" -> {
                    when {
                        attrNameStr == "package" -> result.packageName = strValue()
                        attrResId == ATTR_VERSION_CODE -> result.versionCode = attrData.toLong() and 0xFFFFFFFFL
                        attrResId == ATTR_VERSION_NAME -> result.versionName = strValue()
                        // Split APK: android:split attribute on <manifest>
                        attrResId == ATTR_SPLIT -> {
                            val v = strValue()
                            if (v.isNotEmpty()) result.isSplitApk = true
                        }
                        // Split APK: android:configForSplit attribute on <manifest>
                        attrResId == ATTR_CONFIG_FOR_SPLIT -> {
                            val v = strValue()
                            if (v.isNotEmpty()) result.isSplitApk = true
                        }
                    }
                }
                "uses-sdk" -> {
                    when (attrResId) {
                        ATTR_MIN_SDK    -> result.minSdkVersion    = attrData
                        ATTR_TARGET_SDK -> result.targetSdkVersion = attrData
                    }
                }
                "application" -> {
                    when (attrResId) {
                        ATTR_NAME -> {
                            val cls = strValue()
                            if (cls.isNotEmpty()) result.applicationClass = cls
                        }
                        ATTR_DEBUGGABLE -> {
                            result.isDebuggable = (attrData != 0)
                        }
                    }
                }
            }
        }
    }
}
