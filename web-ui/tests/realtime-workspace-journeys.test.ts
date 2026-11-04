import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import { mergeRealtimeSnapshot, realtimeReviewCurrent, realtimeStage } from '../src/realtime-contract.ts';

// Production JSX and handlers, with hook/lifecycle/control doubles. These tests
// do not claim DOM, native audio/video, service connectivity or visual approval.
type Data = Record<string, any>;
const future = () => Date.now() + 60000;
const base = (patch: Data = {}) => ({ snapshotRevision: 1, sessionId: 'original-session', session: { state: 'idle', microphoneEnabled: false, cameraEnabled: false }, foregroundOwner: true, captureCoordinatorReady: true, microphonePermission: true, cameraPermission: true, naturalVoiceSupported: true, naturalVoiceConfigured: true, naturalVoiceConfiguration: { endpointUrl: 'wss://voice.example.test/realtime', model: 'voice-model', voice: 'original-voice' }, configuration: { revision: 7 }, capabilities: { voiceTaskHost: true, endpointProviderSupported: true }, voiceTurn: { portReady: true }, voiceTask: { available: true }, ...patch });
const invitation = (patch: Data = {}) => base({ invitationState: 'awaiting_owner', invitation: { id: 'original-invitation', expiresAt: future(), source: 'provider_invitation', displayName: '原房间邀请', serverUrl: 'wss://room.example.test', tokenEndpoint: 'https://provider.example.test/token', agentName: 'original-agent' }, ...patch });
const call = (patch: Data = {}) => base({ session: { state: 'connected', transport: 'openai_realtime_ga_websocket', microphoneEnabled: true, cameraEnabled: false }, ...patch });
const review = (command: string, details: Data, extra: Data = {}) => ({ reviewHandle: 'original-review', command, details, expiresAt: future(), ...extra });
const deferred = () => { let resolve: (value: any) => void = () => {}, reject: (reason: Error) => void = () => {}; const promise = new Promise((done, fail) => { resolve = done; reject = fail; }); return { promise, resolve, reject }; };
function frozen<T>(value: T): T { if (value && typeof value === 'object') { Object.values(value).forEach(frozen); Object.freeze(value); } return value; }
function fixture(snapshot: Data = base(), extra: Data = {}) {
  const source = readFileSync(new URL('../src/RealtimeWorkspace.tsx', import.meta.url), 'utf8');
  const ast = ts.createSourceFile('RealtimeWorkspace.tsx', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const body = ast.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(ast).replace(/^export /, '')).join('\n');
  const code = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
  frozen(snapshot);
  const slots: any[] = [], effects: (() => void)[] = [], calls: Data[] = [], expanded = new Set<string>();
  const documentEvents = new Map<string, Set<() => void>>(), windowEvents = new Map<string, Set<() => void>>();
  const events = (map: Map<string, Set<() => void>>) => ({ addEventListener: (name: string, fn: () => void) => { if (!map.has(name)) map.set(name, new Set()); map.get(name)!.add(fn); }, removeEventListener: (name: string, fn: () => void) => map.get(name)?.delete(fn) });
  const document = { visibilityState: 'visible', ...events(documentEvents) }, window = events(windowEvents);
  const responses: Data = { 'realtime.snapshot': snapshot, 'realtime.confirm': { snapshot, result: { message: '原确认回执' } }, 'realtime.control': { snapshot, result: { message: '原控制回执' } }, ...extra.responses };
  let index = 0, dirty = false, tree: any, back: (() => boolean) | null = null;
  const useState = (initial: any) => { const i = index++; slots[i] ??= { value: typeof initial === 'function' ? initial() : initial }; return [slots[i].value, (next: any) => { const value = typeof next === 'function' ? next(slots[i].value) : next; if (!Object.is(value, slots[i].value)) { slots[i].value = value; dirty = true; } }]; };
  const useRef = (initial: any) => { const i = index++; return slots[i] ??= { current: initial }; };
  const useEffect = (effect: () => any, deps?: any[]) => { const i = index++, prior = slots[i]; if (!prior || !deps || deps.some((value, j) => !Object.is(value, prior.deps?.[j]))) { const slot = slots[i] = { deps, cleanup: prior?.cleanup }; effects.push(() => { slot.cleanup?.(); slot.cleanup = effect(); }); } };
  const React = { Fragment: 'Fragment', createElement: (type: any, props: any, ...children: any[]) => ({ type, props: props || {}, children: children.flat(Infinity).filter(child => child !== null && child !== undefined && child !== false) }) };
  const dependencies: Data = { React, useState, useRef, useEffect, document, window, mergeRealtimeSnapshot, realtimeReviewCurrent, realtimeStage };
  for (const name of ['Accordion', 'AccordionItem', 'Button', 'CheckboxField', 'SelectField', 'SettingsDialog', 'TextField', 'ProgressBar', 'Mic', 'Phone', 'RefreshCw', 'Settings2', 'Video', 'Volume2']) dependencies[name] = name;
  const Component = new Function(...Object.keys(dependencies), code + '\nreturn RealtimeWorkspace;')(...Object.values(dependencies));
  const props: Data = { state: { features: { realtime: snapshot }, ...extra.state }, registerTransientBack: (handler: any) => { back = handler; }, run: async (action: string, args: Data = {}) => { calls.push({ action, args }); const response = responses[action]; return typeof response === 'function' ? response(args) : response || {}; } };
  const hidden = (node: any) => node.props.hidden || node.type === 'SettingsDialog' && !node.props.isOpen;
  const collapsed = (node: any) => node.type === 'AccordionItem' && !expanded.has(node.props.id);
  const walk = (node: any, inactive = false): any[] => !node || typeof node !== 'object' ? [] : [{ ...node, visible: !inactive && !hidden(node) }, ...node.children.flatMap((child: any) => walk(child, inactive || hidden(node) || collapsed(node)))];
  const content = (node: any, visible = false): string => !node || typeof node !== 'object' ? String(node ?? '') : visible && hidden(node) ? '' : (node.type === 'AccordionItem' ? `${node.props.title} ${node.props.description || ''}` : '') + (visible && collapsed(node) ? '' : node.children.map((child: any) => content(child, visible)).join(''));
  const render = () => { let rounds = 0; do { assert.ok(rounds++ < 30, 'production component settles'); dirty = false; index = 0; tree = Component(props); effects.splice(0).forEach(effect => effect()); } while (dirty); };
  const find = (predicate: (node: any) => boolean, visible = true) => { const node = walk(tree).filter(node => (!visible || node.visible) && predicate(node)).at(-1); assert.ok(node, 'production control exists'); return node; };
  const button = (label: string) => find(node => node.type === 'Button' && (node.props['aria-label'] || content(node)) === label);
  const checkbox = (label: string) => find(node => node.type === 'CheckboxField' && content(node) === label);
  render();
  return { calls, responses, button, checkbox, find, get text() { return content(tree, true); },
    click(label: string) { const node = button(label); assert.ok(!node.props.disabled, `${label} is enabled`); node.props.onClick(); render(); },
    check(label: string, value = true) { const node = checkbox(label); assert.ok(!node.props.isDisabled, `${label} is enabled`); node.props.onChange(value); render(); },
    acknowledge() { const node = checkbox('我已核对本次范围，确认执行上述操作'); assert.ok(!node.props.isDisabled); node.props.onChange(true); render(); },
    field(label: string, value: string) { const node = find(node => ['TextField', 'SelectField'].includes(node.type) && node.props.label === label); assert.ok(!node.props.isDisabled); node.props.onChange(value); render(); },
    value(label: string) { return find(node => node.type === 'TextField' && node.props.label === label, false).props.value; },
    expand(id: string) { expanded.add(id); render(); },
    push(next: Data) { props.state = { ...props.state, features: { realtime: frozen(next) } }; render(); },
    state(next: Data) { props.state = { ...props.state, ...next }; render(); },
    visibility(value: string) { document.visibilityState = value; [...(documentEvents.get('visibilitychange') || [])].forEach(fn => fn()); render(); },
    pagehide() { [...(windowEvents.get('pagehide') || [])].forEach(fn => fn()); render(); },
    back() { const handled = back?.(); render(); return handled; },
    unmount() { slots.forEach(slot => slot?.cleanup?.()); },
    async flush() { for (let i = 0; i < 12; i++) { await Promise.resolve(); if (dirty) render(); } }
  };
}

