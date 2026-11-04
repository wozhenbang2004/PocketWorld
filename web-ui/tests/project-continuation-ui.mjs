/** Mounted production UI. Synthetic native review/receipt/receiver; never an Android admission proof. */
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import http from 'node:http';
import { createHash } from 'node:crypto';
import { chromium } from '@playwright/test';
import { installProjectPreviewHost } from './project-preview-host-fixture.mjs';

const argument = name => { const index = process.argv.indexOf(name), value = process.argv[index + 1]; if (index < 0 || !value || value.startsWith('--')) throw new Error('Required --bundle <path> --output <path>'); return path.resolve(value); };
const bundle = argument('--bundle'), output = argument('--output');
const only = process.argv.includes('--case') ? process.argv[process.argv.indexOf('--case') + 1] : null;
await fs.mkdir(output, { recursive: true });
const index = await fs.readFile(path.join(bundle, 'index.html'), 'utf8'), entry = index.match(/assets\/(index-[^" ]+\.js)/)?.[1];
assert.ok(entry, 'Use the actual production bundle');
const result = { scope: 'Production Web UI at 360x800 and 800x360. Native task reviews, receipts, document metadata, lifecycle and receiver are synthetic. No Android SQL, Assistant queue, renderer, source or model execution validation.', startedAt: new Date().toISOString(),
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
const count = action => page.evaluate(action => window.previewFixture.calls.filter(row => row.action === action).length, action);
const feedback = () => page.getByRole('textbox', { name: '本次修改要求', exact: true });
const continueButton = () => page.getByRole('button', { name: '在原范围内继续项目任务', exact: true });
const drawer = () => page.locator('.pw-task-drawer');
async function stable() {
  await page.waitForFunction(() => {
    const nodes = [...document.querySelectorAll('.pw-task-header,.pw-task-body,.pw-task-footer,.pw-preview-pane,.pw-composer-region')];
    const key = JSON.stringify(nodes.map(node => { const r = node.getBoundingClientRect(); return [r.x,r.y,r.width,r.height,node.scrollTop]; }));
    const prior = window.__continuationLayout; window.__continuationLayout = { key, count: prior?.key === key ? prior.count + 1 : 0 }; return window.__continuationLayout.count >= 3;
  }, null, { polling: 'raf' });
}
async function shot(name) { await stable(); await page.screenshot({ path: path.join(output, name + '.png') }); result.screenshots.push(name + '.png'); }
async function navigate(name) {
  await page.getByRole('button', { name: '打开导航', exact: true }).click();
  const navigation = page.locator('.pw-work-drawer'); await navigation.getByRole('link', { name, exact: true }).click(); await navigation.waitFor({ state: 'hidden' });
}
async function taskOverview() { await navigate('任务'); await page.locator('.pw-task-row[data-task-id="original-project-task"]').click(); await drawer().waitFor(); }
async function reviewFromOverview() { await taskOverview(); await page.getByRole('button', { name: '核对继续原项目任务', exact: true }).click(); await feedback().waitFor(); }
async function selectedPreview() {
  await navigate('项目'); await page.getByRole('button', { name: '预览项目', exact: true }).click();
  await page.waitForFunction(() => !!window.previewFixture.snapshot().state.projectPreview); await page.evaluate(() => window.previewFixture.ready());
  await page.getByRole('button', { name: '选择元素', exact: true }).click(); await page.getByRole('button', { name: '退出选择', exact: true }).waitFor();
  await page.evaluate(() => { window.previewFixture.beginCapture(); window.previewFixture.finishCapture(); });
  await page.getByText(/已加入 1 处元素引用，尚未发送/).waitFor();
}
async function reviewFromPreview() { await page.getByRole('button', { name: '核对原任务', exact: true }).click(); await feedback().waitFor(); }
async function submit(note = '请调整保存按钮的留白，保留其他内容。') { await feedback().fill(note); await continueButton().click(); await page.waitForFunction(() => window.previewFixture.continuationReceipts().length === 1); }
async function returned() { await page.getByRole('button', { name: '返回原对话，草稿保留', exact: true }).click(); await drawer().waitFor({ state: 'hidden' }); await page.waitForFunction(() => document.querySelector('.pw-app').dataset.route === 'chat'); }
async function noNewWork() { assert.equal(await count('tasks.start'), 0); assert.equal(await count('conversation.new'), 0); assert.equal(await count('projects.create'), 0); }
async function check(name, work, continuation = {}) {
  if (only && only !== name) return;
  const context = await browser.newContext({ viewport: { width: 360, height: 800 }, deviceScaleFactor: 1, locale: 'zh-CN', reducedMotion: 'reduce', serviceWorkers: 'block' });
  await context.route('**/*', route => new URL(route.request().url()).origin === origin ? route.continue() : route.abort());
  await context.addInitScript(installProjectPreviewHost, { continuation }); page = await context.newPage(); page.setDefaultTimeout(8000);
  page.on('pageerror', error => result.errors.push({ name, error: error.message }));
  try {
    await page.goto(origin, { waitUntil: 'networkidle' }); await page.waitForFunction(() => document.documentElement.dataset.pocketUi === 'ready');
    await work(); assert.deepEqual(await page.evaluate(() => window.previewFixture.errors), []); await noNewWork(); result.checks.push({ name, passed: true });
  } catch (error) {
    result.checks.push({ name, passed: false, error: String(error.message || error), observed: await page.evaluate(() => ({
      calls: window.previewFixture.calls.map(row => ({ action: row.action, args: row.args })), view: window.previewFixture.snapshot().view,
      task: window.previewFixture.snapshot().state.tasks[0], route: document.querySelector('.pw-app')?.dataset.route,
    })) }); await shot(name + '-failure');
  } finally { await context.close(); }
}

try {
  await check('completed-overview-explicit-feedback-continue-then-separate-send', async () => {
    await selectedPreview(); await page.getByRole('button', { name: '返回原对话', exact: true }).click(); await page.waitForFunction(() => document.querySelector('.pw-app').dataset.route === 'chat');
    const original = await state(); await taskOverview(); assert.equal(await count('tasks.review'), 0, 'Completed overview offers an explicit read-only review entry');
    await page.getByRole('button', { name: '核对继续原项目任务', exact: true }).click(); await feedback().waitFor();
    assert.equal(await feedback().inputValue(), ''); assert.equal(await continueButton().isDisabled(), true); await feedback().fill('  \n '); assert.equal(await continueButton().isDisabled(), true);
    await feedback().fill('  请调整保存按钮的留白，保留其他内容。  '); await shot('original-review-required-feedback'); await continueButton().click();
    await page.getByText(/原项目任务已续接。对话草稿尚未发送/).waitFor();
    const after = await state(), resume = await page.evaluate(() => window.previewFixture.calls.find(row => row.action === 'tasks.resume').args);
    assert.deepEqual(Object.keys(resume).sort(), ['confirmed', 'feedback', 'reviewHandle']); assert.equal(resume.confirmed, true); assert.equal(resume.feedback, '请调整保存按钮的留白，保留其他内容。');
    assert.deepEqual(after.composer, original.composer); assert.deepEqual(after.state.selectedChatAttachments, original.state.selectedChatAttachments);
    assert.deepEqual(after.state.tasks[0].raw.executionScope, original.state.tasks[0].raw.executionScope); assert.equal(after.state.tasks[0].id, original.state.tasks[0].id);
    assert.equal(after.state.projectPreview.sessionId, original.state.projectPreview.sessionId); assert.equal(after.state.projectPreview.documentGeneration, original.state.projectPreview.documentGeneration);
    assert.equal(await count('projects.preview.close'), 0); assert.equal(await count('composer.send'), 0); assert.equal(await count('tasks.resume'), 1);
    await returned(); assert.equal(await page.getByRole('textbox', { name: '工作输入', exact: true }).inputValue(), original.composer.text);
    assert.deepEqual((await state()).composer, original.composer); await shot('continued-original-conversation-draft-unsent');
    await page.getByRole('button', { name: '发送', exact: true }).click(); await page.waitForFunction(() => window.previewFixture.calls.filter(row => row.action === 'composer.send').length === 1);
    assert.equal(await count('tasks.resume'), 1); assert.equal((await state()).composer.previewRefs[0].runId, 'source-run', 'Do not rewrite producing-run metadata to the new receiver');
  });
  await check('current-preview-opens-original-task-not-in-visible-task-list', async () => {
    assert.equal((await state()).state.tasks.length, 0); await selectedPreview(); const original = await state(); await reviewFromPreview();
    assert.equal(await count('tasks.open'), 1); assert.equal(await count('tasks.review'), 1); assert.equal(await count('tasks.resume'), 0);
    const after = await state(); assert.equal(after.state.tasks[0].id, original.state.projectPreview.taskId); assert.equal(after.state.projectPreview.sessionId, original.state.projectPreview.sessionId);
    assert.equal(after.state.projectPreview.documentGeneration, original.state.projectPreview.documentGeneration); assert.equal(after.state.projectPreview.visible, false);
    assert.deepEqual(after.composer, original.composer); assert.equal(await count('projects.preview.close'), 0); await shot('preview-original-task-review');
  }, { initialTaskHidden: true });
  await check('pending-close-late-receipt-and-read-do-not-unlock-replay', async () => {
    await selectedPreview(); const original = await state(); await reviewFromPreview(); await page.evaluate(() => window.previewFixture.holdContinuation()); await submit();
    await page.getByRole('button', { name: '返回原工作', exact: true }).click(); await drawer().waitFor({ state: 'hidden' });
    await taskOverview(); await page.getByText(/尚未确认这次续接结果/).waitFor();
    await page.evaluate(() => window.previewFixture.releaseContinuation()); await page.getByText(/尚未确认这次续接结果/).waitFor();
    await page.evaluate(() => window.previewFixture.endRecipient()); await page.getByRole('button', { name: '核对原任务状态', exact: true }).click(); await feedback().waitFor();
    assert.equal(await feedback().inputValue(), '请调整保存按钮的留白，保留其他内容。'); assert.equal(await continueButton().isDisabled(), true); assert.equal(await feedback().isDisabled(), true);
    assert.equal(await count('tasks.resume'), 1); assert.deepEqual((await state()).composer, original.composer); await shot('unknown-original-task-feedback-retained');
  });
  await check('token-only-change-retains-unknown-and-independent-send', async () => {
    await selectedPreview(); const original = await state(); await reviewFromPreview(); await page.evaluate(() => window.previewFixture.holdContinuation()); await submit();
    await page.evaluate(() => window.previewFixture.rotateReviewDocument()); await page.getByText(/尚未确认这次续接结果/).waitFor();
    await page.evaluate(() => window.previewFixture.releaseContinuation()); await page.getByText(/尚未确认这次续接结果/).waitFor();
    assert.equal(await count('tasks.resume'), 1); assert.deepEqual((await state()).composer, original.composer);
    await returned(); await page.getByRole('button', { name: '发送', exact: true }).click(); await page.waitForFunction(() => window.previewFixture.calls.filter(row => row.action === 'composer.send').length === 1);
    assert.equal(await count('tasks.resume'), 1);
  });
  await check('native-background-resume-invalidates-only-temporary-review', async () => {
    await reviewFromOverview(); await page.evaluate(() => window.previewFixture.holdContinuation()); await submit('保留这份任务自己的反馈。');
    await page.evaluate(() => window.previewFixture.resumeReviewWindow()); await page.getByText(/尚未确认这次续接结果/).waitFor();
    await page.evaluate(() => { window.previewFixture.releaseContinuation(); window.previewFixture.endRecipient(); });
    await page.getByRole('button', { name: '核对原任务状态', exact: true }).click(); await feedback().waitFor();
    assert.equal(await feedback().inputValue(), '保留这份任务自己的反馈。'); assert.equal(await continueButton().isDisabled(), true); assert.equal(await count('tasks.resume'), 1);
  });
  for (const [name, reason] of [
    ['expired', '原任务范围已到期，本次不能续期；草稿仍保留。'],
    ['exhausted', '原任务调用额度已用尽，本次不能增加额度；草稿仍保留。'],
    ['missing', '原任务范围缺失，不能自动创建新的额度。'],
  ]) await check('native-' + name + '-scope-reason-has-no-continue-action', async () => {
    const original = await state(); await reviewFromOverview(); await page.getByText(reason, { exact: true }).waitFor();
    assert.equal(await continueButton().count(), 0); assert.equal(await feedback().isDisabled(), true); assert.equal(await count('tasks.resume'), 0); assert.deepEqual((await state()).composer, original.composer);
    if (name === 'expired') await shot('expired-original-scope-readable-refusal');
  }, { reason });
  await check('cancelled-original-is-readable-without-continue-entry', async () => {
    await taskOverview(); assert.equal(await page.getByRole('button', { name: '核对继续原项目任务', exact: true }).count(), 0); assert.equal(await continueButton().count(), 0);
    assert.equal(await count('tasks.resume'), 0); assert.equal(await count('tasks.review'), 0);
  }, { status: 'cancelled' });
  await check('mismatched-native-receipt-cannot-become-success', async () => {
    await reviewFromOverview(); await page.evaluate(() => window.previewFixture.continuationReceipt('wrong_task')); await submit(); await page.getByText(/尚未确认这次续接结果/).waitFor();
    assert.equal(await page.getByText(/原项目任务已续接。对话草稿尚未发送/).count(), 0); assert.equal(await count('tasks.resume'), 1);
  });
  for (const changed of ['ended', 'source']) await check('successor-' + changed + '-before-send-preserves-original-draft', async () => {
    await selectedPreview(); const original = await state(); await reviewFromPreview(); await submit(); await page.getByText(/原项目任务已续接。对话草稿尚未发送/).waitFor(); await returned();
    await page.evaluate(changed => changed === 'ended' ? window.previewFixture.endRecipient() : window.previewFixture.changeSource(), changed);
    if (changed === 'ended') {
      await page.getByRole('button', { name: '发送', exact: true }).click(); await page.getByText('原项目任务已结束，无法沿原任务发送；草稿仍保留。', { exact: true }).first().waitFor();
      assert.equal(await count('composer.send'), 1);
    } else { assert.equal(await page.getByRole('button', { name: '请重新选择预览元素', exact: true }).isDisabled(), true); assert.equal(await count('composer.send'), 0); }
    const after = await state(); assert.equal(after.composer.text, original.composer.text); assert.deepEqual(after.composer.previewRefs, original.composer.previewRefs);
    assert.deepEqual(after.composer.sourceRefs, original.composer.sourceRefs); assert.deepEqual(after.state.selectedChatAttachments, original.state.selectedChatAttachments); assert.equal(await count('tasks.resume'), 1);
  });
  await check('short-landscape-feedback-and-fixed-action-are-keyboard-reachable', async () => {
    await page.setViewportSize({ width: 800, height: 360 }); await reviewFromOverview(); await feedback().fill('横屏下也能核对本次修改要求。'); await feedback().click();
    let reached = false;
    for (let index = 0; index < 20; index++) { await page.keyboard.press('Tab'); if (await continueButton().evaluate(node => node === document.activeElement)) { reached = true; break; } }
    assert.equal(reached, true, 'Existing footer remains reachable by actual Tab navigation');
    const rect = await continueButton().boundingBox(); assert.ok(rect && rect.y >= 0 && rect.y + rect.height <= 361); await shot('short-landscape-original-review');
    assert.equal(await count('tasks.resume'), 0); assert.equal(await count('composer.send'), 0);
  });
} finally {
  result.finishedAt = new Date().toISOString(); result.passed = result.checks.length > 0 && result.checks.every(check => check.passed) && result.errors.length === 0;
  await fs.writeFile(path.join(output, 'result.json'), JSON.stringify(result, null, 2) + '\n'); await browser.close(); await new Promise(resolve => server.close(resolve));
}
console.log(JSON.stringify({ passed: result.passed, checks: result.checks.map(({ name, passed, error }) => ({ name, passed, error })), bundle: result.bundle, output }));
if (!result.passed) process.exitCode = 1;
