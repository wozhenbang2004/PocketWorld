import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import * as controls from '../src/conversation-model-controls.ts';
import * as selection from '../src/conversation-model-selection.ts';

// Production TSX handlers with real exported policy helpers and hook/control
// doubles. This covers bridge ownership, not browser or Android rendering.
type Data = Record<string, any>;
const defaultReasoning = { mode: 'DEFAULT', level: '', budgetTokens: 0 };
const high = { mode: 'OPENAI_EFFORT', level: 'high', budgetTokens: 0 };
const binding = { connectionId: 'source-a', installationId: 'binding-a', endpointDigest: 'endpoint-a', credentialGeneration: 1 };
const target = { kind: 'api', connectionId: 'source-a', connectionRevision: 4, binding, modelId: 'conversation-model', outputTokens: 4096, reasoning: defaultReasoning, imageTokenReserve: 0 };
const preference = { scopeKey: 'conversation:a', revision: 3, inherited: false, target };
const review = { ...preference, selectionRevision: 8, reviewHandle: 'conversation-review-a', expiresInMs: 120_000, nativeSelection: { modelRoute: 'api_key', model: target.modelId, provider: 'openai_responses', capabilityBinding: binding, maxOutputTokens: 4096, imageTokenReserve: 0, reasoning: defaultReasoning }, facts: { REASONING: { known: true, value: true }, REASONING_LEVELS: { known: true, value: ['low', 'high'] } }, restriction: '' };
const receipt = { status: 'saved', scope: 'conversation', preference: { ...preference, revision: 4, target: { ...target, reasoning: high } } };

