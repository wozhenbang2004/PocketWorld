import test from 'node:test';
import assert from 'node:assert/strict';
import { browserDraftAfterWindow, browserScopeOptions, browserWorkspaceActionForFrame, browserWorkspaceContext, browserWorkspaceDraftKey, browserWorkspacePayload, browserWorkspaceState, emptyBrowserScope, type BrowserWorkspaceActionWindow } from '../src/browser-workspace-contract.ts';

const state = (extra: any = {}) => browserWorkspaceState({ browserWorkspace: { windowId: 'original-window', generation: 4, snapshotId: 'original-frame', ready: true,
  native: { profileId: 'profile-a' }, draft: { contextTaskId: 'task-a', text: 'Saved goal', ready: true }, ...extra } } as any)!;

test('a failed native snapshot still exposes its error and same-workspace refresh without frame authority', () => {
  const failed = state({ snapshotId: '', snapshotError: true, error: '读取暂不可用', native: {} });
  assert.equal(failed.error, '读取暂不可用');
  assert.deepEqual(browserWorkspacePayload(failed, 'refresh'), { windowId: 'original-window', generation: 4, snapshotId: '', command: 'refresh' });
  assert.throws(() => browserWorkspacePayload(failed, 'stop'));
  assert.throws(() => browserWorkspacePayload(failed, 'openSource', { taskId: 'task-a', sourceId: 'source-a' }));
  assert.equal(state({ snapshotId: '', snapshotError: false }), null);
});
test('window commands retain the exact visible frame and cannot replace native window identity', () => {
  const original = state();
  assert.deepEqual(browserWorkspacePayload(original, 'openSource', { taskId: 'task-a', sourceId: 'source-a' }), { windowId: 'original-window', generation: 4, snapshotId: 'original-frame', command: 'openSource', taskId: 'task-a', sourceId: 'source-a' });
  for (const changed of [{ snapshotId: 'borrowed' }, { generation: 5 }, { command: 'confirmReview' }]) assert.throws(() => browserWorkspacePayload(original, 'refresh', changed));
  assert.notEqual(browserWorkspaceContext(original, 1), browserWorkspaceContext(state({ generation: 5 }), 1));
  assert.throws(() => browserWorkspacePayload(state({ ready: false }), 'refresh'));
});
test('foreground rebind preserves unacknowledged text for its original profile and task only', () => {
  const original = state(), pending = { key: browserWorkspaceDraftKey(original), text: 'My latest text', dirty: true };
  assert.deepEqual(browserDraftAfterWindow(pending, state({ generation: 5 })), pending);
  assert.deepEqual(browserDraftAfterWindow(pending, state({ snapshotId: '', snapshotError: true, native: {}, draft: {} })), pending);
  const accepted = browserDraftAfterWindow(pending, state({ draft: { contextTaskId: 'task-a', text: pending.text, ready: true } }));
  assert.equal(accepted.dirty, false); assert.equal(accepted.text, pending.text);
  for (const changed of [state({ native: { profileId: 'profile-b' } }), state({ draft: { contextTaskId: 'task-b', text: 'Other task text', ready: true } })]) {
    const next = browserDraftAfterWindow(pending, changed); assert.equal(next.dirty, false); assert.notEqual(next.text, pending.text); assert.notEqual(next.key, pending.key);
  }
});
test('disabled continuous read/action settings never carry implicit tool or target permission', () => {
  const draft = { ...emptyBrowserScope(), saveReadSources: true, publicSeeds: 'https://example.test/', ordinaryInput: true, interactionClicks: true, interactionTargets: '#submit', actionOrigins: 'https://example.test', selectedInteractionCandidates: [{ pin: { taskId: 'old' } }] };
  const safe = browserScopeOptions(draft);
  assert.equal(safe.saveReadSources, false); assert.equal(safe.publicSeeds, ''); assert.equal(safe.ordinaryInput, false); assert.equal(safe.interactionClicks, false); assert.equal(safe.actionOrigins, ''); assert.deepEqual(safe.selectedInteractionCandidates, []); assert.equal(safe.scopeMinutes, 0);
});

