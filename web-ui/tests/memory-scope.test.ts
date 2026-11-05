import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';

// Runs production TSX handlers with hook/control/native-receipt doubles.
// This is not browser, accessibility, Android or visual verification.
type Data = Record<string, any>;
const scopeSnapshot = (scope: string) => ({ scopeName: scope === 'private' ? '主人全局 · 私人' : '项目甲',
  records: [{ id: scope + '-record', text: scope + ' 原记忆', layer: 'fact', active: true, consolidationBlocked: false, ownerId: 'owner', spaceId: 'private' }],
  candidates: [{ id: scope + '-candidate', text: scope + ' 待审候选', layer: 'fact', status: 'pending' }] });
const deferred = () => { let resolve!: (value: any) => void, reject!: (error: Error) => void; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
function fixture(overrides: Data = {}, initialCandidate: Data | null = null) {
  const source = readFileSync(new URL('../src/MemoryWorkspace.tsx', import.meta.url), 'utf8');
  const ast = ts.createSourceFile('MemoryWorkspace.tsx', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const body = ast.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(ast).replace(/^export /, '')).join('\n');
  const code = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
  const slots: any[] = [], effects: (() => void)[] = [], calls: Data[] = [];
  let index = 0, dirty = false, tree: any, scope = 'private';
  const useState = (initial: any) => { const i = index++; slots[i] ??= { value: initial }; return [slots[i].value, (next: any) => { const value = typeof next === 'function' ? next(slots[i].value) : next; if (!Object.is(value, slots[i].value)) { slots[i].value = value; dirty = true; } }]; };
  const useRef = (initial: any) => { const i = index++; return slots[i] ??= { current: initial }; };
  const useEffect = (callback: () => any, deps?: any[]) => { const i = index++, previous = slots[i]; if (!deps || !previous?.deps || deps.some((v, j) => !Object.is(v, previous.deps[j]))) { const slot = slots[i] = { deps, cleanup: previous?.cleanup }; effects.push(() => { slot.cleanup?.(); slot.cleanup = callback(); }); } };
  const React = { createElement: (type: any, props: any, ...children: any[]) => ({ type, props: props || {}, children: children.flat(Infinity).filter(v => v !== false && v != null) }) };
  const dependencies: Data = { React, useState, useRef, useEffect, useLayoutEffect: useEffect, document: { visibilityState: 'visible' } };
  for (const name of ['RiArrowLeftSLine', 'RiArrowRightSLine', 'RiRefreshLine', 'Tabs', 'TabList', 'Tab', 'TabPanel', 'Accordion', 'AccordionItem', 'Button', 'CheckboxField', 'IconButton', 'SearchField', 'SelectField', 'SettingsDialog', 'TextAreaField', 'TextField', 'MemoryAssertionFields']) dependencies[name] = name;
  const Component = new Function(...Object.keys(dependencies), code + '\nreturn MemoryWorkspace;')(...Object.values(dependencies));
  const values: Data = { 'knowledge.snapshot': () => scopeSnapshot(scope), 'knowledge.namespaces': { choices: [{ choiceHandle: 'project-a', name: '项目甲' }] },
    'knowledge.namespace': (args: Data) => { scope = args.choiceHandle || 'private'; return { scopeName: scopeSnapshot(scope).scopeName }; },
    'knowledge.get': (args: Data) => ({ readHandle: 'read-' + args.id, candidateSelected: args.candidate, [args.candidate ? 'candidate' : 'record']: scopeSnapshot(scope)[args.candidate ? 'candidates' : 'records'][0] }),
    'knowledge.close': {}, 'knowledge.history': { entries: [{ id: 'history', text: '原范围历史', status: 'accepted' }] }, 'knowledge.capacity': { candidatesUsed: 7, candidateLimit: 20 }, ...overrides };
  const props = { domain: 'memory', initialCandidate, registerTransientBack: () => {}, run: async (action: string, args: Data = {}) => { calls.push({ action, args }); assert.ok(action in values, 'expected native action ' + action); const result = values[action]; return typeof result === 'function' ? result(args) : result; } };
  const hidden = (node: any) => node.props.hidden || node.type === 'SettingsDialog' && !node.props.isOpen;
  const content = (node: any): string => !node || typeof node !== 'object' ? String(node ?? '') : hidden(node) ? '' : node.children.map(content).join('');
  const walk = (node: any): any[] => !node || typeof node !== 'object' || hidden(node) ? [] : [node, ...node.children.flatMap(walk)];
  const render = () => { let count = 0; do { assert.ok(count++ < 30); dirty = false; index = 0; tree = Component(props); effects.splice(0).forEach(fn => fn()); } while (dirty); };
  const find = (predicate: (node: any) => boolean) => { const value = walk(tree).find(predicate); assert.ok(value, 'production control exists'); return value; };
  const button = (label: string) => find(node => ['Button', 'IconButton'].includes(node.type) && (node.props['aria-label'] === label || content(node) === label));
  const select = () => find(node => node.type === 'SelectField' && node.props.label === '本次查看范围');
  render();
  return { values, calls, render, find, button, select,
    get text() { return content(tree); },
    click(label: string) { const node = button(label); assert.ok(!node.props.disabled, label + ' enabled'); node.props.onClick(); render(); },
    change(value: string) { const node = select(); assert.ok(!node.props.isDisabled); node.props.onChange(value); render(); },
    tab(value: string) { find(node => node.type === 'Tabs').props.onSelectionChange(value); render(); },
    unmount() { for (const slot of slots) slot?.cleanup?.(); },
    async flush() { for (let i = 0; i < 22; i++) { await Promise.resolve(); if (dirty) render(); } },
  };
}
const count = (f: ReturnType<typeof fixture>, action: string) => f.calls.filter(value => value.action === action).length;
async function ready(f: ReturnType<typeof fixture>) { await f.flush(); f.click('读取可用项目范围'); await f.flush(); }

test('switching scope automatically reloads records and candidates after the namespace receipt', async () => {
  const f = fixture(); await ready(f); f.change('project-a'); await f.flush();
  assert.deepEqual(f.calls.map(value => value.action), ['knowledge.snapshot', 'knowledge.namespaces', 'knowledge.namespace', 'knowledge.snapshot']);
  assert.match(f.text, /project-a 原记忆/); assert.doesNotMatch(f.text, /private 原记忆/);
  f.tab('candidates'); assert.match(f.text, /project-a 待审候选/);
  f.change('private'); await f.flush(); assert.match(f.text, /private 待审候选/); assert.equal(count(f, 'knowledge.snapshot'), 3);
});

test('delayed switch and delayed snapshot stay single-flight and cannot expose old rows', async () => {
  const selected = deferred(), read = deferred(), f = fixture(); await ready(f);
  const staleChange = f.select().props.onChange, staleRefresh = f.button('重新读取记忆').props.onClick;
  f.values['knowledge.namespace'] = () => selected.promise; f.values['knowledge.snapshot'] = () => read.promise;
  f.change('project-a'); staleChange('private'); staleRefresh(); await f.flush();
  assert.equal(count(f, 'knowledge.namespace'), 1); assert.equal(count(f, 'knowledge.snapshot'), 1);
  assert.equal(f.select().props.isDisabled, true); assert.doesNotMatch(f.text, /private 原记忆/);
  selected.resolve({ scopeName: '项目甲' }); await f.flush();
  assert.equal(count(f, 'knowledge.snapshot'), 2); assert.equal(f.button('重新读取记忆').props.disabled, true);
  assert.match(f.text, /项目甲/); assert.doesNotMatch(f.text, /private 原记忆/);
  staleChange('private'); staleRefresh(); await f.flush(); assert.equal(count(f, 'knowledge.snapshot'), 2);
  read.resolve(scopeSnapshot('project-a')); await f.flush(); assert.match(f.text, /project-a 原记忆/); assert.equal(f.select().props.isDisabled, false);
});

test('namespace failure never reloads or restores an unconfirmed scope; private can be explicitly selected again', async () => {
  const f = fixture(); await ready(f); f.values['knowledge.namespace'] = () => { throw new Error('范围选择失败'); };
  f.change('project-a'); await f.flush();
  assert.equal(count(f, 'knowledge.snapshot'), 1); assert.match(f.text, /范围选择失败/); assert.doesNotMatch(f.text, /正在读取原记忆|private 原记忆/);
  assert.match(f.text, /请先重新选择范围/);
  assert.equal(f.select().props.value, ''); assert.equal(f.select().props.isDisabled, false);
  f.values['knowledge.namespace'] = () => ({ scopeName: '主人全局 · 私人' }); f.change('private'); await f.flush(); assert.match(f.text, /private 原记忆/);
});

test('snapshot failure after a successful switch is not perpetual loading and manual retry reads only the new scope', async () => {
  const f = fixture(); await ready(f); f.values['knowledge.snapshot'] = () => { throw new Error('新范围读取失败'); };
  f.change('project-a'); await f.flush();
  assert.equal(f.select().props.value, 'project-a'); assert.match(f.text, /项目甲/); assert.match(f.text, /新范围读取失败/);
  assert.doesNotMatch(f.text, /private 原记忆|正在读取原记忆/); assert.equal(f.button('重新读取记忆').props.disabled, false);
  f.values['knowledge.snapshot'] = () => scopeSnapshot('project-a'); f.click('重新读取记忆'); await f.flush();
  assert.match(f.text, /project-a 原记忆/); assert.doesNotMatch(f.text, /新范围读取失败/); assert.equal(count(f, 'knowledge.namespace'), 1);
});

test('initial read failure can be retried without claiming that a request remains active', async () => {
  const f = fixture({ 'knowledge.snapshot': () => { throw new Error('首次读取失败'); } }); await f.flush();
  assert.doesNotMatch(f.text, /正在读取原记忆/); assert.match(f.text, /首次读取失败/);
  f.values['knowledge.snapshot'] = () => scopeSnapshot('private'); f.click('重新读取记忆'); await f.flush(); assert.match(f.text, /private 原记忆/);
});

test('a pending old snapshot blocks scope changes; a late unmounted switch never issues a new read', async () => {
  const read = deferred(), f = fixture({ 'knowledge.snapshot': () => read.promise });
  f.select().props.onChange('project-a'); await f.flush(); assert.equal(count(f, 'knowledge.namespace'), 0);
  read.resolve(scopeSnapshot('private')); await f.flush(); assert.match(f.text, /private 原记忆/);
  const switched = deferred(); f.values['knowledge.namespace'] = () => switched.promise; f.change('project-a'); f.unmount(); switched.resolve({ scopeName: '项目甲' }); await f.flush();
  assert.equal(count(f, 'knowledge.snapshot'), 1);
});

test('scope switch retires prior history, capacity, selections and invalidated namespace handles', async () => {
  const f = fixture(); await ready(f); f.click('候选容量'); await f.flush(); f.click('已处理候选历史'); await f.flush();
  f.click('选择记忆归纳');
  f.find(node => node.type === 'CheckboxField' && node.props.className === 'pw-memory-select').props.onChange(true); await f.flush();
  assert.match(f.text, /原范围历史/); assert.match(f.text, /7/); f.change('project-a'); await f.flush();
  assert.doesNotMatch(f.text, /原范围历史|候选容量：/);
  assert.doesNotMatch(f.text, /已选择 1 条|退出多选/);
  assert.ok(f.calls.some(value => value.action === 'knowledge.close' && value.args.handle === 'read-private-record'));
  const project = f.select().props.options.find((value: Data) => value.id === 'project-a');
  assert.equal(project.disabled, true, 'retired native choice may label current scope but cannot authorize another switch');
  f.change('private'); await f.flush(); assert.equal(f.select().props.options.some((value: Data) => value.id === 'project-a'), false);
});

test('initial candidate still opens only after snapshot releases the invocation lock', async () => {
  const f = fixture({}, { id: 'private-candidate', sourceTitle: '原学习来源', revision: 1 }); await f.flush();
  assert.deepEqual(f.calls.map(value => value.action), ['knowledge.snapshot', 'knowledge.get']);
  assert.match(f.text, /private 待审候选/); assert.match(f.text, /原学习来源/);
});