function fixture(overrides: Data = {}, extra: Data = {}) {
  const ast = ts.createSourceFile('ScopedConversationModelControls.tsx', readFileSync(new URL('../src/ScopedConversationModelControls.tsx', import.meta.url), 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const source = ast.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(ast).replace(/^export /, '')).join('\n');
  const code = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
  const slots: any[] = [], effects: Array<() => void> = [], calls: Data[] = [], timers = new Map<number, () => void>();
  let index = 0, dirty = false, tree: any, timerId = 0, back: (() => boolean) | null = null, modelOpened = 0;
  const responses: Data = { 'models.conversation.review': review, 'models.conversation.reasoning': receipt, 'models.conversation.reset': { ...receipt, preference: { ...receipt.preference, inherited: true, target: null } }, 'models.snapshot': { nativeSelection: review.nativeSelection }, ...overrides };
  const useState = (initial: any) => { const i = index++; slots[i] ??= { value: typeof initial === 'function' ? initial() : initial }; return [slots[i].value, (value: any) => { const next = typeof value === 'function' ? value(slots[i].value) : value; if (!Object.is(next, slots[i].value)) { slots[i].value = next; dirty = true; } }]; };
  const useRef = (initial: any) => { const i = index++; return slots[i] ??= { current: initial }; };
  const useEffect = (effect: () => any, deps?: any[]) => { const i = index++, old = slots[i]; if (!deps || !old?.deps || deps.some((value, at) => !Object.is(value, old.deps[at]))) { const slot = slots[i] = { deps, cleanup: old?.cleanup }; effects.push(() => { slot.cleanup?.(); slot.cleanup = effect(); }); } };
  const React = { Fragment: 'Fragment', createElement: (type: any, props: any, ...children: any[]) => ({ type, props: props || {}, children: children.flat(Infinity).filter(child => child !== null && child !== undefined && child !== false) }) };
  const dependencies: Data = { React, useState, useRef, useEffect, ...controls, ...selection, window: { setTimeout(callback: () => void) { const id = ++timerId; timers.set(id, callback); return id; }, clearTimeout(id: number) { timers.delete(id); } } };
  for (const key of ['RiArrowDownSLine', 'RiBrainLine', 'RiCloseLine', 'RiCpuLine', 'Button', 'IconButton', 'Radio', 'RadioGroup', 'ShimmerText']) dependencies[key] = key;
  dependencies.Drawer = Object.fromEntries(['Root', 'Trigger', 'Portal', 'Overlay', 'Content', 'Title', 'Description'].map(key => [key, `Drawer.${key}`]));
  const Component = new Function(...Object.keys(dependencies), code + '\nreturn ScopedConversationModelControls;')(...Object.values(dependencies));
  const props = { state: { modelName: 'global-model', modelSelectionRevision: 8, conversationModel: preference }, can: () => true, act: async (action: string, args: Data = {}) => { calls.push({ action, args }); const value = responses[action]; return typeof value === 'function' ? value(args) : value || {}; }, onOpenModel: () => { modelOpened++; }, registerBackHandler: (value: any) => { back = value; }, ...extra };
  const content = (node: any): string => !node || typeof node !== 'object' ? String(node ?? '') : node.children.map(content).join('');
  const nodes = (node: any): any[] => !node || typeof node !== 'object' ? [] : [node, ...node.children.flatMap(nodes)];
  const render = () => { let rounds = 0; do { assert.ok(rounds++ < 30); dirty = false; index = 0; tree = Component(props); effects.splice(0).forEach(effect => effect()); } while (dirty); };
  const find = (predicate: (node: any) => boolean) => { const node = nodes(tree).find(predicate); assert.ok(node, 'expected real scoped model control'); return node; };
  render();
  return { calls, props, responses, find, get text() { return content(tree); }, get modelOpened() { return modelOpened; },
    get open() { return find(node => node.type === 'Drawer.Root').props.open; },
    begin() { find(node => node.type === 'Drawer.Root').props.onOpenChange(true); render(); },
    click(label: string) { const node = find(node => ['Button', 'IconButton'].includes(node.type) && (content(node) === label || node.props['aria-label'] === label)); assert.ok(!node.props.disabled, `${label} enabled`); node.props.onClick(); render(); },
    choose(value = controls.reasoningId(high)) { find(node => node.type === 'RadioGroup').props.onChange(value); render(); },
    change(state: Data) { props.state = { ...props.state, ...state }; render(); },
    back() { const handled = back?.(); render(); return handled; },
    unmount() { for (const slot of slots) slot?.cleanup?.(); },
    expire() { const pending = [...timers.values()]; timers.clear(); pending.forEach(callback => callback()); render(); },
    async flush() { for (let i = 0; i < 20; i++) { await Promise.resolve(); if (dirty) render(); } },
  };
}

test('the scoped controls use real exported reasoning helpers and write only the reviewed conversation handle', async () => {
  assert.equal(typeof controls.nativeReasoning, 'function'); assert.equal(typeof controls.reasoningChoices, 'function'); assert.equal(typeof controls.reasoningLabel, 'function');
  const f = fixture(); f.begin(); await f.flush(); f.choose(); f.click('应用到此对话'); await f.flush();
  assert.deepEqual(f.calls.find(call => call.action === 'models.conversation.reasoning'), { action: 'models.conversation.reasoning', args: { reviewHandle: 'conversation-review-a', reasoning: high } });
  assert.equal(f.calls.some(call => /^(models\.(select|api\.select|accounts\.select)|settings\.)/.test(call.action)), false);
  assert.equal(f.open, false); assert.ok(f.calls.some(call => call.action === 'models.conversation.close'));
});

test('restoring the default sends only the original conversation and source revisions then closes its review', async () => {
  const f = fixture(); f.begin(); await f.flush(); f.click('恢复使用默认模型'); await f.flush();
  assert.deepEqual(f.calls.find(call => call.action === 'models.conversation.reset')?.args, { expectedPreferenceRevision: 3, expectedSelectionRevision: 8 });
  assert.equal(f.open, false); assert.ok(f.calls.some(call => call.action === 'models.conversation.close'));
});

test('wrong selection revisions, empty handles, and a swapped target cannot become editable reviews', async () => {
  for (const result of [{ ...review, selectionRevision: 7 }, { ...review, reviewHandle: '' }, { ...review, target: { ...target, modelId: 'another-model' } }, { ...review, nativeSelection: { ...review.nativeSelection, model: 'another-model' } }]) {
    const f = fixture({ 'models.conversation.review': result }); f.begin(); await f.flush();
    assert.match(f.text, /审阅已变化|模型.*不匹配/);
    assert.equal(f.calls.some(call => call.action === 'models.conversation.reasoning'), false);
    f.unmount();
  }
});

test('a late read from an earlier conversation revision is discarded', async () => {
  let finish!: (value: any) => void;
  const f = fixture({ 'models.conversation.review': () => new Promise(resolve => { finish = resolve; }) }); f.begin(); await f.flush();
  f.change({ conversationModel: { ...preference, revision: 4, target: { ...target, modelId: 'newer-model' } } });
  finish(review); await f.flush();
  assert.match(f.text, /模型已变化/); assert.equal(f.calls.some(call => call.action === 'models.conversation.reasoning'), false);
  assert.equal(f.find(node => node.type === 'Button' && node.children.join('') === '重新读取').props.disabled, false);
});

test('Back and unmount retire the original native review and discard a late response', async () => {
  let finish!: (value: any) => void;
  const f = fixture({ 'models.conversation.review': () => new Promise(resolve => { finish = resolve; }) }); f.begin(); await f.flush();
  assert.equal(f.back(), true); assert.equal(f.open, false); finish(review); await f.flush();
  assert.equal(f.calls.filter(call => call.action === 'models.conversation.close').length, 1);
  const second = fixture(); second.begin(); await second.flush(); second.unmount(); await second.flush();
  assert.equal(second.calls.filter(call => call.action === 'models.conversation.close').length, 1);
});

test('model groups keep native candidate parameters and never expose generic reasoning choices', async () => {
  const group = { scopeKey: 'conversation:a', revision: 3, inherited: false, modelName: '日常组', target: { kind: 'group', groupId: 'group-a', groupRevision: 6 } };
  const groupReview = { ...review, ...group, nativeSelection: { ...review.nativeSelection, modelRoutePlan: { requestedAlias: 'group-a', groupRevision: 6 } }, restriction: '模型组按各候选的原生参数执行，请在 AI 中心管理候选。' };
  const f = fixture({ 'models.conversation.review': groupReview }, { state: { modelName: 'global-model', modelSelectionRevision: 8, conversationModel: group } }); f.begin(); await f.flush();
  assert.match(f.text, /模型组按各候选的原生参数执行/);
  assert.equal(f.find(node => node.type === 'Button' && node.children.join('') === '应用到此对话').props.disabled, true);
  assert.equal(f.calls.some(call => call.action === 'models.conversation.reasoning'), false);
});

test('an unknown reasoning receipt retires its handle and cannot silently retry or close as success', async () => {
  const f = fixture({ 'models.conversation.reasoning': { ...receipt, preference: { ...receipt.preference, scopeKey: 'conversation:b' } } });
  f.begin(); await f.flush(); f.choose(); f.click('应用到此对话'); await f.flush();
  assert.equal(f.open, true); assert.match(f.text, /没有自动重试/); assert.equal(f.calls.filter(call => call.action === 'models.conversation.reasoning').length, 1);
});

test('an unknown reset cannot be submitted again until an explicit successful reread', async () => {
  const f = fixture({ 'models.conversation.reset': () => Promise.reject(new Error('回执未知')) });
  f.begin(); await f.flush(); f.click('恢复使用默认模型'); await f.flush();
  assert.equal(f.find(node => node.type === 'Button' && node.children.join('') === '恢复使用默认模型').props.disabled, true);
  f.click('重新读取'); await f.flush();
  assert.equal(f.find(node => node.type === 'Button' && node.children.join('') === '恢复使用默认模型').props.disabled, false);
});

test('changing conversations during a write closes the old surface without applying a late receipt to the new scope', async () => {
  let finish!: (value: any) => void;
  const f = fixture({ 'models.conversation.reasoning': () => new Promise(resolve => { finish = resolve; }) }); f.begin(); await f.flush(); f.choose(); f.click('应用到此对话'); await f.flush();
  f.change({ conversationModel: { ...preference, scopeKey: 'conversation:b', revision: 0, inherited: true, target: null } });
  assert.equal(f.open, false); finish(receipt); await f.flush(); assert.equal(f.open, false);
  assert.equal(f.calls.filter(call => call.action === 'models.conversation.reasoning').length, 1);
});

test('expiry removes the reviewed strength choices and requires another explicit read', async () => {
  const f = fixture(); f.begin(); await f.flush(); f.expire();
  assert.match(f.text, /审阅已过期/); assert.equal(f.calls.filter(call => call.action === 'models.conversation.review').length, 1);
});
