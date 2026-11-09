/**
 * Real production bundle + the existing SYNTHETIC AI host, with conversation receipts.
 * Run after the production build:
 *   node web-ui/tests/ai-conversation-ui.mjs --output build-artifacts/ai-conversation-visual
 * Or pass --bundle <existing production bundle>. No alternate app is rendered.
 * Browser assertions cover behavior/geometry; saved screenshots still need visual review.
 * No Android device, real accounts/providers, inference, listener or clipboard validation.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { chromium, expect } from '@playwright/test';
import { installAiCenterHost } from './ai-center-host-fixture.mjs';
import { installAiConversationHost } from './ai-conversation-host-fixture.mjs';

const root = path.resolve(import.meta.dirname, '..');
function argument(name, fallback) {
  const at = process.argv.indexOf(name);
  if (at < 0) return fallback;
  const value = process.argv[at + 1];
  if (!value || value.startsWith('--')) throw new Error(`Missing value for ${name}`);
  return path.resolve(value);
}
const bundle = argument('--bundle', path.resolve(root, '../app/src/main/assets/pocket-ui'));
const output = argument('--output', path.resolve(root, '../build-artifacts/ai-conversation-visual'));
const matrix = [390, 320, 1280].flatMap(width => [false, true].map(dark => ({ width, height: 844, dark })));
const record = {
  scope: 'Production renderer: conversation/navigation at 390, 320 and 1280px in light/dark; synthetic native receipts. Not Android, IME, real provider or listener validation.',
  synthetic: true, outcome: 'not_run', visualReview: 'not_performed_by_runner',
  startedAt: new Date().toISOString(), matrix, checks: [], screenshots: [], errors: [], scenes: [],
};
let browser, server;
await fs.mkdir(output, { recursive: true });
const passed = (scene, name, evidence) => record.checks.push({ scene, name, passed: true, ...(evidence ? { evidence } : {}) });

async function runScene(origin, { width, height, dark }) {
  const scene = `${width}-${dark ? 'dark' : 'light'}`;
  const context = await browser.newContext({ viewport: { width, height }, isMobile: width < 1024,
    hasTouch: width < 1024, deviceScaleFactor: 1, locale: 'zh-CN', timezoneId: 'Asia/Shanghai',
    colorScheme: dark ? 'dark' : 'light', reducedMotion: 'reduce', serviceWorkers: 'block' });
  const externalRequests = [];
  await context.route('**/*', route => {
    if (new URL(route.request().url()).origin === origin) return route.continue();
    externalRequests.push(route.request().url()); return route.abort();
  });
  // A single init script gives the base host and extension a defined installation order.
  await context.addInitScript({ content: `(${installAiCenterHost.toString()})(${JSON.stringify({ dark })});(${installAiConversationHost.toString()})({representative:true});` });
  const page = await context.newPage();
  page.setDefaultTimeout(12000);
  page.on('pageerror', error => record.errors.push({ scene, error: error.message }));
  const summary = () => page.evaluate(() => window.aiConversationFixture.summary());
  const calls = action => page.evaluate(action => window.aiConversationFixture.calls.filter(row => row.action === action), action);
  const count = async action => (await calls(action)).length;
  const receipts = action => page.evaluate(action => window.aiConversationFixture.receipts.filter(row => row.action === action && row.ok).map(row => row.value), action);
  const panel = () => page.getByRole('dialog', { name: '模型与推理', exact: true });
  const panelTab = name => panel().getByRole('tablist', { name: '此对话模型设置', exact: true }).getByRole('tab', { name, exact: true });
  const modelRow = model => panel().getByRole('button', { name: `选择模型：${model}，工作 OpenAI · 测试`, exact: true });
  const chosen = model => modelRow(model).getByLabel('此对话已选模型', { exact: true });
  const openNavigation = async () => { if (width < 1024) await page.getByRole('button', { name: '打开导航', exact: true }).click(); };
  const closeNavigation = async () => { if (width < 1024) await page.getByRole('button', { name: '关闭导航', exact: true }).click(); };
  const center = () => page.locator('.pw-ai-center');
  const aiTab = name => center().getByRole('tablist', { name: 'AI 接入管理', exact: true }).getByRole('tab', { name, exact: true });
  const backToCenter = () => page.locator('.pw-secondary-header').getByRole('button', { name: '返回 AI 接入', exact: true }).click();
  const shot = async (label, surface = page.locator('.pw-app')) => {
    await page.evaluate(() => document.fonts.ready);
    await page.mouse.move(0, 0);
    const geometry = await surface.evaluate(node => {
      const controls = [...node.querySelectorAll('button, input, textarea, [role="tab"]')].filter(element => {
        const rect = element.getBoundingClientRect(), style = getComputedStyle(element);
        return !element.closest('[hidden], [aria-hidden="true"]') && style.visibility !== 'hidden' && style.display !== 'none'
          && rect.width > 2 && rect.height > 2 && rect.bottom > 0 && rect.top < innerHeight;
      });
      return { viewportWidth: innerWidth, documentWidth: document.documentElement.scrollWidth,
        clippedControls: controls.map(element => ({ name: element.getAttribute('aria-label') || element.textContent?.trim().slice(0,80),
          left: element.getBoundingClientRect().left, right: element.getBoundingClientRect().right }))
          .filter(rect => rect.left < -1 || rect.right > innerWidth + 1) };
    });
    const file = `${scene}-${label}.png`;
    await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
    record.screenshots.push({ scene, file, synthetic: true });
    assert.ok(geometry.documentWidth <= geometry.viewportWidth + 1, `${file}: document horizontal overflow`);
    assert.deepEqual(geometry.clippedControls, [], `${file}: visible controls extend beyond the viewport`);
    return geometry;
  };
  const assertDraft = async draft => {
    await expect(page.getByRole('textbox', { name: '工作输入', exact: true })).toHaveValue(draft);
    assert.equal((await summary()).composerText, draft);
  };
  const assertReasoningWire = async (previous, level) => {
    const writes = await calls('models.conversation.reasoning'), write = writes.at(-1);
    const review = (await receipts('models.conversation.review')).find(row => row.reviewHandle === write.args.reviewHandle);
    assert.ok(review, 'Reasoning write must use an actually issued native review');
    assert.equal(review.scopeKey, previous.scopeKey); assert.equal(review.revision, previous.revision);
    assert.deepEqual(write.args, { reviewHandle: review.reviewHandle, reasoning: { mode: 'OPENAI_EFFORT', level, budgetTokens: 0 } });
    const after = (await summary()).preference;
    assert.equal(after.revision, previous.revision + 1);
    assert.deepEqual(after.target, { ...previous.target, reasoning: { mode: 'OPENAI_EFFORT', level, budgetTokens: 0 } }, 'Reasoning must retain model identity and output/image budgets');
    return { args: write.args, scopeKey: after.scopeKey, preferenceRevision: after.revision };
  };
  const chooseReasoning = async label => {
    const group = panel().getByRole('radiogroup', { name: '可用推理强度', exact: true });
    await group.waitFor();
    // React Aria's real input is visually hidden; activate its visible label.
    await group.getByText(label, { exact: true }).click();
  };
  const assertNoPrematureSuccess = async previous => {
    assert.deepEqual((await summary()).preference, previous, 'Pending/unknown must not change the confirmed preference');
    await expect(panel().getByText(/^(模型|推理)已保存$/)).toHaveCount(0);
  };
  const returnFromPanel = async draft => {
    await page.keyboard.press('Escape');
    await expect(panel()).toHaveCount(0);
    await assertDraft(draft);
  };
  try {
    await page.goto(origin, { waitUntil: 'networkidle' });
    const input = page.getByRole('textbox', { name: '工作输入', exact: true });
    await input.waitFor();
    await expect(page.locator('html')).toHaveClass(dark ? /dark/ : /^(?!.*\bdark\b)/);
    const draft = '保留刚写的方案：\n把模型选择和推理留在这次对话里，返回后继续编辑。';
    await input.fill(draft);
    await expect.poll(async () => (await summary()).composerText).toBe(draft);
    const focusSurface = await page.locator('.pw-composer-card').evaluate(node => {
      const shadow = getComputedStyle(node).boxShadow;
      const rings = shadow.replace(/(?:rgba?|hsla?)\([^)]*\)/g, '').split(',').map(layer => [...layer.matchAll(/(-?[\d.]+)px/g)].map(match => Number(match[1])))
        .filter(lengths => lengths.length >= 4 && Math.abs(lengths[0]) < .01 && Math.abs(lengths[1]) < .01 && Math.abs(lengths[2]) < .01 && Math.abs(lengths[3]) >= 2);
      return { boxShadow: shadow, solidFocusRings: rings };
    });
    assert.deepEqual(focusSurface.solidFocusRings, [], 'Focused composer must not restore the old solid 2px shadow ring');
    passed(scene, 'Focused composer retains a soft surface instead of a solid shadow ring', focusSurface);
    await expect(page.getByRole('button', { name: '切换此对话模型：fixture-reasoner-B', exact: true })).toBeVisible();
    await shot('conversation');
    const longReply = page.locator('[data-message-id="fixture-answer-completed"]');
    await expect(longReply).toContainText('如果来源缺失，先保留缺口，再决定下一步。');
    const completed = page.getByRole('button', { name: '查看任务详情：整理本周的设计反馈', exact: true });
    await expect(completed).toHaveClass(/pw-linked-task-complete/);
    await expect(completed).toContainText('已完成'); await expect(completed).toContainText('查看详情');
    await expect(completed.getByText('整理本周的设计反馈', { exact: true })).toHaveCount(0);
    await page.locator('.pw-conversation-scroll').evaluate(node => node.scrollTo({ top: 0 }));
    await shot('long-answer');
    await completed.scrollIntoViewIfNeeded();
    await shot('completed-detail-link');
    const failed = page.getByRole('button', { name: '查看任务详情：读取缺失的参考资料', exact: true });
    await expect(failed).not.toHaveClass(/pw-linked-task-complete/);
    await expect(failed).toContainText('读取缺失的参考资料'); await expect(failed).toContainText('查看失败原因');
    const unknown = page.getByRole('button', { name: '查看任务详情：核对第一个更新回执', exact: true });
    await expect(unknown).not.toHaveClass(/pw-linked-task-complete/);
    await expect(unknown).toContainText('等待核对动作'); await expect(unknown).toContainText('核对操作结果');
    await unknown.scrollIntoViewIfNeeded();
    await shot('unresolved-tasks');
    await expect(page.getByRole('button', { name: '核对操作结果：核对第二个更新回执', exact: true })).toBeVisible();
    const progress = page.getByText('最近进展 · 正在比对两份已读取的资料', { exact: true });
    await progress.scrollIntoViewIfNeeded(); await expect(progress).toBeVisible();
    await expect(page.getByRole('button', { name: '查看当前回复的执行过程', exact: true })).toBeVisible();
    await shot('current-progress');
    passed(scene, 'Long answers retain full content; successful tasks collapse while failed/unknown actions and exact-run progress remain visible');

    await openNavigation();
    const recent = page.getByRole('region', { name: '最近对话', exact: true });
    const workspaces = page.getByRole('navigation', { name: '工作区', exact: true });
    await recent.waitFor(); await workspaces.waitFor();
    const recentBox = await recent.boundingBox(), workBox = await workspaces.boundingBox();
    assert.ok(recentBox && workBox && recentBox.y + recentBox.height <= workBox.y + 1, 'Recent conversations must appear before the functional directory');
    const firstConversation = recent.getByRole('button', { name: '继续整理上次的设计反馈', exact: true });
    await expect(firstConversation).toBeInViewport({ ratio: 1 });
    const reachability = await firstConversation.evaluate(node => {
      const rect = node.getBoundingClientRect(), hit = document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2);
      return { x: rect.x, y: rect.y, width: rect.width, height: rect.height, viewportHeight: innerHeight,
        inFirstViewport: rect.top >= 0 && rect.bottom <= innerHeight && rect.left >= 0 && rect.right <= innerWidth,
        receivesPointer: !!hit && (hit === node || node.contains(hit)) };
    });
    assert.equal(reachability.inFirstViewport, true, 'A recent conversation must be visible before any scrolling');
    assert.equal(reachability.receivesPointer, true, 'The first visible conversation must not be obscured');
    await shot('navigation', width < 1024 ? page.getByRole('dialog', { name: 'PocketWorld', exact: true }) : page.locator('.pw-app'));
    await firstConversation.click();
    await expect.poll(() => count('conversation.select')).toBe(1);
    assert.deepEqual((await calls('conversation.select'))[0].args, { id: 'fixture-conversation-1' });
    await assertDraft(draft);
    passed(scene, 'Recent conversations precede workspaces and a real first-screen row is clickable', reachability);

    await openNavigation();
    await page.getByRole('navigation', { name: 'AI 接入与设置', exact: true }).getByRole('link', { name: 'AI 接入', exact: true }).click();
    await center().getByRole('heading', { name: '你的服务', exact: true }).waitFor();
    await openNavigation();
    await expect(page.getByRole('link', { name: 'AI 接入', exact: true })).toHaveAttribute('aria-current', 'page');
    assert.notEqual(await page.getByRole('link', { name: '设置', exact: true }).getAttribute('aria-current'), 'page');
    await closeNavigation();
    const serviceQuery = center().getByLabel('搜索服务或账号', { exact: true });
    await serviceQuery.fill('Anthropic');
    await expect(center().getByRole('button', { name: /写作 Anthropic · 测试/ })).toBeVisible();
    await expect(center().getByRole('button', { name: /工作 OpenAI · 测试/ })).toHaveCount(0);
    await aiTab('调度').click();
    await center().getByRole('button', { name: '本机 API', exact: true }).click();
    await expect(center()).toHaveAttribute('data-ai-view', 'proxy');
    await center().getByRole('heading', { name: '本机 API', exact: true }).waitFor();
    await expect(page.locator('.pw-secondary-header').getByRole('heading', { name: 'AI 接入', exact: true })).toBeVisible();
    await shot('local-api', center());
    await backToCenter();
    await expect(aiTab('调度')).toHaveAttribute('aria-selected', 'true');
    await aiTab('服务').click();
    await expect(serviceQuery).toHaveValue('Anthropic');
    await expect(center().getByRole('button', { name: /写作 Anthropic · 测试/ })).toBeVisible();
    await expect(center().getByRole('button', { name: /工作 OpenAI · 测试/ })).toHaveCount(0);
    await shot('services-return', center());

    await aiTab('模型').click();
    const centerPicker = center().getByRole('region', { name: '统一模型目录', exact: true });
    await centerPicker.getByLabel('搜索模型或来源', { exact: true }).fill('fixture-reasoner');
    await centerPicker.getByRole('tab', { name: '收藏', exact: true }).click();
    await expect(centerPicker.getByText('fixture-reasoner', { exact: true })).toBeVisible();
    const oldWindow = (await receipts('models.api.open')).at(-1)?.windowId;
    assert.ok(oldWindow);
    await center().getByRole('button', { name: '本机 API', exact: true }).click();
    await expect(center()).toHaveAttribute('data-ai-view', 'proxy');
    await backToCenter();
    await expect(aiTab('模型')).toHaveAttribute('aria-selected', 'true');
    await expect(centerPicker.getByLabel('搜索模型或来源', { exact: true })).toHaveValue('fixture-reasoner');
    await expect(centerPicker.getByRole('tab', { name: '收藏', exact: true })).toHaveAttribute('aria-selected', 'true');
    await expect(centerPicker.getByText('fixture-reasoner', { exact: true })).toBeVisible();
    const newWindow = (await receipts('models.api.open')).at(-1)?.windowId;
    assert.ok(newWindow && newWindow !== oldWindow, 'Returning must read a fresh native picker window, not retain a retired ticket');
    assert.ok((await calls('models.api.close')).some(row => row.args.windowId === oldWindow));
    await shot('models-return', center());
    await center().getByRole('button', { name: '本机 API', exact: true }).click();
    await expect(center()).toHaveAttribute('data-ai-view', 'proxy');
    await openNavigation();
    await expect(page.getByRole('link', { name: 'AI 接入', exact: true })).toHaveAttribute('aria-current', 'page');
    assert.notEqual(await page.getByRole('link', { name: '设置', exact: true }).getAttribute('aria-current'), 'page');
    await page.getByRole('link', { name: 'AI 接入', exact: true }).click();
    await expect(center()).toHaveAttribute('data-ai-view', 'main');
    await expect(aiTab('服务')).toHaveAttribute('aria-selected', 'true');
    await page.locator('.pw-secondary-header').getByRole('button', { name: '返回', exact: true }).click();
    await assertDraft(draft);
    passed(scene, 'AI entry highlights correctly; local API returns to the same tab/query/filter with a fresh native window', { oldWindow, newWindow });

    if (width >= 1024) {
      const selectsBeforeCompact = await count('conversation.select');
      await page.getByRole('button', { name: '收起导航', exact: true }).click();
      const chatLink = page.getByRole('navigation', { name: '工作区', exact: true }).getByRole('link', { name: '对话', exact: true });
      await expect(chatLink).toBeVisible();
      await page.getByRole('link', { name: 'AI 接入', exact: true }).click();
      await center().getByRole('heading', { name: '你的服务', exact: true }).waitFor();
      await shot('compact-navigation');
      await chatLink.click();
      await assertDraft(draft);
      assert.equal(await count('conversation.select'), selectsBeforeCompact, 'Compact return must keep the current conversation');
      assert.equal(await count('conversation.new'), 0, 'Returning to a draft must not create a new conversation');
      await page.getByRole('button', { name: '展开导航', exact: true }).click();
      await expect(page.getByRole('region', { name: '最近对话', exact: true })).toBeVisible();
      passed(scene, 'Collapsed desktop navigation returns to the same conversation and draft without creating a conversation');
    }

    await page.getByRole('button', { name: '切换此对话模型：fixture-reasoner-B', exact: true }).click();
    await panel().waitFor();
    const originalPanel = await panel().elementHandle();
    await expect(chosen('fixture-reasoner-B')).toBeVisible();
    await expect(chosen('fixture-reasoner')).toHaveCount(0);
    assert.equal((await summary()).globalModel, 'fixture-reasoner');
    await shot('model-picker', panel());
    const writesBeforeDetails = await count('models.conversation.api.select');
    await panel().getByRole('button', { name: '查看模型详情：fixture-reasoner-B，工作 OpenAI · 测试', exact: true }).click();
    await panel().getByRole('button', { name: '返回模型', exact: true }).click();
    assert.equal(await count('models.conversation.api.select'), writesBeforeDetails, 'Opening details must not choose a model');
    passed(scene, 'A global default and a different model on the same connection mark only the conversation choice');

    await panelTab('推理').click();
    const beforeReasoning = (await summary()).preference;
    await chooseReasoning('高');
    await expect(panel().getByText('推理已保存', { exact: true })).toBeVisible();
    await expect(panel().getByRole('button', { name: /^(应用|应用到此对话)$/ })).toHaveCount(0);
    const reasoningEvidence = await assertReasoningWire(beforeReasoning, 'high');
    assert.equal((await summary()).preference.target.outputTokens, 2048);
    assert.equal((await summary()).preference.target.imageTokenReserve, 256);
    await shot('reasoning-saved', panel());
    await panelTab('模型').click();
    await modelRow('fixture-reasoner').waitFor();
    const beforeSelection = (await summary()).preference;
    await page.evaluate(() => window.aiConversationFixture.holdNextWrite());
    await modelRow('fixture-reasoner').click();
    await expect.poll(() => count('models.conversation.api.select')).toBe(writesBeforeDetails + 1);
    await expect(panel().getByText('正在保存模型…', { exact: true })).toBeVisible();
    await assertNoPrematureSuccess(beforeSelection);
    const write = (await calls('models.conversation.api.select')).at(-1);
    const issuedReview = (await receipts('models.api.review')).find(row => row.reviewHandle === write.args.reviewHandle);
    assert.ok(issuedReview);
    assert.equal(issuedReview.model.modelId, 'fixture-reasoner');
    assert.deepEqual(write.args, { windowId: issuedReview.windowId, reviewHandle: issuedReview.reviewHandle, expectedPreferenceRevision: beforeSelection.revision });
    await page.evaluate(() => window.aiConversationFixture.resolveWrite('saved'));
    await expect(panel().getByText('模型已保存', { exact: true })).toBeVisible();
    await expect(chosen('fixture-reasoner')).toBeVisible();
    await expect(chosen('fixture-reasoner-B')).toHaveCount(0);
    await expect(panel().getByRole('button', { name: '用于本对话', exact: true })).toHaveCount(0);
    assert.equal((await summary()).preference.target.modelId, 'fixture-reasoner');
    assert.equal((await summary()).preference.revision, beforeSelection.revision + 1);
    assert.equal(await count('models.api.select'), 0, 'Conversation selection must never write the global default');
    await panelTab('推理').click();
    const selectedPreference = (await summary()).preference;
    await chooseReasoning('高');
    await expect(panel().getByText('推理已保存', { exact: true })).toBeVisible();
    await assertReasoningWire(selectedPreference, 'high');
    assert.equal(await originalPanel.evaluate(node => node.isConnected), true, 'Model and reasoning changes must stay in the same panel');
    passed(scene, 'One model click obtains and consumes the original review; reasoning saves immediately in the same panel', { modelWrite: write.args, reasoning: reasoningEvidence });
    await returnFromPanel(draft);
    await expect(page.getByRole('button', { name: '推理强度：高', exact: true })).toBeVisible();

    if (width === 390 && !dark) {
      // Explicitly hold native receipts. Pending and unknown are not optimistic success.
      await page.getByRole('button', { name: '推理强度：高', exact: true }).click();
      const beforeUnknown = (await summary()).preference, reasoningCount = await count('models.conversation.reasoning');
      await page.evaluate(() => window.aiConversationFixture.holdNextWrite());
      await chooseReasoning('低');
      await expect.poll(() => count('models.conversation.reasoning')).toBe(reasoningCount + 1);
      await expect(panel().getByText('正在保存推理…', { exact: true })).toBeVisible();
      await assertNoPrematureSuccess(beforeUnknown);
      await expect(panelTab('模型')).toBeDisabled();
      assert.equal(await page.evaluate(() => window.PocketWeb.handleBack()), true);
      await expect(panel()).toBeVisible();
      await assertNoPrematureSuccess(beforeUnknown);
      await shot('reasoning-pending', panel());
      await page.evaluate(() => window.aiConversationFixture.resolveWrite('unknown'));
      await expect(panel().getByRole('alert')).toContainText('保存结果待核对');
      await expect(panel().getByRole('alert')).toContainText('没有自动重试');
      await assertNoPrematureSuccess(beforeUnknown);
      await shot('reasoning-unknown', panel());
      await panel().getByRole('button', { name: '重新读取', exact: true }).click();
      await panel().getByRole('radiogroup', { name: '可用推理强度', exact: true }).waitFor();
      assert.equal(await count('models.conversation.reasoning'), reasoningCount + 1, 'Read-after-unknown must not replay a reasoning write');
      await assertNoPrematureSuccess(beforeUnknown);

      await panelTab('模型').click();
      await modelRow('fixture-reasoner-B').waitFor();
      const modelCount = await count('models.conversation.api.select');
      await page.evaluate(() => window.aiConversationFixture.holdNextWrite());
      await modelRow('fixture-reasoner-B').click();
      await expect.poll(() => count('models.conversation.api.select')).toBe(modelCount + 1);
      await expect(panel().getByText('正在保存模型…', { exact: true })).toBeVisible();
      await assertNoPrematureSuccess(beforeUnknown);
      await page.keyboard.press('Escape');
      await expect(panel()).toBeVisible();
      await page.evaluate(() => window.aiConversationFixture.resolveWrite('unknown'));
      await expect(panel().getByRole('alert')).toContainText('没有自动重试');
      await assertNoPrematureSuccess(beforeUnknown);
      await shot('model-unknown', panel());
      await panel().getByRole('button', { name: '重新读取', exact: true }).click();
      await expect(chosen('fixture-reasoner')).toBeVisible();
      await expect(chosen('fixture-reasoner-B')).toHaveCount(0);
      assert.equal(await count('models.conversation.api.select'), modelCount + 1, 'Read-after-unknown must not replay a model write');
      await returnFromPanel(draft);
      passed(scene, 'Pending and unknown keep the last confirmed choice; back cannot dismiss a pending write; reread never resubmits');
    }
    const result = await summary();
    assert.equal(result.globalModel, 'fixture-reasoner');
    assert.deepEqual(result.globalNativeSelection.reasoning, { mode: 'OPENAI_EFFORT', level: 'medium', budgetTokens: 0 });
    assert.equal(result.globalNativeSelection.maxOutputTokens, 4096);
    assert.deepEqual(result.errors, []);
    assert.equal(result.pending, null);
    for (const action of ['composer.send', 'models.api.select', 'models.accounts.select', 'proxy.confirmStart']) assert.equal(await count(action), 0, `Journey unexpectedly sent ${action}`);
    assert.deepEqual(externalRequests, [], 'Synthetic renderer must not contact a real service');
    await assertDraft(draft);
    record.scenes.push({ scene, passed: true, fixture: result });
  } catch (error) {
    record.checks.push({ scene, name: 'Conversation journey', passed: false, error: String(error.stack || error) });
    const file = `${scene}-failure.png`;
    try { await page.screenshot({ path: path.join(output, file), animations: 'disabled' }); record.screenshots.push({ scene, file, synthetic: true, failure: true }); } catch {}
    record.scenes.push({ scene, passed: false, fixture: await summary().catch(() => null) });
  } finally { await context.close(); }
}

try {
  const index = await fs.readFile(path.join(bundle, 'index.html'), 'utf8');
  const asset = index.match(/assets\/(index-[^" ]+\.js)/)?.[1];
  assert.ok(asset, 'Expected a Vite production bundle, not a source fixture or development server');
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
  // Normal CI launch only: no fallback browser, policy override, flags or silent retry.
  browser = await chromium.launch({ headless: true });
  record.outcome = 'executed';
  for (const scene of matrix) await runScene(origin, scene);
} catch (error) {
  record.errors.push({ stage: browser ? 'runner' : 'setup', error: String(error.stack || error) });
} finally {
  if (browser) await browser.close();
  if (server?.listening) await new Promise(resolve => server.close(resolve));
  record.finishedAt = new Date().toISOString();
  record.passed = record.outcome === 'executed' && record.scenes.length === matrix.length && record.scenes.every(scene => scene.passed) && !record.errors.length;
  await fs.writeFile(path.join(output, 'results.json'), JSON.stringify(record, null, 2) + '\n');
}
console.log(JSON.stringify(record));
if (!record.passed) process.exitCode = 1;
