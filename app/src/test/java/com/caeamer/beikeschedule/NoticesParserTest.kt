package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.import.parser.NoticesParser
import org.junit.Assert.*
import org.junit.Test

class NoticesParserTest {
    private val fixture get() = requireNotNull(javaClass.getResource("/notices-page.json")).readText()

    @Test fun `公告按服务端顺序解析且忽略无关字段`() {
        val page = NoticesParser.parse(fixture, 1)
        assertEquals(4008, page.total)
        assertEquals(2, page.nextPage)
        assertTrue(page.hasNext)
        assertEquals(listOf("notice-a", "notice-b"), page.rows.map { it.id })
        assertTrue(page.rows.first().external)
        assertEquals("2026-09-24 11:25:00", page.rows.first().publishedAt)
        assertEquals("", page.rows.last().url)
    }
    @Test fun `合法空列表与失败响应明确区分`() {
        val page = NoticesParser.parse("""{"total":0,"list":[],"pageNum":1,"nextPage":0,"hasNextPage":false}""", 1)
        assertTrue(page.rows.isEmpty())
        assertFalse(page.hasNext)
        listOf("<html>登录</html>", "{}", """{"code":500}""").forEach {
            assertTrue(runCatching { NoticesParser.parse(it, 1) }.isFailure)
        }
        assertTrue(runCatching { NoticesParser.parse(fixture, 2) }.isFailure)
        assertTrue(runCatching { NoticesParser.parse(fixture.replace("\"nextPage\": 2", "\"nextPage\": 1"), 1) }.isFailure)
    }
    @Test fun `原文仅允许有主机的HTTP链接`() {
        listOf("javascript:alert(1)", "file:///secret", "intent://example", "https://", "/internal", "https://user:pass@example.org/a", "").forEach {
            assertNull(NoticesParser.safeUrl(it))
        }
        assertEquals("https://jwc.ustb.edu.cn/tztg/example.htm", NoticesParser.safeUrl("https://jwc.ustb.edu.cn/tztg/example.htm"))
    }
}
