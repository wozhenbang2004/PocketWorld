/**
 * Focused production-bundle reading journeys with a local, synthetic PocketNative.
 * This does not run or replace the Actions-only capture, change the app's host
 * authorities, or establish Android/WebView/physical-selection acceptance.
 * Clipboard writes and host updates are deliberately observable test fixtures.
 */
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import { chromium } from '@playwright/test';

const repo = path.resolve(import.meta.dirname, '../..');
const baseline = process.argv.includes('--baseline');
const bundle = path.resolve(process.env.READING_UI_BUNDLE || path.join(repo, 'app/src/main/assets/pocket-ui'));
const output = path.resolve(process.env.READING_UI_EVIDENCE || path.join(repo, '../qa-reading', baseline ? 'baseline' : 'current'));
const digest = value => crypto.createHash('sha256').update(value).digest('hex');
const table = [
  '| 方案 | 当前用途 | 输入保留 | 阅读位置 | 数据边界 | 下一步 |',
  '| --- | --- | --- | --- | --- | --- |',
  '| **对话现场** | 任务记录 \\| 原始材料 | 保留草稿 | 保留当前位置 | 来自测试数据 | 查看详情 |',
  '| 独立阅读 | 长回答与选字 | 不改变输入 | 返回原文位置 | 打开时的快照 | 继续阅读 |',
  '| 宽表格 | 列之间比较 | 不触发发送 | 保留横向位置 | `event_id` | [原始文档](https://example.invalid/original) |',
  '| 团队分工 | 查找当前成员 | 保留补充文字 | 回到原任务 | 相同名字，独立身份 | 查看运行状态 |',
].join('\n');
const tableTsv = [
  ['方案', '当前用途', '输入保留', '阅读位置', '数据边界', '下一步'],
  ['对话现场', '任务记录 | 原始材料', '保留草稿', '保留当前位置', '来自测试数据', '查看详情'],
  ['独立阅读', '长回答与选字', '不改变输入', '返回原文位置', '打开时的快照', '继续阅读'],
  ['宽表格', '列之间比较', '不触发发送', '保留横向位置', 'event_id', '原始文档'],
  ['团队分工', '查找当前成员', '保留补充文字', '回到原任务', '相同名字，独立身份', '查看运行状态'],
].map(row => row.join('\t')).join('\n');
const quoteTable = [
  '> | 材料 | 内容 |',
  '> | --- | --- |',
  '> | 引用块 | **保留原表格** |',
].join('\n');
const referenceTable = [
  '| 原项目 | 对照 |',
  '| --- | --- |',
  '| [项目][source-ref] | **引用式链接** |',
].join('\n');
const selectionPhrase = '我在读的这一段不会被后续回复替换。';
const codeText = 'const original = "资料";\nconst active = original;\n';
const message = [
  '## 把资料读清楚，再继续工作',
  '这是一份用于界面检查的长回答。先辨认当前任务，再查看相关材料；操作属于它作用的对象，阅读位置也是工作的一部分。',
  selectionPhrase,
  ...Array.from({ length: 5 }, (_, index) => `### ${index + 1}. 阅读中的上下文\n\n这段测试内容保留真实长度的中文说明、English terms 和句号。先给当前所需的信息，详细参数按需展开；复制或返回时，草稿仍然属于原会话。`),
  '### 工作方式比较',
  table,
  '### 一个可以复制的代码片段',
  '```js\n' + codeText + '```',
  '### 引用块中的表格', quoteTable,
  '### 表外定义的来源', referenceTable,
  '[source-ref]: https://example.invalid/reference "原始来源"',
  ...Array.from({ length: 6 }, (_, index) => `### ${index + 6}. 接下来检查什么\n\n成员、文件和回答各自保留身份。进入详情后能够继续操作，返回后接着阅读；不要让新的输出把正在看的段落突然推走。这里仅使用合成测试资料，不代表真实执行结果。`),
].join('\n\n');
const draft = '这条草稿要保留，阅读之后继续补充。';
const addition = '\n\n### 流式补充\n\n后续内容已经到达原会话，独立阅读页保持打开时的文字。';

