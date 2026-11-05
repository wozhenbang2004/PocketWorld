import test from 'node:test';
import assert from 'node:assert/strict';
import {getTaskOperations, presentOperation, getRecentTaskProgress, getTaskEventHistory, taskNeedsAttention, taskNeedsReconciliation} from '../src/task-presentation.ts';

const op = (extra: Record<string, unknown> = {}) => ({operationId: 'op-1', tool: 'project_list', state: 'receipt', runId: 'run-now', createdAt: 100, position: 8, arguments: {}, receipt: {ok: true}, ...extra});
const task = (raw: Record<string, unknown> = {}) => ({id: 'task-1', goal: '整理项目资料', status: 'running', summary: '保留原目标', raw: {runId: 'run-now', ...raw}});
const outcome = (kind: string, nextAction: string) => ({format: 'pocketworld.tool-outcome.v1', kind, nextAction, automaticReplayAllowed: false});

test('native tool outcomes preserve effects while explaining who must act next', () => {
  const created = op({receipt: {ok: true, status: 'created', id: 'actual-artifact', requiresPause: true}, toolOutcome: outcome('needs_user', 'ask_owner')});
  const shown = presentOperation(created);
  assert.equal(shown.status, 'waiting');
  assert.match(shown.stateLabel, /需要你处理/);
  assert.deepEqual(shown.result, created.receipt);
  assert.equal(presentOperation(op({receipt: {ok: true}, toolOutcome: outcome('effect_unknown', 'reconcile_original')})).status, 'unknown');
});

test('unknown operations outside the window override a completed task and a newer successful event', () => {
  const input = {id: 'task-1', status: 'completed', raw: {runId: 'run-now', historyHasUnconfirmedOperations: true, operations: [], events: [{type: 'tool', runId: 'run-now', status: 'completed', message: '后来读取已返回'}]}};
  const shown = getRecentTaskProgress(input);
  assert.equal(shown.status, 'unknown');
  assert.equal(shown.source, 'task');
  assert.match(shown.title, /待核对/);
});

test('native lifecycle and unread rows cannot be promoted by a forged outcome projection', () => {
  assert.equal(presentOperation(op({state: 'unknown', toolOutcome: outcome('success', 'continue')})).status, 'unknown');
  assert.equal(presentOperation(op({toolOutcome: {...outcome('success', 'continue'), automaticReplayAllowed: true}})).status, 'unknown');
  const limited = presentOperation(op({status: 'read_limited', toolOutcome: outcome('success', 'continue')}));
  assert.notEqual(limited.status, 'returned');
  assert.equal(limited.result, undefined);
});

test('native admission failure does not become an unknown effect because its code contains unknown', () => {
  const admission = op({receipt: {ok: false, code: 'unknown_tool', accepted: false, nativeDispatched: false}, toolOutcome: outcome('correctable_failure', 'revise_request')});
  assert.equal(presentOperation(admission).status, 'error');
  assert.equal(taskNeedsReconciliation(task({operations: [admission], historyHasUnconfirmedOperations: false})), false);
  assert.equal(presentOperation({...admission, receipt: {...admission.receipt as Record<string, unknown>, requiresReconciliation: true}}).status, 'unknown');
});

test('attention keeps terminal unknowns visible and respects native running facts', () => {
  for (const status of ['completed', 'failed', 'cancelled']) {
    assert.equal(taskNeedsAttention({id: 'task-1', status, raw: {historyHasUnconfirmedOperations: true}}), true);
    assert.equal(taskNeedsAttention({id: 'task-1', status, raw: {historyHasUnconfirmedOperations: false}}), false);
  }
  assert.equal(taskNeedsAttention(task({hasUnresolvedOperations: true, historyHasUnconfirmedOperations: false, operations: [op({state: 'project_running', receipt: {ok: true, status: 'running', terminal: false}})]})), false);
  assert.equal(taskNeedsReconciliation(task({historyHasUnconfirmedOperations: true, operations: [op({state: 'prepared', receipt: undefined})]})), false);
  assert.equal(taskNeedsReconciliation(task({historyHasUnconfirmedOperations: false, operations: [op({state: 'unknown'})]})), true);
});

