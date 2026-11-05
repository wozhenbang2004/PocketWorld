import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';

// Production component/handler checks with explicit hook, DOM-ref and UI doubles.
// These do not establish actual browser layout, keyboard or visual acceptance.
type Data = Record<string, any>;
type Call = { action: string; args: Data; resolve: (value: Data) => void };
function fixture(name: string, initial: Data = {}) {
  const source = ts.createSourceFile(name, readFileSync(new URL('../src/' + name + '.tsx', import.meta.url), 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const body = source.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(source).replace(/^export (default )?/, '')).join('\n');
  const compiled = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
  const slots: any[] = [], effects: (() => void)[] = [], layouts: (() => void)[] = [], calls: Call[] = [], focus: string[] = [];
  let index = 0, tree: any, dirty = false;
  const useState = (initial: any) => { const i = index++; slots[i] ??= { value: typeof initial === 'function' ? initial() : initial }; return [slots[i].value, (next: any) => { const value = typeof next === 'function' ? next(slots[i].value) : next; if (!Object.is(value, slots[i].value)) { slots[i].value = value; dirty = true; } }]; };
  const useRef = (initial: any) => { const i = index++; return slots[i] ??= { current: initial }; };
  const sameDependencies = (previous?: readonly any[], next?: readonly any[]) => !!previous && !!next && previous.length === next.length && next.every((value, i) => Object.is(value, previous[i]));
  const useCallback = (callback: (...args: any[]) => any, deps?: any[]) => { const i = index++; if (!sameDependencies(slots[i]?.deps, deps)) slots[i] = { deps, callback }; return slots[i].callback; };
  const effect = (queue: (() => void)[]) => (callback: () => void | (() => void), deps?: any[]) => { const i = index++, prior = slots[i]; if (!sameDependencies(prior?.deps, deps)) { const slot = slots[i] = { deps, cleanup: prior?.cleanup }; queue.push(() => { slot.cleanup?.(); slot.cleanup = callback(); }); } };
  const React = { Fragment: 'Fragment', createElement: (type: any, props: any, ...children: any[]) => ({ type, props: props || {}, children: children.flat(Infinity).filter(child => child !== null && child !== undefined && child !== false) }) };
  const controls = Object.fromEntries(['Tabs', 'TabList', 'Tab', 'TabPanel', 'Accordion', 'AccordionItem', 'Button', 'CheckboxField', 'HostFileUpload', 'IconButton', 'SearchField', 'SelectField', 'SettingsDialog', 'TextAreaField', 'TextField', 'MemoryAssertionFields', 'MemoryHistoryWorkspace', 'MarkdownBody', 'RiArrowLeftSLine', 'RiArrowRightSLine', 'RiBookOpenLine', 'RiRefreshLine'].map(key => [key, key]));
  const dependencies = { React, useState, useRef, useCallback, useEffect: effect(effects), useLayoutEffect: effect(layouts), ...controls };
  const Component = new Function(...Object.keys(dependencies), compiled + '\nreturn ' + name + ';')(...Object.values(dependencies));
  const props = { state: {}, domain: 'memory', registerTransientBack: () => {}, ...initial, run: (action: string, args: Data = {}) => new Promise(resolve => { calls.push({ action, args, resolve }); if (action.endsWith('.close')) resolve({}); }) };
  const nodes = (node: any): any[] => node && typeof node === 'object' ? [node, ...(node.children || []).flatMap(nodes)] : [];
  const visibleNodes = (node: any): any[] => node && typeof node === 'object' && !node.props.hidden && node.type !== 'AccordionItem' && !(node.type === 'SettingsDialog' && !node.props.isOpen) ? [node, ...(node.children || []).flatMap(visibleNodes)] : [];
  const content = (node: any, visible = false): string => node && typeof node === 'object' ? visible && (node.props.hidden || node.type === 'AccordionItem' || node.type === 'SettingsDialog' && !node.props.isOpen) ? '' : (node.children || []).map((child: any) => content(child, visible)).join('') : String(node ?? '');
  const scroller = { scrollTop: 0 };
  const find = (predicate: (node: any) => boolean) => { const node = nodes(tree).find(predicate); assert.ok(node, 'expected production element'); return node; };
  const render = () => { do { dirty = false; index = 0; tree = Component(props); for (const node of nodes(tree)) if (node.props.ref) node.props.ref.current = { closest: () => scroller, focus: () => focus.push('detail'), scrollIntoView: () => {}, querySelectorAll: () => nodes(tree).filter(row => row.props['data-skill-id'] || row.props['data-memory-id']).map(row => ({ dataset: { skillId: row.props['data-skill-id'], memoryId: row.props['data-memory-id'] }, focus: () => focus.push(row.props['data-skill-id'] || row.props['data-memory-id']) })) }; layouts.splice(0).forEach(run => run()); effects.splice(0).forEach(run => run()); } while (dirty); };
  render();
  return { calls, focus, scroller, find, render, get all() { return nodes(tree); }, get visibleAll() { return visibleNodes(tree); }, get text() { return content(tree); }, get visibleText() { return content(tree, true); },
    click(label: string) { const node = find(node => ['Button', 'IconButton'].includes(node.type) && (node.props['aria-label'] === label || content(node) === label)); assert.ok(!node.props.disabled); node.props.onClick(); render(); },
    async settle(call: Call, value: Data) { call.resolve(value); for (let i = 0; i < 8; i++) await Promise.resolve(); render(); } };
}
const skill = { id: 'skill-a', name: '原技能', description: '原说明', revision: 3, enabled: false, resources: ['SKILL.md'], sourcePolicy: { layer: 'USER', invocation: 'MODEL_AND_OWNER' } };
const record = { id: 'memory-a', text: '完整原记忆\n第二行内容', layer: 'fact', revision: 4, active: true, ownerId: 'owner', spaceId: 'private', consolidationBlocked: false, sourceRefs: [{ sourceId: 'source-a', revision: 2 }] };

test('skill row directly reads the original object; returning restores the collection position and focus', async () => {
  const f = fixture('SkillsWorkspace'); await f.settle(f.calls[0], { skills: [skill] });
  assert.equal(f.all.some(node => node.type === 'CheckboxField'), false);
  f.scroller.scrollTop = 260; f.click('打开技能：原技能');
  const read = f.calls.findLast(call => call.action === 'skills.get')!; assert.deepEqual(read.args, { id: 'skill-a' });
  await f.settle(read, { skill, skillHandle: 'original-skill-handle' });
  assert.equal(f.find(node => node.props['aria-label'] === '技能管理').props.hidden, true); assert.equal(f.scroller.scrollTop, 0);
  f.click('返回技能列表'); assert.equal(f.find(node => node.props['aria-label'] === '技能管理').props.hidden, false);
  assert.equal(f.scroller.scrollTop, 260); assert.equal(f.focus.at(-1), 'skill-a');
  assert.ok(f.calls.some(call => call.action === 'skills.close' && call.args.handle === 'original-skill-handle'));
});
test('Markdown reading preserves the complete original resource and does not submit an approval', async () => {
  const f = fixture('SkillsWorkspace'); await f.settle(f.calls[0], { skills: [skill] }); f.click('打开技能：原技能');
  await f.settle(f.calls.findLast(call => call.action === 'skills.get')!, { skill, skillHandle: 'original-skill-handle' });
  f.click('读取原资源正文'); const read = f.calls.findLast(call => call.action === 'skills.read')!;
  assert.deepEqual(read.args, { skillHandle: 'original-skill-handle', path: 'SKILL.md', offset: 0 });
  const original = '# 原标题\n\n原正文 **保持完整**。\n'; await f.settle(read, { content: original, path: 'SKILL.md', hasMore: true, nextOffset: original.length });
  assert.equal(f.find(node => node.type === 'MarkdownBody').props.text, original);
  assert.ok(f.all.some(node => node.type === 'pre' && node.children[0] === original));
  assert.ok(f.visibleText.includes('正文尚未读完')); assert.equal(f.calls.some(call => call.action.includes('confirm')), false);
});
test('batch-source selection is explicit and cancelling it releases the read handles', async () => {
  const f = fixture('SkillsWorkspace'); await f.settle(f.calls[0], { skills: [{ ...skill, gitSource: { source: {} } }] });
  f.click('批量检查来源'); const checkbox = f.find(node => node.type === 'CheckboxField'); checkbox.props.onChange(true); f.render();
  await f.settle(f.calls.findLast(call => call.action === 'skills.get')!, { skill, skillHandle: 'batch-original-handle' });
  f.click('退出多选'); assert.ok(f.calls.some(call => call.action === 'skills.close' && call.args.handle === 'batch-original-handle'));
  assert.equal(f.all.some(node => node.type === 'CheckboxField'), false); assert.equal(f.calls.some(call => call.action === 'skills.batch.confirm'), false);
});
test('skill mutation restores focus only after the refreshed list is enabled', async () => {
  const f = fixture('SkillsWorkspace'); await f.settle(f.calls[0], { skills: [skill] }); f.click('打开技能：原技能');
  await f.settle(f.calls.findLast(call => call.action === 'skills.get')!, { skill, skillHandle: 'original-skill-handle' });
  f.click('核对并启用当前内容'); await f.settle(f.calls.findLast(call => call.action === 'skills.enabled.review')!, { reviewHandle: 'review-a', reviewKind: 'enabled', before: skill, enabled: true });
  f.find(node => node.type === 'CheckboxField').props.onChange(true); f.render(); f.click('确认这次原操作');
  await f.settle(f.calls.findLast(call => call.action === 'skills.confirm')!, { skill: { ...skill, enabled: true } });
  assert.equal(f.focus.at(-1), 'detail', 'disabled pending rows must not receive focus');
  await f.settle(f.calls.findLast(call => call.action === 'skills.snapshot')!, { skills: [{ ...skill, enabled: true }] });
  assert.equal(f.focus.at(-1), 'skill-a');
  const restored = f.focus.length; f.render(); assert.equal(f.focus.length, restored, 'focus restoration is one-time');
});
test('memory row reads exact original identity; source read and return preserve its scope and position', async () => {
  const f = fixture('MemoryWorkspace'); await f.settle(f.calls[0], { records: [record], candidates: [], scopeName: '私人' });
  // The closed review-dialog double retains its children; only the visible collection
  // is expected to have no selection checkbox before explicit multi-select.
  assert.equal(f.visibleAll.some(node => node.type === 'CheckboxField'), false);
  f.scroller.scrollTop = 180; f.click('读取原版本与来源：完整原记忆');
  const read = f.calls.findLast(call => call.action === 'knowledge.get')!; assert.deepEqual(read.args, { id: 'memory-a', candidate: false });
  await f.settle(read, { record, readHandle: 'original-memory-handle', candidateSelected: false });
  assert.equal(f.find(node => node.props['aria-label'] === '记忆管理').props.hidden, true); assert.ok(f.visibleText.includes(record.text));
  f.click('查看来源 1'); const source = f.calls.findLast(call => call.action === 'knowledge.source')!;
  assert.deepEqual(source.args, { readHandle: 'original-memory-handle', index: 0, offset: 0 }); await f.settle(source, { title: '原来源', text: '原来源全文' });
  f.click('返回列表'); assert.equal(f.scroller.scrollTop, 180); assert.equal(f.focus.at(-1), 'memory-a');
});
test('candidate edits remain drafts and adoption still opens the native exact-version review', async () => {
  const candidate = { ...record, status: 'pending' };
  const f = fixture('MemoryWorkspace', { domain: 'memoryReview' }); await f.settle(f.calls[0], { candidates: [candidate], records: [] });
  f.click('读取原版本与来源：完整原记忆'); await f.settle(f.calls.findLast(call => call.action === 'knowledge.get')!, { candidate, candidateSelected: true, readHandle: 'candidate-original-handle' });
  f.find(node => node.type === 'TextAreaField' && node.props.label === '候选正文').props.onChange('尚未保存的编辑'); f.render();
  assert.ok(f.visibleText.includes('未保存的编辑不会随采用提交'));
  f.click('核对并采用候选'); assert.deepEqual(f.calls.findLast(call => call.action === 'knowledge.review')!.args, { readHandle: 'candidate-original-handle', operation: 'accept' });
  assert.equal(f.calls.some(call => call.action === 'knowledge.confirm'), false);
});
