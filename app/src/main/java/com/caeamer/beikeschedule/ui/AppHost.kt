package com.caeamer.beikeschedule.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.caeamer.beikeschedule.model.*
import com.caeamer.beikeschedule.ui.common.AppMotion
import com.caeamer.beikeschedule.ui.grades.GradesScreen
import com.caeamer.beikeschedule.ui.grades.GradesViewModel
import com.caeamer.beikeschedule.ui.profile.ProfileScreen
import com.caeamer.beikeschedule.ui.profile.StudentProfileScreen
import com.caeamer.beikeschedule.ui.schedule.*
import com.caeamer.beikeschedule.ui.settings.*

/** 主导航、二级页和详情都在同一宿主内；WebView 导入流程由 Activity 单独管理。 */
@Composable
fun AppHost(appearanceViewModel: ScheduleAppearanceViewModel, widgetOpenRequest: Int,
            onWidgetConsumed: () -> Unit, onImport: () -> Unit) {
    val schedule: ScheduleViewModel = viewModel()
    val settings: SettingsViewModel = viewModel()
    val academicSession: com.caeamer.beikeschedule.import.AcademicSessionViewModel = viewModel()
    val state by schedule.uiState.collectAsStateWithLifecycle()
    val savedAppearance by appearanceViewModel.appearance.collectAsStateWithLifecycle()
    val appearance = savedAppearance ?: ScheduleAppearance()
    var mainId by rememberSaveable { mutableStateOf(MainPage.SCHEDULE.id) }
    var pages by rememberSaveable { mutableStateOf(listOf<String>()) }
    var selectedCourseKey by rememberSaveable { mutableStateOf<String?>(null) }
    var editCourseId by rememberSaveable { mutableStateOf<Long?>(null) }
    var settingsVisit by rememberSaveable { mutableIntStateOf(0) }
    val main = MainPage.restore(mainId)
    val page = SettingsPage.restore(pages.lastOrNull()?.substringBefore('@'))
    val holder = rememberSaveableStateHolder()
    val sources = remember { CourseSources() }
    SideEffect { sources.selected = selectedCourseKey }
    val navigate: (SettingsPage) -> Unit = { next ->
        if (page != next) {
            settingsVisit++
            pages = openSettings(pages, next, settingsVisit)
        }
    }
    val back: () -> Unit = {
        pages.lastOrNull()?.let { holder.removeState(it) }
        if (pages.isNotEmpty()) pages = pages.dropLast(1)
    }
    LaunchedEffect(widgetOpenRequest) {
        if (widgetOpenRequest > 0) {
            pages = emptyList()
            selectedCourseKey = null
            mainId = MainPage.SCHEDULE.id
            schedule.showCurrentWeek()
            onWidgetConsumed()
        }
    }
    val course = remember(selectedCourseKey, state.courses) {
        val id = selectedCourseKey?.substringBefore(':')?.toLongOrNull()
        CourseMerger.mergeSameSlot(state.scheduledCourses).firstOrNull { it.id == id }
    }
    // 删除/隐藏后保留退出帧的数据，但不再使用过时的来源位置。
    var detailSnapshot by remember { mutableStateOf<com.caeamer.beikeschedule.data.local.CourseEntity?>(null) }
    LaunchedEffect(course, selectedCourseKey, state.loaded) {
        if (course != null) detailSnapshot = course
        else if (selectedCourseKey != null && detailSnapshot == null && state.loaded) selectedCourseKey = null
        if (selectedCourseKey == null) detailSnapshot = null
    }
    var observedScheduleId by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.scheduleId) {
        if (state.loaded) {
            if (observedScheduleId != null && observedScheduleId != state.scheduleId) {
                selectedCourseKey = null
                editCourseId = null
                detailSnapshot = null
            }
            observedScheduleId = state.scheduleId
        }
    }
    CompositionLocalProvider(LocalCourseSources provides sources) {
        Box(Modifier.fillMaxSize()) {
            val destination = pages.lastOrNull() ?: main.id
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    if (page == null) {
                        Box(if (selectedCourseKey != null) Modifier.clearAndSetSemantics { } else Modifier) {
                            BottomNavigation(main, onSelect = { next ->
                                if (selectedCourseKey == null && main != next) mainId = next.id
                            })
                        }
                    }
                },
            ) { hostPadding ->
                Box(Modifier.fillMaxSize().padding(hostPadding)) {
                    AnimatedContent(
                        targetState = destination,
                        label = "navigation",
                        transitionSpec = {
                            val from = MainPage.entries.indexOfFirst { it.id == initialState }
                            val to = MainPage.entries.indexOfFirst { it.id == targetState }
                            val secondary = from < 0 || to < 0
                            val direction = if (!secondary) {
                                if (to >= from) 1 else -1
                            } else if (targetState == main.id || targetState.substringBefore('@') == SettingsPage.SCHEDULE.name) -1 else 1
                            val duration = if (secondary) AppMotion.SECONDARY else AppMotion.PAGE
                            (fadeIn(tween(duration)) + slideInHorizontally(tween(duration)) { direction * it / 24 }) togetherWith
                                (fadeOut(tween(duration)) + slideOutHorizontally(tween(duration)) { -direction * it / 24 })
                        },
                    ) { visible ->
                        val inactive = visible != destination || selectedCourseKey != null
                        Box(Modifier.fillMaxSize().then(if (inactive) Modifier.clearAndSetSemantics { }
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                }
                            } else Modifier)) {
                            CompositionLocalProvider(com.caeamer.beikeschedule.ui.common.LocalPageActive provides !inactive) {
                                holder.SaveableStateProvider(visible) {
                                    val secondary = SettingsPage.restore(visible.substringBefore('@'))
                                    if (secondary != null) {
                                        // 保存回调可能晚于小组件跳转；只允许退出发起操作的那一页。
                                        val closePage = { if (pages.lastOrNull() == visible) back() }
                                        Surface(Modifier.fillMaxSize().navigationBarsPadding(), color = MaterialTheme.colorScheme.background) {
                                            if (secondary == SettingsPage.ACCOUNT) com.caeamer.beikeschedule.ui.profile.AcademicSyncScreen(closePage, onImport)
                                            else if (secondary == SettingsPage.STUDENT) StudentProfileScreen(settings, closePage)
                                            else ScheduleSettingsScreen(secondary, schedule, appearanceViewModel, closePage, navigate)
                                        }
                                    } else {
                                        val visibleMain = MainPage.restore(visible)
                                        if (visibleMain == MainPage.SCHEDULE) ScheduleBackground(appearance, Modifier.fillMaxSize())
                                        Box(Modifier.fillMaxSize().then(if (visibleMain != MainPage.SCHEDULE)
                                            Modifier.background(MaterialTheme.colorScheme.background) else Modifier)) {
                                            when (visibleMain) {
                                                MainPage.SCHEDULE -> ScheduleScreen(
                                                    onImportClick = { academicSession.startSync(); navigate(SettingsPage.ACCOUNT) },
                                                    onSettings = { navigate(SettingsPage.SCHEDULE) },
                                                    onManage = { navigate(SettingsPage.MANAGE) },
                                                    onCourseDetail = { selected, week ->
                                                        if (selectedCourseKey == null) {
                                                            detailSnapshot = selected
                                                            selectedCourseKey = courseSourceKey(selected, week)
                                                        }
                                                    },
                                                    editCourseId = editCourseId,
                                                    onEditConsumed = { editCourseId = null },
                                                    viewModel = schedule,
                                                )
                                                MainPage.CAMPUS -> {
                                                    val grades: GradesViewModel = viewModel()
                                                    GradesScreen(grades)
                                                }
                                                MainPage.PROFILE -> ProfileScreen(
                                                    navigate,
                                                    onAcademic = { navigate(SettingsPage.ACCOUNT) },
                                                    onClearSample = schedule::clearSampleData,
                                                    hasSample = state.hasSample,
                                                    viewModel = settings,
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
            val shownCourse = course ?: detailSnapshot
            val key = selectedCourseKey
            if (key != null && shownCourse != null && shownCourse.scheduleId == state.scheduleId) {
                val group = state.courses.filter { it.name == shownCourse.name && it.source == shownCourse.source }.map { it.id }
                CourseDetailOverlay(shownCourse, key,
                    sourceBounds = sources.bounds[key]?.takeIf {
                        it.width > 0f && it.height > 0f && course != null &&
                            key == courseSourceKey(course, state.selectedWeek) && main == MainPage.SCHEDULE && page == null
                    },
                    sectionTimes = state.sectionTimes, fontScale = appearance.fontScale,
                    sourceActive = shownCourse.hasClassOnWeek(key.split(':').getOrNull(1)?.toIntOrNull() ?: state.selectedWeek),
                    onClosed = { selectedCourseKey = null }, onEdit = { editCourseId = shownCourse.id },
                    onHide = { schedule.setCoursesHidden(shownCourse.scheduleId, group, true) },
                    onDelete = { schedule.saveCourses(shownCourse.scheduleId, emptyList(), group) })
            }
        }
    }
}

@Composable
private fun BottomNavigation(selected: MainPage, onSelect: (MainPage) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
        BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp)) {
            val itemWidth = maxWidth / MainPage.entries.size
            val x by animateDpAsState(itemWidth * selected.ordinal, tween(AppMotion.PAGE), label = "tabIndicator")
            Box(Modifier.offset(x = x).width(itemWidth).height(56.dp).padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primaryContainer))
            Row {
                MainPage.entries.forEach { page ->
                    val scale by animateFloatAsState(if (page == selected) 1.04f else 1f,
                        tween(AppMotion.STATE), label = "tabIcon")
                    Column(Modifier.weight(1f).height(56.dp).selectable(page == selected, role = Role.Tab, onClick = { onSelect(page) }),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(when (page) {
                            MainPage.SCHEDULE -> Icons.Default.CalendarMonth
                            MainPage.CAMPUS -> Icons.Default.School
                            MainPage.PROFILE -> Icons.Default.Person
                        }, null, Modifier.size(22.dp).graphicsLayer { scaleX = scale; scaleY = scale },
                            tint = if (selected == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(page.title, style = MaterialTheme.typography.labelSmall,
                            color = if (selected == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
