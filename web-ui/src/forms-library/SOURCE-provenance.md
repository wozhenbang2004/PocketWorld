# Source provenance

Research captured 2026-11-01T03:06:12+00:00. This is a personal source-aware study, not an official BoardUI Pro distribution.

| Material | Fixed source | Identity / license |
| --- | --- | --- |
| BoardUI free source | https://github.com/BoardUI/boardui/tree/ec5c5eb3e29c2441be63b9422cd54f411ef23466 | MIT, copyright Mertcan Dundar Esmergul / BoardUI, original LICENSE retained |
| BoardUI live design catalogue | https://www.boardui.com/zh-hans/components | Visible Pro navigation inventoried: 36 entries; marketing counts and public repository can differ |
| Allo storyboard | https://x.com/BuffetDesigns/status/2115882400869933563 | Public author image, retained as reference; no original project license inferred |
| Allo public launch | https://x.com/allophonesystem/status/2115793573589258279 | Public animation, 20.233s observed in browser; blob video download unsupported here |
| Author process reply | https://x.com/BuffetDesigns/status/2116211211272675586 | Figma frames, After Effects manual animation; no original source package found in inspected material |
| Stageshow discovery | https://x.com/op7418/status/2116711094622593292 | User-selected public reference; source and visible discussion reviewed |
| Stageshow kit / examples | https://github.com/benjitaylor/skills/tree/802057409787133703c56001080169e347305572/stageshow | MIT source; NOTICE and per-asset sources remain applicable |

Read scope: BoardUI homepage, current catalogue, free source, 17 public chart documents, composer panel, agent creator, project-board public interface, finance template preview; other template layouts also use public catalogue descriptions and the existing design language. Do not infer full author walkthroughs or pixel-equivalence for all entries.

Our changes: added study/Pro/Stageshow routes; 36 independent implementations with business-context adapters; independent Allo SVG Web Component and timeline; local launch and documentation. BoardUI base components remain source reuse. Stageshow kit and the three selected examples remain byte-identical to the pinned source at QA time.

Alternatives considered: BoardUI original free source (adopt); purchasing Pro (user explicitly chose independent reproduction); generic shadcn restyling (would lose the selected design language); mobile Skia Grok Orb and generic robot Web Components (different rendering target and character, not the Allo source). Native SVG is sufficient for this small web character; Recharts and React Aria supply mature chart/control behavior.

Independent-study files are supplied as reusable code; reference brands, names, screenshots and third-party artwork retain their owners' rights. Consult upstream LICENSE / NOTICE before commercial redistribution.

## 2026-11-01 全目录补齐与布局还原增量

本节更新前述初次研究检查点，不将旧的“69免费/36Pro”当当前视觉全集。官网可见主目录/侧栏/页脚与 https://www.boardui.com/r/registry.json 及 https://www.boardui.com/r/registry-pro.json 交叉核对，去除内部helpers和重复安装别名后共101视觉条目：64free+37Pro。免费88安装单元的全部路径已逐项检查存在；18项新控件及新增preview wrappers从公开r/<name>.json取得，实际JSON快照保留vendor/boardui-registry-20261101。旧源差异先审阅、备份，必要依赖升级到当前免费原版；ContextMenu回调签名与TemplateCopy子数组key有明确兼容性调整，未冒称每个交付文件均与旧Git pin字节相同。

完整目录现在全部使用本站入口，64免费详情直接运行真实源并显示实际完整主文件；此前旧索引78项全部外链/微缩预览被链接覆盖的问题已纠正。九模板按真实原图的27条差异重做外壳、业务模块、空间与密度，另补公开HR模板 https://www.boardui.com/zh-hans/templates/hr 。当前比较图与逐条resolution见components/studies/template-comparison.json；旧版图保留public/comparison/before。

18字体来源及完整OFL、实际字重、字集范围和文件SHA在public/fonts/typography与typography-catalog.json。Stageshow方法选择性研究的依据为固定源码SKILL、craft、motion、framing、bento、rehearse、review与hero-grid/Stage；8方法/7边界保留于design-principles.json，不直接把演示插画的字数/裁切/时长约束升为真实产品规则。

新增侧栏参考 https://x.com/uiuxjosh/status/2116699922707685437 是三张静态图。已实际查看原帖/三图/可见回复，原工程与原创作者归属未证实；三组React交互为独立适配和推演，不宣称原版动画实测。真实原图URL和哈希随包保留references/josh-sidebars，代码未将含UI截图用作操作界面底图。

BoardUI演示画廊21张图片及资料封面来自实际可见img.currentSrc路径，均单独记录媒体来源，不把代码MIT扩成媒体授权。公开预览、源码原件、独立实现、实拍/交互检查和用户效果认可分别记录。
