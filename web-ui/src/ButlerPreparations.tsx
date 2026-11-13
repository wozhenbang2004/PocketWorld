import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Accordion, AccordionItem, Button, IconButton, RadioOptions, SelectField } from './forms-library';
import { Drawer, PermissionReview } from './shell-library';
import { IconArrowLeft, IconChat, IconChevronRight, IconFolder, IconMonitor, IconRefresh } from './icons';
import { WorkContract } from './OwnerWorkFacts';
import './butler-preparations.css';

type Data = Record<string, any>;
export type ButlerPreparationTarget = { sourceTaskId: string; proposalId: string; sourceConversationId: string };
type Shared = { can: (action: string) => boolean; run: (action: string, args?: Data) => Promise<any> };
const rows = (value: unknown): Data[] => Array.isArray(value) ? value.filter(row => row && typeof row === 'object' && !Array.isArray(row)) : [];
const text = (value: unknown) => typeof value === 'string' ? value : '';
const failureText = (value: unknown) => value instanceof Error ? value.message : String(value);
const ownerWindow = () => { const view = bridge.getState().view as Data; return [bridge.getDocumentEpoch(), view.section, view.taskId, view.resumeRevision].join('|'); };
const keyOf = (row: Data) => `${row.sourceTaskId}:${row.proposalId}`;
const preparation = (value: Data, target?: ButlerPreparationTarget) => value && typeof value.sourceTaskId === 'string' && typeof value.proposalId === 'string' && typeof value.sourceConversationId === 'string' && ['phone', 'project'].includes(value.kind) && value.executionAuthorized === false && (!target || keyOf(value) === keyOf(target) && value.sourceConversationId === target.sourceConversationId);
const stateLabels: Record<string, string> = { proposed: '待你核对', prepared: '已建立原任务', started: '已关联执行', dismissed: '已取消提案', unavailable: '原来源不可用' };
const targetStateLabels: Record<string, string> = { queued: '等待执行', preparing: '准备执行', running: '正在执行', verifying: '核对结果中', needs_confirmation: '结果等你核对', awaiting_user: '等你补充', waiting_approval: '等待授权', paused: '已暂停', interrupted: '已中断', completed: '执行已完成', failed: '本次未完成', cancelled: '已停止', stopping: '正在停止' };
const contractOf = (value: Data | null) => value?.contract && typeof value.contract.goal === 'string' && ['constraints', 'deliverable', 'completionCriteria'].every(key => value.contract[key] === undefined || typeof value.contract[key] === 'string') ? value.contract as { goal: string; constraints?: string; deliverable?: string; completionCriteria?: string } : undefined;

