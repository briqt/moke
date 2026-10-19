package com.briqt.moke.ui

import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.data.NetworkKind
import com.briqt.moke.data.ProxyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormValidationTest {
    @Test fun `ports do not default truncate or strip invalid pasted input`() {
        for (text in listOf("", " ", "0", "65536", "10800x", "12.34", "-22", "+22", "999999999999999", "２２")) {
            assertNull(text, FormValidation.port(text))
        }
        assertEquals(22, FormValidation.port("22"))
        assertEquals(65535, FormValidation.port("65535"))
        assertEquals(443, FormValidation.port(" 443 "))
    }

    @Test fun `proxy ports are prefilled with their real defaults and cleared ports stay cleared`() {
        assertEquals(1080, defaultPort(ProxyType.SOCKS5))
        assertEquals(1080, defaultPort(ProxyType.SOCKS4))
        assertEquals(8080, defaultPort(ProxyType.HTTP))
        assertEquals(443, defaultPort(ProxyType.HTTPS))
        assertEquals("443", changedProxyPort("8080", ProxyType.HTTP, ProxyType.HTTPS))
        assertEquals("", changedProxyPort("", ProxyType.HTTP, ProxyType.HTTPS))
        assertEquals("70000", changedProxyPort("70000", ProxyType.HTTP, ProxyType.HTTPS))
        assertEquals("3128", changedProxyPort("3128", ProxyType.HTTP, ProxyType.HTTPS))
    }

    @Test fun `local forward ports retain empty automatic allocation and privileged port limit`() {
        assertNull(FormValidation.port(""))
        assertNull(FormValidation.port("1023", 1024))
        assertEquals(1024, FormValidation.port("1024", 1024))
        assertEquals(65535, FormValidation.port("65535", 1024))
    }

    @Test fun `addresses admit short intranet names and IPv6 without DNS`() {
        for (host in listOf("buildbox", "host.local", "10.0.0.1", "::1", "fd00::1234", "fe80::1%wlan0")) {
            assertTrue(host, FormValidation.host(host))
            assertTrue(host, FormValidation.proxyHost(host))
        }
        for (host in listOf("", "a b", "https://proxy", "host/path", "user@host", "a\\b", "a\u0000b", "a\rb", "a\nb", "a".repeat(254))) {
            assertFalse(host, FormValidation.host(host))
            assertFalse(host, FormValidation.proxyHost(host))
        }
        assertTrue(FormValidation.proxyHost("é".repeat(126)))
        assertFalse(FormValidation.proxyHost("é".repeat(127)))
    }

    @Test fun `control URLs preserve paths but reject unsafe or ambiguous components`() {
        for (url in listOf("https://control", "https://control.example/path/prefix/", "https://[fd00::1]:8443/control")) {
            assertTrue(url, FormValidation.controlUrl(url))
        }
        for (url in listOf("http://control", "https://", "https:///path", "https://user@control", "https://control?key=value", "https://control?", "https://control#", "https://control:0", "https://control:70000", "https://control:", "https://bad host", "https://control/" + "a".repeat(4096))) {
            assertFalse(url, FormValidation.controlUrl(url))
        }
    }

    @Test fun `device names check effective default UTF8 bytes and Go forbidden characters`() {
        assertTrue(FormValidation.deviceName("", "moke-default"))
        assertFalse(FormValidation.deviceName("", ""))
        assertTrue(FormValidation.deviceName("a".repeat(63), "default"))
        assertFalse(FormValidation.deviceName("a".repeat(64), "default"))
        assertTrue(FormValidation.deviceName("墨".repeat(21), "default"))
        assertFalse(FormValidation.deviceName("墨".repeat(22), "default"))
        for (name in listOf("a b", "a.b", "a/b", "a\\b", "a\tb", "a\rb", "a\nb", "a\u0000b")) {
            assertFalse(name, FormValidation.deviceName(name, "default"))
        }
    }

    @Test fun `proxy credentials preserve significant whitespace and enforce protocol byte constraints`() {
        for (type in ProxyType.entries) {
            assertTrue(FormValidation.proxyCredential("  user  ", type, true))
            assertTrue(FormValidation.proxyCredential("  password\t ", type, false))
            assertTrue(FormValidation.proxyCredential("", type, false))
            assertTrue(FormValidation.proxyCredential("é".repeat(127) + "a", type, false))
            assertFalse(FormValidation.proxyCredential("é".repeat(128), type, false))
            for (credential in listOf("a\u0000b", "a\rb", "a\nb")) {
                assertFalse(FormValidation.proxyCredential(credential, type, true))
                assertFalse(FormValidation.proxyCredential(credential, type, false))
            }
        }
        assertFalse(FormValidation.proxyCredential("user:name", ProxyType.HTTP, true))
        assertFalse(FormValidation.proxyCredential("user:name", ProxyType.HTTPS, true))
        assertTrue(FormValidation.proxyCredential("user:name", ProxyType.SOCKS5, true))
        assertTrue(FormValidation.proxyCredential("pass:word", ProxyType.HTTP, false))
    }

    @Test fun `auth keys only permit trimming outside pasted whitespace`() {
        assertTrue(FormValidation.authKey(" \n\tkey-value\r\n"))
        assertTrue(FormValidation.authKey("a".repeat(4096)))
        assertFalse(FormValidation.authKey("a".repeat(4097)))
        for (key in listOf("key value", "key\tvalue", "key\rvalue", "key\nvalue", "key\u0000value")) {
            assertFalse(FormValidation.authKey(key))
        }
    }

    @Test fun `network names are case insensitive only within the same kind and exclude own ID`() {
        val entries = listOf(NetworkEntry(id = "proxy", name = "  Work  ", kind = NetworkKind.PROXY))
        assertTrue(FormValidation.duplicateName("work", "new", NetworkKind.PROXY, entries))
        assertFalse(FormValidation.duplicateName("WORK", "proxy", NetworkKind.PROXY, entries))
        assertFalse(FormValidation.duplicateName("WORK", "new", NetworkKind.TAILSCALE, entries))
    }

    @Test fun `extra roots require a parseable PEM certificate without changing trust policy`() {
        assertTrue(FormValidation.extraCa(""))
        assertTrue(FormValidation.extraCa(" \n "))
        assertFalse(FormValidation.extraCa("not PEM"))
        assertFalse(FormValidation.extraCa("-----BEGIN CERTIFICATE-----\nAA==\n-----END CERTIFICATE-----"))
        assertFalse(FormValidation.extraCa(certificate.replace("CERTIFICATE", "PRIVATE KEY")))
        assertTrue(FormValidation.extraCa(certificate))
        assertTrue(FormValidation.extraCa("ignored prose\n" + certificate + "\n" + certificate))
    }

    @Test fun `tmux names reject syntax separators and controls but preserve ordinary names`() {
        assertTrue(FormValidation.tmuxName(" dev session "))
        for (name in listOf("", " ", "dev.work", "dev:work", "a\nb", "a\u0000b")) assertFalse(name, FormValidation.tmuxName(name))
    }

    private val certificate = """
        -----BEGIN CERTIFICATE-----
        MIICGzCCAaGgAwIBAgIQQdKd0XLq7qeAwSxs6S+HUjAKBggqhkjOPQQDAzBPMQsw
        CQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJuZXQgU2VjdXJpdHkgUmVzZWFyY2gg
        R3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBYMjAeFw0yMDA5MDQwMDAwMDBaFw00
        MDA5MTcxNjAwMDBaME8xCzAJBgNVBAYTAlVTMSkwJwYDVQQKEyBJbnRlcm5ldCBT
        ZWN1cml0eSBSZXNlYXJjaCBHcm91cDEVMBMGA1UEAxMMSVNSRyBSb290IFgyMHYw
        EAYHKoZIzj0CAQYFK4EEACIDYgAEzZvVn4CDCuwJSvMWSj5cz3es3mcFDR0HttwW
        +1qLFNvicWDEukWVEYmO6gbf9yoWHKS5xcUy4APgHoIYOIvXRdgKam7mAHf7AlF9
        ItgKbppbd9/w+kHsOdx1ymgHDB/qo0IwQDAOBgNVHQ8BAf8EBAMCAQYwDwYDVR0T
        AQH/BAUwAwEB/zAdBgNVHQ4EFgQUfEKWrt5LSDv6kviejM9ti6lyN5UwCgYIKoZI
        zj0EAwMDaAAwZQIwe3lORlCEwkSHRhtFcP9Ymd70/aTSVaYgLXTWNLxBo1BfASdW
        tL4ndQavEi51mI38AjEAi/V3bNTIZargCyzuFJ0nN6T5U6VR5CmD1/iQMVtCnwr1
        /q4AaOeMSQ+2b1tbFfLn
        -----END CERTIFICATE-----
    """.trimIndent()
}
