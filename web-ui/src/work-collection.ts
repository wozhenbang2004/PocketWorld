import { ownerWorkPresentation } from './owner-work-presentation';
import { taskNeedsAttention, taskNeedsReconciliation } from './task-presentation';

export type WorkCollectionFilter = 'all' | 'attention' | 'running' | 'ended';
export type WorkCollectionGroup = Exclude<WorkCollectionFilter, 'all'>;
export type WorkCollectionTask = {
  id: string;
  goal: string;
  status: string;
  summary?: string;
  updatedAt?: number;
  raw?: Record<string, any>;
};

const activeStates = new Set(['queued', 'preparing', 'running', 'verifying', 'pausing', 'stopping', 'cancelling']);
const endedStates = new Set(['completed', 'failed', 'cancelled', 'canceled', 'stopped']);
const labels: Record<string, string> = {
  queued: '等待执行', preparing: '准备执行', running: '正在执行', verifying: '核对结果中',
  pausing: '正在暂停', stopping: '正在停止', cancelling: '正在停止',
  waiting_approval: '等待你的授权', awaiting_approval: '等待你的授权',
  waiting_permission: '等待你的授权', awaiting_permission: '等待你的授权',
  needs_confirmation: '结果等你核对', awaiting_user: '等你补充信息', waiting_user: '等你补充信息',
  paused: '已暂停', interrupted: '已中断', completed: '已完成', failed: '本次未完成',
  cancelled: '已停止', canceled: '已停止', stopped: '已停止',
  reconciling: '结果待核对', reconciliation_required: '结果待核对', unknown: '结果待核对',
};

/** Home includes only native independent work and its exact result-handling
 * association. An ordinary assistant reply is not another background work. */
export function isButlerHomeWork(task: WorkCollectionTask): boolean {
  return ownerWorkPresentation(task) !== null;
}

/** One reading classification for the home and durable work directory. This
 * never supplies a control, execution grant or proof of successful completion. */
export function workCollectionPresentation(task: WorkCollectionTask) {
  const raw = task.raw || {}, handling = ownerWorkPresentation(task);
  const active = activeStates.has(task.status), ended = endedStates.has(task.status);
  const identity = raw.workCollectionIdentity;
  const nativeGroup = identity && raw.id === task.id && identity.taskId === task.id
    && identity.runId === String(raw.runId || '') && identity.epoch === Number(raw.coordinatorEpoch || 0)
    && identity.updatedAt === task.updatedAt && identity.status === task.status
    && identity.contextAvailable === (raw.contextAvailable !== false)
    && ['attention', 'running', 'ended'].includes(raw.workCollectionGroup)
    && typeof raw.workCollectionReconciliation === 'boolean'
    ? raw.workCollectionGroup as WorkCollectionGroup : null;
  const reconciliation = ['reconciling', 'reconciliation_required', 'unknown'].includes(task.status)
    || (nativeGroup ? raw.workCollectionReconciliation === true : taskNeedsReconciliation(task));
  const resumableFailure = task.status === 'failed' && raw.executionRole === 'owner_work' && raw.controls?.canContinue === true;
  const pendingApproval = !ended && Array.isArray(raw.approvals) && raw.approvals.some((approval: Record<string, any> | null) =>
    approval && ['pending', 'waiting'].includes(String(approval.state || approval.status || '')));
  const attention = reconciliation || handling?.needsAttention === true || resumableFailure
    || pendingApproval || (!nativeGroup && taskNeedsAttention(task)) || task.status === 'awaiting_permission' || !active && !ended;
  // Re-evaluate current handling/controls even for a paged row. Only the exact
  // native operation distinction is borrowed; a stale group cannot hide a new decision.
  const group: WorkCollectionGroup = attention ? 'attention' : active || handling?.waitingForButler ? 'running' : 'ended';
  const label = reconciliation ? '结果待核对' : handling && raw.contextAvailable === false ? handling.handlingLabel
    : handling?.needsAttention ? handling.handlingLabel : resumableFailure ? '本次未完成，可继续处理'
    : handling && ended && !['none', 'unread'].includes(handling.state) ? handling.handlingLabel
    : group === 'attention' && active ? '需要你处理' : labels[task.status] || '状态待核对';
  const actionLabel = reconciliation ? '核对操作' : handling?.needsAttention ? '核对管家汇报'
    : task.status === 'needs_confirmation' ? '核对结果'
    : ['waiting_approval', 'awaiting_approval', 'waiting_permission', 'awaiting_permission'].includes(task.status) ? '处理授权'
    : ['awaiting_user', 'waiting_user'].includes(task.status) ? '补充信息'
    : task.status === 'failed' ? '查看原因' : ['paused', 'interrupted'].includes(task.status) ? '继续处理'
    : group === 'attention' ? '查看待办' : task.status === 'queued' ? '查看排队'
    : group === 'running' ? '查看进展' : handling?.resultAvailable || handling?.delivered ? '查看结果' : '查看详情';
  return { group, label, actionLabel, handling,
    busy: group === 'running' && (active && task.status !== 'queued' || handling?.state === 'running') };
}

export function matchesWorkFilter(task: WorkCollectionTask, filter: WorkCollectionFilter): boolean {
  return filter === 'all' || workCollectionPresentation(task).group === filter;
}
