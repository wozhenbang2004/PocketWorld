import { test } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import config from '../vite.config.ts';

test('production Vite manifest has an Android-packaged resource path', () => {
  const manifest = config.build?.manifest;
  assert.equal(typeof manifest, 'string', 'Vite default .vite manifest is filtered out of Android assets');
  const output = manifest as string;
  assert.equal(path.posix.isAbsolute(output), false);
  assert.ok(output.split('/').every(segment => segment.length > 0 && !segment.startsWith('.')));
});
