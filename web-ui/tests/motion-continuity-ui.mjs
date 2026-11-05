/**
 * Production Web bundle + local Chromium + an isolated synthetic PocketNative.
 * UI motion and state-continuity evidence only: no Android/WebView, device,
 * native execution, OS background lifecycle, IME or Actions visual-gate claim.
 * The fixture labels screenshots and refuses unmodeled native authorities.
 *
 * PLAYWRIGHT_BROWSERS_PATH=/path/to/browser-cache node tests/motion-continuity-ui.mjs --baseline
 * MOTION_UI_EVIDENCE=/path/to/evidence node tests/motion-continuity-ui.mjs
 */
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import fs from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import { chromium } from '@playwright/test';

const repo = path.resolve(import.meta.dirname, '../..');
const baseline = process.argv.includes('--baseline');
const demo = process.argv.includes('--record-demo');
const reducedStartupOnly = process.argv.includes('--reduced-startup');
assert([baseline, demo, reducedStartupOnly].filter(Boolean).length <= 1, 'Choose one capture or focused verification mode');
const bundle = path.resolve(process.env.MOTION_UI_BUNDLE || path.join(repo, 'app/src/main/assets/pocket-ui'));
const output = path.resolve(process.env.MOTION_UI_EVIDENCE || path.join(repo, '../motion-evidence', baseline ? 'baseline' : demo ? 'demo' : reducedStartupOnly ? 'startup-reduced' : 'current'));
const digest = value => createHash('sha256').update(value).digest('hex');
const cases = [
  { name: 'phone-light', width: 390, height: 844, dark: false },
  { name: 'desktop-dark', width: 1280, height: 900, dark: true },
].filter(viewport => (!(demo || reducedStartupOnly) || viewport.name === 'phone-light') && (!process.env.MOTION_UI_VIEWPORT || viewport.name === process.env.MOTION_UI_VIEWPORT));
assert(cases.length, 'MOTION_UI_VIEWPORT must select a defined viewport');
const draft = '等我补充资料后，再一起确认。';
const readingText = [
  '## 把明天安排妥当',
  '我们先把已经确定的事放在一起，再给还没决定的部分留一点余地。',
  ...Array.from({ length: 8 }, (_, i) => `### ${i + 1}. 留出从容的时间\n\n这是一段用于检验阅读连续性的合成材料。出发时间、路上安排和要带的东西各自记清楚；打开别的工作再回来，还能从刚才看到的地方继续。`),
  '### 接下来可以一起确认',
  '- 哪些事情有固定时间？\n- 哪些资料还需要补充？\n- 有没有想留给自己的空闲？',
].join('\n\n');

function fixtureSnapshot(dark) {
  const conversationId = 'motion-conversation-a';
  return {
    state: {
      conversationId,
      conversations: [
        { id: conversationId, title: '把明天安排妥当', updatedAt: 1793876400000 },
        { id: 'motion-conversation-b', title: '周末的小旅行', updatedAt: 1793869200000 },
      ],
      messages: [
        { id: 'motion-user-a', role: 'user', content: '先帮我梳理一下明天要做的事。', at: 1793876400000 },
        { id: 'motion-answer-a', role: 'assistant', content: readingText, sourceRevision: 1, at: 1793876401000 },
        { id: 'motion-user-b', role: 'user', content: '好，先整理明天的安排，资料我晚点补上。', at: 1793876460000 },
        { id: 'motion-answer-b', role: 'assistant', content: '好，先留着这份安排。你补充资料后，我们再一起确认时间和需要准备的东西。\n\n不着急，一件一件来。', sourceRevision: 1, at: 1793876461000 },
      ],
      tasks: [
        { id: 'motion-task-running', goal: '整理周五的出行资料', status: 'running', summary: '正在整理已经提供的材料', updatedAt: 3, raw: { id: 'motion-task-running', kind: 'assistant', actorId: 'owner', spaceId: 'private', runId: 'fixture-task-run', coordinatorEpoch: 1 } },
        { id: 'motion-task-paused', goal: '核对下周需要准备的东西', status: 'paused', summary: '等你补充出发时间', updatedAt: 2, raw: { id: 'motion-task-paused', kind: 'assistant', actorId: 'owner', spaceId: 'private' } },
        { id: 'motion-task-complete', goal: '收好今天的阅读笔记', status: 'completed', summary: '已整理', updatedAt: 1, raw: { id: 'motion-task-complete', kind: 'assistant', actorId: 'owner', spaceId: 'private' } },
      ],
      modelName: '当前模型', modelRoute: 'api', modelProvider: 'openai_chat',
      appearance: { dark, mode: dark ? 'dark' : 'light' },
      foregroundTurn: null, chatActiveTurn: null, chatBusy: false, chatSubmitting: false,
      projects: [], files: [], features: {}, ownerWork: [], ownerSnapshots: {}, ownerResponses: {}, ownerPending: [], ownerErrors: {},
      moduleResults: {}, moduleBusy: [], moduleErrors: {}, taskActionErrors: {}, selectedChatAttachments: [], chatReceipts: {}, modelDrafts: {},
    },
    composer: { key: 'motion-fixture.a', epoch: 1, revision: 1, ready: true, text: '', sourceRefs: [], previewRefs: [], pending: [], conversationId },
    phoneDraft: { epoch: 1, revision: 1, text: '', phone: false, mode: 'background' },
    host: { connected: true, ready: true },
    view: { section: null },
  };
}

