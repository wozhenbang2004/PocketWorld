import { useCallback, useEffect, useRef, useState } from 'react';
import { Accordion, AccordionItem, Button, SelectField, SettingsDialog, TextAreaField } from './forms-library';
import { bridge } from './bridge';
import { ProjectCheckoutFiles } from './ProjectCheckoutFiles';
import './local-workspace.css';
import './project-development.css';
import './project-workspace-manga.css';

type Data = Record<string, any>;
type DeliveryRequirements = { constraints: string; deliverable: string; completionCriteria: string };
type DevelopmentDraft = DeliveryRequirements & { goal: string; checkoutId: string };
const deliveryFields = ['constraints', 'deliverable', 'completionCriteria'] as const;
const deliveryRequirements = (value: Data = {}): DeliveryRequirements => ({
  constraints: typeof value.constraints === 'string' ? value.constraints : '',
  deliverable: typeof value.deliverable === 'string' ? value.deliverable : '',
  completionCriteria: typeof value.completionCriteria === 'string' ? value.completionCriteria : '',
});
const sameDraft = (draft: DevelopmentDraft, original: Data) => draft.goal === original.goal && draft.checkoutId === original.checkoutId &&
  deliveryFields.every(key => draft[key] === (original[key] || ''));
type Props = { projectId: string; name: string; active: boolean; run: (action: string, args?: Data) => Promise<any>; can: (action: string) => boolean;
  onOpenTask: (id: string) => void; onPrepareTools: () => void; onConnectModel: () => void; onOpenTerminal: () => void;
  registerBack: (handler: (() => boolean) | null) => void; onInteractionBusyChange?: (busy: boolean) => void };
const rows = (value: unknown): Data[] => Array.isArray(value) ? value : [];
const ownerWindow = () => { const view = bridge.getState().view as Data; return [bridge.getDocumentEpoch(), view.section, view.taskId, view.resumeRevision].join('|'); };
const unresolved = (value: Data | null) => !!value && ['pending', 'unknown', 'unconfirmed'].includes(value.status);
const failureText = (failure: unknown) => failure instanceof Error ? failure.message : String(failure);

/** Existing project checkout + original Assistant admission. No command is
 * approved here, and reopening never repeats a submission or copy creation. */
