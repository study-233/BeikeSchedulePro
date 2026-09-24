package com.caeamer.beikeschedule.import

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

private const val JW_HOME = "https://byyt.ustb.edu.cn"
private const val MAIN_PAGE_MARK = "/authentication/main"

/** 只在教务首页找学校自己的入口，由页面生成 SSO 跳转参数，不拼接认证 URL。 */
private const val OPEN_SCHOOL_AUTH_JS = """
(function () {
  if (window.__bkAuthSearchStarted) return;
  window.__bkAuthSearchStarted = true;
  var until = Date.now() + 10000;
  var timer = setInterval(function () {
    var nodes = document.querySelectorAll('a, button, [role="button"], input[type="button"]');
    for (var i = 0; i < nodes.length; i++) {
      var node = nodes[i];
      var label = (node.innerText || node.value || '').replace(/\s/g, '');
      if (label.indexOf('统一身份认证登录') < 0 || !node.getClientRects().length) continue;
      clearInterval(timer);
      if (node.tagName === 'A') node.removeAttribute('target');
      node.click();
      return;
    }
    if (Date.now() >= until) clearInterval(timer);
  }, 250);
})();
"""

/**
 * 学校二维码实际上在 sis.ustb.edu.cn 的 iframe 中，网页靠 /connect/state 长轮询
 * 收到授权结果。学校脚本在请求报错时会延迟执行 location.reload()，切到微信期间
 * 网络请求一旦中断，返回 App 就可能生成新 sid，丢失刚授权的会话。这里仅替换状态请求的
 * 错误处理：保留原二维码重试，并在前台检查轮询是否停住。二维码过期仍由校方页面提示。
 * 它沿用学校页面自己的 getAuthState 和原 sid，不读取或传出任何登录参数。
 */
private const val QR_POLL_RECOVERY_JS = """
(function () {
  if (location.origin !== 'https://sis.ustb.edu.cn' ||
      location.pathname !== '/connect/qrpage' || window === window.top) return;
  var lastRecovery = 0;
  var watchdog = null;
  var retryTimer = null;
  var appForeground = true;
  var pendingLogin = null;
  function finishPendingLogin() {
    if (!appForeground || document.visibilityState === 'hidden' || !pendingLogin) return;
    var login = pendingLogin;
    pendingLogin = null;
    login.callback.apply(login.context, login.args);
  }
  function retrySameQr() {
    if (document.visibilityState === 'hidden' || typeof getAuthState !== 'function' ||
        typeof appId === 'undefined' || typeof retUrl === 'undefined' ||
        typeof randToken === 'undefined' || typeof sid === 'undefined' ||
        typeof stateUrl === 'undefined' || typeof showTip !== 'function') return;
    getAuthState(appId, retUrl, randToken, sid, stateUrl, showTip);
  }
  function installStateRetry() {
    if (typeof window.ajax !== 'function') return;
    var schoolAjax = window.ajax;
    window.ajax = function (options) {
      if (!options || options.url !== '/connect/state') {
        return schoolAjax.apply(this, arguments);
      }
      var request = Object.assign({}, options);
      request.success = function () {
        if (retryTimer !== null) clearTimeout(retryTimer);
        retryTimer = null;
        var response;
        try { response = JSON.parse(arguments[0]); } catch (e) { /* 交给学校原回调处理 */ }
        if (response && response.code === 1 &&
            (!appForeground || document.visibilityState === 'hidden')) {
          pendingLogin = {
            callback: options.success,
            context: this,
            args: Array.prototype.slice.call(arguments),
          };
          return;
        }
        return options.success.apply(this, arguments);
      };
      request.error = function () {
        if (retryTimer !== null) clearTimeout(retryTimer);
        retryTimer = setTimeout(function () {
          retryTimer = null;
          retrySameQr();
        }, 5000);
      };
      return schoolAjax.call(this, request);
    };
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', installStateRetry, { once: true });
  } else {
    installStateRetry();
  }
  function recoverIfStalled() {
    if (document.visibilityState === 'hidden' || performance.now() < 25000) return;
    var tip = document.getElementById('tipInfo');
    if (tip && tip.textContent.indexOf('二维码已失效') >= 0) return;
    if (typeof getAuthState !== 'function' || typeof appId === 'undefined' ||
        typeof retUrl === 'undefined' || typeof randToken === 'undefined' ||
        typeof sid === 'undefined' || typeof stateUrl === 'undefined' ||
        typeof showTip !== 'function') return;
    var now = performance.now();
    if (now - lastRecovery < 20000) return;
    var requests = performance.getEntriesByType('resource');
    for (var i = requests.length - 1; i >= 0; i--) {
      if (requests[i].name.indexOf('/connect/state?') < 0) continue;
      if (now - requests[i].responseEnd < 20000) return;
      break;
    }
    lastRecovery = now;
    getAuthState(appId, retUrl, randToken, sid, stateUrl, showTip);
  }
  window.addEventListener('message', function (event) {
    if (event.source !== window.parent || event.origin !== 'https://sso.ustb.edu.cn') return;
    if (event.data === 'beike:pause-school-qr') {
      appForeground = false;
      return;
    }
    if (event.data !== 'beike:resume-school-qr') return;
    appForeground = true;
    if (pendingLogin) {
      finishPendingLogin();
      return;
    }
    if (watchdog !== null) clearInterval(watchdog);
    watchdog = setInterval(recoverIfStalled, 5000);
    recoverIfStalled();
  });
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'visible') {
      finishPendingLogin();
      recoverIfStalled();
    }
  });
})();
"""

