import test from 'node:test';
import assert from 'node:assert/strict';
import { conversationModelName, conversationSelectionRequest, conversationSelectionReceipt } from '../src/conversation-model-selection.ts';

const state = { modelSelectionRevision: 8, modelName: '全局默认', conversationModel: { scopeKey: 'conversation:a', revision: 3, inherited: false, target: { kind: 'api', modelId: 'A 的模型' } } };

test('conversation A and B names resolve their own API, account and group preferences without changing the global model', () => {
  const original = structuredClone(state);
  assert.equal(conversationModelName(state), 'A 的模型');
  assert.equal(conversationModelName({ ...state, conversationModel: { scopeKey: 'conversation:b', revision: 2, inherited: false, target: { kind: 'account', nativeRef: { modelId: 'B 的账号模型' } } } }), 'B 的账号模型');
  assert.equal(conversationModelName({ ...state, conversationModel: { scopeKey: 'conversation:b', revision: 2, inherited: false, modelName: 'B 的模型组', target: { kind: 'group', groupId: 'group-b', groupRevision: 4 } } }), 'B 的模型组');
  assert.deepEqual(state, original);
});

test('new conversations inherit the real default until an explicit draft scoped selection is requested', () => {
  const draft = { ...state, conversationModel: { scopeKey: 'draft:new-a', revision: 0, inherited: true, target: null } };
  assert.equal(conversationModelName(draft), '全局默认');
  const request = conversationSelectionRequest({ kind: 'api', args: { windowId: 'api-window', reviewHandle: 'api-review' }, selectionRevision: 8 }, draft);
  assert.equal(request.action, 'models.conversation.api.select');
  assert.equal(request.scopeKey, 'draft:new-a');
  assert.equal(request.args.expectedPreferenceRevision, 0);
  assert.equal(draft.conversationModel.target, null);
  assert.equal(draft.modelName, '全局默认');
});

test('API and account selections preserve original windows and review handles and only map to conversation commands', () => {
  for (const kind of ['api', 'account']) {
    const args = { windowId: `${kind}-window`, reviewHandle: `${kind}-review`, expectedPreferenceRevision: 900, ...(kind === 'account' ? { options: { reasoningEffort: '', outputReserve: 4096, imageTokenReserve: 0 } } : {}) };
    const request = conversationSelectionRequest({ kind, args, selectionRevision: 8 }, state);
    assert.equal(request.action, kind === 'api' ? 'models.conversation.api.select' : 'models.conversation.accounts.select');
    assert.equal(request.args.windowId, `${kind}-window`); assert.equal(request.args.reviewHandle, `${kind}-review`);
    assert.equal(request.args.expectedPreferenceRevision, 3); assert.equal(request.scopeKey, 'conversation:a'); assert.equal(request.previousRevision, 3);
    assert.equal(args.expectedPreferenceRevision, 900);
  }
});

test('group selection retains exact group and preference revisions with an explicit selection revision', () => {
  const request = conversationSelectionRequest({ kind: 'group', args: { groupId: 'group-a', expectedRevision: 6, expectedSelectionRevision: 900 }, selectionRevision: 8 }, state);
  assert.deepEqual(request, { action: 'models.conversation.group', args: { groupId: 'group-a', expectedRevision: 6, expectedPreferenceRevision: 3, expectedSelectionRevision: 8 }, scopeKey: 'conversation:a', previousRevision: 3 });
});

test('missing scope, invalid revisions, stale source revisions and unknown target kinds fail before dispatch', () => {
  const target = { kind: 'api', args: { windowId: 'window', reviewHandle: 'review' }, selectionRevision: 8 };
  for (const conversationModel of [{}, { scopeKey: '', revision: 0 }, { scopeKey: 'conversation:a', revision: -1 }, { scopeKey: 'conversation:a', revision: 1.2 }, { scopeKey: 'conversation:a', revision: '3' }]) {
    assert.throws(() => conversationSelectionRequest(target, { ...state, conversationModel }));
  }
  assert.throws(() => conversationSelectionRequest({ ...target, selectionRevision: 7 }, state));
  assert.throws(() => conversationSelectionRequest({ ...target, kind: 'unknown' }, state));
});

test('missing review identities and invalid group revisions cannot manufacture a conversation selection request', () => {
  for (const args of [{}, { windowId: 'window', reviewHandle: '' }, { windowId: '', reviewHandle: 'review' }]) assert.throws(() => conversationSelectionRequest({ kind: 'api', args, selectionRevision: 8 }, state));
  for (const args of [{ groupId: '', expectedRevision: 2 }, { groupId: 'group-a', expectedRevision: 0 }, { groupId: 'group-a', expectedRevision: 2.2 }]) assert.throws(() => conversationSelectionRequest({ kind: 'group', args, selectionRevision: 8 }, state));
});

test('only a saved receipt for the original conversation and next exact revision is accepted', () => {
  const preference = { scopeKey: 'conversation:a', revision: 4, inherited: true, target: null };
  assert.deepEqual(conversationSelectionReceipt({ status: 'saved', scope: 'conversation', preference }, 'conversation:a', 3), preference);
  for (const value of [{}, { status: 'saved', scope: 'default', preference }, { status: 'accepted', scope: 'conversation', preference }, { status: 'saved', scope: 'conversation', preference: { ...preference, scopeKey: 'conversation:b' } }, { status: 'saved', scope: 'conversation', preference: { ...preference, revision: 5 } }, { status: 'saved', scope: 'conversation', preference: { ...preference, revision: '4' } }]) assert.throws(() => conversationSelectionReceipt(value, 'conversation:a', 3));
});

test('receipt verification rejects missing original identity and unsafe or impossible revision arithmetic', () => {
  assert.throws(() => conversationSelectionReceipt({ status: 'saved', scope: 'conversation', preference: { scopeKey: '', revision: 1 } }, '', 0));
  assert.throws(() => conversationSelectionReceipt({ status: 'saved', scope: 'conversation', preference: { scopeKey: 'conversation:a', revision: 0 } }, 'conversation:a', -1));
  assert.throws(() => conversationSelectionReceipt({ status: 'saved', scope: 'conversation', preference: { scopeKey: 'conversation:a', revision: Number.MAX_SAFE_INTEGER + 1 } }, 'conversation:a', Number.MAX_SAFE_INTEGER));
});
