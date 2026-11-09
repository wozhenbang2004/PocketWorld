import test from 'node:test';
import assert from 'node:assert/strict';
import { permissionLabel, readSetup, readSetupRuntime, readSetupRuntimeReview, runtimeCancelInput, runtimeLogText, runtimePhaseLabel, runtimeProgressLabel,
  setupModelLabel, setupSaveConfirmed, setupSaveInput, speechNetworkLabel } from '../src/setup-contract.ts';

const setup = () => ({ version: 1, revision: 3, preferences: { step: 'welcome', capabilities: [], status: 'new' },
  model: { configured: false, label: '', tested: false }, permissions: {}, speech: {} });
const operation = () => ({ taskId: 'original-task', runId: 'original-run', epoch: 4, operationId: 'original-install', status: 'running', phase: 'download',
  completedBytes: 1024, totalBytes: 2048, progressUnit: 'bytes', fraction: .5, error: null, receipt: null });
const runtime = () => ({ format: 'pocketworld.runtime-setup-view.v1', status: 'not_prepared', ready: false, canPrepare: true, canRetry: false, canCancel: false,
  message: '尚未准备', runtime: {}, preparation: {}, operation: null, logs: null });

test('a clean setup starts with no optional capability or inferred permission', () => {
  const actual = readSetup(setup());
  assert.deepEqual(actual.preferences.capabilities, []);
  assert.equal(actual.permissions.accessibility, null);
  assert.equal(actual.permissions.notifications, null);
  assert.equal(permissionLabel(null), '待检查');
  assert.equal(setupModelLabel(actual.model), '尚未连接模型');
  assert.equal(actual.speech.available, null);
});

test('completed guide, configured model and passed text test remain separate facts', () => {
  const actual = readSetup({ ...setup(), preferences: { step: 'finish', capabilities: [], status: 'completed' }, model: { configured: true, tested: false } });
  assert.equal(actual.preferences.status, 'completed');
  assert.equal(setupModelLabel(actual.model), '已选择 · 尚未测试');
  assert.equal(readSetup({ ...setup(), model: { configured: false, tested: true } }).model.tested, false);
  assert.match(speechNetworkLabel('unknown'), /尚未确认/);
  assert.match(speechNetworkLabel('declares_no_network_requirement'), /实际听写验证/);
});

test('saving preserves revision and rejects stale or mismatched authoritative results', () => {
  const before = readSetup(setup()), next = { step: 'capabilities' as const, capabilities: ['tools' as const], status: 'in_progress' as const };
  assert.deepEqual(setupSaveInput(before, next), { expectedRevision: 3, ...next });
  const confirmed = setupSaveConfirmed(before, next, { ...setup(), revision: 4, preferences: next });
  assert.deepEqual(confirmed.preferences.capabilities, ['tools']);
  assert.throws(() => setupSaveConfirmed(before, next, { ...setup(), preferences: next }), /保存结果待核对/);
  assert.throws(() => setupSaveConfirmed(before, next, { ...setup(), revision: 4 }), /保存结果待核对/);
  assert.throws(() => readSetup({ ...setup(), preferences: { ...next, capabilities: ['tools', 'tools'] } }), /完整读取/);
  assert.throws(() => readSetup({ ...setup(), preferences: { ...next, capabilities: ['local'] } }), /完整读取/);
});

test('runtime readiness and cancellation never derive from a label or an incomplete task', () => {
  const absent = readSetupRuntime({ ...runtime(), status: 'ready', ready: false });
  assert.equal(absent.ready, false);
  assert.throws(() => runtimeCancelInput(absent), /没有可取消/);
  assert.throws(() => readSetupRuntime({ ...runtime(), operation: { taskId: 'other-task' }, canCancel: true }), /身份不完整/);
  const actual = readSetupRuntime({ ...runtime(), status: 'running', canCancel: true, operation: operation() });
  assert.deepEqual(runtimeCancelInput(actual), { taskId: 'original-task', runId: 'original-run', epoch: 4 });
});

test('native phase progress preserves bytes versus items and never invents a total', () => {
  const actual = readSetupRuntime({ ...runtime(), status: 'running', operation: operation() });
  assert.equal(runtimeProgressLabel(actual.operation!), '1.0 KB / 2.0 KB');
  const unknown = readSetupRuntime({ ...runtime(), operation: { ...operation(), fraction: Infinity, totalBytes: null, progressUnit: 'items', completedBytes: 4 } });
  assert.equal(unknown.operation!.fraction, null);
  assert.equal(runtimeProgressLabel(unknown.operation!), '4 项');
  const malformed = readSetupRuntime({ ...runtime(), operation: { ...operation(), fraction: 1.4, progressUnit: null } });
  assert.equal(malformed.operation!.fraction, null);
  assert.equal(runtimeProgressLabel(malformed.operation!), '');
  assert.equal(runtimePhaseLabel('installing_system_tools'), '安装工具与依赖');
  assert.equal(runtimePhaseLabel('unknown_future_phase'), '正在准备工具');
});

test('install review requires a native handle and is not execution authorization', () => {
  const review = { reviewHandle: 'native-review', review: { requestId: 'native-request', mode: 'prepare', expiresAt: Date.now() + 1000, executionAuthorized: false,
    title: '安装工具', summary: '下载固定工具', downloadBytes: 1024, minimumFreeBytes: 2048, additionalDownloads: true, existingGenerationRetained: true } };
  assert.equal(readSetupRuntimeReview(review).review.executionAuthorized, false);
  assert.equal(readSetupRuntimeReview(review).review.minimumFreeBytes, 2048);
  assert.throws(() => readSetupRuntimeReview({ ...review, reviewHandle: '' }), /完整读取/);
  assert.throws(() => readSetupRuntimeReview({ ...review, review: { ...review.review, executionAuthorized: true } }), /完整读取/);
});

test('native errors and original log bytes remain inspectable', () => {
  const original = readSetupRuntime({ ...runtime(), status: 'failed', operation: { ...operation(), error: { code: 'download_failed', message: 'HTTP 503', details: { stderr: 'mirror unavailable' } } },
    logs: { chunks: [{ sequence: 1, stream: 'stderr', dataBase64: Buffer.from('依赖下载失败\n').toString('base64'), bytes: 19 }], nextCursor: 1, hasMore: false, logIncompleteTail: false, outputTruncated: false } });
  assert.equal(original.operation!.error!.message, 'HTTP 503');
  assert.deepEqual(original.operation!.error!.details, { stderr: 'mirror unavailable' });
  assert.equal(runtimeLogText(original.logs), '依赖下载失败\n');
});
