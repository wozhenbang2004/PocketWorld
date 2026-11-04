/** Real TeamsWorkspace/BoardUI journey with an explicitly fake host. No Android or model acceptance. */
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium, expect } from '@playwright/test';
import { createServer } from 'vite';

const root = fileURLToPath(new URL('..', import.meta.url));
const output = process.env.TEAM_PHONE_EVIDENCE || path.resolve(root, '../build-artifacts/host-tests/team-phone-ui');
const profiles = [
  { name: 'desktop', viewport: { width: 1280, height: 900 }, hasTouch: false, isMobile: false },
  { name: 'phone', viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true },
];
const choices = [
  { member: 'reader', name: '资料查询', option: '资料应用 · 后台屏幕', tools: 7, models: 4, minutes: 6 },
  { member: 'checker', name: '来源核对', option: '浏览器 · 工作手机后台屏幕', tools: 9, models: 5, minutes: 8 },
];
const results = [];
let server, browser, browserVersion;
const host = page => page.evaluate(() => window.teamPhoneFixture.snapshot());
const scopes = page => page.getByRole('dialog', { name: '这次手机分工的范围', exact: true });
const review = page => page.getByRole('dialog', { name: '开始这次原团队协作', exact: true });
async function shot(page, name) {
  await page.screenshot({ path: path.join(output, `${name}.png`), fullPage: true, animations: 'disabled' });
}
async function enter(page, goal) {
  await page.goto(new URL('tests/team-phone-fixture.html', server.resolvedUrls.local[0]).href);
  await page.getByRole('tab', { name: '团队配置', exact: true }).waitFor({ timeout: 30_000 });
  await page.evaluate(() => document.fonts.ready.then(() => undefined));
  await page.getByText('界面回归样本 · 假宿主，不连接真实手机或模型', { exact: true }).waitFor();
  await page.getByRole('tab', { name: '团队配置', exact: true }).click();
  await page.getByRole('button', { name: /手机资料协作/ }).click();
  await expect(page.getByRole('heading', { name: '手机资料协作', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '选择手机范围并开始', exact: true })).toBeDisabled();
  await page.getByRole('textbox', { name: '这次共同目标', exact: true }).fill(goal);
}
async function openScopes(page) {
  await page.getByRole('button', { name: '选择手机范围并开始', exact: true }).click();
  await expect(scopes(page)).toBeVisible();
  await expect(scopes(page).getByRole('button', { name: '核对本次范围', exact: true })).toBeDisabled();
  return (await host(page)).activePhoneHandle;
}
async function choose(page, choice) {
  const member = scopes(page).locator('section').filter({ has: page.getByRole('heading', { name: choice.name, exact: true }) });
  await member.getByRole('button', { name: /应用范围/ }).click();
  await page.getByRole('option', { name: choice.option, exact: true }).click();
  await member.getByRole('textbox', { name: '最多操作次数', exact: true }).fill(String(choice.tools));
  await member.getByRole('textbox', { name: '最多模型调用次数', exact: true }).fill(String(choice.models));
  await member.getByRole('textbox', { name: '本次有效分钟数', exact: true }).fill(String(choice.minutes));
}
async function chooseAll(page) {
  await choose(page, choices[0]);
  await expect(scopes(page).getByRole('button', { name: '核对本次范围', exact: true })).toBeDisabled();
  await choose(page, choices[1]);
  await expect(scopes(page).getByRole('button', { name: '核对本次范围', exact: true })).toBeEnabled();
}
async function openReview(page, goal, phoneHandle) {
  await scopes(page).getByRole('button', { name: '核对本次范围', exact: true }).click();
  await expect(review(page)).toBeVisible();
  await expect(review(page).getByText(goal, { exact: true })).toBeVisible();
  await expect(review(page).getByRole('button', { name: '确认这一次操作', exact: true })).toBeDisabled();
  for (const choice of choices) {
    await expect(review(page).getByText(`最多 ${choice.tools} 次操作 · ${choice.models} 次模型调用`, { exact: true })).toBeVisible();
  }
  const state = await host(page), request = state.calls.find(call => call.action === 'teams.reviewPhoneStart');
  assert.equal(request.args.phoneHandle, phoneHandle, 'Review consumes the exact original phone selection');
  assert.deepEqual(request.args.members.map(({ memberId, optionId, toolBudget, modelBudget }) => ({ memberId, optionId, toolBudget, modelBudget })),
    choices.map(choice => ({ memberId: choice.member, optionId: `scope-${choice.member}`, toolBudget: choice.tools, modelBudget: choice.models })));
  assert.ok(request.args.members.every(member => Number.isSafeInteger(member.expiresAt) && member.expiresAt > Date.now()));
  assert.equal(state.calls.filter(call => call.action === 'teams.confirm').length, 0, 'Scope review cannot start a run');
  assert.equal(state.activePhoneHandle, null);
  return state.activeReviewHandle;
}
async function acknowledged(page) {
  // React Aria owns the actual checkbox; click its visible label, never force a hidden input.
  await review(page).getByText('已核对这次操作与原目标', { exact: true }).click();
  await expect(review(page).getByRole('checkbox', { name: '已核对这次操作与原目标', exact: true })).toBeChecked();
  await expect(review(page).getByRole('button', { name: '确认这一次操作', exact: true })).toBeEnabled();
}
async function check(name, profile, work) {
  const result = { name, viewport: profile.viewport, passed: false, errors: [], consoleErrors: [] };
  let page;
  try {
    page = await browser.newPage({ viewport: profile.viewport, hasTouch: profile.hasTouch, isMobile: profile.isMobile,
      deviceScaleFactor: 1, reducedMotion: 'reduce', locale: 'zh-CN', timezoneId: 'Asia/Shanghai' });
    page.setDefaultTimeout(15_000);
    page.on('pageerror', error => result.errors.push(String(error)));
    page.on('console', message => { if (message.type() === 'error') result.consoleErrors.push(message.text()); });
    await work(page);
    await expect(page.getByRole('alert')).toHaveCount(0);
    assert.deepEqual(result.errors, [], 'No uncaught UI/runtime errors');
    result.passed = true;
  } catch (error) { result.error = String(error.stack || error); }
  finally {
    if (page) {
      try { result.host = await host(page); } catch (error) { result.hostUnavailable = String(error); }
      try { await shot(page, `${name}-${result.passed ? 'complete' : 'failure'}`); }
      catch (error) { result.passed = false; result.screenshotError = String(error); }
      try { await page.close(); } catch (error) { result.passed = false; result.closeError = String(error); }
    }
    results.push(result);
    process.stdout.write(`${result.passed ? 'PASS' : 'FAIL'} ${name}${result.error ? `: ${result.error}` : ''}\n`);
  }
}

