package com.caeamer.beikeschedule.ui.profile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.caeamer.beikeschedule.AppInfo
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.import.clearJwSession
import com.caeamer.beikeschedule.ui.settings.SettingsViewModel
import com.caeamer.beikeschedule.ui.settings.UpdateState

import com.caeamer.beikeschedule.model.SettingsPage
import com.caeamer.beikeschedule.ui.common.AddScheduleWidgetRow
import com.caeamer.beikeschedule.ui.common.PageHeader
import com.caeamer.beikeschedule.ui.common.SettingsGroup
import com.caeamer.beikeschedule.ui.common.SettingsRow
import androidx.activity.compose.BackHandler

/** 我的：紧凑身份摘要和分组设置；二级页交由应用宿主管理。 */
@Composable
fun ProfileScreen(onSettings: (SettingsPage) -> Unit, onAcademic: () -> Unit,
                  onClearSample: () -> Unit, hasSample: Boolean,
                  viewModel: SettingsViewModel = viewModel()) {
    val academicSession: com.caeamer.beikeschedule.import.AcademicSessionViewModel = viewModel()
    val openAcademic = { academicSession.startGrades(); onAcademic() }
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val update by viewModel.update.collectAsStateWithLifecycle()
    val studentProfile by viewModel.studentProfile.collectAsStateWithLifecycle()
    val appVersion by viewModel.appVersion.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showUpdateDialog by rememberSaveable { mutableStateOf(false) }
    var showClearCacheConfirm by rememberSaveable { mutableStateOf(false) }
    var showLogoutConfirm by rememberSaveable { mutableStateOf(false) }
    var showThemeDialog by rememberSaveable { mutableStateOf(false) }
    var showClearSample by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PageHeader("我的")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SettingsGroup("学籍") {
                SettingsRow(
                    if (studentProfile.isLoggedIn) studentProfile.xm.ifBlank { "学籍信息" } else "获取学籍信息",
                    if (studentProfile.isLoggedIn) listOf(studentProfile.xh, studentProfile.zymc).filter { it.isNotBlank() }.joinToString(" · ")
                    else "登录教务系统并获取成绩后显示",
                    { if (studentProfile.isLoggedIn) onSettings(SettingsPage.STUDENT) else openAcademic() },
                )
            }
            SettingsGroup("课表") {
                SettingsRow("课表管理", onClick = { onSettings(SettingsPage.MANAGE) })
                SettingsRow("学期与校历", onClick = { onSettings(SettingsPage.SEMESTER) })
                SettingsRow("课表显示", "字号、背景与课程显示", { onSettings(SettingsPage.DISPLAY) })
                AddScheduleWidgetRow()
                SettingsRow("上课提醒", onClick = { onSettings(SettingsPage.REMINDER) })
                SettingsRow("隐藏课程", onClick = { onSettings(SettingsPage.HIDDEN) })
                if (hasSample) SettingsRow("清除示例课表", onClick = { showClearSample = true }, destructive = true)
            }
            SettingsGroup("外观") {
                SettingsRow("主题", when (themeMode) {
                    SettingsStore.ThemeMode.SYSTEM -> "跟随系统"
                    SettingsStore.ThemeMode.LIGHT -> "浅色"
                    SettingsStore.ThemeMode.DARK -> "深色"
                }, { showThemeDialog = true })
            }
            SettingsGroup("账号与数据") {
                SettingsRow("教务账号", "登录后自动获取成绩与考试", openAcademic)
                SettingsRow("清除成绩缓存", "成绩、GPA、教务考试与学业进度；保留手动考试", { showClearCacheConfirm = true }, destructive = true)
                SettingsRow("退出教务登录", "保留本地课表与成绩", { showLogoutConfirm = true }, destructive = true)
            }
            SettingsGroup("关于") {
                SettingsRow("检查更新", when (val u = update) {
                    is UpdateState.Checking -> "正在检查…"
                    is UpdateState.UpToDate -> "已是最新版本"
                    is UpdateState.Available -> "发现新版本 v${u.latestVersion}"
                    is UpdateState.Failed -> u.message
                    UpdateState.Idle -> "检查 GitHub Releases"
                }, { if (update is UpdateState.Available) showUpdateDialog = true else viewModel.checkUpdate() })
                SettingsRow("版本", appVersion)
                SettingsRow("项目仓库", "查看源码", {
                    context.openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(AppInfo.REPO_URL)), "未找到可打开网页的应用")
                })
                SettingsRow("联系开发者", AppInfo.CONTACT_EMAIL, {
                    context.openExternal(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${AppInfo.CONTACT_EMAIL}")).apply {
                        putExtra(Intent.EXTRA_SUBJECT, "贝壳课表 反馈")
                        putExtra(Intent.EXTRA_TEXT, "（请描述你遇到的问题或建议；版本 $appVersion）")
                    }, "未找到邮件客户端，可直接发信至 ${AppInfo.CONTACT_EMAIL}")
                })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (showClearSample) AlertDialog(onDismissRequest = { showClearSample = false },
        title = { Text("清除示例课表") }, text = { Text("只删除示例课程，保留导入和手动添加的课程。") },
        confirmButton = { TextButton(onClick = { onClearSample(); showClearSample = false }) { Text("清除") } },
        dismissButton = { TextButton(onClick = { showClearSample = false }) { Text("取消") } })

    if (showUpdateDialog) {
        val u = update
        if (u is UpdateState.Available) {
            AlertDialog(
                onDismissRequest = { showUpdateDialog = false },
                title = { Text("发现新版本 v${u.latestVersion}") },
                text = { if (u.notes.isNotBlank()) Text(u.notes, style = MaterialTheme.typography.bodySmall) },
                confirmButton = {
                    TextButton(onClick = {
                        context.openExternal(
                            Intent(Intent.ACTION_VIEW, Uri.parse(u.url)),
                            "未找到可打开网页的应用",
                        )
                        showUpdateDialog = false
                    }) { Text("前往下载") }
                },
                dismissButton = { TextButton(onClick = { showUpdateDialog = false }) { Text("关闭") } },
            )
        }
    }

    if (showThemeDialog) {
        ThemePickerDialog(
            selectedMode = themeMode,
            onSelect = { mode ->
                viewModel.setThemeMode(mode)
                showThemeDialog = false
            },
            onDismissRequest = { showThemeDialog = false },
        )
    }

    if (showClearCacheConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirm = false },
            title = { Text("清除成绩缓存") },
            // 文案必须写全：clearGradesCache() 实际清掉的不止成绩与 GPA，
            // 还包括教务考试与学业进度，手动考试及其提醒保留。
            text = {
                Text(
                    "将删除本地的：成绩与 GPA、教务考试安排、学业进度（学分类别要求 / 毕业总进度），" +
                        "并取消这些教务考试的提醒。\n\n" +
                        "手动考试及其提醒、课表与隐藏设置不受影响。教务数据需重新抓取。是否继续？",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    academicSession.cancelGrades()
                    viewModel.clearGradesCache()
                    showClearCacheConfirm = false
                }) { Text("清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showClearCacheConfirm = false }) { Text("取消") } },
        )
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("退出教务登录") },
            text = {
                Text(
                    "将清除本机保存的教务系统登录状态（会话 Cookie 与网页存储），" +
                        "下次导入或抓取成绩需要重新登录统一身份认证。\n\n" +
                        "本地的课表与成绩数据不会被删除。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    academicSession.cancelForLogout()
                    clearJwSession(context)
                    showLogoutConfirm = false
                    Toast.makeText(context, "已退出教务登录", Toast.LENGTH_SHORT).show()
                }) { Text("退出登录", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showLogoutConfirm = false }) { Text("取消") } },
        )
    }
}

