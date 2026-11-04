import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';

// Production handlers with hook, adopted-control and native receipt doubles.
// Not browser, accessibility, Android, model-service or visual verification.
type Data = Record<string, any>;
const source = { title: '原来源标题', sourceHandle: 'source-original', sourceRef: { kind: 'episode', id: 'episode-original', revision: 4 } };
const job = { taskId: 'learning-original', taskStatus: 'awaiting_user', runId: '', epoch: 0, canContinue: true, canSkipBatch: false, job: { revision: 2, inputs: [{ title: source.title, sourceFingerprint: 'original-fingerprint' }], results: [], nextIndex: 0 } };
const procedure = { procedureHandle: 'procedure-original', record: { id: 'procedure-id', revision: 3, text: '原方法标题\n原方法完整内容', applicability: '原适用条件', active: true, validationStatus: 'unverified' } };
const skill = { skillHandle: 'skill-original', skill: { id: 'skill-id', revision: 5, digest: 'original-digest', name: '原来源技能', description: '完整来源技能说明', enabled: false, origin: { id: 'origin-original' }, originStatus: 'current', resources: ['reference.txt'] } };
const draft = { draftHandle: 'draft-original', draft: { manifest: '原技能草稿', resources: { 'reference.txt': '原附加资料' } }, inspection: { policy: { requiredCapabilities: ['read'] } } };
const review = { reviewHandle: 'review-original', operation: 'draftExperience', before: procedure.record, payload: {} };

