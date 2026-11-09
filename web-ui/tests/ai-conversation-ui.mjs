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
const matrix = [390, 320, 360, 1280].flatMap(width => [false, true].map(dark => ({ width, height: 844, dark })));
const record = {
  scope: 'Production renderer: conversation/navigation at 320, 360, 390 and 1280px in light/dark, plus empty main at 320/390px in light mode; synthetic native receipts. Not Android, IME, real provider or listener validation.',
  synthetic: true, outcome: 'not_run', visualReview: 'not_performed_by_runner',
  startedAt: new Date().toISOString(), matrix, checks: [], screenshots: [], errors: [], scenes: [], emptyScenes: [],
};
let browser, server;
await fs.mkdir(output, { recursive: true });
const passed = (scene, name, evidence) => record.checks.push({ scene, name, passed: true, ...(evidence ? { evidence } : {}) });

/** Extend only native envelopes. Production App, composer, Markdown and task views
 * stay mounted; these display records and read receipts cannot execute a task. */
function installMainChatHost() {
  const priorBridge = Object.getOwnPropertyDescriptor(window, 'PocketWeb');
  if (!priorBridge?.get || !priorBridge?.set) throw new Error('Install the conversation fixture before its chat presentation extension');
  const originalPost = window.PocketNative.postMessage, calls = [], errors = [];
  // The production bridge consumes and deletes this bootstrap value on handshake.
  // Keep the same immutable host identity as installAiCenterHost, not the deleted global.
  const documentToken = window.__pocketDocumentToken;
  if (!documentToken) throw new Error('Install the chat host before the production document handshake');
  const clone = value => structuredClone(value);
  const taskId = 'fixture-completed', runId = 'fixture-completed-run', epoch = 2;
  const addedCommands = ['tasks.open', 'tasks.close', 'trace.read', 'trace.close', 'conversation.drafts',
    'voice.start', 'conversation.organization.read', 'remoteInbox.state'];
  const markdown = [
    '### 可执行的核对清单',
    '- 保留原始资料、文件名和有待确认的结论，长条目可以自然换行。',
    '- 逐项核对入口、返回位置和正在编辑的草稿，不把一次页面切换当作保存成功。',
    '  1. 先阅读已取得的依据。',
    '  2. 再确认这次操作对应的原任务。',
    '',
    '接口标识：`conversation_source_reference_' + 'original_'.repeat(12) + 'retained`。',
    '',
    '```javascript',
    'const normalizeMainChatEvidence = (source) => ({ ...source, reference: "https://fixture.example.invalid/references/' + 'original-source-'.repeat(12) + 'report.json" });',
    '```',
    '',
    '这些只是渲染测试资料；长代码可以在代码区域内滚动，正文和附件不能撑宽整个对话。',
  ].join('\n');
  let lastSnapshot, idle = false, unread = 0;
  const publish = () => window.PocketWeb.receive({ v: 1, kind: 'state', revision: 0, snapshot: clone(lastSnapshot) });
  Object.defineProperty(window, 'PocketWeb', {
    configurable: true,
    get: () => priorBridge.get.call(window),
    set(value) {
      const receive = value.receive;
      value.receive = input => {
        const message = typeof input === 'string' ? JSON.parse(input) : clone(input);
        if (message.kind === 'state') {
          const snapshot = message.snapshot;
          snapshot.capabilities.commands = [...new Set([...snapshot.capabilities.commands, ...addedCommands])];
          snapshot.state.remoteInbox = { ...(snapshot.state.remoteInbox || {}), unread };
          const answer = snapshot.state.messages.find(row => row.id === 'fixture-answer-completed');
          if (answer && !answer.content.includes('normalizeMainChatEvidence')) answer.content += '\n\n' + markdown;
          const request = snapshot.state.messages.find(row => row.id === 'fixture-request');
          if (request) request.attachments = [
            { id: 'fixture-reference-notes', name: '本周设计反馈与原始材料逐项核对记录（保留未确认结论和返回位置）.md', sha256: 'a'.repeat(64) },
            { id: 'fixture-reference-data', name: 'original_conversation_attachment_' + 'source_reference_'.repeat(6) + '.json', sha256: 'b'.repeat(64) },
          ];
          if (idle) Object.assign(snapshot.state, { chatBusy: false, chatActiveTurn: null, foregroundTurn: null });
          lastSnapshot = clone(snapshot);
        }
        receive(message);
      };
      // The existing conversation adapter patches its envelope before this final
      // presentation extension; its model identities and mutation guards are kept.
      priorBridge.set.call(window, value);
    },
  });
  const handled = new Set(['tasks.open', 'tasks.close', 'trace.read', 'trace.close', 'conversation.drafts']);
  window.PocketNative.postMessage = raw => {
    const request = JSON.parse(raw);
    if (!handled.has(request.action)) return originalPost(raw);
    calls.push(clone(request));
    queueMicrotask(() => {
      try {
        if (request.hostDocumentToken !== documentToken) throw new Error('Chat fixture document changed');
        const args = request.args || {};
        let value;
        if (request.action === 'tasks.open') {
          if (args.id !== taskId || args.requestNavigation !== false || Object.keys(args).length !== 2) throw new Error('Trace entry changed the original task or navigation intent');
          value = { taskId, readOnly: true, executionAuthorized: false };
        } else if (request.action === 'trace.read') {
          if (args.taskId !== taskId || args.runId && args.runId !== runId || args.cursor !== '' || args.limit !== 100) throw new Error('Trace read changed the original task or run');
          const at = 1794205000000;
          // A complete retained-run window: one known event, no older page or
          // limited/invalid rows. This does not claim full historical execution coverage.
          value = { format: 'pocketworld.execution-trace.v1', taskId, runId, epoch, capturedAt: at + 2000,
            runs: [{ runId, epoch, status: 'completed', startedAt: at, updatedAt: at + 1000 }],
            runsLimited: false, relations: [], limit: 100,
            nodes: [{ id: 'event:fixture-completed-summary', kind: 'event', role: 'assistant', parentId: null,
              name: '本次资料已整理，原来源仍保留', status: 'completed', sequence: 1, source: 'task_event',
              taskId, runId, epoch, turnId: 'fixture-turn', turnIndex: 1, startedAt: at, finishedAt: at + 1000,
              elapsedNanos: null, durationMs: null, timingKnown: false, legacy: false }],
            cursor: 'fixture-completed-trace-page', nextCursor: '', hasMore: false, limited: false,
            unboundLegacyEvents: 0, invalidRows: 0, oversizedRows: 0, rowsExamined: 1,
            coverage: 'retained_run_bound_events_only', pageSemantics: 'fixed_event_membership_receipts_may_finish',
            compact: true, bodyIncluded: false, readOnly: true, executionAuthorized: false };
        } else if (request.action === 'conversation.drafts') {
          if (args.taskId !== taskId || Object.keys(args).length !== 1) throw new Error('Proposal disclosure changed the original answer');
          value = { taskId, bodyIncluded: false, partial: false, drafts: Array.from({ length: 20 }, (_, index) => ({
            kind: 'memory', taskId, runId, epoch, operationId: `fixture-memory-proposal-${index + 1}`,
            proposalDigest: (index + 1).toString(16).padStart(64, '0'),
            title: `第 ${index + 1} 项原记忆提案：保留这次设计反馈的原始依据、未确认结论与返回位置，长标题仍应完整地归属于同一项待审阅提案`,
            apply: { status: 'pending_owner' },
          })) };
        } else value = { closed: true, taskCancelled: false };
        window.PocketWeb.receive({ v: 1, kind: 'result', id: request.id, ok: true, value });
      } catch (error) {
        errors.push(String(error.message || error));
        window.PocketWeb.receive({ v: 1, kind: 'result', id: request.id, ok: false,
          error: { code: 'not_accepted', message: String(error.message || error) } });
      }
    });
  };
  window.mainChatFixture = {
    synthetic: true, calls, errors,
    setIdle(value) { idle = value; publish(); },
    setUnread(value) { unread = value; publish(); },
  };
}

