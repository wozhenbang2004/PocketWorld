/** Production account components; synthetic native snapshots. No OAuth, credentials or account I/O. */
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { createServer } from 'vite';
const root = fileURLToPath(new URL('..', import.meta.url));
const output = process.env.ACCOUNT_RECOVERY_EVIDENCE || path.resolve(root, '../build-artifacts/account-login-recovery');
await fs.mkdir(output, { recursive: true });
const server = await createServer({ root, logLevel: 'error', server: { host: '127.0.0.1', port: 0 } });
await server.listen();
const browser = await chromium.launch({ executablePath: process.env.UI_BROWSER_PATH || undefined, headless: true });
const observations = [];
const original = { id: 'original-account', label: '日常使用的 ChatGPT 账号', version: 7, enabled: true, planUsageEnabled: false, status: 'reauthorization_required', error: 'LOGIN_EXPIRED' };
const login = { sessionId: 'expired-session', accountId: original.id, status: 'expired', expiresAt: 1730000000000 };
async function publish(page, accounts, state = login, extra = {}) {
  await page.evaluate(({ accounts, login, extra }) => window.modelFixture.publish({ ownerSnapshots: { accounts: { accounts, login } }, ...extra }), { accounts, login: state, extra });
}
async function calls(page, action) { return page.evaluate(action => window.modelFixture.calls.filter(call => call.action === action), action); }
async function check(name, width, dark, work) {
  const page = await browser.newPage({ viewport: { width, height: 900 }, reducedMotion: 'reduce' });
  page.setDefaultTimeout(5000);
  const errors = []; page.on('pageerror', error => errors.push(String(error)));
  try {
    await page.goto(server.resolvedUrls.local[0] + 'tests/model-account-fixture.html?view=accounts');
    await page.getByRole('button', { name: '添加账号', exact: true }).waitFor();
    await page.evaluate(dark => document.documentElement.classList.toggle('dark', dark), dark);
    await work(page);
    assert.deepEqual(errors, []);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, 'No horizontal overflow');
    observations.push({ name, width, dark, passed: true });
  } catch (error) { observations.push({ name, width, dark, passed: false, error: String(error.message) }); }
  finally { await page.screenshot({ path: path.join(output, name + '.png'), fullPage: true }); await page.close(); }
}
try {
  for (const [width, dark] of [[430, false], [320, true]]) {
    await check('unregistered-expired-' + width, width, dark, async page => {
      await publish(page, []);
      const auth = page.getByRole('region', { name: '本次授权', exact: true });
      await auth.getByText('授权已过期', { exact: true }).waitFor();
      assert.equal(await page.getByRole('button', { name: '打开原授权页面', exact: true }).count(), 0);
      assert.equal((await calls(page, 'accounts.beginLogin')).length, 0, 'Expiry never starts OAuth automatically');
      const retry = auth.getByRole('button', { name: '重新开始登录', exact: true });
      await retry.click();
      const dialog = page.getByRole('dialog', { name: '添加 ChatGPT 账号', exact: true });
      await dialog.waitFor();
      await page.getByRole('textbox', { name: '账号名称（可选）', exact: true }).fill('我的常用账号');
      await page.getByRole('button', { name: '返回', exact: true }).last().click();
      await page.waitForFunction(() => !document.querySelector('.pw-settings-layer:not([inert])'));
      assert.equal((await calls(page, 'accounts.beginLogin')).length, 0);
      assert.equal(await retry.evaluate(el => el === document.activeElement), true);
      await retry.click(); await dialog.waitFor();
      await page.getByRole('button', { name: '用 ChatGPT 登录', exact: true }).click();
      await page.getByText('等待浏览器授权', { exact: true }).waitFor();
      assert.deepEqual((await calls(page, 'accounts.beginLogin')).map(x => x.args), [{ label: '我的常用账号' }]);
      assert.equal((await calls(page, 'accounts.reauthorize')).length, 0);
    });
  }
  await check('registered-expired-single-retry', 430, false, async page => {
    await publish(page, [original]);
    const retry = page.getByRole('button', { name: '重新登录此账号', exact: true });
    await retry.waitFor();
    assert.equal(await page.getByRole('region', { name: '本次授权', exact: true }).getByText(original.label, { exact: true }).count(), 1);
    assert.equal(await page.getByText('reauthorization_required', { exact: true }).count(), 0);
    assert.equal(await page.getByText('LOGIN_EXPIRED', { exact: true }).count(), 0);
    assert.equal(await retry.count(), 1, 'Only one recovery action for the same account');
    await page.evaluate(() => window.modelFixture.setDelay(['accounts.reauthorize']));
    await retry.click();
    await page.waitForFunction(() => window.modelFixture.pending.some(x => x.action === 'accounts.reauthorize'));
    assert.equal(await page.getByRole('region', { name: '本次授权', exact: true }).getByRole('button', { name: '处理中…', exact: true }).isDisabled(), true);
    assert.equal(await page.getByRole('button', { name: '添加账号', exact: true }).isDisabled(), true);
    assert.deepEqual((await calls(page, 'accounts.reauthorize')).map(x => x.args), [{ accountId: original.id, expectedVersion: 7 }]);
    await page.evaluate(() => window.modelFixture.settle('accounts.reauthorize', { status: 'completed', action: 'accounts.reauthorize', command: 'beginLogin', receipt: { sessionId: 'new-session', accountId: 'original-account', status: 'pending' } }));
    await publish(page, [{ ...original, version: 8, status: 'authorizing' }], { ...login, sessionId: 'new-session', status: 'pending' });
    await page.getByText('等待浏览器授权', { exact: true }).waitFor();
    assert.equal((await calls(page, 'accounts.beginLogin')).length, 0);
    assert.equal(await retry.count(), 0);
    await page.getByRole('button', { name: '打开原授权页面', exact: true }).click();
    assert.deepEqual((await calls(page, 'accounts.openAuthorization')).map(x => x.args), [{ sessionId: 'new-session' }]);
  });
  await check('unknown-receipt-refresh-before-retry', 320, true, async page => {
    await publish(page, [original]);
    await page.evaluate(() => window.modelFixture.setDelay(['accounts.reauthorize']));
    const retry = page.getByRole('button', { name: '重新登录此账号', exact: true });
    await retry.click();
    await page.waitForFunction(() => window.modelFixture.pending.some(x => x.action === 'accounts.reauthorize'));
    await page.evaluate(() => window.modelFixture.settle('accounts.reauthorize', { accepted: true }));
    await page.getByText('上次操作结果尚未确认，请先刷新账号状态。', { exact: true }).waitFor();
    assert.equal(await retry.isDisabled(), true);
    assert.equal((await calls(page, 'accounts.reauthorize')).length, 1, 'Unknown results never retry automatically');
    await publish(page, [{ ...original, version: 11 }]);
    await page.getByRole('button', { name: '核对原授权状态', exact: true }).click();
    await page.waitForFunction(() => window.modelFixture.calls.some(x => x.action === 'accounts.refresh'));
    await retry.click();
    assert.deepEqual((await calls(page, 'accounts.reauthorize')).map(x => x.args), [{ accountId: original.id, expectedVersion: 7 }, { accountId: original.id, expectedVersion: 11 }]);
  });
  await check('native-stale-version-during-retry', 430, false, async page => {
    await publish(page, [original]);
    await page.evaluate(() => window.modelFixture.setDelay(['accounts.reauthorize']));
    await page.getByRole('button', { name: '重新登录此账号', exact: true }).click();
    await page.waitForFunction(() => window.modelFixture.pending.some(x => x.action === 'accounts.reauthorize'));
    await publish(page, [{ ...original, version: 12 }]);
    assert.deepEqual((await calls(page, 'accounts.reauthorize')).map(x => x.args), [{ accountId: original.id, expectedVersion: 7 }]);
    await page.evaluate(() => window.modelFixture.settle('accounts.reauthorize', null, 'STALE_ACCOUNT_VERSION'));
    await page.getByText('账号状态已变化。请先刷新账号状态，再重新登录；本次没有沿用旧授权。', { exact: true }).waitFor();
    const retry = page.getByRole('button', { name: '重新登录此账号', exact: true });
    assert.equal(await retry.isDisabled(), true);
    assert.equal((await calls(page, 'accounts.reauthorize')).length, 1);
    await page.getByRole('button', { name: '核对原授权状态', exact: true }).click();
    await retry.click();
    assert.deepEqual((await calls(page, 'accounts.reauthorize')).map(x => x.args), [{ accountId: original.id, expectedVersion: 7 }, { accountId: original.id, expectedVersion: 12 }]);
  });
  await check('disabled-and-unknown-account-safe', 430, false, async page => {
    await publish(page, [{ ...original, enabled: false }]);
    await page.getByText(/此账号已停用，请先在上方启用/).waitFor();
    assert.equal(await page.getByRole('button', { name: '重新登录此账号', exact: true }).count(), 0);
    assert.equal(await page.getByRole('button', { name: '重新开始登录', exact: true }).count(), 0);
    await publish(page, [{ ...original, version: -1 }]);
    await page.getByRole('button', { name: '核对原授权状态', exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: '重新登录此账号', exact: true }).count(), 0);
    await publish(page, undefined);
    await page.getByRole('button', { name: '核对原授权状态', exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: '重新开始登录', exact: true }).count(), 0);
    assert.equal((await calls(page, 'accounts.beginLogin')).length, 0);
  });
} finally { await browser.close(); await server.close(); }
await fs.writeFile(path.join(output, 'results.json'), JSON.stringify({ transport: 'synthetic', observations }, null, 2));
console.log(JSON.stringify({ transport: 'synthetic', observations }, null, 2));
if (observations.some(x => !x.passed)) process.exitCode = 1;
