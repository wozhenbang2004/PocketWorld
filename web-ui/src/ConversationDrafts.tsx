import { useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Button } from './forms-library';
import type { ConversationDraftTarget } from './SkillDraftReview';

type Draft = ConversationDraftTarget & { kind: 'skill' | 'memory'; title: string; apply: { status: string } };
export function ConversationDrafts({ taskId, open }: { taskId: string; open: (draft: Draft) => void }) {
  const [drafts, setDrafts] = useState<Draft[] | null>(null), [busy, setBusy] = useState(false), [error, setError] = useState(''), [partial, setPartial] = useState(false);
  const alive = useRef(true), entered = useRef(false);
  useEffect(() => () => { alive.current = false; }, []);
  const load = async () => {
    if (entered.current) return; entered.current = true; setBusy(true); setError('');
    try {
      const result = await bridge.command('conversation.drafts', { taskId });
      if (result.taskId !== taskId || result.bodyIncluded !== false || !Array.isArray(result.drafts)) throw new Error('原回答提案列表尚不可核对。');
      if (alive.current) { setDrafts(result.drafts.filter((draft: Draft) => draft.taskId === taskId && ['skill', 'memory'].includes(draft.kind))); setPartial(result.partial === true || result.unavailableCandidates > 0); }
    } catch (failure) { if (alive.current) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { entered.current = false; if (alive.current) setBusy(false); }
  };
  const status: Record<string, string> = { pending_owner: '待审阅', saved: '已处理', cancelled: '已放弃', rejected: '未保存', unknown: '结果待核对', applying_unknown: '结果待核对' };
  return <section aria-label="这次回答的提案"><Button variant="ghost" disabled={busy} onClick={() => { void load(); }}>{busy ? '正在读取提案…' : drafts ? '刷新原提案状态' : '查看这次回答的记忆与技能提案'}</Button>
    {error && <p role="alert">{error}</p>}{drafts && !drafts.length && <p className="pw-muted">本次读取窗口没有可核对的提案。</p>}
    {drafts?.map(draft => <div className="pw-action-row" key={draft.runId + ':' + draft.operationId}><Button variant="secondary" onClick={() => open(draft)}>{draft.title} · {status[draft.apply.status] || '状态待核对'}</Button></div>)}
    {partial && <p className="pw-muted">这里是原记录中最近可核对的提案窗口；较早或暂不可读的项目没有计作已处理。</p>}
  </section>;
}
