package com.caeamer.beikeschedule.import

import java.net.URI

private val JW_HTTPS_REDIRECT_HOSTS = setOf(
    "sso.ustb.edu.cn",
    "sis.ustb.edu.cn",
    "byyt.ustb.edu.cn",
)

/** 校方认证链连续返回 HTTP 重定向；只升级既有登录站点，绝不发送明文认证请求。 */
internal fun secureSchoolAuthRedirect(value: String): String? {
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (!uri.scheme.equals("http", ignoreCase = true) ||
        uri.host?.lowercase() !in JW_HTTPS_REDIRECT_HOSTS ||
        uri.port != -1 || uri.rawUserInfo != null || uri.rawFragment != null
    ) return null
    return "https" + value.substring(value.indexOf(':'))
}
