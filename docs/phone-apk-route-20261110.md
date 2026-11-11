# 手机本机 APK 开发：可行路线与未交付边界

研究时间：2026-11-10 UTC。承接“手机本机开发真正可用”的要求；本切片不推 main、不安装依赖、不写或运行测试、不真机。它不替代此前私人项目编辑、原 Agent 任务、原回执与正式版本采纳链。

## 结论

**不依赖 Termux 固定路径的 ARM64 Android 构建路线真实存在，但 PocketWorld 目前没有完成 APK 工具链或安装闭环。** 推荐继续复用原 Ubuntu 24 ARM64/glibc/PRoot 环境，增加可选、独立计费空间的工具链 generation，而不是降低 targetSdk、修改系统安全配置、把 Termux 私有路径硬写进项目，或悄悄切到远端。

当前可交付的是源码级前置条件、构建意图和私钥排除修正。以下组合是**待验证候选**，不是已支持或已在手机跑通的组合：Temurin JDK 17.0.21+9 Linux aarch64、Gradle 8.9、AGP 8.7.3、API 35、显式 Build Tools 35.0.1，以及 ARM64/glibc 的 AAPT2/AIDL/zipalign/split-select。AGP 8.7 官方矩阵是 JDK 17、Gradle 8.9、最高 API 35，默认/最低 Build Tools 34.0.0，不能把这里选择 35.0.1 写成官方认证。旧 AGP/Gradle 候选在正式供货前还要重查安全公告，不自动降级用户已有项目。

首个可验证产品范围应是**无 NDK 的普通 Java Android application**，随后按项目扩展 Kotlin/Compose。不把 Flutter、React Native、C++、任意 Gradle 插件、任意 SDK/AGP 组合、PocketWorld 自身含 JNI/PRoot 的构建纳入这个小范围。未达到上述完整回执和真实构建证据前，UI 不出现“APK 环境就绪”。

## 已读源码与取舍

| 路线 | 一手证据 | 采用与拒绝 |
| --- | --- | --- |
| Commit451 glibc ARM64 build-tools | 发布 35.0.1 对应 commit `51138264630dad250b514b7796e47d700fdad38e`，实际 Docker 是 Debian 12 / glibc 2.36 / GCC 12.2，CMake + aarch64-linux-gnu；四个可执行文件有 GitHub release SHA256 | 最匹配现 Ubuntu 24/glibc。采用源码/固定产物思路，不执行其无 SHA 验证的 install.sh，不用 latest。其 README 所称 AGP 8 只用 SDK 内 AAPT2 不可靠，AGP 8 的实际 Maven AAPT2 路径仍需 override。四工具不是 JDK/完整 SDK/NDK。 |
| lzhiyong/android-sdk-tools | commit `fe637bf075f1b734b4b01d281f9f58899245ed92`，默认 NDK/bionic 静态程序；新 Zig musl/glibc 路线标实验。35.0.2 aarch64 ZIP 14,401,170 B，GitHub digest 缺失，发布流程可能覆盖资产/移动 tag | 源码可重编，且不必依赖 Termux prefix。但不能仅凭 tag 宣称可复现或直接信任历史 ZIP；不是本次默认二进制来源。 |
| hamza72x/android-sdk-linux-arm64 | commit `0e1d2404dad69ed30b435d70c6622c00b323ace6`，真实 glibc 工具，加 Flutter 定制的 strip/CMake/NDK shim | 四工具思路可比；拒绝以 shim 代替 NDK 编译，尤其不能用来称 PocketWorld 自身可在手机构建。 |
| ART/ECJ/D8 免 JDK | kosei commit `9d55d4ab66b0fa12466b03ac676f3fb2ae0188ca`，源码通过 dalvikvm 调用 ECJ、D8、apksigner；原生资源工具仍来自 Termux | 对小 Java 项目有价值，较轻；不支持普通 Gradle/Kotlin/Compose/AAR 依赖解析的同等范围。安装器下载 debug.pk8 并用于签名，这种共享身份不可采用。存在缓存只看文件存在、跨 Android ART 行为/源码许可证等未核项，不直接移植成我们的生产链。 |
| 官方 AOSP ARM64 prebuilts | 已发现 build-tools commit `2d62c3bd70f906a8983c3d1d821880bba24a71bc` 中 linux-arm64 目录/清单，以及 clang host/linux-arm64 仓库 | 不能沿用“Google 从无 ARM64 工具”的过时断言。但该目录不是已证明的 SDK Manager ARM64 产品，进一步目录读取未成功，未确认四工具、SDK 包和兼容组合，不作为可下载安装依据。 |
| Box64/QEMU/远程构建 | 社区存在翻译 x86 工具或远端编译路线 | 非默认。root/binfmt 修改不是普通 App 权限；QEMU 多一层运行开销/故障面。远端会传源码并改变执行地点，须用户明确选择，不冒充本机。 |

