import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';

type Data = Record<string, any>;
const source = ts.createSourceFile('PermissionPolicySettings.tsx', readFileSync(new URL('../src/PermissionPolicySettings.tsx', import.meta.url), 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
const body = source.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(source).replace(/^export /, '')).join('\n');
const code = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
const policy = (mode = 'ask', tools: Data = {}) => ({ version: 1, mode, tools });
const snapshot = (conversationId = 'conversation-a', revision = 7, conversation: Data | null = null) => ({
  conversationId, revision, global: policy('controlled', { ssh_read: 'allow' }), conversation,
  effective: conversation || policy('controlled', { ssh_read: 'allow' }),
});
const deferred = () => { let resolve!: (value: any) => void, reject!: (error: Error) => void; const promise = new Promise<any>((done, fail) => { resolve = done; reject = fail; }); return { promise, resolve, reject }; };

// Executes the production component's handlers with lifecycle doubles. These
// tests cover save/pending ownership, not React Aria geometry or Android storage.
function fixture(read: () => Promise<any>, write: (args: Data) => Promise<any>) {
  const slots: any[] = [], effects: (() => void)[] = [], calls: Data[] = [], pending: boolean[] = [];
  let index = 0, dirty = false, tree: any, mounted = true, writesAfterUnmount = 0;
  const useState = (initial: any) => { const i = index++; slots[i] ??= { value: typeof initial === 'function' ? initial() : initial }; return [slots[i].value, (next: any) => { if (!mounted) writesAfterUnmount++; const value = typeof next === 'function' ? next(slots[i].value) : next; if (!Object.is(value, slots[i].value)) { slots[i].value = value; dirty = true; } }]; };
  const useRef = (initial: any) => { const i = index++; return slots[i] ??= { current: initial }; };
  const useEffect = (effect: () => any, deps: any[]) => { const i = index++, prior = slots[i]; if (!prior || deps.some((value, j) => !Object.is(value, prior.deps[j]))) { const slot = slots[i] = { deps, cleanup: prior?.cleanup }; effects.push(() => { slot.cleanup?.(); slot.cleanup = effect(); }); } };
  const React = { createElement: (type: any, props: any, ...children: any[]) => ({ type, props: props || {}, children: children.flat(Infinity).filter(child => child != null && child !== false) }) };
  const dependencies: Data = { React, useState, useRef, useEffect, Button: 'Button', TextField: 'TextField', RadioOptions: 'RadioOptions', Accordion: 'Accordion', AccordionItem: 'AccordionItem' };
  const Component = new Function(...Object.keys(dependencies), code + '\nreturn PermissionPolicySettings;')(...Object.values(dependencies));
  const props: Data = { conversationId: 'conversation-a', onPendingChange: (value: boolean) => pending.push(value),
    act: (action: string, args?: Data) => { calls.push({ action, args, pendingAtCall: pending.at(-1) }); return action === 'permissions.snapshot' ? read() : write(args!); } };
  const render = () => { if (!mounted) return; let count = 0; do { assert.ok(count++ < 30, 'production effects settle'); dirty = false; index = 0; tree = Component(props); effects.splice(0).forEach(effect => effect()); } while (dirty); };
  const walk = (node: any): any[] => !node || typeof node !== 'object' ? [] : [node, ...node.children.flatMap(walk)];
  const content = (node: any): string => !node || typeof node !== 'object' ? String(node ?? '') : node.children.map(content).join('');
  const find = (predicate: (node: any) => boolean) => { const node = walk(tree).find(predicate); assert.ok(node, 'expected production control'); return node; };
  const field = (label: string) => find(node => ['RadioOptions', 'TextField'].includes(node.type) && node.props.label === label);
  const button = (label: string) => find(node => node.type === 'Button' && content(node) === label);
  render();
  return { calls, pending, find, field, button, render, get text() { return content(tree); }, get nodes() { return walk(tree); }, get writesAfterUnmount() { return writesAfterUnmount; },
    edit(label: string, value: string) { const node = field(label); assert.ok(!node.props.isDisabled); node.props.onChange(value); render(); },
    click(label: string) { const node = button(label); assert.ok(!node.props.disabled); node.props.onClick(); render(); },
    changeConversation(value: string) { props.conversationId = value; render(); },
    unmount() { mounted = false; slots.forEach(slot => slot.cleanup?.()); },
    async flush() { for (let i = 0; i < 8; i++) { await Promise.resolve(); if (dirty) render(); } },
  };
}

test('scope stays native and compact mode rows carry their own descriptions', async () => {
  const native = snapshot();
  const f = fixture(async () => native, async () => native); await f.flush();
  assert.equal(f.field('应用范围').props.value, 'conversation');
  assert.deepEqual(f.field('应用范围').props.options.map((option: Data) => option.id), ['conversation', 'global']);
  assert.equal(f.field('审批模式').props.value, 'controlled');
  assert.ok(f.field('审批模式').props.options.every((option: Data) => typeof option.description === 'string' && option.description.length > 0));
  assert.deepEqual(f.find(node => node.type === 'Accordion').props.defaultExpandedKeys, []);
  assert.equal(f.calls.length, 1, 'rendering or choosing a scope never grants permission');
});

test('save reports pending before dispatch, rejects duplicate activation and only then reports saved', async () => {
  const native = snapshot(), waiting = deferred();
  const f = fixture(async () => native, () => waiting.promise); await f.flush();
  f.edit('审批模式', 'auto');
  const save = f.button('保存权限'), scope = f.field('应用范围'), mode = f.field('审批模式');
  save.props.onClick();
  assert.equal(f.pending.at(-1), true, 'the sheet must lock before the native promise settles');
  save.props.onClick(); scope.props.onChange('global'); mode.props.onChange('ask'); f.render();
  const writes = f.calls.filter(call => call.action === 'permissions.save');
  assert.equal(writes.length, 1);
  assert.equal(writes[0].pendingAtCall, true);
  assert.deepEqual(writes[0].args, { conversationId: 'conversation-a', global: false, policy: policy('auto'), expectedRevision: 7 });
  assert.equal(f.field('应用范围').props.value, 'conversation');
  assert.equal(f.field('审批模式').props.value, 'auto');
  assert.equal(f.field('审批模式').props.isDisabled, true);
  assert.doesNotMatch(f.text, /已保存/);
  waiting.resolve(snapshot('conversation-a', 8, policy('auto'))); await f.flush();
  assert.equal(f.pending.at(-1), false); assert.match(f.text, /已保存/);
  f.edit('审批模式', 'ask'); assert.doesNotMatch(f.text, /已保存/, 'editing clears the previous save receipt');
  assert.deepEqual(native, snapshot(), 'draft changes never mutate the native snapshot');
});

test('a failed save releases pending and keeps the unsaved mode for retry', async () => {
  const waiting = deferred(), f = fixture(async () => snapshot(), () => waiting.promise); await f.flush();
  f.edit('审批模式', 'ask'); f.click('保存权限');
  waiting.reject(new Error('设置已变化，请重新读取')); await f.flush();
  assert.equal(f.pending.at(-1), false); assert.equal(f.field('审批模式').props.value, 'ask');
  assert.match(f.text, /设置已变化/); assert.doesNotMatch(f.text, /已保存/);
  assert.equal(f.button('保存权限').props.disabled, false);
});

test('global and conversation tool rules retain exact matching and inheritance', async () => {
  const native = snapshot('conversation-a', 11, policy('ask', { browser_open: 'deny' })), written: Data[] = [];
  const f = fixture(async () => native, async args => { written.push(args); return snapshot('conversation-a', 12, args.policy); }); await f.flush();
  f.edit('应用范围', 'global');
  assert.equal(f.field('审批模式').props.value, 'controlled');
  assert.equal(f.field('ssh_read').props.value, 'allow');
  f.edit('应用范围', 'conversation');
  assert.equal(f.field('审批模式').props.value, 'ask'); assert.match(f.text, /ssh_read.*继承全局：允许/);
  f.edit('精确工具名', 'attachments_*'); f.click('添加规则');
  assert.match(f.text, /填写精确工具名/);
  assert.equal(f.nodes.some(node => node.props.label === 'attachments_*'), false);
  f.edit('精确工具名', 'attachments_create_document'); f.click('添加规则');
  assert.equal(f.field('attachments_create_document').props.value, 'ask');
  f.edit('attachments_create_document', 'deny');
  f.click('移除此规则');
  assert.equal(f.nodes.some(node => node.props.label === 'browser_open'), false);
  f.click('保存权限'); await f.flush();
  assert.deepEqual(written, [{ conversationId: 'conversation-a', global: false, policy: policy('ask', { attachments_create_document: 'deny' }), expectedRevision: 11 }]);
  assert.deepEqual(native.conversation.tools, { browser_open: 'deny' });
});

test('inherit saves null with the current revision instead of copying global tool rules', async () => {
  const native = snapshot('conversation-a', 4, policy('auto', { browser_open: 'allow' })), written: Data[] = [];
  const f = fixture(async () => native, async args => { written.push(args); return snapshot('conversation-a', 5); }); await f.flush();
  f.click('继承全局'); await f.flush();
  assert.deepEqual(written, [{ conversationId: 'conversation-a', global: false, policy: null, expectedRevision: 4 }]);
  assert.equal(f.field('审批模式').props.value, 'controlled');
  assert.equal(f.nodes.some(node => node.props.label === 'ssh_read'), false, 'inherited rules remain inherited');
  assert.match(f.text, /已恢复继承全局/); assert.equal(f.pending.at(-1), false);
});

test('without a native conversation only the original global policy can be saved', async () => {
  const written: Data[] = [];
  const f = fixture(async () => snapshot('', 3), async args => { written.push(args); return snapshot('', 4); }); await f.flush();
  assert.equal(f.field('应用范围').props.value, 'global');
  assert.equal(f.field('应用范围').props.options[0].disabled, true);
  f.click('保存权限'); await f.flush();
  assert.deepEqual(written, [{ conversationId: '', global: true, policy: policy('controlled', { ssh_read: 'allow' }), expectedRevision: 3 }]);
  assert.equal(f.nodes.some(node => node.type === 'Button' && node.children.includes('继承全局')), false);
});

test('snapshot failure exposes the native error without editable defaults or a pending save', async () => {
  const f = fixture(async () => { throw new Error('请回到私人对话设置执行权限'); }, async () => { throw new Error('unexpected write'); }); await f.flush();
  assert.match(f.text, /请回到私人对话/);
  assert.equal(f.find(node => node.props.role === 'alert').type, 'p');
  assert.equal(f.nodes.some(node => node.type === 'RadioOptions' || node.type === 'Button'), false);
  assert.equal(f.pending.at(-1), false); assert.equal(f.calls.length, 1);
});

test('retired saves cannot unlock or overwrite the replacement conversation', async () => {
  const old = deferred(), next = deferred(); let reads = 0;
  const f = fixture(async () => ++reads === 1 ? snapshot() : snapshot('conversation-b', 20), args => args.conversationId === 'conversation-a' ? old.promise : next.promise); await f.flush();
  f.click('保存权限'); assert.equal(f.pending.at(-1), true);
  f.changeConversation('conversation-b'); assert.equal(f.pending.at(-1), false); await f.flush();
  f.edit('审批模式', 'ask'); f.click('保存权限'); const pendingCount = f.pending.length;
  old.resolve(snapshot('conversation-a', 8, policy('auto'))); await f.flush();
  assert.equal(f.pending.length, pendingCount, 'the old finally cannot release the new save');
  assert.equal(f.pending.at(-1), true); assert.equal(f.field('审批模式').props.value, 'ask'); assert.doesNotMatch(f.text, /已保存/);
  next.resolve(snapshot('conversation-b', 21, policy('ask'))); await f.flush();
  assert.equal(f.pending.at(-1), false); assert.match(f.text, /已保存/);
});

test('unmount releases pending and ignores a late native save', async () => {
  const waiting = deferred(), f = fixture(async () => snapshot(), () => waiting.promise); await f.flush();
  f.click('保存权限'); f.unmount(); assert.equal(f.pending.at(-1), false);
  const pendingCount = f.pending.length;
  waiting.resolve(snapshot('conversation-a', 8, policy('auto'))); await f.flush();
  assert.equal(f.pending.length, pendingCount); assert.equal(f.writesAfterUnmount, 0);
});
