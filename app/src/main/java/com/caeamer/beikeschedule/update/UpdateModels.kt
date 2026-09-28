package com.caeamer.beikeschedule.update

import com.caeamer.beikeschedule.AppInfo
import java.math.BigInteger
import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** 正式发行版只接受数字版本，不能把无法解析的版本当作 0。 */
object AppVersion {
    fun normalize(value: String): String {
        val trimmed = value.trim()
        val version = if (trimmed.startsWith("v", ignoreCase = true)) trimmed.drop(1) else trimmed
        require(Regex("[0-9]+(\\.[0-9]+)*").matches(version)) { "无法识别版本号：$value" }
        return version
    }

    fun compare(a: String, b: String): Int {
        val left = normalize(a).split('.').map(::BigInteger)
        val right = normalize(b).split('.').map(::BigInteger)
        for (index in 0 until maxOf(left.size, right.size)) {
            val result = (left.getOrNull(index) ?: BigInteger.ZERO)
                .compareTo(right.getOrNull(index) ?: BigInteger.ZERO)
            if (result != 0) return result
        }
        return 0
    }
}

@Serializable
data class ReleaseAsset(val id: Long, val name: String, val url: String, val size: Long, val digest: String? = null)

@Serializable
data class AppRelease(val version: String, val notes: String, val pageUrl: String, val asset: ReleaseAsset?)

object ReleaseParser {
    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull

    fun parse(body: String): AppRelease {
        val root = Json.parseToJsonElement(body).jsonObject
        require(root["draft"]?.jsonPrimitive?.booleanOrNull != true &&
            root["prerelease"]?.jsonPrimitive?.booleanOrNull != true) { "暂不支持预发布版本" }
        val version = AppVersion.normalize(root.string("tag_name") ?: error("响应缺少版本号"))
        val assets = (root["assets"] as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element.jsonObject
            val name = item.string("name") ?: return@mapNotNull null
            val url = item.string("browser_download_url") ?: return@mapNotNull null
            if (!name.endsWith(".apk", ignoreCase = true) || item.string("state") != "uploaded" ||
                !isRepositoryUrl(url, download = true)) return@mapNotNull null
            val id = item["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val size = item["size"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            if (id <= 0 || size <= 0) return@mapNotNull null
            ReleaseAsset(id, name, url, size, item.string("digest"))
        }
        val expected = "BeikeSchedulePro-v$version.apk"
        val preferred = assets.filter { it.name == expected }
        val asset = if (preferred.isNotEmpty()) preferred.singleOrNull() else assets.singleOrNull()
        val page = root.string("html_url")?.takeIf { isRepositoryUrl(it) }
            ?: "${AppInfo.REPO_URL}/releases"
        return AppRelease(version, root.string("body").orEmpty().take(20_000), page, asset)
    }

    fun isRepositoryUrl(value: String, download: Boolean = false): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null && uri.port == -1 &&
            uri.path.startsWith("/${AppInfo.REPOSITORY}/releases/" + if (download) "download/" else "")
    }.getOrDefault(false)
}

@Serializable
data class UpdateDownload(
    val release: AppRelease,
    val fileName: String,
    val allowMobile: Boolean,
    val id: Long = 0,
)

@Serializable
data class UpdatePreferences(
    val lastAttempt: Long = 0,
    val remindAfter: Long = 0,
    val ignoredVersion: String? = null,
    val download: UpdateDownload? = null,
)

object UpdatePolicy {
    const val DAY = 24 * 60 * 60 * 1000L
    fun shouldCheck(lastAttempt: Long, now: Long) = lastAttempt == 0L || now < lastAttempt || now - lastAttempt >= DAY
    fun shouldPrompt(preferences: UpdatePreferences, version: String, now: Long) =
        preferences.ignoredVersion != version && now >= preferences.remindAfter

    /** id 尚未保存时按本地目标恢复 enqueue 成功的任务，防止进程退出后重复下载。 */
    fun matchingDownload(savedId: Long, destination: String, rows: List<DownloadRecord>): DownloadRecord? =
        rows.firstOrNull { (savedId == 0L || it.id == savedId) && it.destination == destination }
}

data class DownloadRecord(val id: Long, val destination: String)

enum class UpdatePhase { IDLE, CHECKING, LATEST, AVAILABLE, DOWNLOADING, WAITING, VERIFYING, READY, FAILED }
enum class UpdateRetry { CHECK, DOWNLOAD }

data class UpdateUiState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val release: AppRelease? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val message: String = "",
    val dialogVisible: Boolean = false,
    val mobileConfirmation: Boolean = false,
    val busy: Boolean = false,
    val retry: UpdateRetry = UpdateRetry.CHECK,
) {
    val hasDownload: Boolean get() = phase in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.WAITING, UpdatePhase.VERIFYING, UpdatePhase.READY)
    val summary: String get() = when (phase) {
        UpdatePhase.IDLE -> "每天自动检查，也可手动检查"
        UpdatePhase.CHECKING -> "正在检查…"
        UpdatePhase.LATEST -> "已是最新版本"
        UpdatePhase.AVAILABLE -> "发现新版本 v${release?.version}"
        UpdatePhase.DOWNLOADING -> "正在下载更新"
        UpdatePhase.WAITING -> message
        UpdatePhase.VERIFYING -> "正在校验安装包…"
        UpdatePhase.READY -> "更新已下载，点击安装"
        UpdatePhase.FAILED -> message
    }
}

data class ApkIdentity(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val signers: Set<String>,
    val signingHistory: Set<String> = signers,
)

object ApkValidation {
    fun validateSize(expected: Long, actual: Long) {
        require(expected > 0 && actual == expected) { "安装包大小不符，请重新下载" }
    }

    fun validateDigest(expected: String, actualSha256: String) {
        require(Regex("sha256:[a-fA-F0-9]{64}").matches(expected)) { "无法验证安装包摘要，请前往发布网页确认" }
        require(actualSha256.equals(expected.substringAfter(':'), ignoreCase = true)) { "安装包摘要不符，请重新下载" }
    }

    fun validate(installed: ApkIdentity, candidate: ApkIdentity, target: String) {
        require(candidate.packageName == installed.packageName) { "安装包不属于本应用" }
        require(candidate.versionCode > installed.versionCode) { "安装包版本码未高于当前版本" }
        require(AppVersion.compare(candidate.versionName, target) == 0) { "安装包版本与更新信息不一致" }
        require(AppVersion.compare(candidate.versionName, installed.versionName) > 0) { "安装包不是更新版本" }
        require(installed.signers.isNotEmpty() && candidate.signers.isNotEmpty()) { "无法读取安装包签名" }
        val compatible = if (installed.signers.size > 1 || candidate.signers.size > 1) {
            installed.signers == candidate.signers
        } else {
            installed.signers == candidate.signers || candidate.signingHistory.containsAll(installed.signers)
        }
        require(compatible) { "安装包签名与当前应用不兼容，请使用相同签名的正式版本" }
    }
}
