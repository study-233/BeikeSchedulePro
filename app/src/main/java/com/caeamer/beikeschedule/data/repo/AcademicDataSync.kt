package com.caeamer.beikeschedule.data.repo

import android.content.Context
import com.caeamer.beikeschedule.data.local.ExamEntity
import com.caeamer.beikeschedule.data.local.GradeEntity
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.import.parser.ExamsParser
import com.caeamer.beikeschedule.import.parser.GradesParser
import com.caeamer.beikeschedule.import.parser.JwParser
import com.caeamer.beikeschedule.reminder.ExamReminderScheduler
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import com.caeamer.beikeschedule.import.AcademicTask

/** 沿用现有八项抓取结果和空成绩语义，不将原始载荷存入导航状态。 */
data class AcademicPayload(
    val gpa: String, val grades: String, val user: String, val student: String,
    val semester: String, val exams: String, val categories: String, val progress: String,
)

internal interface AcademicDataSink {
    suspend fun grades(rows: List<GradeEntity>, gpa: String)
    suspend fun student(profile: SettingsStore.StudentProfile)
    suspend fun exams(rows: List<ExamEntity>)
    suspend fun credits(categories: String, progress: String)
    suspend fun rescheduleExams()
    suspend fun gradesOnly(rows: List<GradeEntity>)
    suspend fun gpa(json: String)
}

/** 各项同步共用；每次落盘前检查请求仍有效，取消不被当成普通错误吞掉。 */
internal class AcademicDataSync(private val sink: AcademicDataSink) {
    suspend fun savePart(task: AcademicTask, payload: AcademicPayload, ensureCurrent: () -> Unit) {
        suspend fun check() { currentCoroutineContext().ensureActive(); ensureCurrent() }
        check()
        when (task) {
            AcademicTask.GRADES -> {
                val rows = GradesParser.parseGrades(payload.grades)
                require(rows.isNotEmpty()) { "未解析到成绩，请确认已完成评教且成绩已发布" }
                check(); sink.gradesOnly(rows)
            }
            AcademicTask.GPA -> {
                val gpa = JSONObject(payload.gpa)
                require(!gpa.isNull("BL") && gpa.has("BL")) { "未获取到 GPA 数据" }
                check(); sink.gpa(payload.gpa)
            }
            AcademicTask.STUDENT -> {
                val profile = requireNotNull(GradesParser.parseStudentProfile(payload.user, payload.student)) { "未获取到学籍信息" }
                require(profile.isLoggedIn) { "未获取到学籍信息" }
                check(); sink.student(profile)
            }
            AcademicTask.EXAMS -> {
                require(JSONObject(payload.exams).optJSONArray("list") != null) { "考试响应格式异常" }
                val (xn, xq, _) = JwParser.parseCurrentSemester(payload.semester)
                require(xn.isNotBlank() && xq.isNotBlank()) { "考试学期信息缺失" }
                val rows = ExamsParser.parseExams(payload.exams, xn + xq)
                check(); sink.exams(rows)
                check(); sink.rescheduleExams()
            }
            AcademicTask.PROGRESS -> {
                require(JSONObject(payload.categories).optJSONObject("content")?.optJSONArray("list") != null &&
                    JSONObject(payload.progress).optJSONObject("content")?.optJSONObject("yqmsxf") != null) { "学业进度响应格式异常" }
                check(); sink.credits(payload.categories, payload.progress)
            }
            else -> error("不支持的数据任务")
        }
        check()
    }

    suspend fun save(payload: AcademicPayload, ensureCurrent: () -> Unit): String? {
        suspend fun check() { currentCoroutineContext().ensureActive(); ensureCurrent() }
        check()
        val grades = GradesParser.parseGrades(payload.grades)
        if (grades.isNotEmpty()) { check(); sink.grades(grades, payload.gpa) }
        GradesParser.parseStudentProfile(payload.user, payload.student)?.let {
            check(); sink.student(it)
        }
        val (xn, xq, _) = JwParser.parseCurrentSemester(payload.semester)
        // 失败页/错误 JSON 不应被解析成“成功的空考试”，否则会清空旧提醒。
        val examsSucceeded = runCatching { JSONObject(payload.exams).optJSONArray("list") != null }.getOrDefault(false)
        if (examsSucceeded) { check(); sink.exams(ExamsParser.parseExams(payload.exams, xn + xq)) }
        if (payload.categories.isNotBlank() || payload.progress.isNotBlank()) {
            check(); sink.credits(payload.categories, payload.progress)
        }
        if (examsSucceeded) { check(); sink.rescheduleExams() }
        return buildList {
            if (grades.isEmpty()) add("未解析到成绩，请确认已在教务系统完成评教/成绩发布后重试")
            if (!examsSucceeded) add("考试安排获取失败，已保留上次数据")
        }.joinToString("；").ifEmpty { null }
    }

    companion object {
        fun create(context: Context): AcademicDataSync {
            val app = context.applicationContext
            val repo = ScheduleRepository(app)
            return AcademicDataSync(object : AcademicDataSink {
                override suspend fun gradesOnly(rows: List<GradeEntity>) {
                    repo.replaceGrades(rows)
                    currentCoroutineContext().ensureActive()
                    repo.settings.saveGradesTime(System.currentTimeMillis())
                }
                override suspend fun gpa(json: String) = repo.settings.saveGpa(json)
                override suspend fun grades(rows: List<GradeEntity>, gpa: String) {
                    repo.replaceGrades(rows)
                    currentCoroutineContext().ensureActive()
                    repo.settings.saveGradesMeta(gpa, System.currentTimeMillis())
                }
                override suspend fun student(profile: SettingsStore.StudentProfile) = repo.settings.saveStudentProfile(profile)
                override suspend fun exams(rows: List<ExamEntity>) = repo.replaceImportedExams(rows)
                override suspend fun credits(categories: String, progress: String) = repo.settings.saveCreditMeta(categories, progress)
                override suspend fun rescheduleExams() = ExamReminderScheduler.reschedule(app)
            })
        }
    }
}
