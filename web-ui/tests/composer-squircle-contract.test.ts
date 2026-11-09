import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import { composerHasPayload } from '../src/composer-send-policy.ts';

const source = readFileSync(new URL('../src/library/composer.tsx', import.meta.url), 'utf8');
const styles = readFileSync(new URL('../src/composer-squircle.css', import.meta.url), 'utf8');
const ast = ts.createSourceFile('composer.tsx', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
const body = ast.statements.filter(node => ts.isFunctionDeclaration(node) && ['sizeComposerEditor', 'ChatComposer'].includes(node.name?.text || ''))
  .map(node => node.getText(ast).replace(/^export /, '')).join('\n');
const code = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
type Node = { type: string; props: Record<string, any>; children: any[] };
const React = { createElement: (type: string, props: Record<string, any>, ...children: any[]): Node => ({ type, props: props || {}, children: children.flat(Infinity).filter(value => value !== false && value != null) }) };
// Production component/handler contract only. Layout and touch geometry belong to
// composer-icon-presentation-ui.mjs; these doubles are not a browser acceptance.
function render(props: Record<string, any> = {}, coarse = false) {
  let sent = 0, stopped = 0;
  const dependencies = { React, useLayoutEffect: () => {}, useRef: () => ({ current: null }),
    FrameA: 'FrameA', ComposerContextMeter: 'ComposerContextMeter', SmoothComposerSurface: 'SmoothComposerSurface',
    Icon: 'Icon', ToolButton: 'ToolButton', PressButton: 'PressButton', ProgressCircle: 'ProgressCircle',
    composerHasPayload, matchMedia: () => ({ matches: coarse }) };
  const component = new Function(...Object.keys(dependencies), code + '\nreturn ChatComposer;')(...Object.values(dependencies));
  const tree: Node = component({ value: '', onChange: () => {}, onSend: () => sent++, onStop: () => stopped++,
    onVoice: () => {}, toolbar: React.createElement('leftControls', {}), controls: React.createElement('modelControls', {}), ...props });
  const walk = (node: any): Node[] => !node || typeof node !== 'object' ? [] : [node, ...node.children.flatMap(walk)];
  const nodes = walk(tree);
  return { tree, nodes, sent: () => sent, stopped: () => stopped,
    find: (type: string) => nodes.find(node => node.type === type),
    region: (name: string) => nodes.find(node => node.props.className?.split(' ').includes(name)) };
}

test('editing is a separate full-width row before the control toolbar', () => {
  const view = render({ value: '保留这份草稿' });
  const row = view.region('pw-composer-row')!, toolbar = view.region('pw-composer-toolbar')!;
  assert.ok(toolbar, 'the editor must not share its row with fixed-width controls');
  assert.deepEqual(row.children.map(child => child.type), ['textarea']);
  const card = view.region('pw-composer-card')!;
  assert.ok(card.children.indexOf(row) < card.children.indexOf(toolbar));
  assert.equal(view.find('textarea')?.props.value, '保留这份草稿');
  assert.deepEqual(toolbar.children.map(child => child.props.className), ['pw-composer-leading', 'pw-composer-trailing']);
  assert.deepEqual(view.region('pw-composer-trailing')!.children.map(child => child.props.className), ['pw-composer-controls', 'afa-toolbar']);
});

test('text and attachment-only payloads retain the same composer send gating', () => {
  for (const props of [{ value: '' }, { value: ' \n ' }]) assert.equal(render(props).find('PressButton'), undefined);
  for (const props of [{ value: '你好' }, { attachments: [{ id: 'original-file', name: '文件.pdf' }] }]) {
    const view = render(props), send = view.find('PressButton')!;
    assert.equal(send.props.disabled, false); send.props.onClick(); assert.equal(view.sent(), 1);
    for (const lock of ['disabled', 'submitting', 'sendDisabled']) assert.equal(render({ ...props, [lock]: true }).find('PressButton')!.props.disabled, true);
  }
});

test('busy requests retain stop and a separate draft supplement action', () => {
  const view = render({ busy: true, value: '补充原任务' });
  const stop = view.nodes.find(node => node.props['aria-label'] === '停止当前回复')!;
  assert.ok(stop); stop.props.onClick(); assert.equal(view.stopped(), 1);
  assert.equal(view.find('PressButton')!.props['aria-label'], '补充给当前工作');
  assert.equal(view.nodes.some(node => node.type === 'ToolButton' && node.props.icon === 'mic'), false);
});

test('voice capture keeps its status on the editing row and preserves discard', () => {
  const discard = () => {};
  const view = render({ listening: true, value: '原草稿', voiceStatus: '仍在听写', onDiscardVoice: discard });
  assert.equal(view.find('textarea'), undefined);
  assert.equal(view.find('PressButton'), undefined);
  assert.deepEqual(view.region('pw-composer-row')!.children.map(child => child.props.className), ['afa-recording']);
  assert.equal(view.nodes.find(node => node.type === 'ToolButton' && node.props.label === '取消本次听写')!.props.onClick, discard);
});

test('context meter retains the exact draft key, text and attachment count', () => {
  const view = render({ composerKey: 'conversation:original', value: '未提交文字', contextStatus: 'context', attachments: [{ id: 'file', name: '原附件' }] });
  assert.deepEqual(view.find('ComposerContextMeter')!.props, { composerKey: 'conversation:original', draftText: '未提交文字', attachmentCount: 1 });
  assert.equal(view.nodes.filter(node => node.type === 'SmoothComposerSurface').length, 2);
});

test('IME and ordinary touch Enter never send a draft', () => {
  const key = (nativeEvent = {}, extra = {}) => ({ key: 'Enter', shiftKey: false, nativeEvent, preventDefault() {}, ...extra });
  const desktop = render({ value: '文字' });
  desktop.find('textarea')!.props.onKeyDown(key({ isComposing: true }));
  desktop.find('textarea')!.props.onKeyDown(key({ keyCode: 229 }));
  desktop.find('textarea')!.props.onKeyDown(key({}, { shiftKey: true }));
  assert.equal(desktop.sent(), 0);
  desktop.find('textarea')!.props.onKeyDown(key()); assert.equal(desktop.sent(), 1);
  const touch = render({ value: '文字' }, true);
  touch.find('textarea')!.props.onKeyDown(key()); assert.equal(touch.sent(), 0);
  touch.find('textarea')!.props.onKeyDown(key({}, { ctrlKey: true })); assert.equal(touch.sent(), 1);
});

test('one geometry owner keeps editor and toolbar dimensions independent at every width', () => {
  assert.doesNotMatch(source, /pw-compact-composer/);
  assert.match(styles, /\.pw-composer-row > textarea\s*\{[^}]*width: 100%/);
  assert.match(styles, /\.pw-composer-toolbar\s*\{[^}]*display: flex/);
  assert.doesNotMatch(styles, /@media \(max-width: 360px\)[^{]*\{\s*\.pw-composer-host[^}]*\.pw-composer-row/);
  assert.match(styles, /width: 76%; max-width: 540px/);
  assert.match(styles, /border-radius: 30px/);
  assert.match(styles, /@media \(prefers-reduced-motion: reduce\)/);
});
