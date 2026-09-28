package com.caeamer.beikeschedule

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.caeamer.beikeschedule.model.ExamDraft
import com.caeamer.beikeschedule.ui.grades.ExamEditDialog
import com.caeamer.beikeschedule.ui.grades.ExamListContent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ManualExamUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyNeverFetchedListCanAddWithoutLogin() {
        var draft by mutableStateOf<ExamDraft?>(null)
        var fetched = false
        var saved: ExamDraft? = null
        compose.setContent {
            MaterialTheme {
                ExamListContent(emptyList(), { draft = ExamDraft() }, {}, true, null, 0L, { fetched = true }, {})
                draft?.let { current ->
                    ExamEditDialog(current, false, null, { draft = it }, { saved = draft; draft = null }, {}, { draft = null })
                }
            }
        }
        compose.onNodeWithText("添加考试").performClick()
        compose.onNodeWithTag("exam_name").performTextInput("英语")
        compose.onNodeWithTag("exam_save").performClick()
        compose.runOnIdle {
            assertFalse(fetched)
            assertEquals("英语", saved!!.name)
            assertEquals("", saved!!.date)
        }
    }

    @Test fun editPrefillsAndCancelDoesNotSave() {
        var open by mutableStateOf(true)
        var draft by mutableStateOf(ExamDraft(id = 4, name = "数学", date = "2026-10-02", start = "09:00", end = "11:00"))
        var saves = 0
        compose.setContent { MaterialTheme {
            if (open) ExamEditDialog(draft, false, null, { draft = it }, { saves++ }, {}, { open = false })
        } }
        compose.onNodeWithTag("exam_name").assertTextContains("数学")
        compose.onNodeWithText("日期：2026-10-02").assertExists()
        compose.onNodeWithTag("exam_name").performTextReplacement("物理")
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertFalse(open); assertEquals(0, saves) }
    }

    @Test fun deleteNeedsConfirmation() {
        var deleted = false
        compose.setContent { MaterialTheme {
            ExamEditDialog(ExamDraft(id = 4, name = "数学"), false, null, {}, {}, { deleted = true }, {})
        } }
        compose.onNodeWithText("删除考试").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(deleted) }
        compose.onNodeWithText("保留").performClick()
        compose.runOnIdle { assertFalse(deleted) }
        compose.onNodeWithText("删除考试").performScrollTo().performClick()
        compose.onNodeWithText("删除", substring = false).performClick()
        compose.runOnIdle { assertTrue(deleted) }
    }

    @Test fun savingDisablesResubmissionAndFailureKeepsInput() {
        var draft by mutableStateOf(ExamDraft(name = "数学"))
        var saving by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var saves = 0
        compose.setContent { MaterialTheme {
            ExamEditDialog(draft, saving, error, { draft = it }, { saves++; saving = true }, {}, {})
        } }
        compose.onNodeWithTag("exam_name").performTextReplacement("高等数学")
        compose.onNodeWithTag("exam_save").performClick()
        compose.onNodeWithTag("exam_save").assertIsNotEnabled()
        compose.onNodeWithTag("exam_name").assertIsNotEnabled()
        compose.runOnIdle { saving = false; error = "保存失败，请重试" }
        compose.onNodeWithTag("exam_error").assertTextContains("保存失败，请重试")
        compose.onNodeWithTag("exam_name").assertTextContains("高等数学")
        compose.onNodeWithTag("exam_save").assertIsEnabled()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test fun clearingDateAlsoClearsAndDisablesTimes() {
        var draft by mutableStateOf(ExamDraft(name = "数学", date = "2026-10-02", start = "09:00", end = "11:00"))
        compose.setContent { MaterialTheme { ExamEditDialog(draft, false, null, { draft = it }, {}, {}, {}) } }
        compose.onNodeWithText("清空日期").performClick()
        compose.onNodeWithText("开始时间：待定").assertIsNotEnabled()
        compose.onNodeWithText("结束时间：待定").assertIsNotEnabled()
        compose.runOnIdle { assertEquals("", draft.start); assertEquals("", draft.end) }
    }

    @Test fun onlyManualRowsOfferEditing() {
        val manual = ExamDraft(id = 1, name = "手动数学").toEntity()
        val imported = manual.copy(id = 2, kcmc = "教务英语", source = 0)
        var edited: Long? = null
        compose.setContent { MaterialTheme {
            ExamListContent(listOf(manual, imported), {}, { edited = it.id }, true, null, 1L, {}, {})
        } }
        compose.onNodeWithText("手动数学").performClick()
        compose.runOnIdle { assertEquals(1L, edited) }
        compose.onNode(hasText("教务英语") and hasClickAction()).assertDoesNotExist()
    }
}
