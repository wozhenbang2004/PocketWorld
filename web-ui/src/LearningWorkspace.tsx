import { useCallback, useEffect, useRef, useState } from 'react';
import { ExperienceWorkspace } from './ExperienceWorkspace';
import { Accordion, AccordionItem, Button, CheckboxField, SelectField, SettingsDialog } from './forms-library';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
export type LearningCandidateTarget = { id: string; revision: number; taskId: string; sourceTitle: string };
const rows = (value: any): Data[] => Array.isArray(value) ? value : [];

/** The original source-learning task keeps its batches, receipts and explicit model admission. */
export function LearningWorkspace({ state, data, run, registerTransientBack, onReviewCandidate }: { state: Data; data: Data; run: Run; registerTransientBack: (handler: (() => boolean) | null) => void; onReviewCandidate: (target: LearningCandidateTarget) => void }) {
  const [jobs, setJobs] = useState<Data[]>([]), [cursor, setCursor] = useState(''), [current, setCurrent] = useState<Data | null>(null);
  const [taskId, setTaskId] = useState(''), [episode, setEpisode] = useState<Data | null>(null), [sources, setSources] = useState<Data[]>([]), [retained, setRetained] = useState(false), [createAttempted, setCreateAttempted] = useState(false);
  const [operation, setOperation] = useState<Data | null>(null);
  const [review, setReview] = useState<Data | null>(null), [acknowledged, setAcknowledged] = useState(false), [continuous, setContinuous] = useState(false);
  const [busy, setBusy] = useState(false), [stopping, setStopping] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('');
  const experienceBack = useRef<(() => boolean) | null>(null);
  const registerExperienceBack = useCallback((handler: (() => boolean) | null) => { experienceBack.current = handler; }, []);
  const alive = useRef(true), entered = useRef(false), originalTask = useRef(''), generation = useRef(0);
  const invoke = async (action: string, args: Data = {}, accept?: (result: Data) => void) => {
    if (entered.current) return; entered.current = true; const request = ++generation.current; setBusy(true); setError('');
    try { const result = await run(action, args); if (alive.current && request === generation.current) accept?.(result); return result; }
    catch (failure) { if (alive.current && request === generation.current) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { if (request === generation.current) { entered.current = false; if (alive.current) setBusy(false); } }
  };
  const loadJobs = (next = '') => { void invoke('learning.jobs', { cursor: next }, value => { setJobs(old => next ? Array.from(new Map([...old, ...rows(value.jobs)].map(job => [job.taskId, job])).values()) : rows(value.jobs)); setCursor(value.hasMore ? value.nextCursor : ''); }); };
  const selectJob = (id: string) => { originalTask.current = id; void invoke('learning.job', { taskId: id }, value => { if (value.taskId !== id) throw new Error('原学习任务身份不符。'); setCurrent(value); setNotice(''); }); };
  const closeReview = () => { const handle = review?.reviewHandle; if (handle) void run('knowledge.close', { handle }).catch(() => {}); setReview(null); setAcknowledged(false); };
  useEffect(() => { alive.current = true; loadJobs(); return () => { alive.current = false; generation.current++; }; }, []);
  useEffect(() => { registerTransientBack(() => { if (review) { if (!busy) closeReview(); return true; } return experienceBack.current?.() ?? false; }); return () => registerTransientBack(null); }, [review, busy, registerTransientBack]);
  const ended = rows(state.tasks).filter(task => ['assistant', 'phone'].includes(task.raw?.kind) && !task.raw?.origin && !task.raw?.sourceLearningJob && ['completed', 'failed', 'cancelled', 'interrupted', 'needs_confirmation', 'awaiting_user'].includes(task.status));
  const selected = ended.find(task => task.id === taskId);
  const stop = () => {
    const task = current?.taskId; if (!task || stopping) return;
    const original = operation?.taskId === task && !operation.settled ? operation : null;
    const args = original ? { operationHandle: original.operationHandle } : { taskId: task, runId: current.runId, epoch: current.epoch };
    if (!original && (typeof current.runId !== 'string' || !Number.isInteger(current.epoch) || current.epoch < 0)) { setError('原任务身份尚未读取，请重新核对原水位。'); return; }
    setStopping(true); void run(original ? 'learning.operation.stop' : 'learning.stop', args).then(value => { if (alive.current && originalTask.current === task) { if (!original) setCurrent(value); setNotice('已收到原停止回执。已有草稿、候选和未知调用保留，请核对当前水位。'); } }).catch(failure => { if (alive.current) setError(failure.message); }).finally(() => { if (alive.current) setStopping(false); });
  };
  useEffect(() => {
    if (!operation?.operationHandle || operation.settled) return;
    const handle = operation.operationHandle; let active = true, reading = false;
    const timer = setInterval(() => {
      if (!active || reading || document.visibilityState !== 'visible') return; reading = true;
      void run('learning.operation.query', { operationHandle: handle }).then(value => { if (active && alive.current) { setOperation({ ...value, operationHandle: handle }); if (value.result && value.taskId === originalTask.current) setCurrent(value.result); if (value.error) setError(value.error); } }).catch(failure => { if (active && alive.current) setError(failure.message); active = false; }).finally(() => { reading = false; });
    }, 1500);
    return () => { active = false; clearInterval(timer); };
  }, [operation?.operationHandle, operation?.settled, run]);
  const running = !!operation && !operation.settled;

  const openReview = (kind: 'run' | 'skip') => { if (!current) return; void invoke(`learning.${kind}.review`, { taskId: current.taskId, ...(kind === 'run' ? { maxBatches: continuous ? 20 : 3 } : {}) }, value => { setReview({ ...value, kind }); setAcknowledged(false); }); };
  const batchStatus = (job: Data, index: number) => { const result = rows(job.results)[index]; return result?.candidateRef ? '候选已登记，等待审阅' : result?.outcome === 'no_candidate' ? '依据不足，未形成候选' : result ? '已明确跳过，未知用量保留' : index === job.nextIndex ? '当前批次' : '尚未处理'; };
  return <>
    <section className="pw-source-record"><h2>从已结束任务整理经验</h2><p className="pw-reading-lead">先核对原目标、操作经过与结果分类，再把所选来源加入学习任务。保存来源和建立任务都不会调用模型。</p>
      <SelectField label="选择原任务" value={taskId} options={ended.map(task => ({ id: task.id, label: task.goal || task.id, description: task.status }))} isDisabled={busy} onChange={value => { setTaskId(value); setEpisode(null); setRetained(false); }} />
      <Button variant="secondary" disabled={busy || !selected?.raw?.runId || !selected?.raw?.coordinatorEpoch} onClick={() => { if (!selected) return; setRetained(false); void invoke('learning.episode.inspect', { taskId: selected.id, runId: selected.raw.runId, epoch: selected.raw.coordinatorEpoch }, setEpisode); }}>读取原任务经过</Button>
      {episode && <><h3>{episode.title}</h3><pre className="pw-source-copy" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{episode.text}</pre><p className="pw-muted">显示范围由原任务记录确定。助手总结、工具成功与主人结果确认分别保留；未确认的业务结果仍是未知。</p><Button variant="secondary" disabled={busy || retained || sources.length >= 20} onClick={() => { setRetained(true); void invoke('learning.episode.retain', { reviewHandle: episode.reviewHandle }, value => { if (value.learningAllowed !== true || !value.sourceHandle) { setNotice('原来源当前不能用于提炼；没有加入学习清单。'); return; } setSources(old => { const same = old.some(source => JSON.stringify(source.sourceRef) === JSON.stringify(value.sourceRef)); return same ? old : [...old, value]; }); setCreateAttempted(false); setNotice('原经过已保留；同一原来源在本页清单中只保留一份，尚未调用模型。'); }); }}>保留这份原来源并加入清单</Button></>}
      {sources.length > 0 && <><h3>本次所选来源</h3>{sources.map(source => <div className="pw-form-actions" key={source.sourceHandle}><span>{source.title}</span><Button variant="ghost" disabled={busy || createAttempted} onClick={() => setSources(old => old.filter(row => row.sourceHandle !== source.sourceHandle))}>移出本次清单</Button></div>)}<Button disabled={busy || createAttempted} onClick={() => { setCreateAttempted(true); void invoke('learning.create', { sourceHandles: sources.map(source => source.sourceHandle) }, value => { originalTask.current = value.taskId; setCurrent(value); setSources([]); setNotice('原学习任务已建立，尚未发送模型。'); }); }}>建立原学习任务，暂不发送模型</Button>{createAttempted && sources.length > 0 && <p className="pw-muted">建立结果待核对时，请刷新下方原任务列表；没有重复提交这些来源。</p>}</>}
    </section>
    <section className="pw-source-record"><h2>原学习任务</h2><div className="pw-form-actions"><Button variant="ghost" disabled={busy} onClick={() => loadJobs()}>刷新原任务列表</Button>{cursor && <Button variant="ghost" disabled={busy} onClick={() => loadJobs(cursor)}>读取较早任务</Button>}</div>
      {!jobs.length && <p className="pw-secondary-empty">当前读取窗口没有学习任务。</p>}<Accordion variant="divided">{jobs.map(job => <AccordionItem key={job.taskId} id={job.taskId} title={job.job?.inputs?.[0]?.title || '原来源学习任务'} description={`${job.taskStatus || ''} · 已处理 ${job.job?.nextIndex ?? 0} / ${rows(job.job?.inputs).length} 份来源`}><Button variant="secondary" disabled={busy || running} onClick={() => selectJob(job.taskId)}>读取原水位与批次</Button></AccordionItem>)}</Accordion>
    </section>
    {current && <section className="pw-source-record"><h2>当前原任务的处理进度</h2><p className="pw-muted">{current.taskStatus} · 已处理 {current.job?.nextIndex} / {rows(current.job?.inputs).length} 份来源</p>{current.active && <p>{current.active.stage === 'draft_recorded' ? '原模型草稿已记录；继续仅核对原来源并提交同一候选，不重新调用模型。' : current.active.stage === 'inflight' ? '原批次尚未取得确定草稿回执。' : '旧调用结果未知；没有自动重试，也不计作未使用或退款。'}</p>}
      {rows(current.job?.inputs).map((source, index) => {
        const candidate = rows(current.job?.results)[index]?.candidateRef;
        const canLocate = typeof candidate?.id === 'string' && candidate.id.length > 0 && Number.isSafeInteger(candidate.revision) && candidate.revision > 0;
        return <div key={source.sourceFingerprint || index}><p>{index + 1}. {source.title} · {batchStatus(current.job, index)}</p>{canLocate && <Button variant="ghost" disabled={busy || running || stopping} onClick={() => onReviewCandidate({ id: candidate.id, revision: candidate.revision, taskId: current.taskId, sourceTitle: source.title || '原学习来源' })}>审阅这条候选</Button>}</div>;
      })}
      <CheckboxField isSelected={continuous} onChange={setContinuous} isDisabled={busy}>本次连续处理全部已选来源，最多 20 批</CheckboxField>
      <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => selectJob(current.taskId)}>重新核对原水位</Button><Button disabled={busy || running || current.canContinue !== true} onClick={() => openReview('run')}>核对并继续原批次</Button>{current.canSkipBatch && <Button variant="ghost" disabled={busy} onClick={() => openReview('skip')}>核对并跳过当前来源</Button>}{(busy || running || ['preparing', 'running', 'stopping'].includes(current.taskStatus)) && <Button variant="secondary" disabled={stopping} onClick={stop}>停止这个原任务的本次提炼</Button>}</div>
      <p className="pw-muted">提炼只形成待审候选；采用、方法质量验证和启用技能仍各自核对。离开本页会结束本次调用，原学习计划和已取得结果保留。</p>
    </section>}
    <ExperienceWorkspace state={state} data={data} run={run} registerTransientBack={registerExperienceBack} />
    {error && <p role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}{busy && <p className="pw-muted">正在核对原操作回执…</p>}
    <SettingsDialog isOpen={!!review} onClose={() => { if (!busy) closeReview(); }} title={review?.kind === 'skip' ? '跳过这个原来源' : '继续这个原学习任务'}>
      {review?.kind === 'skip' ? <p>原未知调用和已收到草稿继续保留。跳过不会声称旧请求未执行、不会退款，也不会生成该批候选。</p> : <p>将核验所选原来源版本，并向开始时的当前模型发送最多 {review?.reviewMaxBatches} 份尚未处理的来源。只处理这份清单；已有草稿沿原回执继续采用，不重新发送。</p>}
      <CheckboxField isSelected={acknowledged} onChange={setAcknowledged}>我已核对原来源与影响，确认这一次操作</CheckboxField><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={closeReview}>返回核对</Button><Button disabled={busy || !acknowledged} onClick={() => { const frozen = review; if (!frozen) return; originalTask.current = frozen.taskId; setReview(null); void invoke(`learning.${frozen.kind}.confirm`, { reviewHandle: frozen.reviewHandle }, value => { if (frozen.kind === 'run') setOperation(value); else setCurrent(value); }); }}>{review?.kind === 'skip' ? '确认跳过并保留未知' : '发送并继续原任务'}</Button></div>
    </SettingsDialog>
  </>;
}
