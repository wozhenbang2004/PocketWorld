import type { ReactNode } from 'react';
import { Button, SearchField } from './forms-library';
import { IconAccount, IconArchive, IconCheck, IconChevronRight, IconFileText, IconKey, IconLink, IconMicrophone, IconPalette, IconPhone, IconPrivacy, IconRefresh, IconSearch, IconServer, IconShieldCheck, IconStorage } from './icons';
import { WorkspaceSectionHeading } from './WorkspaceSectionHeading';
import './settings-directory.css';

type Item = { title: string; purpose: string; terms: string; icon: typeof IconKey };
const items = {
  'model-settings': { title: '模型与服务', purpose: '选择默认模型，连接 API 服务', terms: 'AI 接入 大模型 切换 提供商 API key 密钥 调度', icon: IconKey },
  accounts: { title: 'ChatGPT 账号', purpose: '登录、管理授权与套餐', terms: '帐号 账户 登录 登陆 重新授权 退出 订阅 套餐用量 OpenAI', icon: IconAccount },
  'phone:appearance': { title: '外观与字体', purpose: '更换主题，预览中文、英文和代码字体', terms: '深色模式 深色主题 浅色模式 跟随系统 暗色 夜间模式 黑色 白色 日间 英数 字型 换字体', icon: IconPalette },
  'phone:speech': { title: '语音与朗读', purpose: '设置听写、朗读和任务播报', terms: '声音 文字转语音 麦克风 语音引擎 TTS', icon: IconMicrophone },
  updates: { title: '应用更新', purpose: '查看当前版本、检查或下载更新', terms: '升级 安装 APK 更新日志 版本号 关于', icon: IconRefresh },
  usage: { title: '用量统计', purpose: '查看模型消耗、调用记录和预算', terms: '每日用量 Token 费用 上下文 调用明细 模型诊断', icon: IconFileText },
  storage: { title: '项目存储', purpose: '查看空间占用，选择要清理的内容', terms: '磁盘 空间 缓存 清理 释放 诊断导出', icon: IconStorage },
  archives: { title: '备份与迁移', purpose: '导出数据，或从备份恢复', terms: '迁机 原数据 还原 导入 数据备份', icon: IconArchive },
  'execution-policy': { title: '允许执行与自动任务', purpose: '查看运行总开关，核对停用的影响', terms: '全部停止并停用 停止接收新任务 停止任务 停止自动任务 暂停自动任务 关闭自动任务 自动化 关闭执行 启用 停用 执行总开关 运行状态', icon: IconShieldCheck },
  'phone:execution': { title: '系统权限', purpose: '管理界面读取、任务通知与无线调试', terms: '无障碍 授权 ADB 主屏控制', icon: IconShieldCheck },
  'phone:observation': { title: '观察与隐私', purpose: '管理本地取图、文字识别和截图留存', terms: '本地观察 截图 OCR 屏幕 识字', icon: IconPrivacy },
  background: { title: '后台运行', purpose: '管理后台连接、独立屏幕与音频', terms: '助手连接 配对 静音', icon: IconPhone },
  'web-search': { title: '联网搜索', purpose: '选择搜索服务与实时网页来源', terms: '网页搜索 搜索引擎 网络 提供方', icon: IconSearch },
  proxy: { title: '本机 API', purpose: '管理访问地址、客户端连接与近期调用', terms: '代理 本地服务 接口 端口 网关', icon: IconServer },
  setup: { title: '准备中心', purpose: '核对首次使用、模型和工具的准备状态', terms: '初始设置 手机权限 开发工具 就绪 能力准备', icon: IconCheck },
  'group:data': { title: '用量、存储与备份', purpose: '查看用量、清理项目或迁移数据', terms: '数据与应用 数据管理', icon: IconStorage },
  'group:privacy': { title: '权限与运行', purpose: '执行总开关、系统授权与隐私', terms: '运行设置 隐私设置', icon: IconPrivacy },
  'group:services': { title: '搜索与连接', purpose: '联网搜索、本机 API 与准备中心', terms: '高级连接 更多服务', icon: IconLink },
} satisfies Record<string, Item>;
export type SettingId = keyof typeof items;
type SettingsPosition = { page: string; query: string; scrollTop: number };
export type SettingsDirectoryOrigin = SettingsPosition & { parent?: SettingsPosition & { settingId?: SettingId } };

