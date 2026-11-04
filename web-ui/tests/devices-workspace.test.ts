import test from 'node:test';
import assert from 'node:assert/strict';
import { DeviceRequestScope, deviceGrantInput, deviceHistoryLabel, deviceHistoryReceipt, deviceReceipt, deviceReview, deviceSnapshot, deviceTaskDetails, deviceTaskRows } from '../src/devices-workspace.ts';

const now = 1_000_000;
const hash = 'a'.repeat(64);
const snapshot = () => ({ format: 'pocketworld.device-workspace.v1', capturedAt: now, local: { receiverEnabled: false }, peers: [], pendingPairings: [] });
const history = () => ({ kind: 'history', viewHandle: 'original-window', view: { taskId: 'parent-a', operationId: 'operation-a', targetDeviceId: 'peer-a', instanceId: 'instance-a', argumentsDigest: hash, resourceBinding: { kind: 'phone', packageNames: ['sample.allowed'] }, readOnly: true, newRun: false, retrySubmitted: false, unknownRefunded: false } });
const query = () => ({ kind: 'historyReceipt', viewHandle: 'original-window', taskId: 'parent-a', operationId: 'operation-a', targetDeviceId: 'peer-a', stopSubmitted: false, receipt: { readOnly: true, newRun: false, retrySubmitted: false, unknownRefunded: false, unknown: true, operationReceipt: { accepted: true, effectState: 'unknown' } } });
const grant = () => ({ kind: 'pair', viewHandle: 'pair-a', view: { grantOptions: [{ optionId: 'scope-a', toolBudgetMin: 1, toolBudgetMax: 12, modelBudgetMin: 0, modelBudgetMax: 3, expiresAtMax: now + 600_000, allowDelegation: true, historyReadMethods: ['operation.get'], historyReadExpiresAtMax: now + 86_400_000, canGrantWholeDisplayCapture: false, resources: [{ kind: 'phone', packageNames: ['sample.allowed'] }] }], originOptions: [{ optionId: 'origin-a' }], forwardingOptions: [{ optionId: 'downstream-a', toolBudgetMax: 4, modelBudgetMax: 1, expiresAtMax: now + 300_000, resourceBinding: { kind: 'phone', packageNames: ['sample.downstream'] } }] } });
const draft = () => ({ optionId: 'scope-a', toolBudget: '8', modelBudget: '2', durationMinutes: '15', historyDays: '7', historyReadMethods: [] as string[], additionalOriginOptionIds: [] as string[], forwardingOptionIds: [] as string[], forwardingToolBudget: '', forwardingModelBudget: '', wholeDisplayCapture: false });

test('a queue acknowledgement, partial snapshot or coerced object cannot become a device receipt', () => {
  assert.equal(deviceSnapshot(snapshot()).local.receiverEnabled, false);
  assert.throws(() => deviceReceipt({ accepted: true, requestId: 'queued' }));
  assert.throws(() => deviceReceipt({ value: [] }));
  for (const invalid of [{ ...snapshot(), local: [] }, { ...snapshot(), capturedAt: '1000' }, { ...snapshot(), peers: [null] }, { ...snapshot(), pendingPairings: undefined }]) assert.throws(() => deviceSnapshot(invalid));
  assert.equal(deviceReceipt({ value: { readOnly: true }, snapshot: snapshot() }).value.readOnly, true);
  assert.throws(() => deviceReceipt({ value: { nativeCallReturned: true }, snapshot: { accepted: true } }));
});

