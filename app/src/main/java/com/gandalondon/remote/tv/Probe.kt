package com.gandalondon.remote.tv

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Finds the TV from a network it is not on.
 *
 * When the TV sits behind a second router (a Google Wifi mesh plugged into the
 * main router), a phone on the main router's Wi-Fi can only reach it through
 * a port forward on the mesh router, at the mesh router's own address. Network
 * discovery cannot see through, so this checks every address on the phone's
 * network for the remote port and keeps the one presenting this TV's pinned
 * certificate. Nothing else can pass as the TV.
 */
suspend fun probeForTv(context: Context, pin: String?): String? = withContext(Dispatchers.IO) {
    if (pin == null) return@withContext null
    val prefix = localPrefix(context) ?: return@withContext null
    val gate = Semaphore(48)
    coroutineScope {
        (1..254).map { i ->
            async {
                gate.withPermit { "$prefix.$i".takeIf { presentsPin(it, pin) } }
            }
        }.awaitAll().firstOrNull { it != null }
    }
}

/** e.g. "192.168.1" for a phone at 192.168.1.23. */
private fun localPrefix(context: Context): String? {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
    val v4 = lp.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull() ?: return null
    return v4.hostAddress?.substringBeforeLast('.')
}

private fun presentsPin(host: String, pin: String): Boolean = runCatching {
    Socket().use { raw ->
        raw.connect(InetSocketAddress(host, 3001), 400)
        val trust = PinningTrust(null) // records the certificate without judging it
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        (tls.socketFactory.createSocket(raw, host, 3001, true) as SSLSocket).use { s ->
            s.soTimeout = 1_500
            s.startHandshake()
        }
        trust.seen == pin
    }
}.getOrDefault(false)
