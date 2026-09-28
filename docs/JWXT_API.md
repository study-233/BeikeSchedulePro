# 北科本研一体化教务系统（byyt）接口全量参考

> 目的：后续开发直接查本文档，**不再重复扒站**。
> 标注说明：✅ 已实测接入 App ｜ 🔬 已实测/字段实锤但未接入 ｜ 📝 来自 2026-08-28 调研，未逐项实测 ｜ ⚠️ 已弃用/有坑
> 实测时间：2026-08-28 / 2026-08-29（真实会话）。样例统一在 `docs/samples/`。

## 0. 通用约定

| 项 | 说明 |
|---|---|
| Base URL | `https://byyt.ustb.edu.cn` |
| 鉴权 | Cookie `SESSION=<base64(uuid)>`（Spring Security 风格），登录后全站通用 |
| UA | 跨 UA 直连**不会**使会话失效（2026-08-29 curl 实测）；App 内仍统一 WebView 同源 fetch，不维护 Cookie/UA |
| 编码 | 全站 UTF-8（Windows 终端显示乱码是 codepage 问题，非接口问题） |
| 请求体 | 两种：**form**（`application/x-www-form-urlencoded`）与 **JSON**（`application/json`，传 form 会被拒 415）。逐接口标注 |
| 响应 | 两种形态：包装 `{code,msg,msg_en,content}` 或**裸** JSON/数组（逐接口标注） |
| 分页 | PageHelper 结构 `{total,list,pageNum,pageSize,size,pages,isFirstPage,...}` |
| 学期代码 | `xnxq`="2025-20262"（xn+xq 拼接）、`xnxqmc`="2025-2026-2"；进度接口的 `jzxnxq` 同为 xn+xq 拼接 |
| 字段命名 | form 系接口字段多为大写（XN/XQ/KCMC），JSON 系接口小写（kcmc/xf）；同一数据两种接口字段名不同 |
| 页面规律 | 功能页 HTML 尾部引 `/pub/<模块>/...js` 与 `/component/inco/...js`，**数据接口路径藏在 JS 里**；表格列定义 JS（`*Column*.js`）= 响应字段名的权威来源 |

### App 会话检查与登录续接

用户主动刷新时，WebView 优先访问 `/authentication/main`，在教务主框架中以同源 `POST /user/me`（空表单、`cache: no-store`）验证已有会话，再启动数据任务。沿用现有裸用户对象的 `yhdm` / `xh` 字段确认身份，不将探测返回的个人信息传回原生层。

已展示学校统一认证按钮的登录落地页也可直接进入认证；识别不限定根路径。学校按钮负责生成 SSO 参数，App 不拼接或持久化认证链接。认证成功后仍通过真实会话检查才启动同步。

注入脚本共用 `jw_auth.js` 区分明确的认证失效（401、已知登录重定向、带登录标志的 HTML）和普通服务/网络错误。失效事件携带原请求标识，宿主最多自动续接一次，保存剩余网络预算并拒绝旧回调；普通 403、5xx、未知 HTML 不作为过期处理。会话检查阶段上限 20 秒、登录按钮搜索上限 10 秒，用户操作认证不计入任务网络预算。

以上为基于现有接口文档的客户端调整，未访问学校服务验证；需按 `docs/UNIFIED_SYNC_ACCEPTANCE.md` 检查实际登录跳转与冷启动会话复用。

## 1. 当前学期与校历

