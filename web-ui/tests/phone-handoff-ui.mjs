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
const scenario = process.argv.includes('--scenario') ? process.argv[process.argv.indexOf('--scenario') + 1] : null;
const record = { scope: 'Actual production Web UI with synthetic native bridge; Android/native execution and persistence are not verified.', viewport: { width: 360, height: 800 }, checks: [], errors: [], screenshots: [] };

function fixture(mode) {
  const conversation = 'original-conversation', task = '11111111-1111-4111-8111-111111111111';
  const firstRun = '22222222-2222-4222-8222-222222222222', secondRun = '33333333-3333-4333-8333-333333333333';
  const goal = '核对明天上午九点的日历安排', createdAt = 1793764800000;
  const phoneTask = admitted => admitted
    ? { id: task, goal, executionMode: 'background', status: 'running',
        raw: { id: task, goal, createdAt, kind: 'phone', actorId: 'owner', spaceId: 'private', runId: firstRun, coordinatorEpoch: 1,
          conversationPhoneHandoff: { format: 'pocketworld.conversation-phone-handoff.v1', requestId: task, taskId: task,
            conversationId: conversation, goal, createdAt, source: { conversationId: conversation } } } }
    // Real awaiting_user/no-run projection: retain navigation without restoring
    // the goal/body hidden by the native owner-question source gate.
    : { id: task, goal: '原手机任务', executionMode: 'background', status: 'awaiting_user',
        summary: '原问题来源已撤回或尚不可核对。已保留原任务，请核对来源；你仍可停止原任务。',
        raw: { id: task, kind: 'phone', actorId: 'owner', spaceId: 'private', createdAt, coordinatorEpoch: 0,
          taskProjection: 'source_unavailable', ownerQuestionReadState: 'source_unavailable', sourceCurrentReadRequired: true,
          executionAuthorized: false, conversationPhoneLink: { taskId: task, conversationId: conversation } } };
  const snapshot = {
    state: { conversationId: conversation, conversations: [{ id: conversation, title: '我的日程安排' }], messages: [{ id: 'existing', role: 'assistant', content: '我们可以继续安排明天的日程。' }],
      modelName: '已选模型', modelProvider: 'openai_chat', modelRoute: 'api', chatBusy: false, chatSubmitting: false, phoneSubmitting: false, activeTaskId: null,
      tasks: [], projects: [], files: [], features: {}, ownerSnapshots: {}, ownerResponses: {}, ownerPending: [], ownerErrors: {}, moduleResults: {}, moduleBusy: [], moduleErrors: {}, taskActionErrors: {},
      selectedChatAttachments: [{ id: 'attachment', name: '保留的附件.txt', sha256: 'a'.repeat(64), mimeType: 'text/plain', size: 1 }], chatSendMode: 'queue', chatReceipts: {}, modelDrafts: {} },
    composer: { key: 'conversation:' + conversation, epoch: 1, revision: 1, conversationId: conversation, text: '这份聊天草稿仍要保留', mode: 'queue', pending: [], sourceRefs: [] },
    phoneDraft: { text: '', revision: 1, epoch: 1, phone: false, mode: 'background' }, view: { section: null },
  };
  // A reopened conversation receives the persisted task association, without a
  // synthetic user message or the previous controller's transient start receipt.
  if (mode === 'stored') {
    const stored = phoneTask(false);
    snapshot.state.tasks = [stored];
  }
  const commands = ['app.ready', 'app.refresh', 'composer.reading', 'composer.intent', 'composer.edit', 'phone.draft.edit', 'phone.prepare', 'phone.start', 'phone.query', 'tasks.open', 'tasks.close', 'conversation.drafts'];
  const requests = []; let revision = 0, review, original;
  const document = 'phone-handoff-fixture-document'; window.__pocketDocumentToken = document;
  const publish = () => window.PocketWeb.receive({ v: 1, kind: 'state', revision: ++revision, snapshot: structuredClone({ ...snapshot, capabilities: { commands } }) });
  const reply = (request, value) => window.PocketWeb.receive({ v: 1, kind: 'result', id: request.id, ok: true, value });
  const receipt = status => ({ ...original, status, ...(status === 'accepted' ? { taskId: task, admitted: mode === 'accepted',
    ...(mode === 'accepted' ? { runId: firstRun, epoch: 1, taskStatus: 'running' } : { taskStatus: 'awaiting_user' }) } : {}),
    message: status !== 'accepted' ? '启动结果待核对，请查询原请求。' : mode === 'accepted' ? '手机任务已获准执行，结果会回到原对话。' : '手机任务已保存到原对话；尚未确认获准执行，请查看原任务。' });
  const accept = () => {
    const value = receipt('accepted'); snapshot.phoneDraft.startReceipt = value;
    if (snapshot.phoneDraft.revision === original.draftRevision && snapshot.composer.epoch === original.composerEpoch) { snapshot.phoneDraft.text = ''; snapshot.phoneDraft.revision++; }
    snapshot.state.tasks = [phoneTask(mode === 'accepted')];
    if (mode === 'accepted') snapshot.state.activeTaskId = task;
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
        value = mode === 'accepted' || mode === 'saved' ? accept() : mode === 'rejected' ? { ...original, status: 'rejected', message: '原模型选择已变化。尚未创建任务，草稿仍保留，请重新审阅。' } : receipt('unknown'); snapshot.phoneDraft.startReceipt = value; break;
      case 'phone.query':
        if (args.requestId !== task || args.conversationId !== conversation) throw new Error('Query must retain original identity');
        value = accept(); break;
    }
    publish(); setTimeout(() => reply(request, value), 15);
  } };
  window.phoneFixture = { requests, snapshot, publish, resume() {
    snapshot.state.tasks[0].status = 'running'; snapshot.state.activeTaskId = task;
    Object.assign(snapshot.state.tasks[0].raw, { runId: secondRun, coordinatorEpoch: 2 }); publish();
  }, deliver(status = 'failed') {
    const epoch = status === 'failed' ? 1 : 2, runId = epoch === 1 ? firstRun : secondRun;
    snapshot.state.tasks[0].status = status; snapshot.state.activeTaskId = null;
    const content = epoch === 1 ? '手机任务结果：未能读取日历，请检查原任务。' : '手机任务结果：已核对日历，明天上午九点没有安排。';
    snapshot.state.messages.push({ id: 'phone-result-' + epoch, role: 'assistant', content, taskId: task,
      phoneResult: { taskId: task, runId, epoch, deliveryId: `phone_handoff:${task}:${runId}:${epoch}`, status,
        endedAt: createdAt + epoch * 60000, summaryTruncated: false, summaryChars: content.length } }); publish();
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
  const modes = ['unknown', 'accepted', 'rejected', 'saved', 'stored'];
  if (scenario && !modes.includes(scenario)) throw new Error('Unknown scenario');
  for (const mode of scenario ? [scenario] : modes) {
    const context = await browser.newContext({ viewport: record.viewport, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', serviceWorkers: 'block' });
    try {
      await context.route('**/*', route => new URL(route.request().url()).origin === origin ? route.continue() : route.abort());
      await context.addInitScript(fixture, mode); const page = await context.newPage(); page.setDefaultTimeout(8000);
      page.on('pageerror', error => record.errors.push(error.message)); await page.goto(origin, { waitUntil: 'networkidle' });
      const input = page.getByRole('textbox', { name: '工作输入', exact: true });
      await input.waitFor(); assert.equal(await input.inputValue(), '这份聊天草稿仍要保留');
      const assertSavedTask = async () => {
        const link = page.getByRole('button', { name: '查看手机任务：原手机任务', exact: true });
        await link.waitFor(); assert.match(await link.textContent(), /待你处理/);
        assert.equal(await page.evaluate(() => window.phoneFixture.snapshot.state.messages.length), 1);
        assert.equal(await page.locator('.pw-active-bar').count(), 0);
        await link.click(); await page.waitForFunction(() => window.phoneFixture.requests.some(request => request.action === 'tasks.open'));
        assert.equal(await page.evaluate(() => window.phoneFixture.requests.filter(request => request.action === 'tasks.open').at(-1).args.id), '11111111-1111-4111-8111-111111111111');
        await page.getByRole('button', { name: '返回原工作', exact: true }).click();
        await page.getByRole('dialog', { name: '原手机任务', exact: true }).waitFor({ state: 'hidden' });
      };
      if (mode === 'stored') {
        await assertSavedTask();
        assert.equal(await page.evaluate(() => window.phoneFixture.requests.filter(request => request.action === 'phone.start').length), 0);
        record.checks.push('Reopened original conversation shows the saved, unadmitted native task from its persisted association, with no receipt or fabricated message.');
        continue;
      }
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
        await assertSavedTask();
        record.checks.push('Unknown keeps draft, closes to a persistent original-request query, preserves chat text/attachment; same-tick query does not replay start.');
      } else if (mode === 'rejected') {
        await dialog.waitFor({ state: 'hidden' }); assert.equal(await input.inputValue(), '核对明天上午九点的日历安排');
        await page.getByRole('button', { name: '发送', exact: true }).click(); await dialog.waitFor();
        assert.equal(await page.evaluate(() => window.phoneFixture.requests.filter(request => request.action === 'phone.prepare').length), 2);
        assert.equal(await page.evaluate(() => window.phoneFixture.snapshot.state.tasks.length), 0);
        record.checks.push('An explicit pre-admission rejection keeps the draft and permits a fresh deliberate review, without claiming a created task.');
      } else if (mode === 'saved') {
        await dialog.waitFor({ state: 'hidden' }); assert.equal(await input.inputValue(), '');
        await assertSavedTask();
        await page.screenshot({ path: path.join(output, 'phone-saved-360.png') }); record.screenshots.push('phone-saved-360.png');
        record.checks.push('Saved but unadmitted start consumes only the acknowledged draft and retains a direct original-task entry without claiming execution.');
      } else {
        await dialog.waitFor({ state: 'hidden' }); assert.equal(await input.inputValue(), '');
        assert.equal(await page.evaluate(() => window.phoneFixture.snapshot.state.conversationId), 'original-conversation');
        await page.evaluate(() => window.phoneFixture.deliver());
        const firstResult = page.locator('.pw-message').filter({ hasText: '手机任务结果：未能读取日历，请检查原任务。' });
        await firstResult.locator('.pw-phone-result').waitFor();
        assert.equal(await firstResult.getByRole('button', { name: '查看这次回答的记忆与技能提案', exact: true }).count(), 0);
        const frozenMetadata = await firstResult.locator('.pw-phone-result').textContent();
        const endedLabel = await page.evaluate(() => new Date(window.phoneFixture.snapshot.state.messages.at(-1).phoneResult.endedAt).toLocaleString('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }));
        assert.match(frozenMetadata, /本次执行：未完成/); assert.ok(frozenMetadata.includes(endedLabel));
        await page.evaluate(() => window.phoneFixture.resume());
        assert.match(await page.locator('.pw-active-bar').textContent(), /执行中/);
        assert.equal(await firstResult.locator('.pw-phone-result').textContent(), frozenMetadata);
        assert.doesNotMatch(await firstResult.locator('.pw-phone-result').textContent(), /执行中/);
        await page.evaluate(() => window.phoneFixture.deliver('completed'));
        await page.getByText('手机任务结果：已核对日历，明天上午九点没有安排。', { exact: true }).waitFor();
        assert.equal(await page.locator('.pw-phone-result').count(), 2);
        assert.equal(await firstResult.locator('.pw-phone-result').textContent(), frozenMetadata);
        assert.match(await page.locator('.pw-phone-result').last().textContent(), /本次执行：已完成/);
        assert.equal(await page.getByRole('button', { name: '查看当前任务', exact: true }).count(), 2);
        assert.equal(await page.locator('.pw-linked-task').count(), 0);
        const latest = page.getByRole('button', { name: '有新回复 · 回到最新', exact: true });
        if (await latest.count()) await latest.click();
        await page.waitForFunction(() => { const element = document.querySelector('.pw-conversation-scroll'); return element && element.scrollHeight - element.clientHeight - element.scrollTop < 2; });
        await page.screenshot({ path: path.join(output, 'phone-result-360.png') }); record.screenshots.push('phone-result-360.png');
        record.checks.push('Each original run keeps its frozen status, end time and task entry after resume and a later result; current running state never relabels prior failed output.');
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
