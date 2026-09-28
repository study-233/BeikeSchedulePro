package com.caeamer.beikeschedule.import

import org.junit.Assert.*
import org.junit.Test

class SessionProbeFailureTest {
    @Test fun `无法确认会话时保留学校页面入口`() {
        listOf("NETWORK", "REDIRECT", "TIMEOUT", "INVALID_JSON", "UNEXPECTED_HTML", "MISSING_IDENTITY", "CHECK_FAILED").forEach { code ->
            val result = SessionProbeFailure.fromCode(code)
            assertTrue(result.offerSchoolPage)
            assertTrue(result.message.contains("已有数据已保留"))
            assertFalse(result.message.contains("会话已过期"))
        }
    }

    @Test fun `HTTP服务错误不触发自动登录兜底并保留状态码`() {
        listOf("403", "404", "500", "503").forEach { status ->
            val result = SessionProbeFailure.fromCode("HTTP", status)
            assertFalse(result.offerSchoolPage)
            assertTrue(result.message.contains("HTTP $status"))
        }
    }

    @Test fun `不展示桥传入的未知文本或畸形HTTP状态`() {
        val untrusted = "fixture-private-session-url"
        val unknown = SessionProbeFailure.fromCode(untrusted, untrusted)
        assertTrue(unknown.offerSchoolPage)
        assertFalse(unknown.message.contains(untrusted))
        val malformed = SessionProbeFailure.fromCode("HTTP", untrusted)
        assertFalse(malformed.offerSchoolPage)
        assertFalse(malformed.message.contains(untrusted))
    }

    @Test fun `会话检查兜底显示页面但仍不能启动数据任务`() {
        val waiting = AcademicRequest(1, AcademicPhase.WAITING)
        val state = AcademicSessionState(gradesRequest = waiting, foregroundTask = AcademicTask.GRADES,
            browserPhase = AcademicBrowserPhase.MANUAL, browserReady = false)
        assertTrue(state.needsBrowser)
        assertTrue(state.browserVisible)
        assertFalse(state.browserReady)
        assertEquals(AcademicPhase.WAITING, state.gradesRequest?.phase)
    }
}
