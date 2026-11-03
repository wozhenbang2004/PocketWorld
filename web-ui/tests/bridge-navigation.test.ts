import test from 'node:test';
import assert from 'node:assert/strict';

test('page cleanup and the next native page entry wait for their original receipts', async () => {
  const sent: any[] = [];
  (globalThis as any).document = { documentElement: { dataset: {} } };
  (globalThis as any).window = { __pocketDocumentToken: 'navigation-document', PocketNative: { postMessage: (raw: string) => sent.push(JSON.parse(raw)) } };
  const { bridge } = await import('../src/bridge.ts');
  const receive = (globalThis as any).window.PocketWeb.receive;
  const answer = (request: any) => receive({ v: 1, kind: 'result', id: request.id, ok: true, value: { accepted: true } });
  const flush = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };
  receive({ v: 1, kind: 'state', revision: 1, snapshot: { state: {}, capabilities: { commands: ['connections.leave', 'archives.open', 'archives.refresh', 'storage.open'] } } });
  const ready = bridge.command('app.ready'); answer(sent.at(-1)); await ready;
  const leave = bridge.command('connections.leave', { section: 'accounts' });
  const leaveRequest = sent.at(-1);
  const enter = bridge.command('archives.open');
  const beforeReceipt = sent.length;
  answer(leaveRequest); await leave; await flush();
  const enterRequest = sent.find(row => row.action === 'archives.open');
  assert.ok(enterRequest); answer(enterRequest); await enter; await flush();
  assert.equal(beforeReceipt, 2, 'the next entry must not be posted with the retiring native window ticket');

  const next = bridge.command('storage.open');
  const nextRequest = sent.at(-1), count = sent.length;
  const refresh = bridge.command('archives.refresh');
  const beforeNextReceipt = sent.length;
  answer(nextRequest); await next; await flush();
  const refreshRequest = sent.find(row => row.action === 'archives.refresh');
  assert.ok(refreshRequest); answer(refreshRequest); await refresh;
  assert.equal(beforeNextReceipt, count, 'a command behind navigation waits until native has changed its window');
  await flush();
  const retiring = bridge.command('connections.leave', { section: 'storage' });
  const retiringRequest = sent.at(-1), beforeRotation = sent.length;
  const staleEntry = bridge.command('archives.open');
  const rejected = assert.rejects(staleEntry, error => error instanceof Error && (error as any).code === 'not_sent');
  (globalThis as any).window.PocketWeb.hostConfiguration({ documentToken: 'another-foreground-window' });
  answer(retiringRequest); await retiring; await rejected;
  assert.equal(sent.length, beforeRotation, 'an old queued entry never moves into a replacement foreground window');
});
