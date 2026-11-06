import { useEffect, useRef, useState } from 'react';
import './collaboration-depth.css';
import { Button, SettingsDialog, TextField } from './forms-library';
import { bridge } from './bridge';
import { registerOverlayBack } from './overlay-back';
import { RemoteProjectReview } from './RemoteTaskArtifacts';
import { remoteObject as object, type RemoteData as Data } from './remote-task-workspace';

const currentWindow = () => { const view = object(bridge.getState().view); return [bridge.getDocumentEpoch(), view.resumeRevision, view.section, view.taskId].join('|'); };
export function RemoteProjectBaseline({ prepared, visible, onClose, onRegistered }: { prepared: Data; visible: boolean; onClose: () => void; onRegistered: (operation: Data) => void }) {
  const [snapshot, setSnapshot] = useState<Data | null>(null), [root, setRoot] = useState(''), [reviewRoot, setReviewRoot] = useState<string | null>(null), [closedDigest, setClosedDigest] = useState('');
  const [pending, setPending] = useState(''), [error, setError] = useState('');
  const alive = useRef(true), busy = useRef(false), live = useRef<Data | null>(null), inputs = useRef({ root, visible }), owner = useRef(currentWindow()); inputs.current = { root, visible };
  const current = () => alive.current && inputs.current.visible && currentWindow() === owner.current && document.visibilityState === 'visible';
  const dismiss = () => { alive.current = false; const original = live.current; live.current = null; if (original) void bridge.command('remote.project.close', { windowHandle: original.windowHandle }).catch(() => {}); onClose(); };
  const act = async (action: string, args: Data = {}) => {
    if (!current() || busy.current) return;
    const opening = action === 'remote.project.open', original = live.current;
    if (!opening && !original) return;
    if (action === 'remote.project.confirm' && (reviewRoot !== inputs.current.root || args.digest !== original?.projectHistoryReview?.digest)) return;
    busy.current = true; setPending(action); setError('');
    if (action === 'remote.project.confirm') onRegistered(prepared);
    try { const value = await bridge.command(action, opening ? { reviewHandle: prepared.reviewHandle } : { ...args, windowHandle: original!.windowHandle });
      if (!current()) { if (opening && value?.windowHandle) void bridge.command('remote.project.close', { windowHandle: value.windowHandle }).catch(() => {}); return; }
      if (typeof value?.windowHandle !== 'string' || value.taskId !== prepared.operationId || !opening && value.windowHandle !== original!.windowHandle) throw new Error('原项目基线回执身份不可核对。');
      live.current = value; setSnapshot(value); return value;
    } catch (failure) { if (current()) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { busy.current = false; if (alive.current) setPending(''); }
  };
  useEffect(() => {
    alive.current = true; owner.current = currentWindow(); void act('remote.project.open');
    const retire = dismiss;
    const inspect = () => { if (!current()) retire(); };
    const unsubscribe = bridge.subscribe(inspect); document.addEventListener('visibilitychange', inspect); window.addEventListener('pagehide', retire);
    return () => { alive.current = false; const original = live.current; live.current = null; if (original) void bridge.command('remote.project.close', { windowHandle: original.windowHandle }).catch(() => {}); unsubscribe(); document.removeEventListener('visibilitychange', inspect); window.removeEventListener('pagehide', retire); };
  }, [prepared.reviewHandle]);
  useEffect(() => registerOverlayBack(dismiss), [onClose]);
  const review = snapshot?.projectHistoryReview, operation = snapshot?.projectHistoryOperation;
  return <SettingsDialog isOpen={visible} title="先保留项目基线，再开始工作" onClose={dismiss}><div className="pw-remote-section pw-collaboration-dialog">
    <p>将明确目录的公开源码冻结为修改前基线，再沿同一原任务执行。结束后的文件不能补造启动前版本。</p>
    <p>原工作区：{snapshot?.projectEnvironment?.workspace || '正在核对实际工作区'}</p>
    <TextField label="项目相对目录（留空为配置工作区）" value={root} isDisabled={!!pending || !!operation} onChange={value => { inputs.current.root = value; setRoot(value); setReviewRoot(null); }} />
    <Button variant="secondary" disabled={!snapshot || !!pending || !!operation} onClick={() => { const original = root; setClosedDigest(''); void act('remote.project.review', { projectRoot: root }).then(result => { if (result && inputs.current.root === original) setReviewRoot(original); }); }}>核对基线与这次新工作</Button>
    {error && <p className="pw-field-error" role="alert">{error}</p>}{pending && <p role="status">正在核对原项目操作…</p>}
    {operation && <><p>原操作已登记：{operation.state || '结果待核对'} · {operation.operationId}</p><p className="pw-field-hint">离开页面不会创建另一个任务；未知结果请查询同一原编号。</p><Button onClick={dismiss}>返回原工作与回执</Button></>}
    {review && reviewRoot === root && closedDigest !== review.digest && <RemoteProjectReview snapshot={snapshot!} busy={!!pending} active={visible && current()} close={() => setClosedDigest(review.digest)} run={(_action, args) => act('remote.project.confirm', args)} />}
  </div></SettingsDialog>;
}
