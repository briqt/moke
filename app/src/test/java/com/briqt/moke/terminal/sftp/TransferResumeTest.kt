package com.briqt.moke.terminal.sftp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 续传判定：**宁可重传，不可拼坏**。
 * 拼坏的文件看起来是完整的（大小对得上），却在中间一段是旧内容——这种损坏最难被发现。
 */
class TransferResumeTest {

    private fun download(done: Long, size: Long, mtime: Long, append: Boolean = true) = TransferTask(
        hostId = "h",
        hostLabel = "h",
        direction = TransferDirection.DOWNLOAD,
        remotePath = "/tmp/f",
        name = "f",
        total = size,
        done = done,
        remoteSize = size,
        remoteMtime = mtime,
        appendSupported = append,
    )

    @Test
    fun `远端未变且还有剩余则可续`() {
        val t = download(done = 100, size = 1000, mtime = 42)
        assertTrue(TransferTask.canResume(t, RemoteStat(1000, 42, false)))
    }

    @Test
    fun `远端大小变了就不能续`() {
        val t = download(done = 100, size = 1000, mtime = 42)
        assertFalse(TransferTask.canResume(t, RemoteStat(1200, 42, false)))
    }

    @Test
    fun `远端修改时间变了就不能续`() {
        // 大小可能碰巧一样（改了一行等长内容），mtime 是唯一能察觉的信号。
        val t = download(done = 100, size = 1000, mtime = 42)
        assertFalse(TransferTask.canResume(t, RemoteStat(1000, 99, false)))
    }

    @Test
    fun `远端消失或变成目录不能续`() {
        val t = download(done = 100, size = 1000, mtime = 42)
        assertFalse(TransferTask.canResume(t, null))
        assertFalse(TransferTask.canResume(t, RemoteStat(1000, 42, true)))
    }

    @Test
    fun `本地不支持追加写只能整传`() {
        val t = download(done = 100, size = 1000, mtime = 42, append = false)
        assertFalse(TransferTask.canResume(t, RemoteStat(1000, 42, false)))
    }

    @Test
    fun `没有断点或已传完不走续传`() {
        assertFalse(TransferTask.canResume(download(0, 1000, 42), RemoteStat(1000, 42, false)))
        assertFalse(TransferTask.canResume(download(1000, 1000, 42), RemoteStat(1000, 42, false)))
    }

    private fun upload(done: Long, total: Long) = TransferTask(
        hostId = "h",
        hostLabel = "h",
        direction = TransferDirection.UPLOAD,
        remotePath = "/tmp/f",
        name = "f",
        total = total,
        done = done,
    )

    @Test
    fun `上传续传要求远端已有字节正好等于断点`() {
        val t = upload(done = 500, total = 1000)
        assertTrue(TransferTask.canResumeUpload(t, remoteExisting = 500, localSize = 1000))
        // 少了：上次的写没落盘
        assertFalse(TransferTask.canResumeUpload(t, remoteExisting = 400, localSize = 1000))
        // 多了：有别人在写同一个文件
        assertFalse(TransferTask.canResumeUpload(t, remoteExisting = 700, localSize = 1000))
    }

    @Test
    fun `本地源文件变了就重传`() {
        val t = upload(done = 500, total = 1000)
        assertFalse(TransferTask.canResumeUpload(t, remoteExisting = 500, localSize = 1234))
    }

    @Test
    fun `任务表 JSON 往返保留断点信息`() {
        val t = download(done = 123, size = 456, mtime = 789).copy(
            localUri = "content://x/y",
            treeUri = "content://tree",
            state = TransferState.FAILED,
            error = "boom",
        )
        val back = TransferTask.fromJson(t.toJson())
        // 断点信息丢一个，重启后就要么续错、要么白传一遍。
        assertTrue(back.done == 123L && back.remoteSize == 456L && back.remoteMtime == 789L)
        assertTrue(back.localUri == "content://x/y" && back.treeUri == "content://tree")
        assertTrue(back.state == TransferState.FAILED && back.error == "boom")
    }
}
