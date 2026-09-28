# 贝壳课表（BeikeSchedule）技术执行文档

> 版本：v1.0（2026-08-27）
> 状态：已确认，作为后续开发的执行依据

## 1. 项目目标

做一款面向北京科技大学学生的 Android 课表 App：

- 通过内置 WebView 让用户登录北科教务系统，一键拉取个人课表，落库本地
- 提供周视图课表查看、当前周自动定位、单双周/非本周课程区分
- 上课提醒、桌面小组件（二期）
- 本地优先：无账号体系、无服务器依赖、离线可看

非目标（本期不做）：成绩查询、考试安排、抢课、多端同步。

## 2. 调研结论（事实依据）

### 2.1 北科教务系统现状（2026-08-27 已实地验证）

- 现行教务系统：**北京科技大学本研一体化教务管理系统**，入口 `https://byyt.ustb.edu.cn`（2025 年启用，替代旧 `jwgl.ustb.edu.cn`；旧参考代码如 USTB-Awesome-JS 教务部分已失效）。
- 技术栈：服务端渲染 + jQuery/Vue2 + iView 的混合页面（非纯 SPA），认证为 `SESSION` Cookie（HttpOnly），登录走统一认证（`/cas`、oauth2，支持扫码）。
- **登录方案维持不变**：WebView 内由用户自行完成统一认证登录，App 不接触学号密码；登录后通过注入 JS 复用页面会话直接 `fetch` 教务 JSON 接口。
- **重大利好：课表数据有结构化 JSON 接口，无需解析 HTML。** 已用真实登录会话验证以下接口（均为 `POST` + form 参数，返回 JSON）：

| 接口 | 参数 | 返回 |
|---|---|---|
| `/component/querydangqianxnxq` | 无 | 当前学期 `{XN:"2026-2027", XQ:"1", XNXQ:"2026-2027-1"}` |
| `/xszykb/queryxszykbzong` | `xn`, `xq` | **学期总课表**（课程块列表，见下） |
| `/xszykb/queryxszykbzhou` | `xn`, `xq`, `zc` | 指定周次的周课表 |
| `/component/queryKbjg` | `xn`, `xq`, `pylx` | 节次时间定义（北科每天 13 小节，第 1 节 08:00–08:45，含每节 `kssj`/`jssj`） |
| `/component/queryRlZcSj` | `xn`, `xq`, `djz` | 指定周每天对应的日期（用于课表顶部日期行） |
| `/component/queryzclist` | `xn`, `xq` | 学期周次列表 |
| `/xszykb/querykbsffb` | `xn`, `xq` | 课表是否已发布（0=未发布） |

课表块字段（`queryxszykbzong` 实测样本）：

```json
{
  "RWH": "2026-2027-1-2040107-003",   // 课程任务号，天然唯一键
  "KEY": "xq1_jc3",                    // 格子定位：星期1、第3大节（无固定时间课程无此字段）
  "KSJC": 5, "JSJC": 6,                // 开始/结束小节（第5-6节）
  "ZC": "01111111100000...",           // 34 位周次位图（索引 i = 第 i 周，索引 0 为占位符），1=该周有课
  "XB": 4,                             // 色板下标（99999=无固定时间的备注类课程）
  "SKSJ": "机械设计\n张杰\n1-8周\n【校本部】机械楼720\n第5-6节",  // 多行文本：课程名/教师/周数/地点/节次
  "SKSJ_EN": "...(英文对照)"
}
```

- 解析规则：`dayOfWeek` 从 `KEY` 的 `xqN` 提取；节次范围直接用 `KSJC`/`JSJC`；周次用 `ZC` 位图（比"起始周+单双周"模型更精确，调课周也正确）；课程名/教师/地点从 `SKSJ` 按行拆分（地点行带 `【校区】` 前缀）。`XB=99999` 或无 `KEY` 的条目为无固定时间课程（实验周/网课等），单独列表展示。

### 2.2 参考项目清单