function fixture(kind: 'Learning' | 'Experience', responses: Data = {}, initialSection = 'learning') {
  const sourceText = readFileSync(new URL(`../src/${kind}Workspace.tsx`, import.meta.url), 'utf8');
  const ast = ts.createSourceFile(`${kind}Workspace.tsx`, sourceText, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const body = ast.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(ast).replace(/^export /, '')).join('\n');
  const code = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
  let slots: any[] = [], index = 0, dirty = false, tree: any, back: (() => boolean) | null = null;
  const effects: (() => void)[] = [], calls: Data[] = [], expanded = new Set<string>(), timers = new Map<number, () => void>(), subscribers = new Set<() => void>();
  const listeners = new Map<string, Set<() => void>>();
  const listen = { addEventListener: (name: string, fn: () => void) => { if (!listeners.has(name)) listeners.set(name, new Set()); listeners.get(name)!.add(fn); }, removeEventListener: (name: string, fn: () => void) => listeners.get(name)?.delete(fn) };
  const document: Data = { visibilityState: 'visible', activeElement: null, ...listen }, host: Data = { view: { section: initialSection, resumeRevision: 1 } };
  const useState = (initial: any) => { const i = index++; slots[i] ??= { value: typeof initial === 'function' ? initial() : initial }; return [slots[i].value, (next: any) => { const value = typeof next === 'function' ? next(slots[i].value) : next; if (!Object.is(value, slots[i].value)) { slots[i].value = value; dirty = true; } }]; };
  const useRef = (initial: any) => { const i = index++; return slots[i] ??= { current: initial }; };
  const useEffect = (callback: () => any, deps?: any[]) => { const i = index++, prior = slots[i]; if (!deps || !prior?.deps || deps.some((value, j) => !Object.is(value, prior.deps[j]))) { const slot = slots[i] = { deps, cleanup: prior?.cleanup }; effects.push(() => { slot.cleanup?.(); slot.cleanup = callback(); }); } };
  const useCallback = (fn: any, deps: any[]) => { const i = index++, prior = slots[i]; if (!prior || deps.some((value, j) => !Object.is(value, prior.deps[j]))) slots[i] = { fn, deps }; return slots[i].fn; };
  const React: Data = { Fragment: 'Fragment', createElement: (type: any, props: any, ...children: any[]) => {
    const flat = children.flat(Infinity).filter(child => child !== false && child !== null && child !== undefined);
    return typeof type === 'function' ? type({ ...props, children: flat }) : { type, props: props || {}, children: flat };
  } };
  let timerId = 0;
  const dependencies: Data = { React, useState, useRef, useEffect, useLayoutEffect: useEffect, useCallback, document, window: listen,
    bridge: { getState: () => host, getDocumentEpoch: () => 1, subscribe: (fn: () => void) => { subscribers.add(fn); return () => subscribers.delete(fn); } },
    setInterval: (fn: () => void) => { const id = ++timerId; timers.set(id, fn); return id; }, clearInterval: (id: number) => timers.delete(id),
  };
  for (const name of ['Accordion', 'AccordionItem', 'Button', 'CheckboxField', 'SelectField', 'SettingsDialog', 'TextAreaField', 'TextField', 'AIAnswer', 'Tabs', 'TabList', 'Tab', 'TabPanel', 'ArrowLeft', 'BookOpen', 'Plus', 'RefreshCw', 'ChevronRight', 'FileText']) dependencies[name] = name;
  if (kind === 'Learning') { dependencies.ExperienceWorkspace = 'ExperienceWorkspace'; dependencies.LearningObjectRow = 'LearningObjectRow'; }
  const exported = new Function(...Object.keys(dependencies), code + `\nreturn { Component: ${kind}Workspace, ${kind === 'Learning' ? 'learningStopRequest, learningBatchStatus' : 'experienceFilePage, experienceRecoveryTarget'} };`)(...Object.values(dependencies));
  const values: Data = {
    'learning.jobs': { jobs: [job], hasMore: false }, 'learning.job': job,
    'learning.episode.inspect': { title: '任务原经过', text: '完整经过\n结果仍需核对', reviewHandle: 'episode-review' },
    'learning.episode.retain': { ...source, learningAllowed: true }, 'learning.create': job,
    'learning.run.review': { ...job, reviewHandle: 'learning-review', reviewMaxBatches: 3 },
    'learning.run.confirm': { operationHandle: 'learning-operation', taskId: job.taskId, settled: false },
    'learning.operation.query': { taskId: job.taskId, settled: false, result: null },
    'learning.operation.stop': { cancelRequested: true }, 'learning.stop': { ...job, taskStatus: 'stopping' },
    'experience.snapshot': { procedures: [procedure.record], skills: [skill.skill] },
    'experience.procedure': procedure, 'experience.skill': skill, 'experience.draft.review': review,
    'experience.draft.start': { operationHandle: 'experience-operation', requestId: 'request-original', settled: false },
    'experience.operation.query': { settled: false, result: null }, 'experience.operation.stop': { cancelRequested: true },
    'experience.inspect': (args: Data) => ({ ...draft, draftHandle: 'draft-rechecked', draft: { ...draft.draft, manifest: args.manifest } }),
    'experience.import.review': { ...review, operation: 'importExperience' }, 'experience.confirm': skill,
    'experience.read': (args: Data) => ({ ...skill.skill, path: args.path, content: args.path === 'SKILL.md' ? args.offset ? '第二页' : '第一页' : '附加资料', nextOffset: args.path === 'SKILL.md' ? args.offset ? 6 : 3 : 4, hasMore: args.path === 'SKILL.md' && !args.offset }),
    'experience.approve.review': { ...review, operation: 'approveExperience' },
    'experience.quality.list': { applications: [{ applicationHandle: 'application-original', taskId: 'source-task', status: 'completed' }], hasMore: false },
    'experience.quality.review': { ...review, operation: 'reviewAppQuality' },
    'experience.apps': { apps: [{ appHandle: 'app-original', packageName: 'original.package', appVersion: '7' }] },
    'experience.app.review': { ...review, operation: 'draftAppRevision' },
    ...responses,
  };
  const candidateTargets: Data[] = [];
  const props: Data = { state: { tasks: [{ id: 'source-task', goal: '原任务', status: 'completed', raw: { kind: 'assistant', runId: 'run-original', coordinatorEpoch: 8 } }] }, data: {}, panel: 'procedures', onNavigate: (panel: string) => { props.panel = panel; dirty = true; }, registerTransientBack: (fn: any) => { back = fn; }, onReviewCandidate: (target: Data) => candidateTargets.push(target), run: async (action: string, args: Data = {}) => { calls.push({ action, args }); const value = values[action]; return typeof value === 'function' ? value(args) : value || {}; } };
  const hidden = (node: any) => !!node.props.hidden || node.type === 'SettingsDialog' && !node.props.isOpen;
  const walk = (node: any, inactive = false): any[] => !node || typeof node !== 'object' ? [] : [{ ...node, visible: !inactive && !hidden(node) }, ...node.children.flatMap((child: any) => walk(child, inactive || hidden(node) || node.type === 'AccordionItem' && !expanded.has(node.props.id)))];
  const content = (node: any, visible = false): string => !node || typeof node !== 'object' ? String(node ?? '') : visible && hidden(node) ? '' : node.type === 'LearningObjectRow' ? `${node.props.title}${node.props.detail}` : node.type === 'AIAnswer' ? `${node.props.text || ''}${node.children.map((child: any) => content(child, visible)).join('')}` : node.type === 'AccordionItem' ? String(node.props.title) + String(node.props.description || '') + (!visible || expanded.has(node.props.id) ? node.children.map((child: any) => content(child, visible)).join('') : '') : node.children.map((child: any) => content(child, visible)).join('');
  const render = () => { let rounds = 0; do { assert.ok(rounds++ < 40, 'production render settles'); dirty = false; index = 0; tree = exported.Component(props); effects.splice(0).forEach(fn => fn()); } while (dirty); };
  const find = (predicate: (node: any) => boolean, visible = true) => { const node = walk(tree).filter(node => (!visible || node.visible) && predicate(node)).at(-1); assert.ok(node, 'expected production control'); return node; };
  const button = (label: string) => find(node => node.type === 'Button' && (node.props['aria-label'] === label || content(node) === label));
  const unmount = () => { for (const slot of slots) slot?.cleanup?.(); };
  render();
  return { values, calls, candidateTargets, exported, render, find, button, unmount,
    get text() { return content(tree, true); },
    click(label: string) { const node = button(label); assert.ok(!node.props.disabled, `${label} enabled`); node.props.onClick(); render(); },
    row(title: string) { const node = find(node => node.type === 'LearningObjectRow' ? node.props.title === title : node.type === 'Button' && node.props['aria-label'] === `打开：${title}`); assert.ok(!node.props.disabled); node.props.onClick(); render(); },
    field(label: string, value: any) { const node = find(node => ['SelectField', 'TextField', 'TextAreaField'].includes(node.type) && node.props.label === label); assert.ok(!node.props.isDisabled); node.props.onChange(value); render(); },
    value(label: string) { return find(node => node.props.label === label, false).props.value; },
    check(prefix: string) { const node = find(node => node.type === 'CheckboxField' && content(node).startsWith(prefix)); assert.ok(!node.props.isDisabled); node.props.onChange(true); render(); },
    tab(panel: string) { if (kind === 'Learning') find(node => node.type === 'Tabs').props.onSelectionChange(panel); else props.panel = panel; render(); },
    expand(id: string) { expanded.add(id); render(); },
    back() { const handled = back?.(); render(); return handled; },
    hide() { document.visibilityState = 'hidden'; for (const fn of [...listeners.get('visibilitychange') || []]) fn(); render(); },
    show() { document.visibilityState = 'visible'; for (const fn of [...listeners.get('visibilitychange') || []]) fn(); render(); },
    owner(section: string, revision = 2) { host.view = { section, resumeRevision: revision }; for (const fn of [...subscribers]) fn(); render(); },
    tick() { for (const fn of [...timers.values()]) fn(); render(); },
    remount() { unmount(); slots = []; index = 0; dirty = false; render(); },
    async flush() { for (let i = 0; i < 22; i++) { await Promise.resolve(); if (dirty) render(); } },
  };
}
const count = (f: ReturnType<typeof fixture>, action: string) => f.calls.filter(call => call.action === action).length;
const last = (f: ReturnType<typeof fixture>, action: string) => f.calls.filter(call => call.action === action).at(-1)?.args;
async function openJob(f: ReturnType<typeof fixture>) { await f.flush(); f.row(source.title); await f.flush(); }
async function selectSource(f: ReturnType<typeof fixture>) { await f.flush(); f.click('整理新来源'); f.field('选择原任务', 'source-task'); f.click('读取任务经过'); await f.flush(); f.click('保留来源并加入清单'); await f.flush(); }
async function startLearning(f: ReturnType<typeof fixture>) { await openJob(f); f.click('核对并继续原批次'); await f.flush(); f.check('我已核对原来源'); f.click('发送并继续原任务'); await f.flush(); }
async function openMethod(f: ReturnType<typeof fixture>) { await f.flush(); f.row('原方法标题'); await f.flush(); }
async function startDraft(f: ReturnType<typeof fixture>) { await openMethod(f); f.click('核对并起草技能'); await f.flush(); f.check('我已核对原内容'); f.click('确认这一次操作'); await f.flush(); }

