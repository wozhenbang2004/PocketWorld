import { useEffect, useRef, useState } from 'react';
import { RiArrowLeftSLine, RiArrowRightSLine } from '@remixicon/react';
import { Accordion, AccordionItem, Button, CheckboxField, SearchField, SelectField, SettingsDialog, TextAreaField, TextField } from './forms-library';
import './knowledge-depth.css';
import { MemoryAssertionFields } from './MemoryAssertionFields';
import { MemoryReadingWindow, memoryHistoryStatus, taskMemorySelectionDetails, taskMemorySummary, taskMemoryVisibleRecords } from './memory-history';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
const rows = (value: any): Data[] => Array.isArray(value) ? value : [];

/** The same memory reading surfaces, backed by retained native history and original request receipts. */
export function MemoryHistoryWorkspace({ run, scopeKey, registerTransientBack, initialTaskId = '' }: {
  run: Run; scopeKey: string; registerTransientBack: (handler: (() => boolean) | null) => void; initialTaskId?: string;
}) {
  const [view, setView] = useState<'history' | 'tasks'>(initialTaskId ? 'tasks' : 'history');
  const [query, setQuery] = useState(''), [subject, setSubject] = useState('owner');
  const [page, setPage] = useState<Data | null>(null), [cognition, setCognition] = useState<Data | null>(null);
  const [task, setTask] = useState<Data | null>(null), [receipt, setReceipt] = useState<Data | null>(null);
  const [selected, setSelected] = useState<Data | null>(null), [source, setSource] = useState<Data | null>(null);
  const [replacements, setReplacements] = useState<Data[]>([]), [replacement, setReplacement] = useState('');
  const [draft, setDraft] = useState(''), [review, setReview] = useState<Data | null>(null), [acknowledged, setAcknowledged] = useState(false);
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('');
  const window = useRef(new MemoryReadingWindow()), session = useRef(''), reviewHandle = useRef('');
  const selectedRef = useRef<Data | null>(null), focus = useRef<HTMLElement | null>(null), reading = useRef<HTMLElement | null>(null);
  const collection = useRef<HTMLElement | null>(null), listPosition = useRef(0), returnFocus = useRef('');
  selectedRef.current = selected;
  const version = window.current.capture();
  const closeHandle = (handle: string) => { if (handle) void run('memoryHistory.close', { handle }).catch(() => {}); };
  const closeReview = () => { closeHandle(reviewHandle.current); reviewHandle.current = ''; setReview(null); setAcknowledged(false); };
  const clearReading = () => { setSelected(null); selectedRef.current = null; setSource(null); setReplacements([]); setReplacement(''); setDraft(''); closeReview(); };
  const reset = () => {
    window.current.invalidate(); closeHandle(session.current); session.current = '';
    setBusy(false); setPage(null); setCognition(null); setTask(null); setReceipt(null); clearReading(); setError(''); setNotice('');
  };
  const invoke = async (action: string, args: Data, accept?: (value: Data) => void) => {
    const ticket = version;
    if (!window.current.enter(ticket)) return;
    setBusy(true); setError('');
    try {
      let input = args;
      if (['memoryHistory.search', 'memoryHistory.cognition', 'taskMemory.search', 'taskMemory.task'].includes(action)) {
        if (!session.current) {
          const opened = await run('memoryHistory.open', { view });
          if (!window.current.current(ticket)) { closeHandle(opened.sessionHandle); return; }
          session.current = opened.sessionHandle;
        }
        input = { ...args, sessionHandle: session.current };
      }
      const result = await run(action, input);
      if (window.current.current(ticket)) { accept?.(result); return result; }
    } catch (failure) { if (window.current.current(ticket)) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { window.current.finish(ticket); if (window.current.current(ticket)) setBusy(false); }
  };
  const choose = (record: Data, identity = '') => {
    window.current.invalidate(); setBusy(false);
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
  const confirm = () => {
    const handle = reviewHandle.current;
    if (!review || !handle || handle !== review.reviewHandle || !acknowledged || !window.current.current(version)) return;
    reviewHandle.current = ''; setReview(null); setAcknowledged(false);
    void invoke(view === 'history' ? 'memoryHistory.confirm' : 'taskMemory.confirm', { reviewHandle: handle }, value => {
      clearReading(); setPage(null); setCognition(null); setReceipt(null); setTask(null);
      setNotice(value.feedback || '已收到原处理回执，请重新读取当前版本。原始证据保留。');
    });
  };
  useEffect(() => {
    window.current.reopen();
    return () => { window.current.close(); closeHandle(session.current); closeHandle(reviewHandle.current); session.current = ''; reviewHandle.current = ''; };
  }, []);
  useEffect(() => { reset(); }, [scopeKey]);
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
    registerTransientBack(review || selected ? () => { if (!busy) { if (review) closeReview(); else clearReading(); } return true; } : null);
    return () => registerTransientBack(null);
  }, [review, selected, busy, registerTransientBack]);
  useEffect(() => {
    const hidden = () => { if (document.visibilityState !== 'visible') reset(); };
    document.addEventListener('visibilitychange', hidden); return () => document.removeEventListener('visibilitychange', hidden);
  }, []);
  useEffect(() => {
    if (!task?.taskHandle || !receipt) return;
    const timer = setInterval(() => { if (document.visibilityState === 'visible') void invoke('taskMemory.receipt', { taskHandle: task.taskHandle }, acceptReceipt); }, 2000);
    return () => clearInterval(timer);
  }, [task?.taskHandle, !!receipt, version]);
  const details = taskMemorySelectionDetails(receipt);
  const pageRecords = rows(view === 'history' ? page?.matches : page?.tasks);
  const resultGroup = (row: Data) => view === 'history' ? (['pending_conflict', 'current', 'historical'].includes(row.temporalStatus) ? row.temporalStatus : 'unknown') : (['awaiting_user', 'blocked', 'failed'].includes(row.status) ? 'attention' : ['completed', 'cancelled'].includes(row.status) ? 'finished' : 'active');
  const resultGroups = (view === 'history' ? [{ id: 'pending_conflict', label: '冲突待核' }, { id: 'current', label: '当前记录' }, { id: 'historical', label: '历史版本' }, { id: 'unknown', label: '时间或主体待核对' }] : [{ id: 'attention', label: '需要处理' }, { id: 'active', label: '其他原任务' }, { id: 'finished', label: '已结束' }]).map(group => ({ ...group, records: pageRecords.filter(row => resultGroup(row) === group.id) })).filter(group => group.records.length);
  return <section ref={collection} tabIndex={-1} className="pw-memory-history pw-knowledge-workspace" aria-label="旧事与任务来源">
    {!selected && <header className="pw-knowledge-heading"><h2>{view === 'history' ? '查阅旧事' : '核对任务来源'}</h2></header>}
    {!selected && <SelectField label="查阅内容" value={view} options={[{ id: 'history', label: '旧事与主体认知' }, { id: 'tasks', label: '原任务与本轮记忆来源' }]} isDisabled={busy} onChange={value => { reset(); setView(value as 'history' | 'tasks'); setQuery(''); }} />}
    {error && <p className="pw-secondary-error" role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}{busy && <p className="pw-muted" role="status">正在核对原版本与回执…</p>}
    {!selected && <>
      <div className="pw-knowledge-search-actions">
      <SearchField aria-label={view === 'history' ? '旧事关键词或记载日期' : '搜索原任务'} placeholder={view === 'history' ? '关键词或 UTC 日期' : '任务关键词'} value={query} onChange={value => { reset(); setQuery(value); }} />
      <Button variant="secondary" disabled={busy || query.length > 500} onClick={() => search()}>{view === 'history' ? '查旧事' : '搜索任务记录'}</Button></div>
      {view === 'tasks' && initialTaskId && <Button variant="ghost" disabled={busy} onClick={() => openTask(initialTaskId)}>读取当前原任务</Button>}
      {view === 'history' && <Accordion variant="divided" className="pw-compact-disclosures"><AccordionItem id="cognition" title="主体当前认知与冲突">
        <p className="pw-muted">未知主体不会归入主人；计划与想象不参与当前现实事实。</p>
        <TextField label="稳定主体身份" value={subject} onChange={value => { reset(); setSubject(value); }} maxLength={200} isDisabled={busy} />
        <Button variant="ghost" disabled={busy || !subject.trim()} onClick={() => { clearReading(); setPage(null); setCognition(null); void invoke('memoryHistory.cognition', { subjectId: subject.trim() }, setCognition); }}>读取当前认知</Button>
      </AccordionItem></Accordion>}
      {!page && !cognition && !task && !busy && <p className="pw-knowledge-empty-copy">{view === 'history' ? '输入关键词或记载日期，查找保存过的原文。也可以按主体核对当前认知与未决冲突。' : '搜索原任务，再查看当时提供给模型的记忆凭据。'}</p>}
      {cognition && <section aria-label="已读取的主体认知"><p className="pw-knowledge-caption">当前 {rows(cognition.current).length} · 历史 {rows(cognition.history).length} · 待审冲突 {rows(cognition.pendingConflicts).length}</p>
        {rows(cognition.conflictGroups).map(group => <ConflictReview key={group.conflictHandle} group={group} busy={busy} onReview={args => { void invoke('memoryHistory.conflict.review', args, openReview); }} />)}
        {(['current', 'history'] as const).map(key => rows(cognition[key]).length > 0 && <section className="pw-knowledge-directory-group" key={key}><header className="pw-knowledge-group-heading"><h3>{key === 'current' ? '当前认知' : '历史、计划或尚未生效'}</h3><span>{rows(cognition[key]).length}</span></header><div className="pw-memory-list">{rows(cognition[key]).map(row => <article key={row.id} className="pw-memory-list-item"><Button variant="ghost" className="pw-memory-row" data-history-id={`${key}:${row.id}`} trailingIcon={RiArrowRightSLine} disabled={busy} onClick={() => choose(row, `${key}:${row.id}`)}><span className="pw-memory-row-copy"><strong>{row.text}</strong><small>{row.assertion?.kind} · {row.assertionStatus}</small></span></Button></article>)}</div></section>)}
      </section>}
      {page && <section className="pw-knowledge-section"><h3 className="pw-knowledge-section-heading">{view === 'history' ? '匹配的旧事' : '原任务记录'}</h3>
        <p className="pw-muted">{view === 'history' ? `本页 ${rows(page.matches).length} / 匹配 ${page.totalMatches ?? '未知'}。未列出不代表从未保存。` : `本页 ${rows(page.tasks).length} 条原任务 · ${page.coverage === 'full_task_store' ? '完整任务记录检索' : '以原宿主声明范围为准'}`}</p>
        {page.unverifiedArchiveRecords > 0 && <p className="pw-muted">另有 {page.unverifiedArchiveRecords} 条未核归档；查看不会激活历史包。</p>}
        {resultGroups.map(group => <section key={group.id} className="pw-knowledge-directory-group" aria-label={group.label}><header className="pw-knowledge-group-heading"><h3>{group.label}</h3><span>{group.records.length}</span></header><div className="pw-memory-list">{group.records.map(row => <article key={row.id || row.taskId} className="pw-memory-list-item"><Button variant="ghost" className="pw-memory-row" data-history-id={`result:${row.id || row.taskId}`} trailingIcon={RiArrowRightSLine} disabled={busy} onClick={() => view === 'history' ? void invoke('memoryHistory.record', { recordHandle: row.recordHandle }, value => choose({ ...value.record, readHandle: value.readHandle }, `result:${row.id || row.taskId}`)) : openTask(row.taskId)}><span className="pw-memory-row-copy"><strong>{row.textPreview || row.goal || row.title || row.taskId}</strong><small>{view === 'history' ? memoryHistoryStatus(row.temporalStatus) : `${row.status || '状态未知'} · 任务记录，不是正式记忆`}</small></span></Button></article>)}</div></section>)}
        {!pageRecords.length && <p className="pw-knowledge-empty-copy">这次搜索没有匹配项。可以换一个关键词；未列出不代表从未保存。</p>}
        {page.nextCursor && <Button variant="ghost" disabled={busy} onClick={() => search(page.nextCursor)}>下一页</Button>}
      </section>}
      {task && <section className="pw-source-record"><h3>{task.goal || '所选原任务'}</h3><p className="pw-muted">{task.status}</p><div className="pw-form-actions"><Button variant="ghost" disabled={busy} onClick={() => { void invoke('taskMemory.focus', { taskHandle: task.taskHandle }); }}>打开这条原任务</Button><Button variant="secondary" disabled={busy || !task.referenceAvailable} onClick={readReceipt}>读取本轮记忆凭据</Button></div>{!task.referenceAvailable && <p>此原任务没有提供给模型的记忆凭据。不会用当前检索补写当时的来源。</p>}</section>}
      {receipt && <section aria-label="本次提供给模型的记忆来源"><p>{taskMemorySummary(receipt)}</p>{!taskMemoryVisibleRecords(receipt).length && <p className="pw-muted">原凭据没有可显示的记忆条目。</p>}
        {details && <Accordion variant="divided"><AccordionItem id="selection" title="查看本次选择与省略"><p>当时提供核心参考 {details.core} 条 · 任务匹配 {details.retrieved} 条 · 使用 {details.chars} 字符。</p>{Object.entries(details.counts).map(([key, count]) => <p key={key}>{key === 'record_limit' ? '条数预算已满' : key === 'character_limit' ? '字符预算已满' : key === 'record_too_large' ? '条目过长' : '来源或版本变化'}：{String(count)} 条</p>)}<p className="pw-muted">省略版本仅保留标识，不保留旧正文。</p>{details.omitted.map((row: Data) => <p key={row.id}>{row.id} · 修订 {row.revision}</p>)}</AccordionItem></Accordion>}
        <section className="pw-knowledge-directory-group"><header className="pw-knowledge-group-heading"><h3>本次提供的原版本</h3><span>{taskMemoryVisibleRecords(receipt).length}</span></header><div className="pw-memory-list">{taskMemoryVisibleRecords(receipt).map(row => <article key={row.id} className="pw-memory-list-item"><Button variant="ghost" className="pw-memory-row" data-history-id={`receipt:${row.id}`} trailingIcon={RiArrowRightSLine} disabled={busy || !row.readHandle} onClick={() => choose(row, `receipt:${row.id}`)}><span className="pw-memory-row-copy"><strong>{row.text || '原正文已失效，不再显示'}</strong><small>{row.id} · 修订 {row.revision} · {row.status}</small></span></Button></article>)}</div></section>
      </section>}
    </>}
    {selected && <section ref={reading} tabIndex={-1} className="pw-memory-reading"><header className="pw-memory-detail-header"><Button variant="ghost" leadingIcon={RiArrowLeftSLine} disabled={busy} onClick={clearReading}>返回来源列表</Button><h2>{view === 'history' ? '这份已存记忆' : '这次提供的原版本'}</h2></header>
      <div className="pw-memory-document"><p className="pw-reading-copy">{selected.text}</p></div><p className="pw-muted">原修订 {selected.revision} · {view === 'history' ? memoryHistoryStatus(selected.temporalStatus) : selected.status}。摘要不是原件。</p>
      {selected.assertion && <Accordion variant="divided" className="pw-compact-disclosures"><AccordionItem id="history-assertion" title="主体与关系" description={selected.assertion.subjectLabel || '原记录的主体信息'}><MemoryAssertionFields readOnly candidate={selected} /></AccordionItem></Accordion>}
      <div className="pw-form-actions">{rows(selected.assertion?.evidenceSpans).map((_, index) => view === 'history' && <Button key={`span-${index}`} variant="ghost" disabled={busy} onClick={() => readSource(index, true)}>回读确切原句 {index + 1}</Button>)}{rows(selected.sourceRefs).map((_, index) => <Button key={`source-${index}`} variant="ghost" disabled={busy} onClick={() => readSource(index, false)}>查看原来源 {index + 1}</Button>)}</div>
      {view === 'tasks' && <div className="pw-form-actions">{rows(selected.sourceRefs).map((ref, index) => ref.kind === 'task_evidence' && <Button key={index} variant="ghost" disabled={busy} onClick={() => { if (selectedRef.current === selected) void invoke('taskMemory.source.focus', { readHandle: selected.readHandle, index }); }}>打开来源原任务 {index + 1}</Button>)}</div>}
      {!rows(selected.assertion?.evidenceSpans).length && <p className="pw-muted">未附确切原文片段；这段记忆不作为主人原话认证。</p>}
      {source && <section className="pw-source-record"><h3>{source.title || '确切原来源'}</h3><p className="pw-muted">{source.verifiedOriginalSpan ? '原范围与指纹已核对；不证明陈述为真。' : source.status && source.status !== 'current' ? '原来源当前不可读，不显示旧正文。' : '原选择范围，不自动取得执行权限。'}</p><pre className="pw-source-copy" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{source.text}</pre>{source.sourceReadLimited && !source.hasMore && <p className="pw-muted">本次原宿主只返回了这一段原文。请打开来源原任务核对其余内容；没有把截取片段当作全文。</p>}{source.hasMore && <Button variant="ghost" disabled={busy} onClick={() => readSource(source.index, source.span, source.nextOffset)}>继续读取原文</Button>}</section>}
      <Accordion variant="divided" className="pw-compact-disclosures pw-memory-edit"><AccordionItem id="correction" title="纠正或遗忘">
        <TextAreaField label="正确的内容" value={draft} onChange={setDraft} maxLength={4000} isDisabled={busy} />
        <Button variant="secondary" disabled={busy || !draft.trim() || draft.trim() === selected.text} onClick={() => prepare('correct')}>核对纠正</Button>
        {view === 'tasks' && <><Button variant="ghost" disabled={busy} onClick={() => { void invoke('taskMemory.replacements', { readHandle: selected.readHandle }, value => { setReplacements(rows(value.records)); setReplacement(''); }); }}>选择另一条正式记忆替代</Button>{replacements.length > 0 && <><SelectField label="保留的正式记忆" value={replacement} options={replacements.map(row => ({ id: row.replacementHandle, label: row.text }))} onChange={setReplacement} isDisabled={busy} /><Button variant="secondary" disabled={busy || !replacement} onClick={() => prepare('replace')}>核对替代与旧版本停用</Button></>}<Button variant="ghost" disabled={busy} onClick={() => prepare('forget')}>核对遗忘</Button></>}
      </AccordionItem></Accordion>
    </section>}
    <SettingsDialog isOpen={!!review} onClose={() => { if (!busy) closeReview(); }} title={review?.operation === 'conflict' ? '核对这组原冲突版本' : review?.operation === 'forget' ? '遗忘这条原记忆' : '核对本次修改'}><div className="pw-knowledge-review">
      <p className="pw-reading-copy">{review?.before?.text}</p>{rows(review?.beforeRecords).map(row => <p key={row.id}>{row.text} · 修订 {row.revision}</p>)}{review?.afterText && <p className="pw-reading-copy">修改后：{review.afterText}</p>}{review?.reason && <p>理由：{review.reason}</p>}
      <p>只处理所示的精确版本。原会话与任务证据保留；保存不证明内容为真。旧上下文是否清理，以原宿主回执为准。</p>
      <CheckboxField isSelected={acknowledged} onChange={setAcknowledged}>我已核对这次内容与影响</CheckboxField><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={closeReview}>返回核对</Button><Button disabled={busy || !acknowledged} onClick={confirm}>确认这一次修改</Button></div>
    </div></SettingsDialog>
  </section>;
}

