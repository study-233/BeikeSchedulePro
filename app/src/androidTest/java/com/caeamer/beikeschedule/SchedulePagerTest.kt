package com.caeamer.beikeschedule

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.caeamer.beikeschedule.ui.schedule.rememberSchedulePager
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SchedulePagerTest {
    @get:Rule val compose = createComposeRule()

    @Composable
    private fun Content(
        loaded: Boolean,
        week: Int,
        hasCourses: Boolean = true,
        onWeekSelected: (Int) -> Unit = {},
    ) {
        val pager = rememberSchedulePager(loaded, week, 20, onWeekSelected)
        // 与课表一致：未就绪及无课程时均不挂载 Pager。
        when {
            !pager.ready -> Text("加载中")
            !hasCourses -> Text("空课表")
            else -> HorizontalPager(pager.state, Modifier.fillMaxSize().testTag("weeks")) { page ->
                Box(Modifier.fillMaxSize()) { Text("第${page + 1}周") }
            }
        }
    }

    @Test fun asyncLoadLocatesCurrentWeekWithoutWaitingForPagerLayout() {
        var loaded by mutableStateOf(false)
        var week by mutableStateOf(1)
        compose.setContent { Content(loaded, week, onWeekSelected = { week = it }) }
        compose.onNodeWithText("加载中").assertIsDisplayed()
        compose.runOnIdle { week = 3; loaded = true }
        compose.onNodeWithText("加载中").assertDoesNotExist()
        compose.onNodeWithText("第3周").assertIsDisplayed()
        compose.runOnIdle { assertEquals(3, week) }
    }

    @Test fun emptyScheduleCanChangeWeekBeforePagerIsMounted() {
        var loaded by mutableStateOf(false)
        var week by mutableStateOf(1)
        var hasCourses by mutableStateOf(false)
        compose.setContent { Content(loaded, week, hasCourses) }
        compose.runOnIdle { week = 3; loaded = true }
        compose.onNodeWithText("空课表").assertIsDisplayed()
        compose.runOnIdle { week = 8 }
        compose.onNodeWithText("空课表").assertIsDisplayed()
        compose.runOnIdle { hasCourses = true }
        compose.onNodeWithText("第8周").assertIsDisplayed()
    }

    @Test fun restoredPagerAcceptsNewSessionWeekBeforeItIsMounted() {
        val restoration = StateRestorationTester(compose)
        var loaded by mutableStateOf(true)
        var week by mutableStateOf(8)
        restoration.setContent { Content(loaded, week, onWeekSelected = { week = it }) }
        compose.onNodeWithText("第8周").assertIsDisplayed()
        compose.runOnIdle { loaded = false; week = 3 }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { loaded = true }
        compose.onNodeWithText("加载中").assertDoesNotExist()
        compose.onNodeWithText("第3周").assertIsDisplayed()
        compose.runOnIdle { assertEquals(3, week) }
    }

    @Test fun swipeAndExternalLocationStillUpdateWeek() {
        var week by mutableStateOf(3)
        compose.setContent { Content(true, week, onWeekSelected = { week = it }) }
        compose.onNodeWithText("第3周").assertIsDisplayed()
        compose.onNodeWithTag("weeks").performTouchInput { swipeLeft() }
        compose.onNodeWithText("第4周").assertIsDisplayed()
        compose.runOnIdle { assertEquals(4, week); week = 8 }
        compose.onNodeWithText("第8周").assertIsDisplayed()
        compose.runOnIdle { assertEquals(8, week) }
    }
}
