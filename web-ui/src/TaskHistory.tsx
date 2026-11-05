import { useEffect, useRef, useState, type ReactNode } from 'react';
import { bridge } from './bridge';
import { Tabs, TabList, Tab, TabPanel } from './shell-library';
import './task-process.css';
import { TaskTrajectory } from './TaskTrajectory';
import { Accordion, AccordionItem, Button, IconButton } from './forms-library';
import { RiArrowLeftLine, RiArrowRightLine, RiRefreshLine } from '@remixicon/react';
import { getTaskEventHistory, getTaskOperations } from './task-presentation';
import { taskAuditPage, taskHistoryIdentity, type AuditPage, type AuditSection, type TaskHeader } from './task-history';

type Window = { identity: string; cursor: string; prior: string[]; page: AuditPage };
const message = (failure: unknown) => failure instanceof Error ? failure.message : String(failure);
const time = (value?: number) => typeof value === 'number' && value > 0 ? new Date(value).toLocaleString('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '';

function useAuditWindow(task: TaskHeader, section: AuditSection, enabled: boolean) {
  const identity = taskHistoryIdentity(task);
  const [loaded, setLoaded] = useState<Window | null>(null), [busy, setBusy] = useState(false), [error, setError] = useState('');
  const alive = useRef(true), request = useRef(0), entering = useRef(false);
  const current = useRef({ identity, enabled }); current.current = { identity, enabled };
  useEffect(() => { alive.current = true; return () => { alive.current = false; request.current++; }; }, []);
  useEffect(() => { request.current++; entering.current = false; setLoaded(null); setBusy(false); setError(''); }, [identity, enabled]);
  const raw = task.raw || {}, key = section === 'events' ? 'historyEventPage' : 'historyOperationPage';
  let seed: AuditPage | null = null;
  try { if (raw[key]) seed = taskAuditPage(raw[key], task.id, section); } catch { /* The explicit reread below can recover a stale or unavailable projection. */ }
  const observed = loaded?.identity === identity && enabled ? loaded : null;
  const view = observed?.page || seed;
  const read = async (cursor: string, prior: string[], upper?: number) => {
    if (!enabled || entering.current) return;
    entering.current = true; const original = ++request.current; setBusy(true); setError('');
    const valid = () => alive.current && original === request.current && current.current.identity === identity && current.current.enabled;
    try {
      const result = await bridge.command('history.read', { taskId: task.id, section, cursor });
      if (!valid()) return;
      setLoaded({ identity, cursor, prior, page: taskAuditPage(result, task.id, section, cursor, prior, upper) });
    } catch (failure) { if (valid()) setError(message(failure)); }
    finally { if (valid()) { entering.current = false; setBusy(false); } }
  };
  const older = () => { if (view?.hasMore) void read(view.nextCursor, [...(observed?.prior || []), observed?.cursor || ''], view.upperPosition); };
  const newer = () => {
    if (!observed?.prior.length) return;
    const cursor = observed.prior.at(-1)!;
    void read(cursor, observed.prior.slice(0, -1), cursor ? view?.upperPosition : undefined);
  };
  return { page: view, busy, error, older, newer, refresh: () => { void read('', []); }, earlier: Boolean(observed?.cursor), canGoBack: Boolean(observed?.prior.length) };
}

/** The adopted task disclosures now navigate their original bounded audit windows. */
export function TaskHistory({ task, canRead, canReadTrace = false, active = true, registerTransientBack, renderOperation }: {
  task: TaskHeader; canRead: boolean; canReadTrace?: boolean; active?: boolean;
  registerTransientBack?: (handler: (() => boolean) | null) => void;
  renderOperation: (operation: unknown, index: number, scope: string) => ReactNode;
}) {
  const [selectedTab, setSelectedTab] = useState('trace');
  const unavailable = task.raw?.sourceCurrentReadRequired === true || task.raw?.taskProjection === 'source_unavailable';
  const events = useAuditWindow(task, 'events', canRead && !unavailable), operations = useAuditWindow(task, 'operations', canRead && !unavailable);
  if (unavailable) return <section className="pw-task-process" aria-label="执行过程"><p className="pw-muted">原任务来源已变化或暂不可核对，请回任务概览核对来源。</p></section>;
  const eventTask = events.page ? { ...task, raw: { ...task.raw, historyEventPage: events.page } } : task;
  const operationTask = operations.page ? { ...task, raw: { ...task.raw, historyOperationPage: operations.page, operations: [] } } : task;
  const history = getTaskEventHistory(eventTask), tools = getTaskOperations(operationTask);
  const navigation = (window: ReturnType<typeof useAuditWindow>, label: string) => <>
    {window.error && <p className="pw-error" role="alert">{window.error}</p>}
    <div className="pw-action-row pw-audit-navigation" aria-label={label + '翻页'}>
      {window.page && <span className="pw-muted pw-history-window">{window.earlier ? '较早记录' : '最近记录'}{window.page.hasMore ? ' · 尚有更早记录' : ' · 已到当前读取范围末尾'}</span>}
      <span className="pw-grow"/>
      {window.canGoBack && <IconButton icon={RiArrowLeftLine} aria-label="较近记录" title="较近记录" disabled={window.busy || !canRead} onClick={window.newer}/>}
      {window.page?.hasMore && <IconButton icon={RiArrowRightLine} aria-label="较早记录" title="较早记录" disabled={window.busy || !canRead} onClick={window.older}/>}
      {canRead && (window.page ? <IconButton icon={RiRefreshLine} aria-label="重读最近记录" title="重读最近记录" disabled={window.busy} onClick={window.refresh}/> : <Button variant="ghost" leadingIcon={RiRefreshLine} disabled={window.busy} onClick={window.refresh}>读取原记录</Button>)}
    </div>
    {window.busy && <p className="pw-muted" role="status">正在读取{label}…</p>}
    {window.page?.readLimited === true && <p className="pw-muted">部分正文超出当前读取范围。</p>}
  </>;
  return <section className="pw-task-process" aria-label="执行过程">
    <Tabs className="pw-task-history-tabs" selectedKey={selectedTab} onSelectionChange={key => setSelectedTab(String(key))}>
      <TabList aria-label="执行记录类型"><Tab id="trace">轨迹</Tab><Tab id="events" count={history.entries.length}>进展记录</Tab><Tab id="operations" count={tools.length}>工具调用</Tab></TabList>
      {selectedTab !== 'trace' && <p className="pw-audit-caption">数量仅对应当前已读取的记录。</p>}
      <TabPanel id="trace" shouldForceMount><TaskTrajectory task={task} active={active && selectedTab === 'trace'} canRead={canReadTrace} registerTransientBack={registerTransientBack}/></TabPanel>
      <TabPanel id="events" shouldForceMount><section className="pw-task-events" aria-label="任务记录">
      {history.partial && <p className="pw-muted pw-task-history-limit">当前为部分记录，完整过程尚未载入。</p>}
      {history.entries.length ? <Accordion variant="divided" className="pw-task-event-list" aria-label="已读取任务记录">{history.entries.map((event, index) => <AccordionItem key={`${event.id}:${index}`} id={`${task.id}:${event.id}:${index}`} className="pw-task-event" data-event-position={event.position} data-read-limited={event.readLimited || undefined} headingLevel={4} title={event.bodyAvailable && event.detail !== event.stateLabel ? event.detail : event.title} description={[event.title, event.stateLabel, time(event.at), event.runId && !event.isCurrentRun ? '早先执行记录' : ''].filter(Boolean).join(' · ')}><p className="pw-task-event-copy">{event.detail}</p>{event.partial && event.bodyAvailable && <p className="pw-muted">这条记录仅显示已读取部分。</p>}</AccordionItem>)}</Accordion> : <p className="pw-muted">当前窗口没有可读取的任务记录。</p>}
      {navigation(events, '任务记录')}
    </section></TabPanel>
      <TabPanel id="operations" shouldForceMount><section className="pw-task-operations" aria-label="工具记录">
      {tools.length ? <div className="pw-task-tools">{tools.map((operation, index) => <div key={`${operation.runId}:${operation.operationId}`}>
        {operation.runId && task.raw?.runId && operation.runId !== task.raw.runId && <small className="pw-muted">早先执行记录</small>}{renderOperation(operation, index, task.id)}
      </div>)}</div> : <p className="pw-muted pw-task-tool-empty">当前窗口没有可读取的工具记录。</p>}
      {navigation(operations, '工具记录')}
    </section></TabPanel>
    </Tabs>
  </section>;
}