const windowFor = (displayed = state()): BrowserWorkspaceActionWindow => ({ state: displayed, epoch: 1, mounted: true, visible: true, connected: true, inFlight: false });
test('old stop, revoke and handoff callbacks cannot borrow a replacement task frame', () => {
  const displayed = state({ native: { profileId: 'profile-a', activeResearchTask: { id: 'task-a', runId: 'run-a' } } });
  const current = windowFor(displayed), sent: unknown[] = [];
  const action = browserWorkspaceActionForFrame(displayed, 1, () => current);
  const oldButtons = ['stop', 'revokeReadScope', 'revokeTaskScope', 'returnForOwner'].map(command => () => sent.push(action(command)));
  current.state = state({ snapshotId: 'replacement-frame', native: { profileId: 'profile-a', activeResearchTask: { id: 'task-b', runId: 'run-b' } } });
  for (const click of oldButtons) assert.throws(click, /原显示已更新/);
  assert.equal(sent.length, 0);
  const fresh = browserWorkspaceActionForFrame(current.state, 1, () => current);
  assert.equal(fresh('stop').snapshotId, 'replacement-frame');
});
test('captured browser callbacks reject unmount, hidden windows and host identity changes before dispatch', () => {
  const displayed = state(), sent: unknown[] = [];
  let current = windowFor(displayed);
  const action = browserWorkspaceActionForFrame(displayed, 1, () => current);
  const click = () => sent.push(action('returnForOwner'));
  for (const patch of [
    { mounted: false }, { visible: false }, { connected: false }, { epoch: 2 },
    { state: state({ windowId: 'replacement-window' }) }, { state: state({ generation: 5 }) }, { state: state({ ready: false }) },
  ]) { current = { ...windowFor(displayed), ...patch }; assert.throws(click); }
  assert.equal(sent.length, 0);
});
test('an immediate second callback waits for the first receipt, while an idle current stop can stop native work', async () => {
  const displayed = state(), current = windowFor(displayed), sent: unknown[] = [];
  const action = browserWorkspaceActionForFrame(displayed, 1, () => current);
  let finish!: () => void;
  const receipt = new Promise<void>(resolve => { finish = resolve; });
  const click = async () => {
    const payload = action('stop'); current.inFlight = true; sent.push(payload);
    try { await receipt; } finally { current.inFlight = false; }
  };
  const first = click(); await assert.rejects(click(), /受理回执/); assert.equal(sent.length, 1);
  finish(); await first; assert.equal(current.inFlight, false);
  current.state = state({ busy: true });
  assert.equal(action('stop').snapshotId, 'original-frame');
  assert.throws(() => action('revokeReadScope'), /原请求正在处理/);
});
test('a continuation after awaiting a draft cannot upgrade the original button to a later frame', async () => {
  const displayed = state(), current = windowFor(displayed), sent: unknown[] = [];
  const action = browserWorkspaceActionForFrame(displayed, 1, () => current);
  let saved!: () => void;
  const save = new Promise<void>(resolve => { saved = resolve; });
  const oldContinue = (async () => { await save; sent.push(action('reviewScope', { target: 'renewActions', consent: true })); })();
  current.state = state({ snapshotId: 'after-save-replacement-frame' }); saved();
  await assert.rejects(oldContinue, /原显示已更新/); assert.equal(sent.length, 0);
  current.state = displayed;
  const fields = { taskId: 'task-a', sourceId: 'source-a', options: { selected: ['original'] } };
  const payload = action('openSource', fields); fields.options.selected[0] = 'replacement';
  assert.equal(payload.snapshotId, 'original-frame'); assert.equal(payload.taskId, 'task-a'); assert.deepEqual(payload.options.selected, ['original']);
});
