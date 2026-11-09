import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import * as contract from '../src/setup-contract.ts';
import { backgroundConnection } from '../src/forms-library/host-contracts.ts';

// Execute production handlers with hook/control/native doubles. These are not
// screenshots or evidence of Android system permission / installation behavior.
type Data = Record<string, any>;
const source = readFileSync(new URL('../src/SetupWorkspace.tsx', import.meta.url), 'utf8');
const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX } }).outputText;
const baseSetup = () => ({ version: 1, revision: 1, preferences: { step: 'welcome', capabilities: [], status: 'new' }, model: { configured: false, tested: false, label: '' },
  permissions: { accessibility: false, notifications: false, microphone: false, batteryExempt: false, vendorBackgroundVisited: false }, speech: { available: true, networkUse: 'unknown' } });
const baseRuntime = () => ({ format: 'pocketworld.runtime-setup-view.v1', status: 'not_prepared', ready: false, canPrepare: true, canRetry: false, canCancel: false,
  message: '尚未准备', runtime: {}, preparation: {}, operation: null, logs: null });
const flatten = (value: any): any[] => Array.isArray(value) ? value.flatMap(flatten) : value == null || value === false ? [] : [value];
function content(node: any): string { return typeof node === 'object' && node ? flatten(node.props?.children).map(content).join('') : String(node ?? ''); }
function nodes(node: any, visible = true): any[] {
  if (!node || typeof node !== 'object') return [];
  if (visible && (node.props.hidden || node.props.inert || node.props['aria-hidden'] === true)) return [];
  return [node, ...flatten(node.props.children).flatMap(child => nodes(child, visible))];
}

