# PocketWorld 动效覆盖深化研究 · 2026-11-10

## 当前结论与研究边界

**可以明显做得更丰富。下一轮优先补齐业务对象的连续变化：答复从等待到完成、任务状态和位置的变化、当前步骤的展开、结果到达、详情返回以及局部操作回执。** 已有控件和动效底座可继续使用；需要把它们接到真实用户旅程，而不是仅增加公共时长、曲线或页面入场。

用户本轮原话：“还能做更好更丰富覆盖更全吗我建议先深度看看我们审美库相关的”。承接原目标：“结合我们审美库给我们的 pocketworld 的动画系统重构，不过好像其实本来就没啥动画我们 pocketworld”。来源为当前对话；日期为2026-11-10，精确消息ID不可取得。当前解释是先深入研究准确参考与现有覆盖，再形成下一轮可执行设计。

本研究基于 `f4a4c539227eff973df839a0149bc238a7f32545`，包含上一轮动效实现 `49e4512490612168acc3a490d37fa17b70a86857`。本轮只改变研究文档与设计入口，没有修改产品实现、构建或执行测试。下文的“已有”“缺口”均为源码判断；当前 APK 的触摸、帧率、输入法与动效品质未在本轮实机确认。候选设计没有被记为用户审美认可。

### 本次实际阅读与观察

- 完整读取审美库新《动效与过渡系统》的14章、六类任务配方、准确旧版本表及交付边界；读取《组件选择与控件状态系统》中提交、取消、结果未知、局部反馈、披露与返回的相关章节。
- 深读 Josh、Fluid/Jelly、Ovio、Vaul、Reaction Bar、Nick 步骤器及 Affect 的相关原记录、固定源码或归档；对 Morphing Dialog/Popover 等候选核对其证据状态。没有全量宣称129项 Affect 或整库均已逐项体验。
- 主线程在已发布的动效专题实际进行了连续点选、横拖释放、减少动态切换及切换后的继续操作；看到对应选择、内容与操作阶段更新。此为桌面浏览器的教学参考体验，不是旧批准版手感的重新验收，也不是 PocketWorld 实机体验；没有量化瞬时速度或帧率，没有覆盖触屏与拖动中 Esc。
- 实际查看归档的 Affect 官方 Agent Chat / Agent Run 原截图。它们展示了正文、当前工作、展开的活动及待处理决定之间的关系；静态图不能证明动画过程。研究代理还查看了 Vaul 原录像帧、Reaction Bar 原效果与 v1 桌面图、Nick 归档录像帧；Ovio 本轮主要依据源码与既有评审记录，未将缺失的 PNG 记为已查看。
- 按13组真实旅程读取当前状态与渲染逻辑，未仅凭动画 API 的 import 或 CSS 存在判断覆盖。

正式 Skill HEAD 仍为 `20261110T051726Z-6f9a1718`。新动效专题已经作为 Page 与交互网页发布，但其完整 Skill 合并仍为 pending；本文直接取用已读取的新版材料，不宣称本地安装副本已经包含最新全部文件，也不改写全局 HEAD。

研究入口：

