import test from 'node:test';
import assert from 'node:assert/strict';
import { assertionEvidenceText } from '../src/memory-assertion-evidence.ts';

const text = '记住，我下午不喝咖啡。';
const span = { sourceRef: { kind: 'message', sourceId: 'original-message', revision: 2, ownerId: 'owner', spaceId: 'private' }, offset: 7, chars: text.length, sha256: 'a'.repeat(64) };
const evidence = { ...span, text, verifiedOriginalSpan: true, assertionTruthVerified: false };

test('formal assertion evidence has no quote; its exact native source read supplies display text', () => {
  assert.equal(assertionEvidenceText(span, [evidence]), text);
  assert.equal(assertionEvidenceText({ ...span, quote: 'unverified copy' }, []), null);
  assert.equal(assertionEvidenceText(span, [{ ...evidence, verifiedOriginalSpan: false, status: 'source_unavailable' }]), null);
});

test('a different source version, range, digest or claimed factual verification cannot supply the quote', () => {
  for (const replacement of [
    { sourceRef: { ...span.sourceRef, sourceId: 'another' } }, { sourceRef: { ...span.sourceRef, revision: 3 } },
    { sourceRef: { ...span.sourceRef, spaceId: 'other' } }, { offset: 0 }, { chars: text.length + 1 },
    { sha256: 'b'.repeat(64) }, { assertionTruthVerified: true }, { text: text + '伪造' },
  ]) assert.equal(assertionEvidenceText(span, [{ ...evidence, ...replacement }]), null);
});
