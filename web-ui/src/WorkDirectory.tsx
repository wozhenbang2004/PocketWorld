import { useCallback, useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react';
import { bridge } from './bridge';
import { Button } from './forms-library';
import { currentTaskHeaders, taskHeaderPage, type TaskHeader, type TaskHeaderPage } from './task-history';
import { matchesWorkFilter, type WorkCollectionFilter } from './work-collection';

type DirectoryWindow = TaskHeaderPage & { key: string; identity: string; pages: number; cursors: string[]; unavailableSources: Set<string> };
const normalized = (value: string) => value.normalize('NFKC').toLocaleLowerCase('en-US').trim();
const sourceUnavailable = (task: TaskHeader) => task.raw?.taskProjection === 'source_unavailable' || task.raw?.sourceCurrentReadRequired === true;

/** A view over the original private task directory, with no execution or new index.
 * The host binds each cursor to the query and state; current projections can retire
 * old source text and move an already loaded row to its current group. */
export function useWorkDirectory({ tasks, query, filter, enabled, identity, scrollRef }: {
  tasks: TaskHeader[]; query: string; filter: WorkCollectionFilter; enabled: boolean;
  identity: string; scrollRef: RefObject<HTMLElement | null>;
}) {
  const key = JSON.stringify([query.trim(), filter]);
  const [loaded, setLoaded] = useState<DirectoryWindow | null>(null);
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  const [refresh, setRefresh] = useState(0), [visible, setVisible] = useState(() => !document.hidden);
  const current = useRef({ key, identity, enabled, query: query.trim(), filter });
  current.current = { key, identity, enabled, query: query.trim(), filter };
  const loadedRef = useRef(loaded); loadedRef.current = loaded;
  const alive = useRef(true), generation = useRef(0), pending = useRef(false);
  const positions = useRef(new Map<string, number>()), depths = useRef(new Map<string, number>()), restored = useRef(false);

  const read = useCallback(async (append = false) => {
    const expected = { ...current.current };
    if (!expected.enabled || document.hidden || pending.current) return;
    const previous = loadedRef.current?.key === expected.key ? loadedRef.current : null;
    if (append && (!previous || previous.identity !== expected.identity || !previous.nextCursor)) return;
    const request = ++generation.current, documentEpoch = bridge.getDocumentEpoch();
    const accepts = () => alive.current && request === generation.current && !document.hidden &&
      current.current.enabled && current.current.key === expected.key && current.current.identity === expected.identity &&
      bridge.getDocumentEpoch() === documentEpoch;
    pending.current = true; setBusy(true); setError('');
    try {
      let cursor = append ? previous!.nextCursor : '';
      let rows = append ? previous!.tasks : [];
      let cursors = append ? [...previous!.cursors] : [];
      let pageCount = append ? previous!.pages : 0;
      const count = append ? 1 : Math.max(1, depths.current.get(expected.key) || 0, previous?.pages || 0);
      depths.current.set(expected.key, append ? previous!.pages + 1 : count);
      const unavailableSources = new Set(previous?.unavailableSources);
      for (let index = 0; index < count; index++) {
        const reply = await bridge.command('work.tasks.list', { query: expected.query, filter: expected.filter, cursor, limit: 30 });
        if (!accepts()) return;
        if (reply?.format !== 'pocketworld.work-directory.v1' || reply.query !== expected.query || reply.filter !== expected.filter || reply.executionAuthorized !== false)
          throw new Error('工作目录的范围已变化，请重新读取。');
        const page = taskHeaderPage(reply, cursor, cursors);
        if (!Array.isArray(reply.sourceUnavailableTaskIds) || reply.sourceUnavailableTaskIds.length > 200 ||
          reply.sourceUnavailableTaskIds.some((id: unknown) => typeof id !== 'string' || !id || id.length > 240))
          throw new Error('原工作来源状态尚未读完整，请重新读取。');
        for (const id of reply.sourceUnavailableTaskIds) unavailableSources.add(id);
        for (const task of page.tasks) if (!sourceUnavailable(task)) unavailableSources.delete(task.id);
        rows = [...new Map([...rows, ...page.tasks].map(task => [task.id, task])).values()];
        cursors = [...cursors, cursor]; pageCount++;
        const result = { ...page, tasks: rows, key: expected.key, identity: expected.identity, pages: pageCount, cursors, unavailableSources: new Set(unavailableSources) };
        // A later page may fail. Retain every newly verified page immediately,
        // including source withdrawal or changed membership, instead of keeping
        // an old title/body that this read has already superseded.
        loadedRef.current = result; setLoaded(result);
        cursor = page.nextCursor;
        if (!page.hasMore) break;
      }
      if (accepts()) depths.current.set(expected.key, pageCount);
    } catch (failure) {
      if (accepts()) setError(failure instanceof Error ? failure.message : '工作目录暂时不可读，请重试。');
    } finally {
      if (request === generation.current) { pending.current = false; if (alive.current) setBusy(false); }
    }
  }, []);

  useEffect(() => { alive.current = true; return () => { alive.current = false; generation.current++; pending.current = false; }; }, []);
  useEffect(() => {
    const visibility = () => {
      generation.current++; pending.current = false; setBusy(false);
      setVisible(!document.hidden);
      if (!document.hidden) setRefresh(value => value + 1);
    };
    document.addEventListener('visibilitychange', visibility);
    return () => document.removeEventListener('visibilitychange', visibility);
  }, []);
  useEffect(() => {
    generation.current++; pending.current = false; restored.current = false; setError('');
    setBusy(enabled && visible);
    if (!enabled || !visible) return;
    const timer = window.setTimeout(() => { void read(); }, query.trim() ? 220 : 0);
    return () => { window.clearTimeout(timer); generation.current++; pending.current = false; };
  }, [key, identity, enabled, visible, refresh, read]);

  const observed = enabled && visible && loaded?.key === key && loaded.identity === identity ? loaded : null;
  const search = normalized(query);
  const originals = new Map(observed?.tasks.map(task => [task.id, task]));
  const currentRows = tasks.map(task => {
    const original = originals.get(task.id);
    // Source retirement does not change updatedAt. Either newly read native
    // projection may retire text; stale live content must never restore it.
    if (original && sourceUnavailable(original) && !sourceUnavailable(task)) return original;
    const prior = original?.raw, identity = prior?.workCollectionIdentity, raw = task.raw;
    if (!identity || !raw || raw.id !== task.id || identity.taskId !== task.id || identity.runId !== String(raw.runId || '') ||
      identity.epoch !== Number(raw.coordinatorEpoch || 0) || identity.updatedAt !== task.updatedAt || identity.status !== task.status ||
      identity.contextAvailable !== (raw.contextAvailable !== false)) return task;
    // The live row owns body, source and controls. At exactly the same version,
    // retain only the native distinction between an in-flight and unknown call.
    return { ...task, raw: { ...raw, workCollectionIdentity: identity,
      workCollectionGroup: prior?.workCollectionGroup, workCollectionReconciliation: prior?.workCollectionReconciliation } };
  });
  const privateTask = (task: TaskHeader) => task.raw?.id === task.id && task.raw.actorId === 'owner' && task.raw.spaceId === 'private' && task.raw.kind !== 'inference';
  const livePrivate = currentRows.filter(task => privateTask(task) && (!observed?.unavailableSources.has(task.id) || sourceUnavailable(task)));
  const safeOriginals = observed?.tasks.map(task => currentRows.find(live => live.id === task.id && sourceUnavailable(live)) || task) || [];
  const rows = observed ? currentTaskHeaders([...safeOriginals, ...livePrivate], livePrivate).filter(task =>
    (!observed.unavailableSources.has(task.id) || sourceUnavailable(task)) && privateTask(task) && matchesWorkFilter(task, filter) &&
    (!search || normalized(`${task.goal}\n${task.summary || ''}`).includes(search))) : [];
  useLayoutEffect(() => {
    if (!enabled || !observed || busy || error || restored.current || !scrollRef.current) return;
    scrollRef.current.scrollTop = positions.current.get(key) || 0;
    restored.current = true;
  }, [enabled, observed, busy, error, key, scrollRef]);
  const rememberScroll = (top: number) => { if (restored.current) positions.current.set(key, Math.max(0, top)); };
  const reset = () => {
    generation.current++; pending.current = false;
    // Keep the readable rows and requested depth while refreshing the same view.
    setRefresh(value => value + 1);
  };
  return { tasks: rows, busy, error, hasMore: Boolean(observed?.hasMore), loaded: Boolean(observed), enabled,
    loadMore: () => { void read(true); }, reset, rememberScroll };
}

export function WorkDirectoryControls({ directory }: { directory: ReturnType<typeof useWorkDirectory> }) {
  return <section className="pw-task-history-navigation" aria-label="工作目录读取">
    {directory.error && <div className="pw-task-directory-error" role="alert"><p className="pw-error">{directory.error}</p><Button variant="secondary" disabled={directory.busy} onClick={directory.reset}>重新读取</Button></div>}
    {directory.busy && <p className="pw-muted" role="status">{directory.loaded ? '正在读取原工作…' : '正在查找保存的工作…'}</p>}
    {directory.loaded && <div className="pw-action-row">
      <span className="pw-muted pw-history-window">已读取 {directory.tasks.length} 项{directory.hasMore ? ' · 还有更早记录' : ' · 已读完当前范围'}</span>
      <span className="pw-grow" />
      {directory.hasMore && <Button variant="ghost" disabled={directory.busy || !directory.enabled} onClick={directory.loadMore}>{directory.tasks.length ? '查看更多工作' : '继续查找'}</Button>}
    </div>}
  </section>;
}
