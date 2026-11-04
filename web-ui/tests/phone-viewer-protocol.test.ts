import test from 'node:test';
import assert from 'node:assert/strict';
import { PhoneViewerConnection, phoneViewerFrame, phoneViewerIdentity, phoneViewerSession, type PhoneFrameReceipt, type PhonePreviewFrame, type PhoneViewerIdentity } from '../src/phone-viewer-protocol.ts';

const identity: PhoneViewerIdentity = { taskId: 'task-a', runId: 'run-a', epoch: 7 };
const display = { sessionId: 'display-session-a', displayId: 9 };
const receipt = (patch: Partial<PhoneFrameReceipt> = {}): PhoneFrameReceipt => ({ ...identity, ...display, state: 'ready', frameGeneration: 1, capturedAt: 50_000, sourceWidth: 1080, sourceHeight: 2400, previewWidth: 432, previewHeight: 960, mime: 'image/jpeg', imageBase64: '/9j/2Q==', ...patch });
const shown = (patch: Partial<PhonePreviewFrame> = {}): PhonePreviewFrame => ({ ...receipt(), src: 'data:image/jpeg;base64,/9j/2Q==', receivedAt: 1_700_000_000_000, ...patch });
const pending = (patch: Partial<PhoneFrameReceipt> = {}) => receipt({ state: 'pending', frameGeneration: 0, capturedAt: 0, sourceWidth: 0, sourceHeight: 0, previewWidth: 0, previewHeight: 0, imageBase64: undefined, ...patch });
const flush = async () => { for (let index = 0; index < 8; index++) await Promise.resolve(); };
function deferred<T>() { let resolve!: (value: T) => void; let reject!: (reason: unknown) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }

function harness(options: { open?: () => unknown | Promise<unknown>; frame?: (args: Record<string, unknown>) => unknown | Promise<unknown> } = {}) {
  const calls: { action: string; args: Record<string, unknown> }[] = [];
  let sequence = 0, documentEpoch = 1, now = 1_700_000_000_000;
  const owner = new PhoneViewerConnection(identity, async (action, args) => {
    calls.push({ action, args });
    if (action === 'phone.viewer.open') return options.open ? options.open() : { ...identity, viewerId: `viewer-${++sequence}` };
    if (action === 'phone.viewer.frame') return options.frame ? options.frame(args) : receipt();
    if (action === 'phone.viewer.close') return { closed: true };
    throw new Error(`Unexpected command ${action}`);
  }, () => {}, { documentEpoch: () => documentEpoch, now: () => now });
  return { owner, calls, document: (value: number) => { documentEpoch = value; }, advance: (ms: number) => { now += ms; }, frames: () => calls.filter(call => call.action === 'phone.viewer.frame'), closes: () => calls.filter(call => call.action === 'phone.viewer.close') };
}

test('open and frames must match the original run and an independent display', () => {
  assert.deepEqual(phoneViewerIdentity(identity), identity);
  assert.throws(() => phoneViewerSession({ accepted: true }, identity));
  for (const change of [{ taskId: 'task-b' }, { runId: 'run-b' }, { epoch: 8 }, { epoch: '7' }, { viewerId: '' }]) assert.throws(() => phoneViewerSession({ ...identity, viewerId: 'view-a', ...change }, identity));
  const { imageBase64: _absentPixels, ...expectedPending } = pending();
  assert.deepEqual(phoneViewerFrame(pending(), identity, null, null), expectedPending);
  for (const change of [{ taskId: 'task-b' }, { runId: 'run-b' }, { epoch: 8 }, { sessionId: 'next-display' }, { displayId: 0 }, { displayId: 10 }]) assert.throws(() => phoneViewerFrame(receipt(change as Partial<PhoneFrameReceipt>), identity, display, null));
});

test('ready images obey JPEG, dimension and 2 MiB transport bounds; pending may omit geometry', () => {
  assert.equal(phoneViewerFrame(receipt(), identity, display, null).state, 'ready');
  for (const change of [{ mime: 'image/png' }, { sourceWidth: 0 }, { previewHeight: 961 }, { previewWidth: -1 }, { imageBase64: 'data:image/jpeg;base64,/9j/2Q==' }, { imageBase64: 'A'.repeat(2 * 1024 * 1024 + 4) }, { frameGeneration: 0 }, { capturedAt: 0 }]) assert.throws(() => phoneViewerFrame(receipt(change), identity, display, null));
  assert.throws(() => phoneViewerFrame(receipt({ state: 'unchanged', imageBase64: undefined }), identity, display, null));
  assert.equal(phoneViewerFrame(pending(), identity, display, null).previewWidth, 0);
  assert.equal(phoneViewerFrame(pending({ state: 'unchanged', frameGeneration: 1 }), identity, display, shown()).state, 'unchanged');
});

