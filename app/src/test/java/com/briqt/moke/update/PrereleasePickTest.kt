package com.briqt.moke.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「包含预览版」的挑选口径。 */
class PrereleasePickTest {

    private fun r(tag: String, pre: Boolean = false, draft: Boolean = false) =
        ReleaseEntry(tag = tag, url = "u/$tag", prerelease = pre, draft = draft)

    private val feed = listOf(
        r("v0.1.17-rc.4", pre = true),
        r("v0.1.17-rc.3", pre = true),
        r("v0.1.16"),
        r("v0.1.15"),
    )

    @Test
    fun `off keeps stable only`() {
        assertEquals("v0.1.16", UpdateChecker.pickLatest(feed, includePrerelease = false)?.tag)
    }

    @Test
    fun `on prefers the newest prerelease`() {
        assertEquals("v0.1.17-rc.4", UpdateChecker.pickLatest(feed, includePrerelease = true)?.tag)
    }

    @Test
    fun `drafts never count`() {
        val withDraft = listOf(r("v0.2.0", draft = true)) + feed
        assertEquals("v0.1.16", UpdateChecker.pickLatest(withDraft, false)?.tag)
        assertEquals("v0.1.17-rc.4", UpdateChecker.pickLatest(withDraft, true)?.tag)
    }

    @Test
    fun `github ordering is not trusted`() {
        // GitHub 按创建时间排；补发一个旧版本就会让"第一条"是旧的。
        val outOfOrder = listOf(r("v0.1.14"), r("v0.1.16"), r("v0.1.15"))
        assertEquals("v0.1.16", UpdateChecker.pickLatest(outOfOrder, false)?.tag)
    }

    @Test
    fun `stable outranks its own prereleases`() {
        val released = listOf(r("v0.1.17"), r("v0.1.17-rc.4", pre = true))
        assertEquals("v0.1.17", UpdateChecker.pickLatest(released, true)?.tag)
    }

    @Test
    fun `unparseable tags fall back instead of showing nothing`() {
        val weird = listOf(r("nightly"), r("latest"))
        assertEquals("nightly", UpdateChecker.pickLatest(weird, false)?.tag)
        assertNull(UpdateChecker.pickLatest(emptyList(), true))
        assertNull(UpdateChecker.pickLatest(listOf(r("v1.0.0", draft = true)), true))
    }

    @Test
    fun `rc user is not told to downgrade to the current stable`() {
        // 跑 rc.4、开关关掉时，正式版仍是 0.1.16 → 不该提示"有更新"。
        assertFalse(UpdateChecker.isNewer("0.1.16", "0.1.17-rc.4"))
        // 而 rc.4 → rc.5 与 rc.4 → 0.1.17 都算更新。
        assertTrue(UpdateChecker.isNewer("0.1.17-rc.5", "0.1.17-rc.4"))
        assertTrue(UpdateChecker.isNewer("0.1.17", "0.1.17-rc.4"))
    }
}
