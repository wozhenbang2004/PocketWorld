import { useEffect, useRef, useState } from 'react';
import { Button, CheckboxField, SelectField, SettingsDialog, TextAreaField, TextField } from './forms-library';
import TerminalSurface from './TerminalSurface';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
const rows = (value: any): Data[] => Array.isArray(value) ? value : [];
const identity = (task: Data) => ({ taskId: task.taskId || task.id, runId: task.runId || task.raw?.runId, epoch: task.epoch || task.raw?.coordinatorEpoch, executionId: task.executionId || task.id });

/** Existing local checkout/PTY workflow composed with the same BoardUI fields and original xterm. */
export function LocalWorkspace({ state, data, run, supported, registerTransientBack }: { state: Data; data: Data; run: Run; supported: (name: string) => boolean; registerTransientBack: (handler: (() => boolean) | null) => void }) {
  const [project, setProject] = useState(''), [checkout, setCheckout] = useState(''), [source, setSource] = useState<Data | null>(null);
  const [cwd, setCwd] = useState('.'), [env, setEnv] = useState('{}'), [seconds, setSeconds] = useState('300');
  const [review, setReview] = useState<Data | null>(null), [acknowledged, setAcknowledged] = useState(false), [original, setOriginal] = useState<Data | null>(null), [receipt, setReceipt] = useState<Data | null>(null);
  const [view, setView] = useState<Data | null>(null), [output, setOutput] = useState<Data | null>(null), [input, setInput] = useState(''), [uncertain, setUncertain] = useState(false);
  const [columns, setColumns] = useState('80'), [height, setHeight] = useState('24'), [stopReview, setStopReview] = useState(false);
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [prepareAttempted, setPrepareAttempted] = useState(false);
  const entered = useRef(false), alive = useRef(true), viewHandle = useRef(''), reviewHandle = useRef('');
  const invoke = async (name: string, args: Data = {}, accept?: (value: Data) => void) => {
    if (entered.current) return; entered.current = true; setBusy(true); setError('');
    try { const result = await run(name, args); if (alive.current) accept?.(result); return result; }
    catch (failure) { if (alive.current) setError(failure instanceof Error ? failure.message : String(failure)); throw failure; }
    finally { entered.current = false; if (alive.current) setBusy(false); }
  };
  const closeViewer = () => {
    const handle = viewHandle.current; viewHandle.current = ''; setView(null); setOutput(null); setInput('');
    if (handle) void run('local.terminal.close', { handle }).catch(() => {});
  };
  const closeReview = () => {
    const handle = reviewHandle.current; reviewHandle.current = ''; setReview(null); setAcknowledged(false);
    if (handle) void run('local.terminal.close', { handle }).catch(() => {});
  };
  useEffect(() => { alive.current = true; return () => { alive.current = false; const handle = viewHandle.current || reviewHandle.current; if (handle) void run('local.terminal.close', { handle }).catch(() => {}); }; }, []);
  useEffect(() => { registerTransientBack(review ? () => { if (!busy) closeReview(); return true; } : view ? () => { if (!busy) closeViewer(); return true; } : null); return () => registerTransientBack(null); }, [!!review, !!view, busy, registerTransientBack]);
  const open = (target: Data) => {
    if (busy) return;
    void invoke('local.terminal.open', { taskId: target.taskId, executionId: target.executionId }, result => {
      viewHandle.current = result.handle; setView(result); setOutput(result.output); setUncertain(result.metadata?.inputUncertain === true); setOriginal(target);
      setColumns(String(result.metadata?.columns || 80)); setHeight(String(result.metadata?.rows || 24)); setInput('');
    }).catch(() => {});
  };
  useEffect(() => {
    if (!view?.handle || busy || error || output?.terminal || document.visibilityState !== 'visible') return;
    const handle = view.handle;
    const timer = window.setTimeout(() => { if (viewHandle.current === handle) void invoke('local.terminal.read', { handle }, value => { if (viewHandle.current === handle) { setOutput(value); if (value.inputUncertain) setUncertain(true); } }).catch(() => {}); }, 1200);
    return () => window.clearTimeout(timer);
  }, [view?.handle, output, busy, error]);
  const selected = rows(data.checkouts).find(row => row.id === checkout && row.projectId === project);
  const checkouts = rows(data.checkouts).filter(row => row.projectId === project && !row.teamProjectCopy);
  const terminals = rows(state.tasks).filter(row => row.raw?.nativeExecutionMode === 'OWNER_LOCAL_TERMINAL' || row.raw?.ownerLocalTerminal);
  const ready = !!selected && source?.checkoutId === checkout && source?.projectId === project;
  const canInput = !!view?.handle && !busy && !uncertain && output?.nativeStatus === 'running' && !output?.terminal;
  const send = (payload: Data) => {
    if (!canInput) return;
    setUncertain(true); const text = input;
    void invoke('local.terminal.input', { handle: view!.handle, ...payload }, result => {
      setReceipt(result); setUncertain(result.nativeInputSettled !== true);
      if (payload.action === 'text' && result.nativeInputSettled && result.accepted) setInput(current => current === text ? '' : current);
    }).catch(() => {});
  };
  const knownResults = state.moduleResults || {};
  return <>
    <section className="pw-settings-section"><header className="pw-section-heading"><h2>本机开发</h2><p>选定私人工作副本，在原进程中操作并核对回执。</p></header>
      <div className="pw-form-actions"><Button variant="secondary" disabled={busy || !supported('local.inspectRuntime')} onClick={() => { void invoke('local.inspectRuntime').catch(() => {}); }}>核对运行时</Button><Button variant="secondary" disabled={busy || !supported('local.readReceipts')} onClick={() => { void invoke('local.readReceipts').catch(() => {}); }}>读取原运行回执</Button><Button variant="ghost" disabled={busy || !supported('local.termuxProbe')} onClick={() => { void invoke('local.termuxProbe').catch(() => {}); }}>核对 Termux</Button></div>
      {rows(data.projectRuntimes?.runtimes || data.projectRuntimes).map(row => <div className="pw-source-record" key={row.id || row.runtimeId}><h3>{row.name || row.id || row.runtimeId}</h3><p>{row.message || row.status || (row.available ? '当前可用' : '尚未确认可用')}</p></div>)}
      {knownResults['local.probe']?.message && <p>{knownResults['local.probe'].message}</p>}
      {Array.isArray(knownResults['local.probe']?.tools) && <p className="pw-field-hint">已发现工具：{knownResults['local.probe'].tools.map((tool: any) => typeof tool === 'string' ? tool : tool.name || tool.id).join('、') || '尚无'}</p>}
      {knownResults['local.listJobs'] && <details><summary>原本机与 Termux 运行回执</summary>{[...rows(knownResults['local.listJobs'].jobs).map(row => ({ ...row, backendLabel: '本机' })), ...rows(knownResults['local.listJobs'].termuxJobs).map(row => ({ ...row, backendLabel: 'Termux' }))].map((row: Data, index) => <div className="pw-source-record" key={row.id || row.operationId || index}><h3>{row.backendLabel} · {row.status || row.state || '状态待核对'}</h3><p>{row.message || row.summary}</p><dl className="pw-facts"><div><dt>原操作</dt><dd>{row.operationId || row.id}</dd></div><div><dt>退出状态</dt><dd>{row.exitCode == null ? '尚未返回' : row.exitCode}</dd></div></dl></div>)}</details>}
      <div className="pw-form-grid"><SelectField label="原项目" value={project} options={[{ id: '', label: '选择已有项目' }, ...rows(state.projects).map(row => ({ id: row.id, label: row.name }))]} onChange={value => { if (!busy) { closeViewer(); setOriginal(null); setReceipt(null); setProject(value); setCheckout(''); setSource(null); setPrepareAttempted(false); } }} />
        <SelectField label="私人工作副本" value={checkout} options={[{ id: '', label: '选择或准备副本' }, ...checkouts.map(row => ({ id: row.id, label: (row.name || '工作副本') + ' · ' + new Date(row.createdAt || 0).toLocaleString() }))]} onChange={value => { if (!busy) { closeViewer(); setOriginal(null); setReceipt(null); setCheckout(value); setSource(null); } }} /></div>
      <div className="pw-form-actions"><Button variant="secondary" disabled={!project || busy || prepareAttempted} onClick={() => { setPrepareAttempted(true); void invoke('local.prepareCheckout', { projectId: project }, result => { setCheckout(result.id); setSource(null); }).catch(() => {}); }}>从当前项目准备私人副本</Button><Button variant="secondary" disabled={!selected || busy} onClick={() => { void invoke('local.describeCheckout', { projectId: project, checkoutId: checkout }, setSource).catch(() => {}); }}>读取这个副本的实际版本</Button></div>
      {prepareAttempted && <p className="pw-field-hint">本次已请求准备副本。请核对上方真实副本列表；回执不明时不重复准备。</p>}
    </section>
    <section className="pw-settings-section"><header className="pw-section-heading"><h2>手动终端</h2><p>在受控本机环境中打开原工作副本；不调用模型。输入和输出会保留在原任务记录中，不支持密码等秘密输入。</p></header>
      {ready && <p>{source.fileCount} 个源码文件 · 当前副本 {source.bytes} 字节</p>}
      <div className="pw-form-grid"><TextField label="副本中的工作目录" value={cwd} onChange={setCwd} /><TextField label="最长运行秒数（1–300）" inputMode="numeric" value={seconds} onChange={value => setSeconds(value.replace(/\D/g, '').slice(0, 3))} /><TextAreaField label="普通环境变量（JSON 对象）" value={env} onChange={setEnv} hint="例如 APP_LABEL；不能设置密钥或覆盖宿主运行环境。" /></div>
      <Button disabled={busy || !ready || Number(seconds) < 1 || Number(seconds) > 300 || !supported('local.terminal.review')} onClick={() => {
        try { if (view) closeViewer(); const environment = JSON.parse(env); if (!environment || Array.isArray(environment) || typeof environment !== 'object') throw new Error('环境变量需要 JSON 对象。');
          void invoke('local.terminal.review', { projectId: project, checkoutId: checkout, expectedSourceDigest: source!.sourceDigest, runtimeId: 'managed_process_v1', cwd, env: environment, timeoutMillis: Number(seconds) * 1000, columns: 80, rows: 24, inputByteLimit: 65536 }, result => { reviewHandle.current = result.handle; setReview(result); setAcknowledged(false); }).catch(() => {});
        } catch (failure) { setError(failure instanceof Error ? failure.message : '请核对表单'); }
      }}>核对并准备终端</Button>
      {original && <div className="pw-source-record"><h3>本次原终端</h3><p>{receipt?.status || '启动结果待核对'}{receipt?.message ? ' · ' + receipt.message : ''}</p><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => { void invoke('local.terminal.query', original, setReceipt).catch(() => {}); }}>查询原执行</Button><Button variant="secondary" disabled={busy} onClick={() => open(original)}>打开原终端</Button><Button variant="ghost" disabled={busy} onClick={() => setStopReview(true)}>停止这个原进程</Button></div></div>}
      {terminals.length > 0 && <details><summary>已读取窗口中的手动终端</summary>{terminals.map(task => <div className="pw-source-record" key={task.id}><h3>{task.goal || '手动终端'}</h3><p>{task.summary}</p><Button variant="secondary" disabled={busy} onClick={() => { const target = identity(task); closeViewer(); setOriginal(target); void invoke('local.terminal.query', target, setReceipt).catch(() => {}); }}>核对这条原记录</Button></div>)}</details>}
      {error && <p role="alert" className="pw-secondary-error">{error}</p>}
    </section>
    {view && <section className="pw-settings-section"><header className="pw-section-heading"><h2>原终端屏幕</h2><p>关闭这个视图只结束查看，原进程按原时限继续。</p></header><TerminalSurface key={view.handle} frame={output?.screen || null} />
      {output?.terminal && <p>原进程已返回终态；资源释放：{output.resourcesReleased ? '已确认' : '尚未确认'}。</p>}
      <TextAreaField label="普通终端输入" value={input} onChange={setInput} hint="可输入命令；发送一行会在末尾加入换行。不要输入秘密。" />
      <div className="pw-form-actions"><Button variant="ghost" disabled={busy} onClick={() => { void invoke('local.terminal.read', { handle: view.handle }, setOutput).catch(() => {}); }}>读取原屏幕</Button><Button disabled={!canInput || !input || new TextEncoder().encode(input + '\n').length > 4096} onClick={() => send({ action: 'text', text: input + '\n' })}>发送这一行</Button><Button variant="secondary" disabled={!canInput} onClick={() => send({ action: 'text', text: '\u0003' })}>发送 Ctrl+C</Button><Button variant="secondary" disabled={!canInput} onClick={() => send({ action: 'eof' })}>发送 EOF</Button><Button variant="ghost" disabled={busy} onClick={closeViewer}>关闭视图，保留进程</Button></div>
      <div className="pw-form-grid"><TextField label="列数（20–300）" value={columns} onChange={setColumns} inputMode="numeric" /><TextField label="行数（5–120）" value={height} onChange={setHeight} inputMode="numeric" /></div><Button variant="ghost" disabled={!canInput || !/^\d+$/.test(columns) || !/^\d+$/.test(height) || Number(columns) < 20 || Number(columns) > 300 || Number(height) < 5 || Number(height) > 120} onClick={() => send({ action: 'resize', columns: Number(columns), rows: Number(height) })}>调整原终端尺寸</Button>
      {uncertain && <p className="pw-field-hint">上一输入尚未核对，已停止继续输入。</p>}<Button variant="ghost" disabled={busy} onClick={() => { void invoke('local.terminal.inputReceipt', { handle: view.handle }, result => { setReceipt(result); if (result.nativeInputSettled === true) setUncertain(false); }).catch(() => {}); }}>核对原输入回执</Button>
    </section>}
    <SettingsDialog isOpen={!!review} title="打开这个手动终端？" onClose={() => { if (!busy) closeReview(); }}>{review && <><dl className="pw-facts"><div><dt>原目录</dt><dd>{review.command?.workingDirectory}</dd></div><div><dt>最长运行时间</dt><dd>{review.command?.timeoutMillis / 1000} 秒</dd></div><div><dt>输入额度</dt><dd>{review.command?.inputByteLimit} 字节，最多 {review.inputRequestLimit} 次</dd></div></dl><p>只打开已登记的 sh -i。终端操作可能修改这个私人副本，是否导回项目另行核对。</p>{rows(review.warnings).map((warning, i) => <p key={i}>{String(warning)}</p>)}<CheckboxField isSelected={acknowledged} onChange={setAcknowledged}>我已核对目录、时限与普通输入范围</CheckboxField><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={closeReview}>返回</Button><Button disabled={busy || !acknowledged} onClick={() => { const selected = review; const handle = reviewHandle.current; reviewHandle.current = ''; setReview(null); setOriginal({ taskId: selected.requestId, runId: selected.requestId, epoch: 1, executionId: selected.requestId }); setReceipt(null); void invoke('local.terminal.confirm', { handle }, setReceipt).catch(() => {}); }}>确认打开原终端</Button></div></>}</SettingsDialog>
    <SettingsDialog isOpen={stopReview} title="停止这个原终端进程？" onClose={() => { if (!busy) setStopReview(false); }}><p>只请求停止所选原进程。已写入的副本内容和原日志保留；退出与资源回收以真实回执为准。</p><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => setStopReview(false)}>返回</Button><Button disabled={busy || !original} onClick={() => { setStopReview(false); if (original) void invoke('local.terminal.stop', original, value => { setReceipt(value); setUncertain(true); }).catch(() => {}); }}>停止这个原进程</Button></div></SettingsDialog>
  </>;
}
