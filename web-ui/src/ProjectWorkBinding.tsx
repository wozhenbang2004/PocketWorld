import { useEffect, useRef, useState } from 'react';
import { Accordion, AccordionItem, Button } from './forms-library';
import { bridge } from './bridge';
import { projectPackageTaskIdentity } from './project-preview';

type Data = Record<string, any>;
const labels: Record<string, string> = { node: 'Node.js', gradle: 'Gradle', python: 'Python', java: 'Java', static: '静态网页' };
const sha = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{64}$/.test(v);
const destination = '改动留在这份私人副本。单独保存的文件或固定包可回原任务的概览查看，不会自动写回正式文件或发布。';

function guide(value: any, project: string, checkout: string, digest?: string): value is Data {
  return value?.readOnly === true && value.projectId === project && value.checkoutId === checkout &&
    value.runtimeId === 'managed_process_v1' && sha(value.sourceDigest) && (!digest || value.sourceDigest === digest) &&
    typeof value.runtimeReady === 'boolean' && Array.isArray(value.observedTools) && value.observedTools.every((v: unknown) => typeof v === 'string') &&
    Array.isArray(value.kinds) && value.kinds.every((v: unknown) => typeof v === 'string' && Object.hasOwn(labels, v));
}
export function checkoutKindSummary(source: Data | null, project: string, checkout: string): string | null {
  const value = source?.checkoutTooling;
  if (!source || !sha(source.sourceDigest) || !guide(value, project, checkout, source.sourceDigest) || !value.kinds.length) return null;
  return value.kinds.map((kind: string) => labels[kind]).join('、') + (['npm', 'pnpm'].includes(value.manager) ? ' · 候选 ' + value.manager : '');
}
function ToolObservation({ facts }: { facts: Data }) {
  const kinds: string[] = facts.kinds, relevant = new Set<string>();
  if (kinds.includes('node')) { relevant.add('node'); if (facts.manager === 'npm' || facts.manager === 'pnpm') relevant.add(facts.manager); }
  if (kinds.includes('python')) { relevant.add('python3'); relevant.add('sh'); }
  if (kinds.includes('gradle')) { relevant.add('gradle'); relevant.add('java'); }
  if (kinds.includes('java')) { relevant.add('javac'); relevant.add('java'); }
  const observed = [...relevant].filter(name => facts.observedTools.includes(name)), missing = [...relevant].filter(name => !facts.observedTools.includes(name));
  return <><div><dt>副本内容</dt><dd>{kinds.length ? kinds.map(kind => labels[kind]).join('、') : '暂未识别项目类型'}{['npm', 'pnpm'].includes(facts.manager) && '；原方案候选 ' + facts.manager}{facts.manager === 'unsupported_or_conflicting' ? '；包管理器不受支持，或声明与锁文件冲突。' : facts.manager === 'not_checked' ? '；未能核对包管理器声明。' : ''}</dd></div>
    <div><dt>本机工具</dt><dd>{observed.length > 0 && '已观察到 ' + observed.join('、') + '。'}{missing.length > 0 && '未观察到 ' + missing.join('、') + '。'}{!relevant.size && (kinds.includes('static') ? '静态入口不要求编译。' : '尚不能确定这份源码需要哪些工具。')}
      {facts.runtimeReady === false && '本机运行环境尚未就绪。'}{facts.androidClues === true && 'Android SDK 与 ARM64 构建工具尚未核验。'}这些不是已执行的命令。</dd></div></>;
}

/** The selected checkout observation is not a plan, grant or proof that an artifact exists. */
export function CheckoutGuide({ source, projectId, checkoutId }: { source: Data; projectId: string; checkoutId: string }) {
  const facts = source.checkoutTooling;
  return <>{source.projectId === projectId && source.checkoutId === checkoutId && sha(source.sourceDigest) && guide(facts, projectId, checkoutId, source.sourceDigest)
    ? <ToolObservation facts={facts}/> : <div><dt>本机工具</dt><dd>没有取得这份副本的工具观察，具体命令须另外核对。</dd></div>}
    <div><dt>成果去向</dt><dd>{destination}</dd></div></>;
}

