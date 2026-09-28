package com.caeamer.beikeschedule

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.caeamer.beikeschedule.data.local.AppDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 运行前由 KSP 导出版本 7 schema，不手写 Room identity hash。 */
class ExamMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun migrate6To7PreservesExamIdsAndDefaultsToImported() {
        val name = "exam-migration-test"
        helper.createDatabase(name, 6).apply {
            execSQL("INSERT INTO exam (id,kcdm,kcmc,kslx,kssjms,ksrq,kssj,jssj,cdmc,zwh,jkjsbz,kkyxmc,xnxq) VALUES (42,'M','数学','期末','','2026-10-02','09:00','11:00','逸夫楼','09','备注','学院','2026-20271')")
            close()
        }
        helper.runMigrationsAndValidate(name, 7, true, AppDatabase.MIGRATE_6_7).use { db ->
            db.query("SELECT id,kcmc,ksrq,kssj,jssj,cdmc,zwh,jkjsbz,source FROM exam").use {
                assertTrue(it.moveToFirst())
                assertEquals(42L, it.getLong(0)); assertEquals("数学", it.getString(1))
                assertEquals("2026-10-02", it.getString(2)); assertEquals("09:00", it.getString(3))
                assertEquals("11:00", it.getString(4)); assertEquals("逸夫楼", it.getString(5))
                assertEquals("09", it.getString(6)); assertEquals("备注", it.getString(7))
                assertEquals(0, it.getInt(8))
            }
        }
    }

    @Test fun migrationChain5To7RetainsLegacyData() {
        val name = "exam-migration-chain-test"
        helper.createDatabase(name, 5).apply {
            execSQL("INSERT INTO exam VALUES (7,'','历史考试','','','','','','','','','','')")
            close()
        }
        helper.runMigrationsAndValidate(name, 7, true, AppDatabase.MIGRATE_5_6, AppDatabase.MIGRATE_6_7).use { db ->
            db.query("SELECT kcmc,source FROM exam WHERE id = 7").use {
                assertTrue(it.moveToFirst()); assertEquals("历史考试", it.getString(0)); assertEquals(0, it.getInt(1))
            }
        }
    }
}
