#!/usr/bin/env node
/**
 * GitHub Runner evidence of the CURRENT production Web bundle and mounted source
 * components. The in-memory bridge below is an isolated UI test fixture. It is
 * never shipped, never calls Android/TaskStore/models/devices, and proves neither
 * Android host behavior nor real task execution. Keep the emulator gate separate.
 */
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import { createRequire } from 'node:module';

const repo = path.resolve(import.meta.dirname, '../..');
const bundle = path.join(repo, 'app/src/main/assets/pocket-ui');
const output = path.join(repo, 'build-artifacts/library-web-visual');
const sha256 = data => crypto.createHash('sha256').update(data).digest('hex');
const clone = value => structuredClone(value);
const commit = process.env.GITHUB_SHA || '';
const manifest = {
  schema: 1, kind: 'production-web-source-component-visual-evidence',
  status: 'running', commit, runId: process.env.GITHUB_RUN_ID || null,
  runAttempt: process.env.GITHUB_RUN_ATTEMPT || null,
  identity: 'GitHub Chromium + isolated PocketNative UI fixture; NOT Android, NOT a physical phone, NOT real tasks',
  limitations: [
    'This evidence does not replace Android APK, ABI, foreground, bridge authority or WebView >=111 acceptance.',
    'Fixture task, project, member and usage records are synthetic layout inputs, not execution receipts.',
    'Only components actually mounted and checked below are evidenced; no claim of all 42 groups.',
    'Browser pointer gestures are scripted; Android touch/IME/accessibility remain separate checks.',
  ],
  fixture: { version: 1, bridgeProtocol: 'v1/id/documentId/state/revision', persistence: 'none', externalCalls: 'blocked' },
  startedAt: new Date().toISOString(), viewports: [], captures: [], errors: [], requests: [], assets: [],
};
let stage = 'preflight';
let server;
let browser;
let context;

// The explicit full read-model shape follows PocketWebState.snapshot. Values are
// empty/unconfigured; no source seed is added to the production application.
function emptySnapshot() {
  return {
    state: {
      modelName: '', baseUrl: '', hasApiKey: false, screenshots: false,
      localCaptureAllowed: false, localOcrAllowed: false, supportsImageInput: false,
      modelProvider: 'openai_chat', modelRoute: 'api', modelCatalogEnabled: false,
      maxOutputTokens: 4096, cleartextConsent: '', accessibilityEnabled: false,
      notificationsEnabled: false, activeTaskId: null, tasks: [], taskListNextCursor: null,
      conversations: [], conversationId: null, messages: [], chatBusy: false,
      chatSubmitting: false, chatActiveTurn: null, chatSendMode: 'queue', chatReceipts: {},
      selectedChatAttachments: [], chatAttachmentPickerOpen: false,
      chatAttachmentModel: null, attachmentImporting: false, phoneSubmitting: false,
      phoneDraftAcceptedRevision: 0, phoneDraftAcceptedText: '', listening: false,
      speaking: false, speechAvailable: false, voiceInput: null, systemSpeech: null,
      projects: [], projectId: null, files: [], filePath: null, fileContent: '',
      fileBaseline: '', fileVersion: 0, fileLoading: false, fileSaving: false,
      fileReady: false, fileError: null, lastSavedVersion: null, fileHistory: [],
      revisionContent: null, revisionNumber: null, revisionLoading: false,
      requestedProjectId: null, requestedProjectRevision: 0, features: {},
      ownerSnapshots: {}, ownerResponses: {}, ownerPending: [], ownerErrors: {},
      ownerSource: null, moduleResults: {}, moduleBusy: [], moduleErrors: {},
      taskActionErrors: {}, backgroundSetupOpen: false, requestedTaskId: null,
      requestedTaskRevision: 0, requestedConnectionSection: null,
      requestedConnectionRevision: 0, teamRunId: null, teamRuns: {}, modelTest: null,
      modelTestTaskId: null, modelSaving: false, modelTesting: false, modelSavedRevision: 0,
      error: null, notice: null, newTaskScopeMinutes: -1, newTaskToolBudget: 30,
      newTaskModelBudget: 16, phoneReadiness: { checkedAtUptimeMs: 0, groups: [] },
      modelDrafts: {}, conversationReader: null, queuedInputsPage: null,
      projectPreview: null, projectLivePreview: null, projectLivePreviewChecking: false,
    },
    composer: {
      ready: true, key: 'visual-fixture.empty', epoch: 1, revision: 1, text: '', mode: 'queue',
      conversationId: '', saving: false, scrollIndex: 0, scrollOffset: 0,
      anchorMessage: '', observedMessage: '', newReplies: false, sourceRefs: [],
      voiceText: '', voiceInputId: '', pending: [],
    },
    phoneDraft: { text: '', revision: 0, epoch: 1, phone: false, mode: 'background' },
    view: { section: null },
  };
}

