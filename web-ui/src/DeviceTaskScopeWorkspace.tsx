import { useCallback, useEffect, useLayoutEffect, useRef, useState, useSyncExternalStore } from 'react';
import { Accordion, AccordionItem, Button, CheckboxField, SearchField, SettingsDialog, TextField } from './forms-library';
import { bridge, type PocketSnapshot } from './bridge';
import { deviceChoiceCode, materialPath, phoneTextOnly, routeDraft, routeTitle, routeUseSummary, scopeRows as rows, scopeSelection, type RouteDraft, type ScopeData as Data } from './device-task-scope';
import { deviceOriginalManualPhonePreparation } from './devices-workspace';
import { OriginalPhoneDispatchStatus } from './OriginalPhoneDispatchStatus';
import { originalWindowsWorkspacePickerAt, validWindowsWorkspaceFilePath as validWorkspaceFilePath } from './OriginalWindowsTextRead';
import { usePocketSnapshot } from './usePocketSnapshot';

type Run = (action: string, args?: Data) => Promise<any>;
const time = (value: any) => Number.isSafeInteger(Number(value)) ? new Date(Number(value)).toLocaleString() : '尚未确认';
const deviceName = (id: string, peers: Data[]) => {
  const alias=peers.find(peer => peer.deviceId === id)?.label,code=deviceChoiceCode(id);
  return code ? `${alias || '设备'} · ${code}` : '未核对到设备身份';
};
const routeName = (route:Data, names:Data[]):string => {
  const instance=route.targetInstanceId || route.instanceId;
  if(!route.sourceKey || !route.targetDeviceId || !instance || !route.grantId || !Number.isSafeInteger(route.authorizationEpoch))return '';
  const labels=new Set(names.filter(n=>n.sourceKey===route.sourceKey && n.targetDeviceId===route.targetDeviceId &&
    n.targetInstanceId===instance && n.grantId===route.grantId && n.authorizationEpoch===route.authorizationEpoch &&
    typeof n.label==='string' && n.label).map(n=>n.label));
  return labels.size===1 ? [...labels][0] : '';
};

const tools = (value: any): string => Array.isArray(value) ? value.map(row => row.toolName).join('、') : value && typeof value === 'object' ? Object.keys(value).join('、') : '未提供';

type WorkspaceReadFacts = { id: string; actions: string; readsFile: boolean; listsDirectory: boolean };

/** Present the exact native workspace and read declarations, not a path or a new permission. */
function workspaceReadFacts(value: Data, original?: OriginalDevice): WorkspaceReadFacts | undefined {
  const binding = value.resourceBinding, instance = value.targetInstanceId ?? value.instanceId;
  if (!original || value.targetDeviceId !== original.deviceId || instance !== original.instanceId ||
    value.targetInstanceId != null && value.instanceId != null && value.targetInstanceId !== value.instanceId ||
    value.kind !== 'tool_session' || value.capabilityVersion !== 'workspace-v1' || binding?.kind !== 'workspace' ||
    Object.keys(binding).length !== 3 || typeof binding.environmentId !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_-]{0,79}$/.test(binding.environmentId) ||
    typeof binding.binding !== 'string' || !/^[a-f0-9]{64}$/.test(binding.binding)) return;
  const declarations = value.toolDeclarations;
  const entries = Array.isArray(declarations) ? declarations.map(row => [row?.toolName, row?.effect]) :
    declarations && typeof declarations === 'object' ? Object.entries(declarations) : [];
  if (!entries.length || entries.length > 2 || entries.some(([name, effect]) => effect !== 'read' ||
    (name !== 'workspace.list' && name !== 'workspace.read'))) return;
  const agreed = new Set(entries.map(([name]) => name));
  if (agreed.size !== entries.length) return;
  return { id: binding.environmentId, readsFile: agreed.has('workspace.read'), listsDirectory: agreed.has('workspace.list'),
    actions: [agreed.has('workspace.list') && '列目录', agreed.has('workspace.read') && '读小型 UTF-8 文本'].filter(Boolean).join('、') };
}

function ScopeFacts({ value, peers, names, human = false, workspace, firstBrowse = false }: { value: Data; peers: Data[]; names:Data[]; human?: boolean; workspace?: WorkspaceReadFacts; firstBrowse?: boolean }) {
  const resource = value.resourceBinding || {};
  const declarations = Array.isArray(value.toolDeclarations) ? value.toolDeclarations : Object.entries(value.toolDeclarations || {}).map(([toolName, effect]) => ({ toolName, effect }));
  const writes = declarations.filter(row => row.effect === 'write').length, external = declarations.filter(row => row.effect === 'external').length;
  const target = resource.kind === 'desktop' ? `电脑屏幕 ${resource.displayId}${resource.windowId ? ' · 指定窗口 ' + resource.windowId : ''}`
    : resource.screenMode === 'main_screen' || resource.screenMode === 'background' ? `${resource.screenMode === 'background' ? '独立后台屏' : '手机主屏'} · ${(resource.packageNames || []).join('、') || '按原授权应用范围'}`
    : resource.projectId ? `项目 ${resource.projectId}${resource.checkoutId ? ' · 工作副本 ' + resource.checkoutId : ''}` : phoneTextOnly(value) ? '只发本次目标，不带附件、记忆、项目或屏幕' : resource.screenMode === 'capabilities' ? '仅对方授权的能力范围，不含手机屏幕' : '对端明确授权的资源';
  return <>
    <dl className="pw-facts">
      <div><dt>接收设备</dt><dd>{routeName(value,names) || deviceName(value.targetDeviceId || '', peers)}</dd></div>
      <div><dt>使用方式</dt><dd>{routeTitle(value, human)}</dd></div>
      <div><dt>作用范围</dt><dd>{workspace ? <>电脑工作区 <bdi style={{overflowWrap:'anywhere'}}>{workspace.id}</bdi></> : target}</dd></div>
      <div><dt>{workspace ? '允许的读取' : '操作影响'}</dt><dd>{workspace ? workspace.actions : human ? '你逐次进行全屏输入；不调用模型，不继承 AI 自动操作权限' : phoneTextOnly(value) ? '对方不使用工具；机主核对后，本机可从原工作查看最多 2,048 个字的摘要' : value.kind === 'goal' ? '对端模型在原授权工具范围内处理目标' : writes || external ? `含 ${writes} 项写入操作、${external} 项外部影响能力，执行时仍受原工具审批约束` : declarations.length && declarations.every(row => row.effect === 'read') ? '当前列出的工具仅报告读取能力' : '尚未取得完整操作影响，不能推定只读'}</dd></div>
      {!firstBrowse && value.toolBudget !== undefined && <div><dt>累计份额</dt><dd>工具 {value.toolBudget} 次 · 模型 {value.modelBudget} 次</dd></div>}
      {value.deadline && <div><dt>截止</dt><dd>{time(value.deadline)}</dd></div>}
    </dl>
    {!human && resource.screenMode && resource.allowWholeDisplayCapture === true && <p>画面留存：从“新设备工作”建立的任务，已验证的手机观察帧会按本机“截图留存”设置加密保存为历史快照，默认 1 天；关闭留存则不会在任务中显示新图。只保留最近一张，单张不超过 4 MB，不是实时视频或点图控制。</p>}
    <Accordion><AccordionItem id="exact-resource" title={firstBrowse ? '用量与准确身份' : '核对准确身份与工具'}><dl className="pw-facts"><div><dt>设备身份</dt><dd>{value.targetDeviceId}</dd></div><div><dt>准确资源</dt><dd>{JSON.stringify(resource)}</dd></div><div><dt>明确工具</dt><dd>{tools(value.toolDeclarations)}</dd></div>
      {firstBrowse && <div><dt>无模型浏览的用量上限</dt><dd>电脑工具最多 {value.toolBudget} 次，每次另占 1 次手机派发额度；不占用模型调用。</dd></div>}
    </dl></AccordionItem></Accordion>
  </>;
}

function RouteMaterials({ kind, option, view, draft, disabled, update }: { kind: 'files' | 'projects'; option: Data; view: Data; draft: RouteDraft; disabled: boolean; update: (next: RouteDraft) => void }) {
  const allowed = rows(option.toolDeclarations).some(tool => tool.toolName === (kind === 'files' ? 'workspace.receive' : 'workspace.project.receive') && tool.effect === 'write');
  const catalog = rows(view[kind === 'files' ? 'materialOptions' : 'projectOptions']);
  const selected = draft[kind], max = kind === 'files' ? 4 : 1;
  if (!allowed && !selected.length) return null;
  const missing = selected.filter(item => !catalog.some(row => row.id === item.id && row.sha256 === item.sha256 && row.available === true));
  const change = (items: RouteDraft['files']) => update({ ...draft, [kind]: items });
  return <AccordionItem id={kind} title={kind === 'files' ? '发送明确原件' : '发送已核实项目包'} description={selected.length ? '已选 ' + selected.length + ' 项' : '默认不发送'}>
    <p>只分享你在这里选择的原件，不包含私人记忆或整份工作区。{kind === 'files' ? '每项上限 256 KiB，最多 4 项。' : '最多 1 个固定项目包；普通 ZIP 不作为项目。接收与导入为两次独立确认。'}</p>
    {!allowed && <p role="alert">当前范围未报告接收权限；请移除旧选择，或回设备端明确授权。</p>}
    {missing.map(item => <div key={item.id}><p role="alert">原选择 {item.path} 已无法在当前原件目录中核对。</p><Button variant="ghost" disabled={disabled} onClick={() => change(selected.filter(row => row.id !== item.id))}>移除此项选择</Button></div>)}
    {allowed && catalog.map(item => {
      const chosen = selected.find(row => row.id === item.id && row.sha256 === item.sha256);
      const patch = (key: 'path' | 'expectedVersion', value: string) => change(selected.map(row => row === chosen ? { ...row, [key]: value } : row));
      return <div key={item.id}>
        <CheckboxField isSelected={!!chosen} isDisabled={disabled || item.available !== true || !chosen && selected.length >= max} onChange={checked => change(checked ? [...selected, { id: item.id, sha256: item.sha256, path: materialPath(item.name || 'project-source.zip'), expectedVersion: 'absent' }] : selected.filter(row => row !== chosen))}>
          {item.name || item.id} · {item.size} 字节{item.available === false ? ' · 当前不可发送' : ''}
        </CheckboxField>
        {chosen && <><TextField label="对端工作区中的相对路径" value={chosen.path} isDisabled={disabled} onChange={value => patch('path', value)} />
          {kind === 'files' ? <TextField label="目标文件的预期版本" value={chosen.expectedVersion} isDisabled={disabled} onChange={value => patch('expectedVersion', value)} hint="新建填 absent；覆盖必须填写你已核对的原版本，不做盲覆盖。" /> : <p>仅写入不存在的新路径，不覆盖已有项目。清单 {item.manifestDigest}</p>}
        </>}
      </div>;
    })}
    {allowed && !catalog.length && <p>当前没有可选择的私人原件。没有自动导入其他附件或目录。</p>}
  </AccordionItem>;
}

