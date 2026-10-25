package com.par9uet.jm.ui.theme

import androidx.compose.ui.graphics.Color
import com.par9uet.jm.data.models.COLOR_PALETTE_PRESET_DEFAULT

internal data class ColorPreset(
    val id: String,
    val name: String,
    val colors: List<Long>,
    val darkColors: List<Long> = colors,
)

/**
 * Four Material roles adapted from the referenced palettes (primary, secondary, tertiary, error).
 * Light accents are deepened where necessary for text on light surfaces; IDs retain saved choices.
 * Sources: linear.app/brand, vercel.com/geist/colors, raycast.com/press,
 * draculatheme.com/spec (Alucard/Dracula), catppuccin.com/palette (Latte/Mocha).
 */
internal val colorPresets = listOf(
    ColorPreset(COLOR_PALETTE_PRESET_DEFAULT, "Linear",
        colors = listOf(0xFF5C68D0, 0xFF62666D, 0xFF7953BD, 0xFFC93345),
        darkColors = listOf(0xFFA5ADFF, 0xFFB4BCD0, 0xFFC6A7FF, 0xFFFF8A99),
    ),
    ColorPreset("ocean", "Vercel",
        colors = listOf(0xFF0064E2, 0xFF666666, 0xFF7C00C9, 0xFFD60020),
        darkColors = listOf(0xFF50A8FF, 0xFFA1A1A1, 0xFFC472FB, 0xFFFF5E63),
    ),
    ColorPreset("sunset", "Raycast",
        colors = listOf(0xFFBA3F4A, 0xFF5F6170, 0xFF7653A6, 0xFFB42318),
        darkColors = listOf(0xFFFF6363, 0xFFB9BBC6, 0xFFC6A0F6, 0xFFFF9B8E),
    ),
    ColorPreset("forest", "Dracula",
        colors = listOf(0xFF644AC9, 0xFF036A96, 0xFFA3144D, 0xFFCB3A2A),
        darkColors = listOf(0xFFBD93F9, 0xFF8BE9FD, 0xFFFF79C6, 0xFFFF5555),
    ),
    ColorPreset("lavender", "Catppuccin",
        colors = listOf(0xFF8839EF, 0xFF1E66F5, 0xFF11757B, 0xFFD20F39),
        darkColors = listOf(0xFFCBA6F7, 0xFF89B4FA, 0xFF94E2D5, 0xFFF38BA8),
    ),
)

internal fun String.toColorOrNull(): Color? {
    return runCatching {
        val hex = this.removePrefix("#")
        val long = if (hex.length == 6) "FF$hex".toLong(16) else hex.toLong(16)
        Color(long.toInt())
    }.getOrNull()
}

