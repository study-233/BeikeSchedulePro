package com.caeamer.beikeschedule.widget

import android.content.Intent
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.caeamer.beikeschedule.MainActivity
import com.caeamer.beikeschedule.R
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.model.CourseMerger
import com.caeamer.beikeschedule.model.TodayCourse
import com.caeamer.beikeschedule.model.TodaySchedule
import com.caeamer.beikeschedule.ui.theme.CourseColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Glance 专用布局；日期和课程状态来自 model，不在渲染中读取数据库。 */
@Composable
internal fun ScheduleWidgetContent(state: ScheduleWidgetState) {
    val context = LocalContext.current
    val size = LocalSize.current
    // 2×2 只是占格；小米桌面的原生 Widget 区域可能比宽度更高。
    // 透明宿主内居中绘制正方形卡片，不能把宿主高度直接当成设计稿高度。
    val cardSide = minOf(size.width, size.height)
    val inset = cardSide * 0.085f
    val fontScale = context.resources.configuration.fontScale.coerceAtLeast(1f)
    val effectiveHeight = cardSide.value / fontScale
    val spacious = effectiveHeight >= 170
    val visibleLimit = if (effectiveHeight < 140) 1 else 2
    val visualScale = (cardSide.value / 160f).coerceIn(0.85f, 1.35f)
    val dark = when (state.theme) {
        SettingsStore.ThemeMode.LIGHT -> false
        SettingsStore.ThemeMode.DARK -> true
        SettingsStore.ThemeMode.SYSTEM ->
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
    val primary = widgetColor(state.theme, R.color.widget_text_primary, 0xFF242329, 0xFFF2EFF6)
    val secondary = widgetColor(state.theme, R.color.widget_text_secondary, 0xFF65636C, 0xFFC5C1CF)
    val muted = widgetColor(state.theme, R.color.widget_text_muted, 0xFF817E87, 0xFFA8A3B3)
    val accent = widgetColor(state.theme, R.color.widget_accent, 0xFF3962A8, 0xFFAFC9FF)
    val surface = widgetColor(state.theme, R.color.widget_surface, 0xFFEFEDF4, 0xFF25232C)
    val openSchedule = Intent(context, MainActivity::class.java).apply {
        action = ScheduleWidget.ACTION_OPEN_SCHEDULE
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val today = state.schedule
    val date = today?.date ?: LocalDate.now()
    Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = GlanceModifier.size(cardSide)
                .appWidgetBackground()
                .background(surface)
                .cornerRadius(cardSide * 0.12f)
                .clickable(actionStartActivity(openSchedule))
                .padding(inset),
        ) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    context.getString(R.string.widget_title),
                    modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(color = secondary, fontSize = if (spacious) 15.sp else 12.sp),
                    maxLines = 1,
                )
                Text(
                    "${date.monthValue}.${date.dayOfMonth}",
                    style = TextStyle(color = secondary, fontSize = if (spacious) 14.sp else 11.sp),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.width(4.dp))
                Text(
                    context.resources.getStringArray(R.array.widget_weekdays)[date.dayOfWeek.value - 1],
                    style = TextStyle(color = accent, fontSize = if (spacious) 14.sp else 11.sp),
                    maxLines = 1,
                )
            }
            val bodyModifier = GlanceModifier.fillMaxWidth().defaultWeight()
            when {
                state.failed -> EmptyMessage(
                    context.getString(R.string.widget_load_failed), bodyModifier, muted, visualScale, showHint = true,
                )
                today == null -> EmptyMessage(
                    context.getString(R.string.widget_loading), bodyModifier, muted, visualScale,
                )
                today.status != TodaySchedule.Status.CLASSES -> {
                    val message = when (today.status) {
                        TodaySchedule.Status.NEEDS_SETUP -> R.string.widget_needs_setup
                        TodaySchedule.Status.NON_TEACHING_DAY -> R.string.widget_non_teaching
                        TodaySchedule.Status.NO_CLASSES -> R.string.widget_no_classes
                        TodaySchedule.Status.FINISHED -> R.string.widget_finished
                        TodaySchedule.Status.CLASSES -> R.string.widget_title
                    }
                    val needsSetup = today.status == TodaySchedule.Status.NEEDS_SETUP
                    EmptyMessage(
                        context.getString(message), bodyModifier, muted, visualScale,
                        showFace = !needsSetup,
                        showHint = needsSetup,
                    )
                }
                else -> {
                    Spacer(GlanceModifier.height(if (spacious) 8.dp else 6.dp))
                    Column(bodyModifier) {
                        today.courses.take(visibleLimit).forEachIndexed { index, item ->
                            if (index > 0) Spacer(GlanceModifier.height(if (spacious) 8.dp else 5.dp))
                            CourseEntry(item, index == 0, spacious, dark, primary, secondary, muted)
                        }
                    }
                    val more = today.courses.size - visibleLimit
                    if (more > 0 && effectiveHeight >= 100) {
                        Text(
                            context.getString(R.string.widget_more_courses, more),
                            style = TextStyle(color = muted, fontSize = 10.sp),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyMessage(
    message: String,
    modifier: GlanceModifier,
    color: ColorProvider,
    visualScale: Float,
    showFace: Boolean = false,
    showHint: Boolean = false,
) {
    val context = LocalContext.current
    val fontScale = context.resources.configuration.fontScale.coerceAtLeast(1f)
    val effectiveSide = minOf(LocalSize.current.width, LocalSize.current.height).value / fontScale
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (showFace && effectiveSide >= 120) {
                // 参考图中的文字颜文字，作为空态装饰而非粗体标题。
                Text(
                    context.getString(R.string.widget_empty_face),
                    modifier = GlanceModifier.fillMaxWidth(),
                    style = TextStyle(color = color, fontSize = (16 * visualScale).sp, textAlign = TextAlign.Center),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.height((12 * visualScale).dp))
            }
            Text(
                message,
                modifier = GlanceModifier.fillMaxWidth(),
                style = TextStyle(color = color, fontSize = (15 * visualScale).sp, textAlign = TextAlign.Center),
                maxLines = 2,
            )
            if (showHint && effectiveSide >= 120) {
                Spacer(GlanceModifier.height(8.dp))
                Text(
                    context.getString(R.string.widget_open_schedule),
                    modifier = GlanceModifier.fillMaxWidth(),
                    style = TextStyle(color = color, fontSize = 11.sp, textAlign = TextAlign.Center),
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun CourseEntry(
    item: TodayCourse,
    emphasized: Boolean,
    spacious: Boolean,
    dark: Boolean,
    primary: ColorProvider,
    secondary: ColorProvider,
    muted: ColorProvider,
) {
    val context = LocalContext.current
    val course = item.course
    val effectiveHeight = minOf(LocalSize.current.width, LocalSize.current.height).value /
        context.resources.configuration.fontScale.coerceAtLeast(1f)
    val colors = CourseColors.of(course.colorIndex)
    val stripe = if (dark) colors.first else colors.second
    val location = course.location.takeIf { CourseMerger.plausibleLocation(it) }
        ?: context.getString(R.string.widget_location_unknown)
    val time = if (item.start != null && item.end != null) {
        if (emphasized) "${item.start.format(TimeFormat)} - ${item.end.format(TimeFormat)}"
        else item.start.format(TimeFormat)
    } else {
        context.getString(R.string.widget_sections, course.startSection, course.endSection)
    }
    val timeLabel = if (item.phase == TodayCourse.Phase.UNKNOWN_TIME && emphasized) {
        context.getString(R.string.widget_time_unknown, time)
    } else time
    Row(GlanceModifier.fillMaxWidth()) {
        Box(
            GlanceModifier.width(4.dp)
                .height(if (emphasized) { if (spacious) 60.dp else 46.dp } else { if (spacious) 36.dp else 30.dp })
                .background(stripe)
                .cornerRadius(2.dp),
        ) {}
        Spacer(GlanceModifier.width(if (spacious) 10.dp else 8.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(
                course.name,
                style = TextStyle(
                    color = primary,
                    fontSize = if (emphasized) { if (spacious) 18.sp else 14.sp } else { if (spacious) 16.sp else 12.sp },
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = if (emphasized && spacious && effectiveHeight >= 220) 2 else 1,
            )
            if (emphasized) {
                if (effectiveHeight >= 105) {
                    val details = if (spacious && course.teacher.isNotBlank()) "$location  ${course.teacher}" else location
                    Text(details, style = TextStyle(color = secondary, fontSize = if (spacious) 13.sp else 10.sp), maxLines = 1)
                }
                Text(timeLabel, style = TextStyle(color = muted, fontSize = if (spacious) 14.sp else 10.sp), maxLines = 1)
            } else {
                Text("$time  $location", style = TextStyle(color = secondary, fontSize = if (spacious) 12.sp else 10.sp), maxLines = 1)
            }
        }
    }
}

private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm")

private fun widgetColor(mode: SettingsStore.ThemeMode, resource: Int, light: Long, dark: Long): ColorProvider =
    when (mode) {
        SettingsStore.ThemeMode.SYSTEM -> ColorProvider(resource)
        SettingsStore.ThemeMode.LIGHT -> ColorProvider(Color(light))
        SettingsStore.ThemeMode.DARK -> ColorProvider(Color(dark))
    }
