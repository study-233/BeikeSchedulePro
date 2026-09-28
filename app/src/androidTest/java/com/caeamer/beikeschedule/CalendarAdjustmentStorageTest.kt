package com.caeamer.beikeschedule

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.caeamer.beikeschedule.data.local.*
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.data.repo.CalendarAdjustmentRepository
import com.caeamer.beikeschedule.data.repo.ScheduleRepository
import com.caeamer.beikeschedule.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/** 需由开发者先生成 Room 10.json；只使用注入的响应，不请求网络。 */
class CalendarAdjustmentStorageTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val semester = SettingsStore.SemesterConfig("2026-2027", "1", "秋季", "2026-09-07", 4,
        listOf("2026-09-07", "2026-09-14", "2026-09-21", "2026-10-05"))
    private val config = CalendarAdjustments(1, 1, listOf(SemesterAdjustments("2026-2027", "1",
        listOf("2026-10-06"), listOf(ExtraClass("2026-09-20", "2026-10-06")))))
    private fun row() = CourseEntity(taskId = "task", name = "课程", teacher = "", location = "", dayOfWeek = 2,
        startSection = 1, endSection = 2, weekBitmap = "00001", colorIndex = 0, source = CourseEntity.SOURCE_IMPORT)

    @Test fun migration9To10PreservesSchedulesGradesAndExams() {
        val name = "adjustment-migration-test"
        helper.createDatabase(name, 9).apply {
            execSQL("INSERT INTO schedule VALUES (42,'保留课表',0,'2026-2027','1','秋季','2026-09-07',18,'2026-09-07','2026-10-06')")
            execSQL("INSERT INTO schedule_state VALUES (1,42,9,1)")
            execSQL("INSERT INTO grade (id,kcdm,kcmc,xnxq,xnxqmc,kcxz,kclb,xf,zzcj,bkcx,yxmc,sffx,pm,zrs,khfs) VALUES (3,'c','课程','','','','',2.0,'90','','',0,'','','')")
            execSQL("INSERT INTO exam (id,kcdm,kcmc,kslx,kssjms,ksrq,kssj,jssj,cdmc,zwh,jkjsbz,kkyxmc,xnxq,source) VALUES (5,'','手动考试','','','','','','','','','','',1)")
            close()
        }
        helper.runMigrationsAndValidate(name, 10, true, AppDatabase.MIGRATE_9_10).use { db ->
            db.query("SELECT holidayDates FROM schedule WHERE id=42").use { assertTrue(it.moveToFirst()); assertEquals("2026-10-06", it.getString(0)) }
            db.query("SELECT zzcj FROM grade WHERE id=3").use { assertTrue(it.moveToFirst()); assertEquals("90", it.getString(0)) }
            db.query("SELECT source FROM exam WHERE id=5").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            db.query("SELECT COUNT(*) FROM calendar_adjustment_cache").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        }
    }

    @Test fun migrationChain5To10KeepsCourseIdentityAndHiddenState() {
        val name = "adjustment-chain-test"
        helper.createDatabase(name, 5).apply {
            execSQL("INSERT INTO course (id,taskId,name,teacher,location,dayOfWeek,startSection,endSection,weekBitmap,colorIndex,source,hidden) VALUES (42,'task','数学','','',1,1,2,'011',0,0,1)")
            close()
        }
        helper.runMigrationsAndValidate(name, 10, true, AppDatabase.MIGRATE_5_6, AppDatabase.MIGRATE_6_7,
            AppDatabase.MIGRATE_7_8, AppDatabase.MIGRATE_8_9, AppDatabase.MIGRATE_9_10).use { db ->
            db.query("SELECT name,hidden,scheduleId FROM course WHERE id=42").use {
                assertTrue(it.moveToFirst()); assertEquals("数学", it.getString(0)); assertEquals(1, it.getInt(1)); assertEquals(1L, it.getLong(2))
            }
        }
    }

    @Test fun refreshReimportAndWithdrawalKeepAtomicSnapshotAndCourseRows() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val schedules = ScheduleRepository(db, SettingsStore(context), { emptyList() }, { semester })
            val a = schedules.commitImport(null, "A", semester, listOf(row()), emptyList())
            val before = schedules.getScheduleSnapshot()
            var response = CalendarAdjustmentCodec.encode(config)
            val feed = CalendarAdjustmentRepository(db, { response })
            val observed = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5_000) { schedules.currentSchedule.first { it.adjustments != null } }
            }
            feed.refresh(true)
            val current = observed.await()
            assertEquals(config, current.adjustments)
            assertEquals(before.reminderVersion + 1, current.reminderVersion)
            assertEquals(before.courses, current.courses)
            schedules.commitImport(a, "A", semester, listOf(row()), emptyList())
            assertEquals(config, schedules.getScheduleSnapshot().adjustments)
            val restarted = ScheduleRepository(db, SettingsStore(context), { emptyList() }, { error("无需初始化") })
            assertEquals(config, restarted.getScheduleSnapshot().adjustments)
            val b = schedules.commitImport(null, "B", semester.copy(xq = "2"), listOf(row()), emptyList())
            assertNull(DateCourseResolver.applicable(schedules.getScheduleSnapshot().semester, config).rules)
            schedules.switchSchedule(a)
            assertEquals(1, DateCourseResolver.resolve(schedules.getScheduleSnapshot().courses, semester, config, LocalDate.parse("2026-09-20")).size)
            val version = schedules.getScheduleSnapshot().reminderVersion
            response = CalendarAdjustmentCodec.encode(config.copy(revision = 2, semesters = emptyList()))
            feed.refresh(true)
            val withdrawn = schedules.getScheduleSnapshot()
            assertEquals(version + 1, withdrawn.reminderVersion)
            assertTrue(DateCourseResolver.resolve(withdrawn.courses, semester, withdrawn.adjustments, LocalDate.parse("2026-09-20")).isEmpty())
            assertEquals(1, withdrawn.courses.size)
            assertNotEquals(a, b)
        } finally { db.close() }
    }

    @Test fun failedAndOutdatedResponsesKeepCacheAndThrottleButManualRefreshRetries() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            var time = 1_000L
            var calls = 0
            var response = CalendarAdjustmentCodec.encode(config.copy(revision = 2))
            var fail = false
            val feed = CalendarAdjustmentRepository(db, { calls++; if (fail) throw java.net.SocketTimeoutException(); response }, { time })
            feed.refresh()
            val body = db.calendarAdjustmentDao().get()!!.body
            feed.refresh()
            assertEquals(1, calls)
            time += 24 * 60 * 60 * 1000L
            fail = true
            feed.refresh()
            assertEquals(body, db.calendarAdjustmentDao().get()!!.body)
            feed.refresh()
            assertEquals(2, calls)
            fail = false
            for (invalid in listOf("{broken", CalendarAdjustmentCodec.encode(config),
                CalendarAdjustmentCodec.encode(config.copy(revision = 2, semesters = emptyList())))) {
                response = invalid; feed.refresh(true)
                assertEquals(body, db.calendarAdjustmentDao().get()!!.body)
            }
            assertEquals(5, calls)
            assertFalse(CalendarAdjustmentRepository.shouldCheck(time, time + 1))
            assertTrue(CalendarAdjustmentRepository.shouldCheck(time, time - 1))
        } finally { db.close() }
    }

    @Test fun failedTransactionCannotPublishNewRulesWithOldReminderIdentity() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val schedules = ScheduleRepository(db, SettingsStore(context), { emptyList() }, { semester })
            schedules.getScheduleSnapshot()
            var response = CalendarAdjustmentCodec.encode(config)
            val feed = CalendarAdjustmentRepository(db, { response })
            feed.refresh(true)
            val before = schedules.getScheduleSnapshot()
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_adjustment_version BEFORE INSERT ON schedule_state BEGIN SELECT RAISE(ABORT, 'test failure'); END")
            response = CalendarAdjustmentCodec.encode(config.copy(revision = 2, semesters = emptyList()))
            feed.refresh(true)
            val failed = schedules.getScheduleSnapshot()
            assertEquals(before.adjustments, failed.adjustments)
            assertEquals(before.reminderVersion, failed.reminderVersion)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_adjustment_version")
            feed.refresh(true)
            assertEquals(2L, schedules.getScheduleSnapshot().adjustments!!.revision)
            assertEquals(before.reminderVersion + 1, schedules.getScheduleSnapshot().reminderVersion)
        } finally { db.close() }
    }

    @Test fun simultaneousManualRequestsShareOneFetch() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            var calls = 0
            val feed = CalendarAdjustmentRepository(db, {
                calls++; entered.complete(Unit); release.await(); CalendarAdjustmentCodec.encode(config)
            })
            val first = async { feed.refresh(true) }
            withTimeout(5_000) { entered.await() }
            val second = async(start = CoroutineStart.UNDISPATCHED) { feed.refresh(true) }
            release.complete(Unit)
            withTimeout(5_000) { awaitAll(first, second) }
            assertEquals(1, calls)
        } finally { release.complete(Unit); db.close() }
    }
}
