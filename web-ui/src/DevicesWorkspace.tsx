import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import './collaboration-depth.css';
import { Accordion, AccordionItem, Button, CheckboxField, SearchField, SelectField, SettingsDialog, TextAreaField, TextField } from './forms-library';
import { DeviceRequestScope, deviceConnectionLabel, deviceGrantInput, deviceHistoryLabel, deviceHistoryReceipt, deviceTaskDetails, deviceMethodLabels, deviceOperationLabels, deviceReceipt, deviceReview, deviceRows as rows, deviceSnapshot, deviceStrings as strings, deviceTaskRows, deviceWorkspaceTaskMode, type DeviceData as Data } from './devices-workspace';
import { IconAdd, IconArrowLeft, IconChevronRight, IconMonitor, IconPhone, IconRefresh, IconShieldCheck } from './icons';
import { DesktopViewer } from './DesktopViewer';
import { DeviceTaskPreparation } from './DeviceTaskPreparation';
import { PhoneControlPreparation } from './PhoneControlPreparation';
import { desktopScopes } from './desktop-viewer';
import { useSurfaceEnter } from './library/surface-motion';
import { MotionFeedback, useCollectionMotion } from './library/journey-motion';
import './devices-reading.css';

type Run = (action: string, args?: Data) => Promise<any>;
const name = (peer: Data) => peer.label || peer.name || '已配对设备';
const time = (value: any) => typeof value === 'number' && value > 0 ? new Date(value).toLocaleString() : '尚未读取';
const available = (value: any) => value?.available === true;
const fingerprint = (value: any) => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value) ? value.match(/.{1,8}/g)?.join(' ') : '未取得完整指纹';
const grantDraft = () => ({ optionId: '', toolBudget: '', modelBudget: '', durationMinutes: '15', historyDays: '7', historyReadMethods: [] as string[], additionalOriginOptionIds: [] as string[], forwardingOptionIds: [] as string[], forwardingToolBudget: '', forwardingModelBudget: '', wholeDisplayCapture: false });

function ResourceScope({ resource }: { resource: Data }) {
  const labels: Record<string, string> = { screenMode: '屏幕范围', packageNames: '允许应用', allowWholeDisplayCapture: '整屏截图', allowWholeDisplayInput: '整屏输入', projectId: '准确项目', checkoutId: '准确工作副本', runtimeId: '运行环境', displayId: '指定屏幕', resourceId: '资源身份', kind: '资源类型' };
  return <dl className="pw-facts">{Object.entries(resource).map(([key, value]) => <div key={key}><dt>{labels[key] || key}</dt><dd>{typeof value === 'boolean' ? value ? '允许' : '不允许' : Array.isArray(value) ? value.join('、') : typeof value === 'object' ? JSON.stringify(value) : String(value)}</dd></div>)}</dl>;
}
function GrantScope({ scope }: { scope: Data }) {
  const status = ({ active: '执行授权有效', expired: '执行授权已到期', revoked: '授权已撤销', stale_epoch: '旧授权代次，不能执行', identity_changed: '本机身份不可用，旧授权不能执行' } as Record<string, string>)[scope.state];
  return <div className="pw-source-copy">{scope.receivedScope === true ? <p>上次收到的对端授权回执，执行时仍核对当前状态。</p> : status && <p>{status}</p>}{scope.budgetStatus?.budgetState === 'known' && <p>可分配给新任务：工具 {scope.budgetStatus.toolsAvailableForNewAllocations} 次 · 模型 {scope.budgetStatus.modelsAvailableForNewAllocations} 次</p>}{typeof scope.retainedToolsReservedOrUsed === 'number' && <p>已有占用：工具 {scope.retainedToolsReservedOrUsed} 次 · 模型 {scope.retainedModelsReservedOrUsed} 次，不因本次修改清零。</p>}{scope.label && <h4>{scope.label}</h4>}{scope.effectSummary && <p>{scope.effectSummary}</p>}<p>{strings(scope.capabilities).map(method => deviceMethodLabels[method] || method).join(' · ') || '可用方法尚未提供'}</p>{strings(scope.toolNames).length > 0 && <p>准确工具：{strings(scope.toolNames).join('、')}</p>}{rows(scope.resources).map((resource, index) => <ResourceScope key={index} resource={resource} />)}{scope.isolation === false && <p>执行代码可能访问该系统用户可访问的资源，项目目录不是系统沙箱。</p>}{typeof scope.toolBudget === 'number' && <p>授权期内累计：工具 {scope.toolBudget} 次 · 模型 {scope.modelBudget} 次 · 至 {time(scope.expiresAt)}</p>}{scope.historyRead ? <p>结果读取至 {time(scope.historyRead.expiresAt)} · {strings(scope.historyRead.methods).map(method => deviceMethodLabels[method] || method).join('、')}</p> : typeof scope.expiresAt === 'number' && <p>执行到期后未另行授权读取结果。</p>}{scope.allowedOrigins && <p>原发起设备：{strings(scope.allowedOrigins).join('、')}</p>}{scope.forwardingScope ? <><p>下游允许方法：{strings(scope.forwardingScope.capabilities).map(method => deviceMethodLabels[method] || method).join('、')}</p><p>下游准确工具：{strings(scope.forwardingScope.toolNames).join('、')}</p><p>下游累计：工具 {scope.forwardingScope.toolBudget} 次 · 模型 {scope.forwardingScope.modelBudget} 次</p>{rows(scope.forwardingScope.targetResources).map((target, index) => <div key={index}><p>下游设备 {target.targetDeviceId}</p><ResourceScope resource={target.resourceBinding || {}} /></div>)}</> : scope.allowDelegation === true ? <p>仅允许列明来源转交，不继续委派。</p> : <p>未授权转交来源或继续委派。</p>}</div>;
}