function installFixture(initial) {
  const clone = value => JSON.parse(JSON.stringify(value));
  let snapshot = clone(initial), revision = 0, documentId = '';
  const token = 'local-motion-ui-fixture-document';
  const commands = ['app.ready', 'app.refresh', 'composer.edit', 'composer.reading', 'composer.intent', 'conversation.select', 'conversation.new', 'chat.cancel', 'tasks.refresh', 'phone.draft.edit', 'phone.prepare', 'phone.start', 'voice.review', 'voice.accept', 'voice.discard'];
  const requests = [], violations = [];
  let phoneReview = null, phoneReviewSerial = 0, voiceReview = null, voiceReviewSerial = 0, unloadedHistory = null;
  const conversations = new Map();
  conversations.set('motion-conversation-b', {
    messages: [{ id: 'motion-other-user', role: 'user', content: '周末想去走走。' }, { id: 'motion-other-answer', role: 'assistant', content: '可以先看看你想去哪里，再慢慢安排。', sourceRevision: 1 }],
    composer: { ...clone(snapshot.composer), key: 'motion-fixture.b', text: '第二份工作有自己的草稿', conversationId: 'motion-conversation-b' },
  });
  window.__pocketDocumentToken = token;
  const publish = () => {
    snapshot.capabilities = { commands };
    window.PocketWeb.receive({ v: 1, kind: 'state', revision: ++revision, snapshot: clone(snapshot) });
  };
  const setBusy = busy => {
    snapshot.state.foregroundTurn = busy ? { conversationId: snapshot.state.conversationId, taskId: 'motion-current-reply', runId: 'motion-reply-run', epoch: 1, executionRole: 'foreground', resourceState: 'running' } : null;
    snapshot.state.chatActiveTurn = snapshot.state.foregroundTurn;
    snapshot.state.chatBusy = busy;
  };
  const switchConversation = id => {
    if (id === snapshot.state.conversationId) return;
    conversations.set(snapshot.state.conversationId, { messages: clone(snapshot.state.messages), composer: clone(snapshot.composer) });
    const next = conversations.get(id);
    if (!next) throw new Error('Unmodeled conversation identity');
    snapshot.state.conversationId = id;
    snapshot.state.messages = clone(next.messages);
    snapshot.composer = { ...clone(next.composer), epoch: next.composer.epoch + 1 };
    setBusy(false);
  };
  window.PocketNative = { postMessage(raw) {
    queueMicrotask(() => {
      const request = JSON.parse(raw);
      try {
        if (request.v !== 1 || !request.id || request.hostDocumentToken !== token) throw new Error('Invalid document-bound fixture request');
        if (request.action === 'app.ready' && !documentId) documentId = request.documentId;
        if (!documentId || request.documentId !== documentId || !commands.includes(request.action)) throw new Error('Unexpected fixture authority: ' + request.action);
        requests.push({ action: request.action, args: clone(request.args) });
        let value = { accepted: true };
        if (request.action === 'composer.edit') {
          if (request.args.key !== snapshot.composer.key || request.args.epoch !== snapshot.composer.epoch) throw new Error('Composer identity changed');
          snapshot.composer.text = request.args.text;
          snapshot.composer.revision++;
        }
        if (request.action === 'composer.reading') {
          const owner = request.args.key === snapshot.composer.key ? snapshot.composer : [...conversations.values()].find(value => value.composer.key === request.args.key)?.composer;
          if (!owner) throw new Error('Reading has no original draft identity');
          owner.scrollIndex = request.args.index;
          owner.scrollOffset = request.args.offset;
        }
        if (request.action === 'conversation.select') switchConversation(request.args.id);
        if (request.action === 'conversation.new') throw new Error('New conversation is outside this test journey');
        if (request.action === 'composer.intent') {
          if (typeof request.args.phone !== 'boolean') throw new Error('Unmodeled input intent');
          snapshot.phoneDraft.phone = request.args.phone;
          if (request.args.mode) snapshot.phoneDraft.mode = request.args.mode;
          snapshot.composer.epoch++;
        }
        if (request.action === 'phone.draft.edit') {
          if (request.args.epoch !== snapshot.phoneDraft.epoch) throw new Error('Phone draft epoch changed');
          snapshot.phoneDraft.text = request.args.text; snapshot.phoneDraft.revision++;
        }
        if (request.action === 'phone.prepare') {
          if (request.args.epoch !== snapshot.phoneDraft.epoch || request.args.goal !== snapshot.phoneDraft.text || request.args.mode !== snapshot.phoneDraft.mode) throw new Error('Phone review lost its original draft');
          phoneReview = { reviewHandle: 'motion-phone-review-' + (++phoneReviewSerial), requestId: 'motion-phone-request-' + phoneReviewSerial,
            conversationId: snapshot.state.conversationId, conversationTitle: '把明天安排妥当', goal: request.args.goal, mode: request.args.mode,
            modelName: '本次固定模型', provider: 'openai_chat', toolLimit: 40, modelCallLimit: 64, scopeMinutes: 30,
            screenDisclosure: '执行时，屏幕与可见文字会发送到本次固定的模型。', message: '在后台独立屏处理这个目标；连接不可用时等待。' };
          value = clone(phoneReview);
        }
        if (request.action === 'phone.start') {
          if (!phoneReview || request.args.reviewHandle !== phoneReview.reviewHandle || Object.keys(request.args).length !== 1) throw new Error('Phone start lost its reviewed identity');
          throw new Error('Closing-only fixture must never start a phone task');
        }
        if (request.action === 'voice.review') {
          if (snapshot.state.voiceInput?.reviewAvailable !== true || request.args.phone !== snapshot.phoneDraft.phone) throw new Error('No current voice review for this original input');
          voiceReview = { reviewHandle: 'motion-voice-review-' + (++voiceReviewSerial), text: '这段听写先放进草稿，核对之后再发送。', phone: request.args.phone };
          value = clone(voiceReview);
        }
        if (request.action === 'voice.accept' || request.action === 'voice.discard') {
          if (!voiceReview || request.args.reviewHandle !== voiceReview.reviewHandle) throw new Error('Voice action lost its original review');
          if (request.action === 'voice.accept') { snapshot.composer.text = request.args.text; snapshot.composer.revision++; }
          snapshot.state.voiceInput = { reviewAvailable: false };
          voiceReview = null;
        }
        if (request.action === 'chat.cancel') {
          const original = snapshot.state.foregroundTurn;
          if (!original || ['conversationId', 'taskId', 'runId', 'epoch'].some(key => request.args[key] !== original[key])) throw new Error('Stop did not target the original foreground turn');
          setBusy(false);
        }
        window.PocketWeb.receive({ v: 1, kind: 'result', id: request.id, ok: true, value });
        if (request.action !== 'composer.reading') publish();
      } catch (error) {
        violations.push(String(error.message));
        window.PocketWeb.receive({ v: 1, kind: 'result', id: request.id, ok: false, error: String(error.message) });
      }
    });
  } };
  window.motionFixture = {
    setBusy(value) { setBusy(value); publish(); },
    stream(id, text) {
      const row = snapshot.state.messages.find(message => message.id === id);
      if (!row) throw new Error('Cannot stream an absent message');
      row.content += text; row.sourceRevision = Number(row.sourceRevision || 0) + 1; publish();
    },
    append(id, content) {
      if (snapshot.state.messages.some(message => message.id === id)) throw new Error('Duplicate fixture message');
      snapshot.state.messages.push({ id, role: 'assistant', content, sourceRevision: 1 }); publish();
    },
    prepend(id) {
      if (snapshot.state.messages.some(message => message.id === id)) throw new Error('Duplicate history message');
      snapshot.state.messages.unshift({ id, role: 'assistant', content: '较早的记录：这段已经读过的内容不应再次进场。', sourceRevision: 1 }); publish();
    },
    unloadHistory() {
      if (unloadedHistory) throw new Error('History already unloaded');
      unloadedHistory = clone(snapshot.state.messages); snapshot.state.messages = []; publish();
    },
    restoreHistory() {
      if (!unloadedHistory || snapshot.state.messages.length) throw new Error('History cannot be restored over live messages');
      snapshot.state.messages = unloadedHistory; unloadedHistory = null; publish();
    },
    setAppearance(dark) { snapshot.state.appearance = { dark, mode: dark ? 'dark' : 'light' }; publish(); },
    setVoiceAvailable(value) { snapshot.state.voiceInput = { reviewAvailable: value }; publish(); },
    setDocumentHidden(hidden) {
      Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });
      Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => hidden ? 'hidden' : 'visible' });
      document.dispatchEvent(new Event('visibilitychange'));
    },
    inspect() { return { revision, requests: clone(requests), violations: [...violations], snapshot: clone(snapshot) }; },
  };
  document.addEventListener('DOMContentLoaded', () => {
    const label = document.createElement('div');
    label.textContent = 'WEB UI · SYNTHETIC FIXTURE'; label.setAttribute('aria-hidden', 'true');
    Object.assign(label.style, { position: 'fixed', bottom: '0', right: '0', zIndex: '2147483647', padding: '2px 6px', pointerEvents: 'none', background: '#eeeeee', color: '#555555', font: '10px/14px sans-serif' });
    document.body.append(label);
  }, { once: true });
}

// Observe the actual Animation objects returned by production code. This never
// creates, retimes or replaces a product animation. Frames are live DOM samples.
function installMotionObserver() {
  const original = Element.prototype.animate;
  const calls = [];
  Element.prototype.animate = function (...args) {
    const animation = original.apply(this, args);
    const call = { serial: calls.length + 1, animation, element: this, createdAt: performance.now(), frames: [] };
    calls.push(call);
    const sample = () => {
      if (/^pw-(surface|message)-enter$/.test(animation.id) && this.isConnected) {
        const style = getComputedStyle(this), rect = this.getBoundingClientRect();
        call.frames.push({ time: performance.now(), currentTime: animation.currentTime, playState: animation.playState, opacity: style.opacity, transform: style.transform, x: rect.x, y: rect.y, width: rect.width, height: rect.height });
      }
      if (performance.now() - call.createdAt < 1200 && ['running', 'pending'].includes(animation.playState)) requestAnimationFrame(sample);
    };
    requestAnimationFrame(sample);
    return animation;
  };
  window.motionEvidence = {
    checkpoint() { return calls.length; },
    read(since = 0) {
      return calls.filter(call => call.serial > since && /^pw-(surface|message)-enter$/.test(call.animation.id)).map(call => ({
        serial: call.serial, id: call.animation.id, className: call.element.getAttribute('class'),
        messageId: call.element.closest('[data-message-id]')?.getAttribute('data-message-id') || null,
        createdAt: call.createdAt, connected: call.element.isConnected, playState: call.animation.playState,
        keyframes: call.animation.effect?.getKeyframes(), timing: call.animation.effect?.getTiming(), frames: [...call.frames],
      }));
    },
  };
}

