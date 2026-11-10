import { useEffect, useRef, useState } from 'react';
import { Accordion, AccordionItem, Button, CheckboxField, RadioOptions, SelectField, StretchSwitch, TextAreaField } from './forms-library';
import { bridge } from './bridge';
import { appendRemoteClipboard, managedRemoteRows, remoteApprovalClaim, remoteDraftState, remoteEditor, remoteObject as object, remoteProgress, remoteReadsPaused, remoteRows as rows, sameRemoteEditor, savedRemoteEditor, type RemoteData as Data, type RemoteEditor } from './remote-task-workspace';
import './remote-task-workspace.css';
import './collaboration-depth.css';
import { RemoteTaskArtifacts } from './RemoteTaskArtifacts';
import { RemoteTaskPreview } from './RemoteTaskPreview';
import { RemoteTaskHarness } from './RemoteTaskHarness';
import { RemoteTaskInputActions } from './RemoteTaskInputs';

type Run = (action: string, args?: Data) => Promise<any>;
const message = (error: unknown) => error instanceof Error ? error.message : String(error);
const executionLabel = (value: string) => ({ waiting_approval: '等待你决定', awaiting_approval: '等待你决定', working: '进行中', running: '进行中', waiting: '等待下一步', completed: '已完成', failed: '未完成', paused: '已暂停', cancelled: '已停止', status: '执行状态', tool: '工具观察' } as Record<string,string>)[value] || value;
const workerLabel = (value: string) => ({ codex_app_server: 'Codex', pi: 'Pi', claude_code: 'Claude Code' } as Record<string,string>)[value] || value;
const storedEditors = new Map<string, RemoteEditor>();
const supported = (action: string) => bridge.getState().capabilities.commands.includes(action);
const windowIdentity = () => {
  const view = object(bridge.getState().view);
  return [bridge.getDocumentEpoch(), view.resumeRevision, view.section, view.taskId].join('|');
};
const taskVisible = (taskId: string) => document.visibilityState === 'visible' && object(bridge.getState().view).taskId === taskId;
const sourceLabel = (source: Data) => source.sourceProtocol === 'codex_public_child_v1' ? `Codex 子来源 ${source.nativeChildThreadId || ''}` : source.kind === 'child' ? `子来源 ${source.nativeAgentId || source.parentToolUseId || ''}` : '主／未标记来源';

