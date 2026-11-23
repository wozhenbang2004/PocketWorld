import { useLayoutEffect, useRef, useState, type ReactNode, type RefObject } from 'react';
import { Button, IconButton, SearchField } from './forms-library';
import { bridge } from './bridge';
import { completedDirectoryReport } from './ButlerReturnedReport';
import type { ReportTarget } from './OwnerWorkReport';
import { useCollectionMotion } from './library/journey-motion';
import { IconClose, IconChevronRight, IconGrid, IconHistory, IconSearch } from './icons';
import { workCollectionPresentation, type WorkCollectionFilter, type WorkCollectionTask } from './work-collection';
import { WorkDirectoryControls, type useWorkDirectory } from './WorkDirectory';
import { firstFollowUp, readFollowUp, WorkBrief, WorkQueueRow, type WorkEntry, type WorkReading } from './WorkFollowUp';
import './work-recovery-directory.css';

type Directory = ReturnType<typeof useWorkDirectory>;
type RunPin = { runId: string; epoch: number };
export type CompletedDirectoryResultIntent = 'readOriginal' | 'sourceUnavailable';
type Origin = { id: string; entry: WorkEntry; scope: WorkCollectionFilter; query: string; updatedAt: number; run?: RunPin; ownerConversation?: string; completedResult?: true };
/** The redacted header still identifies this ordinary kind of work, even when it cannot provide a run pin. */
const completedOrdinaryWork = (task: WorkCollectionTask) => {
  const raw=task.raw, work=raw?.ownerWork, conversation=raw?.conversationId;
  return task.status==='completed' && raw?.status===task.status && raw.id===task.id && raw.kind==='assistant' &&
    raw.actorId==='owner' && raw.spaceId==='private' && raw.executionMode==='capabilities' && raw.executionRole==='owner_work' &&
    raw.origin==null && raw.parentTaskId==null && raw.teamRunId==null && raw.teamAssignment==null && raw.memberId==null &&
    raw.developmentContext==null && raw.ownerPhoneControl==null && raw.ownerDeviceWork==null &&
    typeof conversation==='string' && !!conversation.trim() &&
    work?.format==='pocketworld.owner-work.v1' && work.workTaskId===task.id && work.conversationId===conversation;
};
const redactedStopRun = (task: WorkCollectionTask): RunPin | undefined => {
  const raw=task.raw;
  return raw?.taskProjection==='source_unavailable' && raw.contextAvailable===false && raw.sourceCurrentReadRequired===true &&
    raw.auditBodyIncluded===false && raw.executionAuthorized===false && raw.controls?.canStop===true &&
    typeof raw.runId==='string' && !!raw.runId.trim() && Number.isSafeInteger(raw.coordinatorEpoch) && raw.coordinatorEpoch>0
    ? {runId:raw.runId,epoch:raw.coordinatorEpoch}:undefined;
};
const runOf = (task: WorkCollectionTask): RunPin | undefined => {
  const row=task.raw;
  return row?.id===task.id && row.actorId==='owner' && row.spaceId==='private' &&
    row.contextAvailable!==false && row.sourceCurrentReadRequired!==true && row.taskProjection!=='source_unavailable' &&
    typeof row.runId==='string' && row.runId.trim() && Number.isSafeInteger(row.coordinatorEpoch) && row.coordinatorEpoch>0
    ? { runId:row.runId, epoch:row.coordinatorEpoch } : undefined;
};
const sameRun = (task: WorkCollectionTask, wanted?: RunPin) => {
  if (!wanted) return true;
  const run=runOf(task); return run?.runId===wanted.runId && run.epoch===wanted.epoch;
};
const ordinaryOwnerConversation = (task: WorkCollectionTask): string | undefined => {
  const raw=task.raw, work=raw?.ownerWork, conversation=raw?.conversationId;
  return raw?.kind==='assistant' && raw.executionRole==='owner_work' && raw.contextAvailable===true && raw.contextWithdrawn!==true &&
    raw.metadataLimited!==true && raw.taskCoreReadLimited!==true && raw.origin==null && !raw.developmentContext && !raw.ownerPhoneControl && !raw.ownerDeviceWork &&
    work?.format==='pocketworld.owner-work.v1' && work.workTaskId===task.id && work.conversationId===conversation &&
    typeof conversation==='string' && !!conversation.trim() ? conversation : undefined;
};
function observedLaterOwnerRound(rows: readonly WorkCollectionTask[], original: Origin) {
  if (!original.run || !original.ownerConversation) return undefined;
  const matches=rows.filter(row=>row?.id===original.id);
  if (matches.length!==1) return undefined;
  const task=matches[0], run=runOf(task);
  return run && run.runId!==original.run.runId && run.epoch>original.run.epoch &&
    typeof task.updatedAt==='number' && Number.isSafeInteger(task.updatedAt) && task.updatedAt>=original.updatedAt &&
    ordinaryOwnerConversation(task)===original.ownerConversation ? run : undefined;
}
function directoryWindow(snapshot: ReturnType<typeof bridge.getState>) {
  const view=snapshot.view as {section?:string|null;taskId?:string|null;resumeRevision?:number}|undefined;
  return !document.hidden && view?.section===null && view.taskId===null && Number.isSafeInteger(view.resumeRevision) &&
    !snapshot.state.filePath && !snapshot.state.conversationReader ? `${bridge.getDocumentEpoch()}:${view.resumeRevision}` : '';
}
const scopes: { id: WorkCollectionFilter; title: string }[] = [
  { id: 'all', title: '全部' }, { id: 'attention', title: '待处理' }, { id: 'running', title: '进行中' }, { id: 'ended', title: '已结束' },
];