const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.ttf': 'font/ttf', '.woff2': 'font/woff2', '.svg': 'image/svg+xml', '.png': 'image/png', '.txt': 'text/plain' };
await fs.mkdir(output, { recursive: true });
const identityBytes = await fs.readFile(path.join(bundle, 'build-identity.json'));
const identity = JSON.parse(identityBytes);
const manifest = JSON.parse(await fs.readFile(path.join(bundle, 'manifest.json'), 'utf8'));
const entry = manifest['index.html'];
const assets = await Promise.all([entry.file, ...(entry.css || [])].map(async file => ({ file, sha256: digest(await fs.readFile(path.join(bundle, file))) })));
const report = {
  kind: 'local-production-web-motion-continuity', baseline, demo, reducedStartupOnly, startedAt: new Date().toISOString(), bundle,
  scope: 'Production Web bundle + local Chromium + synthetic PocketNative; not Android/device/native execution or Actions validation.',
  limitations: ['Visibility is a synthetic document lifecycle event; OS background suspension is not exercised.', 'This test does not establish physical touch, Android IME, power or device frame-rate acceptance.'],
  sourceRevision: identity.sourceRevision, buildIdentitySha256: digest(identityBytes), indexSha256: digest(await fs.readFile(path.join(bundle, 'index.html'))),
  workingTreeDirty: process.env.MOTION_UI_WORKING_TREE_DIRTY ? process.env.MOTION_UI_WORKING_TREE_DIRTY === 'true' : !baseline,
  sourceIdentityNote: baseline ? 'Existing production baseline captured before the motion changes.' : 'Working diff bundle; sourceRevision is the receipt base, not a claim that these rendered changes are committed at that revision.',
  assets,
  browser: '', node: process.version, checks: [], captures: [], evidence: [], errors: [],
};
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
const browser = await chromium.launch({ headless: true, args: ['--disable-background-networking'] });
report.browser = browser.version();
let currentPage, currentCase;
const settle = page => page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
async function capture(page, name, note = '') {
  const file = `${currentCase.name}-${name}.png`;
  await page.screenshot({ path: path.join(output, file) });
  report.captures.push({ file, viewport: currentCase, note, capturedAt: new Date().toISOString() });
}
async function check(name, work) {
  try { await work(); report.checks.push({ name, viewport: currentCase.name, passed: true }); }
  catch (error) { report.checks.push({ name, viewport: currentCase.name, passed: false, error: String(error.stack || error) }); throw error; }
}
const primaryNav = page => page.getByRole('navigation', { name: '主导航', exact: true });
async function openNavigation(page) {
  if (currentCase.width < 1000) await page.getByRole('button', { name: '打开导航', exact: true }).click();
  await primaryNav(page).waitFor({ state: 'visible' });
}
async function navigate(page, route, label) {
  await openNavigation(page);
  await primaryNav(page).getByRole('link', { name: label, exact: true }).click();
  await page.waitForFunction(expected => document.querySelector('.pw-app')?.getAttribute('data-route') === expected, route);
}

async function settleEnters(page) {
  await settle(page);
  await page.waitForFunction(() => !document.getAnimations().some(animation => /^pw-(surface|message)-enter$/.test(animation.id) && ['running', 'paused'].includes(animation.playState)));
  await settle(page);
}
const checkpoint = page => page.evaluate(() => window.motionEvidence.checkpoint());
const observed = (page, since = 0) => page.evaluate(value => window.motionEvidence.read(value), since);
const arrivals = records => records.filter(record => record.id === 'pw-message-enter');
const remember = (name, value) => report.evidence.push({ name, viewport: currentCase.name, ...value });
function assertVisibleAnimation(records, id) {
  const relevant = records.filter(record => record.id === id);
  assert(relevant.length > 0, `No production ${id} animation was observed`);
  const frames = relevant.flatMap(record => record.frames);
  assert(frames.length >= 2, `${id} has no observable intermediate frames`);
  assert(new Set(frames.map(frame => `${frame.opacity}:${frame.transform}`)).size > 1, `${id} never changed its rendered opacity or transform`);
  assert(frames.some(frame => Number(frame.opacity) > 0 && Number(frame.opacity) < 1 || frame.transform !== 'none' && frame.transform !== 'matrix(1, 0, 0, 1, 0, 0)'), `${id} has no rendered intermediate state`);
}
async function sampleDuring(page, name, selector, action, duration = 650) {
  await page.evaluate(({ name, selector, duration }) => {
    const samples = [], started = performance.now();
    window.motionSamples ||= {};
    window.motionSamples[name] = { samples, done: false };
    const sample = () => {
      const node = document.querySelector(selector);
      if (node) {
        const rect = node.getBoundingClientRect(), style = getComputedStyle(node);
        samples.push({ elapsed: performance.now() - started, x: rect.x, y: rect.y, width: rect.width, height: rect.height,
          opacity: style.opacity, transform: style.transform, gridTemplateRows: style.gridTemplateRows,
          open: node.getAttribute('data-open'), inert: node.inert === true });
      }
      if (performance.now() - started < duration) requestAnimationFrame(sample);
      else window.motionSamples[name].done = true;
    };
    sample();
  }, { name, selector, duration });
  await action();
  await page.waitForFunction(name => window.motionSamples[name]?.done, name);
  const samples = await page.evaluate(name => window.motionSamples[name].samples, name);
  remember(name, { selector, samples });
  return samples;
}
async function captureObservedMidpoint(page, name, match) {
  // Freeze a naturally reached middle frame solely for the screenshot, without
  // seeking to a fabricated currentTime. The original animation resumes after it.
  await page.waitForFunction(match => {
    const animation = document.getAnimations().find(candidate => {
      if (candidate.playState !== 'running') return false;
      if (match.id ? candidate.id !== match.id : !candidate.effect?.target?.matches?.(match.selector)) return false;
      const timing = candidate.effect?.getComputedTiming();
      return timing?.progress > 0.45 && timing.progress < 0.9;
    });
    if (!animation) return false;
    const target = animation.effect.target, rect = target.getBoundingClientRect(), style = getComputedStyle(target);
    window.motionMidpoint = { animation, observed: { id: animation.id, kind: animation.constructor.name, animationName: animation.animationName,
      transitionProperty: animation.transitionProperty, currentTime: animation.currentTime, progress: animation.effect.getComputedTiming().progress,
      transform: style.transform, opacity: style.opacity, height: rect.height, x: rect.x, y: rect.y } };
    animation.pause();
    return true;
  }, match, { polling: 'raf', timeout: 2500 });
  try {
    remember(name, { midpoint: await page.evaluate(() => window.motionMidpoint.observed), captureMethod: 'Paused at naturally observed intermediate progress; no seek or style injection.' });
    await capture(page, name, 'Naturally observed middle frame temporarily paused for capture, then resumed.');
  } finally {
    await page.evaluate(() => { window.motionMidpoint.animation.play(); delete window.motionMidpoint; });
  }
}
async function cssAnimations(page) {
  return page.evaluate(() => document.getAnimations().filter(animation => animation instanceof CSSAnimation).map(animation => ({
    name: animation.animationName, playState: animation.playState, currentTime: animation.currentTime,
    iterations: animation.effect.getTiming().iterations === Infinity ? 'infinite' : animation.effect.getTiming().iterations,
    duration: animation.effect.getTiming().duration, target: animation.effect.target?.getAttribute('class'),
    tag: animation.effect.target?.tagName, pseudoElement: animation.effect.pseudoElement,
    computedDuration: animation.effect.target ? getComputedStyle(animation.effect.target, animation.effect.pseudoElement || undefined).animationDuration : null,
    computedIterations: animation.effect.target ? getComputedStyle(animation.effect.target, animation.effect.pseudoElement || undefined).animationIterationCount : null,
    inStop: Boolean(animation.effect.target?.closest('.pw-stop-motion')),
  })));
}
async function waitForOpenSheet(page, description) {
  const sheet = page.locator(`[aria-describedby="${description}"]`);
  await sheet.waitFor();
  await page.waitForFunction(description => {
    const node = document.querySelector(`[aria-describedby="${description}"]`);
    return node && !node.inert && node.getAttribute('data-state') === 'open' && node.getBoundingClientRect().bottom <= innerHeight + 1;
  }, description);
  return sheet;
}
async function closeSheetObservation(page, description, closeName, requiredText, evidenceName) {
  const sheet = page.locator(`[aria-describedby="${description}"]`), retained = await sheet.elementHandle();
  const openBox = await sheet.boundingBox();
  await sheet.getByRole('button', { name: closeName, exact: true }).evaluate(button => {
    button.addEventListener('click', () => { window.sheetClosedAt = performance.now(); }, { once: true });
  });
  await sheet.getByRole('button', { name: closeName, exact: true }).click();
  // This is the specified 100 ms exit-lifetime assertion, not an arbitrary wait
  // for a finished operation. Capture the retained node even while it is inert.
  await page.waitForFunction(() => performance.now() - window.sheetClosedAt >= 100, undefined, { polling: 'raf' });
  const state = await retained.evaluate(node => ({ connected: node.isConnected, inert: node.inert, text: node.textContent,
    elapsed: performance.now() - window.sheetClosedAt, rect: node.getBoundingClientRect().toJSON(),
    readonly: node.querySelector('textarea')?.readOnly,
    animations: document.getAnimations().filter(animation => animation.effect?.target === node).map(animation => ({ kind: animation.constructor.name, name: animation.animationName, playState: animation.playState, currentTime: animation.currentTime })) }));
  assert.equal(state.connected, true, 'The closing sheet unmounted before its visible exit interval');
  assert.equal(state.inert, true, 'The closing sheet still has live interactive authority');
  for (const text of requiredText) assert(state.text.includes(text), `The closing sheet lost original display text: ${text}`);
  assert(state.rect.y > openBox.y + 1, 'The retained sheet did not visibly move through its exit');
  remember(evidenceName, { openBox, after100ms: state });
  await capture(page, evidenceName, 'Natural exit after at least 100 ms: original display remains, content is inert.');
  return retained;
}
async function selectInputIntent(page, label) {
  await page.getByRole('button', { name: '输入方式与选项', exact: true }).click();
  await page.getByText(label, { exact: true }).click();
  await settle(page);
}

