package com.caeamer.beikeschedule

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.caeamer.beikeschedule.data.pref.AppSession
import com.caeamer.beikeschedule.data.pref.ScorePrivacy
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.import.ImportScreen
import com.caeamer.beikeschedule.import.ImportViewModel
import com.caeamer.beikeschedule.ui.grades.GradesScreen
import com.caeamer.beikeschedule.ui.profile.ProfileScreen
import com.caeamer.beikeschedule.ui.schedule.ScheduleScreen
import com.caeamer.beikeschedule.ui.schedule.ScheduleViewModel
import com.caeamer.beikeschedule.ui.theme.BeikeScheduleTheme
import com.caeamer.beikeschedule.ui.theme.CourseColors
import com.caeamer.beikeschedule.model.ScheduleAppearance
import com.caeamer.beikeschedule.ui.schedule.ScheduleBackground
import com.caeamer.beikeschedule.ui.settings.ScheduleAppearanceViewModel
import com.caeamer.beikeschedule.widget.ScheduleWidget
import com.caeamer.beikeschedule.widget.WidgetUpdateCoordinator

/** 底部三个 Tab 的横向内容（课表/教务/我的）。需在 RowScope 内调用（用 weight 均分）。 */
@Composable
private fun RowScope.BottomTabContent(tab: String, onTab: (String) -> Unit) {
    TabItem(
        selected = tab == "schedule",
        onClick = { onTab("schedule") },
        icon = Icons.Default.CalendarMonth,
        label = "课表",
        modifier = Modifier.weight(1f),
    )
    TabItem(
        selected = tab == "jw",
        onClick = { onTab("jw") },
        icon = Icons.Default.School,
        label = "教务",
        modifier = Modifier.weight(1f),
    )
    TabItem(
        selected = tab == "mine",
        onClick = { onTab("mine") },
        icon = Icons.Default.Person,
        label = "我的",
        modifier = Modifier.weight(1f),
    )
}

