import test from 'node:test';
import assert from 'node:assert/strict';

test('original preview return serializes composer commands and drops the old document queue', async () => {
  const sent: any[] = [];
  (globalThis as any).document = { documentElement: { dataset: {} } };
  (globalThis as any).window = { __pocketDocumentToken: 'preview-document', PocketNative: { postMessage: (raw: string) => sent.push(JSON.parse(raw)) } };
  const { bridge } = await import('../src/bridge.ts');
  const receive = (globalThis as any).window.PocketWeb.receive;
  const answer = (request: any, value: any = {}) => receive({ v: 1, kind: 'result', id: request.id, ok: true, value });
  const flush = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };
  receive({ v: 1, kind: 'state', revision: 1, snapshot: { state: {}, capabilities: { commands: ['projects.preview.return', 'composer.edit'] } } });
  const ready = bridge.command('app.ready'); answer(sent.at(-1)); await ready;
  const returning = bridge.command('projects.preview.return', { sessionId: 'original-preview' });
  const original = sent.at(-1), count = sent.length;
  const edit = bridge.command('composer.edit', { key: 'original-draft', epoch: 3, text: '保留原草稿' });
  assert.equal(sent.length, count);
  answer(original, { sessionId: 'original-preview', conversationId: 'original-conversation', returned: true });
  await returning; await flush();
  assert.equal(sent.at(-1).action, 'composer.edit'); answer(sent.at(-1)); await edit; await flush();

  const retiring = bridge.command('projects.preview.return', { sessionId: 'retiring-preview' });
  const retireRequest = sent.at(-1), before = sent.length;
  const staleEdit = bridge.command('composer.edit', { key: 'old-draft', epoch: 3, text: '不能写进新窗口' });
  const rejected = assert.rejects(staleEdit, error => error instanceof Error && (error as any).code === 'not_sent');
  (globalThis as any).window.PocketWeb.hostConfiguration({ documentToken: 'replacement-document' });
  answer(retireRequest); await retiring; await rejected;
  assert.equal(sent.length, before);
});
