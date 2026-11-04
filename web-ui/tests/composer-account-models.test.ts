import test from 'node:test';
import assert from 'node:assert/strict';
import { accountModelAccountRows, accountModelCatalog, accountModelProjectionCurrent, accountModelReadRequest,
  accountModelReviewCurrent, accountModelReviewRequest, accountModelSelection } from '../src/composer-account-models.ts';

function projection() {
  return { windowId: 'original-chat-window', selectionRevision: 7, viewGeneration: 3,
    accountSnapshot: { accounts: [{ id: 'account-a', version: 4, provider: 'chatgpt_plan', label: 'Original account', enabled: true,
      planUsageEnabled: true, status: 'connected', cooldownUntil: 0 }], selectedModelRef: { accountId: 'account-a', accountVersion: 4, modelId: 'model-a' } },
    catalog: { accountId: 'account-a', accountVersion: 4, models: [{ id: 'model-a', displayName: 'Model A',
      nativeRef: { provider: 'chatgpt_plan', accountId: 'account-a', accountVersion: 4, modelId: 'model-a' } }] } };
}
function review() {
  return { ...projection(), reviewHandle: 'one-original-review', accountId: 'account-a', accountVersion: 4, modelId: 'model-a',
    catalogFacts: { facts: { REASONING_LEVELS: { known: true, value: ['low', 'high'] } } } };
}
const options = { reasoningEffort: 'high', outputReserve: '4096', imageTokenReserve: '2048' };

test('account reads retain the native window, exact account and observed version', () => {
  const value = projection();
  assert.deepEqual(accountModelReadRequest(value, 7, 'account-a'), { windowId: 'original-chat-window', accountId: 'account-a', expectedVersion: 4 });
  assert.throws(() => accountModelReadRequest(value, 8, 'account-a'), /原账号/);
  assert.throws(() => accountModelReadRequest(value, 7, 'account-b'), /原账号/);
  assert.equal(accountModelProjectionCurrent({ ...value, selectionRevision: '7' }, 7), false);
  assert.equal(accountModelProjectionCurrent({ ...value, windowId: '' }, 7), false);
});

test('disabled, ungranted, limited and cooling accounts never offer a model-directory request', () => {
  for (const patch of [{ enabled: false }, { planUsageEnabled: false }, { usageLimited: true }, { status: 'signed_out' }, { cooldownUntil: 2000 }, { version: '4' }, { provider: 'other' }]) {
    const value: any = projection(); Object.assign(value.accountSnapshot.accounts[0], patch);
    assert.equal(accountModelAccountRows(value, 7, 1000)[0].disabled, true);
    assert.throws(() => accountModelReadRequest(value, 7, 'account-a', 1000), /原账号/);
  }
});

test('a catalog review keeps its original account even when model names match another account', () => {
  const value = projection(); value.accountSnapshot.accounts.push({ ...value.accountSnapshot.accounts[0], id: 'account-b' });
  assert.deepEqual(accountModelReviewRequest(value, 7, 'model-a'), { windowId: 'original-chat-window', accountId: 'account-a', modelId: 'model-a' });
  value.catalog.models[0].nativeRef.accountId = 'account-b';
  assert.equal(accountModelCatalog(value, 7)?.models.length, 0);
  assert.throws(() => accountModelReviewRequest(value, 7, 'model-a'), /原账号模型目录/);
});

test('changed account versions, missing references and duplicate model identities are not selectable', () => {
  const changed = projection(); changed.accountSnapshot.accounts[0].version = 5;
  assert.equal(accountModelCatalog(changed, 7), null);
  const absent: any = projection(); delete absent.catalog.models[0].nativeRef;
  assert.equal(accountModelCatalog(absent, 7)?.models.length, 0);
  const duplicate = projection(); duplicate.catalog.models.push({ ...duplicate.catalog.models[0] });
  assert.equal(accountModelCatalog(duplicate, 7)?.models.length, 0);
  const duplicateAccount = projection(); duplicateAccount.accountSnapshot.accounts.push({ ...duplicateAccount.accountSnapshot.accounts[0] });
  assert.throws(() => accountModelReadRequest(duplicateAccount, 7, 'account-a'), /原账号/);
});

test('selection sends only the original opaque review and bounded options, never a page-supplied native reference', () => {
  const value = { ...review(), nativeRef: { accountId: 'replacement' }, apiKey: 'must-not-copy', provider: 'other' };
  assert.deepEqual(accountModelSelection(value, 7, options), { windowId: 'original-chat-window', reviewHandle: 'one-original-review',
    options: { reasoningEffort: 'high', outputReserve: 4096, imageTokenReserve: 2048 } });
  assert.throws(() => accountModelSelection(value, 8, options), /原模型审核/);
});

test('local reserves and reasoning choices require exact native-supported values', () => {
  for (const patch of [{ outputReserve: '127' }, { outputReserve: '32769' }, { outputReserve: '4096.5' }, { outputReserve: '4e3' },
    { imageTokenReserve: '-1' }, { imageTokenReserve: '2000001' }, { reasoningEffort: 'invented' }]) {
    assert.throws(() => accountModelSelection(review(), 7, { ...options, ...patch }), /预留范围/);
  }
  assert.equal(accountModelSelection(review(), 7, { ...options, reasoningEffort: '', imageTokenReserve: '0' }).options.reasoningEffort, '');
});

test('an unknown selection result cannot reuse a cleared review or stale selection revision', () => {
  assert.equal(accountModelReviewCurrent(null, 7), false);
  assert.throws(() => accountModelSelection(null, 7, options), /原模型审核/);
  const changed = review(); changed.accountSnapshot.accounts[0].version = 5;
  assert.equal(accountModelReviewCurrent(changed, 7), false);
  assert.throws(() => accountModelSelection(changed, 7, options), /原模型审核/);
});