/** 只通知学校的二维码 iframe；浏览器同源限制不允许主页面直接读取 iframe 的 sid。 */
private const val SCHOOL_QR_MESSAGE_JS = """
(function (message) {
  if (location.origin !== 'https://sso.ustb.edu.cn' || location.pathname !== '/ac-h5/') return;
  var frames = document.querySelectorAll('iframe');
  for (var i = 0; i < frames.length; i++) {
    try {
      var url = new URL(frames[i].src, location.href);
      if (url.origin === 'https://sis.ustb.edu.cn' && url.pathname === '/connect/qrpage') {
        frames[i].contentWindow.postMessage(message, url.origin);
      }
    } catch (e) { /* 尚未加载的 iframe 等下一次前台通知 */ }
  }
})('__MESSAGE__');
"""

private fun schoolQrMessageJs(message: String) = SCHOOL_QR_MESSAGE_JS.replace("__MESSAGE__", message)

/**
 * JS 桥允许注入的 origin 规则。
 *
 * 收口目标是"只有教务系统自己的页面能调用桥"：
 * - 教务站点本体（脚本实际注入的页面）；
 * - ustb.edu.cn 其它子域（统一身份认证可能在别的子域，登录页上手动抓取失败时
 *   仍需要把错误经桥报回界面，否则用户看到的是"点了没反应"）。
 *
 * 第三方 iframe（非 ustb 域）拿不到桥对象；回调里再要求主框架，双保险。
 */
private val BRIDGE_ORIGINS = setOf("https://byyt.ustb.edu.cn", "https://*.ustb.edu.cn")

/**
 * 教务页面渲染修正脚本，解决 WebView 白页：
 * 只对 byyt 教务页面注入；统一认证页有自己的手机布局，不能被改成桌面宽度。
 * 1. 教务页面 rem 适配按 1920px 桌面设计（fontSize = clientWidth/1920*37.5），
 *    而其 meta viewport 是 width=device-width → 手机上布局宽 360px、根字体仅 7px，
 *    须改写为 width=1440 恢复桌面比例（useWideViewPort 会被 meta 覆盖，只能注入改写）；
 * 2. .page{height:100vh} 在此 WebView 中 vh/百分比高度均算出 0（ICB 高度异常），
 *    导致 #app 高度 0 且 overflow:hidden 裁掉全部内容，须用 innerHeight 像素值补上。
 * 页面脚本监听视口变化会自动重算 rem，注入后无需刷新。脚本幂等，每次导航重复注入。
 */
