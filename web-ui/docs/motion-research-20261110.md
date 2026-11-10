# PocketWorld 动效覆盖深化研究与实施记录 · 2026-11-10

## 第二次深化 · 从覆盖推进到受力、形态与阶段接续

用户原话：“我觉得还可以再深入地再做一下这些动画系统，好吧？你可以再挖掘一下我们审美库里面的一些东西，好吗？”来源为本聊天最新消息，精确消息ID与发送时刻不可取得；本批记录于北京时间2026-11-10 21:08。承接“去！全部交给你我相信你”的实施授权。PW-M1–PW-M14 的已发布结果继续保留，本轮不是重做覆盖或重新批准现有组件。

本轮从主线 `b39b846809795ecf004939039e2ccb8000637da7` 开始。重新读取新版动效系统全部14章、控件状态相关段落与当前审美库主档，进一步追到具体的内容尺寸、原码受力和阶段切换。主档显示新系统正文已经保存，完整 Skill 合并仍 pending；没有把安装副本缺项当作库中不存在，也没有改写全局 Skill。

### 本轮改变选择的新证据

- 现有 `MotionDisclosure` 是 grid `0fr ↔ 1fr`；虽然已覆盖开关与中断清理，保持展开时的内容增高没有显式尺寸目标。批准的 Affect 官方 `message-actions-clean.tsx::MessageText` 用 `ResizeObserver` 测内部自然高度，再驱动外层高度；本轮取该测量关系，保持一棵真实内容树。
- 现有文件列表与网格分别挂载不同 DOM；FLIP 只连接坐标，尺寸、文字与焦点没有同一对象的完整接续。沿批准 Affect Files/Folder 结构，把同一路径的真实控件保持为同一节点，再使用现有 Motion 的布局投影及文字位置校正。
- 现有集合 FLIP 的中断保留位置，但重新使用固定时间曲线不继承速度。Ovio 固定源码的 `use-liquid.ts` 已明确采用“当前值 + generator.velocity → 新 spring → WAAPI 帧”的机制；本轮把该机制有界组合到已有 Affect/Reaction Bar 集合让位，位置和按钮自身按压各有属性归属。
- Josh 已批准 comfort 版的受力是轻微横张、纵向压扁；当前薄材质适配主要横纵一起缩小。本轮恢复这个关系，并用现有物理弹簧接续快速释放/再按，不恢复被否定的配色或厚重外观。
- 当前回复阶段在文字出现时卸载 ThinkingLoader，换成另一套图标/裸文字；原生 `ModelStreamPreview` 允许 `text=""` 直接收到 failed，而当前 `!hasText` 优先级会继续显示思考。沿批准 Affect Thinking Loader 的固定图标槽、标签接力及宽度测量，接入真实阶段并纠正该映射。
- 精确依赖链为入口 `motion 12.42.2 → framer-motion 12.43.0 → motion-dom 12.43.0 → motion-utils 12.39.0`。当前官网新增内容不能代替锁文件；本地 spring 生成器对 duration/bounce 分支会清零初速度，本轮使用明确 stiffness/damping/mass，不把 visualDuration 当成速度接续保证，也不升级依赖。

### 采用合同与停止边界

