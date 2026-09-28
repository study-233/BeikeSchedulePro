package com.caeamer.beikeschedule.ui.grades

import com.caeamer.beikeschedule.data.local.GradeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 加权成绩学年筛选逻辑单测。 */
class SchoolYearFilterTest {

    private fun grade(xnxqmc: String, kcxz: String = "必修", score: String = "90", xf: Double = 3.0) =
        GradeEntity(id = 0, kcdm = "k$xnxqmc", kcmc = xnxqmc, xnxq = xnxqmc.replace("-", ""),
            xnxqmc = xnxqmc, kcxz = kcxz, kclb = "通识", xf = xf, zzcj = score, bkcx = "正考",
            yxmc = "院", sffx = false)

    private val state = GradesUiState(
        grades = listOf(
            grade("2024-2025-1"), grade("2024-2025-2"),
            grade("2024-2025-3", score = "85"), // 小学期
            grade("2023-2024-1", score = "80"), grade("2023-2024-2", score = "70"),
            grade("2022-2023-1", score = "95"),
        ),
    )

    @Test
    fun `学年候选 - 前4位聚合且小学期单独一组`() {
        assertEquals(
            listOf("2024-2025", "2023-2024", "2022-2023", "小学期"),
            state.schoolYears,
        )
    }

    @Test
    fun `学年筛选 - 只算该学年1和2学期`() {
        val filtered = state.copy(schoolYearFilter = "2024-2025")
        val r = filtered.weightedResult
        requireNotNull(r)
        // 2024-2025-1(90) + 2(90)，都100；-3(85) 不参与
        assertEquals(90.0, r.score, 0.01)
        assertEquals(2, r.courseCount)
    }

    @Test
    fun `小学期筛选 - 只看3学期`() {
        val filtered = state.copy(schoolYearFilter = "小学期")
        val r = filtered.weightedResult
        requireNotNull(r)
        assertEquals(85.0, r.score, 0.01)
        assertEquals(1, r.courseCount)
    }

    @Test
    fun `全部学年 - 含小学期`() {
        val r = state.weightedResult
        requireNotNull(r)
        // 全部学期（含-3）：(90+90+85+80+70+95)/6 = 85
        assertEquals(85.0, r.score, 0.01)
        assertEquals(6, r.courseCount)
    }

    @Test
    fun `无匹配学年返回 null`() {
        assertNull(state.copy(schoolYearFilter = "1999-2000").weightedResult)
    }
    @Test
    fun `成绩列表与筛选一致且保留原始考试行`() {
        val duplicate = grade("2024-2025-1").copy(bkcx = "补考", zzcj = "95")
        val filtered = state.copy(grades = state.grades + duplicate, semesterFilter = "2024-2025-1")
        assertEquals(listOf("2024-2025-1"), filtered.grouped.map { it.first })
        assertEquals(2, filtered.grouped.single().second.size)
        assertEquals(6, state.grouped.size)
    }

}
