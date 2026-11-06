import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import { localReceiptPollable } from '../src/project-execution.ts';

// Production JSX/handlers with lifecycle and control doubles. This is not DOM,
// xterm, Android, real input-delivery or visual verification.
type Data = Record<string, any>;
const task = { id: 'original-task', taskId: 'original-task', runId: 'original-run', epoch: 4, executionId: 'original-execution', goal: '原终端 A', status: 'running', raw: { nativeExecutionMode: 'OWNER_LOCAL_TERMINAL' } };
const target = { taskId: task.taskId, runId: task.runId, epoch: task.epoch, executionId: task.executionId };
const source = { projectId: 'project-a', checkoutId: 'checkout-a', sourceDigest: 'original-source-digest', fileCount: 12, bytes: 900 };
const opened = { handle: 'original-view', metadata: { columns: 80, rows: 24, workingDirectory: 'src', inputByteLimit: 65536, inputRequestLimit: 128, timeoutMillis: 300000 }, output: { nativeStatus: 'running', terminal: false, screen: null } };
function fixture(options: Data = {}) {
  const text = readFileSync(new URL('../src/LocalWorkspace.tsx', import.meta.url), 'utf8');
  const ast = ts.createSourceFile('LocalWorkspace.tsx', text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const body = ast.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(ast).replace(/^export /, '')).join('\n');
  const code = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
  const slots: any[] = [], effects: (() => void)[] = [], calls: Data[] = [], expanded = new Set<string>(), timers = new Map<number, () => void>();
  const responses: Data = { 'local.describeCheckout': source, 'local.terminal.query': { ...target, status: 'running', operationState: 'project_running' }, 'local.terminal.open': opened, 'local.terminal.review': { ...source, handle: 'review-handle', requestId: 'new-original', command: { workingDirectory: 'src', timeoutMillis: 90000, inputByteLimit: 65536 }, inputRequestLimit: 128, warnings: ['只允许普通输入'] }, 'local.terminal.confirm': { taskId: 'new-original', status: 'running', operationState: 'project_running' }, 'local.terminal.input': { accepted: true, nativeInputSettled: true }, 'local.terminal.inputReceipt': { accepted: true, nativeInputSettled: true }, 'local.terminal.stop': { ...target, status: 'failed', operationState: 'receipt' }, ...options.responses };
  let index = 0, dirty = false, tree: any, back: (() => boolean) | null = null, timerId = 0, focused = 0;
  const scroller = { scrollTop: 0 };
  const root = { closest: () => scroller, querySelector: () => ({ focus: () => focused++ }) };
  const useState = (initial: any) => { const i = index++; slots[i] ??= { value: typeof initial === 'function' ? initial() : initial }; return [slots[i].value, (next: any) => { const value = typeof next === 'function' ? next(slots[i].value) : next; if (!Object.is(value, slots[i].value)) { slots[i].value = value; dirty = true; } }]; };
  const useRef = (initial: any) => { const i = index++; return slots[i] ??= { current: initial }; };
  const useEffect = (effect: () => any, deps: any[]) => { const i = index++, prior = slots[i]; if (!prior || deps.some((value, j) => !Object.is(value, prior.deps[j]))) { const slot = slots[i] = { deps, cleanup: prior?.cleanup }; effects.push(() => { slot.cleanup?.(); slot.cleanup = effect(); }); } };
  const React = { Fragment: 'Fragment', createElement: (type: any, props: any, ...children: any[]) => { if (props?.ref && props.className?.includes('pw-local-workspace')) props.ref.current = root; return { type, props: props || {}, children: children.flat(Infinity).filter(child => child !== null && child !== undefined && child !== false) }; } };
  const dependencies: Data = { React, useState, useRef, useEffect, useLayoutEffect: useEffect, localReceiptPollable, document: { visibilityState: 'visible' }, window: { setTimeout: (callback: () => void) => { timers.set(++timerId, callback); return timerId; }, clearTimeout: (id: number) => timers.delete(id) } };
  for (const key of ['ArrowLeft', 'ChevronRight', 'Folder', 'Plus', 'RefreshCw', 'Terminal', 'Accordion', 'AccordionItem', 'Button', 'CheckboxField', 'SelectField', 'SettingsDialog', 'TextAreaField', 'TextField', 'TerminalSurface']) dependencies[key] = key;
  const Component = new Function(...Object.keys(dependencies), code + '\nreturn LocalWorkspace;')(...Object.values(dependencies));
  const props = { state: { projects: [{ id: 'project-a', name: '原项目 A' }], tasks: [task], ...options.state }, data: { checkouts: [{ id: 'checkout-a', projectId: 'project-a', name: '私人副本' }], ...options.data }, supported: () => true, registerTransientBack: (handler: any) => { back = handler; }, run: async (action: string, args: Data = {}) => { calls.push({ action, args }); const response = responses[action]; return typeof response === 'function' ? response(args) : response || {}; } };
  const hidden = (node: any) => node.props.hidden || node.type === 'SettingsDialog' && !node.props.isOpen || node.type === 'AccordionItem' && !expanded.has(node.props.id);
  const walk = (node: any, inactive = false): any[] => !node || typeof node !== 'object' ? [] : [{ ...node, visible: !inactive && !hidden(node) }, ...node.children.flatMap((child: any) => walk(child, inactive || hidden(node)))];
  const content = (node: any, visible = false): string => !node || typeof node !== 'object' ? String(node ?? '') : visible && hidden(node) ? '' : node.children.map((child: any) => content(child, visible)).join('');
  const render = () => { let rounds = 0; do { assert.ok(rounds++ < 30); dirty = false; index = 0; tree = Component(props); effects.splice(0).forEach(effect => effect()); } while (dirty); };
  const find = (predicate: (node: any) => boolean, visible = true) => { const node = walk(tree).filter(node => (!visible || node.visible) && predicate(node)).at(-1); assert.ok(node, 'production control exists'); return node; };
  const button = (label: string) => find(node => node.type === 'Button' && content(node) === label);
  render();
  return { calls, responses, scroller, timers, find, button, get visibleText() { return content(tree, true); }, get panel() { return tree.props['data-local-view']; }, get focusCount() { return focused; },
    click(label: string) { const node = button(label); assert.ok(!node.props.disabled, `${label} is enabled`); node.props.onClick(); render(); },
    field(label: string, value: string) { const node = find(node => ['TextField', 'TextAreaField', 'SelectField'].includes(node.type) && node.props.label === label); assert.ok(!node.props.isDisabled); node.props.onChange(value); render(); },
    value(label: string) { return find(node => node.props.label === label, false).props.value; },
    session() { const node = find(node => node.type === 'Button' && node.props.className === 'pw-local-session-row' && content(node).startsWith(task.goal)); assert.ok(!node.props.disabled); node.props.onClick(); render(); },
    expand(id: string) { expanded.add(id); render(); },
    acknowledge() { find(node => node.type === 'CheckboxField').props.onChange(true); render(); },
    back() { const handled = back?.(); render(); return handled; },
    unmount() { for (const slot of slots) slot?.cleanup?.(); },
    async flush() { for (let i = 0; i < 8; i++) { await Promise.resolve(); if (dirty) render(); } }
  };
}
async function ready(f: ReturnType<typeof fixture>) { f.field('原项目', 'project-a'); f.field('私人工作副本', 'checkout-a'); f.click('读取这个副本的实际版本'); await f.flush(); }
async function view(f: ReturnType<typeof fixture>) { f.session(); await f.flush(); f.click('打开原终端'); await f.flush(); f.expand('local-input-tools'); }

