import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Button, CheckboxField, SettingsDialog } from './forms-library';
import { PressButton } from './library';
import { OwnerWorkspaceRequest } from './commitment-contract';
import { ordinaryReminderReview, ordinaryReminderSaved } from './plan-contract';
import { ReminderCalendarReview } from './ReminderCalendarReview';
import { usePendingCandidateDeadline } from './usePendingCandidateDeadline';
import './knowledge-depth.css';

export type OriginalChatReminderSource = { conversationId: string; messageId: string; sourceRevision: number; taskId: string; runId: string; epoch: number; replyId: string };
export type OriginalChatReminderRulePin = { ruleId: string; ruleRevision: number };
type Props = {
  source: OriginalChatReminderSource;
  isCurrent(): boolean;
  onClose(): void;
  onOpenSavedRule(pin: OriginalChatReminderRulePin): boolean | Promise<boolean>;
  registerBack?: (handler: (() => boolean) | null) => void;
};
type Data = Record<string, any>;
type Phase = 'reading' | 'saving' | 'unknown' | 'unavailable' | 'read_error';
type Pending = {
  status: 'pending'; calendar: NonNullable<ReturnType<typeof ordinaryReminderReview>>; expiresAt: number;
  review: { reviewHandle: string; input: { id: string; digest: string }; plan: { origin: Data; draft: { title: string; schedule: Data } } };
};
type Saved = { status: 'saved'; pin: OriginalChatReminderRulePin };
type View = Pending | Saved | { status: Phase };
type Job = 'read' | 'save' | 'open';
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value);
const revision = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
const record = (value: unknown): Data | null => value && typeof value === 'object' && !Array.isArray(value) ? value as Data : null;
const sameReply = (a: Data | null, b: OriginalChatReminderSource) => !!a && a.conversationId === b.conversationId &&
  a.taskId === b.taskId && a.runId === b.runId && a.epoch === b.epoch && a.replyId === b.replyId;
const sameSource = (a: OriginalChatReminderSource, b: OriginalChatReminderSource) => a.messageId === b.messageId &&
  a.sourceRevision === b.sourceRevision && sameReply(a, b);

function readOriginalReminder(value: unknown, source: OriginalChatReminderSource, beganAt: number): View {
  const result = record(value);
  if (!result || result.messageId !== source.messageId || result.sourceRevision !== source.sourceRevision || result.executionAuthorized !== false)
    return { status: 'read_error' };
  if (result.status === 'unavailable' && !Object.hasOwn(result, 'source') && !Object.hasOwn(result, 'review') &&
      !Object.hasOwn(result, 'ruleId') && !Object.hasOwn(result, 'ruleRevision')) return { status: 'unavailable' };
  if (!sameReply(record(result.source), source)) return { status: 'read_error' };
  if (result.status === 'saved' && uuid(result.ruleId) && revision(result.ruleRevision) && !Object.hasOwn(result, 'review'))
    return { status: 'saved', pin: { ruleId: result.ruleId, ruleRevision: result.ruleRevision } };
  if (result.status === 'pending' && !Object.hasOwn(result, 'ruleId') && !Object.hasOwn(result, 'ruleRevision')) {
    const review = record(result.review), origin = review?.plan?.origin;
    const calendar = ordinaryReminderReview(review);
    if (review && calendar && !calendar.checkOnly && uuid(review.reviewHandle) && uuid(review.input?.id) &&
        revision(review.expiresInMs) && review.expiresInMs <= 120_000 && origin?.ownerId === 'owner' && origin.spaceId === 'private' &&
        origin.conversationId === source.conversationId && origin.taskId === source.taskId && origin.runId === source.runId && origin.epoch === source.epoch) {
      const { ownerId, spaceId, conversationId, taskId, runId, epoch } = origin;
      return { status: 'pending', calendar, expiresAt: beganAt + review.expiresInMs, review: {
        reviewHandle: review.reviewHandle, input: { id: review.input.id, digest: review.input.digest },
        plan: { origin: { ownerId, spaceId, conversationId, taskId, runId, epoch }, draft: { title: calendar.title, schedule: review.plan.draft.schedule } },
      } };
    }
  }
  return { status: result.status === 'unknown' || result.status === 'unavailable' ? result.status : 'read_error' };
}

