import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { bridge, type PocketSnapshot } from './bridge';
import { archivedPhoneAcceptanceLine, isSourcePhoneSendPhase, type SourcePhoneSendPhase, type SentPhoneGoalFocus } from './devices-workspace';
import { Button } from './forms-library';
import { taskAuditPage, type AuditPage } from './task-history';
import { usePocketSnapshot } from './usePocketSnapshot';

type Fact = { createdAt: number; targetAcceptedAt?: number; targetRunRecorded?: true; sourceSendPhase?: SourcePhoneSendPhase };
type Read = { capturedAt: number; fact?: Fact };
const positiveTime = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
const time = (value: number) => new Date(value).toLocaleString();
const nativeId = (value: unknown): value is string => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(value);
const digest = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const isDevice = (value: unknown): value is string => typeof value === 'string' && /^pw1_[a-f0-9]{64}$/.test(value);
type Data = Record<string, any>;
type ResultRead = { runId: string; epoch: number; operationId: string; child: string; childRun: string; childEpoch: number;
  stamp: string };
type Visit = { pin: ResultRead; client: string; document: number; began: number };
type Outcome = { summary: string; label: string; until: number; untilMono: number };

/** Show one next action only from the already loaded native A row and actual B acceptance.
 * The native command still selects the unique SQL anchor and checks all identities independently. */
function offeredRead(snapshot: PocketSnapshot, source: SentPhoneGoalFocus): ResultRead | undefined {
  const { state, capabilities } = snapshot, view = snapshot.view as Data | undefined;
  if (view?.section !== null || view.taskId !== source.taskId || !Number.isSafeInteger(view.resumeRevision) ||
    !Number.isSafeInteger(state.deviceWorkspaceRevision) || state.conversationReader || state.queuedInputsPage ||
    state.attachmentWorkspace?.open || state.chatAttachmentPickerOpen || state.filePath ||
    !capabilities.commands.includes('devices.queryPhoneGoalResult') || !capabilities.commands.includes('devices.discard')) return;
  const rows = Array.isArray(state.tasks) ? state.tasks.filter(task => task?.id === source.taskId) : [];
  if (rows.length !== 1) return;
  const raw = rows[0].raw, work = raw?.ownerDeviceWork, target = work?.directPhone;
  const runId = raw?.runId, epoch = raw?.coordinatorEpoch;
  if (!raw || raw.id !== source.taskId || raw.actorId !== 'owner' || raw.spaceId !== 'private' || raw.origin != null ||
    raw.parentTaskId != null || raw.status !== rows[0].status || raw.kind !== 'assistant' || raw.executionMode !== 'capabilities' ||
    raw.conversationId !== '' || raw.ownerPhoneControl != null || raw.contextAvailable === false || raw.contextWithdrawn === true ||
    raw.sourceCurrentReadRequired === true || raw.taskProjection !== 'detail_window' || raw.taskCoreReadLimited !== false ||
    work?.format !== 'pocketworld.owner-device-work.v1' || work.requestId !== source.taskId || !digest(work.requestDigest) ||
    work.directWorkspace != null || target?.deviceId !== source.deviceId || target.instanceId !== source.instanceId ||
    target.certificateFingerprint !== source.certificateFingerprint || !nativeId(runId) || !positiveTime(epoch)) return;
  let page: AuditPage;
  try { page = taskAuditPage(raw.historyOperationPage, source.taskId, 'operations'); } catch { return; }
  if (page.completeHistory !== true || page.readLimited !== false || page.hasMore || page.entries[0]?.position !== page.upperPosition) return;
  const sends = page.entries.filter(op => op.tool === 'device_delegate_goal' && op.runId === runId);
  const op = sends.length === 1 ? sends[0] : undefined, operationId = op?.operationId;
  const send = op?.receipt?.nativeDeviceSend, accepted = op?.receipt?.nativeDeviceAcceptedBinding;
  const actual = accepted?.verifiedReceipt, audience = actual?.audience, resource = audience?.resourceBinding;
  if (!nativeId(operationId) || op?.status !== 'available' || !['unknown','receipt'].includes(op.state) ||
    op.arguments?.targetDeviceId !== source.deviceId || send?.format !== 'pocketworld.native-device-send.v1' ||
    send.state !== 'write_claimed' || !positiveTime(send.claimedAt) || !digest(send.wireSha256) ||
    !nativeId(send.allocationId) || accepted?.accepted !== true || accepted.acceptedObjectKind !== 'task' ||
    !nativeId(accepted.localTaskId) || accepted.localTaskId !== accepted.objectId || !nativeId(accepted.runId) || !positiveTime(accepted.executorEpoch) ||
    accepted.parentTaskId !== source.taskId || accepted.sourceOperationId !== operationId || accepted.parentOperationId !== operationId ||
    accepted.targetOperationId !== operationId || accepted.executorDeviceId !== source.deviceId || accepted.instanceId !== source.instanceId ||
    !digest(accepted.operationArgumentsDigest) || actual?.messageType !== 'OperationReceipt' || actual.accepted !== true ||
    actual.operationId !== operationId || actual.localTaskId !== accepted.localTaskId || actual.argumentsDigest !== accepted.operationArgumentsDigest ||
    actual.runId !== accepted.runId || actual.executorEpoch !== accepted.executorEpoch || audience?.taskId !== accepted.localTaskId ||
    audience?.runId !== accepted.runId || audience?.executorEpoch !== accepted.executorEpoch || audience?.rootTaskId !== source.taskId || audience.localTaskId !== send.allocationId || audience.targetDeviceId !== source.deviceId ||
    audience.instanceId !== source.instanceId || audience.requestRunId !== runId || audience.requestExecutorEpoch !== epoch ||
    !isDevice(audience.callerDeviceId) || audience.callerDeviceId !== audience.originDeviceId || audience.sourceShareId != null ||
    resource?.screenMode !== 'capabilities' || Object.keys(resource).length !== 1) return;
  const stamp = JSON.stringify([source.taskId, runId, epoch, operationId, source.deviceId, source.instanceId, source.certificateFingerprint,
    work.requestDigest, accepted.localTaskId, accepted.runId, accepted.executorEpoch, send.wireSha256, view.resumeRevision, state.deviceWorkspaceRevision]);
  return { runId, epoch, operationId, child: accepted.localTaskId, childRun: accepted.runId, childEpoch: accepted.executorEpoch, stamp };
}