test('realtime starts with one read-only snapshot and focused session, with configuration and task recovery disclosed on intent', async () => {
  const f = fixture(); await f.flush();
  assert.deepEqual(f.calls, [{ action: 'realtime.snapshot', args: {} }]);
  assert.match(f.text, /开始一次自然语音对话/); assert.match(f.text, /语音目标与原任务/);
  assert.doesNotMatch(f.text, /实时语音 WSS 端点|手动房间设置|核对并开始一轮语音|读取已保存的任务/);
  f.expand('voice-service'); assert.match(f.text, /系统听写转成文字后交给当前文本模型/);
  f.expand('room-service'); assert.match(f.text, /手动房间设置/);
  f.expand('saved-voice-tasks'); assert.match(f.text, /读取已保存的任务/);
  assert.equal(f.calls.length, 1);
});

test('preparing natural voice does not connect, request permissions, capture or submit a task', async () => {
  const f = fixture(); await f.flush(); f.click('准备自然语音对话'); await f.flush();
  assert.deepEqual(f.calls.at(-1), { action: 'realtime.prepare', args: { kind: 'natural' } });
  assert.match(f.text, /核对采集范围并接受后才连接/);
  assert.equal(f.calls.length, 2);
});

test('invitation capture is off by default and exact native review plus acknowledgement precedes acceptance', async () => {
  const snap = invitation(); const f = fixture(snap, { responses: { 'realtime.reviewInvitation': (args: Data) => review('acceptInvitation', { id: args.invitationId, microphone: args.microphone, camera: args.camera, serverUrl: snap.invitation.serverUrl }) } }); await f.flush();
  assert.equal(f.checkbox('发送本机麦克风声音').props.isSelected, false); assert.equal(f.checkbox('打开并发送本机摄像头').props.isSelected, false);
  assert.match(f.text, /https:\/\/provider.example.test\/token/); assert.match(f.text, /语音服务可能计费/);
  f.click('核对并接受邀请'); await f.flush();
  assert.deepEqual(f.calls.at(-1), { action: 'realtime.reviewInvitation', args: { invitationId: 'original-invitation', microphone: false, camera: false } });
  assert.equal(f.button('确认接受并连接本次通话').props.disabled, true);
  f.acknowledge(); f.click('确认接受并连接本次通话'); await f.flush();
  assert.deepEqual(f.calls.at(-1), { action: 'realtime.confirm', args: { reviewHandle: 'original-review' } });
  assert.equal(f.calls.filter(c => c.action === 'realtime.confirm').length, 1);
  assert.equal(f.calls.some(c => /request.*Permission|tasks\.create/.test(c.action)), false);
});

