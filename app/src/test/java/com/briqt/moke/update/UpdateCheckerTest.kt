package com.briqt.moke.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun `stable patch version is newer`() {
        assertTrue(UpdateChecker.isNewer("0.1.17", "0.1.16"))
        assertFalse(UpdateChecker.isNewer("0.1.16", "0.1.16"))
    }

    @Test
    fun `stable release is newer than matching prerelease`() {
        assertTrue(UpdateChecker.isNewer("0.1.17", "0.1.17-rc.1"))
        assertFalse(UpdateChecker.isNewer("0.1.17-rc.1", "0.1.17"))
    }

    @Test
    fun `prerelease identifiers follow semver precedence`() {
        assertTrue(UpdateChecker.isNewer("0.1.17-rc.2", "0.1.17-rc.1"))
        assertTrue(UpdateChecker.isNewer("0.1.17-beta.11", "0.1.17-beta.2"))
        assertTrue(UpdateChecker.isNewer("0.1.17-rc.1", "0.1.17-rc"))
        assertFalse(UpdateChecker.isNewer("0.1.17-1", "0.1.17-alpha"))
    }

    @Test
    fun `older stable release is not newer than current prerelease`() {
        assertFalse(UpdateChecker.isNewer("0.1.16", "0.1.17-rc.1"))
    }

    @Test
    fun `invalid version is never offered`() {
        assertFalse(UpdateChecker.isNewer("latest", "0.1.16"))
        assertFalse(UpdateChecker.isNewer("0.1.17", "debug"))
    }
}