await fs.mkdir(output, { recursive: true });
try {
  server = await createServer({ root, server: { host: '127.0.0.1', port: 0 }, logLevel: 'error' });
  await server.listen();
  browser = await chromium.launch({ headless: true });
  browserVersion = browser.version();
  for (const profile of profiles) {
    const prefix = profile.name;
    await check(`${prefix}-scope-confirm`, profile, async page => {
      const goal = `核对本周新增资料，分别保留标题与来源（${prefix} 界面回归）`;
      await enter(page, goal); await shot(page, `${prefix}-01-team-entry`);
      const phoneHandle = await openScopes(page);
      await chooseAll(page);
      await scopes(page).getByRole('heading', { name: '资料查询', exact: true }).scrollIntoViewIfNeeded();
      await shot(page, `${prefix}-02-phone-scopes-top`);
      await scopes(page).getByRole('button', { name: '核对本次范围', exact: true }).scrollIntoViewIfNeeded();
      await shot(page, `${prefix}-03-phone-scopes-actions`);
      const reviewHandle = await openReview(page, goal, phoneHandle);
      await shot(page, `${prefix}-04-final-review`);
      await acknowledged(page);
      await review(page).getByRole('button', { name: '确认这一次操作', exact: true }).click();
      await expect(page.getByRole('heading', { name: goal, exact: true })).toBeVisible();
      await expect(page.getByText('已收到原操作回执。', { exact: true })).toBeVisible();
      await expect(page.getByText('工作中', { exact: true })).toBeVisible();
      const state = await host(page), confirmations = state.calls.filter(call => call.action === 'teams.confirm');
      assert.deepEqual(confirmations.map(call => call.args), [{ reviewHandle }], 'Only the acknowledged final review starts once');
      assert.equal(state.confirmedStarts, 1);
      assert.equal(state.activeReviewHandle, null);
      assert.ok(state.consumedHandles.includes(phoneHandle) && state.consumedHandles.includes(reviewHandle));
      assert.ok(!state.closedHandles.includes(phoneHandle) && !state.closedHandles.includes(reviewHandle), 'Consumed handles are not replayed as cancellations');
    });
    await check(`${prefix}-return-cancel`, profile, async page => {
      const goal = `保留这个目标，取消本次手机范围（${prefix} 界面回归）`;
      await enter(page, goal);
      const firstPhoneHandle = await openScopes(page);
      await choose(page, choices[0]);
      await scopes(page).getByRole('button', { name: '返回', exact: true }).click();
      await expect(scopes(page)).toHaveCount(0);
      await expect(page.getByRole('textbox', { name: '这次共同目标', exact: true })).toHaveValue(goal);
      let state = await host(page);
      assert.deepEqual(state.closedHandles, [firstPhoneHandle]);
      assert.equal(state.calls.filter(call => call.action === 'teams.reviewPhoneStart').length, 0);
      const secondPhoneHandle = await openScopes(page);
      assert.notEqual(secondPhoneHandle, firstPhoneHandle, 'Reopening creates a fresh scope handle');
      await chooseAll(page);
      const reviewHandle = await openReview(page, goal, secondPhoneHandle);
      await acknowledged(page); await shot(page, `${prefix}-05-cancel-final-review`);
      if (profile.isMobile) {
        assert.equal(await page.evaluate(() => window.teamPhoneFixture.back()), true, 'Registered production back handler consumes the final review');
      } else await review(page).getByRole('button', { name: '返回', exact: true }).click();
      await expect(review(page)).toHaveCount(0);
      await expect(page.getByRole('textbox', { name: '这次共同目标', exact: true })).toHaveValue(goal);
      await shot(page, `${prefix}-06-goal-preserved-after-cancel`);
      state = await host(page);
      assert.deepEqual(state.closedHandles, [firstPhoneHandle, reviewHandle]);
      assert.deepEqual(state.consumedHandles, [secondPhoneHandle]);
      assert.equal(state.activePhoneHandle, null); assert.equal(state.activeReviewHandle, null);
      assert.equal(state.confirmedStarts, 0);
      assert.equal(state.calls.filter(call => call.action === 'teams.confirm').length, 0, 'Returning, even after acknowledgement, never confirms');
      await page.getByRole('button', { name: '返回上一层', exact: true }).click();
      await expect(page.getByRole('tab', { name: '团队配置', exact: true })).toBeVisible();
      state = await host(page);
      const readingClose = state.calls.filter(call => call.action === 'teams.closeReading' && call.args.viewHandle);
      assert.equal(readingClose.length, 1, 'Returning to the list closes only the original definition reading');
    });
  }
} catch (error) { results.push({ name: 'harness', passed: false, error: String(error.stack || error) }); }
finally {
  for (const [name, resource] of [['browser', browser], ['vite', server]]) {
    if (resource) try { await resource.close(); }
    catch (error) { results.push({ name: `${name}-cleanup`, passed: false, error: String(error.stack || error) }); }
  }
  const receipt = {
    identity: 'Mounted production TeamsWorkspace and BoardUI; explicitly FAKE team/phone host. No Android, pairing, display sessions, model calls or native admission acceptance.',
    browserVersion: browserVersion || null, generatedAt: new Date().toISOString(), nativeAndroidVerified: false,
    expectedJourneys: 4, results,
  };
  await fs.writeFile(path.join(output, 'results.json'), JSON.stringify(receipt, null, 2));
}
if (results.length !== 4 || results.some(result => !result.passed)) {
  for (const result of results.filter(value => !value.passed)) process.stderr.write(`${result.name}: ${result.error || result.screenshotError || result.closeError || 'failed'}\n`);
  process.exitCode = 1;
}
