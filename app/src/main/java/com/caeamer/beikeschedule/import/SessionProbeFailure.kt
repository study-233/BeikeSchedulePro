package com.caeamer.beikeschedule.import

/** 只解析固定诊断码，不将网页响应或异常原文显示到原生界面。 */
internal data class SessionProbeFailure(val message: String, val offerSchoolPage: Boolean) {
    companion object {
        fun fromCode(code: String?, status: String? = null): SessionProbeFailure {
            if (code == "HTTP") {
                val http = status?.toIntOrNull()?.takeIf { it in 400..599 }
                return SessionProbeFailure(
                    if (http != null) "检查登录会话失败：教务服务返回 HTTP $http，请稍后重试，已有数据已保留"
                    else "检查登录会话失败：教务服务响应异常，请稍后重试，已有数据已保留",
                    offerSchoolPage = false,
                )
            }
            val reason = when (code) {
                "REDIRECT" -> "会话检查被学校重定向，尚未确认登录状态"
                "NETWORK" -> "会话检查请求未完成，可能是网络或认证跳转受限"
                "TIMEOUT" -> "会话检查超时"
                "INVALID_JSON", "UNEXPECTED_HTML" -> "会话检查返回了无法识别的响应"
                "MISSING_IDENTITY" -> "会话检查未返回有效用户信息"
                else -> "暂时无法确认教务登录状态"
            }
            return SessionProbeFailure("$reason；请在学校页面继续登录，或点击右上角重新加载。已有数据已保留", true)
        }
    }
}
