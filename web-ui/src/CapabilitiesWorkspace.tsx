import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { DataOperationHeading, DataOperationRow, useDataSettingsJourney } from './DataSettingsJourney';
import { Accordion, AccordionItem, Button, IconButton } from './forms-library';
import { IconClock, IconDevices, IconFileSettings, IconFolder, IconMcp, IconPhone, IconPlugin, IconRefresh, IconSearch, IconSkill, IconTeam, IconTerminal, IconShieldCheck } from './icons';
import { PermissionPolicySettings, type PermissionLeaveGuard } from './PermissionPolicySettings';
import { ToolCatalogBrowser } from './ToolCatalogBrowser';
import { type CatalogTool, type ToolCatalog } from './tool-catalog';
import type { SetupCapability } from './setup-contract';
import { capabilityCheckAction, capabilityCheckDescription, capabilityCheckReady, capabilityChecks, capabilityStartLabels, isCapabilityPurpose, type CapabilityPreparationAction, type CapabilityPurpose } from './capability-journey';
import './capabilities-workspace.css';
import './capability-purpose.css';

export type CapabilityWorkspaceMemory = { view?: string; scrollTop?: number; focusId?: string; selectedTool?: string; policyOrigin?: 'list' | 'catalog' };
type Capability = { id: string; label: string; state: string; installed: boolean; detail?: string; checks?: Record<string, any>[]; notifications?: { checks?: Record<string, any>[] }; runtime?: Record<string, any> };
type CapabilityObservation = { capabilities: Capability[]; observedAt: number; executionAuthorized: false };
const purposes: Record<CapabilityPurpose, { title: string; description: string; icon: typeof IconSearch; route?: string; prepare?: SetupCapability; preparationLabel?: string }> = {
  research: { title: '整理资料与文件', description: '检索资料、归纳内容并保存文档', icon: IconSearch, route: 'web-search', preparationLabel: '查看搜索服务' },
  'phone.background': { title: '在后台操作手机', description: '在独立屏中完成手机上的事情', icon: IconPhone, prepare: 'phone', preparationLabel: '查看手机准备项' },
  'phone.main_screen': { title: '操作当前手机屏幕', description: '在你看到的主屏上完成操作', icon: IconPhone, prepare: 'phone', preparationLabel: '查看手机准备项' },
  project: { title: '开发或修改项目', description: '接着已有项目工作，保留文件与版本', icon: IconFolder, route: 'local', preparationLabel: '检查开发环境' },
  remote: { title: '在其他设备上工作', description: '连接电脑并使用它的工作环境', icon: IconDevices },
  team: { title: '让团队协作', description: '围绕同一个目标分工并汇总结果', icon: IconTeam },
  schedule: { title: '安排后续工作', description: '查看定时工作、条件职责与触发记录', icon: IconClock },
};
// MangaFlow settings groups: quiet section labels around related destinations.
const purposeSections = [
  { title: '资料与创作', ids: ['research', 'project'] },
  { title: '手机与设备', ids: ['phone.background', 'phone.main_screen', 'remote'] },
  { title: '协作与安排', ids: ['team', 'schedule'] },
];
const readinessLabel = (state: string) => ({ scope_required: '按目标核对范围', review_required: '可进入任务核对', selection_required: '需要选择目标', preparation_required: '需要准备', busy: '执行资源使用中', unavailable: '当前不可用' } as Record<string, string>)[state] || '状态待核对';
const checkNames: Record<string, string> = { serviceConnected: '手机操作服务', windowContent: '界面内容读取', interactiveWindows: '窗口读取', deviceUnlocked: '手机解锁', screenInteractive: '屏幕状态', mainDisplayPresent: '手机主屏', gestureCapability: '手机操作权限', screenshotCapability: '截图权限', backgroundSupported: '后台屏支持', backgroundConnected: '后台助手连接', backgroundClean: '原后台会话释放', notificationsAllowed: '任务通知', notificationPermission: '系统通知权限', notificationsNotPaused: '通知显示', taskChannel: '任务通知类别' };
const checkGroupNames: Record<string, string> = { accessibility: '手机权限', notifications: '任务通知', background_setup: '后台连接' };

