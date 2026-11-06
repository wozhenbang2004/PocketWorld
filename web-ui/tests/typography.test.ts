import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import * as typography from '../src/typography.ts';

type Data = Record<string, any>;
const source = ts.createSourceFile('TypographySettings.tsx', readFileSync(new URL('../src/TypographySettings.tsx', import.meta.url), 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
const code = ts.transpileModule(source.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(source).replace(/^export /, '')).join('\n'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React },
}).outputText;

// These run the real preference form handlers with hook/control doubles, not an Android/DOM claim.
function fixture(options: Data = {}) {
  const slots: any[] = [], effects: (() => void)[] = [], calls: Data[] = [], loads: string[] = [];
  let cursor = 0, changed = false, tree: any, alive = true, lateWrites = 0, loadFails = options.loadFails === true;
  const useState = (initial: any) => {
    const index = cursor++; slots[index] ??= { value: typeof initial === 'function' ? initial() : initial };
    return [slots[index].value, (next: any) => {
      if (!alive) { lateWrites++; return; }
      const value = typeof next === 'function' ? next(slots[index].value) : next;
      if (!Object.is(value, slots[index].value)) { slots[index].value = value; changed = true; }
    }];
  };
  const useRef = (initial: any) => { const index = cursor++; return slots[index] ??= { current: initial }; };
  const useEffect = (effect: () => void | (() => void), deps: any[]) => {
    const index = cursor++, prior = slots[index];
    if (!prior || deps.some((value, j) => !Object.is(value, prior.deps[j]))) {
      const slot = slots[index] = { deps, cleanup: prior?.cleanup };
      effects.push(() => { slot.cleanup?.(); slot.cleanup = effect(); });
    }
  };
  const React = { createElement: (type: any, props: Data | null, ...children: any[]) => ({ type, props: props || {}, children: children.flat(Infinity).filter(value => value != null && value !== false) }) };
  const dependencies = { React, useState, useRef, useEffect, ...typography, SelectField: 'SelectField', Button: 'Button',
    document: { fonts: { load: (face: string) => { loads.push(face); return loadFails ? Promise.reject(new Error('missing font')) : Promise.resolve([{ status: 'loaded' }]); } } },
  };
  const Component = new Function(...Object.keys(dependencies), `${code};return TypographySettings;`)(...Object.values(dependencies));
  const props: Data = {
    state: { appearance: { typography: { ...typography.DEFAULT_TYPOGRAPHY } } }, supported: () => true,
    ...options,
    run: async (action: string, args: Data) => { calls.push({ action, args }); return options.run ? options.run(action, args) : { saved: true, typography: args }; },
  };
  const walk = (node: any): any[] => node && typeof node === 'object' ? [node, ...node.children.flatMap(walk)] : [];
  const text = (node: any): string => node && typeof node === 'object' ? node.props.hidden ? '' : node.children.map(text).join('') : String(node ?? '');
  const render = () => {
    if (!alive) return;
    let passes = 0;
    do { assert.ok(++passes < 20); changed = false; cursor = 0; tree = Component(props); effects.splice(0).forEach(effect => effect()); } while (changed);
  };
  const find = (predicate: (node: any) => boolean) => { const found = walk(tree).find(predicate); assert.ok(found, 'expected production control'); return found; };
  const button = (label: string) => find(node => node.type === 'Button' && text(node) === label);
  render();
  return {
    calls, loads, props, render, button, get text() { return text(tree); }, get lateWrites() { return lateWrites; },
    field(label: string) { return find(node => node.type === 'SelectField' && node.props.label === label); },
    select(label: string, id: string) { const node = this.field(label); assert.equal(node.props.isDisabled, false); node.props.onChange(id); render(); },
    click(label: string) { const node = button(label); assert.ok(!node.props.disabled); node.props.onClick(); render(); },
    snapshot(typography: Data, appearance: Data = {}) { props.state = { appearance: { typography, ...appearance } }; render(); },
    setLoadFailure(value: boolean) { loadFails = value; },
    unmount() { alive = false; slots.forEach(slot => slot.cleanup?.()); },
    async flush() { for (let i = 0; i < 12; i++) { await Promise.resolve(); if (changed) render(); } },
  };
}

