import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import { aiApiCandidates, aiConnections, aiObject, aiRows, aiText } from '../src/ai-center-data.ts';
import { apiModelProjectionCurrent, apiModelRows } from '../src/composer-api-models.ts';

// Execute the production editor and its bridge payloads. The native group
// admission tests separately exercise these explicit refs in SettingsStore.
type Data = Record<string, any>;
const source = (id: string) => ({ id, name: id, revision: 1, provider: 'openai_chat', enabled: true });
const candidate = (id: string, connectionId: string, enabled = true): Data => ({
  candidateId: id, target: { kind: 'exact', connectionId, modelId: id }, enabled,
  allowedPurposes: ['MAIN'], allowedAudiences: ['APP_OWNER'],
});
const group = (candidates: Data[], refs: string[]): Data => ({
  groupId: 'group-original', revision: 3, name: '原模型组', candidates,
  allowedAccounts: { mode: 'EXPLICIT', refs }, attemptPolicy: { maxAttempts: 1, maxRouteSwitches: 0 },
});
function fixture(original?: Data, saveFailure = false) {
  const ast = ts.createSourceFile('AIModelGroups.tsx', readFileSync(new URL('../src/AIModelGroups.tsx', import.meta.url), 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const body = ast.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(ast).replace(/^export /, '')).join('\n');
  const code = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
  const slots: any[] = [], effects: (() => void)[] = [], calls: Data[] = [];
  let index = 0, dirty = false, tree: any, back: (() => boolean) | null = null;
  let groups = original ? [structuredClone(original)] : [];
  const useState = (initial: any) => { const i = index++; slots[i] ??= { value: initial }; return [slots[i].value, (next: any) => { const value = typeof next === 'function' ? next(slots[i].value) : next; if (!Object.is(value, slots[i].value)) { slots[i].value = value; dirty = true; } }]; };
  const useRef = (initial: any) => { const i = index++; return slots[i] ??= { current: initial }; };
  const useEffect = (effect: () => any, deps?: any[]) => { const i = index++, old = slots[i]; if (!old || !deps || deps.some((value, at) => !Object.is(value, old.deps[at]))) { const slot = slots[i] = { deps, cleanup: old?.cleanup }; effects.push(() => { slot.cleanup?.(); slot.cleanup = effect(); }); } };
  const React = { Fragment: 'Fragment', createElement: (type: any, props: Data | null, ...children: any[]) => ({ type, props: props || {},
    children: (type === 'SortableList' ? props!.items.map(props!.renderItem) : children).flat(Infinity).filter(child => child !== false && child !== null && child !== undefined) }) };
  const dependencies: Data = { React, useState, useRef, useEffect, crypto, aiApiCandidates, aiConnections, object: aiObject, rows: aiRows, text: aiText, apiModelProjectionCurrent, apiModelRows };
  for (const key of ['ChevronRight', 'Plus', 'Trash2', 'Accordion', 'AccordionItem', 'Button', 'SelectField', 'SettingsDialog', 'StretchSwitch', 'TextField', 'SortableList']) dependencies[key] = key;
  const Component = new Function(...Object.keys(dependencies), code + '\nreturn AIModelGroups;')(...Object.values(dependencies));
  const snapshot = () => ({ format: 'pocketworld.model-groups.v1', groups: structuredClone(groups), purposeBindings: {} });
  const props = { state: { modelSelectionRevision: 5 }, data: { connections: ['source-a', 'source-b', 'source-hidden'].map(source), modelGroups: snapshot() }, supported: () => true,
    registerTransientBack: (handler: (() => boolean) | null) => { back = handler; },
    run: async (action: string, args: Data = {}) => {
      calls.push({ action, args: structuredClone(args) });
      if (action === 'models.groups.snapshot') return snapshot();
      if (action === 'models.api.open') return { windowId: 'picker-window', format: 'pocketworld.model-picker.v1', selectionRevision: props.state.modelSelectionRevision, viewGeneration: 1,
        models: [['source-a', 'a-first'], ['source-a', 'a-second'], ['source-b', 'b-first'], ['source-hidden', 'hidden-model']].map(([connectionId, modelId]) => ({ connectionId, modelId, connectionRevision: 1, available: true, favorite: false })) };
      if (action === 'models.groups.save') {
        if (saveFailure) throw new Error('模型组已变化，请重新打开原记录');
        const saved = { groupId: args.id || 'group-created', revision: args.expectedRevision + 1, name: args.name, ...args.definition };
        groups = [saved]; return { status: 'saved', snapshot: { modelGroups: snapshot() } };
      }
      return {};
    },
  };
  const content = (node: any): string => !node || typeof node !== 'object' ? String(node ?? '') : node.children.map(content).join('');
  const nodes = (node: any): any[] => !node || typeof node !== 'object' || node.type === 'SettingsDialog' && !node.props.isOpen ? [] : [node, ...node.children.flatMap(nodes)];
  const render = () => { let rounds = 0; do { assert.ok(rounds++ < 30); dirty = false; index = 0; tree = Component(props); effects.splice(0).forEach(effect => effect()); } while (dirty); };
  const find = (predicate: (node: any) => boolean) => { const node = nodes(tree).find(predicate); assert.ok(node, 'expected production model group control'); return node; };
  const button = (label: string) => find(node => node.type === 'Button' && (content(node) === label || node.props['aria-label'] === label));
  const click = (label: string) => { const node = button(label); assert.ok(!node.props.disabled, `disabled: ${label}`); node.props.onClick(); render(); };
  render();
  return { props, calls, button, click, render, find,
    field(label: string, value: string) { find(node => ['TextField', 'SelectField'].includes(node.type) && node.props.label === label).props.onChange(value); render(); },
    add(connection: string, model: string) { this.field('添加候选模型', JSON.stringify([connection, model])); click('添加'); },
    edit() { const row = find(node => node.type === 'Button' && node.props.className === 'pw-ai-service-row'); row.props.onClick(); render(); },
    back() { const consumed = back?.(); render(); return consumed; },
    get text() { return content(tree); },
    get saves() { return calls.filter(call => call.action === 'models.groups.save'); },
    async flush() { for (let i = 0; i < 20; i++) { await Promise.resolve(); if (dirty) render(); } },
  };
}

test('a new group authorizes exactly the explicitly added API sources', async () => {
  const f = fixture(); await f.flush(); f.click('新建'); await f.flush();
  f.field('模型组名称', '日常任务'); f.add('source-a', 'a-first'); f.add('source-b', 'b-first');
  f.click('保存模型组'); await f.flush();
  assert.equal(f.saves.length, 1);
  const saved = f.saves[0].args;
  assert.deepEqual(saved.definition.allowedAccounts, { mode: 'EXPLICIT', refs: ['source-a', 'source-b'] });
  assert.equal(saved.id, ''); assert.equal(saved.expectedRevision, 0);
  assert.deepEqual(saved.definition.candidates.map((item: Data) => item.target.connectionId), ['source-a', 'source-b']);
  assert.equal(f.calls.some(call => call.action === 'models.groups.select'), false, 'saving does not switch the default');
});

test('editing a group includes its newly added source and removes unrelated retained refs', async () => {
  const original = group([candidate('a-first', 'source-a')], ['source-a', 'source-hidden']);
  const f = fixture(original); await f.flush(); f.edit(); await f.flush(); f.add('source-b', 'b-first');
  f.click('保存模型组'); await f.flush();
  const saved = f.saves[0].args;
  assert.equal(saved.id, 'group-original'); assert.equal(saved.expectedRevision, 3);
  assert.deepEqual(saved.definition.allowedAccounts.refs, ['source-a', 'source-b']);
  assert.deepEqual(original.allowedAccounts.refs, ['source-a', 'source-hidden'], 'editing does not mutate the input snapshot');
});

test('saving an existing group repairs the formerly empty source list from its visible candidates', async () => {
  const f = fixture(group([candidate('a-first', 'source-a')], [])); await f.flush(); f.edit(); await f.flush();
  f.click('保存模型组'); await f.flush();
  assert.deepEqual(f.saves[0].args.definition.allowedAccounts.refs, ['source-a']);
});

test('multiple models from one source authorize it once and removing its last model revokes that source', async () => {
  const f = fixture(group([candidate('a-first', 'source-a'), candidate('a-second', 'source-a'), candidate('b-first', 'source-b')], ['source-a', 'source-b']));
  await f.flush(); f.edit(); await f.flush(); f.click('保存模型组'); await f.flush();
  assert.deepEqual(f.saves[0].args.definition.allowedAccounts.refs, ['source-a', 'source-b']);
  f.edit(); await f.flush(); f.click('移除候选 a-first'); f.click('保存模型组'); await f.flush();
  assert.deepEqual(f.saves[1].args.definition.allowedAccounts.refs, ['source-a', 'source-b']);
  f.edit(); await f.flush(); f.click('移除候选 a-second'); f.click('保存模型组'); await f.flush();
  assert.deepEqual(f.saves[2].args.definition.allowedAccounts.refs, ['source-b']);
});

test('disabled candidates retain their configuration without authorizing their otherwise unused source', async () => {
  const f = fixture(group([candidate('a-first', 'source-a'), candidate('a-disabled', 'source-a', false), candidate('b-disabled', 'source-b', false)], ['source-a', 'source-b']));
  await f.flush(); f.edit(); await f.flush(); f.click('保存模型组'); await f.flush();
  const definition = f.saves[0].args.definition;
  assert.deepEqual(definition.allowedAccounts.refs, ['source-a']);
  assert.deepEqual(definition.candidates.map((item: Data) => item.enabled), [true, false, false]);
});

test('cancel and Back discard candidate changes without saving or adding authorization', async () => {
  const f = fixture(group([candidate('a-first', 'source-a')], ['source-a'])); await f.flush();
  f.edit(); await f.flush(); f.add('source-b', 'b-first'); f.click('取消');
  f.edit(); await f.flush(); assert.equal(f.find(node => node.type === 'SortableList').props.items.length, 1);
  f.add('source-b', 'b-first'); assert.equal(f.back(), true);
  assert.equal(f.saves.length, 0);
  f.edit(); await f.flush(); f.click('保存模型组'); await f.flush();
  assert.deepEqual(f.saves[0].args.definition.allowedAccounts.refs, ['source-a']);
});

test('a changed selection revision blocks saving the open candidate authorization', async () => {
  const f = fixture(group([candidate('a-first', 'source-a')], ['source-a'])); await f.flush(); f.edit(); await f.flush();
  f.add('source-b', 'b-first'); f.props.state.modelSelectionRevision++; f.render();
  assert.equal(f.button('保存模型组').props.disabled, true);
  assert.match(f.text, /服务或模型组已在其他位置变化/); assert.equal(f.saves.length, 0);
});

test('native revision rejection keeps the save outcome unknown without retrying or selecting', async () => {
  const f = fixture(group([candidate('a-first', 'source-a')], ['source-a']), true); await f.flush(); f.edit(); await f.flush();
  f.add('source-b', 'b-first'); f.click('保存模型组'); await f.flush();
  assert.equal(f.saves.length, 1); assert.equal(f.saves[0].args.expectedRevision, 3);
  assert.equal(f.button('保存模型组').props.disabled, true);
  assert.match(f.text, /模型组已变化/); assert.equal(f.calls.some(call => call.action === 'models.groups.select'), false);
});
