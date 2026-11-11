# 团队协作：一次目标的共同完成

## 2026-11-12：管家唯一接单入口与团队执行闭环

本轮用户原话：“UIUX好好做做啊，很恶心啊现在。而且我们的管家也没有具备这个跟团队的一个链路打通啊。既然管家是我们的唯一入口反正这个做的很不合理啊我觉得。UI UX做的很丑，很恶心，很乱好吗”。来源为本对话对 v0.1.119 的后续纠正，日期按 Asia/Shanghai，精确消息 ID 未提供。这是对上一版产品结构与体验的否决，不能因为上一版构建发布成功而关闭。

当前起点 `04ed87cd804b6d6d8d6fc7449531f391f85ef887`。以下决定替代前轮把“发起协作”作为独立接单入口的 TC-01/02 设计；原运行、权限、问题、资料与未知回执规则继续保留。

### 当前已确认的断点

- 手动页面可准备一次性模型分工，管家 `teams_start` 却只能使用已有编组或制作计划。空编组用户无法从管家目标走到同一临时团队执行。
- 能力入口直接跳团队表单；协作页与工作详情没有准确的双向关联，用户必须离开管家巡视成员和找结果。
- 已有 `owner_work` 结果冻结、outbox、唤醒与回原会话链实际存在，本轮复用。待完成子协作却被前端统一描述为“结果已到，等待管家整理”，中途问题与授权没有成为原工作中可理解的状态。
- 最终回流还有一处原生断点：发起团队留下的操作回执使父工作通常停在 `needs_confirmation`，已有接手只接受 `completed/failed`，因此团队完成不等于管家会自动整理。当前修正以原生只读协作事实决定整理资格，保留主人对原工作的最终核对。

### 本轮范围与实现合同

| ID | 采用决定 | 可观察结果 |
| --- | --- | --- |
| TC-08 唯一入口 | 用户向管家交代目标，前台先创建原 `owner_work`，临时模型团队由该工作调用原团队工具组织；编组目录只负责复用设置。 | 不先要求用户到团队页填写角色和轮数；无编组也能由管家发起模型分工。 |
| TC-09 同一工作 | 原 TaskStore 只读投影经过父子双向核对的团队关联；原工作可进入准确协作，协作可回原工作。 | 不靠名字或目标匹配；旧代次只能查看，不能把补充送给新一次执行。 |
| TC-10 真实进展 | 等团队、等问题/授权、等结果整理分别呈现，沿原 owner_work 接回结果。 | 有子任务问题时能从管家工作进入处理；执行未结束不提前显示结果已到。 |
| TC-11 视觉重组 | 团队详情收敛为进展、成果、详情；进展优先当前阶段/待处理，成员与任务紧凑成行，产出逐项展开；记录、资料、预算与标识按需展开。 | 首屏有一个明确焦点；没有重复成员总览、大块空成果卡与并列操作墙。 |
| TC-12 连续沟通 | 普通补充回原工作；精确问题仍走原问题答复；从辅助目录返回管家保留原草稿，导航不发送消息。 | 用户知道补充给哪件事，前台接待与后台协作保持同一责任关系。 |

沿已有组件清单采用 BoardUI、Affect AgentChat/AgentRun/AssistantHome、既有 Tabs/Accordion/Button/AIAnswer/identity/motion；不新增控件家族。已实际查看仓库 `design/sidebar-approved-20261110.jpg` 及审美库保存的 `affect-screen-agent-chat.jpg`、`affect-screen-agent-run.jpg`，读取对应 `shell-library/upstream/affect/src/demos/screens/workspace.tsx`、`OwnerWorkFacts` 和 `ButlerHome`。参考按各自保存的来源身份使用，不把示例计时、数据或旧图当本产品当前运行。

