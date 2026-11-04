import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { chromium } from '@playwright/test';

// Actual production Web bundle, isolated synthetic native receipts. This verifies
// the selected UI journey, not Android admission, SQLite delivery or device control.
const argument = flag => { const index = process.argv.indexOf(flag); if (index < 0 || !process.argv[index + 1]) throw new Error('Required --bundle <directory> --output <directory>'); return path.resolve(process.argv[index + 1]); };
const bundle = argument('--bundle'), output = argument('--output');
const record = { scope: 'Actual production Web UI with synthetic native bridge; Android/native execution and persistence are not verified.', viewport: { width: 360, height: 800 }, checks: [], errors: [], screenshots: [] };

function fixture(mode) {
  const conversation = 'original-conversation', task = 'original-phone-task';
  const snapshot = {
    state: { conversationId: conversation, conversations: [{ id: conversation, title: '我的日程安排' }], messages: [{ id: 'existing', role: 'assistant', content: '我们可以继续安排明天的日程。' }],
      modelName: '已选模型', modelProvider: 'openai_chat', modelRoute: 'api', chatBusy: false, chatSubmitting: false, phoneSubmitting: false, activeTaskId: null,
      tasks: [], projects: [], files: [], features: {}, ownerSnapshots: {}, ownerResponses: {}, ownerPending: [], ownerErrors: {}, moduleResults: {}, moduleBusy: [], moduleErrors: {}, taskActionErrors: {},
      selectedChatAttachments: [{ id: 'attachment', name: '保留的附件.txt', sha256: 'a'.repeat(64), mimeType: 'text/plain', size: 1 }], chatSendMode: 'queue', chatReceipts: {}, modelDrafts: {} },
    composer: { key: 'conversation:' + conversation, epoch: 1, revision: 1, conversationId: conversation, text: '这份聊天草稿仍要保留', mode: 'queue', pending: [], sourceRefs: [] },
    phoneDraft: { text: '', revision: 1, epoch: 1, phone: false, mode: 'background' }, view: { section: null },
  };
  const commands = ['app.ready', 'app.refresh', 'composer.reading', 'composer.intent', 'composer.edit', 'phone.draft.edit', 'phone.prepare', 'phone.start', 'phone.query', 'tasks.open', 'tasks.close'];
  const requests = []; let revision = 0, review, original;
  const document = 'phone-handoff-fixture-document'; window.__pocketDocumentToken = document;
  const publish = () => window.PocketWeb.receive({ v: 1, kind: 'state', revision: ++revision, snapshot: structuredClone({ ...snapshot, capabilities: { commands } }) });
  const reply = (request, value) => window.PocketWeb.receive({ v: 1, kind: 'result', id: request.id, ok: true, value });
  const receipt = status => ({ ...original, status, ...(status === 'accepted' ? { taskId: task, admitted: mode === 'accepted',
    ...(mode === 'accepted' ? { runId: 'original-phone-run', epoch: 1, taskStatus: 'running' } : { taskStatus: 'awaiting_user' }) } : {}),
    message: status !== 'accepted' ? '启动结果待核对，请查询原请求。' : mode === 'accepted' ? '手机任务已获准执行，结果会回到原对话。' : '手机任务已保存到原对话；尚未确认获准执行，请查看原任务。' });
  const accept = () => {
    const value = receipt('accepted'); snapshot.phoneDraft.startReceipt = value;
    if (snapshot.phoneDraft.revision === original.draftRevision && snapshot.composer.epoch === original.composerEpoch) { snapshot.phoneDraft.text = ''; snapshot.phoneDraft.revision++; }
    snapshot.state.tasks = [{ id: task, goal: original.goal, executionMode: 'background', status: mode === 'accepted' ? 'running' : 'awaiting_user',
      raw: { conversationId: conversation, kind: 'phone', ...(mode === 'accepted' ? { runId: 'original-phone-run', coordinatorEpoch: 1 } : {}) } }];
    if (mode === 'accepted') snapshot.state.activeTaskId = task;
    if (!snapshot.state.messages.some(message => message.taskId === task)) snapshot.state.messages.push({ id: 'phone-goal', role: 'user', content: original.goal, taskId: task });
    return value;
  };
  window.PocketNative = { postMessage(raw) {
    const request = JSON.parse(raw); requests.push(request); const args = request.args; let value = {};
    if (request.hostDocumentToken !== document) throw new Error('Wrong fixture document');
    switch (request.action) {
      case 'app.ready': value = { ready: true }; break;
      case 'composer.intent': snapshot.phoneDraft.phone = args.phone; if (args.mode) snapshot.phoneDraft.mode = args.mode; snapshot.composer.epoch++; break;
      case 'composer.edit': snapshot.composer.text = args.text; snapshot.composer.revision++; break;
      case 'phone.draft.edit': snapshot.phoneDraft.text = args.text; snapshot.phoneDraft.revision++; break;
      case 'phone.prepare':
        review = { reviewHandle: 'native-review', requestId: task, conversationId: conversation, conversationTitle: '我的日程安排', goal: args.goal, mode: args.mode,
          modelName: '本次固定模型', provider: 'openai_chat', toolLimit: 40, modelCallLimit: 64, scopeMinutes: mode === 'rejected' ? 0 : 30,
          screenDisclosure: '执行时，屏幕与可见文字会发送到本次固定的模型。', message: '在后台独立屏处理这个目标；连接不可用时等待。' };
        value = review; break;
      case 'phone.start':
        if (args.reviewHandle !== review.reviewHandle || Object.keys(args).length !== 1) throw new Error('Start must only use the reviewed native handle');
        original = { ...review, draftRevision: snapshot.phoneDraft.revision, composerEpoch: snapshot.composer.epoch };
        value = mode === 'accepted' ? accept() : mode === 'rejected' ? { ...original, status: 'rejected', message: '原模型选择已变化。尚未创建任务，草稿仍保留，请重新审阅。' } : receipt('unknown'); snapshot.phoneDraft.startReceipt = value; break;
      case 'phone.query':
        if (args.requestId !== task || args.conversationId !== conversation) throw new Error('Query must retain original identity');
        value = accept(); break;
    }
    publish(); setTimeout(() => reply(request, value), 15);
  } };
  window.phoneFixture = { requests, snapshot, publish, deliver() {
    snapshot.state.tasks[0].status = 'completed'; snapshot.state.activeTaskId = null;
    snapshot.state.messages.push({ id: 'phone-result', role: 'assistant', content: '手机任务结果：已核对日历，明天上午九点没有安排。', taskId: task }); publish();
  } };
}

