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
import { getTaskEventHistory, getTaskOperations } from './task-presentation';
import { taskAuditPage, taskHistoryIdentity, type AuditPage, type AuditSection, type TaskHeader } from './task-history';

type Window = { identity: string; cursor: string; prior: string[]; page: AuditPage };
const message = (failure: unknown) => failure instanceof Error ? failure.message : String(failure);
const time = (value?: number) => typeof value === 'number' && value > 0 ? new Date(value).toLocaleString('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '';

type Head = { identity: string; page: AuditPage; seed?: AuditPage; headerStamp: string };
const sourceRevision = () => String(bridge.getState().state.traceSourceRevision ?? 0);
const recordKey = (run: string, id: string) => JSON.stringify([run, id]);

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
export function TaskHistory({ task, canRead, canReadTrace = false, active = true, registerTransientBack, renderOperation }: {
  task: TaskHeader; canRead: boolean; canReadTrace?: boolean; active?: boolean;
  registerTransientBack?: (handler: (() => boolean) | null) => void;
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
  const [target, setTarget] = useState<{scope: string; record: ProcessRecord} | null>(null);
  const [drill, setDrill] = useState<{scope: string; section: AuditSection} | null>(null);
  const [locateMiss, setLocateMiss] = useState(''), surface = useRef<HTMLElement>(null), focusReturn = useRef('');
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
    const control = root?.querySelector<HTMLElement>('button') ?? root?.querySelector<HTMLElement>('h3');
    control?.focus({preventScroll: true}); control?.scrollIntoView({block: 'nearest'});
  }, [active, identity, selectedTab]);
  useLayoutEffect(() => {
    if (!target) return;
    if (!active || target.scope !== identity || selectedTab !== target.record.section || bridge.getDocumentEpoch() !== documentEpoch || sourceRevision() !== revision) { setTarget(null); return; }
    const key = recordKey(target.record.runId, target.record.id);
    const row = [...(surface.current?.querySelectorAll<HTMLElement>('[data-audit-key]') ?? [])].find(el =>
      el.dataset.auditKey === key && el.dataset.auditSection === target.record.section);
    if (row) {
      const control = row.querySelector<HTMLElement>('[slot="trigger"], .ui-tcc__head') ?? row;
      control.focus({ preventScroll: true }); row.scrollIntoView({ block: 'nearest' });
    } else setLocateMiss(identity);
    setTarget(null);
  }, [target, active, identity, selectedTab, documentEpoch, revision]);
  if (unavailable) return <section className="pw-task-process" aria-label="执行过程"><p className="pw-muted">原任务来源已变化或暂不可核对，请回任务概览核对来源。</p></section>;

  const projected = (eventPage?: AuditPage, operationPage?: AuditPage) => ({ ...task, raw: { ...task.raw,
    historyEventPage: eventPage, historyOperationPage: operationPage,
    events: eventPage || events.seedInvalid ? undefined : task.raw?.events,
    operations: operationPage || operations.seedInvalid ? [] : task.raw?.operations } });
  const visibleTask = projected(events.page, operations.page), latestTask = projected(events.latest, operations.latest);
  const history = getTaskEventHistory(visibleTask), tools = getTaskOperations(visibleTask);
  const checkpoint = active && selectedTab === 'trace' ? processCheckpoint(latestTask, events.page === events.latest ? history.entries : getTaskEventHistory(latestTask).entries,
    operations.page === operations.latest ? tools : getTaskOperations(latestTask),
    inOriginalTask && canRead ? phoneStopReceipt(latestTask, events.latest, operations.latest) : undefined) : null;
  const locate = (record: ProcessRecord) => {
    if (!active || record.runId !== task.raw?.runId) return;
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
      {history.entries.length ? <Accordion variant="divided" className="pw-task-event-list" aria-label="已读取任务记录" expandedKeys={expanded.scope === identity ? expanded.keys : []} onExpandedChange={keys => setExpanded({scope: identity, keys})}>{history.entries.map((event, index) => <AccordionItem key={event.id + ':' + index} id={recordKey(event.runId, event.id)} data-audit-key={recordKey(event.runId, event.id)} data-audit-section="events" className="pw-task-event" data-event-position={event.position} data-read-limited={event.readLimited || undefined} headingLevel={4} title={event.bodyAvailable && event.detail !== event.stateLabel ? event.detail : event.title} description={[event.title, event.stateLabel, time(event.at), event.runId && task.raw?.runId && !event.isCurrentRun ? '早先执行记录' : ''].filter(Boolean).join(' · ')}><p className="pw-task-event-copy" dir="auto">{expanded.scope === identity && expanded.keys.has(recordKey(event.runId, event.id)) ? event.fullDetail ?? event.detail : event.detail}</p></AccordionItem>)}</Accordion> : <p className="pw-muted">当前窗口没有可读取的任务记录。</p>}
      {navigation(events, '任务记录')}
    </section></TabPanel>
      <TabPanel id="operations" shouldForceMount><section className="pw-task-operations" aria-label="工具记录">
      {tools.length ? <div className="pw-task-tools">{tools.map((operation, index) => <div key={recordKey(operation.runId, operation.operationId)} data-audit-section="operations" data-audit-key={recordKey(operation.runId, operation.operationId)} tabIndex={-1}>
        {operation.runId && task.raw?.runId && operation.runId !== task.raw.runId && <small className="pw-muted">早先执行记录</small>}{renderOperation(operation, index, task.id)}
      </div>)}</div> : <p className="pw-muted pw-task-tool-empty">当前窗口没有可读取的工具记录。</p>}
      {navigation(operations, '工具记录')}
    </section></TabPanel>
    </Tabs>
  </section>;
}