function GrantSelection({ review, busy, confirm }: { review: Data; busy: boolean; confirm: (args: Data) => void }) {
  const [draft, setDraft] = useState<Data>(grantDraft), [error, setError] = useState('');
  const view = review.view, options = rows(view.grantOptions), option = options.find(row => row.optionId === draft.optionId);
  const update = (key: string, value: any) => setDraft(old => ({ ...old, [key]: value }));
  const toggle = (key: string, id: string, selected: boolean) => update(key, selected ? [...strings(draft[key]), id] : strings(draft[key]).filter(value => value !== id));
  return <>
    <p>{review.kind === 'grant' ? '选择将替换本机授予对端的范围。已有用量不会清零。' : '明确选择另一台设备能在这里做什么。双方分别决定各自的授权。'}</p>
    {view.currentGrant && <Accordion><AccordionItem id="current" title="当前权限"><GrantScope scope={view.currentGrant} /></AccordionItem></Accordion>}
    <SelectField label="应用、项目或本机能力范围" value={draft.optionId} options={options.map(row => ({ id: row.optionId, label: row.label || row.optionId }))} onChange={id => { const chosen = options.find(row => row.optionId === id); if (chosen) { setDraft({ ...grantDraft(), optionId: id, toolBudget: String(Math.max(chosen.toolBudgetMin || 0, Math.min(12, chosen.toolBudgetMax))), modelBudget: String(Math.max(chosen.modelBudgetMin || 0, Math.min(3, chosen.modelBudgetMax))) }); setError(''); } }} isDisabled={busy} />
    {!options.length && <p>{view.grantOptionsUnavailableReason || '当前没有可授予的原生范围，请先核对本机设置。'}</p>}
    {option && <>
      <GrantScope scope={option} />
      <TextField label={`累计工具次数（${option.toolBudgetMin || 0}–${option.toolBudgetMax}）`} value={draft.toolBudget} onChange={value => update('toolBudget', value)} isDisabled={busy} />
      <TextField label={`累计模型次数（${option.modelBudgetMin || 0}–${option.modelBudgetMax}）`} value={draft.modelBudget} onChange={value => update('modelBudget', value)} isDisabled={busy} />
      <SelectField label="执行授权期限" value={draft.durationMinutes} onChange={value => update('durationMinutes', value)} options={[{ id: '15', label: '15 分钟' }, { id: '60', label: '1 小时' }, { id: '1440', label: '1 天' }, { id: '43200', label: '30 天' }]} isDisabled={busy} hint={`原范围上限 ${time(option.expiresAtMax)}，最终期限以接下来的准确审阅为准。`} />
      {option.canGrantWholeDisplayCapture === true && <CheckboxField isSelected={draft.wholeDisplayCapture} onChange={value => update('wholeDisplayCapture', value)} isDisabled={busy}>允许整屏截图（可能包含其他应用或通知，与整屏输入分开）</CheckboxField>}
      <Accordion>
        {strings(option.historyReadMethods).length > 0 && <AccordionItem id="history" title="执行到期后的结果读取">{strings(option.historyReadMethods).map(method => <CheckboxField key={method} isSelected={strings(draft.historyReadMethods).includes(method)} onChange={value => toggle('historyReadMethods', method, value)} isDisabled={busy}>{deviceMethodLabels[method] || method}</CheckboxField>)}<SelectField label="结果读取期限" value={draft.historyDays} onChange={value => update('historyDays', value)} options={[{ id: '1', label: '1 天' }, { id: '7', label: '7 天' }, { id: '30', label: '30 天' }]} isDisabled={busy || !draft.historyReadMethods.length} hint="只有勾选的方法获得读取权限，不延长执行期限。" /></AccordionItem>}
        <AccordionItem id="origins" title={`转交来源 · 已选 ${draft.additionalOriginOptionIds.length} 台`}><p>直接来自本次配对设备的任务已包含。其他原发起设备需要逐一明确选择。</p>{rows(view.originOptions).map(origin => <CheckboxField key={origin.optionId} isSelected={draft.additionalOriginOptionIds.includes(origin.optionId)} onChange={value => toggle('additionalOriginOptionIds', origin.optionId, value)} isDisabled={busy}>{origin.label || origin.optionId}</CheckboxField>)}{!rows(view.originOptions).length && <p>没有其他已认证来源。</p>}</AccordionItem>
        {option.allowDelegation === true && <AccordionItem id="forwarding" title={`继续委派 · 已选 ${draft.forwardingOptionIds.length} 个范围`}>{rows(view.forwardingOptions).map(target => <div key={target.optionId}><CheckboxField isSelected={draft.forwardingOptionIds.includes(target.optionId)} onChange={value => toggle('forwardingOptionIds', target.optionId, value)} isDisabled={busy}>{target.label || target.optionId}</CheckboxField><ResourceScope resource={target.resourceBinding || {}} /></div>)}{draft.forwardingOptionIds.length > 0 && <><TextField label="下游累计工具次数" value={draft.forwardingToolBudget} onChange={value => update('forwardingToolBudget', value)} isDisabled={busy} /><TextField label="下游累计模型次数" value={draft.forwardingModelBudget} onChange={value => update('forwardingModelBudget', value)} isDisabled={busy} /><p>发起任务时仍需分配互不重复的份额，原生会核对每个下游的真实上限。</p></>}</AccordionItem>}
      </Accordion>
    </>}
    {error && <p role="alert">{error}</p>}<Button disabled={busy || !option} onClick={() => { try { confirm(deviceGrantInput(review, draft)); } catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); } }}>核对准确权限{review.kind === 'pair' ? '与双端身份' : '修改'}</Button>
  </>;
}

