import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import { aiAccountStatus, aiCatalogCurrent, aiConnections, aiConnectionSource, aiCredentialStatus, aiObject, aiRows, aiServiceBrand, aiSourceGroups, aiSourceLabel, aiText } from '../src/ai-center-data.ts';
import { backgroundConnection } from '../src/forms-library/host-contracts.ts';
import { normalizeWorkspaceRoute, workspaceForRoute, workspaceNavigationKey } from '../src/workspace-directory.ts';

// Execute production presentation/handlers with lifecycle and host doubles.
// Renderer journeys cover DOM/focus; these tests do not claim Android behavior.
type Data = Record<string, any>;
const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');
const compile = (body: string) => ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
function centerFixture(initial: Data = {}) {
  const ast = ts.createSourceFile('AICenter.tsx', read('../src/AICenter.tsx'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const body = ast.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(ast).replace(/^export /, '')).join('\n');
  const slots: any[] = [], effects: (() => void)[] = [], calls: Data[] = [], hosts: string[] = [], focused: string[] = [];
  let index = 0, dirty = false, tree: any, back: (() => boolean) | null = null, childBack: ((handler: (() => boolean) | null) => void) | null = null;
  const scroller = { scrollTop: 0 };
  const root = { closest: () => scroller, querySelector: (selector: string) => ({ focus: () => focused.push(selector) }) };
  const useState = (initial: any) => { const i = index++; slots[i] ??= { value: typeof initial === 'function' ? initial() : initial }; return [slots[i].value, (next: any) => { const value = typeof next === 'function' ? next(slots[i].value) : next; if (!Object.is(value, slots[i].value)) { slots[i].value = value; dirty = true; } }]; };
  const useRef = (initial: any) => { const i = index++; return slots[i] ??= { current: initial }; };
  const useEffect = (effect: () => any, deps?: any[]) => { const i = index++, old = slots[i]; if (!old || !deps || deps.some((value, j) => !Object.is(value, old.deps[j]))) { const slot = slots[i] = { deps, cleanup: old?.cleanup }; effects.push(() => { slot.cleanup?.(); slot.cleanup = effect(); }); } };
  const useCallback = (value: any, deps: any[]) => { const i = index++, old = slots[i]; if (!old || deps.some((dep, j) => !Object.is(dep, old.deps[j]))) slots[i] = { deps, value }; return slots[i].value; };
  const React = { Fragment: 'Fragment', createElement: (type: any, props: Data | null, ...children: any[]) => {
    if (props?.ref && props.className === 'pw-ai-center') props.ref.current = root;
    return { type, props: props || {}, children: children.flat(Infinity).filter(child => child !== false && child !== undefined && child !== null) };
  } };
  const deps: Data = { React, useState, useRef, useEffect, useLayoutEffect: useEffect, useCallback,
    object: aiObject, rows: aiRows, text: aiText, aiAccountStatus, aiCatalogCurrent, aiConnections, aiConnectionSource, aiCredentialStatus, aiServiceBrand, aiSourceGroups, aiSourceLabel };
  for (const name of ['ChevronRight', 'KeyRound', 'Plus', 'RefreshCw', 'Server', 'Settings2', 'UserRound', 'Accordion', 'AccordionItem', 'Button', 'SearchField', 'SettingsDialog', 'StretchSwitch', 'TextField', 'Tabs', 'Tab', 'TabList', 'TabPanel', 'AIModelPicker', 'AIModelGroups']) deps[name] = name;
  const Component = new Function(...Object.keys(deps), compile(body) + '\nreturn AICenter;')(...Object.values(deps));
  const props: Data = { state: { modelName: 'original-model', modelSelectionRevision: 5 }, data: { connections: Array.from({ length: 6 }, (_, i) => ({ id: 'source-' + i, revision: 1, name: i ? 'Other ' + i : 'Anthropic', enabled: true, hasApiKey: true, provider: 'openai_chat', baseUrl: 'https://example.test/v1' })) },
    supported: () => false, registerTransientBack: (handler: any) => { back = handler; }, onHostSectionChange: (section: string) => hosts.push(section),
    run: async (action: string, args: Data = {}) => { calls.push({ action, args }); return {}; },
    renderEditor: () => null, renderAccounts: () => null, renderTools: () => null,
    renderProxy: (registerBack: any) => { childBack = registerBack; return React.createElement('ProxyWorkspace', {}); }, ...initial };
  const hidden = (node: any) => node.props.hidden || node.type === 'SettingsDialog' && !node.props.isOpen;
  const walk = (node: any, inactive = false): any[] => !node || typeof node !== 'object' ? [] : [{ ...node, visible: !inactive && !hidden(node) }, ...node.children.flatMap((child: any) => walk(child, inactive || hidden(node)))];
  const content = (node: any): string => node && typeof node === 'object' ? node.children.map(content).join('') : String(node ?? '');
  const render = () => { let rounds = 0; do { assert.ok(rounds++ < 30); dirty = false; index = 0; tree = Component(props); effects.splice(0).forEach(effect => effect()); } while (dirty); };
  const find = (predicate: (node: any) => boolean) => { const node = walk(tree).find(node => node.visible && predicate(node)); assert.ok(node, 'expected production control'); return node; };
  render();
  return { props, calls, hosts, scroller, focused, find, render,
    get view() { return tree.props['data-ai-view']; }, get nodes() { return walk(tree).filter(node => node.visible); }, get text() { return content(tree); },
    async flush() { for (let i = 0; i < 20; i++) { await Promise.resolve(); if (dirty) render(); } },
    click(label: string) { const node = find(node => node.type === 'Button' && content(node) === label); assert.ok(!node.props.disabled); node.props.onClick(); render(); },
    query(value: string) { find(node => node.type === 'SearchField' && node.props['aria-label'] === '搜索服务或账号').props.onChange(value); render(); },
    tab(value: string) { find(node => node.type === 'Tabs').props.onSelectionChange(value); render(); },
    pickerPresentation(value: Data) { find(node => node.type === 'AIModelPicker').props.onPresentationChange(value); render(); },
    registerProxyBack(handler: (() => boolean) | null) { assert.ok(childBack); childBack(handler); },
    back() { const handled = back?.(); render(); return handled; },
  };
}

test('failed account reads do not masquerade as an empty account center and can be reread', async () => {
  let attempt = 0;
  const calls: string[] = [];
  const f = centerFixture({ data: { connections: [] }, supported: (action: string) => action === 'models.accounts.open',
    run: async (action: string) => {
      calls.push(action);
      if (action === 'models.accounts.close') return {};
      if (++attempt === 1) throw new Error('temporarily unavailable');
      return { windowId: 'account-window-2', accountSnapshot: { accounts: [{ id: 'owner', label: '原来的账号', enabled: true, status: 'connected', planUsageEnabled: true }] } };
    } });
  await f.flush();
  assert.match(f.text, /当前还不能确认已保存的账号/);
  assert.doesNotMatch(f.text, /连接你的第一个 AI 服务/);
  f.click('重新读取账号'); await f.flush();
  assert.match(f.text, /原来的账号/); assert.match(f.text, /已登录/);
  assert.doesNotMatch(f.text, /账号状态暂未更新/);
  assert.deepEqual(calls, ['models.accounts.open', 'models.accounts.open', 'models.accounts.close']);
});

test('source categories remain distinct even when their model protocols are identical', async () => {
  const f = centerFixture({ data: { connections: [
    { id: 'official', name: '官方服务', provider: 'openai_responses', baseUrl: 'https://api.openai.com/v1', hasApiKey: true },
    { id: 'gateway', name: '我的网关', provider: 'openai_responses', baseUrl: 'https://gateway.example/v1', hasApiKey: true },
    { id: 'local', name: '本机模型', provider: 'openai_chat', baseUrl: 'http://127.0.0.1:11434/v1', hasApiKey: false },
  ] } });
  const groups = f.nodes.filter(node => node.type === 'section' && node.props.className === 'pw-ai-service-group');
  assert.deepEqual(groups.map(node => node.props['aria-label']), ['官方 API', '第三方网关', '本地服务']);
  assert.match(f.text, /官方服务/); assert.match(f.text, /我的网关/); assert.match(f.text, /本机模型/);
});

test('a pending model selection consumes Back before its busy render and releases it afterward', () => {
  const f = centerFixture(); f.tab('models');
  const picker = f.find(node => node.type === 'AIModelPicker');
  picker.props.onSelectionPending(true);
  assert.equal(f.back(), true, 'the original Back handler must consume synchronous child pending');
  assert.equal(f.find(node => node.type === 'Tabs').props.selectedKey, 'models');
  assert.equal(f.find(node => node.type === 'Tabs').props.isDisabled, true);
  assert.equal(f.find(node => node.type === 'Button' && node.props.className === 'pw-ai-proxy-link').props.disabled, true);
  picker.props.onSelectionPending(false); f.render();
  assert.equal(f.find(node => node.type === 'Tabs').props.isDisabled, false);
  assert.equal(f.back(), false, 'the real caller receives Back only after the operation settles');
});

test('pending model selection blocks stale tab and proxy handlers without unmounting its owner', () => {
  const f = centerFixture(); f.tab('models');
  const picker = f.find(node => node.type === 'AIModelPicker');
  const tabs = f.find(node => node.type === 'Tabs');
  const proxy = f.find(node => node.type === 'Button' && node.props.className === 'pw-ai-proxy-link');
  picker.props.onSelectionPending(true);
  // Events can still use pre-render closures while the child has begun writing.
  tabs.props.onSelectionChange('services'); proxy.props.onClick(); f.render();
  assert.equal(f.view, 'main');
  assert.equal(f.find(node => node.type === 'Tabs').props.selectedKey, 'models');
  assert.ok(f.find(node => node.type === 'AIModelPicker'));
  assert.deepEqual(f.hosts, []);
  f.tab('routing'); assert.equal(f.find(node => node.type === 'Tabs').props.selectedKey, 'models');
  picker.props.onSelectionPending(false); f.render();
  f.tab('services'); assert.equal(f.find(node => node.type === 'Tabs').props.selectedKey, 'services');
  f.click('本机 API'); assert.equal(f.view, 'proxy'); assert.deepEqual(f.hosts, ['proxy']);
});

test('AI proxy return restores the same tab, service query and scroll after consuming child Back', () => {
  const f = centerFixture(); f.query('Anthropic'); f.tab('routing'); f.scroller.scrollTop = 240;
  f.click('本机 API'); assert.equal(f.view, 'proxy'); assert.deepEqual(f.hosts, ['proxy']); assert.equal(f.scroller.scrollTop, 0);
  assert.equal(f.nodes.some(node => node.type === 'AIModelGroups'), false, 'the old native owner page is unmounted');
  let closed = 0;
  f.registerProxyBack(() => { closed++; f.registerProxyBack(null); return true; });
  assert.equal(f.back(), true); assert.equal(closed, 1); assert.equal(f.view, 'proxy');
  assert.equal(f.back(), true); assert.equal(f.view, 'main'); assert.deepEqual(f.hosts, ['proxy', 'models']);
  assert.equal(f.find(node => node.type === 'Tabs').props.selectedKey, 'routing');
  assert.equal(f.scroller.scrollTop, 240); assert.equal(f.focused.at(-1), '.pw-ai-proxy-link');
  f.tab('services'); assert.equal(f.find(node => node.type === 'SearchField').props.value, 'Anthropic');
  assert.equal(f.back(), false); assert.deepEqual(f.calls, []);
});

test('AI proxy retains only model presentation and reconstructs the picker on return', () => {
  const f = centerFixture(); f.tab('models'); f.pickerPresentation({ query: 'reasoner', filter: 'favorites' });
  f.click('本机 API'); assert.equal(f.nodes.some(node => node.type === 'AIModelPicker'), false);
  f.back(); assert.equal(f.find(node => node.type === 'Tabs').props.selectedKey, 'models');
  assert.deepEqual(f.find(node => node.type === 'AIModelPicker').props.presentationState, { query: 'reasoner', filter: 'favorites' });
  assert.deepEqual(Object.keys(f.find(node => node.type === 'AIModelPicker').props.presentationState).sort(), ['filter', 'query']);
  assert.deepEqual(f.calls, [], 'navigation does not select, test or start a model service');
});

test('a direct proxy entry closes its child first and then delegates to its actual caller', () => {
  const f = centerFixture({ initialView: 'proxy' }); assert.equal(f.view, 'proxy');
  assert.equal(f.nodes.some(node => node.type === 'Tabs' || node.type === 'AIModelPicker'), false);
  f.registerProxyBack(() => { f.registerProxyBack(null); return true; });
  assert.equal(f.back(), true); assert.equal(f.view, 'proxy');
  assert.equal(f.back(), false); assert.equal(f.view, 'proxy'); assert.deepEqual(f.hosts, []); assert.deepEqual(f.calls, []);
});

test('AI routes highlight AI access and devices contain only real device peers', () => {
  for (const route of ['models', 'model-settings', 'accounts', 'proxy', 'connections:proxy', 'settings/accounts']) assert.equal(workspaceNavigationKey(route), 'model-settings', route);
  assert.equal(workspaceForRoute('proxy'), undefined);
  assert.deepEqual(workspaceForRoute('devices')?.pages.map(row => row.route), ['devices', 'remote', 'ssh', 'local']);
  assert.equal(workspaceNavigationKey('ssh'), 'devices'); assert.equal(workspaceNavigationKey('settings'), 'settings');
});

test('Secondary direct, settings and device proxy entries return through their original trail', () => {
  const source = ts.createSourceFile('Secondary.tsx', read('../src/Secondary.tsx'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const component = source.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'Secondary') as ts.FunctionDeclaration;
  const names = new Set(['navigate', 'consumeBack']);
  const body = component.body!.statements.filter(node => ts.isVariableStatement(node) && node.declarationList.declarations.some(d => names.has(d.name.getText(source)))).map(node => node.getText(source)).join('\n');
  for (const route of ['proxy', 'settings', 'devices']) {
    const state: Data = { page: route, query: 'entry search', modelView: { kind: 'list' }, modelHostSection: route === 'proxy' ? 'proxy' : 'models', candidateTarget: null };
    const trail = { current: [] as Data[] }, modelTrail = { current: [] as Data[] }, generation = { current: 0 };
    const handlers = () => {
      const deps: Data = { ...state, route, trail, modelTrail, generation, transientBack: { current: null }, isModelPage: ['models', 'model-settings'].includes(state.page), workspace: workspaceForRoute(state.page), workspaceForRoute, normalize: normalizeWorkspaceRoute, rememberScroll() {}, setNotice() {}, setError() {} };
      for (const key of ['page', 'query', 'modelView', 'modelHostSection', 'candidateTarget']) deps['set' + key[0].toUpperCase() + key.slice(1)] = (value: any) => { state[key] = value; };
      return new Function(...Object.keys(deps), compile(body) + '\nreturn { navigate, consumeBack };')(...Object.values(deps));
    };
    if (route !== 'proxy') {
      handlers().navigate('proxy'); assert.equal(state.page, 'proxy'); assert.equal(state.modelHostSection, 'proxy');
      assert.equal(handlers().consumeBack(), true); assert.equal(state.page, route); assert.equal(state.query, 'entry search');
    }
    assert.equal(handlers().consumeBack(), false, route + ' returns to its real enclosing app');
    assert.equal(trail.current.length, 0);
  }
});

test('settings summaries use explicit observations and retain unknown outcomes', () => {
  const source = ts.createSourceFile('Secondary.tsx', read('../src/Secondary.tsx'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const names = new Set(['object', 'text', 'settingsEntryDetail']);
  const body = source.statements.filter(node => ts.isFunctionDeclaration(node) ? names.has(node.name?.text || '') : ts.isVariableStatement(node) && node.declarationList.declarations.some(d => names.has(d.name.getText(source)))).map(node => node.getText(source)).join('\n');
  const detail = new Function('backgroundConnection', compile(body) + '\nreturn settingsEntryDetail;')(backgroundConnection);
  assert.equal(detail('model-settings', {}), '默认模型尚未读取');
  assert.equal(detail('model-settings', { modelName: false }), '默认模型尚未读取');
  assert.equal(detail('model-settings', { modelName: '' }), '尚未选择默认模型');
  assert.equal(detail('model-settings', { modelName: 'saved-model' }), '默认模型 · saved-model');
  assert.equal(detail('proxy', {}), '尚未读取服务状态');
  assert.equal(detail('proxy', { features: { proxy: { hasToken: true } } }), '尚未读取服务状态');
  assert.equal(detail('proxy', { features: { proxy: { running: false } } }), '尚未运行');
  assert.equal(detail('proxy', { features: { proxy: { running: false } }, moduleBusy: ['proxy.start'] }), '服务操作处理中');
  assert.equal(detail('proxy', { features: { proxy: { running: true } }, moduleErrors: { 'proxy.stop': 'unknown result' } }), '服务状态待核对');
  assert.equal(detail('phone:execution', {}), '界面读取待核实 · 通知待核实');
  assert.equal(detail('phone:execution', { accessibilityEnabled: true, notificationsEnabled: false }), '界面读取已开 · 通知未开');
  assert.equal(detail('phone:observation', { localCaptureAllowed: false, localOcrAllowed: true }), '取图未开 · OCR 已开');
  assert.equal(detail('background', { features: { display: { helperConnected: true, recoveryRequired: true } } }), '后台连接需要核对');
  assert.equal(detail('phone:appearance', { appearance: { mode: 'dark' } }), '深色');
  assert.equal(detail('usage', {}), '');
});