private const val PAGE_FIX_JS = """
(function () {
  if (window.__bkPageFixInstalled) return;
  function fixAll() {
    if (!document.documentElement) return;
    var m = document.querySelector('meta[name="viewport"]');
    if (!m && document.head) {
      m = document.createElement('meta');
      m.name = 'viewport';
      document.head.appendChild(m);
    }
    if (m && m.getAttribute('content') !== 'width=1440') m.setAttribute('content', 'width=1440');
    var app = document.querySelector('#app');
    if (app && app.getBoundingClientRect().height === 0) {
      app.style.setProperty('height', window.innerHeight + 'px', 'important');
    }
  }
  function install() {
    // onPageStarted 可能发生在 <html>/<head> 解析之前；只有观察器成功挂载后才标记已安装。
    if (window.__bkPageFixInstalled || !document.documentElement) return;
    new MutationObserver(fixAll).observe(document.documentElement, { childList: true, subtree: true });
    window.__bkPageFixInstalled = true;
    fixAll();
    document.addEventListener('DOMContentLoaded', fixAll, { once: true });
    setTimeout(fixAll, 500);
    setTimeout(fixAll, 1500);
  }
  if (document.documentElement) install();
  else document.addEventListener('DOMContentLoaded', install, { once: true });
})();
"""

/** 短信登录的滑动拼图会向上弹出；WebView 比浏览器少了顶部空间时，图片会被裁掉。 */
private const val SMS_CAPTCHA_VIEWPORT_JS = """
(function () {
  if (location.origin !== 'https://sso.ustb.edu.cn' ||
      window.__bkCaptchaViewportInstalled) return;
  window.__bkCaptchaViewportInstalled = true;
  var scheduled = false;
  function fitCaptcha() {
    scheduled = false;
    if (!document.body) return;
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    var textNode;
    while ((textNode = walker.nextNode())) {
      if (!textNode.nodeValue || textNode.nodeValue.indexOf('向右滑动填充拼图') < 0) continue;
      var bar = textNode.parentElement;
      if (!bar || !bar.getClientRects().length) continue;
      var popup = bar;
      while (popup && popup !== document.body) {
        var bounds = popup.getBoundingClientRect();
        if (bounds.width >= 180 && bounds.height >= 140 &&
            !popup.querySelector('input[type="tel"], input[placeholder*="手机号"]')) break;
        popup = popup.parentElement;
      }
      if (!popup || popup === document.body) continue;
      var previous = Number(popup.dataset.bkCaptchaShift || 0);
      var top = popup.getBoundingClientRect().top;
      var images = popup.querySelectorAll('canvas, img');
      for (var i = 0; i < images.length; i++) {
        if (images[i].getClientRects().length) {
          top = Math.min(top, images[i].getBoundingClientRect().top);
        }
      }
      var shift = Math.max(0, Math.ceil(12 - (top - previous)));
      if (shift !== previous) {
        popup.dataset.bkCaptchaShift = String(shift);
        // 独立的 translate 不覆盖学校脚本用于定位浮层的 transform。
        popup.style.setProperty('translate', '0px ' + shift + 'px', 'important');
      }
      return;
    }
  }
  function schedule() {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(fitCaptcha);
  }
  function install() {
    if (!document.documentElement) {
      document.addEventListener('DOMContentLoaded', install, { once: true });
      return;
    }
    new MutationObserver(schedule).observe(document.documentElement, {
      childList: true, subtree: true, attributes: true, characterData: true,
      attributeFilter: ['class', 'style'],
    });
    addEventListener('resize', schedule);
    addEventListener('scroll', schedule, true);
    schedule();
  }
  install();
})();
"""