async function checkReviewExits(page) {
  const input = page.getByRole('textbox', { name: '工作输入', exact: true });
  const phoneSelector = '[aria-describedby="pw-phone-description"]';
  const voiceSelector = '[aria-describedby="pw-voice-description"]';
  const phoneGoal = '核对明天上午九点的日历安排';
  const phoneFields = ['核对这次手机任务', phoneGoal, '本次固定模型', '40', '64', '30 分钟'];

  await check('phone-review-keeps-display-but-removes-authority-through-natural-exit', async () => {
    await selectInputIntent(page, '手机后台独立屏');
    await input.fill(phoneGoal);
    await page.waitForFunction(text => window.motionFixture.inspect().snapshot.phoneDraft.text === text, phoneGoal);
    await page.getByRole('button', { name: '发送', exact: true }).click();
    await waitForOpenSheet(page, 'pw-phone-description');
    await capture(page, 'phone-review-open', 'Synthetic fixed phone scope; this test never starts device work.');
    const retained = await closeSheetObservation(page, 'pw-phone-description', '关闭手机任务核对', phoneFields, 'phone-review-closing');
    // Deliberately probe a stale rendered callback as well as browser inertness.
    // This is an adversarial DOM activation, not a claimed physical user click.
    const stale = await retained.evaluate(node => {
      const button = [...node.querySelectorAll('button')].find(button => button.textContent.includes('开始这次任务'));
      if (!button) return { found: false };
      button.focus(); const focused = document.activeElement === button; button.click();
      return { found: true, focused };
    });
    assert.equal(stale.found, true); assert.equal(stale.focused, false, 'Closing scope still accepts focus');
    await settle(page);
    assert.equal(await page.evaluate(() => window.motionFixture.inspect().requests.filter(request => request.action === 'phone.start').length), 0, 'A stale closing-scope callback started work');
    await page.locator(phoneSelector).waitFor({ state: 'detached' });
    assert.equal(await input.inputValue(), phoneGoal, 'Closing review consumed its original phone draft');
  });

  await check('phone-review-reentry-and-hidden-or-reduced-exit-cleanup', async () => {
    const send = await page.getByRole('button', { name: '发送', exact: true }).elementHandle();
    await page.getByRole('button', { name: '发送', exact: true }).click();
    const sheet = await waitForOpenSheet(page, 'pw-phone-description');
    const prepares = await page.evaluate(() => window.motionFixture.inspect().requests.filter(request => request.action === 'phone.prepare').length);
    await sheet.getByRole('button', { name: '关闭手机任务核对', exact: true }).click();
    // Exercise a queued activation of the real, already-rendered send control
    // before the old portal finishes exiting. Pointer journeys above stay native.
    await send.evaluate(button => button.click());
    await page.waitForFunction(expected => window.motionFixture.inspect().requests.filter(request => request.action === 'phone.prepare').length === expected, prepares + 1);
    await waitForOpenSheet(page, 'pw-phone-description');
    const reentry = await sampleDuring(page, 'phone-review-reentry-frames', phoneSelector, async () => {}, 650);
    assert(reentry.every(frame => !frame.inert && frame.height > 0), 'The old exit cleanup invalidated a newly opened review');
    for (const text of phoneFields) assert((await page.locator(phoneSelector).textContent()).includes(text));
    await page.evaluate(() => window.motionFixture.setDocumentHidden(true));
    await page.waitForFunction(() => document.documentElement.dataset.pwMotionPaused === 'true');
    await closeSheetObservation(page, 'pw-phone-description', '关闭手机任务核对', phoneFields, 'phone-review-hidden-exit');
    await page.locator(phoneSelector).waitFor({ state: 'detached', timeout: 2500 });
    remember('finite-exit-survives-background-pause', { elapsed: await page.evaluate(() => performance.now() - window.sheetClosedAt), hidden: true });
    await page.evaluate(() => window.motionFixture.setDocumentHidden(false));
    await page.getByRole('button', { name: '发送', exact: true }).click();
    await waitForOpenSheet(page, 'pw-phone-description');
    await page.locator(phoneSelector).getByRole('button', { name: '关闭手机任务核对', exact: true }).click();
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.locator(phoneSelector).waitFor({ state: 'detached', timeout: 2500 });
    await page.emulateMedia({ reducedMotion: 'no-preference' });
    await settle(page);
    assert.equal(await page.evaluate(() => window.motionFixture.inspect().requests.filter(request => request.action === 'phone.start').length), 0);
    await selectInputIntent(page, '与主 Agent 对话');
    assert.equal(await input.inputValue(), draft, 'Phone reviews changed the separate chat draft');
  });

  await check('voice-review-exit-retains-text-and-cannot-apply-a-stale-review', async () => {
    await page.evaluate(() => window.motionFixture.setVoiceAvailable(true));
    await page.getByRole('button', { name: '核对原听写', exact: true }).click();
    const sheet = await waitForOpenSheet(page, 'pw-voice-description');
    const text = await sheet.getByRole('textbox', { name: '原听写文字', exact: true }).inputValue();
    const retained = await closeSheetObservation(page, 'pw-voice-description', '关闭听写核对', ['核对听写文字', '采用后放入原输入，暂不发送'], 'voice-review-closing');
    const closed = await retained.evaluate(node => {
      const text = node.querySelector('textarea');
      const action = [...node.querySelectorAll('button')].find(button => button.textContent.includes('采用到原输入'));
      action?.click();
      return { text: text?.value, readonly: text?.readOnly, disabled: action?.disabled };
    });
    assert.equal(closed.text, text, 'Voice exit erased the original dictated text');
    assert.equal(closed.readonly, true); assert.equal(closed.disabled, true);
    await page.locator(voiceSelector).waitFor({ state: 'detached' });
    const reopen = await page.getByRole('button', { name: '核对原听写', exact: true }).elementHandle();
    await page.getByRole('button', { name: '核对原听写', exact: true }).click();
    await waitForOpenSheet(page, 'pw-voice-description');
    await page.locator(voiceSelector).getByRole('button', { name: '关闭听写核对', exact: true }).click();
    await reopen.evaluate(button => button.click());
    await waitForOpenSheet(page, 'pw-voice-description');
    await sampleDuring(page, 'voice-review-reentry-frames', voiceSelector, async () => {}, 650);
    assert.equal(await page.locator(voiceSelector).getByRole('textbox', { name: '原听写文字', exact: true }).inputValue(), text);
    await page.locator(voiceSelector).getByRole('button', { name: '关闭听写核对', exact: true }).click();
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.locator(voiceSelector).waitFor({ state: 'detached', timeout: 2500 });
    await page.emulateMedia({ reducedMotion: 'no-preference' });
    await page.evaluate(() => window.motionFixture.setVoiceAvailable(false));
    await settle(page);
    assert.equal(await input.inputValue(), draft);
    assert.deepEqual(await page.evaluate(() => window.motionFixture.inspect().requests.filter(request => ['voice.accept', 'voice.discard'].includes(request.action))), [], 'Closing or reentering a voice review executed a stale action');
  });
}
async function assertOneSurface(page, route) {
  const surfaces = await page.locator('.pw-chat-workspace, .pw-tasks-page, .pw-projects-page').evaluateAll(nodes => nodes.map(node => ({ className: node.className, connected: node.isConnected })));
  assert.equal(surfaces.length, 1, 'A previous route left a second live product subtree');
  const expected = { chat: 'pw-chat-workspace', tasks: 'pw-tasks-page', projects: 'pw-projects-page' }[route];
  assert(surfaces[0].className.includes(expected), 'The live subtree is not the selected route');
  return surfaces;
}

