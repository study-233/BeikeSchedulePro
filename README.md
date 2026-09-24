# BeikeSchedulePro · 贝壳课表

> 感谢原作者 **caeamer** 创建 [BeikeSchedule](https://github.com/coderirse/BeikeSchedule)，为本项目奠定基础。

**面向北京科技大学（USTB）学生的 Android 课表与教务助手。**

从教务系统导入课表和成绩，在手机上查看每周课程、考试安排、个人日程与空闲教室。

[![Android](https://img.shields.io/badge/Android-14%2B-3DDC84?logo=android&logoColor=white)](https://github.com/study-233/BeikeSchedulePro/releases)
[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)](app/build.gradle.kts)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4)](app/src/main/java/com/caeamer/beikeschedule/ui)

[下载 APK](https://github.com/study-233/BeikeSchedulePro/releases) · [问题反馈](https://github.com/study-233/BeikeSchedulePro/issues) · [联系维护者](mailto:mrtaoyx@gmail.com) · [开发文档](docs/TECH_DESIGN.md)

> **v1.0.0 已发布。** 前往 [Releases](https://github.com/study-233/BeikeSchedulePro/releases) 下载签名 APK。

## 主要功能

| 功能 | 说明 |
| --- | --- |
| 课表导入 | 内置 WebView 登录本研一体化教务系统，导入前预览确认 |
| 每周课表 | 同步教学周日历，支持滑动切周、单双周、冲突课程并排显示 |
| 桌面课表 | 2×2 今日课程卡片，显示正在上和接下来的课程 |
| 课程管理 | 手动添加和编辑课程，隐藏或恢复课程，按需隐藏周末与非本周课程 |
| 成绩与学业 | 按学期查看成绩、排名、加权平均分、GPA 和学分修读进度 |
| 考试安排 | 查看考试倒计时、时间、考点和座位号 |
| 个人日程 | 一次性、每天或每周重复待办，支持每日打卡和提前提醒 |
| 空闲教室 | 按教学楼查看今天各大节空教室、座位数及空座率 |
| 消息提醒 | 上课、考前与个人日程提醒，支持开机后重新安排提醒 |
| 个性化 | 跟随系统、浅色和深色主题，课表字号、自定义背景图片、成绩隐藏与课程显示设置 |

<details>
<summary>展开查看功能细节</summary>

- **教学周**：根据教务校历映射教学周与日期，处理不计入教学周的假期；假期中提示并定位到后续教学周。
- **课表显示**：按大节排版，支持周次下拉跳转、今天高亮；课程卡片依次展示课程名、地点、教师和单双周标记，可淡化或隐藏本周不上的课程。
- **课表外观**：点击课表顶部学期名进入设置，或在“我的 → 课表”中打开“课表外观”。课程卡片字号支持 80%–160% 调节，较大字号下可上下滑动网格，日期栏固定，仍可左右滑动切周。支持从系统图片选择器选择背景，调整居中填满/完整显示、遮罩和模糊，实时预览并自动保存；字号和背景可分别恢复默认。
- **课程管理**：教务导入、自定义和示例课程均可隐藏，隐藏后停止上课提醒；教务导入课程仅支持隐藏，自定义和示例课程可删除。同名调课与单双周拆分行自动合并。
- **成绩分析**：加权平均分按必修课计算，可按学年或学期筛选并自定义排除课程；GPA 在本地按项目内置 4.0 制计算，补考和重修覆盖正考。成绩默认隐藏，可通过小眼睛按钮显示。
- **学分进度**：展示毕业总进度和课程性质分类进度；成绩详情包含接口提供的排名、考核方式、学分与开课学院等信息。
- **日程**：按日期分组，已过事项淡化；每日打卡次日重置，支持提前 1–120 分钟提醒。
- **考试提醒**：考试前一天 20:00 和开考前 1 小时提醒；上课默认提前 15 分钟提醒，可自行调整。
- **空闲教室**：无需登录教务账号，支持下拉刷新，显示数据更新时间；当前大节自动展开，已结束时段标注提示。
- **我的**：查看学籍信息、检查更新、打开外部教学系统、清除成绩缓存及退出教务登录。

</details>

## 下载安装

最低支持 **Android 14（API 34）**。选择下载渠道：

- [GitHub Releases](https://github.com/study-233/BeikeSchedulePro/releases)：下载签名 APK。
- [蓝奏云](https://wwazq.lanzoub.com/b01gibyh4d)：密码 `1234`。

安装后：

1. 在应用内登录 [北京科技大学本研一体化教务系统](https://byyt.ustb.edu.cn)，预览并导入课表。
2. 按需导入成绩和考试安排，设置个人日程，并开启需要的通知权限与提醒选项。

## 隐私与数据

- 学号和密码由你在系统 WebView 中直接输入学校认证页面，App 不读取、不存储密码。
- 课表、成绩和个人日程保存在本机，App 不将这些数据上传到开发者服务器。
- 自选课表背景在本机缩小后保存于应用私有目录，不上传，也不参与云备份；删除相册原图不影响已保存的背景。
- WebView 保存登录会话；学籍信息会随成绩抓取缓存在本机，供离线展示。可在“我的 → 退出教务登录”清除会话。
- 空闲教室数据来自贝壳教学平台（`ustb.smartclass.cn`）。请求携带平台公开配置生成的签名参数和设备网络出口 IP，不包含学号、姓名或本机课表等个人数据。
- 应用需要访问学校服务及 GitHub 更新接口；对应服务的可用性会影响导入、查询或更新检查。

## 开发信息

项目采用单个 `app` 模块：

| 层次 | 技术 |
| --- | --- |
| 界面 | Kotlin · Jetpack Compose · Material 3 |
| 本地数据 | Room · DataStore |
| 提醒 | AlarmManager · BroadcastReceiver |
| 桌面小组件 | Jetpack Glance · WorkManager，本地读取课表 |
| 教务导入 | WebView 登录会话 · JavaScript 注入 · 结构化 JSON |
| 空闲教室 | 贝壳教学平台接口 |

工具链、SDK 和依赖要求以仓库配置为准，当前 Gradle daemon 请求 **Java 21**。在仓库根目录使用自带的 Gradle wrapper：Windows PowerShell 为 `.\gradlew.bat`，macOS / Linux 为 `./gradlew`。常用任务包括 `assembleDebug`、`testDebugUnitTest`、`lintDebug` 和 `minifyReleaseWithR8`。

正式签名 APK 使用 `assembleRelease` 构建，需要维护者自己的 `keystore.properties` 和签名密钥；私人配置、密钥与 APK 不加入源码提交。

### 文档与发布

- [教务与空闲教室接口参考](docs/JWXT_API.md)：集成开发前先查阅，避免重复探查学校接口。
- [技术设计](docs/TECH_DESIGN.md)：架构与早期设计背景；历史版本描述请与当前源码对照。
- [桌面课表 Widget](docs/WIDGET_DESIGN.md)：布局、数据流、后台调度限制与手动验收步骤。
- [CI 配置](.github/workflows/ci.yml)：自动检查任务的来源。
- [v1.0.0 发布说明](docs/releases/v1.0.0.md)：查看版本内容与发布记录。

## 反馈与联系

- 维护者：[study-233](https://github.com/study-233)
- 邮箱：[mrtaoyx@gmail.com](mailto:mrtaoyx@gmail.com)
- 问题与建议：[GitHub Issues](https://github.com/study-233/BeikeSchedulePro/issues)

反馈时请附上应用版本、Android 版本、问题描述和复现步骤；截图或日志中请遮挡学号、姓名和登录信息，不要发送密码或会话凭证。

## 项目来源

本项目基于 [coderirse/BeikeSchedule](https://github.com/coderirse/BeikeSchedule)，原作者为 caeamer。BeikeSchedulePro 由 study-233 维护，以下保留原项目版权声明：

© 2026 caeamer. All rights reserved.