package com.buccaneer.browser

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.*
import java.util.Collections
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

/** Loopback SOCKS5 byte tunnel. No VPN, DNS rewriting, TLS interception or polling.
 * Only WebView's scoped proxy override uses it. Blocking accept/read sleep in kernel.
 * IPv6 is tried before IPv4, never raced against it. Failed v6 falls back to native v4.
 */
class Ipv6PreferenceProxy(
    private val resolve: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) },
    private val canUseIpv6: () -> Boolean = { hasIpv6Interface() },
    private val onConnect: (String, InetAddress) -> Unit = { _, _ -> }
) : Closeable {
    private val listener = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    private val slots = Semaphore(32)
    private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
    @Volatile private var closed = false
    val port: Int get() = listener.localPort

    init {
        thread(isDaemon = true, name = "ipv6-accept") {
            while (!closed) {
                val client = try { listener.accept() } catch (_: IOException) { break }
                if (closed || !slots.tryAcquire()) { client.close(); continue }
                sockets.add(client)
                thread(isDaemon = true, name = "ipv6-connect") { serve(client) }
            }
        }
    }

    private fun serve(client: Socket) {
        var upstream: Socket? = null
        try {
            client.soTimeout = 15000
            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()
            if (input.readUnsignedByte() != 5) return
            val methods = ByteArray(input.readUnsignedByte())
            input.readFully(methods)
            if (methods.none { it.toInt() == 0 }) { output.write(byteArrayOf(5, -1)); return }
            output.write(byteArrayOf(5, 0))
            if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 1) return
            if (input.readUnsignedByte() != 0) return
            val host = when (input.readUnsignedByte()) {
                1 -> ByteArray(4).also { input.readFully(it) }.let { InetAddress.getByAddress(it).hostAddress!! }
                4 -> ByteArray(16).also { input.readFully(it) }.let { InetAddress.getByAddress(it).hostAddress!! }
                3 -> ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.toString(Charsets.US_ASCII)
                else -> return
            }
            val port = input.readUnsignedShort()
            if (host.isBlank() || port == 0) return
            upstream = connect(host, port)
            onConnect(host, upstream.inetAddress)
            output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            client.soTimeout = 0
            val remote = upstream
            thread(isDaemon = true, name = "ipv6-upload") {
                try {
                    input.copyTo(remote.getOutputStream(), 16384)
                    try { remote.shutdownOutput() } catch (_: IOException) { }
                } catch (_: IOException) {
                    try { remote.close() } catch (_: IOException) { }
                }
            }
            remote.getInputStream().copyTo(output, 16384)
        } catch (_: Exception) {
            // Tell SOCKS client connect failed when possible; never fabricate HTTP/TLS data.
            if (upstream == null) try {
                client.getOutputStream().write(byteArrayOf(5, 1, 0, 1, 0, 0, 0, 0, 0, 0))
            } catch (_: IOException) { }
        } finally {
            try { upstream?.close() } catch (_: IOException) { }
            try { client.close() } catch (_: IOException) { }
            sockets.remove(client)
            if (upstream != null) sockets.remove(upstream)
            slots.release()
        }
    }

    private fun connect(host: String, port: Int): Socket {
        val addresses = resolve(host)
        val usableV6 = canUseIpv6()
        val ordered = if (usableV6) {
            addresses.filterIsInstance<Inet6Address>().take(2) + addresses.filter { it !is Inet6Address }
        } else addresses.filter { it !is Inet6Address }
        var failure: IOException = IOException("No usable addresses")
        for (address in ordered) {
            if (closed) throw IOException("Proxy closed")
            val socket = Socket(Proxy.NO_PROXY)
            sockets.add(socket)
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(address, port), 2000)
                if (closed) throw IOException("Proxy closed")
                return socket
            } catch (e: IOException) {
                failure = e
                socket.close()
                sockets.remove(socket)
            }
        }
        throw failure
    }

    override fun close() {
        closed = true
        listener.close()
        synchronized(sockets) {
            sockets.forEach { try { it.close() } catch (_: IOException) { } }
            sockets.clear()
        }
    }

    companion object {
        private fun hasIpv6Interface(): Boolean = try {
            NetworkInterface.getNetworkInterfaces().toList().any { network ->
                network.isUp && network.inetAddresses.toList().any {
                    it is Inet6Address && !it.isLinkLocalAddress && !it.isLoopbackAddress && !it.isSiteLocalAddress
                }
            }
        } catch (_: Exception) { false }
    }
}
