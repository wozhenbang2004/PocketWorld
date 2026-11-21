import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, useSyncExternalStore, type ReactNode } from 'react';
import { dequal } from 'dequal/lite';
import { bridge } from './bridge';
import { Tabs, TabList, Tab, TabPanel } from './shell-library';
import './task-process.css';
import { TaskTrajectory } from './TaskTrajectory';
import { Accordion, AccordionItem, Button, IconButton } from './forms-library';
import { IconArrowLeft, IconArrowRight, IconChevronRight, IconRefresh } from './icons';
import { processCheckpoint, type ProcessRecord } from './task-process-checkpoint';
import { phoneStopReceipt } from './phone-stop-receipt';
import { phoneResourceHistory } from './phone-resource-history';
import { getTaskEventHistory, getTaskOperations } from './task-presentation';
import { taskAuditPage, taskHistoryIdentity, type AuditPage, type AuditSection, type TaskHeader } from './task-history';
import { OriginalWindowsTextRead, originalWindowsTextAt } from './OriginalWindowsTextRead';

type Window = { identity: string; cursor: string; prior: string[]; page: AuditPage };
const message = (failure: unknown) => failure instanceof Error ? failure.message : String(failure);
const time = (value?: number) => typeof value === 'number' && value > 0 ? new Date(value).toLocaleString('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '';

type Head = { identity: string; page: AuditPage; seed?: AuditPage; headerStamp: string };
const sourceRevision = () => String(bridge.getState().state.traceSourceRevision ?? 0);
const recordKey = (run: string, id: string) => JSON.stringify([run, id]);

export type OriginalPhoneToolRecord = { taskId: string; runId: string; epoch: number; operationId: string;
  requestDigest: string; wireSha256: string; deviceId: string; instanceId: string; fingerprint: string };
const originalId = (value: unknown): value is string => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(value);
const digest = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const deviceId = (value: unknown): value is string => typeof value === 'string' && /^pw1_[a-f0-9]{64}$/.test(value);
const positiveNumber = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value > 0;

/** A stored native invocation on the exact original phone, not an inference from the currently selected scope. */
export function originalPhoneToolRecord(task: TaskHeader, operationId?: string): OriginalPhoneToolRecord | undefined {
  const raw = task.raw, work = raw?.ownerDeviceWork, peer = work?.directPhone;
  if (!raw || raw.id !== task.id || raw.status !== task.status || raw.actorId !== 'owner' || raw.spaceId !== 'private'
    || raw.kind !== 'assistant' || raw.executionMode !== 'capabilities' || raw.conversationId !== '' || raw.origin != null
    || raw.parentTaskId != null || raw.ownerPhoneControl != null || raw.contextAvailable === false || raw.contextWithdrawn === true
    || raw.sourceCurrentReadRequired === true || raw.taskProjection !== 'detail_window' || raw.auditBodyIncluded !== true || raw.taskCoreReadLimited !== false
    || work?.format !== 'pocketworld.owner-device-work.v1' || work.requestId !== task.id || !digest(work.requestDigest) || work.directWorkspace != null
    || !originalId(task.id) || !originalId(raw.runId) || !positiveNumber(raw.coordinatorEpoch)
    || !deviceId(peer?.deviceId) || !originalId(peer.instanceId) || !digest(peer.certificateFingerprint)) return;
  let page: AuditPage;
  try { page = taskAuditPage(raw.historyOperationPage, task.id, 'operations'); } catch { return; }
  for (const op of page.entries) {
    if (!originalId(op.operationId) || operationId !== undefined && op.operationId !== operationId || op.runId !== raw.runId
      || op.tool !== 'device_invoke_tool' || op.state !== 'receipt' || op.status !== 'available' || op.bodyAvailable === false
      || op.readLimited === true || op.arguments?.targetDeviceId !== peer.deviceId || !originalId(op.arguments?.toolName)) continue;
    const send = op.receipt?.nativeDeviceSend, accepted = op.receipt?.nativeDeviceAcceptedBinding;
    const receipt = accepted?.verifiedReceipt, audience = receipt?.audience;
    // Android tool sessions also have a receiver task ID. Only the invoker's original SQL tool
    // above distinguishes a tool invocation from a Goal; their accepted object kind alone does not.
    if (send?.format !== 'pocketworld.native-device-send.v1' || send.state !== 'write_claimed' || !positiveNumber(send.claimedAt)
      || !digest(send.wireSha256) || !originalId(send.allocationId) || accepted?.accepted !== true || accepted.parentTaskId !== task.id
      || accepted.sourceOperationId !== op.operationId || accepted.targetOperationId !== op.operationId
      || accepted.instanceId !== peer.instanceId || accepted.executorDeviceId !== peer.deviceId || !positiveNumber(accepted.acceptedAt)
      || receipt?.protocolVersion !== '1.0' || receipt.messageType !== 'OperationReceipt' || receipt.accepted !== true
      || receipt.effectState !== 'reported' || receipt.operationId !== op.operationId || receipt.instanceId !== peer.instanceId
      || receipt.executorDeviceId !== peer.deviceId || receipt.acceptedAt !== accepted.acceptedAt
      || !digest(receipt.argumentsDigest) || receipt.argumentsDigest !== accepted.operationArgumentsDigest
      || audience?.kind !== 'operation' || audience.objectId !== op.operationId || audience.rootTaskId !== task.id
      || audience.localTaskId !== send.allocationId || audience.targetDeviceId !== peer.deviceId || audience.instanceId !== peer.instanceId
      || !deviceId(audience.callerDeviceId) || audience.originDeviceId !== audience.callerDeviceId
      || audience.requestRunId !== raw.runId || audience.requestExecutorEpoch !== raw.coordinatorEpoch
      || audience.argumentsDigest !== receipt.argumentsDigest || !['main_screen', 'background'].includes(audience.resourceBinding?.screenMode)) continue;
    return { taskId: task.id, runId: raw.runId, epoch: raw.coordinatorEpoch, operationId: op.operationId, requestDigest: work.requestDigest,
      wireSha256: send.wireSha256, deviceId: peer.deviceId, instanceId: peer.instanceId, fingerprint: peer.certificateFingerprint };
  }
}

function useAuditWindow(task: TaskHeader, identity: string, section: AuditSection, enabled: boolean, active: boolean, documentEpoch: number, revision: string) {
  const [loaded, setLoaded] = useState<Window | null>(null), [head, setHead] = useState<Head | null>(null);
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  const alive = useRef(true), request = useRef(0), entering = useRef(false);
  const current = useRef({ identity, enabled, active }); current.current = { identity, enabled, active };
  useEffect(() => { alive.current = true; return () => { alive.current = false; request.current++; }; }, []);
  useEffect(() => { request.current++; entering.current = false; setLoaded(null); setHead(null); setBusy(false); setError(''); }, [identity, enabled]);
  useEffect(() => { if (!active) { request.current++; entering.current = false; setBusy(false); } }, [active]);
  const key = section === 'events' ? 'historyEventPage' : 'historyOperationPage', value = task.raw?.[key];
  const seed = useMemo(() => {
    try { if (value != null) return taskAuditPage(value, task.id, section); } catch { /* Show the reading gap, not the unchecked rows. */ }
    return undefined;
  }, [value, task.id, section]);
  const currentHead = enabled && head?.identity === identity ? head : null;
  const headerStamp = JSON.stringify([task.updatedAt ?? null, task.status]);
  // An explicit reread can contain more than the embedded projection. Keep it
  // only while that projection stays the same; a same-position tool can settle.
  const latest = currentHead && currentHead.headerStamp === headerStamp &&
    (!seed || currentHead.page.upperPosition >= seed.upperPosition) && dequal(seed, currentHead.seed) ? currentHead.page : seed;
  const observed = enabled && loaded?.identity === identity ? loaded : null;
  const view = observed?.page ?? latest;
  const read = async (cursor: string, prior: string[], upper?: number) => {
    if (!enabled || !active || entering.current) return;
    entering.current = true; const original = ++request.current; setBusy(true); setError('');
    const valid = () => alive.current && original === request.current && current.current.identity === identity && current.current.enabled && current.current.active &&
      bridge.getDocumentEpoch() === documentEpoch && sourceRevision() === revision;
    try {
      const result = await bridge.command('history.read', { taskId: task.id, section, cursor });
      if (!valid()) return;
      const page = taskAuditPage(result, task.id, section, cursor, prior, upper);
      if (cursor) setLoaded({ identity, cursor, prior, page });
      else { setLoaded(null); setHead({ identity, page, seed, headerStamp }); }
    } catch (failure) { if (valid()) setError(message(failure)); }
    finally { if (valid()) { entering.current = false; setBusy(false); } }
  };
  const older = () => { if (view?.hasMore) void read(view.nextCursor, [...(observed?.prior || []), observed?.cursor || ''], view.upperPosition); };
  const newer = () => {
    if (!observed?.prior.length) return;
    const cursor = observed.prior.at(-1)!;
    void read(cursor, observed.prior.slice(0, -1), cursor ? view?.upperPosition : undefined);
  };
  const showLatest = () => { request.current++; entering.current = false; setBusy(false); setError(''); setLoaded(null); };
  return { page: view, latest, busy, error, older, newer, showLatest, refresh: () => { void read('', []); },
    earlier: Boolean(observed), newEntries: !!observed && !!latest && latest.upperPosition > observed.page.upperPosition,
    seedInvalid: value != null && !seed, canGoBack: Boolean(observed?.prior.length) };
}

/** The adopted task disclosures now navigate their original bounded audit windows. */
export function TaskHistory({ task, canRead, canReadTrace = false, active = true, registerTransientBack, registerOriginalPhoneTool, renderOperation }: {
  task: TaskHeader; canRead: boolean; canReadTrace?: boolean; active?: boolean;
  registerTransientBack?: (handler: (() => boolean) | null) => void;
  registerOriginalPhoneTool?: (handler: ((pin: OriginalPhoneToolRecord) => boolean) | null) => void;
  renderOperation: (operation: unknown, index: number, scope: string) => ReactNode;
}) {
  const [selectedTab, setSelectedTab] = useState('trace');
  const documentEpoch = useSyncExternalStore(bridge.subscribe, bridge.getDocumentEpoch, bridge.getDocumentEpoch);
  const inOriginalTask = useSyncExternalStore(bridge.subscribe, () => {
    const view=bridge.getState().view as {section?:string; taskId?:string} | undefined;
    return !view?.section && view?.taskId === task.id;
  });
  const revision = useSyncExternalStore(bridge.subscribe, sourceRevision, sourceRevision);
  const unavailable = task.raw?.contextAvailable === false || task.raw?.sourceCurrentReadRequired === true ||
    task.raw?.taskProjection === 'source_unavailable' || !!task.raw?.id && task.raw.id !== task.id;
  const identity = JSON.stringify([taskHistoryIdentity(task), documentEpoch, revision, unavailable]);
  const events = useAuditWindow(task, identity, 'events', canRead && !unavailable, active, documentEpoch, revision);
  const operations = useAuditWindow(task, identity, 'operations', canRead && !unavailable, active, documentEpoch, revision);
  const [expanded, setExpanded] = useState<{scope: string; keys: Set<string | number>}>({scope: '', keys: new Set()});
  const [target, setTarget] = useState<{scope: string; record: ProcessRecord; originalPhone?: true} | null>(null);
  const [drill, setDrill] = useState<{scope: string; section: AuditSection} | null>(null);
  const [locateMiss, setLocateMiss] = useState(''), surface = useRef<HTMLElement>(null), focusReturn = useRef(''), releaseReturn = useRef('');
  const backToCheckpoint = useCallback(() => {
    focusReturn.current = identity; setDrill(null); setLocateMiss(''); setSelectedTab('trace');
  }, [identity]);
  const fromCheckpoint = active && drill?.scope === identity && selectedTab === drill.section;
  useEffect(() => {
    if (!fromCheckpoint || !registerTransientBack) return;
    registerTransientBack(() => { backToCheckpoint(); return true; });
    return () => registerTransientBack(null);
  }, [fromCheckpoint, registerTransientBack, backToCheckpoint]);
  useLayoutEffect(() => {
    if (!focusReturn.current) return;
    const restore = focusReturn.current === identity && active && selectedTab === 'trace';
    focusReturn.current = '';
    if (!restore) return;
    const root = surface.current?.querySelector<HTMLElement>('.pw-process-checkpoint');
    const prior=releaseReturn.current;releaseReturn.current='';
    const source=root?.querySelector<HTMLElement>('[data-phone-release-return]');
    const control = prior ? (source?.dataset.phoneReleaseReturn===prior ? source : root?.querySelector<HTMLElement>('h3')) :
      root?.querySelector<HTMLElement>('button') ?? root?.querySelector<HTMLElement>('h3');
    control?.focus({preventScroll: true}); control?.scrollIntoView({block: 'nearest'});
  }, [active, identity, selectedTab]);
  useLayoutEffect(() => {
    if (!target) return;
    const current = () => active && target.scope === identity && selectedTab === target.record.section &&
      bridge.getDocumentEpoch() === documentEpoch && sourceRevision() === revision;
    const reveal = () => {
      const live = target.originalPhone ? bridge.getState() : undefined;
      const view = live?.view as {section?: unknown; taskId?: unknown} | undefined;
      if (current() && (!live || !document.hidden && view?.section == null && view?.taskId === task.id && live.capabilities.commands.includes('history.read'))) {
        const key = recordKey(target.record.runId, target.record.id);
        const row = [...(surface.current?.querySelectorAll<HTMLElement>('[data-audit-key]') ?? [])].find(el =>
          el.dataset.auditKey === key && el.dataset.auditSection === target.record.section);
        if (row) {
          const control = row.querySelector<HTMLElement>('[slot="trigger"], .ui-tcc__head') ?? row;
          control.focus({ preventScroll: true }); row.scrollIntoView({ block: 'nearest' });
        } else setLocateMiss(identity);
      }
      setTarget(previous => previous === target ? null : previous);
    };
    if (target.originalPhone && current()) {
      // Let the drawer restore its Process scroll before locating the existing tool row.
      const frame = requestAnimationFrame(reveal);
      return () => cancelAnimationFrame(frame);
    }
    reveal();
  }, [target, active, identity, selectedTab, documentEpoch, revision, task.id]);
  useLayoutEffect(() => {
    if (!registerOriginalPhoneTool) return;
    registerOriginalPhoneTool(pin => {
      if (active || unavailable || !canRead || !inOriginalTask || document.hidden || bridge.getDocumentEpoch() !== documentEpoch
        || sourceRevision() !== revision || !dequal(pin, originalPhoneToolRecord(task, pin.operationId))) return false;
      operations.showLatest();
      setDrill(null); setSelectedTab('operations');
      const here = operations.latest?.entries.some(row => row.operationId === pin.operationId && row.runId === pin.runId &&
        row.status === 'available' && row.tool === 'device_invoke_tool');
      setLocateMiss(here ? '' : identity);
      setTarget(here ? {scope: identity, originalPhone: true,
        record: {section: 'operations', id: pin.operationId, runId: pin.runId, locatable: true, label: '', body: ''}} : null);
      return true;
    });
    return () => registerOriginalPhoneTool(null);
  });
  if (unavailable) return <section className="pw-task-process" aria-label="执行过程"><p className="pw-muted">原任务来源已变化或暂不可核对，请回任务概览核对来源。</p></section>;

  const projected = (eventPage?: AuditPage, operationPage?: AuditPage) => ({ ...task, raw: { ...task.raw,
    historyEventPage: eventPage, historyOperationPage: operationPage,
    events: eventPage || events.seedInvalid ? undefined : task.raw?.events,
    operations: operationPage || operations.seedInvalid ? [] : task.raw?.operations } });
  const visibleTask = projected(events.page, operations.page), latestTask = projected(events.latest, operations.latest);
  const originalRead = active && inOriginalTask && canRead;
  const history = getTaskEventHistory(visibleTask, originalRead), tools = getTaskOperations(visibleTask);
  const resources = originalRead ? phoneResourceHistory(latestTask,events.latest) : undefined;
  const shownResources = events.page===events.latest ? resources : originalRead ? phoneResourceHistory(visibleTask,events.page) : undefined;
  // Keep an original release entry readable when its native body carries no free-text message.
  if(shownResources)history.entries=history.entries.map(entry=>{
    const fact=entry.isCurrentRun&&entry.bodyAvailable&&entry.hasRecordedId?shownResources.records.get(entry.id):undefined;
    return fact?{...entry,title:fact.label,detail:fact.body,fullDetail:undefined}:entry;
  });
  const checkpoint = active && selectedTab === 'trace' ? processCheckpoint(latestTask, events.page === events.latest ? history.entries : getTaskEventHistory(latestTask, originalRead).entries,
    operations.page === operations.latest ? tools : getTaskOperations(latestTask),
    inOriginalTask && canRead ? phoneStopReceipt(latestTask, events.latest, operations.latest) : undefined) : null;
  const locate = (record: ProcessRecord, releaseSource = false) => {
    if (!active || record.runId !== task.raw?.runId) return;
    releaseReturn.current=releaseSource?recordKey(record.runId,record.id):'';
    if (record.section === 'events') {
      events.showLatest();
      setExpanded({scope: identity, keys: new Set([recordKey(record.runId, record.id)])});
    } else operations.showLatest();
    setLocateMiss(''); setDrill({scope: identity, section: record.section}); setSelectedTab(record.section); setTarget({scope: identity, record});
  };
  const brief = checkpoint && <section className="pw-process-checkpoint" aria-label="当前执行进展">
    <header><span className="pw-process-eyebrow">当前执行</span><h3 tabIndex={-1} aria-live="polite" aria-atomic="true">{checkpoint.state}</h3></header>
    {checkpoint.note && <p dir="auto">{checkpoint.note}</p>}
    {checkpoint.record && <div className="pw-checkpoint-evidence">
      <small>{checkpoint.record.label}</small><p dir="auto">{checkpoint.record.body}</p>
      {checkpoint.record.locatable && <Button variant="ghost" trailingIcon={IconChevronRight} onClick={() => locate(checkpoint.record!)}>定位原记录</Button>}
    </div>}
    {checkpoint.lines?.map((line,index) => <p key={index} dir="auto">{line}</p>)}
    {resources?.stored&&resources.stored.body!==resources.event?.body&&<p><strong>独立屏留存记录：</strong>{resources.stored.body}</p>}
    {resources?.event&&!(checkpoint.record?.section==='events'&&checkpoint.record.id===resources.event.id&&checkpoint.record.body===resources.event.body)&&<div className="pw-checkpoint-evidence"><small>{resources.event.label}</small><p>{resources.event.body}</p>
      {(checkpoint.record?.section!=='events'||checkpoint.record.id!==resources.event.id)&&<Button variant="ghost" trailingIcon={IconChevronRight} data-phone-release-return={recordKey(resources.event.runId,resources.event.id)} onClick={()=>locate(resources.event!,true)}>查看这笔清理记录</Button>}</div>}
  </section>;
  const navigation = (window: ReturnType<typeof useAuditWindow>, label: string) => <>
    {window.error && <p className="pw-error" role="alert">{window.error}</p>}
    <div className="pw-action-row pw-audit-navigation" aria-label={label + '翻页'}>
      {window.page && <span className="pw-muted pw-history-window">{window.earlier ? '较早记录' : '最近已读记录'}{window.page.hasMore ? ' · 尚有更早记录' : ' · 已到当前读取范围末尾'}</span>}
      <span className="pw-grow"/>
      {window.canGoBack && <IconButton icon={IconArrowLeft} aria-label="较近记录" title="较近记录" disabled={window.busy || !canRead} onClick={window.newer}/>}
      {window.page?.hasMore && <IconButton icon={IconArrowRight} aria-label="较早记录" title="较早记录" disabled={window.busy || !canRead} onClick={window.older}/>}
      {canRead && (window.page ? <IconButton icon={IconRefresh} aria-label="重读最近记录" title="重读最近记录" disabled={window.busy} onClick={window.refresh}/> : <Button variant="ghost" leadingIcon={IconRefresh} disabled={window.busy} onClick={window.refresh}>读取原记录</Button>)}
    </div>
    {window.newEntries && <p className="pw-muted" role="status">这件工作已有新记录。当前仍保留较早位置，可重读最近记录。</p>}
    {window.seedInvalid && !window.page && !window.error && <p className="pw-muted">原记录的读取范围无法核对。</p>}
    {window.busy && <p className="pw-muted" role="status">正在读取{label}…</p>}
    {window.page?.readLimited === true && <p className="pw-muted">部分正文超出当前读取范围。</p>}
  </>;
  return <section ref={surface} className="pw-task-process" aria-label="执行过程">
    <Tabs className="pw-task-history-tabs" selectedKey={selectedTab} onSelectionChange={key => { if (key !== selectedTab) { setDrill(null); setLocateMiss(''); setSelectedTab(String(key)); } }}>
      <TabList aria-label="执行记录类型"><Tab id="trace">轨迹</Tab><Tab id="events" count={history.entries.length}>进展记录</Tab><Tab id="operations" count={tools.length}>工具调用</Tab></TabList>
      {fromCheckpoint && <Button variant="ghost" className="pw-audit-return" leadingIcon={IconArrowLeft} onClick={backToCheckpoint}>返回当前进展</Button>}
      {selectedTab !== 'trace' && <p className="pw-audit-caption">数量仅对应当前已读取的记录。{locateMiss === identity && <span role="status">原记录已变化，请在当前窗口重新核对。</span>}</p>}
      <TabPanel id="trace" shouldForceMount><TaskTrajectory task={task} currentCheckpoint={brief} active={active && selectedTab === 'trace'} canRead={canReadTrace} registerTransientBack={registerTransientBack}/></TabPanel>
      <TabPanel id="events" shouldForceMount><section className="pw-task-events" aria-label="任务记录">
      {history.partial && <p className="pw-muted pw-task-history-limit">当前为部分记录，完整过程尚未载入。</p>}
      {history.entries.length ? <Accordion variant="divided" className="pw-task-event-list" aria-label="已读取任务记录" expandedKeys={expanded.scope === identity ? expanded.keys : []} onExpandedChange={keys => setExpanded({scope: identity, keys})}>{history.entries.map((event, index) => {
        const verdict = event.ownerGoalVerdict !== undefined;
        return <AccordionItem key={event.id + ':' + index} id={recordKey(event.runId, event.id)} data-audit-key={recordKey(event.runId, event.id)} data-audit-section="events" className="pw-task-event" data-event-position={event.position} data-read-limited={event.readLimited || undefined} headingLevel={4}
          title={verdict ? event.stateLabel : event.bodyAvailable && event.detail !== event.stateLabel ? event.detail : event.title}
          description={[event.title, verdict ? '' : event.stateLabel, time(event.at), event.runId && task.raw?.runId && !event.isCurrentRun ? '早先执行记录' : ''].filter(Boolean).join(' · ')}>
          <p className="pw-task-event-copy" dir="auto">{expanded.scope === identity && expanded.keys.has(recordKey(event.runId, event.id)) ? event.fullDetail ?? event.detail : event.detail}</p>
        </AccordionItem>;
      })}</Accordion> : <p className="pw-muted">当前窗口没有可读取的任务记录。</p>}
      {navigation(events, '任务记录')}
    </section></TabPanel>
      <TabPanel id="operations" shouldForceMount><section className="pw-task-operations" aria-label="工具记录">
      {tools.length ? <div className="pw-task-tools">{tools.map((operation, index) => {
        const original = originalRead && selectedTab === 'operations' && bridge.getState().capabilities.commands.includes('devices.queryHistory')
          ? originalWindowsTextAt(task, operation) : undefined;
        return <div key={recordKey(operation.runId, operation.operationId)} data-audit-section="operations" data-audit-key={recordKey(operation.runId, operation.operationId)} tabIndex={-1}>
          {operation.runId && task.raw?.runId && operation.runId !== task.raw.runId && <small className="pw-muted">早先执行记录</small>}{renderOperation(operation, index, task.id)}
          {original && <OriginalWindowsTextRead key={JSON.stringify([identity, original])} original={original} documentEpoch={documentEpoch} sourceRevision={revision}/>}
        </div>;
      })}</div> : <p className="pw-muted pw-task-tool-empty">当前窗口没有可读取的工具记录。</p>}
      {navigation(operations, '工具记录')}
    </section></TabPanel>
    </Tabs>
  </section>;
}