| ID | 本轮正式落点 | 必须保留的可观察要求 |
| --- | --- | --- |
| PW-M15 实际尺寸披露 | Affect MessageText 测量关系；单树 MotionDisclosure | 展开中增高、收拢、快速反向接当前高度；内部文字不缩放；已完全展开的嵌套父层交还自然布局；关闭立即 inert，减少动态和隐藏直接合法落定。流式正文、活编辑器和原生预览不被新高度动画接管。 |
| PW-M16 文件形态连续 | Affect Files/Folder + 当前 Motion layout | 同 path 列表/网格使用同一真实控件；外形/位置可追踪，文字/图标不随壳拉伸；选择、菜单、焦点保留；首次、查询或项目切换不重演整列。 |
| PW-M17 集合运动接续与归属 | Affect/Reaction Bar FLIP + Ovio 物理生成器 | 同 ID 连续换位接可见位置和当前速度；不跨屏飞行、不复制活内容、不用 stagger 累积等待；列表位置与按钮受力分开，滚动/resize/关闭直接交还当前布局。父表面进场时不叠加另一层子项位移。 |
| PW-M18 输入受力与释放 | Josh comfort；Ramotion Fluid Slider 已有适配 | 按压微横张纵压、释放/再按接续；命中目标与业务触发即时；取消/隐藏/失焦清理。滑块指针松开时回收数值气泡，键盘焦点仍保留值反馈；不更改真实值提交。 |
| PW-M19 回复阶段接力 | Affect Thinking Loader 的固定槽/标签/测宽原码 | 同一答复等待→生成→调用结束/失败保持一行；只响应真实 phase，逐 token 不动；failed 优先，空文本失败不伪称草稿保留，turnEnded 不画成任务成功；历史/重进安静，旧标签只作无交互的短暂显示。 |

保持 Solar 图标、低饱和蓝灰、当前侧栏和双层输入框，模型/推理/执行权限仍为独立锚定入口。本轮不引入新组件家族、不把数字动画当真实进度、不改变任务/回执协议；不写、不跑任何测试。完成后统一类型、正式构建、只读交叉审查与已授权发布，实际 Android 手感按当前可取得环境单列，不以构建代替验收。

### 正式实现与源码审查

PW-M15–PW-M19 已接入正式产品。`motion-disclosure.tsx` 从批准 MessageText 的自然内层测量关系适配，连续 height MotionValue 负责正常改目标，取消/宽度变化/焦点进入即时归位；TreeChildren 用 `resize={false}` 在完全展开后归还自然流。FilesBrowser 保持同 path 的 ContextMenuTrigger、按钮、文字与 FileArt，外壳 layout 与子层 position 校正分开；项目真实滚动容器仅标 `layoutScroll`，没有给原生预览的祖先增加 transform。每一投影层均记录在当前运动集合中，相关滚动会收束整组，而非只看壳是否结束。

集合让位用固定 Ovio 源码的物理生成器和 WAAPI 采样机制。中断读取可见位置及 generator 速度；跨组导致旧节点已移除时，保留的实际帧结合 WAAPI 已含 easing 的进度继续还原位置与透明度。首次到达的淡入仍是时间曲线，不冒称物理惯性；后续位置让位才采用 spring。individual `translate` 与控件自己的按压 transform 分开。App 的表面进场标记让同次新对话/消息变化只保留一层空间入场；不是跨全树的通用编排事务。

Josh 适配只改变 material，保留文字、44px 命中、薄材质和配色。按压/释放由持续的 press/strain 值接续；新物理参数属于产品适配，不声称逐帧等同原78/210ms脚本。FluidValueSlider 以 `isDragging || isFocusVisible` 区分触点与键盘；减少动态/隐藏时仅替换 aria-hidden 的气泡，Thumb 与真实 value owner 保持，`initial={false}` 直接落定。

ReplyPhaseStatus 取 Affect 固定14px图标槽、纯显示旧标签和测宽关系；failed/queued/retained/stopping/turnEnded/实际文本决定当前阶段。空文本失败已按 native `ModelStreamPreview.kt` 的实际合法分支修正；调用结束不等于任务成功。流式 token 不重播，旧标签最多一份且 inert/aria-hidden；字体 ready/loadingdone 只重新拟合当前宽度。后台、离屏与减少动态不补播。

三条独立只读审查分别覆盖披露、输入受力/滑块、回复阶段与集合/表面接续。发现并补齐了跨组首次入场丢失透明度、Slider仅改transition不能保证取消旧spring、字体迟到未重测以及文件子层结束晚于壳的收束范围；静态复核未留下本轮阻塞。没有编写或运行测试。北京时间2026-11-10 21:35，统一 `npm run typecheck --prefix web-ui`（仅 `tsc --noEmit`）完成；正式构建与发布继续在本节记录。最新用户原话已覆盖至本轮深化要求，没有新增未记录指令。

