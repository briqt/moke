package com.briqt.moke.net

import java.net.InetAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkSocketTest {

    @Test
    fun domainUsesDomainAddressType() {
        val target = NetworkSocket.socksTarget("example.com", 22)
        assertEquals(5, target[0].toInt())
        assertEquals(1, target[1].toInt())
        assertEquals(0, target[2].toInt())
        assertEquals(3, target[3].toInt())
        assertEquals(11, target[4].toInt())
        assertEquals("example.com", String(target, 5, 11))
        assertEquals(22, ((target[16].toInt() and 0xff) shl 8) or (target[17].toInt() and 0xff))
        assertEquals(18, target.size)
    }

    @Test
    fun ipv4LiteralUsesIpv4AddressType() {
        val target = NetworkSocket.socksTarget("127.0.0.1", 22)
        assertEquals(1, target[3].toInt())
        assertArrayEquals(byteArrayOf(127, 0, 0, 1), target.copyOfRange(4, 8))
        assertEquals(10, target.size)
    }

    @Test
    fun ipv6LiteralUsesIpv6AddressType() {
        val target = NetworkSocket.socksTarget("2001:db8::1", 22)
        assertEquals(4, target[3].toInt())
        assertArrayEquals(InetAddress.getByName("2001:db8::1").address, target.copyOfRange(4, 20))
        assertEquals(22, target.size)
    }
}
