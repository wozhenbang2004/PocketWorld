import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Accordion, AccordionItem, Button, CheckboxField, SelectField, SettingsDialog, TextAreaField, TextField } from './forms-library';
import { AIAnswer } from './library';
import { TabPanel } from './shell-library';
import { ArrowLeft, ChevronRight, FileText, RefreshCw } from 'lucide-react';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
const rows = (value: any): Data[] => Array.isArray(value) ? value : [];
const labels: Record<string, string> = { draftExperience: '从原方法记忆起草技能', draftAppRevision: '从原任务方法修订 App 经验', importExperience: '保存为停用的技能', approveExperience: '批准这个原版本的技能', reviewSkillQuality: '保存这次原技能的效果归责', reviewAppQuality: '保存这次原方法的效果归责', setAppLearningPolicy: '修改这个原任务的 App 提炼授权' };
const outcomes = [{ id: 'verified', label: '已核对成功' }, { id: 'skill_failed', label: '方法失败' }, { id: 'verifier_rejected', label: '核验不通过' }, { id: 'environment', label: '环境问题' }, { id: 'cancelled', label: '已取消' }, { id: 'model_error', label: '模型错误' }];
const methodTitle = (record: Data | undefined) => record?.text?.split('\n')[0] || '方法记忆';
const methodStatus = (record: Data) => ({ verified: '效果已核实', unverified: '效果未核实', rejected: '核验未通过' } as Data)[record.validationStatus] || record.validationStatus || '效果未核实';
let saved: { procedure: Data | null; skill: Data | null; draft: Data | null; manifest: string; files: Data; path: string; operation: Data | null; unknown: Data | null } | null = null;

/** Adapted from OpenMuse MIT 61796eb2 LinkRow (apps/mobile/src/ui.tsx:368).
 * Its title/detail/chevron and whole-row target use the already adopted BoardUI Button.
 * Author image: OpenMuse's official mobile product image; no upstream art is shipped. */
export function LearningObjectRow({ title, detail, onClick, disabled = false, selected = false }: { title: string; detail: string; onClick: () => void; disabled?: boolean; selected?: boolean }) {
  return <Button variant="ghost" className="pw-learning-object" aria-label={`打开：${title}`} aria-current={selected ? 'true' : undefined} disabled={disabled} onClick={onClick}><span className="pw-learning-object-copy"><strong>{title}</strong><small>{detail}</small></span><ChevronRight aria-hidden="true" className="pw-learning-chevron" /></Button>;
}
export function experienceFilePage(previous: Data | undefined, page: Data, offset: number): Data {
  if (typeof page.content !== 'string' || page.nextOffset !== offset + page.content.length || offset > 0 && previous?.nextOffset !== offset) throw new Error('原文件页范围不连续，请从头重新读取。');
  return { ...page, content: (offset ? previous?.content || '' : '') + page.content };
}

/** Native PocketWebExperience.review freezes these distinct before/payload shapes. */
export function experienceRecoveryTarget(review: Data): Data | null {
  if (review.operation === 'importExperience') return { kind: 'skills' };
  const kind = ['approveExperience', 'reviewSkillQuality'].includes(review.operation) ? 'skill'
    : ['draftExperience', 'draftAppRevision', 'reviewAppQuality'].includes(review.operation) ? 'procedure'
    : review.operation === 'setAppLearningPolicy' ? 'policy' : '';
  const id = kind === 'policy' ? review.payload?.taskId : review.before?.id;
  return kind && typeof id === 'string' && id.length > 0 ? { kind, id } : null;
}

