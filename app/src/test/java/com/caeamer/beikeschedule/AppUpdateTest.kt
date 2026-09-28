package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.update.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AppUpdateTest {
    private val repository = "https://github.com/study-233/BeikeSchedulePro"

    private fun asset(name: String, id: Long = 1, url: String = "$repository/releases/download/v1.2.0/$name") =
        """{"id":$id,"name":"$name","state":"uploaded","size":1024,"browser_download_url":"$url"}"""

    private fun release(assets: String = "", tag: String = "v1.2.0", extra: String = "") =
        """{"tag_name":"$tag","body":null,"html_url":"$repository/releases/tag/$tag","assets":[$assets]$extra}"""

    @Test fun `数字版本比较兼容前缀 缺省分段和大整数`() {
        assertEquals(0, AppVersion.compare("V1.2", "v1.2.0"))
        assertTrue(AppVersion.compare("1.10.0", "1.9.9") > 0)
        assertTrue(AppVersion.compare("1.999999999999999999999", "1.2147483647") > 0)
        assertTrue(AppVersion.compare("0.9", "1.0") < 0)
    }

    @Test fun `异常版本和预发布后缀不降级成零`() {
        listOf("", "latest", "1..2", "1.-1", "1.2-beta", "vv1.2", "vV1.2").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { AppVersion.compare(value, "1.0") }
        }
    }

    @Test fun `优先使用正式命名附件 不依赖附件顺序`() {
        val expected = "BeikeSchedulePro-v1.2.0.apk"
        val result = ReleaseParser.parse(release(listOf(asset("other.apk"), asset(expected, 2)).joinToString()))
        assertEquals(expected, result.asset?.name)
        assertEquals("", result.notes)
        assertEquals("1.2.0", result.version)
    }

    @Test fun `只有一个非标准命名APK时允许下载`() {
        assertEquals("app-release.apk", ReleaseParser.parse(release(asset("app-release.apk"))).asset?.name)
    }

    @Test fun `多个候选 无附件 或重复正式附件都退回网页`() {
        assertNull(ReleaseParser.parse(release()).asset)
        assertNull(ReleaseParser.parse(release("${asset("a.apk")},${asset("b.apk", 2)}")).asset)
        val expected = "BeikeSchedulePro-v1.2.0.apk"
        assertNull(ReleaseParser.parse(release("${asset(expected)},${asset(expected, 2)}")).asset)
    }

    @Test fun `不选取源码压缩包 未上传附件 或空附件`() {
        assertNull(ReleaseParser.parse(release(asset("source.zip"))).asset)
        assertNull(ReleaseParser.parse(release(asset("a.apk").replace("uploaded", "starter"))).asset)
        assertNull(ReleaseParser.parse(release(asset("a.apk").replace("1024", "0"))).asset)
    }

    @Test fun `拒绝草稿和预发布`() {
        assertThrows(IllegalArgumentException::class.java) { ReleaseParser.parse(release(extra = ",\"prerelease\":true")) }
        assertThrows(IllegalArgumentException::class.java) { ReleaseParser.parse(release(extra = ",\"draft\":true")) }
    }

    @Test fun `附件必须来自本仓库HTTPS发布地址`() {
        listOf("http://github.com/study-233/BeikeSchedulePro/releases/download/v1/a.apk",
            "https://example.com/a.apk", "https://github.com/other/repo/releases/download/v1/a.apk",
            "https://github.com.evil.test/study-233/BeikeSchedulePro/releases/download/v1/a.apk").forEach { url ->
            assertNull(ReleaseParser.parse(release(asset("a.apk", url = url))).asset)
        }
    }

    @Test fun `保留GitHub摘要 缺少摘要也可使用签名校验`() {
        val digest = "sha256:" + "a".repeat(64)
        val json = asset("a.apk").dropLast(1) + ",\"digest\":\"$digest\"}"
        assertEquals(digest, ReleaseParser.parse(release(json)).asset?.digest)
        assertNull(ReleaseParser.parse(release(asset("a.apk"))).asset?.digest)
    }

    @Test fun `每日检查按尝试时间限频并处理时钟回拨`() {
        val now = 10 * UpdatePolicy.DAY
        assertTrue(UpdatePolicy.shouldCheck(0, now))
        assertFalse(UpdatePolicy.shouldCheck(now, now))
        assertFalse(UpdatePolicy.shouldCheck(now, now + UpdatePolicy.DAY - 1))
        assertTrue(UpdatePolicy.shouldCheck(now, now + UpdatePolicy.DAY))
        assertTrue(UpdatePolicy.shouldCheck(now, now - 1))
    }

    @Test fun `稍后提醒满24小时后恢复 忽略只针对一个版本`() {
        val now = 10 * UpdatePolicy.DAY
        val later = UpdatePreferences(remindAfter = now + UpdatePolicy.DAY)
        assertFalse(UpdatePolicy.shouldPrompt(later, "1.2.0", now + UpdatePolicy.DAY - 1))
        assertTrue(UpdatePolicy.shouldPrompt(later, "1.2.0", now + UpdatePolicy.DAY))
        val ignored = UpdatePreferences(ignoredVersion = "1.2.0")
        assertFalse(UpdatePolicy.shouldPrompt(ignored, "1.2.0", now))
        assertTrue(UpdatePolicy.shouldPrompt(ignored, "1.3.0", now))
    }

    @Test fun `未写回任务ID时按目标恢复 不认领其他下载`() {
        val rows = listOf(DownloadRecord(1, "file:///other.apk"), DownloadRecord(2, "file:///target.apk"))
        assertEquals(2L, UpdatePolicy.matchingDownload(0, "file:///target.apk", rows)?.id)
        assertEquals(2L, UpdatePolicy.matchingDownload(2, "file:///target.apk", rows)?.id)
        assertNull(UpdatePolicy.matchingDownload(1, "file:///target.apk", rows))
        assertNull(UpdatePolicy.matchingDownload(3, "file:///target.apk", rows))
        assertNull(UpdatePolicy.matchingDownload(0, "file:///missing.apk", rows))
    }

    @Test fun `下载任务及网络选择可跨进程保存`() {
        val parsed = ReleaseParser.parse(release(asset("a.apk")))
        val original = UpdatePreferences(lastAttempt = 10, remindAfter = 20, ignoredVersion = "1.1.0",
            download = UpdateDownload(parsed, "00000000-0000-0000-0000-000000000000.apk", false, 123))
        assertEquals(original, Json.decodeFromString<UpdatePreferences>(Json.encodeToString(original)))
    }

    private val installed = ApkIdentity("io.github.study233.beikeschedulepro", "1.1.0", 2, setOf("release-key"))
    private val candidate = installed.copy(versionName = "1.2.0", versionCode = 3)

    @Test fun `安装包大小与SHA256必须匹配`() {
        ApkValidation.validateSize(1024, 1024)
        assertThrows(IllegalArgumentException::class.java) { ApkValidation.validateSize(1024, 1023) }
        assertThrows(IllegalArgumentException::class.java) { ApkValidation.validateSize(0, 0) }
        ApkValidation.validateDigest("sha256:" + "A".repeat(64), "a".repeat(64))
        assertThrows(IllegalArgumentException::class.java) { ApkValidation.validateDigest("sha256:" + "a".repeat(64), "b".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { ApkValidation.validateDigest("sha256:bad", "bad") }
        assertThrows(IllegalArgumentException::class.java) { ApkValidation.validateDigest("md5:" + "a".repeat(32), "a".repeat(32)) }
    }

    @Test fun `允许相同签名的正式升级`() {
        ApkValidation.validate(installed, candidate, "v1.2.0")
    }

    @Test fun `拒绝错误包名 版本码和发行版本`() {
        assertThrows(IllegalArgumentException::class.java) { ApkValidation.validate(installed, candidate.copy(packageName = "other"), "1.2.0") }
        assertThrows(IllegalArgumentException::class.java) { ApkValidation.validate(installed, candidate.copy(versionCode = 2), "1.2.0") }
        assertThrows(IllegalArgumentException::class.java) { ApkValidation.validate(installed, candidate, "1.3.0") }
        assertThrows(IllegalArgumentException::class.java) { ApkValidation.validate(installed, candidate.copy(versionName = "1.0"), "1.0") }
    }

    @Test fun `拒绝debug签名和缺失证书`() {
        val debug = candidate.copy(signers = setOf("debug-key"), signingHistory = setOf("debug-key"))
        assertThrows(IllegalArgumentException::class.java) { ApkValidation.validate(installed, debug, "1.2.0") }
        assertThrows(IllegalArgumentException::class.java) { ApkValidation.validate(installed, candidate.copy(signers = emptySet()), "1.2.0") }
    }

    @Test fun `证书轮换需包含已安装证书 多签名需完全匹配`() {
        ApkValidation.validate(installed, candidate.copy(signers = setOf("new-key"), signingHistory = setOf("release-key", "new-key")), "1.2.0")
        val multi = installed.copy(signers = setOf("a", "b"))
        ApkValidation.validate(multi, candidate.copy(signers = setOf("b", "a")), "1.2.0")
        assertThrows(IllegalArgumentException::class.java) { ApkValidation.validate(multi, candidate.copy(signers = setOf("a")), "1.2.0") }
    }
}