### 实际参考观察与明确限制

本轮亲自操作了库中精确 Josh neutral 已存交互（长按/释放、Space），查看 Ramotion 作者 GIF 的升起/回落原帧；打开 Affect 当前官方 Thinking Loader Clean 预览并 Reset，且查看保存的 Agent Chat、Reaction Bar、Vaul 图证。固定源码身份与当前官网运行 build 分开；未找到 Files/MessageText 的专门动图，不冒称这些新适配已实际看过效果。实现来源分别为 Affect 官方 `37b91fa`、批准独立 v3 `0e69029`、Ovio `81a1bcb`、Josh exact neutral SHA `11ed2af5…6a37cb7e` 与 Ramotion `98c787c`；原始字节及许可声明保持。

Files 的现有 Motion projection 能保留当前位置/尺寸重新定向，但其进度重启时初速为零，**不声称二维速度连续**。文件集沿用完整 DOM，没有加入可见项裁剪或虚拟化，大数据量的实机帧率未验证。嵌套父子同时打开仍可能重新计算目标；高度夹取只作用于显示，不改内部物理速度。当前没有连接 Android 真机/模拟器，触屏、软键盘、实际帧率和个人手感仍未验收。NumberTicker、Yui540 和拖拽v3没有被悄悄接入：前者保留为具体候选，后两者本轮只取得有限材料，原批准/未验范围不扩张。

下文是上一轮已完成的历史记录。

## 当前交付状态与研究边界

**PW-M7–PW-M14 已完成正式产品源码集成，覆盖下文13组旅程，随 v0.1.86 发布。** 本次把等待、流式答复、任务分组、步骤树、对象列表、内部层级、局部回执和真实媒体区域接到统一的身份及中断规则。沿用已批准组件与当前动效底座，没有增加依赖或新组件家族。正式 Web 与 APK 构建、签名、包完整性核对和发布已完成；Android 视觉、触屏或用户手感尚未验收。

用户本轮原话：“还能做更好更丰富覆盖更全吗我建议先深度看看我们审美库相关的”。承接原目标：“结合我们审美库给我们的 pocketworld 的动画系统重构，不过好像其实本来就没啥动画我们 pocketworld”。来源为当前对话；日期为2026-11-10，精确消息ID不可取得。用户随后明确授权：“去！全部交给你我相信你”。因此范围从研究/设计推进为全部 PW-M7–PW-M14 的实现、集成与交付。实施授权不记为新适配已获审美认可。原话批次记录更新于北京时间2026-11-10 19:50；精确消息ID不可取得。

本研究基于 `f4a4c539227eff973df839a0149bc238a7f32545`，包含上一轮动效实现 `49e4512490612168acc3a490d37fa17b70a86857`。研究阶段只改变文档与设计入口；以下第一至第五节保留当时的来源、判断与采用合同。实施从主线 `5b470fc6` 开始，随后集成 `4f50c7b6` 的整包目录/创建流程、手机及执行修正。当前实现与证据在第六节维护。没有编写或运行测试；APK 的触摸、帧率、输入法与动效品质尚未在本轮实机确认。候选设计没有被记为用户审美认可。

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

本表是研究基线快照，保留改动前的具体差距；当前落地情况见第六节。差距分为“已有能力未充分消费”和“需要增加业务表现连接”，不把源码静态推断写成已复现运行故障。

| 旅程 | 研究时已有，继续保留 | 本轮采用重点 | 源码入口 |
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

### 研究时的三个具体缺口（已进入本轮实现）

**后台工作只动了箭头。** `ConversationWork` 的内容列表通过 `hidden={!expanded}` 直接显隐；状态改变还会让同一 task ID 进入不同父分支。下一轮应连接内容高度、任务身份、分组和状态文字，不能只给根节点加一次淡入。