| 接口 | 方式/参数 | 响应 | 状态 |
|---|---|---|---|
| `/component/querydangqianxnxq` | POST form，空体 | 裸 `{XN,XQ,XNXQ,XNXQ_EN}`（XNXQ="2026-2027-1"） | ✅ |
| `/component/queryRlZcSj` | POST form `xn,xq,djz`(周次) | 包装 `content:[{xqj:1..7,rq:"yyyy-MM-dd"}]` 该周 7 天日期 | ✅ |
| `/component/getXnxqByRq` | **GET** `?rq=yyyy-MM-dd` | 裸 学期+教学周 zc | 📝 |
| `/Xiaoli/queryMonthList` | POST form `xn,xq`，**需 header `RoleCode: 01`** | 裸 `{xlList:[...],xnxqList,monlist}`；**每周 7 条**（每天一条，仅一个星期字段非空，周一那条 `MON`=该周周一日期），`XNXQ`=xn+xq 拼接，`ZC` 1..18 教学周、99 假期 | ✅ |
| `/component/queryzclist` | POST form `xn,xq` | 包装 `content:[{ZC}]` 周次列表（1-18+99） | ✅ |
| `/component/querydangqianzc` | POST form | 当前教学周（假期返回空） | 📝 |

**教学周≠日期周**：长假周（国庆）不占教学周序号，一切周映射以 `Xiaoli/queryMonthList` 为准。

**逐日放假标记（2026-09-28 用户提供响应核对）**：`xlList` 的 `MON/TUES/WED/THUR/FRI/SAT/SUN`
是日期，对应的 `MON1/TUES1/WED1/THUR1/FRI1/SAT1/SUN1` 为逐日标记（字符串 `"1"` 放假、`"0"` 非放假）。
同一天在 `monlist[].dszlist[].dstlist[]` 中的 `sffj` 与之对应，月历的年月来自 `yy/mm`，日来自 `rq`。
响应中 2026-09-25 的 `FRI1="1"`，2026-10-05～07 的 `MON1/TUES1/WED1="1"`，与用户提供的粉色日期一致；
普通周末也由接口标记，不能自行用星期或国家节假日推算。这里依据响应及用户截图对应关系接入，未取得网页着色脚本。

App 仍按当前学期的 `MON` 和教学周 `ZC` 提取周映射，另将对应 `*1=1` 的日期去重保存为 `holidayDates`；
`ZC=99` 的日期不会新增课表周。日期栏只标记当前显示周内的命中日期，今天高亮优先，不改变课程或提醒排期。
旧数据或逐周兜底没有逐日标记时不标色；已有课表升级后需重新导入才能补齐。
原响应的月历对象含用户标识，不整份入库；仅保留无个人信息的校历节选作为测试样本：
`app/src/test/resources/queryMonthList-2026-2027-1-excerpt.json`（第 1～4 教学周及国庆跳周）。

源码验证参考：`node --test app/src/test/js/jw_import.test.cjs` 使用内置 Node 测试模块模拟接口，
不访问学校服务；Gradle 的 `testDebugUnitTest` 覆盖 Kotlin 解析和配置草稿。
数据库版本升至 8 后需由开发者构建导出 `app/schemas/com.caeamer.beikeschedule.data.local.AppDatabase/8.json`，
并运行 `HolidayCalendarMigrationTest`（7→8、5→8）、`ScheduleRepositoryTest` 和 `ImportCommitTest`。
人工检查第 3/4 教学周、今天与放假重合、隐藏周末、明暗主题及多课表切换；以上检查不由源码编辑任务执行。

## 2. 课表

| 接口 | 方式/参数 | 响应 | 状态 |
|---|---|---|---|
| `/xszykb/querykbsffb` | POST form `xn,xq` | 裸 `"0"`=未发布 | ✅ |
| `/xszykb/queryxszykbzong` | POST form `xn,xq` | 包装 `content:[...]` 总课表；字段 `RWH` 任务号、`KEY`（"xq2_jc1"=周2第1节；"bz"=备注行）、`SKSJ` 多行文本（名/师/周/【校区】地/节）、`ZC` 周位图（**34 字符**，index=周次，索引 0 为占位符）、`XB` 色号（99999=无固定时间） | ✅ |
| `/xszykb/queryxszykbzhou` | POST form | 单周课表（含备注） | 📝 |
| `/component/queryKbjg` | POST form `xn,xq,pylx` | 包装 `content:[{xj,kssj,jssj}]` 节次时间（1..13 节） | ✅ |
| `/xszykb/queryxszytjkb` | POST form | 推荐课表视图 | 📝 |
| `/component/queryKssjFb` | POST form | 节次时间发布状态 | 📝 |
| `/component/queryxskbbzb` / `queryxskbbzb2` | POST form | 课表备注 | 📝 |
| `/xskb/queryrwbewm` | POST form | 课程任务二维码 | 📝 |

