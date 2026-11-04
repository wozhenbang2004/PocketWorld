import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';

// Production JSX and handlers with explicit hook/control/host doubles.
// No DOM, real service, credential transmission or visual verification.
type Data = Record<string, any>;
const snapshot = { running: false, externalRelayAllowed: true, selectionRevision: 7, model: 'model-original', protocol: 'openai_chat', endpoints: ['/v1/chat/completions'], recentInferenceReferences: [] };
function fixture(responses: Data = {}) {
  const ast = ts.createSourceFile('ProxyWorkspace.tsx', readFileSync(new URL('../src/ProxyWorkspace.tsx', import.meta.url), 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const body = ast.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(ast).replace(/^export /, '')).join('\n');
  const code = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
  const slots: any[] = [], effects: (() => void)[] = [], calls: Data[] = [], events = new Map<string, Set<() => void>>();
  const values: Data = { 'proxy.snapshot': snapshot, 'proxy.reviewStart': { reviewHandle: 'original-review', scope: 'loopback_only', phoneExecution: false, model: snapshot.model, protocol: snapshot.protocol, port: 41234 }, 'proxy.confirmStart': { ...snapshot, running: true, sessionId: 'original-session', endpoint: 'http://127.0.0.1:41234' }, 'proxy.stop': { ...snapshot, running: false }, ...responses };
  let index = 0, dirty = false, tree: any, back: (() => boolean) | null = null;
  const useState = (initial: any) => { const i = index++; slots[i] ??= { value: typeof initial === 'function' ? initial() : initial }; return [slots[i].value, (next: any) => { const value = typeof next === 'function' ? next(slots[i].value) : next; if (!Object.is(value, slots[i].value)) { slots[i].value = value; dirty = true; } }]; };
  const useRef = (initial: any) => { const i = index++; return slots[i] ??= { current: initial }; };
  const useEffect = (effect: () => any, deps?: any[]) => { const i = index++, prior = slots[i]; if (!deps || !prior?.deps || deps.some((value, j) => !Object.is(value, prior.deps[j]))) { const slot = slots[i] = { deps, cleanup: prior?.cleanup }; effects.push(() => { slot.cleanup?.(); slot.cleanup = effect(); }); } };
  const React = { Fragment: 'Fragment', createElement: (type: any, props: any, ...children: any[]) => ({ type, props: props || {}, children: children.flat(Infinity).filter(child => child !== null && child !== undefined && child !== false) }) };
  const listen = { addEventListener: (name: string, callback: () => void) => { if (!events.has(name)) events.set(name, new Set()); events.get(name)!.add(callback); }, removeEventListener: (name: string, callback: () => void) => events.get(name)?.delete(callback) };
  const document = { visibilityState: 'visible', ...listen };
  const dependencies: Data = { React, useState, useRef, useEffect, document, window: listen };
  for (const key of ['ArrowUpRight', 'ChevronRight', 'History', 'RefreshCw', 'Server', 'Accordion', 'AccordionItem', 'Button', 'CheckboxField', 'SettingsDialog', 'TextField', 'ProgressBar']) dependencies[key] = key;
  const Component = new Function(...Object.keys(dependencies), code + '\nreturn ProxyWorkspace;')(...Object.values(dependencies));
  const props = { state: {}, registerTransientBack: (handler: any) => { back = handler; }, run: async (action: string, args: Data = {}) => { calls.push({ action, args }); const response = values[action]; return typeof response === 'function' ? response(args) : response || {}; } };
  const hidden = (node: any) => node.props.hidden || node.type === 'SettingsDialog' && !node.props.isOpen || node.type === 'AccordionItem';
  const walk = (node: any, inactive = false): any[] => !node || typeof node !== 'object' ? [] : [{ ...node, visible: !inactive && !hidden(node) }, ...node.children.flatMap((child: any) => walk(child, inactive || hidden(node)))];
  const content = (node: any, visible = false): string => !node || typeof node !== 'object' ? String(node ?? '') : visible && hidden(node) ? '' : node.children.map((child: any) => content(child, visible)).join('');
  const render = () => { let rounds = 0; do { assert.ok(rounds++ < 30); dirty = false; index = 0; tree = Component(props); effects.splice(0).forEach(effect => effect()); } while (dirty); };
  const find = (predicate: (node: any) => boolean, visible = true) => { const node = walk(tree).find(node => (!visible || node.visible) && predicate(node)); assert.ok(node, 'expected production control'); return node; };
  const button = (label: string) => find(node => node.type === 'Button' && (content(node) === label || node.props['aria-label'] === label));
  render();
  return { values, calls, find, button, get text() { return content(tree, true); },
    click(label: string) { const node = button(label); assert.ok(!node.props.disabled); node.props.onClick(); render(); },
    field(label: string, value: string) { const node = find(node => node.type === 'TextField' && node.props.label === label); assert.ok(!node.props.isDisabled); node.props.onChange(value); render(); },
    acknowledge() { find(node => node.type === 'CheckboxField' && content(node).startsWith('我已保存')).props.onChange(true); render(); },
    back() { const handled = back?.(); render(); return handled; },
    hide() { document.visibilityState = 'hidden'; for (const callback of [...events.get('visibilitychange') || []]) callback(); render(); },
    async flush() { for (let i = 0; i < 8; i++) { await Promise.resolve(); if (dirty) render(); } },
  };
}
async function prepare(f: ReturnType<typeof fixture>) {
  await f.flush(); f.click('配置并启动'); f.field('监听端口，0 为自动选择', '41234'); f.field('独立访问令牌', 'fixture_not_a_real_secret_12345678'); f.click('核对启动配置'); await f.flush();
}
test('proxy overview reads status without starting a service or exposing configuration fields', async () => {
  const f = fixture(); assert.match(f.text, /尚未读取服务状态/); assert.doesNotMatch(f.text, /独立访问令牌|服务正在运行/);
  await f.flush(); assert.match(f.text, /服务尚未运行/); assert.match(f.text, /只监听这台手机/); assert.deepEqual(f.calls, [{ action: 'proxy.snapshot', args: {} }]);
  assert.equal(f.button('配置并启动').props.disabled, false); assert.doesNotMatch(f.text, /v1\/chat/);
});
test('review clears the token, requires explicit acknowledgement and confirms only the original handle', async () => {
  const f = fixture(); await prepare(f); assert.deepEqual(f.calls.at(-1), { action: 'proxy.reviewStart', args: { port: 41234, token: 'fixture_not_a_real_secret_12345678' } });
  assert.equal(f.find(node => node.type === 'TextField' && node.props.label === '独立访问令牌', false).props.value, ''); assert.equal(f.button('确认并启动本机服务').props.disabled, true); assert.match(f.text, /可能产生服务费用/);
  f.acknowledge(); f.click('确认并启动本机服务'); await f.flush(); assert.deepEqual(f.calls.at(-1), { action: 'proxy.confirmStart', args: { reviewHandle: 'original-review' } }); assert.match(f.text, /服务正在运行/);
});
test('original stop remains accessible when the model route changes and never chooses a replacement', async () => {
  const f = fixture({ 'proxy.snapshot': { ...snapshot, running: true, sessionId: 'original-session', routeCurrent: false, endpoint: 'http://127.0.0.1:41234' } }); await f.flush();
  assert.match(f.text, /旧服务不再接受新的推理/); f.click('停止这次本机服务'); await f.flush(); assert.deepEqual(f.calls.at(-1), { action: 'proxy.stop', args: { sessionId: 'original-session' } }); assert.match(f.text, /服务尚未运行/); assert.equal(f.calls.some(call => /reviewStart|confirmStart/.test(call.action)), false);
});
test('unknown confirmation stays visible and can only be reconsidered after a separate read and explicit decision', async () => {
  const f = fixture({ 'proxy.confirmStart': () => Promise.reject(new Error('原服务回执尚未返回')) }); await prepare(f); f.acknowledge(); f.click('确认并启动本机服务'); await f.flush();
  assert.match(f.text, /上次操作结果待核对/); assert.equal(f.button('配置并启动').props.disabled, true); assert.equal(f.button('我已核对服务状态，允许重新准备').props.disabled, true);
  f.click('刷新服务与调用'); await f.flush(); f.click('我已核对服务状态，允许重新准备'); assert.equal(f.calls.filter(call => call.action === 'proxy.confirmStart').length, 1); assert.equal(f.button('配置并启动').props.disabled, false);
});
test('recent call rows open their exact original task and never manufacture cumulative usage', async () => {
  const f = fixture({ 'proxy.snapshot': { ...snapshot, recentInferenceReferences: [{ taskId: 'call-a', model: 'model-a' }, { taskId: 'call-b', model: 'model-b' }] } }); await f.flush(); assert.match(f.text, /不代表累计用量/);
  const row = f.find(node => node.type === 'Button' && node.props.className === 'pw-proxy-call-row'); row.props.onClick(); await f.flush(); assert.deepEqual(f.calls.at(-1), { action: 'tasks.open', args: { id: 'call-b' } });
});
test('Back and app hiding clear configuration secrets without starting the service', async () => {
  for (const action of ['back', 'hide']) {
    const f = fixture(); await f.flush(); f.click('配置并启动'); f.field('独立访问令牌', 'fixture_not_a_real_secret_12345678');
    if (action === 'back') f.back(); else f.hide();
    assert.equal(f.find(node => node.type === 'TextField' && node.props.label === '独立访问令牌', false).props.value, ''); assert.equal(f.calls.some(call => /reviewStart|confirmStart/.test(call.action)), false);
  }
});
