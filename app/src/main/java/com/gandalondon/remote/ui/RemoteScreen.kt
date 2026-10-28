package com.gandalondon.remote.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gandalondon.remote.Notice
import com.gandalondon.remote.NoticeAction
import com.gandalondon.remote.Power
import com.gandalondon.remote.tv.Choice
import com.gandalondon.remote.tv.Conn
import com.gandalondon.remote.tv.Reason
import com.gandalondon.remote.tv.Target

data class RemoteUi(
    val conn: Conn,
    val current: Target?,            // input on screen now, if it is one of ours
    val choices: Map<Target, Choice?>,
    val switching: Target?,
    val notice: Notice?,
    val power: Power,
)

/**
 * Power, three input tiles and one volume rocker. Nothing else on screen.
 * Settings open from a long-press on any input tile.
 */
@Composable
fun RemoteScreen(
    ui: RemoteUi,
    onSelect: (Target) -> Unit,
    onVolumeUp: () -> Unit,
    onVolumeDown: () -> Unit,
    onRetry: () -> Unit,
    onNoticeAction: (NoticeAction) -> Unit,
    onSettings: () -> Unit,
    onPower: () -> Unit,
) {
    // While the TV is off, the other controls dim so its state reads at a glance.
    val tvOff = ui.power == Power.Off || ui.power == Power.TurningOn
    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .systemBarsPadding()
            .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 24.dp),
    ) {
        // Power sits above the first input, on the same three-column grid.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PowerButton(ui.power, onPower, Modifier.weight(1f))
            Spacer(Modifier.weight(2f))
        }
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Target.entries.forEach { t ->
                InputTile(
                    target = t,
                    selected = ui.current == t && ui.switching == null,
                    busy = ui.switching == t,
                    missing = tvOff || ui.choices[t]?.connected == false,
                    onClick = { onSelect(t) },
                    onLongClick = onSettings,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Only appears when something needs attention.
        val message: Notice? = ui.notice ?: when {
            ui.conn is Conn.Failed && ui.conn.reason == Reason.CertificateChanged -> Notice(
                "The TV needs pairing again, usually after a TV update.",
                NoticeAction.OpenSettings,
            )
            // Off with a way to wake it: the power button already says so.
            ui.conn is Conn.Failed && ui.power != Power.Unknown -> null
            ui.conn is Conn.Failed -> Notice("TV off or not found.", NoticeAction.Retry(onRetry))
            else -> null
        }
        Spacer(Modifier.height(if (message != null) 16.dp else 32.dp))
        message?.let {
            NoticeBar(it, onNoticeAction)
            Spacer(Modifier.height(16.dp))
        }

        // Volume rocker: one grey block, split into up and down.
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp)),
        ) {
            RepeatButton(
                onStep = onVolumeUp,
                description = "Volume up",
                modifier = Modifier.weight(1f).fillMaxWidth(),
                container = Fog,
                pressedContainer = FogPressed,
            ) { Glyph(plus = true, color = if (tvOff) Muted else Ink) }
            RepeatButton(
                onStep = onVolumeDown,
                description = "Volume down",
                modifier = Modifier.weight(1f).fillMaxWidth(),
                container = Fog,
                pressedContainer = FogPressed,
            ) { Glyph(plus = false, color = if (tvOff) Muted else Ink) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InputTile(
    target: Target,
    selected: Boolean,
    busy: Boolean,
    missing: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier,
) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val solid = selected || busy
    val bg = when {
        solid && pressed -> InkPressed
        solid -> Ink
        pressed -> FogPressed
        else -> Fog
    }
    val fg = when {
        solid -> Paper
        missing -> Muted
        else -> Ink
    }
    Box(
        modifier
            .height(112.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(bg)
            .semantics { this.selected = selected }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = "Switch to ${target.label}",
                onLongClickLabel = "Settings",
                onLongClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onLongClick()
                },
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(color = Paper, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
        } else {
            Icon(iconFor(target), contentDescription = target.label, tint = fg, modifier = Modifier.size(40.dp))
        }
    }
}

/** On: solid ink, like the selected input. Off: grey. In between: a spinner. */
@Composable
private fun PowerButton(power: Power, onClick: () -> Unit, modifier: Modifier) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val solid = power == Power.On || power == Power.TurningOff
    val bg = when {
        solid && pressed -> InkPressed
        solid -> Ink
        pressed -> FogPressed
        else -> Fog
    }
    val fg = if (solid) Paper else Ink
    val state = when (power) {
        Power.On -> "TV on"
        Power.Off -> "TV off"
        Power.TurningOn -> "Turning on"
        Power.TurningOff -> "Turning off"
        Power.Unknown -> "TV state unknown"
    }
    Box(
        modifier
            .height(64.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(bg)
            .semantics {
                contentDescription = "Power"
                stateDescription = state
                liveRegion = LiveRegionMode.Polite
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = if (power == Power.On) "Turn TV off" else "Turn TV on",
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (power == Power.TurningOn || power == Power.TurningOff) {
            CircularProgressIndicator(color = fg, strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
        } else {
            Icon(PowerIcon, contentDescription = null, tint = fg, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun NoticeBar(notice: Notice, onAction: (NoticeAction) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(Fog, RoundedCornerShape(16.dp))
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(notice.text, style = Type.Body, modifier = Modifier.weight(1f).padding(vertical = 4.dp))
        notice.action?.let { action -> TextAction(action.label, { onAction(action) }) }
    }
}

/** Bold plus / minus drawn as flat bars. */
@Composable
private fun Glyph(plus: Boolean, color: Color = Ink) {
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 40.dp, height = 6.dp).background(color, RoundedCornerShape(3.dp)))
        if (plus) Box(Modifier.size(width = 6.dp, height = 40.dp).background(color, RoundedCornerShape(3.dp)))
    }
}

// Line icons in the Material style, 24-unit grid, 2-unit stroke.
private fun lineIcon(name: String, vararg paths: String, join: StrokeJoin = StrokeJoin.Round): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach {
            addPath(
                pathData = addPathNodes(it),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineJoin = join,
            )
        }
    }.build()

private val TvIcon = lineIcon(
    "tv",
    "M5,5h14a2,2 0 0 1 2,2v10a2,2 0 0 1 -2,2h-14a2,2 0 0 1 -2,-2v-10a2,2 0 0 1 2,-2z",
)

private val CastIcon = lineIcon(
    "cast",
    "M3,9V7a2,2 0 0 1 2,-2h14a2,2 0 0 1 2,2v10a2,2 0 0 1 -2,2h-6",
    "M3,13a6,6 0 0 1 6,6",
    "M3,17a2,2 0 0 1 2,2",
)

private val GamepadIcon = lineIcon(
    "gamepad",
    "M10,3h4v4l-2,2l-2,-2z",
    "M10,21h4v-4l-2,-2l-2,2z",
    "M3,10h4l2,2l-2,2H3z",
    "M21,10h-4l-2,2l2,2h4z",
    join = StrokeJoin.Miter,
)

private fun iconFor(t: Target) = when (t) {
    Target.LIVE_TV -> TvIcon
    Target.CAST -> CastIcon
    Target.PS5 -> GamepadIcon
}

// Material "power_settings_new", filled, 24-unit grid.
private val PowerIcon = ImageVector.Builder("power", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(
        pathData = addPathNodes(
            "M13,3h-2v10h2V3z" +
                "M17.83,5.17l-1.42,1.42C17.99,7.86 19,9.81 19,12c0,3.87 -3.13,7 -7,7s-7,-3.13 -7,-7" +
                "c0,-2.19 1.01,-4.14 2.58,-5.42L6.17,5.17C4.23,6.82 3,9.26 3,12c0,4.97 4.03,9 9,9" +
                "s9,-4.03 9,-9c0,-2.74 -1.23,-5.18 -3.17,-6.83z"
        ),
        fill = SolidColor(Color.Black),
        pathFillType = PathFillType.NonZero,
    )
}.build()
