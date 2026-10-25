package com.par9uet.jm.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class PaletteContrastTest {
    @Test fun `all preset roles and tag containers keep readable text in both themes`() {
        assertEquals(listOf("Linear", "Vercel", "Raycast", "Dracula", "Catppuccin"), colorPresets.map { it.name })
        // Stored IDs remain compatible with existing settings and backups.
        assertEquals(listOf("default", "ocean", "sunset", "forest", "lavender"), colorPresets.map { it.id })
        for (preset in colorPresets) for (dark in listOf(false, true)) {
            val scheme = applyPaletteOverride(if (dark) darkScheme else lightScheme, preset.id, dark) { null }
            val roles = listOf(scheme.primary to scheme.onPrimary, scheme.secondary to scheme.onSecondary,
                scheme.tertiary to scheme.onTertiary, scheme.error to scheme.onError)
            val containers = listOf(scheme.primaryContainer to scheme.onPrimaryContainer,
                scheme.secondaryContainer to scheme.onSecondaryContainer,
                scheme.tertiaryContainer to scheme.onTertiaryContainer, scheme.errorContainer to scheme.onErrorContainer)
            for ((background, text) in roles + containers) {
                assertTrue("${preset.name} dark=$dark text contrast", colorContrast(background, text) >= 4.5f)
                assertEquals(1f, background.alpha, 0f)
            }
            for ((role, _) in roles) {
                assertTrue("${preset.name} dark=$dark role must be readable on page", colorContrast(role, scheme.surface) >= 4.5f)
            }
            val tags = extendedColorSchemeFor(scheme, dark)
            for (tag in listOf(tags.contentTag, tags.roleTag, tags.workTag)) {
                assertTrue(colorContrast(tag.colorContainer, tag.onColorContainer) >= 4.5f)
            }
        }
    }

    @Test fun `mid brightness red uses black text instead of illegible white`() {
        val red = Color(0xFFFF6363)
        assertTrue(colorContrast(red, Color.White) < 4.5f)
        assertEquals(Color.Black, readableContentColor(red))
        assertEquals(21f, colorContrast(Color.Black, Color.White), 0.01f)
    }

    @Test fun `custom roles and their containers also receive readable foregrounds`() {
        for (dark in listOf(false, true)) {
            val scheme = applyPaletteOverride(if (dark) darkScheme else lightScheme, "custom", dark) {
                listOf("#FFFFFF", "#000000", "#FF6363", "#777777")[it]
            }
            for ((background, text) in listOf(scheme.primary to scheme.onPrimary,
                scheme.secondary to scheme.onSecondary, scheme.tertiary to scheme.onTertiary,
                scheme.error to scheme.onError, scheme.primaryContainer to scheme.onPrimaryContainer,
                scheme.secondaryContainer to scheme.onSecondaryContainer,
                scheme.tertiaryContainer to scheme.onTertiaryContainer, scheme.errorContainer to scheme.onErrorContainer)) {
                assertTrue(colorContrast(background, text) >= 4.5f)
            }
        }
    }
}