test('learning starts at the original task collection with an explicit source entry', async () => {
  const f = fixture('Learning'); await f.flush(); assert.match(f.text, /原来源标题/); assert.doesNotMatch(f.text, /选择原任务|本次所选来源|批次范围与异常处理/);
  assert.equal(count(f, 'learning.jobs'), 1); assert.equal(count(f, 'learning.run.confirm'), 0); assert.equal(f.back(), false);
  f.tab('skills'); assert.equal(f.find(node => node.type === 'ExperienceWorkspace', false).props.panel, 'skills'); assert.equal(count(f, 'knowledge.close'), 0);
});

test('source reading and exact source selection survive tabs before non-model task creation', async () => {
  const f = fixture('Learning'); await selectSource(f); assert.deepEqual(last(f, 'learning.episode.inspect'), { taskId: 'source-task', runId: 'run-original', epoch: 8 });
  f.tab('procedures'); f.tab('jobs'); assert.match(f.text, /完整经过/); assert.match(f.text, /原来源标题/); assert.equal(count(f, 'learning.episode.inspect'), 1);
  f.click('建立学习任务，暂不发送模型'); await f.flush(); assert.deepEqual(last(f, 'learning.create'), { sourceHandles: ['source-original'] }); assert.equal(count(f, 'learning.run.confirm'), 0); assert.match(f.text, /当前批次/);
});

