package com.caeamer.beikeschedule.import

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.data.repo.AcademicDataSync
import com.caeamer.beikeschedule.data.repo.AcademicPayload
import com.caeamer.beikeschedule.data.repo.NoticeRepository
import com.caeamer.beikeschedule.data.repo.ScheduleRepository
import com.caeamer.beikeschedule.import.parser.NoticesParser
import com.caeamer.beikeschedule.reminder.ExamReminderScheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 原始抓取结果仅在内存中流转，不进入导航或持久化状态。 */
data class ImportFetchEvent(val id: Long, val values: List<String>? = null, val error: String? = null)
data class AcademicSessionState(
    val importRequest: AcademicRequest? = null,
    val gradesRequest: AcademicRequest? = null,
    val foregroundTask: AcademicTask? = null,
    val browserRevision: Long = 0,
    val importEvent: ImportFetchEvent? = null,
    val otherRequests: Map<AcademicTask, AcademicRequest> = emptyMap(),
    val browserReady: Boolean = false,
    val browserPhase: AcademicBrowserPhase = AcademicBrowserPhase.CHECKING,
    val browserMessage: String? = null,
    val automaticImport: Boolean = true,
    val noticePage: Int = 1,
    val clearing: Boolean = false,
    val maintenanceError: String? = null,
) {
    operator fun get(task: AcademicTask): AcademicRequest? = when (task) {
        AcademicTask.IMPORT -> importRequest
        AcademicTask.GRADES -> gradesRequest
        else -> otherRequests[task]
    }
    val active get() = AcademicTask.entries.any { this[it]?.active == true }
    val needsBrowser get() = AcademicTask.entries.any { this[it]?.needsBrowser == true }
    val browserVisible get() = foregroundTask != null && needsBrowser && browserPhase.interactive
}

/** Activity 作用域的统一同步协调器。页面切换不销毁会话或已经启动的任务。 */
class AcademicSessionViewModel(app: Application) : AndroidViewModel(app) {
    private val requests = AcademicRequests()
    private val settings = SettingsStore(app)
    private val dataSync = AcademicDataSync.create(app)
    private val notices = NoticeRepository(app)
    val syncTimes = settings.academicSyncTimes
    private val mutableState = MutableStateFlow(AcademicSessionState())
    val state = mutableState.asStateFlow()
    private val timers = mutableMapOf<AcademicTask, Job>()
    private val saveJobs = mutableMapOf<AcademicTask, Job>()
    private val metadataJobs = mutableMapOf<AcademicTask, Job>()
    private var browserLease = 0L
    private var fullSync = false
    private var browserTimeout: Job? = null
    private var browserDeadline: Long? = null

    private fun beginBrowserCheck() {
        browserDeadline = SystemClock.elapsedRealtime() + 20_000L
        mutableState.value = state.value.copy(browserReady = false,
            browserPhase = AcademicBrowserPhase.CHECKING, browserMessage = null)
        armBrowserTimeout()
    }

    private fun armBrowserTimeout() {
        browserTimeout?.cancel()
        val deadline = browserDeadline ?: return
        browserTimeout = viewModelScope.launch {
            delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            if (!state.value.needsBrowser) return@launch
            if (state.value.browserPhase == AcademicBrowserPhase.OPENING_AUTH) {
                showManualLogin("自动进入认证页面超时，请在学校页面继续登录，或点击重新加载")
            } else if (state.value.browserPhase == AcademicBrowserPhase.CHECKING) {
                // 超时也不能封死登录入口；真实主框架加载错误仍由 pageFailed 终止。
                showManualLogin(SessionProbeFailure.fromCode("TIMEOUT").message)
            }
        }
    }

    private fun stopBrowserTimeout() {
        browserTimeout?.cancel()
        browserTimeout = null
        browserDeadline = null
    }

