import test from 'node:test';
import assert from 'node:assert/strict';
import { compareFileText } from '../src/file-diff.ts';

const joined = (parts: Array<{ text: string }>) => parts.map(part => part.text).join('');
const marked = (parts: Array<{ text: string; changed: boolean }>) => joined(parts.filter(part => part.changed));
test('a middle-line edit marks only changed characters and keeps common context neutral', () => {
  const before = '开头\n状态：旧内容\n结尾\n', after = '开头\n状态：新内容\n结尾\n';
  const result = compareFileText(before, after);
  assert.equal(joined(result.before), before); assert.equal(joined(result.after), after);
  assert.equal(marked(result.before), '旧'); assert.equal(marked(result.after), '新');
});
test('insert, delete, empty and identical contents preserve their exact source', () => {
  for (const [before, after] of [['', '新增\n'], ['删除\n', ''], ['', ''], ['原文\n', '原文\n'], ['a\nb\n', 'a\n插入\nb\n']]) {
    const result = compareFileText(before, after);
    assert.equal(joined(result.before), before); assert.equal(joined(result.after), after);
    if (before === after) { assert.equal(marked(result.before), ''); assert.equal(marked(result.after), ''); }
  }
});
test('spaces, tabs, final newline and CRLF changes are not normalized away', () => {
  for (const [before, after] of [['a b', 'a  b'], ['a\tb', 'a b'], ['末尾\n', '末尾'], ['a\r\nb\r\n', 'a\nb\n']]) {
    const result = compareFileText(before, after);
    assert.equal(joined(result.before), before); assert.equal(joined(result.after), after);
    assert.equal(result.whitespaceChanged, true);
  }
});
test('Unicode code points, repeated lines and markup remain exact literal content', () => {
  for (const [before, after] of [['你好🙂\n你好🙂\n', '你好🙃\n你好🙂\n'], ['same\nsame\nold\nsame\n', 'same\nnew\nsame\nsame\n'], ['<script>old</script>', '<script>new</script>']]) {
    const result = compareFileText(before, after);
    assert.equal(joined(result.before), before); assert.equal(joined(result.after), after);
    for (const part of [...result.before, ...result.after]) assert.equal(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(part.text), false);
  }
});
test('large text falls back to exact line changes instead of marking unchanged lines', () => {
  const context = '保持原样'.repeat(26_000) + '\n';
  const before = context + 'old\n' + context, after = context + 'new\n' + context;
  const result = compareFileText(before, after);
  assert.equal(result.mode, 'line');
  assert.equal(joined(result.before), before); assert.equal(joined(result.after), after);
  assert.equal(marked(result.before), 'old\n'); assert.equal(marked(result.after), 'new\n');
});
test('oversized comparisons retain complete unmarked text instead of inventing a whole-file diff', () => {
  const before = 'a'.repeat(260_000), after = 'b'.repeat(260_000), result = compareFileText(before, after);
  assert.equal(result.mode, 'unmarked'); assert.equal(joined(result.before), before); assert.equal(joined(result.after), after);
  assert.equal(marked(result.before), ''); assert.equal(marked(result.after), '');
});

test('search exhaustion below the size limit keeps complete unmarked sources', () => {
  const before = Array.from({ length: 1600 }, (_, i) => 'a' + i + '\n').join('');
  const after = Array.from({ length: 1600 }, (_, i) => 'b' + i + '\n').join('');
  const result = compareFileText(before, after);
  assert.equal(result.mode, 'unmarked'); assert.equal(joined(result.before), before); assert.equal(joined(result.after), after);
  assert.equal(marked(result.before), ''); assert.equal(marked(result.after), '');
});
