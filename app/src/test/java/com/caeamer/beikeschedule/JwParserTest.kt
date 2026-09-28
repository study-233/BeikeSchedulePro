package com.caeamer.beikeschedule.import.parser

import com.caeamer.beikeschedule.data.local.CourseEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析器单测。fixture 为 2026-08-27 用真实登录会话抓取的接口返回，
 * 原始文件见 docs/samples/。
 */
class JwParserTest {

    private fun loadFixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource(name)) { "缺少 fixture: $name" }
            .readText(Charsets.UTF_8)

    @Test
    fun `解析学期总课表 fixture - 条目数与字段正确`() {
        val courses = JwParser.parseCourses(loadFixture("queryxszykbzong-2026-2027-1.json"))

        assertEquals(32, courses.size)

        // 有固定时间的课程块：路基路面工程 星期二 第1-2节
        val first = courses.first { it.name == "路基路面工程" }
        assertEquals("2026-2027-1-72703061-001", first.taskId)
        assertEquals(2, first.dayOfWeek)
        assertEquals(1, first.startSection)
        assertEquals(2, first.endSection)
        assertEquals("苗英豪", first.teacher)
        assertEquals("【校本部】土木楼1018", first.location)
        assertEquals(CourseEntity.SOURCE_IMPORT, first.source)
        assertTrue(first.hasClassOnWeek(1))
        assertTrue(first.hasClassOnWeek(16))
    }

    @Test
    fun `解析学期总课表 fixture - 周次位图边界正确`() {
        val courses = JwParser.parseCourses(loadFixture("queryxszykbzong-2026-2027-1.json"))

        // 机械设计：1-8 周有课，第 9 周起无课
        val mech = courses.first { it.name == "机械设计" && it.dayOfWeek == 1 }
        assertTrue(mech.hasClassOnWeek(1))
        assertTrue(mech.hasClassOnWeek(8))
        assertTrue(!mech.hasClassOnWeek(9))
        assertTrue(!mech.hasClassOnWeek(20))
    }

    @Test
    fun `解析学期总课表 fixture - 无固定时间课程单独归类`() {
        val courses = JwParser.parseCourses(loadFixture("queryxszykbzong-2026-2027-1.json"))

        val unscheduled = courses.filter { it.isUnscheduled }
        assertTrue(unscheduled.isNotEmpty())
        unscheduled.forEach {
            assertEquals(0, it.dayOfWeek)
            assertEquals(CourseEntity.COLOR_UNSCHEDULED, it.colorIndex)
        }
        // 单行文本格式：电子技术实验 [1-16周] 木春梅 备注:无
        val exp = unscheduled.first { it.name == "电子技术实验" }
        assertEquals("木春梅", exp.teacher)
    }

    @Test
    fun `解析节次时间 fixture - 北科每天 13 小节`() {
        val sections = JwParser.parseSectionTimes(loadFixture("queryKbjg-section-times.json"))

        assertEquals(13, sections.size)
        assertEquals(1, sections.first().section)
        assertEquals("08:00", sections.first().startTime)
        assertEquals("08:45", sections.first().endTime)
        assertEquals(13, sections.last().section)
    }

    @Test
    fun `KEY 解析 - 星期提取`() {
        assertEquals(1, JwParser.parseDayOfWeek("xq1_jc3"))
        assertEquals(2, JwParser.parseDayOfWeek("xq2_jc1"))
        assertEquals(7, JwParser.parseDayOfWeek("xq7_jc5"))
    }

    @Test
    fun `单周调课行 - 无KSJC从KEY的jc段推导小节`() {
        // 教务"单周调课行"真实结构：KSJC/JSJC 为 null，地点写"-"，只有 KEY 标明调到哪一大节
        val json = """
            [
              {"RWH":"2026-2027-1-4040016-003","KEY":"xq3_jc1","KSJC":null,"JSJC":null,
               "SKSJ":"机电传动控制\n韩天\n8周\n【校本部】-","ZC":"0000000010000000000000000000000000","XB":5},
              {"RWH":"2026-2027-1-4040016-003","KEY":"xq3_jc2","KSJC":null,"JSJC":null,
               "SKSJ":"机电传动控制\n韩天\n7周\n【校本部】-","ZC":"0000000100000000000000000000000000","XB":5},
              {"RWH":"r2","KEY":"xq4_jc6","KSJC":null,"JSJC":null,
               "SKSJ":"电子技术实习C\n某某\n9周\n【校本部】-","ZC":"0000000010000000000000000000000000","XB":7}
            ]
        """.trimIndent()
        val courses = JwParser.parseCourses(json)
        assertEquals(3, courses.size)
        // xq3_jc1 → 周三 1-2 节；xq3_jc2 → 周三 3-4 节；xq4_jc6 → 周四 11-12 节
        assertEquals(1 to 2, courses[0].startSection to courses[0].endSection)
        assertEquals(3, courses[0].dayOfWeek)
        assertEquals(3 to 4, courses[1].startSection to courses[1].endSection)
        assertEquals(11 to 12, courses[2].startSection to courses[2].endSection)
        assertTrue(courses[0].hasClassOnWeek(8))
    }

    @Test
    fun `KEY 小节段推导 - 各大节边界`() {
        assertEquals(1 to 2, JwParser.keySectionRange("xq3_jc1"))
        assertEquals(9 to 10, JwParser.keySectionRange("xq3_jc5"))
        assertEquals(11 to 12, JwParser.keySectionRange("xq3_jc6"))
        assertEquals(null, JwParser.keySectionRange("xq3_jc7"))
        assertEquals(null, JwParser.keySectionRange("bz"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `KEY 解析 - 非法格式抛异常`() {
        JwParser.parseDayOfWeek("bad_key")
    }

    @Test
    fun `SKSJ 拆分 - 多行格式`() {
        val (name, teacher, location) = JwParser.splitSksj(
            "机械设计\n张杰\n1-8周\n【校本部】机械楼720\n第5-6节", unscheduled = false,
        )
        assertEquals("机械设计", name)
        assertEquals("张杰", teacher)
        assertEquals("【校本部】机械楼720", location)
    }

    @Test
    fun `SKSJ 拆分 - 空文本兜底`() {
        val (name, _, _) = JwParser.splitSksj("", unscheduled = false)
        assertEquals("未命名课程", name)
    }

    @Test
    fun `解析学期总课表 fixture - 备注行解析周数与类型`() {
        val courses = JwParser.parseCourses(loadFixture("queryxszykbzong-2026-2027-1.json"))

        // KEY="bz" 的备注行：机械设计 5-7周 【实验】
        val note = courses.first { it.name == "机械设计【实验】" }
        assertTrue(note.isUnscheduled)
        assertTrue(note.hasClassOnWeek(5))
        assertTrue(note.hasClassOnWeek(7))
        assertTrue(!note.hasClassOnWeek(8))

        // 所有条目都应有周次信息（不再有"无周次"）
        assertTrue(courses.none { it.weekBitmap.isEmpty() })
    }

    @Test
    fun `备注行周数解析 - 多种格式`() {
        assertEquals(listOf(1, 2, 3), com.caeamer.beikeschedule.model.WeekUtils.weeksOf(JwParser.parseNoteWeeks("某课 1-3周 【理论】")))
        assertEquals(listOf(15, 16), com.caeamer.beikeschedule.model.WeekUtils.weeksOf(JwParser.parseNoteWeeks("微机原理与应用B 15,16周 【实验】")))
        assertEquals(listOf(8), com.caeamer.beikeschedule.model.WeekUtils.weeksOf(JwParser.parseNoteWeeks("工程数值计算 8周 【上机】")))
        assertEquals("", JwParser.parseNoteWeeks("没有时间信息"))
    }

    @Test
    fun `位图判定 - 越界周返回 false`() {
        // ZC[i] 对应第 i 周，index 0 为占位
        val c = CourseEntity(
            taskId = "", name = "x", teacher = "", location = "",
            dayOfWeek = 1, startSection = 1, endSection = 2,
            weekBitmap = "001", colorIndex = 1, source = CourseEntity.SOURCE_MANUAL,
        )
        assertTrue(!c.hasClassOnWeek(1))
        assertTrue(c.hasClassOnWeek(2))
        assertTrue(!c.hasClassOnWeek(0))
        assertTrue(!c.hasClassOnWeek(33))
    }

    @Test
    fun `解析教学周日历 - 周次与周一映射正确`() {
        val calendar = JwParser.parseWeekCalendar(
            """{"totalWeeks":18,"weeks":[
                {"zc":1,"monday":"2026-09-07"},
                {"zc":2,"monday":"2026-09-14"},
                {"zc":3,"monday":"2026-09-21"},
                {"zc":4,"monday":"2026-10-05"}
            ]}""",
        )
        assertEquals(18, calendar.totalWeeks)
        assertEquals(4, calendar.weekMondays.size)
        assertEquals("2026-09-07", calendar.weekMondays[0])
        assertEquals("2026-10-05", calendar.weekMondays[3]) // 国庆跳周保留官方映射
    }

    @Test
    fun `解析教学周日历 - 中间缺周按前一周加7天补齐`() {
        val calendar = JwParser.parseWeekCalendar(
            """{"totalWeeks":3,"weeks":[{"zc":1,"monday":"2026-09-07"},{"zc":3,"monday":"2026-09-21"}]}""",
        )
        assertEquals(listOf("2026-09-07", "2026-09-14", "2026-09-21"), calendar.weekMondays)
    }

    @Test
    fun `放假日期去重排序并忽略无效条目且不改变跳周`() {
        val calendar = JwParser.parseWeekCalendar(
            """{"totalWeeks":18,"weeks":[
                {"zc":1,"monday":"2026-09-07"},
                {"zc":2,"monday":"2026-09-14"},
                {"zc":3,"monday":"2026-09-21"},
                {"zc":4,"monday":"2026-10-05"}
            ],"holidayDates":["2026-10-07","2026-09-25","2026-10-05","2026-10-06",
                "2026-09-26","2026-09-25",null,"","invalid","2026-02-30",{},123]}""",
        )
        assertEquals(listOf("2026-09-25", "2026-09-26", "2026-10-05", "2026-10-06", "2026-10-07"), calendar.holidayDates)
        assertEquals("2026-10-05", calendar.weekMondays[3])
        assertEquals(4, calendar.weekMondays.size)
    }

    @Test
    fun `旧周历和错误放假字段不推算周末`() {
        for (extra in listOf("", ",\"holidayDates\":null", ",\"holidayDates\":{}")) {
            val calendar = JwParser.parseWeekCalendar(
                """{"totalWeeks":18,"weeks":[{"zc":1,"monday":"2026-09-07"}]$extra}""",
            )
            assertTrue(calendar.holidayDates.isEmpty())
            assertEquals(listOf("2026-09-07"), calendar.weekMondays)
        }
    }

    @Test
    fun `解析教学周日历 - 空数据与非法输入回退空列表`() {
        assertEquals(0, JwParser.parseWeekCalendar("""{"totalWeeks":18,"weeks":[]}""").weekMondays.size)
        assertEquals(18, JwParser.parseWeekCalendar("""{"totalWeeks":18,"weeks":[]}""").totalWeeks)
        assertEquals(0, JwParser.parseWeekCalendar("not json").weekMondays.size)
    }
}
