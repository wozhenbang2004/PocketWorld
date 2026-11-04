import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import { commitmentDraft, commitmentMutationReceipt, commitmentPolicyReview, commitmentReview, OwnerWorkspaceRequest } from '../src/commitment-contract.ts';
import { newPlanStep, planCreateInput, planMutationReceipt, planState, planStepDraft, planStepInput } from '../src/plan-contract.ts';

// Executes production handlers with explicit hook, control and DOM-ref doubles.
// This is not a browser, visual, keyboard or native-host acceptance claim.
type Data = Record<string, any>;
type Call = { action: string; args: Data; resolve: (value: Data) => void };
function fixture(file: 'CommitmentsWorkspace' | 'PlansWorkspace', component = file as string) {
  const source = ts.createSourceFile(file, readFileSync(new URL('../src/' + file + '.tsx', import.meta.url), 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const body = source.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(source).replace(/^export (default )?/, '')).join('\n');
  const compiled = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
  const slots: any[] = [], effects: (() => void)[] = [], layouts: (() => void)[] = [], calls: Call[] = [], focus: string[] = [];
  const listeners = new Map<string, () => void>();
  let index = 0, tree: any, dirty = false, back: (() => boolean) | null = null;
  const useState = (initial: any) => { const i = index++; slots[i] ??= { value: typeof initial === 'function' ? initial() : initial }; return [slots[i].value, (next: any) => { const value = typeof next === 'function' ? next(slots[i].value) : next; if (!Object.is(value, slots[i].value)) { slots[i].value = value; dirty = true; } }]; };
  const useRef = (initial: any) => { const i = index++; return slots[i] ??= { current: initial }; };
  const effect = (queue: (() => void)[]) => (callback: () => void | (() => void), deps?: any[]) => { const i = index++, prior = slots[i]; if (!deps || !prior || deps.some((v, j) => !Object.is(v, prior.deps?.[j]))) { const slot = slots[i] = { deps, cleanup: prior?.cleanup }; queue.push(() => { slot.cleanup?.(); slot.cleanup = callback(); }); } };
  const React = { Fragment: 'Fragment', createElement: (type: any, props: any, ...children: any[]): any => typeof type === 'function' && ['PlanContents', 'StepFields'].includes(type.name) ? type({ ...props, children }) : ({ type, props: props || {}, children: children.flat(Infinity).filter(child => child !== null && child !== undefined && child !== false) }) };
  const controls = Object.fromEntries(['Tabs', 'TabList', 'Tab', 'TabPanel', 'Accordion', 'AccordionItem', 'Button', 'PressButton', 'CheckboxField', 'SelectField', 'SettingsDialog', 'TextAreaField', 'TextField', 'RiAddLine', 'RiArrowLeftSLine', 'RiArrowRightSLine', 'RiRefreshLine', 'RiSettings3Line'].map(key => [key, key]));
  const document = { visibilityState: 'visible', addEventListener: (name: string, callback: () => void) => listeners.set(name, callback), removeEventListener: (name: string) => listeners.delete(name) };
  const window = { addEventListener: () => {}, removeEventListener: () => {} };
  const dependencies = { React, useState, useRef, useEffect: effect(effects), useLayoutEffect: effect(layouts), ...controls, document, window, commitmentDraft, commitmentMutationReceipt, commitmentPolicyReview, commitmentReview, OwnerWorkspaceRequest, newPlanStep, planCreateInput, planMutationReceipt, planState, planStepDraft, planStepInput, ...(file === 'CommitmentsWorkspace' ? { PlansWorkspace: 'PlansWorkspace' } : {}) };
  const Component = new Function(...Object.keys(dependencies), compiled + '\nreturn ' + component + ';')(...Object.values(dependencies));
  const props = { state: {} as Data, supported: () => true, registerTransientBack: (handler: (() => boolean) | null) => { back = handler; }, onEditingChange: () => {}, run: (action: string, args: Data = {}) => new Promise(resolve => { calls.push({ action, args, resolve }); if (action.endsWith('.discard')) resolve({}); }) };
  const nodes = (node: any): any[] => node && typeof node === 'object' ? [node, ...(node.children || []).flatMap(nodes)] : [];
  const content = (node: any): string => node && typeof node === 'object' ? (node.children || []).map(content).join('') : String(node ?? '');
  const scroller = { scrollTop: 0 };
  const find = (predicate: (node: any) => boolean) => { const node = nodes(tree).find(predicate); assert.ok(node, 'expected production element'); return node; };
  const render = () => { do { dirty = false; index = 0; tree = Component(props); for (const node of nodes(tree)) if (node.props.ref) node.props.ref.current = { closest: () => scroller, focus: () => focus.push(node.props.className === 'pw-followup-collection' ? 'collection' : 'detail'), querySelectorAll: () => nodes(tree).filter(row => row.props['data-commitment-id'] || row.props['data-plan-id']).map(row => ({ dataset: { commitmentId: row.props['data-commitment-id'], planId: row.props['data-plan-id'] }, focus: () => focus.push(row.props['data-commitment-id'] || row.props['data-plan-id']) })) }; layouts.splice(0).forEach(run => run()); effects.splice(0).forEach(run => run()); } while (dirty); };
  render();
  return { calls, focus, scroller, props, find, render, get all() { return nodes(tree); }, get text() { return content(tree); },
    click(label: string) { const node = find(node => ['Button', 'PressButton'].includes(node.type) && (node.props['aria-label'] === label || content(node) === label)); assert.ok(!node.props.disabled, label + ' must be enabled'); node.props.onClick(); render(); },
    back() { const handled = back?.(); render(); return handled; },
    visibility(value: string) { document.visibilityState = value; listeners.get('visibilitychange')?.(); render(); },
    async settle(call: Call, value: Data) { assert.ok(call, 'expected native request'); call.resolve(value); for (let i = 0; i < 8; i++) await Promise.resolve(); render(); } };
}
const commitment = { id: 'commitment-a', revision: 4, title: '交付原报告', details: '完整要求\n保留下一行', status: 'pending', dueAt: 1700000000000, followUpDue: true, taskId: 'task-original' };
const commitmentSnapshot = { commitments: [commitment], policy: { revision: 3, enabled: true }, completionEvidenceAvailable: true };
const plan = { id: 'plan-a', revision: 7, title: '完成正式交付', goal: '保留完整目标与依据', state: 'waiting_for_original_task', approvalValid: true, originalTasksUnsettled: true, runtimeDecision: { reason: '等待原任务回执', nextAt: 1700000005000 }, execution: { state: 'running', started: true, admissions: 2, maxTaskAdmissions: 6, policy: { expiresAt: 1700000100000, zoneId: 'UTC' } }, steps: [{ id: 'step-a', title: '核对原成果', goal: '检查完整成果', status: 'running', dueAt: 1700000000000, taskId: 'task-original', taskObservation: { status: 'delivery_unknown' }, dependencies: [] }, { id: 'step-b', goal: '等待核验结果', status: 'pending', dueAt: 1700000001000, dependencies: [{ stepId: 'step-a', outcome: 'completed' }] }] };

test('follow-up tabs mount one workspace at a time and cannot switch during a child review', () => {
  const f = fixture('CommitmentsWorkspace');
  assert.equal(f.all.filter(node => node.type === 'TabPanel').length, 1);
  const child = f.find(node => typeof node.type === 'function' && node.type.name === 'CommitmentList');
  child.props.onEditingChange(true); f.render();
  f.find(node => node.type === 'Tabs').props.onSelectionChange('plans'); f.render();
  assert.equal(f.find(node => node.type === 'TabPanel').props.id, 'commitments');
  assert.equal(f.find(node => node.type === 'Tab' && node.props.id === 'plans').props.isDisabled, true);
  child.props.onEditingChange(false); f.render(); f.find(node => node.type === 'Tabs').props.onSelectionChange('plans'); f.render();
  assert.equal(f.find(node => node.type === 'TabPanel').props.id, 'plans');
  assert.equal(f.all.some(node => typeof node.type === 'function' && node.type.name === 'CommitmentList'), false);
  assert.equal(f.all.filter(node => node.type === 'PlansWorkspace').length, 1);
});

test('commitment row opens one original detail; native back restores position, focus and releases its read', async () => {
  const f = fixture('CommitmentsWorkspace', 'CommitmentList'); await f.settle(f.calls[0], commitmentSnapshot);
  assert.ok(f.find(node => node.props['data-commitment-id'] === commitment.id).props['aria-description'].includes('已到跟进时间'));
  f.scroller.scrollTop = 260; f.click('查看目标与跟进：交付原报告');
  const read = f.calls.findLast(call => call.action === 'commitments.read')!; assert.deepEqual(read.args, { id: commitment.id });
  await f.settle(read, { commitment, viewHandle: 'commitment-view' });
  assert.equal(f.find(node => node.props.className === 'pw-followup-collection').props.hidden, true);
  assert.equal(f.scroller.scrollTop, 0); assert.ok(f.text.includes(commitment.details));
  assert.equal(f.back(), true); assert.equal(f.scroller.scrollTop, 260); assert.equal(f.focus.at(-1), commitment.id);
  assert.ok(f.calls.some(call => call.action === 'commitments.discard' && call.args.viewHandle === 'commitment-view'));
  assert.equal(f.back(), false);
});

test('commitment actions still require original review and a confirmed native mutation receipt', async () => {
  const f = fixture('CommitmentsWorkspace', 'CommitmentList'); await f.settle(f.calls[0], commitmentSnapshot);
  f.click('查看目标与跟进：交付原报告'); await f.settle(f.calls.findLast(call => call.action === 'commitments.read')!, { commitment, viewHandle: 'commitment-view' });
  f.click('暂缓一小时'); const review = f.calls.findLast(call => call.action === 'commitments.review')!;
  assert.deepEqual(review.args, { operation: 'snooze', viewHandle: 'commitment-view', hours: 1 });
  assert.equal(f.calls.some(call => call.action === 'commitments.confirm'), false);
  await f.settle(review, { reviewHandle: 'review-snooze', command: 'snoozeCommitment', input: { until: 1700000100000 } });
  f.find(node => node.type === 'CheckboxField' && node.props.onChange && node.children.includes('我已核对这次原记录与操作')).props.onChange(true); f.render();
  f.click('确认暂缓跟进'); const confirm = f.calls.findLast(call => call.action === 'commitments.confirm')!; assert.deepEqual(confirm.args, { reviewHandle: 'review-snooze' });
  await f.settle(confirm, { accepted: true });
  assert.ok(f.text.includes('这次写入尚未确认')); assert.equal(f.text.includes('已收到原生保存回执'), false);
  assert.equal(f.find(node => node.type === 'Button' && node.children.includes('暂缓一小时')).props.disabled, true);
});

test('backgrounding invalidates an in-flight commitment read without opening a stale detail', async () => {
  const f = fixture('CommitmentsWorkspace', 'CommitmentList'); await f.settle(f.calls[0], commitmentSnapshot);
  f.click('查看目标与跟进：交付原报告'); const read = f.calls.findLast(call => call.action === 'commitments.read')!;
  f.visibility('hidden'); await f.settle(read, { commitment, viewHandle: 'stale-view' });
  assert.equal(f.all.some(node => node.props['aria-label'] === '当前委托详情'), false);
  assert.equal(f.calls.some(call => call.action === 'commitments.confirm'), false);
});

test('plan detail keeps stop controls, uncertainty and budget visible while step commands stay with the original step', async () => {
  const f = fixture('PlansWorkspace'); await f.settle(f.calls[0], { plans: [plan] });
  f.scroller.scrollTop = 180; f.click('查看目标与原任务：完成正式交付');
  await f.settle(f.calls.findLast(call => call.action === 'plans.read')!, { plan, viewHandle: 'plan-view' });
  assert.equal(f.find(node => node.props.className === 'pw-followup-collection').props.hidden, true);
  assert.ok(f.text.includes('2/6 次，修改不清零')); assert.ok(f.text.includes('暂停后不会误报已经停止'));
  assert.equal(f.find(node => node.type === 'Button' && node.children.includes('暂停')).props.disabled, false);
  assert.equal(f.find(node => node.type === 'Button' && node.children.includes('取消目标')).props.disabled, false);
  const step = f.find(node => node.type === 'AccordionItem' && node.props.id === 'step-a');
  assert.ok(step.props.description); assert.equal(f.find(node => node.type === 'Accordion' && node.props.className === 'pw-followup-step-list').props.allowsMultipleExpanded, undefined);
  f.click('核对原任务状态'); const inspect = f.calls.findLast(call => call.action === 'plans.inspect')!; assert.deepEqual(inspect.args, { viewHandle: 'plan-view' });
  f.scroller.scrollTop = 120; await f.settle(inspect, { plan, viewHandle: 'plan-view-new' }); assert.equal(f.scroller.scrollTop, 120, 'same object refresh must not reset reading position');
  f.click('返回目标列表'); assert.equal(f.scroller.scrollTop, 180); assert.equal(f.focus.at(-1), plan.id);
  assert.ok(f.calls.some(call => call.action === 'plans.discard' && call.args.viewHandle === 'plan-view-new'));
});

test('paused-step retry is bound to its original step and opens a full, uncollapsed confirmation review', async () => {
  const paused = { ...plan, state: 'paused', execution: { ...plan.execution, state: 'paused' }, steps: [{ ...plan.steps[0], status: 'failed', taskObservation: { status: 'failed', terminalVerified: true } }, plan.steps[1]] };
  const f = fixture('PlansWorkspace'); await f.settle(f.calls[0], { plans: [paused] }); f.click('查看目标与原任务：完成正式交付');
  await f.settle(f.calls.findLast(call => call.action === 'plans.read')!, { plan: paused, viewHandle: 'paused-view' });
  f.click('重新审阅步骤 1 的重试'); const review = f.calls.findLast(call => call.action === 'plans.reviewAction')!;
  assert.deepEqual(review.args, { viewHandle: 'paused-view', operation: 'retry', stepId: 'step-a' });
  await f.settle(review, { reviewHandle: 'retry-review', command: 'retry', plan: paused, input: { stepId: 'step-a' } });
  const dialog = f.find(node => node.type === 'SettingsDialog' && node.props.isOpen);
  const all = (node: any): any[] => [node, ...(node.children || []).filter((child: any) => child && typeof child === 'object').flatMap(all)];
  assert.equal(all(dialog).some(node => node.props?.className === 'pw-followup-step-list'), false, 'exact review must not collapse the plan scope');
  assert.equal(all(dialog).filter(node => node.props?.className === 'pw-source-record').length, 2);
  assert.equal(f.calls.some(call => call.action === 'plans.confirm'), false);
});

test('returning after a confirmed snooze falls back to the filtered collection when its row disappears', async () => {
  const f = fixture('CommitmentsWorkspace', 'CommitmentList'); await f.settle(f.calls[0], commitmentSnapshot);
  f.find(node => node.type === 'CheckboxField' && node.children.includes('只看已到跟进时间')).props.onChange(true); f.render();
  f.click('查看目标与跟进：交付原报告'); await f.settle(f.calls.findLast(call => call.action === 'commitments.read')!, { commitment, viewHandle: 'commitment-view' });
  f.click('暂缓一小时'); await f.settle(f.calls.findLast(call => call.action === 'commitments.review')!, { reviewHandle: 'review-snooze', command: 'snoozeCommitment', input: { until: 1700000100000 } });
  f.find(node => node.type === 'CheckboxField' && node.children.includes('我已核对这次原记录与操作')).props.onChange(true); f.render(); f.click('确认暂缓跟进');
  await f.settle(f.calls.findLast(call => call.action === 'commitments.confirm')!, { confirmed: true, command: 'snoozeCommitment', result: { commitment: { ...commitment, status: 'snoozed', followUpDue: false } } });
  f.back(); assert.equal(f.all.some(node => node.props['data-commitment-id'] === commitment.id), false);
  assert.equal(f.focus.at(-1), 'collection'); assert.equal(f.find(node => node.props.className === 'pw-followup-collection').props.tabIndex, -1);
});

test('visible plan budget distinguishes unread counts and missing expiry from explicitly no expiry', async () => {
  for (const policy of [{}, { expiresAt: null }]) {
    const current = { ...plan, execution: { state: 'running', started: true, policy } };
    const f = fixture('PlansWorkspace'); await f.settle(f.calls[0], { plans: [current] }); f.click('查看目标与原任务：完成正式交付');
    await f.settle(f.calls.findLast(call => call.action === 'plans.read')!, { plan: current, viewHandle: 'plan-view' });
    assert.ok(f.text.includes('未读取/未读取 次，修改不清零'));
    assert.ok(f.text.includes('原授权到期' + ('expiresAt' in policy ? '未设置到期' : '未提供')));
  }
});