let server, browser;
try {
  await fs.mkdir(output, { recursive: true });
  const index = await fs.readFile(path.join(bundle, 'index.html'), 'utf8'), asset = index.match(/assets\/(index-[^" ]+\.js)/)?.[1];
  record.bundle = asset; record.bundleSha256 = createHash('sha256').update(await fs.readFile(path.join(bundle, 'assets', asset))).digest('hex');
  server = http.createServer(async (req, res) => {
    try { const name = new URL(req.url, 'http://fixture').pathname, file = path.resolve(bundle, '.' + (name === '/' ? '/index.html' : name));
      if (!file.startsWith(bundle + '/')) throw new Error('Invalid fixture asset');
      const content = await fs.readFile(file); res.writeHead(200, { 'content-type': ({ '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.ttf': 'font/ttf', '.woff2': 'font/woff2', '.svg': 'image/svg+xml' })[path.extname(file)] || 'application/octet-stream' }); res.end(content);
    } catch { res.writeHead(404); res.end(); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); const origin = 'http://127.0.0.1:' + server.address().port;
  browser = await chromium.launch({ headless: true, args: ['--disable-background-networking'] });
  for (const mode of ['unknown', 'accepted', 'rejected']) {
    const context = await browser.newContext({ viewport: record.viewport, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', serviceWorkers: 'block' });
    try {
      await context.route('**/*', route => new URL(route.request().url()).origin === origin ? route.continue() : route.abort());
      await context.addInitScript(fixture, mode); const page = await context.newPage(); page.setDefaultTimeout(8000);
      page.on('pageerror', error => record.errors.push(error.message)); await page.goto(origin, { waitUntil: 'networkidle' });
      const input = page.getByRole('textbox', { name: '工作输入', exact: true });
      await input.waitFor(); assert.equal(await input.inputValue(), '这份聊天草稿仍要保留');
      await page.getByRole('button', { name: '输入方式与选项', exact: true }).click();
      await page.getByText('手机后台独立屏', { exact: true }).click();
      await input.fill('核对明天上午九点的日历安排');
      await page.getByRole('button', { name: '发送', exact: true }).evaluate(button => { button.click(); button.click(); });
      const dialog = page.getByRole('dialog', { name: '核对这次手机任务', exact: true }); await dialog.waitFor();
      await page.waitForFunction(() => { const element = document.querySelector('[aria-describedby="pw-phone-description"]'); return element && element.getBoundingClientRect().bottom <= innerHeight + 1; });
      for (const text of ['我的日程安排', '本次固定模型', '40', '64', mode === 'rejected' ? '不设截止时间' : '30 分钟']) assert.equal(await dialog.getByText(text, { exact: true }).count(), 1);
      assert.match(await dialog.textContent(), /屏幕与可见文字会发送/);
      assert.equal(await page.evaluate(() => window.phoneFixture.requests.filter(request => request.action === 'phone.prepare').length), 1);
      if (mode === 'unknown') { await page.screenshot({ path: path.join(output, 'phone-review-360.png') }); record.screenshots.push('phone-review-360.png'); }
      await dialog.getByRole('button', { name: '开始这次任务', exact: true }).evaluate(button => { button.click(); button.click(); });
      await page.waitForFunction(() => window.phoneFixture.requests.some(request => request.action === 'phone.start'));
      if (mode === 'unknown') {
        await dialog.getByRole('button', { name: '查询原手机请求', exact: true }).waitFor();
        await dialog.getByRole('button', { name: '关闭手机任务核对', exact: true }).click();
        assert.equal(await input.inputValue(), '核对明天上午九点的日历安排');
        await page.getByRole('button', { name: '输入方式与选项', exact: true }).click(); await page.getByText('与主 Agent 对话', { exact: true }).click();
        assert.equal(await input.inputValue(), '这份聊天草稿仍要保留'); assert.equal(await page.getByText('保留的附件.txt', { exact: true }).count(), 1);
        await page.getByRole('button', { name: '查询原手机请求', exact: true }).evaluate(button => { button.click(); button.click(); });
        await page.waitForFunction(() => window.phoneFixture.snapshot.phoneDraft.startReceipt.status === 'accepted');
        assert.equal(await page.evaluate(() => window.phoneFixture.snapshot.phoneDraft.text), '核对明天上午九点的日历安排');
        assert.equal(await input.inputValue(), '这份聊天草稿仍要保留');
        assert.equal(await page.evaluate(() => window.phoneFixture.requests.filter(request => request.action === 'phone.query').length), 1);
        record.checks.push('Unknown keeps draft, closes to a persistent original-request query, preserves chat text/attachment; same-tick query does not replay start.');
      } else if (mode === 'rejected') {
        await dialog.waitFor({ state: 'hidden' }); assert.equal(await input.inputValue(), '核对明天上午九点的日历安排');
        await page.getByRole('button', { name: '发送', exact: true }).click(); await dialog.waitFor();
        assert.equal(await page.evaluate(() => window.phoneFixture.requests.filter(request => request.action === 'phone.prepare').length), 2);
        assert.equal(await page.evaluate(() => window.phoneFixture.snapshot.state.tasks.length), 0);
        record.checks.push('An explicit pre-admission rejection keeps the draft and permits a fresh deliberate review, without claiming a created task.');
      } else {
        await dialog.waitFor({ state: 'hidden' }); assert.equal(await input.inputValue(), '');
        assert.equal(await page.evaluate(() => window.phoneFixture.snapshot.state.conversationId), 'original-conversation');
        await page.evaluate(() => window.phoneFixture.deliver());
        await page.getByText('手机任务结果：已核对日历，明天上午九点没有安排。', { exact: true }).waitFor();
        assert.equal(await page.locator('.pw-linked-task').count(), 1);
        const latest = page.getByRole('button', { name: '有新回复 · 回到最新', exact: true });
        if (await latest.count()) await latest.click();
        await page.waitForFunction(() => { const element = document.querySelector('.pw-conversation-scroll'); return element && element.scrollHeight - element.clientHeight - element.scrollTop < 2; });
        await page.screenshot({ path: path.join(output, 'phone-result-360.png') }); record.screenshots.push('phone-result-360.png');
        record.checks.push('Acknowledged native task consumes only its phone draft; original conversation, rendered result and existing task link remain in place.');
      }
      assert.equal(await page.evaluate(() => window.phoneFixture.requests.filter(request => request.action === 'phone.start').length), 1);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true);
    } finally { await context.close(); }
  }
  assert.deepEqual(record.errors, []); record.status = 'passed';
} catch (error) { record.status = 'failed'; record.failure = String(error.stack || error); throw error; }
finally {
  await fs.writeFile(path.join(output, 'results.json'), JSON.stringify(record, null, 2) + '\n');
  await browser?.close(); if (server) await new Promise(resolve => server.close(resolve));
  console.log(JSON.stringify(record));
}
