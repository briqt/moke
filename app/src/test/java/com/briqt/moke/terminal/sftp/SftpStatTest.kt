package com.briqt.moke.terminal.sftp

import java.io.IOException
import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.net.NetworkException
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.Response.StatusCode
import net.schmizz.sshj.sftp.SFTPException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SftpStatTest {
    @Test fun `only the upstream missing result is translated to absence`() {
        assertNull(readSftpStat("missing") { null })
        var requested = ""
        val present = readSftpStat("present") { requested = it; FileAttributes.EMPTY }
        assertEquals("present", requested)
        assertNotNull(present)
    }

    @Test fun `permission transport and non missing SFTP statuses remain failures`() {
        val errors = listOf(
            SFTPException(StatusCode.PERMISSION_DENIED, "denied"),
            SFTPException(StatusCode.NO_CONNECTION, "disconnected"),
            SFTPException(StatusCode.BAD_MESSAGE, "protocol"),
            SFTPException(StatusCode.NO_SUCH_PATH, "not NO_SUCH_FILE"),
            IOException("transport"),
            IllegalStateException("wrapped", IOException("transport")),
            IllegalStateException("outer", RuntimeException(NetworkException("LOGIN_EXPIRED", NetworkEntry(id = "n"), "expired"))),
        )
        errors.forEach { expected ->
            val error = runCatching { readSftpStat("file") { throw expected } }.exceptionOrNull()
            assertSame(expected, error)
        }
    }
}
