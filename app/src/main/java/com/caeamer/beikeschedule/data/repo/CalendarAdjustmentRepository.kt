package com.caeamer.beikeschedule.data.repo

import android.content.Context
import androidx.room.withTransaction
import com.caeamer.beikeschedule.AppInfo
import com.caeamer.beikeschedule.data.local.AppDatabase
import com.caeamer.beikeschedule.data.local.CalendarAdjustmentCache
import com.caeamer.beikeschedule.model.CalendarAdjustmentCodec
import com.caeamer.beikeschedule.reminder.ClassReminderScheduler
import com.caeamer.beikeschedule.widget.WidgetUpdateCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.HttpURLConnection
import java.net.URL

/** 前台/手动检查共用进程级实例；页面退出不打断已开始的缓存更新。 */
class CalendarAdjustmentRepository internal constructor(
    private val db: AppDatabase,
    private val fetch: suspend () -> String,
    private val now: () -> Long = System::currentTimeMillis,
    private val afterChanged: suspend () -> Unit = {},
) {
    private val dao = db.calendarAdjustmentDao()
    val status = dao.observe()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var running: Deferred<Unit>? = null

    suspend fun refresh(force: Boolean = false) {
        val work = mutex.withLock {
            running?.takeIf { !it.isCompleted } ?: scope.async { refreshOnce(force) }.also { running = it }
        }
        work.await()
    }

    private suspend fun refreshOnce(force: Boolean) {
        val previous = dao.get() ?: CalendarAdjustmentCache()
        val checkedAt = now()
        if (!force && !shouldCheck(previous.lastCheckedAt, checkedAt)) return
        dao.save(previous.copy(lastCheckedAt = checkedAt, status = "正在检查调休配置…"))
        try {
            val incoming = CalendarAdjustmentCodec.parse(fetch())
            var changed = false
            db.withTransaction {
                val current = dao.get() ?: previous
                val old = current.body.takeIf { it.isNotBlank() }?.let(CalendarAdjustmentCodec::parse)
                val replace = CalendarAdjustmentCodec.shouldReplace(old, incoming)
                // 仅实际规则变化才使旧提醒失效；只递增版本不必取消已到点闹钟。
                changed = replace && old?.semesters != incoming.semesters
                dao.save(current.copy(
                    body = if (replace) CalendarAdjustmentCodec.encode(incoming) else current.body,
                    updatedAt = if (replace) now() else current.updatedAt,
                    status = "调休配置已更新至版本 ${incoming.revision}",
                ))
                if (changed) db.scheduleDao().state()?.let {
                    db.scheduleDao().saveState(it.copy(reminderVersion = it.reminderVersion + 1))
                }
            }
            if (changed) {
                try { afterChanged() } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    dao.get()?.let { dao.save(it.copy(status = "调休已保存，上课提醒暂未更新，请重新打开应用重试")) }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // 解析器的固定校验消息可展示；不显示远端正文或底层网络异常。
            val reason = if (e is IllegalArgumentException && e !is kotlinx.serialization.SerializationException)
                e.message ?: "配置校验失败" else "下载或解析失败"
            dao.get()?.let { dao.save(it.copy(status = "$reason；已保留本地调休配置")) }
        }
    }

    companion object {
        const val URL_PATH = "https://raw.githubusercontent.com/${AppInfo.REPOSITORY}/main/data/calendar-adjustments.json"
        private const val INTERVAL = 24 * 60 * 60 * 1000L
        internal fun shouldCheck(last: Long, now: Long): Boolean = last == 0L || now < last || now - last >= INTERVAL

        @Volatile private var instance: CalendarAdjustmentRepository? = null
        fun get(context: Context): CalendarAdjustmentRepository = instance ?: synchronized(this) {
            instance ?: context.applicationContext.let { app ->
                CalendarAdjustmentRepository(AppDatabase.get(app), ::download, afterChanged = {
                    WidgetUpdateCoordinator.requestRefresh(app)
                    ClassReminderScheduler.reschedule(app)
                }).also { instance = it }
            }
        }

        private suspend fun download(): String {
            val connection = URL(URL_PATH).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "BeikeSchedulePro")
                connection.setRequestProperty("Cache-Control", "no-cache")
                check(connection.responseCode == 200) { "调休配置下载失败" }
                return connection.inputStream.use { stream ->
                    val result = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = stream.read(buffer)
                        if (count < 0) break
                        require(result.size() + count <= CalendarAdjustmentCodec.MAX_BYTES) { "调休配置过大" }
                        result.write(buffer, 0, count)
                    }
                    result.toString(Charsets.UTF_8.name())
                }
            } finally { connection.disconnect() }
        }
    }
}