test('polling is serial and bounded to at most five requests per second', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const next = deferred<PhoneFrameReceipt>();
  let reads = 0;
  const h = harness({ frame: () => ++reads === 1 ? next.promise : receipt() });
  t.after(() => h.owner.dispose());
  h.owner.setVisible(true); await flush();
  assert.equal(h.frames().length, 1);
  t.mock.timers.tick(5000); await flush();
  assert.equal(h.frames().length, 1, 'A slow request cannot queue concurrent polls');
  next.resolve(receipt()); await flush();
  t.mock.timers.tick(199); await flush(); assert.equal(h.frames().length, 1);
  t.mock.timers.tick(1); await flush(); assert.equal(h.frames().length, 2);
  assert.equal(h.owner.getState().phase, 'live');
  assert.equal(h.owner.getState().frame?.receivedAt, 1_700_000_000_000, 'UI time is local, not helper uptime');
});

test('hide/show and retry retain the last image but reset the new viewer cursor to -1', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const h = harness(); t.after(() => h.owner.dispose());
  h.owner.setVisible(true); await flush();
  const first = h.owner.getState().frame;
  h.owner.setVisible(false);
  assert.equal(h.owner.getState().frame, first);
  assert.equal(h.owner.getState().phase, 'hidden');
  t.mock.timers.tick(2000); await flush(); assert.equal(h.frames().length, 1);
  h.owner.setVisible(true); await flush();
  h.owner.retry(); await flush();
  assert.deepEqual(h.frames().map(call => [call.args.viewerId, call.args.afterFrameGeneration]), [['viewer-1', -1], ['viewer-2', -1], ['viewer-3', -1]]);
  assert.deepEqual(h.closes().map(call => call.args.viewerId), ['viewer-1', 'viewer-2']);
});

test('geometry invalidation retains pixels, accepts pending zero uptime and then resumes', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const values = [receipt(), pending({ frameGeneration: 2 }), receipt({ frameGeneration: 3, capturedAt: 50_200, sourceWidth: 2400, sourceHeight: 1080, previewWidth: 960, previewHeight: 432 })];
  const h = harness({ frame: () => values.shift()! }); t.after(() => h.owner.dispose());
  h.owner.setVisible(true); await flush();
  const original = h.owner.getState().frame;
  t.mock.timers.tick(200); await flush();
  assert.equal(h.owner.getState().phase, 'waiting'); assert.equal(h.owner.getState().frame, original);
  t.mock.timers.tick(200); await flush();
  assert.equal(h.owner.getState().phase, 'live'); assert.equal(h.owner.getState().frame?.frameGeneration, 3);
  assert.equal(h.owner.getState().frame?.previewWidth, 960);
  assert.deepEqual(h.frames().map(call => call.args.afterFrameGeneration), [-1, 1, 2]);
});

test('pending receipts pin the display and advance generation without lowering uptime watermark', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const values = [pending(), receipt({ sessionId: 'successor-display' })];
  const h = harness({ frame: () => values.shift()! }); t.after(() => h.owner.dispose());
  h.owner.setVisible(true); await flush(); t.mock.timers.tick(200); await flush();
  assert.equal(h.owner.getState().phase, 'error'); assert.equal(h.owner.getState().frame, null); assert.equal(h.closes().length, 1);
  const latest = { frameGeneration: 3, capturedAt: 50_000 };
  assert.throws(() => phoneViewerFrame(receipt({ frameGeneration: 2, capturedAt: 50_100 }), identity, display, shown(), latest));
  assert.throws(() => phoneViewerFrame(receipt({ frameGeneration: 4, capturedAt: 49_999 }), identity, display, shown(), latest));
  assert.equal(phoneViewerFrame(pending({ frameGeneration: 4 }), identity, display, shown(), latest).capturedAt, 0);
});

test('a late open after cleanup releases its original handle without reading any frames', async () => {
  const open = deferred<unknown>();
  const h = harness({ open: () => open.promise });
  h.owner.setVisible(true); await flush(); h.owner.dispose();
  open.resolve({ ...identity, viewerId: 'late-original' }); await flush();
  assert.equal(h.frames().length, 0); assert.deepEqual(h.closes().map(call => call.args.viewerId), ['late-original']);
});

test('old display responses cannot overwrite a later visibility cycle', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const old = deferred<PhoneFrameReceipt>(); let count = 0;
  const h = harness({ frame: () => ++count === 1 ? old.promise : receipt({ frameGeneration: 5, capturedAt: 55_000 }) }); t.after(() => h.owner.dispose());
  h.owner.setVisible(true); await flush(); h.owner.setVisible(false); h.owner.setVisible(true); await flush();
  old.resolve(receipt()); await flush();
  assert.equal(h.owner.getState().frame?.frameGeneration, 5); assert.equal(h.owner.getState().phase, 'live');
});

