# 模型请求生命周期与维护边界

本文记录本轮模型/账号主链重构后的职责与不可破坏的调用顺序。适用于当前统一代码；它是维护说明，不是运行验证报告，也不代表项目六阶段已经全部完成。

## 当前工作约束

当前阶段以生产代码重构和静态审查为主，不新增或运行测试，不编译、打包或触发 CI。代码整合后，再用实际生产代码与获准凭证验证真实功能链路；界面操作验证属于后续阶段。不能把静态审查通过写成登录、流式输出或网络恢复已经实测成功。

## 谁拥有哪部分状态

| 职责 | 唯一原有权威或局部状态拥有者 | 不拥有的权力 |
|---|---|---|
| 逻辑请求准入、尝试预留、语义提交与结算 | 原 `LogicalInferenceAuthority` | 不由传输回调、JSON 字段或 UI 自行授予 |
| 候选准备及排除证据 | `ModelGateway.prepareCandidates` / 私有 `CandidatePreparation` | 准备结果不是可复用权限缓存 |
| 原生回执的期限、尝试上限与资源范围解析 | `LogicalAdmissionBounds` | 不能创建准入或放宽原上限 |
| 本进程并发槽及等待队列 | `ModelCallAdmission` | 不是任务、授权或用量账本 |
| 一次 physical 尝试的提交状态及成功完成序列 | 私有 `LogicalAttemptSession` | 不是第二个重试循环 |
| 已批准重试的路由位置、原因及累计计划等待 | `LogicalRetryProgress` | 不能自行批准重试 |
| physical 用量与返回上下文证据 | `InferenceCapture` | 不证明远端回滚或未计费 |
| 账号版本连续性 | `AccountExecutionBinding` | 版本成员资格不等于当前授权 |
| 单次账号请求回调与完成事件 | 私有 `AccountRequestSession` | 不能换账号、重试或派发工具 |
| 原生拒绝跨账号 checked exception 的恢复 | `AccountAdmissionRefusals` | 不能覆盖较新的取消或 currentness 结果 |
| 同一次 API 准备的 adapter、endpoint、projection、payload | 私有 `PreparedApiRequest` | 不能跳过发送前准入或返回后的能力复核 |
| HTTP 错误体与 header 的有界解释 | `HttpFailureEvidence` | 不决定重试、路由切换或取消 |
| buffered HTTP 取消注册与 watchdog 生命周期 | `BufferedTransportLifetime` | 不负责响应协议或异常分类 |
| 有序展示与敏感信息遮盖 | 私有 `StreamSession` | 不授予工具执行权限 |

这里的局部 owner 不替代原生任务权威。只在 invocation 内有状态的对象，不应被保存到全局注册表、TaskStore 或跨请求复用。

## 请求主链

1. 从原执行上下文取得 purpose 和原逻辑权威，准备原候选计划。
2. 按原候选顺序检查用途、受众、不透明历史来源、模型组与 preflight。排除证据保持原 row index；所有候选共用原期限。
3. 原逻辑权威 `begin` 后，解析其回执。deadline 与 attempts 只能取更严格值。资源 scope 一旦出现就必须完整、类型正确、与 budget owner 一致，不能把 malformed scope 当作缺省。
4. 取得原任务的 physical 并发许可；再次执行实时 group/preflight。候选准备没有冻结权限。
5. 建立原 `InferenceCapture`、逻辑关联和尝试预留，再进入单次传输。
6. 每次内部展示 flush 都检查 request、group 和 call identity。第一次有效提交才写原逻辑权威，成功后才标记 committed；后续 flush 仍检查实时状态。
7. 成功返回依次经过：`returnedHistory`、physical COMPLETED 回执、`afterPersist` 展示终态、request 检查、logical attemptEnded、logical SUCCEEDED、来源 metadata。
8. 任一步异常仍进入原循环的失败路径。诊断、permit 释放、physical 失败回执、取消/部分输出/未知完成分类与重试判断，不交给局部 helper 自行处理。

不要把第 7 步压缩成“HTTP 成功即成功”。响应状态、provider 完整终态、工具参数有效性、回执持久化与展示完成是不同事实。

## 失败、未知与重试

- 本地取消并不能证明远端没有处理请求。已派发但没有明确远端失败证据时，应保留未知完成事实，同时保留 CANCELLED 状态。
- semantic committed、opaque history、账号模式、Bedrock 等限制仍由原循环判断。重试进度对象只记录通过判断后的状态。
- 原 `reserveRetry` 必须先通过，再变更重试进度。已分类的 HTTP 429 不切换原 route；其他获准失败也只能在原候选列表与切换上限内前进一步。
- 等待累计在 `beforeWait` 成功后、sleep 之前增加。这是计划等待，不是实际耗时测量；等待或队列都不得重启原 deadline。
- 某个 physical receipt 失败，不等于已发生的模型用量消失，也不授予重放工具权限。

