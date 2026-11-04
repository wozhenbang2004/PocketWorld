/** Actual production Web bundle; synthetic native state/receipts, no Android DOM capture. */
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import http from 'node:http';
import { createHash } from 'node:crypto';
import { chromium } from '@playwright/test';
import { installProjectPreviewHost } from './project-preview-host-fixture.mjs';

const argument = name => { const index = process.argv.indexOf(name), value = process.argv[index + 1]; if (index < 0 || !value || value.startsWith('--')) throw new Error('Required --bundle <path> --output <path>'); return path.resolve(value); };
const bundle = argument('--bundle'), output = argument('--output');
const selectedCase = process.argv.includes('--case') ? process.argv[process.argv.indexOf('--case') + 1] : null;
await fs.mkdir(output, { recursive: true });
const index = await fs.readFile(path.join(bundle, 'index.html'), 'utf8'), entry = index.match(/assets\/(index-[^" ]+\.js)/)?.[1];
assert.ok(entry, 'Only an actual production bundle is accepted');
const result = { scope: 'Mounted production Web UI at 360x800 and 800x360. Native transport, capture, source checks and CAS are synthetic. Does not verify Android renderer/touch isolation, real source currentness, SQL, model dispatch or patch CAS.',
  startedAt: new Date().toISOString(), bundle: { directory: bundle, entry, sha256: createHash('sha256').update(await fs.readFile(path.join(bundle, 'assets', entry))).digest('hex') }, checks: [], screenshots: [], errors: [] };
const server = http.createServer(async (request, response) => {
  try {
    const pathname = new URL(request.url, 'http://fixture').pathname;
    const file = path.resolve(bundle, '.' + (pathname === '/' ? '/index.html' : pathname));
    if (!file.startsWith(bundle + path.sep)) throw new Error('Invalid asset');
    const bytes = await fs.readFile(file); response.writeHead(200, { 'content-type': ({ '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.woff2': 'font/woff2', '.json': 'application/json', '.svg': 'image/svg+xml' })[path.extname(file)] || 'application/octet-stream' }); response.end(bytes);
  } catch { response.writeHead(404); response.end(); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const origin = 'http://127.0.0.1:' + server.address().port;
const browser = await chromium.launch({ headless: true, args: ['--disable-background-networking'] });
let page;
async function stable() {
  await page.waitForFunction(() => {
    const nodes = [...document.querySelectorAll('.pw-app-header,.pw-preview-pane,.pw-preview-viewport,.pw-preview-footer,.pw-composer-region')];
    const key = JSON.stringify(nodes.map(node => { const r = node.getBoundingClientRect(); return [r.x,r.y,r.width,r.height,node.scrollTop]; }));
    const previous = window.__previewLayout; window.__previewLayout = { key, count: previous?.key === key ? previous.count + 1 : 0 }; return window.__previewLayout.count >= 3;
  }, null, { polling: 'raf' });
}
async function shot(name) { await stable(); await page.screenshot({ path: path.join(output, name + '.png') }); result.screenshots.push(name + '.png'); }
async function navigate(name) {
  await page.getByRole('button', { name: '打开导航', exact: true }).click();
  const drawer = page.locator('.pw-work-drawer'); await drawer.getByRole('link', { name, exact: true }).click(); await drawer.waitFor({ state: 'hidden' });
}
async function openPreview() {
  if (await page.locator('.pw-app').getAttribute('data-route') !== 'projects') await navigate('项目');
  await page.getByRole('button', { name: '预览项目', exact: true }).click();
  await page.getByRole('region', { name: '项目预览', exact: true }).waitFor();
  await page.waitForFunction(() => !!window.previewFixture.snapshot().state.projectPreview);
  await page.evaluate(() => window.previewFixture.ready());
  await page.getByRole('button', { name: '选择元素', exact: true }).waitFor();
  await page.waitForFunction(() => window.previewFixture.calls.some(row => row.action === 'projects.preview.viewport' && row.args.visible));
}
async function chooseElement() {
  await page.getByRole('button', { name: '选择元素', exact: true }).click();
  await page.getByRole('button', { name: '退出选择', exact: true }).waitFor();
  await page.evaluate(() => { window.previewFixture.beginCapture(); window.previewFixture.finishCapture(); });
  await page.getByText(/已加入 \d 处元素引用，尚未发送/).waitFor();
}
const state = () => page.evaluate(() => window.previewFixture.snapshot());
const count = action => page.evaluate(action => window.previewFixture.calls.filter(row => row.action === action).length, action);
const passed = name => result.checks.push({ name, passed: true });
async function check(name, work) {
  if (selectedCase && selectedCase !== name) return;
  const context = await browser.newContext({ viewport: { width: 360, height: 800 }, deviceScaleFactor: 1, locale: 'zh-CN', reducedMotion: 'reduce', serviceWorkers: 'block' });
  await context.route('**/*', route => new URL(route.request().url()).origin === origin ? route.continue() : route.abort());
  await context.addInitScript(installProjectPreviewHost); page = await context.newPage(); page.setDefaultTimeout(8000);
  page.on('pageerror', error => result.errors.push({ name, error: error.message }));
  try {
    await page.goto(origin, { waitUntil: 'networkidle' }); await page.waitForFunction(() => document.documentElement.dataset.pocketUi === 'ready');
    await work(); assert.deepEqual(await page.evaluate(() => window.previewFixture.errors), []); passed(name);
  } catch (error) { result.checks.push({ name, passed: false, error: String(error.message || error) }); await shot(name + '-failure'); }
  finally { await context.close(); }
}

try {
  await check('open-bounded-viewport-selection-keeps-existing-inputs', async () => {
    const before = await state(); await openPreview(); await stable();
    const geometry = await page.evaluate(() => {
      const box = selector => { const r = document.querySelector(selector).getBoundingClientRect(); return { x:r.x,y:r.y,width:r.width,height:r.height,bottom:r.bottom }; };
      return { viewport: box('.pw-preview-viewport'), actions: box('.pw-preview-actions'), footer: box('.pw-preview-footer'), header: box('.pw-app-header'), width: innerWidth, height: innerHeight,
        published: window.previewFixture.calls.filter(row => row.action === 'projects.preview.viewport' && row.args.visible).at(-1).args };
    });
    assert.ok(geometry.viewport.y >= geometry.actions.bottom && geometry.viewport.bottom <= geometry.footer.y + 1);
    assert.ok(geometry.footer.bottom <= geometry.height && geometry.viewport.width > 200 && geometry.viewport.height > 150);
    assert.deepEqual(geometry.published.rect, { x: geometry.viewport.x, y: geometry.viewport.y, width: geometry.viewport.width, height: geometry.viewport.height });
    result.geometry = geometry; await chooseElement();
    const after = await state(); assert.equal(after.composer.text, before.composer.text); assert.deepEqual(after.composer.sourceRefs, before.composer.sourceRefs);
    assert.deepEqual(after.state.selectedChatAttachments, before.state.selectedChatAttachments); assert.equal(after.composer.previewRefs.length, 1); assert.equal(await count('composer.send'), 0);
    await shot('selected-elements-project-pane');
  });
  await check('return-receipt-waits-for-original-draft-and-retains-document', async () => {
    await openPreview(); await chooseElement(); const original = await state();
    await page.evaluate(() => { window.previewFixture.otherDraft(); window.previewFixture.holdReturn(); });
    await page.getByRole('button', { name: '返回原对话', exact: true }).click();
    await page.waitForFunction(() => window.previewFixture.calls.some(row => row.action === 'projects.preview.return'));
    assert.equal(await page.locator('.pw-app').getAttribute('data-route'), 'projects');
    await page.evaluate(() => window.previewFixture.releaseReturn());
    await page.waitForFunction(() => document.querySelector('.pw-app').dataset.route === 'chat');
    assert.equal(await page.getByRole('textbox', { name: '工作输入', exact: true }).inputValue(), original.composer.text);
    const after = await state(); assert.deepEqual(after.composer.sourceRefs, original.composer.sourceRefs); assert.deepEqual(after.composer.previewRefs, original.composer.previewRefs);
    assert.deepEqual(after.state.selectedChatAttachments, original.state.selectedChatAttachments);
    assert.equal(after.state.projectPreview.sessionId, original.state.projectPreview.sessionId); assert.equal(after.state.projectPreview.documentGeneration, original.state.projectPreview.documentGeneration);
    assert.equal(after.state.projectPreview.visible, false); assert.equal(after.state.projectPreview.selecting, false);
    assert.equal(await count('composer.send'), 0); assert.equal(await count('conversation.select'), 0);
    await page.getByRole('button', { name: /^元素引用 · 1/ }).click();
    await page.getByRole('button', { name: '查看片段与来源', exact: true }).click();
    assert.equal(await page.locator('.pw-preview-references img').count(), 0); assert.equal(await page.evaluate(() => window.untrustedExecuted), undefined);
    await page.getByText(/定位提示不是源码行号/).waitFor(); await shot('composer-quotes-attachments-preview-coexist');
    await navigate('项目'); await page.getByRole('button', { name: '预览项目', exact: true }).click();
    await page.getByRole('region', { name: '项目预览', exact: true }).waitFor(); assert.equal(await count('projects.preview'), 1, 'Show the retained Document without reopening it');
  });
  await check('stale-selection-feedback-preserves-new-draft', async () => {
    await openPreview(); await page.getByRole('button', { name: '选择元素', exact: true }).click();
    await page.getByRole('button', { name: '退出选择', exact: true }).waitFor();
    await page.evaluate(() => { window.previewFixture.beginCapture(); window.previewFixture.changeDraft(); window.previewFixture.finishCapture(); });
    await page.getByText('原草稿已变化，本次元素选择未加入；请重新选择。', { exact: true }).waitFor();
    assert.equal((await state()).composer.previewRefs.length, 0);
    await page.getByRole('button', { name: '返回原对话', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('.pw-app').dataset.route === 'chat');
    assert.equal(await page.getByRole('textbox', { name: '工作输入', exact: true }).inputValue(), '这份新草稿必须保留。');
  });
  await check('reload-invalidates-reference-remove-preserves-quote-and-attachment', async () => {
    await openPreview(); await chooseElement(); const original = await state();
    await page.getByRole('button', { name: '重新载入预览', exact: true }).click();
    await page.waitForFunction(() => window.previewFixture.snapshot().composer.previewRefsAvailable === false);
    await page.evaluate(() => window.previewFixture.ready());
    await page.getByRole('button', { name: '返回原对话', exact: true }).click(); await page.waitForFunction(() => document.querySelector('.pw-app').dataset.route === 'chat');
    assert.equal(await page.getByRole('button', { name: '请重新选择预览元素', exact: true }).isDisabled(), true);
    await page.getByText(/原预览已变化或不可用，请重新选择/).waitFor(); await shot('reloaded-reference-unavailable');
    await page.getByRole('button', { name: /^元素引用 · 1/ }).click(); await page.getByRole('button', { name: '移除元素引用 1', exact: true }).click();
    await page.waitForFunction(() => window.previewFixture.snapshot().composer.previewRefs.length === 0);
    const after = await state(); assert.deepEqual(after.composer.sourceRefs, original.composer.sourceRefs); assert.deepEqual(after.state.selectedChatAttachments, original.state.selectedChatAttachments);
    const remove = await page.evaluate(() => window.previewFixture.calls.find(row => row.action === 'composer.preview.remove').args);
    assert.equal(remove.key, original.composer.key); assert.equal(remove.epoch, original.composer.epoch); assert.equal(remove.revision, original.composer.revision); assert.equal(remove.index, 0);
    assert.equal(await count('composer.send'), 0);
  });
  await check('modal-hides-native-viewport-and-back-really-leaves', async () => {
    await openPreview(); await page.getByRole('button', { name: '选择元素', exact: true }).click(); await page.getByRole('button', { name: '退出选择', exact: true }).waitFor();
    await page.getByRole('button', { name: '切换项目', exact: true }).click();
    await page.waitForFunction(() => !window.previewFixture.snapshot().state.projectPreview.visible && !window.previewFixture.snapshot().state.projectPreview.selecting);
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => window.previewFixture.snapshot().state.projectPreview.visible);
    await page.getByRole('button', { name: '选择元素', exact: true }).click(); await page.getByRole('button', { name: '退出选择', exact: true }).waitFor();
    assert.equal(await page.evaluate(() => window.PocketWeb.handleBack()), true); await page.getByRole('button', { name: '选择元素', exact: true }).waitFor();
    assert.equal(await page.evaluate(() => window.PocketWeb.handleBack()), true); await page.getByRole('region', { name: '项目预览', exact: true }).waitFor({ state: 'hidden' });
    assert.equal(await page.evaluate(() => window.PocketWeb.handleBack()), true); await page.waitForFunction(() => document.querySelector('.pw-app').dataset.route === 'chat');
    assert.equal(await count('projects.preview.close'), 0);
  });
  await check('true-close-and-cold-reference-remain-unavailable', async () => {
    await openPreview(); await chooseElement(); await page.getByRole('button', { name: '关闭预览', exact: true }).click();
    await page.getByRole('region', { name: '项目预览', exact: true }).waitFor({ state: 'hidden' });
    assert.equal((await state()).state.projectPreview, null); await navigate('对话');
    assert.equal(await page.getByRole('button', { name: '请重新选择预览元素', exact: true }).isDisabled(), true);
    await page.evaluate(() => window.previewFixture.coldReference()); assert.equal((await state()).composer.previewRefs.length, 1);
    assert.equal(await count('composer.send'), 0);
  });
  await check('document-rotation-does-not-apply-late-return', async () => {
    await openPreview(); await chooseElement(); await page.evaluate(() => { window.previewFixture.otherDraft(); window.previewFixture.holdReturn(); });
    await page.getByRole('button', { name: '返回原对话', exact: true }).click(); await page.waitForFunction(() => window.previewFixture.calls.some(row => row.action === 'projects.preview.return'));
    await page.evaluate(() => window.previewFixture.rotateDocument());
    await page.evaluate(() => window.previewFixture.releaseReturn());
    await page.getByRole('region', { name: '项目预览', exact: true }).waitFor({ state: 'hidden' });
    assert.equal(await page.locator('.pw-app').getAttribute('data-route'), 'projects'); assert.equal(await count('projects.preview.return'), 1); assert.equal(await count('composer.send'), 0);
  });
  await check('ended-original-recipient-rejects-without-new-task-or-draft-loss', async () => {
    await openPreview(); await chooseElement(); await page.getByRole('button', { name: '返回原对话', exact: true }).click(); await page.waitForFunction(() => document.querySelector('.pw-app').dataset.route === 'chat');
    const before = await state(); await page.evaluate(() => window.previewFixture.endRecipient()); await page.getByRole('button', { name: '发送', exact: true }).click();
    await page.getByText('原项目任务已结束，无法沿原任务发送；草稿仍保留。', { exact: true }).first().waitFor();
    const after = await state(); assert.equal(after.composer.text, before.composer.text); assert.deepEqual(after.composer.previewRefs, before.composer.previewRefs);
    assert.deepEqual(after.composer.sourceRefs, before.composer.sourceRefs); assert.deepEqual(after.state.selectedChatAttachments, before.state.selectedChatAttachments);
    assert.equal(await count('composer.send'), 1); assert.equal(await count('conversation.new'), 0); assert.equal(await count('tasks.start'), 0);
    // The existing global failure toast is dismissible; the stable inline notice
    // and original editable draft remain after that actual owner interaction.
    await page.locator('[data-sonner-toast] [data-close-button]').click();
    await page.locator('[data-sonner-toast]').waitFor({ state: 'hidden' });
    const input = page.getByRole('textbox', { name: '工作输入', exact: true }); await input.click();
    assert.equal(await input.evaluate(node => node === document.activeElement), true); assert.equal(await input.inputValue(), before.composer.text);
    await shot('ended-original-recipient-preserves-draft');
  });
  await check('short-landscape-keeps-actions-outside-preview', async () => {
    await page.setViewportSize({ width: 800, height: 360 }); await openPreview(); await stable();
    const boxes = await page.evaluate(() => { const rect = selector => { const b = document.querySelector(selector).getBoundingClientRect(); return { y:b.y,bottom:b.bottom,height:b.height }; }; return { viewport:rect('.pw-preview-viewport'), actions:rect('.pw-preview-actions'), footer:rect('.pw-preview-footer'), height:innerHeight }; });
    assert.ok(boxes.viewport.y >= boxes.actions.bottom && boxes.viewport.bottom <= boxes.footer.y + 1); assert.ok(boxes.footer.bottom <= boxes.height && boxes.viewport.height >= 48);
    await shot('short-landscape-preview-controls');
  });
  await check('missing-registered-preview-is-actionable-without-new-work', async () => {
    await page.evaluate(() => window.previewFixture.unavailable()); await navigate('项目'); await page.getByRole('button', { name: '预览项目', exact: true }).click();
    await page.getByText('当前项目没有已登记的预览，请先核对原项目的采集记录。', { exact: true }).waitFor();
    await page.getByRole('button', { name: '返回项目文件', exact: true }).click(); await page.getByRole('region', { name: '项目预览', exact: true }).waitFor({ state: 'hidden' });
    assert.equal(await count('projects.preview'), 1); assert.equal(await count('projects.build'), 0); assert.equal(await count('composer.send'), 0);
  });
} finally {
  result.finishedAt = new Date().toISOString(); result.passed = result.checks.every(check => check.passed) && result.errors.length === 0;
  await fs.writeFile(path.join(output, 'result.json'), JSON.stringify(result, null, 2) + '\n');
  await browser.close(); await new Promise(resolve => server.close(resolve));
}
console.log(JSON.stringify({ passed: result.passed, checks: result.checks, bundle: result.bundle, output }));
if (!result.passed) process.exitCode = 1;
