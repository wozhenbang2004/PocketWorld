# PocketWorld Windows 混合桌面能力

Windows Companion 继续作为手机 Agent 的执行端：结构化观察与操作由
agent-desktop 承担，显式视觉操作由 Cua Driver 承担，手机上的人工观看由
WebRTC 视频承担。手机保留 Agent 推理、模型凭据、任务与授权；电脑上的三个
可执行文件共同受原 Companion 管理。

## 正式入口与使用流程

手机入口为 **我的设备 → 查看设备与授权 → 查看桌面 → 连接桌面**。连接后默认
观看；点 **我来操作** 才申请人工控制，点 **交还 AI** 释放人工控制。页面沿用原
Android 原生桥接和 `desktop.viewer` 设备方法，不另建 Agent 任务或模型工具。

电脑沿用已安装的固定管理入口。打开本机配对窗口：

```powershell
& "$env:LOCALAPPDATA\PocketWorld\Companion\Manage-Companion.ps1" -Action PairDevices
```

在电脑选择准确范围，核对双端身份、累计次数和到期时间，完成双方确认后启动
桥接。主屏授权包含当前交互会话所有窗口的观看与人工操作，须在电脑明确确认。
**已有配对不能靠升级自动获得 `desktop.viewer`；旧授权需在电脑重新配对并确认
新的桌面授权。** 手机只列出已有相应授权的桌面入口；缺少原生桥接、媒体依赖或
有效授权时报告不可用。

本机停止仍使用同一管理入口的 `-Action StopDesktop`；恢复走
`-Action RecoverDesktop` 的原恢复流程。重新连接、重启或恢复不会重放效果未知
的操作。

## 三路能力怎样协作

| 路径 | 当前实现 | 返回什么、何时使用 |
| --- | --- | --- |
| agent-desktop 结构化执行 | 固定源码的 Windows headless CLI；读取 UIA，使用原生元素引用点击或替换完整值 | 精确窗口默认 `semantic`，返回有界结构与引用，不截图。目标不完整、歧义或引用失效时拒绝执行。 |
| Cua 视觉执行 | 固定 Cua Driver，沿用已有桌面适配器 | 显式 `hybrid` 返回结构与 PNG；`visual` 返回 PNG。主屏默认 `hybrid` 的窗口目录不等于窗口内部 UIA 树。视觉操作必须有对应的新观察。 |
| 人工桌面视频 | Companion 内 aiortc/PyAV，浏览器接收单向视频；授权窗口使用原 HWND 捕获 | 向手机用户传送桌面画面。视频不会成为模型图片，也不刷新 Agent 的结构化观察。人工输入仍走原 mTLS、授权、租约与持久操作记录。 |

结构化路径不保证每个应用都有可用 UIA；大型或自绘界面可能需要显式视觉观察。
结构化点击结果未知时，不会自动改用坐标点击。三路实现的精确契约分别见
[AGENT-DESKTOP.md](packaging/windows/AGENT-DESKTOP.md) 和
[DESKTOP-STREAM.md](packaging/windows/DESKTOP-STREAM.md)。

人工接管等待当前有界 AI 操作结束，且不能强行覆盖未知状态或其他所有者的租约。
人工租约与视频会话最长五分钟；交还后，AI 必须取得新租约和新观察才可继续。
每次人工输入引用最近的帧元数据，重新核对原进程、窗口、显示器、前台和物理
几何，再在同一个适配器锁内派发。帧元数据证明的是近期几何状态，不证明手机
此刻解码出的像素与电脑完全相同，也不证明业务目标已完成。

**人工输入消耗原 PairGrant 的 `toolBudget`，与 AI 工具共享同一有限次数池。**
计费点是持久占用并准备派发，结果未知的输入保留占用。观看、查询状态不消耗
这项输入次数；新 viewer、操作 ID、任务或授权代次不会重置原池。重复操作返回
原回执，不自动再次派发。撤销、到期、锁屏或本地停止终止可用会话；执行结果
未知时保留待核对状态，不因视频关闭而宣称输入未发生或自动恢复 AI。

## 网络与捕获范围

