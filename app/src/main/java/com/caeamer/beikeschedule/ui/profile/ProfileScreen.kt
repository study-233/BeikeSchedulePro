package com.caeamer.beikeschedule.ui.profile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Class
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Grade
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
// 上面 6-17 行已 import 过 Column/Row/Spacer/fillMaxSize…，此处只保留 Box 与新增的 WindowInsets，
// 原先这 12 行是重复粘贴（Kotlin 只报 Duplicate import 警告，故一直被忽略）
import androidx.compose.foundation.layout.WindowInsets
import androidx.lifecycle.viewmodel.compose.viewModel
import com.caeamer.beikeschedule.AppInfo
import com.caeamer.beikeschedule.R
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.import.clearJwSession
import com.caeamer.beikeschedule.ui.settings.ScheduleAppearanceDialog
import com.caeamer.beikeschedule.ui.settings.SettingsViewModel
import com.caeamer.beikeschedule.ui.settings.UpdateState

/** 我的 Tab：学籍信息 + 主题 / 检查更新 / GitHub / 版本号 / 清缓存 + 维护者。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(viewModel: SettingsViewModel = viewModel()) {
    // withLifecycle：退到后台停止收集
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val hideInactiveCourses by viewModel.hideInactiveCourses.collectAsStateWithLifecycle()
    val update by viewModel.update.collectAsStateWithLifecycle()
    val studentProfile by viewModel.studentProfile.collectAsStateWithLifecycle()
    val appVersion by viewModel.appVersion.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showClearCacheConfirm by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showAppearance by rememberSaveable { mutableStateOf(false) }
    if (showAppearance) {
        ScheduleAppearanceDialog(onDismiss = { showAppearance = false })
    }

    Scaffold(
        // 外层 Scaffold（MainActivity）已用 navigationBarsPadding 预留底部 Tab 栏高度，
        // 内层若用默认 contentWindowInsets 会再吃一遍导航栏 inset，底部多出一条空白。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            // 紧凑矮顶栏（外层 Scaffold 不消费状态栏 inset，这里自行处理）——透明透出整屏渐变
            Surface(color = Color.Transparent) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(48.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("我的", style = MaterialTheme.typography.titleMedium)
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // —— 学籍信息 ——（一张大卡片，内部 label:value 多行）
            Text(
                "学籍信息",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(16.dp)) {
                    if (studentProfile.isLoggedIn) {
                        // 账户头：姓名大字 + 学号小字，其余字段降为普通行
                        if (studentProfile.xm.isNotBlank()) {
                            Text(
                                studentProfile.xm,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        if (studentProfile.xh.isNotBlank()) {
                            Text(
                                studentProfile.xh,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (studentProfile.xm.isNotBlank() || studentProfile.xh.isNotBlank()) {
                            HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        }
                        if (studentProfile.yxmc.isNotBlank()) ProfileRow("学院", studentProfile.yxmc)
                        if (studentProfile.zymc.isNotBlank()) ProfileRow("专业", studentProfile.zymc)
                        if (studentProfile.bjmc.isNotBlank()) ProfileRow("班级", studentProfile.bjmc)
                        if (studentProfile.njmc.isNotBlank()) ProfileRow("年级", studentProfile.njmc)
                        // 字段缺失（空串）不能当成否定结论：只有服务端明确返回了才展示对应半边，
                        // 否则一个字段缺失会渲染出"不在校 · 已注册"这种凭空断言的文案。
                        val statusParts = listOfNotNull(
                            studentProfile.xjsfzx.takeIf { it.isNotBlank() }
                                ?.let { if (it == "1") "在校" else "不在校" },
                            studentProfile.xjsfzc.takeIf { it.isNotBlank() }
                                ?.let { if (it == "1") "已注册" else "未注册" },
                        )
                        if (statusParts.isNotEmpty()) {
                            ProfileRow("学籍状态", statusParts.joinToString(" · "))
                        }
                    } else {
                        Text(
                            "未获取学籍信息",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "在教务 Tab 抓取一次成绩后自动显示",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // —— 通用功能 ——（每行独立卡片：图标 + 功能名 + 右侧按钮）
            Text(
                "通用",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val updateSubtitle = when (val u = update) {
                is UpdateState.Checking -> "正在检查…"
                is UpdateState.UpToDate -> "已是最新版本"
                is UpdateState.Available -> "发现新版本 v${u.latestVersion}"
                is UpdateState.Failed -> u.message
                UpdateState.Idle -> "检查 GitHub Releases"
            }
            SettingsItemRow(
                icon = { Icon(Icons.Default.SystemUpdate, null, Modifier.size(20.dp)) },
                title = "检查更新",
                value = updateSubtitle,
                trailing = if (update is UpdateState.Available) {
                    { TextButton(onClick = { showUpdateDialog = true }) { Text("查看") } }
                } else {
                    { TextButton(onClick = { viewModel.checkUpdate() }) { Text("检查") } }
                },
                onClick = {
                    val u = update
                    if (u is UpdateState.Available) showUpdateDialog = true else viewModel.checkUpdate()
                },
            )
            SettingsItemRow(
                icon = { Icon(painterResource(R.drawable.ic_github), "GitHub", Modifier.size(20.dp)) },
                title = "GitHub 仓库",
                value = "查看源码",
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = {
                    context.openExternal(
                        Intent(Intent.ACTION_VIEW, Uri.parse(AppInfo.REPO_URL)),
                        "未找到可打开网页的应用",
                    )
                },
            )
            SettingsItemRow(
                icon = { Icon(Icons.Default.Badge, null, Modifier.size(20.dp)) },
                title = "版本",
                value = appVersion,
            )
            SettingsItemRow(
                icon = { Icon(Icons.Default.MailOutline, null, Modifier.size(20.dp)) },
                title = "联系开发者",
                value = "${AppInfo.CONTACT_EMAIL} · 问题反馈与建议",
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = {
                    context.openExternal(
                        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${AppInfo.CONTACT_EMAIL}")).apply {
                            putExtra(Intent.EXTRA_SUBJECT, "贝壳课表 反馈")
                            putExtra(Intent.EXTRA_TEXT, "（请描述你遇到的问题或建议；版本 $appVersion）")
                        },
                        "未找到邮件客户端，可直接发信至 ${AppInfo.CONTACT_EMAIL}",
                    )
                },
            )
            SettingsItemRow(
                icon = { Icon(Icons.Default.DeleteSweep, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error) },
                title = "清除成绩缓存",
                value = "成绩 / GPA / 考试安排 / 学业进度",
                destructive = true,
                onClick = { showClearCacheConfirm = true },
            )
            SettingsItemRow(
                icon = { Icon(Icons.Default.Logout, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error) },
                title = "退出教务登录",
                value = "清除本机保存的教务会话（不删除课表与成绩）",
                destructive = true,
                onClick = { showLogoutConfirm = true },
            )

            // —— 外部系统 ——（浏览器跳转；课程平台/实践平台地址待补后追加）
            Text(
                "外部系统",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SettingsItemRow(
                icon = { Icon(Icons.Default.Grade, null, Modifier.size(20.dp)) },
                title = "评教系统",
                value = "教学评价",
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = {
                    context.openExternal(
                        Intent(Intent.ACTION_VIEW, Uri.parse(SettingsViewModel.PINGJIAO_URL)),
                        "未找到可打开网页的应用",
                    )
                },
            )
            SettingsItemRow(
                icon = { Icon(Icons.Default.Science, null, Modifier.size(20.dp)) },
                title = "大创 / SRTP",
                value = "大学生创新创业训练计划",
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = {
                    context.openExternal(
                        Intent(Intent.ACTION_VIEW, Uri.parse(SettingsViewModel.SRTP_URL)),
                        "未找到可打开网页的应用",
                    )
                },
            )

            // —— 课表显示 ——
            Text(
                "课表",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SettingsItemRow(
                icon = { Icon(Icons.Default.Palette, null, Modifier.size(20.dp)) },
                title = "课表外观",
                value = "课程字号与背景图片",
                onClick = { showAppearance = true },
            )
            SettingsItemRow(
                icon = { Icon(Icons.Default.VisibilityOff, null, Modifier.size(20.dp)) },
                title = "隐藏本周不上的课",
                value = if (hideInactiveCourses) {
                    "已开启：本周没有安排的课不再显示"
                } else {
                    "单双周的另一半、还没到的调课周会淡化显示"
                },
                trailing = {
                    // onCheckedChange = null：整个行是唯一的开关控件（见 SettingsItemRow 的
                    // toggleable），Switch 只负责显示。否则 TalkBack 会把"行"和"Switch"
                    // 报成两个独立控件，用户听到两个同名开关。
                    Switch(checked = hideInactiveCourses, onCheckedChange = null)
                },
                onClick = { viewModel.setHideInactiveCourses(!hideInactiveCourses) },
                toggleRole = true,
                toggleValue = hideInactiveCourses,
            )

            // —— 主题 ——
            Text(
                "外观",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SettingsItemRow(
                icon = { Icon(Icons.Default.Palette, null, Modifier.size(20.dp)) },
                title = "主题",
                value = when (themeMode) {
                    SettingsStore.ThemeMode.SYSTEM -> "跟随系统"
                    SettingsStore.ThemeMode.LIGHT -> "浅色"
                    SettingsStore.ThemeMode.DARK -> "深色"
                },
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                // 循环切换不可发现（用户不知道点一下会变成什么），改为弹窗三选一
                onClick = { showThemeDialog = true },
            )

            Spacer(Modifier.height(32.dp))
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                "BeikeSchedulePro · ${AppInfo.DEVELOPER_NAME} 维护",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

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
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("主题") },
            text = {
                Column {
                    listOf(
                        SettingsStore.ThemeMode.SYSTEM to "跟随系统",
                        SettingsStore.ThemeMode.LIGHT to "浅色",
                        SettingsStore.ThemeMode.DARK to "深色",
                    ).forEach { (mode, label) ->
                        val selected = themeMode == mode
                        Row(
                            // selectable + Role.RadioButton：整行一个控件且能读出选中态；
                            // RadioButton 置 onClick = null 只做展示（否则 TalkBack 报两个目标）
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = {
                                        viewModel.setThemeMode(mode)
                                        showThemeDialog = false
                                    },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) { Text("取消") }
            },
        )
    }

    if (showClearCacheConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirm = false },
            title = { Text("清除成绩缓存") },
            // 文案必须写全：clearGradesCache() 实际清掉的不止成绩与 GPA，
            // 还包括考试安排与学业进度（学分类别要求/毕业总进度），并取消已排的考前提醒。
            text = {
                Text(
                    "将删除本地的：成绩与 GPA、考试安排、学业进度（学分类别要求 / 毕业总进度），" +
                        "并取消已排的考前提醒。\n\n" +
                        "课表与隐藏设置不受影响。下次进入教务 Tab 需重新抓取。是否继续？",
                )
            },
            confirmButton = {
                TextButton(onClick = {
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
                    clearJwSession(context)
                    showLogoutConfirm = false
                    Toast.makeText(context, "已退出教务登录", Toast.LENGTH_SHORT).show()
                }) { Text("退出登录", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showLogoutConfirm = false }) { Text("取消") } },
        )
    }
}

/** 设置列表项行：左侧几何图标 + 中间标题/值 + 右侧可点按钮（仿 Net-USTB）。 */
@Composable
private fun SettingsItemRow(
    title: String,
    value: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    destructive: Boolean = false,
    onClick: (() -> Unit)? = null,
    /** 该行代表一个开关：整行用 toggleable + Role.Switch 暴露，trailing 的 Switch 只做展示。 */
    toggleRole: Boolean = false,
    /** toggleRole = true 时的当前开关状态。 */
    toggleValue: Boolean = false,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    when {
                        // 开关型行用 toggleable 而非 clickable：无障碍服务会把整行当作
                        // 一个 Role.Switch 控件播报，而不是"可点区域 + 另一个开关"两个控件。
                        onClick != null && toggleRole -> Modifier.toggleable(
                            value = toggleValue,
                            role = Role.Switch,
                            onValueChange = { onClick() },
                        )
                        onClick != null -> Modifier.clickable(onClick = onClick)
                        else -> Modifier
                    },
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 图标容器：圆角浅色底，里面放几何图标
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (destructive) {
                            MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                        } else {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                icon?.invoke() ?: Icon(Icons.Default.Info, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                if (!value.isNullOrBlank()) {
                    Text(
                        value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            trailing?.invoke()
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
