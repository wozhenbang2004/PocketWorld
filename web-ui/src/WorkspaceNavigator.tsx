import { Fragment, useId, useLayoutEffect, useMemo, useRef, useState, type ReactNode, type Ref } from 'react';
import { AnimatePresence } from 'motion/react';
import { Button, IconButton, SearchField } from './forms-library';
import { Drawer, WorkSidebar, type WorkNavItem } from './shell-library';
import { IconAccount, IconArrowLeft, IconChat, IconChevronDown, IconChevronRight, IconClose, IconGrid, IconMore, IconNewChat, IconNotification, IconSearch, IconSettings, IconSidebarClose, IconSidebarOpen } from './icons';
import { conversationNavigationStates, type ConversationNavigationState } from './conversation-work';
import './workspace-navigator.css';

type Recent = { id: string; title?: string; pinOrder?: number | string };
type Face = 'home' | 'directory' | 'records';
export type OwnerNavigationMemory = { homeY?: number; conversationHomeY?: number; directoryY?: number; query?: string; showPinned?: boolean; showOngoing?: boolean; showRecent?: boolean };
type Props = {
  items: WorkNavItem[]; selected: string; recent: readonly Recent[]; selectedConversation?: string;
  conversationFirst?: boolean;
  conversationActivity?: ReadonlyMap<string, ConversationNavigationState>;
  desktop: boolean; compact: boolean; onToggleCompact: () => void;
  directoryOpen: boolean; setDirectoryOpen: (open: boolean) => void;
  searchOpen: boolean; openSearch: () => void; closeSearch: () => void; search: ReactNode; searchTrigger: Ref<HTMLButtonElement>;
  memory: OwnerNavigationMemory; navigate: (key: string) => void; openConversation?: (id: string) => void;
  organizeConversation?: (id: string) => void; newConversation?: () => void; newConversationLabel?: string; newPending: boolean;
  onResumeIndependentReportFollowUp?: () => void;
  profileName: string; avatar?: string; canProfile: boolean; openUnread?: () => void;
};

const first = ['butler', 'tasks', 'artifacts', 'remoteInbox', 'projects', 'devices'];
const followUpGroup = { name: '跟进与协作', keys: ['automation', 'teams'] };
const auxiliaryGroups = [
  { name: '我的资料', keys: ['memory', 'media'] },
  { name: '工具与设置', keys: ['capabilities', 'model-settings', 'setup', 'developer-logs'] },
];
const directoryGroups = [followUpGroup, ...auxiliaryGroups];
const workspaceGroups = [
  { name: '日常工作', keys: ['tasks', 'projects', 'artifacts', 'devices', 'butler'] },
  { ...followUpGroup, keys: ['remoteInbox', ...followUpGroup.keys] },
  ...auxiliaryGroups,
];
const terms: Record<string, string> = {
  butler: '办事 交办 聊天 管家', tasks: '恢复 任务 待办 跟进 中断 工作', artifacts: '文件 交付 下载 成果', projects: '项目 工程 编程',
  devices: '手机 设备 电脑 连接 远程', remoteInbox: '通知 消息 收件箱', automation: '自动化 定时 提醒', teams: '团队 协作 子代理',
  memory: '记忆 偏好 我和管家', media: '媒体 图片 声音', capabilities: '技能 工具 插件 能力', setup: '准备 首次 接入',
  'developer-logs': '开发 诊断 日志', 'model-settings': 'AI 接入 API 模型 账号 服务', settings: '设置 选项 用量 外观',
};
const normalize = (text: string) => text.normalize('NFKC').toLocaleLowerCase('en-US').trim();
const names: Record<string, string> = { devices: '设备', artifacts: '成果' };
// External unread messages have their own shortcut. The notification route
// opens all attention sources; presenting that count here implies a false total.
const withoutUnreadBadge = (item: WorkNavItem): WorkNavItem =>
  item.key === 'remoteInbox' ? { ...item, count: undefined } : item;

/** The app remains the only route/guard owner. This shell projects the same real
 * items and conversation IDs onto three faces of its existing drawer or desktop rail. */
