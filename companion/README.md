# PocketWorld Companion

## 可配置 Harness 与团队成员

团队成员可以绑定电脑上不同的执行器。手机保存成员分工、原团队轮次、消息和批准记录；所选电脑上的原 Companion 启动对应 Harness，并继续使用同一份任务、操作、审批、取消和成果记录。设备、执行环境、Harness 和模型是四个独立选择；安装在电脑上的 CLI 不会变成手机上的另一个执行环境。

当前保留原生 `codex_app_server`、`claude`、`pi`，增加 `acp_<名称>` 的 ACP v1 接入和 `muse_msp` 的独立 MSP 接入。内置目录提供 39 个 ACP 启动预设及 Muse，也可配置自己的 ACP 程序。**目录中的预设不代表已安装、已登录或已完成真实推理；必须以本机协议探测和原任务结果为准。**

### 在执行电脑启用

先通过所选 Harness 自己的官方安装和登录方式准备它。Companion 不安装或升级软件，不自动登录，也不从手机接收 executable、argv 或凭据环境变量。

源码运行方式：

```bash
# 只检查预设、配置与可执行文件；不启动 Harness。
python3 companion/pocket_companion.py --list-harnesses

# 查看一个已有环境；路径和环境 ID 替换为实际值。
python3 companion/pocket_companion.py --config /absolute/owner/companion.json \
  --list-harnesses --environment computer

# 在 Companion 停止后，由主人明确启用该环境的外部工具执行。
python3 companion/pocket_companion.py --config /absolute/owner/companion.json \
  --enable-harness acp_opencode --environment computer --allow-harness-write
```

`--allow-harness-write` 明确将所选环境设为可写。已经可写的环境不需要重复传此选项。启用只修改原配置，不启动任务；随后重启原 Companion。

Windows 安装包包含相同能力，不需要安装源码 Python。在停止 Companion 后，通过原固定 launcher 操作：

```powershell
$pwHarnessLauncher = Join-Path $env:LOCALAPPDATA 'PocketWorld\Companion\Launch-Companion.ps1'
& $pwHarnessLauncher -Action ListHarnesses -Environment computer
& $pwHarnessLauncher -Action EnableHarness -Environment computer -Harness acp_opencode -AllowHarnessWrite
```

安装版仍核对当前安装指针、原配置位置和原用户会话锁。它只更新该安装自己的 `companion.json`；配置变化采用原字节比较和原生替换，未知替换结果保留本地核对材料，不自动重试。运行中的原 Companion 会阻止配置修改。

### 自定义命令与 Windows npm CLI

环境对象内的 `harnessWorkers` 保存启动定义，`workers` 决定允许启用哪些 ID。它与原有 `harnesses` 配置文件绑定功能分开。自定义 ACP ID 使用 `acp_` 前缀，例如 `acp_my_agent`；命令只能来自电脑主人配置。

```json
{
  "id": "computer",
  "kind": "local",
  "root": "/absolute/workspace",
  "writeAllowed": true,
  "workers": ["codex_app_server", "acp_my_agent"],
  "harnessWorkers": {
    "acp_my_agent": {
      "label": "我的 ACP 执行器",
      "command": "/absolute/installed/agent",
      "args": ["acp"],
      "ownerManagedExecution": true
    }
  }
}
```

可选字段是 `env`、`label`、`authMethod`。`authMethod` 必须是该程序实际公布的认证方法 ID；留空时使用主人已准备的认证状态。凭据应在电脑上的受保护配置或服务环境中管理，不能写进任务、仓库或聊天。程序缺失、协议不兼容、模型未确认或会话访问失败都会阻止新任务。

Windows 不执行 `.cmd`、`.bat` 或隐式 shell fallback。对于 npm 发布的 JavaScript CLI，使用主人已安装的 `node.exe` 加该包**实际存在的绝对 JS 入口**。下面的文件内容只是路径格式示例，必须换成所选包的实际入口：

