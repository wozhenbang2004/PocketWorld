import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';

// Production component handlers with hook/host doubles. This does not claim Android or visual acceptance.
function fixture() {
  const source = ts.createSourceFile('ModelDiagnosticsWorkspace.tsx', readFileSync(new URL('../src/ModelDiagnosticsWorkspace.tsx', import.meta.url), 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const body = source.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(source).replace(/^export /, '')).join('\n');
  const compiled = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
  const slots: any[] = [], effects: (() => void)[] = [], calls: any[] = [];
  let index = 0, dirty = false, tree: any, window = 0, epoch = 1, finishCopy: (() => void) | undefined;
  let resolveLogs: ((value: any) => void) | undefined, delayLogs = false;
  const props: any = { state: { modelDiagnostics: {}, webWorkspaceForeground: true }, supported: () => true };
  const document = { visibilityState: 'visible', addEventListener() {}, removeEventListener() {} };
  const logs = { format: 'pocketworld.runtime-log.v1', retainedEvents: 1, maxEvents: 256, events: [{ stage: 'model_request', code: 'PROVIDER_UNAVAILABLE' }] };
  const useState = (initial: any) => { const i = index++; slots[i] ??= { value: initial }; return [slots[i].value, (next: any) => { const value = typeof next === 'function' ? next(slots[i].value) : next; if (!Object.is(value, slots[i].value)) { slots[i].value = value; dirty = true; } }]; };
  const useRef = (initial: any) => { const i = index++; return slots[i] ??= { current: initial }; };
  const useEffect = (effect: () => void | (() => void), deps: any[]) => { const i = index++, prior = slots[i]; if (!prior || deps.some((value, j) => !Object.is(value, prior.deps[j]))) { const slot = slots[i] = { deps, cleanup: prior?.cleanup }; effects.push(() => { slot.cleanup?.(); slot.cleanup = effect(); }); } };
  const bridge = { getDocumentEpoch: () => epoch, command: async (action: string, args: any) => {
    calls.push({ action, args });
    if (action === 'modelDiagnostics.open') return props.state.modelDiagnostics = { ready: true, windowId: 'window-' + ++window, probeSelectionRevision: 1, canProbe: true };
    if (action === 'modelDiagnostics.close') { props.state.modelDiagnostics = {}; return { closed: true }; }
    if (action === 'modelDiagnostics.logs') return delayLogs ? new Promise(resolve => { resolveLogs = resolve; }) : logs;
    return {};
  } };
  const controls = Object.fromEntries(['Accordion', 'AccordionItem', 'Button', 'CheckboxField', 'SettingsDialog'].map(name => [name, name]));
  const React = { Fragment: 'Fragment', createElement: (type: any, props: any, ...children: any[]) => ({ type, props: props || {}, children: children.flat(Infinity).filter(value => value !== false && value != null) }) };
  const dependencies = { React, useState, useRef, useEffect, bridge, document, navigator: { clipboard: { writeText: async (text: string) => { calls.push({ action: 'clipboard', text }); return new Promise<void>(resolve => { finishCopy = resolve; }); } } }, ...controls };
  const Component = new Function(...Object.keys(dependencies), compiled + '\nreturn ModelDiagnosticsWorkspace;')(...Object.values(dependencies));
  const nodes = (node: any): any[] => node && typeof node === 'object' ? [node, ...(node.children || []).flatMap(nodes)] : [];
  const text = (node: any): string => node && typeof node === 'object' ? (node.children || []).map(text).join('') : String(node ?? '');
  const render = () => { do { dirty = false; index = 0; tree = Component(props); effects.splice(0).forEach(effect => effect()); } while (dirty); };
  const flush = async () => { for (let i = 0; i < 8; i++) { await Promise.resolve(); render(); } };
  render();
  return { calls, flush, get text() { return text(tree); },
    async click(label: string) { const button = nodes(tree).find(node => node.type === 'Button' && text(node) === label); assert.ok(button, label); assert.ok(!button.props.disabled, label + ' must be available'); button.props.onClick(); await flush(); },
    async background() { props.state.webWorkspaceForeground = false; render(); await flush(); },
    async replaceDocument() { epoch++; render(); await flush(); },
    delayLogs() { delayLogs = true; }, async releaseLogs() { resolveLogs?.(logs); await flush(); },
    async releaseCopy() { assert.ok(finishCopy); finishCopy(); await flush(); },
  };
}

test('opening and reading logs never starts a model probe, and uses the original owner window', async () => {
  const f = fixture(); await f.click('诊断与运行日志'); await f.click('读取最近日志');
  assert.deepEqual(f.calls.map(call => call.action), ['modelDiagnostics.open', 'modelDiagnostics.logs']);
  assert.deepEqual(f.calls[1].args, { windowId: 'window-1' }); assert.match(f.text, /PROVIDER_UNAVAILABLE/);
  await f.click('复制日志'); assert.match(f.calls.at(-1).text, /PROVIDER_UNAVAILABLE/); await f.releaseCopy();
  assert.match(f.text, /已复制本次读取的运行日志/);
});

test('a late log result cannot reappear after backgrounding or document replacement', async () => {
  for (const leave of ['background', 'replaceDocument'] as const) {
    const f = fixture(); await f.click('诊断与运行日志'); f.delayLogs(); await f.click('读取最近日志');
    await f[leave](); await f.releaseLogs(); assert.doesNotMatch(f.text, /PROVIDER_UNAVAILABLE/);
  }
});

test('a clipboard receipt from a closed window cannot annotate the reopened window', async () => {
  const f = fixture(); await f.click('诊断与运行日志'); await f.click('读取最近日志'); await f.click('复制日志');
  await f.click('诊断与运行日志'); await f.click('诊断与运行日志'); await f.releaseCopy();
  assert.doesNotMatch(f.text, /已复制本次读取的运行日志/); assert.doesNotMatch(f.text, /PROVIDER_UNAVAILABLE/);
});
