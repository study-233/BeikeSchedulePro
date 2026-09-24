package com.caeamer.beikeschedule.import

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 学校微认证二维码里的临时链接；只接受截图中已确认的学校扫码端点。 */
internal fun isValidJwWechatLoginUrl(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    if (!uri.host.equals("sis.ustb.edu.cn", ignoreCase = true)) return false
    if (uri.port != -1 && uri.port != 443) return false
    if (uri.userInfo != null || uri.fragment != null || uri.path != "/scan/async") return false
    val sidValues = uri.rawQuery.orEmpty().split('&')
        .filter { it.substringBefore('=') == "sid" }
        .map { it.substringAfter('=', "") }
    if (sidValues.size != 1) return false
    return runCatching { URLDecoder.decode(sidValues.single(), "UTF-8").isNotBlank() }
        .getOrDefault(false)
}

/** 只在用户点击分享时截取当前 WebView，避免把过期二维码发到微信。必须在主线程调用。 */
private fun captureWebView(view: WebView): Bitmap? {
    if (view.width <= 0 || view.height <= 0) return null
    return Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        view.draw(canvas)
    }
}

private fun decodeJwWechatQr(bitmap: Bitmap): String? {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
    val text = runCatching {
        QRCodeReader().decode(
            BinaryBitmap(HybridBinarizer(source)),
            mapOf(DecodeHintType.TRY_HARDER to true),
        ).text
    }.getOrNull() ?: return null
    return text.takeIf(::isValidJwWechatLoginUrl)
}

/** 导入和成绩页共用的同机微信登录入口，WebView 留在原位等待学校认证回跳。 */
@Composable
internal fun JwWechatLoginPanel(
    webView: WebView?,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preparing by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("微信扫码登录", style = MaterialTheme.typography.titleSmall)
            Text(
                "把登录链接发给微信文件传输助手，在微信里打开并确认，然后返回本页。",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = {
                    val view = webView ?: return@Button
                    preparing = true
                    scope.launch {
                        val bitmap = runCatching { captureWebView(view) }.getOrNull()
                        val link = if (bitmap == null) null else try {
                            withContext(Dispatchers.Default) {
                                runCatching { decodeJwWechatQr(bitmap) }.getOrNull()
                            }
                        } finally {
                            bitmap.recycle()
                        }
                        preparing = false
                        if (link == null) {
                            onMessage("未识别到当前登录二维码。请等待网页加载，或刷新扫码页后重试。")
                            return@launch
                        }
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, link)
                            setPackage("com.tencent.mm")
                        }
                        try {
                            context.startActivity(send)
                        } catch (_: ActivityNotFoundException) {
                            onMessage("未找到可接收登录链接的微信。请安装微信，或使用网页上的其他登录方式。")
                        } catch (_: SecurityException) {
                            onMessage("无法打开微信分享。请使用网页上的其他登录方式。")
                        }
                    }
                },
                enabled = webView != null && !preparing,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(if (preparing) "正在读取登录码…" else "发送登录链接到微信")
            }
        }
    }
}