/** Keep the existing empty host's accounts, history and model state. Only clear
 * its demonstration composer text; the actual App still renders the empty screen. */
function installEmptyMainHost({ configured = false } = {}) {
  let bridge;
  Object.defineProperty(window, 'PocketWeb', {
    configurable: true, get: () => bridge,
    set(value) {
      const receive = value.receive;
      value.receive = input => {
        const message = typeof input === 'string' ? JSON.parse(input) : structuredClone(input);
        if (message.kind === 'state') {
          message.snapshot.composer.text = '';
          if (configured) message.snapshot.capabilities.commands.push('attachments.openPicker', 'phone.prepare');
        }
        receive(message);
      };
      bridge = value;
    },
  });
}

async function composerGeometry(page, scene, label, hasText) {
  const surface = page.locator('.pw-composer-card');
  const names = ['输入方式与选项', '执行权限', '切换此对话模型：fixture-reasoner-B', '推理强度：低', '语音输入', ...(hasText ? ['发送'] : [])];
  for (const name of names) await expect(surface.getByRole('button', { name, exact: true })).toBeVisible();
  await expect(surface.getByRole('button', { name: '发送', exact: true })).toHaveCount(hasText ? 1 : 0);
  const geometry = await surface.evaluate((card, names) => {
    const box = node => { const r = node.getBoundingClientRect(); return { left: r.left, right: r.right, top: r.top, bottom: r.bottom, width: r.width, height: r.height }; };
    const buttons = [...card.querySelectorAll('button')];
    const controls = names.map(name => {
      const node = buttons.find(button => button.getAttribute('aria-label') === name), rect = box(node);
      const hit = document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2);
      return { name, ...rect, receivesPointer: !!hit && (hit === node || node.contains(hit)) };
    });
    const editor = card.querySelector('textarea'), style = getComputedStyle(card);
    return { card: box(card), editor: box(editor), paddingLeft: parseFloat(style.paddingLeft), paddingRight: parseFloat(style.paddingRight),
      editorBorder: getComputedStyle(editor).borderWidth, editorScrollHeight: editor.scrollHeight, controls,
      documentWidth: document.documentElement.scrollWidth, viewportWidth: innerWidth, viewportHeight: innerHeight };
  }, names);
  assert.ok(geometry.editor.width >= geometry.card.width - geometry.paddingLeft - geometry.paddingRight - 2, `${label}: editor must use the full card content width`);
  assert.ok(geometry.editor.bottom <= Math.min(...geometry.controls.map(row => row.top)) + 1, `${label}: text must stay above the toolbar`);
  assert.equal(geometry.editorBorder, '0px', `${label}: editor has an ornamental border`);
  assert.ok(geometry.documentWidth <= geometry.viewportWidth + 1, `${label}: composer widened the document`);
  assert.ok(geometry.editor.top >= 0 && geometry.editor.bottom <= geometry.viewportHeight + 1, `${label}: editor is outside the viewport`);
  for (const [index, control] of geometry.controls.entries()) {
    assert.ok(control.width >= 44 - .1 && control.height >= 44 - .1, `${label}: incomplete touch target ${control.name}: ${JSON.stringify(control)}`);
    assert.ok(control.left >= geometry.card.left - 1 && control.right <= geometry.card.right + 1 && control.bottom <= geometry.card.bottom + 1,
      `${label}: control escaped the primary input surface: ${control.name}`);
    assert.ok(control.left >= -1 && control.right <= geometry.viewportWidth + 1 && control.top >= 0 && control.bottom <= geometry.viewportHeight + 1,
      `${label}: touch target is clipped by the viewport: ${control.name}`);
    assert.equal(control.receivesPointer, true, `${label}: another element covers ${control.name}`);
    if (index) assert.ok(geometry.controls[index - 1].right <= control.left + 1, `${label}: toolbar controls overlap or changed order`);
  }
  passed(scene, label, geometry);
  return geometry;
}

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
  await context.addInitScript({ content: `(${installAiCenterHost.toString()})(${JSON.stringify({ dark })});(${installAiConversationHost.toString()})({representative:true});(${installMainChatHost.toString()})();` });
  const page = await context.newPage();
  page.setDefaultTimeout(12000);
  page.on('pageerror', error => record.errors.push({ scene, error: error.message }));
  const summary = () => page.evaluate(() => ({ ...window.aiConversationFixture.summary(),
    mainChat: { errors: [...window.mainChatFixture.errors],
      calls: window.mainChatFixture.calls.map(({ action, args }) => ({ action, args })) } }));
  const calls = action => page.evaluate(action => window.aiConversationFixture.calls.filter(row => row.action === action), action);
  const count = async action => (await calls(action)).length;
  const chatCalls = action => page.evaluate(action => window.mainChatFixture.calls.filter(row => row.action === action), action);
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

    const header = page.locator('.pw-app-header');
    await expect(header.getByRole('heading', { level: 1 }).getByRole('button', { name: '整理当前对话', exact: true })).toHaveCount(1);
    await expect(header.getByRole('button', { name: '新建对话', exact: true })).toBeVisible();
    await expect(header.getByRole('button', { name: /^(查看通知|通知 ·)/ })).toHaveCount(0);
    await page.evaluate(() => window.mainChatFixture.setUnread(2));
    await expect(header.getByRole('button', { name: '通知 · 2 条未读', exact: true })).toBeVisible();
    await shot('header-unread');
    await page.evaluate(() => window.mainChatFixture.setUnread(0));
    await expect(header.getByRole('button', { name: /^(查看通知|通知 ·)/ })).toHaveCount(0);
    passed(scene, 'Conversation title owns organization; only unread notifications occupy the header');

    await page.evaluate(() => window.mainChatFixture.setIdle(true));
    await input.fill('');
    await expect(page.getByRole('button', { name: '语音输入', exact: true })).toBeVisible();
    await expect(input).toHaveAttribute('placeholder', '问点什么…');
    const emptyGeometry = await composerGeometry(page, scene, 'Empty input keeps full-width text above the reachable toolbar', false);
    await shot('composer-empty');
    await input.fill(draft);
    await composerGeometry(page, scene, 'Filled input keeps all six touch targets separate and ordered', true);
    await shot('composer-filled');
    const longDraft = draft + '\n' + Array.from({ length: 7 }, (_, index) => `第 ${index + 1} 项：请保留长内容、原始附件和返回后的草稿，继续核对当前对话。`).join('\n');
    await input.fill(longDraft);
    const longGeometry = await composerGeometry(page, scene, 'Multiline input grows without displacing or covering its toolbar', true);
    assert.ok(longGeometry.editor.height > emptyGeometry.editor.height + 10, 'A multiline draft must receive more editing space');
    await assertDraft(longDraft);
    await shot('composer-multiline');
    if (width === 390) {
      await page.setViewportSize({ width, height: 480 });
      await input.focus();
      await expect(input).toBeFocused();
      await input.fill(longDraft + '\n压缩视口仍可继续编辑。');
      await assertDraft(longDraft + '\n压缩视口仍可继续编辑。');
      await composerGeometry(page, scene, '390x480 renderer viewport retains editable text and visible controls; not Android IME evidence', true);
      await shot('composer-short-viewport');
      await page.setViewportSize({ width, height });
    }
    await input.fill(draft);
    await assertDraft(draft);
    await page.evaluate(() => window.mainChatFixture.setIdle(false));
    await shot('conversation');
    const longReply = page.locator('[data-message-id="fixture-answer-completed"]');
    await expect(longReply).toContainText('如果来源缺失，先保留缺口，再决定下一步。');
    await expect(longReply.locator('ul')).toHaveCount(1);
    await expect(longReply.locator('ol')).toHaveCount(1);
    await expect(longReply.locator('pre')).toContainText('normalizeMainChatEvidence');
    await expect(page.locator('[data-message-id="fixture-request"] .pw-message-files > button')).toHaveCount(2);
    const contentBounds = await page.locator('[data-message-id="fixture-answer-completed"], [data-message-id="fixture-request"]').evaluateAll(messages => messages.map(message => {
      const rect = message.getBoundingClientRect();
      const blocks = [...message.querySelectorAll('.pw-user-text, .pw-message-files, .pw-message-files > button, .pw-markdown, .pw-markdown-code, pre, ul, ol')];
      return { id: message.getAttribute('data-message-id'), width: message.clientWidth, scrollWidth: message.scrollWidth,
        escaped: blocks.map(node => { const box = node.getBoundingClientRect(); return { tag: node.tagName, className: node.className, left: box.left, right: box.right, width: box.width }; })
          .filter(box => box.width > 0 && (box.left < rect.left - 1 || box.right > rect.right + 1)) };
    }));
    for (const bounds of contentBounds) {
      assert.ok(bounds.scrollWidth <= bounds.width + 1, `${bounds.id}: long content widened its message`);
      assert.deepEqual(bounds.escaped, [], `${bounds.id}: Markdown or attachment escaped its message`);
    }
    passed(scene, 'Long Markdown, nested lists, code and original user attachment names stay inside their messages', contentBounds);
    const completed = page.getByRole('button', { name: '查看任务详情：整理本周的设计反馈', exact: true });
    await expect(completed).toHaveClass(/pw-linked-task-complete/);
    await expect(completed).toContainText('已完成'); await expect(completed).toContainText('查看详情');
    await expect(completed.getByText('整理本周的设计反馈', { exact: true })).toHaveCount(0);
    await page.locator('.pw-conversation-scroll').evaluate(node => node.scrollTo({ top: 0 }));
    await shot('long-answer');
    await longReply.locator('pre').scrollIntoViewIfNeeded();
    await shot('markdown-code');
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

    const trace = longReply.getByRole('button', { name: '查看这条回复的执行轨迹', exact: true });
    await trace.scrollIntoViewIfNeeded();
    await expect(trace).toHaveClass(/pw-message-trace/);
    const traceLabel = await trace.evaluate(button => {
      const walker = document.createTreeWalker(button, NodeFilter.SHOW_TEXT);
      let text;
      while ((text = walker.nextNode())) if (text.textContent.trim() === '执行轨迹') break;
      if (!text) return { found: false };
      const range = document.createRange(); range.selectNodeContents(text);
      const label = range.getBoundingClientRect(), target = button.getBoundingClientRect();
      const hit = document.elementFromPoint(target.x + target.width / 2, target.y + target.height / 2);
      return { found: true, labelWidth: label.width, targetWidth: target.width, targetHeight: target.height,
        labelContained: label.left >= target.left - 1 && label.right <= target.right + 1 && label.top >= target.top - 1 && label.bottom <= target.bottom + 1,
        receivesPointer: !!hit && (hit === button || button.contains(hit)) };
    });
    assert.equal(traceLabel.found, true); assert.ok(traceLabel.labelWidth > 0);
    assert.ok(traceLabel.targetWidth >= 44 && traceLabel.targetHeight >= 44);
    assert.equal(traceLabel.labelContained, true, 'The trace label must fit inside its own button');
    assert.equal(traceLabel.receivesPointer, true);
    await shot('trace-action');
    assert.equal((await chatCalls('trace.read')).length, 0, 'A message footer must not preload trace bodies');
    await trace.click();
    const taskDrawer = page.locator('.pw-task-drawer');
    await expect(taskDrawer).toHaveAttribute('data-task-layer', 'process');
    await expect(taskDrawer.getByRole('heading', { name: '执行过程', exact: true })).toBeVisible();
    await expect(taskDrawer.locator('.pw-task-context')).toHaveText('整理本周的设计反馈');
    await expect(taskDrawer.locator('.pw-execution-trace')).toHaveAttribute('data-completeness', 'complete');
    await expect(taskDrawer.locator('[data-node-id="event:fixture-completed-summary"]')).toBeVisible();
    assert.deepEqual((await chatCalls('tasks.open')).map(row => row.args), [{ id: 'fixture-completed', requestNavigation: false }]);
    const traceReads = await chatCalls('trace.read');
    assert.ok(traceReads.length > 0);
    assert.ok(traceReads.every(row => row.args.taskId === 'fixture-completed' && ['', 'fixture-completed-run'].includes(row.args.runId)));
    await shot('trace-original-task', taskDrawer);
    await taskDrawer.getByRole('button', { name: '返回任务结果', exact: true }).click();
    await expect(taskDrawer).toHaveAttribute('data-task-layer', 'overview');
    await taskDrawer.getByRole('button', { name: '返回原工作', exact: true }).click();
    await expect(taskDrawer).toHaveCount(0);
    await assertDraft(draft);
    passed(scene, 'Visible trace label opens the original task/run in process view and returns with the draft intact', { traceLabel, traceReads: traceReads.map(row => row.args) });

    await expect(page.locator('.pw-message-footer .pw-conversation-drafts')).toHaveCount(0);
    assert.equal((await chatCalls('conversation.drafts')).length, 0, 'Message display must not load proposal lists');
    await longReply.getByRole('button', { name: '消息操作与来源', exact: true }).click();
    const proposals = page.getByRole('region', { name: '这次回答的提案', exact: true });
    await expect(proposals).toBeVisible();
    assert.equal((await chatCalls('conversation.drafts')).length, 0, 'Opening message actions must not load a collapsed proposal list');
    await proposals.getByRole('button', { name: '记忆与技能提案', exact: true }).click();
    const proposalRows = proposals.locator('.pw-conversation-draft-list > button');
    await expect(proposalRows).toHaveCount(20);
    assert.deepEqual((await chatCalls('conversation.drafts')).map(row => row.args), [{ taskId: 'fixture-completed' }]);
    const proposalReachability = [];
    const lastProposal = proposalRows.nth(19);
    await expect(lastProposal).toContainText('第 20 项原记忆提案');
    for (const [name, target] of [['last-proposal', lastProposal], ['refresh-proposals', proposals.getByRole('button', { name: '刷新原提案状态', exact: true })]]) {
      await target.scrollIntoViewIfNeeded();
      await target.focus();
      await expect(target).toBeFocused();
      const geometry = await target.evaluate(button => {
        const popover = button.closest('.pw-message-popover');
        if (!popover) return { foundPopover: false };
        const box = button.getBoundingClientRect(), surface = popover.getBoundingClientRect();
        const hit = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2);
        return { foundPopover: true, scrollTop: popover.scrollTop, scrollHeight: popover.scrollHeight, clientHeight: popover.clientHeight,
          overflowY: getComputedStyle(popover).overflowY,
          containedByPopover: box.left >= surface.left - 1 && box.right <= surface.right + 1 && box.top >= surface.top - 1 && box.bottom <= surface.bottom + 1,
          containedByViewport: box.left >= -1 && box.right <= innerWidth + 1 && box.top >= 0 && box.bottom <= innerHeight + 1,
          receivesPointer: !!hit && (hit === button || button.contains(hit)) };
      });
      assert.equal(geometry.foundPopover, true, `${name}: proposal belongs to the message popover`);
      assert.ok(['auto', 'scroll'].includes(geometry.overflowY), `${name}: long proposal window must scroll within its popover`);
      assert.ok(geometry.scrollHeight > geometry.clientHeight && geometry.scrollTop > 0, `${name}: reached the original window's lower entries by scrolling`);
      assert.equal(geometry.containedByPopover, true, `${name}: target escaped its popover`);
      assert.equal(geometry.containedByViewport, true, `${name}: target is outside the viewport`);
      assert.equal(geometry.receivesPointer, true, `${name}: target center is obscured`);
      proposalReachability.push({ name, ...geometry });
    }
    assert.equal((await chatCalls('conversation.drafts')).length, 1, 'Scrolling and focusing a proposal must not reload its native window');
    assert.equal(await page.evaluate(() => window.aiConversationFixture.calls.some(row => row.action.startsWith('memoryDraft.'))), false,
      'Reachability checks must not open or apply a memory proposal');
    await shot('message-proposals');
    await page.keyboard.press('Escape');
    await expect(proposals).toHaveCount(0);
    await assertDraft(draft);
    passed(scene, 'Twenty long answer proposals read once on expansion; the last item and refresh remain reachable inside the message popover', proposalReachability);

    await openNavigation();
    const recent = page.getByRole('region', { name: '最近对话', exact: true });
    const workspaces = page.getByRole('navigation', { name: '工作区', exact: true });
    await recent.waitFor(); await workspaces.waitFor();
    await expect(page.getByRole('link', { name: '通知', exact: true })).toBeVisible();
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
    assert.deepEqual(await page.evaluate(() => window.mainChatFixture.errors), []);
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

