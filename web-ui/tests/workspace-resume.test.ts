import assert from 'node:assert/strict';
import test from 'node:test';
import { resumeSshFile } from '../src/workspace-resume.ts';

const before = { profileId: 'host-a', path: 'src/main.txt', status: 'completed', content: 'saved before', version: `sha256:${'a'.repeat(64)}` };
const after = { ...before, content: 'saved elsewhere', version: `sha256:${'b'.repeat(64)}` };

test('returning to an unchanged SSH file preserves the unsaved draft and its original base', () => {
  const restored = resumeSshFile(before, 'my draft', { ...before }, 'host-a');
  assert.equal(restored.content, 'my draft');
  assert.equal(restored.file.version, before.version);
  assert.equal(restored.conflict, false);
});

test('returning after another write never silently rebases a draft or clears an existing conflict', () => {
  const restored = resumeSshFile(before, 'my draft', after, 'host-a');
  assert.equal(restored.content, 'my draft');
  assert.equal(restored.file.content, 'saved elsewhere');
  assert.equal(restored.conflict, true);
  assert.equal(resumeSshFile(after, restored.content, { ...after }, 'host-a', restored.conflict).conflict, true);
});

test('a clean reader adopts the actual saved version on return', () => {
  const restored = resumeSshFile(before, before.content, after, 'host-a');
  assert.equal(restored.content, after.content);
  assert.equal(restored.conflict, false);
});

test('a missing, partial or foreign read cannot validate the retained draft', () => {
  for (const invalid of [
    { ...after, profileId: 'host-b' }, { ...after, path: 'other.txt' }, { ...after, status: 'unknown' },
    { ...after, content: undefined }, { ...after, version: '' },
  ]) assert.throws(() => resumeSshFile(before, 'my draft', invalid, 'host-a'));
  assert.equal(before.content, 'saved before');
});
