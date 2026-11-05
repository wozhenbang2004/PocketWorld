import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import { registerOverlayBack, consumeOverlayBack } from '../src/overlay-back.ts';

// Execute the production Select with hook doubles. Browser focus and actual
// React Aria dismissal are covered separately by select-back-ui.mjs.
function selectFixture() {
  const source = ts.createSourceFile('select.tsx', readFileSync(new URL('../src/forms-library/boardui/components/base/select/select.tsx', import.meta.url), 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const body = source.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(source).replace(/^export /, '')).join('\n');
  const compiled = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
  const slots: any[] = []; let index = 0, tree: any;
  const useState = (initial: any) => { const i = index++; slots[i] ??= { value: initial }; return [slots[i].value, (next: any) => { slots[i].value = next; }]; };
  const useLayoutEffect = (callback: () => (() => void) | undefined, deps: any[]) => {
    const i = index++, previous = slots[i];
    if (!previous || deps.some((value, j) => value !== previous.deps[j])) {
      previous?.cleanup?.(); slots[i] = { deps, cleanup: callback() };
    }
  };
  const React = { createElement: (type: any, props: any, ...children: any[]) => ({ type, props, children }) };
  const bindings = { React, useState, useLayoutEffect, createContext: () => ({}), useContext: () => 'md', registerOverlayBack, cx: () => '', AriaSelect: 'Select' };
  const Select = new Function(...Object.keys(bindings), compiled + '\nreturn Select;')(...Object.values(bindings));
  const render = () => { index = 0; tree = Select({ children: [] }); };
  render();
  return { render, open: () => { tree.props.onOpenChange(true); render(); }, close: () => { tree.props.onOpenChange(false); render(); }, get isOpen() { return tree.props.isOpen; }, unmount: () => slots.forEach(slot => slot?.cleanup?.()) };
}

test('native Back closes a real Select before the page handler, and cleans up on close/unmount', async () => {
  (globalThis as any).document = { documentElement: { dataset: {} } };
  (globalThis as any).window = {};
  const { bridge } = await import('../src/bridge.ts');
  let pageBacks = 0;
  const removePage = bridge.setBackHandler(() => { pageBacks++; return true; });
  const fixture = selectFixture();
  try {
    fixture.open(); assert.equal(fixture.isOpen, true);
    assert.equal((globalThis as any).window.PocketWeb.handleBack(), true);
    fixture.render();
    assert.equal(fixture.isOpen, false, 'scope popup must close before page navigation');
    assert.equal(pageBacks, 0);
    assert.equal((globalThis as any).window.PocketWeb.handleBack(), true);
    assert.equal(pageBacks, 1);
    fixture.open(); fixture.close();
    assert.equal(consumeOverlayBack(), false, 'normal React Aria dismissal removes the handler');
    fixture.open(); fixture.unmount();
    assert.equal(consumeOverlayBack(), false, 'route unmount removes the handler');
  } finally { fixture.unmount(); removePage(); }
});

test('only the most recently opened overlay consumes Back; cleanup does not erase another entry', () => {
  const calls: string[] = [];
  const removeFirst = registerOverlayBack(() => calls.push('first'));
  const removeSecond = registerOverlayBack(() => calls.push('second'));
  try {
    assert.equal(consumeOverlayBack(), true); assert.deepEqual(calls, ['second']);
    removeFirst(); removeFirst();
    assert.equal(consumeOverlayBack(), true); assert.deepEqual(calls, ['second', 'second']);
    removeSecond(); assert.equal(consumeOverlayBack(), false);
  } finally { removeFirst(); removeSecond(); }
});

test('nested Selects dismiss one layer per Back, then return to page navigation', async () => {
  const { bridge } = await import('../src/bridge.ts');
  let pageBacks = 0;
  const removePage = bridge.setBackHandler(() => { pageBacks++; return true; });
  const lower = selectFixture(), upper = selectFixture();
  try {
    lower.open(); upper.open();
    assert.equal((globalThis as any).window.PocketWeb.handleBack(), true);
    upper.render(); lower.render();
    assert.equal(upper.isOpen, false);
    assert.equal(lower.isOpen, true);
    assert.equal(pageBacks, 0);
    assert.equal((globalThis as any).window.PocketWeb.handleBack(), true);
    lower.render();
    assert.equal(lower.isOpen, false);
    assert.equal(pageBacks, 0);
    assert.equal((globalThis as any).window.PocketWeb.handleBack(), true);
    assert.equal(pageBacks, 1);
    assert.equal(consumeOverlayBack(), false);
  } finally { upper.unmount(); lower.unmount(); removePage(); }
});
