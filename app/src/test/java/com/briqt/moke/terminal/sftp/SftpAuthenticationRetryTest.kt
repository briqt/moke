package com.briqt.moke.terminal.sftp

import com.briqt.moke.terminal.TailscaleSshAuthException
import com.briqt.moke.terminal.TailscaleSshAuthFailureKind
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class SftpAuthenticationRetryTest {
    @Test fun `cancel timeout and rejection do not start another browser approval`() {
        for (kind in TailscaleSshAuthFailureKind.entries) {
            assertFalse(shouldRetrySftpBrowse(TailscaleSshAuthException(kind, "safe localized message")))
        }
    }

    @Test fun `ordinary disconnected browsing keeps its existing reconnect behavior`() {
        assertTrue(shouldRetrySftpBrowse(IOException("connection lost")))
    }
}