## 3. 成绩与学业

### 3.1 成绩单

`/cjgl/grcjcx/grcjcx` — **POST JSON** `{xn,xq,kcmc:null,cxbj:"-1",pylx,current:1,pageSize:500,xscjlb:null,sffx:null,yhdm}`（yhdm/pylx 取自 `/user/me`）→ 包装 `content:{total,list:[...]}` ✅

list 字段（实测，`docs/samples/grcjcx-all.json`）：`kcdm` `kcmc` `xnxq` `xnxqmc` `kcxz`(必修/任选) `kclb`(通识课程/实验/美育(素质拓展)…) `xf` 学分 `zzcj` 总评(可能为"优/良") `bkcx`(正考/补考) `yxmc` 开课学院 `sffx` 辅修 **`pm` 排名 `zrs` 该课总人数 `khfs` 考核方式** `xs` 学时 `zpcj` `xscjlb`(主修) `rwh`。
⚠️ 字段顺序：`kclb → zzcj → … → xf`（xf 在 zzcj 之后），按名取值不受影响。

### 3.2 GPA

`/cjgl/grcjcx/getgpa` — POST form 空体 → 裸 `{BL:GPA值, HDXF:已获学分, TGKC:通过门数, PM:专业排名, ZRS:专业总人数, PJXFJ_PM:平均学分绩排名, PJXFJ_PM_FW}` ✅

⚠️ **BL 不是 4.0 制 GPA**：实测 BL=4.22 > 满绩 4.0，应为"平均学分绩/20"口径。`queryBxkqk` 带 `sfcxxfj:"1"` 只多返回 `XFJ`（学分绩排名标识）与 `PM`/`ZYRS`，**教务网无 4.0 制绩点接口**。
App 的 4.0 制 GPA 由 `GpaCalculator` 本地计算：换算表 90-100=4.0 / 85-89=3.7 / 80-84=3.4 / 75-79=3.0 / 70-74=2.4 / 65-69=2.0 / 60-64=1.0 / <60=0；纳入全部有数字成绩课程（等级制排除）；同 kcdm 有补考/重修行只取补考/重修（多行取最高）；挂科计 0 绩点、学分进分母。

### 3.3 学业完成情况（修读进度链）🔬

调用链：先 `getXss` 拿标识，再 JSON POST 查询。

| 接口 | 请求 | 响应 |
|---|---|---|
| `/cjgl/cjzhtjcx/cjcx/getXss` | POST form 空体 | 包装 `content:[{xh,nj,pylx,xjid,zyfxdm,fah,…}]`（fah=培养方案号） |
| `/cjgl/cjzhtjcx/cjcx/queryqxnxq` | POST form 空体 | 裸 `{XN,XQ}` 当前学年学期 |
| `/cjgl/cjzhtjcx/cjcx/queryBxkqk` | **POST JSON** `{xh,pylx,nj,jzxnxq:xn+xq,xjid,fah,sfcxxfj:"0"}` | 包装 `content:{yqmsxf:{YQXF要求学分,YQMS要求门数}, ywcxf已修学分, wwcxf未完成学分, ywcms已过门数, wwcms未过门数}`（实测 125.5/89.5/36.0/58/43） |
| `/cjgl/cjzhtjcx/cjcx/queryXflbyq` | **POST JSON** 同上（可加 `zyfxdm:"0"`） | 包装 `content:{total:17,list:[{kclbmc学分类别,kcxzmc课程性质,kclbdm,kcxzdm,yqwcxf要求学分,yzhxf已转移,dzhxf待转移,moochdxf,moocsjhdxf}]}`（17 行与教务网页"学分类别要求"表一致） |

