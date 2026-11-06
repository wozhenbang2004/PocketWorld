import { createHash } from 'node:crypto';
import { mkdir, readFile, readdir, rm, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const output = path.join(root, 'public', 'licenses');
const packageBytes = await readFile(path.join(root, 'package.json'));
const lockBytes = await readFile(path.join(root, 'package-lock.json'));
const project = JSON.parse(packageBytes);
const lock = JSON.parse(lockBytes);
const explicitBuildPackages = process.argv.filter(value => value.startsWith('--include-build=')).flatMap(value => value.slice(16).split(',')).filter(Boolean);
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const supplementRoot = path.join(root, 'licenses');
const supplementBytes = await readFile(path.join(supplementRoot, 'upstream-supplements.json'));
const supplements = JSON.parse(supplementBytes);

async function exactSupplement(metadata) {
  const matches = supplements.entries.filter(entry => entry.package === metadata.name && entry.version === metadata.version && entry.declared_license === metadata.license);
  if (!matches.length) return null;
  if (matches.length !== 1) throw new Error(`Ambiguous license supplement: ${metadata.name}@${metadata.version}`);
  const entry = matches[0];
  const safeFile = relative => {
    const file = path.resolve(supplementRoot, relative);
    if (!file.startsWith(`${supplementRoot}${path.sep}`)) throw new Error('License supplement path escapes its source directory');
    return file;
  };
  const bytes = await readFile(safeFile(entry.file));
  const blob = createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex');
  if (bytes.length !== entry.bytes || hash(bytes) !== entry.sha256 || blob !== entry.git_blob_sha1) throw new Error(`Pinned license supplement identity mismatch: ${metadata.name}@${metadata.version}`);
  if (entry.version_evidence) {
    const proof = await readFile(safeFile(entry.version_evidence.file));
    const version = JSON.parse(proof);
    if (hash(proof) !== entry.version_evidence.sha256 || version.name !== metadata.name || version.version !== metadata.version || version.license !== metadata.license) throw new Error(`License supplement release evidence mismatch: ${metadata.name}@${metadata.version}`);
  }
  return { entry, bytes };
}

async function resolvePackage(from, name) {
  let directory = from;
  while (directory === root || directory.startsWith(`${root}${path.sep}`)) {
    const candidate = path.join(directory, 'node_modules', name);
    try {
      const metadata = JSON.parse(await readFile(path.join(candidate, 'package.json'), 'utf8'));
      return { directory: candidate, metadata };
    } catch (error) {
      if (error.code !== 'ENOENT' && error.code !== 'ENOTDIR') throw error;
    }
    directory = path.dirname(directory);
  }
  return null;
}

const legalName = /^(?:licen[cs]e|copying|notice|copyright)(?:[._ -].*)?$/i;
const legalDirectory = /^(?:licen[cs]es?|notices?|legal)$/i;
async function legalFiles(directory, relative = '', depth = 0) {
  const result = [];
  for (const entry of await readdir(path.join(directory, relative), { withFileTypes: true })) {
    const name = path.join(relative, entry.name);
    if (entry.isFile() && legalName.test(entry.name)) result.push(name);
    if (entry.isDirectory() && depth < 2 && (depth > 0 || legalDirectory.test(entry.name))) result.push(...await legalFiles(directory, name, depth + 1));
  }
  return result;
}

const queue = [];
for (const name of Object.keys(project.dependencies || {})) {
  if (!Object.hasOwn(project.optionalDependencies || {}, name)) queue.push({ name, from: root, required: true, reason: 'production dependency' });
}
for (const name of Object.keys(project.optionalDependencies || {})) queue.push({ name, from: root, required: false, reason: 'installed optional production dependency' });
for (const name of explicitBuildPackages) queue.push({ name, from: root, required: true, reason: 'explicit build-source license (source may be emitted in CSS/assets)' });

const visited = new Set();
const packages = [];
const missing = [];
const errors = [];
await mkdir(output, { recursive: true });
// A repeated build must not ship notices for packages left over from an older closure.
await rm(path.join(output, 'packages'), { recursive: true, force: true });
// Remove a historical generated source inventory before Vite copies public assets.
await rm(path.join(output, 'SOURCES.json'), { force: true });
while (queue.length) {
  const item = queue.shift();
  const found = await resolvePackage(item.from, item.name);
  if (!found) {
    if (item.required) errors.push({ package: item.name, from: path.relative(root, item.from), error: 'required package is not installed' });
    continue;
  }
  const relative = path.relative(root, found.directory).split(path.sep).join('/');
  if (visited.has(relative)) continue;
  visited.add(relative);
  const metadata = found.metadata;
  const pinned = lock.packages?.[relative];
  const sameVersion = pinned?.version === metadata.version;
  if (!pinned || !sameVersion) errors.push({ package: metadata.name || item.name, installation: relative, installedVersion: metadata.version, lockVersion: pinned?.version || null, error: 'installed package does not match package-lock' });
  const identifier = `${(metadata.name || item.name).replaceAll('/', '__')}@${metadata.version}-${hash(Buffer.from(relative)).slice(0, 8)}`;
  const fileRecords = [];
  for (const filename of await legalFiles(found.directory)) {
    const source = path.join(found.directory, filename);
    if ((await stat(source)).size > 2 * 1024 * 1024) {
      missing.push({ package: metadata.name || item.name, file: filename, reason: 'legal file exceeds 2 MiB inspection limit' });
      continue;
    }
    const bytes = await readFile(source);
    const destination = path.join(output, 'packages', identifier, filename);
    await mkdir(path.dirname(destination), { recursive: true });
    await writeFile(destination, bytes);
    fileRecords.push({ source: filename.split(path.sep).join('/'), file: path.relative(output, destination).split(path.sep).join('/'), bytes: bytes.length, sha256: hash(bytes) });
  }
  const declaredLicense = metadata.license || metadata.licenses || null;
  // Only a missing npm legal body may use an exact, independently retained upstream source.
  if (!fileRecords.length) {
    const supplement = await exactSupplement(metadata);
    if (supplement) {
      const destination = path.join(output, 'packages', identifier, 'UPSTREAM-LICENSE.txt');
      await mkdir(path.dirname(destination), { recursive: true });
      await writeFile(destination, supplement.bytes);
      fileRecords.push({ source: supplement.entry.file, origin: 'exact-version pinned upstream supplement; npm legal text absent', file: path.relative(output, destination).split(path.sep).join('/'), bytes: supplement.bytes.length, sha256: hash(supplement.bytes), source_url: supplement.entry.source_url, commit: supplement.entry.commit, copyright: supplement.entry.copyright, release_identity: supplement.entry.release_identity, version_evidence: supplement.entry.version_evidence || null });
    }
  }
  if (!declaredLicense) missing.push({ package: metadata.name || item.name, installation: relative, reason: 'package metadata has no declared license' });
  if (!fileRecords.length) missing.push({ package: metadata.name || item.name, installation: relative, reason: 'no LICENSE/COPYING/NOTICE/COPYRIGHT text found' });
  packages.push({
    name: metadata.name || item.name, version: metadata.version, installation: relative,
    reason: item.reason, declared_license: declaredLicense,
    package_json_sha256: hash(await readFile(path.join(found.directory, 'package.json'))),
    lock: pinned ? { version: pinned.version, resolved: pinned.resolved || null, integrity: pinned.integrity || null, version_matches: sameVersion } : null,
    repository: metadata.repository || null, homepage: metadata.homepage || null,
    legal_files: fileRecords,
  });
  // Build-source exceptions get their own notice only; dev tools they use are not shipped.
  if (item.reason.startsWith('explicit build-source')) continue;
  for (const name of Object.keys(metadata.dependencies || {})) {
    if (!Object.hasOwn(metadata.optionalDependencies || {}, name)) queue.push({ name, from: found.directory, required: true, reason: `production dependency of ${metadata.name || item.name}` });
  }
  for (const name of Object.keys(metadata.optionalDependencies || {})) queue.push({ name, from: found.directory, required: false, reason: `installed optional dependency of ${metadata.name || item.name}` });
  for (const name of Object.keys(metadata.peerDependencies || {})) queue.push({ name, from: found.directory, required: !metadata.peerDependenciesMeta?.[name]?.optional, reason: `installed peer dependency of ${metadata.name || item.name}` });
}

packages.sort((a, b) => a.name.localeCompare(b.name) || a.version.localeCompare(b.version));
const selectedSourceFiles = [];
for (const filename of [
  'THIRD-PARTY-NOTICES.md',
  'src/shell-library/upstream/beui-a866cb2/LICENSE',
  'src/forms-library/upstream/uiux/ncdai-wheel-fbe21d7/LICENSE',
  'src/forms-library/upstream/uiux/RAMOTION-LICENSE',
  'src/workspace-library/upstream/ovio/LICENSE',
  'src/workspace-library/upstream/stageshow/LICENSE',
]) {
  try {
    const bytes = await readFile(path.join(root, filename));
    const destination = filename === 'THIRD-PARTY-NOTICES.md' ? filename : 'source-notices/' + filename;
    await mkdir(path.dirname(path.join(output, destination)), { recursive: true });
    await writeFile(path.join(output, destination), bytes);
    selectedSourceFiles.push({ source: filename, file: destination, bytes: bytes.length, sha256: hash(bytes) });
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
    missing.push({ file: filename, reason: 'selected UI source attribution is not present' });
  }
}
const manifest = {
  schema_version: 1, generated_at: new Date().toISOString(), commit: process.env.GITHUB_SHA || null,
  package_lock_sha256: hash(lockBytes), package_json_sha256: hash(packageBytes),
  scope: 'Installed production dependency closure, including installed peers and optional dependencies. Development dependencies are excluded except explicitly named build-source notices.',
  explicit_build_source_packages: explicitBuildPackages,
  upstream_supplement_manifest: { file: 'upstream-supplements.json', sha256: hash(supplementBytes), policy: supplements.policy, unresolved_candidates: supplements.unresolved },
  selected_source_attribution: selectedSourceFiles,
  status: errors.length ? 'failed' : missing.length ? 'needs-license-review' : 'complete-in-scope',
  packages, missing, errors,
};
await writeFile(path.join(output, 'upstream-supplements.json'), supplementBytes);
await writeFile(path.join(output, 'dependencies.json'), `${JSON.stringify(manifest, null, 2)}\n`);
await writeFile(path.join(output, 'README.txt'), [
  'PocketWorld Web UI third-party dependency notices',
  'Exact legal texts are retained under packages/. Package versions, source URLs, lock integrity, and file hashes are recorded in dependencies.json.',
  'SIL OFL font notices are retained separately beside the pinned full font files in fonts/.',
  'Selected UI source-component attribution is copied here as THIRD-PARTY-NOTICES.md.',
  `Status: ${manifest.status}. Missing license declarations/texts are explicitly recorded; this inventory does not invent a license.`,
  '',
].join('\n'));
console.log(JSON.stringify({ status: manifest.status, packages: packages.length, legalFiles: packages.reduce((count, entry) => count + entry.legal_files.length, 0), missing: missing.length, errors: errors.length }));
if (errors.length || (process.argv.includes('--strict') && missing.length)) process.exitCode = 1;
