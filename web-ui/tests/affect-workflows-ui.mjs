import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { createServer } from 'vite';
import { chromium } from '@playwright/test';

const output = process.env.PW_AFFECT_EVIDENCE || '/tmp/pocketworld-affect-workflows';
await fs.mkdir(output, { recursive: true });
const server = await createServer({ server: { host: '127.0.0.1', port: 0 } });
await server.listen();
const browser = await chromium.launch({ headless: true, executablePath: process.env.PW_CHROMIUM || '/workspace/scratch/af478521f69c/pocketworld-browser-cache/chromium_headless_shell-1194/chrome-linux/headless_shell' });
const errors = [], checks = [];
try {
  const page = await browser.newPage({ viewport: { width: 390, height: 844 }, reducedMotion: 'reduce' });
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(`${server.resolvedUrls.local[0]}tests/affect-workflows.html`, { waitUntil: 'networkidle' });
  await page.getByRole('button', { name: /文档整理/ }).waitFor();
  const commands = () => page.evaluate(() => window.affectFixture.calls);
  await page.getByRole('button', { name: /故障排查/ }).click();
  assert.equal((await commands()).filter(call => call.action === 'skills.get').length, 0);
  await page.getByRole('searchbox', { name: '查找已登记技能' }).fill('read_log');
  assert.equal(await page.locator('[data-skill-id]').count(), 1);
  await page.getByRole('button', { name: '阅读这份技能' }).click();
  assert.equal((await commands()).filter(call => call.action === 'skills.get').length, 1);
  await page.getByRole('button', { name: '返回技能列表' }).click();
  checks.push('Skill metadata preview/search have no native read; explicit reading opens original skill handle.');

  await page.getByRole('button', { name: '诊断与运行日志' }).click();
  await page.getByRole('button', { name: '读取最近日志' }).click();
  assert.equal(await page.locator('.pw-affect-flow-model').count(), 2);
  await page.getByRole('button', { name: '模型请求', exact: true }).click();
  assert.equal(await page.locator('.pw-affect-flow-model').count(), 1);
  await page.locator('.pw-affect-flow-model').click();
  assert.match(await page.locator('.pw-affect-attempt-inspector').innerText(), /TIMEOUT/);
  checks.push('Runtime start/settle pair merges only by native identity; filters and original event inspector work.');

  await page.getByRole('button', { name: '核对本次测试', exact: true }).click();
  const confirm = page.getByRole('button', { name: /明确测试这项 API/ });
  assert.equal(await confirm.isDisabled(), true);
  assert.equal((await commands()).filter(call => call.action === 'modelDiagnostics.start').length, 0);
  await page.getByText('我已核对本次范围与额度消耗', { exact: true }).click();
  await page.evaluate(() => { window.affectFixture.failStart = true; });
  await confirm.click();
  await page.getByRole('alert').filter({ hasText: '原连接已变化' }).waitFor();
  assert.match(await page.locator('.pw-affect-stepnav [aria-current=step]').innerText(), /选择范围/);
  checks.push('Paid-probe confirmation is disabled before acknowledgement; a rejected start never claims observation/success.');

  await page.evaluate(() => { window.affectFixture.failStart = false; });
  await page.getByRole('button', { name: '核对本次测试', exact: true }).click();
  await page.getByText('我已核对本次范围与额度消耗', { exact: true }).click();
  await confirm.click();
  await page.locator('.pw-affect-observation').waitFor();
  assert.match(await page.locator('.pw-affect-stepnav [aria-current=step]').innerText(), /观察结果/);
  await page.getByRole('button', { name: /本次合成实测/ }).click();
  await page.locator('.pw-affect-experiment-observations p').filter({ hasText: '本次未返回单个合成函数；没有执行真实工具。' }).waitFor();
  assert.match(await page.locator('.pw-affect-observation-overview').innerText(), /本次通过.*未证实.*未选择/s);
  checks.push('Only started:true advances to observation; text/function/image statuses and actual uncertainty details remain separate.');
  await page.screenshot({ path: path.join(output, 'mobile-light.png'), fullPage: true });
  await page.evaluate(() => document.documentElement.classList.add('dark'));
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  await page.screenshot({ path: path.join(output, 'mobile-dark.png'), fullPage: true });
  await page.setViewportSize({ width: 1100, height: 850 });
  await page.evaluate(() => document.documentElement.classList.remove('dark'));
  await page.screenshot({ path: path.join(output, 'desktop-light.png'), fullPage: true });

  await page.getByRole('button', { name: '核对本次测试', exact: true }).click();
  await page.evaluate(() => window.affectFixture.changeSelection());
  await page.getByRole('dialog').waitFor({ state: 'hidden' });
  assert.match(await page.locator('.pw-affect-stepnav [aria-current=step]').innerText(), /选择范围/);
  assert.equal((await commands()).filter(call => call.action === 'modelDiagnostics.start').length, 2);
  checks.push('Changing native selection invalidates the review and progress without dispatching another start.');
  assert.deepEqual(errors, []);
  await fs.writeFile(path.join(output, 'receipt.json'), JSON.stringify({ scope: 'Real React pages with synthetic native command fixture. No Android/device/model/API/billing verification.', checks, errors }, null, 2));
  console.log(JSON.stringify({ output, checks, errors }, null, 2));
} finally { await browser.close(); await server.close(); }
