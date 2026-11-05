import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { RiArrowLeftSLine, RiArrowRightSLine, RiRefreshLine } from '@remixicon/react';
import './memory-reading.css';
import { Tabs, TabList, Tab, TabPanel } from './shell-library';
import { Accordion, AccordionItem, Button, CheckboxField, IconButton, SearchField, SelectField, SettingsDialog, TextAreaField, TextField } from './forms-library';
import { MemoryAssertionFields } from './MemoryAssertionFields';
import { MemoryHistoryWorkspace } from './MemoryHistoryWorkspace';
import type { LearningCandidateTarget } from './LearningWorkspace';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
const rows = (value: any): Data[] => Array.isArray(value) ? value : [];
const layers: Record<string, string> = { fact: '事实', preference: '偏好', episodic: '经历', procedural: '方法' };
const layer = (name: string) => layers[name] || name || '其他内容';
const eligible = (row: Data) => row.active === true && row.consolidationBlocked === false && row.ownerId === 'owner' && row.spaceId === 'private';

/** Existing BoardUI reading rows, native namespace selection, and exact record review. */
export function MemoryWorkspace({ domain, run, registerTransientBack, initialCandidate = null, alreadyShownError = '' }: { domain: string; run: Run; registerTransientBack: (handler: (() => boolean) | null) => void; initialCandidate?: LearningCandidateTarget | null; alreadyShownError?: string }) {
  const [snapshot, setSnapshot] = useState<Data | null>(null), [selection, setSelection] = useState<Data | null>(null), [review, setReview] = useState<Data | null>(null), [source, setSource] = useState<Data | null>(null);
  const [assertionOpen, setAssertionOpen] = useState(false), [selectingConsolidation, setSelectingConsolidation] = useState(false);
  const [tab, setTab] = useState(domain === 'memoryReview' ? 'candidates' : 'records'), [query, setQuery] = useState(''), [draft, setDraft] = useState(''), [reason, setReason] = useState('');
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
  const invoke = async (action: string, args: Data = {}, accept?: (value: Data) => void) => {
    if (entered.current) return; entered.current = true; setBusy(true); setError('');
    try { const result = await run(action, args); if (alive.current) accept?.(result); return result; }
    catch (failure) { if (alive.current) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { entered.current = false; if (alive.current) setBusy(false); }
  };
  const closeHandle = (handle: string) => { if (handle) void run('knowledge.close', { handle }).catch(() => {}); };
  const closeReview = () => { closeHandle(reviewHandle.current); reviewHandle.current = ''; setReview(null); setAcknowledged(false); };
  const closeRecord = () => { closeReview(); closeHandle(readHandle.current); readHandle.current = ''; setSelection(null); setSource(null); setAttempted(false); setAssertionOpen(false); };
  const clearConsolidation = () => { selectedHandles.current.forEach(closeHandle); selectedHandles.current = []; setConsolidation([]); };
  const load = (candidate: LearningCandidateTarget | null = null) => {
    if (entered.current || !alive.current) return;
    closeRecord(); clearConsolidation(); setSnapshot(null);
    void invoke('knowledge.snapshot', {}, value => { setSnapshot(value); setScopeName(value.scopeName || ''); setNotice(''); }).then(value => {
      if (value && alive.current && candidate) { setTab('candidates'); choose({ id: candidate.id }, true, candidate); }
    });
  };
  const changeNamespace = async (value: string) => {
    if (entered.current || !alive.current) return;
    closeRecord(); clearConsolidation(); setHistory(null); setCapacity(null); setSelectingConsolidation(false);
    // Native selection retires the old scope and every choice handle, even if
    // the new scope fails. Do not present the old rows as a valid fallback.
    setSnapshot(null); setScopeName(''); setNamespace(''); setNamespaces([]); setNotice('');
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
  useEffect(() => { registerTransientBack(review || selection || tab === 'history' ? () => { if (historyBack.current?.()) return true; if (!review && !selection) return false; if (!busy) { if (review) closeReview(); else closeRecord(); } return true; } : null); return () => registerTransientBack(null); }, [review, selection, busy, tab, registerTransientBack]);
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
  const choose = (row: Data, candidate: boolean, origin: LearningCandidateTarget | null = null) => { openedRecord.current = row.id; listScrollPosition.current = collectionPanel.current?.closest('.pw-secondary-scroll')?.scrollTop || 0; closeRecord(); void invoke('knowledge.get', { id: row.id, candidate }, value => {
    if (origin && value.candidate?.id !== origin.id) { closeHandle(value.readHandle); throw new Error('原批次候选身份不符，请返回学习任务重新读取。'); }
    setSelection(value); readHandle.current = value.readHandle; const record = value.candidate || value.record;
    setDraft(record?.text || ''); setDraftMeta({ layer: record?.layer || 'fact', reason: record?.reason || '', applicability: record?.applicability || '', confidence: String(record?.confidence ?? 0.5) }); setReason(''); setAttempted(false); setNotice(origin ? `已定位“${origin.sourceTitle}”的原候选。批次登记修订 ${origin.revision}，当前修订 ${record?.revision ?? '未知'}；请按当前内容核对。` : '');
  }); };
  const prepare = (operation: string, extra: Data = {}) => { void invoke('knowledge.review', { readHandle: readHandle.current, operation, ...extra }, openReview); };
  const toggleConsolidation = (row: Data, checked: boolean) => {
    if (!checked) { const old = consolidation.find(item => item.record.id === row.id); if (old) { closeHandle(old.readHandle); selectedHandles.current = selectedHandles.current.filter(id => id !== old.readHandle); } setConsolidation(old => old.filter(item => item.record.id !== row.id)); return; }
    if (consolidation.length >= 20) { setError('一次最多归纳 20 条原记忆。'); return; }
    void invoke('knowledge.get', { id: row.id, candidate: false }, value => { selectedHandles.current.push(value.readHandle); setConsolidation(old => [...old, value]); });
  };
  const confirm = () => {
    const frozen = review; if (!frozen) return; const handle = reviewHandle.current; reviewHandle.current = ''; setReview(null); setAcknowledged(false);
    if (frozen.operation === 'consolidation') { void invoke('knowledge.consolidation.start', { reviewHandle: handle }, setOperation); return; }
    setAttempted(true); void invoke('knowledge.confirm', { reviewHandle: handle }, () => setNotice('已收到原处理回执，请重新读取原记录核对。'));
  };
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
  const list = rows(snapshot?.[tab]).filter(row => [row.text, row.title, row.name, layer(row.layer)].join(' ').toLocaleLowerCase().includes(query.toLocaleLowerCase()));
  const operationLabel: Record<string, string> = { correct: '保存纠正', forget: '遗忘这条记忆', core: review?.core ? '加入核心记忆' : '移出核心记忆', accept: '采用这条候选', reject: '拒绝这条候选', edit: '保存候选修改', archive: '清理终态候选正文', reconsider: '重新考虑被拒绝候选', assertion: '核对主体与关系', consolidation: '从所选记忆归纳候选' };
  const running = !!operation && !operation.settled;
  const candidateHasEdits = candidate && record && (draft !== record.text || draftMeta.layer !== (record.layer || 'fact') || draftMeta.reason !== (record.reason || '') || draftMeta.applicability !== (record.applicability || '') || draftMeta.confidence !== String(record.confidence ?? 0.5));
  return <div className="pw-memory-workspace">
    {error && error !== alreadyShownError && <p className="pw-secondary-error" role="alert">{error}</p>}{notice && <p className="pw-field-hint" role="status">{notice}</p>}{busy && <p className="pw-muted">正在核对原回执…</p>}
    <section ref={collectionPanel} className="pw-memory-collection" aria-label="记忆管理" hidden={!!selection}>
      <Tabs selectedKey={tab} onSelectionChange={value => { setTab(String(value)); closeRecord(); }} className="pw-team-tabs">
        <div className="pw-object-toolbar"><TabList aria-label="记忆视图"><Tab id="records" isDisabled={busy}>已保存</Tab><Tab id="candidates" isDisabled={busy}>待审候选</Tab><Tab id="history" isDisabled={busy}>旧事与来源</Tab></TabList><IconButton className="pw-utility-icon" icon={RiRefreshLine} aria-label="重新读取记忆" title="重新读取记忆" disabled={busy} onClick={() => load()} /></div>
        <TabPanel id={tab}>
      <p className="pw-detail-location">{scopeName || (busy ? '正在核对记忆范围' : '记忆范围尚未确认')}</p>
      <Accordion variant="divided" className="pw-compact-disclosures"><AccordionItem id="memory-scope" title="范围与容量">
        <div className="pw-form-actions"><Button variant="ghost" disabled={busy || running} onClick={() => { void invoke('knowledge.namespaces', {}, value => setNamespaces(rows(value.choices))); }}>读取可用项目范围</Button><Button variant="ghost" disabled={busy} onClick={() => { void invoke('knowledge.capacity', {}, setCapacity); }}>候选容量</Button><Button variant="ghost" disabled={busy} onClick={() => { void invoke('knowledge.history', {}, setHistory); }}>已处理候选历史</Button></div>
        <SelectField label="本次查看范围" value={namespace} isDisabled={busy || running} options={[{ id: 'private', label: '主人全局 · 私人' }, ...(namespace && namespace !== 'private' && !namespaces.some(row => row.choiceHandle === namespace) ? [{ id: namespace, label: scopeName || '当前项目', disabled: true }] : []), ...namespaces.map(row => ({ id: row.choiceHandle, label: row.name || row.title || row.projectId }))]} onChange={value => { void changeNamespace(value); }} />
        {capacity && <p>候选容量：{capacity.candidatesUsed ?? '未知'} / {capacity.candidateLimit ?? '未知'}。清理终态候选只移除候选正文，不删除正式记忆。</p>}
        {history && <Accordion variant="divided">{rows(history.entries).map(row => <AccordionItem key={row.id} id={row.id} title={(row.text || '已清理正文，保留去重标记').split('\n')[0]} description={row.status}><p className="pw-reading-copy">{row.text}</p><p className="pw-muted">保留原处理状态；查看不会再次采用。</p></AccordionItem>)}</Accordion>}
      </AccordionItem></Accordion>
      {tab === 'history' ? <MemoryHistoryWorkspace key={namespace + ':' + String(!!snapshot)} scopeKey={namespace + ':' + String(snapshot?.cognitionRevision || '')} run={run} registerTransientBack={registerHistoryBack} /> : <>
      <SearchField className="pw-secondary-search" data-input-size="medium" aria-label="筛选当前读取的记忆" placeholder="查找记忆" value={query} onChange={setQuery} />
      {snapshot && !list.length && <p className="pw-secondary-empty">当前范围与筛选条件下没有记录。</p>}
      <div className="pw-memory-list-heading"><p>{snapshot ? `当前读取 ${list.length} 条` : busy ? '正在读取原记忆…' : namespace ? '尚未读取记忆，请重新读取。' : '尚未读取记忆，请先重新选择范围。'}</p>{tab === 'records' && <Button variant="ghost" disabled={busy || running} onClick={() => { if (selectingConsolidation) clearConsolidation(); setSelectingConsolidation(value => !value); }}>{selectingConsolidation ? '退出多选' : '选择记忆归纳'}</Button>}</div>
      <div className="pw-memory-list">{list.map(row => <article className="pw-memory-list-item" key={row.id}>
        <Button variant="ghost" className="pw-memory-row" data-memory-id={row.id} trailingIcon={RiArrowRightSLine} disabled={busy} onClick={() => choose(row, tab === 'candidates')} aria-label={`读取原版本与来源：${(row.text || row.title || '未命名内容').split('\n')[0].slice(0, 100)}`} aria-description={[layer(row.layer), row.core ? '核心记忆' : '', row.status || '', row.active === false ? '已停用' : ''].filter(Boolean).join('；')}>
          <span className="pw-memory-row-copy"><span>{row.text || row.title || '未命名内容'}</span><small>{[layer(row.layer), row.core ? '核心记忆' : '', row.status || '', row.active === false ? '已停用' : ''].filter(Boolean).join(' · ')}</small></span>
        </Button>
        {selectingConsolidation && tab === 'records' && eligible(row) && <CheckboxField className="pw-memory-select" isSelected={consolidation.some(item => item.record.id === row.id)} isDisabled={busy || running} onChange={checked => toggleConsolidation(row, checked)}>加入本次归纳选择</CheckboxField>}
      </article>)}</div>
      {consolidation.length > 0 && <div className="pw-form-actions"><span>已选择 {consolidation.length} 条原记忆</span><Button variant="secondary" disabled={busy || running || consolidation.length < 2} onClick={() => { void invoke('knowledge.consolidation.review', { readHandles: consolidation.map(item => item.readHandle) }, openReview); }}>核对所选记忆与模型调用</Button><Button variant="ghost" disabled={busy || running} onClick={clearConsolidation}>清空本次选择</Button></div>}
      {operation && <section className="pw-source-record"><h3>这次原归纳</h3><p>{running ? '正在处理所选原版本，尚未确认形成候选。' : operation.error || (operation.result?.outcome === 'existing_candidate_barrier' ? '相同来源已有原候选处理记录，没有再次生成。请刷新候选核对。' : '原候选回执已取得。请刷新候选，阅读后再决定是否采用。')}</p><div className="pw-form-actions"><Button variant="ghost" disabled={busy} onClick={() => { const handle = operation.operationHandle; void invoke('knowledge.consolidation.query', { operationHandle: handle }, value => setOperation({ ...value, operationHandle: handle })); }}>核对原结果</Button>{running && <Button variant="secondary" disabled={stopping} onClick={() => { setStopping(true); void run('knowledge.consolidation.stop', { operationHandle: operation.operationHandle }).then(() => setNotice('已收到原停止请求回执，请核对原结果。')).catch(failure => setError(failure.message)).finally(() => setStopping(false)); }}>停止这次原归纳</Button>}</div></section>}
      </>}
        </TabPanel>
      </Tabs>
    </section>
    {selection && <section ref={selectionPanel} tabIndex={-1} className="pw-source-record pw-memory-reading"><header className="pw-memory-detail-header"><Button variant="ghost" leadingIcon={RiArrowLeftSLine} disabled={busy} onClick={closeRecord}>返回列表</Button><h2>{candidate ? '候选审阅' : '这条记忆'}</h2></header>
      <div className="pw-memory-document"><p className="pw-reading-copy">{record?.text}</p>{record?.applicability && <p className="pw-memory-context">适用条件：{record.applicability}</p>}{record?.reason && <p className="pw-memory-context">依据：{record.reason}</p>}</div><p className="pw-muted">{layer(record?.layer)} · 原修订 {record?.revision} · {record?.status || (record?.active ? '当前可用' : '已停用')}</p>
      {record?.assertion && <p>主体：{record.assertion.subjectLabel || '未知'} · 陈述类型：{record.assertion.kind} · 关系：{record.assertion.predicate} · {record.assertion.object}。保存不证明陈述为真。</p>}
      <div className="pw-form-actions">{rows(record?.sourceRefs).map((ref, index) => <Button key={ref.sourceId + ':' + ref.revision} variant="ghost" disabled={busy || attempted} onClick={() => { void invoke('knowledge.source', { readHandle: readHandle.current, index, offset: 0 }, setSource); }}>查看来源 {index + 1}</Button>)}{candidate && rows(record?.recordRefs).map((ref, index) => <Button key={ref.id + ':' + ref.revision} variant="ghost" disabled={busy || attempted} onClick={() => { void invoke('knowledge.parent', { readHandle: readHandle.current, index }, value => setSource({ title: '父记忆的精确版本', text: value.record?.text })); }}>查看父记忆 {index + 1}</Button>)}</div>
      {source && <section className="pw-source-record"><h3>{source.title}</h3><pre className="pw-source-copy" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{source.text}</pre>{source.hasMore && <Button variant="ghost" disabled={busy} onClick={() => { void invoke('knowledge.source', { readHandle: readHandle.current, index: source.index, offset: source.nextOffset }, setSource); }}>继续读取原文</Button>}</section>}
      {!attempted && (!candidate || record?.status === 'pending') && <>
        <Accordion variant="divided" className="pw-compact-disclosures pw-memory-edit"><AccordionItem id="memory-edit" title={candidate ? '修改候选内容' : '纠正这条记忆'} description="修改后先核对，再保存到原版本">
        <TextAreaField label={candidate ? '候选正文' : '纠正后的正文'} value={draft} onChange={setDraft} maxLength={4000} isDisabled={busy} />
        {candidate && <Accordion variant="divided" className="pw-compact-disclosures"><AccordionItem id="candidate-context" title="类型、依据与适用条件"><SelectField label="记忆类型" value={draftMeta.layer} options={Object.entries(layers).map(([id, label]) => ({ id, label }))} onChange={value => setDraftMeta(old => ({ ...old, layer: value }))} isDisabled={busy} /><TextAreaField label="候选依据" value={draftMeta.reason} onChange={value => setDraftMeta(old => ({ ...old, reason: value }))} maxLength={1000} isDisabled={busy} /><TextAreaField label="适用条件" value={draftMeta.applicability} onChange={value => setDraftMeta(old => ({ ...old, applicability: value }))} maxLength={1000} isDisabled={busy} /><TextField label="可信度（0 到 1，不代表事实已核实）" value={draftMeta.confidence} onChange={value => setDraftMeta(old => ({ ...old, confidence: value }))} isDisabled={busy} /></AccordionItem></Accordion>}
        <div className="pw-form-actions"><Button variant="secondary" disabled={busy || !draft.trim() || (!candidate && draft === record?.text)} onClick={() => prepare(candidate ? 'edit' : 'correct', candidate ? { draft: { text: draft, ...draftMeta, confidence: Number(draftMeta.confidence) } } : { text: draft })}>核对内容修改</Button></div></AccordionItem></Accordion>
        {candidateHasEdits && <p className="pw-memory-context" role="status">未保存的编辑不会随采用提交。请先核对内容修改。</p>}
        <div className="pw-form-actions pw-memory-decisions">{candidate ? <Button disabled={busy} onClick={() => prepare('accept')}>核对并采用候选</Button> : <><Button variant="ghost" disabled={busy} onClick={() => prepare('core', { core: !record?.core })}>{record?.core ? '核对移出核心记忆' : '核对加入核心记忆'}</Button><Button variant="ghost" disabled={busy} onClick={() => prepare('forget')}>核对遗忘影响</Button></>}</div>
        {candidate && <><Accordion variant="divided" className="pw-compact-disclosures"><AccordionItem id="memory-reject" title="拒绝这条候选"><TextField label="拒绝理由" value={reason} onChange={setReason} maxLength={1000} isDisabled={busy} /><Button variant="ghost" disabled={busy || !reason.trim()} onClick={() => prepare('reject', { reason })}>核对并拒绝候选</Button></AccordionItem></Accordion><Accordion variant="divided" className="pw-compact-disclosures" expandedKeys={assertionOpen ? ['assertion'] : []} onExpandedChange={keys => setAssertionOpen(keys.has('assertion'))}><AccordionItem id="assertion" title="主体、关系与时间">{assertionOpen && <MemoryAssertionFields key={selection.readHandle} readHandle={selection.readHandle} candidate={record} run={run} onReview={openReview} />}</AccordionItem></Accordion></>}
      </>}
      {!attempted && candidate && ['accepted', 'rejected', 'invalidated'].includes(record?.status) && <div className="pw-form-actions">{record.status === 'rejected' && <Button variant="secondary" disabled={busy} onClick={() => prepare('reconsider')}>重新考虑这一条拒绝项</Button>}<Button variant="ghost" disabled={busy} onClick={() => prepare('archive')}>核对并清理终态候选正文</Button></div>}
      {attempted && <p className="pw-muted">这一次提交已结束或结果待核对。请重新读取原记录查看现状；没有自动重发。</p>}
    </section>}
    <SettingsDialog isOpen={!!review} onClose={() => { if (!busy) closeReview(); }} title={operationLabel[review?.operation] || '核对记忆修改'}>
      {review?.operation === 'consolidation' ? <><p>向开始时的当前模型发送以下原记忆，归纳为待审候选。原记忆保留；不会自动采用新候选。</p>{rows(review.records).map(row => <p key={row.id} className="pw-reading-copy">{row.text}</p>)}</> : <><p className="pw-muted">确认只应用到刚才读取的原版本。</p><h3>原内容</h3><p className="pw-reading-copy">{review?.before?.text}</p>{review?.afterText && <><h3>修改后</h3><p className="pw-reading-copy">{review.afterText}</p></>}{review?.reason && <p>理由：{review.reason}</p>}{review?.assertion && <p>主体：{review.assertion.subject?.label || '主人'} · {review.assertion.kind} · {review.assertion.predicate} · {review.assertion.object}。此修改继续留在待审候选。</p>}</>}
      {review?.operation === 'forget' && <p>将遗忘原记忆，退休相关派生上下文，并阻止相同旧来源自动重新导入；原消息保留。</p>}{review?.operation === 'accept' && <p>采用候选不会证明内容已独立核实，也不会自动启用技能或取得执行权限。</p>}{review?.operation === 'archive' && <p>将移除这条终态候选的正文与拒绝理由；已接受的正式记忆和必要去重标记保留。这项清理不能恢复正文。</p>}{review?.operation === 'reconsider' && <p>只将这一条完整拒绝项重新放回待审；不会自动采用，也不会解除其他拒绝项或来源遗忘屏障。</p>}
      <CheckboxField isSelected={acknowledged} onChange={setAcknowledged}>我已阅读内容和影响，确认这一次操作</CheckboxField><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={closeReview}>返回</Button><Button disabled={busy || !acknowledged} onClick={confirm}>{review?.operation === 'consolidation' ? '开始这次归纳' : '确认这次修改'}</Button></div>
    </SettingsDialog>
  </div>;
}
