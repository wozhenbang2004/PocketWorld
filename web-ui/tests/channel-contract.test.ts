import test from 'node:test';
import assert from 'node:assert/strict';
import { channelCanStop, channelConnectionStatus, channelIdentity, channelOriginalCurrent, channelOwnerCandidates, channelOwnerIdentity, channelProfilePayload, channelReceivingStatus } from '../src/channel-contracts.ts';

const profile = { id: 'original', provider: 'qq', appId: 'original-bot', transportEpoch: 'old', name: '账号' };
test('an edited Web form cannot retarget an original profile or borrow a new revision', () => {
  const payload = channelProfilePayload(profile, { id: 'other', appId: 'other-bot', provider: 'weixin', transportEpoch: 'new', name: ' new name ', enabled: true });
  assert.deepEqual(channelIdentity(profile), { profileId: 'original', expectedTransportEpoch: 'old' });
  assert.equal(payload.id, 'original'); assert.equal(payload.appId, 'original-bot'); assert.equal(payload.expectedTransportEpoch, 'old'); assert.equal(payload.provider, 'qq'); assert.equal(payload.name, 'new name');
});
test('a new account reuses its original submission id and requires absence without inheriting credentials', () => {
  const draft = { id: 'one-new-id', provider: 'qq', adding: true, secret: 'must-not-inherit', transportEpoch: 'must-not-inherit' };
  const payload = channelProfilePayload(draft, { name: 'new', appId: ' bot ', enabled: true });
  assert.equal(payload.id, 'one-new-id'); assert.equal(payload.expectedTransportEpoch, ''); assert.equal(payload.appId, 'bot'); assert.equal(payload.secret, undefined);
});
test('Weixin setup cannot import a password or enable QQ scope through form fields', () => {
  const payload = channelProfilePayload({ ...profile, provider: 'weixin' }, { name: '微信', secret: 'not-an-official-token', qqGuildEnabled: true, qqStreamRepliesEnabled: true });
  assert.equal(payload.secret, undefined); assert.equal(payload.qqGuildEnabled, false); assert.equal(payload.qqStreamRepliesEnabled, false); assert.equal(payload.autoReply, false); assert.equal(payload.ownerMediaEnabled, false);
});
test('owner review fixes transport, binding and task grant separately', () => {
  const binding = { profileId: profile.id, bindingEpoch: 'owner-1', taskGrantEpoch: 'grant-1' }, original = channelOwnerIdentity(profile, binding);
  const current = { profiles: [profile], bindings: [binding] }; assert.equal(channelOriginalCurrent(original, current), true);
  for (const changed of [{ profiles: [{ ...profile, transportEpoch: 'new' }], bindings: [binding] }, { profiles: [profile], bindings: [{ ...binding, bindingEpoch: 'owner-2' }] }, { profiles: [profile], bindings: [{ ...binding, taskGrantEpoch: 'grant-2' }] }, { profiles: [profile], bindings: [] }]) assert.equal(channelOriginalCurrent(original, changed), false);
});
test('an unbound review does not accept a later owner binding', () => {
  const original = channelOwnerIdentity(profile); assert.equal(channelOriginalCurrent(original, { profiles: [profile], bindings: [] }), true);
  assert.equal(channelOriginalCurrent(original, { profiles: [profile], bindings: [{ profileId: profile.id, bindingEpoch: 'new', taskGrantEpoch: 'grant' }] }), false);
});
test('only current authenticated recent DM evidence is offered for owner binding', () => {
  const now = 100_000_000, event = { id: 'actual', profileId: profile.id, transportEpoch: profile.transportEpoch, kind: 'dm', authenticatedBy: 'qq_gateway', receivedAt: now - 10 };
  const inbox = [event, { ...event, id: 'group', kind: 'group' }, { ...event, id: 'old-epoch', transportEpoch: 'other' }, { ...event, id: 'other-account', profileId: 'other' }, { ...event, id: 'self-asserted', authenticatedBy: 'body_text' }, { ...event, id: 'future', receivedAt: now + 1 }, { ...event, id: 'expired', receivedAt: now - 86_400_001 }];
  assert.deepEqual(channelOwnerCandidates({ inbox }, profile, now).map(row => row.id), ['actual']);
});
test('foreground request and established service remain different evidence', () => {
  assert.match(channelReceivingStatus({ status: 'starting', sessionId: 's' }), /尚未建立/);
  assert.doesNotMatch(channelReceivingStatus({ status: 'foreground', foregroundActive: true, foregroundServicePresent: false }), /已建立/);
  assert.match(channelReceivingStatus({ status: 'foreground', foregroundActive: true, foregroundServicePresent: true }), /已建立/);
  assert.equal(channelCanStop({ status: 'starting', sessionId: 's' }), true);
  assert.equal(channelCanStop({ status: 'stopped', sessionId: 's', cleanupPending: true }), true);
  assert.equal(channelCanStop({ status: 'foreground' }), false);
});
test('one successful sync and a stale listening label are not shown as continuous receiving', () => {
  assert.match(channelConnectionStatus('weixin', { status: 'synchronized', monitorRunning: false }), /当前未持续接收/);
  assert.equal(channelConnectionStatus('qq', { status: 'listening', monitorRunning: false }), '接收已停止');
  assert.match(channelConnectionStatus('qq', { status: 'listening', monitorRunning: true }), /已认证/);
  assert.match(channelConnectionStatus('weixin', { status: 'login_required', monitorRunning: true }), /重新扫码/);
});
