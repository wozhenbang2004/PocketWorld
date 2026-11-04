import assert from 'node:assert/strict';
import test from 'node:test';
import { mergeRealtimeSnapshot, realtimeReviewCurrent } from '../src/realtime-contract.ts';

const now = 1000;
const review = (command: string, details: Record<string, any>) => ({ reviewHandle: 'native-once', command, details, expiresAt: 2000 });

test('a late connect receipt cannot replace a newer disconnect snapshot', () => {
  const stopped = { snapshotRevision: 7, sessionId: 'original', session: { state: 'disconnected' } };
  assert.equal(mergeRealtimeSnapshot(stopped, { snapshotRevision: 6, sessionId: 'original', session: { state: 'connected' } }), stopped);
  assert.equal(mergeRealtimeSnapshot(stopped, {}), stopped);
  assert.equal(mergeRealtimeSnapshot({}, stopped), stopped);
});

test('an invitation review binds its exact invitation and explicit capture permissions', () => {
  const invitation = { id: 'first', expiresAt: 2000 };
  const snapshot = { invitation, invitationState: 'awaiting_owner', microphonePermission: false, cameraPermission: false };
  assert.equal(realtimeReviewCurrent(snapshot, review('acceptInvitation', { id: 'first', microphone: false, camera: false }), now), true);
  assert.equal(realtimeReviewCurrent(snapshot, review('acceptInvitation', { id: 'first', microphone: true, camera: false }), now), false);
  assert.equal(realtimeReviewCurrent({ ...snapshot, invitation: { ...invitation, id: 'successor' } }, review('acceptInvitation', { id: 'first' }), now), false);
  assert.equal(realtimeReviewCurrent({ ...snapshot, invitationState: 'rejected' }, review('acceptInvitation', { id: 'first' }), now), false);
});

test('configuration reviews include the empty-store revision and reject subsequent edits', () => {
  const first = review('configureNaturalVoice', { configurationRevision: 0 });
  assert.equal(realtimeReviewCurrent({ configuration: {} }, first, now), true);
  assert.equal(realtimeReviewCurrent({ configuration: { revision: 1 } }, first, now), false);
  assert.equal(realtimeReviewCurrent({ configuration: {} }, first, 2000), false);
});

test('task cancellation cannot consume a reading token or target a successor execution', () => {
  const original = { voiceTask: { bindingId: 'original-task', viewToken: 'read-v1', cancelToken: 'run-v3' } };
  assert.equal(realtimeReviewCurrent(original, review('cancelVoiceTask', { bindingId: 'original-task', viewToken: 'read-v1' }), now), false);
  assert.equal(realtimeReviewCurrent(original, review('cancelVoiceTask', { bindingId: 'original-task', viewToken: 'run-v3' }), now), true);
  assert.equal(realtimeReviewCurrent({ voiceTask: { ...original.voiceTask, cancelToken: 'run-v4' } }, review('cancelVoiceTask', { bindingId: 'original-task', viewToken: 'run-v3' }), now), false);
});

test('a transcript task review is invalidated by content revision, source session or native readiness', () => {
  const turn = { id: 'turn-1', text: 'Check the project', revision: 4, isFinal: true, canReview: true };
  const snapshot = { sessionId: 'call-1', transcription: { commitAvailable: true, reviewReady: true, turns: [turn] } };
  const original = review('commitVoiceTask', { sessionId: 'call-1', turnId: turn.id, revision: turn.revision, text: turn.text, expiresAt: 2000 });
  assert.equal(realtimeReviewCurrent(snapshot, original, now), true);
  assert.equal(realtimeReviewCurrent({ ...snapshot, sessionId: 'call-2' }, original, now), false);
  assert.equal(realtimeReviewCurrent({ ...snapshot, transcription: { ...snapshot.transcription, turns: [{ ...turn, text: 'Changed goal', revision: 5 }] } }, original, now), false);
  assert.equal(realtimeReviewCurrent({ ...snapshot, transcription: { ...snapshot.transcription, reviewReady: false } }, original, now), false);
});
