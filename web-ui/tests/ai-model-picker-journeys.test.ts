import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import * as apiHelpers from '../src/composer-api-models.ts';
import * as accountHelpers from '../src/composer-account-models.ts';
import * as conversationHelpers from '../src/conversation-model-selection.ts';

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
  const dependencies: Data = { React, useState, useRef, useEffect, ...apiHelpers, ...accountHelpers, ...conversationHelpers };
  for (const key of ['ArrowLeft', 'Check', 'ChevronRight', 'Info', 'RefreshCw', 'Star', 'Accordion', 'AccordionItem', 'Button', 'IconButton', 'SearchField', 'SelectField', 'TextField', 'Tabs', 'Tab', 'TabList', 'TabPanel', 'ShimmerText']) dependencies[key] = key;
  const Component = new Function(...Object.keys(dependencies), code + '\nreturn AIModelPicker;')(...Object.values(dependencies));
  const props = { state: { modelSelectionRevision: 7 }, can: () => true, act: async (action: string, args: Data = {}) => { calls.push({ action, args }); const value = responses[action]; return typeof value === 'function' ? value(args) : value || {}; }, scope: 'conversation', onSelectTarget: async (target: Data) => { selections.push(target); return { status: 'saved' }; }, onSelected: () => { selected++; }, onSelectionPending: (value: boolean) => pending.push(value), ...extra };
  const text = (node: any): string => !node || typeof node !== 'object' ? String(node ?? '') : node.children.map(text).join('');
  const nodes = (node: any): any[] => !node || typeof node !== 'object' ? [] : [node, ...node.children.flatMap(nodes)];
  const render = () => { let rounds = 0; do { assert.ok(rounds++ < 30); dirty = false; index = 0; tree = Component(props); effects.splice(0).forEach(effect => effect()); } while (dirty); };
  const find = (predicate: (node: any) => boolean) => { const node = nodes(tree).find(predicate); assert.ok(node, 'expected production picker control'); return node; };
  render();
  return { calls, selections, pending, props, responses, find, has: (predicate: (node: any) => boolean) => nodes(tree).some(predicate), get text() { return text(tree); }, get selected() { return selected; },
    click(label: string) { const node = find(node => ['Button', 'IconButton'].includes(node.type) && (text(node) === label || node.props['aria-label'] === label)); assert.ok(!node.props.disabled, label + ' should be enabled'); node.props.onClick(); render(); },
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
  const f = fixture(); await f.flush(); f.row('configured-model'); await f.flush();
  assert.deepEqual(f.calls.find(call => call.action === 'models.api.review')?.args, { windowId: 'api-window', connectionId: 'service-a', modelId: 'configured-model', expectedRevision: 4 });
  assert.deepEqual(f.selections[0], { kind: 'api', args: { windowId: 'api-window', reviewHandle: 'api-review' }, selectionRevision: 7, modelId: 'configured-model', sourceLabel: '工作 API' });
  assert.equal(f.calls.some(call => call.action === 'models.api.select'), false); assert.equal(f.selected, 1); assert.deepEqual(f.pending, [true, false]);
});
test('account read and review retain the original account version, handle and real local reserves', async () => {
  const f = fixture(); await f.flush(); f.row('个人账号'); await f.flush();
  assert.deepEqual(f.calls.find(call => call.action === 'models.accounts.models')?.args, { windowId: 'account-window', accountId: 'owner-a', expectedVersion: 3 });
  f.row('套餐目录模型'); await f.flush();
  assert.deepEqual(f.selections[0].args, { windowId: 'account-window', reviewHandle: 'account-review', options: { reasoningEffort: '', outputReserve: 8192, imageTokenReserve: 320 } });
  assert.equal(f.selections[0].kind, 'account'); assert.equal(f.calls.some(call => call.action === 'models.accounts.select'), false);
});
test('group conversation selection returns its exact group revision through the same scope callback', async () => {
  const f = fixture(); await f.flush(); f.row('日常任务'); await f.flush();
  assert.deepEqual(f.selections[0], { kind: 'group', args: { groupId: 'group-a', expectedRevision: 6 }, selectionRevision: 7, modelId: '日常任务', sourceLabel: '模型组' });
  assert.equal(f.calls.some(call => call.action === 'models.groups.select'), false);
});
test('a conversation picker without its native scope callback cannot fall back to a global write', async () => {
  const f = fixture({}, { onSelectTarget: undefined }); await f.flush();
  const save = f.find(node => node.type === 'Button' && node.props['aria-label'] === '选择模型：configured-model，工作 API'); assert.equal(save.props.disabled, true);
  assert.equal(f.calls.some(call => /\.select$/.test(call.action)), false);
});
test('explicit default scope retains the original global native select contract', async () => {
  const f = fixture({ 'models.api.open': { ...apiProjection, models: [{ ...apiRow, selected: false }] } }, { scope: 'default', onSelectTarget: undefined }); await f.flush(); f.row('configured-model'); await f.flush();
  assert.deepEqual(f.calls.find(call => call.action === 'models.api.select'), { action: 'models.api.select', args: { windowId: 'api-window', reviewHandle: 'api-review' } });
});
test('a stale selection revision invalidates displayed reviews instead of silently stamping a new revision', async () => {
  const f = fixture(); await f.flush(); f.click('查看模型详情：configured-model，工作 API'); await f.flush(); f.change({ modelSelectionRevision: 8 });
  assert.match(f.text, /模型配置已变化/); assert.doesNotMatch(f.text, /用于本对话/); assert.equal(f.selections.length, 0);
});
test('an unreadable API source does not hide real account entries or automatically retry', async () => {
  const f = fixture({ 'models.api.open': () => Promise.reject(new Error('服务目录暂不可读取')) }); await f.flush();
  assert.match(f.text, /个人账号/); assert.match(f.text, /服务目录暂不可读取/); assert.equal(f.calls.filter(call => call.action === 'models.api.open').length, 1);
});
test('unknown selection keeps one consumed review and requires a new read', async () => {
  let attempts = 0;
  const f = fixture({}, { onSelectTarget: async () => { attempts++; throw new Error('回执未返回'); } }); await f.flush(); f.row('configured-model'); await f.flush();
  assert.equal(attempts, 1); assert.match(f.text, /没有自动重试/); assert.doesNotMatch(f.text, /用于本对话/); assert.equal(f.selected, 0);
});
test('closing the unified picker retires both original windows', async () => {
  const f = fixture(); await f.flush(); f.unmount(); await f.flush();
  assert.deepEqual(f.calls.filter(call => /\.close$/.test(call.action)), [{ action: 'models.api.close', args: { windowId: 'api-window' } }, { action: 'models.accounts.close', args: { windowId: 'account-window' } }]);
});

