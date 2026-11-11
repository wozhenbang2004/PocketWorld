import { useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Accordion, AccordionItem, Button, CheckboxField, SelectField, SettingsDialog, TextAreaField } from './forms-library';
import { IconAdd, IconArrowLeft, IconCheckCircle, IconChevronRight, IconFileSettings, IconFolder, IconPlay, IconRefresh } from './icons';
import { profileRows, profileStartReceipt, type ProfileData as Data } from './profile-contract';
import './project-work-start.css';

type Operation = {
  operationId: string;
  command: 'start' | 'prepareCheckout';
  status: string;
  goal?: string;
  original?: Data;
  taskId?: string;
  admitted?: boolean;
  error?: string;
};

export type ProjectWorkDraft = {
  expanded?: boolean;
  goal: string;
  profileId: string;
  checkoutId: string;
  runtimeId: string;
  operation?: Operation | null;
};

export const emptyProjectWorkDraft = (): ProjectWorkDraft => ({ goal: '', profileId: '', checkoutId: '', runtimeId: '' });

type Props = {
  project: { id: string; name: string };
  draft: ProjectWorkDraft;
  onChange: (draft: ProjectWorkDraft) => void;
  initiallyOpen: boolean;
  active: boolean;
  ownerRevision: string;
  supported: (action: string) => boolean;
  run: (action: string, args?: Data) => Promise<any>;
  onOpenTask: (id: string) => void;
  onConfigure: () => void;
  onPrepareModel?: () => void;
  onPrepareTools?: () => void;
  registerBack: (handler: (() => boolean) | null) => void;
  onReviewChange: (open: boolean) => void;
  embedded?: boolean;
  onInteractionBusyChange?: (busy: boolean) => void;
};

const taskIdentity = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9-]{36}$/.test(value);
const failureText = (failure: unknown) => failure instanceof Error ? failure.message : String(failure);
const resultStates = new Set(['queued', 'entered', 'completed', 'unknown', 'not_entered']);
const kindLabel = (kind: string, id: string) => kind === 'model' ? ({ MAIN: '主任务模型', SUMMARY: '摘要模型', RELAY: '网关模型', MEMBER: '成员模型' }[id] || '模型')
  : ({ skill: '技能', instruction: '指令资料', plugin: '插件', extension: '扩展', team: '团队模板', target: '执行位置' }[kind] || '工作来源');

