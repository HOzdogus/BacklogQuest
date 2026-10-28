package com.backlogquest.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/** The handheld-console palette from the web version. */
object BQ {
    val Shell = Color(0xFF1D1A2E)
    val Shell2 = Color(0xFF29253F)
    val Edge = Color(0xFF3A3456)
    val Lcd = Color(0xFFC7D19C)
    val LcdInk = Color(0xFF262A17)
    val LcdDim = Color(0xFF6C7344)
    val Ink = Color(0xFFECE8F6)
    val Muted = Color(0xFFA49DBD)
    val A = Color(0xFFE4584D)
    val AShadow = Color(0xFF9C3129)
    val B = Color(0xFFF2B544)
    val BShadow = Color(0xFFA87A22)
    val Ok = Color(0xFF7FD1A4)
    val Bronze = Color(0xFFC98A52)
    val Silver = Color(0xFFB8C0CC)
    val Gold = Color(0xFFF2C94C)
    val CoolBg = Color(0xFF4A2A35)
    val CoolFg = Color(0xFFFFB3AA)
    val OkBg = Color(0xFF1F3B31)
    val OwnBg = Color(0xFF3D3420)

    /** Blocky font for the LCD screen and labels. */
    val Mono = FontFamily.Monospace
}

@Composable
fun BacklogTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = BQ.Shell,
            surface = BQ.Shell2,
            surfaceVariant = BQ.Shell2,
            primary = BQ.A,
            onPrimary = Color.White,
            secondary = BQ.B,
            onSecondary = Color(0xFF2A1D05),
            onBackground = BQ.Ink,
            onSurface = BQ.Ink,
            onSurfaceVariant = BQ.Muted,
            outline = BQ.Edge,
        ),
        content = content,
    )
}
