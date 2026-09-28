package com.caeamer.beikeschedule.data.repo

import com.caeamer.beikeschedule.data.local.ExamEntity
import com.caeamer.beikeschedule.data.local.GradeEntity
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.data.pref.ScorePrivacy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AcademicDataSyncTest {
    private class Sink : AcademicDataSink {
        val writes = mutableListOf<String>()
        var grades = emptyList<GradeEntity>()
        var exams = emptyList<ExamEntity>()
        override suspend fun grades(rows: List<GradeEntity>, gpa: String) { writes += "grades"; grades = rows }
        override suspend fun student(profile: SettingsStore.StudentProfile) { writes += "student" }
        override suspend fun exams(rows: List<ExamEntity>) { writes += "exams"; exams = rows }
        override suspend fun credits(categories: String, progress: String) { writes += "credits" }
        override suspend fun rescheduleExams() { writes += "exam_reminders" }
        override suspend fun gradesOnly(rows: List<GradeEntity>) { writes += "grades"; grades = rows }
        override suspend fun gpa(json: String) { writes += "gpa" }
    }
    private val payload = AcademicPayload(
        gpa = """{"BL":4.0,"HDXF":3.0}""",
        grades = """{"content":{"list":[{"kcdm":"test","kcmc":"测试课程","zzcj":"90","xf":3}]}}""",
        user = "", student = "", semester = """{"XN":"2026-2027","XQ":"1"}""",
        exams = """{"list":[]}""", categories = "[]", progress = "{}",
    )

    @Test fun `两入口共用缓存同步 成绩隐私保持隐藏`() = runBlocking {
        val sink = Sink()
        ScorePrivacy.hide()
        assertNull(AcademicDataSync(sink).save(payload) {})
        assertEquals(listOf("grades", "exams", "credits", "exam_reminders"), sink.writes)
        assertEquals("测试课程", sink.grades.single().kcmc)
        assertTrue(ScorePrivacy.hidden.value)
    }

    @Test fun `考试子请求失败或错误页面不清空考试也不取消提醒`() = runBlocking {
        for (response in listOf("", "<html>登录</html>", """{"code":500}""")) {
            val sink = Sink()
            val warning = AcademicDataSync(sink).save(payload.copy(exams = response)) {}
            assertTrue(warning!!.contains("考试安排获取失败"))
            assertEquals(listOf("grades", "credits"), sink.writes)
        }
    }

    @Test fun `空成绩保留旧成绩 其他成功数据仍保存 合法空考试仍更新提醒`() = runBlocking {
        val sink = Sink()
        val warning = AcademicDataSync(sink).save(payload.copy(grades = """{"content":{"list":[]}}""")) {}
        assertTrue(warning!!.contains("未解析到成绩"))
        assertEquals(listOf("exams", "credits", "exam_reminders"), sink.writes)
        assertTrue(sink.exams.isEmpty())
    }

    @Test fun `没有成绩时也保存成功获取的学籍与学业进度`() = runBlocking {
        val sink = Sink()
        AcademicDataSync(sink).save(payload.copy(
            grades = "", user = """{"yhdm":"test-student","xm":"测试"}""",
            student = """{"ZYMC":"测试专业"}""",
        )) {}
        assertEquals(listOf("student", "exams", "credits", "exam_reminders"), sink.writes)
    }

    @Test fun `退出登录后回调不能写库`() = runBlocking {
        val sink = Sink()
        try {
            AcademicDataSync(sink).save(payload) { throw CancellationException("retired") }
            fail("取消应传播到调用方")
        } catch (_: CancellationException) { }
        assertTrue(sink.writes.isEmpty())
    }

    @Test fun `落盘期间取消后不继续更新其他缓存或提醒`() = runBlocking {
        val sink = Sink()
        try {
            AcademicDataSync(sink).save(payload) {
                if (sink.writes.isNotEmpty()) throw CancellationException("logout")
            }
            fail("取消应传播到调用方")
        } catch (_: CancellationException) { }
        assertEquals(listOf("grades"), sink.writes)
    }
    @Test fun `GPA失败后考试仍可独立保存且不写成绩`() = runBlocking {
        val sink = Sink()
        val sync = AcademicDataSync(sink)
        assertTrue(runCatching { sync.savePart(com.caeamer.beikeschedule.import.AcademicTask.GPA, payload.copy(gpa = "{}")) {} }.isFailure)
        sync.savePart(com.caeamer.beikeschedule.import.AcademicTask.EXAMS, payload) {}
        assertEquals(listOf("exams", "exam_reminders"), sink.writes)
    }

    @Test fun `成绩与GPA各自更新不会覆盖另一项缓存`() = runBlocking {
        val sink = Sink()
        val sync = AcademicDataSync(sink)
        sync.savePart(com.caeamer.beikeschedule.import.AcademicTask.GRADES, payload.copy(gpa = "")) {}
        assertEquals(listOf("grades"), sink.writes)
        sync.savePart(com.caeamer.beikeschedule.import.AcademicTask.GPA, payload.copy(grades = "")) {}
        assertEquals(listOf("grades", "gpa"), sink.writes)
    }

    @Test fun `错误考试与空成绩不能清空已有缓存`() = runBlocking {
        for ((task, invalid) in listOf(
            com.caeamer.beikeschedule.import.AcademicTask.EXAMS to payload.copy(exams = "{\"code\":500}"),
            com.caeamer.beikeschedule.import.AcademicTask.GRADES to payload.copy(grades = "{\"content\":{\"list\":[]}}"),
        )) {
            val sink = Sink()
            assertTrue(runCatching { AcademicDataSync(sink).savePart(task, invalid) {} }.isFailure)
            assertTrue(sink.writes.isEmpty())
        }
    }

    @Test fun `独立任务写入前检查取消状态`() = runBlocking {
        val sink = Sink()
        assertTrue(runCatching {
            AcademicDataSync(sink).savePart(com.caeamer.beikeschedule.import.AcademicTask.GPA, payload) { throw CancellationException("logout") }
        }.isFailure)
        assertTrue(sink.writes.isEmpty())
    }
    @Test fun `成绩失败不影响学籍和学业进度落盘`() = runBlocking {
        val sink = Sink()
        val sync = AcademicDataSync(sink)
        val independent = payload.copy(
            grades = "", user = """{"yhdm":"test-student","xm":"测试"}""",
            student = """{"ZYMC":"测试专业"}""",
            categories = """{"content":{"list":[]}}""",
            progress = """{"content":{"yqmsxf":{"YQXF":120}}}""",
        )
        assertTrue(runCatching { sync.savePart(com.caeamer.beikeschedule.import.AcademicTask.GRADES, independent) {} }.isFailure)
        sync.savePart(com.caeamer.beikeschedule.import.AcademicTask.STUDENT, independent) {}
        sync.savePart(com.caeamer.beikeschedule.import.AcademicTask.PROGRESS, independent) {}
        assertEquals(listOf("student", "credits"), sink.writes)
    }

    @Test fun `进度缺少一个子结果时保留完整旧进度缓存`() = runBlocking {
        val sink = Sink()
        assertTrue(runCatching {
            AcademicDataSync(sink).savePart(com.caeamer.beikeschedule.import.AcademicTask.PROGRESS,
                payload.copy(categories = "{\"content\":{\"list\":[]}}", progress = "")) {}
        }.isFailure)
        assertTrue(sink.writes.isEmpty())
    }
}
