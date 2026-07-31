package com.briqt.moke.terminal

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoshPtyTest {
    @Test
    fun `treats Android pty EIO as closed slave`() {
        assertTrue(MoshPty.isClosed(IOException("read failed: EIO (I/O error)")))
    }

    @Test
    fun `finds EIO in wrapped cause`() {
        assertTrue(MoshPty.isClosed(IOException("read failed", IOException("EIO"))))
    }

    @Test
    fun `does not hide unrelated read failures`() {
        assertFalse(MoshPty.isClosed(IOException("bad file descriptor")))
    }
}
