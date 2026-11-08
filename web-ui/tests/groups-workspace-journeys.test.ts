import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import * as contracts from '../src/groups-workspace.ts';

type Data = Record<string, any>;
const reference = { requestId: 'request-a', taskId: 'task-a', groupId: 'group-a', spaceId: 'group:group-a', goal: '整理会议结论', senderId: 'sender-a', capability: 'group.files', audience: 'group' };
const quota = { dailyTasks: 20, memberDailyTasks: 5, dailyTokens: 40000, maxTokensPerTask: 32000, maxConcurrent: 2, dailyMedia: 4, memberDailyMedia: 1, maxMediaBytes: 20971520 };
const group = (extra: Data = {}) => ({ id: 'group-a', name: '产品讨论', provider: 'qq', spaceId: 'group:group-a', revision: 5, policyEpoch: 'policy-a', enabled: true, visibility: 'requester', grants: [{ capability: 'group.files', resource: 'group:group-a', allowGroupAudience: false, expiresAt: 1900000000000 }], members: [{ senderId: 'sender-a', displayName: '小林', role: 'member', enabled: true }], quota, ...extra });
const snapshot = (extra: Data = {}) => ({ revision: 8, groups: [group(), group({ id: 'group-b', name: '读书小组', spaceId: 'group:group-b' })], requests: [reference], deliveries: [], nativeConnections: [{ id: 'qq-original', provider: 'qq', name: '原机器人', enabled: true, qqGuildEnabled: true }, { id: 'webhook-original', provider: 'webhook', name: 'Webhook', enabled: true }], nativeResources: { 'group.files': true, 'public.answer': true }, ...extra });
const task = (extra: Data = {}) => ({ taskId: 'task-a', revision: 7, status: 'paused', runId: 'run-a', coordinatorEpoch: 3, summary: '上次读取的完整会议结论。', outputs: [], ...extra });
const reading = (extra: Data = {}) => ({ reference, known: true, task: task(), ...extra });
const review = (command: string, input: Data, extra: Data = {}) => ({ reviewHandle: 'original-review', command, input, group: group(), ...extra });
const deferred = () => { let resolve: (value: any) => void = () => {}, reject: (reason: Error) => void = () => {}; const promise = new Promise((done, fail) => { resolve = done; reject = fail; }); return { promise, resolve, reject }; };
function freeze<T>(value: T): T { if (value && typeof value === 'object') { Object.values(value).forEach(freeze); Object.freeze(value); } return value; }

/** Execute the production component's handlers, not an imitation state machine.
 * This does not mount React Aria, render pixels, or claim visual/browser coverage. */
