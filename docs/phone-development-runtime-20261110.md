# 手机本机开发：运行方式、首用与恢复边界

日期：2026-11-10。PocketWorld 代码对照基线：`94fa87098d6b5caf384dfe5c0cd00af88e827ff2`。

## 产品结论

手机上的用户想完成的是“说清要做什么 → 编辑真实项目 → 运行或预览 → 继续修改”，不是学会安装一个名为“内核”的组件。保留 PocketWorld 的原生 Agent/任务宿主，Linux 工具作为按需准备的项目运行条件，不把 Linux 启动变成普通聊天的前置条件。

项目工作页新增目标入口，沿用原 `ProjectOwnerWorkflows → AssistantModule → EngineApplication.startAssistantInput`。它固定私人项目、副本、源码版本和本机运行位置，不新造 runner、任务账本或执行授权。基础环境准备仍由现有 `RuntimeSetupHost` 管理，不另造安装任务/receipt。

## DSH Go 实际启动了什么

对照 `ccch1mneyyy/dsh-Go@8f31b2ed8c3fe0b49451379af7af4ddd00f29eef`：

- Flutter 是界面，Kotlin 宿主启动 Ubuntu 用户态中的 Node/DSH HTTP/WS 服务。默认通过 PRoot 映射路径和系统调用，使用的仍是手机 Android 内核，不是启动一个独立 Linux 内核或虚拟机。
- `dsh web --host 127.0.0.1 --port 3080 --no-open` 的 token/端口就绪承担聊天后端启动判定。PocketWorld 的 AgentRunner/ModelGateway 在原生宿主内，所以不能把这条 Node 服务启动链搬来当作聊天必经路径。
- 首装使用 APK 内 runtime 资产，校验后解包；安装前按展开尺寸乘 1.15 检查可用空间，无尺寸时保守回退。升级保留用户工具、包和工作内容，重建其拥有的引擎部分。安装页能回到同一进度，成功后再启动引擎。
- Direct 是默认关闭的受限替代启动：glibc loader 直接启动同一个 Node，配套路径、DNS、模块/原生扩展补丁；工具子进程仍有 PRoot 包装。失败按 runtime 记忆并回退 PRoot，不是任意 Linux 命令都可绕过 PRoot。
- 其冷启动文档是 2026-10-27、Android 15 x86_64 模拟器的少量样本。提前启动和路径缓存曾把图标到对话列表由约 3.02 秒降到 2.46 秒；不能作为我们的 ARM 手机承诺。常驻/开机启动也不等于真正冷启动更快。

