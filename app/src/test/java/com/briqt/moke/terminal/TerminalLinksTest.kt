package com.briqt.moke.terminal

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalLinksTest {

    // ---------- 文本层 ----------

    @Test
    fun `finds the link covering the index and nothing outside it`() {
        val text = "see https://example.com/docs for details"
        assertEquals("https://example.com/docs", TerminalLinks.urlAt(text, text.indexOf("example")))
        assertEquals("https://example.com/docs", TerminalLinks.urlAt(text, text.indexOf("https")))
        assertNull(TerminalLinks.urlAt(text, 0))
        assertNull(TerminalLinks.urlAt(text, text.indexOf("for")))
    }

    @Test
    fun `trailing punctuation is not part of the link`() {
        assertEquals("https://a.com/x", TerminalLinks.urlAt("go to https://a.com/x.", 8))
        assertEquals("https://a.com/x", TerminalLinks.urlAt("(https://a.com/x)", 3))
        // 链接内部配对的括号要保留。
        assertEquals("https://en.wikipedia.org/wiki/Foo_(bar)", TerminalLinks.urlAt("https://en.wikipedia.org/wiki/Foo_(bar)", 3))
        // 紧跟中文标点（全角句号、逗号）。
        assertEquals("http://localhost:5173/", TerminalLinks.urlAt("打开 http://localhost:5173/。然后", 5))
        assertEquals("https://a.com", TerminalLinks.urlAt("https://a.com，下一个", 2))
    }

    @Test
    fun `only http and https`() {
        assertNull(TerminalLinks.urlAt("ftp://a.com/file", 3))
        assertNull(TerminalLinks.urlAt("file:///etc/hosts", 3))
    }

    @Test
    fun `local addresses`() {
        assertTrue(TerminalLinks.isLocal("http://localhost:5173/"))
        assertTrue(TerminalLinks.isLocal("http://127.0.0.1:8080/api"))
        assertTrue(TerminalLinks.isLocal("http://0.0.0.0:3000"))
        assertTrue(TerminalLinks.isLocal("http://[::1]:8000/"))
        assertTrue(TerminalLinks.isLocal("HTTP://LOCALHOST"))
        assertFalse(TerminalLinks.isLocal("https://example.com/localhost"))
        assertFalse(TerminalLinks.isLocal("https://localhost.example.com/"))
    }

    // ---------- 终端层：真的 TerminalEmulator ----------

    private fun emulator(cols: Int, rows: Int, text: String): TerminalEmulator {
        val out = object : TerminalOutput() {
            override fun write(data: ByteArray, offset: Int, count: Int) {}
            override fun titleChanged(oldTitle: String?, newTitle: String?) {}
            override fun onCopyTextToClipboard(text: String?) {}
            override fun onPasteTextFromClipboard() {}
            override fun onBell() {}
            override fun onColorsChanged() {}
        }
        return TerminalEmulator(out, cols, rows, 10, 20, rows * 2, null).also {
            val b = text.toByteArray(Charsets.UTF_8)
            it.append(b, b.size)
        }
    }

    @Test
    fun `soft-wrapped link is joined back`() {
        // 20 列：链接从第 0 行第 4 列开始，折到第 1、2 行。
        val url = "https://example.com/a/very/long/path"
        val emu = emulator(20, 5, "go: $url\r\nnext")
        assertEquals(url, TerminalLinks.urlAt(emu, 6, 0))
        assertEquals(url, TerminalLinks.urlAt(emu, 3, 1))   // 点在折行后的那一段
        assertNull(TerminalLinks.urlAt(emu, 1, 3))          // 下一行的 "next"
    }

    @Test
    fun `wrapped url at several widths stays whole and has no newline`() {
        val url = "https://github.com/briqt/moke/releases/tag/v0.1.21-rc.5"
        for (cols in listOf(20, 30, 40, 48, 55, 56, 80)) {
            val emu = emulator(cols, 12, "see $url done\r\n")
            val hit = TerminalLinks.urlAt(emu, 4, 0)
            assertEquals("cols=$cols", url, hit)
            assertFalse("cols=$cols", hit!!.contains('\n'))
            // 点折行后的第一列，也要拿到整段。
            if (url.length + 4 > cols) {
                assertEquals("cols=$cols second row", url, TerminalLinks.urlAt(emu, 0, 1))
            }
        }
    }

    @Test
    fun `resizing a wide line into a wrap still returns the whole url`() {
        val url = "https://github.com/briqt/moke/releases/tag/v0.1.21-rc.5"
        val emu = emulator(80, 8, "see $url done\r\n")
        assertEquals(url, TerminalLinks.urlAt(emu, 4, 0))
        emu.resize(36, 10, 10, 20)
        assertEquals(url, TerminalLinks.urlAt(emu, 4, 0))
        assertEquals(url, TerminalLinks.urlAt(emu, 0, 1))
    }

    @Test
    fun `a full line followed by a real newline is not joined to the next line`() {
        val url = "https://github.com/briqt/moke/releases/tag/v0.1.21-rc.5"
        val cols = url.length
        val emu = emulator(cols, 6, url + "\r\n" + "dev@1fce01469ebe:~$ next\r\n")
        assertFalse(emu.screen.getLineWrap(0))
        assertEquals(url, TerminalLinks.urlAt(emu, 0, 0))
        assertNull(TerminalLinks.urlAt(emu, 0, 1))
    }

    @Test
    fun `prompt continuing on the same logical line is swallowed up to the first space`() {
        val url = "https://github.com/briqt/moke/releases/tag/v0.1.21-rc.5"
        val prompt = "dev@1fce01469ebe:~$"
        val emu = emulator(60, 8, url + prompt + " \r\n")
        val hit = TerminalLinks.urlAt(emu, 0, 0)
        // 没有分隔符时，提示符会被当成链接的一部分拼回来；折行本身不插入换行，也不重复折行处的字符。
        assertEquals(url + prompt, hit)
        assertFalse(hit!!.contains('\n'))
        assertFalse(hit.contains("dev@1dev@1"))
    }

    @Test
    fun `a long signed link spanning many rows stays whole from either end`() {
        val url = "https://example.com/download?sig=" + "a".repeat(1400)
        for (cols in listOf(30, 60, 80)) {
            val rows = url.length / cols + 3
            val emu = emulator(cols, rows, url + "\r\n")
            val lastRow = (url.length - 1) / cols
            assertEquals("cols=$cols first row", url, TerminalLinks.urlAt(emu, 0, 0))
            assertEquals("cols=$cols last row", url, TerminalLinks.urlAt(emu, 0, lastRow))
        }
    }

    @Test
    fun `link bar text keeps head and tail of a very long link`() {
        val short = "https://github.com/briqt/moke/releases/tag/v0.1.21"
        assertEquals(short, TerminalLinks.displayOf(short))
        val long = "https://example.com/download?sig=" + "a".repeat(400) + "&end=TAIL"
        val shown = TerminalLinks.displayOf(long)
        assertTrue(shown.startsWith("https://example.com/download?sig="))
        assertTrue(shown.endsWith("&end=TAIL"))
        assertTrue(shown.contains('…'))
        assertTrue(shown.length < long.length)
    }

    @Test
    fun `a real newline inside a url truncates at that newline`() {
        val emu = emulator(80, 6, "https://github.com/briqt/moke/\r\nreleases/tag/v0.1.21-rc.5\r\n")
        assertEquals("https://github.com/briqt/moke/", TerminalLinks.urlAt(emu, 4, 0))
        assertNull(TerminalLinks.urlAt(emu, 0, 1))
    }

    @Test
    fun `wide CJK characters before the link do not shift the hit position`() {
        // "中文" 占 4 列：链接从第 5 列开始。按"一列一字符"算的实现会在这里错位。
        val emu = emulator(40, 3, "中文 http://localhost:5173/ ok")
        assertEquals("http://localhost:5173/", TerminalLinks.urlAt(emu, 5, 0))
        assertEquals("http://localhost:5173/", TerminalLinks.urlAt(emu, 26, 0))
        assertNull(TerminalLinks.urlAt(emu, 4, 0))   // 链接前的空格
        assertNull(TerminalLinks.urlAt(emu, 29, 0))  // 链接后的 "ok"
    }

    @Test
    fun `tapping blank space is not a hit`() {
        val emu = emulator(40, 3, "https://a.com")
        assertNull(TerminalLinks.urlAt(emu, 30, 0))
        assertNull(TerminalLinks.urlAt(emu, 0, 2))
    }
}
