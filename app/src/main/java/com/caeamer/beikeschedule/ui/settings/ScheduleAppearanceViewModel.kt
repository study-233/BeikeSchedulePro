package com.caeamer.beikeschedule.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.caeamer.beikeschedule.data.local.ScheduleBackgroundStore
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.model.BackgroundScale
import com.caeamer.beikeschedule.model.ScheduleAppearance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.onEach
import java.io.IOException
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 两个入口使用 Activity 的同一个 ViewModel，串行保存外观操作。 */
class ScheduleAppearanceViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = SettingsStore(app)
    private val images = ScheduleBackgroundStore(app)
    private val writes = Mutex()
    private val errorState = MutableStateFlow<String?>(null)
    private val importingState = MutableStateFlow(false)
    val error: StateFlow<String?> = errorState
    val importing: StateFlow<Boolean> = importingState
    val appearance: StateFlow<ScheduleAppearance?> = settings.scheduleAppearance
        .retryWhen { cause, _ ->
            if (cause is IOException) {
                errorState.value = READ_ERROR
                delay(2000)
                true
            } else {
                throw cause
            }
        }
        .onEach { if (errorState.value == READ_ERROR) errorState.value = null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private companion object {
        const val READ_ERROR = "无法读取课表外观，正在重试"
    }

    fun clearError() { errorState.value = null }
    fun reportPickerError() { errorState.value = "无法打开图片选择器，请稍后重试" }
    fun setFont(percent: Int) = update { it.copy(fontPercent = percent) }
    fun setScale(scale: BackgroundScale) = update { it.copy(imageScale = scale) }
    fun setOverlay(percent: Int) = update { it.copy(overlayPercent = percent) }
    fun setBlur(dp: Int) = update { it.copy(blurDp = dp) }

    private fun update(transform: (ScheduleAppearance) -> ScheduleAppearance) {
        errorState.value = null
        viewModelScope.launch {
            try {
                writes.withLock { settings.updateScheduleAppearance(transform) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                errorState.value = "外观设置保存失败，请重试"
            }
        }
    }

    fun chooseImage(uri: Uri) {
        if (importingState.value) return
        importingState.value = true
        errorState.value = null
        viewModelScope.launch {
            try {
                writes.withLock {
                    // 文件与配置构成一次提交；离开页面不在两者之间中断清理。
                    withContext(NonCancellable) {
                        val name = images.importImage(uri)
                        try {
                            settings.updateScheduleAppearance { it.copy(backgroundFile = name) }
                        } catch (error: Exception) {
                            images.remove(name)
                            throw error
                        }
                        images.removeUnused(name)
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                errorState.value = "图片处理或保存失败，已保留原背景，请换一张图片重试"
            } finally {
                importingState.value = false
            }
        }
    }

    fun resetBackground() {
        errorState.value = null
        viewModelScope.launch {
            try {
                writes.withLock {
                    withContext(NonCancellable) {
                        settings.updateScheduleAppearance { it.withoutBackground() }
                        images.removeUnused("")
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                errorState.value = "恢复默认背景失败，请重试"
            }
        }
    }
}