type OriginalDevice = { deviceId: string; instanceId: string; certificateFingerprint: string };

const workspaceFileReview = 'deviceTaskScope.reviewWorkspaceFile';
const workspaceFileStart = 'deviceTaskScope.startWorkspaceFile';
const workspaceFileDismiss = 'deviceTaskScope.dismissWorkspaceFile';
const workspaceDirectoryReview = 'deviceTaskScope.reviewWorkspaceDirectory';
const workspaceDirectoryStart = 'deviceTaskScope.startWorkspaceDirectory';
const workspaceListedReview = 'deviceTaskScope.reviewWorkspaceListedFile';
const workspaceListedStart = 'deviceTaskScope.startWorkspaceListedFile';
const workspaceFileCommands = (snapshot: PocketSnapshot) => [workspaceFileReview, workspaceFileStart, workspaceFileDismiss]
  .every(action => snapshot.capabilities.commands.includes(action));
const workspacePickerCommands = (snapshot: PocketSnapshot) =>
  [workspaceDirectoryReview, workspaceDirectoryStart, workspaceListedReview, workspaceListedStart, workspaceFileDismiss]
    .every(action => snapshot.capabilities.commands.includes(action));
const record = (value: unknown): Data | undefined => value && typeof value === 'object' && !Array.isArray(value) ? value as Data : undefined;
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(value);
const digest = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const emptyRows = (value: unknown) => Array.isArray(value) && value.length === 0;

function originalWorkspaceFileWork(snapshot: PocketSnapshot, taskId: string) {
  const view = record(snapshot.view), state = snapshot.state;
  if (!uuid(taskId) || view?.taskId !== taskId || view.section !== null || !Number.isSafeInteger(view.resumeRevision) ||
    view.resumeRevision < 0 || state.attachmentWorkspace?.open === true || state.conversationReader || state.filePath ||
    !Array.isArray(state.tasks)) return;
  const matches = state.tasks.filter(value => record(value)?.id === taskId);
  const task = matches.length === 1 ? record(matches[0]) : undefined, raw = record(task?.raw);
  const work = record(raw?.ownerDeviceWork), target = record(work?.directWorkspace);
  if (!raw || raw.id !== taskId || raw.status !== task?.status || raw.actorId !== 'owner' || raw.spaceId !== 'private' ||
    raw.kind !== 'assistant' || raw.executionMode !== 'capabilities' || raw.conversationId !== '' || raw.origin != null ||
    raw.parentTaskId != null || raw.ownerPhoneControl != null || raw.developmentContext != null || raw.contextAvailable === false || raw.contextWithdrawn === true ||
    raw.sourceCurrentReadRequired === true || raw.taskProjection !== 'detail_window' || raw.taskCoreReadLimited !== false ||
    work?.format !== 'pocketworld.owner-device-work.v1' || work.requestId !== taskId || !digest(work.requestDigest) ||
    work.directPhone != null || !target || !deviceChoiceCode(target.deviceId) || typeof target.instanceId !== 'string' ||
    !/^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(target.instanceId) ||
    !digest(target.certificateFingerprint)) return;
  const original: OriginalDevice = { deviceId: target.deviceId, instanceId: target.instanceId, certificateFingerprint: target.certificateFingerprint };
  return { raw, original, key: JSON.stringify([taskId, view.resumeRevision, work.requestDigest, original]) };
}
type WorkspaceFileWork = NonNullable<ReturnType<typeof originalWorkspaceFileWork>>;
const workspaceFileUnstarted = ({ raw }: WorkspaceFileWork) => raw.status === 'paused' && raw.runId === '' &&
  raw.coordinatorEpoch === 0 && raw.cancelRequested === false && raw.hasUnresolvedOperations === false && raw.ownerWindowsWorkspaceRead !== true;
export function isUnstartedDirectWorkspaceWork(taskId: string, snapshot: PocketSnapshot) {
  const work = originalWorkspaceFileWork(snapshot, taskId);
  return !!work && workspaceFileUnstarted(work);
}
function workspaceFileChoice(work: WorkspaceFileWork, taskId: string, directory = false) {
  if (!workspaceFileUnstarted(work)) return;
  const toolBudget = directory ? 2 : 1;
  const scope = record(work.raw.deviceSourceScope), execution = record(work.raw.executionScope), now = Date.now();
  if (!execution || scope?.format !== 'pocketworld.native-device-source-scope.v1' || scope.taskId !== taskId || scope.contextMode !== 'goal_only' ||
    !Number.isSafeInteger(scope.revision) || scope.revision < 1 || !Number.isSafeInteger(scope.parentScopeRevision) ||
    scope.parentScopeRevision !== execution.revision || !Number.isSafeInteger(execution.deadlineAt) ||
    execution.deadlineAt > 0 && execution.deadlineAt <= now || execution.executionPhase === 'planning' ||
    !Number.isSafeInteger(execution.toolLimit) || !Number.isSafeInteger(execution.toolsUsed) || execution.toolLimit - execution.toolsUsed < 2 * toolBudget ||
    !Array.isArray(scope.routes) || scope.routes.length !== 1) return;
  const route = record(scope.routes[0]), facts = route && workspaceReadFacts(route, work.original);
  if (!route || !facts?.readsFile || directory && !facts.listsDirectory ||
    route.contextMode !== 'goal_only' || route.toolBudget !== toolBudget || route.localToolShare !== toolBudget ||
    route.modelBudget !== 0 || route.localModelShare !== 0 || !emptyRows(route.files) || !emptyRows(route.projects) || !emptyRows(route.downstreamShares) ||
    !Number.isSafeInteger(route.deadline) || route.deadline <= now || typeof route.optionId !== 'string' || !route.optionId ||
    typeof route.grantId !== 'string' || !route.grantId || !digest(route.grantFingerprint) ||
    !Number.isSafeInteger(route.authorizationEpoch) || route.authorizationEpoch < 1) return;
  return { workspaceId: facts.id, key: JSON.stringify([toolBudget, scope.revision, scope.parentScopeRevision, route.optionId,
    route.grantId, route.grantFingerprint, route.authorizationEpoch, route.resourceBinding.binding, route.deadline]) };
}
export function canReviewDirectWorkspace(taskId: string, snapshot: PocketSnapshot) {
  const work = originalWorkspaceFileWork(snapshot, taskId);
  return !!work && (workspaceFileCommands(snapshot) && !!workspaceFileChoice(work, taskId) ||
    workspacePickerCommands(snapshot) && !!workspaceFileChoice(work, taskId, true));
}

type WorkspaceFileModelStart = { disabled: boolean; start: () => void };
type WorkspaceReadSource = { kind: 'file'; path: string } | { kind: 'directory'; relativeDirectory: string } |
  { kind: 'listed'; path: string; runId: string; directoryOperationId: string; entryIndex: number };
type WorkspaceFileDraft = WorkspaceReadSource & { work: string; choice: string; epoch: number; workspace: string;
  reviewing?: boolean; review?: { handle: string; expiresAt: number }; error?: string };
type WorkspaceFileAttempt = { work: string; epoch: number; kind: WorkspaceReadSource['kind'];
  state: 'pending' | 'registered' | 'unknown'; runId?: string };

const visibleWorkspaceFiles = 8;

/** Query only the signed names already returned for this exact directory; never send the query or a path. */
function WorkspaceListedChoices({ choices, disabled, onChoose }: {
  choices: readonly { entryIndex: number; name: string }[]; disabled: boolean; onChoose: (index: number) => void;
}) {
  const [query, setQuery] = useState('');
  const needle = query.toLowerCase();
  const rank = (name: string) => name === query ? 0 : name.toLowerCase() === needle ? 1 : 2;
  const matches = query === '' ? choices :
    choices.filter(row => row.name.toLowerCase().includes(needle)).sort((a, b) => rank(a.name) - rank(b.name));
  return <>
    {choices.length > visibleWorkspaceFiles && <SearchField label="筛选已收到的文件" placeholder="输入文件名"
      clearLabel="清除文件名筛选" hint="只在这次的目录记录中筛选，不重新读取电脑。"
      isDisabled={disabled} value={query} onChange={setQuery}/>}
    {matches.length > 0 ? <ul>{matches.slice(0, visibleWorkspaceFiles).map(row => <li key={row.entryIndex}>
      <Button variant="secondary" disabled={disabled} onClick={() => onChoose(row.entryIndex)}>
        <bdi style={{ overflowWrap: 'anywhere' }}>{row.name}</bdi>
      </Button>
    </li>)}</ul> : <p role="status">这次收到的可选文件中没有匹配项。</p>}
    {query !== '' && matches.length > visibleWorkspaceFiles && <p>还有匹配项，请再输入一些文件名。</p>}
  </>;
}