function fixture(options: { mode?: 'onboarding' | 'center'; setup?: Data; runtime?: Data; runtimeTaskId?: string; wrongSave?: boolean; command?: (action: string, args: Data) => Promise<unknown> | undefined } = {}) {
  let native: Data = options.setup || baseSetup(), runtime: Data = options.runtime || baseRuntime();
  const calls: { action: string; args: Data }[] = [], navigation: string[] = [], listeners = new Map<string, Set<() => void>>();
  let closed = 0, entered = false, index = 0, dirty = false, tree: any, back: (() => boolean) | null = null;
  const slots: any[] = [], effects: (() => void)[] = [];
  const useState = (initial: any) => {
    const i = index++;
    if (!slots[i]) {
      slots[i] = { value: typeof initial === 'function' ? initial() : initial };
      slots[i].set = (next: any) => { const value = typeof next === 'function' ? next(slots[i].value) : next; if (!Object.is(value, slots[i].value)) { slots[i].value = value; dirty = true; } };
    }
    return [slots[i].value, slots[i].set];
  };
  const useRef = (initial: any) => { const i = index++; slots[i] ||= { current: initial }; return slots[i]; };
  const useEffect = (effect: () => any, deps?: any[]) => {
    const i = index++, prior = slots[i];
    if (!prior || !deps || !prior.deps || deps.some((value, j) => !Object.is(value, prior.deps[j]))) {
      const slot = slots[i] = { deps, cleanup: prior?.cleanup };
      effects.push(() => { slot.cleanup?.(); slot.cleanup = effect(); });
    }
  };
  const document = { hidden: false, addEventListener(name: string, callback: () => void) { if (!listeners.has(name)) listeners.set(name, new Set()); listeners.get(name)!.add(callback); }, removeEventListener(name: string, callback: () => void) { listeners.get(name)?.delete(callback); } };
  const timers = new Map<number, () => void>(); let timerId = 0;
  const window = { setTimeout(callback: () => void) { timers.set(++timerId, callback); return timerId; }, clearTimeout(id: number) { timers.delete(id); } };
  const jsx = (type: any, props: Data) => {
    if (props?.ref && typeof type === 'string') props.ref.current = { focus() {}, scrollTo() {} };
    return typeof type === 'function' ? type(props) : { type, props: props || {} };
  };
  const commands = ['connections.enter', 'connections.leave', 'setup.read', 'setup.save', 'setup.model.test', 'setup.permission', 'setup.runtime.read', 'setup.runtime.review', 'setup.runtime.start', 'setup.runtime.cancel', 'setup.runtime.dismiss'];
  const bridge = { getDocumentEpoch: () => 1, command: async (action: string, args: Data = {}) => {
    calls.push({ action, args });
    const intercepted = options.command?.(action, args); if (intercepted) return intercepted;
    if (action === 'connections.enter') { entered = true; return {}; }
    if (action === 'connections.leave') { entered = false; return {}; }
    assert.equal(entered, true, `${action} must follow entering the setup surface`);
    if (action === 'setup.read') return structuredClone(native);
    if (action === 'setup.save') {
      assert.equal(args.expectedRevision, native.revision);
      if (args.status === 'new' || args.status === 'completed' && args.step !== 'finish') throw new Error('Invalid native first-use progress');
      if (!options.wrongSave) native = { ...native, revision: native.revision + 1, preferences: { step: args.step, capabilities: args.capabilities, status: args.status } };
      return {};
    }
    if (action === 'setup.permission') return { opened: true };
    if (action === 'setup.model.test') { native = { ...native, model: { ...native.model, tested: true } }; return {}; }
    if (action === 'setup.runtime.read') return structuredClone(runtime);
    if (action === 'setup.runtime.review') return { reviewHandle: 'native-review', review: { requestId: 'native-request', mode: args.mode, expiresAt: Date.now() + 60_000,
      title: '准备开发工具', summary: '下载并安装本机工具', downloadBytes: 1024, additionalDownloads: true, existingGenerationRetained: true, executionAuthorized: false } };
    if (action === 'setup.runtime.start') {
      assert.deepEqual(args, { reviewHandle: 'native-review' });
      runtime = { ...runtime, status: 'running', canPrepare: false, canCancel: true, operation: { taskId: 'task-a', runId: 'run-a', epoch: 2, operationId: 'install-a', status: 'running', phase: 'download', fraction: null } };
      return structuredClone(runtime);
    }
    if (action === 'setup.runtime.cancel') { runtime = { ...runtime, status: 'cancel_requested' }; return structuredClone(runtime); }
    return {};
  } };
  const require = (name: string): any => {
    if (name === 'react') return { useState, useRef, useEffect };
    if (name === 'react/jsx-runtime') return { jsx, jsxs: jsx, Fragment: 'Fragment' };
    if (name === 'motion/react') return { motion: { div: 'motion.div' }, useReducedMotion: () => true };
    if (name === '@remixicon/react') return new Proxy({}, { get: (_, key) => String(key) });
    if (name === './forms-library') return Object.fromEntries(['Accordion', 'AccordionItem', 'Button', 'CheckboxField'].map(key => [key, key]));
    if (name === './library') return { ProgressBar: 'ProgressBar' };
    if (name === './forms-library/host-contracts') return { backgroundConnection };
    if (name === './bridge') return { bridge };
    if (name === './setup-contract') return contract;
    if (name.endsWith('.css')) return {};
    throw new Error(name);
  };
  const exports: Data = {};
  new Function('exports', 'require', 'document', 'window', compiled)(exports, require, document, window);
  const props: Data = { snapshot: { state: {}, capabilities: { commands }, host: { connected: true }, composer: null, view: { resumeRevision: 0 } }, mode: options.mode || 'onboarding', runtimeTaskId: options.runtimeTaskId,
    onNavigate: (target: string) => navigation.push(target), onClose: () => { closed++; }, registerBackHandler: (handler: any) => { back = handler; } };
  const render = () => { let turns = 0; do { assert.ok(turns++ < 30, 'render settled'); dirty = false; index = 0; tree = exports.SetupWorkspace(props); effects.splice(0).forEach(effect => effect()); } while (dirty); };
  const flush = async () => { for (let i = 0; i < 12; i++) { await Promise.resolve(); render(); } };
  const find = (predicate: (node: any) => boolean) => { const result = nodes(tree).find(predicate); assert.ok(result, 'expected visible production control'); return result; };
  render();
  return { calls, navigation, flush, render, document, get closed() { return closed; }, get native() { return native; }, get step() { return tree.props['data-setup-step']; },
    get text() { return nodes(tree).filter(node => typeof node.props.children === 'string').map(node => node.props.children).join(' '); },
    find, click(label: string) { const button = find(node => node.type === 'Button' && (content(node) === label || node.props['aria-label'] === label)); assert.ok(!button.props.disabled, label); button.props.onClick(); render(); },
    select(id: string) { const checkbox = find(node => node.type === 'CheckboxField' && content(node).includes(id)); assert.ok(!checkbox.props.isDisabled); checkbox.props.onChange(true); render(); },
    changeNative(patch: Data) { native = { ...native, ...patch }; },
    resume() { props.snapshot = { ...props.snapshot, view: { resumeRevision: props.snapshot.view.resumeRevision + 1 } }; render(); },
    visible() { document.hidden = false; listeners.get('visibilitychange')?.forEach(callback => callback()); render(); },
    back() { const handled = back?.(); render(); return handled; },
    unmount() { slots.forEach(slot => slot?.cleanup?.()); },
  };
}