/** Search replaces the title, not an extra row above the results. Closing keeps its query. */
export function WorkFindTitle({ editing, query, onQuery, edit, close }: {
  editing: boolean; query: string; onQuery: (value: string) => void; edit: () => void; close: () => void;
}) {
  const field = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => { if (editing) field.current?.querySelector('input')?.focus({ preventScroll: true }); }, [editing]);
  if (editing) return <div className="pw-work-find-title" ref={field} id="pw-work-find" onKeyDownCapture={event => {
    if (event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229) return;
    if (event.key === 'Escape' || (event.key === 'Enter' && event.target instanceof HTMLInputElement)) { event.preventDefault(); event.stopPropagation(); close(); }
  }}>
    <h1 className="pw-sr-only">搜索我的工作</h1>
    <SearchField value={query} onChange={onQuery} maxLength={500} clearLabel="清除关键词" aria-label="按目标或摘要搜索工作" placeholder="搜索目标或摘要"/>
  </div>;
  return <div className="pw-current-work pw-work-find-title"><h1>{query
    ? <Button variant="ghost" className="pw-work-query-title" data-work-search-entry leadingIcon={IconSearch} aria-label={'继续搜索：' + query} title={query} onClick={edit}><span>{query}</span></Button>
    : '我的工作'}</h1></div>;
}

