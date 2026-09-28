package com.caeamer.beikeschedule

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.caeamer.beikeschedule.data.local.AppDatabase
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.data.repo.ScheduleRepository
import com.caeamer.beikeschedule.model.ExamDraft
import com.caeamer.beikeschedule.ui.grades.GradesViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class ManualExamViewModelTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: AppDatabase
    private lateinit var repo: ScheduleRepository
    private val store = ViewModelStore()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        repo = ScheduleRepository(db, SettingsStore(app), { emptyList() })
    }
    @After fun close() {
        runBlocking { withContext(Dispatchers.Main) { store.clear() } }
        db.close()
    }

    private suspend fun model(handle: SavedStateHandle = SavedStateHandle(),
        reschedule: suspend (Set<Long>) -> Unit = {}): GradesViewModel = withContext(Dispatchers.Main) {
        GradesViewModel(app, handle, repo, reschedule).also { store.put("exam", it) }
    }

    @Test fun repeatedSaveWritesOnceEvenWhileRemindersArePending() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val vm = model { entered.complete(Unit); release.await() }
        withContext(Dispatchers.Main) {
            vm.openExamEditor()
            vm.updateExamDraft(ExamDraft(name = "数学"))
            vm.saveExam()
            vm.saveExam()
        }
        try {
            withTimeout(5000) { entered.await() }
            assertEquals(1, db.examDao().getAll().size)
            withContext(Dispatchers.Main) { vm.saveExam(); vm.openExamEditor() }
            assertNull(vm.examEditor.value.draft)
        } finally { release.complete(Unit) }
        withTimeout(5000) { vm.examEditor.first { !it.saving } }
        assertEquals(1, db.examDao().getAll().size)
    }

    @Test fun failedDatabaseWritePreservesDraftAndDoesNotReschedule() = runBlocking {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_exam BEFORE INSERT ON exam BEGIN SELECT RAISE(ABORT, 'test'); END")
        var reminders = 0
        val vm = model { reminders++ }
        val draft = ExamDraft(name = "数学", note = "带计算器")
        withContext(Dispatchers.Main) { vm.openExamEditor(); vm.updateExamDraft(draft); vm.saveExam() }
        withTimeout(5000) { vm.examEditor.first { it.error != null } }
        assertEquals(draft, vm.examEditor.value.draft)
        assertFalse(vm.examEditor.value.saving)
        assertEquals(0, reminders)
        assertTrue(db.examDao().getAll().isEmpty())
    }

    @Test fun reminderFailureClosesEditorAndRetryDoesNotRepeatDatabaseWrite() = runBlocking {
        val id = repo.saveManualExam(ExamDraft(name = "数学"))
        var attempts = 0
        val vm = model { changed ->
            assertEquals(setOf(id), changed)
            attempts++
            if (attempts == 1) error("模拟提醒失败")
        }
        withContext(Dispatchers.Main) { vm.openExamEditor(db.examDao().get(id)); vm.deleteExam() }
        withTimeout(5000) { vm.examEditor.first { !it.saving && it.draft == null } }
        assertTrue(vm.examReminderRetry.value)
        assertNull(db.examDao().get(id))
        withContext(Dispatchers.Main) { vm.retryExamReminders() }
        withTimeout(5000) { vm.examEditor.first { !it.saving } }
        assertFalse(vm.examReminderRetry.value)
        assertEquals(2, attempts)
        assertTrue(db.examDao().getAll().isEmpty())
    }

    @Test fun restoredSavedStateRetainsDraftAndCancelDoesNotWrite() = runBlocking {
        val handle = SavedStateHandle()
        val draft = ExamDraft(name = "数学", date = "2026-10-02", start = "09:00", note = "带计算器")
        val first = model(handle)
        withContext(Dispatchers.Main) { first.openExamEditor(); first.updateExamDraft(draft) }
        val restoredHandle = withContext(Dispatchers.Main) {
            SavedStateHandle(mapOf("examDraft" to handle.get<ArrayList<String>>("examDraft")))
        }
        val restored = model(restoredHandle)
        assertEquals(draft, restored.examEditor.value.draft)
        withContext(Dispatchers.Main) { restored.closeExamEditor() }
        assertNull(restored.examEditor.value.draft)
        assertTrue(db.examDao().getAll().isEmpty())
    }
}