```json
{
  "label": "我的 ACP 执行器",
  "command": "C:\\Program Files\\nodejs\\node.exe",
  "args": ["C:\\OwnerTools\\my-agent\\dist\\cli.js", "acp"],
  "ownerManagedExecution": true
}
```

将定义另存为本地 JSON 文件后，可以直接使用安装版启用：

```powershell
& $pwHarnessLauncher -Action EnableHarness -Environment computer -Harness acp_my_agent `
  -HarnessDefinition 'C:\OwnerTools\my-agent-definition.json' -AllowHarnessWrite
```

源码入口对应 `--harness-definition /absolute/owner/definition.json`。定义只能替换所选 worker 的主人配置，不能改变安装版的状态目录或设备身份。

### 手机上核对与执行

1. 在原连接页或团队成员绑定中读取实际环境，选择启用的执行器。
2. 核对模型和模式。ACP 会进行实际 `initialize` 与临时 `session/new`，读取程序返回的选项；Muse 使用 MSP 初始化和模型目录，探测阶段不会创建工作区会话。两者都不发送模型 prompt。
3. 选择具体模型/模式后再次探测，使观察与最终选择一致。没有公开目录的程序也可填写所需模型，但只有协议实际确认后才能执行。握手成功不表示账号计费、模型推理或全部工具能力已经验证。
4. 明确接受该电脑的主人管理执行策略，再审阅并提交原工作。ACP/Muse 不宣称操作系统只读沙箱；`readOnly:false` 和风险确认是明确的工作范围。
5. 在原任务内查看输出、真实权限请求、团队消息和成果。审批绑定同一个 job、operation、session、prompt 和请求内容，只提供该程序真实支持的一次性选择。

通用 Harness 观察有效期为 10 分钟。配置、可执行文件身份、模型/模式目录或 Companion 实例发生变化后，需要重新探测。提交前还会再次核对协议和选择。续接只使用同一个已结束且进程已确认停止的原会话，并要求实际协议支持恢复；未知结果、断线和重启不会自动重发 prompt。ACP/Muse 当前不提供执行中的输入 steering。

团队工具只有 `team_send_message`、`team_read_messages`、`team_list_members`，都回到原成员轮次、消息范围和配额。它们不能授予文件、执行或审批权限。临时桥接凭据只存在于原任务进程中，不进入公开任务或持久化命令。

原生 **`pi`** 保持关闭文件与 shell 工具；加入团队时只启用上述三个团队工具，适合讨论与审阅。**`acp_pi`** 使用主人另行安装的 ACP 编码入口，按主人管理策略运行，能力必须重新探测。两条路径的工具权限不能互相推导。

代码生产继续使用原“选定文件副本”或“完整项目副本”流程。只有环境与 worker 同时报告 `assignmentWorkspace` / `projectProduction` 可用时才可选择；目前复制执行依赖本地 POSIX 工作区能力，Windows 不会被宣称已具备该能力。Codex、Claude、ACP 和 Muse 的结果仍经过原审批范围、时间限制、进程退出证据和成果冻结步骤；副本隔离不等于整个外部程序的 OS 沙箱，成果不会自动覆盖源项目。

固定 `gemini_acp` 继续采用下面的原安装指纹和范围约束；通用 `acp_gemini` 是独立的主人管理 ACP 选择，不会沿用或放宽固定路径的批准。

## 固定 Gemini ACP 接入

当前 Companion 在原有 Codex、Claude、Pi 和明确命令流程上，增加了一个固定后端：**Gemini CLI 0.66.0、ACP 协议版本 1**。它使用现有任务、操作记录、审批、查询与取消流程。

这条接入仅用于主人在手机连接页明确创建的**首次私人可写工作**。主机必须是本机 Linux，所选环境必须显式允许写入。它不提供操作系统只读隔离；原有 Codex 只读流程和其他执行器的能力边界继续适用。

## 1. 使用前提

- 主人已自行准备可信的 Node 可执行文件和完整的 `@google/gemini-cli` **0.66.0** 安装树。Companion 不负责安装、升级或搜索替代 CLI。
- 工作目录已经存在，环境 `kind` 为 `local`，`workers` 包含 `gemini_acp`，`writeAllowed` 为布尔值 `true`。
- 启动 Companion 的受保护服务环境已经提供 `GEMINI_API_KEY`。不要把 API key 写进此仓库、配置 JSON、任务文字或手机表单。
- 手机连接已经使用原 Companion 认证配置。Companion 的访问 token 与 Gemini API key 是不同凭据。

代码只观察到 API key 是否存在。**凭据存在、安装内容核对成功和 ACP 握手成功，都不能证明认证成功或模型已完成推理。** 本接入不执行登录、OAuth 或账号切换。

## 2. 只读生成安装指纹

以下命令从仓库根目录执行。所有 `/absolute/...` 都是必须替换的示例路径，不能原样使用。

安装根目录必须包含这个准确入口：

```text
/absolute/real/npm-root/node_modules/@google/gemini-cli/dist/index.js
```

使用现有 helper 读取安装字节：

```bash
python3 -B companion/gemini_acp_profile.py \
  --node /absolute/real/node \
  --installation-root /absolute/real/npm-root
