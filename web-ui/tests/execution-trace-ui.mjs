/** Production components + actual bridge with an explicitly fake native host.
 * This verifies browser behavior, not Android execution or provider delivery. */
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium, expect } from '@playwright/test';
import { createServer } from 'vite';

const root = fileURLToPath(new URL('..', import.meta.url));
const output = process.env.EXECUTION_TRACE_EVIDENCE || path.resolve(root, '../build-artifacts/host-tests/execution-trace-ui');
const results = [], available = new Set();
const requestedCases = process.env.EXECUTION_TRACE_CASES?.split(',').map(value => value.trim()).filter(Boolean);
let server, browser, fatal;
const state = page => page.evaluate(() => window.executionTraceFixture.snapshot());
const row = (page, id) => page.locator(`.pw-trace-event-row[data-node-id="${id}"]`);
const detail = page => page.locator('.pw-trace-detail');
const readCount = async page => (await state(page)).calls.filter(call => call.action === 'trace.read').length;
const detailCount = async page => (await state(page)).calls.filter(call => call.action === 'trace.detail').length;
const shot = (page, name) => page.screenshot({ path: path.join(output, `${name}.png`), fullPage: true, animations: 'disabled' });
const complete = page => expect(page.locator('.pw-execution-trace')).toHaveAttribute('data-completeness', 'complete');
const close = async page => { await detail(page).getByRole('button', { name: '关闭调用详情' }).click(); await expect(detail(page)).toHaveCount(0); };
const tab = (page, name) => detail(page).getByRole('tab', { name, exact: true });
async function enter(page) {
  await page.goto(new URL('tests/execution-trace-fixture.html', server.resolvedUrls.local[0]).href);
  await expect(page.getByRole('tab', { name: '轨迹', exact: true })).toHaveAttribute('aria-selected', 'true', { timeout: 45_000 });
  await complete(page); await expect(row(page, 'event:user')).toBeVisible({ timeout: 30_000 });
  await page.evaluate(() => document.fonts.ready.then(() => undefined));
}
async function openNode(page, id) { await row(page, id).locator('.pw-trace-event-open').click(); await expect(detail(page)).toBeVisible(); await expect(detail(page).getByText('正在读取原记录正文…', { exact: true })).toHaveCount(0); }
async function openRequest(page, assistant = 'event:assistant-1') { await row(page, assistant).locator('.pw-trace-request-entry').click(); await expect(detail(page)).toBeVisible(); await expect(detail(page).getByText('正在读取原记录正文…', { exact: true })).toHaveCount(0); }
async function chooseOld(page) {
  await page.locator('.pw-trace-run-trigger').click(); await page.getByRole('option').filter({ hasText: '失败' }).click();
  await expect(row(page, 'model:historic')).toBeVisible(); await complete(page);
}
async function check(name, width, work) {
  available.add(name);
  if (requestedCases && !requestedCases.includes(name)) return;
  const entry = { name, width, passed: false, pageErrors: [], consoleErrors: [] };
  let page;
  try {
    page = await browser.newPage({ viewport: { width, height: width === 360 ? 800 : 844 }, isMobile: true, hasTouch: true,
      deviceScaleFactor: 1, reducedMotion: 'reduce', locale: 'zh-CN', timezoneId: 'Asia/Shanghai' });
    page.setDefaultTimeout(12_000);
    page.on('pageerror', error => entry.pageErrors.push(String(error)));
    page.on('console', event => { if (event.type() === 'error') entry.consoleErrors.push(event.text()); });
    await enter(page); await work(page);
    const calls = (await state(page)).calls;
    assert.ok(calls.every(call => ['app.ready', 'trace.read', 'trace.detail', 'trace.close'].includes(call.action)), 'Trace UI only sends read commands');
    assert.deepEqual(entry.pageErrors, []); assert.deepEqual(entry.consoleErrors, []);
    entry.passed = true;
  } catch (error) { entry.failure = String(error.stack || error); if (page) await shot(page, `${name}-failure`).catch(() => {}); }
  finally { if (page) await page.close(); results.push(entry); console.log(JSON.stringify(entry)); }
}
try {
  await fs.mkdir(output, { recursive: true });
  process.env.TMPDIR = path.join(output, 'tmp'); await fs.mkdir(process.env.TMPDIR, { recursive: true });
  browser = await chromium.launch({ headless: true, ...(process.env.TRACE_CHROMIUM ? { executablePath: process.env.TRACE_CHROMIUM } : {}) });
  server = await createServer({ root, configLoader: 'runner', cacheDir: path.join(output, 'vite-cache'), optimizeDeps: { entries: ['tests/execution-trace-fixture.html'] }, server: { host: '127.0.0.1', port: 0 } });
  await server.listen();
  for (const width of [360, 390]) {
    await check(`phone-${width}-facts-and-details`, width, async page => {
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true);
      assert.equal(await page.locator('.pw-task-body').evaluate(element => element.scrollWidth <= element.clientWidth + 1), true);
      const snapshot = await state(page);
      assert.ok(snapshot.nodeCount > 100); assert.equal(snapshot.nodeCount, 155);
      await expect(page.locator('.pw-trace-turn[data-turn-index]')).toHaveCount(25);
      await expect(page.locator('.pw-trace-turn[data-turn-index="5"]')).toHaveCount(1);
      await expect(page.locator('[data-overview-node="event:audit-5"]')).toHaveCount(0);
      assert.ok(snapshot.calls.filter(call => call.action === 'trace.read').length >= 4, 'Every compact page is automatically loaded');
      assert.equal(await detailCount(page), 0, 'Opening the list does not fetch bodies');
      await expect(row(page, 'tool:error')).toHaveAttribute('data-failed', 'true');
      await expect(row(page, 'tool:error')).toContainText('125 ms');
      await expect(row(page, 'tool:legacy')).toContainText('耗时未记录');
      await expect(row(page, 'tool:confirm')).toContainText('0 ms');
      await expect(page.locator('.pw-trace-token-summary')).toContainText('24,000 +');
      await expect(page.locator('.pw-trace-token-summary')).toContainText('2,784 +');
      await shot(page, `phone-${width}-trajectory`);
      await page.getByRole('button', { name: '按实际耗时展示总览' }).click();
      await expect(page.locator('.pw-trace-lanes')).toHaveAttribute('data-overview-mode', 'duration');
      await shot(page, `phone-${width}-duration-overview`);
      await openRequest(page);
      assert.deepEqual((await state(page)).calls.filter(call => call.action === 'trace.detail').at(-1).args,
        { taskId: 'task-fixture', runId: 'run-now', epoch: 3, nodeId: 'model:first' });
      await expect(detail(page)).toContainText('fixture-model');
      await expect(detail(page).getByRole('button', { name: '保留的原上下文' })).toBeVisible();
      await tab(page, '选项').click(); await expect(detail(page)).toContainText('max_output_tokens');
      await tab(page, '用量').click();
      await expect(detail(page).getByText('缓存读取', { exact: true })).toBeVisible();
      await expect(detail(page).getByText('其他输入', { exact: true })).toBeVisible();
      await expect(detail(page).getByText('思考', { exact: true })).toBeVisible();
      await expect(detail(page)).toContainText('700'); await expect(detail(page)).toContainText('300');
      await shot(page, `phone-${width}-request-usage`);
      await detail(page).getByRole('tab', { name: '本次执行累计', exact: true }).click();
      await expect(detail(page)).toContainText('累计 25 次物理模型请求');
      await expect(detail(page)).toContainText('24,000 +');
      await shot(page, `phone-${width}-request-cumulative`);
      await tab(page, '耗时').click();
      await expect(detail(page)).toContainText('350 ms'); await expect(detail(page)).toContainText('1.8 秒');
      await expect(detail(page)).toContainText('66.7 token/s');
      assert.equal(await detail(page).evaluate(element => element.scrollWidth <= element.clientWidth + 1), true);
      await shot(page, `phone-${width}-request-timing`);
      assert.equal(await page.evaluate(() => window.executionTraceFixture.back()), true); await expect(detail(page)).toHaveCount(0);
      await openNode(page, 'tool:error'); await expect(detail(page)).toContainText('原工具抛错：connection reset；执行效果待核对');
      await tab(page, '参数').click(); await expect(detail(page)).toContainText('原始资料来源');
      await tab(page, 'Schema').click(); await expect(detail(page)).toContainText('required');
      await tab(page, '耗时').click(); await expect(detail(page)).toContainText('125 ms');
      await shot(page, `phone-${width}-tool-error-timing`); await close(page);
      await openNode(page, 'tool:search'); await tab(page, '结果').click(); await expect(detail(page)).toContainText('原始来源 A');
      await shot(page, `phone-${width}-tool-result`); await close(page);
      await openNode(page, 'tool:sub-1'); await expect(tab(page, 'Schema')).toHaveCount(0); await close(page);
      await openNode(page, 'event:user'); await tab(page, '预览').click(); await expect(detail(page).locator('strong')).toContainText('25 轮资料');
      await tab(page, '原始').click(); await expect(detail(page)).toContainText('"role": "user"');
      await shot(page, `phone-${width}-event-raw`);
    });
  }
  await check('overview-locates-real-turn-and-request', 390, async page => {
    const last = page.locator('[data-overview-node="event:assistant-24"]');
    await last.scrollIntoViewIfNeeded(); await last.click();
    await expect(row(page, 'event:assistant-24')).toHaveAttribute('data-highlighted', 'true');
    const target = await row(page, 'event:assistant-24').boundingBox(); assert.ok(target && target.y > 50 && target.y < 750);
    await shot(page, 'phone-390-located-turn-24');
    await openRequest(page, 'event:assistant-24'); await expect(detail(page)).toContainText('第 24 轮资料分析请求');
    await tab(page, '用量').click(); await expect(detail(page)).toContainText('未记录'); await close(page);
    await page.locator('.pw-task-body').evaluate(element => { element.scrollTop = 0; });
    const first = page.locator('[data-overview-node="event:user"]'); await first.focus();
    await page.keyboard.press('ArrowRight'); await page.keyboard.press('Enter');
    await expect(row(page, 'event:context-1')).toHaveAttribute('data-highlighted', 'true');
  });
  await check('fold-search-filter-keyboard', 390, async page => {
    await page.getByRole('searchbox', { name: '搜索执行轨迹' }).fill('Turn 17');
    await expect(page.locator('.pw-trace-turn[data-turn-index]')).toHaveCount(1);
    await expect(row(page, 'event:assistant-17')).toBeVisible();
    await page.getByRole('searchbox', { name: '搜索执行轨迹' }).fill('');
    await page.getByRole('tab', { name: '异常', exact: true }).click();
    await expect(page.locator('.pw-trace-event-row')).toHaveCount(1); await expect(row(page, 'tool:error')).toBeVisible();
    await page.getByRole('tab', { name: '全部', exact: true }).click();
    await page.getByRole('button', { name: '查看执行区间' }).click();
    await expect(page.getByRole('tree', { name: '执行轨迹时间轴' })).toBeVisible();
    const request = page.locator('.pw-trace-row[data-node-id="model:first"]');
    await request.getByRole('button', { name: '折叠 整理资料并选择工具' }).click();
    await expect(page.locator('.pw-trace-row[data-node-id="tool:error"]')).toHaveCount(0);
    await page.getByRole('tab', { name: '异常', exact: true }).click();
    await expect(page.locator('.pw-trace-row[data-node-id="tool:error"]')).toBeVisible();
    await expect(request).toHaveAttribute('data-context', 'true');
    await page.getByRole('tab', { name: '全部', exact: true }).click();
    await page.getByRole('button', { name: '调用树', exact: true }).click();
    await expect(page.getByRole('tree', { name: '执行调用树' })).toBeVisible();
    await request.focus(); await page.keyboard.press('ArrowRight'); await page.keyboard.press('ArrowDown'); await page.keyboard.press('Enter');
    await expect(detail(page)).toBeVisible(); await close(page);
    await page.getByRole('button', { name: '返回轮次列表' }).click(); await expect(page.locator('.pw-trace-events')).toBeVisible();
  });
  await check('history-pages-and-stale-run', 390, async page => {
    await page.evaluate(() => window.executionTraceFixture.hold());
    await page.getByRole('button', { name: '刷新执行轨迹' }).click();
    await page.evaluate(() => window.executionTraceFixture.hold(false));
    await chooseOld(page); await page.evaluate(() => window.executionTraceFixture.release());
    await expect(row(page, 'model:historic')).toBeVisible(); await expect(row(page, 'event:assistant-1')).toHaveCount(0);
    const after = await readCount(page); await page.waitForTimeout(3300); assert.equal(await readCount(page), after, 'Historical run does not poll');
    await openNode(page, 'model:historic'); await expect(detail(page)).toContainText('原服务返回 503'); await close(page);
    await shot(page, 'phone-390-history');
  });
  await check('backfill-and-detail-independent-partial', 390, async page => {
    await page.evaluate(() => { window.executionTraceFixture.holdOlder(); window.executionTraceFixture.setTask('partial-task'); });
    await expect(page.locator('.pw-execution-trace')).toHaveAttribute('data-completeness', 'loading');
    await expect(page.locator('.pw-trace-completeness')).toContainText('40 条');
    await openRequest(page, 'event:assistant-25');
    await tab(page, '用量').click(); await detail(page).getByRole('tab', { name: '本次执行累计', exact: true }).click();
    await expect(detail(page).locator('[data-cumulative-state]')).toHaveAttribute('data-cumulative-state', 'partial');
    await expect(detail(page)).toContainText('仍有记录未补齐');
    await shot(page, 'phone-390-backfill-partial-cumulative');
    const requests = await detailCount(page); await page.evaluate(() => window.executionTraceFixture.release('trace.read'));
    await complete(page); await expect(detail(page)).toBeVisible(); await expect(tab(page, '用量')).toHaveAttribute('aria-selected', 'true');
    await expect(detail(page)).toContainText('累计 25 次物理模型请求'); assert.equal(await detailCount(page), requests, 'Backfill does not refetch or replace selected body');
    await close(page); await page.evaluate(() => window.executionTraceFixture.limited()); await page.getByRole('button', { name: '刷新执行轨迹' }).click();
    await expect(page.locator('.pw-execution-trace')).toHaveAttribute('data-completeness', 'partial');
    await expect(page.locator('.pw-trace-read-limit')).toContainText('仍不是完整记录'); await shot(page, 'phone-390-source-limited');
  });
  await check('live-preserves-selection-and-scroll-stops-hidden', 390, async page => {
    await page.locator('.pw-task-body').evaluate(element => { element.scrollTop = 270; });
    const before = await page.locator('.pw-task-body').evaluate(element => element.scrollTop), reads = await readCount(page);
    await expect.poll(() => readCount(page), { timeout: 7000 }).toBeGreaterThan(reads);
    await complete(page); const after = await page.locator('.pw-task-body').evaluate(element => element.scrollTop); assert.ok(Math.abs(after - before) < 3);
    await openRequest(page, 'event:assistant-25'); await tab(page, '用量').click();
    await page.evaluate(() => window.executionTraceFixture.complete());
    await expect(detail(page).locator('.pw-drawer-header')).toContainText('已完成', { timeout: 8000 });
    await expect(tab(page, '用量')).toHaveAttribute('aria-selected', 'true');
    await expect(detail(page)).toContainText('120');
    await close(page); await page.evaluate(() => window.executionTraceFixture.setActive(false));
    await expect(page.getByText('轨迹页已隐藏')).toBeVisible();
    const hiddenReads = await readCount(page); await page.waitForTimeout(3300); assert.equal(await readCount(page), hiddenReads);
    assert.ok((await state(page)).calls.some(call => call.action === 'trace.close'));
    await page.evaluate(() => window.executionTraceFixture.setActive(true)); await complete(page);
    await expect(detail(page)).toHaveCount(0); await expect(row(page, 'event:assistant-25')).toContainText('已完成');
  });
  await check('errors-retry-empty-and-document-change', 390, async page => {
    await page.evaluate(() => window.executionTraceFixture.failNext()); await page.getByRole('button', { name: '刷新执行轨迹' }).click();
    await expect(page.locator('.pw-trace-read-error')).toContainText('这次轨迹读取未完成');
    const failedReads = await readCount(page); await page.waitForTimeout(3300); assert.equal(await readCount(page), failedReads);
    await page.getByRole('button', { name: '刷新执行轨迹' }).click(); await expect(page.locator('.pw-trace-read-error')).toHaveCount(0); await complete(page);
    await page.evaluate(() => window.executionTraceFixture.failDetail()); await openNode(page, 'event:user');
    await expect(detail(page).getByRole('alert')).toContainText('这次轨迹读取未完成');
    await detail(page).getByRole('button', { name: '重读详情' }).click(); await expect(detail(page).getByRole('alert')).toHaveCount(0); await expect(detail(page)).toContainText('可追溯的报告');
    await close(page); await page.evaluate(() => window.executionTraceFixture.holdDetails());
    await row(page, 'event:user').locator('.pw-trace-event-open').click(); await expect(detail(page)).toBeVisible();
    await page.evaluate(() => { window.executionTraceFixture.rotateDocument(); window.executionTraceFixture.release('trace.detail'); });
    await expect(detail(page)).toHaveCount(0); await complete(page);
    await page.evaluate(() => window.executionTraceFixture.empty()); await page.getByRole('button', { name: '刷新执行轨迹' }).click();
    await expect(page.locator('.pw-trace-event-row')).toHaveCount(0); await expect(page.locator('.pw-trace-empty')).toContainText('正在等待');
    await shot(page, 'phone-390-empty');
  });
  await check('source-unavailable-and-detail-identity', 360, async page => {
    await page.evaluate(() => window.executionTraceFixture.missingDetails()); await openRequest(page);
    await expect(detail(page).locator('[data-source-state="unavailable"]')).toContainText('原正文已清理');
    await expect(detail(page)).toContainText('fixture-model'); await tab(page, '选项').click();
    await expect(detail(page)).toContainText('原正文已清理'); await expect(detail(page)).not.toContainText('max_output_tokens');
    await shot(page, 'phone-360-missing-source'); await close(page);
    await page.evaluate(() => { window.executionTraceFixture.missingDetails(false); window.executionTraceFixture.mismatchDetail(); });
    await openRequest(page); await expect(detail(page).getByRole('alert')).toContainText('来源不可核对');
    await expect(detail(page)).not.toContainText('other:unrelated'); await close(page);
    await page.evaluate(() => window.executionTraceFixture.mismatchDetail(false)); await openNode(page, 'tool:legacy');
    await expect(detail(page).locator('[data-source-state="metadata_only"]')).toContainText('只保存了调用元数据');
    await expect(detail(page)).toContainText('耗时未记录'); await shot(page, 'phone-360-legacy-metadata');
  });
  await check('opened-source-invalidation-rejects-late-body', 390, async page => {
    await chooseOld(page); await openNode(page, 'model:historic'); await tab(page, '选项').click(); await expect(detail(page)).toContainText('max_output_tokens');
    const listReads = await readCount(page);
    await page.evaluate(() => { window.executionTraceFixture.holdDetails(); window.executionTraceFixture.invalidateSource(); });
    await expect(detail(page)).not.toContainText('max_output_tokens'); await expect(tab(page, '选项')).toHaveAttribute('aria-selected', 'true');
    await page.evaluate(() => { window.executionTraceFixture.missingDetails(); window.executionTraceFixture.invalidateSource(); });
    await expect(detail(page)).not.toContainText('max_output_tokens');
    await page.evaluate(() => window.executionTraceFixture.release('trace.detail'));
    await expect(detail(page).locator('[data-source-state="unavailable"]')).toContainText('原正文已清理');
    await expect(detail(page)).not.toContainText('max_output_tokens');
    assert.equal(await readCount(page), listReads, 'A source revision invalidates the body without restarting the overview');
    await shot(page, 'phone-390-source-revision');
  });
  await check('terminal-transition-queues-final-snapshot', 390, async page => {
    await page.evaluate(() => window.executionTraceFixture.hold()); await page.getByRole('button', { name: '刷新执行轨迹' }).click();
    await expect.poll(async () => (await state(page)).held).toBeGreaterThan(0);
    await page.evaluate(() => { window.executionTraceFixture.complete(); window.executionTraceFixture.setStatus('COMPLETED'); });
    const inFlight = await readCount(page); await page.evaluate(() => window.executionTraceFixture.release('trace.read'));
    await expect.poll(() => readCount(page)).toBeGreaterThan(inFlight + 4);
    await complete(page); await expect(row(page, 'event:assistant-25')).toContainText('已完成');
    await expect(page.locator('.pw-trace-completeness')).toContainText('158 条');
    const finalReads = await readCount(page); await page.waitForTimeout(3300); assert.equal(await readCount(page), finalReads);
  });
  await check('task-change-and-hidden-tab', 390, async page => {
    await page.evaluate(() => window.executionTraceFixture.holdDetails()); await row(page, 'event:user').locator('.pw-trace-event-open').click();
    await page.evaluate(() => { window.executionTraceFixture.setTask('task-other'); window.executionTraceFixture.release('trace.detail'); });
    await expect(detail(page)).toHaveCount(0); await complete(page); await expect(row(page, 'event:user')).toBeVisible();
    await openNode(page, 'event:user'); assert.equal((await state(page)).calls.filter(call => call.action === 'trace.detail').at(-1).args.taskId, 'task-other'); await close(page);
    await page.getByRole('tab', { name: /^进展记录/ }).click();
    const reads = await readCount(page); await page.waitForTimeout(3300); assert.equal(await readCount(page), reads);
    await page.getByRole('tab', { name: '轨迹', exact: true }).click(); await complete(page);
    assert.ok(await readCount(page) > reads); await expect(detail(page)).toHaveCount(0);
  });
  const unknown = requestedCases?.filter(name => !available.has(name)) || [];
  if (unknown.length) throw new Error(`Unknown EXECUTION_TRACE_CASES: ${unknown.join(', ')}`);
  if (!results.length) throw new Error('No execution-trace browser cases ran');
} catch (error) { fatal = String(error.stack || error); console.error(fatal); }
finally {
  await server?.close().catch(() => {}); await browser?.close().catch(() => {});
  const passed = !fatal && results.length > 0 && results.every(entry => entry.passed);
  await fs.mkdir(output, { recursive: true });
  await fs.writeFile(path.join(output, 'results.json'), JSON.stringify({ status: passed ? 'passed' : 'failed', generatedAt: new Date().toISOString(),
    fixture: 'Production TaskHistory + fake read-only native host, 25 real turns, 155 compact nodes, separate trace.detail',
    limitation: 'Browser fixture does not prove Android execution or provider delivery.', requestedCases, fatal, results }, null, 2) + '\n');
  if (!passed) process.exitCode = 1;
}
