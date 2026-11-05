import test, { afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import * as helpers from '../src/project-execution.ts';

type Data = Record<string, any>;
const operation = { tool: 'project_execute', operationId: 'execution-current', runId: 'original-run', receipt: { executionReceipt: { terminalMode: 'pty' } } };
const old = { ...operation, operationId: 'execution-old', runId: 'old-run' };
const task = { id: 'original-task', raw: { actorId: 'owner', spaceId: 'private', kind: 'assistant', developmentContext: {}, runId: 'original-run', operations: [old, operation] } };
const deferred = () => { let resolve!: (value: any) => void; const promise = new Promise<any>(done => { resolve = done; }); return { resolve, promise }; };
const fixtures = new Set<{ unmount: () => void }>();
afterEach(() => { fixtures.forEach(f => f.unmount()); fixtures.clear(); });

// Real production callbacks with hook, native-message, form and parser-ACK doubles.
// No claim of DOM/xterm, Android, native input delivery or visual execution.
function fixture(options: Data = {}) {
  const ast = ts.createSourceFile('ProjectExecutionWorkspace.tsx', readFileSync(new URL('../src/ProjectExecutionWorkspace.tsx', import.meta.url), 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const body = ast.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(ast).replace(/^export /, '')).join('\n');
  const code = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
  const slots: any[] = [], effects: (() => void)[] = [], calls: Data[] = [], listeners = new Set<() => void>(), backs: (() => void)[] = [];
  const docEvents = new Map<string, Set<() => void>>(), pageEvents = new Map<string, Set<() => void>>(), timers = new Map<number, { callback: () => void; delay: number }>();
  let tree: any, index = 0, dirty = false, mounted = true, writesAfterUnmount = 0, tick = 0, epoch = 1, owner = { taskId: task.id, resumeRevision: 1, section: 'tasks' };
  let latest: Data = {};
  const useState = (initial: any) => { const i = index++; slots[i] ??= { value: typeof initial === 'function' ? initial() : initial }; return [slots[i].value, (next: any) => { if (!mounted) writesAfterUnmount++; const value = typeof next === 'function' ? next(slots[i].value) : next; if (!Object.is(value, slots[i].value)) { slots[i].value = value; dirty = true; } }]; };
  const useRef = (initial: any) => { const i = index++; return slots[i] ??= { current: initial }; };
  const useEffect = (effect: () => any, deps: any[]) => { const i = index++, previous = slots[i]; if (!previous || deps.some((value, j) => !Object.is(value, previous.deps[j]))) { const slot = slots[i] = { deps, cleanup: previous?.cleanup }; effects.push(() => { slot.cleanup?.(); slot.cleanup = effect(); }); } };
  const eventTarget = (events: Map<string, Set<() => void>>) => ({ addEventListener: (name: string, callback: () => void) => { if (!events.has(name)) events.set(name, new Set()); events.get(name)!.add(callback); }, removeEventListener: (name: string, callback: () => void) => events.get(name)?.delete(callback) });
  const document = { hidden: false, ...eventTarget(docEvents) }, window = { ...eventTarget(pageEvents), setTimeout: (callback: () => void, delay: number) => { timers.set(++tick, { callback, delay }); return tick; }, clearTimeout: (id: number) => timers.delete(id) };
  const response = (action: string, args: Data): Data => {
    if (action === 'local.execution.open') latest = { handle: `view-${args.executionId}`, taskId: task.id, executionId: args.executionId, metadata: { taskId: task.id, runId: args.executionId === old.operationId ? 'old-run' : 'original-run', epoch: 4, executionId: args.executionId, observingOnly: args.observingOnly, terminalMode: 'pty', columns: 80, rows: 24, inputByteLimit: 65536, inputRequestLimit: 128, pendingInputCount: 0 }, output: { executionId: args.executionId, outputCursor: 3, nativeStatus: 'running', screen: { revision: 1, generation: 0, cols: 80, rows: 24, packets: ['YWJj'] }, hasMore: false }, ...options.opened };
    if (action === 'local.execution.open' || action === 'local.execution.readLogs') return structuredClone(latest);
    return { handle: latest.handle, taskId: latest.taskId, executionId: latest.executionId, nativeInputSettled: true, accepted: true, ...options.receipt };
  };
  const run = async (action: string, args: Data = {}) => { calls.push({ action, args: structuredClone(args) }); return options.run ? options.run(action, args, () => response(action, args)) : response(action, args); };
  const bridge = { getDocumentEpoch: () => epoch, getState: () => ({ view: owner }), command: run, subscribe: (fn: () => void) => { listeners.add(fn); return () => listeners.delete(fn); } };
  const React = { Fragment: 'Fragment', createElement: (type: any, props: any, ...children: any[]) => ({ type, props: props || {}, children: children.flat(Infinity).filter(child => child != null && child !== false) }) };
  const dependencies: Data = { ...helpers, useState, useRef, useEffect, React, bridge, document, window, registerOverlayBack: (fn: () => void) => { backs.push(fn); return () => { const i = backs.indexOf(fn); if (i >= 0) backs.splice(i, 1); }; } };
  for (const name of ['Accordion', 'AccordionItem', 'Button', 'CheckboxField', 'SelectField', 'SettingsDialog', 'TextAreaField', 'TextField', 'TerminalSurface']) dependencies[name] = name;
  const Component = new Function(...Object.keys(dependencies), code + '\nreturn ProjectExecutionWorkspace;')(...Object.values(dependencies));
  const props: Data = { task: structuredClone(task), active: true, run, ...options.props };
  const render = () => { if (!mounted) return; let rounds = 0; do { assert.ok(rounds++ < 40, 'effects settle'); dirty = false; index = 0; tree = Component(props); effects.splice(0).forEach(effect => effect()); } while (dirty); };
  const visible = (node: any) => node.type !== 'SettingsDialog' || node.props.isOpen;
  const walk = (node: any): any[] => !node || typeof node !== 'object' || !visible(node) ? [] : [node, ...node.children.flatMap(walk)];
  const content = (node: any): string => !node || typeof node !== 'object' ? String(node ?? '') : visible(node) ? node.children.map(content).join('') : '';
  const find = (predicate: (node: any) => boolean) => { const node = walk(tree).find(predicate); assert.ok(node, `production control exists: ${content(tree)}`); return node; };
  const button = (label: string) => find(node => node.type === 'Button' && content(node) === label);
  const f = { calls, props, find, button, render, get text() { return content(tree); }, get nodes() { return walk(tree); }, get timers() { return [...timers.values()].map(timer => timer.delay); }, get writesAfterUnmount() { return writesAfterUnmount; },
    expand(value = true) { tree.props.onExpandedChange(new Set(value ? [tree.children[0].props.id] : [])); render(); },
    click(label: string) { const node = button(label); assert.ok(!node.props.disabled, label); node.props.onClick(); render(); },
    field(label: string, value: any, commit = true) { const node = find(node => ['TextField', 'TextAreaField', 'SelectField'].includes(node.type) && node.props.label === label); assert.ok(!node.props.isDisabled); node.props.onChange(value); if (commit) render(); },
    accept(value = true, commit = true) { find(node => node.type === 'CheckboxField').props.onChange(value); if (commit) render(); },
    change(patch: Data) { Object.assign(props, patch); render(); },
    output(patch: Data) { latest = { ...latest, output: { ...latest.output, ...patch } }; },
    rotate() { owner = { ...owner, resumeRevision: owner.resumeRevision + 1 }; epoch++; listeners.forEach(fn => fn()); render(); },
    hide() { document.hidden = true; docEvents.get('visibilitychange')?.forEach(fn => fn()); render(); },
    pagehide() { pageEvents.get('pagehide')?.forEach(fn => fn()); render(); },
    back() { backs.at(-1)?.(); render(); },
    acknowledgeFrame() { const node = find(node => node.type === 'TerminalSurface'); node.props.onFrameApplied(node.props.frame.revision, node.props.frame.generation); render(); },
    timer() { const [id, value] = timers.entries().next().value || []; if (id) { timers.delete(id); value.callback(); render(); } },
    async flush() { for (let i = 0; i < 12; i++) { await Promise.resolve(); if (dirty) render(); } },
    unmount() { if (!mounted) return; mounted = false; for (const slot of slots) slot?.cleanup?.(); }
  };
  fixtures.add(f); render(); return f;
}
async function open(f: ReturnType<typeof fixture>, observingOnly = false) { f.expand(); f.click(observingOnly ? '查看原执行日志' : '打开原交互终端'); await f.flush(); }

test('original operation locators preserve current PTY versus old read-only scope and never acquire authority', () => {
  const rows = helpers.projectExecutionRows(task); assert.equal(rows.length, 2); assert.equal(rows[0].interactive, false); assert.equal(rows[1].interactive, true);
  assert.equal(helpers.projectExecutionRows({ ...task, raw: { ...task.raw, origin: { teamId: 'team' } } }).length, 0);
  assert.equal(helpers.projectExecutionRows(task, { ...operation, tool: 'project_stop' }).length, 0);
  assert.equal(helpers.projectExecutionRows(task, { ...operation, operationId: '../other' }).length, 0);
  const meta = { observingOnly: false, terminalMode: 'pty' }, output = { nativeStatus: 'running' };
  assert.equal(helpers.projectExecutionCanInput(meta, output, true, false, false), true);
  for (const patch of [{ observingOnly: true }, { inputUncertain: true }, { pendingInputCount: 1 }]) assert.equal(helpers.projectExecutionCanInput({ ...meta, ...patch }, output, true, false, false), false);
  for (const patch of [{ outputTruncated: true }, { logIncompleteTail: true }, { screen: { gap: true } }, { nativeStatus: 'unknown' }, { terminal: true }]) assert.equal(helpers.projectExecutionCanInput(meta, { ...output, ...patch }, true, false, false), false);
});

test('runtime activity requires exact execution and keeps original phase and unknown/cold polling rules', () => {
  const activity = { format: 'pocketworld.runtime-activity.v1', operationId: operation.operationId, state: 'inferred_idle', observationWindow: { phase: 'background_observation' }, input: { state: 'unknown' } };
  assert.match(helpers.projectExecutionActivity(activity, operation.operationId), /总期限未延长.*仅为静默推测.*不重发/); assert.equal(helpers.projectExecutionActivity(activity, old.operationId), '');
  for (const status of ['unknown', 'interrupted', 'completed', 'failed', 'cancelled', 'timed_out']) assert.equal(helpers.localReceiptPollable(status), false);
  assert.equal(helpers.localReceiptPollable('stopping'), true); assert.equal(helpers.projectExecutionShouldRead({ nativeStatus: 'unknown', hasMore: true }), false);
  assert.equal(helpers.projectExecutionShouldRead({ terminal: true, hasMore: true }), true); assert.equal(helpers.projectExecutionShouldRead({ nativeStatus: 'running', logIncompleteTail: true }), false);
});

test('explicit read-only open keeps original identity/cursor and has no input or stop affordance', async () => {
  const f = fixture(); assert.equal(f.calls.length, 0); await open(f, true);
  assert.deepEqual(f.calls[0], { action: 'local.execution.open', args: { taskId: task.id, executionId: operation.operationId, observingOnly: true } });
  assert.match(f.text, /只读观察，不能输入或停止执行/); assert.match(f.text, /原记录游标：3/); assert.equal(f.nodes.some(node => node.type === 'CheckboxField'), false);
  assert.equal(f.find(node => node.type === 'TerminalSurface').props.dimensionMode, 'project'); f.click('关闭视图'); await f.flush();
  assert.deepEqual(f.calls.at(-1), { action: 'local.execution.close', args: { handle: 'view-execution-current' } }); assert.equal(f.calls.some(call => call.action.includes('stop')), false);
});

test('input needs original scope plus explicit acceptance and rejects captured edits before render', async () => {
  const f = fixture(); await open(f); f.field('原终端普通输入', 'printf old'); assert.equal(f.button('发送这一行').props.disabled, true); assert.equal(f.calls.length, 1);
  f.accept(); const captured = f.button('发送这一行').props.onClick; f.field('原终端普通输入', 'printf current', false); captured(); assert.equal(f.calls.length, 1); f.render();
  const revoked = f.button('发送这一行').props.onClick; f.accept(false, false); revoked(); assert.equal(f.calls.length, 1); f.render(); f.accept(); f.click('发送这一行'); await f.flush();
  assert.deepEqual(f.calls.at(-1), { action: 'local.execution.input', args: { handle: 'view-execution-current', action: 'text', text: 'printf current\n' } }); assert.equal(f.find(node => node.props.label === '原终端普通输入').props.value, '');
});

test('unknown input stays locked and only an explicit original receipt can release it', async () => {
  const f = fixture({ run: (action: string, _args: Data, actual: () => Data) => ({ ...actual(), ...(action === 'local.execution.input' ? { nativeInputSettled: false, accepted: null } : {}) }) }); await open(f); f.accept(); f.field('原终端普通输入', 'one'); f.click('发送这一行'); await f.flush();
  assert.equal(f.button('Ctrl+C').props.disabled, true); assert.match(f.text, /没有自动重发/); assert.equal(f.find(node => node.props.label === '原终端普通输入').props.value, 'one');
  f.click('读取原日志与退出回执'); await f.flush(); assert.equal(f.button('Ctrl+C').props.disabled, true);
  f.click('核对原输入回执'); await f.flush(); assert.deepEqual(f.calls.at(-1), { action: 'local.execution.inputReceipt', args: { handle: 'view-execution-current' } }); assert.equal(f.button('Ctrl+C').props.disabled, false); assert.equal(f.calls.filter(call => call.action === 'local.execution.input').length, 1);
});

test('log polling waits for the actual renderer ACK, drains original terminal tail, and stops unknown states', async () => {
  const f = fixture(); await open(f); assert.equal(f.timers.length, 0); f.acknowledgeFrame(); assert.deepEqual(f.timers, [700]);
  f.output({ terminal: true, hasMore: true, nativeStatus: 'completed', outputCursor: 4, screen: { revision: 2, generation: 0, cols: 80, rows: 24, packets: ['YWJj', 'ZA=='] }, logObservationPolicy: { automaticLogPageReduced: true, ownerMessage: '本机原日志页已按原策略缩减' } }); f.timer(); await f.flush();
  assert.deepEqual(f.calls.at(-1), { action: 'local.execution.readLogs', args: { handle: 'view-execution-current' } }); assert.equal(f.timers.length, 0); f.acknowledgeFrame(); assert.deepEqual(f.timers, [100]); assert.match(f.text, /原日志页已按原策略缩减/);
  f.output({ hasMore: false }); f.timer(); await f.flush(); assert.equal(f.timers.length, 0);
  f.output({ terminal: false, hasMore: true, nativeStatus: 'unknown' }); f.click('读取原日志与退出回执'); await f.flush(); f.acknowledgeFrame(); assert.equal(f.timers.length, 0); assert.match(f.text, /不可自动轮询/);
});

test('stop review Back cancels; a confirmed original stop is once-only and does not claim resource release', async () => {
  const wait = deferred(); let actualStop: Data = {};
  const f = fixture({ run: (action: string, _args: Data, actual: () => Data) => { const result = actual(); if (action === 'local.execution.stop') { actualStop = result; return wait.promise; } return result; } }); await open(f); f.click('停止这个原进程'); f.back(); assert.equal(f.calls.length, 1);
  f.click('停止这个原进程'); const confirm = f.button('确认停止原进程一次').props.onClick; confirm(); confirm(); f.render();
  assert.equal(f.calls.filter(call => call.action === 'local.execution.stop').length, 1); assert.deepEqual(f.calls.at(-1)?.args, { handle: 'view-execution-current' });
  wait.resolve({ ...actualStop, resourcesReleased: false }); await f.flush(); assert.match(f.text, /退出与资源回收仍待原回执确认/); assert.equal(f.button('停止这个原进程').props.disabled, true); assert.equal(f.button('Ctrl+C').props.disabled, true);
  f.click('关闭视图'); await f.flush(); assert.equal(f.calls.filter(call => call.action === 'local.execution.stop').length, 1);
});

test('original project dimensions are restored and stale resize or hidden callbacks cannot send', async () => {
  const f = fixture(); await open(f); f.accept(); f.field('列数（4–400）', '400'); f.field('行数（2–200）', '200'); const stale = f.button('调整原终端尺寸').props.onClick;
  f.field('列数（4–400）', '4', false); stale(); assert.equal(f.calls.length, 1); f.render(); f.field('行数（2–200）', '2'); f.click('调整原终端尺寸'); await f.flush();
  assert.deepEqual(f.calls.at(-1)?.args, { handle: 'view-execution-current', action: 'resize', columns: 4, rows: 2 });
  f.field('原终端普通输入', 'keep draft'); const oldSend = f.button('发送这一行').props.onClick; f.expand(false); oldSend(); await f.flush(); assert.equal(f.calls.filter(call => call.action === 'local.execution.input').length, 1); f.expand(); await open(f); assert.equal(f.find(node => node.props.label === '原终端普通输入').props.value, 'keep draft');
});

test('old execution selection and view replacement cannot adopt or retarget an asynchronous original open', async () => {
  for (const retire of ['active', 'background', 'owner', 'pagehide', 'unmount']) {
    const wait = deferred(); let original: Data = {};
    const f = fixture({ run: (action: string, _args: Data, actual: () => Data) => { const result = actual(); if (action === 'local.execution.open') { original = result; return wait.promise; } return result; } }); f.expand(); f.click('打开原交互终端');
    if (retire === 'active') f.change({ active: false }); else if (retire === 'background') f.hide(); else if (retire === 'owner') f.rotate(); else if (retire === 'pagehide') f.pagehide(); else f.unmount();
    wait.resolve(original); await f.flush(); assert.equal(f.nodes.some(node => node.type === 'TerminalSurface'), false); assert.deepEqual(f.calls.at(-1), { action: 'local.execution.close', args: { handle: original.handle } }); assert.equal(f.writesAfterUnmount, 0);
  }
  const f = fixture(); f.expand(); const oldOpen = f.button('打开原交互终端').props.onClick; f.field('原项目执行', old.operationId, false); oldOpen(); assert.equal(f.calls.length, 0); f.render(); f.click('查看原执行日志'); await f.flush(); assert.equal(f.calls[0].args.executionId, old.operationId); assert.equal(f.calls[0].args.observingOnly, true);
});
