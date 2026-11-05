/** Current production components in headless Edge; synthetic transport, no OAuth or Android. */
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { createServer } from 'vite';
const root = fileURLToPath(new URL('..', import.meta.url));
const server = await createServer({ root, logLevel: 'error', server: { host: '127.0.0.1', port: 0 } });
await server.listen();
const browser = await chromium.launch({ channel: 'msedge', headless: true, args: ['--disable-background-networking'] });
const observations = [];
try {
  for (const [width, theme] of [[430, 'light'], [320, 'dark']]) {
    const page = await browser.newPage({ viewport: { width, height: 850 }, reducedMotion: 'reduce' });
    await page.goto(server.resolvedUrls.local[0] + 'tests/model-account-fixture.html?view=accounts');
    await page.getByRole('button', { name: '添加账号', exact: true }).waitFor();
    await page.evaluate(dark => document.documentElement.classList.toggle('dark', dark), theme === 'dark');
    assert.equal(await page.getByRole('heading', { name: 'ChatGPT 账号', exact: true }).count(), 1, 'The page should have one account title');
    const lead = await page.locator('.pw-accounts-workspace > .pw-reading-lead').boundingBox();
    assert.ok(lead.width > width * .7, 'The description should use the available reading width');
    const add = page.getByRole('button', { name: '添加账号', exact: true });
    await add.click();
    const dialog = page.getByRole('dialog', { name: '添加 ChatGPT 账号', exact: true });
    await dialog.waitFor();
    await page.waitForFunction(() => document.querySelector('[role="dialog"]')?.contains(document.activeElement));
    await page.getByRole('textbox', { name: '账号名称（可选）', exact: true }).fill('一个较长的本机账号名称'.repeat(5));
    const bounds = await dialog.boundingBox();
    assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= width + 1);
    for (let i = 0; i < 8; i++) {
      await page.keyboard.press('Tab');
      assert.equal(await dialog.evaluate(el => el.contains(document.activeElement)), true, 'Tab must remain inside the modal');
    }
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => !document.querySelector('.pw-settings-layer:not([inert])'));
    assert.equal(await add.evaluate(el => el === document.activeElement), true, 'Closing returns focus to Add account');
    await add.click(); await dialog.waitFor();
    await page.getByRole('button', { name: '返回', exact: true }).last().click();
    await page.waitForFunction(() => !document.querySelector('.pw-settings-layer:not([inert])'));
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    observations.push({ width, theme, titleCount: 1, leadWidth: lead.width, modalWidth: bounds.width, focusAndClose: 'passed' });
    await page.close();
  }
  const page = await browser.newPage({ viewport: { width: 430, height: 850 } });
  await page.goto(server.resolvedUrls.local[0] + 'tests/model-account-fixture.html');
  await page.getByRole('button', { name: '打开导航', exact: true }).click();
  await page.getByRole('button', { name: '打开原生工作台', exact: true }).click();
  await page.waitForFunction(() => window.modelFixture.calls.some(call => call.action === 'app.openNativeWorkspace'));
  observations.push({ navigationRecoveryEntry: 'dispatched through the host capability' });
  await page.close();
  console.log(JSON.stringify({ browser: 'Edge', transport: 'synthetic', observations }, null, 2));
} finally { await browser.close(); await server.close(); }
