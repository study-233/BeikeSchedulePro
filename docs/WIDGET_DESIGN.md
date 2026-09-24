# 2×2 今日课表 Widget

状态：已有用户提供的小米桌面运行截图；已据此修正卡片比例与空态源码。本轮修改尚未执行 Gradle 检查或真机复验。第一版仅提供 2×2；不新增 1×1 Provider。

## 使用与显示规则

安装包含本功能的 APK 后，在桌面的小组件列表中找到“贝壳课表 / 今日课表”，拖到桌面。首次安装如尚未配置课程和学期，点按卡片进入 App 完成导入或设置。

布局参考圆角浅灰紫卡片：顶栏“我的课表”和日期；第一门显示课程、地点与教师、完整起止时间；第二门紧凑显示课程、开始时间和地点。竖条复用现有课程色板，课程名和地点过长时截断；应用浅色、深色和跟随系统设置均生效。

- 正在进行的课程优先，之后是今天尚未开始的课程；区间为 [开始时间, 结束时间)。
- 默认显示两门，更多课程显示“还有 N 门”。小尺寸或大字体下优先保留一门及时间；空间不足时省略教师等次要信息。
- 一天上完后显示“今日课程已结束”，不自动跳到明天。
- 隐藏和未排课课程不参与；手动、教务、示例课程均可展示。
- 按学校校历判断实际教学周。开学前、跳过的假期及学期结束后，不借用下一教学周的课程。
- Widget 不受 App 当前选中周、隐藏周末或隐藏非本周课程设置影响。
- 缺失或非法作息保留课程并显示节次，不捏造时刻，不误报“今日无课”。
- 点击打开课表并定位当前周；如果 App 正在导入，保留导入页面，在退出导入后处理定位。
- 所有实例使用当前本地课表，无独立配置页。

2×2 是启动器网格尺寸，不是固定像素大小。最小声明尺寸为 140dp × 140dp，目标网格为 2×2；使用 Glance SizeMode.Exact 读取宿主尺寸。外层占满宿主，卡片取宽高较小值保持正方形并居中；圆角为边长的 12%，内边距为 8.5%。背景不再随桌面较高的占格拉成长卡片，但不会改变桌面分配的占格。

无课空态按参考截图居中显示文字颜文字和灰色常规字重的“今天没有课啦”；仅未配置或读取失败时保留操作引导。课程密度按实际方形卡片计算，小尺寸或大字体时减少展示行，避免沿用宿主较高的尺寸造成溢出。不同启动器仍需实机确认。

## 小米桌面差异定位

基于用户提供的同屏对比截图和当前源码，分开处理以下两类问题：

| 检查项 | 定位结果 | 当前处理 |
| --- | --- | --- |
| 卡片比例 | 原布局将有背景的 Column 直接 fillMaxSize；桌面分配的 2×2 区域并非正方形 | 改为透明宿主内居中方形卡片 |
| 空态层次 | 原 EmptyMessage 左对齐、粗体，并总是显示“点按打开课表” | 改为居中、灰色常规字重，正常空态移除操作提示 |
| 圆角与留白 | 原 28dp 固定圆角、9/12dp 边距与参考比例不一致 | 圆角和内边距跟随卡片边长 |
| 小米组件中心入口 | Manifest 只有标准 Android Widget 声明，没有小米接入声明 | 保持安卓小组件入口；没有添加未经验证的小米标记或进程配置 |

