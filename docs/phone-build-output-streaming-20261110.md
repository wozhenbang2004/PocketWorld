# 手机构建成果流式保存（stage6）

承接 stage5，独立增量；不安装依赖、不运行或编写测试、不真机、不推 main。

## 实际改动

新 managed build 成果选择使用 `pocketworld.managed-build-output.v2`，允许原 AttachmentBook 已支持的单件 **20MiB**，总存储仍是原 **100MiB**，未扩全局配额。APK 可以作为实际二进制成果保存，但不是因为扩展名而获得签名、可安装、业务正确或分享权限。

`ReviewedBuildOutput` 在第一次读取和第二次完整 SHA 核对时只用 32KiB 缓冲，不保留整文件 byte[]。发布时再次核原选择，然后将绑定准确大小/SHA 的流交给原 `AttachmentBook.importNativeResult`。流在返回 EOF 前核对：完整复制 SHA、长度、原工作目录路径、普通文件、nlink=1、size/mtime/fileKey。检查失败抛异常，Book 不会进入原文件提交；成功后 Book 仍自行计算 SHA 和数量、sync、按原 pause revision 提交。

`beforePublication` 仍在 Book monitor 外调用；没有从流的 EOF 回调 Local/TaskStore，不新建锁序。逐块沿原 RunControl 核对；源文件发生改变不截断后冒充成功。该防护不是 same-UID OS 沙箱，`atomicArtifactFence=false` 保留。

## 原协议没有放宽

- 原项目、sourceDigest、工具环境 generation、原成功 build job、checkoutSequence、idle/unknown 门禁、明确 collect 审阅、TaskStore admission 均不改。
- 旧 v1 选择仍按原 **2MiB** 验证，摘要和 argv 不重写；原回执查询继续使用原 operation ID，未知发布不重新生成 ID 或自动重做。
- `ProjectManagedOutput.MAX_BYTES` 和 `bytes()` 保持原 2MiB 内存合同。ManagedSkillBackend 等既有调用者不会借本次改动扩大输出。
- 不改 AttachmentBook、LocalModule、UI、APK MIME、权限或 installer。新类是包内流适配器，没有第二个产物库或安装账本。
- Android 前置条件文案只同步 20MiB/100MiB 事实；APK 工具链未验证、签名/安装未完成的标识不变。

## 未完成与可实施性

这解除了 **2–20MiB** managed 构建成果的收集限制，不是任意大 APK 方案。超过 20MiB 需要连同附件总配额、读取、查看快照、导出和归档一起重新设计。当前 Book 的保存已是流式，但查看/导出的一些路径仍读取最大 20MiB 的 byte[]；本切片不把整条附件链标为零拷贝。

模块内 `app/build` 尚可能进入源码预算，工具链固定 SDK 字节/许可/生成根/安装身份仍缺。签名可考虑官方 apksig + 私有 AndroidKeyStore，不能凭现有 BouncyCastle 就声称已有 APK 签名器。系统 PackageInstaller 提供 commit/STATUS_PENDING_USER_ACTION/STATUS_SUCCESS/失败回执，这是**可实施但尚未完成**，不是 Android 不支持。

现有 `AppUpdateManager` 仅接受签名发布 manifest 绑定的 PocketWorld 自身升级，强核包名、递增版本和相同证书；它的 owner Activity 也限原自更新页面。不能把这条已有的可信升级链改成任意项目 APK 安装入口。项目安装需单独绑定主人明确选中的不可变成果、系统 session 与前台确认、取消/重开查询；不能把打开系统界面记作安装成功，也不能在未知结果后自动再次 commit。

## 核对范围

源码级回读涵盖新流与原 Book 的 EOF→metadata→pause-revision commit 顺序、原 Local admission、v1 兼容、Skills 调用及未知回执。未编译、未执行程序、未测试、未安装依赖、未构建/签名/安装 APK。

API 研究来源：[PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller)、[Session.commit](https://developer.android.com/reference/android/content/pm/PackageInstaller.Session#commit(android.content.IntentSender))、[SessionInfo](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionInfo)。它们证明平台提供接口，不证明本补丁实现了安装功能。

## 2026-11-19：先找到本次构建后的文件，再单独收集

以前 `project_list_files` / `project_search` 特意不进入根 `dist` 和 `build`，但 `project_collect_plan` 又必须收到完整文件名。即使同一 managed build 已有成功回执，带内容哈希的文件也可能只能猜，或被迫再次申请执行命令。本次不放开普通源码工具：同一收集能力可显式用 `listDirectory: "."` 列出实际存在的这两个输出根，再用返回的相对目录逐层读取名称与大小；下一页要带回同一目录的原 cursor。空结果只覆盖此范围，超过 20 MiB、隐藏、凭据、特殊文件及链接不列为候选。

这一步沿原私有任务的 SQL 操作、成功构建回执、所选副本、源码版本、工具环境与构建顺序核对，不读输出正文、不计算文件 SHA、不生成计划、不固定附件。目录中现存的文件不能证明由这次构建产生。另传明确的 `path` 才创建原有单文件审阅计划；最终收集仍受原 prepared 与主人批准约束。Termux 接口不变。没有增加远程执行、离线推理或通用 Android APK 构建能力。

三个源码参照（仅比较机制，不复制代码或继承权限）：
- [DSH-Go 引擎安装](https://github.com/ccch1mneyyy/dsh-Go/blob/9ad11167c53a5da12ff8791c21f4327f5d8a9a25/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/runtime/RuntimeInstaller.kt#L59-L106)从 APK 资产按清单安装，先看可用空间、比对可用的摘要再解包；[工作区](https://github.com/ccch1mneyyy/dsh-Go/blob/9ad11167c53a5da12ff8791c21f4327f5d8a9a25/app/lib/ui/session/workspace_files.dart#L21-L52)区分已经交付的会话成果与当前目录。PocketWorld 不把候选文件名当成交付物，也不照搬其可访问工作区外文件的边界。
- [AndCode 固定工作区克隆](https://github.com/yuga-hashimoto/and-code/blob/244a7e098951e75110fbd9f132b19db3646dbaa0/app/src/main/java/com/yugahashimoto/andcode/runtime/local/GitCloneRepository.kt#L25-L96)、[冲突检查后原子保存](https://github.com/yuga-hashimoto/and-code/blob/244a7e098951e75110fbd9f132b19db3646dbaa0/app/src/main/java/com/yugahashimoto/andcode/runtime/local/WorkspaceFileWriter.kt#L33-L49)以及[从实际后端列目录](https://github.com/yuga-hashimoto/and-code/blob/244a7e098951e75110fbd9f132b19db3646dbaa0/app/src/main/java/com/yugahashimoto/andcode/feature/workspace/WorkspaceExplorerViewModel.kt#L138-L164)：实际目录发现和选定文件操作各有独立证据，不从命名惯例推断存在。
- [Operit 文件浏览](https://github.com/AAswordman/Operit/blob/7a39941bad80209973c535046a18195c5da9e647/app/src/main/java/com/ai/assistance/operit/ui/features/chat/webview/workspace/FileManager.kt#L200-L222)从当前环境的 list_files 取实际类型/大小；[工作区](https://github.com/AAswordman/Operit/blob/7a39941bad80209973c535046a18195c5da9e647/app/src/main/java/com/ai/assistance/operit/ui/features/chat/webview/workspace/WorkspaceManager.kt#L452-L475)另行写入，命令执行（774 行）和导出（1009 行）也独立。本次只借鉴区分“观察”和“操作”，不赋予终端或更大的目录权限。
