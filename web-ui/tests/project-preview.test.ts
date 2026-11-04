import test from 'node:test';
import assert from 'node:assert/strict';
import { previewReferenceMessage, previewReturned, previewViewport, projectPreviewState, samePreviewWindow } from '../src/project-preview.ts';

const original = { kind: 'bundle', sessionId: 'native-session', taskId: 'original-task', conversationId: 'original-project-conversation',
  documentGeneration: 1, ready: true, selecting: false, visible: true, error: null, metadata: { projectId: 'original-project' } };

test('preview metadata supports initial loading without accepting live previews or coerced identity', () => {
  assert.equal(projectPreviewState(original)?.sessionId, original.sessionId);
  assert.equal(projectPreviewState({ ...original, documentGeneration: 0 })?.ready, false);
  assert.equal(projectPreviewState({ ...original, kind: 'live' }), null);
  assert.equal(projectPreviewState({ ...original, documentGeneration: '1' }), null);
  assert.equal(projectPreviewState({ ...original, sessionId: 11 }), null);
  assert.equal(projectPreviewState({ ...original, selecting: 'true' })?.selecting, false);
});

test('return receipt waits for both original conversation and original composer, never another draft', () => {
  const receipt = { sessionId: original.sessionId, conversationId: original.conversationId, returned: true };
  assert.equal(previewReturned(receipt, original, 'previous-conversation', 'previous-conversation'), false);
  assert.equal(previewReturned(receipt, original, original.conversationId, 'another-draft'), false);
  assert.equal(previewReturned({ ...receipt, sessionId: 'replacement-session' }, original, original.conversationId, original.conversationId), false);
  assert.equal(previewReturned({ ...receipt, returned: 'true' }, original, original.conversationId, original.conversationId), false);
  assert.equal(previewReturned(receipt, original, original.conversationId, original.conversationId), true);
});

test('preview window fences include document, project and resume identity', () => {
  const ticket = { projectId: 'project-a', documentEpoch: 3, resumeRevision: 6 };
  assert.equal(samePreviewWindow(ticket, { ...ticket }), true);
  for (const change of [{ projectId: 'project-b' }, { documentEpoch: 4 }, { resumeRevision: 7 }])
    assert.equal(samePreviewWindow(ticket, { ...ticket, ...change }), false);
});

test('viewport clips to actual window and hides invalid, empty, occluded and offscreen rectangles', () => {
  assert.deepEqual(previewViewport('s', true, { x: -10, y: 600, width: 400, height: 300 }, 360, 800),
    { sessionId: 's', visible: true, rect: { x: 0, y: 600, width: 360, height: 200 }, viewportWidth: 360, viewportHeight: 800 });
  for (const rect of [null, { x: 0, y: 0, width: NaN, height: 1 }, { x: 0, y: 0, width: 0, height: 1 }, { x: 400, y: 0, width: 50, height: 50 }])
    assert.equal(previewViewport('s', true, rect, 360, 800).visible, false);
  assert.equal(previewViewport('s', false, { x: 10, y: 80, width: 340, height: 400 }, 360, 800).visible, false);
  assert.equal(previewViewport('s', true, { x: 10, y: 80, width: 340, height: 400 }, Infinity, 800).visible, false);
});

test('metadata availability never labels a reference source-verified', () => {
  assert.match(previewReferenceMessage(true), /发送时.*重新核对/);
  for (const unavailable of [false, undefined, 'true']) assert.match(previewReferenceMessage(unavailable), /重新选择/);
});