function fixture(initial: Data = snapshot(), extra: Data = {}) {
  const source = readFileSync(new URL('../src/GroupsWorkspace.tsx', import.meta.url), 'utf8');
  const ast = ts.createSourceFile('GroupsWorkspace.tsx', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const body = ast.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(ast).replace(/^export /, '')).join('\n');
  const code = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
  const slots: any[] = [], effects: (() => void)[] = [], calls: Data[] = [], expanded = new Set<string>();
  const documentEvents = new Map<string, Set<() => void>>(), windowEvents = new Map<string, Set<() => void>>();
  const events = (map: Map<string, Set<() => void>>) => ({ addEventListener(name: string, fn: () => void) { if (!map.has(name)) map.set(name, new Set()); map.get(name)!.add(fn); }, removeEventListener(name: string, fn: () => void) { map.get(name)?.delete(fn); } });
  const document = { visibilityState: 'visible', ...events(documentEvents) }, window = events(windowEvents);
  const responses: Data = { 'groups.snapshot': initial, 'groups.inspectTask': reading(), 'groups.discard': { closed: true }, ...extra.responses };
  let index = 0, dirty = false, tree: any, back: (() => boolean) | null = null, focused = '';
  const scroll = { scrollTop: 0, addEventListener() {}, removeEventListener() {} };
  const root = { closest: () => scroll, focus: () => { focused = 'root'; }, querySelectorAll: () => walk(tree).filter(node => node.props['data-group-object']).map(node => ({ dataset: { groupObject: node.props['data-group-object'] }, focus: () => { focused = node.props['data-group-object']; } })) };
  const useState = (initialValue: any) => { const i = index++; slots[i] ??= { value: typeof initialValue === 'function' ? initialValue() : initialValue }; return [slots[i].value, (next: any) => { const value = typeof next === 'function' ? next(slots[i].value) : next; if (!Object.is(value, slots[i].value)) { slots[i].value = value; dirty = true; } }]; };
  const useRef = (value: any) => { const i = index++; return slots[i] ??= { current: value }; };
  const useEffect = (effect: () => any, deps?: any[]) => { const i = index++, prior = slots[i]; if (!prior || !deps || deps.some((value, j) => !Object.is(value, prior.deps?.[j]))) { const slot = slots[i] = { deps, cleanup: prior?.cleanup }; effects.push(() => { slot.cleanup?.(); slot.cleanup = effect(); }); } };
  const React = { Fragment: 'Fragment', createElement: (type: any, props: any, ...children: any[]) => { if (props?.ref && type === 'section') props.ref.current = root; return { type, props: props || {}, children: children.flat(Infinity).filter(child => child !== null && child !== undefined && child !== false) }; } };
  const dependencies: Data = { React, useState, useRef, useEffect, useLayoutEffect: useEffect, document, window, ...contracts };
  for (const name of ['Accordion', 'AccordionItem', 'Button', 'CheckboxField', 'SearchField', 'SelectField', 'SettingsDialog', 'TextField', 'Tabs', 'TabList', 'Tab', 'TabPanel', 'ArrowLeft', 'ArrowUpRight', 'ChevronRight', 'FileText', 'Plus', 'RefreshCw', 'Settings2', 'ShieldCheck', 'Users']) dependencies[name] = name;
  const Component = new Function(...Object.keys(dependencies), code + '\nreturn GroupsWorkspace;')(...Object.values(dependencies));
  const props: Data = { state: { webWorkspaceResumeRevision: 1 }, data: freeze(initial), supported: (action: string) => !(extra.unsupported || []).includes(action), registerTransientBack: (handler: any) => { back = handler; }, run: async (action: string, args: Data = {}) => { calls.push({ action, args }); const response = responses[action]; return typeof response === 'function' ? response(args) : response || {}; } };
  const isHidden = (node: any, selectedTab?: string) => node.props.hidden || node.type === 'SettingsDialog' && !node.props.isOpen || node.type === 'TabPanel' && node.props.id !== selectedTab;
  function walk(node: any, inactive = false, selectedTab?: string): any[] { if (!node || typeof node !== 'object') return []; const tab = node.type === 'Tabs' ? node.props.selectedKey : selectedTab, hidden = inactive || isHidden(node, tab), collapsed = node.type === 'AccordionItem' && !expanded.has(node.props.id); return [{ ...node, visible: !hidden }, ...node.children.flatMap((child: any) => walk(child, hidden || collapsed, tab))]; }
  function text(node: any, selectedTab?: string): string { if (!node || typeof node !== 'object') return String(node ?? ''); const tab = node.type === 'Tabs' ? node.props.selectedKey : selectedTab; if (isHidden(node, tab)) return ''; const label = node.type === 'AccordionItem' ? `${node.props.title} ${node.props.description || ''}` : ''; return label + (node.type === 'AccordionItem' && !expanded.has(node.props.id) ? '' : node.children.map((child: any) => text(child, tab)).join('')); }
  const render = () => { let rounds = 0; do { assert.ok(rounds++ < 35, 'production component settles'); dirty = false; index = 0; tree = Component(props); effects.splice(0).forEach(effect => effect()); } while (dirty); };
  const find = (predicate: (node: any) => boolean, visible = true) => { const found = walk(tree).filter(node => (!visible || node.visible) && predicate(node)).at(-1); assert.ok(found, 'production control exists'); return found; };
  const button = (label: string) => find(node => node.type === 'Button' && (node.props['aria-label'] || text(node)) === label);
  const checkbox = (label: string) => find(node => node.type === 'CheckboxField' && text(node) === label);
  render();
  return { calls, responses, button, checkbox, find, scroll, get focused() { return focused; }, get text() { return text(tree); }, get view() { return tree.props['data-group-view']; },
    click(label: string) { const node = button(label); assert.ok(!node.props.disabled, `${label} is enabled`); node.props.onClick(); render(); },
    submit(label: string) { const form = find(node => node.type === 'form' && node.props['aria-label'] === label); form.props.onSubmit({ preventDefault() {} }); render(); },
    check(label: string, value = true) { const node = checkbox(label); assert.ok(!node.props.isDisabled); node.props.onChange(value); render(); },
    acknowledge() { this.check('我已核对对象、范围与影响，确认这一次操作'); },
    field(label: string, value: string) { const node = find(node => ['TextField', 'SelectField'].includes(node.type) && node.props.label === label); assert.ok(!node.props.isDisabled); node.props.onChange(value); render(); },
    value(label: string) { return find(node => ['TextField', 'SelectField'].includes(node.type) && node.props.label === label).props.value; },
    tab(id: string) { const tab = find(node => node.type === 'Tab' && node.props.id === id); assert.ok(!tab.props.isDisabled); find(node => node.type === 'Tabs').props.onSelectionChange(id); render(); },
    expand(id: string) { expanded.add(id); render(); },
    push(next: Data) { props.data = freeze(next); render(); },
    resume() { props.state = { ...props.state, webWorkspaceResumeRevision: props.state.webWorkspaceResumeRevision + 1 }; render(); },
    visibility(value: string) { document.visibilityState = value; [...(documentEvents.get('visibilitychange') || [])].forEach(fn => fn()); render(); },
    pagehide() { [...(windowEvents.get('pagehide') || [])].forEach(fn => fn()); render(); },
    back() { const handled = back?.(); render(); return handled; },
    unmount() { slots.forEach(slot => slot?.cleanup?.()); },
    async flush() { for (let i = 0; i < 16; i++) { await Promise.resolve(); if (dirty) render(); } }
  };
}
async function openRequest(f: ReturnType<typeof fixture>) { await f.flush(); f.click('打开群空间：产品讨论'); f.click('读取群请求：整理会议结论'); await f.flush(); }