| 项目 | 协议 | 借鉴内容 |
|---|---|---|
| [WakeUp课程表](https://sj.qq.com/appdetail/com.suda.yzune.wakeupschedule) | 适配代码未公开（公开仓库仅苏大早期版） | **产品交互与页面设计重点参考**；教务导入交互模式（WebView 登录 + 注入 JS 抓取）；官方已适配北科，证明方案可行 |
| [Dawn-Course](https://github.com/HF-CYGG/Dawn-Course) | GPL-3.0 | 工程架构参考（不直接抄代码，避免 GPL 传染）；「导入脚本可远程更新」机制 |
| [USTB-Course-ICS-Exporter](https://github.com/NicodeSS/USTB-Course-ICS-Exporter) | - | 北科微教务课表页的解析思路、节次时间映射、国庆周特殊处理经验 |
| [USTB-Awesome-JS](https://github.com/isHarryh/USTB-Awesome-JS) | MIT | 北科各平台登录/页面交互实战代码（注意教务部分已过时） |

WakeUp 页面设计要点（UI 复刻目标）：
- 周视图网格：左侧节次列（含每节起止时间），顶部日期行（高亮今天），课程色块圆角卡片
- 支持横向滑动切换周，顶部下拉显示当前周/切换周，非本周课程半透明
- 点课程块弹详情（课程名/教师/地点/周数/节次）
- 单双周在课程块上标注

## 3. 技术选型

基于现有脚手架（Kotlin 2.2.10 + Compose BOM 2026.02.01 + minSdk 34 + AGP 9.2.1），单模块 app，MVVM 分层。

新增依赖（写入 `gradle/libs.versions.toml`）：

| 用途 | 依赖 |
|---|---|
| 架构 | `androidx.lifecycle:lifecycle-viewmodel-compose`、`androidx.navigation:navigation-compose` |
| 本地存储 | `androidx.room:room-runtime/room-ktx`（KSP 编译器）、`androidx.datastore:datastore-preferences` |
| 网络 | `com.squareup.okhttp3:okhttp`（复用 WebView Cookie 调教务接口） |
| JSON | `org.jetbrains.kotlinx:kotlinx-serialization-json` |
| 后台任务 | `androidx.work:work-runtime-ktx`（上课提醒、可选的定时刷新） |
| 小组件（M5） | `androidx.glance:glance-appwidget` |

（原计划兜底用的 jsoup 已确认不需要——课表数据有 JSON 接口。）

不引入 Hilt：项目规模下手动构造依赖足够，保持简单。

## 4. 架构与包结构

```
com.example.beikeschedule
├── data/
│   ├── local/          # Room：AppDatabase、DAO、Entity
│   ├── pref/           # DataStore：开学日期、当前学期、提醒开关等
│   └── repo/           # ScheduleRepository（UI 唯一数据入口）
├── import/             # 教务导入
│   ├── ImportWebViewScreen.kt   # WebView 登录 + 注入脚本
│   ├── JsBridge.kt              # @JavascriptInterface 回传 JSON 结果
│   └── parser/         # 教务 JSON → CourseEntity 映射（纯 Kotlin，可单测）
├── model/              # 领域模型（Course、TimeSlot、WeekInfo 等）
├── ui/
│   ├── schedule/       # 周视图课表主页（核心页面）
│   ├── course/         # 课程详情、手动增删改
│   └── settings/       # 开学日期、节次时间、提醒设置
├── remind/             # 上课提醒（WorkManager + Notification）
└── MainActivity.kt
```

分层规则：UI(Compose) → ViewModel → Repository → Room/DataStore/网络，单向依赖。

## 5. 数据模型（Room）

```kotlin
@Entity(tableName = "course")
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String,        // 教务任务号 RWH（导入源唯一键，手动添加为空串）
    val name: String,          // 课程名
    val teacher: String,       // 教师
    val location: String,      // 上课地点（含【校区】前缀原样保留）
    val dayOfWeek: Int,        // 1..7（周一..周日），0=无固定时间课程
    val startSection: Int,     // 起始小节（对应教务 KSJC）
    val endSection: Int,       // 结束小节（对应教务 JSJC）
    val weekBitmap: String,    // 教务 ZC 位图原样存储：ZC[i] 对应第 i 周（index 0 恒为 '0' 占位），'1'=该周有课
    val colorIndex: Int,       // 色板下标（直接用教务 XB；99999 视为无固定时间课程）
    val source: Int,           // 0=教务导入 1=手动添加
) {
    fun hasClassOnWeek(week: Int): Boolean =
        week in 1 until weekBitmap.length && weekBitmap[week] == '1'
}

@Entity(tableName = "section_time")
data class SectionTimeEntity(   // 每小节起止时间，导入时从 /component/queryKbjg 拉取并缓存
    @PrimaryKey val section: Int,  // 小节号 1..13（北科实测每天 13 小节）
    val startTime: String,         // "08:00"
    val endTime: String,           // "08:45"
)
```

设计说明：教务的 `ZC` 34 位周次位图比「起始周+结束周+单双周」模型表达力更强（调课周、间断周都准确），故数据模型直接采用位图；UI 层需要"单/双周"角标时由位图推算即可。无固定时间课程（`XB=99999`、无 `KEY`）`dayOfWeek=0`，在课表页底部单独列表展示。

学期配置（DataStore）：当前学期（XN/XQ，导入时从教务接口写入）、开学日期（第 1 周周一，首次导入时引导确认）、提醒开关等。
当前周 = `(today - 开学日期) / 7 + 1`；也可用 `/component/queryRlZcSj` 反查校验（教务自身在假期可能返回空，以本地推算为准）。

## 6. 核心流程设计

### 6.1 教务导入流程（核心，接口已实测可用）

1. `ImportWebViewScreen` 打开 WebView，加载 `https://byyt.ustb.edu.cn`；用户自行完成统一认证登录（账号密码或扫码，App 不接触凭证）。
2. `WebViewClient.onPageFinished` 检测已登录（出现 `/authentication/main` 页面且非登录跳转）后，注入 JS 依次 `fetch`（同源请求自动携带 `SESSION` Cookie）：
   - `POST /component/querydangqianxnxq` → 当前学期 XN/XQ
   - `POST /xszykb/querykbsffb` → 课表是否已发布，未发布则提示并终止
   - `POST /xszykb/queryxszykbzong`（xn, xq）→ 学期总课表
   - `POST /component/queryKbjg`（xn, xq, pylx=1）→ 节次时间表
   - `POST /component/queryRlZcSj`（djz=1）→ 反推第 1 周周一日期（用于自动填开学日期）
3. 全部结果经 `JsBridge`（`@JavascriptInterface`）以 JSON 字符串回传 → kotlinx.serialization 反序列化 → 映射为 `CourseEntity` 列表（映射规则见 2.1 节字段表）。
4. 预览页展示解析结果（课程数、无固定时间课程数、学期、开学日期），用户确认后**覆盖式写入**该学期数据（按学期先删后插，避免脏数据）。

解析 JS 与 App 解耦：脚本文本支持从远程（GitHub raw / 仓库内 `server/` 静态目录）拉取更新，本地缓存 + assets 内置兜底——教务接口改版时可以不发版修适配。HTML/DOM 解析不再作为方案，仅在接口不可用时再评估。

### 6.2 课表周视图

- 页面结构：顶部栏（学期名 + 第 N 周 + 周切换下拉）+ 日期行（本周 7 天，今天高亮）+ 网格区（左节次列 × 7 天列）。
- 课程块：圆角卡片，底色取色板（Material You 风格浅色，文字取对应深色），显示课程名 + 地点；单/双周加角标；**非当前周的课程块 alpha 降至 0.3**。
- 交互：横向滑动切周（Pager），点击课程块弹详情 BottomSheet，长按进入编辑。
- 空学期/未导入状态 → 引导跳转导入页。

### 6.3 上课提醒

- WorkManager 周期性任务（每日一次）计算当天课程，用 `setExactAndAllowWhileIdle` 的 AlarmManager 或 WorkManager `OneTimeWorkRequest` 排定课前 N 分钟通知。
- 通知内容：课程名、地点、第几节、距上课时间。
- 设置页可开关提醒、调整提前时间。

## 7. 里程碑

| 里程碑 | 内容 | 验收标准 |
|---|---|---|
| M1 骨架与数据层 | 依赖接入、Room/DataStore 建表、Repository、领域模型 | 单测：周次位图判定、SKSJ 文本拆分、KEY 解析正确 |
| M2 课表 UI | 周视图 + 手动增删改课程 + 详情弹层（参考 WakeUp 交互） | 手动添加的课程正确显示在对应周/节次，滑周流畅 |
| M3 教务导入 | WebView 登录 byyt + 注入 JS 调 JSON 接口（6.1 流程）+ 预览确认入库 | 用真实账号导入本学期课表，课程名/地点/节次/周次位图与教务网一致 |
| M4 体验完善 | 当前周定位、开学日期设置、上课提醒、深色模式 | 杀进程重启后当前周正确；提醒准时弹出 |
| M5 小组件 | Glance 桌面小组件（今日课程） | 小组件展示当日课程并随数据刷新 |

**M3 调研任务已于 2026-08-27 完成**：接口定义与字段样本见 2.1 节（实测数据：当前学期 2026-2027-1，每天 13 小节，第 1 节 08:00–08:45）。真实接口返回样本已存档至 `docs/samples/`（`queryxszykbzong-2026-2027-1.json`、`queryKbjg-section-times.json`），M1 的解析单测直接以这两个文件为 fixture。M3 可直接按 6.1 流程编码。

## 8. 风险与对策

| 风险 | 对策 |
|---|---|
| 2025 新教务系统结构（已探明，见 2.1） | 接口走 JSON，无 HTML 解析负担；适配脚本远程可更新，应对后续改版 |
| 统一认证登录限制（如仅扫码） | WebView 内由用户自行登录，App 不接触凭证；SESSION Cookie 仅存于系统 WebView 私有存储 |
| 教务接口/字段改版导致适配失效 | 解析脚本远程热更新；失败时明确提示 + 保留手动添加兜底 |
| 教务在假期/未发布课表时返回空 | 用 `/xszykb/querykbsffb` 前置判断，给出"课表未发布"的友好提示而非导入空表 |
| 国庆等节假日调课 | 保留教务 `ZC` 位图；维护者 JSON 另提供整日停课与补课映射，按来源日期筛选课程，见 `CALENDAR_ADJUSTMENTS.md`；不根据国家节假日自动推算 |
| 合规与凭证安全 | 不收集、不上传学号密码；导入全程在 WebView 同源环境内完成 |

## 9. 工程规范

- 包名维持 `com.example.beikeschedule` 暂不改，发布前再定正式 applicationId。
- 每次改动后跑 `./gradlew assembleDebug` 验证编译；M1 起为周次/解析等纯逻辑写 JUnit 单测。
- 提交粒度按里程碑内小步提交，不一次性堆砌。
