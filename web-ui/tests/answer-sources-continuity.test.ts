import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';

// Execute the production component and identity helper, not a copied reducer.
// Hook lifecycles, element trees, DOM refs and focus are explicit test doubles.
// This is logic coverage, not ReactDOM, browser layout, focus-trap or visual validation.
const source = ts.createSourceFile('AnswerSources.tsx', readFileSync(new URL('../src/AnswerSources.tsx', import.meta.url), 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
const functions = ['sourceKey', 'AnswerSources'].map(name => {
  const declaration = source.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === name);
  assert.ok(declaration, `production function ${name} must exist`);
  return declaration.getText(source).replace('export function', 'function');
});
const compiled = ts.transpileModule(functions.join('\n'), { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }).outputText;
const instantiate = new Function('React', 'useEffect', 'useLayoutEffect', 'useRef', 'useState', 'bridge', 'Drawer', 'Button', 'IconButton', 'ShimmerText', 'RiArrowLeftLine', 'RiArrowRightSLine', 'RiCloseLine', 'RiFileTextLine', 'RiRefreshLine', compiled + '\nreturn { AnswerSources, sourceKey };');

type Data = Record<string, any>;
type Call = { action: string; args: Data; resolve: (value: unknown) => void; reject: (error: Error) => void };
const pin = { kind: 'conversation', sourceId: 'source-a', sourceRevision: 7, messageIndex: 2, messageRevision: 3 };
const entry = (handle: string, changes: Data = {}, title = '原来源') => ({ status: 'available', sourceHandle: handle, title, pin: { ...pin, ...changes } });
const listPage = (entries: Data[], nextCursor = -1, windowId = 'window-a') => ({ status: 'current', windowId, entries, nextCursor });
const detailPage = (offset = 0, nextOffset = -1, windowId = 'window-a') => ({ status: 'current', windowId, detail: { body: `原保留文本 ${offset}`, readRange: { offset, chars: 100, totalChars: 3000 }, nextOffset } });

function fixture() {
  const slots: any[] = [], effects: (() => void)[] = [], layouts: (() => void)[] = [], calls: Call[] = [];
  const focuses: { key: string; handle: string; options: unknown }[] = [], writesAfterUnmount: unknown[] = [];
  let index = 0, dirty = false, mounted = true, revision = 1, tree: any;
  const target = { messageId: 'answer-a' };
  const nodes = (node: any): any[] => node && typeof node === 'object' ? [node, ...(node.children || []).flatMap(nodes)] : [];
  const content = (node: any): string => node && typeof node === 'object' ? (node.children || []).map(content).join('') : String(node ?? '');
  const find = (predicate: (node: any) => boolean) => { const node = nodes(tree).find(predicate); assert.ok(node, 'expected an element in the production tree'); return node; };
  const body = {
    scrollTop: 0,
    dataset: {} as Record<string, string>,
    querySelectorAll(selector: string) {
      assert.equal(selector, '[data-source-key]');
      return nodes(tree).filter(node => node.props['data-source-key']).map(node => ({
        dataset: { sourceKey: node.props['data-source-key'] },
        focus: (options: unknown) => focuses.push({ key: node.props['data-source-key'], handle: node.props.key, options }),
      }));
    },
  };
  const useRef = (initial: any) => { const i = index++; return slots[i] ??= { current: initial }; };
  const useState = (initial: any) => {
    const i = index++; slots[i] ??= { value: typeof initial === 'function' ? initial() : initial };
    return [slots[i].value, (next: any) => {
      // Record explicitly: an assertion thrown inside .then could be swallowed by .catch.
      if (!mounted) { writesAfterUnmount.push(next); return; }
      const value = typeof next === 'function' ? next(slots[i].value) : next;
      if (!Object.is(value, slots[i].value)) { slots[i].value = value; dirty = true; }
    }];
  };
  const effectHook = (queue: (() => void)[]) => (effect: () => void | (() => void), deps: unknown[]) => {
    const i = index++, prior = slots[i];
    if (!prior || deps.length !== prior.deps.length || deps.some((value, j) => !Object.is(value, prior.deps[j]))) {
      const slot = slots[i] = { deps, cleanup: prior?.cleanup };
      queue.push(() => { slot.cleanup?.(); slot.cleanup = effect(); });
    }
  };
  const React = { Fragment: 'Fragment', createElement: (type: unknown, props: any, ...children: any[]) => ({ type, props: props || {}, children: children.flat(Infinity).filter(child => child !== null && child !== undefined && child !== false) }) };
  const bridge = { command: (action: string, args: Data) => new Promise((resolve, reject) => {
    calls.push({ action, args, resolve, reject });
    if (action === 'sources.close') resolve({});
  }) };
  const Drawer = Object.fromEntries(['Root', 'Portal', 'Overlay', 'Content', 'Title', 'Description'].map(name => [name, `Drawer.${name}`]));
  const production = instantiate(React, effectHook(effects), effectHook(layouts), useRef, useState, bridge, Drawer, 'Button', 'IconButton', 'ShimmerText', 'BackIcon', 'NextIcon', 'CloseIcon', 'FileIcon', 'RefreshIcon');
  const render = () => {
    assert.ok(mounted, 'the harness must not render an unmounted component');
    do {
      dirty = false; index = 0;
      tree = production.AnswerSources({ target, revision, onClose: () => {} });
      const element = find(node => node.props.className === 'pw-drawer-body');
      element.props.ref.current = body;
      body.dataset = element.props['data-source-view'] === undefined ? {} : { sourceView: element.props['data-source-view'] };
      layouts.splice(0).forEach(effect => effect());
      effects.splice(0).forEach(effect => effect());
    } while (dirty);
  };
  const click = (node: any) => { assert.ok(!node.props.disabled, 'the production action must be enabled'); node.props.onClick(); render(); };
  render();
  return {
    calls, body, focuses, writesAfterUnmount, sourceKey: production.sourceKey,
    get read(): Call { const call = calls.findLast(call => call.action !== 'sources.close'); assert.ok(call); return call; },
    get rows() { return nodes(tree).filter(node => node.type === 'Button' && node.props['data-source-key']); },
    get alerts() { return nodes(tree).filter(node => node.props.role === 'alert').map(content); },
    get refresh() { return find(node => node.props['aria-label'] === '重新核对原来源'); },
    get text() { return content(tree); },
    hasButton(label: string) { return nodes(tree).some(node => node.type === 'Button' && content(node) === label); },
    open(handle: string) { click(find(node => node.type === 'Button' && node.props.key === handle)); },
    button(label: string) { click(find(node => node.type === 'Button' && content(node) === label)); },
    back() { click(find(node => node.props['aria-label'] === '返回来源列表')); },
    retry() { click(this.refresh); },
    scroll(top: number) { body.scrollTop = top; find(node => node.props.className === 'pw-drawer-body').props.onScroll({ currentTarget: body }); },
    updateRevision() { revision++; render(); },
    async settle(call: Call, value: unknown, error?: string) {
      if (error) call.reject(new Error(error)); else call.resolve(value);
      // Flush the production then/catch/finally chain, including close callbacks.
      for (let i = 0; i < 6; i++) await Promise.resolve();
      if (mounted) render();
    },
    unmount() {
      if (!mounted) return;
      slots.forEach(slot => slot.cleanup?.()); mounted = false;
      find(node => node.props.className === 'pw-drawer-body').props.ref.current = null;
    },
  };
}

function assertRead(call: Call, handle: string, cursor = 0, offset = 0) {
  assert.equal(call.action, 'sources.read');
  assert.deepEqual(call.args, { windowId: 'window-a', sourceHandle: handle, cursor, offset }, 'only the current native handle, never the presentation key or public pin, authorizes the read');
}

test('production presentation identity follows every public-pin field, not the fresh handle or title', () => {
  const f = fixture(), original = entry('handle-old'), key = f.sourceKey(original);
  assert.equal(f.sourceKey(entry('handle-fresh', {}, '重命名')), key);
  for (const [field, value] of Object.entries({ kind: 'memory', sourceId: 'source-b', sourceRevision: 8, messageIndex: 3, messageRevision: 4 })) {
    assert.notEqual(f.sourceKey(entry('handle-fresh', { [field]: value })), key, `${field} must distinguish a different original pin`);
  }
  f.unmount();
});

test('returning restores list scroll and focuses the same pin after handles rotate and rows reorder', async () => {
  const f = fixture();
  assert.equal(f.read.action, 'sources.open');
  assert.deepEqual(f.read.args, { messageId: 'answer-a' });
  await f.settle(f.read, listPage([entry('a-old'), entry('b-old', { sourceId: 'source-b' })]));
  const originalKey = f.rows[0].props['data-source-key'];
  // Opening also captures scroll synchronously, without relying on an onScroll event.
  f.body.scrollTop = 342; f.open('a-old'); assertRead(f.read, 'a-old');
  f.scroll(0); // A loading view has no source-view key and must not overwrite saved scroll.
  await f.settle(f.read, detailPage()); assert.equal(f.body.scrollTop, 0);
  f.scroll(68); f.back(); assertRead(f.read, '');
  f.scroll(5);
  await f.settle(f.read, listPage([entry('b-fresh', { sourceId: 'source-b' }), entry('a-fresh')]));
  assert.equal(f.body.scrollTop, 342);
  assert.deepEqual(f.focuses, [{ key: originalKey, handle: 'a-fresh', options: { preventScroll: true } }]);
  f.open('a-fresh'); assertRead(f.read, 'a-fresh');
  await f.settle(f.read, detailPage());
  assert.equal(f.body.scrollTop, 68, 'detail scroll follows the pin despite a new authorization handle');
  assert.equal(f.focuses.length, 1, 'detail restoration must not focus a list row');
  assert.equal(f.calls.length, 4, 'restoration must not issue additional reads');
  f.unmount();
});

test('detail paging keeps per-source segment scroll and returns to the original list cursor', async () => {
  const f = fixture(); await f.settle(f.read, listPage([], 20));
  f.button('下一组来源'); assertRead(f.read, '', 20);
  await f.settle(f.read, listPage([entry('a-1'), entry('b-1', { sourceId: 'source-b' })]));
  f.scroll(190); f.open('a-1'); assertRead(f.read, 'a-1', 20);
  await f.settle(f.read, detailPage(0, 1000)); f.scroll(82);
  f.button('下一段'); assertRead(f.read, 'a-1', 20, 1000);
  await f.settle(f.read, detailPage(1000, 2000)); assert.equal(f.body.scrollTop, 0); f.scroll(211);
  f.button('下一段'); assertRead(f.read, 'a-1', 20, 2000);
  await f.settle(f.read, detailPage(2000)); assert.equal(f.body.scrollTop, 0); f.scroll(303);
  f.button('上一段'); assertRead(f.read, 'a-1', 20, 1000);
  await f.settle(f.read, detailPage(1000, 2000)); assert.equal(f.body.scrollTop, 211);
  f.button('上一段'); assertRead(f.read, 'a-1', 20);
  await f.settle(f.read, detailPage(0, 1000)); assert.equal(f.body.scrollTop, 82); assert.equal(f.hasButton('上一段'), false);
  f.back(); assertRead(f.read, '', 20);
  await f.settle(f.read, listPage([entry('a-2'), entry('b-2', { sourceId: 'source-b' })]));
  assert.equal(f.body.scrollTop, 190);
  f.open('b-2'); assertRead(f.read, 'b-2', 20);
  await f.settle(f.read, detailPage(0, 1000)); assert.equal(f.body.scrollTop, 0); assert.equal(f.hasButton('上一段'), false); f.scroll(34);
  f.button('下一段'); assertRead(f.read, 'b-2', 20, 1000);
  await f.settle(f.read, detailPage(1000)); assert.equal(f.body.scrollTop, 0, 'another source must not inherit source A segment scroll'); f.scroll(44);
  f.back(); assertRead(f.read, '', 20);
  await f.settle(f.read, listPage([entry('a-3'), entry('b-3', { sourceId: 'source-b' })]));
  assert.equal(f.body.scrollTop, 190);
  f.open('a-3'); assertRead(f.read, 'a-3', 20);
  await f.settle(f.read, detailPage(0, 1000));
  assert.equal(f.body.scrollTop, 82); assert.equal(f.hasButton('上一段'), false, 'a fresh selection starts at offset zero without another detail history stack');
  f.button('下一段'); assertRead(f.read, 'a-3', 20, 1000);
  await f.settle(f.read, detailPage(1000)); assert.equal(f.body.scrollTop, 211);
  f.unmount();
});

for (const changes of [{ sourceRevision: 8 }, { messageRevision: 4 }]) {
  test(`a changed ${Object.keys(changes)[0]} cannot restore the earlier detail position or return focus`, async () => {
    const f = fixture(); await f.settle(f.read, listPage([entry('old-revision')]));
    const originalKey = f.rows[0].props['data-source-key'];
    f.scroll(147); f.open('old-revision'); await f.settle(f.read, detailPage()); f.scroll(286);
    f.back(); await f.settle(f.read, listPage([entry('new-revision', changes)]));
    assert.equal(f.body.scrollTop, 147, 'the original list position is still valid');
    assert.notEqual(f.rows[0].props['data-source-key'], originalKey);
    assert.deepEqual(f.focuses, [], 'a revised source is not the original return-focus target');
    f.open('new-revision'); assertRead(f.read, 'new-revision'); await f.settle(f.read, detailPage());
    assert.equal(f.body.scrollTop, 0, 'a changed pin must not inherit the old detail reading place');
    f.unmount();
  });
}

test('returning while detail is pending ignores that late detail without closing the live window', async () => {
  const f = fixture(); await f.settle(f.read, listPage([entry('a-1')]));
  f.scroll(173); f.open('a-1'); const retired = f.read;
  f.back(); const current = f.read; assertRead(current, '');
  await f.settle(retired, detailPage());
  assert.equal(f.refresh.props.disabled, true); assert.equal(f.rows.length, 0);
  assert.equal(f.calls.some(call => call.action === 'sources.close'), false);
  await f.settle(current, listPage([entry('a-2')]));
  assert.equal(f.rows.length, 1); assert.equal(f.body.scrollTop, 173);
  assert.equal(f.focuses[0].handle, 'a-2'); assert.equal(f.refresh.props.disabled, false);
  f.unmount();
});

test('a superseded open closes only its stale window and cannot replace the newer result', async () => {
  const f = fixture(), retired = f.read;
  f.updateRevision(); const current = f.read;
  assert.equal(current.action, 'sources.open');
  await f.settle(current, listPage([entry('current-handle')], -1, 'current-window'));
  await f.settle(retired, listPage([entry('stale-handle')], -1, 'stale-window'));
  assert.deepEqual(f.rows.map(row => row.props.key), ['current-handle']);
  assert.deepEqual(f.calls.filter(call => call.action === 'sources.close').map(call => call.args), [{ windowId: 'stale-window' }]);
  assert.equal(f.refresh.props.disabled, false);
  f.unmount();
  assert.deepEqual(f.calls.filter(call => call.action === 'sources.close').map(call => call.args), [{ windowId: 'stale-window' }, { windowId: 'current-window' }]);
  assert.deepEqual(f.writesAfterUnmount, []);
});

test('a retired read error cannot leak or clear busy state for a newer revision', async () => {
  const f = fixture(); await f.settle(f.read, listPage([entry('a-1')]));
  f.open('a-1'); const retired = f.read;
  f.updateRevision(); const current = f.read;
  await f.settle(retired, null, '旧请求失败');
  assert.deepEqual(f.alerts, []); assert.equal(f.refresh.props.disabled, true);
  await f.settle(current, detailPage());
  assert.deepEqual(f.alerts, []); assert.equal(f.refresh.props.disabled, false);
  assert.ok(f.text.includes('原保留文本 0'));
  f.unmount();
});

test('unmount ignores a late open result and closes the window it created', async () => {
  const f = fixture(), pending = f.read; f.unmount();
  await f.settle(pending, listPage([entry('late-handle')], -1, 'late-window'));
  assert.deepEqual(f.writesAfterUnmount, []); assert.deepEqual(f.focuses, []);
  assert.deepEqual(f.calls.map(call => [call.action, call.args]), [
    ['sources.open', { messageId: 'answer-a' }],
    ['sources.close', { windowId: 'late-window' }],
  ]);
});

for (const outcome of ['success', 'failure']) {
  test(`unmount closes its current window and ignores a late read ${outcome}`, async () => {
    const f = fixture(); await f.settle(f.read, listPage([entry('a-1')]));
    f.open('a-1'); const pending = f.read; f.unmount();
    assert.deepEqual(f.calls.at(-1)?.args, { windowId: 'window-a' });
    assert.equal(f.calls.at(-1)?.action, 'sources.close');
    await f.settle(pending, detailPage(), outcome === 'failure' ? '已关闭后的错误' : undefined);
    assert.deepEqual(f.writesAfterUnmount, []); assert.deepEqual(f.focuses, []);
    assert.equal(f.calls.filter(call => call.action !== 'sources.close').length, 2, 'late callbacks must not reopen or retry the window');
  });
}

test('open and detail failures remain explicitly retryable with the same native target and handle', async () => {
  const f = fixture(); await f.settle(f.read, null, '首次读取失败');
  assert.deepEqual(f.alerts, ['首次读取失败']); assert.equal(f.refresh.props.disabled, false);
  f.retry(); assert.equal(f.read.action, 'sources.open'); assert.deepEqual(f.read.args, { messageId: 'answer-a' });
  assert.deepEqual(f.alerts, []); assert.equal(f.refresh.props.disabled, true);
  await f.settle(f.read, listPage([entry('retry-handle')]));
  f.open('retry-handle'); assertRead(f.read, 'retry-handle'); await f.settle(f.read, null, '原内容读取失败');
  assert.deepEqual(f.alerts, ['原内容读取失败']); assert.equal(f.refresh.props.disabled, false);
  f.retry(); assertRead(f.read, 'retry-handle');
  await f.settle(f.read, detailPage());
  assert.deepEqual(f.alerts, []); assert.equal(f.refresh.props.disabled, false); assert.ok(f.text.includes('原保留文本 0'));
  assert.ok(f.calls.every(call => ['sources.open', 'sources.read'].includes(call.action)), 'retry must not perform unrelated bridge actions');
  f.unmount();
});

test('source reading names the selected original source, then uses the current native detail title', async () => {
  const f = fixture(); await f.settle(f.read, listPage([entry('titled-source', {}, '列表中的原来源标题')]));
  f.open('titled-source'); assertRead(f.read, 'titled-source');
  assert.ok(f.text.includes('列表中的原来源标题'));
  const detail = { ...detailPage(), detail: { ...detailPage().detail, context: { title: '本次读取的原文标题', url: 'https://example.test/original' } } };
  await f.settle(f.read, detail);
  assert.ok(f.text.includes('本次读取的原文标题')); assert.ok(f.text.includes('https://example.test/original'));
  assert.equal(f.text.includes('列表中的原来源标题'), false);
  f.unmount();
});
