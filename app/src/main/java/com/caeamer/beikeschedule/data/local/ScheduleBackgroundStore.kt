package com.caeamer.beikeschedule.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.caeamer.beikeschedule.model.ScheduleAppearance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.roundToInt

/** 背景仅存于本机；所有解码和文件操作都在 IO 线程执行。 */
class ScheduleBackgroundStore(context: Context) {
    private val app = context.applicationContext
    private val directory = File(app.filesDir, "schedule_backgrounds")

    private fun file(name: String): File? = name
        .takeIf { it.isNotEmpty() && ScheduleAppearance(backgroundFile = it).normalized().backgroundFile == it }
        ?.let { File(directory, it) }

    suspend fun importImage(uri: Uri): String = withContext(Dispatchers.IO) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建背景目录")
        val name = "${UUID.randomUUID()}.png"
        val target = checkNotNull(file(name))
        try {
            // ImageDecoder 处理方向信息；解码前限制尺寸，避免先载入整张大图。
            val bitmap = decode(ImageDecoder.createSource(app.contentResolver, uri))
            try {
                target.outputStream().use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        throw IOException("无法保存背景图片")
                    }
                }
            } finally {
                bitmap.recycle()
            }
            name
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    suspend fun load(name: String): Bitmap? = withContext(Dispatchers.IO) {
        val source = file(name)?.takeIf { it.isFile } ?: return@withContext null
        try {
            decode(ImageDecoder.createSource(source))
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** 只清理本组件生成的文件；必须在新配置成功落盘后调用。 */
    suspend fun removeUnused(keep: String) = withContext(Dispatchers.IO) {
        // 清理是提交后的尽力操作，不能将成功的图片替换报告为失败。
        try {
            directory.listFiles()?.filter { it.name != keep && file(it.name) != null }
                ?.forEach { it.delete() }
        } catch (_: SecurityException) {
            // 后续替换或恢复默认时重试。
        }
        Unit
    }

    suspend fun remove(name: String) = withContext(Dispatchers.IO) {
        file(name)?.delete()
        Unit
    }

    private fun decode(source: ImageDecoder.Source): Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val ratio = (2048f / maxOf(info.size.width, info.size.height)).coerceAtMost(1f)
        decoder.setTargetSize(
            (info.size.width * ratio).roundToInt().coerceAtLeast(1),
            (info.size.height * ratio).roundToInt().coerceAtLeast(1),
        )
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
}