/** Project form state belongs to App; this component owns only the current native window. */
export function ProjectWorkStart({ project, draft, onChange, initiallyOpen, active, ownerRevision, supported, run, onOpenTask, onConfigure, onPrepareModel, onPrepareTools, registerBack, onReviewChange, embedded = false, onInteractionBusyChange }: Props) {
  const [snapshot, setSnapshot] = useState<Data | null>(null), [inspection, setInspection] = useState<Data | null>(null), [review, setReview] = useState<Data | null>(null);
  const [loading, setLoading] = useState(false), [busy, setBusy] = useState(false), [error, setError] = useState(''), [acknowledged, setAcknowledged] = useState(false), [reload, setReload] = useState(0);
  const [visible, setVisible] = useState(() => !document.hidden);
  const activeRef = useRef(active && visible); activeRef.current = active && visible;
  const generation = useRef(0), windowId = useRef(''), pending = useRef(false), reviewRef = useRef(review), draftRef = useRef(draft), changeRef = useRef(onChange);
  draftRef.current = draft; changeRef.current = onChange; reviewRef.current = review;
  const expanded = embedded || (draft.expanded ?? initiallyOpen);
  const mounted = active && visible && (expanded || !!draft.operation);
  const patch = (next: Partial<ProjectWorkDraft>) => {
    const value = { ...draftRef.current, ...next }; draftRef.current = value; changeRef.current(value);
  };
  const current = (ticket: number, id: string) => activeRef.current && generation.current === ticket && windowId.current === id && !document.hidden;
  const closeNative = (id: string) => { if (id) void bridge.command('projectWork.close', { windowId: id }).catch(() => {}); };
  const clearReview = () => { reviewRef.current = null; setReview(null); setAcknowledged(false); };
  const restartWindow = () => { clearReview(); setInspection(null); setReload(value => value + 1); };
  const closeForm = () => { if (pending.current) return; clearReview(); setInspection(null); patch({ expanded: false }); };

  useEffect(() => {
    const changed = () => setVisible(!document.hidden);
    document.addEventListener('visibilitychange', changed);
    return () => document.removeEventListener('visibilitychange', changed);
  }, []);

  useEffect(() => {
    const ticket = ++generation.current;
    const old = windowId.current; windowId.current = ''; closeNative(old);
    pending.current = false; setBusy(false); clearReview(); setInspection(null); setSnapshot(null); setLoading(mounted); setError('');
    if (mounted) {
      void run('projectWork.open', { projectId: project.id }).then(value => {
        if (!activeRef.current || generation.current !== ticket || document.hidden) { closeNative(value?.windowId); return; }
        if (value?.projectId !== project.id || typeof value.windowId !== 'string' || !value.windowId || !Array.isArray(value.profiles) || !Array.isArray(value.checkouts) || !Array.isArray(value.runtimes)) throw new Error('项目准备信息没有完整返回，请重新读取。');
        windowId.current = value.windowId; setSnapshot(value); setLoading(false);
      }).catch(failure => { if (generation.current === ticket) { setError(failureText(failure)); setLoading(false); } });
    }
    return () => {
      generation.current++; pending.current = false;
      const id = windowId.current; windowId.current = ''; closeNative(id);
    };
  }, [project.id, mounted, ownerRevision, reload]);

  useEffect(() => { onReviewChange(!!review); return () => onReviewChange(false); }, [!!review, onReviewChange]);
  const back = useRef<() => boolean>(() => false);
  back.current = () => {
    if (!active) return false;
    if (reviewRef.current) { if (!pending.current) restartWindow(); return true; }
    if (pending.current) return true;
    if (expanded && !embedded) { closeForm(); return true; }
    return false;
  };
  useEffect(() => { registerBack(() => back.current()); return () => registerBack(null); }, [registerBack]);

  const request = async (action: string, input: Data, accept: (value: Data) => void | Promise<void>, operation?: Operation) => {
    const id = windowId.current, ticket = generation.current;
    if (!id || pending.current || !mounted) return;
    pending.current = true; setBusy(true); setError('');
    try {
      const value = await run(action, { windowId: id, ...input });
      if (!current(ticket, id)) return;
      if (!value || value.projectId !== project.id) throw new Error('回执与当前项目不一致，请核对原操作。');
      await accept(value);
    } catch (failure) {
      if (current(ticket, id)) {
        const message = failureText(failure); setError(message);
        if (operation && draftRef.current.operation?.operationId === operation.operationId) {
          const notSent = failure instanceof Error && 'code' in failure && failure.code === 'not_sent';
          patch({ operation: { ...operation, status: notSent ? 'not_entered' : 'unknown', error: message } });
        }
      }
    } finally {
      if (current(ticket, id)) { pending.current = false; setBusy(false); }
    }
  };

  const acceptResult = (value: Data, expected: Operation, queried: boolean) => {
    if (value.operationId !== expected.operationId || value.command !== expected.command || !resultStates.has(value.status) || queried && (value.readOnly !== true || value.replayAllowed !== false)) throw new Error('原操作的身份或回执尚未确认，请继续核对。');
    if (draftRef.current.operation?.operationId !== expected.operationId) return;
    if (expected.command === 'prepareCheckout' && value.status === 'completed') {
      const checkout = value.receipt?.checkout;
      if (checkout?.projectId !== project.id || typeof checkout.id !== 'string' || !checkout.id) throw new Error('工作副本回执未指向当前项目。');
      patch({ checkoutId: checkout.id, operation: null }); setReload(version => version + 1); return;
    }
    if (value.taskId != null && !taskIdentity(value.taskId)) throw new Error('任务身份尚未完整返回，请核对原操作。');
    let taskId = taskIdentity(value.taskId) ? value.taskId : expected.taskId;
    if (expected.command === 'start' && value.status === 'completed') {
      const actual = profileStartReceipt(value.receipt, expected);
      if (taskId && taskId !== actual.taskId) throw new Error('任务回执身份不一致，请保留当前记录并核对。');
      taskId = actual.taskId;
    }
    const operation = { ...expected, status: value.status, taskId, admitted: value.admitted === true || expected.admitted === true, error: typeof value.error === 'string' ? value.error : undefined };
    patch({ operation, ...(taskId && draftRef.current.goal.trim() === expected.goal ? { goal: '' } : {}) });
  };
  const queryResult = () => {
    const original = draftRef.current.operation; if (!original) return;
    void request('projectWork.result', { operationId: original.operationId }, value => acceptResult(value, original, true));
  };
  const queryRef = useRef(queryResult); queryRef.current = queryResult;
  useEffect(() => {
    const original = draft.operation;
    if (!mounted || !snapshot || !original || original.taskId || !['queued', 'entered'].includes(original.status)) return;
    let count = 0;
    const timer = window.setInterval(() => {
      if (++count > 12) { window.clearInterval(timer); return; }
      if (!pending.current) queryRef.current();
    }, 1500);
    return () => window.clearInterval(timer);
  }, [mounted, snapshot?.windowId, draft.operation?.operationId, draft.operation?.status, draft.operation?.taskId]);

  const prepareCheckout = () => {
    if (draftRef.current.operation || !windowId.current || pending.current) return;
    const operation: Operation = { operationId: crypto.randomUUID(), command: 'prepareCheckout', status: 'queued' };
    patch({ operation });
    void request('projectWork.prepareCheckout', { operationId: operation.operationId }, value => acceptResult(value, operation, false), operation);
  };
  const prepare = () => {
    const selected = draftRef.current;
    if (selected.operation || !selected.goal.trim() || selected.goal.trim().length > 4000 || selected.goal.includes('\u0000') || !selected.profileId || !selected.checkoutId || !selected.runtimeId) return;
    const goal = selected.goal.trim();
    setInspection(null);
    void request('projectWork.preflight', { profileId: selected.profileId, checkoutId: selected.checkoutId, runtimeId: selected.runtimeId }, async value => {
      if (!value.preflightHandle || value.profileId !== selected.profileId || value.project?.projectId !== project.id || value.project?.checkoutId !== selected.checkoutId || value.project?.runtime !== selected.runtimeId) throw new Error('工作配置或项目副本已变化，请重新选择。');
      setInspection(value);
      if (value.runnable !== true) return;
      const id = windowId.current, ticket = generation.current;
      const shown = await run('projectWork.reviewStart', { windowId: id, preflightHandle: value.preflightHandle, goal });
      if (!current(ticket, id)) return;
      if (shown.projectId !== project.id || shown.command !== 'start' || !shown.reviewHandle || !shown.operationId || shown.project?.projectId !== project.id || shown.project?.checkoutId !== selected.checkoutId || shown.project?.runtime !== selected.runtimeId || shown.original?.profileId !== selected.profileId || shown.original?.revision !== value.revision || shown.goal !== goal) throw new Error('本次任务的核对信息不完整，请重新准备。');
      reviewRef.current = shown; setReview(shown); setAcknowledged(false);
    });
  };
  const confirm = () => {
    const original = reviewRef.current;
    if (!original || !acknowledged || pending.current || draftRef.current.operation) return;
    const operation: Operation = { operationId: original.operationId, command: 'start', status: 'queued', goal: original.goal, original: original.original };
    patch({ operation }); clearReview();
    void request('projectWork.confirm', { reviewHandle: original.reviewHandle, consent: true }, value => acceptResult(value, operation, false), operation);
  };
  const changeSelection = (next: Partial<ProjectWorkDraft>) => { if (!pending.current && !draftRef.current.operation) { patch(next); setInspection(null); setError(''); } };

  const profiles = profileRows(snapshot?.profiles), checkouts = profileRows(snapshot?.checkouts), runtimes = profileRows(snapshot?.runtimes);
  const profile = profiles.find(value => value.profileId === draft.profileId), checkout = checkouts.find(value => value.id === draft.checkoutId), runtime = runtimes.find(value => value.runtimeId === draft.runtimeId);
  const needsLocalTools = runtime?.runtimeId === 'managed_process_v1' && runtime.canExecute === false;
  const unavailableSelection = (value: string, found: Data | undefined) => value && !found ? [{ id: value, label: '原选择已不可用，请重新选择', disabled: true }] : [];
  const operation = draft.operation;
  const interactionBusy = active && (busy || loading || !!review || !!operation && ['queued', 'entered', 'unknown'].includes(operation.status));
  useEffect(() => { onInteractionBusyChange?.(interactionBusy); return () => onInteractionBusyChange?.(false); }, [interactionBusy, onInteractionBusyChange]);
  const locked = busy || loading || !snapshot || !!operation;
  const ready = !locked && !!profile && profile.restoredPendingReview !== true && !!checkout && !!runtime && !!draft.goal.trim() && draft.goal.trim().length <= 4000 && !draft.goal.includes('\u0000');
  const failedSources = profileRows(inspection?.items).filter(item => item.included !== true);
  const referenceName = (id: string, rows: Data[], key: string) => rows.find(row => row[key] === id)?.name || id;

  return <section hidden={!active} className="pw-project-start" aria-label="使用工作配置开始项目工作">
    {!embedded && <div className="pw-project-start-heading"><h3>{operation ? '这次工作' : '让 AI 接着做'}</h3>
      {!expanded && !operation && <Button variant="secondary" leadingIcon={IconAdd} data-workspace-entry="project-new-work" onClick={() => patch({ expanded: true })}>新工作</Button>}
      {expanded && !operation && <Button variant="ghost" size="small" disabled={busy} onClick={closeForm}>收起</Button>}
    </div>}
    {embedded && !operation && <p className="pw-project-start-caption">使用已保存的模型、资料与工具组合，在所选副本中工作。</p>}
    {expanded && !operation && <div className="pw-project-start-form">
      <TextAreaField label="这次想完成什么？" placeholder="描述目标、要修改的内容或期待的结果…" value={draft.goal} onChange={goal => changeSelection({ goal })} rows={3} autoResize maxRows={7} maxLength={4000} isDisabled={busy} className="pw-project-goal" />
      {loading ? <p className="pw-project-start-status" role="status">正在读取工作配置与项目副本…</p> : snapshot && <>
        {profiles.length ? <div className="pw-project-profile-choice"><SelectField label="工作配置" placeholder="选择已保存配置" value={draft.profileId} onChange={profileId => changeSelection({ profileId })} isDisabled={locked} options={[...unavailableSelection(draft.profileId, profile), ...profiles.map(row => ({ id: row.profileId, label: row.name || '未命名配置', description: row.restoredPendingReview ? '需先重新绑定来源' : '使用已保存的模型、资料与工具', disabled: row.restoredPendingReview === true }))]} />
          <Button variant="ghost" iconOnly leadingIcon={IconFileSettings} aria-label="管理工作配置" title="管理工作配置" disabled={busy} data-workspace-entry="project-work-profiles" onClick={onConfigure}/></div>
          : <div className="pw-project-start-setup"><IconFileSettings size={20} aria-hidden/><div><strong>先选好怎么工作</strong><p>保存常用模型与工具组合，再回到这里开始。</p></div><Button variant="secondary" trailingIcon={IconChevronRight} disabled={busy} data-workspace-entry="project-work-profiles" onClick={onConfigure}>配置工作方式</Button></div>}
        {checkouts.length ? <Accordion className="pw-project-start-options" defaultExpandedKeys={!checkout || !runtime ? ['project-work-location'] : []}><AccordionItem id="project-work-location" title="工作副本与运行位置" description={checkout && runtime ? `${checkout.name || '私人副本'} · ${runtime.name || runtime.runtimeId}` : '选择这次工作的副本与环境'}>
          <div className="pw-project-start-selections"><SelectField label="工作副本" value={draft.checkoutId} onChange={checkoutId => changeSelection({ checkoutId })} isDisabled={locked} options={[...unavailableSelection(draft.checkoutId, checkout), ...checkouts.map(row => ({ id: row.id, label: row.name || '私人工作副本', description: [typeof row.fileCount === 'number' ? `${row.fileCount} 个文件` : '', typeof row.createdAt === 'number' && row.createdAt > 0 ? new Date(row.createdAt).toLocaleString('zh-CN') : ''].filter(Boolean).join(' · ') }))]}/>
            <SelectField label="运行位置" value={draft.runtimeId} onChange={runtimeId => changeSelection({ runtimeId })} isDisabled={locked} options={[...unavailableSelection(draft.runtimeId, runtime), ...runtimes.map(row => ({ id: row.runtimeId, label: row.name || row.runtimeId, description: row.description || undefined }))]}/>
          </div>
        </AccordionItem></Accordion>
          : <div className="pw-project-start-setup"><IconFolder size={20} aria-hidden/><div><strong>准备一份工作副本</strong><p>从当前项目内容建立私人副本，供这次工作使用。</p></div><Button variant="secondary" disabled={locked} onClick={prepareCheckout}>准备副本</Button></div>}
        {(!runtimes.length || needsLocalTools) && <p className="pw-project-start-status">{needsLocalTools ? '本机开发工具尚未就绪，需要运行命令时可以先准备。' : '还没有可选运行位置。'}{onPrepareTools && <Button variant="ghost" size="small" disabled={busy} data-workspace-entry="project-work-tools" onClick={onPrepareTools}>准备运行环境</Button>}</p>}
      </>}
      {inspection && inspection.runnable !== true && <div className="pw-project-start-issues" role="status"><strong>还有几项需要准备</strong>
        {failedSources.length > 0 ? <ul>{failedSources.map((item, index) => <li key={`${item.kind}:${item.id}:${index}`}>{kindLabel(item.kind, item.id)} · {item.optional ? '本次可省略' : '暂不可用'}</li>)}</ul> : <p>请核对工作配置中的主任务与摘要模型，以及这次工作的执行范围。</p>}
        <div className="pw-project-start-actions">{onPrepareModel && <Button variant="ghost" disabled={busy} data-workspace-entry="project-work-models" onClick={onPrepareModel}>连接模型</Button>}<Button variant="ghost" disabled={busy} data-workspace-entry="project-work-profiles" onClick={onConfigure}>调整工作配置</Button></div>
      </div>}
      <div className="pw-project-start-footer"><Button variant="ghost" iconOnly leadingIcon={IconRefresh} aria-label="重新读取工作准备" title="重新读取工作准备" disabled={busy} onClick={restartWindow}/><span/>
        <Button variant="primary" leadingIcon={IconPlay} disabled={!ready} onClick={prepare}>{busy ? '正在核对…' : '核对并开始'}</Button>
      </div>
    </div>}
    {operation && <div className="pw-project-start-receipt" role="status" aria-live="polite">
      {operation.taskId ? <><IconCheckCircle size={22} aria-hidden/><div><strong>{operation.admitted ? '任务已开始' : '任务已建立'}</strong><p dir="auto">{operation.goal}</p>{operation.error && <p>{operation.error}</p>}</div><div className="pw-project-start-actions"><Button variant="primary" trailingIcon={IconChevronRight} disabled={!supported('tasks.open')} onClick={() => onOpenTask(operation.taskId!)}>查看任务</Button><Button variant="ghost" disabled={busy} onClick={() => { patch({ operation: null, expanded: true }); restartWindow(); }}>新工作</Button></div></>
        : <><div><strong>{operation.command === 'prepareCheckout' ? '正在确认工作副本' : '正在确认这次启动'}</strong><p>{operation.status === 'not_entered' ? '原请求尚未进入。输入已保留，可以重新准备。' : operation.status === 'unknown' ? '结果还没有确认。先查询这次操作，避免重复创建。' : '请求已送出，正在等本次操作的真实回执。'}</p>{operation.goal && <p dir="auto">{operation.goal}</p>}{operation.error && <p>{operation.error}</p>}</div><div className="pw-project-start-actions"><Button variant="secondary" leadingIcon={IconRefresh} disabled={busy || loading} onClick={snapshot ? queryResult : restartWindow}>{busy ? '正在查询…' : loading ? '正在读取…' : snapshot ? '核对结果' : '重新读取项目'}</Button>{operation.status === 'not_entered' && <Button variant="ghost" disabled={busy} onClick={() => { patch({ operation: null, expanded: true }); restartWindow(); }}>返回准备</Button>}</div></>}
    </div>}
    {error && !review && <p className="pw-project-start-error" role="alert">{error}</p>}
    <SettingsDialog isOpen={!!review} title="开始这次项目工作" closeLabel="返回项目目标" onClose={() => { if (!pending.current) restartWindow(); }}>
      {review && <div className="pw-project-start-review"><p className="pw-project-review-goal" dir="auto">{review.goal}</p>
        <dl><div><dt>项目</dt><dd>{project.name}</dd></div><div><dt>工作配置</dt><dd>{review.definition?.name || '已保存配置'}</dd></div><div><dt>工作副本</dt><dd>{referenceName(review.project?.checkoutId, checkouts, 'id')}</dd></div><div><dt>运行位置</dt><dd>{referenceName(review.project?.runtime, runtimes, 'runtimeId')}</dd></div></dl>
        <p className="pw-project-start-caption">按以上范围创建一项新任务，执行中的授权与预算沿用原规则。</p>
        <CheckboxField isSelected={acknowledged} isDisabled={busy} onChange={setAcknowledged}>确认目标、配置与工作副本</CheckboxField>
        {error && <p className="pw-project-start-error" role="alert">{error}</p>}
        <div className="pw-project-start-actions"><Button variant="ghost" leadingIcon={IconArrowLeft} disabled={busy} onClick={restartWindow}>返回修改</Button><Button variant="primary" leadingIcon={IconPlay} disabled={busy || !acknowledged} onClick={confirm}>开始工作</Button></div>
      </div>}
    </SettingsDialog>
  </section>;
}