/**
 * 教务系统 WebView（导入页/成绩页共用）：
 * 登录统一认证 → 到达主页后回调 onMainPage（由调用方注入抓取脚本）。
 *
 * JS 桥（[bridge]/[bridgeName]）走 `WebViewCompat.addWebMessageListener`：
 * 对象只注入给下面的 [BRIDGE_ORIGINS] 列出的 origin，且回调里再校验"主框架 + 教务域名"。
 * 此前用 `addJavascriptInterface`，它对 WebView 里**每个 frame** 生效，
 * 白名单页面内嵌的第三方 iframe 能直接调用桥伪造数据（导航白名单管不到 iframe 与重定向）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun JwWebView(
    bridge: JwBridge,
    bridgeName: String,
    onMainPage: () -> Unit,
    onCreated: (WebView) -> Unit = {},
    onPageStarted: () -> Unit = {},
    onPageError: (String) -> Unit = {},
    onPageProgress: (Int) -> Unit = {},
    onAuthPageChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentView = remember { arrayOfNulls<WebView>(1) }
    // 完成地址含一次性 auth_code，只在内存里保留到前台导航完成，绝不输出到日志。
    val pendingCompletionUrl = remember { arrayOfNulls<String>(1) }
    val completionRetryCount = remember { intArrayOf(0) }
    fun continuePendingCompletion() {
        val view = currentView[0] ?: return
        val url = pendingCompletionUrl[0] ?: return
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        pendingCompletionUrl[0] = null
        view.postDelayed({
            if (currentView[0] === view &&
                lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            ) {
                view.loadUrl(url)
            } else if (currentView[0] === view) {
                pendingCompletionUrl[0] = url
            }
        }, 350)
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> currentView[0]?.let { view ->
                    view.evaluateJavascript(schoolQrMessageJs("beike:pause-school-qr"), null)
                }
                Lifecycle.Event.ON_RESUME -> {
                    currentView[0]?.let { view ->
                        view.onResume()
                        view.resumeTimers()
                        view.evaluateJavascript(schoolQrMessageJs("beike:resume-school-qr"), null)
                    }
                    continuePendingCompletion()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            // 仅调试包允许 DevTools 远程调试 WebView
            if (it.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
            WebView(it).apply {
                currentView[0] = this
                // 教务登录页 PC 布局加载慢，且默认白背景刺眼；设淡暖色底让加载过程更柔和
                setBackgroundColor(android.graphics.Color.parseColor("#F5EFEF"))
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // 配合 PAGE_FIX_JS 的 meta 改写：宽视口布局 + 总览缩放把 PC 页面缩放到一屏
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                // 显式关掉文件/content 访问与混合内容：这些在 targetSdk 37 下本就是安全默认值，
                // 但"依赖平台默认值"是隐式契约，写出来才能在将来被审计时确认意图。
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.allowFileAccessFromFileURLs = false
                settings.allowUniversalAccessFromFileURLs = false
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.javaScriptCanOpenWindowsAutomatically = false
                CookieManager.getInstance().setAcceptCookie(true)
                // 第三方 cookie 默认关闭；教务数据请求均为同源。
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                // 桥：平台按 origin 限定可见范围（见类注释），回调里再校验主框架 + 教务域名。
                // 两条 origin 规则覆盖"教务站点本体"与"统一认证可能用到的其它 ustb 子域"，
                // 保证登录页上手动抓取失败时仍能把错误经桥报回界面（而不是静默无反应）。
                WebViewCompat.addWebMessageListener(
                    this,
                    bridgeName,
                    BRIDGE_ORIGINS,
                ) { _, message, sourceOrigin, isMainFrame, _ ->
                    if (!isMainFrame) return@addWebMessageListener
                    val host = sourceOrigin.host?.lowercase() ?: return@addWebMessageListener
                    if (!isJwHost(host)) return@addWebMessageListener
                    message.data?.let(bridge::dispatch)
                }
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    WebViewCompat.addDocumentStartJavaScript(
                        this,
                        QR_POLL_RECOVERY_JS,
                        setOf("https://sis.ustb.edu.cn"),
                    )
                    WebViewCompat.addDocumentStartJavaScript(
                        this,
                        SMS_CAPTCHA_VIEWPORT_JS,
                        setOf("https://sso.ustb.edu.cn"),
                    )
                }
                webViewClient = object : WebViewClient() {
                    // 域名白名单：站外链接一律转交系统浏览器，避免把教务会话带进任意站点。
                    // （桥的可见范围另有平台级 origin 限定，见 BRIDGE_ORIGINS。）
                    //
                    // **必须失败关闭**：此前 host 为 null 时 `?: return false` 会放行
                    // file:/content:/data:/blob: 这类无 host 的 URL 进入 WebView；
                    // 也没有 scheme 检查，http:// 的站内地址同样能过。
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: android.webkit.WebResourceRequest,
                    ): Boolean {
                        val url = request.url
                        // 校方认证链连续返回 HTTP Location。取消明文请求，通过 HTTPS
                        // 访问同一端点；仅接管已知登录站点主框架的 GET 重定向，保留原始参数。
                        val secureRedirect = if (request.isForMainFrame && request.isRedirect &&
                            request.method == "GET"
                        ) secureSchoolAuthRedirect(url.toString()) else null
                        if (secureRedirect != null) {
                            pendingCompletionUrl[0] = secureRedirect
                            continuePendingCompletion()
                            return true
                        }
                        if (url.scheme != "https") return true          // 拒绝加载，不交接
                        val host = url.host?.lowercase() ?: return true // 无 host 一律拒绝
                        if (request.isForMainFrame && isSchoolQrCompletionUrl(url) &&
                            !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                        ) {
                            pendingCompletionUrl[0] = url.toString()
                            return true
                        }
                        if (isJwHost(host)) return false
                        return runCatching {
                            view.context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, url),
                            )
                            true
                        }.getOrDefault(true)
                    }

                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                        if (isSsoLoginPageUrl(url)) {
                            pendingCompletionUrl[0] = null
                            completionRetryCount[0] = 0
                        }
                        onPageStarted()
                        onAuthPageChanged(isAuthPageUrl(url))
                        // 尽早注入，MutationObserver 会在 meta 标签解析出来时立即改写。
                        if (isByytUrl(url)) view.evaluateJavascript(PAGE_FIX_JS, null)
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        // 兜底注入（脚本幂等），覆盖 onPageStarted 时机过晚的情况
                        if (isByytUrl(url)) view.evaluateJavascript(PAGE_FIX_JS, null)
                        if (isAuthPageUrl(url)) view.evaluateJavascript(SMS_CAPTCHA_VIEWPORT_JS, null)
                        onAuthPageChanged(isAuthPageUrl(url))
                        if (isByytLandingUrl(url)) view.evaluateJavascript(OPEN_SCHOOL_AUTH_JS, null)
                        // 主页面判定改为 host + path **精确**匹配：
                        // 此前是 url.contains("/authentication/main")，任意域名下含该路径的
                        // URL（如 https://evil.example/authentication/main）都会触发抓取脚本注入。
                        if (isMainPageUrl(url)) {
                            view.post { onMainPage() }
                        }
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: android.webkit.WebResourceRequest,
                        error: android.webkit.WebResourceError,
                    ) {
                        if (request.isForMainFrame) {
                            if (isSchoolQrCompletionUrl(request.url) &&
                                error.description.toString().contains("ERR_CONNECTION_ABORTED") &&
                                completionRetryCount[0] == 0
                            ) {
                                completionRetryCount[0] = 1
                                pendingCompletionUrl[0] = request.url.toString()
                                continuePendingCompletion()
                                return
                            }
                            onPageError(
                                if (isSchoolQrCompletionUrl(request.url)) {
                                    "微信授权已完成，但学校认证回跳失败：${error.description}。请检查网络后重试登录。"
                                } else {
                                    "页面加载失败：${error.description}（请检查网络/VPN后重进本页）"
                                },
                            )
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: android.webkit.WebResourceRequest,
                        errorResponse: android.webkit.WebResourceResponse,
                    ) {
                        if (request.isForMainFrame) {
                            onPageError("页面返回错误：HTTP ${errorResponse.statusCode}")
                        }
                    }

                    override fun onReceivedSslError(
                        view: WebView,
                        handler: android.webkit.SslErrorHandler,
                        error: android.net.http.SslError,
                    ) {
                        handler.cancel()
                        onPageError("SSL 证书校验失败（${error.primaryError}），请检查网络/VPN")
                    }
                }
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                        onPageProgress(newProgress)
                    }
                }
                loadUrl(JW_HOME)
                onCreated(this)
            }
        },
        // WebView 必须显式销毁：AndroidView 离开组合时若只丢掉引用，持有 Activity context 的
        // WebView 与其 JS 定时器（PAGE_FIX_JS 里的 MutationObserver / setTimeout）会一起泄漏。
        // 导入页的"预览 → 重新抓取"会反复创建新实例，成绩页每次抓取完成后也会销毁一个。
        onRelease = { view ->
            if (currentView[0] === view) currentView[0] = null
            pendingCompletionUrl[0] = null
            runCatching { WebViewCompat.removeWebMessageListener(view, bridgeName) }
            runCatching { view.stopLoading() }
            runCatching { view.loadUrl("about:blank") }
            runCatching { view.destroy() }
        },
    )
}

/** 教务系统域名白名单：ustb.edu.cn 及其子域。前导点保证 evilustb.edu.cn 不匹配。 */
internal fun isJwHost(host: String): Boolean =
    host == "ustb.edu.cn" || host.endsWith(".ustb.edu.cn")