export function WorkspaceNavigator({items, selected, recent, selectedConversation, conversationFirst, conversationActivity, desktop, compact, onToggleCompact,
  directoryOpen, setDirectoryOpen, searchOpen, openSearch, closeSearch, search, searchTrigger, memory, navigate,
  openConversation, organizeConversation, newConversation, newConversationLabel, newPending, onResumeIndependentReportFollowUp,
  profileName, avatar, canProfile, openUnread}: Props) {
  const face: Face = searchOpen ? 'records' : directoryOpen ? 'directory' : 'home';
  const mini = compact && face === 'home';
  const chatFirst = !!conversationFirst && !desktop && recent.length > 0;
  const homePosition = chatFirst ? 'conversationHomeY' : 'homeY';
  const scroll = useRef<HTMLDivElement>(null), directoryButton = useRef<HTMLButtonElement>(null), back = useRef<HTMLButtonElement>(null);
  const priorFace = useRef<Face>(face);
  const recentRest = useId();
  const [directoryQuery, setDirectoryQuery] = useState(memory.query || ''), [workspaceQuery, setWorkspaceQuery] = useState('');
  const query = chatFirst ? workspaceQuery : directoryQuery;
  const [showPinned, setShowPinned] = useState(!!memory.showPinned);
  const [showOngoing, setShowOngoing] = useState(!!memory.showOngoing);
  const [showRecent, setShowRecent] = useState(!!memory.showRecent);
  const [badAvatar, setBadAvatar] = useState<string>();
  const index = useMemo(() => new Map(items.map(i => [i.key, i])), [items]);
  const home = first.flatMap(key => { const item = index.get(key); return item ? [{ ...withoutUnreadBadge(item), label: names[key] || item.label, group: '日常工作' }] : []; });
  const directory: WorkNavItem[] = [], used = new Set(chatFirst ? ['settings'] : [...first, 'settings']);
  for (const group of chatFirst ? workspaceGroups : directoryGroups) for (const key of group.keys) {
    used.add(key);
    const item = index.get(key);
    if (item) directory.push(chatFirst
      ? { ...withoutUnreadBadge(item), label: names[key] || item.label, group: group.name }
      : { ...item, group: group.name });
  }
  // New product routes must not silently vanish from the drawer.
  for (const item of items) if (!used.has(item.key)) directory.push({ ...item, group: item.group || '其他功能' });
  const needle = normalize(query);
  const found = needle ? items.filter(i => needle.split(/\s+/).every(t => normalize([i.label, i.group, terms[i.key] || ''].join(' ')).includes(t)))
    .map(i => ({ ...withoutUnreadBadge(i), group: '匹配的功能' })) : directory;
  const onDirectoryRoute = directory.find(i => i.key === selected);
  const inbox = index.get('remoteInbox'), unread = Number(inbox?.count || 0);
  const activity = (conversation: Recent) => {
    const state = conversationActivity?.get(conversation.id);
    return state ? conversationNavigationStates[state] : undefined;
  };
  const ongoing = recent.filter(activity).sort((a, b) => (activity(b)?.priority || 0) - (activity(a)?.priority || 0));
  const other = recent.filter(c => !activity(c));
  const pinned = other.filter(c => Number(c.pinOrder) > 0), unpinned = other.filter(c => !(Number(c.pinOrder) > 0));
  const remember = () => {
    if (!scroll.current) return;
    if (face === 'home') memory[homePosition] = scroll.current.scrollTop;
    else if (face === 'directory' && !chatFirst) memory.directoryY = scroll.current.scrollTop;
  };
  const enter = (to: 'records' | 'directory') => {
    remember();
    if (to === 'directory') {
      if (chatFirst) setWorkspaceQuery('');
      setDirectoryOpen(true);
    } else openSearch();
  };
  useLayoutEffect(() => {
    const before = priorFace.current; priorFace.current = face;
    if (scroll.current) scroll.current.scrollTop = face === 'home' ? memory[homePosition] || 0
      : face === 'directory' && !chatFirst ? memory.directoryY || 0 : 0;
    if (before === face) return;
    if (face === 'home') {
      // The original node may have unmounted with the previous drawer face.
      const source = before === 'directory' ? directoryButton.current : scroll.current?.querySelector<HTMLElement>('[data-owner-search-trigger]');
      source?.focus({ preventScroll: true });
    } else if (face === 'directory') back.current?.focus({ preventScroll: true });
    // WorkSearch owns its own focus and input method while open.
  }, [face, homePosition, chatFirst, memory]);
  const changeQuery = (value: string) => {
    if (chatFirst) setWorkspaceQuery(value);
    else { setDirectoryQuery(value); memory.query = value; memory.directoryY = 0; }
    if (scroll.current) scroll.current.scrollTop = 0;
  };
  const choose = (key: string) => { remember(); navigate(key); };
  const rows = (entries: readonly Recent[]) => <div className="pw-recent-work">{entries.map(c => {
    const title = c.title || '未命名对话', mark = activity(c);
    return <div className="pw-conversation-row" key={c.id}>
      <Button variant="ghost" leadingIcon={IconChat} title={title} disabled={!openConversation}
        aria-current={c.id === selectedConversation ? 'page' : undefined} className={c.id === selectedConversation ? 'is-current' : undefined}
        data-conversation-activity={!!mark || undefined}
        onClick={() => { remember(); openConversation?.(c.id); }}><span dir="auto">{title}</span>{mark && <small>{mark.label}</small>}</Button>
      {organizeConversation && <IconButton icon={IconMore} aria-label={'整理对话：' + title} onClick={() => organizeConversation(c.id)}/>}
    </div>;
  })}</div>;
  const title = face === 'records' ? '搜索记录' : face === 'directory' ? (chatFirst ? '工作区' : '全部功能') : 'PocketWorld';
  const profile = profileName || '我的';
  const workRoutes = <Fragment key="work-routes">
    <WorkSidebar items={home} selected={selected} onNavigate={choose} label="主要工作区" compact={mini} hideGroupLabels/>
    <Button ref={directoryButton} variant="ghost" className="pw-owner-all" leadingIcon={IconGrid} trailingIcon={IconChevronRight}
      aria-label={onDirectoryRoute ? '全部功能，当前位置：' + onDirectoryRoute.label : '全部功能'} title="全部功能" onClick={() => enter('directory')}>
      <span>全部功能</span>{onDirectoryRoute && !mini && <small>{onDirectoryRoute.label}</small>}
    </Button>
  </Fragment>;
  const resumeFollowUp = !mini && onResumeIndependentReportFollowUp ?
    <Button key="resume-follow-up" variant="ghost" className="pw-owner-all" leadingIcon={IconChat}
      trailingIcon={IconChevronRight} disabled={newPending} onClick={() => { remember(); onResumeIndependentReportFollowUp(); }}>
      <span>继续未发追问</span>
    </Button> : null;
  const conversations = !mini && (ongoing.length > 0 || pinned.length > 0 || unpinned.length > 0) ?
    <section key="conversations" className="pw-work-recent pw-owner-recent" aria-label="对话列表" data-chat-first={chatFirst || undefined}>
      {ongoing.length > 0 && <div className="pw-conversation-group"><div className="pw-owner-recent-heading"><h3>跟进中的对话</h3>
        {ongoing.length > 3 && <Button size="small" variant="ghost" trailingIcon={IconChevronDown} aria-expanded={showOngoing}
          aria-label={showOngoing ? '收起跟进中的对话' : '展开其余 ' + (ongoing.length - 3) + ' 个跟进中的对话'}
          onClick={() => { memory.showOngoing = !showOngoing; setShowOngoing(!showOngoing); }}>{showOngoing ? '收起' : '展开其余 ' + (ongoing.length - 3) + ' 个'}</Button>}</div>
        {rows(showOngoing ? ongoing : ongoing.slice(0, 3))}</div>}
      {pinned.length > 0 && <div className="pw-conversation-group"><div className="pw-owner-recent-heading"><h3>置顶</h3>
        {pinned.length > 3 && <Button size="small" variant="ghost" trailingIcon={IconChevronDown} aria-expanded={showPinned}
          aria-label={showPinned ? '收起置顶' : '展开 ' + pinned.length + ' 个置顶对话'}
          onClick={() => { memory.showPinned = !showPinned; setShowPinned(!showPinned); }}>{showPinned ? '收起' : '展开 ' + pinned.length + ' 个'}</Button>}</div>
        {rows(showPinned ? pinned : pinned.slice(0, 3))}</div>}
      {unpinned.length > 0 && <div className="pw-conversation-group"><h3>最近</h3>
        {rows(unpinned.slice(0, 5))}
        {chatFirst && unpinned.length > 5 && <>
          <Button className="pw-owner-recent-more" size="small" variant="ghost" trailingIcon={IconChevronDown}
            aria-expanded={showRecent} aria-controls={recentRest} onClick={() => { memory.showRecent = !showRecent; setShowRecent(!showRecent); }}>
            {showRecent ? '收起较早对话' : '更多近期对话'}
          </Button>
          <div id={recentRest} hidden={!showRecent}>{showRecent && rows(unpinned.slice(5))}</div>
        </>}
      </div>}
    </section> : null;
  return <>
    <header className={'pw-drawer-header pw-owner-nav-header' + (face !== 'home' ? ' pw-search-header' : '')} data-nav-face={face}>
      {face !== 'home' && <IconButton ref={back} icon={IconArrowLeft} aria-label="返回导航首页" onClick={face === 'records' ? closeSearch : () => setDirectoryOpen(false)}/>}
      {desktop ? <h2>{title}</h2> : <Drawer.Title>{title}</Drawer.Title>}
      {chatFirst && face === 'home' && <Button ref={directoryButton} variant="ghost" size="small" className="pw-owner-workspaces"
        leadingIcon={IconGrid} onClick={() => enter('directory')}>工作区</Button>}
      {desktop && face === 'home' && <IconButton icon={mini ? IconSidebarOpen : IconSidebarClose} aria-label={mini ? '展开导航' : '收起导航'} aria-expanded={!mini} onClick={onToggleCompact}/>}
      {!desktop && <><Drawer.Description className="pw-sr-only" id="pw-work-description">交办事情、找到原工作与成果，或前往其他功能。</Drawer.Description>
        <Drawer.Close asChild><IconButton icon={IconClose} aria-label="关闭导航"/></Drawer.Close></>}
    </header>
    <div ref={scroll} className={'pw-drawer-body pw-owner-nav-body pw-navigation-search-host' + (face === 'records' ? ' pw-navigation-search' : '')}
      data-nav-face={face} data-mini={mini || undefined} onScroll={event => { if (event.target === event.currentTarget) remember(); }}>
      <AnimatePresence>{face === 'records' ? search : null}</AnimatePresence>
      {face === 'records' ? null : face === 'directory' ? <>
        <div className="pw-owner-nav-find"><SearchField value={query} onChange={changeQuery} maxLength={80} clearLabel="清除功能关键词" aria-label="按名称或用途查找功能" placeholder="查找功能"/></div>
        {found.length ? <WorkSidebar items={found} selected={selected} onNavigate={choose} label={needle ? '匹配的功能' : '功能目录'} hideGroupLabels={!!needle}/> :
          <div className="pw-owner-nav-empty" role="status"><p>没有找到“{query}”对应的功能</p><Button variant="ghost" onClick={() => changeQuery('')}>清除关键词</Button></div>}
      </> : <>
        <div className="pw-owner-quick">
          <Button ref={searchTrigger} variant="ghost" className="pw-owner-search" data-owner-search-trigger leadingIcon={IconSearch}
            aria-label="搜索工作、对话、成果与项目" title="搜索记录" onClick={() => enter('records')}>搜索记录</Button>
          {newConversation && !newConversationLabel && <IconButton icon={IconNewChat} aria-label="新对话" title="新对话" disabled={newPending} onClick={() => { remember(); newConversation(); }}/>}
        </div>
        {newConversation && newConversationLabel && <Button variant="ghost" className="pw-owner-all" leadingIcon={IconChat}
          trailingIcon={IconChevronRight} disabled={newPending} onClick={() => { remember(); newConversation(); }}>{newConversationLabel}</Button>}
        {chatFirst ? [resumeFollowUp, conversations] : [workRoutes, resumeFollowUp, conversations]}
      </>}
    </div>
    {face === 'home' && <footer className="pw-navigation-footer pw-owner-nav-footer" data-mini={mini || undefined}><div className="pw-navigation-account">
      <Button variant="ghost" className="pw-navigation-profile" disabled={!canProfile} title="个人资料与偏好"
        aria-label={profile + '，个人资料与偏好'} aria-current={selected === 'profile' ? 'page' : undefined} onClick={() => choose('profile')}>
        <span className="pw-navigation-profile-content"><span className="pw-sidebar-avatar" aria-hidden="true">
          {avatar && avatar !== badAvatar ? <img src={avatar} alt="" onError={() => setBadAvatar(avatar)}/> : profile === '我的' ? <IconAccount size={21}/> : Array.from(profile)[0]}
        </span>{!mini && <span className="pw-navigation-profile-copy"><strong dir="auto">{profile}</strong></span>}</span>
      </Button>
      {Number.isSafeInteger(unread) && unread > 0 && !inbox?.disabled && <IconButton icon={IconNotification}
        aria-label={unread + ' 条外部消息未读，打开外部未读'} title="外部未读消息" data-owner-unread onClick={() => {remember();if(openUnread)openUnread();else navigate('remoteInbox')}}/>}
      <IconButton icon={IconSettings} aria-label="设置" title="设置" aria-current={selected === 'settings' ? 'page' : undefined} onClick={() => choose('settings')}/>
    </div></footer>}
  </>;
}