test('permission prompts are explicit and replacement invitation resets selected capture and invalidates old review', async () => {
  const first = invitation({ microphonePermission: false }); const f = fixture(first); await f.flush();
  f.check('发送本机麦克风声音'); assert.equal(f.button('核对并接受邀请').props.disabled, true);
  f.click('授予麦克风权限'); await f.flush(); assert.deepEqual(f.calls.at(-1), { action: 'realtime.requestMicrophonePermission', args: {} });
  const allowed = { ...first, snapshotRevision: 2, microphonePermission: true }; f.push(allowed);
  f.responses['realtime.reviewInvitation'] = review('acceptInvitation', { id: 'original-invitation', microphone: true, camera: false });
  f.click('核对并接受邀请'); await f.flush(); f.acknowledge();
  f.push({ ...allowed, snapshotRevision: 3, invitation: { ...first.invitation, id: 'next-invitation' } });
  assert.equal(f.checkbox('发送本机麦克风声音').props.isSelected, false); assert.equal(f.button('确认接受并连接本次通话').props.disabled, true);
  assert.match(f.text, /原会话、配置、任务或审阅已变化/);
  f.click('返回'); await f.flush(); assert.deepEqual(f.calls.at(-1), { action: 'realtime.closeReview', args: { reviewHandle: 'original-review' } });
});