/** Only this hook owns a native original-task observation window. Nothing is replayed on resume. */
function useRemoteTask(taskId: string) {
  const [snapshot, setSnapshot] = useState<Data | null>(null), [pending, setPending] = useState(''), [error, setError] = useState(''), [stale, setStale] = useState(false);
  const live = useRef<Data | null>(null), active = useRef<object | null>(null), alive = useRef(true), generation = useRef(0), handle = useRef(''), identity = useRef(windowIdentity()), firstRead = useRef(false);
  const retire = () => { generation.current++; live.current = null; if (alive.current) { setStale(true); setError(''); } };
  const act: Run = async (action, args = {}) => {
    const opening = action === 'remoteTask.open';
    if (!alive.current || active.current || !taskVisible(taskId) || !supported(action) || !opening && !live.current) return;
    const captured = live.current, epoch = generation.current, window = windowIdentity(), request = {};
    const current = () => alive.current && generation.current === epoch && active.current === request && taskVisible(taskId) && windowIdentity() === window;
    active.current = request; setPending(action); setError('');
    try {
      const value = await bridge.command(action, opening ? { taskId } : { ...args, windowHandle: captured!.windowHandle, reviewId: captured!.reviewId });
      if (!current()) { if (opening && value?.snapshot?.windowHandle) void bridge.command('remoteTask.close', { windowHandle: value.snapshot.windowHandle }).catch(() => {}); return; }
      if (action === 'remoteTask.clipboard') return value;
      const incoming = object(value?.snapshot);
      if (incoming.taskId !== taskId || typeof incoming.windowHandle !== 'string' || !incoming.windowHandle ||
        !opening && incoming.windowHandle !== captured?.windowHandle) throw new Error('原任务回执身份不可核对，请重新读取。');
      handle.current = incoming.windowHandle; live.current = incoming; if (opening) firstRead.current = true; setSnapshot(incoming); setStale(false);
      return value;
    } catch (failure) { if (current()) { setError(message(failure)); live.current = null; setStale(true); } }
    finally { if (active.current === request) { active.current = null; if (alive.current) setPending(''); } }
  };
  useEffect(() => {
    alive.current = true; identity.current = windowIdentity(); void act('remoteTask.open');
    const inspect = () => { const next = windowIdentity(); if (next !== identity.current || !taskVisible(taskId)) { identity.current = next; retire(); } };
    const unsubscribe = bridge.subscribe(inspect);
    document.addEventListener('visibilitychange', inspect); window.addEventListener('pagehide', retire);
    return () => { alive.current = false; generation.current++; live.current = null; unsubscribe(); document.removeEventListener('visibilitychange', inspect); window.removeEventListener('pagehide', retire);
      const original = handle.current; handle.current = ''; if (original) void bridge.command('remoteTask.close', { windowHandle: original }).catch(() => {}); };
  }, [taskId]);
  useEffect(() => {
    if (!snapshot || stale || pending || snapshot.configurationOnly || !firstRead.current && snapshot.readPollAllowed !== true) return;
    const captured = snapshot, originalWindow = windowIdentity(); let timer: ReturnType<typeof setTimeout>;
    const read = () => {
      if (!alive.current || live.current !== captured || !taskVisible(taskId) || windowIdentity() !== originalWindow) return;
      if (remoteReadsPaused() || active.current) { timer = setTimeout(read, 500); return; }
      firstRead.current = false; void act('remoteTask.refresh');
    };
    timer = setTimeout(read, firstRead.current ? 250 : snapshot.readPollDelayMillis === 500 ? 500 : 4000);
    return () => clearTimeout(timer);
  }, [snapshot, pending, stale]);
  return { snapshot, pending, error, stale, act, current: () => live.current };
}

