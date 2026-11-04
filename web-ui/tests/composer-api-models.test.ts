import assert from 'node:assert/strict';
import test from 'node:test';
import { apiCapability, apiModelFavorite, apiModelKey, apiModelProjectionCurrent, apiModelReviewCurrent, apiModelReviewRequest, apiModelRows, apiModelSelection } from '../src/composer-api-models.ts';
const row = (connectionId: string, connectionRevision: number, extra = {}) => ({ connectionId, connectionRevision, modelId: 'same-model', available: true, favorite: false, lastUsedAt: 0, ...extra });
const projection = () => ({ windowId: 'original-window', viewGeneration: 1, format: 'pocketworld.model-picker.v1', selectionRevision: 8, preferencesAvailable: true, models: [row('a', 3), row('b', 5, { favorite: true, lastUsedAt: 42 }), row('removed', 0, { available: false, favorite: true })] });
const review = (p = projection(), index = 1) => ({ windowId: p.windowId, viewGeneration: p.viewGeneration, selectionRevision: p.selectionRevision, reviewHandle: 'opaque-original-review', model: p.models[index] });
test('same-named models retain exact connection and revision and select only the opaque review', () => {
  const p = projection(), r = review(p);
  assert.equal(apiModelProjectionCurrent(p, 8), true);
  assert.notEqual(apiModelKey(p.models[0]), apiModelKey(p.models[1]));
  assert.deepEqual(apiModelReviewRequest(p, 8, apiModelKey(p.models[1])), { windowId: 'original-window', connectionId: 'b', modelId: 'same-model', expectedRevision: 5 });
  assert.deepEqual(apiModelSelection(r, p, 8), { windowId: 'original-window', reviewHandle: 'opaque-original-review' });
  assert.deepEqual(apiModelFavorite(r, p, 8), { windowId: 'original-window', connectionId: 'b', modelId: 'same-model', expectedRevision: 5, favorite: false });
});
test('duplicate rows, native bindings, changed windows and revisions cannot select', () => {
  const p = projection(), r = review(p);
  assert.equal(apiModelProjectionCurrent({ ...p, models: [...p.models, p.models[0]] }, 8), false);
  assert.equal(apiModelProjectionCurrent({ ...p, models: [{ ...p.models[0], binding: { connectionId: 'a' } }] }, 8), false);
  for (const wrong of [{ ...r, windowId: 'next-window' }, { ...r, viewGeneration: 2 }, { ...r, selectionRevision: 9 }, { ...r, model: { ...r.model, connectionRevision: 6 } }]) {
    assert.equal(apiModelReviewCurrent(wrong, p, 8), false); assert.throws(() => apiModelSelection(wrong, p, 8));
  }
  assert.throws(() => apiModelReviewRequest(p, 9, apiModelKey(p.models[1])));
});
test('unavailable favorite can only be removed using its original missing-connection identity', () => {
  const p = projection(), r = review(p, 2);
  assert.throws(() => apiModelSelection(r, p, 8));
  assert.deepEqual(apiModelFavorite(r, p, 8), { windowId: p.windowId, connectionId: 'removed', modelId: 'same-model', expectedRevision: 0, favorite: false });
  assert.throws(() => apiModelFavorite(r, { ...p, preferencesAvailable: false }, 8));
});
test('recent and capability displays only use declared facts and persisted timestamps', () => {
  const p = projection(), before = JSON.stringify(p);
  assert.deepEqual(apiModelRows(p, 8, 'recent').map(row => row.connectionId), ['b']);
  assert.deepEqual(apiModelRows(p, 8, 'favorites').map(row => row.connectionId), ['b', 'removed']);
  apiModelSelection(review(p, 0), p, 8); assert.equal(JSON.stringify(p), before);
  assert.equal(apiCapability({ known: false, value: true }), '未声明');assert.equal(apiCapability({ known: true, value: 'true' }), '未声明');
  assert.equal(apiCapability({ known: true, value: true }), '支持');assert.equal(apiCapability({ known: true, value: false }), '不支持');
});
