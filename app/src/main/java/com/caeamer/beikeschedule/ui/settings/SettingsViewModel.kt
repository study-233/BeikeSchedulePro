package com.caeamer.beikeschedule.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.data.repo.ScheduleRepository
import com.caeamer.beikeschedule.reminder.ExamReminderScheduler
import com.caeamer.beikeschedule.widget.WidgetUpdateCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)

    val themeMode: StateFlow<SettingsStore.ThemeMode> = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsStore.ThemeMode.SYSTEM)
    val studentProfile: StateFlow<SettingsStore.StudentProfile> = settings.studentProfile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsStore.StudentProfile())
    val appVersion: StateFlow<String> = MutableStateFlow(
        runCatching {
            getApplication<Application>().packageManager
                .getPackageInfo(getApplication<Application>().packageName, 0).versionName ?: ""
        }.getOrDefault(""),
    )

    fun setThemeMode(mode: SettingsStore.ThemeMode) {
        viewModelScope.launch {
            settings.setThemeMode(mode)
            WidgetUpdateCoordinator.requestRefresh(getApplication())
        }
    }

    /** 清除成绩本地缓存（含教务考试与学业进度，保留手动考试，下次进教务 Tab 重新抓取）。 */
    fun clearGradesCache() {
        viewModelScope.launch {
            val repo = ScheduleRepository(getApplication())
            settings.saveGradesMeta("", 0L)
            settings.saveCreditMeta("", "")
            repo.replaceGrades(emptyList())
            repo.replaceImportedExams(emptyList())
            // 只撤销已删除的教务考试提醒，保留手动考试及其已到点的待投递提醒。
            runCatching { ExamReminderScheduler.reschedule(getApplication()) }
                .onFailure { e -> if (e is CancellationException) throw e }
        }
    }

    companion object {
        /** 外部系统入口（"我的"页外链组）。课程平台/实践平台地址待补后追加。 */
        const val PINGJIAO_URL = "https://pingjiao.ustb.edu.cn"
        const val SRTP_URL = "https://srtp.ustb.edu.cn"
    }
}
