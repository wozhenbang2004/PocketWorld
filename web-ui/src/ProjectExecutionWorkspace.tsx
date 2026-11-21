import { useEffect, useRef, useState } from 'react';
import { Accordion, AccordionItem, Button, CheckboxField, SelectField, SettingsDialog, TextAreaField, TextField } from './forms-library';
import TerminalSurface from './TerminalSurface';
import {ProjectTermuxOriginalReceipt} from './ProjectTermuxOriginalReceipt';
import {ProjectExecutionExitPanel} from './ProjectExecutionExitPanel';
import {originalExecutionExit,type ExecutionExit} from './project-execution-exit';
import { bridge } from './bridge';
import { registerOverlayBack } from './overlay-back';
import { bareManagedNodeCheck, currentManagedNodeOperation, directNodeLogPin, nodeCompilerOutputReply, originalDirectNodeExecution, originalFirstBuildFailure, originalNodeCompilerOutput, projectExecutionActivity, projectExecutionCanInput, projectExecutionRows, projectExecutionShouldRead, sameDirectNodeExecution, sameManagedNodeOperation, sameNodeCompilerOutput, type BareManagedNodeCheck, type DirectNodeExecutionPin, type NodeCompilerOutputReply, type OriginalManagedNodePending, type OriginalNodeCompilerOutput, type ProjectExecutionData as Data } from './project-execution';
import './local-workspace.css';
import './project-workspace-manga.css';

type Run = (action: string, args?: Data) => Promise<any>;
type ExecutionView = Data & {checkedPending?:BareManagedNodeCheck};
const ownerWindow = () => { const view = bridge.getState().view as Data; return [bridge.getDocumentEpoch(), view.taskId, view.section, view.resumeRevision].join('|'); };
const drafts = new Map<string, string>(), stopped = new Set<string>();
const keys = [{ id: '\r', label: 'Enter' }, { id: '\t', label: 'Tab' }, { id: '\u001b', label: 'Esc' }, { id: '\u001b[A', label: '↑' }, { id: '\u001b[B', label: '↓' }, { id: '\u001b[C', label: '→' }, { id: '\u001b[D', label: '←' }];

const reviewCompilerOutput = 'projects.nodeCompilerOutput.review', saveCompilerOutput = 'projects.nodeCompilerOutput.save';
const checkDirectBuildReceipt = 'projects.nodeBuild.checkReceipt';
function compilerOutputWindow(taskId: string, native = bridge.getState()) {
  const view = native.view as Data;
  return !document.hidden && view?.taskId === taskId && view.section == null && native.state.filePath == null &&
    native.state.attachmentWorkspace?.open !== true && [reviewCompilerOutput,saveCompilerOutput].every(name => native.capabilities.commands.includes(name))
    ? JSON.stringify([bridge.getDocumentEpoch(),taskId,view.resumeRevision]) : '';
}
type CompilerOutputState = NodeCompilerOutputReply | {state: 'unconfirmed'};

/** One affirmative save for the only file witnessed at this build's native
 * exit. It never needs the log reader, another task window, or a path argument. */
