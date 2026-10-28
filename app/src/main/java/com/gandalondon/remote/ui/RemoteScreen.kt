package com.gandalondon.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.gandalondon.remote.Notice
import com.gandalondon.remote.NoticeAction
import com.gandalondon.remote.tv.Choice
import com.gandalondon.remote.tv.Conn
import com.gandalondon.remote.tv.Target

data class RemoteUi(
    val conn: Conn,
    val tvName: String,
    val current: Target?,            // input on screen now, if it is one of ours
    val choices: Map<Target, Choice?>,
    val switching: Target?,
    val volume: Int?,
    val notice: Notice?,
)

@Composable
fun RemoteScreen(
    ui: RemoteUi,
    onSelect: (Target) -> Unit,
    onVolumeUp: () -> Unit,
    onVolumeDown: () -> Unit,
    onRetry: () -> Unit,
    onNoticeAction: (NoticeAction) -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        // Status + settings, kept small and out of the way.
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusLine(ui.conn, ui.tvName, onRetry, Modifier.weight(1f))
            TextAction("Settings", onSettings)
        }

        Spacer(Modifier.height(16.dp))

        // The hero: what the TV is showing, in one big word or two.
        val connected = ui.conn == Conn.Connected
        val hero = when {
            !connected -> "Remote"
            ui.switching != null -> ui.switching.label
            ui.current != null -> ui.current.label
            else -> "Other"
        }
        val sub = when {
            !connected -> " "
            ui.switching != null -> "Switching…"
            ui.current != null -> ui.choices[ui.current]?.tvLabel?.let { "On $it" } ?: " "
            else -> "Another app is on screen"
        }
        Text(hero, style = Type.Hero, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Text(sub, style = Type.Body.copy(color = Muted))

        Spacer(Modifier.height(32.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Target.entries.forEach { t ->
                InputButton(
                    target = t,
                    choice = ui.choices[t],
                    selected = ui.current == t && ui.switching == null,
                    busy = ui.switching == t,
                    enabled = connected,
                    onClick = { onSelect(t) },
                )
            }
        }

        Spacer(Modifier.weight(1f))

        ui.notice?.let { NoticeBar(it, onNoticeAction) }

        Spacer(Modifier.height(16.dp))

        // Volume lives at the bottom, in easy reach of a thumb.
        RepeatButton(onVolumeUp, "Volume up", Modifier.fillMaxWidth().height(120.dp)) {
            Glyph(plus = true)
        }
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
            Text(
                ui.volume?.let { "Volume $it" } ?: "Volume",
                style = Type.Body.copy(color = Muted),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        RepeatButton(onVolumeDown, "Volume down", Modifier.fillMaxWidth().height(120.dp)) {
            Glyph(plus = false)
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusLine(conn: Conn, tvName: String, onRetry: () -> Unit, modifier: Modifier) {
    val (dot, text) = when (conn) {
        Conn.Connected -> Live to tvName
        Conn.Connecting, Conn.Idle -> Muted to "Connecting…"
        Conn.AwaitingPairing -> Muted to "Waiting for the TV"
        is Conn.Failed -> Alert to when (conn.reason) {
            com.gandalondon.remote.tv.Reason.Unreachable -> "TV off or not found"
            else -> "Needs pairing"
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Spacer(Modifier.size(8.dp))
        Text(text, style = Type.Body, maxLines = 1)
        if (conn is Conn.Failed) {
            Spacer(Modifier.size(8.dp))
            TextAction("Retry", onRetry)
        }
    }
}

@Composable
private fun InputButton(
    target: Target,
    choice: Choice?,
    selected: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // Selected input is solid ink; the others sit back in grey.
    val solid = selected || busy
    FlatButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .semantics { this.selected = selected },
        container = if (solid) Ink else Fog,
        pressedContainer = if (solid) InkPressed else FogPressed,
    ) {
        val fg = if (solid) Paper else if (enabled) Ink else Muted
        Text(target.label, style = Type.Control.copy(color = fg), modifier = Modifier.weight(1f))
        when {
            busy -> CircularProgressIndicator(color = Paper, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            selected -> Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = Paper)
            choice != null && !choice.connected ->
                Text("Not detected", style = Type.Body.copy(color = Muted))
        }
    }
}

@Composable
private fun NoticeBar(notice: Notice, onAction: (NoticeAction) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Fog, RoundedCornerShape(16.dp))
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(notice.text, style = Type.Body, modifier = Modifier.weight(1f).padding(vertical = 4.dp))
        notice.action?.let { action -> TextAction(action.label, { onAction(action) }) }
    }
}

/** Bold plus / minus drawn as flat bars, matching the app icon. */
@Composable
private fun Glyph(plus: Boolean) {
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 40.dp, height = 6.dp).background(Paper, RoundedCornerShape(3.dp)))
        if (plus) Box(Modifier.size(width = 6.dp, height = 40.dp).background(Paper, RoundedCornerShape(3.dp)))
    }
}
