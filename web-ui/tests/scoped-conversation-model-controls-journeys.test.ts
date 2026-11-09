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

function fixture(overrides: Data = {}, extra: Data = {}, component: 'panel' | 'controls' = 'panel') {
  const ast = ts.createSourceFile('ScopedConversationModelControls.tsx', readFileSync(new URL('../src/ScopedConversationModelControls.tsx', import.meta.url), 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const source = ast.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(ast).replace(/^export /, '')).join('\n');
  const code = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
  const slots: any[] = [], effects: Array<() => void> = [], calls: Data[] = [], timers = new Map<number, () => void>(), modelSections: Array<string | undefined> = [];
  let index = 0, dirty = false, tree: any, timerId = 0, back: (() => boolean) | null = null, modelOpened = 0;
  const responses: Data = { 'models.conversation.review': review, 'models.conversation.reasoning': receipt, 'models.conversation.reset': { ...receipt, preference: { ...receipt.preference, inherited: true, target: null } }, 'models.snapshot': { nativeSelection: review.nativeSelection }, ...overrides };
  const useState = (initial: any) => { const i = index++; slots[i] ??= { value: typeof initial === 'function' ? initial() : initial }; return [slots[i].value, (value: any) => { const next = typeof value === 'function' ? value(slots[i].value) : value; if (!Object.is(next, slots[i].value)) { slots[i].value = next; dirty = true; } }]; };
  const useRef = (initial: any) => { const i = index++; return slots[i] ??= { current: initial }; };
  const useEffect = (effect: () => any, deps?: any[]) => { const i = index++, old = slots[i]; if (!deps || !old?.deps || deps.some((value, at) => !Object.is(value, old.deps[at]))) { const slot = slots[i] = { deps, cleanup: old?.cleanup }; effects.push(() => { slot.cleanup?.(); slot.cleanup = effect(); }); } };
  const React = { Fragment: 'Fragment', createElement: (type: any, props: any, ...children: any[]) => ({ type, props: props || {}, children: children.flat(Infinity).filter(child => child !== null && child !== undefined && child !== false) }) };
  const dependencies: Data = { React, useState, useRef, useEffect, ...controls, ...selection, window: { setTimeout(callback: () => void) { const id = ++timerId; timers.set(id, callback); return id; }, clearTimeout(id: number) { timers.delete(id); } } };
  for (const key of ['RiArrowDownSLine', 'RiBox3Line', 'RiBrainLine', 'RiCloseLine', 'RiCpuLine', 'ReasoningIndicator', 'Button', 'IconButton', 'Radio', 'RadioGroup', 'ShimmerText']) dependencies[key] = key;
  dependencies.Drawer = Object.fromEntries(['Root', 'Trigger', 'Portal', 'Overlay', 'Content', 'Title', 'Description'].map(key => [key, `Drawer.${key}`]));
  const Component = new Function(...Object.keys(dependencies), code + `\nreturn ${component === 'controls' ? 'ScopedConversationModelControls' : 'ScopedConversationReasoningPanel'};`)(...Object.values(dependencies));
  const props = { state: { modelName: 'global-model', modelSelectionRevision: 8, conversationModel: preference }, can: () => true, act: async (action: string, args: Data = {}) => { calls.push({ action, args }); const value = responses[action]; return typeof value === 'function' ? value(args) : value || {}; }, onOpenModel: (section?: string) => { modelOpened++; modelSections.push(section); }, onSelectionPending: (value: boolean) => { back = value ? () => true : null; }, ...extra };
  const content = (node: any): string => !node || typeof node !== 'object' ? String(node ?? '') : node.children.map(content).join('');
  const nodes = (node: any): any[] => !node || typeof node !== 'object' ? [] : [node, ...node.children.flatMap(nodes)];
  const render = () => { let rounds = 0; do { assert.ok(rounds++ < 30); dirty = false; index = 0; tree = Component(props); effects.splice(0).forEach(effect => effect()); } while (dirty); };
  const find = (predicate: (node: any) => boolean) => { const node = nodes(tree).find(predicate); assert.ok(node, 'expected real scoped model control'); return node; };
  render();
  return { calls, props, responses, find, modelSections, has: (predicate: (node: any) => boolean) => nodes(tree).some(predicate), get text() { return content(tree); }, get modelOpened() { return modelOpened; },
    begin() { render(); },
    click(label: string) { const node = find(node => ['Button', 'IconButton'].includes(node.type) && (content(node) === label || node.props['aria-label'] === label)); assert.ok(!node.props.disabled, `${label} enabled`); node.props.onClick(); render(); },
    choose(value = controls.reasoningId(high)) { find(node => node.type === 'RadioGroup').props.onChange(value); render(); },
    change(state: Data) { props.state = { ...props.state, ...state }; render(); },
    back() { const handled = back?.(); render(); return handled; },
    unmount() { for (const slot of slots) slot?.cleanup?.(); },
    expire() { const pending = [...timers.values()]; timers.clear(); pending.forEach(callback => callback()); render(); },
    async flush() { for (let i = 0; i < 20; i++) { await Promise.resolve(); if (dirty) render(); } },
  };
}

test('opening a failed inherited reasoning chip rereads its summary and restores the confirmed icon', async () => {
  const inherited = { ...preference, inherited: true, target: null };
  const f = fixture({ 'models.snapshot': () => Promise.reject(new Error('read unavailable')) },
    { state: { modelName: target.modelId, modelSelectionRevision: 8, conversationModel: inherited } }, 'controls');
  const icon = () => f.find(node => node.type === 'ReasoningIndicator').props;
  await f.flush(); assert.equal(icon().error, true); assert.equal(icon().reasoning, null);
  f.responses['models.snapshot'] = { nativeSelection: { ...review.nativeSelection, reasoning: high } };
  f.click('推理强度：读取失败，点按重新读取');
  assert.deepEqual(f.modelSections, ['reasoning']);
  assert.equal(icon().loading, true); assert.equal(icon().error, false); assert.equal(icon().reasoning, null);
  await f.flush();
  assert.deepEqual(icon().reasoning, high); assert.equal(icon().loading, false); assert.equal(icon().error, false);
  assert.equal(f.find(node => node.props.className === 'pw-conversation-reasoning-chip').props['aria-label'], '推理强度：高');
  assert.deepEqual(f.calls.map(call => call.action), ['models.snapshot', 'models.snapshot']); f.unmount();
});

test('a retried summary cannot replace a newer model or conversation with a late success or failure', async () => {
  for (const change of ['model', 'scope']) {
    const inherited = { ...preference, inherited: true, target: null }, low = { ...high, level: 'low' };
    const f = fixture({ 'models.snapshot': () => Promise.reject(new Error('read unavailable')) },
      { state: { modelName: target.modelId, modelSelectionRevision: 8, conversationModel: inherited } }, 'controls');
    const icon = () => f.find(node => node.type === 'ReasoningIndicator').props;
    await f.flush();
    let resolve!: (value: Data) => void, reject!: (reason: Error) => void;
    f.responses['models.snapshot'] = () => new Promise((done, fail) => { resolve = done; reject = fail; });
    f.click('推理强度：读取失败，点按重新读取'); assert.equal(icon().loading, true);
    const model = change === 'model' ? 'new-model' : target.modelId;
    f.responses['models.snapshot'] = { nativeSelection: { ...review.nativeSelection, model, reasoning: low } };
    f.change(change === 'model' ? { modelName: model } : { conversationModel: { ...inherited, scopeKey: 'conversation:b' } });
    await f.flush(); assert.deepEqual(icon().reasoning, low);
    if (change === 'scope') resolve({ nativeSelection: { ...review.nativeSelection, reasoning: high } });
    else reject(new Error('old read failed'));
    await f.flush();
    assert.deepEqual(icon().reasoning, low); assert.equal(icon().loading, false); assert.equal(icon().error, false);
    assert.equal(f.calls.length, 3); assert.ok(f.calls.every(call => call.action === 'models.snapshot')); f.unmount();
  }
});

test('the scoped controls use real exported reasoning helpers and write only the reviewed conversation handle', async () => {
  assert.equal(typeof controls.nativeReasoning, 'function'); assert.equal(typeof controls.reasoningChoices, 'function'); assert.equal(typeof controls.reasoningLabel, 'function');
  const f = fixture(); f.begin(); await f.flush(); f.choose(); await f.flush();
  assert.deepEqual(f.calls.find(call => call.action === 'models.conversation.reasoning'), { action: 'models.conversation.reasoning', args: { reviewHandle: 'conversation-review-a', reasoning: high } });
  assert.equal(f.calls.some(call => /^(models\.(select|api\.select|accounts\.select)|settings\.)/.test(call.action)), false);
  assert.match(f.text, /推理已保存/); f.unmount(); assert.ok(f.calls.some(call => call.action === 'models.conversation.close'));
});

test('the reasoning sheet cannot reset the selected model or show model navigation', async () => {
  const f = fixture(); await f.flush();
  assert.doesNotMatch(f.text, /恢复使用默认模型|管理 AI 接入|选择模型/);
  assert.equal(f.calls.some(call => call.action === 'models.conversation.reset'), false); f.unmount();
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

test('unmount retires the original native review and discards a late response', async () => {
  let finish!: (value: any) => void;
  const f = fixture({ 'models.conversation.review': () => new Promise(resolve => { finish = resolve; }) }); f.begin(); await f.flush();
  f.unmount(); finish(review); await f.flush();
  assert.equal(f.calls.filter(call => call.action === 'models.conversation.close').length, 1);
  const second = fixture(); second.begin(); await second.flush(); second.unmount(); await second.flush();
  assert.equal(second.calls.filter(call => call.action === 'models.conversation.close').length, 1);
});

test('model groups keep native candidate parameters and never expose generic reasoning choices', async () => {
  const group = { scopeKey: 'conversation:a', revision: 3, inherited: false, modelName: '日常组', target: { kind: 'group', groupId: 'group-a', groupRevision: 6 } };
  const groupReview = { ...review, ...group, nativeSelection: { ...review.nativeSelection, modelRoutePlan: { requestedAlias: 'group-a', groupRevision: 6 } }, restriction: '模型组按各候选的原生参数执行，请在 AI 中心管理候选。' };
  const f = fixture({ 'models.conversation.review': groupReview }, { state: { modelName: 'global-model', modelSelectionRevision: 8, conversationModel: group } }); f.begin(); await f.flush();
  assert.match(f.text, /模型组按各候选的原生参数执行/);
  assert.doesNotMatch(f.text, /服务默认|应用到此对话/);
  assert.equal(f.calls.some(call => call.action === 'models.conversation.reasoning'), false);
});

test('an unknown reasoning receipt retires its handle and cannot silently retry or close as success', async () => {
  const f = fixture({ 'models.conversation.reasoning': { ...receipt, preference: { ...receipt.preference, scopeKey: 'conversation:b' } } });
  f.begin(); await f.flush(); f.choose(); await f.flush();
  assert.match(f.text, /保存结果待核对/); assert.doesNotMatch(f.text, /推理已保存/); assert.match(f.text, /没有自动重试/); assert.equal(f.calls.filter(call => call.action === 'models.conversation.reasoning').length, 1);
});

test('a lone service default is a truthful read-only explanation rather than an editable strength choice', async () => {
  for (const [facts, expected] of [[{ REASONING: { known: true, value: false } }, '这个模型不提供推理强度选项。'], [{}, '这个模型尚未声明可选的推理强度。']] as const) {
    const f = fixture({ 'models.conversation.review': { ...review, facts } }); await f.flush();
    assert.equal(f.has(node => node.type === 'RadioGroup'), false);
    assert.equal(f.find(node => node.props.className === 'pw-conversation-reasoning-default').props.role, 'status');
    assert.match(f.text, /服务默认/); assert.ok(f.text.includes(expected));
    assert.doesNotMatch(f.text, /恢复使用默认模型/);
    assert.equal(f.calls.some(call => ['models.conversation.reasoning', 'models.conversation.reset'].includes(call.action)), false); f.unmount();
  }
});

test('changing conversations during a write retires the old review without applying a late receipt to the new scope', async () => {
  let finish!: (value: any) => void;
  const f = fixture({ 'models.conversation.reasoning': () => new Promise(resolve => { finish = resolve; }) }); f.begin(); await f.flush(); f.choose(); await f.flush();
  f.change({ conversationModel: { ...preference, scopeKey: 'conversation:b', revision: 0, inherited: true, target: null } });
  assert.match(f.text, /对话已切换/); finish(receipt); await f.flush(); assert.doesNotMatch(f.text, /推理已保存/);
  assert.equal(f.calls.filter(call => call.action === 'models.conversation.reasoning').length, 1);
});

test('expiry removes the reviewed strength choices and requires another explicit read', async () => {
  const f = fixture(); f.begin(); await f.flush(); f.expire();
  assert.match(f.text, /审阅已过期/); assert.equal(f.calls.filter(call => call.action === 'models.conversation.review').length, 1);
});

test('clicking a strength writes immediately, keeps the old check while pending and rejects duplicate clicks', async () => {
  let finish!: (value: any) => void;
  const f = fixture({ 'models.conversation.reasoning': () => new Promise(resolve => { finish = resolve; }) }); await f.flush();
  const group = f.find(node => node.type === 'RadioGroup'), previous = group.props.value;
  group.props.onChange(controls.reasoningId(high)); group.props.onChange(controls.reasoningId(high)); await f.flush();
  assert.equal(f.calls.filter(call => call.action === 'models.conversation.reasoning').length, 1);
  assert.equal(f.find(node => node.type === 'RadioGroup').props.value, previous);
  assert.match(f.text, /正在保存推理/); assert.doesNotMatch(f.text, /推理已保存/);
  finish(receipt); await f.flush(); assert.match(f.text, /推理已保存/);
});

test('the current or unsupported strength is never submitted', async () => {
  const f = fixture(); await f.flush(); f.choose(controls.reasoningId(defaultReasoning)); f.choose('invented-effort'); await f.flush();
  assert.equal(f.calls.some(call => call.action === 'models.conversation.reasoning'), false);
});

test('saved status alone cannot confirm different reasoning, model identity or output reserves', async () => {
  for (const patch of [{ reasoning: defaultReasoning }, { modelId: 'another-model' }, { outputTokens: 8000 }, { imageTokenReserve: 999 }, { binding: { ...binding, credentialGeneration: 2 } }]) {
    const f = fixture({ 'models.conversation.reasoning': { ...receipt, preference: { ...receipt.preference, target: { ...receipt.preference.target, ...patch } } } }); await f.flush(); f.choose(); await f.flush();
    assert.match(f.text, /保存结果待核对/); assert.doesNotMatch(f.text, /推理已保存/); assert.equal(f.calls.filter(call => call.action === 'models.conversation.reasoning').length, 1); f.unmount();
  }
});

test('a confirmed save waits for the published preference then reads a new handle without rewriting', async () => {
  const f = fixture(); await f.flush(); f.choose(); await f.flush(); assert.match(f.text, /推理已保存/);
  assert.equal(f.calls.filter(call => call.action === 'models.conversation.review').length, 1);
  f.responses['models.conversation.review'] = { ...review, ...receipt.preference, selectionRevision: 10, reviewHandle: 'new-review', nativeSelection: { ...review.nativeSelection, reasoning: high } };
  f.change({ modelSelectionRevision: 10, conversationModel: receipt.preference }); await f.flush();
  assert.equal(f.calls.filter(call => call.action === 'models.conversation.review').length, 2);
  assert.equal(f.calls.filter(call => call.action === 'models.conversation.reasoning').length, 1);
  assert.equal(f.find(node => node.type === 'RadioGroup').props.value, controls.reasoningId(high));
});

test('an unknown write cannot auto-reread or replay after native source revisions change', async () => {
  const f = fixture({ 'models.conversation.reasoning': { status: 'unknown' } }); await f.flush(); f.choose(); await f.flush();
  f.change({ modelSelectionRevision: 10 }); await f.flush();
  assert.match(f.text, /没有自动重试/); assert.equal(f.calls.filter(call => call.action === 'models.conversation.review').length, 1);
  f.responses['models.conversation.review'] = { ...review, selectionRevision: 10 };
  f.click('重新读取'); await f.flush(); assert.equal(f.calls.filter(call => call.action === 'models.conversation.reasoning').length, 1);
});

test('an inherited model may become a scoped reasoning choice while retaining its reviewed native identity', async () => {
  const inherited = { ...preference, revision: 0, inherited: true, target: null };
  const f = fixture({ 'models.conversation.review': { ...review, ...inherited }, 'models.conversation.reasoning': { ...receipt, preference: { ...receipt.preference, revision: 1 } } },
    { state: { modelSelectionRevision: 8, modelName: target.modelId, conversationModel: inherited } });
  await f.flush(); assert.match(f.text, /沿用默认/); f.choose(); await f.flush(); assert.match(f.text, /推理已保存/);
  assert.equal(f.calls.some(call => /models\.(api|accounts)\.select/.test(call.action)), false);
});
