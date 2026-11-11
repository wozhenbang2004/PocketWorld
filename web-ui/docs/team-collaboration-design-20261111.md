# 团队协作：一次目标的共同完成

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
