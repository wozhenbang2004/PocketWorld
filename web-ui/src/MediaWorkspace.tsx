import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { ArrowLeft, ArrowRight, ChevronRight, File, Image, Plus, RefreshCw, Settings } from 'lucide-react';
import { Accordion, AccordionItem, Button, CheckboxField, SelectField, SettingsDialog, TextAreaField, TextField, formatFileSize } from './forms-library';
import { AIAnswer, ProgressBar } from './library';
import './media-workspace.css';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
type Panel = 'library' | 'create' | 'task';
const rows = (value: any): Data[] => Array.isArray(value) ? value : [];
const object = (value: any): Data => value && typeof value === 'object' && !Array.isArray(value) ? value : {};
const submissions = new Set(['generateImage', 'generateVideo', 'editImage', 'synthesizeSpeech', 'submitWorkflow']);
const taskState = (value: string) => ({ awaiting_user: '等待确认输入', running: '正在处理', preparing: '正在准备', needs_confirmation: '成果待核对', completed: '已完成', failed: '未完成', reconciling: '原效果待核对', interrupted: '已中断', cancelled: '已停止' } as Data)[value] || value || '状态待核对';
const unknownReceipt = (receipt: Data) => !receipt.status || ['unknown', 'prepared', 'submitting', 'not_found', 'host_receipt_unavailable'].includes(receipt.status);
const inputText = (input: Data) => input.prompt || input.text || (input.workflow ? JSON.stringify(input.workflow, null, 2) : '原输入暂不可读取');

/** BoardUI source controls and the retained attachment-row composition, with
 * OpenMuse LinkRow's title/detail/chevron hierarchy (61796eb2, ui.tsx:368).
 * Only presentation changes; the native media owner holds every operation. */
