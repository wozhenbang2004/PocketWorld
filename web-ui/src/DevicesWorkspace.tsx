import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import './collaboration-depth.css';
import './device-control-reading.css';
import { Accordion, AccordionItem, Button, CheckboxField, RadioOptions, SearchField, SelectField, SettingsDialog, TextAreaField, TextField } from './forms-library';
import { DeviceRequestScope, archivedPhoneStopLine, deviceChannelWasConnected, deviceConnectionLabel, deviceGrantInput, deviceHistoryLabel, deviceHistoryReceipt, deviceOriginalTaskActions, isReceivedPhoneTask, receivedPhoneGoalReturn, matchesReceivedPhoneGoal, type ReceivedPhoneGoalReturn, type SentPhoneGoalFocus, PHONE_ACTION_WINDOW_MS, deviceRecordedScopes, devicePhoneGoalArchive, localReceivedPhonePage, localReviewedInbox, samePhoneGoalArchive, receivedPhoneArchiveReturn, waitingPhoneGoalPage, type PhoneArchiveSide, deviceTaskHistoryPage, deviceTaskHistoryLinks, deviceTaskDetails, deviceMethodLabels, deviceOperationLabels, devicePhoneGoalResult, deviceReceipt, deviceReview, deviceRows as rows, deviceSnapshot, deviceStrings as strings, deviceTaskRows, deviceWorkspaceTaskMode, type DeviceData as Data } from './devices-workspace';
import { IconAdd, IconArrowLeft, IconChevronRight, IconClock, IconDevices, IconLayers, IconMonitor, IconPhone, IconRefresh, IconShieldCheck } from './icons';
import { DesktopViewer } from './DesktopViewer';
import { PairingInvitationDisplay, shownPairingInvitation, type ShownPairingInvitation } from './PairingInvitationDisplay';
import { SavedPhoneDispatches } from './SavedPhoneDispatches';
import { SavedPeerScopeReceipt } from './SavedPeerScopeReceipt';
import { LocalPeerNameEditor } from './LocalPeerNameEditor';
import { WindowsCommandReceipt } from './WindowsCommandReceipt';
import { WindowsWorkspaceReadReceipt } from './WindowsWorkspaceReadReceipt';
import { WindowsFileDeliveryReceipt } from './WindowsFileDeliveryReceipt';
import { OriginalLocalExportedFile } from './OriginalLocalExportedFile';
import { DeviceTaskPreparation, type PreparedPhone } from './DeviceTaskPreparation';
import { PhoneControlPreparation } from './PhoneControlPreparation';
import { WorkspaceSectionHeading } from './WorkspaceSectionHeading';
import { desktopScopes } from './desktop-viewer';
import { windowsObservationCompatibility } from './windows-observation-compatibility';
import { phoneSurfaceStatus } from './phone-surface-status';
import { useSurfaceEnter } from './library/surface-motion';
import { MotionFeedback, WorkingStatus, useCollectionMotion } from './library/journey-motion';
import './devices-reading.css';
import './device-workspace-overview.css';

type Run = (action: string, args?: Data) => Promise<any>;
const name = (peer: Data) => peer.label || peer.name || (retired(peer) ? '已撤销的设备' : '已配对设备');
const time = (value: any) => typeof value === 'number' && value > 0 ? new Date(value).toLocaleString() : '尚未读取';
const available = (value: any) => value?.available === true;
const unreadSource = (task: Data) => task.summaryOnly === true || task.raw?.contextAvailable === false || task.raw?.sourceCurrentReadRequired === true || task.raw?.taskProjection === 'source_unavailable';
const retired = (peer: Data) => peer.revoked === true || peer.connection?.state === 'REVOKED';
const peerPlatform = (peer: Data) => peer.windowsDesktop ? 'Windows' : peer.platform || '';
const platformIcon = (peer: Data) => /android|ios|phone/i.test(peerPlatform(peer)) ? IconPhone : /windows|macos|linux|desktop/i.test(peerPlatform(peer)) ? IconMonitor : IconDevices;
const fingerprint = (value: any) => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value) ? value.match(/.{1,8}/g)?.join(' ') : '未取得完整指纹';
const grantDraft = () => ({ optionId: '', toolBudget: '', modelBudget: '', durationMinutes: '15', historyDays: '7', historyReadMethods: [] as string[], additionalOriginOptionIds: [] as string[], forwardingOptionIds: [] as string[], forwardingToolBudget: '', forwardingModelBudget: '', wholeDisplayCapture: false });

function ResourceScope({ resource }: { resource: Data }) {
  const labels: Record<string, string> = { screenMode: '屏幕范围', packageNames: '允许应用', allowWholeDisplayCapture: '整屏截图', allowWholeDisplayInput: '整屏输入', projectId: '准确项目', checkoutId: '准确工作副本', runtimeId: '运行环境', displayId: '指定屏幕', resourceId: '资源身份', kind: '资源类型' };
  return <dl className="pw-facts">{Object.entries(resource).map(([key, value]) => <div key={key}><dt>{labels[key] || key}</dt><dd>{typeof value === 'boolean' ? value ? '允许' : '不允许' : Array.isArray(value) ? value.join('、') : typeof value === 'object' ? JSON.stringify(value) : String(value)}</dd></div>)}</dl>;
}
const pairConclusion = (state: unknown): string => {
  switch (state) {
    case 'rejected': return '对方已拒绝这次原配对，不再继续握手。';
    case 'remote_trust_ended': return '对方保留了上次提交记录，但已不再信任这份原配对。请双方各自在设备上核对。';
    case 'local_pair_followup_unverified': return '原配对已在本机保存，授权回执或连接地址等后续步骤尚未核完。请查看已保存的伙伴，不必重新配对。';
    case 'local_pair_connection_unavailable': return '原配对与对方授权回执已经保存，但本机没有成功开启连接。请到伙伴详情核对连接。';
    case 'local_pair_saved': return '原配对已在本机保存，正在处理连接。不必重复握手。';
    default: return '';
  }
};
const ownerWindowEnded = (candidate: Data, now: number) => !!candidate.localOwnerWindow &&
  (candidate.localOwnerWindow === 'review_closed' || candidate.localOwnerWindow === 'completion_closed' || candidate.expiresAt <= now);
const originalPairProgress = (candidate: Data, now = Date.now()): string => {
  const terminal = pairConclusion(candidate.state); if (terminal) return terminal;
  if (ownerWindowEnded(candidate, now)) {
    const recorded = (candidate.localOwnerWindow === 'completion_closed' || candidate.localOwnerWindow === 'completion_open') ? '本机已保存原身份与范围确认' : '本机已收到这份申请，尚未确认';
    return recorded + (candidate.expiresAt <= now ? '；原邀请已到期。' : '；原邀请窗口已关闭。') + '原申请不能在这里继续，不代表对方拒绝。';
  }
  if (candidate.joiningOriginal === true) {
    switch (candidate.state) {
      case 'waiting_remote_confirmation': return '上次回执显示邀请方收到了这份申请，当时还没有机主确认。对方确认后可在这里继续原配对。';
      case 'confirmed_for_mtls': return '双方已确认原指纹与范围，但尚未核对到本机保存。继续原配对会完成握手、保存配对并尝试连接，不启动任务。';
      case 'remote_commit_reported': return '上次回执显示邀请方已经提交，当时仍信任原身份。本机尚未核对到保存，继续时只完成这次原配对并尝试连接。';
      case 'local_review_only': return '本机已核对指纹与范围，尚无邀请方收到或确认的回执。继续只核对这份原配对；双方都确认后才会保存并尝试连接。';
    }
  }
  if (candidate.advanceAvailable === true) return '对方确认后可继续原配对，保存并尝试连接，不启动任务。';
  if (candidate.reviewAvailable === true) return '在这台设备核对权限与两个完整指纹。';
  return candidate.state === 'confirmed_for_mtls' ? '本机已确认，接下来由加入邀请的一方继续配对。' : '请重读本机记录核对进展。';
};
function GrantScope({ scope }: { scope: Data }) {
  const expired = Number.isSafeInteger(scope.expiresAt) && scope.expiresAt <= Date.now();
  // Safe display only. The native grant and original peer certificate decide whether disclosure is available.
  const identityOnly = scope.optionId === 'pair_identity_only' || (String(scope.grantId || '').startsWith('dpv1_') &&
    scope.capabilities?.length === 1 && scope.capabilities[0] === 'device.describe' && scope.toolNames?.length === 0);
  if (identityOnly) {
    const unavailable = expired || ['expired', 'revoked', 'stale_epoch', 'identity_changed'].includes(scope.state);
    return <div className="pw-source-copy"><p>仅公开设备身份和本次授权回执。</p>
      <p>不公开忙闲、屏幕、模型、项目或其他授权。不能读任务、调用工具、派工、安排日程或转授。</p>
      {scope.expiresAt && <p>{unavailable ? '这份记录已失效，不能据此继续查看。' : `授权至 ${time(scope.expiresAt)}；到期或撤销后不再返回。`}</p>}
    </div>;
  }
  const status = ({ active: '上次核对时执行授权有效', expired: '执行授权已到期', revoked: '授权已撤销', stale_epoch: '旧授权代次，不能执行', identity_changed: '本机身份不可用，旧授权不能执行' } as Record<string, string>)[scope.state === 'active' && expired ? 'expired' : scope.state];
  return <div className="pw-source-copy">{scope.receivedScope === true ? <p>{expired ? '对方授权回执中的执行期限已过，结果读取期限另计。' : '上次收到的对端授权回执，执行时仍核对当前状态。'}</p> : status && <p>{status}</p>}{scope.budgetStatus?.budgetState === 'known' && <p>可分配给新任务：工具 {scope.budgetStatus.toolsAvailableForNewAllocations} 次 · 模型 {scope.budgetStatus.modelsAvailableForNewAllocations} 次</p>}{typeof scope.retainedToolsReservedOrUsed === 'number' && <p>已有占用：工具 {scope.retainedToolsReservedOrUsed} 次 · 模型 {scope.retainedModelsReservedOrUsed} 次，不因本次修改清零。</p>}{scope.label && <h4>{scope.label}</h4>}{scope.effectSummary && <p>{scope.effectSummary}</p>}<p>{strings(scope.capabilities).map(method => deviceMethodLabels[method] || method).join(' · ') || '可用方法尚未提供'}</p>{strings(scope.toolNames).length > 0 && <p>准确工具：{strings(scope.toolNames).join('、')}</p>}{rows(scope.resources).map((resource, index) => <ResourceScope key={index} resource={resource} />)}{scope.isolation === false && <p>执行代码可能访问该系统用户可访问的资源，项目目录不是系统沙箱。</p>}{typeof scope.toolBudget === 'number' && <p>授权期内累计：工具 {scope.toolBudget} 次 · 模型 {scope.modelBudget} 次 · 至 {time(scope.expiresAt)}</p>}{scope.historyRead ? <p>结果读取至 {time(scope.historyRead.expiresAt)} · {strings(scope.historyRead.methods).map(method => deviceMethodLabels[method] || method).join('、')}</p> : typeof scope.expiresAt === 'number' && <p>执行到期后未另行授权读取结果。</p>}{scope.allowedOrigins && <p>原发起设备：{strings(scope.allowedOrigins).join('、')}</p>}{scope.forwardingScope ? <><p>下游允许方法：{strings(scope.forwardingScope.capabilities).map(method => deviceMethodLabels[method] || method).join('、')}</p><p>下游准确工具：{strings(scope.forwardingScope.toolNames).join('、')}</p><p>下游累计：工具 {scope.forwardingScope.toolBudget} 次 · 模型 {scope.forwardingScope.modelBudget} 次</p>{rows(scope.forwardingScope.targetResources).map((target, index) => <div key={index}><p>下游设备 {target.targetDeviceId}</p><ResourceScope resource={target.resourceBinding || {}} /></div>)}</> : scope.allowDelegation === true ? <p>仅允许列明来源转交，不继续委派。</p> : <p>未授权转交来源或继续委派。</p>}</div>;
}

function GrantSelection({ review, busy, confirm }: { review: Data; busy: boolean; confirm: (args: Data) => void }) {
  const [draft, setDraft] = useState<Data>(grantDraft), [error, setError] = useState('');
  const view = review.view, options = rows(view.grantOptions), option = options.find(row => row.optionId === draft.optionId);
  const identityOnly = option?.optionId === 'pair_identity_only';
  const [filter, setFilter] = useState('');
  const matches = options.filter(row => String(row.label || row.optionId).toLocaleLowerCase().includes(filter.trim().toLocaleLowerCase()));
  const update = (key: string, value: any) => setDraft(old => ({ ...old, [key]: value }));
  const toggle = (key: string, id: string, selected: boolean) => update(key, selected ? [...strings(draft[key]), id] : strings(draft[key]).filter(value => value !== id));
  return <>
    <p>{review.kind === 'grant' ? '选择将替换本机授予对端的范围。已有用量不会清零。' : '明确选择另一台设备能在这里做什么。双方分别决定各自的授权。'}</p>
    {view.currentGrant && <Accordion><AccordionItem id="current" title="当前权限"><GrantScope scope={view.currentGrant} /></AccordionItem></Accordion>}
    {options.length > 8 && <SearchField aria-label="筛选本机可授予的范围" placeholder="搜索应用、项目或能力" value={filter} isDisabled={busy} onChange={value => { setFilter(value); setDraft(grantDraft()); setError(''); }} />}
    <SelectField label="允许对方在这台手机使用什么" value={draft.optionId} placeholder="明确选择一个范围" options={matches.map(row => ({ id: row.optionId, label: row.label || row.optionId }))} onChange={id => { const chosen = matches.find(row => row.optionId === id); if (chosen) { setDraft({ ...grantDraft(), optionId: id, toolBudget: String(Math.max(chosen.toolBudgetMin || 0, Math.min(12, chosen.toolBudgetMax))), modelBudget: String(Math.max(chosen.modelBudgetMin || 0, Math.min(3, chosen.modelBudgetMax))) }); setError(''); } }} isDisabled={busy || !matches.length} />
    {view.workScopesUnavailable === true && <p>暂时无法核对其他工作权限，不影响只交换身份。</p>}
    {!!options.length && !matches.length && <p>没有匹配的本机范围，请更换或清除搜索词。</p>}
    {!options.length && <p>{view.grantOptionsUnavailableReason || '当前没有可授予的原生范围，请先核对本机设置。'}</p>}
    {option && <>
      <GrantScope scope={option} />
      {!identityOnly && <><TextField label={`累计工具次数（${option.toolBudgetMin || 0}–${option.toolBudgetMax}）`} value={draft.toolBudget} onChange={value => update('toolBudget', value)} isDisabled={busy} />
      <TextField label={`累计模型次数（${option.modelBudgetMin || 0}–${option.modelBudgetMax}）`} value={draft.modelBudget} onChange={value => update('modelBudget', value)} isDisabled={busy} /></>}
      <SelectField label={identityOnly ? '身份与回执可见期限' : '执行授权期限'} value={draft.durationMinutes} onChange={value => update('durationMinutes', value)} options={[{ id: '15', label: '15 分钟' }, { id: '60', label: '1 小时' }, { id: '1440', label: '1 天' }, { id: '43200', label: '30 天' }]} isDisabled={busy} hint={`原范围上限 ${time(option.expiresAtMax)}，最终期限以接下来的准确审阅为准。`} />
      {option.canGrantWholeDisplayCapture === true && <CheckboxField isSelected={draft.wholeDisplayCapture} onChange={value => update('wholeDisplayCapture', value)} isDisabled={busy}>允许整屏截图（可能包含其他应用或通知，与整屏输入分开）</CheckboxField>}
      {!identityOnly && <Accordion>
        {strings(option.historyReadMethods).length > 0 && <AccordionItem id="history" title="执行到期后的结果读取">{strings(option.historyReadMethods).map(method => <CheckboxField key={method} isSelected={strings(draft.historyReadMethods).includes(method)} onChange={value => toggle('historyReadMethods', method, value)} isDisabled={busy}>{deviceMethodLabels[method] || method}</CheckboxField>)}<SelectField label="结果读取期限" value={draft.historyDays} onChange={value => update('historyDays', value)} options={[{ id: '1', label: '1 天' }, { id: '7', label: '7 天' }, { id: '30', label: '30 天' }]} isDisabled={busy || !draft.historyReadMethods.length} hint="只有勾选的方法获得读取权限，不延长执行期限。" /></AccordionItem>}
        <AccordionItem id="origins" title={`转交来源 · 已选 ${draft.additionalOriginOptionIds.length} 台`}><p>直接来自本次配对设备的任务已包含。其他原发起设备需要逐一明确选择。</p>{rows(view.originOptions).map(origin => <CheckboxField key={origin.optionId} isSelected={draft.additionalOriginOptionIds.includes(origin.optionId)} onChange={value => toggle('additionalOriginOptionIds', origin.optionId, value)} isDisabled={busy}>{origin.label || origin.optionId}</CheckboxField>)}{!rows(view.originOptions).length && <p>没有其他已认证来源。</p>}</AccordionItem>
        {option.allowDelegation === true && <AccordionItem id="forwarding" title={`继续委派 · 已选 ${draft.forwardingOptionIds.length} 个范围`}><p>这里只列本机已记住、允许此次来源的原授权。勾选不会联系设备；下一步只向你勾选的设备核对可用能力，不派任务。</p>{rows(view.forwardingOptions).map(target => <div key={target.optionId}><CheckboxField isSelected={draft.forwardingOptionIds.includes(target.optionId)} onChange={value => toggle('forwardingOptionIds', target.optionId, value)} isDisabled={busy}>{target.label || target.optionId}</CheckboxField><ResourceScope resource={target.resourceBinding || {}} /></div>)}{draft.forwardingOptionIds.length > 0 && <><TextField label="下游累计工具次数" value={draft.forwardingToolBudget} onChange={value => update('forwardingToolBudget', value)} isDisabled={busy} /><TextField label="下游累计模型次数" value={draft.forwardingModelBudget} onChange={value => update('forwardingModelBudget', value)} isDisabled={busy} /><p>发起任务时仍需分配互不重复的份额，原生会核对每个下游的真实上限。</p></>}</AccordionItem>}
      </Accordion>}
    </>}
    {error && <p role="alert">{error}</p>}<Button disabled={busy || !option} onClick={() => { try { confirm(deviceGrantInput(review, draft)); } catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); } }}>{draft.forwardingOptionIds.length ? '读取所选转交设备并核对权限' : '核对准确权限' + (review.kind === 'pair' ? '与双端身份' : '修改')}</Button>
  </>;
}