export function RemoteDraftEditor({ snapshot, busy, active = true, run }: { snapshot: Data; busy: boolean; active?: boolean; run: Run }) {
  const taskId = String(snapshot.taskId), [editor, setEditor] = useState<RemoteEditor>(() => storedEditors.get(taskId) || remoteEditor(snapshot, crypto.randomUUID()));
  const [notice, setNotice] = useState(''), [pasting, setPasting] = useState(false), [clipboardUsed, setClipboardUsed] = useState('');
  const live = useRef({ snapshot, editor, active, busy }), mounted = useRef(true), used = useRef('');
  live.current = { snapshot, editor, active, busy };
  const edit = (next: RemoteEditor) => { live.current.editor = next; setEditor(next); setNotice(''); storedEditors.set(taskId, next); if (storedEditors.size > 32) storedEditors.delete(storedEditors.keys().next().value!); };
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  useEffect(() => {
    const saved = rows(snapshot.inputDrafts).find(row => row.id === editor.id);
    if (saved && saved.text === editor.text && saved.mode === editor.mode && saved.revision > editor.revision && saved.targetCurrent === true) edit(savedRemoteEditor(saved));
    else if (!saved && editor.revision === 0 && !editor.scope && snapshot.fresh === true && !snapshot.teamRemoteWriter && !snapshot.configurationOnly) {
      // Opening may precede the first protocol observation. Attach only a new,
      // never-bound editor; an existing scope must not follow a different turn.
      const scopes = object(snapshot.draftScopes);
      const initial = remoteEditor(snapshot, editor.id, scopes[editor.mode] ? editor.mode : undefined);
      if (initial.scope) edit({ ...initial, text: editor.text });
    }
  }, [snapshot]);
  const state = remoteDraftState(snapshot, editor), delivery = object(snapshot.inputDelivery), currentTurn = object(delivery.currentTurn), nextTurn = object(delivery.nextTurn);
  const captured = { ...editor }, review = snapshot.reviewId, window = snapshot.windowHandle;
  const unchanged = () => mounted.current && live.current.active && !live.current.busy && taskVisible(taskId) &&
    live.current.snapshot.reviewId === review && live.current.snapshot.windowHandle === window && sameRemoteEditor(captured, live.current.editor);
  const action = (command: string, args: Data) => { if (!unchanged()) return; const now = remoteDraftState(live.current.snapshot, live.current.editor);
    if (!(command === 'sendDraft' ? now.canSend : command === 'withdrawDraft' ? now.canWithdraw : now.canSave)) return;
    return run(`remoteTask.${command}`, args);
  };
  const paste = async () => {
    if (!unchanged() || pasting || used.current === review || !state.canPaste || !supported('remoteTask.clipboard')) return;
    used.current = review; setClipboardUsed(review); setPasting(true); setNotice('');
    try { const result = await run('remoteTask.clipboard', { draftId: captured.id, draftRevision: captured.revision, mode: captured.mode });
      // The parent marks its request busy while pending; compare the exact editor again after it settles.
      const now = live.current;
      if (!mounted.current || !now.active || !taskVisible(taskId) || now.snapshot.reviewId !== review || now.snapshot.windowHandle !== window || !sameRemoteEditor(captured, now.editor)) return;
      edit({ ...captured, text: appendRemoteClipboard(captured.text, result) }); setNotice('已追加到本地编辑框；尚未保存，也未发送。');
    } catch (failure) { if (mounted.current && sameRemoteEditor(captured, live.current.editor)) setNotice(message(failure)); }
    finally { if (mounted.current) setPasting(false); }
  };
  if (snapshot.configurationOnly || snapshot.teamRemoteWriter || snapshot.draftEditorAvailable === false || !rows(snapshot.inputDrafts).length && (delivery.protocolEvidenceReported !== true || !nextTurn.adapterMounted && !currentTurn.adapterMounted)) return null;
  return <section className="pw-remote-drafts" aria-label="原会话输入草稿">
    <div className="pw-section-heading"><h3>原会话输入草稿</h3><Button variant="ghost" size="small" disabled={busy || pasting || !active} onClick={() => edit(remoteEditor(snapshot, crypto.randomUUID()))}>新草稿</Button></div>
    <p className="pw-muted">保存和排队只写入本机。查询、重连或恢复页面不会自动发送；发送仍使用原会话配置。</p>
    {rows(snapshot.inputDrafts).length > 0 && <SelectField label="已保存的草稿" value={state.saved ? editor.id : ''} isDisabled={busy || pasting || !active}
      options={rows(snapshot.inputDrafts).map(row => ({ id: row.id, label: `${row.mode === 'steer' ? '当前执行' : '下一轮'} · ${row.state} · ${String(row.text || '').slice(0, 32)}` }))}
      onChange={id => { const row = rows(live.current.snapshot.inputDrafts).find(value => value.id === id); if (row) edit(savedRemoteEditor(row)); }} />}
    <RadioOptions label="输入方式" value={editor.mode} isDisabled={busy || pasting || !active || !!state.saved} onChange={mode => { if (state.saved) return; const next = remoteEditor(snapshot, editor.id, mode); edit({ ...next, text: editor.text }); }}
      options={[{ id: 'next_turn', label: '排队到下一轮', description: '原会话结算后明确发送', disabled: nextTurn.adapterMounted !== true }, ...(currentTurn.adapterMounted ? [{ id: 'steer', label: '补充当前执行', description: currentTurn.delivery === 'native_queue' ? '写入原生队列，是否消费仍待观察' : '仅发给已核对的原 turn' }] : [])]} />
    {delivery.driver === 'pi' && <p className="pw-field-hint">{nextTurn.noToolsSessionOnly ? 'Pi no-tools 会话不提供编码工具能力。' : ''}队列接受不证明模型已消费；只核对原任务回执。</p>}
    {delivery.driver === 'codex_app_server' && <p className="pw-field-hint">接受输入不证明模型已采用；停止任务也不会回滚已经发生的作用。</p>}
    <TextAreaField label="草稿内容" value={editor.text} onChange={text => edit({ ...live.current.editor, text })} rows={5} maxLength={12000} isDisabled={busy || pasting || !active || !!state.saved && !['draft', 'queued'].includes(state.saved.state)} />
    {state.saved && !['draft', 'queued'].includes(state.saved.state) && <p className="pw-field-hint">原操作：{state.saved.deliveryStatus || state.saved.journalState || state.saved.state}。仅查询原记录，不会重复派发。</p>}
    {!state.sameTarget && <p className="pw-field-error">原草稿或会话身份已变化，保留文字供你核对。请查询后明确新建草稿。</p>}
    {state.saved?.state === 'queued' && !state.unchanged && <p className="pw-field-hint">编辑内容尚未保存，不能发送旧队列条目。</p>}
    <div className="pw-form-actions">
      {supported('remoteTask.clipboard') && <Button variant="secondary" disabled={!active || busy || pasting || !state.canPaste || clipboardUsed === review} onClick={paste}>读取本机剪贴板并追加一次</Button>}
      <Button variant="secondary" disabled={!active || busy || pasting || !state.canSave} onClick={() => action('saveDraft', { draftId: captured.id, draftRevision: captured.revision, mode: captured.mode, text: captured.text, queued: false })}>仅保存草稿</Button>
      <Button variant="secondary" disabled={!active || busy || pasting || !state.canSave} onClick={() => action('saveDraft', { draftId: captured.id, draftRevision: captured.revision, mode: captured.mode, text: captured.text, queued: true })}>保存并排队</Button>
      {state.saved && <Button variant="ghost" disabled={!active || busy || pasting || !state.canWithdraw} onClick={() => action('withdrawDraft', { draftId: captured.id, draftRevision: captured.revision })}>撤回本地草稿</Button>}
      {state.saved?.state === 'queued' && <Button disabled={!active || busy || pasting || !state.canSend} onClick={() => action('sendDraft', { draftId: captured.id, draftRevision: captured.revision })}>明确发送这个队列条目一次</Button>}
    </div>
    {notice && <p className="pw-field-hint" role="status">{notice}</p>}
  </section>;
}

