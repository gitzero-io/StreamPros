package com.example.hub

import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.darkColorScheme

// Every color the UI uses. Named HubColors so Surface/Text don't clash with the composables.
// Roles: GlowRed = focus only. GlowBlue = primary actions and progress. GlowGreen = success.
// GlowPurple = brand/discovery. Warning = failed installs only (never red).
object HubColors {
    val Black = Color(0xFF030305)
    val Surface = Color(0xFF0B0B10)
    val SurfaceRaised = Color(0xFF14141C)
    val Line = Color(0xFF232330)
    val GlowRed = Color(0xFFFF2A3D)
    val GlowBlue = Color(0xFF2F9BFF)
    val GlowGreen = Color(0xFF2BF58A)
    val GlowPurple = Color(0xFFB15CFF)
    val Text = Color(0xFFF5F5F7)
    val Muted = Color(0xFF8A8A9A)
    val Warning = Color(0xFFFFB020)
}

// Resting tint for each category's cards.
val Category.tint: Color
    get() = when (this) {
        Category.MOVIES -> HubColors.GlowBlue
        Category.SHOWS -> HubColors.GlowPurple
        Category.SPORTS -> HubColors.GlowGreen
        Category.LIVE_TV -> HubColors.GlowBlue // not GlowRed: red is focus only
        Category.ANIME -> HubColors.GlowPurple
        Category.LATIN -> HubColors.GlowPurple
        Category.PLAYER -> HubColors.GlowBlue
        Category.MEDIA_CLIENT -> HubColors.GlowPurple
        Category.IPTV_PLAYER -> HubColors.GlowBlue
    }

// Dark tv-material theme built from HubColors. HubButton sets its own colors; this covers
// anything else that reads the theme (default content color, dialogs, outlined buttons).
@OptIn(ExperimentalTvMaterial3Api::class)
val HubColorScheme = darkColorScheme(
    primary = HubColors.GlowBlue,
    onPrimary = HubColors.Black,
    secondary = HubColors.GlowPurple,
    onSecondary = HubColors.Black,
    tertiary = HubColors.GlowGreen,
    onTertiary = HubColors.Black,
    background = HubColors.Black,
    onBackground = HubColors.Text,
    surface = HubColors.Surface,
    onSurface = HubColors.Text,
    surfaceVariant = HubColors.SurfaceRaised,
    onSurfaceVariant = HubColors.Muted,
    inverseSurface = HubColors.Text,
    inverseOnSurface = HubColors.Black,
    error = HubColors.Warning,
    onError = HubColors.Black,
    border = HubColors.Line,
    borderVariant = HubColors.Line
)