    private fun publish() {
        mutableState.value = mutableState.value.copy(importRequest = requests[AcademicTask.IMPORT],
            gradesRequest = requests[AcademicTask.GRADES],
            otherRequests = AcademicTask.entries.filter { it != AcademicTask.IMPORT && it != AcademicTask.GRADES }
                .mapNotNull { task -> requests[task]?.let { task to it } }.toMap())
    }
    fun startSync() = startAll(automatic = true)
    fun startGrades() = startSync()
    fun startImport() = startAll(automatic = false)
    private fun startAll(automatic: Boolean) {
        if (state.value.clearing) return
        if (!state.value.needsBrowser) beginBrowserCheck()
        if (!state.value.active || !fullSync) {
            if (state.value.noticePage > 1) cancelTask(AcademicTask.NOTICES)
            requests.beginAll()
            fullSync = true
            mutableState.value = state.value.copy(automaticImport = automatic, importEvent = null, noticePage = 1)
        }
        mutableState.value = state.value.copy(foregroundTask = AcademicTask.IMPORT)
        publish()
    }
    fun retryFailed() {
        if (state.value.clearing) return
        AcademicTask.entries.filter { requests[it]?.phase == AcademicPhase.FAILED }.forEach(::retry)
    }
    fun retry(task: AcademicTask) {
        if (state.value.clearing || requests[task]?.active == true) return
        if (!state.value.needsBrowser) beginBrowserCheck()
        if (!state.value.active) fullSync = false
        requests.begin(task)
        if (task == AcademicTask.IMPORT) mutableState.value = state.value.copy(importEvent = null)
        mutableState.value = state.value.copy(foregroundTask = task)
        publish()
    }
    fun startNotices(page: Int = 1) {
        if (state.value.clearing || requests[AcademicTask.NOTICES]?.active == true || page < 1) return
        mutableState.value = state.value.copy(noticePage = page)
        retry(AcademicTask.NOTICES)
    }
    fun browserReady() {
        stopBrowserTimeout()
        mutableState.value = state.value.copy(browserReady = true,
            browserPhase = AcademicBrowserPhase.READY, browserMessage = null)
    }
    fun openingAuthentication() {
        if (state.value.browserPhase == AcademicBrowserPhase.MANUAL) return
        if (state.value.browserPhase != AcademicBrowserPhase.OPENING_AUTH) {
            browserDeadline = SystemClock.elapsedRealtime() + 20_000L
        }
        mutableState.value = state.value.copy(browserReady = false, browserPhase = AcademicBrowserPhase.OPENING_AUTH)
        armBrowserTimeout()
    }
    fun showManualLogin(message: String) {
        stopBrowserTimeout()
        mutableState.value = state.value.copy(browserReady = false,
            browserPhase = AcademicBrowserPhase.MANUAL, browserMessage = message)
    }
    fun requireLogin() {
        // 学校也可能直接导航到认证页，而不是通过 fetch 返回失效事件。
        if (AcademicTask.entries.any { requests[it]?.let { r -> r.needsBrowser && r.deadline != null } == true }) {
            if (!requests.pauseForAuthentication(SystemClock.elapsedRealtime())) {
                pageFailed("重新认证后会话仍不可用，请稍后重试，已有数据已保留")
                return
            }
            AcademicTask.entries.filter { requests[it]?.needsBrowser == true }.forEach { timers.remove(it)?.cancel() }
            publish()
        }
        stopBrowserTimeout()
        mutableState.value = state.value.copy(browserReady = false,
            browserPhase = AcademicBrowserPhase.LOGIN, browserMessage = null,
            foregroundTask = state.value.foregroundTask ?: AcademicTask.entries.firstOrNull { requests[it]?.needsBrowser == true })
    }
    fun authenticationExpired(task: AcademicTask, token: Long) {
        if (!accepts(task, token)) return
        if (!requests.pauseForAuthentication(SystemClock.elapsedRealtime())) {
            pageFailed("重新认证后会话仍不可用，请稍后重试，已有数据已保留")
            return
        }
        AcademicTask.entries.filter { requests[it]?.needsBrowser == true }.forEach { timers.remove(it)?.cancel() }
        beginBrowserCheck()
        mutableState.value = state.value.copy(browserRevision = state.value.browserRevision + 1)
        publish()
    }
    fun consumeImportEvent(id: Long) {
        if (state.value.importEvent?.id == id) mutableState.value = state.value.copy(importEvent = null)
    }
    fun launchTask(task: AcademicTask): Long? {
        if (!state.value.browserReady) return null
        val now = SystemClock.elapsedRealtime()
        val pending = requests[task]
        if (pending != null && (requests.expired(task, pending.id, now) || pending.remainingMillis == 0L)) {
            fail(task, pending.id, "获取超时，已保留上次数据，请重试")
            return null
        }
        val token = requests.launch(task, now) ?: return null
        armTimeout(task)
        publish()
        return token
    }
    fun accepts(task: AcademicTask, token: Long) = requests.accepts(task, token)
    fun ensureImportCurrent(token: Long) {
        val request = state.value.importRequest
        if (request?.id != token || !request.active) throw CancellationException("Import retired")
    }
    fun importResult(token: Long, values: List<String>) {
        if (!requests.saving(AcademicTask.IMPORT, token)) return
        mutableState.value = state.value.copy(importEvent = ImportFetchEvent(token, values))
        publish()
    }
    fun importReview(token: Long) {
        if (requests.review(token)) {
            timers.remove(AcademicTask.IMPORT)?.cancel()
            publish()
        }
    }
    fun importSaving(token: Long) {
        requests.retryImportSave(token)
        publish()
        ensureImportCurrent(token)
        requests.saving(AcademicTask.IMPORT, token)
        publish()
    }
    fun importCommitted(token: Long) {
        ensureImportCurrent(token)
        requests.clearDeadline(AcademicTask.IMPORT, token)
        timers.remove(AcademicTask.IMPORT)?.cancel()
    }
    fun importFinished(token: Long, error: String? = null) {
        if (error != null) {
            if (requests.finish(AcademicTask.IMPORT, token, error, failed = true)) {
                timers.remove(AcademicTask.IMPORT)?.cancel()
                publish()
            }
        } else completed(AcademicTask.IMPORT, token)
    }
    fun gradesResult(task: AcademicTask, token: Long, payload: AcademicPayload) = save(task, token) { check ->
        dataSync.savePart(task, payload, check)
    }
    fun noticesResult(token: Long, json: String) {
        val page = state.value.noticePage
        save(AcademicTask.NOTICES, token) { check -> notices.save(NoticesParser.parse(json, page), check) }
    }
    private fun save(task: AcademicTask, token: Long, block: suspend (() -> Unit) -> Unit) {
        if (!requests.saving(task, token)) return
        publish()
        saveJobs[task] = viewModelScope.launch {
            try {
                block { if (state.value[task]?.let { it.id == token && it.phase == AcademicPhase.SAVING } != true) throw CancellationException("Request retired") }
                completed(task, token)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(task, token, e.message ?: "保存失败，已保留未更新的数据") }
        }
    }
    private fun completed(task: AcademicTask, token: Long) {
        if (!requests.finish(task, token)) return
        timers.remove(task)?.cancel()
        publish()
        // 保存数据后才记录成功时间；偏好写入失败不重复提交已成功的数据。
        metadataJobs[task] = viewModelScope.launch {
            try { settings.saveAcademicSyncTime(task.name) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
        }
    }
    fun fail(task: AcademicTask, token: Long, message: String) {
        if (!requests.finish(task, token, message, failed = true)) return
        timers.remove(task)?.cancel()
        if (task == AcademicTask.IMPORT) mutableState.value = state.value.copy(importEvent = ImportFetchEvent(token, error = message))
        publish()
    }
    private fun armTimeout(task: AcademicTask) {
        timers.remove(task)?.cancel()
        val request = requests[task] ?: return
        val deadline = request.deadline ?: return
        timers[task] = viewModelScope.launch {
            delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            if (requests.expired(task, request.id, SystemClock.elapsedRealtime())) {
                saveJobs[task]?.cancel()
                fail(task, request.id, "获取超时，已保留上次数据，请重试")
            }
        }
    }
    fun attachBrowser(): Long {
        browserLease++
        if (state.value.browserPhase == AcademicBrowserPhase.READY || browserDeadline == null) beginBrowserCheck()
        recoverBrowser()
        return browserLease
    }
    fun isCurrentBrowser(lease: Long) = lease == browserLease
    fun detachBrowser(lease: Long) {
        if (isCurrentBrowser(lease)) { browserLease++; recoverBrowser() }
    }
    fun pageStarted(lease: Long) {
        if (lease == browserLease) {
            if (state.value.browserPhase == AcademicBrowserPhase.READY || state.value.browserPhase == AcademicBrowserPhase.LOGIN) beginBrowserCheck()
            mutableState.value = state.value.copy(browserReady = false)
            recoverBrowser()
        }
    }
    private fun recoverBrowser() {
        requests.recoverBrowser()
        AcademicTask.entries.filter { requests[it]?.active == true }.forEach(::armTimeout)
        publish()
    }
    fun pageFailed(message: String) {
        stopBrowserTimeout()
        AcademicTask.entries.forEach { task -> requests[task]?.takeIf { it.needsBrowser }?.let { fail(task, it.id, message) } }
    }
    fun retryBrowser() {
        recoverBrowser()
        beginBrowserCheck()
        mutableState.value = state.value.copy(browserRevision = state.value.browserRevision + 1, browserReady = false)
    }
    fun leaveBrowser() {
        stopBrowserTimeout()
        AcademicTask.entries.filter { requests[it]?.let { r -> r.phase == AcademicPhase.WAITING && r.deadline == null } == true }.forEach(::cancelTask)
        mutableState.value = state.value.copy(foregroundTask = null)
        publish()
    }
    fun leaveImport() {
        if (requests[AcademicTask.IMPORT]?.let { it.phase == AcademicPhase.WAITING && it.deadline == null } == true) {
            leaveBrowser()
            return
        }
        if (requests[AcademicTask.IMPORT]?.phase == AcademicPhase.REVIEW) {
            cancelTask(AcademicTask.IMPORT)
            mutableState.value = state.value.copy(importEvent = null)
        }
        // 已开始的抓取/保存继续；不能丢弃尚未被宿主消费的结果。
        publish()
    }
    private fun cancelTask(task: AcademicTask) {
        requests.cancel(task)
        timers.remove(task)?.cancel()
        saveJobs[task]?.cancel()
        metadataJobs[task]?.cancel()
    }
    fun cancelGrades() {
        DATA_TASKS.forEach(::cancelTask)
        publish()
    }
    fun cancelForLogout() {
        stopBrowserTimeout()
        AcademicTask.entries.forEach(::cancelTask)
        mutableState.value = state.value.copy(foregroundTask = null, importEvent = null,
            browserReady = false, browserRevision = state.value.browserRevision + 1)
        publish()
    }
    fun clearNoticeCache() = clearCache(setOf(AcademicTask.NOTICES)) { notices.clear() }
    fun clearGradesCache() = clearCache(DATA_TASKS - AcademicTask.STUDENT) {
        val repo = ScheduleRepository(getApplication())
        settings.saveGradesMeta("", 0L)
        settings.saveCreditMeta("", "")
        repo.replaceGrades(emptyList())
        repo.replaceImportedExams(emptyList())
        ExamReminderScheduler.reschedule(getApplication())
    }
    private fun clearCache(tasks: Set<AcademicTask>, clear: suspend () -> Unit) {
        if (state.value.clearing) return
        mutableState.value = state.value.copy(clearing = true, maintenanceError = null)
        tasks.forEach(::cancelTask)
        publish()
        viewModelScope.launch {
            try {
                tasks.forEach { saveJobs[it]?.join() }
                tasks.forEach { metadataJobs[it]?.join() }
                clear()
                tasks.forEach { settings.saveAcademicSyncTime(it.name, 0L) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.value = state.value.copy(maintenanceError = "缓存清理未完成，请重试") }
            finally { mutableState.value = state.value.copy(clearing = false) }
        }
    }
    companion object {
        private val DATA_TASKS = setOf(AcademicTask.GRADES, AcademicTask.GPA, AcademicTask.EXAMS, AcademicTask.STUDENT, AcademicTask.PROGRESS)
    }
}
