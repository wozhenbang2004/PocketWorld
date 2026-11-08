import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import * as harnessCapabilities from '../src/harness-capabilities.ts';

// Invoke production JSX and handlers with explicit lifecycle/bridge doubles.
// These are contract checks, not a DOM, browser, native SSH or visual review.
type Node = { type: string; props: any; children: any[] };
const source = readFileSync(new URL('../src/ConnectionWorkspace.tsx', import.meta.url), 'utf8');
const code = ts.transpileModule(source.replace(/^import .*;\n/gm, '').replace('export default function ', 'function '), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None, jsx: ts.JsxEmit.React, jsxFactory: 'h', jsxFragmentFactory: 'Fragment' },
}).outputText;
const profile = { id: 'host-a', name: 'Personal host', host: 'host.example.invalid', port: 22, username: 'owner', workspace: '/home/owner/work', bindingHash: 'a'.repeat(64), credentialsConfigured: true, hostKeyTrusted: true, hostFingerprint: 'SHA256:exact-original-host-key', baseUrl: 'https://companion.example.invalid', hasToken: true };
const version = 'sha256:' + 'b'.repeat(64);
const textOf = (node: any): string => node == null || node === false ? '' : typeof node === 'object' ? node.children.map(textOf).join('') : String(node);
function nodes(node: any, predicate: (node: Node) => boolean): Node[] { return node && typeof node === 'object' ? [...(predicate(node) ? [node] : []), ...node.children.flatMap((child: any) => nodes(child, predicate))] : []; }
function fixture(component = 'ConnectionWorkspace', supplied: any = {}) {
  let slots: any[] = [], at = 0, changed = false, tree: Node, pending: (() => void)[] = [], back: (() => boolean) | null = null;
  const calls: any[] = [], deferred: any[] = []; let heldAction = ''; 
  const state: any = { tasks: [], webWorkspaceResumeRevision: 0 };
  const props: any = { domain: 'remote', state, data: { profiles: [profile], jobs: [], operations: [] }, profile, supported: () => true, visible: true, back: { current: null }, registerTransientBack: (handler: any) => { back = handler; }, ...supplied };
  const h = (type: any, properties: any, ...children: any[]): Node => ({ type: typeof type === 'function' ? type.name : type, props: properties || {}, children: children.flat(Infinity).filter(item => item !== null && item !== undefined && item !== false) });
  const useState = (initial: any) => { const i = at++; if (!slots[i]) slots[i] = { value: typeof initial === 'function' ? initial() : initial }; return [slots[i].value, (value: any) => { const next = typeof value === 'function' ? value(slots[i].value) : value; if (!Object.is(next, slots[i].value)) { slots[i].value = next; changed = true; } }]; };
  const useRef = (initial: any) => { const i = at++; return slots[i] ??= { current: initial }; };
  const useEffect = (effect: () => any, dependencies: any[]) => { const i = at++, previous = slots[i]; if (!previous || dependencies.some((item, j) => !Object.is(item, previous.dependencies[j]))) { const slot = slots[i] = { dependencies, cleanup: previous?.cleanup }; pending.push(() => { slot.cleanup?.(); slot.cleanup = effect(); }); } };
  const useDataSettingsJourney = (initial = 'list') => { const [view, set] = useState(initial), root = useRef(null); return { view, root, open: set, back: () => { if (view === 'list') return false; set('list'); return true; } }; };
  const bridge = { getState: () => ({ view: { section: props.domain } }), getDocumentEpoch: () => 1, subscribe: () => () => {} };
  const document = { visibilityState: 'visible', addEventListener() {}, removeEventListener() {}, activeElement: null };
  const window = { addEventListener() {}, removeEventListener() {} };
  props.run = (action: string, args: any) => {
    calls.push({ action, args });
    if (action === heldAction) return new Promise(resolve => deferred.push(resolve));
    const response = supplied.respond?.(action, args);
    if (response !== undefined) return Promise.resolve(response);
    if (action === 'remote.environments') return Promise.resolve({ environments: [{ id: 'environment-a', label: 'Original environment', kind: 'local', workspace: '/home/owner/work', bindingHash: 'e'.repeat(64), workers: [{ id: 'command', available: true }] }] });
    if (action === 'remote.reviewTask') return Promise.resolve({ ...args, operationId: 'remote-original', reviewHandle: 'remote-review' });
    if (action === 'ssh.listFiles') return Promise.resolve({ path: args.path, files: [{ name: 'notes.txt', bytes: 5, directory: false }] });
    if (action === 'ssh.readFile') return Promise.resolve({ path: args.path, status: 'completed', content: 'saved', version });
    if (action === 'ssh.reviewOperation') return Promise.resolve({ operation: args.command, reviewHandle: 'original-review', ...args.arguments });
    if (action === 'ssh.confirmOperation') return new Promise(resolve => deferred.push(resolve));
    return Promise.resolve({ status: 'completed' });
  };
  const names = ['Accordion', 'AccordionItem', 'Button', 'CheckboxField', 'SearchField', 'SelectField', 'SettingsDialog', 'TextAreaField', 'TextField', 'TerminalSurface', 'RemoteProjectBaseline', 'RemoteInputFlow', 'HarnessEvidence', 'ArrowLeft', 'ArrowUp', 'ChevronRight', 'FileText', 'Folder', 'History', 'Monitor', 'Plus', 'Server', 'ShieldCheck', 'Terminal', 'Wrench'];
  const components = new Function('h', 'Fragment', 'useState', 'useRef', 'useEffect', 'useLayoutEffect', 'useDataSettingsJourney', 'bridge', 'document', 'window', ...Object.keys(harnessCapabilities), ...names, code + ';return { ConnectionWorkspace, SshWork, RemoteWork };')(
    h, 'Fragment', useState, useRef, useEffect, useEffect, useDataSettingsJourney, bridge, document, window, ...Object.values(harnessCapabilities), ...names,
  );
  const render = () => { do { changed = false; at = 0; tree = components[component](props); pending.splice(0).forEach(effect => effect()); } while (changed); return tree; };
  const find = (predicate: (node: Node) => boolean) => { const matches = nodes(tree, predicate); assert.equal(matches.length, 1, 'Expected one production element'); return matches[0]; };
  const flush = async () => { for (let i = 0; i < 8; i++) { await Promise.resolve(); if (changed) render(); } };
  render();
  return { props, calls, deferred, render, flush, find, hold(action: string) { heldAction = action; }, get tree() { return tree; }, get back() { return back; }, button: (label: string) => find(node => node.type === 'Button' && textOf(node) === label), row: (title: string) => find(node => node.type === 'ConnectionRow' && node.props.title === title), remount() { slots.forEach(slot => slot.cleanup?.()); slots = []; pending = []; render(); } };
}