function WorkspaceFileRead({ taskId, active, disabled, modelStart, registerBack, onPending }: { taskId: string; active: boolean;
  disabled: boolean; modelStart?: WorkspaceFileModelStart; registerBack: (handler: (() => boolean) | null) => void; onPending: (value: boolean) => void }) {
  const snapshot = usePocketSnapshot(), activeRef = useRef(active), disabledRef = useRef(disabled);
  const documentEpoch = useSyncExternalStore(bridge.subscribeDocumentEpoch, bridge.getDocumentEpoch, bridge.getDocumentEpoch);
  activeRef.current = active; disabledRef.current = disabled;
  const [draft, setDraft] = useState<WorkspaceFileDraft | null>(null), currentDraft = useRef<WorkspaceFileDraft | null>(null);
  const [attempt, setAttempt] = useState<WorkspaceFileAttempt | null>(null), currentAttempt = useRef<WorkspaceFileAttempt | null>(null);
  const [, redrawDeadline] = useState(0);
  const reviewSummary = useRef<HTMLParagraphElement>(null);
  const change = (value: WorkspaceFileDraft | null) => { currentDraft.current = value; setDraft(value); };
  const writeAttempt = (value: WorkspaceFileAttempt) => { currentAttempt.current = value; setAttempt(value); };
  const retire = (handle: string | undefined, epoch: number) => {
    if (handle && bridge.getDocumentEpoch() === epoch && bridge.getState().capabilities.commands.includes(workspaceFileDismiss))
      void bridge.command(workspaceFileDismiss, { reviewHandle: handle }).catch(() => {});
  };
  const workInWindow = (key: string, epoch: number) => {
    if (!activeRef.current || document.hidden || bridge.getDocumentEpoch() !== epoch) return;
    const work = originalWorkspaceFileWork(bridge.getState(), taskId);
    if (work?.key === key) return work;
  };
  const draftCurrent = (value: WorkspaceFileDraft) => {
    const work = workInWindow(value.work, value.epoch);
    if (!work) return false;
    const live = bridge.getState();
    if (value.kind === 'file') return workspaceFileCommands(live) && workspaceFileChoice(work, taskId)?.key === value.choice;
    if (!workspacePickerCommands(live)) return false;
    if (value.kind === 'directory') return workspaceFileChoice(work, taskId, true)?.key === value.choice;
    const picker = originalWindowsWorkspacePickerAt(live, taskId), directory = picker?.directory;
    return !!picker?.canChoose && picker.runId === value.runId && !!directory &&
      directory.operationId === value.directoryOperationId && directory.received.identity === value.choice &&
      directory.workspaceId === value.workspace && directory.choices.some(row => row.entryIndex === value.entryIndex && row.path === value.path);
  };
  const back = () => {
    const value = currentDraft.current;
    if (!value) return false;
    if (value.kind === 'file' && value.review && draftCurrent(value)) change({ ...value, review: undefined, error: undefined });
    else change(null);
    retire(value.review?.handle, value.epoch);
    return true;
  };
  const handleBack = useRef(back); handleBack.current = back;
  useLayoutEffect(() => {
    registerBack(() => handleBack.current());
    return () => {
      registerBack(null);
      const value = currentDraft.current;
      currentDraft.current = null;
      if (value) retire(value.review?.handle, value.epoch);
      currentAttempt.current = null;
      onPending(false);
    };
  }, [taskId, registerBack, onPending]);
  useLayoutEffect(() => {
    const invalidate = () => {
      const value = currentDraft.current;
      if (value && !draftCurrent(value)) { change(null); retire(value.review?.handle, value.epoch); }
    };
    invalidate();
    const token = bridge.subscribeDocumentEpoch(invalidate);
    document.addEventListener('visibilitychange', invalidate);
    return () => { token(); document.removeEventListener('visibilitychange', invalidate); };
  }, [taskId, active, snapshot]);
  useLayoutEffect(() => { if (draft?.review) reviewSummary.current?.focus({ preventScroll: true }); }, [draft?.review]);
  useEffect(() => {
    if (!draft?.review) return;
    const timer = window.setTimeout(() => {
      if (currentDraft.current !== draft || !draftCurrent(draft)) return;
      retire(draft.review?.handle, draft.epoch);
      change({ ...draft, review: undefined, error: draft.kind === 'file' ? '这次核对已到期，请重新核对这个路径。' : '这次核对已到期，请重新核对。' });
    }, Math.max(0, draft.review.expiresAt - Date.now()));
    return () => window.clearTimeout(timer);
  }, [draft]);

  const check = () => {
    const value = currentDraft.current;
    if (!value || value.reviewing || value.review || disabledRef.current || !draftCurrent(value) ||
      value.kind === 'file' && !validWorkspaceFilePath(value.path) ||
      value.kind === 'directory' && value.relativeDirectory !== '' && !validWorkspaceFilePath(value.relativeDirectory)) return;
    const flight: WorkspaceFileDraft = { ...value, reviewing: true, error: undefined };
    const action = flight.kind === 'file' ? workspaceFileReview : flight.kind === 'directory' ? workspaceDirectoryReview : workspaceListedReview;
    const args = flight.kind === 'file' ? { taskId, path: flight.path } : flight.kind === 'directory' ?
      flight.relativeDirectory === '' ? { taskId } : { taskId, relativeDirectory: flight.relativeDirectory } :
      { taskId, runId: flight.runId, epoch: 1, directoryOperationId: flight.directoryOperationId, entryIndex: flight.entryIndex };
    change(flight);
    void bridge.command(action, args).then(result => {
      const reply = record(result);
      if (!reply || reply.taskId !== taskId || reply.workspaceId !== flight.workspace || !uuid(reply.reviewHandle) ||
        !Number.isSafeInteger(reply.expiresAt) || reply.modelCalls !== 0 || reply.executionAuthorized !== false ||
        (flight.kind === 'file' ? reply.format !== 'pocketworld.owner-windows-workspace-read-review.v1' ||
          reply.exactPath !== flight.path || reply.maxDisplayBytes !== 8192 :
          flight.kind === 'directory' ? reply.format !== 'pocketworld.owner-windows-workspace-directory-review.v1' ||
            reply.relativeDirectory !== flight.relativeDirectory || reply.remoteToolLimit !== 2 :
            reply.format !== 'pocketworld.owner-windows-workspace-listed-file-review.v1' || reply.exactPath !== flight.path || reply.maxDisplayBytes !== 8192 ||
            reply.runId !== flight.runId || reply.epoch !== 1 || reply.directoryOperationId !== flight.directoryOperationId || reply.entryIndex !== flight.entryIndex))
        throw new Error('unmatched review');
      if (currentDraft.current !== flight || !draftCurrent(flight)) { retire(reply.reviewHandle, flight.epoch); return; }
      if (reply.expiresAt <= Date.now() || reply.expiresAt > Date.now() + 120_000) { retire(reply.reviewHandle, flight.epoch); throw new Error('expired review'); }
      change({ ...flight, reviewing: false, review: { handle: reply.reviewHandle, expiresAt: reply.expiresAt } });
    }).catch(() => {
      if (currentDraft.current !== flight || !draftCurrent(flight)) return;
      change({ ...value, error: flight.kind === 'file' ? '未核对到这个文件的读取范围。请检查相对路径、原电脑连接和已保存范围。' :
        flight.kind === 'directory' ? '未核对到原电脑工作区的列目录范围，没有联系电脑读取。' :
          '未核对到刚选的原目录文件。目录记录仍保留，没有读取其他文件。' });
    });
  };
  const begin = (kind: 'file' | 'directory') => {
    const live = bridge.getState(), work = originalWorkspaceFileWork(live, taskId), epoch = bridge.getDocumentEpoch();
    const choice = work && workspaceFileChoice(work, taskId, kind === 'directory');
    if (!work || !choice || !(kind === 'file' ? workspaceFileCommands(live) : workspacePickerCommands(live)) ||
      !epoch || !workInWindow(work.key, epoch) || disabledRef.current || currentDraft.current || currentAttempt.current) return;
    const captured = { work: work.key, choice: choice.key, epoch, workspace: choice.workspaceId };
    if (kind === 'file') change({ ...captured, kind, path: '' });
    else { change({ ...captured, kind, relativeDirectory: '' }); check(); }
  };
  const beginListed = (shown: NonNullable<ReturnType<typeof originalWindowsWorkspacePickerAt>>, entryIndex: number) => {
    const live = bridge.getState(), work = originalWorkspaceFileWork(live, taskId), epoch = bridge.getDocumentEpoch();
    const picker = originalWindowsWorkspacePickerAt(live, taskId), directory = picker?.directory, displayed = shown.directory;
    const option = directory?.choices.find(row => row.entryIndex === entryIndex);
    if (!work || !epoch || !workInWindow(work.key, epoch) || !workspacePickerCommands(live) || !picker?.canChoose ||
      picker.runId !== shown.runId || !directory || !displayed || directory.operationId !== displayed.operationId ||
      directory.received.identity !== displayed.received.identity || !option ||
      disabledRef.current || currentDraft.current || currentAttempt.current?.state === 'pending' || currentAttempt.current?.kind === 'listed') return;
    change({ work: work.key, choice: directory.received.identity, epoch, workspace: directory.workspaceId, kind: 'listed', path: option.path,
      runId: picker.runId, directoryOperationId: directory.operationId, entryIndex: option.entryIndex });
    check();
  };
  const confirm = () => {
    const value = currentDraft.current, attempted = currentAttempt.current;
    if (!value?.review || disabledRef.current || (value.kind === 'listed' ? attempted?.kind === 'listed' || attempted?.state === 'pending' : !!attempted) ||
      !draftCurrent(value) || value.review.expiresAt <= Date.now()) return;
    const pending: WorkspaceFileAttempt = { work: value.work, epoch: value.epoch, kind: value.kind, state: 'pending',
      runId: value.kind === 'listed' ? value.runId : undefined };
    const action = value.kind === 'file' ? workspaceFileStart : value.kind === 'directory' ? workspaceDirectoryStart : workspaceListedStart;
    writeAttempt(pending); onPending(true); change(null);
    void bridge.command(action, { reviewHandle: value.review.handle }).then(result => {
      const reply = record(result);
      if (!reply || reply.taskId !== taskId || !uuid(reply.runId) || reply.epoch !== 1 || reply.state !== 'registered' ||
        reply.modelCalls !== 0 || reply.replayAllowed !== false ||
        (value.kind === 'directory' ? reply.format !== 'pocketworld.owner-windows-workspace-directory-start.v1' || reply.directoryReceiptConfirmed !== false :
          reply.receiptConfirmed !== false || (value.kind === 'file' ? reply.format !== 'pocketworld.owner-windows-workspace-read-start.v1' :
            reply.format !== 'pocketworld.owner-windows-workspace-listed-file-start.v1' || reply.runId !== value.runId ||
            reply.directoryOperationId !== value.directoryOperationId)))
        throw new Error('unmatched registration');
      if (currentAttempt.current === pending) writeAttempt({ ...pending, state: 'registered', runId: reply.runId });
    }).catch(() => {
      if (currentAttempt.current === pending) writeAttempt({ ...pending, state: 'unknown' });
    }).finally(() => { if (currentAttempt.current) onPending(false); });
  };

  const work = active && !document.hidden ? originalWorkspaceFileWork(snapshot, taskId) : undefined;
  const choice = work && workspaceFileCommands(snapshot) && workspaceFileChoice(work, taskId);
  const directoryChoice = work && workspacePickerCommands(snapshot) && workspaceFileChoice(work, taskId, true);
  const picker = work && originalWindowsWorkspacePickerAt(snapshot, taskId), directory = picker?.directory;
  const sameAttempt = work && attempt && attempt.work === work.key && attempt.epoch === documentEpoch ? attempt : undefined;
  const canChoose = picker?.canChoose === true && workspacePickerCommands(snapshot) &&
    sameAttempt?.state !== 'pending' && sameAttempt?.kind !== 'listed';
  const deadline = picker?.phase === 'waiting_for_file' ? picker.expiresAt : undefined;
  useEffect(() => {
    if (deadline === undefined || deadline <= Date.now()) return;
    const timer = window.setTimeout(() => redrawDeadline(value => value + 1), deadline - Date.now());
    return () => window.clearTimeout(timer);
  }, [deadline]);
  const stillWaiting = work && sameAttempt && sameAttempt.kind !== 'listed' && !picker &&
    (workspaceFileUnstarted(work) || sameAttempt.runId === work.raw.runId &&
      work.raw.coordinatorEpoch === 1 && ['preparing', 'queued', 'running'].includes(work.raw.status));
  const listedAttempt = sameAttempt?.kind === 'listed' && sameAttempt.runId === picker?.runId ? sameAttempt : undefined;
  const directoryStatus = !picker ? undefined :
    picker.phase === 'ended' ? picker.fileReceived ? undefined : directory ?
      directory.choices.length ? '没有可核对的原文本；仍可查看当时的目录。' : '原目录没有可选择的 8 KiB 以内文件。' :
      '尚未核对到这次的原目录或文本回执。' :
    picker.phase === 'listing' ? '正在等待原电脑的目录回执，没有读取文件。' :
    picker.phase === 'reading' || listedAttempt?.state === 'registered' ? '已登记文件读取，尚未核实文本回执。' :
    listedAttempt?.state === 'unknown' ? '这次文件读取尚未核实，不会重新提交。' :
    listedAttempt?.state === 'pending' ? '正在登记选中的文件，还没有收到文本。' :
    canChoose && directory?.choices.length ? undefined :
    canChoose ? '原目录没有可选择的 8 KiB 以内文件。' :
    directory ? '目前不能继续选择文件；已收到的目录仍可查看。' : '尚未核对到可查看的原目录。';
  const reviewTitle = draft?.kind === 'directory' ? '核对列出目录' : draft?.kind === 'listed' ? '核对所选原文件' :
    draft?.review ? '确认从原电脑读取' : '核对原电脑文件';
  const invalidDirectory = draft?.kind === 'directory' && draft.relativeDirectory !== '' && !validWorkspaceFilePath(draft.relativeDirectory);
  const shownFolder = directory?.relativeDirectory ? <>目录 <bdi style={{ overflowWrap: 'anywhere' }}>{directory.relativeDirectory}</bdi>。 </> : null;
  return <>
    {(choice || directoryChoice) && !attempt && <div className="pw-form-actions">
      <Button onClick={() => begin(directoryChoice ? 'directory' : 'file')} disabled={disabled}>{directoryChoice ? '浏览电脑文件' : '读取一个电脑文件'}</Button>
      {modelStart && <Button variant="ghost" disabled={disabled || modelStart.disabled} onClick={() => {
        if (!currentDraft.current && !currentAttempt.current && !disabledRef.current && activeRef.current && !document.hidden &&
          canReviewDirectWorkspace(taskId, bridge.getState())) modelStart.start();
      }}>使用模型开始</Button>}
    </div>}
    {stillWaiting && sameAttempt && <p className="pw-field-hint" role="status">{sameAttempt.state === 'pending' ?
      sameAttempt.kind === 'directory' ? '正在登记列出目录…' : '正在登记这次读取…' :
      sameAttempt.state === 'registered' ? sameAttempt.kind === 'directory' ? '已登记列出目录，尚未核实电脑回执。' : '已登记这次读取，尚未核实电脑回执。请在本工作查看结果。' :
      sameAttempt.kind === 'directory' ? '是否已登记列出目录仍待核对。没有重新开始，请在原工作核对状态。' :
        '尚未核实这次读取是否登记。没有重新开始，请在原工作核对状态。'}</p>}
    {directoryStatus && <p className="pw-field-hint" role="status">{directoryStatus}</p>}
    {picker && directory && <section className="pw-operation-entry" aria-label="这次从原电脑收到的目录">
      {canChoose && directory.choices.length > 0 ? <>
        <h4>选择一个文件</h4>
        <p className="pw-muted">{shownFolder}尚未读取正文；确认后只用原连接读取选中的相对路径。</p>
        <WorkspaceListedChoices key={JSON.stringify([documentEpoch, work?.key, picker.runId, directory.operationId, directory.received.identity])}
          choices={directory.choices} disabled={disabled} onChoose={entryIndex => beginListed(picker, entryIndex)}/>
        {directory.received.truncated && <p>电脑只返回了部分目录项。</p>}
      </> : <Accordion variant="divided" className="pw-compact-disclosures">
        <AccordionItem id="workspace-picker-original-directory" headingLevel={4} title="查看当时列出的目录">
          <p className="pw-muted">{shownFolder}当时返回 {directory.received.returned} 项；这不是当前目录。</p>
          {directory.choices.length > 0 ? <ul>{directory.choices.slice(0, visibleWorkspaceFiles).map(row =>
            <li key={row.entryIndex}><bdi style={{ overflowWrap: 'anywhere' }}>{row.name}</bdi></li>)}</ul> :
            directory.received.entries.length > 0 && <ul>{directory.received.entries.map((row, index) =>
              <li key={index}><bdi style={{ overflowWrap: 'anywhere' }}>{row.name}</bdi> · {row.kind === 'file' ? '文件' : row.kind === 'directory' ? '目录' : '不可选'}</li>)}</ul>}
          {directory.choices.length ? <p>仅列出当时可选择的前 8 个文件。</p> :
            directory.received.entries.length < directory.received.returned && <p>这里只显示留存的前 8 项。</p>}
          {directory.received.truncated && <p>电脑只返回了部分目录项。</p>}
        </AccordionItem>
      </Accordion>}
    </section>}
    <SettingsDialog isOpen={!!draft && draftCurrent(draft)} onClose={back} title={reviewTitle}
      closeLabel={draft?.kind === 'file' && draft.review ? '返回修改路径' : draft?.kind === 'listed' ? '返回原目录' : '返回原工作'}>
      {draft && <section className="pw-collaboration-dialog">
        <p>电脑工作区 <bdi>{draft.workspace}</bdi></p>
        {draft.kind === 'directory' && <>
          <TextField label="相对目录（可选）" hint="留空列出根目录。也可输入 docs 或 src/components；核对时不会读取电脑。" value={draft.relativeDirectory}
            isDisabled={draft.reviewing || disabled} maxLength={2048} onChange={relativeDirectory => {
              if (currentDraft.current !== draft || draft.reviewing || disabledRef.current || relativeDirectory === draft.relativeDirectory || !draftCurrent(draft)) return;
              // Typing is not a new review or a listing. Retire only the old checked literal.
              change({ ...draft, relativeDirectory, review: undefined, error: undefined });
              retire(draft.review?.handle, draft.epoch);
            }}/>
          {invalidDirectory && <p className="pw-field-hint">请输入工作区内的相对目录，用 / 分隔，不用盘符、通配符或上级路径。</p>}
        </>}
        {draft.review ? <>
          <p ref={reviewSummary} tabIndex={-1}>{draft.kind === 'directory' ? <>
            只列出{draft.relativeDirectory ? <>目录 <bdi style={{ overflowWrap: 'anywhere' }}>{draft.relativeDirectory}</bdi></> : '这个工作区的根目录'}，不读文件正文。接下来仍须单独确认一个文件。
          </> :
            <>只读 <bdi style={{ overflowWrap: 'anywhere' }}>{draft.path}</bdi>。可完整显示 8 KiB 以内的 UTF-8 文本。{draft.kind === 'listed' && '读取确认时该路径的内容，文件可能已变化。'}</>} 不调用模型，不改动电脑文件。</p>
          <div className="pw-form-actions"><Button variant="secondary" onClick={back}>{draft.kind === 'file' ? '返回修改' : draft.kind === 'listed' ? '返回原目录' : '返回原工作'}</Button>
            <Button disabled={disabled} onClick={confirm}>{draft.kind === 'directory' ? '确认列出目录' : '确认读取一次'}</Button></div>
        </> : <>
          {draft.kind === 'file' && <><TextField label="工作区内的相对路径" hint="例如 notes/readme.txt。核对路径不会读取文件。" value={draft.path}
            isDisabled={draft.reviewing || disabled} maxLength={2048} onChange={path => {
              if (currentDraft.current === draft && !draft.reviewing && draftCurrent(draft)) change({ ...draft, path, error: undefined });
            }}/>
            {draft.path && !validWorkspaceFilePath(draft.path) && <p className="pw-field-hint">请输入一个准确的相对文件路径，用 / 分隔，不用盘符、通配符或上级路径。</p>}
          </>}
          {draft.reviewing && <p role="status">{draft.kind === 'directory' ? '正在核对要列出的目录…' : '正在核对所选工作区和路径…'}</p>}
          {draft.error && <p className="pw-error" role="alert">{draft.error}</p>}
          <div className="pw-form-actions"><Button variant="secondary" onClick={back}>{draft.kind === 'listed' ? '返回原目录' : '返回原工作'}</Button>
            {draft.kind === 'file' && <Button disabled={disabled || draft.reviewing || !validWorkspaceFilePath(draft.path)} onClick={check}>核对读取范围</Button>}
            {draft.kind === 'directory' && !draft.reviewing && <Button disabled={disabled || invalidDirectory} onClick={check}>核对目录范围</Button>}
          </div>
        </>}
      </section>}
    </SettingsDialog>
  </>;
}