function populatedSnapshot() {
  const value = emptySnapshot();
  const at = Date.UTC(2026, 10, 1, 12, 0);
  const members = [
    { id: 'fixture-planner', name: '夹具 · 规划成员', status: 'returned', task: '仅用于组件布局核对' },
    { id: 'fixture-reviewer', name: '夹具 · 审阅成员', status: 'waiting_approval', task: '静态 UI 夹具，未执行工作' },
  ];
  const task = {
    id: 'visual-fixture-task-review', goal: '隔离视觉夹具 · 核对页面结构', status: 'needs_confirmation',
    summary: '此记录只用于观察正式组件布局；没有模型调用、设备操作或真实交付。',
    executionMode: 'background', updatedAt: at,
    raw: {
      kind: 'assistant', status: 'needs_confirmation', runId: 'visual-fixture-run', coordinatorEpoch: 1,
      revision: 1, toolLimit: 30, modelCallLimit: 16,
      executionScope: { fixture: true, toolLimit: 30, modelCallLimit: 16 },
      steps: [
        { id: 'fixture-step-read', title: '夹具步骤 · 读取约束', status: 'complete', durationMillis: 1200 },
        { id: 'fixture-step-review', title: '夹具步骤 · 待审阅布局', status: 'needs_approval', children: [
          { id: 'fixture-sub-title', title: '夹具子步骤 · 信息层级', status: 'returned' },
          { id: 'fixture-sub-control', title: '夹具子步骤 · 控件密度', status: 'pending' },
        ] },
        { id: 'fixture-step-delivery', title: '夹具步骤 · 尚未交付', status: 'pending' },
      ],
      operations: [{
        operationId: 'visual-fixture-operation', title: '夹具工具卡 · read_layout', state: 'returned',
        arguments: { source: 'isolated UI fixture', path: 'layout-notes.md' },
        receipt: { fixture: true, description: '静态结构示例；未运行工具。' }, durationMillis: 1200,
      }],
      executionTree: { roots: [{ id: 'fixture-coordinator', name: '夹具 · 协调成员', status: 'waiting_approval',
        task: '静态协作关系，不代表正在执行', children: members }] },
      handoffs: { agents: members, routes: [{ id: 'fixture-route', fromId: members[0].id, toId: members[1].id,
        title: '夹具材料交接', items: [{ id: 'fixture-material', title: '夹具 · 层级检查清单',
          summary: '只展示卡片与展开布局', status: 'returned',
          materials: [{ id: 'fixture-file', title: 'fixture-layout.md' }], reason: '隔离测试数据，未发生实际交接。' }] }] },
      findings: { agents: members, findings: [{ id: 'fixture-finding', title: '夹具发现 · 输入区保持可达',
        agentId: members[1].id, agentName: members[1].name, file: 'fixture-layout.md',
        body: '这段文字仅用于验证展开后的阅读密度与换行。', reviewed: false }] },
      memory: [{ id: 'fixture-memory', text: '隔离夹具：保持黑白灰与紧凑信息层级。',
        scope: '项目', sourceLabel: '隔离视觉夹具', revision: 1 }],
      contextWindow: { model: '视觉夹具，非模型', used: 2400, total: 8000, unit: 'fixture tokens',
        message: '示例数值，不是真实调用用量。', segments: [
          { id: 'fixture-context', name: '夹具上下文', value: 1600, items: [{ id: 'fixture-context-file', title: 'fixture-layout.md', value: 1600 }] },
          { id: 'fixture-history', name: '夹具对话', value: 800 },
        ] },
    },
  };
  Object.assign(value.state, {
    conversations: [{ id: 'visual-fixture-conversation', title: '隔离视觉夹具 · 工作区', updatedAt: at }],
    conversationId: 'visual-fixture-conversation',
    messages: [
      { id: 'visual-fixture-user', role: 'user', content: '隔离视觉夹具：请展示清晰的信息层级。', at, taskId: null, attachments: [] },
      { id: 'visual-fixture-assistant', role: 'assistant',
        content: '这是用于**组件布局验收**的隔离文字。\n\n- 核对任务与来源的关系\n- 保留原状态和可读的操作范围\n\n这里没有真实任务或执行回执。',
        at: at + 1000, taskId: task.id, attachments: [], sourceRevision: 1 },
    ],
    tasks: [task, { id: 'visual-fixture-task-paused', goal: '隔离视觉夹具 · 尚待补充材料',
      status: 'paused', summary: '静态暂停示例，没有后台工作。', executionMode: 'background', updatedAt: at - 1000,
      raw: { runId: 'visual-fixture-paused-run', coordinatorEpoch: 1, revision: 1 } }],
    projects: [{ id: 'visual-fixture-project', name: '隔离视觉夹具 · 界面笔记' }],
    projectId: 'visual-fixture-project',
    files: [{ path: 'notes/fixture-layout.md', version: 1 }, { path: 'fixture-readme.md', version: 1 }],
    ownerSnapshots: { teams: { teams: [{ id: 'visual-fixture-team', name: '隔离视觉夹具 · 评审组',
      status: 'paused', description: '只核对定义卡片，成员未执行任务。', members, revision: 1, sourceLabel: '隔离 UI 夹具' }], runs: [] } },
  });
  Object.assign(value.composer, { key: 'visual-fixture.populated', conversationId: value.state.conversationId });
  return value;
}

