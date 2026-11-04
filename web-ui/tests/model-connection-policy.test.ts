import test from 'node:test';
import assert from 'node:assert/strict';
import { cacheCounterText, cacheHintText, compatibilityDraft, compatibilityNeedsSave, compatibilityOptions, connectionTestResult } from '../src/model-connection-policy.ts';

const draft = { editorId: 'cdb9664e-3c35-4b5b-a2be-84b1e8bdfb01', draftRevision: 3, connectionId: 'connection-a', connectionRevision: 7 };
const reference = { taskId: 'a56a6ddb-3de4-4b00-9aa9-9cd3eff3cf91', runId: 'd7432e34-8f42-4be3-833d-3c42d52129ad', epoch: 1, purpose: 'CONNECTION_TEST', accountingOnly: true, executionAuthorized: false };
const result = () => ({ status: 'tested', snapshot: { ok: true, inferenceReference: { ...reference },
  connectionTest: { ...draft, format: 'pocketworld.connection-test.v1', testId: 'b23e331e-843d-435c-88ec-929a40e55d2e', draftFingerprint: 'a'.repeat(64),
    presentationCurrent: true, outcome: 'completed', inferenceReference: { ...reference } } } });
const saved = { format: 'pocketworld.provider-compat.v1', id: 'native-profile', revision: 2, apiMode: 'openai_chat', model: 'model-a',
  tokenField: 'MAX_COMPLETION_TOKENS', streamUsage: 'OMIT', toolWireShape: 'NATIVE', schemaSubset: 'CORE_JSON', maxTools: 64, maxRequestBytes: 1048576, cacheMode: 'OFF', cacheSupport: 'UNKNOWN' };
const original = { id: 'connection-a', revision: 7, provider: 'openai_chat', baseUrl: 'https://example.test/v1', model: 'model-a', configuration: { compatibility: saved } };
const route = { provider: 'openai_chat', url: original.baseUrl, model: original.model, apiKey: '' };

test('only the original native test result and accounting task can appear on this exact draft', () => {
  assert.equal(connectionTestResult(result(), draft, draft)?.ok, true);
  for (const changed of [{ ...draft, connectionId: 'connection-b' }, { ...draft, connectionRevision: 8 },
    { ...draft, draftRevision: 4 }, { ...draft, editorId: 'f78ce8eb-f3cb-46d9-9b37-1c7247bb600b' }]) {
    assert.equal(connectionTestResult(result(), draft, changed), null);
  }
  assert.equal(connectionTestResult({ status: 'tested', snapshot: { ok: true, message: 'old global success' } }, draft, draft), null);
});

test('late native windows, a swapped original task and unbound fingerprints cannot claim success', () => {
  const late = result(); late.snapshot.connectionTest.presentationCurrent = false;
  assert.equal(connectionTestResult(late, draft, draft), null);
  const wrongTask = result(); wrongTask.snapshot.inferenceReference.taskId = '2a0de1dc-3b57-4e1d-bdf4-55d2bdbab20c';
  assert.equal(connectionTestResult(wrongTask, draft, draft), null);
  const missing = result(); missing.snapshot.connectionTest.draftFingerprint = '';
  assert.equal(connectionTestResult(missing, draft, draft), null);
  const forged = result(); forged.snapshot.inferenceReference.executionAuthorized = true;
  assert.equal(connectionTestResult(forged, draft, draft), null);
});

test('unknown or not-dispatched responses remain typed and cannot turn into success without a task', () => {
  const unknown: any = result(); unknown.snapshot.ok = false; unknown.snapshot.connectionTest.outcome = 'unknown';
  unknown.snapshot.inferenceReference = null; unknown.snapshot.connectionTest.inferenceReference = null;
  assert.equal(connectionTestResult(unknown, draft, draft)?.connectionTest.outcome, 'unknown');
  unknown.snapshot.connectionTest.outcome = 'not_dispatched';
  assert.equal(connectionTestResult(unknown, draft, draft)?.connectionTest.outcome, 'not_dispatched');
  unknown.snapshot.connectionTest.outcome = 'completed'; unknown.snapshot.ok = true;
  assert.equal(connectionTestResult(unknown, draft, draft), null);
});

