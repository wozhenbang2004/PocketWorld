import test from 'node:test';
import assert from 'node:assert/strict';
import { TaskCapabilityConsent, taskCapabilityArtifactStatus, taskCapabilityDecision } from '../src/task-capabilities.ts';

test('a retained click cannot confirm a different review or withdrawn consent', () => {
  const review = new TaskCapabilityConsent(); review.show('original'); review.change(true); const original = review.capture();
  review.change(false); review.change(true); assert.equal(review.confirm(original), false);
  const current = review.capture(); assert.equal(review.confirm(current), true); assert.equal(review.confirm(current), false);
  review.show('new'); review.change(true); assert.equal(review.confirm(current), false);
  review.clear(); assert.equal(review.confirm(review.capture()), false);
});
test('a native decision is never described as execution success', () => {
  assert.match(taskCapabilityDecision({ accepted: true }), /尚未确认/);
  assert.match(taskCapabilityDecision({ decisionRecorded: true, approved: true }), /结果仍以原回执/);
  assert.doesNotMatch(taskCapabilityDecision({ decisionRecorded: true, approved: true }), /执行成功|已经完成/);
  assert.match(taskCapabilityDecision({ decisionRecorded: true, approved: false }), /已拒绝/);
});
test('completed execution without retrieved bytes keeps original retrieval visible', () => {
  assert.match(taskCapabilityArtifactStatus({ receipt: { status: 'completed' }, artifactAvailability: { state: 'not_retrieved', canRecheck: true } }), /尚未完整取回/);
  assert.equal(taskCapabilityArtifactStatus({ artifactAvailability: { state: 'available' } }), '原文件成果已取回');
  assert.equal(taskCapabilityArtifactStatus({ receipt: { status: 'rejected' } }), '本次执行已拒绝');
});