```

该命令只读文件并输出候选配置，不执行 Node 或 Gemini，不安装依赖，也不启用环境。对应 Python API 是 [gemini_acp_profile.py](gemini_acp_profile.py) 中的 `installation_configuration(node_executable, installation_root)`。

输出恰好包含四个字段，将实际输出作为所选环境的 `geminiAcp` 对象：

| 字段 | 实际含义 |
| --- | --- |
| `nodeExecutable` | 现存、可执行、绝对且已解析的 Node 文件路径；不能填脚本启动器或路径别名。 |
| `nodeSha256` | 该 Node 文件字节的 SHA-256。 |
| `installationRoot` | 完整 npm 安装根目录的绝对真实路径。 |
| `installationSha256` | 完整安装树按固定规则得到的内容摘要。 |

生成和验证共用 `_observe_installation`，不需要先填写摘要才能生成摘要。安装树摘要以排序后的相对 POSIX 路径清单为输入：普通文件记录路径、长度和 SHA-256；允许的内部文件链接记录路径与链接文字。清单使用固定 UTF-8 JSON 编码后再求 SHA-256。目录的身份和权限也会核对；不能跳过不可读内容、特殊文件、指向树外的链接或目录链接。

Node 和安装内容须由运行用户或 root 拥有，且不可被组或其他用户写入。核对还有固定的文件数量和总字节上限。任何来源变化都会使原观察失效，需要重新核对。安装字节身份只说明本次读取的来源，不能证明所有子进程执行链或操作系统沙箱。

## 3. 配置现有 Companion

`geminiAcp` 位于 **`environments` 中每个环境对象内**。已有配置应保留原环境、Codex/Claude 的 worker 与权限设置，只为需要这项能力的环境增补固定配置。

下面是一份最小独立示例。路径必须替换为实际路径，两个 `REPLACE_...` 必须替换为上一步 helper 的实际输出；占位值会被拒绝。

```json
{
  "listen": "127.0.0.1",
  "port": 8766,
  "tokenFile": "/absolute/owner/companion-token.txt",
  "stateDir": "/absolute/owner/jobs",
  "environments": [
    {
      "id": "computer",
      "label": "我的电脑",
      "kind": "local",
      "root": "/absolute/workspace",
      "workers": ["gemini_acp"],
      "writeAllowed": true,
      "geminiAcp": {
        "nodeExecutable": "/absolute/real/node",
        "nodeSha256": "REPLACE_WITH_NODE_SHA256_FROM_HELPER",
        "installationRoot": "/absolute/real/npm-root",
        "installationSha256": "REPLACE_WITH_INSTALLATION_SHA256_FROM_HELPER"
      }
    }
  ]
}
```

如果尚无 Companion 配置，可先使用原初始化入口：

```bash
python3 -B companion/pocket_companion.py \
  --config /absolute/owner/companion.json \
  --init \
  --workspace /absolute/workspace
