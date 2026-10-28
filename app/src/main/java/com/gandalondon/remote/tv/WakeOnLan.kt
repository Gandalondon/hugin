package com.gandalondon.remote.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Wakes a TV that is fully off. The TV listens for a "magic packet" (its
 * network card address repeated 16 times) when "Turn on via Wi-Fi" is
 * enabled in its settings. Sent several ways at once, as routers differ in
 * which they pass on.
 */
suspend fun wakeOnLan(macs: List<String>, host: String?): Boolean = withContext(Dispatchers.IO) {
    val targets = buildList {
        add("255.255.255.255")
        host?.let {
            add(it)                                            // direct, if the router remembers it
            it.substringBeforeLast('.', "").takeIf { p -> p.isNotEmpty() }?.let { p -> add("$p.255") }
        }
    }.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }

    var sent = false
    runCatching {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            macs.forEach { mac ->
                val packet = magicPacket(mac) ?: return@forEach
                repeat(3) {
                    targets.forEach { addr ->
                        listOf(9, 7).forEach { port ->
                            runCatching {
                                socket.send(DatagramPacket(packet, packet.size, addr, port))
                                sent = true
                            }
                        }
                    }
                }
            }
        }
    }
    sent
}

private fun magicPacket(mac: String): ByteArray? {
    val bytes = mac.split(':', '-').mapNotNull { it.toIntOrNull(16)?.toByte() }
    if (bytes.size != 6) return null
    return ByteArray(6) { 0xFF.toByte() } + List(16) { bytes }.flatten().toByteArray()
}
