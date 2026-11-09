import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import { modelConnectionCommand } from '../src/forms-library/host-contracts.ts';
import * as policy from '../src/model-connection-policy.ts';

// Real ModelSettings handlers; form and hook doubles isolate the bridge.
// These checks do not prove graphics, real credentials, or a live provider.
type Data = Record<string, any>;
function fixture(responses: Data = {}, propsOverride: Data = {}) {
  const ast = ts.createSourceFile('Secondary.tsx', readFileSync(new URL('../src/Secondary.tsx', import.meta.url), 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const names = new Set(['object', 'list', 'text', 'first', 'ordinaryModelDraft', 'ModelSettings']);
  const selected = ast.statements.filter(node => ts.isFunctionDeclaration(node) && names.has(node.name?.text || '') || ts.isVariableStatement(node) && node.declarationList.declarations.some(item => ts.isIdentifier(item.name) && names.has(item.name.text)));
  const code = ts.transpileModule(selected.map(node => node.getText(ast)).join('\n'), { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
  const slots: any[] = [], effects: Array<() => void> = [], calls: Data[] = [], saved: Data[] = [], drafts: any[] = [], events = new Map<string, Set<() => void>>();
  let index = 0, dirty = false, tree: any;
  const useState = (initial: any) => { const i = index++; slots[i] ??= { value: typeof initial === 'function' ? initial() : initial }; return [slots[i].value, (value: any) => { const next = typeof value === 'function' ? value(slots[i].value) : value; if (!Object.is(next, slots[i].value)) { slots[i].value = next; dirty = true; } }]; };
  const useRef = (initial: any) => { const i = index++; return slots[i] ??= { current: initial }; };
  const useEffect = (effect: () => any, deps?: any[]) => { const i = index++, old = slots[i]; if (!deps || !old?.deps || deps.some((value, at) => !Object.is(value, old.deps[at]))) { const slot = slots[i] = { deps, cleanup: old?.cleanup }; effects.push(() => { slot.cleanup?.(); slot.cleanup = effect(); }); } };
  const React = { Fragment: 'Fragment', createElement: (type: any, props: any, ...children: any[]) => ({ type, props: props || {}, children: children.flat(Infinity).filter(child => child !== null && child !== undefined && child !== false) }) };
  const listeners = { addEventListener: (name: string, callback: () => void) => { if (!events.has(name)) events.set(name, new Set()); events.get(name)!.add(callback); }, removeEventListener: (name: string, callback: () => void) => events.get(name)?.delete(callback) };
  const document = { visibilityState: 'visible', ...listeners };
  const dependencies: Data = { React, useState, useRef, useEffect, document, window: listeners, modelConnectionCommand, ...policy, crypto,
    bridge: { getState: () => ({ view: { section: 'models' } }), getDocumentEpoch: () => 1, subscribe: () => () => {} }, requestAnimationFrame: (fn: () => void) => { fn(); return 1; }, cancelAnimationFrame: () => {} };
  for (const key of ['TextField', 'SelectField', 'CheckboxField', 'StretchSwitch', 'Accordion', 'AccordionItem', 'Button', 'PressButton', 'Facts', 'SettingsDialog', 'ModelConnectionOptions', 'ModelConnectionTestFeedback', 'CapabilityAction', 'RiShieldCheckLine']) dependencies[key] = key;
  const Component = new Function(...Object.keys(dependencies), code + '\nreturn ModelSettings;')(...Object.values(dependencies));
  const props = { state: {}, adding: true, preset: { name: '私有服务', provider: 'openai_chat', url: 'https://api.example/v1' }, supported: () => true,
    run: async (action: string, args: Data = {}) => { calls.push({ action, args }); if (responses[action]) return typeof responses[action] === 'function' ? responses[action](args) : responses[action];
      if (action === 'settings.reviewModelTransport') return { required: false };
      if (action === 'models.connections.add' || action === 'models.connections.update') return { status: 'saved', savedConnection: { id: 'source-new', revision: 1 }, snapshot: { connections: { selectedConnectionId: 'old-default', connections: [] } }, selectionChanged: false };
      if (action === 'models.catalog.preview') return { format: 'pocketworld.draft-catalog.v1', status: 'succeeded', editorId: args.editorId, draftRevision: args.draftRevision, models: [{ modelId: 'actual-directory-model' }], saved: false, selectionChanged: false };
      return {};
    }, onSaved: (value: Data) => saved.push(value), onDraftChange: (value: any) => drafts.push(value), onInspect: () => {}, registerTransientBack: () => {}, ...propsOverride };
  const content = (node: any): string => !node || typeof node !== 'object' ? String(node ?? '') : node.children.map(content).join('');
  const nodes = (node: any): any[] => !node || typeof node !== 'object' ? [] : [node, ...node.children.flatMap(nodes)];
  const render = () => { let rounds = 0; do { assert.ok(rounds++ < 30); dirty = false; index = 0; tree = Component(props); effects.splice(0).forEach(effect => effect()); } while (dirty); };
  const find = (predicate: (node: any) => boolean) => { const node = nodes(tree).find(predicate); assert.ok(node, 'expected production source editor control'); return node; };
  render();
  return { calls, saved, drafts, find, get text() { return content(tree); },
    click(label: string) { const node = find(node => ['Button', 'PressButton'].includes(node.type) && content(node) === label); assert.ok(!node.props.disabled); node.props.onClick(); render(); },
    field(label: string, value: string) { find(node => ['TextField', 'SelectField'].includes(node.type) && node.props.label === label).props.onChange(value); render(); },
    hide() { document.visibilityState = 'hidden'; for (const callback of events.get('visibilitychange') || []) callback(); render(); },
    async flush() { for (let i = 0; i < 20; i++) { await Promise.resolve(); if (dirty) render(); } },
  };
}

test('new services can save with no model and receive their own identity without switching defaults', async () => {
  const f = fixture(); f.field('API 密钥', 'fixture-key'); f.click('保存服务'); await f.flush();
  const saved = f.calls.find(call => call.action === 'models.connections.add'); assert.ok(saved);
  assert.equal(saved.args.model, ''); assert.equal(saved.args.id, ''); assert.equal(saved.args.expectedRevision, 0); assert.equal(saved.args.name, '私有服务');
  assert.equal(f.calls.some(call => /models\.connection\.(add|select)$/.test(call.action)), false);
  assert.equal(f.saved[0].savedConnection.id, 'source-new'); assert.equal(f.saved[0].selectionChanged, false);
});
test('draft directory reads bind editor revision and do not save or select', async () => {
  const f = fixture(); f.field('API 密钥', 'fixture-key'); f.click('读取模型'); await f.flush();
  const read = f.calls.find(call => call.action === 'models.catalog.preview'); assert.ok(read); assert.equal(read.args.model, ''); assert.ok(read.args.editorId); assert.equal(read.args.draftRevision, 1);
  const selector = f.find(node => node.type === 'SelectField' && node.props.label === '选择模型'); assert.deepEqual(selector.props.options, [{ id: 'actual-directory-model', label: 'actual-directory-model' }]);
  assert.equal(f.saved.length, 0); assert.equal(f.calls.some(call => /select|connections\.add/.test(call.action)), false);
  f.field('选择模型', 'actual-directory-model'); f.click('保存服务'); await f.flush(); assert.equal(f.calls.find(call => call.action === 'models.connections.add')?.args.model, 'actual-directory-model');
});
test('a late directory response cannot attach to a changed source draft', async () => {
  let finish: ((value: Data) => void) | undefined, captured: Data = {};
  const f = fixture({ 'models.catalog.preview': (args: Data) => { captured = args; return new Promise(resolve => { finish = resolve; }); } });
  f.click('读取模型'); await f.flush(); f.field('接口地址', 'https://other.example/v1');
  finish!({ format: 'pocketworld.draft-catalog.v1', status: 'succeeded', editorId: captured.editorId, draftRevision: captured.draftRevision, models: [{ modelId: 'stale-model' }], saved: false, selectionChanged: false });
  await f.flush(); assert.doesNotMatch(f.text, /目录已读取/); assert.equal(f.saved.length, 0);
});
test('a malformed draft directory receipt is never promoted to a successful read', async () => {
  const f = fixture({ 'models.catalog.preview': { format: 'pocketworld.draft-catalog.v1', status: 'succeeded', editorId: 'different-editor', draftRevision: 0, models: [], saved: false, selectionChanged: false } });
  f.click('读取模型'); await f.flush(); assert.match(f.text, /目录回执不属于当前服务草稿/); assert.doesNotMatch(f.text, /目录已读取/);
});
test('draft models declared incompatible remain visible with an explanation but cannot be selected', async () => {
  const f = fixture({ 'models.catalog.preview': (args: Data) => ({ format: 'pocketworld.draft-catalog.v1', status: 'succeeded', editorId: args.editorId, draftRevision: args.draftRevision, saved: false, selectionChanged: false,
    models: [{ modelId: 'different-protocol', protocolCompatibility: 'declared_incompatible' }, { modelId: 'unknown-protocol', protocolCompatibility: 'unknown' }] }) });
  f.click('读取模型'); await f.flush();
  const selector = f.find(node => node.type === 'SelectField' && node.props.label === '选择模型');
  assert.deepEqual(selector.props.options[0], { id: 'different-protocol', label: 'different-protocol', disabled: true, description: '目录已声明使用其他协议' });
  f.field('选择模型', 'different-protocol');
  assert.notEqual(f.find(node => node.type === 'SelectField' && node.props.label === '选择模型').props.value, 'different-protocol');
  f.field('选择模型', 'unknown-protocol'); f.click('保存服务'); await f.flush();
  assert.equal(f.calls.find(call => call.action === 'models.connections.add')?.args.model, 'unknown-protocol');
});
test('draft retention excludes credentials and app hiding clears the live secret', async () => {
  const f = fixture(); f.field('API 密钥', 'fixture-key');
  assert.equal(JSON.stringify(f.drafts).includes('fixture-key'), false);
  f.hide(); assert.equal(f.find(node => node.type === 'TextField' && node.props.label === 'API 密钥').props.value, '');
  assert.equal(f.calls.length, 0);
});
