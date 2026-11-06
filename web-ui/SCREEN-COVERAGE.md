# 逐界面设计覆盖

2026-11-06：本轮逐业务路由完成层级改造与复核；不能用共享组件升级替代逐页检查。覆盖 381 个主要与子场景，包含列表、详情、编辑、核对、来源阅读与关键异常。场景数不是独立页面数量，也不表示所有设备与数据组合均已穷尽。

用户原话与采用规则见 [DESIGN.md](DESIGN.md#逐界面层级与格式塔改造--2026-11-06-后续要求)。六域实施与交叉审查发现的主任务后置、操作裁切、真实Button内部标签对齐、主体身份折叠等问题均已对应修正。已适合任务的连续阅读、受控审批和轻量操作保留原结构，不为改动数量重写。

| 范围 | 记录场景 | 逐项证据 |
| --- | ---: | --- |
| 对话、任务与项目 | 7 | [coverage-primary.json](tests/coverage-primary.json) |
| 设置与模型 | 108 | [coverage-settings.json](tests/coverage-settings.json) |
| 记忆与持续目标 | 64 | [coverage-knowledge.json](tests/coverage-knowledge.json) |
| 协作与设备 | 58 | [coverage-collaboration.json](tests/coverage-collaboration.json) |
| 文件、成果与开发 | 23 | [coverage-artifacts.json](tests/coverage-artifacts.json) |
| 技能、插件与模型选择 | 51 | [coverage-ecosystem.json](tests/coverage-ecosystem.json) |
| 浏览器、启动与通话 | 51 | [coverage-browser.json](tests/coverage-browser.json) |
| 阅读、审批与临时操作 | 15 | [coverage-overlays.json](tests/coverage-overlays.json) |
| 路线参考与托管会话 | 4 | [coverage-reference-sessions.json](tests/coverage-reference-sessions.json) |

每份清单保留原问题、主任务、成熟来源、修改或保留决定、实际截图与验证边界。前后对照以已发布 main e86952a（本地同内容379911a）为主要基线；补充路线参考/托管会话以本域0f36884的未改动同一组件为before，具体身份另列。未改动浮层只有当前图，不伪造before。

实际业务路由核对：App的对话/任务/项目主入口与Secondary各域均有对应记录；额外补齐路线参考与托管会话。Android系统设置、文件选择器、系统分享等外部系统界面不计为自绘改造。

## 验证与图证

- 实际React组件使用显式隔离宿主数据；每个场景核对目标内容或对象、活动前景层、稳定帧、文字与横向边界。对照覆盖360与1280宽度；少数子场景仅手机图，宽度逐项列明。
- 当前生产Web包的三个主入口、任务详情、项目切换/创建、九项阅读旅程与原有管家完整旅程通过；引用、来源返回、待处理禁用、原问题单次回执等真实React操作通过。
- 全量688项Node测试、TypeScript与Web整包构建通过；模块独立回归见各清单。原生字体5项内存SharedPreferences契约通过，不能代替真实Android磁盘和重启。
- 图册生成器按清单复制原图字节，有声明哈希时核对身份；手机预览可筛选，完整ZIP保留全部声明截图和宽屏对照。保留场景显式标记。
- 这些截图与静态检查不证明Android真机、真实服务执行、计费或动效时序。完整Android编译仍受本地原有AGP8.7.3解析限制；没有新APK或真机验收结论。

截图交付：`PocketWorld-界面预览.html`（代表页面），`PocketWorld-全场景截图对照.zip`（完整原图与逐场景记录）。