test('create outcome unknown stays visible outside the source panel and cannot resubmit', async () => {
  const f = fixture('Learning', { 'learning.create': () => { throw new Error('回执未知'); } }); await selectSource(f); f.click('建立学习任务，暂不发送模型'); await f.flush(); assert.equal(f.button('建立学习任务，暂不发送模型').props.disabled, true);
  f.tab('skills'); assert.match(f.text, /建立回执待核对/); assert.equal(count(f, 'learning.create'), 1);
});

test('synchronously settled learning start queries the same operation and candidate identity', async () => {
  const done = { ...job, canContinue: false, job: { ...job.job, nextIndex: 1, results: [{ candidateRef: { id: 'candidate-original', revision: 9 } }] } };
  const f = fixture('Learning', { 'learning.run.confirm': { taskId: job.taskId, operationHandle: 'learning-operation', settled: true }, 'learning.operation.query': { taskId: job.taskId, settled: true, result: done } });
  await startLearning(f); assert.equal(count(f, 'learning.run.confirm'), 1); assert.equal(count(f, 'learning.operation.query'), 1); assert.deepEqual(last(f, 'learning.operation.query'), { operationHandle: 'learning-operation' });
  f.click('审阅这条候选'); assert.deepEqual(f.candidateTargets, [{ id: 'candidate-original', revision: 9, taskId: job.taskId, sourceTitle: source.title }]);
});

test('learning running and query failures remain visible in other tabs; stopping is not settlement', async () => {
  const f = fixture('Learning'); await startLearning(f); f.tab('skills'); assert.match(f.text, /正在等待原批次回执/); f.click('请求停止本次提炼'); await f.flush(); assert.deepEqual(last(f, 'learning.operation.stop'), { operationHandle: 'learning-operation' }); assert.match(f.text, /正在等待原批次回执/);
  f.values['learning.operation.query'] = () => { throw new Error('查询中断'); }; f.tick(); await f.flush(); assert.match(f.text, /回执读取失败/); const n = count(f, 'learning.operation.query'); f.tick(); await f.flush(); assert.equal(count(f, 'learning.operation.query'), n); assert.equal(count(f, 'learning.run.confirm'), 1);
});

test('learning background revokes review/source authority but preserves reading and selection', async () => {
  const f = fixture('Learning'); await selectSource(f); f.hide(); f.show(); assert.match(f.text, /完整经过/); assert.match(f.text, /需重新读取并保留此来源/); assert.equal(f.button('建立学习任务，暂不发送模型').props.disabled, true); assert.equal(count(f, 'learning.create'), 0);
  f.click('返回学习任务'); f.row(source.title); await f.flush(); f.click('核对并继续原批次'); await f.flush(); f.hide(); f.show(); assert.equal(f.find(node => node.type === 'SettingsDialog', false).props.isOpen, false); assert.equal(count(f, 'learning.run.confirm'), 0);
});

test('unknown learning start does not expose a retry and keeps authoritative continuation flags', async () => {
  const f = fixture('Learning', { 'learning.run.confirm': () => { throw new Error('启动回执未知'); } }); await startLearning(f); assert.match(f.text, /启动回执尚未确定/); assert.equal(f.button('核对并继续原批次').props.disabled, true);
  f.values['learning.job'] = { ...job, canContinue: false, canSkipBatch: true, runId: 'run-current', epoch: 12, active: { stage: 'unknown' } }; f.click('重新核对任务水位'); await f.flush(); assert.equal(f.button('核对并继续原批次').props.disabled, true); assert.equal(count(f, 'learning.run.confirm'), 1);
});

