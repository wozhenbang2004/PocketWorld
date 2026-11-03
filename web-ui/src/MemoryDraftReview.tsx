import { useEffect, useRef, useState } from 'react';
import { RiCloseLine } from '@remixicon/react';
import { bridge } from './bridge';
import { Drawer } from './shell-library';
import { Button, CheckboxField, IconButton } from './forms-library';
import { ShimmerText } from './library';
import type { ConversationDraftTarget } from './SkillDraftReview';

type Data = Record<string, any>;
const list = (value: any): Data[] => Array.isArray(value) ? value : [];
export function MemoryDraftReview({ target, onClose }: { target: ConversationDraftTarget; onClose: () => void }) {
  const [value, setValue] = useState<Data | null>(null), [source, setSource] = useState<Data | null>(null), [error, setError] = useState(''), [busy, setBusy] = useState(true), [acknowledged, setAcknowledged] = useState(false), [attempted, setAttempted] = useState(false);
  const handle = useRef(''), alive = useRef(true), entered = useRef(false);
  const original = { taskId: target.taskId, runId: target.runId, epoch: target.epoch, operationId: target.operationId, proposalDigest: target.proposalDigest };
  useEffect(() => {
    alive.current = true;
    void bridge.command('memoryDraft.open', original).then(result => { if (alive.current) { setValue(result); handle.current = result.handle || ''; } else if (result.handle) void bridge.command('memoryDraft.close', { handle: result.handle }).catch(() => {}); }).catch(failure => { if (alive.current) setError(failure.message); }).finally(() => { if (alive.current) setBusy(false); });
    return () => { alive.current = false; const id = handle.current; handle.current = ''; if (id) void bridge.command('memoryDraft.close', { handle: id }).catch(() => {}); };
  }, []);
  const request = async (name: string, args: Data, accept: (result: Data) => void) => {
    if (entered.current || busy) return; entered.current = true; setBusy(true); setError('');
    try { const result = await bridge.command(name, args); if (alive.current) accept(result); }
    catch (failure) { if (alive.current) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { entered.current = false; if (alive.current) setBusy(false); }
  };
  const review = value?.review, proposal = value?.proposal || review?.proposal || {}, apply = value?.apply || (value?.status ? value : {});
  const pending = apply.status === 'pending_owner' && !!review && !!handle.current && !attempted;
  const action = proposal.action || review?.action;
  const title = action === 'forget' ? '遗忘这条记忆' : action === 'update' ? '纠正这条记忆' : '保存这条记忆';
  const status: Record<string, string> = { pending_owner: '待你审阅', saved: action === 'forget' ? '原遗忘已应用' : '原记忆已保存', cancelled: '已放弃原提案', rejected: '本次没有修改正式记忆', unknown: '原修改结果待核对', applying_unknown: '原修改结果待核对' };
  const close = () => { if (!busy) onClose(); };
  return <Drawer.Root open onOpenChange={open => { if (!open) close(); }} shouldScaleBackground={false}><Drawer.Portal><Drawer.Overlay className="pw-overlay" /><Drawer.Content className="pw-drawer" aria-describedby="memory-draft-description">
    <div className="pw-drawer-handle" /><header className="pw-drawer-header"><div><Drawer.Title>{title}</Drawer.Title><Drawer.Description id="memory-draft-description">核对原内容、来源与影响后，再确认这一次修改。</Drawer.Description></div><IconButton icon={RiCloseLine} aria-label="返回原回答" disabled={busy} onClick={close} /></header>
    <div className="pw-drawer-body">{busy && <ShimmerText>正在核对原提案…</ShimmerText>}{error && <p role="alert">{error}</p>}{apply.status && <p role="status">{status[apply.status] || '原提案状态待核对'}</p>}{apply.message && <p>{apply.message}</p>}
      {proposal.reason && <p>{proposal.reason}</p>}{review && <>
        {review.before && <section className="pw-source-record"><h3>原记忆</h3><p className="pw-reading-copy">{review.before.text}</p><p className="pw-muted">原修订 {review.before.revision}</p></section>}
        {review.after && <section className="pw-source-record"><h3>{action === 'create' ? '拟保存内容' : '纠正后的内容'}</h3><p className="pw-reading-copy">{review.after.text}</p>{review.after.applicability && <p>适用范围：{review.after.applicability}</p>}</section>}
        <section className="pw-source-record"><h3>本次提案来源</h3><p className="pw-muted">这是助手根据已准备的主人消息整理的提案；接受保存不会把归纳内容变成已独立核实的事实。</p><div className="pw-action-row">{list(review.sourceRefs).map((ref, index) => <Button key={ref.sourceId + ':' + ref.revision} variant="ghost" disabled={busy || attempted || !handle.current} onClick={() => { void request('memoryDraft.source', { handle: handle.current, index, offset: 0 }, setSource); }}>来源 {index + 1} · 修订 {ref.revision}</Button>)}</div>
          {source && <><h4>{source.title}</h4><pre className="pw-source-copy" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{source.text}</pre><p className="pw-muted">已显示原文 {source.offset}–{source.nextOffset} / {source.totalChars} 字符</p>{source.hasMore && <Button variant="ghost" disabled={busy || attempted} onClick={() => { void request('memoryDraft.source', { handle: handle.current, index: source.index, offset: source.nextOffset }, setSource); }}>继续读取原文</Button>}</>}
        </section>
        <section className="pw-source-record"><h3>确认后的影响</h3>{action === 'forget' ? <p>遗忘原记忆并处理由同一证据派生的记忆，阻止旧来源被自动重新导入。原聊天消息不会因此删除。</p> : action === 'update' ? <p>保留原来源关系并替换正文；依赖旧内容的上下文会退休，旧断言证据不会冒充新正文的证明。</p> : <p>采用这份待审候选，保存为私人记忆。内容仍保留未核实状态，后续可在记忆管理中纠正或遗忘。</p>}</section>
        {pending && <><CheckboxField isSelected={acknowledged} onChange={setAcknowledged}>我已核对正文、来源与以上影响</CheckboxField><div className="pw-action-row"><Button disabled={busy || !acknowledged} onClick={() => { const id = handle.current; setAttempted(true); void request('memoryDraft.confirm', { handle: id }, result => { handle.current = ''; setValue({ proposal, apply: result }); }); }}>确认{action === 'forget' ? '遗忘' : action === 'update' ? '纠正' : '保存'}</Button><Button variant="secondary" disabled={busy} onClick={() => { const id = handle.current; setAttempted(true); void request('memoryDraft.cancel', { handle: id }, result => { handle.current = ''; setValue(result); }); }}>放弃这个原提案</Button></div></>}
      </>}{(!pending || attempted || error) && <Button variant="ghost" disabled={busy} onClick={() => { void request('memoryDraft.query', original, result => { handle.current = ''; setValue(result); setSource(null); }); }}>查询原修改结果</Button>}
    </div></Drawer.Content></Drawer.Portal></Drawer.Root>;
}