test('the displayed check belongs to conversation B while the same-source global A stays unchecked', async () => {
  const second = { ...apiRow, modelId: 'conversation-B', selected: false };
  const f = fixture({ 'models.api.open': { ...apiProjection, models: [apiRow, second] } }, { state: { modelSelectionRevision: 7, modelName: apiRow.modelId,
    conversationModel: { scopeKey: 'conversation:b', revision: 4, inherited: false, target: { kind: 'api', connectionId: apiRow.connectionId, modelId: second.modelId } } } });
  await f.flush();
  assert.equal(f.find(node => node.props['aria-label'] === '选择模型：configured-model，工作 API').props['aria-pressed'], false);
  assert.equal(f.find(node => node.props['aria-label'] === '选择模型：conversation-B，工作 API').props['aria-pressed'], true);
  assert.equal(f.find(node => node.type === 'Check').props['aria-label'], '此对话已选模型');
  f.row('conversation-B'); await f.flush(); assert.equal(f.selections.length, 0, 'reselecting the current model must not reset its reasoning');
});

test('an inherited check is explicitly labeled instead of claiming an independent conversation choice', async () => {
  const f = fixture({}, { state: { modelSelectionRevision: 7, modelName: apiRow.modelId, conversationModel: { scopeKey: 'draft:a', revision: 0, inherited: true, target: null } } });
  await f.flush(); assert.match(f.text, /沿用默认 · configured-model/);
  assert.equal(f.find(node => node.type === 'Check').props['aria-label'], '沿用默认模型');
});

test('a selected API model with newer source metadata can be explicitly reviewed again without losing its check', async () => {
  const f = fixture({}, { state: { modelSelectionRevision: 7, conversationModel: { scopeKey: 'conversation:a', revision: 3,
    inherited: false, target: { kind: 'api', connectionId: apiRow.connectionId, connectionRevision: 3, modelId: apiRow.modelId } } } });
  await f.flush();
  assert.equal(f.find(node => node.props['aria-label'] === '选择模型：configured-model，工作 API').props['aria-pressed'], true);
  assert.equal(f.calls.some(call => call.action === 'models.api.review'), false, 'a metadata revision never automatically reselects a model');
  const before = f.text;
  f.row('configured-model'); await f.flush();
  assert.deepEqual(f.calls.find(call => call.action === 'models.api.review')?.args, { windowId: 'api-window', connectionId: 'service-a', modelId: 'configured-model', expectedRevision: 4 });
  assert.deepEqual(f.selections[0], { kind: 'api', args: { windowId: 'api-window', reviewHandle: 'api-review' }, selectionRevision: 7, modelId: 'configured-model', sourceLabel: '工作 API' });
  assert.match(before, /来源信息已更新，可重新核对/);
  assert.doesNotMatch(before, /已失效/, 'a source rename can change its revision without invalidating the native binding');
  assert.equal(f.calls.some(call => call.action === 'models.api.select'), false);
});

