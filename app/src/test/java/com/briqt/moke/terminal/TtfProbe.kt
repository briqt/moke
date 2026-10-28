package com.briqt.moke.terminal

import java.io.File

private fun ByteArray.u8(p: Int): Int = this[p].toInt() and 0xFF
private fun ByteArray.u16(p: Int): Int = (u8(p) shl 8) or u8(p + 1)
private fun ByteArray.i16(p: Int): Int = u16(p).toShort().toInt()
private fun ByteArray.u32(p: Int): Int = (u16(p) shl 16) or u16(p + 2)

/**
 * 极简 TTF/OTF 度量探针（仅测试用）：只解析 head / hhea / hmtx / cmap（format 4 与 12），
 * 回答一个问题——「这个码位在这个字体里的 advance 是多少 em」。
 *
 * 存在的理由：终端格子由主字体的 advance 决定。任何回退字体只要 advance 与之不同，
 * 字形就只能在渲染时被缩放——图标会变瘦、CJK 会被拉宽。这件事必须在构建期守住，
 * 而不是等用户在屏幕上发现。见 [BundledFontMetricsTest]。
 */
internal class TtfProbe private constructor(
    private val bytes: ByteArray,
    private val tables: Map<String, Pair<Int, Int>>,
) {

    private val unitsPerEm: Int
    private val numberOfHMetrics: Int
    private val hmtxOffset: Int
    private val cmapSubtable: Int
    private val cmapFormat: Int

    init {
        unitsPerEm = bytes.u16(table("head") + 18)
        numberOfHMetrics = bytes.u16(table("hhea") + 34)
        hmtxOffset = table("hmtx")
        val cmap = table("cmap")
        val records = bytes.u16(cmap + 2)
        var chosen = -1
        var chosenFormat = 0
        for (i in 0 until records) {
            val record = cmap + 4 + 8 * i
            val subtable = cmap + bytes.u32(record + 4)
            when (bytes.u16(subtable)) {
                12 -> if (chosenFormat != 12) { chosen = subtable; chosenFormat = 12 }
                4 -> if (chosenFormat == 0) { chosen = subtable; chosenFormat = 4 }
            }
        }
        require(chosen >= 0) { "no usable cmap subtable" }
        cmapSubtable = chosen
        cmapFormat = chosenFormat
    }

    val emSize: Int get() = unitsPerEm

    /** 某个码位的 advance，单位 em；字体没有这个码位时返回 null。 */
    fun advanceEm(codePoint: Int): Float? {
        val glyph = glyphId(codePoint) ?: return null
        val index = minOf(glyph, numberOfHMetrics - 1)
        return bytes.u16(hmtxOffset + 4 * index) / unitsPerEm.toFloat()
    }

    private fun table(tag: String): Int = tables[tag]?.first ?: error("missing table $tag")

    private fun glyphId(codePoint: Int): Int? =
        if (cmapFormat == 12) glyphIdFormat12(codePoint) else glyphIdFormat4(codePoint)

    private fun glyphIdFormat12(codePoint: Int): Int? {
        val groups = bytes.u32(cmapSubtable + 12)
        var low = 0
        var high = groups - 1
        while (low <= high) {
            val mid = (low + high) / 2
            val group = cmapSubtable + 16 + 12 * mid
            val start = bytes.u32(group)
            val end = bytes.u32(group + 4)
            when {
                codePoint < start -> high = mid - 1
                codePoint > end -> low = mid + 1
                else -> return bytes.u32(group + 8) + (codePoint - start)
            }
        }
        return null
    }

    private fun glyphIdFormat4(codePoint: Int): Int? {
        if (codePoint > 0xFFFF) return null
        val segCount = bytes.u16(cmapSubtable + 6) / 2
        val endCodes = cmapSubtable + 14
        val startCodes = endCodes + segCount * 2 + 2
        val idDeltas = startCodes + segCount * 2
        val idRangeOffsets = idDeltas + segCount * 2
        var low = 0
        var high = segCount - 1
        while (low <= high) {
            val mid = (low + high) / 2
            if (codePoint > bytes.u16(endCodes + mid * 2)) low = mid + 1 else high = mid - 1
        }
        if (low >= segCount) return null
        val start = bytes.u16(startCodes + low * 2)
        if (codePoint < start) return null
        val delta = bytes.i16(idDeltas + low * 2)
        val rangeOffsetAddress = idRangeOffsets + low * 2
        val rangeOffset = bytes.u16(rangeOffsetAddress)
        if (rangeOffset == 0) return (codePoint + delta) and 0xFFFF
        val glyphAddress = rangeOffsetAddress + rangeOffset + (codePoint - start) * 2
        if (glyphAddress + 1 >= bytes.size) return null
        val glyph = bytes.u16(glyphAddress)
        return if (glyph == 0) null else (glyph + delta) and 0xFFFF
    }

    companion object {
        fun open(file: File): TtfProbe {
            require(file.isFile) { "font not found: ${file.absolutePath}" }
            val bytes = file.readBytes()
            val tableCount = bytes.u16(4)
            val tables = HashMap<String, Pair<Int, Int>>(tableCount)
            for (i in 0 until tableCount) {
                val record = 12 + 16 * i
                val tag = String(bytes, record, 4, Charsets.US_ASCII)
                tables[tag] = bytes.u32(record + 8) to bytes.u32(record + 12)
            }
            return TtfProbe(bytes, tables)
        }
    }
}
