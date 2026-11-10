import { useEffect, useRef, useState } from 'react';
import { RiCloseLine } from '@remixicon/react';
import { bridge } from './bridge';
import { Drawer } from './shell-library';
import './knowledge-depth.css';
import { Accordion, AccordionItem, Button, CheckboxField, IconButton } from './forms-library';
import { ShimmerText } from './library';
import type { ConversationDraftTarget } from './SkillDraftReview';
import { MemoryAssertionFields } from './MemoryAssertionFields';

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
    try {
      const result = await bridge.command(name, args);
      if (alive.current) accept(result);
      else if (name === 'memoryDraft.open' && result.handle) void bridge.command('memoryDraft.close', { handle: result.handle }).catch(() => {});
    }
    catch (failure) { if (alive.current) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { entered.current = false; if (alive.current) setBusy(false); }
  };
  const review = value?.review, proposal = value?.proposal || review?.proposal || {}, apply = value?.apply || (value?.status ? value : {});
  const pending = apply.status === 'pending_owner' && !!review && !!handle.current && !attempted;
  // Only a fresh query without a review can enable reopening after an uncertain attempt.
  const canReopen = apply.status === 'pending_owner' && !review && !handle.current;
  const action = proposal.action || review?.action;
  const assertion = review?.after?.assertion || (action === 'forget' ? review?.before?.assertion : null);
  const assertionSubject = assertion?.subjectId === 'owner' ? '你' : assertion?.subjectLabel || '尚未明确';
  const assertionKind = ({ user_said: '原话记载', observed: '直接观察', inferred: '推测', told: '转述', imagined: '计划或想象' } as Record<string, string>)[assertion?.kind] || '原类型待核对';
  const title = action === 'forget' ? '遗忘这条记忆' : action === 'update' ? '纠正这条记忆' : '保存这条记忆';
  const status: Record<string, string> = { pending_owner: '待你审阅', saved: action === 'forget' ? '原遗忘已应用' : '原记忆已保存', cancelled: '已放弃原提案', rejected: '本次没有修改正式记忆', unknown: '原修改结果待核对', applying_unknown: '原修改结果待核对' };
  const close = () => { if (!busy) onClose(); };
  return <Drawer.Root open onOpenChange={open => { if (!open) close(); }} shouldScaleBackground={false}><Drawer.Portal><Drawer.Overlay className="pw-overlay" /><Drawer.Content className="pw-drawer pw-memory-draft-drawer" aria-describedby="memory-draft-description">
    <div className="pw-drawer-handle" /><header className="pw-drawer-header"><div><Drawer.Title>{title}</Drawer.Title><Drawer.Description id="memory-draft-description">核对原内容、来源与影响后，再确认这一次修改。</Drawer.Description></div><IconButton icon={RiCloseLine} aria-label="返回原回答" disabled={busy} onClick={close} /></header>
    <div className="pw-drawer-body pw-memory-draft-review pw-knowledge-workspace">{busy && <ShimmerText>正在核对原提案…</ShimmerText>}{error && <p role="alert">{error}</p>}{apply.status && <p role="status">{status[apply.status] || '原提案状态待核对'}</p>}{apply.message && <p>{apply.message}</p>}
      {proposal.reason && <p>{proposal.reason}</p>}{review && <>
        {assertion && <p className="pw-knowledge-status">主体：{assertionSubject} · {assertionKind} · 未独立核实</p>}
        <div className="pw-memory-draft-comparison">
        {review.before && <section className="pw-source-record"><h3>原记忆</h3><p className="pw-reading-copy">{review.before.text}</p><p className="pw-muted">原修订 {review.before.revision}</p></section>}
        {review.after && <section className="pw-source-record"><h3>{action === 'create' ? '拟保存内容' : '纠正后的内容'}</h3><p className="pw-reading-copy">{review.after.text}</p>{review.after.applicability && <p>适用范围：{review.after.applicability}</p>}</section>}
        </div>
        <section className="pw-source-record"><h3>确认后的影响</h3>{action === 'forget' ? <p>遗忘原记忆并处理由同一证据派生的记忆，阻止旧来源被自动重新导入。原聊天消息不会因此删除。</p> : action === 'update' ? <p>保留原来源关系并替换正文；依赖旧内容的上下文会退休，旧断言证据不会冒充新正文的证明。</p> : <p>采用这份待审候选，保存为私人记忆。内容仍保留未核实状态，后续可在记忆管理中纠正或遗忘。</p>}</section>
        {(review.after?.assertion || action === 'forget' && review.before?.assertion) && <Accordion variant="divided" className="pw-compact-disclosures"><AccordionItem id="draft-assertion" title={action === 'forget' ? '将遗忘的主体与依据' : '主体与依据'} description="展开核对陈述、原句、条件与时间">
          {review.after?.assertion && <MemoryAssertionFields readOnly candidate={review.after} />}
          {action === 'forget' && review.before?.assertion && <MemoryAssertionFields readOnly candidate={review.before} title="将遗忘的主体与依据" />}
        </AccordionItem></Accordion>}
        <section className="pw-source-record pw-memory-draft-sources"><h3>本次提案来源</h3><p className="pw-muted">这是助手根据已准备的主人消息整理的提案；接受保存不会把归纳内容变成已独立核实的事实。</p><div className="pw-action-row">{list(review.sourceRefs).map((ref, index) => <Button key={ref.sourceId + ':' + ref.revision} variant="ghost" disabled={busy || attempted || !handle.current} onClick={() => { void request('memoryDraft.source', { handle: handle.current, index, offset: 0 }, setSource); }}>来源 {index + 1} · 修订 {ref.revision}</Button>)}</div>
          {source && <><h4>{source.title}</h4><pre className="pw-source-copy" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{source.text}</pre><p className="pw-muted">已显示原文 {source.offset}–{source.nextOffset} / {source.totalChars} 字符</p>{source.hasMore && <Button variant="ghost" disabled={busy || attempted} onClick={() => { void request('memoryDraft.source', { handle: handle.current, index: source.index, offset: source.nextOffset }, setSource); }}>继续读取原文</Button>}</>}
        </section>

      </>}{canReopen ? <Button variant="secondary" disabled={busy} onClick={() => { void request('memoryDraft.open', original, result => { handle.current = result.handle || ''; setValue(result); setSource(null); setAcknowledged(false); setAttempted(false); }); }}>重新打开审阅</Button> : (!pending || attempted || error) && <Button variant="ghost" disabled={busy} onClick={() => { void request('memoryDraft.query', original, result => { handle.current = ''; setValue(result); setSource(null); setAcknowledged(false); }); }}>查询原修改结果</Button>}
    </div>
        {pending && <section className="pw-memory-draft-confirmation"><CheckboxField isSelected={acknowledged} onChange={setAcknowledged}>我已核对正文、来源与以上影响</CheckboxField><div className="pw-action-row"><Button disabled={busy || !acknowledged} onClick={() => { const id = handle.current; setAttempted(true); void request('memoryDraft.confirm', { handle: id }, result => { handle.current = ''; setValue({ proposal, apply: result }); }); }}>确认{action === 'forget' ? '遗忘' : action === 'update' ? '纠正' : '保存'}</Button><Button variant="secondary" disabled={busy} onClick={() => { const id = handle.current; setAttempted(true); void request('memoryDraft.cancel', { handle: id }, result => { handle.current = ''; setValue(result); }); }}>放弃这个原提案</Button></div></section>}
    </Drawer.Content></Drawer.Portal></Drawer.Root>;
}
