import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import { getTaskOutcome } from '../src/task-outcome.ts';

// Execute the production JSX and handlers with explicit hook/control doubles.
// This is contract coverage, not DOM layout, React Aria or visual acceptance.
type Data = Record<string, any>;
type Call = { action: string; args: Data; resolve: (value: Data) => void };
function fixture(file: string, name: string, props: Data) {
  const source = ts.createSourceFile(file, readFileSync(new URL('../src/' + file, import.meta.url), 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const body = source.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(source).replace(/^export /, '')).join('\n');
  const compiled = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
  const slots: any[] = [], effects: (() => void)[] = [], calls: Call[] = [];
  let index = 0, tree: any, dirty = false;
  const useState = (initial: any) => {
    const i = index++; slots[i] ??= { value: typeof initial === 'function' ? initial() : initial };
    return [slots[i].value, (next: any) => { const value = typeof next === 'function' ? next(slots[i].value) : next; if (!Object.is(value, slots[i].value)) { slots[i].value = value; dirty = true; } }];
  };
  const useRef = (initial: any) => { const i = index++; return slots[i] ??= { current: initial }; };
  const useEffect = (effect: () => void | (() => void), deps: any[]) => {
    const i = index++, prior = slots[i];
    if (!prior || deps.some((v, j) => !Object.is(v, prior.deps[j]))) {
      const slot = slots[i] = { deps, cleanup: prior?.cleanup };
      effects.push(() => { slot.cleanup?.(); slot.cleanup = effect(); });
    }
  };
  const React = { Fragment: 'Fragment', createElement: (type: any, props: any, ...children: any[]): any => {
    const values = children.flat(Infinity).filter(child => child !== null && child !== undefined && child !== false);
    return typeof type === 'function' ? type({ ...props, children: values }) : { type, props: props || {}, children: values };
  } };
  const dependencies = { React, useState, useRef, useEffect, getTaskOutcome,
    bridge: { command: (action: string, args: Data) => new Promise(resolve => calls.push({ action, args, resolve })) },
    Accordion: 'Accordion', AccordionItem: 'AccordionItem', Button: 'Button', AIAnswer: 'AIAnswer',
    RiArrowLeftSLine: 'PreviousIcon', RiArrowRightSLine: 'NextIcon', RiRefreshLine: 'RefreshIcon', RiFileLine: 'FileIcon' };
  const Component = new Function(...Object.keys(dependencies), compiled + '\nreturn ' + name + ';')(...Object.values(dependencies));
  const nodes = (node: any): any[] => node && typeof node === 'object' ? [node, ...(node.children || []).flatMap(nodes)] : [];
  const text = (node: any, visible = false): string => node && typeof node === 'object' ? visible && node.type === 'AccordionItem' ? '' : (node.children || []).map((child: any) => text(child, visible)).join('') : String(node ?? '');
  const render = () => { do { dirty = false; index = 0; tree = Component(props); effects.splice(0).forEach(run => run()); } while (dirty); };
  const find = (predicate: (node: any) => boolean) => { const node = nodes(tree).find(predicate); assert.ok(node, 'expected production element'); return node; };
  render();
  return { calls, render, find, get all() { return nodes(tree); }, get text() { return text(tree); }, get visibleText() { return text(tree, true); },
    click(label: string) { const node = find(node => node.type === 'Button' && (node.props['aria-label'] === label || text(node) === label)); assert.ok(!node.props.disabled); node.props.onClick(); render(); },
    change(next: Data) { Object.assign(props, next); render(); },
    async settle(call: Call, value: Data) { call.resolve(value); for (let i = 0; i < 6; i++) await Promise.resolve(); render(); } };
}
const usage = (taskId = 'task-a') => ({ taskId, readOnly: true, executionAuthorized: false, headline: '保留的累计用量', coverage: '只覆盖已保存的调用', warnings: ['非最终计数'], money: ['原费用未知'], groups: [], calls: [{ id: 'call-a', purpose: '原模型调用', apiKey: true, counters: [], pricing: [] }], cursor: 0, nextCursor: -1 });
async function ready() {
  const f = fixture('TaskInference.tsx', 'TaskInference', { taskId: 'task-a' });
  assert.equal(f.calls.length, 0, 'rendering does not automatically read or estimate');
  f.click('查看累计模型调用与用量'); await f.settle(f.calls[0], usage()); return f;
}

test('usage stays manually loaded; scope and warnings are visible before diagnostic disclosures', async () => {
  const f = await ready();
  assert.deepEqual(f.calls[0].args, { taskId: 'task-a', cursor: 0 });
  assert.ok(f.visibleText.includes('只覆盖已保存的调用')); assert.ok(f.visibleText.includes('非最终计数'));
  assert.equal(f.visibleText.includes('原费用未知'), false);
  assert.ok(f.text.includes('原费用未知'));
});
test('native non-quote outcomes retain their precise reason and original receipt', async () => {
  for (const result of [
    { available: false, callId: 'call-a', reason: 'receipt_not_found', maxBytes: 65536 },
    { available: false, callId: 'call-a', reason: 'receipt_identity_conflict', maxBytes: 65536 },
    { available: false, callId: 'call-a', reason: 'receipt_read_limited', maxBytes: 65536 },
    { known: false, reason: 'price_host_unavailable', newInference: false },
  ]) {
    const f = await ready(); f.click('按当前价目另行试算');
    assert.deepEqual(f.calls[1].args, { taskId: 'task-a', callId: 'call-a' }); await f.settle(f.calls[1], result);
    assert.ok(f.text.includes('无法另行试算')); assert.ok(f.text.includes('未知金额不代表免费'));
    assert.equal(f.text.includes('试算回执身份不可核对'), false);
    assert.deepEqual(JSON.parse(f.find(node => node.type === 'pre').children[0]), result);
  }
});
test('alternate quotes keep exact decimals and unknown costs; identity mismatches stay rejected', async () => {
  const f = await ready(); f.click('按当前价目另行试算');
  await f.settle(f.calls[1], { format: 'pocketworld.inference-reestimate.v1', callId: 'call-a', originalReceiptUnchanged: true, newInference: false, known: true, estimate: { currency: 'USD', knownTokenSubtotal: '0.00000123456789', estimatedTokenCost: null, allTokenComponentsPriced: false, finalCounters: false } });
  assert.ok(f.text.includes('USD 0.00000123456789')); assert.ok(f.text.includes('未知（未按 0 计算）')); assert.ok(f.text.includes('含非最终或不完整计数'));
  f.click('按当前价目另行试算'); await f.settle(f.calls[2], { format: 'pocketworld.inference-reestimate.v1', callId: 'different-call', originalReceiptUnchanged: true, newInference: false, known: true });
  assert.ok(f.text.includes('原调用的试算回执身份不可核对')); assert.equal(f.all.some(node => node.type === 'pre'), false);
});
test('late usage response from a previous task cannot populate the new task', async () => {
  const f = fixture('TaskInference.tsx', 'TaskInference', { taskId: 'task-a' }); f.click('查看累计模型调用与用量');
  f.change({ taskId: 'task-b' }); await f.settle(f.calls[0], usage());
  assert.equal(f.text.includes('保留的累计用量'), false); f.click('查看累计模型调用与用量');
  assert.deepEqual(f.calls[1].args, { taskId: 'task-b', cursor: 0 });
});
test('linked message files are visible and keep exact host identities, outside collapsed reply', () => {
  const opened: any[] = [], file = { id: 'file-a', sha256: 'a'.repeat(64), name: '原交付文件.txt' };
  const f = fixture('TaskOutcome.tsx', 'TaskOutcome', { task: { id: 'task-a', summary: '当前摘要' }, messages: [{ id: 'message-a', taskId: 'task-a', role: 'assistant', content: '保留答复', attachments: [file] }], onCopy: async () => {}, onOpenAttachment: (...args: any[]) => opened.push(args) });
  assert.ok(f.visibleText.includes(file.name)); assert.equal(f.visibleText.includes('保留答复'), false);
  f.find(node => node.type === 'Button').props.onClick(); assert.deepEqual(opened, [['message-a', 'file-a', file.sha256]]);
});
test('attachment without verifiable original identity stays disabled', () => {
  const f = fixture('TaskOutcome.tsx', 'TaskOutcome', { task: { id: 'task-a' }, messages: [{ taskId: 'task-a', role: 'assistant', attachments: [{ id: 'file-a', name: '待核对文件' }] }], onCopy: async () => {}, onOpenAttachment: () => { throw new Error('must not run'); } });
  assert.equal(f.find(node => node.type === 'Button').props.disabled, true); assert.ok(f.visibleText.includes('原文件身份待核对'));
});
test('compact decision title and next action preserve distinct callbacks; resolved rows stay disabled', async () => {
  const decisions = [{ id: 'task-a', title: '核对原任务', detail: '需要确认', primary: '处理', secondary: '查看任务' }], calls: any[] = [];
  const f = fixture('shell-library/DecisionInbox.tsx', 'DecisionInbox', { decisions, compact: true, onDecide: (...args: any[]) => calls.push(args) });
  f.click('查看任务：核对原任务'); for (let i = 0; i < 4; i++) await Promise.resolve(); f.render();
  f.click('处理'); for (let i = 0; i < 4; i++) await Promise.resolve(); f.render();
  assert.deepEqual(calls, [[decisions[0], 'secondary'], [decisions[0], 'primary']]);
  f.change({ decisions: [{ ...decisions[0], resolved: '已核对' }] });
  assert.equal(f.find(node => node.props.className === 'ui-dic__compact-open').props.disabled, true);
});