**步骤器原能力没有全部进入真实轨迹入口。** `traceSteps` 将父子记录摊平成带 depth 的 rows，不传内嵌 tasks；ExecutionSteps 内嵌子步骤的 grid 开合在此入口没有对应内容。结构改变时 `_build()` 重建，普通更新也重写 glyph / label；耗时通过 `digitWindow.textContent` 更新，保留的 `.digit` 入场 CSS 不会因此生效。现有时钟和状态样式确实存在，但不能据此宣称真实树展开已完整连续。优先做稳定 ID 局部更新与真实层级变化；精确耗时仍取记录，不为制造运动添加计时器。

**全局动效能力没有覆盖全部独立实现。** `library/agents.tsx` 的 TreeChildren 仍用固定280ms计时卸载；`feedback.tsx` 的 BlurCrossfade 用固定420ms清除；`adapted/agent-motion.tsx` 的 FLIP 在 revision 变化时取消旧动画，减动效采用即时读取而非途中订阅。它们有自己的合理来源，但需要逐实现补中断/立即落定/隐藏清理；不能凭全局 token 或 CSS 宣称统一完成。这里是静态审阅发现，实际表现仍须绑定构建后真实体验。

## 四、采用设计范围与可观察要求

这些条目承接现有 PW-M1–PW-M6，用户已授权全部实施。可观察要求保持不变，源码、构建和实际体验证据分别记录，不以实施授权新增组件家族批准。

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

### 本次实施分批

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

## 六、实现落点、证据与下一检查点

### 13组旅程的当前实现

| 旅程 / 要求 | 本轮实际落地 | 边界与剩余体验证据 |
| --- | --- | --- |
| 聊天等待→流式→正式回复 / M7 | 新 `ConversationReply.tsx` 把原生 request、task、run、epoch、answer 回执精确匹配为同一展示行；连续 `CitedAnswer` 保留正文节点，来源、附件和操作补入原位置；活动条就地收束。 | receipt 的 coordinatorEpoch 必须与 UiReplyContext 一致，缺失/不一致不合并；重复 request 也退回独立消息。旧阅读锚点不改，不按文字相似度猜同一回复。 |
| 后台工作 / M8–M9 | 单树 `MotionDisclosure` 连接展开高度、状态和任务换组；摘要/任务入口被真实移除后，焦点依次落到同任务、摘要、其他可见入口或原输入框。 | 收起立即 inert；主动移出的焦点不拉回；没有为离场保留可操作旧任务。 |
| 任务列表 / M9 | 使用真实 task ID 连接需处理、进行中、暂停和结束分组；新任务局部到达，邻项让位，状态回执原位变化；保存当前焦点去向。 | 搜索/筛选/更早历史改变属于身份边界，读取期间静默建立基线。 |
| 轨迹、步骤与结果 / M8、M12–M13 | `traceSteps` 输出真实嵌套 children；ExecutionSteps 按稳定 ID 复用节点、更新当前状态和实测脊线；当前/未完成/错误分支默认展开，手动折叠独立保存。TaskOutcome 正文、图像、文件按真实 ID 到达。 | 无演示完成时钟；历史回填不播放新事件入场；实时数字不逐帧触发整树变化；过期截图即时撤下。 |
| 团队与群组 / M9–M11 | 目录、定义、协作运行和群详情/编辑按层级进入返回；成员、消息、请求、成果和投递记录使用原 ID；真实操作回执局部反馈。 | 团队必须等 viewHandle、群任务必须读到 known 后才建立活动基线；正文增量不作为列表 revision；保留原草稿、引用、未知结果及投递边界。 |
| 项目、文件与历史 / M9–M12 | 当前项目文件刷新保留宿主当前列表；列表↔网格按路径让位；文件历史→具体版本有深度；采用历史内容使用一个 inert 披露区，打开和取消恢复焦点；保存阶段局部更新。 | owner 包含 projectId 和外部搜索词；项目切换仍即时清理旧列表；异步读取旧版本不当作新版本入场；只保留一个草稿编辑器。 |
| 浏览器研究与来源 / M10–M12 | 研究/来源/页面切换，页面换组、原审批、研究结论、已存来源及下载条目局部变化；接管/交还/操作阶段与 toolbar 状态更新留在原位置。 | BrowserChrome 仅做当前内容透明度变化，保留原生 toolbar/inset 及真实进度，不平移 native sibling。 |
| 设备、手机与桌面 / M9–M13 | 设备目录→详情有深度与换组；连接与控制阶段原位反馈；手机首帧前后保留同尺寸画面框；桌面继续使用同一个 video，视口变化废弃旧触点，缩放帧任务可取消。 | 视频、手机真实帧不做克隆或补间。设备观看及项目 native preview 的祖先表面动画关闭，输入映射继续使用实际几何。 |
| 个人资料、偏好、记忆 / M9–M11 | 扩展原有内部层级；记忆、候选、历史与首页预览按真实 ID 跟踪；采纳/保存/删除/撤销及错误/核对回执局部反馈。 | 搜索、主题、候选和 presentation 变化重建身份，保留原草稿、来源披露与 Back/focus 合同。 |
| 数据设置与 AI 接入 / M10–M11 | 数据设置内部页沿单一 surface owner 进入返回；AI 服务、账号、模型和调度的层级、对象行及实际回执接续。 | 保留最新主线的空态/添加弹窗/返回入口；表单不双挂载，unknown 锁定不由动画解除。 |
| 通知 / M9、M11 | 通知按真实 seq 到达和让位；标记已读等待宿主确认后保留同条或后继焦点；未读数量、接收状态及真实回执局部更新。 | endpoint/筛选切换取消旧焦点请求；主线已改为 SettingsDialog 的配置层沿已有动效，不再叠第二个表面动画。 |
| 提醒 / M9–M11 | 规则换组、列表→详情、回执到达及操作反馈；返回按原规则 ID/索引寻找当前 DOM；刷新保留已读规则且锁定依赖当前读取的动作。 | 规则和回执使用互不重叠的 selector；历史回执读取、显示更早记录静默；读取失败明确展示上次内容与核对入口。 |
| 应用更新 / M11–M14 | 当前版本、实际阶段、进度区和原动作位置连续让位；检查、下载、暂停/恢复、系统交接按真实 phase 更新。 | downloadedBytes 不触发整块过渡；状态仍区分未知、取消请求、系统窗口待核对，不等待动画完成才进入真实状态。 |