test('connection discovery opens an original object before mounting its work controls', () => {
  const f = fixture();
  assert.equal(nodes(f.tree, node => node.type === 'RemoteWork').length, 0);
  assert.equal(nodes(f.tree, node => node.type === 'Button' && textOf(node) === '探测能力').length, 0);
  f.row(profile.name).props.onOpen(); f.render();
  assert.equal(f.tree.props['data-connection-view'], 'profile:' + profile.id);
  assert.equal(f.calls.length, 0, 'Opening an overview cannot probe or execute');
  f.row('进入电脑工作区').props.onOpen(); f.render();
  const work = f.find(node => node.type === 'RemoteWork');
  assert.equal(work.props.profile.bindingHash, profile.bindingHash);
  assert.equal(work.props.visible, true);
  f.back!(); f.render();
  assert.equal(f.find(node => node.type === 'RemoteWork').props.visible, false, 'Returning to overview retains the original work draft');
});

test('a changed source blocks the old connection instead of silently adopting a new target', () => {
  const f = fixture(); f.row(profile.name).props.onOpen(); f.render(); f.row('进入电脑工作区').props.onOpen(); f.render();
  f.props.data.profiles = [{ ...profile, bindingHash: 'c'.repeat(64) }]; f.render();
  assert.equal(nodes(f.tree, node => node.type === 'RemoteWork').length, 0);
  assert.match(textOf(f.tree), /旧目标仍显示/);
  assert.equal(f.calls.length, 0);
});

test('original jobs keep their full original service and operation identity', async () => {
  const job = { id: 'job-1', profileId: profile.id, operationId: 'operation-1', serviceBase: 'https://original.example.invalid', profileBinding: 'd'.repeat(64), status: 'running' };
  const f = fixture('ConnectionWorkspace', { data: { profiles: [profile], jobs: [job], operations: [] } });
  f.row('电脑上的原任务').props.onOpen(); f.render(); f.button('请求停止原任务').props.onClick(); await f.flush();
  assert.deepEqual(f.calls[0], { action: 'remote.cancelJob', args: { profileId: profile.id, jobId: job.id, jobOperationId: job.operationId, serviceBase: job.serviceBase, _profileBinding: job.profileBinding } });
});

