import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import * as contracts from '../src/channel-contracts.ts';

// Exercise production JSX and handlers with lifecycle, BoardUI-control and host
// doubles. This is not DOM, native-channel, screenshot or visual verification.
type Data = Record<string, any>;
const profile = { id: 'account-a', name: '我的原账号', provider: 'qq', appId: 'bot-a', transportEpoch: 'transport-a', enabled: true, hasSecret: true };
const binding = { profileId: profile.id, senderId: 'owner-a', bindingEpoch: 'binding-a', taskGrantEpoch: 'grant-a', ownerTasksEnabled: true };
const event = { id: 'message-a', profileId: profile.id, transportEpoch: profile.transportEpoch, kind: 'dm', senderId: 'owner-a', authenticatedBy: 'qq_gateway', text: '保留这条原消息', receivedAt: Date.now() - 1000, latestInferenceReference: { taskId: 'inference-a' }, ownerImageArtifacts: [{ artifactId: 'image-a', itemIndex: 0, sha256: 'fixed-image-sha' }], messageParts: { parts: [{ kind: 'text', text: '文字' }, { kind: 'media', mediaKind: 'voice', declaredName: '原语音' }] } };
const task = { id: 'task-a', goal: '原主人任务', status: 'unknown' };
const request = { requestId: 'request-a', taskId: task.id, admission: 'admission_unknown', cancellation: 'cancellation_unknown' };
const background = { sessionId: 'receiving-a', status: 'foreground', foregroundActive: true, foregroundServicePresent: true, runningReceivers: 1, authenticatedReceivers: 1, profiles: [{ id: profile.id }], platform: { serviceDeclared: true, notificationsVisible: true } };