test('bounded operation pages preserve limited rows and earlier runs without leaking their bodies', () => {
  const input = task({
    historyOperationPage: {taskId: 'task-1', section: 'operations', hasMore: true, entries: [
      op({operationId: 'new-limited', position: 10, status: 'read_limited', receipt: {ok: true}}),
      op({operationId: 'earlier', runId: 'run-old', position: 9, status: 'available'}),
      op({status: 'available'}),
    ]},
    operations: [null, op(), op({operationId: 'new-limited', receipt: {ok: true}})],
  });
  const before = JSON.stringify(input);
  const operations = getTaskOperations(input);
  assert.deepEqual(operations.map(value => value.operationId), ['new-limited', 'earlier', 'op-1']);
  assert.equal(operations[0].readLimited, true);
  assert.equal(operations[0].bodyAvailable, false);
  assert.equal(operations[0].receipt, undefined);
  assert.equal(getRecentTaskProgress(input).operationId, 'op-1');
  assert.equal(getRecentTaskProgress(input).partial, true);
  assert.equal(JSON.stringify(input), before, 'projection must not mutate native snapshots');
});

test('receipt failures cannot become returned success and unknown effects take precedence', () => {
  assert.equal(presentOperation(op({receipt: {ok: false}})).status, 'error');
  assert.equal(presentOperation(op({receipt: {status: 'failed'}})).status, 'error');
  assert.equal(presentOperation(op({receipt: {exitCode: 1}})).status, 'error');
  assert.equal(presentOperation(op({receipt: {ok: false, requiresReconciliation: true}})).status, 'unknown');
  assert.equal(presentOperation(op({state: 'remote_job_unknown', receipt: {ok: true}})).status, 'unknown');
  assert.equal(presentOperation(op({receipt: {accepted: null}})).status, 'unknown');
});

test('prepared, partial, approval, stopped and owner reconciliation retain separate meanings', () => {
  assert.equal(presentOperation(op({state: 'prepared', receipt: null})).stateLabel, '已登记，等待结果');
  assert.equal(presentOperation(op({receipt: {ok: true, terminal: false}})).stateLabel, '已返回部分结果');
  assert.equal(presentOperation(op({receipt: {code: 'approval_required'}})).status, 'needs_approval');
  assert.equal(presentOperation(op({receipt: {status: 'cancelled'}})).status, 'stopped');
  const reconciled = presentOperation(op({state: 'resolved', receipt: {source: 'owner_reconciliation', outcome: 'not_occurred'}}));
  assert.match(reconciled.stateLabel, /主人已核对未发生/);
  assert.match(reconciled.stateLabel, /不会自动重试/);
  assert.doesNotMatch(reconciled.stateLabel, /成功/);
  assert.equal(presentOperation(op({state: 'project_running'})).status, 'running');
  assert.equal(presentOperation(op({state: 'project_stopping'})).status, 'waiting');
});

test('registration and fixture timing fields cannot create duration or a tool display name', () => {
  const result = presentOperation(op({createdAt: Date.now() - 30000, durationMillis: 2000, durationMs: 2000, startedAt: 1, title: '伪标题', name: '伪名称'}));
  assert.equal(result.name, 'project_list');
  assert.equal(result.durationMs, undefined);
  assert.equal('startedAt' in result, false);
});

test('recent progress only uses readable records of the current run', () => {
  const input = task({
    operations: [op({runId: 'run-old', createdAt: 999, position: 99}), op({operationId: 'current', createdAt: 1, position: 1})],
    historyEventPage: {taskId: 'task-1', section: 'events', entries: [
      {position: 10, status: 'read_limited', event: {type: 'tool', runId: 'run-now', message: '不可读取的正文'}},
      {position: 9, status: 'available', event: {type: 'tool', runId: 'run-old', message: '上轮记录'}},
      {position: 8, status: 'available', event: {type: 'tool', runId: 'run-now', status: 'completed', message: '原工具记录已返回'}},
    ]},
  });
  const progress = getRecentTaskProgress(input);
  assert.equal(progress.source, 'event');
  assert.equal(progress.detail, '原工具记录已返回');
  assert.equal(progress.stateLabel, '已有该记录');
  assert.equal(progress.runId, 'run-now');
  assert.equal(progress.position, 8);
  assert.equal(progress.partial, true);
});

test('foreign pages, absent run identity and sparse snapshots fall back to task facts', () => {
  const foreign = task({historyOperationPage: {taskId: 'another-task', section: 'operations', entries: [op({status: 'available'})]}});
  assert.deepEqual(getTaskOperations(foreign), []);
  assert.equal(getRecentTaskProgress(foreign).source, 'task');
  const noRun = {id: 'task-1', status: 'needs_confirmation', summary: '请确认原结果', raw: {operations: [op()]}};
  assert.equal(getRecentTaskProgress(noRun).source, 'task');
  assert.equal(getRecentTaskProgress(noRun).title, '已有结果，等待确认');
  assert.equal(getRecentTaskProgress(noRun).detail, '请确认原结果');
  assert.deepEqual(getTaskOperations(null), []);
  assert.equal(getRecentTaskProgress(undefined).source, 'task');
  assert.equal(presentOperation(null).status, 'unknown');
});

