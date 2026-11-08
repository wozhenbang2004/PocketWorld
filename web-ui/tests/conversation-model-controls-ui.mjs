/** Real production component, synthetic native contracts; not an Android/provider test. */
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { chromium } from '@playwright/test';
import { createServer } from 'vite';

const root = path.resolve(import.meta.dirname, '..');
const output = process.env.MODEL_CONTROLS_EVIDENCE || path.resolve(root, '../build-artifacts/conversation-model-controls');
await fs.mkdir(output, { recursive: true });
const server = await createServer({ root, server: { host: '127.0.0.1', port: 0 }, optimizeDeps: { entries: ['tests/conversation-model-controls-fixture.html'] }, logLevel: 'error' });
await server.listen();
const browser = await chromium.launch({ headless: true });
const checks = [], errors = [];
async function scenario(name, query, work) {
  const page = await browser.newPage({ viewport: { width: 390, height: 640 }, hasTouch: true, isMobile: true, reducedMotion: 'reduce' });
  page.setDefaultTimeout(10000); page.on('pageerror', error => errors.push(`${name}: ${error.message}`));
  try {
    await page.goto(server.resolvedUrls.local[0] + 'tests/conversation-model-controls-fixture.html' + query);
    await page.waitForFunction(() => !!window.reasoningFixture);
    await work(page); checks.push({ name, passed: true });
  } catch (error) { checks.push({ name, passed: false, error: String(error.stack || error) }); await page.screenshot({ path: path.join(output, name + '-failure.png') }); }
  finally { await page.close(); }
}
const open = async page => { await page.getByRole('button', { name: '推理强度：中', exact: true }).click(); await page.getByRole('radiogroup', { name: '可用推理强度', exact: true }).waitFor(); };
const high = async page => { await page.locator('.pw-conversation-reasoning-option').filter({ has: page.getByText('high', { exact: true }) }).click(); };
try {
  await scenario('initial-read-and-hit-targets', '?delaySnapshot=1', async page => {
    const chip = page.locator('.pw-conversation-reasoning-chip'); await chip.waitFor();
    assert.equal((await chip.textContent()).includes('默认'), false);
    assert.match(await chip.getAttribute('aria-label'), /正在读取/);
    await page.evaluate(() => window.reasoningFixture.settle('models.snapshot'));
    await page.getByRole('button', { name: '推理强度：中', exact: true }).waitFor();
    assert.deepEqual(await page.evaluate(() => window.reasoningFixture.calls.map(item => item.action)), ['models.snapshot']);
    const controls = await page.locator('.pw-conversation-model-controls button').evaluateAll(nodes => nodes.map(node => ({ width: node.getBoundingClientRect().width, height: node.getBoundingClientRect().height })));
    assert.ok(controls.every(box => box.width >= 48 && box.height >= 48), JSON.stringify(controls));
    await page.screenshot({ path: path.join(output, 'controls-390.png') });
  });
  await scenario('api-exact-effort-save', '', async page => {
    await open(page); await high(page); await page.screenshot({ path: path.join(output, 'api-reasoning.png') });
    await page.getByRole('button', { name: '应用', exact: true }).click();
    await page.locator('.pw-conversation-reasoning-drawer').waitFor({ state: 'hidden' });
    await page.getByRole('button', { name: '推理强度：高', exact: true }).waitFor();
    const writes = await page.evaluate(() => window.reasoningFixture.calls.filter(item => item.action === 'models.select'));
    assert.equal(writes.length, 1); assert.deepEqual(Object.keys(writes[0].args).sort(), ['reasoning', 'reviewHandle']);
    assert.deepEqual(writes[0].args.reasoning, { mode: 'OPENAI_EFFORT', level: 'high', budgetTokens: 0 });
  });
  await scenario('account-current-model-direct-effort', '?route=account', async page => {
    await page.getByRole('button', { name: '推理强度：中', exact: true }).waitFor();
    assert.equal(await page.evaluate(() => window.reasoningFixture.calls.some(item => item.action.startsWith('models.accounts.'))), false);
    await open(page); await high(page); await page.screenshot({ path: path.join(output, 'account-reasoning.png') });
    await page.getByRole('button', { name: '应用', exact: true }).click();
    await page.locator('.pw-conversation-reasoning-drawer').waitFor({ state: 'hidden' });
    await page.getByRole('button', { name: '推理强度：高', exact: true }).waitFor();
    const calls = await page.evaluate(() => window.reasoningFixture.calls);
    const read = calls.find(item => item.action === 'models.accounts.models'), write = calls.find(item => item.action === 'models.accounts.select');
    assert.deepEqual(read.args, { windowId: 'account-window-1', accountId: 'fixture-account', expectedVersion: 4 });
    assert.deepEqual(write.args, { windowId: 'account-window-1', reviewHandle: 'account-review-2', options: { reasoningEffort: 'high', outputReserve: 7168, imageTokenReserve: 2800 } });
  });
  await scenario('unknown-save-consumes-review-and-blocks-back', '?route=account', async page => {
    await open(page); await high(page); await page.evaluate(() => window.reasoningFixture.setDelay(['models.accounts.select']));
    await page.getByRole('button', { name: '应用', exact: true }).click();
    await page.getByRole('button', { name: '正在保存…', exact: true }).waitFor();
    assert.equal(await page.evaluate(() => window.reasoningFixture.back()), true);
    assert.equal(await page.locator('.pw-conversation-reasoning-drawer').isVisible(), true);
    await page.evaluate(() => window.reasoningFixture.settle('models.accounts.select', 'unknown'));
    await page.getByRole('alert').filter({ hasText: '没有自动重试' }).waitFor();
    assert.equal(await page.getByRole('button', { name: '应用', exact: true }).count(), 0);
    assert.equal(await page.evaluate(() => window.reasoningFixture.calls.filter(item => item.action === 'models.accounts.select').length), 1);
    await page.getByRole('button', { name: '重新读取', exact: true }).click(); await page.getByRole('radiogroup').waitFor();
    assert.equal(await page.evaluate(() => window.reasoningFixture.calls.filter(item => item.action === 'models.accounts.select').length), 1);
    assert.equal(await page.getByRole('button', { name: '应用', exact: true }).isDisabled(), true);
  });
  await scenario('changed-model-invalidates-review', '', async page => {
    await open(page); await high(page); await page.evaluate(() => window.reasoningFixture.changeRevision());
    await page.getByRole('alert').filter({ hasText: '模型设置已变化' }).waitFor();
    assert.equal(await page.getByRole('button', { name: '应用', exact: true }).count(), 0);
    assert.equal(await page.evaluate(() => window.reasoningFixture.calls.some(item => item.action === 'models.select')), false);
    assert.equal(await page.evaluate(() => window.reasoningFixture.back()), true);
    await page.locator('.pw-conversation-reasoning-drawer').waitFor({ state: 'hidden' });
  });
  await scenario('group-route-is-never-silently-replaced', '', async page => {
    await page.getByRole('button', { name: '推理强度：中', exact: true }).waitFor(); await page.evaluate(() => window.reasoningFixture.useGroup());
    await page.getByRole('button', { name: '推理强度：中', exact: true }).click();
    await page.getByText('当前模型由用途配置指定，请在模型配置中调整推理设置。', { exact: true }).waitFor();
    assert.equal(await page.evaluate(() => window.reasoningFixture.calls.some(item => ['models.review', 'models.select'].includes(item.action))), false);
    await page.getByRole('button', { name: '选择模型', exact: true }).click();
    assert.equal(await page.evaluate(() => window.reasoningFixture.modelOpened()), 1);
  });
} finally {
  await browser.close(); await server.close(); await fs.writeFile(path.join(output, 'results.json'), JSON.stringify({ scope: 'Production component with synthetic native contracts; Android/provider execution not tested.', checks, errors }, null, 2) + '\n');
}
console.log(JSON.stringify({ checks, errors })); if (errors.length || checks.some(check => !check.passed)) process.exitCode = 1;
