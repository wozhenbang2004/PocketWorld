import { useCallback, useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { AIAnswer } from './library';
import { Button } from './forms-library';

type ReportTarget = { taskId: string; runId: string; epoch: number; reportMessageId: string; conversationId: string };
type ReportPin = { expectedReportRevision: number; expectedReportFingerprint: string };
type ReportPage = { identity: string; body: string; offset: number; totalChars: number; nextOffset: number };
const resumeRevision = () => Number((bridge.getState().view as { resumeRevision?: number } | undefined)?.resumeRevision || 0);

/** Read the saved report in its original work, using the existing Answer and
 * reader controls. A page read never selects a conversation or starts a task. */
export function OwnerWorkReport({ target, active, supported, identity, onCopy }: {
  target: ReportTarget;
  active: boolean;
  supported: boolean;
  identity: string;
  onCopy: (text: string) => Promise<unknown>;
}) {
  const { taskId, runId, epoch, reportMessageId, conversationId } = target;
  const [retainedPage, setPage] = useState<ReportPage | null>(null);
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  const [offsets, setOffsets] = useState<number[]>([]);
  const alive = useRef(false), enabled = useRef(false), generation = useRef(0), pending = useRef(false);
  const pin = useRef<ReportPin | null>(null), requestedOffset = useRef(0);
  const heading = useRef<HTMLHeadingElement>(null);
  enabled.current = active && supported;

  const read = useCallback(async (offset: number, fromStart = false, moveToHeading = false) => {
    if (!enabled.current || document.hidden || pending.current) return;
    if (fromStart) { pin.current = null; setOffsets([]); }
    const expected = pin.current;
    if (offset > 0 && !expected) { setError('请从汇报开头重新读取。'); return; }
    const request = ++generation.current, documentEpoch = bridge.getDocumentEpoch(), resume = resumeRevision();
    pending.current = true; requestedOffset.current = offset;
    setBusy(true); setError('');
    // Returning from a task tab keeps the document's height and reading position
    // while the same page is rechecked. A different window or range has no body.
    setPage(previous => !fromStart && previous?.identity === identity && previous.offset === offset ? previous : null);
    const accepts = () => alive.current && enabled.current && request === generation.current
      && documentEpoch === bridge.getDocumentEpoch() && resume === resumeRevision() && !document.hidden;
    try {
      const result = await bridge.command('ownerWork.report.read', { taskId, runId, epoch, reportMessageId, offset, maxChars: 6000, ...expected });
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
      setPage({ identity, body, offset, totalChars: range.totalChars, nextOffset: result.nextOffset });
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
  }, [taskId, runId, epoch, reportMessageId, conversationId, identity]);

  useEffect(() => {
    alive.current = true; setBusy(false); setError('');
    if (active && supported) void read(requestedOffset.current);
    const resume = () => {
      generation.current++; pending.current = false; setPage(null); setBusy(false);
      if (!document.hidden && enabled.current) void read(requestedOffset.current);
    };
    document.addEventListener('visibilitychange', resume);
    return () => { alive.current = false; generation.current++; pending.current = false; document.removeEventListener('visibilitychange', resume); };
  }, [active, supported, identity, read]);

  const page = retainedPage?.identity === identity ? retainedPage : null;
  const partial = !!page && (page.offset > 0 || page.nextOffset >= 0);
  return <section className="pw-owner-work-report" aria-label="管家汇报" aria-busy={busy || undefined}>
    <header className="pw-outcome-heading"><h3 ref={heading} tabIndex={-1}>管家汇报</h3></header>
    {busy && <p className="pw-reading-note" role="status">正在读取这件工作的汇报…</p>}
    {error && <p className="pw-reading-note pw-error" role="alert">{error}</p>}
    {!active || !supported ? <p className="pw-reading-note">原工作详情就绪后可读取汇报。</p> : page && <>
      {partial && <p className="pw-reading-note">第 {offsets.length || 1} 段 · 长汇报分段显示，复制仅包含当前这一段。</p>}
      {page.body ? <AIAnswer text={page.body} onCopy={() => onCopy(page.body)} onCopyCode={onCopy} /> : <p className="pw-reading-note">这份汇报没有文字正文。</p>}
    </>}
    {active && supported && (error || page && (offsets.length > 1 || page.nextOffset >= 0)) && <div className="pw-action-row pw-owner-report-controls">
      {!!page && offsets.length > 1 && <Button variant="ghost" disabled={busy} onClick={() => void read(offsets[offsets.length - 2], false, true)}>上一段</Button>}
      {!!page && page.nextOffset >= 0 && <Button variant="secondary" disabled={busy} onClick={() => void read(page.nextOffset, false, true)}>下一段汇报</Button>}
      {!!error && <Button variant="secondary" disabled={busy} onClick={() => void read(requestedOffset.current)}>重新读取这一段</Button>}
      {!!error && !!pin.current && <Button variant="ghost" disabled={busy} onClick={() => void read(0, true, true)}>从开头重新读取</Button>}
    </div>}
  </section>;
}
