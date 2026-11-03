import test from 'node:test';
import assert from 'node:assert/strict';

test('a timed out first handshake resumes only after native token rotates', async () => {
  const sent: any[] = []; const callbacks: (() => void)[] = [];
  const originalSet = globalThis.setTimeout, originalClear = globalThis.clearTimeout;
  (globalThis as any).setTimeout = (fn: () => void) => { callbacks.push(fn); return callbacks.length; };
  (globalThis as any).clearTimeout = () => {};
  try {
    (globalThis as any).document = { documentElement: { dataset: {} } };
    (globalThis as any).window = { __pocketDocumentToken: 'T1', PocketNative: { postMessage: (raw: string) => sent.push(JSON.parse(raw)) } };
    const { bridge } = await import('../src/bridge.ts');
    const binding = bridge.bindHostDocument(); await Promise.resolve(); await Promise.resolve();
    assert.equal(sent.length, 1); callbacks[0](); await Promise.resolve(); await Promise.resolve();
    assert.equal(sent.length, 1, 'no same-token retry after unknown outcome');
    (globalThis as any).window.PocketWeb.hostConfiguration({ documentToken: 'T2' });
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
    assert.equal(sent.length, 2); assert.equal(sent[1].hostDocumentToken, 'T2');
    (globalThis as any).window.PocketWeb.receive({ v: 1, kind: 'result', id: sent[0].id, ok: true });
    assert.notEqual(bridge.getState().host?.ready, true);
    (globalThis as any).window.PocketWeb.receive({ v: 1, kind: 'result', id: sent[1].id, ok: true });
    await binding; assert.equal(bridge.getState().host?.ready, true);
  } finally { globalThis.setTimeout = originalSet; globalThis.clearTimeout = originalClear; }
});
