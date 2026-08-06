package com.briqt.moke.terminal.sftp

import org.junit.Assert.assertEquals
import org.junit.Test

class RemotePathTest {

    @Test
    fun `规范化折叠重复分隔符与点段`() {
        assertEquals("/var/log", RemotePath.normalize("/var//./log/"))
        assertEquals("/var", RemotePath.normalize("/var/log/.."))
        assertEquals("/", RemotePath.normalize("/"))
        assertEquals("/", RemotePath.normalize("/a/.."))
    }

    @Test
    fun `绝对路径上的 dotdot 退不过根`() {
        assertEquals("/", RemotePath.normalize("/../.."))
        assertEquals("/x", RemotePath.normalize("/../x"))
    }

    @Test
    fun `相对路径开头的 dotdot 必须保留`() {
        // 丢掉它会把 `../x` 悄悄变成 `x`，跳到完全不同的目录。
        assertEquals("../x", RemotePath.normalize("../x"))
        assertEquals("../..", RemotePath.normalize("../.."))
    }

    @Test
    fun `join 处理结尾斜杠与绝对路径覆盖`() {
        assertEquals("/var/log", RemotePath.join("/var", "log"))
        assertEquals("/var/log", RemotePath.join("/var/", "log"))
        assertEquals("/etc", RemotePath.join("/var", "/etc"))
        assertEquals("/a/b c", RemotePath.join("/a", "b c"))
    }

    @Test
    fun `parent 与 name`() {
        assertEquals("/var", RemotePath.parent("/var/log"))
        assertEquals("/", RemotePath.parent("/var"))
        assertEquals("/", RemotePath.parent("/"))
        assertEquals("log", RemotePath.name("/var/log"))
        assertEquals("/", RemotePath.name("/"))
    }

    @Test
    fun `面包屑逐级给出可点路径`() {
        assertEquals(
            listOf("/" to "/", "var" to "/var", "log" to "/var/log"),
            RemotePath.crumbs("/var/log"),
        )
        assertEquals(listOf("/" to "/"), RemotePath.crumbs("/"))
    }

    @Test
    fun `shell 转义能扛住空格 引号 与 $`() {
        assertEquals("'/tmp/a b'", RemotePath.shellQuote("/tmp/a b"))
        assertEquals("'/tmp/it'\\''s'", RemotePath.shellQuote("/tmp/it's"))
        assertEquals("'\$HOME/x'", RemotePath.shellQuote("\$HOME/x"))
        assertEquals("''", RemotePath.shellQuote(""))
    }

    @Test
    fun `大小按 10 进制单位显示`() {
        assertEquals("999 B", RemotePath.formatSize(999))
        assertEquals("1.0 KB", RemotePath.formatSize(1000))
        assertEquals("1.5 MB", RemotePath.formatSize(1_500_000))
        assertEquals("—", RemotePath.formatSize(-1))
    }

    @Test
    fun `中文与换行文件名也能安全拼接与转义`() {
        assertEquals("/data/报告 (1).pdf", RemotePath.join("/data", "报告 (1).pdf"))
        assertEquals("'/data/a\nb'", RemotePath.shellQuote("/data/a\nb"))
    }
}