**口径警告（重要）**：
- `queryXflbyq` 的 `ywcxf` 是**转移/认定口径**（如创新学分 14.2），≠已完成学分，**勿用作已完成**；网页"已完成学分"实为**前端按成绩单 `kclb` 分组汇总已通过课程 `xf`**（已数值验证：学科平台 23.5、实验 5.0、基础实习 3.0、专业实习 2.0 与网页一致）。App 内同样本地汇总，可离线。
- 本地 kclb → Xflbyq 类别行的匹配规则：全等 或 行名以本地名结尾（如 "素质拓展—美育(素质拓展)".endsWith("美育(素质拓展)")）。

| 其他同模块接口 | 说明 | 状态 |
|---|---|---|
| `/cjgl/cjzhtjcx/cjcx/queryMkyq` | **JSON** `{xjid,zyfxdm,fah,pylx}` 模块要求树（含每模块要求/完成学分门数学时） | 🔬 |
| `/cjgl/cjzhtjcx/cjcx/querybyyq` | **JSON** `{xh,zyfx,pylx,fah}` 毕业要求 | 🔬 |
| `/cjgl/cjzhtjcx/cjcx/queryFaKzkc` | 方案可修课程 | 📝 |
| `/cjgl/cjzhtjcx/cjcx/querysfxsbyyq` | 是否显示毕业要求 | 📝 |
| `/cjgl/cjzhtjcx/cjcx/queryZyfxTjinfo` | **JSON** 专业方向统计 | 📝 |
| `/cjgl/cjzhtjcx/cjcx/queryInfo` | **JSON**（页面注释代码中出现，勿依赖） | 📝 |
| `/cjgl/grcjcx/bkcxbjList` | 补考/重修班级 | 📝 |
| `/cjgl/grcjcx/seefx` | 辅修成绩 | 📝 |
| `/xjgl/xyyj/*`（如 `queryXyyjXshd`） | 学业警示/学期成绩/挂科情况（菜单"学业警示核查"）——App 选择本地算挂科，不依赖 | 📝 |
| `/UserManager/queryXsxkqk` | ⚠️ **实为本学期选课情况**（YXXF=本学期所选学分，YXMS=所选门数，另有 kxfsobj），**不是修读进度，弃用** | ⚠️ |

## 4. 考试

`/kscxtj/queryXsksByxhList` — POST form：**`pxn`/`pxq`/`ppylx`（⚠️ p 前缀，传 xn/xq 会被忽略返回空）** + `pageNum`/`pageSize` → **裸** PageHelper 分页 `{total,list:[...]}` 🔬

list 字段（权威来源：列定义 JS `/pub/gly/ksgl/cxtj/XskscxByXhColumn-*.js`，已存 `docs/samples/XskscxByXhColumn.js`）：
`XH` `XM` `KCDM` `KCMC` 课程名、`KSSJDMC` 考试类型（"期末考试"）、`KSSJMS` **考试时间描述文本**（需解析出日期/起止时间）、`ZWH` 座位号、`CDDM`/`CDXX` 地点、`JKJSBZ` 进考场标志、`KKYXMC` 开课学院、`ZYMC`/`ZYFXMC`/`NJMC`。

⚠️ 当前学期与往期学期均返回空（历史排考被清理）；空态样例 `docs/samples/exams-empty.json`。**排考后需用真数据核对 `KSSJMS` 格式一次**。
考试查询页面：GET `/kscxtj/queryXskscxByXh`（HTML）。

## 5. 选课（全模块 📝 未逐项实测，App 不做写操作）

`/Xsxk/*`：`queryXkdqXnxq` 当前选课学期、`queryKkxqList` 开课学期列表、`queryKxrw` 可选任务、`addXuanke` 选课、`tuike` 退课、`queryXkgwc` 购物车、`queryYxkc` 已选课程、`queryXsxkrzList` 选课日志、`queryJiaofei` 缴费查询；`/Xsxktz/queryXlctzList` 休复学；页面 `/Xsxk/query/1`。
课表查询（选课系统内）：`/Xskbcx/queryXskbcxList`(个人) / `queryBjkbcxList`(班级) / `queryGwckbcxList`(公选课)。

