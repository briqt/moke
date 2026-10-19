package com.briqt.moke.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SshKeyPasswordFinderTest {
    @Test fun `only an empty passphrase omits the password finder`() {
        assertNull(keyPasswordFinder(""))
    }

    @Test fun `whitespace only passphrases remain passwords`() {
        for (passphrase in listOf(" ", "   ", "\t", " \t\n ")) {
            val finder = keyPasswordFinder(passphrase)
            assertNotNull(finder)
            assertArrayEquals(passphrase.toCharArray(), finder!!.reqPassword(null))
        }
    }

    @Test fun `leading trailing and internal whitespace is not trimmed or normalized`() {
        for (passphrase in listOf(" secret", "secret ", " secret ", "a  b", "\tsecret\n")) {
            assertArrayEquals(passphrase.toCharArray(), keyPasswordFinder(passphrase)!!.reqPassword(null))
        }
    }
}