async function runReducedStartup(page, initial = false) {
  await check('startup-reduced-preference-has-no-spatial-entry-or-infinite-loop', async () => {
    if (!initial) {
      await page.emulateMedia({ reducedMotion: 'reduce' });
      await page.reload();
      await page.getByRole('textbox', { name: '工作输入', exact: true }).waitFor();
      await page.evaluate(() => document.fonts.ready);
    }
    assert.equal(await page.evaluate(() => matchMedia('(prefers-reduced-motion: reduce)').matches), true);
    await settle(page);
    assert.deepEqual(await observed(page), [], 'Startup under reduced motion produced a spatial entry');
    await page.evaluate(() => window.motionFixture.setBusy(true));
    await page.getByRole('button', { name: '停止当前回复', exact: true }).waitFor();
    await settle(page);
    const animations = await cssAnimations(page);
    assert.equal(animations.filter(animation => animation.iterations === 'infinite' && animation.playState === 'running').length, 0, 'Startup reduced preference left an infinite activity loop');
    remember('startup-reduced-preference', { initialContext: initial, animations });
    await capture(page, 'startup-reduced-motion', 'Reduced-motion preference was present before this document and its MotionConfig mounted.');
  });
}

async function recordNaturalDemo(page) {
  // Deliberate reading holds are for the demonstration only. No animation is
  // paused, sought or fast-forwarded in this mode; all input targets are real UI.
  const input = page.getByRole('textbox', { name: '工作输入', exact: true });
  const hold = duration => page.waitForTimeout(duration);
  await input.fill('资料晚点补上，回来接着聊。');
  await page.locator('.pw-conversation-scroll').evaluate(node => { node.scrollTop = node.scrollHeight; });
  await hold(900);
  await openNavigation(page); await hold(900);
  await primaryNav(page).getByRole('link', { name: '任务', exact: true }).click(); await hold(650);
  const filter = page.getByRole('button', { name: '搜索与筛选任务', exact: true });
  await filter.click(); await hold(850);
  await page.locator('.pw-task-filters-reveal').getByText('进行中', { exact: true }).click(); await hold(650);
  await filter.click(); await hold(650);
  await openNavigation(page); await hold(600);
  await primaryNav(page).getByRole('link', { name: '对话', exact: true }).click(); await hold(800);
  await page.locator('.pw-conversation-scroll').evaluate(node => { node.scrollTop = node.scrollHeight; });
  await page.evaluate(() => window.motionFixture.setBusy(true)); await hold(550);
  await page.locator('.pw-conversation-scroll').hover();
  await page.mouse.wheel(0, 2000); await hold(300);
  await page.evaluate(() => window.motionFixture.append('motion-demo-reply', '我先帮你把安排整理好。')); await hold(700);
  await page.evaluate(() => window.motionFixture.stream('motion-demo-reply', '\n\n有新的资料时，再放到这里一起核对。')); await hold(800);
  await page.getByRole('button', { name: '停止当前回复', exact: true }).click(); await hold(700);
  await page.getByRole('button', { name: '输入方式与选项', exact: true }).click(); await hold(350);
  await page.getByText('手机后台独立屏', { exact: true }).click();
  await input.fill('核对明天上午九点的日历安排'); await hold(700);
  await page.getByRole('button', { name: '发送', exact: true }).click();
  await waitForOpenSheet(page, 'pw-phone-description'); await hold(1800);
  await page.getByRole('button', { name: '关闭手机任务核对', exact: true }).click(); await hold(1000);
  await page.getByRole('button', { name: '输入方式与选项', exact: true }).click(); await hold(350);
  await page.getByText('与主 Agent 对话', { exact: true }).click(); await hold(900);
  assert.equal(await input.inputValue(), '资料晚点补上，回来接着聊。');
  report.demonstration = { playback: 'Natural browser time; no animation pause, seek or time acceleration.', scenes: ['Side navigation and route entry', 'Filter reveal and collapse', 'New reply and same-id streaming', 'Foreground activity and stop', 'Phone scope review and complete natural exit', 'Return to the retained chat draft'] };
}