test('document changes discard late receipts and require an explicit same-task retry', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const frame = deferred<PhoneFrameReceipt>();
  const h = harness({ frame: () => frame.promise }); t.after(() => h.owner.dispose());
  h.owner.setVisible(true); await flush(); h.document(2);
  frame.resolve(receipt()); await flush();
  assert.equal(h.owner.getState().phase, 'error'); assert.equal(h.owner.getState().frame, null);
  t.mock.timers.tick(5000); await flush(); assert.equal(h.frames().length, 1);
  h.owner.retry(); await flush();
  assert.deepEqual(h.frames().map(call => call.args.afterFrameGeneration), [-1, -1]);
});

test('transient failures retain the previous frame and retry with a backoff', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] }); let reads = 0;
  const h = harness({ frame: () => { if (++reads === 2) throw Object.assign(new Error('暂时无法采集'), { code: 'preview_unavailable' }); return receipt({ frameGeneration: reads, capturedAt: 50_000 + reads }); } }); t.after(() => h.owner.dispose());
  h.owner.setVisible(true); await flush(); const first = h.owner.getState().frame;
  t.mock.timers.tick(200); await flush(); assert.equal(h.owner.getState().frame, first); assert.equal(h.owner.getState().phase, 'error');
  t.mock.timers.tick(499); await flush(); assert.equal(reads, 2);
  t.mock.timers.tick(1); await flush(); assert.equal(reads, 3); assert.equal(h.owner.getState().phase, 'live');
});

test('fatal stale/ended/hidden failures stop automatic polling; completed tasks keep the last frame', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  for (const code of ['phone_viewer_stale', 'phone_viewer_ended', 'phone_viewer_hidden']) {
    let reads = 0;
    const h = harness({ frame: () => { if (++reads > 1) throw Object.assign(new Error(code), { code }); return receipt(); } });
    h.owner.setVisible(true); await flush(); const first = h.owner.getState().frame;
    t.mock.timers.tick(200); await flush(); t.mock.timers.tick(20_000); await flush();
    assert.equal(reads, 2); assert.equal(h.owner.getState().frame, first); assert.equal(h.closes().length, 1); h.owner.dispose();
  }
  const h = harness(); t.after(() => h.owner.dispose());
  h.owner.setVisible(true); await flush(); const first = h.owner.getState().frame;
  h.owner.setVisible(true, true); h.owner.retry(); t.mock.timers.tick(5000); await flush();
  assert.equal(h.owner.getState().phase, 'ended'); assert.equal(h.owner.getState().frame, first); assert.equal(h.frames().length, 1);
  assert.ok(h.calls.every(call => !call.action.includes('stop')), 'Closing a viewer never stops an execution');
});

test('hiding a fatally stale viewer does not silently reopen it on return', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const h = harness({ frame: () => { throw Object.assign(new Error('原任务已经变化'), { code: 'phone_viewer_stale' }); } }); t.after(() => h.owner.dispose());
  h.owner.setVisible(true); await flush();
  h.owner.setVisible(false); h.owner.setVisible(true); t.mock.timers.tick(1000); await flush();
  assert.equal(h.frames().length, 1); assert.equal(h.owner.getState().phase, 'error');
  h.owner.retry(); await flush(); assert.equal(h.frames().length, 2);
});

test('a preparing display becomes ready on the same viewer without a manual retry', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  let reads = 0;
  const h = harness({ frame: () => {
    if (++reads === 1) throw Object.assign(new Error('独立屏正在启动'), { code: 'phone_viewer_starting' });
    return receipt();
  } });
  t.after(() => h.owner.dispose());
  h.owner.setVisible(true); await flush();
  assert.equal(h.owner.getState().phase, 'waiting');
  assert.equal(h.owner.getState().error, '');
  assert.equal(h.owner.getState().frame, null);
  t.mock.timers.tick(499); await flush(); assert.equal(reads, 1);
  t.mock.timers.tick(1); await flush();
  assert.equal(h.owner.getState().phase, 'live');
  assert.equal(h.owner.getState().frame?.frameGeneration, 1);
  assert.deepEqual(h.frames().map(call => [call.args.viewerId, call.args.afterFrameGeneration]), [['viewer-1', -1], ['viewer-1', -1]]);
  assert.equal(h.calls.filter(call => call.action === 'phone.viewer.open').length, 1);
  assert.equal(h.closes().length, 0, 'Preparing does not close or replace the native viewer');
});