- [审美库账号资料库](https://chatgpt.com/space/page_6c522c5579c08191944bb585f4bf5708)
- [动效与过渡系统全文](https://chatgpt.com/space/page_f4034532030c8191a308961164dd0335)
- [已实际打开的动效教学](https://uiux-library-visual-catalog.k01075888660.chatgpt.site/studies/motion-system?from=uiux-motion-transition-system-20261110#continuity)
- [组件选择与控件状态系统](https://chatgpt.com/space/page_fdada86a1c088191a5b13013fd3f66d3)

## 一、深读后改变了什么判断

### 1. 组件动起来与整个任务连续，是两层工作

上一轮已完善输入区、选中底片、侧栏、公共浮层及运动偏好。当前业务层仍有“同一任务换分组”“等待行变成答复”“列表变成详情”“读取中替换整块旧内容”等直接变化。这些位置才决定用户能否追踪自己刚才操作的对象。

这与 Carbon 对组件微交互和产品整体编排的区分一致：组件自带动画，并不自动处理组件之间的关系与页面变化。这里取其方法，不统一采用 Carbon 的皮肤或参数。[Carbon 原文](https://www.carbondesignsystem.com/building-blocks/foundations/motion/overview)

### 2. 更丰富可以来自形态、层级和让位，不必增大整体运动

Jelly 的关键是选中底片的中心、宽度、速度相关拉伸与按压反馈；文字和命中区域保持稳定。Nick 的价值是主子步骤共轴、当前细节展开与弯曲脊线跟随布局。Reaction Bar 的价值是增删后邻项让位。Affect 的价值包括按钮内容变化后的宽度接续、真实阶段换字、结果与活动留在同一任务上下文。

因此 PocketWorld 可保持当前 Paper/Graphite 和批准的侧栏、双层输入框，同时增加“操作对象在连续变化”的感受。新的弹性幅度、形态适配及真实使用效果仍需分别体验。

### 3. 公共 token 是默认值，角色关系还要继续细分

现有 `motion-tokens.ts` 将进入、退出、布局、选择和抽屉分开，是有用基础。下一轮需补充具体去向、距离和使用频率，而非让每个退出都共享同一性格。Carbon 明确保留一种例外：仍被理解为停在屏幕外、可再次召回的侧栏，退出可采用标准曲线。Fluent 对没有父子关系的顶层目的地倾向快速淡化，以免暗示不存在的层级。[Carbon](https://www.carbondesignsystem.com/building-blocks/foundations/motion/overview) · [Fluent](https://fluent2.microsoft.design/motion/)

位置接续与速度接续也要区分。Motion 的物理弹簧、`duration + bounce` 与 `visualDuration` 有不同承接条件；不能把“有 spring”统一当作保持惯性。此处只确认机制，不用现行文档替代项目固定版本及目标 WebView 的实际核对。[Motion transitions](https://motion.dev/docs/react-transitions)

### 4. 好看的演示源必须保留正确的业务改造

Nick 的获认可归档演示按 `duration / elapsed` 推导步骤完成，设置 steps 会清零并重建。Affect ProgressTracker 原件使用内部 done 集合、手工勾选及可选 autoplay；其现有 ID 的 done 变化不是完整受控接口。两者不能直接充当真实 Agent 任务的执行事实。

PocketWorld 当前 `execution-stepper.js` 已去掉演示播放时钟，`trace-stepper.ts` 由真实 trace 状态生成步骤；ThinkingLoader 也已受控。下一轮沿这些正确适配补回表现能力，不能为重现演示动画把假进度重新接进产品。

### 5. 保留上下文优先于强行形变

返回应保留原对象、阅读位置、草稿与合理焦点。不是每个弹层都要变成底部抽屉，也不是每次打开详情都需要大面积共享元素变形。模型、推理、权限继续保持已经采用的独立锚定入口；同级切换、父子层级、就地披露分别处理。

## 二、准确参考与采用决定

| 参考与身份 | 本次读到的具体机制 | 对 PocketWorld 的决定 | 认可与采用边界 |
| --- | --- | --- | --- |
| Josh 中性 comfort 批准版；`review-approved.html`，SHA `11ed2af5…6a37cb7e` | 稳定底座、下沉与释放、轻微受力 | 保留现有 PressButton；让实际操作阶段接续其反馈，文字和目标位置保持 | 用户“这个非常舒服”只归属准确版；不继承粉色整版或石墨发白否决版本；未声明独立 SPDX，不标 MIT |
| Fluid / Jelly 批准显示版 | 共享底片；Jelly 分开中心、宽度、速度拉伸和按压 | 保留少量相关短标签的选中连续性；重点补业务内容与选择的配合 | 两个点击显示版有认可；Jelly 横拖只有实施授权，未获真实手感批准 |
| Ovio 固定 `81a1bcb6737356038163728b3b2a657c9dcffa32` | Minimal 的双边缘 lead/trail；按压与释放的不同状态 | 延续已采用 Minimal 原码；只在合适的局部选择中保留弹性 | 首3明确喜欢、另6委托选优，家族 partial；Toy 的 pointercancel 会走提交路径、track 为 touch-none，不能直接覆盖现有取消和纵向滚动合同；Dial Menu 保持隔离 |
| Vaul 1.1.2，`3e97aac6a38e4481bade71d7233ed6002e80f9b0`，MIT | 前景拖动与后景缩小/圆角形成空间关系 | 保留真实抽屉与现有手势归属；分别调节导航抽屉与任务层的收束 | 用户“这个抽屉可以的我喜欢”来自作者原录像；不能泛化为所有辅助界面都使用抽屉；当前接入体验另核 |
| Reaction Bar v1，独立已批准实现，SHA `f9d06ac2…306d33f6` | 局部增删、选中反馈和 WAAPI FLIP 邻项让位 | 参考任务、通知、记忆、附件等真实对象的局部变化；优先沿已留存 Affect FLIP / 现有 Motion 机制适配 | 用户“那那个你入库吧”归属 v1；FLIP 不是物理弹簧，也不证明跨平台效果 |
| Nick 主子任务步骤器的准确归档 | 主子文字共轴、当前子步骤展开、按测量高度弯曲的脊线 | 在现有受控 ExecutionSteps 上补稳定 ID 的局部更新、增删与展开连续性 | `INDEX.md` 将“挺好的”指向 `frontend-final-source-archive-20261031` 同版核心；批准归档与裸文件仅3处灰度不同，不能夸大为功能版本分叉；不是作者原码 |
| Affect 129项设计参考；官方源码 `37b91fa` / 独立参考 `0e69029` | 受控 ThinkingLoader、状态按钮宽度变化、ToolCall 详情、Agent 活动与结果上下文 | 组合现有真实状态适配与已采用组件，补任务阶段、操作回执和结果到达 | 129项设计参考认可不等于全部官方原码：40项官方 MIT 源码对应57份 TSX，89项独立参考；不把公开 Pro 演示当官方 Pro 源码；不移入模拟流式或自动完成时间线 |
| Motion Primitives Morphing Dialog / Popover | 库内记录指向同对象展开的候选方向 | 暂列后续比较，不作为本轮默认迁移目标 | 当前实体仍为 technical_candidate_unreviewed，固定源码/版本/许可证据未闭合；不能借“库里有”自动升级成已批准组件 |
| 拖拽重量感 v3、Ramotion、Yui540、Stageshow | 跟手/落位、触点反馈、遮罩时序、叙事编排 | 保留为特定任务的后续研究入口；不为丰富动效新增无业务需要的拖拽或演示 | 拖拽 v3 是普通接受且本地准确原码未读；Ramotion 是 Swift/iOS 原码；Yui540 的关键帧叙事不证明可任意中断；Stageshow 是方法参考 |

既有产品源码与身份入口：[`library/PROVENANCE.json`](../src/library/PROVENANCE.json)、[`NAVIGATION-UIUX-PROVENANCE.json`](../src/shell-library/NAVIGATION-UIUX-PROVENANCE.json)、[`forms-library/SOURCE-map.json`](../src/forms-library/SOURCE-map.json)。准确旧案例映射与完整哈希见动效专题第十三章；本表只记录本项目取舍。

## 三、13组旅程覆盖矩阵

下列差距分为“已有能力未充分消费”和“需要增加业务表现连接”；不把源码静态推断写成已经复现的运行故障。

| 旅程 | 当前已有，继续保留 | 下一轮重点 | 源码入口 |
| --- | --- | --- | --- |
| 聊天发送、等待、流式、答复 | composer 尺寸/附件/控件 presence；真实新消息一次到达；历史与流式阅读锚点 | 等待行、流式草稿、正式消息之间按真实答复身份连续交接；正文不二次入场，来源/附件/动作局部出现 | [`App.tsx`](../src/App.tsx)，`chatPage`；[`surface-motion.ts`](../src/library/surface-motion.ts)；[`useConversationReading.ts`](../src/useConversationReading.ts) |
| 聊天中的后台工作 | 真实状态、进度环、task ID、箭头旋转；需要处理的任务始终可达 | 展开高度与内容一起变化；同一任务在“需关注/单项后台/多项后台”之间保持可追踪 | [`ConversationWork.tsx`](../src/ConversationWork.tsx)，`renderTask` 与 `hidden={!expanded}` |
| 任务列表与结束去向 | 原 task ID、实际进度；任务详情和返回位置 | 新任务局部加入；进行中→需处理/已暂停/最近结束有位置与状态交接；历史加载不重播 | [`App.tsx`](../src/App.tsx)，`renderTaskRow`、`endedTasks`、三组任务列表 |
| 执行轨迹、步骤、结果 | 三泳道条块位移/宽度、事件定位高亮、真实耗时、状态时钟、详情浮层 | 步骤结构增删局部更新；真实树折叠消费连续布局；结果正文/附件到达；事件/区间/步骤视图选择协调 | [`TaskTrajectory.tsx`](../src/TaskTrajectory.tsx)、[`trace-stepper.ts`](../src/trace-stepper.ts)、[`execution-stepper.js`](../src/library/execution-stepper.js)、[`TaskOutcome.tsx`](../src/TaskOutcome.tsx) |
| 团队与群组 | 本地 viewKey、焦点/返回位置/草稿、共享 Tabs 与弹窗、真实读取条 | 目录→定义→运行及群组→详情的内部层级；成员消息、待答问题、成果及投递回执局部变化 | [`TeamsWorkspace.tsx`](../src/TeamsWorkspace.tsx)、[`GroupsWorkspace.tsx`](../src/GroupsWorkspace.tsx) |
| 文件、版本、项目成果 | 加载占位、保存进度、编辑/比较/历史 Tabs、来源披露 | 安全可展示的旧列表刷新时保留上下文；文件列表/网格转换；版本详情与采用回执；图片/附件占位让位 | [`App.tsx`](../src/App.tsx)，`projectListBusy`；[`ProjectFileEditor.tsx`](../src/ProjectFileEditor.tsx)；[`FilesBrowser.tsx`](../src/shell-library/FilesBrowser.tsx) |
| 浏览器研究、页面、来源 | 原生真实加载进度、忙碌文案、审阅弹窗 | 内部视图、研究状态、接管/交还、待继续操作与恢复状态的局部衔接 | [`BrowserWorkspace.tsx`](../src/BrowserWorkspace.tsx)，`currentView`；[`BrowserChrome.tsx`](../src/BrowserChrome.tsx) |
| 设备、手机与桌面 | 手机上一有效解码帧、桌面同一个 video、真实指针坐标、浮层 | 设备分组去向、内部详情、连接状态与控制栏；放大容器需与实际渲染/输入几何协同 | [`DevicesWorkspace.tsx`](../src/DevicesWorkspace.tsx)、[`DesktopViewer.tsx`](../src/DesktopViewer.tsx)、[`phone-viewer.tsx`](../src/phone-viewer.tsx) |
| 个人资料、偏好、记忆 | **内部层级已接 useSurfaceEnter**；草稿/返回、来源与冲突披露、确认层 | 记忆采纳/忽略/删除/撤销及分组变化；保存/错误/待核对提示在原位置出现 | [`ProfileWorkspace.tsx`](../src/ProfileWorkspace.tsx)，`memoryRow` 与已保存/候选分支 |
| 设置、AI 接入、备份和存储 | Secondary 外层进场；共享表单/开关；真实 pending/unknown/recover；返回焦点 | AICenter 内部 view.kind、DataSettingsJourney 内部层级；局部读取与保存回执 | [`AICenter.tsx`](../src/AICenter.tsx)、[`DataSettingsJourney.tsx`](../src/DataSettingsJourney.tsx)、[`Secondary.tsx`](../src/Secondary.tsx) |
| 通知收件箱 | 已连接呼吸、基础开关/按钮、Sonner 进退 | 新通知加入和未读筛选下标记已读后的离场/让位；未读数量与当前阅读位置 | [`RemoteInboxWorkspace.tsx`](../src/RemoteInboxWorkspace.tsx)、[`remote-inbox.css`](../src/remote-inbox.css) |
| 提醒与定时工作 | 列表位置与焦点恢复；编辑/确认/Accordion；真实回执 | 今天/明天/暂停/结束分组变化；列表→规则详情；读取时保留合法上下文 | [`ReminderWorkspace.tsx`](../src/ReminderWorkspace.tsx)，`chooseRule` 与 reading/list/detail 分支 |
| 应用更新 | 真实字节进度、不确定状态、共享控件、安装返回/待核对语义 | 固定操作区域内衔接检查→下载→暂停/恢复→校验→可安装；按钮/图标/说明协同 | [`UpdatesWorkspace.tsx`](../src/UpdatesWorkspace.tsx)，`primary`、`progress` 与进度区域 |

### 三个最有说明力的具体缺口

**后台工作只动了箭头。** `ConversationWork` 的内容列表通过 `hidden={!expanded}` 直接显隐；状态改变还会让同一 task ID 进入不同父分支。下一轮应连接内容高度、任务身份、分组和状态文字，不能只给根节点加一次淡入。

**步骤器原能力没有全部进入真实轨迹入口。** `traceSteps` 将父子记录摊平成带 depth 的 rows，不传内嵌 tasks；ExecutionSteps 内嵌子步骤的 grid 开合在此入口没有对应内容。结构改变时 `_build()` 重建，普通更新也重写 glyph / label；耗时通过 `digitWindow.textContent` 更新，保留的 `.digit` 入场 CSS 不会因此生效。现有时钟和状态样式确实存在，但不能据此宣称真实树展开已完整连续。优先做稳定 ID 局部更新与真实层级变化；精确耗时仍取记录，不为制造运动添加计时器。

**全局动效能力没有覆盖全部独立实现。** `library/agents.tsx` 的 TreeChildren 仍用固定280ms计时卸载；`feedback.tsx` 的 BlurCrossfade 用固定420ms清除；`adapted/agent-motion.tsx` 的 FLIP 在 revision 变化时取消旧动画，减动效采用即时读取而非途中订阅。它们有自己的合理来源，但需要逐实现补中断/立即落定/隐藏清理；不能凭全局 token 或 CSS 宣称统一完成。这里是静态审阅发现，实际表现仍须绑定构建后真实体验。

## 四、下一轮设计范围与可观察要求

这些条目是本轮推荐设计与待实现项，承接现有 PW-M1–PW-M6，不覆写此前已实现状态，也不自动新增组件家族批准。

| ID | 采用与落点 | 可观察要求 |
| --- | --- | --- |
| PW-M7 答复连续性 | 沿 Affect 已采用等待/答复组件与现有消息身份，将等待、流式、正式结果连接 | 同一答复不重播正文；补全只加入新来源/附件/操作；用户阅读历史时不被拉回底部；身份不能可靠对应时保守即时切换 |
| PW-M8 当前任务与步骤 | 延续受控 Nick / Affect 适配，稳定 ID 更新节点；当前细节、图标、脊线和邻项协同 | 真实阶段变化可追踪；用户主动展开的历史不被新事件强制折叠；失败/需确认可见；演示时钟不得决定完成 |
| PW-M9 业务列表 | 按 Reaction Bar / 现有 Affect FLIP / Motion 的局部让位机制，覆盖任务、通知、记忆、提醒及协作消息 | 区分用户动作、新事实与历史恢复；相同对象换组可追踪；被删除后焦点有合理后继；列表总等待不随条目数量累积 |
| PW-M10 内部层级 | 扩展现有单个当前表面机制到团队、群组、AI 接入、数据设置、设备、提醒、文件历史 | 同级与深入/返回含义一致；快进快退接当前状态；保留已有草稿、滚动和焦点；不保留两套活编辑器/订阅 |
| PW-M11 操作到回执 | 沿现有 BoardUI/Josh 操作壳，参考 Affect 状态内容与宽度接续，覆盖保存、复制、投递、启停、更新 | 输入立即反馈；等待留在原动作附近；成功/失败/未知/请求取消分别表达；不从动画结束推断保存成功 |
| PW-M12 成果与真实预览 | 局部结果、附件和占位到内容的连续变化；实时渲染区域保留真实实例 | 新结果出现时原阅读对象稳定；媒体载入不造成无依据大幅跳动；不对真实帧补间、不克隆 video/WebView；native sibling 几何未确认前不对其套容器形变 |
| PW-M13 完整运动生命周期 | 审阅旧 FLIP、BlurCrossfade、树分支、Shadow DOM 步骤器和 Embla 的实际 owner | 快速反向、取消、减动效途中变化、隐藏/恢复、卸载均进入合法终态；暂停装饰不暂停业务；恢复不补播过时动作 |
| PW-M14 节奏与注意力 | 保留公共默认值，按短反馈/局部展开/长期停靠/层级/结果细分；旧批准参数有明确继承范围 | 文字和命中区稳定；少量关键动作有表达性；高频阅读与批量刷新保持安静；结果可读可操作不等装饰尾部 |

### 建议实施顺序

**第一批：聊天与任务。** 打通 PW-M7 / M8，以及后台工作和任务列表的 PW-M9。它覆盖最常用链路“输入→等待→执行/需我处理→结果→查看过程→返回”。首批采用已有来源，先把步骤器与业务对象连续性做完整。

**第二批：把相同机制扩到日常管理。** 通知、记忆、提醒、团队/群组、文件与设置内部层级；应用 PW-M9 / M10 / M11。按各模块实际事件和返回合同接入，避免“一把包上 motion”掩盖不同状态语义。

**第三批：媒体与跨渲染层细化。** 设备、远程桌面、项目预览、浏览器 toolbar/native inset，以及剩余独立生命周期。它们需要真实几何和输入对应证据，不能从 React 部分的变化外推原生视图已同步。

PW-M13 / M14 随每批涉及的实际组件一起处理，最后按覆盖表复查。Morphing Dialog/Popover、额外拖拽与其他新视觉家族保留为后续比较；当前采用范围已有足够的丰富度来源。

## 五、状态、身份与中断合同

每个实质变化在相关组件附近说明以下必要信息即可，不新增通用调度平台：

1. 谁在变化：task/run/message/file/receipt 的稳定身份及窗口版本。
2. 为什么变化：用户动作、新事实、局部刷新还是历史恢复。
3. 哪个是真实状态：意图、呈现、业务回执分开；未知、取消请求和已取消分开。
4. 哪些属性由谁负责：位置、内容尺寸、材质按压、透明度、滚动不能争抢同一 owner。
5. 怎样中断：改目标、返回、关闭中重开、pointercancel、减动效变化、隐藏和卸载如何收束。
6. 怎样继续原任务：阅读位置、选择、未提交输入、焦点及结果入口保留什么。

其中聊天尾随不能普遍改成 smooth scroll；现有阅读锚点与键盘/内容增长已有专门管理。native preview 的 CSS transform 不会自动证明宿主每帧收到正确矩形；权限、敏感读取和窗口有效性失效必须立即处理，视觉退出不能延后这些业务清理。

## 六、证据状态与下一检查点

- **已完成：** 新动效系统全文与相关状态系统研究；具体源码/历史认可分层；参考教学桌面操作；13组旅程静态覆盖盘点；PW-M7–PW-M14 的采用建议与可观察要求。
- **已有产品基线：** PW-M1–PW-M6 的代码已在上一轮实现并构建。本轮不把它重新标记为未做，也不将本轮候选写成已实现。
- **未完成：** PW-M7–PW-M14 的正式产品实现、当前 APK 真实体验、新适配的用户效果反馈；图像/视频本身不能证明交互质量。
- **验证边界：** 未编写或运行测试；没有用模拟宿主、假任务或伪回执制作产品效果。后续先完成正式实现与必要构建，再走获准的真实用户旅程，区分构建结果、AI 实际体验与用户认可。
- **下一确切动作：** 从第一批“聊天与任务”开始：沿本报告读取现有源码与准确来源，为答复身份交接、后台工作展开、步骤局部更新和任务换组确定实际事件及单一表现 owner，再进行有界实现。先阅读本报告、`DESIGN.md` 的 PW-M1–6 与相关源文件；不重新做已完成的全局 token / 输入框 / 侧栏迁移。

本报告的覆盖表、差距和采用状态以本文件为唯一维护位置，`DESIGN.md` 只保留入口。新的主线变动应在实施相关模块前复核；代码基础、参考效果与授权/审美状态分别记录。
