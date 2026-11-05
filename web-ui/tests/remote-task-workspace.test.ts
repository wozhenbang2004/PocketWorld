import test, { afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import * as helpers from '../src/remote-task-workspace.ts';

type Data = Record<string, any>;
const openFixtures = new Set<{ unmount: () => void }>();
afterEach(() => { for (const f of openFixtures) f.unmount(); openFixtures.clear(); });
const copy = (value: any) => structuredClone(value);
const deferred = () => { let resolve!: (value: any) => void; const promise = new Promise<any>(done => { resolve = done; }); return { resolve, promise }; };
const scope = (mode = 'next_turn') => ({ mode, profileGeneration: 'a'.repeat(64), taskId: 'task', runId: 'run', coordinatorEpoch: 1, jobId: 'job', jobOperationId: 'operation', threadId: 'session', sessionId: 'session', promptRequestId: 'prompt', worker: 'pi', protocol: 'pi_rpc_v1', noTools: true });
function snapshot(patch: Data = {}): Data {
  return { taskId: 'task', windowHandle: 'original-window', reviewId: 'review-1', fresh: true, canContinue: true, draftEditorAvailable: true,
    source: { worker: 'pi', protocol: 'pi_rpc_v1', executorStatus: 'completed', jobOperationId: 'operation' },
    draftScopes: { next_turn: scope(), steer: scope('steer') }, inputDrafts: [],
    inputDelivery: { protocolEvidenceReported: true, driver: 'pi', currentTurn: { adapterMounted: false, observedAvailable: false, acceptanceProvesModelUse: false }, nextTurn: { adapterMounted: true, observedAvailable: true, noToolsSessionOnly: true } }, ...patch };
}
const queued = (id = '00000000-0000-0000-0000-000000000001', mode = 'next_turn', text = 'queued owner input'): Data => ({ id, revision: 1, mode, text, state: 'queued', scope: scope(mode), targetCurrent: true });

// Execute the production component's callbacks with explicit hook/control/bridge doubles.
// This is not a React Aria render, browser/device test or remote dispatch simulation.
function fixture(component: string, props: Data = {}, options: Data = {}) {
  const file = options.file || 'RemoteTaskWorkspace';
  const source = ts.createSourceFile(file + '.tsx', readFileSync(new URL(`../src/${file}.tsx`, import.meta.url), 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const body = source.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(source).replace(/^export /, '')).join('\n');
  const code = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
  const slots: any[] = [], effects: (() => void)[] = [], calls: Data[] = [], listeners = new Set<() => void>(), back: (() => void)[] = [];
  const docEvents = new Map<string, Set<() => void>>(), windowEvents = new Map<string, Set<() => void>>(), timers = new Map<number, { fn: () => void; delay: number }>();
  let index = 0, dirty = false, tree: any, mounted = true, writesAfterUnmount = 0, epoch = 1, nextTimer = 1;
  let view: Data = { section: 'tasks', taskId: 'task', resumeRevision: 1, ...options.view };
  const useState = (initial: any) => { const i = index++; slots[i] ??= { value: typeof initial === 'function' ? initial() : initial }; return [slots[i].value, (next: any) => { if (!mounted) writesAfterUnmount++; const value = typeof next === 'function' ? next(slots[i].value) : next; if (!Object.is(value, slots[i].value)) { slots[i].value = value; dirty = true; } }]; };
  const useRef = (initial: any) => { const i = index++; return slots[i] ??= { current: initial }; };
  const useEffect = (effect: () => any, deps: any[]) => { const i = index++, prior = slots[i]; if (!prior || !deps || deps.some((value, j) => !Object.is(value, prior.deps?.[j]))) { const slot = slots[i] = { deps, cleanup: prior?.cleanup }; effects.push(() => { slot.cleanup?.(); slot.cleanup = effect(); }); } };
  const React = { Fragment: 'Fragment', createElement: (type: any, props: any, ...children: any[]) => ({ type, props: props || {}, children: children.flat(Infinity).filter(child => child != null && child !== false) }) };
  const eventTarget = (events: Map<string, Set<() => void>>) => ({ addEventListener: (name: string, fn: () => void) => { if (!events.has(name)) events.set(name, new Set()); events.get(name)!.add(fn); }, removeEventListener: (name: string, fn: () => void) => events.get(name)?.delete(fn) });
  const document = { visibilityState: 'visible', hidden: false, ...eventTarget(docEvents) }, window = { innerWidth: 1000, innerHeight: 800, ...eventTarget(windowEvents) };
  const command = async (action: string, args: Data = {}) => { calls.push({ action, args: copy(args) }); return options.command ? options.command(action, args) : options.response; };
  const bridge = { getDocumentEpoch: () => epoch, getState: () => ({ view, capabilities: { commands: { includes: () => true } } }), subscribe: (fn: () => void) => { listeners.add(fn); return () => listeners.delete(fn); }, command };
  const dependencies: Data = { ...helpers, object: helpers.remoteObject, rows: helpers.remoteRows, React, useState, useRef, useEffect, useLayoutEffect: useEffect, bridge, document, window, crypto,
    setTimeout: (fn: () => void, delay: number) => { const id = nextTimer++; timers.set(id, { fn, delay }); return id; }, clearTimeout: (id: number) => timers.delete(id),
    registerOverlayBack: (fn: () => void) => { back.push(fn); return () => { const i = back.indexOf(fn); if (i >= 0) back.splice(i, 1); }; } };
  for (const name of ['Accordion', 'AccordionItem', 'Button', 'CheckboxField', 'RadioOptions', 'SelectField', 'StretchSwitch', 'TextAreaField', 'TextField', 'SettingsDialog', 'RemoteTaskArtifacts', 'RemoteTaskHarness', 'RemoteTaskPreview', 'RemoteTaskInputActions', 'RemoteActionReview', 'RemoteProjectReview']) dependencies[name] = name;
  const Component = new Function(...Object.keys(dependencies), code + `\nreturn ${component};`)(...Object.values(dependencies));
  const actualProps = { snapshot: snapshot(), busy: false, active: true, enabled: true, run: command, ...props };
  const render = () => { if (!mounted) return; let count = 0; do { assert.ok(count++ < 40, 'production effects settle'); dirty = false; index = 0; tree = Component(actualProps); effects.splice(0).forEach(effect => effect()); } while (dirty); };
  const walk = (node: any): any[] => !node || typeof node !== 'object' || node.type === 'SettingsDialog' && !node.props.isOpen ? [] : [node, ...node.children.flatMap(walk)];
  const content = (node: any): string => !node || typeof node !== 'object' ? String(node ?? '') : node.type === 'SettingsDialog' && !node.props.isOpen ? '' : node.children.map(content).join('');
  const find = (predicate: (node: any) => boolean) => { const value = walk(tree).find(predicate); assert.ok(value, `expected production control; content=${content(tree)}`); return value; };
  const button = (label: string) => find(node => node.type === 'Button' && content(node) === label);
  const f = { props: actualProps, calls, find, button, render, get text() { return content(tree); }, get nodes() { return walk(tree); }, get writesAfterUnmount() { return writesAfterUnmount; }, get timers() { return [...timers.values()].map(value => value.delay); },
    click(label: string) { const node = button(label); assert.ok(!node.props.disabled, label); node.props.onClick(); render(); },
    field(label: string, value: any, commit = true) { const node = find(node => ['TextAreaField', 'TextField', 'SelectField', 'RadioOptions'].includes(node.type) && node.props.label === label); assert.ok(!node.props.isDisabled); node.props.onChange(value); if (commit) render(); },
    change(patch: Data) { Object.assign(actualProps, patch); render(); },
    rotate(patch: Data = {}) { view = { ...view, resumeRevision: view.resumeRevision + 1, ...patch }; listeners.forEach(fn => fn()); render(); },
    visibility(value: string) { document.visibilityState = value; document.hidden = value !== 'visible'; docEvents.get('visibilitychange')?.forEach(fn => fn()); render(); },
    pagehide() { windowEvents.get('pagehide')?.forEach(fn => fn()); render(); },
    back() { const fn = back.at(-1); fn?.(); render(); return !!fn; },
    tick(delay: number) { const found = [...timers].find(([, timer]) => timer.delay === delay); assert.ok(found, `expected timer ${delay}, actual ${[...timers.values()].map(row => row.delay)}`); timers.delete(found[0]); found[1].fn(); render(); },
    unmount() { if (!mounted) return; mounted = false; slots.forEach(slot => slot.cleanup?.()); },
    async flush() { for (let i = 0; i < 12; i++) { await Promise.resolve(); if (dirty) render(); } },
  };
  render(); openFixtures.add(f); return f;
}

test('native-projected capability, exact saved scope and settlement all gate the current editor', () => {
  const row = queued(), view = snapshot({ inputDrafts: [row] }), editor = helpers.savedRemoteEditor(row);
  assert.equal(helpers.remoteDraftState(view, editor).canSend, true);
  for (const patch of [{ fresh: false }, { canContinue: false }, { draftEditorAvailable: false }, { teamRemoteWriter: true }, { reviewId: '' }, { windowHandle: '' }, { draftScopes: { next_turn: { ...scope(), coordinatorEpoch: 2 } } },
    { inputDelivery: { ...view.inputDelivery, nextTurn: { adapterMounted: true, observedAvailable: false } } }, { inputDrafts: [{ ...row, targetCurrent: false }] }, { inputDrafts: [{ ...row, revision: 2 }] }]) assert.equal(helpers.remoteDraftState({ ...view, ...patch }, editor).canSend, false);
  assert.equal(helpers.remoteDraftState(view, { ...editor, text: 'unsaved change' }).canSend, false);
  assert.equal(helpers.remoteDraftState(view, { ...editor, text: 'unsaved change' }).canSave, true);
  assert.equal(helpers.sameRemoteScope(scope(), { ...scope(), extra: true }), false);
  assert.equal(helpers.sameRemoteScope({}, {}), false);
});

test('queueing is local intent and sends only its exact save fields', async () => {
  const view = snapshot({ canContinue: false }); view.inputDelivery.nextTurn.observedAvailable = false;
  const f = fixture('RemoteDraftEditor', { snapshot: view }); assert.equal(f.calls.length, 0);
  f.field('草稿内容', 'one explicit local Pi draft'); f.click('保存并排队'); await f.flush();
  assert.equal(f.calls.length, 1); const { action, args } = f.calls[0]; assert.equal(action, 'remoteTask.saveDraft');
  assert.deepEqual(Object.keys(args).sort(), ['draftId', 'draftRevision', 'mode', 'queued', 'text']); assert.equal(args.text, 'one explicit local Pi draft'); assert.equal(args.mode, 'next_turn'); assert.equal(args.queued, true); assert.equal(args.draftRevision, 0); assert.match(args.draftId, /^[a-f0-9-]{36}$/);
  assert.match(f.text, /保存和排队只写入本机/); assert.match(f.text, /费用|不提供编码工具能力|无工具/);
});

test('explicit send uses only saved id and revision; old send closures reject editor and selection changes synchronously', async () => {
  const first = queued(), second = queued('00000000-0000-0000-0000-000000000002', 'next_turn', 'other queued input');
  const f = fixture('RemoteDraftEditor', { snapshot: snapshot({ inputDrafts: [first, second] }) });
  f.field('已保存的草稿', first.id); const old = f.button('明确发送这个队列条目一次').props.onClick;
  f.field('草稿内容', 'unsaved owner requirement', false); old(); assert.equal(f.calls.length, 0); f.render(); assert.equal(f.button('明确发送这个队列条目一次').props.disabled, true);
  f.field('已保存的草稿', first.id); const selected = f.button('明确发送这个队列条目一次').props.onClick;
  f.field('已保存的草稿', second.id, false); selected(); assert.equal(f.calls.length, 0); f.render(); f.click('明确发送这个队列条目一次'); await f.flush();
  assert.deepEqual(f.calls, [{ action: 'remoteTask.sendDraft', args: { draftId: second.id, draftRevision: 1 } }]);
});

test('Pi queue and Codex steer obey native availability without reconstructing a target', async () => {
  for (const driver of ['pi', 'codex_app_server']) {
    const row = queued(undefined, 'steer'), view = snapshot({ inputDrafts: [row], canContinue: false });
    view.inputDelivery.driver = driver; view.inputDelivery.currentTurn = { adapterMounted: true, observedAvailable: true, delivery: driver === 'pi' ? 'native_queue' : 'original_turn_steer' };
    const f = fixture('RemoteDraftEditor', { snapshot: view }); f.field('已保存的草稿', row.id); f.click('明确发送这个队列条目一次'); await f.flush();
    assert.deepEqual(f.calls[0], { action: 'remoteTask.sendDraft', args: { draftId: row.id, draftRevision: 1 } });
    f.change({ snapshot: { ...view, inputDelivery: { ...view.inputDelivery, currentTurn: { ...view.inputDelivery.currentTurn, observedAvailable: false } } } }); assert.equal(f.button('明确发送这个队列条目一次').props.disabled, true);
    assert.match(f.text, driver === 'pi' ? /队列接受不证明模型已消费/ : /接受输入不证明模型已采用/);
  }
});

test('changed Pi identity retains the saved text with all save/send capabilities disabled', () => {
  const row = queued(), view = snapshot({ inputDrafts: [{ ...row, targetCurrent: false }], draftScopes: {} }); view.inputDelivery = { protocolEvidenceReported: false, currentTurn: {}, nextTurn: {} };
  const f = fixture('RemoteDraftEditor', { snapshot: view }); f.field('已保存的草稿', row.id);
  assert.equal(f.find(node => node.type === 'TextAreaField').props.value, row.text); assert.equal(f.button('保存并排队').props.disabled, true); assert.equal(f.button('明确发送这个队列条目一次').props.disabled, true);
  f.change({ snapshot: { ...view, draftEditorAvailable: false } }); assert.equal(f.nodes.length, 0, 'unproven generic harness keeps the original native restriction');
});

test('one explicit clipboard read only appends local text; repeat or old-selection callbacks cannot read again', async () => {
  const first = queued(), second = queued('00000000-0000-0000-0000-000000000002', 'next_turn', 'second persisted');
  const f = fixture('RemoteDraftEditor', { snapshot: snapshot({ inputDrafts: [first, second] }) }, { response: { text: 'LOCAL_CLIP_中文\nsecond', readOnce: true } });
  f.field('已保存的草稿', first.id); const old = f.button('读取本机剪贴板并追加一次').props.onClick;
  f.field('已保存的草稿', second.id, false); await old(); assert.equal(f.calls.length, 0); f.render();
  const current = f.button('读取本机剪贴板并追加一次').props.onClick; current(); current(); await f.flush();
  assert.deepEqual(f.calls, [{ action: 'remoteTask.clipboard', args: { draftId: second.id, draftRevision: 1, mode: 'next_turn' } }]);
  assert.equal(f.find(node => node.type === 'TextAreaField').props.value, 'second persisted\nLOCAL_CLIP_中文\nsecond'); assert.equal(f.button('明确发送这个队列条目一次').props.disabled, true);
  assert.match(f.text, /尚未保存，也未发送/); assert.equal(f.button('读取本机剪贴板并追加一次').props.disabled, true);
});

test('late clipboard results never replace a changed, retired or unmounted local editor', async () => {
  for (const action of ['edit', 'review', 'hidden', 'unmount']) {
    const wait = deferred(), f = fixture('RemoteDraftEditor', {}, { command: () => wait.promise }); f.field('草稿内容', 'keep'); const previousEdit = f.find(node => node.type === 'TextAreaField').props.onChange; f.click('读取本机剪贴板并追加一次');
    if (action === 'edit') { previousEdit('newer text'); f.render(); } else if (action === 'review') f.change({ snapshot: snapshot({ reviewId: 'new-review' }) }); else if (action === 'hidden') f.visibility('hidden'); else f.unmount();
    wait.resolve({ readOnce: true, text: 'old clipboard' }); await f.flush(); assert.equal(f.calls.length, 1); assert.equal(f.find(node => node.type === 'TextAreaField').props.value.includes('old clipboard'), false); assert.equal(f.writesAfterUnmount, 0);
  }
});

test('clipboard bounds preserve exact newlines and reject malformed or oversized samples', () => {
  assert.equal(helpers.appendRemoteClipboard('old', { readOnce: true, text: '中文\nsecond' }), 'old\n中文\nsecond');
  assert.equal(helpers.appendRemoteClipboard('old\n', { readOnce: true, text: 'next' }), 'old\nnext');
  for (const sample of [{ readOnce: false, text: 'text' }, { accepted: true }, { readOnce: true, text: '\0bad' }, { readOnce: true, text: '字'.repeat(12000) }]) assert.throws(() => helpers.appendRemoteClipboard('old', sample));
});

test('public child filtering uses only native-projected identities and never turns an unknown child into parent', () => {
  const source = (key: string, kind: string, nativeChildThreadId = '') => ({ key, kind, nativeChildThreadId, sourceProtocol: 'codex_public_child_v1', executionAuthorized: false, childSessionControl: false });
  const rows = [{ event: { text: 'parent' }, source: source('main_or_unspecified', 'main') }, { event: { text: 'PUBLIC child A', textForm: 'received_public_delta_not_complete_message' }, source: source('child-a', 'child', 'child-a') }, { event: { text: 'PUBLIC child B' }, source: source('child-b', 'child', 'child-b') }, { event: { text: 'UNCONFIRMED CHILD' }, source: { kind: 'child' } }];
  const f = fixture('RemoteProgress', { snapshot: snapshot({ progress: { events: rows, readStatus: 'confirmed', unconfirmedSources: 1 } }) });
  f.field('进度来源', 'child-a'); assert.match(f.text, /PUBLIC child A/); assert.doesNotMatch(f.text, /PUBLIC child B|UNCONFIRMED CHILD/); assert.match(f.text, /公开增量观察，不代表完整消息/);
  f.field('进度来源', 'child-b'); assert.match(f.text, /PUBLIC child B/); assert.doesNotMatch(f.text, /PUBLIC child A/);
  f.field('进度来源', 'main_or_unspecified'); assert.match(f.text, /parent/); assert.doesNotMatch(f.text, /PUBLIC child A|PUBLIC child B/); assert.equal(f.calls.length, 0);
});

const listing = (id = 'old-listing', identity = 'run-1') => ({ listingHandle: id, dataOnly: true, authorizesExecution: false, managedConnections: [{ profileId: 'computer', contexts: [{ environmentId: 'workspace', sessions: [{ taskId: 'original-task', worker: 'pi', sessionEvidence: 'native_protocol_observed', rowIdentity: identity }] }] }] });
test('managed sessions are metadata-only; stale list, background, pagehide and disposed buttons cannot navigate', async () => {
  let value = listing(); const f = fixture('RemoteManagedSessionsWorkspace', {}, { response: value, command: (action: string) => action === 'remoteSessions.read' ? value : { navigationRequested: true, taskId: 'original-task' }, view: { taskId: null } }); await f.flush();
  assert.deepEqual(f.calls.map(call => call.action), ['remoteSessions.read']); const old = f.find(node => node.type === 'Button' && node.props.className === 'pw-remote-session').props.onClick;
  value = listing('new-listing', 'run-2'); f.click('刷新本机原任务清单'); await f.flush(); await old(); assert.equal(f.calls.filter(call => call.action === 'remoteSessions.open').length, 0);
  const fresh = f.find(node => node.type === 'Button' && node.props.className === 'pw-remote-session').props.onClick; await fresh(); await f.flush(); assert.deepEqual(f.calls.at(-1), { action: 'remoteSessions.open', args: { listingHandle: 'new-listing', taskId: 'original-task', rowIdentity: 'run-2' } });
  f.pagehide(); await fresh(); assert.equal(f.calls.filter(call => call.action === 'remoteSessions.open').length, 1); f.unmount(); await fresh(); assert.equal(f.calls.filter(call => call.action === 'remoteSessions.open').length, 1);
});

test('an open that returns after task retirement is closed and cannot be adopted in the new window', async () => {
  const waiting = deferred(), f = fixture('RemoteTaskWorkspace', { taskId: 'task' }, { command: (action: string) => action === 'remoteTask.open' ? waiting.promise : { closed: true } });
  assert.deepEqual(f.calls, [{ action: 'remoteTask.open', args: { taskId: 'task' } }]); f.rotate(); waiting.resolve({ snapshot: snapshot() }); await f.flush();
  assert.equal(f.calls.at(-1)?.action, 'remoteTask.close'); assert.equal(f.nodes.some(node => node.type === 'RemoteTaskArtifacts'), false); assert.equal(f.calls.filter(call => call.action === 'remoteTask.open').length, 1);
});

test('original progress polling is GET-only, pauses for a review and stops on native unconfirmed policy', async () => {
  let reads = 0; const f = fixture('RemoteTaskWorkspace', { taskId: 'task' }, { command: (action: string) => action === 'remoteTask.open' ? { snapshot: snapshot({ readPollAllowed: true, readPollDelayMillis: 4000 }) } : action === 'remoteTask.refresh' ? { snapshot: snapshot({ reviewId: `read-${++reads}`, readPollAllowed: false }) } : { closed: true } }); await f.flush();
  const release = helpers.holdRemoteReads(); f.tick(250); assert.equal(reads, 0); release(); f.tick(500); await f.flush(); assert.equal(reads, 1); assert.equal(f.timers.length, 0); assert.deepEqual(f.calls.map(call => call.action), ['remoteTask.open', 'remoteTask.refresh']);
  f.visibility('hidden'); f.visibility('visible'); await f.flush(); assert.equal(reads, 1);
});

test('digest review consumes only its Back entry, refuses replaced/expired scope and dispatches once', () => {
  let confirmed = 0, closed = 0; const f = fixture('RemoteActionReview', { identity: 'window:digest-a', title: 'Exact scope', active: true, busy: false, onClose: () => closed++, onConfirm: () => confirmed++, expiresAt: Date.now() + 60000 }, { file: 'RemoteTaskArtifacts' });
  assert.equal(helpers.remoteReadsPaused(), true); f.find(node => node.type === 'CheckboxField').props.onChange(true); f.render(); const old = f.button('确认这一次').props.onClick;
  f.change({ identity: 'window:digest-b' }); old(); assert.equal(confirmed, 0); assert.equal(f.button('确认这一次').props.disabled, true);
  f.find(node => node.type === 'CheckboxField').props.onChange(true); f.render(); const now = f.button('确认这一次').props.onClick; now(); now(); assert.equal(confirmed, 1);
  f.back(); now(); assert.equal(confirmed, 1); assert.equal(closed, 1); f.unmount(); assert.equal(helpers.remoteReadsPaused(), false);
  const expired = fixture('RemoteActionReview', { identity: 'expired', title: 'scope', active: true, busy: false, onClose: () => {}, onConfirm: () => confirmed++, expiresAt: Date.now() - 1 }, { file: 'RemoteTaskArtifacts' }); expired.find(node => node.type === 'CheckboxField').props.onChange(true); expired.render(); expired.button('确认这一次').props.onClick(); assert.equal(confirmed, 1);
});

test('input attachment selection sends exact native ids and hashes, without borrowing chat attachments', async () => {
  let view: Data = { taskId: 'task', pageHandle: 'input-window', viewHandle: 'view-1', request: { profileId: 'computer', environmentId: 'workspace', worker: 'codex_app_server' }, files: [], availableFiles: [{ id: 'file-1', sha256: 'a'.repeat(64), name: 'original.txt', size: 50, mimeType: 'text/plain' }], registered: false };
  const f = fixture('RemoteInputFlow', { taskId: 'task', close: () => {} }, { file: 'RemoteTaskInputs', command: (action: string) => { if (action === 'remoteInputs.select') view = { ...view, viewHandle: 'view-2', files: [{ attachmentId: 'file-1', sha256: 'a'.repeat(64), bytes: 50, name: 'original.txt' }] }; return view; } }); await f.flush();
  assert.deepEqual(f.calls[0], { action: 'remoteInputs.open', args: { taskId: 'task', continuation: false } }); f.find(node => node.type === 'CheckboxField').props.onChange(true); f.render(); await f.flush();
  assert.deepEqual(f.calls[1], { action: 'remoteInputs.select', args: { files: [{ id: 'file-1', sha256: 'a'.repeat(64) }], pageHandle: 'input-window', viewHandle: 'view-1' } });
  assert.equal(f.calls.some(call => /attachments\./.test(call.action)), false); f.back(); await f.flush(); assert.equal(f.calls.at(-1)?.action, 'remoteInputs.close');
});

test('input upload keeps inspect and cancel available without reissuing the original confirmation', async () => {
  const wait = deferred(); let view: Data = { taskId: 'task', pageHandle: 'input-window', viewHandle: 'view-1', registered: false, files: [{ attachmentId: 'file-1', bytes: 50 }], availableFiles: [], review: { digest: 'review-digest', expiresAt: Date.now() + 60000 }, scope: { files: [] }, request: {} };
  const f = fixture('RemoteInputFlow', { taskId: 'task', close: () => {} }, { file: 'RemoteTaskInputs', command: (action: string) => {
    if (action === 'remoteInputs.confirm') return wait.promise;
    if (action === 'remoteInputs.inspect') { view = { ...view, registered: true, busy: true, viewHandle: 'view-2', writtenBytes: 12 }; return view; }
    if (action === 'remoteInputs.cancel') return { ...view, viewHandle: 'view-3', message: 'stop requested; not a deletion receipt' };
    return view;
  } }); await f.flush(); const confirm = f.find(node => node.type === 'RemoteActionReview').props.onConfirm; confirm(); confirm(); f.render();
  assert.equal(f.calls.filter(call => call.action === 'remoteInputs.confirm').length, 1); f.tick(1000); await f.flush(); f.click('请求停止本次原任务'); await f.flush();
  assert.deepEqual(f.calls.filter(call => call.action === 'remoteInputs.confirm')[0].args, { digest: 'review-digest', billingAcknowledged: true, pageHandle: 'input-window', viewHandle: 'view-1' });
  assert.equal(f.calls.at(-1)?.action, 'remoteInputs.cancel'); assert.equal(f.calls.at(-1)?.args.viewHandle, 'view-2');
  f.unmount(); wait.resolve({ ...view, viewHandle: 'late-confirm', busy: false }); await f.flush(); assert.equal(f.writesAfterUnmount, 0);
});

test('retired input windows leave captured attachment confirmations inert', async () => {
  for (const reason of ['hidden', 'window', 'pagehide', 'unmount']) {
    const view = { taskId: 'task', pageHandle: 'input-window', viewHandle: 'view-1', registered: false,
      files: [{ attachmentId: 'file-1', bytes: 50 }], availableFiles: [], request: {}, scope: { files: [] },
      review: { digest: 'review-digest', expiresAt: Date.now() + 60000 } };
    const f = fixture('RemoteInputFlow', { taskId: 'task', close: () => {} }, { file: 'RemoteTaskInputs', response: view });
    await f.flush();
    const confirm = f.find(node => node.type === 'RemoteActionReview').props.onConfirm;
    if (reason === 'hidden') f.visibility('hidden');
    else if (reason === 'window') f.rotate({ taskId: 'other-task' });
    else if (reason === 'pagehide') f.pagehide();
    else f.unmount();
    assert.doesNotThrow(() => confirm(), reason);
    await f.flush();
    assert.equal(f.calls.some(call => call.action === 'remoteInputs.confirm'), false, reason);
    assert.equal(f.writesAfterUnmount, 0, reason);
  }
});

test('retired task windows leave captured approval decisions inert', async () => {
  for (const reason of ['hidden', 'window', 'pagehide', 'unmount']) {
    const view = snapshot({ source: { worker: 'pi', pendingApprovals: [{ requestId: 'request-1', canAccept: true, tool: 'original-command' }] } });
    const f = fixture('RemoteTaskWorkspace', { taskId: 'task' }, { command: (action: string) => action === 'remoteTask.open' ? { snapshot: view } : { closed: true } });
    await f.flush();
    const approve = f.button('只允许这一次').props.onClick;
    if (reason === 'hidden') f.visibility('hidden');
    else if (reason === 'window') f.rotate({ taskId: 'other-task' });
    else if (reason === 'pagehide') f.pagehide();
    else f.unmount();
    assert.doesNotThrow(() => approve(), reason);
    await f.flush();
    assert.equal(f.calls.some(call => call.action === 'remoteTask.approve'), false, reason);
    assert.equal(f.writesAfterUnmount, 0, reason);
  }
});

test('native preview visibility follows its own top dialog and actual unobscured placeholder', () => {
  const style = { display: 'block', visibility: 'visible', opacity: '1' }, owner: Data = { getClientRects: () => [{}], closest: () => null, style }, other: Data = { ...owner };
  const node: Data = { isConnected: true, parentElement: owner, getBoundingClientRect: () => ({ left: 10, top: 10, right: 300, bottom: 400 }), contains: (hit: any) => hit === node, closest: (selector: string) => selector.includes('[hidden]') ? null : owner };
  let dialogs: Data[] = [owner], hit: any = node;
  const page: Data = { hidden: false, defaultView: { innerWidth: 1000, innerHeight: 800, getComputedStyle: (item: Data) => item.style || style }, querySelectorAll: () => dialogs, elementFromPoint: () => hit };
  assert.equal(helpers.remotePreviewIsVisible(node as any, page as any), true, 'its own modal must not block it');
  dialogs = [owner, other]; assert.equal(helpers.remotePreviewIsVisible(node as any, page as any), false, 'a nested modal hides the native surface');
  dialogs = [owner]; hit = other; assert.equal(helpers.remotePreviewIsVisible(node as any, page as any), false, 'a real overlay at the placeholder blocks the renderer');
  hit = node; owner.style = { ...style, opacity: '0' }; assert.equal(helpers.remotePreviewIsVisible(node as any, page as any), false); owner.style = style;
  page.hidden = true; assert.equal(helpers.remotePreviewIsVisible(node as any, page as any), false); page.hidden = false;
  node.closest = () => owner; assert.equal(helpers.remotePreviewIsVisible(node as any, page as any), false, 'hidden/inert ancestor cannot leak a native overlay');
});

test('SSH preview review keeps the original profile binding, port and TTL and closes on pagehide', async () => {
  let view: Data = { pageHandle: 'preview-window', taskId: 'task', mounted: false, starting: false, profiles: [{ id: 'ssh-original', name: 'Host', host: 'host.invalid', bindingHash: 'b'.repeat(64) }] };
  const f = fixture('RemoteTaskPreview', { taskId: 'task', enabled: true }, { file: 'RemoteTaskPreview', command: (action: string, args: Data) => {
    if (action === 'remotePreview.review') return view = { ...view, review: { digest: 'exact-tunnel', expiresAt: Date.now() + 60000, sshProfile: { id: args.profileId }, input: { remotePort: args.remotePort, timeoutSeconds: args.timeoutSeconds } } };
    if (action === 'remotePreview.start') return view = { ...view, starting: true, review: null };
    return view;
  } });
  assert.equal(f.calls.length, 0); f.click('预览这个项目已运行的网页'); await f.flush(); f.field('原 SSH 连接', 'ssh-original'); f.field('电脑上已运行的开发端口', '5173'); f.field('隧道保留秒数（10–600）', '120'); f.click('核对准确转发范围'); await f.flush();
  assert.deepEqual(f.calls[1], { action: 'remotePreview.review', args: { profileId: 'ssh-original', bindingHash: 'b'.repeat(64), remotePort: 5173, timeoutSeconds: 120, pageHandle: 'preview-window' } });
  const old = f.find(node => node.type === 'RemoteActionReview').props.onConfirm; f.field('电脑上已运行的开发端口', '4173', false); old(); assert.equal(f.calls.some(call => call.action === 'remotePreview.start'), false); f.render();
  f.click('核对准确转发范围'); await f.flush(); f.find(node => node.type === 'RemoteActionReview').props.onConfirm(); f.render(); await f.flush(); assert.deepEqual(f.calls.at(-1), { action: 'remotePreview.start', args: { digest: 'exact-tunnel', accepted: true, pageHandle: 'preview-window' } });
  assert.match(f.text, /尚未确认已建立/); f.pagehide(); await f.flush(); assert.equal(f.calls.at(-1)?.action, 'remotePreview.close'); assert.equal(f.calls.filter(call => call.action === 'remotePreview.openPage').length, 1);
});

test('a late original SSH open is closed after its owner task changes', async () => {
  const wait = deferred(), f = fixture('RemoteTaskPreview', { taskId: 'task', enabled: true }, { file: 'RemoteTaskPreview', command: (action: string) => action === 'remotePreview.openPage' ? wait.promise : { closed: true } });
  f.click('预览这个项目已运行的网页'); f.rotate({ taskId: 'other-task' }); wait.resolve({ pageHandle: 'late-tunnel-window', taskId: 'task' }); await f.flush();
  assert.deepEqual(f.calls.at(-1), { action: 'remotePreview.close', args: { pageHandle: 'late-tunnel-window' } }); assert.equal(f.calls.some(call => call.action === 'remotePreview.start'), false);
});

test('native approval claims stay scoped to original run and suppress repeat decisions', () => {
  const original = { tool: 'remote_approval', runId: 'run-1', arguments: { requestId: 'request-1' }, state: 'unknown' };
  assert.equal(helpers.remoteApprovalClaim({ task: { runId: 'run-1', operations: [original] } }, 'request-1'), original);
  assert.equal(helpers.remoteApprovalClaim({ task: { runId: 'run-2', operations: [original] } }, 'request-1'), undefined);
  assert.equal(helpers.remoteApprovalClaim({ task: { runId: 'run-1', operations: [original] } }, 'request-2'), undefined);
});
