import { calendarTimeAt, validCalendarTime, type CalendarTime } from './calendar-time';
type Data = Record<string, any>;
export type PlanTimingDraft = CalendarTime & { scheduleMode: 'now' | 'calendar' | 'legacy'; delay: string; timingEdited: boolean };
export const planTimingDraft = (at?: number): PlanTimingDraft => ({ ...calendarTimeAt(at || Date.now() + 3600000), scheduleMode: at ? 'calendar' : 'now', delay: '0', timingEdited: false });
export type PlanStepDraft = { goal: string; executor: string; projectId: string; path: string; heading: string; minimumBytes: string; dependencies: Array<{ step: number; outcome: string }>; remoteWaitIndex: string; businessWaitIndex: string };
export const newPlanStep = (dependencies: PlanStepDraft['dependencies'] = []): PlanStepDraft => ({ goal: '', executor: 'phone', projectId: '', path: '', heading: '', minimumBytes: '16', dependencies, remoteWaitIndex: '-1', businessWaitIndex: '-1' });
function integer(value: string, min: number, max: number, label: string): number { const n = Number(value); if (value.trim() === '' || !Number.isSafeInteger(n) || n < min || n > max) throw new Error(label); return n; }
export function planStepInput(draft: PlanStepDraft, index: number, sources: Data = {}): Data {
  if (!draft.goal.trim() || draft.goal.length > 4000) throw new Error('请填写 1–4000 字的步骤目标。');
  if (!['phone', 'workspace'].includes(draft.executor)) throw new Error('请选择真实执行位置。');
  if (new Set(draft.dependencies.map(ref => ref.step)).size !== draft.dependencies.length || draft.dependencies.some(ref => !Number.isInteger(ref.step) || ref.step < 0 || ref.step >= index || !['completed', 'failed', 'cancelled', 'settled'].includes(ref.outcome))) throw new Error('前置条件必须唯一地引用同一目标的更早步骤。');
  if (draft.executor === 'workspace' && (!draft.projectId || !draft.path.trim() || !draft.heading.trim())) throw new Error('请指定原项目、文件路径和用于核验的完整首行。');
  const reference = (value: string, collection: string): Data | 'preserve' | null => {
    const at = integer(value, -2, 100, '请重新选择原前置来源。');
    if (at === -1) return null;
    if (at === -2) return 'preserve';
    const original = sources[collection]?.[at]?.reference;
    if (!original || typeof original !== 'object') throw new Error('原前置来源已不可核对，没有换成其它任务。');
    return structuredClone(original);
  };
  const { remoteWaitIndex, businessWaitIndex, ...input } = draft;
  return { ...input, minimumBytes: integer(draft.minimumBytes, 1, 1000000, '文件最小大小应为 1–1000000 字节。'), remoteWait: reference(remoteWaitIndex, 'remoteWaitCandidates'), businessWait: reference(businessWaitIndex, 'businessWaitCandidates'), dependencies: draft.dependencies.map(ref => ({ ...ref })) };
}
export function planTimingInput(timing: PlanTimingDraft): Data {
  if (timing.scheduleMode === 'now') return { delayMinutes: 0 };
  if (timing.scheduleMode === 'legacy') return { delayMinutes: integer(timing.delay, 0, 525600, '开始时间应为 0–525600 分钟之后。') };
  if (timing.scheduleMode !== 'calendar' || !validCalendarTime(timing)) throw new Error('请选择完整的开始日期、时间与时区。');
  return { date: timing.date, time: timing.time, zone: timing.zone, laterOverlap: timing.laterOverlap };
}
export function planCreateInput(goal: string, timing: PlanTimingDraft | string, steps: PlanStepDraft[], sources: Data = {}): Data {
  if (!goal.trim() || goal.length > 4000 || steps.length < 1 || steps.length > 50) throw new Error('填写完整目标，并保留 1–50 个步骤。');
  if (steps.some(step => step.remoteWaitIndex === '-2' || step.businessWaitIndex === '-2')) throw new Error('新目标必须明确选择现有来源。');
  return { goal, ...(typeof timing === 'string' ? { delayMinutes: integer(timing, 0, 525600, '开始时间应为 0–525600 分钟之后。') } : planTimingInput(timing)), steps: steps.map((step, index) => planStepInput(step, index, sources)) };
}
export function planStepDraft(plan: Data, step: Data, snapshot: Data): PlanStepDraft {
  const rows: Data[] = plan.steps || [], index = rows.findIndex(row => row.id === step.id);
  if (index < 0 || !Array.isArray(step.dependencies)) throw new Error('原步骤与依赖不可核对，没有删除任何条件。');
  const dependencies = step.dependencies.map((ref: Data) => {
    const earlier = rows.findIndex(row => row.id === ref.stepId);
    if (earlier < 0 || earlier >= index || !['completed', 'failed', 'cancelled', 'settled'].includes(ref.outcome)) throw new Error('原前置条件不可核对，不能省略后保存。');
    return { step: earlier, outcome: ref.outcome };
  });
  const candidate = (field: string, collection: string): string => {
    if (!step[field]) return '-1';
    const found = (snapshot[collection] || []).findIndex((row: Data) => JSON.stringify(row.reference) === JSON.stringify(step[field]));
    return String(found < 0 ? -2 : found);
  };
  return { goal: step.goal || '', executor: step.executor || 'phone', projectId: step.verification?.projectId || '', path: step.verification?.path || '', heading: step.verification?.requiredHeading || '', minimumBytes: String(step.verification?.minBytes || 16), dependencies, remoteWaitIndex: candidate('remoteWait', 'remoteWaitCandidates'), businessWaitIndex: candidate('businessWait', 'businessWaitCandidates') };
}
export function planMutationReceipt(value: Data, command: string): Data {
  if (value?.confirmed !== true || value.command !== command || value.result?.command !== command || !Array.isArray(value.snapshot?.plans)) throw new Error('尚未取得这次计划操作的完整原生回执，请核对原目标。');
  return value;
}
export const planState = (value: string) => ({ completed: '目标已按标准完成', finished_with_failures: '本轮结束，有步骤失败', paused: '计划已暂停', cancelled: '计划已取消', review_required: '修改后待审阅', waiting_for_owner_start: '等你开始', waiting_for_original_task: '工作正在处理或等待核对', scheduled: '等待计划时间', ready_for_host_check: '等待启动条件满足', blocked: '需要处理阻塞' } as Data)[value] || '尚未授权执行';