test('expired reviews stay disabled and rejection retains the exact original invitation identity', async () => {
  const f = fixture(invitation(), { responses: { 'realtime.reviewInvitation': review('acceptInvitation', { id: 'original-invitation' }, { expiresAt: Date.now() - 1 }) } }); await f.flush();
  f.click('核对并接受邀请'); await f.flush(); assert.equal(f.button('确认接受并连接本次通话').props.disabled, true);
  assert.equal(f.checkbox('我已核对本次范围，确认执行上述操作').props.isDisabled, true);
  f.click('返回'); await f.flush(); f.click('拒绝本次邀请'); await f.flush();
  assert.deepEqual(f.calls.at(-1), { action: 'realtime.control', args: { command: 'rejectInvitation', invitationId: 'original-invitation' } });
  assert.equal(f.calls.some(c => c.action === 'realtime.confirm'), false);
});

test('pending work does not block independent mute and disconnect lanes, and a late receipt cannot resurrect a disconnected call', async () => {
  const work = deferred(), mute = deferred();
  const snap = call({ transcription: { turns: [{ id: 'turn-a', revision: 2, isFinal: true, canReview: true, text: 'original words' }] } });
  const f = fixture(snap, { responses: { 'realtime.reviewTurn': () => work.promise, 'realtime.control': (args: Data) => args.command === 'mute' ? mute.promise : { snapshot: { ...snap, snapshotRevision: 9, session: { state: 'disconnected', microphoneEnabled: false } } } } }); await f.flush();
  f.click('复核并只保存目标'); f.click('关闭麦克风发送');
  assert.equal(f.button('关闭麦克风发送').props.disabled, true); assert.equal(f.button('挂断当前通话').props.disabled, false);
  assert.match(f.text, /麦克风：正在发送/);
  f.click('挂断当前通话'); await f.flush();
  assert.deepEqual(f.calls.filter(c => c.action === 'realtime.control'), [ { action: 'realtime.control', args: { command: 'mute', sessionId: 'original-session' } }, { action: 'realtime.control', args: { command: 'disconnect', sessionId: 'original-session' } } ]);
  mute.resolve({ snapshot: { ...snap, snapshotRevision: 8 } }); work.resolve({}); await f.flush();
  assert.match(f.text, /已断开/); assert.doesNotMatch(f.text, /麦克风：正在发送|挂断当前通话/);
});

test('unknown mutations require a fresh read and explicit reconciliation; stopping remains available', async () => {
  const snap = call(); const f = fixture(snap, { responses: { 'realtime.control': () => Promise.reject(new Error('原控制结果未知')) } }); await f.flush();
  f.click('关闭麦克风发送'); await f.flush();
  assert.match(f.text, /上次操作结果待核对/); assert.match(f.text, /麦克风：正在发送/);
  assert.equal(f.button('我已核对原记录，允许重新准备').props.disabled, true);
  assert.equal(f.button('挂断当前通话').props.disabled, false);
  f.click('刷新通话状态'); await f.flush(); assert.equal(f.button('我已核对原记录，允许重新准备').props.disabled, false);
  f.click('我已核对原记录，允许重新准备'); await f.flush();
  assert.equal(f.calls.filter(c => c.action === 'realtime.control').length, 1);
});

