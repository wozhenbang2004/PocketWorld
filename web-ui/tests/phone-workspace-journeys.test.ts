import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import { backgroundConnection, wirelessEndpoints, wirelessPort } from '../src/forms-library/host-contracts.ts';
// Execute production JSX/handlers with explicit lifecycle/control doubles. Not DOM, graphics or Android verification.
type Data = Record<string, any>;
const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');
function fixture(name: 'PhoneSettings' | 'BackgroundSettings', initial: Data = {}) {
  const source = ts.createSourceFile('Secondary.tsx', read('../src/Secondary.tsx'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const names = new Set(['object', 'list', 'text', 'first', 'Card', 'Facts', 'Empty', name]);
  const body = source.statements.filter(node => ts.isFunctionDeclaration(node) ? names.has(node.name?.text || '') : ts.isVariableStatement(node) && node.declarationList.declarations.some(d => names.has(d.name.getText(source)))).map(node => node.getText(source)).join('\n');
  const journey = ts.createSourceFile('DataSettingsJourney.tsx', read('../src/DataSettingsJourney.tsx'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const retainedJourney = journey.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(journey).replace(/^export /, '')).join('\n');
  const code = ts.transpileModule(retainedJourney + '\n' + body, { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
  const slots: any[] = [], effects: (() => void)[] = [], calls: Data[] = [], navigated: string[] = [], views: string[] = [], focused: string[] = [];
  const events = new Map<string, Set<() => void>>(); let index = 0, dirty = false, tree: any, back: (() => boolean) | null = null;
  const scroller = { scrollTop: 0 };
  const walk = (node: any, hidden = false, selected = ''): any[] => {
    if (!node || typeof node !== 'object') return [];
    const currentTab = node.type === 'Tabs' ? node.props.selectedKey : selected;
    const inactive = hidden || !!node.props.hidden || node.type === 'SettingsDialog' && !node.props.isOpen || node.type === 'AccordionItem' || node.type === 'TabPanel' && !!currentTab && node.props.id !== currentTab;
    return [{ ...node, visible: !inactive }, ...node.children.flatMap((child: any) => walk(child, inactive, currentTab))];
  };
  const content = (node: any, visible = false, selected = ''): string => {
    if (!node || typeof node !== 'object') return String(node ?? '');
    const currentTab = node.type === 'Tabs' ? node.props.selectedKey : selected;
    if (visible && (node.props.hidden || node.type === 'SettingsDialog' && !node.props.isOpen || node.type === 'AccordionItem' || node.type === 'TabPanel' && currentTab && node.props.id !== currentTab)) return '';
    return node.children.map((child: any) => content(child, visible, currentTab)).join('');
  };
  const root = { closest: () => scroller, focus: () => focused.push('root'), querySelector: () => ({ focus: () => focused.push('heading') }), querySelectorAll: () => walk(tree).filter(node => node.props['data-operation-entry']).map(node => ({ dataset: { operationEntry: node.props['data-operation-entry'] }, focus: () => focused.push(node.props['data-operation-entry']) })) };
  const useState = (initial: any) => { const i = index++; slots[i] ??= { value: typeof initial === 'function' ? initial() : initial }; return [slots[i].value, (next: any) => { const value = typeof next === 'function' ? next(slots[i].value) : next; if (!Object.is(value, slots[i].value)) { slots[i].value = value; dirty = true; } }]; };
  const useRef = (initial: any) => { const i = index++; return slots[i] ??= { current: initial }; };
  const useEffect = (effect: () => any, deps: any[]) => { const i = index++, prior = slots[i]; if (!prior || deps.some((value, j) => !Object.is(value, prior.deps[j]))) { const slot = slots[i] = { deps, cleanup: prior?.cleanup }; effects.push(() => { slot.cleanup?.(); slot.cleanup = effect(); }); } };
  const React = { Fragment: 'Fragment', createElement: (type: any, props: any, ...children: any[]) => { if (props?.ref && props.className === 'pw-phone-workspace') props.ref.current = root; const values = children.flat(Infinity).filter(child => child !== null && child !== undefined && child !== false); return typeof type === 'function' ? type({ ...props, children: values }) : { type, props: props || {}, children: values }; } };
  const listen = { addEventListener: (name: string, callback: () => void) => { if (!events.has(name)) events.set(name, new Set()); events.get(name)!.add(callback); }, removeEventListener: (name: string, callback: () => void) => events.get(name)?.delete(callback) };
  const document = { visibilityState: 'visible', ...listen };
  const dependencies: Data = { React, useState, useRef, useEffect, useLayoutEffect: useEffect, backgroundConnection, wirelessEndpoints, wirelessPort, document, window: listen };
  for (const key of ['Accordion', 'AccordionItem', 'Button', 'CheckboxField', 'StretchSwitch', 'TextField', 'RadioOptions', 'SettingsDialog', 'PressButton', 'CapabilityAction', 'Tabs', 'TabList', 'Tab', 'TabPanel', 'RiArrowLeftLine', 'RiArrowRightSLine', 'RiCheckLine', 'AppearanceSettings', 'TypographySettings', 'SystemSpeechSettings', 'BackgroundAudioSettings', 'BackgroundOwnerSettings', 'BackgroundChecks']) dependencies[key] = key;
  const Component = new Function(...Object.keys(dependencies), code + '\nreturn ' + name + ';')(...Object.values(dependencies));
  const props: Data = { state: {}, supported: () => true, run: async (action: string, args: Data = {}) => { calls.push({ action, args }); return { accepted: true }; }, navigate: (value: string) => navigated.push(value), onReturn: () => navigated.push('phone'), registerTransientBack: (value: any) => { back = value; }, onViewChange: (value: string) => views.push(value), ...initial };
  const render = () => { let count = 0; do { assert.ok(count++ < 30, 'production effects settle'); dirty = false; index = 0; tree = Component(props); effects.splice(0).forEach(effect => effect()); } while (dirty); };
  const find = (predicate: (value: any) => boolean, visible = true) => { const value = walk(tree).find(node => (!visible || node.visible) && predicate(node)); assert.ok(value, 'expected production control'); return value; };
  render();
  return { props, calls, navigated, views, focused, scroller, find, render, get visibleText() { return content(tree, true); }, get all() { return walk(tree); }, get view() { return tree.props['data-phone-view'] || tree.props['data-background-panel']; },
    entry(id: string) { find(node => node.props['data-operation-entry'] === id).props.onClick(); render(); },
    click(label: string) { const button = find(node => ['Button', 'PressButton'].includes(node.type) && content(node) === label); assert.ok(!button.props.disabled); button.props.onClick(); render(); },
    field(label: string, value: string) { const field = find(node => node.type === 'TextField' && node.props.label === label); assert.ok(!field.props.isDisabled); field.props.onChange(value); render(); },
    panel(value: string) { find(node => node.type === 'Tabs').props.onSelectionChange(value); render(); },
    back() { const result = back?.(); render(); return result; },
    change(state: Data) { props.state = { ...props.state, ...state }; render(); },
    hide() { document.visibilityState = 'hidden'; events.get('visibilitychange')?.forEach(callback => callback()); render(); },
    async action(action: string, match: (args: any) => boolean = () => true) { const button = find(node => node.type === 'CapabilityAction' && node.props.action === action && match(node.props.args)); assert.ok(!button.props.disabled); await button.props.run(action, button.props.args); render(); },
    async flush() { for (let i = 0; i < 8; i++) { await Promise.resolve(); if (dirty) render(); } }
  };
}
test('phone home is a five-entry purpose list, with no automatic permission or browser action', () => {
  const f = fixture('PhoneSettings'); assert.equal(f.view, 'list'); assert.equal(f.all.filter(node => node.visible && node.props['data-operation-entry']).length, 5);
  assert.equal(f.calls.length, 0); assert.match(f.visibleText, /尚未核实/); assert.equal(f.visibleText.includes('允许本地 OCR'), false);
  f.scroller.scrollTop = 140; f.entry('observation'); assert.equal(f.view, 'observation'); assert.equal(f.scroller.scrollTop, 0); assert.equal(f.focused.at(-1), 'heading');
  assert.equal(f.back(), true); assert.equal(f.view, 'list'); assert.equal(f.scroller.scrollTop, 140); assert.equal(f.focused.at(-1), 'observation');
});
test('unknown observation settings stay disabled; known toggles preserve the original paired values', async () => {
  const f = fixture('PhoneSettings'); f.entry('observation');
  assert.equal(f.find(node => node.type === 'StretchSwitch' && node.props.label === '允许本地 OCR').props.disabled, true);
  f.change({ localCaptureAllowed: true, localOcrAllowed: false });
  f.find(node => node.type === 'StretchSwitch' && node.props.label === '允许本地 OCR').props.onChange(true); await f.flush();
  assert.deepEqual(f.calls, [{ action: 'settings.saveLocalObservation', args: { capture: true, ocr: true } }]);
});
test('browser entries dispatch only the named original purpose and do not infer a task', async () => {
  const f = fixture('PhoneSettings'); f.entry('browser');
  await f.action('settings.openBrowser', args => args.purpose === 'research'); await f.action('settings.openBrowser', args => args.purpose === 'browse');
  assert.deepEqual(f.calls.map(call => call.args), [{ purpose: 'research' }, { purpose: 'browse' }]); assert.equal(f.calls.some(call => call.action === 'phone.start'), false);
});
test('playing audio remains stoppable outside speech preferences, with the exact readout identity', async () => {
  const f = fixture('PhoneSettings', { state: { speaking: true, taskAnnouncements: { readout: { active: true, readoutId: 'original-readout' } } } });
  assert.match(f.visibleText, /停止朗读/); await f.action('speech.stopTaskAnnouncement'); assert.deepEqual(f.calls[0], { action: 'speech.stopTaskAnnouncement', args: { readoutId: 'original-readout' } });
  f.entry('appearance'); assert.match(f.visibleText, /停止朗读/); assert.equal(f.find(node => node.type === 'SystemSpeechSettings', false).visible, false);
});
test('returning from background retains the phone presentation choice without storing authority', () => {
  const f = fixture('PhoneSettings', { initialView: 'execution' }); assert.equal(f.view, 'execution'); assert.equal(f.calls.length, 0);
  const button = f.find(node => node.type === 'Button' && node.props.onClick && node.children.some((child: any) => typeof child === 'object'));
  assert.ok(button); assert.equal(f.views.at(-1), 'execution');
  const source = read('../src/Secondary.tsx'); assert.match(source, /initialView=\{phonePresentation\.current\}/); assert.match(source, /setCode\(''\); onReturn\(\)/);
});
const display = (extra: Data = {}) => ({ supportedAndroid: true, authorizationBackend: 'wireless_adb', helperConnected: false, adb: { phase: 'needs_pairing', identityPresent: false, discovery: {} }, ...extra });
const background = (extra: Data = {}) => ({ backgroundSetupOpen: true, accessibilityEnabled: true, notificationsEnabled: true, phoneReadiness: { groups: [{ id: 'background', title: '后台显示', state: 'blocked', checks: [{ id: 'input', state: 'unknown', message: '输入能力尚未核实', action: 'retry' }] }] }, features: { display: display() }, ...extra });
test('background setup leads with one current connection stage and hides audit details until selected', () => {
  const f = fixture('BackgroundSettings', { state: background() }); assert.equal(f.view, 'setup'); assert.equal(f.calls.length, 0);
  assert.match(f.visibleText, /与这台手机配对/); assert.equal(f.visibleText.includes('本机能力检查'), false);
  const steps = f.all.filter(node => node.props['aria-current'] === 'step'); assert.equal(steps.length, 1);
  f.panel('checks'); assert.equal(f.view, 'checks'); assert.match(f.visibleText, /本机能力检查/); assert.equal(f.back(), true); assert.equal(f.view, 'setup');
});
test('owner child dialogs consume their registered Back before leaving the setup panel', () => {
  const f = fixture('BackgroundSettings', { state: background() });
  f.panel('checks');
  const audio = f.find(node => node.type === 'BackgroundAudioSettings', false);
  const advanced = f.find(node => node.type === 'BackgroundOwnerSettings', false);
  const checks = f.find(node => node.type === 'BackgroundChecks', false);
  let closed = 0;
  checks.props.registerBack(() => { closed++; checks.props.registerBack(null); return true; });
  audio.props.registerBack(null); advanced.props.registerBack(null);
  assert.equal(f.back(), true); assert.equal(closed, 1); assert.equal(f.view, 'checks');
  assert.equal(f.back(), true); assert.equal(f.view, 'setup');
  audio.props.registerBack(() => { closed++; audio.props.registerBack(null); return true; });
  checks.props.registerBack(null);
  assert.equal(f.back(), true); assert.equal(closed, 2); assert.equal(f.view, 'setup');
  assert.equal(f.calls.length, 0, 'dismissing a dialog never dispatches its protected action');
});
test('changing setup panels or leaving the app clears pairing codes without submitting', () => {
  for (const leave of ['panel', 'hide']) {
    const f = fixture('BackgroundSettings', { state: background() }); f.field('系统窗口当前的六位配对码', '123456');
    if (leave === 'panel') { f.panel('checks'); f.panel('setup'); } else f.hide();
    assert.equal(f.find(node => node.type === 'TextField' && node.props.type === 'password').props.value, ''); assert.equal(f.calls.length, 0);
  }
});
test('explicit pairing uses the original loopback target once, with pending receipt and stop controls on both tabs', async () => {
  const f = fixture('BackgroundSettings', { state: background() }); f.click('未找到？填写本机端口'); f.field('当前配对窗口的端口', '37123'); f.field('系统窗口当前的六位配对码', '123456'); f.click('确认配对并继续'); await f.flush();
  assert.deepEqual(f.calls, [{ action: 'background.pair', args: { host: '127.0.0.1', port: 37123, pairingCode: '123456' } }]);
  assert.match(f.visibleText, /等待实际连接回执/); assert.match(f.visibleText, /停止本次连接与启动/); f.panel('checks'); assert.match(f.visibleText, /停止本次连接与启动/); assert.equal(f.calls.length, 1);
});
test('unknown connection and audio recovery remain visible above either tab and cannot restart pairing', () => {
  const f = fixture('BackgroundSettings', { state: background({ features: { display: display({ recoveryRequired: true, adb: { phase: 'paired', identityPresent: true, recoveryRequired: true, discovery: {} } }), phone_setup: { audioRecovery: { pendingCount: 2, needsOwnerAction: true } } } }) });
  for (const panel of ['setup', 'checks']) { f.panel(panel); assert.match(f.visibleText, /上次连接结果尚未确认/); assert.match(f.visibleText, /原 App 可能仍静音/); }
  assert.equal(f.calls.length, 0); f.panel('setup'); assert.equal(f.all.some(node => node.visible && node.type === 'TextField' && node.props.type === 'password'), false);
});
test('an original task retains ownership and host-only compatibility selection still requires its review', async () => {
  const owned = fixture('BackgroundSettings', { state: background({ activeTaskId: 'original-task' }) });
  assert.equal(owned.all.some(node => node.visible && node.type === 'TextField' && node.props.type === 'password'), false); await owned.action('tasks.open'); assert.deepEqual(owned.calls[0].args, { id: 'original-task' });
  const fallback = fixture('BackgroundSettings', { state: background({ features: { display: display({ supportedAndroid: false }) } }) });
  assert.equal(fallback.calls.length, 0); fallback.click('选择主屏兼容模式'); assert.match(fallback.visibleText, /占用当前屏幕/); assert.equal(fallback.calls.length, 0); assert.equal(fallback.back(), true); assert.equal(fallback.calls.length, 0);
});

test('native connection explanations survive outside disclosures even without a module error', () => {
  const f = fixture('BackgroundSettings', { state: background({ features: { display: display({ adb: { phase: 'connecting', busy: true, message: '原连接进程退出尚未确认，请核对设备服务', discovery: {} } }) } }) });
  for (const panel of ['setup', 'checks']) { f.panel(panel); assert.match(f.visibleText, /原连接进程退出尚未确认/); }
  assert.equal(f.calls.length, 0);
});

// Execute Secondary's actual navigation/return/back closures over retained state.
// Child widgets are excluded; this verifies the return stack, not native Back or DOM.
test('background return consumes the original phone entry and does not reopen setup on Back', () => {
  const source = ts.createSourceFile('Secondary.tsx', read('../src/Secondary.tsx'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const secondary = source.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'Secondary') as ts.FunctionDeclaration;
  const names = new Set(['navigate', 'returnToPhone', 'consumeBack']);
  const body = secondary.body!.statements.filter(node => ts.isVariableStatement(node) && node.declarationList.declarations.some(d => names.has(d.name.getText(source)))).map(node => node.getText(source)).join('\n');
  const code = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  for (const route of ['settings', 'background']) {
    const state: Data = { page: route, query: '', modelView: { kind: 'list' }, candidateTarget: null };
    const trail = { current: [] as Data[] }, modelTrail = { current: [] as Data[] }, generation = { current: 0 };
    const handlers = () => {
      const deps: Data = { ...state, route, trail, modelTrail, generation, transientBack: { current: null }, isModelPage: false, normalize: (value: string) => value, rememberScroll() {}, setNotice() {}, setError() {} };
      for (const key of ['page', 'query', 'modelView', 'candidateTarget']) deps['set' + key[0].toUpperCase() + key.slice(1)] = (value: any) => { state[key] = value; };
      return new Function(...Object.keys(deps), code + '\nreturn { navigate, returnToPhone, consumeBack };')(...Object.values(deps));
    };
    if (route === 'settings') { handlers().navigate('phone'); state.query = 'original query'; handlers().navigate('background'); }
    handlers().returnToPhone(); assert.equal(state.page, 'phone');
    assert.equal(trail.current.some(entry => entry.page === 'background' || entry.page === 'phone'), false);
    if (route === 'settings') assert.equal(state.query, 'original query');
    assert.equal(handlers().consumeBack(), true); assert.equal(state.page, 'settings');
    assert.equal(handlers().consumeBack(), false);
  }
});
test('footer and native setup closure both return through the same parent callback after clearing secrets', () => {
  const f = fixture('BackgroundSettings', { state: background() });
  f.field('系统窗口当前的六位配对码', '123456'); f.click('返回手机能力'); assert.deepEqual(f.navigated, ['phone']);
  assert.equal(f.find(node => node.type === 'TextField' && node.props.type === 'password').props.value, '');
  const closed = fixture('BackgroundSettings', { state: background() }); closed.change({ backgroundSetupOpen: false }); assert.deepEqual(closed.navigated, ['phone']);
});
