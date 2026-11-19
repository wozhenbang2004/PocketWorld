import { useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Accordion, AccordionItem, Button } from './forms-library';
import { IconRefresh } from './icons';
import type { ConversationDraftTarget } from './SkillDraftReview';

type Draft = ConversationDraftTarget & { kind: 'skill' | 'memory'; action: string; title: string; apply: { status: string }; undoStatus?: string; duplicate?: boolean };
type Directory = { key: string; drafts?: Draft[]; partial?: boolean; error?: string };
const applyLabels: Record<string, string> = { pending_owner: '待审阅', saved: '已处理', cancelled: '已放弃', rejected: '未保存', unknown: '结果待核对', applying_unknown: '结果待核对' };
function draftStatus(draft: Draft) {
  const phase = draft.apply?.status;
  if (draft.kind === 'memory') {
    if (draft.undoStatus !== 'not_requested' && (phase !== 'saved' || draft.action !== 'create')) return '原提案状态待核对';
    switch (draft.undoStatus) {
      case 'reverted': return '这次保存已撤销';
      case 'undoing_unknown': case 'unknown': return '撤销结果待核对';
      case 'rejected': return '撤销未执行，原保存回执保留';
      case 'not_requested': break;
      default: return '原提案状态待核对';
    }
    if (phase === 'rejected' && draft.duplicate === true) return '已有相同内容，本次未重复保存';
    if (phase === 'saved') return draft.action === 'forget' ? '这次已遗忘' : draft.action === 'update' ? '这次已更新' : '这次已保存';
  }
  return applyLabels[phase] || '状态待核对';
}
export function ConversationDrafts({ taskId, open, refreshRevision = 0, reviewingMemory = false }: { taskId: string; open: (draft: Draft) => void; refreshRevision?: number; reviewingMemory?: boolean }) {
  const [expanded, setExpanded] = useState(false), [refresh, setRefresh] = useState(0);
  const [foreground, setForeground] = useState(() => document.visibilityState === 'visible');
  useEffect(() => {
    const changed = () => setForeground(document.visibilityState === 'visible');
    document.addEventListener('visibilitychange', changed); return () => document.removeEventListener('visibilitychange', changed);
  }, []);
  const [directory, setDirectory] = useState<Directory | null>(null);
  const inFlight = useRef<Promise<unknown> | null>(null);
  const visible = foreground && expanded && !reviewingMemory, key = JSON.stringify([taskId, refreshRevision, refresh]);
  const current = visible && directory?.key === key ? directory : null;
  const drafts = current?.drafts, partial = current?.partial, error = current?.error, busy = visible && !current;
  useEffect(() => {
    if (!visible) { setDirectory(null); return; }
    let active = true;
    // Only a visible, previously opened directory of this task rereads on return.
    // Coalesce overlapping revisions; an old success or failure cannot relabel the new window.
    void (async () => {
      const pending = inFlight.current;
      if (pending) await pending.catch(() => {});
      if (!active || document.visibilityState !== 'visible') return;
      const request = bridge.command('conversation.drafts', { taskId }); inFlight.current = request;
      try {
        const result = await request;
        if (result.taskId !== taskId || result.bodyIncluded !== false || !Array.isArray(result.drafts)) throw new Error('原回答提案列表尚不可核对。');
        if (active) setDirectory({ key, drafts: result.drafts.filter((draft: Draft) => draft.taskId === taskId && ['skill', 'memory'].includes(draft.kind)), partial: result.partial === true || result.unavailableCandidates > 0 });
      } catch (failure) {
        if (active) setDirectory({ key, error: failure instanceof Error ? failure.message : String(failure) });
      } finally { if (inFlight.current === request) inFlight.current = null; }
    })();
    return () => { active = false; };
  }, [key, taskId, visible]);
  return <section className="pw-conversation-drafts" data-expanded={expanded} aria-label="这次回答的提案">
    <Accordion variant="divided" expandedKeys={expanded ? ['drafts'] : []} onExpandedChange={keys => {
      const next = keys.has('drafts');
      if (next && !expanded) setRefresh(value => value + 1);
      setExpanded(next);
    }}><AccordionItem id="drafts" title="记忆与技能提案" description={drafts ? `${drafts.length} 项已读取${partial ? ' · 部分记录' : ''}` : undefined}>
      {busy && <p className="pw-muted" role="status">正在读取原提案…</p>}
      {error && <p className="pw-error" role="alert">{error}</p>}{drafts && !drafts.length && <p className="pw-muted">本次读取窗口没有可核对的提案。</p>}
      <div className="pw-conversation-draft-list">{drafts?.map(draft => <Button variant="ghost" key={draft.runId + ':' + draft.operationId} onClick={() => open(draft)}><span className="pw-conversation-draft-copy"><b>{draft.title}</b><small>{draftStatus(draft)}</small></span></Button>)}</div>
      {partial && <p className="pw-muted">这里是原记录中最近可核对的提案窗口；较早或暂不可读的项目没有计作已处理。</p>}
      <Button variant="ghost" size="small" leadingIcon={IconRefresh} disabled={busy} onClick={() => setRefresh(value => value + 1)}>{error ? '重新读取提案' : '刷新原提案状态'}</Button>
    </AccordionItem></Accordion>
  </section>;
}
