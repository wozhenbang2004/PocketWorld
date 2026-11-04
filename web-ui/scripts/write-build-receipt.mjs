import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import childProcess from 'node:child_process';
import { bundleFiles, verifyBundle } from './verify-build-receipt.mjs';

const root = path.resolve(import.meta.dirname, '../..');
const output = path.join(root, 'app/src/main/assets/pocket-ui');
const index = fs.readFileSync(path.join(output, 'index.html'), 'utf8');
if (!index.includes('data-pocket-ui="library-web"')) throw new Error('Library UI marker missing');
const files = bundleFiles(output).map(file => {
  const data = fs.readFileSync(path.join(output, file));
  return { path: file, bytes: data.length, sha256: crypto.createHash('sha256').update(data).digest('hex') };
});
for (const prefix of ['assets/', 'fonts/', 'licenses/']) {
  if (!files.some(file => file.path.startsWith(prefix))) throw new Error(`Missing bundled ${prefix}`);
}
let revision = process.env.GITHUB_SHA || '';
if (!revision) { try { revision = childProcess.execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim(); } catch { revision = 'local-uncommitted'; } }
fs.writeFileSync(path.join(output, 'build-identity.json'), JSON.stringify({ kind: 'pocketworld-library-web', sourceRevision: revision, node: process.version, minimumWebView: 111, files }, null, 2) + '\n');
verifyBundle(output);
console.log(`Verified ${files.length} locally bundled UI files for ${revision}`);