### 共用机制与生命周期

`library/journey-motion.tsx` 沿现有 Affect FLIP 与已批准 Reaction Bar 的局部增删机制适配：首次/换 owner/换查询/读历史时只建立基线；真实对象变化才入场或让位。中断同时读当前位置和透明度，继续到最新目标。相关祖先/自身/子层滚动立即收束布局动画，避免与阅读滚动竞争；远距与屏幕外对象不跨屏飞行。移除对象立即服从业务，不复制或保留旧活 DOM。

单树披露沿已采用 Affect 开合关系，关闭立刻 inert/aria-hidden，视觉高度可以自然收束。当前回执只淡化新内容，不保存旧操作壳。采用现有 token 的140ms短反馈、220ms局部入场和280ms布局节奏；不按列表条数累加等待。动态 reduced motion、隐藏、恢复和卸载均收束到当前合法状态；恢复不补播过时变化。

旧独立实现同时收尾：Affect FLIP 从当前位移重定向并订阅运动偏好/可见性；TreeChildren 移除固定280ms卸载；BlurCrossfade 由真实 WAAPI 生命周期清除旧的纯展示层并即时 inert；ExecutionSteps 清理时钟/观察者/帧任务。Embla 8.6.0 保留原引擎，reInit 重建当前手势起点，touchcancel 返回原 snap，减动画与页面隐藏即时落定；不会只把 options.duration 设0却遗漏原引擎释放速度。

### 核对与构建状态