// This function is serialized by Playwright. Everything it touches lives in the
// test document only; production sources contain no fixture branch or data.
function installFixture(options) {
  const copy = value => JSON.parse(JSON.stringify(value));
  let snapshot = copy(options.empty);
  let documentId = null;
  let revision = 0;
  let epoch = 1;
  let scenario = 'empty';
  let label = 'initializing';
  const requests = [];
  const violations = [];
  const receipts = new Map();
  const commands = [
    'app.ready', 'app.refresh', 'composer.edit', 'composer.reading', 'composer.send',
    'tasks.refresh', 'tasks.open', 'tasks.close', 'tasks.review', 'tasks.confirm', 'tasks.scope',
    'projects.refresh', 'projects.select', 'projects.open', 'projects.close', 'projects.create',
    'projects.newFile', 'connections.enter', 'connections.leave',
    'settings.saveModel', 'settings.testModel',
  ];
  const badge = () => {
    if (!document.body) return;
    let node = document.getElementById('pocket-ci-evidence-identity');
    if (!node) {
      node = document.createElement('div'); node.id = 'pocket-ci-evidence-identity';
      node.setAttribute('aria-hidden', 'true');
      Object.assign(node.style, { position: 'fixed', inset: 'auto 0 0', zIndex: '2147483647', pointerEvents: 'none',
        background: '#111', color: '#fff', font: '9px/13px monospace', padding: '2px 4px', textAlign: 'center' });
      document.body.append(node);
    }
    node.textContent = `WEB UI FIXTURE · NOT ANDROID / REAL TASKS · ${options.commit.slice(0, 8)} · ${innerWidth}px · ${label}`;
  };
  document.addEventListener('DOMContentLoaded', badge, { once: true });
  const receive = message => {
    if (!window.PocketWeb?.receive) throw new Error('Production PocketWeb.receive is missing');
    window.PocketWeb.receive(message);
  };
  const publish = () => {
    if (!documentId) throw new Error('Fixture state cannot precede app.ready/documentId');
    snapshot.capabilities = { commands };
    receive({ v: 1, kind: 'state', revision: ++revision, snapshot: copy(snapshot) });
  };
  window.PocketNative = { postMessage(raw) {
    queueMicrotask(() => {
      let request;
      let value = {};
      let failure = null;
      try {
        request = JSON.parse(raw);
        if (request.v !== 1 || typeof request.id !== 'string' || !request.id || typeof request.action !== 'string') throw new Error('Invalid v1/id/action');
        if (!/^[0-9a-f]{32}$/.test(request.documentId || '')) throw new Error('Invalid documentId');
        const identity = JSON.stringify(request);
        if (receipts.has(request.id)) {
          const old = receipts.get(request.id);
          if (old.identity !== identity) throw new Error('Request id reused with different payload');
          receive(old.response); return;
        }
        const args = request.args;
        if (!args || typeof args !== 'object' || Array.isArray(args)) throw new Error('Invalid args');
        if (request.action === 'app.ready') {
          if (args.documentId !== request.documentId || documentId && request.documentId !== documentId) throw new Error('app.ready document fencing failed');
          documentId = request.documentId; value = { ready: true, source: 'library-web' }; publish();
        } else {
          if (!documentId || request.documentId !== documentId) throw new Error('Stale fixture document');
          if (!commands.includes(request.action)) throw new Error('Unsupported fixture capability');
          switch (request.action) {
            case 'app.refresh': case 'tasks.refresh': case 'projects.refresh': publish(); break;
            case 'composer.edit':
              if (args.key !== snapshot.composer.key || args.epoch !== snapshot.composer.epoch || typeof args.text !== 'string') throw new Error('Stale composer fixture key/epoch');
              snapshot.composer.text = args.text; snapshot.composer.revision++; publish(); break;
            case 'composer.reading':
              if (args.key !== snapshot.composer.key) throw new Error('Stale reading fixture key'); break;
            case 'tasks.open':
              if (!snapshot.state.tasks.some(task => task.id === args.id)) throw new Error('Unknown fixture task'); break;
            case 'tasks.close': case 'connections.leave': break;
            case 'connections.enter': snapshot.view.section = args.section; break;
            case 'tasks.review': {
              const task = snapshot.state.tasks.find(row => row.id === args.id);
              if (!task) throw new Error('Unknown fixture review target');
              value = { reviewHandle: `visual-only-${documentId}-${revision}`, expiresInMs: 120000, task: copy(task),
                actions: [{ action: 'tasks.confirm', label: '核对结果' }, { action: 'tasks.scope', label: '调整原任务范围' }],
                approvals: [], unresolvedOperations: [] }; break;
            }
            case 'projects.select':
              if (!snapshot.state.projects.some(project => project.id === args.id)) throw new Error('Unknown fixture project');
              snapshot.state.projectId = args.id; publish(); break;
            case 'projects.open':
              if (args.projectId !== snapshot.state.projectId || !snapshot.state.files.some(file => file.path === args.path)) throw new Error('Unknown fixture file');
              Object.assign(snapshot.state, { filePath: args.path, fileVersion: 1, fileReady: true,
                fileContent: '# 隔离视觉夹具\n\n这段内容只用于真实编辑组件的布局。\n',
                fileBaseline: '# 隔离视觉夹具\n\n这段内容只用于真实编辑组件的布局。\n' }); publish(); break;
            case 'projects.close': snapshot.state.filePath = null; snapshot.state.fileReady = false; publish(); break;
            default:
              // Buttons may mount to inspect their real design. No test gesture is
              // allowed to submit, approve, save, create, test a model or run work.
              throw new Error(`Forbidden business action in visual-only fixture: ${request.action}`);
          }
        }
      } catch (error) { failure = String(error.message || error); violations.push(failure); }
      if (!request?.id) return;
      const response = { v: 1, kind: 'result', id: request.id, ok: failure === null,
        ...(failure ? { error: { code: 'fixture_not_accepted', message: failure } } : { value }) };
      receipts.set(request.id, { identity: JSON.stringify(request), response });
      requests.push({ action: request.action, id: request.id, documentId: request.documentId, ok: !failure, revision });
      receive(response);
    });
  } };
  Object.defineProperty(window, '__POCKET_VISUAL_FIXTURE__', { value: {
    setScenario(next) {
      if (!['empty', 'populated'].includes(next)) throw new Error('Unknown UI fixture scenario');
      scenario = next; snapshot = copy(options[next]);
      snapshot.composer.epoch = ++epoch; snapshot.phoneDraft.epoch = epoch;
      publish(); badge();
    },
    label(next) { label = next; badge(); },
    summary() { return { scenario, revision, documentId, requests: copy(requests), violations: [...violations],
      persistence: 'none', tasks: snapshot.state.tasks.map(task => task.id) }; },
  }, configurable: false });
}