export type CapabilitiesWorkspaceProps = {
  state: Record<string, any>; onNavigate: (route: string) => void; onPrepare?: (capability?: SetupCapability) => void;
  run?: (action: string, args?: Record<string, any>) => Promise<any>; supported?: (action: string) => boolean;
  registerBack?: (back: (() => boolean) | null) => void;
  registerBeforeLeave?: (guard: PermissionLeaveGuard | null) => void;
  memory?: CapabilityWorkspaceMemory; onStartWork?: (purpose: CapabilityPurpose) => void;
};
/** Affect's retained integration rows + list/detail journey over native definitions.
 * Catalog reads never start services, run plugins, connect devices or grant tools. */
export function CapabilitiesWorkspace({ state, onNavigate, onPrepare, run, supported, registerBack, registerBeforeLeave, memory, onStartWork }: CapabilitiesWorkspaceProps) {
  const journey = useDataSettingsJourney(memory?.view || 'list');
  const [catalog, setCatalog] = useState<ToolCatalog | null>(null), [loading, setLoading] = useState(false), [error, setError] = useState(''), [selectedTool, setSelectedTool] = useState(memory?.selectedTool || '');
  const [observation, setObservation] = useState<CapabilityObservation | null>(null), [observing, setObserving] = useState(false), [observationError, setObservationError] = useState('');
  const observationGeneration = useRef(0), observationAlive = useRef(false);
  const policyOrigin = useRef<'list' | 'catalog'>(memory?.policyOrigin || 'catalog');
  const beforeLeave = useRef<PermissionLeaveGuard | null>(null), catalogBack = useRef<(() => boolean) | null>(null), generation = useRef(0);
  const runRef = useRef(run); runRef.current = run;
  const canRead = !!run && supported?.('permissions.catalog') === true;
  const canEdit = !!run && supported?.('permissions.snapshot') === true && supported?.('permissions.save') === true;
  const canObserve = !!run && supported?.('work.capabilities') === true;
  const restoration = useRef({ pending: true, view: memory?.view || 'list', top: memory?.scrollTop || 0, focus: memory?.focusId || '' });
  const remember = () => { if (memory) { memory.view = journey.view; memory.selectedTool = selectedTool; memory.scrollTop = journey.root.current?.closest('.pw-secondary-scroll')?.scrollTop || 0; const focused = document.activeElement as HTMLElement | null; if (focused && journey.root.current?.contains(focused)) memory.focusId = focused.closest<HTMLElement>('[data-capability-focus],[data-operation-entry]')?.dataset.capabilityFocus || focused.closest<HTMLElement>('[data-operation-entry]')?.dataset.operationEntry || memory.focusId; } };
  const latestRemember = useRef(remember); latestRemember.current = remember;
  useEffect(() => { if (memory) { memory.view = journey.view; memory.selectedTool = selectedTool; } }, [journey.view, selectedTool, memory]);
  useLayoutEffect(() => { const scroller = journey.root.current?.closest('.pw-secondary-scroll'); return () => { latestRemember.current(); if (memory && scroller) memory.scrollTop = scroller.scrollTop; }; }, []);
  useLayoutEffect(() => {
    const saved = restoration.current;
    if (!saved.pending) return;
    if (saved.view !== journey.view) { saved.pending = false; return; }
    if ((journey.view === 'list' || journey.view.startsWith('purpose:')) && canObserve && !observation || journey.view === 'catalog' && canRead && !catalog) return;
    saved.pending = false;
    const scroller = journey.root.current?.closest('.pw-secondary-scroll'); if (scroller) scroller.scrollTop = saved.top;
    const target = Array.from(journey.root.current?.querySelectorAll<HTMLElement>('[data-capability-focus],[data-operation-entry]') || []).find(node => node.dataset.capabilityFocus === saved.focus || node.dataset.operationEntry === saved.focus);
    if (saved.focus) (target || journey.root.current?.querySelector<HTMLElement>('[data-operation-heading]') || journey.root.current)?.focus({ preventScroll: true });
  }, [journey.view, observation, catalog, canObserve, canRead]);
  const observe = useCallback(async () => {
    if (!canObserve || !runRef.current) return;
    const request = ++observationGeneration.current; setObserving(true); setObservationError('');
    try {
      const value = await runRef.current('work.capabilities', { purpose: 'all' });
      if (!observationAlive.current || request !== observationGeneration.current) return;
      if (!Array.isArray(value?.capabilities) || value.executionAuthorized !== false || !Number.isSafeInteger(value.observedAt) || value.capabilities.some((item: any) => typeof item?.id !== 'string' || typeof item?.state !== 'string' || typeof item?.label !== 'string' || typeof item?.installed !== 'boolean')) throw new Error('能力状态暂不可读取，请重新核对。');
      setObservation(value);
    } catch (failure) { if (observationAlive.current && request === observationGeneration.current) setObservationError(failure instanceof Error ? failure.message : '能力状态暂不可读取。'); }
    finally { if (observationAlive.current && request === observationGeneration.current) setObserving(false); }
  }, [canObserve]);
  useEffect(() => {
    observationAlive.current = true; void observe();
    const resume = () => { if (document.hidden) observationGeneration.current++; else void observe(); };
    document.addEventListener('visibilitychange', resume);
    return () => { observationAlive.current = false; observationGeneration.current++; document.removeEventListener('visibilitychange', resume); };
  }, [observe, state.webWorkspaceResumeRevision]);
  const registerPolicyLeave = useCallback((guard: PermissionLeaveGuard | null) => { beforeLeave.current = guard; }, []);
  const registerCatalogBack = useCallback((back: (() => boolean) | null) => { catalogBack.current = back; }, []);
  const leave = (next: () => void) => { if (!beforeLeave.current?.(next)) next(); };
  const reload = async () => {
    if (!canRead || !runRef.current) return;
    const epoch = ++generation.current; setLoading(true); setError('');
    try { const value: ToolCatalog = await runRef.current('permissions.catalog', {}); if (epoch === generation.current) setCatalog(value); }
    catch (e) { if (epoch === generation.current) setError(`目录暂不可读取：${e instanceof Error ? e.message : String(e)}`); }
    finally { if (epoch === generation.current) setLoading(false); }
  };
  useEffect(() => { if (journey.view === 'catalog' || journey.view === 'policy') void reload(); return () => { ++generation.current; }; }, [canRead, state.conversationId, journey.view === 'catalog' || journey.view === 'policy']);
  const back = () => {
    if (journey.view === 'policy') { leave(() => journey.open(policyOrigin.current)); return true; }
    if (journey.view === 'catalog' && catalogBack.current?.()) return true;
    return journey.back();
  };
  useEffect(() => { registerBack?.(back); return () => registerBack?.(null); }, [registerBack, journey.view]);
  useEffect(() => { registerBeforeLeave?.(next => beforeLeave.current?.(next) || false); return () => registerBeforeLeave?.(null); }, [registerBeforeLeave]);
  const openPolicy = (tool = '') => { policyOrigin.current = journey.view === 'list' ? 'list' : 'catalog'; if (memory) memory.policyOrigin = policyOrigin.current; setSelectedTool(tool); journey.open('policy'); };
  const sourceRoute = (tool: CatalogTool) => tool.source === 'installed_plugin' ? 'plugins' : ({ skills: 'skills', project: 'local', remote: 'devices', devices: 'devices', teams: 'teams', ssh: 'ssh', media: 'media', automation: 'automation' } as Record<string, string>)[tool.category];
  const selectedCapability = observation?.capabilities.find(item => journey.view === `purpose:${item.id}`);
  const selectedChecks = capabilityChecks(selectedCapability?.checks, selectedCapability?.notifications?.checks);
  const pendingChecks = selectedChecks.filter(check => !capabilityCheckReady(check)), preparedChecks = selectedChecks.filter(capabilityCheckReady);
  const pendingGroups = [...new Set(pendingChecks.map(check => check.action || 'retry'))].map(key => {
    const checks = pendingChecks.filter(check => (check.action || 'retry') === key);
    return { key, checks, action: capabilityCheckAction(checks[0]) };
  });
  const selectedPurposeId = selectedCapability && isCapabilityPurpose(selectedCapability.id) ? selectedCapability.id : undefined;
  const selectedPurpose = selectedPurposeId ? purposes[selectedPurposeId] : undefined;
  const unsupportedBackground = pendingChecks.some(check => check.action === 'main_screen_choice');
  const needsPreparation = selectedCapability?.state === 'preparation_required' || pendingChecks.length > 0;
  const canStart = selectedCapability?.installed && selectedPurposeId && !unsupportedBackground && ['scope_required', 'review_required', 'selection_required', 'preparation_required', 'busy'].includes(selectedCapability.state);
  const prepareTools = selectedPurposeId === 'project' && selectedCapability?.state === 'preparation_required' && selectedCapability.runtime?.preparationAvailable === true && !!onPrepare;
  const showPreparation = selectedPurpose && (selectedPurpose.route || selectedPurpose.prepare && onPrepare) && !unsupportedBackground && (!pendingChecks.length || selectedPurposeId === 'project');
  const readyStatus = <div className="pw-capability-observation" aria-live="polite"><span>{observing ? '正在核对当前状态…' : observation ? `核对于 ${new Date(observation.observedAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}` : '尚未核对当前状态'}</span><IconButton icon={IconRefresh} aria-label="重新核对能力状态" disabled={!canObserve || observing} onClick={() => void observe()} /></div>;
  const prepareCapability = () => { if (!selectedPurpose) return; remember(); if (memory) memory.focusId = 'prepare'; if (prepareTools) onPrepare?.('tools'); else if (selectedPurpose.prepare && onPrepare) onPrepare(selectedPurpose.prepare); else if (selectedPurpose.route) onNavigate(selectedPurpose.route); };
  const followCheck = (action: CapabilityPreparationAction, focusId: string) => {
    if (observing || observationError) return;
    remember(); if (memory) memory.focusId = focusId;
    if (action.kind === 'prepare') onPrepare?.(action.capability);
    else if (action.kind === 'navigate') onNavigate(action.route);
    else if (action.kind === 'purpose') journey.open(`purpose:${action.purpose}`);
    else void observe();
  };
  return <div className="pw-capabilities-workspace" ref={journey.root} tabIndex={-1}>
    {journey.view === 'list' ? <>
      <div className="pw-capabilities-introduction"><h2 className="pw-capabilities-intro">让想法开始行动</h2><p>选择一项工作，查看准备情况。</p></div>
      {readyStatus}
      {!canObserve && <p className="pw-capability-read-error" role="status">当前版本尚不能读取能力准备情况，仍可管理工具来源。</p>}
      {observationError && <p className="pw-capability-read-error" role="alert">{observationError}</p>}
      {observation && <div className="pw-capability-purpose-groups" aria-busy={observing || undefined}>{[...purposeSections, { title: '其他能力', ids: observation.capabilities.filter(item => !purposeSections.some(section => section.ids.includes(item.id))).map(item => item.id) }].map(section => {
        const items = observation.capabilities.filter(item => section.ids.includes(item.id));
        return items.length > 0 && <section className="pw-capabilities-group" aria-label={section.title} key={section.title}><header><h2>{section.title}</h2></header><div className="pw-capability-purpose-list pw-material-content">{items.map(item => {
          const purpose = isCapabilityPurpose(item.id) ? purposes[item.id] : undefined;
          return <div className="pw-capability-entry" key={item.id} data-readiness={item.state}><DataOperationRow id={`purpose:${item.id}`} title={purpose?.title || item.label} detail={`${readinessLabel(item.state)} · ${purpose?.description || item.detail || ''}`} icon={purpose?.icon} onOpen={id => { if (memory) memory.focusId = id; journey.open(id); }} /></div>;
        })}</div></section>;
      })}</div>}
      <div className="pw-capabilities-management pw-material-navigation" aria-label="能力管理"><Button variant="ghost" leadingIcon={IconPlugin} data-capability-focus="sources" onClick={() => journey.open('sources')}>连接与管理</Button><Button variant="ghost" leadingIcon={IconFileSettings} data-capability-focus="catalog" onClick={() => journey.open('catalog')}>工具目录</Button>{canEdit && <Button variant="ghost" leadingIcon={IconShieldCheck} data-capability-focus="policy" onClick={() => openPolicy()}>审批规则</Button>}</div>
    </> : journey.view.startsWith('purpose:') ? <>
      <DataOperationHeading title={selectedPurpose?.title || selectedCapability?.label || '能力准备'} parent="能力" description={selectedPurpose?.description || ''} onBack={() => journey.open('list')} />
      {readyStatus}
      {observationError && <p className="pw-capability-read-error" role="alert">{observationError}</p>}
      {selectedCapability ? <section className="pw-capability-purpose-detail pw-material-content">
        <h3>{readinessLabel(selectedCapability.state)}</h3><p>{selectedCapability.detail}</p>
        {!!pendingGroups.length && <section className="pw-capability-next-steps" aria-label="还需准备的事项"><h4>还需完成</h4><ul className="pw-capability-checks pw-capability-pending-checks">{pendingGroups.map(({ key, checks, action }) => {
          const first = checks[0], focusId = `check:${key}`;
          return <li key={key}><div><strong>{checkGroupNames[key] || first.label || first.title || checkNames[first.id || ''] || '准备项'}</strong>{checks.map((check, index) => <span key={check.id || index}>{checks.length > 1 ? `${check.label || check.title || checkNames[check.id || ''] || '准备项'}：` : ''}{capabilityCheckDescription(check)}</span>)}</div>{(action.kind !== 'prepare' || onPrepare) && <Button variant="secondary" size="small" data-capability-focus={focusId} disabled={observing || !!observationError} onClick={() => followCheck(action, focusId)}>{action.label}</Button>}</li>;
        })}</ul></section>}
        {selectedCapability.runtime?.configurationProblem && <p className="pw-capability-read-error">{String(selectedCapability.runtime.configurationProblem)}</p>}
        <div className="pw-action-row">{showPreparation && needsPreparation && <Button data-capability-focus="prepare" disabled={observing || !!observationError} onClick={prepareCapability}>{prepareTools ? '准备开发工具' : selectedPurpose?.preparationLabel}</Button>}{onStartWork && canStart && selectedPurposeId && <Button variant={needsPreparation || selectedCapability.state === 'busy' ? 'secondary' : 'primary'} data-capability-focus="start" disabled={observing || !!observationError} onClick={() => { remember(); if (memory) memory.focusId = 'start'; onStartWork(selectedPurposeId); }}>{capabilityStartLabels[selectedPurposeId]}</Button>}{showPreparation && !needsPreparation && <Button variant="secondary" data-capability-focus="prepare" disabled={observing || !!observationError} onClick={prepareCapability}>{selectedPurpose?.preparationLabel}</Button>}</div>
        <p className="pw-capability-scope-note">准备情况供你选择。开始工作时，仍会核对这次目标的资源与执行范围。</p>
        {!!preparedChecks.length && <Accordion variant="divided" defaultExpandedKeys={[]}><AccordionItem id="purpose-checks" title={`已准备的事项（${preparedChecks.length}）`}><ul className="pw-capability-checks">{preparedChecks.map((check, index) => <li key={check.id || index}><strong>{check.label || check.title || checkNames[check.id || ''] || '准备项'}</strong><span>{capabilityCheckDescription(check)}</span></li>)}</ul></AccordionItem></Accordion>}
      </section> : !observing && !observationError ? <p className="pw-capability-read-error">这项能力的状态暂不可读取，请重新核对或返回能力列表。</p> : null}
    </> : journey.view === 'policy' && run ? <>
      <DataOperationHeading title="审批规则" parent={policyOrigin.current === 'list' ? '能力' : '工具目录'} description="选择作用范围，再设置工具的审批方式。" onBack={() => leave(() => journey.open(policyOrigin.current))} />
      <PermissionPolicySettings key={`${String(state.conversationId || '')}:${selectedTool}`} conversationId={String(state.conversationId || '')} act={run} initialScope={state.conversationId ? 'conversation' : 'global'} initialTool={selectedTool} registerBeforeLeave={registerPolicyLeave} />
    </> : journey.view === 'sources' ? <>
      <DataOperationHeading title="连接与管理" parent="能力" description="准备做事需要的方法、服务和执行位置。" onBack={() => journey.open('list')} />
      {onPrepare && <section className="pw-capabilities-preparation pw-material-content" aria-label="手机能力准备"><DataOperationRow id="setup" title="准备这台手机" detail="按需准备手机操作、开发、语音与后台运行" icon={IconPhone} onOpen={() => onPrepare()} /></section>}
      <section className="pw-capabilities-group" aria-label="方法与任务选择"><header><h2>方法与任务选择</h2></header><div className="pw-capabilities-list pw-material-content">
        <DataOperationRow id="skills" title="技能与方法" detail="安装和阅读做事步骤，脚本执行单独审批" icon={IconSkill} onOpen={onNavigate} />
        <DataOperationRow id="workProfiles" title="工作配置" detail="选择模型、技能与工具，不授予执行权限" icon={IconFileSettings} onOpen={onNavigate} />
      </div></section>
      <section className="pw-capabilities-group" aria-label="扩展与服务"><header><h2>扩展与服务</h2></header><div className="pw-capabilities-list pw-material-content">
        <DataOperationRow id="plugins" title="插件" detail="查找、安装、查看定义与逐项工具授权" icon={IconPlugin} onOpen={onNavigate} />
        <DataOperationRow id="mcp" title="工具服务 · MCP" detail="接入全网搜索与邮件等服务；准备、安装和授权分别核对" icon={IconMcp} onOpen={onNavigate} />
      </div></section>
      <section className="pw-capabilities-group" aria-label="执行位置"><header><h2>执行位置</h2></header><div className="pw-capabilities-list pw-material-content">
        <DataOperationRow id="local" title="这台手机的开发环境" detail="准备运行环境、项目与依赖，构建仍需原范围" icon={IconTerminal} onOpen={onNavigate} />
        <DataOperationRow id="devices" title="其他设备" detail="核对连接与设备能力，每次使用原授权范围" icon={IconDevices} onOpen={onNavigate} />
      </div></section>
    </> : null}
    <div className="pw-capabilities-catalog-view" hidden={journey.view !== 'catalog'}>
      <DataOperationHeading title="工具目录" parent="能力" description="查看工具定义、来源及审批规则。" onBack={() => journey.open('list')} />
      <div className="pw-capabilities-controls"><Button variant="secondary" onClick={() => journey.open('sources')}>管理来源</Button>{canEdit && <Button variant="ghost" onClick={() => openPolicy()}>审批规则</Button>}</div>
      {canRead ? <ToolCatalogBrowser tools={catalog?.tools || []} loading={loading} error={error} onReload={() => void reload()} registerBack={registerCatalogBack} detail={tool => <div className="pw-capabilities-tool-actions">{canEdit && tool.configurable && <Button onClick={() => openPolicy(tool.name)}>设置这个工具的审批</Button>}{sourceRoute(tool) && <Button variant="secondary" onClick={() => onNavigate(sourceRoute(tool))}>管理相关功能</Button>}{onPrepare && (tool.category === 'phone' || tool.category === 'semantic') && <Button variant="secondary" onClick={() => onPrepare()}>准备这台手机</Button>}<Button variant="ghost" onClick={() => onNavigate('workProfiles')}>管理工作配置</Button></div>} /> : <p className="pw-tool-status">当前版本未提供工具目录读取。仍可管理技能、插件、服务与执行位置。</p>}
      {!!catalog?.notices.length && <Accordion variant="divided" defaultExpandedKeys={[]}><AccordionItem id="catalog-notices" title="目录范围与读取说明">{catalog.notices.map((notice, index) => <p className="pw-tool-boundary" key={index}>{notice}</p>)}</AccordionItem></Accordion>}
    </div>
  </div>;
}
