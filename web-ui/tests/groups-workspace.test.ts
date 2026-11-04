import test from 'node:test';
import assert from 'node:assert/strict';
import { acceptGroupDelivery, acceptGroupSnapshot, acceptGroupTask, confirmedGroupResult, groupDeliveryLabel, groupPolicyDraft, groupPolicyPayload, groupPresentation, sameGroupVersion } from '../src/groups-workspace.ts';

const reference = { requestId: 'request-a', taskId: 'task-a', groupId: 'group-a', spaceId: 'group:group-a' };
const task = (extra = {}) => ({ taskId: 'task-a', revision: 7, status: 'paused', runId: 'run-a', coordinatorEpoch: 3, ...extra });
const reading = (extra = {}) => ({ reference, known: true, task: task(), ...extra });
const snapshot = (revision: number) => ({ revision, groups: [], requests: [], deliveries: [] });
const group = (extra = {}) => ({ id: 'group-a', spaceId: 'group:group-a', revision: 5, policyEpoch: 'policy-a', enabled: false, visibility: 'requester', grants: [{ capability: 'group.files', resource: 'group:group-a', allowGroupAudience: false }], quota: { dailyTasks: 20, memberDailyTasks: 5, dailyTokens: 40000, maxTokensPerTask: 4000, maxConcurrent: 2, dailyMedia: 4, memberDailyMedia: 1, maxMediaBytes: 20971520 }, ...extra });

test('a queued acknowledgment cannot save a group or replace its snapshot', () => {
  const current = snapshot(8);
  assert.equal(acceptGroupSnapshot(current, { accepted: true, requestId: 'queued' }), current);
  assert.equal(acceptGroupSnapshot(current, snapshot(7)), current);
  assert.equal(acceptGroupSnapshot(current, { ...snapshot(9), requests: undefined }), current);
  assert.equal(acceptGroupSnapshot(current, { ...snapshot(9), revision: '9' }), current);
  assert.equal(acceptGroupSnapshot(current, snapshot(9))?.revision, 9);
  assert.throws(() => confirmedGroupResult({ accepted: true }, 'member.set'));
  assert.throws(() => confirmedGroupResult({ confirmed: true, command: 'policy.set', result: group() }, 'member.set'));
  assert.equal(confirmedGroupResult({ confirmed: true, command: 'member.set', result: group() }, 'member.set').id, 'group-a');
});

test('members, policy and a different group invalidate the old editable version', () => {
  assert.equal(sameGroupVersion(group(), group()), true);
  for (const changed of [group({ id: 'group-b' }), group({ revision: 6 }), group({ policyEpoch: 'policy-b' }), group({ revision: '5' }), undefined]) assert.equal(sameGroupVersion(group(), changed), false);
});

test('task receipts remain on the exact group request and never roll back a revision', () => {
  const latest = reading({ task: task({ revision: 9, status: 'completed' }) });
  assert.equal(acceptGroupTask(reference, latest, reading()), latest);
  for (const key of ['requestId', 'taskId', 'groupId', 'spaceId']) assert.throws(() => acceptGroupTask(reference, null, reading({ reference: { ...reference, [key]: 'other' } })));
  assert.throws(() => acceptGroupTask(reference, null, reading({ known: 'true' })));
  assert.throws(() => acceptGroupTask(reference, null, reading({ task: task({ taskId: 'another' }) })));
  assert.throws(() => acceptGroupTask(reference, null, reading({ task: task({ revision: '7' }) })));
  assert.equal(acceptGroupTask(reference, latest, { reference, known: false }), latest);
  assert.equal(acceptGroupTask(reference, null, { reference, known: false }).known, false);
});

test('a presentation for an older run cannot grant approval or recovery UI to a new run', () => {
  const view = { ...reference, taskRevision: 7, runId: 'run-a', coordinatorEpoch: 3, status: 'paused', approval: { state: 'pending' } };
  assert.equal(groupPresentation(reference, task(), view), view);
  for (const changed of [task({ revision: 8 }), task({ runId: 'run-b' }), task({ coordinatorEpoch: 4 }), task({ status: 'running' })]) assert.equal(groupPresentation(reference, changed, view), null);
  assert.equal(groupPresentation(reference, task(), { ...view, spaceId: 'private' }), null);
});

test('delivery acceptance stays pending and unknown remains query-only', () => {
  const delivery = { ...reference, deliveryId: 'delivery-a', outputId: 'output-a', state: 'accepted' };
  assert.equal(acceptGroupDelivery(reference, delivery, 'delivery-a', 'output-a').state, 'accepted');
  assert.match(groupDeliveryLabel('accepted'), /送达待核对/);
  assert.match(groupDeliveryLabel('unknown'), /只能查询/);
  assert.throws(() => acceptGroupDelivery(reference, { ...delivery, state: 'delivered' }));
  assert.throws(() => acceptGroupDelivery(reference, { ...delivery, state: 'delivered', platformMessageId: ' ' }));
  assert.equal(acceptGroupDelivery(reference, { ...delivery, state: 'delivered', platformMessageId: 'original-message' }).state, 'delivered');
  assert.throws(() => acceptGroupDelivery(reference, delivery, 'another-delivery'));
  assert.throws(() => acceptGroupDelivery(reference, delivery, undefined, 'another-output'));
  assert.throws(() => acceptGroupDelivery(reference, { ...delivery, taskId: 'task-b' }));
});

test('policy editors preserve the original group resource and require explicit integer quotas', () => {
  const original = group(); const before = JSON.stringify(original); const draft = groupPolicyDraft(original);
  const input = groupPolicyPayload(original, draft);
  assert.equal(input.id, 'group-a'); assert.equal(input.revision, 5); assert.equal(input.policyEpoch, 'policy-a');
  assert.equal(input.grants[0].resource, 'group:group-a'); assert.equal(input.grants[0].hours, 24);
  assert.equal(JSON.stringify(original), before);
  for (const value of ['1.1', '1e3', '', '-1', 'NaN', '9007199254740992']) assert.throws(() => groupPolicyPayload(original, { ...draft, quota: { ...draft.quota, maxTokensPerTask: value } }));
  assert.throws(() => groupPolicyPayload(original, { ...draft, hours: '745' }));
  assert.throws(() => groupPolicyPayload(original, { ...draft, quota: { ...draft.quota, memberDailyTasks: '21' } }));
  assert.throws(() => groupPolicyPayload(original, { ...draft, quota: { ...draft.quota, maxTokensPerTask: '40001' } }));
});
