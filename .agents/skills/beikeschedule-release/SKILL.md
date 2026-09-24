---
name: beikeschedule-release
description: 为 study-233/BeikeSchedulePro 准备 Android 版本配置、中文发布说明和 GitHub Release 发布步骤；用于本项目发版或发布准备，默认仅编辑源码与文档。
---

# BeikeSchedulePro 版本发布

为本仓库准备可审阅的发布材料。使用中文交流；命令和文件路径相对于仓库根目录。读取仓库当前规则，不将本技能视为构建、签名、提交、推送或发布的授权。

## 项目约定

- 发布目标：`study-233/BeikeSchedulePro`，维护者 `study-233`，反馈邮箱 `mrtaoyx@gmail.com`。
- 应用 ID：`io.github.study233.beikeschedulepro`；应用显示名“贝壳课表”。Kotlin 包路径和 namespace 仍为 `com.caeamer.beikeschedule`，不因发版迁移。
- 独立发行线从 `versionName = "1.0.0"`、`versionCode = 1` 开始；以后每次新发布递增内部版本码，不能再次重置为 1。
- 标签为 `v<版本号>`，正式附件为 `BeikeSchedulePro-v<版本号>.apk`，中文说明位于 `docs/releases/v<版本号>.md`。
- 历史中继承了原项目标签；不能仅按最大标签或最近标签推断本发行线的上次版本。以本项目已确认的发布记录、目标提交和版本配置为依据；来源不明时说明缺口。

## 准备源码与发布说明

1. 从工作目录定位仓库根目录，读取适用的 `AGENTS.md`、`README.md`、`app/build.gradle.kts`、`.github/workflows/ci.yml` 和已有发布说明。只读查看 Git 状态、差异、分支、remote 与标签，不读取 `keystore.properties`、私钥或密码。
2. 确认目标版本和发布范围。用户已提供版本或执行授权时直接沿用；需要选择下个版本且无法从上下文确定时再询问。检查 `origin` 指向目标仓库；不自动改 remote、历史标签或无关修改。
3. 修改 `versionName` 与 `versionCode`。新版本码应高于当前和已确认发布的版本码；同一份尚未发布的准备工作重复执行时保持已准备版本码，不重复加一。保留 SDK、依赖及签名配置。
4. 检查 `AppInfo.kt` 中的仓库、更新 API、维护者和邮箱；检查“我的”页及 README 使用当前发行版信息，原作者署名保留在来源说明中。常规发版不改变应用 ID 或提醒 action。
5. 审阅实际差异，编写或更新 `docs/releases/v<版本号>.md`，包含版本及内部版本码、可确认的变化、安装或升级说明、验证状态和已知限制。首次独立发行说明与原版共存、数据需重新导入；后续发行说明同应用 ID 和相同签名升级。已有未提交修改不能被当作已验证功能，也不能未经审阅全量打包进发布提交。
6. 检查 diff 和旧入口残留，交付本次改动、待执行检查及发布步骤。不为版本常量或文案新增机械匹配测试；若实际修改了行为，再补相应测试源码。

## 验证与签名构建交接

默认由用户执行以下命令。实际任务以当前 CI 为准；Windows PowerShell 使用仓库 wrapper：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug minifyReleaseWithR8 --console=plain
.\gradlew.bat assembleRelease --console=plain
```

macOS/Linux 将 `.\gradlew.bat` 换成 `./gradlew`。release 构建依赖用户已有的私人签名配置；缺失时报告缺口，不生成、替换或显示密钥密码，不更改本机 JDK、SDK 或全局配置。

交接时区分源码审阅、自动检查、设备验证和正式发布四种状态。没有执行或用户未提供结果的项目标记“未执行/待验证”，不要声称通过。当前源码编辑范围也不运行 skill 验证脚本。

设备验收包括：独立安装与原版共存、首次登录导入、课程/考试/日程提醒、版本显示、仓库跳转、邮件反馈及无邮件客户端提示。检查更新应只请求本仓库；尚无公开正式 Release 时保留现有失败提示，不能把请求失败报告为“已是最新版本”。

构建成功后，用户核对 release APK 的应用 ID、版本名、内部版本码和签名证书，并将签名产物命名为 `BeikeSchedulePro-v<版本号>.apk`；未签名产物或 debug 包不能作为正式附件。发布说明记录实际验证结果后再定稿。

## GitHub 发布交接

仅在用户明确授权相应执行步骤时执行；已有授权无需反复确认。默认给用户可审阅的命令与顺序：

1. 确认发布文件清单及分支；仅暂存审阅过的文件，提交版本配置与定稿说明。不要使用全量暂存来夹带无关改动；密钥、私人配置、日志及 APK 不进入源码提交。
2. 记录完整目标提交 SHA。APK 必须对应这个提交的源码；若构建后又改动了源码或依赖，重新构建验证。说明文档的验证状态更新可单独核对。
3. 只读核对本地和 `origin` 上同名标签、目标仓库同名 Release，确认账号对目标仓库有发布权限。查询失败不能当作“没有发布”；已有同名标签或 Release 时停止创建步骤，列出当前目标提交、附件与发布状态，不强推标签或覆盖附件。
4. 推送已确认的发布分支提交，在完整目标 SHA 上创建附注标签，仅推送该标签到 `origin`，不使用 `--tags` 推送继承的历史标签。
5. 使用 GitHub 发布页面上传已核对的签名 APK，或生成明确指定仓库、验证标签存在并从文件读取说明的命令：

```text
gh release create v<版本号> <签名APK路径> --repo study-233/BeikeSchedulePro --verify-tag --title "BeikeSchedulePro v<版本号>" --notes-file docs/releases/v<版本号>.md --latest
```

输出给用户时替换占位符为已确认的实际值，并按其 shell 正确引用路径。多行发布说明始终使用文件，不拼接进 shell 字符串。正式发布采用公开的非预发布 Release；若用户只要求草稿，则使用草稿并明确应用内更新检查尚不能发现它。

6. 发布完成后核对仓库、标签所指提交、公开状态、APK 附件和下载链接；由用户检查应用内更新来源是否正确。返回真实发布链接，任何一步未完成则明确停留位置，不将源码准备完成称为发布成功。

遇到检查失败、签名或权限缺失、标签冲突时保留已完成的材料，说明具体缺口；不要通过删除发布、改写标签或无限重试推进。
