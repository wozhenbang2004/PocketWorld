/** Presentation routes over the existing native workspaces.
 * Keep native section names and object identities in Secondary unchanged.
 * A workspace opens its real default page, never an extra tool directory.
 */
export type WorkspaceKey = 'memory' | 'automation' | 'teams' | 'tools' | 'devices' | 'media';
export type WorkspaceSection = {
  key: WorkspaceKey;
  title: string;
  route: string;
  pages: ReadonlyArray<{ route: string; title: string; aliases?: ReadonlyArray<string> }>;
};

export const WORKSPACE_SECTIONS: ReadonlyArray<WorkspaceSection> = [
  { key: 'memory', title: '记忆', route: 'memory', pages: [
    { route: 'memory', title: '记忆', aliases: ['memoryReview'] },
    { route: 'learning', title: '学习与经验' },
  ] },
  { key: 'automation', title: '自动化', route: 'automation', pages: [
    { route: 'automation', title: '提醒' },
    { route: 'commitments', title: '委托与目标' },
  ] },
  { key: 'teams', title: '团队', route: 'teams', pages: [
    { route: 'teams', title: '团队' },
    { route: 'groups', title: '群协作' },
    { route: 'channels', title: '消息渠道' },
  ] },
  { key: 'tools', title: '技能与工具', route: 'skills', pages: [
    { route: 'skills', title: '技能' },
    { route: 'plugins', title: '插件', aliases: ['pluginCatalog'] },
    { route: 'mcp', title: 'MCP' },
    { route: 'workProfiles', title: '工作配置' },
  ] },
  { key: 'devices', title: '设备连接', route: 'devices', pages: [
    { route: 'devices', title: '我的设备' },
    { route: 'remote', title: '电脑' },
    { route: 'ssh', title: 'SSH' },
    { route: 'local', title: '本机开发' },
  ] },
  { key: 'media', title: '媒体', route: 'media', pages: [
    { route: 'media', title: '生成与文件' },
    { route: 'realtime', title: '实时通话' },
  ] },
];

const legacyGroups: Readonly<Record<string, string>> = {
  'group:devices': 'devices',
  'group:memory': 'memory',
  'group:extensions': 'skills',
  'group:media': 'media',
  'group:work': 'group:data',
};

export function normalizeWorkspaceRoute(route: string): string {
  const page = (route || 'settings').replace(/^connections[:/]/, '').replace(/^settings[:/]/, '');
  return legacyGroups[page] || (page === 'connections' ? 'settings' : page);
}

export function workspaceForRoute(route: string): WorkspaceSection | undefined {
  const page = normalizeWorkspaceRoute(route);
  return WORKSPACE_SECTIONS.find(workspace => workspace.pages.some(item => item.route === page || item.aliases?.includes(page)));
}

export function workspacePageForRoute(route: string): string | undefined {
  const page = normalizeWorkspaceRoute(route);
  return workspaceForRoute(page)?.pages.find(item => item.route === page || item.aliases?.includes(page))?.route;
}

/** Uses actual App drawer keys, including the primary task workspace. */
export function workspaceNavigationKey(route: string): string {
  const page = normalizeWorkspaceRoute(route);
  if (['chat', 'tasks', 'projects', 'profile'].includes(page)) return page;
  if (page === 'history') return 'tasks';
  if (['models', 'model-settings', 'accounts', 'proxy'].includes(page)) return 'model-settings';
  return workspaceForRoute(page)?.route || 'settings';
}
