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

function fixture({ mode, reviewModelName }) {
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
    phoneDraft: { text: '', revision: 1, epoch: 1, phone: false, mode: 'background' }, view: { section: null, taskId: null },
  };
  // A reopened conversation receives the persisted task association, without a
  // synthetic user message or the previous controller's transient start receipt.
  if (mode === 'stored') {
    const stored = phoneTask(false);
    snapshot.state.tasks = [stored];
  }
  const commands = ['app.ready', 'app.refresh', 'composer.reading', 'composer.intent', 'composer.edit', 'phone.draft.edit', 'phone.prepare', 'phone.start', 'phone.query', 'phone.resume', 'tasks.resume', 'tasks.open', 'tasks.close', 'conversation.drafts'];
  const requests = [], taskReads = []; let revision = 0, review, original;
  // Keep the synthetic owner projection separate from the bounded recent-task
  // cache. This models a fresh read, not a database or persistence implementation.
  let originalTask = snapshot.state.tasks[0] ? structuredClone(snapshot.state.tasks[0]) : null;
  const document = 'phone-handoff-fixture-document'; window.__pocketDocumentToken = document;
  const publish = () => window.PocketWeb.receive({ v: 1, kind: 'state', revision: ++revision, snapshot: structuredClone({ ...snapshot, capabilities: { commands } }) });
  const reply = (request, value) => window.PocketWeb.receive({ v: 1, kind: 'result', id: request.id, ok: true, value });
  const receipt = status => ({ ...original, status, ...(status === 'accepted' ? { taskId: task, admitted: mode === 'accepted',
    ...(mode === 'accepted' ? { runId: firstRun, epoch: 1, taskStatus: 'running' } : { taskStatus: 'awaiting_user' }) } : {}),
    message: status !== 'accepted' ? '启动结果待核对，请查询原请求。' : mode === 'accepted' ? '手机任务已获准执行，结果会回到原对话。' : '手机任务已保存到原对话；尚未确认获准执行，请查看原任务。' });
  const accept = () => {
    const value = receipt('accepted'); snapshot.phoneDraft.startReceipt = value;
    if (snapshot.phoneDraft.revision === original.draftRevision && snapshot.composer.epoch === original.composerEpoch) { snapshot.phoneDraft.text = ''; snapshot.phoneDraft.revision++; }
    snapshot.state.tasks = [phoneTask(mode === 'accepted')]; originalTask = structuredClone(snapshot.state.tasks[0]);
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
          modelName: reviewModelName, provider: 'openai_chat', toolLimit: 40, modelCallLimit: 64, scopeMinutes: mode === 'rejected' ? 0 : 30,
          screenDisclosure: '执行时，屏幕与可见文字会发送到本次固定的模型。', message: '在后台独立屏处理这个目标；连接不可用时等待。' };
        value = review; break;
      case 'phone.start':
        if (args.reviewHandle !== review.reviewHandle || Object.keys(args).length !== 1) throw new Error('Start must only use the reviewed native handle');
        original = { ...review, draftRevision: snapshot.phoneDraft.revision, composerEpoch: snapshot.composer.epoch };
        value = mode === 'accepted' || mode === 'saved' ? accept() : mode === 'rejected' ? { ...original, status: 'rejected', message: '原模型选择已变化。尚未创建任务，草稿仍保留，请重新审阅。' } : receipt('unknown'); snapshot.phoneDraft.startReceipt = value; break;
      case 'phone.query':
        if (args.requestId !== task || args.conversationId !== conversation) throw new Error('Query must retain original identity');
        value = accept(); break;
      case 'tasks.open': {
        const cached = snapshot.state.tasks.some(row => row.id === args.id);
        const visibleResult = snapshot.view.section === null && snapshot.view.taskId === null && snapshot.state.conversationId === conversation
          && snapshot.state.messages.some(message => message.phoneResult?.taskId === args.id);
        if (!originalTask || args.id !== originalTask.id || (!cached && !visibleResult)) throw new Error('Open must read the original visible task');
        if (args.requestNavigation !== false || Object.keys(args).length !== 2) throw new Error('An internal result entry must not request external navigation');
        // PocketWebBridge.openOriginalTask first publishes view.taskId; the
        // controller's readTaskForOpen/mergeTask then updates state.tasks. The
        // completion receipt carries only read identity and no task payload.
        snapshot.view.taskId = args.id; publish();
        setTimeout(() => {
          snapshot.state.tasks = [...snapshot.state.tasks.filter(row => row.id !== args.id), structuredClone(originalTask)];
          delete snapshot.state.taskActionErrors[args.id];
          const result = { taskId: args.id, readOnly: true, executionAuthorized: false };
          taskReads.push({ taskId: args.id, cacheWasCold: !cached, result });
          reply(request, result); publish();
        }, 15);
        return;
      }
      case 'tasks.close': snapshot.view.taskId = null; break;
      case 'phone.resume':
      case 'tasks.resume': throw new Error('Reading an original result must not resume execution');
    }
    publish(); setTimeout(() => reply(request, value), 15);
  } };
  window.phoneFixture = { requests, taskReads, snapshot, publish, evictTaskCache() {
    if (snapshot.view.taskId !== null) throw new Error('Close the detail window before evicting the recent-task cache');
    snapshot.state.tasks = []; publish();
  }, resume() {
    snapshot.state.tasks[0].status = 'running'; snapshot.state.activeTaskId = task;
    Object.assign(snapshot.state.tasks[0].raw, { runId: secondRun, coordinatorEpoch: 2 });
    originalTask = structuredClone(snapshot.state.tasks[0]); publish();
  }, deliver(status = 'failed') {
    const epoch = status === 'failed' ? 1 : 2, runId = epoch === 1 ? firstRun : secondRun;
    snapshot.state.tasks[0].status = status; snapshot.state.activeTaskId = null;
    const content = epoch === 1 ? '手机任务结果：未能读取日历，请检查原任务。' : '手机任务结果：已核对日历，明天上午九点没有安排。';
    snapshot.state.messages.push({ id: 'phone-result-' + epoch, role: 'assistant', content, taskId: task,
      phoneResult: { taskId: task, runId, epoch, deliveryId: `phone_handoff:${task}:${runId}:${epoch}`, status,
        endedAt: createdAt + epoch * 60000, summaryTruncated: false, summaryChars: content.length } });
    originalTask = structuredClone(snapshot.state.tasks[0]); publish();
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
    const reviewModelName = mode === 'accepted' ? '本次固定模型 · LongContextReasoningVisionPreview-2026-11-WithExtendedContext' : '本次固定模型';
    const context = await browser.newContext({ viewport: record.viewport, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', serviceWorkers: 'block' });
    try {
      await context.route('**/*', route => new URL(route.request().url()).origin === origin ? route.continue() : route.abort());
      await context.addInitScript(fixture, { mode, reviewModelName }); const page = await context.newPage(); page.setDefaultTimeout(8000);
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
      for (const text of ['我的日程安排', reviewModelName, '40', '64', mode === 'rejected' ? '不设截止时间' : '30 分钟']) assert.equal(await dialog.getByText(text, { exact: true }).count(), 1);
      assert.match(await dialog.textContent(), /屏幕与可见文字会发送/);
      assert.equal(await page.evaluate(() => window.phoneFixture.requests.filter(request => request.action === 'phone.prepare').length), 1);
      if (mode === 'unknown') { await page.screenshot({ path: path.join(output, 'phone-review-360.png') }); record.screenshots.push('phone-review-360.png'); }
      if (mode === 'accepted') { await page.screenshot({ path: path.join(output, 'phone-review-long-model-360.png') }); record.screenshots.push('phone-review-long-model-360.png'); }
      const reviewLayout = await dialog.evaluate(element => {
        const measure = node => {
          const rect = node.getBoundingClientRect(), style = getComputedStyle(node);
          return { text: node.textContent, rect: { top: rect.top, right: rect.right, bottom: rect.bottom, left: rect.left, width: rect.width, height: rect.height },
            style: { display: style.display, gridTemplateColumns: style.gridTemplateColumns, flexDirection: style.flexDirection,
              alignItems: style.alignItems, overflowWrap: style.overflowWrap, whiteSpace: style.whiteSpace, lineHeight: style.lineHeight },
            scrollWidth: node.scrollWidth, clientWidth: node.clientWidth };
        };
        const fields = element.querySelector('.pw-scope-fields'), heading = element.querySelector('.cb-permission2-heading .cb-grow');
        return { fields: measure(fields), rows: [...fields.children].map(row => ({ row: measure(row), term: measure(row.querySelector('dt')), definition: measure(row.querySelector('dd')) })),
          heading: { container: measure(heading), title: measure(heading.querySelector('strong')), context: measure(heading.querySelector('small')) } };
      });
      (record.reviewLayouts ||= {})[mode] = reviewLayout;
      for (const [index, field] of reviewLayout.rows.entries()) {
        const label = `${mode}: ${field.term.text}`;
        assert.ok(Math.abs(field.term.rect.top - field.definition.rect.top) <= 2, `${label}: label and value must share the same row`);
        assert.ok(field.definition.rect.left >= field.term.rect.right - 1, `${label}: label and value must not overlap horizontally`);
        assert.ok(field.definition.rect.right <= reviewLayout.fields.rect.right + 1 && field.definition.scrollWidth <= field.definition.clientWidth + 1,
          `${label}: value must wrap within the field width`);
        if (index > 0) {
          const previous = reviewLayout.rows[index - 1];
          assert.ok(field.row.rect.top >= Math.max(previous.row.rect.bottom, previous.term.rect.bottom, previous.definition.rect.bottom) - 1,
            `${label}: field must start below the complete preceding label and value`);
        }
      }
      assert.ok(reviewLayout.heading.context.rect.top >= reviewLayout.heading.title.rect.bottom - 1,
        `${mode}: execution location must appear below the reviewed goal`);
      if (mode === 'accepted') {
        const model = reviewLayout.rows.find(field => field.term.text === '本次模型');
        assert.ok(model.definition.rect.height > model.term.rect.height, 'The long model name must wrap without occupying the next field');
      }
      record.checks.push(`${mode}: review labels and values share rows; consecutive fields do not overlap; execution location is below the goal${mode === 'accepted' ? '; long model name wraps within its field' : ''}.`);
      await dialog.getByRole('button', { name: '开始这次任务', exact: true }).evaluate(button => { button.click(); button.click(); });
      await page.waitForFunction(() => window.phoneFixture.requests.some(request => request.action === 'phone.start'));
      if (mode === 'unknown') {
        await dialog.getByRole('button', { name: '查询原手机请求', exact: true }).waitFor();
        await dialog.getByRole('button', { name: '关闭手机任务核对', exact: true }).click();
        assert.equal(await input.inputValue(), '核对明天上午九点的日历安排');
        await page.getByRole('button', { name: '输入方式与选项', exact: true }).click(); await page.getByText('与主 Agent 对话', { exact: true }).click();
        assert.equal(await input.inputValue(), '这份聊天草稿仍要保留'); assert.equal(await page.getByText('保留的附件.txt', { exact: true }).count(), 1);
        const originalReceipt = await page.evaluate(() => window.phoneFixture.snapshot.phoneDraft.startReceipt);
        await page.getByRole('button', { name: '打开导航', exact: true }).click();
        const navigation = page.getByRole('dialog', { name: 'PocketWorld', exact: true }); await navigation.waitFor();
        await navigation.getByRole('button', { name: '关闭导航', exact: true }).click(); await navigation.waitFor({ state: 'hidden' });
        assert.deepEqual(await page.evaluate(() => window.phoneFixture.snapshot.phoneDraft.startReceipt), originalReceipt);
        assert.equal(await page.evaluate(() => window.phoneFixture.snapshot.state.conversationId), 'original-conversation');
        assert.equal(await page.evaluate(() => window.phoneFixture.requests.filter(request => request.action === 'phone.query').length), 0);
        assert.equal(await input.inputValue(), '这份聊天草稿仍要保留');
        assert.equal(await page.getByText('保留的附件.txt', { exact: true }).count(), 1);
        await page.getByRole('button', { name: '查询原手机请求', exact: true }).evaluate(button => { button.click(); button.click(); });
        await page.waitForFunction(() => window.phoneFixture.snapshot.phoneDraft.startReceipt.status === 'accepted');
        assert.equal(await page.evaluate(() => window.phoneFixture.snapshot.phoneDraft.text), '核对明天上午九点的日历安排');
        assert.equal(await input.inputValue(), '这份聊天草稿仍要保留');
        assert.equal(await page.evaluate(() => window.phoneFixture.requests.filter(request => request.action === 'phone.query').length), 1);
        assert.deepEqual(await page.evaluate(() => window.phoneFixture.requests.find(request => request.action === 'phone.query').args),
          { requestId: originalReceipt.requestId, conversationId: originalReceipt.conversationId });
        await assertSavedTask();
        record.checks.push('Unknown keeps its original-request query through closing review and opening/closing the main sidebar, preserves chat text/attachment, and queries the same request/conversation once; same-tick query does not replay start.');
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
        const frozenResults = await page.locator('.pw-phone-result').evaluateAll(nodes => nodes.map(node => ({ deliveryId: node.dataset.deliveryId, text: node.textContent })));
        const executionRequests = await page.evaluate(() => window.phoneFixture.requests.filter(request => ['phone.start', 'phone.resume', 'tasks.resume'].includes(request.action)));
        for (const [index, result] of frozenResults.entries()) {
          await page.evaluate(() => window.phoneFixture.evictTaskCache());
          await page.waitForFunction(() => window.phoneFixture.snapshot.state.tasks.length === 0 && window.phoneFixture.snapshot.view.taskId === null);
          await page.locator(`.pw-phone-result[data-delivery-id="${result.deliveryId}"]`).getByRole('button', { name: '查看当前任务', exact: true }).click();
          const detail = page.getByRole('dialog', { name: '核对明天上午九点的日历安排', exact: true }); await detail.waitFor();
          await detail.locator('[data-task-status="completed"]').waitFor();
          assert.equal(await page.evaluate(() => window.phoneFixture.snapshot.view.taskId), '11111111-1111-4111-8111-111111111111');
          assert.equal(await page.evaluate(() => window.phoneFixture.taskReads.length), index + 1);
          assert.deepEqual(await page.evaluate(() => window.phoneFixture.requests.filter(request => request.action === 'tasks.open').at(-1).args),
            { id: '11111111-1111-4111-8111-111111111111', requestNavigation: false });
          await detail.getByRole('button', { name: '返回原工作', exact: true }).click(); await detail.waitFor({ state: 'hidden' });
          await page.waitForFunction(() => window.phoneFixture.snapshot.view.taskId === null);
          assert.equal(await page.evaluate(() => window.phoneFixture.snapshot.state.conversationId), 'original-conversation');
          assert.deepEqual(await page.locator('.pw-phone-result').evaluateAll(nodes => nodes.map(node => ({ deliveryId: node.dataset.deliveryId, text: node.textContent }))), frozenResults);
          assert.deepEqual(await page.evaluate(() => window.phoneFixture.requests.filter(request => ['phone.start', 'phone.resume', 'tasks.resume'].includes(request.action))), executionRequests);
        }
        record.coldResultReads = await page.evaluate(() => window.phoneFixture.taskReads);
        assert.deepEqual(record.coldResultReads, frozenResults.map(() => ({ taskId: '11111111-1111-4111-8111-111111111111', cacheWasCold: true,
          result: { taskId: '11111111-1111-4111-8111-111111111111', readOnly: true, executionAuthorized: false } })));
        assert.equal(await page.evaluate(() => window.phoneFixture.requests.filter(request => request.action === 'tasks.open').length), 2);
        assert.equal(await page.evaluate(() => window.phoneFixture.requests.filter(request => request.action === 'tasks.close').length), 2);
        record.checks.push('With the recent-task cache emptied separately for each of two frozen results, both buttons read the same original task through tasks.open, show its current completed detail, and return without changing either result or issuing any additional start/resume. Synthetic read receipts are readOnly and executionAuthorized=false.');
        const latest = page.getByRole('button', { name: '有新回复 · 回到最新', exact: true });
        if (await latest.count()) await latest.click();
        await page.waitForFunction(() => { const element = document.querySelector('.pw-conversation-scroll'); return element && element.scrollHeight - element.clientHeight - element.scrollTop < 2; });
        await page.screenshot({ path: path.join(output, 'phone-result-360.png') }); record.screenshots.push('phone-result-360.png');
        record.checks.push('Each original run keeps its frozen status, end time and task entry after resume and a later result; current running state never relabels prior failed output.');
      }
      assert.equal(await page.evaluate(() => window.phoneFixture.requests.filter(request => request.action === 'phone.start').length), 1);
      assert.equal(await page.evaluate(() => window.phoneFixture.requests.filter(request => ['phone.resume', 'tasks.resume'].includes(request.action)).length), 0);
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
