import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { chromium } from '@playwright/test';

// Production React bundle + isolated native fixture. This tests UI journeys and
// command identities; Android admission, durable SQL and device actions need native tests.
const argument = flag => { const at = process.argv.indexOf(flag); if (at < 0 || !process.argv[at + 1]) throw new Error('Required --bundle and --output'); return path.resolve(process.argv[at + 1]); };
const bundle = argument('--bundle'), output = argument('--output');
const record = { scope: 'Production Web bundle with synthetic native receipts, not Android execution', checks: [], errors: [], screenshots: [] };
function fixture() {
  const conversation = 'original-conversation';
  const work = (id, goal) => ({ id, goal, status: 'running', summary: '', updatedAt: id === 'work-a' ? 2 : 1, executionMode: 'background', raw: {
    id, goal, kind: 'assistant', actorId: 'owner', spaceId: 'private', conversationId: conversation, executionRole: 'owner_work', runId: `${id}-run`, coordinatorEpoch: 1,
    controls: { canStop: true, canInput: true, canContinue: false }, ownerWork: { sourceTaskId: 'foreground', sourceRunId: 'foreground-run', sourceEpoch: 1, replyToMessageIds: ['request-a'], phase: 'running' },
  } });
  const tasks = [work('work-a', '整理明天的日程与出行安排'), work('work-b', '核对本周账单，找出需要确认的项目')];
  const message = { id: 'answer-a', role: 'assistant', taskId: 'foreground', content: '这两件事我已分别安排。你可以继续告诉我其他事。',
    replyContext: { taskId: 'foreground', runId: 'foreground-run', epoch: 1, replyId: 'saved-reply', replyToMessageIds: ['request-a', 'request-older'] } };
  const snapshot = { state: { conversationId: conversation, conversations: [{ id: conversation, title: '和管家的日常安排' }],
    messages: [{ id: 'request-a', role: 'user', content: '帮我整理明天的安排，再核对本周账单。' }, message], tasks, ownerWork: tasks,
    modelName: '已选模型', foregroundTurn: null, chatActiveTurn: null, chatBusy: false, chatSubmitting: false, activeTaskId: null, activePhoneTaskIds: [],
    projects: [], files: [], features: {}, ownerSnapshots: {}, ownerResponses: {}, ownerPending: [], ownerErrors: {}, moduleResults: {}, moduleBusy: [], moduleErrors: {}, taskActionErrors: {}, selectedChatAttachments: [], chatSendMode: 'queue', chatReceipts: {}, modelDrafts: {} },
    composer: { key: `conversation:${conversation}`, epoch: 1, revision: 1, conversationId: conversation, text: '还有一件事想和你聊', mode: 'queue', pending: [], sourceRefs: [], previewRefs: [] },
    phoneDraft: { text: '', revision: 1, epoch: 1, phone: false, mode: 'background' }, view: { section: null },
  };
  const commands = ['app.ready', 'app.refresh', 'composer.edit', 'composer.send', 'composer.reading', 'composer.mode', 'composer.intent', 'composer.query', 'tasks.open', 'tasks.close', 'ownerWork.input', 'ownerWork.control', 'message.origin', 'conversation.drafts', 'memoryDraft.open', 'memoryDraft.close', 'memoryDraft.source', 'memoryDraft.query', 'memoryDraft.confirm', 'memoryDraft.cancel'];
  const requests = []; let revision = 0, pending;
  const token = 'butler-fixture'; window.__pocketDocumentToken = token;
  const publish = () => window.PocketWeb.receive({ v: 1, kind: 'state', revision: ++revision, snapshot: structuredClone({ ...snapshot, capabilities: { commands } }) });
  const reply = (request, value) => window.PocketWeb.receive({ v: 1, kind: 'result', id: request.id, ok: true, value });
  const draft = { kind: 'memory', title: '记住你下午不喝咖啡', taskId: 'foreground', runId: 'foreground-run', epoch: 1, operationId: 'memory-op', proposalDigest: 'digest', apply: { status: 'pending_owner' } };
  const quote = '记住，我下午不喝咖啡。', sourceRef = { kind: 'message', sourceId: 'preference', revision: 1, ownerId: 'owner', spaceId: 'private' };
  const span = { sourceRef, offset: 0, chars: quote.length, sha256: 'a'.repeat(64) };
  window.PocketNative = { postMessage(raw) {
    const request = JSON.parse(raw); requests.push(request); const args = request.args; let value = {};
    if (request.hostDocumentToken !== token) throw new Error('Wrong fixture document');
    switch (request.action) {
      case 'app.ready': value = { ready: true }; break;
      case 'composer.edit': snapshot.composer.text = args.text; snapshot.composer.revision++; break;
      case 'composer.intent': snapshot.phoneDraft.phone = args.phone; if (args.mode) snapshot.phoneDraft.mode = args.mode; break;
      case 'composer.mode': snapshot.composer.mode = args.mode; snapshot.state.chatSendMode = args.mode; break;
      case 'composer.send': snapshot.composer.text = ''; snapshot.composer.revision++; value = { status: 'accepted' }; break;
      case 'ownerWork.input':
        pending = args; snapshot.composer.pending = [{ clientMessageId: args.clientMessageId, draftKey: snapshot.composer.key, status: 'unconfirmed', receipt: { status: 'unconfirmed' }, originalTarget: { taskId: args.taskId, runId: args.runId, epoch: args.epoch } }];
        value = { status: 'unconfirmed', clientMessageId: args.clientMessageId, message: '正在核对原补充回执。' }; break;
      case 'composer.query':
        if (args.clientMessageId !== pending.clientMessageId) throw new Error('Query changed original client');
        snapshot.composer.text = ''; snapshot.composer.revision++; snapshot.composer.pending = [];
        value = { ...pending, conversationId: conversation, status: 'queued' }; break;
      case 'ownerWork.control': {
        const task = tasks.find(row => row.id === args.taskId);
        if (!task || task.raw.runId !== args.runId || task.raw.coordinatorEpoch !== args.epoch) throw new Error('Control changed original work tuple');
        task.status = args.action === 'continue' ? 'running' : 'cancelled';
        if (args.action === 'continue') { task.raw.runId = 'work-b-resumed-run'; task.raw.coordinatorEpoch = 2; }
        task.raw.controls = { canContinue: false, canInput: args.action === 'continue', canStop: args.action === 'continue' };
        value = { ...args, status: task.status, message: args.action === 'continue' ? '已继续原任务。' : '已请求停止这件事。' }; break;
      }
      case 'tasks.open': snapshot.view.taskId = args.id; value = { taskId: args.id }; break;
      case 'tasks.close': snapshot.view.taskId = null; break;
      case 'message.origin': value = { conversationId: conversation, replyMessageId: args.messageId, sourceMessageId: args.sourceMessageId, available: true, text: '更早的原请求：账单只做核对，支出由我确认。', offset: 0, nextOffset: 25, totalChars: 25, hasMore: false }; break;
      case 'conversation.drafts': value = { taskId: args.taskId, bodyIncluded: false, drafts: args.taskId === 'foreground' ? [draft] : [] }; break;
      case 'memoryDraft.open': value = { handle: 'memory-native-review', proposal: { action: 'create' }, apply: { status: 'pending_owner' }, review: { action: 'create', sourceRefs: [sourceRef], after: { text: '主人下午不喝咖啡', assertion: { kind: 'user_said', subjectId: 'owner', subjectType: 'owner', subjectLabel: '主人', predicate: '饮品偏好', object: '不喝咖啡', condition: '下午', eventTime: null, validFrom: null, validTo: null, reviewAfter: null, evidenceSpans: [span] }, assertionEvidence: [{ ...span, text: quote, verifiedOriginalSpan: true, assertionTruthVerified: false }] } } }; break;
      case 'memoryDraft.confirm': if (args.handle !== 'memory-native-review' || Object.keys(args).length !== 1) throw new Error('Changed memory review'); draft.apply.status = 'saved'; snapshot.state.memoryRefreshRevision = (snapshot.state.memoryRefreshRevision || 0) + 1; value = { status: 'saved' }; break;
    }
    publish(); setTimeout(() => reply(request, value), 10);
  } };
  window.butlerFixture = { requests, snapshot, publish, interruptSecond() { tasks[1].status = 'interrupted'; tasks[1].raw.controls = { canInput: false, canStop: true, canContinue: true }; publish(); }, advance() { snapshot.state.tasks[0].raw.runId = 'work-a-later-run'; snapshot.state.tasks[0].raw.coordinatorEpoch = 2; publish(); }, deliver() {
    snapshot.state.messages.push({ id: 'old-outcome', role: 'assistant', taskId: 'work-a', content: '第一次核对没有完成，原始记录保留。',
      replyContext: { taskId: 'work-a', runId: 'work-a-run', epoch: 1, deliveryId: '00000000-0000-4000-8000-000000000001', replyToMessageIds: ['request-a'] },
      taskResult: { kind: 'owner_work', taskId: 'work-a', runId: 'work-a-run', epoch: 1, deliveryId: '00000000-0000-4000-8000-000000000001', status: 'failed', endedAt: 1793764860000, summaryChars: 20, summaryTruncated: false } }); publish();
  } };
}
let browser, server, page;
try {
  await fs.mkdir(output, { recursive: true });
  const index = await fs.readFile(path.join(bundle, 'index.html'), 'utf8'), asset = index.match(/assets\/(index-[^" ]+\.js)/)?.[1];
  if (!asset) throw new Error('Production entry asset is missing');
  record.bundle = asset; record.bundleSha256 = createHash('sha256').update(await fs.readFile(path.join(bundle, 'assets', asset))).digest('hex');
  server = http.createServer(async (request, response) => {
    try { const name = new URL(request.url, 'http://fixture').pathname, file = path.resolve(bundle, '.' + (name === '/' ? '/index.html' : name));
      if (!file.startsWith(bundle + '/')) throw new Error('Invalid asset');
      const content = await fs.readFile(file); response.writeHead(200, { 'content-type': ({ '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.ttf': 'font/ttf', '.woff2': 'font/woff2', '.svg': 'image/svg+xml' })[path.extname(file)] || 'application/octet-stream' }); response.end(content);
    } catch { response.writeHead(404); response.end(); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); const origin = `http://127.0.0.1:${server.address().port}`;
  browser = await chromium.launch({ headless: true, ...(process.env.UIUX_CHROMIUM ? { executablePath: process.env.UIUX_CHROMIUM } : {}), args: ['--disable-background-networking'] });
  const context = await browser.newContext({ viewport: { width: 360, height: 800 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', serviceWorkers: 'block' });
  await context.route('**/*', route => new URL(route.request().url()).origin === origin ? route.continue() : route.abort());
  await context.addInitScript(fixture); page = await context.newPage(); page.setDefaultTimeout(10000);
  page.on('pageerror', error => record.errors.push(error.message));
  await page.goto(origin, { waitUntil: 'networkidle' }); const input = page.getByRole('textbox', { name: '工作输入', exact: true }); await input.waitFor();
  assert.equal(await page.locator('.pw-work-row').count(), 2); assert.equal(await page.locator('.pw-current-thinking').count(), 0);
  assert.equal(await page.getByRole('button', { name: '停止回复', exact: true }).count(), 0);
  await page.getByRole('button', { name: '原请求 1', exact: true }).click(); assert.equal(await input.inputValue(), '还有一件事想和你聊');
  assert.equal(await page.evaluate(() => document.activeElement?.getAttribute('data-message-id')), 'request-a');
  await page.getByRole('button', { name: '原请求 2', exact: true }).click();
  await page.getByRole('dialog', { name: '这次答复的原请求', exact: true }).waitFor();
  await page.getByText('更早的原请求：账单只做核对，支出由我确认。', { exact: true }).waitFor();
  await page.getByRole('button', { name: '返回原答复', exact: true }).click(); assert.equal(await input.inputValue(), '还有一件事想和你聊');
  await page.screenshot({ path: path.join(output, 'butler-work-360.png'), animations: 'disabled' }); record.screenshots.push('butler-work-360.png');
  await page.getByRole('button', { name: '发送', exact: true }).click(); await page.waitForFunction(() => window.butlerFixture.requests.some(request => request.action === 'composer.send'));
  const independent = await page.evaluate(() => window.butlerFixture.requests.find(request => request.action === 'composer.send'));
  assert.deepEqual(Object.keys(independent.args).sort(), ['epoch', 'key', 'text']);
  record.checks.push('Two background works leave the main conversation available; independent input carries no background tuple. Original request navigation preserves draft and conversation.');
  const openWork = () => page.getByRole('button', { name: '查看工作：整理明天的日程与出行安排', exact: true }).click();
  await openWork(); await page.getByRole('button', { name: '补充这个任务', exact: true }).click(); await page.locator('.pw-owner-work-target').waitFor();
  await input.fill('这件事先核对上午的安排。'); await page.getByRole('button', { name: '发送', exact: true }).click();
  await page.getByRole('button', { name: '查询原回执', exact: true }).waitFor(); assert.equal(await input.inputValue(), '这件事先核对上午的安排。');
  const targeted = await page.evaluate(() => window.butlerFixture.requests.find(request => request.action === 'ownerWork.input').args);
  assert.equal(targeted.taskId, 'work-a'); assert.equal(targeted.runId, 'work-a-run'); assert.equal(targeted.epoch, 1); assert.equal(targeted.mode, 'queue');
  await page.getByRole('button', { name: '查询原回执', exact: true }).click(); await page.waitForFunction(() => window.butlerFixture.snapshot.composer.pending.length === 0); await page.waitForFunction(field => field.value === '', await input.elementHandle()); assert.equal(await input.inputValue(), '');
  assert.equal(await page.evaluate(() => window.butlerFixture.requests.filter(request => request.action === 'ownerWork.input').length), 1);
  record.checks.push('Explicit work input uses its displayed task/run/epoch; uncertain receipt keeps text and queries the same client without resending.');
  await page.evaluate(() => window.butlerFixture.advance()); await input.fill('不要误发给新的执行轮次。');
  assert.equal(await page.getByRole('button', { name: '发送', exact: true }).isDisabled(), true);
  await page.getByRole('button', { name: '回到管家对话', exact: true }).click();
  await page.evaluate(() => window.butlerFixture.deliver()); const outcome = page.locator('[data-message-id="old-outcome"]'); await outcome.locator('.pw-task-result').waitFor();
  assert.match(await outcome.locator('.pw-task-result').textContent(), /未完成/); assert.doesNotMatch(await outcome.locator('.pw-task-result').textContent(), /执行中/);
  await openWork(); await page.getByRole('button', { name: '停止这件事', exact: true }).click();
  await page.waitForFunction(() => window.butlerFixture.requests.some(request => request.action === 'ownerWork.control'));
  const control = await page.evaluate(() => window.butlerFixture.requests.find(request => request.action === 'ownerWork.control').args);
  assert.deepEqual(control, { taskId: 'work-a', runId: 'work-a-later-run', epoch: 2, action: 'cancel' });
  await page.getByRole('button', { name: '返回原工作', exact: true }).click();
  record.checks.push('A selected old target cannot silently follow a newer run. Frozen result stays failed; an explicit current control targets only the displayed new tuple.');
  await page.evaluate(() => window.butlerFixture.interruptSecond());
  await page.getByRole('button', { name: '查看工作：核对本周账单，找出需要确认的项目', exact: true }).click();
  await page.getByRole('button', { name: '继续原任务', exact: true }).click();
  await page.waitForFunction(() => window.butlerFixture.requests.some(request => request.action === 'ownerWork.control' && request.args.action === 'continue'));
  const continued = await page.evaluate(() => window.butlerFixture.requests.find(request => request.action === 'ownerWork.control' && request.args.action === 'continue').args);
  assert.deepEqual(continued, { taskId: 'work-b', runId: 'work-b-run', epoch: 1, action: 'continue' });
  await page.getByRole('button', { name: '返回原工作', exact: true }).click(); assert.equal(await input.inputValue(), '不要误发给新的执行轮次。');
  record.checks.push('An interrupted work remains reachable when native continuation is available; explicit continue uses its original tuple and preserves the independent conversation draft.');
  await page.locator('[data-message-id="answer-a"]').getByRole('button', { name: '消息操作与来源', exact: true }).click();
  await page.getByRole('button', { name: /记忆与技能提案/ }).click();
  await page.getByRole('button', { name: /记住你下午不喝咖啡/ }).click(); const memory = page.getByRole('dialog', { name: '保存这条记忆', exact: true }); await memory.waitFor();
  await memory.getByRole('button', { name: /^主体与依据/ }).click();
  await memory.getByRole('button', { name: /^适用条件与时间/ }).click();
  await memory.getByText('记住，我下午不喝咖啡。', { exact: true }).waitFor(); assert.equal(await memory.locator('.pw-memory-assertion .pw-facts').getByText('下午', { exact: true }).count(), 1);
  assert.equal(await memory.locator('.pw-memory-assertion .pw-facts').getByText('你', { exact: true }).count(), 1);
  assert.equal(await memory.getByText('你的原话', { exact: true }).count(), 1);
  assert.equal(await page.evaluate(() => window.butlerFixture.requests.some(request => request.action.startsWith('knowledge.assertion.'))), false);
  await memory.getByText('你的原话', { exact: true }).scrollIntoViewIfNeeded();
  await page.screenshot({ path: path.join(output, 'butler-memory-360.png'), animations: 'disabled' }); record.screenshots.push('butler-memory-360.png');
  await memory.getByText('我已核对正文、来源与以上影响', { exact: true }).click(); assert.equal(await memory.getByRole('checkbox').isChecked(), true);
  await memory.getByRole('button', { name: '确认保存', exact: true }).click(); await memory.getByText('原记忆已保存', { exact: true }).waitFor();
  const memoryConfirmations = await page.evaluate(() => window.butlerFixture.requests.filter(request => request.action === 'memoryDraft.confirm'));
  assert.equal(memoryConfirmations.length, 1); assert.deepEqual(memoryConfirmations[0].args, { handle: 'memory-native-review' });
  await memory.getByRole('button', { name: '返回原回答', exact: true }).click();
  await page.locator('[data-message-id="answer-a"]').getByText('已处理', { exact: true }).waitFor();
  record.checks.push('Memory draft reuses assertion display with subject, condition and exact quote; confirmation sends only its original native review handle.');
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  await page.setViewportSize({ width: 1280, height: 900 }); await page.screenshot({ path: path.join(output, 'butler-work-1280.png'), animations: 'disabled' }); record.screenshots.push('butler-work-1280.png');
  assert.deepEqual(record.errors, []); record.status = 'passed'; await context.close();
} catch (error) { record.status = 'failed'; record.failure = String(error.stack || error); if (page && !page.isClosed()) await page.screenshot({ path: path.join(output, 'failure.png'), animations: 'disabled' }).catch(() => {}); throw error; }
finally { await fs.writeFile(path.join(output, 'results.json'), JSON.stringify(record, null, 2) + '\n'); await browser?.close(); if (server) await new Promise(resolve => server.close(resolve)); console.log(JSON.stringify(record)); }