## 6. 学籍与个人

| 接口 | 说明 | 状态 |
|---|---|---|
| `/user/me` | POST form；完整档案+25 权限码+`roleAuth['01']` 完整功能菜单树（qxmc/url/fqxdm）+学籍快照 | ✅ |
| `/UserManager/queryxsxx` | 学籍信息（学院/专业/班级/年级） | ✅ |
| `/UserManager/querywhbsxsxx` | 含身份证号 ⚠️ 敏感勿存 | 📝 |
| `/UserManager/queryArrears` | 欠费查询 | 📝 |
| `/UserManager/xgmm` | 改密码 | 📝 |
| `/UserManager/upddzyx` / `updlxdh` | 改邮箱/电话 | 📝 |
| `/common/queryGjlist` / `queryMzlist` | 国籍/民族代码表（页面下拉用） | 📝 |

## 7. 培养方案与其他

- `/Zdpyfa/*` 18 个接口（基本信息/课程要求/毕业要求/实践环节/专业方向统计等）📝——**部分需页面上下文，直接调用 403**；页面 `/xspyyjsxsjh/xsjhBgd/1`。
- `/Jkgrjhpp/qeuryXspyfaList` 📝（注意原拼写 qeury）。
- 交流生业务 `/j1jh/gnjls/*` 📝；AI 助手 `/incoai/go` 📝；收藏 `/shouCang/qxshouCang` 📝。
- 外部系统：评教 `https://pingjiao.ustb.edu.cn`、大创/SRTP `https://srtp.ustb.edu.cn`、北科学堂/雨课堂/毕业/实践教学平台（URL 待补）。

## 8. 样例文件索引（docs/samples/）

| 文件 | 内容 |
|---|---|
| `grcjcx-all.json` | 全量成绩单（含 pm/zrs/khfs） |
| `getgpa.json` | GPA 概览 |
| `xflbyq.json` | 学分类别要求 17 行（要求学分实锤） |
| `bxkqk.json` | 毕业总进度 |
| `xsxkqk.json` | 本学期选课情况（弃用留档） |
| `exams-empty.json` | 考试分页空态（参数正确性验证） |
| `XskscxByXhColumn.js` | 考试列定义（响应字段名依据） |
| `queryxszykbzong-2026-2027-1.json` | 总课表 |
| `queryKbjg-section-times.json` | 节次时间 |
| `queryRlZcSj-week1-dates.json` | 第 1 周日期 |

## 9. 附录：贝壳教学平台（smartclass）无课教室接口

> 与上面 1~8 节的**教务系统（byyt）无关**，是另一个站点：`https://ustb.smartclass.cn`，
> 对应页面 `/ustb/ClassRoom.aspx`（"今日无课教室"）。本节于 2026-09 对照站点自身前端
> 与真实响应核实，勿再重新扒站。

### 9.1 接口一览

| 接口 | 方法 | 用途 |
|---|---|---|
| `/config.json` | GET | 站点配置；`domainConfig` 字段是**加密的签名密钥段**，`csrkTime`（300000 = 5 分钟）是 token 有效窗口。**不需要签名** |
| `/Home/GettimeDif` | GET | 服务器当前时间，**绝对毫秒时间戳**（不是时间差）。需要签名 |
| `/general/api/open/building/listBuildings` | GET | 楼栋列表 `[{id,name}]` |
| `/general/api/open/teachingCycle/listNodeTypes` | GET | 节次类型 `[{id,name}]`，实测有"默认节次"（6 大节）与"小节次"（12 小节） |
| `/general/api/classroom/freeClassRooms` | POST | 空教室。体 `{buildingId, cycleTypeId, nodeId}`，`nodeId` 传空串=全部时段 |