test('SSH file browsing keeps a single file editor and the original version review', async () => {
  const f = fixture('SshWork', { domain: 'ssh' });
  f.row('工作区文件').props.onOpen(); f.render(); assert.equal(f.calls.length, 0);
  f.button('读取目录').props.onClick(); await f.flush(); f.row('notes.txt').props.onOpen(); await f.flush();
  assert.deepEqual(f.calls[1], { action: 'ssh.readFile', args: { profileId: profile.id, _profileBinding: profile.bindingHash, path: 'notes.txt' } });
  assert.equal(nodes(f.tree, node => node.type === 'ConnectionRow' && node.props.title === 'notes.txt').length, 0, 'The file list yields to the focused editor');
  f.find(node => node.type === 'TextAreaField' && node.props.label === '原文件内容').props.onChange('draft'); f.render();
  f.button('审阅本次文件修改').props.onClick(); await f.flush();
  assert.deepEqual(f.calls[2], { action: 'ssh.reviewOperation', args: { command: 'writeFile', arguments: { profileId: profile.id, _profileBinding: profile.bindingHash, path: 'notes.txt', content: 'draft', expectedVersion: version, acceptCooperativeWrite: true } } });
  assert.equal(f.button('确认执行一次').props.disabled, true);
  f.find(node => node.type === 'CheckboxField').props.onChange(true); f.render(); f.button('确认执行一次').props.onClick(); f.render();
  assert.deepEqual(f.calls[3], { action: 'ssh.confirmOperation', args: { reviewHandle: 'original-review' } });
  f.remount();
  assert.match(textOf(f.tree), /有原操作结果尚待核对/);
  assert.equal(f.find(node => node.type === 'TextAreaField' && node.props.label === '原文件内容').props.value, 'draft');
  assert.equal(f.button('审阅本次文件修改').props.disabled, true, 'A retained draft must be reread and cannot replay an uncertain save');
});

test('discarding a changed SSH file is local and never sends a write', async () => {
  const f = fixture('SshWork', { domain: 'ssh' }); f.row('工作区文件').props.onOpen(); f.render(); f.button('读取目录').props.onClick(); await f.flush(); f.row('notes.txt').props.onOpen(); await f.flush();
  f.find(node => node.type === 'TextAreaField' && node.props.label === '原文件内容').props.onChange('unsaved'); f.render();
  f.props.back.current(); f.render();
  assert.equal(f.find(node => node.type === 'SettingsDialog').props.title, '放弃未保存的文件修改？');
  f.button('放弃这些修改').props.onClick(); f.render();
  assert.equal(f.calls.some(call => call.action === 'ssh.confirmOperation'), false);
  assert.equal(nodes(f.tree, node => node.type === 'TextAreaField' && node.props.label === '原文件内容').length, 0);
});


test('connection overview exposes only the selected original pending operations', async () => {
  const f = fixture('ConnectionWorkspace', { data: { profiles: [profile], jobs: [], operations: [
    { profileId: profile.id, operationId: 'original-pending', worker: 'Original worker', status: 'unknown' },
    { profileId: 'another-host', operationId: 'unrelated', worker: 'Unrelated worker', status: 'unknown' },
  ] } });
  f.row(profile.name).props.onOpen(); f.render();
  assert.equal(f.row('进入电脑工作区').props.detail, '继续原工作，或核对后开始下一项');
  assert.match(textOf(f.tree), /有原提交待核对/);
  assert.match(f.row('Original worker').props.detail, /original-pending/);
  assert.equal(nodes(f.tree, node => node.type === 'ConnectionRow' && node.props.title === 'Unrelated worker').length, 0);
  f.row('Original worker').props.onOpen(); await f.flush();
  assert.deepEqual(f.calls[0], { action: 'tasks.open', args: { id: 'original-pending' } });
});