test('stored typography is allowlisted per role and can never inject a CSS family or URL', () => {
  const received = { chinese: '"; color: red; font-family:"', latin: 'https://bad.invalid/font.ttf', code: 'inter' };
  assert.deepEqual(typography.normalizeTypography(received), typography.DEFAULT_TYPOGRAPHY);
  assert.deepEqual(typography.normalizeTypography({ chinese: 'notoserifsc', latin: 'geist', code: 'ibmplexmono' }), { chinese: 'notoserifsc', latin: 'geist', code: 'ibmplexmono' });
  const stacks = typography.typographyStacks(received);
  assert.equal(stacks.ui, '"Inter", "Noto Sans SC", system-ui, sans-serif');
  assert.equal(stacks.code, '"JetBrains Mono", "Noto Sans SC", ui-monospace, monospace');
  assert.equal(typography.typographySaved({ accepted: true }, { ...typography.DEFAULT_TYPOGRAPHY }), false);
  assert.equal(typography.typographySaved({ saved: true, typography: { ...typography.DEFAULT_TYPOGRAPHY, injected: true } }, { ...typography.DEFAULT_TYPOGRAPHY }), false);
});

test('selecting fonts previews only the selected families and does not persist until Save', async () => {
  const f = fixture(); await f.flush();
  assert.equal(f.loads.length, 3, '18 option labels do not request 18 fonts');
  assert.equal(f.field('中文').props.options.length, 6);
  assert.equal(f.field('英文与数字').props.options.length, 12);
  assert.equal(f.field('代码与终端').props.options.length, 2);
  f.select('中文', 'lxgwwenkai'); f.select('英文与数字', 'geist'); await f.flush();
  assert.equal(f.calls.length, 0);
  f.click('保存字体'); await f.flush();
  assert.deepEqual(f.calls, [{ action: 'settings.typography', args: { chinese: 'lxgwwenkai', latin: 'geist', code: 'jetbrainsmono' } }]);
  assert.match(f.text, /字体已保存，正在应用/);
  assert.deepEqual(f.props.state.appearance.typography, typography.DEFAULT_TYPOGRAPHY, 'a confirmed receipt does not locally fabricate a new native snapshot');
  f.snapshot({ chinese: 'lxgwwenkai', latin: 'geist', code: 'jetbrainsmono' });
  assert.match(f.text, /字体已保存并应用/);
  assert.equal(f.button('保存字体').props.disabled, true);
});

test('unconfirmed writes retain the selection for retry and never present acceptance as saved', async () => {
  let first = true;
  const f = fixture({ run: (_action: string, args: Data) => first ? (first = false, { accepted: true }) : { saved: true, typography: args } });
  await f.flush(); f.select('代码与终端', 'ibmplexmono'); await f.flush(); f.click('保存字体'); await f.flush();
  assert.match(f.text, /保存尚未确认/); assert.doesNotMatch(f.text, /字体已保存/);
  assert.equal(f.field('代码与终端').props.value, 'ibmplexmono');
  f.click('重试保存'); await f.flush();
  assert.equal(f.calls.length, 2); assert.match(f.text, /字体已保存/);
});

test('a failed disk write remains retryable after native memory already matches the selection', async () => {
  let reject!: (failure: Error) => void;
  let first = true;
  const waiting = new Promise((_resolve, fail) => { reject = fail; });
  const f = fixture({ run: (_action: string, args: Data) => first ? (first = false, waiting) : { saved: true, typography: args } });
  await f.flush(); f.select('英文与数字', 'geist'); await f.flush(); f.click('保存字体');
  const preference = { chinese: 'notosanssc', latin: 'geist', code: 'jetbrainsmono' };
  f.snapshot(preference, { error: '字体偏好写入失败，请重试。' });
  reject(new Error('字体偏好写入失败，请重试。')); await f.flush();
  assert.equal(f.field('英文与数字').props.value, 'geist');
  assert.match(f.text, /写入失败/);
  f.click('重试保存'); await f.flush();
  assert.equal(f.calls.length, 2);
  assert.deepEqual(f.calls[1].args, preference);
  f.snapshot(preference); await f.flush();
  assert.match(f.text, /字体已保存并应用/);
  assert.equal(f.button('保存字体').props.disabled, true);
});

