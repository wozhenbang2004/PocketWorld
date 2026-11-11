/** Presentation metadata only. A definition is not an installed, mounted or authorized tool. */
export type ToolDecision = 'allow' | 'ask' | 'deny' | 'review';
export type ToolPolicy = { version: 2; mode: 'custom' | 'auto_review' | 'full_access'; tools: Record<string, ToolDecision>; instructions: string };
export type PolicySnapshot = { revision: number; conversationId: string; global: ToolPolicy; conversation: ToolPolicy | null; effective: ToolPolicy };
export type CatalogTool = { name: string; category: string; description: string; source: string; configurable: boolean };
export type ToolCatalog = { tools: CatalogTool[]; notices: string[]; metadataOnly?: boolean; executionAuthorized?: boolean };
export const categoryLabels: Record<string, string> = {
  phone: '操作手机', browser: '浏览网页', ssh: '连接服务器', attachments: '处理文件与文档',
  memory: '查找知识与历史', skills: '使用技能', workspace: '管理文件与成果', project: '开发项目',
  remote: '在电脑上工作', devices: '跨设备工作', media: '创作图片与音视频', semantic: '日历、时钟与 App 入口',
  teams: '让团队协作', automation: '安排后续工作', assistant: '私人管家', plugins: '扩展工具',
  run: '组织任务步骤', uncatalogued: '仅有保存的规则', task_record: '原任务登记',
};
export const decisionLabels: Record<ToolDecision, string> = { allow: '直接允许', ask: '询问我', deny: '禁止', review: 'AI 审批' };
export const toolSource = (tool: CatalogTool) => tool.source === 'installed_plugin' ? '已注册插件' : tool.source === 'saved_rule' ? '保存的工具名，目录未登记' : tool.source === 'original_task' ? '原任务登记，目录未发现' : tool.source === 'task_bound' ? '按任务接入的内置定义' : '内置注册定义';
const titles: Record<string, string> = {
  semantic_discover: '查找日历、时钟与 App 入口', semantic_dispatch: '执行已核对的日历或 App 操作',
  memory_search: '检索私人记忆', memory_recall: '召回记忆与来源', memory_raw_query: '查找原始任务记录', memory_raw_read: '读取原始任务记录',
  skills_list: '发现技能', skills_read: '阅读准确版本的技能', skills_execute: '准备本次技能脚本执行',
  workspace_projects: '列出本地项目', workspace_list: '浏览项目文件', workspace_read: '读取项目文件', workspace_write: '保存项目文件',
  workspace_create: '创建项目', workspace_artifacts: '查找文件成果', workspace_read_artifact: '读取冻结成果',
  attachments_list: '查找已导入附件', attachments_read_text: '读取附件文字', attachments_create_document: '生成文档附件',
  schedule_propose: '提出定时工作草案', schedule_list: '查看本轮计划草案', post_notice: '提交原任务通知',
  teams_list: '查看已有团队', teams_plan: '提出团队工作计划', teams_start: '准备启动团队', teams_get: '读取团队进展与成果',
  teams_message: '补充团队工作信息', teams_control: '控制原团队工作', teams_wait: '等待原团队进展',
  remote_profiles: '查看电脑连接', remote_environments: '查看电脑执行环境', remote_read_file: '读取电脑工作区文件',
  remote_list_jobs: '查找电脑已有任务', remote_get_job: '读取电脑任务结果', remote_probe_worker: '探测电脑执行器',
  owner_work_start: '交给后台管家继续工作', owner_work_list: '查看管家接手的工作', owner_work_get: '读取原工作结果',
  device_invoke_tool: '调用已授权设备工具', device_delegate_goal: '向原设备委派任务', device_operation_get: '查询原设备操作',
  media_generate_image: '生成图片', media_edit_image: '编辑图片', media_generate_video: '生成视频', media_synthesize_speech: '合成语音', media_read_original: '读取媒体原件',
};
const browserTitles: Record<string, string> = {
  open: '打开任务网页', select: '切换任务标签页', observe: '读取网页结构', request_user: '请主人接管网页', navigate: '前往网址',
  click: '点击网页目标', type: '输入网页文字', set_date: '设置日期', focus: '聚焦网页目标', press: '发送按键', select_option: '选择网页选项',
  set_checked: '切换勾选', scroll: '滚动网页', wait: '等待网页变化', download: '请求网页下载', download_status: '查询原下载',
  retain_source: '保留网页来源', close: '关闭任务标签页', finish: '结束本次浏览', observe_visual: '观察网页截图', evaluate: '读取页面表达式',
  inspect: '检查网页对象', script: '执行受限页面程序', network: '查看网页网络记录', network_body: '读取原网络响应',
  download_document: '保存网页文档', prepare_sequence: '准备连续网页动作', native_input: '发送原生网页输入', site_mode: '切换网站显示模式',
  resize: '调整任务网页尺寸', back: '网页后退', forward: '网页前进', reload: '重新载入网页', upload_artifact: '上传已选成果',
};
export function toolTitle(tool: CatalogTool): string {
  if (titles[tool.name]) return titles[tool.name];
  if (tool.name.startsWith('browser_task_')) return browserTitles[tool.name.slice(13)] || tool.name;
  const sentence = tool.description.split(/[。；;\n]/)[0].trim();
  return sentence && sentence.length <= 42 ? sentence : tool.name;
}
export function ruleSummary(policy: ToolPolicy, global: ToolPolicy, scope: 'global' | 'conversation', name: string): string {
  if (Object.prototype.hasOwnProperty.call(policy.tools, name)) return `${scope === 'global' ? '全局规则' : '此对话规则'} · ${decisionLabels[policy.tools[name]]}`;
  if (scope === 'conversation' && global.tools[name]) return `继承全局规则 · ${decisionLabels[global.tools[name]]}`;
  return policy.mode === 'full_access' ? '默认模式 · 直接允许可配置审批' : policy.mode === 'auto_review' ? '默认模式 · 更改类动作由 AI 审批' : '默认模式 · 沿用原生要求';
}
