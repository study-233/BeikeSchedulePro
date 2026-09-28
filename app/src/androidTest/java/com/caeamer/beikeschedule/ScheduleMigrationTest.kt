package com.caeamer.beikeschedule

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.caeamer.beikeschedule.data.local.AppDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** 运行前需由 KSP 导出版本 6 schema；不手写生成的 Room identity hash。 */
class ScheduleMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun migrate5To6PreservesIdsHiddenRowsAndTimes() {
        val name = "schedule-migration-test"
        helper.createDatabase(name, 5).apply {
            execSQL("INSERT INTO course (id, taskId, name, teacher, location, dayOfWeek, startSection, endSection, weekBitmap, colorIndex, source, hidden) VALUES (42, 'task', '数学', '教师', '教室', 1, 1, 2, '011', 0, 0, 1)")
            execSQL("INSERT INTO section_time VALUES (1, '08:00', '08:45')")
            execSQL("INSERT INTO todo (id, title, note, repeatMode, weekdays, date, time, remindMinutes, colorIndex, lastDoneDate) VALUES (9, '旧日程', '', 0, '0111110', '', '08:00', 15, 0, '')")
            close()
        }
        helper.runMigrationsAndValidate(name, 6, true, AppDatabase.MIGRATE_5_6).use { db ->
            db.query("SELECT id, hidden, scheduleId FROM course").use {
                assertTrue(it.moveToFirst()); assertEquals(42L, it.getLong(0)); assertEquals(1, it.getInt(1)); assertEquals(1L, it.getLong(2))
            }
            db.query("SELECT scheduleId, startTime FROM section_time").use {
                assertTrue(it.moveToFirst()); assertEquals(1L, it.getLong(0)); assertEquals("08:00", it.getString(1))
            }
            db.query("SELECT activeScheduleId, initialized FROM schedule_state").use {
                assertTrue(it.moveToFirst()); assertEquals(1L, it.getLong(0)); assertEquals(0, it.getInt(1))
            }
            db.query("SELECT title FROM todo WHERE id = 9").use { assertTrue(it.moveToFirst()); assertEquals("旧日程", it.getString(0)) }
        }
    }
}