UIX-01/02/03：以同一工作为主对象，辅助团队目录降级，手机单列画布、当前问题就近处理。UIX-04/05：继承当前字体和中性表面/低饱和强调色，标题、工作行、辅助信息三层；普通行无装饰边框，使用 12/16/24px 间距表达关系。UIX-06：采用上述已有组件及来源。UIX-07：稳定对象局部展开，不给整页统一上下滑入。UIX-08：保留原草稿、离页保护、旧代次与未知结果。UIX-09：以管家、工作、协作进展、成果表达实际责任。UIX-10：320px起换行、44px操作命中、按钮名称与原焦点/返回机制；设备效果需真实产物确认。

本轮不新增第二执行器/结果队列，不改设备/文件授权，不把模型分工扩成任意执行器。禁止编写和运行测试、fixture、模拟验收及本地 APK 构建；采用必要源码/类型/生产构建与现有无测试 CI。

### 本轮实现与检查点

北京时间 2026-11-12 07:11 起的集成检查点：正式 Web 构建完成，随后完成原生只读结果整理与交叉源码审阅。`npm run typecheck` 与 `npm run build` 均退出 0，后者执行字体/许可准备、TypeScript、Vite 生产打包和 224 个本地资源的清单/哈希核对。最终界面修正后再次完成正式打包；后续只有 TaskStore 与本设计记录变动。既有大包提示及 `client-only` 许可正文缺项保留，本轮没有引入依赖或降低完整性检查。

交叉源码审阅修正了协作行与 BoardUI 按钮内容 wrapper 的布局对应、带目标回管家时实际写入回执的确认，以及父结果展示摘要截短后影响自动整理的问题；长正文从原 checkpoint 回读并验证原生最终回答记录。审阅结束没有剩余已证实的源码阻断。`git diff --check` 退出 0；未编写或运行测试。原生编译及签名产物仍由对应 main 提交的既有无测试 CI 负责，不把源码审阅写成设备体验通过。

| 当前要求 | 正式实现 | 已取得证据与剩余边界 |
| --- | --- | --- |
| TC-08 | `NativeInstructionProfile`、`HostToolSupervisor`、`TeamParentTools`、`TeamDraftDefinition`、`FeatureHost`、`TeamsModule`，以及 App 的团队能力入口 | 前台先 `owner_work_start`；原独立工作可用 `goal + members` 安排 1–4 名模型成员。原模型、准备操作、准确审批、父任务预算与取消链继续生效；没有新执行器或永久编组写入。原生编译由 main 对应 CI 完成。 |
| TC-09 | `TaskStore.ownerWorkLinkForTeam/projectOwnerWorkTeams`、`PocketWebTeams`、`OwnerWorkTeams`、App/Secondary 导航 | 原父子双向关系核对后才投影；工作打开准确团队，团队回准确工作。旧执行只查看、不自动补充；来源不可读显示缺口。未在真实设备执行跨页旅程。 |
| TC-10 | `TaskStore` 原结果接手链、`owner-work-presentation`、`work-collection`、`conversation-work`、`ConversationWork`、`ButlerHome` | 等待成员、问题/授权、管家结果处理分别显示；父工作结束但子协作未结算时仍保留进行中。阶段返回人数缺失时不补成 0/0。晚到旧结果保留原回执，不改当前 run 的 phase 与 pending 提示。当前临时模型团队在原生完成事实齐全后，可自动接回只读整理，原工作仍待主人核对。 |
| TC-11 | `TeamsWorkspace`、`team-workbench.css`、`OwnerWorkFacts`、`owner-work.css` | 进展/成果/详情三层结构、单内容画布、32px 成员身份、18/20px 标题、15px 正文、12/13px 辅助说明、44px 操作范围已在正式组件实现。当前 Android 排版与动效未实看，不标视觉通过。 |
| TC-12 | `team-navigation`、App、`PocketWebBridge`、原持久草稿与补充入口 | 普通新要求回原工作，精确问题按原问题回答；带目标回管家只预填、不发送，保留原输入且用原 key/epoch/revision 防止覆盖。未知启动与消息草稿继续原记录核对，不变成新一次提交。 |

模型临时分工复用原 `TeamResearchHost` 的显选资料范围；目标和分工不自动授予父管家的全会话、私域记忆、浏览器或任意网页权限。没有显选证据时仍须说明证据缺口，不把多角色分析描述为自动完成联网研究。

