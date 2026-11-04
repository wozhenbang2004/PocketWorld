import test from 'node:test';
import assert from 'node:assert/strict';
import { messageQuoteRange } from '../src/message-quote.ts';

test('ordinary original text uses exact UTF-16 positions', () => {
  assert.deepEqual(messageQuoteRange('甲乙😀丙', 1, 4), { offset: 1, chars: 3 });
  assert.equal(messageQuoteRange('甲乙😀丙', 2, 3), null);
  assert.equal(messageQuoteRange('甲乙😀丙', 3, 4), null);
});
test('textarea newline normalization maps back to the native original', () => {
  assert.deepEqual(messageQuoteRange('甲\r\n乙\r丙\n丁', 2, 5), { offset: 3, chars: 3 });
  assert.deepEqual(messageQuoteRange('甲\r\n乙', 0, 3), { offset: 0, chars: 4 });
  assert.deepEqual(messageQuoteRange('甲\r\n乙', 1, 2), { offset: 1, chars: 2 });
});
test('empty, invalid and oversized original selections are rejected', () => {
  assert.equal(messageQuoteRange('abc', 0, 0), null);
  assert.equal(messageQuoteRange('abc', -1, 2), null);
  assert.equal(messageQuoteRange('abc', 1, 4), null);
  assert.equal(messageQuoteRange('abc', 0.5, 2), null);
  assert.equal(messageQuoteRange('a'.repeat(4001), 0, 4001), null);
  assert.deepEqual(messageQuoteRange('a'.repeat(4000), 0, 4000), { offset: 0, chars: 4000 });
  assert.equal(messageQuoteRange('\r\n'.repeat(2001), 0, 2001), null);
});