function NodeCompilerOutput({original, operation, owner}: {
  original: OriginalNodeCompilerOutput; operation: Data; owner: string;
}) {
  const [reply, setReply] = useState<CompilerOutputState | null>(null);
  const [busy, setBusy] = useState(''), [note, setNote] = useState('');
  const region = useRef<HTMLElement>(null), active = useRef(false), flight = useRef<object|null>(null);
  const currentReply = useRef<CompilerOutputState|null>(null);
  const show = (next: CompilerOutputState|null) => { currentReply.current = next; setReply(next); };
  useEffect(() => {
    active.current = true;
    return () => { active.current = false; flight.current = null; };
  }, []);
  const current = () => {
    const host = bridge.getState(), surface = region.current;
    const task = (Array.isArray(host.state.tasks) ? host.state.tasks as Data[] : []).find(task => task.id === original.taskId);
    return active.current && !!surface?.isConnected && !!surface.getClientRects().length &&
      !surface.closest('[hidden],[inert],[aria-hidden="true"]') && compilerOutputWindow(original.taskId,host) === owner &&
      sameNodeCompilerOutput(original, originalNodeCompilerOutput(task,operation));
  };
  async function request(saving: boolean) {
    if (!current() || flight.current) return;
    const previous = currentReply.current;
    if (saving ? previous?.state !== 'ready' : previous?.state === 'ready' || previous?.state === 'saved') return;
    if (saving && previous?.state === 'ready' && previous.expiresAt <= Date.now()) {
      show(null); setNote('这次核对已过期，没有提交保存。请重新核对同一文件。'); return;
    }
    const action = saving ? saveCompilerOutput : reviewCompilerOutput, token = {};
    const accepts = () => flight.current === token && current();
    flight.current = token; setBusy(action); setNote('');
    // Consume this confirmation on dispatch, including when the reply is lost.
    if (saving) show({state: 'unconfirmed'});
    try {
      const {taskId,runId,epoch,buildOperationId} = original;
      const value = await bridge.command(action, {taskId,runId,epoch,buildOperationId,
        ...(saving && previous?.state === 'ready' ? {reviewHandle:previous.reviewHandle} : {})});
      if (!accepts()) return;
      const next = nodeCompilerOutputReply(value, original);
      if (!next || (saving && next.state !== 'saved') || (previous?.state === 'pending' && next.state === 'ready'))
        throw Error('Unmatched compiler output response');
      show(next);
      if (previous?.state === 'unconfirmed' && next.state === 'ready')
        setNote('没有查到已登记的保存。这次需要你重新确认。');
    } catch {
      if (accepts()) setNote(saving || previous?.state === 'unconfirmed' || previous?.state === 'pending'
        ? '尚未取得这笔原保存的回执，没有再次写入。'
        : '暂时无法核对这次编译输出。没有改用其他文件。');
    } finally {
      if (flight.current === token) { flight.current = null; if (active.current) setBusy(''); }
    }
  }
  const ready = reply?.state === 'ready';
  const awaiting = reply?.state === 'pending' || reply?.state === 'unconfirmed';
  return <section className="pw-form-flow" ref={region} aria-label="这次构建的编译输出" aria-busy={!!busy}>
    {ready && <>
      <p><strong>保存这一个编译文件</strong></p>
      <p><bdi dir="ltr">{original.path}</bdi> · {original.bytes.toLocaleString()} 字节</p>
      <p className="pw-field-hint">只保存这次构建退出时核对的版本。不重新构建、运行或分享。</p>
      <details><summary>核对文件版本</summary><p className="pw-field-hint">SHA-256：<bdi>{original.sha256}</bdi></p></details>
      {note && <p className="pw-field-hint" role="status">{note}</p>}
    </>}
    {reply?.state === 'saved' && <p className="pw-reading-note" role="status">
      <strong>已保存到本机成果。</strong> 在这件工作的「概览 → 已保存的文件成果」可打开 <bdi dir="ltr">{original.path}</bdi> 的固定原件。
    </p>}
    {awaiting && <p role="status" className="pw-field-hint">{busy === saveCompilerOutput ? '正在等待原保存回执。' :
      busy === reviewCompilerOutput ? '正在核对这笔原保存。' :
      note || '这笔原保存仍待核对。只查询原保存，不会再写一份或重新构建。'}</p>}
    {!reply && note && <p role="status" className="pw-field-hint">{note}</p>}
    {reply?.state !== 'saved' && <div className="pw-form-actions">
      <Button variant={ready ? 'primary' : 'secondary'} size="small" disabled={!!busy} onClick={() => { void request(ready); }}>
        {ready ? '确认保存文件' : awaiting ? '核对原保存' : busy ? '正在核对编译输出…' : '查看这次编译输出'}
      </Button>
      {ready && <Button variant="ghost" size="small" disabled={!!busy} onClick={() => {
        if (!current() || flight.current || currentReply.current?.state !== 'ready') return;
        show(null); setNote('');
      }}>不保存</Button>}
    </div>}
  </section>;
}

/** One original project execution, displayed through the existing VT surface.
 * Open/read do not create a process; close only retires this observation session. */
