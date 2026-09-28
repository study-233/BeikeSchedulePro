package com.caeamer.beikeschedule

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.caeamer.beikeschedule.data.local.AppDatabase
import com.caeamer.beikeschedule.data.local.ExamEntity
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.data.repo.ScheduleRepository
import com.caeamer.beikeschedule.model.ExamDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class ManualExamRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: ScheduleRepository
    private val draft = ExamDraft(name = "手动数学", date = "2026-10-02", start = "09:00")
    private val imported = draft.toEntity().copy(kcmc = "教务英语", source = ExamEntity.SOURCE_IMPORT)

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = ScheduleRepository(db, SettingsStore(context), { emptyList() })
    }
    @After fun close() { db.close() }

    @Test fun manualCrudPreservesIdAndAllowsSameName() = runBlocking {
        val first = repo.saveManualExam(draft)
        val second = repo.saveManualExam(draft)
        assertNotEquals(first, second)
        assertEquals(first, repo.saveManualExam(draft.copy(id = first, location = "新教室", note = "带计算器")))
        assertEquals("新教室", db.examDao().get(first)!!.cdmc)
        assertEquals("带计算器", db.examDao().get(first)!!.jkjsbz)
        repo.deleteManualExam(first)
        assertNull(db.examDao().get(first))
        assertEquals(second, db.examDao().getAll().single().id)
    }

    @Test fun importedRecordsAndMissingIdsCannotBeEditedOrDeleted() = runBlocking {
        repo.replaceImportedExams(listOf(imported))
        val row = db.examDao().getAll().single()
        assertTrue(runCatching { repo.saveManualExam(draft.copy(id = row.id)) }.isFailure)
        assertTrue(runCatching { repo.deleteManualExam(row.id) }.isFailure)
        assertTrue(runCatching { repo.saveManualExam(draft.copy(id = row.id + 99)) }.isFailure)
        assertTrue(runCatching { repo.saveManualExam(draft.copy(name = " ")) }.isFailure)
        assertEquals(row, db.examDao().getAll().single())
    }

    @Test fun refreshAndCacheClearPreserveManualData() = runBlocking {
        val id = repo.saveManualExam(draft)
        val manual = db.examDao().get(id)
        repo.replaceImportedExams(listOf(imported))
        val oldImportId = db.examDao().getAll().single { !it.isManual }.id
        repo.replaceImportedExams(listOf(imported.copy(cdmc = "新考场")))
        assertEquals(manual, db.examDao().get(id))
        assertNull(db.examDao().get(oldImportId))
        assertEquals("新考场", db.examDao().getAll().single { !it.isManual }.cdmc)
        // 同一接口用于成功空刷新和“清除成绩缓存”。
        repo.replaceImportedExams(emptyList())
        assertEquals(listOf(manual), db.examDao().getAll())
    }

    @Test fun failedRefreshRollsBackWithoutTouchingManualData() = runBlocking {
        repo.saveManualExam(draft)
        repo.replaceImportedExams(listOf(imported))
        val before = db.examDao().getAll()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_import BEFORE INSERT ON exam WHEN NEW.source = 0 BEGIN SELECT RAISE(ABORT, 'test'); END")
        assertTrue(runCatching { repo.replaceImportedExams(listOf(imported)) }.isFailure)
        assertEquals(before, db.examDao().getAll())
    }

    @Test fun concurrentSyncAndManualChangesKeepBothSourcesIsolated() = runBlocking {
        val id = repo.saveManualExam(draft)
        listOf(
            async(Dispatchers.Default) { repeat(5) { repo.replaceImportedExams(listOf(imported)) } },
            async(Dispatchers.Default) { repeat(5) { repo.saveManualExam(draft.copy(id = id, location = "考场$it")) } },
        ).awaitAll()
        assertEquals(2, db.examDao().getAll().size)
        assertEquals("考场4", db.examDao().get(id)!!.cdmc)
        listOf(
            async(Dispatchers.Default) { repo.replaceImportedExams(emptyList()) },
            async(Dispatchers.Default) { repo.deleteManualExam(id) },
        ).awaitAll()
        assertTrue(db.examDao().getAll().isEmpty())
    }
}