function fixture(options: Data = {}) {
  const ast = ts.createSourceFile('ChannelsWorkspace.tsx', readFileSync(new URL('../src/ChannelsWorkspace.tsx', import.meta.url), 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const body = ast.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(ast).replace(/^export (default )?/, '')).join('\n');
  const code = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
  const frames = new Map<string, any[]>(), expanded = new Set<string>(), calls: Data[] = [], effects: (() => void)[] = [], events = new Map<string, Set<() => void>>();
  let slots: any[] = [], index = 0, dirty = false, tree: any, back: (() => boolean) | null = null, visited = new Set<string>(), focusCount = 0;
  const responses: Data = { 'channels.reviewReceiving': { reviewHandle: 'receiving-review-a', names: [profile.name] }, 'channels.startReceiving': { status: 'starting', foregroundEstablished: false }, 'channels.reviewOwner': { reviewHandle: 'owner-review-a', profileName: profile.name, senderId: binding.senderId, authenticatedBy: 'qq_gateway' }, 'channels.confirmOwner': { updated: true }, 'channels.reviewRemoval': { reviewHandle: 'remove-review-a', name: profile.name }, 'channels.inspectTask': { found: true, taskId: task.id }, 'channels.reviewMedia': { digest: 'media-review-a', name: '原语音', profileName: profile.name, senderId: event.senderId, itemIndex: 1 }, 'channels.freezeMedia': { attachment: { size: 16, mimeType: 'audio/test', sha256: 'fixed-media-sha' } }, ...options.responses };
  const useState = (initial: any) => { const frame = slots, i = index++; frame[i] ??= { value: typeof initial === 'function' ? initial() : initial }; return [frame[i].value, (next: any) => { const value = typeof next === 'function' ? next(frame[i].value) : next; if (!Object.is(value, frame[i].value)) { frame[i].value = value; dirty = true; } }]; };
  const useRef = (initial: any) => { const i = index++; return slots[i] ??= { current: initial }; };
  const useEffect = (effect: () => any, deps?: any[]) => { const i = index++, prior = slots[i]; if (!deps || !prior?.deps || deps.some((value, j) => !Object.is(value, prior.deps[j]))) { const slot = slots[i] = { deps, cleanup: prior?.cleanup }; effects.push(() => { slot.cleanup?.(); slot.cleanup = effect(); }); } };
  const scroller = { scrollTop: 0 }, root = { closest: () => scroller };
  const React = { Fragment: 'Fragment', createElement: (type: any, props: any, ...children: any[]) => { if (props?.ref && props.className?.includes('pw-channels-workspace')) props.ref.current = root; return { type, props: props || {}, children }; } };
  class Element { focus() { focusCount++; } }
  const listen = { addEventListener: (name: string, callback: () => void) => { if (!events.has(name)) events.set(name, new Set()); events.get(name)!.add(callback); }, removeEventListener: (name: string, callback: () => void) => events.get(name)?.delete(callback) };
  const document = { visibilityState: 'visible', activeElement: new Element(), ...listen };
  const dependencies: Data = { React, useState, useRef, useEffect, useLayoutEffect: useEffect, document, window: listen, HTMLElement: Element, ...contracts, rows: contracts.channelObjects, text: contracts.channelText };
  for (const key of ['ChevronRight', 'MessageSquare', 'RefreshCw', 'Settings', 'Accordion', 'AccordionItem', 'Button', 'CheckboxField', 'SearchField', 'SelectField', 'SettingsDialog', 'TextField', 'Tabs', 'TabList', 'Tab', 'TabPanel']) dependencies[key] = key;
  const Component = new Function(...Object.keys(dependencies), code + '\nreturn ChannelsWorkspace;')(...Object.values(dependencies));
  const props = { state: { tasks: [task], ...options.state }, data: { profiles: [profile], bindings: [binding], inbox: [event], taskRequests: [request], outbox: [{ id: 'delivery-a', text: '原回复', status: 'unknown' }, { id: 'delivery-b', text: '等待的回复', status: 'queued' }], background, capabilities: { ownerTaskHostConfigured: true }, ...options.data }, supported: (action: string) => !options.unsupported?.includes(action), registerTransientBack: (handler: any) => { back = handler; }, run: async (action: string, args: Data = {}) => { calls.push({ action, args }); const response = responses[action]; return typeof response === 'function' ? response(args) : response || {}; } };
  const resolve = (node: any, path: string): any => {
    if (Array.isArray(node)) return node.map((child, i) => resolve(child, path + '/' + (child?.props?.key ?? i)));
    if (!node || typeof node !== 'object') return node;
    if (typeof node.type === 'function') {
      const id = path + '/' + node.type.name + ':' + (node.props.key ?? ''); visited.add(id);
      if (!frames.has(id)) frames.set(id, []);
      const priorSlots = slots, priorIndex = index; slots = frames.get(id)!; index = 0;
      const output = node.type({ ...node.props, children: node.children }); slots = priorSlots; index = priorIndex;
      return resolve(output, id);
    }
    return { ...node, children: node.children.map((child: any, i: number) => resolve(child, path + '/' + String(node.type) + ':' + i)) };
  };
  const hidden = (node: any) => node.props.hidden || node.type === 'SettingsDialog' && !node.props.isOpen;
  const itemOpen = (node: any, owner?: any) => owner?.props.expandedKeys ? owner.props.expandedKeys.has(node.props.id) : expanded.has(node.props.id);
  const walk = (node: any, inactive = false, owner?: any): any[] => Array.isArray(node) ? node.flatMap(child => walk(child, inactive, owner)) : !node || typeof node !== 'object' ? [] : [{ ...node, visible: !inactive && !hidden(node), owner }, ...node.children.flatMap((child: any) => walk(child, inactive || hidden(node) || node.type === 'AccordionItem' && !itemOpen(node, owner), node.type === 'Accordion' ? node : owner))];
  const content = (node: any, visible = false, owner?: any): string => Array.isArray(node) ? node.map(child => content(child, visible, owner)).join('') : !node || typeof node !== 'object' ? node == null || node === false ? '' : String(node) : visible && hidden(node) ? '' : (node.type === 'AccordionItem' ? content(node.props.title) + content(node.props.description) : node.type === 'SettingsDialog' ? node.props.title : '') + (visible && node.type === 'AccordionItem' && !itemOpen(node, owner) ? '' : node.children.map((child: any) => content(child, visible, node.type === 'Accordion' ? node : owner)).join(''));
  const render = () => { let rounds = 0; do { assert.ok(rounds++ < 30, 'hook state settles'); dirty = false; visited = new Set(); tree = resolve(React.createElement(Component, props), 'root'); for (const [id, frame] of frames) if (!visited.has(id)) { frame.forEach(slot => slot?.cleanup?.()); frames.delete(id); } effects.splice(0).forEach(effect => effect()); } while (dirty); };
  const find = (predicate: (node: any) => boolean, visible = true) => { const node = walk(tree).filter(node => (!visible || node.visible) && predicate(node)).at(-1); assert.ok(node, 'expected production control'); return node; };
  const button = (label: string) => find(node => node.type === 'Button' && (content(node) === label || node.props['aria-label'] === label));
  render();
  return { props, calls, responses, find, button, scroller, get visibleText() { return content(tree, true); }, get focusCount() { return focusCount; },
    click(label: string) { const node = button(label); assert.ok(!node.props.disabled, label + ' is enabled'); node.props.onClick(); render(); },
    tab(id: string) { find(node => node.type === 'Tabs').props.onSelectionChange(id); render(); },
    expand(id: string) { const node = find(node => node.type === 'AccordionItem' && node.props.id === id); if (node.owner?.props.onExpandedChange) node.owner.props.onExpandedChange(new Set([...node.owner.props.expandedKeys, id])); else expanded.add(id); render(); },
    account(id = profile.id) { const node = find(node => node.type === 'Button' && node.props.className === 'pw-channels-link-row' && node.props.key === id); node.props.onClick(); render(); },
    field(label: string, value: string) { const node = find(node => ['TextField', 'SelectField'].includes(node.type) && node.props.label === label); assert.ok(!node.props.isDisabled); node.props.onChange(value); render(); },
    check(label: string) { const node = find(node => node.type === 'CheckboxField' && content(node) === label); assert.ok(!node.props.isDisabled); node.props.onChange(true); render(); },
    updateData(next: Data) { props.data = { ...props.data, ...next }; render(); },
    back() { const handled = back?.(); render(); return handled; },
    async flush() { for (let i = 0; i < 10; i++) { await Promise.resolve(); if (dirty) render(); } },
  };
}

test('channel workspace opens on recent messages without automatically connecting, receiving, replying or granting', async () => {
  const f = fixture(); await f.flush();
  assert.deepEqual(f.calls, [{ action: 'channels.open', args: {} }]);
  assert.equal(f.find(node => node.type === 'Tabs').props.selectedKey, 'inbox');
  assert.match(f.visibleText, /保留这条原消息/); assert.doesNotMatch(f.visibleText, /QQ 平台 AppSecret|核对所选账号并开启|核对并允许 \/task/);
  assert.equal(f.button('请求停止本次接收').props.disabled, false);
});

test('unknown task and delivery notices and the exact active stop remain above every tab', async () => {
  const f = fixture();
  for (const id of ['tasks', 'outbox', 'inbox']) {
    f.tab(id); assert.match(f.visibleText, /1 项任务回执待核对/); assert.match(f.visibleText, /1 项投递结果未知/); assert.match(f.visibleText, /前台接收服务已建立/);
    assert.equal(f.button('请求停止本次接收').props.disabled, false);
  }
  f.tab('outbox'); f.click('请求停止本次接收'); await f.flush();
  assert.deepEqual(f.calls.at(-1), { action: 'channels.stopReceiving', args: { sessionId: 'receiving-a' } });
  assert.equal(f.calls.some(call => /startReceiving|confirmOwner|saveProfile/.test(call.action)), false);
});

test('unknown deliveries older than the old 20-row window remain reachable; only the original queued reply can cancel', async () => {
  const f = fixture({ data: { outbox: [{ id: 'old-unknown', text: '更早的未知回复', status: 'unknown' }, ...Array.from({ length: 25 }, (_, i) => ({ id: 'sent-' + i, text: '已发回复 ' + i, status: 'sent' })), { id: 'queued-original', text: '待发原回复', status: 'queued' }] } });
  f.click('1 项投递结果未知'); assert.match(f.visibleText, /更早的未知回复/); f.expand('old-unknown'); assert.match(f.visibleText, /不自动重发/);
  f.expand('queued-original'); f.click('取消这项待发回复'); await f.flush();
  assert.deepEqual(f.calls.at(-1), { action: 'channels.cancelOutbox', args: { id: 'queued-original' } });
});

test('account detail keeps its stable identity through reorder and profile review keeps the original transport epoch', async () => {
  const other = { ...profile, id: 'account-b', name: '另一个账号', transportEpoch: 'transport-b', appId: 'bot-b' };
  const f = fixture({ data: { profiles: [profile, other] } }); f.click('连接与接收'); f.account();
  f.updateData({ profiles: [other, profile] }); assert.equal(f.find(node => node.type === 'SettingsDialog').props.title, profile.name);
  f.expand('account-connection'); f.click('连接设置'); f.field('连接名称', '新显示名称'); f.field('新 AppSecret（留空保留）', 'fixture-only-not-a-real-secret');
  f.click('保存这项连接'); await f.flush();
  const saved = f.calls.find(call => call.action === 'channels.saveProfile')!;
  assert.equal(saved.args.id, profile.id); assert.equal(saved.args.expectedTransportEpoch, profile.transportEpoch); assert.equal(saved.args.appId, profile.appId);
  assert.equal(saved.args.name, '新显示名称'); assert.equal(saved.args.secret, 'fixture-only-not-a-real-secret');
  assert.equal(f.find(node => node.type === 'SettingsDialog').props.title, profile.name); assert.ok(f.button('连接设置'));
  f.updateData({ profiles: [other] }); assert.match(f.visibleText, /原连接已不在本机/); assert.doesNotMatch(f.visibleText, /QQ 平台 AppSecret/);
  assert.equal(f.calls.filter(call => call.action === 'channels.saveProfile').length, 1);
});

test('profile changes behind an open editor block saving rather than borrowing a newer epoch', () => {
  const f = fixture(); f.click('连接与接收'); f.account(); f.expand('account-connection'); f.click('连接设置'); f.field('连接名称', '本地草稿');
  f.updateData({ profiles: [{ ...profile, transportEpoch: 'new-transport' }] });
  assert.equal(f.button('保存这项连接').props.disabled, true); assert.match(f.visibleText, /未用旧表单覆盖/);
  assert.equal(f.calls.some(call => call.action === 'channels.saveProfile'), false);
});

test('receiving requires explicit account selection, the original review handle and a separate acknowledgment', async () => {
  const f = fixture({ data: { background: { status: 'stopped', platform: { serviceDeclared: true, notificationsVisible: true } } } });
  f.click('连接与接收'); f.expand('receiving-scope'); assert.equal(f.button('核对所选账号并开启').props.disabled, true);
  f.check(profile.name + ' · QQ 官方机器人'); f.click('核对所选账号并开启'); await f.flush();
  assert.deepEqual(f.calls.at(-1), { action: 'channels.reviewReceiving', args: { profileIds: [profile.id] } }); assert.equal(f.button('明确开启接收').props.disabled, true);
  f.check('我已核对所选账号及接收和耗电范围'); f.click('明确开启接收'); await f.flush();
  assert.deepEqual(f.calls.at(-1), { action: 'channels.startReceiving', args: { reviewHandle: 'receiving-review-a' } });
  assert.equal(f.find(node => node.type === 'SettingsDialog').props.title, '连接与接收');
  f.back(); assert.equal(f.find(node => node.type === 'Tabs').props.selectedKey, 'inbox'); assert.equal(f.calls.filter(call => call.action === 'channels.startReceiving').length, 1);
});

test('owner task grant keeps original account, binding and permission identities and only confirms its issued handle', async () => {
  const f = fixture(); f.click('连接与接收'); f.account(); f.expand('account-permissions'); f.click('核对并关闭任务权限'); f.click('读取原审阅'); await f.flush();
  assert.deepEqual(f.calls.at(-1), { action: 'channels.reviewOwner', args: { profileId: profile.id, expectedTransportEpoch: profile.transportEpoch, expectedBindingEpoch: binding.bindingEpoch, expectedTaskGrantEpoch: binding.taskGrantEpoch, operation: 'tasks', enabled: false } });
  assert.equal(f.button('明确确认此操作').props.disabled, true); f.check('我已核对这个原账号与本次权限范围'); f.click('明确确认此操作'); await f.flush();
  assert.deepEqual(f.calls.at(-1), { action: 'channels.confirmOwner', args: { reviewHandle: 'owner-review-a' } });
  assert.equal(f.find(node => node.type === 'SettingsDialog').props.title, profile.name);
});

test('message expansion survives tabs and new arrivals, while media and image actions retain exact original identities', async () => {
  const original = { ...event, id: 'original-message' };
  const f = fixture({ data: { inbox: [original, ...Array.from({ length: 11 }, (_, i) => ({ ...event, id: 'newer-' + i }))] } });
  f.expand(original.id); f.scroller.scrollTop = 240; f.tab('tasks'); assert.equal(f.scroller.scrollTop, 0); f.scroller.scrollTop = 80; f.tab('inbox'); assert.equal(f.scroller.scrollTop, 240);
  f.updateData({ inbox: [original, ...Array.from({ length: 16 }, (_, i) => ({ ...event, id: 'newer-' + i }))] });
  assert.ok(f.find(node => node.type === 'AccordionItem' && node.props.id === original.id));
  f.click('查看固定原图 · 第 1 项'); await f.flush(); assert.deepEqual(f.calls.at(-1), { action: 'channels.openImage', args: { eventId: original.id, id: 'image-a', sha256: 'fixed-image-sha' } });
  f.click('核对原件 · 原语音'); await f.flush();
  assert.equal(f.calls.at(-1)?.action, 'channels.reviewMedia'); assert.equal(f.calls.at(-1)?.args.eventId, original.id); assert.equal(f.calls.at(-1)?.args.itemIndex, 1);
  assert.equal(f.button('保存这一项').props.disabled, true); f.check('我确认这份原本人私聊的这一项，只允许保存到本机'); f.click('保存这一项'); await f.flush();
  assert.deepEqual(f.calls.at(-1), { action: 'channels.freezeMedia', args: { digest: 'media-review-a' } }); f.back(); await f.flush(); assert.ok(f.focusCount > 0);
  assert.equal(f.find(node => node.type === 'Tabs').props.selectedKey, 'inbox');
  assert.ok(f.find(node => node.type === 'AccordionItem' && node.props.id === original.id));
  assert.equal(f.calls.some(call => /startReceiving|confirmOwner|saveProfile/.test(call.action)), false);
});

test('unknown task handling reads the same request only and retains its inspection result across tabs', async () => {
  const f = fixture(); f.click('1 项任务回执待核对'); f.expand(request.requestId); f.click('核对原宿主回执'); await f.flush();
  assert.deepEqual(f.calls.at(-1), { action: 'channels.inspectTask', args: { requestId: request.requestId } });
  f.tab('inbox'); f.tab('tasks'); assert.match(f.visibleText, /已核对同一宿主原任务，未重新执行/);
  f.click('打开原任务'); await f.flush(); assert.deepEqual(f.calls.at(-1), { action: 'tasks.open', args: { id: task.id } });
  assert.equal(f.calls.every(call => ['channels.open', 'channels.inspectTask', 'tasks.open'].includes(call.action)), true);
});


test('a changed owner grant invalidates the visible original review without silent rebinding', async () => {
  const f = fixture(); f.click('连接与接收'); f.account(); f.expand('account-permissions'); f.click('核对并关闭任务权限'); f.click('读取原审阅'); await f.flush();
  f.check('我已核对这个原账号与本次权限范围'); f.updateData({ bindings: [{ ...binding, taskGrantEpoch: 'new-grant' }] });
  assert.equal(f.button('明确确认此操作').props.disabled, true); assert.match(f.visibleText, /原连接、主人或权限已变化/);
  assert.equal(f.calls.some(call => call.action === 'channels.confirmOwner'), false);
});
