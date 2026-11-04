import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import { currentTaskHeaders, taskHeaderPage } from '../src/task-history.ts';

// Execute the production hook with a small deterministic hook-lifecycle driver.
// This tests paging and async cancellation, not React DOM, layout or Android.
const source = ts.createSourceFile('TaskListHistory.tsx', readFileSync(new URL('../src/TaskListHistory.tsx', import.meta.url), 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
const hook = source.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'useTaskListHistory');
assert.ok(hook);
const compiled = ts.transpileModule(hook.getText(source).replace('export function', 'function'), { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
const instantiate = new Function('useEffect', 'useRef', 'useState', 'bridge', 'currentTaskHeaders', 'taskHeaderPage', compiled + '\nreturn useTaskListHistory;');

const header = (id: string, updatedAt = 1) => ({ id, goal: id, status: 'completed', updatedAt });
const page = (id: string, nextCursor = 'older-2') => ({ tasks: [header(id)], hasMore: !!nextCursor, nextCursor, auditBodyIncluded: false });

function fixture() {
  const slots: any[] = [], effects: (() => void)[] = [], calls: { action: string; args: any; resolve: (value: unknown) => void; reject: (error: Error) => void }[] = [];
  let index = 0, dirty = false, scope = 'conversation-a', enabled = true, mounted = true, view: any;
  let tasks = [header('recent')];
  const useRef = (initial: any) => { const i = index++; return slots[i] ??= { current: initial }; };
  const useState = (initial: any) => {
    const i = index++; slots[i] ??= { value: typeof initial === 'function' ? initial() : initial };
    return [slots[i].value, (next: any) => {
      assert.ok(mounted, 'a retired read must not write after unmount');
      const value = typeof next === 'function' ? next(slots[i].value) : next;
      if (!Object.is(value, slots[i].value)) { slots[i].value = value; dirty = true; }
    }];
  };
  const useEffect = (effect: () => void | (() => void), deps: unknown[]) => {
    const i = index++, prior = slots[i];
    if (!prior || deps.some((value, j) => !Object.is(value, prior.deps[j]))) {
      const slot = slots[i] = { deps, cleanup: prior?.cleanup };
      effects.push(() => { slot.cleanup?.(); slot.cleanup = effect(); });
    }
  };
  const useHistory = instantiate(useEffect, useRef, useState, { command: (action: string, args: any) => new Promise((resolve, reject) => calls.push({ action, args, resolve, reject })) }, currentTaskHeaders, taskHeaderPage);
  const render = () => {
    do {
      dirty = false; index = 0;
      view = useHistory(tasks, 'older-1', scope, enabled);
      effects.splice(0).forEach(effect => effect());
    } while (dirty);
  };
  render();
  return {
    get view() { return view; }, calls,
    update(nextScope = scope, nextEnabled = enabled, nextTasks = tasks) { scope = nextScope; enabled = nextEnabled; tasks = nextTasks; render(); },
    render,
    async settle(call: number, value: unknown, error?: string) {
      if (error) calls[call].reject(new Error(error)); else calls[call].resolve(value);
      await Promise.resolve(); await Promise.resolve();
      if (mounted) render();
    },
    unmount() { slots.forEach(slot => slot.cleanup?.()); mounted = false; },
  };
}

test('task history keeps the same loaded window and back cursor when returning to the route', async () => {
  const f = fixture(); f.view.older(); await f.settle(0, page('earlier'));
  assert.equal(f.view.earlier, true);
  f.update('conversation-a', false); f.update('conversation-a', true);
  assert.equal(f.view.earlier, true, 'navigation must not reset the selected history window');
  assert.deepEqual(f.view.tasks.map((row: any) => row.id), ['earlier']);
  assert.equal(f.view.canGoBack, true);
  assert.equal(f.calls.length, 1, 'returning must not request or replay anything');
  f.view.older(); await f.settle(1, page('oldest', ''));
  assert.equal(f.calls[1].args.cursor, 'older-2');
  f.view.newer(); await f.settle(2, page('earlier'));
  assert.equal(f.calls[2].args.cursor, 'older-1');
  f.view.newer(); f.render();
  assert.equal(f.view.earlier, false);
  assert.equal(f.calls.length, 3, 'back to recent uses the current native snapshot');
});

test('leaving invalidates a pending page without replacing the last settled window on return', async () => {
  const f = fixture(); f.view.older(); await f.settle(0, page('earlier'));
  f.view.older(); f.render(); assert.equal(f.view.busy, true);
  f.update('conversation-a', false); f.update('conversation-a', true);
  assert.equal(f.view.busy, false);
  await f.settle(1, page('late-oldest', ''));
  assert.deepEqual(f.view.tasks.map((row: any) => row.id), ['earlier']);
  assert.equal(f.view.hasMore, true);
  assert.equal(f.calls.length, 2);
});

test('a retired error cannot leak or clear a new request after returning', async () => {
  const f = fixture(); f.view.older(); await f.settle(0, page('earlier'));
  f.view.older(); f.update('conversation-a', false); f.update('conversation-a', true);
  f.view.older(); f.render();
  await f.settle(1, null, 'late error');
  assert.equal(f.view.error, ''); assert.equal(f.view.busy, true);
  await f.settle(2, page('oldest', ''));
  assert.equal(f.view.busy, false); assert.equal(f.view.error, '');
  assert.deepEqual(f.view.tasks.map((row: any) => row.id), ['oldest']);
});

test('scope change discards the previous window even while the route is hidden', async () => {
  const f = fixture(); f.view.older(); await f.settle(0, page('earlier'));
  f.view.older();
  f.update('conversation-a', false); f.update('conversation-b', false); f.update('conversation-a', true);
  await f.settle(1, page('foreign-late', ''));
  assert.equal(f.view.earlier, false); assert.equal(f.view.canGoBack, false);
  assert.deepEqual(f.view.tasks.map((row: any) => row.id), ['recent']);
  assert.equal(f.calls.length, 2);
});

test('explicit reset still selects recent tasks and retires any outstanding page', async () => {
  const f = fixture(); f.view.older(); await f.settle(0, page('earlier'));
  f.view.older(); f.view.reset(); f.render(); await f.settle(1, page('late', ''));
  assert.equal(f.view.earlier, false); assert.equal(f.view.busy, false); assert.equal(f.view.error, '');
  assert.deepEqual(f.view.tasks.map((row: any) => row.id), ['recent']);
  assert.equal(f.calls.length, 2);
});

test('retained rows use newer native data without adding unrelated tasks or allowing reads while hidden', async () => {
  const f = fixture(); f.view.older(); await f.settle(0, page('earlier'));
  f.update('conversation-a', false); f.view.older();
  f.update('conversation-a', true, [header('earlier', 50), header('outside', 100)]);
  assert.deepEqual(f.view.tasks.map((row: any) => [row.id, row.updatedAt]), [['earlier', 50]]);
  assert.equal(f.calls.length, 1);
  assert.ok(f.calls.every(call => call.action === 'history.read' && call.args.section === 'tasks'));
});

test('an unmounted task list rejects late responses', async () => {
  const f = fixture(); f.view.older(); f.unmount(); await f.settle(0, page('late'));
  assert.equal(f.calls.length, 1);
});
