package com.caeamer.beikeschedule.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.caeamer.beikeschedule.AppInfo
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.data.repo.ScheduleRepository
import com.caeamer.beikeschedule.import.parser.GradesParser
import com.caeamer.beikeschedule.reminder.ExamReminderScheduler
import com.caeamer.beikeschedule.widget.WidgetUpdateCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/** 更新检查状态。 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val latestVersion: String, val notes: String, val url: String) : UpdateState
    data class Failed(val message: String) : UpdateState
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)

    val themeMode: StateFlow<SettingsStore.ThemeMode> = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsStore.ThemeMode.SYSTEM)
    val update: StateFlow<UpdateState> = updateState
    val studentProfile: StateFlow<SettingsStore.StudentProfile> = settings.studentProfile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsStore.StudentProfile())
    val appVersion: StateFlow<String> = MutableStateFlow(
        runCatching {
            getApplication<Application>().packageManager
                .getPackageInfo(getApplication<Application>().packageName, 0).versionName ?: ""
        }.getOrDefault(""),
    )

    /** 「隐藏本周不上的课」：与课表页共用同一个 DataStore 键，两边即时同步。 */
    val hideInactiveCourses: StateFlow<Boolean> = settings.hideInactiveCourses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setHideInactiveCourses(hidden: Boolean) {
        viewModelScope.launch { settings.setHideInactiveCourses(hidden) }
    }

    init {
        checkUpdate()
    }

    fun setThemeMode(mode: SettingsStore.ThemeMode) {
        viewModelScope.launch {
            settings.setThemeMode(mode)
            WidgetUpdateCoordinator.requestRefresh(getApplication())
        }
    }

    /** 清除成绩本地缓存（含考试安排与学业进度，下次进教务 Tab 重新抓取）。 */
    fun clearGradesCache() {
        viewModelScope.launch {
            val repo = ScheduleRepository(getApplication())
            settings.saveGradesMeta("", 0L)
            settings.saveCreditMeta("", "")
            repo.replaceGrades(emptyList())
            repo.replaceExams(emptyList())
            // 考试数据已清空 → 同步取消已排的考前提醒
            // （否则成绩清完了，旧的"明天考试"闹钟还会带着地点/座位号弹出来）。
            // cancelDueAlarms = true：用户显式清空，连"已到点但系统还没投递"的那条
            // 也不要再弹；日常重排必须保持默认 false，否则会丢掉 Doze 下未投递的提醒。
            // runCatching：重排异常逃出 viewModelScope 会崩进程，这里只允许"本轮不重排"。
            runCatching { ExamReminderScheduler.reschedule(getApplication(), cancelDueAlarms = true) }
                .onFailure { e -> if (e is CancellationException) throw e }
        }
    }

    /** 检查 GitHub 最新 release 与已装版本比对（进入设置页自动触发，可手动重查）。 */
    fun checkUpdate() {
        if (updateState.value is UpdateState.Checking) return
        updateState.value = UpdateState.Checking
        viewModelScope.launch {
            updateState.value = fetchLatestRelease()
        }
    }

    private suspend fun fetchLatestRelease(): UpdateState = withContext(Dispatchers.IO) {
        val installed = runCatching {
            getApplication<Application>().packageManager
                .getPackageInfo(getApplication<Application>().packageName, 0).versionName
        }.getOrNull()
            ?: return@withContext UpdateState.Failed("无法读取本机版本号")

        // disconnect 必须在 finally：此前只有成功路径会断开，非 200 早退与异常路径
        // 都泄漏连接直到 GC（弱网下表现为后续请求排队变慢）。
        var conn: HttpURLConnection? = null
        try {
            conn = URL(AppInfo.RELEASES_API).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "BeikeSchedulePro")
            if (conn.responseCode != 200) {
                return@withContext UpdateState.Failed("GitHub 请求失败（HTTP ${conn.responseCode}）")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = Json.parseToJsonElement(body).jsonObject
            val tag = obj["tag_name"]?.jsonPrimitive?.content ?: return@withContext UpdateState.Failed("响应缺少版本号")
            // body 可能是 JSON null：jsonPrimitive.content 对字面量 null 会返回字符串 "null"，
            // 直接进更新说明会显示"null"。显式判空。
            val notes = obj["body"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content.orEmpty().take(300)
            val url = obj["html_url"]?.jsonPrimitive?.content ?: AppInfo.REPO_URL
            when {
                GradesParser.compareVersions(tag, installed) > 0 ->
                    UpdateState.Available(tag.removePrefix("v"), notes, url)
                else -> UpdateState.UpToDate
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            UpdateState.Failed("检查失败：${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        /** 外部系统入口（"我的"页外链组）。课程平台/实践平台地址待补后追加。 */
        const val PINGJIAO_URL = "https://pingjiao.ustb.edu.cn"
        const val SRTP_URL = "https://srtp.ustb.edu.cn"
    }
}
