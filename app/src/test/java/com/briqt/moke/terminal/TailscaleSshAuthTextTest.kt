package com.briqt.moke.terminal

// type: authentication banner trust-boundary regression tests
import org.junit.Assert.*
import org.junit.Test

class TailscaleSshAuthTextTest {
    @Test fun `only absolute https links without credentials or controls are accepted`() {
        val valid = listOf(
            "https://login.example.test/a/check?token=secret#approve",
            "HTTPS://login.example.test/check",
            "https://login.example.test:8443/check",
            "https://[2001:db8::1]/check",
        )
        for (url in valid) assertTrue(url, TailscaleSshAuthText.isSafeUrl(url))
        val invalid = listOf(
            "http://login.example.test/check", "javascript:alert(1)", "javascript:https://login.example.test/check",
            "intent://login.example.test/check", "//login.example.test/check", "/check", "https:check",
            "https:///check", "https://user:password@login.example.test/check", "https://user@login.example.test/check",
            "https://login.example.test/\u0000secret", "https://login.example.test/\r\nsecret",
            "https://login.example.test/%0asecret", "https://login.example.test/%1bsecret",
            "https://login.example.test/%C2%85secret", "https://login.example.test/%E2%80%AEsecret",
            "https://login.example.test/‮secret", "https://login.example.test/has space",
            "https://login.example.test\\@evil.test/check", "https://login.example.test/%5csecret",
            "https://login.example.test:0/check", "https://login.example.test:65536/check", "https://",
        )
        for (url in invalid) assertFalse(url, TailscaleSshAuthText.isSafeUrl(url))
    }

    @Test fun `banner exposes a valid link separately and never repeats its token in text`() {
        val url = "https://login.example.test/check?token=secret"
        val banner = TailscaleSshAuthText.banner("Check required.\nVisit: $url\nWaiting for approval.")
        assertEquals(url, banner.url)
        assertEquals("Check required. Visit: Waiting for approval.", banner.message)
        assertFalse(banner.message.contains("secret"))
        assertFalse(banner.message.contains("https://"))
    }

    @Test fun `malicious link schemes and userinfo are neither offered nor shown in error text`() {
        for (url in listOf("http://bad.test/token", "https://user:secret@bad.test/token", "javascript:https://bad.test/token")) {
            val banner = TailscaleSshAuthText.banner("Access denied. $url Check the SSH policy.")
            assertNull(banner.url)
            assertEquals("Access denied. Check the SSH policy.", banner.message)
        }
    }

    @Test fun `text strips terminal escapes controls and bidi formatting and is bounded`() {
        val banner = TailscaleSshAuthText.banner("\u001b[31mDenied\u001b[0m\u0000‮\r\nRetry\tpolicy")
        assertEquals("Denied Retry policy", banner.message)
        assertNull(banner.url)
        assertEquals(2_000, TailscaleSshAuthText.banner("x".repeat(20_000)).message.length)
        assertEquals("user@host", TailscaleSshAuthText.target("\u001b[31muser@host\u0000‮\u001b[0m"))
        assertEquals(200, TailscaleSshAuthText.target("x".repeat(10_000)).length)
    }

    @Test fun `a truncated banner or oversized link cannot become an authentication URL`() {
        assertNull(TailscaleSshAuthText.banner("https://login.example.test/" + "x".repeat(20_000)).url)
        assertFalse(TailscaleSshAuthText.isSafeUrl("https://login.example.test/" + "x".repeat(4_100)))
    }

    @Test fun `prose punctuation and an invalid first URL do not prevent a later safe URL`() {
        val url = "https://login.example.test/check"
        val banner = TailscaleSshAuthText.banner("Denied https://user:secret@bad.test. Try ($url).")
        assertEquals(url, banner.url)
        assertFalse(banner.message.contains("secret"))
    }

    @Test fun `a refusal without a link remains actionable`() {
        assertEquals(TailscaleSshBanner("User is not permitted by the SSH policy.", null),
            TailscaleSshAuthText.banner("User is not permitted by the SSH policy."))
    }
}