/** The method and skill panels share the mounted owner window, never its permissions. */
export function ExperienceWorkspace({ state, data, run, registerTransientBack, panel = 'procedures', onNavigate = () => {} }: { state: Data; data: Data; run: Run; registerTransientBack: (handler: (() => boolean) | null) => void; panel?: string; onNavigate?: (panel: string) => void }) {
  const [snapshot, setSnapshot] = useState<Data>(data), [loaded, setLoaded] = useState(false);
  const [procedure, setProcedure] = useState<Data | null>(saved?.procedure || null), [skill, setSkill] = useState<Data | null>(saved?.skill || null);
  const [methodOpen, setMethodOpen] = useState(!!saved?.procedure), [skillOpen, setSkillOpen] = useState(!!saved?.skill);
  const [draft, setDraft] = useState<Data | null>(saved?.draft || null), [manifest, setManifest] = useState(saved?.manifest || ''), [operation, setOperation] = useState<Data | null>(saved?.operation || null);
  const [unknown, setUnknown] = useState<Data | null>(saved?.unknown || null), [review, setReview] = useState<Data | null>(null);
  const [confirmed, setConfirmed] = useState(false), [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState(''), [stopping, setStopping] = useState(false);
  const [path, setPath] = useState(saved?.path || 'SKILL.md'), [files, setFiles] = useState<Data>(saved?.files || {}), [manifestRead, setManifestRead] = useState(false);
  const [apps, setApps] = useState<Data[]>([]), [appsRead, setAppsRead] = useState(false), [appHandle, setAppHandle] = useState(''), [lesson, setLesson] = useState(false), [baseId, setBaseId] = useState('');
  const [qualityState, setQualityState] = useState<Data>({ kind: '', applications: [], cursor: '', loaded: false }), [qualityOutcome, setQualityOutcome] = useState('verified'), [qualityNote, setQualityNote] = useState('');
  const [policyTask, setPolicyTask] = useState(''), [policy, setPolicy] = useState<Data | null>(null), [packageName, setPackageName] = useState('');
  const [disclosures, setDisclosures] = useState<Set<string | number>>(new Set());
  const alive = useRef(true), entered = useRef(false), handles = useRef(new Set<string>()), operationResult = useRef(''), epoch = useRef(0), pendingMutation = useRef<Data | null>(null);
  const root = useRef<HTMLDivElement | null>(null), positions = useRef<Record<string, number>>({}), origin = useRef<HTMLElement | null>(null);
  const latest = useRef({ procedure, skill, operation, draft, unknown }); latest.current = { procedure, skill, operation, draft, unknown };
  const ownerTicket = () => { const native = bridge.getState().view as Data | undefined; return `${bridge.getDocumentEpoch()}:${native?.section}:${native?.taskId || ''}:${native?.resumeRevision || 0}`; };
  const nativeActive = () => { const native = bridge.getState().view as Data | undefined; return document.visibilityState === 'visible' && native?.section === 'learning' && !native?.taskId; };
  const remember = (result: Data) => { for (const key of ['procedureHandle', 'skillHandle', 'draftHandle', 'reviewHandle', 'policyHandle']) if (typeof result[key] === 'string') handles.current.add(result[key]); return result; };
  const invoke = async (action: string, args: Data = {}, accept?: (result: Data) => void | Promise<void>, mutation?: Data) => {
    if (entered.current || !nativeActive()) return; entered.current = true; const ticket = epoch.current; setBusy(true); setError(''); if (mutation) pendingMutation.current = mutation;
    try { const result = await run(action, args); if (alive.current && ticket === epoch.current && nativeActive()) { await accept?.(remember(result)); if (mutation) pendingMutation.current = null; } return result; }
    catch (failure) { if (alive.current && ticket === epoch.current) { setError(failure instanceof Error ? failure.message : String(failure)); if (mutation) { setUnknown(mutation); pendingMutation.current = null; } } }
    finally { if (ticket === epoch.current) { entered.current = false; if (alive.current) setBusy(false); } }
  };
  const load = () => { void invoke('experience.snapshot', {}, value => { setSnapshot(value); setLoaded(true); }); };
  const closeReview = () => { if (review?.reviewHandle) void run('experience.close', { handle: review.reviewHandle }).catch(() => {}); setReview(null); setConfirmed(false); };
  const invalidate = () => {
    epoch.current++; entered.current = false; setBusy(false); setStopping(false); setReview(null); setConfirmed(false); handles.current.clear();
    if (pendingMutation.current) { setUnknown(pendingMutation.current); pendingMutation.current = null; }
    setProcedure(old => old ? { ...old, procedureHandle: '', stale: true } : old); setSkill(old => old ? { ...old, skillHandle: '', stale: true } : old);
    setDraft(old => old ? { ...old, draftHandle: '', stale: true } : old); setManifestRead(false);
    setOperation(old => old ? { ...old, operationHandle: '', stale: true } : old);
    setApps([]); setAppHandle(''); setAppsRead(false); setPolicy(old => old ? { ...old, policyHandle: '', stale: true } : old);
    setQualityState(old => ({ ...old, stale: true, applications: rows(old.applications).map(item => ({ ...item, applicationHandle: '' })) }));
  };
  useEffect(() => {
    alive.current = true; let owner = ownerTicket(), loadedOwner = '';
    // A late connections.enter grants only this initial read, not any old review.
    const loadOwner = () => { const ticket = ownerTicket(); if (nativeActive() && loadedOwner !== ticket) { loadedOwner = ticket; load(); } };
    const hide = () => { if (!nativeActive()) invalidate(); else loadOwner(); };
    const unsubscribe = bridge.subscribe(() => { const incoming = ownerTicket(); if (incoming !== owner) { owner = incoming; invalidate(); } loadOwner(); });
    loadOwner();
    document.addEventListener('visibilitychange', hide); window.addEventListener('pagehide', invalidate);
    return () => { alive.current = false; epoch.current++; unsubscribe(); document.removeEventListener('visibilitychange', hide); window.removeEventListener('pagehide', invalidate); handles.current.forEach(handle => { void run('experience.close', { handle }).catch(() => {}); }); if (pendingMutation.current && saved) saved.unknown = pendingMutation.current; };
  }, []);
  useEffect(() => { saved = { procedure: procedure ? { ...procedure, procedureHandle: '', stale: true } : null, skill: skill ? { ...skill, skillHandle: '', stale: true } : null, draft: draft ? { ...draft, draftHandle: '', stale: true } : null, manifest, files, path, operation: operation ? { ...operation, operationHandle: '', stale: true } : null, unknown }; }, [procedure, skill, draft, manifest, files, path, operation, unknown]);
  const changeDetail = (kind: string, open: boolean) => { const key = `${kind}:${kind === 'procedures' ? methodOpen : skillOpen}`; positions.current[key] = root.current?.closest<HTMLElement>('.pw-secondary-scroll')?.scrollTop || 0; if (open) origin.current = document.activeElement as HTMLElement; if (kind === 'procedures') setMethodOpen(open); else setSkillOpen(open); if (!open) origin.current?.focus({ preventScroll: true }); };
  useLayoutEffect(() => { const scroller = root.current?.closest<HTMLElement>('.pw-secondary-scroll'); if (scroller && ['procedures', 'skills'].includes(panel)) scroller.scrollTop = positions.current[`${panel}:${panel === 'procedures' ? methodOpen : skillOpen}`] || 0; if (panel === 'procedures' && !methodOpen || panel === 'skills' && !skillOpen) origin.current?.focus({ preventScroll: true }); }, [methodOpen, skillOpen]);
  useEffect(() => { registerTransientBack(() => { if (review) { if (!busy) closeReview(); return true; } if (panel === 'procedures' && methodOpen) { changeDetail('procedures', false); return true; } if (panel === 'skills' && skillOpen) { changeDetail('skills', false); return true; } return false; }); return () => registerTransientBack(null); }, [review, methodOpen, skillOpen, panel, busy, registerTransientBack]);
  const acceptDraft = (value: Data) => { remember(value); setDraft(value); setManifest(value.draft?.manifest || ''); setNotice('草稿已取得，尚未保存或启用。请阅读正文与附加资料。'); };
  const acceptOperation = (original: Data, value: Data) => {
    setOperation({ ...original, ...value, needsRead: false, queryFailed: false });
    if (value.result && operationResult.current !== original.operationHandle) { operationResult.current = original.operationHandle; if (value.result.draft) acceptDraft(value.result); else setNotice(value.result.proposed === false ? '原回执未形成新候选，请核对原因。' : '已取得原 App 经验候选回执，请到记忆候选页审阅。'); }
  };
  const queryOperation = async (original: Data, ticket: number) => {
    try { const value = await run('experience.operation.query', { operationHandle: original.operationHandle }); if (alive.current && ticket === epoch.current && nativeActive() && latest.current.operation?.operationHandle === original.operationHandle) acceptOperation(original, value); }
    catch (failure) { if (alive.current && ticket === epoch.current) { setOperation(old => old?.operationHandle === original.operationHandle ? { ...old, queryFailed: true } : old); setError(failure instanceof Error ? failure.message : String(failure)); } }
  };
  useEffect(() => {
    if (!operation?.operationHandle || operation.stale || operation.queryFailed || operation.settled && !operation.needsRead) return;
    const ticket = epoch.current; let active = true, reading = false;
    const query = () => { if (!active || reading || !nativeActive()) return; reading = true; void queryOperation(operation, ticket).finally(() => { reading = false; }); };
    query(); const timer = setInterval(query, 1500); return () => { active = false; clearInterval(timer); };
  }, [operation?.operationHandle, operation?.settled, operation?.needsRead, operation?.stale, operation?.queryFailed]);
  const running = !!operation && !operation.settled && !operation.stale;
  const openReview = (value: Data) => { setReview(value); setConfirmed(false); };
  const selectProcedure = (id: string, refresh = false) => {
    if (busy || running || draft && procedure?.record.id !== id) return;
    if (!refresh && procedure?.record.id === id && !procedure.stale) { changeDetail('procedures', true); return; }
    void invoke('experience.procedure', { id }, value => { if (value.record?.id !== id) throw new Error('原方法身份不符。'); setProcedure(value); if (procedure?.record.id !== id) { setQualityState(old => old.kind === 'procedure' ? { kind: '', applications: [], cursor: '', loaded: false } : old); setQualityNote(''); } changeDetail('procedures', true); setApps([]); setAppsRead(false); setAppHandle(''); setBaseId(''); setNotice(''); });
  };
  const selectSkill = (id: string, refresh = false) => {
    if (busy) return; if (!refresh && skill?.skill.id === id && !skill.stale) { changeDetail('skills', true); return; }
    void invoke('experience.skill', { id }, value => { if (value.skill?.id !== id) throw new Error('原技能身份不符。'); const same = skill?.skill.id === id && skill?.skill.digest === value.skill.digest; setSkill(value); if (skill?.skill.id !== id) { setQualityState(old => old.kind === 'skill' ? { kind: '', applications: [], cursor: '', loaded: false } : old); setQualityNote(''); } changeDetail('skills', true); setManifestRead(false); if (!same) { setPath('SKILL.md'); setFiles({}); } else setFiles(old => Object.fromEntries(Object.entries(old).map(([name, page]) => [name, { ...(page as Data), stale: true }]))); setNotice(''); });
  };
  const read = (offset: number) => {
    if (!skill?.skillHandle || skill.stale) return; const target = skill, filePath = path;
    void invoke('experience.read', { skillHandle: target.skillHandle, path: filePath, offset }, value => {
      if (value.id !== target.skill.id || value.revision !== target.skill.revision || value.digest !== target.skill.digest || value.path !== filePath) throw new Error('回读文件不是当前原技能版本。');
      const page = experienceFilePage(files[filePath], value, offset); setFiles(old => ({ ...old, [filePath]: page }));
      if (filePath === 'SKILL.md') setManifestRead(!value.hasMore);
    });
  };
  const quality = (kind: 'skill' | 'procedure', cursor = '') => { const target = kind === 'skill' ? skill : procedure, handle = kind === 'skill' ? target?.skillHandle : target?.procedureHandle; if (!handle || target?.stale) return; void invoke('experience.quality.list', { kind, handle, cursor }, value => { setQualityState(old => ({ kind, loaded: true, stale: false, applications: cursor && old.kind === kind ? [...old.applications, ...rows(value.applications)] : rows(value.applications), cursor: value.hasMore ? value.nextCursor : '' })); }); };
  const confirm = () => {
    const frozen = review; if (!frozen || !confirmed || entered.current) return; setReview(null); setConfirmed(false);
    const mutation = { operation: frozen.operation, label: labels[frozen.operation], recovery: experienceRecoveryTarget(frozen) };
    if (['draftExperience', 'draftAppRevision'].includes(frozen.operation)) {
      void invoke('experience.draft.start', { reviewHandle: frozen.reviewHandle }, value => { setOperation({ ...value, needsRead: true, title: methodTitle(frozen.before), procedureId: frozen.before?.id, kind: frozen.operation }); }, mutation);
    } else void invoke('experience.confirm', { reviewHandle: frozen.reviewHandle }, value => {
      if (frozen.operation === 'importExperience') { setDraft(null); setManifest(''); setSkill(value); setSkillOpen(true); onNavigate('skills'); setPath('SKILL.md'); setFiles({}); setManifestRead(false); setNotice('技能已保存，保持停用。请完整回读当前正文，再决定是否批准。'); }
      else if (frozen.operation === 'approveExperience') { setNotice('已收到这个原版本的批准回执。执行仍需原宿主权限。'); setSkill(old => old ? { ...old, skill: value.skill, skillHandle: '', stale: true } : old); setManifestRead(false); }
      else { setNotice('已收到原处理回执，请重新读取当前状态。'); if (frozen.operation === 'setAppLearningPolicy') setPolicy(null); else setQualityState({ kind: '', applications: [], cursor: '', loaded: false }); }
    }, mutation);
  };
  const recoverUnknown = () => {
    const target = unknown?.recovery; if (!target || busy) return;
    if (target.kind === 'skills') { onNavigate('skills'); changeDetail('skills', false); load(); }
    else if (target.kind === 'skill') { onNavigate('skills'); selectSkill(target.id, true); }
    else if (target.kind === 'procedure') { onNavigate('procedures'); selectProcedure(target.id, true); }
    else if (target.kind === 'policy') {
      onNavigate('procedures'); changeDetail('procedures', false); setPolicyTask(target.id); setDisclosures(old => new Set([...old, 'app-policy']));
      void invoke('experience.policy.get', { taskId: target.id }, value => { setPolicy(value); setPackageName(Array.isArray(value.policy?.packages) ? value.policy.packages[0] || '' : ''); });
    }
  };
  const stop = () => { if (!operation?.operationHandle || operation.stale || stopping) return; const ticket = epoch.current; setStopping(true); void run('experience.operation.stop', { operationHandle: operation.operationHandle }).then(() => { if (alive.current && ticket === epoch.current) setNotice('已收到停止请求回执，请继续核对原请求的最终结果。'); }).catch(failure => { if (alive.current && ticket === epoch.current) setError(failure.message); }).finally(() => { if (alive.current && ticket === epoch.current) setStopping(false); }); };
  const endReview = () => { if (busy || running) return; invalidate(); setOperation(null); setDraft(null); setManifest(''); setUnknown(null); setNotice('本地草稿审阅已结束。旧调用、用量和未知结果仍保留在原记录中。'); };
  const selectedApp = apps.find(row => row.appHandle === appHandle);
  const bases = rows(snapshot.procedures).filter(row => row.active === true && row.appKnowledge?.stage === 'verified' && row.appKnowledge.packageName === selectedApp?.packageName && row.appKnowledge.appVersion === selectedApp?.appVersion);
  const file = files[path], blocked = busy || running || !!unknown;
  const qualityBody = (kind: 'skill' | 'procedure') => <><Button variant="ghost" disabled={busy || (kind === 'skill' ? !skill?.skillHandle : !procedure?.procedureHandle)} onClick={() => quality(kind)}>读取原试用记录</Button>{qualityState.kind === kind && qualityState.loaded && <><p className="pw-learning-caption">工具成功和模型自述不等于业务成功。只按原任务结果归责，不扩大执行权限。</p>{!qualityState.applications.length ? <p className="pw-learning-caption">当前读取范围没有试用记录。</p> : <><SelectField label="实际结果" value={qualityOutcome} options={outcomes} onChange={setQualityOutcome} isDisabled={busy} /><TextAreaField label="核对依据" value={qualityNote} onChange={setQualityNote} maxLength={1000} isDisabled={busy} /><ul className="pw-learning-records">{rows(qualityState.applications).map((row, index) => <li key={row.applicationHandle || `${row.taskId}:${index}`}><strong>原任务 {row.taskId}</strong><p>{row.applicationState || row.status}</p><div className="pw-form-actions"><Button variant="ghost" disabled={busy || running} onClick={() => { void run('tasks.open', { id: row.taskId }).catch(failure => setError(failure.message)); }}>打开原任务</Button><Button variant="secondary" disabled={busy || !qualityNote.trim() || !row.applicationHandle || !!unknown} onClick={() => { void invoke('experience.quality.review', { applicationHandle: row.applicationHandle, outcome: qualityOutcome, note: qualityNote }, openReview); }}>核对此原步骤的结果</Button></div></li>)}</ul></>}{qualityState.cursor && <Button variant="ghost" disabled={busy || qualityState.stale} onClick={() => quality(kind, qualityState.cursor)}>读取下一页原记录</Button>}</>}</>;
  return <>
    {(operation || unknown || draft) && <section className="pw-learning-activity" aria-label="当前方法与技能请求"><div><strong>{unknown?.label || operation?.title || methodTitle(procedure?.record)}</strong><p>{unknown ? '原操作结果待核对，未重复提交。请先读取对应原记录，不能把未知当作失败或取消。' : operation?.stale ? '原起草窗口已失效。保留的内容仅供阅读，旧调用结果与用量仍需核对。' : operation?.queryFailed ? '原起草回执读取失败，结果未知。没有重新发送模型请求。' : stopping ? '正在请求停止，尚未确认最终结果。' : running ? '正在等待原起草回执。切换分区不会取消这次调用。' : operation?.error || (draft ? draft.stale ? '本地草稿已保留，原审阅句柄已失效，不能直接导入。' : '草稿待审阅，尚未保存或启用。' : '原起草请求已返回，请核对结果。')}</p></div><div className="pw-form-actions"><Button variant="ghost" onClick={() => { onNavigate('procedures'); setMethodOpen(true); }}>查看原方法与草稿</Button>{operation?.operationHandle && !operation.stale && <Button variant="ghost" disabled={busy} onClick={() => { void queryOperation(operation, epoch.current); }}>核对同一次原请求</Button>}{running && <Button variant="secondary" disabled={stopping} onClick={stop}>{stopping ? '停止请求中…' : '请求停止这次起草'}</Button>}{unknown && <Button variant="ghost" disabled={busy || !unknown.recovery} onClick={recoverUnknown}>读取相关原记录</Button>}{unknown && <Button variant="ghost" disabled={busy || running} onClick={endReview}>结束本地审阅并清除草稿，保留未知</Button>}</div></section>}
    {(error || notice || busy) && <div className="pw-learning-feedback">{error && <p role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}{busy && <p role="status">正在核对原经验回执…</p>}</div>}
    <div ref={root} className="pw-learning-experience">
      <TabPanel id="procedures" shouldForceMount hidden={panel !== 'procedures'} className="pw-learning-panel">
        <div hidden={methodOpen}>
          <header className="pw-learning-heading"><div><h2>方法</h2><p>阅读已审阅的方法，按需整理成可复用技能。</p></div><Button variant="ghost" iconOnly leadingIcon={RefreshCw} aria-label="刷新方法与来源技能" disabled={busy} onClick={load} /></header>
          {draft && <LearningObjectRow title="继续审阅技能草稿" detail={methodTitle(procedure?.record)} onClick={() => setMethodOpen(true)} />}
          {!rows(snapshot.procedures).length && <div className="pw-learning-empty"><FileText aria-hidden="true" /><h3>{loaded ? '还没有可整理的方法' : '正在读取方法'}</h3><p>学习任务形成的候选需要先审阅并采用，才会进入方法集合。</p></div>}
          <div className="pw-learning-list">{rows(snapshot.procedures).map(row => <LearningObjectRow key={row.id} title={methodTitle(row)} detail={methodStatus(row)} selected={procedure?.record.id === row.id} disabled={busy || running || !!draft && procedure?.record.id !== row.id} onClick={() => selectProcedure(row.id)} />)}</div>
          <Accordion className="pw-learning-disclosures" expandedKeys={disclosures} onExpandedChange={setDisclosures} allowsMultipleExpanded><AccordionItem id="app-policy" title="任务结束后的 App 提炼授权" description={policy?.policy?.enabled ? `已读取的任务授权已开启${policy.stale ? '，需重新核对' : ''}` : '默认关闭，按原私人任务和单个 App 限定'}><p className="pw-learning-caption">每轮最多一次模型调用，只形成待审候选。已开始或结果未知的旧调用不会自动重发。</p><SelectField label="原私人任务" value={policyTask} options={rows(state.tasks).filter(row => !row.raw?.origin && ['assistant', 'phone'].includes(row.raw?.kind)).map(row => ({ id: row.id, label: row.goal || row.id }))} onChange={value => { setPolicyTask(value); setPolicy(null); setPackageName(''); }} isDisabled={busy} /><Button variant="ghost" disabled={busy || !policyTask} onClick={() => { void invoke('experience.policy.get', { taskId: policyTask }, value => { setPolicy(value); setPackageName(Array.isArray(value.policy?.packages) ? value.policy.packages[0] || '' : ''); }); }}>读取原授权</Button>{policy && <><p>{policy.policy?.enabled ? '当前已开启' : '当前关闭'} · 原修订 {policy.policy?.revision}</p><TextField label="限定 App 包名" value={packageName} onChange={setPackageName} isDisabled={busy} /><div className="pw-form-actions"><Button variant="secondary" disabled={busy || !packageName.trim() || !policy.policyHandle || !!unknown} onClick={() => { void invoke('experience.policy.review', { policyHandle: policy.policyHandle, enabled: true, packageName }, openReview); }}>核对此原任务的限定授权</Button><Button variant="ghost" disabled={busy || !policy.policy?.enabled || !policy.policyHandle || !!unknown} onClick={() => { void invoke('experience.policy.review', { policyHandle: policy.policyHandle, enabled: false, packageName: '' }, openReview); }}>核对关闭后续提炼</Button></div>{rows(policy.jobs).map(job => <p key={job.taskId}>{job.status} · {job.authorizationStatus}</p>)}</>}</AccordionItem></Accordion>
        </div>
        <div hidden={!methodOpen}>
          <Button variant="ghost" leadingIcon={ArrowLeft} onClick={() => changeDetail('procedures', false)}>返回方法</Button>
          {procedure && <article className="pw-learning-reading"><header className="pw-learning-heading"><div><span className="pw-learning-eyebrow">原方法 · 修订 {procedure.record.revision}</span><h2>{methodTitle(procedure.record)}</h2><p>{methodStatus(procedure.record)}</p></div>{procedure.stale && <Button variant="secondary" disabled={busy || running} onClick={() => selectProcedure(procedure.record.id)}>重新读取原方法</Button>}</header><AIAnswer text={procedure.record.text || ''} showActions={false} /><div className="pw-learning-conditions"><h3>适用条件</h3><p className="pw-learning-verbatim">{procedure.record.applicability || '原方法未提供额外适用条件。'}</p></div>{procedure.stale && <p className="pw-learning-caution">上次的阅读内容已保留，请重新读取原版本后再审阅。已有草稿不会因此自动取得新导入权限。</p>}
            {!draft && <div className="pw-learning-next"><Button disabled={blocked || !!operation || !procedure.procedureHandle} onClick={() => { void invoke('experience.draft.review', { procedureHandle: procedure.procedureHandle }, openReview); }}>核对并起草技能</Button><p className="pw-learning-caption">先核对将发送的内容，再明确允许这一次模型调用。</p></div>}
            {draft && <section className="pw-learning-draft"><header className="pw-learning-heading"><div><h3>技能草稿</h3><p>尚未保存为技能，尚未启用。</p></div></header><TextAreaField label="完整 SKILL.md" value={manifest} onChange={setManifest} maxLength={64000} isDisabled={busy} /><Accordion className="pw-learning-disclosures"><AccordionItem id="draft-resources" title="附加资料与所需能力" description={`${Object.keys(draft.draft?.resources || {}).length} 份附加资料 · 导入不授予执行权限`}>{Object.entries(draft.draft?.resources || {}).map(([name, body]) => <article className="pw-learning-resource" key={name}><h4>{name}</h4><AIAnswer showActions={false}><div className="pw-learning-verbatim">{String(body)}</div></AIAnswer></article>)}<p>所需能力：{(draft.inspection?.policy?.requiredCapabilities || []).join('、') || '无额外能力声明'}</p><p>资料是待审参考，不授予工具或脚本执行权限。</p></AccordionItem></Accordion>{draft.stale && <p className="pw-learning-caution">原生草稿审阅已失效，保留的文本不能直接重新检查或导入。可先保留文字，明确结束后再从当前原方法开始新的审阅。</p>}<div className="pw-form-actions"><Button variant="secondary" disabled={blocked || !manifest.trim() || !draft.draftHandle} onClick={() => { void invoke('experience.inspect', { draftHandle: draft.draftHandle, manifest }, acceptDraft); }}>重新检查当前草稿</Button><Button disabled={blocked || !draft.draftHandle || manifest !== draft.draft?.manifest} onClick={() => { void invoke('experience.import.review', { draftHandle: draft.draftHandle }, openReview); }}>核对并保存为停用技能</Button></div>{manifest !== draft.draft?.manifest && <p className="pw-learning-caption">正文已编辑，保存前需要重新检查当前草稿。</p>}</section>}
            <Accordion className="pw-learning-disclosures" expandedKeys={disclosures} onExpandedChange={setDisclosures} allowsMultipleExpanded>
              <AccordionItem id="method-app" title="修订 App 经验" description="仅使用原任务实际观察到的 App 版本"><Button variant="ghost" disabled={blocked || !procedure.procedureHandle} onClick={() => { void invoke('experience.apps', { procedureHandle: procedure.procedureHandle }, value => { setApps(rows(value.apps)); setAppsRead(true); setAppHandle(''); setBaseId(''); }); }}>读取原任务观察到的 App 版本</Button>{appsRead && !apps.length && <p className="pw-learning-caption">原任务没有可用于修订的 App 版本记录。</p>}{apps.length > 0 && <><SelectField label="原任务中的 App 版本" value={appHandle} options={apps.map(row => ({ id: row.appHandle, label: `${row.packageName} · ${row.versionName || row.appVersion}` }))} isDisabled={blocked} onChange={value => { setAppHandle(value); setBaseId(''); }} /><SelectField label="修订依据的旧稿" value={baseId || '__new__'} options={[{ id: '__new__', label: '首次建立，无旧稿' }, ...bases.map(row => ({ id: row.id, label: methodTitle(row) }))]} onChange={value => setBaseId(value === '__new__' ? '' : value)} isDisabled={blocked} /><CheckboxField isSelected={lesson} onChange={setLesson} isDisabled={blocked}>整理为失败教训，保留实际归责</CheckboxField><Button variant="secondary" disabled={blocked || !appHandle || !!operation || !!draft} onClick={() => {
                if (!baseId) { void invoke('experience.app.review', { appHandle, baseHandle: '', lesson }, openReview); return; }
                const ticket = epoch.current; void invoke('experience.procedure', { id: baseId }, async value => { if (value.record?.id !== baseId) throw new Error('旧稿身份不符。'); const reviewed = await run('experience.app.review', { appHandle, baseHandle: value.procedureHandle, lesson }); if (alive.current && ticket === epoch.current && nativeActive()) openReview(remember(reviewed)); });
              }}>核对这次 App 经验起草</Button></>}</AccordionItem>
              <AccordionItem id="method-quality" title="试用记录与效果归责" description="核对原任务实际结果，不把工具成功当作业务成功">{qualityBody('procedure')}</AccordionItem>
              <AccordionItem id="method-finish" title="结束本次草稿审阅" description="返回列表会保留草稿；仅在此明确结束后清除本地草稿"><p className="pw-learning-caption">请先保存需要的草稿文字，并核对原操作结果。结束审阅不会取消已发生的模型调用、清除用量或重新发送请求。</p><Button variant="ghost" disabled={busy || running || !operation && !draft && !unknown} onClick={endReview}>结束审阅并清除本地草稿</Button></AccordionItem>
            </Accordion>
          </article>}
        </div>
      </TabPanel>
      <TabPanel id="skills" shouldForceMount hidden={panel !== 'skills'} className="pw-learning-panel">
        <div hidden={skillOpen}>
          <header className="pw-learning-heading"><div><h2>来源技能</h2><p>阅读当前原版本，核对来源，再决定是否批准。</p></div><Button variant="ghost" iconOnly leadingIcon={RefreshCw} aria-label="刷新来源技能" disabled={busy} onClick={load} /></header>
          {!rows(snapshot.skills).filter(row => row.origin).length && <div className="pw-learning-empty"><FileText aria-hidden="true" /><h3>{loaded ? '还没有来源型技能' : '正在读取来源技能'}</h3><p>从方法起草并保存后，技能会以停用状态出现在这里。</p></div>}
          <div className="pw-learning-list">{rows(snapshot.skills).filter(row => row.origin).map(row => <LearningObjectRow key={row.id} title={row.name} detail={`${row.enabled ? '已批准，执行仍需权限' : '停用'}${row.description ? ` · ${row.description}` : ''}`} disabled={busy} selected={skill?.skill.id === row.id} onClick={() => selectSkill(row.id)} />)}</div>
        </div>
        <div hidden={!skillOpen}>
          <Button variant="ghost" leadingIcon={ArrowLeft} onClick={() => changeDetail('skills', false)}>返回来源技能</Button>
          {skill && <article className="pw-learning-reading"><header className="pw-learning-heading"><div><span className="pw-learning-eyebrow">来源技能 · 修订 {skill.skill.revision}</span><h2>{skill.skill.name}</h2><p>{skill.skill.description}</p><p>{skill.skill.enabled ? '已批准，执行仍需权限' : '停用'} · 来源 {skill.skill.originStatus || '待核对'}</p></div>{skill.stale && <Button variant="secondary" disabled={busy} onClick={() => selectSkill(skill.skill.id)}>重新读取原技能</Button>}</header>
            {skill.stale && <p className="pw-learning-caution">上次的阅读内容已保留，原授权已失效。请重新读取原技能，再从首页连续读取当前正文。</p>}
            <div className="pw-learning-source-picker"><SelectField label="当前原文件" value={path} options={Array.from(new Set<string>(['SKILL.md', ...(skill.skill.resources || [])])).map(id => ({ id, label: id }))} onChange={setPath} isDisabled={busy} /><Button variant="secondary" disabled={busy || !skill.skillHandle} onClick={() => read(0)}>{file ? '从头重新读取' : '读取原正文'}</Button></div>
            {file && <><AIAnswer showActions={false}>{path === 'SKILL.md' ? <div className="pw-learning-verbatim">{file.content}</div> : <pre className="pw-learning-verbatim">{file.content}</pre>}</AIAnswer><p className="pw-learning-caption">{file.stale || skill.stale ? '上次读取的内容' : file.hasMore ? '正文尚未读完' : '当前文件已完整读取'} · 已显示 {file.content?.length || 0} 字符</p>{file.hasMore && <Button variant="ghost" disabled={busy || !skill.skillHandle || file.stale} onClick={() => read(file.nextOffset)}>继续读取原文件</Button>}</>}
            <div className="pw-learning-next"><Button disabled={busy || !!unknown || !manifestRead || !skill.skillHandle || skill.skill.enabled === true} onClick={() => { void invoke('experience.approve.review', { skillHandle: skill.skillHandle }, openReview); }}>核对并批准此原版本</Button>{!manifestRead && !skill.skill.enabled && <p className="pw-learning-caption">先连续读完当前版本的 SKILL.md，再核对批准范围。阅读附加文件不会清除已读正文。</p>}</div>
            <Accordion className="pw-learning-disclosures" expandedKeys={disclosures} onExpandedChange={setDisclosures} allowsMultipleExpanded><AccordionItem id="skill-quality" title="试用记录与效果归责" description="只核对这份原技能的实际使用记录">{qualityBody('skill')}</AccordionItem></Accordion>
          </article>}
        </div>
      </TabPanel>
    </div>
    <SettingsDialog isOpen={!!review} onClose={() => { if (!busy) closeReview(); }} title={labels[review?.operation] || '核对原经验操作'}>
      {review?.operation === 'draftExperience' || review?.operation === 'draftAppRevision' ? <><p>将所选原方法正文与适用条件发送给开始时的当前模型，生成一份待审草稿。不会执行工具，不会自动保存为正式记忆或启用技能。</p><AIAnswer text={review?.before?.text || ''} showActions={false} />{review?.before?.applicability && <p>适用条件：{review.before.applicability}</p>}{review?.operation === 'draftAppRevision' && <p>{review.payload.packageName} · {review.payload.appVersion} · {review.payload.lesson ? '教训' : '方法修订'}</p>}</> : review?.operation === 'importExperience' ? <p>把刚才核对的完整草稿和资料保存为新技能，保持停用；原方法来源绑定保留。</p> : review?.operation === 'approveExperience' ? <p>批准刚才完整读取的 SKILL.md 与原包摘要；来源变化或遗忘后仍会失效。运行时还需原宿主能力和权限。</p> : review?.operation === 'setAppLearningPolicy' ? <p>{review.payload.enabled ? `允许此原任务终态后，向当前配置模型发送 ${review.payload.packages?.join('、')} 的原动作证据，每轮最多一次，只形成待审候选。` : '关闭此原任务的后续自动提炼。已发生的模型调用、用量和未知结果保留。'}</p> : <><p>结果：{outcomes.find(row => row.id === review?.payload?.outcome)?.label}</p><p>{review?.payload?.note}</p><p>只归责于所示原任务与步骤；不会增加执行权限。</p></>}
      <CheckboxField isSelected={confirmed} onChange={setConfirmed}>我已核对原内容、版本和影响，确认这一次操作</CheckboxField><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={closeReview}>返回</Button><Button disabled={busy || !confirmed} onClick={confirm}>确认这一次操作</Button></div>
    </SettingsDialog>
  </>;
}
