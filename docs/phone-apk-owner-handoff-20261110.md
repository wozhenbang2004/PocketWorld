# 私人构建 APK 的主人安装交接（Stage 7，源码候选）

状态：Stage 7 源码已存在于 main `50ceebc077517e937de5007c4302d9172bf132be`（2026-11-11 对齐）。主人的 PocketWebHost + React 审阅接线已补齐。原交付没有编译、依赖安装、测试或真机证据；本次流式增量同样未构建/运行，不是“手机 APK 开发环境已配好”。

## 用户旅程与复用

已有原项目构建回执 → 原托管成果收集和 AttachmentBook 私人附件 → 在原文件详情选择外部打开 → 原 PocketWebHost 承载的主人 React APK 审阅页 → 重新读原文件并核 SHA → 明确交给固定系统安装器 → 用户亲自在系统确认 → 分开显示系统结果信号与包身份只读观察。

- 源必须是原 `OwnerFilePreviewSource`，且是 `managed_build_output` 的私人 owner 产物，原 `nativePublication` 的 role、operation、task、run 和 artifact plan 均相符。不能用 URI、网页 JSON、文件名或团队附件替换。
- 仅原私人 `managed_build_output` 的 `.apk` 外部打开进入该新审阅页。普通私人、群/团队 APK 与非 APK 的既有明确外部打开、导出和分享流程保持不变；typed-source 限制不扩大为全局文件打开禁令。
- 沿用原 `AttachmentViewSnapshots` 固定内容 URI、20 MiB 单文件限制、10 分钟有效期、40 MiB/4 份副本预算。没有扩大 Provider 根、增加任意文件分享或建立另一套产物仓库。
- 解析 APK 前只对 ZIP 声明做有界预检；APK 可有 64 MiB 单条目、256 MiB 总展开声明、2 MiB 目录，最多 20000 项。实际文件仍须 ≤20 MiB。不解压 APK，不把这些上限放宽到源码包。
- 临时解析副本只在 app cache，受 40 MiB/4 份及 10 分钟陈旧清理限制。解析后复核原 SHA/长度、文件属性和单硬链接；源书仍做原 current 检查。PRoot 与应用同 UID，不因此获得对同 UID 恶意执行代码的完整隔离。
- PackageManager 解析包名、版本、最低 API、当前签名证书指纹。`cryptographicVerificationPerformed=false`。最终由 Android 安装器验证 APK；此页不自行实现签名算法。
- PocketWorld 自身包拒绝，仍必须走原应用更新器；观察到不同证书或降级时拒绝；不自动卸载、清数据、接受证书替换、签名或运行已安装应用。

## 权限与回执

产品控件复用已有 BoardUI Button/Checkbox、SettingsDialog、MotionFeedback/ProgressBar 及 UpdatesWorkspace 布局；没有新写 Android LinearLayout、TextView、Button 或 AlertDialog 产品表单。非导出 Activity 只承载原 WebHost 与生命周期/原交接权威，新增独立 `project_apk` endpoint，以原 document/windowId/generation/foreground 绑定；不会借用 `app_updates` 的更新授权。文档替换或离开前台退休异步读取/准备，过期准备只能清理未发出的副本。

原 manifest 已有 `REQUEST_INSTALL_PACKAGES`。只新增安装 Intent 与对应设置 Intent 的查询范围、一个非导出 Activity。系统组件必须既是解析结果，又确实在该 Intent 的匹配活动中，且为 SYSTEM/UPDATED_SYSTEM_APP；选择器或第三方处理器不能获得文件。只为单个 URI 传 READ grant，不传持久授权、写授权、跳过确认或“允许替换”额外项。

用户未允许本应用请求安装时，只提供单独的“由我打开安装来源设置”，不点开关；返回不自动交接。主人敏感窗口沿用原 NativeSensitiveStep，并在系统窗口期间保留，阻止手机自动化代替用户操作敏感步骤。

一条持久原交接引用及上一条回执，不是安装会话队列或 TaskStore：

1. 原审阅对象、requestId、单调不复用的 Android requestCode、URI 和 `awaiting_system` 必须先 commit 成功，再进入外部交接。
2. 已知没有接收活动可记 `not_started` 并清理未发出的副本。其他启动异常记作未知，不删可能已发出的副本，不自动重试。
3. `RESULT_OK` 仅记 `system_reported_success`。按钮“核对原安装状态”另读同包名的版本和证书。`replacementProved=false`、`installedApkBytesVerified=false`；交接前已匹配时不会宣称这次新安装已证实。
4. 查询不到包不是不存在的证明。Android 11+ 包可见性限制可能使核对未知，不新增 `QUERY_ALL_PACKAGES`。
5. 冷恢复及丢回执只能读原引用，不能重投。旧 Bundle 不能重新打开已关闭的持久等待。旧 requestCode 不能串到后来的新请求。
6. 主人可明确结束等待，仍记录 `owner_closed_unknown`，不假称取消系统安装或判断成败；另一次交接需要重新读取、审阅和明确确认。仅保留当前及上一条引用，页面明示不是完整安装历史。
7. 已知返回或主人明确离开原安装页面后撤回该 URI READ grant。已交付副本不冒充未交付删除，保留原 TTL 清理。持久引用没有签名私钥、密码或 APK 正文。