const waitingReasons = new Set(['not_due', 'quiet_or_not_due', 'conditions_blocked', 'remote_wait_pending', 'business_wait_pending']);
const runningReasons = new Set(['bound_task', 'ready']);
const advancingTaskStates = new Set(['queued', 'preparing', 'running', 'observing', 'planning', 'acting', 'verifying', 'pausing', 'stopping', 'cancelling', 'registered']);
const originalTaskLabels: Record<string, string> = {
  queued: '原工作等待执行', preparing: '原工作正在准备', running: '原工作正在执行', observing: '原工作正在观察', planning: '原工作正在整理步骤', acting: '原工作正在执行', verifying: '原工作正在核对结果',
  pausing: '原工作正在暂停', stopping: '原工作正在停止', cancelling: '原工作正在停止', registered: '原工作已登记，等待核对启动条件',
  awaiting_user: '原工作等你补充信息', waiting_user: '原工作等你补充信息',
  awaiting_approval: '原工作等你审阅授权', waiting_approval: '原工作等你审阅授权', awaiting_permission: '原工作等你审阅授权', waiting_permission: '原工作等你审阅授权',
  needs_confirmation: '原工作结果等你核对', unverified_result: '原工作结果等你核对', paused: '原工作已暂停，等你继续', interrupted: '原工作已中断，需要处理',
  reconciling: '原工作操作结果待核对', reconciliation_required: '原工作操作结果待核对', unknown: '原工作操作结果待核对', unavailable: '原工作状态暂不可读取',
  failed: '原工作未完成，请查看原因', cancelled: '原工作已停止，请核对后续步骤', canceled: '原工作已停止，请核对后续步骤', stopped: '原工作已停止，请核对后续步骤', revoked: '原工作正在撤回，结果仍需核对',
};
function planOriginalTask(plan: Data): { attention: boolean; message: string } | null {
  if (plan.state !== 'waiting_for_original_task' && plan.runtimeDecision?.reason !== 'bound_task') return null;
  const stepId = plan.runtimeDecision?.stepId || plan.nextStep?.id;
  const step = Array.isArray(plan.steps) ? plan.steps.find((row: Data) => row.id === stepId) : undefined;
  const unavailable = { attention: true, message: '原工作状态暂不可核对，请打开当前步骤重新读取' };
  if (!step || typeof step.taskId !== 'string' || !step.taskId) return unavailable;
  const observed = step.taskObservation;
  const observedCurrent = observed?.authority === 'TaskStore' && observed.taskId === step.taskId
    && (step.taskRunId === undefined || observed.runId === step.taskRunId)
    && (step.taskEpoch === undefined || observed.coordinatorEpoch === step.taskEpoch);
  // The current TaskStore projection may expose a pending approval or unknown
  // operation even while the retained observation still says running.
  const projected = step.taskStateAuthority === 'TaskStore';
  if (projected && step.taskNeedsApproval === true) return { attention: true, message: '原工作等你审阅具体操作授权' };
  const status = projected && ['unknown', 'unavailable', 'unverified_result', 'revoked', 'registered'].includes(step.taskState) ? step.taskState
    : projected && typeof step.taskStatus === 'string' ? step.taskStatus : observedCurrent ? String(observed.status || '') : '';
  if (!status) return unavailable;
  if (status === 'completed') return { attention: true, message: '原工作已结束，步骤成果仍需核对' };
  return { attention: !advancingTaskStates.has(status), message: originalTaskLabels[status] || '原工作状态仍需核对，请查看当前步骤' };
}
export function planGroup(plan: Data): 'attention' | 'running' | 'scheduled' | 'ended' {
  if (plan.originalTasksUnsettled && plan.state !== 'waiting_for_original_task') return 'attention';
  if (['completed', 'cancelled'].includes(plan.state)) return 'ended';
  if (['finished_with_failures', 'paused', 'review_required', 'waiting_for_owner_start', 'blocked'].includes(plan.state)
      || !plan.execution || plan.approvalValid !== true || (Array.isArray(plan.requirements?.blockers) && plan.requirements.blockers.length > 0)) return 'attention';
  const originalTask = planOriginalTask(plan);
  if (originalTask?.attention) return 'attention';
  const reason = String(plan.runtimeDecision?.reason || '');
  if (reason && !waitingReasons.has(reason) && !runningReasons.has(reason)) return 'attention';
  if (plan.state === 'waiting_for_original_task') return originalTask ? 'running' : 'attention';
  if (['scheduled', 'ready_for_host_check'].includes(plan.state) || waitingReasons.has(reason)) return 'scheduled';
  return 'attention';
}