export function DevicesWorkspace({ state, data, supported, run, registerTransientBack, openModels, modelReturn, sourceFocus, onReturnToPartnerTask }: { sourceFocus?: SentPhoneGoalFocus; onReturnToPartnerTask?: () => void; modelReturn?: ReceivedPhoneGoalReturn; state: Data; data: Data; supported: (action: string) => boolean; run: Run; registerTransientBack: (handler: (() => boolean) | null) => void; openModels?: (origin?: ReceivedPhoneGoalReturn) => void }) {
  const pinnedConnection=sourceFocus?.purpose==='partner_connection';
  const [pinnedLocalRead,setPinnedLocalRead]=useState(0);
  const [snapshot, setSnapshot] = useState<Data | null>(null), [peerId, setPeerId] = useState(''), [form, setForm] = useState(''), [raw, setRaw] = useState('');
  const [address, setAddress] = useState(''), [inviteId, setInviteId] = useState(''), [host, setHost] = useState(''), [port, setPort] = useState(''), [recoveryAddresses, setRecoveryAddresses] = useState<string[]>([]);
  const [invitation, setInvitation] = useState<ShownPairingInvitation|null>(null), [review, setReview] = useState<Data | null>(null), [ack, setAck] = useState(false), [future, setFuture] = useState(false);
  const [workBusy, setWorkBusy] = useState(false), [controlBusy, setControlBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('');
  const [taskDetails, setTaskDetails] = useState<Record<string, Data>>({}), [historyReceipt, setHistoryReceipt] = useState<Data | null>(null), [historyStopSubmitted, setHistoryStopSubmitted] = useState(false);
  const [firstPair,setFirstPair]=useState<{handle:string;picker:string;inviteId:string;deviceId:string;fingerprint:string;expiresAt:number;until:number}|null>(null);
  const [waitingPhones, setWaitingPhones] = useState<Data | null>(null);
  const [waitingBlock,setWaitingBlock]=useState<Data|null>(null);
  const [phoneArchive, setPhoneArchive] = useState<Data | null>(null);
  const [closedInbox,setClosedInbox]=useState<Data|null>(null),[closedDraft,setClosedDraft]=useState('');
  const [ownText,setOwnText]=useState<Data|null>(null),ownTextSequence=useRef(0);
  const closeOwnText=()=>{ownTextSequence.current++;setOwnText(null);};
  const autoReadSource=useRef('');
  useEffect(()=>{
    if(!invitation)return;
    const expire=()=>setInvitation(active=>active===invitation?null:active);
    let timer=0;
    const check=()=>{const left=Math.min(invitation.expiresAt-Date.now(),invitation.until-performance.now());
      if(left<=0||document.hidden||snapshot?.local?.deviceId!==invitation.device||snapshot?.local?.instanceId!==invitation.instance
        ||snapshot?.local?.certificateFingerprint!==invitation.fingerprint){expire();return;}
      timer=window.setTimeout(check,Math.min(1000,left));
    };
    check();return()=>window.clearTimeout(timer);
  },[invitation,snapshot?.local?.deviceId,snapshot?.local?.instanceId,snapshot?.local?.certificateFingerprint]);
  const [archiveFind,setArchiveFind]=useState({key:'',text:''});
  const [heldPhoneResult, setPhoneGoalResult] = useState<Data | null>(null);
  const [preparingDeviceWork, setPreparingDeviceWork] = useState(false);
  const [workPhone, setWorkPhone] = useState<PreparedPhone>();
  const [discoveryAck, setDiscoveryAck] = useState(false), [advertise, setAdvertise] = useState(false), [discoveryPeer, setDiscoveryPeer] = useState('');
  const [desktopOpen, setDesktopOpen] = useState(false), [query, setQuery] = useState('');
  const restoredModelReturn = useRef<ReceivedPhoneGoalReturn | undefined>(undefined);
  const [modelReturnFocus, setModelReturnFocus] = useState<ReceivedPhoneGoalReturn>();
  const focusedModelReturn = useRef<ReceivedPhoneGoalReturn | undefined>(undefined);
  const [localExpanded, setLocalExpanded] = useState(false), [moreWork, setMoreWork] = useState({ key: '', count: 0 });
  const lastPeer = useRef('');
  const readingRoot = useRef<HTMLElement>(null), readingPositions = useRef({ list: 0, detail: 0 }), previousReadingView = useRef('list');
  const rememberReadingPosition = (view: 'list' | 'detail') => { readingPositions.current[view] = readingRoot.current?.closest<HTMLElement>('.pw-secondary-scroll')?.scrollTop || 0; };
  const busy = workBusy || controlBusy;
  const alive = useRef(true), scope = useRef(new DeviceRequestScope()), handle = useRef(''), selectedPeer = useRef(peerId), resumed = useRef(state.webWorkspaceResumeRevision), nativeRevision = useRef(state.deviceWorkspaceRevision);
  const acceptSnapshot = (value: any) => { const actual = deviceSnapshot(value); setSnapshot(old => !old || actual.capturedAt >= old.capturedAt ? actual : old); };
  const invoke = async (action: string, args: Data = {}, accept?: (value: Data, snapshot?: Data | null) => void, lane = 'work') => {
    if (!alive.current || document.visibilityState !== 'visible' || scope.current.hasPending(lane)) return;
    if (lane === 'control' && action !== 'devices.confirm') {
      scope.current.invalidate(true); setWorkBusy(false); setReview(null); handle.current = ''; setInvitation(null); setForm(''); setRaw('');
    }
    const ticket = scope.current.begin(lane); if (!ticket) return;
    const setPending = lane === 'control' ? setControlBusy : setWorkBusy;
    setPending(true); setError('');
    try {
      if (!supported(action)) throw new Error('当前宿主尚未接通这项设备操作。');
      const response = await run(action, args);
      if (alive.current && scope.current.owns(ticket)) {
        if (action === 'devices.openTask' || action === 'devices.openCheckedLocalFile' || action === 'devices.discard') accept?.(response);
        else { const receipt = deviceReceipt(response); if (receipt.snapshot) acceptSnapshot(receipt.snapshot); accept?.(receipt.value, receipt.snapshot); }
      } else if (typeof response?.value?.viewHandle === 'string') {
        void run('devices.discard', { viewHandle: response.value.viewHandle }).catch(() => {});
      }
    } catch (failure) { if (alive.current && scope.current.owns(ticket)) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { if (scope.current.finish(ticket) && alive.current) setPending(false); }
  };
  // Supplying a deviceId asks that peer for fresh capabilities. Local reads must also work offline.
  const refresh = () => { void invoke('devices.snapshot',{},(_value,actual)=>{
    if(pinnedConnection && Number.isSafeInteger(actual?.capturedAt))setPinnedLocalRead(actual!.capturedAt);
  }); };
  const closeReview = () => {
    const old = handle.current; handle.current = ''; scope.current.invalidate(document.visibilityState === 'visible');
    setReview(null); setAck(false); setFuture(false); setHistoryReceipt(null); setPhoneGoalResult(null); setHistoryStopSubmitted(false); setWorkBusy(false); setControlBusy(false);
    if (old) void run('devices.discard', { viewHandle: old }).catch(() => {});
  };
  const selectPeer = (next: string) => {
    if (selectedPeer.current === next) return;
    if(closedInbox?.closedHandle)void run('devices.discard',{closedHandle:closedInbox.closedHandle}).catch(()=>{});
    setClosedInbox(null);setClosedDraft('');
    if (waitingPhones?.waitingHandle) void run('devices.discard', { waitingHandle: waitingPhones.waitingHandle }).catch(() => {});
    if (phoneArchive?.archiveHandle) void run('devices.discard', { archiveHandle: phoneArchive.archiveHandle }).catch(() => {});
    closeOwnText();setModelReturnFocus(undefined); setPhoneArchive(null); setArchiveFind({key:'',text:''}); setWaitingPhones(null);
    closeReview(); // Retire pending review handles before changing the list/detail scope.
    setNotice(''); setError(''); setTaskDetails({});
    setDiscoveryAck(false); setAdvertise(false); setDiscoveryPeer('');
    if (next) lastPeer.current = next;
    selectedPeer.current = next;
    setPeerId(next);
  };
  const acceptReview = (value: Data) => { const actual = deviceReview(value); handle.current = actual.viewHandle; setReview(actual); setAck(false); setFuture(false); setHistoryReceipt(null); setPhoneGoalResult(null); setHistoryStopSubmitted(false); setRaw(''); setForm(''); setInvitation(null); };
  const clearSensitive = (enabled = false, keepPairEntry = false) => {
    if(pinnedConnection)setPinnedLocalRead(0);
    setFirstPair(null);setDesktopOpen(false); setDiscoveryAck(false); setAdvertise(false); setDiscoveryPeer('');setClosedInbox(null);setClosedDraft('');closeOwnText(); setModelReturnFocus(undefined); setPhoneArchive(null); setArchiveFind({key:'',text:''}); setWaitingPhones(null);
    scope.current.invalidate(enabled); handle.current = ''; setReview(null); setAck(false); setFuture(false); setRaw(''); setInvitation(null);
    setForm(old => keepPairEntry && (old === 'add' || old === 'invite') ? old : ''); setAddress(''); setHost(''); setPort(''); setRecoveryAddresses([]); setHistoryReceipt(null); setPhoneGoalResult(null); setHistoryStopSubmitted(false); setTaskDetails({}); setSnapshot(null); setError(''); setNotice(''); setWorkBusy(false); setControlBusy(false);
    void run('devices.discard', {}).catch(() => {});
  };
  useEffect(() => { if (document.visibilityState === 'visible') { try { acceptSnapshot(data); } catch { /* A summary is not a native device snapshot. */ } } }, [data]);
  useEffect(() => {
    alive.current = true; scope.current.invalidate(document.visibilityState === 'visible'); refresh();
    return () => { alive.current = false; scope.current.invalidate(false); handle.current = ''; void run('devices.discard', {}).catch(() => {}); };
  }, []);
  useEffect(() => { if (resumed.current === state.webWorkspaceResumeRevision) return; resumed.current = state.webWorkspaceResumeRevision; clearSensitive(document.visibilityState === 'visible', true); refresh(); }, [state.webWorkspaceResumeRevision]);
  useEffect(() => {
    const changed = () => { clearSensitive(document.visibilityState === 'visible', true); if (document.visibilityState === 'visible') refresh(); };
    const hidden = () => clearSensitive(false, true);
    document.addEventListener('visibilitychange', changed); window.addEventListener('pagehide', hidden);
    return () => { document.removeEventListener('visibilitychange', changed); window.removeEventListener('pagehide', hidden); };
  }, []);
  useEffect(() => {
    if (busy || nativeRevision.current === state.deviceWorkspaceRevision || document.visibilityState !== 'visible') return;
    nativeRevision.current = state.deviceWorkspaceRevision; refresh();
  }, [state.deviceWorkspaceRevision, busy]);
  useEffect(() => { registerTransientBack(() => {
    if (preparingDeviceWork) return true;
    if (ownText) {closeOwnText();return true;}
    if (review) { closeReview(); return true; }
    if (invitation) { setInvitation(null); return true; }
    if (form) { clearSensitive(true); refresh(); return true; }
    if (desktopOpen) { setDesktopOpen(false); return true; }
    if (sourceFocus) return false; // Return to the same owner task instead of stepping to another peer.
    if (peerId) { selectPeer(''); return true; }
    return false;
  }); return () => registerTransientBack(null); }, [review, invitation, form, desktopOpen, peerId, sourceFocus, ownText, preparingDeviceWork, registerTransientBack]);
  useEffect(() => {
    if (!snapshot?.lanDiscovery?.active && !snapshot?.lanDiscovery?.stopPending) return;
    const timer = window.setInterval(() => {
      if (Date.now() > Number(snapshot?.lanDiscovery?.expiresAt || 0) + 10_000) { window.clearInterval(timer); return; }
      if (!scope.current.hasPending('work') && document.visibilityState === 'visible') void invoke('devices.snapshot');
    }, 1500);
    return () => window.clearInterval(timer);
  }, [snapshot?.lanDiscovery?.active, snapshot?.lanDiscovery?.stopPending]);
  // Keep list/detail reading position without changing native request or Back ownership.
  useLayoutEffect(() => {
    const view = desktopOpen ? 'desktop' : peerId ? 'detail' : 'list';
    if (view === previousReadingView.current) return;
    const previous = previousReadingView.current, scroll = readingRoot.current?.closest<HTMLElement>('.pw-secondary-scroll');
    if (scroll) scroll.scrollTop = view === 'list' ? readingPositions.current.list : view === 'detail' && previous === 'desktop' ? readingPositions.current.detail : 0;
    previousReadingView.current = view;
    if (view === 'detail') readingRoot.current?.querySelector<HTMLElement>('.pw-device-heading h2')?.focus({ preventScroll: true });
    if (view === 'list') {
      const trigger = Array.from(readingRoot.current?.querySelectorAll<HTMLButtonElement>('button[data-peer-entry]:not(:disabled)') || [])
        .find(button => button.dataset.peerEntry === lastPeer.current);
      (trigger || readingRoot.current?.querySelector<HTMLElement>('[data-this-phone-heading]'))?.focus({ preventScroll: true });
    }
  }, [peerId, desktopOpen]);
  const peers = rows(snapshot?.peers).filter(row => typeof row.deviceId === 'string' && row.deviceId.trim());
  const peer = peerId ? peers.find(row => row.deviceId === peerId) : undefined, local = snapshot?.local || {}, actions = snapshot?.actions || {};
  const PeerIcon = peer ? platformIcon(peer) : IconDevices;
  const pairingEntry = form === 'add' || form === 'invite';
  // Only this short-lived choice ID crosses the system picker. The URI, photo
  // and decoded invite stay native; the owner separately asks to inspect it.
  const [imageId, setImageId] = useState(''), activeImage = useRef(''), imageRun = useRef(run);
  imageRun.current = run;
  const releaseImage = () => {
    const old = activeImage.current; activeImage.current = '';
    if (old) void imageRun.current('devices.pairImage.close', { pickerId: old }).catch(() => {});
  };
  const closeImage = () => { releaseImage(); setImageId(''); };
  useEffect(() => { if (form !== 'add' && activeImage.current) closeImage(); }, [form]);
  useEffect(() => () => releaseImage(), []);
  const leaveFirstPair=()=>{
    if(!firstPair)return;
    setFirstPair(null);setRaw('');closeImage();
    if(handle.current===firstPair.handle){handle.current='';void run('devices.discard',{viewHandle:firstPair.handle}).catch(()=>{});}
  };
  const showPairPreview=(value:Data,picker='')=>{
    if(value.kind!=='firstIdentity'){closeImage();setFirstPair(null);acceptReview(value);return;}
    const v=value.view,left=v?.expiresAt-Date.now();
    if(!v||typeof value.viewHandle!=='string'||!value.viewHandle||typeof v.inviteId!=='string'||!v.inviteId||typeof v.inviterDeviceId!=='string'
      ||!/^pw1_[a-f0-9]{64}$/.test(v.inviterDeviceId)||typeof v.inviterInstanceId!=='string'||!v.inviterInstanceId||!/^[a-f0-9]{64}$/.test(v.inviterFingerprint)
      ||!Number.isSafeInteger(left)||left<=0||left>300000||v.fromImage!==!!picker||picker&&activeImage.current!==picker)throw Error('首次本机身份预览与原邀请不一致。');
    handle.current=value.viewHandle;setFirstPair({handle:value.viewHandle,picker,inviteId:v.inviteId,deviceId:v.inviterDeviceId,fingerprint:v.inviterFingerprint,expiresAt:v.expiresAt,until:performance.now()+left});
  };
  useEffect(()=>{
    if(!firstPair)return;
    let timer=0;const check=()=>{
      const left=Math.min(firstPair.expiresAt-Date.now(),firstPair.until-performance.now());
      if(form!=='add'||document.hidden||left<=0){leaveFirstPair();return;}
      timer=window.setTimeout(check,Math.min(left,1000));
    };check();return()=>window.clearTimeout(timer);
  },[firstPair,form]);
  const continueFirstPair=()=>{
    const first=firstPair;
    if(busy||!first||handle.current!==first.handle||Date.now()>=first.expiresAt||performance.now()>=first.until||
      (first.picker?activeImage.current!==first.picker:!raw.trim()))return;
    setFirstPair(null);handle.current='';setRaw('');
    void invoke('devices.continueFirstPair',{viewHandle:first.handle,acknowledged:true,...(first.picker?{pickerId:first.picker}:{raw})},value=>{
      if(value.kind!=='pair'||value.view?.inviteId!==first.inviteId||value.view?.inviterDeviceId!==first.deviceId||value.view?.inviterFingerprint!==first.fingerprint)throw Error('原双端身份窗口已变化，请重新预览。');
      closeImage();acceptReview(value);
    });
  };
  const chooseImage = () => {
    releaseImage(); const id = crypto.randomUUID(); activeImage.current = id; setImageId(id);
    void invoke('devices.pairImage.pick', { pickerId: id }, value => {
      if (value.pickerId !== id || value.opened !== true) throw new Error('没有收到这次系统选图的回执，请重新选择。');
    });
  };
  const inviteAddresses = strings(actions.localAddresses);
  const inviteAddress = address ? inviteAddresses.includes(address) ? address : '' : inviteAddresses.length === 1 ? inviteAddresses[0] : '';
  const openPairEntry = (side: 'add' | 'invite') => { leaveFirstPair();setRaw(''); setAddress(''); setError(''); setNotice(''); setForm(side); };
  const reportPair = (candidate: Data, receipt: Data | null | undefined) => {
    const here = receipt?.local;
    const localKnown = typeof here?.deviceId === 'string' && !!here.deviceId && typeof here.certificateFingerprint === 'string' && /^[a-f0-9]{64}$/.test(here.certificateFingerprint);
    const other = localKnown && here.deviceId === candidate.inviterDeviceId && here.certificateFingerprint === candidate.inviterFingerprint ? 'joiner'
      : localKnown && here.deviceId === candidate.joinerDeviceId && here.certificateFingerprint === candidate.joinerFingerprint ? 'inviter' : '';
    const otherId = other && candidate[other + 'DeviceId'], otherFingerprint = other && candidate[other + 'Fingerprint'];
    const match = typeof otherId === 'string' && otherId.length > 0 && typeof otherFingerprint === 'string' && /^[a-f0-9]{64}$/.test(otherFingerprint)
      && rows(receipt?.peers).find(item => !retired(item) && item.deviceId === otherId && item.certificateFingerprint === otherFingerprint);
    const original = other && rows(receipt?.pendingPairings).find(row =>
      (row.candidateId || row.inviteId) === (candidate.candidateId || candidate.inviteId) &&
      row.inviterDeviceId === candidate.inviterDeviceId && row.joinerDeviceId === candidate.joinerDeviceId &&
      row.inviterFingerprint === candidate.inviterFingerprint && row.joinerFingerprint === candidate.joinerFingerprint);
    if (original && original.joiningOriginal === true && (pairConclusion(original.state) || !match)) {
      if (match && String(original.state).startsWith('local_pair_')) { rememberReadingPosition('list'); selectPeer(match.deviceId); }
      setNotice(originalPairProgress(original)); return;
    }
    if (match) { rememberReadingPosition('list'); selectPeer(match.deviceId); setNotice('本机已读到同一身份的配对伙伴。查看双方授权范围，再决定是否开始工作。'); }
    else setNotice(other === 'joiner' ? '本机已确认。请回到加入邀请的设备，选「继续原配对」完成双端握手。'
      : other === 'inviter' ? '原配对尚未读到已保存的伙伴。请对方核对指纹与范围，再从这台手机继续原配对。' : '配对请求已有回执，尚未核对到同一身份的伙伴。请查看原配对记录。');
  };
  const tasks = deviceTaskRows(state.tasks, snapshot, peerId);
  // Model settings are local. Restore only the exact saved recipient/task on a new owner view.
  useEffect(() => {
    if (!modelReturn || restoredModelReturn.current === modelReturn || !Array.isArray(state.tasks) || !snapshot || !Number.isSafeInteger(snapshot.capturedAt) || snapshot.capturedAt < modelReturn.leftAt || document.visibilityState !== 'visible') return;
    if(modelReturn.modelReturnHandle){
      if(busy||scope.current.hasPending('work')||scope.current.hasPending('control'))return;
      restoredModelReturn.current=modelReturn;
      if(snapshot.local?.deviceId!==modelReturn.targetId || snapshot.local?.instanceId!==modelReturn.instanceId){setNotice('接收手机身份已变化，未打开其他工作。');return;}
      // Back from this phone's model settings is a local read, not a review, credential change or submit.
      void invoke('devices.returnToReceivedPhoneGoal',{modelReturnHandle:modelReturn.modelReturnHandle,taskId:modelReturn.taskId,operationId:modelReturn.operationId},value=>{
        const restored=receivedPhoneArchiveReturn(value,modelReturn,{deviceId:modelReturn.sourceId},snapshot.local);
        if(restored.grantId!==modelReturn.grantId||restored.epoch!==modelReturn.epoch||restored.parentTaskId!==modelReturn.parentTaskId)
          throw new Error('原模型设置返回位置不再属于当时的派工。');
        const detail=modelReturn.peerDetail&&rows(snapshot.peers).some(row=>row.deviceId===restored.sourceId);
        selectPeer(detail?restored.sourceId:'');setModelReturnFocus({...restored,peerDetail:detail});
        setNotice('已回到本机接纳的同一件工作。模型设置没有启动任务；请按它的当前状态决定是否重新审阅。');
      });return;
    }
    restoredModelReturn.current = modelReturn;
    const task = deviceTaskRows(state.tasks, snapshot, '').find(item => matchesReceivedPhoneGoal(item, snapshot, modelReturn));
    if (!task || unreadSource(task) || modelReturn.peerDetail && !rows(snapshot.peers).some(item => item.deviceId === modelReturn.sourceId)) {
      setNotice('原派工或本机身份已变化，未打开其他任务。请从设备记录重新选择。'); return;
    }
    selectPeer(modelReturn.peerDetail ? modelReturn.sourceId : '');
    setModelReturnFocus(modelReturn);
    setNotice(!task.raw.runId && ['queued', 'awaiting_user'].includes(task.status) && !task.raw.cancelRequested
      ? '已回到这次原派工。选择模型不等于接受任务，请重新审阅原目标、范围与预算。' : '已回到同一项原工作。它已不在原先的待接手状态，未重新启动。');
  }, [modelReturn, snapshot, state.tasks,busy]);
  const readPeerCapabilities = () => {
    if (!peer || selectedPeer.current !== peer.deviceId || retired(peer)) return;
    setNotice('');
    void invoke('devices.snapshot', { deviceId: peer.deviceId }, () => setNotice('已收到读取回执，以下方能力记录的时间为准。'));
  };
  const phoneCapabilities = rows(peer?.capabilitySnapshot?.capabilities).filter(item => ['phone.tools.main', 'phone.tools.background', 'agent.goal'].includes(item.id));
  const now = Date.now(), validUntil = peer?.capabilitySnapshot?.validUntil;
  const received = peer && deviceRecordedScopes(peer, true, now), given = peer && deviceRecordedScopes(peer, false, now);
  // Repaint a recorded expiry while the page is open; never poll or contact the partner here.
  const actionExpiries = rows(state.tasks).filter(task => isReceivedPhoneTask(task, snapshot)).flatMap(task => {
    const item = taskDetails[task.id], origin = task.raw.origin, source = rows(snapshot?.peers).find(row => row.deviceId === origin.callerDeviceId);
    const grant = rows(source?.grantScopes).find(row => row.grantId === origin.grantId && row.authorizationEpoch === origin.authorizationEpoch);
    const captured = item?.phoneActionScope ? item.actions.capturedAt : snapshot?.taskActions?.[task.id]?.capturedAt;
    return [captured + PHONE_ACTION_WINDOW_MS, item?.phoneActionScope?.executionDeadline ?? task.raw.deviceExecutionScope?.deadline, grant?.expiresAt];
  });
  const nextExpiry = Math.min(...[validUntil, ...actionExpiries, ...rows(waitingPhones?.rows).map(row=>row.sourceIdentity?.expiresAt), ...rows(snapshot?.pendingPairings).filter(row => row.localOwnerWindow).map(row => row.expiresAt), ...rows(peer?.receivedGrantScopes).map(row => row.expiresAt), ...rows(peer?.grantScopes).map(row => row.expiresAt)]
    .filter(t => Number.isSafeInteger(t) && t > now));
  const [clockRevision, tickClock] = useState(0);
  useEffect(() => {
    if (!Number.isFinite(nextExpiry)) return;
    const timer = window.setTimeout(() => tickClock(value => value + 1), Math.min(2_147_483_647, Math.max(1, nextExpiry - Date.now())));
    return () => window.clearTimeout(timer);
  }, [nextExpiry, clockRevision]);
  const windowsObservation = windowsObservationCompatibility(peer, now);
  const capabilityCaveat = !Number.isSafeInteger(validUntil) || validUntil <= 0 ? '尚未核实有效期限' : validUntil <= now ? '上次读取已过期' : '';
  const phonePeer = /android|phone/i.test(peer?.platform || '') || phoneCapabilities.some(item => String(item.id).startsWith('phone.'));
  const phoneAccess = peer && phonePeer && !retired(peer) ? phoneSurfaceStatus(peer, now) : null;
  const desktopPeer = !!peer && (!!peer.windowsDesktop || /windows/i.test(peer.platform || '') || desktopScopes(peer, now, local.deviceId).length > 0
    || rows(peer.capabilitySnapshot?.capabilities).some(item => item.id === 'desktop' || String(item.id).startsWith('desktop.')));
  const disclosure = peer?.capabilitySnapshot;
  const lastIdentityOnly = !!peer && disclosure?.metadata?.disclosureScope === 'pair_identity_only' &&
    disclosure.deviceId === peer.deviceId && disclosure.instanceId === peer.instanceId &&
    Array.isArray(disclosure.capabilities) && !disclosure.capabilities.length;
  // A cached description cannot create authority. A separately obtained working receipt, if any,
  // must not be hidden behind an older identity-only description.
  const identityOnlyHere = lastIdentityOnly && !rows(peer?.receivedGrantScopes).some(row => row.receivedScope === true &&
    Number.isSafeInteger(row.expiresAt) && row.expiresAt > now && strings(row.capabilities).some(method => method === 'task.submitGoal' || method === 'tool.invoke'));
  const openDirectedPhone = (destination:'work'|'manual') => {
    if (!peer || selectedPeer.current !== peer.deviceId || retired(peer)) return;
    if (!phonePeer || desktopPeer || identityOnlyHere || !/^pw1_[a-f0-9]{64}$/.test(peer.deviceId) ||
      typeof peer.instanceId !== 'string' || !peer.instanceId || !/^[a-f0-9]{64}$/.test(peer.certificateFingerprint || '')) {
      setError('没有能锁定到这部手机的工作范围。没有创建任务，也没有换成其他设备。'); return;
    }
    setWorkPhone({ deviceId: peer.deviceId, instanceId: peer.instanceId, certificateFingerprint: peer.certificateFingerprint, label: name(peer) });
    setForm(destination);
  };
  const prepareDeviceGoal = () => openDirectedPhone('work');
  const prepareWindowsWorkspace = () => {
    if (!peer || !desktopPeer || identityOnlyHere || selectedPeer.current !== peer.deviceId || retired(peer)) return;
    if (!/^pw1_[a-f0-9]{64}$/.test(peer.deviceId) || typeof peer.instanceId !== 'string' || !peer.instanceId || !/^[a-f0-9]{64}$/.test(peer.certificateFingerprint || '')) {
      setError('原 Windows 配对身份尚未核对，没有替换其他设备。'); return;
    }
    setWorkPhone({ deviceId: peer.deviceId, instanceId: peer.instanceId, certificateFingerprint: peer.certificateFingerprint, label: name(peer), kind: 'windows-workspace' });
    setForm('work');
  };
  const openDesktopPreparation = () => { rememberReadingPosition('detail'); setError(''); setNotice(''); setDesktopOpen(true); };
  // See the next available step without making old work a prerequisite for new work.
  const peerEntry = !peer || retired(peer) ? null
    : !deviceChannelWasConnected(peer) && available(peer.actions?.connect) && supported('devices.reviewAction')
      ? { kind: 'connect', label: '核对并连接', icon: IconDevices, open: () => actionReview('peer.connect', { deviceId: peer.deviceId }) }
    : identityOnlyHere ? (deviceChannelWasConnected(peer) && supported('devices.snapshot')
      ? { kind: 'capabilities', label: '向对端核对身份与回执', icon: IconLayers, open: readPeerCapabilities } : null)
    : desktopPeer ? { kind: 'desktop', label: '准备查看桌面', icon: IconMonitor, open: openDesktopPreparation }
    : phonePeer && supported('deviceTaskScope.prepare')
      ? { kind: 'goal', label: '准备交办', icon: IconAdd, open: prepareDeviceGoal }
    : deviceChannelWasConnected(peer) && supported('devices.snapshot')
      ? { kind: 'capabilities', label: '核对可用能力', icon: IconLayers, open: () => readPeerCapabilities() }
      : null;
  const localPhone = rows(state.phoneReadiness?.groups);
  const phoneStatus = (id: string) => { const value = localPhone.find(row => row.id === id); return ({ ready: '本机检查可用', blocked: '需要准备', unsupported: '本机不支持', unknown: '尚未核实' } as Record<string,string>)[value?.state] || '尚未核实'; };
  const localScreenDetail = (id: string) => {
    const group = localPhone.find(value => value.id === id), checks = rows(group?.checks);
    const reason = checks.find(check => check.state === group?.state && check.state !== 'ready' && check.message) || checks.find(check => check.state !== 'ready' && check.message);
    return reason ? phoneStatus(id) + ' · ' + reason.message : phoneStatus(id);
  };
  const openPhoneSetup = (section: 'accessibility' | 'background' | 'notifications') => { if (busy) return; clearSensitive(true, form === 'invite'); void run('settings.openSystem', { section }).catch(async failure => { await invoke('devices.snapshot'); if (alive.current && document.visibilityState === 'visible') setError(failure instanceof Error ? failure.message : String(failure)); }); };
  const actionReview = (operation: string, extra: Data = {}) => { void invoke('devices.reviewAction', { operation, ...extra }, acceptReview, operation.startsWith('task.') ? 'control' : 'work'); };
  const confirmReview = (extra: Data = {}) => {
    const original = review; if (!original || !ack || busy) return;
    handle.current = ''; setReview(null);
    void invoke('devices.confirm', { viewHandle: original.viewHandle, acknowledged: true, ...(original.kind === 'goal' ? { useForFutureGoals: future } : {}), ...extra }, (value, actual) => {
      if (value.nativeCallReturned !== true || value.kind !== original.kind) throw new Error('原设备操作尚未取得匹配回执，请刷新核对。');
      if (original.kind === 'pair') { reportPair(original.view, actual); return; }
      if(original.kind==='goal'||original.kind==='action'&&original.view.operation==='task.cancelPending'){
        // A confirmed choice changed this local record. Invalidate the page; never infer a run or refetch a peer.
        setWaitingPhones(page=>!page?.resultOnly&&rows(page?.rows).some(r=>r.taskId===original.view.taskId)?null:page);
        setWaitingBlock(null);
      }
      if (original.kind === 'result') setWaitingPhones(page => page?.resultOnly ? { ...page,
        rows: rows(page.rows).filter(row => row.taskId !== original.view.taskId || row.reviewRecordedAt !== original.view.recordedAt) } : page);
      const pending=original.kind==='action'?original.view.pendingGoal:null;
      if(pending?.runStarted===false&&pending.localOnly===true){
        const r=value.receipt;
        const confirmed=r?.kind==='receivedGoalDecline'&&r.verified===true&&r.notDispatched===true&&r.taskId===pending.taskId&&r.operationId===pending.operationId&&
          r.sourceId===pending.callerDeviceId&&r.acceptedAt===pending.acceptedAt&&r.localDeviceId===local.deviceId&&r.localInstanceId===local.instanceId;
        setNotice(confirmed?'本机已结束这件工作，它从未启动。不需要发起设备在线，也不代表对方已读。':'尚未核实本机这份原件是否已结束。请重新读取本机原工作，不要重复提交。');return;
      }
      setNotice(original.kind==='result' && extra.keepTextLocal===true ? '本机已确认完成；原发起设备只可读取完成状态，本次没有交回文字。' : original.kind === 'resume' ? extra.approved === true ? '已处理这次原执行的恢复请求，实际进展以原任务回执为准。' : '已拒绝这次恢复请求，原任务未因本次拒绝而继续。' : original.kind === 'action' && original.view.operation?.startsWith('task.') ? '已发出原任务控制请求。实际暂停、继续或停止以原任务回执为准。' : '原生处理已返回，请核对下方实际状态。');
    }, original.kind === 'resume' || original.kind === 'action' && original.view.operation?.startsWith('task.') ? 'control' : 'work');
  };
  const openHistory = (taskId: string, link: Data) => {
    const selected = selectedPeer.current;
    if (selected !== peerId || !deviceTaskHistoryLinks(taskDetails[taskId], taskId, selected)
      .some(row => row.operationId === link.operationId && row.targetDeviceId === link.targetDeviceId)) return;
    const page = taskDetails[taskId]?.history;
    void invoke('devices.openHistory', { taskId, operationId: link.operationId, ...(page?.pageHandle ? { historyPageHandle: page.pageHandle } : {}) }, value => {
      if (value.kind !== 'history' || value.view?.taskId !== taskId ||
        value.view?.operationId !== link.operationId || value.view?.targetDeviceId !== link.targetDeviceId) {
        if (typeof value.viewHandle === 'string') void run('devices.discard', { viewHandle: value.viewHandle }).catch(() => {});
        throw new Error('回执与所选设备或原操作不符，请重新读取。');
      }
      acceptReview(value);
    });
  };
  const pinnedReadReady=!pinnedConnection || pinnedLocalRead>0 && Number.isSafeInteger(snapshot?.capturedAt) && snapshot!.capturedAt>=pinnedLocalRead;
  const focusedPeer=sourceFocus && pinnedReadReady?peers.find(it=>it.deviceId===sourceFocus.deviceId && it.instanceId===sourceFocus.instanceId
    && it.certificateFingerprint===sourceFocus.certificateFingerprint && !retired(it)):undefined;
  const activeArchive = samePhoneGoalArchive(phoneArchive, peer, local) && (phoneArchive?.sourceTaskId||'')===(sourceFocus?.taskId||'')
    && (!sourceFocus || peer===focusedPeer) ? phoneArchive : null;
  const waitingPage = waitingPhones && waitingPhones.localDeviceId === local.deviceId && waitingPhones.localInstanceId === local.instanceId ? waitingPhones : null;
  const localInbox=closedInbox&&closedInbox.localDeviceId===local.deviceId&&closedInbox.localInstanceId===local.instanceId?closedInbox:null;
  const readClosedInbox=(direction?:'older'|'newer',query='')=>{
    if(busy||selectedPeer.current||direction&&!localInbox)return;
    closeOwnText();
    if(waitingPhones?.waitingHandle)void run('devices.discard',{waitingHandle:waitingPhones.waitingHandle}).catch(()=>{});
    setWaitingPhones(null);setWaitingBlock(null);
    const search=direction?localInbox!.searchText||'':query.trim();
    void invoke('devices.readReviewedPhoneInbox',direction?{closedHandle:localInbox!.closedHandle,direction}:search?{searchText:search}:{},value=>{
      if(!selectedPeer.current){setClosedInbox(localReviewedInbox(value,local,search));setClosedDraft(search);}
    });
  };
  const sourceLabel = (id:string, original?:Data) => {
    const known=original && Number.isSafeInteger(original.expiresAt) && original.expiresAt>Date.now() &&
      peers.find(p=>p.deviceId===id && !retired(p) && p.instanceId===original.instanceId &&
        p.certificateFingerprint===original.fingerprint && p.authorizationEpoch===original.authorizationEpoch);
    return known ? name(known) : '来源设备 '+id.slice(-6);
  };
  const readWaiting = (direction?: 'older' | 'newer', wanted:'pending'|'started'|'results'|'declined'|'failed'|'withdrawn'='pending') => {
    if (busy || selectedPeer.current || direction && !waitingPage) return;
    if(localInbox){closeOwnText();setClosedInbox(null);setClosedDraft('');void run('devices.discard',{closedHandle:localInbox.closedHandle}).catch(()=>{});}
    const lane=direction?(waitingPage!.withdrawnOnly?'withdrawn':waitingPage!.failedOnly?'failed':waitingPage!.declinedOnly?'declined':waitingPage!.startedOnly?'started':waitingPage!.resultOnly?'results':'pending'):wanted;setWaitingBlock(null);
    void invoke('devices.readWaitingPhoneGoals', direction ? { waitingHandle: waitingPage!.waitingHandle, direction } : lane!=='pending'?{queue:lane}:{}, value => {
      if (!selectedPeer.current) {
        const page = waitingPhoneGoalPage(value,local);
        if ((page.withdrawnOnly===true)!==(lane==='withdrawn')||(page.failedOnly===true)!==(lane==='failed')||(page.resultOnly === true)!==(lane==='results')||(page.startedOnly===true)!==(lane==='started')||(page.declinedOnly===true)!==(lane==='declined')) throw new Error('原设备工作页已变化，未切换到其他记录。');
        setWaitingPhones(page);
      }
    });
  };
  const reviewWaiting = (row:Data) => {
    if(busy||selectedPeer.current||!waitingPage||waitingPage.resultOnly||waitingPage.startedOnly||waitingPage.declinedOnly||waitingPage.withdrawnOnly||waitingPage.failedOnly)return;
    const args={waitingHandle:waitingPage.waitingHandle,taskId:row.taskId,operationId:row.operationId};
    setWaitingBlock(null);
    void invoke('devices.reviewGoal',args,value=>{
      if(selectedPeer.current)return;
      if(value.kind==='waitingGoalBlocked'&&value.waitingHandle===args.waitingHandle&&value.taskId===args.taskId&&value.operationId===args.operationId&&value.sourceId===row.sourceDeviceId){setWaitingBlock(value);return;}
      if(value.kind!=='goal'||value.view?.taskId!==args.taskId||value.view?.origin?.remoteOperationId!==args.operationId||value.view?.origin?.callerDeviceId!==row.sourceDeviceId)throw Error('这次核对不属于所选原目标，请重新读取。');
      acceptReview(value);
    });
  };
  const returnWaiting = (row: Data) => {
    if (busy || selectedPeer.current || !waitingPage || waitingPage.declinedOnly || waitingPage.withdrawnOnly || !rows(waitingPage.rows).some(it => it.taskId === row.taskId && it.operationId === row.operationId && it.sourceDeviceId === row.sourceDeviceId)) return;
    void invoke('devices.returnToReceivedPhoneGoal', { taskId: row.taskId, operationId: row.operationId, waitingHandle: waitingPage.waitingHandle }, value => {
      if (selectedPeer.current) return;
      const source = receivedPhoneArchiveReturn(value,row,{ deviceId: row.sourceDeviceId },local);
      setModelReturnFocus({ ...source, peerDetail: false }); setNotice(waitingPage.failedOnly?'已定位这次失败的原执行，只读取本机记录；没有恢复或重派。':waitingPage.startedOnly?'已定位这次原执行，没有向任何设备发出恢复、停止或重派请求。':waitingPage.resultOnly ? '已打开仍待核对的原执行。请查看成果后再决定是否确认；尚未交回摘要。' : '已找回本机接纳的原工作，以它当前的可用操作为准。没有重新启动。');
    });
  };
  const archiveSide: PhoneArchiveSide = activeArchive?.side === 'received' ? 'received' : 'sent';
  const findKey=JSON.stringify([local?.deviceId,local?.instanceId,peer?.deviceId,peer?.instanceId,peer?.certificateFingerprint,archiveSide]);
  const archiveDraft=archiveFind.key===findKey?archiveFind.text:String(activeArchive?.searchText||'');
  const readPhoneArchive = (side: PhoneArchiveSide, direction?: 'older' | 'newer', searchText='') => {
    if (busy || !peer || selectedPeer.current !== peer.deviceId || direction && (!activeArchive || archiveSide !== side)) return;
    if(sourceFocus && (!focusedPeer || focusedPeer!==peer || side!=='sent' || searchText))return;
    closeOwnText();const sourceId=sourceFocus?.taskId||'';
    const target = peer.deviceId, keyword=direction?String(activeArchive?.searchText||''):searchText.trim();
    void invoke(side === 'received' ? 'devices.readReceivedPhoneGoals' : 'devices.readPhoneGoalArchive', direction ? { archiveHandle: activeArchive!.archiveHandle, direction } : { targetDeviceId: target, ...(sourceId?{sourceTaskId:sourceId}:{}), ...(keyword?{searchText:keyword}:{}) }, value => {
      const actual = devicePhoneGoalArchive(value, peer, local, side,sourceId); if(String(actual.searchText||'')!==keyword)throw new Error('这次本机查找已变化，请再查一次。');
      if (selectedPeer.current === target) {setPhoneArchive(actual); setArchiveFind({key:JSON.stringify([local?.deviceId,local?.instanceId,peer.deviceId,peer.instanceId,peer.certificateFingerprint,side]),text:keyword});}
    });
  };
  useEffect(()=>{
    if(!sourceFocus || pinnedConnection || !focusedPeer || busy || !snapshot || document.visibilityState!=='visible')return;
    if(peerId!==sourceFocus.deviceId){selectPeer(sourceFocus.deviceId);return;}
    const k=JSON.stringify([sourceFocus.taskId,sourceFocus.deviceId,sourceFocus.instanceId,sourceFocus.certificateFingerprint,local.deviceId,local.instanceId]);
    if(autoReadSource.current===k || !supported('devices.readPhoneGoalArchive'))return;
    autoReadSource.current=k;readPhoneArchive('sent'); // This method reads only this phone's retained records.
  },[sourceFocus,focusedPeer,busy,peerId,local.deviceId,local.instanceId]);
  const ownTextShown=ownText && (ownText.home?!!localInbox&&ownText.archiveHandle===localInbox.closedHandle:activeArchive?.side==='received'&&ownText.archiveHandle===activeArchive.archiveHandle)?ownText:null;
  const readOwnText=(row:Data,page=0,before?:Data,home=ownText?.home===true,part:string=before?.part||'result',match=false)=>{
    if(row.notConfirmed===true)return;
    const archive=home?localInbox:activeArchive;
    if(busy||sourceFocus||!archive||(home?!!selectedPeer.current:!peer||selectedPeer.current!==peer.deviceId||archive.side!=='received')
      ||!rows(archive.rows).some(it=>it.taskId===row.taskId&&it.operationId===row.operationId&&(home?it.sourceDeviceId===row.sourceDeviceId:it.mayCheckLocalText===true)))return;
    if(match){
      if(!home||!archive.searchText||before||!['goal','result'].includes(row.matchPart)||!Number.isSafeInteger(row.matchPage))return;
      page=row.matchPage;part=row.matchPart;
    }
    const ticket=++ownTextSequence.current,handle=home?archive.closedHandle:archive.archiveHandle;
    const resultPage=part==='goal'?(before?.part==='result'?before.page:ownText?.resultPage||0):page;
    setOwnText({archiveHandle:handle,home,row,resultPage,page:null});
    void invoke('devices.readLocalReviewedPhoneGoal',{[home?'closedHandle':'archiveHandle']:handle,taskId:row.taskId,operationId:row.operationId,
      ...(match?{openMatch:true}:before?{page,stamp:before.stamp,part}:{})}, value=>{
      if(ticket!==ownTextSequence.current||(home?!!selectedPeer.current:selectedPeer.current!==peer?.deviceId))return;
      const actual=localReceivedPhonePage(value,archive,row,page,before,part);
      setOwnText({archiveHandle:handle,home,row,resultPage,page:actual});
    });
  };
  const openArchivedGoal = (row: Data, sourceWork: boolean) => {
    if (busy || !peer || !activeArchive || activeArchive.targetDeviceId !== selectedPeer.current || !rows(activeArchive.rows).some(entry => entry.taskId === row.taskId && entry.operationId === row.operationId)) return;
    const args = { taskId: row.taskId, operationId: row.operationId, archiveHandle: activeArchive.archiveHandle };
    if (archiveSide === 'received') {
      void invoke('devices.returnToReceivedPhoneGoal', args, value => {
        if (selectedPeer.current !== peer.deviceId) return;
        setModelReturnFocus(receivedPhoneArchiveReturn(value, row, peer, local));
        setNotice('已找回本机接纳的原工作。没有重新开始；可用操作请从该工作单独读取。');
      });
      return;
    }
    if (sourceWork) { void invoke('devices.openTask', args); return; }
    void invoke('devices.openHistory', args, value => {
      if (value.kind !== 'history' || value.view?.archiveReadOnly !== true || value.view.phoneGoalResult !== true || value.view.taskId !== row.taskId
        || value.view.operationId !== row.operationId || value.view.targetDeviceId !== activeArchive.targetDeviceId) {
        if (typeof value.viewHandle === 'string') void run('devices.discard', { viewHandle: value.viewHandle }).catch(() => {});
        throw new Error('原手机或派发已变化，未打开其他结果。');
      }
      acceptReview(value);
    });
  };
  const queryHistory = (stop = false) => {
    const original = review; if (original?.kind !== 'history' || busy || stop && (original.view?.archiveReadOnly || !ack || historyStopSubmitted)) return;
    if (stop) setHistoryStopSubmitted(true);
    setHistoryReceipt(null); // A disconnected re-read must not keep a prior Windows result on screen.
    setPhoneGoalResult(null); // Never leave a prior run's result beside a newly checked receipt.
    void invoke(stop ? 'devices.stopHistory' : 'devices.queryHistory', { viewHandle: original.viewHandle, ...(stop ? { acknowledged: true } : {}) }, value => {
      const actual = deviceHistoryReceipt(value, original); setHistoryReceipt(actual.receipt); setHistoryStopSubmitted(actual.stopSubmitted); setAck(false);
    });
  };
  const phoneResultPath = review?.kind === 'history' ? historyReceipt?.localPhoneResultPath ?? review.view.localPhoneResultPath : undefined;
  const returnPhoneSource = () => {
    const original = review;
    if (busy || original?.kind !== 'history' || original.view.phoneGoalResult !== true || !['original_run', 'need_dispatch_receipt'].includes(original.view.localPhoneResultPath)) return;
    void invoke('devices.openTask', { taskId: original.view.taskId, operationId: original.view.operationId, sourceViewHandle: original.viewHandle });
  };
  // A visible result is not a durable local copy. A receipt with no business text needs no text window.
  const textWindow=heldPhoneResult?.textWindow, shownPeer=textWindow?peers.find(it=>it.deviceId===textWindow.target):undefined;
  const textRemaining=()=>Math.min(Number(textWindow?.until)-Date.now(),Number(textWindow?.monotonicUntil)-performance.now());
  const sameRead=!textWindow || (textWindow.handle===review?.viewHandle && textWindow.revision===state.deviceWorkspaceRevision
    && local.deviceId===textWindow.local && local.instanceId===textWindow.localInstance && !!shownPeer && !retired(shownPeer)
    && shownPeer.instanceId===textWindow.instance && shownPeer.certificateFingerprint===textWindow.fingerprint);
  const textClosed=heldPhoneResult?.textClosed===true || !!textWindow && (!sameRead || textRemaining()<=0);
  const phoneGoalResult=textClosed?null:heldPhoneResult;
  useEffect(()=>{
    if(!textWindow)return;
    const retire=()=>setPhoneGoalResult(old=>old?.textWindow===textWindow?{textClosed:true}:old);
    const left=textRemaining();
    if(!sameRead || left<=0){retire();return;}
    const timer=window.setTimeout(retire,Math.ceil(left));
    return ()=>window.clearTimeout(timer);
  },[textWindow,sameRead]);
  const readPhoneGoal = () => {
    const original = review; if (original?.kind !== 'history' || original.view.phoneGoalResult !== true || phoneResultPath !== 'original_run' || busy) return;
    setPhoneGoalResult(null);
    const began=performance.now(),revision=state.deviceWorkspaceRevision;
    void invoke('devices.queryPhoneGoalResult', { viewHandle: original.viewHandle }, value => {
      const result=devicePhoneGoalResult(value,original);
      if(!result.summary){setPhoneGoalResult(result);return;}
      const pair=peers.find(it=>it.deviceId===original.view.targetDeviceId && it.instanceId===result.instanceId && it.certificateFingerprint===result.textTargetFingerprint && !retired(it));
      const ttl=Math.min(result.textReadUntil-Date.now(),result.textReadRemainingMs,5000-(performance.now()-began));
      if(!pair || !local.instanceId || ttl<=0){setPhoneGoalResult({textClosed:true});return;}
      setPhoneGoalResult({...result,textWindow:{handle:original.viewHandle,revision,target:pair.deviceId,instance:pair.instanceId,fingerprint:pair.certificateFingerprint,
        local:local.deviceId,localInstance:local.instanceId,until:result.textReadUntil,monotonicUntil:performance.now()+ttl}});
    });
  };
  const openRecovery = (original: Data, providing: boolean) => { setInviteId(original.inviteId); setHost(''); setPort(''); setAddress(''); if (providing) void invoke('devices.recoveryAddresses', {}, value => { setRecoveryAddresses(strings(value.addresses)); setForm('recoveryProvide'); }); else setForm('recoveryRead'); };
  const originalView = review?.view || {}, exactGrant = originalView.grantReview;
  const declining=review?.kind==='action'&&originalView.pendingGoal?.runStarted===false&&originalView.pendingGoal?.localOnly===true;
  const pairings = rows(snapshot?.pendingPairings);
  const pendingPairs = pairings.filter(row => !pairConclusion(row.state) && !ownerWindowEnded(row, now));
  const trusted = peers.filter(row => !retired(row));
  const visiblePeers = peers.filter(peer => [name(peer), peer.deviceId, peerPlatform(peer)].filter(Boolean).join(' ').toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()));
  const peerSections = [
    { id: 'online', title: '上次检查已接通', peers: visiblePeers.filter(row => !retired(row) && row.connection?.state === 'ONLINE') },
    { id: 'degraded', title: '上次连接不稳定', peers: visiblePeers.filter(row => !retired(row) && row.connection?.state === 'DEGRADED') },
    { id: 'other', title: '未接通或待核对', peers: visiblePeers.filter(row => !retired(row) && !deviceChannelWasConnected(row)) },
    { id: 'revoked', title: '已撤销的设备记录', peers: visiblePeers.filter(retired) },
  ];
  const deviceView = desktopOpen ? 'desktop' : peerId ? `detail:${peerId}` : 'list';
  // The media viewport keeps its real geometry; only directory/detail surfaces move.
  useSurfaceEnter(readingRoot, deviceView, { selector: '', depth: desktopOpen ? 2 : peerId ? 1 : 0, active: !desktopOpen && !!snapshot });
  const receiverLabel = local.receiverEnabled === true ? local.listenerReady === true ? '本机接收通道已启动' : '接收已启用，通道仍待就绪' : local.receiverEnabled === false ? '设备接收未启用' : '接收状态尚未确认';
  const taskLabels: Record<string, string> = { queued: '等待执行', preparing: '准备中', running: '执行中', pausing: '正在暂停', paused: '已暂停', stopping: '正在停止', verifying: '正在核对', needs_confirmation: '成果待确认', awaiting_user: '待你处理', waiting_user: '待你处理', waiting_approval: '等待授权', awaiting_approval: '等待授权', waiting_permission: '等待授权', interrupted: '已中断', completed: '已完成', failed: '未完成', cancelled: '已停止', canceled: '已停止', stopped: '已停止', reconciling: '结果待核对', reconciliation_required: '结果待核对', blocked_unknown:'执行效果待核对', unknown: '结果待核对' };

  const workKey = peerId ? 'peer:' + peerId : 'directory', extraWork = moreWork.key === workKey ? moreWork.count : 0;
  const actionsFor = (task: Data, at = now) => deviceOriginalTaskActions(task, taskDetails[task.id], snapshot, peerId, at);
  const actIfCurrent = (task: Data, key: string, next: () => void) => {
    const fresh = actionsFor(task, Date.now());
    if (unreadSource(task) || !available(fresh[key])) { setError(fresh.phoneActionReason || fresh[key]?.reason || '原操作状态已变化，请重新读取。'); return; }
    next();
  };
  const resumeRequest = (task: Data) => {
    const request = actionsFor(task).resumeRequest;
    return !unreadSource(task) && request?.taskId === task.id && (!peerId || request.callerDeviceId === peerId) ? request : undefined;
  };
  const needsOwner = (task: Data) => {
    if (unreadSource(task)) return false;
    const facts = actionsFor(task);
    return available(facts?.goalReview) || available(facts?.resultReview) || resumeRequest(task)?.ownerActionRequired === true
      || rows(task.raw?.approvals).some(item => item.state === 'pending');
  };
  const chooseModelForReceived=(args:Data,row:Data,sourceId:string,expected?:Data)=>{
    if(!openModels||busy||sourceFocus||!supported('devices.prepareReceivedModelReturn'))return;
    const selected=selectedPeer.current;if(selected&&selected!==sourceId)return;
    void invoke('devices.prepareReceivedModelReturn',args,value=>{
      if(selectedPeer.current!==selected)return;
      const source=receivedPhoneArchiveReturn(value,row,{deviceId:sourceId},local);
      if(!/^[0-9a-f-]{36}$/.test(value.modelReturnHandle||'') || expected&&(source.parentTaskId!==expected.parentTaskId || source.grantId!==expected.grantId || source.epoch!==expected.epoch))
        throw new Error('未取得当时原派工的本机返回位置，没有打开模型设置。');
      closeReview();openModels({...source,peerDetail:!!selected,modelReturnHandle:value.modelReturnHandle});
    });
  };
  const openModelSettingsFor = (task: Data) => {
    if (!openModels || busy || unreadSource(task) || task.raw?.runId || !available(actionsFor(task, Date.now()).goalReview)) {
      setError('这次待接手状态已变化，请重新读取原工作后再选择模型。'); return;
    }
    const origin = receivedPhoneGoalReturn(task, snapshot, !!peerId);
    if (!origin || peerId && origin.sourceId !== peerId) { setError('无法核对本机与原派工，没有打开其他工作。'); return; }
    if(supported('devices.prepareReceivedModelReturn'))chooseModelForReceived({taskId:task.id},origin,origin.sourceId,origin);
    else {closeReview();openModels(origin);}
  };
  const returnedTask = modelReturnFocus && tasks.find(item => !unreadSource(item) && matchesReceivedPhoneGoal(item, snapshot, modelReturnFocus));
  const waiting: Data[] = [], interruptible: Data[] = [], remaining: Data[] = [];
  const canStop = (task: Data) => { const facts = actionsFor(task); return available(facts.stop) || available(facts.pendingCancel); };
  // The native 40-action window bounds controls, not the number of saved originals one may open.
  for (const task of tasks) if (task !== returnedTask) (needsOwner(task) ? waiting : canStop(task) ? interruptible : remaining).push(task);
  const initialCount = Math.max(0, 2 - Number(!!returnedTask) - waiting.length - interruptible.length);
  const shownTasks = [...(returnedTask ? [returnedTask] : []), ...waiting, ...interruptible, ...remaining.slice(0, initialCount + extraWork)];
  const nextCount = Math.min(12, tasks.length - shownTasks.length);
  useLayoutEffect(() => {
    if (!returnedTask || !modelReturnFocus || focusedModelReturn.current === modelReturnFocus) return;
    const heading = Array.from(readingRoot.current?.querySelectorAll<HTMLElement>('[data-received-goal]') || []).find(el => el.dataset.receivedGoal === returnedTask.id);
    if (!heading) return;
    focusedModelReturn.current = modelReturnFocus; heading.focus({ preventScroll: true }); heading.scrollIntoView({ block: 'nearest' });
  }, [modelReturnFocus, returnedTask?.id]);
  useCollectionMotion(readingRoot, JSON.stringify([state.webWorkspaceResumeRevision, peerId, query.trim()]),
    JSON.stringify([localExpanded, peerSections.map(section => [section.id, section.peers.map(row => row.deviceId)]), pairings.map(row => [row.candidateId || row.inviteId, row.state]), shownTasks.map(task => [task.id, task.status])]),
    { active: !desktopOpen && !!snapshot });
  const originalWork = snapshot && (!peerId || peer) && tasks.length > 0 ? (
        <section className="pw-device-section pw-device-tasks" aria-label={peer ? "这台设备的原工作" : "设备相关工作"}>
          <header className="pw-device-section-heading"><div><h3>{peer ? '这台设备的原工作' : '设备相关工作'}</h3><p>{peer ? '打开本机保留的原任务，不会重连或恢复执行。' : '本机保存的任务与结果，打开不会重新执行。'}</p></div><span>已读到 {tasks.length} 项</span></header>
          <div className="pw-device-task-list">
            {shownTasks.map(task => {
              const controls = actionsFor(task), request = resumeRequest(task);
              const readable = !unreadSource(task), details = readable ? taskDetails[task.id] : undefined, links = deviceTaskHistoryLinks(details, task.id, peerId);
              const receivedPhone = readable && isReceivedPhoneTask(task, snapshot), showLocalRead = receivedPhone && (!controls.capturedAt || controls.phoneActionReason);
              const readLocalTask = () => { void invoke('devices.readTask', { taskId: task.id, ...(peerId ? { targetDeviceId: peerId } : {}) }, value => {
                const actual = deviceTaskDetails(value, task.id); deviceTaskHistoryPage(value, task.id, peerId, 'taskDetails');
                setTaskDetails(old => ({ ...old, [task.id]: actual }));
              }); };
              const turnHistoryPage = (direction: 'older' | 'newer') => {
                const pageHandle = details?.history.pageHandle;
                if (busy || !pageHandle || selectedPeer.current !== peerId) return;
                void invoke('devices.readTask', { taskId: task.id, historyPageHandle: pageHandle, direction }, value => {
                  const page = deviceTaskHistoryPage(value, task.id, peerId, 'taskHistoryPage');
                  setTaskDetails(old => old[task.id]?.history.pageHandle !== pageHandle ? old : { ...old, [task.id]: { ...old[task.id], history: page } });
                });
              };
              return <article className="pw-device-task" key={task.id} data-motion-key={`task:${task.id}`}>
                <header><h4 dir="auto" tabIndex={receivedPhone ? -1 : undefined} data-received-goal={receivedPhone ? task.id : undefined}>{readable ? task.goal || '原设备任务' : '原工作来源待核对'}</h4><span>{readable ? taskLabels[task.status] || '状态待读取' : '本机仅留关联'}</span></header>
                {readable && task.summary && <p className="pw-device-task-summary" dir="auto">{task.summary}</p>}
                {readable && deviceWorkspaceTaskMode(task.raw) === 'manual_phone' && <p>{task.raw?.runId ? '原手控操作与回执保留；离页后不恢复旧输入权限。' : '手控尚未开始。打开后选择一个手机屏幕，明确审阅，再逐次操作。'}</p>}
                {readable && deviceWorkspaceTaskMode(task.raw) === 'agent_device' && !task.raw?.runId && <p>原目标尚未启动。打开后选择设备范围并审阅开始；取消不会影响其他任务。</p>}
                {readable && controls.phoneActionReason && <p role="status">{controls.phoneActionReason}</p>}
                {receivedPhone && !controls.phoneActionReason && !task.raw?.runId
                  && (available(controls.goalReview) || available(controls.pendingCancel)) && <p>{available(controls.goalReview) ? '这台手机已接收，尚未启动。你可以核对本机模型与范围，再决定是否开始。' : `这台手机已接收，尚未启动。${controls.goalReview?.reason || '目前不能开始。'}`}</p>}
                <div className="pw-form-actions">
                  {showLocalRead && <Button variant="secondary" disabled={busy || !supported('devices.readTask')} onClick={readLocalTask}>读取本机接手状态</Button>}
                  {readable && available(controls.goalReview) && <Button disabled={busy} onClick={() => actIfCurrent(task, 'goalReview', () => { void invoke('devices.reviewGoal', { taskId: task.id }, acceptReview); })}>审阅本机模型与原目标</Button>}
                  {receivedPhone && !task.raw.runId && openModels && available(controls.goalReview) && receivedPhoneGoalReturn(task, snapshot) &&
                    <Button variant="ghost" disabled={busy} onClick={() => openModelSettingsFor(task)}>先选本机 API 模型</Button>}
                  {readable && available(controls.resultReview) && <Button disabled={busy} onClick={() => actIfCurrent(task, 'resultReview', () => { void invoke('devices.reviewResult', { taskId: task.id }, acceptReview); })}>核对成果并结束</Button>}
                  <Button variant="secondary" disabled={busy || !supported('devices.openTask')} onClick={() => {
                    if (selectedPeer.current !== peerId || !deviceTaskRows(state.tasks, snapshot, peerId).some(row => row.id === task.id)) return;
                    void invoke('devices.openTask', { taskId: task.id });
                  }}>打开原工作</Button>
                  {(['stop', 'pendingCancel'] as const).filter(key => !(key === 'stop' && available(controls.pendingCancel))).map(key => {
                    const operation = key === 'pendingCancel' ? 'task.cancelPending' : 'task.stop';
                    return available(controls[key]) && <Button key={key} variant="ghost" disabled={controlBusy} onClick={() => actIfCurrent(task, key, () => actionReview(operation, { taskId: task.id }))}>{deviceOperationLabels[operation]}</Button>;
                  })}
                </div>
                {readable && request?.ownerActionRequired === true && <div className="pw-device-task-request"><h4>对方请求继续原执行</h4><p>请求设备 {request.callerDeviceId}</p>{request.stale === true && <p>任务版本已经变化；需要对方重新读取后发起恢复请求。仍可拒绝这次旧请求。</p>}<Button variant="secondary" disabled={busy} onClick={() => {
                    const fresh = actionsFor(task, Date.now()).resumeRequest;
                    if (!fresh?.ownerActionRequired || fresh.resumeRequestId !== request.resumeRequestId || fresh.reviewRevision !== request.reviewRevision) { setError('原恢复请求已变化，请重新读取。'); return; }
                    void invoke('devices.reviewResumeRequest', { taskId: task.id, resumeRequestId: request.resumeRequestId, reviewRevision: request.reviewRevision }, acceptReview, 'control');
                  }}>核对这次恢复请求</Button></div>}
                {readable && rows(task.raw?.approvals).filter(row => row.state === 'pending').slice(0, 16).map(approval => <div className="pw-device-task-request" key={approval.id || approval.approvalId}><p>{approval.message || approval.tool}</p><Button variant="secondary" disabled={busy || typeof (approval.id || approval.approvalId) !== 'string' || !Number.isSafeInteger(approval.expiresAt) || approval.expiresAt <= Date.now()} onClick={() => { void invoke('devices.reviewApproval', { taskId: task.id, approvalId: approval.id || approval.approvalId }, acceptReview); }}>核对这次原操作</Button></div>)}
                {readable && <Accordion className="pw-device-task-history"><AccordionItem id={`history-${task.id}`} headingLevel={5} title="操作记录与管理" description={details ? `已读取 ${links.length} 项原操作` : undefined}>
                  <div className="pw-form-actions">
                  {(['pause', 'resume'] as const).filter(key => !(key === 'resume' && controls.resumeRequest?.ownerActionRequired === true)).map(key =>
                    available(controls[key]) && <Button key={key} variant="ghost" disabled={busy} onClick={() => actIfCurrent(task, key, () => actionReview(`task.${key}`, { taskId: task.id }))}>{deviceOperationLabels[`task.${key}`]}</Button>)}
                  </div>
                  {!showLocalRead && <Button variant="ghost" disabled={busy} onClick={readLocalTask}>{details ? '从最近的原派发重新读取' : '读取本机的原派发记录'}</Button>}
                  {details && <>
                    {!links.length && <p>{peerId ? '本次读取中没有这台设备的关联操作。' : '本次读取中没有关联的设备操作。'}</p>}
                    {links.map(link => <div className="pw-device-history-entry" key={link.operationId}><p>设备 {link.targetDeviceId} · 原操作 {link.operationId}</p><p>{link.terminalReceipt?.terminal === true && link.terminalReceipt?.stopped === true && link.terminalReceipt?.hasUnresolved === false ? '执行端已有结束回执，业务成果仍待核对。' : String(link.holdState || '').includes('unknown') ? '执行效果仍未确认，原占用次数保留。' : link.childBinding ? '执行端已接受，尚未核对最终结果。' : '已保留这次原操作的关联记录。'}</p><Button variant="secondary" disabled={busy} onClick={() => openHistory(task.id, link)}>查看原派发记录</Button></div>)}
                    {(details.history.hasMore || details.history.canReadNewer) && <><p>只翻阅本机保留的原派发{peerId ? '，已按这台设备筛选' : ''}，不会联系对方或重发任务。</p>
                      <div className="pw-form-actions">{details.history.canReadNewer && <Button variant="ghost" disabled={busy} onClick={() => turnHistoryPage('newer')}>较新的派发</Button>}
                        {details.history.hasMore && <Button variant="secondary" disabled={busy} onClick={() => turnHistoryPage('older')}>更早的派发</Button>}</div></>}
                  </>}
                </AccordionItem></Accordion>}
              </article>;
            })}
          </div>
          {(nextCount > 0 || extraWork > 0) && <div className="pw-form-actions">
            {nextCount > 0 && <Button variant="secondary" onClick={() => setMoreWork({ key: workKey, count: extraWork + nextCount })}>再显示 {nextCount} 项工作</Button>}
            {extraWork > 0 && <Button variant="ghost" onClick={() => setMoreWork({ key: workKey, count: 0 })}>收起更多工作</Button>}
          </div>}
        </section>
  ) : null;
  const localOngoing = snapshot?.lanDiscovery?.active === true || snapshot?.lanDiscovery?.stopPending === true;
  const showLocalTools = localExpanded || localOngoing;

  return <>
    <section ref={readingRoot} className="pw-settings-section pw-devices-reading pw-depth-collaboration" data-device-view={desktopOpen ? 'desktop' : peerId ? 'detail' : 'list'}>
      {!sourceFocus && (desktopOpen || peerId) && <header className="pw-device-heading">
        <div className="pw-device-heading-copy">
          {peerId && !desktopOpen && <Button variant="ghost" className="pw-device-back" leadingIcon={IconArrowLeft} disabled={busy} onClick={() => { selectPeer(''); setForm(''); setRaw(''); }}>所有设备</Button>}
          <h2 tabIndex={-1}>{desktopOpen ? '电脑桌面' : peer ? name(peer) : '设备详情'}</h2>
          {!desktopOpen && peer && <p>{[peerPlatform(peer), deviceConnectionLabel(peer)].filter(Boolean).join(' · ')}</p>}
        </div>
        {!desktopOpen && <div className="pw-device-heading-actions">
          {peerEntry && <Button variant="primary" leadingIcon={peerEntry.icon} disabled={busy} onClick={peerEntry.open}>{peerEntry.label}</Button>}
          <Button variant="ghost" iconOnly leadingIcon={IconRefresh} aria-label="重读这台设备的连接记录" title="重读这台设备的连接记录" disabled={busy} onClick={refresh} />
        </div>}
      </header>}
      {desktopOpen && peer && <DesktopViewer key={peer.deviceId + ':' + peer.instanceId} peer={peer} ownerDeviceId={local.deviceId || ''} now={now}
        partialGrants={peer.hasMoreReceivedGrantScopes === true || snapshot?.hasMoreOutgoingGrants === true || peer.capabilitySnapshot?.metadata?.grantReceiptsTruncated === true}
        supported={supported} run={run} onRefresh={readPeerCapabilities} refreshing={busy} refreshError={error} onClose={() => setDesktopOpen(false)} />}
      {desktopOpen && !peer && <p role="alert">设备状态已变化。<Button variant="secondary" onClick={() => setDesktopOpen(false)}>返回设备</Button></p>}
      {!desktopOpen && <>
        {error && <MotionFeedback identity={error} className="pw-device-feedback" role="alert">{error}</MotionFeedback>}
        {notice && <MotionFeedback identity={notice} className="pw-device-feedback" role="status">{notice}</MotionFeedback>}
        <WorkingStatus active={busy && !form}>正在读取原设备回执…</WorkingStatus>
        {sourceFocus && pinnedConnection && <section className="pw-device-section" aria-label="原工作指定的设备连接">
          <WorkspaceSectionHeading title={focusedPeer?name(focusedPeer):'原工作的执行设备'} description="这里只读取本机记录，不联系设备或启动任务。" />
          {!snapshot || !pinnedReadReady ? <p>等待读取本机当前保存的伙伴身份。</p> : !focusedPeer ? <p role="status">{peers.some(row=>row.deviceId===sourceFocus.deviceId)
            ? '原伙伴已撤销，或者证书或实例已变化，没有打开现在的设备。'
            : snapshot.hasMorePeers?'原身份不在本次本机读取范围。没有改选其他伙伴。':'本机没有匹配这份工作的原伙伴。没有改选其他设备。'}</p>
            : <><p>上次连接检查：{deviceConnectionLabel(focusedPeer)}</p>
                {!deviceChannelWasConnected(focusedPeer) && <p>{focusedPeer.actions?.connect?.reason || '连接需要你单独核对；尚未联系对方。'}</p>}
                {deviceChannelWasConnected(focusedPeer) && <p>连接记录不代表现在可执行。回到这份原工作后再核对设备范围。</p>}
                {!deviceChannelWasConnected(focusedPeer) && available(focusedPeer.actions?.connect) && supported('devices.reviewAction') &&
                  <Button variant="secondary" disabled={busy} onClick={() => { void invoke('devices.reviewAction',{operation:'peer.connect',deviceId:sourceFocus.deviceId},value=>{
                    const next=deviceReview(value), target=next.view?.peer;
                    if(next.kind!=='action' || next.view?.operation!=='peer.connect' || !target || retired(target) ||
                      target.deviceId!==sourceFocus.deviceId || target.instanceId!==sourceFocus.instanceId || target.certificateFingerprint!==sourceFocus.certificateFingerprint){
                      if(next.viewHandle)void run('devices.discard',{viewHandle:next.viewHandle}).catch(()=>{});
                      setError('核对期间原设备身份已变化，没有连接。');return;
                    }
                    acceptReview(value);
                  }); }}>核对并连接这台原设备</Button>}</>}
          <Accordion><AccordionItem id="pinned-connection-identity" title="这份工作固定的身份"><p className="pw-source-copy">原设备 {sourceFocus.deviceId}<br/>原证书 {fingerprint(sourceFocus.certificateFingerprint)}</p></AccordionItem></Accordion>
          <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={refresh}>重读本机连接记录</Button>
            {onReturnToPartnerTask && <Button disabled={busy || !!review} onClick={onReturnToPartnerTask}>返回这份原工作</Button>}</div>
        </section>}
        {sourceFocus && !pinnedConnection && <section className="pw-device-section" aria-label="原任务派单">
          <WorkspaceSectionHeading title="这份工作的原派单" description={focusedPeer?'交给 '+name(focusedPeer)+' · 仅查本机记录':'只核对当时选定的那台手机'} />
          {!snapshot ? <p>尚未读取本机保存的设备身份。</p> : !focusedPeer ? <SavedPhoneDispatches key={JSON.stringify([sourceFocus.taskId,sourceFocus.deviceId,sourceFocus.instanceId,sourceFocus.certificateFingerprint,local.deviceId,local.instanceId])}
              source={sourceFocus} local={local} busy={busy||!local.deviceId||!local.instanceId||!supported('devices.readPhoneGoalArchive')}
              read={(args,accept)=>{void invoke('devices.readPhoneGoalArchive',args,accept)}}/>
            : activeArchive && <>{!rows(activeArchive.rows).length && <p role="status">{activeArchive.hasMore?'这一页没有能核对的原派单，可以继续查看较早记录。':'本机没有找到能核对的原派单，无法据此判断对方是否收到。'}</p>}
              {rows(activeArchive.rows).map(row=><article className="pw-device-history-entry" key={row.operationId}>
                <h4 dir="auto">{row.goal}</h4><p>{time(row.createdAt)} · {row.targetAcceptedAt?('对方已接单 '+time(row.targetAcceptedAt)+(row.targetRunRecorded?'，已记录执行编号':'')):'已保存派单，尚无可核对的对方接单回执'}</p>
                {row.stopReceipt&&<p>{archivedPhoneStopLine(row.stopReceipt,time)}</p>}
                <Button variant="secondary" disabled={busy||!supported('devices.openHistory')} onClick={()=>openArchivedGoal(row,false)}>核对原回执与成果</Button>
              </article>)}
              <p className="pw-device-footnote">接单不代表机主已读或工作已完成。本机回复不是对方成果；读取对方已核对的结果需另行点击。</p>
              <div className="pw-form-actions">{activeArchive.canReadNewer && <Button variant="ghost" disabled={busy} onClick={()=>readPhoneArchive('sent','newer')}>较新派单</Button>}
                {activeArchive.hasMore && <Button variant="secondary" disabled={busy} onClick={()=>readPhoneArchive('sent','older')}>较早派单</Button>}</div>
            </>}
          {(!snapshot||!!focusedPeer)&&<Button variant="ghost" disabled={busy} onClick={()=>focusedPeer&&peerId===sourceFocus.deviceId?readPhoneArchive('sent'):refresh()}>重读本机记录</Button>}
        </section>}
        {!sourceFocus && !snapshot && !busy && <div className="pw-device-empty"><h3>尚未读取设备状态</h3><p>读取后查看已配对设备与连接状态。</p><Button variant="secondary" onClick={refresh}>读取设备状态</Button></div>}
        {!sourceFocus && snapshot && !peerId && <>
          <section className="pw-device-home" aria-label="本机能力">
            <header className="pw-device-home-heading"><span className="pw-device-home-symbol" aria-hidden><IconPhone size={24}/></span>
              <div><h2 data-this-phone-heading tabIndex={-1}>这台手机</h2><p>{receiverLabel}</p></div></header>
            <dl className="pw-device-local-facts">
              <div><dt>主屏读取</dt><dd>{phoneStatus('mainObservation')}</dd></div>
              <div><dt>独立屏准备</dt><dd>{phoneStatus('backgroundPreparation')}</dd></div>
            </dl>
            <Button variant="ghost" className="pw-device-local-toggle" aria-expanded={showLocalTools} aria-controls="pw-device-local-tools"
              disabled={busy || localOngoing} onClick={() => {
                if (showLocalTools) { setDiscoveryAck(false); setAdvertise(false); setDiscoveryPeer(''); }
                setLocalExpanded(!showLocalTools);
              }}>{localOngoing ? '临时查找尚未结束' : showLocalTools ? '收起本机设置' : '本机设置与权限'}</Button>
            <div id="pw-device-local-tools" hidden={!showLocalTools}>
            <Accordion className="pw-device-disclosures pw-device-local-settings">
              <AccordionItem id="phone-preparation" title="主屏与独立屏准备" description="主屏与独立屏分别准备">
                <p>只配置这台手机，不会启动远程任务。配对时还需明确选应用、节点或整屏输入范围。</p>
                <dl className="pw-facts">{[['mainObservation', '主屏读屏'], ['mainExecution', '主屏操作'], ['mainScreenshot', '主屏截图'],
                  ['backgroundPreparation', '独立屏准备'], ['backgroundExecution', '独立屏执行'], ['backgroundInput', '独立屏输入'], ['backgroundCapture', '独立屏截图']].map(([id, title]) =>
                  <div key={id}><dt>{title}</dt><dd>{localScreenDetail(id)}</dd></div>)}</dl>
                <div className="pw-form-actions"><Button variant="secondary" disabled={busy || !supported('settings.openSystem')} onClick={() => openPhoneSetup('accessibility')}>准备主屏访问</Button><Button variant="secondary" disabled={busy || !supported('settings.openSystem')} onClick={() => openPhoneSetup('background')}>准备独立屏连接</Button></div>
                <p>独立屏连接成功不等于已建立显示、输入与截图能力。主屏会影响你正在用的界面；整屏截图与整屏输入分别授权。</p>
              </AccordionItem>
              <AccordionItem id="receiver" title="本机接收服务" description={receiverLabel} icon={IconPhone}>
                <p>本机接收、双端身份与每项授权分别核对。连接状态不代表任务已经停止或完成。</p>
                {local.certificateFingerprint && <p className="pw-source-copy">本机完整指纹：<bdi>{fingerprint(local.certificateFingerprint)}</bdi></p>}
                {local.receiverReason && <p>{local.receiverReason}</p>}
                <Button variant="secondary" disabled={busy || !available(actions[local.receiverEnabled ? 'disable' : 'enable'])} onClick={() => actionReview(local.receiverEnabled ? 'receiver.disable' : 'receiver.enable')}>{local.receiverEnabled ? '核对并停用接收' : '核对并启用接收'}</Button>
                {!available(actions[local.receiverEnabled ? 'disable' : 'enable']) && <p>{actions[local.receiverEnabled ? 'disable' : 'enable']?.reason}</p>}
              </AccordionItem>
              {supported('devices.discovery.start') && <AccordionItem id="lan-discovery" title="临时查找局域网地址" description="60 秒 · 不自动配对或信任">
                <p>在另一台 Android 手机的同一入口启用临时广播，然后查找。广播只公开随机服务名和连接端口，不含设备身份、证书、邀请或权限；同一网络的其他设备也可能看到。电脑端尚未接入这项广播，仍用短期邀请。</p>
                {!snapshot?.lanDiscovery?.active && <>
                  <CheckboxField isSelected={advertise} isDisabled={busy || !snapshot?.local?.listenerReady} onChange={value => { setAdvertise(value); setDiscoveryAck(false); }}>同时临时广播本机接收地址（需先开启设备接收）</CheckboxField>
                  <CheckboxField isSelected={discoveryAck} isDisabled={busy} onChange={setDiscoveryAck}>我同意在本页查找 60 秒{advertise ? '，并向同一局域网临时公开本机服务地址' : '，不广播本机接收服务'}</CheckboxField>
                  <Button disabled={busy || !discoveryAck || snapshot?.lanDiscovery?.stopPending} onClick={() => void invoke('devices.discovery.start', { advertise, acknowledged: true })}>开始本次查找</Button>
                </>}
                {snapshot?.lanDiscovery && <p role="status">{snapshot.lanDiscovery.notice}{snapshot.lanDiscovery.active ? ` 截至 ${time(snapshot.lanDiscovery.expiresAt)}。` : ''}{snapshot.lanDiscovery.advertising ? ' 系统仍报告本机服务广播已登记。' : ''}{snapshot.lanDiscovery.stopPending ? ' 已禁用本页候选，系统停止回执仍待确认。' : ''}</p>}
                {snapshot?.lanDiscovery?.active && <>
                  <p>下列是未认证的地址候选，不是在线设备。先选它应当对应的原配对身份，再核对地址；实际连接仍须通过原证书双向 TLS。未配对设备仍需交换短期邀请。</p>
                  <SelectField label="应当连接的原配对设备" value={discoveryPeer} onChange={setDiscoveryPeer} isDisabled={busy} options={[{ id: '', label: '明确选择一台已配对设备' }, ...peers.filter(row => !row.revoked).map(row => ({ id: row.deviceId, label: `${name(row)} · ${String(row.deviceId).slice(-8)}` }))]} />
                  {rows(snapshot.lanDiscovery.candidates).map(candidate => <div className="pw-source-record" key={candidate.candidateId}><p>{candidate.host} · 控制 {candidate.controlPort} · 成果 {candidate.bulkPort} · 未认证</p><Button variant="secondary" disabled={busy || !discoveryPeer} onClick={() => void invoke('devices.discovery.reviewConnection', { deviceId: discoveryPeer, candidateId: candidate.candidateId }, acceptReview)}>核对这个地址与原身份</Button></div>)}
                  {!rows(snapshot.lanDiscovery.candidates).length && <p>暂未发现可用地址。确认另一台手机已开启本页广播；访客 Wi-Fi、客户端隔离、VPN 或系统限制可能阻止发现。空列表不代表设备不存在。</p>}
                  {snapshot.lanDiscovery.skipped > 0 && <p>部分服务未能解析或不符合有限发现范围，未纳入候选。</p>}
                  <Button variant="ghost" disabled={busy} onClick={() => void invoke('devices.discovery.stop')}>停止查找与本机广播</Button>
                </>}
                <p>离开本页、进入后台或到期即禁用候选并请求系统撤回发现与广播；IPv6 链路本地地址暂不接入，仍可使用原邀请中的可路由地址。查找和广播不启用接收、不改变权限，也不启动任务。</p>
              </AccordionItem>}
              {(rows(snapshot.pairingOffersToRecover).length > 0 || rows(snapshot.committedPairingsToRecover).length > 0) && <AccordionItem id="recover" title="核对已有配对结果" description="找回已提交的结果，不重新授权">
                <p>只读取双方已确认的原结果，不重做配对、不延长旧权限。</p>
                {rows(snapshot.pairingOffersToRecover).map(row => <div className="pw-device-history-entry" key={row.inviteId}><p>原邀请 {row.inviteId}</p><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => openRecovery(row, false)}>读取已提交结果</Button>{row.abandonAvailable === true && <Button variant="ghost" disabled={busy} onClick={() => { void invoke('devices.reviewAbandonment', { inviteId: row.inviteId }, acceptReview); }}>核对并放弃本机待配对</Button>}</div></div>)}
                {rows(snapshot.committedPairingsToRecover).map(row => <div className="pw-device-history-entry" key={row.inviteId}><p>为原设备 {row.peerDeviceId} 提供已提交结果</p><Button variant="secondary" disabled={busy || row.recoveryState !== 'available'} onClick={() => openRecovery(row, true)}>开启原结果只读窗口</Button></div>)}
              </AccordionItem>}
            </Accordion>
            </div>
          </section>
          {pairings.length > 0 && <section className="pw-device-section" aria-label="这次配对的进展与回执">
            <WorkspaceSectionHeading title={pendingPairs.length ? '配对进展' : '原配对回执'} description={`${pairings.length} 项`} />
            <div className="pw-device-attention">
              {pairings.map(candidate => {
                const id = candidate.candidateId || candidate.inviteId, advance = candidate.advanceAvailable === true;
                const ended = ownerWindowEnded(candidate, now), known = peers.some(row => row.deviceId === candidate.joinerDeviceId);
                const other = local.deviceId && (local.deviceId === candidate.inviterDeviceId ? candidate.joinerDeviceId : local.deviceId === candidate.joinerDeviceId ? candidate.inviterDeviceId : '');
                return <article className="pw-device-pairing" key={id} data-motion-key={`pairing:${id}`}>
                  <div><h4>{candidate.label || (typeof other === 'string' && other ? `设备 ${other.slice(-4)}` : '原设备配对')}</h4>
                    <p>{originalPairProgress(candidate, now)}</p></div>
                  {advance ? <Button variant="secondary" disabled={busy || !id || !supported('devices.advancePairing')} onClick={() => { void invoke('devices.advancePairing', { candidateId: id }, (value, actual) => {
                    if (value.queriedOriginalPairing !== true) throw new Error('尚未收到这次原配对的回执，请重读本机记录。');
                    reportPair(candidate, actual);
                  }); }}>继续原配对</Button> : ended ? (!known && <Button variant="secondary" disabled={busy || !supported('devices.createInvitation')} onClick={() => openPairEntry('invite')}>准备新邀请</Button>) : candidate.reviewAvailable === true ? <Button variant="secondary" disabled={busy || !id || !supported('devices.reviewPairing')} onClick={() => { void invoke('devices.reviewPairing', { candidateId: id }, acceptReview); }}>核对权限与身份</Button> : null}
                </article>;
              })}
            </div>
          </section>}
          <section className="pw-device-section" aria-label="设备配对">
            <WorkspaceSectionHeading title="配对伙伴"
              description={trusted.length ? `${snapshot.hasMorePeers ? '本页' : '已配对'} ${trusted.length} 台 · 按上次检查分组` : snapshot.hasMorePeers ? '当前读取页没有有效配对' : peers.length ? '目前没有有效配对' : '尚未配对其他设备'}
              actions={<>
                <Button variant="ghost" iconOnly leadingIcon={IconRefresh} aria-label="刷新设备状态" title="刷新设备状态" disabled={busy} onClick={refresh} />
                <Button variant={pendingPairs.length || trusted.length ? 'secondary' : 'primary'} leadingIcon={IconAdd} disabled={busy} onClick={() => openPairEntry('add')}>配对设备</Button>
              </>} />
            {!trusted.length && !snapshot.hasMorePeers && <p className="pw-device-pair-note">{pendingPairs.length ? '已有配对等待核对，无需重新发起。' : '已有对方的邀请可直接加入；从这台手机发邀请才需要先开本机接收。'}</p>}
            {(peers.length > 5 || query) && <SearchField className="pw-device-search" fieldClassName="pw-collaboration-search" shape="rounded" aria-label="搜索已配对设备" placeholder="搜索名称或设备" value={query} onChange={setQuery} />}
            {peers.length ? <div className="pw-device-directory">{peerSections.filter(section => section.peers.length > 0).map(section => <section className="pw-collaboration-group" key={section.id} aria-label={section.title}><header className="pw-collaboration-group-heading"><h3>{section.title}</h3><span>{section.peers.length} 台</span></header><div className="pw-device-list">
              {section.peers.map(row => <Button key={row.deviceId} data-peer-entry={row.deviceId} data-motion-key={`peer:${row.deviceId}`} variant="ghost" className="pw-device-row" leadingIcon={platformIcon(row)} trailingIcon={IconChevronRight} disabled={busy} onClick={() => { rememberReadingPosition('list'); selectPeer(row.deviceId); }}>
                <span className="pw-device-row-copy"><span className="pw-device-row-name" dir="auto">{name(row)}</span><span className="pw-device-row-status">{deviceConnectionLabel(row)}</span><small>{peerPlatform(row) || (retired(row) ? '原设备' : '配对设备')} · {row.connection?.observedAt > 0 ? time(row.connection.observedAt) : '尚无检查时间'}</small></span>
              </Button>)}
            </div></section>)}{query && !visiblePeers.length && <div className="pw-device-empty"><h3>没有匹配的设备</h3><p>换个名称搜索，或查看全部已配对设备。</p><Button variant="secondary" onClick={() => setQuery('')}>清除搜索</Button></div>}</div> : null}
          </section>
          {supported('devices.readWaitingPhoneGoals') && local.deviceId && <section className="pw-device-section" aria-label="其他设备交来的原工作">
            <WorkspaceSectionHeading title="交给本机的工作" description="查看本机接纳记录，不联系发送设备" actions={<>
              <Button variant={waitingPage && !waitingPage.resultOnly && !waitingPage.startedOnly && !waitingPage.declinedOnly && !waitingPage.withdrawnOnly && !waitingPage.failedOnly ? 'primary' : 'secondary'} disabled={busy} onClick={() => readWaiting()}>待接手</Button>
              <Button variant={waitingPage?.startedOnly || waitingPage?.failedOnly ? 'primary' : 'secondary'} disabled={busy} onClick={() => readWaiting(undefined,'started')}>已接手</Button>
              <Button variant={waitingPage?.resultOnly ? 'primary' : 'secondary'} disabled={busy} onClick={() => readWaiting(undefined,'results')}>待核对成果</Button>
              {supported('devices.readReviewedPhoneInbox')&&<Button variant={localInbox?'primary':'secondary'} disabled={busy} onClick={()=>readClosedInbox()}>核对过的工作</Button>}
            </>} />
            {localInbox&&<><form className="pw-form-actions" onSubmit={e=>{e.preventDefault();if(closedDraft.trim())readClosedInbox(undefined,closedDraft);}}>
              <SearchField aria-label="在本机原目标和已确认的结果中查找" placeholder="查找原目标或已确认的文字" value={closedDraft} isDisabled={busy}
                onChange={value=>setClosedDraft(Array.from(value).slice(0,64).join(''))}/>
              <Button type="submit" variant="secondary" disabled={busy||!closedDraft.trim()}>查找</Button>
              {localInbox.searchText&&<Button type="button" variant="ghost" disabled={busy} onClick={()=>readClosedInbox()}>显示全部</Button>}</form>
              <p role="status">{localInbox.searchText?'仅在本机原目标和已确认的结果中查找「'+localInbox.searchText+'」；不列出结果正文。':'这是这部手机自己核对过的文字工作；原发起设备不需要在线。未确认完成的工作只列本机决定，不提供结果正文。'}</p>
              {localInbox.limitedCandidates>0&&<p>另有 {localInbox.limitedCandidates} 项已结束记录无法核对，未列作已核对原文。</p>}
              {!localInbox.rows.length&&<p>{localInbox.searchText?'这段已核对的记录没有匹配。':'这一页没有可核对的本机原件。'}{localInbox.hasMore&&'可以继续查看较早记录。'}</p>}
              {rows(localInbox.rows).map(row=><article className="pw-device-history-entry" key={JSON.stringify([row.taskId,row.operationId])}><h4 dir="auto">{row.goal}</h4>
                <p>{time(row.reviewedAt)} 核对 · 原发起设备 …{String(row.sourceDeviceId).slice(-8)} · {row.notConfirmed?'本机已结束，但没有确认完成':row.keptOnPhone?'只回状态':'允许读取摘要'}{!row.notConfirmed&&!row.hasText&&' · 原结果无文字'}</p>
                {localInbox.searchText&&<p>{row.notConfirmed?'匹配原目标，未检索结果正文':row.bothParts?'原目标与原结果均匹配':row.matchPart==='result'?'匹配原结果':'匹配原目标'}</p>}
                {!row.notConfirmed&&<Button variant="secondary" disabled={busy||!supported('devices.readLocalReviewedPhoneGoal')} onClick={()=>readOwnText(row,0,undefined,true,'result',!!localInbox.searchText)}>
                  {localInbox.searchText?'打开匹配段附近':row.hasText?'重看本机原文':'查看当时的核对'}</Button>}</article>)}
              <div className="pw-form-actions">{localInbox.canReadNewer&&<Button variant="ghost" disabled={busy} onClick={()=>readClosedInbox('newer')}>较新记录</Button>}
                {localInbox.hasMore&&<Button variant="secondary" disabled={busy} onClick={()=>readClosedInbox('older')}>{localInbox.searchText?'查找较早记录':'较早记录'}</Button>}</div></>}
            {waitingPage && <><p role="status">{time(waitingPage.capturedAt)} 读取。{waitingPage.withdrawnOnly?'只列原发起设备的撤回已在本机登记、而且原工作未开始的记录。不是断网、机主不接手或运行中的停止请求。':waitingPage.failedOnly?'只列本机原任务和同一轮执行都登记为失败的记录，按原接纳时间排列；不读取对方状态或成果。':waitingPage.declinedOnly?'这里只列本机明确选择不接手、且凭原记录核对为从未启动的目标。按原接纳时间排列，不是对方已读回执。':waitingPage.startedOnly?'这是本机原执行留下的状态，不代表此刻仍在运行或一定能恢复。':waitingPage.resultOnly ? '以下原执行已结束，需机主核对；此处不显示或交回摘要。' : '接纳不等于已同意使用模型；过期的目标只能按原记录处理。'}</p>
              {waitingPage.resultOnly && waitingPage.limitedCandidates > 0 && <p>另有 {waitingPage.limitedCandidates} 项旧记录超过本次读取上限，无法判断是否待核对。可从对应设备的「它交给我的」单项查看。</p>}
              {waitingPage.withdrawnOnly&&waitingPage.limitedCandidates>0&&<p>另有 {waitingPage.limitedCandidates} 条候选记录不能核对为启动前撤回，未列入。</p>}
              {waitingPage.declinedOnly&&waitingPage.limitedCandidates>0&&<p>另有 {waitingPage.limitedCandidates} 条候选不能核对为本机决定，未列入。</p>}
              {(waitingPage.startedOnly||waitingPage.failedOnly)&&waitingPage.limitedCandidates>0&&<p>另有 {waitingPage.limitedCandidates} 项旧记录缺少可核对的执行信息，未列入。</p>}
              {!waitingPage.rows.length && <p>这一批没有可核对的{waitingPage.withdrawnOnly?'启动前撤回记录':waitingPage.failedOnly?'本机失败原执行':waitingPage.declinedOnly?'本机不接手记录':waitingPage.startedOnly?'正在处理、暂停或中断的原执行':waitingPage.resultOnly ? '待审成果' : '待接手目标'}。{waitingPage.hasMore && '可以再查较早记录。'}</p>}
              {rows(waitingPage.rows).map(row => {const blocked=waitingBlock?.waitingHandle===waitingPage.waitingHandle&&waitingBlock?.taskId===row.taskId&&waitingBlock?.operationId===row.operationId?waitingBlock:null;
                return <div className="pw-device-history-entry" key={JSON.stringify([row.taskId,row.operationId])}>
                <h4 dir="auto">{row.goal}</h4><p>来自 {sourceLabel(row.sourceDeviceId,row.sourceIdentity)} · {waitingPage.failedOnly?'最后登记失败 '+time(row.stateRecordedAt):waitingPage.declinedOnly||waitingPage.withdrawnOnly?'本机接纳 '+time(row.createdAt):waitingPage.startedOnly?'本机登记执行 '+time(row.registeredAt):waitingPage.resultOnly ? '产生结果 ' + time(row.reviewRecordedAt) : time(row.createdAt)}{row.waitingState === 'interrupted_before_start' && ' · 本机已中断，未启动'}</p>
                {waitingPage.failedOnly&&<p>本机登记执行 {time(row.registeredAt)} · 第 {row.epoch} 轮；不代表成果已交回。</p>}
                {waitingPage.withdrawnOnly&&<p>{time(row.withdrawnAt)} 原发起设备已撤回 · 本机未启动</p>}
                {waitingPage.declinedOnly&&<p>{time(row.declinedAt)} 本机决定不接手 · 从未启动</p>}
                {waitingPage.startedOnly&&<p>任务记录：{taskLabels[row.taskState]}{row.taskState!==row.runState&&' · 执行记录：'+taskLabels[row.runState]}{row.stopRequested&&' · 已请求停止，非停止回执'}</p>}
                {blocked && <p role="status">{({original_not_retained:'本机保存了原派单，但已没有当时的接手授权；不会从记录中重建执行。',
                  expired:'原派单已到期，不能再启动。',interrupted_before_start:'上次接手在开始前中断，没有原执行可恢复。',original_conditions_unavailable:'无法再次核对原授权和本机条件；本次没有启动。'} as Record<string,string>)[blocked.state] || blocked.reason || '本机未建立模型审阅。请查看原工作与本机模型，尚未开始执行。'}</p>}
                {!waitingPage.declinedOnly&&!waitingPage.withdrawnOnly&&<div className="pw-form-actions">{!waitingPage.resultOnly && !waitingPage.startedOnly && !waitingPage.failedOnly && <Button disabled={busy||!supported('devices.reviewGoal')} onClick={()=>reviewWaiting(row)}>{blocked?'重新核对接手条件':'核对本机接手'}</Button>}
                  {blocked?.canCheckLocalModel===true && openModels && supported('devices.prepareReceivedModelReturn')&&<Button variant="secondary" disabled={busy}
                    onClick={()=>chooseModelForReceived({waitingHandle:waitingPage.waitingHandle,taskId:row.taskId,operationId:row.operationId},row,row.sourceDeviceId)}>先检查本机模型</Button>}
                  {!waitingPage.resultOnly&&!waitingPage.startedOnly&&!waitingPage.failedOnly&&(!blocked||blocked.canRetire===true)&&<Button variant="ghost" disabled={busy||!supported('devices.reviewAction')}
                    onClick={()=>actionReview('task.cancelPending',{waitingHandle:waitingPage.waitingHandle,taskId:row.taskId,operationId:row.operationId})}>不接手</Button>}
                  {waitingPage.resultOnly && <Button disabled={busy || !supported('devices.reviewResult')} onClick={() => {
                  void invoke('devices.reviewResult', { waitingHandle: waitingPage.waitingHandle, taskId: row.taskId, operationId: row.operationId }, acceptReview);
                }}>核对这份成果</Button>}
                  <Button variant="secondary" disabled={busy || !supported('devices.returnToReceivedPhoneGoal')} onClick={() => returnWaiting(row)}>{waitingPage.failedOnly?'查看这次失败的原工作':waitingPage.startedOnly?'回到这次原执行':'查看原工作'}</Button></div>}
              </div>})}
              <div className="pw-form-actions">{(waitingPage.startedOnly||waitingPage.failedOnly)&&<Button variant="ghost" disabled={busy}
                onClick={()=>readWaiting(undefined,waitingPage.failedOnly?'started':'failed')}>{waitingPage.failedOnly?'回到处理中的工作':'查看执行失败的记录'}</Button>}
                {!waitingPage.failedOnly&&!waitingPage.startedOnly&&!waitingPage.resultOnly&&<>
                  {(waitingPage.declinedOnly||waitingPage.withdrawnOnly)&&<Button variant="ghost" disabled={busy} onClick={()=>readWaiting()}>回到待接手</Button>}
                  {!waitingPage.declinedOnly&&<Button variant="ghost" disabled={busy} onClick={()=>readWaiting(undefined,'declined')}>本机不接手的记录</Button>}
                  {!waitingPage.withdrawnOnly&&<Button variant="ghost" disabled={busy} onClick={()=>readWaiting(undefined,'withdrawn')}>发起设备撤回的记录</Button>}</>}
                {waitingPage.canReadNewer && <Button variant="ghost" disabled={busy} onClick={() => readWaiting('newer')}>较新记录</Button>}
                {waitingPage.hasMore && <Button variant="secondary" disabled={busy} onClick={() => readWaiting('older')}>查较早记录</Button>}</div>
            </>}
          </section>}
          {originalWork}
          {(trusted.length > 0 || snapshot.hasMorePeers) && supported('deviceTaskScope.prepare') && <section className="pw-device-section" aria-label="准备新的设备工作">
            <WorkspaceSectionHeading title="准备新工作" actions={<>
              <Button variant="secondary" disabled={busy} onClick={() => { setWorkPhone(undefined); setForm('work'); }}>准备交给助手</Button>
              {supported('phoneControl.prepare') && <Button variant="ghost" disabled={busy} onClick={() => { setWorkPhone(undefined); setForm('manual'); }}>自己控制手机</Button>}
            </>} />
          </section>}
        </>}
        {!sourceFocus && peerId && !peer && snapshot && <p className="pw-device-feedback" role="alert">这台设备不在当前读取页。保留原身份，请返回后刷新核对。</p>}
        {!sourceFocus && peer && <>
          <section className="pw-device-connection pw-device-observation" aria-label="这台设备上次的连接检查">
            <MotionFeedback identity={JSON.stringify([peer.deviceId, peer.connection?.state, peer.revoked])} initial={false} className="pw-device-connection-heading" role="status">
              <PeerIcon size={20} aria-hidden/>
              <div><span className="pw-device-observation-label">上次连接检查</span><h3>{deviceConnectionLabel(peer)}</h3></div>
            </MotionFeedback>
            <p><IconClock size={14} aria-hidden/> {time(peer.connection?.observedAt)}</p>
            {peer.connection?.unconfirmedTransportWrite === true && <p className="pw-device-inline-notice">有操作结果待核对，请查看原工作记录。</p>}
            {retired(peer) ? <p>{peer.revocation?.peerAcknowledged === true ? '本机信任已撤销，对端已回执。' : '本机信任已撤销或连接已被撤销，请核对原记录。'}</p>
              : peer.connection?.state === 'DEGRADED' ? <p>上次通道不稳定。打开本机记录不需要重新配对；读取对端最新结果需重新核对。</p>
              : !deviceChannelWasConnected(peer) ? <p>未接通不等于工作停止。先查看原记录，需要对端时再核对连接。</p>
              : <p>当时通道已接通；画面、输入和本次执行范围另行核对。</p>}
            {!retired(peer) && windowsObservation && <p className="pw-field-hint">{windowsObservation}</p>}
            {!retired(peer) && <><dl className="pw-device-scope-directions">
              <div><dt>对方给本机</dt><dd>{received?.text}</dd></div>
              <div><dt>本机给对方</dt><dd>{given?.text}</dd></div>
            </dl><p>这是已读授权，不是执行回执。准确应用、工具和额度见「能力与两端权限」。</p></>}
            {phoneAccess && <div><h4>本机可用的手机工具</h4>
              {phoneAccess.note && <p>{phoneAccess.note}</p>}
              {phoneAccess.entries.map(entry => <div key={entry.key}><h4>{entry.title}{entry.packages && ' · ' + entry.packages}</h4>
                <dl className="pw-facts"><div><dt>读屏节点</dt><dd>{entry.read}</dd></div>
                  <div><dt>操作</dt><dd>{entry.control}</dd></div><div><dt>整屏截图</dt><dd>{entry.capture}</dd></div></dl>
                {entry.next && <p>{entry.next}</p>}</div>)}
              {phoneAccess.entries.length > 0 && <p>这是上次核对的条件，不是已经看见或操作了画面。工作开始前仍会核对当前范围。</p>}
              {deviceChannelWasConnected(peer) && <Button variant="secondary" disabled={busy || !supported('devices.snapshot')} onClick={readPeerCapabilities}>核对这台手机的能力记录</Button>}
            </div>}
          </section>
          {originalWork || <section className="pw-device-no-work"><h3>这台设备的工作</h3><p>本机暂未读到关联的原工作。</p></section>}
          <Accordion className="pw-device-disclosures">
            {((!desktopPeer || phonePeer) && supported('devices.readPhoneGoalArchive') || supported('devices.readReceivedPhoneGoals')) && <AccordionItem id="phone-goal-archive" title={desktopPeer && !phonePeer ? '这台电脑交给本机的工作' : '找回往来目标'} description="查本机留下的记录，无需对方在线" icon={IconClock}>
              <div className="pw-form-actions" role="group" aria-label="读取方向">
                {(!desktopPeer || phonePeer) && supported('devices.readPhoneGoalArchive') && <Button variant={activeArchive && archiveSide === 'sent' ? 'secondary' : 'ghost'} aria-pressed={!!activeArchive && archiveSide === 'sent'} disabled={busy} onClick={() => readPhoneArchive('sent')}>我交给它的</Button>}
                {supported('devices.readReceivedPhoneGoals') && <Button variant={activeArchive && archiveSide === 'received' ? 'secondary' : 'ghost'} aria-pressed={!!activeArchive && archiveSide === 'received'} disabled={busy} onClick={() => readPhoneArchive('received')}>{desktopPeer && !phonePeer ? '读取本机接纳的电脑派工' : '它交给我的'}</Button>}
              </div>
              {activeArchive && <>
                <p role="status">{archiveSide === 'received' ? '这里只列本机从这台伙伴当前身份收到的目标，不代表已同意开始。换证或换实例前的来单不混入；找回原工作不会重启。' : '这里只列本机为这部手机保留的目标，不代表已经送达或完成。打开原派发后，由你决定是否读取回执或成果。'}</p>
                <form className="pw-form-actions" onSubmit={e=>{e.preventDefault();if(!busy && archiveDraft.trim())readPhoneArchive(archiveSide,undefined,archiveDraft)}}>
                  <SearchField aria-label="查找已保存的原目标" placeholder="输入原目标里的词" value={archiveDraft} isDisabled={busy}
                    onChange={value=>setArchiveFind({key:findKey,text:Array.from(value).slice(0,64).join('')})} />
                  <Button type="submit" variant="secondary" disabled={busy||!archiveDraft.trim()}>查找</Button>
                  {activeArchive.searchText && <Button type="button" variant="ghost" disabled={busy} onClick={()=>readPhoneArchive(archiveSide)}>显示全部</Button>}
                </form>
                {archiveSide==='sent' && activeArchive.oldTargetIdentityChanged===true && <p>这台设备曾更换配对身份。不能核对目标的旧派单不归入这里，仍可从当时的本机原任务查找。</p>}
                {activeArchive.searchText && <p role="status">只在这部设备的{archiveSide==='received'?'本机已接收':'本机已发出'}原目标中查找「{activeArchive.searchText}」，不会查询对端或成果。</p>}
                {!activeArchive.rows.length && <p>{activeArchive.searchText ? activeArchive.hasMore ? '这段记录没有命中，可以继续查更早的记录。' : '这段可核对的记录没有命中。' : '这一批本机记录里没有能核对的直接手机目标。'}</p>}
                {rows(activeArchive.rows).map(row => <div className="pw-device-history-entry" key={JSON.stringify([row.taskId, row.operationId])}>
                  <h4 dir="auto">{row.goal}</h4>{row.matchedLater===true && <p>关键词出现在原目标的后文。</p>}<p>{time(row.createdAt)} · {row.textOnly === true ? '仅文字，不使用屏幕' : row.screenMode === 'background' ? '独立后台屏' : '主屏'}</p>
                  <div className="pw-form-actions">{archiveSide === 'received'
                    ? <><Button variant="secondary" disabled={busy || !supported('devices.returnToReceivedPhoneGoal')} onClick={() => openArchivedGoal(row, false)}>找回本机接纳的原工作</Button>
                      {row.mayCheckLocalText===true&&supported('devices.readLocalReviewedPhoneGoal')&&<Button variant="ghost" disabled={busy} onClick={()=>readOwnText(row)}>重看本机已核对原文</Button>}</>
                    : <><Button variant="secondary" disabled={busy} onClick={() => openArchivedGoal(row, false)}>核对这次原派发</Button>
                      <Button variant="ghost" disabled={busy || !supported('devices.openTask')} onClick={() => openArchivedGoal(row, true)}>打开本机原工作</Button></>}</div>
                </div>)}
                <div className="pw-form-actions">{activeArchive.canReadNewer && <Button variant="ghost" disabled={busy} onClick={() => readPhoneArchive(archiveSide, 'newer')}>较新记录</Button>}
                  {activeArchive.hasMore && <Button variant="secondary" disabled={busy} onClick={() => readPhoneArchive(archiveSide, 'older')}>{activeArchive.searchText?'查更早的记录':'更早记录'}</Button>}</div>
              </>}
            </AccordionItem>}
            {!retired(peer) && (desktopPeer || phonePeer || supported('deviceTaskScope.prepare')) && <AccordionItem id="peer-work-options" title="新工作与手动查看" description="新目标要重新选择设备和范围，不沿用原工作的权限" icon={IconLayers}>
              {!deviceChannelWasConnected(peer) && <p>准备目标不代表对端已接收。实际执行仍需核对原设备连接和本次授权。</p>}
              <div className="pw-form-actions">
              {desktopPeer && !identityOnlyHere && peerEntry?.kind !== 'desktop' && <Button disabled={busy} leadingIcon={IconMonitor} onClick={openDesktopPreparation}>核对并选择桌面范围</Button>}
              {phonePeer && !desktopPeer && !identityOnlyHere && !retired(peer) && supported('phoneControl.prepare') && <Button disabled={busy} onClick={() => openDirectedPhone('manual')}>手动查看这部手机的屏幕范围</Button>}
              {!retired(peer) && !identityOnlyHere && (desktopPeer || phonePeer) && supported('deviceTaskScope.prepare') && peerEntry?.kind !== 'goal' &&
                <Button variant="secondary" disabled={busy} onClick={desktopPeer ? prepareWindowsWorkspace : prepareDeviceGoal}>{desktopPeer ? '准备读取这台电脑的工作区' : '为这部手机准备工作'}</Button>}
              {!retired(peer) && (identityOnlyHere || !desktopPeer && !phonePeer) && <p>{identityOnlyHere
                ? '对方上次只公开身份。这里不创建交办任务，也不会改用其他设备。'
                : '尚未核对到能锁定这台伙伴的工作方式。如果不指定设备，可从设备总览另行准备目标。'}</p>}
              </div>
              {desktopPeer && <p className="pw-field-hint">准备页只核对电脑与观看范围，不打开画面，不申请控制。</p>}
              {phonePeer && <div><p>写目标后，选择主屏或独立后台屏及准确应用范围，再审阅开始。“完成目标”由模型操作；“手动控制”由你刷新静态画面后逐次点击、滑动或输入，不是实时视频镜像。</p>
            {phoneCapabilities.filter(cap => cap.id === 'agent.goal').map(cap => <p key={cap.id}>交给对端模型处理目标：{capabilityCaveat || (cap.ready === true ? '上次报告可接受目标，具体范围另审' : cap.canSubmitForLocalReview === true ? '可交给对方审阅，尚未开始' : '对端未报告可接受目标')}</p>)}</div>}
            </AccordionItem>}
            <AccordionItem id="network-help" title="换了网络或连接不上" description="先核对控制通道，再核对画面">
              <dl className="pw-facts">
                <div><dt>检查时间</dt><dd>{time(peer.connection?.observedAt)}</dd></div>
              {deviceChannelWasConnected(peer) && <div><dt>当时的控制通道</dt><dd>{peer.connection?.path === 'RELAY_WSS' ? '临时 WSS 中继' : peer.connection?.path === 'DIRECT_TLS' ? '直连 mTLS' : '路径待核对'}</dd></div>}
              {desktopPeer && <div><dt>桌面画面</dt><dd>需在桌面页单独连接，控制通道在线不代表能看到画面。</dd></div>}
            </dl>
              <div className="pw-form-actions">
              {!retired(peer) && available(peer.actions?.connect) && <Button disabled={busy} onClick={() => actionReview('peer.connect', { deviceId: peer.deviceId })}>核对并连接</Button>}
              {available(peer.actions?.disconnect) && <Button variant="ghost" disabled={busy} onClick={() => actionReview('peer.disconnect', { deviceId: peer.deviceId })}>停止主动连接</Button>}
              </div>
              <p>同一局域网：对端保持接收服务开启，使用它当前显示的数字地址与短期邀请。换网不创建新身份；用原设备的邀请更新地址后重新连接。</p>
              <p>离开局域网：已有临时 WSS 租约只连控制通道，不包含桌面视频。桌面默认依赖双方可直达网络；TURN 只可能转发媒体，不能代替已认证控制通道。</p>
              <p>断开只关闭连接，不代表远端任务停止。先查看原任务与操作回执；未确认的写入不重放。</p>
              {!retired(peer) && <Button variant="secondary" disabled={busy} onClick={() => { setForm('connection'); setRaw(''); setAddress(''); }}>核对原身份并更新连接方式</Button>}
            </AccordionItem>
            <AccordionItem id="capabilities" title="能力与两端权限" description="查看范围，调整本机授予的权限" icon={IconShieldCheck}>
              {!retired(peer) && <>{!phoneAccess && <Button variant="secondary" disabled={busy || !supported('devices.snapshot')} onClick={readPeerCapabilities}>向这台设备更新能力记录</Button>}{!deviceChannelWasConnected(peer) && <p>上次检查未接通。更新时会核对实际通道，不会自动重连。</p>}</>}
              <p>上次能力记录：{time(peer.capabilitySnapshot?.capturedAt)}</p>
              {peer.capabilitySnapshot?.metadata?.disclosureScope === 'pair_identity_only' &&
                peer.capabilitySnapshot.deviceId === peer.deviceId && peer.capabilitySnapshot.instanceId === peer.instanceId &&
                peer.capabilitySnapshot.capabilities?.length === 0 &&
                <p>上次对方只公开了设备身份和这一份授权回执，未公布工具或忙闲。不代表对方离线。</p>}
              {rows(peer.capabilitySnapshot?.capabilities).filter(cap => !phoneAccess || !['phone.tools.main', 'phone.tools.background'].includes(cap.id)).map(cap => <p key={cap.id}>{cap.id} · {capabilityCaveat || (cap.ready === true && cap.authorized === true ? '上次核对时可用' : cap.reason || '上次未报告就绪')}</p>)}
              <h4>允许这台设备在本机使用</h4>{rows(peer.grantScopes).map((scope, index) => <GrantScope key={index} scope={scope} />)}
              <h4>本机可在对端使用</h4>{rows(peer.receivedGrantScopes).map((scope, index) => <GrantScope key={index} scope={scope} />)}
              {!retired(peer) && supported('devices.readSavedPeerScope') && <SavedPeerScopeReceipt
                key={JSON.stringify([local.deviceId,local.instanceId,peer.deviceId,peer.instanceId,peer.certificateFingerprint,peer.authorizationEpoch])}
                here={local} peer={peer} busy={busy} read={done => { void invoke('devices.readSavedPeerScope', {
                  deviceId:peer.deviceId,instanceId:peer.instanceId,fingerprint:peer.certificateFingerprint
                }, value => done(value.savedPeerScope)); }} />}
              {peer.hasMoreGrantScopes || peer.hasMoreReceivedGrantScopes || snapshot?.hasMoreOutgoingGrants ? <p>本次授权记录有读取上限，未列出不代表没有。</p> : null}
              {!peer.revoked && <Button variant="secondary" disabled={busy} onClick={() => { void invoke('devices.reviewPeerGrant', { deviceId: peer.deviceId }, acceptReview); }}>调整本机授予的权限</Button>}
            </AccordionItem>
            <AccordionItem id="identity" title="身份与连接设置" description="完整指纹、连接地址与信任管理">
              {supported('devices.changeLocalPeerName') && (!retired(peer) || !!peer.ownerLocalName) && <LocalPeerNameEditor
                key={JSON.stringify([local.deviceId,local.instanceId,peer.deviceId,peer.instanceId,peer.certificateFingerprint])}
                current={peer.ownerLocalName || ''} busy={busy} retired={retired(peer)} save={(expected,next,done) => {
                  void invoke('devices.changeLocalPeerName',{localDeviceId:local.deviceId,localInstanceId:local.instanceId,localFingerprint:local.certificateFingerprint,deviceId:peer.deviceId,instanceId:peer.instanceId,fingerprint:peer.certificateFingerprint,expected,name:next},(value,actual) => {
                    if(value.localPeerNameSaved===true && value.deviceId===peer.deviceId && value.instanceId===peer.instanceId &&
                      rows(actual?.peers).some(item=>item.deviceId===peer.deviceId && item.instanceId===peer.instanceId && item.certificateFingerprint===peer.certificateFingerprint && (item.ownerLocalName || '')===next)) done();
                  });
                }} />}

              <p className="pw-source-copy">设备 {peer.deviceId} · 授权版本 {peer.authorizationEpoch}</p>
              <p className="pw-source-copy">完整证书指纹：{fingerprint(peer.certificateFingerprint || peer.fingerprint)}</p>
              <div className="pw-form-actions">
                {!retired(peer) && <Button variant="secondary" disabled={busy} onClick={() => { setForm('connection'); setRaw(''); setAddress(''); }}>更新连接地址</Button>}
                {available(peer.actions?.revoke) && <Button variant="ghost" disabled={busy} onClick={() => actionReview('peer.revoke', { deviceId: peer.deviceId })}>核对并撤销本机信任</Button>}
              </div>
            </AccordionItem>
          </Accordion>
        </>}
        {!sourceFocus && snapshot && <Accordion className="pw-device-read-details"><AccordionItem id="device-read-details" title="读取详情" description={snapshot.hasMorePeers || snapshot.hasMorePendingPairings || snapshot.hasMoreTaskActions || snapshot.taskLinks?.hasMore ? '本次读取有范围限制' : undefined}>
          <p className="pw-device-footnote">状态读取于 {time(snapshot.capturedAt)}</p>
          {(snapshot.hasMorePeers || snapshot.hasMorePendingPairings || snapshot.taskLinks?.hasMore) && <p className="pw-device-footnote">本次只读取了部分设备或关联记录，未列出不代表没有。</p>}
          {snapshot.hasMoreTaskActions && <p className="pw-device-footnote">本次只核对了前 40 项工作的可用操作，其余请打开原工作查看。</p>}
        </AccordionItem></Accordion>}
      </>}
    </section>
    <SettingsDialog isOpen={!!ownTextShown} onClose={closeOwnText} title={ownTextShown?.page?.part==='goal'?'当时收到的完整目标':'本机已核对的原文'}>
      {ownTextShown&&<div className="pw-device-form">{ownTextShown.page?.part!=='goal'&&<h3 dir="auto">{ownTextShown.row.goal}</h3>}
        {error&&<><p role="alert">{error}</p><Button variant="secondary" disabled={busy} onClick={()=>readOwnText(ownTextShown.row,0,undefined,ownTextShown.home,'result',ownTextShown.home&&!!localInbox?.searchText)}>重新核对原文</Button></>}<WorkingStatus active={busy}>正在核对本机原执行记录…</WorkingStatus>
        {ownTextShown.page&&<><p>本机核对时间：{time(ownTextShown.page.reviewedAt)}</p>
          <p>{ownTextShown.page.part==='goal'?'这是原发起设备当时直接交给本机的目标，不含它的其他对话或资料。':ownTextShown.page.keptOnPhone?'当时只回完成状态，文字留在这部手机。':'当时允许原发起设备凭有效读取权限查看已核对摘要的前 2,048 字；不代表它已经读过。'}</p>
          <div aria-live="polite"><p className="pw-source-copy" dir="auto">{ownTextShown.page.text||'这份已核对的原结果没有文字。'}</p>
          {ownTextShown.page.pages>1&&<p>第 {ownTextShown.page.page+1} / {ownTextShown.page.pages} 段</p>}</div>
          {(ownTextShown.page.part==='goal'||ownTextShown.page.longOriginalGoal===true)&&<div className="pw-form-actions"><Button variant="secondary" disabled={busy}
            onClick={()=>{const goal=ownTextShown.page.part==='goal';readOwnText(ownTextShown.row,goal?ownTextShown.resultPage:0,ownTextShown.page,ownTextShown.home,goal?'result':'goal');}}>
            {ownTextShown.page.part==='goal'?'回到已核对的结果':'查看完整原目标'}</Button></div>}
          <div className="pw-form-actions">{ownTextShown.page.page>0&&<Button variant="ghost" disabled={busy} onClick={()=>readOwnText(ownTextShown.row,ownTextShown.page.page-1,ownTextShown.page)}>上一段</Button>}
            {ownTextShown.page.page+1<ownTextShown.page.pages&&<Button variant="secondary" disabled={busy} onClick={()=>readOwnText(ownTextShown.row,ownTextShown.page.page+1,ownTextShown.page)}>下一段</Button>}</div></>}
        <p className="pw-device-footnote">仅查看本机保存的原件，没有联系发起设备或重新派工。</p>
        <Button variant="ghost" onClick={closeOwnText}>回到接纳记录</Button></div>}
    </SettingsDialog>
    <PhoneControlPreparation phone={workPhone} onPendingChange={setPreparingDeviceWork} open={form === 'manual'} run={run} onClose={() => setForm('')} />
    <DeviceTaskPreparation phone={workPhone} onPendingChange={setPreparingDeviceWork} open={form === 'work'} run={run} onClose={() => setForm('')} />
    <SettingsDialog isOpen={!!form && !review && form !== 'work' && form !== 'manual' && (!pairingEntry || !!snapshot)} onClose={() => { clearSensitive(true); refresh(); }} title={form === 'relay' ? '临时中继租约' : form === 'connection' ? '更新原设备连接地址' : form.startsWith('recovery') ? '核对原配对结果' : '配对另一台设备'}><div className="pw-collaboration-dialog" data-collaboration-kind="DevicesWorkspace">
      <div className="pw-device-form">
        {error && <p className="pw-device-feedback" role="alert">{error}</p>}
        <WorkingStatus active={busy}>正在读取原设备回执…</WorkingStatus>
        {pairingEntry && <RadioOptions className="pw-device-pair-roles" label="从哪台设备发出邀请？" value={form} onChange={side => { if (side === 'add' || side === 'invite') openPairEntry(side); }} isDisabled={busy}
          options={[{id: 'add', label: '对方已发邀请'}, {id: 'invite', label: '从这台手机发出'}]} />}
        {['add', 'connection', 'relay'].includes(form) && <section className="pw-device-form-step">
          {form === 'add' && <p>加入对方的短时邀请，无需开启这台手机的接收或屏幕读取。两端仍需分别核对指纹和权限。</p>}
          {form==='add'&&firstPair?<div className="pw-device-review">
            <h3>先看这次原邀请</h3>
            <p>发起设备 …{firstPair.deviceId.slice(-8)} · 有效至 {time(firstPair.expiresAt)}</p><p className="pw-source-copy">邀请中的完整证书指纹：<bdi>{fingerprint(firstPair.fingerprint)}</bdi></p>
            <p>本机还没有设备身份。继续会将本机身份保存在 Android 密钥库里，再核对两端指纹和权限；不会发送请求或开启接收。</p>
            <div className="pw-form-actions"><Button disabled={busy||!supported('devices.continueFirstPair')} onClick={continueFirstPair}>继续配对并创建本机身份</Button>
              <Button variant="ghost" disabled={busy} onClick={leaveFirstPair}>返回，不创建身份</Button></div>
          </div>:<>
          {form === 'add' && supported('devices.pairImage.pick') && supported('devices.pairImage.review') && <>
            <div className="pw-form-actions">
              <Button variant="secondary" disabled={busy || !available(actions.join)} onClick={chooseImage}>{imageId ? '重新选择图片' : '选择邀请二维码图片'}</Button>
              {imageId && <Button disabled={busy || !available(actions.join)} onClick={() => {
                void invoke('devices.pairImage.review', { pickerId: imageId }, value => {
                  if (activeImage.current !== imageId) throw new Error('原选图窗口已变化，请重新选择。');
                  if (value.cancelled === true && value.pickerId === imageId) { closeImage();setError('没有选择图片。请重新选图，或粘贴原邀请。'); return; }
                  showPairPreview(value,imageId);
                });
              }}>核对所选图片</Button>}
            </div>
            <p>可选另一台手机或电脑这次邀请的截图或照片，保留四周白边。只在本机识别，不上传，也不自动加入。也可以粘贴邀请：</p>
          </>}
          <TextAreaField label={form === 'relay' ? '已有临时租约' : form === 'add' ? '对方的短时邀请' : '原设备短时邀请'} value={raw} onChange={setRaw} isDisabled={busy} hint="仅在当前窗口核对，离开或进入后台后清除。" />
          <Button disabled={busy || !raw.trim() || form === 'add' && !available(actions.join)} onClick={() => { void invoke(form === 'relay' ? 'devices.reviewRelay' : form === 'connection' ? 'devices.reviewConnection' : 'devices.inspectInvitation', { raw, ...(form === 'connection' ? { deviceId: peerId } : {}) }, form==='add'?value=>showPairPreview(value):acceptReview); }}>{form === 'add' ? '核对原邀请' : '读取原生审阅'}</Button>
          </>}
          {form === 'add' && !available(actions.join) && actions.join?.reason && <p>{actions.join.reason}</p>}
        </section>}
        {form === 'invite' && <section className="pw-device-form-step">
          <h3>{receiverLabel}</h3>
          {!available(actions.invite) ? <>
            <p>{local.receiverEnabled ? local.receiverReason || actions.invite?.reason || '尚未取得可用的邀请地址。' : actions.enable?.reason || '先核对并开启接收，才能生成本机邀请。开启接收不会授予对方屏幕或执行权限。'}</p>
            <div className="pw-form-actions">
              {local.receiverEnabled === false && available(actions.enable) && <Button disabled={busy || !supported('devices.reviewAction')} onClick={() => { void invoke('devices.reviewAction', { operation: 'receiver.enable' }, value => {
                const actual = deviceReview(value);
                if (actual.kind !== 'action' || actual.view.operation !== 'receiver.enable') { void run('devices.discard', { viewHandle: actual.viewHandle }).catch(() => {}); throw new Error('接收服务审阅不匹配，请重新读取。'); }
                acceptReview(actual); setForm('invite');
              }); }}>核对并启用本机接收</Button>}
              {local.receiverEnabled === false && !available(actions.enable) && localPhone.some(group => group.id === 'taskNotificationsForStart' && group.state === 'blocked') && supported('settings.openSystem') &&
                <Button variant="secondary" disabled={busy} onClick={() => openPhoneSetup('notifications')}>在系统中设置任务通知</Button>}
              <Button variant="ghost" disabled={busy} onClick={refresh}>重读本机状态</Button>
            </div>
          </> : <>
            {inviteAddresses.length === 1 ? <p className="pw-source-copy">本次邀请使用 <bdi>{inviteAddresses[0]}</bdi></p>
              : <SelectField label="本机地址或已配置中继" value={inviteAddress} onChange={setAddress} options={inviteAddresses.map(value => ({ id: value, label: value }))} isDisabled={busy} />}
            <Button disabled={busy || !inviteAddress || !supported('devices.createInvitation')} onClick={() => { void invoke('devices.createInvitation', { address: inviteAddress }, value => { setInvitation(shownPairingInvitation(value,local)); setRaw(''); setForm(''); }); }}>创建短时邀请</Button>
          </>}
        </section>}
        {pairingEntry && <Accordion className="pw-device-form-alternatives">
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
    <SettingsDialog isOpen={!!invitation} onClose={() => setInvitation(null)} title="将邀请交给另一台设备"><div className="pw-collaboration-dialog" data-collaboration-kind="DevicesWorkspace">
      {invitation && <PairingInvitationDisplay invite={invitation}/>}
      <p className="pw-source-copy">本机完整指纹：<bdi>{fingerprint(local.certificateFingerprint)}</bdi></p>
      <Button variant="secondary" onClick={() => setInvitation(null)}>清除显示，回到配对进度</Button>
    </div></SettingsDialog>
    <SettingsDialog isOpen={!!review && (!sourceFocus || !!focusedPeer && (pinnedConnection
      ? review.kind==='action' && review.view?.operation==='peer.connect' && review.view?.peer?.deviceId===sourceFocus.deviceId &&
        review.view?.peer?.instanceId===sourceFocus.instanceId && review.view?.peer?.certificateFingerprint===sourceFocus.certificateFingerprint
      : review.kind==='history' && review.view?.taskId===sourceFocus.taskId && review.view?.targetDeviceId===sourceFocus.deviceId && review.view?.instanceId===sourceFocus.instanceId))} onClose={closeReview} title={review?.kind === 'pair' ? '范围与双端身份' : review?.kind === 'grant' ? '原设备权限修改' : review?.kind === 'goal' ? '用本机模型处理原目标' : review?.kind === 'result' ? '核对这次原成果' : review?.kind === 'approval' ? '核对这次设备操作' : review?.kind === 'recovery' ? '原配对结果只读窗口' : review?.kind === 'resume' ? '核对这次恢复请求' : review?.kind === 'history' ? (originalView.phoneGoalResult === true ? '这次手机目标与派发' : '原设备操作回执') : '核对原设备操作'}><div className="pw-collaboration-dialog" data-collaboration-kind="DevicesWorkspace">
      {review && <div className="pw-device-review">
        {error && <p role="alert">{error}</p>}
        {['pair', 'grant'].includes(review.kind) && !exactGrant ? <GrantSelection key={review.viewHandle} review={review} busy={busy} confirm={args => { void invoke('devices.reviewGrant', args, acceptReview); }} /> : <>
          {exactGrant && <><GrantScope scope={exactGrant} />{review.kind === 'pair' ? <><p className="pw-source-copy">发出邀请的设备：{originalView.inviterDeviceId}<br />完整指纹：{fingerprint(originalView.inviterFingerprint)}</p><p className="pw-source-copy">加入设备：{originalView.joinerDeviceId}<br />完整指纹：{fingerprint(originalView.joinerFingerprint)}</p><p>原邀请至 {time(originalView.expiresAt)}。两台设备均核对一致后才能完成配对。</p></> : <p className="pw-source-copy">已配对设备完整指纹：{fingerprint(originalView.certificateFingerprint)}</p>}</>}
          {review.kind === 'goal' && <><h3>{originalView.goal}</h3><p>本机模型 {originalView.model} · {originalView.provider} · {originalView.source === 'local_api' ? '本机 API 连接' : originalView.source === 'local_account' ? '本机账号' : originalView.source}</p><ResourceScope resource={originalView.resourceBinding || {}} /><p>本次上限：工具 {originalView.toolBudget} 次 · 模型 {originalView.modelBudget} 次 · 至 {time(originalView.deadline)}</p>{originalView.origin && <p>原发起设备 {originalView.origin.originDeviceId} · 请求设备 {originalView.origin.callerDeviceId}</p>}{originalView.contextSummary && <p>{originalView.contextSummary}</p>}{originalView.contextArtifactCount > 0 && <p>附带 {originalView.contextArtifactCount} 个原文件引用，仅本任务使用。</p>}{openModels && <Button variant="secondary" disabled={busy} onClick={() => {
              const source = tasks.find(item => item.id === originalView.taskId);
              const origin = originalView.origin;
              const mode=originalView.resourceBinding?.screenMode,text=mode==='capabilities' && originalView.toolBudget===0 &&
                ['pocketworld.direct-text.owner-reviewed-result.v1','pocketworld.original-phone-allocation.owner-reviewed-text.v1'].includes(origin?.textReturnContract);
              if ((text||['main_screen','background'].includes(mode)) && origin?.callerDeviceId && origin.callerDeviceId===origin.originDeviceId) {
                if(supported('devices.prepareReceivedModelReturn')){
                  chooseModelForReceived({viewHandle:review.viewHandle},{taskId:originalView.taskId,operationId:origin.remoteOperationId},origin.callerDeviceId,
                    {parentTaskId:origin.parentTaskId,grantId:origin.grantId,epoch:origin.authorizationEpoch});return;
                }
                if (!source || !isReceivedPhoneTask(source, snapshot) || origin.remoteOperationId !== source.raw.origin.remoteOperationId || origin.grantId !== source.raw.origin.grantId) { setError('这项原派工已变化，请关闭后重新读取。'); return; }
                openModelSettingsFor(source);
              } else { closeReview(); openModels(); }
            }}>更换本机模型</Button>}{originalView.source === 'local_account' && <p role="alert">这个账号当前不支持跨设备目标，请先在模型设置选择已授权 API 模型。</p>}<CheckboxField isSelected={future} onChange={setFuture} isDisabled={busy}>以后同一配对授权、模型与范围内的目标自动处理</CheckboxField>{future && <p>未来策略：工具 {originalView.policyToolBudget} 次 · 模型 {originalView.policyModelBudget} 次 · 至 {time(originalView.policyExpiresAt)}。模型、配对代次或范围改变后重新审阅。</p>}</>}
          {review.kind === 'action' && <><h3>{declining?'不接手这项工作':deviceOperationLabels[originalView.operation]}</h3>{originalView.peer && <><p>{name(originalView.peer)} · {originalView.peer.deviceId}</p><p className="pw-source-copy">{fingerprint(originalView.peer.certificateFingerprint)}</p></>}{originalView.taskId && <><p>{originalView.goal || originalView.pendingGoal?.goal}</p><p>原任务 {originalView.taskId}{originalView.runId ? ` · 执行 ${originalView.runId} · 代次 ${originalView.executorEpoch}` : ' · 从未启动的原目标'}</p>{originalView.origin && <p>原发起设备 {originalView.origin.originDeviceId} · 请求设备 {originalView.origin.callerDeviceId}</p>}{originalView.resourceBinding && <ResourceScope resource={originalView.resourceBinding} />}</>}{originalView.lease && <p>{originalView.lease.serviceBase} · {originalView.lease.inviterDeviceId}</p>}{declining&&<p>来自 {sourceLabel(originalView.pendingGoal.callerDeviceId,rows(waitingPage?.rows).find(row=>row.taskId===originalView.taskId && row.operationId===originalView.pendingGoal.operationId && row.sourceDeviceId===originalView.pendingGoal.callerDeviceId && row.createdAt===originalView.pendingGoal.acceptedAt)?.sourceIdentity)} · 本机接纳于 {time(originalView.pendingGoal.acceptedAt)}</p>}<p>{declining?'确认后，本机会结束这份从未启动的原派单。不运行模型，不向发起设备发消息。':originalView.operation === 'peer.revoke' ? '本机撤销会使旧授权失效，对端是否收到撤销另看真实回执。已经发生的动作无法撤回。' : originalView.operation.startsWith('task.') ? '只控制这项原任务，不重新提交目标。暂停、继续与停止仍以执行端回执为准。' : '变更本机接收或连接不会把正在执行的任务标记为停止。'}</p></>}
          {review.kind === 'connection' && <>{originalView.discoveredAddress && <p role="alert">此地址来自未认证的局域网广播。确认只更新本机连接地址并尝试原证书双向 TLS；不重新配对、不扩大权限，不代表地址已验证或设备在线。选错地址会连接失败，可用原设备邀请恢复。</p>}<p>更新原设备 {originalView.deviceId} 的连接地址。</p><p className="pw-source-copy">已配对证书完整指纹：{fingerprint(originalView.certificateFingerprint)}</p><ResourceScope resource={Object.fromEntries(Object.entries(originalView).filter(([key]) => ['host', 'port', 'controlPort', 'bulkPort', 'serviceBase', 'path', 'connectionKind', 'kind', 'expiresAt'].includes(key)))} /></>}
          {review.kind === 'relay' && <><p>{originalView.serviceBase} · 至 {time(originalView.expiresAt)}</p><p>只在内存中使用这份临时租约；不产生设备权限，双端身份仍要核对。</p><p>原邀请设备 {originalView.inviterDeviceId}</p></>}
          {review.kind === 'abandon' && <><p>只放弃本机这次待配对记录，对端可能已经保存配对。这里不会代替对端撤销。</p><p className="pw-source-copy">原设备指纹：{fingerprint(originalView.inviterFingerprint)}</p></>}
          {review.kind === 'recovery' && <><p>{originalView.kind === 'relay' ? originalView.serviceBase : `${originalView.host} · ${originalView.port}`}</p><p>只读入口截至 {time(originalView.expiresAt)}，关闭此窗口将关闭入口，不延长旧授权。</p><p className="pw-source-copy">本机指纹 {fingerprint(originalView.localFingerprint)}<br />原设备指纹 {fingerprint(originalView.peerFingerprint)}</p></>}
          {review.kind === 'result' && <><p className="pw-source-copy">{originalView.summary || '执行已结束，请核对原成果。'}</p><Accordion><AccordionItem id="evidence" title="原成果依据"><pre className="pw-source-copy">{JSON.stringify(originalView.evidence || {}, null, 2)}</pre></AccordionItem></Accordion><p>{originalView.canKeepOriginalText===true ? <>只回状态时，文字和依据均留在本机。如选择交回摘要，原发起设备（…{String(originalView.textResultReader).slice(-12)}）可凭既有授权读取前 2,048 个字。</> : originalView.textResultReader ? <>确认后，原发起设备（…{String(originalView.textResultReader).slice(-12)}）可凭原授权读取这次摘要的前 2,048 个字。依据和执行记录只留本机。</> : "确认会保存这次核对。对于直接委派的手机目标，原发起设备可在已有读取权限内查看已确认的摘要；不会重新执行或新增权限。"}</p></>}
          {review.kind === 'approval' && <><p>{originalView.message || originalView.tool}</p><ResourceScope resource={originalView.resourceBinding || {}} /><Accordion><AccordionItem id="arguments" title="这次操作的准确参数"><pre className="pw-source-copy">{JSON.stringify(originalView.arguments || {}, null, 2)}</pre></AccordionItem></Accordion><p>只决定这个原运行的准确参数，不扩大设备授权。</p></>}
          {review.kind === 'resume' && <><p>原任务 {originalView.taskId} · 原执行 {originalView.runId}</p><p>请求设备 {originalView.callerDeviceId} · 请求于 {time(originalView.requestedAt)}</p><p>本次只决定已暂停原执行的恢复请求，原授权、累计预算、期限与来源仍由原生核对。</p>{originalView.canApprove !== true && <p role="alert">这次请求已失效或原执行暂不可继续；可以拒绝后由对方重新读取。</p>}</>}
          {review.kind === 'history' && originalView.phoneGoalResult === true && <section aria-label="同一手机目标的实际结果">
            <h3>对方手机的目标结果</h3>
            {phoneResultPath === 'original_run' ? <><p>本机已核对同一次原执行。下方单独向原手机读取，能否返回成果取决于它的现有读取授权与机主核对状态。</p>
              <Button variant="secondary" disabled={busy || !supported('devices.queryPhoneGoalResult')} onClick={readPhoneGoal}>{phoneGoalResult || textClosed ? '重读原目标结果' : '读取原目标结果'}</Button></>
              : phoneResultPath === 'need_dispatch_receipt' ? <><p>本机还没有可核对的原执行编号。先查对方的派发回执；这不等于读取成果，也不会重新派工。</p>
                <Button variant="secondary" disabled={busy || !supported('devices.queryHistory')} onClick={() => queryHistory()}>核对这次原派发</Button></>
              : <p role="status">无法把本机记录与这次原执行核对一致，不会读取或展示其他任务的结果。</p>}
            {textClosed && <p role="status">本次文字查看已结束，或原设备身份与授权已变化。正文已收起；如仍需查看，请手动重读。</p>}
            {phoneGoalResult && <div role="status"><p>对端报告：{({ succeeded: '已完成', failed: '未完成', cancelled: '已取消', running: '执行中', paused: '已暂停', accepted: '已接收', waiting_approval: '等待确认', waiting_resource: '等待资源', waiting_dependency: '等待前置工作', queued_local: '已排队', submitting: '提交中', pause_requested: '正在暂停', cancel_requested: '正在取消', interrupted: '已中断', needs_reconcile: '仍待核对' } as Record<string,string>)[phoneGoalResult.state] || '状态待核对'} · {time(phoneGoalResult.updatedAt)}</p>
              {phoneGoalResult.review === 'confirmed' ? <><p>对方机主已核对{phoneGoalResult.truncated ? ' · 仅显示摘要前 2,048 字' : ''}</p>
                {phoneGoalResult.summaryWithheld===true ? <p>对方机主确认了完成，选择只回状态。文字保留在原手机。</p> : phoneGoalResult.summary.trim() ? <p className="pw-source-copy" dir="auto">{phoneGoalResult.summary}</p> : <p>没有可返回的文字摘要，不能在这里核对原结果。这里未读取文件或其他成果依据。</p>}</>
                : <p>{phoneGoalResult.review === 'pending' ? '结果在原手机等机主核对，暂不显示内容。' : phoneGoalResult.review === 'not_confirmed' ? '原手机没有确认这份成果。' : phoneGoalResult.review === 'none' ? '原手机还没有这次执行已核对的成果记录。' : '原手机只返回了执行进展，没有可核对的原结果摘要。'}</p>}
            </div>}
            {['original_run','need_dispatch_receipt'].includes(originalView.localPhoneResultPath) && phoneResultPath !== 'unverified_source'
              && <Button variant="ghost" disabled={busy || !supported('devices.openTask')} onClick={returnPhoneSource}>回到本机发起这件事的原工作</Button>}
          </section>}
          {review.kind === 'history' && originalView.directWorkspaceRead && <WindowsWorkspaceReadReceipt original={originalView} read={historyReceipt} />}
          {review.kind === 'history' && originalView.canCheckLocalExportedFile === true && <OriginalLocalExportedFile
            key={review.viewHandle} handle={review.viewHandle} original={originalView} disabled={busy || !supported('devices.checkLocalExportedFile')}
            open={supported('devices.openCheckedLocalFile') ? ticket => void invoke('devices.openCheckedLocalFile', { viewHandle: review.viewHandle, fileReview: ticket }) : undefined}
            check={accept => void invoke('devices.checkLocalExportedFile', { viewHandle: review.viewHandle }, accept)} />}
          {review.kind === 'history' && originalView.directWorkspaceFileDelivery === true && <WindowsFileDeliveryReceipt original={originalView} read={historyReceipt} />}
          {review.kind === 'history' && originalView.directWorkspaceCommand === true && <WindowsCommandReceipt original={originalView} read={historyReceipt} />}
          {review.kind === 'history' && <>{originalView.phoneGoalResult === true && <h3>原派发回执</h3>}<p>执行设备 {originalView.targetDeviceId}</p><p>原操作 {originalView.operationId}</p><ResourceScope resource={originalView.resourceBinding} /><p role="status">{deviceHistoryLabel(historyReceipt, originalView)}</p><p>查询只读取原操作；未知效果保留原占用次数。请求停止后仍需核对回执。</p>{historyReceipt && <Accordion><AccordionItem id="original-receipt" title="原操作回执详情"><pre className="pw-source-copy">{JSON.stringify(historyReceipt, null, 2)}</pre></AccordionItem></Accordion>}{(originalView.phoneGoalResult !== true || phoneResultPath !== 'need_dispatch_receipt') && <Button disabled={busy} onClick={() => queryHistory()}>查询原操作回执</Button>}{originalView.archiveReadOnly ? <p>从历史目录只读打开，不会停止或重发原任务。</p> : !historyStopSubmitted ? <><CheckboxField isSelected={ack} onChange={setAck} isDisabled={busy}>我已核对上方原设备、原操作与范围，要求停止这次操作</CheckboxField><Button variant="secondary" disabled={busy || !ack} onClick={() => queryHistory(true)}>请求停止这次原操作</Button></> : <p>本窗口已提交过停止请求。继续查询原回执核对结果。</p>}</>}
          {!['recovery', 'history'].includes(review.kind) && <CheckboxField isSelected={ack} onChange={setAck} isDisabled={busy}>{declining?'我已核对原派单，确认这部手机不接手':review.kind === 'pair' ? '我同意准确权限，并已比较两台设备显示的两个完整指纹，均一致' : '我已核对原对象、范围与影响，确认这一次操作'}</CheckboxField>}
          <div className="pw-form-actions"><Button variant="secondary" onClick={closeReview}>{review.kind === 'recovery' ? '关闭只读入口' : '返回核对'}</Button>{!['recovery', 'history'].includes(review.kind) && <Button disabled={busy || !ack || review.kind === 'resume' && originalView.canApprove !== true || review.kind === 'goal' && (originalView.source === 'local_account' || !originalView.model)} onClick={() => confirmReview(review.kind === 'result' ? { completed: true } : ['approval', 'resume'].includes(review.kind) ? { approved: true } : {})}>{review.kind === 'result' ? originalView.canKeepOriginalText===true?'确认完成并交回摘要':'确认成果并结束' : review.kind === 'approval' ? '允许这次原操作' : review.kind === 'resume' ? '同意继续原执行' : review.kind === 'pair' ? '同意权限并确认双端指纹' : declining?'确认不接手':'确认本次操作'}</Button>}{review.kind==='result' && originalView.canKeepOriginalText===true && <Button variant="secondary" disabled={busy||!ack} onClick={()=>confirmReview({completed:true,keepTextLocal:true})}>确认完成，只回状态</Button>}{review.kind === 'result' && <Button variant="ghost" disabled={busy || !ack} onClick={() => confirmReview({ completed: false })}>结束但不确认完成</Button>}{['approval', 'resume'].includes(review.kind) && <Button variant="secondary" disabled={busy || !ack} onClick={() => confirmReview({ approved: false })}>{review.kind === 'resume' ? '拒绝这次恢复请求' : '拒绝这次操作'}</Button>}</div>
        </>}
        {review.kind === 'pair' && <Button variant="ghost" disabled={busy} onClick={() => { const old = review; handle.current = ''; setReview(null); void invoke('devices.reject', { viewHandle: old.viewHandle }, () => setNotice('本机已拒绝这次配对，请核对原状态。')); }}>拒绝这次原配对</Button>}
      </div>}
    </div></SettingsDialog>
  </>;
}
