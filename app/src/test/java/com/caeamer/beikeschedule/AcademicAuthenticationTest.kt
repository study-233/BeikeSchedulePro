package com.caeamer.beikeschedule.import

import org.junit.Assert.*
import org.junit.Test

class AcademicAuthenticationTest {
    @Test fun `检查和自动跳转不展示网页 等待用户登录或兜底才展示`() {
        val request = AcademicRequest(1, AcademicPhase.WAITING)
        val state = AcademicSessionState(gradesRequest = request, foregroundTask = AcademicTask.GRADES)
        for (phase in AcademicBrowserPhase.entries) {
            assertEquals(phase == AcademicBrowserPhase.LOGIN || phase == AcademicBrowserPhase.MANUAL,
                state.copy(browserPhase = phase).browserVisible)
        }
        assertFalse(state.copy(browserPhase = AcademicBrowserPhase.LOGIN, foregroundTask = null).browserVisible)
        assertFalse(state.copy(browserPhase = AcademicBrowserPhase.LOGIN,
            gradesRequest = request.copy(phase = AcademicPhase.CANCELLED)).browserVisible)
    }

    @Test fun `失效后仅续接网络任务 不重复提交成功和正在保存的项目`() {
        val requests = AcademicRequests()
        requests.beginAll()
        val ids = AcademicTask.entries.associateWith { requests.launch(it, 100)!! }
        requests.finish(AcademicTask.GPA, ids.getValue(AcademicTask.GPA))
        requests.saving(AcademicTask.STUDENT, ids.getValue(AcademicTask.STUDENT))
        requests.review(ids.getValue(AcademicTask.IMPORT))
        assertTrue(requests.pauseForAuthentication(10_100))
        assertNull(requests.launch(AcademicTask.GPA, 200_000))
        assertNull(requests.launch(AcademicTask.STUDENT, 200_000))
        assertNull(requests.launch(AcademicTask.IMPORT, 200_000))
        assertTrue(requests.isSaving(AcademicTask.STUDENT, ids.getValue(AcademicTask.STUDENT)))
        assertFalse(requests.accepts(AcademicTask.GRADES, ids.getValue(AcademicTask.GRADES)))
        assertFalse(requests.finish(AcademicTask.GRADES, ids.getValue(AcademicTask.GRADES), failed = true))
        assertNull(requests[AcademicTask.GRADES]?.deadline)
        // 长时间登录和配置重建不消耗、也不重置剩余的 80 秒网络预算。
        requests.recoverBrowser()
        val fresh = requests.launch(AcademicTask.GRADES, 200_000)!!
        assertEquals(280_000L, requests[AcademicTask.GRADES]?.deadline)
        assertFalse(requests.pauseForAuthentication(200_010))
        assertTrue(requests.accepts(AcademicTask.GRADES, fresh))
    }

    @Test fun `取消登录废弃等待任务 显式重新刷新恢复一次续接额度`() {
        val requests = AcademicRequests()
        requests.beginLogin(false)
        val old = requests.launch(AcademicTask.GRADES, 0)!!
        assertTrue(requests.pauseForAuthentication(100))
        requests.cancel(AcademicTask.GRADES)
        assertNull(requests.launch(AcademicTask.GRADES, 200))
        assertFalse(requests.accepts(AcademicTask.GRADES, old))
        requests.beginLogin(false)
        requests.launch(AcademicTask.GRADES, 300)
        assertTrue(requests.pauseForAuthentication(400))
    }
}