type Group = { title: string; entries: readonly SettingId[] };
const groups: Readonly<Record<string, Group | undefined>> = {
  'group:data': { title: items['group:data'].title, entries: ['usage', 'storage', 'archives', 'updates'] },
  'group:privacy': { title: items['group:privacy'].title, entries: ['phone:execution', 'phone:observation', 'background'] },
  'group:services': { title: items['group:services'].title, entries: ['web-search', 'proxy', 'setup'] },
  // Direct links from other workspaces and older hosts keep their original destinations.
  'group:models': { title: '模型与服务', entries: ['model-settings', 'accounts', 'web-search', 'proxy'] },
  'group:preferences': { title: '使用偏好', entries: ['phone:appearance', 'phone:speech', 'setup'] },
};
export const settingsGroupFor = (page: string) => Object.hasOwn(groups, page) ? groups[page] : undefined;
const home: readonly Group[] = [
  { title: '账号与模型', entries: ['model-settings', 'accounts'] },
  { title: '日常使用', entries: ['phone:appearance', 'phone:speech', 'updates'] },
  { title: '更多设置', entries: ['group:data', 'group:privacy', 'group:services'] },
];
const normalize = (value: string) => value.normalize('NFKC').trim().toLowerCase();

type Props = {
  page: string; query: string; onQueryChange: (value: string) => void;
  getStatus: (id: SettingId) => string; onOpen: (id: SettingId) => void;
  hasSetup: boolean; canOpenUpdates: boolean; executionControls?: ReactNode;
};

/** A directory, not another settings owner: opening a row never saves its value. */
export function SettingsDirectory({ page, query, onQueryChange, getStatus, onOpen, hasSetup, canOpenUpdates, executionControls }: Props) {
  const group = settingsGroupFor(page);
  const visible = (id: SettingId) => id !== 'setup' || hasSetup;
  const purpose = (id: SettingId) => id === 'group:services' && !hasSetup ? '联网搜索与本机 API' : items[id].purpose;
  const description = (id: SettingId) => id === 'updates' && !canOpenUpdates ? '当前窗口不支持应用更新' : getStatus(id) || purpose(id);
  const rows = (ids: readonly SettingId[]) => <div className="pw-settings-links">{ids.filter(visible).map(id => <Button
    key={id} variant="ghost" leadingIcon={items[id].icon} trailingIcon={IconChevronRight}
    className="pw-domain-link h-auto whitespace-normal" data-setting-entry={id} data-workspace-entry={'setting:' + id}
    disabled={id === 'updates' && !canOpenUpdates} onClick={() => onOpen(id)}>
    <span className="pw-settings-row-copy"><span>{items[id].title}</span><small>{description(id)}</small></span>
  </Button>)}</div>;

  if (group) return <div className="pw-settings-home pw-settings-manga pw-settings-directory" data-settings-detail>
    {page === 'group:privacy' && <section className="pw-settings-policy" aria-label="执行与自动任务">{executionControls}</section>}
    <div className="pw-settings-category-content">{rows(group.entries)}</div>
  </div>;

  const text = normalize(query);
  const terms = text.split(/\s+/);
  const results = text ? (Object.keys(items) as SettingId[]).filter(visible).filter(id => {
    const item = items[id];
    const haystack = normalize([item.title, purpose(id), item.terms, getStatus(id)].join(' '));
    return terms.every(term => haystack.includes(term));
  }) : [];
  // Prefer the actual setting over a category that happens to contain the same search word.
  const leaves = results.filter(id => !id.startsWith('group:'));
  const matches = leaves.length ? leaves : results;
  return <div className="pw-settings-home pw-settings-manga pw-settings-directory">
    <SearchField className="pw-secondary-search" data-input-size="medium" aria-label="搜索设置" clearLabel="清除搜索"
      placeholder="搜索设置" value={query} onChange={onQueryChange} shape="rounded"/>
    {text ? <section className="pw-settings-results" aria-label="匹配的设置">
      <p role="status" className={matches.length ? 'sr-only' : 'pw-settings-no-match'}>{matches.length ? `找到 ${matches.length} 项设置` : '没有匹配的设置。可以换个词，或清除搜索。'}</p>
      {rows(matches)}
    </section> : <div className="pw-settings-sections">{home.map((section, index) => <section key={section.title} aria-label={section.title} className="pw-settings-category" data-secondary-links={index === home.length - 1 || undefined}>
      <WorkspaceSectionHeading title={section.title}/>
      <div className="pw-settings-category-content">{rows(section.entries)}</div>
    </section>)}</div>}
  </div>;
}