test('group browsing is read-only and original request/task navigation uses only the exact request identity', async () => {
  const f = fixture(); await f.flush(); assert.deepEqual(f.calls, [{ action: 'groups.snapshot', args: {} }]);
  f.click('打开群空间：产品讨论'); assert.match(f.text, /结果受众：原群成员/); assert.doesNotMatch(f.text, /request-a|task-a/);
  f.click('读取群请求：整理会议结论'); await f.flush(); assert.deepEqual(f.calls.at(-1), { action: 'groups.inspectTask', args: { requestId: 'request-a' } });
  assert.match(f.text, /上次读取的完整会议结论/); assert.doesNotMatch(f.text, /request-a|run-a/);
  f.expand('request-identity'); assert.match(f.text, /request-a/); assert.match(f.text, /run-a/);
  f.click('打开原任务与审批'); await f.flush(); assert.deepEqual(f.calls.at(-1), { action: 'groups.openTask', args: { requestId: 'request-a' } });
  assert.equal(f.calls.some(call => /create|confirm|review/.test(call.action)), false);
});

test('request reading, tab position, list position and source focus survive ordinary navigation without reissuing the request', async () => {
  const f = fixture(); await f.flush(); f.scroll.scrollTop = 63; f.click('打开群空间：产品讨论'); f.scroll.scrollTop = 210;
  f.click('读取群请求：整理会议结论'); await f.flush(); f.scroll.scrollTop = 540; f.tab('members'); f.scroll.scrollTop = 90; f.tab('tasks');
  assert.match(f.text, /上次读取的完整会议结论/); assert.equal(f.scroll.scrollTop, 540); assert.equal(f.calls.filter(call => call.action === 'groups.inspectTask').length, 1);
  f.back(); assert.equal(f.scroll.scrollTop, 210); assert.equal(f.focused, JSON.stringify(['group-a', 'group:group-a', 'request-a', 'task-a']));
  f.back(); assert.equal(f.scroll.scrollTop, 63); assert.equal(f.focused, 'group-a');
});

test('unknown and malformed readings retain last-known content but never enable old task authority', async () => {
  const f = fixture(); await openRequest(f);
  f.responses['groups.inspectTask'] = { reference, known: false }; f.click('重新读取原状态'); await f.flush();
  assert.match(f.text, /原任务当前状态尚未知/); assert.match(f.text, /上次读取的完整会议结论/); assert.equal(f.button('核对并继续原任务').props.disabled, true);
  f.responses['groups.inspectTask'] = reading({ reference: { ...reference, spaceId: 'private' }, task: task({ summary: 'wrong space content' }) }); f.click('重新读取原状态'); await f.flush();
  assert.match(f.text, /原任务当前状态读取失败/); assert.doesNotMatch(f.text, /wrong space content/); assert.equal(f.button('核对并停止原运行').props.disabled, true);
  f.responses['groups.inspectTask'] = reading({ task: task({ revision: 6, summary: 'older content' }) }); f.click('重新读取原状态'); await f.flush();
  assert.match(f.text, /上次读取的完整会议结论/); assert.doesNotMatch(f.text, /older content/);
});

