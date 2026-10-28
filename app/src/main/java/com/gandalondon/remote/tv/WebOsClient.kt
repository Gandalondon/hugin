package com.gandalondon.remote.tv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

sealed interface Conn {
    data object Idle : Conn
    data object Connecting : Conn
    data object AwaitingPairing : Conn
    data object Connected : Conn
    data class Failed(val reason: Reason, val detail: String? = null) : Conn
}

enum class Reason { Unreachable, PairingDeclined, CertificateChanged }

class TvException(message: String) : Exception(message)

/**
 * Minimal client for LG webOS's local remote protocol (SSAP over WebSocket),
 * covering only what this remote needs: pairing, volume, inputs and the
 * foreground app.
 *
 * Protocol details mirror aiowebostv (Home Assistant's maintained webOS
 * library): the same registration manifest, endpoints and wss:3001 / ws:3000
 * ports. There is no maintained JVM equivalent, so the small subset is
 * implemented here on OkHttp.
 */
class WebOsClient(private val store: Store) {

    private val _conn = MutableStateFlow<Conn>(Conn.Idle)
    val conn: StateFlow<Conn> = _conn

    private val _volume = MutableStateFlow<Int?>(null)
    val volume: StateFlow<Int?> = _volume

    private val _foregroundApp = MutableStateFlow<String?>(null)
    val foregroundApp: StateFlow<String?> = _foregroundApp

    private val _inputs = MutableStateFlow<List<ExternalInput>?>(null)
    val inputs: StateFlow<List<ExternalInput>?> = _inputs

    /** The TV's own power state, e.g. "Active", "Active Standby", "Screen Off". Null if not reported. */
    private val _powerState = MutableStateFlow<String?>(null)
    val powerState: StateFlow<String?> = _powerState

    private var socket: WebSocket? = null
    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val subscriptions = ConcurrentHashMap<String, (JSONObject) -> Unit>()

    /** Increments on every connect/disconnect so late callbacks from an old socket are ignored. */
    @Volatile private var session = 0

    fun connect(host: String) {
        disconnect()
        val gen = session
        _conn.value = Conn.Connecting
        open(host, secure = true, gen)
    }

    fun disconnect() {
        session++
        socket?.close(1000, null)
        socket = null
        pending.values.forEach { it.completeExceptionally(TvException("Disconnected")) }
        pending.clear()
        subscriptions.clear()
        if (_conn.value !is Conn.Failed) _conn.value = Conn.Idle
    }

    // region connection

