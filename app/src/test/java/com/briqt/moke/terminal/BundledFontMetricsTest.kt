package com.briqt.moke.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 内置字体必须与终端格子「对得上」——但对得上的含义分两种，这组断言就是分界线。
 *
 * 终端格子宽 = 主字体的 advance（0.6em 是绝大多数等宽字体的取值）。回退字体只负责补字形，
 * 但它补的是**哪一类**字形，决定了它该用什么度量：
 *
 * - **pictograph**（devicons、Font Awesome、git 图标）按 1em 方形设计。终端格子是 0.6 × 1.32em，
 *   把 1em 的图标压进 0.6em 会让它只剩 60% 大小——这正是"yazi 里图标偏小"的成因。所以这类字形
 *   由 `nerd_symbols_design.ttf`（advance = 1.0em）提供，渲染器按设计尺寸画、让它对称溢出。
 * - **线画**（powerline、box drawing、块元素）必须与格子严丝合缝，否则接缝会断开。这类字形由
 *   `nerd_symbols_mono.ttf`（advance = 0.6em = 主字体）提供。
 *
 * 两段回退能各司其职的前提是**覆盖范围互补**：设计尺寸字体没有 box drawing / 块元素，
 * 所以这些字形会落到格子度量字体；反过来图标在格子度量字体里也有，但链序在前面的是设计尺寸字体。
 */
class BundledFontMetricsTest {

    private val fontDir = fontDirectory()
    private val primary by lazy { TtfProbe.open(File(fontDir, "jetbrains_mono.ttf")) }
    private val designIcons by lazy { TtfProbe.open(File(fontDir, "nerd_symbols_design.ttf")) }
    private val monoIcons by lazy { TtfProbe.open(File(fontDir, "nerd_symbols_mono.ttf")) }

    private val cell by lazy { requireNotNull(primary.advanceEm('X'.code)) { "primary font lacks 'X'" } }

    /** pictograph 回退按 1em 设计尺寸提供图标，且本身等宽（同一行里字形不会忽大忽小）。 */
    @Test
    fun nerdDesignFontKeepsTheDesignSize() {
        assertEquals(0.6f, cell, 0.0001f)
        val advances = ICONS.map { requireNotNull(designIcons.advanceEm(it)) { "U+%04X missing".format(it) } }
        advances.forEachIndexed { index, advance ->
            assertEquals("U+%04X".format(ICONS[index]), 1.0f, advance, 0.0001f)
        }
    }

    /** 线画回退与格子同度量：powerline / box drawing / 块元素正好填满一个格子。 */
    @Test
    fun nerdMonoFontMatchesTheCell() {
        for (codePoint in LINE_ART) {
            val advance = monoIcons.advanceEm(codePoint)
            assertNotNull("line-art fallback misses U+%04X".format(codePoint), advance)
            assertEquals("U+%04X".format(codePoint), cell, advance!!, 0.0001f)
        }
    }

    /** 两段回退的覆盖必须互补，否则链序会把线画也交给设计尺寸字体（接缝就会断开）。 */
    @Test
    fun theTwoNerdFontsSplitTheWorkByCoverage() {
        for (codePoint in LINE_ART) {
            assertNull(
                "design-size font must not claim U+%04X, or line art loses its cell metrics".format(codePoint),
                designIcons.advanceEm(codePoint),
            )
            assertNotNull("mono font must claim U+%04X".format(codePoint), monoIcons.advanceEm(codePoint))
        }
        for (codePoint in ICONS) {
            assertNotNull("design-size font must claim U+%04X".format(codePoint), designIcons.advanceEm(codePoint))
        }
    }

    /**
     * CJK 回退（内置思源黑体子集）用单一 advance 覆盖所有汉字。
     *
     * 已知差距：它是 **1.0em**，而 2 格宽字符的跨度是 2 × 0.6em = 1.2em。渲染器会把这个 2 列字形
     * 等比放大到填满跨度（`GridMetrics.placementScale`），因此不会出现字距被拉开的观感；但严格来说
     * 只有「1 字 1.2em」的 CJK 字体（Maple NF CN、Sarasa Mono 之类）才是格子精确匹配的。
     * Release 构建用 Maple NF CN 作为默认回退，正是这个原因。
     */
    @Test
    fun cjkFallbackUsesOneUniformAdvanceForHanzi() {
        val cjk = TtfProbe.open(File(fontDir, "noto_sans_sc.otf"))
        val advances = listOf(0x4E2D, 0x6587, 0x4E00, 0x9F99, 0x3000, 0xFF0C).map {
            requireNotNull(cjk.advanceEm(it)) { "CJK fallback misses U+%04X".format(it) }
        }
        advances.forEach { assertEquals(advances.first(), it, 0.0001f) }
        assertTrue("a wide glyph must not be narrower than a cell", advances.first() >= cell)
    }

    /** 所有内置字体都要能被解析出合理度量（换字体时先在这里发现坏文件）。 */
    @Test
    fun bundledFontsParseAndReportSaneMetrics() {
        val files = listOf(
            "jetbrains_mono.ttf",
            "nerd_symbols_design.ttf",
            "nerd_symbols_mono.ttf",
            "noto_sans_sc.otf",
            "noto_sans_symbols2.ttf",
        )
        for (name in files) {
            val probe = TtfProbe.open(File(fontDir, name))
            assertTrue("$name: unitsPerEm=${probe.emSize}", probe.emSize in 16..16384)
            // 纯符号字体既没有拉丁字母、也不一定有 powerline（设计尺寸字体已摘除），
            // 所以用「拉丁 X / 一个 pictograph / 一个线画」三选一来判断 cmap 可用。
            val hasGlyphs = listOf('X'.code, 0xF015, 0x2500).any { probe.advanceEm(it) != null }
            assertTrue("$name exposes no known glyph", hasGlyphs)
        }
    }

    private companion object {
        /** pictograph：devicon、Font Awesome 文件/主页（方形，按 1em 设计）。 */
        val ICONS = intArrayOf(0xE702, 0xF00C, 0xF015)

        /**
         * 线画：Powerline Symbols 的 git 分支与分隔符、box drawing 横竖线、块元素。
         *
         * U+E0A0–U+E0D7（Powerline Symbols + Powerline Extra）在设计尺寸字体里被从 cmap 摘掉了：
         * 它们按 1em 设计、只有 1.011em 高，落在 1.32em 的行里会露出行缝；交给格子度量字体
         * （1.334em 高，正好填满行盒）才严丝合缝。这是本次唯一一处字体修改。
         */
        val LINE_ART = intArrayOf(0xE0A0, 0xE0B0, 0xE0B2, 0x2500, 0x2502, 0x2588)

        /** 单元测试的工作目录通常是模块目录，但也可能是仓库根；向上找一次即可。 */
        fun fontDirectory(): File {
            var dir: File? = File("").absoluteFile
            while (dir != null) {
                File(dir, "src/main/res/font").takeIf { it.isDirectory }?.let { return it }
                File(dir, "app/src/main/res/font").takeIf { it.isDirectory }?.let { return it }
                dir = dir.parentFile
            }
            error("res/font not found from ${File("").absolutePath}")
        }
    }
}