function outcome(value: Data, source: SentPhoneGoalFocus, selected: Visit): Outcome {
  const { pin, began } = selected, result = value?.result;
  if (value?.kind !== 'originalSentPhoneTextResult' || value.taskId !== source.taskId || value.runId !== pin.runId ||
    value.epoch !== pin.epoch || value.operationId !== pin.operationId || value.targetDeviceId !== source.deviceId ||
    result?.readOnly !== true || result.taskId !== pin.child || result.runId !== pin.childRun ||
    result.executorEpoch !== pin.childEpoch || result.instanceId !== source.instanceId || !positiveTime(result.updatedAt) ||
    !['confirmed','pending','none','not_confirmed','unverified','unavailable'].includes(result.review) ||
    typeof result.summary !== 'string' || [...result.summary].length > 2048 || typeof result.truncated !== 'boolean' ||
    result.review !== 'confirmed' && (result.summary || result.truncated || result.summaryWithheld != null) ||
    result.review === 'confirmed' && result.state !== 'succeeded' ||
    result.summaryWithheld != null && (result.summaryWithheld !== true || result.summary || result.truncated))
    throw new Error('没有核对到同一次接手执行的成果。');
  const summary = result.summary;
  let until = 0, untilMono = 0;
  if (summary.length) {
    if (!positiveTime(result.textReadUntil) || !positiveTime(result.textReadRemainingMs) ||
      result.textTargetFingerprint !== source.certificateFingerprint) throw new Error('原文字的读取时间已结束。');
    const now = performance.now(), left = Math.min(result.textReadUntil - Date.now(), result.textReadRemainingMs, 5000 - (now - began));
    if (left <= 0) throw new Error('原文字的读取时间已结束。');
    until = result.textReadUntil; untilMono = now + left;
  }
  const label = result.review === 'confirmed' ? result.summaryWithheld ? '原机主已确认完成，文字留在原手机。'
    : summary.trim() ? (result.truncated ? '原机主已确认。只显示摘要前 2,048 字。' : '原机主已确认。') : '原机主已确认，未提供可显示的文字摘要。'
    : result.review === 'pending' ? '原手机报告成果待机主核对，还没有返回文字。'
    : result.review === 'not_confirmed' ? '原机主没有确认这份成果。' : '这次未取得原机主确认的成果，不能据此判断没有成果。';
  return { label, summary, until, untilMono };
}

