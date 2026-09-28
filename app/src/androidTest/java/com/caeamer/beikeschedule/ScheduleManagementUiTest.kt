package com.caeamer.beikeschedule

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.caeamer.beikeschedule.data.local.ScheduleEntity
import com.caeamer.beikeschedule.ui.settings.ScheduleManagementList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ScheduleManagementUiTest {
    @get:Rule val compose = createComposeRule()
    private val original = ScheduleEntity(1, "旧课表", 0)

    @Test fun blankAndDuplicateNamesCannotSaveAndDraftSurvivesRestoration() {
        val restoration = StateRestorationTester(compose)
        val saved = mutableListOf<String>()
        restoration.setContent {
            MaterialTheme {
                ScheduleManagementList(listOf(original), 1, false, null, {}, { _, name, done -> saved += name; done() }, { _, _ -> }, { _, _ -> })
            }
        }
        compose.onNodeWithText("新建课表").performClick()
        compose.onNodeWithText("课表名称").performTextReplacement("  ")
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithText("课表名称").performTextReplacement("旧课表")
        compose.onNodeWithText("已有同名课表，请重新命名").assertIsDisplayed()
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithText("课表名称").performTextReplacement("  春季课表  ")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("  春季课表  ").assertIsDisplayed()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals(listOf("春季课表"), saved) }
    }

    @Test fun cancelDeleteDoesNotWriteAndClearUsesSelectedId() {
        val deleted = mutableListOf<Long>()
        val cleared = mutableListOf<Long>()
        compose.setContent { MaterialTheme {
            ScheduleManagementList(listOf(original), 1, false, null, {}, { _, _, _ -> },
                { id, done -> cleared += id; done() }, { id, done -> deleted += id; done() })
        } }
        compose.onNodeWithText("删除课表").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertTrue(deleted.isEmpty()) }
        compose.onNodeWithText("清空课程").performClick()
        compose.onNodeWithText("清空").performClick()
        compose.runOnIdle { assertEquals(listOf(1L), cleared); assertTrue(deleted.isEmpty()) }
    }

    @Test fun switchingAndRenamingUseStableIdAndFailedSaveKeepsInput() {
        var switched: Long? = null
        var error by mutableStateOf<String?>(null)
        var savedId: Long? = null
        val other = original.copy(id = 2, name = "另一课表")
        compose.setContent { MaterialTheme {
            ScheduleManagementList(listOf(other), 1, false, error, { switched = it }, { id, _, _ ->
                savedId = id; error = "保存失败，请重试"
            }, { _, _ -> }, { _, _ -> })
        } }
        compose.onNodeWithText("切换").performClick()
        compose.onNodeWithText("重命名").performClick()
        compose.onNodeWithText("课表名称").performTextReplacement("调整课表")
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText("调整课表").assertIsDisplayed()
        compose.onNodeWithText("保存失败，请重试").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2L, switched); assertEquals(2L, savedId) }
    }
}
