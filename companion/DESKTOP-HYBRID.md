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
默认窗口观察只在原生树明确返回 `elements_complete=true`、且没有截断或身份
缺失标记时可用于结构化输入。仅安装 Cua、无法证明树完整时会明确拒绝默认
`semantic` 并提示显式选择 `hybrid` 或 `visual`；不会凭空补出完整性证明。
结构化点击结果未知时，不会自动改用坐标点击。三路实现的精确契约分别见
[AGENT-DESKTOP.md](packaging/windows/AGENT-DESKTOP.md) 和
[DESKTOP-STREAM.md](packaging/windows/DESKTOP-STREAM.md)。

人工接管等待当前有界 AI 操作结束，且不能强行覆盖未知状态或其他所有者的租约。
人工租约与视频会话最长五分钟；交还后，AI 必须取得新租约和新观察才可继续。
每次人工输入引用最近两秒内的真实发送帧。Companion 在原帧最后一个 RTP 包
完成发送后发布实际 `rtpTimestamp`；手机必须把它与**当前显示帧**的视频回调
时间戳逐一匹配，才开放输入。媒体与元数据可按任一顺序到达，但旧帧、相同尺寸
的其他帧、AV PTS 或重传不能代替这条身份对应。浏览器缺少该回调字段、媒体
实现校验不匹配或帧过期时保留观看并禁用输入。

派发前还会重新核对原进程、窗口、显示器、前台与物理几何，使用原 Cua capture
身份映射坐标；DWM 窗框裁剪只有可唯一映射时才允许点击。上述对应证明输入
引用了用户实际显示的编码帧，不证明截帧之后桌面完全没变，也不证明业务目标
已完成。接管、交还和输入会废弃旧观察与可操作帧，要求后续取得新证据。

最多两个窗口可同时观看，只有一个可持人工控制权；另一个窗口显示“另一窗口
正在控制”。滚动针对当前前台的授权 HWND，使用原生窗口滚动路径；不接受网页
自填屏幕指针位置，也不继承旧点击位置。支持有界点击、滚动、文字和有限键盘
输入；本轮不提供拖拽，也不宣称硬件加速已完成。

**人工输入消耗原 PairGrant 的 `toolBudget`，与 AI 工具共享同一有限次数池。**
计费点是原操作账本持久占用输入尝试的身份，操作占用和扣次数在同一事务中
完成；已占用的失败或未知输入保留占用。观看、查询状态不消耗
这项输入次数；新 viewer、操作 ID、任务或授权代次不会重置原池。重复操作返回
同一运行时保留的原回执，不自动再次派发；回执缓存丢失或重启后明确报告结果
不可重放。撤销、到期、锁屏或本地停止终止可用会话；只有
`inputReceipt.status=completed` 且 `inputDispatched=true` 才视为已确认派发。

结果未知时，本人仍可明确交还或关闭页面，但全局 `needsReconcile` 不会因此
消失，其他窗口也不能借新会话接管或继续输入。关闭只释放本会话已结算的安全
租约；仍在执行、结果未知或本机暂停的资源栅栏保留。升级旧主干版本时，旧
操作 ID、查看会话 ID、已消费额度和原资源栅栏同样保留，不能因换了账本入口
而重新执行；旧记录不被改写为“已确认无后续输入”。

连接从打开请求到第一幅实际播放画面最多等待 45 秒；已经播放后持续停帧，
给予 10 秒恢复窗口。视频轨道结束或元数据通道关闭时关闭准确的原会话；
短暂 mute、断线和重新连接期间禁止输入，恢复后要求新的画面证据。缺少可验证
RTP 身份但视频仍在正常播放时保持仅观看，不会把“不能接管”误判为“视频卡死”。
浏览器同时缺少帧回调和解码帧计数时，仅用递增播放时间维持观看；它不能证明
新像素已经显示，也不作为帧新鲜度或输入授权的依据。
用户手势在等待状态查询前固定它对应的画面与控制版本；等待中画面或控制权改变，
该次尚未发送的输入就取消，不改绑到新画面，也不标成已发送但结果未知。

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

在 `companion` 目录、已准备实际媒体 SDK 的 Python 环境运行：

```text
python -B -m unittest -v test_desktop_rtp test_desktop_stream test_desktop_worker_stream test_desktop_viewer test_desktop_semantic_human test_desktop_adapter test_agent_desktop_driver test_desktop_jobs test_desktop_recovery test_desktop_pairing test_desktop_runtime test_windows_gui_gate test_windows_runtime_build test_windows_packaging test_windows_installation_entry test_windows_install_recovery
```

这批回归保留主干语义观察、双 worker 原生停止证明和显式 hybrid 的 Windows GUI
用例，并把旧人工控制 API 的场景迁到实际 mTLS 授权、原 SQLite 操作账本和原
桌面租约。新增证据覆盖共享额度与重启、旧票据保留、真正执行中的 AI 输入与
人工竞争、两窗口控制状态、锁屏清理、单次派发和严格帧对应。

真实媒体用例使用合成图像，实际建立本地 ICE/DTLS/SRTP/SCTP 通道，解码视频，
把接收 RTP 包时间戳和解码器时间戳映射与元数据核对；独立编码探针实际加载
aiortc/PyAV 并生成 VP8。缺少媒体 SDK 时只能跳过这些媒体实测，不能据此报告
媒体通过。RTP 零值、32 位回绕、重传和失配关闭输入另有契约回归。

上述 Linux 契约和回环不代表 Windows 原生 UIA、HWND 捕获、物理输入、冻结
EXE/安装器、Android WebView 播放与触控或跨网络连接已通过。Windows GUI
用例保留实际平台 gate，必须在受控真实桌面运行；完整 Android 与发布构建
也以对应提交的真实 CI/设备结果为准。本文件提供正式源码与构建入口，不能
代替这些平台验收或签名发布包。
