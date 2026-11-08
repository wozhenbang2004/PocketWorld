import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { ExperienceWorkspace, LearningObjectRow } from './ExperienceWorkspace';
import { Accordion, AccordionItem, Button, CheckboxField, SearchField, SelectField, SettingsDialog } from './forms-library';
import { AIAnswer } from './library';
import { Tabs, TabList, Tab, TabPanel } from './shell-library';
import { ArrowLeft, BookOpen, Plus, RefreshCw } from 'lucide-react';
import './learning-workspace.css';
import './knowledge-depth.css';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
export type LearningCandidateTarget = { id: string; revision: number; taskId: string; sourceTitle: string };
const rows = (value: any): Data[] => Array.isArray(value) ? value : [];
const title = (value: Data | null) => value?.job?.inputs?.[0]?.title || '来源学习任务';
const taskStatus = (value: string) => ({ preparing: '准备中', running: '提炼中', stopping: '正在停止', completed: '已完成', failed: '需要核对', cancelled: '已取消', interrupted: '已中断', needs_confirmation: '等待确认', awaiting_user: '等待处理' } as Data)[value] || value || '状态待核对';
const jobGroup = (job: Data) => {
  const status = job.jobStatus || job.taskStatus;
  return ['preparing', 'running', 'stopping'].includes(status) ? 'active' : ['awaiting_user', 'needs_confirmation', 'failed', 'interrupted'].includes(status) ? 'attention' : ['completed', 'cancelled'].includes(status) ? 'finished' : 'other';
};
// Reading context is retained, never the authority to reuse an old native window.
let saved: { current: Data | null; taskId: string; episode: Data | null; sources: Data[]; operation: Data | null; createAttempted: boolean } | null = null;
export function learningBatchStatus(job: Data, index: number): string {
  const result = rows(job.results)[index];
  return result?.candidateRef ? '候选已登记，等待审阅' : result?.outcome === 'no_candidate' ? '依据不足，未形成候选' : result ? '已明确跳过，未知用量保留' : index === job.nextIndex ? '当前批次' : '尚未处理';
}
export function learningOperationUnresolved(operation: Data | null): boolean {
  return !!operation && (!operation.settled || !!operation.queryFailed || !!operation.error || !!operation.needsRead || !operation.result);
}
export function learningStopRequest(current: Data | null, operation: Data | null): { action: string; args: Data } | null {
  if (!current?.taskId || operation && learningOperationUnresolved(operation) && operation.taskId !== current.taskId) return null;
  if (operation && operation.taskId === current.taskId && !operation.settled && !operation.stale && operation.operationHandle) return { action: 'learning.operation.stop', args: { operationHandle: operation.operationHandle } };
  if (current.stale || typeof current.runId !== 'string' || !Number.isInteger(current.epoch) || current.epoch < 0) return null;
  return { action: 'learning.stop', args: { taskId: current.taskId, runId: current.runId, epoch: current.epoch } };
}