async function walk(directory, base = directory) {
  const files = [];
  for (const entry of (await fs.readdir(directory, { withFileTypes: true })).sort((a, b) => a.name.localeCompare(b.name))) {
    assert(!entry.isSymbolicLink(), `Symlink is not allowed: ${entry.name}`);
    const file = path.join(directory, entry.name);
    if (entry.isDirectory()) files.push(...await walk(file, base));
    else if (entry.isFile()) {
      const data = await fs.readFile(file);
      files.push({ path: path.relative(base, file).split(path.sep).join('/'), bytes: data.length, sha256: sha256(data) });
    }
  }
  return files;
}

async function verifyBundle() {
  assert(process.env.GITHUB_ACTIONS === 'true', 'This capture is authorized only on GitHub Actions; no local browser fallback');
  assert(/^[0-9a-f]{40}$/.test(commit), 'A precise GitHub commit SHA is required');
  const identity = JSON.parse(await fs.readFile(path.join(bundle, 'build-identity.json'), 'utf8'));
  assert.equal(identity.kind, 'pocketworld-library-web');
  assert.equal(identity.sourceRevision, commit, 'Production bundle belongs to a different commit');
  assert.equal(identity.minimumWebView, 111);
  const assets = (await walk(bundle)).filter(row => row.path !== 'build-identity.json');
  assert.deepEqual(assets, identity.files, 'Production assets changed after the build identity was written');
  assert(assets.some(row => row.path.startsWith('fonts/')), 'Actual bundled fonts missing');
  assert((await fs.readFile(path.join(bundle, 'index.html'), 'utf8')).includes('data-pocket-ui="library-web"'));
  manifest.bundle = { ...identity, identitySha256: sha256(await fs.readFile(path.join(bundle, 'build-identity.json'))) };
  manifest.fixture.sourceSha256 = sha256(await fs.readFile(import.meta.filename));
  manifest.sourceFiles = [];
  for (const file of ['web-ui/src/App.tsx', 'web-ui/src/Secondary.tsx', 'web-ui/src/bridge.ts', 'app/src/main/kotlin/dev/pocketengine/ui/web/PocketWebState.kt']) {
    manifest.sourceFiles.push({ path: file, sha256: sha256(await fs.readFile(path.join(repo, file))) });
  }
}

async function startServer() {
  const root = await fs.realpath(bundle);
  const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
    '.json': 'application/json', '.ttf': 'font/ttf', '.woff': 'font/woff', '.woff2': 'font/woff2', '.svg': 'image/svg+xml', '.png': 'image/png' };
  server = http.createServer(async (request, response) => {
    try {
      assert(['GET', 'HEAD'].includes(request.method), 'Read-only static server');
      const url = new URL(request.url, 'http://127.0.0.1');
      let relative = decodeURIComponent(url.pathname).replace(/^\/+/, '');
      if (!relative) relative = 'index.html';
      if (relative === 'favicon.ico') { response.writeHead(204); response.end(); return; }
      assert(!relative.includes('\\') && !relative.split('/').includes('..'), 'Invalid asset path');
      const file = await fs.realpath(path.join(root, relative));
      assert(file.startsWith(root + path.sep), 'Outside production bundle');
      const data = await fs.readFile(file);
      response.writeHead(200, { 'Content-Type': mime[path.extname(file)] || 'application/octet-stream',
        'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff',
        'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; frame-src 'none'" });
      response.end(request.method === 'HEAD' ? undefined : data);
    } catch { response.writeHead(404); response.end('Asset unavailable'); }
  });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  const address = server.address();
  return `http://127.0.0.1:${address.port}`;
}

async function settle(page, milliseconds = 420) {
  await page.evaluate(async () => { await document.fonts.ready; await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))); });
  if (milliseconds) await page.waitForTimeout(milliseconds);
}

