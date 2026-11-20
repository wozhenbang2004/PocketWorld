import { useEffect, useState } from 'react';
import { bridge } from './bridge';
import { archivedPhoneAcceptanceLine, isSourcePhoneSendPhase, type SourcePhoneSendPhase, type SentPhoneGoalFocus } from './devices-workspace';
import { Button } from './forms-library';

type Fact = { createdAt: number; targetAcceptedAt?: number; targetRunRecorded?: true; sourceSendPhase?: SourcePhoneSendPhase };
type Read = { capturedAt: number; fact?: Fact };
const positiveTime = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
const time = (value: number) => new Date(value).toLocaleString();

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
export function OriginalPhoneDispatchStatus({ source, canRead, onOpen }: { source: SentPhoneGoalFocus; canRead: boolean; onOpen: () => void }) {
  const { taskId, deviceId, instanceId, certificateFingerprint } = source;
  const [read, setRead] = useState<Read | null>(null), [pending, setPending] = useState(false), [unavailable, setUnavailable] = useState(false);
  const [revision, refresh] = useState(0);
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
      <Button onClick={onOpen}>查看原派单与结果</Button>
      {canRead && <Button variant="ghost" disabled={pending} onClick={() => refresh(value => value + 1)}>重读本机记录</Button>}
    </div>
  </div>;
}