async function runMotionRegression(page) {
  const input = () => page.getByRole('textbox', { name: '工作输入', exact: true });
  const scroll = () => page.locator('.pw-conversation-scroll');
  let readingTop;
  await check('initial-history-and-background-work-do-not-animate-as-new-replies', async () => {
    await settleEnters(page);
    assert.equal(await page.locator('.pw-message').count(), 4);
    assert.deepEqual(arrivals(await observed(page)), [], 'Initial native history replayed message arrivals');
    assert.equal(await page.getByRole('button', { name: '停止当前回复', exact: true }).count(), 0, 'A background task acquired the foreground stop control');
    await input().fill(draft);
    await page.waitForFunction(value => window.motionFixture.inspect().snapshot.composer.text === value, draft);
    readingTop = await scroll().evaluate(node => { node.scrollTop = Math.min(430, (node.scrollHeight - node.clientHeight) / 2); return node.scrollTop; });
    assert(readingTop > 0, 'The reading fixture must genuinely scroll');
    await page.waitForFunction(() => window.motionFixture.inspect().requests.some(request => request.action === 'composer.reading'));
    await capture(page, 'chat', 'Current production UI with a retained draft and a real scroll offset.');
  });

  await check('navigation-background-travels-and-route-enters-on-one-live-subtree', async () => {
    const before = await checkpoint(page);
    if (currentCase.width >= 1000) {
      const selector = 'nav[aria-label="主导航"] a[aria-current="page"] .pw-nav-selection';
      const frames = await sampleDuring(page, 'navigation-spring-frames', selector, async () => {
        await primaryNav(page).getByRole('link', { name: '任务', exact: true }).click();
        await captureObservedMidpoint(page, 'route-enter-midpoint', { id: 'pw-surface-enter' });
      });
      const ys = frames.map(frame => frame.y), first = ys[0], last = ys.at(-1);
      assert(Math.abs(last - first) > 20, 'The navigation selection did not move between rows');
      assert(ys.some(y => Math.abs(y - first) > 0.75 && Math.abs(y - last) > 0.75), 'The selection jumped without an observable spring interval');
      const target = await primaryNav(page).getByRole('link', { name: '任务', exact: true }).boundingBox();
      assert(Math.abs(last - target.y) < 4, 'The moving selection did not settle on the selected row');
    } else {
      await openNavigation(page);
      assert.equal(await primaryNav(page).locator('a[aria-current="page"] .pw-nav-selection').count(), 1);
      await capture(page, 'navigation', 'Current side navigation, before selecting the task route.');
      await primaryNav(page).getByRole('link', { name: '任务', exact: true }).click();
    }
    await page.waitForFunction(() => document.querySelector('.pw-app')?.getAttribute('data-route') === 'tasks');
    await assertOneSurface(page, 'tasks');
    await settleEnters(page);
    const animations = await observed(page, before);
    assertVisibleAnimation(animations, 'pw-surface-enter');
    remember('route-entry-observation', { animations });
  });

  await check('task-filters-reveal-intermediate-height-retain-values-and-close-inert', async () => {
    const trigger = page.getByRole('button', { name: '搜索与筛选任务', exact: true });
    const reveal = page.locator('.pw-task-filters-reveal');
    assert.equal(await reveal.count(), 1, 'The filter reveal must remain mounted when closed');
    assert.equal(await reveal.evaluate(node => node.inert), true);
    const opening = await sampleDuring(page, 'filter-opening-frames', '.pw-task-filters-reveal', async () => {
      await trigger.click();
      await captureObservedMidpoint(page, 'task-filters-midpoint', { selector: '.pw-task-filters-reveal' });
    }, 800);
    const closedHeight = opening[0].height, openHeight = opening.at(-1).height;
    assert(openHeight - closedHeight > 40, 'Filters did not visibly expand');
    assert(opening.some(frame => frame.height > closedHeight + 1 && frame.height < openHeight - 1), 'Filter height jumped without an intermediate layout frame');
    assert.equal(await trigger.getAttribute('aria-expanded'), 'true');
    assert.equal(await reveal.evaluate(node => node.inert), false);
    const search = page.getByRole('searchbox', { name: '搜索已加载任务', exact: true });
    await search.fill('出行');
    // React Aria's visually hidden native radio is covered by its visible label.
    // Use that actual pointer target, then verify the native checked state.
    await reveal.getByText('进行中', { exact: true }).click();
    assert.equal(await page.locator('.pw-task-results .pw-task-row').count(), 1);
    const retainedSearch = await search.elementHandle();
    await trigger.click();
    assert.equal(await reveal.evaluate(node => node.inert), true, 'Closing filters must become inert immediately');
    assert.equal(await retainedSearch.evaluate(node => { node.focus(); return document.activeElement === node; }), false, 'A closed search control still accepts focus');
    for (let i = 0; i < 3; i++) {
      await page.keyboard.press('Tab');
      assert.equal(await reveal.evaluate(node => node.contains(document.activeElement)), false, 'Keyboard focus entered closed filters');
    }
    await page.waitForFunction(() => document.querySelector('.pw-task-filters-reveal').getBoundingClientRect().height < 1);
    const repeats = await sampleDuring(page, 'filter-interrupted-reversal-frames', '.pw-task-filters-reveal', async () => {
      for (let i = 0; i < 5; i++) await trigger.click({ noWaitAfter: true });
    });
    assert(repeats.some(frame => frame.height > 1), 'Repeated opening did not reveal filters');
    assert.equal(await trigger.getAttribute('aria-expanded'), 'true');
    assert.equal(await search.inputValue(), '出行', 'Disclosure lost its search text');
    assert.equal(await page.getByRole('radio', { name: '进行中', exact: true }).isChecked(), true, 'Disclosure lost its selected filter');
    await capture(page, 'task-filters-open', 'Repeated open/close preserves the task search and selection.');
    await trigger.click();
    assert.equal(await reveal.evaluate(node => node.inert), true);
    await page.getByRole('button', { name: '清除任务筛选', exact: true }).click();
  });

  await check('rapid-real-navigation-cancels-old-motion-with-draft-and-reading-intact', async () => {
    const before = await checkpoint(page), states = [];
    for (const [route, label] of [['projects', '项目'], ['chat', '对话'], ['tasks', '任务'], ['projects', '项目'], ['chat', '对话']]) {
      await navigate(page, route, label);
      states.push({ route, surfaces: await assertOneSurface(page, route), running: await page.evaluate(() => document.getAnimations().filter(animation => animation.id === 'pw-surface-enter' && animation.playState === 'running').length) });
    }
    await settleEnters(page);
    assert.equal(await input().inputValue(), draft);
    assert(Math.abs(await scroll().evaluate(node => node.scrollTop) - readingTop) <= 2, 'Navigation changed the saved reading offset');
    assert.deepEqual(arrivals(await observed(page, before)), [], 'Returning to chat replayed its already-rendered messages');
    const surfaceStyle = await page.locator('.pw-route-workspace').evaluate(node => ({ opacity: getComputedStyle(node).opacity, transform: getComputedStyle(node).transform }));
    assert.equal(Number(surfaceStyle.opacity), 1, 'An interrupted entry left the route transparent');
    assert(['none', 'matrix(1, 0, 0, 1, 0, 0)'].includes(surfaceStyle.transform), 'An interrupted entry left a transform behind');
    remember('rapid-navigation', { states, surfaceStyle, readingTop, animations: await observed(page, before) });
  });

  await check('streaming-does-not-replay-and-only-new-tail-identities-enter-once', async () => {
    const before = await checkpoint(page), top = await scroll().evaluate(node => node.scrollTop);
    for (const text of ['\n\n第一段补充。', '还在整理。', '现在这一段完整了。']) {
      await page.evaluate(text => window.motionFixture.stream('motion-answer-b', text), text);
      await settle(page);
    }
    assert.equal(await scroll().evaluate(node => node.scrollTop), top, 'Streaming pulled the user away from an earlier reading position');
    assert.deepEqual(arrivals(await observed(page, before)), [], 'Streaming an existing id replayed an arrival');
    await scroll().evaluate(node => { node.scrollTop = node.scrollHeight; });
    await settle(page);
    const arrivalStart = await checkpoint(page);
    await page.evaluate(() => window.motionFixture.append('motion-arrival-one', '新的回复到了。'));
    await captureObservedMidpoint(page, 'message-arrival-midpoint', { id: 'pw-message-enter' });
    await settleEnters(page);
    for (const text of ['\n\n这是同一条回复的后续文字。', '它保持原来的身份。', '不会随着每一段文字重新进场。']) {
      await page.evaluate(text => window.motionFixture.stream('motion-arrival-one', text), text);
      await settle(page);
    }
    await page.evaluate(() => window.motionFixture.append('motion-arrival-two', '第二条新回复，拥有自己的消息身份。'));
    await settleEnters(page);
    const records = arrivals(await observed(page, arrivalStart));
    assert.deepEqual(records.map(record => record.messageId), ['motion-arrival-one', 'motion-arrival-two'], 'Each new tail identity must enter exactly once');
    assertVisibleAnimation(records, 'pw-message-enter');
    remember('message-arrival-identity', { animations: records, originalReadingTop: top });
    const historyStart = await checkpoint(page);
    await page.evaluate(() => window.motionFixture.prepend('motion-historical-message'));
    await settleEnters(page);
    assert.deepEqual(arrivals(await observed(page, historyStart)), [], 'Prepending earlier history replayed message arrivals');
  });

  await check('conversation-switch-restores-original-draft-without-arrival-replay', async () => {
    const before = await checkpoint(page);
    await openNavigation(page);
    await page.getByRole('button', { name: '周末的小旅行', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('.pw-conversation-scroll')?.getAttribute('data-conversation-id') === 'motion-conversation-b');
    assert.equal(await input().inputValue(), '第二份工作有自己的草稿');
    await settleEnters(page);
    await openNavigation(page);
    await page.getByRole('button', { name: '把明天安排妥当', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('.pw-conversation-scroll')?.getAttribute('data-conversation-id') === 'motion-conversation-a');
    await settleEnters(page);
    assert.equal(await input().inputValue(), draft);
    assert.deepEqual(arrivals(await observed(page, before)), [], 'Switching conversations replayed stored history');
  });

  await check('empty-to-restored-history-is-hydration-not-a-new-arrival', async () => {
    const before = await checkpoint(page);
    const expected = await page.locator('.pw-message').count();
    await page.evaluate(() => window.motionFixture.unloadHistory());
    await page.waitForFunction(() => document.querySelectorAll('.pw-message').length === 0);
    await page.evaluate(() => window.motionFixture.restoreHistory());
    await page.waitForFunction(expected => document.querySelectorAll('.pw-message').length === expected, expected);
    await settleEnters(page);
    assert.deepEqual(arrivals(await observed(page, before)), [], 'An empty restored window treated its history as new replies');
    assert.equal(await input().inputValue(), draft);
  });

  await check('reader-keeps-its-snapshot-and-position-during-background-streaming', async () => {
    const original = page.locator('[data-message-id="motion-answer-a"]');
    const trigger = original.getByRole('button', { name: '消息操作与来源', exact: true });
    await trigger.scrollIntoViewIfNeeded();
    await settle(page);
    await trigger.click();
    const top = await scroll().evaluate(node => node.scrollTop);
    await page.getByRole('button', { name: '阅读与选字', exact: true }).click();
    const reader = page.getByRole('dialog', { name: '阅读回复', exact: true });
    await reader.waitFor();
    const readingPage = reader.locator('.pw-reading-page:not([hidden])');
    const frozen = await readingPage.innerText();
    const before = await checkpoint(page);
    await page.evaluate(() => window.motionFixture.stream('motion-answer-a', '\n\n阅读期间到达的原会话补充。'));
    await settle(page);
    assert.equal(await readingPage.innerText(), frozen, 'A live update replaced the open reading document');
    assert.equal(await scroll().evaluate(node => node.scrollTop), top, 'A live update moved the original reading position');
    assert.deepEqual(arrivals(await observed(page, before)), [], 'Updating a message underneath the reader replayed it');
    await capture(page, 'reading-continuity', 'Open reading snapshot retained while its original message receives more text.');
    const back = reader.getByRole('button', { name: /返回/ }).first();
    await back.click();
    await reader.waitFor({ state: 'hidden' });
    await settle(page);
    assert.equal(await input().inputValue(), draft);
    assert(Math.abs(await scroll().evaluate(node => node.scrollTop) - top) <= 2, 'Closing the reader lost the original scroll offset');
  });

  await checkReviewExits(page);

  await check('foreground-stop-ring-runs-only-for-the-real-active-tuple', async () => {
    assert.equal(await page.locator('.pw-stop-motion').count(), 0);
    await page.evaluate(() => window.motionFixture.setBusy(true));
    const stop = page.getByRole('button', { name: '停止当前回复', exact: true });
    await stop.waitFor();
    assert.equal(await stop.locator('.pw-stop-motion').count() + await stop.evaluate(node => node.matches('.pw-stop-motion') ? 1 : 0), 1, 'Stop motion is not attached to the active stop control');
    assert.equal(await page.locator('.pw-stop-motion [role="progressbar"]').count(), 1, 'The stop control does not contain its progress indicator');
    assert.equal(await page.locator('.pw-stop-motion [role="progressbar"]').getAttribute('aria-valuenow'), null, 'Foreground activity must remain indeterminate');
    await scroll().evaluate(node => { node.scrollTop = node.scrollHeight; });
    await settle(page);
    const first = await cssAnimations(page);
    assert(first.some(animation => animation.inStop && animation.playState === 'running' && animation.iterations === 'infinite'), 'The foreground progress circle has no running animation');
    const ringFrames = await sampleDuring(page, 'foreground-progress-frames', '.pw-stop-motion', async () => { await capture(page, 'foreground-progress', 'Indeterminate circle accompanies the original foreground stop control.'); }, 220);
    const second = await cssAnimations(page);
    assert(second.some(animation => animation.inStop && first.some(before => before.inStop && before.name === animation.name && Number(animation.currentTime) > Number(before.currentTime) + 30)), 'The displayed progress circle did not advance over time');
    remember('foreground-progress-animation', { first, second, sampledFrames: ringFrames.length });
  });

  await check('hidden-document-pauses-infinite-loops-and-does-not-replay-on-resume', async () => {
    await page.evaluate(() => window.motionFixture.setDocumentHidden(true));
    await page.waitForFunction(() => document.documentElement.dataset.pwMotionPaused === 'true');
    await settle(page);
    const paused = (await cssAnimations(page)).filter(animation => animation.iterations === 'infinite');
    assert(paused.length > 0, 'There was no active CSS animation to validate the pause policy against');
    assert(paused.every(animation => animation.playState === 'paused'), 'An infinite animation continued while the document was marked hidden');
    await page.evaluate(() => {
      window.pausedMotionEvidence = document.getAnimations().filter(animation => animation instanceof CSSAnimation && animation.effect.getTiming().iterations === Infinity).map(animation => ({ animation, currentTime: animation.currentTime }));
    });
    const before = await checkpoint(page);
    await sampleDuring(page, 'hidden-progress-frames', '.pw-stop-motion', async () => {
      await page.evaluate(() => window.motionFixture.append('motion-hidden-arrival', '页面恢复可见前已到达的回复。'));
    }, 220);
    const elapsed = await page.evaluate(() => window.pausedMotionEvidence.map(({ animation, currentTime }) => ({ name: animation.animationName, playState: animation.playState, delta: Number(animation.currentTime) - Number(currentTime) })));
    assert(elapsed.every(animation => animation.playState === 'paused' && Math.abs(animation.delta) < 1), 'Paused CSS animation time advanced');
    await page.evaluate(() => window.motionFixture.setDocumentHidden(false));
    await page.waitForFunction(() => document.documentElement.dataset.pwMotionPaused !== 'true');
    await settle(page);
    assert((await cssAnimations(page)).some(animation => animation.inStop && animation.playState === 'running'), 'The active progress circle did not resume');
    assert.deepEqual(arrivals(await observed(page, before)), [], 'A hidden arrival replayed during visibility recovery');
    remember('synthetic-visibility-pause', { paused, elapsed, limitation: 'Synthetic document visibility event, not OS background suspension.' });
  });

  await check('dynamic-reduced-motion-removes-loops-and-spatial-entries', async () => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await settle(page);
    const reducedLoops = await cssAnimations(page);
    remember('dynamic-reduced-loop-inventory', { reducedLoops, media: await page.evaluate(() => ({ reduced: matchMedia('(prefers-reduced-motion: reduce)').matches, hidden: document.hidden })) });
    assert.equal(reducedLoops.filter(animation => animation.iterations === 'infinite' && animation.playState === 'running').length, 0, 'An infinite CSS animation continued under reduced motion');
    const sameStop = await page.locator('.pw-stop-motion').elementHandle();
    await page.emulateMedia({ reducedMotion: 'no-preference' });
    await settle(page);
    assert.equal(await sameStop.evaluate(node => node === document.querySelector('.pw-stop-motion')), true, 'A media preference remounted the active stop control');
    const resumedFirst = await cssAnimations(page);
    assert(resumedFirst.some(animation => animation.inStop && animation.iterations === 'infinite' && animation.playState === 'running'), 'Allowing motion did not restore the existing active progress circle');
    await sampleDuring(page, 'preference-restored-progress-frames', '.pw-stop-motion', async () => {}, 220);
    const resumedSecond = await cssAnimations(page);
    assert(resumedSecond.some(animation => animation.inStop && resumedFirst.some(before => before.inStop && before.name === animation.name && Number(animation.currentTime) > Number(before.currentTime) + 30)), 'The restored progress circle stayed frozen');
    remember('same-control-motion-preference-recovery', { first: resumedFirst, second: resumedSecond });
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await settle(page);
    assert.equal((await cssAnimations(page)).filter(animation => animation.iterations === 'infinite' && animation.playState === 'running').length, 0, 'A second reduction left a resumed loop running');
    const before = await checkpoint(page);
    await page.evaluate(() => window.motionFixture.append('motion-reduced-arrival', '减少动态效果时，新的内容仍然可以直接阅读。'));
    await settleEnters(page);
    await navigate(page, 'tasks', '任务');
    await settleEnters(page);
    if (currentCase.width >= 1000) {
      const frames = await sampleDuring(page, 'reduced-navigation-frames', 'nav[aria-label="主导航"] a[aria-current="page"] .pw-nav-selection', () => primaryNav(page).getByRole('link', { name: '项目', exact: true }).click(), 220);
      assert(frames.every(frame => ['none', 'matrix(1, 0, 0, 1, 0, 0)'].includes(frame.transform)), 'Navigation still interpolated a spatial transform under reduced motion');
    }
    await navigate(page, 'chat', '对话');
    await settleEnters(page);
    const reducedEntries = await observed(page, before);
    assert(reducedEntries.every(record => (record.keyframes || []).every(frame => !frame.transform || ['none', 'translateY(0px)', 'translateY(0)', 'translate(0px, 0px)', 'matrix(1, 0, 0, 1, 0, 0)'].includes(frame.transform))), 'Reduced motion still created a translated or scaled WAAPI entry');
    assert.equal(await input().inputValue(), draft);
    await scroll().evaluate(node => { node.scrollTop = node.scrollHeight; });
    await capture(page, 'reduced-motion', 'Dynamic prefers-reduced-motion retains state and readable active controls without looping motion.');
    await page.getByRole('button', { name: '停止当前回复', exact: true }).click();
    await page.waitForFunction(() => window.motionFixture.inspect().snapshot.state.foregroundTurn === null);
    await page.locator('.pw-stop-motion').waitFor({ state: 'detached' });
    const stopped = await page.evaluate(() => window.motionFixture.inspect().requests.filter(request => request.action === 'chat.cancel'));
    assert.equal(stopped.length, 1, 'Stop was dispatched more than once');
    assert.deepEqual(stopped[0].args, { conversationId: 'motion-conversation-a', taskId: 'motion-current-reply', runId: 'motion-reply-run', epoch: 1 });
    await page.emulateMedia({ reducedMotion: 'no-preference' });
    await settle(page);
    const restart = await checkpoint(page);
    await page.evaluate(() => window.motionFixture.append('motion-restored-arrival', '恢复动态效果后，下一条新回复正常进场。'));
    await settleEnters(page);
    const restored = arrivals(await observed(page, restart));
    assert.equal(restored.length, 1, 'Dynamic motion preference did not recover for a new reply');
    assertVisibleAnimation(restored, 'pw-message-enter');
    remember('dynamic-motion-preference', { reducedLoops, reducedEntries, restored, stopped });
  });

  await check('both-themes-retain-stable-endpoints-and-fit-the-viewport', async () => {
    const before = await checkpoint(page);
    await page.evaluate(dark => window.motionFixture.setAppearance(dark), !currentCase.dark);
    await settle(page);
    assert.equal(await input().inputValue(), draft);
    assert.deepEqual(arrivals(await observed(page, before)), [], 'A theme change replayed messages');
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, 'The page overflows the viewport horizontally');
    await capture(page, 'alternate-theme', 'The same retained work in the other theme.');
    await page.evaluate(dark => window.motionFixture.setAppearance(dark), currentCase.dark);
    await settle(page);
    assert.equal(await page.locator('.pw-stop-motion').count(), 0, 'Completed foreground work left an animated stop control behind');
    await capture(page, 'final-chat', 'Final settled state after navigation, streaming, reading, visibility and reduced-motion changes.');
  });
  if (currentCase.width < 1000) await runReducedStartup(page);
}