test('hidden remote workspace rejects late review and a previously captured confirm handler', async () => {
  const f = fixture('RemoteWork');
  f.button('读取当前工作环境').props.onClick(); await f.flush();
  f.find(node => node.type === 'SelectField' && node.props.label === '工作环境').props.onChange('environment-a'); f.render();
  f.find(node => node.type === 'SelectField' && node.props.label === '执行器').props.onChange('command'); f.render();
  f.hold('remote.reviewTask'); f.button('审阅这次新工作').props.onClick(); f.render();
  f.props.visible = false; f.render();
  f.deferred.shift()({ reviewHandle: 'late-review', operationId: 'late-operation' }); await f.flush();
  assert.equal(f.find(node => node.type === 'SettingsDialog').props.isOpen, false);
  f.hold(''); f.props.visible = true; f.render(); f.button('审阅这次新工作').props.onClick(); await f.flush();
  assert.equal(f.find(node => node.type === 'SettingsDialog').props.isOpen, true);
  const originalConfirm = f.button('确认创建一次').props.onClick;
  f.props.visible = false; f.render(); originalConfirm(); await f.flush();
  assert.equal(f.calls.filter(call => call.action === 'remote.confirmTask').length, 0);
  assert.equal(f.find(node => node.type === 'SettingsDialog').props.isOpen, false);
});

test('hidden SSH workspace rejects late review and an old confirm callback', async () => {
  const f = fixture('SshWork', { domain: 'ssh' });
  f.row('运行一条命令').props.onOpen(); f.render();
  f.find(node => node.type === 'TextAreaField' && node.props.label === 'POSIX 命令').props.onChange('pwd'); f.render();
  f.hold('ssh.reviewOperation'); f.button('核对并执行这条命令').props.onClick(); f.render();
  f.props.visible = false; f.render(); f.deferred.shift()({ operation: 'exec', reviewHandle: 'late-ssh-review', command: 'pwd' }); await f.flush();
  assert.equal(f.find(node => node.type === 'SettingsDialog').props.isOpen, false);
  f.hold(''); f.props.visible = true; f.render(); f.button('核对并执行这条命令').props.onClick(); await f.flush();
  const originalConfirm = f.button('确认执行一次').props.onClick;
  f.props.visible = false; f.render(); originalConfirm(); await f.flush();
  assert.equal(f.calls.filter(call => call.action === 'ssh.confirmOperation').length, 0);
  assert.equal(f.find(node => node.type === 'SettingsDialog').props.isOpen, false);
});

test('configured harnesses come from the computer and submit the exact verified selection', async () => {
  const worker = { id: 'acp_example', label: 'Owner configured agent', available: true, configurationRevision: '1'.repeat(64), protocol: 'acp', executionPolicy: 'owner_managed' };
  const environment = { id: 'environment-a', label: 'Original environment', workspace: '/home/owner/work', bindingHash: 'e'.repeat(64), writeAllowed: true, workers: [worker] };
  let protocolVerified = false;
  const f = fixture('RemoteWork', { respond(action: string) {
    if (action === 'remote.environments') return { environments: [environment] };
    if (action === 'remote.probeWorker') return { worker: worker.id, available: true, protocolVerified, configurationRevision: worker.configurationRevision, capabilityRevision: '2'.repeat(64), models: { availableModels: [{ modelId: 'model-a', name: 'Model A' }] } };
  } });
  f.button('读取当前工作环境').props.onClick(); await f.flush();
  f.find(node => node.type === 'SelectField' && node.props.label === '工作环境').props.onChange(environment.id); f.render();
  const selector = f.find(node => node.type === 'SelectField' && node.props.label === '执行器');
  assert.ok(selector.props.options.some((option: any) => option.id === worker.id && option.label === worker.label));
  selector.props.onChange(worker.id); f.render();
  f.find(node => node.type === 'TextAreaField' && node.props.label === '这次要完成什么').props.onChange('Inspect the workspace'); f.render();
  f.button('核对执行器与模型能力').props.onClick(); await f.flush();
  f.find(node => node.type === 'CheckboxField').props.onChange(true); f.render();
  assert.equal(f.button('审阅这次新工作').props.disabled, true, 'An available boolean without verified protocol is insufficient');
  protocolVerified = true; f.button('核对执行器与模型能力').props.onClick(); await f.flush();
  assert.equal(f.button('审阅这次新工作').props.disabled, false);
  f.button('审阅这次新工作').props.onClick(); await f.flush();
  const request = f.calls.find(call => call.action === 'remote.reviewTask').args;
  assert.deepEqual(request.harnessSelection, { protocol: 'pocketworld.harness-selection.v1', workerId: worker.id, configurationRevision: worker.configurationRevision, capabilityRevision: '2'.repeat(64), ownerManagedExecution: true });
  assert.equal(request.readOnly, false); assert.equal(request.externalExecutionRiskAcknowledged, true);
  assert.equal(request.environmentBinding, environment.bindingHash);
});

