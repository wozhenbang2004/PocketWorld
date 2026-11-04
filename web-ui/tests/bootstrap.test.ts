import test from 'node:test';
import assert from 'node:assert/strict';

test('late bridge module receives the native bootstrap without publishing it as UI state', async () => {
  const sent: any[] = [];
  (globalThis as any).document = { documentElement: { dataset: {} } };
  (globalThis as any).window = {
    __pocketDocumentToken: 'bootstrap-token',
    PocketNative: { postMessage: (raw: string) => sent.push(JSON.parse(raw)) },
  };
  const { bridge } = await import('../src/bridge.ts');
  await bridge.waitForHostDocument();
  assert.equal((globalThis as any).window.__pocketDocumentToken, undefined);
  assert.equal(bridge.getState().host?.documentToken, undefined);
  assert.equal(bridge.getDocumentEpoch(), 1);
  const epochs: number[] = [];
  const unsubscribe = bridge.subscribe(() => epochs.push(bridge.getDocumentEpoch()));
  const ready = bridge.bindHostDocument();
  await Promise.resolve(); await Promise.resolve();
  assert.equal(sent[0].hostDocumentToken, 'bootstrap-token');
  (globalThis as any).window.PocketWeb.hostConfiguration({ documentToken: 'resume-token' });
  assert.equal(bridge.getDocumentEpoch(), 2);
  assert.equal(epochs.at(-1), 2, 'subscribers can invalidate their original review on a token-only change');
  assert.equal(bridge.getState().host?.documentToken, undefined);
  (globalThis as any).window.PocketWeb.hostConfiguration({ documentToken: 'resume-token' });
  (globalThis as any).window.PocketWeb.hostConfiguration({ documentToken: '' });
  assert.equal(bridge.getDocumentEpoch(), 2, 'duplicate or invalid tokens do not invent a document change');
  await Promise.resolve(); await Promise.resolve();
  assert.equal(sent.length, 2);
  assert.equal(sent[1].hostDocumentToken, 'resume-token');
  (globalThis as any).window.PocketWeb.receive({ v: 1, kind: 'result', id: sent[0].id, ok: true, value: {} });
  assert.notEqual(bridge.getState().host?.ready, true, 'stale acknowledgement cannot bind new token');
  (globalThis as any).window.PocketWeb.receive({ v: 1, kind: 'result', id: sent[1].id, ok: true, value: {} });
  await ready;
  assert.equal(bridge.getState().host?.ready, true);
  unsubscribe();
});
