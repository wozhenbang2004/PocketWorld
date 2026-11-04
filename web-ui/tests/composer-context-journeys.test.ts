import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';

// Executes production handlers against hook/control/native-receipt doubles.
// This does not render a page or prove visual, focus, touch, scroll or Android behavior.
type Data = Record<string, any>;
type Kind = 'source' | 'preview' | 'voice';
const originalSource = { generation: 'source-generation', sourceConversationId: 'conversation-original', sourceRef: { kind: 'message', sourceId: 'message-original', revision: 4, ownerId: 'owner', spaceId: 'private' }, bodyFingerprint: 'sha256:original-body', offset: 3, chars: 30, unit: 'UTF-16' };
const originalPreview = { referenceId: 'reference-original', previewId: 'preview-original', documentGeneration: 7, path: 'index.html', text: '原元素文字', htmlSnippet: '<img src=x onerror="untrustedExecuted=true">', selectorHint: '#original', selectorIsSourceLocation: false, untrusted: true, checkoutId: 'checkout-original', sourceDigest: 'digest-original', sourceKind: 'fixed_workspace_project_artifact', artifactId: 'artifact-original', artifactSha256: 'archive-original', packageOperationId: 'package-original' };
const originalComposer = { key: 'draft-original', epoch: 5, revision: 8, text: '这份输入必须保留', sourceRefs: [originalSource, { ...originalSource, offset: 45 }], previewRefs: [originalPreview, { ...originalPreview, referenceId: 'reference-second', path: 'other.html' }], previewRefsAvailable: true };
const originalHandoff = { handoffId: 'handoff-original', taskId: 'task-original', note: '原说明\n必须保留', closed: false, saving: false, saveAttempted: false };
const originalInput = { inputId: 'input-original', target: { conversationId: 'quick-panel:handoff-original' }, active: false, reviewAvailable: true, finalText: '原听写文字', resourceReleaseUnknown: false, phase: 'review', message: '先核对后追加' };
function deferred() { let resolve!: (value: any) => void, reject!: (value: any) => void; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }

