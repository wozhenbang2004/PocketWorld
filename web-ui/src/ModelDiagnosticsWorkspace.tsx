import { useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Accordion, AccordionItem, Button, CheckboxField, SettingsDialog } from './forms-library';

type Data = Record<string, any>;
type Props = { state: Data; supported: (command: string) => boolean };
const object = (value: any): Data => value && typeof value === 'object' && !Array.isArray(value) ? value : {};
const status = (value: string) => ({ passed: '本次通过', unconfirmed: '未证实', failed: '未证实', not_requested: '未测试' }[value] || '未完成');

/** Bounded, explicitly approved synthetic requests through the original model service. */
export function ModelDiagnosticsWorkspace({ state, supported }: Props) {
  const data = object(state.modelDiagnostics), probe = object(data.probe);
  const [expanded, setExpanded] = useState(false), [image, setImage] = useState(false), [acknowledge, setAcknowledge] = useState(false);
  const [review, setReview] = useState<Data | null>(null), [pending, setPending] = useState(false), [error, setError] = useState('');
  const windowId = useRef(''), alive = useRef(true), sequence = useRef(0), inFlight = useRef(false);
  const epoch = bridge.getDocumentEpoch(), previousEpoch = useRef(epoch);
  const previousSelection = useRef(data.probeSelectionRevision);
  const active = state.webWorkspaceForeground !== false && document.visibilityState === 'visible';
  const available = supported('modelDiagnostics.open');
  useEffect(() => {
    alive.current = true;
    const hidden = () => { if (document.visibilityState !== 'visible') { sequence.current++; setReview(null); setAcknowledge(false); setExpanded(false); } };
    document.addEventListener('visibilitychange', hidden);
    return () => { alive.current = false; sequence.current++; document.removeEventListener('visibilitychange', hidden); const id = windowId.current; windowId.current = ''; if (id) void bridge.command('modelDiagnostics.close', { windowId: id }).catch(() => {}); };
  }, []);
  useEffect(() => { if (previousEpoch.current !== epoch || !active || !data.ready) { previousEpoch.current = epoch; sequence.current++; setReview(null); setAcknowledge(false); if (!active) setExpanded(false); } }, [epoch, active, data.ready, data.probeSelectionRevision]);
  useEffect(() => {
    if (previousSelection.current !== undefined && previousSelection.current !== data.probeSelectionRevision) sequence.current++;
    previousSelection.current = data.probeSelectionRevision; setReview(null); setAcknowledge(false);
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
  const closeReview = () => { setReview(null); setAcknowledge(false); if (windowId.current) void invoke('modelDiagnostics.discard'); };
  const busy = pending || !!data.busy, ready = active && data.ready === true && data.windowId === windowId.current;
  return <section className="pw-model-diagnostics">
    <Button variant="ghost" disabled={!available || pending} onClick={() => { if (expanded) { setExpanded(false); setReview(null); void invoke('modelDiagnostics.close'); } else void invoke('modelDiagnostics.open'); }}>文本、工具与合成图片实测</Button>
    {expanded && <div className="pw-model-diagnostics-body"><h3>{data.model || '原 API 模型'}</h3>
      <p className="pw-field-hint">测试保留的 API 连接。最多 2 次请求；选择合成图片后最多 3 次，会消耗该连接额度。只发送随机合成内容和一个声明但不执行的函数，不使用聊天历史或手机画面。</p>
      <p className="pw-field-hint">全部阶段共用原连接的 {data.requestTimeoutSeconds || 60} 秒期限。离开此页停止后续探测，已发生的请求用量仍保留。</p>
      <CheckboxField isSelected={image} isDisabled={!ready || busy} onChange={next => { setImage(next); setReview(null); setAcknowledge(false); }}>增加一张合成图片（不读取屏幕）</CheckboxField>
      <div className="pw-form-actions"><Button variant="secondary" disabled={!ready || busy || !data.canProbe} onClick={() => { void invoke('modelDiagnostics.review', { includeImage: image }).then(result => { if (result?.reviewHandle) { setReview(result); setAcknowledge(false); } }); }}>核对本次测试</Button>{data.busy && <Button variant="secondary" disabled={!ready || pending} onClick={() => { void invoke('modelDiagnostics.stop'); }}>停止</Button>}</div>
      {data.busy && <p role="status">正在测试原连接，请等待实际结果…</p>}
      {probe.model && <div><p>文本：{status(probe.text)} · 工具：{status(probe.tools)} · 图片：{status(probe.image)}</p><p className="pw-field-hint">{probe.model} · 本次精确连接修订的独立观察</p><Accordion variant="divided"><AccordionItem id="probe" title="查看本次观测与用量引用"><pre className="pw-source-code">{JSON.stringify(probe, null, 2)}</pre></AccordionItem></Accordion></div>}
      <p className="pw-field-hint">通过只说明本次合成结构，不能证明全部工具、JSON schema 或模型质量。未证实不表示永久不支持，也不会覆盖目录声明。</p>
    </div>}
    {(error || data.error) && <p className="pw-field-error" role="alert">{error || data.error}</p>}
    <SettingsDialog isOpen={!!review} title="确认模型能力实测" onClose={closeReview}>{review && <><p>{review.model} · 最多 {review.maxRequests} 次请求，会消耗原 API 连接额度。</p><p>仅包含合成文本、声明但不执行的测试函数{review.includeImage ? '与合成图片' : ''}。全部请求共用 {review.requestTimeoutSeconds} 秒期限。</p><CheckboxField isSelected={acknowledge} onChange={setAcknowledge} isDisabled={busy}>我已核对本次范围与额度消耗</CheckboxField><div className="pw-form-actions"><Button variant="secondary" onClick={closeReview} disabled={busy}>取消</Button><Button disabled={!ready || busy || !acknowledge} onClick={() => { const original = review; setReview(null); setAcknowledge(false); void invoke('modelDiagnostics.start', { reviewHandle: original.reviewHandle, acknowledge: true }); }}>明确测试这项 API（最多{review.maxRequests}次）</Button></div></>}</SettingsDialog>
  </section>;
}
