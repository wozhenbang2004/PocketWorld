import { useEffect, useRef, useState } from 'react';
import { IconClose, IconUndo } from './icons';
import { bridge } from './bridge';
import { Drawer } from './shell-library';
import './knowledge-depth.css';
import './memory-draft-review.css';
import { Accordion, AccordionItem, Button, IconButton } from './forms-library';
import { ShimmerText } from './library';
import type { ConversationDraftTarget } from './SkillDraftReview';
import { MemoryAssertionFields } from './MemoryAssertionFields';

type Data = Record<string, any>;
const list = (value: any): Data[] => Array.isArray(value) ? value : [];
export function MemoryDraftReview({ target, onClose, onOpenMemory }: {
  target: ConversationDraftTarget;
  onClose: () => void;
  onOpenMemory?: (target: { id: string; candidate: boolean }) => void;
}) {
  const [value, setValue] = useState<Data | null>(null), [source, setSource] = useState<Data | null>(null);
  const [error, setError] = useState(''), [busy, setBusy] = useState(true), [attempted, setAttempted] = useState(false), [undoUncertain, setUndoUncertain] = useState(false);
  const handle = useRef(''), alive = useRef(true), entered = useRef(false);
  const original = { taskId: target.taskId, runId: target.runId, epoch: target.epoch, operationId: target.operationId, proposalDigest: target.proposalDigest };
  useEffect(() => {
    alive.current = true;
    void bridge.command('memoryDraft.open', original).then(result => {
      if (alive.current) { setValue(result); handle.current = result.handle || ''; }
      else if (result.handle) void bridge.command('memoryDraft.close', { handle: result.handle }).catch(() => {});
    }).catch(failure => { if (alive.current) setError(failure.message); }).finally(() => { if (alive.current) setBusy(false); });
    return () => { alive.current = false; const id = handle.current; handle.current = ''; if (id) void bridge.command('memoryDraft.close', { handle: id }).catch(() => {}); };
  }, []);
  const request = async (name: string, args: Data, accept: (result: Data) => void, uncertain?: () => void) => {
    if (entered.current || busy) return;
    entered.current = true; setBusy(true); setError('');
    try {
      const result = await bridge.command(name, args);
      if (alive.current) accept(result);
      else if (name === 'memoryDraft.open' && result.handle) void bridge.command('memoryDraft.close', { handle: result.handle }).catch(() => {});
    } catch (failure) {
      if (alive.current) { uncertain?.(); setError(failure instanceof Error ? failure.message : String(failure)); }
    } finally { entered.current = false; if (alive.current) setBusy(false); }
  };
  const review = value?.review, proposal = value?.proposal || review?.proposal || {}, apply = value?.apply || (value?.status ? value : {});
  const outcomeStatus = value?.status || apply.status;
  const pending = outcomeStatus === 'pending_owner' && !!review && !!handle.current && !attempted;
  // A fresh native query must settle an uncertain attempt before the original review can reopen.
  const canReopen = outcomeStatus === 'pending_owner' && !review && !handle.current;
  const action = proposal.action || review?.action;
  const assertion = review?.after?.assertion || (action === 'forget' ? review?.before?.assertion : null);
  const originalRecord = value?.receipt?.record || apply.record || apply.receipt?.record;
  const record = value && Object.prototype.hasOwnProperty.call(value, 'record') ? value.record : originalRecord;
  const candidate = value?.existingCandidate;
  const undoAvailable = (value?.undoAvailable ?? value?.receipt?.undoAvailable ?? apply.undoAvailable ?? apply.receipt?.undoAvailable) === true;
  const undoReason = value?.undoUnavailableReason || value?.receipt?.undoUnavailableReason || apply.undoUnavailableReason || apply.receipt?.undoUnavailableReason;
  const outcomeMessage = value?.undo?.message || value?.message || apply.message;
  const saved = outcomeStatus === 'saved', reverted = outcomeStatus === 'reverted', duplicate = outcomeStatus === 'already_saved';
  const undoPending = undoUncertain || ['unknown', 'undoing_unknown'].includes(value?.undo?.status);
  const displayedRecord = record || (duplicate ? candidate : originalRecord);
  const expiry = review?.after?.expiresAt;
  const expiryLabel = typeof expiry === 'number' && Number.isSafeInteger(expiry) && expiry > 0 ? new Date(expiry).toLocaleString(undefined, { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '';
  const title = undoPending ? '撤销结果待核对' : duplicate ? '已有相同内容' : reverted ? '已撤销这次保存' : saved ? action === 'forget' ? '已忘记这条记忆' : action === 'update' ? '已更新记忆' : '已记住'
    : action === 'forget' ? '忘记这条记忆' : action === 'update' ? '更新这条记忆' : '记住这件事';
  const status: Record<string, string> = { pending_owner: '待你确认', saved: action === 'forget' ? '这次遗忘已完成' : '这次记忆已保存', already_saved: '已有相同内容，本次没有重复保存', reverted: '这次保存已撤销', cancelled: '已放弃这个提案', rejected: '本次没有修改记忆', unknown: '修改结果待核对', applying_unknown: '修改结果待核对', conflict: '记忆已有后续变化，请查看最新内容' };
  const close = () => { if (!busy) onClose(); };
  const acceptOutcome = (result: Data) => {
    const prior = handle.current;
    if (prior) void bridge.command('memoryDraft.close', { handle: prior }).catch(() => {});
    handle.current = ''; setValue({ ...result, proposal: result.proposal || proposal }); setSource(null); setUndoUncertain(false);
  };
  const queryOriginal = () => { void request('memoryDraft.query', original, acceptOutcome); };
  const readSource = (index: number, offset = 0) => {
    void request('memoryDraft.source', { handle: handle.current, index, offset }, result => {
      setSource(previous => offset > 0 && previous?.index === index ? { ...result, offset: previous.offset, text: String(previous.text || '') + String(result.text || '') } : result);
    });
  };
  return <Drawer.Root open onOpenChange={open => { if (!open) close(); }} shouldScaleBackground={false}><Drawer.Portal>
    <Drawer.Overlay className="pw-overlay" />
    <Drawer.Content className="pw-drawer pw-memory-draft-drawer" aria-describedby="memory-draft-description">
      <div className="pw-drawer-handle" />
      <header className="pw-drawer-header"><div><Drawer.Title>{title}</Drawer.Title><Drawer.Description id="memory-draft-description">{undoPending ? '原保存已完成，请查询这次撤销的实际结果。' : saved || reverted || duplicate ? '查看这次修改的实际结果。' : '查看内容与来源，决定是否保存。'}</Drawer.Description></div><IconButton icon={IconClose} aria-label="返回原回答" disabled={busy} onClick={close} /></header>
      <div className="pw-drawer-body pw-memory-draft-review pw-knowledge-workspace">
        {busy && <ShimmerText>正在读取记忆…</ShimmerText>}
        {error && <p className="pw-memory-draft-error" role="alert">{error}</p>}
        {outcomeStatus && <p className="pw-memory-draft-result" role="status">{undoPending ? '撤销结果待核对，请查询这次修改。' : status[outcomeStatus] || '提案状态待核对'}</p>}
        {typeof outcomeMessage === 'string' && outcomeMessage && <p>{outcomeMessage}</p>}
        {(saved || reverted || duplicate) && <section className="pw-memory-draft-receipt" aria-label="保存结果">
          {!record && !duplicate && typeof originalRecord?.text === 'string' && <p className="pw-muted">本次保存时的内容</p>}
          {typeof displayedRecord?.text === 'string' && <p className="pw-reading-copy">{displayedRecord.text}</p>}
          {typeof undoReason === 'string' && <p className="pw-muted">{undoReason}</p>}
          <div className="pw-action-row">
            {(saved || duplicate) && typeof record?.id === 'string' && value?.recordCurrent !== false && onOpenMemory && action !== 'forget' && <Button variant="secondary" disabled={busy} onClick={() => { onOpenMemory({ id: record.id, candidate: false }); onClose(); }}>查看记忆</Button>}
            {duplicate && !record && typeof candidate?.id === 'string' && onOpenMemory && <Button variant="secondary" disabled={busy} onClick={() => { onOpenMemory({ id: candidate.id, candidate: true }); onClose(); }}>查看待确认内容</Button>}
            {saved && action === 'create' && undoAvailable && !undoPending && <Button variant="ghost" leadingIcon={IconUndo} disabled={busy} onClick={() => { void request('memoryDraft.undo', original, acceptOutcome, () => setUndoUncertain(true)); }}>撤销这次保存</Button>}
          </div>
        </section>}
        {proposal.reason && !saved && !reverted && !duplicate && <p>{proposal.reason}</p>}
        {review && !saved && !reverted && !duplicate && <>
          <div className="pw-memory-draft-comparison">
            {review.before && <section className="pw-source-record"><h3>原来记住的内容</h3><p className="pw-reading-copy">{review.before.text}</p></section>}
            {review.after && <section className="pw-source-record"><h3>{action === 'create' ? '准备记住的内容' : '更新后'}</h3><p className="pw-reading-copy">{review.after.text}</p>{review.after.applicability && <p className="pw-muted">适用范围：{review.after.applicability}</p>}{expiryLabel && <p className="pw-muted">有效至 {expiryLabel}，到期后不再作为当前背景。</p>}</section>}
          </div>
          <p className="pw-memory-draft-effect">{action === 'forget' ? '以后不再参考这条记忆，并处理依赖它的旧内容。原聊天保留，这次遗忘不能撤销。' : action === 'update' ? '保存后使用更新的内容，旧内容不再作为当前事实。' : '保存到个人记忆，供后续私人对话参考。你可以随时查看和纠正。'}</p>
          <Accordion variant="divided" className="pw-compact-disclosures">
            <AccordionItem id="draft-source" title="来源" description="查看这次提案依据的原话">
              <div className="pw-action-row">{list(review.sourceRefs).map((ref, index) => <Button key={String(ref.sourceId) + ':' + index} variant="ghost" disabled={busy || attempted || !handle.current} onClick={() => readSource(index)}>{ref.title || ref.name || '查看来源 ' + (index + 1)}</Button>)}</div>
              {!list(review.sourceRefs).length && <p className="pw-muted">这份提案没有可打开的聊天来源。</p>}
              {source && <section className="pw-memory-draft-source"><h4>{source.title || '原始内容'}</h4><p className="pw-reading-copy">{source.text}</p>{source.hasMore && <Button variant="ghost" disabled={busy || attempted} onClick={() => readSource(source.index, source.nextOffset)}>继续读取原文</Button>}</section>}
            </AccordionItem>
            {assertion && <AccordionItem id="draft-assertion" title="更多依据" description="查看主体、条件与时间">
              {review.after?.assertion && <MemoryAssertionFields readOnly candidate={review.after} />}
              {action === 'forget' && review.before?.assertion && <MemoryAssertionFields readOnly candidate={review.before} title="将遗忘的主体与依据" />}
            </AccordionItem>}
          </Accordion>
        </>}
        {canReopen ? <Button variant="secondary" disabled={busy} onClick={() => { void request('memoryDraft.open', original, result => { handle.current = result.handle || ''; setValue(result); setSource(null); setAttempted(false); setUndoUncertain(false); }); }}>重新打开提案</Button>
          : (!pending || attempted || error || undoUncertain) && <Button variant="ghost" disabled={busy} onClick={queryOriginal}>查询这次修改</Button>}
      </div>
      {pending && <section className="pw-memory-draft-confirmation"><div className="pw-action-row">
        <Button disabled={busy} onClick={() => { const id = handle.current; handle.current = ''; setAttempted(true); void request('memoryDraft.confirm', { handle: id }, acceptOutcome); }}>{action === 'forget' ? '忘记这条记忆' : action === 'update' ? '更新记忆' : '记住'}</Button>
        <Button variant="ghost" disabled={busy} onClick={() => { const id = handle.current; handle.current = ''; setAttempted(true); void request('memoryDraft.cancel', { handle: id }, acceptOutcome); }}>忽略</Button>
      </div></section>}
    </Drawer.Content>
  </Drawer.Portal></Drawer.Root>;
}
