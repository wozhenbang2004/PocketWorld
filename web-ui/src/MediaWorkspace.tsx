import { useEffect, useRef, useState } from 'react';
import { Button, CheckboxField, SelectField, SettingsDialog, TextAreaField, TextField } from './forms-library';
import { AIAnswer, PressButton, ProgressBar } from './library';

type Data = Record<string, any>;
type Run = (action: string, args?: Data) => Promise<any>;
const rows = (value: any): Data[] => Array.isArray(value) ? value : [];
const object = (value: any): Data => value && typeof value === 'object' && !Array.isArray(value) ? value : {};
const submissions = new Set(['generateImage', 'generateVideo', 'editImage', 'synthesizeSpeech', 'submitWorkflow']);
const taskState = (value: string) => ({ awaiting_user: '等待确认输入', running: '正在处理', preparing: '正在准备', needs_confirmation: '成果待核对', completed: '已完成', failed: '未完成', reconciling: '原效果待核对', interrupted: '已中断', cancelled: '已停止' } as Data)[value] || value;

/** Reuses the adopted BoardUI forms and the native, durable two-step media submission. */
export function MediaWorkspace({ state, run, registerTransientBack }: { state: Data; run: Run; registerTransientBack: (handler: (() => boolean) | null) => void }) {
  const [snapshot, setSnapshot] = useState<Data>({}), [reading, setReading] = useState<Data | null>(null), [review, setReview] = useState<Data | null>(null);
  const [settings, setSettings] = useState<Data | null>(null), [acknowledged, setAcknowledged] = useState(false), [busy, setBusy] = useState(false);
  const [error, setError] = useState(''), [notice, setNotice] = useState(''), [kind, setKind] = useState('image'), [text, setText] = useState(''), [graph, setGraph] = useState('');
  const [imageId, setImageId] = useState(''), [maskId, setMaskId] = useState(''), [observe, setObserve] = useState(true), [submittedTask, setSubmittedTask] = useState('');
  const alive = useRef(true), pending = useRef(false), generation = useRef(0), selected = useRef<Data | null>(null), reviewRef = useRef<Data | null>(null);
  const resumed = useRef(state.webWorkspaceResumeRevision), backgrounded = useRef(document.visibilityState !== 'visible'); selected.current = reading; reviewRef.current = review;
  const config = object(snapshot.configuration), comfy = config.provider === 'comfyui';
  const release = (value: Data | null, key: string) => { if (value?.[key]) void run('media.closeReading', { [key]: value[key] }).catch(() => {}); };
  const acceptReading = (value: Data) => { if (!value.taskId || !value.task || value.task.id !== value.taskId) throw new Error('原媒体任务回执身份不匹配。'); release(selected.current, 'viewHandle'); setReading(value); };
  const invoke = async (action: string, args: Data = {}, accept?: (value: Data) => void | Promise<void>) => {
    if (pending.current || backgrounded.current || state.webWorkspaceTaskOpen) return;
    pending.current = true; const ticket = ++generation.current; setBusy(true); setError('');
    try { const value = object(await run(action, args)); if (alive.current && generation.current === ticket) await accept?.(value); }
    catch (failure) { if (alive.current && generation.current === ticket) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { if (generation.current === ticket) { pending.current = false; if (alive.current) setBusy(false); } }
  };
  const refresh = () => { void invoke('media.snapshot', {}, setSnapshot); };
  const readTask = (taskId: string) => { void invoke('media.readTask', { taskId }, acceptReading); };
  const closeReview = () => { release(reviewRef.current, 'reviewHandle'); setReview(null); setAcknowledged(false); };
  const receiveReview = (value: Data) => { if (!value.reviewHandle || !value.command || !value.input) throw new Error('未取得原媒体操作审阅。'); closeReview(); setReview(value); setAcknowledged(false); };
  useEffect(() => { alive.current = true; refresh(); return () => { alive.current = false; generation.current++; release(selected.current, 'viewHandle'); release(reviewRef.current, 'reviewHandle'); }; }, []);
  const restoreWorkspace = () => {
    generation.current++; pending.current = false; closeReview();
    const original = selected.current; setReading(original ? { ...original, viewHandle: '', submissionReview: undefined } : null);
    void invoke('media.snapshot', {}, async value => {
      setSnapshot(value); if (!original?.taskId) return;
      const ticket = generation.current, latest = object(await run('media.readTask', { taskId: original.taskId }));
      if (alive.current && ticket === generation.current) acceptReading(latest);
    });
  };
  useEffect(() => {
    if (resumed.current === state.webWorkspaceResumeRevision) return;
    resumed.current = state.webWorkspaceResumeRevision;
    if (!backgrounded.current) restoreWorkspace();
  }, [state.webWorkspaceResumeRevision]);
  useEffect(() => {
    const hide = () => {
      backgrounded.current = true; generation.current++; pending.current = false;
      setBusy(false); closeReview(); setSettings(value => value ? { ...value, apiKey: '' } : null);
      setReading(value => value ? { ...value, viewHandle: '', submissionReview: undefined } : null);
    };
    const visibility = () => {
      if (document.visibilityState !== 'visible') hide();
      else if (backgrounded.current) { backgrounded.current = false; restoreWorkspace(); }
    };
    document.addEventListener('visibilitychange', visibility); window.addEventListener('pagehide', hide);
    return () => { document.removeEventListener('visibilitychange', visibility); window.removeEventListener('pagehide', hide); };
  });
  useEffect(() => { registerTransientBack(() => { if (busy) return true; if (review) { closeReview(); return true; } if (settings) { setSettings(null); return true; } if (reading) { release(reading, 'viewHandle'); setReading(null); return true; } return false; }); return () => registerTransientBack(null); }, [busy, review, settings, reading, registerTransientBack]);
  const mediaTasks = rows(state.tasks).filter(task => object(task.raw).feature === 'media' || object(object(task.raw).nativeEventTypeFlags).media_owner_prepared === true || rows(object(task.raw).events).some(event => event.type === 'media_owner_prepared'));
  const images = rows(object(object(state.features).attachments).attachments).filter(file => ['image/png', 'image/jpeg', 'image/webp'].includes(file.mimeType) && file.size > 0 && file.size <= 4 * 1024 * 1024);
  const prepare = () => {
    try {
      const args: Data = { kind, observeUntilComplete: observe, ...(kind === 'workflow' ? { workflow: JSON.parse(graph) } : { text }) };
      if (kind === 'edit') { args.imageId = imageId; if (maskId) args.maskId = maskId; }
      void invoke('media.prepare', args, receiveReview);
    } catch { setError('请填写有效的 API 工作流 JSON，没有准备或提交请求。'); }
  };
  const openSettings = () => setSettings({ provider: config.provider || 'openai_compatible', baseUrl: config.baseUrl || '', apiKey: '', imageModel: config.imageModel || '', ttsModel: config.ttsModel || '', voice: config.voice || '', legacyImageBase64: config.legacyImageBase64 === true, allowResultUrls: config.allowResultUrls === true, comfyJobsApi: config.comfyJobsApi === true, workflowText: '', videoWorkflowText: '', promptNodeId: config.promptNodeId || '', promptInput: config.promptInput || 'text', videoPromptNodeId: config.videoPromptNodeId || '', videoPromptInput: config.videoPromptInput || 'text' });
  const configReview = () => {
    if (!settings) return;
    try {
      const { workflowText, videoWorkflowText, ...input } = settings;
      if (settings.provider === 'comfyui') { if (workflowText.trim()) input.workflow = JSON.parse(workflowText); if (videoWorkflowText.trim()) input.videoWorkflow = JSON.parse(videoWorkflowText); }
      void invoke('media.reviewConfiguration', input, value => { setSettings(old => old ? { ...old, apiKey: '' } : null); receiveReview(value); });
    } catch { setError('模板不是有效 JSON，没有保存服务配置。'); }
  };
  const confirm = () => {
    const original = review; if (!original || busy || !acknowledged) return;
    setReview(null); setAcknowledged(false);
    if (submissions.has(original.command)) setSubmittedTask(original.input.taskId);
    void invoke('media.confirm', { reviewHandle: original.reviewHandle }, async value => {
      if (value.confirmed !== true || value.command !== original.command) throw new Error('原媒体操作回执尚待核对。');
      if (original.command === 'configure') { setSnapshot(value.result); setSettings(null); setNotice('媒体服务配置已保存；是否可用仍以实际调用为准。'); return; }
      setNotice(value.result?.message || '已收到原服务回执，请核对原任务与产物。');
      const id = original.input.taskId || reading?.taskId; if (!id) return;
      const ticket = generation.current, latest = object(await run('media.readTask', { taskId: id }));
      if (alive.current && generation.current === ticket) acceptReading(latest);
    });
  };
  const operate = (action: string) => { if (reading?.viewHandle) void invoke(action, { viewHandle: reading.viewHandle }, async value => {
    if (value.reviewHandle) { receiveReview(value); return; }
    if (value.confirmed !== true) throw new Error('原媒体回执尚待核对。');
    setNotice(value.result?.message || '已取得原操作回执。');
    const ticket = generation.current, latest = object(await run('media.readTask', { taskId: reading.taskId }));
    if (alive.current && generation.current === ticket) acceptReading(latest);
  }); };
  const change = (key: string, value: any) => setSettings(old => old ? { ...old, [key]: value } : null);
  const field = (key: string, label: string, secret = false) => <TextField key={key} label={label} type={secret ? 'password' : 'text'} value={settings?.[key] || ''} onChange={value => change(key, value)} isDisabled={busy} />;
  const operation = object(reading?.operation), receipt = object(reading?.receipt), files = rows(receipt.attachments);
  return <section className="pw-settings-section">
    <header className="pw-section-heading"><h2>{reading ? '原媒体任务' : '图片、视频与语音文件'}</h2><p>{reading ? '查询和取回都保持同一服务操作。' : '先核对输入，再明确提交到已配置服务。'}</p></header>
    <div className="pw-form-actions">{reading && <Button variant="ghost" disabled={busy} onClick={() => { release(reading, 'viewHandle'); setReading(null); }}>返回媒体工作</Button>}<Button variant="secondary" disabled={busy} onClick={() => reading ? readTask(reading.taskId) : refresh()}>刷新原记录</Button>{!reading && <Button variant="ghost" disabled={busy} onClick={openSettings}>服务配置</Button>}</div>
    {busy && <ProgressBar label="等待原媒体操作回执" isIndeterminate showValueLabel={false} size="sm" />}{error && <p role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}
    {!reading && <>
      <p>{snapshot.configured ? `已配置 ${config.provider === 'comfyui' ? 'ComfyUI' : '兼容媒体服务'} · ${config.baseUrl}` : '尚未配置媒体服务。'}</p>
      <SelectField label="创作类型" value={kind} onChange={setKind} options={[{ id: 'image', label: '生成图片' }, ...(comfy ? [{ id: 'workflow', label: '运行 ComfyUI API 图' }, ...(config.hasVideoWorkflow ? [{ id: 'video', label: '生成视频' }] : [])] : [{ id: 'speech', label: '生成语音文件' }, ...(snapshot.imageEditingSupported ? [{ id: 'edit', label: '编辑原图' }] : [])])]} isDisabled={busy} />
      {kind === 'edit' && <><SelectField label="已导入的原图" value={imageId} onChange={setImageId} options={images.map(file => ({ id: file.id, label: `${file.name} · ${file.size} 字节` }))} isDisabled={busy} /><SelectField label="透明 PNG mask（可选）" value={maskId} onChange={setMaskId} options={[{ id: '', label: '不使用 mask' }, ...images.filter(file => file.mimeType === 'image/png').map(file => ({ id: file.id, label: file.name }))]} isDisabled={busy} /><p className="pw-muted">确认后上传完整原图；原件保留，新结果单独保存。原图与 mask 需满足原服务的尺寸限制。</p></>}
      <TextAreaField label={kind === 'workflow' ? 'ComfyUI API 工作流 JSON' : kind === 'speech' ? '要合成为语音的文字' : kind === 'edit' ? '描述对原图的修改' : '描述要生成的内容'} value={kind === 'workflow' ? graph : text} onChange={kind === 'workflow' ? setGraph : setText} isDisabled={busy} />
      {comfy && <CheckboxField isSelected={observe} onChange={setObserve} isDisabled={busy}>应用存活期间继续查询这项原任务</CheckboxField>}
      {comfy && <p className="pw-muted">只查询已确认的原服务任务，最多 60 次／5 分钟且受原授权期限限制。</p>}
      <PressButton label="准备并核对输入" disabled={busy || !snapshot.configured || !(kind === 'workflow' ? graph : text).trim() || (kind === 'edit' && !imageId)} onClick={prepare} />
      {submittedTask && <div className="pw-selected-object"><p>这次提交保留在原任务中。回执未知时先查询原记录。</p><Button variant="secondary" disabled={busy} onClick={() => readTask(submittedTask)}>核对刚才的原提交</Button></div>}
      <h3>原媒体任务</h3>{mediaTasks.length ? mediaTasks.map(task => <Button key={task.id} variant="ghost" className="pw-object-row h-auto whitespace-normal" disabled={busy} onClick={() => readTask(task.id)}><span>{task.goal}<small>{taskState(task.status)}</small></span></Button>) : <p className="pw-secondary-empty">当前窗口还没有媒体任务。</p>}
    </>}
    {reading && <><h3>{reading.task.goal}</h3><p>{taskState(reading.task.status)}</p>{reading.task.summary && <AIAnswer text={reading.task.summary} />}
      <div className="pw-form-actions"><Button variant="secondary" onClick={() => void run('tasks.open', { id: reading.taskId }).catch(() => {})}>打开原任务与审批</Button></div>
      {reading.submissionReview && !operation.operationId && <><p>原输入已保存，尚未提交到服务。</p><Button disabled={busy || !reading.viewHandle} onClick={() => receiveReview(reading.submissionReview)}>核对原输入并提交</Button></>}
      {operation.operationId && <><p>{receipt.message || receipt.providerStatus || receipt.status || '原服务状态待核对'}</p><div className="pw-form-actions"><Button disabled={busy || !reading.viewHandle} variant="secondary" onClick={() => operate('media.query')}>查询原操作</Button><Button disabled={busy || !reading.viewHandle} variant="secondary" onClick={() => operate('media.fetchResults')}>取回原产物</Button>{(receipt.ownerResultDownloadReviewAvailable || receipt.resultStatus === 'owner_download_policy_required') && <Button disabled={busy || !reading.viewHandle} variant="secondary" onClick={() => operate('media.reviewDownload')}>核对一次性结果下载</Button>}<Button disabled={busy || !reading.viewHandle} variant="ghost" onClick={() => operate('media.stopWaiting')}>停止本地等待</Button><Button disabled={busy || !reading.viewHandle} variant="ghost" onClick={() => operate('media.reviewCancel')}>请求取消原服务任务</Button></div></>}
      {!!files.length && <section><h3>已保存产物</h3>{files.map(file => <Button key={`${file.id}:${file.sha256}`} variant="secondary" disabled={busy} onClick={() => void invoke('media.openResult', { viewHandle: reading.viewHandle, fileId: file.id, sha256: file.sha256 })}>{file.name || file.mimeType} · {file.size} 字节{object(state.attachmentOpens)[`${file.id}:${file.sha256}`]?.checking ? ' · 正在核对' : ''}</Button>)}{files.map(file => object(state.attachmentOpens)[`${file.id}:${file.sha256}`]?.error && <p role="alert" key={file.id}>{object(state.attachmentOpens)[`${file.id}:${file.sha256}`].error}</p>)}</section>}
    </>}
    <SettingsDialog isOpen={!!settings} title="媒体服务配置" onClose={() => { if (!busy) setSettings(null); }}>{settings && <>
      <SelectField label="服务类型" value={settings.provider} onChange={value => change('provider', value)} options={[{ id: 'openai_compatible', label: '兼容图片与语音 API' }, { id: 'comfyui', label: 'ComfyUI' }]} isDisabled={busy} />
      {field('baseUrl', '服务地址')}{field('apiKey', '服务密钥，留空保留相同服务原密钥', true)}
      {settings.provider === 'comfyui' ? <>{['workflowText', 'videoWorkflowText'].map(key => <TextAreaField key={key} label={key === 'workflowText' ? '图片 API 模板 JSON（可选）' : '视频 API 模板 JSON（可选）'} value={settings[key]} onChange={value => change(key, value)} isDisabled={busy} />)}{field('promptNodeId', '图片提示词节点 ID')}{field('promptInput', '图片提示词字段')}{field('videoPromptNodeId', '视频提示词节点 ID')}{field('videoPromptInput', '视频提示词字段')}<CheckboxField isSelected={settings.comfyJobsApi} onChange={value => change('comfyJobsApi', value)} isDisabled={busy}>服务支持原任务的 jobs 定向取消接口</CheckboxField><p className="pw-muted">保存时模板留空会清除原模板。默认仅删除原排队项，不使用全局中断。</p></> : <>{field('imageModel', '图片模型 ID')}{field('ttsModel', '语音模型 ID')}{field('voice', 'Voice 标识')}<CheckboxField isSelected={settings.legacyImageBase64} onChange={value => change('legacyImageBase64', value)} isDisabled={busy}>兼容旧版 Base64 图片响应</CheckboxField><CheckboxField isSelected={settings.allowResultUrls} onChange={value => change('allowResultUrls', value)} isDisabled={busy}>允许获取原响应中的公开 HTTPS 结果地址</CheckboxField><p className="pw-muted">只读原响应地址，不跟随跳转，不向结果来源发送服务密钥。</p></>}
      <Button disabled={busy || !settings.baseUrl.trim()} onClick={configReview}>核对并保存配置</Button>
    </>}</SettingsDialog>
    <SettingsDialog isOpen={!!review} title={review?.command === 'configure' ? '核对媒体服务配置' : review?.command === 'cancelJob' ? '定向取消原服务任务' : review?.command === 'confirmResultDownload' ? '一次取回原结果' : '提交这次媒体请求'} onClose={() => { if (!busy) closeReview(); }}>
      {review && <>{review.command === 'configure' ? <><p>{review.input.provider} · {review.input.baseUrl}</p>{review.replacesSavedTemplates && <p>这次配置会替换已保存的模板；留空的模板会清除。</p>}</> : review.command === 'cancelJob' ? <p>仅针对这项原服务任务。原服务不支持定向取消时会明确拒绝；本地停止不代表远端已经停止。</p> : review.command === 'confirmResultDownload' ? <><p>只从原响应地址 GET 一次，不重新生成。</p><p>{review.downloadReview?.providerOrigin} → {review.downloadReview?.resultOrigin}</p></> : <><p>输入将发送到已配置服务，可能产生费用。回执未知时只查询同一原任务。</p>{review.command === 'editImage' && <p>会上传选定原图与可选 mask 的完整原字节。</p>}<AIAnswer text={review.input.prompt || review.input.text || JSON.stringify(review.input.workflow, null, 2)} /></>}
        <CheckboxField isSelected={acknowledged} onChange={setAcknowledged} isDisabled={busy}>已核对本次输入、服务与操作</CheckboxField><div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={closeReview}>返回</Button><Button disabled={busy || !acknowledged} onClick={confirm}>确认这一次操作</Button></div>
      </>}
    </SettingsDialog>
  </section>;
}
