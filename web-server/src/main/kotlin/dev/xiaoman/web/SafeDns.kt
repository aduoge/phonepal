package dev.xiaoman.web

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/** Filters every resolved address, including DNS answers changed after configuration. */
class SafeDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> = delegate.lookup(hostname).filter(::isPublic)
        .ifEmpty { throw UnknownHostException("不允许访问内网地址") }
    companion object {
        fun isPublic(address: InetAddress): Boolean {
            if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
                address.isAnyLocalAddress || address.isMulticastAddress) return false
            val b = address.address.map { it.toInt() and 255 }
            if (b.size == 4 && b[0] == 100 && b[1] in 64..127) return false
            if (b.size == 16 && b[0] and 254 == 252) return false
            return true
        }
    }
}
