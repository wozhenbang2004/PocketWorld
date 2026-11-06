import { useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { DiagnosticWorkflowSteps, ProbeObservationCard, RuntimeAttemptChain } from './shell-library/affect-workflows';
import { Accordion, AccordionItem, Button, CheckboxField, SettingsDialog } from './forms-library';

type Data = Record<string, any>;
type Props = { state: Data; supported: (command: string) => boolean };
const object = (value: any): Data => value && typeof value === 'object' && !Array.isArray(value) ? value : {};

/** Local failure evidence and separately approved synthetic requests in the existing settings disclosure. */
export function ModelDiagnosticsWorkspace({ state, supported }: Props) {
  const data = object(state.modelDiagnostics), probe = object(data.probe);
  const [expanded, setExpanded] = useState(false), [image, setImage] = useState(false), [acknowledge, setAcknowledge] = useState(false);
  const [review, setReview] = useState<Data | null>(null), [pending, setPending] = useState(false), [error, setError] = useState('');
  const [logs, setLogs] = useState<Data | null>(null), [copyStatus, setCopyStatus] = useState('');
  const [stage, setStage] = useState<'select' | 'review' | 'observe'>('select');
  const windowId = useRef(''), alive = useRef(true), sequence = useRef(0), inFlight = useRef(false);
  const epoch = bridge.getDocumentEpoch(), previousEpoch = useRef(epoch);
  const previousSelection = useRef(data.probeSelectionRevision);
  const active = state.webWorkspaceForeground !== false && document.visibilityState === 'visible';
  const available = supported('modelDiagnostics.open');
  useEffect(() => {
    alive.current = true;
    const hidden = () => { if (document.visibilityState !== 'visible') { sequence.current++; setReview(null); setAcknowledge(false); setExpanded(false); setLogs(null); setCopyStatus(''); setStage('select'); } };
    document.addEventListener('visibilitychange', hidden);
    return () => { alive.current = false; sequence.current++; document.removeEventListener('visibilitychange', hidden); const id = windowId.current; windowId.current = ''; if (id) void bridge.command('modelDiagnostics.close', { windowId: id }).catch(() => {}); };
  }, []);
  useEffect(() => { if (previousEpoch.current !== epoch || !active || !data.ready) { previousEpoch.current = epoch; sequence.current++; setReview(null); setAcknowledge(false); setLogs(null); setCopyStatus(''); setStage('select'); if (!active) setExpanded(false); } }, [epoch, active, data.ready, data.probeSelectionRevision]);
  useEffect(() => {
    if (previousSelection.current !== undefined && previousSelection.current !== data.probeSelectionRevision) sequence.current++;
    previousSelection.current = data.probeSelectionRevision; setReview(null); setAcknowledge(false); setStage('select');
  }, [data.probeSelectionRevision]);
  async function invoke(command: string, fields: Data = {}) {
    if (!alive.current || !active || inFlight.current) return;
    const operation = ++sequence.current, documentEpoch = bridge.getDocumentEpoch();
    inFlight.current = true; setPending(true); setError('');
    try {
      const result = object(await bridge.command(command, command === 'modelDiagnostics.open' ? {} : { windowId: windowId.current, ...fields }));
      if (!alive.current || operation !== sequence.current || bridge.getDocumentEpoch() !== documentEpoch || document.visibilityState !== 'visible') return;
      if (command === 'modelDiagnostics.open') { if (typeof result.windowId !== 'string' || !result.ready) throw new Error('诊断窗口尚未就绪'); windowId.current = result.windowId; setExpanded(true); }
      return result;
    } catch (failure) { if (alive.current && operation === sequence.current) setError(failure instanceof Error ? failure.message : '原诊断结果尚未确认'); }
    finally { inFlight.current = false; if (alive.current) setPending(false); }
  }
  const closeReview = () => { setReview(null); setAcknowledge(false); setStage('select'); if (windowId.current) void invoke('modelDiagnostics.discard'); };
  const busy = pending || !!data.busy, ready = active && data.ready === true && data.windowId === windowId.current;
  const logText = logs ? JSON.stringify(logs, null, 2) : '';
  async function copyLogs() {
    if (!ready || !logs) return;
    const originalWindow = windowId.current, originalEpoch = bridge.getDocumentEpoch(), originalSequence = sequence.current;
    const current = () => alive.current && windowId.current === originalWindow && bridge.getDocumentEpoch() === originalEpoch && sequence.current === originalSequence && document.visibilityState === 'visible';
    try {
      if (!navigator.clipboard?.writeText) throw new Error('当前无法直接复制，请选择下方日志文字后复制。');
      await navigator.clipboard.writeText(logText);
      if (current()) setCopyStatus('已复制本次读取的运行日志');
    } catch (failure) { if (current()) setCopyStatus(failure instanceof Error ? failure.message : '复制未完成，请选择日志文字后复制。'); }
  }
  return <section className="pw-model-diagnostics">
    <Button variant="ghost" disabled={!available || pending} onClick={() => { if (expanded) { setExpanded(false); setReview(null); setLogs(null); setCopyStatus(''); setStage('select'); void invoke('modelDiagnostics.close'); } else void invoke('modelDiagnostics.open'); }}>诊断与运行日志</Button>
    {expanded && <div className="pw-model-diagnostics-body">
      <div className="pw-diagnostics-workbench"><section className="pw-diagnostics-logs"><h3>最近运行记录</h3>
      <p className="pw-field-hint">先读取本机记录，定位失败阶段。</p>
      <div className="pw-form-actions"><Button variant="secondary" disabled={!ready || pending || !supported('modelDiagnostics.logs')} onClick={() => { setCopyStatus(''); void invoke('modelDiagnostics.logs').then(result => { if (result && Array.isArray(result.events)) setLogs(result); }); }}>读取最近日志</Button>{logs && <Button variant="secondary" disabled={!ready || pending} onClick={() => { void copyLogs(); }}>复制日志</Button>}</div>
      {logs && <><RuntimeAttemptChain events={logs.events}/><p className="pw-field-hint">本次读取 {logs.retainedEvents || 0} 条，最多保留 {logs.maxEvents || 256} 条；本机日志文件总上限 512 KiB。</p>{(logs.fileWriteFailures > 0 || logs.droppedFileWrites > 0) && <p role="status">部分记录未写入文件，可先复制当前日志，并补充 Logcat。</p>}<Accordion variant="divided"><AccordionItem id="runtime-log" title="查看本次日志"><pre className="pw-source-code" tabIndex={0}>{logText}</pre></AccordionItem></Accordion></>}
      {copyStatus && <p role="status">{copyStatus}</p>}
      <Accordion variant="divided"><AccordionItem id="runtime-log-scope" title="日志内容与保存范围"><p className="pw-field-hint">包含最近的启动、登录、消息、模型与工具运行阶段、错误码和耗时。保存在本机，不含账号凭据、聊天正文或文件内容。</p></AccordionItem></Accordion>
      </section><section className="pw-diagnostics-probe">
      <DiagnosticWorkflowSteps stage={review ? 'review' : stage}/>
      <div className="pw-affect-workflow pw-affect-stepbody">
      <h3>文本、工具与合成图片实测 · {data.model || '原 API 模型'}</h3>
      <p className="pw-field-hint">测试保留的 API 连接。最多 2 次请求；选择合成图片后最多 3 次，会消耗该连接额度。只发送随机合成内容和一个声明但不执行的函数，不使用聊天历史或手机画面。</p>
      <p className="pw-field-hint">全部阶段共用原连接的 {data.requestTimeoutSeconds || 60} 秒期限。离开此页停止后续探测，已发生的请求用量仍保留。</p>
      <CheckboxField isSelected={image} isDisabled={!ready || busy} onChange={next => { setImage(next); setReview(null); setAcknowledge(false); setStage('select'); }}>增加一张合成图片（不读取屏幕）</CheckboxField>
      <div className="pw-form-actions"><Button variant="secondary" disabled={!ready || busy || !data.canProbe} onClick={() => { void invoke('modelDiagnostics.review', { includeImage: image }).then(result => { if (result?.reviewHandle) { setReview(result); setAcknowledge(false); setStage('review'); } }); }}>核对本次测试</Button>{data.busy && <Button variant="secondary" disabled={!ready || pending} onClick={() => { void invoke('modelDiagnostics.stop'); }}>停止</Button>}</div>
      {data.busy && <p role="status">正在测试原连接，请等待实际结果…</p>}
      {probe.model && <ProbeObservationCard key={`${probe.model}:${probe.at || ''}:${probe.configurationDigest || ''}`} probe={probe}/>}
      <p className="pw-field-hint">通过只说明本次合成结构，不能证明全部工具、JSON schema 或模型质量。未证实不表示永久不支持，也不会覆盖目录声明。</p>
      </div>
      </section></div>
    </div>}
    {(error || data.error) && <p className="pw-field-error" role="alert">{error || data.error}</p>}
    <SettingsDialog isOpen={!!review} title="确认模型能力实测" onClose={closeReview}>{review && <><p>{review.model} · 最多 {review.maxRequests} 次请求，会消耗原 API 连接额度。</p><p>仅包含合成文本、声明但不执行的测试函数{review.includeImage ? '与合成图片' : ''}。全部请求共用 {review.requestTimeoutSeconds} 秒期限。</p><CheckboxField isSelected={acknowledge} onChange={setAcknowledge} isDisabled={busy}>我已核对本次范围与额度消耗</CheckboxField><div className="pw-form-actions"><Button variant="secondary" onClick={closeReview} disabled={busy}>取消</Button><Button disabled={!ready || busy || !acknowledge} onClick={() => { const original = review; setReview(null); setAcknowledge(false); void invoke('modelDiagnostics.start', { reviewHandle: original.reviewHandle, acknowledge: true }).then(result => { if (result?.started === true) setStage('observe'); else if (alive.current) setStage('select'); }); }}>明确测试这项 API（最多{review.maxRequests}次）</Button></div></>}</SettingsDialog>
  </section>;
}