async function reachable(locator, { minSize = 24 } = {}) {
  await locator.waitFor({ state: 'visible' });
  assert.equal(await locator.count(), 1, 'The main target is ambiguous');
  assert(await locator.isEnabled(), 'The main target is disabled');
  const geometry = await locator.evaluate(node => {
    const b = node.getBoundingClientRect();
    const x = b.x + b.width / 2, y = b.y + b.height / 2;
    const target = document.elementFromPoint(x, y);
    return { x: b.x, y: b.y, width: b.width, height: b.height, viewportWidth: innerWidth, viewportHeight: innerHeight,
      hit: !!target && (node === target || node.contains(target)) };
  });
  assert(geometry.width >= minSize && geometry.height >= minSize, `Control is below ${minSize}px: ${JSON.stringify(geometry)}`);
  assert(geometry.x >= -1 && geometry.y >= -1 && geometry.x + geometry.width <= geometry.viewportWidth + 1 && geometry.y + geometry.height <= geometry.viewportHeight + 1, `Control clipped: ${JSON.stringify(geometry)}`);
  assert(geometry.hit, `Control is obstructed: ${JSON.stringify(geometry)}`);
  return geometry;
}

async function capture(page, viewport, name, selectors, options = {}) {
  stage = `${viewport.width}:${name}`;
  await page.evaluate(name => window.__POCKET_VISUAL_FIXTURE__.label(name), name);
  await settle(page, options.motion ? 0 : 420);
  const mounted = [];
  for (const selector of selectors) {
    const locator = page.locator(selector).first();
    await locator.waitFor({ state: 'visible' });
    const bounds = await locator.boundingBox();
    assert(bounds && bounds.width > 0 && bounds.height > 0, `Mounted component has no layout: ${selector}`);
    mounted.push({ selector, count: await page.locator(selector).count(), bounds });
  }
  const stepCount = await page.locator('pocket-execution-steps .step').count();
  if (selectors.includes('pocket-execution-steps')) assert.equal(stepCount, 3, 'Actual source stepper did not receive the fixture steps');
  const layout = await page.evaluate(() => ({
    innerWidth, innerHeight, documentWidth: document.documentElement.scrollWidth, bodyWidth: document.body.scrollWidth,
    fonts: ['Inter', 'Noto Sans SC', 'JetBrains Mono'].map(family => ({ family, loaded: document.fonts.check(`400 16px "${family}"`) })),
    reducedMotion: matchMedia('(prefers-reduced-motion: reduce)').matches,
    scale: visualViewport?.scale ?? 1,
  }));
  assert(layout.documentWidth <= viewport.width + 1 && layout.bodyWidth <= viewport.width + 1, `Horizontal overflow at ${stage}: ${JSON.stringify(layout)}`);
  assert(layout.fonts.every(font => font.loaded), 'Bundled font not ready');
  assert.equal(layout.scale, 1, 'Unexpected whole-page zoom');
  const fixture = await page.evaluate(() => window.__POCKET_VISUAL_FIXTURE__.summary());
  assert.equal(fixture.violations.length, 0, `Fixture protocol/business violation: ${fixture.violations.join('; ')}`);
  assert.equal(manifest.errors.length, 0, 'Browser errors occurred; inspect manifest');
  const file = `${viewport.width}-${name}.png`;
  await page.screenshot({ path: path.join(output, file), fullPage: false, animations: 'allow', caret: 'hide' });
  const png = await fs.readFile(path.join(output, file));
  assert(png.length > 1024 && png.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])), 'Screenshot is not a nonempty PNG');
  assert.equal(png.readUInt32BE(16), viewport.width);
  assert.equal(png.readUInt32BE(20), viewport.height);
  manifest.captures.push({ file, width: viewport.width, viewport, stage: name, kind: 'web-ui-isolated-fixture',
    android: false, realTasks: false, scenario: fixture.scenario, revision: fixture.revision, mounted, layout,
    stepCount, capturedAt: new Date().toISOString(), ...options.metadata });
}

async function navigate(page, name) {
  const open = page.getByRole('button', { name: '全部工作', exact: true });
  await reachable(open); await open.click();
  const content = page.locator('.pw-work-drawer');
  await content.waitFor({ state: 'visible' });
  await content.getByRole('link', { name, exact: true }).click();
  await content.waitFor({ state: 'hidden' });
  await settle(page);
}

