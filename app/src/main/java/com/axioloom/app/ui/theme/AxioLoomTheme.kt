package com.axioloom.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Umbra UI Design System Palette Tokens
 * Precise, architectural Swiss-grid theme with flat graphite surfaces and surgical amber/green accents.
 */
object UmbraColors {
    // Recessed & Surface Neutrals
    val BgRecessed = Color(0xFF0B0C0D)       // Deepest canvas well & plot viewports
    val BgCanvas = Color(0xFF0E0F11)         // Primary workspace background
    val BgSurface = Color(0xFF101113)        // Standard chrome, header bars, idle tiles
    val BgElevated = Color(0xFF141619)       // Modals, dropdown menus, hover states
    val BgActiveAmber = Color(0xFF1C1815)    // Amber-washed surface for latched tool tiles
    val BgActiveGreen = Color(0xFF111A16)    // Oxidized-green wash for telemetry containers

    // Structural Hairline Borders
    val BorderHairline = Color(0xFF202123)   // 1px full-bleed dividers and header rules
    val BorderSubtle = Color(0xFF2B2C30)     // Idle control borders, input frames, tile outlines
    val BorderFocus = Color(0xFF3E4046)      // Hover and keyboard focus borders
    val BorderAmber = Color(0xFF53493E)      // Outer border for active/latched controls

    // Typography Tokens
    val TextPrimary = Color(0xFFEAEAEA)      // Primary headings, active tab labels, selected text
    val TextSecondary = Color(0xFFA5A7AC)    // Idle button labels, inactive tabs, body copy
    val TextMuted = Color(0xFF6E7075)        // Monospace taxonomy paths, empty hints, watermarks

    // Surgical Semantic Accents
    val AccentAmber = Color(0xFFF5B459)      // Primary active indicator: latch bars, tab underlines
    val AccentAmberDim = Color(0xFFC49758)   // Secondary amber stroke or badge outline
    val AccentGreen = Color(0xFF3C9570)      // Oxidized green for telemetry dots and nominal status
    val AccentInk = Color(0xFF2F3136)        // Neutral ink-grey for secondary badges and axes
    val AccentDanger = Color(0xFFD4515C)     // Restrained oxidized crimson for diagnostic errors

    // Canvas Grid
    val GridDot = Color(0xFF2A2B2D)          // Micro-dots inside technical canvas
    val GridCrosshair = Color(0xFF525458)    // Corner registration L-brackets and crosshairs
}

private val UmbraDarkColorScheme = darkColorScheme(
    primary = UmbraColors.AccentAmber,
    onPrimary = Color(0xFF1C1815),
    secondary = UmbraColors.TextSecondary,
    onSecondary = UmbraColors.BgSurface,
    background = UmbraColors.BgCanvas,
    onBackground = UmbraColors.TextPrimary,
    surface = UmbraColors.BgSurface,
    onSurface = UmbraColors.TextPrimary,
    surfaceVariant = UmbraColors.BgElevated,
    onSurfaceVariant = UmbraColors.TextSecondary,
    error = UmbraColors.AccentDanger,
    onError = Color(0xFFEAEAEA),
    outline = UmbraColors.BorderSubtle
)

@Composable
fun AxioLoomTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = UmbraDarkColorScheme,
        content = content
    )
}
