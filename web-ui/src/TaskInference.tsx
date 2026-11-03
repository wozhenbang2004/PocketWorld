import { useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Accordion, AccordionItem, Button } from './forms-library';
type Data = Record<string, any>;
const list = (value: any): any[] => Array.isArray(value) ? value : [];
function Counters({ values }: { values: any }) { return <dl className="pw-facts">{list(values).map(row => <div key={row.name}><dt>{row.name}</dt><dd>{row.text}</dd></div>)}</dl>; }

export function TaskInference({ taskId }: { taskId: string }) {
  const [value, setValue] = useState<Data | null>(null), [busy, setBusy] = useState(false), [error, setError] = useState(''), [estimate, setEstimate] = useState<Data | null>(null);
  const alive = useRef(true), entered = useRef(false), handle = useRef(0);
  useEffect(() => { alive.current = true; return () => { alive.current = false; handle.current++; }; }, []);
  const read = async (cursor = 0) => {
    if (entered.current) return; entered.current = true; const request = ++handle.current; setBusy(true); setError('');
    try { const result = await bridge.command('usage.task', { taskId, cursor }); if (alive.current && handle.current === request) { if (result.taskId !== taskId || result.readOnly !== true || result.executionAuthorized !== false) throw new Error('原任务用量身份不可核对。'); setValue(result); setEstimate(null); } }
    catch (failure) { if (alive.current) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { entered.current = false; if (alive.current) setBusy(false); }
  };
  return <section className="pw-task-inference" aria-label="原任务模型调用与用量"><div className="pw-action-row"><Button variant="ghost" disabled={busy} onClick={() => { void read(); }}>{value ? '重新核对累计用量' : '查看累计模型调用与用量'}</Button></div>
    {error && <p role="alert">{error}</p>}{value && <><h3>{value.headline}</h3><p className="pw-muted">{value.coverage}</p>{list(value.warnings).map((warning, i) => <p key={i}>{warning}</p>)}
      <Accordion variant="divided"><AccordionItem id="native-usage-cost" title="费用估算与未知项">{list(value.money).map((line, i) => <p key={i}>{line}</p>)}<p className="pw-muted">历史估算沿原冻结价目保留。实际账单和套餐剩余量未观察；服务冷却时间不是套餐重置时间。</p></AccordionItem>
        <AccordionItem id="native-usage-context" title={value.context?.title || '原上下文信息'}>{list(value.context?.lines).map((line, i) => <p key={i}>{line}</p>)}</AccordionItem>
        {list(value.groups).map((group, index) => <AccordionItem key={index} id={'native-usage-group-' + index} title={group.title} description={group.route}><Counters values={group.counters} /><p className="pw-muted">{group.semantics}</p></AccordionItem>)}
        {list(value.calls).map(call => <AccordionItem key={call.id} id={call.id} title={call.purpose + ' · ' + call.status} description={call.route}><p className="pw-muted">{call.binding} · {call.at ? new Date(call.at).toLocaleString() : '时间未记录'}</p><Counters values={call.counters} />{list(call.pricing).map((line, i) => <p key={i}>{line}</p>)}{list(call.failure).map((line, i) => <p key={i}>{line}</p>)}{call.apiKey && <Button variant="ghost" disabled={busy} onClick={() => { if (entered.current) return; entered.current = true; setBusy(true); setError(''); void bridge.command('usage.reestimate', { taskId, callId: call.id }).then(result => { if (alive.current) setEstimate({ callId: call.id, result }); }).catch(failure => { if (alive.current) setError(failure.message); }).finally(() => { entered.current = false; if (alive.current) setBusy(false); }); }}>按当前价目另行试算</Button>}{estimate?.callId === call.id && <details open><summary>本次另行试算，历史未改变</summary><pre className="pw-source-copy" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{JSON.stringify(estimate.result, null, 2)}</pre></details>}</AccordionItem>)}
      </Accordion><div className="pw-action-row">{value.cursor > 0 && <Button variant="ghost" disabled={busy} onClick={() => { void read(Math.max(0, value.cursor - 20)); }}>较近调用</Button>}{value.nextCursor >= 0 && <Button variant="ghost" disabled={busy} onClick={() => { void read(value.nextCursor); }}>较早调用</Button>}</div></>}
  </section>;
}
