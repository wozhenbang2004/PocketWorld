# 从已配置的 SSH 工作区将一个结果带回手机

此前文件行一律进入 256 KiB UTF-8 编辑器，不能从这里拿回 APK、ZIP 等实际字节。直接 SSH 工作区不等于手机项目副本或 Companion 的 job。用户先读取这台主机的目录，选择一个普通文件；这一步不打开原件。原目录项、配置版本、私人窗口都匹配后，用户可发起一次只读摘要核对。看到实际大小、SHA-256 和目的地后，另行确认将这个版本保存为手机的 PocketWorld 私人附件。没有上传、安装或自动同步。20 MiB 是单文件上限，不是“允许访问任意路径”；拒绝符号链接、隐藏路径和已知密钥文件。传输回执只证明保存了这些字节，不证明哪个构建产生了它。

原生已有链路：`FeatureHost` 登记独立的私人传输原任务，`SshNativeRequest` 与 SQL 核对原配置和准确版本，`DirectSshClient.BinaryRead` 在 EOF 检查流式 SHA/大小和源文件属性，`SshFileArtifacts` 与 `AttachmentBook` 才提交附件。网页只持有短时一次性核对编号。纯配置和上次目录不是实时联网成功；旧页面、版本变更、缺少摘要或缺少本机文件宿主不能保存。回执不完整先查同一主机的传输记录，不重放操作。

对照原始实现：[DSH-Go 主机核对和 attach-only 分支](https://github.com/ccch1mneyyy/dsh-Go/blob/9ad11167c53a5da12ff8791c21f4327f5d8a9a25/app/lib/remote/ssh/ssh_link.dart#L207-L268) 将可信连接和服务启动分开；[OpenHands Workspace](https://github.com/OpenHands/software-agent-sdk/blob/141423921fdaeb08cd5f53464a69e860da70b66f/openhands-sdk/openhands/sdk/workspace/remote/base.py#L165-L232) 区分执行命令与文件下载；[Hermes SSH](https://github.com/NousResearch/hermes-agent/blob/3235b4d6ab56c39089f70f95d76f801300be4733/tools/environments/ssh.py#L91-L150) 区分探测连接和可选文件同步。不借用它们的远端启动、终端或自动同步权限。

沿用 [15 系统基线](../DESIGN.md)：01–04 保留中性色、原弹层圆角、单列来源/目的与可折行摘要；05–07 原按钮在读取期间禁用，沿用对话框的焦点、进退动效和目录/文件图标；08 不给未知二进制伪造缩略图；09–11 返回所选主机下的目录，触屏/键盘/返回键共用原弹层，不扩展全局导航；12–15 区分目录大小、实测版本、保存回执和未知结果，慢传输只报等待，不伪造进度或构建成功。源码核对不代替手机上真实 SFTP、视觉与慢链路验收。
