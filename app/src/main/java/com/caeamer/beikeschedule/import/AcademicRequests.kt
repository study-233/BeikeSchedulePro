package com.caeamer.beikeschedule.import

/** 请求标识只存在内存，不包含账号、Cookie 或学校的一次性登录参数。 */
enum class AcademicTask { IMPORT, GRADES }
enum class AcademicPhase { WAITING, RUNNING, SAVING, SUCCESS, FAILED, CANCELLED }
data class AcademicRequest(
    val id: Long,
    val phase: AcademicPhase,
    val deadline: Long? = null,
    val message: String? = null,
) {
    val active get() = phase == AcademicPhase.WAITING || phase == AcademicPhase.RUNNING || phase == AcademicPhase.SAVING
    val needsBrowser get() = phase == AcademicPhase.WAITING || phase == AcademicPhase.RUNNING
}

/** 单线程使用的请求门禁；独立于 Android，覆盖重入、重建及迟到回调。 */
internal class AcademicRequests {
    private var sequence = 0L
    private val requests = mutableMapOf<AcademicTask, AcademicRequest>()
    operator fun get(task: AcademicTask): AcademicRequest? = requests[task]
    fun beginLogin(importSchedule: Boolean) {
        if (importSchedule) begin(AcademicTask.IMPORT)
        begin(AcademicTask.GRADES)
    }
    fun begin(task: AcademicTask): AcademicRequest {
        requests[task]?.takeIf { it.active }?.let { return it }
        return AcademicRequest(++sequence, AcademicPhase.WAITING).also { requests[task] = it }
    }
    fun launch(task: AcademicTask, now: Long): Long? {
        val request = requests[task]?.takeIf { it.phase == AcademicPhase.WAITING } ?: return null
        requests[task] = request.copy(phase = AcademicPhase.RUNNING, deadline = request.deadline ?: now + TIMEOUT_MS)
        return request.id
    }
    fun accepts(task: AcademicTask, id: Long): Boolean =
        requests[task]?.let { it.id == id && it.phase == AcademicPhase.RUNNING } == true
    fun isSaving(task: AcademicTask, id: Long): Boolean =
        requests[task]?.let { it.id == id && it.phase == AcademicPhase.SAVING } == true
    fun saving(task: AcademicTask, id: Long): Boolean {
        if (!accepts(task, id)) return false
        requests[task] = requests.getValue(task).copy(phase = AcademicPhase.SAVING)
        return true
    }
    fun finish(task: AcademicTask, id: Long, message: String? = null, failed: Boolean = false): Boolean {
        val request = requests[task]?.takeIf { it.id == id && it.active } ?: return false
        requests[task] = request.copy(phase = if (failed) AcademicPhase.FAILED else AcademicPhase.SUCCESS, message = message)
        return true
    }
    fun expired(task: AcademicTask, id: Long, now: Long): Boolean =
        requests[task]?.let { it.id == id && it.active && it.deadline?.let { end -> now >= end } == true } == true
    fun cancel(task: AcademicTask) {
        requests[task]?.let { requests[task] = it.copy(phase = AcademicPhase.CANCELLED) }
    }
    fun recoverBrowser() {
        AcademicTask.entries.forEach { task ->
            requests[task]?.takeIf { it.phase == AcademicPhase.RUNNING }?.let {
                // 截止时刻沿用原请求，旋转或导航不能不断延长超时。
                requests[task] = it.copy(id = ++sequence, phase = AcademicPhase.WAITING)
            }
        }
    }
    companion object { const val TIMEOUT_MS = 90_000L }
}
