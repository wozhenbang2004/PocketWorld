import { useEffect, useRef, useState } from 'react';
import { Accordion, AccordionItem, Button } from './forms-library';
import { ProgressBar } from './library';
import './project-runtime.css';
type Data = Record<string, any>;

export function ProjectRuntimeHealth({ taskId, run, onPrepareTools }: { taskId: string; run: (action: string, args?: Data) => Promise<any>; onPrepareTools?: () => void }) {
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
  return <section className="pw-project-runtime-health" aria-busy={busy}><div className="pw-section-heading"><div><h3>{view?.headline || '手机开发工具与恢复范围'}</h3><p className="pw-muted">{view?.caption || '读取原项目的现用版本、准备阶段与保留范围。'}</p></div><Button variant="ghost" disabled={busy} onClick={() => void read()}>{view ? '刷新环境观察' : '查看环境观察'}</Button></div>
    {onPrepareTools && <Button variant="secondary" onClick={onPrepareTools}>准备与修复开发工具</Button>}
    {error && <p role="alert" className="pw-error">{error}</p>}{view && <Accordion><AccordionItem title="版本与恢复详情" description={view.phase ? `当前阶段：${view.phase}` : undefined}>{(view.details || []).map((line: string, index: number) => <p key={index} style={{ overflowWrap: 'anywhere' }}>{line}</p>)}{typeof view.fraction === 'number' && !view.executable && <ProgressBar maxValue={1} value={view.fraction} label="原工具准备观察进度" size="sm"/>}{view.resumableDownload && <p>下载支持续传；请在准备中心核对当前安装状态后继续。</p>}<p>{view.admissionMessage}</p><p>这里显示已有运行状态。准备中心可单独安装或修复开发工具；项目执行仍保留自己的任务范围。</p></AccordionItem></Accordion>}
  </section>;
}