function fixture(kind: Kind, responses: Data = {}, inputProps: Data = {}) {
  let slots: any[] = [], index = 0, dirty = false, tree: any, epoch = 1, revision = 1;
  const host: Data = { systemSpeech: { recognitionAvailable: true, microphoneGranted: true }, notice: '', error: '' };
  const effects: (() => void)[] = [], subscribers = new Set<() => void>(), calls: Data[] = [], expanded = new Set<string>();
  const useState = (initial: any) => { const i = index++; slots[i] ??= { value: typeof initial === 'function' ? initial() : initial }; return [slots[i].value, (next: any) => { const value = typeof next === 'function' ? next(slots[i].value) : next; if (!Object.is(slots[i].value, value)) { slots[i].value = value; dirty = true; } }]; };
  const useRef = (initial: any) => { const i = index++; return slots[i] ??= { current: initial }; };
  const useEffect = (callback: () => any, deps?: any[]) => { const i = index++, previous = slots[i]; if (!deps || !previous?.deps || deps.some((value, j) => !Object.is(value, previous.deps[j]))) { const slot = slots[i] = { deps, cleanup: previous?.cleanup }; effects.push(() => { slot.cleanup?.(); slot.cleanup = callback(); }); } };
  const React: Data = { Fragment: 'Fragment', createElement: (type: any, props: any, ...children: any[]) => ({ type, props: props || {}, children: children.flat(Infinity).filter(child => child !== false && child !== null && child !== undefined) }) };
  const values: Data = { 'composer.source.remove': { removed: true }, 'composer.preview.remove': { removed: true }, ...responses };
  const run = async (action: string, args: Data = {}) => { calls.push({ action, args }); const value = values[action]; return typeof value === 'function' ? value(args) : value; };
  const props: Data = { composer: structuredClone(originalComposer), disabled: false, handoff: { ...originalHandoff }, input: structuredClone(originalInput), run, ...inputProps };
  const dependencies: Data = { React, useState, useRef, useEffect, bridge: { getDocumentEpoch: () => epoch, getState: () => ({ revision, composer: props.composer, state: { quickPanelVoice: props.handoff, voiceInput: props.input, ...host } }), subscribe: (fn: () => void) => { subscribers.add(fn); return () => subscribers.delete(fn); }, command: run }, previewReferenceMessage: (available: any) => available === true ? '发送时会重新核对预览和真实源码。' : '原预览已变化或不可用，请重新选择；保留的片段不能直接发送。' };
  for (const name of ['Accordion', 'AccordionItem', 'Button', 'SettingsDialog', 'TextAreaField']) dependencies[name] = name;
  const load = (name: string, exports: string) => {
    const source = readFileSync(new URL(`../src/${name}.tsx`, import.meta.url), 'utf8');
    const ast = ts.createSourceFile(name + '.tsx', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
    const body = ast.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(ast).replace(/^export /, '')).join('\n');
    const code = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
    return new Function(...Object.keys(dependencies), code + `\nreturn { ${exports} };`)(...Object.values(dependencies));
  };
  const source = load('ComposerSourceReferences', 'ComposerSourceReferences, useComposerReferenceRemoval');
  dependencies.useComposerReferenceRemoval = source.useComposerReferenceRemoval;
  const Component = kind === 'source' ? source.ComposerSourceReferences : kind === 'preview' ? load('ComposerPreviewReferences', 'ComposerPreviewReferences').ComposerPreviewReferences : load('QuickPanelVoiceDraft', 'QuickPanelVoiceDraft').QuickPanelVoiceDraft;
  const hidden = (node: any) => !!node.props.hidden || node.type === 'SettingsDialog' && !node.props.isOpen;
  const walk = (node: any, inactive = false): any[] => !node || typeof node !== 'object' ? [] : [{ ...node, visible: !inactive && !hidden(node) }, ...node.children.flatMap((child: any) => walk(child, inactive || hidden(node) || node.type === 'AccordionItem' && !expanded.has(node.props.id)))];
  const content = (node: any, visible = false): string => !node || typeof node !== 'object' ? String(node ?? '') : visible && hidden(node) ? '' : node.type === 'AccordionItem' ? content(node.props.title) + content(node.props.description) + (!visible || expanded.has(node.props.id) ? node.children.map((child: any) => content(child, visible)).join('') : '') : node.children.map((child: any) => content(child, visible)).join('');
  const render = () => { let rounds = 0; do { assert.ok(rounds++ < 30, 'production hooks settle'); dirty = false; index = 0; tree = Component(props); effects.splice(0).forEach(fn => fn()); } while (dirty); };
  const find = (predicate: (node: any) => boolean, visible = true) => { const node = walk(tree).filter(node => (!visible || node.visible) && predicate(node)).at(-1); assert.ok(node, 'expected production control'); return node; };
  const button = (label: string) => find(node => node.type === 'Button' && (node.props['aria-label'] === label || content(node) === label));
  render();
  return { props, values, calls, render, find, button,
    get text() { return content(tree, true); },
    get nodes() { return walk(tree); },
    click(label: string) { const node = button(label); assert.ok(!node.props.disabled, label + ' enabled'); node.props.onClick(); render(); },
    expand(id: string) { expanded.add(id); render(); }, collapse(id: string) { expanded.delete(id); render(); },
    expandObject(index = 0) { const node = walk(tree).filter(node => node.type === 'AccordionItem' && node.props.headingLevel === 4)[index]; assert.ok(node); expanded.add(node.props.id); render(); },
    field(value: string) { const node = find(node => node.type === 'TextAreaField'); assert.ok(!node.props.isDisabled); node.props.onChange(value); render(); },
    get draft() { return find(node => node.type === 'TextAreaField', false).props.value; },
    update(next: Data) { Object.assign(props, next); render(); },
    publish(next: Data, renderNow = true) { Object.assign(host, next); revision++; for (const fn of [...subscribers]) fn(); if (renderNow) render(); },
    rotate() { epoch++; for (const fn of [...subscribers]) fn(); render(); },
    unmount() { for (const slot of slots) slot?.cleanup?.(); },
    async flush() { for (let i = 0; i < 16; i++) { await Promise.resolve(); if (dirty) render(); } },
  };
}
const count = (f: ReturnType<typeof fixture>, action: string) => f.calls.filter(call => call.action === action).length;
const last = (f: ReturnType<typeof fixture>, action: string) => f.calls.filter(call => call.action === action).at(-1)?.args;

