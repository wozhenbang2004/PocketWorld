import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { chromium } from '@playwright/test';

// Production React bundle, synthetic phone frames and native receipts only.
// This does not prove Android capture, device input, or a physical device's frame rate.
const argument = (flag, fallback) => { const index = process.argv.indexOf(flag); return path.resolve(index < 0 ? fallback : process.argv[index + 1]); };
const bundle = argument('--bundle', 'app/src/main/assets/pocket-ui');
const output = argument('--output', 'app/build/reports/phone-viewer-web');
const record = { scope: 'Production UI with isolated synthetic native host and clearly labelled generated test frames; no device acceptance.', checks: [], errors: [], screenshots: [] };

function fixture() {
  const conversation = 'phone-viewer-conversation';
  const makeTask = (id, title) => ({ id, goal: title, executionMode: 'background', status: 'running', updatedAt: 1,
    raw: { id, kind: 'phone', actorId: 'owner', spaceId: 'private', executionMode: 'background', runId: `run-${id}`, coordinatorEpoch: 1,
      conversationPhoneLink: { taskId: id, conversationId: conversation } } });
  const snapshot = {
    state: { conversationId: conversation, conversations: [{ id: conversation, title: '安排周末的行程' }],
      messages: [{ id: 'request', role: 'user', content: '帮我核对周六的行程，再查一下明天的天气。' }, { id: 'reply', role: 'assistant', content: '两部独立屏正在处理。你可以在这里查看各自的进展。' }],
      tasks: [makeTask('calendar', '核对周六行程与提醒'), makeTask('weather', '查看明天的天气和出行建议')], activeTaskId: 'calendar',
      modelName: '已选模型', modelProvider: 'openai_chat', modelRoute: 'api', chatBusy: false, chatSubmitting: false, phoneSubmitting: false,
      projects: [], files: [], features: {}, ownerSnapshots: {}, ownerResponses: {}, ownerPending: [], ownerErrors: {}, moduleResults: {}, moduleBusy: [], moduleErrors: {}, taskActionErrors: {}, selectedChatAttachments: [], chatSendMode: 'queue', chatReceipts: {}, modelDrafts: {} },
    composer: { key: `conversation:${conversation}`, epoch: 1, revision: 1, conversationId: conversation, text: '等会儿再查一下路线', mode: 'queue', pending: [], sourceRefs: [] },
    phoneDraft: { text: '', revision: 1, epoch: 1, phone: false, mode: 'background' }, view: { section: null, resumeRevision: 0 },
  };
  const commands = ['app.ready', 'app.refresh', 'composer.reading', 'composer.edit', 'tasks.open', 'tasks.close', 'phone.viewer.open', 'phone.viewer.frame', 'phone.viewer.close', 'phone.pause', 'phone.resume'];
  const documentToken = 'phone-viewer-test-document'; window.__pocketDocumentToken = documentToken;
  const requests = [], frameReplies = [], sessions = new Map(), pendingByViewer = new Map(), generations = new Map();
  let serial = 0, revision = 0, ready = false, hidden = false, fail = false, substitute = false, held = null, holdNext = false;
  let firstFrameStarting = true, startupReply = null;
  let maxPending = 0;
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => hidden ? 'hidden' : 'visible' });
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });
  const publish = () => window.PocketWeb.receive({ v: 1, kind: 'state', revision: ++revision, snapshot: structuredClone({ ...snapshot, capabilities: { commands } }) });
  const reply = (request, value, error) => {
    if (request.action === 'phone.viewer.frame') frameReplies.push({ viewerId: request.args.viewerId, afterFrameGeneration: request.args.afterFrameGeneration, state: error?.code || value.state });
    window.PocketWeb.receive({ v: 1, kind: 'result', id: request.id, ok: !error, ...(error ? { error } : { value }) });
  };
  const image = (task, generation) => {
    const canvas = document.createElement('canvas'); canvas.width = 480; canvas.height = 854;
    const ctx = canvas.getContext('2d'); ctx.fillStyle = '#fbfbfb'; ctx.fillRect(0, 0, 480, 854);
    ctx.fillStyle = '#24282c'; ctx.font = 'bold 22px "Noto Sans SC", sans-serif'; ctx.fillText('9:41', 28, 42);
    ctx.font = 'bold 35px "Noto Sans SC", sans-serif'; ctx.fillText(task === 'calendar' ? '周末行程' : '天气与出行', 28, 115);
    ctx.fillStyle = '#70777e'; ctx.font = '20px "Noto Sans SC", sans-serif'; ctx.fillText('测试画面 · ' + task + ' · 帧 ' + generation, 28, 155);
    const rows = task === 'calendar' ? ['09:30  出发去公园', '11:00  和朋友见面', '14:00  参观展览'] : ['明天  24° / 18°', '上午多云，午后有小雨', '出门记得带伞'];
    rows.forEach((text, i) => { ctx.fillStyle = '#eeeeef'; ctx.fillRect(24, 200 + i * 130, 432, 104); ctx.fillStyle = '#353b40'; ctx.font = '23px "Noto Sans SC", sans-serif'; ctx.fillText(text, 45, 260 + i * 130); });
    ctx.fillStyle = '#d7dadd'; ctx.fillRect(24, 665, 432, 6); ctx.fillStyle = '#535c64'; ctx.fillRect(24, 665, 30 + generation % 20 * 20, 6);
    ctx.fillStyle = '#777'; ctx.font = '19px "Noto Sans SC", sans-serif'; ctx.fillText('合成帧，仅用于界面验收', 28, 722);
    return canvas.toDataURL('image/jpeg', .8).split(',')[1];
  };
  window.PocketNative = { postMessage(raw) {
    const request = JSON.parse(raw), args = request.args || {}; requests.push(request);
    if (request.hostDocumentToken !== documentToken) throw new Error('Incorrect test document');
    if (request.action === 'phone.viewer.open') {
      const task = snapshot.state.tasks.find(item => item.id === args.taskId);
      if (!task || args.runId !== task.raw.runId || args.epoch !== task.raw.coordinatorEpoch) { reply(request, null, { code: 'phone_viewer_stale', message: '原任务已变化' }); return; }
      const session = { viewerId: `viewer-${++serial}`, taskId: task.id, runId: args.runId, epoch: args.epoch, generation: 0, cursor: -1 };
      sessions.set(session.viewerId, session); reply(request, { viewerId: session.viewerId, taskId: task.id, runId: args.runId, epoch: args.epoch }); return;
    }
    if (request.action === 'phone.viewer.close') { sessions.delete(args.viewerId); reply(request, { closed: true }); return; }
    if (request.action === 'phone.viewer.frame') {
      const session = sessions.get(args.viewerId);
      if (!session) { reply(request, null, { code: 'phone_viewer_stale', message: '原查看窗口已关闭' }); return; }
      if (args.afterFrameGeneration > session.cursor) throw new Error('New handle reused a cursor from another preview');
      const count = (pendingByViewer.get(session.viewerId) || 0) + 1; pendingByViewer.set(session.viewerId, count); maxPending = Math.max(maxPending, count);
      if (firstFrameStarting) {
        firstFrameStarting = false;
        // Hold only the first reply until the production viewer is visible so the
        // test can observe the preparing gap before pending geometry and pixels.
        startupReply = () => {
          pendingByViewer.set(session.viewerId, pendingByViewer.get(session.viewerId) - 1);
          reply(request, null, { code: 'phone_viewer_starting', message: '独立屏正在启动' });
        };
        return;
      }
      const generation = ready ? (generations.get(session.taskId) || 0) + 1 : 0;
      if (ready) generations.set(session.taskId, generation);
      session.cursor = generation;
      const frame = { taskId: session.taskId, runId: session.runId, epoch: session.epoch, sessionId: `display-${session.taskId}`, displayId: session.taskId === 'calendar' ? 11 : 12,
        state: ready ? 'ready' : 'pending', frameGeneration: generation, capturedAt: ready ? 10000 + generation * 200 : 0, sourceWidth: ready ? 1080 : 0, sourceHeight: ready ? 1920 : 0,
        previewWidth: ready ? 480 : 0, previewHeight: ready ? 854 : 0, mime: 'image/jpeg', ...(ready ? { imageBase64: image(session.taskId, generation) } : {}) };
      if (substitute) frame.displayId = 99;
      const finish = () => { pendingByViewer.set(session.viewerId, pendingByViewer.get(session.viewerId) - 1); reply(request, frame, fail ? { code: 'preview_unavailable', message: '测试：暂时无法读取画面' } : null); };
      if (holdNext) { holdNext = false; held = finish; } else setTimeout(finish, 25);
      return;
    }
    if (request.action === 'phone.pause' || request.action === 'phone.resume') {
      const task = snapshot.state.tasks.find(item => item.id === args.taskId);
      if (!task || args.runId !== task.raw.runId || args.epoch !== task.raw.coordinatorEpoch) throw new Error('Control changed the selected original run');
      task.status = request.action === 'phone.pause' ? 'paused' : 'running'; publish(); reply(request, { accepted: true }); return;
    }
    if (request.action === 'tasks.open') { snapshot.view.taskId = args.id; publish(); reply(request, { taskId: args.id, readOnly: true }); return; }
    if (request.action === 'tasks.close') { delete snapshot.view.taskId; publish(); reply(request, { closed: true }); return; }
    if (request.action === 'composer.edit') { snapshot.composer.text = args.text; snapshot.composer.revision++; }
    publish(); reply(request, {});
  } };
  window.phoneViewerFixture = {
    requests, frameReplies, snapshot, publish, sessions, enableFrames() { ready = true; },
    startupHeld() { return !!startupReply; }, releaseStartup() { const finish = startupReply; startupReply = null; finish?.(); },
    setHidden(value) { hidden = value; document.dispatchEvent(new Event('visibilitychange')); },
    setFailure(value) { fail = value; }, setSubstitution(value) { substitute = value; },
    hold() { holdNext = true; }, hasHeld() { return !!held; }, release() { const finish = held; held = null; finish?.(); },
    end(id) { snapshot.state.tasks.find(task => task.id === id).status = 'completed'; publish(); },
    maxPending() { return maxPending; },
  };
}

