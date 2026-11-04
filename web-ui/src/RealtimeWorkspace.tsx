import { useEffect, useRef, useState } from 'react';
import { Accordion, AccordionItem, Button, CheckboxField, SelectField, SettingsDialog, TextField } from './forms-library';
import { ProgressBar } from './library';
import { mergeRealtimeSnapshot, realtimeReviewCurrent, realtimeStage } from './realtime-contract';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
const object = (value: any): Data => value && typeof value === 'object' && !Array.isArray(value) ? value : {};
const rows = (value: any): Data[] => Array.isArray(value) ? value.filter(row => row && typeof row === 'object') : [];
const operation = (command: string) => ({ configureNaturalVoice: '保存自然语音服务', configureEndpointProvider: '保存通话服务', configure: '保存房间设置', clearNaturalVoice: '移除自然语音配置', clearEndpointProvider: '移除通话服务配置', acceptInvitation: '接受并连接本次通话', mute: '开启麦克风发送', setCamera: '开启摄像头发送', startVoiceTurn: '开始一轮听写与朗读', commitTurn: '只保存这个任务目标', commitVoiceTask: '保存目标并开始原任务', startVoiceTask: '开始处理原任务', cancelVoiceTask: '停止原任务当前执行', readVoiceTask: '朗读原任务记录' } as Data)[command] || '确认本次操作';
const taskStatus = (value: string) => ({ awaiting_user: '等待你继续', queued: '等待执行', preparing: '正在准备', running: '正在处理', paused: '已暂停', needs_confirmation: '等待原任务确认', stopping: '正在停止，结果待核对', completed: '原任务记录为完成，等待你验收', failed: '未完成，没有自动重跑', interrupted: '已中断，没有自动重跑', cancelled: '已记录取消', reconciling: '正在核对原结果' } as Data)[value] || value || '请读取原任务状态';

