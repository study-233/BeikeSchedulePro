package com.caeamer.beikeschedule.ui.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.data.local.SectionTimeEntity
import com.caeamer.beikeschedule.ui.common.AppMotion
import com.caeamer.beikeschedule.ui.theme.CourseColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal fun courseSourceKey(course: CourseEntity, week: Int) =
    "${course.id}:$week:${course.dayOfWeek}:${course.startSection}:${course.endSection}"

@Stable
internal class CourseSources {
    val bounds = mutableStateMapOf<String, Rect>()
    var selected by mutableStateOf<String?>(null)
}
internal val LocalCourseSources = staticCompositionLocalOf { CourseSources() }

/** 与网格共享同一根布局。边界插值由 Compose 动画时钟驱动，可直接反向且遵循系统动画缩放。 */
@Composable
internal fun CourseDetailOverlay(course: CourseEntity, sourceKey: String, sourceBounds: Rect?,
                                 sectionTimes: List<SectionTimeEntity>, fontScale: Float,
                                 onClosed: () -> Unit, onEdit: () -> Unit,
                                 onHide: () -> Unit, onDelete: () -> Unit,
                                 sourceActive: Boolean = true,
                                 occurrence: com.caeamer.beikeschedule.model.CourseOccurrence? = null) {
    val progress = remember(sourceKey) { Animatable(0f) }
    var fadeOnly by remember(sourceKey) { mutableStateOf(false) }
    var expanded by remember(sourceKey) { mutableStateOf(true) }
    var afterClose by remember(sourceKey) { mutableStateOf<(() -> Unit)?>(null) }
    val latestClosed by rememberUpdatedState(onClosed)
    val scroll = rememberScrollState()
    var drag by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    var settling by remember { mutableStateOf<Job?>(null) }
    var rootPosition by remember { mutableStateOf(Offset.Zero) }
    var panelHeight by remember(sourceKey) { mutableIntStateOf(0) }
    // 首次打开的来源快照只用于展开；关闭时只认可仍然存在的来源。
    val initialSource = remember(sourceKey) { sourceBounds }
    val dismiss = { expanded = false }
    BackHandler(onBack = dismiss)
    LaunchedEffect(expanded, panelHeight > 0) {
        if (panelHeight == 0) return@LaunchedEffect
        progress.animateTo(if (expanded) 1f else 0f, tween(AppMotion.DETAIL))
        if (!expanded) {
            latestClosed()
            afterClose?.invoke()
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { rootPosition = it.positionInRoot() }) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val inset = with(density) { 16.dp.toPx() }
        val bottomInset = WindowInsets.navigationBars.getBottom(density).toFloat() + inset
        val maxPanelHeight = (heightPx - bottomInset - WindowInsets.statusBars.getTop(density) - inset).coerceAtLeast(1f)
        val targetHeight = panelHeight.toFloat().coerceIn(1f, maxPanelHeight)
        val target = Rect(inset, heightPx - bottomInset - targetHeight, widthPx - inset, heightPx - bottomInset)
        val source = (if (fadeOnly) null else if (expanded) initialSource else sourceBounds)?.translate(-rootPosition)
        val canReturn = source != null
        val rect = if (source != null) lerp(source, target, progress.value) else target
        val panelAlpha = if (canReturn) 1f else progress.value
        fun move(delta: Float) {
            if (!expanded) return
            settling?.cancel()
            drag = (drag + delta).coerceIn(0f, target.height)
        }
        fun finish(velocity: Float) {
            if (drag > target.height * 0.25f || velocity > with(density) { 1000.dp.toPx() }) dismiss()
            else {
                settling?.cancel()
                settling = scope.launch { animate(drag, 0f, animationSpec = tween(AppMotion.STATE)) { value, _ -> drag = value } }
            }
        }
        val connection = remember(expanded, target.height) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (drag > 0f) {
                        val before = drag
                        move(available.y)
                        return Offset(0f, drag - before)
                    }
                    return Offset.Zero
                }
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (available.y > 0f && scroll.value == 0) {
                        move(available.y)
                        return Offset(0f, available.y)
                    }
                    return Offset.Zero
                }
                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (drag <= 0f) return Velocity.Zero
                    finish(available.y)
                    return available
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f * progress.value * (1f - drag / heightPx)))
            .clickable(onClickLabel = "关闭课程详情", onClick = dismiss))
        val cardColors = CourseColors.card(course.colorIndex, MaterialTheme.colorScheme.background.luminance() < 0.5f, sourceActive)
        val cardColor = cardColors.background
        Surface(
            modifier = Modifier.offset { IntOffset(rect.left.roundToInt(), (rect.top + drag * progress.value).roundToInt()) }
                // 始终按展开宽度测量自然高度，动画只变换边界，避免长标题在展开中反复换行。
                .width(with(density) { target.width.toDp() })
                .heightIn(max = with(density) { maxPanelHeight.toDp() })
                .onSizeChanged { panelHeight = it.height }
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = rect.width / target.width
                    scaleY = rect.height / panelHeight.coerceAtLeast(1)
                    alpha = panelAlpha * progress.value
                }.testTag("course_detail_panel").semantics { paneTitle = "课程详情" },
            shape = RoundedCornerShape((6 + 14 * progress.value).dp),
            color = androidx.compose.ui.graphics.lerp(cardColor, MaterialTheme.colorScheme.surface, progress.value),
        ) {
            // 面板空白区域也拦截点击，不能落到遮罩或课表。
            Box(Modifier.fillMaxWidth().clickable(enabled = true, indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {}) {
                Column(Modifier.fillMaxWidth().nestedScroll(connection)) {
                    Box(Modifier.fillMaxWidth().height(60.dp)
                        .padding(horizontal = 12.dp).testTag("course_detail_handle")
                        .draggable(rememberDraggableState { move(it) }, Orientation.Vertical,
                            onDragStopped = { finish(it) }), contentAlignment = Alignment.Center) {
                        Box(Modifier.align(Alignment.TopCenter).padding(top = 8.dp).width(36.dp).height(4.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)))
                        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(48.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("课程详情", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = dismiss) { Text("关闭") }
                        }
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(scroll)) {
                        CourseDetailContent(course, sectionTimes,
                            occurrence = occurrence,
                            onEdit = { afterClose = onEdit; dismiss() },
                            onHide = { fadeOnly = true; onHide(); dismiss() }, onDelete = { fadeOnly = true; onDelete(); dismiss() })
                    }
                }
            }
        }
        if (progress.value < 1f && source != null) {
            Surface(
                modifier = Modifier.offset { IntOffset(rect.left.roundToInt(), (rect.top + drag * progress.value).roundToInt()) }
                    .width(with(density) { rect.width.toDp() }).height(with(density) { rect.height.toDp() })
                    .alpha(1f - progress.value),
                shape = RoundedCornerShape((6 + 14 * progress.value).dp), color = cardColor,
            ) { CourseCardText(course,
                cardColors.title, fontScale, cardColors.location, cardColors.detail) }
        }
    }
}
