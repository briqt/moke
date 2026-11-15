package com.briqt.moke.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class BackupNameTest {
    private val t = LocalDateTime.of(2026, 11, 15, 14, 30, 5)

    @Test
    fun `字段顺序为应用、时间、设备、平台、版本`() {
        assertEquals("moke_20261115-143005_我的手机_android_0.2.2.mokesync", BackupName.build(t, "我的手机", "0.2.2"))
    }

    @Test
    fun `同一秒重名时在秒后追加序号，且能解析回来`() {
        val name = BackupName.build(t, "Pixel 9", "0.2.2-rc.1", suffix = 2)
        assertEquals("moke_20261115-143005-2_Pixel-9_android_0.2.2-rc.1.mokesync", name)
        val p = BackupName.parse(name)!!
        assertEquals(t, p.time)
        assertEquals("Pixel-9", p.device)
        assertEquals("android", p.platform)
        assertEquals("0.2.2-rc.1", p.version)
    }

    @Test
    fun `设备名里的分隔符、路径字符和控制字符被替换，过长截断`() {
        assertEquals("a-b-c-d", BackupName.sanitize("a_b/c\\d"))
        assertEquals("unknown", BackupName.sanitize("  __ "))
        assertEquals(32, BackupName.sanitize("x".repeat(80)).length)
        val name = BackupName.build(t, "my_phone:1", "0.2.2")
        assertEquals("my-phone-1", BackupName.parse(name)!!.device)
    }

    @Test
    fun `按文件名排序就是按时间排序`() {
        val names = listOf(
            BackupName.build(t.plusDays(1), "B", "0.2.2"),
            BackupName.build(t, "Z", "0.2.2"),
            BackupName.build(t.plusMinutes(1), "A", "0.2.2"),
        )
        assertEquals(names.map { BackupName.parse(it)!!.time }.sorted(), names.sorted().map { BackupName.parse(it)!!.time })
    }

    @Test
    fun `不符合格式的文件解析为空，但扩展名对就仍算备份文件`() {
        assertNull(BackupName.parse("notes.txt"))
        assertNull(BackupName.parse("my-backup.mokesync"))
        assertNull(BackupName.parse("moke_2026-11-15_x_android_1.mokesync"))
        assertTrue(BackupName.isBackup("my-backup.mokesync"))
    }
}