- 已完成三路隔离实现、主线程集成与只读交叉核对。具体修正包括：reply epoch 身份、外部文件搜索身份、工作入口移除后的焦点、快速状态更新的透明度接续、祖先滚动中断、Embla reInit 的旧手势清理。
- 已集成主线 `4f50c7b6`，保留新的 Solar/蓝灰、目录与创建流程、通知配置对话框，以及手机、浏览下载、上下文恢复和真实执行修正。
- 北京时间2026-11-10 19:50，正式 `npm run build` 在 `355d0e0f5ab56ab5fabe07925b926e55e04c6c5d` 完成：TypeScript 无诊断，Vite 构建2532模块，223个本地随包文件完整性核对通过。保留既有大chunk提示及 `client-only` 缺LICENSE文本提示，没有增加依赖或改写许可。最终 APK 构建/发布回执如下。
- 没有编写或运行任何测试，没有 mock host、fixture 或注入假任务/回执。CI 沿现有编译、构建、签名与包完整性流程；停用的测试和截图工作流保持停用。
- 当前环境没有可调用 Android 设备/模拟器或 adb/emulator，不能据源码或 Web 构建声称 Android 触屏、输入法、实际帧率、视觉及减动画手感通过。实际 APK 体验及用户效果反馈仍待取得；不阻塞独立的实现与构建工作。

### 正式发布与可恢复检查点

- 源码提交：[`ee9d6785b34ce9b402df1230688e61996c6f63a8`](https://github.com/wozhenbang2004/PocketWorld/commit/ee9d6785b34ce9b402df1230688e61996c6f63a8)，tree `08833ffa087b4cd6a9fdb26069283b9ee0eb7fc6`；基于 `f0741568` 发布，43个文件、1161行新增/336行删除。远端 tree 与集成后的本地 tree 完全一致，随后已从 origin 取回核对；无未提交产品代码。
- [正式发布 v0.1.86](https://github.com/wozhenbang2004/PocketWorld/releases/tag/v0.1.86)，北京时间 **2026-11-10 20:01:37** 完成；应用内部版本 **0.4.1086 / 1086**，ARM64 / Android 11+。
- [run 42811410376](https://github.com/wozhenbang2004/PocketWorld/actions/runs/42811410376)：build、prepare、publish 全部 success；正式 release 指向同一源码提交，非 draft、非 prerelease。
- [正式 APK](https://github.com/wozhenbang2004/PocketWorld/releases/download/v0.1.86/008e09a3e1d1220cd79c594ba5845e77c7ae8222823280df77c35a24a4777763-app-1086.apk)：**148,144,682 bytes**；SHA-256 `008e09a3e1d1220cd79c594ba5845e77c7ae8222823280df77c35a24a4777763`。release 资产 digest/大小与 CI 原身份回执逐项一致；本轮还产生1085→1086的1,699,259-byte增量包。
- 稳定项目证书 SHA-256：`b58e014e2a2e49b2e85ebd5b47a034a28cb0d313d71f379a5208d21afe388e58`。实际核对 assembleDebug、APK签名、ZIP对齐/完整性、打包Web清单与hash、原生ABI/目录hash及显示助手DEX。
- 轻量构建证据 artifact `13762705953` 已取回读取 `apk-identity.json`、`package.txt` 与 native receipt；ZIP SHA-256 `f8550b1570b96ad39ed781e15664950edec87299e09d46203b1512cb6764ccd2`。回执明确 `device_install_or_runtime_test: not performed`，未把交叉编译或包完整性检查写成设备运行验证。

### 下一确切动作

实现、集成、构建与发布已完成。下一有效证据来自真实 APK 体验或用户反馈，优先查看一条完整链路：发送→等待/流式→正式答复→执行步骤→返回；再查看任务换组、通知标记已读、文件历史采用/取消及设备观看。未实际到达的路径继续标未体验，不新增模拟环境或测试资产。

本报告是覆盖、差距及采用状态的唯一维护位置，`DESIGN.md` 只保留入口。Morphing Dialog/Popover、额外拖拽和其他新视觉家族继续保留为候选，没有偷偷纳入本轮，也没有把实施授权写成用户审美认可。