test('stop contract prefers exact in-flight handle, then only a current run/epoch', () => {
  const f = fixture('Learning'); const stop = f.exported.learningStopRequest;
  assert.deepEqual(stop({ ...job, stale: true }, { taskId: job.taskId, operationHandle: 'op', settled: false }), { action: 'learning.operation.stop', args: { operationHandle: 'op' } });
  assert.equal(stop({ ...job, stale: true }, { stale: true }), null); assert.deepEqual(stop(job, { taskId: job.taskId, stale: true }), { action: 'learning.stop', args: { taskId: job.taskId, runId: '', epoch: 0 } });
  assert.equal(stop(job, { taskId: 'other-task', stale: true }), null); assert.equal(stop({ ...job, epoch: -1 }, null), null); assert.equal(f.exported.learningBatchStatus({ results: [{ outcome: 'skipped' }] }, 0), '已明确跳过，未知用量保留');
});

test('method and source-skill collections are separate; low-frequency controls are disclosures', async () => {
  const f = fixture('Experience'); await f.flush(); assert.match(f.text, /原方法标题/); assert.doesNotMatch(f.text, /原来源技能|限定 App 包名|完整 SKILL.md/);
  f.tab('skills'); assert.match(f.text, /原来源技能/); assert.doesNotMatch(f.text, /原方法完整内容/); assert.equal(count(f, 'experience.draft.start'), 0); assert.equal(count(f, 'experience.snapshot'), 1);
});

test('synchronous experience completion retrieves draft once; tabs and back retain the same edited draft', async () => {
  const f = fixture('Experience', { 'experience.draft.start': { operationHandle: 'experience-operation', settled: true }, 'experience.operation.query': { settled: true, result: draft } }); await startDraft(f);
  assert.equal(count(f, 'experience.operation.query'), 1); f.field('完整 SKILL.md', '保留修改的草稿'); f.tab('skills'); assert.match(f.text, /草稿待审阅/); f.tab('procedures'); assert.equal(f.value('完整 SKILL.md'), '保留修改的草稿');
  assert.equal(f.button('核对并保存为停用技能').props.disabled, true); f.click('返回方法'); f.row('继续审阅技能草稿'); assert.equal(f.value('完整 SKILL.md'), '保留修改的草稿'); assert.equal(count(f, 'experience.close'), 0); assert.equal(count(f, 'experience.draft.start'), 1);
});

test('draft edit -> inspection -> import review uses exact handles and leaves skill disabled', async () => {
  const f = fixture('Experience', { 'experience.operation.query': { settled: true, result: draft } }); await startDraft(f); f.field('完整 SKILL.md', '检查后的正文'); f.click('重新检查当前草稿'); await f.flush(); assert.deepEqual(last(f, 'experience.inspect'), { draftHandle: 'draft-original', manifest: '检查后的正文' });
  f.click('核对并保存为停用技能'); await f.flush(); assert.deepEqual(last(f, 'experience.import.review'), { draftHandle: 'draft-rechecked' }); assert.equal(f.button('确认这一次操作').props.disabled, true); f.check('我已核对原内容'); f.click('确认这一次操作'); await f.flush(); assert.deepEqual(last(f, 'experience.confirm'), { reviewHandle: 'review-original' }); assert.match(f.text, /保持停用/); assert.equal(count(f, 'experience.approve.review'), 0);
});

test('skill reading appends every original page and preserves reading when changing files/tabs', async () => {
  const f = fixture('Experience'); await f.flush(); f.tab('skills'); f.row('原来源技能'); await f.flush(); f.click('读取原正文'); await f.flush(); assert.equal(f.button('核对并批准此原版本').props.disabled, true); f.click('继续读取原文件'); await f.flush(); assert.match(f.text, /第一页第二页/);
  f.field('当前原文件', 'reference.txt'); f.click('读取原正文'); await f.flush(); f.tab('procedures'); f.tab('skills'); f.field('当前原文件', 'SKILL.md'); assert.match(f.text, /第一页第二页/); assert.equal(count(f, 'experience.read'), 3); assert.equal(f.button('核对并批准此原版本').props.disabled, false);
  f.click('核对并批准此原版本'); await f.flush(); assert.deepEqual(last(f, 'experience.approve.review'), { skillHandle: 'skill-original' }); assert.equal(count(f, 'experience.confirm'), 0);
});

