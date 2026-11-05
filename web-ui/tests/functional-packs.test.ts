import test from 'node:test';
import assert from 'node:assert/strict';
import { packConfirmInput, packConsentKey, packIdentity, packReceipt, packReview, packSelectionMatches } from '../src/functional-packs.ts';

const review = (extra: any = {}) => ({ pageId: 'page-a', reviewHandle: 'review-a', selectionRevision: 0,
  selectedKeys: ['data', 'transform', 'method'], selectionMatches: true,
  data: { ready: true, executed: false, atomic: false, permissionsRestored: false, removing: false,
    components: ['data', 'transform', 'method'].map(key => ({ key, selected: true, selectable: true })) }, ...extra });

test('only the original native page and complete exact component review can be acknowledged', () => {
  const original = review(); assert.equal(packReview(original, 'page-a'), original);
  for (const bad of [review({ pageId: 'page-b' }), review({ selectionRevision: '0' }), review({ selectedKeys: ['data', 'data'] }), review({ selectedKeys: ['unlisted'] }), review({ data: { ...original.data, executed: true } })]) assert.throws(() => packReview(bad, 'page-a'));
  assert.deepEqual(packConfirmInput(original, original, packConsentKey(original)), { pageId: 'page-a', reviewHandle: 'review-a', selectionRevision: 0, consent: true });
});
test('removing or adding a component immediately invalidates consent and an earlier enabled callback', () => {
  const full = review(), changed = review({ selectedKeys: ['data', 'transform'], selectionMatches: false });
  assert.equal(packSelectionMatches(changed), false);
  assert.throws(() => packConfirmInput(changed, full, packConsentKey(full)));
  const subset = review({ reviewHandle: 'review-subset', selectedKeys: ['data', 'transform'], data: { ...full.data, components: full.data.components.map(row => ({ ...row, selected: row.key !== 'method' })) } });
  assert.equal(packSelectionMatches(subset), true);
  assert.throws(() => packConfirmInput({ ...subset, selectedKeys: full.selectedKeys }, subset, packConsentKey(subset)));
  assert.throws(() => packConfirmInput({ ...subset, selectionRevision: 2 }, subset, packConsentKey(subset)));
  assert.throws(() => packConfirmInput(subset, subset, ''));
});
test('returning to the same frozen subset permits fresh consent without expanding the actual install', () => {
  const original = review(), subset = review({ selectionRevision: 3, selectedKeys: ['transform', 'data'], data: { ...original.data, components: original.data.components.map(row => ({ ...row, selected: row.key !== 'method' })) } });
  assert.equal(packSelectionMatches(subset), true);
  assert.deepEqual(packConfirmInput(subset, subset, packConsentKey(subset)), { pageId: 'page-a', reviewHandle: 'review-a', selectionRevision: 3, consent: true });
});
test('uninstall consent uses the same exact native component set including retained manual versions', () => {
  const original = review({ data: { ...review().data, removing: true, components: review().data.components.map(row => ({ ...row, action: row.key === 'method' ? 'retain_changed' : 'remove' })) } });
  assert.equal(packSelectionMatches(original), true);
  assert.equal(packConfirmInput(original, original, packConsentKey(original)).consent, true);
  assert.throws(() => packIdentity({ id: 'kit', installationId: 'old', revision: '1' }));
  assert.deepEqual(packIdentity({ id: 'kit', installationId: 'old', revision: 1 }), { id: 'kit', installationId: 'old', revision: 1 });
});
test('queued acknowledgements and unknown component effects cannot be presented as a whole-pack success', () => {
  const receipt = { id: 'kit', installationId: 'install-a', taskId: 'task-a', status: 'partial', executed: false, replayAllowed: false, wholePackageInstalled: false };
  const value = { pageId: 'page-a', snapshot: { packs: [] }, receipt, replayAllowed: false };
  assert.equal(packReceipt(value, 'page-a').status, 'partial');
  assert.equal(packReceipt({ ...value, receipt: { ...receipt, status: 'unknown' } }, 'page-a').status, 'unknown');
  for (const bad of [{ accepted: true }, { ...value, pageId: 'other' }, { ...value, receipt: { ...receipt, executed: true } }, { ...value, receipt: { ...receipt, status: 'accepted' } }]) assert.throws(() => packReceipt(bad, 'page-a'));
});
