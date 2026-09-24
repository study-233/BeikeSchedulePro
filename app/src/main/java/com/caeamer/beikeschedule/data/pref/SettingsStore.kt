package com.caeamer.beikeschedule.data.pref

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.datastore.preferences.core.Preferences
import com.caeamer.beikeschedule.model.BackgroundScale
import com.caeamer.beikeschedule.model.ScheduleAppearance

/**
 * 配置存储。corruptionHandler 必加：settings.preferences_pb 一旦损坏（写中断/存储写满/恢复异常），
 * 默认实现会抛 CorruptionException 进入 collectAsState/stateIn 的收集协程 → 每次启动都崩且无法自愈，
 * 用户只能清应用数据。兜底重置为默认配置：成绩库与课表库是独立的 Room 数据库，不受影响。
 */
private val Context.dataStore by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** 学期与提醒等配置（DataStore）。 */
class SettingsStore(private val context: Context) {

    data class SemesterConfig(
        val xn: String = "",          // 学年，如 "2026-2027"
        val xq: String = "",          // 学期，如 "1"
        val name: String = "",        // 展示名，如 "2026-2027-1"
        val firstMonday: String = "", // 第 1 周周一，yyyy-MM-dd
        val totalWeeks: Int = 20,
        /**
         * 官方教学周日历：下标+1 = 教学周，值 = 该周周一（yyyy-MM-dd）。
         * 来自教务校历接口，长假周不占序号；为空时回退 firstMonday + totalWeeks 推算。
         */
        val weekMondays: List<String> = emptyList(),
    )

    /** 学籍快照（教务抓取时顺手存，"我的"页离线展示）。 */
    data class StudentProfile(
        val xm: String = "",       // 姓名
        val xh: String = "",       // 学号
        val yxmc: String = "",     // 学院
        val zymc: String = "",     // 专业
        val bjmc: String = "",     // 班级
        val njmc: String = "",     // 年级
        val xjsfzx: String = "",   // 是否在校（"1"=在校）
        val xjsfzc: String = "",   // 是否注册（"1"=已注册）
    ) {
        val isLoggedIn: Boolean get() = xh.isNotBlank()
    }

    /** 主题模式：跟随系统 / 浅色 / 深色。 */
    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    private object Keys {
        val SCHEDULE_FONT = intPreferencesKey("schedule_font_percent")
        val SCHEDULE_BACKGROUND = stringPreferencesKey("schedule_background_file")
        val SCHEDULE_SCALE = stringPreferencesKey("schedule_background_scale")
        val SCHEDULE_OVERLAY = intPreferencesKey("schedule_background_overlay")
        val SCHEDULE_BLUR = intPreferencesKey("schedule_background_blur")
        val XN = stringPreferencesKey("semester_xn")
        val XQ = stringPreferencesKey("semester_xq")
        val NAME = stringPreferencesKey("semester_name")
        val FIRST_MONDAY = stringPreferencesKey("first_monday")
        val TOTAL_WEEKS = intPreferencesKey("total_weeks")
        val WEEK_MONDAYS = stringPreferencesKey("week_mondays")
        val REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled")
        val REMINDER_MINUTES = intPreferencesKey("reminder_minutes")
        val REMINDER_CODES = stringPreferencesKey("reminder_codes")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val GPA_CACHE = stringPreferencesKey("gpa_cache")
        val GRADES_FETCHED_AT = longPreferencesKey("grades_fetched_at")
        val SP_XM = stringPreferencesKey("sp_xm")
        val SP_XH = stringPreferencesKey("sp_xh")
        val SP_YXMC = stringPreferencesKey("sp_yxmc")
        val SP_ZYMC = stringPreferencesKey("sp_zymc")
        val SP_BJMC = stringPreferencesKey("sp_bjmc")
        val SP_NJMC = stringPreferencesKey("sp_njmc")
        val SP_XJSFZX = stringPreferencesKey("sp_xjsfzx")
        val SP_XJSFZC = stringPreferencesKey("sp_xjsfzc")
        val WEIGHT_SEMESTER = stringPreferencesKey("weight_semester")
        val WEIGHT_EXCLUDED = stringPreferencesKey("weight_excluded")
        val HIDE_WEEKEND = booleanPreferencesKey("hide_weekend")
        val HIDE_INACTIVE_COURSES = booleanPreferencesKey("hide_inactive_courses")
        val XFLBYQ_JSON = stringPreferencesKey("xflbyq_json")
        val BXKQK_JSON = stringPreferencesKey("bxkqk_json")
        val EXAM_REMINDER_CODES = stringPreferencesKey("exam_reminder_codes")
        val TODO_REMINDER_CODES = stringPreferencesKey("todo_reminder_codes")
        val FREE_ROOM_BUILDING = stringPreferencesKey("free_room_building")
        val FREE_ROOM_TAB_INDEX = intPreferencesKey("free_room_tab_index")
    }