export function ProjectDevelopmentWorkspace({ projectId, name, active, run, can, onOpenTask, onPrepareTools, onConnectModel, onOpenTerminal, registerBack, onInteractionBusyChange }: Props) {
  const [view, setView] = useState<Data | null>(null), [goal, setGoal] = useState(''), [checkout, setCheckout] = useState('');
  const [requirements, setRequirements] = useState<DeliveryRequirements>(() => deliveryRequirements());
  const [source, setSource] = useState<Data | null>(null), [review, setReview] = useState<Data | null>(null), [submission, setSubmission] = useState<Data | null>(null);
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [expired, setExpired] = useState(false), [copyResult, setCopyResult] = useState<Data | null>(null);
  const [filesOpen, setFilesOpen] = useState(false), filesBack = useRef<(() => boolean) | null>(null);
  const registerFilesBack = useCallback((handler: (() => boolean) | null) => { filesBack.current = handler; }, []);
  const alive = useRef(false), generation = useRef(0), entered = useRef<object | null>(null), handle = useRef(''), activeReview = useRef<Data | null>(null);
  const sourceAttempt = useRef(''), readAttempt = useRef(''), touched = useRef(false), draft = useRef<DevelopmentDraft>({ goal: '', checkoutId: '', ...deliveryRequirements() });
  const latest = useRef({ run, can, active }); latest.current = { run, can, active }; activeReview.current = review;
  const clearSubmittedDraft = (receipt: Data) => {
    if (receipt.status !== 'accepted' || !sameDraft(draft.current, receipt)) return;
    draft.current = { ...draft.current, goal: '', ...deliveryRequirements() }; setGoal(''); setRequirements(deliveryRequirements());
  };
  const applySubmission = (receipt: Data) => { setSubmission(receipt); clearSubmittedDraft(receipt); };
  const invoke = async (action: string, args: Data = {}, accept?: (value: Data) => void) => {
    if (!latest.current.active || entered.current || document.hidden || !latest.current.can(action)) return;
    const token = {}, window = ownerWindow(), sequence = generation.current;
    entered.current = token; setBusy(true); setError('');
    const current = () => alive.current && latest.current.active && entered.current === token && generation.current === sequence && ownerWindow() === window && !document.hidden;
    try {
      const value = await latest.current.run(action, args);
      if (current()) { accept?.(value); return value as Data; }
      if (action === 'projects.development.read' && value?.viewHandle) void latest.current.run('projects.development.close', { viewHandle: value.viewHandle }).catch(() => {});
    }
    catch (failure) { if (current()) setError(failureText(failure)); }
    finally { if (entered.current === token) { entered.current = null; if (alive.current) setBusy(false); } }
  };
  const read = () => {
    if (latest.current.active && !entered.current && !document.hidden && latest.current.can('projects.development.read')) readAttempt.current = ownerWindow();
    return invoke('projects.development.read', { projectId }, value => {
    if (value.projectId !== projectId || typeof value.viewHandle !== 'string') throw new Error('没有取得这个原项目的页面，请重新读取。');
    handle.current = value.viewHandle; setView(value); sourceAttempt.current = ''; setSource(null);
    if (!touched.current) {
      const retained = value.draft || {}, available = rows(value.checkouts);
      const selected = retained.checkoutId || (available.length === 1 ? available[0].checkoutId : '');
      draft.current = { goal: retained.goal || '', checkoutId: selected, ...deliveryRequirements(retained) };
      setGoal(draft.current.goal); setCheckout(selected); setRequirements(deliveryRequirements(retained));
    }
    setSubmission(value.draft?.submission || null);
    if (value.draft?.submission) clearSubmittedDraft(value.draft.submission);
    });
  };
  const readRef = useRef(read); readRef.current = read;
  const retain = (next: DevelopmentDraft) => {
    touched.current = true; draft.current = next;
    const original = handle.current, sequence = generation.current;
    if (original) void latest.current.run('projects.development.draft', { viewHandle: original, ...next }).catch(failure => {
      if (alive.current && sequence === generation.current && handle.current === original) setError(`草稿未保存：${failureText(failure)}`);
    });
  };
  const acceptReview = (value: Data) => {
    if (value.deliveryContract?.format !== 'pocketworld.owner-work-contract.v1' || value.deliveryContract.revision !== 1 || value.deliveryContract.goal !== value.goal)
      throw new Error('项目交付约定没有完整载入，请重新核对。');
    draft.current = { goal: value.goal, checkoutId: value.checkoutId, ...deliveryRequirements(value.deliveryContract) };
    setGoal(draft.current.goal); setRequirements(deliveryRequirements(value.deliveryContract)); setReview(value);
  };
  const dismiss = () => {
    if (entered.current) return;
    const original = activeReview.current; activeReview.current = null; setReview(null);
    if (original && handle.current) void latest.current.run('projects.development.dismiss', { viewHandle: handle.current, reviewHandle: original.reviewHandle }).catch(() => {});
  };
  useEffect(() => {
    registerBack(() => { if (!latest.current.active) return false; if (filesBack.current?.()) return true; if (entered.current) return true; if (activeReview.current) { dismiss(); return true; } return false; });
    return () => registerBack(null);
  }, [registerBack]);
  useEffect(() => {
    alive.current = true; let owner = ownerWindow();
    const retire = () => {
      generation.current++; entered.current = null;
      const original = handle.current; handle.current = ''; sourceAttempt.current = ''; readAttempt.current = ''; activeReview.current = null;
      if (alive.current) { setView(null); setReview(null); setSource(null); setBusy(false); setFilesOpen(false); }
      if (original) void latest.current.run('projects.development.close', { viewHandle: original }).catch(() => {});
    };
    // Switching the start method retires its window, never its plain draft or
    // the identity of a submitted operation. Returning performs a fresh read.
    if (!active) { retire(); return () => { alive.current = false; }; }
    const inspect = () => {
      const next = ownerWindow();
      if (next !== owner || document.hidden) { owner = next; retire(); }
      if (latest.current.active && !document.hidden && !handle.current && !entered.current && readAttempt.current !== next && latest.current.can('projects.development.read')) void readRef.current();
    };
    const unsubscribe = bridge.subscribe(inspect); document.addEventListener('visibilitychange', inspect); window.addEventListener('pagehide', retire);
    void readRef.current();
    return () => { alive.current = false; retire(); unsubscribe(); document.removeEventListener('visibilitychange', inspect); window.removeEventListener('pagehide', retire); };
  }, [projectId, active]);
  const readable = can('projects.development.read');
  useEffect(() => { if (active && readable && !handle.current) void readRef.current(); }, [active, readable]);
  useEffect(() => {
    if (!active || !view || !checkout || busy || review || filesOpen || unresolved(submission)) return;
    const key = `${view.viewHandle}|${projectId}|${checkout}`;
    if (sourceAttempt.current === key) return;
    sourceAttempt.current = key; setSource(null);
    void invoke('projects.development.inspect', { viewHandle: view.viewHandle, checkoutId: checkout }, value => {
      if (draft.current.checkoutId !== checkout) return;
      if (value.projectId !== projectId || value.checkoutId !== checkout) throw new Error('源码不属于这个所选副本，请重新读取。');
      setSource(value);
    });
  }, [active, view, checkout, busy, review, filesOpen, submission, projectId]);
  useEffect(() => {
    setExpired(false); if (!review) return;
    const remaining = Number(review.expiresAt) - Date.now();
    if (!Number.isFinite(remaining) || remaining <= 0) { setExpired(true); return; }
    const timer = window.setTimeout(() => setExpired(true), remaining); return () => window.clearTimeout(timer);
  }, [review]);
  const pending = unresolved(submission), disabled = !active || busy || pending || !!review || filesOpen || !view || view.draftRecoveryRequired === true;
  const copyUnconfirmed = copyResult?.unconfirmed === true || copyResult?.found === false && copyResult?.partial !== true
    || !copyResult && !!view?.draft?.copyRequestId && view?.draft?.copyStatus !== 'ready';
  const interactionBusy = active && (busy || pending || !!review || filesOpen || copyUnconfirmed);
  useEffect(() => { onInteractionBusyChange?.(interactionBusy); return () => onInteractionBusyChange?.(false); }, [interactionBusy, onInteractionBusyChange]);
  const closeFiles = () => { setFilesOpen(false); sourceAttempt.current = ''; setSource(null); };
  const available = rows(view?.checkouts);
  const copy = (query: boolean) => {
    if (!query) setCopyResult({ unconfirmed: true });
    return invoke(query ? 'projects.development.queryCopy' : 'projects.development.prepare', { viewHandle: handle.current }, value => {
    if (value.projectId !== projectId) throw new Error('副本准备回执不属于当前项目。');
    setCopyResult(value);
    setView(previous => previous ? { ...previous, draft: { ...previous.draft, copyRequestId: value.requestId },
      checkouts: value.found && value.checkout ? [...rows(previous.checkouts).filter(row => row.checkoutId !== value.checkoutId), { ...value.checkout, checkoutId: value.checkoutId }] : previous.checkouts } : previous);
    if (value.found) { setCheckout(value.checkoutId); retain({ ...draft.current, checkoutId: value.checkoutId }); sourceAttempt.current = ''; }
    });
  };
  const start = () => {
    const selected = activeReview.current;
    if (!active || !selected || busy || expired || Number(selected.expiresAt) <= Date.now()) return;
    const marker = { projectId, checkoutId: selected.checkoutId, clientMessageId: selected.requestId, goal: selected.goal,
      ...deliveryRequirements(selected.deliveryContract), status: 'unknown', message: '正在核对原输入回执。请勿重复开始。' };
    setSubmission(marker); setReview(null); activeReview.current = null;
    void invoke('projects.development.start', { viewHandle: handle.current, reviewHandle: selected.reviewHandle }, applySubmission);
  };
  return <section hidden={!active} className="pw-project-development pw-local-workspace pw-settings-section" aria-label="使用默认模型开发项目">
    <header className="pw-development-intro"><h3>让模型接手开发</h3><p>在私人副本中工作，结果由你审阅后导回项目。</p></header>
    {error && <p className="pw-local-inline-notice" role="alert">{error}</p>}
    {!view ? <Button variant="secondary" disabled={busy || !readable} onClick={() => void read()}> {busy ? '正在读取原项目…' : '读取项目开发条件'} </Button> : <>
      {view.draftRecoveryRequired && <p className="pw-local-inline-notice" role="alert">{view.draftRecoveryMessage} 仍可查看下方原任务、文件和工具状态。请保留应用数据，从诊断记录定位草稿存储问题后恢复，不要清空数据或重复提交目标。</p>}
      {submission && <div className="pw-local-inline-notice" role="status"><p>{submission.message || (pending ? '原项目输入仍待核对。' : submission.status === 'accepted' ? '项目任务已保存。' : '上次输入没有启动。')}</p>
        {submission.status === 'accepted' && <p>继续同一件事时，请打开原任务补充，不必重新开始。</p>}
        {submission.error && <p>{submission.error}</p>}<div className="pw-form-actions">
          {submission.taskId && <Button variant="secondary" disabled={busy || filesOpen || !can('tasks.open')} onClick={() => onOpenTask(submission.taskId)}>查看原任务与结果</Button>}
          {pending && <Button variant="secondary" disabled={busy || filesOpen} onClick={() => void invoke('projects.development.query', { viewHandle: handle.current }, applySubmission)}>查询原输入</Button>}
        </div></div>}
      <TextAreaField label="这次的目标" value={goal} isDisabled={disabled} onChange={value => { if (value.length <= 12000) { setGoal(value); retain({ ...draft.current, goal: value }); } }} hint="例如：做一个能在手机预览的番茄钟，先用本地数据。不要填写密码或令牌。" />
      <Accordion><AccordionItem id="development-delivery" title="交付要求" description={deliveryFields.some(key => requirements[key].trim()) ? '已补充约束、交付物或完成标准' : '选填：约束、交付物与完成标准'}>
        <TextAreaField label="限制与约束" value={requirements.constraints} isDisabled={disabled} hint="例如：保留已有数据，沿用当前组件，每项最多 4000 字。"
          onChange={value => { if (value.length <= 4000) { setRequirements(previous => ({ ...previous, constraints: value })); retain({ ...draft.current, constraints: value }); } }} />
        <TextAreaField label="要交付什么" value={requirements.deliverable} isDisabled={disabled} hint="例如：可预览的页面和对应项目固定包。"
          onChange={value => { if (value.length <= 4000) { setRequirements(previous => ({ ...previous, deliverable: value })); retain({ ...draft.current, deliverable: value }); } }} />
        <TextAreaField label="怎样算完成" value={requirements.completionCriteria} isDisabled={disabled} hint="写清你要看到的实际结果；发送或发布仍需原来的确认。"
          onChange={value => { if (value.length <= 4000) { setRequirements(previous => ({ ...previous, completionCriteria: value })); retain({ ...draft.current, completionCriteria: value }); } }} />
      </AccordionItem></Accordion>
      <SelectField label="要工作的私人副本" value={checkout} isDisabled={disabled} options={[{ id: '', label: available.length ? '选择已有私人副本' : '尚未准备私人副本' },
        ...available.map(row => ({ id: row.checkoutId, label: `${row.name || name} · ${new Date(row.createdAt || 0).toLocaleString()}` })),
        ...(checkout && !available.some(row => row.checkoutId === checkout) ? [{ id: checkout, label: '原副本暂不可用，请核对' }] : [])]}
        onChange={value => { setCheckout(value); setSource(null); sourceAttempt.current = ''; retain({ ...draft.current, checkoutId: value }); }} />
      {available.length === 0 && <div className="pw-form-actions"><Button variant="secondary" disabled={disabled || copyResult?.partial === true} onClick={() => void copy(false)}>准备这个项目的私人副本</Button></div>}
      {(view.draft?.copyRequestId || copyResult) && <div><Button variant="ghost" disabled={busy || !!review || filesOpen} onClick={() => void copy(true)}>查询原副本准备</Button>
        {copyResult?.partial && <div role="status"><p>原副本准备中断，部分文件已保留，不会覆盖。可在手动终端显式准备另一份完整副本，再返回选择；已有部分不会自动删除。</p><Button variant="secondary" disabled={busy || filesOpen} onClick={onOpenTerminal}>打开副本准备入口</Button></div>}
        {copyResult && !copyResult.found && !copyResult.partial && <p role="status">未发现这次准备的副本；显式准备仍使用同一请求，不会换一个身份重建。</p>}</div>}
      <div className="pw-form-actions"><Button disabled={disabled || !goal.trim() || !checkout || !source || view.model?.configured !== true} onClick={() => void invoke('projects.development.review', { viewHandle: handle.current, checkoutId: checkout, goal, ...requirements }, acceptReview)}>核对并开始</Button>
        <Button variant="ghost" disabled={busy || pending || !!review || filesOpen} onClick={() => void read()}>重新读取项目条件</Button>
        {checkout && !source && <Button variant="ghost" disabled={disabled} onClick={() => { sourceAttempt.current = ''; setView(previous => previous ? { ...previous } : previous); }}>重新读取副本版本</Button>}
      </div>
      <div className="pw-form-actions"><Button variant="secondary" disabled={busy || !!review || filesOpen || !checkout || !can('projects.development.files.open')} onClick={() => setFilesOpen(true)}>编辑这份私人副本的文件</Button></div>
      {view.model?.configured !== true && <p className="pw-local-inline-notice">需要先连接默认模型。项目与已保存草稿保留。<Button variant="secondary" disabled={busy || pending || !!review || filesOpen} onClick={onConnectModel}>连接模型</Button></p>}
      <Accordion><AccordionItem id="development-conditions" title="运行位置与开发条件" description={view.runtime?.canExecute === true ? '这台手机的基础运行条件已具备' : '可以先讨论或编辑，运行命令前需准备工具'}>
        <p>本次只绑定这台手机与所选副本，不会自动切换到远程设备。Linux 工具不是聊天引擎，不需要为本机开发开启手机操控权限。</p>
        <p>Web / Node.js / Python 的安装、构建与启动仍需核对具体命令。基础工具不包含 Android SDK、JDK 或 Gradle，不代表所有项目都能运行。</p>
        <p>模型调用需要相应网络；已有文件和副本不会因离线而删除。退出页面不等于停止任务，重开请查看原任务。</p>
        <Button variant="secondary" disabled={busy || pending || !!review || filesOpen} onClick={onPrepareTools}>查看工具准备</Button>
        {source && <dl className="pw-facts"><div><dt>原项目</dt><dd>{projectId}</dd></div><div><dt>私人副本</dt><dd>{checkout}</dd></div><div><dt>已读取版本</dt><dd>{source.sourceDigest}</dd></div></dl>}
      </AccordionItem></Accordion>
    </>}
    {view && filesOpen && checkout && <ProjectCheckoutFiles key={`${view.viewHandle}|${checkout}`} projectId={projectId} checkoutId={checkout} viewHandle={view.viewHandle} run={run} onClose={closeFiles} registerBack={registerFilesBack} />}
    <SettingsDialog isOpen={!!review} title="开始这个项目任务？" onClose={dismiss} closeLabel="返回修改目标">{review && <div className="pw-local-review">
      <p>{review.goal}</p>
      {deliveryFields.some(key => review.deliveryContract?.[key]) && <dl className="pw-facts">
        {review.deliveryContract.constraints && <div><dt>限制与约束</dt><dd>{review.deliveryContract.constraints}</dd></div>}
        {review.deliveryContract.deliverable && <div><dt>要交付什么</dt><dd>{review.deliveryContract.deliverable}</dd></div>}
        {review.deliveryContract.completionCriteria && <div><dt>怎样算完成</dt><dd>{review.deliveryContract.completionCriteria}</dd></div>}
      </dl>}
      <dl className="pw-facts"><div><dt>项目</dt><dd>{name}</dd></div><div><dt>私人副本</dt><dd>{review.checkoutId}</dd></div><div><dt>默认模型</dt><dd>{review.model?.label} · {review.model?.provider}</dd></div><div><dt>运行位置</dt><dd>{review.runtime}</dd></div><div><dt>源码版本</dt><dd>{review.source?.sourceDigest}</dd></div></dl>
      <p>将目标交给默认模型，在新的项目任务中处理。不会批准任意命令、安装依赖、读取项目记忆或自动发布。具体文件修改、命令与成果采纳仍按原任务范围审阅。</p>
      {expired && <p role="status">本次审阅已过期，还未启动。请返回重新核对。</p>}
      <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={dismiss}>返回修改</Button><Button disabled={busy || expired} onClick={start}>开始这个项目任务</Button></div>
    </div>}</SettingsDialog>
  </section>;
}