响应统一为 `{"code":0,"msg":"success","data":[…]}`；`code != 0` 时 `msg` 即错误原因。
`freeClassRooms` 的 `data` 为 `[{nodeId,nodeName,startTime,endTime,classroomItems:[{classroomId,classroomName,noSeatRate,seatCount}]}]`，
`startTime/endTime` 的日期部分是固定占位 `2000-01-01`，**只取时分**；
`noSeatRate` 可能为 `null`（无数据），必须与 `0`（真的 0% 空座）区分。

### 9.2 请求签名（`csrkToken`）

- 算法：AES-256-CBC/PKCS7 解密 `/config.json` 的 `domainConfig`（hex）得到明文配置，取其中的 `csrkKey`。
- token：把**毫秒时间戳的每一位数字**当作下标去 `csrkKey` 里取字符（13 位时间戳 → 13 字符 token）。
- 时间戳必须落在服务端的 `[现在, 现在+csrkTime]` 窗口内，且**必须用服务器时间**（设备时钟快 3 分钟就会全量失败）。
  站点自己的取法是 `TimeDif = parseInt(/Home/GettimeDif 响应) - Date.now()`；
  App 改用 `/config.json` 的 HTTP `Date` 响应头（该请求不需要签名，没有"先有鸡还是先有蛋"的问题）。
- 服务端密钥会**轮换**：轮换后请求返回 `csrf key validate error`，正确做法是丢弃本地缓存的 key、
  重新拉 `/config.json` 后重试一次（**每个接口都要**这么做，只覆盖一半会让自愈整条失效）。
- 实现与单测：`data/remote/SmartClassCrypto.kt`、`SmartClassKeyProvider.kt`、`SmartClassApi.kt`，
  fixture 见 `app/src/test/resources/smartclass-config.json`。

### 9.3 其他

- 站点页面上的"全天 / 上午 / 下午 / 晚上"筛选是同一份数据的界面分组，接口没有对应的日期或半天参数：
  **只能查"今天"**。App 因此在页面顶部明示"今天 · 更新时间"，并把已结束的时段淡化。
- 站点会在本地存储里放 `csrkDate` 版本号，版本变化时清空 `csrkKey` 重新拉取——与 App 的"密钥被拒即重取"同源。

## 10. 通知公告（2026-09-28 用户提供请求与响应）

`POST /component/queryTongZhiGongGaoPage`，Content-Type 为 `application/x-www-form-urlencoded;charset=UTF-8`。

请求体：`bt=&pageNum=1&pageSize=15&kssj=`。`bt`、`kssj` 为空；翻页仅改变 `pageNum`。请求通过登录后的 WebView 同源 fetch，复用现有会话，不保存或手工拼接 Cookie。

裸 PageHelper 响应字段：`total`、`list`、`pageNum`、`pageSize`、`nextPage`、`hasNextPage`。用户样本 total=4008、pageNum=1、pageSize=15、nextPage=2。

每条公告使用 `id`、`bt`（标题）、`fssj`（发布时间）、`sfwblj`（1 为外链）、`wburl` / `url`（原文）。样本中的 `nr`、`fj` 等为空，不假定存在正文接口，不保存接收人或权限等无关字段。原文只接受有主机的 HTTP(S) 地址，通过外部浏览器打开；相对地址或空链接不猜测路径。

一键同步取第一页，公告页按需翻页；本地按 ID 去重、保留服务端顺序。第一页成功后替换缓存，后续页追加，列表和分页元数据在 Room 同一事务中保存。失败保留旧数据；退出登录和缓存清理隔离迟到回调。不做定时轮询或系统通知推送。

当前 Room 版本为 9，需由开发者生成 `9.json` 并验证 8→9 与 5→9 迁移；版本 8 的校历迁移继续保留。参见 `docs/UNIFIED_SYNC_ACCEPTANCE.md`。接口依据用户提供材料实现，源码编辑未访问学校服务或运行接口验证。
