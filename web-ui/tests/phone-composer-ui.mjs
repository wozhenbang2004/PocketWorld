/** Real app controls with synthetic native receipts; no Android or model calls. */
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import { chromium, expect } from '@playwright/test';
import { installAiCenterHost } from './ai-center-host-fixture.mjs';
import { installAiConversationHost } from './ai-conversation-host-fixture.mjs';

const option = (name, fallback) => {
  const index = process.argv.indexOf(name);
  return index < 0 ? fallback : process.argv[index + 1];
};
const root = path.resolve(import.meta.dirname, '..');
const output = path.resolve(option('--output', path.join(root, '../build-artifacts/phone-composer')));
const bundle = path.resolve(option('--bundle', path.join(root, '../app/src/main/assets/pocket-ui')));
let origin = option('--url', null), server, browser;
const results = { synthetic: true, renderer: origin ? 'development' : 'production', checks: [], errors: [] };
await fs.mkdir(output, { recursive: true });
try {
  if (!origin) {
    server = http.createServer(async (request, response) => {
      try {
        const pathname = new URL(request.url, 'http://fixture').pathname;
        const file = path.resolve(bundle, '.' + (pathname === '/' ? '/index.html' : decodeURIComponent(pathname)));
        assert.ok(file.startsWith(bundle + path.sep));
        const body = await fs.readFile(file);
        response.writeHead(200, { 'content-type': ({ '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.woff2': 'font/woff2', '.svg': 'image/svg+xml' })[path.extname(file)] || 'application/octet-stream' });
        response.end(body);
      } catch { response.writeHead(404); response.end(); }
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    origin = 'http://127.0.0.1:' + server.address().port;
  }
  browser = await chromium.launch({ headless: true, ...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH } : {}) });
  for (const freshConversation of [false, true]) for (const mode of ['background', 'main_screen']) {
    const scene = `${freshConversation ? 'draft' : 'conversation'}-${mode}`;
    const fixture = { freshConversation, defaultModel: freshConversation ? 'fixture-reasoner' : '' };
    const selectedModel = freshConversation ? 'fixture-reasoner-B' : 'fixture-reasoner';
    const context = await browser.newContext({ viewport: { width: freshConversation ? 320 : 390, height: 844 },
      isMobile: true, hasTouch: true, locale: 'zh-CN', reducedMotion: 'reduce' });
    const external = [];
    await context.route('**/*', route => {
      if (new URL(route.request().url()).origin === origin) return route.continue();
      external.push(route.request().url()); return route.abort();
    });
    await context.addInitScript({ content: `(${installAiCenterHost.toString()})();(${installAiConversationHost.toString()})(${JSON.stringify({ phoneComposer: fixture })});` });
    const page = await context.newPage();
    page.setDefaultTimeout(8000);
    page.on('pageerror', error => results.errors.push({ scene, message: error.message }));
    const result = { scene, passed: false };
    try {
      await page.goto(origin, { waitUntil: 'networkidle', timeout: 60000 });
      await page.getByRole('button', { name: '输入方式与选项', exact: true }).click();
      await page.getByText(mode === 'background' ? '手机后台独立屏' : '手机主屏操作', { exact: true }).click();
      const editor = page.getByRole('textbox', { name: '工作输入', exact: true });
      const goal = '整理这次手机测试任务';
      await editor.fill(goal);
      await expect.poll(() => page.evaluate(() => window.aiConversationFixture.summary().phoneDraft.mode)).toBe(mode);
      const model = page.getByRole('button', { name: '切换此对话模型：fixture-reasoner-B', exact: true });
      result.beforeSelection = { modelDisabled: await model.isDisabled(),
        sendDisabled: await page.getByRole('button', { name: /^(发送|请先连接模型)$/ }).isDisabled() };
      await page.screenshot({ path: path.join(output, `${scene}-composer.png`), animations: 'disabled' });
      await expect(model).toBeEnabled();
      await expect(page.getByRole('button', { name: '发送', exact: true })).toBeEnabled();
      await model.click();
      const panel = page.getByRole('dialog', { name: '模型与推理', exact: true });
      await expect(panel).toBeVisible();
      await panel.getByRole('button', { name: '选择模型：fixture-reasoner，工作 OpenAI · 测试', exact: true }).click();
      await expect.poll(() => page.evaluate(() => window.aiConversationFixture.summary().preference.modelName)).toBe('fixture-reasoner');
      if (freshConversation) {
        await panel.getByRole('button', { name: '选择模型：fixture-reasoner-B，工作 OpenAI · 测试', exact: true }).click();
        await expect.poll(() => page.evaluate(() => window.aiConversationFixture.summary().preference.modelName)).toBe(selectedModel);
      }
      await panel.getByRole('tab', { name: '推理', exact: true }).click();
      const reasoning = panel.getByRole('radiogroup', { name: '可用推理强度', exact: true });
      await expect(reasoning).toBeVisible();
      await reasoning.getByText('高', { exact: true }).click();
      await expect.poll(() => page.evaluate(() => window.aiConversationFixture.summary().preference.target.reasoning.level)).toBe('high');
      await expect(panel.getByRole('button', { name: '关闭模型选择', exact: true })).toBeEnabled();
      assert.equal(await page.evaluate(() => window.PocketWeb.handleBack()), true);
      await expect(panel).toBeHidden();
      await expect(editor).toHaveValue(goal);
      await page.getByRole('button', { name: '发送', exact: true }).click();
      const review = page.getByRole('dialog', { name: '核对这次手机任务', exact: true });
      await expect(review).toBeVisible();
      await expect(review.getByText(selectedModel, { exact: true })).toBeVisible();
      await page.screenshot({ path: path.join(output, `${scene}-review.png`), animations: 'disabled' });
      await review.getByRole('button', { name: '关闭手机任务核对', exact: true }).click();
      await expect(editor).toHaveValue(goal);
      await page.evaluate(() => window.aiConversationFixture.setPhoneSubmitting(true));
      await expect(page.getByRole('button', { name: `切换此对话模型：${selectedModel}`, exact: true })).toBeDisabled();
      await page.evaluate(() => window.aiConversationFixture.setPhoneSubmitting(false));
      await expect(page.getByRole('button', { name: `切换此对话模型：${selectedModel}`, exact: true })).toBeEnabled();
      await page.getByRole('button', { name: `切换此对话模型：${selectedModel}`, exact: true }).click();
      await expect(panel).toBeVisible();
      await page.evaluate(() => window.aiConversationFixture.setChannelOrigin(true));
      await expect(panel).toBeHidden();
      await expect(page.getByRole('button', { name: `渠道默认模型：${fixture.defaultModel || '未选择'}`, exact: true })).toBeDisabled();
      await expect(page.getByRole('button', { name: /^切换此对话模型：/ })).toHaveCount(0);
      await expect(page.getByRole('button', { name: '选择模型，开始对话', exact: true })).toHaveCount(0);
      assert.equal(await page.getByRole('button', { name: fixture.defaultModel ? '发送' : '请先连接模型', exact: true }).isDisabled(), !fixture.defaultModel);
      await page.evaluate(() => window.aiConversationFixture.setChannelOrigin(false));
      await expect(page.getByRole('button', { name: `切换此对话模型：${selectedModel}`, exact: true })).toBeEnabled();
      await expect(editor).toHaveValue(goal);
      const final = await page.evaluate(() => window.aiConversationFixture.summary());
      const prepares = final.calls.filter(call => call.action === 'phone.prepare');
      assert.deepEqual(prepares.map(call => call.args), [{ goal, mode, epoch: 1 }]);
      assert.equal(final.globalModel, fixture.defaultModel);
      assert.equal(final.preference.modelName, selectedModel);
      assert.notEqual(selectedModel, fixture.defaultModel);
      assert.equal(final.preference.target.reasoning.level, 'high');
      assert.equal(final.composerText, '保留这条测试草稿');
      assert.equal(final.calls.filter(call => ['composer.send', 'models.api.select', 'models.accounts.select'].includes(call.action)).length, 0);
      assert.deepEqual(final.errors, []);
      assert.deepEqual(external, []);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
      result.passed = true;
    } catch (error) {
      result.error = String(error.stack || error);
      await page.screenshot({ path: path.join(output, `${scene}-failure.png`), animations: 'disabled' }).catch(() => {});
    } finally {
      results.checks.push(result);
      await context.close();
    }
  }
} catch (error) { results.errors.push({ message: String(error.stack || error) }); }
finally {
  await browser?.close();
  if (server) await new Promise(resolve => server.close(resolve));
  results.passed = results.checks.length === 4 && results.checks.every(check => check.passed) && !results.errors.length;
  await fs.writeFile(path.join(output, 'results.json'), JSON.stringify(results, null, 2) + '\n');
}
console.log(JSON.stringify(results));
if (!results.passed) process.exitCode = 1;