自动整理只作用于本次 `goal + members` 产生的临时模型团队：原工作、run/epoch、启动操作、已消费审批、成员定义与父范围必须准确对应；团队及研究成员的原生执行都已完成，且没有未处理问题、待批操作、未知回执、设备/文件写入或进一步执行子任务。整理仍通过原 outbox、管家空闲接手及一次预留额度，工具收敛到原授权内的结果/证据读取，并在每次推理或工具调用前重核同一事实。该条件不自动接受业务目标完成，不恢复旧团队或增加执行额度；条件不成立时仍显示原待处理状态。

UIX-01/02/03/06/08/09 已取得当前源码结构与构建证据；UIX-04/05/07/10 的真实窄屏排版、触摸、焦点、读屏、键盘、动效及前后台恢复仍未验证。当前环境没有 Android 设备/模拟器，没有用 fixture 或自制静态页面冒充真实运行。正式 APK 和发布状态以本轮 main 对应的 CI/Release 为准。

## 2026-11-11 历史轮次与 AionUI 研究

下文保留前轮研究、实现及当时的验证范围。独立发起表单、四个等权运行页面和团队内普通新消息入口已被上面的 TC-08–TC-12 替代；其余来源、精确问题、资料授权和未知回执要求继续适用。

## 目标与依据

用户本轮原话：“对我们抛keyboard这个项目的团队协作那一部分进行深度的优化”“深度的重构设计”“没有符合我们的产品语义”“好好地研究一下人家AION UI这个项目的团队协作”“无论是功能还是这个产品逻辑，还是用户体验，还是UI UX”。当前聊天，记录日期为客户端北京时间 2026-11-11，精确消息 ID 与发送时刻不可取得。

“抛keyboard”的项目名根据既有上下文、仓库和模块定位为 PocketWorld，不改写用户原话；不涉及独立的 Deep Research 项目。起点为 main `9c193fe845bc963c1389eab60b32330fb280d029`。沿用用户此前直接修改 main 的授权及禁写、禁跑测试要求；保留其他会话的管家、手机、项目和模型改动。

交付前主线推进到 `16d37dfe2abd22d9b25b0ec3ac6a4841024bd033`（模型草稿、项目历史与工作操作），本轮已接续该提交；双方设计记录保留，原生草稿字段无损合并，不覆盖并行工作。

本轮的主对象是 **一次协作任务**。管家及原对话保留任务来源；团队是完成目标的执行方式；编组是可以再次使用的成员配置。它们分别承载用户意图、实际执行与可复用设置，不共用一个配置页面冒充全部产品。

## 研究与采用边界

