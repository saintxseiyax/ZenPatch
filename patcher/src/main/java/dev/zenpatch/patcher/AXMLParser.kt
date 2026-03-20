package dev.zenpatch.patcher

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal Android Binary XML (AXML) parser.
 * Only decodes elements and attributes, not full XML reconstruction.
 * Used internally by ApkAnalyzer and ManifestEditor.
 */
class AXMLParser(private val data: ByteArray) {

    enum class EventType { START_DOCUMENT, START_ELEMENT, END_ELEMENT, END_DOCUMENT }

    data class Event(
        val type: EventType,
        val name: String,
        val attrs: Map<String, String>
    )

    private val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

    // String pool
    private val strings = mutableListOf<String>()

    fun parse(visitor: (Event) -> Unit) {
        buf.position(0)
        if (buf.remaining() < 8) return

        val magic = buf.int
        if (magic != 0x00080003) {
            // Try lenient parsing
        }
        val fileSize = buf.int

        while (buf.hasRemaining() && buf.remaining() >= 8) {
            val chunkType = buf.int
            val chunkSize = buf.int
            if (chunkSize <= 0 || chunkSize > buf.capacity()) break

            val chunkStart = buf.position() - 8
            when (chunkType) {
                CHUNK_STRING_POOL -> parseStringPool(chunkStart, chunkSize)
                CHUNK_START_ELEMENT -> {
                    val event = parseStartElement()
                    if (event != null) visitor(event)
                }
                CHUNK_END_ELEMENT -> {
                    val event = parseEndElement()
                    if (event != null) visitor(event)
                }
                else -> {
                    // Skip unknown chunk
                    val skip = chunkStart + chunkSize - buf.position()
                    if (skip > 0 && buf.remaining() >= skip) buf.position(buf.position() + skip)
                    else break
                }
            }
        }
        visitor(Event(EventType.END_DOCUMENT, "", emptyMap()))
    }

    private fun parseStringPool(chunkStart: Int, chunkSize: Int) {
        if (buf.remaining() < 20) return
        val stringCount = buf.int
        val styleCount = buf.int
        val flags = buf.int
        val stringsStart = buf.int
        val stylesStart = buf.int

        val isUtf8 = (flags and FLAG_UTF8) != 0
        val offsets = IntArray(stringCount) {
            if (buf.remaining() >= 4) buf.int else 0
        }

        val poolOffset = chunkStart + stringsStart
        strings.clear()
        for (offset in offsets) {
            val pos = poolOffset + offset
            if (pos >= data.size) {
                strings.add("")
                continue
            }
            val str = if (isUtf8) {
                readUtf8String(pos)
            } else {
                readUtf16String(pos)
            }
            strings.add(str)
        }

        // Seek to end of chunk
        val endPos = chunkStart + chunkSize
        if (endPos <= buf.capacity()) buf.position(endPos)
    }

    private fun readUtf8String(offset: Int): String {
        if (offset >= data.size) return ""
        val b = ByteBuffer.wrap(data, offset, data.size - offset).order(ByteOrder.LITTLE_ENDIAN)
        // UTF-8 encoded string: first byte(s) = char count (utf16), then byte count, then chars
        var charLen = b.get().toInt() and 0xFF
        if (charLen and 0x80 != 0) {
            charLen = ((charLen and 0x7F) shl 8) or (b.get().toInt() and 0xFF)
        }
        var byteLen = b.get().toInt() and 0xFF
        if (byteLen and 0x80 != 0) {
            byteLen = ((byteLen and 0x7F) shl 8) or (b.get().toInt() and 0xFF)
        }
        if (byteLen <= 0 || b.remaining() < byteLen) return ""
        val bytes = ByteArray(byteLen)
        b.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readUtf16String(offset: Int): String {
        if (offset + 2 > data.size) return ""
        val b = ByteBuffer.wrap(data, offset, data.size - offset).order(ByteOrder.LITTLE_ENDIAN)
        var len = b.short.toInt() and 0xFFFF
        if (len and 0x8000 != 0) {
            len = ((len and 0x7FFF) shl 16) or (b.short.toInt() and 0xFFFF)
        }
        if (len <= 0 || b.remaining() < len * 2) return ""
        val chars = CharArray(len) { b.short.toInt().toChar() }
        return String(chars)
    }

    private fun parseStartElement(): Event? {
        if (buf.remaining() < 20) return null
        val lineNumber = buf.int
        val comment = buf.int
        val ns = buf.int
        val nameIdx = buf.int
        val attrStart = buf.short.toInt() and 0xFFFF
        val attrSize = buf.short.toInt() and 0xFFFF
        val attrCount = buf.short.toInt() and 0xFFFF
        val idAttr = buf.short.toInt() and 0xFFFF
        val classAttr = buf.short.toInt() and 0xFFFF
        val styleAttr = buf.short.toInt() and 0xFFFF

        val name = strings.getOrElse(nameIdx) { "" }
        val attrs = mutableMapOf<String, String>()

        repeat(attrCount) {
            if (buf.remaining() < 20) return@repeat
            val attrNsIdx = buf.int
            val attrNameIdx = buf.int
            val attrRawValIdx = buf.int
            val attrValueSize = buf.short.toInt() and 0xFFFF
            val attrValueRes0 = buf.get().toInt() and 0xFF
            val attrDataType = buf.get().toInt() and 0xFF
            val attrData = buf.int

            val attrName = strings.getOrElse(attrNameIdx) { "" }
            val attrNs = strings.getOrElse(attrNsIdx) { "" }
            val qualifiedName = if (attrNs.isNotEmpty()) {
                val nsShort = when {
                    attrNs.contains("android") -> "android"
                    else -> attrNs
                }
                "$nsShort:$attrName"
            } else attrName

            val value = when (attrDataType) {
                TYPE_STRING -> strings.getOrElse(attrData) { "" }
                TYPE_INT_DEC -> attrData.toString()
                TYPE_INT_HEX -> "0x${attrData.toString(16)}"
                TYPE_BOOLEAN -> if (attrData != 0) "true" else "false"
                else -> if (attrRawValIdx >= 0) strings.getOrElse(attrRawValIdx) { attrData.toString() }
                        else attrData.toString()
            }
            if (attrName.isNotEmpty()) {
                attrs[qualifiedName] = value
                attrs[attrName] = value  // also store without namespace prefix
            }
        }

        return Event(EventType.START_ELEMENT, name, attrs)
    }

    private fun parseEndElement(): Event? {
        if (buf.remaining() < 16) return null
        buf.int // lineNumber
        buf.int // comment
        buf.int // ns
        val nameIdx = buf.int
        val name = strings.getOrElse(nameIdx) { "" }
        return Event(EventType.END_ELEMENT, name, emptyMap())
    }

    companion object {
        private const val CHUNK_STRING_POOL = 0x001C0001
        private const val CHUNK_START_ELEMENT = 0x00100102
        private const val CHUNK_END_ELEMENT = 0x00100103
        private const val CHUNK_RESOURCE_MAP = 0x00080180
        private const val CHUNK_XML_TREE_HEADER = 0x00080003

        private const val FLAG_UTF8 = 0x100

        private const val TYPE_STRING = 0x03
        private const val TYPE_INT_DEC = 0x10
        private const val TYPE_INT_HEX = 0x11
        private const val TYPE_BOOLEAN = 0x12
    }
}
