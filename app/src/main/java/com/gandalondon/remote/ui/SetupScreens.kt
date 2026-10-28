package com.gandalondon.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gandalondon.remote.tv.FoundTv

@Composable
fun SetupScreen(
    found: List<FoundTv>,
    searching: Boolean,
    message: String?,
    onSearch: () -> Unit,
    onPick: (host: String, name: String?) -> Unit,
) {
    var ip by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(80.dp))
        Text("Find your TV", style = Type.Hero)
        Spacer(Modifier.height(16.dp))
        Text(
            "On the TV, turn on LG Connect Apps in network settings. Keep this phone on the same Wi-Fi.",
            style = Type.Body.copy(color = Muted),
        )

        message?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, style = Type.Body.copy(color = Alert))
        }

        Spacer(Modifier.height(32.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            found.forEach { tv ->
                FlatButton(onClick = { onPick(tv.host, tv.name) }, modifier = Modifier.fillMaxWidth().height(72.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(tv.name, style = Type.Control.copy(color = Paper), maxLines = 1)
                        Text(tv.host, style = Type.Body.copy(color = Color(0xFFBDBDBD)))
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (searching) {
                CircularProgressIndicator(color = Ink, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(16.dp))
                Text("Searching the Wi-Fi…", style = Type.Body.copy(color = Muted))
            } else {
                Text(
                    if (found.isEmpty()) "No TV found yet." else "Not listed?",
                    style = Type.Body.copy(color = Muted),
                    modifier = Modifier.weight(1f),
                )
                TextAction("Search again", onSearch)
            }
        }

        Spacer(Modifier.height(32.dp))

        // Fallback: the TV's IP is under Settings > Network > Wi-Fi > Advanced.
        TextField(
            value = ip,
            onValueChange = { ip = it.filter { c -> c.isDigit() || c == '.' } },
            label = { Text("TV IP address") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (ip.isValidIp()) onPick(ip, null) }),
            shape = RoundedCornerShape(16.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Fog,
                unfocusedContainerColor = Fog,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Ink,
                focusedLabelColor = Muted,
                unfocusedLabelColor = Muted,
            ),
            textStyle = Type.Body,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        FlatButton(
            onClick = { if (ip.isValidIp()) onPick(ip, null) },
            modifier = Modifier.fillMaxWidth().height(64.dp),
            container = if (ip.isValidIp()) Ink else Fog,
            pressedContainer = if (ip.isValidIp()) InkPressed else FogPressed,
            arrangement = Arrangement.Center,
        ) {
            Text(
                "Connect",
                style = Type.Control.copy(color = if (ip.isValidIp()) Paper else Muted),
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}

private fun String.isValidIp(): Boolean {
    val parts = split('.')
    return parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
}

@Composable
fun PairingScreen(onCancel: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(80.dp))
        Text("Check your TV", style = Type.Hero)
        Spacer(Modifier.height(16.dp))
        Text(
            "Choose Accept on the TV with your LG remote. You only need to do this once.",
            style = Type.Body.copy(color = Muted),
        )
        Spacer(Modifier.weight(1f))
        FlatButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            container = Fog,
            pressedContainer = FogPressed,
            arrangement = Arrangement.Center,
        ) {
            Text("Cancel", style = Type.Control)
        }
        Spacer(Modifier.height(24.dp))
    }
}
