import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { IconClose, IconUndo } from './icons';
import { bridge } from './bridge';
import { Drawer } from './shell-library';
import './knowledge-depth.css';
import './memory-draft-review.css';
import { Accordion, AccordionItem, Button, IconButton } from './forms-library';
import { ShimmerText } from './library';
import type { ConversationDraftTarget } from './SkillDraftReview';
import { MemoryAssertionFields } from './MemoryAssertionFields';
import { ScopedMemoryEvidence } from './ScopedMemoryEvidence';
import { OriginalSavedMemory, originalMemoryWindow } from './OriginalSavedMemory';
import { usePendingCandidateDeadline } from './usePendingCandidateDeadline';

type Data = Record<string, any>;
type FocusedSavedMemory = { id: string; revision: number; duplicate: boolean; windowKey: string;
  undo?: { operation: ConversationDraftTarget; candidateId: string } };
type SavedElsewhere = { readExpiresAt: number } & (
  { status: 'same_version'; id: string; revision: 1; text: string } |
  { status: 'changed'; revision: number } | { status: 'past_only' });
export type SavedMemoryConversationPin = ConversationDraftTarget & { conversationId: string };
export type OriginalMemoryDraftReply = Pick<ConversationDraftTarget, 'taskId' | 'runId' | 'epoch'> & { messageId: string; sourceRevision: 1 };
type MemoryDraftProps = ({ target: ConversationDraftTarget; originalReply?: never; isCurrent?: never } |
  { target?: never; originalReply: OriginalMemoryDraftReply; isCurrent: () => boolean }) & {
  onClose: () => void;
  onOpenMemory?: (target: { id: string; candidate: boolean; expectedRevision?: number }) => void;
  originalWorkReturn?: (pin: SavedMemoryConversationPin) => { open?: () => void } | undefined;
};
function originalReplyTarget(value: unknown, reply: OriginalMemoryDraftReply): ConversationDraftTarget | null {
  if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).length !== 5) return null;
  const row = value as Record<string, unknown>;
  return row.taskId === reply.taskId && row.runId === reply.runId && row.epoch === reply.epoch &&
    typeof row.operationId === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(row.operationId) &&
    typeof row.proposalDigest === 'string' && /^[a-f0-9]{64}$/.test(row.proposalDigest)
    ? { taskId: reply.taskId, runId: reply.runId, epoch: reply.epoch, operationId: row.operationId, proposalDigest: row.proposalDigest } : null;
}
function savedElsewhereResult(value: unknown): SavedElsewhere | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const row = value as Record<string, unknown>, expiry = row.readExpiresAt;
  const n = Object.keys(row).length;
  if (row.format !== 'pocketworld.original-conversation-memory-acceptance.v1' || row.executionAuthorized !== false ||
      row.replayAllowed !== false || typeof expiry !== 'number' || !Number.isSafeInteger(expiry) || expiry <= Date.now()) return null;
  if (n === 5 && row.status === 'past_only') return { status: 'past_only', readExpiresAt: expiry };
  if (n === 6 && row.status === 'changed' && typeof row.revision === 'number' && Number.isSafeInteger(row.revision) && row.revision > 1)
    return { status: 'changed', revision: row.revision, readExpiresAt: expiry };
  if (n === 8 && row.status === 'same_version' && row.revision === 1 && typeof row.id === 'string' &&
      /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(row.id) && typeof row.text === 'string' && row.text.trim() && row.text.length <= 4000)
    return { status: 'same_version', id: row.id, revision: 1, text: row.text, readExpiresAt: expiry };
  return null;
}
const proposalWindow = () => {
  const s = bridge.getState().state;
  return JSON.stringify([bridge.getDocumentEpoch(), s.conversationId, s.memoryRefreshRevision, s.traceSourceRevision]);
};
export function MemoryDraftReview({ target, originalReply, isCurrent, onClose, onOpenMemory, originalWorkReturn }: MemoryDraftProps) {
  const [value, setValue] = useState<Data | null>(null), [reviewClosed, setReviewClosed] = useState(false);
  const [adopted, setAdopted] = useState<{ row: SavedElsewhere; window: string } | null>(null);
  const [resolved, setResolved] = useState<ConversationDraftTarget | null>(null);
  const [error, setError] = useState(''), [busy, setBusy] = useState(true), [attempted, setAttempted] = useState(false), [undoUncertain, setUndoUncertain] = useState(false);
  const [focused, setFocused] = useState<FocusedSavedMemory | null>(null), [undoFromRecord, setUndoFromRecord] = useState(false);
  const handle = useRef(''), alive = useRef(true), entered = useRef(false);
  const selected = target || resolved;
  const original = selected && { taskId: selected.taskId, runId: selected.runId, epoch: selected.epoch, operationId: selected.operationId, proposalDigest: selected.proposalDigest };
  const openInput = originalReply ? { messageId: originalReply.messageId, sourceRevision: originalReply.sourceRevision } : original;
  const sameReply = () => !originalReply || isCurrent?.() === true;
  const acceptOpen = (result: Data) => {
    if (originalReply) {
      const next = originalReplyTarget(result.originalTarget, originalReply);
      if (!next || original && (original.operationId !== next.operationId || original.proposalDigest !== next.proposalDigest)) {
        if (result.handle) void bridge.command('memoryDraft.close', { handle: result.handle }).catch(() => {});
        throw new Error('没有核对到这条回答的原记忆提案，未打开其他提案。');
      }
      setResolved(next);
    }
    if (result.savedElsewhere !== undefined) {
      const accepted = savedElsewhereResult(result.savedElsewhere);
      if (!originalReply || !accepted || result.handle !== undefined || result.review !== undefined)
        throw new Error('没有核对到这条提案对应的个人记忆。');
      handle.current = ''; setValue(null); setAdopted({ row: accepted, window: proposalWindow() });
      return;
    }
    setAdopted(null);
    handle.current = result.handle || ''; setValue(result);
  };
  useEffect(() => {
    alive.current = true;
    if (!openInput || !sameReply()) { setBusy(false); return; }
    void bridge.command('memoryDraft.open', openInput).then(result => {
      if (alive.current && sameReply()) acceptOpen(result);
      else if (result.handle) void bridge.command('memoryDraft.close', { handle: result.handle }).catch(() => {});
    }).catch(failure => { if (alive.current && sameReply()) setError(failure.message); }).finally(() => { if (alive.current) setBusy(false); });
    return () => { alive.current = false; const id = handle.current; handle.current = ''; if (id) void bridge.command('memoryDraft.close', { handle: id }).catch(() => {}); };
  }, []);
  const request = async (name: string, args: Data, accept: (result: Data) => void, uncertain?: () => void) => {
    if (entered.current || busy || !sameReply()) return;
    entered.current = true; setBusy(true); setError('');
    try {
      const result = await bridge.command(name, args);
      if (alive.current && sameReply()) accept(result);
      else if (name === 'memoryDraft.open' && result.handle) void bridge.command('memoryDraft.close', { handle: result.handle }).catch(() => {});
    } catch (failure) {
      if (alive.current && sameReply()) { uncertain?.(); setError(failure instanceof Error ? failure.message : String(failure)); }
    } finally { entered.current = false; if (alive.current) setBusy(false); }
  };
  const review = value?.review, proposal = value?.proposal || review?.proposal || {}, apply = value?.apply || (value?.status ? value : {});
  const adoptedCurrent = () => !!adopted && sameReply() && !document.hidden && adopted.window === proposalWindow() && Date.now() < adopted.row.readExpiresAt;
  const savedInProfile = adoptedCurrent() ? adopted?.row : null;
  useLayoutEffect(() => {
    if (!adopted) return;
    const retire = () => { setAdopted(null); setReviewClosed(true); };
    const recheck = () => { if (!adoptedCurrent()) retire(); };
    const off = bridge.subscribe(recheck);
    const timer = window.setTimeout(retire, Math.max(0, adopted.row.readExpiresAt - Date.now()));
    document.addEventListener('visibilitychange', recheck); window.addEventListener('pagehide', retire); recheck();
    return () => { off(); clearTimeout(timer); document.removeEventListener('visibilitychange', recheck); window.removeEventListener('pagehide', retire); };
  }, [adopted]);
  useLayoutEffect(() => {
    const id = handle.current;
    if (!id || !review) return;
    const originalWindow = proposalWindow(), deadline = review.expiresAt;
    const retire = () => {
      if (handle.current !== id) return; // Never reinterpret an already-submitted mutation.
      handle.current = ''; setValue(null); setReviewClosed(true); setError('');
      void bridge.command('memoryDraft.close', { handle: id }).catch(() => {});
    };
    const hide = () => { if (document.hidden) retire(); };
    const off = bridge.subscribe(() => { if (proposalWindow() !== originalWindow) retire(); });
    const left = Number.isSafeInteger(deadline) ? deadline - Date.now() : 0;
    const timer = left > 0 ? window.setTimeout(retire, left) : undefined;
    if (left <= 0 || document.hidden) retire();
    document.addEventListener('visibilitychange', hide); window.addEventListener('pagehide', retire);
    return () => { off(); if (timer !== undefined) clearTimeout(timer); document.removeEventListener('visibilitychange', hide); window.removeEventListener('pagehide', retire); };
  }, [review]);
  const outcomeStatus = value?.status || apply.status;
  const pending = outcomeStatus === 'pending_owner' && !!review && !!handle.current && !attempted;
  const dismissOnly = review?.dismissOnly === true;
  // A fresh native query must settle an uncertain attempt before the original review can reopen.
  const canReopen = outcomeStatus === 'pending_owner' && !review && !handle.current;
  const action = proposal.action || review?.action;
  const assertion = review?.after?.assertion || (action === 'forget' ? review?.before?.assertion : null);
  const originalRecord = value?.receipt?.record || apply.record || apply.receipt?.record;
  const candidate = value?.existingCandidate;
  const undoAvailable = (value?.undoAvailable ?? value?.receipt?.undoAvailable ?? apply.undoAvailable ?? apply.receipt?.undoAvailable) === true;
  const undoReason = value?.undoUnavailableReason || value?.receipt?.undoUnavailableReason || apply.undoUnavailableReason || apply.receipt?.undoUnavailableReason;
  const outcomeMessage = value?.undo?.message || value?.message || apply.message;
  const saved = outcomeStatus === 'saved', reverted = outcomeStatus === 'reverted', duplicate = outcomeStatus === 'already_saved';
  const undoPending = undoUncertain || ['unknown', 'undoing_unknown'].includes(value?.undo?.status);
  const receiptRecord = duplicate ? (value?.receipt?.existingRecord || apply.existingRecord) : originalRecord;
  const displayedRecord = undoFromRecord ? null : receiptRecord || (duplicate ? candidate : null);
  const canReadSaved = (saved || duplicate) && action !== 'forget' && typeof receiptRecord?.id === 'string' &&
    Number.isSafeInteger(receiptRecord.revision) && receiptRecord.revision > 0;
  const savedReceipt = value?.receipt || apply.receipt || apply;
  const savedCandidateId = proposal.candidate?.id;
  // Carry only the original creation/operation locator into the exact read; not its old body.
  const focusedUndo = originalReply && original && saved && !duplicate && action === 'create' && undoAvailable && !undoPending &&
    value?.queryOriginalReceipt !== true && savedReceipt.queryOriginalReceipt !== true && savedReceipt.journalRecorded !== false &&
    proposal.format === 'pocketworld.conversation-memory-draft.v1' && savedReceipt.action === 'create' && savedReceipt.status === 'saved' &&
    savedReceipt.accepted === true && savedReceipt.mutationApplied === true && savedReceipt.formalMemorySaved === true &&
    savedReceipt.executionAuthorized === false && receiptRecord?.ownerId === 'owner' && receiptRecord.spaceId === 'private' &&
    receiptRecord.formation === 'reviewed_learning' && receiptRecord.revision === 1 && savedReceipt.record?.id === receiptRecord.id &&
    typeof receiptRecord.id === 'string' && /^[a-f0-9-]{36}$/.test(receiptRecord.id) &&
    typeof savedCandidateId === 'string' && /^[a-f0-9-]{36}$/.test(savedCandidateId) &&
    savedReceipt.record.revision === 1 && savedReceipt.record.learningCandidateId === savedCandidateId &&
    Object.entries(original).every(([key, field]) => proposal[key] === field && savedReceipt[key] === field)
    ? { operation: original, candidateId: savedCandidateId } : undefined;
  // This immutable receipt can identify its original conversation, not the record's current state.
  const returnToSource = original && canReadSaved && saved && !undoPending && value?.queryOriginalReceipt !== true &&
    (action === 'create' || action === 'update') && proposal.format === 'pocketworld.conversation-memory-draft.v1' &&
    typeof proposal.conversationId === 'string' && proposal.conversationId.trim() && proposal.conversationId.length <= 240 &&
    savedReceipt.status === 'saved' && savedReceipt.action === action && savedReceipt.journalRecorded !== false &&
    savedReceipt.accepted === true && savedReceipt.mutationApplied === true && savedReceipt.formalMemorySaved === true &&
    savedReceipt.executionAuthorized === false && savedReceipt.record?.id === receiptRecord.id &&
    savedReceipt.record.revision === receiptRecord.revision && /^[a-f0-9-]{36}$/.test(receiptRecord.id) &&
    receiptRecord.ownerId === 'owner' && receiptRecord.spaceId === 'private' &&
    Object.entries(original).every(([key, field]) => proposal[key] === field && savedReceipt[key] === field)
    ? originalWorkReturn?.({ ...original, conversationId: proposal.conversationId }) : undefined;
  const expiry = review?.after?.expiresAt;
  const candidatePin = action === 'create' ? proposal.candidate : undefined;
  const candidateUntil = pending && review?.after?.status === 'pending' && typeof candidatePin?.id === 'string' && Number.isSafeInteger(candidatePin.revision) && candidatePin.revision > 0 && candidatePin.id === review.after.id && candidatePin?.revision === review.after.revision && Number.isSafeInteger(expiry) && expiry > 0 ? expiry : 0;
  const candidateExpired = usePendingCandidateDeadline(candidateUntil);
  const expiredReceipt = original && outcomeStatus === 'rejected' && apply.outcome === 'candidate_expired_before_save' && apply.action === 'create' &&
    Object.entries(original).every(([key, field]) => apply[key] === field) && apply.accepted === false && apply.mutationApplied === false && apply.formalMemorySaved === false && apply.undoAvailable === false &&
    typeof candidatePin?.id === 'string' && candidatePin.id === apply.candidateId && Number.isSafeInteger(candidatePin.revision) && candidatePin.revision > 0 && candidatePin.revision === apply.candidateRevision &&
    Number.isSafeInteger(apply.expiresAt) && apply.expiresAt > 0 && Number.isSafeInteger(apply.checkedAt) && apply.expiresAt <= apply.checkedAt;

  const expiryLabel = typeof expiry === 'number' && Number.isSafeInteger(expiry) && expiry > 0 ? new Date(expiry).toLocaleString(undefined, { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '';
  const title = savedInProfile ? (savedInProfile.status === 'same_version' ? '已保存到个人记忆' : '这条建议曾保存') : undoPending ? '撤销结果待核对' : originalReply && !value ? '核对记忆提案' : dismissOnly ? '这份旧提案不能采纳' : duplicate ? '已有相同内容' : reverted ? '已撤销这次保存' : saved ? action === 'forget' ? '已忘记这条记忆' : action === 'update' ? '已更新记忆' : '已记住'
    : action === 'forget' ? '忘记这条记忆' : action === 'update' ? '更新这条记忆' : '记住这件事';
  const status: Record<string, string> = { pending_owner: '待你确认', saved: action === 'forget' ? '这次遗忘已完成' : '这次记忆已保存', already_saved: '已有相同内容，本次没有重复保存', reverted: '这次保存已撤销', cancelled: '已放弃这个提案', rejected: '本次没有修改记忆', unknown: '修改结果待核对', applying_unknown: '修改结果待核对', conflict: '记忆已有后续变化，请查看最新内容' };
  const close = () => { if (!busy) onClose(); };
  const acceptOutcome = (result: Data) => {
    const prior = handle.current;
    if (prior) void bridge.command('memoryDraft.close', { handle: prior }).catch(() => {});
    handle.current = ''; setValue({ ...result, proposal: result.proposal || proposal }); setReviewClosed(false); setUndoUncertain(false);
  };
  const undoFocused = () => {
    const visit = focused, pin = visit?.undo;
    if (!visit || !pin || !originalReply || !sameReply() || entered.current || busy || document.hidden ||
        originalMemoryWindow() !== visit.windowKey || visit.duplicate || visit.revision !== 1) return;
    setUndoFromRecord(true); setUndoUncertain(true); setFocused(null);
    void request('memoryDraft.undo', pin.operation, result => {
      const matches = (row: Data | undefined) => Object.entries(pin.operation).every(([key, field]) => row?.[key] === field);
      const receipt = result.receipt || result.apply?.receipt, undo = result.undo;
      if (!matches(result.proposal) || !matches(receipt) || receipt?.record?.id !== visit.id ||
          receipt.record.revision !== 1 || receipt.record.learningCandidateId !== pin.candidateId ||
          receipt.record.ownerId !== 'owner' || receipt.record.spaceId !== 'private' || receipt.status !== 'saved' ||
          !['saved', 'reverted', 'unknown'].includes(result.status) || result.status === 'reverted' &&
          (result.queryOriginalReceipt === true || !matches(undo) || undo?.status !== 'reverted' || undo.undone !== true ||
           undo.mutationApplied !== true || !Number.isSafeInteger(undo.deleted) || undo.deleted < 1 ||
           undo.record?.id !== visit.id || undo.record.revision !== 1 ||
           undo.record.learningCandidateId !== pin.candidateId || !Array.isArray(undo.removedRefs) ||
           !undo.removedRefs.some((row: Data) => row.recordId === visit.id && row.revision === 1 && row.ownerId === 'owner' && row.spaceId === 'private'))) {
        throw new Error('撤销结果尚未核对。请查询这次修改，不要重复撤销。');
      }
      acceptOutcome(result);
    });
  };
  const queryOriginal = () => {
    if (!original || entered.current || busy) return;
    // Querying retires the native review window: don't leave its old page visible while waiting.
    if (handle.current) { handle.current = ''; setValue(null); setReviewClosed(true); }
    void request('memoryDraft.query', original, acceptOutcome);
  };
  return <Drawer.Root open onOpenChange={open => { if (!open) close(); }} shouldScaleBackground={false}><Drawer.Portal>
    <Drawer.Overlay className="pw-overlay" />
    <Drawer.Content className="pw-drawer pw-memory-draft-drawer" aria-describedby="memory-draft-description">
      <div className="pw-drawer-handle" />
      <header className="pw-drawer-header"><div><Drawer.Title>{focused ? '这条个人记忆' : title}</Drawer.Title><Drawer.Description id="memory-draft-description">{focused ? '只核对原回执指向的正式记录，不打开个人目录。' : savedInProfile ? '这就是原回答的那条建议，已另行确认保存，不需要再保存。' : dismissOnly ? '原来源不允许用于记忆整理。你仍可以忽略这份提案。' : undoPending ? '原保存已完成，请查询这次撤销的实际结果。' : saved || reverted || duplicate ? '查看这次修改的实际结果。' : '查看内容与来源，决定是否保存。'}</Drawer.Description></div><IconButton icon={IconClose} aria-label="返回原回答" disabled={busy} onClick={close} /></header>
      <div className="pw-drawer-body pw-memory-draft-review pw-knowledge-workspace">
        {focused && original ? <OriginalSavedMemory id={focused.id} revision={focused.revision} duplicate={focused.duplicate} windowKey={focused.windowKey}
          target={original} close={onClose} undoOriginal={focused.undo ? undoFocused : undefined}
          manage={onOpenMemory ? verifiedRevision => { onOpenMemory({ id: focused.id, candidate: false, expectedRevision: verifiedRevision }); onClose(); } : undefined} /> : <>
        {busy && <ShimmerText>正在读取记忆…</ShimmerText>}
        {error && <p className="pw-memory-draft-error" role="alert">{error}</p>}
        {savedInProfile && <section className="pw-memory-draft-receipt" aria-label="个人记忆保存结果">
          {savedInProfile.status === 'same_version' ? <>
            <p className="pw-reading-copy">{savedInProfile.text}</p>
            <p className="pw-muted">当前记录仍是当时保存的版本。不代表某次回答已经参考过它。</p>
            {onOpenMemory && <Button variant="secondary" disabled={busy} onClick={() => {
              if (adoptedCurrent()) onOpenMemory({ id: savedInProfile.id, candidate: false, expectedRevision: savedInProfile.revision });
            }}>管理这条个人记忆</Button>}
          </> : <p className="pw-muted">{savedInProfile.status === 'changed' ? `这条记忆已有更新，当前是第 ${savedInProfile.revision} 版。这里不显示旧内容。` : '只能确认这条建议当时已保存，没能核对它现在仍可参考，这里不显示正文。'}</p>}
          <p className="pw-muted">以后可在「我与管家 → 个人记忆」查看。</p>
        </section>}
        {outcomeStatus && <p className="pw-memory-draft-result" role="status">{dismissOnly ? '只可忽略，不显示原话或建议正文。' : undoPending ? '撤销结果待核对，请查询这次修改。' : status[outcomeStatus] || '提案状态待核对'}</p>}
        {typeof outcomeMessage === 'string' && outcomeMessage && <p>{outcomeMessage}</p>}
        {expiredReceipt && onOpenMemory && <Button variant="ghost" disabled={busy} onClick={() => { onOpenMemory({ id: apply.candidateId, candidate: true }); onClose(); }}>查看原建议</Button>}
        {expiredReceipt && value?.queryOriginalReceipt === true && <p className="pw-muted">本机确认这次没有新增记忆，原操作的回执留存尚待核对。</p>}
        {(saved || reverted || duplicate) && <section className="pw-memory-draft-receipt" aria-label="保存结果">
          {!undoFromRecord && typeof receiptRecord?.text === 'string' && <p className="pw-muted">{duplicate ? '当时匹配到的记忆内容' : '这次保存时的内容'}，不是当前版本的证明</p>}
          {typeof displayedRecord?.text === 'string' && <p className="pw-reading-copy">{displayedRecord.text}</p>}
          {typeof undoReason === 'string' && <p className="pw-muted">{undoReason}</p>}
          <div className="pw-action-row">
            {canReadSaved && (returnToSource
              ? returnToSource.open ? <Button variant="secondary" disabled={busy} onClick={returnToSource.open}>前往原对话核对</Button>
                : <span className="pw-muted" role="status">请在原对话核对这条记忆。当前不能切换。</span>
              : <Button variant="secondary" disabled={busy} onClick={() => { setFocused({ id: receiptRecord.id, revision: receiptRecord.revision, duplicate, windowKey: originalMemoryWindow(), undo: focusedUndo }); setValue(null); }}>只看这条记忆</Button>)}
            {duplicate && !receiptRecord && typeof candidate?.id === 'string' && onOpenMemory && <Button variant="secondary" disabled={busy} onClick={() => { onOpenMemory({ id: candidate.id, candidate: true }); onClose(); }}>查看待确认内容</Button>}
            {original && saved && action === 'create' && undoAvailable && !undoPending && <Button variant="ghost" leadingIcon={IconUndo} disabled={busy} onClick={() => { void request('memoryDraft.undo', original, acceptOutcome, () => setUndoUncertain(true)); }}>撤销这次保存</Button>}
          </div>
        </section>}
        {!undoFromRecord && proposal.reason && !saved && !reverted && !duplicate && <p>{proposal.reason}</p>}
        {review && !dismissOnly && !saved && !reverted && !duplicate && <>
          <div className="pw-memory-draft-comparison">
            {review.before && <section className="pw-source-record"><h3>原来记住的内容</h3><p className="pw-reading-copy">{review.before.text}</p></section>}
            {review.after && <section className="pw-source-record"><h3>{action === 'create' ? '准备记住的内容' : '更新后'}</h3><p className="pw-reading-copy">{review.after.text}</p>{review.after.applicability && <p className="pw-muted">适用范围：{review.after.applicability}</p>}{expiryLabel && <p className="pw-muted">{candidateExpired ? `这条建议已于 ${expiryLabel} 到期，不能再采纳；仍可返回或明确忽略。` : `有效至 ${expiryLabel}，到期后不再作为当前背景。`}</p>}</section>}
          </div>
          <p className="pw-memory-draft-effect">{action === 'forget' ? '以后不再参考这条记忆，并处理依赖它的旧内容。原聊天保留，这次遗忘不能撤销。' : action === 'update' ? '保存后使用更新的内容，旧内容不再作为当前事实。' : '保存到个人记忆，供后续私人对话参考。你可以随时查看和纠正。'}</p>
          <Accordion variant="divided" className="pw-compact-disclosures">
            <AccordionItem id="draft-source" title="来源" description="查看这次提案依据的原话">
              {Array.isArray(review.sourceRefs) && review.sourceRefs.length > 0
                ? !attempted && handle.current && <ScopedMemoryEvidence key={handle.current} record={review} candidate={false} project={false} proposal handle={handle.current} expires={review.expiresAt} busy={busy} invoke={request} back={close} />
                : <p className="pw-muted">这份提案没有可打开的聊天来源。</p>}
            </AccordionItem>
            {assertion && <AccordionItem id="draft-assertion" title="更多依据" description="查看主体、条件与时间">
              {review.after?.assertion && <MemoryAssertionFields readOnly candidate={review.after} />}
              {action === 'forget' && review.before?.assertion && <MemoryAssertionFields readOnly candidate={review.before} title="将遗忘的主体与依据" />}
            </AccordionItem>}
          </Accordion>
        </>}
        {reviewClosed && <p className="pw-muted" role="status">原审阅窗口已关闭。没有因此确认或忽略提案。</p>}
        {openInput && (reviewClosed || canReopen || originalReply && !original && error) ? <Button variant="secondary" disabled={busy} onClick={() => { void request('memoryDraft.open', openInput, result => { acceptOpen(result); setReviewClosed(false); setAttempted(false); setUndoUncertain(false); }); }}>重新核对原提案</Button>
          : !adopted && original && (!pending || attempted || error || undoUncertain) && <Button variant="ghost" disabled={busy} onClick={queryOriginal}>查询这次修改</Button>}
        </>}
      </div>
      {!focused && pending && <section className="pw-memory-draft-confirmation"><div className="pw-action-row">
        {!dismissOnly && <Button disabled={busy || candidateExpired} onClick={() => { if (candidateUntil && Date.now() >= candidateUntil) return; const id = handle.current; handle.current = ''; setAttempted(true); void request('memoryDraft.confirm', { handle: id }, acceptOutcome); }}>{action === 'forget' ? '忘记这条记忆' : action === 'update' ? '更新记忆' : '记住'}</Button>}
        <Button variant="ghost" disabled={busy} onClick={() => { const id = handle.current; handle.current = ''; setAttempted(true); void request('memoryDraft.cancel', { handle: id }, acceptOutcome); }}>忽略</Button>
      </div></section>}
    </Drawer.Content>
  </Drawer.Portal></Drawer.Root>;
}
