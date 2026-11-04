import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';

// Exercises the production component and handlers with hook/control doubles.
// This is not browser, Android, actual service or visual verification.
type Data = Record<string, any>;
const task = { id: 'task-original', goal: '一张海边日落图片', status: 'needs_confirmation', summary: '原服务结果仍需核对', raw: { feature: 'media' } };
const file = { id: 'file-original', sha256: 'sha-original', name: 'sunset.png', mimeType: 'image/png', size: 1024 };
const configuration = { provider: 'openai_compatible', baseUrl: 'https://media.example.test/v1', imageModel: 'image-original', ttsModel: 'voice-original', voice: 'calm' };
const snapshot = { configured: true, configurationRevision: 'config-original', configuration, imageEditingSupported: true, inputLimits: { imagePromptChars: 2000, speechTextChars: 4000 } };
const originalReading = { taskId: task.id, task, viewHandle: 'view-original', operation: { operationId: 'operation-original' }, receipt: { status: 'response_received', message: '原响应已收到', attachments: [file] } };
const preparedReview = { reviewHandle: 'review-prepared', command: 'generateImage', input: { taskId: 'task-prepared', configurationRevision: 'config-original', prompt: '保存的确切输入', observeUntilComplete: true } };
function fixture(options: Data = {}) {
  const text = readFileSync(new URL('../src/MediaWorkspace.tsx', import.meta.url), 'utf8');
  const ast = ts.createSourceFile('MediaWorkspace.tsx', text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const body = ast.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(ast).replace(/^export /, '')).join('\n');
  const code = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
  const slots: any[] = [], effects: (() => void)[] = [], layouts: (() => void)[] = [], calls: Data[] = [], focus: string[] = [];
  const expanded = new Set<string>(), docListeners = new Map<string, () => void>(), windowListeners = new Map<string, () => void>();
  let index = 0, dirty = false, tree: any, back: (() => boolean) | null = null;
  const scroller = { scrollTop: 0 }, document: Data = { visibilityState: 'visible', activeElement: null, addEventListener: (name: string, handler: () => void) => docListeners.set(name, handler), removeEventListener: (name: string) => docListeners.delete(name) };
  const root = { closest: () => scroller, contains: () => true, querySelector: () => ({ focus: () => focus.push('heading') }) };
  const useState = (initial: any) => { const i = index++; slots[i] ??= { value: typeof initial === 'function' ? initial() : initial }; return [slots[i].value, (next: any) => { const value = typeof next === 'function' ? next(slots[i].value) : next; if (!Object.is(value, slots[i].value)) { slots[i].value = value; dirty = true; } }]; };
  const useRef = (initial: any) => { const i = index++; return slots[i] ??= { current: initial }; };
  const effect = (queue: (() => void)[]) => (callback: () => any, deps?: any[]) => { const i = index++, prior = slots[i]; if (!prior || !deps || deps.some((value, j) => !Object.is(value, prior.deps?.[j]))) { const slot = slots[i] = { deps, cleanup: prior?.cleanup }; queue.push(() => { slot.cleanup?.(); slot.cleanup = callback(); }); } };
  const React = { Fragment: 'Fragment', createElement: (type: any, props: any, ...children: any[]) => { if (props?.ref) props.ref.current = root; return { type, props: props || {}, children: children.flat(Infinity).filter(child => child !== null && child !== undefined && child !== false) }; } };
  const dependencies: Data = { React, useState, useRef, useEffect: effect(effects), useLayoutEffect: effect(layouts), document, window: { addEventListener: (name: string, handler: () => void) => windowListeners.set(name, handler), removeEventListener: (name: string) => windowListeners.delete(name) }, formatFileSize: (size: number) => `${Math.round(size / 1024)} KB` };
  for (const name of ['ArrowLeft', 'ArrowRight', 'ChevronRight', 'File', 'Image', 'Plus', 'RefreshCw', 'Settings', 'Accordion', 'AccordionItem', 'Button', 'CheckboxField', 'SelectField', 'SettingsDialog', 'TextAreaField', 'TextField', 'AIAnswer', 'ProgressBar']) dependencies[name] = name;
  const Component = new Function(...Object.keys(dependencies), code + '\nreturn MediaWorkspace;')(...Object.values(dependencies));
  const responses: Data = {
    'media.snapshot': snapshot,
    'media.readTask': (args: Data) => args.taskId === 'task-prepared' ? { ...originalReading, taskId: args.taskId, task: { ...task, id: args.taskId }, viewHandle: 'view-prepared' } : originalReading,
    'media.prepare': preparedReview,
    'media.query': { confirmed: true, result: { message: '已查询原操作' } },
    'media.fetchResults': { confirmed: true, result: { message: '已取回原产物' } },
    'media.stopWaiting': { confirmed: true, result: { status: 'local_wait_stop_requested', message: '已停止本地等待，远端仍可能继续' } },
    'media.reviewCancel': { reviewHandle: 'review-cancel', command: 'cancelJob', input: { taskId: task.id, operationId: 'operation-original' } },
    'media.reviewDownload': { reviewHandle: 'review-download', command: 'confirmResultDownload', input: { taskId: task.id, operationId: 'operation-original' }, downloadReview: { providerOrigin: 'https://media.example.test', resultOrigin: 'https://result.example.test', providerResponseSha256: 'response-sha', resultUrlSha256: 'url-sha' } },
    'media.reviewConfiguration': (args: Data) => { const { apiKey: _secret, ...input } = args; return { reviewHandle: 'review-config', command: 'configure', input, replacesSavedTemplates: true }; },
    'media.confirm': (args: Data) => ({ confirmed: true, command: args.reviewHandle === 'review-config' ? 'configure' : args.reviewHandle === 'review-cancel' ? 'cancelJob' : args.reviewHandle === 'review-download' ? 'confirmResultDownload' : 'generateImage', result: args.reviewHandle === 'review-config' ? snapshot : { status: 'response_received', message: '原服务回执' } }),
    ...options.responses,
  };
  const props = { state: { tasks: [task], features: { attachments: { attachments: [file] } }, ...options.state }, registerTransientBack: (handler: any) => { back = handler; }, run: async (action: string, args: Data = {}) => { calls.push({ action, args }); const response = responses[action]; return typeof response === 'function' ? response(args) : response || {}; } };
  const hidden = (node: any) => node.props.hidden || node.type === 'SettingsDialog' && !node.props.isOpen || node.type === 'AccordionItem' && !expanded.has(node.props.id);
  const walk = (node: any, inactive = false): any[] => !node || typeof node !== 'object' ? [] : [{ ...node, visible: !inactive && !hidden(node) }, ...node.children.flatMap((child: any) => walk(child, inactive || hidden(node)))];
  const content = (node: any, visible = false): string => !node || typeof node !== 'object' ? String(node ?? '') : visible && hidden(node) ? '' : node.type === 'AIAnswer' ? node.props.text : node.children.map((child: any) => content(child, visible)).join('');
  const render = () => { let rounds = 0; do { assert.ok(rounds++ < 30, 'production render settles'); dirty = false; index = 0; tree = Component(props); layouts.splice(0).forEach(callback => callback()); effects.splice(0).forEach(callback => callback()); } while (dirty); };
  const find = (predicate: (node: any) => boolean, visible = true) => { const node = walk(tree).filter(node => (!visible || node.visible) && predicate(node)).at(-1); assert.ok(node, 'production control exists'); return node; };
  const button = (label: string) => find(node => node.type === 'Button' && (node.props['aria-label'] === label || content(node) === label));
  render();
  return { calls, responses, scroller, focus, props, render, find, button,
    get panel() { return tree.props['data-media-view']; }, get visibleText() { return content(tree, true); },
    click(label: string) { const node = button(label); assert.ok(!node.props.disabled, `${label} is enabled`); document.activeElement = { isConnected: true, closest: () => null, matches: () => false, focus: () => focus.push(label) }; node.props.onClick(); render(); },
    field(label: string, value: any) { const node = find(node => ['TextField', 'TextAreaField', 'SelectField'].includes(node.type) && node.props.label === label); assert.ok(!node.props.isDisabled, `${label} is enabled`); node.props.onChange(value); render(); },
    value(label: string) { return find(node => node.props.label === label, false).props.value; },
    acknowledge() { find(node => node.type === 'CheckboxField' && content(node) === '已核对本次输入、服务与操作').props.onChange(true); render(); },
    expand(id: string) { expanded.add(id); render(); },
    back() { const handled = back?.(); render(); return handled; },
    hide() { document.visibilityState = 'hidden'; docListeners.get('visibilitychange')?.(); render(); },
    show() { document.visibilityState = 'visible'; docListeners.get('visibilitychange')?.(); render(); },
    native(open: boolean, revision = 1) { Object.assign(props.state, { webWorkspaceTaskOpen: open, webWorkspaceResumeRevision: revision }); render(); },
    unmount() { for (const slot of slots) slot?.cleanup?.(); },
    async flush() { for (let i = 0; i < 12; i++) { await Promise.resolve(); if (dirty) render(); } }
  };
}
async function create(f: ReturnType<typeof fixture>, text = '海边日落') { await f.flush(); f.click(f.visibleText.includes('继续创作') ? '继续创作' : '开始创作'); f.field('描述要生成的内容', text); }
async function read(f: ReturnType<typeof fixture>) { await f.flush(); f.click('查看原媒体任务：' + task.goal); await f.flush(); }
const count = (f: ReturnType<typeof fixture>, action: string) => f.calls.filter(call => call.action === action).length;