- AionUI 固定源码 `iOfficeAI/AionUi@e108b6b56b1a6eb99a00904878d0582f657b2908`，读取 team 页面、成员聊天、协作看板、启动/恢复与消息交互；后端实际机制沿 `iOfficeAI/AionCore@503a87199eb978790ca77675b39c84b241535bf3` 的运行管理、工作调度、邮箱、任务板及角色提示核对。没有运行上游产品；官方演示仅看到首页帧，不能据此宣称当前完整交互已实测。
- 参考入口：[官方项目](https://github.com/iOfficeAI/AionUi)、[团队页面](https://github.com/iOfficeAI/AionUi/blob/e108b6b56b1a6eb99a00904878d0582f657b2908/packages/desktop/src/renderer/pages/team/TeamPage.tsx)。采用其目标与成员实例分离、默认向负责人沟通、共享任务可追踪、成员独立阻塞的产品关系。没有复制上游业务代码。
- 上游文档对 warmup、动态成员和消息接口存在版本差异，以固定源码为依据。AionUI 的并行桌面列不直接搬到手机；协作总览使用紧凑单列，成员与细节逐层展开。
- 上游 [#4374](https://github.com/iOfficeAI/AionUi/issues/4374)、[#4357](https://github.com/iOfficeAI/AionUi/issues/4357)、[#4358](https://github.com/iOfficeAI/AionUi/issues/4358) 为问题报告，不能当作本机复现：它们提示要重点核对停止反馈、在途消息和可见授权入口。[#3574](https://github.com/iOfficeAI/AionUi/issues/3574) 的临时团队/原对话回流属于提案，不能声称是已发布能力。
- 当前 PocketWorld 的 `leaderId` 在普通模式中承担最终汇总模型选择，不等于已有会自主增删成员、分派任务的 Leader。界面如实表达“负责汇总”；父管家的 `teams_plan/start/get/message/wait/control` 和 TaskStore/Runner 仍是实际调度链。

### 本次真正影响设计的机制

| 成熟参照 | 直接读到的机制 | PocketWorld 采用及保留边界 |
| --- | --- | --- |
| [TeamRunManager](https://github.com/iOfficeAI/AionCore/blob/503a87199eb978790ca77675b39c84b241535bf3/crates/aionui-team/src/team_run/manager.rs) | queued、starting、running、enqueue lease 与 paused intent 都参与本轮结算；成员空闲不等于全队完成。 | 目录与概览呈现原运行事实，部分完成、暂停、失败各自保留；已完成成员数不伪装成果通过。 |
| [Mailbox](https://github.com/iOfficeAI/AionCore/blob/503a87199eb978790ca77675b39c84b241535bf3/crates/aionui-team/src/mailbox.rs) | 先读取未消费消息，实际交付成功后再确认消费；取消不能先丢消息。 | 沿已有 messageId/入队/消费回执，补充与暂停改要求明确分开；未知消息不换身份重发。 |
| [Work coordinator](https://github.com/iOfficeAI/AionCore/blob/503a87199eb978790ca77675b39c84b241535bf3/crates/aionui-team/src/work_coordinator/model.rs) | session generation、slot、batch 与 operation 绑定因果归属。 | 原生待处理检查当前 round/generation/child，再跨通知与工作区完整传递；不能用成员名或局部 ID 跳另一场协作。 |
| [TeamPage](https://github.com/iOfficeAI/AionUi/blob/e108b6b56b1a6eb99a00904878d0582f657b2908/packages/desktop/src/renderer/pages/team/TeamPage.tsx) | 成员视图、共享任务与活动是同一个团队上下文里的不同观察方式。 | 手机默认聚焦概览，分工、沟通与资料维持同一运行；保留搜索、选择和阅读位置。桌面宽列没有直接移植。 |

### 当前实现约束

一次协作仍可由私人协作页面手动发起；目标驱动的自动组织继续由已有管家工具链承担。本轮没有把快速角色模板描述成自治规划器，也没有把 AionUI 的提案当作已发布功能。编组来源、运行身份和用户原对话继续分开保留。

## PW-TC：当前范围和可观察要求

| ID | 当前问题 | 设计与实现要求 |
| --- | --- | --- |
| TC-01 | 目录以长期团队配置为主，已有协作被称为记录 | 默认查看协作，首要动作发起协作；复用编组单独管理。按真实状态分组，搜索保留，不把当前窗口数量写成历史总量。 |
| TC-02 | 普通目标需先建团队、保存审阅、再启动审阅 | 快速协作支持目标与模型分工，经一次启动审阅进入原生 start。一次性编组不写入团队定义目录。已有设备/文件编组沿原 viewHandle 和专项范围审阅使用。 |
| TC-03 | 运行页先堆成员再堆任务，成果在末尾 | 概览先目标、真实状态、需要处理及当前成果；分工、沟通、资料各有目的。旧结果失效时明确标识，完成计数不伪装总体成功。 |
| TC-04 | 研究/手机子任务的等待状态没有进入团队待处理投影 | 沿当前 round、成员 generation 和原 child 身份生成只读 attention；准确 taskId 可回原任务，旧回执/退出成员不复活。状态权威仍在原 TaskStore 与 Coordinator。 |
| TC-05 | 消息入队、修改方向、继续执行的语义不明显 | 普通补充只入队；修改要求并暂停是明确独立选择；继续使用已保存消息沿原控制链。原问题和收件成员保持可见，未知发送结果保留原消息身份且不重复提交。 |
| TC-06 | 停止后资料组件与原生作用域一起被禁用，违背可查看承诺 | 停止后仍能读原资料及撤回已有分享；不恢复新授权、执行或预算。撤回使旧结论失效，不能把 cancelled 改为 paused。 |
| TC-07 | 原始 phase、模型与运行术语挤占主信息 | 使用任务语言和明确下一步；原始日志/执行器详情下沉，现有失败、等待依赖、未知结果各自保留含义。 |

不新增第二执行引擎，不改团队/工作区权限，不复制生产数据，不把全部高级能力强行收进快速开始。已有工作项制作、材料授权、动态成员、历史身份、原任务审批与成果入口必须保留。

## 正式旅程与界面关系

1. 进入协作目录，首先看需处理和进行中的目标；已结束协作可回看。复用配置在编组标签中。
2. 发起协作：写目标，选快速分工或已存编组，必要时调整模型分工；确认实际目标、成员与轮次后启动。保存长期编组是另一种明确用途。
3. 进入协作概览：知道目标、当前进展、需要处理的问题以及现有成果。可打开原任务继续处理预算、授权、真实制作等上下文。
4. 查看分工：看工作项/成员职责、依赖、子任务状态和产出。控制动作与它作用的对象相邻，低频编组管理在详情中。
5. 沟通：默认收件人为负责汇总的成员；引用问题时锁定原问题与收件人。补充入队、明确修改方向、继续执行分别反馈。
6. 停止及返回：历史、消息和已共享资料可读；停止后仍可撤回资料。返回保留搜索、阅读位置、所选成员与未发送草稿。

## 组件及设计检查

采用仓内已有且保留来源的 BoardUI 字段/按钮/列表/对话框、Fluid Tabs、Affect AIAnswer/进度/消息呈现、Josh PressButton、现有 identity 与 motion tokens。来源版本沿 `src/library/PROVENANCE.json`、forms/shell provenance 和 `THIRD-PARTY-NOTICES.md`，不新增组件家族或用原生默认控件另造风格。

| 检查 | 本轮具体决定 |
| --- | --- |
| UIX-01 信息架构 | 协作 run 与编组 definition 分离；当前目标为首要对象。 |
| UIX-02 布局密度 | 320px 起紧凑单列；概览与分工分别披露，宽屏有节制地增加列宽。 |
| UIX-03 空间层级 | 目录→原协作→对象细节保持连续；不嵌套多重卡片或全屏指标墙。 |
| UIX-04 排版 | 沿现有正文/辅助/标题角色，长目标与成员名称自然换行，元信息不争主任务。 |
| UIX-05 颜色图标 | 沿主题语义色与低对比表面，状态不用颜色单独表达；不引入随机成员色块。 |
| UIX-06 组件 | 使用已采纳原库适配组件，不新增原生外观；组件出处不等于当前视觉通过。 |
| UIX-07 动效 | 使用原 view/collection/disclosure motion；选中与返回稳定，刷新不整页跳动。 |
| UIX-08 状态恢复 | 真实空/加载/失败、失效读取、未知提交、已停止只读保持独立。 |
| UIX-09 文案 | 目标、分工、负责汇总、补充、成果、待处理；不给当前机制虚构“自主Leader”。 |
| UIX-10 适配可访问性 | 所有按钮有名称，现有 Tabs/Dialog 焦点与系统返回保留；窄屏允许内容换行。 |

## 关键数据契约

- `teams.prepareDraftStart` 只准备模型协作的不可变启动审阅，不保存编组、不执行推理；返回与 `teams.reviewStart` 相同的启动审阅结构。
- `teams.confirm` 消费原 reviewHandle，经已有 FeatureHost `teams/start` 登记 TaskStore，再交原 TeamCoordinator。只有原生私人入口可使用 draftDefinition，工具/JSON 不取得新执行权。
- 启动审阅的模型/工具上限直接来自原 `TeamCoordinator.initialBudget`，界面不复制预算公式。`pendingStartTeamId/pendingStartSince` 只用于未知回执的原记录核对，不能承载审阅、授权或自动重试。
- `reading.attention` 为 `TeamAttention.forRun(run)` 的只读结果，携带原 run/member 代次及可核对的 child/工作项/问题身份；不是新的状态存储。
- 原生材料目录的 revoke-only 模式覆盖退出成员与已停止协作。撤回只影响来源有效性与旧成果，不使已结束执行继续。

## 当前状态与验证边界

2026-11-11 14:45 UTC：本轮实现、限定交叉源码审阅和 Web 生产构建完成。`npm run build --prefix web-ui` 成功执行字体与许可准备、`tsc --noEmit`、Vite 生产打包与 224 个打包文件的清单/哈希核对；`git diff --check` 通过。初次类型检查发现的临时分工移除成员数组推断错误已修正，随后完整构建成功。构建保留既有 `client-only` 包许可正文缺项与大 bundle 提示；本轮未新增依赖，也没有修改许可声明或资源完整性门槛。

| 活动要求 | 当前落点 | 当前证据与剩余边界 |
| --- | --- | --- |
| TC-01 / TC-03 / TC-07 | `TeamsWorkspace`、`team-presentation`、`team-workbench.css` | 默认协作目录、四个运行面板、目标/结果顺序与中文状态已经实现并通过类型/打包；实际窄屏构图与滚动未体验。 |
| TC-02 | `TeamQuickStartForm`、`TeamStartReview`、`PocketWebTeams`、`TeamDraftDefinition`、`FeatureHost`、`TeamsModule` | 一次审阅进入原 TaskStore/Coordinator；独立审阅确认没有保存永久编组或附加设备/文件授权。已修正等待原生锁时离页仍可能启动的窗口，以及归档来源标记遗漏。 |
| TC-04 | `TeamAttention`、`NotificationTeamAttention`、`PocketWebTeams`、`TeamCollaborationPage` | 原生唯一候选投影与精确身份贯穿；已修正跨运行局部 key 冲突、前端补猜子任务、成员级误去重，以及问题消息缺 workItemId 的断链。 |
| TC-05 | `TeamsWorkspace` 与既有消息回执/持久草稿 | 普通消息与暂停改要求分开，原问题按当前候选回答；明确 `not_accepted` 可重新核对，未知结果仍保留原标识、不自动重发。 |
| TC-06 | `TeamEvidenceWorkspace`、`TeamCoordinator.memberMaterialsRevokeOnly/researchMaterialsChanged` | 停止后读取与撤回可达，原生 cancelled 分支不会进入暂停/重置/重新执行；真实撤回流程尚未在设备上执行。 |

没有编写或运行单元、冒烟、集成、契约或回归测试，没有调用 fixture 页面，没有本地构建 APK。已静态核对实际 npm、Git hooks 与 main 发布工作流：本次构建路径不触发产品测试；旧 `tools/release.py`/PowerShell 包装仍含测试，未采用。

Android 编译、签名、打包与正式发布沿本次 main 提交对应的 [GitHub Release 工作流](https://github.com/wozhenbang2004/PocketWorld/actions/workflows/github-release.yml) 完成，实际结果以该提交的运行和 Release 为准，不提前标成功。当前环境没有可用 Android 设备/模拟器；设备上的真实协作、触摸、键盘、焦点、前后台、动效与视觉仍待正式产物体验。UIX-01/02/03/06/08/09 的代码关系已核对，UIX-04/05/07/10 的设备效果及完整用户旅程未验证，不能用源码/构建代替视觉验收。

已保存编组的启动若只有未知回执而实际候选缺失或不唯一，继续保留待核对状态；本轮没有另建操作回执框架。材料、手机和项目文件协作仍走各自原任务范围，不把快速模型分工扩成通用执行授权。

审美库已实际读取核心政策、统一入口、UIUX 索引并检索相关候选；Space 最新清单为 `20261110T051726Z-6f9a1718`。当前安装副本与远端存在同步冲突，双方保留，未宣称全库已更新；本轮组件沿仓内已固定的原码/许可及既有采用基线取用。