test('source disclosure keeps exact ordered metadata and composer text without rereading or removing', () => {
  const f = fixture('source'), original = structuredClone(f.props.composer);
  assert.match(f.text, /原文引用 · 2/); assert.doesNotMatch(f.text, /message-original|sha256:original-body/);
  f.expand('source-references'); assert.match(f.text, /未读取正文/); f.expandObject();
  assert.match(f.text, /message-original/); assert.match(f.text, /3–33 · UTF-16/); assert.match(f.text, /sha256:original-body/);
  f.collapse('source-references'); f.expand('source-references'); assert.deepEqual(f.props.composer, original); assert.equal(f.calls.length, 0);
  assert.equal(f.nodes.some(node => node.type === 'details'), false);
});

test('preview keeps unavailable state outside disclosure and renders original untrusted fragments as text', () => {
  const f = fixture('preview'); f.update({ composer: { ...f.props.composer, previewRefsAvailable: false } });
  assert.match(f.text, /原预览已变化或不可用/); assert.doesNotMatch(f.text, /archive-original/);
  f.expand('preview-references'); f.expandObject(); assert.match(f.text, /原元素文字/); assert.match(f.text, /原任务固定项目包/); assert.match(f.text, /archive-original/); assert.match(f.text, /不是源码行号/);
  assert.equal(f.nodes.some(node => node.type === 'img' || node.props.dangerouslySetInnerHTML), false);
  assert.equal(f.find(node => node.type === 'pre').children[0], originalPreview.htmlSnippet);
  f.collapse('preview-references'); assert.match(f.text, /原预览已变化或不可用/); assert.equal(f.calls.length, 0);
});

for (const kind of ['source', 'preview'] as const) {
  const action = `composer.${kind}.remove`, label = kind === 'source' ? '原文' : '元素';
  test(`${kind} removal uses original index and draft CAS once, waits for native snapshot`, async () => {
    const wait = deferred(), f = fixture(kind, { [action]: () => wait.promise });
    f.expand(`${kind}-references`); const click = f.button(`移除${label}引用 2`).props.onClick;
    click(); click(); f.render(); assert.equal(count(f, action), 1);
    assert.deepEqual(last(f, action), { key: 'draft-original', epoch: 5, revision: 8, index: 1 });
    wait.resolve({ removed: true }); await f.flush(); click(); assert.equal(count(f, action), 1); assert.match(f.text, /正在核对草稿/);
    const field = kind === 'source' ? 'sourceRefs' : 'previewRefs';
    f.update({ composer: { ...f.props.composer, revision: 9, [field]: [f.props.composer[field][0]] } });
    assert.equal(f.button(`移除${label}引用 1`).props.disabled, false); assert.equal(f.props.composer.text, originalComposer.text);
  });
  test(`${kind} unknown removal remains visible when closed and cannot replay`, async () => {
    const f = fixture(kind, { [action]: () => { throw Object.assign(new Error('原移除回执未知'), { code: 'outcome_unknown' }); } });
    f.expand(`${kind}-references`); const old = f.button(`移除${label}引用 1`).props.onClick; f.click(`移除${label}引用 1`); await f.flush();
    f.collapse(`${kind}-references`); assert.match(f.text, /原移除回执未知/); assert.match(f.text, /暂不再次移除/); old(); await f.flush(); assert.equal(count(f, action), 1);
    assert.equal(f.props.composer.sourceRefs.length, 2); assert.equal(f.props.composer.previewRefs.length, 2);
  });
  test(`${kind} changed draft fences stale handlers and late failures without blocking the new draft`, async () => {
    const wait = deferred(), f = fixture(kind, { [action]: () => wait.promise });
    f.expand(`${kind}-references`); const old = f.button(`移除${label}引用 1`).props.onClick; f.click(`移除${label}引用 1`);
    f.update({ composer: { ...f.props.composer, key: 'new-draft', epoch: 6, revision: 1 } }); old(); assert.equal(count(f, action), 1);
    wait.reject(new Error('旧草稿迟到错误')); await f.flush(); assert.doesNotMatch(f.text, /旧草稿迟到错误/); assert.equal(f.button(`移除${label}引用 1`).props.disabled, false);
    f.values[action] = { removed: true }; f.click(`移除${label}引用 2`); await f.flush(); assert.deepEqual(last(f, action), { key: 'new-draft', epoch: 6, revision: 1, index: 1 });
  });
  test(`${kind} document rotation exposes uncertainty and fences previous click callbacks`, async () => {
    const wait = deferred(), f = fixture(kind, { [action]: () => wait.promise });
    f.expand(`${kind}-references`); const old = f.button(`移除${label}引用 1`).props.onClick; f.click(`移除${label}引用 1`); f.rotate(); old();
    f.collapse(`${kind}-references`); assert.match(f.text, /结果尚未核对/); wait.resolve({ removed: true }); await f.flush(); assert.match(f.text, /暂不再次移除/); assert.equal(count(f, action), 1);
  });
}

