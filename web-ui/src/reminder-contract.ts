import { calendarTimeAt } from './calendar-time';
type Data = Record<string, any>;
export type ReminderIntent = 'create' | 'edit' | 'reschedule';
export type ReminderDraft = {
  title: string; phone: boolean; goal: string; type: string; minutes: string; date: string; time: string; zone: string;
  weekdays: number[]; monthDay: string; lastDay: boolean; skipGap: boolean; laterOverlap: boolean;
  cron: string; misfire: string; overlap: string; maxCatchUp: string;
};
export type ReminderSubmission = { requestId: string; command: string };
const scheduleTypes = ['once', 'interval', 'daily', 'weekly', 'monthly', 'cron'];
export const reminderDeleteWarning = '删除会移除此提醒及其全部投递记录，并取消相关通知。要保留原记录请改用暂停。此操作不会重放原任务。';
export function reminderDraft(rule?: Data | null, intent: ReminderIntent = 'create', zone = 'UTC'): ReminderDraft {
  const schedule = intent === 'edit' ? rule?.schedule || {} : {};
  const policy = intent === 'edit' ? rule?.policy || {} : {};
  const once = calendarTimeAt(schedule.type === 'once' && Number.isFinite(schedule.at) ? schedule.at : Date.now() + 30 * 60000, schedule.zone || zone);
  return {
    title: rule?.title || '', phone: rule?.kind === 'phone_goal', goal: rule?.goal || '',
    type: schedule.type || 'once', minutes: schedule.type === 'interval' ? String(schedule.intervalMillis / 60000) : '30',
    date: once.date, time: schedule.time || ((schedule.type || 'once') === 'once' ? once.time : '09:00'), zone: schedule.zone || zone,
    weekdays: Array.isArray(schedule.daysOfWeek) ? [...schedule.daysOfWeek] : [1, 2, 3, 4, 5],
    monthDay: String(schedule.dayOfMonth || 1), lastDay: schedule.shortMonthPolicy === 'last_day',
    skipGap: schedule.dstGap === 'skip', laterOverlap: schedule.dstOverlap === 'later', cron: schedule.expression || '0 9 * * 1-5',
    misfire: policy.misfire || 'skip', overlap: policy.overlap || 'buffer_one', maxCatchUp: String(policy.maxCatchUp || 3),
  };
}
export function reminderTiming(draft: ReminderDraft): string {
  return JSON.stringify([draft.type, draft.minutes, draft.date, draft.time, draft.zone, [...draft.weekdays].sort((a, b) => a - b), draft.monthDay, draft.lastDay, draft.skipGap, draft.laterOverlap, draft.cron]);
}
export function reminderReviewPayload(draft: ReminderDraft, intent: ReminderIntent, original?: Data | null, initialTiming = ''): Data {
  if (!draft.title.trim()) throw new Error('请填写提醒内容。');
  if (draft.phone && !draft.goal.trim()) throw new Error('请填写手机任务目标。');
  if (!scheduleTypes.includes(draft.type)) throw new Error('当前时间安排不支持编辑，请重新核对。');
  const maxCatchUp = Number(draft.maxCatchUp);
  if (!Number.isInteger(maxCatchUp) || maxCatchUp < 1 || maxCatchUp > 20) throw new Error('补处理次数应为 1 至 20。');
  const payload: Data = { intent, draft: { ...draft, weekdays: [...draft.weekdays].sort((a, b) => a - b),
    monthDay: Number(draft.monthDay), maxCatchUp,
    preserveSchedule: intent === 'edit' && reminderTiming(draft) === initialTiming,
  } };
  if (intent !== 'create') {
    if (!original?.id || !Number.isInteger(original.revision)) throw new Error('原提醒身份不可用，请刷新。');
    payload.id = original.id; payload.revision = original.revision;
  }
  return payload;
}
export function reminderRuleState(rule: Data, now: number) {
  const schedule = rule.schedule || {};
  const exhausted = schedule.type === 'once' && rule.enabled !== true &&
    (Number(schedule.at) <= now || Number(rule.onceClaimedAt ?? -1) >= Number(schedule.at) || rule.onceClaimedAt === undefined && Number(rule.claimedCount) > 0);
  const sourceCurrent = rule.sourceInvalidated !== true && rule.sourceCheckPending !== true;
  const editable = scheduleTypes.includes(schedule.type) && (schedule.scheduleVersion === undefined || schedule.scheduleVersion === 1);
  return { exhausted, sourceCurrent, editable, canEdit: sourceCurrent && editable,
    canToggle: rule.sourceInvalidated !== true && !exhausted && (rule.enabled === true || sourceCurrent && editable),
    canReschedule: exhausted && sourceCurrent && !Object.hasOwn(rule, 'nativeOrigin') && rule.manualOriginVersion === 1,
  };
}

