/**
 * Actual production bundle + SYNTHETIC native host. Intended for CI Chromium.
 * Run from repo root: node web-ui/tests/ai-center-ui.mjs --output build-artifacts/ai-center-visual
 * Build the production assets first, or pass --bundle <existing production bundle>.
 * This does not validate Android insets, real provider calls, listener sockets or clipboard writes.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { chromium, expect } from '@playwright/test';
import { installAiCenterHost } from './ai-center-host-fixture.mjs';

const root = path.resolve(import.meta.dirname, '..');
function argument(name, fallback) {
  const at = process.argv.indexOf(name);
  if (at < 0) return fallback;
  const value = process.argv[at + 1];
  if (!value || value.startsWith('--')) throw new Error(`Missing value for ${name}`);
  return path.resolve(value);
}
const bundle = argument('--bundle', path.resolve(root, '../app/src/main/assets/pocket-ui'));
const output = argument('--output', path.resolve(root, '../build-artifacts/ai-center-visual'));
const record = {
  scope: 'Production React renderer at 390x844, light/dark, with synthetic native receipts and nativeInsetsHandledByNative=true. No Android/device, provider, login, listener, clipboard, usage or billing validation.',
  synthetic: true, viewport: { width: 390, height: 844 }, startedAt: new Date().toISOString(),
  outcome: 'not_run', checks: [], screenshots: [], errors: [], scenes: [],
};
let server, browser;
await fs.mkdir(output, { recursive: true });
const passed = (scene, name, evidence) => record.checks.push({ scene, name, passed: true, ...(evidence ? { evidence } : {}) });

async function runScene(origin, dark, empty) {
  const scene = `390-${dark ? 'dark' : 'light'}-${empty ? 'empty' : 'configured'}`;
  const context = await browser.newContext({ viewport: record.viewport, isMobile: true, hasTouch: true,
    deviceScaleFactor: 1, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', colorScheme: dark ? 'dark' : 'light',
    reducedMotion: 'reduce', serviceWorkers: 'block' });
  const externalRequests = [];
  await context.route('**/*', route => {
    if (new URL(route.request().url()).origin === origin) return route.continue();
    externalRequests.push(route.request().url()); return route.abort();
  });
  await context.addInitScript(installAiCenterHost, { dark, empty });
  const page = await context.newPage();
  page.setDefaultTimeout(12000);
  page.on('pageerror', error => record.errors.push({ scene, error: error.message }));
  const shot = async label => {
    await page.evaluate(() => document.fonts.ready);
    await page.mouse.move(0, 0);
    const file = `${scene}-${label}.png`;
    await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
    record.screenshots.push({ scene, file, synthetic: true });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), true, `${file}: horizontal overflow`);
  };
  const center = () => page.locator('.pw-ai-center');
  const count = action => page.evaluate(action => window.aiCenterFixture.calls.filter(row => row.action === action).length, action);
  const calls = action => page.evaluate(action => window.aiCenterFixture.calls.filter(row => row.action === action).map(row => row.args), action);
  const back = () => page.locator('.pw-secondary-header').getByRole('button', { name: '返回', exact: true }).click();
  try {
    await page.goto(origin, { waitUntil: 'networkidle' });
    const input = page.getByRole('textbox', { name: '工作输入', exact: true });
    await input.waitFor();
    await page.waitForFunction(() => document.documentElement.dataset.pocketNativeInsets === 'true');
    await expect(page.locator('html')).toHaveClass(dark ? /dark/ : /^(?!.*\bdark\b)/);
    const layout = await page.evaluate(() => {
      const app = document.querySelector('.pw-app'), header = document.querySelector('.pw-app-header'), card = document.querySelector('.pw-composer-card');
      if (!app || !header || !card) throw new Error('Production chat surfaces missing');
      const appStyle = getComputedStyle(app), headerStyle = getComputedStyle(header), style = getComputedStyle(card);
      return { appTop: app.getBoundingClientRect().top, headerTop: header.getBoundingClientRect().top,
        headerHeight: header.getBoundingClientRect().height, appPaddingTop: parseFloat(appStyle.paddingTop),
        headerPaddingTop: parseFloat(headerStyle.paddingTop), insetVariable: getComputedStyle(document.documentElement).getPropertyValue('--pocket-inset-top').trim(),
        borders: ['Top', 'Right', 'Bottom', 'Left'].map(side => ({ width: parseFloat(style['border' + side + 'Width']), color: style['border' + side + 'Color'] })) };
    });
    assert.ok(Math.abs(layout.headerTop - layout.appTop) <= 1, 'Chat header must start at the native-sized content edge');
    assert.equal(layout.appPaddingTop, 0, 'Native-owned system insets must not be added again by the Web shell');
    assert.equal(layout.insetVariable, '0px');
    assert.ok(layout.headerHeight >= 48 && layout.headerHeight <= 72, `Unexpected header blank area: ${layout.headerHeight}`);
    assert.ok(layout.headerPaddingTop <= 8, 'Header must not contain a second safe-area spacer');
    assert.ok(layout.borders.every(edge => edge.width === 0 || edge.color === 'transparent' || /rgba\([^)]*,\s*0\)$/.test(edge.color)), 'Composer has a visible border');
    await input.focus();
    const focused = await page.locator('.pw-composer-card').evaluate(node => {
      const s = getComputedStyle(node); return ['Top', 'Right', 'Bottom', 'Left'].map(side => ({ width: parseFloat(s['border' + side + 'Width']), color: s['border' + side + 'Color'] }));
    });
    assert.ok(focused.every(edge => edge.width === 0 || edge.color === 'transparent' || /rgba\([^)]*,\s*0\)$/.test(edge.color)), 'Focused composer has a visible frame');
    await input.evaluate(node => node.blur());
    passed(scene, 'Native-inset shell has no repeated top padding; composer is borderless at rest and focus', layout);
    await shot('chat');
    const draft = await input.inputValue();
    await page.locator('.pw-conversation-model-chip').click();
    const picker = page.getByRole('region', { name: '统一模型目录', exact: true });
    await picker.waitFor();
    await expect(picker.getByText('正在读取来源与模型', { exact: true })).toHaveCount(0);
    await expect(picker.getByRole('tab', { name: '全部', exact: true })).toBeVisible();
    await expect(picker.getByRole('tab', { name: '收藏', exact: true })).toBeVisible();
    await expect(picker.getByRole('tab', { name: '最近使用', exact: true })).toBeVisible();
    if (empty) await picker.getByRole('heading', { name: '添加你的第一个 AI 服务', exact: true }).waitFor();
    else {
      await picker.getByRole('button', { name: /fixture-reasoner/ }).waitFor();
      await picker.getByRole('button', { name: /个人 ChatGPT · 测试/ }).waitFor();
      await picker.getByRole('button', { name: /日常任务 · 测试/ }).waitFor();
    }
    await shot('unified-picker');
    if (!empty) {
      await picker.getByRole('button', { name: /fixture-reasoner/ }).click();
      await picker.getByRole('button', { name: '用于本对话', exact: true }).waitFor();
      await shot('model-review');
      await picker.getByRole('button', { name: '返回模型', exact: true }).click();
      await picker.getByRole('button', { name: /个人 ChatGPT · 测试/ }).click();
      await picker.getByRole('button', { name: /套餐模型 · 测试/ }).waitFor();
      passed(scene, 'Unified picker renders API, group and same-window account catalog identities');
    }
    await picker.getByRole('button', { name: '管理 AI 接入', exact: true }).click();
    await center().getByRole('heading', { name: '你的服务', exact: true }).waitFor();
    await expect(page.getByRole('dialog', { name: '选择模型', exact: true })).toHaveCount(0);
    await expect(page.locator('.pw-secondary-header').getByRole('heading', { name: 'AI 接入', exact: true })).toBeVisible();
    assert.equal(await page.evaluate(() => window.aiCenterFixture.summary().synthetic), true);
    if (empty) await center().getByRole('heading', { name: '连接你的第一个 AI 服务', exact: true }).waitFor();
    else await center().getByRole('button', { name: /工作 OpenAI · 测试/ }).waitFor();
    await shot('services');
    await center().getByRole('tab', { name: '模型', exact: true }).click();
    if (empty) await center().getByRole('heading', { name: '添加你的第一个 AI 服务', exact: true }).waitFor();
    else await center().getByRole('button', { name: /fixture-reasoner/ }).waitFor();
    await shot('models');
    await center().getByRole('tab', { name: '调度', exact: true }).click();
    await center().getByRole('heading', { name: '模型组', exact: true }).waitFor();
    if (empty) await center().getByRole('heading', { name: '按用途组合多个模型', exact: true }).waitFor();
    else {
      await center().getByRole('button', { name: /日常任务 · 测试/ }).waitFor();
      await shot('scheduling');
      await center().getByRole('button', { name: /日常任务 · 测试/ }).click();
      await center().getByRole('heading', { name: '编辑模型组', exact: true }).waitFor();
      await expect(center().getByLabel('模型组名称', { exact: true })).toHaveValue('日常任务 · 测试');
      await expect(center().locator('.pw-ai-candidate-row')).toHaveCount(2);
      await shot('scheduling-editor');
      await center().getByRole('button', { name: '取消', exact: true }).click();
    }
    if (empty) await shot('scheduling');
    passed(scene, 'Picker navigates to one AI center with services, models and scheduling');
    await center().getByRole('tab', { name: '服务', exact: true }).click();
    await center().locator('header.pw-ai-section-heading').getByRole('button', { name: '添加服务', exact: true }).click();
    await center().getByRole('heading', { name: '添加服务', exact: true }).waitFor();
    for (const name of ['OpenAI', 'Anthropic', 'Gemini', '自定义服务', 'AWS Bedrock']) await expect(center().getByRole('button', { name: new RegExp('^' + name) })).toBeVisible();
    await shot('provider-directory');
    await center().getByRole('button', { name: /^OpenAI/ }).click();
    await center().getByRole('button', { name: /^登录 ChatGPT/ }).waitFor();
    await center().getByRole('button', { name: /^添加 API 密钥/ }).waitFor();
    await shot('openai-method');
    await center().getByRole('button', { name: /^添加 API 密钥/ }).click();
    const editor = page.getByRole('region', { name: '新连接配置', exact: true });
    await editor.waitFor();
    await expect(editor.getByLabel('服务名称', { exact: true })).toHaveValue('OpenAI API');
    await expect(editor.getByLabel('接口地址', { exact: true })).toHaveValue('https://api.openai.com/v1');
    await expect(editor.getByLabel('API 密钥', { exact: true })).toHaveAttribute('type', 'password');
    await shot('add-source');
    if (empty) {
      passed(scene, 'Empty states lead to provider and authentication choice without inventing models');
      await back(); await back(); await back();
    } else {
      await editor.getByLabel('服务名称', { exact: true }).fill('新增 OpenAI · 测试');
      await editor.getByLabel('API 密钥', { exact: true }).fill('fixture-key-not-a-real-secret');
      await page.getByRole('button', { name: '保存服务', exact: true }).click();
      await center().locator('[data-ai-view]').count();
      await expect(center()).toHaveAttribute('data-ai-view', 'connection');
      await center().getByRole('heading', { name: '新增 OpenAI · 测试', exact: true }).waitFor();
      const submitted = await calls('models.connections.add');
      assert.equal(submitted.length, 1); assert.equal(submitted[0].provider, 'openai_responses');
      assert.equal(submitted[0].url, 'https://api.openai.com/v1'); assert.equal(submitted[0].model, '');
      assert.equal(submitted[0].apiKey, 'fixture-key-not-a-real-secret');
      assert.equal(await page.getByText('fixture-key-not-a-real-secret', { exact: true }).count(), 0);
      assert.equal(await count('models.connection.test'), 0);
      assert.equal(await count('models.api.select'), 0);
      await shot('source-saved');
      passed(scene, 'Provider preset reaches actual source form; saving source does not test inference or choose a default');
      await back();
    }
    await center().getByRole('button', { name: '本机 API', exact: true }).click();
    const proxy = page.locator('.pw-proxy-workspace');
    await proxy.getByRole('heading', { name: '服务尚未运行', exact: true }).waitFor();
    assert.equal(await count('proxy.confirmStart'), 0);
    await shot('local-api-stopped');
    if (empty) {
      await expect(proxy.getByRole('button', { name: '配置并启动', exact: true })).toBeDisabled();
      await expect(proxy.getByText('先在 AI 接入中选择明确的 API 模型。', { exact: true })).toBeVisible();
      passed(scene, 'Unconfigured local API remains stopped with a concrete setup next step');
    } else {
      await proxy.getByRole('button', { name: '配置并启动', exact: true }).click();
      const setup = page.getByRole('dialog', { name: '启动本机 API', exact: true });
      await setup.waitFor();
      await expect(setup.getByLabel('监听端口', { exact: true })).toHaveValue('0');
      await expect(setup.getByRole('button', { name: '核对启动配置', exact: true })).toBeDisabled();
      await setup.getByRole('button', { name: '生成访问令牌', exact: true }).click();
      await setup.getByRole('button', { name: '生成新令牌', exact: true }).waitFor();
      assert.deepEqual(await calls('proxy.generateToken'), [{ tokenRevision: 'fixture-token-revision-0' }]);
      await setup.getByRole('button', { name: '复制令牌', exact: true }).click();
      await expect.poll(() => count('proxy.copyToken')).toBe(1);
      assert.deepEqual((await calls('proxy.copyToken'))[0], { tokenRevision: 'fixture-token-revision-1' });
      assert.equal(await count('proxy.confirmStart'), 0, 'Token operations must not start a listener');
      await shot('local-api-token-setup');
      await setup.getByRole('button', { name: '核对启动配置', exact: true }).click();
      const review = page.getByRole('dialog', { name: '核对本次本机服务', exact: true });
      await review.waitFor();
      await expect(review.getByRole('button', { name: '启动本机 API', exact: true })).toBeDisabled();
      await shot('local-api-review');
      await review.getByRole('checkbox', { name: '确认本次模型与本机访问范围', exact: true }).check();
      await review.getByRole('button', { name: '启动本机 API', exact: true }).click();
      await proxy.getByRole('heading', { name: '服务正在运行', exact: true }).waitFor();
      await expect(proxy.getByText('http://127.0.0.1:18743', { exact: true })).toBeVisible();
      await expect(proxy.getByText('http://127.0.0.1:18743/v1', { exact: true })).toBeVisible();
      await expect(proxy.getByText('Authorization · Bearer', { exact: true })).toBeVisible();
      await proxy.getByRole('button', { name: '复制访问令牌', exact: true }).click();
      await expect.poll(() => count('proxy.copyToken')).toBe(2);
      assert.deepEqual((await calls('proxy.copyToken'))[1], { tokenRevision: 'fixture-token-revision-1', sessionId: 'fixture-proxy-session-1' });
      await shot('local-api-running');
      await proxy.getByRole('button', { name: /^请求地址与兼容范围/ }).click();
      await proxy.getByText('http://127.0.0.1:18743/v1/chat/completions', { exact: true }).waitFor();
      await shot('local-api-endpoints');
      await proxy.getByRole('button', { name: '停止这次本机服务', exact: true }).click();
      await proxy.getByRole('heading', { name: '服务尚未运行', exact: true }).waitFor();
      assert.deepEqual(await calls('proxy.stop'), [{ sessionId: 'fixture-proxy-session-1' }]);
      assert.equal(await count('proxy.confirmStart'), 1);
      assert.equal(await count('proxy.reviewStart'), 1);
      passed(scene, 'Local API shows protocol/base URL/auth/endpoints; opaque token revision and original session survive generate/copy/review/start/stop');
    }
    const summary = await page.evaluate(() => window.aiCenterFixture.summary());
    assert.deepEqual(summary.errors, []);
    assert.deepEqual(externalRequests, [], 'Renderer fixture must not contact a real provider or service');
    assert.equal(await page.evaluate(() => window.aiCenterFixture.calls.some(row => row.action === 'composer.send')), false);
    await back();
    // A real navigation return, rather than changing React state in the test fixture.
    await back();
    await input.waitFor();
    assert.equal(await input.inputValue(), draft, 'Management navigation must preserve the original composer draft');
    passed(scene, 'Management navigation preserves the draft and issues no inference');
    record.scenes.push({ scene, passed: true, fixture: summary });
  } catch (error) {
    record.checks.push({ scene, name: 'Renderer journey', passed: false, error: String(error.stack || error) });
    const file = `${scene}-failure.png`;
    try { await page.screenshot({ path: path.join(output, file), animations: 'disabled' }); record.screenshots.push({ scene, file, synthetic: true, failure: true }); } catch {}
    record.scenes.push({ scene, passed: false, fixture: await page.evaluate(() => window.aiCenterFixture?.summary()).catch(() => null) });
  } finally { await context.close(); }
}

