import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { bundleFiles, verifyBundle } from '../scripts/verify-build-receipt.mjs';

function fixture() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pocket-bundle-'));
  for (const [file, data] of Object.entries({ 'index.html': '<html data-pocket-ui="library-web">', 'assets/main.js': 'main', 'fonts/main.woff2': 'font', 'licenses/main.txt': 'license' })) {
    fs.mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
    fs.writeFileSync(path.join(root, file), data);
  }
  const receipt = { kind: 'pocketworld-library-web', files: bundleFiles(root).map(file => {
    const data = fs.readFileSync(path.join(root, file));
    return { path: file, bytes: data.length, sha256: crypto.createHash('sha256').update(data).digest('hex') };
  }) };
  const save = () => fs.writeFileSync(path.join(root, 'build-identity.json'), JSON.stringify(receipt));
  save();
  return { root, receipt, save };
}

test('exact receipt accepts all required bundled resources', t => {
  const { root, receipt } = fixture(); t.after(() => fs.rmSync(root, { recursive: true }));
  assert.equal(verifyBundle(root).files.length, receipt.files.length);
});

for (const file of ['assets/extra.js', 'extra.html', 'assets/build-identity.json']) {
  test(`unlisted ${file} is rejected even when every listed hash is correct`, t => {
    const { root } = fixture(); t.after(() => fs.rmSync(root, { recursive: true }));
    fs.writeFileSync(path.join(root, file), 'unlisted');
    assert.throws(() => verifyBundle(root), /Unlisted/);
  });
}

test('missing and modified resources fail verification', t => {
  const { root } = fixture(); t.after(() => fs.rmSync(root, { recursive: true }));
  fs.writeFileSync(path.join(root, 'assets/main.js'), 'changed');
  assert.throws(() => verifyBundle(root), /changed/);
  fs.unlinkSync(path.join(root, 'assets/main.js'));
  assert.throws(() => verifyBundle(root), /Missing/);
});

test('duplicate and ambiguous receipt paths fail before resolving files', t => {
  const { root, receipt, save } = fixture(); t.after(() => fs.rmSync(root, { recursive: true }));
  const entry = receipt.files[0]; receipt.files.push(entry); save();
  assert.throws(() => verifyBundle(root), /duplicate/);
  receipt.files.pop();
  for (const name of ['../secret', '/index.html', 'assets/../index.html', 'assets//main.js', 'assets\\main.js', 'assets/%2e/main.js', 'build-identity.json']) {
    receipt.files[0] = { ...entry, path: name }; save();
    assert.throws(() => verifyBundle(root), /Invalid/);
  }
});

test('symlink resources and receipt are rejected without following them', t => {
  const { root } = fixture(); t.after(() => fs.rmSync(root, { recursive: true }));
  fs.symlinkSync('main.js', path.join(root, 'assets/link.js'));
  assert.throws(() => verifyBundle(root), /Non-regular/);
  fs.unlinkSync(path.join(root, 'assets/link.js'));
  fs.renameSync(path.join(root, 'build-identity.json'), path.join(root, 'receipt-data'));
  fs.symlinkSync('receipt-data', path.join(root, 'build-identity.json'));
  assert.throws(() => verifyBundle(root), /regular/);
});