const deliveryStates: Record<string, string> = {
  notified: '已向系统提交通知', awaiting_user: '已向系统提交通知', blocked_notifications: '通知未能发出',
  delivery_unknown: '是否发出仍待核对', dispatching: '正在投递，尚无回执', deferred: '已延后投递',
  expired: '已过期，未投递', missed_window: '错过有效时间，未投递', missed_schedule: '已跳过错过的安排',
  coalesced: '已合并到另一次安排', overlap_skipped: '与原任务重叠，已跳过', cancelled: '已取消',
  budget_exhausted: '次数已用完，未投递',
};
const phoneStates: Record<string, string> = {
  running: '手机任务执行中', failed: '手机任务执行失败', cancelled: '手机任务已取消', paused: '手机任务已暂停',
  stopping: '手机任务正在停止', reconciling: '原任务结果仍在核对', waiting_user: '原任务需要你处理',
  awaiting_user: '原任务需要你处理', awaiting_authorization: '原任务等待核对或授权', unverified_result: '原任务已结束，结果尚未核实',
};
type OccurrenceAction = { kind: 'prepare'; label: string } | { kind: 'open'; label: string; taskId: string; round?: { expectedRunId: string; expectedEpoch: number } };
type Occurrence = { delivery: string; task?: string; action?: OccurrenceAction; attention: boolean; sourceHeld?: boolean };