/** 教务站点才需要桌面视口修正；SSO/微认证页面保留自己的移动端布局。 */
private fun isByytUrl(url: String): Boolean =
    runCatching { android.net.Uri.parse(url) }.getOrNull()
        ?.takeIf { it.scheme == "https" }
        ?.host?.lowercase()
        ?.let { it == "byyt.ustb.edu.cn" } == true

private fun isByytLandingUrl(url: String): Boolean {
    if (!isByytUrl(url)) return false
    val path = runCatching { android.net.Uri.parse(url).path }.getOrNull()
    return path.isNullOrEmpty() || path == "/"
}

private fun isAuthPageUrl(url: String): Boolean =
    runCatching { android.net.Uri.parse(url) }.getOrNull()
        ?.takeIf { it.scheme == "https" }
        ?.host?.lowercase()
        ?.let { it == "sso.ustb.edu.cn" || it == "sis.ustb.edu.cn" } == true

private fun isSsoLoginPageUrl(url: String): Boolean = runCatching {
    val uri = android.net.Uri.parse(url)
    uri.scheme == "https" && uri.host.equals("sso.ustb.edu.cn", ignoreCase = true) &&
        uri.path == "/ac-h5/"
}.getOrDefault(false)

/** 微信扫码完成后学校生成的唯一回跳地址；其他认证导航不参与延迟/重试。 */
private fun isSchoolQrCompletionUrl(uri: android.net.Uri): Boolean = runCatching {
    uri.scheme == "https" && uri.host.equals("sso.ustb.edu.cn", ignoreCase = true) &&
        uri.path == "/idp/authCenter/authenticateByLck" &&
        uri.getQueryParameter("thirdPartyAuthCode") == "microQr" &&
        !uri.getQueryParameter("auth_code").isNullOrBlank()
}.getOrDefault(false)