function ConflictReview({ group, busy, onReview }: { group: Data; busy: boolean; onReview: (args: Data) => void }) {
  const [selected, setSelected] = useState(''), [coexist, setCoexist] = useState(false), [reason, setReason] = useState('');
  return <section className="pw-source-record pw-knowledge-conflict"><h3>核对这组冲突</h3><p className="pw-muted">较晚推断不会自动替换主人纠正。请明确选择保留版本，或给出共存理由。</p><SelectField label="保留的当前事实" value={selected} options={rows(group.records).map(row => ({ id: row.id, label: `${row.reviewRole === 'protected_current' ? '当前保留：' : ''}${row.text}` }))} onChange={setSelected} isDisabled={busy || coexist} /><CheckboxField isSelected={coexist} onChange={setCoexist} isDisabled={busy}>有理由地共存，不选唯一当前值</CheckboxField><TextAreaField label="为什么保留或共存" value={reason} onChange={setReason} maxLength={1000} isDisabled={busy} /><Button variant="secondary" disabled={busy || (!coexist && !selected) || !reason.trim()} onClick={() => onReview({ conflictHandle: group.conflictHandle, decision: coexist ? 'coexist' : 'replace', selectedId: coexist ? '' : selected, reason })}>核对这组精确版本</Button></section>;
}
