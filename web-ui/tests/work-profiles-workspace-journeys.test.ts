import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import * as contract from '../src/profile-contract.ts';

// Production component/handlers with hook, adopted-control and host doubles.
// This does not establish browser, Android, source-service or visual behavior.
type Data = Record<string, any>;
const exactModel = { kind: 'exact', connectionId: 'connection-original', modelId: 'model-original' };
const profile = { ...contract.newProfile(), profileId: 'profile-original', revision: 7, name: '日常工作', modelPolicies: { MAIN: exactModel }, targetBindings: [{ id: 'computer-original', revision: 3, optional: false }] };
const skill = { id: 'skill-original', name: '原资料', revision: 4, digest: 'skill-digest', active: true, contentVerified: true };
const choices = { choicesHandle: 'choices-original', useCurrentProject: false, modelChoices: [{ label: '原模型', detail: '原连接', reference: exactModel }], relayModelChoices: [], sections: { skills: { available: true, items: [skill] }, plugins: { available: true, items: [] }, extensions: { available: true, items: [] }, teams: { available: true, items: [] } } };
const snapshot = { revision: 1, profiles: [profile] };
const review = { reviewHandle: 'review-original', operationId: 'operation-original', command: 'save', definition: profile, original: profile };
function fixture(responses: Data = {}) {
  const ast = ts.createSourceFile('WorkProfilesWorkspace.tsx', readFileSync(new URL('../src/WorkProfilesWorkspace.tsx', import.meta.url), 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const body = ast.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(ast).replace(/^export /, '')).join('\n');
  const code = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
  let slots: any[] = [], index = 0, dirty = false, tree: any, back: (() => boolean) | null = null;
  const effects: (() => void)[] = [], calls: Data[] = [], expanded = new Set<string>(), listeners = new Map<string, Set<() => void>>(), subscribers = new Set<() => void>();
  const host: Data = { view: { section: 'workProfiles', resumeRevision: 1 } };
  const listen = { addEventListener: (name: string, callback: () => void) => { if (!listeners.has(name)) listeners.set(name, new Set()); listeners.get(name)!.add(callback); }, removeEventListener: (name: string, callback: () => void) => listeners.get(name)?.delete(callback) };
  const document = { visibilityState: 'visible', ...listen };
  const useState = (initial: any) => { const i = index++; slots[i] ??= { value: typeof initial === 'function' ? initial() : initial }; return [slots[i].value, (next: any) => { const value = typeof next === 'function' ? next(slots[i].value) : next; if (!Object.is(value, slots[i].value)) { slots[i].value = value; dirty = true; } }]; };
  const useRef = (initial: any) => { const i = index++; return slots[i] ??= { current: initial }; };
  const useEffect = (callback: () => any, deps?: any[]) => { const i = index++, prior = slots[i]; if (!deps || !prior?.deps || deps.some((value, j) => !Object.is(value, prior.deps[j]))) { const slot = slots[i] = { deps, cleanup: prior?.cleanup }; effects.push(() => { slot.cleanup?.(); slot.cleanup = callback(); }); } };
  const React: Data = { Fragment: 'Fragment', createElement: (type: any, props: any, ...children: any[]) => {
    const flattened = children.flat(Infinity).filter(child => child !== false && child !== null && child !== undefined);
    if (typeof type === 'function') return type({ ...props, children: flattened });
    if (type === 'ObjectTable') flattened.push(...props.rows.flatMap((row: Data) => props.columns.map((column: Data) => column.render(row))));
    return { type, props: props || {}, children: flattened };
  } };
  const bridge = { getState: () => host, getDocumentEpoch: () => 1, subscribe: (callback: () => void) => { subscribers.add(callback); return () => subscribers.delete(callback); }, command: async (action: string, args: Data = {}) => { calls.push({ action, args }); return {}; } };
  const dependencies: Data = { React, useState, useRef, useEffect, document, window: listen, bridge, ...contract };
  for (const name of ['Accordion', 'AccordionItem', 'Button', 'CheckboxField', 'ObjectTable', 'SelectField', 'SettingsDialog', 'TextField', 'PressButton', 'Tabs', 'TabList', 'Tab', 'TabPanel', 'ArrowLeft', 'ChevronRight', 'FileSliders', 'RefreshCw']) dependencies[name] = name;
  const Component = new Function(...Object.keys(dependencies), code + '\nreturn WorkProfilesWorkspace;')(...Object.values(dependencies));
  const values: Data = {
    'profiles.snapshot': snapshot,
    'profiles.read': { profile, viewHandle: 'view-original' },
    'profiles.choices': (args: Data) => ({ ...choices, useCurrentProject: args.useCurrentProject }),
    'profiles.reviewSave': (args: Data) => ({ ...review, definition: args.definition, command: args.viewHandle ? 'save' : 'create', original: args.viewHandle ? profile : null }),
    'profiles.reviewRebind': (args: Data) => ({ ...review, definition: args.definition, command: 'rebind' }),
    'profiles.reviewRemove': { ...review, command: 'remove' },
    'profiles.confirm': { confirmed: true, operationId: review.operationId, command: 'save', record: { ...profile, revision: 8 }, snapshot },
    'profiles.preflight': { ready: true, items: [{ kind: 'model', id: 'MAIN', included: true }] },
    'profiles.result': { operationId: review.operationId, readOnly: true, replayAllowed: false, status: 'not_entered', snapshot },
    ...responses,
  };
  const props = { state: {}, supported: () => true, registerTransientBack: (handler: any) => { back = handler; }, run: async (action: string, args: Data = {}) => { calls.push({ action, args }); const value = values[action]; return typeof value === 'function' ? value(args) : value || {}; } };
  const hidden = (node: any) => node.props.hidden || node.type === 'SettingsDialog' && !node.props.isOpen;
  const walk = (node: any, inactive = false): any[] => !node || typeof node !== 'object' ? [] : [{ ...node, visible: !inactive && !hidden(node) }, ...node.children.flatMap((child: any) => walk(child, inactive || hidden(node) || node.type === 'AccordionItem' && !expanded.has(node.props.id)))];
  const content = (node: any, visible = false): string => !node || typeof node !== 'object' ? String(node ?? '') : visible && hidden(node) ? '' : node.type === 'AccordionItem' ? String(node.props.title) + String(node.props.description || '') + (!visible || expanded.has(node.props.id) ? node.children.map((child: any) => content(child, visible)).join('') : '') : node.children.map((child: any) => content(child, visible)).join('');
  const render = () => { let rounds = 0; do { assert.ok(rounds++ < 30, 'production render settles'); dirty = false; index = 0; tree = Component(props); effects.splice(0).forEach(callback => callback()); } while (dirty); };
  const find = (predicate: (node: any) => boolean, visible = true) => { const node = walk(tree).filter(node => (!visible || node.visible) && predicate(node)).at(-1); assert.ok(node, 'expected production control'); return node; };
  const button = (label: string) => find(node => ['Button', 'PressButton'].includes(node.type) && (node.props['aria-label'] === label || content(node) === label));
  const unmount = () => { for (const slot of slots) slot?.cleanup?.(); };
  render();
  return { values, calls, render, find, button, unmount,
    get text() { return content(tree, true); },
    get panel() { return find(node => node.type === 'Tabs', false).props.selectedKey; },
    click(label: string) { const node = button(label); assert.ok(!node.props.disabled, `${label} is enabled`); node.props.onClick(); render(); },
    field(label: string, value: any) { const node = find(node => ['TextField', 'SelectField'].includes(node.type) && node.props.label === label); assert.ok(!node.props.isDisabled, `${label} is enabled`); node.props.onChange(value); render(); },
    value(label: string) { return find(node => node.props.label === label, false).props.value; },
    check(label: string, value = true) { const node = find(node => node.type === 'CheckboxField' && content(node).startsWith(label)); assert.ok(!node.props.isDisabled, `${label} is enabled`); node.props.onChange(value); render(); },
    tab(id: string) { find(node => node.type === 'Tabs').props.onSelectionChange(id); render(); },
    expand(id: string) { expanded.add(id); render(); },
    close() { find(node => node.type === 'SettingsDialog' && node.props.isOpen).props.onClose(); render(); },
    back() { const handled = back?.(); render(); return handled; },
    hide() { document.visibilityState = 'hidden'; for (const callback of [...listeners.get('visibilitychange') || []]) callback(); render(); },
    show() { document.visibilityState = 'visible'; for (const callback of [...listeners.get('visibilitychange') || []]) callback(); render(); },
    owner(section: string, revision = 2) { host.view = { section, resumeRevision: revision }; for (const callback of [...subscribers]) callback(); render(); },
    remount() { unmount(); slots = []; index = 0; dirty = false; render(); },
    async flush() { for (let i = 0; i < 15; i++) { await Promise.resolve(); if (dirty) render(); } },
  };
}
const count = (f: ReturnType<typeof fixture>, action: string) => f.calls.filter(call => call.action === action).length;
async function open(f: ReturnType<typeof fixture>) { await f.flush(); f.click('打开工作配置：日常工作'); await f.flush(); }
async function prepare(f: ReturnType<typeof fixture>) { await open(f); f.click('核对保存引用'); await f.flush(); }

test('profiles starts with the original collection and an explicit new entry, not an expanded editor', async () => {
  const f = fixture(); await f.flush(); assert.deepEqual(f.calls, [{ action: 'profiles.snapshot', args: {} }]);
  assert.match(f.text, /日常工作/); assert.doesNotMatch(f.text, /先选主任务模型|使用当前已选项目的资料范围/); assert.equal(f.back(), false);
});

test('tabs preserve one draft and current original source authority without extra reads or approvals', async () => {
  const f = fixture(); await open(f); f.field('配置名称', '我的工作草稿'); f.field('主任务模型', contract.profileReferenceKey(exactModel));
  assert.equal(f.panel, 'models'); assert.equal(count(f, 'profiles.choices'), 1);
  f.tab('sources'); f.expand('skillRefs'); f.check('原资料'); f.tab('selected'); assert.match(f.text, /skill-original/); assert.match(f.text, /computer-original/);
  f.check('明确可选'); f.tab('models'); assert.equal(f.value('配置名称'), '我的工作草稿'); assert.equal(f.value('主任务模型'), contract.profileReferenceKey(exactModel));
  assert.equal(count(f, 'profiles.read'), 1); assert.equal(count(f, 'profiles.choices'), 1); assert.equal(count(f, 'profiles.confirm'), 0);
  f.click('核对保存引用'); await f.flush(); const saved = f.calls.find(call => call.action === 'profiles.reviewSave')!.args;
  assert.equal(saved.viewHandle, 'view-original'); assert.equal(saved.definition.profileId, profile.profileId); assert.equal(saved.definition.revision, 7); assert.equal(saved.definition.name, '我的工作草稿'); assert.equal(saved.definition.skillRefs[0].digest, skill.digest);
});

test('Back and dialog close preserve ordinary input but retire view, choices and old callbacks', async () => {
  for (const close of ['back', 'dialog']) {
    const f = fixture(); await open(f); f.field('配置名称', '返回后继续'); f.expand('members'); f.field('成员标识（留空表示成员默认模型）', 'writer');
    const oldSave = f.button('核对保存引用'), oldChoices = f.button('读取当前可用来源');
    if (close === 'back') assert.equal(f.back(), true); else f.close();
    assert.match(f.text, /返回后继续/); assert.match(f.text, /有未保存修改/); assert.equal(f.button('新建配置').props.disabled, true);
    oldSave.props.onClick(); oldChoices.props.onClick(); await f.flush(); assert.equal(count(f, 'profiles.reviewSave'), 0); assert.equal(count(f, 'profiles.choices'), 1);
    f.click('继续编辑草稿'); assert.equal(f.value('配置名称'), '返回后继续'); assert.equal(f.value('成员标识（留空表示成员默认模型）'), 'writer'); assert.match(f.text, /来源窗口待?读取|来源窗口待读取/); assert.equal(f.button('核对保存引用').props.disabled, true);
    oldSave.props.onClick(); oldChoices.props.onClick(); await f.flush(); assert.equal(count(f, 'profiles.reviewSave'), 0); assert.equal(count(f, 'profiles.choices'), 1);
    f.click('重新核对原修订'); await f.flush(); f.click('读取当前可用来源'); await f.flush(); assert.equal(f.button('核对保存引用').props.disabled, false); assert.ok(f.calls.some(call => call.action === 'profiles.discard' && !Object.keys(call.args).length));
  }
});

test('review requires acknowledgement and consumes only its exact original handle once', async () => {
  const f = fixture(); await prepare(f); assert.equal(count(f, 'profiles.confirm'), 0); assert.equal(f.button('确认保存引用修改').props.disabled, true);
  f.check('我已核对上方配置'); const confirm = f.button('确认保存引用修改'); confirm.props.onClick(); confirm.props.onClick(); f.render(); await f.flush();
  assert.deepEqual(f.calls.filter(call => call.action === 'profiles.confirm'), [{ action: 'profiles.confirm', args: { reviewHandle: review.reviewHandle } }]);
  assert.match(f.text, /已收到原生保存回执/); assert.equal(f.button('核对保存引用').props.disabled, true);
});

test('returning from a review keeps the edit but cannot recycle its old acknowledgement or confirm handler', async () => {
  const f = fixture(); await prepare(f); f.check('我已核对上方配置'); const stale = f.button('确认保存引用修改');
  f.click('返回编辑'); f.values['profiles.reviewSave'] = { ...review, reviewHandle: 'review-next', operationId: 'operation-next' }; f.click('核对保存引用'); await f.flush();
  stale.props.onClick(); await f.flush(); assert.equal(count(f, 'profiles.confirm'), 0); assert.equal(f.button('确认保存引用修改').props.disabled, true);
  assert.ok(f.calls.some(call => call.action === 'profiles.discard' && call.args.reviewHandle === review.reviewHandle));
});

test('unknown result stays outside all tabs and only uses the original read-only result path without replay', async () => {
  const f = fixture({ 'profiles.confirm': () => Promise.reject(new Error('回执超时')) }); await prepare(f); f.check('我已核对上方配置'); f.click('确认保存引用修改'); await f.flush();
  for (const tab of ['models', 'sources', 'selected']) { f.tab(tab); assert.match(f.text, /原回执待核对/); assert.equal(f.button('核对保存引用').props.disabled, true); }
  f.click('返回配置列表'); assert.match(f.text, /原回执待核对/); f.click('继续编辑草稿'); f.click('核对原操作与当前清单'); await f.flush();
  assert.deepEqual(f.calls.find(call => call.action === 'profiles.result')!.args, { operationId: review.operationId }); assert.equal(count(f, 'profiles.confirm'), 1);
  f.click('我已核对，允许重新准备'); assert.equal(f.button('核对保存引用').props.disabled, true); assert.match(f.text, /下一次保存需要重新读取与审阅/);
});

test('a changed native revision stays visible across tabs and needs an explicit merge preserving the draft', async () => {
  const f = fixture(); await open(f); f.field('配置名称', '我要保留的名称'); f.click('返回配置列表'); f.click('继续编辑草稿');
  f.values['profiles.read'] = { profile: { ...profile, revision: 8, name: '别人已保存的名称' }, viewHandle: 'view-revision-8' }; f.click('重新核对原修订'); await f.flush();
  f.tab('sources'); assert.match(f.text, /原记录已有新修订/); assert.match(f.text, /别人已保存的名称/); assert.equal(f.button('核对保存引用').props.disabled, true);
  f.click('采用当前修订作为保存依据，保留我的修改'); assert.equal(f.value('配置名称'), '我要保留的名称'); f.click('核对保存引用'); await f.flush();
  const input = f.calls.find(call => call.action === 'profiles.reviewSave')!.args; assert.equal(input.viewHandle, 'view-revision-8'); assert.equal(input.definition.revision, 8); assert.equal(input.definition.name, '我要保留的名称');
});

test('restored pending state is never hidden by a tab and rebinding still requires original source acknowledgement', async () => {
  const restored = { ...profile, restoredPendingReview: true, restoredReferences: [{ kind: 'model:MAIN', reference: { ...exactModel, connectionId: 'old-device' } }] };
  const f = fixture({ 'profiles.read': { profile: restored, viewHandle: 'view-restored' } }); await open(f);
  for (const tab of ['models', 'sources', 'selected']) { f.tab(tab); assert.match(f.text, /恢复后待重新绑定，当前不能执行/); assert.equal(f.button('预检并审阅重新绑定').props.disabled, true); }
  f.check('我已核对旧引用清单'); f.click('预检并审阅重新绑定'); await f.flush();
  const input = f.calls.find(call => call.action === 'profiles.reviewRebind')!.args; assert.equal(input.viewHandle, 'view-restored'); assert.equal(input.choicesHandle, choices.choicesHandle); assert.equal(input.definition.restoredPendingReview, true); assert.deepEqual(input.definition.restoredReferences, restored.restoredReferences); assert.equal(count(f, 'profiles.confirm'), 0);
});

test('changing project scope clears the old choices while preserving exact selected references', async () => {
  const f = fixture(); await open(f); f.check('使用当前已选项目的资料范围'); assert.equal(f.find(node => node.props.label === '主任务模型').props.isDisabled, true); assert.equal(f.value('主任务模型'), contract.profileReferenceKey(exactModel));
  f.click('读取当前可用来源'); await f.flush(); assert.deepEqual(f.calls.filter(call => call.action === 'profiles.choices').at(-1)!.args, { useCurrentProject: true }); assert.equal(f.find(node => node.props.label === '主任务模型').props.isDisabled, false);
  assert.ok(f.calls.some(call => call.action === 'profiles.discard' && call.args.choicesHandle === choices.choicesHandle));
});

test('late owner reads and reviews are discarded after backgrounding rather than reopening a permission window', async () => {
  let resolveRead: (value: Data) => void = () => {};
  const f = fixture({ 'profiles.read': () => new Promise(resolve => { resolveRead = resolve; }) }); await f.flush(); f.click('打开工作配置：日常工作'); f.hide(); resolveRead({ profile, viewHandle: 'late-view' }); await f.flush();
  assert.doesNotMatch(f.text, /先选主任务模型/); assert.ok(f.calls.some(call => call.action === 'profiles.discard' && call.args.viewHandle === 'late-view'));
  let resolveReview: (value: Data) => void = () => {};
  const g = fixture({ 'profiles.reviewSave': () => new Promise(resolve => { resolveReview = resolve; }) }); await open(g); g.field('配置名称', '后台仍保留'); g.click('核对保存引用'); g.hide(); resolveReview({ ...review, reviewHandle: 'late-review' }); await g.flush();
  assert.equal(g.value('配置名称'), '后台仍保留'); assert.ok(g.calls.some(call => call.action === 'profiles.discard' && call.args.reviewHandle === 'late-review')); assert.equal(count(g, 'profiles.confirm'), 0);
});

test('an owner change and remount retain a draft but never restore a previous view or source window', async () => {
  const f = fixture(); await open(f); f.field('配置名称', '跨页面草稿'); const stale = f.button('核对保存引用'); f.owner('settings'); stale.props.onClick(); await f.flush(); assert.equal(count(f, 'profiles.reviewSave'), 0);
  f.owner('workProfiles', 3); await f.flush(); assert.equal(f.value('配置名称'), '跨页面草稿'); assert.equal(f.button('核对保存引用').props.disabled, true);
  f.remount(); await f.flush(); assert.equal(f.value('配置名称'), '跨页面草稿'); assert.equal(f.button('核对保存引用').props.disabled, true); assert.equal(f.find(node => node.props.label === '主任务模型').props.isDisabled, true);
});

test('new profiles use the same original review path and selected-source fields without creating native replacement controls', async () => {
  const f = fixture(); await f.flush(); f.click('新建配置'); await f.flush(); f.field('配置名称', '新工作'); f.field('主任务模型', contract.profileReferenceKey(exactModel)); f.click('核对保存引用'); await f.flush();
  const input = f.calls.find(call => call.action === 'profiles.reviewSave')!.args; assert.equal(input.viewHandle, undefined); assert.equal(input.definition.name, '新工作'); assert.equal(count(f, 'profiles.confirm'), 0);
  const source = readFileSync(new URL('../src/WorkProfilesWorkspace.tsx', import.meta.url), 'utf8'), css = readFileSync(new URL('../src/work-profiles-workspace.css', import.meta.url), 'utf8');
  assert.doesNotMatch(source, /<(?:input|select|button|textarea)\b/); assert.match(source, /from '\.\/shell-library'/); assert.match(source, /<ObjectTable/); assert.match(css, /\.pw-profile-tabs \[hidden\]/); assert.match(css, /\.pw-profile-editor-footer/); assert.doesNotMatch(css, /linear-gradient|backdrop-filter/);
});

test('new-profile confirmation accepts only its matching operation and first native revision', async () => {
  const saved = { ...contract.newProfile(), profileId: 'profile-created', revision: 1, name: '新工作' };
  const f = fixture({ 'profiles.confirm': { confirmed: true, operationId: review.operationId, command: 'create', record: saved, snapshot: { revision: 2, profiles: [saved] } } });
  await f.flush(); f.click('新建配置'); await f.flush(); f.field('配置名称', '新工作'); f.click('核对保存引用'); await f.flush(); f.check('我已核对上方配置'); f.click('确认新建工作配置'); await f.flush();
  assert.match(f.text, /已收到原生保存回执/); assert.equal(f.value('配置名称'), '新工作'); assert.equal(f.button('核对保存引用').props.disabled, true); assert.equal(count(f, 'profiles.confirm'), 1);
});

test('a confirmation lost on background stays unknown even when its late success arrives', async () => {
  let resolveConfirm: (value: Data) => void = () => {};
  const f = fixture({ 'profiles.confirm': () => new Promise(resolve => { resolveConfirm = resolve; }) }); await prepare(f); f.check('我已核对上方配置'); f.click('确认保存引用修改'); f.hide();
  resolveConfirm({ confirmed: true, operationId: review.operationId, command: 'save', record: { ...profile, revision: 8 }, snapshot }); await f.flush(); f.show(); await f.flush();
  assert.match(f.text, /原回执待核对/); assert.doesNotMatch(f.text, /已收到原生保存回执/); assert.equal(f.button('核对保存引用').props.disabled, true); assert.equal(f.button('我已核对，允许重新准备').props.disabled, true); assert.equal(count(f, 'profiles.confirm'), 1);
});