export function RemoteProgress({ snapshot }: { snapshot: Data }) {
  const [filter, setFilter] = useState('*'), [expanded, setExpanded] = useState(false);
  const { progress, children, visible } = remoteProgress(snapshot, filter);
  useEffect(() => { setFilter('*'); setExpanded(false); }, [snapshot.taskId, snapshot.source?.jobOperationId, progress.generation]);
  if (!snapshot.progress) return null;
  return <section className="pw-remote-progress" aria-label="原电脑执行进度"><h3>{progress.readStatus === 'confirmed' ? '本次核对的执行进度' : '已保存的执行进度'}</h3>
    <p className="pw-muted">有界读取的原任务公开观察。关闭页面只停止观察，不会停止电脑任务；这不是完整 CLI 历史或子会话控制。</p>
    {progress.readStatus !== 'confirmed' && <p className="pw-field-hint">本次补流尚未确认，原记录和游标保留；没有重发任务、批准或答案。</p>}
    {progress.hasMore && <p className="pw-field-hint">还有保留记录可读取，请继续查询原任务。</p>}
    {Number(progress.unconfirmedSources) > 0 && <p className="pw-field-error">{progress.unconfirmedSources} 条观察的原生来源无法核对，未混入主来源或其他子来源。</p>}
    {rows(progress.gaps).map((gap, index) => <p key={index} className="pw-field-hint">{gap.reason === 'retention_gap' ? `事件 ${gap.fromSequence}–${gap.throughSequence} 已超出电脑保留窗口。` : '来源代次已变化，旧代遗漏范围无法确认。'}没有补造遗漏内容。</p>)}
    {(children.length > 0 || filter !== '*') && <SelectField label="进度来源" value={filter} onChange={value => { setFilter(value); setExpanded(false); }} options={[{ id: '*', label: '全部原来源' }, { id: 'main_or_unspecified', label: '主／未标记来源' }, ...children.map(source => ({ id: source.key, label: sourceLabel(source) }))]} />}
    {!visible.length && <p className="pw-muted">当前保留窗口没有此来源的可核对记录。</p>}
    <ol className="pw-remote-events">{(expanded ? visible : visible.slice(-6)).map(({ event, source }, index) => <li key={`${event.sequence || index}:${source.key}`}><small>{sourceLabel(source)}{source.kind === 'child' ? ' · 只读' : ''}</small><strong>{[event.tool || executionLabel(event.kind || ''), executionLabel(event.status || '')].filter(Boolean).join(' · ')}</strong>
      {event.textForm === 'received_public_delta_not_complete_message' && <p className="pw-field-hint">已收到的公开增量观察，不代表完整消息。</p>}
      {['summary', 'command', 'text', 'explanation', 'counters'].map(key => event[key] ? <div key={key}><p>{typeof event[key] === 'string' ? event[key] : JSON.stringify(event[key])}</p>{event[`${key}Truncated`] && <small>本段已截断。</small>}</div> : null)}
      {event.kind === 'usage' && <small>供应商原 thread 累计观察，不逐快照累加，不代表本任务账单。</small>}
    </li>)}</ol>
    {visible.length > 6 && <Button variant="ghost" onClick={() => setExpanded(value => !value)}>{expanded ? '收起早前记录' : `查看该来源保留的 ${visible.length} 条`}</Button>}
  </section>;
}

