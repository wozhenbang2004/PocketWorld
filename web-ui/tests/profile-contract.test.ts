import test from 'node:test';
import assert from 'node:assert/strict';
import { newProfile, profileAsset, profileModel, profileMutationReceipt, profilePluginTool, profileReferenceKey, profileSaveInput, ProfileRequestGate } from '../src/profile-contract.ts';

test('model selections retain exact connection and group revision without selecting a replacement', () => {
  const original = newProfile(), ref = { kind: 'group', groupId: 'owner-group', groupRevision: 7 };
  const draft = profileModel(original, 'MAIN', ref);
  assert.deepEqual(draft.modelPolicies.MAIN, ref); assert.deepEqual(original.modelPolicies, {});
  assert.notEqual(profileReferenceKey(ref), profileReferenceKey({ ...ref, groupRevision: 8 }));
  assert.deepEqual(profileModel(draft, 'MAIN', null).modelPolicies, {});
  assert.throws(() => profileModel(original, 'MEMBER.another task', ref));
  assert.throws(() => profileModel(original, 'RELAY', { kind: 'exact', connectionId: 'account:owner', modelId: 'selected' }));
});

test('skill and extension selection needs current source approval and carries exact source identity', () => {
  const skill = { id: 'verified-skill', revision: 4, digest: 'a'.repeat(64), active: true, contentVerified: true, apiKey: 'must-not-copy' };
  const draft = profileAsset(newProfile(), 'skillRefs', skill, true);
  assert.deepEqual(draft.skillRefs, [{ id: skill.id, revision: 4, digest: skill.digest, optional: false }]);
  assert.throws(() => profileAsset(draft, 'skillRefs', { ...skill, active: false }, true));
  assert.throws(() => profileAsset(draft, 'instructionRefs', { ...skill, contentVerified: false }, true));
  assert.deepEqual(profileAsset(draft, 'skillRefs', { ...skill, active: false }, false).skillRefs, []);
  const extension = { id: 'ext', installationId: 'original-install', version: '1.0', approvalRevision: 3, sourceSha256: 'b'.repeat(64), enabled: true, approved: true };
  assert.equal(profileAsset(draft, 'extensionRefs', extension, true).extensionRefs[0].installationId, 'original-install');
  assert.throws(() => profileAsset(draft, 'extensionRefs', { ...extension, approved: false }, true));
});

test('a replaced plugin cannot inherit tools from its previous installation or review', () => {
  const plugin = { id: 'plugin', installationId: 'first', stateRevision: 4, manifestDigest: 'c'.repeat(64), enabled: true, tools: [{ name: 'read', granted: true }, { name: 'write', granted: true }, { name: 'unapproved', granted: false }] };
  const first = profilePluginTool(newProfile(), plugin, 'read', true);
  assert.deepEqual(profilePluginTool(first, plugin, 'write', true).pluginRefs[0].tools, ['read', 'write']);
  const replaced = profilePluginTool(first, { ...plugin, installationId: 'second', stateRevision: 5 }, 'write', true);
  assert.deepEqual(replaced.pluginRefs[0].tools, ['write']); assert.equal(replaced.pluginRefs[0].installationId, 'second');
  assert.throws(() => profilePluginTool(first, plugin, 'unapproved', true));
  assert.throws(() => profilePluginTool(first, { ...plugin, enabled: false }, 'write', true));
});

test('saving edits preserves native identity and pending restore history, and needs a fresh view', () => {
  const original = { ...newProfile(), profileId: 'original', revision: 8, name: '恢复的配置', restoredPendingReview: true, restoredReferences: [{ kind: 'model:MAIN', reference: { kind: 'exact', connectionId: 'old', modelId: 'old-model' } }] };
  const draft = { ...original, profileId: 'replacement', revision: 1, name: '我的草稿', restoredPendingReview: false, restoredReferences: [] };
  assert.throws(() => profileSaveInput(draft, original, ''));
  const input = profileSaveInput(draft, original, 'original-window');
  assert.equal(input.definition.profileId, 'original'); assert.equal(input.definition.revision, 8); assert.equal(input.definition.name, '我的草稿');
  assert.equal(input.definition.restoredPendingReview, true); assert.deepEqual(input.definition.restoredReferences, original.restoredReferences);
  assert.equal(draft.restoredPendingReview, false);
  const fresh = profileSaveInput(draft, null, '');
  assert.equal('restoredPendingReview' in fresh.definition, false); assert.equal('restoredReferences' in fresh.definition, false);
});

test('a completed write needs the exact operation, profile and next revision, with real rebind state', () => {
  const original = { profileId: 'original', revision: 8 }, expected = { operationId: 'one-operation', command: 'rebind', original };
  const record = { ...newProfile(), profileId: 'original', revision: 9, restoredPendingReview: false };
  const receipt = { confirmed: true, operationId: expected.operationId, command: 'rebind', record };
  assert.equal(profileMutationReceipt(receipt, expected), record);
  assert.throws(() => profileMutationReceipt({ accepted: true }, expected));
  assert.throws(() => profileMutationReceipt({ ...receipt, operationId: 'another-operation' }, expected));
  for (const changed of [{ profileId: 'another-profile' }, { revision: 8 }, { restoredPendingReview: true }]) assert.throws(() => profileMutationReceipt({ ...receipt, record: { ...record, ...changed } }, expected));
  assert.throws(() => profileMutationReceipt({ ...receipt, command: 'remove', record: { removed: true, profileId: 'different' } }, { ...expected, command: 'remove' }));
});

test('hidden pending writes remain unknown across reads and require explicit original-result acknowledgment', () => {
  const gate = new ProfileRequestGate(), write = gate.begin('original-operation')!;
  assert.equal(gate.invalidate(false), 'original-operation'); assert.equal(gate.owns(write), false); assert.equal(gate.finish(write), false);
  assert.equal(gate.begin(), null); gate.invalidate(true);
  assert.equal(gate.begin('retry'), null); assert.equal(gate.acknowledge(), false);
  const read = gate.begin()!; assert.equal(gate.finish(read), true); assert.equal(gate.acknowledge(), false);
  gate.inspectOriginal('other-operation'); assert.equal(gate.acknowledge(), false);
  gate.inspectOriginal('original-operation'); assert.equal(gate.begin('retry'), null);
  assert.equal(gate.acknowledge(), true); assert.notEqual(gate.begin('new-review-operation'), null);
});

test('document or section changes invalidate both late reads and a previous acknowledgment basis', () => {
  const gate = new ProfileRequestGate(), oldRead = gate.begin()!;
  gate.invalidate(true); const newRead = gate.begin()!;
  assert.equal(gate.finish(oldRead), false); assert.equal(gate.owns(newRead), true); assert.equal(gate.finish(newRead), true);
  gate.markUnknown('operation'); gate.inspectOriginal('operation'); gate.invalidate(true);
  assert.equal(gate.acknowledge(), false); assert.equal(gate.begin('replay'), null);
  gate.inspectOriginal('operation'); assert.equal(gate.acknowledge(), true);
});
