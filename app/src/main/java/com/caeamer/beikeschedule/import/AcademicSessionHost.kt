package com.caeamer.beikeschedule.import

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.caeamer.beikeschedule.data.repo.AcademicPayload

/** 唯一 WebView 的组合位置不随原生页面/预览切换而改变。Activity 销毁时释放。 */
@Composable
fun AcademicSessionHost(
    session: AcademicSessionViewModel,
    onImportStart: () -> Unit,
    onCloseImport: () -> Unit,
) {
    val state by session.state.collectAsStateWithLifecycle()
    if (state.needsBrowser) key(state.browserRevision) {
        SessionBrowser(session, state, onImportStart, onCloseImport)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionBrowser(
    session: AcademicSessionViewModel,
    state: AcademicSessionState,
    onImportStart: () -> Unit,
    onCloseImport: () -> Unit,
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var ready by remember { mutableStateOf(false) }
    var authPage by remember { mutableStateOf(false) }
    var pageLoading by remember { mutableStateOf(true) }
    var pageMessage by remember { mutableStateOf<String?>(null) }
    val lease = remember { session.attachBrowser() }
    var alive by remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        onDispose { alive = false; session.detachBrowser(lease) }
    }
    val close: () -> Unit = {
        val importing = session.state.value.foregroundTask == AcademicTask.IMPORT
        session.leaveBrowser()
        if (importing) onCloseImport()
    }
    BackHandler(state.browserVisible, close)
    LaunchedEffect(ready, webView, state.importRequest, state.gradesRequest) {
        val browser = webView
        if (ready && browser != null) {
            AcademicTask.entries.forEach { task ->
                val token = session.launchTask(task) ?: return@forEach
                if (task == AcademicTask.IMPORT) onImportStart()
                try {
                    val asset = if (task == AcademicTask.IMPORT) "jw_import.js" else "jw_grades.js"
                    val script = loadAssetScript(context, "import/$asset").replace("__BEIKE_REQUEST_ID__", token.toString())
                    browser.evaluateJavascript(script, null)
                } catch (_: Exception) {
                    session.fail(task, token, "无法启动获取，请重试")
                }
            }
        }
    }
    fun isCurrentBrowser() = alive && session.isCurrentBrowser(lease)
    fun bridge(task: AcademicTask) = ScopedJwBridge(
        accepts = { isCurrentBrowser() && session.accepts(task, it) },
        delegate = { token ->
            if (task == AcademicTask.IMPORT) JwImportBridge(
                onSuccess = { a, b, c, d, e, f -> session.importResult(token, listOf(a, b, c, d, e, f)) },
                onFailure = { session.fail(task, token, it) },
            ) else GradesBridge(
                onResult = { a, b, c, d, e, f, g, h -> session.gradesResult(token, AcademicPayload(a, b, c, d, e, f, g, h)) },
                onFailure = { session.fail(task, token, it) },
            )
        },
    )
    // 隐藏时只占一个不可交互、无无障碍节点的像素区域，JS 网络任务继续执行。
    Column(if (state.browserVisible) Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()
        else Modifier.size(1.dp).alpha(0f).clearAndSetSemantics { }) {
        if (state.browserVisible) {
            TopAppBar(
                title = { Text(if (state.foregroundTask == AcademicTask.IMPORT) "从教务系统导入" else "教务数据") },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = { IconButton(onClick = session::retryBrowser) { Icon(Icons.Default.Refresh, "重新加载教务网页") } },
            )
            Text(
                pageMessage ?: if (state.foregroundTask == AcademicTask.IMPORT) "登录后自动获取课表、成绩与考试" else "登录后自动获取成绩与考试",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium,
            )
            AcademicSyncStatus(state.gradesRequest, session::startGrades)
            if (pageLoading || state.importRequest?.phase == AcademicPhase.RUNNING || state.gradesRequest?.phase == AcademicPhase.RUNNING) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (authPage) JwWechatLoginPanel(webView = webView, onMessage = { pageMessage = it })
        }
        JwWebView(
            bridge = bridge(AcademicTask.IMPORT), bridgeName = "BeikeImport",
            additionalBridges = mapOf("BeikeGrades" to bridge(AcademicTask.GRADES)),
            modifier = if (state.browserVisible) Modifier.fillMaxWidth().weight(1f) else Modifier.size(1.dp),
            interactive = state.browserVisible,
            onCreated = { webView = it },
            onMainPage = { if (isCurrentBrowser()) ready = true },
            onPageStarted = {
                if (isCurrentBrowser()) { ready = false; pageMessage = null; session.pageStarted(lease) }
            },
            onPageError = { if (isCurrentBrowser()) session.pageFailed(it) },
            onPageProgress = { if (isCurrentBrowser()) pageLoading = it < 100 },
            onAuthPageChanged = {
                if (isCurrentBrowser()) {
                    authPage = it
                    if (it && !session.state.value.browserVisible) session.pageFailed("会话已过期，请重新登录后重试")
                }
            },
        )
    }
}

/** 不包含分数，尊重成绩隐私；导入预览、校园和宿主浏览页复用。 */
@Composable
fun AcademicSyncStatus(request: AcademicRequest?, onRetry: () -> Unit) {
    if (request == null || request.phase == AcademicPhase.CANCELLED) return
    val label = when (request.phase) {
        AcademicPhase.WAITING -> "登录后自动获取成绩与考试"
        AcademicPhase.RUNNING, AcademicPhase.SAVING -> "正在获取成绩与考试…"
        AcademicPhase.SUCCESS -> request.message ?: "成绩与考试已更新"
        AcademicPhase.FAILED -> request.message ?: "获取失败，已保留上次数据"
        AcademicPhase.CANCELLED -> return
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = if (request.phase == AcademicPhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (request.phase == AcademicPhase.FAILED || (request.phase == AcademicPhase.SUCCESS && request.message != null)) {
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}
