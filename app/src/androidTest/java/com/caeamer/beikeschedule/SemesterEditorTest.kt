package com.caeamer.beikeschedule

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.model.SemesterDraft
import com.caeamer.beikeschedule.ui.settings.SemesterEditor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SemesterEditorTest {
    @get:Rule val compose = createComposeRule()
    private val original = SettingsStore.SemesterConfig(name = "旧学期", totalWeeks = 20)

    @Test fun cancelDoesNotSave() {
        var writes = 0
        var left = false
        compose.setContent { MaterialTheme { SemesterEditor(original, false, null, { writes++ }, { left = true }) } }
        compose.onNodeWithText("旧学期").performTextReplacement("新学期")
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, writes); assertTrue(left) }
    }

    @Test fun backRequiresDiscardConfirmation() {
        var left = false
        compose.setContent { MaterialTheme { SemesterEditor(original, false, null, {}, { left = true }) } }
        compose.onNodeWithText("旧学期").performTextReplacement("新学期")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("放弃修改？").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        compose.runOnIdle { assertFalse(left) }
        compose.onNodeWithText("新学期").assertIsDisplayed()
    }

    @Test fun restorePreservesDraftAndFailedSaveCanBeRetried() {
        val restoration = StateRestorationTester(compose)
        val writes = mutableListOf<SemesterDraft>()
        var error by mutableStateOf<String?>(null)
        restoration.setContent { MaterialTheme { SemesterEditor(original, false, error, {
            writes += it
            error = "保存失败，请重试"
        }, {}) } }
        compose.onNodeWithText("旧学期").performTextReplacement("待保存学期")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("待保存学期").assertIsDisplayed()
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText("保存失败，请重试").assertIsDisplayed()
        compose.onNodeWithText("待保存学期").assertIsDisplayed()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals(2, writes.size); assertEquals(writes[0], writes[1]) }
    }
}
