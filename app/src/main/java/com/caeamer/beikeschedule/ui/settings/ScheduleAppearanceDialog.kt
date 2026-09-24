package com.caeamer.beikeschedule.ui.settings

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.model.BackgroundScale
import com.caeamer.beikeschedule.ui.schedule.CourseCardText
import com.caeamer.beikeschedule.ui.schedule.ScheduleBackground
import com.caeamer.beikeschedule.ui.schedule.rememberScheduleBackground
import com.caeamer.beikeschedule.ui.theme.CourseColors
import kotlin.math.roundToInt

@Composable
fun ScheduleAppearanceDialog(
    onDismiss: () -> Unit,
    viewModel: ScheduleAppearanceViewModel = viewModel(),
) {
    val saved by viewModel.appearance.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::chooseImage)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("课表外观", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text("完成") }
                }
                HorizontalDivider()
                val appearance = saved
                if (appearance == null) {
                    CircularProgressIndicator(Modifier.padding(24.dp))
                    error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
                } else {
                    var font by rememberSaveable(appearance.fontPercent) { mutableIntStateOf(appearance.fontPercent) }
                    var overlay by rememberSaveable(appearance.overlayPercent) { mutableIntStateOf(appearance.overlayPercent) }
                    var blur by rememberSaveable(appearance.blurDp) { mutableIntStateOf(appearance.blurDp) }
                    LaunchedEffect(error) {
                        if (error != null) {
                            font = appearance.fontPercent
                            overlay = appearance.overlayPercent
                            blur = appearance.blurDp
                        }
                    }
                    val preview = appearance.copy(fontPercent = font, overlayPercent = overlay, blurDp = blur)
                    val image = rememberScheduleBackground(appearance.backgroundFile)
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.fillMaxWidth().heightIn(min = 220.dp).clip(RoundedCornerShape(16.dp))) {
                            ScheduleBackground(preview, Modifier.matchParentSize(), image)
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    "效果预览",
                                    Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                                val (bg, fg) = CourseColors.of(0)
                                Surface(color = bg, shape = RoundedCornerShape(6.dp), modifier = Modifier.width(96.dp)) {
                                    CourseCardText(PREVIEW_COURSE, fg, preview.fontScale)
                                }
                            }
                        }
                        error?.let { message ->
                            Text(message, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = viewModel::clearError) { Text("知道了") }
                        }
                        AppearanceSlider(
                            "课程字号", "$font%", font, 80..160, steps = 15, enabled = !importing,
                            onChange = { font = (it / 5f).roundToInt() * 5 },
                            onFinished = { viewModel.setFont(font) },
                        )
                        TextButton(onClick = { font = 100; viewModel.setFont(100) }, enabled = !importing) {
                            Text("恢复默认字号")
                        }
                        Text("同时调整课程名、地点、教师和单双周标记；文字增大时可上下滑动课表。", style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        Text("背景图片", style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !importing,
                            onClick = {
                                try {
                                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                } catch (_: ActivityNotFoundException) {
                                    viewModel.reportPickerError()
                                } catch (_: SecurityException) {
                                    viewModel.reportPickerError()
                                }
                            },
                        ) {
                            Text(if (importing) "正在处理图片…" else if (appearance.backgroundFile.isEmpty()) "选择图片" else "更换图片")
                        }
                        if (appearance.backgroundFile.isNotEmpty()) {
                            if (!image.loading && image.bitmap == null) {
                                Text("背景图片不可用，已显示默认渐变，请重新选择图片。", color = MaterialTheme.colorScheme.error)
                            }
                            Text("显示方式")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = appearance.imageScale == BackgroundScale.CROP,
                                    onClick = { viewModel.setScale(BackgroundScale.CROP) }, enabled = !importing,
                                    label = { Text("居中填满") },
                                )
                                FilterChip(
                                    selected = appearance.imageScale == BackgroundScale.FIT,
                                    onClick = { viewModel.setScale(BackgroundScale.FIT) }, enabled = !importing,
                                    label = { Text("完整显示") },
                                )
                            }
                            AppearanceSlider(
                                "遮罩强度", "$overlay%", overlay, 0..80, steps = 79, enabled = !importing,
                                onChange = { overlay = it }, onFinished = { viewModel.setOverlay(overlay) },
                            )
                            AppearanceSlider(
                                "模糊程度", "$blur", blur, 0..24, steps = 23, enabled = !importing,
                                onChange = { blur = it }, onFinished = { viewModel.setBlur(blur) },
                            )
                            TextButton(onClick = viewModel::resetBackground, enabled = !importing) { Text("恢复默认背景") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceSlider(
    title: String,
    valueLabel: String,
    value: Int,
    range: IntRange,
    steps: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    onFinished: () -> Unit,
) {
    Column {
        Text("$title · $valueLabel")
        Slider(
            value = value.toFloat(), onValueChange = { onChange(it.roundToInt()) },
            onValueChangeFinished = onFinished,
            valueRange = range.first.toFloat()..range.last.toFloat(), steps = steps, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}

private val PREVIEW_COURSE = CourseEntity(
    id = 0, taskId = "", name = "大学物理", teacher = "张老师", location = "教学楼 301",
    dayOfWeek = 1, startSection = 1, endSection = 2, weekBitmap = "010101010101010101010",
    colorIndex = 0, source = CourseEntity.SOURCE_SAMPLE,
)
