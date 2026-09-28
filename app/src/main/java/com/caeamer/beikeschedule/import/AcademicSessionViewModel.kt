package com.caeamer.beikeschedule.import

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.caeamer.beikeschedule.data.repo.AcademicDataSync
import com.caeamer.beikeschedule.data.repo.AcademicPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 只传给已有 ImportViewModel 解析；成绩同步不触碰课表确认入库。 */
data class ImportFetchEvent(val id: Long, val values: List<String>? = null, val error: String? = null)
data class AcademicSessionState(
    val importRequest: AcademicRequest? = null,
    val gradesRequest: AcademicRequest? = null,
    val foregroundTask: AcademicTask? = null,
    val browserRevision: Long = 0,
    val importEvent: ImportFetchEvent? = null,
) {
    val needsBrowser get() = importRequest?.needsBrowser == true || gradesRequest?.needsBrowser == true
    val browserVisible get() = when (foregroundTask) {
        AcademicTask.IMPORT -> importRequest?.needsBrowser == true
        AcademicTask.GRADES -> gradesRequest?.needsBrowser == true
        null -> false
    }
}

/** Activity 作用域：WebView 可退出前台，已开始的成绩抓取与落盘继续完成。 */
class AcademicSessionViewModel(app: Application) : AndroidViewModel(app) {
    private val requests = AcademicRequests()
    private val dataSync = AcademicDataSync.create(app)
    private val mutableState = MutableStateFlow(AcademicSessionState())
    val state = mutableState.asStateFlow()
    private val timers = mutableMapOf<AcademicTask, Job>()
    private var saveJob: Job? = null
    private var browserLease = 0L

    private fun publish() {
        mutableState.value = mutableState.value.copy(importRequest = requests[AcademicTask.IMPORT], gradesRequest = requests[AcademicTask.GRADES])
    }
    fun startImport() {
        requests.beginLogin(importSchedule = true)
        mutableState.value = mutableState.value.copy(foregroundTask = AcademicTask.IMPORT, importEvent = null)
        publish()
    }
    fun startGrades() {
        requests.beginLogin(importSchedule = false)
        val foreground = if (state.value.foregroundTask == AcademicTask.IMPORT && requests[AcademicTask.IMPORT]?.active == true)
            AcademicTask.IMPORT else AcademicTask.GRADES
        mutableState.value = mutableState.value.copy(foregroundTask = foreground)
        publish()
    }
    fun consumeImportEvent(id: Long) {
        if (state.value.importEvent?.id == id) mutableState.value = state.value.copy(importEvent = null)
    }
    fun launchTask(task: AcademicTask): Long? {
        val now = SystemClock.elapsedRealtime()
        val pending = requests[task]
        if (pending != null && requests.expired(task, pending.id, now)) {
            fail(task, pending.id, "获取超时，已保留上次数据，请重试")
            return null
        }
        val token = requests.launch(task, now) ?: return null
        armTimeout(task)
        publish()
        return token
    }
    fun accepts(task: AcademicTask, token: Long): Boolean = requests.accepts(task, token)

    fun importResult(token: Long, values: List<String>) {
        if (!requests.accepts(AcademicTask.IMPORT, token)) return
        requests.finish(AcademicTask.IMPORT, token)
        timers.remove(AcademicTask.IMPORT)?.cancel()
        mutableState.value = state.value.copy(importEvent = ImportFetchEvent(token, values))
        publish()
    }
    fun gradesResult(token: Long, payload: AcademicPayload) {
        if (!requests.saving(AcademicTask.GRADES, token)) return
        publish()
        saveJob = viewModelScope.launch {
            try {
                val warning = dataSync.save(payload) {
                    if (!requests.isSaving(AcademicTask.GRADES, token)) throw CancellationException("Request retired")
                }
                if (requests.finish(AcademicTask.GRADES, token, warning)) {
                    timers.remove(AcademicTask.GRADES)?.cancel()
                    publish()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { fail(AcademicTask.GRADES, token, "保存失败，请重试；未完成的数据保留上次缓存") }
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
                if (task == AcademicTask.GRADES) saveJob?.cancel()
                fail(task, request.id, "获取超时，已保留上次数据，请重试")
            }
        }
    }
    fun attachBrowser(): Long {
        browserLease++
        recoverBrowser()
        return browserLease
    }
    fun isCurrentBrowser(lease: Long): Boolean = lease == browserLease
    fun detachBrowser(lease: Long) {
        if (isCurrentBrowser(lease)) {
            browserLease++
            recoverBrowser()
        }
    }
    fun pageStarted(lease: Long) { if (lease == browserLease) recoverBrowser() }
    private fun recoverBrowser() {
        requests.recoverBrowser()
        AcademicTask.entries.filter { requests[it]?.active == true }.forEach(::armTimeout)
        publish()
    }
    fun pageFailed(message: String) {
        AcademicTask.entries.forEach { task -> requests[task]?.takeIf { it.needsBrowser }?.let { fail(task, it.id, message) } }
    }
    fun retryBrowser() {
        // 重新加载仅重启尚未完成的任务，已取得的课表预览/成绩不重复抓取。
        recoverBrowser()
        mutableState.value = state.value.copy(browserRevision = state.value.browserRevision + 1)
    }
    fun leaveBrowser() {
        // 登录尚未完成时关闭则取消等待；已经启动的成绩请求继续，课表不再产生迟到预览。
        if (state.value.foregroundTask == AcademicTask.IMPORT) cancelTask(AcademicTask.IMPORT)
        if (requests[AcademicTask.GRADES]?.phase == AcademicPhase.WAITING && requests[AcademicTask.GRADES]?.deadline == null) cancelTask(AcademicTask.GRADES)
        mutableState.value = state.value.copy(foregroundTask = null)
        publish()
    }
    fun leaveImport() {
        cancelTask(AcademicTask.IMPORT)
        if (requests[AcademicTask.GRADES]?.phase == AcademicPhase.WAITING && requests[AcademicTask.GRADES]?.deadline == null) cancelTask(AcademicTask.GRADES)
        mutableState.value = state.value.copy(foregroundTask = null, importEvent = null)
        publish()
    }
    private fun cancelTask(task: AcademicTask) {
        requests.cancel(task)
        timers.remove(task)?.cancel()
        if (task == AcademicTask.GRADES) saveJob?.cancel()
    }
    fun cancelGrades() { cancelTask(AcademicTask.GRADES); publish() }
    fun cancelForLogout() {
        AcademicTask.entries.forEach(::cancelTask)
        mutableState.value = state.value.copy(foregroundTask = null, importEvent = null, browserRevision = state.value.browserRevision + 1)
        publish()
    }
}