let server, browser;
try {
  await fs.mkdir(output, { recursive: true });
  const index = await fs.readFile(path.join(bundle, 'index.html'), 'utf8');
  const asset = index.match(/assets\/(index-[^" ]+\.js)/)?.[1];
  record.bundle = asset; record.sha256 = createHash('sha256').update(await fs.readFile(path.join(bundle, 'assets', asset))).digest('hex');
  server = http.createServer(async (req, res) => {
    try {
      const pathname = new URL(req.url, 'http://fixture').pathname;
      const file = path.resolve(bundle, '.' + (pathname === '/' ? '/index.html' : pathname));
      if (!file.startsWith(bundle + '/')) throw new Error('Invalid test asset');
      const bytes = await fs.readFile(file);
      res.writeHead(200, { 'content-type': ({ '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.ttf': 'font/ttf', '.woff2': 'font/woff2', '.svg': 'image/svg+xml' })[path.extname(file)] || 'application/octet-stream' }); res.end(bytes);
    } catch { res.writeHead(404); res.end(); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const origin = 'http://127.0.0.1:' + server.address().port;
  browser = await chromium.launch({ headless: true, args: ['--disable-background-networking'] });
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', serviceWorkers: 'block' });
  await context.route('**/*', route => new URL(route.request().url()).origin === origin ? route.continue() : route.abort());
  await context.addInitScript(fixture);
  const page = await context.newPage(); page.setDefaultTimeout(8000); page.on('pageerror', error => record.errors.push(error.message));
  await page.goto(origin, { waitUntil: 'networkidle' });
  const card = page.getByRole('region', { name: '手机独立屏预览', exact: true });
  await card.waitFor(); await card.scrollIntoViewIfNeeded();
  await page.waitForFunction(() => window.phoneViewerFixture.startupHeld());
  await page.evaluate(() => window.phoneViewerFixture.releaseStartup());
  await page.waitForFunction(() => window.phoneViewerFixture.frameReplies[0]?.state === 'phone_viewer_starting');
  await card.getByText('正在连接独立屏', { exact: true }).waitFor();
  assert.equal(await card.locator('.pw-phone-viewer-error').count(), 0, 'Preparing is a waiting state, not an error');
  assert.equal(await card.getByRole('button', { name: '重新读取', exact: true }).count(), 0, 'Preparing must not require a manual retry');
  await page.waitForFunction(() => window.phoneViewerFixture.frameReplies.some(frame => frame.state === 'pending'));
  assert.equal(await card.locator('.pw-phone-viewer-error').count(), 0);
  await page.screenshot({ path: path.join(output, 'phone-viewer-loading-390.png') }); record.screenshots.push('phone-viewer-loading-390.png');
  await page.evaluate(() => window.phoneViewerFixture.enableFrames());
  await card.locator('img').waitFor();
  const startup = await page.evaluate(() => ({ replies: window.phoneViewerFixture.frameReplies.slice(0, window.phoneViewerFixture.frameReplies.findIndex(frame => frame.state === 'ready') + 1), opens: window.phoneViewerFixture.requests.filter(request => request.action === 'phone.viewer.open').length }));
  assert.equal(startup.opens, 1, 'Starting, pending and ready share the original native handle');
  assert.equal(new Set(startup.replies.map(frame => frame.viewerId)).size, 1);
  assert.equal(startup.replies[0].state, 'phone_viewer_starting');
  assert.equal(startup.replies[1].state, 'pending');
  assert.equal(startup.replies.at(-1).state, 'ready');
  assert.ok(startup.replies.slice(1, -1).every(frame => frame.state === 'pending'));
  assert.deepEqual(startup.replies.slice(0, 2).map(frame => frame.afterFrameGeneration), [-1, -1]);
  assert.equal(await card.locator('.pw-phone-viewer-error').count(), 0);
  assert.equal(await card.getByRole('button', { name: '重新读取', exact: true }).count(), 0);
  record.startup = startup;
  record.checks.push('Native preparing error, pending geometry and ready pixels recover automatically on the same handle, without an error surface or manual retry.');
  const first = await card.locator('img').getAttribute('src');
  await page.waitForFunction(src => document.querySelector('.pw-phone-viewer-card img')?.getAttribute('src') !== src, first);
  assert.equal(await page.locator('[data-phone-viewer-task]').count(), 1);
  assert.equal(await page.evaluate(() => window.phoneViewerFixture.maxPending()), 1);
  record.checks.push('One in-flight frame per native handle, continuously changing decoded frames and one conversation viewer.');
  await page.screenshot({ path: path.join(output, 'phone-viewer-card-390.png') }); record.screenshots.push('phone-viewer-card-390.png');

  await card.getByRole('button', { name: '展开独立屏', exact: true }).click();
  let sheet = page.getByRole('dialog', { name: '手机独立屏', exact: true }); await sheet.waitFor();
  await page.waitForFunction(() => { const box = document.querySelector('.pw-phone-viewer-sheet')?.getBoundingClientRect(); return box && box.top < innerHeight * .1 && box.bottom <= innerHeight + 2; });
  await page.waitForFunction(() => { const sheet = document.querySelector('.pw-phone-viewer-sheet'); return sheet && getComputedStyle(sheet).opacity === '1' && sheet.getAnimations().every(animation => animation.playState !== 'running'); });
  assert.equal(await sheet.locator('figure').evaluate(figure => { const image = figure.querySelector('img'), caption = figure.querySelector('figcaption'); const bounds = figure.getBoundingClientRect(); return image && caption && image.getBoundingClientRect().bottom <= bounds.bottom + 1 && caption.getBoundingClientRect().bottom <= bounds.bottom + 1 && figure.scrollHeight <= figure.clientHeight + 1; }), true, 'Entire phone image and caption fit inside their figure; sheet body owns scrolling');
  const box = await sheet.boundingBox(); assert.ok(box && Math.abs(box.x) < 1 && Math.abs(box.width - 390) < 1 && box.height <= 844 * .95);
  assert.equal(await page.evaluate(() => window.phoneViewerFixture.sessions.size), 1);
  await page.screenshot({ path: path.join(output, 'phone-viewer-sheet-390.png') }); record.screenshots.push('phone-viewer-sheet-390.png');
  await sheet.getByRole('button', { name: '暂停执行', exact: true }).click();
  await sheet.getByRole('button', { name: '继续执行', exact: true }).waitFor();
  const paused = await sheet.locator('img').getAttribute('src');
  await page.waitForFunction(src => document.querySelector('.pw-phone-viewer-sheet img')?.getAttribute('src') !== src, paused);
  assert.deepEqual(await page.evaluate(() => window.phoneViewerFixture.requests.find(request => request.action === 'phone.pause').args), { taskId: 'calendar', runId: 'run-calendar', epoch: 1 });
  await sheet.getByRole('button', { name: '继续执行', exact: true }).click();
  await page.evaluate(() => window.PocketWeb.handleBack());
  await sheet.waitFor({ state: 'hidden' });
  assert.equal(await page.getByRole('textbox', { name: '工作输入', exact: true }).inputValue(), '等会儿再查一下路线');
  assert.equal(await page.evaluate(() => window.phoneViewerFixture.requests.filter(request => /stop|start|manual|takeover/.test(request.action)).length), 0);
  record.checks.push('Mobile bottom sheet shares the feed; exact-run pause/resume keeps frames updating; Android back preserves task and composer.');

  await page.evaluate(() => window.phoneViewerFixture.hold());
  await page.waitForFunction(() => window.phoneViewerFixture.hasHeld());
  await card.getByLabel('查看任务', { exact: true }).selectOption(JSON.stringify(['weather', 'run-weather', 1]));
  await page.locator('[data-phone-viewer-task="weather"] .pw-phone-viewer-card img').waitFor();
  await page.evaluate(() => window.phoneViewerFixture.release());
  assert.match(await card.locator('img').getAttribute('alt'), /天气/);
  assert.equal(await page.locator('[data-phone-viewer-task]').getAttribute('data-phone-viewer-task'), 'weather');
  record.checks.push('Switching the selected phone discards a late frame and never selects by global activeTaskId.');

  await page.evaluate(() => window.phoneViewerFixture.setHidden(true));
  await page.waitForFunction(() => window.phoneViewerFixture.sessions.size === 0);
  const hiddenCount = await page.evaluate(() => window.phoneViewerFixture.requests.filter(request => request.action === 'phone.viewer.frame').length);
  await page.waitForTimeout(450);
  assert.equal(await page.evaluate(() => window.phoneViewerFixture.requests.filter(request => request.action === 'phone.viewer.frame').length), hiddenCount);
  await page.evaluate(() => window.phoneViewerFixture.setHidden(false));
  await card.getByText('连续预览', { exact: true }).waitFor();
  record.checks.push('Hidden document closes native viewer and stops polling; return opens one fresh handle with cursor -1.');

  await page.evaluate(() => window.phoneViewerFixture.setSubstitution(true));
  await card.getByText('预览暂时中断', { exact: true }).waitFor();
  assert.match(await card.locator('figcaption').textContent(), /上一帧/);
  const staleCount = await page.evaluate(() => window.phoneViewerFixture.requests.filter(request => request.action === 'phone.viewer.frame').length);
  await page.waitForTimeout(450);
  assert.equal(await page.evaluate(() => window.phoneViewerFixture.requests.filter(request => request.action === 'phone.viewer.frame').length), staleCount);
  await page.screenshot({ path: path.join(output, 'phone-viewer-stale-390.png') }); record.screenshots.push('phone-viewer-stale-390.png');
  await page.evaluate(() => window.phoneViewerFixture.setSubstitution(false));
  await card.getByRole('button', { name: '重新读取', exact: true }).click();
  await card.getByText('连续预览', { exact: true }).waitFor();
  record.checks.push('Substituted display is rejected, last frame is marked stale, automatic reads stop, deliberate retry stays on original task.');

  await page.setViewportSize({ width: 1280, height: 900 });
  await card.getByRole('button', { name: '展开独立屏', exact: true }).click();
  sheet = page.getByRole('dialog', { name: '手机独立屏', exact: true }); await sheet.waitFor();
  await page.waitForFunction(() => { const sheet = document.querySelector('.pw-phone-viewer-sheet'); return sheet && getComputedStyle(sheet).opacity === '1' && sheet.getAnimations().every(animation => animation.playState !== 'running'); });
  const desktop = await sheet.boundingBox(); assert.ok(desktop && desktop.width <= 790 && desktop.height <= 900 * .95 && Math.abs(desktop.x + desktop.width / 2 - 640) < 2);
  await page.screenshot({ path: path.join(output, 'phone-viewer-sheet-1280.png') }); record.screenshots.push('phone-viewer-sheet-1280.png');
  await page.keyboard.press('Escape'); await sheet.waitFor({ state: 'hidden' });
  await page.getByRole('button', { name: '查看手机任务：查看明天的天气和出行建议', exact: true }).click();
  const taskDialog = page.getByRole('dialog', { name: '查看明天的天气和出行建议', exact: true }); await taskDialog.waitFor();
  assert.equal(await page.locator('[data-phone-viewer-task]').count(), 1);
  await taskDialog.getByRole('button', { name: '展开独立屏', exact: true }).click(); await sheet.waitFor();
  await page.evaluate(() => window.PocketWeb.handleBack()); await sheet.waitFor({ state: 'hidden' });
  assert.equal(await taskDialog.isVisible(), true);
  await page.getByRole('button', { name: '返回原工作', exact: true }).click(); await taskDialog.waitFor({ state: 'hidden' });
  record.checks.push('Desktop centered 790px sheet, Escape/focus return and nested task-detail Android back preserve the parent workspace; no duplicate viewer.');

  await card.locator('img').waitFor();
  await page.evaluate(() => window.phoneViewerFixture.end('weather'));
  await card.getByText('任务已结束', { exact: true }).waitFor();
  assert.match(await card.locator('figcaption').textContent(), /最后画面/);
  await page.waitForFunction(() => window.phoneViewerFixture.sessions.size === 0);
  record.checks.push('Task end releases the feed and labels the retained last frame without reopening or resuming execution.');
  assert.equal(record.errors.length, 0, JSON.stringify(record.errors));
  record.status = 'passed';
  await context.close();
} catch (error) { record.status = 'failed'; record.failure = error.stack; process.exitCode = 1; }
finally {
  await browser?.close(); if (server) await new Promise(resolve => server.close(resolve));
  await fs.mkdir(output, { recursive: true }); await fs.writeFile(path.join(output, 'results.json'), JSON.stringify(record, null, 2) + '\n');
  console.log(JSON.stringify(record, null, 2));
}