test('older runs, unread bodies and fixture-only trees cannot manufacture current progress', () => {
  const input = task({
    operations: [op({runId: 'run-old', position: 100})],
    historyOperationPage: {taskId: 'task-1', section: 'operations', entries: [
      op({operationId: 'unread-current', position: 101, status: 'read_limited', receipt: {ok: true}}),
    ]},
    steps: [{title: '不属于生产schema的阶段', status: 'completed'}],
    executionTree: {roots: [{title: '不可当作生产进度'}]},
  });
  const progress = getRecentTaskProgress(input);
  assert.equal(progress.source, 'task');
  assert.equal(progress.title, '正在执行');
  assert.equal(progress.detail, '保留原目标');
  assert.equal(progress.partial, true);
});

test('event history preserves original page order and only parses available bodies', () => {
  const input = task({historyEventPage: {taskId: 'task-1', section: 'events', completeHistory: false, hasMore: true, entries: [
    {position: 3, eventKey: 'event-3', type: 'tool', status: 'available', event: {type: 'tool', status: 'completed', runId: 'run-now', at: 300, message: '原读取结果已返回'}},
    {position: 2, eventKey: 'event-2', type: 'model', status: 'read_limited', event: {runId: 'run-now', at: 999, message: '不可读取的正文', receipt: {ok: true}}},
    {position: 1, eventKey: 'event-1', type: 'run', status: 'available', event: {type: 'run', status: 'running', runId: 'run-old', at: 100, message: '早先执行记录'}},
  ]}});
  const before = JSON.stringify(input);
  const history = getTaskEventHistory(input);
  assert.deepEqual(history.entries.map(entry => entry.position), [3, 2, 1]);
  assert.deepEqual(history.entries.map(entry => entry.id), ['event-3', 'event-2', 'event-1']);
  assert.equal(history.entries[0].detail, '原读取结果已返回');
  assert.equal(history.entries[0].isCurrentRun, true);
  assert.equal(history.entries[1].detail, '此条记录正文尚未载入');
  assert.equal(history.entries[1].runId, '');
  assert.equal(history.entries[1].at, undefined);
  assert.equal(history.entries[1].bodyAvailable, false);
  assert.equal(history.entries[1].readLimited, true);
  assert.equal(history.entries[2].runId, 'run-old');
  assert.equal(history.entries[2].isCurrentRun, false);
  assert.equal(history.hasMore, true);
  assert.equal(history.partial, true);
  assert.equal(history.readLimited, true);
  assert.equal(history.completeHistory, false);
  assert.equal(JSON.stringify(input), before);
});

test('event history rejects foreign windows and preserves empty, core and snapshot limits', () => {
  const foreign = getTaskEventHistory(task({historyEventPage: {taskId: 'other', section: 'events', completeHistory: true, entries: [{position: 1, status: 'available', event: {runId: 'run-now', message: ' чужая запись '}}]}}));
  assert.deepEqual(foreign.entries, []);
  assert.equal(foreign.unavailableReason, 'foreign_page');
  assert.equal(foreign.completeHistory, false);
  const complete = {taskId: 'task-1', section: 'events', completeHistory: true, hasMore: false, readLimited: false, entries: []};
  assert.equal(getTaskEventHistory(task({historyEventPage: complete})).partial, false);
  const coreLimited = getTaskEventHistory(task({taskCoreReadLimited: true, historyEventPage: complete}));
  assert.equal(coreLimited.coreReadLimited, true);
  assert.equal(coreLimited.completeHistory, false);
  const snapshot = getTaskEventHistory(task({events: [{runId: 'run-now', type: 'tool', status: 'completed', at: 5, message: '已读取的原记录'}]}));
  assert.equal(snapshot.source, 'snapshot');
  assert.equal(snapshot.entries[0].detail, '已读取的原记录');
  assert.equal(snapshot.partial, true);
  assert.equal(snapshot.completeHistory, false);
  assert.equal(getTaskEventHistory(null).unavailableReason, 'not_loaded');
});


test('accepted and running success receipts remain pending instead of looking completed', () => {
  const accepted = presentOperation(op({receipt: {ok: true, accepted: true, status: 'accepted'}, toolOutcome: {...outcome('success', 'continue'), completion: 'pending'}}));
  assert.equal(accepted.status, 'waiting');
  assert.match(accepted.stateLabel, /尚无终态/);
  const running = presentOperation(op({receipt: {ok: true, status: 'running', terminal: false}, toolOutcome: {...outcome('success', 'continue'), completion: 'pending'}}));
  assert.equal(running.status, 'waiting');
  assert.match(running.stateLabel, /尚无终态/);
});
