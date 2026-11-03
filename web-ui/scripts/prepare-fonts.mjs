import { createHash } from 'node:crypto';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Full, unmodified official font files.
// The exact commit and Git blob identities were read from google/fonts on 2026-11-02.
const commit = '6c1dbe27dcf7d817d4b62cec033ba77fa8e2a5ec';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const output = path.join(root, 'public', 'fonts');
const entries = [
  { family: 'Inter', file: 'Inter-variable.ttf', source: 'ofl/inter/Inter[opsz,wght].ttf', bytes: 876576,
    blob: '047c92f6e2212473dc436020afed689527076d44', glyphs: 'PocketWorld 0123456789' },
  { family: 'Noto Sans SC', file: 'NotoSansSC-variable.ttf', source: 'ofl/notosanssc/NotoSansSC[wght].ttf', bytes: 17772300,
    blob: 'fb0637bafbcd804fe32152370a1225990745b4bc', glyphs: '主工作模型任务审批记忆文件设备广州繁體廣東龍龜' },
  { family: 'JetBrains Mono', file: 'JetBrainsMono-variable.ttf', source: 'ofl/jetbrainsmono/JetBrainsMono[wght].ttf', bytes: 187208,
    blob: 'aa310be8b717fe3774f9444dd89d5f4101cc6d10', glyphs: 'const path = ./src 0123456789 {}[]()' },
  { family: 'Inter', file: 'Inter-OFL.txt', source: 'ofl/inter/OFL.txt', bytes: 4377,
    blob: '21f6aff961064c2e429f570995e446bcdd555422' },
  { family: 'Noto Sans SC', file: 'NotoSansSC-OFL.txt', source: 'ofl/notosanssc/OFL.txt', bytes: 4388,
    blob: '1c9f43281b8f216c5461fe9ac729afbade7724e4' },
  { family: 'JetBrains Mono', file: 'JetBrainsMono-OFL.txt', source: 'ofl/jetbrainsmono/OFL.txt', bytes: 4399,
    blob: '821a3dac22aff15a1f1c9689a1d79c45bb58ca39' },
];

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
  if (bytes.length !== entry.bytes || gitBlob(bytes) !== entry.blob) throw new Error(`Pinned font identity mismatch: ${entry.file}`);
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
  return `https://raw.githubusercontent.com/google/fonts/${commit}/${entry.source.split('/').map(encodeURIComponent).join('/')}`;
}

async function prepare(entry) {
  const destination = path.join(output, entry.file);
  let bytes;
  try {
    bytes = await readFile(destination);
    verify(entry, bytes);
  } catch {
    const response = await fetch(url(entry), { signal: AbortSignal.timeout(180000) });
    if (!response.ok) throw new Error(`Font download failed (${response.status}): ${entry.file}`);
    bytes = Buffer.from(await response.arrayBuffer());
    verify(entry, bytes);
    const temporary = `${destination}.download`;
    await writeFile(temporary, bytes);
    await rename(temporary, destination);
  }
  return {
    family: entry.family, file: entry.file, source: entry.source,
    bytes: bytes.length, git_blob_sha1: entry.blob,
    sha256: createHash('sha256').update(bytes).digest('hex'),
    required_glyphs_verified: entry.glyphs || null,
  };
}

const filenames = new Set();
for (const entry of entries) {
  if (filenames.has(entry.file) || !/^[0-9a-f]{40}$/.test(entry.blob) || !Number.isInteger(entry.bytes)) throw new Error('Invalid font pin configuration');
  filenames.add(entry.file);
}

if (process.argv.includes('--check-config')) {
  console.log(JSON.stringify({ repository: 'google/fonts', commit, files: entries.length, mode: 'configuration-only; no font download or glyph validation performed' }));
} else {
  await mkdir(output, { recursive: true });
  const files = await Promise.all(entries.map(prepare));
  const receipt = {
    repository: 'https://github.com/google/fonts', commit, license: 'SIL Open Font License 1.1',
    scope: 'Unmodified full variable font files published at the pinned paths; not catalogue specimen subsets. Glyph samples validate UI coverage, not every Unicode character.',
    files,
  };
  await writeFile(path.join(output, 'sources.json'), `${JSON.stringify(receipt, null, 2)}\n`);
  console.log(JSON.stringify({ fonts: files.filter(file => file.file.endsWith('.ttf')).length, licenses: files.filter(file => file.file.endsWith('.txt')).length, commit, output }));
}