/** Delivery describes that notification, while goalState is a fresh, original-occurrence TaskStore read. Neither authorizes execution. */
export function reminderOccurrence(receipt: Data, rule: Data | undefined, now: number): Occurrence {
  const delivery = Object.hasOwn(deliveryStates, receipt.status) ? deliveryStates[receipt.status] : '投递状态待核对';
  const transportIssue = receipt.status === 'delivery_unknown' || receipt.status === 'blocked_notifications';
  const view: Occurrence = { delivery, attention: transportIssue };
  if (receipt.sourceInvalidated || rule?.sourceInvalidated) return { delivery: '原提醒来源已失效', sourceHeld: true, attention: false };
  if (!rule || rule.id !== receipt.ruleId || receipt.sourceCheckPending || rule.sourceCheckPending)
    return { delivery: '原提醒来源待核对', sourceHeld: true, attention: false };
  if (receipt.kind !== 'phone_goal') return view;

  const goal = receipt.goalState;
  if (!receipt.id || !goal || goal.authority !== 'TaskStore' || goal.executionAuthorized !== false || goal.occurrenceId !== receipt.id ||
      ['evidence_mismatch', 'host_evidence_unavailable'].includes(goal.state)) {
    return { ...view, task: '本次手机任务记录尚未核对', attention: transportIssue || receipt.status === 'awaiting_user' };
  }
  if (typeof goal.taskId === 'string' && goal.taskId.trim() && goal.taskId.length <= 240 && typeof goal.taskStatus === 'string' && goal.taskStatus) {
    const verified = goal.state === 'succeeded' && goal.taskStatus === 'completed' &&
      ['owner_confirmation', 'task_result'].includes(goal.evidenceRef?.kind) && typeof goal.evidenceRef?.id === 'string' && !!goal.evidenceRef.id.trim();
    const round = typeof goal.runId === 'string' && goal.runId.trim() && goal.runId.length <= 80 &&
      Number.isInteger(goal.coordinatorEpoch) && goal.coordinatorEpoch > 0 && goal.coordinatorEpoch <= 2147483647
      ? { expectedRunId: goal.runId, expectedEpoch: goal.coordinatorEpoch as number } : undefined;
    const task = verified ? '手机任务已确认完成' : Object.hasOwn(phoneStates, goal.state) ? phoneStates[goal.state] : '原任务结果待核对';
    // Prepared tasks do not yet have an execution round. Open the original task,
    // never claim a particular execution result without a checked round.
    return { delivery, task, action: { kind: 'open', taskId: goal.taskId, round, label: verified && round ? '查看这次任务结果' : '查看原手机任务' },
      attention: receipt.status === 'delivery_unknown' || !verified && !['running', 'cancelled', 'stopping'].includes(goal.state) };
  }
  if (goal.taskId !== undefined || goal.state === 'succeeded') return { ...view, task: '原任务身份尚未核对', attention: true };
  if (goal.state !== 'awaiting_user' || !['awaiting_user', 'blocked_notifications'].includes(receipt.status))
    return { ...view, task: '未读取到本次手机任务' };
  if (rule.revision !== receipt.revision || rule.taskRequestsRevoked === true || rule.kind !== 'phone_goal' || rule.goal !== receipt.goal)
    return { ...view, task: '原安排已变化，不能从这次记录准备任务', attention: false };
  if (rule.enabled !== true && rule.schedule?.type !== 'once') return { ...view, task: '提醒已暂停，不能准备本次任务', attention: false };
  if (!Number.isFinite(receipt.validUntil) || !Number.isFinite(receipt.occurrenceAt) || now < receipt.occurrenceAt)
    return { ...view, task: '本次任务的有效时间待核对' };
  if (now >= receipt.validUntil) return { ...view, task: '已过有效时间，未读取到本次手机任务', attention: false };
  return { ...view, task: '尚未准备手机任务，准备后仍需你确认开始', action: { kind: 'prepare', label: '准备手机任务（不执行）' }, attention: true };
}

/** An accepted bridge receipt is not a saved rule. Only the matching original feature result is. */
export function reminderSubmissionResult(state: Data, submission: ReminderSubmission): { kind: 'waiting' | 'failed' | 'saved' | 'updated' | 'deleted'; message: string } {
  const key = 'automation.' + submission.command;
  if ((state.moduleBusy || []).includes(key) || state.moduleResultInputs?.[key]?._requestId !== submission.requestId) return { kind: 'waiting', message: '请求已登记，等待原操作回执。' };
  if (state.moduleErrors?.[key]) return { kind: 'failed', message: String(state.moduleErrors[key]) + ' 请先刷新核对原记录，勿重复新建。' };
  const result = state.moduleResults?.[key] || {};
  if (submission.command === 'save' && result.saveReceipt?.state === 'saved') {
    const receipt = result.saveReceipt;
    const confirmed = receipt.alarmReplan === 'api_accepted' && receipt.changePublication === 'emitted' && receipt.ruleReadback === 'same_revision' && ['current', 'not_applicable'].includes(receipt.sourceState);
    return { kind: 'saved', message: confirmed ? '提醒已保存，实际投递以原记录为准。' : '提醒已保存；后续调度、来源或状态回传仍需核对。请刷新已有提醒，勿重复新建。' };
  }
  if (submission.command === 'setEnabled' && result.rule?.id) return { kind: 'updated', message: '已收到原提醒启停回执，实际状态以刷新记录为准。' };
  if (submission.command === 'delete' && result.deleted === true) return { kind: 'deleted', message: '已收到原提醒及其投递记录的删除回执。' };
  return { kind: 'failed', message: '原操作结果尚未确认。请刷新核对已有提醒，勿重复提交。' };
}