test('missing removal acknowledgement is not success or permission to retry', async () => {
  const f = fixture('source', { 'composer.source.remove': {} }); f.expand('source-references'); const old = f.button('移除原文引用 1').props.onClick; f.click('移除原文引用 1'); await f.flush(); old();
  assert.equal(count(f, 'composer.source.remove'), 1); assert.match(f.text, /尚未收到确定的移除回执/); assert.equal(f.props.composer.sourceRefs.length, 2);
});

test('voice capture and stop preserve the native handoff and never submit a task', async () => {
  const start = deferred(), f = fixture('voice', { 'quickVoice.start': () => start.promise }, { input: { active: false, reviewAvailable: false, phase: 'idle' } });
  assert.match(f.text, /补充原浮层说明/); assert.doesNotMatch(f.text, /原说明\n必须保留/); f.expand('voice-original-note'); assert.match(f.text, /原说明\n必须保留/); assert.equal(f.calls.length, 0);
  const click = f.button('开始系统听写').props.onClick; click(); click(); f.render(); assert.equal(count(f, 'quickVoice.start'), 1); assert.deepEqual(last(f, 'quickVoice.start'), { handoffId: 'handoff-original' });
  f.update({ input: { ...originalInput, active: true, reviewAvailable: false, phase: 'listening', finalText: '' } }); start.resolve({}); await f.flush(); assert.match(f.text, /正在听写/);
  const stop = f.button('结束本次听写').props.onClick; stop(); stop(); f.render(); await f.flush(); assert.equal(count(f, 'voice.stop'), 1); assert.deepEqual(last(f, 'voice.stop'), {});
  f.update({ input: { ...f.props.input, phase: 'stopping' } }); assert.equal(f.button('等待音频释放').props.disabled, true); assert.equal(count(f, 'quickVoice.save'), 0);
});

test('voice review edits survive disclosure and a cancelled close, then append exactly once', async () => {
  const save = deferred(), f = fixture('voice', { 'quickVoice.save': () => save.promise });
  f.field('我修改后的最终文字'); f.expand('voice-original-note'); f.collapse('voice-original-note');
  f.click('关闭听写'); assert.match(f.text, /舍弃尚未追加/); assert.equal(count(f, 'quickVoice.close'), 0);
  f.click('返回核对'); assert.equal(f.draft, '我修改后的最终文字');
  const click = f.button('追加到原浮层草稿').props.onClick; click(); click(); f.render(); assert.equal(count(f, 'quickVoice.save'), 1);
  assert.deepEqual(last(f, 'quickVoice.save'), { handoffId: 'handoff-original', inputId: 'input-original', text: '我修改后的最终文字' });
  save.resolve({ saved: true }); await f.flush(); assert.doesNotMatch(f.text, /本次文字已保存在/); assert.match(f.text, /追加结果待核对/);
  f.update({ handoff: { ...originalHandoff, saveAttempted: true, receipt: { saved: true, taskId: 'task-original', runId: 'run-original', epoch: 7, previousRevision: 3, draftRevision: 4, requiresEditingBeforeSend: true } } });
  assert.match(f.text, /已追加到原草稿/); assert.match(f.text, /超过1500字/); assert.doesNotMatch(f.text, /追加到原浮层草稿/);
  f.expand('voice-save-receipt'); assert.match(f.text, /run-original/); assert.match(f.text, /3 → 4/); f.click('完成'); await f.flush(); assert.equal(count(f, 'quickVoice.close'), 1);
});

