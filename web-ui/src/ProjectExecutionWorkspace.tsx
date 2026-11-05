import { useEffect, useRef, useState } from 'react';
import { Accordion, AccordionItem, Button, CheckboxField, SelectField, SettingsDialog, TextAreaField, TextField } from './forms-library';
import TerminalSurface from './TerminalSurface';
import { bridge } from './bridge';
import { registerOverlayBack } from './overlay-back';
import { projectExecutionActivity, projectExecutionCanInput, projectExecutionRows, projectExecutionShouldRead, type ProjectExecutionData as Data } from './project-execution';
import './local-workspace.css';

type Run = (action: string, args?: Data) => Promise<any>;
const ownerWindow = () => { const view = bridge.getState().view as Data; return [bridge.getDocumentEpoch(), view.taskId, view.section, view.resumeRevision].join('|'); };
const drafts = new Map<string, string>(), stopped = new Set<string>();
const keys = [{ id: '\r', label: 'Enter' }, { id: '\t', label: 'Tab' }, { id: '\u001b', label: 'Esc' }, { id: '\u001b[A', label: '↑' }, { id: '\u001b[B', label: '↓' }, { id: '\u001b[C', label: '→' }, { id: '\u001b[D', label: '←' }];

/** One original project execution, displayed through the existing VT surface.
 * Open/read do not create a process; close only retires this observation session. */