test('local workspace starts with source selection, without automatic prepare, probe, review or launch', async () => {
  const f = fixture(); assert.equal(f.panel, 'setup'); assert.equal(f.calls.length, 0);
  assert.doesNotMatch(f.visibleText, /核对运行时|发送这一行|调整原终端尺寸|普通环境变量/);
  await ready(f); assert.deepEqual(f.calls, [{ action: 'local.describeCheckout', args: { projectId: 'project-a', checkoutId: 'checkout-a' } }]);
  assert.match(f.visibleText, /12 个源码文件/); assert.match(f.visibleText, /核对并准备终端/); assert.doesNotMatch(f.visibleText, /普通环境变量（JSON 对象）/);
});
test('setup and terminal input drafts survive pane changes, with scroll restoration and no hidden input or polling', async () => {
  const f = fixture(); await ready(f); f.field('副本中的工作目录', 'src'); f.field('最长运行秒数（1–300）', '90'); f.expand('environment'); f.field('普通环境变量（JSON 对象）', '{"APP_LABEL":"draft"}');
  f.scroller.scrollTop = 123; await view(f); assert.equal(f.scroller.scrollTop, 0); assert.ok(f.focusCount > 0);
  f.field('普通终端输入', 'printf ready'); const oldSend = f.button('发送这一行'); f.scroller.scrollTop = 321;
  f.click('返回工作副本'); assert.equal(f.scroller.scrollTop, 123); assert.equal(f.timers.size, 0); assert.equal(f.value('副本中的工作目录'), 'src'); assert.equal(f.value('最长运行秒数（1–300）'), '90'); assert.equal(f.value('普通环境变量（JSON 对象）'), '{"APP_LABEL":"draft"}');
  oldSend.props.onClick(); await f.flush(); assert.equal(f.calls.filter(call => call.action === 'local.terminal.input').length, 0);
  f.session(); assert.equal(f.scroller.scrollTop, 321); assert.equal(f.value('普通终端输入'), 'printf ready'); assert.equal(f.calls.filter(call => call.action === 'local.terminal.open').length, 1); assert.ok(f.timers.size > 0);
});
test('review uses exact source and bounds; unknown confirmation stays visible and cannot become another launch', async () => {
  const f = fixture({ responses: { 'local.terminal.confirm': { taskId: 'new-original', status: 'reconciling', operationState: 'unknown' } } }); await ready(f); f.field('副本中的工作目录', 'src'); f.field('最长运行秒数（1–300）', '90'); f.click('核对并准备终端'); await f.flush();
  assert.deepEqual(f.calls.at(-1), { action: 'local.terminal.review', args: { projectId: 'project-a', checkoutId: 'checkout-a', expectedSourceDigest: source.sourceDigest, runtimeId: 'managed_process_v1', cwd: 'src', env: {}, timeoutMillis: 90000, columns: 80, rows: 24, inputByteLimit: 65536 } });
  assert.equal(f.button('确认打开原终端').props.disabled, true); assert.match(f.visibleText, /源码版本original-source-digest/); f.acknowledge(); f.click('确认打开原终端'); await f.flush();
  assert.equal(f.panel, 'session'); assert.match(f.visibleText, /原执行结果待核对/); f.click('返回工作副本'); assert.match(f.visibleText, /原执行结果待核对/); assert.equal(f.button('核对并准备终端').props.disabled, true);
  f.click('查询原执行'); await f.flush(); assert.deepEqual(f.calls.at(-1), { action: 'local.terminal.query', args: { taskId: 'new-original', runId: 'new-original', epoch: 1, executionId: 'new-original' } }); assert.equal(f.calls.filter(call => call.action === 'local.terminal.confirm').length, 1);
});
test('uncertain input locks all input actions across panes and only queries its original handle', async () => {
  const f = fixture({ responses: { 'local.terminal.input': { nativeInputSettled: false, status: 'unknown' }, 'local.terminal.inputReceipt': { nativeInputSettled: false } } }); await view(f); f.field('普通终端输入', 'echo one'); f.click('发送这一行'); await f.flush();
  assert.deepEqual(f.calls.at(-1), { action: 'local.terminal.input', args: { handle: 'original-view', action: 'text', text: 'echo one\n' } }); assert.equal(f.button('发送这一行').props.disabled, true); assert.equal(f.button('Ctrl+C').props.disabled, true);
  f.click('返回工作副本'); assert.match(f.visibleText, /上一输入尚未核对/); assert.equal(f.value('普通终端输入'), 'echo one'); f.click('核对原输入回执'); await f.flush(); assert.deepEqual(f.calls.at(-1), { action: 'local.terminal.inputReceipt', args: { handle: 'original-view' } });
  f.session(); assert.equal(f.button('发送这一行').props.disabled, true); assert.equal(f.calls.filter(call => call.action === 'local.terminal.input').length, 1);
});
test('close and Back only release the viewer; stop still requires review with the original identity', async () => {
  const f = fixture(); await view(f); f.field('普通终端输入', 'unsent draft'); f.click('关闭视图'); await f.flush(); assert.deepEqual(f.calls.at(-1), { action: 'local.terminal.close', args: { handle: 'original-view' } }); assert.equal(f.calls.some(call => call.action === 'local.terminal.stop'), false);
  f.click('打开原终端'); await f.flush(); assert.equal(f.value('普通终端输入'), 'unsent draft'); f.click('停止这个原进程'); assert.equal(f.back(), true); assert.equal(f.calls.some(call => call.action === 'local.terminal.stop'), false);
  f.click('停止这个原进程'); f.click('停止这个原进程'); await f.flush(); assert.deepEqual(f.calls.at(-1), { action: 'local.terminal.stop', args: target }); assert.equal(f.button('Ctrl+C').props.disabled, true); assert.match(f.visibleText, /已请求停止原进程/); assert.doesNotMatch(f.visibleText, /上一输入尚未核对/);
  f.click('返回工作副本'); assert.match(f.visibleText, /已请求停止原进程/);
});
test('read-only metadata and terminal/input bounds cannot be overridden by presentation controls', async () => {
  const f = fixture({ responses: { 'local.terminal.open': { ...opened, metadata: { ...opened.metadata, observingOnly: true } } } }); await view(f); f.field('普通终端输入', 'echo blocked'); assert.equal(f.button('发送这一行').props.disabled, true); assert.match(f.visibleText, /只读观察/); assert.match(f.visibleText, /65536 字节 · 128 次动作 · 300 秒/);
  f.expand('geometry'); f.field('列数（20–300）', '301'); assert.equal(f.button('调整原终端尺寸').props.disabled, true); assert.equal(f.calls.some(call => call.action === 'local.terminal.input'), false);
});
test('pending preparation cannot change selection and late completion cannot update an unmounted workspace', async () => {
  let resolve: (value: any) => void = () => {};
  const f = fixture({ responses: { 'local.prepareCheckout': () => new Promise(done => { resolve = done; }) } }); f.field('原项目', 'project-a'); f.click('准备新的私人副本');
  assert.equal(f.find(node => node.type === 'SelectField' && node.props.label === '原项目').props.isDisabled, true); assert.equal(f.button('准备新的私人副本').props.disabled, true);
  f.unmount(); resolve({ id: 'late-checkout' }); await f.flush(); assert.equal(f.value('私人工作副本'), ''); assert.equal(f.calls.filter(call => call.action === 'local.prepareCheckout').length, 1);
});
test('cold original receipt inventory stays read-only and unknown native status cannot restart polling', async () => {
  const f = fixture({ data: { legacyReceiptRecovery: { scope: 'legacy_readonly_command_receipts', readableOriginalRecords: 2, coldUnconfirmedRecords: 1, unreadableOriginalRecordsRetained: 1, originalInventoryUnconfirmed: true, blockingScope: 'all_checkouts', message: '原清单尚未完整核对' }, jobs: [{ id: 'original-cold', status: 'unknown', originalPersistedStatus: 'running', phase: 'original-cold-observation', coldProcessUnconfirmed: true, exitCode: null }] }, responses: { 'local.terminal.open': { ...opened, output: { ...opened.output, nativeStatus: 'unknown' } } } });
  assert.match(f.visibleText, /原清单尚未完整核对/); assert.match(f.visibleText, /可读原记录 2 条 · 冷恢复待核 1 条/); assert.equal(f.calls.length, 0);
  f.expand('runtime'); assert.match(f.visibleText, /original-cold/); assert.match(f.visibleText, /原持久化阶段running/); assert.match(f.visibleText, /original-cold-observation/); assert.match(f.visibleText, /当前状态不自动轮询/);
  await view(f); assert.equal(f.timers.size, 0); assert.equal(f.button('发送这一行').props.disabled, true); assert.equal(f.calls.some(call => /confirm|prepare|cancel/.test(call.action)), false);
});
