import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import { backgroundConnection } from '../src/forms-library/host-contracts.ts';
import * as helpers from '../src/background-settings.ts';

type Data = Record<string, any>;
const audioRecovery = (pending = 0) => ({ restored: 2, supersededByOwner: 1, pending, ownerRequestedWrite: true,
  status: pending ? 'audio_owner_action_required' : 'audio_reconciled', needsOwnerAction: pending > 0, mayRemainMuted: pending > 0 });

test('audio success requires the actual persisted value or a consistent original-ledger receipt', () => {
  for (const value of [null, { accepted: true }, { enabled: true }, { persisted: true, enabled: 'true' }, { persisted: false, enabled: true }])
    assert.throws(() => helpers.audioSettingReceipt(value, true));
  assert.throws(() => helpers.audioSettingReceipt({ persisted: true, enabled: false }, true));
  assert.match(helpers.audioSettingReceipt({ persisted: true, enabled: false }, false), /已有声音记录仍需单独处理/);
  assert.match(helpers.audioSettingReceipt({ persisted: true, enabled: true }, true), /已保存/);
  for (const patch of [{ restored: -1 }, { pending: 0.5 }, { pending: '0' }, { supersededByOwner: Number.MAX_SAFE_INTEGER + 1 },
    { ownerRequestedWrite: false }, { needsOwnerAction: true }, { mayRemainMuted: true }, { status: 'accepted' }])
    assert.throws(() => helpers.audioRecoveryReceipt({ ...audioRecovery(), ...patch }));
  assert.throws(() => helpers.audioRecoveryReceipt({ accepted: true }));
  assert.match(helpers.audioRecoveryReceipt(audioRecovery()), /当前原声音记录已结算/);
  assert.match(helpers.audioRecoveryReceipt(audioRecovery(3)), /仍有 3 项待处理，原 App 可能仍静音/);
  assert.throws(() => helpers.diagnosticPreview({ accepted: true }));
  assert.throws(() => helpers.diagnosticPreview({ reportHandle: ' ', text: 'report' }));
  assert.throws(() => helpers.ownerActionReceipt({}));
});

test('background connection follows native display ownership, not an unrelated active task', () => {
  const display = { supportedAndroid: true, authorizationBackend: 'wireless_adb',
    helperConnected: false, taskOwned: false, adb: { identityPresent: true } };
  const available = backgroundConnection(display, 'active-conversation-task');
  assert.equal(available.taskOwned, false);
  assert.equal(available.canConnect, true, 'a task waiting for setup does not reserve the background helper');
  assert.equal(backgroundConnection({ ...display, taskOwned: true }, 'active-conversation-task').taskOwned, true);
  const { taskOwned: _known, ...legacyDisplay } = display;
  assert.equal(backgroundConnection(legacyDisplay, 'active-conversation-task').taskOwned, true,
    'older snapshots without an explicit resource owner remain conservative');
  assert.equal(backgroundConnection(legacyDisplay, null).taskOwned, false);
});

test('per-copy policy stays on its exact user and requires a readable revision', () => {
  const main = { selectionHandle: 'main', packageName: 'test.app', userId: 0 }, work = { selectionHandle: 'work', packageName: 'test.app', userId: 10 };
  assert.deepEqual(helpers.ownerAppCopies({ apps: [main, work, null, [], { ...work, selectionHandle: '' }, { ...work, userId: -1 }] }), [main, work]);
  const policy = { available: true, revision: 12, rules: [{ packageName: 'test.app', userId: 0, automation: true, ownerShare: false }, { packageName: 'test.app', userId: 10, automation: false, ownerShare: true }] };
  assert.deepEqual(helpers.ownerAppAccess(policy, work), { automation: false, ownerShare: true, revision: 12 });
  assert.deepEqual(helpers.ownerAppAccess(policy, main), { automation: true, ownerShare: false, revision: 12 });
  for (const revision of [-1, undefined, '12', 1.5, Number.MAX_SAFE_INTEGER + 1]) assert.equal(helpers.ownerAppAccess({ ...policy, revision }, main).revision, null);
  assert.equal(helpers.ownerAppAccess({ ...policy, available: false }, main).revision, null);
  assert.equal(helpers.ownerWindowAvailable({ backgroundSetupOpen: true, backgroundOwner: {}, webWorkspaceTaskOpen: true }), false);
  assert.equal(helpers.ownerWindowAvailable({ backgroundSetupOpen: true }), false);
});