function fixtureSnapshot(dark) {
  return {
    state: {
      modelName: '界面测试模型', modelRoute: 'api', modelProvider: 'openai_chat',
      appearance: { dark, mode: dark ? 'dark' : 'light' },
      conversationId: 'reading-conversation-a', conversations: [{ id: 'reading-conversation-a', title: '资料阅读与工作连续性' }],
      messages: [
        { id: 'reading-user', role: 'user', at: 1793776800000, content: '请把手机阅读的关键做法整理一下，附上比较表。' },
        { id: 'reading-answer', role: 'assistant', at: 1793776801000, content: message, sourceRevision: 1, retainedSources: false },
      ],
      tasks: [], projects: [], files: [], chatBusy: false, chatSubmitting: false,
      selectedChatAttachments: [], chatReceipts: {}, features: {}, ownerPending: [], ownerErrors: {}, ownerResponses: {}, ownerSnapshots: {},
    },
    composer: { key: 'reading-fixture.a', epoch: 7, revision: 1, ready: true, text: '', sourceRefs: [], pending: [], conversationId: 'reading-conversation-a' },
    phoneDraft: { epoch: 1, revision: 1, text: '', phone: false, mode: 'background' },
    host: { connected: true, ready: true },
    view: { section: null },
  };
}

function installFixture(initial) {
  const clone = value => JSON.parse(JSON.stringify(value));
  let snapshot = clone(initial), revision = 0, documentId = '';
  const commands = ['app.ready', 'app.refresh', 'composer.edit', 'composer.reading', 'conversation.select', 'media.snapshot', 'media.prepare', 'media.closeReading'];
  const requests = [], violations = [], copies = [], uiEvents = [];
  let rejectClipboard = false;
  const token = 'local-reading-ui-fixture-document';
  window.__pocketDocumentToken = token;
  const publish = () => {
    snapshot.capabilities = { commands };
    window.PocketWeb.receive({ v: 1, kind: 'state', revision: ++revision, snapshot: clone(snapshot) });
  };
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: {
    async writeText(text) {
      if (rejectClipboard) { rejectClipboard = false; throw new Error('测试复制失败，请重试'); }
      copies.push(String(text));
    },
  } });
  window.PocketNative = { postMessage(raw) {
    queueMicrotask(() => {
      const request = JSON.parse(raw);
      try {
        if (request.v !== 1 || !request.id || request.hostDocumentToken !== token) throw new Error('Invalid document-bound fixture request');
        if (request.action === 'app.ready' && !documentId) documentId = request.documentId;
        if (!documentId || request.documentId !== documentId || !commands.includes(request.action)) throw new Error('Unexpected fixture authority');
        requests.push({ action: request.action, args: clone(request.args) });
        if (request.action === 'composer.edit') {
          if (request.args.key !== snapshot.composer.key || request.args.epoch !== snapshot.composer.epoch) throw new Error('Composer identity changed');
          snapshot.composer.text = request.args.text; snapshot.composer.revision++;
        }
        if (request.action === 'composer.reading') {
          if (request.args.key !== snapshot.composer.key) throw new Error('Reading belongs to another draft');
          snapshot.composer.scrollIndex = request.args.index; snapshot.composer.scrollOffset = request.args.offset;
        }
        if (request.action === 'conversation.select') throw new Error('Use the explicit host-switch scenario, not an unmodeled select receipt');
        let value = { accepted: true };
        if (request.action === 'media.snapshot') value = { configured: true, configuration: {
          provider: 'openai_compatible', baseUrl: 'https://example.invalid', imageModel: 'fixture-image',
        } };
        if (request.action === 'media.prepare') {
          if (request.args.kind !== 'image' || typeof request.args.text !== 'string') throw new Error('Unexpected media preparation');
          value = { reviewHandle: 'fixture-media-review-1', command: 'generateImage', input: {
            taskId: 'fixture-media-task-1', kind: 'image', text: request.args.text, observeUntilComplete: true,
          } };
        }
        if (request.action === 'media.closeReading' && request.args.reviewHandle !== 'fixture-media-review-1') throw new Error('Wrong original media review handle');
        window.PocketWeb.receive({ v: 1, kind: 'result', id: request.id, ok: true, value });
        if (['app.ready', 'app.refresh', 'composer.edit'].includes(request.action)) publish();
      } catch (error) {
        violations.push(String(error.message));
        window.PocketWeb.receive({ v: 1, kind: 'result', id: request.id, ok: false, error: String(error.message) });
      }
    });
  } };
  window.readingFixture = {
    append(text) {
      const original = snapshot.state.messages.find(row => row.id === 'reading-answer');
      if (!original) throw new Error('Original message disappeared');
      original.content += text; original.sourceRevision++; snapshot.state.chatBusy = true; publish();
    },
    switchConversation() {
      snapshot.state.conversationId = 'reading-conversation-b';
      snapshot.state.conversations.push({ id: 'reading-conversation-b', title: '另一份工作' });
      snapshot.state.messages = [{ id: 'reading-other-answer', role: 'assistant', content: '这是另一个会话的内容。' }];
      snapshot.state.chatBusy = false;
      snapshot.composer = { ...snapshot.composer, key: 'reading-fixture.b', epoch: 8, revision: 1, text: '第二个会话的草稿', conversationId: 'reading-conversation-b' };
      publish();
    },
    openMedia() {
      snapshot.state.requestedConnectionSection = 'media';
      snapshot.state.requestedConnectionRevision = Number(snapshot.state.requestedConnectionRevision || 0) + 1;
      publish();
    },
    failNextCopy() { rejectClipboard = true; },
    inspect() { return { revision, requests: clone(requests), violations: [...violations], copies: [...copies], uiEvents: clone(uiEvents), snapshot: clone(snapshot) }; },
  };
  // Passive evidence only: useful if a modal return leaves a trigger unusable.
  for (const type of ['pointerdown', 'pointerup', 'click', 'focusin', 'keydown', 'scroll']) document.addEventListener(type, event => {
    const target = event.target;
    uiEvents.push({ type, at: performance.now(), key: event.key, tag: target?.tagName,
      label: target?.getAttribute?.('aria-label'), className: typeof target?.className === 'string' ? target.className : '',
      scrollTop: target?.scrollTop });
    if (uiEvents.length > 120) uiEvents.shift();
  }, { capture: true, passive: true });
  document.addEventListener('DOMContentLoaded', () => {
    const label = document.createElement('div'); label.textContent = 'Web界面 · 测试数据'; label.setAttribute('aria-hidden', 'true');
    Object.assign(label.style, { position: 'fixed', bottom: '0', right: '0', zIndex: '2147483647', padding: '2px 6px',
      pointerEvents: 'none', background: '#eeeeee', color: '#555555', font: '10px/14px sans-serif' });
    document.body.append(label);
  }, { once: true });
}

