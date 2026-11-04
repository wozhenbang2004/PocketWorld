import { useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Button } from './forms-library';
import { currentTaskHeaders, taskHeaderPage, type TaskHeader, type TaskHeaderPage } from './task-history';

type Window = { scope: string; cursor: string; prior: string[]; page: TaskHeaderPage };

/** Keep one bounded history window; current task projections remain the live authority. */
export function useTaskListHistory(tasks: TaskHeader[], initialCursor: string, scope: string, enabled: boolean) {
  const [loaded, setLoaded] = useState<Window | null>(null), [busy, setBusy] = useState(false), [error, setError] = useState('');
  const alive = useRef(true), request = useRef(0), entering = useRef(false), current = useRef({ scope, enabled });
  current.current = { scope, enabled };
  useEffect(() => { alive.current = true; return () => { alive.current = false; request.current++; }; }, []);
  const reset = () => { request.current++; entering.current = false; setLoaded(null); setBusy(false); setError(''); };
  useEffect(reset, [scope, enabled]);
  const read = async (cursor: string, prior: string[]) => {
    if (!enabled || entering.current) return;
    entering.current = true; const original = ++request.current; setBusy(true); setError('');
    const valid = () => alive.current && request.current === original && current.current.scope === scope && current.current.enabled;
    try {
      const result = await bridge.command('history.read', { section: 'tasks', cursor });
      if (valid()) setLoaded({ scope, cursor, prior, page: taskHeaderPage(result, cursor, prior) });
    } catch (failure) { if (valid()) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { if (valid()) { entering.current = false; setBusy(false); } }
  };
  const observed = loaded?.scope === scope && enabled ? loaded : null;
  const cursor = observed ? observed.page.nextCursor : initialCursor;
  const hasMore = observed ? observed.page.hasMore : Boolean(initialCursor);
  const older = () => { if (hasMore && cursor) void read(cursor, [...(observed?.prior || []), observed?.cursor || '']); };
  const newer = () => {
    if (!observed?.prior.length) return;
    const previous = observed.prior.at(-1)!;
    if (!previous) reset(); else void read(previous, observed.prior.slice(0, -1));
  };
  return { tasks: observed ? currentTaskHeaders(observed.page.tasks, tasks) : currentTaskHeaders(tasks, tasks), busy, error, hasMore,
    earlier: observed !== null, canGoBack: Boolean(observed?.prior.length), older, newer, reset, enabled };
}

export function TaskListHistoryControls({ history }: { history: ReturnType<typeof useTaskListHistory> }) {
  return <section className="pw-task-history-navigation" aria-label="任务列表翻页">
    {history.error && <p className="pw-error" role="alert">{history.error}</p>}
    {history.busy && <p className="pw-muted" role="status">正在读取原任务窗口…</p>}
    {(history.earlier || history.hasMore) && <p className="pw-muted">{history.earlier ? '当前显示较早的任务窗口。' : '这里只显示最近读取的任务。'}搜索和筛选作用于当前窗口。</p>}
    <div className="pw-action-row">
      {history.canGoBack && <Button variant="ghost" disabled={history.busy || !history.enabled} onClick={history.newer}>较近任务</Button>}
      {history.hasMore && <Button variant="secondary" disabled={history.busy || !history.enabled} onClick={history.older}>较早任务</Button>}
      {history.earlier && <Button variant="ghost" disabled={history.busy} onClick={history.reset}>回到最近任务</Button>}
    </div>
  </section>;
}
