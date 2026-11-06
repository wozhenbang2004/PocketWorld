import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { createServer } from 'vite';
import { chromium, expect } from '@playwright/test';

const root = path.resolve(import.meta.dirname, '..');
const output = process.env.OVERLAY_DEPTH_EVIDENCE || path.resolve(root, '../build-artifacts/depth-overlays');
const cache = '/dev/shm/pw-depth-overlays-cache';
await fs.mkdir(output, { recursive: true });
await fs.mkdir(path.join(cache, 'browser'), { recursive: true });
process.env.TMPDIR = path.join(cache, 'browser');
const server = await createServer({ root, cacheDir: path.join(cache, 'vite'), logLevel: 'error',
  optimizeDeps: { entries: ['tests/depth-overlays.html'] }, server: { host: '127.0.0.1', port: 0, hmr: false, watch: null } });
await server.listen();
let browser;
const records = [];
const scenes = ['conversation', 'quote', 'origin', 'sources', 'sources-read', 'approval', 'approval-pending', 'usage', 'question'];
const answer = '请先核对周末出行时间和路线，涉及预订或付款时先交给我确认。';
const probe = page => page.evaluate(() => window.overlayDepth);
const events = async page => (await probe(page)).events;
async function foreground(target) {
  await expect(target).toHaveCount(1);
  await expect(target).toBeVisible();
  await expect.poll(() => target.evaluate(element => !element.closest('[inert],[aria-hidden="true"]') &&
    (!element.hasAttribute('data-visible') || element.getAttribute('data-visible') === 'true'))).toBe(true);
  const box = await target.boundingBox();
  assert.ok(box && box.width > 0 && box.height > 0, 'arrival object has a rendered area');
  return box;
}
try {
  const executablePath = process.env.UIUX_CHROMIUM || process.env.PW_CHROMIUM;
  browser = await chromium.launch({ headless: true, ...(executablePath ? { executablePath } : {}) });
  for (const width of [360, 1280]) for (const scene of scenes) {
    const page = await browser.newPage({ viewport: { width, height: 900 }, locale: 'zh-CN', reducedMotion: 'reduce' });
    page.setDefaultTimeout(8000);
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    try {
      await page.goto(`${server.resolvedUrls.local[0]}tests/depth-overlays.html?scene=${scene}`, { waitUntil: 'networkidle' });
      await page.waitForFunction(() => window.overlayDepth?.ready === true);
      const host = await probe(page), ready = host.events.find(event => event.action === 'app.ready');
      assert.ok(ready, 'real bridge sent app.ready');
      assert.equal(ready.args.documentId, ready.documentId);
      assert.equal(ready.hostDocumentToken, 'overlay-depth-document');
      assert.equal(host.documentEpoch, 1);
      assert.ok(host.commands.includes('sources.read'));
      assert.deepEqual(host.fixtureErrors, []);
      let target, object;
      if (scene === 'conversation') {
        target = page.getByRole('dialog', { name: '整理对话', exact: true }); object = 'conversation-1 / org-selection-1';
        await expect(target.getByRole('textbox', { name: '对话名称', exact: true })).toHaveValue('周末出行安排');
        await expect(target.getByRole('checkbox', { name: '置顶到对话列表前面', exact: true })).toBeChecked();
        await expect(target.getByRole('button', { name: '保存修改', exact: true })).toBeEnabled();
        assert.deepEqual((await events(page)).find(event => event.action === 'conversation.organization.read').args, { conversationId: 'conversation-1' });
      } else if (scene === 'quote') {
        target = page.getByRole('dialog', { name: '引用这条消息', exact: true }); object = 'answer-1 / quote-window-1';
        await expect(target.getByRole('textbox', { name: '长按或拖动选择原文', exact: true })).toHaveValue(answer);
        await expect(target.getByRole('button', { name: '加入草稿', exact: true })).toBeEnabled();
        assert.deepEqual((await events(page)).find(event => event.action === 'messageQuote.open').args,
          { messageId: 'answer-1', sourceRevision: 1, key: 'draft-1', epoch: 1 });
      } else if (scene === 'origin') {
        target = page.getByRole('dialog', { name: '这次答复的原请求', exact: true }); object = 'answer-1 -> request-1';
        await expect(target.getByText(answer, { exact: true })).toBeVisible();
        assert.deepEqual((await events(page)).find(event => event.action === 'message.origin').args,
          { messageId: 'answer-1', sourceMessageId: 'request-1', offset: 0 });
      } else if (scene.startsWith('sources')) {
        target = page.getByRole('dialog', { name: '回答来源', exact: true }); object = 'answer-1 / source-window-1';
        const row = target.getByRole('button', { name: '查看原保留内容：出行安排原始说明', exact: true });
        await expect(row).toBeVisible();
        assert.deepEqual((await events(page)).find(event => event.action === 'sources.open').args, { messageId: 'answer-1' });
        if (scene === 'sources-read') {
          await row.click();
          target = page.getByRole('dialog', { name: '出行安排原始说明', exact: true }); object += ' / source-handle-1';
          await expect(target.locator('.pw-source-copy')).toHaveText(answer);
          await expect(target.getByText('https://example.invalid/original', { exact: true })).toBeVisible();
          assert.deepEqual((await events(page)).find(event => event.action === 'sources.read').args,
            { windowId: 'source-window-1', sourceHandle: 'source-handle-1', cursor: 0, offset: 0 });
        }
      } else if (scene.startsWith('approval')) {
        target = page.getByRole('region', { name: '本次操作授权', exact: true }); object = 'approval-1 / submit_form';
        await expect(target.getByRole('heading', { name: 'submit_form', exact: true })).toBeVisible();
        await expect(target.getByText('原申请中的接收方', { exact: true })).toBeVisible();
        await expect(target.getByText('核对完成后再提交这段内容。', { exact: true })).toBeVisible();
        const allow = target.getByRole('button', { name: '允许这次操作', exact: true });
        const reject = target.getByRole('button', { name: '拒绝', exact: true });
        if (scene === 'approval-pending') { await expect(allow).toBeDisabled(); await expect(reject).toBeDisabled(); }
        else { await expect(allow).toBeEnabled(); await expect(reject).toBeEnabled(); }
      } else if (scene === 'usage') {
        target = page.getByRole('region', { name: '原任务模型调用与用量', exact: true }); object = 'task-1 / readOnly usage';
        await target.getByRole('button', { name: '查看累计模型调用与用量', exact: true }).click();
        await expect(target.getByRole('heading', { name: '本任务的累计模型调用与用量', exact: true })).toBeVisible();
        await expect(target.getByText('实际账单与套餐剩余量未观察。', { exact: true })).toBeVisible();
        assert.equal((await events(page)).find(event => event.action === 'usage.task').args.taskId, 'task-1');
      } else {
        target = page.getByRole('region', { name: '回答原问题', exact: true }); object = 'owner-question-1';
        await expect(target.getByRole('heading', { name: '这次行程优先考虑哪一种方案？', exact: true })).toBeVisible();
        await expect(target.getByRole('button', { name: '优先节省时间', exact: true })).toBeEnabled();
        await expect(target.getByRole('button', { name: '发给原任务', exact: true })).toBeDisabled();
      }
      const box = await foreground(target);
      await page.evaluate(async () => { await document.fonts.ready; await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))); });
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1);
      assert.equal(overflow, false);
      assert.deepEqual(errors, []);
      assert.deepEqual((await probe(page)).fixtureErrors, []);
      const filename = `${scene}-${width}.png`;
      await page.screenshot({ path: path.join(output, filename), animations: 'disabled', timeout: 15000, fullPage: true });
      // Run transitions after the unchanged scene screenshot, without weakening
      // the component's selection, read-handle or one-use native receipt gates.
      if (scene === 'conversation') {
        await target.getByRole('button', { name: '取消', exact: true }).click();
        assert.deepEqual((await events(page)).find(event => event.action === 'conversation.organization.close').args, { selectionHandle: 'org-selection-1' });
      } else if (scene === 'quote') {
        await target.getByRole('button', { name: '加入草稿', exact: true }).click();
        assert.deepEqual((await events(page)).find(event => event.action === 'messageQuote.stage').args, { windowId: 'quote-window-1', offset: 0, chars: answer.length });
        await expect(target.getByRole('button', { name: '加入草稿', exact: true })).toBeDisabled();
      } else if (scene === 'sources-read') {
        await target.getByRole('button', { name: '返回来源列表', exact: true }).click();
        const row = page.getByRole('button', { name: '查看原保留内容：出行安排原始说明', exact: true });
        await expect(row).toBeVisible();
        await expect(row).toBeFocused();
        await row.click();
        await expect(page.locator('.pw-source-copy')).toHaveText(answer);
        assert.equal((await events(page)).filter(event => event.action === 'sources.read').at(-1).args.sourceHandle, 'source-handle-2');
      } else if (scene === 'approval') {
        await target.getByRole('button', { name: '拒绝', exact: true }).click();
        assert.deepEqual((await events(page)).at(-1), { approvalId: 'approval-1', approved: false });
      } else if (scene === 'question') {
        await target.getByRole('button', { name: '优先节省时间', exact: true }).click();
        await expect(target.getByRole('status')).toHaveText('回答已保留在原手机任务；是否继续以原任务状态为准。');
        assert.deepEqual((await events(page)).find(event => event.action === 'tasks.answerQuestionChoice').args, { optionId: 'fast' });
        assert.equal((await events(page)).filter(event => event.action === 'tasks.answerQuestionChoice').length, 1);
      }
      assert.deepEqual(errors, []);
      assert.deepEqual((await probe(page)).fixtureErrors, []);
      records.push({ scene, width, status: 'passed', screenshot: path.join(output, filename),
        arrival: { object, box, foreground: true, handshake: 'real app.ready before render' }, errors, overflow, sourceUnchanged: true });
      console.log(`${scene} ${width} passed`);
    } catch (error) {
      records.push({ scene, width, status: 'failed', error: String(error.stack || error), errors });
    } finally { await page.close(); }
  }
} finally {
  await browser?.close();
  await server.close();
  await fs.writeFile(path.join(output, 'result.json'), JSON.stringify({
    scope: 'Real React overlays using the unmodified bridge and an explicitly advertised synthetic native transport. Not Android, native persistence or native authority acceptance.',
    visualScope: 'Reduced motion and finite animations disabled for static screenshots; no animation-timing claim.', records }, null, 2));
}
console.log(JSON.stringify(records.map(({ scene, width, status }) => ({ scene, width, status }))));
if (records.length !== 18 || records.some(record => record.status !== 'passed')) process.exitCode = 1;
