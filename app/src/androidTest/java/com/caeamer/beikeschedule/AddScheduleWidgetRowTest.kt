package com.caeamer.beikeschedule

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.caeamer.beikeschedule.ui.common.AddScheduleWidgetRow
import com.caeamer.beikeschedule.widget.WidgetPinResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AddScheduleWidgetRowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun acceptedRequestWithoutLauncherUiShowsFeedbackAndManualHelpAndCanRetry() {
        var requests = 0
        compose.setContent {
            MaterialTheme {
                Column {
                    AddScheduleWidgetRow {
                        requests++
                        WidgetPinResult.REQUESTED
                    }
                }
            }
        }
        compose.onNodeWithText("添加桌面小组件").performClick()
        compose.onNodeWithText("已向桌面发出添加请求，请在系统界面确认").assertIsDisplayed()
        compose.onNodeWithText("没有弹出？查看手动添加方法").performClick()
        compose.onNodeWithText("如果桌面没有弹出添加界面，请长按桌面空白处，进入小组件列表，找到「贝壳课表」中的「今日课表」并添加。").assertIsDisplayed()
        compose.onNodeWithText("知道了").performClick()
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onNodeWithText("添加桌面小组件").performClick()
        compose.runOnIdle { assertEquals(2, requests) }
    }

    @Test fun unsupportedLauncherShowsManualInstructions() {
        verifyManualInstructions(WidgetPinResult.UNSUPPORTED)
    }

    @Test fun failedRequestShowsManualInstructions() {
        verifyManualInstructions(WidgetPinResult.FAILED)
    }

    private fun verifyManualInstructions(result: WidgetPinResult) {
        compose.setContent {
            MaterialTheme { Column { AddScheduleWidgetRow { result } } }
        }
        compose.onNodeWithText("添加桌面小组件").performClick()
        compose.onNodeWithText("无法快捷添加，请长按桌面空白处，进入小组件列表，找到「贝壳课表」中的「今日课表」并添加。").assertIsDisplayed()
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithText("在桌面查看今日课程").assertIsDisplayed()
    }
}