function dispatchFact(value: any, source: SentPhoneGoalFocus): Read {
  if (value?.kind !== 'originalPhoneDispatchFact' || value.readOnly !== true || value.taskId !== source.taskId || !positiveTime(value.capturedAt)
    || value.target?.deviceId !== source.deviceId || value.target.instanceId !== source.instanceId || value.target.certificateFingerprint !== source.certificateFingerprint)
    throw new Error('本机留档与这份工作选定的手机不同。');
  const fact = value.fact;
  if (fact !== undefined && (!fact || !positiveTime(fact.createdAt) || fact.targetAcceptedAt !== undefined && !positiveTime(fact.targetAcceptedAt)
    || fact.targetRunRecorded !== undefined && (fact.targetRunRecorded !== true || !positiveTime(fact.targetAcceptedAt))
    || fact.sourceSendPhase !== undefined && (fact.targetAcceptedAt !== undefined || !isSourcePhoneSendPhase(fact.sourceSendPhase))))
    throw new Error('本机未取得可核对的派单事实。');
  return { capturedAt: value.capturedAt, fact };
}

/** Private summary of this work's own saved sends, never a poll or a partner's task/result reader. */
export function OriginalPhoneDispatchStatus({ source, canRead, onOpen, registerResultBack }: {
  source: SentPhoneGoalFocus; canRead: boolean; onOpen: () => void; registerResultBack: (back: (() => boolean) | null) => void;
}) {
  const { taskId, deviceId, instanceId, certificateFingerprint } = source;
  const [read, setRead] = useState<Read | null>(null), [pending, setPending] = useState(false), [unavailable, setUnavailable] = useState(false);
  const [revision, refresh] = useState(0);
  const snapshot = usePocketSnapshot(), offer = offeredRead(snapshot, source);
  const visit = useRef<Visit | null>(null), backFocus = useRef<HTMLButtonElement>(null), heading = useRef<HTMLHeadingElement>(null);
  const [result, setResult] = useState<{ visit: Visit; reading?: true; value?: Outcome; message?: string }>();
  const current = (original: Visit) => !document.hidden && bridge.getDocumentEpoch() === original.document &&
    offeredRead(bridge.getState(), source)?.stamp === original.pin.stamp;
  const retire = () => {
    const old = visit.current;
    visit.current = null;
    if (old) void bridge.command('devices.discard', { originalSentPhoneText: true, clientRequest: old.client }).catch(() => {});
  };
  const close = (focus = false) => {
    retire(); setResult(undefined);
    if (focus) requestAnimationFrame(() => { if (backFocus.current?.isConnected && !document.hidden) backFocus.current.focus({ preventScroll: true }); });
  };
  useEffect(() => {
    const check = () => { if (visit.current && !current(visit.current)) close(); };
    const hide = () => close();
    const stop = bridge.subscribe(check), stopDocument = bridge.subscribeDocumentEpoch(check);
    document.addEventListener('visibilitychange', check); window.addEventListener('pagehide', hide);
    return () => { stop(); stopDocument(); document.removeEventListener('visibilitychange', check); window.removeEventListener('pagehide', hide); retire(); };
  }, [taskId, deviceId, instanceId, certificateFingerprint]);
  const view = result && visit.current === result.visit && current(result.visit) ? result : undefined;
  useLayoutEffect(() => {
    // Cover Back between the explicit click and the first React paint too.
    registerResultBack(() => {
      if (!visit.current || !current(visit.current)) return false;
      close(true); return true;
    });
    return () => registerResultBack(null);
  }, [registerResultBack, taskId, deviceId, instanceId, certificateFingerprint]);
  useLayoutEffect(() => { if (view && !view.reading) heading.current?.focus({ preventScroll: true }); }, [view]);
  useEffect(() => {
    const value = view?.value;
    if (!value?.until || !view) return;
    const left = Math.min(value.until - Date.now(), value.untilMono - performance.now());
    const timer = window.setTimeout(() => {
      if (visit.current === view.visit) setResult({ visit: view.visit, message: '本次文字查看已结束，未在本机留存。' });
    }, Math.max(0, left));
    return () => window.clearTimeout(timer);
  }, [view]);
  const readResult = () => {
    if (visit.current || !offer) return;
    const client = Array.from(crypto.getRandomValues(new Uint8Array(16)), n => n.toString(16).padStart(2, '0')).join('');
    const selected = { pin: offer, client, document: bridge.getDocumentEpoch(), began: performance.now() };
    if (!current(selected)) return;
    visit.current = selected; setResult({ visit: selected, reading: true });
    void bridge.command<Data>('devices.queryPhoneGoalResult', { originalSentPhoneText: true, taskId, runId: offer.runId,
      epoch: offer.epoch, operationId: offer.operationId, childTaskId: offer.child, childRunId: offer.childRun,
      childEpoch: offer.childEpoch, clientRequest: client }).then(value => {
      if (visit.current === selected && current(selected)) setResult({ visit: selected, value: outcome(value, source, selected) });
    }).catch(failure => {
      if (visit.current === selected && current(selected)) setResult({ visit: selected,
        message: failure instanceof Error ? failure.message : '这次没有读到可核对的原结果。' });
    });
  };
  useEffect(() => {
    let active = true, sequence = 0;
    const clear = () => { sequence++; setRead(null); setPending(false); setUnavailable(false); };
    const readLocal = () => {
      clear();
      if (!canRead || document.hidden) return;
      const token = sequence; setPending(true);
      const current = () => active && token === sequence && !document.hidden;
      void bridge.command('deviceTaskScope.readPhoneDispatchFact', { taskId }).then(value => {
        if (current()) setRead(dispatchFact(value, { taskId, deviceId, instanceId, certificateFingerprint }));
      }).catch(() => { if (current()) setUnavailable(true); }).finally(() => { if (current()) setPending(false); });
    };
    readLocal();
    document.addEventListener('visibilitychange', readLocal); window.addEventListener('pagehide', clear);
    return () => { active = false; document.removeEventListener('visibilitychange', readLocal); window.removeEventListener('pagehide', clear); };
  }, [taskId, deviceId, instanceId, certificateFingerprint, canRead, revision]);
  return <div aria-label="原手机派单">
    <p role="status">{pending ? '正在核对本机派单记录。' : unavailable ? '本机暂未核对到这份工作的原派单。'
      : read?.fact ? `本机最近留档：${archivedPhoneAcceptanceLine(read.fact, time)}${read.fact.targetAcceptedAt ? '不是机主已读或成果回执。' : ''}`
        : read ? '本机这次未找到可核对的派单记录，不能据此判断是否送达。' : '本机工作进展不代表原手机已接单。'}</p>
    <div className="pw-form-actions">
      {offer && <Button ref={backFocus} variant="secondary" disabled={!!view} onClick={readResult}>从原手机读已确认结果</Button>}
      <Button variant={offer ? 'ghost' : undefined} onClick={() => { if (visit.current) close(); onOpen(); }}>{offer ? '查看原派单' : '查看原派单与结果'}</Button>
      {canRead && <Button variant="ghost" disabled={pending} onClick={() => refresh(value => value + 1)}>重读本机记录</Button>}
    </div>
    {view && <section className="pw-outcome-document" aria-label="这次原手机的文字成果">
      <h3 ref={heading} tabIndex={-1}>原手机这次成果</h3>
      <p role="status">{view.reading ? '正在向原手机读取，只核对这一次。' : view.message || view.value?.label}</p>
      {view.value?.summary.trim() && Date.now() < view.value.until && performance.now() < view.value.untilMono &&
        <p className="pw-source-copy" dir="auto" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{view.value.summary}</p>}
      <Button variant="ghost" onClick={() => close(true)}>返回原工作</Button>
    </section>}
  </div>;
}