function RemoteQuestion({ request, snapshot, busy, run }: { request: Data; snapshot: Data; busy: boolean; run: Run }) {
  const [answers, setAnswers] = useState<Record<string, string[]>>({}), [custom, setCustom] = useState<Record<string, string>>({});
  const latest = useRef({ request, snapshot, answers, custom }); latest.current = { request, snapshot, answers, custom };
  const update = (nextAnswers: Record<string, string[]>, nextCustom: Record<string, string>) => { latest.current.answers = nextAnswers; latest.current.custom = nextCustom; setAnswers(nextAnswers); setCustom(nextCustom); };
  const questions = rows(request.questions), previous = rows(snapshot.task?.operations).find(op => op.tool === 'remote_question_answer' && op.arguments?.request?.requestId === request.requestId && op.arguments?.request?.requestHash === request.requestHash && !(op.state === 'remote_question_rejected' && op.receipt?.notDispatchedConfirmed === true));
  const writable = !busy && snapshot.fresh && snapshot.canAnswerQuestions && request.canAnswer && !previous;
  const actual = Object.fromEntries(questions.map(q => [q.id, custom[q.id]?.trim() ? [custom[q.id]] : answers[q.id] || []]));
  const complete = questions.length > 0 && questions.every(q => !q.requiresPrivateInput && actual[q.id].length > 0 && (q.multiSelect || actual[q.id].length === 1));
  return <section className="pw-remote-question"><h3>Agent 需要你回答</h3><p className="pw-muted">只回答当前原问题，不批准命令或文件修改。</p>
    {questions.map(q => <div key={q.id}><h4>{q.header}</h4><p>{q.text}</p>{q.requiresPrivateInput ? <p className="pw-field-error">秘密输入不在此收集，请在可信电脑处理。</p> : <>
      {rows(q.options).map(option => <CheckboxField key={option.label} isSelected={(answers[q.id] || []).includes(option.label) && !custom[q.id]?.trim()} isDisabled={!writable} onChange={checked => { const old = latest.current.answers[q.id] || []; update({ ...latest.current.answers, [q.id]: q.multiSelect ? checked ? [...old, option.label] : old.filter(value => value !== option.label) : checked ? [option.label] : [] }, { ...latest.current.custom, [q.id]: '' }); }}>{option.label}{option.description ? ` — ${option.description}` : ''}</CheckboxField>)}
      {q.allowOther && <TextAreaField label="其他回答（不填写密码、验证码）" value={custom[q.id] || ''} maxLength={2000} isDisabled={!writable} onChange={text => { update(text.trim() ? { ...latest.current.answers, [q.id]: [] } : latest.current.answers, { ...latest.current.custom, [q.id]: text }); }} />}</>}</div>)}
    {previous ? <p className="pw-field-hint">答案已在原任务登记：{previous.state}。请查询原回执。</p> : <Button disabled={!writable || !complete} onClick={() => { const current = latest.current; if (!writable || !complete || current.snapshot.reviewId !== snapshot.reviewId || current.request.requestHash !== request.requestHash || current.answers !== answers || current.custom !== custom) return; void run('remoteTask.answerQuestion', { requestId: request.requestId, requestHash: request.requestHash, answers: actual }); }}>发送这份正常回答一次</Button>}
  </section>;
}