## 账号专属边界

`AccountExecutionBinding` 只维护同一模型的版本连续性。调用方仍需要当前 host 和授权检查；返回的版本集合是快照，不能暴露可变集合。

并行续期中，某次已知 expected 的结果可能落后于共享 current。它仍可归属于该 invocation，但不能令 current 倒退。不要把这个兼容行为改成“actual 必须大于所有并发请求的 current”。绑定对象不回调 gateway，也不反向取得 binding-map 监视器。

`AccountAdmissionRefusals` 保留两条不同规则：每类别原子地保留第一个异常；恢复时按 source、request policy、context budget、route、request refresh 的固定优先级。不能替换成“全局第一个异常”，也不能重新构造异常丢失身份。原 request/cancellation 检查在恢复这些异常之前。

账号历史翻译分为工具声明、单条历史映射和最后的 `ResponsesRequest` 校验。`provider_data` 的原生 items 经过来源检查后保序复制，不能再重复拼接可见 message/tool calls。namespace 声明不授予工具执行权；图片仍同时受截图设置与原生账号能力约束。

## 传输与展示生命周期

- 取消注册之后的任何可失败初始化，必须有明确清理路径。buffered 和 streaming 的共享期限计算都在注册之前完成。
- watchdog 接收已计算的绝对 monotonic deadline，不能在分配对象或启动线程后重新用相对剩余时间开始计时。
- 原生 `ProtocolGateway` 在准入前用同一次剩余预算计算 monotonic deadline，并保留原 relative call timeout。OkHttp 的 relative 计时到 execute 才开始，不能让中间的审批等待或 claim 查询顺延网络期限；实际调用使用两者中更早的期限。这不保证中止所有准入等待，也不代表异步取消能消除所有发送竞态。
- buffered 正常关闭顺序保持 finished、移除 callback、interrupt watchdog、disconnect。不要无意把显式 finally 改成不同异常抑制语义。
- HTTP 错误体最多接受 16 KiB 且必须是有效 UTF-8。超限或读取失败不根据截断正文猜测错误。含原凭证的 header 仍整组变为 invalid sentinel，不能只删一项后制造“有效重试提示”。
- `COMPLETED` 的 commit gate 成功之后才能关闭展示通道；否则 gate 拒绝会同时压掉后续失败通知。关闭仍在 sink 调用前，防止 sink 自身失败后重复发送终态。
- 缓冲 JSON 的播放不属于 provider token 到达时间。JSON fallback 可以展示文本、验证终态与用量，但不能为对齐 SSE 机械补 `observedVisibleText`，从而伪造首 token/流速证据。

## 原生协议转发不是内部 UI 流

`ProtocolGateway` 的首次 semantic commit 不能单独代表逐帧权限检查；现有生产 sink 另有职责：

- Loopback 在每帧发送前检查原选择、路由绑定、selection revision 与传输同意。
- Harness 的派发准入在原远端 claim 查询并校验后，再复核本地任务、租约与路由；远端 claim 仍活着不等于本地执行权威仍有效。这是发送边界上的当前性检查，不是跨远端与本机的原子租约。
- Harness 在每个 bounded fragment 发送前及正常 frames ACK 返回后检查原任务/run/epoch、期限、租约和当前路由。失败后的原片段状态查询返回后也必须复核 current，再接受同一片段的 acceptedThrough；尤其 EOF 没有下一片来补检查。查询仅核对原片段，不重新推理，也不否认远端已经接收的事实。
- `RelayRouteBinding.samePlan` 比较 group revision、用途/受众、候选顺序、ID、排除信息和配置 digest，不只是模型名字。

新增入口必须检查它的实际 sink 合同，不能只复制一个“首次提交”回调。反过来也不能在没有核查宿主语义时，把 external commit 调用频率擅自改为逐帧。

## 后续真实验证清单（当前不执行）

优先验证实际生产路径：账号首次登录与续期后的连续对话；API/账号完整响应及工具链；取消、模型/权限撤销发生在首帧前和终态前；两种传输的 deadline 与 callback 清理；原生转发在 route/lease 变化后的停止；计量与回执中“已派发、部分、未知、取消”的区别。

验证应经过现有生产入口，明确凭证与服务授权，记录当前 commit、实际失败证据及修复后结果。不要用另写的伪请求替代 APP 本身的请求构造，也不要将本轮未运行的验证写成已通过。