/** Adopted BoardUI workspace; all capture, connection and task authority remains native. */
export function RealtimeWorkspace({ state, run, registerTransientBack }: { state: Data; run: Run; registerTransientBack: (handler: (() => boolean) | null) => void }) {
  const [snapshot, setSnapshot] = useState<Data>({}), [review, setReview] = useState<Data | null>(null), [settings, setSettings] = useState<Data | null>(null);
  const [busy, setBusy] = useState(false), [controlBusy, setControlBusy] = useState<string[]>([]), [ack, setAck] = useState(false);
  const [microphone, setMicrophone] = useState(false), [camera, setCamera] = useState(false), [expanded, setExpanded] = useState(false);
  const [error, setError] = useState(''), [notice, setNotice] = useState(''), [uncertain, setUncertain] = useState(false), [readAfterUnknown, setReadAfterUnknown] = useState(false);
  const alive = useRef(true), generation = useRef(0), pending = useRef(false), controls = useRef(new Set<string>()), reading = useRef(false);
  const backgrounded = useRef(document.visibilityState !== 'visible'), reviewRef = useRef<Data | null>(null), resumed = useRef(state.webWorkspaceResumeRevision);
  const pendingMutations = useRef(new Set<symbol>());
  reviewRef.current = review;
  const update = (value: Data) => { const incoming = Number.isSafeInteger(value.snapshotRevision) ? value : object(value.snapshot); setSnapshot(previous => mergeRealtimeSnapshot(previous, incoming)); };
  const closeReview = () => { const original = reviewRef.current; reviewRef.current = null; setReview(null); setAck(false); if (original?.reviewHandle) void run('realtime.closeReview', { reviewHandle: original.reviewHandle }).catch(() => {}); };
  const invoke = async (action: string, args: Data = {}, accept?: (value: Data) => void, lane: 'work' | 'control' | 'read' = 'work', mutation = false) => {
    if (backgrounded.current || state.webWorkspaceTaskOpen) return;
    const key = args.command || action;
    if (lane === 'work' ? pending.current : lane === 'read' ? reading.current : controls.current.has(key)) return;
    if (lane === 'work') { pending.current = true; setBusy(true); }
    else if (lane === 'read') reading.current = true;
    else { controls.current.add(key); setControlBusy([...controls.current]); }
    const ticket = generation.current, mutationId = Symbol(action); if (mutation) pendingMutations.current.add(mutationId); setError('');
    try { const value = object(await run(action, args)); if (alive.current && generation.current === ticket) { update(value); accept?.(value); } }
    catch (failure) { if (alive.current && generation.current === ticket) { setError(failure instanceof Error ? failure.message : String(failure)); if (mutation) { setUncertain(true); setReadAfterUnknown(false); } } }
    finally { pendingMutations.current.delete(mutationId); if (generation.current === ticket) { if (lane === 'work') { pending.current = false; if (alive.current) setBusy(false); } else if (lane === 'read') reading.current = false; else { controls.current.delete(key); if (alive.current) setControlBusy([...controls.current]); } } }
  };
  const refresh = () => { void invoke('realtime.snapshot', {}, () => setReadAfterUnknown(true), 'read'); };
  const receiveReview = (value: Data) => { if (!value.reviewHandle || !value.command || !value.details || !Number.isFinite(value.expiresAt)) throw new Error('未取得完整的原通话审阅，请重新读取状态。'); closeReview(); setReview(value); setAck(false); };
  const reviewAction = (command: string, input: Data = {}) => { if (!uncertain) void invoke('realtime.reviewAction', { command, ...input }, receiveReview); };
  const control = (command: string, input: Data = {}) => { closeReview(); void invoke('realtime.control', { command, ...input }, value => setNotice(object(value.result).message || '已收到原操作回执，请核对当前状态。'), 'control', true); };
  const invalidate = () => { if (pendingMutations.current.size) { setUncertain(true); setReadAfterUnknown(false); setNotice('页面离开时原操作仍在等待回执，请核对实际会话或原任务，没有重新提交。'); } generation.current++; pending.current = false; reading.current = false; controls.current.clear(); setBusy(false); setControlBusy([]); closeReview(); setMicrophone(false); setCamera(false); setSettings(value => value ? { ...value, authenticationToken: '', participantToken: '' } : null); };
  useEffect(() => { alive.current = true; refresh(); return () => { alive.current = false; generation.current++; const original = reviewRef.current; if (original?.reviewHandle) void run('realtime.closeReview', { reviewHandle: original.reviewHandle }).catch(() => {}); }; }, []);
  useEffect(() => { update(object(object(state.features).realtime)); }, [state.features]);
  useEffect(() => {
    const hide = () => { backgrounded.current = true; invalidate(); };
    const visibility = () => { if (document.visibilityState !== 'visible') hide(); else if (backgrounded.current) { backgrounded.current = false; refresh(); } };
    document.addEventListener('visibilitychange', visibility); window.addEventListener('pagehide', hide);
    return () => { document.removeEventListener('visibilitychange', visibility); window.removeEventListener('pagehide', hide); };
  });
  useEffect(() => { if (resumed.current !== state.webWorkspaceResumeRevision) { resumed.current = state.webWorkspaceResumeRevision; invalidate(); if (!backgrounded.current) refresh(); } }, [state.webWorkspaceResumeRevision]);
  useEffect(() => { if (state.webWorkspaceTaskOpen) invalidate(); }, [state.webWorkspaceTaskOpen]);
  useEffect(() => { setMicrophone(false); setCamera(false); }, [object(snapshot.invitation).id]);
  useEffect(() => { registerTransientBack(() => { if (review) { closeReview(); return true; } if (settings) { setSettings(null); return true; } return busy; }); return () => registerTransientBack(null); }, [review, settings, busy, registerTransientBack]);

  const session = object(snapshot.session), invitation = object(snapshot.invitation), voice = object(snapshot.voiceTurn), transcript = object(snapshot.transcription), taskView = object(snapshot.voiceTask), task = object(taskView.task), readout = object(taskView.readout);
  const capabilities = object(snapshot.capabilities), config = object(snapshot.configuration), naturalConfig = object(snapshot.naturalVoiceConfiguration), providerConfig = object(snapshot.providerConfiguration);
  const waiting = snapshot.invitationState === 'awaiting_owner', active = snapshot.accepting === true || ['connecting', 'connected', 'reconnecting', 'disconnecting'].includes(session.state);
  const natural = (waiting || snapshot.accepting) ? invitation.source === 'owner_prepared_natural_voice' : session.transport === 'openai_realtime_ga_websocket';
  const loaded = Number.isSafeInteger(snapshot.snapshotRevision), ownerReady = snapshot.foregroundOwner === true && snapshot.captureCoordinatorReady === true;
  const occupied = active || waiting || voice.active === true || snapshot.audioLeaseHeld === true;
  const locked = busy || uncertain || state.webWorkspaceTaskOpen === true;
  const hostBusy: string[] = Array.isArray(state.moduleBusy) ? state.moduleBusy : [];
  const controlPending = (command: string) => controlBusy.includes(command) || hostBusy.includes(`realtime.${command}`);
  const currentReview = realtimeReviewCurrent(snapshot, review), details = object(review?.details);
  const turns = rows(transcript.turns), visibleTurns = expanded ? turns : turns.slice(-4), taskId = task.taskId || taskView.lastTaskId;
  const binding = { bindingId: taskView.bindingId, viewToken: taskView.viewToken };
  const openTask = (id: string) => { closeReview(); void run('tasks.open', { id }).catch(failure => setError(failure.message)); };
  const openSettings = (kind: string) => {
    const old = kind === 'natural' ? naturalConfig : kind === 'provider' ? providerConfig : config;
    setSettings({ kind, endpointUrl: old.endpointUrl || '', serverUrl: old.serverUrl || '', model: old.model || '', voice: old.voice || '', transcriptionModel: old.transcriptionModel || '', agentName: old.agentName || '', allowLoopbackWs: old.allowLoopbackWs === true, clearToken: false, authenticationToken: '', participantToken: '' });
  };
  const configReview = () => {
    if (!settings) return;
    const { kind } = settings;
    const input = kind === 'natural' ? { kind, endpointUrl: settings.endpointUrl.trim(), model: settings.model.trim(), voice: settings.voice.trim(), transcriptionModel: settings.transcriptionModel.trim(), authenticationToken: settings.authenticationToken }
      : kind === 'provider' ? { kind, endpointUrl: settings.endpointUrl.trim(), serverUrl: settings.serverUrl.trim(), agentName: settings.agentName.trim(), authenticationToken: settings.authenticationToken }
        : { kind, serverUrl: settings.serverUrl.trim(), participantToken: settings.participantToken, allowLoopbackWs: settings.allowLoopbackWs, clearToken: settings.clearToken };
    setSettings(value => value ? { ...value, authenticationToken: '', participantToken: '' } : null);
    void invoke('realtime.reviewConfiguration', input, receiveReview);
  };
  const confirm = () => {
    if (!review || !ack || !currentReview || locked) return;
    const original = review; reviewRef.current = null; setReview(null); setAck(false);
    void invoke('realtime.confirm', { reviewHandle: original.reviewHandle }, value => {
      setNotice(object(value.result).message || '已收到原生回执，当前状态如下。');
      if (original.command.startsWith('configure') || original.command.startsWith('clear')) setSettings(null);
    }, 'work', true);
  };
  const prepare = (kind: string) => { void invoke('realtime.prepare', { kind }, () => { setNotice('已准备本次邀请。核对采集范围并接受后才连接。'); }, 'work', true); };
  const permission = (kind: 'Microphone' | 'Camera') => { void invoke(`realtime.request${kind}Permission`, {}, () => setNotice('已请求系统权限。授权后仍需明确核对本次采集范围。')); };
  return <>
    <section className="pw-settings-section">
      <header className="pw-section-heading"><div><h2>实时通话</h2><p>接受后才连接。切到后台会结束采集和通话；已保存的任务继续保留。</p></div></header>
      <div className="pw-form-actions"><Button variant="secondary" onClick={refresh}>刷新通话状态</Button>{!occupied && <Button variant="ghost" disabled={!loaded || locked || !snapshot.naturalVoiceSupported} onClick={() => openSettings('natural')}>自然语音设置</Button>}</div>
      {busy && <ProgressBar label="等待原通话回执" isIndeterminate showValueLabel={false} size="sm" />}
      {error && <p className="pw-field-error" role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}
      {uncertain && <><p role="status">上次操作结果待核对。请读取当前状态与原记录；页面不会重放连接、采集或任务提交。</p><Button variant="secondary" disabled={!readAfterUnknown || busy} onClick={() => { setUncertain(false); setReadAfterUnknown(false); }}>我已核对原记录，允许重新准备</Button></>}
      <p aria-live="polite">{loaded ? realtimeStage(session.state) : '尚未读取通话状态'}</p>
      {!active && !waiting && <>
        {snapshot.naturalVoiceConfigured ? <><p>{naturalConfig.model} · {naturalConfig.voice}</p><Button disabled={locked || occupied || !ownerReady} onClick={() => prepare('natural')}>准备自然语音对话</Button></> : <p>{loaded && !snapshot.naturalVoiceSupported ? '自然语音适配器尚未接入，当前不能连接。' : '先填写实际语音服务、模型和凭据，再准备通话。'}</p>}
      </>}
      {snapshot.invitationMessage && <p className="pw-muted">{snapshot.invitationMessage}</p>}
      {waiting && !active && <div className="pw-selected-object">
        <h3>{invitation.displayName || '当前通话邀请'}</h3>
        <p>{invitation.source === 'provider_invitation' ? `来自已配置服务 ${invitation.providerId || ''} 的邀请。` : '这是你准备发起的通话。'}</p>
        <p className="pw-reading-copy">{invitation.serverUrl}</p>
        {invitation.tokenEndpoint && <p className="pw-reading-copy">接受后向 {invitation.tokenEndpoint} 请求参与者凭据；agent：{invitation.agentName}</p>}
        {natural && <p>语音模型：{invitation.model} · 声音：{invitation.voice}。开启麦克风后原始声音发送到该服务。</p>}
        <CheckboxField isSelected={microphone} onChange={setMicrophone} isDisabled={locked}>发送本机麦克风声音</CheckboxField>
        {!natural && <CheckboxField isSelected={camera} onChange={setCamera} isDisabled={locked}>打开并发送本机摄像头</CheckboxField>}
        <p className="pw-muted">采集默认关闭，也可只接收对端音频。语音服务可能计费；通话不会自动执行任务或敏感操作。</p>
        <div className="pw-form-actions">{!snapshot.microphonePermission && <Button variant="secondary" disabled={busy} onClick={() => permission('Microphone')}>授予麦克风权限</Button>}{!natural && !snapshot.cameraPermission && <Button variant="secondary" disabled={busy} onClick={() => permission('Camera')}>授予摄像头权限</Button>}</div>
        <div className="pw-form-actions"><Button disabled={locked || !ownerReady || microphone && !snapshot.microphonePermission || camera && !snapshot.cameraPermission} onClick={() => void invoke('realtime.reviewInvitation', { invitationId: invitation.id, microphone, camera }, receiveReview)}>核对并接受邀请</Button><Button variant="secondary" disabled={controlPending('rejectInvitation')} onClick={() => control('rejectInvitation', { invitationId: invitation.id })}>拒绝本次邀请</Button></div>
      </div>}
      {active && <div className="pw-selected-object">
        <h3>{natural ? '当前自然语音' : '当前房间通话'}</h3>
        <p>麦克风：{session.microphoneEnabled ? '正在发送' : '关闭'}{!natural && ` · 摄像头：${session.cameraEnabled ? '正在发送' : '关闭'}`}</p>
        {session.message && <p>{session.message}</p>}
        {session.state === 'connected' && <div className="pw-form-actions">
          {session.microphoneEnabled ? <Button variant="secondary" disabled={controlPending('mute')} onClick={() => control('mute', { sessionId: snapshot.sessionId })}>关闭麦克风发送</Button> : <Button variant="secondary" disabled={locked || controlPending('mute') || !snapshot.microphonePermission} onClick={() => reviewAction('enableMicrophone', { sessionId: snapshot.sessionId })}>核对开启麦克风</Button>}
          {!natural && (session.cameraEnabled ? <Button variant="secondary" disabled={controlPending('setCamera')} onClick={() => control('setCamera', { sessionId: snapshot.sessionId })}>关闭摄像头</Button> : <Button variant="secondary" disabled={locked || controlPending('setCamera') || !snapshot.cameraPermission} onClick={() => reviewAction('enableCamera', { sessionId: snapshot.sessionId })}>核对开启摄像头</Button>)}
          {natural && <Button variant="secondary" disabled={controlPending('interruptOutput')} onClick={() => control('interruptOutput', { sessionId: snapshot.sessionId })}>打断当前说话</Button>}
          {!snapshot.microphonePermission && <Button variant="ghost" disabled={busy} onClick={() => permission('Microphone')}>麦克风权限</Button>}{!natural && !snapshot.cameraPermission && <Button variant="ghost" disabled={busy} onClick={() => permission('Camera')}>摄像头权限</Button>}
        </div>}
        <Button variant="secondary" disabled={!snapshot.sessionId || controlPending('disconnect') || session.state === 'disconnecting'} onClick={() => control('disconnect', { sessionId: snapshot.sessionId })}>{snapshot.accepting || session.state === 'connecting' ? '取消当前连接' : '挂断当前通话'}</Button>
        {!natural && <><p>房间远端参与者：{session.remoteParticipants ?? '尚未读取'}。已连接房间不代表 AI 对端已就绪。</p><p className="pw-muted">当前 Web 页尚未接通真实视频显示。摄像头开启时仍会发送实际画面；可随时关闭摄像头或挂断。</p>{rows(session.remoteVideos).map(video => <p key={video.trackSid}>{video.displayName || '远端参与者'} · {video.muted ? '对端已暂停视频' : '已收到视频轨道，当前页面不能显示'}</p>)}</>}
      </div>}
      {session.resourceReleaseUnknown && <p className="pw-field-error" role="alert">媒体资源释放未确认。请核对系统采集指示，重启应用后再开始通话。</p>}
      {loaded && !ownerReady && <p className="pw-muted">当前前台主人或采集协调器尚未就绪，不能开始采集。</p>}
    </section>

    {(transcript.active || transcript.retained || turns.length > 0) && <section className="pw-settings-section">
      <header className="pw-section-heading"><div><h2>{transcript.retained ? '本次保留的转录' : '本次通话字幕'}</h2><p>逐字复核最终文字后才能保存目标。转录来源标记不能证明说话人身份。</p></div></header>
      {!turns.length && <p className="pw-secondary-empty">尚未收到最终输入转录。自然语音服务需要配置其实际支持的输入转录模型。</p>}
      {turns.length > 4 && <Button variant="ghost" onClick={() => setExpanded(!expanded)}>{expanded ? '只看最近字幕' : `查看全部 ${turns.length} 段`}</Button>}
      {visibleTurns.map(turn => <article className="pw-source-record" key={turn.id}>
        <header><h3>{turn.sourceKind === 'reported_local_microphone' ? '本机麦克风转录（服务标记）' : '远端或来源未核验'}</h3></header><p className="pw-reading-copy">{turn.text || (turn.phase === 'incomplete' ? '这一段未完整接收' : '正在接收…')}</p>
        <p className="pw-muted">{turn.commitState === 'unknown' ? '保存结果待核对' : turn.commitState === 'committed' ? '已保存到原任务' : turn.phase === 'incomplete' ? '未完整接收，不能提交' : turn.isFinal ? '最终转录，仍需明确复核' : '临时转录，不会执行'}</p>
        <div className="pw-form-actions">{turn.canReview && <><Button variant="secondary" disabled={locked} onClick={() => void invoke('realtime.reviewTurn', { sessionId: snapshot.sessionId, turnId: turn.id, revision: turn.revision, startTask: false }, receiveReview)}>复核并只保存目标</Button>{capabilities.voiceTaskHost && <Button variant="secondary" disabled={locked} onClick={() => void invoke('realtime.reviewTurn', { sessionId: snapshot.sessionId, turnId: turn.id, revision: turn.revision, startTask: true }, receiveReview)}>复核后交给原任务</Button>}</>}{turn.commitState === 'unknown' && <Button variant="secondary" disabled={busy} onClick={() => void invoke('realtime.reconcileTurn', { commitId: turn.commitId }, () => setNotice('已核对原提交，没有重新创建。'))}>核对这次原提交</Button>}{turn.taskId && <Button variant="ghost" onClick={() => openTask(turn.taskId)}>打开原任务</Button>}</div>
        {turn.postCommitUpdate && <p>字幕后来发生修订，已确认的原任务没有被自动修改。</p>}
      </article>)}
      {transcript.notice && <p role="status">{transcript.notice}</p>}
      <p className="pw-muted">未保存转录只保留到下一次通话或重启；已保存任务留在原任务记录中。</p>
    </section>}

    <section className="pw-settings-section">
      <header className="pw-section-heading"><div><h2>语音目标与原任务</h2><p>挂断和停止说话不取消任务；权限与敏感操作仍在原任务中确认。</p></div></header>
      {taskView.available ? <>
        {taskId && <div className="pw-selected-object"><h3>{taskView.goal || '这次语音的原任务'}</h3><p>{taskStatus(task.status)}</p>{taskView.message && <p>{taskView.message}</p>}{task.summary && <p className="pw-reading-copy">{task.summary}</p>}{taskView.needsRefresh && <p role="status">这是上次读取的记录，请核对当前原任务。</p>}
          <div className="pw-form-actions"><Button variant="secondary" onClick={() => openTask(taskId)}>查看原任务与产物</Button>{taskView.bindingId && <Button variant="ghost" disabled={busy || taskView.busy} onClick={() => void invoke('realtime.inspectVoiceTask', binding)}>核对原任务状态</Button>}{taskView.canStart && <Button disabled={locked} onClick={() => reviewAction('startVoiceTask', binding)}>核对并开始原任务</Button>}{taskView.canCancel && <Button variant="secondary" disabled={locked} onClick={() => reviewAction('cancelVoiceTask', { ...binding, viewToken: taskView.cancelToken })}>核对取消当前执行</Button>}</div>
          {readout.active ? <Button variant="secondary" disabled={controlPending('interruptOutput') || readout.state === 'stopping'} onClick={() => control('interruptOutput', { readoutId: readout.readoutId })}>停止朗读，保留任务</Button> : task.summary && <Button variant="ghost" disabled={locked || occupied || taskView.busy || taskView.needsRefresh || readout.resourceReleaseUnknown} onClick={() => reviewAction('readVoiceTask', binding)}>核对朗读原任务记录</Button>}
          {readout.message && <p className="pw-muted">{readout.message}</p>}
        </div>}
        <Accordion><AccordionItem title="找回已保存的语音任务"><Button variant="secondary" disabled={busy} onClick={() => void invoke('realtime.listVoiceTasks')}>读取已保存的任务</Button>{taskView.recoveryMessage && <p>{taskView.recoveryMessage}</p>}{rows(taskView.recentTasks).map(saved => <article className="pw-source-record" key={saved.taskId}><h3>{saved.goal || '已保存语音目标'}</h3><p>{taskStatus(saved.status)}</p><Button variant="secondary" disabled={busy} onClick={() => void invoke('realtime.recoverVoiceTask', { taskId: saved.taskId })}>恢复这项原任务视图</Button></article>)}</AccordionItem></Accordion>
      </> : <p className="pw-secondary-empty">{loaded ? '语音任务宿主尚未接入，不能从此页创建或控制任务。' : '等待读取语音任务能力。'}</p>}
    </section>

    <section className="pw-settings-section"><Accordion>
      <AccordionItem title="单轮听写与朗读">
        <p>系统听写转成文字后交给当前文本模型，再由系统朗读。此操作不会自动创建或执行任务。</p>
        {voice.message && <p>{voice.message}</p>}{voice.status && <p>{realtimeStage(voice.status)}</p>}{voice.recognizedText && <p className="pw-reading-copy">{voice.recognizedText}</p>}{voice.replyText && <p className="pw-reading-copy">{voice.replyText}</p>}
        {voice.active ? <Button variant="secondary" disabled={controlPending('stopVoiceTurn') || !voice.id} onClick={() => control('stopVoiceTurn', { voiceTurnId: voice.id })}>停止本轮听写与朗读</Button> : <Button variant="secondary" disabled={locked || occupied || !ownerReady || !voice.portReady || voice.resourceReleaseUnknown || !snapshot.microphonePermission} onClick={() => reviewAction('startVoiceTurn')}>核对并开始一轮语音</Button>}
        {!snapshot.microphonePermission && <Button variant="ghost" disabled={busy} onClick={() => permission('Microphone')}>授予麦克风权限</Button>}
        {voice.inferenceReference?.taskId && <Button variant="ghost" onClick={() => openTask(voice.inferenceReference.taskId)}>查看这次模型调用</Button>}
        {voice.resourceReleaseUnknown && <p role="alert">本轮音频释放未确认，不能开始新一轮。</p>}
      </AccordionItem>
      <AccordionItem title="房间通话与高级连接">
        <p>使用已经部署的通话服务或短期房间参与者令牌。加入房间不等于 AI 服务已经就绪。</p>
        <div className="pw-form-actions"><Button variant="secondary" disabled={!loaded || locked || occupied || !capabilities.endpointProviderSupported} onClick={() => openSettings('provider')}>通话服务设置</Button><Button variant="ghost" disabled={!loaded || locked || occupied} onClick={() => openSettings('room')}>手动房间设置</Button></div>
        <div className="pw-form-actions">{snapshot.providerConfigured && <Button variant="secondary" disabled={locked || occupied || !ownerReady} onClick={() => prepare('provider')}>准备连接通话服务</Button>}{snapshot.configured && <Button variant="secondary" disabled={locked || occupied || !ownerReady} onClick={() => prepare('room')}>准备加入手动房间</Button>}</div>
        {snapshot.providerError && <p role="alert">{snapshot.providerError}</p>}
        {loaded && !capabilities.endpointProviderSupported && <p>通话服务适配器尚未接入。</p>}
        <p className="pw-muted">当前 Web 视频承载尚未接通；已连接房间的音频、采集状态与停止控制来自真实会话。</p>
      </AccordionItem>
    </Accordion></section>

    <SettingsDialog isOpen={!!settings && !review} title="通话连接设置" onClose={() => { if (!busy) setSettings(null); }}>{settings && <div className="pw-form-grid">
      <SelectField label="连接类型" value={settings.kind} isDisabled={busy} options={[{ id: 'natural', label: '自然语音服务' }, { id: 'provider', label: '通话服务' }, { id: 'room', label: '手动房间' }]} onChange={openSettings} />
      {settings.kind !== 'room' && <TextField label={settings.kind === 'natural' ? '实时语音 WSS 端点' : '凭据服务 HTTPS 端点'} value={settings.endpointUrl} isDisabled={busy} onChange={endpointUrl => setSettings(old => ({ ...old, endpointUrl }))} />}
      {settings.kind === 'natural' ? <><TextField label="服务实际支持的语音模型" value={settings.model} isDisabled={busy} onChange={model => setSettings(old => ({ ...old, model }))} /><TextField label="声音标识" value={settings.voice} isDisabled={busy} onChange={voice => setSettings(old => ({ ...old, voice }))} /><TextField label="输入转录模型（可选）" value={settings.transcriptionModel} isDisabled={busy} onChange={transcriptionModel => setSettings(old => ({ ...old, transcriptionModel }))} hint="需要把话语复核为任务时，填写该服务实际支持的输入转录模型。" /></> : <TextField label="LiveKit 房间 WSS 地址" value={settings.serverUrl} isDisabled={busy} onChange={serverUrl => setSettings(old => ({ ...old, serverUrl }))} />}
      {settings.kind === 'provider' && <TextField label="真实部署的 agent 名称" value={settings.agentName} isDisabled={busy} onChange={agentName => setSettings(old => ({ ...old, agentName }))} />}
      <TextField label={settings.kind === 'room' ? '短期参与者令牌' : '服务认证凭据'} type="password" autoComplete="new-password" value={settings.kind === 'room' ? settings.participantToken : settings.authenticationToken} isDisabled={busy} onChange={value => setSettings(old => ({ ...old, [old?.kind === 'room' ? 'participantToken' : 'authenticationToken']: value }))} hint="同一服务可留空保留已保存凭据；更换服务须输入新凭据。审阅后和进入后台会清除输入框。" />
      {settings.kind === 'room' && <><CheckboxField isSelected={settings.allowLoopbackWs} onChange={allowLoopbackWs => setSettings(old => ({ ...old, allowLoopbackWs }))}>允许仅本机回环使用 WS</CheckboxField><CheckboxField isSelected={settings.clearToken} onChange={clearToken => setSettings(old => ({ ...old, clearToken }))}>清除已保存的房间令牌</CheckboxField></>}
      {uncertain && <><p role="status">原保存结果待核对，草稿已保留。请读取实际配置后再决定是否重新准备。</p>{readAfterUnknown && <p className="pw-reading-copy">当前保存的服务：{(settings.kind === 'natural' ? naturalConfig.endpointUrl : settings.kind === 'provider' ? providerConfig.endpointUrl : config.serverUrl) || '尚未配置'}</p>}<div className="pw-form-actions"><Button variant="secondary" onClick={refresh}>读取原通话状态</Button><Button variant="secondary" disabled={!readAfterUnknown || busy} onClick={() => { setUncertain(false); setReadAfterUnknown(false); }}>我已核对，允许重新准备</Button></div></>}
      {error && <p role="alert">{error}</p>}<div className="pw-form-actions"><Button disabled={locked || occupied} onClick={configReview}>核对并保存设置</Button>{settings.kind !== 'room' && (settings.kind === 'natural' ? snapshot.naturalVoiceConfigured : snapshot.providerConfigured) && <Button variant="ghost" disabled={locked || occupied} onClick={() => reviewAction(settings.kind === 'natural' ? 'clearNaturalVoice' : 'clearEndpointProvider')}>核对移除此配置</Button>}</div>
    </div>}</SettingsDialog>
    <SettingsDialog isOpen={!!review} title={operation(review?.command || '')} onClose={closeReview}>{review && <>
      {details.text && <p className="pw-reading-copy">{details.text}</p>}{details.goal && <p className="pw-reading-copy">{details.goal}</p>}
      {details.endpointUrl && <p className="pw-reading-copy">服务：{details.endpointUrl}</p>}{details.serverUrl && <p className="pw-reading-copy">连接：{details.serverUrl}</p>}{details.model && <p>模型：{details.model}{details.voice ? ` · 声音：${details.voice}` : ''}</p>}{details.agentName && <p>Agent：{details.agentName}</p>}
      {review.command === 'acceptInvitation' && <><p>麦克风：{details.microphone ? '开启并发送声音' : '关闭'} · 摄像头：{details.camera ? '开启并发送画面' : '关闭'}</p><p>确认后才连接并按这次选择采集。此语音连接不自动执行任务或敏感操作。</p></>}
      {review.command === 'mute' && <p>开启当前会话的麦克风，将本机声音发送给当前服务。</p>}{review.command === 'setCamera' && <p>开启当前会话的摄像头并发送实际画面。当前 Web 页面不能显示视频预览。</p>}
      {review.command === 'startVoiceTurn' && <p>把本轮听写后的文字发给此模型，再用系统朗读。不会创建或启动任务。</p>}
      {review.command === 'commitTurn' && <p>只保存为等待你继续的原任务。请逐字核对以上转录；不准确请返回并改用文字输入。</p>}
      {['commitVoiceTask', 'startVoiceTask'].includes(review.command) && <p>确认后通过原任务开始处理此目标。敏感动作仍需在原任务逐项确认。</p>}
      {review.command === 'cancelVoiceTask' && <p>请求停止当前原执行。已经发生的效果不会撤回；结果未知时继续核对原任务。</p>}
      {review.command === 'readVoiceTask' && <p>通过本机系统朗读原任务当前记录。停止说话不会取消任务。</p>}
      {review.command.startsWith('configure') && <p>只保存这次服务设置，不连接、不采集。认证凭据不会出现在回执中。</p>}
      {review.command === 'configure' && details.clearToken && <p>本次会清除已保存的房间参与者令牌。</p>}
      {review.command.startsWith('clear') && <p>移除已保存的此项服务及凭据，不改变已保存任务。</p>}
      {!currentReview && <p role="alert">原会话、配置、任务或审阅已变化，请返回后重新读取并核对。</p>}
      <CheckboxField isSelected={ack} onChange={setAck} isDisabled={locked || !currentReview}>我已核对本次范围，确认执行上述操作</CheckboxField><div className="pw-form-actions"><Button variant="secondary" onClick={closeReview}>返回</Button><Button disabled={locked || !ack || !currentReview} onClick={confirm}>确认{operation(review.command)}</Button></div>
    </>}</SettingsDialog>
  </>;
}