export function RemoteTaskWorkspace({ taskId }: { taskId: string }) {
  const { snapshot, pending, error, stale, act, current } = useRemoteTask(taskId);
  const enabled = !!snapshot && !stale && taskVisible(taskId), source = object(snapshot?.source);
  if (!supported('remoteTask.open')) return null;
  return <section className="pw-remote-workspace pw-depth-collaboration" aria-label="原电脑任务"><div className="pw-section-heading"><h3>电脑会话</h3><Button variant="secondary" disabled={!!pending || !taskVisible(taskId)} onClick={() => void act(!snapshot || stale ? 'remoteTask.open' : 'remoteTask.refresh')}>{pending ? '正在核对…' : !snapshot || stale ? '重新打开原任务' : '查询原任务'}</Button></div>
    {error && <p className="pw-field-error" role="alert">{error}</p>}{stale && <p className="pw-field-hint">原页面已变化，请重新核对；没有自动发送旧输入。</p>}
    {snapshot && <><div className="pw-remote-session-identity"><p className="pw-muted">{workerLabel(source.worker || '原执行器')} · {executionLabel(source.executorStatus || '状态待核对')}</p>
      {source.threadId && <Accordion className="pw-remote-identity"><AccordionItem title="原会话身份"><p className="pw-source-copy">{source.threadId}</p><p className="pw-field-hint">{source.worker} · {source.executorStatus}</p></AccordionItem></Accordion>}</div>
      {!snapshot.fresh && !stale && <p className="pw-field-hint">{snapshot.reason || '请查询原任务后再作出新的决定。'}</p>}
      <div className="pw-remote-work-layout"><div className="pw-remote-primary" aria-label="待处理与输入">
      {rows(source.pendingApprovals).map(approval => <section className="pw-remote-question" key={`${snapshot.reviewId}:${approval.requestId}`}><h4>{approval.tool || approval.method || '原执行请求'}</h4><p>{approval.summary || approval.command || approval.reason || '请核对原请求的完整范围。'}</p><Accordion className="pw-remote-request-details"><AccordionItem title="准确请求参数" description="原请求身份与完整参数"><pre>{JSON.stringify(approval, null, 2)}</pre></AccordionItem></Accordion>{remoteApprovalClaim(snapshot, approval.requestId) ? <p className="pw-field-hint">决定已登记，只查询原回执，不会再次发送。</p> : <div className="pw-form-actions">
        {['decline', 'accept', ...(!snapshot.teamRemoteWriter ? ['cancel'] : [])].map(decision => <Button key={decision} variant={decision === 'accept' ? 'primary' : decision === 'cancel' ? 'ghost' : 'secondary'} disabled={!enabled || !!pending || !snapshot.fresh || !approval.requestId || decision === 'accept' && (approval.canAccept !== true || Array.isArray(snapshot.teamMemberApprovalAcceptIds) && !snapshot.teamMemberApprovalAcceptIds.includes(approval.requestId))} onClick={() => { const now = current(); if (!now || now.reviewId !== snapshot.reviewId || remoteApprovalClaim(now, approval.requestId)) return; void act('remoteTask.approve', { requestId: approval.requestId, decision }); }}>{decision === 'accept' ? '只允许这一次' : decision === 'cancel' ? '取消这一次' : '拒绝这一次'}</Button>)}</div>}</section>)}
      {rows(snapshot.task?.operations).filter(operation => operation.tool === 'remote_approval').slice(-8).map(operation => <p className="pw-field-hint" key={operation.operationId}>本机单次决定：{operation.arguments?.requestId} · {operation.arguments?.decision} · {operation.state === 'remote_approval_receipt' ? '已收到协议回执，非工具成功' : '决定已登记，仅查询原回执'}</p>)}
      {rows(source.pendingQuestions).map(request => <RemoteQuestion key={`${taskId}:${source.jobOperationId}:${request.requestId}:${request.requestHash}`} request={request} snapshot={snapshot} busy={!enabled || !!pending} run={act} />)}
      <RemoteDraftEditor key={taskId} snapshot={snapshot} busy={!!pending} active={enabled} run={act} />
      </div><aside className="pw-remote-context" aria-label="执行观察与来源">
      <RemoteProgress snapshot={snapshot} />
      <RemoteTaskHarness snapshot={snapshot} busy={!!pending} active={enabled} run={act} returnToTask={() => void act('remoteTask.open')} />
      <RemoteTaskArtifacts snapshot={snapshot} busy={!!pending} active={enabled} run={act} />
      <RemoteTaskInputActions snapshot={snapshot} enabled={enabled && !pending} onReturn={() => void act('remoteTask.open')} />

      <Accordion><AccordionItem title="本机提醒与连接核对"><StretchSwitch label="原任务需要我时提醒" checked={snapshot.attention?.enabled === true} disabled={!enabled || !!pending || !snapshot.fresh} onChange={value => { if (current()?.reviewId === snapshot.reviewId) void act('remoteTask.setAttention', { enabled: value }); }} description="只修改这个原电脑任务的本机提醒。" />
        <Button variant="secondary" disabled={!enabled || !!pending || !snapshot.fresh || snapshot.teamRemoteWriter} onClick={() => void act('remoteTask.inspectRecovery')}>核对原连接恢复状态</Button></AccordionItem></Accordion>
      </aside></div>
      {!snapshot.teamRemoteWriter && <RemoteTaskPreview taskId={taskId} enabled={enabled && !pending && snapshot.fresh === true} />}
    </>}
  </section>;
}

