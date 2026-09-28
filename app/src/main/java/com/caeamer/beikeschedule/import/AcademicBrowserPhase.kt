package com.caeamer.beikeschedule.import

/** 页面是否需要用户操作，不以 WebView 是否加载完毕来推断。 */
enum class AcademicBrowserPhase(val label: String, val interactive: Boolean = false) {
    CHECKING("正在检查登录会话…"),
    OPENING_AUTH("正在进入统一身份认证…"),
    LOGIN("请完成学校认证", true),
    MANUAL("请在学校页面继续登录", true),
    READY("教务数据正在同步"),
}
