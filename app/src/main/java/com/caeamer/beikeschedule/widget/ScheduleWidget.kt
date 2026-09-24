package com.caeamer.beikeschedule.widget

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.data.repo.ScheduleRepository
import com.caeamer.beikeschedule.model.TodaySchedule
import com.caeamer.beikeschedule.model.TodayScheduleResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.ZonedDateTime

internal data class ScheduleWidgetState(
    val schedule: TodaySchedule? = null,
    val theme: SettingsStore.ThemeMode = SettingsStore.ThemeMode.SYSTEM,
    val failed: Boolean = false,
)

class ScheduleWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = ScheduleRepository(context.applicationContext)
        provideContent {
            val revision = currentState<Preferences>()[RefreshRevision]
            val state by produceState(ScheduleWidgetState(), revision) {
                value = try {
                    val theme = repo.settings.themeMode.first()
                    val snapshot = repo.getScheduleSnapshot()
                    ScheduleWidgetState(
                        schedule = TodayScheduleResolver.resolve(snapshot, ZonedDateTime.now()),
                        theme = theme,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    ScheduleWidgetState(failed = true)
                }
            }
            ScheduleWidgetContent(state)
        }
    }

    companion object {
        internal val RefreshRevision = stringPreferencesKey("refresh_revision")
        const val ACTION_OPEN_SCHEDULE = "io.github.study233.beikeschedulepro.action.OPEN_WIDGET_SCHEDULE"
    }
}