async function runViewport(origin, viewport) {
  stage = `${viewport.width}:context`;
  const record = { ...viewport, deviceScaleFactor: 1, browserInput: 'scripted pointer, not Android touch',
    checks: [], fixture: null, video: null };
  manifest.viewports.push(record);
  context = await browser.newContext({ viewport, deviceScaleFactor: 1, locale: 'zh-CN', timezoneId: 'UTC',
    colorScheme: 'light', reducedMotion: 'no-preference', serviceWorkers: 'block',
    recordVideo: { dir: path.join(output, 'videos'), size: viewport } });
  await context.route('**/*', async route => {
    const request = route.request();
    const url = new URL(request.url());
    const allowed = url.origin === origin && ['GET', 'HEAD'].includes(request.method());
    manifest.requests.push({ width: viewport.width, path: allowed ? url.pathname : `${url.protocol}//${url.host}`, method: request.method(), allowed });
    if (!allowed) { manifest.errors.push({ stage, kind: 'external-request-blocked', url: `${url.protocol}//${url.host}` }); await route.abort('blockedbyclient'); }
    else await route.continue();
  });
  await context.routeWebSocket('**/*', socket => {
    manifest.errors.push({ stage, kind: 'websocket-blocked' });
    socket.close({ code: 1008, reason: 'Visual fixture allows no WebSocket traffic' });
  });
  await context.addInitScript(installFixture, { empty: emptySnapshot(), populated: populatedSnapshot(), commit });
  const page = await context.newPage();
  page.setDefaultTimeout(15_000);
  page.on('pageerror', error => manifest.errors.push({ stage, kind: 'pageerror', message: error.message }));
  page.on('console', message => {
    if (message.type() === 'error') manifest.errors.push({ stage, kind: 'console-error', message: message.text() });
  });
  page.on('response', response => {
    if (response.status() >= 400) manifest.errors.push({ stage, kind: 'http-error', status: response.status(), path: new URL(response.url()).pathname });
  });
  const video = page.video();
  try {
    await page.goto(origin + '/', { waitUntil: 'networkidle' });
    await page.waitForFunction(() => document.documentElement.dataset.pocketUi === 'ready');
    await page.getByRole('heading', { name: '想把什么交给我？', exact: true }).waitFor();
    const fonts = await page.evaluate(async () => {
      const faces = await Promise.all(['Inter', 'Noto Sans SC', 'JetBrains Mono'].map(family => document.fonts.load(`400 16px "${family}"`, '工作PocketWorld')));
      await document.fonts.ready;
      return faces.map(values => values.map(font => ({ family: font.family, status: font.status })));
    });
    assert(fonts.every(faces => faces.length > 0 && faces.every(face => face.status === 'loaded')), 'Actual production font faces not loaded');
    record.fontFaces = fonts;
    record.userAgent = await page.evaluate(() => navigator.userAgent);
    record.checks.push({ stage: 'empty', allWork: await reachable(page.getByTestId('all_work'), { minSize: 36 }),
      composer: await reachable(page.getByRole('textbox', { name: '工作输入', exact: true }), { minSize: 30 }) });
    await capture(page, viewport, '01-empty', ['.pw-start .pw-agent-avatar', '.afa-composer', '[data-testid="all_work"]']);
    // Keep the breathing/blink motion visible in the unedited video. No state is
    // advanced and no task progresses while waiting.
    await page.waitForTimeout(1400);
    const editor = page.getByRole('textbox', { name: '工作输入', exact: true });
    await editor.fill('隔离视觉夹具：把信息层级梳理清楚。');
    await capture(page, viewport, '02-input-filled', ['.afa-composer textarea', '.pw-press']);
    const send = page.getByRole('button', { name: '发送', exact: true });
    const geometry = await reachable(send, { minSize: 32 });
    await page.mouse.move(geometry.x + geometry.width / 2, geometry.y + geometry.height / 2);
    await page.mouse.down();
    await page.waitForTimeout(110);
    assert.equal(await send.getAttribute('data-state'), 'pressed', 'Actual PressButton press state did not mount');
    await capture(page, viewport, '03-input-pressed', ['.pw-press[data-state="pressed"]'], { motion: true,
      metadata: { gesture: 'pointer down, held, then dragged out/cancelled; no send' } });
    await page.mouse.move(Math.max(2, geometry.x - 20), Math.max(2, geometry.y - 40));
    await page.mouse.up();
    await settle(page, 300);
    assert.equal(await editor.inputValue(), '隔离视觉夹具：把信息层级梳理清楚。', 'Cancelled press lost the input');
    await page.getByTestId('all_work').click();
    await capture(page, viewport, '04-all-work', ['.pw-work-drawer', '.pw-work-sidebar']);
    await page.getByRole('button', { name: '关闭全部工作', exact: true }).click();
    await page.locator('.pw-work-drawer').waitFor({ state: 'hidden' });
    // Reopen/close in opposite directions so actual Vaul entry and exit remain
    // in the raw video. Do not alter animations or generate interpolated frames.
    await page.getByTestId('all_work').click();
    await page.waitForTimeout(160);
    await page.getByRole('button', { name: '关闭全部工作', exact: true }).click();
    await page.locator('.pw-work-drawer').waitFor({ state: 'hidden' });
    await page.evaluate(() => window.__POCKET_VISUAL_FIXTURE__.setScenario('populated'));
    await navigate(page, '全部任务');
    await capture(page, viewport, '05-task-list', ['.pw-task-list .pw-task-row', '[role="tablist"]']);
    await page.locator('.pw-task-row').filter({ hasText: '隔离视觉夹具 · 核对页面结构' }).click();
    const drawer = page.locator('.pw-task-drawer');
    await drawer.waitFor({ state: 'visible' });
    await capture(page, viewport, '06-task-steps', ['.pw-task-drawer', 'pocket-execution-steps', '.ui-tcc']);
    const tool = drawer.locator('.ui-tcc__head').first();
    await tool.scrollIntoViewIfNeeded(); await tool.click();
    await capture(page, viewport, '07-tool-expanded', ['.ui-tcc__head[aria-expanded="true"]', '.ui-tcc__sections']);
    const tree = drawer.getByRole('tree', { name: 'Agent协作关系', exact: true });
    await tree.scrollIntoViewIfNeeded();
    await capture(page, viewport, '08-agent-members', ['[role="tree"][aria-label="Agent协作关系"]', '.cb-agent-node']);
    const fold = drawer.getByRole('button', { name: '收起 夹具 · 协调成员 的成员', exact: true });
    await fold.click(); await page.waitForTimeout(100);
    await drawer.getByRole('button', { name: '展开 夹具 · 协调成员 的成员', exact: true }).click();
    await settle(page);
    const handoff = drawer.getByRole('region', { name: '材料交接', exact: true });
    await handoff.scrollIntoViewIfNeeded();
    await capture(page, viewport, '09-handoff', ['.afa-handoffagents', '.afa-handoffroutes']);
    const review = drawer.getByRole('region', { name: '原任务核对', exact: true });
    await review.getByRole('button', { name: '查看可用操作', exact: true }).click();
    const feedback = review.getByRole('textbox', { name: '本次结果反馈', exact: true });
    await feedback.fill('隔离夹具文字，不提交任何决定。');
    await feedback.scrollIntoViewIfNeeded();
    await capture(page, viewport, '10-task-review', ['.pw-protected-actions', 'textarea']);
    stage = `${viewport.width}:11-scope-form`;
    const tools = review.getByRole('textbox', { name: '工具调用总上限', exact: true });
    const models = review.getByRole('textbox', { name: '模型调用总上限', exact: true });
    const confirmScope = review.getByRole('button', { name: '确认本次范围修订', exact: true });
    for (const field of [tools, models]) {
      assert.equal(await field.count(), 1, 'The labelled numeric text field is ambiguous');
      assert.equal(await field.getAttribute('inputmode'), 'numeric', 'The actual source field lost numeric input intent');
      assert.equal(await field.getAttribute('pattern'), '[0-9]*', 'The actual source field lost its numeric pattern');
    }
    assert.equal(await tools.inputValue(), '30', 'Reviewed tool limit did not populate the real field');
    assert.equal(await models.inputValue(), '16', 'Reviewed model limit did not populate the real field');
    await tools.fill('401');
    assert(await confirmScope.isDisabled(), 'Out-of-range tool limit must block scope submission');
    await tools.fill('36');
    await models.fill('513');
    assert(await confirmScope.isDisabled(), 'Out-of-range model limit must block scope submission');
    await models.fill('20');
    assert.equal(await tools.inputValue(), '36', 'Tool limit edit did not remain in the controlled field');
    assert.equal(await models.inputValue(), '20', 'Model limit edit did not remain in the controlled field');
    assert(await confirmScope.isEnabled(), 'Valid reviewed scope draft should enable its actual control');
    const scopeDraft = { tools: '36', models: '20', invalidToolLimitBlocked: true, invalidModelLimitBlocked: true, submitted: false };
    record.checks.push({ stage: '11-scope-form', scopeDraft });
    await tools.scrollIntoViewIfNeeded();
    await capture(page, viewport, '11-scope-form', ['.pw-scope-editor', '[role="radiogroup"]'], { metadata: { scopeDraft } });
    await drawer.getByRole('button', { name: '关闭任务详情', exact: true }).click();
    await drawer.waitFor({ state: 'hidden' });
    await navigate(page, '项目与文件');
    await capture(page, viewport, '12-project-files', ['.pw-projects-page', '.pw-project-work']);
    await page.getByRole('tab', { name: '目录层级', exact: true }).click();
    await capture(page, viewport, '13-project-tree', ['.pw-file-tree', '[role="tree"]']);
    await navigate(page, '团队协作');
    await capture(page, viewport, '14-team-definition', ['.pw-secondary[aria-label="团队"]', '.pw-source-record']);
    await navigate(page, '设置与连接');
    await page.getByRole('button', { name: /^模型设置/ }).click();
    const url = page.getByRole('textbox', { name: '接口地址', exact: true });
    await url.fill('https://fixture.invalid/v1');
    await page.getByRole('textbox', { name: '模型名称', exact: true }).fill('隔离布局夹具，非模型');
    await url.scrollIntoViewIfNeeded();
    await capture(page, viewport, '15-model-form', ['.pw-secondary[aria-label="模型设置"]', '.pw-form-grid']);
    const toggle = page.getByRole('switch', { name: '允许按需发送截图' });
    await toggle.scrollIntoViewIfNeeded();
    await toggle.click();
    await capture(page, viewport, '16-form-controls', ['.pw-settings-divided', '[role="switch"]']);
    record.fixture = await page.evaluate(() => window.__POCKET_VISUAL_FIXTURE__.summary());
    assert(record.fixture.requests.some(request => request.action === 'app.ready'), 'No actual bridge handshake');
    assert(!record.fixture.requests.some(request => ['composer.send', 'tasks.confirm', 'tasks.scope', 'settings.saveModel', 'settings.testModel', 'projects.create', 'projects.newFile'].includes(request.action)), 'A business action was dispatched');
    assert.equal(record.fixture.violations.length, 0);
    record.status = 'passed';
  } catch (error) {
    record.status = 'failed'; record.failure = String(error.stack || error);
    try {
      await page.evaluate(() => window.__POCKET_VISUAL_FIXTURE__?.label('FAILED-PARTIAL'));
      const file = `${viewport.width}-failed-partial.png`;
      await page.screenshot({ path: path.join(output, file), fullPage: false });
      manifest.captures.push({ file, viewport, partial: true, stage, android: false, realTasks: false });
      record.fixture = await page.evaluate(() => window.__POCKET_VISUAL_FIXTURE__?.summary());
    } catch { /* retain original failure */ }
    throw error;
  } finally {
    await context.close(); context = null;
    if (video) record.video = path.relative(output, await video.path()).split(path.sep).join('/');
  }
}

