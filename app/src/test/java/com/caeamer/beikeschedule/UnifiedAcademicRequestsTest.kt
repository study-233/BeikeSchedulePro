package com.caeamer.beikeschedule.import

import com.caeamer.beikeschedule.data.local.ScheduleEntity
import org.junit.Assert.*
import org.junit.Test

class UnifiedAcademicRequestsTest {
    @Test fun `全量同步启动所有任务且活跃请求去重`() {
        val requests = AcademicRequests()
        requests.beginAll()
        val ids = AcademicTask.entries.associateWith { requests.launch(it, 10)!! }
        requests.beginAll()
        AcademicTask.entries.forEach {
            assertNull(requests.launch(it, 20))
            assertTrue(requests.accepts(it, ids.getValue(it)))
        }
        requests.finish(AcademicTask.GPA, ids.getValue(AcademicTask.GPA), "失败", true)
        requests.begin(AcademicTask.GPA)
        assertNotEquals(ids.getValue(AcademicTask.GPA), requests.launch(AcademicTask.GPA, 30))
        assertTrue(requests.accepts(AcademicTask.EXAMS, ids.getValue(AcademicTask.EXAMS)))
    }
    @Test fun `课表等待选择不超时且必须提交后才能完成`() {
        val requests = AcademicRequests()
        requests.beginAll()
        val id = requests.launch(AcademicTask.IMPORT, 0)!!
        requests.saving(AcademicTask.IMPORT, id)
        assertEquals(AcademicPhase.SAVING, requests[AcademicTask.IMPORT]?.phase)
        requests.review(id)
        assertFalse(requests.expired(AcademicTask.IMPORT, id, 999999))
        assertFalse(requests[AcademicTask.IMPORT]!!.needsBrowser)
        requests.saving(AcademicTask.IMPORT, id)
        requests.finish(AcademicTask.IMPORT, id, "保存失败", true)
        requests.retryImportSave(id)
        assertTrue(requests.saving(AcademicTask.IMPORT, id))
        requests.cancel(AcademicTask.IMPORT)
        requests.retryImportSave(id)
        assertFalse(requests.saving(AcademicTask.IMPORT, id))
    }
    @Test fun `重建公告请求保留截止时间并丢弃旧回调 清理后禁止写入`() {
        val requests = AcademicRequests()
        requests.begin(AcademicTask.NOTICES)
        val old = requests.launch(AcademicTask.NOTICES, 100)!!
        requests.recoverBrowser()
        val fresh = requests.launch(AcademicTask.NOTICES, 200)!!
        assertFalse(requests.accepts(AcademicTask.NOTICES, old))
        assertEquals(90100L, requests[AcademicTask.NOTICES]?.deadline)
        requests.cancel(AcademicTask.NOTICES)
        assertFalse(requests.saving(AcademicTask.NOTICES, fresh))
    }
    @Test fun `课表匹配使用完整学期而不是名称或当前选择`() {
        val first = ScheduleEntity(id = 1, name = "我的课表", createdAt = 0, xn = "2026-2027", xq = "1")
        val other = first.copy(id = 2, xq = "2")
        assertTrue(ImportViewModel.matchingSchedules(listOf(other), "2026-2027", "1").isEmpty())
        assertEquals(listOf(first), ImportViewModel.matchingSchedules(listOf(first, other), "2026-2027", "1"))
        assertEquals(2, ImportViewModel.matchingSchedules(listOf(first, first.copy(id = 3)), "2026-2027", "1").size)
        assertTrue(ImportViewModel.matchingSchedules(listOf(first), "", "").isEmpty())
    }
}