test('experience query failure and stop acknowledgement never invent cancellation or permit replay', async () => {
  const f = fixture('Experience'); await startDraft(f); f.tab('jobs'); assert.match(f.text, /正在等待原起草回执/); f.click('请求停止这次起草'); await f.flush(); assert.deepEqual(last(f, 'experience.operation.stop'), { operationHandle: 'experience-operation' }); assert.match(f.text, /正在等待原起草回执/);
  f.values['experience.operation.query'] = () => { throw new Error('查询失效'); }; f.tick(); await f.flush(); assert.match(f.text, /结果未知/); f.tab('procedures'); assert.equal(f.button('核对并起草技能').props.disabled, true); assert.equal(count(f, 'experience.draft.start'), 1);
});

test('background or owner change revokes authority, preserves ordinary draft, and never reopens review', async () => {
  const f = fixture('Experience', { 'experience.operation.query': { settled: true, result: draft } }); await startDraft(f); f.field('完整 SKILL.md', '后台前的普通草稿'); f.owner('memoryReview'); f.owner('learning', 3); assert.equal(f.value('完整 SKILL.md'), '后台前的普通草稿'); assert.equal(f.button('重新检查当前草稿').props.disabled, true); assert.equal(f.button('核对并保存为停用技能').props.disabled, true); assert.match(f.text, /原起草窗口已失效/); assert.equal(count(f, 'experience.draft.start'), 1);
  f.remount(); await f.flush(); assert.equal(f.value('完整 SKILL.md'), '后台前的普通草稿'); assert.equal(f.button('核对并保存为停用技能').props.disabled, true);
});

test('quality and app review remain bound to the explicitly chosen method despite a selected skill', async () => {
  const f = fixture('Experience'); await openMethod(f); f.tab('skills'); f.row('原来源技能'); await f.flush(); f.tab('procedures'); f.expand('method-quality'); f.click('读取原试用记录'); await f.flush(); assert.deepEqual(last(f, 'experience.quality.list'), { kind: 'procedure', handle: 'procedure-original', cursor: '' });
  f.field('核对依据', '核对原任务的事实依据'); f.click('核对此原步骤的结果'); await f.flush(); assert.deepEqual(last(f, 'experience.quality.review'), { applicationHandle: 'application-original', outcome: 'verified', note: '核对原任务的事实依据' }); f.back();
  f.expand('method-app'); f.click('读取原任务观察到的 App 版本'); await f.flush(); f.field('原任务中的 App 版本', 'app-original'); const before = count(f, 'experience.procedure'); f.click('核对这次 App 经验起草'); await f.flush(); assert.deepEqual(last(f, 'experience.app.review'), { appHandle: 'app-original', baseHandle: '', lesson: false }); assert.equal(count(f, 'experience.procedure'), before); assert.equal(count(f, 'experience.draft.start'), 0);
});

test('file page contract rejects gaps and never treats a last-page fragment as full content', () => {
  const f = fixture('Experience'), append = f.exported.experienceFilePage;
  const first = append(undefined, { content: 'abc', nextOffset: 3, hasMore: true }, 0); const lastPage = append(first, { content: 'de', nextOffset: 5, hasMore: false }, 3);
  assert.equal(lastPage.content, 'abcde'); assert.throws(() => append(first, { content: 'x', nextOffset: 6 }, 5), /不连续/); assert.throws(() => append(first, { content: 'x', nextOffset: 9 }, 3), /不连续/);
});

test('a delayed model-start receipt after background cannot restore stale authority or hide the unknown', async () => {
  let finish: (value: Data) => void = () => {};
  const f = fixture('Experience', { 'experience.draft.start': () => new Promise(resolve => { finish = resolve; }) });
  await openMethod(f); f.click('核对并起草技能'); await f.flush(); f.check('我已核对原内容'); f.click('确认这一次操作'); f.hide(); f.show();
  finish({ operationHandle: 'late-operation', settled: true }); await f.flush(); assert.match(f.text, /原操作结果待核对/); assert.equal(count(f, 'experience.operation.query'), 0); assert.equal(count(f, 'experience.draft.start'), 1); assert.equal(f.button('核对并起草技能').props.disabled, true);
});

test('querying an old completed operation cannot overwrite a re-inspected draft', async () => {
  const f = fixture('Experience', { 'experience.operation.query': { settled: true, result: draft } }); await startDraft(f); f.field('完整 SKILL.md', '新的已检查正文'); f.click('重新检查当前草稿'); await f.flush(); f.click('核对同一次原请求'); await f.flush(); assert.equal(f.value('完整 SKILL.md'), '新的已检查正文'); f.click('核对并保存为停用技能'); await f.flush(); assert.deepEqual(last(f, 'experience.import.review'), { draftHandle: 'draft-rechecked' });
});

