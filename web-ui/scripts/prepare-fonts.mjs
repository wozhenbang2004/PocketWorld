import { createHash } from 'node:crypto';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const output = path.join(root, 'public', 'fonts');
// Complete, unmodified official files. Pin updates are explicit source changes;
// catalogue specimens must never be substituted for these production fonts.
const sources = JSON.parse(await readFile(new URL('./font-sources.json', import.meta.url), 'utf8'));
const entries = sources.files;

function gitBlob(bytes) {
  return createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex');
}

function cmapHas(bytes, codepoint) {
  const tableCount = bytes.readUInt16BE(4);
  let cmap = -1;
  for (let i = 0; i < tableCount; i++) {
    const offset = 12 + i * 16;
    if (bytes.toString('ascii', offset, offset + 4) === 'cmap') cmap = bytes.readUInt32BE(offset + 8);
  }
  if (cmap < 0) throw new Error('Font has no cmap');
  const count = bytes.readUInt16BE(cmap + 2);
  for (let i = 0; i < count; i++) {
    const record = cmap + 4 + i * 8;
    const platform = bytes.readUInt16BE(record);
    if (platform !== 0 && platform !== 3) continue;
    const sub = cmap + bytes.readUInt32BE(record + 4);
    const format = bytes.readUInt16BE(sub);
    if (format === 12) {
      const groups = bytes.readUInt32BE(sub + 12);
      for (let n = 0; n < groups; n++) {
        const group = sub + 16 + n * 12;
        const start = bytes.readUInt32BE(group);
        const end = bytes.readUInt32BE(group + 4);
        if (codepoint >= start && codepoint <= end && bytes.readUInt32BE(group + 8) + codepoint - start !== 0) return true;
      }
    } else if (format === 4 && codepoint <= 0xffff) {
      const segments = bytes.readUInt16BE(sub + 6) / 2;
      for (let n = 0; n < segments; n++) {
        const end = bytes.readUInt16BE(sub + 14 + n * 2);
        const start = bytes.readUInt16BE(sub + 16 + segments * 2 + n * 2);
        if (codepoint < start || codepoint > end) continue;
        const delta = bytes.readInt16BE(sub + 16 + segments * 4 + n * 2);
        const rangePosition = sub + 16 + segments * 6 + n * 2;
        const range = bytes.readUInt16BE(rangePosition);
        let glyph = range === 0 ? (codepoint + delta) & 0xffff : bytes.readUInt16BE(rangePosition + range + (codepoint - start) * 2);
        if (range !== 0 && glyph !== 0) glyph = (glyph + delta) & 0xffff;
        if (glyph !== 0) return true;
      }
    }
  }
  return false;
}

function verify(entry, bytes) {
  if (bytes.length !== entry.bytes || gitBlob(bytes) !== entry.blob || createHash('sha256').update(bytes).digest('hex') !== entry.sha256) throw new Error(`Pinned font identity mismatch: ${entry.file}`);
  if (entry.glyphs) {
    const sfnt = bytes.readUInt32BE(0);
    if (sfnt !== 0x00010000 && bytes.toString('ascii', 0, 4) !== 'OTTO') throw new Error(`Not an SFNT font: ${entry.file}`);
    const missing = [...new Set(entry.glyphs)].filter(character => !cmapHas(bytes, character.codePointAt(0)));
    if (missing.length) throw new Error(`Missing required glyphs in ${entry.file}: ${missing.join('')}`);
  } else if (!bytes.toString('utf8').includes('SIL OPEN FONT LICENSE')) {
    throw new Error(`Missing OFL text: ${entry.file}`);
  }
}

function url(entry) {
  return `https://raw.githubusercontent.com/${entry.repository}/${entry.commit}/${entry.source.split('/').map(encodeURIComponent).join('/')}`;
}

async function prepare(entry) {
  const destination = path.join(output, entry.file);
  let bytes;
  try {
    bytes = await readFile(destination);
    verify(entry, bytes);
  } catch (error) {
    if (process.argv.includes('--verify-only')) throw new Error(`Bundled font unavailable or invalid: ${entry.file}`, { cause: error });
    const response = await fetch(url(entry), { signal: AbortSignal.timeout(180000) });
    if (!response.ok) throw new Error(`Font download failed (${response.status}): ${entry.file}`);
    bytes = Buffer.from(await response.arrayBuffer());
    verify(entry, bytes);
    const temporary = `${destination}.download`;
    await writeFile(temporary, bytes);
    await rename(temporary, destination);
  }
  return {
    id: entry.id, family: entry.family, file: entry.file, source: entry.source,
    repository: entry.repository, commit: entry.commit, weight: entry.weight || null,
    bytes: bytes.length, git_blob_sha1: entry.blob,
    sha256: createHash('sha256').update(bytes).digest('hex'),
    required_glyphs_verified: entry.glyphs || null,
  };
}

const filenames = new Set();
const families = new Map(sources.families.map(family => [family.id, family]));
if (sources.format !== 'pocketworld-font-sources/v1' || families.size !== 18 || sources.families.length !== 18) throw new Error('Invalid production font family configuration');
for (const entry of entries) {
  if (!families.has(entry.id) || families.get(entry.id).family !== entry.family ||
      filenames.has(entry.file) || !/^[A-Za-z0-9.-]+$/.test(entry.file) ||
      !['google/fonts', 'lxgw/LxgwWenKai'].includes(entry.repository) ||
      !/^[0-9a-f]{40}$/.test(entry.commit) || !/^[0-9a-f]{40}$/.test(entry.blob) ||
      !/^[0-9a-f]{64}$/.test(entry.sha256) || !Number.isInteger(entry.bytes) || entry.bytes <= 0 ||
      typeof entry.source !== 'string' || entry.source.startsWith('/') || entry.source.split('/').some(part => !part || part === '..' || part === '.')) throw new Error('Invalid font pin configuration');
  filenames.add(entry.file);
}
for (const id of families.keys()) {
  if (!entries.some(entry => entry.id === id && entry.glyphs) || !entries.some(entry => entry.id === id && !entry.glyphs && entry.file.endsWith('-OFL.txt'))) throw new Error(`Incomplete font or license: ${id}`);
}

if (process.argv.includes('--check-config')) {
  console.log(JSON.stringify({ families: families.size, files: entries.length, mode: 'configuration-only; no font download or glyph validation performed' }));
} else {
  await mkdir(output, { recursive: true });
  const files = new Array(entries.length);
  let next = 0;
  // Bound concurrent downloads and decoded font buffers for the full CJK families.
  await Promise.all(Array.from({ length: Math.min(4, entries.length) }, async () => {
    for (;;) {
      const index = next++;
      if (index >= entries.length) return;
      files[index] = await prepare(entries[index]);
    }
  }));
  const receipt = {
    format: sources.format, license: sources.license,
    scope: sources.scope, families: sources.families,
    files,
  };
  await writeFile(path.join(output, 'sources.json'), `${JSON.stringify(receipt, null, 2)}\n`);
  console.log(JSON.stringify({ families: families.size, fonts: files.filter(file => file.file.endsWith('.ttf')).length, licenses: files.filter(file => file.file.endsWith('.txt')).length, font_bytes: files.filter(file => file.file.endsWith('.ttf')).reduce((sum, file) => sum + file.bytes, 0), output }));
}