```

初始化要求目标配置不存在，会创建私有 token 文件和默认配置，然后退出。默认环境仅启用明确命令且 `writeAllowed:false`；随后由主人明确编辑所需环境。现有配置不需要重新初始化。

在服务环境已经提供 `GEMINI_API_KEY`、配置已核对之后，原启动命令是：

```bash
python3 -B companion/pocket_companion.py \
  --config /absolute/owner/companion.json
```

这个命令启动 Companion 服务。Gemini 进程由后续明确的执行器探测或已审阅的新任务启动，不需要手动拼接 vendor 命令。

示例默认只监听电脑的 loopback 地址，手机不能把自己的 `127.0.0.1` 当作电脑地址。连接应沿已有的安全部署方式配置；若让 Companion 直接监听非 loopback 地址，原入口要求 `allowRemote:true`，并同时配置有效的 `tlsCert` 与 `tlsKey`，访问 token 仍然必需。POSIX 主机上的 token 文件不能被其他用户读取。

## 4. 手机上的首次工作流程

1. 在原连接页选择 Companion 连接，点击 **“读取当前工作环境”**，核对实际工作区与“允许写入”。
2. 选择 **“Gemini CLI · ACP”**，填写本次目标。模型 ID 可以留空，表示明确使用执行端默认模型；指定模型后，原会话还必须回读到该准确模型。
3. 点击 **“核对执行器与模型能力”**。探测核对固定安装、受控配置、凭据存在性和实际 `initialize` 握手；它会启动受控进程，但不创建任务会话、不发送 prompt，也不证明登录或推理成功。
4. 明确勾选 **“我明确要在此环境开展可写工作，已知 Gemini 不提供只读隔离”**，再点击 **“审阅这次新工作”**。
5. 核对连接、地址、工作区、执行器、模型、时限和完整目标，点击 **“确认创建一次”**。手机原生层捕获本次观察与主人确认；不要手工制作 `backendSelection` 或把能力摘要当成授权票据。
6. 使用 **“查询原操作”** 或打开原任务查看状态、实际成果和权限请求。断线、超时或提交结果未知时，继续核对该原操作，不重新确认同一工作。

该 worker 的请求必须显式包含 `readOnly:false`。省略该字段或传入 `true` 都会拒绝；手机的可写确认负责产生这次明确选择。首次确认授权任务启动；Gemini 随后实际发出的权限请求仍在原任务里逐项审阅。

观察缓存有效期为 5 分钟。过期、安装变化、配置变化、凭据变化或环境绑定变化都会阻止新的派发，要求重新观察和审阅。**已经启动的原任务仍可查询或取消**，不会因为缓存过期而失去这些操作。

## 5. 权限、取消与未知结果

### 原请求的一次性审批

每个可审批请求绑定原 job、operation、session、prompt 和完整权限内容。仅接受原请求实际提供的 `allow_once`，或原 `reject_once` / 取消；不支持永久批准。无法完整保存、含有需删除的秘密字段、超出大小边界或会因脱敏而改变的请求，不会变成可批准的残缺内容。

新 driver 不提供手机宿主文件系统、terminal 或其他任意回调执行权限。Peer 宣称支持某能力、发送命令目录或给出 JSON 字段，都不能增加宿主授权。固定 `default` 审批模式本身仍有本机工具能力，不能把它视为只读沙箱。

### 原操作查询与取消

原任务保留首次选择的后端、模型和环境绑定。查询、审批回执、取消回执、重启后的记录，以及归档后仍保留的未知操作，都保持这组原身份。

取消通过原任务控制发送一次 `session/cancel` notification。该通知没有停止 ACK；匹配的原 prompt 最终回执和进程清理证据分别记录。迟到结果只属于原任务，不会转给后继工作。取消不能撤销已产生的文件修改或其他效果。

连接中断、管道写入不确定、缺少原最终回执或 Companion 重启，都不会自动重发 prompt。重启会撤回已失效的现场审批，保留原记录供查询。新后端不支持恢复原会话继续执行。

## 6. 固定配置与不支持的范围

固定启动路径是已核对的 Node 和包入口，使用 ACP1、`default` 模式、禁用扩展以及受控空策略目录。显式模型值采用单个 `--model=<值>` 参数，不能变成额外启动选项。

Companion 在自己的状态目录下准备私有配置，通过 `GEMINI_CLI_HOME`、固定 system/default settings 路径和显式环境变量白名单使用它。原 `HOME` 保留；继承的 `NODE_OPTIONS`、任意 Gemini 配置覆盖或其他认证变量不会变成这个后端的启动参数。API key 只来自原受保护环境。

实际 `/etc/gemini-cli/policies` 若有 `.toml` 策略、路径不可信或无法读取，当前接入会拒绝启动，交由主人核对冲突。它不会覆盖管理员策略继续执行，也不依靠文件中的 `admin.*` 设置来声称已关闭 MCP。

本片明确不支持：

- Windows、SSH 环境或其他 Gemini 版本。
- 只读工作、团队、群组、设备、渠道、gateway 和 WorkProfile 路径。
- 模型自行选择此 worker、任意 executable / argv / 环境变量输入。
- 会话继续、fork、steering、重新附着，以及自动重试未知 prompt。
- 类型化问询回复、自动登录、账号切换、扩展、自定义 MCP 或客户端宿主工具。

这些限制属于这个固定 Gemini 接入。原有 Codex、Claude 及其他 adapter 继续使用各自原来的流程，不需要改用 Gemini 才能工作。

## 7. 如何解读探测结果

| 观察结果 | 可得出的结论及下一步 |
| --- | --- |
| 安装缺失或摘要不符 | 固定来源尚不可用；核对已有安装及 helper 输出，再进行新的观察。 |
| 凭据缺失 | Companion 环境中没有可用的预配置 API key；由主人通过既有受保护服务配置处理。 |
| 握手协议或版本不符 | 实际 peer 不符合固定 ACP1 / 0.66.0 合同；不会自动换后端。 |
| 握手已核对、凭据存在 | 具备这次探测的来源与握手证据；认证、会话模型和推理仍未验证。 |
| 来源改变或观察过期 | 原新任务选择失效；重新读取环境、探测和审阅。原任务继续按原编号查询。 |
| 状态未知或原最终回执缺失 | 只查询原 job / operation，并按原任务控制取消；不要把查询改成重新提交。 |

通用协议错误还可能来自配置或网络，不能仅凭错误文字把它归为账号登录失败。`end_turn` 只表示原 prompt 返回了结束回执，实际任务目标、文件成果和费用仍需分别核对。

## 8. 本次验证范围

截至 2026-11-04，实际已执行：

| 验证 | 实际结果 |
| --- | --- |
| 新 Gemini driver / profile / 原 manager 回归 | 最新 **32 / 32 通过**，0 跳过；包括单参数模型边界修正。 |
| 既有 Codex、Claude、Companion、remote interactions、worker executable identity | **113 / 113 通过**；在模型参数窄修前与当时 31 个新用例合跑，合计 144 / 144。 |
| 模型参数窄修后 | 复跑受影响的 32 项新 suite；没有把历史 113 项描述成又执行过一次。 |

新测试使用真实 Python 子进程的隔离 stdio peer、原 `JobManager`、原 SQLite 操作记录、原管道与进程清理，并包含 loopback HTTP 流程。测试来源校验固定实际 Python ELF 与临时 fixture 字节，用来验证接线；它不冒充真实 Node/Gemini 安装。

从 `companion/` 目录可运行当前受影响 suite：

```bash
python3 -B -m unittest -v test_gemini_acp
```

真实 Gemini CLI 0.66.0、Node/vendor 接受行为、账号认证、模型推理、费用以及 Android SDK35 原生链仍为**未运行验证**。本地 fixture 通过不能替代这些平台与账号前提；文档中的配置步骤也不代表已在用户电脑启用。
