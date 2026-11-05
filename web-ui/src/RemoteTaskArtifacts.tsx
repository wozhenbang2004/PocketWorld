import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Accordion, AccordionItem, Button, CheckboxField, SelectField, SettingsDialog, TextAreaField, TextField } from './forms-library';
import { registerOverlayBack } from './overlay-back';
import { holdRemoteReads, remoteObject as object, remoteRows as rows, type RemoteData as Data } from './remote-task-workspace';

type Run = (action: string, args?: Data) => Promise<any>;
type Props = { snapshot: Data; busy: boolean; active: boolean; run: Run };
const json = (value: unknown) => typeof value === 'string' ? value : JSON.stringify(value, null, 2);

/** Digest/selection-bound confirmation; Back consumes only this review. */
export function RemoteActionReview({ identity, title, active, busy, expiresAt, onClose, onConfirm, children, confirmLabel = '确认这一次' }: {
  identity: string; title: string; active: boolean; busy: boolean; expiresAt?: number; onClose: () => void; onConfirm: () => void; children: ReactNode; confirmLabel?: string;
}) {
  const [accepted, setAccepted] = useState(false), [expired, setExpired] = useState(false);
  const live = useRef({ identity, accepted, active, busy }), mounted = useRef(true), acceptedIdentity = useRef(''); live.current = { identity, accepted, active, busy };
  const dismiss = () => { live.current.active = false; live.current.accepted = false; acceptedIdentity.current = ''; onClose(); };
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  useEffect(holdRemoteReads, []);
  useEffect(() => { setAccepted(false); setExpired(false); }, [identity]);
  useEffect(() => registerOverlayBack(dismiss), [onClose]);
  useEffect(() => { if (!active) onClose(); }, [active]);
  useEffect(() => { if (!expiresAt) return; const remaining = expiresAt - Date.now(); if (remaining <= 0) { setExpired(true); return; } const timer = setTimeout(() => setExpired(true), remaining); return () => clearTimeout(timer); }, [expiresAt]);
  return <SettingsDialog isOpen={active} title={title} onClose={dismiss}><div className="pw-remote-review">{children}
    <CheckboxField isSelected={accepted && acceptedIdentity.current === identity} isDisabled={busy || expired} onChange={value => { live.current.accepted = value; acceptedIdentity.current = value ? identity : ''; setAccepted(value); }}>我已核对确切目标与完整范围，只同意这一次动作。</CheckboxField>
    {expired && <p className="pw-field-error">本次审阅已过期，请重新读取核对。</p>}
    <div className="pw-form-actions"><Button variant="secondary" onClick={dismiss}>返回核对</Button><Button disabled={!accepted || acceptedIdentity.current !== identity || busy || expired} onClick={() => {
      const now = live.current; if (!mounted.current || !now.active || now.busy || !now.accepted || acceptedIdentity.current !== identity || now.identity !== identity || expiresAt && Date.now() >= expiresAt) return; acceptedIdentity.current = ''; live.current.accepted = false; setAccepted(false); onConfirm();
    }}>{confirmLabel}</Button></div></div></SettingsDialog>;
}

export function RemoteProjectReview({ snapshot, busy, active, run, close }: Props & { close: () => void }) {
  const review = object(snapshot.projectHistoryReview), scope = object(review.scope), desired = object(scope.desired), current = object(scope.current), request = object(scope.request), source = object(scope.source);
  const restoring = scope.action === 'RESTORE';
  return <RemoteActionReview identity={`${snapshot.windowHandle}:${review.digest}`} title={restoring ? '确认只恢复这个文件' : scope.action === 'FORK' ? '确认建立独立对话分支' : '确认项目范围与新工作'} active={active} busy={busy} expiresAt={review.expiresAt} onClose={close}
    confirmLabel={restoring ? '恢复这一个文件' : '只提交这一次新工作'} onConfirm={() => { void run('remoteTask.project.confirm', { digest: review.digest, scopeAcknowledged: true }); close(); }}>
    <p>目标：{scope.serviceBase}<br />环境：{request.environmentId || source.environmentId}</p>
    {restoring ? <><h4>{desired.path}</h4><p>恢复版本：{desired.bytes} 字节 · SHA-256 {desired.sha256}</p><p>当前已读：{current.bytes} 字节 · SHA-256 {current.sha256}</p><p>只写回这一个已冻结文件的实际字节。当前文件漂移会拒绝，结果未知时只查询原编号；旧快照与对话保持原样。</p></> : <>
      {scope.action === 'FORK' ? <p>从原 thread {source.threadId} / turn {source.turnId} 建立真实新分支。只分支对话，工作区仍是当前文件；旧批准和旧预算不会继承。</p> : <p>先保留目录 {request.projectHistory?.projectRoot || '所配置工作区'} 的项目基线，再执行这次工作。</p>}
      <p>{request.worker} · {request.model || '执行端默认模型'}{request.effort ? ` · 请求档位 ${request.effort}` : ''} · {request.timeoutSeconds} 秒</p><pre>{request.prompt}</pre>
      <p>{request.readOnly ? '本次为 Codex 只读分析。' : '按原工作区写入权限执行。'}CLI 内部模型次数、tokens 与费用不可硬限；文件变化记录不证明独占作者。</p>
    </>}
    <Accordion><AccordionItem title="版本与身份细节"><pre>{json(scope)}</pre></AccordionItem></Accordion>
  </RemoteActionReview>;
}