try {
  for (const viewport of cases) {
    currentCase = viewport;
    const context = await browser.newContext({ viewport: { width: viewport.width, height: viewport.height }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', reducedMotion: reducedStartupOnly ? 'reduce' : 'no-preference', serviceWorkers: 'block', ...(demo ? { recordVideo: { dir: output, size: { width: viewport.width, height: viewport.height } } } : {}) });
    await context.route('**/*', route => new URL(route.request().url()).origin === origin ? route.continue() : route.abort());
    await context.addInitScript(installFixture, fixtureSnapshot(viewport.dark));
    await context.addInitScript(installMotionObserver);
    const page = currentPage = await context.newPage();
    const video = demo ? page.video() : null;
    page.setDefaultTimeout(8000);
    page.on('pageerror', error => report.errors.push({ viewport: viewport.name, message: String(error) }));
    page.on('console', message => { if (message.type() === 'error') report.errors.push({ viewport: viewport.name, message: message.text() }); });
    await page.goto(origin);
    await page.getByRole('textbox', { name: '工作输入', exact: true }).waitFor();
    await page.evaluate(() => document.fonts.ready);
    await settle(page);
    if (baseline) {
      await page.locator('.pw-conversation-scroll').evaluate(node => { node.scrollTop = node.scrollHeight; });
      await settle(page);
      await capture(page, 'chat', 'Existing production baseline; synthetic messages and task states.');
      if (viewport.width < 1000) {
        await openNavigation(page);
        await page.waitForFunction(() => !document.getAnimations().some(animation => animation.effect?.target?.matches?.('.pw-navigation-drawer, .pw-navigation-overlay') && animation.playState === 'running'));
        await capture(page, 'navigation', 'Existing side navigation; synthetic recent conversations.');
      }
    } else if (demo) await recordNaturalDemo(page);
    else if (reducedStartupOnly) await runReducedStartup(page, true);
    else await runMotionRegression(page);
    assert.deepEqual(await page.evaluate(() => window.motionFixture.inspect().violations), [], 'Fixture native authority violations');
    report.evidence.push({ viewport: viewport.name, animations: await page.evaluate(() => window.motionEvidence.read()), nativeRequests: await page.evaluate(() => window.motionFixture.inspect().requests) });
    await context.close();
    if (video) {
      const file = 'pocketworld-motion-phone.webm';
      await fs.rename(await video.path(), path.join(output, file));
      report.video = { file, viewport, playback: 'natural' };
    }
  }
  assert.deepEqual(report.errors, [], 'Browser page/console errors');
  report.status = 'passed';
} catch (error) {
  report.status = 'failed'; report.failure = String(error.stack || error); process.exitCode = 1;
  if (currentPage && !currentPage.isClosed()) {
    await capture(currentPage, 'failure', 'Failed assertion; inspect results.json.').catch(() => {});
    report.cssAnimationDiagnostics = await cssAnimations(currentPage).catch(() => null);
    report.diagnostics = await currentPage.evaluate(() => ({ fixture: window.motionFixture?.inspect(), animations: window.motionEvidence?.read(), route: document.querySelector('.pw-app')?.getAttribute('data-route'), text: document.body.innerText })).catch(() => null);
  }
} finally {
  report.finishedAt = new Date().toISOString();
  await fs.writeFile(path.join(output, 'results.json'), JSON.stringify(report, null, 2) + '\n');
  await browser.close();
  await new Promise(resolve => server.close(resolve));
  console.log(JSON.stringify({ status: report.status, baseline, checks: report.checks.length, captures: report.captures.map(item => item.file), output, failure: report.failure }));
}
