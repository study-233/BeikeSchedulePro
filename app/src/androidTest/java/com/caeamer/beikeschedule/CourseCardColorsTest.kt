package com.caeamer.beikeschedule

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.caeamer.beikeschedule.ui.theme.CourseColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseCardColorsTest {
    @Test fun allTextRemainsOpaqueAndReadableInBothThemesAndWeekStates() {
        for (index in 0..9) for (dark in listOf(false, true)) for (active in listOf(false, true)) {
            val palette = CourseColors.card(index, dark, active)
            assertEquals(1f, palette.background.alpha, 0f)
            listOf(palette.title, palette.location, palette.detail).forEach { ink ->
                assertEquals(1f, ink.alpha, 0f)
                val contrast = (maxOf(ink.luminance(), palette.background.luminance()) + 0.05f) /
                    (minOf(ink.luminance(), palette.background.luminance()) + 0.05f)
                assertTrue("palette=$index dark=$dark active=$active contrast=$contrast", contrast >= 4.5f)
            }
            assertNotEquals(CourseColors.card(index, dark, true).background, CourseColors.card(index, dark, false).background)
        }
    }

    @Test fun widgetPaletteRetainsOriginalColors() {
        assertEquals(Color(0xFFD6F0F5) to Color(0xFF2E7B8C), CourseColors.of(5))
        assertEquals(CourseColors.card(9), CourseColors.card(-1))
    }
}
