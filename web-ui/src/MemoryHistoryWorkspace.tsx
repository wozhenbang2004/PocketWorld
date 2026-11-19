import { useEffect, useRef, useState } from 'react';
import { IconChevronLeft, IconChevronRight } from './icons';
import { Accordion, AccordionItem, Button, CheckboxField, RadioOptions, SearchField, SelectField, SettingsDialog, TextAreaField, TextField } from './forms-library';
import './knowledge-depth.css';
import './memory-reading.css';
import { useSurfaceEnter } from './library/surface-motion';
import { cognitionWindow, MemoryResolutionHistory } from './MemoryResolutionHistory';
import { MemoryAssertionFields } from './MemoryAssertionFields';
import { MemoryConflictEvidence } from './MemoryConflictEvidence';
import { conflictReceiptMatches, MemoryConflictDecision, reviewedConflict } from './MemoryConflictDecision';
import { WorkingStatus } from './library/journey-motion';
import { MemoryReadingWindow, memoryHistoryStatus, taskMemorySelectionDetails, taskMemorySummary, taskMemoryVisibleRecords } from './memory-history';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
const rows = (value: any): Data[] => Array.isArray(value) ? value : [];

/** The same memory reading surfaces, backed by retained native history and original request receipts. */
export function MemoryHistoryWorkspace({ run, scopeKey, registerTransientBack, initialTaskId = '', readOnly = false, privateTaskHistory = false }: {
  run: Run; scopeKey: string; registerTransientBack: (handler: (() => boolean) | null) => void; initialTaskId?: string; readOnly?: boolean; privateTaskHistory?: boolean;
}) {
  const [view, setView] = useState<'history' | 'tasks'>(privateTaskHistory && initialTaskId ? 'tasks' : 'history');
  const [query, setQuery] = useState(''), [subject, setSubject] = useState('owner');
  const [page, setPage] = useState<Data | null>(null), [cognition, setCognition] = useState<Data | null>(null);
  const [task, setTask] = useState<Data | null>(null), [receipt, setReceipt] = useState<Data | null>(null);
  const [selected, setSelected] = useState<Data | null>(null), [source, setSource] = useState<Data | null>(null);
  const [replacements, setReplacements] = useState<Data[]>([]), [replacement, setReplacement] = useState('');
  const [draft, setDraft] = useState(''), [review, setReview] = useState<Data | null>(null), [acknowledged, setAcknowledged] = useState(false);
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('');
  const cognitionAt = useRef(''), localChoices = useRef(new Set<string>()), turnFocus = useRef(false), pageHeading = useRef<HTMLParagraphElement | null>(null);
  const [deferredPage, setDeferredPage] = useState('');
  const blocked = useRef(readOnly); blocked.current = readOnly;
  const locked = busy || readOnly;
  const window = useRef(new MemoryReadingWindow()), session = useRef(''), reviewHandle = useRef('');
  const lease = useRef({ expires: 0, timer: 0, confirming: false });
  const [version, setVersion] = useState(() => window.current.capture());
  const selectedRef = useRef<Data | null>(null), focus = useRef<HTMLElement | null>(null), reading = useRef<HTMLElement | null>(null);
  const collection = useRef<HTMLElement | null>(null), listPosition = useRef(0), returnFocus = useRef('');
  useSurfaceEnter(collection, selected ? `history:${selected.id}` : `history-list:${view}`, { selector: selected ? ':scope > .pw-memory-reading' : '', depth: selected ? 1 : 0, active: !readOnly });
  selectedRef.current = selected;
  const closeHandle = (handle: string) => { if (handle) void run('memoryHistory.close', { handle }).catch(() => {}); };
  const closeReview = () => { closeHandle(reviewHandle.current); reviewHandle.current = ''; setReview(null); setAcknowledged(false); };
  const clearReading = () => { setSelected(null); selectedRef.current = null; setSource(null); setReplacements([]); setReplacement(''); setDraft(''); closeReview(); };
  const reset = () => {
    localChoices.current.clear(); turnFocus.current = false; setDeferredPage('');
    globalThis.clearTimeout(lease.current.timer); lease.current = { expires: 0, timer: 0, confirming: false };
    window.current.invalidate(); closeHandle(session.current); session.current = '';
    // Even an already-empty view needs fresh handlers for this reading window.
    // Otherwise React can skip every no-op reset and retain a retired ticket.
    setVersion(window.current.capture());
    setBusy(false); setPage(null); setCognition(null); setTask(null); setReceipt(null); clearReading(); setError(''); setNotice('');
  };
  const expire = () => {
    if (!session.current) return;
    const uncertain = lease.current.confirming;
    reset(); setNotice(uncertain ? '原阅读窗口已到期，刚提交的修改没有取得可核对的回执。请重新读取状态，不要重复提交。' : '原阅读窗口已到期，已收起正文和来源。需要继续时请重新读取。');
  };
  const live = () => { if (session.current && Date.now() >= lease.current.expires) { expire(); return false; } return !document.hidden; };
  const invoke = async (action: string, args: Data, accept?: (value: Data) => void) => {
    if (!live() || blocked.current || !privateTaskHistory && action.startsWith('taskMemory.')) return;
    const ticket = version;
    if (!window.current.enter(ticket)) return;
    setBusy(true); setError('');
    const changing = action === 'memoryHistory.confirm' || action === 'taskMemory.confirm';
    if (changing) lease.current.confirming = true;
    try {
      let input = args;
      if (['memoryHistory.search', 'memoryHistory.cognition', 'taskMemory.search', 'taskMemory.task'].includes(action)) {
        if (!session.current) {
          const opened = await run('memoryHistory.open', { view });
          if (!window.current.current(ticket) || document.hidden) { closeHandle(opened.sessionHandle); return; }
          if (typeof opened.sessionHandle !== 'string' || !opened.sessionHandle || opened.view !== view || !Number.isSafeInteger(opened.readExpiresAt) || opened.readExpiresAt <= Date.now()) {
            closeHandle(opened.sessionHandle); throw new Error('没有取得当前原生阅读期限，未读取正文。');
          }
          const id = opened.sessionHandle; session.current = id; lease.current.expires = opened.readExpiresAt;
          lease.current.timer = globalThis.setTimeout(() => { if (session.current === id) expire(); }, Math.max(0, opened.readExpiresAt - Date.now()));
        }
        input = { ...args, sessionHandle: session.current };
      }
      const result = await run(action, input);
      if (window.current.current(ticket) && live()) { accept?.(result); return result; }
    } catch (failure) { if (window.current.current(ticket) && live()) {
      // A failed presence check cannot keep a previous private receipt, opened source, or review alive.
      if (action === 'taskMemory.receipt') { reset(); setNotice('本次未能核对原提供回执，已收起旧正文。需要时请从原任务重新读取。'); }
      setError(failure instanceof Error ? failure.message : String(failure));
    } }
    finally { window.current.finish(ticket); if (window.current.current(ticket)) { if (changing) lease.current.confirming = false; setBusy(false); } }
  };
  const choose = (record: Data, identity = '') => {
    if (!session.current || !window.current.current(version) || !live()) return;
    window.current.invalidate(); setBusy(false);
    setVersion(window.current.capture());
    closeHandle(selectedRef.current?.readHandle || '');
    focus.current = document.activeElement as HTMLElement;
    returnFocus.current = identity || focus.current?.dataset.historyId || '';
    listPosition.current = collection.current?.closest('.pw-secondary-scroll')?.scrollTop || 0;
    setSelected(record); setDraft(record.text || ''); setSource(null); setReplacement(''); setReplacements([]); closeReview();
  };
  const openReview = (value: Data) => { reviewHandle.current = value.reviewHandle; setReview(value); setAcknowledged(false); };
  const search = (cursor = '') => {
    clearReading(); setCognition(null); setTask(null); setReceipt(null); setPage(null);
    void invoke(view === 'history' ? 'memoryHistory.search' : 'taskMemory.search', { query, cursor }, setPage);
  };
  const readCognition = (cursorHandle?: string) => {
    if (locked || !subject.trim() || (cursorHandle && (!cognition || ![cognition.directoryPage?.previous, cognition.directoryPage?.next].includes(cursorHandle)))) return;
    localChoices.current.clear(); setDeferredPage(''); turnFocus.current = !!cursorHandle;
    clearReading(); setPage(null); setCognition(null); const origin = cognitionWindow();
    void invoke('memoryHistory.cognition', { subjectId: subject.trim(), ...(cursorHandle ? { cursorHandle } : {}) }, value => {
      const d = value.directoryPage;
      if (origin !== cognitionWindow() || value.subjectId !== subject.trim() || !d || !Number.isSafeInteger(d.number) || !Number.isSafeInteger(d.pages) ||
          d.number < 1 || d.number > d.pages || !Number.isSafeInteger(d.visible) || d.visible < 0 || d.visible > 24 || !Number.isSafeInteger(d.total) || d.total < d.visible ||
          (d.number > 1) !== (typeof d.previous === 'string' && !!d.previous) || (d.number < d.pages) !== (typeof d.next === 'string' && !!d.next)) throw new Error('原主体目录页未能核对，请从第一页重新读取。');
      cognitionAt.current = origin; setCognition(value);
    });
  };
  const stepCognitionPage = (handle: string) => { if (localChoices.current.size) setDeferredPage(handle); else readCognition(handle); };
  useEffect(() => { if (cognition && turnFocus.current) { turnFocus.current = false; pageHeading.current?.focus(); } }, [cognition]);
  const openTask = (taskId: string) => { clearReading(); setReceipt(null); setTask(null); void invoke('taskMemory.task', { taskId }, setTask); };
  const acceptReceipt = (value: Data) => {
    setReceipt(value); const previous = selectedRef.current;
    if (previous && !taskMemoryVisibleRecords(value).some(row => row.id === previous.id && row.revision === previous.revision && row.readHandle)) clearReading();
  };
  const readReceipt = () => { if (task?.taskHandle) { setSource(null); void invoke('taskMemory.receipt', { taskHandle: task.taskHandle }, acceptReceipt); } };
  const readSource = (index: number, span: boolean, offset = 0) => {
    if (!selected?.readHandle || selectedRef.current !== selected) return; setSource(null);
    const args = { readHandle: selected.readHandle, index, offset, ...(view === 'history' ? { span } : {}) };
    void invoke(view === 'history' ? 'memoryHistory.source' : 'taskMemory.source', args, value => setSource({ ...value, index, span }));
  };
  const prepare = (operation: string) => {
    if (!selected?.readHandle || selectedRef.current !== selected) return;
    void invoke(view === 'history' ? 'memoryHistory.review' : 'taskMemory.review', {
      readHandle: selected.readHandle, ...(view === 'history' ? { text: draft } : { operation, ...(operation === 'correct' ? { text: draft } : {}), ...(operation === 'replace' ? { replacementHandle: replacement } : {}) }),
    }, openReview);
  };
  const conflictPlan = reviewedConflict(review);
  const unidentifiedDecision = review?.operation === 'conflict' && !conflictPlan;
  const confirm = () => {
    const handle = reviewHandle.current;
    if (blocked.current || unidentifiedDecision || !review || !handle || handle !== review.reviewHandle || !acknowledged || !window.current.current(version)) return;
    reviewHandle.current = ''; setReview(null); setAcknowledged(false);
    void invoke(view === 'history' ? 'memoryHistory.confirm' : 'taskMemory.confirm', { reviewHandle: handle }, value => {
      clearReading(); setPage(null); setCognition(null); setReceipt(null); setTask(null);
      if (conflictPlan) setNotice(conflictReceiptMatches(value, conflictPlan)
        ? (conflictPlan.decision === 'coexist' ? `已记录这 ${conflictPlan.versions.length} 条并存。` : '已记录只保留你选的版本。') + '原证据保留；需要查看当前状态时请重新读取。'
        : '原回执未能核对到这次选择。请重新读取这组状态，不要重复提交。');
      else setNotice(value.feedback || '已收到原处理回执，请重新读取当前版本。原始证据保留。');
    });
  };
  useEffect(() => {
    window.current.reopen();
    setVersion(window.current.capture());
    return () => { globalThis.clearTimeout(lease.current.timer); window.current.close(); closeHandle(session.current); closeHandle(reviewHandle.current); session.current = ''; reviewHandle.current = ''; };
  }, []);
  useEffect(() => { reset(); if(!privateTaskHistory)setView('history'); }, [scopeKey,privateTaskHistory]);
  useEffect(() => {
    const scroll = collection.current?.closest('.pw-secondary-scroll');
    if (selected) { if (scroll) scroll.scrollTop = 0; reading.current?.focus({ preventScroll: true }); }
    else if (!busy && returnFocus.current) {
      if (scroll) scroll.scrollTop = listPosition.current;
      const target = Array.from(collection.current?.querySelectorAll<HTMLElement>('[data-history-id]') || []).find(node => node.dataset.historyId === returnFocus.current);
      (target || collection.current)?.focus({ preventScroll: true }); returnFocus.current = '';
    }
  }, [selected, busy]);
  useEffect(() => {
    registerTransientBack(review || selected || deferredPage ? () => { if (!busy) { if (review) closeReview(); else if (selected) clearReading(); else setDeferredPage(''); } return true; } : null);
    return () => registerTransientBack(null);
  }, [review, selected, deferredPage, busy, registerTransientBack]);
  useEffect(() => {
    const hidden = () => { if (document.visibilityState !== 'visible') reset(); else live(); };
    const leave = () => reset(), resumed = () => { live(); };
    document.addEventListener('visibilitychange', hidden); globalThis.addEventListener('pagehide', leave); globalThis.addEventListener('pageshow', resumed); globalThis.addEventListener('focus', resumed);
    return () => { document.removeEventListener('visibilitychange', hidden); globalThis.removeEventListener('pagehide', leave); globalThis.removeEventListener('pageshow', resumed); globalThis.removeEventListener('focus', resumed); };
  }, []);
  const monitorReceipt = taskMemoryVisibleRecords(receipt).some(row => row.status === 'current' && typeof row.readHandle === 'string' && !!row.readHandle);
  useEffect(() => {
    if (!task?.taskHandle || !monitorReceipt) return;
    const timer = setInterval(() => { if (document.visibilityState === 'visible') void invoke('taskMemory.receipt', { taskHandle: task.taskHandle }, acceptReceipt); }, 2000);
    return () => clearInterval(timer);
  }, [task?.taskHandle, monitorReceipt, version]);
  const details = taskMemorySelectionDetails(receipt);
  const pageRecords = rows(view === 'history' ? page?.matches : page?.tasks);
  const resultGroup = (row: Data) => view === 'history' ? (['pending_conflict', 'current', 'historical'].includes(row.temporalStatus) ? row.temporalStatus : 'unknown') : (['awaiting_user', 'blocked', 'failed'].includes(row.status) ? 'attention' : ['completed', 'cancelled'].includes(row.status) ? 'finished' : 'active');
  const resultGroups = (view === 'history' ? [{ id: 'pending_conflict', label: '冲突待核' }, { id: 'current', label: '当前记录' }, { id: 'historical', label: '历史版本' }, { id: 'unknown', label: '时间或主体待核对' }] : [{ id: 'attention', label: '需要处理' }, { id: 'active', label: '其他原任务' }, { id: 'finished', label: '已结束' }]).map(group => ({ ...group, records: pageRecords.filter(row => resultGroup(row) === group.id) })).filter(group => group.records.length);
  return <section ref={collection} tabIndex={-1} className="pw-memory-history pw-knowledge-workspace" aria-label="旧事与任务来源" inert={readOnly || undefined}>
    {!selected && <header className="pw-knowledge-heading"><h2>{view === 'history' ? '查阅旧事' : '核对任务来源'}</h2></header>}
    {!selected && privateTaskHistory && <SelectField label="查阅内容" value={view} options={[{ id: 'history', label: '旧事与主体认知' }, { id: 'tasks', label: '原任务与本轮记忆来源' }]} isDisabled={locked} onChange={value => { reset(); setView(value as 'history' | 'tasks'); setQuery(''); }} />}
    {!selected && !privateTaskHistory && <p className="pw-muted">只查阅当前记忆范围里的旧事。需要查看私人任务来源时，请先返回并明确选择“主人全局 · 私人”。</p>}
    {error && <p className="pw-secondary-error" role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}
    <WorkingStatus active={busy && !readOnly}>正在核对原版本与回执…</WorkingStatus>
    {!selected && <>
      <div className="pw-knowledge-search-actions">
      <SearchField aria-label={view === 'history' ? '旧事关键词或记载日期' : '搜索原任务'} placeholder={view === 'history' ? '关键词或 UTC 日期' : '任务关键词'} value={query} onChange={value => { reset(); setQuery(value); }} />
      <Button variant="secondary" disabled={locked || query.length > 500} onClick={() => search()}>{view === 'history' ? '查旧事' : '搜索任务记录'}</Button></div>
      {view === 'tasks' && initialTaskId && <Button variant="ghost" disabled={locked} onClick={() => openTask(initialTaskId)}>读取当前原任务</Button>}
      {view === 'history' && <Accordion variant="divided" className="pw-compact-disclosures"><AccordionItem id="cognition" title="主体当前认知与冲突">
        <p className="pw-muted">未知主体不会归入主人；计划与想象不参与当前现实事实。</p>
        <TextField label="稳定主体身份" value={subject} onChange={value => { reset(); setSubject(value); }} maxLength={200} isDisabled={locked} />
        <Button variant="ghost" disabled={locked || !subject.trim()} onClick={() => readCognition()}>读取当前认知</Button>
      </AccordionItem></Accordion>}
      {!page && !cognition && !task && !busy && <p className="pw-knowledge-empty-copy">{view === 'history' ? '输入关键词或记载日期，查找保存过的原文。也可以按主体核对当前认知与未决冲突。' : '搜索原任务，再查看当时提供给模型的记忆凭据。'}</p>}
      {cognition && <section aria-label="已读取的主体认知">{cognition.directoryPage.pages > 1 && <p ref={pageHeading} tabIndex={-1} className="pw-muted" role="status">第 {cognition.directoryPage.number} / {cognition.directoryPage.pages} 页 · 本页 {cognition.directoryPage.visible} 条，共 {cognition.directoryPage.total} 条。未列在本页不等于没有保存。</p>}<p className="pw-knowledge-caption">{cognition.directoryPage.pages > 1 ? '本页：' : ''}当前 {rows(cognition.current).length} · 历史 {rows(cognition.history).length} · 待审冲突 {rows(cognition.pendingConflicts).length}</p>
        {rows(cognition.conflictGroups).map(group => <ConflictReview key={group.conflictHandle} group={group} onDraft={dirty => { if (dirty) localChoices.current.add(group.conflictHandle); else localChoices.current.delete(group.conflictHandle); }} pending={rows(cognition.pendingConflicts)} deadline={cognition.conflictReadExpiresAt} invoke={invoke} busy={locked} onReview={args => { void invoke('memoryHistory.conflict.review', args, openReview); }} />)}
        {(['current', 'history'] as const).map(key => rows(cognition[key]).length > 0 && <section className="pw-knowledge-directory-group" key={key}><header className="pw-knowledge-group-heading"><h3>{key === 'current' ? '当前认知' : '历史、计划或尚未生效'}</h3><span>{rows(cognition[key]).length}</span></header><div className="pw-memory-list">{rows(cognition[key]).map(row => <article key={row.id} className="pw-memory-list-item"><Button variant="ghost" className="pw-memory-row" data-history-id={`${key}:${row.id}`} trailingIcon={IconChevronRight} disabled={locked} onClick={() => choose(row, `${key}:${row.id}`)}><span className="pw-memory-row-copy"><strong>{row.text}</strong><small>{row.assertion?.kind} · {row.assertionStatus}</small></span></Button></article>)}</div></section>)}
        {cognition.directoryPage.pages > 1 && <nav aria-label="同一主体目录分页">{deferredPage ? <><p className="pw-muted">这一页有尚未提交的选择或理由。翻页不会保存。</p><div className="pw-form-actions">
          <Button variant="secondary" disabled={locked} onClick={() => setDeferredPage('')}>留在本页</Button>
          <Button variant="ghost" disabled={locked} onClick={() => readCognition(deferredPage)}>放弃未提交内容并翻页</Button>
        </div></> : <div className="pw-form-actions">
          {cognition.directoryPage.previous && <Button variant="ghost" disabled={locked} onClick={() => stepCognitionPage(cognition.directoryPage.previous)}>上一页</Button>}
          {cognition.directoryPage.next && <Button variant="ghost" disabled={locked} onClick={() => stepCognitionPage(cognition.directoryPage.next)}>下一页</Button>}
        </div>}</nav>}
      </section>}
      {page && <section className="pw-knowledge-section"><h3 className="pw-knowledge-section-heading">{view === 'history' ? '匹配的旧事' : '原任务记录'}</h3>
        <p className="pw-muted">{view === 'history' ? `本页 ${rows(page.matches).length} / 匹配 ${page.totalMatches ?? '未知'}。未列出不代表从未保存。` : `本页 ${rows(page.tasks).length} 条原任务 · ${page.coverage === 'full_task_store' ? '完整任务记录检索' : '以原宿主声明范围为准'}`}</p>
        {page.unverifiedArchiveRecords > 0 && <p className="pw-muted">另有 {page.unverifiedArchiveRecords} 条未核归档；查看不会激活历史包。</p>}
        {resultGroups.map(group => <section key={group.id} className="pw-knowledge-directory-group" aria-label={group.label}><header className="pw-knowledge-group-heading"><h3>{group.label}</h3><span>{group.records.length}</span></header><div className="pw-memory-list">{group.records.map(row => <article key={row.id || row.taskId} className="pw-memory-list-item"><Button variant="ghost" className="pw-memory-row" data-history-id={`result:${row.id || row.taskId}`} trailingIcon={IconChevronRight} disabled={locked} onClick={() => view === 'history' ? void invoke('memoryHistory.record', { recordHandle: row.recordHandle }, value => choose({ ...value.record, readHandle: value.readHandle }, `result:${row.id || row.taskId}`)) : openTask(row.taskId)}><span className="pw-memory-row-copy"><strong>{row.textPreview || row.goal || row.title || row.taskId}</strong><small>{view === 'history' ? memoryHistoryStatus(row.temporalStatus) : `${row.status || '状态未知'} · 任务记录，不是正式记忆`}</small></span></Button></article>)}</div></section>)}
        {!pageRecords.length && <p className="pw-knowledge-empty-copy">这次搜索没有匹配项。可以换一个关键词；未列出不代表从未保存。</p>}
        {page.nextCursor && <Button variant="ghost" disabled={locked} onClick={() => search(page.nextCursor)}>下一页</Button>}
      </section>}
      {task && <section className="pw-source-record"><h3>{task.goal || '所选原任务'}</h3><p className="pw-muted">{task.status}</p><div className="pw-form-actions"><Button variant="ghost" disabled={locked} onClick={() => { void invoke('taskMemory.focus', { taskHandle: task.taskHandle }); }}>打开这条原任务</Button><Button variant="secondary" disabled={locked || !task.referenceAvailable} onClick={readReceipt}>读取本轮记忆凭据</Button></div>{!task.referenceAvailable && <p>此原任务没有提供给模型的记忆凭据。不会用当前检索补写当时的来源。</p>}</section>}
      {receipt && <section aria-label="本次提供给模型的记忆来源"><p>{taskMemorySummary(receipt)}</p>{!taskMemoryVisibleRecords(receipt).length && <p className="pw-muted">原凭据没有可显示的记忆条目。</p>}
        {details && <Accordion variant="divided"><AccordionItem id="selection" title="查看本次选择与省略"><p>当时提供核心参考 {details.core} 条 · 任务匹配 {details.retrieved} 条 · 使用 {details.chars} 字符。</p>{Object.entries(details.counts).map(([key, count]) => <p key={key}>{key === 'record_limit' ? '条数预算已满' : key === 'character_limit' ? '字符预算已满' : key === 'record_too_large' ? '条目过长' : '来源或版本变化'}：{String(count)} 条</p>)}<p className="pw-muted">省略版本仅保留标识，不保留旧正文。</p>{details.omitted.map((row: Data) => <p key={row.id}>{row.id} · 修订 {row.revision}</p>)}</AccordionItem></Accordion>}
        <section className="pw-knowledge-directory-group"><header className="pw-knowledge-group-heading"><h3>本次提供的原版本</h3><span>{taskMemoryVisibleRecords(receipt).length}</span></header><div className="pw-memory-list">{taskMemoryVisibleRecords(receipt).map(row => <article key={row.id} className="pw-memory-list-item"><Button variant="ghost" className="pw-memory-row" data-history-id={`receipt:${row.id}`} trailingIcon={IconChevronRight} disabled={locked || !row.readHandle} onClick={() => choose(row, `receipt:${row.id}`)}><span className="pw-memory-row-copy"><strong>{row.text || '原正文已失效，不再显示'}</strong><small>{row.id} · 修订 {row.revision} · {row.status}</small></span></Button></article>)}</div></section>
      </section>}
    </>}
    {selected && <section ref={reading} tabIndex={-1} className="pw-memory-reading"><header className="pw-memory-detail-header"><Button variant="ghost" leadingIcon={IconChevronLeft} disabled={locked} onClick={clearReading}>返回来源列表</Button><h2>{view === 'history' ? '这份已存记忆' : '这次提供的原版本'}</h2></header>
      <div className="pw-memory-document"><p className="pw-reading-copy">{selected.text}</p></div><p className="pw-muted">原修订 {selected.revision} · {view === 'history' ? memoryHistoryStatus(selected.temporalStatus ?? selected.assertionStatus) : selected.status}。摘要不是原件。</p>
      {view === 'history' && selected.assertionResolution && <MemoryResolutionHistory row={selected} cognition={cognition} originalWindow={cognitionAt.current} />}
      {selected.assertion && <Accordion variant="divided" className="pw-compact-disclosures"><AccordionItem id="history-assertion" title="主体与关系" description={selected.assertion.subjectLabel || '原记录的主体信息'}><MemoryAssertionFields readOnly candidate={selected} /></AccordionItem></Accordion>}
      <div className="pw-form-actions">{rows(selected.assertion?.evidenceSpans).map((_, index) => view === 'history' && <Button key={`span-${index}`} variant="ghost" disabled={locked} onClick={() => readSource(index, true)}>回读确切原句 {index + 1}</Button>)}{rows(selected.sourceRefs).map((_, index) => <Button key={`source-${index}`} variant="ghost" disabled={locked} onClick={() => readSource(index, false)}>查看原来源 {index + 1}</Button>)}</div>
      {view === 'tasks' && <div className="pw-form-actions">{rows(selected.sourceRefs).map((ref, index) => ref.kind === 'task_evidence' && <Button key={index} variant="ghost" disabled={locked} onClick={() => { if (selectedRef.current === selected) void invoke('taskMemory.source.focus', { readHandle: selected.readHandle, index }); }}>打开来源原任务 {index + 1}</Button>)}</div>}
      {!rows(selected.assertion?.evidenceSpans).length && <p className="pw-muted">未附确切原文片段；这段记忆不作为主人原话认证。</p>}
      {source && <section className="pw-source-record"><h3>{source.title || '确切原来源'}</h3><p className="pw-muted">{source.verifiedOriginalSpan ? '原范围与指纹已核对；不证明陈述为真。' : source.status && source.status !== 'current' ? '原来源当前不可读，不显示旧正文。' : '原选择范围，不自动取得执行权限。'}</p><pre className="pw-source-copy" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{source.text}</pre>{source.sourceReadLimited && !source.hasMore && <p className="pw-muted">本次原宿主只返回了这一段原文。请打开来源原任务核对其余内容；没有把截取片段当作全文。</p>}{source.hasMore && <Button variant="ghost" disabled={locked} onClick={() => readSource(source.index, source.span, source.nextOffset)}>继续读取原文</Button>}</section>}
      <Accordion variant="divided" className="pw-compact-disclosures pw-memory-edit"><AccordionItem id="correction" title="纠正或遗忘">
        <TextAreaField label="正确的内容" value={draft} onChange={setDraft} maxLength={4000} isDisabled={locked} />
        <Button variant="secondary" disabled={locked || !draft.trim() || draft.trim() === selected.text} onClick={() => prepare('correct')}>核对纠正</Button>
        {view === 'tasks' && <><Button variant="ghost" disabled={locked} onClick={() => { void invoke('taskMemory.replacements', { readHandle: selected.readHandle }, value => { setReplacements(rows(value.records)); setReplacement(''); }); }}>选择另一条正式记忆替代</Button>{replacements.length > 0 && <><SelectField label="保留的正式记忆" value={replacement} options={replacements.map(row => ({ id: row.replacementHandle, label: row.text }))} onChange={setReplacement} isDisabled={locked} /><Button variant="secondary" disabled={locked || !replacement} onClick={() => prepare('replace')}>核对替代与旧版本停用</Button></>}<Button variant="ghost" disabled={locked} onClick={() => prepare('forget')}>核对遗忘</Button></>}
      </AccordionItem></Accordion>
    </section>}
    <SettingsDialog className="pw-memory-modal" isOpen={!!review} onClose={() => { if (!busy) closeReview(); }} title={review?.operation === 'conflict' ? '核对这组原冲突版本' : review?.operation === 'forget' ? '遗忘这条原记忆' : '核对本次修改'}><div className="pw-knowledge-review">
      {review?.operation === 'conflict' ? <MemoryConflictDecision plan={conflictPlan} /> : <section className="pw-memory-review-version"><h3>当前内容</h3><p className="pw-reading-copy">{review?.before?.text}</p>{rows(review?.beforeRecords).map(row => <p key={row.id}>{row.text} · 修订 {row.revision}</p>)}</section>}{review?.afterText && <section className="pw-memory-review-version"><h3>修改后</h3><p className="pw-reading-copy">{review.afterText}</p></section>}{review?.reason && <p>理由：{review.reason}</p>}
      <p>只处理所示的精确版本。原会话与任务证据保留；保存不证明内容为真。旧上下文是否清理，以原宿主回执为准。</p>
      <CheckboxField isSelected={acknowledged} onChange={setAcknowledged} isDisabled={locked || unidentifiedDecision}>我已核对这次内容与影响</CheckboxField><div className="pw-form-actions"><Button variant="secondary" disabled={locked} onClick={closeReview}>返回核对</Button><Button disabled={locked || !acknowledged || unidentifiedDecision} onClick={confirm}>{conflictPlan?.decision === 'coexist' ? '确认并存' : conflictPlan?.decision === 'replace' ? '确认保留所选版本' : '确认这一次修改'}</Button></div>
    </div></SettingsDialog>
  </section>;
}

