import test from 'node:test';
import assert from 'node:assert/strict';
import { currentTaskHeaders, taskAuditPage, taskHeaderPage, taskHistoryIdentity } from '../src/task-history.ts';

const event = (position: number, extra = {}) => ({ position, eventKey: `event-${position}`, status: 'available', event: { runId: 'run-a', message: `event ${position}` }, ...extra });
const audit = (extra = {}) => ({ format: 'pocketworld.task-audit-page.v1', taskId: 'task-a', section: 'events', entries: [event(40), event(39)], upperPosition: 40, hasMore: true, nextCursor: 'opaque-next', executionAuthorized: false, ...extra });
const header = (id: string, updatedAt: number, extra = {}) => ({ id, updatedAt, goal: id, status: 'completed', ...extra });
const summaries = (extra = {}) => ({ tasks: [header('task-b', 50), header('task-a', 40)], hasMore: true, nextCursor: 'older-header-window', auditBodyIncluded: false, ...extra });

test('audit pagination keeps the original task, section, order and upper boundary', () => {
  const original = audit(), before = JSON.stringify(original);
  const page = taskAuditPage(original, 'task-a', 'events');
  assert.deepEqual(page.entries.map(row => row.position), [40, 39]);
  const older = taskAuditPage(audit({ entries: [event(38)], nextCursor: '', hasMore: false }), 'task-a', 'events', page.nextCursor, [''], page.upperPosition);
  assert.equal(older.nextCursor, '');
  assert.equal(older.hasMore, false);
  assert.equal(JSON.stringify(original), before);
  assert.throws(() => taskAuditPage(audit({ taskId: 'task-b' }), 'task-a', 'events'));
  assert.throws(() => taskAuditPage(audit({ section: 'operations' }), 'task-a', 'events'));
  assert.throws(() => taskAuditPage(audit({ upperPosition: 41 }), 'task-a', 'events', 'older', [''], 40));
  assert.throws(() => taskAuditPage(audit({ executionAuthorized: true }), 'task-a', 'events'));
});

test('duplicate, unordered and foreign audit rows are rejected while limited bodies stay limited', () => {
  for (const entries of [[event(40), event(40)], [event(39), event(40)], [event(41)], [event(40, { taskId: 'task-b' })]])
    assert.throws(() => taskAuditPage(audit({ entries }), 'task-a', 'events'));
  const limited = taskAuditPage(audit({ entries: [event(40, { status: 'read_limited', event: undefined })], readLimited: true }), 'task-a', 'events');
  assert.equal(limited.entries[0].status, 'read_limited');
  assert.equal(limited.entries[0].event, undefined);
  assert.equal(limited.readLimited, true);
});

test('opaque cursors must advance and terminal pages cannot keep a continuation', () => {
  assert.throws(() => taskAuditPage(audit(), 'task-a', 'events', 'opaque-next'));
  assert.throws(() => taskAuditPage(audit(), 'task-a', 'events', 'third', ['', 'opaque-next']));
  assert.throws(() => taskAuditPage(audit({ nextCursor: '' }), 'task-a', 'events'));
  assert.throws(() => taskAuditPage(audit({ hasMore: false }), 'task-a', 'events'));
  assert.throws(() => taskHeaderPage(summaries(), 'older-header-window'));
  assert.throws(() => taskHeaderPage(summaries(), 'third', ['', 'older-header-window']));
});

test('task headers retain original identities and allow filtered empty pages with a next cursor', () => {
  const page = taskHeaderPage(summaries());
  assert.deepEqual(page.tasks.map(row => row.id), ['task-b', 'task-a']);
  assert.equal(page.tasks[0].raw?.id, 'task-b');
  const filtered = taskHeaderPage(summaries({ tasks: [], accountingRecordsExcluded: 40 }));
  assert.equal(filtered.tasks.length, 0);
  assert.equal(filtered.hasMore, true, 'excluded ledger rows do not mean the original task list has ended');
  assert.throws(() => taskHeaderPage(summaries({ auditBodyIncluded: true })));
  assert.throws(() => taskHeaderPage(summaries({ tasks: [header('task-a', 1), header('task-a', 2)] })));
  assert.throws(() => taskHeaderPage(summaries({ tasks: [header('', 1)] })));
  assert.throws(() => taskHeaderPage(summaries({ tasks: Array.from({ length: 41 }, (_, i) => header(String(i), i)) })));
});

test('a loaded task window deduplicates and uses newer native state without admitting other windows', () => {
  const old = header('task-a', 40, { summary: '旧来源', raw: { runId: 'run-old', coordinatorEpoch: 1 } });
  const latest = header('task-a', 80, { summary: '原来源已撤回', raw: { runId: 'run-new', coordinatorEpoch: 2 } });
  const window = currentTaskHeaders([old, header('task-b', 50), old], [latest, header('outside', 100)]);
  assert.deepEqual(window.map(row => row.id), ['task-a', 'task-b']);
  assert.equal(window[0].summary, '原来源已撤回');
  assert.equal(window[0].raw?.runId, 'run-new');
  assert.notEqual(taskHistoryIdentity(old), taskHistoryIdentity(latest));
  assert.notEqual(taskHistoryIdentity(old), taskHistoryIdentity({ ...old, id: 'task-b' }));
  assert.notEqual(taskHistoryIdentity(old), taskHistoryIdentity({ ...old, raw: { ...old.raw, sourceCurrentReadRequired: true } }));
  assert.equal(currentTaskHeaders([latest], [old])[0].updatedAt, 80);
});