test('a success flag cannot contradict the native test outcome', () => {
  for (const outcome of ['failed', 'unknown', 'not_dispatched']) {
    const contradictory = result(); contradictory.snapshot.connectionTest.outcome = outcome;
    assert.equal(connectionTestResult(contradictory, draft, draft), null);
  }
  const incomplete: any = result(); delete incomplete.snapshot.ok;
  incomplete.snapshot.connectionTest.outcome = 'failed';
  assert.equal(connectionTestResult(incomplete, draft, draft), null);
});

test('compatibility saves only the supported definition and never caller-supplied identity or secrets', () => {
  const selected = compatibilityDraft({ ...saved, apiKey: 'fixture-secret', installationId: 'forged' });
  assert.deepEqual(compatibilityOptions(selected, 'openai_chat'), { tokenField: 'MAX_COMPLETION_TOKENS', streamUsage: 'OMIT', toolWireShape: 'NATIVE',
    schemaSubset: 'CORE_JSON', maxTools: 64, maxRequestBytes: 1048576, cacheMode: 'OFF', cacheSupport: 'UNKNOWN' });
  assert.doesNotMatch(JSON.stringify(selected), /fixture-secret|native-profile|forged/);
  assert.equal(compatibilityNeedsSave(selected, original, route), false);
  assert.equal(compatibilityNeedsSave({ ...selected, tokenField: 'MAX_TOKENS' }, original, route), true);
  assert.equal(compatibilityNeedsSave({ ...selected, enabled: false }, original, route), true);
});

test('ordinary route drafts do not borrow a saved policy; a new explicit policy must be saved', () => {
  for (const changed of [{ ...route, model: 'model-b' }, { ...route, url: 'https://other.test/v1' }, { ...route, apiKey: 'replacement' }]) {
    assert.equal(compatibilityNeedsSave(compatibilityDraft(), original, changed), false);
    assert.equal(compatibilityNeedsSave(compatibilityDraft(saved), original, changed), true);
  }
  assert.equal(compatibilityNeedsSave(compatibilityDraft(), undefined, route), false);
  assert.equal(compatibilityNeedsSave(compatibilityDraft(saved), undefined, route), true);
});

test('protocol mapping and numeric bounds reject unsupported wire mutations before a request', () => {
  const selected = compatibilityDraft(saved);
  assert.throws(() => compatibilityOptions({ ...selected, tokenField: 'arbitrary' }, 'openai_chat'), /支持范围/);
  assert.throws(() => compatibilityOptions(selected, 'openai_responses'), /Chat/);
  assert.throws(() => compatibilityOptions({ ...selected, maxTools: '0' }, 'openai_chat'), /1–512/);
  assert.throws(() => compatibilityOptions({ ...selected, maxRequestBytes: '25165825' }, 'openai_chat'), /字节/);
  assert.throws(() => compatibilityOptions({ ...selected, cacheMode: 'EPHEMERAL' }, 'openai_chat'), /缓存提示/);
});

test('cache absence, partial dispatch and reported zeros are represented differently without a bill', () => {
  assert.equal(compatibilityDraft().cacheMode, 'OFF'); assert.equal(compatibilityDraft().cacheSupport, 'UNKNOWN');
  assert.match(cacheCounterText(undefined), /未知/); assert.match(cacheCounterText({ value: 0, source: 'unknown' }), /未知/);
  assert.match(cacheCounterText({ value: 0, source: 'provider_reported' }), /^0 tokens/);
  assert.match(cacheCounterText({ value: 18, source: 'provider_reported' }), /^18 tokens/);
  assert.match(cacheHintText({ hintPrepared: true, dispatchAttempted: true }), /送达与命中未确认/);
  assert.match(cacheHintText({ reason: 'no_stable_system_or_tools' }), /没有稳定/);
  assert.match(cacheHintText({ reason: 'cache_support_not_confirmed' }), /未确认/);
});