await fs.mkdir(output, { recursive: true });
const identity = JSON.parse(await fs.readFile(path.join(bundle, 'build-identity.json'), 'utf8'));
const index = await fs.readFile(path.join(bundle, 'index.html'));
const identityBytes = await fs.readFile(path.join(bundle, 'build-identity.json'));
const report = {
  kind: 'local-production-web-reading-journeys', baseline, startedAt: new Date().toISOString(), bundle,
  identity: 'Production Web bundle + local Chromium + synthetic PocketNative and clipboard; NOT Android or real execution',
  limitations: ['Selection ranges are synthetic DOM input, not physical long-press or Android IME.',
    'Clipboard fixture verifies requested text and failure feedback, not OS clipboard integration.',
    'No task, account, file or model execution occurs. The Actions-only visual gate is not run or replaced.'],
  sourceRevision: identity.sourceRevision, indexSha256: digest(index), buildIdentitySha256: digest(identityBytes), node: process.version,
  checks: [], captures: [], errors: [], viewports: [], diagnostics: [],
};
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json',
  '.woff2': 'font/woff2', '.ttf': 'font/ttf', '.png': 'image/png', '.svg': 'image/svg+xml', '.txt': 'text/plain' };
const server = http.createServer(async (request, response) => {
  try {
    const pathname = decodeURIComponent(new URL(request.url, 'http://127.0.0.1').pathname);
    const file = path.resolve(bundle, '.' + (pathname === '/' ? '/index.html' : pathname));
    if (!file.startsWith(bundle + path.sep)) { response.writeHead(403).end(); return; }
    const body = await fs.readFile(file);
    response.writeHead(200, { 'Content-Type': types[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store' }).end(body);
  } catch { response.writeHead(404).end('Not found'); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const origin = 'http://127.0.0.1:' + server.address().port;
const browser = await chromium.launch({ headless: true });
report.browser = browser.version();
let currentPage, currentViewport;
async function settle(page) {
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}
async function capture(page, name) {
  await settle(page);
  const file = currentViewport.name + '-' + name + '.png';
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
  report.captures.push({ file, viewport: currentViewport, capturedAt: new Date().toISOString() });
}
async function group(name, work) {
  try { await work(); report.checks.push({ name, viewport: currentViewport.name, passed: true }); }
  catch (error) {
    report.checks.push({ name, viewport: currentViewport.name, passed: false, error: String(error.stack || error) });
    report.diagnostics.push({ name, viewport: currentViewport.name, state: await uiState(currentPage).catch(() => null) });
    await capture(currentPage, 'FAILED-' + name).catch(() => {}); throw error;
  }
}
const originalMessage = page => page.locator('.pw-conversation-scroll .pw-message-assistant').last();
const reader = page => page.getByRole('dialog', { name: '阅读回复', exact: true });
const tableReader = page => page.getByRole('dialog', { name: '表格', exact: true });
const activePage = page => page.locator('.pw-reading-page:not([hidden])');
const conversationTop = page => page.locator('.pw-conversation-scroll').evaluate(node => node.scrollTop);
async function uiState(page) {
  return page.evaluate(() => {
    const describe = node => node && ({ tag: node.tagName, id: node.id, className: typeof node.className === 'string' ? node.className : '',
      label: node.getAttribute?.('aria-label'), role: node.getAttribute?.('role'), expanded: node.getAttribute?.('aria-expanded'),
      state: node.getAttribute?.('data-state'), pointerEvents: getComputedStyle(node).pointerEvents, hidden: node.hidden });
    const trigger = [...document.querySelectorAll('.pw-conversation-scroll .pw-message-assistant button[aria-label="消息操作与来源"]')].at(-1);
    const rect = trigger?.getBoundingClientRect();
    return { bodyStylePointerEvents: document.body.style.pointerEvents, bodyPointerEvents: getComputedStyle(document.body).pointerEvents,
      activeElement: describe(document.activeElement), trigger: describe(trigger), triggerRect: rect?.toJSON(),
      triggerHit: rect ? describe(document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2)) : null,
      overlays: [...document.querySelectorAll('[role="dialog"], [data-radix-popper-content-wrapper], [data-overlay-container], .pw-reading-overlay, .react-aria-Popover')].map(describe),
      uiEvents: window.readingFixture.inspect().uiEvents };
  });
}
async function checkOverflow(page) {
  const widths = await page.evaluate(() => ({ width: document.documentElement.clientWidth, documentWidth: document.documentElement.scrollWidth, bodyWidth: document.body.scrollWidth }));
  assert(widths.documentWidth <= widths.width + 1 && widths.bodyWidth <= widths.width + 1, 'The document overflows horizontally: ' + JSON.stringify(widths));
}
async function checkButtons(page) {
  const bounds = await page.locator('.pw-reading-dialog .pw-reading-header button,.pw-reading-page:not([hidden]) .pw-reading-tools button').evaluateAll(nodes => nodes.map(node => {
    const rect = node.getBoundingClientRect(); return { label: node.getAttribute('aria-label') || node.textContent, width: rect.width, height: rect.height, right: rect.right, bottom: rect.bottom, top: rect.top };
  }));
  assert(bounds.length >= 4);
  for (const item of bounds) assert(item.width >= 44 && item.height >= 44 && item.right <= currentViewport.width + 1 && item.bottom <= currentViewport.height + 1 && item.top >= -1,
    'Reading control is too small or clipped: ' + JSON.stringify(item));
}
async function openReader(page) {
  const trigger = originalMessage(page).getByRole('button', { name: '消息操作与来源', exact: true });
  // A person scrolls to this message-tail control before pressing it. If click()
  // auto-scrolls a distant trigger, its queued scroll can arrive after the menu
  // opens and correctly dismiss React Aria's popover. Complete that precondition;
  // never retry a failed click or alter the product's dismissal behavior.
  await trigger.scrollIntoViewIfNeeded(); await settle(page);
  report.diagnostics.push({ name: 'visible-message-trigger-before-click', viewport: currentViewport.name, state: await uiState(page) });
  await trigger.click();
  report.diagnostics.push({ name: 'message-trigger-click', viewport: currentViewport.name, state: await uiState(page) });
  const sourceTop = await conversationTop(page);
  await page.getByRole('button', { name: '阅读与选字', exact: true }).click();
  await reader(page).waitFor(); await settle(page);
  return sourceTop;
}
async function back(page) {
  assert.equal(await page.evaluate(() => window.PocketWeb.handleBack()), true, 'Native Back was not handled by the active reading layer');
  await settle(page);
}
async function assertConversationRetained(page, top) {
  assert(Math.abs(await conversationTop(page) - top) <= 1, 'Returning changed the original conversation reading position');
  assert.equal(await page.getByRole('textbox', { name: '工作输入', exact: true }).inputValue(), draft, 'Reading changed the original draft');
}

const viewports = baseline ? [{ name: 'baseline-360-light', width: 360, height: 800, dark: false }] : [
  { name: '360-light', width: 360, height: 800, dark: false },
  { name: '412-light', width: 412, height: 892, dark: false },
  { name: '360-dark', width: 360, height: 800, dark: true },
  { name: '800-landscape', width: 800, height: 360, dark: false },
];
const selectedViewports = process.env.READING_UI_VIEWPORT ? viewports.filter(viewport => viewport.name === process.env.READING_UI_VIEWPORT) : viewports;
assert(selectedViewports.length > 0, 'READING_UI_VIEWPORT must select a defined viewport');
try {
  for (const viewport of selectedViewports) {
    currentViewport = viewport; report.viewports.push(viewport);
    const context = await browser.newContext({ viewport: { width: viewport.width, height: viewport.height }, deviceScaleFactor: 1,
      locale: 'zh-CN', colorScheme: viewport.dark ? 'dark' : 'light', reducedMotion: 'reduce', hasTouch: true });
    const page = await context.newPage(); currentPage = page;
    const pageErrors = [], consoleErrors = [], blocked = [];
    page.setDefaultTimeout(8000);
    page.on('pageerror', error => pageErrors.push(String(error)));
    page.on('console', event => { if (event.type() === 'error') consoleErrors.push(event.text()); });
    await page.route('**/*', route => {
      if (new URL(route.request().url()).origin === origin) return route.continue();
      blocked.push(route.request().url()); return route.abort();
    });
    await context.addInitScript(installFixture, fixtureSnapshot(viewport.dark));
    try {
      await page.goto(origin, { waitUntil: 'networkidle' });
      await page.waitForFunction(() => document.documentElement.dataset.pocketUi === 'ready');
      await page.evaluate(() => document.fonts.ready); await settle(page);
      await page.getByRole('textbox', { name: '工作输入', exact: true }).fill(draft);
      await page.waitForFunction(expected => window.readingFixture.inspect().snapshot.composer.text === expected, draft);
      if (baseline) {
        await group('reading-and-table-expand-not-yet-present', async () => {
          const inlineTable = originalMessage(page).getByRole('region', { name: '表格，可横向滚动', exact: true }).first();
          await inlineTable.scrollIntoViewIfNeeded(); await capture(page, 'inline-table-before');
          assert.equal(await page.getByRole('button', { name: '展开表格', exact: true }).count(), 0);
          await originalMessage(page).getByRole('button', { name: '消息操作与来源', exact: true }).click();
          assert.equal(await page.getByRole('button', { name: '阅读与选字', exact: true }).count(), 0);
          await capture(page, 'message-menu-before');
        });
      } else {
        let sourceTop;
        await group('open-frozen-reader-and-trap-focus', async () => {
          sourceTop = await openReader(page);
          assert((await activePage(page).innerText()).includes(selectionPhrase));
          await checkButtons(page); await checkOverflow(page); await capture(page, 'reading');
          if (viewport.dark) assert.equal(await reader(page).evaluate(node => getComputedStyle(node).backgroundColor), 'rgb(27, 27, 27)');
          for (let index = 0; index < 10; index++) {
            await page.keyboard.press(index === 0 ? 'Shift+Tab' : 'Tab');
            assert.equal(await reader(page).evaluate(node => node.contains(document.activeElement)), true, 'Keyboard focus escaped the reading dialog');
          }
        });
        await group('selection-copy-and-streaming-freeze', async () => {
          await reader(page).getByRole('button', { name: '选字', exact: true }).click();
          const original = reader(page).getByRole('textbox', { name: '选择原文', exact: true });
          assert.equal(await original.inputValue(), message);
          const selectionStart = message.indexOf(selectionPhrase), selectionEnd = selectionStart + selectionPhrase.length;
          // Establish the range, then use a browser key event to complete it.
          // React's select plugin does not consume an arbitrary synthetic `select` event.
          await original.evaluate((node, range) => { node.focus(); node.setSelectionRange(range.start, range.end - 1, 'forward'); }, { start: selectionStart, end: selectionEnd });
          await original.press('Shift+ArrowRight');
          await reader(page).getByRole('button', { name: '复制选中文字', exact: true }).waitFor();
          await page.evaluate(value => window.readingFixture.append(value), addition);
          await page.waitForFunction(() => document.querySelector('.pw-conversation-scroll')?.textContent.includes('后续内容已经到达原会话'));
          assert.equal(await original.inputValue(), message, 'Streaming replaced the reading snapshot');
          assert.deepEqual(await original.evaluate(node => [node.selectionStart, node.selectionEnd]), [selectionStart, selectionEnd], 'Streaming disturbed the original text selection');
          assert.equal(await conversationTop(page), sourceTop, 'Streaming moved the background conversation while reading');
          await reader(page).getByRole('button', { name: '复制选中文字', exact: true }).click();
          await page.waitForFunction(expected => window.readingFixture.inspect().copies.at(-1) === expected, selectionPhrase);
          await capture(page, 'selection');
          await reader(page).getByRole('button', { name: '全选', exact: true }).click();
          assert.deepEqual(await original.evaluate(node => [node.selectionStart, node.selectionEnd]), [0, message.length]);
          await reader(page).getByRole('button', { name: '复制选中文字', exact: true }).click();
          await page.waitForFunction(expected => window.readingFixture.inspect().copies.at(-1) === expected, message);
        });
        await group('nested-table-copy-and-exact-return', async () => {
          await reader(page).getByRole('button', { name: '阅读', exact: true }).click();
          const expand = reader(page).getByRole('button', { name: '展开表格', exact: true }).first();
          await expand.scrollIntoViewIfNeeded();
          const scrollNode = activePage(page).locator('.pw-reading-scroll');
          const priorScroll = await scrollNode.evaluate(node => node.scrollTop);
          const priorDocument = await scrollNode.elementHandle();
          const sourceRegion = activePage(page).getByRole('region', { name: '表格，可横向滚动', exact: true }).first();
          const horizontal = await sourceRegion.evaluate(node => { node.scrollLeft = 80; return { width: node.clientWidth, scrollWidth: node.scrollWidth, left: node.scrollLeft }; });
          await expand.click(); await tableReader(page).waitFor();
          const region = activePage(page).getByRole('region', { name: '表格，可横向滚动', exact: true });
          assert.equal(await region.locator('th').count(), 6); assert.equal(await region.locator('tbody tr').count(), 4);
          assert((await region.innerText()).includes('任务记录 | 原始材料'));
          assert.equal(await tableReader(page).getByRole('button', { name: '展开表格', exact: true }).count(), 0, 'Expanded table recursively expands itself');
          const tableViewport = activePage(page).locator('.pw-reading-scroll');
          const expandedHorizontal = await tableViewport.evaluate(node => { node.scrollLeft = 80; return { width: node.clientWidth, scrollWidth: node.scrollWidth, left: node.scrollLeft }; });
          assert(expandedHorizontal.scrollWidth > expandedHorizontal.width && expandedHorizontal.left > 0, 'The expanded wide table cannot scroll within its reader viewport');
          await tableViewport.evaluate(node => { node.scrollLeft = 0; });
          await tableReader(page).getByRole('button', { name: '复制表格', exact: true }).click();
          await page.waitForFunction(expected => window.readingFixture.inspect().copies.at(-1) === expected, tableTsv);
          await checkButtons(page); await checkOverflow(page); await capture(page, 'table-expanded');
          await back(page); await reader(page).waitFor();
          assert(await priorDocument.evaluate(node => node.isConnected && !node.hidden), 'Nested return replaced the original reading document');
          assert.equal(await activePage(page).locator('.pw-reading-scroll').evaluate(node => node.scrollTop), priorScroll, 'Nested table return reset the reply scroll');
          assert.equal(await sourceRegion.evaluate(node => node.scrollLeft), horizontal.left, 'Nested table return reset the original table horizontal position');
          assert.equal(await reader(page).getByRole('button', { name: '展开表格', exact: true }).first().evaluate(node => document.activeElement === node), true, 'Nested return did not restore the original expand button focus');
          await expand.click(); await tableReader(page).waitFor();
          await page.keyboard.press('Escape'); await reader(page).waitFor();
          assert.equal(await activePage(page).getAttribute('data-kind'), 'message', 'One Escape closed more than the nested table');
          assert.equal(await scrollNode.evaluate(node => node.scrollTop), priorScroll, 'Nested Escape reset the reply reading position');
          await back(page); await reader(page).waitFor({ state: 'hidden' });
          await assertConversationRetained(page, sourceTop);
          assert.equal(await originalMessage(page).getByRole('button', { name: '消息操作与来源', exact: true }).evaluate(node => document.activeElement === node), true, 'Return did not restore the original message-menu trigger');
          await page.getByRole('button', { name: '有新回复 · 回到最新', exact: true }).waitFor();
        });
        await group('inline-table-snapshot-scroll-and-escape', async () => {
          const expand = originalMessage(page).getByRole('button', { name: '展开表格', exact: true }).first();
          await expand.scrollIntoViewIfNeeded();
          const originalTop = await conversationTop(page);
          await expand.click(); await tableReader(page).waitFor();
          await checkOverflow(page);
          await tableReader(page).getByRole('button', { name: '选字', exact: true }).click();
          assert.equal(await tableReader(page).getByRole('textbox', { name: '选择原文', exact: true }).inputValue(), table);
          await page.keyboard.press('Escape'); await tableReader(page).waitFor({ state: 'hidden' });
          await assertConversationRetained(page, originalTop);
          assert.equal(await expand.evaluate(node => document.activeElement === node), true, 'Inline table did not restore focus');
        });
        if (viewport.name === '360-light') {
          await group('quoted-and-reference-table-context', async () => {
            const quoteExpand = originalMessage(page).getByRole('button', { name: '展开表格', exact: true }).nth(1);
            await quoteExpand.scrollIntoViewIfNeeded();
            const originalTop = await conversationTop(page);
            await quoteExpand.click(); await tableReader(page).waitFor();
            assert.equal(await activePage(page).locator('table').count(), 1, 'Quoted Markdown stopped being a table when expanded');
            assert.deepEqual(await activePage(page).locator('td').allTextContents(), ['引用块', '保留原表格']);
            await tableReader(page).getByRole('button', { name: '选字', exact: true }).click();
            assert.equal(await tableReader(page).getByRole('textbox', { name: '选择原文', exact: true }).inputValue(), quoteTable, 'Raw quote table lost a line prefix');
            await tableReader(page).getByRole('button', { name: '全选', exact: true }).click();
            await tableReader(page).getByRole('button', { name: '复制选中文字', exact: true }).click();
            await page.waitForFunction(expected => window.readingFixture.inspect().copies.at(-1) === expected, quoteTable);
            await tableReader(page).getByRole('button', { name: '返回原工作', exact: true }).click();
            await tableReader(page).waitFor({ state: 'hidden' });
            await assertConversationRetained(page, originalTop);
            const referenceExpand = originalMessage(page).getByRole('button', { name: '展开表格', exact: true }).nth(2);
            await referenceExpand.scrollIntoViewIfNeeded(); await referenceExpand.click(); await tableReader(page).waitFor();
            assert.equal(await activePage(page).locator('table').count(), 1);
            assert.equal(await activePage(page).getByRole('link', { name: '项目', exact: true }).getAttribute('href'), 'https://example.invalid/reference', 'Expansion lost a link defined outside the table');
            assert.deepEqual(await activePage(page).locator('td').allTextContents(), ['项目', '引用式链接']);
            await tableReader(page).getByRole('button', { name: '复制表格', exact: true }).click();
            await page.waitForFunction(expected => window.readingFixture.inspect().copies.at(-1) === expected, '原项目\t对照\n项目\t引用式链接');
            await checkOverflow(page); await capture(page, 'reference-table');
            await page.keyboard.press('Escape'); await tableReader(page).waitFor({ state: 'hidden' });
          });
          await group('copy-failure-retry-and-mode-scroll', async () => {
            sourceTop = await openReader(page);
            const scrollNode = activePage(page).locator('.pw-reading-scroll');
            await scrollNode.evaluate(node => { node.scrollTop = 333; });
            const prior = await scrollNode.evaluate(node => node.scrollTop);
            await reader(page).getByRole('button', { name: '选字', exact: true }).click();
            await reader(page).getByRole('button', { name: '阅读', exact: true }).click();
            assert.equal(await scrollNode.evaluate(node => node.scrollTop), prior, 'Switching between reading and selection reset reading position');
            await page.evaluate(() => window.readingFixture.failNextCopy());
            await reader(page).getByRole('button', { name: '复制全文', exact: true }).click();
            await reader(page).getByRole('status').filter({ hasText: '测试复制失败，请重试' }).waitFor();
            await reader(page).getByRole('button', { name: '复制全文', exact: true }).click();
            await page.waitForFunction(expected => window.readingFixture.inspect().copies.at(-1) === expected, message + addition);
            await page.keyboard.press('Escape'); await reader(page).waitFor({ state: 'hidden' });
            await assertConversationRetained(page, sourceTop);
          });
          await group('conversation-change-closes-old-reader', async () => {
            await openReader(page);
            // Let the original scroll acknowledgement settle before replacing its owner.
            await page.waitForTimeout(250);
            await page.evaluate(() => window.readingFixture.switchConversation());
            await page.locator('.pw-reading-dialog').waitFor({ state: 'hidden' });
            await page.getByText('这是另一个会话的内容。', { exact: true }).waitFor();
            assert.equal(await page.getByRole('textbox', { name: '工作输入', exact: true }).inputValue(), '第二个会话的草稿');
            assert.equal(await page.evaluate(() => window.PocketWeb.handleBack()), false, 'Closed reading layer still intercepts native Back');
          });
          await group('media-review-nesting-keeps-original-confirmation', async () => {
            await page.evaluate(() => window.readingFixture.openMedia());
            await page.getByRole('button', { name: '开始创作', exact: true }).click();
            const input = page.getByRole('textbox', { name: '描述要生成的内容', exact: true });
            await input.fill(table);
            await page.getByRole('button', { name: '准备并核对输入', exact: true }).click();
            const review = page.getByRole('dialog', { name: '提交这次媒体请求', exact: true });
            await review.waitFor();
            const acknowledgement = review.getByRole('checkbox', { name: '已核对本次输入、服务与操作', exact: true });
            // React Aria visually hides its native input. Press the visible
            // associated label, then assert the original checkbox state.
            await review.getByText('已核对本次输入、服务与操作', { exact: true }).click();
            assert.equal(await acknowledgement.isChecked(), true);
            const reviewNode = await review.elementHandle();
            const expand = review.getByRole('button', { name: '展开表格', exact: true });
            await expand.scrollIntoViewIfNeeded(); await expand.click(); await tableReader(page).waitFor();
            assert.equal(await tableReader(page).evaluate(node => node.contains(document.elementFromPoint(innerWidth / 2, innerHeight / 2))), true, 'An underlying settings layer covers the reader');
            for (let index = 0; index < 10; index++) {
              await page.keyboard.press(index === 0 ? 'Shift+Tab' : 'Tab');
              assert.equal(await tableReader(page).evaluate(node => node.contains(document.activeElement)), true, 'Tab escaped into the underlying media confirmation');
            }
            await checkButtons(page); await checkOverflow(page); await capture(page, 'media-review-table');
            await page.keyboard.press('Escape'); await tableReader(page).waitFor({ state: 'hidden' }); await review.waitFor();
            assert(await reviewNode.evaluate(node => node.isConnected), 'Escape replaced the original confirmation');
            assert.equal(await acknowledgement.isChecked(), true, 'Reader Escape reset the original acknowledgement');
            assert.equal(await page.evaluate(() => window.readingFixture.inspect().requests.filter(row => row.action === 'media.closeReading').length), 0, 'Reader Escape released the original review handle');
            assert.equal(await expand.evaluate(node => document.activeElement === node), true, 'Reader Escape did not restore the original review focus');
            await expand.click(); await tableReader(page).waitFor();
            await back(page); await tableReader(page).waitFor({ state: 'hidden' }); await review.waitFor();
            assert.equal(await acknowledgement.isChecked(), true, 'Native Back reset the original acknowledgement');
            assert.equal(await page.evaluate(() => window.readingFixture.inspect().requests.filter(row => row.action === 'media.closeReading').length), 0, 'Reader Back released the original review handle');
            await capture(page, 'media-review-return');
            await back(page); await review.waitFor({ state: 'hidden' });
            await page.waitForFunction(() => window.readingFixture.inspect().requests.filter(row => row.action === 'media.closeReading').length === 1);
            assert.equal(await input.inputValue(), table, 'Returning from review lost the media input');
            const calls = await page.evaluate(() => window.readingFixture.inspect().requests);
            assert.deepEqual(calls.filter(row => row.action === 'media.closeReading').map(row => row.args), [{ reviewHandle: 'fixture-media-review-1' }]);
            assert.equal(calls.filter(row => row.action === 'media.confirm').length, 0, 'A reading journey submitted a media operation');
          });
        }
      }
      await group('fixture-authority-console-and-overflow', async () => {
        await checkOverflow(page);
        const state = await page.evaluate(() => window.readingFixture.inspect());
        assert.deepEqual(pageErrors, []); assert.deepEqual(consoleErrors, []); assert.deepEqual(blocked, []); assert.deepEqual(state.violations, []);
        assert.equal(state.requests.filter(row => row.action === 'app.ready').length, 1);
        assert(state.requests.every(row => ['app.ready', 'app.refresh', 'composer.edit', 'composer.reading', 'media.snapshot', 'media.prepare', 'media.closeReading'].includes(row.action)), 'Reading caused a write or navigation command outside the fixture contract');
        report.viewports.at(-1).commands = state.requests.map(row => ({ action: row.action, args: row.args }));
        report.viewports.at(-1).clipboardWrites = state.copies.length;
      });
    } catch (error) {
      report.errors.push({ viewport: viewport.name, error: String(error.stack || error), pageErrors, consoleErrors, blocked });
    } finally { await context.close(); }
  }
} finally {
  await browser.close(); await new Promise(resolve => server.close(resolve));
  report.finishedAt = new Date().toISOString();
  report.status = report.errors.length || report.checks.some(row => !row.passed) ? 'failed' : 'passed';
  await fs.writeFile(path.join(output, 'results.json'), JSON.stringify(report, null, 2));
}
for (const check of report.checks) process.stdout.write(`${check.passed ? 'PASS' : 'FAIL'} ${check.viewport} ${check.name}${check.error ? ': ' + check.error.split('\n')[0] : ''}\n`);
process.stdout.write(JSON.stringify({ status: report.status, checks: report.checks.length, captures: report.captures.length, output }) + '\n');
if (report.status !== 'passed') process.exitCode = 1;