    private fun readAppearance(p: Preferences) = ScheduleAppearance(
        fontPercent = p[Keys.SCHEDULE_FONT] ?: 100,
        backgroundFile = p[Keys.SCHEDULE_BACKGROUND].orEmpty(),
        imageScale = BackgroundScale.entries.firstOrNull { it.name == p[Keys.SCHEDULE_SCALE] }
            ?: BackgroundScale.CROP,
        overlayPercent = p[Keys.SCHEDULE_OVERLAY] ?: 30,
        blurDp = p[Keys.SCHEDULE_BLUR] ?: 0,
    ).normalized()

    val scheduleAppearance: Flow<ScheduleAppearance> = context.dataStore.data
        .map(::readAppearance).distinctUntilChanged()

    /** 在同一次 DataStore 事务中读取和修改，避免字号写入覆盖正在更换的图片。 */
    suspend fun updateScheduleAppearance(transform: (ScheduleAppearance) -> ScheduleAppearance) {
        context.dataStore.edit { p ->
            val value = transform(readAppearance(p)).normalized()
            p[Keys.SCHEDULE_FONT] = value.fontPercent
            p[Keys.SCHEDULE_BACKGROUND] = value.backgroundFile
            p[Keys.SCHEDULE_SCALE] = value.imageScale.name
            p[Keys.SCHEDULE_OVERLAY] = value.overlayPercent
            p[Keys.SCHEDULE_BLUR] = value.blurDp
        }
    }

    val semester: Flow<SemesterConfig> = context.dataStore.data.map { p ->
        SemesterConfig(
            xn = p[Keys.XN] ?: "",
            xq = p[Keys.XQ] ?: "",
            name = p[Keys.NAME] ?: "",
            firstMonday = p[Keys.FIRST_MONDAY] ?: "",
            totalWeeks = p[Keys.TOTAL_WEEKS] ?: 20,
            weekMondays = p[Keys.WEEK_MONDAYS].toWeekMondays(),
        )
    }

    suspend fun saveSemester(config: SemesterConfig) {
        context.dataStore.edit { p ->
            p[Keys.XN] = config.xn
            p[Keys.XQ] = config.xq
            p[Keys.NAME] = config.name
            p[Keys.FIRST_MONDAY] = config.firstMonday
            p[Keys.TOTAL_WEEKS] = config.totalWeeks
            p[Keys.WEEK_MONDAYS] = config.weekMondays.joinToString(",")
        }
    }