test('closing an unsaved review needs the explicit discard action and preserves original note', async () => {
  const f = fixture('voice'); f.field('尚未追加的修改'); f.find(node => node.type === 'SettingsDialog').props.onClose(); f.render();
  assert.equal(count(f, 'quickVoice.close'), 0); f.click('返回核对'); assert.equal(f.draft, '尚未追加的修改');
  f.click('关闭听写'); f.click('舍弃本次听写并关闭'); await f.flush(); assert.equal(count(f, 'quickVoice.close'), 1); assert.equal(count(f, 'quickVoice.save'), 0); assert.equal(f.props.handoff.note, originalHandoff.note);
});

test('unknown save retains edited text, exposes uncertainty and does not replay', async () => {
  const f = fixture('voice', { 'quickVoice.save': () => { throw Object.assign(new Error('保存回执未知'), { code: 'outcome_unknown' }); } });
  f.field('仍需核对的本次文字'); const old = f.button('追加到原浮层草稿').props.onClick; f.click('追加到原浮层草稿'); await f.flush(); old(); await f.flush();
  assert.equal(count(f, 'quickVoice.save'), 1); assert.equal(f.draft, '仍需核对的本次文字'); assert.match(f.text, /保存回执未知/); assert.match(f.text, /不会重复保存/); assert.doesNotMatch(f.text, /本次文字已保存在/);
  f.click('关闭听写'); assert.match(f.text, /追加结果请回原浮层核对/); f.click('返回核对'); assert.equal(f.draft, '仍需核对的本次文字');
});

test('late old handoff failures and stale save callbacks cannot affect a new handoff', async () => {
  const wait = deferred(), f = fixture('voice', { 'quickVoice.save': () => wait.promise });
  const old = f.button('追加到原浮层草稿').props.onClick; f.click('追加到原浮层草稿');
  f.update({ handoff: { ...originalHandoff, handoffId: 'handoff-new' }, input: { ...originalInput, inputId: 'input-new', target: { conversationId: 'quick-panel:handoff-new' }, finalText: '新听写文字' } });
  old(); wait.reject(new Error('旧交接迟到错误')); await f.flush(); assert.equal(count(f, 'quickVoice.save'), 1); assert.equal(f.draft, '新听写文字'); assert.doesNotMatch(f.text, /旧交接迟到错误/);
  assert.equal(f.button('追加到原浮层草稿').props.disabled, false);
});

test('other input identity cannot be stopped or saved through this handoff', () => {
  const f = fixture('voice', {}, { input: { ...originalInput, active: true, reviewAvailable: false, phase: 'listening' } });
  const old = f.button('结束本次听写').props.onClick;
  f.update({ input: { ...originalInput, inputId: 'input-other', target: { conversationId: 'conversation-other' }, active: true, reviewAvailable: false } }); old();
  assert.match(f.text, /另一份听写尚未处理/); assert.equal(f.calls.length, 0); assert.equal(f.nodes.some(node => node.type === 'Button' && node.visible && node.children.includes('开始系统听写')), false);
});

test('resource-release unknown blocks capture and append without claiming it can reopen', () => {
  const f = fixture('voice', {}, { input: { ...originalInput, resourceReleaseUnknown: true } });
  assert.match(f.text, /音频资源释放尚未确认/); assert.equal(f.button('追加到原浮层草稿').props.disabled, true);
  f.update({ input: { ...f.props.input, reviewAvailable: false, active: false } }); assert.equal(f.button('开始系统听写').props.disabled, true); assert.equal(f.calls.length, 0);
});

