package com.caeamer.beikeschedule

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.caeamer.beikeschedule.data.local.AppDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 运行前由 KSP 导出版本 8 schema；不手写 Room identity hash。 */
class HolidayCalendarMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun migrate7To8PreservesSchedulesAndDefaultsToUnknownHolidays() {
        val name = "holiday-calendar-migration-test"
        helper.createDatabase(name, 7).apply {
            execSQL("INSERT INTO schedule VALUES (42,'课表',0,'2026-2027','1','秋季','2026-09-07',18,'2026-09-07,2026-09-14,2026-09-21,2026-10-05')")
            execSQL("INSERT INTO schedule_state VALUES (1,42,9,1)")
            close()
        }
        helper.runMigrationsAndValidate(name, 8, true, AppDatabase.MIGRATE_7_8).use { db ->
            db.query("SELECT id,name,weekMondays,holidayDates FROM schedule").use {
                assertTrue(it.moveToFirst())
                assertEquals(42L, it.getLong(0))
                assertEquals("课表", it.getString(1))
                assertEquals("2026-09-07,2026-09-14,2026-09-21,2026-10-05", it.getString(2))
                assertEquals("", it.getString(3))
            }
            db.query("SELECT activeScheduleId,reminderVersion,initialized FROM schedule_state").use {
                assertTrue(it.moveToFirst())
                assertEquals(42L, it.getLong(0))
                assertEquals(9L, it.getLong(1))
                assertEquals(1, it.getInt(2))
            }
        }
    }

    @Test fun migrationChain5To8PreservesExistingCourse() {
        val name = "holiday-calendar-migration-chain-test"
        helper.createDatabase(name, 5).apply {
            execSQL("INSERT INTO course (id,taskId,name,teacher,location,dayOfWeek,startSection,endSection,weekBitmap,colorIndex,source,hidden) VALUES (42,'task','数学','','',1,1,2,'011',0,0,1)")
            close()
        }
        helper.runMigrationsAndValidate(name, 8, true,
            AppDatabase.MIGRATE_5_6, AppDatabase.MIGRATE_6_7, AppDatabase.MIGRATE_7_8).use { db ->
            db.query("SELECT name,hidden,scheduleId FROM course WHERE id=42").use {
                assertTrue(it.moveToFirst())
                assertEquals("数学", it.getString(0))
                assertEquals(1, it.getInt(1))
                assertEquals(1L, it.getLong(2))
            }
            db.query("SELECT holidayDates FROM schedule WHERE id=1").use {
                assertTrue(it.moveToFirst())
                assertEquals("", it.getString(0))
            }
        }
    }
}