/** One owner workspace. Local tabs never leave the native learning window. */
export function LearningWorkspace({ state, data, run, registerTransientBack, onReviewCandidate }: { state: Data; data: Data; run: Run; registerTransientBack: (handler: (() => boolean) | null) => void; onReviewCandidate: (target: LearningCandidateTarget) => void }) {
  const [panel, setPanel] = useState('jobs'), [view, setView] = useState(saved?.current ? 'job' : 'list');
  const [jobs, setJobs] = useState<Data[]>([]), [loaded, setLoaded] = useState(false), [cursor, setCursor] = useState('');
  const [query, setQuery] = useState(''), [filter, setFilter] = useState('all');
  const [current, setCurrent] = useState<Data | null>(saved?.current || null), [taskId, setTaskId] = useState(saved?.taskId || '');
  const [episode, setEpisode] = useState<Data | null>(saved?.episode || null), [sources, setSources] = useState<Data[]>(saved?.sources || []);
  const [retained, setRetained] = useState(false), [createAttempted, setCreateAttempted] = useState(saved?.createAttempted || false);
  const [operation, setOperation] = useState<Data | null>(saved?.operation || null), [review, setReview] = useState<Data | null>(null);
  const [acknowledged, setAcknowledged] = useState(false), [continuous, setContinuous] = useState(false);
  const [busy, setBusy] = useState(false), [stopping, setStopping] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('');
  const root = useRef<HTMLElement | null>(null), positions = useRef<Record<string, number>>({}), focus = useRef<HTMLElement | null>(null);
  const experienceBack = useRef<(() => boolean) | null>(null);
  const registerExperienceBack = useCallback((handler: (() => boolean) | null) => { experienceBack.current = handler; }, []);
  const alive = useRef(true), entered = useRef(false), originalTask = useRef(current?.taskId || ''), generation = useRef(0), windowEpoch = useRef(0);
  const location = `${panel}:${view}`, latest = useRef({ current, episode, sources, operation }); latest.current = { current, episode, sources, operation };
  const ownerTicket = () => { const native = bridge.getState().view as Data | undefined; return `${bridge.getDocumentEpoch()}:${native?.section}:${native?.taskId || ''}:${native?.resumeRevision || 0}`; };
  const nativeActive = () => { const native = bridge.getState().view as Data | undefined; return document.visibilityState === 'visible' && native?.section === 'learning' && !native?.taskId; };
  const rememberPosition = () => { positions.current[location] = root.current?.closest<HTMLElement>('.pw-secondary-scroll')?.scrollTop || 0; };
  const navigate = (nextPanel: string, nextView = view) => { rememberPosition(); setPanel(nextPanel); setView(nextView); };
  useLayoutEffect(() => { const scroller = root.current?.closest<HTMLElement>('.pw-secondary-scroll'); if (scroller) scroller.scrollTop = positions.current[location] || 0; if (panel === 'jobs' && view === 'list') focus.current?.focus({ preventScroll: true }); }, [location]);
  const invoke = async (action: string, args: Data = {}, accept?: (result: Data) => void) => {
    if (entered.current || !nativeActive()) return; entered.current = true; const request = ++generation.current; setBusy(true); setError('');
    try { const result = await run(action, args); if (alive.current && request === generation.current && nativeActive()) accept?.(result); return result; }
    catch (failure) { if (alive.current && request === generation.current) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { if (request === generation.current) { entered.current = false; if (alive.current) setBusy(false); } }
  };
  const loadJobs = (next = '') => { void invoke('learning.jobs', { cursor: next }, value => { setJobs(old => next ? Array.from(new Map([...old, ...rows(value.jobs)].map(job => [job.taskId, job])).values()) : rows(value.jobs)); setCursor(value.hasMore ? value.nextCursor : ''); setLoaded(true); }); };
  const running = !!operation && !operation.settled && !operation.stale;
  const unresolved = learningOperationUnresolved(operation);
  const selectJob = (id: string) => {
    if (entered.current || unresolved && operation?.taskId !== id) return;
    void invoke('learning.job', { taskId: id }, value => { if (value.taskId !== id) throw new Error('原学习任务身份不符。'); originalTask.current = id; setCurrent(value); setJobs(old => old.map(row => row.taskId === id ? value : row)); setOperation(old => old && old.taskId !== id && !learningOperationUnresolved(old) ? null : old?.unknownStart ? { ...old, stale: true } : old); navigate('jobs', 'job'); setNotice(''); });
  };
  const closeReview = () => { if (review?.reviewHandle) void run('knowledge.close', { handle: review.reviewHandle }).catch(() => {}); setReview(null); setAcknowledged(false); };
  const invalidate = () => {
    generation.current++; windowEpoch.current++; entered.current = false; setBusy(false); setStopping(false); setReview(null); setAcknowledged(false);
    setCurrent(old => old ? { ...old, stale: true } : old);
    setEpisode(old => old ? { ...old, reviewHandle: '', stale: true } : old);
    setSources(old => old.map(source => ({ ...source, sourceHandle: '', stale: true })));
    setOperation(old => old ? { ...old, operationHandle: '', stale: true } : old);
  };
  useEffect(() => {
    alive.current = true; let owner = ownerTicket(), loadedOwner = '';
    // connections.enter arrives asynchronously after this component mounts.
    // Read the collection once per valid owner window, never replay a mutation.
    const loadOwner = () => { const ticket = ownerTicket(); if (nativeActive() && loadedOwner !== ticket) { loadedOwner = ticket; loadJobs(); } };
    const hide = () => { if (!nativeActive()) invalidate(); else loadOwner(); };
    const unsubscribe = bridge.subscribe(() => { const incoming = ownerTicket(); if (incoming !== owner) { owner = incoming; invalidate(); } loadOwner(); });
    loadOwner();
    document.addEventListener('visibilitychange', hide); window.addEventListener('pagehide', invalidate);
    return () => { alive.current = false; generation.current++; windowEpoch.current++; unsubscribe(); document.removeEventListener('visibilitychange', hide); window.removeEventListener('pagehide', invalidate); };
  }, []);
  useEffect(() => { saved = { current: current ? { ...current, stale: true } : null, taskId, episode: episode ? { ...episode, reviewHandle: '', stale: true } : null, sources: sources.map(source => ({ ...source, sourceHandle: '', stale: true })), operation: operation ? { ...operation, operationHandle: '', stale: true } : null, createAttempted }; }, [current, taskId, episode, sources, operation, createAttempted]);
  useEffect(() => { registerTransientBack(() => {
    if (review) { if (!busy) closeReview(); return true; }
    if (experienceBack.current?.()) return true;
    if (panel !== 'jobs') { navigate('jobs'); return true; }
    if (view !== 'list') { navigate('jobs', 'list'); focus.current?.focus({ preventScroll: true }); return true; }
    return false;
  }); return () => registerTransientBack(null); }, [review, busy, panel, view, registerTransientBack]);
  const ended = rows(state.tasks).filter(task => ['assistant', 'phone'].includes(task.raw?.kind) && !task.raw?.origin && !task.raw?.sourceLearningJob && ['completed', 'failed', 'cancelled', 'interrupted', 'needs_confirmation', 'awaiting_user'].includes(task.status));
  const selected = ended.find(task => task.id === taskId);
  const stop = () => {
    if (stopping) return; const target = learningStopRequest(current, operation);
    if (!target) { setError('原停止身份已失效，请重新读取任务水位；不能据此断言旧调用已取消。'); return; }
    const task = current!.taskId, ticket = windowEpoch.current; setStopping(true);
    void run(target.action, target.args).then(value => { if (alive.current && ticket === windowEpoch.current && originalTask.current === task) { if (target.action === 'learning.stop') setCurrent(value); setNotice('已收到停止请求回执。请核对原批次结果，已有用量、草稿与未知结果保留。'); } }).catch(failure => { if (alive.current && ticket === windowEpoch.current) setError(failure.message); }).finally(() => { if (alive.current && ticket === windowEpoch.current) setStopping(false); });
  };
  const queryOperation = async (original: Data, ticket: number) => {
    try {
      const value = await run('learning.operation.query', { operationHandle: original.operationHandle });
      if (!alive.current || ticket !== windowEpoch.current || !nativeActive() || latest.current.operation?.operationHandle !== original.operationHandle) return;
      if (value.taskId !== original.taskId) throw new Error('原学习操作身份不符，结果待核对。');
      setOperation({ ...original, ...value, needsRead: false, queryFailed: false });
      if (value.result && value.taskId === originalTask.current) { if (value.result.taskId !== value.taskId) throw new Error('批次结果不属于原学习任务。'); setCurrent(value.result); setJobs(old => old.map(row => row.taskId === value.taskId ? value.result : row)); }
      if (value.error) setError(value.error);
    } catch (failure) { if (alive.current && ticket === windowEpoch.current) { setOperation(old => old?.operationHandle === original.operationHandle ? { ...old, queryFailed: true } : old); setError(failure instanceof Error ? failure.message : String(failure)); } }
  };
  useEffect(() => {
    if (!operation?.operationHandle || operation.stale || operation.queryFailed || operation.settled && !operation.needsRead) return;
    let active = true, reading = false; const ticket = windowEpoch.current;
    const query = () => { if (!active || reading || !nativeActive()) return; reading = true; void queryOperation(operation, ticket).finally(() => { reading = false; }); };
    query(); const timer = setInterval(query, 1500);
    return () => { active = false; clearInterval(timer); };
  }, [operation?.operationHandle, operation?.settled, operation?.needsRead, operation?.stale, operation?.queryFailed]);
  const openReview = (kind: 'run' | 'skip') => { if (!current || current.stale || running) return; void invoke(`learning.${kind}.review`, { taskId: current.taskId, ...(kind === 'run' ? { maxBatches: continuous ? 20 : 3 } : {}) }, value => { if (value.taskId !== current.taskId) throw new Error('原审阅不属于当前任务。'); setReview({ ...value, kind }); setAcknowledged(false); }); };
  const showCurrent = () => { if (operation?.taskId && current?.taskId !== operation.taskId) selectJob(operation.taskId); else navigate('jobs', 'job'); };
  const canEndOperationView = !!operation?.stale && !operation.operationHandle && !!current && current.taskId === operation.taskId && !current.stale && !busy && !stopping;
  const endOperationView = () => { if (!canEndOperationView) return; setOperation(null); setNotice('本地调用查看已结束。原任务、用量与未知结果继续保留；这不表示旧调用成功或已取消。'); };
  const attention = operation || current?.active || ['preparing', 'running', 'stopping'].includes(current?.taskStatus);
  const visibleJobs = jobs.filter(job => [title(job), ...rows(job.job?.inputs).map(source => source.title)].join(' ').toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()))
    .filter(job => filter === 'all' || (filter === 'open' ? jobGroup(job) !== 'finished' : jobGroup(job) === 'finished'));
  const jobGroups = [{ id: 'attention', label: '需要处理' }, { id: 'active', label: '正在提炼' }, { id: 'other', label: '其他任务' }, { id: 'finished', label: '已结束' }]
    .map(group => ({ ...group, jobs: visibleJobs.filter(job => jobGroup(job) === group.id) })).filter(group => group.jobs.length > 0);
  return <section ref={root} className="pw-learning-workspace pw-knowledge-workspace">
    <Tabs selectedKey={panel} onSelectionChange={key => navigate(String(key))} className="pw-learning-tabs">
      <TabList aria-label="学习与经验"><Tab id="jobs">学习任务</Tab><Tab id="procedures">方法</Tab><Tab id="skills">来源技能</Tab></TabList>
      {attention && <section className="pw-learning-activity" aria-label="当前学习调用">
        <div><strong>{operation?.title || title(current)}</strong><p>{operation?.unknownStart && !operation.operationHandle ? '模型请求的启动回执尚未确定，请重新读取原任务水位；没有重发请求。' : operation?.stale ? '旧调用窗口已失效，结果需从原任务核对。未自动重发，也不能认定已取消。' : operation?.queryFailed ? '原调用回执读取失败，结果待核对；没有重发模型请求。' : stopping ? '正在请求停止，尚未确认最终结果。' : running ? '正在等待原批次回执。切换分区不会取消调用。' : operation?.error || current?.active?.stage === 'inflight' ? operation?.error || '当前批次尚未取得确定草稿回执。' : current?.active?.stage === 'draft_recorded' ? ['contract_changed_requires_review', 'contract_unverifiable_requires_review'].includes(current?.active?.extractionContractStatus) ? '原草稿仍保留，但提炼规则已变化或无法核对，不能直接提交或重新发送模型。' : current?.active?.extractionContractStatus === 'legacy_contract_unknown' ? '旧版草稿已保存。仅按原格式核对同一候选，历史提炼策略仍未知，不重新调用模型。' : '原模型草稿已记录。继续会核对并提交同一候选，不重新发送。' : current?.active ? '旧调用结果未知，不计作未使用或退款。' : '原调用已返回。请在当前任务核对批次与候选。'}</p></div>
        <div className="pw-form-actions"><Button variant="ghost" onClick={showCurrent}>查看当前任务</Button>{operation?.operationHandle && !operation.stale && <Button variant="ghost" disabled={busy} onClick={() => { void queryOperation(operation, windowEpoch.current); }}>核对原调用回执</Button>}{operation?.stale && !operation.operationHandle && <Button variant="ghost" disabled={!canEndOperationView} onClick={endOperationView}>结束本地查看，原结果继续保留</Button>}{learningStopRequest(current, operation) && (running || ['preparing', 'running', 'stopping'].includes(current?.taskStatus)) && <Button variant="secondary" disabled={stopping} onClick={stop}>{stopping ? '停止请求中…' : '请求停止本次提炼'}</Button>}</div>
      </section>}
      {createAttempted && sources.length > 0 && <section className="pw-learning-activity" aria-label="来源清单建立回执"><div><strong>所选来源 · 建立回执待核对</strong><p>{busy ? '正在建立学习任务，尚未确认结果。' : '未确认这份清单的建立结果，不能重复提交。来源选择已保留。'}</p></div><Button variant="ghost" disabled={busy} onClick={() => { navigate('jobs', 'list'); loadJobs(); }}>核对原任务列表</Button><Button variant="ghost" onClick={() => navigate('jobs', 'sources')}>查看所选来源</Button></section>}
      {(error || busy || notice && panel === 'jobs') && <div className="pw-learning-feedback">{error && <p role="alert">{error}</p>}{notice && panel === 'jobs' && <p role="status">{notice}</p>}{busy && <p role="status">正在核对原操作回执…</p>}</div>}
      <ExperienceWorkspace state={state} data={data} run={run} registerTransientBack={registerExperienceBack} panel={panel} onNavigate={next => navigate(next)} />
      <TabPanel id="jobs" shouldForceMount hidden={panel !== 'jobs'} className="pw-learning-panel">
        <div hidden={view !== 'list'}>
          <header className="pw-learning-heading"><div><h2>学习任务</h2></div><div className="pw-form-actions"><Button variant="ghost" iconOnly leadingIcon={RefreshCw} aria-label="刷新学习任务" disabled={busy} onClick={() => loadJobs()} /><Button leadingIcon={Plus} onClick={() => { focus.current = document.activeElement as HTMLElement; navigate('jobs', 'sources'); }}>整理新来源</Button></div></header>
          {sources.length > 0 && <LearningObjectRow title={`继续整理 · ${sources.length} 份所选来源`} detail="保留上次阅读与选择，尚未发送模型" onClick={() => navigate('jobs', 'sources')} />}
          <SearchField className="pw-secondary-search" aria-label="查找已读取的学习任务" placeholder="查找任务或来源" value={query} onChange={setQuery} />
          <div className="pw-knowledge-filters" role="group" aria-label="学习任务状态筛选">{[{ id: 'all', label: '全部' }, { id: 'open', label: '待处理与进行中' }, { id: 'finished', label: '已结束' }].map(item => <Button key={item.id} variant="ghost" aria-pressed={filter === item.id} onClick={() => setFilter(item.id)}>{item.label}</Button>)}</div>
          {!jobs.length && <div className="pw-learning-empty"><BookOpen aria-hidden="true" /><h3>{loaded ? '还没有读取到学习任务' : '正在读取学习任务'}</h3><p>{loaded ? '整理一份已结束任务的来源即可开始。建立任务不会调用模型。' : '只读取已有记录，不会开始提炼。'}</p></div>}
          {jobs.length > 0 && !visibleJobs.length && <p className="pw-secondary-empty" role="status">已读取的任务中没有匹配项。可以调整关键词或筛选。</p>}
          {jobGroups.map(group => <section key={group.id} className="pw-knowledge-directory-group" aria-label={group.label}><header className="pw-knowledge-group-heading"><h3>{group.label}</h3><span>{group.jobs.length}</span></header><div className="pw-learning-list">{group.jobs.map(job => <LearningObjectRow key={job.taskId} title={title(job)} detail={`${taskStatus(job.jobStatus || job.taskStatus)} · 已处理 ${job.job?.nextIndex ?? 0} / ${rows(job.job?.inputs).length} 份来源`} disabled={busy || unresolved && operation?.taskId !== job.taskId} selected={current?.taskId === job.taskId} onClick={() => { focus.current = document.activeElement as HTMLElement; if (current && current.taskId === job.taskId && !current.stale) showCurrent(); else selectJob(job.taskId); }} />)}</div></section>)}
          {cursor && <Button variant="ghost" disabled={busy} onClick={() => loadJobs(cursor)}>读取较早任务</Button>}
        </div>
        <div hidden={view !== 'sources'}>
          <Button variant="ghost" leadingIcon={ArrowLeft} onClick={() => navigate('jobs', 'list')}>返回学习任务</Button>
          <header className="pw-learning-heading"><div><h2>整理新来源</h2></div>{sources.length > 0 && <span className="pw-learning-caption">已选 {sources.length} / 20</span>}</header>
          <div className="pw-learning-source-picker"><SelectField label="选择原任务" value={taskId} options={ended.map(task => ({ id: task.id, label: task.goal || task.id, description: taskStatus(task.status) }))} isDisabled={busy} onChange={value => { setTaskId(value); setEpisode(null); setRetained(false); }} /><Button variant="secondary" disabled={busy || !selected?.raw?.runId || !selected?.raw?.coordinatorEpoch} onClick={() => { if (!selected) return; setRetained(false); void invoke('learning.episode.inspect', { taskId: selected.id, runId: selected.raw.runId, epoch: selected.raw.coordinatorEpoch }, setEpisode); }}>读取任务经过</Button></div>
          {!ended.length && <p className="pw-learning-caption">当前窗口没有可整理的已结束私人任务。</p>}
          <div className="pw-learning-source-layout">
          {episode && <article className="pw-learning-reading"><header><span className="pw-learning-eyebrow">原任务来源</span><h3>{episode.title}</h3></header><AIAnswer showActions={false}><div className="pw-learning-verbatim">{episode.text}</div></AIAnswer><p className="pw-learning-caption">显示范围由原任务记录确定。助手总结、工具成功和主人确认分别保留；未经确认的业务结果仍是未知。</p>{episode.stale && <p className="pw-learning-caution">这是上次的阅读内容。请重新读取任务经过，再保留当前原来源。</p>}<Button variant="secondary" disabled={busy || retained || !episode.reviewHandle || sources.length >= 20 || createAttempted} onClick={() => { setRetained(true); void invoke('learning.episode.retain', { reviewHandle: episode.reviewHandle }, value => { if (value.learningAllowed !== true || !value.sourceHandle) { setNotice('原来源当前不能用于提炼，没有加入清单。'); return; } setSources(old => { const at = old.findIndex(source => JSON.stringify(source.sourceRef) === JSON.stringify(value.sourceRef)); return at < 0 ? [...old, value] : old.map((source, index) => index === at ? value : source); }); setNotice('原来源已加入清单，尚未调用模型。'); }); }}>{retained ? '已请求保留这份来源' : '保留来源并加入清单'}</Button></article>}
          {sources.length > 0 && <section className="pw-learning-selection"><h3>本次所选来源</h3><div className="pw-form-actions pw-learning-selection-actions"><Button disabled={busy || unresolved || createAttempted || sources.some(source => !source.sourceHandle)} onClick={() => { if (entered.current || unresolved) return; setCreateAttempted(true); void invoke('learning.create', { sourceHandles: sources.map(source => source.sourceHandle) }, value => { originalTask.current = value.taskId; setCurrent(value); setOperation(null); setJobs(old => [value, ...old.filter(row => row.taskId !== value.taskId)]); setSources([]); setCreateAttempted(false); setRetained(false); setEpisode(null); setNotice('学习任务已建立，尚未发送模型。'); navigate('jobs', 'job'); }); }}>建立学习任务，暂不发送模型</Button></div><ul>{sources.map((source, index) => <li key={JSON.stringify(source.sourceRef) || index}><div><strong>{source.title}</strong>{!source.sourceHandle && <small>阅读内容已保留，需重新读取并保留此来源</small>}</div><Button variant="ghost" disabled={busy || createAttempted} onClick={() => setSources(old => old.filter(row => row !== source))}>移出清单</Button></li>)}</ul>{unresolved && <p className="pw-learning-caption">所选来源已保留。请先核对当前原调用，再建立另一份学习任务。</p>}{createAttempted && <div className="pw-learning-caution"><p>建立结果待核对。此清单不会重复提交，请读取原任务列表确认。</p><Button variant="ghost" disabled={busy} onClick={() => { navigate('jobs', 'list'); loadJobs(); }}>核对原任务列表</Button><Button variant="ghost" disabled={busy} onClick={() => { setSources([]); setCreateAttempted(false); setEpisode(null); setRetained(false); setNotice('本地来源清单已结束。旧建立结果仍需在原任务列表核对，没有重复提交。'); }}>结束本次清单，保留原结果未知</Button></div>}</section>}
          </div>
        </div>
        <div hidden={view !== 'job'}>
          <Button variant="ghost" leadingIcon={ArrowLeft} onClick={() => navigate('jobs', 'list')}>返回学习任务</Button>
          {current && <article className="pw-learning-reading"><header className="pw-learning-heading"><div><span className="pw-learning-eyebrow">来源学习任务</span><h2>{title(current)}</h2><p>{taskStatus(current.taskStatus)} · 已处理 {current.job?.nextIndex ?? 0} / {rows(current.job?.inputs).length} 份来源</p></div><Button variant="ghost" iconOnly leadingIcon={RefreshCw} aria-label="重新核对任务水位" disabled={busy} onClick={() => selectJob(current.taskId)} /></header>
            {current.stale && <p className="pw-learning-caution">当前显示上次读取的批次。原窗口已变化，请重新核对任务水位后再操作。</p>}
            <div className="pw-learning-next"><Button disabled={busy || running || current.stale || current.canContinue !== true} onClick={() => openReview('run')}>核对并继续原批次</Button><p className="pw-learning-caption">默认最多处理 3 份来源。提炼只形成待审候选，不会自动采用或启用技能。</p></div>
            <h3 className="pw-knowledge-section-heading">来源与批次</h3>
            <ol className="pw-learning-batches">{rows(current.job?.inputs).map((source, index) => { const candidate = rows(current.job?.results)[index]?.candidateRef; const canLocate = typeof candidate?.id === 'string' && candidate.id.length > 0 && Number.isSafeInteger(candidate.revision) && candidate.revision > 0; return <li key={source.sourceFingerprint || index} aria-current={index === current.job?.nextIndex ? 'step' : undefined}><span className="pw-learning-batch-index">{index + 1}</span><div><strong>{source.title}</strong><p>{learningBatchStatus(current.job, index)}</p>{canLocate && <Button variant="ghost" disabled={busy || running || stopping} onClick={() => onReviewCandidate({ id: candidate.id, revision: candidate.revision, taskId: current.taskId, sourceTitle: source.title || '原学习来源' })}>审阅这条候选</Button>}</div></li>; })}</ol>

            <Accordion className="pw-learning-disclosures"><AccordionItem id="learning-options" title="批次范围与异常处理" description={continuous ? '本次最多连续处理 20 批' : '每次最多 3 批，已完成的批次不重发'}><CheckboxField isSelected={continuous} onChange={setContinuous} isDisabled={busy || running}>本次连续处理全部已选来源，最多 20 批</CheckboxField>{current.canSkipBatch && <Button variant="ghost" disabled={busy || running || current.stale} onClick={() => openReview('skip')}>核对并跳过当前来源</Button>}<p className="pw-learning-caption">跳过会保留旧调用未知状态与用量。真正离开此工作区会结束本次调用，原学习计划与已取得结果保留；停止请求不等于已取得最终回执。</p><p className="pw-learning-caption">任务 {current.taskId} · 原运行 {current.runId || '尚未开始'} · 水位 {current.epoch ?? '未读取'}</p></AccordionItem></Accordion>
          </article>}
        </div>
      </TabPanel>
    </Tabs>
    <SettingsDialog isOpen={!!review} onClose={() => { if (!busy) closeReview(); }} title={review?.kind === 'skip' ? '跳过这个原来源' : '继续这个原学习任务'}><div className="pw-knowledge-review">
      <p><strong>{title(review)}</strong></p>
      {review?.kind === 'skip' ? <p>原未知调用和已收到草稿继续保留。跳过不会声称旧请求未执行、不会退款，也不会生成该批候选。</p> : <p>将核验所选原来源版本，并向开始时的当前模型发送最多 {review?.reviewMaxBatches} 份尚未处理的来源。只处理这份清单；已有草稿沿原回执继续采用，不重新发送。</p>}
      {review && <p className="pw-learning-caption">当前第 {(review.job?.nextIndex ?? 0) + 1} 批：{rows(review.job?.inputs)[review.job?.nextIndex ?? 0]?.title || '请核对原来源'}</p>}
      <CheckboxField isSelected={acknowledged} onChange={setAcknowledged}>我已核对原来源与影响，确认这一次操作</CheckboxField><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={closeReview}>返回核对</Button><Button disabled={busy || !acknowledged} onClick={() => { const frozen = review; if (!frozen || !acknowledged || entered.current) return; originalTask.current = frozen.taskId; setReview(null); setAcknowledged(false); setCurrent(old => old ? { ...old, stale: true } : old); if (frozen.kind === 'run') setOperation({ taskId: frozen.taskId, title: title(frozen), unknownStart: true, settled: false }); else setNotice('当前来源的跳过请求已提交，回执尚未确认。若未取得结果，请重新读取原任务水位，不重复提交。'); void invoke(`learning.${frozen.kind}.confirm`, { reviewHandle: frozen.reviewHandle }, value => { if (frozen.kind === 'run') setOperation({ ...value, title: title(frozen), needsRead: true, unknownStart: false }); else { setCurrent(value); setOperation(null); setNotice('已取得原跳过回执，旧调用未知状态与用量继续保留。'); } }); }}>{review?.kind === 'skip' ? '确认跳过并保留未知' : '发送并继续原任务'}</Button></div>
    </div></SettingsDialog>
  </section>;
}