test('approval, uncertain effect, private scope and audience remain visible outside disclosures', async () => {
  const current = task({ status: 'awaiting_user', resultPrivate: true, outputs: [{ outputId: 'private-output', text: 'DO NOT EXPOSE OUTPUT', mimeType: 'text/plain', audience: 'group' }] });
  const f = fixture(snapshot(), { responses: { 'groups.inspectTask': reading({ task: current, presentation: { ...reference, taskRevision: 7, runId: 'run-a', coordinatorEpoch: 3, status: 'awaiting_user', approval: { message: '需要主人确认本次范围' }, recovery: { message: '外部效果尚未确认' } } }) } }); await openRequest(f);
  for (const important of ['结果受众：原群成员', '这份结果保持原私人范围', '需要主人确认本次范围', '外部效果尚未确认']) assert.ok(f.text.includes(important));
  assert.doesNotMatch(f.text, /DO NOT EXPOSE OUTPUT|核对原受众并发回|request-a/);
});

test('task mutations require a fresh native review and explicit acknowledgement, then consume only that handle once', async () => {
  const f = fixture(snapshot(), { responses: { 'groups.reviewTask': review('task.resume', { requestId: 'request-a', runId: 'run-a', coordinatorEpoch: 3, taskRevision: 7 }, { reference, task: task() }), 'groups.confirm': { confirmed: true, command: 'task.resume', result: reading({ task: task({ status: 'running', revision: 8 }) }) } } }); await openRequest(f);
  f.click('核对并继续原任务'); await f.flush(); assert.deepEqual(f.calls.at(-1), { action: 'groups.reviewTask', args: { requestId: 'request-a', intent: 'resume' } });
  assert.equal(f.button('确认继续原任务').props.disabled, true); f.acknowledge(); const confirm = f.button('确认继续原任务').props.onClick; confirm(); confirm(); await f.flush();
  assert.deepEqual(f.calls.filter(call => call.action === 'groups.confirm'), [{ action: 'groups.confirm', args: { reviewHandle: 'original-review', confirmed: true } }]); assert.match(f.text, /正在执行/);
});

test('known delivery states including unknown and failure remain query-only on their original delivery IDs', async () => {
  for (const state of ['unknown', 'accepted', 'failed']) {
    const delivery = { ...reference, deliveryId: 'delivery-original', outputId: 'output-a', audience: 'group', state };
    const f = fixture(snapshot({ deliveries: [delivery] }), { responses: { 'groups.inspectTask': reading({ task: task({ status: 'completed', outputs: [{ outputId: 'output-a', text: '全文结果', mimeType: 'text/plain', audience: 'group' }] }) }), 'groups.inspectDelivery': delivery } }); await openRequest(f);
    assert.doesNotMatch(f.text, /核对原受众并发回/); f.click('查询原投递，不重新发送'); await f.flush();
    assert.deepEqual(f.calls.at(-1), { action: 'groups.inspectDelivery', args: { deliveryId: 'delivery-original' } }); assert.equal(f.calls.some(call => call.action === 'groups.confirm'), false);
  }
});

test('a delivery confirmation with uncertain outcome cannot be resubmitted even before a delivery record arrives', async () => {
  const output = { outputId: 'output-a', text: '完整原产物', mimeType: 'text/plain', audience: 'group' }, completed = task({ status: 'completed', outputs: [output] });
  const f = fixture(snapshot(), { responses: { 'groups.inspectTask': reading({ task: completed }), 'groups.reviewDelivery': review('delivery.send', { requestId: 'request-a', outputId: 'output-a', runId: 'run-a', coordinatorEpoch: 3, taskRevision: 7 }, { reference, task: completed }), 'groups.confirm': () => Promise.reject(new Error('原投递回执尚未取得')) } }); await openRequest(f);
  f.click('核对原受众并发回'); await f.flush(); assert.deepEqual(f.calls.at(-1), { action: 'groups.reviewDelivery', args: { requestId: 'request-a', outputId: 'output-a' } }); assert.match(f.text, /发往原群：产品讨论/);
  f.acknowledge(); f.click('确认发回原产物'); await f.flush(); assert.match(f.text, /投递结果待核对/); assert.doesNotMatch(f.text, /核对原受众并发回/);
  f.click('刷新原群记录'); await f.flush(); assert.doesNotMatch(f.text, /核对原受众并发回/); assert.equal(f.calls.filter(call => call.action === 'groups.confirm').length, 1);
});

