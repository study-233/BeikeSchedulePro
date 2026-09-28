package com.caeamer.beikeschedule.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

@Serializable
data class CalendarAdjustments(
    val schemaVersion: Int,
    val revision: Long,
    val semesters: List<SemesterAdjustments>,
)

@Serializable
data class SemesterAdjustments(
    val xn: String,
    val xq: String,
    val suspendedDates: List<String>,
    val extraClasses: List<ExtraClass>,
)

@Serializable
data class ExtraClass(val date: String, val sourceDate: String, val note: String = "")

/** 远端文件与本地缓存共用严格解析；先校验全量文件，再允许替换缓存。 */
object CalendarAdjustmentCodec {
    private val json = Json { encodeDefaults = true }
    const val MAX_BYTES = 256 * 1024

    fun parse(text: String): CalendarAdjustments {
        val value = json.decodeFromString<CalendarAdjustments>(text.removePrefix("\uFEFF"))
        require(value.schemaVersion == 1) { "不支持的调休格式版本" }
        require(value.revision > 0) { "调休版本必须为正整数" }
        require(value.semesters.distinctBy { it.xn to it.xq }.size == value.semesters.size) { "调休学期重复" }
        value.semesters.forEach { semester ->
            require(Regex("\\d{4}-\\d{4}").matches(semester.xn) && semester.xq in listOf("1", "2", "3")) { "调休学期格式无效" }
            val years = semester.xn.split('-').map(String::toInt)
            require(years[1] == years[0] + 1) { "调休学年必须为连续两年" }
            require(semester.suspendedDates.distinct().size == semester.suspendedDates.size) { "停课日期重复" }
            semester.suspendedDates.forEach(::date)
            require(semester.extraClasses.distinctBy { it.date to it.sourceDate }.size == semester.extraClasses.size) { "补课规则重复" }
            semester.extraClasses.forEach {
                date(it.date); date(it.sourceDate)
                require(it.date != it.sourceDate) { "补课日期不能与来源日期相同" }
                require(it.note.length <= 200) { "调休备注过长" }
            }
        }
        return value.copy(semesters = value.semesters.sortedWith(compareBy({ it.xn }, { it.xq })).map {
            it.copy(suspendedDates = it.suspendedDates.sorted(),
                extraClasses = it.extraClasses.sortedWith(compareBy({ rule -> rule.date }, { rule -> rule.sourceDate })))
        })
    }

    fun encode(value: CalendarAdjustments): String = json.encodeToString(value)

    /** 返回是否需要替换内容；同版本仅允许语义相同的响应。 */
    fun shouldReplace(previous: CalendarAdjustments?, incoming: CalendarAdjustments): Boolean {
        if (previous == null) return true
        require(incoming.revision >= previous.revision) { "调休版本低于本地缓存，已保留原配置" }
        require(incoming.revision != previous.revision || incoming == previous) { "调休内容已修改但版本未递增，已保留原配置" }
        return incoming.revision > previous.revision
    }

    private fun date(raw: String) {
        require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(raw) && runCatching { LocalDate.parse(raw) }.isSuccess) { "调休日期无效：$raw" }
    }
}
