package com.gandalondon.remote.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.sp
import com.gandalondon.remote.R

// Palette: white paper, near-black ink (never pure black), one quiet grey.
val Ink = Color(0xFF1A1A1A)
val InkPressed = Color(0xFF3D3D3D)
val Paper = Color(0xFFFFFFFF)
val Fog = Color(0xFFF1F1F1)
val FogPressed = Color(0xFFE2E2E2)
val Muted = Color(0xFF6B6B6B)
val Line = Color(0xFFE2E2E2)
val Live = Color(0xFF188038)
val Alert = Color(0xFFC5221F)

// Three sizes only: one hero, one for controls, one for everything else.
// Geist (SIL Open Font License), bundled so the app looks the same on every phone.
val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
)

object Type {
    val Hero = TextStyle(
        fontFamily = Geist,
        fontSize = 56.sp,
        lineHeight = 64.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp, // a touch looser than Material's negative display tracking
        lineBreak = LineBreak.Heading, // balanced lines, no widows
        color = Ink,
    )
    val Control = TextStyle(
        fontFamily = Geist,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
        color = Ink,
    )
    val Body = TextStyle(
        fontFamily = Geist,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
        lineBreak = LineBreak.Paragraph, // avoids a lone last word
        color = Ink,
    )
}

@Composable
fun RemoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        typography = Typography().let { t ->
            Typography(
                displayLarge = t.displayLarge.copy(fontFamily = Geist), displayMedium = t.displayMedium.copy(fontFamily = Geist), displaySmall = t.displaySmall.copy(fontFamily = Geist),
                headlineLarge = t.headlineLarge.copy(fontFamily = Geist), headlineMedium = t.headlineMedium.copy(fontFamily = Geist), headlineSmall = t.headlineSmall.copy(fontFamily = Geist),
                titleLarge = t.titleLarge.copy(fontFamily = Geist), titleMedium = t.titleMedium.copy(fontFamily = Geist), titleSmall = t.titleSmall.copy(fontFamily = Geist),
                bodyLarge = t.bodyLarge.copy(fontFamily = Geist), bodyMedium = t.bodyMedium.copy(fontFamily = Geist), bodySmall = t.bodySmall.copy(fontFamily = Geist),
                labelLarge = t.labelLarge.copy(fontFamily = Geist), labelMedium = t.labelMedium.copy(fontFamily = Geist), labelSmall = t.labelSmall.copy(fontFamily = Geist),
            )
        },
        colorScheme = lightColorScheme(
            primary = Ink,
            onPrimary = Paper,
            background = Paper,
            onBackground = Ink,
            surface = Paper,
            onSurface = Ink,
            surfaceVariant = Fog,
            onSurfaceVariant = Muted,
        ),
        content = content,
    )
}