test('a selected API model at the same source revision remains a no-op', async () => {
  const f = fixture({}, { state: { modelSelectionRevision: 7, conversationModel: { scopeKey: 'conversation:a', revision: 3,
    inherited: false, target: { kind: 'api', connectionId: apiRow.connectionId, connectionRevision: 4, modelId: apiRow.modelId } } } });
  await f.flush(); f.row('configured-model'); await f.flush();
  assert.equal(f.calls.some(call => call.action === 'models.api.review'), false);
  assert.equal(f.selections.length, 0); assert.doesNotMatch(f.text, /来源信息已更新/);
});

test('an explicit source recheck cannot apply its late review to another conversation', async () => {
  let finish!: (value: any) => void;
  const f = fixture({ 'models.api.review': () => new Promise(resolve => { finish = resolve; }) }, { state: { modelSelectionRevision: 7,
    conversationModel: { scopeKey: 'conversation:a', revision: 3, inherited: false,
      target: { kind: 'api', connectionId: apiRow.connectionId, connectionRevision: 3, modelId: apiRow.modelId } } } });
  await f.flush(); f.row('configured-model'); await f.flush();
  assert.equal(typeof finish, 'function', 'rechecking uses the original native review handler');
  f.change({ conversationModel: { scopeKey: 'conversation:b', revision: 0, inherited: true, target: null } });
  finish({ ...apiProjection, reviewHandle: 'api-review', model: apiRow }); await f.flush();
  assert.equal(f.selections.length, 0); assert.equal(f.selected, 0); f.unmount();
});

test('details and favorite remain independent of the direct selection path', async () => {
  const f = fixture(); await f.flush(); f.click('查看模型详情：configured-model，工作 API'); await f.flush();
  assert.match(f.text, /图片输入/); assert.equal(f.selections.length, 0);
  f.click('收藏'); await f.flush();
  assert.deepEqual(f.calls.find(call => call.action === 'models.api.favorite')?.args, { windowId: 'api-window', connectionId: 'service-a', modelId: 'configured-model', expectedRevision: 4, favorite: true });
  assert.equal(f.selections.length, 0); assert.match(f.text, /收藏已更新/);
});

test('a late review cannot select into a different conversation or source revision', async () => {
  for (const patch of [{ modelSelectionRevision: 8 }, { conversationModel: { scopeKey: 'conversation:b', revision: 0, inherited: true, target: null } }]) {
    let finish!: (value: any) => void;
    const f = fixture({ 'models.api.review': () => new Promise(resolve => { finish = resolve; }) }, { state: { modelSelectionRevision: 7, conversationModel: { scopeKey: 'conversation:a', revision: 3, inherited: true, target: null } } });
    await f.flush(); f.row('configured-model'); await f.flush(); f.change(patch); finish({ ...apiProjection, reviewHandle: 'api-review', model: apiRow }); await f.flush();
    assert.equal(f.selections.length, 0); assert.equal(f.selected, 0); f.unmount();
  }
});

test('a pending direct choice cannot be submitted twice or displayed as saved', async () => {
  let finish!: (value: any) => void, attempts = 0;
  const f = fixture({}, { onSelectTarget: () => { attempts++; return new Promise(resolve => { finish = resolve; }); } }); await f.flush();
  const choose = f.find(node => node.props['aria-label'] === '选择模型：configured-model，工作 API').props.onClick;
  choose(); choose(); await f.flush();
  assert.equal(attempts, 1); assert.match(f.text, /正在保存模型/); assert.doesNotMatch(f.text, /模型已保存/);
  finish({ status: 'saved' }); await f.flush(); assert.equal(f.selected, 1); assert.match(f.text, /模型已保存/);
});

