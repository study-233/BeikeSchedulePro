package com.caeamer.beikeschedule.ui.notices

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.caeamer.beikeschedule.import.*
import com.caeamer.beikeschedule.import.parser.NoticesParser
import com.caeamer.beikeschedule.ui.profile.syncTimeLabel

@Composable
fun NoticesScreen(session: AcademicSessionViewModel, viewModel: NoticesViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync by session.state.collectAsStateWithLifecycle()
    val request = sync[AcademicTask.NOTICES]
    val busy = request?.active == true || sync.clearing
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("通知公告", style = MaterialTheme.typography.titleLarge)
            Text("更新于 ${syncTimeLabel(state.feed?.fetchedAt ?: 0L)} · 教务系统", style = MaterialTheme.typography.bodySmall)
        }
        if (busy || !state.loaded) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (request?.needsBrowser == true) item { Text(sync.browserPhase.label, style = MaterialTheme.typography.bodySmall) }
        val error = request?.takeIf { it.phase == AcademicPhase.FAILED }?.message ?: state.error
        if (error != null) item {
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { session.retry(AcademicTask.NOTICES) }, enabled = !busy) { Text("重试") }
        }
        if (state.loaded && state.rows.isEmpty()) item {
            Text(if (state.feed == null) "同步后可在这里离线查看公告标题，点击公告打开原文。" else "暂无通知公告")
            if (state.feed == null) Button(onClick = { session.startNotices() }, enabled = !busy) { Text("获取公告") }
        }
        items(state.rows, key = { it.id }) { notice ->
            OutlinedCard(Modifier.fillMaxWidth().clickable {
                val url = NoticesParser.safeUrl(notice.url)
                if (url == null) Toast.makeText(context, "该公告暂未提供可打开的原文链接", Toast.LENGTH_SHORT).show()
                else runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    .onFailure { Toast.makeText(context, "未找到可打开网页的应用", Toast.LENGTH_SHORT).show() }
            }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(notice.title, style = MaterialTheme.typography.titleMedium)
                    Text(notice.publishedAt.ifBlank { "发布时间未知" }, style = MaterialTheme.typography.bodySmall)
                    Text(if (notice.url.isBlank()) "暂无原文链接" else "查看原文 ↗", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        state.feed?.let { feed -> item {
            if (feed.hasNext) OutlinedButton(onClick = { session.startNotices(feed.nextPage) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("加载更多") }
            else Text("已显示全部公告", style = MaterialTheme.typography.bodySmall)
        } }
    }
}