export function ProjectExecutionWorkspace({ task, operation, active = true, run = bridge.command, prepareFailure, focusFailure = false, projectName, editFailedCopy, originalPending, reviewedPending = [], skipPending = [] }: {
  task: Data; operation?: Data; active?: boolean; run?: Run; prepareFailure?:(r:ExecutionExit)=>void;
  focusFailure?:boolean; projectName?:string; editFailedCopy?:()=>void; originalPending?:OriginalManagedNodePending;
  reviewedPending?:readonly OriginalManagedNodePending[]; skipPending?:readonly OriginalManagedNodePending[];
}) {
  const rows = focusFailure ? [] : projectExecutionRows(task, operation).filter(row=>!skipPending.some(pin=>sameManagedNodeOperation(row,pin)));
  const knownFailure = operation&&!originalPending ? undefined : originalFirstBuildFailure(task);
  const failure = originalPending&&knownFailure&&(knownFailure.runId!==originalPending.runId||knownFailure.operationId!==originalPending.operationId) ? undefined : knownFailure;
  const entries = failure && !rows.some(row => row.operationId === failure.operationId) ? [...rows, failure] : rows;
  const [selection, setSelection] = useState(''), [expanded, setExpanded] = useState(!!failure);
  const disclosureId = `execution:${task.id}:${operation?.operationId || 'list'}`;
  const pendingOriginal = !operation && task.raw?.developmentContext?.ownerDraftFirstBuild &&
    (task.raw.hasUnresolvedOperations === true || task.raw.historyHasUnconfirmedOperations === true);
  const selected = focusFailure ? failure : entries.find(row => row.operationId === selection) || failure || (pendingOriginal ? undefined : entries.at(-1));
  const waitingPending=selected?.state==='unknown'?reviewedPending.find(pin=>!pin.canObserve&&sameManagedNodeOperation(selected,pin)):undefined;
  const noPendingLog=!!waitingPending;
  const checkable=operation&&waitingPending?bareManagedNodeCheck(task,waitingPending):undefined;
  const commands=bridge.getState().capabilities.commands,canObserve=commands.includes('local.execution.observe'),canInteract=commands.includes('local.execution.open')&&commands.includes('local.execution.input');
  const direct=selected&&originalDirectNodeExecution(task,selected);
  const directLog=directNodeLogPin(direct);
  const directCheck=direct&&'pin' in direct&&direct.checkReceipt&&commands.includes(checkDirectBuildReceipt)?direct.pin:undefined;
  const directWaiting=!!direct&&(!directLog||!!directCheck);
  const pendingTitle=originalPending?.phase==='dependencies'?'原依赖准备':'原构建';
  const execution = selected?.operationId || '', originalKey = `${task.id}|${execution}`;
  const failureName = failure && execution === failure.operationId ? failure.failureStage === 'dependencies' ? '原依赖准备失败' : '原构建失败' : '';
  const [storedView, setView] = useState<ExecutionView | null>(null), [busy, setBusy] = useState(''), [error, setError] = useState(''), [painted, setPainted] = useState('');
  const [directCheckNote,setDirectCheckNote]=useState('');
  const view = storedView && storedView.executionId===execution && (!storedView.checkedPending||currentManagedNodeOperation(task,storedView.checkedPending)) ? storedView : null;
  const waitingForReceipt=noPendingLog&&!view?.checkedPending;
  const [, updateWindow] = useState(0);
  const [input, setInput] = useState(() => drafts.get(originalKey) || ''), [accepted, setAccepted] = useState(false), [uncertain, setUncertain] = useState(false), [inputReceipt, setInputReceipt] = useState<Data | null>(null);
  const [columns, setColumns] = useState('80'), [height, setHeight] = useState('24'), [key, setKey] = useState('\r'), [stopReview, setStopReview] = useState(false), [stopRequested, setStopRequested] = useState(stopped.has(originalKey)), [stopReceipt, setStopReceipt] = useState<Data | null>(null);
  const alive = useRef(true), pending = useRef<object | null>(null), generation = useRef(0), currentPage = useRef<ExecutionView | null>(null), windowId = useRef(ownerWindow());
  const live = useRef({ active, expanded, execution, originalKey, input, accepted, uncertain, stopRequested, stopReview, columns, height, key });
  live.current = { active, expanded, execution, originalKey, input, accepted, uncertain, stopRequested, stopReview, columns, height, key };
  const captured = { execution, originalKey, generation: generation.current, window: ownerWindow() };
  const liveOriginalTask=()=>{
    const snapshot=bridge.getState(),nativeView=snapshot.view as Data;
    if(nativeView.section!=null||nativeView.taskId!==task.id)return undefined;
    return (Array.isArray(snapshot.state.tasks)?snapshot.state.tasks as Data[]:[]).find(t=>t.id===task.id);
  };
  const samePendingWindow=(pin=originalPending??currentPage.current?.checkedPending)=>
    !pin||!!currentManagedNodeOperation(liveOriginalTask(),pin);
  const freshDirect=(pin:DirectNodeExecutionPin)=>{
    const state=bridge.getState().state;
    return selected&&state.filePath==null&&state.attachmentWorkspace?.open!==true&&
      sameDirectNodeExecution(originalDirectNodeExecution(liveOriginalTask(),selected),pin);
  };
  const current = () => alive.current && live.current.active && live.current.expanded && !document.hidden && (bridge.getState().view as Data).taskId === task.id && captured.execution === live.current.execution && captured.generation === generation.current && captured.window === ownerWindow() && samePendingWindow();
  const close = () => {
    const old = currentPage.current; currentPage.current = null; generation.current++; pending.current = null; live.current.accepted = false; live.current.stopReview = false;
    if (alive.current) { setView(null); setBusy(''); setAccepted(false); setStopReview(false); setPainted(''); setDirectCheckNote(''); updateWindow(value => value + 1); }
    if (old?.handle) void run('local.execution.close', { handle: old.handle }).catch(() => {});
  };
  const apply = (value: ExecutionView) => {
    currentPage.current = value; setView(value);
    const waiting = value.metadata?.inputUncertain === true || value.output?.inputUncertain === true;
    if (waiting || value.metadata?.inputUncertain === false && value.output?.inputUncertain === false) { live.current.uncertain = waiting; setUncertain(waiting); }
  };
  const request = async (action: string, args: Data = {}, check?:BareManagedNodeCheck) => {
    const prior=currentPage.current,checked=check??prior?.checkedPending,receiptPin=originalPending??checked;
    const checkingDirect=action===checkDirectBuildReceipt,directPin=checkingDirect?directCheck:directLog;
    if (!current() || pending.current || noPendingLog&&!checked || originalPending&&!originalPending.canObserve ||
        receiptPin&&action!=='local.execution.observe'&&action!=='local.execution.readLogs') return;
    if (direct||checkingDirect) {
      const fresh=directPin&&freshDirect(directPin);
      if (!fresh || checked || !bridge.getState().capabilities.commands.includes(action) ||
          (checkingDirect ? !fresh.checkReceipt : directWaiting || !directNodeLogPin(fresh) ||
            action!=='local.execution.observe'&&action!=='local.execution.readLogs')) return;
    }
    if(check){
      const live=bareManagedNodeCheck(liveOriginalTask(),check);
      if(action!=='local.execution.observe'||!bridge.getState().capabilities.commands.includes(action)||
        !live||live.sourceDigest!==check.sourceDigest||live.planDigest!==check.planDigest)return;
    }
    const opening = action === 'local.execution.open' || action === 'local.execution.observe';
    if (!opening && !checkingDirect && (!prior || prior.executionId !== execution) || opening && !selected) return;
    const token = {}, epoch = generation.current, owner = ownerWindow(), originalExecution = execution;
    pending.current = token; setBusy(action); setError('');
    if (checkingDirect) setDirectCheckNote('');
    const directStillCurrent = () => {
      if (!directPin) return true;
      const fresh = freshDirect(directPin);
      return !!fresh && (checkingDirect || !!directNodeLogPin(fresh));
    };
    const valid = () => alive.current && live.current.active && live.current.expanded && !document.hidden && epoch === generation.current && owner === ownerWindow() && execution === live.current.execution && pending.current === token && samePendingWindow(receiptPin) && directStillCurrent();
    let acquired: Data | null = null;
    try {
      if (checkingDirect&&directPin) {
        const {taskId,runId,epoch,buildOperationId}=directPin;
        const reply=await bridge.command(action,{taskId,runId,epoch,buildOperationId});
        if (!valid()) return;
        const matching=reply?.taskId===taskId&&reply.runId===runId&&reply.epoch===epoch&&reply.buildOperationId===buildOperationId;
        if (!matching || !(reply.state==='unknown' || reply.state==='settled'&&['completed','failed','paused'].includes(reply.status)))
          throw Error('Unmatched original direct build receipt');
        setDirectCheckNote(reply.state==='settled'?'已核对这次构建的结束回执，等待原记录更新。':'尚未核对到这次构建的结束回执。没有重新运行。');
        return;
      }
      const value = await run(action, opening ? { taskId: task.id, executionId: originalExecution,
        ...(check?{checkFirstReceipt:true}:action==='local.execution.observe'?{}:{observingOnly:args.observingOnly}) } : { ...args, handle: prior!.handle });
      if (opening) acquired = value;
      if (!valid()) { if (opening && value?.handle) void run('local.execution.close', { handle: value.handle }).catch(() => {}); return; }
      if (value?.taskId !== task.id || value.executionId !== originalExecution || typeof value.handle !== 'string' || !value.handle || !opening && value.handle !== prior!.handle) throw new Error('原项目执行回执身份不可核对，请重新打开原记录。');
      if (opening || action === 'local.execution.readLogs') {
        if(action==='local.execution.observe'&&value.metadata?.observingOnly!==true)throw Error('未取得原执行的只读观察，不进入交互终端。');
        if (value.metadata?.taskId !== task.id || value.metadata?.executionId !== originalExecution || value.output?.executionId !== originalExecution) throw new Error('原日志或终端元数据身份不一致。');
        if(receiptPin&&(value.metadata.observingOnly!==true||value.metadata.runId!==receiptPin.runId||
          value.metadata.epoch!==receiptPin.epoch||value.metadata.projectId!==receiptPin.projectId||
          value.metadata.checkoutId!==receiptPin.checkoutId))throw Error('未取得同一次本机执行的原日志，没有读取其他记录。');
        if(directPin&&(value.metadata.observingOnly!==true||value.metadata.runId!==directPin.runId||
          value.metadata.epoch!==directPin.epoch||value.metadata.projectId!==directPin.projectId||value.metadata.checkoutId!==directPin.checkoutId||
          value.metadata.planDigest!==directPin.planDigest||value.output?.terminal!==true||value.output?.resourcesReleased!==true))
          throw Error('未取得这次已结束构建的原日志。');
        if(checked){
          const exit=originalExecutionExit(value.output.observedExit,value.metadata,value.output.nativeStatus);
          if(value.output.terminal!==true||value.output.resourcesReleased!==true||!exit||exit.kind!=='exit_code'||
            exit.sourceDigest!==checked.sourceDigest||exit.planDigest!==checked.planDigest||exit.stepId!==checked.phase||
            exit.resourcesReleased!==true||exit.processGroupAbsent!==true||exit.cancelRequested!==false||exit.timedOut!==false||
            !(exit.nativeStatus==='completed'&&exit.value===0||exit.nativeStatus==='failed'&&exit.value>0))
            throw Error('未取得这次执行的已结束回执。');
        }
        if (focusFailure) {
          const receipt=originalExecutionExit(value.output.observedExit,value.metadata,value.output.nativeStatus);
          if (!failure || value.metadata.observingOnly !== true || !receipt || receipt.kind !== 'exit_code' || receipt.nativeStatus !== 'failed' ||
              receipt.runId !== failure.runId || receipt.epoch !== failure.epoch || receipt.projectId !== failure.projectId ||
              receipt.checkoutId !== failure.checkoutId || receipt.sourceDigest !== failure.sourceDigest || receipt.planDigest !== failure.planDigest ||
              receipt.stepId !== failure.failureStage || receipt.value !== failure.exitCode)
            throw new Error('未取得这次失败的原日志与退出回执，没有读取其他执行。');
        }
        apply(checked?{...value,checkedPending:checked}:value);
        if (opening) { setColumns(String(value.metadata.columns)); setHeight(String(value.metadata.rows)); live.current.uncertain = value.metadata.inputUncertain === true || value.output.inputUncertain === true; setUncertain(live.current.uncertain); setInputReceipt(null); live.current.stopRequested = stopped.has(originalKey) || value.stopRequested === true; setStopRequested(live.current.stopRequested); }
      } else if (action === 'local.execution.input' || action === 'local.execution.inputReceipt') {
        setInputReceipt(value);
        const waiting = typeof value.inputUncertain === 'boolean' ? value.inputUncertain : value.nativeInputSettled === true ? false : null;
        if (waiting !== null) {
          live.current.uncertain = waiting; setUncertain(waiting);
          const old = currentPage.current;
          if (old) apply({ ...old, metadata: { ...old.metadata, inputUncertain: waiting, ...(!waiting ? { pendingInputCount: 0 } : {}) }, output: { ...old.output, inputUncertain: waiting } });
        }
      } else if (action === 'local.execution.stop') setStopReceipt(value);
      return value;
    } catch (failure) {
      if (opening && acquired?.handle && currentPage.current?.handle !== acquired.handle) void run('local.execution.close', { handle: acquired.handle }).catch(() => {});
      if (valid()&&checkingDirect) setDirectCheckNote('尚未核对到这次构建的结束回执。没有重新运行。');
      else if (valid()) setError(check?(currentManagedNodeOperation(liveOriginalTask(),check)?.state==='receipt'?
        '同一条原执行记录已更新。可重新查看原回执；没有重新运行。':
        '尚未核对到这次执行的结束回执，结果仍待核对。没有重新运行。'):failure instanceof Error ? failure.message : String(failure));
    }
    finally { if (pending.current === token) { pending.current = null; if (alive.current) setBusy(''); } }
  };
  useEffect(() => {
    alive.current = true; windowId.current = ownerWindow();
    const inspect = () => { if (windowId.current !== ownerWindow() || document.hidden) { windowId.current = ownerWindow(); close(); } };
    const unsubscribe = bridge.subscribe(inspect); document.addEventListener('visibilitychange', inspect); window.addEventListener('pagehide', close);
    return () => { alive.current = false; close(); unsubscribe(); document.removeEventListener('visibilitychange', inspect); window.removeEventListener('pagehide', close); };
  }, [task.id]);
  useEffect(() => { if (!active) close(); }, [active]);
  useEffect(() => { close(); setInput(drafts.get(originalKey) || ''); setInputReceipt(null); setStopReceipt(null); live.current.stopRequested = stopped.has(originalKey); setStopRequested(live.current.stopRequested); setError(''); }, [originalKey]);
  const output = view?.output || {}, metadata = view?.metadata || {}, frame = output.screen;
  const frameId = `${view?.handle}|${frame?.generation}|${frame?.revision}`;
  useEffect(() => {
    if (originalPending || view?.checkedPending || noPendingLog || directWaiting || !active || !expanded || !view || busy || error || stopReview || painted !== frameId || !projectExecutionShouldRead(output)) return;
    const timer = window.setTimeout(() => { void request('local.execution.readLogs'); }, output.hasMore ? 100 : 700);
    return () => window.clearTimeout(timer);
  }, [originalPending, noPendingLog, directWaiting, active, expanded, view, busy, error, stopReview, painted, frameId]);
  useEffect(() => {
    if (!stopReview) return;
    return registerOverlayBack(() => { live.current.stopReview = false; setStopReview(false); });
  }, [stopReview]);
  const enabled = current() && !!view && !busy && !stopReview && projectExecutionCanInput(metadata, output, accepted, uncertain, stopRequested);
  const canSendNow = () => current() && !pending.current && !live.current.stopReview && !!currentPage.current && projectExecutionCanInput(currentPage.current.metadata, currentPage.current.output, live.current.accepted, live.current.uncertain, live.current.stopRequested);
  const send = (payload: Data, expectedText?: string) => {
    if (!canSendNow() || expectedText !== undefined && expectedText !== live.current.input) return;
    if (payload.action === 'resize' && (Number(live.current.columns) !== payload.columns || Number(live.current.height) !== payload.rows)) return;
    if (payload.action === 'text' && (typeof payload.text !== 'string' || payload.text.includes('\0') || new TextEncoder().encode(payload.text).length > 4096)) return;
    live.current.uncertain = true; setUncertain(true);
    void request('local.execution.input', payload).then(result => { if (result?.nativeInputSettled && result.accepted && expectedText !== undefined && live.current.input === expectedText && current()) { live.current.input = ''; drafts.delete(originalKey); setInput(''); } });
  };
  const activity = projectExecutionActivity(output.activity || {}, execution), logPolicy = output.logObservationPolicy || {};
  const observed=originalExecutionExit(output.observedExit,metadata,output.nativeStatus);
  const compiler = active && operation && !originalPending && !focusFailure && !directWaiting ? originalNodeCompilerOutput(task,operation) : undefined;
  const compilerWindow = compiler ? compilerOutputWindow(compiler.taskId) : '';
  const directBuildNote=direct?.phase==='waiting'?'这次构建已登记。收到结束与资源释放回执后，才可查看原日志。':
    direct?.phase==='stopping'?'已请求停止这次构建，尚未确认原进程退出与资源释放。':
    directLog?'这次构建已有结束回执，原工作结果仍待核对。':'这次构建的结束回执仍待核对。没有回执不代表没有执行。';
  if (!entries.length) return null;
  return <>{compiler && compilerWindow && operation && <NodeCompilerOutput key={JSON.stringify([compilerWindow,compiler])}
    original={compiler} operation={operation} owner={compilerWindow}/>}
  <Accordion className="pw-local-disclosures pw-project-execution" expandedKeys={expanded ? [disclosureId] : []} onExpandedChange={values => { const next = values.has(disclosureId); live.current.expanded = next; if (!next) close(); setExpanded(next); }}><AccordionItem id={disclosureId} title={originalPending ? pendingTitle+'的日志与回执' : focusFailure ? failureName : directWaiting?'原构建回执':waitingForReceipt&&checkable&&canObserve ? '原执行记录' : '原执行与日志'} description={originalPending ? '只读，不会重跑' : focusFailure && failure ? `实际退出码 ${failure.exitCode}` : failureName || (operation ? '这次原项目执行' : `${entries.length} 条已读取的项目执行记录`)}>
    {focusFailure && failure && <>
      <dl className="pw-facts">
        <div><dt>本次所用副本</dt><dd dir="auto">{projectName || failure.projectId} · <bdi>{failure.checkoutId}</bdi></dd></div>
        <div><dt>原批准命令</dt><dd>{failure.approvedCommand ? <code dir="ltr" className="pw-field-hint">{failure.approvedCommand.join(' ')}</code> : '未能核对完整命令参数'}</dd></div>
      </dl>
      {editFailedCopy&&<div className="pw-form-actions"><Button variant="secondary" disabled={!active||!!busy} onClick={editFailedCopy}>编辑原副本的当前文件</Button></div>}
    </>}
    {!focusFailure && !operation && (entries.length > 1 || !selected) && <SelectField label="原项目执行" value={execution} isDisabled={!!busy || stopReview} options={entries.map(row => ({ id: row.operationId, label: `${row.routeKind==='termux'?'外部 Termux 回执':row.interactive&&canInteract?'当前 PTY':'本机只读执行'} · ${row.operationId}` }))} onChange={value => { if (pending.current || !current()) return; close(); live.current.execution = value; setSelection(value); }} />}
    {!selected?<p className="pw-field-hint">原操作仍有待核对项，没有用之前的日志当作本次结果。可以手动选择具体的历史执行。</p>:directWaiting?<>
      <p className="pw-field-hint" role="status">{busy===checkDirectBuildReceipt?'正在核对这次构建的结束回执…':directCheckNote||directBuildNote}</p>
      {directCheck&&<div className="pw-form-actions"><Button variant="secondary" disabled={!active||!!busy} onClick={()=>{void request(checkDirectBuildReceipt)}}>核对这次构建的结束回执</Button></div>}
    </>:waitingForReceipt?<>
      <p className="pw-field-hint" role="status">{busy&&checkable?'正在核对这次执行…':error||(checkable&&canObserve?'尚未确认原进程回执。若核对到结束回执，只更新这次的原记录，不会重新运行。':'尚未确认原进程回执，暂不能读取日志。没有日志不代表没有执行。')}</p>
      {checkable&&canObserve&&<div className="pw-form-actions"><Button variant="secondary" disabled={!active||!!busy} onClick={()=>{void request('local.execution.observe',{},checkable)}}>核对这次执行</Button></div>}
    </>:selected.routeKind==='termux'?(commands.includes('local.execution.savedTermux')?
      <ProjectTermuxOriginalReceipt key={originalKey} task={task} row={selected} active={active&&expanded} run={run}/>:<p>当前版本没有外部 Termux 的原回调阅读能力，不转去本机终端。</p>):!view ? <><p className="pw-field-hint">{direct?.phase==='releasedAfterStop'?'停止请求已有实际结束与资源释放回执。只读查看不会重跑。':'读取原标准输出、错误输出和记录游标。打开页面不会新建或恢复执行。'}</p><div className="pw-form-actions"><Button variant="secondary" disabled={!active || !!busy || !!(originalPending||direct)&&!canObserve} onClick={() => { void request(originalPending||focusFailure||direct||canObserve?'local.execution.observe':'local.execution.open', { observingOnly: true }); }}>{originalPending ? '读取原日志与回执' : failureName ? `查看${failureName}日志` : '查看原执行日志'}</Button>{!originalPending && !direct && selected.interactive && canInteract && <Button variant="secondary" disabled={!active || !!busy} onClick={() => { void request('local.execution.open', { observingOnly: false }); }}>打开原交互终端</Button>}</div></> : <>
      <div className="pw-local-screen-toolbar"><span><strong>{metadata.executable}</strong><small>{metadata.workingDirectory}</small></span><Button variant="ghost" size="small" onClick={close}>关闭视图</Button></div>
      <p className="pw-field-hint pw-execution-scope">{metadata.observingOnly ? '只读观察，不能输入或停止执行。' : '原交互终端；输入仍受原任务时限、额度与进程状态约束。'}关闭视图只结束查看，不停止原进程。</p>
      {observed&&<ProjectExecutionExitPanel key={JSON.stringify([view.handle,observed.executionId,observed.epoch,observed.kind,observed.value,observed.nativeStatus])} exit={observed} task={task} active={active&&!busy&&current()} prepare={prepareFailure}/>}
      {!observed&&output.terminal&&<p className="pw-field-hint">此原回执没有可核对的进程退出码或信号；不推测原因。</p>}
      {activity && <p role="status" className="pw-local-inline-notice">{activity}</p>}
      {logPolicy.automaticLogPageReduced && <p role="status" className="pw-local-inline-notice">{logPolicy.ownerMessage || '原运行时已减少自动日志页的读取字节；仍读取同一份原日志。'}</p>}
      <div className="pw-local-screen"><TerminalSurface key={view.handle} dimensionMode="project" frame={frame || null} onFrameApplied={(revision, generation) => { if (alive.current && currentPage.current?.handle === view.handle) setPainted(`${view.handle}|${generation}|${revision}`); }} /></div>
      <p className="pw-field-hint">原记录游标：{output.outputCursor ?? '尚未返回'}{output.hasMore ? ' · 后续原日志仍待读取' : ''}。{output.terminal ? `原执行回执为终态；资源释放${output.resourcesReleased ? '已确认' : '尚未确认'}。` : '运行与退出以原生回执为准。'}</p>
      {(output.outputTruncated || frame?.gap || output.logIncompleteTail) && <p role="status" className="pw-local-inline-notice">{output.logIncompleteTail ? '原日志末条尚未写完整，只显示此前完整记录。' : '较早输出已被覆盖或截断，当前内容不是完整输出。'}已停止继续输入，不能把日志缺口当作进程完成。</p>}
      {!projectExecutionShouldRead(output) && !output.terminal && <p className="pw-field-hint">当前原状态不可自动轮询；请明确读取原记录，不会重放执行。</p>}
      <div className="pw-form-actions"><Button variant="secondary" disabled={!active || !!busy} onClick={() => { void request('local.execution.readLogs'); }}>读取原日志与退出回执</Button></div>
      {uncertain && <p role="status" className="pw-local-inline-notice">原输入结果待核，已禁止继续输入；没有自动重发。</p>}
      {stopRequested && <p role="status" className="pw-local-inline-notice">{stopReceipt?.resourcesReleased === true ? '原停止回执已确认资源释放，已有日志保留。' : '已请求停止原进程，退出与资源回收仍待原回执确认。请读取原日志与退出回执，不要重复停止。'}</p>}
      {!metadata.observingOnly && <Accordion className="pw-local-disclosures"><AccordionItem id="project-input-tools" title="终端输入与进程控制" description={output.terminal?'进程已结束，输入已停用':'普通输入、控制键与原范围授权'}><div className="pw-local-input">
        <CheckboxField isSelected={accepted} isDisabled={!active || !!busy || stopRequested} onChange={value => { live.current.accepted = value; setAccepted(value); }}>允许在原范围内输入普通内容</CheckboxField>
        <p className="pw-field-hint">原授权上限：{metadata.inputByteLimit} 字节 · {metadata.inputRequestLimit} 次动作。输入可能修改原私人副本，内容会留在原任务记录中。不支持密码等秘密输入。</p>
        <TextAreaField label="原终端普通输入" value={input} onChange={value => { live.current.input = value; setInput(value); drafts.set(originalKey, value); if (drafts.size > 32) drafts.delete(drafts.keys().next().value!); }} hint="明确发送后在末尾加入换行，编辑本身不会派发。" />
        <div className="pw-form-actions"><Button disabled={!enabled || !input || input.includes('\0') || new TextEncoder().encode(input + '\n').length > 4096} onClick={() => send({ action: 'text', text: input + '\n' }, input)}>发送这一行</Button><Button variant="secondary" disabled={!enabled} onClick={() => send({ action: 'text', text: '\u0003' })}>Ctrl+C</Button><Button variant="secondary" disabled={!enabled} onClick={() => send({ action: 'eof' })}>EOF</Button></div>

        {inputReceipt && <p role="status" className="pw-field-hint">{inputReceipt.knownInput === false ? '当前视图没有原输入记录。' : inputReceipt.message || (inputReceipt.nativeInputSettled ? '已取得原输入回执；字节接收不证明程序已处理。' : '原输入仍待核对，没有重新发送。')}</p>}
        <Button variant="ghost" size="small" disabled={!active || !!busy} onClick={() => { void request('local.execution.inputReceipt'); }}>核对原输入回执</Button>
        <Accordion><AccordionItem id="project-terminal-keys" title="终端按键与尺寸"><div className="pw-form-grid"><SelectField label="原终端按键" value={key} options={keys} onChange={value => { live.current.key = value; setKey(value); }} /><Button variant="secondary" disabled={!enabled} onClick={() => { if (live.current.key === key) send({ action: 'text', text: key }); }}>发送这个按键</Button></div><div className="pw-form-grid"><TextField label="列数（4–400）" value={columns} onChange={value => { live.current.columns = value; setColumns(value); }} inputMode="numeric" /><TextField label="行数（2–200）" value={height} onChange={value => { live.current.height = value; setHeight(value); }} inputMode="numeric" /></div><Button variant="secondary" disabled={!enabled || !/^\d+$/.test(columns) || !/^\d+$/.test(height) || Number(columns) < 4 || Number(columns) > 400 || Number(height) < 2 || Number(height) > 200} onClick={() => send({ action: 'resize', columns: Number(columns), rows: Number(height) })}>调整原终端尺寸</Button></AccordionItem></Accordion>
        <Button variant="ghost" disabled={!active || !!busy || stopRequested || output.terminal} onClick={() => { if (!current() || pending.current || live.current.stopRequested) return; live.current.stopReview = true; setStopReview(true); }}>停止这个原进程</Button>

      </div></AccordionItem></Accordion>}
      <Accordion><AccordionItem id="project-terminal-identity" title="原执行身份"><dl className="pw-facts"><div><dt>任务</dt><dd>{metadata.taskId}</dd></div><div><dt>运行</dt><dd>{metadata.runId} · {metadata.epoch}</dd></div><div><dt>执行</dt><dd>{metadata.executionId}</dd></div><div><dt>项目 / 副本</dt><dd>{metadata.projectId} / {metadata.checkoutId}</dd></div></dl></AccordionItem></Accordion>
    </>}
    {error && !waitingForReceipt && <p role="alert" className="pw-secondary-error">{error}</p>}
    <SettingsDialog isOpen={stopReview} title="停止这个原项目进程？" onClose={() => { live.current.stopReview = false; setStopReview(false); }}><p>只停止当前核对的原执行，已写入的副本和原日志保留。</p><p className="pw-field-hint">{metadata.taskId} · {metadata.runId} · {metadata.executionId}</p><div className="pw-form-actions"><Button variant="secondary" onClick={() => { live.current.stopReview = false; setStopReview(false); }}>返回</Button><Button variant="danger" disabled={!!busy || !active} onClick={() => { if (!current() || pending.current || !live.current.stopReview || live.current.stopRequested || currentPage.current?.handle !== view?.handle) return; live.current.stopRequested = true; live.current.stopReview = false; stopped.add(originalKey); if (stopped.size > 64) stopped.delete(stopped.values().next().value!); setStopRequested(true); setStopReview(false); void request('local.execution.stop'); }}>确认停止原进程一次</Button></div></SettingsDialog>
  </AccordionItem></Accordion></>;
}

