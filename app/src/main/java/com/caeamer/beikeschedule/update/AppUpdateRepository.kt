package com.caeamer.beikeschedule.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.caeamer.beikeschedule.AppInfo
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class DownloadProgress(val status: Int, val bytes: Long, val total: Long, val reason: Int)

/** 仅管理本应用明确创建的更新任务；不读取教务会话，不访问公共下载目录。 */
class AppUpdateRepository(private val context: Context) {
    val store = UpdateStore(context)
    private val manager get() = context.getSystemService(DownloadManager::class.java)
        ?: error("系统下载服务不可用，请前往网页下载")
    private val packageFlags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())

    fun installedVersion(): String = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        ?: error("无法读取本机版本号")

    suspend fun latest(): AppRelease = withContext(Dispatchers.IO) {
        val connection = URL(AppInfo.RELEASES_API).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "BeikeSchedulePro")
            check(connection.responseCode == 200) { "GitHub 请求失败（HTTP ${connection.responseCode}）" }
            // 防止异常响应无限占用内存，更新说明在解析后另行限制长度。
            val body = connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(8192)
                buildString {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = reader.read(buffer)
                        if (count < 0) break
                        require(length + count <= 2_000_000) { "更新响应过大" }
                        append(buffer, 0, count)
                    }
                }
            }
            ReleaseParser.parse(body)
        } finally {
            connection.disconnect()
        }
    }

    fun needsMobileConfirmation(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return true
        return !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || connectivity.isActiveNetworkMetered
    }

    private fun directory(): File {
        val downloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: error("更新存储目录不可用")
        return File(downloads, "updates").apply { check(isDirectory || mkdirs()) { "无法创建更新目录" } }
    }

    private fun file(task: UpdateDownload): File {
        require(Regex("[a-f0-9-]{36}\\.apk").matches(task.fileName)) { "更新文件记录无效" }
        return File(directory(), task.fileName)
    }

    /** 先写入目标文件，再 enqueue；若此时进程退出，restore 按目标路径认领已有任务。 */
    suspend fun start(release: AppRelease, allowMobile: Boolean): UpdateDownload = withContext(Dispatchers.IO + NonCancellable) {
        val previous = restore()
        if (previous != null && previous.release == release) return@withContext previous
        if (previous != null) cancel(previous)
        val task = UpdateDownload(release, "${UUID.randomUUID()}.apk", allowMobile)
        store.edit { it.copy(download = task, ignoredVersion = null, remindAfter = 0) }
        enqueue(task)
    }

    private suspend fun enqueue(task: UpdateDownload): UpdateDownload {
        val asset = task.release.asset ?: error("此版本没有可直接下载的 APK")
        require(ReleaseParser.isRepositoryUrl(asset.url, download = true)) { "更新下载地址无效" }
        file(task) // 校验文件名和存储可用性。
        val request = DownloadManager.Request(Uri.parse(asset.url))
            .setTitle("贝壳课表 v${task.release.version}")
            .setDescription("下载完成后返回贝壳课表安装")
            .setMimeType("application/vnd.android.package-archive")
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "updates/${task.fileName}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(task.allowMobile)
            .setAllowedOverRoaming(false)
            .setAllowedNetworkTypes(if (task.allowMobile) DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
                else DownloadManager.Request.NETWORK_WIFI)
        val saved = task.copy(id = manager.enqueue(request))
        store.edit { it.copy(download = saved) }
        return saved
    }

    suspend fun restore(): UpdateDownload? = withContext(Dispatchers.IO + NonCancellable) {
        val task = store.read().download
        if (task == null) {
            cleanupFiles(null)
            return@withContext null
        }
        val match = findDownload(task)
        val recovered = if (match != null) task.copy(id = match.id) else task
        if (AppVersion.compare(task.release.version, installedVersion()) <= 0) {
            cancel(recovered)
            cleanupFiles(null)
            return@withContext null
        }
        cleanupFiles(task.fileName)
        when {
            match != null -> recovered.also { if (it.id != task.id) store.edit { prefs -> prefs.copy(download = it) } }
            task.id == 0L -> {
                file(task).delete()
                enqueue(task)
            }
            else -> {
                cancel(task)
                error("下载任务已被系统移除，请重新下载")
            }
        }
    }

    private fun findDownload(task: UpdateDownload): DownloadRecord? {
        val destination = Uri.fromFile(file(task)).toString()
        val rows = mutableListOf<DownloadRecord>()
        val query = DownloadManager.Query().apply { if (task.id != 0L) setFilterById(task.id) }
        val result = manager.query(query) ?: error("无法读取系统下载任务，请稍后重试")
        result.use { cursor ->
            while (cursor.moveToNext()) {
                rows += DownloadRecord(cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)),
                    cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)).orEmpty())
            }
        }
        return UpdatePolicy.matchingDownload(task.id, destination, rows)
    }

    suspend fun progress(task: UpdateDownload): DownloadProgress = withContext(Dispatchers.IO) {
        manager.query(DownloadManager.Query().setFilterById(task.id))?.use { cursor ->
            check(cursor.moveToFirst()) { "下载任务已被系统移除，请重新下载" }
            fun number(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
            DownloadProgress(number(DownloadManager.COLUMN_STATUS).toInt(),
                number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR), number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                number(DownloadManager.COLUMN_REASON).toInt())
        } ?: error("无法读取下载状态")
    }

    suspend fun cancel(task: UpdateDownload) = withContext(Dispatchers.IO + NonCancellable) {
        findDownload(task)?.let { manager.remove(it.id) }
        file(task).delete()
        store.edit { if (it.download?.fileName == task.fileName) it.copy(download = null) else it }
    }

    private fun cleanupFiles(keep: String?) {
        val downloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        File(downloads, "updates").listFiles()?.filter { it.name != keep && it.isFile && Regex("[a-f0-9-]{36}\\.apk").matches(it.name) }
            ?.forEach { it.delete() }
    }

    suspend fun validate(task: UpdateDownload): File = withContext(Dispatchers.IO) {
        val apk = file(task)
        val asset = task.release.asset ?: error("更新缺少附件信息")
        require(apk.isFile) { "安装包文件已丢失，请重新下载" }
        ApkValidation.validateSize(asset.size, apk.length())
        asset.digest?.let { digest ->
            val hash = MessageDigest.getInstance("SHA-256")
            apk.inputStream().use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    hash.update(buffer, 0, count)
                }
            }
            ApkValidation.validateDigest(digest, hash.digest().hex())
        }
        val pm = context.packageManager
        val installed = pm.getPackageInfo(context.packageName, packageFlags).identity()
        val candidate = pm.getPackageArchiveInfo(apk.absolutePath, packageFlags)?.identity()
            ?: error("无法解析安装包，请重新下载")
        ApkValidation.validate(installed, candidate, task.release.version)
        apk
    }

    suspend fun installIntent(task: UpdateDownload): Intent {
        val apk = validate(task)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun PackageInfo.identity(): ApkIdentity {
        val signing = signingInfo
        fun certificates(values: Array<android.content.pm.Signature>?) = values.orEmpty().map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex()
        }.toSet()
        val signers = certificates(signing?.apkContentsSigners)
        return ApkIdentity(packageName, versionName.orEmpty(), longVersionCode, signers,
            if (signing?.hasMultipleSigners() == false) certificates(signing.signingCertificateHistory) else signers)
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