    private fun open(host: String, secure: Boolean, gen: Int) {
        val pin = PinningTrust(store.certPin)
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(pin), SecureRandom()) }
        val http = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .sslSocketFactory(tls.socketFactory, pin)
            // The TV's certificate names LG, not its IP address. Identity is
            // instead checked by pinning the certificate fingerprint.
            .hostnameVerifier { _, _ -> true }
            .build()

        val url = if (secure) "wss://$host:3001" else "ws://$host:3000"
        var opened = false

        socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != session) return webSocket.cancel()
                opened = true
                socket = webSocket
                register(webSocket, pin)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != session) return
                handle(webSocket, JSONObject(text), pin)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen == session) dropped()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen != session) return
                when {
                    t.hasCause<PinMismatch>() ->
                        _conn.value = Conn.Failed(Reason.CertificateChanged)
                    // Older firmware only offers plain ws on 3000. Never fall
                    // back once a certificate is pinned: that would let an
                    // attacker downgrade the connection.
                    !opened && secure && store.certPin == null ->
                        open(host, secure = false, gen)
                    else -> dropped()
                }
            }
        })
    }

    private fun dropped() {
        socket = null
        _powerState.value = null
        pending.values.forEach { it.completeExceptionally(TvException("Connection lost")) }
        pending.clear()
        subscriptions.clear()
        val current = _conn.value
        if (current !is Conn.Failed) _conn.value = Conn.Failed(Reason.Unreachable)
    }

    private fun register(ws: WebSocket, pin: PinningTrust) {
        val payload = JSONObject(REGISTRATION_PAYLOAD)
        store.clientKey?.let { payload.put("client-key", it) }
        ws.send(
            JSONObject()
                .put("type", "register")
                .put("id", REGISTER_ID)
                .put("payload", payload)
                .toString()
        )
    }

    private fun handle(ws: WebSocket, msg: JSONObject, pin: PinningTrust) {
        val id = msg.optString("id")
        val type = msg.optString("type")
        val payload = msg.optJSONObject("payload") ?: JSONObject()

        if (id == REGISTER_ID) {
            when (type) {
                "response" -> if (payload.optString("pairingType") == "PROMPT") {
                    _conn.value = Conn.AwaitingPairing
                }
                "registered" -> {
                    store.clientKey = payload.optString("client-key")
                    pin.seen?.let { if (store.certPin == null) store.certPin = it }
                    _conn.value = Conn.Connected
                    subscribeState(ws)
                }
                "error" -> {
                    _conn.value = Conn.Failed(Reason.PairingDeclined, msg.optString("error"))
                    session++
                    ws.close(1000, null)
                }
            }
            return
        }

        subscriptions[id]?.let { callback ->
            if (type == "response") callback(payload)
            return
        }
        pending.remove(id)?.complete(msg)
    }

    // endregion

    // region state

    private fun subscribeState(ws: WebSocket) {
        subscribe(ws, "audio/getVolume") { p -> parseVolume(p)?.let { _volume.value = it } }
        subscribe(ws, "com.webos.applicationManager/getForegroundAppInfo") { p ->
            if (p.has("appId")) _foregroundApp.value = p.optString("appId")
        }
        subscribe(ws, "tv/getExternalInputList") { p ->
            p.optJSONArray("devices")?.let { _inputs.value = parseInputs(it) }
        }
        subscribe(ws, "com.webos.service.tvpower/power/getPowerState") { p ->
            if (p.has("state")) _powerState.value = p.optString("state")
        }
        // Save the TV's network card addresses: a TV that is fully off can
        // only be woken by a Wake-on-LAN packet sent to one of them.
        subscribe(ws, "com.webos.service.connectionmanager/getinfo", oneShot = true) { p ->
            val macs = listOf("wiredInfo", "wifiInfo").mapNotNull { key ->
                p.optJSONObject(key)?.optString("macAddress")?.takeIf { it.count { c -> c == ':' } == 5 }
            }
            if (macs.isNotEmpty()) store.macs = macs
        }
    }

    private fun subscribe(
        ws: WebSocket,
        uri: String,
        oneShot: Boolean = false,
        callback: (JSONObject) -> Unit,
    ) {
        val id = "sub_${ids.getAndIncrement()}"
        subscriptions[id] = callback
        val type = if (oneShot) "request" else "subscribe"
        ws.send(JSONObject().put("type", type).put("id", id).put("uri", "ssap://$uri").toString())
    }

    private fun parseVolume(p: JSONObject): Int? {
        p.optJSONObject("volumeStatus")?.let { if (it.has("volume")) return it.optInt("volume") }
        return if (p.has("volume")) p.optInt("volume") else null
    }

    private fun parseInputs(devices: JSONArray): List<ExternalInput> =
        (0 until devices.length()).mapNotNull { i ->
            val d = devices.optJSONObject(i) ?: return@mapNotNull null
            val id = d.optString("id").ifBlank { return@mapNotNull null }
            ExternalInput(
                id = id,
                label = d.optString("label").ifBlank { portName(id) },
                appId = d.optString("appId"),
                connected = d.optBoolean("connected", true),
                icon = d.optString("icon"),
            )
        }

    // endregion

    // region commands

    /** Turn off. Sent without waiting: the TV often shuts down before it replies. */
    fun powerOff(): Boolean {
        val ws = socket ?: return false
        if (_conn.value != Conn.Connected) return false
        val msg = JSONObject().put("type", "request").put("id", "req_${ids.getAndIncrement()}")
            .put("uri", "ssap://system/turnOff")
        return ws.send(msg.toString())
    }

    /**
     * Wake a TV that is still connected: picture switched off, or in the
     * network standby that Quick Start+ keeps alive. Fully off needs Wake-on-LAN.
     */
    suspend fun powerOnConnected() {
        if (_powerState.value == "Screen Off") request("com.webos.service.tvpower/power/turnOnScreen")
        else request("system/turnOn")
    }

    suspend fun volumeUp() = request("audio/volumeUp")
    suspend fun volumeDown() = request("audio/volumeDown")

    suspend fun switchTo(choice: Choice) {
        if (choice.inputId == LIVE_TV_ID) {
            request("system.launcher/launch", JSONObject().put("id", LIVE_TV_APP))
        } else {
            request("tv/switchInput", JSONObject().put("inputId", choice.inputId))
        }
    }

    suspend fun refreshInputs() {
        val p = request("tv/getExternalInputList")
        p.optJSONArray("devices")?.let { _inputs.value = parseInputs(it) }
    }

    private suspend fun request(uri: String, payload: JSONObject? = null): JSONObject {
        val ws = socket
        if (ws == null || _conn.value != Conn.Connected) throw TvException("Not connected to the TV")
        val id = "req_${ids.getAndIncrement()}"
        val result = CompletableDeferred<JSONObject>()
        pending[id] = result
        val msg = JSONObject().put("type", "request").put("id", id).put("uri", "ssap://$uri")
        payload?.let { msg.put("payload", it) }
        if (!ws.send(msg.toString())) {
            pending.remove(id)
            throw TvException("Not connected to the TV")
        }
        val res = withTimeoutOrNull(5_000) { result.await() } ?: run {
            pending.remove(id)
            throw TvException("The TV did not respond")
        }
        if (res.optString("type") == "error") throw TvException(res.optString("error"))
        val body = res.optJSONObject("payload") ?: JSONObject()
        if (body.has("returnValue") && !body.optBoolean("returnValue")) {
            throw TvException(body.optString("errorText", "The TV refused that"))
        }
        return body
    }

    // endregion

    companion object {
        private const val REGISTER_ID = "register_0"

        /** Same manifest aiowebostv sends; the TV shows a one-time Accept prompt for it. */
        private val REGISTRATION_PAYLOAD = """
            {
              "forcePairing": false,
              "pairingType": "PROMPT",
              "manifest": {
                "manifestVersion": 1,
                "appVersion": "1.1",
                "permissions": [
                  "APP_TO_APP", "CLOSE", "CONTROL_AUDIO", "CONTROL_DISPLAY",
                  "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_PLAYBACK",
                  "CONTROL_INPUT_MEDIA_RECORDING", "CONTROL_INPUT_TEXT",
                  "CONTROL_INPUT_TV", "CONTROL_MOUSE_AND_KEYBOARD", "CONTROL_POWER",
                  "CONTROL_TV_SCREEN", "LAUNCH", "LAUNCH_WEBAPP", "READ_APP_STATUS",
                  "READ_COUNTRY_INFO", "READ_CURRENT_CHANNEL", "READ_INPUT_DEVICE_LIST",
                  "READ_INSTALLED_APPS", "READ_LGE_SDX", "READ_LGE_TV_INPUT_EVENTS",
                  "READ_NETWORK_STATE", "READ_NOTIFICATIONS", "READ_POWER_STATE",
                  "READ_RUNNING_APPS", "READ_SETTINGS", "READ_TV_CHANNEL_LIST",
                  "READ_TV_CURRENT_TIME", "READ_UPDATE_INFO", "SEARCH", "TEST_OPEN",
                  "TEST_PROTECTED", "TEST_SECURE", "UPDATE_FROM_REMOTE_APP",
                  "WRITE_NOTIFICATION_ALERT", "WRITE_NOTIFICATION_TOAST", "WRITE_SETTINGS"
                ]
              }
            }
        """.trimIndent()
    }
}

internal class PinMismatch : CertificateException("TV certificate changed")

/**
 * Trust-on-first-use. The TV presents a self-signed certificate, so the
 * fingerprint seen at first pairing is saved and every later connection must
 * present the same one. This stops another device on the network posing as
 * the TV once pairing is done.
 */
internal class PinningTrust(private val pinned: String?) : X509TrustManager {
    @Volatile var seen: String? = null

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("No certificate")
        val fp = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
            .joinToString("") { "%02x".format(it) }
        if (pinned != null && pinned != fp) throw PinMismatch()
        seen = fp
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        throw CertificateException("Not used")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
    var t: Throwable? = this
    while (t != null) {
        if (t is T) return true
        t = t.cause
    }
    return false
}