export function MediaWorkspace({ state, run, registerTransientBack }: { state: Data; run: Run; registerTransientBack: (handler: (() => boolean) | null) => void }) {
  const [snapshot, setSnapshot] = useState<Data>({}), [snapshotReady, setSnapshotReady] = useState(false);
  const [reading, setReading] = useState<Data | null>(null), [review, setReview] = useState<Data | null>(null);
  const [settings, setSettings] = useState<Data | null>(null), [settingsOpen, setSettingsOpen] = useState(false);
  const [acknowledged, setAcknowledged] = useState(false), [busy, setBusy] = useState(false), [panel, setPanel] = useState<Panel>('library');
  const [error, setError] = useState(''), [notice, setNotice] = useState(''), [kind, setKind] = useState('image'), [text, setText] = useState(''), [graph, setGraph] = useState('');
  const [imageId, setImageId] = useState(''), [maskId, setMaskId] = useState(''), [observe, setObserve] = useState(true);
  const [preparedTask, setPreparedTask] = useState(''), [submittedTask, setSubmittedTask] = useState(''), [submissionUnknown, setSubmissionUnknown] = useState(false);
  const acknowledgement = useRef(false);
  const alive = useRef(true), pending = useRef(false), generation = useRef(0), selected = useRef<Data | null>(null), reviewRef = useRef<Data | null>(null);
  const resumed = useRef(state.webWorkspaceResumeRevision), backgrounded = useRef(document.visibilityState !== 'visible'), nativeOpened = useRef(!!state.webWorkspaceTaskOpen);
  const root = useRef<HTMLElement>(null), positions = useRef<Record<string, number>>({}), focuses = useRef<Record<string, HTMLElement | null>>({}), previous = useRef('library');
  const latest = useRef({ state, panel, settingsOpen, settings, submittedTask }); latest.current = { state, panel, settingsOpen, settings, submittedTask }; selected.current = reading; reviewRef.current = review;
  const config = object(snapshot.configuration), comfy = config.provider === 'comfyui';
  const presentation = panel === 'task' ? 'task:' + (reading?.taskId || '') : panel;
  const available = () => alive.current && !pending.current && !backgrounded.current && !latest.current.state.webWorkspaceTaskOpen;
  const release = (value: Data | null, key: string) => { if (value?.[key]) void run('media.closeReading', { [key]: value[key] }).catch(() => {}); };
  const releaseReading = (value: Data | null) => { release(value, 'viewHandle'); release(value?.submissionReview, 'reviewHandle'); };
  const remember = () => {
    positions.current[presentation] = root.current?.closest<HTMLElement>('.pw-secondary-scroll')?.scrollTop || 0;
    const focus = document.activeElement as HTMLElement | null;
    if (focus && root.current?.contains(focus)) focuses.current[presentation] = focus;
  };
  const move = (next: Panel) => { remember(); setPanel(next); };
  const clearView = () => {
    const original = selected.current; releaseReading(original);
    const retained = original ? { ...original, viewHandle: '', retainedInput: original.submissionReview?.input || original.retainedInput, submissionReview: undefined } : null;
    selected.current = retained; setReading(retained);
  };
  const closeReview = (returnToSettings = true) => {
    const original = reviewRef.current; reviewRef.current = null; release(original, 'reviewHandle');
    // A prepared review is one-use even when it originated inside a reading.
    const currentReading = selected.current;
    if (original?.reviewHandle && currentReading && currentReading.submissionReview?.reviewHandle === original.reviewHandle) {
      const next = { ...currentReading, retainedInput: currentReading.submissionReview?.input, submissionReview: undefined }; selected.current = next; setReading(next);
    }
    setReview(null); acknowledgement.current = false; setAcknowledged(false);
    if (returnToSettings && original?.command === 'configure') setSettingsOpen(true);
  };
  const receiveReview = (value: Data) => {
    if (!value.reviewHandle || !value.command || !value.input || (submissions.has(value.command) && !value.input.taskId)) {
      release(value, 'reviewHandle'); throw new Error('未取得可核对的原媒体操作审阅。');
    }
    closeReview(false); reviewRef.current = value; setReview(value); acknowledgement.current = false; setAcknowledged(false);
  };
  const acceptReading = (value: Data, taskId: string) => {
    if (!value.viewHandle || value.taskId !== taskId || value.task?.id !== taskId) {
      releaseReading(value); throw new Error('原媒体任务回执身份不匹配。');
    }
    releaseReading(selected.current); selected.current = value; setReading(value);
    if (taskId === latest.current.submittedTask && (value.submissionReview || !unknownReceipt(object(value.receipt)))) setSubmissionUnknown(false);
  };
  const invoke = async (action: string, args: Data = {}, accept?: (value: Data) => void | Promise<void>) => {
    if (!available()) return;
    pending.current = true; const ticket = ++generation.current; setBusy(true); setError('');
    try {
      const value = object(await run(action, args));
      if (alive.current && generation.current === ticket && !backgrounded.current && !latest.current.state.webWorkspaceTaskOpen) await accept?.(value);
      else { releaseReading(value); release(value, 'reviewHandle'); }
    } catch (failure) { if (alive.current && generation.current === ticket) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { if (generation.current === ticket) { pending.current = false; if (alive.current) setBusy(false); } }
  };
  const acceptSnapshot = (value: Data) => { setSnapshot(value); setSnapshotReady(true); };
  const refresh = () => { void invoke('media.snapshot', {}, acceptSnapshot); };
  const readTask = (taskId: string) => {
    if (!taskId || !available() || reviewRef.current || latest.current.settingsOpen) return;
    remember();
    void invoke('media.readTask', { taskId }, value => { acceptReading(value, taskId); setPanel('task'); });
  };
  const refreshReading = async (taskId: string) => {
    const ticket = generation.current, value = object(await run('media.readTask', { taskId }));
    if (alive.current && ticket === generation.current && !backgrounded.current && !latest.current.state.webWorkspaceTaskOpen) { acceptReading(value, taskId); setPanel('task'); }
    else releaseReading(value);
  };
  const closeSettings = () => { if (!available()) return; setSettings(value => value ? { ...value, apiKey: '' } : null); setSettingsOpen(false); };
  const goBack = () => {
    if (!available()) return;
    if (reviewRef.current) { closeReview(); return; }
    if (settingsOpen) { closeSettings(); return; }
    if (panel !== 'library') { if (panel === 'task') clearView(); move('library'); }
  };
  const invalidate = () => {
    generation.current++; pending.current = false; setBusy(false); setSnapshotReady(false);
    closeReview(false); clearView(); setSettings(value => value ? { ...value, apiKey: '' } : null);
  };
  const restoreWorkspace = () => {
    invalidate();
    const taskId = latest.current.panel === 'task' ? selected.current?.taskId : '';
    void invoke('media.snapshot', {}, async value => { acceptSnapshot(value); if (taskId) await refreshReading(taskId); });
  };
  useEffect(() => {
    alive.current = true; refresh();
    return () => { alive.current = false; generation.current++; releaseReading(selected.current); release(reviewRef.current, 'reviewHandle'); };
  }, []);
  useEffect(() => {
    const changed = resumed.current !== state.webWorkspaceResumeRevision;
    const returned = nativeOpened.current && !state.webWorkspaceTaskOpen;
    resumed.current = state.webWorkspaceResumeRevision;
    if (state.webWorkspaceTaskOpen && !nativeOpened.current) invalidate();
    nativeOpened.current = !!state.webWorkspaceTaskOpen;
    if ((changed || returned) && !backgrounded.current && !state.webWorkspaceTaskOpen) restoreWorkspace();
  }, [state.webWorkspaceResumeRevision, state.webWorkspaceTaskOpen]);
  useEffect(() => {
    const hide = () => { backgrounded.current = true; invalidate(); };
    const visibility = () => {
      if (document.visibilityState !== 'visible') hide();
      else if (backgrounded.current) { backgrounded.current = false; restoreWorkspace(); }
    };
    document.addEventListener('visibilitychange', visibility); window.addEventListener('pagehide', hide);
    return () => { document.removeEventListener('visibilitychange', visibility); window.removeEventListener('pagehide', hide); };
  });
  useEffect(() => {
    registerTransientBack(() => { if (busy || review || settingsOpen || panel !== 'library') { goBack(); return true; } return false; });
    return () => registerTransientBack(null);
  }, [busy, review, settingsOpen, panel, reading, registerTransientBack]);
  useLayoutEffect(() => {
    if (presentation === previous.current || busy) return;
    const scroll = root.current?.closest<HTMLElement>('.pw-secondary-scroll');
    if (scroll) scroll.scrollTop = positions.current[presentation] || 0;
    const focus = focuses.current[presentation];
    if (focus?.isConnected && root.current?.contains(focus) && !focus.closest('[hidden]') && !focus.matches(':disabled')) focus.focus({ preventScroll: true });
    else root.current?.querySelector<HTMLElement>('[data-media-panel]:not([hidden]) [data-media-heading]')?.focus({ preventScroll: true });
    previous.current = presentation;
  }, [presentation, busy]);

  const mediaTasks = rows(state.tasks).filter(task => object(task.raw).feature === 'media' || object(object(task.raw).nativeEventTypeFlags).media_owner_prepared === true || rows(object(task.raw).events).some(event => event.type === 'media_owner_prepared'));
  const images = rows(object(object(state.features).attachments).attachments).filter(file => ['image/png', 'image/jpeg', 'image/webp'].includes(file.mimeType) && file.size > 0 && file.size <= 4 * 1024 * 1024);
  const kindOptions = [{ id: 'image', label: '生成图片' }, ...(comfy ? [{ id: 'workflow', label: '运行 ComfyUI API 图' }, ...(config.hasVideoWorkflow ? [{ id: 'video', label: '生成视频' }] : [])] : [{ id: 'speech', label: '生成语音文件' }, ...(snapshot.imageEditingSupported ? [{ id: 'edit', label: '编辑原图' }] : [])])];
  const kindAvailable = kindOptions.some(option => option.id === kind);
  const inputLimit = Number(object(snapshot.inputLimits)[kind === 'speech' ? 'speechTextChars' : 'imagePromptChars']);
  const draftValid = kindAvailable && (kind === 'workflow' ? graph : text).trim() && !(kind !== 'workflow' && inputLimit > 0 && text.length > inputLimit)
    && !(comfy && kind === 'image' && !config.hasWorkflow) && (kind !== 'edit' || images.some(file => file.id === imageId) && (!maskId || images.some(file => file.id === maskId && file.mimeType === 'image/png')));
  const prepare = () => {
    if (!available() || latest.current.panel !== 'create' || reviewRef.current || latest.current.settingsOpen || !snapshotReady || !snapshot.configured || !draftValid || submissionUnknown) return;
    try {
      const args: Data = { kind, observeUntilComplete: observe, ...(kind === 'workflow' ? { workflow: JSON.parse(graph) } : { text }) };
      if (kind === 'edit') { args.imageId = imageId; if (maskId) args.maskId = maskId; }
      void invoke('media.prepare', args, value => { receiveReview(value); setPreparedTask(value.input.taskId); });
    } catch { setError('请填写有效的 API 工作流 JSON，没有准备或提交请求。'); }
  };
  const openSettings = () => {
    if (!available() || reviewRef.current) return;
    setSettings(old => old ? { ...old, apiKey: '' } : { provider: config.provider || 'openai_compatible', baseUrl: config.baseUrl || '', apiKey: '', imageModel: config.imageModel || '', ttsModel: config.ttsModel || '', voice: config.voice || '', legacyImageBase64: config.legacyImageBase64 === true, allowResultUrls: config.allowResultUrls === true, comfyJobsApi: config.comfyJobsApi === true, workflowText: '', videoWorkflowText: '', promptNodeId: config.promptNodeId || '', promptInput: config.promptInput || 'text', videoPromptNodeId: config.videoPromptNodeId || '', videoPromptInput: config.videoPromptInput || 'text' });
    setSettingsOpen(true);
  };
  const configReview = () => {
    if (!settings || settings !== latest.current.settings || !latest.current.settingsOpen || !available() || !snapshotReady || reviewRef.current) return;
    try {
      const { workflowText, videoWorkflowText, ...input } = settings;
      if (settings.provider === 'comfyui') { if (workflowText.trim()) input.workflow = JSON.parse(workflowText); if (videoWorkflowText.trim()) input.videoWorkflow = JSON.parse(videoWorkflowText); }
      setSettings(old => old ? { ...old, apiKey: '' } : null);
      void invoke('media.reviewConfiguration', input, value => { receiveReview(value); setSettingsOpen(false); });
    } catch { setSettings(old => old ? { ...old, apiKey: '' } : null); setError('模板不是有效 JSON，没有保存服务配置。'); }
  };
  const confirm = () => {
    const original = reviewRef.current;
    if (!original || original.reviewHandle !== review?.reviewHandle || !available() || !acknowledgement.current) return;
    const submitting = submissions.has(original.command);
    if (submitting && original.input.configurationRevision && original.input.configurationRevision !== snapshot.configurationRevision) return;
    reviewRef.current = null; setReview(null); acknowledgement.current = false; setAcknowledged(false);
    const currentReading = selected.current;
    if (currentReading && currentReading.submissionReview?.reviewHandle === original.reviewHandle) {
      const next = { ...currentReading, retainedInput: currentReading.submissionReview?.input, submissionReview: undefined }; selected.current = next; setReading(next);
    }
    if (submitting) { setSubmittedTask(original.input.taskId); latest.current.submittedTask = original.input.taskId; setSubmissionUnknown(true); }
    void invoke('media.confirm', { reviewHandle: original.reviewHandle }, async value => {
      if (value.confirmed !== true || value.command !== original.command) throw new Error('原媒体操作回执尚待核对。');
      if (original.command === 'configure') { acceptSnapshot(object(value.result)); setSettings(null); setSettingsOpen(false); setNotice('媒体服务配置已保存；是否可用仍以实际调用为准。'); return; }
      setNotice(value.result?.message || '已收到原服务回执，请核对原任务与产物。');
      const id = original.input.taskId || selected.current?.taskId;
      if (id) { remember(); await refreshReading(id); }
    });
  };
  const operate = (action: string) => {
    const original = selected.current;
    if (!available() || latest.current.panel !== 'task' || !original?.viewHandle || original.taskId !== reading?.taskId || original.viewHandle !== reading?.viewHandle || reviewRef.current || latest.current.settingsOpen) return;
    void invoke(action, { viewHandle: original.viewHandle }, async value => {
      if (value.reviewHandle) { receiveReview(value); return; }
      if (value.confirmed !== true) throw new Error('原媒体回执尚待核对。');
      setNotice(value.result?.message || '已取得原操作回执。'); await refreshReading(original.taskId);
    });
  };
  const openResult = (file: Data) => {
    const original = selected.current;
    if (!available() || latest.current.panel !== 'task' || !original?.viewHandle || original.taskId !== reading?.taskId || original.viewHandle !== reading?.viewHandle || reviewRef.current || latest.current.settingsOpen || !file.id || !file.sha256 || !rows(object(original.receipt).attachments).some(item => item.id === file.id && item.sha256 === file.sha256)) return;
    void invoke('media.openResult', { viewHandle: original.viewHandle, fileId: file.id, sha256: file.sha256 });
  };
  const change = (key: string, value: any) => setSettings(old => old ? { ...old, [key]: value, ...(key === 'provider' || key === 'baseUrl' ? { apiKey: '' } : {}) } : null);
  const field = (key: string, label: string, secret = false) => <TextField key={key} label={label} type={secret ? 'password' : 'text'} autoComplete={secret ? 'new-password' : undefined} value={settings?.[key] || ''} onChange={value => change(key, value)} isDisabled={busy} />;
  const operation = object(reading?.operation), receipt = object(reading?.receipt), files = rows(receipt.attachments);
  const serviceName = snapshot.configured ? comfy ? 'ComfyUI' : '兼容媒体服务' : snapshotReady ? '尚未配置媒体服务' : '媒体服务待读取';
  const reviewInput = object(review?.input), submissionReview = !!review && submissions.has(review.command);
  const reviewStale = submissionReview && !!reviewInput.configurationRevision && reviewInput.configurationRevision !== snapshot.configurationRevision;
  const openCreate = () => { if (!available() || reviewRef.current || latest.current.settingsOpen) return; if (panel === 'task') clearView(); move('create'); };
  return <section ref={root} className="pw-settings-section pw-media-workspace" data-media-view={panel}>
    <header className="pw-media-heading"><div><h2>媒体工作室</h2><p>从原任务继续，或准备一份新的图片、视频与语音。</p></div>{panel !== 'create' && <Button leadingIcon={Plus} disabled={busy} onClick={openCreate}>{text || graph ? '继续创作' : '开始创作'}</Button>}</header>
    <div className="pw-media-service"><div><span>{serviceName}</span>{snapshot.configured && <small>{config.baseUrl}</small>}</div><Button variant="ghost" size="small" leadingIcon={Settings} disabled={busy} onClick={openSettings}>服务配置</Button></div>
    {busy && <ProgressBar className="pw-media-progress" label="等待原媒体操作回执" isIndeterminate showValueLabel={false} size="sm" />}
    {error && <p className="pw-media-feedback" role="alert">{error}</p>}{notice && <p className="pw-media-feedback" role="status">{notice}</p>}
    {submittedTask && <div className="pw-media-submission" role={submissionUnknown ? 'status' : undefined}><div><strong>{submissionUnknown ? '原提交回执尚待核对' : '本次提交保留在原任务中'}</strong><p>{submissionUnknown ? '先读取同一原任务，不会自动重发，也不代表服务未收到。' : '受理不代表生成完成，产物和实际效果请在原任务核对。'}</p><small>原任务 · {submittedTask}</small></div><Button variant="secondary" disabled={busy} onClick={() => readTask(submittedTask)}>核对刚才的原提交</Button></div>}

    <div data-media-panel="library" hidden={panel !== 'library'}>
      <div className="pw-media-section-heading"><h3 tabIndex={-1} data-media-heading>原媒体任务</h3><Button variant="ghost" size="small" leadingIcon={RefreshCw} disabled={busy} onClick={refresh}>刷新原记录</Button></div>
      {mediaTasks.length ? <div className="pw-media-task-list">{mediaTasks.map(task => <Button key={task.id} variant="ghost" className="pw-media-task-row" leadingIcon={Image} trailingIcon={ChevronRight} disabled={busy} aria-label={'查看原媒体任务：' + (task.goal || task.id)} onClick={() => readTask(task.id)}><span className="pw-media-row-copy"><strong>{task.goal || '未命名媒体任务'}</strong><small>{taskState(task.status)} · {task.id}</small>{task.summary && <span className="pw-media-row-summary">{task.summary}</span>}</span></Button>)}</div> : <div className="pw-media-empty"><Image size={24} aria-hidden /><h3>这里会保留你的媒体任务</h3><p>准备输入后会先保存原任务。只有明确确认，才发送到服务。</p><Button variant="secondary" leadingIcon={ArrowRight} disabled={busy} onClick={openCreate}>准备第一份创作</Button></div>}
      {preparedTask && !mediaTasks.some(task => task.id === preparedTask) && preparedTask !== submittedTask && <Button variant="ghost" className="pw-media-task-row" leadingIcon={File} trailingIcon={ChevronRight} disabled={busy} onClick={() => readTask(preparedTask)}><span className="pw-media-row-copy"><strong>已准备的原输入</strong><small>原任务 · {preparedTask} · 尚待核对提交</small></span></Button>}
    </div>

    <div data-media-panel="create" hidden={panel !== 'create'} className="pw-media-work-surface">
      <Button variant="ghost" className="pw-media-back" leadingIcon={ArrowLeft} disabled={busy} onClick={goBack}>返回媒体任务</Button>
      <div className="pw-media-panel-heading"><h3 tabIndex={-1} data-media-heading>准备一份创作</h3><p>草稿留在这里。下一步先核对原输入与服务，再确认这一次提交。</p></div>
      <div className="pw-media-compose-fields"><SelectField label="创作类型" value={kind} onChange={setKind} options={kindAvailable ? kindOptions : [...kindOptions, { id: kind, label: '原草稿类型（当前服务不可用）', disabled: true }]} isDisabled={busy} />
        {!kindAvailable && <p role="alert">当前服务不支持原草稿类型，请选择可用类型；输入仍保留。</p>}
        {kind === 'edit' && <div className="pw-media-image-inputs"><div className="pw-media-field-pair"><SelectField label="已导入的原图" value={imageId} onChange={setImageId} options={images.map(file => ({ id: file.id, label: `${file.name} · ${formatFileSize(file.size)}` }))} isDisabled={busy} /><SelectField label="透明 PNG mask（可选）" value={maskId} onChange={setMaskId} options={[{ id: '', label: '不使用 mask' }, ...images.filter(file => file.mimeType === 'image/png').map(file => ({ id: file.id, label: file.name }))]} isDisabled={busy} /></div><p className="pw-media-note">确认后上传完整原图与选定 mask；原件保留，新结果单独保存。原图与 mask 需满足原服务的尺寸限制。</p>{!images.length && <p className="pw-media-note">请先在附件库导入 PNG、JPEG 或 WebP 原图，单个不超过 4 MB。</p>}</div>}
        <TextAreaField label={kind === 'workflow' ? 'ComfyUI API 工作流 JSON' : kind === 'speech' ? '要合成为语音的文字' : kind === 'edit' ? '描述对原图的修改' : '描述要生成的内容'} value={kind === 'workflow' ? graph : text} onChange={kind === 'workflow' ? setGraph : setText} isDisabled={busy} rows={8} />
        {kind !== 'workflow' && inputLimit > 0 && <p className="pw-media-note">{text.length.toLocaleString()} / {inputLimit.toLocaleString()} 字符{text.length > inputLimit ? ' · 超出原服务输入上限，请缩短后核对。' : ''}</p>}
        {comfy && kind === 'image' && !config.hasWorkflow && <p role="alert">生图需要先配置图片模板与提示词节点，也可以选择运行完整 API 图。</p>}
        {comfy && <div className="pw-media-observe"><CheckboxField isSelected={observe} onChange={setObserve} isDisabled={busy}>应用存活期间继续查询这项原任务</CheckboxField><p className="pw-media-note">仅查询已确认的原服务任务，最多 60 次／5 分钟，仍受原授权期限限制。</p></div>}
      </div>
      <div className="pw-media-compose-footer"><p className="pw-media-note">准备不会发送生成请求。确认后输入会发往上方服务，可能产生费用。</p><Button disabled={busy || !snapshotReady || !snapshot.configured || !draftValid || submissionUnknown} onClick={prepare}>准备并核对输入</Button></div>
      {!snapshot.configured && <p className="pw-media-note">先打开服务配置，保存并读取真实配置后再准备输入。</p>}
    </div>

    {reading && <div data-media-panel="task" hidden={panel !== 'task'} className="pw-media-work-surface">
      <Button variant="ghost" className="pw-media-back" leadingIcon={ArrowLeft} disabled={busy} onClick={goBack}>返回媒体任务</Button>
      <div className="pw-media-panel-heading"><h3 tabIndex={-1} data-media-heading>{reading.task.goal || '原媒体任务'}</h3><p>{taskState(reading.task.status)}</p><small>原任务 · {reading.taskId}</small>{operation.operationId && <small>原服务操作 · {operation.operationId}</small>}</div>
      {!reading.viewHandle && <p className="pw-media-feedback" role="status">原读取权限需要重新核对。内容只作保留记录，不代表仍可执行。</p>}
      <div className="pw-form-actions pw-media-task-actions"><Button variant="secondary" leadingIcon={RefreshCw} disabled={busy} onClick={() => readTask(reading.taskId)}>重新读取原任务</Button><Button variant="ghost" trailingIcon={ArrowRight} disabled={busy} onClick={() => { if (available() && selected.current?.taskId === reading.taskId) void run('tasks.open', { id: reading.taskId }).catch(failure => setError(failure instanceof Error ? failure.message : String(failure))); }}>打开原任务与审批</Button></div>
      {operation.operationId ? <>
        <section className="pw-media-receipt" aria-label="原服务回执"><h4>{receipt.providerStatus || receipt.status || '原服务状态待核对'}</h4><p>{receipt.message || '尚未取得可说明实际效果的服务回执。'}</p><p className="pw-media-note">受理不代表生成完成。这里只查询和取回原操作，不重新生成。</p>{receipt.providerJobId && <small>服务任务 · {receipt.providerJobId}</small>}<div className="pw-form-actions"><Button variant="secondary" disabled={busy || !reading.viewHandle} onClick={() => operate('media.query')}>查询原操作</Button><Button variant="secondary" disabled={busy || !reading.viewHandle} onClick={() => operate('media.fetchResults')}>取回原产物</Button></div></section>
        {(receipt.ownerResultDownloadReviewAvailable || receipt.resultStatus === 'owner_download_policy_required') && <section className="pw-media-submission"><div><strong>原结果等待一次性下载许可</strong><p>先核对原服务与结果来源，只取回这次原响应。</p></div><Button variant="secondary" disabled={busy || !reading.viewHandle} onClick={() => operate('media.reviewDownload')}>核对一次性结果下载</Button></section>}
      </> : <section className="pw-media-receipt" aria-label="原媒体输入"><h4>{reading.submissionReview ? '原输入已保存，尚未提交到服务' : '原输入与提交状态待读取'}</h4>{!reading.submissionReview && reading.retainedInput && <><p className="pw-media-note">原输入仍保留，请重新读取这项原任务的提交状态。</p><AIAnswer text={inputText(object(reading.retainedInput))} /></>}{reading.submissionReview && <><p className="pw-media-note">下面是这项原任务保存的输入，不会使用创作区的其他草稿。</p><AIAnswer text={inputText(object(reading.submissionReview.input))} /><Button disabled={busy || !reading.viewHandle} onClick={() => { const current = selected.current; if (available() && latest.current.panel === 'task' && current?.viewHandle && current.submissionReview) receiveReview(current.submissionReview); }}>核对原输入并提交</Button></>}</section>}
      <section className="pw-media-results" aria-label="已保存产物"><div className="pw-media-section-heading"><h4>已保存产物</h4>{files.length > 0 && <small>{files.length} 个文件</small>}</div>{files.length ? <div className="pw-media-file-list">{files.map(file => { const opening = object(state.attachmentOpens)[`${file.id}:${file.sha256}`]; return <div key={`${file.id}:${file.sha256}`}><Button variant="ghost" className="pw-media-file-row" leadingIcon={File} trailingIcon={ChevronRight} disabled={busy || !reading.viewHandle || !file.id || !file.sha256 || opening?.checking} onClick={() => openResult(file)}><span className="pw-media-row-copy"><strong>{file.name || file.mimeType || '原产物'}</strong><small>{opening?.checking ? '正在核对原文件' : !file.id || !file.sha256 ? '原文件身份待核对' : `${file.mimeType || '文件'} · ${formatFileSize(Number(file.size) || 0)} · 打开原产物`}</small></span></Button>{opening?.error && <p role="alert" className="pw-media-note">{opening.error}</p>}</div>; })}</div> : <p className="pw-media-note">原记录尚无已保存文件。服务有结果后，可取回同一原操作的产物。</p>}</section>
      {reading.task.summary && <section className="pw-media-summary"><h4>任务保留的摘要</h4><AIAnswer text={reading.task.summary} /></section>}
      {operation.operationId && <Accordion className="pw-media-disclosures"><AccordionItem id="media-waiting" title="等待与取消" description="停止本地等待与取消远端任务是不同操作"><p className="pw-media-note">停止本地等待不会撤回服务请求，也不代表远端任务已停止。远端取消仍需单独核对。</p><div className="pw-form-actions"><Button disabled={busy || !reading.viewHandle} variant="secondary" onClick={() => operate('media.stopWaiting')}>停止本地等待</Button><Button disabled={busy || !reading.viewHandle} variant="ghost" onClick={() => operate('media.reviewCancel')}>请求取消原服务任务</Button></div></AccordionItem></Accordion>}
    </div>}

    <SettingsDialog isOpen={settingsOpen && !review} title="媒体服务配置" onClose={closeSettings}>{settings && <div className="pw-media-settings">
      <p className="pw-media-note">配置只在核对后保存。关闭时保留非密钥草稿，密钥不会回填或留在草稿中。</p>{error && <p role="alert">{error}</p>}
      <SelectField label="服务类型" value={settings.provider} onChange={value => change('provider', value)} options={[{ id: 'openai_compatible', label: '兼容图片与语音 API' }, { id: 'comfyui', label: 'ComfyUI' }]} isDisabled={busy} />
      {field('baseUrl', '服务地址')}{field('apiKey', '服务密钥，留空保留相同服务原密钥', true)}
      {settings.provider === 'comfyui' ? <>
        <div className="pw-media-settings-group"><h3>图片模板</h3><TextAreaField label="图片 API 模板 JSON（可选）" value={settings.workflowText} onChange={value => change('workflowText', value)} isDisabled={busy} rows={5} /><div className="pw-media-field-pair">{field('promptNodeId', '图片提示词节点 ID')}{field('promptInput', '图片提示词字段')}</div></div>
        <Accordion className="pw-media-disclosures"><AccordionItem id="media-video-template" title="视频模板" description={config.hasVideoWorkflow ? '已有模板，留空保存会清除' : '按需配置视频工作流'}><TextAreaField label="视频 API 模板 JSON（可选）" value={settings.videoWorkflowText} onChange={value => change('videoWorkflowText', value)} isDisabled={busy} rows={5} /><div className="pw-media-field-pair">{field('videoPromptNodeId', '视频提示词节点 ID')}{field('videoPromptInput', '视频提示词字段')}</div></AccordionItem></Accordion>
        <CheckboxField isSelected={settings.comfyJobsApi} onChange={value => change('comfyJobsApi', value)} isDisabled={busy}>服务支持原任务的 jobs 定向取消接口</CheckboxField><p className="pw-media-note">保存会替换模板，留空会清除原模板。默认仅删除原排队项，不使用全局中断。</p>
      </> : <><div className="pw-media-settings-group"><h3>图片与语音模型</h3>{field('imageModel', '图片模型 ID')}<div className="pw-media-field-pair">{field('ttsModel', '语音模型 ID')}{field('voice', 'Voice 标识')}</div></div><CheckboxField isSelected={settings.legacyImageBase64} onChange={value => change('legacyImageBase64', value)} isDisabled={busy}>兼容旧版 Base64 图片响应</CheckboxField><CheckboxField isSelected={settings.allowResultUrls} onChange={value => change('allowResultUrls', value)} isDisabled={busy}>允许获取原响应中的公开 HTTPS 结果地址</CheckboxField><p className="pw-media-note">只读原响应地址，不跟随跳转，不向结果来源发送服务密钥。</p></>}
      <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={closeSettings}>返回工作室</Button><Button disabled={busy || !snapshotReady || !settings.baseUrl.trim()} onClick={configReview}>核对并保存配置</Button></div>
    </div>}</SettingsDialog>
    <SettingsDialog isOpen={!!review} title={review?.command === 'configure' ? '核对媒体服务配置' : review?.command === 'cancelJob' ? '定向取消原服务任务' : review?.command === 'confirmResultDownload' ? '一次取回原结果' : '提交这次媒体请求'} onClose={() => { if (available()) closeReview(); }}>
      {review && <div className="pw-media-review">
        <div className="pw-media-review-identity"><strong>{review.command === 'configure' ? '将保存的服务' : '这一次原操作'}</strong>{reviewInput.taskId && <small>原任务 · {reviewInput.taskId}</small>}{reviewInput.operationId && <small>原服务操作 · {reviewInput.operationId}</small>}{reviewInput.configurationRevision && <small>配置版本 · {reviewInput.configurationRevision}</small>}</div>
        {review.command === 'configure' ? <><p>{reviewInput.provider} · {reviewInput.baseUrl}</p>{review.replacesSavedTemplates && <p role="alert">这次配置会替换已保存的模板；留空的模板会清除。</p>}<AIAnswer text={JSON.stringify(reviewInput, null, 2)} /></> : review.command === 'cancelJob' ? <p>仅针对这项原服务任务。原服务不支持定向取消时会明确拒绝；本地停止不代表远端已经停止。</p> : review.command === 'confirmResultDownload' ? <><p>只从原响应地址 GET 一次，不重新生成。</p><p>原服务：{review.downloadReview?.providerOrigin}<br />结果来源：{review.downloadReview?.resultOrigin}</p><small>原响应 SHA-256 · {review.downloadReview?.providerResponseSha256}</small><small>原结果地址 SHA-256 · {review.downloadReview?.resultUrlSha256}</small></> : <>
          <p>输入将发送到已配置服务，可能产生费用。回执未知时只查询同一原任务。</p><p>{reviewStale ? '服务配置已变化或尚未重新读取，请返回原任务重新核对。' : `${serviceName} · ${config.baseUrl || '服务地址待核对'}`}</p>
          {review.command === 'editImage' && <div className="pw-media-review-identity"><p>会上传选定原图与可选 mask 的完整原字节。</p><small>原图 · {reviewInput.imageId} · {reviewInput.imageSha256}</small>{reviewInput.maskId && <small>Mask · {reviewInput.maskId} · {reviewInput.maskSha256}</small>}</div>}
          <AIAnswer text={inputText(reviewInput)} />{comfy && reviewInput.observeUntilComplete && <p className="pw-media-note">本次输入保留原任务的有限只读续查选择，不续期原授权，不重新生成。</p>}
        </>}
        <CheckboxField isSelected={acknowledged} onChange={value => { acknowledgement.current = value; setAcknowledged(value); }} isDisabled={busy || reviewStale}>已核对本次输入、服务与操作</CheckboxField><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => closeReview()}>返回</Button><Button disabled={busy || !acknowledged || reviewStale} onClick={confirm}>确认这一次操作</Button></div>
      </div>}
    </SettingsDialog>
  </section>;
}