## 续接补齐与检查边界

本轮采用 root 转交的 Stage 7 边界：只完成原私人成果的主人安装交接，保留 PocketWebHost 与 owner-only React；不得改成原生产品表单、自动签名或未知重发，不安装依赖、不写跑测试或使用真机。
交叉审查时曾考虑全局拦截普通/群 APK 的外部打开；root 明确指出属于未授权收紧，已撤回，不纳入最终增量。

- `main.tsx` 按原生 `project_apk` surface 选择 `ProjectApkWorkspace`；`PocketWebHost.projectApk` 与独立 endpoint 绑定。现有 `bridge.ts` 支持具名 snapshot 扩展，无需修改共享 bridge 或借用更新器授权。
- `onResume`、明确“读取原状态”及每项原生动作准入重新读持久 current。另一主人窗口改变等待状态后，旧窗口不再仅显示缓存；异步核对返回时重新读 current，不把 previous 投影成新的当前回执。
- 包状态读取若与系统回执或主人结束等待交错，`ProjectApkHandoff.observe` 保留较新状态，不把较早观察挂到新回执上。核对仍然不证明安装归因。
- 系统设置打开期间锁住新的页面动作；只在本 Activity 原等待 ID 上进行退出撤权，不撤其他窗口的 URI。准备或审阅失败清除旧 review，下一次必须重新核对。
- 64 位版本代码只在给 React 的展示投影中变成十进制文本，避免 JavaScript 数值舍入；原生比较、哈希和持久引用仍使用原精确 long。SettingsDialog 保持挂载并通过 `isOpen` 退出，沿用原控件的关闭动效。
- 源码交叉审查覆盖原源身份、持久未知回执、窗口生命周期和系统组件限制；当前没有运行或视觉验收。现存 `node_modules` 的来源/完整性不能确认，且本轮已知有依赖安装被审核拒绝，未借用这些依赖执行类型检查或构建。

历史 Stage 7 补丁已并入上述 main 基线，不能重复应用。后续应先完成允许的不带测试构建，再按另行授权做真实设备体验；本报告不把源码审查称为构建、签名、安装或体验成功。

## 2026-11-11：原 APK 审阅与系统读取改为有界流式

本次只补原旅程中的内存路径，不扩大文件、权限或安装范围：

- `AttachmentOutcomeHost.originalFile` 为同一私人附件签发原流式读能力，复用 `AttachmentBook.exportOwnerArchiveBytes` 的私人来源与前后 SHA 校验。`OwnerFilePreviewSource.copyTo` 再绑定原长度/hash、控制器与前后 current，失败的部分写入只能由调用方丢弃，不能外发。未具流接口的既有来源保留受限 byte[] 兼容，不宣称其他来源都已流式化。
- `OwnerProjectApk.inspect` 直接流入未发布的 app cache 审阅副本，不再先持整份 APK；解析后的文件属性/hash 核对照旧。交接准备从同一原源流入原 `AttachmentViewSnapshots`，完整检查并写完元数据才返回 URI。
- 快照锁内只预留完整原长度预算、创建副本和发布元数据；读取原源/current/Book、控制器回调和流写均在锁外。进程内活跃写入保留完整预算且不被 TTL 清理，失败清理未发布副本，避免原文件会话撤销回调形成 Book 与快照锁反向等待。
- 系统安装器实际消费的 `AttachmentViewSnapshots.openReadOnly` 也不再创建整份 byte[]：用 32 KiB 缓冲复制并核 SHA 到私有 reader，unlink 后再核对真正只读 FD 的长度/hash，rewind 后才返回。不是只把生产端改流式、读取端仍全量持有。此共用 reader 改动也用于原临时查看 URI，但不改变其来源权限或可读内容。
- 原每文件 20 MiB、源附件总存储 100 MiB、各临时目录 40 MiB/4 份、10 分钟 TTL、固定系统组件和单 URI READ grant 均未提高。没有增加下载、安装工具、签名、URI 根、后台会话、自动重投或首启。
- 校验仍需要多次读盘，未测得速度/内存/电量改善数值；同 UID 不是恶意代码隔离。完整 SDK profile、超过 20 MiB 的 APK、模块输出与源码范围隔离、签名保管/恢复仍是开放项。