默认 `desktop.streaming.iceServers` 为空，以双方直接可达的 LAN 为基础，没有
内置公共 STUN/TURN。跨网络需要自行配置合适的 ICE 服务，并保证原设备信令与
输入通路可达：直接双向 TLS，或已有 WSS 字节中继承载的内层双向 TLS。
**TURN 只解决媒体路径，不替代配对授权和原 mTLS 路由。** LAN 连通不代表任意
NAT、防火墙或外网组合都能连通。

视频没有独立的未认证 HTTP/MJPEG 入口，也没有输入 data channel。默认长边
最多 1200 像素、10 FPS，最多两个观看会话；当前使用软件编码。窗口授权只捕获
原 HWND，不扩成包含其他窗口的屏幕矩形；整块主显示器需要对应主屏授权。
当前仅支持 Windows 交互用户会话与主显示器；锁屏、UAC/安全桌面、跨主屏边界
窗口和非主屏捕获不在本实现支持范围。受保护或特殊渲染窗口可能显示空白。

## 固定来源与 Windows 三二进制打包

| 组件 | 本实现固定版本与源码 | 事实源 |
| --- | --- | --- |
| agent-desktop | 包版本 1.1.0、JSON 契约 2.6；`6660fbac768dc7e7332837b90ed465684859afa3` | [源码 pin](packaging/agent-desktop-pin.json)、[构建与许可说明](packaging/windows/AGENT-DESKTOP.md)；固定 main 提交，不把同名发布包视为等价 |
| Cua Driver | 0.38.0 prerelease；`f595a4fb6f524e555e5e422aab81c3457fe4a713` | [Build-CuaDriver.ps1](packaging/windows/Build-CuaDriver.ps1)；只构建 Driver，不包含 Spaces 或 Perception |
| aiortc / PyAV | aiortc 1.15.0：`a1dc5e2546aa7f51a92860f358192590559d7225`；PyAV 17.0.1：`fa90286add4ebbaa2e141b5d6d1ba3e290973a38` | [媒体依赖清单](packaging/windows-stream-dependencies.json)、[媒体运行时说明](packaging/windows/DESKTOP-STREAM.md)，包含实际 wheel 哈希与许可 |
| 截图依赖 | Pillow 13.0.0 | [Windows 桌面依赖清单](packaging/windows-desktop-dependencies.json) |

在已经准备好的 Windows x64 构建机、PocketWorld 仓库根目录执行。三个源码工作区
都须是准确、干净的固定提交；Rust/MSVC 工具链、Cargo 缓存、Python 环境及完整
wheelhouse 须已准备。运行时使用独立审查的完整
`pocketworld.windows-runtime-inputs.v1` 锁及其已审查 SHA-256；媒体 requirements
不是完整环境锁。构建脚本不负责下载、安装依赖或生成自动信任的锁。输出目录
须不存在，并与源码、解释器和 wheelhouse 分开。

以下占位值须替换为本次真实审查值，`python` 须来自上述固定构建环境：

```powershell
$productCommit = '<PocketWorld 的 40 位提交 SHA>'
$runtimeLockSha256 = '<独立审查的完整环境锁 SHA-256>'
$bundleVersion = '<本次包版本>'

.\companion\packaging\windows\Build-CuaDriver.ps1 `
  -SourceRoot C:\Build\cua `
  -OutputDirectory C:\Build\pocket-cua

.\companion\packaging\windows\Build-AgentDesktop.ps1 `
  -SourceRoot C:\Build\agent-desktop `
  -OutputDirectory C:\Build\pocket-agent-desktop

python .\companion\packaging\windows\build_runtime.py `
  --source-commit $productCommit `
  --input-lock C:\Build\inputs\windows-runtime-inputs.json `
  --input-lock-sha256 $runtimeLockSha256 `
  --wheelhouse C:\Build\wheelhouse `
  --output C:\Build\pocket-runtime

$cuaArtifactSha256 = (Get-FileHash C:\Build\pocket-cua\cua-driver.exe -Algorithm SHA256).Hash.ToLowerInvariant()

