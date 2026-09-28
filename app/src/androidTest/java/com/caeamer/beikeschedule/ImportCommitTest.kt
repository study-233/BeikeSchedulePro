package com.caeamer.beikeschedule

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.caeamer.beikeschedule.data.local.AppDatabase
import com.caeamer.beikeschedule.data.local.SectionTimeEntity
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.data.repo.ScheduleRepository
import com.caeamer.beikeschedule.import.ImportUiState
import com.caeamer.beikeschedule.import.ImportViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class ImportCommitTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val store = ViewModelStore()
    private lateinit var db: AppDatabase
    private lateinit var repo: ScheduleRepository

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        repo = ScheduleRepository(db, SettingsStore(app), { listOf(SectionTimeEntity(1, "08:00", "08:45")) },
            { SettingsStore.SemesterConfig() })
    }
    @After fun close() { instrumentation.runOnMainSync { store.clear() }; db.close() }

    private fun preview(after: suspend () -> Unit = {}): ImportViewModel {
        val courses = app.assets.open("sample/courses.json").bufferedReader().use { it.readText() }
        val sections = app.assets.open("sample/sections.json").bufferedReader().use { it.readText() }
        lateinit var vm: ImportViewModel
        instrumentation.runOnMainSync {
            vm = ImportViewModel(app, repo, after)
            store.put("import", vm)
            vm.onFetchResult("""{"XN":"2026-2027","XQ":"1","XNXQ":"秋季"}""", "1", courses, sections, "{}",
                """{"totalWeeks":18,"weeks":[{"zc":1,"monday":"2026-09-07"}],"holidayDates":["2026-09-12"]}""")
            vm.setName("导入测试")
        }
        assertTrue(vm.state.value is ImportUiState.Preview)
        return vm
    }

    @Test fun duplicateConfirmCannotCreateSecondScheduleWhileCommittingOrDone() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val vm = preview { entered.complete(Unit); finish.await() }
        instrumentation.runOnMainSync { vm.confirmImport(); vm.confirmImport() }
        withTimeout(5000) { entered.await() }
        assertEquals(ImportUiState.Committing, vm.state.value)
        assertEquals(2, repo.schedules.first().size)
        instrumentation.runOnMainSync { vm.confirmImport() }
        finish.complete(Unit)
        withTimeout(5000) { vm.state.first { it is ImportUiState.Done } }
        instrumentation.runOnMainSync { vm.confirmImport() }
        assertEquals(2, repo.schedules.first().size)
    }

    @Test fun failedSaveRestoresTargetAndPreviewAndCanRetry() = runBlocking {
        val target = repo.createSchedule("目标")
        val vm = preview()
        instrumentation.runOnMainSync { vm.selectTarget(false, target) }
        val original = vm.state.value as ImportUiState.Preview
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_section BEFORE INSERT ON section_time BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        instrumentation.runOnMainSync { vm.confirmImport() }
        val failed = withTimeout(5000) {
            vm.state.first { it is ImportUiState.Preview && it.saveError != null }
        } as ImportUiState.Preview
        assertEquals(original.targetId, failed.targetId)
        assertEquals(original.newName, failed.newName)
        assertEquals(original.courses, failed.courses)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_section")
        instrumentation.runOnMainSync { vm.confirmImport() }
        withTimeout(5000) { vm.state.first { it is ImportUiState.Done } }
        assertEquals(target, repo.getScheduleSnapshot().scheduleId)
        assertEquals(listOf("2026-09-12"), repo.getScheduleSnapshot().semester.holidayDates)
        assertFalse(repo.getScheduleSnapshot().courses.isEmpty())
    }
}