视觉差异来自布局实现，Glance 能表达方形卡片、居中内容和比例留白。Glance 最终仍由 Android RemoteViews 承载，不能仅根据截图断言 WakeUp 使用哪种渲染库。[Android Glance 文档](https://developer.android.com/develop/ui/compose/glance/create-app-widget)

小米官方区分“安卓原生组件入口”和“小部件中心”。后者展示经审核正式上线的小部件；应用商店审核和小部件审核是两个流程，不能以增加一个 meta-data 或换成 XML 代替。[小米适配 Q&A](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1591)、[提交审核与上传指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1588)

小米技术文档还要求 :widgetProvider 独立进程、miuiWidget 标识及应用级 miuiWidgetVersion，并有曝光刷新和布局规范。当前实现采用默认进程内的 Glance / WorkManager 与单进程 SettingsStore，不能直接只给 Receiver 加 android:process。正式适配需要先评估 Glance 更新路径、后台调度和多进程数据访问，再按目标系统版本进行厂商自测及审核；本轮未进行平台提交、账号操作或发布。[小米技术规范](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1584)

证据限制：用户截图能确认当前空态的外观，不能证明修改后的渲染效果、WakeUp 内部实现或目标手机的组件中心收录状态。尚未执行手机操作，也没有测试辅助功能或实时刷新表现。

## 数据与目录

依赖为 Glance 1.2.0 和 WorkManager 2.12.0，声明在 gradle/libs.versions.toml。既有 Kotlin、Compose、Room 等依赖声明保持原样。

源码根目录为 app/src/main/java/com/caeamer/beikeschedule/：

| 文件/目录 | 职责 |
| --- | --- |
| data/repo/ScheduleSnapshot.kt | 课程、作息与学期的只读快照 |
| data/repo/ScheduleRepository.kt | getScheduleSnapshot；课程和作息在同一 Room 读取事务中获取 |
| model/TodaySchedule.kt | 今日课程状态与下一次时间边界 |
| model/TodayScheduleResolver.kt | 复用 WeekResolver、CourseMerger，计算当前和后续课程 |
| widget/ScheduleWidget.kt | Glance 生命周期与异步加载 |
| widget/ScheduleWidgetContent.kt | 卡片展示、主题及点击行为 |
| widget/ScheduleWidgetReceiver.kt | 添加、更新、调整尺寸、恢复和删除 |
| widget/WidgetUpdateCoordinator.kt | 唯一后台任务的安排和取消 |
| widget/ScheduleWidgetWorker.kt | 本地刷新 Worker 与周期/边界触发 Worker |
| widget/WidgetSystemReceiver.kt | 开机、日期、时间、时区、应用更新事件 |

Widget 元数据、布局预览、背景和文案位于 app/src/main/res/。预览中的课程为固定演示文字，不读取用户数据。

不增加 Entity、不修改 Room v5、不产生新的课程缓存。Glance Preferences 只存每次刷新的 revision，解决会话仍活跃时仅调用 update 却未重新计算当前时间的问题。课程依旧由 Room 和 DataStore 提供，不把个人课程塞入 WorkManager 输入数据，不读取或传输登录信息。

Room 与 DataStore 不是跨存储原子事务。成功导入后的主动刷新在二者完成后触发；导入并发读取或进程中断时的跨存储一致性限制保持原状。

## 刷新与生命周期

1. 第一个 Widget 添加后注册唯一的 15 分钟周期任务；每次刷新重算下一处课程开始、结束或当地午夜。
2. 周期/边界任务仅发出刷新请求，真正渲染由独立 Worker 处理，避免边界任务替换并取消自己。
3. 立即刷新任务采用唯一名称和 REPLACE，合并连续修改，保留最后一次更新。周期任务采用 KEEP。
4. 成功导入、保存/删除/隐藏/恢复课程、示例数据变化、修改学期、切换主题及 App 回前台时请求刷新。
5. 收到开机、时间/时区/日期改变、应用更新广播后重新计算。
6. 最后一个 Widget 删除时取消所有本功能的任务；没有实例时不新增任务。
7. 读取或更新异常由后续任务重试，卡片有读取失败提示。调度故障不改变导入、课程保存的成功结果。

没有常驻前台服务、额外通知或 Widget 专用精确闹钟。原有课程、考试、日程提醒实现保持独立。updatePeriodMillis 为 0，避免重复的系统周期刷新。

WorkManager 受省电、Doze、任务配额及厂商策略影响，15 分钟是请求周期而非准点保证。滑走应用与系统回收进程不等同于系统设置中的“强行停止”；后者可能阻止更新，直到用户重新打开应用。修改时间/时区后同样以系统实际允许调度的时间为准。

## 导入保护

Widget 功能本身未改动教务域名、WebView、JS、消息桥、Parser、接口参数、校历回退及原有事务写入算法；首版另有教务登录适配改动，需单独回归。ImportViewModel 仅在保存课程与学期成功、进入 Done 状态后通知 Widget 刷新。保留手动课程、教务课程隐藏状态及示例清理策略。

## 验证与交付

新增 JVM 测试源码：app/src/test/java/com/caeamer/beikeschedule/model/TodayScheduleResolverTest.kt，覆盖：

- 上课前、开始、期间、结束、全部结束及跨午夜。
- 校历跳周、开学前、学期后、官方校历优先、周末与周次位图。
- 隐藏和未排课排除、同槽位合并、同时段冲突、手动与示例课程。
- 缺失/非法作息、第 13 小节、下一次边界、时区和夏令时午夜。

本次仅进行源码及差异审阅，没有执行这些测试、构建、Lint、APK 安装或设备操作。

开发者可按 .github/workflows/ci.yml 在仓库根目录手动执行：

Windows PowerShell：

~~~powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug minifyReleaseWithR8 --console=plain
~~~

macOS / Linux：

~~~bash
./gradlew testDebugUnitTest lintDebug assembleDebug minifyReleaseWithR8 --console=plain
~~~

真机验收重点：

- 在同一小米桌面、相同网格和字体下对比 WakeUp：确认方形卡片居中、无课文案居中且正常空态不再显示操作提示。
- 添加两个实例，比较小尺寸、大字体、深浅色及不同启动器的文字截断；有课时确认两条课程不溢出。
- 查看正在上的课程；到结束边界后，等待系统任务刷新，确认切换。
- 编辑、隐藏、恢复、重新导入、清除示例、修改学期后检查实例一致。
- App 冷启动或停在其他 Tab 时点击；导入页面打开时点击；旋转后检查页面状态。
- 回收应用进程、开机、跨午夜及改时区后观察恢复；不把强行停止后的行为视作持续刷新保证。
- 删除最后一个实例后，检查 Widget 专用周期与边界任务不再保留。
- 回归北科导入预览、确认、再次导入、手动课程保留及原有提醒。