test('member forms preserve ordinary drafts and cannot reuse a review after source group version changes', async () => {
  const f = fixture(snapshot(), { responses: { 'groups.reviewMember': (args: Data) => review('member.set', args) } }); await f.flush(); f.click('打开群空间：产品讨论'); f.tab('members'); f.click('编辑群成员：小林');
  assert.equal(f.find(node => node.type === 'TextField' && node.props.label === '原平台成员身份').props.isDisabled, true);
  f.field('成员显示名称', '小林的新名字'); f.click('返回并保留草稿'); f.tab('tasks'); f.tab('members'); f.click('编辑群成员：小林'); assert.equal(f.value('成员显示名称'), '小林的新名字');
  f.submit('编辑群成员'); await f.flush(); assert.deepEqual(f.calls.at(-1), { action: 'groups.reviewMember', args: { id: 'group-a', revision: 5, policyEpoch: 'policy-a', senderId: 'sender-a', displayName: '小林的新名字', role: 'member', enabled: true } });
  f.acknowledge(); f.push(snapshot({ revision: 9, groups: [group({ revision: 6 })] })); await f.flush(); assert.match(f.text, /旧审阅不能继续使用/); assert.equal(f.button('核对成员变更').props.disabled, true); assert.equal(f.value('成员显示名称'), '小林的新名字');
  assert.ok(f.calls.some(call => call.action === 'groups.discard' && call.args.reviewHandle === 'original-review')); assert.equal(f.calls.some(call => call.action === 'groups.confirm'), false);
  f.click('重新读取当前设置'); assert.equal(f.value('成员显示名称'), '小林');
});

test('create form keeps its draft on return, clears incompatible platform identities, and waits for a native review', async () => {
  const f = fixture(snapshot(), { responses: { 'groups.reviewCreate': (args: Data) => review('group.create', args, { group: null, connection: { name: '原机器人' } }) } }); await f.flush(); f.click('新建群空间');
  f.field('群空间名称', '新的群空间'); f.field('机器人连接', 'qq-original'); f.field('群场景', 'qq_guild_channel'); f.field('原子频道 channel_id', 'channel-a');
  assert.equal(f.button('核对并创建').props.disabled, true); f.submit('新建群空间'); assert.equal(f.calls.some(call => call.action === 'groups.reviewCreate'), false);
  f.field('原频道 guild_id', 'guild-a'); f.click('返回并保留草稿'); f.click('新建群空间'); assert.equal(f.value('群空间名称'), '新的群空间'); assert.equal(f.value('原频道 guild_id'), 'guild-a');
  f.field('机器人连接', 'webhook-original'); assert.equal(f.value('群场景'), 'group'); assert.equal(f.value('原群 roomId'), ''); f.field('原群 roomId', 'room-original');
  f.submit('新建群空间'); await f.flush(); assert.deepEqual(f.calls.at(-1), { action: 'groups.reviewCreate', args: { name: '新的群空间', accountId: 'webhook-original', roomId: 'room-original', conversationKind: 'group', conversationParentId: '' } });
  assert.match(f.text, /空间创建后保持关闭/); assert.equal(f.button('确认保存').props.disabled, true); assert.equal(f.calls.some(call => call.action === 'groups.confirm'), false);
});

