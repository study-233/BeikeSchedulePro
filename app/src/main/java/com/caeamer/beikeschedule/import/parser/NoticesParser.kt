package com.caeamer.beikeschedule.import.parser

import com.caeamer.beikeschedule.data.local.NoticeEntity
import org.json.JSONObject
import java.net.URI

data class NoticePage(val rows: List<NoticeEntity>, val page: Int, val nextPage: Int, val hasNext: Boolean, val total: Int)

object NoticesParser {
    fun parse(json: String, requestedPage: Int): NoticePage {
        val root = JSONObject(json)
        val list = requireNotNull(root.optJSONArray("list")) { "公告响应格式异常" }
        val page = root.getInt("pageNum")
        require(page == requestedPage && page > 0) { "公告页码不匹配，请刷新后重试" }
        val hasNext = root.getBoolean("hasNextPage")
        val next = root.getInt("nextPage")
        require(!hasNext || next == page + 1) { "公告分页信息异常" }
        val rows = (0 until list.length()).map { index ->
            val row = list.getJSONObject(index)
            fun text(key: String) = if (row.isNull(key)) "" else row.optString(key).trim()
            val id = text("id")
            val title = text("bt")
            require(id.isNotEmpty() && title.isNotEmpty()) { "公告缺少标题或标识" }
            val external = text("sfwblj") == "1"
            NoticeEntity(id, title, text("fssj"), external,
                safeUrl(if (external) text("wburl") else text("url")).orEmpty(), index)
        }.distinctBy { it.id }
        require(!hasNext || rows.isNotEmpty()) { "公告分页返回空列表，请重试" }
        return NoticePage(rows, page, next, hasNext, root.getInt("total").coerceAtLeast(0))
    }

    fun safeUrl(value: String): String? = runCatching {
        val uri = URI(value)
        value.takeIf { uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null }
    }.getOrNull()
}