    val reminderEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.REMINDER_ENABLED] ?: false }

    val reminderMinutes: Flow<Int> =
        context.dataStore.data.map { it[Keys.REMINDER_MINUTES] ?: 15 }

    suspend fun setReminder(enabled: Boolean, minutesBefore: Int) {
        context.dataStore.edit { p ->
            p[Keys.REMINDER_ENABLED] = enabled
            p[Keys.REMINDER_MINUTES] = minutesBefore
        }
    }

    /** 已排课程提醒闹钟（requestCode + 触发时刻），用于精确取消与"已到点不动它"的判定。 */
    val reminderScheduledAlarms: Flow<List<ScheduledAlarm>> =
        context.dataStore.data.map { p -> AlarmCodec.decode(p[Keys.REMINDER_CODES]) }

    suspend fun saveReminderScheduledAlarms(alarms: List<ScheduledAlarm>) {
        context.dataStore.edit { p -> p[Keys.REMINDER_CODES] = AlarmCodec.encode(alarms) }
    }

    /** 已排考试提醒闹钟（requestCode + 触发时刻），语义同上。 */
    val examScheduledAlarms: Flow<List<ScheduledAlarm>> =
        context.dataStore.data.map { p -> AlarmCodec.decode(p[Keys.EXAM_REMINDER_CODES]) }

    suspend fun saveExamScheduledAlarms(alarms: List<ScheduledAlarm>) {
        context.dataStore.edit { p -> p[Keys.EXAM_REMINDER_CODES] = AlarmCodec.encode(alarms) }
    }

    /** 已排日程提醒闹钟（requestCode + 触发时刻），语义同上。 */
    val todoScheduledAlarms: Flow<List<ScheduledAlarm>> =
        context.dataStore.data.map { p -> AlarmCodec.decode(p[Keys.TODO_REMINDER_CODES]) }

    suspend fun saveTodoScheduledAlarms(alarms: List<ScheduledAlarm>) {
        context.dataStore.edit { p -> p[Keys.TODO_REMINDER_CODES] = AlarmCodec.encode(alarms) }
    }

    /** 学分类别要求（queryXflbyq 原始 JSON）与毕业总进度（queryBxkqk 原始 JSON）缓存。 */
    val xflbyqJson: Flow<String> = context.dataStore.data.map { p -> p[Keys.XFLBYQ_JSON] ?: "" }
    val bxkqkJson: Flow<String> = context.dataStore.data.map { p -> p[Keys.BXKQK_JSON] ?: "" }

    suspend fun saveCreditMeta(xflbyqJson: String, bxkqkJson: String) {
        context.dataStore.edit { p ->
            p[Keys.XFLBYQ_JSON] = xflbyqJson
            p[Keys.BXKQK_JSON] = bxkqkJson
        }
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { p ->
        runCatching { ThemeMode.valueOf(p[Keys.THEME_MODE] ?: "") }.getOrDefault(ThemeMode.SYSTEM)
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { p -> p[Keys.THEME_MODE] = mode.name }
    }

    /** GPA 概览缓存（getgpa 原始 JSON）与成绩刷新时间（epoch 毫秒，0=从未抓取）。 */
    val gpaCache: Flow<String> = context.dataStore.data.map { p -> p[Keys.GPA_CACHE] ?: "" }
    val gradesFetchedAt: Flow<Long> = context.dataStore.data.map { p -> p[Keys.GRADES_FETCHED_AT] ?: 0L }

    suspend fun saveGradesMeta(gpaJson: String, fetchedAt: Long) {
        context.dataStore.edit { p ->
            p[Keys.GPA_CACHE] = gpaJson
            p[Keys.GRADES_FETCHED_AT] = fetchedAt
        }
    }

    /** 学籍快照缓存（教务 Tab 抓成绩时顺手抓的 user/me 关键字段）。 */
    val studentProfile: Flow<StudentProfile> = context.dataStore.data.map { p ->
        StudentProfile(
            xm = p[Keys.SP_XM] ?: "",
            xh = p[Keys.SP_XH] ?: "",
            yxmc = p[Keys.SP_YXMC] ?: "",
            zymc = p[Keys.SP_ZYMC] ?: "",
            bjmc = p[Keys.SP_BJMC] ?: "",
            njmc = p[Keys.SP_NJMC] ?: "",
            xjsfzx = p[Keys.SP_XJSFZX] ?: "",
            xjsfzc = p[Keys.SP_XJSFZC] ?: "",
        )
    }

    suspend fun saveStudentProfile(profile: StudentProfile) {
        context.dataStore.edit { p ->
            p[Keys.SP_XM] = profile.xm
            p[Keys.SP_XH] = profile.xh
            p[Keys.SP_YXMC] = profile.yxmc
            p[Keys.SP_ZYMC] = profile.zymc
            p[Keys.SP_BJMC] = profile.bjmc
            p[Keys.SP_NJMC] = profile.njmc
            p[Keys.SP_XJSFZX] = profile.xjsfzx
            p[Keys.SP_XJSFZC] = profile.xjsfzc
        }
    }

    /** 加权成绩用户自定义：学期筛选（空=全部学期）与手动排除的课程 kcdm 集合。 */
    val weightedSemesterFilter: Flow<String> =
        context.dataStore.data.map { it[Keys.WEIGHT_SEMESTER] ?: "" }
    val weightedExcludedKcdm: Flow<Set<String>> =
        context.dataStore.data.map { p ->
            p[Keys.WEIGHT_EXCLUDED]?.takeIf { it.isNotBlank() }
                ?.split(",")?.toSet() ?: emptySet()
        }

    suspend fun saveWeightedFilter(semester: String, excluded: Set<String>) {
        context.dataStore.edit { p ->
            p[Keys.WEIGHT_SEMESTER] = semester
            p[Keys.WEIGHT_EXCLUDED] = excluded.joinToString(",")
        }
    }

    /** 课表是否隐藏周六周日列（周末无课时收窄网格）。 */
    val hideWeekend: Flow<Boolean> = context.dataStore.data.map { it[Keys.HIDE_WEEKEND] ?: false }

    suspend fun setHideWeekend(hidden: Boolean) {
        context.dataStore.edit { p -> p[Keys.HIDE_WEEKEND] = hidden }
    }

    /**
     * 课表是否隐藏"本周暂时不上"的课。
     * 关（默认）：这些课以 30% alpha 淡化显示（单双周的另一半、还没到的调课周）；
     * 开：直接不显示，网格更干净。
     */
    val hideInactiveCourses: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.HIDE_INACTIVE_COURSES] ?: false }

    suspend fun setHideInactiveCourses(hidden: Boolean) {
        context.dataStore.edit { p -> p[Keys.HIDE_INACTIVE_COURSES] = hidden }
    }

    // —— 无课教室 ——

    /** 上次查看的楼栋 ID（空 = 用接口返回的第一栋）。 */
    val freeRoomBuilding: Flow<String> =
        context.dataStore.data.map { it[Keys.FREE_ROOM_BUILDING] ?: "" }

    suspend fun setFreeRoomBuilding(buildingId: String) {
        context.dataStore.edit { p -> p[Keys.FREE_ROOM_BUILDING] = buildingId }
    }

    /**
     * 教务 Tab 上次停留的分段下标（0=无课教室 1=成绩 2=考试）。
     *
     * 默认 0：用户明确要求"默认打开教务是无课教室"。
     * 键名 `free_room_tab_index` 是历史遗留（该分段当时只有无课教室），
     * 实际存的是**整个教务 Tab** 的分段，不是无课教室自己的状态。
     */
    val gradesTabIndex: Flow<Int> =
        context.dataStore.data.map { it[Keys.FREE_ROOM_TAB_INDEX] ?: 0 }

    suspend fun setGradesTabIndex(index: Int) {
        context.dataStore.edit { p -> p[Keys.FREE_ROOM_TAB_INDEX] = index }
    }

    private companion object {
        fun String?.toWeekMondays(): List<String> =
            this?.takeIf { it.isNotBlank() }?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: emptyList()
    }
}