test('native appearance errors are visible and allow retry even in a newly opened clean editor', async () => {
  const f = fixture({ state: { appearance: { typography: typography.DEFAULT_TYPOGRAPHY, error: '磁盘写入失败' } } });
  await f.flush(); assert.match(f.text, /磁盘写入失败/);
  f.click('重试保存'); await f.flush();
  assert.equal(f.calls.length, 1);
  assert.deepEqual(f.calls[0].args, typography.DEFAULT_TYPOGRAPHY);
});

test('the native preference updates existing product font aliases as well as new tokens', () => {
  const hookSource = ts.createSourceFile('usePocketTypography.ts', readFileSync(new URL('../src/usePocketTypography.ts', import.meta.url), 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
  const hookCode = ts.transpileModule(hookSource.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(hookSource).replace(/^export /, '')).join('\n'), { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  const properties = new Map<string, string>(), dataset: Data = {};
  const dependencies = { ...typography, useLayoutEffect: (effect: () => void) => effect(), document: { documentElement: { style: { setProperty: (name: string, value: string) => properties.set(name, value) }, dataset } } };
  const hook = new Function(...Object.keys(dependencies), `${hookCode};return usePocketTypography;`)(...Object.values(dependencies));
  hook({ typography: { chinese: 'lxgwwenkai', latin: 'geist', code: 'ibmplexmono' } });
  assert.equal(properties.get('--pw-font'), '"Geist", "LXGW WenKai", system-ui, sans-serif');
  assert.equal(properties.get('--ovio-font'), properties.get('--pw-font-ui'));
  assert.equal(properties.get('--font-code'), '"IBM Plex Mono", "LXGW WenKai", ui-monospace, monospace');
  assert.deepEqual(dataset, { fontChinese: 'lxgwwenkai', fontLatin: 'geist', fontCode: 'ibmplexmono' });
});

test('fresh native snapshots update clean editors while preserving an unsaved local choice', async () => {
  const f = fixture(); await f.flush();
  f.snapshot({ chinese: 'notoserifsc', latin: 'dmsans', code: 'jetbrainsmono' }); await f.flush();
  assert.equal(f.field('中文').props.value, 'notoserifsc');
  f.select('中文', 'lxgwwenkai'); await f.flush();
  f.snapshot({ chinese: 'mashanzheng', latin: 'manrope', code: 'ibmplexmono' }); await f.flush();
  assert.equal(f.field('中文').props.value, 'lxgwwenkai');
  f.click('恢复默认组合'); await f.flush();
  assert.equal(f.field('中文').props.value, 'notosanssc');
  assert.equal(f.calls.length, 0, 'restoring the preview still requires an explicit Save');
});

test('failed font loads offer retry without saving a fallback preview', async () => {
  const f = fixture({ loadFails: true }); await f.flush();
  f.select('中文', 'notoserifsc'); await f.flush();
  assert.match(f.text, /预览暂未载入/); assert.equal(f.button('保存字体').props.disabled, true);
  f.setLoadFailure(false); f.click('重试预览'); await f.flush();
  assert.equal(f.button('保存字体').props.disabled, false); assert.equal(f.calls.length, 0);
});

test('a save is single-flight and a late receipt cannot write to a closed settings view', async () => {
  let resolve!: (value: any) => void;
  const waiting = new Promise(done => { resolve = done; });
  const f = fixture({ run: () => waiting }); await f.flush();
  f.select('英文与数字', 'outfit'); await f.flush();
  const save = f.button('保存字体').props.onClick;
  save(); save(); f.render(); assert.equal(f.calls.length, 1);
  f.unmount(); resolve({ saved: true, typography: { chinese: 'notosanssc', latin: 'outfit', code: 'jetbrainsmono' } }); await f.flush();
  assert.equal(f.lateWrites, 0);
});