/** The retained ConversationWork rows, backed by the durable proposal index. */
export function ButlerPreparationEntries({ conversationId = '', sourceTaskId, representedTaskId, active, refreshKey, can, run, onOpen, onPresenceChange }: Shared & {
  conversationId?: string; sourceTaskId?: string; active: boolean; refreshKey: string; onOpen: (target: ButlerPreparationTarget) => void;
  representedTaskId?: string;
  onPresenceChange?: (present: boolean) => void;
}) {
  const [entries, setEntries] = useState<Data[]>([]), [cursor, setCursor] = useState(''), [more, setMore] = useState(false);
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [expanded, setExpanded] = useState(false);
  const alive = useRef(true), flight = useRef(false), sequence = useRef(0), attempted = useRef(''), latest = useRef({ active, can, run }); latest.current = { active, can, run };
  const load = async (append = false) => {
    if (!latest.current.active || flight.current || !latest.current.can('butler.preparations.list')) return;
    const original = ++sequence.current, window = ownerWindow(); flight.current = true; setBusy(true); setError('');
    try {
      const value = await latest.current.run('butler.preparations.list', { conversationId, cursor: append ? cursor : '', limit: 12 });
      if (!alive.current || original !== sequence.current || ownerWindow() !== window) return;
      if (!Array.isArray(value.entries) || !value.entries.every((row: Data) => preparation(row) && (!conversationId || row.sourceConversationId === conversationId)) || typeof value.hasMore !== 'boolean' || typeof value.nextCursor !== 'string' || value.hasMore && !value.nextCursor) throw new Error('原工作准备列表尚未完整读取。');
      const next = value.entries as Data[];
      setEntries(previous => append ? [...previous, ...next.filter(row => !previous.some(old => keyOf(old) === keyOf(row)))] : next);
      setCursor(value.nextCursor); setMore(value.hasMore);
    } catch (failure) { if (alive.current && original === sequence.current) setError(failureText(failure)); }
    finally { flight.current = false; if (alive.current && original === sequence.current) setBusy(false); }
  };
  const loadRef = useRef(load); loadRef.current = load;
  useEffect(() => { alive.current = true; return () => { alive.current = false; sequence.current++; }; }, []);
  const readKey = JSON.stringify([conversationId, sourceTaskId, refreshKey, ownerWindow()]);
  const readable = can('butler.preparations.list');
  useEffect(() => { if (!active || !readable) { attempted.current = ''; return; } if (!busy && !flight.current && attempted.current !== readKey) { attempted.current = readKey; void loadRef.current(); } }, [active, busy, readKey, readable]);
  // Remove only progress already shown by the current-task row, and settled
  // history. An allocated task may still need admission or receipt recovery.
  const relevant = entries.filter(row => {
    if (sourceTaskId) return row.sourceTaskId === sourceTaskId;
    const target = row.target;
    if (!target?.taskId) return !['dismissed', 'unavailable'].includes(row.state);
    if (target.taskId === representedTaskId) return false;
    return !target.runId || target.hasUnresolvedOperations !== false || !['completed', 'cancelled', 'canceled', 'stopped'].includes(target.status);
  });
  const visible = expanded ? relevant : relevant.slice(0, 3);
  const present = readable && (relevant.length > 0 || !!error || more);
  useEffect(() => { onPresenceChange?.(present); return () => onPresenceChange?.(false); }, [present, onPresenceChange]);
  if (!present) return null;
  return <section className="pw-butler-preparations" aria-label={sourceTaskId ? '关联的准备记录' : '待处理与进行中的工作'} aria-busy={busy}>
    {relevant.length > 0 && <header><h2>{sourceTaskId ? '关联的准备' : '工作与准备'}</h2></header>}
    {error && <p className="pw-error" role="alert">{error}</p>}
    {error && <Button variant="ghost" size="small" leadingIcon={IconRefresh} disabled={!active || busy} onClick={() => void load()}>重新读取工作记录</Button>}
    <ol>{visible.map(row => <li key={keyOf(row)}><Button variant="ghost" className="pw-preparation-row" leadingIcon={row.kind === 'phone' ? IconMonitor : IconFolder} trailingIcon={IconChevronRight} disabled={!active || busy} onClick={() => onOpen({ sourceTaskId: row.sourceTaskId, proposalId: row.proposalId, sourceConversationId: row.sourceConversationId })}>
      <span><strong dir="auto">{text(row.goal) || (row.kind === 'phone' ? '原手机工作提案' : '原项目工作提案')}</strong><small>{row.target?.taskId ? targetStateLabels[row.target.status] || '原任务状态待核对' : stateLabels[row.state] || '状态待核对'} · {row.kind === 'phone' ? '手机操作' : '项目开发'}</small></span>
    </Button></li>)}</ol>
    {relevant.length > 3 && <Button variant="ghost" size="small" disabled={!active || busy} onClick={() => setExpanded(value => !value)}>{expanded ? '收起' : `查看另 ${relevant.length - 3} 项`}</Button>}
    {more && <Button variant="ghost" size="small" disabled={!active || busy} onClick={() => { setExpanded(true); void load(true); }}>{busy ? '正在读取…' : '查看更早的工作'}</Button>}
  </section>;
}

type Choices = { mode: string; projectId: string; checkoutId: string };
const choicesOf = (value: Data): Choices => ({ mode: ['main_screen', 'background'].includes(value.mode) ? value.mode : 'background', projectId: text(value.projectId), checkoutId: text(value.checkoutId) });
const encodeChoices = (value: Choices, kind: string) => kind === 'phone' ? JSON.stringify([value.mode]) : JSON.stringify([value.projectId, value.checkoutId]);

/** Reuses the existing work goal, project selectors, PermissionReview and Vaul
 * object page. Only the native typed proposal can enter the original admission. */