// Execute production handlers with explicit lifecycle/control doubles. No React
// Aria rendering, Android device, model execution or actual system side effects.
function fixture(name: 'BackgroundAudioSettings' | 'BackgroundOwnerSettings' | 'BackgroundChecks', initial: Data = {}) {
  const source = ts.createSourceFile('BackgroundOwnerSettings.tsx', readFileSync(new URL('../src/BackgroundOwnerSettings.tsx', import.meta.url), 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const body = source.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(source).replace(/^export /, '')).join('\n');
  const code = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
  const slots: any[] = [], effects: (() => void)[] = [], calls: Data[] = [], listeners = new Set<() => void>();
  const documentEvents = new Map<string, Set<() => void>>(), windowEvents = new Map<string, Set<() => void>>();
  let index = 0, dirty = false, tree: any, back: (() => boolean) | null = null, mounted = true, writesAfterUnmount = 0, documentEpoch = 1;
  let view: Data = { section: 'background', resumeRevision: 1, taskId: null };
  const useState = (initial: any) => { const i = index++; slots[i] ??= { value: typeof initial === 'function' ? initial() : initial }; return [slots[i].value, (next: any) => { if (!mounted) writesAfterUnmount++; const value = typeof next === 'function' ? next(slots[i].value) : next; if (!Object.is(value, slots[i].value)) { slots[i].value = value; dirty = true; } }]; };
  const useRef = (initial: any) => { const i = index++; return slots[i] ??= { current: initial }; };
  const useEffect = (effect: () => any, deps: any[]) => { const i = index++, prior = slots[i]; if (!prior || deps.some((value, j) => !Object.is(value, prior.deps[j]))) { const slot = slots[i] = { deps, cleanup: prior?.cleanup }; effects.push(() => { slot.cleanup?.(); slot.cleanup = effect(); }); } };
  const React = { Fragment: 'Fragment', createElement: (type: any, props: any, ...children: any[]) => ({ type, props: props || {}, children: children.flat(Infinity).filter(child => child != null && child !== false) }) };
  const eventTarget = (events: Map<string, Set<() => void>>) => ({ addEventListener: (name: string, callback: () => void) => { if (!events.has(name)) events.set(name, new Set()); events.get(name)!.add(callback); }, removeEventListener: (name: string, callback: () => void) => events.get(name)?.delete(callback) });
  const document = { visibilityState: 'visible', ...eventTarget(documentEvents) }, window = eventTarget(windowEvents);
  const bridge = { getDocumentEpoch: () => documentEpoch, getState: () => ({ view }), subscribe: (callback: () => void) => { listeners.add(callback); return () => listeners.delete(callback); } };
  const dependencies: Data = { ...helpers, object: helpers.backgroundObject, React, useState, useRef, useEffect, backgroundConnection, bridge, document, window };
  for (const name of ['Accordion', 'AccordionItem', 'Button', 'SelectField', 'RadioOptions', 'SettingsDialog', 'StretchSwitch', 'TextField']) dependencies[name] = name;
  const Component = new Function(...Object.keys(dependencies), code + '\nreturn ' + name + ';')(...Object.values(dependencies));
  const state: Data = { backgroundSetupOpen: true, webWorkspaceResumeRevision: 1, webWorkspaceTaskOpen: false, backgroundOwner: {}, moduleBusy: [], features: { display: { supportedAndroid: true, authorizationBackend: 'wireless_adb', helperConnected: true, audioSilenceEnabled: false, clipboardPasteAllowed: false, adb: { identityPresent: true }, appAccess: { available: true, revision: 1, rules: [] } }, phone_setup: { recovery: { consent: false, revision: 1 } } } };
  const props: Data = { supported: () => true, ...initial, state: { ...state, ...initial.state }, run: async (action: string, args: Data = {}) => { calls.push({ action, args }); return initial.run ? initial.run(action, args) : { accepted: true }; }, registerBack: (value: any) => { back = value; } };
  const render = () => { if (!mounted) return; let count = 0; do { assert.ok(count++ < 30, 'production effects settle'); dirty = false; index = 0; tree = Component(props); effects.splice(0).forEach(effect => effect()); } while (dirty); };
  const walk = (node: any): any[] => !node || typeof node !== 'object' || node.type === 'SettingsDialog' && !node.props.isOpen ? [] : [node, ...node.children.flatMap(walk)];
  const content = (node: any): string => !node || typeof node !== 'object' ? String(node ?? '') : node.type === 'SettingsDialog' && !node.props.isOpen ? '' : node.children.map(content).join('');
  const find = (predicate: (node: any) => boolean) => { const value = walk(tree).find(predicate); assert.ok(value, 'expected production control'); return value; };
  const button = (label: string) => find(node => node.type === 'Button' && content(node) === label);
  render();
  return { props, calls, find, render, button, get text() { return content(tree); }, get all() { return walk(tree); }, get writesAfterUnmount() { return writesAfterUnmount; },
    click(label: string) { const control = button(label); assert.ok(!control.props.disabled); control.props.onClick(); render(); },
    toggle(label: string, value: boolean) { const control = find(node => node.type === 'StretchSwitch' && node.props.label === label); assert.ok(!control.props.disabled && !control.props.busy); control.props.onChange(value); render(); },
    field(label: string, value: string) { find(node => node.type === 'TextField' && node.props.label === label).props.onChange(value); render(); },
    change(patch: Data) { props.state = { ...props.state, ...patch }; render(); },
    visibility(value: string) { document.visibilityState = value; documentEvents.get('visibilitychange')?.forEach(callback => callback()); render(); },
    rotate(epoch: boolean) { if (epoch) documentEpoch++; else view = { ...view, resumeRevision: view.resumeRevision + 1 }; listeners.forEach(callback => callback()); render(); },
    back() { const consumed = back?.(); render(); return consumed; },
    unmount() { mounted = false; slots.forEach(slot => slot.cleanup?.()); },
    async flush() { for (let i = 0; i < 8; i++) { await Promise.resolve(); if (dirty) render(); } },
  };
}
const deferred = () => { let resolve!: (value: any) => void; const promise = new Promise<any>(done => { resolve = done; }); return { promise, resolve }; };

test('audio controls keep unknown settings disabled and receipt acceptance never becomes saved policy', async () => {
  const f = fixture('BackgroundAudioSettings');
  assert.equal(f.calls.length, 0);
  f.toggle('打开应用前静音', true); await f.flush();
  assert.match(f.text, /保存尚未确认/);
  assert.equal(f.find(node => node.type === 'StretchSwitch').props.checked, false, 'the switch follows host state');
  f.change({ features: { display: { helperConnected: true } } });
  assert.equal(f.find(node => node.type === 'StretchSwitch').props.disabled, true);
  f.change({ activeTaskId: 'original-task', features: { display: { audioSilenceEnabled: false } } });
  assert.equal(f.find(node => node.type === 'StretchSwitch').props.disabled, true);
  f.change({ features: { display: { audioSilenceEnabled: true } } });
  assert.equal(f.find(node => node.type === 'StretchSwitch').props.disabled, false, 'the owner can revoke future silencing during a task');
});

test('audio restoration consumes Back before page navigation and one click dispatches one original write', async () => {
  const waiting = deferred(), f = fixture('BackgroundAudioSettings', { run: () => waiting.promise });
  f.click('处理原声音记录…'); assert.equal(f.back(), true); assert.equal(f.calls.length, 0);
  f.click('处理原声音记录…'); const confirm = f.button('按记录设置声音');
  confirm.props.onClick(); confirm.props.onClick(); f.render();
  assert.deepEqual(f.calls, [{ action: 'background.audio.restore', args: { confirmed: true } }]);
  waiting.resolve(audioRecovery(2)); await f.flush();
  assert.match(f.text, /仍有 2 项待处理/); assert.equal(f.text.includes('当前原声音记录已结算'), false);
});

test('late diagnostics never reopen a report after hiding, resume, document replacement, task overlay or unmount', async () => {
  for (const change of ['visibility', 'resume', 'document', 'task', 'owner', 'unmount']) {
    const waiting = deferred(), f = fixture('BackgroundChecks', { run: () => waiting.promise });
    f.click('预览脱敏诊断报告');
    if (change === 'visibility') { f.visibility('hidden'); f.visibility('visible'); }
    else if (change === 'resume' || change === 'document') f.rotate(change === 'document');
    else if (change === 'task') f.change({ webWorkspaceTaskOpen: true });
    else if (change === 'owner') f.change({ backgroundSetupOpen: false });
    else f.unmount();
    waiting.resolve({ reportHandle: 'old-report', text: 'old diagnostic result' }); await f.flush();
    assert.equal(f.text.includes('old diagnostic result'), false, change);
    assert.equal(f.all.some(node => node.type === 'Button' && node.children.includes('复制这份诊断')), false, change);
    assert.equal(f.writesAfterUnmount, 0, change);
    assert.equal(f.calls.length, 1, 'view retirement never replays the device request');
  }
});

test('diagnostic actions use the exact visible preview and clear it when its owner window changes', async () => {
  const f = fixture('BackgroundChecks', { run: (action: string) => action.endsWith('.preview') ? { reportHandle: 'original-report', text: 'redacted facts' } : action.endsWith('.copy') ? { copied: true } : { shareChooserRequested: true } });
  f.click('预览脱敏诊断报告'); await f.flush();
  assert.match(f.text, /redacted facts/); assert.equal(f.calls.length, 1);
  f.click('复制这份诊断'); await f.flush(); f.click('系统分享…'); await f.flush();
  assert.deepEqual(f.calls.slice(1), [{ action: 'background.diagnostics.copy', args: { reportHandle: 'original-report' } }, { action: 'background.diagnostics.share', args: { reportHandle: 'original-report' } }]);
  f.change({ webWorkspaceResumeRevision: 2 }); assert.equal(f.text.includes('redacted facts'), false);
  const incomplete = fixture('BackgroundChecks'); incomplete.click('预览脱敏诊断报告'); await incomplete.flush();
  assert.match(incomplete.text, /诊断预览回执不完整/);
  assert.equal(incomplete.all.some(node => node.type === 'Button' && node.children.includes('复制这份诊断')), false);
});

test('owner app edits pin the selected copy, preserve the other permission and expire with the list', async () => {
  const copy = { selectionHandle: 'work-profile-copy', packageName: 'test.app', label: 'Test', userId: 10, currentProfile: false, profileSerial: 7, uid: 100021, versionCode: 3 };
  const f = fixture('BackgroundOwnerSettings', { state: { activeTaskId: 'original-task', backgroundOwner: { apps: [copy] }, features: { display: { appAccess: { available: true, revision: 7, rules: [{ packageName: 'test.app', userId: 0, automation: false, ownerShare: true }, { packageName: 'test.app', userId: 10, automation: true, ownerShare: false }] } } } } });
  f.find(node => node.type === 'SelectField').props.onChange(copy.selectionHandle); f.render();
  f.toggle('允许自动读取和操作', false); await f.flush();
  assert.deepEqual(f.calls, [{ action: 'background.apps.set', args: { selectionHandle: copy.selectionHandle, automation: false, ownerShare: false, expectedRevision: 7 } }]);
  f.change({ backgroundOwner: { apps: [{ ...copy, selectionHandle: 'new-list-copy' }] } });
  assert.equal(f.find(node => node.type === 'SelectField').props.value, '');
  assert.equal(f.all.some(node => node.type === 'StretchSwitch' && node.props.label === '允许自动读取和操作'), false);
});

test('advanced compatibility never starts on selection and forget confirmation closes without a write on Back', () => {
  const f = fixture('BackgroundOwnerSettings');
  const radio = f.find(node => node.type === 'RadioOptions'); radio.props.onChange('adb'); f.render();
  assert.equal(f.calls.length, 0); assert.match(f.text, /准备电脑命令/);
  f.change({ features: { ...f.props.state.features, display: { ...f.props.state.features.display, authorizationBackend: 'shizuku' } } });
  assert.equal(f.find(node => node.type === 'RadioOptions').props.value, 'adb', 'an explicit presentation choice survives a background state refresh');
  f.click('遗忘本机无线配对身份…'); assert.equal(f.back(), true); assert.equal(f.calls.length, 0);
  f.click('遗忘本机无线配对身份…'); f.change({ activeTaskId: 'new-original-task' });
  assert.equal(f.all.some(node => node.type === 'SettingsDialog'), false);
});

test('practice confirmation pins the reviewed field and only a real evidence row can open its original task', async () => {
  const f = fixture('BackgroundChecks');
  f.field('目标测试应用包名', 'test.original'); f.field('可安全测试的输入框标签', '原普通字段');
  f.click('此字段后台演练…'); assert.equal(f.back(), true); assert.equal(f.calls.length, 0);
  f.click('此字段后台演练…');
  f.field('目标测试应用包名', 'test.changed'); f.field('可安全测试的输入框标签', '后来改变的字段');
  f.click('开始这次演练'); await f.flush();
  assert.deepEqual(f.calls, [{ action: 'background.practice.field', args: { mode: 'background', confirmed: true, packageName: 'test.original', field: '原普通字段' } }]);
  assert.match(f.text, /演练请求已登记/); assert.equal(f.text.includes('已实际核验'), false);
  f.change({ backgroundOwner: { practiceEvidence: { accepted: true, taskId: 'not-evidence' } } });
  assert.equal(f.all.some(node => node.type === 'Button' && node.children.includes('查看原演练任务')), false);
  f.change({ backgroundOwner: { practiceEvidence: { ok: true, taskId: 'older-original-task', resultScope: 'history_original_phone_run', checks: [] } } });
  f.click('查看原演练任务'); await f.flush();
  assert.deepEqual(f.calls.at(-1), { action: 'background.practice.open', args: { taskId: 'older-original-task' } });
});