/** 底部 Tab 项（紧凑单列：图标在上文字在下，无默认 padding）。 */
@Composable
private fun TabItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            // selectable + Role.Tab：TalkBack 能读出"已选中"的 Tab 语义
            // （裸 clickable 只会读成按钮，三个 Tab 听不出哪个是当前页）
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.height(22.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

class MainActivity : ComponentActivity() {

    private var widgetOpenRequest by mutableStateOf(0)

    override fun onStart() {
        super.onStart()
        WidgetUpdateCoordinator.requestRefresh(applicationContext)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("pending_widget_open", widgetOpenRequest)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeWidgetIntent()
    }

    private fun consumeWidgetIntent() {
        if (intent?.action == ScheduleWidget.ACTION_OPEN_SCHEDULE) {
            widgetOpenRequest += 1
            // 消费一次，避免旋转重建时再次跳转。
            intent.action = null
        }
    }

    /**
     * 成绩隐私复位 + 课表定位复位：App 退到后台（Home/切应用/锁屏/划掉后台）即生效。
     * 前台内切换 Tab 不触发 onStop，因此成绩显示状态、课表上手动翻到的周次在 App 内得以保持。
     *
     * 注意这只是成绩隐私的**第二道防线**：最近任务（Recents）的缩略图取的是最后一帧已绘制画面，
     * 而 Compose 在 onStop 之后不保证再绘帧，所以"点小眼睛显示分数 → 立刻按 Home"
     * 的缩略图里分数仍然是可见的。第一道防线见 setContent 里的 setRecentsScreenshotEnabled。
     */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            ScorePrivacy.hide()
            // 下一次进入视为新的前台会话：课表重新定位到当前周
            // （用户手动翻的周次不落盘，退出即作废，见 AppSession 注释）
            AppSession.markBackgrounded()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetOpenRequest = savedInstanceState?.getInt("pending_widget_open") ?: 0
        consumeWidgetIntent()
        enableEdgeToEdge()
        val settings = SettingsStore(applicationContext)
        setContent {
            // 显示分数期间禁止把本 Activity 的画面放进最近任务缩略图。
            // 用 LaunchedEffect 而非 collectAsState：只有这一个效果关心这个状态，
            // 用 collectAsState 会让每次点小眼睛都重组整棵主题树。
            LaunchedEffect(Unit) {
                ScorePrivacy.hidden.collect { hidden ->
                    setRecentsScreenshotEnabled(hidden)
                }
            }
            // withLifecycle：App 退到后台时停止收集，避免后台仍在驱动主题树重组
            val themeMode by settings.themeMode.collectAsStateWithLifecycle(
                initialValue = SettingsStore.ThemeMode.SYSTEM,
            )
            val darkTheme = when (themeMode) {
                SettingsStore.ThemeMode.LIGHT -> false
                SettingsStore.ThemeMode.DARK -> true
                SettingsStore.ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            // 导入 ViewModel 提到宿主：进入导入前要清掉上一次流程的终态（见 resetIfFinished），
            // 否则成功导入后同一进程内再也进不去导入页（会被 Done 终态立刻弹出来）
            val importViewModel: ImportViewModel = viewModel()
            val appearanceViewModel: ScheduleAppearanceViewModel = viewModel()
            val savedAppearance by appearanceViewModel.appearance.collectAsStateWithLifecycle()
            val appearance = savedAppearance ?: ScheduleAppearance()
            BeikeScheduleTheme(darkTheme = darkTheme) {
                var tab by rememberSaveable { mutableStateOf("schedule") }
                var showImport by rememberSaveable { mutableStateOf(false) }
                // 导入流程仍由原页面管理；有导入在途时保留请求，退出导入后再定位。
                LaunchedEffect(widgetOpenRequest, showImport) {
                    if (widgetOpenRequest > 0 && !showImport) tab = "schedule"
                }
                // 导入页的 WebView 展示的是浅底教务页面，需要临时切成深色状态栏图标
                var importLightPage by remember { mutableStateOf(false) }

                // 状态栏/导航栏图标明暗：themeMode 只是 Compose 内部的主题选择，
                // 不会改资源 uiMode，而 enableEdgeToEdge() 的 SystemBarStyle.auto 只看 uiMode
                // （本应用主题是 android:Theme.Material.Light.NoActionBar，恒为 notnight），
                // 所以必须自己按 darkTheme 设置，否则"系统浅色 + 应用深色"时是深图标压在近黑渐变上。
                val darkIcons = if (showImport && importLightPage) true else !darkTheme
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = darkIcons
                        isAppearanceLightNavigationBars = darkIcons
                    }
                }

                if (showImport) {
                    // 导入为全屏流程，不显示底部 Tab；返回键由 ImportScreen 内的 BackHandler 接管
                    ImportScreen(
                        onDone = { showImport = false },
                        onLightBackgroundVisible = { importLightPage = it },
                        viewModel = importViewModel,
                    )
                } else {
                    // 整屏渐变仅在「课表页」开启：浅色暖渐变/暗色暗渐变，其他页用主题默认背景
                    val useGradient = tab == "schedule"
                    Box(
                        Modifier.fillMaxSize().background(
                            if (useGradient) {
                                if (darkTheme) CourseColors.scheduleGradientDark else CourseColors.scheduleGradient
                            } else {
                                SolidColor(Color.Transparent)
                            },
                        ),
                    ) {
                        if (useGradient) {
                            ScheduleBackground(appearance, Modifier.fillMaxSize())
                        }
                        Scaffold(
                            // 内容区不消费系统栏 insets：各页顶栏自行处理状态栏
                            contentWindowInsets = WindowInsets(0, 0, 0, 0),
                            // 开启渐变色透明，否则用主题默认背景
                            containerColor = if (useGradient) Color.Transparent else MaterialTheme.colorScheme.background,
                            bottomBar = {
                                // 底部栏：课表页透出渐变，其他页用默认 surface 色调
                                if (useGradient) {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .background(
                                                if (appearance.backgroundFile.isNotEmpty()) {
                                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                                                } else Color.Transparent,
                                            )
                                            .navigationBarsPadding()
                                            .height(56.dp)
                                            .padding(horizontal = 8.dp),
                                    ) {
                                        BottomTabContent(tab, onTab = { tab = it })
                                    }
                                } else {
                                    // 非课表页：默认 surface 底色；去掉 tonalElevation 避免顶部阴影色块
                                    Surface(color = MaterialTheme.colorScheme.surface) {
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .navigationBarsPadding()
                                                .height(56.dp)
                                                .padding(horizontal = 8.dp),
                                        ) {
                                            BottomTabContent(tab, onTab = { tab = it })
                                        }
                                    }
                                }
                            },
                        ) { padding ->
                            Box(Modifier.padding(padding)) {
                                when (tab) {
                                    "jw" -> GradesScreen()
                                    "mine" -> ProfileScreen()
                                    else -> {
                                        val scheduleViewModel: ScheduleViewModel = viewModel()
                                        LaunchedEffect(widgetOpenRequest) {
                                            if (widgetOpenRequest > 0) {
                                                scheduleViewModel.showCurrentWeek()
                                                widgetOpenRequest = 0
                                            }
                                        }
                                        ScheduleScreen(
                                            onImportClick = {
                                                importViewModel.resetIfFinished()
                                                showImport = true
                                            },
                                            viewModel = scheduleViewModel,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
