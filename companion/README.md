# PocketWorld Companion：固定 Gemini ACP 接入

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
