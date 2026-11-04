import test from 'node:test';
import assert from 'node:assert/strict';
import { reminderDeleteWarning, reminderDraft, reminderTiming, reminderReviewPayload, reminderRuleState, reminderSubmissionResult } from '../src/reminder-contract.ts';

test('editing content preserves the original schedule identity and policies are not minted by the Web draft', () => {
  const rule = { id: 'original', revision: 4, title: '查看结果', enabled: false, schedule: { type: 'interval', anchorAt: 12345, intervalMillis: 1800000 }, policy: { misfire: 'coalesce', maxOccurrences: 9 } };
  const draft = reminderDraft(rule, 'edit', 'Asia/Shanghai'), timing = reminderTiming(draft);
  draft.title = '更新提醒内容';
  const payload = reminderReviewPayload(draft, 'edit', rule, timing);
  assert.equal(payload.draft.preserveSchedule, true); assert.equal(payload.id, 'original'); assert.equal(payload.revision, 4);
  assert.equal(payload.draft.misfire, 'coalesce'); assert.equal(payload.draft.maxOccurrences, undefined); assert.equal(payload.draft.enabled, undefined);
  draft.minutes = '60'; assert.equal(reminderReviewPayload(draft, 'edit', rule, timing).draft.preserveSchedule, false);
});

test('rescheduling starts a new future timing draft and cannot replay the original occurrence', () => {
  const rule = { id: 'old', revision: 2, title: '开始目标', kind: 'phone_goal', goal: '核对原目标', schedule: { type: 'once', at: 1 }, policy: { maxOccurrences: 1 } };
  const draft = reminderDraft(rule, 'reschedule', 'Asia/Shanghai');
  assert.equal(draft.type, 'once'); assert.equal(draft.minutes, '30'); assert.equal(draft.goal, '核对原目标');
  const payload = reminderReviewPayload(draft, 'reschedule', rule);
  assert.equal(payload.draft.preserveSchedule, false); assert.equal(payload.id, 'old'); assert.equal(payload.draft.at, undefined);
});

test('all native calendar types remain selectable and weekly selection has deterministic identity', () => {
  for (const type of ['once', 'interval', 'daily', 'weekly', 'monthly', 'cron']) {
    const draft = reminderDraft(); draft.title = '真实提醒'; draft.type = type;
    assert.equal(reminderReviewPayload(draft, 'create').draft.type, type);
  }
  const first = reminderDraft(); first.weekdays = [5, 1, 3]; const second = { ...first, weekdays: [1, 3, 5] };
  assert.equal(reminderTiming(first), reminderTiming(second));
  assert.throws(() => reminderReviewPayload({ ...first, title: '' }, 'create'));
  assert.throws(() => reminderReviewPayload({ ...first, title: 'x', phone: true, goal: '' }, 'create'));
  assert.throws(() => reminderReviewPayload({ ...first, title: 'x', maxCatchUp: '21' }, 'create'));
});

test('exhausted one-time reminders offer reschedule but never blindly enable an old occurrence', () => {
  const rule = { enabled: false, schedule: { type: 'once', at: 1000 }, revision: 2, manualOriginVersion: 1 };
  assert.deepEqual(reminderRuleState(rule, 2000), { exhausted: true, sourceCurrent: true, editable: true, canEdit: true, canToggle: false, canReschedule: true });
  assert.equal(reminderRuleState({ ...rule, sourceCheckPending: true }, 2000).canReschedule, false);
  assert.equal(reminderRuleState({ ...rule, nativeOrigin: { taskId: 'original' } }, 2000).canReschedule, false);
  assert.equal(reminderRuleState({ ...rule, manualOriginVersion: undefined }, 2000).canReschedule, false);
  assert.equal(reminderRuleState({ ...rule, schedule: { type: 'daily', scheduleVersion: 99 } }, 2000).canEdit, false);
  assert.equal(reminderRuleState({ ...rule, schedule: { type: 'once', at: 9000 }, claimedCount: 1 }, 2000).canToggle, false);
});

test('only the matching feature receipt can complete a submission and saved-but-unconfirmed remains explicit', () => {
  const submission = { requestId: 'original-request', command: 'save' };
  const state = { moduleResultInputs: { 'automation.save': { _requestId: 'older-request' } }, moduleResults: { 'automation.save': { saveReceipt: { state: 'saved' } } } };
  assert.equal(reminderSubmissionResult(state, submission).kind, 'waiting');
  state.moduleResultInputs['automation.save']._requestId = 'original-request';
  const actual = reminderSubmissionResult(state, submission);
  assert.equal(actual.kind, 'saved'); assert.match(actual.message, /仍需核对/);
  assert.equal(reminderSubmissionResult({ ...state, moduleErrors: { 'automation.save': '回执未知' } }, submission).kind, 'failed');
  assert.equal(reminderSubmissionResult({ ...state, moduleBusy: ['automation.save'] }, submission).kind, 'waiting');
  assert.equal(reminderSubmissionResult({ moduleResultInputs: { 'automation.delete': { _requestId: 'r' } }, moduleResults: { 'automation.delete': { deleted: true } } }, { requestId: 'r', command: 'delete' }).kind, 'deleted');
});

test('delete review discloses original AutomationBook receipt deletion and the preserving alternative', () => {
  assert.match(reminderDeleteWarning, /全部投递记录/); assert.match(reminderDeleteWarning, /改用暂停/);
  assert.doesNotMatch(reminderDeleteWarning, /已经发生的记录仍保留/);
});
