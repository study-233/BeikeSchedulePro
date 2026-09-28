package com.caeamer.beikeschedule.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.util.Locale

@Composable
fun AppUpdateHost(viewModel: AppUpdateViewModel, blocked: Boolean) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var awaitingPermission by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(lifecycle, viewModel) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.observeForeground() }
    }

    val launchInstaller: (Intent) -> Unit = { intent ->
        try { context.startActivity(intent) }
        catch (_: Exception) { viewModel.installationUnavailable("无法打开系统安装器，可前往发布网页下载安装") }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val resume = awaitingPermission
        awaitingPermission = false
        if (resume && context.packageManager.canRequestPackageInstalls()) {
            viewModel.prepareInstallation()
        } else if (resume) {
            viewModel.installationUnavailable("尚未允许安装此来源的应用，可点击立即安装重试")
        }
    }
    // 由当前 Activity 在前台消费安装请求，避免校验期间旋转或切后台后拉起旧 Activity。
    LaunchedEffect(lifecycle, viewModel, blocked) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.installIntent.collect { intent ->
                if (intent == null || blocked) return@collect
                viewModel.consumeInstallIntent()
                if (context.packageManager.canRequestPackageInstalls()) launchInstaller(intent)
                else {
                    try {
                        awaitingPermission = true
                        permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}")))
                    } catch (_: Exception) {
                        awaitingPermission = false
                        viewModel.installationUnavailable("无法打开安装授权页面，请在系统设置中允许此应用安装更新")
                    }
                }
            }
        }
    }
    val openPage: () -> Unit = {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.release?.pageUrl
                ?: "${com.caeamer.beikeschedule.AppInfo.REPO_URL}/releases")))
        } catch (_: Exception) {
            android.widget.Toast.makeText(context, "未找到可打开网页的应用", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // 登录、导入和教务同步优先；状态和下载不依赖弹窗是否显示。
    if (blocked || !state.dialogVisible) return
    if (state.mobileConfirmation) {
        AlertDialog(
            onDismissRequest = viewModel::closeMobileConfirmation,
            title = { Text("使用移动网络下载？") },
            text = { Text("安装包约 ${formatUpdateSize(state.release?.asset?.size ?: 0)}。允许后，本次下载在切换到移动网络时也会继续；漫游时暂停。") },
            confirmButton = { TextButton(onClick = { viewModel.download(true) }) { Text("允许并下载") } },
            dismissButton = { Column {
                TextButton(onClick = { viewModel.download(false) }) { Text("仅 Wi-Fi 下载") }
                TextButton(onClick = viewModel::closeMobileConfirmation) { Text("取消") }
            } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = viewModel::later,
        title = { Text(if (state.release != null) "更新至 v${state.release!!.version}" else "检查更新") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Text(state.summary)
                state.release?.let { release ->
                    release.asset?.let { Text("安装包：${formatUpdateSize(it.size)}") }
                    if (release.asset == null) Text("此版本没有唯一可用的 APK 附件，请前往发布网页下载。")
                    if (release.notes.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(release.notes)
                    }
                }
                if (state.phase == UpdatePhase.DOWNLOADING || state.phase == UpdatePhase.WAITING) {
                    Spacer(Modifier.height(12.dp))
                    if (state.totalBytes > 0) {
                        LinearProgressIndicator(progress = {
                            (state.downloadedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f)
                        }, modifier = Modifier.fillMaxWidth())
                        Text("${formatUpdateSize(state.downloadedBytes)} / ${formatUpdateSize(state.totalBytes)}")
                    } else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (state.phase == UpdatePhase.READY && state.message.isNotBlank()) Text(state.message)
            }
        },
        confirmButton = {
            when (state.phase) {
                UpdatePhase.AVAILABLE -> TextButton(onClick = if (state.release?.asset != null) viewModel::requestDownload else openPage,
                    enabled = !state.busy) { Text(if (state.release?.asset != null) "立即更新" else "前往下载") }
                UpdatePhase.READY -> TextButton(onClick = viewModel::prepareInstallation, enabled = !state.busy) { Text("立即安装") }
                UpdatePhase.FAILED -> TextButton(onClick = viewModel::retry,
                    enabled = !state.busy) { Text("重试") }
                UpdatePhase.DOWNLOADING, UpdatePhase.WAITING -> TextButton(onClick = viewModel::cancelDownload,
                    enabled = !state.busy) { Text("取消下载") }
                UpdatePhase.IDLE, UpdatePhase.LATEST -> TextButton(onClick = viewModel::later) { Text("关闭") }
                else -> Unit
            }
        },
        dismissButton = {
            Column {
                if (state.phase !in setOf(UpdatePhase.IDLE, UpdatePhase.LATEST)) {
                    TextButton(onClick = viewModel::later) { Text(if (state.hasDownload) "稍后处理" else "稍后提醒") }
                }
                if (state.phase == UpdatePhase.AVAILABLE) {
                    TextButton(onClick = viewModel::ignoreVersion) { Text("忽略此版本") }
                }
                if (state.phase == UpdatePhase.FAILED || state.phase == UpdatePhase.READY) {
                    TextButton(onClick = openPage) { Text("前往发布网页") }
                }
            }
        },
    )
}

private fun formatUpdateSize(bytes: Long): String = String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
