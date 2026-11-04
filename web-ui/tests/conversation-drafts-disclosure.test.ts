import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';

// Execute the production component's state/handlers with an element-tree stub.
// This is logic coverage, not React DOM, focus, animation or visual validation.
const source = ts.createSourceFile('ConversationDrafts.tsx', readFileSync(new URL('../src/ConversationDrafts.tsx', import.meta.url), 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
const component = source.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'ConversationDrafts');
assert.ok(component);
const compiled = ts.transpileModule(component.getText(source).replace('export function', 'function'), { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
const instantiate = new Function('React', 'useEffect', 'useRef', 'useState', 'bridge', 'Accordion', 'AccordionItem', 'Button', 'RiRefreshLine', compiled + '\nreturn ConversationDrafts;');
const draft = { taskId: 'task-a', runId: 'run-a', operationId: 'op-a', kind: 'memory', title: '原任务提案', apply: { status: 'pending_owner' } };

function fixture() {
  const slots: any[] = [], effects: (() => void)[] = [], calls: any[] = [], opened: any[] = [];
  let index = 0, mounted = true, tree: any;
  const useState = (initial: any) => { const i = index++; slots[i] ??= { value: initial }; return [slots[i].value, (value: any) => { assert.ok(mounted); slots[i].value = value; }]; };
  const useRef = (initial: any) => { const i = index++; return slots[i] ??= { current: initial }; };
  const useEffect = (effect: () => void | (() => void), deps: unknown[]) => {
    const i = index++; if (!slots[i]) { slots[i] = { deps }; effects.push(() => { slots[i].cleanup = effect(); }); }
  };
  const React = { createElement: (type: unknown, props: any, ...children: any[]) => ({ type, props: props || {}, children: children.flat(Infinity).filter(Boolean) }) };
  const renderComponent = instantiate(React, useEffect, useRef, useState, { command: (action: string, args: any) => new Promise((resolve, reject) => calls.push({ action, args, resolve, reject })) }, 'Accordion', 'AccordionItem', 'Button', 'RefreshIcon');
  const render = () => { index = 0; tree = renderComponent({ taskId: 'task-a', open: (value: unknown) => opened.push(value) }); effects.splice(0).forEach(effect => effect()); };
  const nodes = (node: any): any[] => node && typeof node === 'object' ? [node, ...(node.children || []).flatMap(nodes)] : [];
  render();
  return {
    calls, opened, render,
    get accordion() { return nodes(tree).find(node => node.type === 'Accordion'); },
    get item() { return nodes(tree).find(node => node.type === 'AccordionItem'); },
    get buttons() { return nodes(tree).filter(node => node.type === 'Button'); },
    get alerts() { return nodes(tree).filter(node => node.props.role === 'alert'); },
    toggle(open: boolean) { this.accordion.props.onExpandedChange(new Set(open ? ['drafts'] : [])); render(); },
    async settle(value: unknown, error?: string) { const call = calls.at(-1); if (error) call.reject(new Error(error)); else call.resolve(value); await Promise.resolve(); await Promise.resolve(); if (mounted) render(); },
    unmount() { slots.forEach(slot => slot.cleanup?.()); mounted = false; },
  };
}

test('proposals read only on first expansion, collapse in place, and retain explicit refresh', async () => {
  const f = fixture(); assert.equal(f.calls.length, 0); assert.deepEqual(f.accordion.props.expandedKeys, []);
  f.toggle(true); assert.deepEqual(f.calls.map(call => [call.action, call.args]), [['conversation.drafts', { taskId: 'task-a' }]]);
  f.toggle(false); f.toggle(true); assert.equal(f.calls.length, 1, 'rapid toggles must not duplicate the read');
  await f.settle({ taskId: 'task-a', bodyIncluded: false, drafts: [draft] });
  assert.equal(f.item.props.description, '1 项已读取');
  f.toggle(false); assert.deepEqual(f.accordion.props.expandedKeys, []);
  f.toggle(true); assert.equal(f.calls.length, 1, 'reopening retains the settled original window');
  f.buttons[0].props.onClick(); assert.deepEqual(f.opened, [draft]); assert.equal(f.calls.length, 1, 'opening review does not apply the proposal');
  f.buttons.at(-1).props.onClick(); assert.equal(f.calls.length, 2);
});

test('partial and foreign proposal results never become a complete or different task list', async () => {
  const f = fixture(); f.toggle(true);
  await f.settle({ taskId: 'task-a', bodyIncluded: false, partial: true, drafts: [draft, { ...draft, taskId: 'foreign' }] });
  assert.equal(f.item.props.description, '1 项已读取 · 部分记录');
  assert.equal(f.buttons.length, 2);
  f.buttons.at(-1).props.onClick(); await f.settle({ taskId: 'foreign', bodyIncluded: false, drafts: [] });
  assert.equal(f.alerts.length, 1); assert.equal(f.item.props.description, '1 项已读取 · 部分记录');
});

test('failed first read stays retryable without applying anything', async () => {
  const f = fixture(); f.toggle(true); await f.settle(null, '暂不可读取');
  assert.equal(f.alerts.length, 1); assert.equal(f.buttons.at(-1).children[0], '重新读取提案');
  f.buttons.at(-1).props.onClick(); await f.settle({ taskId: 'task-a', bodyIncluded: false, drafts: [] });
  assert.equal(f.alerts.length, 0); assert.equal(f.item.props.description, '0 项已读取');
  assert.ok(f.calls.every(call => call.action === 'conversation.drafts'));
});

test('unmount ignores late proposal results', async () => {
  const f = fixture(); f.toggle(true); f.unmount(); await f.settle({ taskId: 'task-a', bodyIncluded: false, drafts: [draft] });
  assert.equal(f.calls.length, 1); assert.equal(f.opened.length, 0);
});