export function DevicesWorkspace({ state, data, supported, run, registerTransientBack, openModels }: { state: Data; data: Data; supported: (action: string) => boolean; run: Run; registerTransientBack: (handler: (() => boolean) | null) => void; openModels?: () => void }) {
  const [snapshot, setSnapshot] = useState<Data | null>(null), [peerId, setPeerId] = useState(''), [form, setForm] = useState(''), [raw, setRaw] = useState('');
  const [address, setAddress] = useState(''), [inviteId, setInviteId] = useState(''), [host, setHost] = useState(''), [port, setPort] = useState(''), [recoveryAddresses, setRecoveryAddresses] = useState<string[]>([]);
  const [invitation, setInvitation] = useState(''), [review, setReview] = useState<Data | null>(null), [ack, setAck] = useState(false), [future, setFuture] = useState(false);
  const [workBusy, setWorkBusy] = useState(false), [controlBusy, setControlBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('');
  const [taskDetails, setTaskDetails] = useState<Record<string, Data>>({}), [historyReceipt, setHistoryReceipt] = useState<Data | null>(null), [historyStopSubmitted, setHistoryStopSubmitted] = useState(false);
  const [preparingDeviceWork, setPreparingDeviceWork] = useState(false);
  const [desktopOpen, setDesktopOpen] = useState(false), [query, setQuery] = useState('');
  const readingRoot = useRef<HTMLElement>(null), readingPositions = useRef({ list: 0, detail: 0 }), previousReadingView = useRef('list');
  const rememberReadingPosition = (view: 'list' | 'detail') => { readingPositions.current[view] = readingRoot.current?.closest<HTMLElement>('.pw-secondary-scroll')?.scrollTop || 0; };
  const busy = workBusy || controlBusy;
  const alive = useRef(true), scope = useRef(new DeviceRequestScope()), handle = useRef(''), selectedPeer = useRef(peerId), resumed = useRef(state.webWorkspaceResumeRevision), nativeRevision = useRef(state.deviceWorkspaceRevision);
  selectedPeer.current = peerId;
  const acceptSnapshot = (value: any) => { const actual = deviceSnapshot(value); setSnapshot(old => !old || actual.capturedAt >= old.capturedAt ? actual : old); };
  const invoke = async (action: string, args: Data = {}, accept?: (value: Data) => void, lane = 'work') => {
    if (!alive.current || document.visibilityState !== 'visible' || scope.current.hasPending(lane)) return;
    if (lane === 'control' && action !== 'devices.confirm') {
      scope.current.invalidate(true); setWorkBusy(false); setReview(null); handle.current = ''; setInvitation(''); setForm(''); setRaw('');
    }
    const ticket = scope.current.begin(lane); if (!ticket) return;
    const setPending = lane === 'control' ? setControlBusy : setWorkBusy;
    setPending(true); setError('');
    try {
      if (!supported(action)) throw new Error('当前宿主尚未接通这项设备操作。');
      const response = await run(action, args);
      if (alive.current && scope.current.owns(ticket)) {
        if (action === 'devices.openTask' || action === 'devices.discard') accept?.(response);
        else { const receipt = deviceReceipt(response); if (receipt.snapshot) acceptSnapshot(receipt.snapshot); accept?.(receipt.value); }
      } else if (typeof response?.value?.viewHandle === 'string') {
        void run('devices.discard', { viewHandle: response.value.viewHandle }).catch(() => {});
      }
    } catch (failure) { if (alive.current && scope.current.owns(ticket)) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { if (scope.current.finish(ticket) && alive.current) setPending(false); }
  };
  const refresh = () => { void invoke('devices.snapshot', selectedPeer.current ? { deviceId: selectedPeer.current } : {}); };
  const closeReview = () => {
    const old = handle.current; handle.current = ''; scope.current.invalidate(document.visibilityState === 'visible');
    setReview(null); setAck(false); setFuture(false); setHistoryReceipt(null); setHistoryStopSubmitted(false); setWorkBusy(false); setControlBusy(false);
    if (old) void run('devices.discard', { viewHandle: old }).catch(() => {});
  };
  const acceptReview = (value: Data) => { const actual = deviceReview(value); handle.current = actual.viewHandle; setReview(actual); setAck(false); setFuture(false); setHistoryReceipt(null); setHistoryStopSubmitted(false); setRaw(''); setForm(''); setInvitation(''); };
  const clearSensitive = (enabled = false) => {
    setDesktopOpen(false);
    scope.current.invalidate(enabled); handle.current = ''; setReview(null); setAck(false); setFuture(false); setRaw(''); setInvitation('');
    setForm(''); setAddress(''); setHost(''); setPort(''); setRecoveryAddresses([]); setHistoryReceipt(null); setHistoryStopSubmitted(false); setTaskDetails({}); setSnapshot(null); setError(''); setNotice(''); setWorkBusy(false); setControlBusy(false);
    void run('devices.discard', {}).catch(() => {});
  };
  useEffect(() => { if (document.visibilityState === 'visible') { try { acceptSnapshot(data); } catch { /* A summary is not a native device snapshot. */ } } }, [data]);
  useEffect(() => {
    alive.current = true; scope.current.invalidate(document.visibilityState === 'visible'); refresh();
    return () => { alive.current = false; scope.current.invalidate(false); handle.current = ''; void run('devices.discard', {}).catch(() => {}); };
  }, []);
  useEffect(() => { if (resumed.current === state.webWorkspaceResumeRevision) return; resumed.current = state.webWorkspaceResumeRevision; clearSensitive(document.visibilityState === 'visible'); refresh(); }, [state.webWorkspaceResumeRevision]);
  useEffect(() => {
    const changed = () => { clearSensitive(document.visibilityState === 'visible'); if (document.visibilityState === 'visible') refresh(); };
    const hidden = () => clearSensitive(false);
    document.addEventListener('visibilitychange', changed); window.addEventListener('pagehide', hidden);
    return () => { document.removeEventListener('visibilitychange', changed); window.removeEventListener('pagehide', hidden); };
  }, []);
  useEffect(() => {
    if (busy || nativeRevision.current === state.deviceWorkspaceRevision || document.visibilityState !== 'visible') return;
    nativeRevision.current = state.deviceWorkspaceRevision; refresh();
  }, [state.deviceWorkspaceRevision, busy]);
  useEffect(() => { registerTransientBack(() => {
    if (preparingDeviceWork) return true;
    if (review) { closeReview(); return true; }
    if (invitation) { setInvitation(''); return true; }
    if (form) { clearSensitive(true); refresh(); return true; }
    if (desktopOpen) { setDesktopOpen(false); return true; }
    if (peerId) { setPeerId(''); return true; }
    return false;
  }); return () => registerTransientBack(null); }, [review, invitation, form, desktopOpen, peerId, preparingDeviceWork, registerTransientBack]);
  // Keep list/detail reading position without changing native request or Back ownership.
  useLayoutEffect(() => {
    const view = desktopOpen ? 'desktop' : peerId ? 'detail' : 'list';
    if (view === previousReadingView.current) return;
    const previous = previousReadingView.current, scroll = readingRoot.current?.closest<HTMLElement>('.pw-secondary-scroll');
    if (scroll) scroll.scrollTop = view === 'list' ? readingPositions.current.list : view === 'detail' && previous === 'desktop' ? readingPositions.current.detail : 0;
    previousReadingView.current = view;
    if (view !== 'desktop') readingRoot.current?.querySelector<HTMLElement>('.pw-device-heading h2')?.focus({ preventScroll: true });
  }, [peerId, desktopOpen]);
  const peer = rows(snapshot?.peers).find(row => row.deviceId === peerId), local = snapshot?.local || {}, actions = snapshot?.actions || {};
  const tasks = deviceTaskRows(state.tasks, snapshot, peerId);
  const phoneCapabilities = rows(peer?.capabilitySnapshot?.capabilities).filter(item => ['phone.tools.main', 'phone.tools.background', 'agent.goal'].includes(item.id));
  const phonePeer = /android|phone/i.test(peer?.platform || '') || phoneCapabilities.some(item => String(item.id).startsWith('phone.'));
  const desktopPeer = desktopScopes(peer || {}).length > 0 || /windows/i.test(peer?.platform || '') || rows(peer?.capabilitySnapshot?.capabilities).some(item => String(item.id).startsWith('desktop.'));
  const localPhone = rows(state.phoneReadiness?.groups);
  const phoneStatus = (id: string) => { const value = localPhone.find(row => row.id === id); return ({ ready: '本机检查可用', blocked: '需要准备', unsupported: '本机不支持', unknown: '尚未核实' } as Record<string,string>)[value?.state] || '尚未核实'; };
  const openPhoneSetup = (section: 'accessibility' | 'background') => { if (busy) return; clearSensitive(true); void run('settings.openSystem', { section }).catch(failure => setError(failure instanceof Error ? failure.message : String(failure))); };
  const actionReview = (operation: string, extra: Data = {}) => { void invoke('devices.reviewAction', { operation, ...extra }, acceptReview, operation.startsWith('task.') ? 'control' : 'work'); };
  const confirmReview = (extra: Data = {}) => {
    const original = review; if (!original || !ack || busy) return;
    handle.current = ''; setReview(null);
    void invoke('devices.confirm', { viewHandle: original.viewHandle, acknowledged: true, ...(original.kind === 'goal' ? { useForFutureGoals: future } : {}), ...extra }, value => {
      if (value.nativeCallReturned !== true || value.kind !== original.kind) throw new Error('原设备操作尚未取得匹配回执，请刷新核对。');
      setNotice(original.kind === 'pair' ? '本机指纹与权限确认已提交。等待双端完成握手，以原配对状态为准。' : original.kind === 'resume' ? extra.approved === true ? '已处理这次原执行的恢复请求，实际进展以原任务回执为准。' : '已拒绝这次恢复请求，原任务未因本次拒绝而继续。' : original.kind === 'action' && original.view.operation?.startsWith('task.') ? '已发出原任务控制请求。实际暂停、继续或停止以原任务回执为准。' : '原生处理已返回，请核对下方实际状态。');
    }, original.kind === 'resume' || original.kind === 'action' && original.view.operation?.startsWith('task.') ? 'control' : 'work');
  };
  const queryHistory = (stop = false) => {
    const original = review; if (original?.kind !== 'history' || busy || stop && (!ack || historyStopSubmitted)) return;
    if (stop) setHistoryStopSubmitted(true);
    void invoke(stop ? 'devices.stopHistory' : 'devices.queryHistory', { viewHandle: original.viewHandle, ...(stop ? { acknowledged: true } : {}) }, value => {
      const actual = deviceHistoryReceipt(value, original); setHistoryReceipt(actual.receipt); setHistoryStopSubmitted(actual.stopSubmitted); setAck(false);
    });
  };
  const openRecovery = (original: Data, providing: boolean) => { setInviteId(original.inviteId); setHost(''); setPort(''); setAddress(''); if (providing) void invoke('devices.recoveryAddresses', {}, value => { setRecoveryAddresses(strings(value.addresses)); setForm('recoveryProvide'); }); else setForm('recoveryRead'); };
  const originalView = review?.view || {}, exactGrant = originalView.grantReview;
  const peers = rows(snapshot?.peers), pairings = rows(snapshot?.pendingPairings);
  const visiblePeers = peers.filter(peer => [name(peer), peer.deviceId, peer.platform].filter(Boolean).join(' ').toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()));
  const peerSections = [
    { id: 'online', title: '已连接', peers: visiblePeers.filter(peer => !peer.revoked && peer.connection?.state === 'ONLINE') },
    { id: 'other', title: '未连接或待核对', peers: visiblePeers.filter(peer => !peer.revoked && peer.connection?.state !== 'ONLINE') },
    { id: 'revoked', title: '已撤销信任', peers: visiblePeers.filter(peer => peer.revoked) },
  ];
  const deviceView = desktopOpen ? 'desktop' : peerId ? `detail:${peerId}` : 'list';
  // The media viewport keeps its real geometry; only directory/detail surfaces move.
  useSurfaceEnter(readingRoot, deviceView, { selector: '', depth: desktopOpen ? 2 : peerId ? 1 : 0, active: !desktopOpen && !!snapshot });
  useCollectionMotion(readingRoot, JSON.stringify([state.webWorkspaceResumeRevision, peerId, query.trim()]),
    JSON.stringify([peerSections.map(section => [section.id, section.peers.map(row => row.deviceId)]), pairings.map(row => [row.candidateId || row.inviteId, row.state]), tasks.slice(0, 40).map(task => [task.id, task.status])]),
    { active: !desktopOpen && !!snapshot });
  const receiverLabel = local.receiverEnabled === true ? local.listenerReady === true ? '本机接收通道已启动' : '接收已启用，通道仍待就绪' : local.receiverEnabled === false ? '设备接收未启用' : '接收状态尚未确认';
  const taskLabels: Record<string, string> = { queued: '等待执行', preparing: '准备中', running: '执行中', pausing: '正在暂停', paused: '已暂停', stopping: '正在停止', verifying: '正在核对', needs_confirmation: '成果待确认', awaiting_user: '待你处理', waiting_user: '待你处理', waiting_approval: '等待授权', awaiting_approval: '等待授权', waiting_permission: '等待授权', interrupted: '已中断', completed: '已完成', failed: '未完成', cancelled: '已停止', canceled: '已停止', stopped: '已停止', reconciling: '结果待核对', reconciliation_required: '结果待核对', unknown: '结果待核对' };

  return <>
    <section ref={readingRoot} className="pw-settings-section pw-devices-reading pw-depth-collaboration" data-device-view={desktopOpen ? 'desktop' : peerId ? 'detail' : 'list'}>
      {(desktopOpen || peerId) && <header className="pw-device-heading">
        <div className="pw-device-heading-copy">
          {peerId && !desktopOpen && <Button variant="ghost" className="pw-device-back" leadingIcon={IconArrowLeft} disabled={busy} onClick={() => { setPeerId(''); setForm(''); setRaw(''); }}>所有设备</Button>}
          <h2 tabIndex={-1}>{desktopOpen ? '电脑桌面' : peer ? name(peer) : '设备详情'}</h2>
          {!desktopOpen && peer && <p>{peer.platform || '已配对设备'}</p>}
        </div>
        {!desktopOpen && <div className="pw-device-heading-actions">
          <Button variant="ghost" iconOnly leadingIcon={IconRefresh} aria-label="刷新原设备状态" title="刷新原设备状态" disabled={busy} onClick={refresh} />
        </div>}
      </header>}
      {desktopOpen && peer && <DesktopViewer key={peer.deviceId} peer={peer} supported={supported} run={run} onClose={() => setDesktopOpen(false)} />}
      {desktopOpen && !peer && <p role="alert">设备状态已变化。<Button variant="secondary" onClick={() => setDesktopOpen(false)}>返回设备</Button></p>}
      {!desktopOpen && <>
        {error && <MotionFeedback identity={error} className="pw-device-feedback" role="alert">{error}</MotionFeedback>}
        {notice && <MotionFeedback identity={notice} className="pw-device-feedback" role="status">{notice}</MotionFeedback>}
        {busy && <MotionFeedback identity="reading" role="status" className="pw-device-loading">正在读取原设备回执…</MotionFeedback>}
        {!snapshot && !busy && <div className="pw-device-empty"><h3>尚未读取设备状态</h3><p>读取后查看已配对设备与连接状态。</p><Button variant="secondary" onClick={refresh}>读取设备状态</Button></div>}
        {snapshot && !peerId && <>
          {supported('deviceTaskScope.prepare') && <section className="pw-device-section" aria-label="新建设备工作"><header className="pw-device-section-heading"><div><h3>让设备完成一件事</h3><p>写目标，选择准确设备范围，再开始。也可以进入电脑详情直接查看桌面。</p></div></header><div className="pw-form-actions"><Button disabled={busy} onClick={() => setForm('work')}>交给助手完成目标</Button>{supported('phoneControl.prepare') && <Button variant="secondary" disabled={busy} onClick={() => setForm('manual')}>手动控制手机</Button>}</div></section>}
          {pairings.length > 0 && <section className="pw-device-section" aria-label="等待核对的原配对">
            <header className="pw-device-section-heading"><h3>配对待确认</h3><span>{pairings.length} 项</span></header>
            <div className="pw-device-attention">
              {pairings.map(candidate => <article className="pw-device-pairing" key={candidate.candidateId || candidate.inviteId} data-motion-key={`pairing:${candidate.candidateId || candidate.inviteId}`}>
                <div><h4>{candidate.label || '原设备配对'}</h4><p>{candidate.state === 'waiting_remote_confirmation' ? '等待另一台设备确认' : candidate.state === 'confirmed_for_mtls' ? '身份已确认，等待完成握手' : candidate.state === 'paired' ? '配对回执已产生，请核对当前信任' : '等待本机核对'}</p></div>
                <Button variant="secondary" disabled={busy || candidate.advanceAvailable !== true && candidate.reviewAvailable !== true && !['', 'pending_owner_confirmation'].includes(candidate.state || '')} onClick={() => { void invoke(candidate.advanceAvailable ? 'devices.advancePairing' : 'devices.reviewPairing', { candidateId: candidate.candidateId || candidate.inviteId }, candidate.advanceAvailable ? () => setNotice('已查询原配对状态，未重新发起配对。') : acceptReview); }}>{candidate.advanceAvailable ? '查询原配对进度' : '审阅范围与双端身份'}</Button>
              </article>)}
            </div>
          </section>}
          <section className="pw-device-section" aria-label="已配对设备">
            <header className="pw-device-section-heading pw-device-directory-heading">
              <h3>{peers.length ? `已配对设备 · ${peers.length} 台` : '配对设备'}</h3>
              <div className="pw-device-heading-actions">
                <Button variant="ghost" iconOnly leadingIcon={IconRefresh} aria-label="刷新设备状态" title="刷新设备状态" disabled={busy} onClick={refresh} />
                {peers.length > 0 && <Button leadingIcon={IconAdd} disabled={busy} onClick={() => { setForm('add'); setRaw(''); setAddress(''); }}>配对设备</Button>}
              </div>
            </header>
            {(peers.length > 5 || query) && <SearchField className="pw-device-search" fieldClassName="pw-collaboration-search" shape="rounded" aria-label="搜索已配对设备" placeholder="搜索名称或设备" value={query} onChange={setQuery} />}
            {peers.length ? <div className="pw-device-directory">{peerSections.filter(section => section.peers.length > 0).map(section => <section className="pw-collaboration-group" key={section.id} aria-label={section.title}><header className="pw-collaboration-group-heading"><h3>{section.title}</h3><span>{section.peers.length} 台</span></header><div className="pw-device-list">
              {section.peers.map(row => <Button key={row.deviceId} data-motion-key={`peer:${row.deviceId}`} variant="ghost" className="pw-device-row" leadingIcon={/android|ios|phone/i.test(row.platform || '') ? IconPhone : IconMonitor} trailingIcon={IconChevronRight} disabled={busy} aria-label={`查看 ${name(row)} 的设备与授权`} onClick={() => { rememberReadingPosition('list'); setPeerId(row.deviceId); setError(''); }}>
                <span className="pw-device-row-copy"><span className="pw-device-row-name">{name(row)}</span><span className="pw-device-row-status">{deviceConnectionLabel(row)}</span><small>{row.platform || '已配对设备'} · {deviceTaskRows(state.tasks, snapshot, row.deviceId).length} 项关联任务</small></span>
              </Button>)}
            </div></section>)}{query && !visiblePeers.length && <div className="pw-device-empty"><h3>没有匹配的设备</h3><p>换个名称搜索，或查看全部已配对设备。</p><Button variant="secondary" onClick={() => setQuery('')}>清除搜索</Button></div>}</div> : <div className="pw-device-empty">
              <span className="pw-device-empty-icon" aria-hidden="true"><IconMonitor size={24} /><IconPhone size={19} /></span>
              <h3>连接另一台设备</h3>
              <p>同一局域网先交换短期邀请，核对两端身份与范围。这里不会扫描附近设备，也不会仅凭同一 Wi-Fi 自动信任。</p>
              <Button variant={pairings.length ? 'secondary' : 'primary'} disabled={busy} onClick={() => { setForm('add'); setRaw(''); setAddress(''); }}>{pairings.length ? '配对其他设备' : '配对设备'}</Button>
            </div>}
          </section>
          <section className="pw-device-section" aria-label="本机接收">
            <header className="pw-device-section-heading"><h3>本机接收</h3></header>
            <Accordion className="pw-device-disclosures pw-device-local-settings">
              <AccordionItem id="phone-preparation" title="让另一台设备使用这台手机" description="主屏与独立屏分别准备">
                <p>只配置这台手机，不会启动远程任务。配对时还需明确选应用、节点或整屏输入范围。</p>
                <dl className="pw-facts"><div><dt>主屏读取</dt><dd>{phoneStatus('mainObservation')}</dd></div><div><dt>主屏输入</dt><dd>{phoneStatus('mainExecution')}</dd></div><div><dt>独立屏准备</dt><dd>{phoneStatus('backgroundPreparation')}</dd></div><div><dt>独立屏执行</dt><dd>{phoneStatus('backgroundExecution')}</dd></div></dl>
                <div className="pw-form-actions"><Button variant="secondary" disabled={busy || !supported('settings.openSystem')} onClick={() => openPhoneSetup('accessibility')}>准备主屏访问</Button><Button variant="secondary" disabled={busy || !supported('settings.openSystem')} onClick={() => openPhoneSetup('background')}>准备独立屏连接</Button></div>
                <p>独立屏连接成功不等于已建立显示、输入与截图能力。主屏会影响你正在用的界面；整屏截图与整屏输入分别授权。</p>
              </AccordionItem>
              <AccordionItem id="receiver" title="这台手机" description={receiverLabel} icon={IconPhone}>
                <p>本机接收、双端身份与每项授权分别核对。连接状态不代表任务已经停止或完成。</p>
                {local.receiverReason && <p>{local.receiverReason}</p>}
                <Button variant="secondary" disabled={busy || !available(actions[local.receiverEnabled ? 'disable' : 'enable'])} onClick={() => actionReview(local.receiverEnabled ? 'receiver.disable' : 'receiver.enable')}>{local.receiverEnabled ? '核对并停用接收' : '核对并启用接收'}</Button>
                {!available(actions[local.receiverEnabled ? 'disable' : 'enable']) && <p>{actions[local.receiverEnabled ? 'disable' : 'enable']?.reason}</p>}
              </AccordionItem>
              {(rows(snapshot.pairingOffersToRecover).length > 0 || rows(snapshot.committedPairingsToRecover).length > 0) && <AccordionItem id="recover" title="核对已有配对结果" description="找回已提交的结果，不重新授权">
                <p>只读取双方已确认的原结果，不重做配对、不延长旧权限。</p>
                {rows(snapshot.pairingOffersToRecover).map(row => <div className="pw-device-history-entry" key={row.inviteId}><p>原邀请 {row.inviteId}</p><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => openRecovery(row, false)}>读取已提交结果</Button>{row.abandonAvailable === true && <Button variant="ghost" disabled={busy} onClick={() => { void invoke('devices.reviewAbandonment', { inviteId: row.inviteId }, acceptReview); }}>核对并放弃本机待配对</Button>}</div></div>)}
                {rows(snapshot.committedPairingsToRecover).map(row => <div className="pw-device-history-entry" key={row.inviteId}><p>为原设备 {row.peerDeviceId} 提供已提交结果</p><Button variant="secondary" disabled={busy || row.recoveryState !== 'available'} onClick={() => openRecovery(row, true)}>开启原结果只读窗口</Button></div>)}
              </AccordionItem>}
            </Accordion>
          </section>
        </>}
        {peerId && !peer && snapshot && <p className="pw-device-feedback" role="alert">这台设备不在当前读取页。保留原身份，请返回后刷新核对。</p>}
        {peer && <>
          <section className="pw-device-connection" aria-label="这台设备的连接状态">
            <MotionFeedback identity={JSON.stringify([peer.deviceId, peer.connection?.state, peer.revoked])} initial={false} className="pw-device-connection-heading" role="status"><IconMonitor size={20} aria-hidden="true" /><h3>{deviceConnectionLabel(peer)}</h3></MotionFeedback>
            <p>{peer.connection?.state === 'ONLINE' ? peer.connection?.path === 'RELAY_WSS' ? '当前通过出站 WSS 中继连接。' : peer.connection?.path === 'DIRECT_TLS' ? '当前通过直连 TLS 通道连接。' : '已连接，传输路径仍待核对。' : '未确认的操作仍需查看执行结果，离线不代表停止。'}</p>
            <dl className="pw-facts"><div><dt>控制通道</dt><dd>{peer.connection?.state === 'ONLINE' ? `最近回执在线 · ${peer.connection?.path === 'RELAY_WSS' ? '临时 WSS 中继' : peer.connection?.path === 'DIRECT_TLS' ? '直连 mTLS' : '路径待核对'}` : '尚未核对为在线'}</dd></div>{desktopPeer && <div><dt>桌面画面</dt><dd>进入桌面后单独连接并核对，不随控制通道判为可用</dd></div>}</dl>
            {peer.connection?.unconfirmedTransportWrite === true && <p className="pw-device-inline-notice">有操作结果待确认，请查看原执行结果。</p>}
            {peer.revoked && <p>{peer.revocation?.peerAcknowledged === true ? '本机信任已撤销，对端已回执。' : '本机信任已撤销，对端仍未确认。'}</p>}
            <div className="pw-form-actions">
              {desktopPeer && <Button disabled={busy || !desktopScopes(peer).length} leadingIcon={IconMonitor} onClick={() => { rememberReadingPosition('detail'); setDesktopOpen(true); }}>查看与手动控制电脑</Button>}
              {phonePeer && !peer.revoked && supported('phoneControl.prepare') && <Button disabled={busy} onClick={() => setForm('manual')}>手动控制手机</Button>}
              {!peer.revoked && supported('deviceTaskScope.prepare') && <Button variant={desktopPeer ? 'secondary' : 'primary'} disabled={busy} onClick={() => setForm('work')}>让设备完成目标</Button>}
              {available(peer.actions?.connect) && <Button disabled={busy} onClick={() => actionReview('peer.connect', { deviceId: peer.deviceId })}>核对并连接</Button>}
              {available(peer.actions?.disconnect) && <Button variant="ghost" disabled={busy} onClick={() => actionReview('peer.disconnect', { deviceId: peer.deviceId })}>停止主动连接</Button>}
            </div>
          </section>
          {desktopPeer && <p className="pw-field-hint">纯手控不需要安装或登录 Codex、Claude。先只读查看，再单独申请本次输入控制；与 AI 输入互斥。{!desktopScopes(peer).length && '当前没有收到 desktop.viewer 屏幕范围，请在电脑端明确授予后刷新。'}</p>}
          {phonePeer && <section className="pw-device-section" aria-label="手机工作方式"><header className="pw-device-section-heading"><h3>在这台手机上工作</h3></header><p>写目标后，选择主屏或独立后台屏及准确应用范围，再审阅开始。“完成目标”由模型操作；“手动控制”由你刷新静态画面后逐次点击、滑动或输入，不是实时视频镜像。</p>
            {phoneCapabilities.map(cap => <p key={cap.id}>{cap.id === 'phone.tools.main' ? '手机主屏工具' : cap.id === 'phone.tools.background' ? '独立后台屏工具' : '交给对端模型处理目标'}：{peer.capabilitySnapshot?.validUntil <= Date.now() ? '上次读取已到期，需刷新核对' : cap.ready === true ? '对端报告就绪，仍需本次范围授权' : cap.canSubmitForLocalReview === true ? '可交给对端主人审阅，尚未开始' : cap.reason || '对端尚未报告就绪'}</p>)}
            {!phoneCapabilities.length && <p>尚未读到手机工具能力。请在对端准备主屏访问或独立屏连接、启用接收，再刷新。</p>}<Button variant="ghost" disabled={busy} onClick={refresh}>重新读取对端能力</Button></section>}
          <Accordion className="pw-device-disclosures">
            <AccordionItem id="network-help" title="换了网络或连接不上" description="先核对控制通道，再核对画面">
              <p>同一局域网：对端保持接收服务开启，使用它当前显示的数字地址与短期邀请。换网不创建新身份；用原设备的邀请更新地址后重新连接。</p>
              <p>离开局域网：已有临时 WSS 租约只连控制通道，不包含桌面视频。桌面默认依赖双方可直达网络；TURN 只可能转发媒体，不能代替已认证控制通道。</p>
              <p>断开只关闭连接，不代表远端任务停止。先查看下方原任务与操作回执；未确认的写入不重放。</p>
              {!peer.revoked && <Button variant="secondary" disabled={busy} onClick={() => { setForm('connection'); setRaw(''); setAddress(''); }}>核对原身份并更新连接方式</Button>}
            </AccordionItem>
            <AccordionItem id="capabilities" title="能力与两端权限" description="查看范围，调整本机授予的权限" icon={IconShieldCheck}>
              {rows(peer.capabilitySnapshot?.capabilities).map(cap => <p key={cap.id}>{cap.id} · {peer.capabilitySnapshot?.validUntil <= Date.now() ? '读取已过期，请刷新' : cap.ready === true && cap.authorized === true ? '核对时可用' : cap.reason || '条件尚未就绪'}</p>)}
              <h4>允许这台设备在本机使用</h4>{rows(peer.grantScopes).map((scope, index) => <GrantScope key={index} scope={scope} />)}
              <h4>本机可在对端使用</h4>{rows(peer.receivedGrantScopes).map((scope, index) => <GrantScope key={index} scope={scope} />)}
              {peer.hasMoreGrantScopes || peer.hasMoreReceivedGrantScopes ? <p>当前是一页已读取授权，还有未展开的记录。</p> : null}
              {!peer.revoked && <Button variant="secondary" disabled={busy} onClick={() => { void invoke('devices.reviewPeerGrant', { deviceId: peer.deviceId }, acceptReview); }}>调整本机授予的权限</Button>}
            </AccordionItem>
            <AccordionItem id="identity" title="身份与连接设置" description="完整指纹、连接地址与信任管理">
              <p className="pw-source-copy">设备 {peer.deviceId} · 授权版本 {peer.authorizationEpoch}</p>
              <p className="pw-source-copy">完整证书指纹：{fingerprint(peer.certificateFingerprint || peer.fingerprint)}</p>
              <div className="pw-form-actions">
                {!peer.revoked && <Button variant="secondary" disabled={busy} onClick={() => { setForm('connection'); setRaw(''); setAddress(''); }}>更新连接地址</Button>}
                {available(peer.actions?.revoke) && <Button variant="ghost" disabled={busy} onClick={() => actionReview('peer.revoke', { deviceId: peer.deviceId })}>核对并撤销本机信任</Button>}
              </div>
            </AccordionItem>
          </Accordion>
        </>}
        {tasks.length > 0 && <section className="pw-device-section pw-device-tasks" aria-label="原设备任务">
          <header className="pw-device-section-heading"><div><h3>关联任务</h3><p>{peer ? '这台设备参与的原任务' : '设备参与的原任务与成果'}</p></div><span>{tasks.length} 项</span></header>
          <div className="pw-device-task-list">
            {tasks.slice(0, 40).map(task => {
              const controls = snapshot?.taskActions?.[task.id] || {}, request = controls.resumeRequest;
              const details = taskDetails[task.id], links = rows(details?.history?.links);
              return <article className="pw-device-task" key={task.id} data-motion-key={`task:${task.id}`}>
                <header><h4>{task.goal || '原设备任务'}</h4><span>{taskLabels[task.status] || task.status || '状态待读取'}</span></header>
                {task.summary && <p className="pw-device-task-summary">{task.summary}</p>}
                {deviceWorkspaceTaskMode(task.raw) === 'manual_phone' && <p>{task.raw?.runId ? '原手控操作与回执保留；离页后不恢复旧输入权限。' : '手控尚未开始。打开后选择一个手机屏幕，明确审阅，再逐次操作。'}</p>}
                {deviceWorkspaceTaskMode(task.raw) === 'agent_device' && !task.raw?.runId && <p>原目标尚未启动。打开后选择设备范围并审阅开始；取消不会影响其他任务。</p>}
                <div className="pw-form-actions">
                  {available(controls.goalReview) && <Button disabled={busy} onClick={() => { void invoke('devices.reviewGoal', { taskId: task.id }, acceptReview); }}>审阅本机模型与原目标</Button>}
                  {available(controls.resultReview) && <Button disabled={busy} onClick={() => { void invoke('devices.reviewResult', { taskId: task.id }, acceptReview); }}>核对成果并结束</Button>}
                  <Button variant="secondary" disabled={busy} onClick={() => { void invoke('devices.openTask', { taskId: task.id }); }}>查看原任务与结果</Button>
                  {(['pause', 'resume', 'stop', 'pendingCancel'] as const).filter(control => !(control === 'resume' && request?.ownerActionRequired === true) && !(control === 'stop' && available(controls.pendingCancel))).map(control => available(controls[control]) && <Button key={control} variant="ghost" disabled={control === 'stop' || control === 'pendingCancel' ? controlBusy : busy} onClick={() => actionReview(`task.${control === 'pendingCancel' ? 'cancelPending' : control}`, { taskId: task.id })}>{deviceOperationLabels[`task.${control === 'pendingCancel' ? 'cancelPending' : control}`]}</Button>)}
                </div>
                {request?.ownerActionRequired === true && <div className="pw-device-task-request"><h4>对方请求继续原执行</h4><p>请求设备 {request.callerDeviceId}</p>{request.stale === true && <p>任务版本已经变化；需要对方重新读取后发起恢复请求。仍可拒绝这次旧请求。</p>}<Button variant="secondary" disabled={busy} onClick={() => { void invoke('devices.reviewResumeRequest', { taskId: task.id, resumeRequestId: request.resumeRequestId, reviewRevision: request.reviewRevision }, acceptReview, 'control'); }}>核对这次恢复请求</Button></div>}
                {rows(task.raw?.approvals).filter(row => row.state === 'pending').slice(0, 16).map(approval => <div className="pw-device-task-request" key={approval.id || approval.approvalId}><p>{approval.message || approval.tool}</p><Button variant="secondary" disabled={busy || typeof (approval.id || approval.approvalId) !== 'string' || !Number.isSafeInteger(approval.expiresAt) || approval.expiresAt <= Date.now()} onClick={() => { void invoke('devices.reviewApproval', { taskId: task.id, approvalId: approval.id || approval.approvalId }, acceptReview); }}>核对这次原操作</Button></div>)}
                <Accordion className="pw-device-task-history"><AccordionItem id={`history-${task.id}`} headingLevel={5} title="设备操作记录" description={details ? `已读取 ${links.length} 项原操作` : undefined}>
                  <Button variant="ghost" disabled={busy} onClick={() => { void invoke('devices.readTask', { taskId: task.id }, value => { const actual = deviceTaskDetails(value, task.id); setTaskDetails(old => ({ ...old, [task.id]: actual })); }); }}>{details ? '刷新原设备操作记录' : '读取原设备操作记录'}</Button>
                  {details && <>
                    {!links.length && <p>本次原生读取没有返回已关联的设备操作。</p>}
                    {links.map(link => <div className="pw-device-history-entry" key={link.operationId}><p>设备 {link.targetDeviceId} · 原操作 {link.operationId}</p><p>{link.terminalReceipt?.terminal === true && link.terminalReceipt?.stopped === true && link.terminalReceipt?.hasUnresolved === false ? '执行端已有结束回执，业务成果仍待核对。' : String(link.holdState || '').includes('unknown') ? '执行效果仍未确认，原占用次数保留。' : link.childBinding ? '执行端已接受，尚未核对最终结果。' : '已保留这次原操作的关联记录。'}</p><Button variant="secondary" disabled={busy} onClick={() => { void invoke('devices.openHistory', { taskId: task.id, operationId: link.operationId }, acceptReview); }}>查看这次原操作回执</Button></div>)}
                    {details.history.hasMore === true && <p>当前显示最近 24 项原设备操作。</p>}
                  </>}
                </AccordionItem></Accordion>
              </article>;
            })}
          </div>
          {tasks.length > 40 && <p className="pw-device-footnote">当前展示前 40 项原设备任务。</p>}
        </section>}
        {snapshot && <Accordion className="pw-device-read-details"><AccordionItem id="device-read-details" title="读取详情" description={snapshot.hasMorePeers || snapshot.hasMorePendingPairings || snapshot.hasMoreTaskActions ? '还有记录未展开' : undefined}>
          <p className="pw-device-footnote">状态读取于 {time(snapshot.capturedAt)}</p>
          {(snapshot.hasMorePeers || snapshot.hasMorePendingPairings || snapshot.hasMoreTaskActions) && <p className="pw-device-footnote">当前展示有界读取页，更多记录需从原对象继续核对。</p>}
        </AccordionItem></Accordion>}
      </>}
    </section>
    <PhoneControlPreparation onPendingChange={setPreparingDeviceWork} open={form === 'manual'} run={run} onClose={() => setForm('')} />
    <DeviceTaskPreparation onPendingChange={setPreparingDeviceWork} open={form === 'work'} run={run} onClose={() => setForm('')} />
    <SettingsDialog isOpen={!!form && form !== 'work'} onClose={() => { clearSensitive(true); refresh(); }} title={form === 'relay' ? '临时中继租约' : form === 'connection' ? '更新原设备连接地址' : form.startsWith('recovery') ? '核对原配对结果' : '配对你的另一台设备'}><div className="pw-collaboration-dialog" data-collaboration-kind="DevicesWorkspace">
      <div className="pw-device-form">
        {error && <p className="pw-device-feedback" role="alert">{error}</p>}
        {busy && <p className="pw-device-loading" role="status">正在读取原设备回执…</p>}
        {form === 'add' && <><p className="pw-device-form-intro">在要连接的手机或电脑打开 PocketWorld 接收端，启用接收并复制短期邀请。下面先读取邀请，再核对双端完整指纹和各自的权限。</p><p>手机端需先准备主屏访问或独立屏；电脑手控需提供屏幕范围。安装电脑代理不是配对或手控的前提。</p></>}
        {['add', 'connection', 'relay'].includes(form) && <section className="pw-device-form-step">
          {form === 'add' && <h3>使用另一台设备的邀请</h3>}
          <TextAreaField label={form === 'relay' ? '已有临时租约' : '原设备短时邀请'} value={raw} onChange={setRaw} isDisabled={busy} hint="仅在当前窗口核对，离开或进入后台后清除。" />
          <Button disabled={busy || !raw.trim() || form === 'add' && !available(actions.join)} onClick={() => { void invoke(form === 'relay' ? 'devices.reviewRelay' : form === 'connection' ? 'devices.reviewConnection' : 'devices.inspectInvitation', { raw, ...(form === 'connection' ? { deviceId: peerId } : {}) }, acceptReview); }}>{form === 'add' ? '核对邀请与权限' : '读取原生审阅'}</Button>
          {form === 'add' && !available(actions.join) && actions.join?.reason && <p>{actions.join.reason}</p>}
        </section>}
        {form === 'add' && <Accordion className="pw-device-form-alternatives">
          <AccordionItem id="create-invitation" title="从这台手机发起配对" description="创建短时邀请，交给另一台设备">
            <SelectField label="本机地址或已配置中继" value={address} onChange={setAddress} options={strings(actions.localAddresses).map(value => ({ id: value, label: value }))} isDisabled={busy} />
            <Button variant="secondary" disabled={busy || !address || !available(actions.invite)} onClick={() => { void invoke('devices.createInvitation', { address }, value => { if (value.displayOnce !== true || typeof value.invitation !== 'string' || !value.invitation) throw new Error('未取得原短时邀请。'); setInvitation(value.invitation); setRaw(''); setForm(''); }); }}>创建本机短时邀请</Button>
            {!available(actions.invite) && <p>{actions.invite?.reason}</p>}
          </AccordionItem>
          <AccordionItem id="relay-invitation" title="两台设备不在同一网络" description="使用已取得的临时中继租约">
            <p>使用你已有的临时租约建立控制通道。这里不会自动注册中继账号、开放端口或启用 VPN；设备权限仍单独核对，桌面画面另需可达媒体通道。</p>
            <Button variant="secondary" disabled={busy} onClick={() => { setForm('relay'); setRaw(''); }}>接收异网连接的临时中继租约</Button>
          </AccordionItem>
        </Accordion>}
        {form === 'connection' && <><SelectField label="已配置的临时中继" value={address} onChange={setAddress} options={Array.from(new Set(rows(actions.relayLeases).map(row => row.serviceBase))).map(value => ({ id: value, label: value }))} isDisabled={busy} /><Button variant="secondary" disabled={busy || !address} onClick={() => { void invoke('devices.reviewConnection', { deviceId: peerId, serviceBase: address }, acceptReview); }}>核对原设备中继地址</Button></>}
        {form === 'relay' && <><p>租约仅提供临时网络通道，不授予设备权限。仍需比较双端指纹并明确选择范围。</p>{rows(actions.relayLeases).map(lease => <div className="pw-source-record" key={lease.serviceBase + lease.inviterDeviceId}><p>{lease.serviceBase} · 至 {time(lease.expiresAt)}</p><Button variant="secondary" disabled={busy} onClick={() => actionReview('relay.remove', { serviceBase: lease.serviceBase, inviterDeviceId: lease.inviterDeviceId })}>核对并移除这份租约</Button></div>)}</>}
        {form === 'recoveryProvide' && <><p>仅开放原结果的短时只读窗口，关闭页面会关闭入口。</p><SelectField label="本机实际地址或原中继" value={address} onChange={setAddress} options={recoveryAddresses.map(value => ({ id: value, label: value }))} isDisabled={busy} /><Button disabled={busy || !address} onClick={() => { void invoke('devices.openRecovery', { inviteId, address }, acceptReview); }}>开启原结果只读窗口</Button></>}
        {form === 'recoveryRead' && <><TextField label="原设备显示的数字地址或已配置中继服务" value={host} onChange={setHost} isDisabled={busy} />{!/^wss:\/\/|^https:\/\//.test(host) && <TextField label="原恢复端口" value={port} onChange={setPort} isDisabled={busy} />}<Button disabled={busy || !host || !/^wss:\/\/|^https:\/\//.test(host) && !/^\d+$/.test(port)} onClick={() => { void invoke('devices.recoverPairing', { inviteId, host, port: /^wss:\/\/|^https:\/\//.test(host) ? 0 : Number(port) }, () => { setForm(''); setNotice('已查询原已提交配对结果，未重新授权。'); }); }}>只读取原配对结果</Button></>}
      </div>
    </div></SettingsDialog>
    <SettingsDialog isOpen={!!invitation} onClose={() => setInvitation('')} title="本机一次性短时邀请"><div className="pw-collaboration-dialog" data-collaboration-kind="DevicesWorkspace"><p>仅交给你信任的另一台设备。邀请到期后需要重新创建；接下来仍需双端指纹与准确权限审阅。</p><TextAreaField label="原邀请，选中后可复制" value={invitation} isReadOnly /><Button variant="secondary" onClick={() => setInvitation('')}>关闭并清除显示</Button></div></SettingsDialog>
    <SettingsDialog isOpen={!!review} onClose={closeReview} title={review?.kind === 'pair' ? '范围与双端身份' : review?.kind === 'grant' ? '原设备权限修改' : review?.kind === 'goal' ? '用本机模型处理原目标' : review?.kind === 'result' ? '核对这次原成果' : review?.kind === 'approval' ? '核对这次设备操作' : review?.kind === 'recovery' ? '原配对结果只读窗口' : review?.kind === 'resume' ? '核对这次恢复请求' : review?.kind === 'history' ? '原设备操作回执' : '核对原设备操作'}><div className="pw-collaboration-dialog" data-collaboration-kind="DevicesWorkspace">
      {review && <div className="pw-device-review">
        {error && <p role="alert">{error}</p>}
        {['pair', 'grant'].includes(review.kind) && !exactGrant ? <GrantSelection key={review.viewHandle} review={review} busy={busy} confirm={args => { void invoke('devices.reviewGrant', args, acceptReview); }} /> : <>
          {exactGrant && <><GrantScope scope={exactGrant} />{review.kind === 'pair' ? <><p className="pw-source-copy">发出邀请的设备：{originalView.inviterDeviceId}<br />完整指纹：{fingerprint(originalView.inviterFingerprint)}</p><p className="pw-source-copy">加入设备：{originalView.joinerDeviceId}<br />完整指纹：{fingerprint(originalView.joinerFingerprint)}</p><p>原邀请至 {time(originalView.expiresAt)}。两台设备均核对一致后才能完成配对。</p></> : <p className="pw-source-copy">已配对设备完整指纹：{fingerprint(originalView.certificateFingerprint)}</p>}</>}
          {review.kind === 'goal' && <><h3>{originalView.goal}</h3><p>本机模型 {originalView.model} · {originalView.provider} · {originalView.source === 'local_api' ? '本机 API 连接' : originalView.source === 'local_account' ? '本机账号' : originalView.source}</p><ResourceScope resource={originalView.resourceBinding || {}} /><p>本次上限：工具 {originalView.toolBudget} 次 · 模型 {originalView.modelBudget} 次 · 至 {time(originalView.deadline)}</p>{originalView.origin && <p>原发起设备 {originalView.origin.originDeviceId} · 请求设备 {originalView.origin.callerDeviceId}</p>}{originalView.contextSummary && <p>{originalView.contextSummary}</p>}{originalView.contextArtifactCount > 0 && <p>附带 {originalView.contextArtifactCount} 个原文件引用，仅本任务使用。</p>}{openModels && <Button variant="secondary" disabled={busy} onClick={() => { closeReview(); openModels(); }}>更换本机模型</Button>}{originalView.source === 'local_account' && <p role="alert">这个账号当前不支持跨设备目标，请先在模型设置选择已授权 API 模型。</p>}<CheckboxField isSelected={future} onChange={setFuture} isDisabled={busy}>以后同一配对授权、模型与范围内的目标自动处理</CheckboxField>{future && <p>未来策略：工具 {originalView.policyToolBudget} 次 · 模型 {originalView.policyModelBudget} 次 · 至 {time(originalView.policyExpiresAt)}。模型、配对代次或范围改变后重新审阅。</p>}</>}
          {review.kind === 'action' && <><h3>{deviceOperationLabels[originalView.operation]}</h3>{originalView.peer && <><p>{name(originalView.peer)} · {originalView.peer.deviceId}</p><p className="pw-source-copy">{fingerprint(originalView.peer.certificateFingerprint)}</p></>}{originalView.taskId && <><p>{originalView.goal || originalView.pendingGoal?.goal}</p><p>原任务 {originalView.taskId}{originalView.runId ? ` · 执行 ${originalView.runId} · 代次 ${originalView.executorEpoch}` : ' · 从未启动的原目标'}</p>{originalView.origin && <p>原发起设备 {originalView.origin.originDeviceId} · 请求设备 {originalView.origin.callerDeviceId}</p>}{originalView.resourceBinding && <ResourceScope resource={originalView.resourceBinding} />}</>}{originalView.lease && <p>{originalView.lease.serviceBase} · {originalView.lease.inviterDeviceId}</p>}<p>{originalView.operation === 'peer.revoke' ? '本机撤销会使旧授权失效，对端是否收到撤销另看真实回执。已经发生的动作无法撤回。' : originalView.operation.startsWith('task.') ? '只控制这项原任务，不重新提交目标。暂停、继续与停止仍以执行端回执为准。' : '变更本机接收或连接不会把正在执行的任务标记为停止。'}</p></>}
          {review.kind === 'connection' && <><p>更新原设备 {originalView.deviceId} 的连接地址。</p><p className="pw-source-copy">已配对证书完整指纹：{fingerprint(originalView.certificateFingerprint)}</p><ResourceScope resource={Object.fromEntries(Object.entries(originalView).filter(([key]) => ['host', 'port', 'controlPort', 'bulkPort', 'serviceBase', 'path', 'connectionKind', 'kind', 'expiresAt'].includes(key)))} /></>}
          {review.kind === 'relay' && <><p>{originalView.serviceBase} · 至 {time(originalView.expiresAt)}</p><p>只在内存中使用这份临时租约；不产生设备权限，双端身份仍要核对。</p><p>原邀请设备 {originalView.inviterDeviceId}</p></>}
          {review.kind === 'abandon' && <><p>只放弃本机这次待配对记录，对端可能已经保存配对。这里不会代替对端撤销。</p><p className="pw-source-copy">原设备指纹：{fingerprint(originalView.inviterFingerprint)}</p></>}
          {review.kind === 'recovery' && <><p>{originalView.kind === 'relay' ? originalView.serviceBase : `${originalView.host} · ${originalView.port}`}</p><p>只读入口截至 {time(originalView.expiresAt)}，关闭此窗口将关闭入口，不延长旧授权。</p><p className="pw-source-copy">本机指纹 {fingerprint(originalView.localFingerprint)}<br />原设备指纹 {fingerprint(originalView.peerFingerprint)}</p></>}
          {review.kind === 'result' && <><p className="pw-source-copy">{originalView.summary || '执行已结束，请核对原成果。'}</p><Accordion><AccordionItem id="evidence" title="原成果依据"><pre className="pw-source-copy">{JSON.stringify(originalView.evidence || {}, null, 2)}</pre></AccordionItem></Accordion><p>只保存这次执行的成果核对，不重新执行。</p></>}
          {review.kind === 'approval' && <><p>{originalView.message || originalView.tool}</p><ResourceScope resource={originalView.resourceBinding || {}} /><Accordion><AccordionItem id="arguments" title="这次操作的准确参数"><pre className="pw-source-copy">{JSON.stringify(originalView.arguments || {}, null, 2)}</pre></AccordionItem></Accordion><p>只决定这个原运行的准确参数，不扩大设备授权。</p></>}
          {review.kind === 'resume' && <><p>原任务 {originalView.taskId} · 原执行 {originalView.runId}</p><p>请求设备 {originalView.callerDeviceId} · 请求于 {time(originalView.requestedAt)}</p><p>本次只决定已暂停原执行的恢复请求，原授权、累计预算、期限与来源仍由原生核对。</p>{originalView.canApprove !== true && <p role="alert">这次请求已失效或原执行暂不可继续；可以拒绝后由对方重新读取。</p>}</>}
          {review.kind === 'history' && <><p>执行设备 {originalView.targetDeviceId}</p><p>原操作 {originalView.operationId}</p><ResourceScope resource={originalView.resourceBinding} /><p role="status">{deviceHistoryLabel(historyReceipt)}</p>{historyReceipt?.operationReceipt?.summary && <p className="pw-source-copy">{historyReceipt.operationReceipt.summary}</p>}<p>查询只读取原操作；未知效果保留原占用次数。请求停止后仍需核对回执。</p>{historyReceipt && <Accordion><AccordionItem id="original-receipt" title="原操作回执详情"><pre className="pw-source-copy">{JSON.stringify(historyReceipt, null, 2)}</pre></AccordionItem></Accordion>}<Button disabled={busy} onClick={() => queryHistory()}>查询原操作回执</Button>{!historyStopSubmitted ? <><CheckboxField isSelected={ack} onChange={setAck} isDisabled={busy}>我已核对上方原设备、原操作与范围，要求停止这次操作</CheckboxField><Button variant="secondary" disabled={busy || !ack} onClick={() => queryHistory(true)}>请求停止这次原操作</Button></> : <p>本窗口已提交过停止请求。继续查询原回执核对结果。</p>}</>}
          {!['recovery', 'history'].includes(review.kind) && <CheckboxField isSelected={ack} onChange={setAck} isDisabled={busy}>{review.kind === 'pair' ? '我同意准确权限，并已比较两台设备显示的两个完整指纹，均一致' : '我已核对原对象、范围与影响，确认这一次操作'}</CheckboxField>}
          <div className="pw-form-actions"><Button variant="secondary" onClick={closeReview}>{review.kind === 'recovery' ? '关闭只读入口' : '返回核对'}</Button>{!['recovery', 'history'].includes(review.kind) && <Button disabled={busy || !ack || review.kind === 'resume' && originalView.canApprove !== true || review.kind === 'goal' && (originalView.source === 'local_account' || !originalView.model)} onClick={() => confirmReview(review.kind === 'result' ? { completed: true } : ['approval', 'resume'].includes(review.kind) ? { approved: true } : {})}>{review.kind === 'result' ? '确认成果并结束' : review.kind === 'approval' ? '允许这次原操作' : review.kind === 'resume' ? '同意继续原执行' : review.kind === 'pair' ? '同意权限并确认双端指纹' : '确认本次操作'}</Button>}{review.kind === 'result' && <Button variant="secondary" disabled={busy || !ack} onClick={() => confirmReview({ completed: false })}>结束但不确认完成</Button>}{['approval', 'resume'].includes(review.kind) && <Button variant="secondary" disabled={busy || !ack} onClick={() => confirmReview({ approved: false })}>{review.kind === 'resume' ? '拒绝这次恢复请求' : '拒绝这次操作'}</Button>}</div>
        </>}
        {review.kind === 'pair' && <Button variant="ghost" disabled={busy} onClick={() => { const old = review; handle.current = ''; setReview(null); void invoke('devices.reject', { viewHandle: old.viewHandle }, () => setNotice('本机已拒绝这次配对，请核对原状态。')); }}>拒绝这次原配对</Button>}
      </div>}
    </div></SettingsDialog>
  </>;
}