test('native reviews keep exact device fingerprints, task/run identities and closed-result proof', () => {
  const pair = { kind: 'pair', viewHandle: 'pair-a', view: { inviteId: 'invite-a', expiresAt: now, inviterFingerprint: hash, joinerFingerprint: 'b'.repeat(64) } };
  assert.equal(deviceReview(pair), pair);
  assert.throws(() => deviceReview({ ...pair, view: { ...pair.view, joinerFingerprint: hash.slice(0, 16) } }));
  assert.throws(() => deviceReview({ kind: 'grant', viewHandle: 'grant-a', view: { deviceId: 'peer-a' } }));
  const result = { kind: 'result', viewHandle: 'result-a', view: { taskId: 'parent-a', runId: 'run-a', executorEpoch: 3, resultDigest: hash, historicalReviewOnly: true, nativeRunnerExited: true, nativeDispatchClosed: true } };
  assert.equal(deviceReview(result), result);
  for (const change of [{ runId: '' }, { executorEpoch: '3' }, { nativeRunnerExited: false }, { nativeDispatchClosed: false }, { resultDigest: 'partial' }]) assert.throws(() => deviceReview({ ...result, view: { ...result.view, ...change } }));
  const resume = { kind: 'resume', viewHandle: 'resume-a', view: { taskId: 'parent-a', runId: 'run-a', executorEpoch: 3, resumeRequestId: 'request-a', reviewRevision: 9, ownerActionRequired: true, newRun: false, newGrant: false } };
  assert.equal(deviceReview(resume), resume);
  for (const change of [{ resumeRequestId: '' }, { reviewRevision: '9' }, { newRun: true }, { newGrant: true }, { ownerActionRequired: false }]) assert.throws(() => deviceReview({ ...resume, view: { ...resume.view, ...change } }));
});

test('read-only history never accepts another operation or treats a management acknowledgement as completed work', () => {
  const original = deviceReview(history());
  const actual = deviceHistoryReceipt(query(), original);
  assert.equal(actual.receipt.unknown, true);
  assert.match(deviceHistoryLabel(actual.receipt), /未确认/);
  for (const key of ['viewHandle', 'taskId', 'operationId', 'targetDeviceId']) assert.throws(() => deviceHistoryReceipt({ ...query(), [key]: 'another' }, original));
  for (const key of ['newRun', 'retrySubmitted', 'unknownRefunded']) assert.throws(() => deviceHistoryReceipt({ ...query(), receipt: { ...query().receipt, [key]: true } }, original));
  assert.throws(() => deviceReview({ ...history(), view: { ...history().view, argumentsDigest: '' } }));
  const stopped = { ...query(), stopSubmitted: true, receipt: { managementOnly: true, requested: true, stopped: false, unknown: true } };
  assert.equal(deviceHistoryReceipt(stopped, original).stopSubmitted, true);
  assert.match(deviceHistoryLabel(stopped.receipt), /仍待核对/);
  assert.match(deviceHistoryLabel({ managementOnly: true, stopped: true }), /继续查询/);
  assert.match(deviceHistoryLabel({ managementOnly: true, requested: 'true' }), /结果待核对/);
});

test('task discovery and history links preserve the original parent and exact paired device', () => {
  const first = { id: 'first', raw: { origin: { kind: 'paired_device', callerDeviceId: 'peer-a' } } };
  const second = { id: 'second', raw: { origin: { kind: 'paired_device', callerDeviceId: 'peer-b' }, operations: [{ tool: 4 }] } };
  const current = { ...snapshot(), taskLinks: { links: [{ parentTaskId: 'archived-parent', operationId: 'op', targetDeviceId: 'peer-a' }] } };
  assert.deepEqual(deviceTaskRows([first, second], current, 'peer-a').map(task => task.id), ['first', 'archived-parent']);
  assert.deepEqual(deviceTaskRows([first, second], current, 'peer-b').map(task => task.id), ['second']);
  const details = { kind: 'taskDetails', taskId: 'archived-parent', actions: { capturedAt: now }, history: current.taskLinks };
  assert.equal(deviceTaskDetails(details, 'archived-parent'), details);
  assert.throws(() => deviceTaskDetails(details, 'first'));
  assert.throws(() => deviceTaskDetails({ ...details, history: { links: [{ parentTaskId: 'first', operationId: 'op', targetDeviceId: 'peer-a' }] } }, 'archived-parent'));
});