async function currentCodexCreate() {
  const digest = 'd'.repeat(64);
  const f = fixture('RemoteWork', { respond(action: string) {
    if (action === 'remote.environments') return { environments: [{ id: 'environment-a', label: 'Original environment', kind: 'local', workspace: '/original', bindingHash: 'e'.repeat(64), workers: [{ id: 'codex_app_server', available: true }], projectHistoryCapabilities: { supported: true }, privateInputCapabilities: { supported: true } }] };
    if (action === 'remote.probeWorker') return { available: true, modelOptions: { digest, models: [{ id: 'current-model', efforts: ['high'] }] } };
  } });
  const field = (label: string, value: any, commit = true) => { f.find(node => ['SelectField', 'TextField', 'TextAreaField'].includes(node.type) && node.props.label === label).props.onChange(value); if (commit) f.render(); };
  f.button('读取当前工作环境').props.onClick(); await f.flush(); field('工作环境', 'environment-a'); field('执行器', 'codex_app_server'); field('这次要完成什么', 'Original owner goal'); field('请求模型 ID（可留空）', 'current-model'); field('请求推理档位（可留空）', 'high'); field('任务时限（10–3600 秒）', '240');
  f.button('核对执行器与模型能力').props.onClick(); await f.flush(); return { f, field, digest };
}

test('ordinary, baseline and private-input create entries capture the same current selection into the original native review', async () => {
  for (const [label, child] of [['审阅这次新工作', ''], ['先保留项目基线，再创建这次工作', 'RemoteProjectBaseline'], ['选择私人原件，再审阅这次工作', 'RemoteInputFlow']]) {
    const { f, digest } = await currentCodexCreate(); assert.equal(f.button(label).props.disabled, false); f.button(label).props.onClick(); await f.flush();
    const captured = f.calls.filter(call => call.action === 'remote.reviewTask'); assert.equal(captured.length, 1);
    assert.deepEqual(captured[0].args, { profileId: profile.id, bindingHash: profile.bindingHash, environmentId: 'environment-a', environmentBinding: 'e'.repeat(64), worker: 'codex_app_server', timeoutSeconds: 240, prompt: 'Original owner goal', readOnly: true, model: 'current-model', effort: 'high', modelOptionsDigest: digest });
    if (child) { const prepared = f.find(node => node.type === child).props.prepared; assert.equal(prepared.reviewHandle, 'remote-review'); assert.equal(prepared.model, 'current-model'); assert.equal(prepared.effort, 'high'); }
    else { const facts = nodes(f.tree, node => node.type === 'Facts').at(-1)!; assert.ok(facts.props.values.some(([key, value]: any) => key === '推理强度' && value === 'high')); }
    assert.equal(f.calls.some(call => /confirmTask|remoteInputs.confirm|remote.project.confirm/.test(call.action)), false, 'preparing a review never starts work');
  }
});

test('model and effort edits synchronously invalidate all three captured new-work callbacks before React renders', async () => {
  for (const [label, fieldLabel, newValue] of [['审阅这次新工作', '请求模型 ID（可留空）', 'new-model'], ['先保留项目基线，再创建这次工作', '请求推理档位（可留空）', 'low'], ['选择私人原件，再审阅这次工作', '这次要完成什么', 'changed goal']]) {
    const { f, field } = await currentCodexCreate(); const captured = f.button(label).props.onClick, before = f.calls.length;
    field(fieldLabel, newValue, false); captured(); await f.flush(); assert.equal(f.calls.length, before, 'old rendered selection cannot obtain a fresh native review');
  }
});

test('a changed or dismissed native create review cannot dispatch its captured confirmation', async () => {
  for (const retire of ['edit', 'close', 'hidden']) {
    const { f, field } = await currentCodexCreate(); f.button('审阅这次新工作').props.onClick(); await f.flush(); const captured = f.button('确认创建一次').props.onClick, before = f.calls.length;
    if (retire === 'edit') field('任务时限（10–3600 秒）', '200', false);
    else if (retire === 'close') f.button('返回修改').props.onClick();
    else { f.props.visible = false; f.render(); f.props.visible = true; f.render(); }
    captured(); await f.flush(); assert.equal(f.calls.length, before); assert.equal(f.calls.some(call => call.action === 'remote.confirmTask'), false);
  }
});