test('first use enters one native surface, reads facts, and can skip without granting or installing', async () => {
  const f = fixture(); await f.flush();
  assert.equal(f.step, 'welcome');
  assert.deepEqual(f.calls.map(call => call.action), ['connections.enter', 'setup.read']);
  f.click('以后再说'); await f.flush();
  assert.equal(f.native.preferences.status, 'skipped'); assert.equal(f.closed, 1);
  assert.deepEqual(f.native.preferences.capabilities, []);
  assert.equal(f.calls.some(call => ['setup.permission', 'setup.runtime.start', 'setup.model.test'].includes(call.action)), false);
});

test('model management returns to its saved step and real test is an explicit action', async () => {
  const f = fixture(); await f.flush(); f.click('开始准备'); await f.flush();
  assert.equal(f.step, 'model'); f.click('连接模型'); await f.flush();
  assert.deepEqual(f.navigation, ['connections']); assert.equal(f.native.preferences.step, 'model');
  assert.equal(f.calls.some(call => call.action === 'setup.model.test'), false);
  f.changeNative({ model: { configured: true, label: '实际模型', tested: false } }); f.resume(); await f.flush();
  assert.equal(f.step, 'model'); assert.match(f.text, /尚未测试/);
  f.click('测试文本连接'); await f.flush();
  assert.match(f.text, /文本连接测试通过/);
});

test('choosing tools only saves intent and reads state; a second explicit action starts the reviewed install', async () => {
  const f = fixture({ mode: 'center' }); await f.flush(); f.select('运行开发工具'); await f.flush();
  assert.deepEqual(f.native.preferences.capabilities, ['tools']);
  assert.equal(f.calls.some(call => call.action === 'setup.runtime.start' || call.action === 'setup.runtime.review'), false);
  f.click('准备开发工具'); await f.flush();
  assert.equal(f.calls.filter(call => call.action === 'setup.runtime.review').length, 1);
  assert.equal(f.calls.some(call => call.action === 'setup.runtime.start'), false);
  f.click('开始准备'); await f.flush();
  assert.equal(f.calls.filter(call => call.action === 'setup.runtime.start').length, 1);
  const progress = f.find(node => node.type === 'ProgressBar'); assert.equal(progress.props.isIndeterminate, true);
  f.unmount(); await Promise.resolve();
  assert.equal(f.calls.some(call => call.action === 'setup.runtime.cancel'), false, 'leaving the page does not cancel an installation');
});

test('dismissing an install review releases its native handle without starting a task', async () => {
  const f = fixture({ mode: 'center' }); await f.flush(); f.select('运行开发工具'); await f.flush(); f.click('准备开发工具'); await f.flush();
  f.click('暂不安装'); await f.flush();
  assert.deepEqual(f.calls.find(call => call.action === 'setup.runtime.dismiss')?.args, { reviewHandle: 'native-review' });
  assert.equal(f.calls.some(call => call.action === 'setup.runtime.start'), false);
});

test('an install start failure remains visible after rereading the original runtime', async () => {
  const f = fixture({ mode: 'center', command: action => action === 'setup.runtime.start' ? Promise.reject(new Error('下载清单签名不匹配')) : undefined });
  await f.flush(); f.select('运行开发工具'); await f.flush(); f.click('准备开发工具'); await f.flush();
  f.click('开始准备'); await f.flush();
  assert.equal(f.calls.filter(call => call.action === 'setup.runtime.start').length, 1);
  assert.match(f.text, /下载清单签名不匹配/);
  assert.match(f.text, /尚未准备/);
});

test('an original runtime task entry reads that record and only explicit retry follows a new operation', async () => {
  const setup = { ...baseSetup(), preferences: { step: 'finish', capabilities: ['tools'], status: 'completed' } };
  const runtime = { ...baseRuntime(), status: 'failed', canRetry: true, operation: { taskId: 'original-task', runId: 'original-run', epoch: 1, operationId: 'original-install', status: 'failed', phase: 'failed' } };
  const f = fixture({ mode: 'center', setup, runtime, runtimeTaskId: 'original-task' }); await f.flush();
  assert.equal(f.calls.find(call => call.action === 'setup.runtime.read')?.args.taskId, 'original-task');
  f.click('查看运行开发工具准备'); await f.flush(); f.click('重新准备'); await f.flush(); f.click('开始准备'); await f.flush();
  assert.equal(f.calls.filter(call => call.action === 'setup.runtime.read').at(-1)?.args.taskId, 'task-a', 'only the explicit start result can replace the original task for follow-up reads');
});

