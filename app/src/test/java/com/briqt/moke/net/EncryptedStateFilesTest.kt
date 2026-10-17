package com.briqt.moke.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EncryptedStateFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = EncryptedStateFiles(tmp.root, { "ENC:" + it.reversed() }, {
        require(it.startsWith("ENC:")); it.removePrefix("ENC:").reversed()
    })

    @Test fun `round trip, delete and missing keys`() {
        val s = store()
        assertNull(s.read("home", "_machinekey"))
        val value = byteArrayOf(1, 0, -1, 42)
        s.write("home", "_machinekey", value)
        assertArrayEquals(value, s.read("home", "_machinekey"))
        s.write("home", "_machinekey", byteArrayOf())
        assertArrayEquals(byteArrayOf(), s.read("home", "_machinekey"))
        s.write("home", "_machinekey", null)
        assertNull(s.read("home", "_machinekey"))
    }

    @Test fun `nothing is stored in plaintext and keys cannot escape the directory`() {
        val s = store()
        s.write("home", "../../evil", "node-private-key".toByteArray())
        val files = tmp.root.walkTopDown().filter { it.isFile }.toList()
        assertEquals(1, files.size)
        assertTrue(files[0].canonicalPath.startsWith(tmp.root.canonicalPath + "/home/"))
        assertFalse(files[0].readText().contains("bm9kZS1wcml2YXRlLWtleQ")) // base64 of the value
        assertTrue(runCatching { s.write("../x", "k", byteArrayOf(1)) }.isFailure)
        assertTrue(runCatching { s.read("..", "k") }.isFailure)
    }

    @Test fun `networks are isolated and deleting one removes only its state`() {
        val s = store()
        s.write("home", "k", byteArrayOf(1))
        s.write("work", "k", byteArrayOf(2))
        s.deleteNetwork("home")
        assertNull(s.read("home", "k"))
        assertArrayEquals(byteArrayOf(2), s.read("work", "k"))
    }
}
