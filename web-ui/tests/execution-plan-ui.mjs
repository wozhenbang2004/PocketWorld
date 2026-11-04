import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { chromium } from '@playwright/test';
import { installExecutionPlanHost } from './execution-plan-host-fixture.mjs';

// The root builds the integrated production app once. This journey only consumes that bundle.
const argument = flag => { const at = process.argv.indexOf(flag); if (at < 0 || !process.argv[at + 1]) throw new Error('Required: --bundle <directory> --output <directory>'); return path.resolve(process.argv[at + 1]); };
const bundle = argument('--bundle'), output = argument('--output');
const index = await fs.readFile(path.join(bundle, 'index.html'), 'utf8');
const entry = index.match(/assets\/(index-[^" ]+\.js)/)?.[1];
const record = { scope: 'Actual integrated production bundle with synthetic native transport at 360x800. Does not execute Android/TaskStore, native phase transitions or model tools.',
  startedAt: new Date().toISOString(), bundle, entry, sha256: createHash('sha256').update(await fs.readFile(path.join(bundle, 'assets', entry))).digest('hex'),
  viewport: { width: 360, height: 800 }, checks: [], screenshots: [], errors: [] };
let server, browser, context, page;
const check = name => record.checks.push({ name, passed: true });
const drawer = () => page.locator('.pw-task-drawer');
const count = action => page.evaluate(action => window.executionPlanFixture.calls.filter(row => row.action === action).length, action);
const approve = () => drawer().getByRole('button', { name: '确认这版计划，在原范围内继续', exact: true });
async function stable() {
  const wait = await page.evaluate(async () => {
    const value = number => typeof number === 'number' && !Number.isFinite(number) ? String(number) : number;
    const describe = animation => {
      const timing = animation.effect?.getComputedTiming(), target = animation.effect?.target;
      return { type: animation.constructor.name, property: animation instanceof CSSTransition ? animation.transitionProperty : null,
        playState: animation.playState, pending: animation.pending, currentTime: value(animation.currentTime),
        duration: value(timing?.duration), endTime: value(timing?.endTime), progress: timing?.progress,
        target: target instanceof Element ? `${target.tagName.toLowerCase()}${target.id ? '#' + target.id : ''}.${String(target.className).slice(0, 160)}` : null };
    };
    const animations = document.getAnimations(), before = { fonts: document.fonts.status, animations: animations.map(describe) };
    const finite = animations.filter(animation => animation instanceof CSSTransition && animation.playState !== 'paused' && Number.isFinite(animation.effect?.getComputedTiming().endTime));
    const started = performance.now(); let timer;
    // A paused or detached transition elsewhere in the mounted document must not hold a screenshot forever.
    const result = await Promise.race([
      Promise.allSettled([document.fonts.ready, ...finite.map(animation => animation.finished)]).then(() => 'settled'),
      new Promise(resolve => { timer = setTimeout(() => resolve('timed_out'), 2500); })
    ]);
    clearTimeout(timer);
    return { result, elapsedMs: Math.round(performance.now() - started), before,
      after: { fonts: document.fonts.status, animations: document.getAnimations().map(describe) } };
  });
  (record.layoutWaits ||= []).push(wait);
  await page.waitForFunction(() => {
    const value = JSON.stringify([...document.querySelectorAll('.pw-task-drawer,.pw-task-body,.pw-approval-item')].map(node => { const box = node.getBoundingClientRect(); return [box.x, box.y, box.width, box.height, node.scrollTop]; }));
    const old = window.__executionPlanLayout; window.__executionPlanLayout = { value, stable: old?.value === value ? old.stable + 1 : 0 }; return window.__executionPlanLayout.stable >= 3;
  }, null, { polling: 'raf' });
}
async function shot(name) { await stable(); await page.screenshot({ path: path.join(output, name + '.png') }); record.screenshots.push(name + '.png'); }
// Opening an attention task now reads its original review and presents the pending decision directly.
async function openReview() { await drawer().getByRole('region', { name: '本次操作授权', exact: true }).waitFor(); }
async function checkScope(number) { const label = `我已核对计划版本 ${number} 的完整步骤和原任务范围`; await drawer().getByText(label, { exact: true }).click(); assert.equal(await drawer().getByRole('checkbox', { name: label, exact: true }).isChecked(), true); }

try {
  await fs.mkdir(output, { recursive: true });
  server = http.createServer(async (req, res) => { try {
    const name = new URL(req.url, 'http://local').pathname, file = path.resolve(bundle, '.' + (name === '/' ? '/index.html' : name));
    if (!file.startsWith(bundle + '/')) throw new Error('invalid asset');
    const content = await fs.readFile(file); res.writeHead(200, { 'content-type': ({ '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.woff2': 'font/woff2', '.json': 'application/json', '.svg': 'image/svg+xml' })[path.extname(file)] || 'application/octet-stream' }); res.end(content);
  } catch { res.writeHead(404); res.end(); } });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); const origin = 'http://127.0.0.1:' + server.address().port;
  browser = await chromium.launch({ headless: true, args: ['--disable-background-networking'] });
  context = await browser.newContext({ viewport: record.viewport, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', serviceWorkers: 'block' });
  await context.route('**/*', route => new URL(route.request().url()).origin === origin ? route.continue() : route.abort());
  await context.addInitScript(installExecutionPlanHost); page = await context.newPage(); page.setDefaultTimeout(8000);
  page.on('pageerror', error => record.errors.push(error.message)); await page.goto(origin, { waitUntil: 'networkidle' });
  await page.waitForFunction(() => document.documentElement.dataset.pocketUi === 'ready');
  assert.equal(await count('tasks.open'), 0, 'A notification must wait until its original task is read');
  await page.evaluate(() => window.executionPlanFixture.loadRequestedTask());
  await page.waitForFunction(() => window.executionPlanFixture.calls.some(row => row.action === 'tasks.open'));
  const opened = await page.evaluate(() => window.executionPlanFixture.calls.filter(row => row.action === 'tasks.open'));
  assert.equal(opened.length, 1); assert.equal(opened[0].args.requestNavigation, false);
  await drawer().waitFor(); await drawer().getByRole('button', { name: '过程与范围', exact: true }).click();
  await drawer().getByRole('button', { name: /^(本次范围|修订本次范围)$/ }).click();
  await drawer().getByRole('button', { name: '进入只读规划', exact: true }).click();
  await drawer().getByText('只读规划已保存在原任务中，尚未启动执行。原预算与截止时间保持不变。', { exact: true }).waitFor();
  const entered = await page.evaluate(() => ({ task: window.executionPlanFixture.state(), calls: window.executionPlanFixture.calls }));
  const enter = entered.calls.find(row => row.action === 'tasks.plan.enter'); assert.deepEqual(Object.keys(enter.args), ['reviewHandle']);
  assert.equal(entered.task.raw.executionScope.executionPhase, 'planning'); assert.equal(entered.task.raw.executionScope.toolsUsed, 7); assert.equal(entered.task.raw.executionScope.modelCallsUsed, 4);
  assert.equal(entered.task.raw.executionScope.toolLimit, 20); assert.equal(entered.task.raw.executionScope.modelCallLimit, 12);
  assert.equal(entered.calls.some(row => ['tasks.resume', 'tasks.scope'].includes(row.action)), false); await shot('planning-saved-no-start');
  check('a notification waits for the original task and opens its native window once; entering planning only submits the original handle, preserves displayed budgets and does not start or refill');

  await page.reload({ waitUntil: 'networkidle' }); await page.evaluate(() => window.executionPlanFixture.loadRequestedTask()); await drawer().getByText(/原任务处于只读规划/).waitFor();
  assert.equal(await count('tasks.plan.enter'), 0); assert.equal(await count('tasks.approve'), 0);
  const coldOpens = await count('tasks.open'); await page.evaluate(() => window.executionPlanFixture.reopen(true)); await stable();
  assert.equal(await count('tasks.open'), coldOpens, 'An already-open native original task window must not be opened again'); await shot('cold-web-planning');
  check('a fresh Web document displays the fixture persisted native planning phase without a transition and reuses an already-open native task window');

  await page.evaluate(() => window.executionPlanFixture.propose(2)); await openReview();
  await drawer().getByRole('heading', { name: '计划审阅', exact: true }).waitFor();
  assert.equal(await approve().isEnabled(), false); assert.equal(await drawer().getByRole('checkbox', { name: /我已核对计划版本 2/ }).isChecked(), false);
  await drawer().getByRole('button', { name: '1. 读取原资料', exact: true }).click();
  await drawer().getByText('逐项读取已授权的原项目资料，记录现状与缺口；不写入项目文件。', { exact: true }).waitFor(); await shot('plan-body-reviewed');
  await drawer().getByRole('button', { name: '2. 核对修改与验收', exact: true }).click();
  await drawer().getByText('计划版本 2：列出所需修改及对应验收，确认后仍逐步核对原授权。', { exact: true }).waitFor();
  await checkScope(2); await approve().scrollIntoViewIfNeeded(); await stable(); await shot('plan-budget-and-decision');
  assert.equal(await drawer().evaluate(node => node.scrollWidth <= node.clientWidth + 1), true, 'The original drawer must not overflow at 360px');
  await approve().click(); await drawer().getByText('这版计划的决定已保存，续接已排队；尚未确认开始执行。', { exact: true }).waitFor();
  const decision = await page.evaluate(() => window.executionPlanFixture.calls.find(row => row.action === 'tasks.approve'));
  assert.equal(decision.args.planRevision, 2); assert.equal(decision.args.scopeReviewed, true);
  assert.deepEqual(Object.keys(decision.args).sort(), ['reviewHandle', 'approvalId', 'approved', 'scopeReviewed', 'planRevision', 'planArtifactRef', 'planHash'].sort());
  check('the original complete plan and budget are reviewable, approval defaults off and a saved queued decision is not execution success');

  await page.evaluate(() => window.executionPlanFixture.propose(3)); await openReview(); await checkScope(3);
  const before = await page.evaluate(() => window.executionPlanFixture.state().updatedAt), previousDecisions = await count('tasks.approve');
  await page.evaluate(() => window.executionPlanFixture.propose(4, false));
  await drawer().getByText('任务已有新进展，请重新核对后再操作。', { exact: true }).waitFor();
  assert.equal(await page.evaluate(() => window.executionPlanFixture.state().updatedAt), before); assert.equal(await approve().count(), 0); assert.equal(await count('tasks.approve'), previousDecisions);
  await drawer().getByRole('button', { name: '重新核对', exact: true }).click();
  await drawer().getByRole('checkbox', { name: /我已核对计划版本 4/ }).waitFor(); assert.equal(await approve().isEnabled(), false);
  check('changing only nested plan and scope versions at the same timestamp disables the old checked approval and resets its checkbox');

  await page.evaluate(() => window.executionPlanFixture.hold(['tasks.approve'])); await checkScope(4); await approve().click();
  await page.waitForFunction(() => window.executionPlanFixture.held.length === 1); const heldDecisions = await count('tasks.approve');
  await page.keyboard.press('Escape'); await drawer().waitFor({ state: 'hidden' });
  await page.evaluate(() => window.executionPlanFixture.reopen()); await openReview();
  await page.evaluate(() => window.executionPlanFixture.settle()); await stable();
  assert.equal(await count('tasks.approve'), heldDecisions); assert.equal(await approve().isEnabled(), false);
  assert.equal(await drawer().getByText('这版计划的决定已保存，续接已排队；尚未确认开始执行。', { exact: true }).count(), 0);
  assert.equal(await drawer().getByText('原计划窗口已关闭，请核对原记录；没有重复提交', { exact: true }).count(), 0);
  check('closing and reopening the original drawer prevents a late old decision from attaching or replaying');

  await page.evaluate(() => window.executionPlanFixture.failBody(true)); await drawer().getByRole('button', { name: '重新核对', exact: true }).click();
  await drawer().getByText('未取得这版计划的完整原文，请重新核对；仍可拒绝这次申请', { exact: true }).waitFor();
  assert.equal(await approve().isEnabled(), false); assert.equal(await drawer().getByRole('checkbox').count(), 0);
  assert.equal(await drawer().getByRole('button', { name: '拒绝', exact: true }).isEnabled(), true); await shot('missing-body-reject-only');
  await drawer().getByRole('button', { name: '拒绝', exact: true }).click();
  await drawer().getByText('已拒绝这次计划申请，没有批准执行。', { exact: true }).waitFor();
  const rejection = await page.evaluate(() => window.executionPlanFixture.calls.filter(row => row.action === 'tasks.approve').at(-1));
  assert.equal(rejection.args.approved, false); assert.equal(rejection.args.approvalId, 'approval-4');
  assert.deepEqual(Object.keys(rejection.args).sort(), ['reviewHandle', 'approvalId', 'approved'].sort());
  check('a failed original body read cannot approve but still allows rejecting only the original pending approval');
  assert.deepEqual(record.errors, []); record.passed = true;
} catch (error) {
  record.passed = false; record.failure = String(error.stack || error);
  if (page) { await page.screenshot({ path: path.join(output, 'failure.png') }).catch(() => {}); record.body = await page.locator('body').innerText().catch(() => ''); }
} finally {
  record.finishedAt = new Date().toISOString(); await fs.mkdir(output, { recursive: true }); await fs.writeFile(path.join(output, 'result.json'), JSON.stringify(record, null, 2));
  await context?.close(); await browser?.close(); if (server) await new Promise(resolve => server.close(resolve));
}
console.log(JSON.stringify({ passed: record.passed, checks: record.checks, output }, null, 2)); if (!record.passed) process.exitCode = 1;