证据：[架构](https://github.com/ccch1mneyyy/dsh-Go/blob/8f31b2ed8c3fe0b49451379af7af4ddd00f29eef/docs/architecture.md)、[冷启动分析及适用限制](https://github.com/ccch1mneyyy/dsh-Go/blob/8f31b2ed8c3fe0b49451379af7af4ddd00f29eef/docs/design/runtime-cold-start.md)、[启动实现](https://github.com/ccch1mneyyy/dsh-Go/blob/8f31b2ed8c3fe0b49451379af7af4ddd00f29eef/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/runtime/LaunchStart.kt)、[Direct 实现](https://github.com/ccch1mneyyy/dsh-Go/blob/8f31b2ed8c3fe0b49451379af7af4ddd00f29eef/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/runtime/DirectLaunch.kt)、[安装实现](https://github.com/ccch1mneyyy/dsh-Go/blob/8f31b2ed8c3fe0b49451379af7af4ddd00f29eef/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/runtime/RuntimeInstaller.kt)。

用户报告 [#711](https://github.com/ccch1mneyyy/dsh-Go/issues/711) 涉及 Android 9/Linux 3.18 的 PRoot 崩溃；[#885](https://github.com/ccch1mneyyy/dsh-Go/issues/885) 涉及 Android 17 的 Direct glibc `SIGSYS`，并称回退 PRoot 可用。这些是机型/版本相关的报告，不是我们复现的结果，不据此关闭系统安全控制或声称覆盖所有手机。

## 方案比较与取舍

| 方案 | 能借鉴什么 | 本轮决定 |
| --- | --- | --- |
| DSH Go 的 PRoot + Ubuntu + Node 服务 | 首装容量、同一进度返回、保留用户内容、明确回退 | 借鉴首用/恢复体验；不替换原生聊天与任务宿主 |
| DSH Go Direct | 固定入口的启动优化，需要实测、回退、原生扩展适配 | 不启用。我们的瓶颈不等同其 Node 插件加载；target SDK、ELF、seccomp 条件不同 |
| `kelai141/dsh-mobile-apk` 的 bionic Node | 原生 Android libc 可免去 PRoot 主服务开销 | 不把 Termux 快照当 drop-in。固定路径、包构建、glibc 原生扩展兼容与生命周期都需重新交付 |
| Termux/PRoot-Distro | 普通 Linux 用户态、归档恢复、固定镜像与缓存 | 可作兼容性参考；不是 root、内核隔离、systemd 或 Docker daemon |
| AndroidIDE / 社区 ARM64 SDK tools | 真正 APK 工具链需要独立 JDK/SDK/ABI 适配，不止有终端 | 不冒充已支持全链 APK 构建；见下方缺口 |

其他一手依据：

- [bionic Node 宿主](https://github.com/kelai141/dsh-mobile-apk/blob/64098a20b93d4af0579715db78d798acf16fdff7/app/src/main/java/com/dsharnessmobile/shell/EngineManager.kt) 与 [启动流程](https://github.com/kelai141/dsh-mobile-apk/blob/64098a20b93d4af0579715db78d798acf16fdff7/app/src/main/java/com/dsharnessmobile/shell/EngineStartFlow.kt)。包含直接 ProcessBuilder、linker64 回退与 Termux 运行时约束。
- [PRoot-Distro README](https://github.com/termux/proot-distro/blob/f8abdd9957f80586fb184fdd7d558950b8e641db/README.md)：路径翻译不提供真实 root/cgroups/namespaces；文件系统密集工作有 ptrace 成本。
- [AndroidIDE README](https://github.com/AndroidIDEOfficial/AndroidIDE/blob/77ee1a315f34b9ed74a9da94f94a0dc276f72ff6/README.md)：官方仓库已 archived，明确不再维护；有 JDK/Gradle/SDK 能力，不代表包含官方 Android NDK。
- [ARM64 SDK tools](https://github.com/amirghm/android-sdk-tools-arm64/blob/62f108e67bffa0fe42482623a33e879fd626d28c/README.md)：作者报告单一 ARM64 Android + PRoot 环境下 Debug/Release 构建和安装成功，但 Bionic Build Tools 依赖 Termux 固定路径，glibc Platform Tools 是另一包；SDK 平台、Java 工具和许可文件不包含，跨机器字节级可复现性尚未建立。

## PocketWorld 现在的实际条件

`app/src/main/assets/runtime/catalog.json` 固定 arm64-v8a、Ubuntu 24.04.5、Node 24.22.2 和 PRoot 版本/摘要。基础 archive 共约 58.1 MiB；展开尺寸加 512 MiB 余量的最低可用空间约 797 MiB。最低空间不是完整项目预算，APT、node_modules、.venv、构建结果、旧 generation 和项目副本会额外占用。

`AndroidRuntimePreparation` 下载校验归档、保留 `.part` 显式续传、建立并密封 generation，切换 active pointer。APT/bootstrap 仍可能联网，归档缓存齐全不保证离线首装完整成功。修复保留旧 generation 和项目，不通过重置数据解决安装失败。

基础实际工具由本机安装/probe/hash 事实显示，包含 Node/npm、Python、Git、sh。`ProjectBuildPlan` 的 Node 依赖安装默认 `--ignore-scripts`；需要生命周期脚本时另审精确命令。本机 Python 使用项目 `.venv`。这不等于任意依赖、浏览器二进制、原生扩展或 APK 工具链都可运行。

`AndroidManagedRuntimeFactory` 限定实际 ARM64 ABI。当前 APK `minSdk=30`、`targetSdk=35`；构建从固定 PRoot 源码产出 APK 中的 `libproot.so` / `libloader.so`（作为 execve payload，而非 dlopen 库），运行前核对 SHA，`PROOT_LOADER` 指向 APK nativeLibraryDir 中的 loader。DSH 当前文档明确依赖 targetSdk 28 的私有目录 exec 条件。不能照搬其 Direct 启动，也不为此降 SDK 或关闭系统安全。证据为本仓库 `app/build.gradle`、`tools/build-android-runtime.py`、`AndroidRuntimePreparation.hostEnvironment/command`，不是设备执行证明。

`ManagedProjectRuntime` 明确是同 UID 进程组，不是安全沙箱。安装完成、工具已挂载、本次操作已批准是三件不同的事。手机无障碍/屏幕操控权限不是本机开发的前置条件；远程设备也不作为失败后的隐式替代运行位置。

## 正式用户旅程与本轮实修

| 场景 | 进入/恢复方式 | 实现边界 |
| --- | --- | --- |
| 第一次从想法到项目任务 | 新建项目 → 工作页填目标 → 准备或选择私人副本 → 自动读取实际源码 → 审阅模型/副本/版本/本机 → 开始 | 副本准备有固定原请求身份；单一现有普通副本可建议选择，多副本不猜；不自动启动终端 |
| 基础环境缺失 | 工作页/任务页进入已有“工具准备” | 复用 RuntimeSetupHost 的原安装审阅、任务、进度、取消和查询；不需要先造一个 AI 安装任务 |
| 运行与预览 | 已接受回执打开原项目任务；按原计划修改、依赖安装、运行和查看结果 | 沿用 ProjectCapabilityPort / ProjectBuildPlan、实际日志、固定包与 HTTP/WS live preview，不把端口打开当业务完成 |
| 自己编辑真实工作副本 | 工作页明确选择私人副本 → 编辑这份私人副本的文件 → 读取实际文字/新路径 → 保存 | 复用原 ProjectFileDraft/ProjectFileDraftStore；256 KiB 编辑/保存上限；按 SHA-CAS 保存，运行中/未知进程仍阻止不安全写入；不会把正式版本编辑误作运行副本编辑 |
| 手工修改保存后采纳 | 同一文件 → 比较正式版本与已保存副本 → 确认这一份文字差异 | 复用原 reviewFile/importToProject；实际 source SHA + 正式版本 CAS。只采纳当前文件；同内容只明确登记基线；未知回执重新比较，不盲目重写版本 |
| 编辑退出/重开 | 每次编辑进入原 app-private 草稿缓存；重开先恢复再观察实际文件 | 缓存异步持久化，只有 durable 标识才称已落盘。尚未确认的界面文字在当前 Web 文档保留，和恢复草稿不同必须显式选择；进程被系统杀死前未落盘的最后文字不能保证恢复 |
| 同一件事继续修改 | 打开原任务补充/继续；已接受的目标从草稿中移走 | 不把继续误作新 client 或新任务；具体继续仍服从原任务状态与审批 |
| 页面关闭/后台/切换项目 | 原生 page/review 失效；保留目标、原 client 和副本准备引用 | 晚回执只更新同 client，不写入新页面；离开页面不是停止任务 |
| 完全冷启动 | app-private 展示草稿先恢复；Activity Bundle 不覆盖新存储；pending 显示 unknown | 发起前先持久提交原请求身份。只查询原 Assistant/TaskStore，不因 not_found 自动重发；审阅句柄和授权从不恢复 |
| 离线 | 已有项目、文件、副本仍可读，已安装工具不需每次重装 | 远程模型与缺失依赖不能虚构离线可用；没有暗换模型、设备或自动补下载 |
| 安装失败 | 查询原安装任务，显示其实际失败阶段和分类 | `ManagedProcessJobs` 把 `preparationFailure` 写回原 receipt；RuntimeSetupState 不再用全局 preparation.json 的同 code 失败替旧任务作解释 |
| 副本准备中断 | 查询同 requestId；部分目录保留且不覆盖 | 可以显式去原手动副本入口另备完整副本；不将残留目录冒充就绪，也不自动删除 |

首用入口实现：`ProjectDevelopmentWorkspace.tsx`、`PocketWebProjectDevelopment.kt`、`ProjectOwnerWorkflows` 的 owner helpers。目标提交复用原 Assistant。native fence 在模型已捕获、任务分配前再核对窗口/模型 revision；不持有通用 app monitor 进入 Assistant，保持既有锁序。

工具准备修正：`RuntimeSetupState`、`ManagedProcessJobs`、`SetupWorkspace`、`setup-contract`。手动终端 `LocalWorkspace` 选定副本后自动 describe，并绑定同 project/checkout/window，失败保留显式刷新。

## 私人副本与正式文件的衔接

原 Web“项目文件”编辑的是 `VersionedWorkspace` 正式版本，Agent/手动终端使用私人 checkout，两个位置不是同一棵目录。本轮新增独立 `ProjectCheckoutFiles` / `PocketWebCheckoutFiles`，明确展示所选原副本，接上现有原生能力，不替换正式文件编辑器。

- 列表来自实际 `changes`，40 项一页，翻页以原 sourceDigest 防止拼接不同树。保留草稿路径并列，不从配置猜文件。源码最多 2000 文件/16 MiB；路径范围沿用原规则，不编辑 `.git`、依赖和运行缓存。
- 原共享 DraftStore 管理草稿、冲突、容量与持久化错误。干净文件退出释放缓存，未保存文字不静默淘汰。新页面/其他编辑器不能以旧 revision 覆盖新稿。无法读取损坏草稿时保留原缓存，不把空白写回。
- 保存复用原手工 writer，原任务/进程状态守卫与实际 SHA-CAS 都保留，不从编辑器创建热修改权限。未知保存结果要求读取同一文件；读到变化也保留草稿，并提供明确的采用当前副本或保留我的修改选择。
- 逐文件采纳仅使用 native 保留的 2 分钟单次审阅，Web 不提交 replacement body/正式版本/任意 diff。原文本 importable/完整差异/65536 字符限制照旧；不把二进制强行改文字。正式写入和副本基线不是跨存储原子事务，部分完成会明确显示原 receipt，重新比较后可只登记已一致基线。
- 保存、采纳都不启动代码、发布或分享，不新增工具授权。原任务的 command/dependency/live preview 审阅仍留在原链上，编辑器不绕过在运行的原服务。

## 仍不能宣称完成的部分

1. **完整 Android APK 工具链**：现 catalog 没有 JDK/SDK/Gradle。社区方案的 Bionic 绝对路径、glibc 工具、AGP/JDK/SDK/ABI 矩阵、签名密钥保护、许可证和分发摘要需独立整合。未经适配和真实设备证据，不把“安装成功”包装成“任何 Android App 都可构建”。
2. **离线第一次安装完整环境**：DSH 的 bundled runtime 有优势。我们当前是下载 catalog + bootstrap；要改变这一点，需选择发布包体积预算，产出含完整依赖的可分发、可验证 artifact，而不是仅加一个“离线可用”开关。
3. **设备性能与兼容性**：没有运行真机或模拟器，没有测得 PocketWorld 的启动、内存、发热、后台保活、SELinux/seccomp/PRoot 兼容矩阵。不能引用 DSH 的模拟器数字充数。
4. **损坏的持久草稿**：保留原存储并使新提交只读受限，已有任务/文件仍可从原列表核对。需保留诊断与数据后修复存储，不以清空应用数据恢复。未知输入没有可证明未派发的证据时不授予重发许可。
5. **验证状态**：按要求未写测试、未运行测试、未真机测试。当前没有编译、视觉验收或运行成功结论。交叉核对是源码契约检查，不是业务验收。

## 整合注意

独立新文件和原生 owner helpers 可单独保留；`App.tsx`、`PocketWebBridge.kt`、`PocketWorldController.kt` 与其他对话可能并行变化，应先对照最终 main 的同位置小 hunk，再统一整合。本轮不得提前提交 main。UI 保留既有 BoardUI fields、Accordion、SettingsDialog 和本机工作样式；没有替换原正式文件编辑器、终端或预览器。私人副本编辑和逐文件采纳是独立新增组件。

交付顺序：先 stage1 基础体验修正，再 stage2 项目目标 core/integration，再 stage3 私人副本文件增量，再 stage4 逐文件采纳与本说明增量；各包带独立基线/摘要，不重复应用最终内容。冻结候选不会自动成为 main 或已发布功能。
