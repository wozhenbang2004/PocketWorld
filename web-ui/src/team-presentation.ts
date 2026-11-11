type TeamRecord = Record<string, any>;

const records = (value: unknown): TeamRecord[] => Array.isArray(value) ? value : [];
const text = (value: unknown): string => typeof value === 'string' ? value : '';

/** Labels describe stored coordinator facts; these helpers never authorize work. */
export function teamStatusLabel(value: unknown): string {
  return ({
    queued: '等待开始', running: '进行中', completed: '本轮已完成', partial: '部分分工未完成', paused: '已暂停',
    awaiting_review: '等待审阅', awaiting_input: '等待回答', awaiting_user: '需要处理', awaiting_approval: '等待授权',
    waiting_dependency: '等待前置分工', blocked_dependency: '前置分工未完成', blocked_unknown: '操作结果待核对',
    needs_confirmation: '结果待确认', reconciling: '正在核对结果', failed: '未完成', exited: '已退出',
    blocked: '需要处理阻碍', unsupported: '当前执行方式不支持',
    cancelled: '已停止', interrupted: '已中断', stopping: '正在停止', unknown: '状态待核对',
  } as Record<string, string>)[text(value)] || '状态待核对';
}

export function teamPhaseLabel(value: unknown): string {
  return ({
    queued: '准备分工', members: '成员正在推进', review: '核对成员产出', synthesis: '整理共同结论',
    work_items: '按工作计划推进', intervention: '新要求待继续', member_configuration: '成员已调整，等待继续',
    awaiting_member: '等待加入成员', waiting_member: '等待成员完成', inconclusive: '结论仍有分歧',
  } as Record<string, string>)[text(value)] || '';
}

export function teamGoalTitle(value: unknown, fallback = '这次协作'): string {
  const firstLine = text(value).trim().split('\n')[0];
  return firstLine ? firstLine.length > 72 ? firstLine.slice(0, 72) + '…' : firstLine : fallback;
}

export function teamRoleLabel(run: TeamRecord): string {
  return run.mode === 'produce' || run.workPlan ? '默认收件人' : '负责汇总';
}

export const teamNeedsAttention = (value: unknown): boolean => [
  'awaiting_review', 'awaiting_input', 'awaiting_user', 'awaiting_approval', 'needs_confirmation',
  'blocked_unknown', 'unknown', 'reconciling', 'blocked_dependency', 'blocked', 'unsupported', 'failed', 'partial', 'interrupted',
].includes(text(value));

export const teamHasEnded = (value: unknown): boolean => ['completed', 'cancelled'].includes(text(value));

export const teamCanPause = (value: unknown): boolean => [
  'queued', 'running', 'awaiting_approval', 'awaiting_input', 'awaiting_user', 'waiting_dependency', 'reconciling',
].includes(text(value));

export const teamCanResume = (value: unknown): boolean => [
  'paused', 'interrupted', 'failed', 'partial', 'blocked_dependency',
].includes(text(value));

export function teamTimeLabel(value: unknown): string {
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0) return '';
  return new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(value);
}

export type TeamAttentionItem = {
  id: string; memberId: string; workItemId?: string; taskId?: string;
  title: string; detail: string; status: string;
};

export function teamAttentionItems(run: TeamRecord | null, nativeAttention: unknown): TeamAttentionItem[] {
  if (!run || run.status === 'cancelled') return [];
  const workItems = records(run.workPlan?.items);
  const members = records(run.members);
  return records(nativeAttention).filter(item => item.kind !== 'question').map(item => {
    const memberId = text(item.memberId), member = members.find(row => row.id === memberId);
    const workItem = workItems.find(row => row.workItemId === item.workItemId);
    return {
      id: text(item.id) || [memberId, text(item.workItemId), text(item.assignmentId), text(item.childKind), item.round ?? '', text(item.taskId)].join(':'), memberId,
      ...(item.workItemId ? { workItemId: text(item.workItemId) } : {}),
      taskId: text(item.taskId),
      title: text(item.title || workItem?.title || member?.assignment || member?.role) || '查看这项分工',
      detail: [text(item.memberName || member?.name), teamStatusLabel(item.status)].filter(Boolean).join(' · '),
      status: text(item.status),
    };
  });
}

export function teamProgressLabel(run: TeamRecord): string {
  const total = run.totalWorkItems, completed = run.completedWorkItems;
  if (typeof total !== 'number' || typeof completed !== 'number' || total <= 0 || completed < 0 || completed > total) return '';
  return `已完成 ${completed} / ${total} ${run.workPlan ? '项分工' : '个成员阶段'}`;
}
