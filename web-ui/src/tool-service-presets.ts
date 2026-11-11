/** Setup guidance, not installed definitions, health claims, grants or credentials. */
export const toolServicePresets = [
  {
    id: 'exa', name: 'Exa 全网搜索', purpose: '检索公开网页并读取来源正文',
    status: '可填入远程服务地址，仍需发现与逐项授权',
    endpoint: 'https://mcp.exa.ai/mcp', protocolVersion: '2025-11-25',
    destination: '使用搜索时，查询词和读取的网址会发送给 Exa；不会自动上传整个对话。不要把私密资料拼进查询。',
    preparation: '官方提供免 API key 的限额搜索和正文读取。预置与参数兼容仅经源码核对，尚未实连发现；工具是否可安装以本次发现为准。此入口不登录、不升级套餐、不启用付费 Agent。',
    source: 'https://exa.ai/docs/get-started/exa-mcp', sourceLabel: 'Exa 官方接入说明',
  },
  {
    id: 'gmail', name: 'Gmail 搜索、阅读与发送', purpose: '独立 OAuth 授权的本机 MCP 连接器',
    status: '连接器源码已提供，尚需准备依赖与本人账号授权',
    endpoint: '', protocolVersion: '2025-11-25',
    destination: '邮箱读取请求和获批邮件发送给 Google。连接器不经过额外邮件中转商。正文会作为本次工具结果提供给任务选用的模型。',
    preparation: '需要 POSIX Python 3.10+、连接器依赖、你自己的 Google Desktop OAuth client，以及稳定的私人状态目录。先由本人运行独立授权入口；默认只读，发送需另行开启并在原任务核对全部收件人和完整内容。没有代你注册账号或授权。',
    source: 'https://github.com/wozhenbang2004/PocketWorld/tree/main/connectors/gmail', sourceLabel: '查看连接器源码、准备步骤与限制',
  },
] as const;
