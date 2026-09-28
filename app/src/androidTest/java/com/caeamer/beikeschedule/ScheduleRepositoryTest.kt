package com.caeamer.beikeschedule

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.caeamer.beikeschedule.data.local.*
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.data.repo.ScheduleRepository
import com.caeamer.beikeschedule.model.SemesterDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class ScheduleRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: ScheduleRepository
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val times = listOf(SectionTimeEntity(1, "08:00", "08:45"))
    private val semester = SettingsStore.SemesterConfig("2026-2027", "1", "秋季", "2026-09-07", 20,
        listOf("2026-09-07", "2026-09-14"))

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = ScheduleRepository(db, SettingsStore(context), { times }, { semester })
    }
    @After fun close() { db.close() }

    private fun course(source: Int = CourseEntity.SOURCE_IMPORT, hidden: Boolean = false) = CourseEntity(
        taskId = "task", name = "数学", teacher = "教师", location = "教室", dayOfWeek = 1,
        startSection = 1, endSection = 1, weekBitmap = "011", colorIndex = 0, source = source, hidden = hidden)

    @Test fun initializationCanRetryAndNeverOverwritesSavedSemester() = runBlocking {
        var fail = true
        val retrying = ScheduleRepository(db, SettingsStore(context), { times }, {
            if (fail) error("模拟读取失败")
            semester
        })
        assertTrue(runCatching { retrying.getScheduleSnapshot() }.isFailure)
        assertNull(db.scheduleDao().state())
        fail = false
        val first = retrying.getScheduleSnapshot()
        retrying.saveSemesterDraft(first.scheduleId, SemesterDraft("我的学期", "2026-08-31", 22))
        val restarted = ScheduleRepository(db, SettingsStore(context), { times }, { error("不应再次读取旧配置") })
        assertEquals("我的学期", restarted.getScheduleSnapshot().semester.name)
        assertEquals(1, restarted.schedules.first().size)
    }

    @Test fun legacyInitializationRollsBackConfigAndMarkerTogether() = runBlocking {
        db.scheduleDao().insert(ScheduleEntity(id = 1, name = "默认课表", createdAt = 0))
        db.scheduleDao().saveState(ScheduleStateEntity(activeScheduleId = 1))
        db.courseDao().insert(course(hidden = true).copy(id = 42, scheduleId = 1))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_initialization BEFORE INSERT ON schedule_state BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        assertTrue(runCatching { repo.getScheduleSnapshot() }.isFailure)
        assertFalse(db.scheduleDao().state()!!.initialized)
        assertEquals("", db.scheduleDao().get(1)!!.semesterName)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_initialization")
        val initialized = repo.getScheduleSnapshot()
        assertEquals(semester, initialized.semester)
        assertEquals(42L, initialized.courses.single().id)
        assertTrue(initialized.courses.single().hidden)
        assertTrue(db.scheduleDao().state()!!.initialized)
    }

    @Test fun courseNamesAndSectionsAreIsolatedAndImportPreservesManualAndHidden() = runBlocking {
        val a = repo.commitImport(null, "A", semester, listOf(course()), times)
        val imported = repo.getScheduleSnapshot().courses.single()
        repo.setCoursesHidden(a, listOf(imported.id), true)
        repo.addManualCourse(a, course(CourseEntity.SOURCE_MANUAL).copy(name = "自习"))
        val b = repo.commitImport(null, "B", semester.copy(name = "B学期"), listOf(course()),
            listOf(SectionTimeEntity(1, "09:00", "09:45")))
        assertFalse(repo.getScheduleSnapshot().courses.single().hidden)
        repo.commitImport(a, "不会覆盖名称", semester, listOf(course().copy(location = "新教室")), times)
        val updated = repo.getScheduleSnapshot()
        assertEquals("A", updated.scheduleName)
        assertEquals(2, updated.courses.size)
        assertTrue(updated.courses.first { it.source == CourseEntity.SOURCE_IMPORT }.hidden)
        assertEquals("08:00", updated.sectionTimes.single().startTime)
        repo.switchSchedule(b)
        val other = repo.currentSchedule.first()
        assertEquals("B学期", other.semester.name)
        assertEquals("09:00", other.sectionTimes.single().startTime)
        assertEquals("教室", other.courses.single().location)
        assertTrue(runCatching { repo.replaceCourses(b, listOf(imported.id), emptyList()) }.isFailure)
        assertEquals(1, repo.getScheduleSnapshot().courses.size)
    }

    @Test fun clearingAndDeletingKeepOtherDataAndLastScheduleBecomesEmptyDefault() = runBlocking {
        val original = repo.getScheduleSnapshot().scheduleId
        val a = repo.commitImport(null, "A", semester, listOf(course(hidden = true)), times)
        val b = repo.createSchedule("B")
        repo.replaceGrades(listOf(GradeEntity(kcdm = "g", kcmc = "成绩", xnxq = "", xnxqmc = "", kcxz = "",
            kclb = "", xf = 1.0, zzcj = "90", bkcx = "", yxmc = "", sffx = false)))
        repo.upsertTodo(TodoEntity(title = "历史日程", time = "08:00"))
        repo.replaceImportedExams(listOf(ExamEntity(kcdm = "e", kcmc = "考试", kslx = "", kssjms = "", ksrq = "",
            kssj = "", jssj = "", cdmc = "", zwh = "", jkjsbz = "", kkyxmc = "", xnxq = "")))
        repo.switchSchedule(a)
        val before = repo.getScheduleSnapshot()
        repo.clearSchedule(a)
        val cleared = repo.getScheduleSnapshot()
        assertTrue(cleared.courses.isEmpty())
        assertEquals(before.semester, cleared.semester)
        assertEquals(before.sectionTimes, cleared.sectionTimes)
        assertTrue(cleared.reminderVersion > before.reminderVersion)
        repo.deleteSchedule(a)
        assertEquals(original, repo.getScheduleSnapshot().scheduleId)
        repo.deleteSchedule(b)
        assertEquals(original, repo.getScheduleSnapshot().scheduleId)
        repo.deleteSchedule(original)
        val empty = repo.getScheduleSnapshot()
        assertEquals("默认课表", empty.scheduleName)
        assertTrue(empty.courses.isEmpty())
        assertEquals(1, repo.schedules.first().size)
        assertEquals(1, db.gradeDao().count())
        assertEquals(1, db.examDao().getAll().size)
        assertEquals(1, db.todoDao().getAll().size)
        assertTrue(runCatching { repo.saveSemesterDraft(a, SemesterDraft("旧草稿", "", 20)) }.isFailure)
        assertTrue(runCatching { repo.replaceCourses(a, emptyList(), listOf(course())) }.isFailure)
    }

    @Test fun failedImportRollsBackCourseCalendarSectionsAndActiveSelection() = runBlocking {
        val a = repo.commitImport(null, "A", semester.copy(holidayDates = listOf("2026-09-25")), listOf(course()), times)
        val before = repo.getScheduleSnapshot()
        val b = repo.createSchedule("B")
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_section BEFORE INSERT ON section_time BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        assertTrue(runCatching {
            repo.commitImport(a, "A", semester.copy(firstMonday = "2026-08-31", holidayDates = listOf("2026-10-05")),
                listOf(course().copy(name = "新课")), times)
        }.isFailure)
        assertEquals(b, repo.getScheduleSnapshot().scheduleId)
        repo.switchSchedule(a)
        val after = repo.getScheduleSnapshot()
        assertEquals(before.courses, after.courses)
        assertEquals(before.semester, after.semester)
        assertEquals(before.sectionTimes, after.sectionTimes)
        val count = repo.schedules.first().size
        assertTrue(runCatching { repo.commitImport(null, "失败新课表", semester, listOf(course()), times) }.isFailure)
        assertEquals(count, repo.schedules.first().size)
    }

    @Test fun holidayDatesAreIsolatedReplacedAndPreservedWhenClearingCourses() = runBlocking {
        val aCalendar = semester.copy(holidayDates = listOf("2026-09-25", "2026-09-26"))
        val a = repo.commitImport(null, "A", aCalendar, listOf(course()), times)
        val b = repo.commitImport(null, "B", semester.copy(holidayDates = listOf("2026-10-05")), listOf(course()), times)
        repo.switchSchedule(a)
        assertEquals(aCalendar.holidayDates, repo.getScheduleSnapshot().semester.holidayDates)
        repo.clearSchedule(a)
        assertEquals(aCalendar.holidayDates, repo.getScheduleSnapshot().semester.holidayDates)
        repo.commitImport(a, "A", aCalendar.copy(holidayDates = listOf("2026-09-27")), listOf(course()), times)
        assertEquals(listOf("2026-09-27"), repo.getScheduleSnapshot().semester.holidayDates)
        repo.switchSchedule(b)
        assertEquals(listOf("2026-10-05"), repo.getScheduleSnapshot().semester.holidayDates)
        // 旧脚本或兜底数据不保留可能属于旧学期的日期。
        repo.commitImport(a, "A", semester, listOf(course()), times)
        assertTrue(repo.getScheduleSnapshot().semester.holidayDates.isEmpty())
    }

    @Test fun differentSemesterRequiresExplicitConfirmationAndRepeatedSwitchDoesNotChangeVersion() = runBlocking {
        val a = repo.commitImport(null, "A", semester, listOf(course()), times)
        val before = repo.getScheduleSnapshot()
        repo.switchSchedule(a)
        assertEquals(before.reminderVersion, repo.getScheduleSnapshot().reminderVersion)
        assertTrue(runCatching { repo.commitImport(a, "A", semester.copy(xq = "2"), listOf(course()), times) }.isFailure)
        repo.commitImport(a, "A", semester.copy(xq = "2"), listOf(course()), times, allowDifferentSemester = true)
        assertEquals("2", repo.getScheduleSnapshot().semester.xq)
        val b = repo.createSchedule("B")
        repeat(5) { repo.switchSchedule(a); repo.switchSchedule(b) }
        assertEquals(b, repo.getScheduleSnapshot().scheduleId)
        assertTrue(repo.getScheduleSnapshot().reminderVersion > before.reminderVersion)
    }
    @Test fun automaticImportKeepsCurrentScheduleAndPreservesTargetManualCourses() = runBlocking {
        val target = repo.getScheduleSnapshot().scheduleId
        repo.commitImport(target, "", semester, listOf(course()), times)
        repo.setCoursesHidden(target, db.courseDao().getBySource(target, CourseEntity.SOURCE_IMPORT).map { it.id }, true)
        repo.addManualCourse(target, course(CourseEntity.SOURCE_MANUAL).copy(name = "自定义课"))
        val current = repo.createSchedule("当前查看")
        repo.commitImport(target, "", semester, listOf(course()), times, activateTarget = false)
        assertEquals(current, repo.getScheduleSnapshot().scheduleId)
        val rows = db.courseDao().getBySource(target, CourseEntity.SOURCE_IMPORT)
        assertTrue(rows.single().hidden)
        assertEquals("自定义课", db.courseDao().getBySource(target, CourseEntity.SOURCE_MANUAL).single().name)
    }

    @Test fun retiredImportRollsBackDatabaseTransaction() = runBlocking {
        val target = repo.getScheduleSnapshot().scheduleId
        repo.commitImport(target, "", semester, listOf(course()), times)
        var checks = 0
        assertTrue(runCatching {
            repo.commitImport(target, "", semester, listOf(course().copy(name = "迟到课")), times,
                activateTarget = false, ensureCurrent = { if (++checks == 2) throw kotlinx.coroutines.CancellationException("logout") })
        }.isFailure)
        assertEquals("数学", repo.getScheduleSnapshot().courses.single().name)
    }
}