test('grant choices cannot import scopes, duplicate origins, expand downstream budgets or reuse expired bounds', () => {
  const original = grant(), before = JSON.stringify(original);
  const chosen = deviceGrantInput(original, draft(), now);
  assert.equal(chosen.toolBudget, 8); assert.equal(chosen.historyDays, 0); assert.equal(chosen.forwardingToolBudget, 0);
  assert.equal('resources' in chosen, false); assert.equal(JSON.stringify(original), before);
  for (const value of ['', '1.5', '1e2', '-1', '13', '9007199254740992']) assert.throws(() => deviceGrantInput(original, { ...draft(), toolBudget: value }, now));
  for (const changes of [{ optionId: '' }, { additionalOriginOptionIds: ['unknown'] }, { additionalOriginOptionIds: ['origin-a', 'origin-a'] }, { additionalOriginOptionIds: ['origin-a', 4] }, { forwardingOptionIds: ['unknown'] }, { wholeDisplayCapture: true }, { wholeDisplayCapture: 'false' }, { historyReadMethods: ['task.submitGoal'] }]) assert.throws(() => deviceGrantInput(original, { ...draft(), ...changes }, now));
  const forward = { ...draft(), forwardingOptionIds: ['downstream-a'], forwardingToolBudget: '4', forwardingModelBudget: '1' };
  assert.equal(deviceGrantInput(original, forward, now).forwardingModelBudget, 1);
  assert.throws(() => deviceGrantInput(original, { ...forward, forwardingToolBudget: '5' }, now));
  assert.throws(() => deviceGrantInput(original, { ...forward, forwardingModelBudget: '2' }, now));
  assert.throws(() => deviceGrantInput(original, { ...forward, forwardingToolBudget: '0', forwardingModelBudget: '0' }, now));
  const noDelegation = grant(); noDelegation.view.grantOptions[0].allowDelegation = false;
  assert.throws(() => deviceGrantInput(noDelegation, forward, now));
  assert.throws(() => deviceGrantInput(original, draft(), now + 600_001));
  assert.throws(() => deviceGrantInput(original, forward, now + 300_001));
  const expiredHistory = grant(); expiredHistory.view.grantOptions[0].historyReadExpiresAtMax = now - 1;
  assert.throws(() => deviceGrantInput(expiredHistory, { ...draft(), historyReadMethods: ['operation.get'] }, now));
});

test('background, Back, and a replacement window invalidate late invitations without clearing a successor request', async () => {
  const scope = new DeviceRequestScope(); let displayed = '';
  const old = scope.begin()!;
  let resolve!: (value: string) => void;
  const pending = new Promise<string>(done => { resolve = done; }).then(value => { if (scope.owns(old)) displayed = value; scope.finish(old); });
  scope.invalidate(false); assert.equal(scope.begin(), null);
  scope.invalidate(true); const next = scope.begin()!;
  resolve('old-secret-invitation'); await pending;
  assert.equal(displayed, ''); assert.equal(scope.hasPending(), true); assert.equal(scope.owns(next), true);
  assert.equal(scope.begin(), null); assert.equal(scope.finish(next), true); assert.equal(scope.hasPending(), false);
  const review = scope.begin()!; scope.invalidate(true); assert.equal(scope.owns(review), false); assert.equal(scope.finish(review), false);
});

test('the original control lane remains available during peer reads and has its own duplicate gate', () => {
  const scope = new DeviceRequestScope(), read = scope.begin()!, stop = scope.begin('control')!;
  assert.ok(read); assert.ok(stop); assert.equal(scope.begin('control'), null);
  assert.equal(scope.finish(stop), true); assert.equal(scope.owns(read), true);
  scope.invalidate(); assert.equal(scope.finish(read), false);
});