test('missing capture fields are shown as unknown rather than assumed off, and room video limitations remain visible', async () => {
  const f = fixture(call({ session: { state: 'connected', remoteParticipants: 2 }, microphonePermission: false, cameraPermission: false })); await f.flush();
  assert.match(f.text, /麦克风：状态待核对/); assert.match(f.text, /摄像头：状态待核对/);
  assert.match(f.text, /已连接房间不代表 AI 对端已就绪/); assert.match(f.text, /摄像头开启时仍会发送实际画面/);
  assert.equal(f.button('核对开启麦克风').props.disabled, true); assert.equal(f.button('核对开启摄像头').props.disabled, true);
});

test('active single-turn and task reading stop controls and release warnings are never inside collapsed service or recovery panels', async () => {
  const f = fixture(base({ voiceTurn: { active: true, id: 'original-voice-turn', status: 'listening', recognizedText: '原听写', resourceReleaseUnknown: true }, voiceTask: { available: true, readout: { active: true, readoutId: 'original-readout', state: 'speaking', resourceReleaseUnknown: true } } })); await f.flush();
  assert.match(f.text, /本轮音频释放未确认/); assert.match(f.text, /原任务朗读的音频释放未确认/);
  f.click('停止本轮听写与朗读'); await f.flush(); f.click('停止朗读，保留任务'); await f.flush();
  assert.deepEqual(f.calls.filter(c => c.action === 'realtime.control'), [ { action: 'realtime.control', args: { command: 'stopVoiceTurn', voiceTurnId: 'original-voice-turn' } }, { action: 'realtime.control', args: { command: 'interruptOutput', readoutId: 'original-readout' } } ]);
  assert.equal(f.calls.some(c => c.action === 'realtime.confirm'), false);
});

test('older uncertain transcript commits stay visible without expanding all turns and reconcile only the original commit', async () => {
  const turns = Array.from({ length: 7 }, (_, i) => ({ id: `turn-${i}`, text: `words ${i}`, revision: i + 1, isFinal: true, canReview: false, ...(i === 0 ? { commitState: 'unknown', commitId: 'original-commit' } : {}) }));
  const f = fixture(base({ transcription: { retained: true, turns } })); await f.flush();
  assert.match(f.text, /words 0/); assert.doesNotMatch(f.text, /words 1/); assert.match(f.text, /保存结果待核对/);
  f.click('核对这次原提交'); await f.flush(); assert.deepEqual(f.calls.at(-1), { action: 'realtime.reconcileTurn', args: { commitId: 'original-commit' } });
  f.click('查看全部 7 段'); assert.match(f.text, /words 1/); f.click('只看最近字幕'); assert.doesNotMatch(f.text, /words 1/); assert.match(f.text, /words 0/);
});

test('transcript save and start remain separate native reviews bound to the exact final text, session and revision', async () => {
  const turn = { id: 'original-turn', revision: 4, isFinal: true, canReview: true, text: 'Check this original project', sourceKind: 'reported_local_microphone' };
  const snap = base({ transcription: { retained: true, commitAvailable: true, reviewReady: true, turns: [turn] } });
  const f = fixture(snap, { responses: { 'realtime.reviewTurn': (args: Data) => review(args.startTask ? 'commitVoiceTask' : 'commitTurn', { sessionId: args.sessionId, turnId: args.turnId, revision: args.revision, text: turn.text, expiresAt: future() }) } }); await f.flush();
  f.click('复核并只保存目标'); await f.flush(); assert.deepEqual(f.calls.at(-1), { action: 'realtime.reviewTurn', args: { sessionId: 'original-session', turnId: 'original-turn', revision: 4, startTask: false } });
  f.click('返回'); await f.flush(); f.click('复核后交给原任务'); await f.flush();
  assert.deepEqual(f.calls.at(-1), { action: 'realtime.reviewTurn', args: { sessionId: 'original-session', turnId: 'original-turn', revision: 4, startTask: true } });
  f.acknowledge(); f.push({ ...snap, snapshotRevision: 2, transcription: { ...snap.transcription, turns: [{ ...turn, text: 'Changed text', revision: 5 }] } });
  assert.equal(f.button('确认保存目标并开始原任务').props.disabled, true); assert.equal(f.calls.some(c => c.action === 'realtime.confirm'), false);
});