export function ButlerPreparationDetail({ target, projects, can, run, onClose, onOpenTask, onConversation, onSetup, registerBack }: Shared & {
  target: ButlerPreparationTarget; projects: { id: string; name: string }[]; onClose: () => void; onOpenTask: (id: string) => void;
  onConversation?: (id: string) => void; onSetup?: (kind: 'phone' | 'project') => void; registerBack: (handler: (() => boolean) | null) => void;
}) {
  const [view, setView] = useState<Data | null>(null), [review, setReview] = useState<Data | null>(null), [capabilities, setCapabilities] = useState<Data[]>([]);
  const [choices, setChoices] = useState<Choices>(() => choicesOf({})), [inspection, setInspection] = useState<Data | null>(null), [copy, setCopy] = useState<Data | null>(null), [receipt, setReceipt] = useState<Data | null>(null);
  const [busy, setBusy] = useState(false), [saving, setSaving] = useState(false), [error, setError] = useState(''), [draftError, setDraftError] = useState(''), [stale, setStale] = useState(false), [saved, setSaved] = useState(true);
  const alive = useRef(true), entered = useRef(false), generation = useRef(0), handle = useRef(''), viewRef = useRef(view), reviewRef = useRef(review), receiptRef = useRef(receipt);
  const choicesRef = useRef(choices), baseline = useRef(''), touched = useRef(false), needsSave = useRef(false), flight = useRef<Promise<void> | null>(null), paused = useRef(false);
  const scroller = useRef<HTMLDivElement>(null), heading = useRef<HTMLHeadingElement>(null), reviewPosition = useRef(0), wasReview = useRef(false);
  const latest = useRef({ run, can, onClose }); latest.current = { run, can, onClose }; viewRef.current = view; reviewRef.current = review; receiptRef.current = receipt;
  const invoke = async (action: string, args: Data, accept?: (value: Data) => void, raw = false) => {
    if (entered.current || document.hidden || !latest.current.can(action)) return;
    const original = handle.current, window = ownerWindow(), ticket = generation.current;
    if (!raw && !original) return;
    entered.current = true; setBusy(true); setError('');
    try {
      const value = await latest.current.run(action, raw ? args : { viewHandle: original, ...args });
      if (!alive.current || ticket !== generation.current || window !== ownerWindow() || document.hidden) return;
      accept?.(value); return value as Data;
    } catch (failure) { if (alive.current && ticket === generation.current) setError(failureText(failure)); }
    finally { entered.current = false; if (alive.current) setBusy(false); }
  };
  const read = () => invoke('butler.preparation.read', { sourceTaskId: target.sourceTaskId, proposalId: target.proposalId }, value => {
    if (!preparation(value, target) || !text(value.viewHandle)) throw new Error('没有取得这份原提案的页面，请重新读取。');
    handle.current = value.viewHandle; setView(value); setStale(false); setReview(null); setInspection(null); setCapabilities([]);
    const nativeChoices = choicesOf(value.preparationDraft || {});
    baseline.current = encodeChoices(nativeChoices, value.kind);
    needsSave.current = value.preparationDraft?.saved === false;
    if (!touched.current) { choicesRef.current = nativeChoices; setChoices(nativeChoices); }
    setSaved(!needsSave.current && baseline.current === encodeChoices(choicesRef.current, value.kind));
    if (needsSave.current) setDraftError('原选择尚未确认写入本机，请保留本页并重新保存。');
    setCopy(value.preparationDraft?.copyRequestId ? { projectId: nativeChoices.projectId, requestId: value.preparationDraft.copyRequestId, queryRequired: true } : null);
    setReceipt(value.originalStart || null); paused.current = false;
  }, true);
  const readRef = useRef(read); readRef.current = read;
  const closeNative = (original: string) => { if (original) void latest.current.run('butler.preparation.close', { viewHandle: original }).catch(() => {}); };
  useEffect(() => {
    alive.current = true; let window = ownerWindow();
    const retire = () => { generation.current++; const original = handle.current; handle.current = ''; closeNative(original); if (alive.current) { setStale(true); setReview(null); setInspection(null); setCapabilities([]); } };
    const inspect = () => { const next = ownerWindow(); if (next !== window || document.hidden) { window = next; retire(); } };
    const unsubscribe = bridge.subscribe(inspect); document.addEventListener('visibilitychange', inspect);
    void readRef.current();
    return () => { alive.current = false; retire(); unsubscribe(); document.removeEventListener('visibilitychange', inspect); };
  }, [target.sourceTaskId, target.proposalId]);
  async function flush(explicit = false): Promise<void> {
    if (flight.current) return flight.current;
    const currentView = viewRef.current;
    if (!currentView || !needsSave.current && baseline.current === encodeChoices(choicesRef.current, currentView.kind)) return;
    if (paused.current && !explicit || !handle.current || document.hidden || entered.current) throw new Error('选择还没有保存，请保留本页并重新保存。');
    const original = handle.current, ticket = generation.current; paused.current = false;
    const operation = (async () => { setSaving(true); try {
      while (alive.current && (needsSave.current || baseline.current !== encodeChoices(choicesRef.current, currentView.kind))) {
        const next = { ...choicesRef.current }, encoded = encodeChoices(next, currentView.kind);
        const value = await latest.current.run('butler.preparation.draft', { viewHandle: original, ...(currentView.kind === 'phone' ? { mode: next.mode } : { projectId: next.projectId, checkoutId: next.checkoutId }) });
        if (!alive.current || ticket !== generation.current || original !== handle.current) throw new Error('原准备页面已变化，选择仍保留，请重新读取后保存。');
        if (value.saved !== true || encodeChoices(choicesOf(value.preparationDraft || {}), currentView.kind) !== encoded) throw new Error('原准备选择的保存回执尚未核对。');
        baseline.current = encoded; needsSave.current = false; setDraftError(''); setSaved(encoded === encodeChoices(choicesRef.current, currentView.kind));
        if (currentView.kind === 'project' && choicesRef.current.projectId === next.projectId) setCopy(previous => value.preparationDraft?.copyRequestId ? previous?.projectId === next.projectId && previous?.requestId === value.preparationDraft.copyRequestId ? previous : { projectId: next.projectId, requestId: value.preparationDraft.copyRequestId, queryRequired: true } : null);
      }
    } catch (failure) { paused.current = true; if (alive.current) { setDraftError(failureText(failure)); setSaved(false); } throw failure; }
    finally { flight.current = null; if (alive.current) setSaving(false); } })();
    flight.current = operation; return operation;
  }
  const edit = (next: Choices) => { if (entered.current || reviewRef.current) return; touched.current = true; if (choicesRef.current.projectId !== next.projectId) setInspection(null); choicesRef.current = next; setChoices(next); setSaved(false); setReview(null); setError(''); if (!paused.current) void flush().catch(() => {}); };
  const close = async (after?: () => void) => {
    if (entered.current) return;
    try { await flush(); const original = handle.current; handle.current = ''; closeNative(original); latest.current.onClose(); after?.(); }
    catch (failure) { if (alive.current) setDraftError(failureText(failure)); }
  };
  const back = useRef<() => boolean>(() => true);
  back.current = () => { if (!entered.current) { if (reviewRef.current) setReview(null); else void close(); } return true; };
  useEffect(() => { registerBack(() => back.current()); return () => registerBack(null); }, [registerBack]);
  useLayoutEffect(() => {
    if (!scroller.current || wasReview.current === !!review) return;
    if (review) { reviewPosition.current = scroller.current.scrollTop; scroller.current.scrollTop = 0; heading.current?.focus({ preventScroll: true }); }
    else scroller.current.scrollTop = reviewPosition.current;
    wasReview.current = !!review;
  }, [!!review]);
  const afterSave = async (operation: () => void | Promise<unknown>) => { try { await flush(true); if (alive.current) await operation(); } catch (failure) { if (alive.current) setDraftError(failureText(failure)); } };
  const inspectProject = () => void afterSave(() => invoke('butler.preparation.project.choices', { projectId: choicesRef.current.projectId }, value => {
    if (value.projectId !== choicesRef.current.projectId || !Array.isArray(value.checkouts)) throw new Error('私人副本信息不属于当前所选项目。'); setInspection(value);
  }));
  const copyProject = (query: boolean) => void afterSave(async () => {
    const projectId = choicesRef.current.projectId; if (!query) setCopy({ projectId, queryRequired: true });
    await invoke(query ? 'butler.preparation.project.queryCopy' : 'butler.preparation.project.prepare', { projectId }, value => {
      if (value.projectId !== projectId || !text(value.requestId) || value.replayAllowed !== false) throw new Error('原副本准备回执尚未核对。');
      setCopy(value); if (value.found === true) setInspection(null);
    });
  });
  const prepareReview = () => void afterSave(() => invoke(view?.kind === 'phone' ? 'butler.preparation.phone.review' : 'butler.preparation.project.review', view?.kind === 'phone' ? { mode: choicesRef.current.mode } : { projectId: choicesRef.current.projectId, checkoutId: choicesRef.current.checkoutId }, value => {
    if (!text(value.reviewHandle) || value.goal !== contractOf(viewRef.current)?.goal || !contractOf(value)) throw new Error('审阅没有完整对应原提案目标，请重新核对。');
    if (viewRef.current?.kind === 'phone' && (value.mode !== choicesRef.current.mode || value.conversationId !== target.sourceConversationId || !text(value.requestId) || !text(value.screenDisclosure) || !text(value.modelName) || ![value.scopeMinutes, value.toolLimit, value.modelCallLimit].every(Number.isSafeInteger))) throw new Error('手机屏幕披露与本次范围尚未完整返回。');
    if (viewRef.current?.kind === 'project' && (value.projectId !== choicesRef.current.projectId || value.checkoutId !== choicesRef.current.checkoutId || !text(value.source?.sourceDigest) || !text(value.model?.label))) throw new Error('项目副本、源码与模型审阅尚未完整返回。');
    setReview(value);
  }));
  const acceptReceipt = (value: Data, original?: Data | null) => {
    if (preparation(value, target)) { setView(previous => ({ ...previous, ...value })); setReceipt(null); return; }
    const expected = original || receiptRef.current;
    if (!['accepted', 'rejected', 'unknown'].includes(value.status) || expected?.kind === 'phone' && value.requestId !== expected.requestId || expected?.kind === 'project' && value.clientMessageId !== expected.clientMessageId || value.status === 'accepted' && !text(value.taskId)) throw new Error('原启动回执身份尚未完整核对，请查询原请求。');
    setReceipt(value); setReview(null);
  };
  const start = () => {
    const original = reviewRef.current, currentView = viewRef.current; if (!original || !currentView || entered.current) return;
    const retained = { ...original, kind: currentView.kind, clientMessageId: currentView.clientMessageId, status: 'unknown', message: '原启动请求待核对，请查询原请求。' };
    setReceipt(retained); setReview(null);
    void invoke(currentView.kind === 'phone' ? 'butler.preparation.phone.start' : 'butler.preparation.project.start', { reviewHandle: original.reviewHandle }, value => acceptReceipt(value, retained));
  };
  const blocked = busy || saving || stale || !view || !handle.current, unresolved = !!receipt && ['pending', 'unknown', 'accepted'].includes(receipt.status);
  const canPrepare = !blocked && view?.canPrepare === true && !unresolved && !review && can('butler.preparation.draft');
  const checkouts = rows(inspection?.checkouts), contract = contractOf(view), targetTaskId = text(receipt?.taskId) || text(view?.target?.taskId);
  const projectName = projects.find(project => project.id === choices.projectId)?.name || '原项目';
  return <Drawer.Root open onOpenChange={open => { if (!open) void close(); }} dismissible={!busy && !saving} direction="right" handleOnly shouldScaleBackground={false}>
    <Drawer.Portal><Drawer.Overlay className="pw-overlay pw-work-object-overlay" /><Drawer.Content className="pw-drawer pw-task-drawer pw-work-object-surface pw-preparation-detail" aria-describedby="pw-preparation-description">
      <header className="pw-drawer-header pw-task-header"><IconButton icon={IconArrowLeft} aria-label={review ? '返回准备选择' : '返回原页面'} disabled={busy || saving} onClick={() => back.current()} /><div><Drawer.Title ref={heading} tabIndex={-1}>{review ? '核对本次执行' : '管家为你准备的工作'}</Drawer.Title><Drawer.Description id="pw-preparation-description">{view?.kind === 'phone' ? '原请求 · 手机操作' : view?.kind === 'project' ? '原请求 · 项目开发' : '读取原提案与准备状态'}</Drawer.Description></div></header>
      <div ref={scroller} className="pw-drawer-body pw-task-body pw-preparation-body">
        {error && <p className="pw-error" role="alert">{error}</p>}{draftError && <p className="pw-error" role="alert">{draftError}</p>}
        {draftError && !saved && <Button variant="ghost" disabled={busy || saving} onClick={() => { const original = handle.current; handle.current = ''; closeNative(original); onClose(); }}>放弃本页未保存的选择并返回</Button>}
        {(!view || stale) && <section><p className="pw-muted">{stale ? '原准备窗口已变化，保留选择后重新核对即可继续。' : busy ? '正在读取原提案…' : '原提案尚未完整读取。'}</p><Button variant="secondary" disabled={busy || saving} onClick={() => void read()}>重新读取原提案</Button></section>}
        {view && <>
          <div className="pw-owner-work-handling"><div><strong>{stateLabels[view.state] || '状态待核对'}</strong><p>{text(view.reason) || (targetTaskId ? '这份准备已关联原任务，按实际执行进展查看结果。' : '核对目标、所需能力与本次范围后，再决定开始。')}</p></div></div>
          {contract && !review && <WorkContract goal={contract.goal} contract={contract} />}
          {receipt && <section className="pw-preparation-receipt" aria-label="原启动记录"><p role="status">{text(receipt.message) || (receipt.status === 'accepted' ? '原任务已保存。' : '原启动回执待核对。')}</p>{receipt.error && <p className="pw-error">{text(receipt.error)}</p>}{['pending', 'unknown'].includes(receipt.status) && <Button variant="secondary" disabled={blocked} onClick={() => void invoke('butler.preparation.query', {}, acceptReceipt)}>查询原启动请求</Button>}</section>}
          {targetTaskId && <Button variant="secondary" trailingIcon={IconChevronRight} disabled={busy || saving || !can('tasks.open')} onClick={() => void close(() => onOpenTask(targetTaskId))}>查看原工作与结果</Button>}
          {view.canPrepare === true && !unresolved && !review && <section className="pw-preparation-choices" aria-label="本次执行准备">
            {view.kind === 'phone' ? <RadioOptions label="在哪个屏幕执行" value={choices.mode} options={[{ id: 'background', label: '后台独立屏' }, { id: 'main_screen', label: '手机主屏' }]} onChange={mode => edit({ ...choicesRef.current, mode })} isDisabled={!canPrepare} /> : <>
              <SelectField label="选择真实项目" value={choices.projectId} options={[{ id: '', label: '选择要工作的项目' }, ...projects.map(project => ({ id: project.id, label: project.name })), ...(choices.projectId && !projects.some(project => project.id === choices.projectId) ? [{ id: choices.projectId, label: '原项目暂不可用，请重新核对' }] : [])]} isDisabled={!canPrepare} onChange={projectId => { setCopy(null); edit({ ...choicesRef.current, projectId, checkoutId: '' }); }} />
              {view.projectId && !choices.projectId && <p className="pw-muted">提案已包含项目线索，请从真实项目中明确选择本次对象。</p>}
              {choices.projectId && <Button variant="secondary" disabled={!canPrepare} onClick={inspectProject}>读取这个项目的私人副本</Button>}
              {inspection && inspection.projectId === choices.projectId && <><SelectField label="本次工作的私人副本" value={choices.checkoutId} options={[{ id: '', label: checkouts.length ? '选择已有私人副本' : '尚未准备私人副本' }, ...checkouts.map(row => ({ id: row.checkoutId, label: text(row.name) || projectName })), ...(choices.checkoutId && !checkouts.some(row => row.checkoutId === choices.checkoutId) ? [{ id: choices.checkoutId, label: '原副本暂不可用，请核对' }] : [])]} isDisabled={!canPrepare} onChange={checkoutId => edit({ ...choicesRef.current, checkoutId })} /><p className="pw-muted">{inspection.runtime?.canExecute === true ? '本机运行环境可用，仍需核对本次源码与模型。' : text(inspection.runtime?.message || inspection.runtime?.configurationProblem) || '运行环境尚待准备。'}</p></>}
              {choices.projectId && (!copy || copy.registered === false) && inspection && !checkouts.length && <Button variant="secondary" disabled={!canPrepare} onClick={() => copyProject(false)}>准备原项目的私人副本</Button>}
              {choices.projectId && <Button variant="ghost" disabled={!canPrepare} onClick={() => copyProject(true)}>查询原副本准备</Button>}
              {copy?.projectId === choices.projectId && <p className="pw-muted" role="status">{copy.found === true ? '原私人副本已准备，请重新读取副本列表后选择。' : copy.partial === true ? '原副本准备中断，已有文件保留。请在原项目入口核对。' : copy.queryRequired ? '原副本准备已有记录，请先查询原请求。' : '这次查询没有读到完整副本，未重复创建。'}</p>}
            </>}
            {!saved && <Button variant="secondary" disabled={blocked} onClick={() => void flush(true).catch(() => {})}>保存保留的选择</Button>}
            <div className="pw-preparation-start"><Button variant="primary" disabled={!canPrepare || view.kind === 'project' && (!choices.projectId || !choices.checkoutId)} onClick={prepareReview}>核对{view.kind === 'phone' ? '手机范围' : '源码与模型'}</Button><span role="status">{saving ? '正在保存选择…' : saved ? '准备选择已保留' : '本页选择尚未确认保存'}</span></div>
            <Accordion variant="divided"><AccordionItem id="capabilities" title="核对所需能力" description="设备与运行环境的当前情况">
              <Button variant="ghost" leadingIcon={IconRefresh} disabled={blocked} onClick={() => void invoke('butler.capabilities', { purpose: view.kind }, value => { if (value.observationOnly !== true || !Array.isArray(value.capabilities)) throw new Error('能力状态尚未完整读取。'); setCapabilities(value.capabilities); }, true)}>读取当前能力状态</Button>
              {capabilities.map(row => <section key={row.id}><h3>{text(row.label)}</h3><p>{text(row.detail)}</p>{row.state && <p className="pw-muted">{({ busy: '当前资源占用中', preparation_required: '需要完成准备', review_required: '可以进入本次审阅', selection_required: '需要选择真实执行对象', unavailable: '当前不可用' } as Record<string, string>)[row.state] || '请按实际范围核对'}</p>}</section>)}
              {onSetup && <Button variant="secondary" disabled={busy || saving} onClick={() => void close(() => onSetup(view.kind))}>打开{view.kind === 'phone' ? '手机' : '本机工具'}准备</Button>}
            </AccordionItem></Accordion>
          </section>}
          {review && <PermissionReview title={text(review.goal)} context={view.kind === 'phone' ? review.mode === 'background' ? '在后台独立屏执行' : '在手机主屏执行' : `${projectName} · 原私人副本`} reason={view.kind === 'phone' ? review.screenDisclosure : '按已选择的源码与模型建立原项目任务，命令执行与项目导回继续使用各自的原审阅入口。'} fields={view.kind === 'phone' ? [{ label: '结果返回', value: review.conversationTitle || '原私人对话' }, { label: '本次模型', value: review.modelName }, { label: '工具调用上限', value: review.toolLimit }, { label: '模型调用上限', value: review.modelCallLimit }, { label: '本次期限', value: review.scopeMinutes === 0 ? '不设截止时间' : `${review.scopeMinutes} 分钟` }] : [{ label: '本次模型', value: text(review.model?.label) }, { label: '运行位置', value: text(review.runtime) }, { label: '固定副本', value: choices.checkoutId }, { label: '源码版本', value: text(review.source?.sourceDigest) }]} pending={busy} onReject={() => setReview(null)} onApprove={!blocked ? start : undefined} approveLabel="确认并开始这次工作"><WorkContract goal={text(review.goal)} contract={contractOf(review)} /></PermissionReview>}
          <nav className="pw-task-source-links" aria-label="原委托与准备"><Button variant="ghost" disabled={busy || saving || !can('tasks.open')} onClick={() => void close(() => onOpenTask(target.sourceTaskId))}>查看原委托记录</Button>{onConversation && <Button variant="ghost" leadingIcon={IconChat} disabled={busy || saving} onClick={() => void close(() => onConversation(target.sourceConversationId))}>回到原委托话题</Button>}{view.canPrepare === true && !unresolved && !review && <Button variant="ghost" disabled={blocked} onClick={() => void afterSave(() => invoke('butler.preparation.dismiss', {}, value => { if (!preparation(value, target)) throw new Error('原提案取消回执尚未核对。'); setView(value); }))}>取消这份提案</Button>}</nav>
        </>}
      </div>
    </Drawer.Content></Drawer.Portal></Drawer.Root>;
}
