package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.data.local.ExamEntity
import com.caeamer.beikeschedule.model.hasEnded
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class ExamPresentationTest {
    private val exam = ExamEntity(kcdm = "1", kcmc = "课程", kslx = "期末", kssjms = "", ksrq = "2026-09-27",
        kssj = "08:00", jssj = "09:50", cdmc = "教学楼301", zwh = "1", jkjsbz = "", kkyxmc = "", xnxq = "")
    @Test fun `当天结束的考试归入历史`() {
        assertFalse(exam.hasEnded(LocalDateTime.parse("2026-09-27T09:00")))
        assertTrue(exam.hasEnded(LocalDateTime.parse("2026-09-27T10:00")))
    }
    @Test fun `未知结束时间保留至当天结束未知日期不误判`() {
        assertFalse(exam.copy(jssj = "").hasEnded(LocalDateTime.parse("2026-09-27T23:00")))
        assertTrue(exam.copy(jssj = "").hasEnded(LocalDateTime.parse("2026-09-28T00:00")))
        assertFalse(exam.copy(ksrq = "").hasEnded(LocalDateTime.parse("2026-09-28T00:00")))
    }
}
