import { useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Button, SettingsDialog, TextAreaField } from './forms-library';

type Run = (action: string, args?: Record<string, any>) => Promise<any>;
type Props = { name: string; spaceId: string; run: Run; close: () => void; revisit: (id?: string, existing?: boolean) => void; back: { current: (() => void) | null } };
type Review = { review: string; spaceId: string; text: string };

/** Only an owner-typed convention, not a file excerpt, global preference, or task input. */
export function ProjectMemoryNote({ name, spaceId, run, close, revisit, back }: Props) {
  const [text, setText] = useState(''), [review, setReview] = useState<Review | null>(null), [leaving, setLeaving] = useState(false);
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [attempted, setAttempted] = useState(false);
  const live = useRef(true), entered = useRef(false), handle = useRef('');
  const owner = useRef({ run, close }); owner.current = { run, close };
  const release = (id = handle.current) => { if (handle.current === id) handle.current = ''; if (id) void owner.current.run('knowledge.close', { handle: id }).catch(() => {}); };
  const edit = () => { release(); setReview(null); setError(''); };
  const backOne = () => {
    if (entered.current) return;
    if (attempted) revisit(); else if (leaving) setLeaving(false); else if (review) edit(); else if (text.trim()) setLeaving(true); else close();
  };
  useEffect(() => { back.current = backOne; return () => { back.current = null; }; }, [back, review, leaving, attempted, text, revisit, close]);
  useEffect(() => {
    live.current = true; const epoch = bridge.getDocumentEpoch();
    const leave = () => { live.current = false; owner.current.close(); };
    const off = bridge.subscribe(() => { if (epoch !== bridge.getDocumentEpoch()) leave(); });
    const hide = () => { if (document.visibilityState === 'hidden') leave(); };
    document.addEventListener('visibilitychange', hide);
    return () => { live.current = false; off(); document.removeEventListener('visibilitychange', hide); release(); };
  }, []);
  const request = async (work: () => Promise<void>) => {
    if (!live.current || entered.current) return; entered.current = true; setBusy(true); setError('');
    try { await work(); } catch (failure) { if (live.current) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { entered.current = false; if (live.current) setBusy(false); }
  };
  const prepare = () => { if (!text.trim()) return; void request(async () => {
    const submitted = text.trim(); const value = await run('knowledge.project.note.review', { expectedSpace: spaceId, text: submitted });
    if (!live.current) { if (typeof value?.review === 'string') release(value.review); return; }
    if (value?.spaceId !== spaceId || !value.review || typeof value.review !== 'string' || value.chars !== submitted.length) {
      if (typeof value?.review === 'string') release(value.review);
      throw new Error('未能核对原项目和约定内容，没有保存。');
    }
    handle.current = value.review; setReview({ review: value.review, spaceId, text: submitted });
  }); };
  const confirm = () => { if (!review || attempted) return; void request(async () => {
    setAttempted(true);
    const value = await run('knowledge.project.note.confirm', { review: review.review });
    if (!live.current) return;
    if (value?.spaceId !== spaceId || typeof value.recordId !== 'string' || !value.recordId || typeof value.alreadyRecorded !== 'boolean') throw new Error('尚未取得原项目记录的确切回执。');
    revisit(value.recordId, value.alreadyRecorded);
  }); };
  return <SettingsDialog className="pw-memory-modal" isOpen closeLabel="返回上一步" title={leaving ? '放弃未保存的文字？' : review ? '核对项目约定' : '写项目约定'} onClose={backOne}>
    <div className="pw-knowledge-review"><p className="pw-detail-location">仅此项目 · {name}</p>
      {leaving ? <><p>这段文字还没有保存。</p><div className="pw-form-actions"><Button onClick={() => setLeaving(false)}>继续写</Button><Button variant="ghost" onClick={close}>放弃并返回</Button></div></>
      : <>{review ? <><p className="pw-reading-copy">{review.text}</p><p className="pw-muted">将作为你手写的项目约定保存，没有关联文件原文。不会自动把它提供给任何任务。</p></>
        : <><TextAreaField label="这个项目要遵守什么约定？" value={text} onChange={value => { setText(value); setError(''); }} rows={5} maxLength={4000} isDisabled={busy} placeholder="例如：本项目的交接文档写中文。" />
          <p className="pw-muted">保存在这个项目的「已保存」，不是全局个人偏好。保存后可在原记录纠正或遗忘。</p></>}
        {busy && <p role="status">{attempted ? '正在核对保存回执…' : '正在核对原项目…'}</p>}
        {error && <p className="pw-error" role="alert">{error}</p>}
        {attempted && error && <p className="pw-muted">请先重新读取这个项目的已保存记录，不要重复提交。</p>}
        <div className="pw-form-actions">{!review && <Button disabled={busy || !text.trim()} onClick={prepare}>核对保存位置</Button>}
          {review && !attempted && <Button disabled={busy} onClick={confirm}>只保存到这个项目</Button>}
          {(!attempted || !!error) && <Button variant="ghost" disabled={busy} onClick={backOne}>{attempted ? '核对本项目已保存记录' : review ? '返回修改' : '返回'}</Button>}
        </div></>}
    </div></SettingsDialog>;
}
