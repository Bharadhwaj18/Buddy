package com.shrey.buddy.desktop

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import java.time.LocalTime

/** Laptop colours: "Calm mist" by day, "Soft dusk" in the evening. */
data class BuddyTheme(
    val dark: Boolean,
    /** Cards, bar, panel. */
    val surface: Color,
    /** Highlighted rows inside a card. */
    val surfaceAlt: Color,
    /** Pill-shaped secondary buttons. */
    val chip: Color,
    val line: Color,
    val divider: Color,
    val text: Color,
    val dim: Color,
    val faint: Color,
    val accent: Color,
    /** Accent used for text (a little stronger than [accent] on light). */
    val accentText: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val late: Color,
    val lateSoft: Color,
    val ok: Color,
    val okSoft: Color,
    val warn: Color,
    val warnSoft: Color,
    /** Main window background. */
    val window: Color,
) {
    val material: ColorScheme
        get() = if (dark) darkColorScheme(primary = accent, onPrimary = onAccent, background = window, surface = surface, onSurface = text)
        else lightColorScheme(primary = accent, onPrimary = onAccent, background = window, surface = surface, onSurface = text)

    companion object {
        val Light = BuddyTheme(
            dark = false,
            surface = Color(0xFFFFFFFF), surfaceAlt = Color(0xFFF4F6FC), chip = Color(0xFFF1F3F9),
            line = Color(0xFFD9DEEA), divider = Color(0xFFEEF1F7),
            text = Color(0xFF2E3445), dim = Color(0xFF8A93A6), faint = Color(0xFFB3BACB),
            accent = Color(0xFF7C8CE0), accentText = Color(0xFF5D6DC9), accentSoft = Color(0xFFEEF0FB), onAccent = Color.White,
            late = Color(0xFFC9677A), lateSoft = Color(0xFFFBEEF1),
            ok = Color(0xFF3F8F5E), okSoft = Color(0xFFE7F4EC),
            warn = Color(0xFF9A5B12), warnSoft = Color(0xFFFCF1E2),
            window = Color(0xFFF6F7FB),
        )
        val Dark = BuddyTheme(
            dark = true,
            surface = Color(0xFF262A36), surfaceAlt = Color(0xFF2C2F40), chip = Color(0xFF30354A),
            line = Color(0xFF363B4C), divider = Color(0xFF30354A),
            text = Color(0xFFE6E4F0), dim = Color(0xFF8E92A8), faint = Color(0xFF6F7489),
            accent = Color(0xFFB7A8F5), accentText = Color(0xFFB7A8F5), accentSoft = Color(0xFF2E2C45), onAccent = Color(0xFF262A36),
            late = Color(0xFFF2B8A0), lateSoft = Color(0xFF3A2E2E),
            ok = Color(0xFF9BD8B0), okSoft = Color(0xFF24352B),
            warn = Color(0xFFF5CF9B), warnSoft = Color(0xFF3A3226),
            window = Color(0xFF1E212B),
        )

        /** Which theme applies right now. */
        fun forNow(s: Settings, now: LocalTime = LocalTime.now()): BuddyTheme = when (s.theme) {
            ThemeMode.LIGHT -> Light
            ThemeMode.DARK -> Dark
            ThemeMode.BY_TIME -> {
                val darkFrom = runCatching { LocalTime.parse(s.darkFrom) }.getOrDefault(LocalTime.of(19, 0))
                val lightFrom = runCatching { LocalTime.parse(s.lightFrom) }.getOrDefault(LocalTime.of(7, 0))
                val isDark = if (darkFrom > lightFrom) now >= darkFrom || now < lightFrom else now >= darkFrom && now < lightFrom
                if (isDark) Dark else Light
            }
        }
    }
}

val LocalTheme = staticCompositionLocalOf { BuddyTheme.Light }

enum class ThemeMode(val label: String) { BY_TIME("Light by day, dark in the evening"), LIGHT("Always light"), DARK("Always dark") }
