package com.caeamer.beikeschedule

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.caeamer.beikeschedule.data.local.ScheduleEntity
import com.caeamer.beikeschedule.import.ImportPreview
import com.caeamer.beikeschedule.import.ImportUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ImportTargetUiTest {
    @get:Rule val compose = createComposeRule()
    private val target = ScheduleEntity(1, "旧课表", 0, xn = "2025-2026", xq = "2", semesterName = "春季")
    private val preview = ImportUiState.Preview("秋季", "2026-2027", "1", "2026-09-07", emptyList(), 20,
        emptyList(), emptyList(), newName = "秋季", nameInitialized = true)

    @Test fun updateRequiresTargetAndDifferentSemesterConfirmation() {
        var state by mutableStateOf(preview)
        val confirmations = mutableListOf<Boolean>()
        compose.setContent { MaterialTheme {
            ImportPreview(state, listOf(target), { confirmations += it },
                { create, id -> state = state.copy(createNew = create, targetId = id) },
                { state = state.copy(newName = it) }, {})
        } }
        compose.onNodeWithText("更新已有课表").performScrollTo().performClick()
        compose.onNodeWithText("确认导入").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("旧课表 · 春季").performScrollTo().performClick()
        compose.onNodeWithText("确认导入").performScrollTo().performClick()
        compose.onNodeWithText("跨学期更新课表？").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertTrue(confirmations.isEmpty()) }
        compose.onNodeWithText("确认导入").performScrollTo().performClick()
        compose.onNodeWithText("确认更新").performClick()
        compose.runOnIdle { assertEquals(listOf(true), confirmations) }
    }

    @Test fun missingTargetDisablesCommitAndInputSurvivesRestore() {
        val restoration = StateRestorationTester(compose)
        var targets by mutableStateOf(listOf(target))
        restoration.setContent {
            var create by rememberSaveable { mutableStateOf(true) }
            var selected by rememberSaveable { mutableStateOf<Long?>(null) }
            var name by rememberSaveable { mutableStateOf("秋季") }
            MaterialTheme {
                ImportPreview(preview.copy(createNew = create, targetId = selected, newName = name), targets, {},
                    { new, id -> create = new; selected = id }, { name = it }, {})
            }
        }
        compose.onNodeWithText("课表名称").performTextReplacement("自定义名称")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("自定义名称").assertIsDisplayed()
        compose.onNodeWithText("更新已有课表").performScrollTo().performClick()
        compose.onNodeWithText("旧课表 · 春季").performScrollTo().performClick()
        compose.runOnIdle { targets = listOf(target.copy(id = 2, name = "新目标")) }
        compose.onNodeWithText("目标课表已删除，请重新选择").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("确认导入").performScrollTo().assertIsNotEnabled()
    }
}