本次再次读取 Android 官方 SDK 条款第 2/3 节、AGP 8.7 compatibility 表和 PackageInstaller.Session.commit：SDK 使用仍需接受条款，开源组件各自许可；AGP 8.7 的 Gradle/JDK/API 为 8.9/17/35、默认 Build Tools 34.0.0；targetSdk 35+ 的 Session commit 不能使用 immutable PendingIntent。没有接受任何条款或创建 Session。固定 Perfetto SDK 摘要原文已回读，候选 JSON 反映来源证据而非本机字节验证。

独立源码复查已核对原源前后身份、实际 FD 消费、活跃写入预算和撤销回调锁序；最初发现的反向锁路径已按上述结构修正，复查未见新增阻断。这不等于运行或并发行为验证。

验证边界：仅源码及文档差异审查；本环境未发现 javac、Android SDK 或可用 Gradle 构建环境，未安装补齐。未写/运行测试，未进行编译、设备运行、UI 体验、签名或真实安装。下一步是允许的不带测试构建与另行授权的真实体验，不是重新应用 Stage 5–7。

## 为什么暂不引入 PackageInstaller Session

Android 官方从 API 29 建议 PackageInstaller 替代此 Intent。此增量选择与现有更新器一致的原生主人交接形态，避免在没有真实环境验证时新造 commit/session/receiver 的执行账本。API 35 的 session commit 还涉及可变 PendingIntent 等生命周期约束。这里只实现有界单 APK 交接，不声称 Session 级逐字节安装归因、分包安装、后台或静默安装已完成。若系统不提供明确可固定的 handler，则停止并说明，不回退第三方安装器。

## 工具链与签名仍缺什么

Stage 5 候选为 Ubuntu ARM64 + Temurin 17 + Gradle 8.9 + AGP 8.7.3 + API 35 + ARM64 Build Tools 35.0.1，已有源码和发布元数据依据，但仍不是可启用 catalog：SDK 许可明确确认、实际 ARM64 组合验证、下载/展开/缓存峰值预算、受控 Java/Gradle 环境及命令超时均未闭合。原只读候选不能变成“安装即成功”。

本轮另找到 Google Perfetto 固定提交中的两个 SDK ZIP SHA256，补足了 Stage 5 当时缺少的摘要来源证据。见 `phone-sdk-sha256-addendum-20261110.json`；没有下载 ZIP 或实际核验字节，不能把来源证据说成已安装/已验证。

安全可实施的后续方向是一个原 runtime generation 内的固定 APK profile：所有包固定版本/hash/来源，沿用原 RuntimeSetupHost 审阅、取消、断点或重试原 task、旧 generation 回退；只有实际 probe/hash/原版本组合成立才登记工具可用。不能直接 apt 修改已封存 rootfs 后沿用旧 identity。

签名策略须另行交付。调试签名可以由该 profile 的 AGP 生成本机独有 debug key，但其位置必须是 app 私有的签名专用持久目录而非 checkout/Git；禁止下载共享 debug key、把 keystore 或密码写入命令通知、团队资料或源码包。正式发布密钥需主人明确选择存储/备份/恢复方式，密钥丢失会影响后续升级；不能为了“闭环”悄悄生成替代发布密钥。现有文件名排除是防误带规则，不是对任意重命名秘密或 Git 命令的完整保护。

还有一个源范围缺口：`ProjectSources.sourcePath` 当前只排除根 `build/dist`，常见 Android `app/build/` 仍可能被纳入源码摘要的 2 MiB 单文件/16 MiB 总限制，构建后会阻断源码核对或产物选择。Stage 6 的 20 MiB 流式发布没有解决它。后续必须明确、同源地定义模块生成根并贯穿 baseline/编辑/导入/枚举/源码摘要，或经过实际验证的输出重定向；不能粗暴排除任何名为 `build` 的路径段而藏掉合法源码。

大于 20 MiB 的真实 APK、完整分包、模块生成根、ARM64 SDK profile 安装与合法分发、签名生成/备份/恢复、已安装 APK 的逐字节归因、首启权限/运行体验均未解决。它们是待实现/待验证，不是 Android 平台绝对不可实施。

## 证据入口（2026-11-10 阅读）

- Android Intent 安装及返回结果：https://developer.android.com/reference/android/content/Intent#ACTION_INSTALL_PACKAGE ，https://developer.android.com/reference/android/content/Intent#EXTRA_RETURN_RESULT
- APK 包信息解析：https://developer.android.com/reference/android/content/pm/PackageManager#getPackageArchiveInfo(java.lang.String,%20int)
- Android 11+ 包可见性：https://developer.android.com/training/package-visibility
- 原代码：`AttachmentOutcomeHost.originalFile`、`AttachmentBook.importNativeResult`、`AttachmentViewSnapshots.prepare`、`AppUpdateActivity`、`AppUpdateManager`、`NativeSensitiveStep`。
- 工具链来源和版本证据：随 Stage 5 的 `phone-apk-route-20261110.md` 与 `phone-apk-candidate-20261110.json`，不是运行 catalog。
