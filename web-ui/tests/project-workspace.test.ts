import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import { adoptedRevisionSnapshot, revisionSourceSnapshot, fileWorkspaceKey, revisionWindowMatches, type RevisionAdoption } from '../src/project-file.ts';
const original: RevisionAdoption = { projectId: 'project-a', path: 'src/page.ts', version: 6, revision: 2, content: 'old content', draft: 'unsaved edit', editRevision: 3, documentEpoch: 4, resumeRevision: 5, navigationRevision: 8 };
const host = (change = {}) => ({ projectId: original.projectId, filePath: original.path, fileReady: true, fileVersion: original.version, revisionNumber: original.revision, revisionContent: original.content, fileContent: original.content, ...change });
test('file presentation state is keyed without project/path delimiter collisions', () => {
  assert.notEqual(fileWorkspaceKey('a:b', 'c'), fileWorkspaceKey('a', 'b:c'));
  assert.equal(fileWorkspaceKey('a', ''), ''); assert.equal(fileWorkspaceKey(1, 'p'), '');
});
test('revision adoption requires exact file, version, history body and ready state', () => {
  assert.equal(adoptedRevisionSnapshot(original, host()), true);
  for (const change of [{ projectId: 'other' }, { filePath: 'other' }, { fileReady: false }, { fileReady: 'true' }, { fileVersion: '6' }, { fileVersion: 7 }, { revisionNumber: 3 }, { revisionContent: 'other' }, { fileContent: 'other' }]) assert.equal(adoptedRevisionSnapshot(original, host(change)), false);
  for (const key of Object.keys(original) as (keyof RevisionAdoption)[]) assert.equal(revisionWindowMatches(original, { ...original, [key]: typeof original[key] === 'number' ? Number(original[key]) + 1 : original[key] + 'changed' }), false, key);
});
// Execute the production hook with explicit bridge/lifecycle doubles. No DOM/rendering claims.
function fixture() {
  const source = readFileSync(new URL('../src/useProjectRevision.ts', import.meta.url), 'utf8').replace(/^import .*;\n/gm, '').replace(/^export /gm, '');
  const code = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText;
  let index = 0, dirty = false, api: any, state: any = host({ fileContent: original.draft }), epoch = original.documentEpoch, resume = original.resumeRevision, localEdit = original.editRevision, localNavigation = original.navigationRevision;
  const slots: any[] = [], effects: (() => void)[] = [], listeners = new Set<() => void>(), visibility = new Set<() => void>(), timeouts = new Set<() => void>(), applied: RevisionAdoption[] = [], calls: any[] = [];
  const options: any = { key: fileWorkspaceKey(original.projectId, original.path), active: true, allowed: true, saving: false, current: { ...original }, localCurrent: () => ({ editRevision: localEdit, navigationRevision: localNavigation }), apply: (value: RevisionAdoption) => applied.push(value) };
  const useState = (initial: any) => { const i = index++; slots[i] ??= { value: initial }; return [slots[i].value, (next: any) => { if (!Object.is(next, slots[i].value)) { slots[i].value = next; dirty = true; } }]; };
  const useRef = (initial: any) => { const i = index++; return slots[i] ??= { current: initial }; };
  const useEffect = (effect: () => any, deps: any[]) => { const i = index++, prior = slots[i]; if (!prior || deps.some((v, j) => !Object.is(v, prior.deps[j]))) { const slot = slots[i] = { deps, cleanup: prior?.cleanup }; effects.push(() => { slot.cleanup?.(); slot.cleanup = effect(); }); } };
  const document = { hidden: false, addEventListener: (_: string, callback: () => void) => visibility.add(callback), removeEventListener: (_: string, callback: () => void) => visibility.delete(callback) };
  const bridge = { getDocumentEpoch: () => epoch, getState: () => ({ state, view: { resumeRevision: resume } }), subscribe: (callback: () => void) => { listeners.add(callback); return () => listeners.delete(callback); }, command: (action: string, args: any) => new Promise((resolve, reject) => calls.push({ action, args, resolve, reject })) };
  const window = { setTimeout: (callback: () => void) => { timeouts.add(callback); return callback; }, clearTimeout: (callback: () => void) => timeouts.delete(callback) };
  const run = new Function('useState', 'useRef', 'useEffect', 'bridge', 'document', 'window', 'adoptedRevisionSnapshot', 'revisionSourceSnapshot', 'revisionWindowMatches', code + ';return useProjectRevision;')(useState, useRef, useEffect, bridge, document, window, adoptedRevisionSnapshot, revisionSourceSnapshot, revisionWindowMatches);
  const render = () => { do { dirty = false; index = 0; api = run(options); effects.splice(0).forEach(effect => effect()); } while (dirty); };
  const flush = async () => { for (let i = 0; i < 8; i++) { await Promise.resolve(); if (dirty) render(); } };
  render();
  return { calls, applied, options, get api() { return api; }, render, flush,
    adopt() { api.adopt(); render(); },
    publish(next: any) { state = next; [...listeners].forEach(listener => listener()); render(); },
    change(change: any) { options.current = { ...options.current, ...change }; render(); },
    hide() { document.hidden = true; [...visibility].forEach(listener => listener()); render(); },
    retireWithoutRender() { document.hidden = true; [...visibility].forEach(listener => listener()); },
    resumeWithoutRender() { resume++; [...listeners].forEach(listener => listener()); },
    editWithoutRender() { localEdit++; },
    navigationWithoutRender() { localNavigation++; },
    documentChange() { epoch++; [...listeners].forEach(listener => listener()); render(); },
    timeout() { [...timeouts].forEach(callback => callback()); },
    async settle(receipt: any = { accepted: true }) { calls[0].resolve(receipt); await flush(); }
  };
}
test('production adoption waits for receipt AND native snapshot in either order; never saves', async () => {
  for (const stateFirst of [true, false]) {
    const f = fixture(); f.adopt(); f.adopt();
    assert.equal(f.calls.length, 1); assert.equal(f.api.holdsDraft(), true);
    assert.deepEqual(f.calls[0].args, { projectId: original.projectId, path: original.path, version: 6, revision: 2 });
    assert.equal(f.calls[0].action, 'projects.useRevision');
    if (stateFirst) { f.publish(host()); assert.equal(f.applied.length, 0); await f.settle(); }
    else { await f.settle(); assert.equal(f.applied.length, 0); f.publish(host()); await f.flush(); }
    assert.deepEqual(f.applied, [original]); assert.equal(f.api.pending, false); assert.equal(f.api.unknown, false); assert.match(f.api.message, /保存后/); assert.equal(f.calls.length, 1);
  }
});
test('late adoption cannot overwrite another draft, edit generation, navigation or source', async () => {
  for (const change of [{ draft: 'new local edit' }, { editRevision: 4 }, { navigationRevision: 9 }, { resumeRevision: 6 }, { revision: 3 }, { content: 'different revision body' }, { version: 7 }, { projectId: 'other' }]) {
    const f = fixture(); f.adopt(); f.change(change); f.publish(host()); await f.settle(); assert.equal(f.applied.length, 0); assert.equal(f.calls.length, 1); assert.equal(f.api.pending, false); assert.equal(f.api.unknown, true);
  }
});
test('hidden or replaced document retires the pending adoption without replay', async () => {
  for (const retire of ['hide', 'documentChange'] as const) { const f = fixture(); f.adopt(); f[retire](); f.publish(host()); await f.settle(); assert.equal(f.applied.length, 0); assert.equal(f.api.unknown, true); assert.equal(f.api.holdsDraft(), true); }
});
test('missing exact snapshot or malformed receipt keeps local draft and an explicit unknown result', async () => {
  const f = fixture(); f.adopt(); await f.settle(); f.publish(host({ revisionNumber: 3 })); f.timeout(); await f.flush(); assert.equal(f.applied.length, 0); assert.equal(f.api.unknown, true); assert.equal(f.api.holdsDraft(), true); f.adopt(); assert.equal(f.calls.length, 1);
  const malformed = fixture(); malformed.adopt(); malformed.publish(host()); await malformed.settle({ accepted: 'true' }); assert.equal(malformed.applied.length, 0); assert.equal(malformed.api.unknown, true);
});
test('project composition uses retained tabs and exact adapters without replacing the native preview', () => {
  const app = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');
  const editor = readFileSync(new URL('../src/ProjectFileEditor.tsx', import.meta.url), 'utf8');
  const preview = readFileSync(new URL('../src/ProjectPreviewPane.tsx', import.meta.url), 'utf8');
  const tree = readFileSync(new URL('../src/shell-library/adapted/tree/src/tree-view.tsx', import.meta.url), 'utf8');
  assert.match(app, /fileAdoption\.holdsDraft\(\)/); assert.match(app, /projectListTop\.current\.get\(String\(state\.projectId/);
  assert.match(app, /onSelectionChange=\{key=>\{if\(key==='preview'\)projectPreview\.open\(\);else projectPreview\.hide\(\)\}\}/);
  assert.match(app, /没有匹配的文件/); assert.match(app, /expandedIds=\{projectView\.expanded\}/);
  assert.match(editor, /Tabs, TabList, Tab, TabPanel, InlineDiff.*shell-library/); assert.match(editor, /替换当前编辑草稿/); assert.match(editor, /保存后才会生成新版本/); assert.match(editor, /registerBack\(back\)/);
  assert.match(tree, /TreeExpansionContext\.Provider/); assert.match(tree, /onKeyDown=\{event => handleTreeKeyDown/);
  assert.match(preview, /projects\.preview\.viewport/); assert.match(preview, /previewReturned\(value, originalView/); assert.doesNotMatch(preview, /<iframe|srcDoc=/);
});

test('retirement holds the original draft synchronously before React commits unknown state', async () => {
  const f = fixture(); f.adopt(); f.retireWithoutRender();
  assert.equal(f.api.holdsDraft(), true, 'native state autosync in the same effect flush must not replace a clean draft');
  f.adopt(); assert.equal(f.calls.length, 1, 'same-flush click cannot replay the retired adoption');
  f.publish(host()); await f.settle(); assert.equal(f.applied.length, 0);
});

test('latest native resume and synchronous local edits/navigation retire even before React renders', async () => {
  for (const change of ['resumeWithoutRender', 'editWithoutRender', 'navigationWithoutRender'] as const) {
    const f = fixture(); f.adopt(); f[change](); f.publish(host()); await f.settle();
    assert.equal(f.applied.length, 0); assert.equal(f.api.unknown, true); assert.equal(f.api.holdsDraft(), true);
  }
});

test('an event from a stale render cannot start adoption against a new native or local window', () => {
  for (const change of ['resumeWithoutRender', 'editWithoutRender', 'navigationWithoutRender'] as const) { const f = fixture(); f[change](); f.adopt(); assert.equal(f.calls.length, 0); }
  const f = fixture(); f.publish(host({ revisionContent: 'changed history' })); f.adopt(); assert.equal(f.calls.length, 0);
});
function editorFixture() {
  const source = readFileSync(new URL('../src/ProjectFileEditor.tsx', import.meta.url), 'utf8').replace(/^import .*;\n/gm, '').replace(/^export /gm, '');
  const code = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
  const slots: any[] = [], effects: (() => void)[] = []; let index = 0, dirty = false, tree: any, back: any;
  const events: any[] = [];
  const props: any = { path: original.path, version: 6, draft: { text: original.draft, baseline: 'saved', version: 6, dirty: true }, state: { fileReady: true, fileHistory: [{ version: 2, at: 1000 }], revisionNumber: 2, revisionContent: original.content }, conflict: false, mode: 'edit', can: () => true, edit: (value: string) => events.push(['edit', value]), save: () => events.push(['save']), readHistory: () => events.push(['history']), readRevision: (revision: number) => events.push(['revision', revision]), retry: () => events.push(['retry']), copy: () => events.push(['copy']), adoption: { pending: false, unknown: false, message: '', adopt: () => events.push(['adopt']) }, registerBack: (value: any) => { back = value; }, setMode: (mode: string) => { props.mode = mode; } };
  const useState = (initial: any) => { const i = index++; slots[i] ??= { value: initial }; return [slots[i].value, (value: any) => { if (!Object.is(value, slots[i].value)) { slots[i].value = value; dirty = true; } }]; };
  const useRef = (initial: any) => { const i = index++; return slots[i] ??= { current: initial }; };
  const useEffect = (effect: () => any, deps: any[]) => { const i = index++, prior = slots[i]; if (!prior || deps.some((v, j) => !Object.is(v, prior.deps[j]))) { const slot = slots[i] = { deps, cleanup: prior?.cleanup }; effects.push(() => { slot.cleanup?.(); slot.cleanup = effect(); }); } };
  const React = { Fragment: 'Fragment', createElement: (type: any, attributes: any, ...children: any[]) => ({ type, props: attributes || {}, children: children.flat(Infinity).filter(child => child !== null && child !== undefined && child !== false) }) };
  const dependencies: Record<string, any> = { React, useState, useRef, useEffect, requestAnimationFrame: (callback: () => void) => { callback(); return 1; }, cancelAnimationFrame: () => {} };
  for (const name of ['Button', 'TextAreaField', 'Tabs', 'TabList', 'Tab', 'TabPanel', 'InlineDiff', 'ProgressBar', 'Skeleton', 'ShimmerText', 'RiArrowLeftLine', 'RiFileCopyLine', 'RiRefreshLine']) dependencies[name] = name;
  const run = new Function(...Object.keys(dependencies), code + ';return ProjectFileEditor;')(...Object.values(dependencies));
  const render = () => { do { dirty = false; index = 0; tree = run(props); effects.splice(0).forEach(effect => effect()); } while (dirty); };
  const nodes = (value: any): any[] => value && typeof value === 'object' ? [value, ...value.children.flatMap(nodes)] : [];
  const label = (value: any): string => value && typeof value === 'object' ? value.children.map(label).join('') : String(value ?? '');
  const find = (predicate: (value: any) => boolean) => { const found = nodes(tree).find(predicate); assert.ok(found, 'expected production editor element'); return found; };
  render();
  return { props, events, render, find, get back() { return back; }, click(name: string) { const node = find(node => node.type === 'Button' && label(node) === name); assert.ok(!node.props.disabled); node.props.onClick(); render(); }, mode(value: string) { find(node => node.type === 'Tabs').props.onSelectionChange(value); render(); } };
}
test('editor history is a separate layer and replacing a dirty draft requires explicit review', () => {
  const f = editorFixture(); f.mode('history'); assert.deepEqual(f.events, [['history']]);
  const row = f.find(node => node.props['data-revision'] === 2); row.props.onClick(); f.render(); assert.deepEqual(f.events.at(-1), ['revision', 2]);
  f.click('替换编辑草稿…'); assert.equal(f.events.some(event => event[0] === 'adopt'), false);
  f.click('复制当前草稿'); assert.deepEqual(f.events.at(-1), ['copy']);
  f.click('采用到编辑草稿'); assert.deepEqual(f.events.at(-1), ['adopt']); assert.equal(f.events.some(event => event[0] === 'save'), false);
});
test('pending adoption blocks file tabs, local Back, editor typing and saving controls', () => {
  const f = editorFixture(); f.props.adoption.pending = true; f.render();
  for (const id of ['edit', 'diff', 'history']) assert.equal(f.find(node => node.type === 'Tab' && node.props.id === id).props.isDisabled, true);
  assert.equal(f.find(node => node.type === 'TextAreaField').props.isReadOnly, true);
  f.mode('history'); assert.equal(f.props.mode, 'edit'); assert.equal(f.back(), true);
  assert.equal(f.find(node => node.type === 'Button' && node.props.onClick === f.props.save).props.disabled, true);
});
test('history preview cannot offer adoption for another or still-loading revision', () => {
  for (const state of [{ revisionNumber: 3 }, { revisionLoading: true }]) {
    const f = editorFixture(); Object.assign(f.props.state, state); f.mode('history'); f.find(node => node.props['data-revision'] === 2).props.onClick(); f.render();
    assert.throws(() => f.click('替换编辑草稿…'));
  }
});
