import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { copyFile, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';

const root = fileURLToPath(new URL('../', import.meta.url));
const hash = (bytes: Buffer, algorithm = 'sha256') => createHash(algorithm).update(bytes).digest('hex');

test('the unresolved client-only notice retains exact npm evidence without inventing an owner', async () => {
  const manifest = JSON.parse(await readFile(path.join(root, 'licenses/upstream-supplements.json'), 'utf8'));
  const unresolved = manifest.unresolved.find((entry: any) => entry.package === 'client-only' && entry.version === '0.0.1');
  assert.ok(unresolved);
  assert.equal(manifest.entries.some((entry: any) => entry.package === 'client-only'), false);
  const evidence = path.join(root, 'licenses/client-only-0.0.1/evidence');
  const distributionBytes = await readFile(path.join(evidence, 'distribution.json'));
  const registryBytes = await readFile(path.join(evidence, 'registry.json'));
  const packageBytes = await readFile(path.join(evidence, 'package.json'));
  assert.equal(hash(distributionBytes), unresolved.distribution_evidence.sha256);
  assert.equal(hash(registryBytes), unresolved.registry_evidence.sha256);
  assert.equal(hash(packageBytes), unresolved.version_evidence.sha256);
  const inspected = JSON.parse(distributionBytes.toString());
  const registry = JSON.parse(registryBytes.toString());
  const metadata = JSON.parse(packageBytes.toString());
  const lock = JSON.parse(await readFile(path.join(root, 'package-lock.json'), 'utf8'));
  const archive = await readFile(path.join(evidence, 'client-only-0.0.1.tgz'));
  assert.equal(archive.length, 481);
  assert.equal(hash(archive), inspected.archive.sha256);
  assert.equal(hash(archive, 'sha1'), registry.dist.shasum);
  const integrity = `sha512-${createHash('sha512').update(archive).digest('base64')}`;
  assert.equal(integrity, registry.dist.integrity);
  assert.equal(integrity, lock.packages['node_modules/client-only'].integrity);
  assert.equal(metadata.name, 'client-only'); assert.equal(metadata.version, '0.0.1');
  assert.equal(metadata.license, 'MIT');
  assert.equal(registry.gitHead, undefined); assert.equal(registry.repository, undefined);
  assert.equal(inspected.publisher, registry._npmUser.name);
  assert.equal(inspected.published_copyright_notice, null);
  assert.deepEqual(inspected.published_license_text_files, []);
  assert.equal(inspected.notice_status, 'unresolved');
  assert.deepEqual(inspected.files.map((file: any) => [file.path, file.bytes]), [['error.js', 144], ['index.js', 0], ['package.json', 467]]);
  for (const file of inspected.files) {
    const installed = await readFile(path.join(root, 'node_modules/client-only', file.path));
    assert.equal(installed.length, file.bytes); assert.equal(hash(installed), file.sha256);
  }
});

test('a declared MIT package with no legal body stays missing in normal and strict scans', async t => {
  const directory = await mkdtemp(path.join(process.env.TEMP || process.env.TMP || tmpdir(), 'pocketworld-license-test-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const metadata = JSON.parse(await readFile(path.join(root, 'node_modules/react/package.json'), 'utf8'));
  const lock = JSON.parse(await readFile(path.join(root, 'package-lock.json'), 'utf8'));
  const project = { name: 'license-collection-fixture', private: true, dependencies: { 'client-only': '0.0.1', react: metadata.version } };
  await mkdir(path.join(directory, 'scripts'));
  await mkdir(path.join(directory, 'licenses'));
  await copyFile(path.join(root, 'scripts/collect-licenses.mjs'), path.join(directory, 'scripts/collect-licenses.mjs'));
  await copyFile(path.join(root, 'licenses/upstream-supplements.json'), path.join(directory, 'licenses/upstream-supplements.json'));
  await writeFile(path.join(directory, 'package.json'), JSON.stringify(project));
  await writeFile(path.join(directory, 'package-lock.json'), JSON.stringify({ packages: {
    'node_modules/client-only': lock.packages['node_modules/client-only'], 'node_modules/react': lock.packages['node_modules/react'],
  } }));
  // A neighboring dependency's genuine MIT notice must never satisfy client-only.
  for (const [name, files] of [['client-only', ['package.json', 'index.js', 'error.js']], ['react', ['package.json', 'LICENSE']]] as const) {
    await mkdir(path.join(directory, 'node_modules', name), { recursive: true });
    for (const filename of files) await copyFile(path.join(root, 'node_modules', name, filename), path.join(directory, 'node_modules', name, filename));
  }
  for (const filename of ['THIRD-PARTY-NOTICES.md', 'src/shell-library/upstream/beui-a866cb2/LICENSE',
    'src/forms-library/upstream/uiux/ncdai-wheel-fbe21d7/LICENSE', 'src/forms-library/upstream/uiux/RAMOTION-LICENSE',
    'src/workspace-library/upstream/ovio/LICENSE', 'src/workspace-library/upstream/stageshow/LICENSE']) {
    await mkdir(path.dirname(path.join(directory, filename)), { recursive: true });
    await copyFile(path.join(root, filename), path.join(directory, filename));
  }
  for (const strict of [false, true]) {
    const result = spawnSync(process.execPath, [path.join(directory, 'scripts/collect-licenses.mjs'), ...(strict ? ['--strict'] : [])], { cwd: directory, encoding: 'utf8' });
    assert.equal(result.status, strict ? 1 : 0, result.stderr);
    const report = JSON.parse(await readFile(path.join(directory, 'public/licenses/dependencies.json'), 'utf8'));
    assert.equal(report.status, 'needs-license-review');
    assert.equal(report.packages.length, 2);
    assert.deepEqual(report.errors, []);
    assert.deepEqual(report.missing, [{ package: 'client-only', installation: 'node_modules/client-only', reason: 'no LICENSE/COPYING/NOTICE/COPYRIGHT text found' }]);
    const client = report.packages.find((entry: any) => entry.name === 'client-only');
    assert.equal(client.declared_license, 'MIT'); assert.deepEqual(client.legal_files, []);
  }
});
