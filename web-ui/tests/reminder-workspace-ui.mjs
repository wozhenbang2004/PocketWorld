import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createServer } from 'vite';
import { chromium } from '@playwright/test';

const output = path.resolve(process.env.PW_REMINDER_OUTPUT || 'test-results/reminder-workspace');
const cacheDir = path.join(os.tmpdir(), `pw-reminder-ui-${process.pid}`);
await fs.mkdir(output, { recursive: true });
const server = await createServer({ cacheDir, optimizeDeps: { entries: ['tests/depth-knowledge.html'] }, server: { host: '127.0.0.1', port: 0, watch: null, hmr: false } });
await server.listen();
const browser = await chromium.launch({ headless: true, ...(process.env.PW_CHROMIUM ? { executablePath: process.env.PW_CHROMIUM } : {}) });
const page = await browser.newPage({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, timezoneId: 'Asia/Shanghai', reducedMotion: 'reduce' });
const errors = [], checks = [], screenshots = [];
page.on('pageerror', error => errors.push(error.message));
page.setDefaultTimeout(12000);
const calls = () => page.evaluate(() => window.knowledgeFixture.calls);
const capture = async name => {
  await page.waitForFunction(() => document.fonts.status === 'loaded');
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, `${name}: no horizontal overflow`);
  await page.screenshot({ path: path.join(output, `${name}.png`), fullPage: true, animations: 'disabled' });
  screenshots.push(name);
};
const open = async () => {
  await page.goto(`${server.resolvedUrls.local[0]}tests/depth-knowledge.html?area=reminders`, { waitUntil: 'networkidle' });
  await page.getByRole('heading', { name: '提醒与主动任务', exact: true }).waitFor();
};
try {
  await open();
  const attention = page.getByRole('region', { name: '需要核对的提醒结果', exact: true });
  await attention.getByText('投递未知', { exact: true }).waitFor();
  await attention.getByText('等待你查看', { exact: true }).waitFor();
  await attention.getByRole('button', { name: '准备原手机任务（不执行）', exact: true }).waitFor();
  for (const field of await page.getByText('领取延后', { exact: true }).all()) assert.equal(await field.isVisible(), false, 'delivery mechanics start collapsed');
  const firstPlan = await page.locator('[data-reminder-id="reminder-a"]').boundingBox();
  assert.ok(firstPlan && firstPlan.y < 844, 'the next reminder is discoverable in the initial phone viewport');
  await capture('reminder-overview-390');

  await attention.getByRole('button', { name: '核对结果', exact: true }).click();
  assert.deepEqual((await calls()).map(call => call.action), ['automation.receipts'], 'query only reads the existing receipts');
  await page.evaluate(() => { window.knowledgeFixture.failNext = 'automation.receipts'; });
  await attention.getByRole('button', { name: '核对结果', exact: true }).click();
  await page.getByText('模拟宿主：原版本已变化，请重新核对。', { exact: true }).waitFor();
  await attention.getByText('投递未知', { exact: true }).waitFor();
  assert.equal((await calls()).some(call => /confirm|prepareOccurrence|save|delete|setEnabled/.test(call.action)), false, 'failed receipt query cannot replay or start a task');
  checks.push('Unknown delivery and awaiting-user outcomes remain visible; querying uses only automation.receipts, including a failed query.');

  await open();
  await attention.getByRole('button', { name: '投递明细', exact: true }).click();
  await attention.getByText('领取延后', { exact: true }).first().waitFor();
  await capture('reminder-delivery-details-390');
  await attention.getByRole('button', { name: '投递明细', exact: true }).click();
  await attention.getByRole('button', { name: '准备原手机任务（不执行）', exact: true }).click();
  assert.deepEqual((await calls()).find(call => call.action === 'automation.prepareOccurrence')?.args, { occurrenceId: 'occurrence-b' }, 'prepare retains the actual occurrence identity');
  checks.push('Detailed delivery timing is available on demand; preparing the phone goal retains occurrence-b and does not confirm execution.');

  await page.getByRole('button', { name: '暂停与结束', exact: true }).click();
  assert.equal(await page.locator('[data-reminder-id]').count(), 1);
  await page.locator('[data-reminder-id="reminder-b"]').click();
  await page.getByRole('button', { name: '编辑', exact: true }).click();
  const dialog = page.locator('.pw-settings-layer:not([inert])[data-visible=true] [role=dialog]');
  await dialog.getByRole('textbox', { name: '提醒内容', exact: true }).waitFor();
  assert.equal(await dialog.getByRole('textbox', { name: '提醒内容', exact: true }).inputValue(), '周五整理项目周报');
  await dialog.getByRole('button', { name: '核对提醒计划', exact: true }).click();
  await dialog.getByRole('heading', { name: '核对提醒计划', exact: true }).waitFor();
  const reviewed = (await calls()).findLast(call => call.action === 'automation.reviewSchedule');
  assert.equal(reviewed.args.id, 'reminder-b');
  assert.equal(reviewed.args.revision, 2);
  assert.equal(reviewed.args.draft.preserveSchedule, true);
  assert.equal((await calls()).some(call => call.action === 'automation.confirmReview'), false);
  await dialog.getByRole('button', { name: '返回修改', exact: true }).click();
  await dialog.getByRole('button', { name: '取消', exact: true }).click();
  await page.getByRole('button', { name: '返回提醒列表', exact: true }).click();
  assert.equal(await page.getByRole('button', { name: '暂停与结束', exact: true }).getAttribute('aria-pressed'), 'true');
  assert.equal(await page.locator('[data-reminder-id="reminder-b"]').count(), 1);
  checks.push('A filtered plan opens the same id/revision, preserves schedule on review, and returns to its retained filter without submission.');

  await open();
  await page.setViewportSize({ width: 320, height: 844 });
  await capture('reminder-overview-320');
  await page.setViewportSize({ width: 390, height: 844 });
  await page.evaluate(() => { document.documentElement.classList.add('dark'); });
  assert.equal(await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--pw-canvas').trim().toLowerCase()), '#111111', 'the dark capture uses the real product theme');
  await capture('reminder-overview-dark-390');
  assert.deepEqual(errors, []);
  await fs.writeFile(path.join(output, 'result.json'), JSON.stringify({ checks, screenshots, errors, platform: 'Chromium, current React components with the existing isolated native fixture; no Android device validation' }, null, 2));
  console.log(JSON.stringify({ checks, screenshots, errors }, null, 2));
} catch (error) {
  await page.screenshot({ path: path.join(output, 'failure.png'), fullPage: true });
  throw error;
} finally {
  await browser.close();
  await server.close();
  await fs.rm(cacheDir, { recursive: true, force: true });
}
