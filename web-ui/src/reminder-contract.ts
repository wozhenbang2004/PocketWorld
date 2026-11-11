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