/** One original answer, mounted only after its explicit More action. No task or scheduler state is inferred. */
export function OriginalChatReminderReview(props: Props) {
  const origin = useRef({ source: props.source, documentEpoch: bridge.getDocumentEpoch() }).current, latest = useRef(props);
  const scope = useRef(new OwnerWorkspaceRequest()), alive = useRef(false), job = useRef<Job | null>(null);
  const expected = useRef<{ id: string; digest?: string } | null>(null);
  const [view, setView] = useState<View>({ status: 'reading' });
  const [checked, setChecked] = useState(false), [retired, setRetired] = useState(false);
  const [opening, setOpening] = useState(false), [openFailed, setOpenFailed] = useState(false);
  const [saveUncertain, setSaveUncertain] = useState(false);
  const live = ({ source, isCurrent }: Props = latest.current) => !document.hidden &&
    bridge.getDocumentEpoch() === origin.documentEpoch && sameSource(origin.source, source) && isCurrent();
  const current = () => alive.current && live();
  const retire = () => {
    if (!alive.current) return;
    alive.current = false; scope.current.invalidate(false); job.current = null;
    setRetired(true); setView({ status: 'unavailable' }); setChecked(false);
  };
  const close = () => {
    if (!current()) return false;
    if (job.current !== 'save' && job.current !== 'open') { retire(); latest.current.onClose(); }
    return true;
  };
  useLayoutEffect(() => { latest.current = props; if (!live(props)) retire(); });
  useLayoutEffect(() => {
    alive.current = true;
    const check = () => { if (!live()) retire(); };
    const off = bridge.subscribe(check), offDocument = bridge.subscribeDocumentEpoch(check);
    document.addEventListener('visibilitychange', check); window.addEventListener('pagehide', retire);
    check();
    return () => {
      alive.current = false; scope.current.invalidate(false); job.current = null;
      off(); offDocument(); document.removeEventListener('visibilitychange', check); window.removeEventListener('pagehide', retire);
    };
  }, []);
  useLayoutEffect(() => {
    props.registerBack?.(close);
    return () => props.registerBack?.(null);
  }, [props.registerBack]);

  const request = async (kind: Job, action: () => Promise<unknown>, accept: (value: unknown) => void, uncertain: () => void) => {
    if (!current() || job.current) return;
    const ticket = scope.current.begin(); if (ticket === null) return;
    job.current = kind;
    try { const value = await action(); if (current() && scope.current.owns(ticket)) accept(value); }
    catch { if (current() && scope.current.owns(ticket)) uncertain(); }
    finally { if (scope.current.finish(ticket)) job.current = null; }
  };
  const read = () => {
    const beganAt = Date.now();
    void request('read', () => {
      setView({ status: 'reading' }); setChecked(false); setOpenFailed(false);
      return bridge.command('plans.reviewSchedule', { messageId: origin.source.messageId, sourceRevision: origin.source.sourceRevision });
    }, value => {
      const actual = readOriginalReminder(value, origin.source, beganAt), prev = expected.current;
      if (actual.status === 'pending') {
        const { id, digest } = actual.review.input;
        if (prev && (prev.id !== id || prev.digest !== undefined && prev.digest !== digest)) { setView({ status: 'read_error' }); return; }
        expected.current = { id, digest }; setSaveUncertain(false);
      } else if (actual.status === 'saved') {
        if (prev && prev.id !== actual.pin.ruleId) { setView({ status: 'read_error' }); return; }
        expected.current = prev || { id: actual.pin.ruleId }; setSaveUncertain(false);
      }
      setView(actual);
    }, () => setView({ status: 'read_error' }));
  };
  // App mounts this keyed source only on explicit owner selection. No passive per-message reads.
  useEffect(() => { read(); }, []);

  const deadline = view.status === 'pending' && view.calendar.times.length > 0
    ? Math.min(view.expiresAt, view.calendar.times[0].at) : 0;
  const expired = usePendingCandidateDeadline(deadline);
  const confirm = () => {
    if (view.status !== 'pending' || !checked || !deadline || Date.now() >= deadline || !current()) return;
    const held = view.review;
    const unconfirmed = () => { setView({ status: 'unknown' }); setSaveUncertain(true); };
    void request('save', () => {
      setView({ status: 'saving' }); setChecked(false);
      return bridge.command('plans.confirm', { reviewHandle: held.reviewHandle, originalChatReminder: true });
    }, value => {
      const actual = readOriginalReminder(value, origin.source, 0), receipt = record(value);
      if (actual.status !== 'saved' || receipt?.confirmed !== true || receipt.command !== 'approveSchedule' ||
          actual.pin.ruleId !== held.input.id || actual.pin.ruleRevision !== 1 || !ordinaryReminderSaved(receipt, held)) { unconfirmed(); return; }
      setView(actual); setSaveUncertain(false);
    }, unconfirmed);
  };
  const openSaved = (pin: OriginalChatReminderRulePin) => {
    const unavailable = () => { setOpening(false); setOpenFailed(true); };
    void request('open', async () => {
      setOpening(true); setOpenFailed(false);
      return latest.current.onOpenSavedRule(pin);
    }, opened => { if (opened === true) setOpening(false); else unavailable(); }, unavailable);
  };

  if (retired || !live(props)) return null;
  const retry = view.status === 'unknown' || view.status === 'unavailable' || view.status === 'read_error' || expired || openFailed;
  return <SettingsDialog isOpen title={view.status === 'saved' ? '这条提醒' : '核对这次提醒'} closeLabel="返回原对话" onClose={close}>
    <div className="pw-knowledge-dialog pw-knowledge-review">
      {view.status === 'reading' && <p role="status">正在核对这条回复的原提醒…</p>}
      {view.status === 'saving' && <p role="status">正在确认这次保存…</p>}
      {view.status === 'pending' && <>
        <ReminderCalendarReview original={view.calendar} />
        {expired ? <p role="status">这次时间核对已过期，请重新读取原提醒。</p> : deadline > 0 && <>
          <CheckboxField isSelected={checked} onChange={setChecked}>我已核对提醒内容、时间与时区</CheckboxField>
          <div className="pw-form-actions"><PressButton compact disabled={!checked} onClick={confirm}>确认保存提醒</PressButton></div>
        </>}
      </>}
      {view.status === 'saved' && <>
        <p role="status">这条提醒已保存到这台手机。</p>
        {!openFailed && <div className="pw-form-actions"><Button variant="secondary" disabled={opening} onClick={() => openSaved(view.pin)}>{opening ? '正在定位原提醒…' : '查看这条提醒'}</Button></div>}
      </>}
      {(view.status === 'unknown' || saveUncertain) && <p role="status">原提醒的保存结果尚未核对。只重新读取这份原记录，不重复新建。</p>}
      {!saveUncertain && view.status === 'unavailable' && <p role="status">暂未在这条回复核对到可用的原提醒。</p>}
      {!saveUncertain && view.status === 'read_error' && <p role="alert">未能读取这条回复的原提醒，没有打开其他提醒。</p>}
      {openFailed && <p role="status">未能定位这条已保存的提醒。请重新核对原记录。</p>}
      {retry && <div className="pw-form-actions"><Button variant="secondary" onClick={read}>重新核对原提醒</Button></div>}
    </div>
  </SettingsDialog>;
}
