package com.caeamer.beikeschedule.import

import org.junit.Assert.*
import org.junit.Test

class AcademicRequestsTest {
    @Test fun `请求门禁支持按需组合课表与成绩任务`() {
        listOf(true, false).forEach { import ->
            val requests = AcademicRequests()
            requests.beginLogin(import)
            assertNotNull(requests.launch(AcademicTask.GRADES, 0))
            assertEquals(import, requests.launch(AcademicTask.IMPORT, 0) != null)
        }
    }

    @Test fun `有效会话到达主页直接启动 重复主页回调与重复入口不重复启动`() {
        val requests = AcademicRequests()
        requests.beginLogin(true)
        val token = requests.launch(AcademicTask.GRADES, 100)!!
        requests.beginLogin(true)
        assertNull(requests.launch(AcademicTask.GRADES, 200))
        assertTrue(requests.accepts(AcademicTask.GRADES, token))
        assertFalse(requests.accepts(AcademicTask.IMPORT, token))
        assertTrue(requests.saving(AcademicTask.GRADES, token))
        assertFalse(requests.saving(AcademicTask.GRADES, token))
    }

    @Test fun `课表结果与成绩结果独立 预览和返回后成绩继续`() {
        for (failed in listOf(false, true)) {
            val requests = AcademicRequests()
            requests.beginLogin(true)
            val import = requests.launch(AcademicTask.IMPORT, 0)!!
            val grades = requests.launch(AcademicTask.GRADES, 0)!!
            requests.finish(AcademicTask.IMPORT, import, failed = failed)
            val preview = AcademicSessionState(requests[AcademicTask.IMPORT], requests[AcademicTask.GRADES], AcademicTask.IMPORT, browserReady = true)
            assertTrue(preview.needsBrowser)
            assertFalse(preview.browserVisible)
            requests.cancel(AcademicTask.IMPORT)
            assertTrue(requests.accepts(AcademicTask.GRADES, grades))
            assertTrue(preview.copy(foregroundTask = null).needsBrowser)
        }
    }

    @Test fun `成绩失败不终止课表 成绩完成后普通页面变化不重新启动 显式刷新才新建`() {
        val requests = AcademicRequests()
        requests.beginLogin(true)
        val import = requests.launch(AcademicTask.IMPORT, 0)!!
        val grades = requests.launch(AcademicTask.GRADES, 0)!!
        requests.finish(AcademicTask.GRADES, grades, failed = true)
        assertTrue(requests.accepts(AcademicTask.IMPORT, import))
        requests.recoverBrowser()
        assertNull(requests.launch(AcademicTask.GRADES, 20))
        requests.beginLogin(false)
        assertNotEquals(grades, requests.launch(AcademicTask.GRADES, 30))
        assertFalse(requests.accepts(AcademicTask.GRADES, grades))
    }

    @Test fun `配置重建只重启网络任务 废弃旧回调且不延长截止时间`() {
        val requests = AcademicRequests()
        requests.beginLogin(true)
        val import = requests.launch(AcademicTask.IMPORT, 10)!!
        val grades = requests.launch(AcademicTask.GRADES, 10)!!
        requests.finish(AcademicTask.IMPORT, import)
        requests.recoverBrowser()
        assertFalse(requests.accepts(AcademicTask.GRADES, grades))
        val restored = requests.launch(AcademicTask.GRADES, 1000)!!
        assertNotEquals(grades, restored)
        assertEquals(90_010L, requests[AcademicTask.GRADES]?.deadline)
        assertNull(requests.launch(AcademicTask.IMPORT, 1000))
        assertFalse(requests.expired(AcademicTask.GRADES, restored, 90_009))
        assertTrue(requests.expired(AcademicTask.GRADES, restored, 90_010))
        requests.finish(AcademicTask.GRADES, restored, failed = true)
        assertFalse(requests.accepts(AcademicTask.GRADES, restored))
        assertNull(requests.launch(AcademicTask.GRADES, 100_000))
    }

    @Test fun `已完成同步重建时不再获取 手动刷新新建截止时刻`() {
        val requests = AcademicRequests()
        requests.beginLogin(false)
        val original = requests.launch(AcademicTask.GRADES, 0)!!
        requests.saving(AcademicTask.GRADES, original)
        requests.finish(AcademicTask.GRADES, original)
        requests.recoverBrowser()
        assertNull(requests.launch(AcademicTask.GRADES, 1000))
        assertFalse(AcademicSessionState(gradesRequest = requests[AcademicTask.GRADES]).needsBrowser)
        requests.beginLogin(false)
        val fresh = requests.launch(AcademicTask.GRADES, 1000)!!
        assertNotEquals(original, fresh)
        assertEquals(91_000L, requests[AcademicTask.GRADES]?.deadline)
    }

    @Test fun `配置重建不重复落盘 退出登录立即失效 旧失败回调也不能覆盖新请求`() {
        val requests = AcademicRequests()
        requests.beginLogin(true)
        val grades = requests.launch(AcademicTask.GRADES, 0)!!
        requests.saving(AcademicTask.GRADES, grades)
        requests.recoverBrowser()
        assertTrue(requests.isSaving(AcademicTask.GRADES, grades))
        assertNull(requests.launch(AcademicTask.GRADES, 1))
        AcademicTask.entries.forEach(requests::cancel)
        assertFalse(requests.isSaving(AcademicTask.GRADES, grades))
        requests.beginLogin(false)
        val fresh = requests.launch(AcademicTask.GRADES, 10)!!
        assertFalse(requests.finish(AcademicTask.GRADES, grades, failed = true))
        assertFalse(requests.expired(AcademicTask.GRADES, grades, 100_000))
        assertTrue(requests.accepts(AcademicTask.GRADES, fresh))
    }
}
