/** Real production Web UI; the native SQL/package/renderer transport is explicitly synthetic. */
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import http from 'node:http';
import { createHash } from 'node:crypto';
import { chromium } from '@playwright/test';
import { installProjectPreviewHost } from './project-preview-host-fixture.mjs';

const argument = name => { const at = process.argv.indexOf(name), value = process.argv[at + 1]; if (at < 0 || !value || value.startsWith('--')) throw new Error('Required --bundle <path> --output <path>'); return path.resolve(value); };
const bundle = argument('--bundle'), output = argument('--output');
const only = process.argv.includes('--case') ? process.argv[process.argv.indexOf('--case') + 1] : null;
await fs.mkdir(output, { recursive: true });
const index = await fs.readFile(path.join(bundle, 'index.html'), 'utf8'), entry = index.match(/assets\/(index-[^" ]+\.js)/)?.[1];
assert.ok(entry, 'Use the actual production bundle');
const result = { scope: 'Production Web controls at 360x800/800x360 with synthetic native package/SQL/draft receipts. The gray viewport is not a mock Android renderer. No Android, real package provenance, source/CAS or model-dispatch claim.', startedAt: new Date().toISOString(),
  bundle: { directory: bundle, entry, sha256: createHash('sha256').update(await fs.readFile(path.join(bundle, 'assets', entry))).digest('hex') }, checks: [], screenshots: [], errors: [] };
const server = http.createServer(async (request, response) => {
  try {
    const pathname = new URL(request.url, 'http://fixture').pathname, file = path.resolve(bundle, '.' + (pathname === '/' ? '/index.html' : pathname));
    if (!file.startsWith(bundle + path.sep)) throw new Error('Invalid asset');
    const bytes = await fs.readFile(file); response.writeHead(200, { 'content-type': ({ '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.woff2': 'font/woff2', '.json': 'application/json', '.svg': 'image/svg+xml' })[path.extname(file)] || 'application/octet-stream' }); response.end(bytes);
  } catch { response.writeHead(404); response.end(); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const origin = 'http://127.0.0.1:' + server.address().port;
const browser = await chromium.launch({ headless: true, args: ['--disable-background-networking'] });
let page;
const state = () => page.evaluate(() => window.previewFixture.snapshot());
const calls = () => page.evaluate(() => window.previewFixture.calls);
const count = action => page.evaluate(action => window.previewFixture.calls.filter(row => row.action === action).length, action);
async function stable() {
  await page.waitForFunction(() => {
    const nodes = [...document.querySelectorAll('.pw-preview-pane,.pw-preview-viewport,.pw-preview-footer,.pw-preview-entry,.pw-composer-region')];
    const key = JSON.stringify(nodes.map(node => { const r = node.getBoundingClientRect(); return [r.x, r.y, r.width, r.height, node.scrollTop]; }));
    const previous = window.__packageLayout; window.__packageLayout = { key, count: previous?.key === key ? previous.count + 1 : 0 }; return window.__packageLayout.count >= 3;
  }, null, { polling: 'raf' });
}
async function shot(name) { await stable(); await page.screenshot({ path: path.join(output, name + '.png') }); result.screenshots.push(name + '.png'); }
async function navigate(name) {
  await page.getByRole('button', { name: '打开导航', exact: true }).click();
  const drawer = page.locator('.pw-work-drawer'); await drawer.getByRole('link', { name, exact: true }).click(); await drawer.waitFor({ state: 'hidden' });
}
async function taskHistory() {
  await navigate('任务'); await page.locator('.pw-task-row[data-task-id="original-project-task"]').click();
  const drawer = page.locator('.pw-task-drawer'); await drawer.waitFor();
  await drawer.getByRole('button', { name: '过程与范围', exact: true }).click();
  await drawer.getByRole('button', { name: '查看执行过程', exact: true }).click();
  await page.waitForFunction(() => document.querySelector('.pw-task-drawer')?.dataset.taskLayer === 'process');
}
async function inspect(which = 0) {
  await taskHistory(); await page.getByRole('button', { name: '查看这次任务的固定包', exact: true }).nth(which).click();
  await page.locator('.pw-task-drawer').waitFor({ state: 'hidden' });
  await page.getByRole('region', { name: '固定包入口', exact: true }).waitFor();
}
async function selectEntry(entryPath) {
  const field = page.locator('.pw-selection-field').filter({ has: page.getByText('固定包中的 HTML 入口', { exact: true }) });
  const trigger = field.getByRole('button'); await trigger.scrollIntoViewIfNeeded(); await stable(); await trigger.click();
  await page.getByRole('option', { name: entryPath, exact: true }).click();
  await page.locator('[role="listbox"]').waitFor({ state: 'detached' });
}
async function open() {
  await page.getByRole('button', { name: '打开固定预览', exact: true }).click();
  await page.waitForFunction(() => !!window.previewFixture.snapshot().state.projectPreview?.packageOperationId);
  await page.evaluate(() => window.previewFixture.ready());
  await page.getByRole('button', { name: '选择元素', exact: true }).waitFor();
  await page.waitForFunction(() => window.previewFixture.calls.some(row => row.action === 'projects.preview.viewport' && row.args.visible));
}
async function capture() {
  await page.getByRole('button', { name: '选择元素', exact: true }).click();
  await page.getByRole('button', { name: '退出选择', exact: true }).waitFor();
  await page.evaluate(() => { window.previewFixture.beginCapture(); window.previewFixture.finishCapture(); });
  await page.getByText(/已加入 \d 处元素引用，尚未发送/).waitFor();
}
async function check(name, work) {
  if (only && only !== name) return;
  const context = await browser.newContext({ viewport: { width: 360, height: 800 }, deviceScaleFactor: 1, locale: 'zh-CN', reducedMotion: 'reduce', serviceWorkers: 'block' });
  await context.route('**/*', route => new URL(route.request().url()).origin === origin ? route.continue() : route.abort());
  await context.addInitScript(installProjectPreviewHost, { packages: true }); page = await context.newPage(); page.setDefaultTimeout(8000);
  page.on('pageerror', error => result.errors.push({ name, error: error.message }));
  try {
    await page.goto(origin, { waitUntil: 'networkidle' }); await page.waitForFunction(() => document.documentElement.dataset.pocketUi === 'ready');
    await work(); assert.deepEqual(await page.evaluate(() => window.previewFixture.errors), []); result.checks.push({ name, passed: true });
  } catch (error) {
    result.checks.push({ name, passed: false, error: String(error.message || error), observed: await page.evaluate(() => ({
      calls: window.previewFixture.calls.map(row => ({ action: row.action, args: row.args })), route: document.querySelector('.pw-app')?.dataset.route,
      projectId: window.previewFixture.snapshot().state.projectId, view: window.previewFixture.snapshot().view, preview: window.previewFixture.snapshot().state.projectPreview,
    })) }); await shot(name + '-failure');
  }
  finally { await context.close(); }
}

try {
  await check('original-task-close-inspect-project-open-and-explicit-send', async () => {
    const before = await state(); await taskHistory(); await page.evaluate(() => window.previewFixture.holdClose());
    await page.getByRole('button', { name: '查看这次任务的固定包', exact: true }).first().click();
    await page.waitForFunction(() => window.previewFixture.calls.some(row => row.action === 'tasks.close'));
    await page.getByRole('progressbar', { name: '正在读取这次任务的固定包', exact: true }).waitFor();
    assert.equal(await count('projects.package.preview.inspect'), 0); assert.equal(await count('projects.select'), 0);
    await page.evaluate(() => window.previewFixture.releaseClose()); await page.getByRole('region', { name: '固定包入口', exact: true }).waitFor();
    await page.getByRole('progressbar', { name: '正在读取这次任务的固定包', exact: true }).waitFor({ state: 'hidden' });
    assert.equal((await state()).state.projectPreview, null); assert.equal((await state()).state.projectId, 'project-a');
    assert.equal(await page.getByRole('button', { name: '固定包中的 HTML 入口', exact: true }).count(), 0);
    await page.getByText('dist/home.html', { exact: true }).waitFor(); assert.equal(await count('projects.package.preview.open'), 0);
    await shot('declared-package-entry'); await open(); await capture();
    const after = await state(); assert.equal(after.composer.text, before.composer.text); assert.deepEqual(after.composer.sourceRefs, before.composer.sourceRefs); assert.deepEqual(after.composer.attachments, before.composer.attachments);
    assert.equal(after.composer.previewRefs[0].packageOperationId, await page.evaluate(() => window.previewFixture.packageOperation(0)));
    assert.equal(after.composer.previewRefs[0].sourceKind, 'fixed_workspace_project_artifact'); assert.equal(await count('composer.send'), 0);
    await shot('fixed-package-selected'); await page.getByRole('button', { name: '返回原对话', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('.pw-app').dataset.route === 'chat');
    await page.getByRole('button', { name: /元素引用 · 1/ }).click(); await page.getByRole('button', { name: '查看片段与来源', exact: true }).click();
    await page.getByText('原任务固定项目包', { exact: true }).waitFor(); await shot('fixed-reference-original-composer');
    await page.evaluate(() => window.previewFixture.clearText()); assert.equal(await page.getByRole('button', { name: '发送', exact: true }).isDisabled(), true);
    await page.getByRole('textbox', { name: '工作输入', exact: true }).fill('请沿这份原项目任务调整所选按钮。');
    await page.getByRole('button', { name: '发送', exact: true }).click(); await page.waitForFunction(() => window.previewFixture.calls.some(row => row.action === 'composer.send'));
    assert.equal(await count('composer.send'), 1); assert.equal(await count('tasks.start'), 0); assert.equal(await count('conversation.new'), 0);
    const order = (await calls()).map(row => row.action); assert.ok(order.indexOf('tasks.close') < order.indexOf('projects.package.preview.inspect')); assert.ok(order.indexOf('projects.package.preview.inspect') < order.indexOf('projects.select')); assert.ok(order.indexOf('projects.select') < order.indexOf('projects.package.preview.open'));
  });
  await check('source-package-explicit-choice-never-guesses-entry', async () => {
    await inspect(1); const button = page.getByRole('button', { name: '打开固定预览', exact: true }); assert.equal(await button.isDisabled(), true); assert.equal(await count('projects.package.preview.open'), 0);
    await selectEntry('demo/button.html'); await shot('actual-html-choice'); await open();
    const request = (await calls()).find(row => row.action === 'projects.package.preview.open'); assert.deepEqual(Object.keys(request.args).sort(), ['entryPath', 'operationId', 'taskId']); assert.equal(request.args.entryPath, 'demo/button.html');
    assert.equal((await state()).state.projectPreview.metadata.entryPath, 'demo/button.html');
  });
  await check('single-entry-still-explicit-and-no-html-recovers-original-task', async () => {
    await inspect(3); assert.equal(await page.getByRole('button', { name: '打开固定预览', exact: true }).isDisabled(), true); await selectEntry('single/real-entry.html'); assert.equal(await count('projects.package.preview.open'), 0);
    await page.getByRole('button', { name: '返回原任务', exact: true }).click(); await page.locator('.pw-task-drawer[data-task-layer="process"]').waitFor();
    await page.getByRole('button', { name: '查看这次任务的固定包', exact: true }).nth(2).click(); await page.getByText(/这份固定包没有可视 HTML 入口/).waitFor();
    assert.equal(await page.getByRole('button', { name: '打开固定预览', exact: true }).count(), 0); assert.equal((await state()).state.projectPreview, null); await shot('no-html-original-task-recoverable');
    await page.getByRole('button', { name: '返回原任务', exact: true }).click(); await page.locator('.pw-task-drawer[data-task-layer="process"]').waitFor(); assert.equal((await state()).composer.text, '请检查这个按钮，保留原来说明。');
  });
  await check('same-project-different-package-never-reuses-retained-session', async () => {
    await inspect(); await open(); await capture(); const first = (await state()).state.projectPreview;
    await page.getByRole('button', { name: '返回原对话', exact: true }).click(); await inspect(1); assert.equal(await page.getByRole('button', { name: '选择元素', exact: true }).count(), 0);
    await selectEntry('site/start.html'); await open(); const next = (await state()).state.projectPreview;
    assert.notEqual(next.sessionId, first.sessionId); assert.notEqual(next.packageOperationId, first.packageOperationId); assert.notEqual(next.metadata.artifactId, first.metadata.artifactId); assert.equal(await count('projects.package.preview.open'), 2); assert.equal(await count('projects.preview'), 0);
    assert.equal((await state()).composer.previewRefsAvailable, false);
  });
  await check('changed-source-keeps-history-visible-but-disables-selection-and-send', async () => {
    await inspect(); await open(); await capture(); const original = (await state()).state.projectPreview; await page.evaluate(() => window.previewFixture.changeSource());
    await page.getByText(/当前实际源码已变化/).waitFor(); assert.equal(await page.getByRole('button', { name: '选择元素', exact: true }).isDisabled(), true);
    assert.equal((await state()).state.projectPreview.sessionId, original.sessionId); await shot('historical-package-source-unavailable');
    await page.getByRole('button', { name: '返回原对话', exact: true }).click(); await page.getByRole('button', { name: '请重新选择预览元素', exact: true }).waitFor(); assert.equal(await page.getByRole('button', { name: '请重新选择预览元素', exact: true }).isDisabled(), true); assert.equal((await state()).composer.previewRefs.length, 1); assert.equal(await count('composer.send'), 0);
  });
  await check('late-inspect-cannot-select-project-or-reopen-pane-after-navigation', async () => {
    await taskHistory(); await page.evaluate(() => window.previewFixture.holdInspect()); await page.getByRole('button', { name: '查看这次任务的固定包', exact: true }).first().click();
    await page.waitForFunction(() => window.previewFixture.calls.some(row => row.action === 'projects.package.preview.inspect')); await page.locator('.pw-task-drawer').waitFor({ state: 'hidden' }); await navigate('对话');
    await page.evaluate(() => window.previewFixture.releaseInspect()); await stable(); assert.equal(await page.locator('.pw-app').getAttribute('data-route'), 'chat'); assert.equal(await count('projects.select'), 0); assert.equal(await count('projects.package.preview.open'), 0); assert.equal((await state()).state.projectId, 'other-project');
  });
  await check('wrong-inspect-tuple-never-changes-project', async () => {
    await taskHistory(); await page.evaluate(() => window.previewFixture.holdInspect()); await page.getByRole('button', { name: '查看这次任务的固定包', exact: true }).first().click();
    await page.waitForFunction(() => window.previewFixture.calls.some(row => row.action === 'projects.package.preview.inspect')); await page.evaluate(() => window.previewFixture.releaseInspect(true));
    await page.getByText('固定包描述与原任务操作不符，未切换到另一份成果。', { exact: true }).waitFor(); assert.equal(await count('projects.select'), 0); assert.equal((await state()).state.projectPreview, null);
  });
  await check('wrong-open-tuple-is-not-adopted-as-current-session', async () => {
    await inspect(); await page.evaluate(() => window.previewFixture.holdOpen()); await page.getByRole('button', { name: '打开固定预览', exact: true }).click();
    await page.waitForFunction(() => window.previewFixture.calls.some(row => row.action === 'projects.package.preview.open')); await page.evaluate(() => window.previewFixture.releaseOpen(true));
    await page.getByText('固定包预览回执与所选原操作或入口不符，未打开另一版本。', { exact: true }).waitFor(); assert.equal(await page.getByRole('button', { name: '选择元素', exact: true }).count(), 0); assert.equal(await count('projects.preview.mode'), 0);
  });
  await check('retired-package-does-not-fall-back-to-active-files', async () => {
    await inspect(); await page.evaluate(() => window.previewFixture.retirePackage()); await page.getByRole('button', { name: '打开固定预览', exact: true }).click();
    await page.getByText('原固定包入口不可读取，未改用当前文件。', { exact: true }).waitFor(); assert.equal(await count('projects.preview'), 0); assert.equal((await state()).state.projectPreview, null); assert.equal((await state()).composer.text, '请检查这个按钮，保留原来说明。'); await shot('retired-package-preserves-choice-and-draft');
  });
  await check('short-landscape-keeps-package-choices-and-renderer-controls-reachable', async () => {
    await page.setViewportSize({ width: 800, height: 360 }); await inspect(1); await selectEntry('demo/button.html'); await open(); await stable();
    const geometry = await page.evaluate(() => { const box = selector => { const r = document.querySelector(selector).getBoundingClientRect(); return { y: r.y, bottom: r.bottom, height: r.height }; }; return { viewport: box('.pw-preview-viewport'), actions: box('.pw-preview-actions'), footer: box('.pw-preview-footer'), height: innerHeight }; });
    assert.ok(geometry.viewport.y >= geometry.actions.bottom); assert.ok(geometry.viewport.bottom <= geometry.footer.y + 1); assert.ok(geometry.footer.bottom <= geometry.height); assert.ok(geometry.viewport.height >= 48); result.geometry = geometry; await shot('fixed-package-short-landscape');
  });
} finally {
  result.finishedAt = new Date().toISOString(); result.passed = result.checks.every(check => check.passed) && result.errors.length === 0;
  await fs.writeFile(path.join(output, 'result.json'), JSON.stringify(result, null, 2) + '\n'); await browser.close(); await new Promise(resolve => server.close(resolve));
}
console.log(JSON.stringify({ passed: result.passed, checks: result.checks, bundle: result.bundle, output }));
if (!result.passed) process.exitCode = 1;
