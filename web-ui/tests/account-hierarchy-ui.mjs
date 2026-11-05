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
  for (const width of [320, 430, 1280]) {
    const page = await browser.newPage({ viewport: { width, height: 850 } });
    await page.goto(server.resolvedUrls.local[0] + 'tests/model-account-fixture.html');
    const desktop = width >= 1024;
    const openNavigation = async () => {
      if (!desktop) await page.getByRole('button', { name: '打开导航', exact: true }).click();
      await page.getByRole('navigation', { name: '主导航', exact: true }).waitFor();
    };
    await openNavigation();
    const primary = page.getByRole('navigation', { name: '主导航', exact: true });
    assert.deepEqual(await primary.getByRole('link').evaluateAll(nodes => nodes.map(node => node.getAttribute('aria-label'))), ['对话', '任务', '项目']);
    const rows = await primary.getByRole('link').evaluateAll(nodes => nodes.map(node => { const r = node.getBoundingClientRect(); return { x: r.x, y: r.y }; }));
    assert.ok(rows.every(row => Math.abs(row.x - rows[0].x) < 1) && rows[0].y < rows[1].y && rows[1].y < rows[2].y, 'Primary navigation remains a vertical side list');
    const navigation = page.locator(desktop ? '.pw-desktop-navigation' : '.pw-work-drawer');
    const bounds = await navigation.boundingBox();
    assert.ok(bounds.x < 1 && bounds.width <= 320 && bounds.height > 800, 'Navigation opens along the side rather than the bottom');
    assert.equal(await page.getByRole('button', { name: /打开原生工作台|网页工作台/ }).count(), 0);
    const tools = navigation.getByRole('button', { name: '更多工具', exact: true });
    assert.equal(await tools.getAttribute('aria-expanded'), 'false');
    await tools.click();
    const secondary = page.getByRole('navigation', { name: '更多工具', exact: true });
    await secondary.getByRole('link', { name: '记忆', exact: true }).waitFor();
    assert.deepEqual(await secondary.getByRole('link').evaluateAll(nodes => nodes.map(node => node.getAttribute('aria-label'))), ['团队', '记忆', '任务历史']);
    assert.equal(await primary.getByRole('link').count(), 3, 'Tools never become competing primary routes');
    assert.equal(await navigation.getByRole('link', { name: '设置', exact: true }).isVisible(), true);
    await primary.getByRole('link', { name: '任务', exact: true }).click();
    await page.locator('.pw-app[data-route="tasks"]').waitFor();
    if (!desktop) await navigation.waitFor({ state: 'hidden' });
    await openNavigation();
    await primary.getByRole('link', { name: '项目', exact: true }).click();
    await page.locator('.pw-app[data-route="projects"]').waitFor();
    await openNavigation();
    await primary.getByRole('link', { name: '对话', exact: true }).click();
    await page.locator('.pw-app[data-route="chat"]').waitFor();
    await openNavigation();
    await page.getByRole('button', { name: '搜索工作', exact: true }).click();
    const search = page.getByRole('combobox', { name: '搜索已加载工作与功能', exact: true });
    await search.fill('项目');
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => document.activeElement?.getAttribute('aria-label') === '搜索工作');
    assert.equal(await page.getByRole('button', { name: '搜索工作', exact: true }).evaluate(node => node === document.activeElement), true, 'Search dismissal restores its navigation trigger');
    if (!desktop) {
      assert.equal(await page.evaluate(() => window.PocketWeb.handleBack()), true);
      await navigation.waitFor({ state: 'hidden' });
      assert.equal(await page.getByRole('button', { name: '打开导航', exact: true }).evaluate(node => node === document.activeElement), true);
    }
    assert.equal(await page.evaluate(() => window.modelFixture.calls.some(call => call.action === 'app.openNativeWorkspace')), false, 'An old advertised capability cannot reopen a second frontend');
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    observations.push({ width, navigation: desktop ? 'permanent sidebar' : 'Vaul side drawer', primaryRoutes: 3, toolsDisclosure: 'passed', routeAndFocusContinuity: 'passed' });
    await page.close();
  }
  console.log(JSON.stringify({ browser: 'Edge', transport: 'synthetic', observations }, null, 2));
} finally { await browser.close(); await server.close(); }
