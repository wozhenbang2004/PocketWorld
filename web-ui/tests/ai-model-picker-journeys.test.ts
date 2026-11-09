import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import * as apiHelpers from '../src/composer-api-models.ts';
import * as accountHelpers from '../src/composer-account-models.ts';

// Executes production component handlers with hook/control doubles. This is not
// a browser render, Android verification, real login, or a paid inference test.
type Data = Record<string, any>;
const apiRow = { connectionId: 'service-a', connectionRevision: 4, connectionName: '工作 API', modelId: 'configured-model', available: true, favorite: false, selected: true };
const apiProjection = { windowId: 'api-window', format: 'pocketworld.model-picker.v1', selectionRevision: 7, viewGeneration: 2, preferencesAvailable: true, models: [apiRow] };
const account = { id: 'owner-a', version: 3, label: '个人账号', provider: 'chatgpt_plan', enabled: true, planUsageEnabled: true, usageLimited: false, status: 'connected', cooldownUntil: 0 };
const accountProjection = { windowId: 'account-window', selectionRevision: 7, viewGeneration: 3, accountSnapshot: { accounts: [account] } };
const catalogModel = { id: 'plan-model', displayName: '套餐目录模型', nativeRef: { provider: 'chatgpt_plan', accountId: account.id, accountVersion: account.version, modelId: 'plan-model' } };
const group = { groupId: 'group-a', revision: 6, name: '日常任务', candidates: [{ candidateId: 'candidate-a', target: { kind: 'exact', connectionId: 'service-a', modelId: 'configured-model' }, enabled: true }], attemptPolicy: { maxAttempts: 2, maxRouteSwitches: 1 } };
function fixture(overrides: Data = {}, extra: Data = {}) {
  const ast = ts.createSourceFile('AIModelPicker.tsx', readFileSync(new URL('../src/AIModelPicker.tsx', import.meta.url), 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const source = ast.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(ast).replace(/^export /, '')).join('\n');
  const code = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
  const slots: any[] = [], effects: Array<() => void> = [], calls: Data[] = [], selections: Data[] = [], pending: boolean[] = [];
  let index = 0, dirty = false, tree: any, selected = 0;
  const responses: Data = {
    'models.api.open': apiProjection,
    'models.accounts.open': accountProjection,
    'models.groups.snapshot': { format: 'pocketworld.model-groups.v1', groups: [group], purposeBindings: {} },
    'models.api.review': { ...apiProjection, reviewHandle: 'api-review', model: apiRow },
    'models.accounts.models': { ...accountProjection, catalog: { accountId: account.id, accountVersion: account.version, models: [catalogModel] } },
    'models.accounts.review': { ...accountProjection, accountId: account.id, accountVersion: account.version, accountLabel: account.label, modelId: catalogModel.id, reviewHandle: 'account-review', catalogFacts: { facts: { REASONING_LEVELS: { known: true, value: ['low', 'high'] } } }, options: { reasoningEffort: '', outputReserve: 8192, imageTokenReserve: 320 } },
    'models.api.select': { status: 'saved' }, 'models.api.favorite': { status: 'saved' }, 'models.accounts.select': { status: 'saved' }, 'models.groups.select': { status: 'saved' }, ...overrides,
  };
  const useState = (initial: any) => { const i = index++; slots[i] ??= { value: typeof initial === 'function' ? initial() : initial }; return [slots[i].value, (value: any) => { const next = typeof value === 'function' ? value(slots[i].value) : value; if (!Object.is(next, slots[i].value)) { slots[i].value = next; dirty = true; } }]; };
  const useRef = (initial: any) => { const i = index++; return slots[i] ??= { current: initial }; };
  const useEffect = (effect: () => any, deps?: any[]) => { const i = index++, old = slots[i]; if (!deps || !old?.deps || deps.some((value, at) => !Object.is(value, old.deps[at]))) { const slot = slots[i] = { deps, cleanup: old?.cleanup }; effects.push(() => { slot.cleanup?.(); slot.cleanup = effect(); }); } };
  const React = { Fragment: 'Fragment', createElement: (type: any, props: any, ...children: any[]) => ({ type, props: props || {}, children: children.flat(Infinity).filter(child => child !== null && child !== undefined && child !== false) }) };
  const dependencies: Data = { React, useState, useRef, useEffect, ...apiHelpers, ...accountHelpers };
  for (const key of ['ArrowLeft', 'Check', 'ChevronRight', 'RefreshCw', 'Star', 'Accordion', 'AccordionItem', 'Button', 'SearchField', 'SelectField', 'TextField', 'Tabs', 'Tab', 'TabList', 'TabPanel', 'ShimmerText']) dependencies[key] = key;
  const Component = new Function(...Object.keys(dependencies), code + '\nreturn AIModelPicker;')(...Object.values(dependencies));
  const props = { state: { modelSelectionRevision: 7 }, can: () => true, act: async (action: string, args: Data = {}) => { calls.push({ action, args }); const value = responses[action]; return typeof value === 'function' ? value(args) : value || {}; }, scope: 'conversation', onSelectTarget: async (target: Data) => { selections.push(target); return { status: 'saved' }; }, onSelected: () => { selected++; }, onSelectionPending: (value: boolean) => pending.push(value), ...extra };
  const text = (node: any): string => !node || typeof node !== 'object' ? String(node ?? '') : node.children.map(text).join('');
  const nodes = (node: any): any[] => !node || typeof node !== 'object' ? [] : [node, ...node.children.flatMap(nodes)];
  const render = () => { let rounds = 0; do { assert.ok(rounds++ < 30); dirty = false; index = 0; tree = Component(props); effects.splice(0).forEach(effect => effect()); } while (dirty); };
  const find = (predicate: (node: any) => boolean) => { const node = nodes(tree).find(predicate); assert.ok(node, 'expected production picker control'); return node; };
  render();
  return { calls, selections, pending, props, responses, find, get text() { return text(tree); }, get selected() { return selected; },
    click(label: string) { const node = find(node => node.type === 'Button' && (text(node) === label || node.props['aria-label'] === label)); assert.ok(!node.props.disabled, label + ' should be enabled'); node.props.onClick(); render(); },
    row(label: string) { const node = find(node => node.type === 'Button' && ['pw-ai-model-row'].includes(node.props.className) && text(node).startsWith(label)); assert.ok(!node.props.disabled); node.props.onClick(); render(); },
    change(state: Data) { props.state = { ...props.state, ...state }; render(); },
    unmount() { for (const slot of slots) slot?.cleanup?.(); },
    async flush() { for (let i = 0; i < 20; i++) { await Promise.resolve(); if (dirty) render(); } },
  };
}

test('one catalog exposes API, account and model groups without an implicit selection', async () => {
  const f = fixture(); await f.flush();
  assert.match(f.text, /工作 API/); assert.match(f.text, /个人账号/); assert.match(f.text, /日常任务/);
  assert.equal(f.calls.some(call => /\.select$/.test(call.action)), false);
  assert.equal(f.selections.length, 0);
});
test('conversation API selection preserves the exact reviewed window and never uses global select', async () => {
  const f = fixture(); await f.flush(); f.row('configured-model'); await f.flush(); f.click('用于本对话'); await f.flush();
  assert.deepEqual(f.calls.find(call => call.action === 'models.api.review')?.args, { windowId: 'api-window', connectionId: 'service-a', modelId: 'configured-model', expectedRevision: 4 });
  assert.deepEqual(f.selections[0], { kind: 'api', args: { windowId: 'api-window', reviewHandle: 'api-review' }, selectionRevision: 7, modelId: 'configured-model', sourceLabel: '工作 API' });
  assert.equal(f.calls.some(call => call.action === 'models.api.select'), false); assert.equal(f.selected, 1); assert.deepEqual(f.pending, [true, false]);
});
test('account read and review retain the original account version, handle and real local reserves', async () => {
  const f = fixture(); await f.flush(); f.row('个人账号'); await f.flush();
  assert.deepEqual(f.calls.find(call => call.action === 'models.accounts.models')?.args, { windowId: 'account-window', accountId: 'owner-a', expectedVersion: 3 });
  f.row('套餐目录模型'); await f.flush(); f.click('用于本对话'); await f.flush();
  assert.deepEqual(f.selections[0].args, { windowId: 'account-window', reviewHandle: 'account-review', options: { reasoningEffort: '', outputReserve: 8192, imageTokenReserve: 320 } });
  assert.equal(f.selections[0].kind, 'account'); assert.equal(f.calls.some(call => call.action === 'models.accounts.select'), false);
});
test('group conversation selection returns its exact group revision through the same scope callback', async () => {
  const f = fixture(); await f.flush(); f.row('日常任务'); f.click('用于本对话'); await f.flush();
  assert.deepEqual(f.selections[0], { kind: 'group', args: { groupId: 'group-a', expectedRevision: 6 }, selectionRevision: 7, modelId: '日常任务', sourceLabel: '模型组' });
  assert.equal(f.calls.some(call => call.action === 'models.groups.select'), false);
});
test('a conversation picker without its native scope callback cannot fall back to a global write', async () => {
  const f = fixture({}, { onSelectTarget: undefined }); await f.flush(); f.row('configured-model'); await f.flush();
  const save = f.find(node => node.type === 'Button' && node.children.join('') === '用于本对话'); assert.equal(save.props.disabled, true);
  assert.equal(f.calls.some(call => /\.select$/.test(call.action)), false);
});
test('explicit default scope retains the original global native select contract', async () => {
  const f = fixture({}, { scope: 'default', onSelectTarget: undefined }); await f.flush(); f.row('configured-model'); await f.flush(); f.click('设为默认模型'); await f.flush();
  assert.deepEqual(f.calls.at(-1), { action: 'models.api.select', args: { windowId: 'api-window', reviewHandle: 'api-review' } });
});
test('a stale selection revision invalidates displayed reviews instead of silently stamping a new revision', async () => {
  const f = fixture(); await f.flush(); f.row('configured-model'); await f.flush(); f.change({ modelSelectionRevision: 8 });
  assert.match(f.text, /模型配置已变化/); assert.doesNotMatch(f.text, /用于本对话/); assert.equal(f.selections.length, 0);
});
test('an unreadable API source does not hide real account entries or automatically retry', async () => {
  const f = fixture({ 'models.api.open': () => Promise.reject(new Error('服务目录暂不可读取')) }); await f.flush();
  assert.match(f.text, /个人账号/); assert.match(f.text, /服务目录暂不可读取/); assert.equal(f.calls.filter(call => call.action === 'models.api.open').length, 1);
});
test('unknown selection keeps one consumed review and requires a new read', async () => {
  let attempts = 0;
  const f = fixture({}, { onSelectTarget: async () => { attempts++; throw new Error('回执未返回'); } }); await f.flush(); f.row('configured-model'); await f.flush(); f.click('用于本对话'); await f.flush();
  assert.equal(attempts, 1); assert.match(f.text, /没有自动重试/); assert.doesNotMatch(f.text, /用于本对话/); assert.equal(f.selected, 0);
});
test('closing the unified picker retires both original windows', async () => {
  const f = fixture(); await f.flush(); f.unmount(); await f.flush();
  assert.deepEqual(f.calls.filter(call => /\.close$/.test(call.action)), [{ action: 'models.api.close', args: { windowId: 'api-window' } }, { action: 'models.accounts.close', args: { windowId: 'account-window' } }]);
});