# 明确的未签名开发包；不作为生产发布验收。
python .\companion\packaging\windows\build_bundle.py `
  --frozen-dir C:\Build\pocket-runtime\dist\PocketWorld-Companion `
  --driver-binary C:\Build\pocket-cua\cua-driver.exe `
  --driver-sha256 $cuaArtifactSha256 `
  --driver-provenance C:\Build\pocket-cua\cua-build-provenance.json `
  --agent-desktop-binary C:\Build\pocket-agent-desktop\agent-desktop.exe `
  --agent-desktop-provenance C:\Build\pocket-agent-desktop\agent-desktop-build-provenance.json `
  --runtime-provenance C:\Build\pocket-runtime\runtime-build-provenance.json `
  --runtime-notices C:\Build\pocket-runtime\runtime-notices `
  --source-commit $productCommit `
  --version $bundleVersion `
  --output C:\Build\PocketWorld-Companion-dev.zip `
  --allow-unsigned-development
```

标准包包含 `app/PocketWorld-Companion.exe`、`driver/cua-driver.exe`、
`driver/agent-desktop.exe` 及其运行依赖、构建凭据和许可。安装沿用
[Install-Companion.ps1](packaging/windows/Install-Companion.ps1)，由原安装器核对包
与实际二进制并写入原配置。生产包须使用实际签名后的产物、
`--signing-provenance` 和可信签名者核验，移除开发标志；原始源码构建记录与最终
签名产物记录分别保留。开发包的哈希或版本输出不等于 Windows GUI 验收。

标准 runtime 构建必须实际运行冻结后的 `--help` 和 `--stream-self-test`，后者
加载真实媒体依赖并在内存编码一帧合成 VP8 图像，不联网、不捕获桌面、不访问
配置或身份。缺 aiortc/PyAV 的源码开发环境诚实报告视频不可用；标准 Windows
包必须通过媒体探针后才可产生有效构建凭据。完整依赖与原生 FFmpeg 许可保留
方式见 [DESKTOP-STREAM.md](packaging/windows/DESKTOP-STREAM.md)。

### 采用关系

[微软 HCU](https://github.com/microsoft/Hybrid-Computer-Use) 提供程序化工具、
辅助功能结构和 GUI 三种互补通路，其 MCP 接口是 AX 与 GUI。本项目参考这一
分工，将程序化操作保留在原 shell/API 工具中，将结构化路径接到固定的
agent-desktop，将显式视觉路径接到既有 Cua Driver。HCU 本身没有作为新的 Agent
或另一套权限服务器运行；本次也没有承诺覆盖 HCU 的完整批处理、所有平台或
上游基准表现。这样可继续复用手机原有任务、授权、预算及 Windows 输入锁。
用户观看另走 WebRTC，这项产品能力与模型通过结构或截图观察是不同的调用路径。

## 验证范围

在 `companion` 目录可运行本次相关回归：

```text
python -B -m unittest -v test_desktop_stream test_desktop_worker_stream test_windows_runtime_build
python -B -m unittest -v test_desktop_viewer test_desktop_semantic_human test_device_protocol
```

2026-11-04 本地集成检查：Python 161 项中 159 通过，真实 aiortc/PyAV 回环与编码
两项因环境缺少运行库跳过；Java 共享协议 17 项及原生 Window 关闭竞态 4 项通过。
协议、结构观察与原生 Host 核心源码通过 Android SDK 35 javac 检查；TypeScript、
Vite、18 项 Node 契约及 6 个标明 mock RTC/native host 的真实浏览器界面旅程通过。
这些界面旅程使用真实 canvas MediaStream/video 解码，不能证明 Windows 传输。

[PR #42](https://github.com/wozhenbang2004/PocketWorld/pull/42) 已注册完整 AGP
Java/Kotlin 编译和 Robolectric/真实 loopback mTLS 检查。首轮
[GitHub Actions](https://github.com/wozhenbang2004/PocketWorld/actions/runs/41823649450)
在 runner 分配前失败：runner ID 为 0、步骤为空且没有日志，未执行代码编译。
该环境阻塞的具体原因未从当前 API 获得；完整原生 host 测试仍是未完成的交付门槛。
Windows 原生 UIA、窗口捕获、物理输入、冻结 EXE/安装器、Android WebView 播放与
触控，以及跨网络连接仍须在真实设备验收。这里提供的是已接入正式产品路径的
源码与构建入口，不是已完成这些平台验收的发布包。
