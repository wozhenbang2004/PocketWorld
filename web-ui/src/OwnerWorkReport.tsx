import { useCallback, useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { AIAnswer } from './library';
import { Button } from './forms-library';

export type ReportTarget = { taskId: string; runId: string; epoch: number; reportMessageId: string; conversationId: string };
export type ReportPin = { expectedReportRevision: number; expectedReportFingerprint: string };
type FollowUpOffer = { handle: string; documentEpoch: number; resume: number; request: number };
type ReportPage = { identity: string; body: string; offset: number; totalChars: number; nextOffset: number; followUp?: FollowUpOffer };
const resumeRevision = () => Number((bridge.getState().view as { resumeRevision?: number } | undefined)?.resumeRevision || 0);

/** Read the saved report in its original work, using the existing Answer and
 * reader controls. A page read never selects a conversation or starts a task. */
export function OwnerWorkReport({ target, active, supported, identity, onCopy, originalVersion, onIndependentFollowUp, followUpState }: {
  target: ReportTarget;
  originalVersion?: ReportPin;
  active: boolean;
  supported: boolean;
  identity: string;
  onCopy: (text: string) => Promise<unknown>;
  onIndependentFollowUp?: (handle: string) => Promise<boolean>;
  followUpState?: 'blocked' | 'pending';
}) {
  const { taskId, runId, epoch, reportMessageId, conversationId } = target;
  const [retainedPage, setPage] = useState<ReportPage | null>(null);
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  const [followUpError, setFollowUpError] = useState('');
  const [offsets, setOffsets] = useState<number[]>([]);
  const alive = useRef(false), enabled = useRef(false), generation = useRef(0), pending = useRef(false);
  const pin = useRef<ReportPin | null>(originalVersion??null), requestedOffset = useRef(0);
  const heading = useRef<HTMLHeadingElement>(null);
  const usedRequest = useRef<number | null>(null);
  const independentFollowUp = !!originalVersion && !!onIndependentFollowUp;
  enabled.current = active && supported;

  const read = useCallback(async (offset: number, fromStart = false, moveToHeading = false) => {
    if (!enabled.current || document.hidden || pending.current) return;
    if (fromStart) { pin.current = originalVersion??null; setOffsets([]); }
    const expected = pin.current;
    if (offset > 0 && !expected) { setError('请从汇报开头重新读取。'); return; }
    const request = ++generation.current, documentEpoch = bridge.getDocumentEpoch(), resume = resumeRevision();
    pending.current = true; requestedOffset.current = offset;
    setBusy(true); setError(''); setFollowUpError('');
    // Returning from a task tab keeps the document's height and reading position
    // while the same page is rechecked. A different window or range has no body.
    setPage(previous => {
      if (fromStart || previous?.identity !== identity || previous.offset !== offset) return null;
      return previous.followUp ? { ...previous, followUp: undefined } : previous;
    });
    const accepts = () => alive.current && enabled.current && request === generation.current
      && documentEpoch === bridge.getDocumentEpoch() && resume === resumeRevision() && !document.hidden;
    try {
      // Only this pinned report's actual first page can offer the whole report.
      const requestFollowUp = independentFollowUp && offset === 0 && !!expected;
      const result = await bridge.command('ownerWork.report.read', { taskId, runId, epoch, reportMessageId, offset, maxChars: 6000, ...expected,
        ...(requestFollowUp ? {independentFollowUp: true} : {}) });
      if (!accepts()) return;
      if (result?.taskId !== taskId || result.runId !== runId || result.epoch !== epoch || result.reportMessageId !== reportMessageId
        || result.conversationId !== conversationId || result.readOnly !== true || result.ownerOnly !== true || result.executionAuthorized !== false) {
        throw new Error('原工作与汇报的关系暂不可核对，请重新打开这件工作。');
      }
      if (result.status !== 'current') throw new Error(typeof result.reason === 'string' ? result.reason : '这份汇报暂不可读取，请重新打开原工作核对。');
      const range = result.readRange, body = result.body;
      if (!Number.isSafeInteger(result.reportRevision) || result.reportRevision < 1 || typeof result.bodyFingerprint !== 'string'
        || !/^sha256:[a-f0-9]{64}$/.test(result.bodyFingerprint)
        || expected && (result.reportRevision !== expected.expectedReportRevision || result.bodyFingerprint !== expected.expectedReportFingerprint)
        || typeof body !== 'string' || body.length > 6000 || range?.unit !== 'UTF-16' || range.offset !== offset || range.chars !== body.length
        || !Number.isSafeInteger(range.totalChars) || range.totalChars < offset + body.length
        || range.totalChars > offset && !body.length || !Number.isSafeInteger(result.nextOffset)
        || result.nextOffset !== (offset + body.length < range.totalChars ? offset + body.length : -1)) {
        throw new Error('汇报版本或读取范围已变化，请从开头重新读取。');
      }
      pin.current = { expectedReportRevision: result.reportRevision, expectedReportFingerprint: result.bodyFingerprint };
      const handle = result.followUpHandle;
      const followUp = requestFollowUp && result.modelCalled === false && result.modelReferenceRegistered === false &&
        result.nextOffset === -1 && body.length === range.totalChars && body.length > 0 && body.length <= 4000 &&
        typeof handle === 'string' && /^[A-Za-z0-9_-]{1,240}$/.test(handle) ? { handle, documentEpoch, resume, request } : undefined;
      setPage({ identity, body, offset, totalChars: range.totalChars, nextOffset: result.nextOffset, followUp });
      setOffsets(previous => { const index = previous.indexOf(offset); return index < 0 ? [...previous, offset] : previous.slice(0, index + 1); });
      if (moveToHeading) requestAnimationFrame(() => {
        if (!accepts() || !heading.current) return;
        heading.current.scrollIntoView({ block: 'start' }); heading.current.focus({ preventScroll: true });
      });
    } catch (failure) {
      if (accepts()) { setPage(null); setError(failure instanceof Error ? failure.message : '汇报暂不可读取，请重试。'); }
    } finally {
      if (request === generation.current) { pending.current = false; if (alive.current) setBusy(false); }
    }
  }, [taskId, runId, epoch, reportMessageId, conversationId, identity, originalVersion, independentFollowUp]);

  useEffect(() => {
    alive.current = true; setBusy(false); setError(''); setFollowUpError('');
    if (active && supported) void read(requestedOffset.current);
    else setPage(previous => previous?.followUp ? { ...previous, followUp: undefined } : previous);
    const resume = () => {
      generation.current++; pending.current = false; setPage(null); setBusy(false); setFollowUpError('');
      if (!document.hidden && enabled.current) void read(requestedOffset.current);
    };
    const unsubscribe = independentFollowUp ? bridge.subscribeDocumentEpoch(() => {
      generation.current++; pending.current = false; setPage(null); setBusy(false); setError(''); setFollowUpError('');
    }) : undefined;
    document.addEventListener('visibilitychange', resume);
    return () => { alive.current = false; generation.current++; pending.current = false; unsubscribe?.(); document.removeEventListener('visibilitychange', resume); };
  }, [active, supported, identity, read, independentFollowUp]);

  const page = retainedPage?.identity === identity ? retainedPage : null;
  const partial = !!page && (page.offset > 0 || page.nextOffset >= 0);
  const currentOffer = (offer: FollowUpOffer) => {
    const view = bridge.getState().view as { section?: unknown; taskId?: unknown; resumeRevision?: unknown } | undefined;
    return independentFollowUp && alive.current && enabled.current && !document.hidden && view?.section === null && view.taskId === taskId &&
      offer.request === generation.current && offer.documentEpoch === bridge.getDocumentEpoch() && view.resumeRevision === offer.resume;
  };
  const followUp = !busy && !error && page?.followUp && currentOffer(page.followUp) ? page.followUp : undefined;
  const openFollowUp = async (offer: FollowUpOffer) => {
    if (!onIndependentFollowUp || followUpState || pending.current || usedRequest.current === offer.request || !currentOffer(offer)) return;
    usedRequest.current = offer.request;
    const unconfirmed = '尚未确认打开独立追问。原汇报仍可查看，请核对本机草稿。';
    let failure = '';
    try { if (await onIndependentFollowUp(offer.handle) !== true) failure = unconfirmed; }
    catch (reason) { failure = reason instanceof Error ? reason.message || unconfirmed : unconfirmed; }
    if (!currentOffer(offer)) return;
    setPage(previous => previous?.followUp === offer ? { ...previous, followUp: undefined } : previous);
    if (failure) setFollowUpError(failure);
  };
  return <section className="pw-owner-work-report" aria-label="管家汇报" aria-busy={busy || undefined}>
    <header className="pw-outcome-heading"><h3 ref={heading} tabIndex={-1}>管家汇报</h3></header>
    {busy && <p className="pw-reading-note" role="status">正在读取这件工作的汇报…</p>}
    {error && <p className="pw-reading-note pw-error" role="alert">{error}</p>}
    {!active || !supported ? <p className="pw-reading-note">原工作详情就绪后可读取汇报。</p> : page && <>
      {partial && <p className="pw-reading-note">第 {offsets.length || 1} 段 · 长汇报分段显示，复制仅包含当前这一段。</p>}
      {page.body ? <AIAnswer text={page.body} onCopy={() => onCopy(page.body)} onCopyCode={onCopy} /> : <p className="pw-reading-note">这份汇报没有文字正文。</p>}
    </>}
    {active && followUpError && <p className="pw-reading-note pw-error" role="status">{followUpError}</p>}
    {active && supported && (followUp || followUpError || error || page && (offsets.length > 1 || page.nextOffset >= 0)) && <div className="pw-action-row pw-owner-report-controls">
      {followUp && <Button variant="secondary" disabled={!!followUpState} aria-busy={followUpState === 'pending' || undefined}
        onClick={() => void openFollowUp(followUp)}>{followUpState === 'pending' ? '正在准备独立追问…' : '引用这份汇报，另写追问'}</Button>}
      {followUpError && <Button variant="secondary" disabled={busy || !!followUpState} onClick={() => void read(0)}>重新核对这份汇报</Button>}
      {!!page && offsets.length > 1 && <Button variant="ghost" disabled={busy} onClick={() => void read(offsets[offsets.length - 2], false, true)}>上一段</Button>}
      {!!page && page.nextOffset >= 0 && <Button variant="secondary" disabled={busy} onClick={() => void read(page.nextOffset, false, true)}>下一段汇报</Button>}
      {!!error && <Button variant="secondary" disabled={busy} onClick={() => void read(requestedOffset.current)}>重新读取这一段</Button>}
      {!!error && !!pin.current && <Button variant="ghost" disabled={busy} onClick={() => void read(0, true, true)}>从开头重新读取</Button>}
    </div>}
  </section>;
}
