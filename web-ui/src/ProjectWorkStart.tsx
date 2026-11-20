import { useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Accordion, AccordionItem, Button, CheckboxField, SelectField, SettingsDialog, TextAreaField } from './forms-library';
import { IconAdd, IconArrowLeft, IconCheckCircle, IconChevronRight, IconClock, IconFileSettings, IconFolder, IconPlay, IconRefresh } from './icons';
import { profileDependencyLabel, profileRows, profileStartReceipt, type ProfileData as Data } from './profile-contract';
import { isProfileSkill, WorkProfileSkillPreparation } from './WorkProfileSkillPreparation';
import './project-work-start.css';

type Operation = {
  operationId: string;
  command: 'start' | 'prepareCheckout';
  status: string;
  goal?: string;
  original?: Data;
  taskId?: string;
  checkoutId?: string; // Optional for a receipt retained by an older client.
  admitted?: boolean;
  final?: boolean;
  error?: string;
};

export type ProjectWorkDraft = {
  expanded?: boolean;
  goal: string;
  profileId: string;
  profileRevision?: number; // A return from setup retains the explicitly picked saved revision.
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
  onOpenTask: (id: string, checkoutId?: string) => void;
  onTaskRegistered: () => void;
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
  : ({ skill: '技能', instruction: '指令资料', plugin: '插件', extension: '扩展', team: '团队模板' }[kind] || (kind?.startsWith('target:')?'配置中的执行位置':'工作来源'));
const isRevision = (value: unknown): value is number => typeof value==='number' && Number.isSafeInteger(value) && value>0;
const isDigest = (value: unknown) => typeof value==='string' && /^[a-f0-9]{64}$/.test(value);
const originalScope = (scope:Data|undefined,project:string,choice:ProjectWorkDraft) =>
  scope?.format==='pocketworld.development-context.v1' && scope.projectId===project && scope.checkoutId===choice.checkoutId &&
  scope.runtime===choice.runtimeId && scope.executionAuthorized===false && scope.commandsAuthorized===false && isDigest(scope.initialSourceDigest);
const originalResolution = (value:Data|undefined,profile:string,revision:number) =>
  value?.format==='pocketworld.resolved-work-profile.v1' && value.profileId===profile && value.profileRevision===revision &&
  value.sourceChecksOnly===true && value.executionAuthorized===false && value.runtimeVerified===false && isDigest(value.resolutionDigest);
const actualModel = (value:Data|undefined,purpose:string) => {
  const selected=value?.modelPolicies?.[purpose];
  return typeof selected?.model==='string' && selected.model.trim() ? selected.model : '';
};
function modelReviewLabel(value:Data,purpose:string) {
  const policy=value.modelPolicies[purpose], primary=actualModel(value,purpose), route=policy?.route;
  const candidates=profileRows(route?.orderedCandidates).filter(row=>row.exclusion===''&&typeof row.model==='string'&&typeof row.provider==='string');
  const names=[...new Set(candidates.map(row=>`${row.model} · ${row.provider}`))];
  if (route?.format==='pocketworld.model-route-plan.v1'&&route.purpose===purpose&&isRevision(route.groupRevision)&&typeof route.requestedAlias==='string'&&names.length)
    return `${route.requestedAlias}（模型组） · ${names.join(' / ')}`;
  return policy?.account ? `${primary} · ChatGPT 账号` : names.length===1 ? names[0] : primary;
}
function runtimeObservation(runtime:Data|undefined):string {
  if (runtime?.runtimeId==='managed_process_v1') {
    if (runtime.canExecute===true) return '手机托管工具已准备；未验证这份项目的命令、依赖或构建。';
    if (runtime.canExecute===false) return '手机托管工具尚未就绪；需要运行命令时先准备。';
  }
  if (runtime?.runtimeId==='termux') {
    if (runtime.installed===false || runtime.enabled===false || runtime.runCommandAvailable===false)
      return 'Termux 尚未准备好。需核对安装、启用与系统许可，不会改用其他环境。';
    if (runtime.installed===true && runtime.enabled===true && runtime.runCommandAvailable===true)
      return '已登记 Termux 连接，尚未验证外部应用开关和项目工具。';
  }
  return '所选环境的工具状态未核对。';
}

/** Project form state belongs to App; this component owns only the current native window. */
export function ProjectWorkStart({ project, draft, onChange, initiallyOpen, active, ownerRevision, supported, run, onOpenTask, onTaskRegistered, onConfigure, onPrepareModel, onPrepareTools, registerBack, onReviewChange, embedded = false, onInteractionBusyChange }: Props) {
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
  const current = (ticket: number, id: string) => activeRef.current && generation.current === ticket && windowId.current === id && !document.hidden &&
    bridge.getState().state.projectId === project.id && !bridge.getState().state.filePath;
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
    if (!id || pending.current || !mounted || !current(ticket, id)) return;
    pending.current = true; setBusy(true); setError('');
    try {
      const value = await run(action, { windowId: id, ...input });
      if (!current(ticket, id)) return;
      if (!value || value.projectId !== project.id || value.windowId !== id) throw new Error('回执不属于当前项目窗口，请核对原操作。');
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
    if (expected.taskId && value.taskId && expected.taskId !== value.taskId) throw new Error('原启动返回了不同工作，未替换已登记记录。');
    let taskId = taskIdentity(value.taskId) ? value.taskId : expected.taskId;
    if (expected.command === 'start' && value.status === 'completed') {
      const actual = profileStartReceipt(value.receipt, expected);
      if (taskId && taskId !== actual.taskId) throw new Error('任务回执身份不一致，请保留当前记录并核对。');
      taskId = actual.taskId;
    }
    const previous = draftRef.current.operation;
    const admitted = value.admitted === true || expected.admitted === true;
    const final = value.final === true || expected.final === true || ['completed', 'not_entered'].includes(value.status);
    const registrationChanged = !!taskId && (previous?.taskId !== taskId || admitted && !previous.admitted || final && !previous.final);
    const operation = { ...expected, status: value.status, taskId, admitted, final, error: typeof value.error === 'string' ? value.error : undefined };
    patch({ operation, ...(taskId && draftRef.current.goal.trim() === expected.goal ? { goal: '' } : {}) });
    if (registrationChanged) onTaskRegistered();
  };
  const queryResult = () => {
    const original = draftRef.current.operation; if (!original) return;
    void request('projectWork.result', { operationId: original.operationId }, value => acceptResult(value, original, true));
  };
  const queryRef = useRef(queryResult); queryRef.current = queryResult;
  useEffect(() => {
    const original = draft.operation;
    if (!mounted || !snapshot || !original || original.admitted || original.final || !['queued', 'entered'].includes(original.status)) return;
    let count = 0;
    const timer = window.setInterval(() => {
      if (++count > 12) { window.clearInterval(timer); return; }
      if (!pending.current) queryRef.current();
    }, 1500);
    return () => window.clearInterval(timer);
  }, [mounted, snapshot?.windowId, draft.operation?.operationId, draft.operation?.status, draft.operation?.admitted, draft.operation?.final]);

  const prepareCheckout = () => {
    if (draftRef.current.operation || !windowId.current || pending.current) return;
    const operation: Operation = { operationId: crypto.randomUUID(), command: 'prepareCheckout', status: 'queued' };
    patch({ operation });
    void request('projectWork.prepareCheckout', { operationId: operation.operationId }, value => acceptResult(value, operation, false), operation);
  };
  const prepare = () => {
    const selected = draftRef.current;
    if (selected.operation || !selected.goal.trim() || selected.goal.trim().length > 4000 || selected.goal.includes('\u0000') || !selected.profileId || !selected.checkoutId || !selected.runtimeId) return;
    const goal = selected.goal.trim(), revision = selected.profileRevision ?? profile?.revision;
    if (!isRevision(revision) || revision!==profile?.revision) return;
    setInspection(null);
    void request('projectWork.preflight', { profileId: selected.profileId, profileRevision: revision, checkoutId: selected.checkoutId, runtimeId: selected.runtimeId }, async value => {
      if (!value.preflightHandle || value.profile?.profileId!==selected.profileId || value.profile?.revision!==revision || value.revision!==revision ||
          !originalResolution(value,selected.profileId,revision) || !originalScope(value.project,project.id,selected))
        throw new Error('未核对到这一版配置、项目副本或运行位置。原目标仍保留。');
      setInspection(value);
      if (value.runnable !== true) return;
      const id = windowId.current, ticket = generation.current;
      const shown = await run('projectWork.reviewStart', { windowId: id, preflightHandle: value.preflightHandle, goal });
      if (!current(ticket, id)) return;
      if (shown.projectId!==project.id || shown.windowId!==id || shown.command!=='start' || !shown.reviewHandle || !shown.operationId || !originalScope(shown.project,project.id,selected) ||
          shown.project.initialSourceDigest!==value.project.initialSourceDigest || shown.original?.profileId!==selected.profileId || shown.original?.revision!==revision ||
          !originalResolution(shown.preflight,selected.profileId,revision) || shown.preflight.resolutionDigest!==value.resolutionDigest ||
          !actualModel(shown.preflight,'MAIN') || !actualModel(shown.preflight,'SUMMARY') || shown.goal!==goal)
        throw new Error('审阅不再是刚才的源码、配置或模型。未开始工作，请重新核对。');
      reviewRef.current = shown; setReview(shown); setAcknowledged(false);
    });
  };
  const confirm = () => {
    const original = reviewRef.current;
    if (!original || !acknowledged || pending.current || draftRef.current.operation) return;
    const retained=draftRef.current;
    if (!current(generation.current, windowId.current) || original.projectId!==project.id || original.windowId!==windowId.current ||
        !originalScope(original.project,project.id,retained) || original.original?.profileId!==retained.profileId ||
        original.original?.revision!==(retained.profileRevision ?? profile?.revision) || original.goal!==retained.goal.trim()) {
      clearReview(); setError('项目目标、配置或副本已变化，没有开始工作。'); return;
    }
    const operation: Operation = { operationId: original.operationId, command: 'start', status: 'queued', goal: original.goal, checkoutId: original.project.checkoutId, original: original.original };
    patch({ operation }); clearReview();
    void request('projectWork.confirm', { reviewHandle: original.reviewHandle, consent: true }, value => acceptResult(value, operation, false), operation);
  };
  const changeSelection = (next: Partial<ProjectWorkDraft>) => { if (!pending.current && !draftRef.current.operation) { patch(next); setInspection(null); setError(''); } };

  const profiles = profileRows(snapshot?.profiles), checkouts = profileRows(snapshot?.checkouts), runtimes = profileRows(snapshot?.runtimes);
  const profile = profiles.find(value => value.profileId === draft.profileId), checkout = checkouts.find(value => value.id === draft.checkoutId), runtime = runtimes.find(value => value.runtimeId === draft.runtimeId);
  const needsLocalTools = runtime?.runtimeId === 'managed_process_v1' && runtime.canExecute === false;
  const profileChanged = !!profile && isRevision(draft.profileRevision) && profile.revision!==draft.profileRevision;
  const copyLabel = (id:string) => `${checkouts.find(row=>row.id===id)?.name || '私人副本'} · ${id.slice(0,8)}`;
  const unavailableSelection = (value: string, found: Data | undefined) => value && !found ? [{ id: value, label: '原选择已不可用，请重新选择', disabled: true }] : [];
  const operation = draft.operation;
  // SQL may already contain the task before its authorized run owns the project.
  // Leaving the original form during that interval can invalidate admission.
  const admissionPending = !!operation?.taskId && !operation.admitted && !operation.final && !['completed', 'not_entered'].includes(operation.status);
  const interactionBusy = active && (busy || loading || !!review || !!operation && ['queued', 'entered', 'unknown'].includes(operation.status) && (!operation.taskId || admissionPending));
  useEffect(() => { onInteractionBusyChange?.(interactionBusy); return () => onInteractionBusyChange?.(false); }, [interactionBusy, onInteractionBusyChange]);
  const locked = busy || loading || !snapshot || !!operation;
  const ready = !locked && !!profile && isRevision(profile.revision) && !profileChanged && profile.restoredPendingReview !== true &&
    !!checkout && !!runtime && !!draft.goal.trim() && draft.goal.trim().length <= 4000 && !draft.goal.includes('\u0000');
  const items = profileRows(inspection?.items);
  const missingModels = ['MAIN','SUMMARY'].filter(purpose=>!actualModel(inspection||undefined,purpose));
  const foreignTargets = items.filter(item=>item.included===true&&typeof item.kind==='string'&&item.kind.startsWith('target:'));
  const failedSources = items.filter(item=>item.included!==true&&!isProfileSkill(item)&&!(item.kind==='model'&&missingModels.includes(item.id)));
  const resolvedTools = profileRows(review?.preflight?.items).filter(item=>item.kind==='plugin'&&item.included===true&&Array.isArray(item.binding?.tools))
    .flatMap(item=>item.binding.tools.filter((tool:unknown)=>typeof tool==='string').map((tool:string)=>`${item.id} · ${tool}`));
  const referenceName = (id: string, rows: Data[], key: string) => rows.find(row => row[key] === id)?.name || id;
  const chooseProfile = (profileId:string) => {
    const next=profiles.find(row=>row.profileId===profileId);
    if (next && isRevision(next.revision)) changeSelection({profileId,profileRevision:next.revision});
  };

  return <section hidden={!active} className="pw-project-start" aria-label="使用工作配置开始项目工作">
    {!embedded && <div className="pw-project-start-heading"><h3>{operation ? '这次工作' : '让 AI 接着做'}</h3>
      {!expanded && !operation && <Button variant="secondary" leadingIcon={IconAdd} data-workspace-entry="project-new-work" onClick={() => patch({ expanded: true })}>新工作</Button>}
      {expanded && !operation && <Button variant="ghost" size="small" disabled={busy} onClick={closeForm}>收起</Button>}
    </div>}
    {embedded && !operation && <p className="pw-project-start-caption">使用已保存的模型、资料与工具组合，在所选副本中工作。</p>}
    {expanded && !operation && <div className="pw-project-start-form">
      <TextAreaField label="这次想完成什么？" placeholder="描述目标、要修改的内容或期待的结果…" value={draft.goal} onChange={goal => changeSelection({ goal })} rows={3} autoResize maxRows={7} maxLength={4000} isDisabled={busy} className="pw-project-goal" />
      {loading ? <p className="pw-project-start-status" role="status">正在读取工作配置与项目副本…</p> : snapshot && <>
        {profiles.length ? <div className="pw-project-profile-choice"><SelectField label="工作配置" placeholder="选择已保存配置" value={draft.profileId} onChange={chooseProfile} isDisabled={locked} options={[...unavailableSelection(draft.profileId, profile), ...profiles.map(row => ({ id: row.profileId, label: row.name || '未命名配置', description: row.restoredPendingReview ? '需先重新绑定来源' : `已保存修订 ${row.revision} · ${row.profileId.slice(-8)}`, disabled: row.restoredPendingReview === true || !isRevision(row.revision) }))]} />
          <Button variant="ghost" iconOnly leadingIcon={IconFileSettings} aria-label="管理工作配置" title="管理工作配置" disabled={busy} data-workspace-entry="project-work-profiles" onClick={onConfigure}/></div>
          : <div className="pw-project-start-setup"><IconFileSettings size={20} aria-hidden/><div><strong>先选好怎么工作</strong><p>保存常用模型与工具组合，再回到这里开始。</p></div><Button variant="secondary" trailingIcon={IconChevronRight} disabled={busy} data-workspace-entry="project-work-profiles" onClick={onConfigure}>配置工作方式</Button></div>}
        {profileChanged && <div className="pw-project-start-status" role="status">原来选的是修订 {draft.profileRevision}，现在已保存修订 {profile.revision}。目标和副本仍保留。
          {isRevision(profile.revision) && profile.restoredPendingReview!==true&&<Button variant="secondary" size="small" disabled={locked}
            onClick={()=>chooseProfile(profile.profileId)}>选用修订 {profile.revision}</Button>}</div>}
        {checkouts.length ? <Accordion className="pw-project-start-options" defaultExpandedKeys={!checkout || !runtime ? ['project-work-location'] : []}><AccordionItem id="project-work-location" title="工作副本与运行位置" description={checkout && runtime ? `${copyLabel(checkout.id)} · ${runtime.name || runtime.runtimeId}` : '选择这次工作的副本与环境'}>
          <div className="pw-project-start-selections"><SelectField label="工作副本" value={draft.checkoutId} onChange={checkoutId => changeSelection({ checkoutId })} isDisabled={locked} options={[...unavailableSelection(draft.checkoutId, checkout), ...checkouts.map(row => ({ id: row.id, label: copyLabel(row.id), description: [typeof row.fileCount === 'number' ? `${row.fileCount} 个文件` : '', typeof row.createdAt === 'number' && row.createdAt > 0 ? new Date(row.createdAt).toLocaleString('zh-CN') : ''].filter(Boolean).join(' · ') }))]}/>
            <SelectField label="运行位置" value={draft.runtimeId} onChange={runtimeId => changeSelection({ runtimeId })} isDisabled={locked} options={[...unavailableSelection(draft.runtimeId, runtime), ...runtimes.map(row => ({ id: row.runtimeId, label: row.name || row.runtimeId, description: row.description || undefined }))]}/>
          </div>
        </AccordionItem></Accordion>
          : <div className="pw-project-start-setup"><IconFolder size={20} aria-hidden/><div><strong>准备一份工作副本</strong><p>从当前项目内容建立私人副本，供这次工作使用。</p></div><Button variant="secondary" disabled={locked} onClick={prepareCheckout}>准备副本</Button></div>}
        {(!runtimes.length || needsLocalTools || runtime?.runtimeId==='termux') && <p className="pw-project-start-status">{runtime ? runtimeObservation(runtime) : '还没有可选运行位置。'}
          {onPrepareTools && (!runtimes.length || needsLocalTools) && <Button variant="ghost" size="small" disabled={busy} data-workspace-entry="project-work-tools" onClick={onPrepareTools}>准备运行环境</Button>}</p>}
      </>}
      {inspection && inspection.runnable !== true && <div className="pw-project-start-issues" role="status"><strong>这个项目暂不能用所选配置开始</strong>
        {!!missingModels.length && <p>尚未核对到{missingModels.map(id=>kindLabel('model',id)).join('、')}。请在这份配置里选择原模型。</p>}
        {!!foreignTargets.length && <p>这份配置有 {foreignTargets.length} 项单独绑定的执行目标。当前项目入口不接受这些绑定，只使用所选的本机副本；不会自动省略目标或切换位置。请调整这份配置，或回原目标的启动入口。</p>}
        {!!failedSources.length && <ul>{failedSources.map((item, index) => <li key={`${item.kind}:${item.id}:${index}`}>{kindLabel(item.kind, item.id)} · {item.optional ? '本次可省略' : profileDependencyLabel(item.status)}</li>)}</ul>}
        {!missingModels.length&&!foreignTargets.length&&!failedSources.length&&!items.some(item=>isProfileSkill(item)&&item.included!==true)&&<p>没有取得同一范围的可启动预检，请重新读取所选配置。</p>}
        <WorkProfileSkillPreparation preflight={inspection}/>
        <div className="pw-project-start-actions">{onPrepareModel && <Button variant="ghost" disabled={busy} data-workspace-entry="project-work-models" onClick={onPrepareModel}>连接模型</Button>}<Button variant="ghost" disabled={busy} data-workspace-entry="project-work-profiles" onClick={onConfigure}>调整工作配置</Button></div>
      </div>}
      <div className="pw-project-start-footer"><Button variant="ghost" iconOnly leadingIcon={IconRefresh} aria-label="重新读取工作准备" title="重新读取工作准备" disabled={busy} onClick={restartWindow}/><span/>
        <Button variant="primary" leadingIcon={IconPlay} disabled={!ready} onClick={prepare}>{busy ? '正在核对…' : '核对这次工作'}</Button>
      </div>
    </div>}
    {operation && <div className="pw-project-start-receipt" role="status" aria-live="polite">
      {operation.taskId ? <>{operation.admitted ? <IconCheckCircle size={22} aria-hidden/> : <IconClock size={22} aria-hidden/>}
        <div><strong>{operation.admitted ? '任务已开始' : '工作已登记，启动未确认'}</strong><p dir="auto">{operation.goal}</p>
          {admissionPending && <p>先核对这次交办，不会重新提交目标。</p>}{operation.error && <p>{operation.error}</p>}</div>
        <div className="pw-project-start-actions">
          {!operation.admitted && <Button variant={admissionPending ? 'primary' : 'ghost'} leadingIcon={IconRefresh} disabled={busy || loading}
            onClick={snapshot ? queryResult : restartWindow}>{busy ? '正在查询…' : '核对启动回执'}</Button>}
          {!admissionPending && <Button variant="primary" trailingIcon={IconChevronRight} disabled={!supported('tasks.open')} data-project-receipt-task={operation.taskId}
            onClick={() => onOpenTask(operation.taskId!, operation.checkoutId)}>查看原工作</Button>}
          <Button variant="ghost" disabled={busy || admissionPending} onClick={() => { patch({ operation: null, expanded: true }); restartWindow(); }}>新工作</Button>
        </div></>
        : <><div><strong>{operation.command === 'prepareCheckout' ? '正在确认工作副本' : '正在确认这次启动'}</strong><p>{operation.status === 'not_entered' ? '原请求尚未进入。输入已保留，可以重新准备。' : operation.status === 'unknown' ? '结果还没有确认。先查询这次操作，避免重复创建。' : '请求已送出，正在等本次操作的真实回执。'}</p>{operation.goal && <p dir="auto">{operation.goal}</p>}{operation.error && <p>{operation.error}</p>}</div><div className="pw-project-start-actions"><Button variant="secondary" leadingIcon={IconRefresh} disabled={busy || loading} onClick={snapshot ? queryResult : restartWindow}>{busy ? '正在查询…' : loading ? '正在读取…' : snapshot ? '核对结果' : '重新读取项目'}</Button>{operation.status === 'not_entered' && <Button variant="ghost" disabled={busy} onClick={() => { patch({ operation: null, expanded: true }); restartWindow(); }}>返回准备</Button>}</div></>}
    </div>}
    {error && !review && <p className="pw-project-start-error" role="alert">{error}</p>}
    <SettingsDialog isOpen={!!review} title="核对这次项目工作" closeLabel="返回项目目标" onClose={() => { if (!pending.current) restartWindow(); }}>
      {review && <div className="pw-project-start-review"><p className="pw-project-review-goal" dir="auto">{review.goal}</p>
        <dl><div><dt>项目</dt><dd>{project.name}</dd></div><div><dt>工作配置</dt><dd>{review.definition?.name || '已保存配置'} · 修订 {review.original.revision} · {review.original.profileId.slice(-8)}</dd></div>
          <div><dt>工作副本</dt><dd>{copyLabel(review.project.checkoutId)}<br/><small>源码 <code>{review.project.initialSourceDigest.slice(0,12)}</code></small></dd></div>
          <div><dt>运行位置</dt><dd>{referenceName(review.project.runtime, runtimes, 'runtimeId')}<br/><small>{runtimeObservation(runtime)}</small></dd></div>
          <div><dt>主任务模型</dt><dd dir="auto">{modelReviewLabel(review.preflight,'MAIN')}</dd></div><div><dt>摘要模型</dt><dd dir="auto">{modelReviewLabel(review.preflight,'SUMMARY')}</dd></div></dl>
        <WorkProfileSkillPreparation preflight={review.preflight}/>
        {!!resolvedTools.length&&<Accordion><AccordionItem id="project-review-plugin-tools" title="预检选入的插件工具" description={`${resolvedTools.length} 项`}>
          <ul>{resolvedTools.map((name:string,index:number)=><li key={index}>{name}</li>)}</ul>
        </AccordionItem></Accordion>}
        {review.preflight.optionalOmissions>0&&<p className="pw-project-start-caption">有 {review.preflight.optionalOmissions} 项可选来源未就绪，本次省略，没有换成别的来源。</p>}
        <p className="pw-project-start-caption">这里只开始这次任务，不表示副本已运行或构建。命令与权限仍按原任务范围另行核对。</p>
        <CheckboxField isSelected={acknowledged} isDisabled={busy} onChange={setAcknowledged}>确认目标、配置修订、副本与运行位置</CheckboxField>
        {error && <p className="pw-project-start-error" role="alert">{error}</p>}
        <div className="pw-project-start-actions"><Button variant="ghost" leadingIcon={IconArrowLeft} disabled={busy} onClick={restartWindow}>返回修改</Button><Button variant="primary" leadingIcon={IconPlay} disabled={busy || !acknowledged} onClick={confirm}>开始工作</Button></div>
      </div>}
    </SettingsDialog>
  </section>;
}
