import { Button } from './forms-library';
import { ProgressCircle } from './library';
import { MotionFeedback } from './library/journey-motion';
import { IconAttention, IconCheckCircle, IconChevronRight, IconClock, IconError, IconFileText, IconHistory, IconInfo, IconPauseCircle, IconReview, IconStop } from './icons';
import { getRecentTaskProgress } from './task-presentation';
import { workCollectionPresentation, type WorkCollectionTask } from './work-collection';
import { domainLabel, homeDomainResult, homeWorkIdentity } from './butler-work-entry';

export type WorkEntry = 'overview' | 'review' | 'process';
type Reading = { label: string; entry: WorkEntry };
type Kind = 'decision' | 'paused' | 'failed' | 'queued' | 'active' | 'handling' | 'reported' | 'saved' | 'ended' | 'stopped' | 'unread';
type Fact = { name: string; text: string };
const asText = (value: unknown) => typeof value === 'string' ? value.trim() : '';
const limit = (value: string) => Array.from(value).slice(0, 360).join('');
const overview = (label: string): Reading => ({ label, entry: 'overview' });
const process = (label: string): Reading => ({ label, entry: 'process' });
const marker = { decision: IconAttention, paused: IconPauseCircle, failed: IconError, queued: IconClock, active: IconHistory,
  handling: IconReview, reported: IconCheckCircle, saved: IconFileText, ended: IconHistory, stopped: IconStop, unread: IconInfo };

/** Which original to read, never which operation to perform. Shared Work classification
 * owns membership, and the native original still owns its review, continuation and result. */
export function readFollowUp(task: WorkCollectionTask, canReview: boolean, canContinue: boolean) {
  const view = workCollectionPresentation(task), raw = task.raw, owner = view.handling;
  const readable = raw?.id === task.id && raw.actorId === 'owner' && raw.spaceId === 'private' && raw.kind !== 'inference' && raw.contextAvailable !== false
    && raw.taskProjection !== 'source_unavailable' && raw.sourceCurrentReadRequired !== true;
  const original = readable ? homeWorkIdentity(task) : null;
  const domain = original ? domainLabel[original.domain] : '';
  const nativeResult = original ? homeDomainResult(task, original.domain) : '';
  const approval = view.pendingApproval || ['waiting_approval', 'awaiting_approval', 'waiting_permission', 'awaiting_permission'].includes(task.status);
  const ownerInput = ['awaiting_user', 'waiting_user', 'needs_confirmation'].includes(task.status);
  const review = (label: string): Reading => canReview ? { label, entry: 'review' } : overview('查看原工作');
  const result = (kind: Kind, label: string, next: Reading, reason = '') => ({
    task, group: view.group, kind, label, next, readable, domain, ownerRequest: kind === 'decision' && (approval || ownerInput), reason: readable ? limit(asText(reason)) : '',
    goal: readable ? asText(task.goal) || '未命名工作' : '原工作来源不可用',
    busy: readable && view.busy && (kind === 'active' || kind === 'handling'),
    // A failed or stopped run may still have a saved result or a readable report.
    receipt: !readable ? undefined : owner?.delivered ? overview('查看工作汇报') : owner?.resultAvailable ? overview('查看已保存结果') : undefined,
    resultState: readable && owner ? owner.resultState : '',
  });
  if (!readable) return result('unread', '来源不可用', overview('查看原工作'));
  // An outstanding decision wins even if an execution update has arrived.
  if (view.reconciliation) return result('decision', view.label, review('核对原操作'),
    '先核对上次操作是否发生，不会自动重试。');
  if (ownerInput) return result('decision', view.label, review(view.actionLabel), task.summary);
  if (approval) return result('decision', '等你授权', review('查看授权请求'), task.summary);
  // A result-handling notice must not cover a pending original authorization.
  if (owner?.needsAttention) return result('decision', owner.handlingLabel, review(owner.actionLabel), owner.reason);
  if (view.group === 'running' && (owner?.waitingForChildren || owner?.waitingForButler))
    return result('handling', owner.handlingLabel, overview(owner.waitingForChildren ? '查看协作进展' : '查看结果处理'), owner.reason);
  if (task.status === 'paused' || task.status === 'interrupted' || task.status === 'failed') {
    const failed = task.status === 'failed', resumable = canReview && canContinue && raw.controls?.canContinue === true;
    return result(failed ? 'failed' : 'paused', failed ? '本次未完成' : view.label, resumable ? review('核对后继续')
      : process(failed ? '查看失败记录' : task.status === 'paused' ? '查看暂停位置' : '查看中断记录'));
  }
  if (view.group === 'attention') return result('unread', view.label, overview('查看原工作'));
  if (task.status === 'queued') return result('queued', '已排队', process('查看执行记录'));
  if (view.group === 'running') return result('active', view.label, process('查看执行过程'));
  if (task.status === 'stopped' || task.status === 'cancelled' || task.status === 'canceled')
    return result('stopped', '已停止', process('查看停止记录'));
  if (owner?.delivered) return result('reported', '管家已汇报', overview('查看工作汇报'));
  if (owner?.resultAvailable) return result('saved', '执行结果已保存', overview('查看已保存结果'));
  if (nativeResult) return result('saved', nativeResult, overview('查看原成果'));
  return result('ended', '本次执行已结束', overview('查看结束记录'));
}
export type WorkReading = ReturnType<typeof readFollowUp>;