test('an original task read never adopts a different latest install', async () => {
  const setup = { ...baseSetup(), preferences: { step: 'finish', capabilities: ['tools'], status: 'completed' } };
  const runtime = { ...baseRuntime(), status: 'running', canCancel: true, operation: { taskId: 'different-latest-task', runId: 'other-run', epoch: 3, operationId: 'other-install', status: 'running', phase: 'downloading' } };
  const f = fixture({ mode: 'center', setup, runtime, runtimeTaskId: 'original-task' }); await f.flush();
  f.click('查看运行开发工具准备'); await f.flush();
  assert.match(f.text, /没有读取到这条原准备记录/);
  assert.equal(f.calls.some(call => call.action === 'setup.runtime.cancel'), false);
  assert.equal(nodes(f.find(node => node.props['aria-label'] === '开发工具准备')).some(node => node.type === 'ProgressBar'), false);
});

test('system settings exit waits for return and then reads the actual permission value', async () => {
  const f = fixture({ mode: 'center' }); await f.flush(); f.select('后台继续工作'); await f.flush();
  const reads = f.calls.filter(call => call.action === 'setup.read').length;
  f.click('允许通知'); await f.flush();
  assert.equal(f.calls.filter(call => call.action === 'setup.read').length, reads, 'do not read after launching another system screen');
  f.changeNative({ permissions: { ...f.native.permissions, notifications: true } }); f.visible(); await f.flush();
  assert.equal(f.calls.filter(call => call.action === 'setup.read').length, reads + 1);
  assert.match(f.text, /任务通知已允许/);
});

test('a return that races a permission request is read after the request settles', async () => {
  let release: (value: unknown) => void = () => {};
  const f = fixture({ mode: 'center', command: action => action === 'setup.permission' ? new Promise(resolve => { release = resolve; }) : undefined });
  await f.flush(); f.select('语音输入'); await f.flush();
  const reads = f.calls.filter(call => call.action === 'setup.read').length;
  f.click('允许麦克风'); f.visible(); await f.flush();
  assert.equal(f.calls.filter(call => call.action === 'setup.read').length, reads);
  release({ opened: true }); await f.flush();
  assert.equal(f.calls.filter(call => call.action === 'setup.read').length, reads + 1);
  assert.equal(f.native.permissions.microphone, false, 'a permission request is not a grant');
});

test('stale save acknowledgement keeps the user on the current step', async () => {
  const f = fixture({ wrongSave: true }); await f.flush(); f.click('开始准备'); await f.flush();
  assert.equal(f.step, 'welcome'); assert.equal(f.closed, 0); assert.match(f.text, /保存结果待核对/);
  assert.equal(f.calls.some(call => call.action === 'setup.permission' || call.action === 'setup.runtime.start'), false);
});

test('reopening the center preserves a completed or skipped first-use decision', async () => {
  for (const status of ['completed', 'skipped']) {
    const originalStep = status === 'completed' ? 'finish' : 'welcome';
    const f = fixture({ mode: 'center', setup: { ...baseSetup(), preferences: { step: originalStep, capabilities: [], status } } });
    await f.flush(); f.select('语音输入'); await f.flush();
    assert.equal(f.native.preferences.status, status, 'a center edit must not reactivate first-use on the next launch');
    assert.deepEqual(f.native.preferences.capabilities, ['voice'], 'the valid center edit must actually save');
    assert.equal(f.native.preferences.step, originalStep, 'center presentation does not replace the persistent first-use step');
    f.click('连接模型'); await f.flush(); assert.deepEqual(f.navigation, ['connections']);
    assert.equal(f.native.preferences.step, originalStep);
    f.click('关闭准备中心'); await f.flush(); assert.equal(f.closed, 1);
  }
});

test('text-only completion remains possible and does not claim a model was connected', async () => {
  const f = fixture(); await f.flush(); f.click('开始准备'); await f.flush(); f.click('稍后连接，继续'); await f.flush();
  f.click('查看准备状态'); await f.flush(); assert.equal(f.step, 'finish'); assert.match(f.text, /尚未连接模型/);
  f.click('进入应用'); await f.flush(); assert.equal(f.closed, 1); assert.equal(f.native.preferences.status, 'completed');
  assert.equal(f.calls.some(call => call.action.startsWith('setup.runtime.')), false);
});
