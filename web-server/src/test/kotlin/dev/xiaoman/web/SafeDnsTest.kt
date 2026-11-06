package dev.xiaoman.web

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.*

class SafeDnsTest {
    @Test fun rejectsNonPublicAddresses() {
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.1.1", "0.0.0.0",
            "224.0.0.1", "100.64.0.1", "100.127.255.254", "::1", "::", "fc00::1", "fdff::1", "fe80::1", "ff02::1").forEach { ip ->
            val dns = SafeDns(object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName(ip)) })
            assertFailsWith<UnknownHostException>(ip) { dns.lookup("example.test") }
        }
    }
    @Test fun filtersMixedAnswersAndAllowsPublicAddresses() {
        val public = InetAddress.getByName("8.8.8.8")
        val dns = SafeDns(object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName("10.0.0.1"), public) })
        assertEquals(listOf(public), dns.lookup("example.test"))
        assertTrue(SafeDns.isPublic(InetAddress.getByName("2606:4700:4700::1111")))
        assertTrue(SafeDns.isPublic(InetAddress.getByName("100.128.0.1")))
    }
}