function ConflictReview({ group, pending, deadline, busy, invoke, onDraft, onReview }: { group: Data; onDraft: (dirty: boolean) => void; pending: Data[]; deadline: number; busy: boolean; invoke: (action: string, args: Data, receive: (value: Data) => void) => Promise<unknown>; onReview: (args: Data) => void }) {
  const [choice, setChoice] = useState(''), [reason, setReason] = useState('');
  useEffect(() => () => onDraft(false), [group.conflictHandle]);
  const originals = rows(group.records), coexist = choice === ':coexist', chosen = originals.find(row => `keep:${row.id}` === choice);
  if (group.reviewable === false) return <section className="pw-source-record pw-knowledge-conflict"><h3>这组冲突暂时只能逐条核对</h3>
    <p className="pw-muted">同组共 {group.totalRecords} 条，超过一次原生审阅可处理的 20 条。本页只显示其中 {originals.length} 条；不会让部分版本代替整组确认。</p>
    <MemoryConflictEvidence group={group} pending={pending} deadline={deadline} busy={busy} invoke={invoke} /></section>;
  const options = originals.map(row => ({ id: `keep:${row.id}`, label: row.text, description: `${row.reviewRole === 'protected_current' ? '此前受保护的当前版本' : '待核对'} · 修订 ${row.revision}` }));
  if (originals.length >= 2) options.push({ id: ':coexist', label: '这些内容并存', description: '不指定唯一版本，需要说明保留理由。' });
  return <section className="pw-source-record pw-knowledge-conflict"><h3>核对这组冲突</h3>
    <p className="pw-muted">新的推断不会自动替换主人纠正过的内容。先逐条对照，再决定以后如何参考。</p>
    <MemoryConflictEvidence group={group} pending={pending} deadline={deadline} busy={busy} invoke={invoke} />
    <RadioOptions label={originals.length >= 2 ? '保留哪一条，或允许并存' : '核对要保留的版本'} value={choice} options={options} onChange={v => { setChoice(v); onDraft(!!v || !!reason.trim()); }} isDisabled={busy} />
    <TextAreaField label="这样处理的理由" value={reason} onChange={v => { setReason(v); onDraft(!!choice || !!v.trim()); }} maxLength={1000} isDisabled={busy} />
    <Button variant="secondary" disabled={busy || !(coexist ? originals.length >= 2 : chosen) || !reason.trim()} onClick={() => onReview({
      conflictHandle: group.conflictHandle, decision: coexist ? 'coexist' : 'replace', selectedId: coexist ? '' : chosen?.id, reason,
    })}>核对这次选择</Button></section>;
}
