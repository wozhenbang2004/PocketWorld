/** Mounted production UI + an explicitly synthetic host. No Android or real account. */
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import http from 'node:http';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { createServer } from 'vite';
import { installModelAccountHost } from './model-account-host-fixture.mjs';

const root = fileURLToPath(new URL('..', import.meta.url));
const output = process.env.MODEL_ACCOUNT_EVIDENCE || path.resolve(root, '../build-artifacts/model-account-ui');
await fs.mkdir(output, { recursive: true });
const bundle = process.env.MODEL_ACCOUNT_BUNDLE && path.resolve(process.env.MODEL_ACCOUNT_BUNDLE);
let server, origin, bundleIdentity;
if (bundle) {
  const index = await fs.readFile(path.join(bundle, 'index.html'), 'utf8');
  const entry = index.match(/assets\/(index-[^" ]+\.js)/)?.[1];
  assert.ok(entry, 'The production entry must be identifiable');
  bundleIdentity = { bundle, entry, sha256: createHash('sha256').update(await fs.readFile(path.join(bundle, 'assets', entry))).digest('hex') };
  server = http.createServer(async (request, response) => {
    try {
      const name = new URL(request.url, 'http://fixture').pathname;
      const file = path.resolve(bundle, '.' + (name === '/' ? '/index.html' : name));
      if (!file.startsWith(bundle + path.sep)) throw new Error('Invalid fixture asset');
      const content = await fs.readFile(file);
      response.writeHead(200, { 'content-type': ({ '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.woff2': 'font/woff2', '.json': 'application/json', '.svg': 'image/svg+xml' })[path.extname(file)] || 'application/octet-stream' });
      response.end(content);
    } catch { response.writeHead(404); response.end(); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  origin = 'http://127.0.0.1:' + server.address().port + '/';
} else {
  server = await createServer({ root, server: { host: '127.0.0.1', port: 0 }, logLevel: 'error' });
  await server.listen(); origin = server.resolvedUrls.local[0];
}
const browser = await chromium.launch({ channel: process.env.UI_BROWSER_CHANNEL || undefined, headless: true, args: ['--disable-background-networking'] });
const results = [];
const baseline = process.argv.includes('--baseline');
async function navigate(page, name) {
  await page.getByRole('button', { name: '打开导航', exact: true }).click();
  const drawer = page.locator('.pw-work-drawer');
  await drawer.getByRole('link', { name, exact: true }).click();
  await drawer.waitFor({ state: 'hidden' });
}
async function check(name, work, view = '') {
  const page = await browser.newPage({ viewport: { width: 430, height: 900 }, deviceScaleFactor: 1, reducedMotion: 'reduce' });
  page.setDefaultTimeout(8000);
  try {
    if (bundle) {
      await page.route('**/*', route => new URL(route.request().url()).origin === new URL(origin).origin ? route.continue() : route.abort());
      await page.addInitScript(installModelAccountHost, view);
    }
    await page.goto(bundle ? origin : origin + 'tests/model-account-fixture.html' + (view ? '?view=' + view : ''));
    await page.waitForFunction(() => !!window.modelFixture, null, { timeout: 15000 });
    if (bundle) await page.waitForFunction(() => document.documentElement.dataset.pocketUi === 'ready');
    await work(page);
    if (bundle) assert.deepEqual(await page.evaluate(() => window.modelFixture.errors), []);
    results.push({ name, passed: true });
  } catch (error) { results.push({ name, passed: false, error: String(error.message) }); }
  finally { if (process.env.UI_NO_SCREENSHOTS !== '1') await page.screenshot({ path: path.join(output, name + '.png'), fullPage: true }); await page.close(); }
}
try {
  await check('account-new-label', async page => {
    await page.getByRole('button', { name: '添加账号', exact: true }).click();
    if (!baseline || await page.getByRole('button', { name: '用 ChatGPT 登录', exact: true }).count()) await page.getByRole('button', { name: '用 ChatGPT 登录', exact: true }).click();
    await page.waitForFunction(() => window.modelFixture.calls.some(call => call.action === 'accounts.beginLogin'));
    const args = await page.evaluate(() => window.modelFixture.calls.find(call => call.action === 'accounts.beginLogin').args);
    assert.equal(args.label, '我的 ChatGPT 账号', 'New account needs the native nonempty label before authorization can start');
    await page.getByText('本次授权', { exact: true }).waitFor();
  }, 'accounts');
  await check('api-models-retain-connection-identity', async page => {
    await page.getByRole('button', { name: '切换模型：same-model', exact: true }).click();
    await page.getByText('备用连接', { exact: false }).first().waitFor({ timeout: 2500 });
    assert.ok(await page.getByText('工作连接', { exact: false }).count());
    assert.ok(await page.getByText('最近使用', { exact: false }).count());
    assert.ok(await page.getByText('图片', { exact: false }).count());
  });
  await check('old-create-receipt-keeps-new-dialog', async page => {
    await navigate(page, '项目');
    await page.getByRole('button', { name: '新项目', exact: true }).click();
    await page.getByRole('textbox', { name: '项目名称', exact: true }).fill('第一次创建');
    await page.getByRole('button', { name: '创建', exact: true }).click();
    await page.waitForFunction(() => window.modelFixture.pending.some(call => call.action === 'projects.create'));
    await page.getByRole('button', { name: '关闭创建', exact: true }).click();
    await page.getByRole('button', { name: '新项目', exact: true }).click();
    await page.getByRole('textbox', { name: '项目名称', exact: true }).fill('保留第二次草稿');
    await page.evaluate(() => window.modelFixture.settle('projects.create', { accepted: true }));
    await page.waitForTimeout(50);
    assert.equal(await page.getByRole('textbox', { name: '项目名称', exact: true }).inputValue({ timeout: 1500 }), '保留第二次草稿');
  });
  if (!baseline) {
    await check('account-final-failure-is-visible', async page => {
      await page.evaluate(() => window.modelFixture.setDelay(['accounts.beginLogin']));
      await page.getByRole('button', { name: '添加账号', exact: true }).click();
      await page.getByRole('button', { name: '用 ChatGPT 登录', exact: true }).click();
      await page.waitForFunction(() => window.modelFixture.pending.some(call => call.action === 'accounts.beginLogin'));
      await page.evaluate(() => window.modelFixture.settle('accounts.beginLogin', null, '服务商暂不可用，请稍后重新核对'));
      await page.getByText('服务商暂不可用，请稍后重新核对', { exact: false }).first().waitFor();
      assert.equal(await page.getByText('请求已登记，等待原操作回执。', { exact: true }).count(), 0);
    }, 'accounts');

    await check('account-final-result-preserves-label-and-waits', async page => {
      await page.evaluate(() => window.modelFixture.setDelay(['accounts.beginLogin']));
      await page.getByRole('button', { name: '添加账号', exact: true }).click();
      await page.getByRole('textbox', { name: '账号名称（可选）', exact: true }).fill('我的工作账号');
      await page.getByRole('button', { name: '用 ChatGPT 登录', exact: true }).click();
      await page.waitForFunction(() => window.modelFixture.pending.some(call => call.action === 'accounts.beginLogin'));
      assert.equal(await page.getByRole('button', { name: '正在准备授权…', exact: true }).isDisabled(), true);
      await page.evaluate(() => window.modelFixture.settle('accounts.beginLogin', null, '服务商暂不可用'));
      assert.equal(await page.getByRole('textbox', { name: '账号名称（可选）', exact: true }).inputValue(), '我的工作账号');
      await page.getByRole('button', { name: '用 ChatGPT 登录', exact: true }).click();
      await page.waitForFunction(() => window.modelFixture.pending.some(call => call.action === 'accounts.beginLogin'));
      await page.evaluate(() => window.modelFixture.settle('accounts.beginLogin', { status: 'completed', action: 'accounts.beginLogin', command: 'beginLogin', receipt: { status: 'pending', sessionId: 'original-session', accountId: 'original-account' } }));
      await page.getByText('授权已准备。', { exact: false }).first().waitFor();
      await page.getByRole('textbox', { name: '账号名称（可选）', exact: true }).waitFor({ state: 'hidden' });
    }, 'accounts');
    await check('api-selection-keeps-original-connection', async page => {
      await page.getByRole('button', { name: '切换模型：same-model', exact: true }).click();
      await page.getByRole('option', { name: /备用连接/ }).click();
      await page.getByRole('button', { name: '确认切换主模型', exact: true }).click();
      await page.getByRole('button', { name: '关闭模型选择', exact: true }).waitFor({ state: 'hidden' });
      const calls = await page.evaluate(() => window.modelFixture.calls);
      const review = calls.find(call => call.action === 'models.api.review');
      assert.deepEqual(review.args, { windowId: 'api-window-1', connectionId: 'api-b', modelId: 'same-model', expectedRevision: 5 });
      assert.deepEqual(calls.find(call => call.action === 'models.api.select').args, { windowId: 'api-window-1', reviewHandle: 'review-api-b' });
      const models = await page.evaluate(() => window.modelFixture.getModels());
      assert.equal(models.find(row => row.connectionId === 'api-a').lastUsedAt, 0);
      assert.equal(models.find(row => row.selected).connectionId, 'api-b');
    });
    await check('api-favorite-recent-and-disabled-model', async page => {
      await page.getByRole('button', { name: '切换模型：same-model', exact: true }).click();
      await page.getByText('最近使用', { exact: true }).click();
      assert.equal(await page.getByRole('option').count(), 1);
      await page.getByRole('option', { name: /备用连接/ }).click();
      await page.getByRole('button', { name: '取消收藏', exact: true }).click();
      await page.getByRole('option', { name: /备用连接/ }).waitFor();
      await page.getByText('收藏', { exact: true }).click();
      await page.getByRole('option', { name: /已停用连接/ }).click();
      assert.equal(await page.getByRole('button', { name: '确认切换主模型', exact: true }).count(), 0);
      await page.getByRole('button', { name: '取消收藏', exact: true }).click();
      await page.getByText('尚无收藏模型。', { exact: false }).waitFor();
      const calls = await page.evaluate(() => window.modelFixture.calls);
      assert.equal(calls.filter(call => call.action === 'models.api.select').length, 0);
      assert.deepEqual(calls.filter(call => call.action === 'models.api.favorite').map(call => [call.args.connectionId, call.args.expectedRevision, call.args.favorite]), [['api-b', 5, false], ['api-c', 2, false]]);
    });
    await check('old-model-review-cannot-replace-reopened-picker', async page => {
      await page.evaluate(() => window.modelFixture.setDelay(['models.api.review']));
      await page.getByRole('button', { name: '切换模型：same-model', exact: true }).click();
      await page.getByRole('option', { name: /备用连接/ }).click();
      await page.waitForFunction(() => window.modelFixture.pending.some(call => call.action === 'models.api.review'));
      await page.getByRole('button', { name: '关闭模型选择', exact: true }).click();
      await page.getByRole('button', { name: '切换模型：same-model', exact: true }).click();
      await page.getByRole('option', { name: /备用连接/ }).waitFor();
      await page.evaluate(() => window.modelFixture.settle('models.api.review', { windowId: 'api-window-1', viewGeneration: 1, selectionRevision: 8, reviewHandle: 'old', model: window.modelFixture.getModels()[1] }));
      await page.waitForTimeout(50);
      assert.equal(await page.getByRole('button', { name: '关闭模型选择', exact: true }).count(), 1);
      assert.equal(await page.getByRole('button', { name: '确认切换主模型', exact: true }).count(), 0);
      assert.equal(await page.getByRole('option', { name: /备用连接/ }).count(), 1);
    });
    await check('old-newfile-receipt-keeps-new-dialog', async page => {
      await page.evaluate(() => window.modelFixture.publish({ projectId: 'original-project', projects: [{ id: 'original-project', name: '原项目' }], files: [] }));
      await navigate(page, '项目');
      await page.getByRole('button', { name: '新文件', exact: true }).click();
      await page.getByRole('textbox', { name: '文件路径', exact: true }).fill('first.txt');
      await page.getByRole('button', { name: '创建', exact: true }).click();
      await page.waitForFunction(() => window.modelFixture.pending.some(call => call.action === 'projects.newFile'));
      await page.getByRole('button', { name: '关闭创建', exact: true }).click();
      await page.getByRole('button', { name: '新文件', exact: true }).click();
      await page.getByRole('textbox', { name: '文件路径', exact: true }).fill('second.txt');
      await page.evaluate(() => window.modelFixture.settle('projects.newFile', { accepted: true }));
      await page.waitForTimeout(50);
      assert.equal(await page.getByRole('textbox', { name: '文件路径', exact: true }).inputValue(), 'second.txt');
    });
    await check('pending-audio-recovery-is-visible-and-opens-settings', async page => {
      await page.evaluate(() => window.modelFixture.publish({ features: { phone_setup: { audioRecovery: { pendingCount: 2, needsOwnerAction: true } } } }));
      await page.getByText('还有 2 项声音记录待处理，原 App 可能仍静音。', { exact: false }).waitFor();
      await page.getByRole('button', { name: '打开无障碍设置', exact: true }).click();
      const calls = await page.evaluate(() => window.modelFixture.calls);
      assert.ok(calls.some(call => call.action === 'settings.openSystem' && call.args.section === 'accessibility'));
      assert.equal(calls.some(call => call.action === 'background.restoreAudio'), false);
    }, 'background');
    await check('incomplete-attachments-preserve-readable-selection', async page => {
      await page.evaluate(() => window.modelFixture.publish({ chatAttachmentPickerOpen: true, features: { attachments: { indexDiagnostics: { incomplete: true }, attachments: [{ id: 'readable-a', sha256: 'sha-a', name: '可读原文件.txt', mimeType: 'text/plain', size: 12 }] } } }));
      await page.getByText('部分附件尚未核对完整，可读文件仍可选择。未确认的原件已保留，请核对后再备份。', { exact: true }).waitFor();
      const file = page.getByRole('checkbox', { name: /可读原文件.txt/ }); assert.equal(await file.isEnabled(), true); await page.getByText('可读原文件.txt', { exact: true }).click();
      const args = await page.evaluate(() => window.modelFixture.calls.find(call => call.action === 'attachments.toggle').args);
      assert.deepEqual(args, { id: 'readable-a', sha256: 'sha-a' });
    });
  }
} finally { await browser.close(); if (bundle) await new Promise(resolve => server.close(resolve)); else await server.close(); }
await fs.writeFile(path.join(output, 'results.json'), JSON.stringify({ mode: baseline ? 'before' : 'after', identity: bundle ? 'Actual Vite production bundle and real Web bridge with synthetic native transport; not Android or OAuth' : 'Mounted React production components with isolated host fixture; not Android or OAuth', bundleIdentity, results }, null, 2));
for (const result of results) process.stdout.write(`${result.passed ? 'PASS' : 'FAIL'} ${result.name}${result.error ? ': ' + result.error : ''}\n`);
if (results.some(result => !result.passed)) process.exitCode = 1;
