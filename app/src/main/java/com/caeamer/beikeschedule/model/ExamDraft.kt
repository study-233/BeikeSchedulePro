package com.caeamer.beikeschedule.model

import com.caeamer.beikeschedule.data.local.ExamEntity
import java.time.LocalDate
import java.time.LocalTime

/** 手动考试草稿；空字符串表示待定，日期/时间以 ISO 格式保存。 */
data class ExamDraft(
    val id: Long = 0,
    val name: String = "",
    val date: String = "",
    val start: String = "",
    val end: String = "",
    val location: String = "",
    val seat: String = "",
    val type: String = "",
    val note: String = "",
) {
    fun withDate(value: String): ExamDraft =
        if (value.isEmpty()) copy(date = "", start = "", end = "") else copy(date = value)

    fun withStart(value: String): ExamDraft =
        if (value.isEmpty()) copy(start = "", end = "") else copy(start = value)

    fun validationError(): String? = when {
        name.isBlank() -> "请填写考试名称"
        date.isNotEmpty() && runCatching { LocalDate.parse(date) }.isFailure -> "请选择有效日期"
        date.isEmpty() && (start.isNotEmpty() || end.isNotEmpty()) -> "请先选择考试日期"
        start.isNotEmpty() && !validTime(start) -> "请选择有效的开始时间"
        end.isNotEmpty() && start.isEmpty() -> "请先选择开始时间"
        end.isNotEmpty() && !validTime(end) -> "请选择有效的结束时间"
        end.isNotEmpty() && !LocalTime.parse(end).isAfter(LocalTime.parse(start)) -> "结束时间须晚于开始时间，不支持跨日考试"
        else -> null
    }

    fun toEntity(): ExamEntity {
        require(validationError() == null) { validationError().orEmpty() }
        return ExamEntity(id = id, kcdm = "", kcmc = name.trim(), kslx = type.trim(),
            kssjms = "", ksrq = date, kssj = start, jssj = end, cdmc = location.trim(),
            zwh = seat.trim(), jkjsbz = note.trim(), kkyxmc = "", xnxq = "", source = ExamEntity.SOURCE_MANUAL)
    }

    /** SavedStateHandle 只保存平台支持的值，不要求领域类型 Parcelable。 */
    fun savedFields(): ArrayList<String> = arrayListOf(id.toString(), name, date, start, end, location, seat, type, note)

    companion object {
        private fun validTime(value: String): Boolean =
            value.matches(Regex("\\d{2}:\\d{2}")) && runCatching { LocalTime.parse(value) }.isSuccess

        fun restore(fields: List<String>): ExamDraft = ExamDraft(
            fields[0].toLong(), fields[1], fields[2], fields[3], fields[4], fields[5], fields[6], fields[7], fields[8])

        fun from(exam: ExamEntity): ExamDraft {
            require(exam.isManual) { "教务考试不能手动修改" }
            return ExamDraft(exam.id, exam.kcmc, exam.ksrq, exam.kssj, exam.jssj,
                exam.cdmc, exam.zwh, exam.kslx, exam.jkjsbz)
        }
    }
}
