package com.caeamer.beikeschedule

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.caeamer.beikeschedule.import.AcademicSessionViewModel
import com.caeamer.beikeschedule.import.AcademicSessionHost
import com.caeamer.beikeschedule.import.ImportUiState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.caeamer.beikeschedule.data.pref.AppSession
import com.caeamer.beikeschedule.data.pref.ScorePrivacy
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.import.ImportScreen
import com.caeamer.beikeschedule.import.ImportViewModel
import com.caeamer.beikeschedule.ui.theme.BeikeScheduleTheme
import com.caeamer.beikeschedule.ui.settings.ScheduleAppearanceViewModel
import com.caeamer.beikeschedule.widget.ScheduleWidget
import com.caeamer.beikeschedule.widget.WidgetUpdateCoordinator

class MainActivity : ComponentActivity() {

    private var widgetOpenRequest by mutableStateOf(0)

    override fun onStart() {
        super.onStart()
        WidgetUpdateCoordinator.requestRefresh(applicationContext)
        lifecycleScope.launch {
            try {
                com.caeamer.beikeschedule.data.repo.CalendarAdjustmentRepository.get(applicationContext).refresh()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // 本地存储不可用时不阻断启动；设置页手动刷新可重试。
            }
        }
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
        // 无论用户进入哪个页面，都清理旧日程提醒；失败留待下次启动/系统广播重试。
        lifecycleScope.launch {
            runCatching { com.caeamer.beikeschedule.reminder.TodoReminderScheduler.cleanup(applicationContext) }
        }
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
            val academicSession: AcademicSessionViewModel = viewModel()
            val academicState by academicSession.state.collectAsStateWithLifecycle()
            val importState by importViewModel.state.collectAsStateWithLifecycle()
            val appearanceViewModel: ScheduleAppearanceViewModel = viewModel()
            val appUpdateViewModel: com.caeamer.beikeschedule.update.AppUpdateViewModel = viewModel()
            BeikeScheduleTheme(darkTheme = darkTheme) {
                var showImport by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(academicState.importEvent) {
                    academicState.importEvent?.let { event ->
                        event.values?.let { importViewModel.acceptSyncResult(event, academicState.automaticImport, academicSession) }
                        event.error?.let(importViewModel::onFetchError)
                        academicSession.consumeImportEvent(event.id)
                    }
                }
                LaunchedEffect(academicState.importRequest?.phase) {
                    when (academicState.importRequest?.phase) {
                        com.caeamer.beikeschedule.import.AcademicPhase.REVIEW -> showImport = true
                        com.caeamer.beikeschedule.import.AcademicPhase.CANCELLED -> {
                            importViewModel.cancelPendingImport()
                            showImport = false
                        }
                        else -> Unit
                    }
                }
                // 进程重建后恢复尚在登录的导入入口；配置重建保留现有请求，不重复启动。
                LaunchedEffect(showImport) {
                    if (showImport && academicState.importRequest == null && importState is ImportUiState.Browsing) academicSession.startImport()
                }

                // 状态栏/导航栏图标明暗：themeMode 只是 Compose 内部的主题选择，
                // 不会改资源 uiMode，而 enableEdgeToEdge() 的 SystemBarStyle.auto 只看 uiMode
                // （本应用主题是 android:Theme.Material.Light.NoActionBar，恒为 notnight），
                // 所以必须自己按 darkTheme 设置，否则"系统浅色 + 应用深色"时是深图标压在近黑渐变上。
                val darkIcons = if (academicState.browserVisible) true else !darkTheme
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = darkIcons
                        isAppearanceLightNavigationBars = darkIcons
                    }
                }

                val hostHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
                Box {
                    Box(if (academicState.browserVisible) Modifier.clearAndSetSemantics { }.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    } else Modifier) {
                        if (showImport) {
                            ImportScreen(
                                onDone = { showImport = false; academicSession.leaveImport() },
                                onRetry = { importViewModel.backToBrowsing(); academicSession.leaveImport(); academicSession.retry(com.caeamer.beikeschedule.import.AcademicTask.IMPORT) },
                                viewModel = importViewModel,
                            )
                        } else {
                            hostHolder.SaveableStateProvider("app_host") {
                                com.caeamer.beikeschedule.ui.AppHost(
                                    appearanceViewModel = appearanceViewModel,
                                    appUpdateViewModel = appUpdateViewModel,
                                    widgetOpenRequest = widgetOpenRequest,
                                    onWidgetConsumed = { widgetOpenRequest = 0 },
                                    onImport = {
                                        importViewModel.backToBrowsing()
                                        showImport = true
                                        academicSession.startImport()
                                    },
                                )
                            }
                        }
                    }
                    AcademicSessionHost(academicSession, importViewModel::onFetchStart, onCloseImport = { showImport = false })
                    com.caeamer.beikeschedule.update.AppUpdateHost(
                        appUpdateViewModel,
                        blocked = showImport || academicState.browserVisible || academicState.active,
                    )
                }
            }
        }
    }
}
