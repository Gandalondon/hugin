package com.gandalondon.remote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.gandalondon.remote.tv.Choice
import com.gandalondon.remote.tv.ExternalInput
import com.gandalondon.remote.tv.LiveTvInput
import com.gandalondon.remote.tv.Target
import com.gandalondon.remote.tv.portName

@Composable
fun SettingsScreen(
    tvName: String,
    host: String,
    inputs: List<ExternalInput>?,
    choices: Map<Target, Choice?>,
    overrides: Map<Target, String>,
    onChoose: (Target, String?) -> Unit,
    onPairAgain: () -> Unit,
    onForget: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    var open by remember { mutableStateOf<Target?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            TextAction("Done", onClose)
        }
        Spacer(Modifier.height(16.dp))
        Text("Settings", style = Type.Hero)

        Spacer(Modifier.height(32.dp))
        Text("Inputs", style = Type.Control)
        Spacer(Modifier.height(8.dp))
        Text(
            "Matched from the names your TV gives its inputs. Tap one to change it.",
            style = Type.Body.copy(color = Muted),
        )
        Spacer(Modifier.height(16.dp))

        Target.entries.forEach { t ->
            val choice = choices[t]
            val value = when {
                choice == null -> "Not set"
                choice.automatic -> "${describe(choice)} · auto"
                else -> describe(choice)
            }
            HorizontalDivider(color = Line)
            KeyValueRow(t.label, value, expanded = open == t) { open = if (open == t) null else t }
            if (open == t) {
                val options = buildList {
                    if (t == Target.LIVE_TV) add(LiveTvInput)
                    inputs?.let { addAll(it) }
                }
                Column(Modifier.padding(bottom = 8.dp)) {
                    OptionRow("Automatic", selected = overrides[t] == null) {
                        onChoose(t, null); open = null
                    }
                    options.forEach { input ->
                        OptionRow(
                            label = optionLabel(input),
                            selected = overrides[t] == input.id,
                        ) { onChoose(t, input.id); open = null }
                    }
                    if (inputs == null) {
                        Text(
                            "Connect to the TV to see its inputs.",
                            style = Type.Body.copy(color = Muted),
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = Line)

        Spacer(Modifier.height(48.dp))
        Text("TV", style = Type.Control)
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = Line)
        KeyValueRow("Name", tvName)
        HorizontalDivider(color = Line)
        KeyValueRow("Address", host)
        HorizontalDivider(color = Line)

        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlatButton(
                onClick = onPairAgain,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                container = Fog,
                pressedContainer = FogPressed,
                arrangement = Arrangement.Center,
            ) { Text("Pair again", style = Type.Control) }
            FlatButton(
                onClick = onForget,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                container = Fog,
                pressedContainer = FogPressed,
                arrangement = Arrangement.Center,
            ) { Text("Forget this TV", style = Type.Control.copy(color = Alert)) }
        }
        Spacer(Modifier.height(32.dp))
    }
}

private fun describe(choice: Choice): String {
    val port = portName(choice.inputId)
    return if (choice.inputId == com.gandalondon.remote.tv.LIVE_TV_ID) "Tuner"
    else if (choice.tvLabel.equals(port, ignoreCase = true)) port
    else "$port · ${choice.tvLabel}"
}

private fun optionLabel(input: ExternalInput): String {
    if (input.id == com.gandalondon.remote.tv.LIVE_TV_ID) return input.label
    val port = portName(input.id)
    val name = if (input.label.equals(port, ignoreCase = true)) port else "$port · ${input.label}"
    return if (input.connected) name else "$name (nothing connected)"
}

@Composable
private fun KeyValueRow(key: String, value: String, expanded: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, style = Type.Body, modifier = Modifier.weight(1f))
        Text(value, style = Type.Body.copy(color = if (expanded) Ink else Muted))
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Type.Body, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = Ink, modifier = Modifier.size(24.dp))
    }
}
