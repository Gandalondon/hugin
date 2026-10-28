package com.gandalondon.remote

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gandalondon.remote.tv.Conn
import com.gandalondon.remote.tv.Reason
import com.gandalondon.remote.tv.Target
import com.gandalondon.remote.tv.resolve
import com.gandalondon.remote.ui.PairingScreen
import com.gandalondon.remote.ui.RemoteScreen
import com.gandalondon.remote.ui.RemoteTheme
import com.gandalondon.remote.ui.RemoteUi
import com.gandalondon.remote.ui.SettingsScreen
import com.gandalondon.remote.ui.SetupScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent { RemoteTheme { App() } }
    }
}

@Composable
private fun App(vm: RemoteViewModel = viewModel()) {
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.onStart() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onStop() }

    val conn by vm.conn.collectAsStateWithLifecycle()
    val host by vm.host.collectAsStateWithLifecycle()
    val tvName by vm.tvName.collectAsStateWithLifecycle()
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val overrides by vm.overrides.collectAsStateWithLifecycle()
    val foreground by vm.foregroundApp.collectAsStateWithLifecycle()
    val switching by vm.switching.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val found by vm.found.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()

    var showSettings by rememberSaveable { mutableStateOf(false) }

    val currentHost = host
    val failure = conn as? Conn.Failed
    // Setup only appears until the TV accepts this phone; after that the remote opens straight away.
    val needsSetup = currentHost == null || failure?.reason == Reason.PairingDeclined

    when {
        needsSetup -> {
            LaunchedEffect(Unit) { vm.discover() }
            SetupScreen(
                found = found,
                searching = searching,
                message = if (failure?.reason == Reason.PairingDeclined)
                    "The TV did not accept the pairing. Try again and choose Accept." else null,
                onSearch = vm::discover,
                onPick = vm::pair,
            )
        }

        conn == Conn.AwaitingPairing -> PairingScreen(onCancel = vm::forgetTv)

        showSettings -> {
            val choices = Target.entries.associateWith { resolve(it, inputs, overrides[it]) }
            SettingsScreen(
                tvName = tvName ?: "LG TV",
                host = currentHost!!,
                inputs = inputs,
                choices = choices,
                overrides = overrides,
                onChoose = vm::setOverride,
                onPairAgain = { showSettings = false; vm.pairAgain() },
                onForget = { showSettings = false; vm.forgetTv() },
                onClose = { showSettings = false },
            )
        }

        else -> {
            val choices = Target.entries.associateWith { resolve(it, inputs, overrides[it]) }
            val current = Target.entries.firstOrNull { t ->
                val c = choices[t]
                c != null && foreground != null && c.appId == foreground
            }
            RemoteScreen(
                ui = RemoteUi(
                    conn = conn,
                    current = current,
                    choices = choices,
                    switching = switching,
                    notice = notice,
                ),
                onSelect = vm::select,
                onVolumeUp = vm::volumeUp,
                onVolumeDown = vm::volumeDown,
                onRetry = vm::retryNow,
                onNoticeAction = { action ->
                    when (action) {
                        is NoticeAction.Retry -> { vm.dismissNotice(); action.run() }
                        NoticeAction.OpenSettings -> { vm.dismissNotice(); showSettings = true }
                    }
                },
                onSettings = { showSettings = true },
            )
        }
    }
}
