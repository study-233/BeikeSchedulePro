package com.caeamer.beikeschedule

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.caeamer.beikeschedule.data.local.AppDatabase
import com.caeamer.beikeschedule.data.local.NoticeEntity
import com.caeamer.beikeschedule.data.repo.NoticeRepository
import com.caeamer.beikeschedule.import.parser.NoticePage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 由开发者构建生成 9.json 后运行；只使用本地数据库。 */
class NoticesStorageTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun migration8To9PreservesCalendarAndManualExam() {
        val name = "notice-migration-test"
        helper.createDatabase(name, 8).apply {
            execSQL("INSERT INTO schedule VALUES (42,'保留课表',0,'2026-2027','1','秋季','2026-09-07',18,'2026-09-07','2026-09-12')")
            execSQL("INSERT INTO schedule_state VALUES (1,42,9,1)")
            execSQL("INSERT INTO exam (id,kcdm,kcmc,kslx,kssjms,ksrq,kssj,jssj,cdmc,zwh,jkjsbz,kkyxmc,xnxq,source) VALUES (5,'','手动考试','','','','','','','','','','',1)")
            close()
        }
        helper.runMigrationsAndValidate(name, 9, true, AppDatabase.MIGRATE_8_9).use { db ->
            db.query("SELECT name,holidayDates FROM schedule WHERE id=42").use {
                assertTrue(it.moveToFirst()); assertEquals("保留课表", it.getString(0)); assertEquals("2026-09-12", it.getString(1))
            }
            db.query("SELECT source FROM exam WHERE id=5").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            db.query("SELECT COUNT(*) FROM notice").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        }
    }

    @Test fun migrationChain5To9KeepsImportedCourse() {
        val name = "notice-chain-migration-test"
        helper.createDatabase(name, 5).apply {
            execSQL("INSERT INTO course (id,taskId,name,teacher,location,dayOfWeek,startSection,endSection,weekBitmap,colorIndex,source,hidden) VALUES (42,'task','数学','','',1,1,2,'011',0,0,1)")
            close()
        }
        helper.runMigrationsAndValidate(name, 9, true, AppDatabase.MIGRATE_5_6,
            AppDatabase.MIGRATE_6_7, AppDatabase.MIGRATE_7_8, AppDatabase.MIGRATE_8_9).use { db ->
            db.query("SELECT name,hidden,scheduleId FROM course WHERE id=42").use {
                assertTrue(it.moveToFirst()); assertEquals("数学", it.getString(0)); assertEquals(1, it.getInt(1)); assertEquals(1L, it.getLong(2))
            }
        }
    }

    @Test fun pagingDeduplicatesAndFailedRefreshRollsBackRowsAndCursor() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = NoticeRepository(db)
            fun row(id: String) = NoticeEntity(id, id, "2026-09-28", true, "https://jwc.ustb.edu.cn/$id", 0)
            repo.save(NoticePage(listOf(row("a"), row("b")), 1, 2, true, 3)) {}
            repo.save(NoticePage(listOf(row("b"), row("c")), 2, 0, false, 3)) {}
            assertEquals(listOf("a", "b", "c"), db.noticeDao().all().map { it.id })
            val before = db.noticeDao().feed()
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_notice BEFORE INSERT ON notice BEGIN SELECT RAISE(ABORT, 'test failure'); END")
            assertTrue(runCatching { repo.save(NoticePage(listOf(row("new")), 1, 0, false, 1)) {} }.isFailure)
            assertEquals(listOf("a", "b", "c"), db.noticeDao().all().map { it.id })
            assertEquals(before, db.noticeDao().feed())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_notice")
            var checks = 0
            assertTrue(runCatching {
                repo.save(NoticePage(listOf(row("late")), 1, 0, false, 1)) {
                    if (++checks == 2) throw CancellationException("logout")
                }
            }.isFailure)
            assertEquals(before, db.noticeDao().feed())
            assertEquals(listOf("a", "b", "c"), db.noticeDao().all().map { it.id })
            repo.save(NoticePage(emptyList(), 1, 0, false, 0)) {}
            assertTrue(db.noticeDao().all().isEmpty())
            assertFalse(db.noticeDao().feed()!!.hasNext)
            repo.clear()
            assertNull(db.noticeDao().feed())
        } finally { db.close() }
    }
}