test('policy review retains exact group resource and versions, while quotas and diagnostics stay auxiliary', async () => {
  const f = fixture(snapshot(), { responses: { 'groups.reviewPolicy': (args: Data) => review('policy.set', { ...args, grants: args.grants.map((grant: Data) => ({ ...grant, expiresAt: 1900000000000 })) }) } }); await f.flush(); f.click('打开群空间：产品讨论'); f.tab('policy');
  assert.match(f.text, /仅原请求发起人受众/); assert.doesNotMatch(f.text, /每份媒体大小上限/); f.click('编辑能力与额度');
  assert.equal(f.checkbox('允许把这项能力的结果发到群里').props.isSelected, false); f.submit('编辑能力与额度'); await f.flush();
  const call = f.calls.find(call => call.action === 'groups.reviewPolicy')!; assert.equal(call.args.id, 'group-a'); assert.equal(call.args.revision, 5); assert.equal(call.args.policyEpoch, 'policy-a'); assert.deepEqual(call.args.grants, [{ capability: 'group.files', resource: 'group:group-a', allowGroupAudience: false, hours: 24 }]);
  assert.match(f.text, /旧任务授权不自动迁移/); assert.equal(f.calls.some(call => call.action === 'groups.confirm'), false);
});

test('returning from the original task refreshes the original reference without discarding reading position or reusing review authority', async () => {
  const f = fixture(snapshot(), { responses: { 'groups.reviewTask': review('task.resume', { requestId: 'request-a' }, { reference, task: task() }) } }); await openRequest(f); f.scroll.scrollTop = 440;
  f.click('核对并继续原任务'); await f.flush(); f.acknowledge(); f.resume(); await f.flush();
  assert.deepEqual(f.calls.filter(call => call.action === 'groups.inspectTask').at(-1), { action: 'groups.inspectTask', args: { requestId: 'request-a' } }); assert.match(f.text, /上次读取的完整会议结论/); assert.equal(f.scroll.scrollTop, 440);
  assert.equal(f.calls.some(call => call.action === 'groups.confirm'), false); assert.ok(f.calls.some(call => call.action === 'groups.discard'));
});

test('backgrounding rejects a late review handle and resuming reads the original request rather than repeating an operation', async () => {
  const pending = deferred(); const f = fixture(snapshot(), { responses: { 'groups.reviewTask': () => pending.promise } }); await openRequest(f); f.click('核对并继续原任务'); f.visibility('hidden');
  pending.resolve(review('task.resume', { requestId: 'request-a' }, { reference, task: task() })); await f.flush();
  assert.ok(f.calls.some(call => call.action === 'groups.discard' && call.args.reviewHandle === 'original-review')); assert.equal(f.calls.some(call => call.action === 'groups.confirm'), false);
  f.visibility('visible'); await f.flush(); assert.equal(f.calls.filter(call => call.action === 'groups.reviewTask').length, 1); assert.equal(f.calls.filter(call => call.action === 'groups.inspectTask').length, 2);
});

test('a removed original request cannot be restored by an in-flight task receipt', async () => {
  const pending = deferred(); const f = fixture(snapshot(), { responses: { 'groups.inspectTask': () => pending.promise } }); await f.flush(); f.click('打开群空间：产品讨论'); f.click('读取群请求：整理会议结论');
  f.push(snapshot({ revision: 9, requests: [] })); pending.resolve(reading()); await f.flush();
  assert.doesNotMatch(f.text, /上次读取的完整会议结论|打开原任务与审批/); assert.match(f.text, /原请求不在当前群记录中/);
});


test('native nested delivery output identity survives body-free inspection and a newer delivered snapshot wins', async () => {
  const output = { outputId: 'output-native', text: '原文', mimeType: 'text/plain', audience: 'group' };
  const delivery = { requestId: 'request-a', taskId: 'task-a', deliveryId: 'delivery-native', output, audience: 'group', state: 'unknown' };
  const nativeReference = { ...reference, senderId: undefined };
  const f = fixture(snapshot({ requests: [nativeReference], deliveries: [delivery] }), { responses: { 'groups.inspectTask': reading({ reference: nativeReference, task: task({ status: 'completed', outputs: [output] }) }), 'groups.inspectDelivery': { requestId: 'request-a', taskId: 'task-a', deliveryId: 'delivery-native', audience: 'group', state: 'unknown' } } });
  await openRequest(f); assert.doesNotMatch(f.text, /核对原受众并发回|undefined/); f.click('查询原投递，不重新发送'); await f.flush();
  assert.doesNotMatch(f.text, /核对原受众并发回/);
  f.push(snapshot({ revision: 9, requests: [nativeReference], deliveries: [{ ...delivery, state: 'delivered', platformMessageId: 'message-native' }] }));
  assert.match(f.text, /已送达/); assert.doesNotMatch(f.text, /投递结果未知/);
});
