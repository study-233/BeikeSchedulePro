package com.caeamer.beikeschedule.update

import android.app.Application
import android.app.DownloadManager
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AppUpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = AppUpdateRepository(app)
    private val mutableState = MutableStateFlow(UpdateUiState())
    val state = mutableState.asStateFlow()
    private val mutableInstallIntent = MutableStateFlow<Intent?>(null)
    val installIntent = mutableInstallIntent.asStateFlow()
    private val mutex = Mutex()
    private var task: UpdateDownload? = null
    private var verifiedId: Long? = null
    private var readyPromptedId: Long? = null
    private var pendingOperations = 0
    private var deferUntil = 0L
    private var ignoredInSession: String? = null
    private var dismissEpoch = 0

    private fun change(transform: (UpdateUiState) -> UpdateUiState) { mutableState.value = transform(state.value) }

    /** 由 STARTED 生命周期驱动；离开前台停止轮询，系统下载仍继续。 */
    suspend fun observeForeground() {
        refresh(manual = false)
        while (true) {
            delay(1000)
            if (!state.value.busy && task != null && verifiedId != task?.id && state.value.phase != UpdatePhase.FAILED) {
                operation(UpdateRetry.DOWNLOAD, showError = false) { poll() }
            }
        }
    }

    fun checkManually() = refresh(manual = true)

    private fun refresh(manual: Boolean) {
        if (manual) change { it.copy(dialogVisible = true) }
        operation(UpdateRetry.CHECK, showError = manual) {
            // 即使进程被系统回收，也从持久化任务恢复；不要重新 enqueue。
            val saved = repository.store.read().download
            if (saved != null) change { it.copy(release = saved.release, retry = UpdateRetry.DOWNLOAD) }
            task = repository.restore()
            if (task != null) {
                readyPromptedId = null
                poll()
                return@operation
            }
            if (saved != null) change { it.copy(phase = UpdatePhase.IDLE, release = null) }
            val now = System.currentTimeMillis()
            val preferences = repository.store.read()
            if (!manual && !UpdatePolicy.shouldCheck(preferences.lastAttempt, now)) return@operation
            repository.store.edit { it.copy(lastAttempt = now) }
            change { it.copy(phase = UpdatePhase.CHECKING, release = null, message = "", retry = UpdateRetry.CHECK) }
            val release = repository.latest()
            if (AppVersion.compare(release.version, repository.installedVersion()) <= 0) {
                change { it.copy(phase = UpdatePhase.LATEST) }
            } else {
                val prompt = shouldPrompt(release.version)
                change { it.copy(phase = UpdatePhase.AVAILABLE, release = release,
                    dialogVisible = it.dialogVisible || prompt) }
            }
        }
    }

    private fun operation(retry: UpdateRetry, showError: Boolean, queue: Boolean = false, block: suspend () -> Unit) {
        if (state.value.busy && !queue) return
        val epoch = dismissEpoch
        pendingOperations++
        change { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                mutex.withLock { block() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                change { it.copy(phase = UpdatePhase.FAILED, message = e.message ?: "更新操作失败，请重试",
                    retry = if (task != null || it.retry == UpdateRetry.DOWNLOAD) UpdateRetry.DOWNLOAD else retry,
                    dialogVisible = it.dialogVisible || (showError && epoch == dismissEpoch)) }
            } finally {
                pendingOperations--
                change { it.copy(busy = pendingOperations > 0) }
            }
        }
    }

    fun later() {
        dismissEpoch++
        consumeInstallIntent()
        deferUntil = System.currentTimeMillis() + UpdatePolicy.DAY
        change { it.copy(dialogVisible = false, mobileConfirmation = false) }
        val until = deferUntil
        savePreference { it.copy(remindAfter = until) }
    }

    fun ignoreVersion() {
        val version = state.value.release?.version ?: return
        ignoredInSession = version
        later()
        savePreference { it.copy(ignoredVersion = version) }
    }

    private fun savePreference(transform: (UpdatePreferences) -> UpdatePreferences) {
        viewModelScope.launch {
            try { repository.store.edit(transform) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                change { it.copy(message = "无法保存更新提醒设置，重启后可能再次提示") }
            }
        }
    }

    private suspend fun shouldPrompt(version: String): Boolean {
        val now = System.currentTimeMillis()
        return now >= deferUntil && ignoredInSession != version &&
            UpdatePolicy.shouldPrompt(repository.store.read(), version, now)
    }

    fun requestDownload() {
        if (state.value.busy) return
        if (repository.needsMobileConfirmation()) change { it.copy(mobileConfirmation = true) }
        else download(allowMobile = false)
    }

    fun closeMobileConfirmation() = change { it.copy(mobileConfirmation = false) }

    fun download(allowMobile: Boolean) {
        val release = state.value.release ?: return
        change { it.copy(mobileConfirmation = false) }
        operation(UpdateRetry.DOWNLOAD, showError = true) {
            // 失败重试先移除失败记录；普通重复点击由 busy 和 repository.start 去重。
            if (state.value.phase == UpdatePhase.FAILED) {
                (task ?: repository.store.read().download)?.let { repository.cancel(it) }
                task = null
            }
            task = repository.start(release, allowMobile)
            deferUntil = 0
            ignoredInSession = null
            verifiedId = null
            readyPromptedId = null
            change { it.copy(dialogVisible = true, retry = UpdateRetry.DOWNLOAD, message = "") }
            poll()
        }
    }

    fun cancelDownload() {
        operation(UpdateRetry.DOWNLOAD, showError = true) {
            (task ?: repository.store.read().download)?.let { repository.cancel(it) }
            task = null
            verifiedId = null
            readyPromptedId = null
            change { it.copy(phase = UpdatePhase.AVAILABLE, downloadedBytes = 0, totalBytes = 0, message = "") }
        }
    }

    private suspend fun poll() {
        val current = task ?: return
        val progress = repository.progress(current)
        change { it.copy(release = current.release, downloadedBytes = progress.bytes.coerceAtLeast(0),
            totalBytes = progress.total.takeIf { size -> size > 0 } ?: current.release.asset?.size ?: 0) }
        when (progress.status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                if (verifiedId != current.id) {
                    change { it.copy(phase = UpdatePhase.VERIFYING) }
                    try {
                        repository.validate(current)
                        verifiedId = current.id
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        repository.cancel(current)
                        task = null
                        throw e
                    }
                }
                var prompt = false
                if (readyPromptedId != current.id) {
                    prompt = shouldPrompt(current.release.version)
                    readyPromptedId = current.id
                }
                change { it.copy(phase = UpdatePhase.READY,
                    message = if (it.phase == UpdatePhase.READY) it.message else "",
                    dialogVisible = it.dialogVisible || prompt) }
            }
            DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED -> change {
                it.copy(phase = UpdatePhase.WAITING, message = if (!current.allowMobile) "等待 Wi-Fi 或系统开始下载" else "等待网络或系统重试")
            }
            DownloadManager.STATUS_RUNNING -> change { it.copy(phase = UpdatePhase.DOWNLOADING, message = "") }
            DownloadManager.STATUS_FAILED -> {
                // 保留发行信息供重试，移除失败任务，避免每秒重复报告同一失败。
                repository.cancel(current)
                task = null
                error(when (progress.reason) {
                    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足，请清理后重新下载"
                    DownloadManager.ERROR_DEVICE_NOT_FOUND -> "更新存储不可用，请重试"
                    else -> "下载失败（${progress.reason}），请重试或前往网页下载"
                })
            }
            else -> error("无法识别下载状态，请重新下载")
        }
    }

    fun retry() {
        when (state.value.retry) {
            UpdateRetry.CHECK -> checkManually()
            UpdateRetry.DOWNLOAD -> requestDownload()
        }
    }

    /** 点击安装和授权返回都重新校验文件，不信任先前校验结果。 */
    fun prepareInstallation() {
        val epoch = dismissEpoch
        operation(UpdateRetry.DOWNLOAD, showError = true, queue = true) {
            val current = task ?: repository.restore()?.also { task = it } ?: error("安装包已失效，请重新下载")
            val intent = repository.installIntent(current)
            if (epoch == dismissEpoch) mutableInstallIntent.value = intent
        }
    }

    fun consumeInstallIntent() { mutableInstallIntent.value = null }

    fun installationUnavailable(message: String) {
        change { it.copy(phase = UpdatePhase.READY, message = message, dialogVisible = true) }
    }
}