/**
 * 是否为教务主页面：**host 必须在白名单内**且 path 精确等于 [MAIN_PAGE_MARK]。
 *
 * 此前是 `url.contains(MAIN_PAGE_MARK)`：任何域名下含该路径的 URL（含查询串伪造）
 * 都会触发抓取脚本注入，而抓取脚本会调用 @JavascriptInterface 桥向本地库写入数据。
 */
private fun isMainPageUrl(url: String): Boolean {
    val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return false
    if (uri.scheme != "https") return false
    val host = uri.host?.lowercase() ?: return false
    if (!isJwHost(host)) return false
    return uri.path == MAIN_PAGE_MARK
}

/** 读取 assets 内的注入脚本文本。 */
fun loadAssetScript(context: android.content.Context, path: String): String =
    context.assets.open(path).bufferedReader().use { it.readText() }

/**
 * 清除教务登录会话（"退出教务登录"）。
 *
 * 为什么必须有这个入口：WebView 的统一身份认证 SESSION cookie 由 CookieManager 落盘在
 * `app_webview/`，按设计会一直保留（备份规则已排除它，所以不会随云备份/换机外流，但会
 * 长期留在本机）。此前全项目只有"清除成绩缓存"，没有任何终止会话的手段 —— 手机借人、
 * 二手机转卖时 WebView 仍是登录态。
 *
 * 清的是"登录态"，不是课表/成绩数据（那是本地 Room/DataStore，由"清除成绩缓存"负责）。
 */
fun clearJwSession(context: android.content.Context) {
    runCatching {
        android.webkit.CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
    }
    runCatching { android.webkit.WebStorage.getInstance().deleteAllData() }
    runCatching {
        android.webkit.WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
    }
}
