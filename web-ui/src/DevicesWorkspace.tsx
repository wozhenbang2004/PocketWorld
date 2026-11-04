import { useEffect, useRef, useState } from 'react';
import { Accordion, AccordionItem, Button, CheckboxField, SelectField, SettingsDialog, TextAreaField, TextField } from './forms-library';
import { DeviceRequestScope, deviceConnectionLabel, deviceGrantInput, deviceHistoryLabel, deviceHistoryReceipt, deviceTaskDetails, deviceMethodLabels, deviceOperationLabels, deviceReceipt, deviceReview, deviceRows as rows, deviceSnapshot, deviceStrings as strings, deviceTaskRows, type DeviceData as Data } from './devices-workspace';

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
    if (review) { closeReview(); return true; }
    if (invitation) { setInvitation(''); return true; }
    if (form) { clearSensitive(true); refresh(); return true; }
    if (peerId) { setPeerId(''); return true; }
    return false;
  }); return () => registerTransientBack(null); }, [review, invitation, form, peerId, registerTransientBack]);
  const peer = rows(snapshot?.peers).find(row => row.deviceId === peerId), local = snapshot?.local || {}, actions = snapshot?.actions || {};
  const tasks = deviceTaskRows(state.tasks, snapshot, peerId);
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

  return <>
    <section className="pw-settings-section"><header className="pw-section-heading"><div><h2>{peer ? name(peer) : '我的设备'}</h2><p>本机接收、双端身份与每项授权分别核对。连接状态不代表任务已经停止或完成。</p></div></header>
      <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={refresh}>刷新原设备状态</Button>{peerId ? <Button variant="ghost" disabled={busy} onClick={() => { setPeerId(''); setForm(''); setRaw(''); }}>返回设备列表</Button> : <Button disabled={busy} onClick={() => { setForm('add'); setRaw(''); setAddress(''); }}>配对设备</Button>}</div>
      {error && <p role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}{busy && <p role="status" className="pw-muted">正在读取原设备回执…</p>}
      {!snapshot && !busy && <p className="pw-secondary-empty">设备状态尚未完整读取，请刷新核对。</p>}
      {snapshot && !peerId && <>
        <article className="pw-source-record"><h3>这台手机</h3><p>{local.receiverEnabled === true ? local.listenerReady === true ? '本机接收通道已启动' : '接收已启用，通道仍待就绪' : local.receiverEnabled === false ? '设备接收未启用' : '接收状态尚未确认'}</p>{local.receiverReason && <p>{local.receiverReason}</p>}<Button variant="secondary" disabled={busy || !available(actions[local.receiverEnabled ? 'disable' : 'enable'])} onClick={() => actionReview(local.receiverEnabled ? 'receiver.disable' : 'receiver.enable')}>{local.receiverEnabled ? '核对并停用接收' : '核对并启用接收'}</Button>{!available(actions[local.receiverEnabled ? 'disable' : 'enable']) && <p className="pw-muted">{actions[local.receiverEnabled ? 'disable' : 'enable']?.reason}</p>}</article>
        {rows(snapshot.pendingPairings).length > 0 && <h3>等待核对的原配对</h3>}{rows(snapshot.pendingPairings).map(candidate => <article className="pw-source-record" key={candidate.candidateId || candidate.inviteId}><h4>{candidate.label || '原设备配对'}</h4><p>{candidate.state === 'waiting_remote_confirmation' ? '等待另一台设备确认' : candidate.state === 'confirmed_for_mtls' ? '身份已确认，等待完成握手' : candidate.state === 'paired' ? '配对回执已产生，请核对当前信任' : '等待本机核对'}</p><Button variant="secondary" disabled={busy || candidate.advanceAvailable !== true && candidate.reviewAvailable !== true && !['', 'pending_owner_confirmation'].includes(candidate.state || '')} onClick={() => { void invoke(candidate.advanceAvailable ? 'devices.advancePairing' : 'devices.reviewPairing', { candidateId: candidate.candidateId || candidate.inviteId }, candidate.advanceAvailable ? () => setNotice('已查询原配对状态，未重新发起配对。') : acceptReview); }}>{candidate.advanceAvailable ? '查询原配对进度' : '审阅范围与双端身份'}</Button></article>)}
        {rows(snapshot.peers).map(row => <article className="pw-source-record" key={row.deviceId}><header><h3>{name(row)}</h3><span>{deviceConnectionLabel(row)}</span></header><p>{row.platform || '已配对设备'} · {deviceTaskRows(state.tasks, snapshot, row.deviceId).length} 项关联任务</p><Button variant="secondary" disabled={busy} onClick={() => { setPeerId(row.deviceId); setError(''); }}>查看设备与授权</Button></article>)}
        {!rows(snapshot.peers).length && <p className="pw-secondary-empty">还没有已配对设备。可使用另一台设备的短时邀请，或在本机创建邀请。</p>}
        {(rows(snapshot.pairingOffersToRecover).length > 0 || rows(snapshot.committedPairingsToRecover).length > 0) && <Accordion><AccordionItem id="recover" title="核对已有配对结果"><p>只读取双方已确认的原结果，不重做配对、不延长旧权限。</p>{rows(snapshot.pairingOffersToRecover).map(row => <div className="pw-source-record" key={row.inviteId}><p>原邀请 {row.inviteId}</p><Button variant="secondary" disabled={busy} onClick={() => openRecovery(row, false)}>读取已提交结果</Button>{row.abandonAvailable === true && <Button variant="ghost" disabled={busy} onClick={() => { void invoke('devices.reviewAbandonment', { inviteId: row.inviteId }, acceptReview); }}>核对并放弃本机待配对</Button>}</div>)}{rows(snapshot.committedPairingsToRecover).map(row => <div className="pw-source-record" key={row.inviteId}><p>为原设备 {row.peerDeviceId} 提供已提交结果</p><Button variant="secondary" disabled={busy || row.recoveryState !== 'available'} onClick={() => openRecovery(row, true)}>开启原结果只读窗口</Button></div>)}</AccordionItem></Accordion>}
      </>}
      {peerId && !peer && snapshot && <p role="alert">这台设备不在当前读取页。保留原身份，请返回后刷新核对。</p>}
      {peer && <>
        <h3>{deviceConnectionLabel(peer)}</h3><p>{peer.connection?.state === 'ONLINE' ? peer.connection?.path === 'RELAY_WSS' ? '当前通过出站 WSS 中继连接。' : peer.connection?.path === 'DIRECT_TLS' ? '当前通过直连 TLS 通道连接。' : '已连接，传输路径仍待核对。' : '未确认的操作仍需查看执行结果，离线不代表停止。'}</p>
        {peer.connection?.unconfirmedTransportWrite === true && <p>有操作结果待确认，请查看原执行结果。</p>}
        <div className="pw-form-actions">{available(peer.actions?.connect) && <Button disabled={busy} onClick={() => actionReview('peer.connect', { deviceId: peer.deviceId })}>核对并连接</Button>}{available(peer.actions?.disconnect) && <Button variant="secondary" disabled={busy} onClick={() => actionReview('peer.disconnect', { deviceId: peer.deviceId })}>停止主动连接</Button>}{!peer.revoked && <><Button variant="secondary" disabled={busy} onClick={() => { setForm('connection'); setRaw(''); setAddress(''); }}>更新连接地址</Button><Button variant="secondary" disabled={busy} onClick={() => { void invoke('devices.reviewPeerGrant', { deviceId: peer.deviceId }, acceptReview); }}>调整本机授予的权限</Button></>}</div>
        {peer.revoked && <p>{peer.revocation?.peerAcknowledged === true ? '本机信任已撤销，对端已回执。' : '本机信任已撤销，对端仍未确认。'}</p>}
        <Accordion><AccordionItem id="capabilities" title="能力与两端权限">{rows(peer.capabilitySnapshot?.capabilities).map(cap => <p key={cap.id}>{cap.id} · {peer.capabilitySnapshot?.validUntil <= Date.now() ? '读取已过期，请刷新' : cap.ready === true && cap.authorized === true ? '核对时可用' : cap.reason || '条件尚未就绪'}</p>)}<h4>允许这台设备在本机使用</h4>{rows(peer.grantScopes).map((scope, index) => <GrantScope key={index} scope={scope} />)}<h4>本机可在对端使用</h4>{rows(peer.receivedGrantScopes).map((scope, index) => <GrantScope key={index} scope={scope} />)}{peer.hasMoreGrantScopes || peer.hasMoreReceivedGrantScopes ? <p>当前是一页已读取授权，还有未展开的记录。</p> : null}</AccordionItem><AccordionItem id="identity" title="身份与安全"><p>设备 {peer.deviceId} · 授权版本 {peer.authorizationEpoch}</p><p className="pw-source-copy">完整证书指纹：{fingerprint(peer.certificateFingerprint || peer.fingerprint)}</p>{available(peer.actions?.revoke) && <Button variant="secondary" disabled={busy} onClick={() => actionReview('peer.revoke', { deviceId: peer.deviceId })}>核对并撤销本机信任</Button>}</AccordionItem></Accordion>
      </>}
      {tasks.length > 0 && <h3>原设备任务</h3>}
      {tasks.slice(0, 40).map(task => {
        const controls = snapshot?.taskActions?.[task.id] || {}, request = controls.resumeRequest;
        const details = taskDetails[task.id], links = rows(details?.history?.links);
        return <article className="pw-source-record" key={task.id}>
          <header><h4>{task.goal || '原设备任务'}</h4><span>{task.status}</span></header>{task.summary && <p>{task.summary}</p>}
          <div className="pw-form-actions">
            <Button variant="secondary" disabled={busy} onClick={() => { void invoke('devices.openTask', { taskId: task.id }); }}>查看原任务与结果</Button>
            <Button variant="secondary" disabled={busy} onClick={() => { void invoke('devices.readTask', { taskId: task.id }, value => { const actual = deviceTaskDetails(value, task.id); setTaskDetails(old => ({ ...old, [task.id]: actual })); }); }}>读取原设备操作记录</Button>
            {available(controls.goalReview) && <Button disabled={busy} onClick={() => { void invoke('devices.reviewGoal', { taskId: task.id }, acceptReview); }}>审阅本机模型与原目标</Button>}
            {available(controls.resultReview) && <Button disabled={busy} onClick={() => { void invoke('devices.reviewResult', { taskId: task.id }, acceptReview); }}>核对成果并结束</Button>}
            {(['pause', 'resume', 'stop', 'pendingCancel'] as const).filter(control => !(control === 'resume' && request?.ownerActionRequired === true) && !(control === 'stop' && available(controls.pendingCancel))).map(control => available(controls[control]) && <Button key={control} variant="secondary" disabled={control === 'stop' || control === 'pendingCancel' ? controlBusy : busy} onClick={() => actionReview(`task.${control === 'pendingCancel' ? 'cancelPending' : control}`, { taskId: task.id })}>{deviceOperationLabels[`task.${control === 'pendingCancel' ? 'cancelPending' : control}`]}</Button>)}
          </div>
          {request?.ownerActionRequired === true && <div className="pw-source-record"><h4>对方请求继续原执行</h4><p>请求设备 {request.callerDeviceId}</p>{request.stale === true && <p>任务版本已经变化；需要对方重新读取后发起恢复请求。仍可拒绝这次旧请求。</p>}<Button variant="secondary" disabled={busy} onClick={() => { void invoke('devices.reviewResumeRequest', { taskId: task.id, resumeRequestId: request.resumeRequestId, reviewRevision: request.reviewRevision }, acceptReview, 'control'); }}>核对这次恢复请求</Button></div>}
          {details && <Accordion><AccordionItem id={`history-${task.id}`} title={`原设备操作 · ${links.length} 项`}>
            {!links.length && <p>本次原生读取没有返回已关联的设备操作。</p>}
            {links.map(link => <div className="pw-source-record" key={link.operationId}><p>设备 {link.targetDeviceId} · 原操作 {link.operationId}</p><p>{link.terminalReceipt?.terminal === true && link.terminalReceipt?.stopped === true && link.terminalReceipt?.hasUnresolved === false ? '执行端已有结束回执，业务成果仍待核对。' : String(link.holdState || '').includes('unknown') ? '执行效果仍未确认，原占用次数保留。' : link.childBinding ? '执行端已接受，尚未核对最终结果。' : '已保留这次原操作的关联记录。'}</p><Button variant="secondary" disabled={busy} onClick={() => { void invoke('devices.openHistory', { taskId: task.id, operationId: link.operationId }, acceptReview); }}>查看这次原操作回执</Button></div>)}
            {details.history.hasMore === true && <p>当前显示最近 24 项原设备操作。</p>}
          </AccordionItem></Accordion>}
          {rows(task.raw?.approvals).filter(row => row.state === 'pending').slice(0, 16).map(approval => <div key={approval.id || approval.approvalId}><p>{approval.message || approval.tool}</p><Button variant="secondary" disabled={busy || typeof (approval.id || approval.approvalId) !== 'string' || !Number.isSafeInteger(approval.expiresAt) || approval.expiresAt <= Date.now()} onClick={() => { void invoke('devices.reviewApproval', { taskId: task.id, approvalId: approval.id || approval.approvalId }, acceptReview); }}>核对这次原操作</Button></div>)}
        </article>;
      })}
      {tasks.length > 40 && <p>当前展示前 40 项原设备任务。</p>}
      {snapshot && <p className="pw-muted">原状态读取于 {time(snapshot.capturedAt)}{snapshot.hasMorePeers || snapshot.hasMorePendingPairings || snapshot.hasMoreTaskActions ? ' · 当前展示有界读取页，更多记录需从原对象继续核对。' : ''}</p>}
    </section>
    <SettingsDialog isOpen={!!form} onClose={() => { clearSensitive(true); refresh(); }} title={form === 'relay' ? '临时中继租约' : form === 'connection' ? '更新原设备连接地址' : form.startsWith('recovery') ? '核对原配对结果' : '配对你的另一台设备'}>
      {['add', 'connection', 'relay'].includes(form) && <><TextAreaField label={form === 'relay' ? '已有临时租约' : '原设备短时邀请'} value={raw} onChange={setRaw} isDisabled={busy} hint="仅在当前窗口核对，离开或进入后台后清除。" /><Button disabled={busy || !raw.trim() || form === 'add' && !available(actions.join)} onClick={() => { void invoke(form === 'relay' ? 'devices.reviewRelay' : form === 'connection' ? 'devices.reviewConnection' : 'devices.inspectInvitation', { raw, ...(form === 'connection' ? { deviceId: peerId } : {}) }, acceptReview); }}>读取原生审阅</Button></>}
      {form === 'add' && <><h3>让另一台设备来连接</h3><SelectField label="本机地址或已配置中继" value={address} onChange={setAddress} options={strings(actions.localAddresses).map(value => ({ id: value, label: value }))} isDisabled={busy} /><Button variant="secondary" disabled={busy || !address || !available(actions.invite)} onClick={() => { void invoke('devices.createInvitation', { address }, value => { if (value.displayOnce !== true || typeof value.invitation !== 'string' || !value.invitation) throw new Error('未取得原短时邀请。'); setInvitation(value.invitation); setRaw(''); setForm(''); }); }}>创建本机短时邀请</Button>{!available(actions.invite) && <p>{actions.invite?.reason}</p>}<Button variant="ghost" disabled={busy} onClick={() => { setForm('relay'); setRaw(''); }}>接收异网连接的临时中继租约</Button></>}
      {form === 'connection' && <><SelectField label="已配置的临时中继" value={address} onChange={setAddress} options={Array.from(new Set(rows(actions.relayLeases).map(row => row.serviceBase))).map(value => ({ id: value, label: value }))} isDisabled={busy} /><Button variant="secondary" disabled={busy || !address} onClick={() => { void invoke('devices.reviewConnection', { deviceId: peerId, serviceBase: address }, acceptReview); }}>核对原设备中继地址</Button></>}
      {form === 'relay' && <><p>租约仅提供临时网络通道，不授予设备权限。仍需比较双端指纹并明确选择范围。</p>{rows(actions.relayLeases).map(lease => <div className="pw-source-record" key={lease.serviceBase + lease.inviterDeviceId}><p>{lease.serviceBase} · 至 {time(lease.expiresAt)}</p><Button variant="secondary" disabled={busy} onClick={() => actionReview('relay.remove', { serviceBase: lease.serviceBase, inviterDeviceId: lease.inviterDeviceId })}>核对并移除这份租约</Button></div>)}</>}
      {form === 'recoveryProvide' && <><p>仅开放原结果的短时只读窗口，关闭页面会关闭入口。</p><SelectField label="本机实际地址或原中继" value={address} onChange={setAddress} options={recoveryAddresses.map(value => ({ id: value, label: value }))} isDisabled={busy} /><Button disabled={busy || !address} onClick={() => { void invoke('devices.openRecovery', { inviteId, address }, acceptReview); }}>开启原结果只读窗口</Button></>}
      {form === 'recoveryRead' && <><TextField label="原设备显示的数字地址或已配置中继服务" value={host} onChange={setHost} isDisabled={busy} />{!/^wss:\/\/|^https:\/\//.test(host) && <TextField label="原恢复端口" value={port} onChange={setPort} isDisabled={busy} />}<Button disabled={busy || !host || !/^wss:\/\/|^https:\/\//.test(host) && !/^\d+$/.test(port)} onClick={() => { void invoke('devices.recoverPairing', { inviteId, host, port: /^wss:\/\/|^https:\/\//.test(host) ? 0 : Number(port) }, () => { setForm(''); setNotice('已查询原已提交配对结果，未重新授权。'); }); }}>只读取原配对结果</Button></>}
    </SettingsDialog>
    <SettingsDialog isOpen={!!invitation} onClose={() => setInvitation('')} title="本机一次性短时邀请"><p>仅交给你信任的另一台设备。邀请到期后需要重新创建；接下来仍需双端指纹与准确权限审阅。</p><TextAreaField label="原邀请，选中后可复制" value={invitation} isReadOnly /><Button variant="secondary" onClick={() => setInvitation('')}>关闭并清除显示</Button></SettingsDialog>
    <SettingsDialog isOpen={!!review} onClose={closeReview} title={review?.kind === 'pair' ? '范围与双端身份' : review?.kind === 'grant' ? '原设备权限修改' : review?.kind === 'goal' ? '用本机模型处理原目标' : review?.kind === 'result' ? '核对这次原成果' : review?.kind === 'approval' ? '核对这次设备操作' : review?.kind === 'recovery' ? '原配对结果只读窗口' : review?.kind === 'resume' ? '核对这次恢复请求' : review?.kind === 'history' ? '原设备操作回执' : '核对原设备操作'}>
      {review && <>
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
      </>}
    </SettingsDialog>
  </>;
}
