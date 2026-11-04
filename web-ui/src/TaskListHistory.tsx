import { useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Button, IconButton } from './forms-library';
import { RiArrowLeftLine, RiArrowRightLine, RiSkipBackLine } from '@remixicon/react';
import { currentTaskHeaders, taskHeaderPage, type TaskHeader, type TaskHeaderPage } from './task-history';

type Window = { scope: string; cursor: string; prior: string[]; page: TaskHeaderPage };

/** Keep one bounded history window; current task projections remain the live authority. */
export function useTaskListHistory(tasks: TaskHeader[], initialCursor: string, scope: string, enabled: boolean) {
  const [loaded, setLoaded] = useState<Window | null>(null), [busy, setBusy] = useState(false), [error, setError] = useState('');
  const alive = useRef(true), request = useRef(0), entering = useRef(false), current = useRef({ scope, enabled });
  current.current = { scope, enabled };
  useEffect(() => { alive.current = true; return () => { alive.current = false; request.current++; }; }, []);
  const reset = () => { request.current++; entering.current = false; setLoaded(null); setBusy(false); setError(''); };
  useEffect(reset, [scope]);
  // Leaving the route retires pending reads, not the last settled history page.
  // Returning to the same scope requires no new read or business command.
  useEffect(() => { request.current++; entering.current = false; setBusy(false); setError(''); }, [enabled]);
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
    {history.busy && <p className="pw-muted" role="status">正在读取任务…</p>}
    {(history.earlier || history.hasMore) && <div className="pw-action-row">
      <span className="pw-muted pw-history-window">{history.earlier ? '较早任务' : '最近任务'} · 仅搜索当前页</span>
      <span className="pw-grow"/>
      {history.canGoBack && <Button variant="ghost" leadingIcon={RiArrowLeftLine} disabled={history.busy || !history.enabled} onClick={history.newer}>较近任务</Button>}
      {history.hasMore && <Button variant="ghost" trailingIcon={RiArrowRightLine} disabled={history.busy || !history.enabled} onClick={history.older}>较早任务</Button>}
      {history.earlier && <IconButton icon={RiSkipBackLine} aria-label="回到最近任务" title="回到最近任务" disabled={history.busy} onClick={history.reset}/>}
    </div>}
  </section>;
}
