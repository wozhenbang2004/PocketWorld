import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';
import { chromium } from '@playwright/test';
import { createServer } from 'vite';

// Real App, MemoryWorkspace and React Aria Select. Only native receipts are
// synthetic; this does not establish Android/WebView acceptance.
const server = await createServer({ server: { host: '127.0.0.1', port: 0 } });
let browser;
try {
  await server.listen();
  const origin = server.resolvedUrls.local[0];
  browser = await chromium.launch({ headless: true, ...(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {}), args: ['--no-sandbox'] });
  const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.addInitScript(() => {
    const commands = ['app.ready', 'app.refresh', 'connections.enter', 'connections.leave', 'knowledge.snapshot', 'knowledge.namespaces', 'knowledge.namespace'];
    const snapshot = { state: { messages: [], tasks: [], projects: [], conversations: [], features: {}, modelName: '测试模型' }, composer: null, capabilities: { commands }, view: {} };
    let revision = 0;
    window.__pocketDocumentToken = 'select-back-test';
    window.selectBackRequests = [];
    window.PocketNative = { postMessage(raw) {
      const request = JSON.parse(raw); window.selectBackRequests.push(request);
      const value = request.action === 'knowledge.snapshot' ? { records: [], candidates: [], scopeName: '主人全局 · 私人' }
        : request.action === 'knowledge.namespaces' ? { choices: [{ choiceHandle: 'project-a', name: '项目甲' }] }
        : request.action === 'knowledge.namespace' ? { scopeName: '项目甲' } : {};
      queueMicrotask(() => {
        window.PocketWeb.receive({ v: 1, kind: 'state', revision: ++revision, snapshot });
        window.PocketWeb.receive({ v: 1, kind: 'result', id: request.id, ok: true, value });
      });
    } };
  });
  await page.goto(origin);
  const memory = page.locator('.pw-memory-workspace');
  async function enterMemory() {
    await page.getByRole('button', { name: '打开导航', exact: true }).click();
    await page.getByRole('link', { name: '记忆', exact: true }).click();
    await memory.waitFor();
    await page.getByRole('button', { name: '范围与容量', exact: true }).click();
  }
  const back = () => page.evaluate(() => window.PocketWeb.handleBack());
  await enterMemory();
  const select = page.getByRole('button', { name: '本次查看范围' });
  await select.click();
  await page.getByRole('listbox').waitFor();
  if (process.env.SELECT_BACK_EVIDENCE_DIR) {
    await mkdir(process.env.SELECT_BACK_EVIDENCE_DIR, { recursive: true });
    await page.screenshot({ path: path.join(process.env.SELECT_BACK_EVIDENCE_DIR, 'scope-open.png') });
  }
  assert.equal(await back(), true);
  await page.getByRole('listbox').waitFor({ state: 'hidden' });
  assert.equal(await memory.isVisible(), true, 'Back must close the scope popup, not leave memory');
  await select.waitFor();
  await page.waitForFunction(() => document.activeElement?.getAttribute('aria-haspopup') === 'listbox');
  assert.equal(await page.evaluate(() => window.selectBackRequests.some(r => r.action === 'knowledge.namespace' || r.action === 'connections.leave')), false);
  if (process.env.SELECT_BACK_EVIDENCE_DIR) await page.screenshot({ path: path.join(process.env.SELECT_BACK_EVIDENCE_DIR, 'scope-back-closed.png') });
  // Escape and normal selection still belong to React Aria.
  await select.click(); await page.getByRole('listbox').waitFor(); await page.keyboard.press('Escape');
  await page.getByRole('listbox').waitFor({ state: 'hidden' });
  await page.getByRole('button', { name: '读取可用项目范围', exact: true }).click();
  await select.click(); await page.getByRole('option', { name: '项目甲', exact: true }).click();
  await page.getByRole('listbox').waitFor({ state: 'hidden' });
  assert.equal(await page.evaluate(() => window.selectBackRequests.filter(r => r.action === 'knowledge.namespace').length), 1);
  await select.click(); await page.getByRole('listbox').waitFor(); assert.equal(await back(), true);
  await page.getByRole('listbox').waitFor({ state: 'hidden' }); assert.equal(await memory.isVisible(), true);
  assert.equal(await back(), true); await memory.waitFor({ state: 'hidden' });
  // Unmounted/closed selectors must not swallow later route Back.
  await enterMemory(); assert.equal(await back(), true); await memory.waitFor({ state: 'hidden' });
  assert.equal(await back(), false);
  // A real Select inside a real dialog must consume one Back per visible layer.
  const mountNested = () => page.evaluate(async () => {
    const { mount } = await import('/tests/select-back-fixture.tsx');
    window.unmountNestedSelect = mount();
  });
  await mountNested();
  const dialog = page.getByRole('dialog', { name: '嵌套返回回归', exact: true });
  const nestedSelect = page.getByRole('button', { name: '嵌套范围' });
  await dialog.waitFor();
  await nestedSelect.click(); await page.getByRole('listbox').waitFor();
  if (process.env.SELECT_BACK_EVIDENCE_DIR) await page.screenshot({ path: path.join(process.env.SELECT_BACK_EVIDENCE_DIR, 'nested-select-open.png') });
  assert.equal(await back(), true);
  await page.getByRole('listbox').waitFor({ state: 'hidden' });
  assert.equal(await dialog.isVisible(), true, 'first Back must retain the parent dialog');
  await page.waitForFunction(() => document.activeElement?.getAttribute('aria-haspopup') === 'listbox');
  assert.equal(await back(), true);
  await dialog.waitFor({ state: 'hidden' });
  assert.equal(await back(), false, 'closed dialog and Select must leave no Back handler');
  await page.evaluate(() => window.unmountNestedSelect());
  // Unmount while the Select is still open, then ensure neither layer survives.
  await mountNested(); await dialog.waitFor();
  await nestedSelect.click(); await page.getByRole('listbox').waitFor();
  await page.evaluate(() => window.unmountNestedSelect());
  await page.getByRole('listbox').waitFor({ state: 'hidden' });
  assert.equal(await back(), false, 'open Select unmount must remove its Back handler');
  assert.deepEqual(errors, []);
  console.log('PASS: native bridge Back dismisses real scope Select first; focus, Escape, selection, repeated open, nested dialog Back and open-unmount cleanup retained');
} finally {
  await browser?.close();
  await server.close();
}
