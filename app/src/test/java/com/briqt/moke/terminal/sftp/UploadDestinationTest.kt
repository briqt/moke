package com.briqt.moke.terminal.sftp

import com.briqt.moke.data.Host
import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.data.NetworkKind
import com.briqt.moke.data.Route
import net.schmizz.sshj.sftp.OpenMode
import org.junit.Assert.*
import org.junit.Test

class UploadDestinationTest {
    private val host = Host(id = "target", host = "server.example", port = 22, username = "user")

    @Test fun `an unconfirmed upload can only create a new file atomically`() {
        val modes = uploadOpenModes(0, false)
        assertTrue(OpenMode.EXCL in modes)
        assertTrue(OpenMode.CREAT in modes)
        assertFalse(OpenMode.TRUNC in modes)
    }

    @Test fun `only explicit replacement can truncate an existing file`() {
        val modes = uploadOpenModes(0, true)
        assertTrue(OpenMode.TRUNC in modes)
        assertFalse(OpenMode.EXCL in modes)
    }

    @Test fun `resume never creates a missing file with a hole`() {
        val modes = uploadOpenModes(42, false)
        assertEquals(setOf(OpenMode.WRITE), modes.toSet())
        assertEquals(modes, uploadOpenModes(42, true))
    }

    @Test fun `the checked destination rejects another host port or account`() {
        val checked = UploadDestination.from(host, null, null)
        assertTrue(checked.matches(Route.Ok(host, null, null)))
        assertFalse(checked.matches(Route.Ok(host.copy(host = "another.example"), null, null)))
        assertFalse(checked.matches(Route.Ok(host.copy(port = 2222), null, null)))
        assertFalse(checked.matches(Route.Ok(host.copy(username = "another"), null, null)))
    }

    @Test fun `the checked destination includes the real first hop and proxy revision`() {
        val proxy = NetworkEntry(id = "proxy", name = "Proxy", proxyHost = "proxy.example")
        val jump = host.copy(id = "jump", host = "jump.example")
        val checked = UploadDestination.from(host, jump, proxy)
        assertFalse(checked.matches(Route.Ok(host, null, proxy)))
        assertFalse(checked.matches(Route.Ok(host, jump.copy(host = "other-jump.example"), proxy)))
        assertFalse(checked.matches(Route.Ok(host, jump, proxy.copy(revision = 2))))
        assertFalse(checked.matches(Route.Ok(host, jump, proxy.copy(proxyHost = "other-proxy.example"))))
    }

    @Test fun `tailnet control server is part of the checked identity`() {
        val network = NetworkEntry(id = "tailnet", name = "Network", kind = NetworkKind.TAILSCALE)
        val checked = UploadDestination.from(host, null, network)
        assertFalse(checked.matches(Route.Ok(host, null, network.copy(controlUrl = "https://control.example"))))
    }

    @Test fun `labels and SSH credentials do not change the destination identity`() {
        val checked = UploadDestination.from(host, null, null)
        assertTrue(checked.matches(Route.Ok(host.copy(label = "Renamed", password = "updated", passphrase = " updated "), null, null)))
    }

    @Test fun `a restored upload without its original destination is refused`() {
        val route = Route.Ok(host, null, null)
        assertFalse(uploadDestinationMatches(null, route))
        assertTrue(uploadDestinationMatches(UploadDestination.from(host, null, null), route))
        assertFalse(uploadDestinationMatches(UploadDestination.from(host.copy(port = 2222), null, null), route))
    }

    @Test fun `document metadata must be a basename not a provider supplied path`() {
        for (name in listOf("", ".", "..", "../config", "/etc/config", "dir/file", "bad\u0000name")) assertFalse(name, validUploadName(name))
        for (name in listOf("file.txt", " name with spaces ", "back\\slash.txt")) assertTrue(name, validUploadName(name))
    }
}
