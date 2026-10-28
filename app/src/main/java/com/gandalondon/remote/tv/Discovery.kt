package com.gandalondon.remote.tv

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

data class FoundTv(val host: String, val name: String)

/**
 * Finds LG webOS TVs on the Wi-Fi with an SSDP search for the TV's
 * second-screen service, the same service LG's own app uses.
 */
suspend fun discoverTvs(context: Context, timeoutMs: Long = 3_000): List<FoundTv> =
    withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi.createMulticastLock("tv-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
        val found = linkedMapOf<String, String?>() // host -> description URL
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 500
                val search = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: urn:lge-com:service:webos-second-screen:1\r\n\r\n"
                    ).toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) { socket.send(DatagramPacket(search, search.size, group, 1900)) }

                val buf = ByteArray(2048)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length)
                    if (!text.contains("webos-second-screen", ignoreCase = true)) continue
                    val host = packet.address.hostAddress ?: continue
                    val location = Regex("""(?im)^location:\s*(\S+)""").find(text)?.groupValues?.get(1)
                    if (host !in found) found[host] = location
                }
            }
        } finally {
            lock.release()
        }
        found.map { (host, location) -> FoundTv(host, location?.let(::friendlyName) ?: "LG TV") }
    }

/** Reads the TV's name (e.g. "[LG] webOS TV OLED65C14LB") from its UPnP description. */
private fun friendlyName(location: String): String? = runCatching {
    val conn = URL(location).openConnection() as HttpURLConnection
    conn.connectTimeout = 1_500
    conn.readTimeout = 1_500
    conn.inputStream.bufferedReader().use { it.readText() }
        .let { Regex("<friendlyName>(.*?)</friendlyName>").find(it)?.groupValues?.get(1) }
        ?.replace("[LG]", "")?.trim()
        ?.takeIf { it.isNotBlank() }
}.getOrNull()