test('original task inspect, start, cancellation and opening preserve binding and distinct current execution token', async () => {
  const voiceTask = { available: true, bindingId: 'original-binding', viewToken: 'view-v2', cancelToken: 'cancel-run-v3', task: { taskId: 'original-task', status: 'running', summary: '原结果' }, goal: '原语音目标', canStart: true, canCancel: true };
  const snap = base({ voiceTask }); const f = fixture(snap, { responses: { 'realtime.reviewAction': (args: Data) => review(args.command, { bindingId: args.bindingId, viewToken: args.viewToken }) } }); await f.flush();
  f.click('核对原任务状态'); await f.flush(); assert.deepEqual(f.calls.at(-1), { action: 'realtime.inspectVoiceTask', args: { bindingId: 'original-binding', viewToken: 'view-v2' } });
  f.click('核对并开始原任务'); await f.flush(); assert.deepEqual(f.calls.at(-1), { action: 'realtime.reviewAction', args: { command: 'startVoiceTask', bindingId: 'original-binding', viewToken: 'view-v2' } }); f.click('返回'); await f.flush();
  f.click('核对取消当前执行'); await f.flush(); assert.deepEqual(f.calls.at(-1), { action: 'realtime.reviewAction', args: { command: 'cancelVoiceTask', bindingId: 'original-binding', viewToken: 'cancel-run-v3' } });
  f.acknowledge(); f.push({ ...snap, snapshotRevision: 2, voiceTask: { ...voiceTask, cancelToken: 'cancel-run-v4' } }); assert.equal(f.button('确认停止原任务当前执行').props.disabled, true);
  f.click('返回'); await f.flush(); f.click('查看原任务与产物'); await f.flush(); assert.deepEqual(f.calls.at(-1), { action: 'tasks.open', args: { id: 'original-task' } });
});

test('saved voice task recovery is explicit and uses its original task id without task creation', async () => {
  const f = fixture(base({ voiceTask: { available: true, recentTasks: [{ taskId: 'saved-original', goal: '保留的原目标', status: 'awaiting_user' }] } })); await f.flush();
  assert.doesNotMatch(f.text, /保留的原目标/); f.expand('saved-voice-tasks'); f.click('读取已保存的任务'); await f.flush();
  assert.deepEqual(f.calls.at(-1), { action: 'realtime.listVoiceTasks', args: {} }); f.click('恢复这项原任务视图'); await f.flush();
  assert.deepEqual(f.calls.at(-1), { action: 'realtime.recoverVoiceTask', args: { taskId: 'saved-original' } });
  assert.equal(f.calls.some(c => c.action === 'realtime.confirm' || c.action.startsWith('tasks.')), false);
});