test('media starts with the original task collection and service identity, not a parallel creation/configuration form', async () => {
  const f = fixture(); await f.flush(); assert.equal(f.panel, 'library');
  assert.deepEqual(f.calls, [{ action: 'media.snapshot', args: {} }]); assert.match(f.visibleText, /原媒体任务/); assert.match(f.visibleText, /一张海边日落图片/); assert.match(f.visibleText, /https:\/\/media.example.test\/v1/);
  assert.equal(f.find(node => node.props['data-media-panel'] === 'create', false).props.hidden, true);
  assert.equal(f.find(node => node.type === 'SettingsDialog' && node.props.title === '媒体服务配置', false).props.isOpen, false);
  assert.equal(f.back(), false);
});
test('creation, original reading and return preserve draft, object, scroll and a focus destination without retaining view authority', async () => {
  const f = fixture(); await create(f, '未提交草稿'); f.scroller.scrollTop = 154; const stalePrepare = f.button('准备并核对输入'); f.click('返回媒体任务');
  assert.equal(f.panel, 'library'); stalePrepare.props.onClick(); await f.flush(); assert.equal(count(f, 'media.prepare'), 0);
  f.scroller.scrollTop = 62; await read(f); assert.equal(f.panel, 'task'); assert.equal(f.scroller.scrollTop, 0); assert.match(f.visibleText, /原任务 · task-original/); assert.match(f.visibleText, /原服务操作 · operation-original/);
  f.scroller.scrollTop = 286; const staleQuery = f.button('查询原操作'); f.click('返回媒体任务'); assert.equal(f.scroller.scrollTop, 62); staleQuery.props.onClick(); await f.flush(); assert.equal(count(f, 'media.query'), 0);
  f.click('继续创作'); assert.equal(f.value('描述要生成的内容'), '未提交草稿'); assert.equal(f.scroller.scrollTop, 154); assert.ok(f.focus.length > 0); f.click('返回媒体任务'); await read(f); assert.equal(f.scroller.scrollTop, 286);
  assert.equal(count(f, 'media.readTask'), 2); assert.ok(f.calls.some(call => call.action === 'media.closeReading' && call.args.viewHandle === 'view-original'));
});
test('prepare does not submit; review exposes exact input/task/config and confirms a consumed original handle once', async () => {
  const f = fixture(); await create(f); f.click('准备并核对输入'); await f.flush();
  assert.deepEqual(f.calls.find(call => call.action === 'media.prepare')?.args, { kind: 'image', observeUntilComplete: true, text: '海边日落' }); assert.equal(count(f, 'media.confirm'), 0);
  assert.match(f.visibleText, /task-prepared/); assert.match(f.visibleText, /config-original/); assert.match(f.visibleText, /保存的确切输入/); assert.match(f.visibleText, /可能产生费用/);
  assert.equal(f.button('确认这一次操作').props.disabled, true); f.acknowledge(); const confirm = f.button('确认这一次操作'); confirm.props.onClick(); confirm.props.onClick(); f.render(); await f.flush();
  assert.equal(count(f, 'media.confirm'), 1); assert.deepEqual(f.calls.find(call => call.action === 'media.confirm')?.args, { reviewHandle: 'review-prepared' }); assert.equal(f.panel, 'task'); assert.match(f.visibleText, /task-prepared/); assert.equal(f.value('描述要生成的内容'), '海边日落');
});
test('returning from review only releases its original handle and preserves the creation input', async () => {
  const f = fixture(); await create(f, '仍需核对的草稿'); f.click('准备并核对输入'); await f.flush(); f.acknowledge(); const staleConfirm = f.button('确认这一次操作');
  assert.equal(f.back(), true); await f.flush(); assert.equal(f.panel, 'create'); assert.equal(f.value('描述要生成的内容'), '仍需核对的草稿'); assert.equal(count(f, 'media.confirm'), 0);
  assert.ok(f.calls.some(call => call.action === 'media.closeReading' && call.args.reviewHandle === 'review-prepared'));
  f.responses['media.prepare'] = { ...preparedReview, reviewHandle: 'another-review' }; f.click('准备并核对输入'); await f.flush(); staleConfirm.props.onClick(); await f.flush(); assert.equal(count(f, 'media.confirm'), 0); assert.equal(f.button('确认这一次操作').props.disabled, true);
});
test('unknown submission remains visible through return and configuration; it never becomes an automatic retry or completion', async () => {
  const f = fixture({ responses: { 'media.confirm': () => { throw new Error('回执超时'); }, 'media.readTask': { ...originalReading, taskId: 'task-prepared', task: { ...task, id: 'task-prepared', status: 'reconciling' }, receipt: { status: 'unknown', message: '原效果待核对', attachments: [] } } } });
  await create(f); f.click('准备并核对输入'); await f.flush(); f.acknowledge(); f.click('确认这一次操作'); await f.flush(); assert.match(f.visibleText, /原提交回执尚待核对/); assert.equal(f.button('准备并核对输入').props.disabled, true);
  f.click('返回媒体任务'); assert.match(f.visibleText, /原提交回执尚待核对/); f.click('核对刚才的原提交'); await f.flush(); assert.match(f.visibleText, /原效果待核对/); assert.match(f.visibleText, /原提交回执尚待核对/);
  f.click('服务配置'); f.click('返回工作室'); f.click('返回媒体任务'); f.click('继续创作'); assert.equal(f.button('准备并核对输入').props.disabled, true); assert.equal(count(f, 'media.confirm'), 1); assert.equal(count(f, 'media.prepare'), 1);
});
test('service draft survives close/review/return but secrets clear on target change, submission, failure and background', async () => {
  const f = fixture(); await create(f); f.click('服务配置'); f.field('服务地址', 'https://draft.example.test/v1'); f.field('图片模型 ID', 'draft-model'); f.field('服务密钥，留空保留相同服务原密钥', 'secret-one'); f.click('返回工作室');
  assert.equal(f.value('描述要生成的内容'), '海边日落'); f.click('服务配置'); assert.equal(f.value('服务地址'), 'https://draft.example.test/v1'); assert.equal(f.value('图片模型 ID'), 'draft-model'); assert.equal(f.value('服务密钥，留空保留相同服务原密钥'), '');
  f.field('服务密钥，留空保留相同服务原密钥', 'secret-two'); f.field('服务地址', 'https://another.example.test/v1'); assert.equal(f.value('服务密钥，留空保留相同服务原密钥'), '');
  f.field('服务密钥，留空保留相同服务原密钥', 'secret-three'); f.click('核对并保存配置'); assert.equal(f.value('服务密钥，留空保留相同服务原密钥'), ''); await f.flush(); assert.match(f.visibleText, /https:\/\/another.example.test\/v1/); assert.doesNotMatch(f.visibleText, /secret-three/); assert.equal(count(f, 'media.confirm'), 0); f.click('返回');
  assert.equal(f.value('图片模型 ID'), 'draft-model'); f.responses['media.reviewConfiguration'] = () => { throw new Error('审阅失败'); }; f.field('服务密钥，留空保留相同服务原密钥', 'secret-four'); f.click('核对并保存配置'); await f.flush(); assert.equal(f.value('服务密钥，留空保留相同服务原密钥'), '');
  f.field('服务密钥，留空保留相同服务原密钥', 'secret-five'); f.hide(); assert.equal(f.value('服务密钥，留空保留相同服务原密钥'), ''); assert.equal(f.value('图片模型 ID'), 'draft-model');
});
test('reading a different identity is rejected and releases the returned handle instead of replacing the original object', async () => {
  const f = fixture({ responses: { 'media.readTask': { ...originalReading, taskId: 'unrelated', task: { ...task, id: 'unrelated' }, viewHandle: 'wrong-view' } } }); await read(f);
  assert.equal(f.panel, 'library'); assert.match(f.visibleText, /身份不匹配/); assert.ok(f.calls.some(call => call.action === 'media.closeReading' && call.args.viewHandle === 'wrong-view')); assert.equal(count(f, 'media.confirm'), 0);
});
test('late reads and prepared reviews cannot restore permissions after backgrounding or unmounting', async () => {
  let resolveRead: (value: Data) => void = () => {}; const f = fixture({ responses: { 'media.readTask': () => new Promise(resolve => { resolveRead = resolve; }) } }); await f.flush(); f.click('查看原媒体任务：' + task.goal); f.hide(); resolveRead(originalReading); await f.flush(); assert.equal(f.panel, 'library'); assert.ok(f.calls.some(call => call.action === 'media.closeReading' && call.args.viewHandle === 'view-original'));
  let resolvePrepare: (value: Data) => void = () => {}; const g = fixture({ responses: { 'media.prepare': () => new Promise(resolve => { resolvePrepare = resolve; }) } }); await create(g); g.click('准备并核对输入'); g.unmount(); resolvePrepare(preparedReview); await g.flush(); assert.ok(g.calls.some(call => call.action === 'media.closeReading' && call.args.reviewHandle === 'review-prepared')); assert.equal(count(g, 'media.confirm'), 0);
});
test('background/owner-task return rereads the same task and a stale query never reuses a released view handle', async () => {
  const f = fixture(); await read(f); const staleQuery = f.button('查询原操作'); f.hide(); assert.equal(f.button('查询原操作').props.disabled, true); staleQuery.props.onClick(); await f.flush(); assert.equal(count(f, 'media.query'), 0);
  f.responses['media.readTask'] = { ...originalReading, viewHandle: 'view-renewed' }; f.show(); await f.flush(); f.click('查询原操作'); await f.flush(); assert.deepEqual(f.calls.find(call => call.action === 'media.query')?.args, { viewHandle: 'view-renewed' });
  f.click('打开原任务与审批'); await f.flush(); assert.deepEqual(f.calls.find(call => call.action === 'tasks.open')?.args, { id: task.id }); f.native(true); assert.equal(f.button('查询原操作').props.disabled, true); f.native(false, 2); await f.flush(); assert.equal(f.panel, 'task'); assert.equal(f.button('查询原操作').props.disabled, false);
});
test('attachments remain exact original file versions and downloads/cancel still need separate one-time reviews', async () => {
  const f = fixture({ responses: { 'media.readTask': { ...originalReading, receipt: { ...originalReading.receipt, ownerResultDownloadReviewAvailable: true } } } }); await read(f);
  const open = f.find(node => node.type === 'Button' && node.props.className === 'pw-media-file-row'); open.props.onClick(); f.render(); await f.flush(); assert.deepEqual(f.calls.find(call => call.action === 'media.openResult')?.args, { viewHandle: 'view-original', fileId: 'file-original', sha256: 'sha-original' });
  f.click('核对一次性结果下载'); await f.flush(); assert.match(f.visibleText, /https:\/\/result.example.test/); assert.match(f.visibleText, /response-sha/); assert.match(f.visibleText, /url-sha/); assert.equal(count(f, 'media.confirm'), 0); f.click('返回');
  f.expand('media-waiting'); f.click('请求取消原服务任务'); await f.flush(); assert.match(f.visibleText, /本地停止不代表远端已经停止/); f.acknowledge(); f.click('确认这一次操作'); await f.flush(); assert.deepEqual(f.calls.find(call => call.action === 'media.confirm')?.args, { reviewHandle: 'review-cancel' });
});
test('reading a prepared task reviews its original saved input and cancellation retains input without a reusable review', async () => {
  const original = { ...preparedReview, input: { ...preparedReview.input, taskId: task.id } };
  const f = fixture({ responses: { 'media.readTask': { taskId: task.id, task, viewHandle: 'prepared-view', operation: null, receipt: {}, submissionReview: original } } }); await create(f, '另一份未提交草稿'); f.click('返回媒体任务'); await read(f);
  assert.match(f.visibleText, /保存的确切输入/); f.click('核对原输入并提交'); await f.flush(); assert.match(f.visibleText, /保存的确切输入/); assert.equal(count(f, 'media.prepare'), 0); f.click('返回'); assert.match(f.visibleText, /原输入仍保留/); assert.match(f.visibleText, /保存的确切输入/); assert.equal(f.value('描述要生成的内容'), '另一份未提交草稿'); assert.equal(count(f, 'media.confirm'), 0);
});
test('changed service capabilities preserve unsupported drafts without allowing stale-kind preparation', async () => {
  const f = fixture(); await create(f); f.field('创作类型', 'speech'); f.field('要合成为语音的文字', '要保留的语音文字'); f.click('返回媒体任务'); f.responses['media.snapshot'] = { ...snapshot, configurationRevision: 'new-config', configuration: { provider: 'comfyui', baseUrl: 'https://comfy.example.test', hasWorkflow: false } }; f.click('刷新原记录'); await f.flush(); f.click('继续创作');
  assert.equal(f.value('创作类型'), 'speech'); assert.equal(f.value('要合成为语音的文字'), '要保留的语音文字'); assert.match(f.visibleText, /不支持原草稿类型/); assert.equal(f.button('准备并核对输入').props.disabled, true); assert.equal(count(f, 'media.prepare'), 0);
});
test('the media layout uses imported controls and one lightweight working surface instead of native replacement inputs', () => {
  const source = readFileSync(new URL('../src/MediaWorkspace.tsx', import.meta.url), 'utf8');
  const css = readFileSync(new URL('../src/media-workspace.css', import.meta.url), 'utf8');
  assert.match(source, /from '\.\/forms-library'/); assert.match(source, /<AIAnswer /); assert.match(source, /import '\.\/media-workspace.css'/); assert.doesNotMatch(source, /<(?:button|input|textarea|select)\b/);
  assert.match(css, /\.pw-media-workspace \[hidden\] \{ display: none !important/); assert.match(css, /@media \(max-width: 640px\)/); assert.match(css, /background: var\(--pw-surface/); assert.doesNotMatch(css, /linear-gradient|backdrop-filter/);
});

test('closed configuration cannot submit a retained handler or a secret from an earlier settings draft', async () => {
  const f = fixture(); await f.flush(); f.click('服务配置'); f.field('服务密钥，留空保留相同服务原密钥', 'discarded-secret'); const oldReview = f.button('核对并保存配置'); f.click('返回工作室'); oldReview.props.onClick(); await f.flush(); assert.equal(count(f, 'media.reviewConfiguration'), 0);
  f.click('服务配置'); oldReview.props.onClick(); await f.flush(); assert.equal(count(f, 'media.reviewConfiguration'), 0); assert.equal(f.value('服务密钥，留空保留相同服务原密钥'), '');
});
