import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { IconChevronLeft, IconChevronRight, IconMemory, IconRefresh, IconSearch } from './icons';
import './knowledge-depth.css';
import './memory-reading.css';
import { Tabs, TabList, Tab, TabPanel } from './shell-library';
import { Accordion, AccordionItem, Button, CheckboxField, IconButton, SearchField, SelectField, SettingsDialog, TextAreaField, TextField } from './forms-library';
import { MemoryAssertionFields } from './MemoryAssertionFields';
import { MemoryHistoryWorkspace } from './MemoryHistoryWorkspace';
import { WorkingStatus } from './library/journey-motion';
import { FormDraftNotice, useFormDrafts, type RegisterBeforeLeave } from './form-drafts';
import type { LearningCandidateTarget } from './LearningWorkspace';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
const rows = (value: any): Data[] => Array.isArray(value) ? value : [];
const layers: Record<string, string> = { fact: '事实', preference: '偏好', episodic: '经历', procedural: '方法' };
const layer = (name: string) => layers[name] || name || '其他内容';
const recordStatus = (value: string) => ({ pending: '待审阅', accepted: '已采用', rejected: '已拒绝', invalidated: '已失效', archived: '正文已清理' } as Record<string, string>)[value] || value;
const eligible = (row: Data) => row.active === true && row.consolidationBlocked === false && row.ownerId === 'owner' && row.spaceId === 'private';

