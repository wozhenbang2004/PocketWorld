import test from 'node:test';
import assert from 'node:assert/strict';
import { packageOperationTarget, packagePreviewChoices, packagePreviewMatches, previewReferenceMessage, previewReturned, previewViewport, projectPreviewState, samePreviewWindow } from '../src/project-preview.ts';

const original = { kind: 'bundle', sessionId: 'native-session', taskId: 'original-task', conversationId: 'original-project-conversation',
  documentGeneration: 1, ready: true, selecting: false, visible: true, error: null, selectionAvailable: true, selectionUnavailableReason: null, metadata: { projectId: 'original-project' } };

test('preview metadata supports initial loading without accepting live previews or coerced identity', () => {
  assert.equal(projectPreviewState(original)?.sessionId, original.sessionId);
  assert.equal(projectPreviewState({ ...original, documentGeneration: 0 })?.ready, false);
  assert.equal(projectPreviewState({ ...original, kind: 'live' }), null);
  assert.equal(projectPreviewState({ ...original, documentGeneration: '1' }), null);
  assert.equal(projectPreviewState({ ...original, sessionId: 11 }), null);
  assert.equal(projectPreviewState({ ...original, selecting: 'true' })?.selecting, false);
  assert.equal(projectPreviewState({ ...original, selectionAvailable: 'true' })?.selectionAvailable, false);
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

const task = { id: 'task-original', raw: { kind: 'assistant', actorId: 'owner', spaceId: 'private', developmentContext: { projectId: 'project-original', checkoutId: 'checkout-original' } } };
const operation = { operationId: 'operation-original', runId: 'run-original', tool: 'project_execute', state: 'receipt', bodyAvailable: true,
  receipt: { status: 'completed', accepted: true, code: 'project_file_receipt',
    projectExecution: { authority: 'native_host_operation', intent: 'freeze_package', taskId: task.id, operationId: 'operation-original', runId: 'run-original', projectId: 'project-original', checkoutId: 'checkout-original' },
    executionReceipt: { status: 'completed', artifactReady: true, receiptReceived: true, operationId: 'operation-original' } } };
const target = { taskId: task.id, operationId: operation.operationId };
const choices = { kind: 'package_preview_choices', ...target, conversationId: 'conversation-original', projectId: 'project-original', checkoutId: 'checkout-original',
  sourceDigest: 'a'.repeat(64), artifactId: '11111111-1111-4111-8111-111111111111', artifactSha256: 'b'.repeat(64), mode: 'web_output', entryPath: 'dist/index.html', entryChoices: ['dist/index.html'] };

test('package button needs an available original private settled native package operation', () => {
  assert.deepEqual(packageOperationTarget(task, operation), target);
  for (const change of [{ state: 'prepared' }, { state: 'unknown' }, { readLimited: true }, { bodyAvailable: false }, { tool: 'project_package' }])
    assert.equal(packageOperationTarget(task, { ...operation, ...change }), null);
  for (const raw of [{ ...task.raw, origin: { kind: 'team' } }, { ...task.raw, actorId: 'member' }, { ...task.raw, spaceId: 'group' }])
    assert.equal(packageOperationTarget({ ...task, raw }, operation), null);
  assert.equal(packageOperationTarget(task, { ...operation, receipt: { ...operation.receipt, projectExecution: { ...operation.receipt.projectExecution, taskId: 'another-task' } } }), null);
});

test('package inspection is original tuple metadata and cannot pretend to be a document', () => {
  assert.deepEqual(packagePreviewChoices(choices, target), choices);
  for (const change of [{ taskId: 'another-task' }, { operationId: 'another-operation' }, { kind: 'bundle' }, { sessionId: 'made-up' }, { ready: true }, { artifactSha256: 'short' }])
    assert.equal(packagePreviewChoices({ ...choices, ...change }, target), null);
});

test('web output only offers its declared HTML entry and rejects invented alternatives', () => {
  assert.equal(packagePreviewChoices({ ...choices, entryChoices: ['dist/other.html'] }, target), null);
  assert.equal(packagePreviewChoices({ ...choices, entryChoices: [choices.entryPath, 'other.html'] }, target), null);
  assert.equal(packagePreviewChoices({ ...choices, entryPath: '../index.html', entryChoices: ['../index.html'] }, target), null);
});

test('source package choices preserve zero, one and multiple actual entries without an automatic selection', () => {
  for (const entryChoices of [[], ['site/home.html'], ['site/home.html', 'demo/start.html']]) {
    const parsed = packagePreviewChoices({ ...choices, mode: 'source_project', entryPath: '', entryChoices }, target);
    assert.equal(parsed?.entryPath, ''); assert.deepEqual(parsed?.entryChoices, entryChoices);
    assert.notEqual(parsed?.entryChoices, entryChoices);
  }
  assert.equal(packagePreviewChoices({ ...choices, mode: 'source_project' }, target), null);
  assert.equal(packagePreviewChoices({ ...choices, mode: 'source_project', entryPath: '', entryChoices: ['one.html', 'one.html'] }, target), null);
});

test('a fixed package receipt cannot substitute another operation, artifact, source or entry in the same project', () => {
  const selected = packagePreviewChoices(choices, target)!;
  const view = projectPreviewState({ ...original, taskId: target.taskId, conversationId: choices.conversationId, packageOperationId: target.operationId,
    metadata: { origin: 'fixed_workspace_project_artifact', projectId: choices.projectId, checkoutId: choices.checkoutId, sourceDigest: choices.sourceDigest,
      artifactId: choices.artifactId, artifactSha256: choices.artifactSha256, entryPath: choices.entryPath } })!;
  assert.equal(packagePreviewMatches(view, selected, selected.entryPath), true);
  assert.equal(packagePreviewMatches({ ...view, packageOperationId: 'different-operation' }, selected, selected.entryPath), false);
  for (const change of [{ origin: 'active_workspace_observation' }, { artifactId: 'different-artifact' }, { artifactSha256: 'c'.repeat(64) }, { sourceDigest: 'd'.repeat(64) }, { entryPath: 'another.html' }])
    assert.equal(packagePreviewMatches({ ...view, metadata: { ...view.metadata, ...change } }, selected, selected.entryPath), false);
  assert.equal(packagePreviewMatches({ ...view, selectionAvailable: false, selectionUnavailableReason: '原源码已变化' }, selected, selected.entryPath), true, 'historical document remains viewable; selection availability is a separate native observation');
});