type Props = {
  directory: Directory; live: WorkCollectionTask[]; canContinue: (task: WorkCollectionTask) => boolean; scroll: RefObject<HTMLElement | null>; ready: boolean; canOpen: boolean; canReview: boolean;
  scope: WorkCollectionFilter; query: string; selectScope: (value: WorkCollectionFilter) => void;
  clearQuery: () => void; drillEnded: () => void; start: () => void; history: () => void;
  open: (id: string, entry: WorkEntry, query: string, run?: RunPin, report?: ReportTarget, resultIntent?: CompletedDirectoryResultIntent) => void;
  registerReturn: (fn: (() => boolean) | null) => void; extra?: ReactNode;
};
export function WorkRecoveryDirectory({ directory, live, canContinue, scroll, ready, canOpen, canReview, scope, query, selectScope,
  clearQuery, drillEnded, start, history, open, registerReturn, extra }: Props) {
  const origin = useRef<Origin | null>(null), focusReturn = useRef<HTMLDivElement>(null);
  const [returning, setReturning] = useState<Origin | null>(null);
  const items = directory.tasks, searched = !!query.trim(), filtered = searched || scope !== 'all';
  const readings = items.map(task => readFollowUp(task, canReview, canContinue(task)));
  const groups: Record<Exclude<WorkCollectionFilter, 'all'>, WorkReading[]> = { attention: [], running: [], ended: [] };
  for (const item of readings) groups[item.group].push(item);
  // Only the unfiltered overview is an editorial selection. Searching must
  // expose every already-read match, in the same order as the directory.
  const overview = scope === 'all' && !searched;
  const moreEnded = overview && (groups.ended.length > 3 || directory.hasMore);
  const visibleEnded = overview ? groups.ended.slice(0, 3) : groups.ended;
  const visible = searched ? readings : [...groups.attention, ...groups.running, ...visibleEnded];
  const brief = searched ? undefined : firstFollowUp(visible);
  const compact = (rows: WorkReading[]) => rows.filter(v => v !== brief);
  const attentionRows = compact(groups.attention), runningRows = compact(groups.running), endedRows = compact(visibleEnded);
  const hasQueue = !!(attentionRows.length || runningRows.length || endedRows.length || moreEnded && visibleEnded.length);
  const motionRevision = JSON.stringify([brief?.task.id, visible.map(v => [v.task.id, v.goal, v.kind, v.label, v.next.entry, v.next.label])]);
  useCollectionMotion(scroll, JSON.stringify([scope, query]), motionRevision, { active: ready && directory.loaded && !directory.busy });
  const matchesScope = (o: Origin) => o.scope === scope && o.query === query;

  const visit = (task: WorkCollectionTask, entry: WorkEntry) => {
    if (!canOpen) return;
    const raw=task.raw, completed=entry==='overview' && completedOrdinaryWork(task);
    const unavailable=completed && (directory.sourceUnavailable(task.id) || raw?.taskProjection==='source_unavailable' ||
      raw?.contextWithdrawn===true || raw?.sourceCurrentReadRequired===true || raw?.contextAvailable===false);
    const stopRun=unavailable ? redactedStopRun(task) : undefined;
    if (unavailable && !stopRun) {
      open(task.id, entry, query, undefined, undefined, 'sourceUnavailable'); return;
    }
    const run=stopRun??runOf(task);
    const ownerConversation=run&&Number.isSafeInteger(task.updatedAt)&&raw?.controls?.canContinue===true ? ordinaryOwnerConversation(task) : undefined;
    const report = !unavailable && entry === 'overview' && run && bridge.getState().capabilities.commands.includes('ownerWork.report.read')
      ? completedDirectoryReport(task) : null;
    const completedResult=completed && !!run && (!!stopRun || raw?.contextAvailable===true) && !report ? true : undefined;
    origin.current = { id: task.id, entry, scope, query, updatedAt: task.updatedAt || 0, run, ownerConversation, completedResult }; setReturning(null);
    if (scroll.current) directory.rememberScroll(scroll.current.scrollTop);
    open(task.id, entry, query, run, report ?? undefined, completedResult ? 'readOriginal' : undefined);
  };
  const focusOriginal = (o: Origin) => {
    if (o.run && !visible.some(item => item.task.id===o.id && sameRun(item.task,o.run))) return false;
    const buttons = Array.from(scroll.current?.querySelectorAll<HTMLElement>('button[data-task-id]:not(:disabled)') || []).filter(el => el.dataset.taskId === o.id);
    const row = buttons.find(el => el.dataset.workEntry === o.entry) || buttons[0];
    if (!row) return false;
    row.focus({ preventScroll: true });
    const bounds = row.getBoundingClientRect(), view = scroll.current, frame = view?.getBoundingClientRect();
    const tools = view?.querySelector<HTMLElement>('[data-work-scopes]')?.getBoundingClientRect();
    if (view && frame) {
      const top = Math.max(frame.top, tools?.bottom || frame.top);
      const delta = bounds.top < top ? bounds.top - top - 4 : bounds.bottom > frame.bottom ? bounds.bottom - frame.bottom + 4 : 0;
      if (delta) view.scrollBy({ top: delta, behavior: 'instant' });
    }
    return true;
  };
  const restore = useRef<() => boolean>(() => false);
  restore.current = () => {
    const o = origin.current; origin.current = null;
    if (!o || !matchesScope(o)) return false;
    if (!focusOriginal(o)) setReturning(o);
    return true;
  };
  useLayoutEffect(() => { registerReturn(() => restore.current()); return () => registerReturn(null); }, [registerReturn]);
  useLayoutEffect(() => {
    if (!returning) return;
    if (!matchesScope(returning)) { setReturning(null); return; }
    if (!directory.loaded || directory.busy || directory.error) return;
    if (focusOriginal(returning)) setReturning(null);
    else focusReturn.current?.focus({ preventScroll: true });
  }, [returning, scope, query, directory.loaded, directory.busy, directory.error, motionRevision]);
  const previousFocus = useRef<{ id: string; entry: WorkEntry; node: HTMLElement } | null>(null);
  useLayoutEffect(() => {
    const previous = previousFocus.current;
    if (returning || origin.current || !previous || previous.node.isConnected || document.activeElement !== document.body) return;
    previousFocus.current = null; focusOriginal({ id: previous.id, entry: previous.entry, scope, query, updatedAt: 0 });
  }, [scope, query, motionRevision, returning]);
  const scopeKey = (id: WorkCollectionFilter) => { origin.current = null; setReturning(null); selectScope(id); };
  // Only offer another scope from a current, still-readable, owner-private original.
  // A withdrawn source or an older header cannot reveal its title or infer its new lane.
  const located = returning && live.find(t => t.id === returning.id && t.raw?.id === t.id && t.raw.actorId === 'owner'
    && t.raw.spaceId === 'private' && t.raw.kind !== 'inference' && t.raw.contextAvailable !== false
    && t.raw.taskProjection !== 'source_unavailable' && t.raw.sourceCurrentReadRequired !== true
    && sameRun(t,returning.run) && (t.updatedAt || 0) >= returning.updatedAt);
  const differentRun = !!returning?.run && [...live, ...items].some(t => t.id===returning.id &&
    (t.updatedAt||0)>=returning.updatedAt && !!runOf(t) && !sameRun(t,returning.run));
  const laterRound=returning&&observedLaterOwnerRound(live,returning);
  const laterOutside=!!laterRound&&!visible.some(v=>v.task.id===returning?.id&&sameRun(v.task,laterRound));
  const shownDirectory=laterOutside?directoryWindow(bridge.getState()):'';
  const openLaterRound=()=>{
    const native=bridge.getState();
    if(!returning||!laterRound||!shownDirectory||!canOpen||!matchesScope(returning)||directoryWindow(native)!==shownDirectory||
      !native.capabilities.commands.includes('tasks.open')||!Array.isArray(native.state.tasks))return;
    const current=observedLaterOwnerRound(native.state.tasks,returning);
    if(!current||current.runId!==laterRound.runId||current.epoch!==laterRound.epoch)return;
    // Keep the notice usable if the exact open fails. A fresh return record
    // lets the drawer restore focus here on close without changing the old pin.
    origin.current={...returning};
    if(scroll.current)directory.rememberScroll(scroll.current.scrollTop);
    open(returning.id,'overview','',current);
  };
  const originalResultUnavailable=returning?.completedResult===true && directory.sourceUnavailable(returning.id);
  const returnToOriginal = (original: Origin) => {
    origin.current = original; setReturning(null);
    open(original.id, 'overview', '', original.run, undefined, original.completedResult ? 'readOriginal' : undefined);
  };
  const targetScope = located ? workCollectionPresentation(located).group : null;
  const norm = (s: string) => s.normalize('NFKC').toLocaleLowerCase('en-US').trim();
  const retainQuery = located ? !norm(query) || norm(located.goal + '\n' + (located.summary || '')).includes(norm(query)) : false;
  const canLocate = !!targetScope && (targetScope !== scope || (!retainQuery && !!query));
  const relocate = () => {
    if (!returning || !targetScope || !canLocate) return;
    setReturning({ ...returning, scope: targetScope, query: retainQuery ? query : '' });
    selectScope(targetScope); if (!retainQuery) clearQuery();
  };
  const renderRow = (item: WorkReading) => <WorkQueueRow key={item.task.id} item={item} canOpen={canOpen} visit={visit}/>;
  return <main ref={scroll} className="pw-page pw-tasks-page pw-depth-tasks pw-recovery-directory" data-work-scope-current={scope}
    onScroll={e => directory.rememberScroll(e.currentTarget.scrollTop)} onFocusCapture={e => {
      const node = e.target as HTMLElement, button = node.closest<HTMLElement>('button[data-task-id]');
      previousFocus.current = button?.dataset.taskId ? { id: button.dataset.taskId, entry: button.dataset.workEntry as WorkEntry, node } : null;
    }}>
    <div className="pw-task-status-filters" data-work-scopes role="group" aria-label="工作范围">{scopes.map(s => <Button key={s.id} variant="ghost"
      data-work-scope={s.id} aria-pressed={scope === s.id} onClick={() => scopeKey(s.id)}>{s.title}</Button>)}</div>
    {returning && matchesScope(returning) && directory.loaded && !directory.busy && !directory.error && !visible.some(v => v.task.id === returning.id && sameRun(v.task,returning.run)) &&
      <div ref={focusReturn} className="pw-work-return-notice" tabIndex={-1} role="status">
        <span>{originalResultUnavailable ? '这项工作的原结果暂不可读取。' : laterRound ? '这项工作已进入新一轮，刚才查看的是上一轮。' : differentRun ? '这项工作的执行轮次已变化。' : '刚查看的工作不在当前列表。'}</span>{originalResultUnavailable ? returning.run && <Button variant="ghost" disabled={!canOpen}
          onClick={() => returnToOriginal(returning)}>核对本轮停止状态</Button> : shownDirectory&&canOpen ?
          <Button variant="ghost" onClick={openLaterRound}>查看当前一轮</Button> : differentRun ? null : canLocate ? <Button variant="ghost" onClick={relocate}>在「{scopes.find(s => s.id === targetScope)?.title}」中定位</Button>
          : <Button variant="ghost" disabled={!canOpen} onClick={() => returnToOriginal(returning)}>查看原工作</Button>}<IconButton icon={IconClose} aria-label="留在当前列表" onClick={() => { setReturning(null); scroll.current?.querySelector<HTMLElement>('[aria-pressed="true"]')?.focus({ preventScroll: true }); }}/>
      </div>}
    <section className="pw-task-results pw-follow-directory" aria-label={searched ? '工作搜索结果' : '工作列表'} data-work-split={!!brief && hasQueue || undefined}>
      {searched && !!visible.length && <section className="pw-task-collection" aria-label="匹配的工作">
        <h3>匹配的工作</h3>
        <div className="pw-task-list">{visible.map(renderRow)}</div>
      </section>}
      {brief && <WorkBrief key={brief.task.id} item={brief} canOpen={canOpen} visit={visit}/>}
      {!searched && hasQueue && <div className="pw-follow-queue">
        {!!attentionRows.length && <section className="pw-task-collection pw-work-attention" aria-label="需要关注"><h3>需要关注</h3><div className="pw-task-list">{attentionRows.map(renderRow)}</div></section>}
        {!!runningRows.length && <section className="pw-task-collection" aria-label="进行中"><h3>进行中</h3><div className="pw-task-list">{runningRows.map(renderRow)}</div></section>}
        {(!!endedRows.length || moreEnded && !!visibleEnded.length) && <section className="pw-task-collection pw-ended-tasks" aria-label="已结束的工作">
          <div className="pw-work-ended-heading"><h3>{scope === 'ended' ? '已结束的工作' : '最近结束'}</h3>
            {moreEnded && <Button variant="ghost" size="small" trailingIcon={IconChevronRight} data-work-ended-link onClick={drillEnded}>查看已结束</Button>}</div>
          {!!endedRows.length && <div className="pw-task-list">{endedRows.map(renderRow)}</div>}</section>}
      </div>}
      {!ready && <p className="pw-muted" role="status">正在连接工作目录…</p>}
      {ready && directory.enabled && !directory.loaded && !directory.busy && !directory.error && <p className="pw-muted" role="status">正在读取工作目录…</p>}
      {ready && !directory.enabled && <p className="pw-muted" role="status">当前版本暂不能读取保存的工作。</p>}
      {ready && directory.loaded && !items.length && !directory.busy && !directory.error &&
        <div className="pw-empty pw-task-empty"><IconGrid size={24} aria-hidden/><h3>{directory.hasMore ? '已读取的范围内没有匹配项' : filtered ? '没有匹配的工作' : '还没有工作'}</h3>
          <p>{directory.hasMore ? '还有更早的记录，可以继续查找。' : searched ? '可先清除关键词，保留当前范围。' : scope !== 'all' ? '可以返回全部工作。' : '交给管家的事情，会在这里显示进展。'}</p>
          {searched ? <Button variant="secondary" onClick={clearQuery}>清除关键词</Button> : scope !== 'all' ? <Button variant="secondary" onClick={() => scopeKey('all')}>查看全部工作</Button> : !directory.hasMore && <Button onClick={start}>交代一件事</Button>}
        </div>}
    </section>
    <WorkDirectoryControls directory={directory}/>
    <div className="pw-work-directory-secondary"><Button variant="ghost" leadingIcon={IconHistory} data-workspace-entry="work-history" onClick={history}>历史记录</Button></div>
    {extra}
  </main>;
}