/** Existing BoardUI reading rows, native namespace selection, and exact record review. */
export function MemoryWorkspace({ domain, run, registerTransientBack, registerBeforeLeave, initialCandidate = null, alreadyShownError = '' }: { domain: string; run: Run; registerTransientBack: (handler: (() => boolean) | null) => void; registerBeforeLeave?: RegisterBeforeLeave; initialCandidate?: LearningCandidateTarget | null; alreadyShownError?: string }) {
  const formDraft = useFormDrafts(run, 'memory', registerBeforeLeave);
  const [snapshot, setSnapshot] = useState<Data | null>(null), [selection, setSelection] = useState<Data | null>(null), [review, setReview] = useState<Data | null>(null), [source, setSource] = useState<Data | null>(null);
  const [snapshotCurrent, setSnapshotCurrent] = useState(false), currentList = useRef(false);
  const [assertionOpen, setAssertionOpen] = useState(false), [selectingConsolidation, setSelectingConsolidation] = useState(false);
  const [tab, setTab] = useState(domain === 'memoryReview' ? 'candidates' : 'records'), [query, setQuery] = useState(''), [draft, setDraft] = useState(''), [reason, setReason] = useState('');
  const [filter, setFilter] = useState('all');
  const [draftMeta, setDraftMeta] = useState<Data>({ layer: 'fact', reason: '', applicability: '', confidence: '0.5' });
  const [namespaces, setNamespaces] = useState<Data[]>([]), [namespace, setNamespace] = useState('private'), [history, setHistory] = useState<Data | null>(null), [capacity, setCapacity] = useState<Data | null>(null);
  const [scopeName, setScopeName] = useState('');
  const [consolidation, setConsolidation] = useState<Data[]>([]), [operation, setOperation] = useState<Data | null>(null), [stopping, setStopping] = useState(false);
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState(''), [acknowledged, setAcknowledged] = useState(false), [attempted, setAttempted] = useState(false);
  const selectionPanel = useRef<HTMLElement | null>(null), collectionPanel = useRef<HTMLElement | null>(null);
  const listScrollPosition = useRef(0), openedRecord = useRef('');
  const alive = useRef(true), entered = useRef(false), readHandle = useRef(''), reviewHandle = useRef(''), selectedHandles = useRef<string[]>([]);
  const historyBack = useRef<(() => boolean) | null>(null);
  const registerHistoryBack = useCallback((handler: (() => boolean) | null) => { historyBack.current = handler; }, []);
  const invoke = async (action: string, args: Data = {}, accept?: (value: Data) => void | Promise<void>) => {
    if (!alive.current || entered.current) return; entered.current = true; setBusy(true); setError('');
    try { const result = await run(action, args); if (alive.current) await accept?.(result); return result; }
    catch (failure) { if (alive.current) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { entered.current = false; if (alive.current) setBusy(false); }
  };
  const closeHandle = (handle: string) => { if (handle) void run('knowledge.close', { handle }).catch(() => {}); };
  const closeReview = () => { closeHandle(reviewHandle.current); reviewHandle.current = ''; setReview(null); setAcknowledged(false); };
  const closeRecord = () => { closeReview(); closeHandle(readHandle.current); readHandle.current = ''; setSelection(null); setSource(null); setAttempted(false); setAssertionOpen(false); };
  const clearConsolidation = () => { selectedHandles.current.forEach(closeHandle); selectedHandles.current = []; setConsolidation([]); };
  const load = (candidate: LearningCandidateTarget | null = null) => {
    if (entered.current || !alive.current) return;
    closeRecord(); clearConsolidation(); setHistory(null); setCapacity(null);
    // A same-scope refresh may keep its last reading on screen, never its admission.
    currentList.current = false; setSnapshotCurrent(false);
    void invoke('knowledge.snapshot', {}, value => { setSnapshot(value); currentList.current = true; setSnapshotCurrent(true); setScopeName(value.scopeName || ''); setNotice(''); }).then(value => {
      if (value && alive.current && candidate) { setTab('candidates'); choose({ id: candidate.id }, true, candidate); }
    });
  };
  const changeNamespace = async (value: string) => {
    if (entered.current || !alive.current) return;
    closeRecord(); clearConsolidation(); setHistory(null); setCapacity(null); setSelectingConsolidation(false);
    // Native selection retires the old scope and every choice handle, even if
    // the new scope fails. Do not present the old rows as a valid fallback.
    currentList.current = false; setSnapshotCurrent(false); setSnapshot(null); setScopeName(''); setNamespace(''); setNamespaces([]); setNotice('');
    const result = await invoke('knowledge.namespace', { choiceHandle: value === 'private' ? '' : value }, selected => {
      setNamespace(value); setScopeName(selected.scopeName || '');
    });
    if (!alive.current) return;
    // invoke releases its single-flight lock in finally. A nested load inside
    // accept would be discarded while that lock is still held.
    if (result) load();
    else setNotice('范围切换未完成，请重新选择范围。');
  };
  useEffect(() => { alive.current = true; load(initialCandidate); return () => { alive.current = false; closeHandle(readHandle.current); closeHandle(reviewHandle.current); selectedHandles.current.forEach(closeHandle); }; }, []);
  useEffect(() => { registerTransientBack(review || selection || tab === 'history' ? () => { if (historyBack.current?.()) return true; if (!review && !selection) return false; if (!busy) { if (review) closeReview(); else formDraft.guard(closeRecord); } return true; } : null); return () => registerTransientBack(null); }, [review, selection, busy, tab, registerTransientBack]);
  useEffect(() => {
    if (!operation?.operationHandle || operation.settled) return;
    const handle = operation.operationHandle; let active = true, reading = false;
    const timer = setInterval(() => {
      if (!active || reading || document.visibilityState !== 'visible') return; reading = true;
      void run('knowledge.consolidation.query', { operationHandle: handle }).then(value => { if (active && alive.current) setOperation({ ...value, operationHandle: handle }); }).catch(failure => { if (active && alive.current) setError(failure.message); active = false; }).finally(() => { reading = false; });
    }, 1500);
    return () => { active = false; clearInterval(timer); };
  }, [operation?.operationHandle, operation?.settled, run]);
  const openReview = (value: Data) => { setReview(value); reviewHandle.current = value.reviewHandle; setAcknowledged(false); };
  const choose = (row: Data, candidate: boolean, origin: LearningCandidateTarget | null = null) => { if (!currentList.current || entered.current) return; openedRecord.current = row.id; listScrollPosition.current = collectionPanel.current?.closest('.pw-secondary-scroll')?.scrollTop || 0; closeRecord(); void invoke('knowledge.get', { id: row.id, candidate }, async value => {
    if (origin && value.candidate?.id !== origin.id) { closeHandle(value.readHandle); throw new Error('原批次候选身份不符，请返回学习任务重新读取。'); }
    const record = value.candidate || value.record;
    const saved = await formDraft.open(String(record?.ownerId || 'owner') + ':' + String(record?.spaceId || scopeName || 'private'), (candidate ? 'candidate:' : 'record:') + row.id, String(record?.revision ?? ''), { text: record?.text || '', layer: record?.layer || 'fact', reason: record?.reason || '', applicability: record?.applicability || '', confidence: String(record?.confidence ?? 0.5) });
    if (!saved) { closeHandle(value.readHandle); return; }
    setSelection(value); readHandle.current = value.readHandle; setDraft(saved.text); setDraftMeta({ layer: saved.layer, reason: saved.reason, applicability: saved.applicability, confidence: saved.confidence }); setReason(''); setAttempted(false); setNotice(origin ? `已定位“${origin.sourceTitle}”的原候选。批次登记修订 ${origin.revision}，当前修订 ${record?.revision ?? '未知'}；请按当前内容核对。` : '');
  }); };
  const refreshSelection = (id: string, isCandidate: boolean) => {
    // Re-read storage after a write rather than keep an unusable, stale revision.
    // This also serves uncertain outcomes without repeating the mutation.
    void invoke('knowledge.snapshot', {}, value => {
      setSnapshot(value); currentList.current = true; setSnapshotCurrent(true); setScopeName(value.scopeName || ''); setHistory(null); setCapacity(null);
      clearConsolidation();
    }).then(value => {
      if (!value || !alive.current) return;
      const current = rows(value[isCandidate ? 'candidates' : 'records']).find(row => row.id === id);
      if (current) choose(current, isCandidate);
      else { closeRecord(); setNotice('当前记录已不在此范围中，列表已重新读取。'); }
    });
  };
  const prepare = (operation: string, extra: Data = {}) => { if ((formDraft.stale || !!formDraft.conflict || formDraft.requiresRecheck) && ['edit', 'correct', 'accept'].includes(operation)) return; void invoke('knowledge.review', { readHandle: readHandle.current, operation, ...extra }, openReview); };
  const toggleConsolidation = (row: Data, checked: boolean) => {
    if (!currentList.current || entered.current) return;
    if (!checked) { const old = consolidation.find(item => item.record.id === row.id); if (old) { closeHandle(old.readHandle); selectedHandles.current = selectedHandles.current.filter(id => id !== old.readHandle); } setConsolidation(old => old.filter(item => item.record.id !== row.id)); return; }
    if (consolidation.length >= 20) { setError('一次最多归纳 20 条原记忆。'); return; }
    void invoke('knowledge.get', { id: row.id, candidate: false }, value => { selectedHandles.current.push(value.readHandle); setConsolidation(old => [...old, value]); });
  };
  const confirm = async () => {
    const frozen = review; if (!frozen) return; const handle = reviewHandle.current; reviewHandle.current = ''; setReview(null); setAcknowledged(false);
    if (frozen.operation === 'consolidation') { void invoke('knowledge.consolidation.start', { reviewHandle: handle }, setOperation); return; }
    const id = frozen.before?.id, isCandidate = selection?.candidateSelected === true;
    setAttempted(true); if (['edit', 'correct', 'accept'].includes(frozen.operation)) { try { await formDraft.markSubmitted(); } catch (failure) { setError((failure as Error).message); return; } } void invoke('knowledge.confirm', { reviewHandle: handle }, () => setNotice('操作已完成，正在读取当前状态。')).then(value => {
      if (value && alive.current && id) { if (['edit', 'correct', 'accept', 'forget', 'archive'].includes(frozen.operation)) formDraft.discard(() => refreshSelection(id, isCandidate)); else refreshSelection(id, isCandidate); }
    });
  };
  useEffect(() => { if (selection) formDraft.update({ text: draft, ...draftMeta }); }, [selection, draft, draftMeta, formDraft.update]);
  const record = selection?.candidate || selection?.record, candidate = selection?.candidateSelected === true;
  useLayoutEffect(() => {
    const scroller = collectionPanel.current?.closest('.pw-secondary-scroll');
    if (scroller) scroller.scrollTop = selection ? 0 : listScrollPosition.current;
    if (selection) selectionPanel.current?.focus({ preventScroll: true });
    else if (openedRecord.current) Array.from(collectionPanel.current?.querySelectorAll<HTMLButtonElement>('[data-memory-id]') || []).find(row => row.dataset.memoryId === openedRecord.current)?.focus({ preventScroll: true });
  }, [selection]);
  useEffect(() => {
    if (initialCandidate && selection?.candidate?.id === initialCandidate.id) {
      selectionPanel.current?.scrollIntoView({ block: 'start' });
      selectionPanel.current?.focus({ preventScroll: true });
    }
  }, [initialCandidate?.id, selection?.candidate?.id]);
  const collection = rows(snapshot?.[tab]);
  const hasFilters = !!query.trim() || filter !== 'all';
  const pendingCandidates = rows(snapshot?.candidates).filter(row => row.status === 'pending').length;
  const list = collection.filter(row => [row.text, row.title, row.name, layer(row.layer)].join(' ').toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()))
    .filter(row => filter === 'all' || (filter === 'core' ? row.core === true : filter === 'inactive' ? row.active === false : filter === 'pending' ? row.status === 'pending' : row.status !== 'pending'));
  const canConsolidate = list.filter(eligible).length >= 2;
  const groupKey = (row: Data) => tab === 'candidates' ? row.status === 'pending' ? 'pending' : 'processed' : row.active === false ? 'inactive' : row.core ? 'core' : row.layer in layers ? row.layer : 'other';
  const groups = (tab === 'candidates' ? ['pending', 'processed'] : ['core', ...Object.keys(layers), 'other', 'inactive'])
    .map(key => ({ key, label: ({ core: '核心记忆', pending: '等待审阅', processed: '已处理候选', inactive: '已停用', other: '其他内容', ...layers } as Data)[key], records: list.filter(row => groupKey(row) === key) }))
    .filter(group => group.records.length > 0);
  const filters = tab === 'candidates' ? [{ id: 'all', label: '全部' }, { id: 'pending', label: '待审阅' }, { id: 'processed', label: '已处理' }] : [{ id: 'all', label: '全部' }, { id: 'core', label: '核心' }, { id: 'inactive', label: '已停用' }];
  const operationLabel: Record<string, string> = { correct: '保存纠正', forget: '遗忘这条记忆', core: review?.core ? '加入核心记忆' : '移出核心记忆', accept: '采用这条候选', reject: '拒绝这条候选', edit: '保存候选修改', archive: '清理终态候选正文', reconsider: '重新考虑被拒绝候选', assertion: '核对主体与关系', consolidation: '从所选记忆归纳候选' };
  const running = !!operation && !operation.settled;
  const candidateHasEdits = candidate && record && (draft !== record.text || draftMeta.layer !== (record.layer || 'fact') || draftMeta.reason !== (record.reason || '') || draftMeta.applicability !== (record.applicability || '') || draftMeta.confidence !== String(record.confidence ?? 0.5));
  return <div className="pw-memory-workspace pw-knowledge-workspace">
    <FormDraftNotice draft={formDraft} />
    {error && error !== alreadyShownError && <p className="pw-secondary-error" role="alert">{error}</p>}{notice && <p className="pw-field-hint" role="status">{notice}</p>}
    {snapshot && !snapshotCurrent && !busy && <p className="pw-field-hint" role="status">当前保留上次读取的内容，尚未核对最新状态。请重新读取后再操作。</p>}
    <section ref={collectionPanel} className="pw-memory-collection" aria-label="记忆管理" hidden={!!selection}>
      <Tabs selectedKey={tab} onSelectionChange={value => { setTab(String(value)); setFilter('all'); closeRecord(); }} className="pw-team-tabs pw-memory-tabs">
        <TabList aria-label="记忆视图"><Tab id="records" isDisabled={busy || !snapshotCurrent}>已保存</Tab><Tab id="candidates" isDisabled={busy || !snapshotCurrent}>待审阅</Tab><Tab id="history" isDisabled={busy || !snapshotCurrent}>旧事与来源</Tab></TabList>
        <TabPanel id={tab}>
      <header className="pw-memory-scope-heading"><div className="pw-memory-scope-copy"><p className="pw-detail-location">{scopeName || (busy ? '正在核对记忆范围' : '记忆范围尚未确认')}</p><WorkingStatus active={busy}>正在核对原回执…</WorkingStatus></div><IconButton className="pw-utility-icon" icon={IconRefresh} aria-label="重新读取记忆" title="重新读取记忆" disabled={busy} onClick={() => load()} /></header>
      {tab === 'history' ? <MemoryHistoryWorkspace key={namespace + ':' + String(!!snapshot)} scopeKey={namespace + ':' + String(snapshot?.cognitionRevision || '')} readOnly={busy || !snapshotCurrent} run={run} registerTransientBack={registerHistoryBack} /> : <>
      {snapshot && (collection.length > 0 || hasFilters) && <div className="pw-memory-tools">
        <SearchField className="pw-secondary-search" data-input-size="medium" aria-label="筛选当前读取的记忆" placeholder={tab === 'candidates' ? '查找候选' : '查找记忆'} clearLabel="清除搜索" value={query} onChange={setQuery} />
        <SelectField className="pw-memory-filter" label="记忆状态筛选" value={filter} options={filters} onChange={setFilter} />
      </div>}
      {snapshot && !list.length && <section className="pw-memory-empty" aria-label={hasFilters ? '没有匹配结果' : tab === 'candidates' ? '暂无待审候选' : '暂无已保存记忆'}>
        {hasFilters ? <IconSearch aria-hidden="true" /> : <IconMemory aria-hidden="true" />}
        <h3>{hasFilters ? '没有匹配的记忆' : tab === 'candidates' ? '还没有待审候选' : '还没有保存的记忆'}</h3>
        <p>{hasFilters ? '试试其他关键词，或清除当前筛选。' : tab === 'candidates' ? '有新的记忆候选时，会在这里等待你审阅。' : pendingCandidates ? '有待审阅的候选，确认采用后会出现在这里。' : '在对话中提出需要记住的信息，之后在这里查看与整理。'}</p>
        {hasFilters ? <Button variant="secondary" onClick={() => { setQuery(''); setFilter('all'); }}>清除筛选</Button> : tab === 'records' && pendingCandidates > 0 ? <Button disabled={busy} onClick={() => { setTab('candidates'); setQuery(''); setFilter('pending'); }}>查看待审候选</Button> : null}
      </section>}
      {!snapshot && !busy && <p className="pw-memory-unread">{namespace ? '尚未读取记忆，请重新读取。' : '尚未读取记忆，请先重新选择范围。'}</p>}
      {(list.length > 0 || selectingConsolidation) && <div className="pw-memory-list-heading"><p>{snapshot ? `已读取 ${list.length} 条` : '正在读取记忆…'}</p>{tab === 'records' && (canConsolidate || selectingConsolidation) && <Button variant="ghost" disabled={busy || running || !snapshotCurrent} onClick={() => { if (selectingConsolidation) clearConsolidation(); setSelectingConsolidation(value => !value); }}>{selectingConsolidation ? '退出多选' : '归纳记忆'}</Button>}</div>}
      {selectingConsolidation && tab === 'records' && <div className="pw-form-actions pw-memory-selection-actions" role="region" aria-label="本次记忆归纳选择"><span>已选 {consolidation.length} 条</span><Button variant="secondary" disabled={busy || running || !snapshotCurrent || consolidation.length < 2} onClick={() => { void invoke('knowledge.consolidation.review', { readHandles: consolidation.map(item => item.readHandle) }, openReview); }}>核对所选记忆与模型调用</Button><Button variant="ghost" disabled={busy || running || !consolidation.length} onClick={clearConsolidation}>清空本次选择</Button></div>}
      {groups.map(group => <section className="pw-knowledge-directory-group" key={group.key} aria-label={group.label}><header className="pw-knowledge-group-heading"><h3>{group.label}</h3><span>{group.records.length}</span></header><div className="pw-memory-list">{group.records.map(row => <article className="pw-memory-list-item" key={row.id}>
        <Button variant="ghost" className="pw-memory-row" data-memory-id={row.id} trailingIcon={IconChevronRight} disabled={busy || !snapshotCurrent} onClick={() => choose(row, tab === 'candidates')} aria-label={`读取原版本与来源：${(row.text || row.title || '未命名内容').split('\n')[0].slice(0, 100)}`} aria-description={[layer(row.layer), row.core ? '核心记忆' : '', recordStatus(row.status) || '', row.active === false ? '已停用' : ''].filter(Boolean).join('；')}>
          <span className="pw-memory-row-copy"><strong>{(row.text || row.title || '未命名内容').split('\n')[0]}</strong>{String(row.text || '').includes('\n') && <span className="pw-knowledge-row-preview">{row.text.slice(row.text.indexOf('\n') + 1)}</span>}<small>{[layer(row.layer), recordStatus(row.status) || '', row.active === false ? '已停用' : '', row.revision != null ? `修订 ${row.revision}` : ''].filter(Boolean).join(' · ')}</small></span>
        </Button>
        {selectingConsolidation && tab === 'records' && eligible(row) && <CheckboxField className="pw-memory-select" isSelected={consolidation.some(item => item.record.id === row.id)} isDisabled={busy || running || !snapshotCurrent} onChange={checked => toggleConsolidation(row, checked)}>加入本次归纳选择</CheckboxField>}
      </article>)}</div></section>)}
      {operation && <section className="pw-source-record"><h3>这次原归纳</h3><p>{running ? '正在处理所选原版本，尚未确认形成候选。' : operation.error || (operation.result?.outcome === 'existing_candidate_barrier' ? '相同来源已有原候选处理记录，没有再次生成。请刷新候选核对。' : '原候选回执已取得。请刷新候选，阅读后再决定是否采用。')}</p><div className="pw-form-actions"><Button variant="ghost" disabled={busy} onClick={() => { const handle = operation.operationHandle; void invoke('knowledge.consolidation.query', { operationHandle: handle }, value => setOperation({ ...value, operationHandle: handle })); }}>核对原结果</Button>{running && <Button variant="secondary" disabled={stopping} onClick={() => { setStopping(true); void run('knowledge.consolidation.stop', { operationHandle: operation.operationHandle }).then(() => setNotice('已收到原停止请求回执，请核对原结果。')).catch(failure => setError(failure.message)).finally(() => setStopping(false)); }}>停止这次原归纳</Button>}</div></section>}
      </>}
      <Accordion variant="divided" className="pw-compact-disclosures pw-memory-scope"><AccordionItem id="memory-scope" title="范围与容量">
        <div className="pw-form-actions"><Button variant="ghost" disabled={busy || running} onClick={() => { void invoke('knowledge.namespaces', {}, value => setNamespaces(rows(value.choices))); }}>读取可用项目范围</Button><Button variant="ghost" disabled={busy} onClick={() => { void invoke('knowledge.capacity', {}, setCapacity); }}>候选容量</Button><Button variant="ghost" disabled={busy} onClick={() => { void invoke('knowledge.history', {}, setHistory); }}>已清理候选记录</Button></div>
        <SelectField label="本次查看范围" value={namespace} isDisabled={busy || running} options={[{ id: 'private', label: '主人全局 · 私人' }, ...(namespace && namespace !== 'private' && !namespaces.some(row => row.choiceHandle === namespace) ? [{ id: namespace, label: scopeName || '当前项目', disabled: true }] : []), ...namespaces.map(row => ({ id: row.choiceHandle, label: row.name || row.title || row.projectId }))]} onChange={value => { void changeNamespace(value); }} />
        {capacity && <p>候选容量：{capacity.candidatesUsed ?? '未知'} / {capacity.candidateLimit ?? '未知'}。清理终态候选只移除候选正文，不删除正式记忆。</p>}
        {history && (rows(history.history).length ? <Accordion variant="divided">{rows(history.history).map(row => <AccordionItem key={row.id} id={row.id} title={row.outcome === 'forgotten' ? '已遗忘的候选' : recordStatus(row.outcome) || '候选处理记录'} description={typeof row.archivedAt === 'number' ? new Date(row.archivedAt).toLocaleString() : '清理时间未记录'}><p className="pw-muted">候选正文已清理，仅保留处理结果与去重记录。查看不会再次采用。</p><p className="pw-detail-location">候选编号：{row.id}</p>{row.acceptedRecordId && <p className="pw-detail-location">对应正式记忆：{row.acceptedRecordId}</p>}</AccordionItem>)}</Accordion> : <p className="pw-muted">当前范围没有已清理的候选记录。尚未清理的采用、拒绝或失效项可在“待审阅”的“已处理”中查看。</p>)}
      </AccordionItem></Accordion>
        </TabPanel>
      </Tabs>
    </section>
    {selection && <section ref={selectionPanel} tabIndex={-1} className="pw-source-record pw-memory-reading"><header className="pw-memory-detail-header"><Button variant="ghost" leadingIcon={IconChevronLeft} disabled={busy} onClick={() => formDraft.guard(closeRecord)}>返回列表</Button><div className="pw-memory-detail-copy"><h2>{candidate ? '候选审阅' : '这条记忆'}</h2><WorkingStatus active={busy}>正在核对原回执…</WorkingStatus></div></header>
      <div className="pw-memory-document"><p className="pw-reading-copy">{record?.text}</p>{record?.applicability && <p className="pw-memory-context">适用条件：{record.applicability}</p>}{record?.reason && <p className="pw-memory-context">依据：{record.reason}</p>}</div><p className="pw-muted pw-knowledge-status">{layer(record?.layer)}{record?.core ? ' · 核心记忆' : ''} · 原修订 {record?.revision} · {recordStatus(record?.status) || (record?.active ? '当前可用' : '已停用')}</p>
      {record?.assertion && <p>主体：{record.assertion.subjectLabel || '未知'} · 陈述类型：{record.assertion.kind} · 关系：{record.assertion.predicate} · {record.assertion.object}。保存不证明陈述为真。</p>}
      {!attempted && candidate && record?.status === 'pending' && <>{candidateHasEdits && <p className="pw-memory-context pw-knowledge-notice" role="status">先保存刚才的修改，再采用更新后的候选。</p>}<div className="pw-form-actions pw-memory-decisions"><Button disabled={busy || formDraft.stale || !!formDraft.conflict || formDraft.requiresRecheck} onClick={() => candidateHasEdits ? prepare('edit', { draft: { text: draft, ...draftMeta, confidence: Number(draftMeta.confidence) } }) : prepare('accept')}>{candidateHasEdits ? '先保存修改' : '核对并采用候选'}</Button></div></>}
<section className="pw-knowledge-evidence"><h3>来源与关联</h3>
      <div className="pw-form-actions">{rows(record?.sourceRefs).map((ref, index) => <Button key={ref.sourceId + ':' + ref.revision} variant="ghost" disabled={busy || attempted} onClick={() => { void invoke('knowledge.source', { readHandle: readHandle.current, index, offset: 0 }, setSource); }}>查看来源 {index + 1}</Button>)}{candidate && rows(record?.recordRefs).map((ref, index) => <Button key={ref.id + ':' + ref.revision} variant="ghost" disabled={busy || attempted} onClick={() => { void invoke('knowledge.parent', { readHandle: readHandle.current, index }, value => setSource({ title: '父记忆的精确版本', text: value.record?.text })); }}>查看父记忆 {index + 1}</Button>)}</div>
      {source && <section className="pw-source-record"><h3>{source.title}</h3><pre className="pw-source-copy" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{source.text}</pre>{source.hasMore && <Button variant="ghost" disabled={busy} onClick={() => { void invoke('knowledge.source', { readHandle: readHandle.current, index: source.index, offset: source.nextOffset }, setSource); }}>继续读取原文</Button>}</section>}
      </section>
      {!attempted && (!candidate || record?.status === 'pending') && <>
        <Accordion variant="divided" className="pw-compact-disclosures pw-memory-edit"><AccordionItem id="memory-edit" title={candidate ? '修改候选内容' : '纠正这条记忆'}>
        <TextAreaField label={candidate ? '候选正文' : '纠正后的正文'} value={draft} onChange={setDraft} maxLength={4000} isDisabled={busy} />
        {candidate && <Accordion variant="divided" className="pw-compact-disclosures"><AccordionItem id="candidate-context" title="类型、依据与适用条件"><SelectField label="记忆类型" value={draftMeta.layer} options={Object.entries(layers).map(([id, label]) => ({ id, label }))} onChange={value => setDraftMeta(old => ({ ...old, layer: value }))} isDisabled={busy} /><TextAreaField label="候选依据" value={draftMeta.reason} onChange={value => setDraftMeta(old => ({ ...old, reason: value }))} maxLength={1000} isDisabled={busy} /><TextAreaField label="适用条件" value={draftMeta.applicability} onChange={value => setDraftMeta(old => ({ ...old, applicability: value }))} maxLength={1000} isDisabled={busy} /><TextField label="可信度（0 到 1，不代表事实已核实）" value={draftMeta.confidence} onChange={value => setDraftMeta(old => ({ ...old, confidence: value }))} isDisabled={busy} /></AccordionItem></Accordion>}
        <div className="pw-form-actions"><Button variant="secondary" disabled={busy || formDraft.stale || !!formDraft.conflict || formDraft.requiresRecheck || !draft.trim() || (!candidate && draft === record?.text)} onClick={() => prepare(candidate ? 'edit' : 'correct', candidate ? { draft: { text: draft, ...draftMeta, confidence: Number(draftMeta.confidence) } } : { text: draft })}>核对内容修改</Button></div></AccordionItem></Accordion>
        {!candidate && <Accordion variant="divided" className="pw-compact-disclosures"><AccordionItem id="memory-maintenance" title="核心记忆与遗忘" description={record?.core ? '当前在核心记忆中；遗忘前会单独核对影响' : '按需加入核心，或核对遗忘影响'}><div className="pw-form-actions"><Button variant="ghost" disabled={busy} onClick={() => prepare('core', { core: !record?.core })}>{record?.core ? '核对移出核心记忆' : '核对加入核心记忆'}</Button><Button variant="ghost" disabled={busy} onClick={() => prepare('forget')}>核对遗忘影响</Button></div></AccordionItem></Accordion>}
        {candidate && <><Accordion variant="divided" className="pw-compact-disclosures"><AccordionItem id="memory-reject" title="拒绝这条候选"><TextField label="拒绝理由" value={reason} onChange={setReason} maxLength={1000} isDisabled={busy} /><Button variant="ghost" disabled={busy || !reason.trim()} onClick={() => prepare('reject', { reason })}>核对并拒绝候选</Button></AccordionItem></Accordion><Accordion variant="divided" className="pw-compact-disclosures" expandedKeys={assertionOpen ? ['assertion'] : []} onExpandedChange={keys => setAssertionOpen(keys.has('assertion'))}><AccordionItem id="assertion" title="主体、关系与时间">{assertionOpen && <MemoryAssertionFields key={selection.readHandle} readHandle={selection.readHandle} candidate={record} run={run} onReview={openReview} />}</AccordionItem></Accordion></>}
      </>}
      {!attempted && candidate && ['accepted', 'rejected', 'invalidated'].includes(record?.status) && <div className="pw-form-actions">{record.status === 'rejected' && <Button variant="secondary" disabled={busy} onClick={() => prepare('reconsider')}>重新考虑这一条拒绝项</Button>}<Button variant="ghost" disabled={busy} onClick={() => prepare('archive')}>核对并清理终态候选正文</Button></div>}
      {!attempted && <Button variant="ghost" disabled={busy} onClick={() => formDraft.discard(() => { setDraft(record?.text || ''); setDraftMeta({ layer: record?.layer || 'fact', reason: record?.reason || '', applicability: record?.applicability || '', confidence: String(record?.confidence ?? 0.5) }); closeRecord(); })}>放弃未提交的修改</Button>}
      {(attempted || formDraft.requiresRecheck) && <div className="pw-form-actions"><p className="pw-muted">本次提交已结束或结果待核对。重新读取只查询当前状态，不会重复提交。</p><Button variant="secondary" disabled={busy} onClick={() => { formDraft.clearSubmitted(); refreshSelection(record.id, candidate); }}>重新读取当前状态</Button></div>}
    </section>}
    <SettingsDialog isOpen={!!review} onClose={() => { if (!busy) closeReview(); }} title={operationLabel[review?.operation] || '核对记忆修改'}><div className="pw-knowledge-review">
      {review?.operation === 'consolidation' ? <><p>向开始时的当前模型发送以下原记忆，归纳为待审候选。原记忆保留；不会自动采用新候选。</p>{rows(review.records).map(row => <p key={row.id} className="pw-reading-copy">{row.text}</p>)}</> : <><p className="pw-muted">确认只应用到刚才读取的原版本。</p><h3>原内容</h3><p className="pw-reading-copy">{review?.before?.text}</p>{review?.afterText && <><h3>修改后</h3><p className="pw-reading-copy">{review.afterText}</p></>}{review?.reason && <p>理由：{review.reason}</p>}{review?.assertion && <p>主体：{review.assertion.subject?.label || '主人'} · {review.assertion.kind} · {review.assertion.predicate} · {review.assertion.object}。此修改继续留在待审候选。</p>}</>}
      {review?.operation === 'forget' && <p>将遗忘原记忆，退休相关派生上下文，并阻止相同旧来源自动重新导入；原消息保留。</p>}{review?.operation === 'accept' && <p>采用候选不会证明内容已独立核实，也不会自动启用技能或取得执行权限。</p>}{review?.operation === 'archive' && <p>将移除这条终态候选的正文与拒绝理由；已接受的正式记忆和必要去重标记保留。这项清理不能恢复正文。</p>}{review?.operation === 'reconsider' && <p>只将这一条完整拒绝项重新放回待审；不会自动采用，也不会解除其他拒绝项或来源遗忘屏障。</p>}
      <CheckboxField isSelected={acknowledged} onChange={setAcknowledged}>我已阅读内容和影响，确认这一次操作</CheckboxField><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={closeReview}>返回</Button><Button disabled={busy || !acknowledged} onClick={confirm}>{review?.operation === 'consolidation' ? '开始这次归纳' : '确认这次修改'}</Button></div>
    </div></SettingsDialog>
  </div>;
}