export function RemoteTaskArtifacts({ snapshot, busy, active, run }: Props) {
  const source = object(snapshot.source), task = object(snapshot.task), directory = object(snapshot.resultDirectory), selected = rows(snapshot.selectedResultFiles), saved = rows(task.remoteResultSnapshots);
  const [path, setPath] = useState(''), [manual, setManual] = useState(''), [artifactPath, setArtifactPath] = useState('');
  const [freeze, setFreeze] = useState<Data | null>(null), [selectedSnapshot, setSelectedSnapshot] = useState<Data | null>(null);
  const [prompt, setPrompt] = useState(''), [model, setModel] = useState(''), [effort, setEffort] = useState(''), [changeModel, setChangeModel] = useState(false), [timeout, setTimeoutValue] = useState('300'), [recoveryAck, setRecoveryAck] = useState(false);
  const [continuing, setContinuing] = useState<Data | null>(null), [projectReviewClosed, setProjectReviewClosed] = useState('');
  const [forkPrompt, setForkPrompt] = useState(''), [forkTimeout, setForkTimeout] = useState('300'), [inputSource, setInputSource] = useState(''), [returnClosed, setReturnClosed] = useState('');
  const [notice, setNotice] = useState('');
  const live = useRef({ snapshot, active, busy, prompt, selectedSnapshot, model, effort, changeModel, timeout, recoveryAck, manual, inputSource }), mounted = useRef(true); live.current = { snapshot, active, busy, prompt, selectedSnapshot, model, effort, changeModel, timeout, recoveryAck, manual, inputSource };
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const enabled = active && !busy && snapshot.fresh === true, browse = enabled && snapshot.canCaptureSnapshot === true;
  const safeRun: Run = (action, args = {}) => {
    if (!mounted.current || !live.current.active || live.current.busy || live.current.snapshot.reviewId !== snapshot.reviewId || live.current.snapshot.windowHandle !== snapshot.windowHandle) return Promise.resolve();
    return run(action, args);
  };
  useEffect(() => { setFreeze(null); setContinuing(null); if (!active) { setProjectReviewClosed(snapshot.projectHistoryReview?.digest || ''); setReturnClosed(snapshot.conversationResultReview?.digest || ''); } }, [snapshot.reviewId, active]);
  useEffect(() => { setSelectedSnapshot(null); setChangeModel(false); setModel(''); setEffort(''); setRecoveryAck(false); }, [source.jobOperationId, source.modelOptions?.digest]);
  if (snapshot.configurationOnly || snapshot.teamRemoteWriter || task.teamRunId || source.assignmentWorkspace) return null;
  const result = object(snapshot.result), artifact = object(snapshot.artifact), frozen = object(snapshot.snapshotFile), project = object(snapshot.projectHistoryPage), history = object(project.history), before = object(history.baseline), after = object(history.after), diff = object(snapshot.projectHistoryDiff), projectReview = object(snapshot.projectHistoryReview);
  const operation = object(snapshot.projectHistoryOperation), returnReview = object(snapshot.conversationResultReview), returnScope = object(returnReview.scope), destination = object(returnScope.inputSource);
  const models = rows(source.modelOptions?.models), selectedModel = models.find(item => item.id === model);
  const continuationPayload = () => { const now = live.current; return { prompt: now.prompt, ...(now.selectedSnapshot ? { snapshotId: now.selectedSnapshot.snapshotId, snapshotVersion: now.selectedSnapshot.snapshotVersion } : {}),
    ...(now.snapshot.coldRecoveryConfirmed ? { timeoutSeconds: Number(now.timeout), recoveryOwnerAcknowledged: now.recoveryAck } : now.changeModel ? { modelSelection: { model: now.model, modelOptionsDigest: now.snapshot.source?.modelOptions?.digest, ...(now.effort ? { effort: now.effort } : {}) } } : {}) }; };
  return <Accordion className="pw-remote-artifacts" allowsMultipleExpanded>
    <AccordionItem title="执行输出与当前文件"><div className="pw-remote-section">
      <p className="pw-muted">执行端实际回执不等于当前文件树或冻结成果；退出码 0 也不证明目标完成。</p>
      {result.exitCode != null && <p>退出码：{result.exitCode}</p>}{['output', 'diff'].map(key => result[key] ? <div key={key}><h4>{key === 'output' ? '输出' : '执行器报告的差异'}</h4><pre>{result[key]}</pre>{(result[`${key}ViewTruncated`] || result[`${key}Truncated`]) && <p className="pw-field-hint">正文显示有截断。</p>}</div> : null)}
      <TextField label="原工作区相对文件路径" value={artifactPath} onChange={setArtifactPath} isDisabled={busy} />
      <Button variant="secondary" disabled={!enabled || !artifactPath.trim() || artifactPath.length > 2048} onClick={() => void safeRun('remoteTask.readArtifact', { path: artifactPath.trim() })}>核对当前文件字节与 SHA</Button>
      {artifact.path && <div><h4>{artifact.path} · {artifact.bytes} 字节</h4><p>SHA-256 {artifact.sha256}</p><p className="pw-field-hint">这是读取时的工作区字节，尚未冻结。</p>{artifact.preview === 'utf8_text' ? <pre>{artifact.text}</pre> : <p>二进制原件已核对大小与 SHA。</p>}{artifact.viewTruncated && <p>文字预览已截断。</p>}</div>}
    </div></AccordionItem>
    <AccordionItem title="成果文件与冻结版本"><div className="pw-remote-section">
      <p className="pw-muted">原 job 结算后读取所选文件。文件冻结只证明采集时的实际字节与来源，不证明独占作者。</p>
      <TextField label="工作区相对目录（留空为根目录）" value={path} onChange={setPath} isDisabled={busy} />
      <div className="pw-form-actions"><Button variant="secondary" disabled={!browse} onClick={() => void safeRun('remoteTask.listResultFiles', { path, offset: 0 })}>读取这个目录</Button>{directory.path && <Button variant="ghost" disabled={!browse} onClick={() => { const parent = directory.path.includes('/') ? directory.path.slice(0, directory.path.lastIndexOf('/')) : ''; setPath(parent); void safeRun('remoteTask.listResultFiles', { path: parent, offset: 0 }); }}>上一级</Button>}</div>
      {rows(directory.files).map(file => <div className="pw-remote-file" key={file.path}><div><strong>{file.name || file.path}</strong><small>{file.kind === 'directory' ? '目录' : `${file.bytes} 字节`}</small></div><Button variant="ghost" disabled={!browse || file.kind !== 'directory' && !file.selectable} onClick={() => { if (file.kind === 'directory') { setPath(file.path); void safeRun('remoteTask.listResultFiles', { path: file.path, offset: 0 }); } else void safeRun('remoteTask.selectResultFile', { path: file.path }); }}>{file.kind === 'directory' ? '进入' : selected.some(row => row.path === file.path) ? '移除选择' : '读取并选择'}</Button></div>)}
      {directory.nextOffset != null && <Button variant="ghost" disabled={!browse} onClick={() => void safeRun('remoteTask.listResultFiles', { path: directory.path || '', offset: directory.nextOffset })}>沿同目录版本读取下一页</Button>}
      {directory.inspectionLimitReached && <p className="pw-field-hint">已到本轮检视上限，请进入更具体的目录。</p>}
      {selected.length > 0 && <><p>已选择 {selected.length} 个文件 · {selected.reduce((sum, file) => sum + Number(file.bytes || 0), 0)} 字节</p><Button disabled={!browse} onClick={() => setFreeze({ reviewId: snapshot.reviewId, selected: JSON.stringify(selected), command: 'captureSelectedSnapshot', args: {}, files: selected })}>核对并冻结所选版本</Button></>}
      <Accordion><AccordionItem title="按明确路径采集（高级）"><TextAreaField label="明确文件路径，每行一个（1–8 个）" value={manual} onChange={value => { live.current.manual = value; setManual(value); }} isDisabled={busy} /><Button variant="secondary" disabled={!browse || !manual.trim() || manual.split('\n').filter(line => line.trim()).length > 8} onClick={() => { const paths = manual.split('\n').map(line => line.trim()).filter(Boolean); setFreeze({ reviewId: snapshot.reviewId, manual, command: 'captureSnapshot', args: { paths }, files: paths.map(path => ({ path })) }); }}>核对并保存指定文件快照</Button></AccordionItem></Accordion>
      {rows(task.operations).filter(row => row.tool === 'remote_snapshot').slice(-4).map(row => <p key={row.operationId} className="pw-field-hint">快照操作 {row.operationId} · {row.state === 'unknown' ? '回执待核对，只查询原编号，不重新采集' : row.receipt?.message || row.state}</p>)}
      {saved.map(version => <section className="pw-remote-frozen" key={version.snapshotId}><h4>冻结版本 · {String(version.snapshotVersion).slice(0, 16)}</h4><p>{version.bytes} 字节 · 原 job {version.jobId}</p>
        {rows(version.files).map(file => { const local = rows(snapshot.snapshotAttachments).find(row => row.snapshotId === version.snapshotId && row.path === file.path); return <div key={file.path} className="pw-remote-file"><span>{file.path}</span><div className="pw-form-actions"><Button variant="ghost" disabled={!enabled} onClick={() => void safeRun('remoteTask.readSnapshot', { snapshotId: version.snapshotId, path: file.path })}>查看冻结文件</Button><Button variant="ghost" disabled={!enabled} onClick={() => void safeRun(local ? 'remoteTask.openSavedSnapshot' : 'remoteTask.saveSnapshot', local ? { attachmentId: local.attachment.id } : { snapshotId: version.snapshotId, path: file.path })}>{local ? '打开手机原件' : '存入手机原件'}</Button></div></div>; })}
        {version.jobId === source.jobId && <Button variant="secondary" disabled={!enabled} onClick={() => setSelectedSnapshot(selectedSnapshot?.snapshotId === version.snapshotId ? null : version)}>{selectedSnapshot?.snapshotId === version.snapshotId ? '已选此版本继续（点击取消）' : '以此版本明确继续'}</Button>}
      </section>)}
      {frozen.path && <div><h4>冻结原件：{frozen.path}</h4><p>{frozen.bytes} 字节 · SHA-256 {frozen.sha256}</p>{frozen.preview === 'utf8_text' ? <pre>{frozen.text}</pre> : <p>二进制原件已核对完整字节与 SHA。</p>}{frozen.viewTruncated && <p>预览已截断；SHA 对应完整冻结原件。</p>}</div>}
      {freeze && <RemoteActionReview identity={`${snapshot.windowHandle}:${freeze.reviewId}:${JSON.stringify(freeze.files)}`} title="冻结这些明确文件" active={active} busy={busy} onClose={() => setFreeze(null)} confirmLabel="确认冻结此版本" onConfirm={() => {
        if (!browse || freeze.reviewId !== live.current.snapshot.reviewId || freeze.selected && freeze.selected !== JSON.stringify(live.current.snapshot.selectedResultFiles) || freeze.manual !== undefined && freeze.manual !== live.current.manual) return;
        void safeRun(`remoteTask.${freeze.command}`, freeze.args); setFreeze(null);
      }}><p>原 job：{source.jobId}</p>{rows(freeze.files).map(file => <p key={file.path}>{file.path}{file.sha256 ? <><br />{file.bytes} 字节 · SHA-256 {file.sha256}</> : null}</p>)}<p>只保存上述范围，不递归扫描。已读版本漂移会拒绝；未知回执不自动重试。</p></RemoteActionReview>}
    </div></AccordionItem>
    <AccordionItem title="项目版本与对话分支"><div className="pw-remote-section">
      <p className="pw-muted">比较原 job 前后的冻结源码。未报告的变化按归属未知显示；文件恢复与对话分支分别审阅。</p>
      <Button variant="secondary" disabled={!enabled} onClick={() => void safeRun('remoteTask.project.list', { offset: 0 })}>读取这项原工作前后版本</Button>
      {rows(snapshot.projectHistorySources).map(original => <Button variant="ghost" key={original.jobOperationId} disabled={!enabled} onClick={() => void safeRun('remoteTask.project.list', { offset: 0, operationId: original.jobOperationId })}>查看已登记项目版本 · {original.jobId}</Button>)}
      {project.jobId && <p>正在查看 job {project.jobId} · {project.offset}–{project.nextOffset} / {project.totalFiles} 条</p>}
      {project.jobId && history.comparisonStatus !== 'available' && <p className="pw-field-hint">后置比较尚不可用：{history.reason || history.comparisonStatus}，不是完整变化清单。</p>}
      {rows(project.files).map(file => <section key={file.path}><h4>{({ added: '新增', deleted: '删除', modified: '修改', unchanged: '未变' } as Record<string, string>)[file.change] || '仅基线'} · {file.path}</h4><p className="pw-field-hint">{file.workerReportedPath ? '上游报告涉及此路径，非独占创作证明。' : '实际文件观察，作者归属未知。'}</p><div className="pw-form-actions">
        {history.after && <Button variant="ghost" disabled={!enabled} onClick={() => void safeRun('remoteTask.project.diff', { path: file.path })}>查看实际差异</Button>}
        {[[file.before, before, '审阅恢复修改前版本'], [file.after, after, '审阅恢复工作结束版本']].map(([exists, version, label]) => exists && <Button key={String(label)} variant="ghost" disabled={!enabled} onClick={() => { setProjectReviewClosed(''); void safeRun('remoteTask.project.reviewRestore', { snapshotId: (version as Data).snapshotId, snapshotVersion: (version as Data).snapshotVersion, path: file.path }); }}>{String(label)}</Button>)}
      </div></section>)}
      {project.hasMore && <Button variant="ghost" disabled={!enabled} onClick={() => void safeRun('remoteTask.project.list', { offset: project.nextOffset })}>下一页 · 同一冻结比较版本</Button>}
      {diff.path && <div><h4>差异：{diff.path}</h4>{diff.binary ? <p>二进制版本：{diff.beforeSha256} → {diff.afterSha256}</p> : <pre>{diff.diff || '所读字节没有文本差异'}</pre>}{diff.diffTruncated && <p>文本差异已截断。</p>}</div>}
      {source.controls?.threadFork === true ? <><TextAreaField label="新分支这次要完成什么" value={forkPrompt} onChange={setForkPrompt} isDisabled={busy} /><TextField label="新分支执行时限（10–3600 秒）" value={forkTimeout} onChange={setForkTimeout} inputMode="numeric" isDisabled={busy} /><Button variant="secondary" disabled={!enabled || !forkPrompt.trim() || forkPrompt.length > 12000 || !/^\d+$/.test(forkTimeout) || Number(forkTimeout) < 10 || Number(forkTimeout) > 3600} onClick={() => { setProjectReviewClosed(''); void safeRun('remoteTask.project.reviewFork', { prompt: forkPrompt, timeoutSeconds: Number(forkTimeout) }); }}>审阅这次独立会话分支</Button></> : <p>当前原协议没有可核分支条件；不会把分支失败改成空会话创建。</p>}
      {rows(task.operations).filter(row => row.tool === 'remote_file_restore').slice(-8).map(row => <Button key={row.operationId} variant="ghost" disabled={!enabled} onClick={() => void safeRun('remoteTask.project.queryRestore', { operationId: row.operationId })}>核对原文件恢复 · {row.state}</Button>)}
      {operation.operationId && <><p className="pw-field-hint">原操作 {operation.operationId} · {operation.state}</p>{operation.arguments?.scope?.action === 'FORK' && operation.taskId && <Button variant="secondary" disabled={!enabled} onClick={() => void safeRun('remoteTask.project.openTask', { taskId: operation.taskId })}>打开新任务与真实分支回执</Button>}</>}
      {projectReview.digest && projectReviewClosed !== projectReview.digest && <RemoteProjectReview snapshot={snapshot} busy={busy} active={active} run={safeRun} close={() => setProjectReviewClosed(projectReview.digest)} />}
    </div></AccordionItem>
    <AccordionItem title="继续原会话"><div className="pw-remote-section"><TextAreaField label="本次明确的新要求" value={prompt} onChange={value => { live.current.prompt = value; setPrompt(value); setContinuing(null); }} isDisabled={busy} maxLength={12000} />
      {selectedSnapshot && <p>本次选定快照 {selectedSnapshot.snapshotId}，派发前再次核对原文件版本；漂移时拒绝，不自动换绑。</p>}
      {!snapshot.coldRecoveryConfirmed && models.length > 0 && ['claude', 'codex_app_server'].includes(source.worker) && <><CheckboxField isSelected={changeModel} isDisabled={busy} onChange={setChangeModel}>这次下一轮另选模型／档位</CheckboxField>{changeModel && <><SelectField label="下一轮模型" value={model} isDisabled={busy} onChange={value => { setModel(value); setEffort(''); }} options={models.map(item => ({ id: item.id, label: item.name || item.id }))} /><SelectField label="原生档位" value={effort} isDisabled={busy} onChange={setEffort} options={[{ id: '', label: '保留执行端既有档位' }, ...rows(selectedModel?.efforts).map(item => ({ id: item.id, label: item.id }))]} /><p className="pw-field-hint">选择绑定当前原生模型目录。请求不等于生效，不改变工具权限或预算。</p></>}</>}
      {snapshot.coldRecoveryConfirmed && <><TextField label="本次新执行时限（10–900 秒）" value={timeout} onChange={setTimeoutValue} isDisabled={busy} inputMode="numeric" /><CheckboxField isSelected={recoveryAck} isDisabled={busy} onChange={setRecoveryAck}>明确授权这次新执行与时限，理解内部费用未知，不恢复旧批准。</CheckboxField></>}
      <Button disabled={!enabled || !snapshot.canContinue || !prompt.trim() || prompt.length > 12000 || changeModel && !selectedModel || snapshot.coldRecoveryConfirmed && (!recoveryAck || !/^\d+$/.test(timeout) || Number(timeout) < 10 || Number(timeout) > 900)} onClick={() => setContinuing({ reviewId: snapshot.reviewId, payload: continuationPayload() })}>核对并继续原会话</Button>
      {!snapshot.coldRecoveryConfirmed && ['codex_app_server', 'claude', 'pi'].includes(source.worker) && <Button variant="secondary" disabled={!enabled || !snapshot.canContinue || !prompt.trim() || prompt.length > 12000 || changeModel} onClick={() => setContinuing({ reviewId: snapshot.reviewId, gateway: true, payload: { prompt, ...(selectedSnapshot ? { snapshotId: selectedSnapshot.snapshotId, snapshotVersion: selectedSnapshot.snapshotVersion } : {}) } })}>仅准备网关新轮，暂不执行</Button>}
      {notice && <p role="status">{notice}</p>}
      {continuing && <RemoteActionReview identity={`${snapshot.windowHandle}:${continuing.reviewId}:${JSON.stringify(continuing.payload)}`} title={continuing.gateway ? '仅准备这一轮，尚不运行 CLI' : '确认这次原会话续接'} active={active} busy={busy} onClose={() => setContinuing(null)} confirmLabel={continuing.gateway ? '确认仅准备此轮' : '确认续接原会话一次'} onConfirm={() => {
        const original = continuing, text = prompt, now = live.current; const exact = original.gateway ? { prompt: now.prompt, ...(now.selectedSnapshot ? { snapshotId: now.selectedSnapshot.snapshotId, snapshotVersion: now.selectedSnapshot.snapshotVersion } : {}) } : continuationPayload(); if (!enabled || now.snapshot.reviewId !== original.reviewId || JSON.stringify(original.payload) !== JSON.stringify(exact)) return; setContinuing(null);
        void safeRun(original.gateway ? 'remoteTask.gateway.prepareContinuation' : 'remoteTask.continue', original.payload).then(value => { if (!mounted.current || !live.current.active || live.current.snapshot.windowHandle !== snapshot.windowHandle) return; const state = value?.operation?.state; if (['remote_job_active', 'remote_job_receipt'].includes(state) && live.current.prompt === text) setPrompt(''); setNotice(value?.operation ? `原续接已登记：${state || value.operation.status || '请查询回执'}` : original.gateway && value?.snapshot?.gatewayContinuation?.operationId ? '本轮已准备；配置与最终运行仍须明确审阅。' : '原续接结果待核对，请查询原任务，不要重发。'); });
      }}><p>原电脑 {source.profileId} · 环境 {source.environmentId} · 原会话 {source.sessionId || source.threadId}</p><pre>{continuing.payload.prompt}</pre><p>{continuing.gateway ? '这里只登记本轮输入。随后仍须核对模型网关、准确配置和最终执行范围，尚不派发 CLI。' : '在原任务建立新的执行代次，可能继续修改工作区。没有继承旧批准；费用与最终效果仍按实际回执核对。'}</p>{continuing.payload.snapshotId && <p>只按冻结版本 {continuing.payload.snapshotVersion} 继续。</p>}</RemoteActionReview>}
    </div></AccordionItem>
    {(rows(snapshot.conversationInputSources).length > 0 || rows(snapshot.conversationResultDeliveries).length > 0) && <AccordionItem title="成果回到原私聊"><div className="pw-remote-section">
      <p className="pw-muted">先选这个任务显选过的主人私聊原件，再审阅一份冻结成果，只生成原会话草稿。</p>
      <SelectField label="原私聊来源" value={inputSource} isDisabled={busy} onChange={value => { live.current.inputSource = value; setInputSource(value); setReturnClosed(returnReview.digest || ''); }} options={rows(snapshot.conversationInputSources).map(item => ({ id: item.attachmentId, label: `${item.profileName} · ${item.senderId} · 原消息 ${item.messageId}` }))} />
      {inputSource && rows(snapshot.snapshotAttachments).map(file => <div key={`${file.snapshotId}:${file.path}`}><p>{file.path} · {file.bytes} 字节</p><div className="pw-form-actions">{[['reviewConversationResult', '按完整文字核对'], ['reviewConversationAttachment', '按原文件附件核对']].map(([command, label]) => <Button key={command} variant="secondary" disabled={!enabled || command.endsWith('Attachment') && file.bytes > 8 * 1024 * 1024} onClick={() => { setReturnClosed(''); void safeRun(`remoteTask.${command}`, { inputAttachmentId: inputSource, snapshotId: file.snapshotId, path: file.path }); }}>{label}</Button>)}</div></div>)}
      {rows(snapshot.conversationResultDeliveries).slice(-8).reverse().map(row => <section key={row.id}><p>{({ queued: '原会话草稿已保存，尚未发送', sending: '正在发送，结果待核对', sent: '原平台已接受消息请求，不代表已读', unknown: '投递效果未知，保留原编号，不重发', cancelled: '待发消息已取消' } as Record<string, string>)[row.status] || row.message || '原投递尚未确认'}</p>{row.ownerReplyMedia && <p className="pw-field-hint">附件阶段 {row.mediaPhase} · 已确认上传 {row.mediaVerifiedUploadedBytes || 0} 字节</p>}{row.status === 'queued' && <div className="pw-form-actions"><Button disabled={!enabled} onClick={() => void safeRun('remoteTask.sendConversationResult', { id: row.id })}>发送这条已确认草稿</Button><Button variant="secondary" disabled={!enabled} onClick={() => void safeRun('remoteTask.cancelConversationResult', { id: row.id })}>取消待发</Button></div>}</section>)}
      {returnReview.digest && returnClosed !== returnReview.digest && <RemoteActionReview identity={`${snapshot.windowHandle}:${returnReview.digest}`} title="只生成这条原私聊成果草稿" active={active} busy={busy} expiresAt={returnReview.expiresAt} onClose={() => setReturnClosed(returnReview.digest)} confirmLabel="确认这条原会话草稿" onConfirm={() => { if (destination.attachmentId !== live.current.inputSource) return; void safeRun('remoteTask.confirmConversationResult', { digest: returnReview.digest }); setReturnClosed(returnReview.digest); }}><p>{destination.profileName} · 收件人 {destination.senderId}<br />原消息 {destination.messageId}</p><h4>{returnScope.path} · {returnScope.bytes} 字节</h4>{returnScope.resultKind === 'exact_binary_attachment_not_text_substitution' ? <p>完整原文件附件：{returnScope.media?.name} · {returnScope.media?.mimeType}</p> : <pre>{returnScope.text}</pre>}<p>SHA-256 {returnScope.sha256}</p><p>只包含这份原件。确认后先生成待发草稿，再由你明确发送。</p></RemoteActionReview>}
    </div></AccordionItem>}
  </Accordion>;
}