export function planWaitingReason(plan: Data): string {
  const blockers = Array.isArray(plan.requirements?.blockers) ? plan.requirements.blockers : [];
  if (blockers.length > 0) return blockers.map(String).join('；');
  const originalTask = planOriginalTask(plan);
  if (originalTask?.attention || plan.runtimeDecision?.reason === 'bound_task') return originalTask?.message || '原工作状态暂不可核对，请查看当前步骤';
  const reason = String(plan.runtimeDecision?.reason || '');
  if (!reason) return '';
  return ({
    not_approved: '执行范围尚未确认', plan_paused: '已暂停，等你继续', plan_cancelled: '目标已取消',
    manual_start_required: '等你明确开始', model_consent_required: '需要核对模型可读取的资料', business_source_consent_required: '需要核对消息来源',
    expired: '这一步的有效时间已过', approval_expired: '本次执行范围已到期', expires_before_eligible: '可执行时间晚于当前有效期限',
    not_due: '等待所选开始时间', quiet_or_not_due: '等待开始时间或安静时段结束', conditions_blocked: '等待网络、电量或充电条件满足',
    remote_wait_pending: '等待已有电脑工作结束', remote_wait_unavailable: '电脑工作记录暂不可核对', remote_wait_identity_changed: '原电脑工作身份有变化',
    remote_wait_cancelled: '前置电脑工作已取消', remote_wait_unknown: '前置电脑工作结果待核对', remote_wait_exit_unavailable: '电脑工作退出结果尚不可读取', remote_wait_failed: '前置电脑工作未成功',
    business_wait_pending: '等待所选来源的新消息', business_wait_unavailable: '消息来源暂不可核对', business_wait_retention_gap: '所需的消息记录存在缺口', business_wait_consumed_event_changed: '原消息记录已变化',
    dependency_pending: '等待前置步骤完成', dependency_cancelled: '前置步骤已取消', dependency_expired: '前置步骤已到期', dependency_outcome_mismatch: '前置步骤尚未达到所选结果',
    dependency_definition_missing: '前置条件需要重新核对', dependency_membership_invalid: '前置步骤归属需要核对', dependency_task_state_unavailable: '前置工作状态暂不可核对', dependency_task_not_settled: '前置工作仍在结束或核对',
    other_original_task_unsettled: '其他关联工作仍在结束或核对', other_original_task_state_unavailable: '其他关联工作状态暂不可核对',
    task_bridge_unavailable: '当前执行入口暂不可用', task_admission_budget_exhausted: '本目标的启动次数已用完', admission_unknown: '原启动结果待核对',
    bound_task: '已关联原工作，查看该步骤的进展', existing_task_requires_owner_continue: '需要在原工作中明确继续', ready: '条件已具备，等待原调度处理',
    completed: '这一步已完成', failed: '这一步未完成', cancelled: '这一步已取消', skipped: '这一步未采用',
  } as Record<string, string>)[reason] || '当前条件仍需核对，请查看目标与步骤';
}