export function ProjectExecutionWorkspace({ task, operation, active = true, run = bridge.command }: { task: Data; operation?: Data; active?: boolean; run?: Run }) {
  const entries = projectExecutionRows(task, operation), [selection, setSelection] = useState('');
  const [expanded, setExpanded] = useState(false), disclosureId = `execution:${task.id}:${operation?.operationId || 'list'}`;
  const selected = entries.find(row => row.operationId === selection) || entries.at(-1), execution = selected?.operationId || '', originalKey = `${task.id}|${execution}`;
  const [view, setView] = useState<Data | null>(null), [busy, setBusy] = useState(''), [error, setError] = useState(''), [painted, setPainted] = useState('');
  const [, updateWindow] = useState(0);
  const [input, setInput] = useState(() => drafts.get(originalKey) || ''), [accepted, setAccepted] = useState(false), [uncertain, setUncertain] = useState(false), [inputReceipt, setInputReceipt] = useState<Data | null>(null);
  const [columns, setColumns] = useState('80'), [height, setHeight] = useState('24'), [key, setKey] = useState('\r'), [stopReview, setStopReview] = useState(false), [stopRequested, setStopRequested] = useState(stopped.has(originalKey)), [stopReceipt, setStopReceipt] = useState<Data | null>(null);
  const alive = useRef(true), pending = useRef<object | null>(null), generation = useRef(0), currentPage = useRef<Data | null>(null), windowId = useRef(ownerWindow());
  const live = useRef({ active, expanded, execution, originalKey, input, accepted, uncertain, stopRequested, stopReview, columns, height, key });
  live.current = { active, expanded, execution, originalKey, input, accepted, uncertain, stopRequested, stopReview, columns, height, key };
  const captured = { execution, originalKey, generation: generation.current, window: ownerWindow() };
  const current = () => alive.current && live.current.active && live.current.expanded && !document.hidden && (bridge.getState().view as Data).taskId === task.id && captured.execution === live.current.execution && captured.generation === generation.current && captured.window === ownerWindow();
  const close = () => {
    const old = currentPage.current; currentPage.current = null; generation.current++; pending.current = null; live.current.accepted = false; live.current.stopReview = false;
    if (alive.current) { setView(null); setBusy(''); setAccepted(false); setStopReview(false); setPainted(''); updateWindow(value => value + 1); }
    if (old?.handle) void run('local.execution.close', { handle: old.handle }).catch(() => {});
  };
  const apply = (value: Data) => { currentPage.current = value; setView(value); if (value.metadata?.inputUncertain || value.output?.inputUncertain) { live.current.uncertain = true; setUncertain(true); } };
  const request = async (action: string, args: Data = {}) => {
    if (!current() || pending.current) return;
    const opening = action === 'local.execution.open', prior = currentPage.current;
    if (!opening && !prior || opening && !selected) return;
    const token = {}, epoch = generation.current, owner = ownerWindow(), originalExecution = execution;
    pending.current = token; setBusy(action); setError('');
    const valid = () => alive.current && live.current.active && live.current.expanded && !document.hidden && epoch === generation.current && owner === ownerWindow() && execution === live.current.execution && pending.current === token;
    let acquired: Data | null = null;
    try {
      const value = await run(action, opening ? { taskId: task.id, executionId: originalExecution, observingOnly: args.observingOnly } : { ...args, handle: prior!.handle });
      if (opening) acquired = value;
      if (!valid()) { if (opening && value?.handle) void run('local.execution.close', { handle: value.handle }).catch(() => {}); return; }
      if (value?.taskId !== task.id || value.executionId !== originalExecution || typeof value.handle !== 'string' || !value.handle || !opening && value.handle !== prior!.handle) throw new Error('原项目执行回执身份不可核对，请重新打开原记录。');
      if (opening || action === 'local.execution.readLogs') {
        if (value.metadata?.taskId !== task.id || value.metadata?.executionId !== originalExecution || value.output?.executionId !== originalExecution) throw new Error('原日志或终端元数据身份不一致。');
        apply(value);
        if (opening) { setColumns(String(value.metadata.columns)); setHeight(String(value.metadata.rows)); live.current.uncertain = value.metadata.inputUncertain === true || value.output.inputUncertain === true; setUncertain(live.current.uncertain); setInputReceipt(null); live.current.stopRequested = stopped.has(originalKey) || value.stopRequested === true; setStopRequested(live.current.stopRequested); }
      } else if (action === 'local.execution.input' || action === 'local.execution.inputReceipt') {
        setInputReceipt(value);
        if (value.nativeInputSettled === true) { live.current.uncertain = false; setUncertain(false); const old = currentPage.current; if (old) apply({ ...old, metadata: { ...old.metadata, inputUncertain: false, pendingInputCount: 0 }, output: { ...old.output, inputUncertain: false } }); }
      } else if (action === 'local.execution.stop') setStopReceipt(value);
      return value;
    } catch (failure) { if (opening && acquired?.handle && currentPage.current?.handle !== acquired.handle) void run('local.execution.close', { handle: acquired.handle }).catch(() => {}); if (valid()) setError(failure instanceof Error ? failure.message : String(failure)); }
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
    if (!active || !expanded || !view || busy || error || stopReview || painted !== frameId || !projectExecutionShouldRead(output)) return;
    const timer = window.setTimeout(() => { void request('local.execution.readLogs'); }, output.hasMore ? 100 : 700);
    return () => window.clearTimeout(timer);
  }, [active, expanded, view, busy, error, stopReview, painted, frameId]);
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
  if (!entries.length) return null;
  return <Accordion className="pw-local-disclosures" expandedKeys={expanded ? [disclosureId] : []} onExpandedChange={values => { const next = values.has(disclosureId); live.current.expanded = next; if (!next) close(); setExpanded(next); }}><AccordionItem id={disclosureId} title="执行终端与原日志" description={operation ? '这次原项目执行' : `${entries.length} 条已读取的项目执行记录`}>
    {!operation && entries.length > 1 && <SelectField label="原项目执行" value={execution} isDisabled={!!busy || stopReview} options={entries.map(row => ({ id: row.operationId, label: `${row.interactive ? '当前 PTY' : '只读执行'} · ${row.operationId}` }))} onChange={value => { if (pending.current || !current()) return; close(); live.current.execution = value; setSelection(value); }} />}
    {!view ? <><p className="pw-field-hint">读取原标准输出、错误输出和记录游标。打开页面不会新建或恢复执行。</p><div className="pw-form-actions"><Button variant="secondary" disabled={!active || !!busy} onClick={() => { void request('local.execution.open', { observingOnly: true }); }}>查看原执行日志</Button>{selected?.interactive && <Button variant="secondary" disabled={!active || !!busy} onClick={() => { void request('local.execution.open', { observingOnly: false }); }}>打开原交互终端</Button>}</div></> : <>
      <div className="pw-local-screen-toolbar"><span>{metadata.executable} · {metadata.workingDirectory}</span><Button variant="ghost" size="small" onClick={close}>关闭视图</Button></div>
      <p className="pw-field-hint">{metadata.observingOnly ? '只读观察，不能输入或停止执行。' : '原交互终端；输入仍受原任务时限、额度与进程状态约束。'}关闭视图只结束查看，不停止原进程。</p>
      {activity && <p role="status" className="pw-local-inline-notice">{activity}</p>}
      {logPolicy.automaticLogPageReduced && <p role="status" className="pw-local-inline-notice">{logPolicy.ownerMessage || '原运行时已减少自动日志页的读取字节；仍读取同一份原日志。'}</p>}
      <div className="pw-local-screen"><TerminalSurface key={view.handle} dimensionMode="project" frame={frame || null} onFrameApplied={(revision, generation) => { if (alive.current && currentPage.current?.handle === view.handle) setPainted(`${view.handle}|${generation}|${revision}`); }} /></div>
      <p className="pw-field-hint">原记录游标：{output.outputCursor ?? '尚未返回'}{output.hasMore ? ' · 后续原日志仍待读取' : ''}。{output.terminal ? `原进程已返回终态；资源释放${output.resourcesReleased ? '已确认' : '尚未确认'}。` : '运行与退出以原生回执为准。'}</p>
      {(output.outputTruncated || frame?.gap || output.logIncompleteTail) && <p role="status" className="pw-local-inline-notice">{output.logIncompleteTail ? '原日志末条尚未写完整，只显示此前完整记录。' : '较早输出已被覆盖或截断，当前内容不是完整输出。'}已停止继续输入，不能把日志缺口当作进程完成。</p>}
      {!projectExecutionShouldRead(output) && !output.terminal && <p className="pw-field-hint">当前原状态不可自动轮询；请明确读取原记录，不会重放执行。</p>}
      <div className="pw-form-actions"><Button variant="secondary" disabled={!active || !!busy} onClick={() => { void request('local.execution.readLogs'); }}>读取原日志与退出回执</Button></div>
      {!metadata.observingOnly && <div className="pw-local-input">
        <CheckboxField isSelected={accepted} isDisabled={!active || !!busy || stopRequested} onChange={value => { live.current.accepted = value; setAccepted(value); }}>允许在原范围内输入普通内容</CheckboxField>
        <p className="pw-field-hint">原授权上限：{metadata.inputByteLimit} 字节 · {metadata.inputRequestLimit} 次动作。输入可能修改原私人副本，内容会留在原任务记录中。不支持密码等秘密输入。</p>
        <TextAreaField label="原终端普通输入" value={input} onChange={value => { live.current.input = value; setInput(value); drafts.set(originalKey, value); if (drafts.size > 32) drafts.delete(drafts.keys().next().value!); }} hint="明确发送后在末尾加入换行，编辑本身不会派发。" />
        <div className="pw-form-actions"><Button disabled={!enabled || !input || input.includes('\0') || new TextEncoder().encode(input + '\n').length > 4096} onClick={() => send({ action: 'text', text: input + '\n' }, input)}>发送这一行</Button><Button variant="secondary" disabled={!enabled} onClick={() => send({ action: 'text', text: '\u0003' })}>Ctrl+C</Button><Button variant="secondary" disabled={!enabled} onClick={() => send({ action: 'eof' })}>EOF</Button></div>
        {uncertain && <p role="status" className="pw-local-inline-notice">原输入结果待核，已禁止继续输入；没有自动重发。</p>}
        {inputReceipt && <p role="status" className="pw-field-hint">{inputReceipt.knownInput === false ? '当前视图没有原输入记录。' : inputReceipt.message || (inputReceipt.nativeInputSettled ? '已取得原输入回执；字节接收不证明程序已处理。' : '原输入仍待核对，没有重新发送。')}</p>}
        <Button variant="ghost" size="small" disabled={!active || !!busy} onClick={() => { void request('local.execution.inputReceipt'); }}>核对原输入回执</Button>
        <Accordion><AccordionItem id="project-terminal-keys" title="终端按键与尺寸"><div className="pw-form-grid"><SelectField label="原终端按键" value={key} options={keys} onChange={value => { live.current.key = value; setKey(value); }} /><Button variant="secondary" disabled={!enabled} onClick={() => { if (live.current.key === key) send({ action: 'text', text: key }); }}>发送这个按键</Button></div><div className="pw-form-grid"><TextField label="列数（4–400）" value={columns} onChange={value => { live.current.columns = value; setColumns(value); }} inputMode="numeric" /><TextField label="行数（2–200）" value={height} onChange={value => { live.current.height = value; setHeight(value); }} inputMode="numeric" /></div><Button variant="secondary" disabled={!enabled || !/^\d+$/.test(columns) || !/^\d+$/.test(height) || Number(columns) < 4 || Number(columns) > 400 || Number(height) < 2 || Number(height) > 200} onClick={() => send({ action: 'resize', columns: Number(columns), rows: Number(height) })}>调整原终端尺寸</Button></AccordionItem></Accordion>
        <Button variant="ghost" disabled={!active || !!busy || stopRequested || output.terminal} onClick={() => { if (!current() || pending.current || live.current.stopRequested) return; live.current.stopReview = true; setStopReview(true); }}>停止这个原进程</Button>
        {stopRequested && <p role="status" className="pw-local-inline-notice">{stopReceipt?.resourcesReleased === true ? '原停止回执已确认资源释放，已有日志保留。' : '已请求停止原进程，退出与资源回收仍待原回执确认。请读取原日志与退出回执，不要重复停止。'}</p>}
      </div>}
      <Accordion><AccordionItem id="project-terminal-identity" title="原执行身份"><dl className="pw-facts"><div><dt>任务</dt><dd>{metadata.taskId}</dd></div><div><dt>运行</dt><dd>{metadata.runId} · {metadata.epoch}</dd></div><div><dt>执行</dt><dd>{metadata.executionId}</dd></div><div><dt>项目 / 副本</dt><dd>{metadata.projectId} / {metadata.checkoutId}</dd></div></dl></AccordionItem></Accordion>
    </>}
    {error && <p role="alert" className="pw-secondary-error">{error}</p>}
    <SettingsDialog isOpen={stopReview} title="停止这个原项目进程？" onClose={() => { live.current.stopReview = false; setStopReview(false); }}><p>只停止当前核对的原执行，已写入的副本和原日志保留。</p><p className="pw-field-hint">{metadata.taskId} · {metadata.runId} · {metadata.executionId}</p><div className="pw-form-actions"><Button variant="secondary" onClick={() => { live.current.stopReview = false; setStopReview(false); }}>返回</Button><Button variant="danger" disabled={!!busy || !active} onClick={() => { if (!current() || pending.current || !live.current.stopReview || live.current.stopRequested || currentPage.current?.handle !== view?.handle) return; live.current.stopRequested = true; live.current.stopReview = false; stopped.add(originalKey); if (stopped.size > 64) stopped.delete(stopped.values().next().value!); setStopRequested(true); setStopReview(false); void request('local.execution.stop'); }}>确认停止原进程一次</Button></div></SettingsDialog>
  </AccordionItem></Accordion>;
}
