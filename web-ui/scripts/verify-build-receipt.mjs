import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { pathToFileURL } from 'node:url';

export const receiptName = 'build-identity.json';

export function canonicalResourcePath(value) {
  return typeof value === 'string' && value.length > 0 && value.length <= 512
    && !/[\\%?#\u0000-\u0020\u007f]/u.test(value)
    && value.split('/').every(part => part !== '' && part !== '.' && part !== '..');
}

/** The receipt itself is the only file outside the allowlist. Never follow links. */
export function bundleFiles(root) {
  if (!fs.lstatSync(root).isDirectory()) throw new Error('Web UI bundle root is not a directory');
  const files = [];
  function visit(dir) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
      const file = path.join(dir, entry.name);
      const relative = path.relative(root, file).split(path.sep).join('/');
      if (!canonicalResourcePath(relative)) throw new Error(`Invalid Web UI resource path: ${relative}`);
      if (entry.isDirectory()) visit(file);
      else if (!entry.isFile()) throw new Error(`Non-regular Web UI resource: ${relative}`);
      else if (relative !== receiptName) files.push(relative);
    }
  }
  visit(root);
  return files;
}

export function verifyBundle(root) {
  const manifest = path.join(root, receiptName);
  if (!fs.lstatSync(manifest).isFile()) throw new Error('Web UI receipt is not a regular file');
  if (fs.statSync(manifest).size > 1024 * 1024) throw new Error('Web UI receipt is too large');
  const receipt = JSON.parse(fs.readFileSync(manifest, 'utf8'));
  if (receipt.kind !== 'pocketworld-library-web' || !Array.isArray(receipt.files)
      || receipt.files.length === 0 || receipt.files.length > 4096) throw new Error('Unexpected Web UI bundle identity');
  const listed = new Set();
  for (const item of receipt.files) {
    if (!item || !canonicalResourcePath(item.path) || item.path === receiptName || listed.has(item.path)
        || !Number.isSafeInteger(item.bytes) || item.bytes < 0 || !/^[a-f0-9]{64}$/.test(item.sha256)) {
      throw new Error(`Invalid or duplicate Web UI receipt entry: ${item?.path}`);
    }
    listed.add(item.path);
  }
  const actual = bundleFiles(root);
  for (const file of actual) if (!listed.has(file)) throw new Error(`Unlisted Web UI resource: ${file}`);
  for (const file of listed) if (!actual.includes(file)) throw new Error(`Missing Web UI resource: ${file}`);
  for (const item of receipt.files) {
    const data = fs.readFileSync(path.join(root, item.path));
    if (data.length !== item.bytes || crypto.createHash('sha256').update(data).digest('hex') !== item.sha256) {
      throw new Error(`Web UI resource changed after verification: ${item.path}`);
    }
  }
  if (!listed.has('index.html') || !fs.readFileSync(path.join(root, 'index.html'), 'utf8').includes('data-pocket-ui="library-web"')) {
    throw new Error('Selected Web UI entry is missing');
  }
  for (const prefix of ['assets/', 'fonts/', 'licenses/']) {
    if (!actual.some(file => file.startsWith(prefix))) throw new Error(`Missing bundled ${prefix}`);
  }
  return receipt;
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const root = path.resolve(process.argv[2] || path.join(import.meta.dirname, '../../app/src/main/assets/pocket-ui'));
  const receipt = verifyBundle(root);
  console.log(`Verified exact Web UI bundle: ${receipt.files.length} resources`);
}
