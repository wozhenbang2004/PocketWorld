import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Button, SelectField, SettingsDialog, TextField } from './forms-library';
import { bridge } from './bridge';
import { registerOverlayBack } from './overlay-back';
import { RemoteActionReview } from './RemoteTaskArtifacts';
import { holdRemoteReads, remoteObject as object, remoteRows as rows, remotePreviewIsVisible, type RemoteData as Data } from './remote-task-workspace';

const ownerWindow = () => { const view = object(bridge.getState().view); return [bridge.getDocumentEpoch(), view.taskId, view.section, view.resumeRevision].join('|'); };
export function RemoteTaskPreview({ taskId, enabled }: { taskId: string; enabled: boolean }) {
  const [view, setView] = useState<Data | null>(null), [pending, setPending] = useState(''), [error, setError] = useState('');
  const [profileId, setProfile] = useState(''), [port, setPort] = useState('5173'), [seconds, setSeconds] = useState('300');
  const [review, setReview] = useState<Data | null>(null);
  const live = useRef<Data | null>(null), alive = useRef(true), generation = useRef(0), active = useRef(false), originalWindow = useRef(ownerWindow()), viewport = useRef<HTMLDivElement>(null);
  const inputs = useRef({ profileId, port, seconds }); inputs.current = { profileId, port, seconds };
  const close = () => { const original = live.current; live.current = null; generation.current++; setView(null); setReview(null); setError(''); if (original) void bridge.command('remotePreview.close', { pageHandle: original.pageHandle }).catch(() => {}); };
  const current = () => alive.current && document.visibilityState === 'visible' && object(bridge.getState().view).taskId === taskId;
  const request = async (action: string, args: Data = {}) => {
    if (active.current || !current()) return;
    const epoch = generation.current, owner = ownerWindow(), handle = live.current?.pageHandle;
    if (action !== 'remotePreview.openPage' && !handle) return;
    active.current = true; setPending(action); setError('');
    try { const result = await bridge.command(action, action === 'remotePreview.openPage' ? { taskId } : { ...args, pageHandle: handle });
      if (!current() || generation.current !== epoch || ownerWindow() !== owner) { if (action === 'remotePreview.openPage' && result?.pageHandle) void bridge.command('remotePreview.close', { pageHandle: result.pageHandle }).catch(() => {}); return; }
      if (typeof result?.pageHandle !== 'string' || result.taskId !== taskId || action !== 'remotePreview.openPage' && result.pageHandle !== handle) throw new Error('原预览回执身份不可核对。');
      live.current = result; setView(result); return result;
    } catch (failure) { if (current() && generation.current === epoch && ownerWindow() === owner) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { active.current = false; if (alive.current) setPending(''); }
  };
  useEffect(() => {
    alive.current = true; originalWindow.current = ownerWindow();
    const inspect = () => { const owner = ownerWindow(); if (owner !== originalWindow.current || !current()) { originalWindow.current = owner; close(); } };
    const unsubscribe = bridge.subscribe(inspect); document.addEventListener('visibilitychange', inspect); window.addEventListener('pagehide', close);
    return () => { alive.current = false; generation.current++; const original = live.current; live.current = null; if (original) void bridge.command('remotePreview.close', { pageHandle: original.pageHandle }).catch(() => {}); unsubscribe(); document.removeEventListener('visibilitychange', inspect); window.removeEventListener('pagehide', close); };
  }, [taskId]);
  useEffect(() => { if (!view) return; return registerOverlayBack(close); }, [view?.pageHandle]);
  useEffect(() => { if (view) return holdRemoteReads(); }, [view?.pageHandle]);
  useEffect(() => { if (!view || !view.starting && !view.mounted) return; const timer = setTimeout(() => { void request('remotePreview.read'); }, 1000); return () => clearTimeout(timer); }, [view, pending]);
  useLayoutEffect(() => {
    if (!view?.mounted) return;
    const pageHandle = view.pageHandle, owner = ownerWindow(); let frame = 0, disposed = false, last = '';
    const send = (visible: boolean) => {
      if (ownerWindow() !== owner) return; const node = viewport.current, rect = node?.getBoundingClientRect();
      const args = { pageHandle, visible: visible && !!rect && remotePreviewIsVisible(node, document),
        rect: { x: rect?.x || 0, y: rect?.y || 0, width: Math.max(0, rect?.width || 0), height: Math.max(0, rect?.height || 0) }, viewportWidth: Math.max(1, window.innerWidth), viewportHeight: Math.max(1, window.innerHeight) };
      const encoded = JSON.stringify(args); if (last === encoded) return; last = encoded;
      void bridge.command('remotePreview.viewport', args).catch(() => {});
    };
    const schedule = () => { cancelAnimationFrame(frame); frame = requestAnimationFrame(() => { if (!disposed) send(true); }); };
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(schedule); if (viewport.current) observer?.observe(viewport.current);
    const overlays = typeof MutationObserver === 'undefined' ? null : new MutationObserver(schedule);
    overlays?.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'style', 'hidden', 'inert', 'aria-hidden', 'aria-modal', 'data-state', 'data-visible'] });
    window.addEventListener('resize', schedule); window.addEventListener('scroll', schedule, true); window.visualViewport?.addEventListener('resize', schedule); window.visualViewport?.addEventListener('scroll', schedule); document.addEventListener('visibilitychange', schedule);
    send(true); schedule();
    return () => { disposed = true; cancelAnimationFrame(frame); observer?.disconnect(); overlays?.disconnect(); window.removeEventListener('resize', schedule); window.removeEventListener('scroll', schedule, true); window.visualViewport?.removeEventListener('resize', schedule); window.visualViewport?.removeEventListener('scroll', schedule); document.removeEventListener('visibilitychange', schedule); send(false); };
  }, [view?.mounted, view?.pageHandle]);
  if (!bridge.getState().capabilities.commands.includes('remotePreview.openPage')) return null;
  const profiles = rows(view?.profiles), selected = profiles.find(row => row.id === profileId), fieldsValid = /^\d+$/.test(port) && Number(port) >= 1024 && Number(port) <= 65535 && /^\d+$/.test(seconds) && Number(seconds) >= 10 && Number(seconds) <= 600;
  const reviewedInput = object(review?.input), reviewedProfile = object(review?.sshProfile);
  return <><Button variant="secondary" disabled={!enabled || !!pending} onClick={() => void request('remotePreview.openPage')}>预览这个项目已运行的网页</Button>
    <SettingsDialog isOpen={!!view} title="原项目网页预览" onClose={close}><div className="pw-remote-section">
      <p>先在电脑正常运行开发服务器。这里只建立所选 SSH 到电脑 127.0.0.1 单一端口的限时转发，不启动服务。</p>
      {!view?.mounted && <><SelectField label="原 SSH 连接" value={profileId} isDisabled={!!pending || !!view?.starting} onChange={value => { inputs.current.profileId = value; setProfile(value); setReview(null); }} options={profiles.map(row => ({ id: row.id, label: `${row.name} · ${row.host} · ${row.workspace || ''}` }))} />
        {!profiles.length && <p className="pw-field-hint">尚无已核对主机密钥且已配置凭据的 SSH 连接，请先到连接设置完成。</p>}
        <TextField label="电脑上已运行的开发端口" value={port} inputMode="numeric" isDisabled={!!pending || !!view?.starting} onChange={value => { inputs.current.port = value; setPort(value); setReview(null); }} />
        <TextField label="隧道保留秒数（10–600）" value={seconds} inputMode="numeric" isDisabled={!!pending || !!view?.starting} onChange={value => { inputs.current.seconds = value; setSeconds(value); setReview(null); }} />
        <Button variant="secondary" disabled={!!pending || !!view?.starting || !selected || !fieldsValid} onClick={() => { const original = { profileId, port, seconds }; void request('remotePreview.review', { profileId, bindingHash: selected!.bindingHash, remotePort: Number(port), timeoutSeconds: Number(seconds) }).then(result => { if (result?.review && JSON.stringify(inputs.current) === JSON.stringify(original)) setReview(result.review); }); }}>核对准确转发范围</Button>
        {view?.starting && <p role="status">原隧道连接正在核对，尚未确认已建立。</p>}
      </>}
      {view?.mounted && <><div ref={viewport} className="pw-remote-preview-viewport" aria-label="原生隔离开发页面" /><p className="pw-field-hint">仅原隧道页面和同端口 WebSocket；其他来源或端口会拒绝。页面脚本会运行。</p></>}
      {(view?.starting || view?.mounted || view?.hostTaskId) && <div className="pw-form-actions"><Button variant="secondary" disabled={!!pending} onClick={() => void request('remotePreview.stop')}>断开本机隧道</Button>{view?.hostTaskId && <Button variant="ghost" disabled={!!pending} onClick={() => void request('remotePreview.openTask')}>查看原隧道任务与关闭回执</Button>}</div>}
      {(error || view?.error) && <p className="pw-field-error" role="alert">{error || view?.error}</p>}
      {view?.receipt && <p className="pw-field-hint">原隧道回执：{view.receipt.status || (view.receipt.forwardingEstablished ? '已建立转发' : '转发尚未确认')}。断开本机隧道不代表远端服务停止。</p>}
    </div></SettingsDialog>
    {review && <RemoteActionReview identity={`${view?.pageHandle}:${review.digest}`} title="明确打开这个预览隧道" active={!!view && current()} busy={!!pending} expiresAt={review.expiresAt} onClose={() => setReview(null)} confirmLabel="打开这一个原隧道" onConfirm={() => {
      if (inputs.current.profileId !== reviewedProfile.id || Number(inputs.current.port) !== reviewedInput.remotePort || Number(inputs.current.seconds) !== reviewedInput.timeoutSeconds) return;
      void request('remotePreview.start', { digest: review.digest, accepted: true }); setReview(null);
    }}><p>原电脑任务 {reviewedInput.previewSource?.taskId}<br />原 job {reviewedInput.previewSource?.jobId}</p><p>SSH {reviewedProfile.host} → 127.0.0.1:{reviewedInput.remotePort}<br />本机随机回环端口，保留 {reviewedInput.timeoutSeconds} 秒。</p><p>请核对这是原项目的机器与服务。网页脚本及同端口 WebSocket 会运行；其他本机 App 可能访问本机端口。离页、后台或到期会断开本隧道，远端服务继续状态需另行核对。</p></RemoteActionReview>}
  </>;
}
