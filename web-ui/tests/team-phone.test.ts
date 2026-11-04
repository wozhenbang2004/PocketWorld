import assert from 'node:assert/strict';
import test from 'node:test';
import { teamEditorMember, teamPhoneChoice, teamPhoneDevice, teamPhoneSelections, teamPhoneTargetKey } from '../src/team-phone.ts';

const now = 1_700_000_000_000;
const option = { optionId: 'scope-a', available: true, toolBudgetMax: 20, modelBudgetMax: 12, expiresAtMax: now + 15 * 60_000 };
const member = { memberId: 'a', name: '查询', options: [option] };
test('editing a phone member preserves its target, assignment and dependencies as independent drafts', () => {
  const original = { id: 'a', name: '查询', role: '查订单', runner: 'phone_agent', device: { kind: 'paired_android', targetDeviceId: 'paired-a' }, assignment: '核对今天的订单', dependsOn: ['b'], phoneScope: { authority: 'never forwarded' } };
  const draft = teamEditorMember(original); draft.device.targetDeviceId = 'paired-b'; draft.dependsOn.push('c');
  assert.equal(original.device.targetDeviceId, 'paired-a'); assert.deepEqual(original.dependsOn, ['b']); assert.equal(draft.assignment, original.assignment); assert.equal(draft.phoneScope, undefined);
  assert.equal(teamEditorMember({ id: 'b', name: '分析', role: '比较结果' }).runner, 'model');
});
test('targets retain exact paired identity and only configuration fields leave presentation', () => {
  const target = { kind: 'paired_android', targetDeviceId: 'paired-a', label: '工作手机', available: true, permission: 'not authority' };
  assert.equal(teamPhoneTargetKey(target), 'paired_android:paired-a'); assert.deepEqual(teamPhoneDevice(target), { kind: 'paired_android', targetDeviceId: 'paired-a' });
  assert.equal(teamPhoneTargetKey({ kind: 'local_phone', screenMode: 'main_screen' }), '');
});
test('review uses the selected member scope and exact integer budgets and deadline', () => {
  assert.deepEqual(teamPhoneSelections({ members: [member] }, { a: teamPhoneChoice(option, now) }, now), [{ memberId: 'a', optionId: 'scope-a', toolBudget: 20, modelBudget: 12, expiresAt: now + 10 * 60_000 }]);
  assert.throws(() => teamPhoneSelections({ members: [member] }, {}, now), /选择/);
  assert.throws(() => teamPhoneSelections({ members: [member, { ...member, memberId: 'b', options: [{ ...option, optionId: 'scope-b' }] }] }, { a: teamPhoneChoice(option, now), b: teamPhoneChoice(option, now) }, now), /选择/);
});
test('stale, unavailable and excessive choices cannot be submitted as a phone review', () => {
  const choice = teamPhoneChoice(option, now);
  for (const change of [{ toolBudget: '21' }, { modelBudget: '1.5' }, { durationMinutes: '16' }, { durationMinutes: '0' }]) assert.throws(() => teamPhoneSelections({ members: [member] }, { a: { ...choice, ...change } }, now));
  assert.throws(() => teamPhoneSelections({ members: [{ ...member, options: [{ ...option, available: false }] }] }, { a: choice }, now));
  assert.throws(() => teamPhoneSelections({ members: [member] }, { a: choice }, now + 6 * 60_000), /有效期/);
});
