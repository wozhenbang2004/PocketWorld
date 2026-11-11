# 通知与待处理中心 · 2026-11-10

## 本次目标与边界

本轮协调任务要求深入调查消息推送、社区成熟方案、Android 约束与当前实现，从用户/产品角度重设计并落地；保留独立 UI 重做，不写或运行测试、不做真机测试，暂不提交 main，最终由统筹者整合。授权来自本轮委派，直接用户消息 ID 未取得。初始源码基线为 `46acfec257947f243d3f6caceffc9b894ddde451`；整合候选已仅迁移本线改动至精确 main `f08dfb8c95949b32f743c7861cf50325e3c67b09`，保留主线 UsageWorkspace、复制反馈与动效。

用户要的是「现在什么需要我、来自哪里、如何处理、处理后还能回到原处」，不是展示接收器状态的技术控制台。通知中心承担发现与回到原对象；原任务、审批、团队运行和外部事件服务继续各自拥有真实状态。原任务区按对象聚合当前待处理与结果，不复制全部模型 post_notice 的正文日志；该正文仍沿原任务操作记录读取。收到、交给 Android、用户已读、归档、业务已完成五者不合并。

当前实现有三个不同问题：本机任务注意力散落在系统通知和任务页；名为通知的页面仅显示外部 HTTPS 事件；后台约 50 秒前台轮询及 JobScheduler 补收不能承诺实时推送。读取其他应用通知的 `features/notifications/NotificationInbox` 是另一项敏感能力，本轮不将其混入中心，不新增通知监听授权。

## 实际调研与选用