test('unknown results remain unresolved after source publication and rereading never replays the write', async () => {
  let attempts = 0;
  const f = fixture({}, { onSelectTarget: async () => { attempts++; return { status: 'unknown' }; } }); await f.flush(); f.row('configured-model'); await f.flush();
  f.change({ modelSelectionRevision: 8 }); assert.match(f.text, /没有自动重试/);
  f.responses['models.api.open'] = { ...apiProjection, selectionRevision: 8 }; f.responses['models.accounts.open'] = { ...accountProjection, selectionRevision: 8 };
  f.click('重新读取'); await f.flush(); assert.equal(attempts, 1); assert.equal(f.selected, 0); assert.doesNotMatch(f.text, /模型已保存/);
});

test('account reviews from a different window or account cannot be selected', async () => {
  for (const replacement of [{ windowId: 'other-window' }, { accountId: 'other-account' }, { modelId: 'other-model' }]) {
    const f = fixture(); await f.flush(); f.row('个人账号'); await f.flush();
    f.responses['models.accounts.review'] = { ...f.responses['models.accounts.review'], ...replacement };
    f.row('套餐目录模型'); await f.flush(); assert.equal(f.selections.length, 0); assert.match(f.text, /审核已变化/); f.unmount();
  }
});

test('an expired detail review cannot become a write even if the button was rendered earlier', async () => {
  const f = fixture({ 'models.api.review': { ...apiProjection, reviewHandle: 'api-review', model: apiRow, expiresInMs: 1 } }); await f.flush();
  f.click('查看模型详情：configured-model，工作 API'); await f.flush();
  const save = f.find(node => node.type === 'Button' && node.children.join('') === '用于本对话').props.onClick;
  const originalNow = Date.now;
  try { Date.now = () => originalNow() + 1000; save(); await f.flush(); } finally { Date.now = originalNow; }
  assert.equal(f.selections.length, 0); assert.match(f.text, /已过期/);
});

test('presentation state preserves only query/filter and reopening uses newly opened native windows', async () => {
  const changes: any[] = [];
  const f = fixture({}, { presentationState: { query: 'configured', filter: 'all' }, onPresentationChange: (value: any) => changes.push(value) }); await f.flush();
  const search = f.find(node => node.type === 'SearchField'); assert.equal(search.props.value, 'configured'); search.props.onChange('工作');
  assert.deepEqual(changes.at(-1), { query: '工作', filter: 'all' });
  f.unmount(); await f.flush();
  const second = fixture({ 'models.api.open': { ...apiProjection, windowId: 'new-api-window' } }, { presentationState: changes.at(-1) }); await second.flush();
  assert.equal(second.find(node => node.type === 'SearchField').props.value, '工作');
  assert.equal(second.calls.filter(call => call.action === 'models.api.open').length, 1);
  assert.equal(second.calls.some(call => call.args.windowId === 'api-window'), false); second.unmount();
});

test('a saved conversation choice remains visible and rereads fresh windows only after its native publication', async () => {
  let finish!: (value: any) => void, writes = 0;
  const preference = { scopeKey: 'conversation:a', revision: 3, inherited: false, target: { kind: 'api', connectionId: 'service-a', modelId: 'previous-model' } };
  const f = fixture({}, { state: { modelSelectionRevision: 7, modelName: apiRow.modelId, conversationModel: preference }, onSelectTarget: () => { writes++; return new Promise(resolve => { finish = resolve; }); } });
  await f.flush(); f.row('configured-model'); await f.flush();
  const next = { ...preference, revision: 4, target: { ...preference.target, modelId: apiRow.modelId } };
  f.responses['models.api.open'] = { ...apiProjection, windowId: 'saved-window', selectionRevision: 9 };
  f.responses['models.accounts.open'] = { ...accountProjection, windowId: 'saved-account-window', selectionRevision: 9 };
  finish({ status: 'saved', scope: 'conversation', preference: next }); await f.flush();
  assert.equal(f.calls.filter(call => call.action === 'models.api.open').length, 1, 'receipt alone must not reuse or stamp old source windows');
  f.change({ modelSelectionRevision: 9, conversationModel: next }); await f.flush();
  assert.match(f.text, /模型已保存/);
  assert.equal(f.find(node => node.props['aria-label'] === '选择模型：configured-model，工作 API').props['aria-pressed'], true);
  assert.equal(f.calls.filter(call => call.action === 'models.api.open').length, 2);
  assert.equal(writes, 1); assert.equal(f.selected, 1);
});

const explicitPreference = { scopeKey: 'conversation:a', revision: 3, inherited: false,
  target: { kind: 'api', connectionId: apiRow.connectionId, connectionRevision: 4, modelId: 'conversation-model' } };
const resetState = { modelSelectionRevision: 7, modelName: apiRow.modelId, conversationModel: explicitPreference };
const resetReceipt = { status: 'saved', scope: 'conversation', preference: { ...explicitPreference, revision: 4, inherited: true, target: null } };