async function runEmptyMainScene(origin, width) {
  const scene = `${width}-light-empty-main`;
  const configured = width === 320;
  const context = await browser.newContext({ viewport: { width, height: 844 }, isMobile: true,
    hasTouch: true, deviceScaleFactor: 1, locale: 'zh-CN', timezoneId: 'Asia/Shanghai',
    colorScheme: 'light', reducedMotion: 'reduce', serviceWorkers: 'block' });
  const externalRequests = [];
  await context.route('**/*', route => {
    if (new URL(route.request().url()).origin === origin) return route.continue();
    externalRequests.push(route.request().url()); return route.abort();
  });
  await context.addInitScript({ content: `(${installAiCenterHost.toString()})(${JSON.stringify({ empty: !configured })});(${installEmptyMainHost.toString()})(${JSON.stringify({ configured })});` });
  const page = await context.newPage();
  page.setDefaultTimeout(12000);
  page.on('pageerror', error => record.errors.push({ scene, error: error.message }));
  try {
    await page.goto(origin, { waitUntil: 'networkidle' });
    const main = page.locator('.pw-chat-workspace');
    await expect(main).toHaveClass(/pw-chat-empty/);
    await expect(main.getByRole('heading', { level: 2, name: '从一个想法开始', exact: true })).toBeVisible();
    const shortcutNames = configured ? ['处理文件', '手机操作', '使用技能'] : ['选择模型，开始对话'];
    const start = main.locator('.pw-chat-start');
    await expect(start.getByRole('button')).toHaveCount(shortcutNames.length);
    for (const name of shortcutNames) {
      const shortcut = start.getByRole('button', { name, exact: true });
      await expect(shortcut).toBeVisible();
      await expect(shortcut).toBeEnabled();
      await expect(shortcut).toBeInViewport({ ratio: 1 });
    }
    await expect(page.locator('.pw-composer-row textarea')).toHaveValue('');
    await expect(page.locator('.pw-message-stream')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '发送', exact: true })).toHaveCount(0);
    await page.evaluate(() => document.fonts.ready);
    const geometry = await main.evaluate(node => {
      const heading = node.querySelector('.pw-start-heading h2');
      const box = element => { const r = element.getBoundingClientRect(); return { left: r.left, right: r.right, top: r.top, bottom: r.bottom, width: r.width, height: r.height }; };
      const shortcuts = [...node.querySelectorAll('.pw-chat-start button')].map(shortcut => {
        const target = box(shortcut), hit = document.elementFromPoint(target.left + target.width / 2, target.top + target.height / 2);
        return { name: shortcut.textContent.trim(), ...target, receivesPointer: !!hit && (hit === shortcut || shortcut.contains(hit)) };
      });
      return { heading: box(heading), shortcuts, viewportWidth: innerWidth, viewportHeight: innerHeight,
        documentWidth: document.documentElement.scrollWidth };
    });
    assert.ok(geometry.documentWidth <= width + 1, 'Empty main must not overflow horizontally');
    for (const target of [{ name: 'heading', ...geometry.heading }, ...geometry.shortcuts]) {
      assert.ok(target.left >= -1 && target.right <= width + 1 && target.top >= 0 && target.bottom <= geometry.viewportHeight + 1,
        `Empty main ${target.name} must remain fully visible`);
    }
    for (const shortcut of geometry.shortcuts) {
      assert.ok(geometry.heading.bottom <= shortcut.top + 1, 'The empty-state shortcuts must stay below the heading');
      assert.equal(shortcut.receivesPointer, true, `Empty main shortcut is covered: ${shortcut.name}`);
      if (configured) assert.ok(shortcut.height >= 44 && shortcut.width >= 44, `Empty main shortcut lost its touch target: ${shortcut.name}`);
    }
    const file = `${scene}.png`;
    await page.mouse.move(0, 0);
    await page.screenshot({ path: path.join(output, file), animations: 'disabled' });
    record.screenshots.push({ scene, file, synthetic: true });
    const fixture = await page.evaluate(() => window.aiCenterFixture.summary());
    assert.deepEqual(fixture.errors, []);
    assert.equal(fixture.connections.length, configured ? 2 : 0);
    assert.deepEqual(externalRequests, []);
    passed(scene, 'Production empty main keeps its heading and shortcuts visible with a blank composer', { configured, ...geometry });
    record.emptyScenes.push({ scene, passed: true, configured, fixture });
  } catch (error) {
    record.checks.push({ scene, name: 'Empty main', passed: false, error: String(error.stack || error) });
    const file = `${scene}-failure.png`;
    try { await page.screenshot({ path: path.join(output, file), animations: 'disabled' }); record.screenshots.push({ scene, file, synthetic: true, failure: true }); } catch {}
    record.emptyScenes.push({ scene, passed: false });
  } finally { await context.close(); }
}

try {
  const index = await fs.readFile(path.join(bundle, 'index.html'), 'utf8');
  const buildIdentity = JSON.parse(await fs.readFile(path.join(bundle, 'build-identity.json'), 'utf8'));
  record.sourceRevision = buildIdentity.sourceRevision;
  if (process.env.GITHUB_SHA) assert.equal(record.sourceRevision, process.env.GITHUB_SHA, 'Screenshot bundle must belong to this workflow commit');
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
  for (const width of [320, 390]) await runEmptyMainScene(origin, width);
} catch (error) {
  record.errors.push({ stage: browser ? 'runner' : 'setup', error: String(error.stack || error) });
} finally {
  if (browser) await browser.close();
  if (server?.listening) await new Promise(resolve => server.close(resolve));
  record.finishedAt = new Date().toISOString();
  record.passed = record.outcome === 'executed' && record.scenes.length === matrix.length && record.scenes.every(scene => scene.passed)
    && record.emptyScenes.length === 2 && record.emptyScenes.every(scene => scene.passed) && !record.errors.length;
  await fs.writeFile(path.join(output, 'results.json'), JSON.stringify(record, null, 2) + '\n');
}
console.log(JSON.stringify(record));
if (!record.passed) process.exitCode = 1;