没有下载、执行或安装上述二进制。GitHub digest 只是服务返回的发布资产身份，**尚未在本机验证其字节、ELF interpreter、NEEDED 库、ABI/符号版本、daemon 模式或 Android/PRoot 运行**。发布工作流先生成 tar、后 strip 离散二进制，不能把离散文件 hash 直接当成 tar 内文件 hash。

## 来源、成本和许可

候选具体元数据见同目录 `phone-apk-candidate-20261110.json`，它是研究材料，不在 APK assets/runtime catalog 中，不可用来授予安装/执行能力。

- Temurin aarch64 JDK 17.0.21+9：192,336,814 B；Gradle 8.9 binary ZIP：136,114,148 B；四个离散 ARM64 工具合计 8,940,592 B。JDK/Gradle 采用发布者固定版本及校验来源；JDK 签名资产存在但本轮未验证签名。
- SDK feed 的 F-Droid 透明日志镜像显示 Linux Build Tools 35.0.1 为 61,959,297 B，Platform 35 r02 为 64,273,788 B。镜像给的是 SHA-1，且本轮没能读取 Google 原 feed，所以两项仍为**未完成固定 SHA256/官方内容核验**，不能进安装 catalog。不要从搜索摘要猜 URL，镜像里的 Linux 文件名是 `build-tools_r35.0.1_linux.zip`。
- 上述原始下载合计约 **442.1 MiB**，还不含现有基本环境约 58.1 MiB、AGP/Kotlin/Maven 项目依赖、签名工具附加项或安装临时文件。展开量、同时保存旧 generation 的峰值、项目缓存和产物都未测量，不能拿基本环境约 797 MiB 最低空间当 APK 总预算。产品应由真实 archive/expanded 清单加缓存/旧版保留预算计算，不先写死“1GB 足够”。
- [Google SDK 条款](https://developer.android.com/studio/terms) 2 节要求接受后使用；3.4 对修改/再分发有限制，3.5 将开源组件交给各自开源许可。不能因为构建胶水是 MIT 就把 Google SDK、AOSP、JDK 和第三方库统一当 MIT。保留组件 notices；优先由手机直接从供应商下载经明确审阅的 SDK，不把未知权利的合并 SDK 放进仓库/APK。
- 本次没有接受 SDK 条款、运行 `yes | sdkmanager --licenses`、上传源码、保存凭据或同意任何权限。可选工具链的原生审阅应同时展示来源、总下载/空间、各许可和联网范围，不暗中接受协议。

## 首次、重开、离线的真实闭环设计

1. **首次编辑**：创建/选择项目、私人副本、Agent 目标、原文件 SHA-CAS 保存及正式版本采纳沿 stage2–4。纯编辑不要求先装数百 MiB SDK。
2. **第一次选择 APK 构建**：读同一 sourceDigest 的实际项目。显示可见模块/版本线索和缺口，不能把 regex 当 Gradle 解析器。用户明确选择 application 模块、variant、是否联网和输出，不猜 release、不自动发布。安装是单独审批，不等于项目运行审批。
3. **安装与维护**：沿 RuntimeSetupHost 原 owner task/receipt、catalog identity、stage/probe/seal generation、保留旧版路径；新增完整 SDK profile 身份和验证后才登记工具。不能另造一份任务账本，或在现 active rootfs 内 apt 安装后沿用旧 hash。空间不足/下载中断保留已知旧版；损坏缓存仅按原身份修复，不浮动升级。
4. **执行**：沿原 plan/TaskStore/CapabilitySnapshot admission，冻结源码、toolchain generation、精确 argv、模块/variant 与联网影响。实际使用原生核验过的 JDK/Gradle/AAPT2，不把私有绝对路径提交到项目。`dependencies` 不保证缓存全部 build artifacts；`assemble` 不主动请求 `check/test`，但构建脚本仍能添加任意依赖任务，不能保证“不执行测试”。首次 Gradle 下载/构建可能超过现普通命令 5 分钟，若产品扩展必须使用原受控期限/前台观察合同，不能悄悄无限运行。
5. **产物与身份**：成功进程退出也不是 APK 可安装证明。按同 sourceDigest、原 job/顺序、真实文件 hash 固定输出，校验 APK 结构/签名/包名/版本/SDK，再由主人决定签名或安装。APK 不是网页预览。Android PackageInstaller 的系统确认和安装回执是独立一步，安装也不证明业务运行。
6. **密钥**：调试密钥每个主人/项目独立生成，放在不进入源码/Git/团队/备份包的私有密钥域。下载来的通用 debug.pk8 不能证明用户发布身份。密码不进 argv、环境、日志、模型/团队资料。release 不自动签；AndroidKeyStore + apksig 是候选，不是现有已完成能力，当前依赖中也未见 apksig。密钥备份/恢复必须单独设计授权，丢失不可悄悄换钥冒充更新原 App。
7. **退出重开**：复用原 task/execution 查询，不重新启动未知构建/签名/安装；恢复项目与原工具链引用，旧 generation 不能偷偷换成新版。**离线**只允许已完整、已核验并具所需依赖缓存的组合；未缓存显示具体缺项，不能报告准备成功后才悄悄联网。卸载应用清空私有工具链/密钥的风险必须向主人说明。

## 本次独立源码修正

- `ProjectAndroidPreflight` 接入原 `ProjectBuildPlan.inspect` 和原 `project_inspect` 返回。只读现 sourceDigest 的 manifest/plugin/SDK 字面量、原生编译线索、wrapper 声明、原工具观察；显式 `apkCapabilityVerified=false`、不解析 build graph、不执行工具，不回显任意 URL、机器路径、签名密码。注释/动态表达式/别名的误检与漏检明确保留为未知。
- Gradle `build` 意图改为 `--no-daemon assemble`，不再默认触发 Gradle 生命周期 `build -> check/test`。显式原 `test` 意图保持不变，本轮没有写/运行测试。未增加 runner、wrapper 自动下载、权限或任务状态。
- `ProjectPackageArchive.excluded` 原单一进出包/成果策略新增 `.android`、PK8/PKCS8/JCEKS/BKS 等私钥/密钥库、签名 properties 和常见备份后缀。不会删除文件；明确选中被排除文件时仍由原入口拒绝。既有含这些文件的包现在不能导入是有意的安全收紧。它是已知文件名规则，**不声称能发现任意改名或硬编码在源码中的秘密，也不保护用户自行运行的 git add/push**。

不改 frozen stage1–4、统一候选、App.tsx/Bridge/UI。仅源码读写和研究，没有编译、测试、设备、签名、APK 构建或安装结果。

## 未交付项与继续实施条件

1. 完整 SDK/JDK/Gradle profile 的官方字节身份、许可、展开量、原生可执行加载/版本验证、原 setup 生命周期集成。只有“有安装按钮”不满足此项。
2. **Android 模块输出与源隔离**：`ProjectSources.sourcePath` 目前仅排根 build/dist，模块 `app/build` 仍可能进入源码清单；直接全局排所有名为 build 的目录又会误删合法源码范围。应在原完整树/源码策略统一支持明确生成根，连同 baseline、编辑、导回、包和 Agent 查询一致，而非只在 UI 隐藏。
3. **大 APK 成果**：`ProjectManagedOutput.MAX_BYTES` 为 2MiB，且目前持 byte[]；需要原附件 catalog 的有界流式保存/身份核验方案，不能简单把内存限制调到几百 MiB。
4. APK 解析、签名、密钥持久/迁移、PackageInstaller owner handoff 与回执恢复。未签/未验签输出不得标为安装成功。
5. 真实组装与手机运行证据。当前明确禁止测试/安装依赖/真机，源码补丁不可能替代这些证据；这些是交付边界，不是“研究认为可行所以已完成”。

## 可回读来源

- [AGP 8.7 官方矩阵](https://developer.android.com/build/releases/agp-8-7-0-release-notes)
- [官方 AAPT2 与 Maven 机制](https://developer.android.com/tools/aapt2)
- [Gradle 官方 checksum](https://gradle.org/release-checksums/) 与 [wrapper JAR 验证](https://docs.gradle.org/current/userguide/gradle_wrapper.html)
- [Commit451 固定发布源码](https://github.com/Commit451/android-arm-build-tools/tree/51138264630dad250b514b7796e47d700fdad38e)、[发布资产](https://github.com/Commit451/android-arm-build-tools/releases/tag/platform-tools-35.0.1)
- [Temurin 固定发布](https://github.com/adoptium/temurin17-binaries/releases/tag/jdk-17.0.21%2B9)
- [SDK 透明日志固定镜像](https://github.com/f-droid/android-sdk-transparency-log/blob/dae05c5c31b45d5ef52a90af7603881d0805e238/android/repository/repository2-3.xml)
- [lzhiyong 源码](https://github.com/lzhiyong/android-sdk-tools/tree/fe637bf075f1b734b4b01d281f9f58899245ed92)、[hamza shim 路线](https://github.com/hamza72x/android-sdk-linux-arm64/tree/0e1d2404dad69ed30b435d70c6622c00b323ace6)
- [kosei 原调用链](https://github.com/farhaanaliii/kosei/blob/9d55d4ab66b0fa12466b03ac676f3fb2ae0188ca/src/kosei/dalvikvm.py)、[安装源码](https://github.com/farhaanaliii/kosei/blob/9d55d4ab66b0fa12466b03ac676f3fb2ae0188ca/src/kosei/setup.py)
- [AOSP ARM64 目录证据](https://android.googlesource.com/platform/prebuilts/build-tools/+/2d62c3bd70f906a8983c3d1d821880bba24a71bc/)
