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