export function RemoteManagedSessionsWorkspace() {
  const [data, setData] = useState<Data | null>(null), [pending, setPending] = useState(false), [error, setError] = useState('');
  const alive = useRef(true), generation = useRef(0), busy = useRef(false), live = useRef<Data | null>(null), identity = useRef(windowIdentity());
  const read = async () => {
    if (!alive.current || busy.current || document.visibilityState !== 'visible' || !supported('remoteSessions.read')) return;
    const epoch = ++generation.current, owner = windowIdentity(); busy.current = true; live.current = null; setPending(true); setError('');
    try { const value = await bridge.command('remoteSessions.read'); if (!alive.current || generation.current !== epoch || windowIdentity() !== owner || document.visibilityState !== 'visible') return;
      if (typeof value?.listingHandle !== 'string' || value.dataOnly !== true || value.authorizesExecution !== false) throw new Error('原会话清单未返回可核对身份。'); live.current = value; setData(value);
    } catch (failure) { if (alive.current && generation.current === epoch) setError(message(failure)); }
    finally { busy.current = false; if (alive.current) setPending(false); }
  };
  useEffect(() => {
    alive.current = true; void read();
    const retire = () => { generation.current++; live.current = null; if (alive.current) setData(null); };
    const inspect = () => { const next = windowIdentity(); if (next !== identity.current || document.visibilityState !== 'visible') { identity.current = next; retire(); } };
    const unsubscribe = bridge.subscribe(inspect); document.addEventListener('visibilitychange', inspect); window.addEventListener('pagehide', retire);
    return () => { alive.current = false; generation.current++; live.current = null; unsubscribe(); document.removeEventListener('visibilitychange', inspect); window.removeEventListener('pagehide', retire); };
  }, []);
  if (!supported('remoteSessions.read')) return null;
  const listing = data?.listingHandle, sessionRows = data ? managedRemoteRows(data) : [];
  const sessionGroups = Array.from(new Set(sessionRows.map(row => JSON.stringify([row.connectionLabel, row.environmentLabel]))));
  return <section className="pw-remote-sessions pw-depth-collaboration" aria-label="本机电脑会话清单"><div className="pw-section-heading"><h3>本机电脑会话</h3><Button variant="ghost" disabled={pending} onClick={read}>{pending ? '正在读取…' : '刷新本机原任务清单'}</Button></div>
    <p className="pw-muted">仅最后保存的本机原任务元数据，不探测外部 CLI。打开只导航到原任务，不新增执行或授权。</p>
    {error && <p className="pw-field-error" role="alert">{error}</p>}
    {data && <><p className="pw-field-hint">外部已认领会话：不可用；外部会话发现：不可用。{data.olderLocalTasksNotScanned ? '更早的本机任务未在本次窗口扫描。' : ''}</p>
      <div className="pw-remote-session-list">{sessionGroups.map(group => { const [connection, environment] = JSON.parse(group); return <section className="pw-remote-session-group" key={group}><h4>{connection} · {environment}</h4>{sessionRows.filter(row => JSON.stringify([row.connectionLabel, row.environmentLabel]) === group).map(row => <Button variant="ghost" key={`${row.taskId}:${row.rowIdentity}`} className="pw-remote-session" disabled={pending || !row.rowIdentity || !supported('remoteSessions.open')} onClick={async () => {
        if (!alive.current || busy.current || document.visibilityState !== 'visible' || !live.current || live.current.listingHandle !== listing || !managedRemoteRows(live.current).some(actual => actual.taskId === row.taskId && actual.rowIdentity === row.rowIdentity)) return;
        const epoch = generation.current; busy.current = true; setPending(true); setError('');
        try { await bridge.command('remoteSessions.open', { listingHandle: listing, taskId: row.taskId, rowIdentity: row.rowIdentity }); }
        catch (failure) { if (alive.current && epoch === generation.current) setError(message(failure)); }
        finally { busy.current = false; if (alive.current) setPending(false); }
      }}><span className="pw-remote-session-copy"><strong>{row.title || row.goal || row.taskId}</strong><small>{workerLabel(row.worker || row.protocol || '原执行器')} · {row.sessionEvidence === 'native_protocol_observed' ? '原协议会话已观察' : '原会话身份待核对'}</small></span><span className="pw-remote-session-action">打开原任务</span></Button>)}</section>; })}</div>
      {!managedRemoteRows(data).length && <p className="pw-muted">本次窗口没有可核对的原电脑会话。</p>}
      {rows(data.unconfirmedLocalTasks).length > 0 && <p className="pw-field-hint">另有 {rows(data.unconfirmedLocalTasks).length} 项本机任务身份不完整，仅保留记录，不能猜测会话。</p>}
    </>}
  </section>;
}