| 路线与实际阅读 | 机制、产品影响 | 采用决定与边界 |
| --- | --- | --- |
| [Android 通知设计](https://developer.android.com/design/ui/mobile/guides/home-screen/notifications)、[渠道](https://developer.android.com/develop/ui/views/notifications/channels)、[NotificationChannel](https://developer.android.com/reference/android/app/NotificationChannel) | 及时相关、点开正确对象；重要性受用户控制，应用总开关、单渠道和渠道组均能阻止显示。 | ADAPT 系统原生通知模板。重要/普通消息分别摘要；关闭渠道不改走其他渠道，不把 notify 返回当送达或阅读。 |
| [Android Doze/Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)、[前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)、[超时](https://developer.android.com/develop/background-work/services/fgs/timeout) | 空闲时网络和 job 会延迟；前台服务类型/系统限制不能靠文案解除。Android 15 的特定 dataSync/mediaProcessing 六小时规则不能泛化成所有 specialUse 的规则。 | 保留已显式开启的约 50 秒检测、系统补收与手动同步。拆开「已启动检测」「最近成功同步」「离线/失败」「通知权限」。不新增唤醒锁、精确闹钟或强制豁免电池策略。 |
| [ntfy 手机文档](https://docs.ntfy.sh/subscribe/phone/)、[Android v1.25.2 发布说明，51730a0](https://github.com/binwiederhier/ntfy-android/releases/tag/v1.25.2) | Play/F-Droid、自托管与 Firebase 不是相同传输；离线暂停重试、等待网络、网络切换恢复；用户反对反复弹连接失败。 | REFERENCE 离线观测与低打扰反馈，本轮增加 waiting_network 和安静的常驻状态。没有复制 ntfy 原码、引入 SDK 或使用其服务。社区故障个案不作为普遍到达率结论。 |
| [UnifiedPush 介绍](https://unifiedpush.org/developers/intro/)、[UX](https://unifiedpush.org/developers/ux/) | 手机分发器、推送服务器、应用服务器三方协作；注册、切换、注销及失败需要真实流程；推送消息可只是唤醒同步，不能等同系统通知。 | 后续传输首选候选为用户可选 UnifiedPush，保留原 HTTPS 拉取作为权威恢复链。启用需要选择分发器/端点、应用服务器注册、加密/认证及可达性，不是加一个按钮。没有部署或发送任何用户数据。 |
| [Novu headless 源文档](https://github.com/novuhq/novu/blob/next/docs/platform/inbox/headless-mode.mdx)、[3.7.0 Inbox 更新](https://www.novu.co/changelog/lessinbox-greater-updates-v370/) | 数据/状态/动作和呈现分离；seen/read/archive 不等同业务动作完成；批量操作按指定对象。 | COMBINE 其状态区分与本地权威。只读/未读和归档/移回可逆；批量只覆盖点击时筛选出的远程条目，不处理审批、不吃掉随后新消息。不引入 Novu 服务或第二套任务库。 |
| [Gotify Android README](https://github.com/gotify/android)、[消息 extras](https://gotify.net/docs/msgextras) | 自托管 WebSocket 仍受后台限制；extras 可带点击 URL/接收 Intent，富文本远图有隐私代价。Android 专页不可访问，README 和 extras 可读。 | REFERENCE/REJECT 直接执行 extras。外部正文保持纯文本，系统通知只定位原本地条目，用户再明确选择 HTTPS 来源；不自动下载远图、不执行远程 Intent。Gotify 是可部署替代，不是原任务审批权威。 |

Affect/BoardUI/React Aria 继续来自已批准审美库和仓内真实适配。实际读取 `shell-library/DecisionInbox.tsx`，上游身份为 PW-26 / MIT 源码适配；沿 `forms-library` 已有 Button/IconButton/SettingsDialog、现有 tokens 与 collection motion。没有从零造新控件、导入新的设计家族或宣称本适配已获审美验收。

## 本批正式旅程与设计合同

| ID / 检查 | 可观察行为 | 源码落点 |
| --- | --- | --- |
| NC-01 / UIX-01/02/03 | 首先看到原任务待处理/任务结果、团队等待，再看远程更新；设置按需展开，不再把接收器配置当主页。原任务不出现「全部已读」。 | NotificationTaskAttention、NotificationTeamAttention、RemoteInboxWorkspace；复用 DecisionInbox compact。 |
| NC-02 / UIX-01/08/09 | 较早待处理也可找到。原 taskSummaryPage 保留 owner/source projection，每请求扫描最多 5 个 40 行头摘要窗口，遇匹配页返回；空扫描窗口保留 continuation。没有加载 audit 全文或持久化另一个待办索引。 | TaskStore.taskNotificationSummaryPage；history.read section=attention/taskUpdates；PocketWebTaskHistory 原只读 ID 记录。 |
| NC-03 / UIX-06/08/10 | 待处理点击走 tasks.open 后原 review；中心不能批、答、恢复或重试。原任务 Drawer 返回仍是同一中心筛选/页。普通终结 failed 不成为无法清除的永久待办，而与 completed/停止记录一起出现在「任务结果」的原任务行。结果是当前原对象摘要，不伪装成每次通知的完整历史。 | App 窄接线、原任务权威与既有 review。 |
| NC-04 / UIX-01/08/10 | 团队问题按原 run/member/assignment/question/generation 定位；跨页只带导航 locator，重新读取原 run 再处理。全历史分页不依赖父任务状态。团队候选来自协调器保留快照，不冒称实时 TaskStore 查询；同 run 内超过 40 项用 afterItemId 继续，不跳过其他工作项。返回通知保留筛选、页游标和原成员问题锚点，再读当前状态。一次性 focus 成功/明确失败后按 requestId 消费，离开 teams 也退休，普通再次进团队不重放旧定位。 | teams.attention / teams.readAttention 由团队线实现；中心不复制团队业务。 |
| NC-05 / UIX-08/10 | 单条系统通知定位 configId+seq+source_key 的原消息，归档条目仍可定位但不取消归档、不标已读。旧连接、淘汰条目和伪造 locator 明确提示，不打开替代 URL。 | RemoteInboxNotifications、RemoteInboxStore、ComposeActivity。 |
| NC-06 / UIX-06/08 | 远程条目可标未读、归档、移回；「本筛选全部已读」只提交当前快照的原 ID。身份任一失效则整批不写。手动已读/归档后再撤销不会重放系统提醒；这用独立 alertSuppressed 表达，不伪造投递记录。 | 原 AtomicFile journal；不改变源 Issue/PR/任务状态。 |
| NC-07 / UIX-08/09 | 配置成功不等于联网；检测进程运行不等于同步成功；OS 接受不等于送达。显示实际后台 job 登记、最近成功同步和当前离线/失败；后台登记失败可重试，权限入口能到系统渠道设置。 | 同步观测持久化 lastSync；常驻通知不反复报警；渠道受用户总开关、渠道和组控制。 |
| NC-08 / UIX-04/05/07/10 | 沿既有文字层级、表面/间距 tokens、44px 触控与减少动态；有焦点样式，定位原条目，保留集合动效和设置 Back。 | 专属 CSS 小增量，无共享颜色/控件重做。当前视觉和触感未验证。 |

现有远程事件留存仍为最近 300 条/512 KiB；source_key 去重仅在该保留窗内。归档不是长期备份。数字 seq 收紧为正安全整数，避免 JS 精度把两个原条目混成一个。连接身份/端点/凭据在同一锁内捕获，禁止切换服务时把新 token 发往旧地址。批量写、同步记录和投递标记均在原 journal，连接替换不会污染新连接。

## 集成与证据

已实现源码在正式模块；专属通知/中心文件与 App、Secondary、Controller、Bridge、ComposeActivity、TaskStore 的共享接线拆成补丁，由最终整合者基于最新 main 合并。团队 native/UI 接口依赖团队线补丁，不能单独交付一个不具备 teams.attention 的 APK。交叉审阅已纠正「只看最近任务」「失败永久待办」「旧任务 overview 不读取 review」三个实际问题。

本轮未写、未运行任何测试，未做真机、模拟器、伪造宿主截图或 UI 体验。当前副本未准备完整 Android SDK/前端依赖与项目构建环境，尚未完成编译；源码检查不等于构建通过或视觉验收。最终集成需沿仓内不带测试的 TypeScript/Android 构建流程产出正式包，分别记录构建、用户体验和审美反馈。

仍需产品决定/真实环境才可闭合的能力：选定并部署推送提供者、手机分发器注册、不同 Android/厂商后台到达表现及跨设备同步已读。上述不是本批本地中心/拉取实现的承诺；未把「轮询更清楚」包装成可靠推送已上线。若要求硬实时，必须先选传输与服务端部署，不能通过本地通知管理器或关闭电池保护来作保证。

### 当前到达与恢复链的准确边界

- 前台服务保持运行且网络可用时，约 50 秒固定延后再拉取；离线时只保留等待网络状态，后续轮询恢复，不是网络回来即唤醒。服务为 START_NOT_STICKY，进程消失后没有即时重启承诺。
- 已登记的持久 JobScheduler 任务请求最小周期及网络约束，由 Android 选择实际运行时机。Doze 会延后网络与 job，强行停止/厂商禁后台可能使其无法执行；重启持久登记不等于准时运行。页面可核对真实登记并重登记，也可手动同步。
- 拉取成功后按原 cursor 原子保存并补收；通知投递前崩溃可由 pendingAlerts 再尝试，稳定身份避免变成另一条通知。Android 接受调用仍不是屏幕展示或人已阅读的证明。长时间离线的恢复还受服务端保留范围和本机 300 条保留窗限制。
- 本轮没有 FCM/UnifiedPush 的系统唤醒路径，不承诺杀进程、Doze、禁后台下的实时送达。保留现有自托管 HTTPS 连接无需新增第三方推送账号，但这项优势不能换算成可靠性保证。若要求低延迟唤醒，需明确选择服务端部署和分发器/FCM，授权必要端点与数据流，再接真实注册/注销/恢复链。

## 可靠唤醒增量（本节替代上述基线的“尚无推送源码”状态）

2026-11-10 UTC 本轮协调新增要求：继续尽可能实现 Android 唤醒/推送链，比较 FCM/UnifiedPush 的账号和部署成本；允许只写默认关闭的官方 SDK 声明及源码，不安装、不运行、不注册、不部署第三方服务。本段由该委派转述，未取得直接用户消息 ID。增量以五线整合候选的通知文件为底，不覆盖此前 UI、任务或团队改动。

### 研究收敛与仍开放的分支

- FCM：读取 [Android 起步](https://firebase.google.com/docs/cloud-messaging/android/get-started) 与 [消息优先级](https://firebase.google.com/docs/cloud-messaging/android-message-priority)。需要 Firebase 项目、发送身份与 Google Play 服务环境。只发送静默高优先级数据不是可靠捷径，系统观察不到可见通知时可能降级；回调生命周期也不能覆盖任意后台拉取。暂不引入账号、Firebase 配置或 Analytics。若未来面向已有 GMS 的大众发行版，仍值得作为另一个明确可选通道，不因选择 UnifiedPush 宣称 FCM 无价值。
- UnifiedPush：读取 [AND_3.1.0](https://unifiedpush.org/developers/spec/android/)、当前 [connector API](https://unifiedpush.org/kdoc/connector/)、[Maven 3.3.5 元数据](https://central.sonatype.com/artifact/org.unifiedpush.android/connector)；实际读 GitHub 官方镜像 3.0.10 的注册、密钥、Receiver 和 Service 转发源码。镜像不是最新 3.3.5 原码，Codeberg 3.3.5 原码访问失败，这个差距保留。当前官方 API 与包元数据核对到 3.3.5 / Apache-2.0；不自写广播协议或 WebPush 解密。
- 接收器仍可能离线。实际读取 [ntfy #1834](https://github.com/binwiederhier/ntfy/issues/1834) 的问题描述和部分日志，报告屏幕唤醒后积压消息才到达。这是具体设备/发行环境个案，不是全体到达率统计；帖内 LLM 关于闹钟的建议未采纳。它直接支持“保留原 HTTPS 日志补收与真实回执”，而非只显示注册成功。
- Worker 加密发送采用 `@block65/webcrypto-web-push` **2.0.0**（MIT）。实际读 [固定 v2.0.0](https://github.com/block65/webcrypto-web-push/tree/v2.0.0) 的 package、README、payload/types 实现和 Cloudflare 示例入口；v2 才是 RFC 8291 aes128gcm / RFC 8292 VAPID，不能误用旧 1.x draft 编码。相比 Node web-push 的 Node crypto/https 适配，本项目直接复用 WebCrypto 版本；相比手写 RFC 或 vendoring Tink，只添加有界业务接线。未安装或执行上游库，作者运行结论不冒充本项目实测。
- [Android JobInfo.Builder](https://developer.android.com/reference/android/app/job/JobInfo.Builder) 已核对：API 31 起可申请 expedited，允许网络/持久约束，但受配额、系统负载及网络影响。本增量用真实 JobService 处理拉取，不把 SDK 短回调中启动一个裸线程当后台生命期保障。没有精确闹钟、强制电池豁免或设备权限调整。

### 实现合同与证据位置

| ID | 已写的正式源码链 | 不被该证据证明的部分 |
| --- | --- | --- |
| NCP-01 | 标准 APK 不解析 SDK；`pocketworldUnifiedPush=true` 才加入官方 connector 3.3.5 与独立 source set，compileSdk 36。标准构建只有禁用的非导出占位 Service。 | 可选构建依赖锁、API 36/AGP/Kotlin 兼容与 APK 构建尚未验证，不是已经可发布的推送 APK。 |
| NCP-02 | 已有设置 Dialog/Button/TextField 中明确选择已安装接收器、输入专用登记 Token、同意向当前服务共享端点和加密参数；默认未启用。SDK 不弹另一套自制 picker，不自动安装或登录。 | 未实际看当前界面、运行交互或取得用户审美反馈。 |
| NCP-03 | SDK 解密成功、instance 与当前配置一致才接受 `push_challenge` / `inbox_wake`。回调原子保存，再请求 Android expedited Job，配额不足回落普通持久 Job。只拉原 HTTPS 事件，不执行任务或打开 URL。 | distributor 未投递、force-stop、厂商禁后台、网络不可用仍无硬实时保证。 |
| NCP-04 | Worker 单独入口、独立登记 Token，不扩大 INBOX_TOKEN。只向运营者精确 host 白名单发送，禁止跳转；不传正文。随机 challenge 只经加密推送下发，HTTPS 登记响应不泄露它；设备证明收到后才激活。 | 服务商接受 HTTP 请求不等于手机已接收，手机接收不等于已补收、OS 显示或用户已读。 |
| NCP-05 | 原服务端事件表和原本地 cursor/journal 仍是权威。最多 16 台登记、每批 8 台、4 并发、退避重试；只有本机实际拉取 cursor 的 ack 让服务端停止该批唤醒。重复 hint 不新增事件；过期 endpoint 404/410 移除并由客户端重新登记。 | 不是跨设备已读同步；本地最多 300 条和服务端实际保留范围仍适用。 |
| NCP-06 | 本机 endpoint、登记 Token、设备 secret 与 keyset 用既有 AndroidKeyStore 加密配置保存，不进前端快照/日志；SDK 私钥由 SDK 管理，现有备份规则排除 sharedpref。停用先退休本机 identity 再退订/远端注销；换服务不能把新 Token 发到旧域。 | 离线注销无法确认服务端立即删除；记录 30 天租约到期清理，运营者可直接撤销。D1 中订阅字段是敏感 capability，应限制管理访问与导出。 |
| NCP-07 | 不启用 SDK 也获得实际恢复改进：保存配置、应用回前台及离线同步后登记一次性 network Job；中断/分页未完成可重排，周期 Job 独立兜底。单次拉取有页数和墙钟预算。 | 这是恢复链，不是普通 Job 可以在任意 Doze/杀进程情况下实时唤醒的承诺。 |

源码：`RemoteInboxPush`、独立 `UnifiedInboxPush` / `RemoteInboxPushService`、原 Store/Sync/JobService、`PocketWebRemoteInbox`、原通知设置；Worker `src/push.mjs` / `worker-push.mjs` 和可选 migration。部署与撤销步骤见 `integrations/issue-radar/README.md` 的可选推送节。

当前仅执行 `node --check` 的语法读取，不执行模块、模拟请求、测试、依赖安装、构建、设备或部署。默认路径与可选路径分别交叉审查。仍未闭合：完整工具链构建及依赖锁、操作者部署/凭据、真实 distributor 注册和实际用户环境到达/返回体验。研究与源码是增量成果，不能把这些缺口写成已完成的可靠送达。

传输覆盖边界：本增量只为已有 HTTPS 远程事件收件箱提供唤醒，未把本机 TaskStore、团队正文或设备执行状态上传给推送后端。本机任务完成仍由既有运行进程发布本机通知；进程已终止不意味着任务能在云端继续执行。


### 无新服务的本机提醒恢复增量

2026-11-10 UTC 本轮协调继续要求寻找无需新后台配置的真实缺口。已确认源码断点：原 `RemoteInboxSync` 在全部网络分页成功后才发布 `pendingAlerts`。因此，已落盘的事件在离线、接收 Token 失效或后续页失败时仍被卡住；系统通知权限恢复后，network Job 也不一定能执行。它不是缺少新的推送提供者，而是把本机展示错误地依赖了网络成功。

NCP-08：将原发布逻辑集中为不访问网络的 `RemoteInboxNotifications.publishPending`，回前台与通知权限获准后通过短时本机工作恢复；同步 finally 也发布已原子落盘的部分。保留同一 configId 锁、系统总开关/渠道/组、已读/归档 suppression 和原条目身份。只将 Android 接受的条目批量记为 alerted，不改 cursor、已读、归档、任务处置或同步成功记录。批量落盘减少每个提醒各写一次完整 journal；稳定 ID 加 onlyAlertOnce 限制崩溃重试重复响铃。

这项恢复不需要 UnifiedPush、FCM、第三方账号、服务端变更或新权限。它仅恢复本机现有保留窗内的未发布提醒，不是进程死亡时自主唤醒，也不证明 Android 实际展示/用户已读。未写或运行测试，未安装依赖、构建、真机或部署；源码与补丁检查不能代替当前环境体验。


NCP-09：另确认“条数有界但字节无界”的真实补收阻塞。单是 300 条各 600 个常用汉字的合法正文就约 540,000 UTF-8 字节，超过原 journal 的 512 KiB 硬限，尚未计标题、来源、链接与 JSON；原 count-only 截断会使 ingest 反复失败、cursor 不前进。现按原序列保留最新记录，同时满足最多 300 条与 448 KiB 内容窗口，为后续状态写留 64 KiB。原 512 KiB 文件配额不提高，cursor 仍只随同一 atomic journal 成功提交，已读/归档和去重继续使用这一个保留窗。该结论来自字段上限与真实序列化路径的源码检查，未构造/执行合成测试。

### 2026-11-11 · 可选链故障隔离与登记恢复

本轮继续委派要求为“补齐通知中心可靠送达”，保持不安装依赖、不写/跑测试、不做真机、不注册/部署/订阅/传递用户数据的边界；未取得直接用户消息 ID。读取 GitHub `main` 固定基线 `50ceebc077517e937de5007c4302d9172bf132be` 后定位以下真实源码断点，不以旧本地候选代替主干：

- **NCP-10，推送不可拖垮事件来源。** 原 `worker-push.scheduled` 先 await `flushPush`，可选表缺失或 D1 推送查询异常会在 GitHub 扫描之前抛出。现将发送放入独立 `waitUntil` 并隔离运行时错误；扫描前可恢复旧积压，扫描后可发送新事件。日志仅保留固定错误提示，不输出 endpoint、secret、payload 或异常对象。读取 [Cloudflare Context/waitUntil](https://developers.cloudflare.com/workers/runtime-apis/context/) 的生命周期约定；未运行 Worker。模块导入/打包失败仍需操作者处理，这不是所有启动故障的 fallback。
- **NCP-11，凭据修复要求不能被传输状态抹掉。** 原客户端用同一个 `status` 表示登记 Token 失效和唤醒/补收；收到旧有效登记的 hint 或成功回执可能覆盖 `credentials_required`。现用本机加密状态中的独立标记保留该要求，并兼容已有失效状态；继续展示原 UI 的凭据修复文案。device secret 与登记 Token 权限独立，旧登记仍有效时允许补收回执，不假装已修好登记 Token；登记缺失且凭据失效时不持续紧密重试。
- **NCP-12，旧登记证明不沿用。** `/ack` 返回 401/410 时退休旧 challenge，并记录需恢复登记；维护时向同一已选 distributor 重新请求端点，后续仍需真实加密 challenge。不会选择新接收器、改变服务地址或自动创建账号。这个实现不能证明 distributor 会立即更换失效端点，真实返回仍待环境确认。

核对 [Android JobInfo.Builder#setExpedited](https://developer.android.com/reference/android/app/job/JobInfo.Builder#setExpedited(boolean))：官方允许 network、storage-not-low、persistence 约束，因此没有误删现有 persisted + expedited 组合。系统配额、Doze、force-stop 与厂商限制仍适用。

本增量只做代码阅读、`node --check` 静态语法和 diff 空白核对，未导入/执行发送模块、安装依赖、解析新锁、编译 APK、构建 Worker、运行测试、配置第三方或操作设备。默认入口和默认 APK 没有启用推送。NCP-10 至 NCP-12 是候选源码补齐，不是已部署/已验证送达；NCP-01 的工具链、操作者服务及分发器配置和真实用户到达体验仍开放。
