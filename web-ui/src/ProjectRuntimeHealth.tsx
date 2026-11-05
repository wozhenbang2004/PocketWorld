import { useEffect, useRef, useState } from 'react';
import { Accordion, AccordionItem, Button } from './forms-library';
type Data = Record<string, any>;

export function ProjectRuntimeHealth({ taskId, run }: { taskId: string; run: (action: string, args?: Data) => Promise<any> }) {
  const [view, setView] = useState<Data | null>(null), [busy, setBusy] = useState(false), [error, setError] = useState('');
  const alive = useRef(true), generation = useRef(0), pending = useRef(false), version = generation.current;
  const read = async () => {
    if (!alive.current || version !== generation.current || pending.current) return;
    const ticket = generation.current; pending.current = true; setBusy(true); setError('');
    try { const result = await run('projectRuntime.read', { taskId }); if (alive.current && ticket === generation.current) { if (result.taskId !== taskId || result.readOnly !== true) throw new Error('原环境观察身份不可核对'); setView(result); } }
    catch (failure) { if (alive.current && ticket === generation.current) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { if (ticket === generation.current) { pending.current = false; if (alive.current) setBusy(false); } }
  };
  useEffect(() => { alive.current = true; const hide = () => { if (document.hidden) { generation.current++; pending.current = false; setBusy(false); setView(null); } }; document.addEventListener('visibilitychange', hide); return () => { alive.current = false; generation.current++; document.removeEventListener('visibilitychange', hide); }; }, [taskId]);
  return <section><div className="pw-section-heading"><div><h3>{view?.headline || '手机开发工具与恢复范围'}</h3><p className="pw-muted">{view?.caption || '读取原项目的现用版本、准备阶段与保留范围。'}</p></div><Button variant="ghost" disabled={busy} onClick={() => void read()}>{view ? '刷新环境观察' : '查看环境观察'}</Button></div>
    {error && <p role="alert">{error}</p>}{view && <Accordion><AccordionItem title="版本与恢复详情" description={view.phase ? `当前阶段：${view.phase}` : undefined}>{(view.details || []).map((line: string, index: number) => <p key={index} style={{ overflowWrap: 'anywhere' }}>{line}</p>)}{typeof view.fraction === 'number' && !view.executable && <progress max={1} value={view.fraction} aria-label="原工具准备观察进度" />}{view.resumableDownload && <p>原下载可续传；继续准备仍需核对原项目任务。</p>}<p>{view.admissionMessage}</p><p>这里只读取已有状态。工具准备、切换环境与实际执行继续通过原项目任务审阅。</p></AccordionItem></Accordion>}
  </section>;
}
