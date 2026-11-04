import { useEffect, useRef, useState, type ReactNode } from 'react';
import { bridge } from './bridge';
import { Accordion, AccordionItem, Button } from './forms-library';
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
export function TaskHistory({ task, canRead, renderOperation }: { task: TaskHeader; canRead: boolean; renderOperation: (operation: unknown, index: number, scope: string) => ReactNode }) {
  const unavailable = task.raw?.sourceCurrentReadRequired === true || task.raw?.taskProjection === 'source_unavailable';
  const events = useAuditWindow(task, 'events', canRead && !unavailable), operations = useAuditWindow(task, 'operations', canRead && !unavailable);
  if (unavailable) return <section className="pw-task-process" aria-label="执行过程"><p className="pw-muted">原任务来源已变化或暂不可核对，请回任务概览核对来源。</p></section>;
  const eventTask = events.page ? { ...task, raw: { ...task.raw, historyEventPage: events.page } } : task;
  const operationTask = operations.page ? { ...task, raw: { ...task.raw, historyOperationPage: operations.page, operations: [] } } : task;
  const history = getTaskEventHistory(eventTask), tools = getTaskOperations(operationTask);
  const navigation = (window: ReturnType<typeof useAuditWindow>, label: string) => <>
    {window.error && <p className="pw-error" role="alert">{window.error}</p>}
    <div className="pw-action-row" aria-label={label + '翻页'}>
      {window.canGoBack && <Button variant="ghost" size="small" disabled={window.busy || !canRead} onClick={window.newer}>较近记录</Button>}
      {window.page?.hasMore && <Button variant="ghost" size="small" disabled={window.busy || !canRead} onClick={window.older}>较早记录</Button>}
      {canRead && <Button variant="ghost" size="small" disabled={window.busy} onClick={window.refresh}>{window.page ? '重读最近记录' : '读取原记录'}</Button>}
    </div>
    {window.busy && <p className="pw-muted" role="status">正在读取{label}…</p>}
    {window.page && <p className="pw-muted">{window.earlier ? '当前为较早的记录窗口。' : '当前为最近的记录窗口。'}{window.page.hasMore ? '可继续读取较早记录。' : '已到这次读取范围的末尾。'}{window.page.readLimited === true && '部分正文超出当前读取范围。'}</p>}
  </>;
  return <section className="pw-task-process" aria-label="执行过程">
    <section className="pw-task-events" aria-label="任务记录"><h3 className="pw-task-history-heading">任务记录</h3>
      {history.partial && <p className="pw-muted pw-task-history-limit">这里只显示当前已读取的记录窗口，不能据此判断完整过程。</p>}
      {history.entries.length ? <Accordion variant="divided" className="pw-task-event-list" aria-label="已读取任务记录">{history.entries.map((event, index) => <AccordionItem key={`${event.id}:${index}`} id={`${task.id}:${event.id}:${index}`} className="pw-task-event" data-event-position={event.position} data-read-limited={event.readLimited || undefined} headingLevel={4} title={event.title} description={[event.stateLabel, time(event.at), event.runId && !event.isCurrentRun ? '早先执行记录' : ''].filter(Boolean).join(' · ')}><p className="pw-task-event-copy">{event.detail}</p>{event.partial && event.bodyAvailable && <p className="pw-muted">当前只保留这条记录的已读部分。</p>}</AccordionItem>)}</Accordion> : <p className="pw-muted">当前窗口没有可读取的任务记录。</p>}
      {navigation(events, '任务记录')}
    </section>
    <section className="pw-task-operations" aria-label="工具记录"><h3 className="pw-task-history-heading">工具记录</h3>
      {tools.length ? <div className="pw-task-tools">{tools.map((operation, index) => <div key={`${operation.runId}:${operation.operationId}`}>
        {operation.runId && task.raw?.runId && operation.runId !== task.raw.runId && <small className="pw-muted">早先执行记录</small>}{renderOperation(operation, index, task.id)}
      </div>)}</div> : <p className="pw-muted pw-task-tool-empty">当前窗口没有可读取的工具记录。</p>}
      {navigation(operations, '工具记录')}
    </section>
  </section>;
}
