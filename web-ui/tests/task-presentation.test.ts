import test from 'node:test';
import assert from 'node:assert/strict';
import {getTaskOperations, presentOperation, getRecentTaskProgress} from '../src/task-presentation.ts';

const op = (extra: Record<string, unknown> = {}) => ({operationId: 'op-1', tool: 'project_list', state: 'receipt', runId: 'run-now', createdAt: 100, position: 8, arguments: {}, receipt: {ok: true}, ...extra});
const task = (raw: Record<string, unknown> = {}) => ({id: 'task-1', goal: '整理项目资料', status: 'running', summary: '保留原目标', raw: {runId: 'run-now', ...raw}});

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