/** Use one brief for this scope, never a duplicate card. A pending owner decision
 * precedes recoverable work, running work and history, regardless of timestamp. */
export function firstFollowUp(rows: WorkReading[]): WorkReading | undefined {
  return rows.find(v => v.ownerRequest)
    || rows.find(v => v.kind === 'decision')
    || rows.find(v => v.group === 'attention' && v.next.entry === 'review' && (v.kind === 'paused' || v.kind === 'failed'))
    || rows.find(v => v.group === 'running' && v.kind !== 'unread')
    || rows.find(v => v.kind === 'paused' || v.group === 'attention' && v.kind === 'failed')
    || rows.find(v => v.group === 'ended' && v.kind !== 'unread');
}

function recentFact(v: WorkReading): Fact | null {
  if (!v.readable) return null;
  const progress = getRecentTaskProgress(v.task);
  if (progress.source !== 'task' && progress.runId && progress.runId === v.task.raw?.runId) {
    const detail = asText(progress.detail);
    return { name: progress.source === 'operation' ? '本次操作记录' : '本次执行记录',
      text: detail && detail !== progress.title ? progress.title + '：' + detail : progress.title };
  }
  return null;
}
function note(v: WorkReading): Fact | null {
  if (!v.readable) return null;
  const summary = limit(asText(v.task.summary));
  if (v.reason && v.reason !== v.goal) return { name: v.kind === 'decision' ? '待处理事项' : '进展说明', text: v.reason };
  return summary && summary !== v.goal && summary !== v.label ? { name: '工作摘要', text: summary } : null;
}
const when = (at?: number) => {
  if (!at || !Number.isFinite(at) || at <= 0) return '';
  const value = new Date(at);
  return Number.isNaN(value.getTime()) ? '' : value.toLocaleString('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
};
function StateMark({ item }: { item: WorkReading }) {
  const Icon = marker[item.kind];
  return <span className="pw-follow-mark" aria-hidden>{item.busy ? <ProgressCircle aria-label={item.label} isIndeterminate size="sm"/> : <Icon size={18}/>}</span>;
}
type ActionProps = { item: WorkReading; canOpen: boolean; visit: (task: WorkCollectionTask, entry: WorkEntry) => void };

export function WorkBrief({ item, canOpen, visit }: ActionProps) {
  const { task, kind, next } = item, stamp = when(task.updatedAt);
  const context = note(item), record = recentFact(item);
  const facts: Fact[] = [];
  // Name what was actually read. If the two projections quote the same event, show it once.
  if (context && !(record && (record.text === context.text || record.text.endsWith('：' + context.text)))) facts.push(context);
  if (record) facts.push(record);
  const receipt = next.entry !== 'overview' ? item.receipt : undefined;
  const related = receipt || (next.entry === 'review' && record ? process('查看执行记录') : undefined);
  // A terminal run without an attached result is not evidence of successful delivery.
  if (kind === 'ended' && item.resultState && !item.receipt)
    facts.push({ name: '交付情况', text: item.resultState });
  if (!facts.length && (kind === 'active' || kind === 'queued'))
    facts.push({ name: '执行记录', text: kind === 'queued' ? '工作已登记，尚未开始执行。' : '还没有读到本次执行的具体记录。' });
  const intro = kind === 'decision' ? '等你处理' : kind === 'paused' || kind === 'failed' ? '停下的工作'
    : item.group === 'running' ? '正在跟进' : '最近结束';
  return <article className="pw-follow-brief" aria-label={intro} data-motion-key={task.id} data-follow-kind={kind}>
    <header className="pw-follow-heading"><span className="pw-follow-kicker"><StateMark item={item}/>{intro}{item.domain && <span> · {item.domain}</span>}</span>
      {stamp && <time dateTime={new Date(task.updatedAt!).toISOString()} title="工作记录更新时间">{stamp}</time>}</header>
    <h2 dir="auto">{item.goal}</h2>
    <MotionFeedback className="pw-follow-state" as="div" identity={kind + ':' + item.label} initial={false} role="status" aria-atomic="true"><span className="pw-sr-only">{item.goal}：</span>{item.label}</MotionFeedback>
    {!!facts.length && <dl className="pw-follow-facts">{facts.map(fact => <div key={fact.name}>
      <dt>{fact.name}</dt><dd dir="auto">{fact.text}</dd></div>)}</dl>}
    <div className="pw-follow-actions">
      <Button variant={item.group === 'attention' ? 'secondary' : 'ghost'} trailingIcon={IconChevronRight}
        disabled={!canOpen} data-task-id={task.id} data-work-entry={next.entry} data-task-status={task.status}
        aria-label={item.goal + '：' + next.label} onClick={() => visit(task, next.entry)}>{next.label}</Button>
      {related && <Button variant="ghost" disabled={!canOpen} data-task-id={task.id} data-work-entry={related.entry}
        aria-label={item.goal + '：' + related.label} onClick={() => visit(task, related.entry)}>{related.label}</Button>}
    </div>
  </article>;
}

export function WorkQueueRow({ item, canOpen, visit }: ActionProps) {
  const { task, next } = item;
  const detail = note(item), stamp = when(task.updatedAt);
  return <Button variant="ghost" className="pw-task-row pw-follow-row" disabled={!canOpen}
    data-motion-key={task.id} data-task-id={task.id} data-work-entry={next.entry} data-task-status={task.status} data-follow-kind={item.kind}
    aria-label={[item.goal, item.domain, item.label, next.label].filter(Boolean).join('，')} onClick={() => visit(task, next.entry)}>
    <StateMark item={item}/>
    <span className="pw-task-row-copy"><b dir="auto">{item.goal}</b>
      {detail && <span className="pw-task-row-summary" dir="auto">{detail.text}</span>}
      <span className="pw-task-row-meta"><MotionFeedback as="span" identity={item.label} initial={false}>{item.label}</MotionFeedback>
        {item.domain && <span>{item.domain}</span>}<span className="pw-work-next-step">{next.label}</span>{stamp && <time dateTime={new Date(task.updatedAt!).toISOString()}>{stamp}</time>}</span>
    </span><IconChevronRight size={16} aria-hidden/>
  </Button>;
}
