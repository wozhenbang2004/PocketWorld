import { useEffect, useRef, useState } from 'react';
import { Accordion, AccordionItem, Button, CheckboxField, SettingsDialog, TextAreaField, TextField } from './forms-library';
import { bridge } from './bridge';
import { registerOverlayBack } from './overlay-back';
import { RemoteActionReview } from './RemoteTaskArtifacts';
import { holdRemoteReads, remoteObject as object, remoteRows as rows, type RemoteData as Data } from './remote-task-workspace';

const ownerWindow = () => { const view = object(bridge.getState().view); return [bridge.getDocumentEpoch(), view.resumeRevision, view.section, view.taskId].join('|'); };
export function RemoteInputFlow({ taskId, continuation = false, prepared, visible = true, close, onRegistered }: {
  taskId?: string; continuation?: boolean; prepared?: Data; visible?: boolean; close: () => void; onRegistered?: (operation: Data) => void;
}) {
  const [view, setView] = useState<Data | null>(null), [pending, setPending] = useState<string[]>([]), [error, setError] = useState(''), [readFailed, setReadFailed] = useState(false), [unknown, setUnknown] = useState(false);
  const [prompt, setPrompt] = useState(''), [timeout, setTimeoutValue] = useState('300'), [confirmClosed, setConfirmClosed] = useState('');
  const alive = useRef(true), live = useRef<Data | null>(null), operations = useRef(new Set<string>()), owner = useRef(ownerWindow()), visibleNow = useRef(visible); visibleNow.current = visible;
  const registered = useRef(false), input = useRef({ prompt, timeout }), unknownNow = useRef(false); input.current = { prompt, timeout }; unknownNow.current = unknown;
  const dismiss = () => { alive.current = false; const original = live.current; live.current = null; if (original) void bridge.command('remoteInputs.close', { pageHandle: original.pageHandle }).catch(() => {}); close(); };
  const current = () => alive.current && visibleNow.current && document.visibilityState === 'visible' && ownerWindow() === owner.current;
  const act = async (operation: string, args: Data = {}) => {
    const observing = ['inspect', 'cancel'].includes(operation), opening = operation === 'open';
    if (!current() || operations.current.has(operation) || operations.current.size > 0 && !observing || !opening && !live.current || unknownNow.current && !['inspect', 'query', 'cancel', 'openTask'].includes(operation)) return;
    const original = live.current, expectedTask = prepared?.operationId || taskId;
    operations.current.add(operation); setPending([...operations.current]); setError('');
    if (operation === 'confirm' && prepared && !registered.current) { registered.current = true; onRegistered?.(prepared); }
    try {
      const action = opening && prepared ? 'remote.inputs.open' : `remoteInputs.${operation}`;
      const payload = opening ? prepared ? { reviewHandle: prepared.reviewHandle } : { taskId, continuation } : { ...args, pageHandle: original!.pageHandle, viewHandle: original!.viewHandle };
      const result = await bridge.command(action, payload);
      if (!current()) { if (opening && result?.pageHandle) void bridge.command('remoteInputs.close', { pageHandle: result.pageHandle }).catch(() => {}); return; }
      if (operation === 'openTask') { if (result?.navigationRequested !== true || result.taskId !== expectedTask) throw new Error('原任务导航未确认。'); close(); return; }
      if (result?.taskId !== expectedTask || typeof result?.pageHandle !== 'string' || typeof result?.viewHandle !== 'string' || !opening && result.pageHandle !== original?.pageHandle) throw new Error('原附件回执身份不可核对。');
      live.current = result; setView(result); setReadFailed(false);
      if (operation === 'query' || operation === 'inspect') { unknownNow.current = false; setUnknown(false); }
      return result;
    } catch (failure) {
      if (current()) { setError(failure instanceof Error ? failure.message : String(failure)); if (operation === 'inspect') setReadFailed(true); if (!['open', 'inspect', 'query'].includes(operation)) { unknownNow.current = true; setUnknown(true); } }
    } finally { operations.current.delete(operation); if (alive.current) setPending([...operations.current]); }
  };
  useEffect(() => {
    alive.current = true; owner.current = ownerWindow(); void act('open');
    const inspect = () => { if (!current()) dismiss(); };
    const unsubscribe = bridge.subscribe(inspect); document.addEventListener('visibilitychange', inspect); window.addEventListener('pagehide', dismiss);
    return () => { alive.current = false; const original = live.current; live.current = null; if (original) void bridge.command('remoteInputs.close', { pageHandle: original.pageHandle }).catch(() => {}); unsubscribe(); document.removeEventListener('visibilitychange', inspect); window.removeEventListener('pagehide', dismiss); };
  }, [taskId, continuation, prepared?.reviewHandle]);
  useEffect(holdRemoteReads, []);
  useEffect(() => registerOverlayBack(dismiss), [close]);
  useEffect(() => { if (!view || readFailed || !view.busy && !pending.includes('confirm') || pending.includes('inspect')) return; const timer = setTimeout(() => { void act('inspect'); }, 1000); return () => clearTimeout(timer); }, [view, pending, readFailed]);
  const busy = pending.some(operation => operation !== 'inspect') || view?.busy === true, selected = rows(view?.files), available = rows(view?.availableFiles), target = object(view?.request), scope = object(view?.scope), review = object(view?.review), receiver = object(view?.inputState);
  const selectedIds = new Set(selected.map(file => file.attachmentId));
  const canReview = !!view && !busy && !unknown && selected.length > 0 && (!view.continuation || view.registered || target.prompt === prompt && target.timeoutSeconds === Number(timeout));
  return <SettingsDialog isOpen={visible} title="把选定原件交给这项电脑任务" onClose={dismiss}><div className="pw-remote-section">
    <p>这里的选择独立于聊天附件。确认后只发送列出的原件，其他文件与账号凭据不会自动加入。</p>
    <p>目标：{target.profileId || prepared?.profileId} · {target.environmentId || prepared?.environmentId} · {target.worker || prepared?.worker}<br />工作区：{scope.workspace || view?.environment?.workspace || '正在核对'}</p>
    {!view?.registered && continuation && <><p className="pw-field-hint">在原会话建立本次新的执行和输入目录；旧 job、旧原件与旧批准保持各自记录。</p><TextAreaField label="这次如何使用新文件" value={prompt} onChange={value => { input.current.prompt = value; setPrompt(value); setConfirmClosed(review.digest || ''); }} isDisabled={busy} maxLength={8000} /><TextField label="本次新执行时限（10–3600 秒）" value={timeout} onChange={value => { input.current.timeout = value; setTimeoutValue(value); setConfirmClosed(review.digest || ''); }} isDisabled={busy} inputMode="numeric" /><Button variant="secondary" disabled={busy || unknown || !view || !prompt.trim() || prompt.length > 8000 || !/^\d+$/.test(timeout) || Number(timeout) < 10 || Number(timeout) > 3600} onClick={() => void act('setContinuation', { prompt, timeoutSeconds: Number(timeout) })}>保存本次新要求与时限</Button></>}
    {view && !view.registered && <><h4>选择私人原件</h4><p className="pw-field-hint">最多 8 个，每个 256 KiB，总计 512 KiB。群技术副本不进入此私人范围。</p>{!available.length && <p>暂无可选原件。先从原附件入口导入，再重新打开本页。</p>}{available.map(file => <CheckboxField key={`${file.id}:${file.sha256}`} isSelected={selectedIds.has(file.id)} isDisabled={busy || unknown || !selectedIds.has(file.id) && selectedIds.size >= 8} onChange={checked => {
      if (!current() || busy || unknown || live.current?.viewHandle !== view.viewHandle) return; const ids = new Set(selectedIds); if (checked) ids.add(file.id); else ids.delete(file.id); setConfirmClosed(review.digest || '');
      void act('select', { files: available.filter(row => ids.has(row.id)).map(row => ({ id: row.id, sha256: row.sha256 })) });
    }}><span>{file.name} · {file.size} 字节 · {file.mimeType}{file.channelSource && <small> · 原私聊 {file.channelSource.profileName} / {file.channelSource.senderId} / {file.channelSource.messageId}</small>}</span></CheckboxField>)}</>}
    {view?.registered && <p className="pw-field-hint">原任务已登记，目标与原件范围不会改绑；未知结果只查询原编号，不重传 body 或重建 job。</p>}
    {selected.length > 0 && <><p>已核对 {selected.length} 个原件 · {selected.reduce((sum, file) => sum + Number(file.bytes || 0), 0)} 字节</p>{selected.map(file => <p key={file.attachmentId}>{file.name} · {file.bytes} 字节 · {file.mimeType}</p>)}<Button variant="secondary" disabled={!canReview} onClick={() => { setConfirmClosed(''); void act('review'); }}>{view?.registered ? '重新核对原范围' : '核对目标与外传范围'}</Button></>}
    <p role="status">{view?.message || '正在读取原生范围…'}</p>{typeof view?.fileIndex === 'number' && view.fileIndex >= 0 && <p className="pw-field-hint">第 {view.fileIndex + 1} 个文件已写入传输 {view.writtenBytes} 字节，尚不等于远端 hash 已核对。</p>}
    {receiver.phase && <><p>远端阶段：{receiver.phase} · 已核 hash {receiver.verifiedBytes || 0} / {receiver.bytes || 0} 字节</p><p className="pw-field-hint">{receiver.workerStarted ? '原 worker 报告已启动，后续审批与结果在原任务查看。' : '尚无原 worker 已启动回执，ready 前不会启动。'}</p></>}
    {view?.registered && <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={() => void act('query')}>查询原 job，不重发</Button><Button variant="secondary" disabled={pending.includes('cancel')} onClick={() => void act('cancel')}>请求停止本次原任务</Button><Button variant="ghost" disabled={pending.includes('openTask') || busy} onClick={() => void act('openTask')}>打开原任务与成果</Button></div>}
    {error && <p className="pw-field-error" role="alert">{error}</p>}{unknown && <p className="pw-field-hint">本次效果待核对，请查询原记录；不会自动重复发送原件。</p>}
    <p className="pw-field-hint">输入副本可能保留，取消不等于远端删除。离页或切后台会撤销尚未派发的确认，已发送字节不能撤回。</p>
    {review.digest && confirmClosed !== review.digest && <RemoteActionReview identity={`${view?.pageHandle}:${review.digest}`} title="仅向这个准确目标发送这些原件" active={visible && current()} busy={busy} expiresAt={review.expiresAt} onClose={() => setConfirmClosed(review.digest)} confirmLabel="确认发送本次原件" onConfirm={() => {
      const original = live.current;
      if (!original || original.review?.digest !== review.digest || continuation && !view?.registered && (original.request?.prompt !== input.current.prompt || original.request?.timeoutSeconds !== Number(input.current.timeout))) return;
      void act('confirm', { digest: review.digest, billingAcknowledged: true }); setConfirmClosed(review.digest);
    }}><p>{scope.profileId}<br />{scope.serviceBase}<br />{scope.workspace}/{scope.inputDirectory}</p><p>{scope.request?.worker} · {scope.request?.model || '执行端默认模型'}{scope.request?.effort ? ` · 推理强度 ${scope.request.effort}` : ''} · 本次时限 {scope.request?.timeoutSeconds} 秒</p>
      {scope.continuationSource && <p>原会话 {scope.continuationSource.threadId} · 来源 job {scope.continuationSource.jobId} · 本次新执行不重发旧操作。</p>}
      {rows(scope.files).map(file => <p key={file.attachmentId}>{file.name} · {file.bytes} 字节 · {file.mimeType}<br />SHA-256 {file.sha256}</p>)}
      {rows(view?.channelInputSources).map(source => <p key={source.attachmentId}>原私聊 {source.profileName} · {source.senderId} · 消息 {source.messageId}。本次只批准向所选电脑外传，不自动回发成果。</p>)}
      <p>输入目录不是 OS 读写或网络沙箱。CLI 内部模型调用、tokens 与费用不能在此硬限；上传成功不保证理解任意文件格式。</p><Accordion><AccordionItem title="本次目标全文"><pre>{scope.request?.prompt}</pre></AccordionItem></Accordion>
    </RemoteActionReview>}
  </div></SettingsDialog>;
}

export function RemoteTaskInputActions({ snapshot, enabled, onReturn }: { snapshot: Data; enabled: boolean; onReturn: () => void }) {
  const [mode, setMode] = useState<'continuation' | 'original' | null>(null);
  if (snapshot.teamRemoteWriter || snapshot.configurationOnly || !bridge.getState().capabilities.commands.includes('remoteInputs.open')) return null;
  const close = () => { setMode(null); onReturn(); };
  return <><div className="pw-form-actions">{snapshot.canContinue && <Button variant="secondary" disabled={!enabled || !snapshot.fresh} onClick={() => setMode('continuation')}>选择新文件，在原会话继续</Button>}{snapshot.task?.remoteInputScope && <Button variant="ghost" disabled={!enabled} onClick={() => setMode('original')}>原私人附件暂存与传输回执</Button>}</div>{mode && <RemoteInputFlow taskId={snapshot.taskId} continuation={mode === 'continuation'} close={close} />}</>;
}
