package com.gandalondon.remote.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val Pill = RoundedCornerShape(percent = 50)

/**
 * Flat button: colour change on press instead of a ripple, plus a light tap
 * of haptics so a press is felt without looking.
 */
@Composable
fun FlatButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = Ink,
    pressedContainer: Color = InkPressed,
    shape: RoundedCornerShape = Pill,
    arrangement: Arrangement.Horizontal = Arrangement.Start,
    content: @Composable RowScope.() -> Unit,
) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = modifier
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(if (pressed) pressedContainer else container)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
            .padding(horizontal = 24.dp),
        horizontalArrangement = arrangement,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Volume button: one step per tap, and repeats while held. */
@Composable
fun RepeatButton(
    onStep: () -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val step by rememberUpdatedState(onStep)
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(32.dp))
            .background(if (pressed) InkPressed else Ink)
            .semantics {
                role = Role.Button
                contentDescription = description
                onClick { step(); true }
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    step()
                    val repeat = scope.launch {
                        delay(450)
                        while (true) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            step()
                            delay(160)
                        }
                    }
                    tryAwaitRelease()
                    repeat.cancel()
                    pressed = false
                })
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Small text-only action, e.g. "Settings" or "Retry". No underline. */
@Composable
fun TextAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Ink) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Type.Body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = color))
    }
}
