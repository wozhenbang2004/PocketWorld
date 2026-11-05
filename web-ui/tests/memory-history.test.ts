import assert from 'node:assert/strict';
import test from 'node:test';
import { MemoryReadingWindow, memoryHistoryStatus, taskMemorySelectionDetails, taskMemorySummary, taskMemoryVisibleRecords } from '../src/memory-history.ts';

test('query changes, closing and returning to the same range cannot revive old callbacks or replies', () => {
  const window = new MemoryReadingWindow(); const first = window.capture();
  assert.equal(window.enter(first), true); assert.equal(window.enter(first), false);
  window.invalidate(); assert.equal(window.current(first), false); assert.equal(window.enter(first), false);
  const next = window.capture(); assert.equal(window.enter(next), true);
  window.finish(first); assert.equal(window.enter(next), false);
  window.close(); window.reopen(); assert.equal(window.current(first), false); assert.equal(window.current(next), false);
  assert.equal(window.enter(window.capture()), true);
});

test('historical, imagined and conflict displays never promote old text to current facts', () => {
  assert.match(memoryHistoryStatus('historical'), /不代表现在/);
  assert.match(memoryHistoryStatus('pending_conflict'), /尚未确定/);
  assert.match(taskMemorySummary({ phase: 'prepared', status: 'current' }), /尚未证明/);
  assert.match(taskMemorySummary({ phase: 'unavailable' }), /不会用当前检索/);
});

test('revoked or failed source receipts erase stale prose and edit handles, retaining exact identities', () => {
  const original = { phase: 'provided_to_request', status: 'invalidated', records: [
    { id: 'old', revision: 4, status: 'source_forgotten', text: 'OLD_SECRET', readHandle: 'old-handle' },
    { id: 'kept', revision: 2, status: 'current', text: 'CURRENT', readHandle: 'current-handle' },
  ] };
  const result = taskMemoryVisibleRecords(original);
  assert.deepEqual(result[0], { id: 'old', revision: 4, status: 'source_forgotten' });
  assert.equal(result[1].text, 'CURRENT'); assert.equal(original.records[0].text, 'OLD_SECRET');
  assert.match(taskMemorySummary(original), /部分来源已失效/);
  assert.ok(taskMemoryVisibleRecords({ ...original, status: 'revoked' }).every(row => !('text' in row) && !('readHandle' in row)));
});

test('selection omissions expose only original identities and counts, never caller-retained old bodies', () => {
  const receipt = { selectionDiagnosticsAvailable: true, selectionDiagnostics: { coreSelected: 14, retrievedSelected: 1, charsUsed: 320,
    omitted: [{ id: 'omitted', revision: 7, reason: 'record_limit', text: 'OLD_BODY' }], omissionCounts: { record_limit: 1 } } };
  assert.deepEqual(taskMemorySelectionDetails(receipt)?.omitted, [{ id: 'omitted', revision: 7, reason: 'record_limit' }]);
  assert.equal(taskMemorySelectionDetails({ ...receipt, selectionDiagnosticsAvailable: false }), null);
  assert.equal(taskMemorySelectionDetails(receipt)?.counts.record_limit, 1);
});
