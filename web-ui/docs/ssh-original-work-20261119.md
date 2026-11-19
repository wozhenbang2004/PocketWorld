# 已配置主机的原工作

原断点：`SshOwnerWorkflows.startTask → bindNewAttempt → TaskStore.bindSshSelection` 把主机选择绑定到 **私人 assistant 任务** 的 `sshSelection`，不是 feature=ssh。旧主机历史仅在首页最近 200 条里按 `feature=ssh + profileId` 筛选：既漏掉 Agent 原委托，也可能在同配置编号改绑后误列旧服务器的命令。

新视图只读手机本地的 TaskStore。每页最多八条；通过现有任务摘要游标查看更早记录，单次不超过 64 个标题和 16 个有限原记录。仅接受原 `sshSelection.id/bindingHash`，或原生 `sshNativeRequest` 中一致的操作、配置和运行身份。配置版本包含原主机、账号、目录、主机密钥及配置/凭据代次。只有编号、共享账号、当前目录或相似文件名均不构成匹配。没有完整绑定的旧记录不展示；绝不为了兼容而重归类。页游标使用短期本机句柄，不把不相干任务 ID 交给网页。重新打开时再验证当前保存配置与原私人任务/运行/代次，旧页不跨服务器复用。

命令退出码只是那条原命令的回执；无回执/待核对不等于失败或远端已停。另一次手动 SFTP 拉回只在它自己的原传输任务中显示：核对 attachment 的来源 task/operation/profile/binding/path 和选定 SHA/大小，不将同目录文件冒认为构建产物，也不声称附件现在仍可打开。本页不连接主机、调用模型、读取凭据/文件内容、改变授权、执行命令或重试。

[DSH-Go 原连接与主机校验](https://github.com/ccch1mneyyy/dsh-Go/blob/9ad11167c53a5da12ff8791c21f4327f5d8a9a25/app/lib/remote/ssh/ssh_link.dart#L207-L257) 和 [OpenHands 的环境命令与文件下载](https://github.com/OpenHands/software-agent-sdk/blob/141423921fdaeb08cd5f53464a69e860da70b66f/openhands-sdk/openhands/sdk/workspace/remote/base.py#L165-L232) 同样区分目标和操作；不复用它们的 SSH 命令、网络会话或远端运行服务。本设计沿用 [15 系统](../DESIGN.md)：原主机目录→单列工作→原任务；连接和状态优先，完整运行编号按需展开，无伪进度或远端“在线”徽章。设备视觉与真实会话不以源码验证代替。
