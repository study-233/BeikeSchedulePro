package com.caeamer.beikeschedule

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.caeamer.beikeschedule.data.pref.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleAppearanceStoreTest {
    @Test fun heightPersistsAndIndependentResetsPreserveOtherAppearanceSettings() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsStore(context)
        val original = settings.scheduleAppearance.first()
        try {
            settings.updateScheduleAppearance { it.copy(fontPercent = 145, sectionHeightPercent = 123) }
            val reloaded = SettingsStore(context).scheduleAppearance.first()
            assertEquals(125, reloaded.sectionHeightPercent)
            assertEquals(145, reloaded.fontPercent)

            settings.updateScheduleAppearance { it.withoutBackground() }
            assertEquals(125, settings.scheduleAppearance.first().sectionHeightPercent)
            settings.updateScheduleAppearance { it.copy(fontPercent = 100) }
            assertEquals(125, settings.scheduleAppearance.first().sectionHeightPercent)

            settings.updateScheduleAppearance { it.copy(fontPercent = 145, sectionHeightPercent = 100) }
            val fitted = SettingsStore(context).scheduleAppearance.first()
            assertEquals(100, fitted.sectionHeightPercent)
            assertEquals(145, fitted.fontPercent)
        } finally {
            settings.updateScheduleAppearance { original }
        }
    }
}