/** Only the consumed native review identifies this task. A current source read is optional;
 * an old, missing, or Termux selection must not be labeled as the phone's managed checkout. */
export function ContinuedProjectBinding({ task, projects, read }: { task: Data; projects: { id: string; name: string }[]; read?: (args: Data) => Promise<any> }) {
  const target = projectPackageTaskIdentity(task), raw = task.raw || {}, context = raw.developmentContext || {};
  const { taskId = '', projectId = '', checkoutId = '' } = target || {};
  const [facts, setFacts] = useState<Data | null>(null), [error, setError] = useState(''), [busy, setBusy] = useState(false);
  const ticket = useRef(0), pending = useRef(false);
  const windowKey = () => { const v = bridge.getState().view as Data; return [bridge.getDocumentEpoch(), v.taskId, v.section, v.resumeRevision].join('|'); };
  useEffect(() => {
    let previous = windowKey();
    const retire = () => { ticket.current++; pending.current = false; setBusy(false); setFacts(null); setError(''); };
    const inspect = () => { const next = windowKey(); if (next !== previous || document.hidden) { previous = next; retire(); } };
    const stop = bridge.subscribe(inspect); document.addEventListener('visibilitychange', inspect); window.addEventListener('pagehide', retire);
    return () => { ticket.current++; pending.current = false; stop(); document.removeEventListener('visibilitychange', inspect); window.removeEventListener('pagehide', retire); };
  }, []);
  if (!target || !context.runtime) return <p className="pw-muted">原记录没有完整的项目、副本或运行位置；不会用当前打开的项目代替。</p>;
  const selected = projects.find(p => p.id === projectId)?.name;
  const observe = async () => {
    const v = bridge.getState().view as Data;
    if (!read || context.runtime !== 'managed_process_v1' || document.hidden || v.section != null || v.taskId !== taskId || pending.current) return;
    const seq = ++ticket.current, window = windowKey(); pending.current = true; setBusy(true); setFacts(null); setError('');
    const current = () => ticket.current === seq && windowKey() === window && !document.hidden;
    try {
      const view = await read({ taskId, checkoutGuide: true });
      if (!current()) return;
      if (view?.taskId !== taskId || view.runId !== raw.runId || view.epoch !== raw.coordinatorEpoch || view.readOnly !== true || !guide(view.checkoutTooling, projectId, checkoutId))
        throw new Error('副本、工具或原运行已经变化，请重新核对。');
      setFacts(view.checkoutTooling);
    } catch (failure) { if (current()) setError(failure instanceof Error ? failure.message : '原副本暂时不可核对。'); }
    finally { if (ticket.current === seq) { pending.current = false; setBusy(false); } }
  };
  const runtime = context.runtime === 'managed_process_v1' ? '本机托管环境' : context.runtime === 'termux' ? '手机上的外部 Termux' : '原记录的运行位置未能识别';
  return <Accordion variant="divided"><AccordionItem id="continue-project-location" title={selected || '原项目与交付位置'} description={runtime + ' · 原私人副本'}>
    <dl className="pw-facts"><div><dt>原项目</dt><dd dir="auto">{projectId}</dd></div><div><dt>私人副本</dt><dd dir="auto">{checkoutId}</dd></div>
      {sha(context.initialSourceDigest) && <div><dt>首次开工版本</dt><dd>{context.initialSourceDigest}</dd></div>}
      {facts && <><div><dt>刚读取的副本版本</dt><dd>{facts.sourceDigest}{sha(context.initialSourceDigest) && context.initialSourceDigest !== facts.sourceDigest && '（已不同于首次开工）'}</dd></div><ToolObservation facts={facts}/></>}
      <div><dt>成果去向</dt><dd>{destination}</dd></div></dl>
    {read && context.runtime === 'managed_process_v1' && <Button variant="ghost" disabled={busy} onClick={() => void observe()}>{busy ? '正在读取…' : '核对当前副本与工具'}</Button>}
    {error && <p role="alert" className="pw-muted">{error}</p>}
  </AccordionItem></Accordion>;
}