@Composable
fun StudentProfileScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val profile by viewModel.studentProfile.collectAsStateWithLifecycle()
    BackHandler(enabled = com.caeamer.beikeschedule.ui.common.LocalPageActive.current, onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        PageHeader("学籍信息", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            listOf("姓名" to profile.xm, "学号" to profile.xh, "学院" to profile.yxmc,
                "专业" to profile.zymc, "班级" to profile.bjmc, "年级" to profile.njmc,
                "在校状态" to profile.xjsfzx.takeIf { it.isNotBlank() }?.let { if (it == "1") "在校" else "不在校" }.orEmpty(),
                "注册状态" to profile.xjsfzc.takeIf { it.isNotBlank() }?.let { if (it == "1") "已注册" else "未注册" }.orEmpty(),
            ).filter { it.second.isNotBlank() }.forEach { (label, value) -> ProfileRow(label, value) }
            if (!profile.isLoggedIn) Text("暂无学籍信息，请在校园页获取成绩。")
        }
    }
}

/** 学籍信息大卡片内的 label:value 行。 */
@Composable
private fun ProfileRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/**
 * 安全拉起外部应用。
 * 本页所有外链都是隐式 Intent：设备上没有浏览器、或（尤其）没有邮件客户端时，
 * startActivity 会抛 ActivityNotFoundException 直接崩掉进程 —— ACTION_SENDTO + mailto:
 * 不像 ACTION_VIEW 那样有系统选择器兜底。JwWebView 的同类调用早已用 runCatching 包裹，这里补齐。
 */
private fun Context.openExternal(intent: Intent, unavailableHint: String) {
    runCatching { startActivity(intent) }.onFailure {
        Toast.makeText(this, unavailableHint, Toast.LENGTH_SHORT).show()
    }
}