try {
  await fs.mkdir(output, { recursive: true });
  assert(process.env.GITHUB_ACTIONS === 'true', 'This capture is authorized only on GitHub Actions; no local browser fallback');
  // This directory belongs solely to this evidence run. Do not let stale PNGs or
  // videos from a prior attempt become evidence of the current commit.
  await fs.rm(output, { recursive: true, force: true });
  await fs.mkdir(output, { recursive: true });
  await verifyBundle();
  const require = createRequire(path.join(repo, 'web-ui/package.json'));
  const packageVersion = require('@playwright/test/package.json').version;
  assert.equal(packageVersion, '1.56.1', 'Use the pinned, officially verified Playwright version');
  const { chromium } = require('@playwright/test');
  const origin = await startServer();
  stage = 'launch-github-chromium';
  browser = await chromium.launch({ headless: true, args: ['--disable-background-networking'] });
  manifest.browser = { engine: 'chromium', version: browser.version(), playwright: packageVersion, source: 'GitHub Actions installed official Playwright browser' };
  const failures = [];
  for (const viewport of [{ width: 360, height: 800 }, { width: 412, height: 892 }]) {
    try { await runViewport(origin, viewport); }
    catch (error) { failures.push(`${viewport.width}px: ${error.message || error}`); }
  }
  assert.equal(failures.length, 0, failures.join('\n'));
  assert.equal(manifest.errors.length, 0, 'Browser/network errors occurred');
  manifest.status = 'passed';
} catch (error) {
  manifest.status = 'failed';
  manifest.failure = { stage, message: String(error.message || error), stack: String(error.stack || error) };
  await fs.writeFile(path.join(output, 'failure.json'), JSON.stringify(manifest.failure, null, 2) + '\n');
  process.exitCode = 1;
} finally {
  if (context) await context.close().catch(() => {});
  if (browser) await browser.close().catch(() => {});
  if (server) await new Promise(resolve => server.close(resolve));
  manifest.finishedAt = new Date().toISOString();
  manifest.assets = (await walk(output)).filter(file => !['manifest.json', 'failure.json'].includes(file.path));
  for (const viewport of manifest.viewports) {
    if (viewport.video) {
      const asset = manifest.assets.find(file => file.path === viewport.video);
      const header = asset ? (await fs.readFile(path.join(output, viewport.video))).subarray(0, 4) : null;
      if (!asset || asset.bytes < 10000 || !header.equals(Buffer.from([26, 69, 223, 163]))) {
        manifest.status = 'failed'; process.exitCode = 1;
        manifest.errors.push({ kind: 'video-missing-or-empty', width: viewport.width });
      }
    }
  }
  if (manifest.status === 'failed' && !manifest.failure) {
    manifest.failure = { stage: 'artifact-integrity', message: 'Recorded video failed the artifact integrity check' };
    await fs.writeFile(path.join(output, 'failure.json'), JSON.stringify(manifest.failure, null, 2) + '\n');
  }
  await fs.writeFile(path.join(output, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
  console.log(JSON.stringify({ status: manifest.status, stage, captures: manifest.captures.length,
    viewports: manifest.viewports.map(row => ({ width: row.width, status: row.status })), artifact: 'build-artifacts/library-web-visual' }));
}
