import test from 'node:test';
import assert from 'node:assert/strict';

test('document-fenced requests keep native acceptance separate from durable input state', async () => {
  const sent: any[] = [];
  const rootDataset: Record<string, string> = {};
  (globalThis as any).document = { documentElement: { dataset: rootDataset } };
  (globalThis as any).window = {
    PocketNative: { postMessage: (raw: string) => { sent.push(JSON.parse(raw)); } },
  };
  const { bridge } = await import('../src/bridge.ts');
  const receiver = (globalThis as any).window.PocketWeb;
  let changes = 0;
  const unsubscribe = bridge.subscribe(() => { changes++; });
  receiver.receive({ v: 1, kind: 'state', revision: 1, snapshot: {
    state: { messages: [], tasks: [] },
    composer: { key: 'private:one', epoch: 7, text: '保留中的输入', revision: 4 },
    capabilities: { commands: ['composer.edit', 'composer.send', 'chat.cancel'] },
  } });
  assert.equal(bridge.getState().composer?.text, '保留中的输入');
  assert.equal(changes, 1);

  await assert.rejects(bridge.command('featureRequest', {}), /不可执行/);
  assert.equal(sent.length, 0, 'unsupported actions never enter the native bridge');

  let hostReady = false;
  const hostWait = bridge.waitForHostDocument().then(() => { hostReady = true; });
  await Promise.resolve();
  assert.equal(hostReady, false);
  receiver.hostConfiguration({ documentToken: 'native-document-token' });
  await hostWait;
  assert.equal(bridge.getState().host?.documentToken, undefined);
  const ready = bridge.command('app.ready');
  const readyRequest = sent.at(-1);
  assert.equal(readyRequest.hostDocumentToken, 'native-document-token');
  assert.ok(readyRequest.documentId.length >= 16);
  assert.equal(readyRequest.args.documentId, readyRequest.documentId);
  receiver.receive({ v: 1, kind: 'result', id: readyRequest.id, ok: true, value: { accepted: true } });
  await ready;
  receiver.hostConfiguration({ documentToken: 'rotated-after-resume' });
  assert.equal(bridge.getState().host?.documentToken, undefined);

  const edit = bridge.command('composer.edit', { key: 'private:one', epoch: 7, text: '修改后的草稿' });
  const editRequest = sent.at(-1);
  const send = bridge.command('composer.send', { key: 'private:one', epoch: 7, text: '修改后的草稿' });
  const sendRequest = sent.at(-1);
  assert.notEqual(editRequest.id, sendRequest.id);
  assert.equal(editRequest.hostDocumentToken, 'rotated-after-resume');
  assert.equal(editRequest.documentId, readyRequest.documentId);
  assert.equal(sendRequest.documentId, readyRequest.documentId);
  assert.equal(sendRequest.args.epoch, 7);

  // Native replies may arrive in a different order. Acceptance cannot clear the draft.
  receiver.receive({ v: 1, kind: 'result', id: 'another-window', ok: true, value: { accepted: true } });
  receiver.receive({ v: 1, kind: 'result', id: sendRequest.id, ok: true, value: { accepted: true } });
  assert.deepEqual(await send, { accepted: true });
  assert.equal(bridge.getState().composer?.text, '保留中的输入');
  receiver.receive({ v: 1, kind: 'result', id: editRequest.id, ok: false, error: { code: 'not_accepted', message: '原输入窗口已更新' } });
  await assert.rejects(edit, error => error instanceof Error && error.message === '原输入窗口已更新' && (error as any).code === 'not_accepted');
  assert.equal(sent.length, 3, 'a rejected or unmatched receipt must not resend an operation');

  receiver.receive({ v: 1, kind: 'state', revision: 0, snapshot: { composer: { text: '' } } });
  receiver.receive({ v: 2, kind: 'state', revision: 99, snapshot: { composer: { text: '' } } });
  assert.equal(bridge.getState().composer?.text, '保留中的输入', 'stale or foreign-version state cannot erase the current draft');
  receiver.receive({ v: 1, kind: 'state', revision: 2, snapshot: {
    state: { messages: [], tasks: [] },
    composer: { key: 'private:one', epoch: 8, text: '宿主持久化的草稿', revision: 5 },
    capabilities: { commands: ['composer.edit'] },
  } });
  assert.equal(bridge.getState().composer?.text, '宿主持久化的草稿');
  assert.equal(bridge.getState().composer?.epoch, 8);
  unsubscribe();
  const before = changes;
  receiver.hostConfiguration({ fontScale: 1.2, keyboardVisible: true });
  assert.equal(rootDataset.pocketKeyboard, 'true');
  assert.equal(bridge.getState().host?.keyboardVisible, true);
  receiver.hostConfiguration({ fontScale: 1.3 });
  assert.equal(rootDataset.pocketKeyboard, 'true', 'an unrelated host update must not dismiss the IME signal');
  receiver.hostConfiguration({ keyboardVisible: false });
  assert.equal(rootDataset.pocketKeyboard, 'false');
  assert.equal(bridge.getState().host?.keyboardVisible, false);
  assert.equal(changes, before, 'closed subscribers do not keep receiving UI updates');

  let returns = 0;
  const clearBack = bridge.setBackHandler(() => { returns++; return true; });
  assert.equal(receiver.handleBack(), true);
  clearBack();
  assert.equal(receiver.handleBack(), false);
  assert.equal(returns, 1);
  delete (globalThis as any).window.PocketNative;
  await assert.rejects(bridge.command('composer.edit', {}), error => error instanceof Error && /尚未连接/.test(error.message) && (error as any).code === 'not_sent');
  assert.equal(sent.length, 3);
});
