package com.caeamer.beikeschedule.model

enum class MainPage(val id: String, val title: String) {
    SCHEDULE("schedule", "课表"), CAMPUS("campus", "校园"), PROFILE("mine", "我的");
    companion object {
        fun restore(id: String?) = if (id == "jw") CAMPUS else entries.firstOrNull { it.id == id } ?: SCHEDULE
    }
}
enum class SettingsPage(val title: String) {
    SCHEDULE("课表设置"), MANAGE("课表管理"), SEMESTER("学期与校历"), DISPLAY("课表显示"), REMINDER("上课提醒"),
    HIDDEN("隐藏课程"), STUDENT("学籍信息"), ACCOUNT("账号与数据");
    companion object { fun restore(id: String?) = entries.firstOrNull { it.name == id } }
}

/** 字符串标识是持久化协议，不依赖枚举位置。 */
enum class CampusSection(val id: String, val title: String) {
    FREE_ROOM("free_room", "空教室"), NOTICES("notices", "公告"), SCORES("scores", "成绩"), EXAMS("exams", "考试");
    companion object {
        fun restore(id: String?, legacyIndex: Int? = null): CampusSection = if (id != null) {
            entries.firstOrNull { it.id == id } ?: FREE_ROOM
        } else when (legacyIndex) { 2 -> SCORES; 3 -> EXAMS; else -> FREE_ROOM }
    }
}

/** 可保存的二级页访问标识；每次进入生成新键，返回时恢复父页而不复用旧草稿。 */
data class NavigationEntry(val page: SettingsPage, val visit: Int) {
    val key: String get() = "${page.name}@$visit"
    companion object {
        fun restore(key: String): NavigationEntry? {
            val page = SettingsPage.restore(key.substringBefore('@')) ?: return null
            val visit = key.substringAfter('@', "").toIntOrNull() ?: return null
            return NavigationEntry(page, visit)
        }
    }
}
fun openSettings(stack: List<String>, page: SettingsPage, visit: Int): List<String> =
    if (stack.lastOrNull()?.let(NavigationEntry::restore)?.page == page) stack
    else stack + NavigationEntry(page, visit).key