for (const kind of ['Learning', 'Experience'] as const) test(`${kind} first valid owner arrival loads the original collection once`, async () => {
  const f = fixture(kind, {}, 'memoryReview'); await f.flush();
  const action = kind === 'Learning' ? 'learning.jobs' : 'experience.snapshot';
  assert.equal(count(f, action), 0, 'old owner is not allowed to read learning data');
  f.owner('learning', 2); await f.flush();
  assert.equal(count(f, action), 1, 'first valid asynchronous owner arrival reads the collection');
  assert.match(f.text, kind === 'Learning' ? /原来源标题/ : /原方法标题/);
  f.owner('learning', 2); await f.flush(); f.tab('skills'); f.tab(kind === 'Learning' ? 'jobs' : 'procedures');
  assert.equal(count(f, action), 1, 'same-owner publications and local tabs do not duplicate the read');
  assert.equal(count(f, 'learning.run.confirm'), 0); assert.equal(count(f, 'experience.draft.start'), 0); assert.equal(count(f, 'experience.confirm'), 0);
});

test('unknown method import recovers the skill collection, never a skill retained in another panel', async () => {
  const f = fixture('Experience', { 'experience.operation.query': { settled: true, result: draft }, 'experience.confirm': () => { throw new Error('导入回执未知'); } });
  await f.flush(); f.tab('skills'); f.row('原来源技能'); await f.flush(); f.tab('procedures'); await startDraft(f);
  f.click('核对并保存为停用技能'); await f.flush(); f.check('我已核对原内容'); f.click('确认这一次操作'); await f.flush();
  const before = count(f, 'experience.snapshot'); f.click('读取相关原记录'); await f.flush();
  assert.equal(count(f, 'experience.snapshot'), before + 1, 'unknown new skill import reads the collection');
  assert.match(f.text, /阅读当前原版本/); assert.equal(count(f, 'experience.skill'), 1, 'unrelated retained skill is not reused as recovery');
});

test('unknown method quality keeps the reviewed method despite a retained skill', async () => {
  const f = fixture('Experience', { 'experience.confirm': () => { throw new Error('归责回执未知'); } }); await openMethod(f); f.tab('skills'); f.row('原来源技能'); await f.flush(); f.tab('procedures'); f.expand('method-quality'); f.click('读取原试用记录'); await f.flush(); f.field('核对依据', '原方法核对依据'); f.click('核对此原步骤的结果'); await f.flush(); f.check('我已核对原内容'); f.click('确认这一次操作'); await f.flush();
  const before = count(f, 'experience.procedure'); f.click('读取相关原记录'); await f.flush(); assert.equal(count(f, 'experience.procedure'), before + 1); assert.deepEqual(last(f, 'experience.procedure'), { id: procedure.record.id }); assert.equal(count(f, 'experience.skill'), 1);
});

test('selecting job B after a definitive A result ends A activity instead of relabeling its query', async () => {
  const other = { ...job, taskId: 'learning-B', job: { ...job.job, inputs: [{ title: '任务B来源' }] } };
  const f = fixture('Learning', { 'learning.jobs': { jobs: [job, other] }, 'learning.job': (args: Data) => args.taskId === other.taskId ? other : job, 'learning.operation.query': { taskId: job.taskId, settled: true, result: { ...job, canContinue: false } } });
  await startLearning(f); f.click('返回学习任务'); f.row('任务B来源'); await f.flush();
  assert.match(f.text, /任务B来源/); assert.doesNotMatch(f.text, /核对原调用回执/, 'A operation is not presented under B');
});

test('an unresolved learning operation does not let new task creation replace its original identity', async () => {
  const f = fixture('Learning'); await startLearning(f); f.click('返回学习任务'); await selectSource(f);
  assert.equal(f.button('建立学习任务，暂不发送模型').props.disabled, true, 'source draft stays editable but new task cannot replace unresolved A'); assert.match(f.text, /完整经过/); assert.equal(count(f, 'learning.create'), 0);
});

