package com.par9uet.jm.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

/** WCAG relative luminance uses linear sRGB, not a weighted sum of encoded RGB values. */
internal fun colorContrast(first: Color, second: Color): Float {
    val a = first.luminance()
    val b = second.luminance()
    return (max(a, b) + 0.05f) / (min(a, b) + 0.05f)
}

internal fun readableContentColor(background: Color): Color =
    if (colorContrast(background, Color.Black) >= colorContrast(background, Color.White)) Color.Black else Color.White
