# PocketWorld 的同类产品下限：盯旅程，不比功能图标

日期：2026-11-19。以 1149 已发布、1150 代码已进 main 为观察点。下表竞品能力来自其公开源码与文档，尚未做横向真机跑分。验收用实际设备与实际成果，不用截图或编译替代，本仓库的执行、授权和存储事实继续由原生持有。

| 产品域 | 对标的具体机制 | PocketWorld 必须走通的主路径 |
| --- | --- | --- |
| 手机本机开发 Harness | [DSH Go](https://github.com/ccch1mneyyy/dsh-go/blob/main/README.en.md) 来自 App 的首次工具准备与手机端工作；[DSHA](https://github.com/DSH-APP/DSHA/blob/main/BUILD.md) 固定可核对的运行输入。完整环境与小包不是一件事：DSHA 选择离线捆绑并承担更大的安装包。 | 干净手机不用电脑、Termux 或用户自己敲命令即可看到可用工具、准备和导入；同一工程的副本、原执行位置与产物有衔接。首个 Web/Python 工程和能在手机构建 APK 是不同验收，不拿源码识别代替后者。 |
| 本机 Phone Use | [OpenPhoneUse](https://github.com/joungminsung/OpenPhoneUse) 把无障碍主屏做成独立可用路径；[Mika](https://github.com/wangzan101/Mika/blob/main/docs/features.en.md) 提供独立屏体验。 | 首次交办即知道在哪个屏、还缺什么、是否真实看见第一屏；停手有明确回执。主屏与独立屏分别验收，不因为一个屏就绪暗示另一屏可用。 |
| 管家和记忆 | [OpenClaw](https://docs.openclaw.ai/concepts/memory) 和 [Hermes Agent](https://hermes-agent.nousresearch.com/docs/user-guide/features/memory) 把长期记忆与后续交办连起来。 | 交办先进入管家而不是执行器选择面板；离开再回来能找回同一工作和原汇报。用户明确记住的偏好与仅本轮输入分开，撤回后原入口同步，不把提供给模型说成已采用。 |
| 多 Agent 协作 | [AionUi](https://github.com/iOfficeAI/AionUi/blob/main/readme.md) 的 Team Mode 把负责人、委派和成员回传分开。 | 用户只交一个目标；团队汇总能回到成员真实交付，某个成员卡住不要求重跑整个团队。不得拿正文自述当派发或完成。 |
| 其他手机 | [OpenClaw Nodes](https://docs.openclaw.ai/nodes) 把配对节点及其能力分别管理。 | 选实际目标手机后能区分仅连通、可读、可接收输入；在原任务看真实接纳与后续屏幕或结果。用户没有选的设备不自动接单。 |
| Windows 与云服务器 | [Hermes Computer Use](https://hermes-agent.nousresearch.com/docs/user-guide/features/computer-use) 将可操作桌面的会话作为明确前提；[OpenHands runtime](https://docs.openhands.dev/openhands/usage/sandboxes/overview) 单独抽象执行环境。 | Windows 桌面有本人的可交互会话、授权范围与实际回读；SSH 是终端和文件，不声称可以点桌面。云主机开哪台、原工作在哪里、东西到底有没有保存分开说。无 Companion 安装器或无设备时保留实际边界，不给成功标志。 |

同行项目的权限设定、root、调试桥和外部渠道不会自动进入 PocketWorld。可借鉴互相独立的能力、准备状态和使用流程，第三方实现复用需分别核对许可证、来源和所需授权。

### 两处源码核对

- DSHA 的 [Gradle](https://github.com/DSH-APP/DSHA/blob/main/app/build.gradle) 产物面向 arm64；[固定输入锁](https://github.com/DSH-APP/DSHA/blob/main/ci/sunset-inputs.lock.json) 对完整构建输入记录 SHA-256 和字节数。锁记录不是手机实际下载量。PocketWorld 采用“备妥一次、显示真实环境、再固定项目执行”的体验目标，不照抄离线大小和特权设定。
- OpenPhoneUse 的 [ToolRegistry](https://github.com/joungminsung/OpenPhoneUse/blob/main/app/src/main/java/com/phoneuse/agent/ToolRegistry.kt) 将手机、记忆、用户确认和连接器分别交给模块；[DeviceInfoModule](https://github.com/joungminsung/OpenPhoneUse/blob/main/app/src/main/java/com/phoneuse/agent/DeviceInfoModule.kt) 明确给出了“代码存在但未进入注册表”的范例。PocketWorld 的设备状态也不应把“实现了这个工具”合并成“这台设备、当前屏幕可用了”。

### 2026-11-21 · 用同一条用户旅程决定开发顺序

基线为已发布的 `v0.1.304`；下述判断来自固定版本的源码和上游文档。签名出包、静态走查和实际体验分别记录，本次没有安卓实测。

- **PW-FLOOR-DEV1，本机首次交付（P0，未达到）**：不接电脑、不另装 Termux、不为了执行固定命令先配模型。导入真实项目，缺环境/缺依赖都在同一个项目给出可操作的下一步；离开再回来恢复同一次工作。改文件、确认构建、打开这次实际生成的成果。DSH Go 的[安装流程](https://github.com/ccch1mneyyy/dsh-Go/blob/9ad11167c53a5da12ff8791c21f4327f5d8a9a25/app/lib/ui/onboarding/runtime_setup_page.dart)明确区分选择、安装、失败和恢复，[项目文件](https://github.com/ccch1mneyyy/dsh-Go/blob/9ad11167c53a5da12ff8791c21f4327f5d8a9a25/app/lib/ui/session/workspace_files.dart)和[会话成果](https://github.com/ccch1mneyyy/dsh-Go/blob/9ad11167c53a5da12ff8791c21f4327f5d8a9a25/app/lib/ui/session/session_artifacts.dart)回到同一会话。PocketWorld 已有运行环境准备和依赖已就绪的小型 TypeScript 构建，新导入的依赖准备仍未发布。先用原项目构建面板接上已审阅的依赖准备、真实回执和依赖复核，再另行确认构建。工具可能需要联网下载，不把无电脑写成离线首装。
- **PW-FLOOR-DEV2，普通 Web 项目（下一优先，未达到）**：对已有常见项目改一处内容、保存并看到更新后的真实页面，预览和构建成果仍归原项目。参考 [Operit 的项目工作区与终端](https://github.com/AAswordman/Operit/blob/main/README.zh-CN.md#主要能力)：文件编辑与网页预览不要求切到别的工具。结合 PocketWorld 已有 Vite 与固定成果路径，不扩大为任意前端或 Android APK 已支持。
- **PW-FLOOR-PHONE1，首次有用的手机任务（P0，未达到）**：在原聊天明确选一个已授权 App，看到它的实际第一屏和当前步骤，可交回用户或停止，结果仍可从同一聊天打开。参考 [Mika 的可见进度、接管与继续](https://github.com/wangzan101/Mika/blob/main/docs/features.en.md#operates-any-app-without-taking-over-your-screen)；主屏先验，后台独立屏逐项单独验。PocketWorld 已发布原投递结果回读，不等于能取得新选中 App 的第一屏；前台 App 的身份仍需可靠匹配，不能用连接或启动成功代替实际看见，不复制竞品的授权默认值。

### 2026-11-21 · 普通网页的发布与下一道验收

- **已发布：** `v0.1.305` 补上符合条件的新 TypeScript/npm 项目的锁定依赖准备；`v0.1.306` 补上基础 Vite 实际构建和原工作固定网页；`v0.1.307` 允许直接查看刚构建的基础 Vite 网页，不用先保存。三个版本的签名发布已核对；尚未在安卓上走完整条用户旅程。
- **React 项目验收：** 按 [Vite 官方 React JavaScript 模板](https://github.com/vitejs/vite/tree/main/packages/create-vite/template-react) 的默认配置，导入带原始 npm 锁的可信项目，在手机上修改 `src/App.jsx` 中只由组件渲染的文字。确认所需依赖与构建，在原工作打开这次生成的网页，核对新文字和本地样式，点一下原组件的计数按钮。返回同一份工作，固定保存可选。这条验收不能拿构建退出码、源 HTML、截图或新开任务替代；自定义配置、React TypeScript 构建和热更新不算本轮覆盖。
