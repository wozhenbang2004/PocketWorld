import { useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Accordion, AccordionItem, Button } from './forms-library';
import { RiRefreshLine } from '@remixicon/react';
import type { ConversationDraftTarget } from './SkillDraftReview';

type Draft = ConversationDraftTarget & { kind: 'skill' | 'memory'; title: string; apply: { status: string } };
export function ConversationDrafts({ taskId, open, refreshRevision = 0 }: { taskId: string; open: (draft: Draft) => void; refreshRevision?: number }) {
  const [drafts, setDrafts] = useState<Draft[] | null>(null), [busy, setBusy] = useState(false), [error, setError] = useState(''), [partial, setPartial] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const alive = useRef(true), entered = useRef(false);
  const attemptedRevision = useRef(refreshRevision);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  const load = async () => {
    if (entered.current) return; entered.current = true; attemptedRevision.current = refreshRevision; setBusy(true); setError('');
    try {
      const result = await bridge.command('conversation.drafts', { taskId });
      if (result.taskId !== taskId || result.bodyIncluded !== false || !Array.isArray(result.drafts)) throw new Error('原回答提案列表尚不可核对。');
      if (alive.current) { setDrafts(result.drafts.filter((draft: Draft) => draft.taskId === taskId && ['skill', 'memory'].includes(draft.kind))); setPartial(result.partial === true || result.unavailableCandidates > 0); }
    } catch (failure) { if (alive.current) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { entered.current = false; if (alive.current) setBusy(false); }
  };
  useEffect(() => {
    if (busy || attemptedRevision.current === refreshRevision) return;
    if (expanded) void load();
    else { attemptedRevision.current = refreshRevision; setDrafts(null); }
  }, [refreshRevision, busy, expanded]);
  const status: Record<string, string> = { pending_owner: '待审阅', saved: '已处理', cancelled: '已放弃', rejected: '未保存', unknown: '结果待核对', applying_unknown: '结果待核对' };
  return <section className="pw-conversation-drafts" aria-label="这次回答的提案">
    <Accordion variant="divided" expandedKeys={expanded ? ['drafts'] : []} onExpandedChange={keys => {
      const next = keys.has('drafts'); setExpanded(next);
      // Disclosure only reads the original proposal window. It never applies a draft.
      if (next && drafts === null && !busy) void load();
    }}><AccordionItem id="drafts" title="记忆与技能提案" description={drafts ? `${drafts.length} 项已读取${partial ? ' · 部分记录' : ''}` : undefined}>
      {busy && <p className="pw-muted" role="status">正在读取原提案…</p>}
      {error && <p className="pw-error" role="alert">{error}</p>}{drafts && !drafts.length && <p className="pw-muted">本次读取窗口没有可核对的提案。</p>}
      <div className="pw-conversation-draft-list">{drafts?.map(draft => <Button variant="ghost" key={draft.runId + ':' + draft.operationId} onClick={() => open(draft)}><span className="pw-conversation-draft-copy"><b>{draft.title}</b><small>{status[draft.apply.status] || '状态待核对'}</small></span></Button>)}</div>
      {partial && <p className="pw-muted">这里是原记录中最近可核对的提案窗口；较早或暂不可读的项目没有计作已处理。</p>}
      <Button variant="ghost" size="small" leadingIcon={RiRefreshLine} disabled={busy} onClick={() => { void load(); }}>{error ? '重新读取提案' : '刷新原提案状态'}</Button>
    </AccordionItem></Accordion>
  </section>;
}
