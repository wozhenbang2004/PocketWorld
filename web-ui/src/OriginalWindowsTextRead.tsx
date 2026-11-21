import { useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Button } from './forms-library';
import type { TaskHeader } from './task-history';
import type { TaskOperation } from './task-presentation';

const record = (value: unknown) => value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, any> : undefined;
const nativeId = (value: unknown): value is string => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(value);
type Original = { taskId: string; runId: string; epoch: number; operationId: string; deviceId: string; instanceId: string; fingerprint: string };
type Snapshot = { text: string; bytes: number; recordedAt: number };

/** Visible local tool row only. Native rechecks the actual Work, wire, claim and current peer. */
export function originalWindowsTextAt(task: TaskHeader, op: TaskOperation): Original | undefined {
  const raw = task.raw, work = raw?.ownerDeviceWork, pin = work?.directWorkspace, args = record(op.arguments);
  const send = record(record(op.receipt)?.nativeDeviceSend);
  if (!raw || raw.id !== task.id || raw.actorId !== 'owner' || raw.spaceId !== 'private' || raw.origin != null || raw.contextWithdrawn === true
    || work?.format !== 'pocketworld.owner-device-work.v1' || work.requestId !== task.id || work.directPhone != null
    || !nativeId(task.id) || !nativeId(raw.runId) || op.runId !== raw.runId || !Number.isSafeInteger(raw.coordinatorEpoch) || raw.coordinatorEpoch <= 0
    || !nativeId(op.operationId) || op.tool !== 'device_invoke_tool' || !op.bodyAvailable || !['unknown', 'receipt'].includes(op.state)
    || send?.format !== 'pocketworld.native-device-send.v1' || send.state !== 'write_claimed' || args?.toolName !== 'workspace.read'
    || typeof pin?.deviceId !== 'string' || !/^pw1_[a-f0-9]{64}$/.test(pin.deviceId) || args.targetDeviceId !== pin.deviceId
    || !nativeId(pin.instanceId) || typeof pin.certificateFingerprint !== 'string' || !/^[a-f0-9]{64}$/.test(pin.certificateFingerprint)) return;
  return { taskId: task.id, runId: raw.runId, epoch: raw.coordinatorEpoch, operationId: op.operationId,
    deviceId: pin.deviceId, instanceId: pin.instanceId, fingerprint: pin.certificateFingerprint };
}

function snapshotOf(value: unknown, original: Original): Snapshot {
  const item = record(value);
  if (item?.format !== 'pocketworld.original-windows-text.v1' || item.readOnly !== true || item.taskId !== original.taskId
    || item.runId !== original.runId || item.epoch !== original.epoch || item.operationId !== original.operationId
    || item.deviceId !== original.deviceId || item.instanceId !== original.instanceId || typeof item.text !== 'string'
    || !Number.isSafeInteger(item.bytes) || item.bytes < 0 || item.bytes > 1024 || new TextEncoder().encode(item.text).length > 1024
    || (item.bytes === 0) !== (item.text.length === 0) || !Number.isSafeInteger(item.recordedAt) || item.recordedAt <= 0
    || typeof item.version !== 'string' || !/^sha256:[a-f0-9]{64}$/.test(item.version))
    throw new Error('原文本回执与这条工作记录无法核对。');
  return { text: item.text, bytes: item.bytes, recordedAt: item.recordedAt };
}

export function OriginalWindowsTextRead({ original, documentEpoch, sourceRevision }: { original: Original; documentEpoch: number; sourceRevision: string }) {
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  const alive = useRef(true), sequence = useRef(0), reading = useRef(false);
  const clear = () => { sequence.current++; reading.current = false; setBusy(false); setSnapshot(null); setError(''); };
  useEffect(() => {
    alive.current = true;
    const visibility = () => { if (document.hidden) clear(); };
    document.addEventListener('visibilitychange', visibility);
    return () => { alive.current = false; sequence.current++; document.removeEventListener('visibilitychange', visibility); };
  }, []);
  const current = () => {
    const live = bridge.getState(), view = record(live.view);
    const raw = live.state.tasks?.find((row: {id: string}) => row.id === original.taskId)?.raw, pin = raw?.ownerDeviceWork?.directWorkspace;
    return alive.current && !document.hidden && bridge.getDocumentEpoch() === documentEpoch
      && String(live.state.traceSourceRevision ?? 0) === sourceRevision && !view?.section && view?.taskId === original.taskId
      && raw?.runId === original.runId && raw?.coordinatorEpoch === original.epoch && raw?.contextAvailable !== false
      && raw?.contextWithdrawn !== true && raw?.sourceCurrentReadRequired !== true && raw?.taskProjection !== 'source_unavailable'
      && pin?.deviceId === original.deviceId && pin?.instanceId === original.instanceId && pin?.certificateFingerprint === original.fingerprint;
  };
  const read = async () => {
    if (reading.current || !current()) return;
    reading.current = true; const attempt = ++sequence.current;
    setBusy(true); setSnapshot(null); setError('');
    const eligible = () => sequence.current === attempt && current();
    try {
      const {taskId, runId, epoch, operationId} = original;
      const reply = await bridge.command('devices.queryHistory', { originalWindowsText: true, taskId, runId, epoch, operationId });
      if (eligible()) setSnapshot(snapshotOf(reply, original));
    } catch (failure) {
      if (eligible()) setError(failure instanceof Error ? failure.message : '没有取得可核对的原文本。');
    } finally {
      if (sequence.current === attempt && alive.current) { reading.current = false; setBusy(false); }
    }
  };
  const shown = current() ? snapshot : null;
  return <section className="pw-operation-entry" aria-label="原电脑保存的文本">
    {shown ? <>
      <h4>当时读到的文本</h4>
      <p className="pw-muted">原文件 {shown.bytes} 字节 · 当时保存于 {new Date(shown.recordedAt).toLocaleString('zh-CN')}。不是当前文件。</p>
      {shown.bytes === 0 ? <p>原回执已核对：当时文件为 0 字节。</p> : <pre className="pw-source-copy" dir="auto">{shown.text}</pre>}
      <Button variant="ghost" onClick={clear}>关闭原文本</Button>
    </> : <>
      <Button variant="secondary" disabled={busy} onClick={() => void read()}>{busy ? '正在核对原文本…' : '向原电脑查看当时的文本'}</Button>
      <p className="pw-muted">只查这次留存的回执，不重新读取文件。</p>
      {error && <p role="status">{error}</p>}
    </>}
  </section>;
}