test('configuration review clears credential fields, preserves ordinary draft and stays bound to the native configuration revision', async () => {
  const f = fixture(base(), { responses: { 'realtime.reviewConfiguration': review('configureNaturalVoice', { configurationRevision: 7, endpointUrl: 'wss://draft.example.test', model: 'draft-model', voice: 'draft-voice' }) } }); await f.flush();
  f.expand('voice-service'); f.click('自然语音设置'); f.field('实时语音 WSS 端点', ' wss://draft.example.test '); f.field('服务实际支持的语音模型', 'draft-model'); f.field('声音标识', 'draft-voice'); f.field('服务认证凭据', 'fixture-only-secret');
  f.click('核对并保存设置'); await f.flush();
  assert.deepEqual(f.calls.at(-1), { action: 'realtime.reviewConfiguration', args: { kind: 'natural', endpointUrl: 'wss://draft.example.test', model: 'draft-model', voice: 'draft-voice', transcriptionModel: '', authenticationToken: 'fixture-only-secret' } });
  assert.equal(f.value('服务认证凭据'), ''); assert.equal(f.value('服务实际支持的语音模型'), 'draft-model'); assert.doesNotMatch(f.text, /fixture-only-secret/);
  f.acknowledge(); f.push(base({ snapshotRevision: 2, configuration: { revision: 8 } })); assert.equal(f.button('确认保存自然语音服务').props.disabled, true);
  assert.equal(f.back(), true); await f.flush(); assert.equal(f.value('服务认证凭据'), ''); assert.equal(f.value('服务实际支持的语音模型'), 'draft-model');
});

test('hide and resume invalidate outstanding review and clear keys without automatic permissions, connection or task replay', async () => {
  const f = fixture(base(), { responses: { 'realtime.reviewConfiguration': review('configureNaturalVoice', { configurationRevision: 7 }) } }); await f.flush();
  f.expand('voice-service'); f.click('自然语音设置'); f.field('服务认证凭据', 'fixture-only-secret'); f.field('服务实际支持的语音模型', 'ordinary-draft');
  f.visibility('hidden'); assert.equal(f.value('服务认证凭据'), ''); assert.equal(f.value('服务实际支持的语音模型'), 'ordinary-draft');
  f.visibility('visible'); await f.flush(); f.click('核对并保存设置'); await f.flush(); f.acknowledge();
  f.state({ webWorkspaceResumeRevision: 1 }); await f.flush(); assert.deepEqual(f.calls.filter(c => c.action === 'realtime.closeReview').at(-1), { action: 'realtime.closeReview', args: { reviewHandle: 'original-review' } });
  assert.equal(f.calls.filter(c => c.action === 'realtime.snapshot').length, 3);
  assert.equal(f.calls.some(c => c.action === 'realtime.confirm' || /Permission|\.prepare/.test(c.action)), false);
});

test('pagehide during a pending mutation retains unknown outcome and rejects the late visible receipt', async () => {
  const pending = deferred(); const f = fixture(base(), { responses: { 'realtime.prepare': () => pending.promise } }); await f.flush();
  f.click('准备自然语音对话'); f.pagehide(); pending.resolve({ snapshot: invitation({ snapshotRevision: 5 }), result: { message: 'late accepted' } }); await f.flush();
  assert.match(f.text, /页面离开时原操作仍在等待回执/); assert.match(f.text, /上次操作结果待核对/); assert.doesNotMatch(f.text, /原房间邀请|late accepted/);
  assert.equal(f.calls.filter(c => c.action === 'realtime.prepare').length, 1);
});

test('opening the original task invalidates current review and unmount ignores late reads', async () => {
  const pending = deferred(); const f = fixture(invitation(), { responses: { 'realtime.reviewInvitation': review('acceptInvitation', { id: 'original-invitation' }) } }); await f.flush();
  f.click('核对并接受邀请'); await f.flush(); f.state({ webWorkspaceTaskOpen: true }); await f.flush();
  assert.equal(f.calls.filter(c => c.action === 'realtime.closeReview').length, 1); assert.equal(f.calls.some(c => c.action === 'realtime.confirm'), false);
  f.state({ webWorkspaceTaskOpen: false }); f.responses['realtime.snapshot'] = () => pending.promise; f.click('刷新通话状态'); f.unmount();
  pending.resolve(call({ snapshotRevision: 20 })); await f.flush(); assert.doesNotMatch(f.text, /麦克风：正在发送/);
});