test('experience recovery maps every native review shape without using retained selections', () => {
  const f = fixture('Experience'), target = f.exported.experienceRecoveryTarget;
  for (const operation of ['draftExperience', 'draftAppRevision', 'reviewAppQuality']) assert.deepEqual(target({ operation, before: procedure.record }), { kind: 'procedure', id: procedure.record.id });
  for (const operation of ['approveExperience', 'reviewSkillQuality']) assert.deepEqual(target({ operation, before: skill.skill }), { kind: 'skill', id: skill.skill.id });
  assert.deepEqual(target({ operation: 'importExperience', before: { record: procedure.record, ...draft } }), { kind: 'skills' });
  assert.deepEqual(target({ operation: 'setAppLearningPolicy', before: { taskId: 'wrong-retained-task' }, payload: { taskId: 'reviewed-task' } }), { kind: 'policy', id: 'reviewed-task' });
  assert.equal(target({ operation: 'approveExperience', before: {} }), null);
});

test('unknown App policy mutation recovers its reviewed task authorization, not the retained skill', async () => {
  const policy = { taskId: 'source-task', policyHandle: 'policy-original', policy: { revision: 4, enabled: false, packages: [] }, jobs: [] };
  const f = fixture('Experience', { 'experience.policy.get': policy, 'experience.policy.review': { ...review, operation: 'setAppLearningPolicy', before: policy, payload: { taskId: 'source-task', enabled: true, packages: ['original.package'] } }, 'experience.confirm': () => { throw new Error('授权回执未知'); } });
  await f.flush(); f.tab('skills'); f.row('原来源技能'); await f.flush(); f.tab('procedures'); f.expand('app-policy'); f.field('原私人任务', 'source-task'); f.click('读取原授权'); await f.flush(); f.field('限定 App 包名', 'original.package'); f.click('核对此原任务的限定授权'); await f.flush(); f.check('我已核对原内容'); f.click('确认这一次操作'); await f.flush();
  f.click('读取相关原记录'); await f.flush(); assert.equal(count(f, 'experience.policy.get'), 2); assert.deepEqual(last(f, 'experience.policy.get'), { taskId: 'source-task' }); assert.equal(count(f, 'experience.skill'), 1); assert.equal(count(f, 'experience.confirm'), 1);
});

test('a stale learning call can be explicitly left only after rereading its original task, without inventing success', async () => {
  const other = { ...job, taskId: 'learning-B', taskStatus: 'running', runId: 'run-B', epoch: 13, job: { ...job.job, inputs: [{ title: '任务B来源' }] } };
  const f = fixture('Learning', { 'learning.jobs': { jobs: [job, other] }, 'learning.job': (args: Data) => args.taskId === other.taskId ? other : job, 'learning.stop': { ...other, taskStatus: 'stopping' } });
  await startLearning(f); f.hide(); f.show(); f.click('返回学习任务'); await selectSource(f);
  assert.equal(f.button('建立学习任务，暂不发送模型').props.disabled, true);
  let endDisabledBeforeRead: unknown = 'missing'; try { endDisabledBeforeRead = f.button('结束本地查看，原结果继续保留').props.disabled; } catch {}
  f.values['learning.job'] = (args: Data) => args.taskId === other.taskId ? other : { ...job, taskStatus: 'completed', canContinue: false, canSkipBatch: false };
  f.click('查看当前任务'); f.click('重新核对任务水位'); await f.flush();
  assert.match(f.text, /旧调用窗口已失效/); assert.equal(f.button('核对并继续原批次').props.disabled, true);
  assert.equal(endDisabledBeforeRead, true, 'stale snapshot is not enough to end the local view');
  assert.equal(f.button('结束本地查看，原结果继续保留').props.disabled, false);
  f.click('结束本地查看，原结果继续保留'); assert.match(f.text, /不表示旧调用成功或已取消/);
  f.click('返回学习任务'); f.row('继续整理 · 1 份所选来源'); assert.match(f.text, /完整经过/); assert.equal(f.button('建立学习任务，暂不发送模型').props.disabled, false);
  f.click('返回学习任务'); f.row('任务B来源'); await f.flush(); assert.match(f.text, /任务B来源/);
  assert.equal(count(f, 'learning.run.confirm'), 1); assert.equal(count(f, 'learning.operation.stop'), 0); assert.equal(count(f, 'learning.stop'), 0); assert.equal(count(f, 'learning.skip.confirm'), 0); assert.equal(count(f, 'learning.create'), 0);
  f.click('请求停止本次提炼'); await f.flush(); assert.deepEqual(last(f, 'learning.stop'), { taskId: 'learning-B', runId: 'run-B', epoch: 13 });
});