export function DeviceTaskScopeWorkspace({ taskId, peers = [], run, onBackChange, onSaved, onOpenDevices, onOpenOriginal, directed, directedWorkspace, directedManual, startedPhone, canReadDispatch,
  workspaceFileActive = false, workspaceFileDisabled = false, workspaceFileModelStart }: { directedManual?:OriginalDevice; directed?: OriginalDevice; directedWorkspace?: OriginalDevice; taskId: string; peers?: Data[]; run: Run; onBackChange?: (handler: (() => boolean) | null) => void; onSaved?: () => void; onOpenDevices?: () => void; onOpenOriginal?: () => void; startedPhone?: boolean; canReadDispatch?: boolean;
  workspaceFileActive?: boolean; workspaceFileDisabled?: boolean; workspaceFileModelStart?: WorkspaceFileModelStart }) {
  const [open, setOpen] = useState(false), [view, setView] = useState<Data | null>(null), [drafts, setDrafts] = useState<Record<string, RouteDraft>>({});
  const [review, setReview] = useState<Data | null>(null), [ack, setAck] = useState(false), [removeUnavailable, setRemoveUnavailable] = useState(false);
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('');
  const [manualScope, setManualScope] = useState(false);
  const [workspaceFilePending, setWorkspaceFilePending] = useState(false);
  const mounted = useRef(true), working = useRef(false), generation = useRef(0), page = useRef(''), reviewHandle = useRef('');
  const pendingSave = useRef(false), initialDraft = useRef('');
  const browseReviewSummary = useRef<HTMLParagraphElement>(null);
  const resultBack = useRef<(() => boolean) | null>(null);
  const registerResultBack = useCallback((next: (() => boolean) | null) => { resultBack.current = next; }, []);
  const [discard, setDiscard] = useState<'close' | 'reload' | 'devices' | null>(null);
  const dirty = !!view && (JSON.stringify(drafts) !== initialDraft.current || removeUnavailable);
  const manualTarget = !directed && !directedWorkspace ? directedManual : undefined;
  const noManualRange = !!manualTarget && view?.humanInputOnly === true && !rows(view.options).length;
  const originalManualConnection = () => {
    if (!manualTarget || !onOpenDevices || document.hidden || !mounted.current) return false;
    const live = bridge.getState(), native = record(live.view);
    if (live.host?.ready !== true || bridge.getDocumentEpoch() < 1 || !native || native.section !== null || native.taskId !== taskId) return false;
    const matches = rows(live.state.tasks).filter(row => row.id === taskId), task = matches[0];
    if (matches.length !== 1 || !task || task.summaryOnly === true || task.status !== 'paused' ||
        task.raw?.taskProjection !== 'detail_window' || task.raw.taskCoreReadLimited !== false ||
        task.raw.contextAvailable === false || task.raw.contextWithdrawn === true || task.raw.sourceCurrentReadRequired === true) return false;
    const original = deviceOriginalManualPhonePreparation(task);
    return !!original && original.deadlineAt > Date.now() &&
      (['deviceId', 'instanceId', 'certificateFingerprint'] as const).every(key => original.target[key] === manualTarget[key]);
  };
  const requestExit = (next: 'close' | 'reload' | 'devices') => { if (working.current) return; if (dirty) setDiscard(next); else exit(next); };
  const exit = (next: 'close' | 'reload' | 'devices') => {
    setDiscard(null);
    if (next === 'devices' && manualTarget && (!view || noManualRange) && !originalManualConnection()) {
      setError('原手控准备已变化或到期，没有打开其他设备。'); return;
    }
    if (next === 'reload') doRead(); else { close(); if (next === 'devices') onOpenDevices?.(); }
  };
  const dismissReview = () => { const handle = reviewHandle.current; reviewHandle.current = ''; setReview(null); setAck(false); if (handle) void run('deviceTaskScope.dismissReview', { reviewHandle: handle }).catch(() => {}); };
  const close = () => {
    generation.current++; working.current = false; setBusy(false); setDiscard(null);
    if (pendingSave.current) { setNotice('范围保存结果尚未确认。请重新读取原任务的已保存范围，不要重复确认。'); pendingSave.current = false; }
    dismissReview(); const handle = page.current; page.current = ''; setOpen(false); setView(null);
    if (handle) void run('deviceTaskScope.close', { pageHandle: handle }).catch(() => {});
  };
  const liveClose = useRef(close); liveClose.current = close;
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; liveClose.current(); }; }, [taskId]);
  useEffect(() => {
    const hide = () => { if (document.visibilityState !== 'visible') liveClose.current(); };
    const owner = () => JSON.stringify([bridge.getDocumentEpoch(), bridge.getState().view]);
    let identity = owner();
    const unsubscribe = bridge.subscribe(() => { const next = owner(); if (next !== identity) { identity = next; liveClose.current(); } });
    const pageHidden = () => liveClose.current();
    document.addEventListener('visibilitychange', hide); window.addEventListener('pagehide', pageHidden);
    return () => { unsubscribe(); document.removeEventListener('visibilitychange', hide); window.removeEventListener('pagehide', pageHidden); };
  }, []);
  useEffect(() => { onBackChange?.(() => { if (!open) return resultBack.current?.() === true; if (busy) return true; if (discard) setDiscard(null); else if (review) dismissReview(); else requestExit('close'); return true; }); return () => onBackChange?.(null); }, [open, busy, review, discard, dirty, onBackChange]);
  useLayoutEffect(() => { if (review?.firstBrowse === true) browseReviewSummary.current?.focus(); }, [review]);
  useEffect(() => {
    if (!review) return;
    const timer = window.setTimeout(() => { dismissReview(); setError('这次范围审阅已到期，请重新核对。'); }, Math.max(0, Number(review.reviewExpiresAt || Date.now() + 120_000) - Date.now()));
    return () => window.clearTimeout(timer);
  }, [review]);
  const invoke = async (action: string, args: Data, accepted: (value: Data) => void) => {
    if (working.current || !mounted.current || document.visibilityState !== 'visible') return;
    const ticket = generation.current; working.current = true; setBusy(true); setError('');
    try {
      const value = await run(action, args);
      if (ticket !== generation.current || !mounted.current) {
        if (value?.pageHandle) void run('deviceTaskScope.close', { pageHandle: value.pageHandle }).catch(() => {});
        return;
      }
      accepted(value);
    } catch (failure) { if (ticket === generation.current && mounted.current) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { if (ticket === generation.current && mounted.current) { working.current = false; setBusy(false); } }
  };
  const read = () => { if (view && dirty) { setDiscard('reload'); return; } doRead(); };
  const originalTarget = directed ?? directedWorkspace ?? directedManual;
  const directedId = originalTarget?.deviceId;
  const scopeLabel = directedWorkspace ? '所选电脑的只读范围' : directedManual ? '这部原手机的屏幕范围' : '所选手机的可用范围';
  const doRead = () => {
    if (working.current) return;
    dismissReview(); setOpen(true);
    void invoke('deviceTaskScope.open', { taskId }, value => {
      if (!value || value.taskId !== taskId || typeof value.pageHandle !== 'string' || !Array.isArray(value.options)) throw new Error('没有收到本任务的设备范围。');
      const pinned = directedWorkspace ? value.directWorkspace : directedManual ? value.manualPhoneTarget : value.directPhone;
      if (originalTarget && (directedManual && (value.humanInputOnly!==true || !rows(value.unavailableOptions).every(item=>item.targetDeviceId===directedManual.deviceId)) || !(['deviceId', 'instanceId', 'certificateFingerprint'] as const).every(key => pinned?.[key] === originalTarget[key])
        || !value.options.every((option: Data) => option && option.targetDeviceId === originalTarget.deviceId
          && option.targetInstanceId === originalTarget.instanceId && !rows(option.downstreamOptions).length
          && (!directedManual || option.kind==='tool_session' && ['main_screen','background'].includes(option.resourceBinding?.screenMode)
            && option.resourceBinding?.allowWholeDisplayCapture===true && option.resourceBinding?.allowWholeDisplayInput===true)
          && (!directedWorkspace || option.kind === 'tool_session' && option.capabilityVersion === 'workspace-v1'
            && rows(option.toolDeclarations).length > 0 && rows(option.toolDeclarations).every(tool => tool.effect === 'read' && ['workspace.list', 'workspace.read'].includes(tool.toolName)))))) {
        page.current = ''; setView(null); void run('deviceTaskScope.close', { pageHandle: value.pageHandle }).catch(() => {});
        throw new Error('读取的范围不属于准备时选定的原设备与能力，没有改用其他设备。');
      }
      page.current = value.pageHandle; const next = Object.fromEntries(rows(value.options).map(option => [option.optionId, routeDraft(option, value)])); initialDraft.current = JSON.stringify(next); setView(value); setDrafts(next); setRemoveUnavailable(false); setManualScope(false);
    });
  };
  const acceptReview = (value: Data, firstBrowse = false) => {
    if (value?.pageHandle !== page.current || value?.taskId !== taskId || !value.reviewHandle) throw new Error('原设备范围审阅不完整，请重新读取。');
    reviewHandle.current = value.reviewHandle; setReview({ ...value, firstBrowse }); setAck(false);
  };
  // An empty first choice only saves an unusable policy. Explicitly clearing a saved scope remains available.
  const firstDirectedScope = !!view && !!originalTarget && !directedManual && (!!directed !== !!directedWorkspace) &&
    !!deviceChoiceCode(originalTarget.deviceId) && /^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(originalTarget.instanceId) && /^[a-f0-9]{64}$/.test(originalTarget.certificateFingerprint) &&
    !!page.current && view.pageHandle === page.current && view.taskId === taskId && view.humanInputOnly === false && view.executionAuthorized === false &&
    view.runId === '' && view.executorEpoch === 0 && view.sourceScopeRevision === 0 && !Object.hasOwn(view, 'selected') &&
    (['deviceId', 'instanceId', 'certificateFingerprint'] as const).every(key =>
      (directedWorkspace ? view.directWorkspace : view.directPhone)?.[key] === originalTarget[key]);
  const options = rows(view?.options), chosenRange = options.some(option => drafts[option.optionId]?.selected === true);
  const unavailable = rows(view?.selected?.routes).filter(old => !options.some(option => option.optionId === old.optionId));
  const prepare = () => {
    if (!view || unavailable.length && !removeUnavailable) return;
    try {
      const routes = scopeSelection(view, drafts);
      if ((firstDirectedScope && !routes.length) || (manualTarget && view.humanInputOnly === true && routes.length !== 1)) return;
      void invoke('deviceTaskScope.review', { pageHandle: page.current, routes }, acceptReview);
    }
    catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); }
  };
  const confirm = () => {
    if (!review || !ack || working.current) return;
    const captured = review; reviewHandle.current = ''; setReview(null); setAck(false); pendingSave.current = true;
    void invoke(captured.kind === 'import' ? 'deviceTaskScope.confirmImport' : 'deviceTaskScope.confirm', { pageHandle: page.current, reviewHandle: captured.reviewHandle, acknowledged: true }, value => {
      if (value?.saved !== true || value?.taskId !== taskId || value?.reviewHandle !== captured.reviewHandle) throw new Error('保存回执尚未匹配，请重新读取已保存范围。');
      pendingSave.current = false; setNotice(captured.kind === 'import' ? '已保存这份原项目包的导入确认；实际导入仍由原任务执行。' :
        captured.firstBrowse ? '已保存所选工作区。请在原工作选择「浏览电脑文件」；还没有列目录或读取文件。' :
        '设备范围已保存。尚未开始或重启任务；请在原任务明确开始或继续。'); setView(null); close(); onSaved?.();
    }).finally(() => {
      if (pendingSave.current && mounted.current) { pendingSave.current = false; setNotice('保存结果尚未确认。请重新读取已保存范围，没有自动重做。'); setView(null); }
    });
  };
  const ownerNames=rows(view?.ownerDisplayNames);
  const peerIds = [...new Set(options.map(option => option.targetDeviceId))];
  const noFirstRange = firstDirectedScope && !peerIds.length;
  const manualConnection = open && !busy && !review && (!view || noManualRange) && originalManualConnection();
  const savedWorkspace = view?.directWorkspace;
  const workspaceTarget = directedWorkspace && !directed && !directedManual && view?.taskId === taskId &&
    !!page.current && view.pageHandle === page.current && view.executionAuthorized === false &&
    (['deviceId', 'instanceId', 'certificateFingerprint'] as const).every(key => savedWorkspace?.[key] === directedWorkspace[key]) ? directedWorkspace : undefined;
  const reviewedWorkspace = review?.kind === 'scope' && review.taskId === taskId && review.pageHandle === page.current && review.executionAuthorized === false ? workspaceTarget : undefined;
  const workspaceNotice = '这里选择的是电脑授权的工作区，不是目标中提到的单个文件。';
  const groupName=(id:string) => {
    const labels=new Set(rows(view?.options).filter(option=>option.targetDeviceId===id).map(option=>routeName(option,ownerNames)));
    return labels.size===1 && !labels.has('') ? [...labels][0] : deviceName(id,peers);
  };
  const showDispatch = !open && startedPhone && directed && onOpenOriginal && !directedWorkspace && !directedManual;
  // A pinned Windows connection record does not depend on whether this task can still edit its scope.
  const openWindowsConnection = directedWorkspace && !directed && !directedManual ? onOpenDevices : undefined;
  const workspaceFiles = directedWorkspace && !directed && !directedManual;
  const workspaceSnapshot = bridge.getState();
  const workspaceWork = workspaceFiles && workspaceFileActive ? originalWorkspaceFileWork(workspaceSnapshot, taskId) : undefined;
  const browseChoices = (snapshot: PocketSnapshot, now = Date.now()) => {
    if (!view || !open || !workspaceTarget || !workspaceFileActive || workspaceFileDisabled || workspaceFilePending ||
      !firstDirectedScope || manualScope || dirty || chosenRange || error || pendingSave.current || reviewHandle.current ||
      view.pageHandle !== page.current || view.contextMode !== 'goal_only' || view.budgetReserved !== false ||
      !Number.isSafeInteger(view.toolsAvailable) || view.toolsAvailable < 4 ||
      !Number.isSafeInteger(view.deadlineMaximum) || view.deadlineMaximum <= now ||
      !workspacePickerCommands(snapshot) || !snapshot.capabilities.commands.includes('deviceTaskScope.review') ||
      !snapshot.capabilities.commands.includes('deviceTaskScope.confirm')) return [];
    const work = originalWorkspaceFileWork(snapshot, taskId), execution = record(work?.raw.executionScope);
    if (!work || !workspaceFileUnstarted(work) || work.raw.deviceSourceScope != null ||
      work.original.deviceId !== workspaceTarget.deviceId || work.original.instanceId !== workspaceTarget.instanceId ||
      work.original.certificateFingerprint !== workspaceTarget.certificateFingerprint ||
      !execution || !Number.isSafeInteger(view.scopeRevision) || view.scopeRevision < 1 ||
      execution.revision !== view.scopeRevision || execution.executionPhase === 'planning' ||
      !Number.isSafeInteger(execution.toolLimit) || !Number.isSafeInteger(execution.toolsUsed) ||
      execution.toolLimit - execution.toolsUsed < 4) return [];
    return options.flatMap(option => {
      const facts = workspaceReadFacts(option, workspaceTarget), draft = drafts[option.optionId];
      const deadline = Number(draft?.deadline);
      return facts?.readsFile && facts.listsDirectory && option.executionAuthorized === false &&
        typeof option.optionId === 'string' && /^option_[a-f0-9]{64}$/.test(option.optionId) &&
        typeof option.sourceKey === 'string' && /^route_[a-f0-9]{64}$/.test(option.sourceKey) &&
        typeof option.grantId === 'string' && !!option.grantId && Number.isSafeInteger(option.authorizationEpoch) && option.authorizationEpoch > 0 &&
        Number.isSafeInteger(option.toolBudgetMaximum) && option.toolBudgetMaximum >= 2 && option.modelBudgetMaximum === 0 &&
        emptyRows(option.downstreamOptions) && Number.isSafeInteger(option.deadlineMaximum) &&
        Number.isSafeInteger(deadline) && now < deadline && deadline <= Math.min(view.deadlineMaximum, option.deadlineMaximum) &&
        draft?.selected === false && options.filter(other => other.optionId === option.optionId ||
          other.resourceBinding?.environmentId === facts.id).length === 1 ? [{ option, facts }] : [];
    });
  };
  const quickBrowseChoices = !review ? browseChoices(workspaceSnapshot) : [];
  const quickBrowse = quickBrowseChoices.length > 0;
  const prepareBrowse = (option: Data) => {
    if (!view || working.current || !mounted.current || document.visibilityState !== 'visible') return;
    const now = Date.now(), candidate = browseChoices(bridge.getState(), now).find(row => row.option === option);
    if (!candidate) { setError('当前工作区范围已经变化，请重新核对。'); return; }
    const proposal = { ...drafts, [option.optionId]: { ...drafts[option.optionId], selected: true,
      tools: '2', models: '0', localTools: '2', localModels: '0' } };
    try {
      // Translate the clicked row's immutable draft now. React state is only the preview/editor, not the review input.
      const routes = scopeSelection(view, proposal, now), choice = routes[0];
      if (routes.length !== 1 || choice.optionId !== option.optionId || choice.toolBudget !== 2 || choice.localToolShare !== 2 ||
        choice.modelBudget !== 0 || choice.localModelShare !== 0 || !emptyRows(choice.shares) ||
        !emptyRows(choice.files) || !emptyRows(choice.projects)) throw new Error('没有得到一个准确的工作区选择。');
      void invoke('deviceTaskScope.review', { pageHandle: page.current, routes }, value => {
        const route = rows(value?.routes)[0], facts = route && workspaceReadFacts(route, workspaceTarget);
        if (value?.kind !== 'scope' || value.executionAuthorized !== false || value.budgetReserved !== false ||
          value.contextMode !== 'goal_only' || value.goal !== view.goal || value.revision !== 1 || value.parentScopeRevision !== view.scopeRevision ||
          !uuid(value.reviewHandle) || !Number.isSafeInteger(value.reviewExpiresAt) || value.reviewExpiresAt <= Date.now() ||
          !Array.isArray(value.routes) || value.routes.length !== 1 || !facts?.listsDirectory || !facts.readsFile ||
          facts.id !== candidate.facts.id || route.resourceBinding.binding !== option.resourceBinding.binding ||
          route.optionId !== option.optionId || route.sourceKey !== option.sourceKey || route.grantId !== option.grantId ||
          route.authorizationEpoch !== option.authorizationEpoch || !digest(route.grantFingerprint) || route.deadline !== choice.deadline ||
          route.contextMode !== 'goal_only' || route.toolBudget !== 2 || route.localToolShare !== 2 || route.modelBudget !== 0 ||
          route.localModelShare !== 0 || !emptyRows(route.downstreamShares) || !emptyRows(route.files) || !emptyRows(route.projects))
          throw new Error('审阅与所选的原电脑工作区不符，未保存。请重新核对。');
        acceptReview(value, true); setDrafts(proposal);
      });
    } catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); }
  };
  const workspaceFileReady = workspaceWork && canReviewDirectWorkspace(taskId, workspaceSnapshot);
  const workspaceFileRun = !!workspaceWork && workspaceWork.raw.ownerWindowsWorkspaceRead === true &&
    uuid(workspaceWork.raw.runId) && workspaceWork.raw.coordinatorEpoch === 1;
  const devicesLabel = openWindowsConnection ? '核对原电脑连接记录' : directedId ? '查看这份工作的原设备连接' : '管理设备与连接';
  const manualConnectionLabel = '查看这部原手机的连接记录';
  const scopeReadNote = directedId ? '点击可向原设备核对范围，只用已有的连接。' : '点击可向已有连接的候选设备核对范围。';
  return <section className="pw-task-capabilities" aria-label="本任务的远端设备范围">
    {showDispatch && <OriginalPhoneDispatchStatus key={`${taskId}:${directed.deviceId}:${directed.instanceId}:${directed.certificateFingerprint}`} source={{taskId, ...directed}} canRead={canReadDispatch === true} onOpen={onOpenOriginal} registerResultBack={registerResultBack} />}
    {workspaceFiles && <WorkspaceFileRead taskId={taskId} active={workspaceFileActive && !open} disabled={workspaceFileDisabled || busy}
      modelStart={workspaceFileModelStart} registerBack={registerResultBack} onPending={setWorkspaceFilePending}/>}
    {!workspaceFileRun && <Button variant={showDispatch || workspaceFileReady ? 'ghost' : 'secondary'} onClick={read} disabled={busy || workspaceFilePending}>{directedId ? '核对' + scopeLabel : '核对已连接设备的范围'}</Button>}
    {!open && openWindowsConnection && <Button variant="ghost" disabled={busy || workspaceFilePending} onClick={openWindowsConnection}>{devicesLabel}</Button>}
    {!startedPhone && directedId && !open && onOpenOriginal && <Button variant="ghost" disabled={busy} onClick={onOpenOriginal}>查看原派单与结果</Button>}
    {!open && !workspaceFileRun && <p>{directedId ? `固定目标：${deviceName(directedId, peers)}。` : ''}{openWindowsConnection ? '核对范围只使用原连接；核对连接记录只读本机。' : scopeReadNote}{showDispatch?'核对范围与读取原派单是两件事。':'不会自动连接、读取画面或派发。'}</p>}
    {!open && !workspaceFileReady && workspaceWork && workspaceFileUnstarted(workspaceWork) &&
      (workspaceFileCommands(workspaceSnapshot) || workspacePickerCommands(workspaceSnapshot)) &&
      <p className="pw-field-hint">{workspacePickerCommands(workspaceSnapshot) ?
        '想不用模型浏览文件？先核对原电脑已有的只读工作区。保存范围不会读取文件。' :
        '想不经模型读取一个文本？先保存一个允许读取文件的原电脑工作区，并将对端工具份额设为 1，另留 1 次本机派发。'}</p>}
    {notice && <p role="status">{notice}</p>}
    <SettingsDialog isOpen={open} onClose={() => requestExit('close')} title={review ? review.kind === 'import' ? '核对原项目包导入' : review.firstBrowse ? '核对要浏览的工作区' : '核对本任务设备范围' : quickBrowse ? '选择电脑工作区' : directedId ? '核对' + scopeLabel : view?.humanInputOnly ? '选择一个手控手机屏幕' : '选择远端手机或电脑'}>
      <div className="pw-collaboration-dialog">
        {busy && <p role="status">正在读取原设备范围回执…</p>}
        {error && <p role="alert">{error}</p>}
        {!review && !view && !busy && <><p>{notice || '核对允许的范围可读取已有连接的能力说明，不会发送目标或启动模型。'}</p><div className="pw-form-actions">
          {manualConnection && <Button onClick={() => requestExit('devices')}>{manualConnectionLabel}</Button>}
          <Button variant={manualConnection ? 'ghost' : undefined} onClick={read}>重新核对设备范围</Button>
          {openWindowsConnection && <Button variant="ghost" onClick={() => requestExit('devices')}>{devicesLabel}</Button>}
        </div></>}
        {view && !review && <>
          <h3>{view.goal}</h3>
          {quickBrowse && <>
            <p>{groupName(quickBrowseChoices[0].option.targetDeviceId)} · 选择一个已授权的工作区。保存范围后，回原工作再确认列目录和打开哪个文件。</p>
            <div className="pw-form-actions" role="group" aria-label="原电脑的只读工作区">
              {quickBrowseChoices.map(({ option, facts }) => <Button key={option.optionId} variant={quickBrowseChoices.length === 1 ? 'primary' : 'secondary'}
                disabled={busy} onClick={() => prepareBrowse(option)}>准备浏览 · <bdi style={{overflowWrap:'anywhere'}}>{facts.id}</bdi></Button>)}
            </div>
          </>}
          {!quickBrowse && <>
          <p>{view.humanInputOnly ? '只选择一个手机屏幕。需要对端主人已明确授予全屏捕获、输入及准确应用范围。你逐次操作，不调用模型，不发送材料或转派。' : '只选择这项原任务可以用的设备。工具份额来自本任务剩余预算，设备已配对不等于已授权。默认不发送文件、项目或私人记忆。'}</p>
          {options.some(option => workspaceReadFacts(option, workspaceTarget)) && <p className="pw-field-hint">{workspaceNotice}</p>}
          <dl className="pw-facts"><div><dt>当前可分配</dt><dd>工具 {view.toolsAvailable} 次 · 模型 {view.modelsAvailable} 次</dd></div><div><dt>原任务最晚截止</dt><dd>{time(view.deadlineMaximum)}</dd></div></dl>
          <p>{view.humanInputOnly ? '每次对端操作同时占用一次本机派发额度；对端份额至少 2 次，最多分配当前总额的一半。每次刷新也占用调用。' : '发起设备操作本身还至少占用 1 次本机工具调用；不要把全部剩余额度分给对端。'}已执行和未知结果的占用不会因改范围退回。</p>
          {unavailable.length > 0 && <><p role="alert">有 {unavailable.length} 项已保存范围当前未能读取。可能是离线、授权到期或资源变化，未替换成其他设备。</p>{!noManualRange && <CheckboxField isSelected={removeUnavailable} isDisabled={busy} onChange={setRemoveUnavailable}>这次保存明确移除当前不可读取的旧选择（不会撤回已发生的操作）</CheckboxField>}</>}
          {peerIds.map(id => <section key={id}><h3>{groupName(id)}</h3><Accordion>
            {rows(view.options).filter(option => option.targetDeviceId === id).map(option => {
              const draft = drafts[option.optionId]; if (!draft) return null;
              const update = (next: RouteDraft) => { dismissReview(); setDrafts(old => ({ ...(view.humanInputOnly && next.selected ? Object.fromEntries(Object.entries(old).map(([id, value]) => [id, { ...value, selected: false }])) : old), [option.optionId]: next })); };
              const shareCount = Object.values(draft.shares).filter(row => row.selected).length;
              const workspace = workspaceReadFacts(option, workspaceTarget);
              return <AccordionItem key={option.optionId} id={option.optionId} title={workspace ? <>只读工作区 <bdi style={{overflowWrap:'anywhere'}}>{workspace.id}</bdi></> : routeTitle(option, view.humanInputOnly)}
                description={[workspace ? workspace.actions : routeUseSummary(option, view.humanInputOnly), draft.selected ? '已选择 · 尚未执行' : ''].filter(Boolean).join(' · ') || '未选择'}>
                <CheckboxField isSelected={draft.selected} isDisabled={busy} onChange={selected => update({ ...draft, selected })}>将这个准确范围用于本任务</CheckboxField>
                <ScopeFacts value={option} peers={peers} names={ownerNames} human={view.humanInputOnly} workspace={workspace} />
                {draft.selected && <>
                  {!!draft.missingShares.length && <><p role="alert">有 {draft.missingShares.length} 项原下游范围当前无法核对，未自动替换或移除。</p><Button variant="secondary" disabled={busy} onClick={() => update({ ...draft, missingShares: [] })}>明确移除这些旧下游选择</Button></>}
                  {phoneTextOnly(option) ? <p>工具 0 次，不使用屏幕。</p> : <TextField label={'工具总份额（最多 ' + option.toolBudgetMaximum + ' 次）'} value={draft.tools} inputMode="numeric" isDisabled={busy} onChange={tools => update({ ...draft, tools })} />}
                  {workspaceFiles && workspaceFileActive && workspaceFileCommands(bridge.getState()) && view.runId === '' && view.executorEpoch === 0 &&
                    workspace?.readsFile && Number(option.toolBudgetMaximum) >= 1 && <>
                      {draft.tools === '1' ? <p className="pw-field-hint">只保留这一个工作区与一次工具份额，确认保存范围后即可在原工作直接读取一个文本，不用模型。</p> :
                        <Button variant="ghost" disabled={busy} onClick={() => update({ ...draft, tools: '1' })}>单文件读取：设为 1 次</Button>}
                      {workspace.listsDirectory && workspacePickerCommands(workspaceSnapshot) && Number(option.toolBudgetMaximum) >= 2 &&
                        Number(view.toolsAvailable) >= 4 && (draft.tools === '2' ?
                          <p className="pw-field-hint">只保留这一个工作区，保存后在原工作先核对目录，再选一个文件；不自动开始。另留 2 次本机派发。</p> :
                          <Button variant="ghost" disabled={busy} onClick={() => update({ ...draft, tools: '2' })}>先列目录再选文件：设为 2 次</Button>)}
                    </>}
                  {option.kind === 'goal' && <TextField label={'模型总份额（最多 ' + option.modelBudgetMaximum + ' 次）'} value={draft.models} inputMode="numeric" isDisabled={busy} onChange={models => update({ ...draft, models })} />}
                  <p>本次截止：{time(draft.deadline)}</p><div className="pw-form-actions">
                    {(view.humanInputOnly ? [1,5] : phoneTextOnly(option) ? [3,10] : [15,60]).map(minutes => <Button key={minutes} variant="ghost" disabled={busy} onClick={() => update({ ...draft, deadline: String(Math.min(Date.now() + minutes * 60_000, Number(view.deadlineMaximum), Number(option.deadlineMaximum))) })}>从现在起 {minutes} 分钟内</Button>)}
                  </div>
                  {view.humanInputOnly ? <p>只接收你在原手控页的每次明确操作，不启动代理；换设备或屏幕必须重新准备和审阅。画面仅在本页短时显示，不自动刷新，不是实时视频。</p> : option.kind === 'goal' ? <p>只将本次目标交给对端已授权模型；对端仍核对本机策略，不继承本机账号或私人记忆。</p> : workspace && workspaceFiles ? <p>保存范围不会开始读取。你可以回原工作选择已可用的只读操作，或明确使用模型开始。</p> :
                    <p>由本任务模型调用对端明确工具。手机主屏与独立屏不互相兜底；电脑工具也不依赖 Codex 或 Claude 安装。</p>}
                  <Accordion>
                    <RouteMaterials kind="files" option={option} view={view} draft={draft} disabled={busy} update={update} />
                    <RouteMaterials kind="projects" option={option} view={view} draft={draft} disabled={busy} update={update} />
                    {rows(option.downstreamOptions).length > 0 && <AccordionItem id="shares" title="继续转交到其他设备" description={shareCount ? '已选 ' + shareCount + ' 个准确范围' : '默认不转派'}>
                      <p>对端本机与每个下游使用互不重复的份额，总和不得超过上方总份额。</p>
                      {rows(option.downstreamOptions).map(item => { const chosen = draft.shares[item.optionId]; return <div key={item.optionId}>
                        <CheckboxField isSelected={chosen?.selected || false} isDisabled={busy} onChange={selected => update({ ...draft, shares: { ...draft.shares, [item.optionId]: { ...chosen, selected } } })}>{deviceName(item.targetDeviceId, peers)} · {JSON.stringify(item.resourceBinding)}</CheckboxField>
                        {chosen?.selected && <><TextField label="此下游工具份额" value={chosen.tools} inputMode="numeric" isDisabled={busy} onChange={tools => update({ ...draft, shares: { ...draft.shares, [item.optionId]: { ...chosen, tools } } })} /><TextField label="此下游模型份额" value={chosen.models} inputMode="numeric" isDisabled={busy} onChange={models => update({ ...draft, shares: { ...draft.shares, [item.optionId]: { ...chosen, models } } })} /></>}
                      </div>; })}
                      {shareCount > 0 && <><TextField label="留给对端本机的工具份额" value={draft.localTools} inputMode="numeric" isDisabled={busy} onChange={localTools => update({ ...draft, localTools })} /><TextField label="留给对端本机的模型份额" value={draft.localModels} inputMode="numeric" isDisabled={busy} onChange={localModels => update({ ...draft, localModels })} /></>}
                    </AccordionItem>}
                  </Accordion>
                </>}
              </AccordionItem>;
            })}
          </Accordion></section>)}
          </>}
          {rows(view.unavailableOptions).length > 0 && <Accordion><AccordionItem id="unavailable" title={`有 ${rows(view.unavailableOptions).length} 项范围暂不可用`}><p>这些范围没有替换成其他设备，也不阻止你选择已核对的设备。</p>{rows(view.unavailableOptions).map((item,index) => <p key={index}>{deviceName(item.targetDeviceId, peers)}：{item.reason === 'grant_unavailable' ? '原授权到期、撤销或无法核对' : item.reason === 'capability_read_unavailable' ? '连接或能力读取未完成，请核对对端接收服务与当前网络' : '设备身份或能力时效不匹配，需要重新读取'}</p>)}</AccordionItem></Accordion>}
          {!peerIds.length && <p>{view.manualPhoneTarget ? '未核对到这部原手机已授权的手控屏幕。不改用别台手机；可查看原连接与两端范围。' : view.directWorkspace ? '这次没有核对到原电脑可用的只读工作区。可核对原连接与已有范围，不会改用其他设备。' : view.directPhone ? '未读取到这部原手机可用的直接范围。请检查对端连接及已有授权；不会改投其他设备或继续转派。' : view.humanInputOnly ? '没有可手控的手机范围。请先连接对端，并由对端主人授权主屏或独立屏、准确应用、全屏捕获与输入，再重新读取。不会改用电脑或模型代理。' : '未读取到可选择的设备范围。请在设备页连接对端，并由对端明确授权工具或目标范围，再回来重新读取。'}</p>}
          {view.hasMoreOptions && <p>还有未读取的授权记录。未显示的设备不会自动加入本任务。</p>}
          {!quickBrowse && firstDirectedScope && !noFirstRange && !chosenRange && <p>先选择一个已核对的原设备范围，再审阅。</p>}
          <div className="pw-form-actions">
            {quickBrowse && <Button variant="ghost" disabled={busy} onClick={() => setManualScope(true)}>手动设置范围</Button>}
            {!quickBrowse && !noFirstRange && !noManualRange && <Button disabled={busy || unavailable.length > 0 && !removeUnavailable || (firstDirectedScope || !!manualTarget) && !chosenRange} onClick={prepare}>{firstDirectedScope ? '核对所选范围' : '核对这次范围修改'}</Button>}
            {noFirstRange && onOpenDevices && <Button disabled={busy} onClick={() => requestExit('devices')}>{devicesLabel}</Button>}
            {manualConnection && <Button onClick={() => requestExit('devices')}>{manualConnectionLabel}</Button>}
            <Button variant={(noFirstRange && !onOpenDevices) || (noManualRange && !manualConnection) ? 'secondary' : 'ghost'} disabled={busy} onClick={read}>重新核对设备范围</Button>
            {!noFirstRange && !noManualRange && onOpenDevices && <Button variant="ghost" disabled={busy} onClick={() => requestExit('devices')}>{devicesLabel}</Button>}
          </div>
          {rows(view.pendingProjectImports).map(item => <div key={item.operationId || item.receiveOperationId}><p>原项目包等待独立导入确认</p><Button variant="secondary" disabled={busy} onClick={() => { void invoke('deviceTaskScope.reviewImport', { pageHandle: page.current, operationId: item.receiveOperationId || item.operationId }, acceptReview); }}>核对这份原项目包</Button></div>)}
        </>}
        {review && <>
          <h3>{review.goal || view?.goal}</h3><p>{review.firstBrowse ? '保存只读范围不会读取文件、启动任务或扩大电脑授权。' : review.kind === 'scope' ? '下面是原生解析后的准确选择。保存不发送目标、不开始任务，不为对端扩大授权。' : '确认只允许原任务导入这份已经接收的项目包；不会立即执行、覆盖已有项目或创建第二项任务。'}</p>
          {review.firstBrowse ? <p ref={browseReviewSummary} tabIndex={-1}>回到原工作后，可以选择不使用模型浏览：先确认列一次目录，再单独确认读取至多一个小文本。</p> :
            rows(review.routes).some(route => workspaceReadFacts(route, reviewedWorkspace)) && <p className="pw-field-hint">{workspaceNotice}</p>}
          {rows(review.routes).map(route => <div key={route.sourceKey}><ScopeFacts value={route} peers={peers} names={ownerNames} human={view?.humanInputOnly} workspace={workspaceReadFacts(route, reviewedWorkspace)} firstBrowse={review.firstBrowse} />
            <p>{review.firstBrowse ? '不分享文件、项目或私人记忆。' : '上下文：仅本次目标（goal_only）；私人记忆与整份工作区不提供。'}</p>
            {['files','projects'].map(kind => rows(route[kind]).map(item => <p key={kind + item.id}>{kind === 'files' ? '原件' : '项目包'}：{item.name || item.id} → {item.path} · {item.size} 字节 · {item.expectedVersion === 'absent' ? '仅新建' : '覆盖已核对版本 ' + item.expectedVersion}<br />SHA-256：{item.sha256}</p>))}
            {rows(route.downstreamShares).length > 0 && <><p>对端本机：工具 {route.localToolShare} 次 · 模型 {route.localModelShare} 次</p>{rows(route.downstreamShares).map((item,index) => <p key={index}>下游 {deviceName(item.targetDeviceId, peers)} · {JSON.stringify(item.resourceBinding)} · 工具 {item.toolBudget} 次 · 模型 {item.modelBudget} 次</p>)}</>}
          </div>)}
          {review.kind === 'scope' && rows(review.routes).length === 0 && <p role="alert">本次将清空本任务的设备选择；不会取消已经发生的对端操作。</p>}
          {review.kind === 'import' && <><dl className="pw-facts"><div><dt>接收设备</dt><dd>{deviceName(review.targetDeviceId || '', peers)}</dd></div><div><dt>原项目包</dt><dd>{review.name || review.artifactId}</dd></div><div><dt>新副本路径</dt><dd>{review.path}</dd></div><div><dt>内容范围</dt><dd>{review.summary?.fileCount} 个文件 · {review.summary?.directoryCount} 个目录 · {review.summary?.bytes} 字节</dd></div></dl><p>仅导入新的副本，不覆盖现有项目，不恢复项目历史、运行环境或执行权限。确认后仍由原任务执行。</p><Accordion><AccordionItem id="import-exact" title="完整项目清单与原接收回执"><pre style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere'}}>{JSON.stringify(review, null, 2)}</pre></AccordionItem></Accordion></>}
          <CheckboxField isSelected={ack} isDisabled={busy} onChange={setAck}>{review.kind === 'import' ? '我已核对接收设备、原项目包、准确的新副本路径与完整清单' : review.firstBrowse ? '我已核对原电脑、工作区、读取范围和截止时间' : '我已核对接收设备、目标、准确资源、预算期限，以及上方明确选择的文件和项目路径'}</CheckboxField>
          <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={dismissReview}>返回修改</Button><Button disabled={busy || !ack} onClick={confirm}>确认保存一次</Button></div>
        </>}
        {!review && <Button variant="ghost" disabled={busy} onClick={() => requestExit('close')}>返回原任务</Button>}
      </div>
    </SettingsDialog>
    <SettingsDialog isOpen={!!discard} title="保留还是放弃当前选择？" onClose={() => setDiscard(null)}>
      <div className="pw-collaboration-dialog"><p>当前设备、份额或文件路径有未保存修改。返回编辑可以继续核对；放弃不会更改已经保存的原任务范围。</p><div className="pw-form-actions"><Button variant="secondary" onClick={() => setDiscard(null)}>保留并继续编辑</Button><Button onClick={() => { if (discard) exit(discard); }}>放弃这些修改{discard === 'reload' ? '并重新读取' : ''}</Button></div></div>
    </SettingsDialog>
  </section>;
}
