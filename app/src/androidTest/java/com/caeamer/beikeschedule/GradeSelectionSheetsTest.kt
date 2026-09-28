package com.caeamer.beikeschedule

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.caeamer.beikeschedule.data.local.GradeEntity
import com.caeamer.beikeschedule.ui.grades.GradeCourseSelectorSheet
import com.caeamer.beikeschedule.ui.grades.GradeFilterField
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GradeSelectionSheetsTest {
    @get:Rule val compose = createComposeRule()
    private val grade = GradeEntity(id = 1, kcdm = "course1", kcmc = "通信原理", xnxq = "2025-20261",
        xnxqmc = "2025-2026-1", kcxz = "必修", kclb = "专业课", xf = 4.0, zzcj = "86",
        bkcx = "正考", yxmc = "学院", sffx = false)

    @Test fun tappingCourseNameTogglesOnceAndUpdatesSummary() {
        var included by mutableStateOf(true)
        val calls = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                GradeCourseSelectorSheet(listOf(grade to included), false, {
                    calls += it
                    included = !included
                }, {})
            }
        }
        compose.onNodeWithText("通信原理").performClick()
        compose.onNodeWithText("已纳入 0 / 1 门 · 勾选后立即重算").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("course1"), calls) }
    }

    @Test fun hidingScoresAlsoHidesCreditsAndCountInOpenSelector() {
        var hide by mutableStateOf(false)
        compose.setContent { MaterialTheme { GradeCourseSelectorSheet(listOf(grade to true), hide, {}, {}) } }
        compose.onNodeWithText("4.0 学分 · 86 分").assertIsDisplayed()
        compose.runOnIdle { hide = true }
        compose.onNodeWithText("4.0 学分 · 86 分").assertDoesNotExist()
        compose.onNodeWithText("已纳入 1 / 1 门 · 勾选后立即重算").assertDoesNotExist()
        compose.onNodeWithText("学分与成绩已隐藏").assertIsDisplayed()
    }

    @Test fun choosingSemesterUsesExistingCallbackAndDismissesSheet() {
        var chosen: String? = null
        compose.setContent {
            MaterialTheme {
                GradeFilterField("全部学期", "全部学年",
                    listOf("" to "全部学期", "2025-2026-1" to "2025-2026-1"),
                    listOf("" to "全部学年"), "", "", { chosen = it }, {})
            }
        }
        compose.onNodeWithText("全部学年 · 全部学期").performClick()
        compose.onNodeWithText("2025-2026-1").performClick()
        compose.onNodeWithText("按学年或具体学期查看，点选立即应用").assertDoesNotExist()
        compose.runOnIdle { assertEquals("2025-2026-1", chosen) }
    }
}
