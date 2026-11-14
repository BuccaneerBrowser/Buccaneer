package com.buccaneer.browser

import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class Ipv6PreferenceProxyTest {
    @Test fun `unreachable IPv6 falls back to IPv4`() { ipv4Fallback(true) }
    @Test fun `device without IPv6 uses IPv4 unchanged`() { ipv4Fallback(false) }

    private fun ipv4Fallback(hasV6: Boolean) {
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            thread(isDaemon = true) {
                server.accept().use { socket ->
                    socket.getOutputStream().write(socket.getInputStream().read())
                }
            }
            val families = java.util.concurrent.CopyOnWriteArrayList<InetAddress>()
            Ipv6PreferenceProxy(
                resolve = { arrayOf(InetAddress.getByName("::1"), InetAddress.getByName("127.0.0.1")) },
                canUseIpv6 = { hasV6 },
                onConnect = { _, address -> families.add(address) }
            ).use { proxy ->
                Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", proxy.port))).use { client ->
                    client.soTimeout = 3000
                    client.connect(InetSocketAddress.createUnresolved("movy.sx", server.localPort), 3000)
                    client.getOutputStream().write(171)
                    assertEquals(171, client.getInputStream().read())
                }
                assertTrue(families.single() is Inet4Address)
            }
        }
    }

    @Test fun `close unblocks accept and releases loopback listener`() {
        val proxy = Ipv6PreferenceProxy()
        val port = proxy.port
        proxy.close()
        try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
            fail("closed proxy still listening")
        } catch (_: java.io.IOException) { }
    }

    @Test fun `SOCKS domain connects IPv6 before offered IPv4 without terminating TLS bytes`() {
        val v6 = InetAddress.getByName("::1")
        ServerSocket(0, 4, v6).use { server ->
            val done = CountDownLatch(1)
            thread(isDaemon = true) {
                server.accept().use { socket ->
                    val b = socket.getInputStream().read()
                    socket.getOutputStream().write(b)
                }
                done.countDown()
            }
            val families = java.util.concurrent.CopyOnWriteArrayList<String>()
            Ipv6PreferenceProxy(
                resolve = { arrayOf(InetAddress.getByName("127.0.0.1"), v6) },
                canUseIpv6 = { true },
                onConnect = { _, address -> families.add(address.hostAddress) }
            ).use { proxy ->
                Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", proxy.port))).use { client ->
                    client.soTimeout = 3000
                    client.connect(InetSocketAddress.createUnresolved("flixer.gd", server.localPort), 3000)
                    client.getOutputStream().write(22) // opaque TLS handshake record byte
                    assertEquals(22, client.getInputStream().read())
                }
                assertTrue(done.await(3, TimeUnit.SECONDS))
                assertTrue(InetAddress.getByName(families.single()) is Inet6Address)
            }
        }
    }
}