test('restoring the default from the model picker preserves original conversation/source revisions and retires its windows', async () => {
  const f = fixture({ 'models.conversation.reset': resetReceipt }, { state: resetState }); await f.flush();
  f.click('恢复使用默认模型'); await f.flush();
  assert.deepEqual(f.calls.find(call => call.action === 'models.conversation.reset')?.args,
    { expectedPreferenceRevision: 3, expectedSelectionRevision: 7 });
  assert.match(f.text, /已恢复使用默认模型/); assert.equal(f.selections.length, 0);
  assert.equal(f.calls.some(call => call.action === 'models.conversation.reasoning'), false);
  assert.deepEqual(f.pending, [true, false]);
  f.unmount(); assert.equal(f.calls.filter(call => /models\.(api|accounts)\.close/.test(call.action)).length, 2);
});

test('an unknown model reset cannot be submitted again until an explicit successful reread', async () => {
  const f = fixture({ 'models.conversation.reset': () => Promise.reject(new Error('回执未知')) }, { state: resetState }); await f.flush();
  f.click('恢复使用默认模型'); await f.flush();
  assert.equal(f.find(node => node.props.className === 'pw-conversation-model-reset').props.disabled, true);
  assert.match(f.text, /保存结果待核对/); assert.match(f.text, /没有自动重试/); assert.doesNotMatch(f.text, /已恢复使用默认模型/);
  f.click('重新读取'); await f.flush();
  assert.equal(f.find(node => node.props.className === 'pw-conversation-model-reset').props.disabled, false);
  assert.equal(f.calls.filter(call => call.action === 'models.conversation.reset').length, 1);
});

test('a pending model reset blocks duplicate writes and never confirms a late receipt in another conversation', async () => {
  let finish!: (value: any) => void;
  const f = fixture({ 'models.conversation.reset': () => new Promise(resolve => { finish = resolve; }) }, { state: resetState }); await f.flush();
  const reset = f.find(node => node.props.className === 'pw-conversation-model-reset').props.onClick;
  reset(); reset(); await f.flush();
  assert.equal(f.calls.filter(call => call.action === 'models.conversation.reset').length, 1);
  assert.match(f.text, /正在保存模型/); assert.doesNotMatch(f.text, /已恢复使用默认模型/);
  f.change({ conversationModel: { ...explicitPreference, scopeKey: 'conversation:b' } }); finish(resetReceipt); await f.flush();
  assert.doesNotMatch(f.text, /已恢复使用默认模型/); assert.equal(f.selected, 0); f.unmount();
});

test('reset requires an exact inherited receipt with no remaining conversation target', async () => {
  for (const patch of [{ scopeKey: 'conversation:b' }, { revision: 7 }, { inherited: false }, { target: explicitPreference.target }]) {
    const f = fixture({ 'models.conversation.reset': { ...resetReceipt, preference: { ...resetReceipt.preference, ...patch } } }, { state: resetState }); await f.flush();
    f.click('恢复使用默认模型'); await f.flush();
    assert.match(f.text, /保存结果待核对/); assert.doesNotMatch(f.text, /已恢复使用默认模型/);
    assert.equal(f.find(node => node.props.className === 'pw-conversation-model-reset').props.disabled, true);
    assert.equal(f.calls.filter(call => call.action === 'models.conversation.reset').length, 1); f.unmount();
  }
});

test('model reset is absent for inherited conversations and the global management picker', async () => {
  for (const extra of [{ state: { ...resetState, conversationModel: resetReceipt.preference } }, { scope: 'default', state: resetState }]) {
    const f = fixture({}, extra); await f.flush();
    assert.equal(f.has(node => node.props.className === 'pw-conversation-model-reset'), false);
    assert.equal(f.calls.some(call => call.action === 'models.conversation.reset'), false); f.unmount();
  }
});

test('account details expose reasoning edits only in AI management, never in the conversation model sheet', async () => {
  for (const scope of ['conversation', 'default']) {
    const f = fixture({}, { scope }); await f.flush(); f.row('个人账号'); await f.flush();
    f.find(node => node.type === 'IconButton' && node.props['aria-label']?.startsWith('查看模型详情：套餐目录模型')).props.onClick(); await f.flush();
    assert.equal(f.has(node => node.type === 'SelectField' && node.props.label === '推理设置'), scope === 'default');
    assert.equal(f.has(node => node.type === 'TextField' && node.props.label === '输出预留'), true);
    assert.equal(f.selections.length, 0); f.unmount();
  }
});