test('document rotation retains local review text and rejects old actions and late completion', async () => {
  const wait = deferred(), f = fixture('voice', { 'quickVoice.save': () => wait.promise }); f.field('轮换前核对文字');
  const old = f.button('追加到原浮层草稿').props.onClick; f.click('追加到原浮层草稿'); f.rotate(); old(); wait.reject(new Error('旧页面错误')); await f.flush();
  assert.match(f.text, /原听写窗口已变化/); assert.doesNotMatch(f.text, /旧页面错误/); assert.equal(f.draft, '轮换前核对文字'); assert.equal(count(f, 'quickVoice.save'), 1);
});


test('first microphone permission request releases the start wait without recording or replay', async () => {
  const f = fixture('voice', {}, { input: { inputId: '', active: false, reviewAvailable: false, phase: 'idle' } });
  f.values['quickVoice.start'] = () => { f.publish({ systemSpeech: { recognitionAvailable: true, microphoneGranted: false }, notice: '请在系统窗口授权；授权后再明确点击系统听写，当前没有录音' }); return {}; };
  f.click('开始系统听写'); await f.flush();
  assert.equal(f.button('开始系统听写').props.disabled, false); assert.match(f.text, /授权后再明确点击系统听写/); assert.equal(count(f, 'quickVoice.start'), 1);
  f.publish({ systemSpeech: { recognitionAvailable: true, microphoneGranted: true } }); await f.flush(); assert.equal(count(f, 'quickVoice.start'), 1);
  f.values['quickVoice.start'] = {}; f.click('开始系统听写'); await f.flush(); assert.equal(count(f, 'quickVoice.start'), 2);
});

test('native no-service and catch-only failures release the wait and show the original error', async () => {
  for (const unavailable of [true, false]) {
    const f = fixture('voice', {}, { input: { inputId: '', active: false, reviewAvailable: false, phase: 'idle' } });
    f.values['quickVoice.start'] = () => { f.publish({ systemSpeech: { recognitionAvailable: !unavailable, microphoneGranted: true }, error: unavailable ? '这台设备没有可用系统语音服务。请继续文字输入，没有改用云服务' : '实时语音正在使用音频，请先结束后再系统听写' }); return {}; };
    f.click('开始系统听写'); await f.flush();
    assert.equal(f.button('开始系统听写').props.disabled, false); assert.match(f.text, unavailable ? /没有可用系统语音服务/ : /实时语音正在使用音频/); assert.equal(count(f, 'quickVoice.start'), 1);
  }
});

test('repeated identical native start failure has a manual local wait exit even without a new snapshot', async () => {
  const f = fixture('voice', {}, { input: { inputId: '', active: false, reviewAvailable: false, phase: 'idle' } });
  f.values['quickVoice.start'] = () => {
    if (count(f, 'quickVoice.start') === 1) f.publish({ error: '实时语音正在使用音频，请先结束后再系统听写' });
    // The second native attempt catches the identical error. Its identical snapshot
    // is deduplicated by the bridge, so no state notification or revision arrives.
    return {};
  };
  f.click('开始系统听写'); await f.flush(); assert.equal(f.button('开始系统听写').props.disabled, false);
  f.click('开始系统听写'); await f.flush(); assert.equal(count(f, 'quickVoice.start'), 2);
  assert.equal(f.button('开始系统听写').props.disabled, true, 'a stale error is not fresh proof that recording did not start');
  f.click('已核对系统状态，结束等待'); await f.flush();
  assert.equal(f.button('开始系统听写').props.disabled, false); assert.equal(count(f, 'quickVoice.start'), 2, 'ending the local wait never replays capture');
  assert.match(f.text, /没有再次开始录音/); assert.equal(count(f, 'voice.stop'), 0);
  const unknown = fixture('voice', { 'quickVoice.start': () => { throw Object.assign(new Error('启动结果未知'), { code: 'outcome_unknown' }); } }, { input: { inputId: '', active: false, reviewAvailable: false, phase: 'idle' } });
  unknown.click('开始系统听写'); await unknown.flush();
  assert.equal(unknown.button('开始系统听写').props.disabled, true);
  assert.equal(unknown.nodes.some(node => node.type === 'Button' && node.visible && node.children.includes('已核对系统状态，结束等待')), false);
});
