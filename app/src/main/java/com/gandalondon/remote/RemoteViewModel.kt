package com.gandalondon.remote

import android.app.Application
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
                            _host.value?.let { client.connect(it) }
                        }
                    }
                }
            }
        }
    }

    fun onStart() {
        visible = true
        _host.value?.let { client.connect(it) }
    }

    fun onStop() {
        visible = false
        retryJob?.cancel()
        client.disconnect()
    }

    fun retryNow() {
        _host.value?.let { client.connect(it) }
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
        store.host = host.trim()
        store.tvName = name
        _host.value = store.host
        _tvName.value = name
        store.clearPairing()
        client.connect(store.host!!)
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

    fun volumeUp() = command { client.volumeUp() }
    fun volumeDown() = command { client.volumeDown() }

    private fun command(block: suspend () -> Unit) {
        if (conn.value != Conn.Connected) {
            show(Notice("Not connected to the TV yet.", NoticeAction.Retry(::retryNow)))
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
            show(Notice("Not connected to the TV yet.", NoticeAction.Retry(::retryNow)))
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
        client.disconnect()
    }
}