try {
  const index = await fs.readFile(path.join(bundle, 'index.html'), 'utf8');
  const asset = index.match(/assets\/(index-[^" ]+\.js)/)?.[1];
  assert.ok(asset, 'Expected an existing Vite production bundle, not source/dev HTML');
  record.bundle = asset;
  record.bundleSha256 = createHash('sha256').update(await fs.readFile(path.join(bundle, 'assets', asset))).digest('hex');
  record.indexSha256 = createHash('sha256').update(index).digest('hex');
  server = http.createServer(async (request, response) => {
    try {
      const url = new URL(request.url, 'http://fixture');
      const file = path.resolve(bundle, '.' + (url.pathname === '/' ? '/index.html' : decodeURIComponent(url.pathname)));
      if (!file.startsWith(bundle + path.sep)) throw new Error('Invalid bundle path');
      const body = await fs.readFile(file);
      response.writeHead(200, { 'content-type': ({ '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.ttf': 'font/ttf', '.otf': 'font/otf', '.woff2': 'font/woff2', '.svg': 'image/svg+xml' })[path.extname(file)] || 'application/octet-stream', 'cache-control': 'no-store' });
      response.end(body);
    } catch { response.writeHead(404); response.end(); }
  });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  const origin = 'http://127.0.0.1:' + server.address().port;
  // One normal launch. No local retry, sandbox override or alternate browser workaround.
  browser = await chromium.launch({ headless: true });
  record.outcome = 'executed';
  for (const dark of [false, true]) for (const empty of [false, true]) await runScene(origin, dark, empty);
} catch (error) {
  record.errors.push({ stage: browser ? 'runner' : 'setup', error: String(error.stack || error) });
} finally {
  if (browser) await browser.close();
  if (server?.listening) await new Promise(resolve => server.close(resolve));
  record.finishedAt = new Date().toISOString();
  record.passed = record.outcome === 'executed' && record.scenes.length === 4 && record.scenes.every(scene => scene.passed) && !record.errors.length;
  await fs.writeFile(path.join(output, 'results.json'), JSON.stringify(record, null, 2) + '\n');
}
console.log(JSON.stringify(record));
if (!record.passed) process.exitCode = 1;
