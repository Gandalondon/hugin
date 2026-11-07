package com.gandalondon.remote

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gandalondon.remote.tv.Choice
import com.gandalondon.remote.tv.Conn
import com.gandalondon.remote.tv.FoundTv
import com.gandalondon.remote.tv.Reason
import com.gandalondon.remote.tv.Store
import com.gandalondon.remote.tv.Target
import com.gandalondon.remote.tv.WebOsClient
import com.gandalondon.remote.tv.discoverTvs
import com.gandalondon.remote.tv.resolve
import com.gandalondon.remote.tv.wakeOnLan
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the power button shows. */
enum class Power { On, Off, Unknown, TurningOn, TurningOff }

/** A short message under the input buttons, optionally with one action. */
data class Notice(val text: String, val action: NoticeAction? = null)

sealed interface NoticeAction {
    val label: String
    data class Retry(val run: () -> Unit) : NoticeAction { override val label = "Retry" }
    data object OpenSettings : NoticeAction { override val label = "Settings" }
}

class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store(app)
    val client = WebOsClient(store)

    val conn: StateFlow<Conn> = client.conn
    val volume = client.volume
    val muted = client.muted
    val foregroundApp = client.foregroundApp
    val inputs = client.inputs

    private val _host = MutableStateFlow(store.host)
    val host: StateFlow<String?> = _host

    private val _tvName = MutableStateFlow(store.tvName)
    val tvName: StateFlow<String?> = _tvName

    private val _overrides = MutableStateFlow(store.overrides())
    val overrides: StateFlow<Map<Target, String>> = _overrides

    private val _switching = MutableStateFlow<Target?>(null)
    val switching: StateFlow<Target?> = _switching

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice

    private val _found = MutableStateFlow<List<FoundTv>>(emptyList())
    val found: StateFlow<List<FoundTv>> = _found

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching

    private val _power = MutableStateFlow(Power.Unknown)
    val power: StateFlow<Power> = _power

    /** Set while a power change is in flight, so the button shows progress. */
    private var pending: Power? = null
    private var powerJob: Job? = null

    private var visible = false
    private var retryJob: Job? = null
    private var noticeJob: Job? = null

    init {
        // Reconnect quietly while the app is open: 1s, 2s, 4s, then every 8s.
        viewModelScope.launch {
            var attempt = 0
            client.conn.collect { state ->
                retryJob?.cancel()
                when {
                    state is Conn.Connected -> { attempt = 0 }
                    state is Conn.Failed && state.reason == Reason.Unreachable && visible -> {
                        val wait = (1000L shl attempt.coerceAtMost(3))
                        attempt++
                        retryJob = launch {
                            delay(wait)
                            connectKnown()
                        }
                    }
                }
            }
        }
        viewModelScope.launch { client.conn.collect { updatePower() } }
        viewModelScope.launch { client.powerState.collect { updatePower() } }
    }

    /** The TV's real state, or null while it is still unknown. */
    private fun observedPower(): Power? {
        val c = client.conn.value
        return when {
            c == Conn.Connected -> when (client.powerState.value) {
                "Active Standby", "Suspend", "Power Off", "Screen Off" -> Power.Off
                else -> Power.On
            }
            // Unreachable and we know how to wake it: treat as off, not lost.
            c is Conn.Failed && c.reason == Reason.Unreachable ->
                if (store.macs.isNotEmpty()) Power.Off else Power.Unknown
            else -> null // connecting: keep showing the last state, no flicker
        }
    }

    private fun updatePower() {
        val seen = observedPower()
        val target = pending
        if (target != null) {
            val done = (target == Power.TurningOn && seen == Power.On) ||
                (target == Power.TurningOff && seen == Power.Off)
            if (!done) { _power.value = target; return }
            pending = null
            powerJob?.cancel()
        }
        _power.value = seen ?: if (_power.value in setOf(Power.TurningOn, Power.TurningOff)) Power.Unknown else _power.value
    }

    fun togglePower() {
        if (pending != null) return
        when (_power.value) {
            Power.On -> turnOff()
            else -> turnOn()
        }
    }

    private fun turnOff() {
        if (!client.powerOff()) {
            show(Notice("Not connected to the TV yet.", NoticeAction.Retry(::retryNow)))
            return
        }
        _notice.value = null
        pending = Power.TurningOff
        updatePower()
        powerJob = viewModelScope.launch {
            delay(15_000)
            if (pending == Power.TurningOff) {
                pending = null
                updatePower()
                show(Notice("The TV did not turn off.", NoticeAction.Retry(::togglePower)))
            }
        }
    }

    private fun turnOn() {
        val macs = store.macs
        val connected = client.conn.value == Conn.Connected
        if (!connected && macs.isEmpty()) {
            show(Notice("Turn the TV on with its own remote once, so Hugin can learn to wake it."))
            return
        }
        _notice.value = null
        pending = Power.TurningOn
        updatePower()
        powerJob = viewModelScope.launch {
            if (connected) runCatching { client.powerOnConnected() }
            // Fully off: wake it, then keep knocking until it answers.
            repeat(12) { i ->
                if (pending != Power.TurningOn) return@launch
                if (client.conn.value != Conn.Connected) {
                    if (i % 3 == 0 && macs.isNotEmpty()) wakeOnLan(macs, store.hosts)
                    if (client.conn.value !is Conn.Connecting) retryNow()
                }
                delay(2_000)
            }
            if (pending == Power.TurningOn) {
                pending = null
                updatePower()
                show(
                    Notice(
                        "The TV did not turn on. Check Turn on via Wi-Fi is on in the TV's settings.",
                        NoticeAction.Retry(::togglePower),
                    )
                )
            }
        }
    }

    fun onStart() {
        visible = true
        connectKnown()
    }

    /**
     * Try every address the TV has answered at. If none work (the phone is
     * on a different Wi-Fi network, or the router gave the TV a new address),
     * search the network for this same TV and connect to it there, keeping
     * the pairing: no Accept prompt, no setup.
     */
    private fun connectKnown() {
        if (store.host == null) return
        client.connect(store.hosts)
    }

    private var lastRediscovery = 0L

    private fun rediscover(force: Boolean = false) {
        if (store.host == null) return
        val now = System.currentTimeMillis()
        if (!force && now - lastRediscovery < 20_000) return
        lastRediscovery = now
        viewModelScope.launch {
            val tvs = runCatching { discoverTvs(getApplication()) }.getOrDefault(emptyList())
            val id = store.tvId
            // Same TV by its own ID; with no ID saved yet, any LG TV here (the
            // pinned certificate still refuses a different TV).
            val mine = tvs.filter { id == null || it.id == null || it.id == id }
            if (mine.isEmpty()) return@launch
            mine.firstOrNull { it.id != null }?.let { if (store.tvId == null) store.tvId = it.id }
            val known = store.hosts
            val fresh = mine.map { it.host }.filter { it !in known }
            if (fresh.isNotEmpty() || client.conn.value != Conn.Connected) {
                store.hosts = mine.map { it.host } + known
                if (client.conn.value != Conn.Connected) connectKnown()
            }
        }
    }

    /** Reconnect as soon as the phone joins another network. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onNetworkChanged()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = Unit
        override fun onLost(network: Network) = Unit
    }

    private fun onNetworkChanged() {
        viewModelScope.launch {
            if (!visible) return@launch
            delay(1_000) // let the new network settle
            connectKnown()
            rediscover(force = true)
        }
    }

    init {
        runCatching {
            getApplication<Application>().getSystemService(ConnectivityManager::class.java)
                .registerDefaultNetworkCallback(networkCallback)
        }
        // Keep the shown address and the TV's ID current once connected.
        viewModelScope.launch {
            client.conn.collect { state ->
                if (state == Conn.Connected) {
                    _host.value = store.host
                    if (store.tvId == null) rediscover(force = true)
                }
                if (state is Conn.Failed && state.reason == Reason.Unreachable && visible) rediscover()
            }
        }
    }

    fun onStop() {
        visible = false
        retryJob?.cancel()
        client.disconnect()
    }

    fun retryNow() {
        connectKnown()
        rediscover(force = true)
    }

    // region setup

    fun discover() {
        if (_searching.value) return
        viewModelScope.launch {
            _searching.value = true
            _found.value = runCatching { discoverTvs(getApplication()) }.getOrDefault(emptyList())
            _searching.value = false
        }
    }

    fun pair(host: String, name: String?) {
        val h = host.trim()
        val id = _found.value.firstOrNull { it.host == h }?.id
        // Picking the same TV again (for example from the other Wi-Fi network)
        // keeps the pairing; only a different TV starts over.
        val sameTv = store.clientKey != null &&
            ((id != null && id == store.tvId) || h in store.hosts || (id != null && store.tvId == null))
        if (!sameTv) {
            store.clearPairing()
            store.hosts = emptyList()
            store.tvId = id
        }
        store.rememberHost(h)
        store.tvName = name ?: store.tvName
        _host.value = h
        _tvName.value = store.tvName
        connectKnown()
    }

    fun pairAgain() {
        store.clearPairing()
        retryNow()
    }

    fun forgetTv() {
        client.disconnect()
        store.forgetTv()
        _host.value = null
        _tvName.value = null
        _overrides.value = emptyMap()
        _notice.value = null
        discover()
    }

    fun setOverride(target: Target, inputId: String?) {
        store.setOverride(target, inputId)
        _overrides.value = store.overrides()
    }

    // endregion

    // region controls

    fun choiceFor(target: Target): Choice? = resolve(target, inputs.value, _overrides.value[target])

    /** While muted, a tap on plus only unmutes, so the volume does not jump. */
    fun volumeUp() = command {
        if (client.muted.value) client.setMute(false) else client.volumeUp()
    }
    fun volumeDown() = command { client.volumeDown() }
    fun mute() = command { client.setMute(true) }

    private fun notConnected() {
        if (_power.value == Power.Off) show(Notice("The TV is off."))
        else show(Notice("Not connected to the TV yet.", NoticeAction.Retry(::retryNow)))
    }

    private fun command(block: suspend () -> Unit) {
        if (conn.value != Conn.Connected) {
            notConnected()
            return
        }
        viewModelScope.launch {
            runCatching { block() }.onFailure {
                show(Notice("The TV did not respond.", NoticeAction.Retry(::retryNow)))
            }
        }
    }

    fun select(target: Target, force: Boolean = false) {
        if (conn.value != Conn.Connected) {
            notConnected()
            return
        }
        val choice = choiceFor(target)
        if (choice == null) {
            show(Notice("Choose which TV input is ${target.label}.", NoticeAction.OpenSettings))
            return
        }
        if (!choice.connected && !force) {
            show(
                Notice(
                    "Nothing is connected on ${choice.tvLabel}. Check ${target.label} is on, then retry.",
                    NoticeAction.Retry { retrySelect(target) },
                )
            )
            return
        }
        _notice.value = null
        viewModelScope.launch {
            _switching.value = target
            val ok = runCatching {
                client.switchTo(choice)
                withTimeoutOrNull(6_000) { foregroundApp.first { it == choice.appId } } != null
            }.getOrDefault(false)
            _switching.value = null
            if (!ok) {
                show(
                    Notice(
                        "The TV did not switch to ${target.label}.",
                        NoticeAction.Retry { retrySelect(target) },
                    )
                )
            }
        }
    }

    /** Re-read the TV's inputs, then switch even if it still reports nothing connected. */
    private fun retrySelect(target: Target) {
        viewModelScope.launch {
            runCatching { client.refreshInputs() }
            select(target, force = true)
        }
    }

    fun dismissNotice() {
        _notice.value = null
    }

    private fun show(notice: Notice) {
        _notice.value = notice
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(8_000)
            if (_notice.value === notice) _notice.value = null
        }
    }

    // endregion

    override fun onCleared() {
        runCatching {
            getApplication<Application>().getSystemService(ConnectivityManager::class.java)
                .unregisterNetworkCallback(networkCallback)
        }
        client.disconnect()
    }
}
