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
  fixture: { version: 4, bridgeProtocol: 'v1/id/documentId/state/revision', persistence: 'none', externalCalls: 'blocked',
    taskSchema: 'PocketWebState UiTask + TaskStore bounded history pages; no synthetic agent tree or future steps',
    content: 'Representative project-material review, synthetic content; no file or task is changed' },
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
  const taskId = 'visual-fixture-task-review';
  const runId = 'visual-fixture-run';
  // This is the real bounded history page shape. The content is representative
  // test input. It is not a tool receipt, file reference, or production execution.
  const historyEvents = [
    { position: 3, eventKey: `task:${taskId}:event-order:3`, type: 'result', status: 'available', bodyBytes: 168,
      event: { type: 'result', status: 'needs_confirmation', runId, at: at + 90_000,
        message: '已有资料分类建议，请确认后再继续。现有文件保持原位置。' } },
    { position: 2, eventKey: `task:${taskId}:event-order:2`, type: 'observation', status: 'available', bodyBytes: 146,
      event: { type: 'observation', status: 'completed', runId, at: at + 60_000,
        message: '项目资料可按需求、会议和参考资料分组。' } },
    { position: 1, eventKey: `task:${taskId}:event-order:1`, type: 'run', status: 'available', bodyBytes: 112,
      event: { type: 'run', status: 'running', runId, at,
        message: '先整理分类建议，再由你确认。' } },
  ];
  const task = {
    id: taskId, goal: '整理项目资料', status: 'needs_confirmation',
    summary: '建议分为需求、会议和参考资料三组。现有文件尚未移动，等你核对。',
    executionMode: 'background', updatedAt: at + 90_000,
    raw: {
      id: taskId, kind: 'assistant', status: 'needs_confirmation', runId, coordinatorEpoch: 1,
      revision: 1, toolLimit: 30, modelCallLimit: 16,
      taskProjection: 'detail_window', resultReviewDigest: 'a'.repeat(64),
      evidence: {
        modelSummary: '资料分类建议（尚未执行）\n\n- 需求：目标与待确认事项，需求草稿保留为独立文件。\n- 会议：按日期保留每次讨论。\n- 参考资料：外部链接与背景材料。\n\n现有文件尚未移动，请先核对分类。',
        // Display identity only: this isolated fixture cannot establish native authority.
        nativeFinalAnswer: { format: 'pocketworld.runner-final-answer.v1', taskId, runId,
          coordinatorEpoch: 1, inputBoundaryEmpty: true },
      },
      summary: '建议分为需求、会议和参考资料三组。现有文件尚未移动，等你核对。',
      executionScope: { toolLimit: 30, modelCallLimit: 16 },
      operations: [], events: historyEvents.map(row => row.event).reverse(),
      historyEventPage: { taskId, section: 'events', entries: historyEvents, hasMore: false, readLimited: false, nextCursor: '' },
      historyOperationPage: { taskId, section: 'operations', entries: [], hasMore: false, readLimited: false, nextCursor: '' },
      taskCoreReadLimited: false,
    },
  };
  Object.assign(value.state, {
    features: { teams: {
      teams: [{ id: 'fixture-team-a', name: '项目伙伴', revision: 1, members: [
        { id: 'fixture-member-a', name: '资料整理', role: '研究' },
        { id: 'fixture-member-b', name: '内容检查', role: '复核' },
      ] }],
      runs: [{ id: 'fixture-team-run-a', goal: '整理发布前资料', status: 'paused',
        summary: '测试记录：已列出待核对资料，尚未提交任何修改。', revision: 1,
        generation: 1, taskId: 'visual-fixture-task-review' }],
    } },
    // MemoryModule.snapshot contains counts only. The read-only list response
    // belongs to moduleResults, with MemoryBook's actual layer/source fields.
    moduleResults: { 'memory.list': { records: [
      { id: 'fixture-memory-a', ownerId: 'fixture-owner', spaceId: 'fixture-space',
        layer: 'preference', text: '会议记录按日期保留。\n需求草稿先保留独立文件，核对后再决定是否合并。',
        source: '测试对话：项目资料整理', revision: 1, core: false, active: true,
        sourceIds: [], sourceRefs: [], createdAt: at, updatedAt: at + 2000 },
      { id: 'fixture-memory-b', ownerId: 'fixture-owner', spaceId: 'fixture-space',
        layer: 'fact', text: '当前项目的资料包含需求、会议和参考资料。',
        source: '测试记录：项目资料', revision: 1, core: false, active: true,
        sourceIds: [], sourceRefs: [], createdAt: at, updatedAt: at + 1000 },
    ] } },
    modelConnections: { models: [
      { modelId: 'gpt-4.1-mini', name: 'gpt-4.1-mini', catalogFresh: true,
        facts: { CONTEXT_WINDOW: { known: true, value: { tokens: 128000 } }, IMAGE_INPUT: { known: true, value: true } } },
      { modelId: 'fixture-reasoning-model', name: 'Fixture reasoning model', catalogFresh: true, facts: {} },
    ] },
    modelName: 'gpt-4.1-mini', baseUrl: 'https://api.example.invalid/v1', hasApiKey: true,
    conversations: [{ id: 'visual-fixture-conversation', title: '项目资料整理', updatedAt: at + 90_000 }],
    conversationId: 'visual-fixture-conversation',
    messages: [
      { id: 'visual-fixture-user-1', role: 'user', content: '帮我整理这个项目的资料，先给方案，确认后再移动文件。', at, taskId: null, attachments: [] },
      { id: 'visual-fixture-assistant-1', role: 'assistant', content: '可以。我会先按内容归类，保留原文件名称和位置。遇到重复资料会单独列出来，等你决定。', at: at + 1000, taskId: null, attachments: [], sourceRevision: 1 },
      { id: 'visual-fixture-user-2', role: 'user', content: '会议记录按日期保留，需求草稿也先别合并。', at: at + 2000, taskId: null, attachments: [] },
      { id: 'visual-fixture-assistant', role: 'assistant',
        content: '分类建议已经准备好：\n\n- **需求**：目标、需求草稿和待确认事项\n- **会议**：按日期保留每次讨论\n- **参考资料**：外部链接与背景材料\n\n需求草稿不会合并，现有文件也没有移动。你可以先核对这份建议，再决定下一步。',
        at: at + 90_000, taskId: task.id, attachments: [], sourceRevision: 1 },
      { id: 'visual-fixture-user-3', role: 'user', content: '好，我先看看分类是否合适。', at: at + 91_000, taskId: null, attachments: [] },
      { id: 'visual-fixture-assistant-3', role: 'assistant', content: '从上面的任务卡可以回到这次整理。确认前不移动文件；需要修改分类时，也可以在原任务里补充。', at: at + 92_000, taskId: null, attachments: [], sourceRevision: 1 },
    ],
    tasks: [task, { id: 'visual-fixture-task-paused', goal: '补充周会记录',
      status: 'paused', summary: '等你补充本周的讨论要点。', executionMode: 'background', updatedAt: at - 1000,
      raw: { runId: 'visual-fixture-paused-run', coordinatorEpoch: 1, revision: 1 } }],
    projects: [{ id: 'visual-fixture-project', name: '新产品计划' }],
    projectId: 'visual-fixture-project',
    files: [{ path: '需求草稿.md', version: 1 }, { path: '会议/11月讨论.md', version: 1 }, { path: '参考资料.md', version: 1 }],
  });
  Object.assign(value.composer, { key: 'visual-fixture.populated', conversationId: value.state.conversationId });
  return value;
}

function emptyResultSnapshot() {
  const value = populatedSnapshot();
  const task = value.state.tasks[0];
  task.summary = ''; task.raw.summary = ''; task.raw.evidence = {};
  task.raw.resultReviewDigest = 'b'.repeat(64);
  task.raw.events = task.raw.events.filter(event => event.type !== 'result');
  task.raw.historyEventPage.entries = task.raw.historyEventPage.entries.filter(row => row.type !== 'result');
  value.state.messages = value.state.messages.filter(message => message.taskId !== task.id);
  return value;
}

function verifyFixtureInputs() {
  const populated = populatedSnapshot();
  const forbidden = ['steps', 'executionTree', 'handoffs', 'findings', 'memory', 'contextWindow'];
  for (const task of populated.state.tasks) {
    for (const field of forbidden) assert(!(field in task.raw), `Fixture invents an ordinary TaskStore root: ${field}`);
    if (task.status !== 'needs_confirmation') continue;
    assert.equal(task.raw.taskProjection, 'detail_window', 'Review fixture must use a detail window, not a task summary');
    assert(/^[a-f0-9]{64}$/.test(task.raw.resultReviewDigest), 'Review fixture needs a result-bound digest');
    assert.equal(typeof task.raw.evidence.modelSummary, 'string');
    assert.deepEqual(task.raw.evidence.nativeFinalAnswer, { format: 'pocketworld.runner-final-answer.v1',
      taskId: task.id, runId: task.raw.runId, coordinatorEpoch: task.raw.coordinatorEpoch, inputBoundaryEmpty: true });
    for (const [key, section] of [['historyEventPage', 'events'], ['historyOperationPage', 'operations']]) {
      const page = task.raw[key];
      assert.equal(page.taskId, task.id); assert.equal(page.section, section);
      assert.equal(typeof page.hasMore, 'boolean'); assert.equal(typeof page.readLimited, 'boolean');
      assert.equal(typeof page.nextCursor, 'string');
      let previous = Infinity;
      for (const row of page.entries) {
        assert(Number.isSafeInteger(row.position) && row.position < previous, 'TaskStore pages must retain descending position');
        previous = row.position;
        assert(['available', 'read_limited'].includes(row.status));
        assert(Number.isSafeInteger(row.bodyBytes) && row.bodyBytes >= 0);
        if (section === 'events' && row.status === 'available') assert.equal(row.event.runId, task.raw.runId);
        if (section === 'operations') {
          assert.equal(row.runId, task.raw.runId); assert.equal(typeof row.operationId, 'string');
          assert.equal(typeof row.tool, 'string'); assert.equal(typeof row.createdAt, 'number');
          assert(!('durationMillis' in row), 'Registration time is not an execution duration');
        }
      }
    }
  }
  assert.equal(populated.state.hasApiKey, true);
  assert(!('apiKey' in populated.state), 'The fixture must not contain a saved secret');
  const emptyResult = emptyResultSnapshot();
  assert.equal(emptyResult.state.tasks[0].summary, '');
  assert.equal(Object.keys(emptyResult.state.tasks[0].raw.evidence).length, 0);
  assert(!emptyResult.state.tasks[0].raw.events.some(event => event.type === 'result'));
  assert(!emptyResult.state.tasks[0].raw.historyEventPage.entries.some(row => row.type === 'result'));
  assert(!emptyResult.state.messages.some(message => message.taskId === emptyResult.state.tasks[0].id), 'The missing-result case still has a linked answer');
  return { tasks: populated.state.tasks.length, historyEvents: populated.state.tasks[0].raw.historyEventPage.entries.length,
    historyOperations: populated.state.tasks[0].raw.historyOperationPage.entries.length };
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
  let nextReviewRejection = null;
  let focusedTaskId = null;
  const requests = [];
  const violations = [];
  const expectedRejections = [];
  const receipts = new Map();
  const commands = [
    'app.ready', 'app.refresh', 'composer.edit', 'composer.reading', 'composer.send',
    'tasks.refresh', 'tasks.open', 'tasks.close', 'tasks.review', 'tasks.confirm', 'tasks.scope',
    'projects.refresh', 'projects.select', 'projects.open', 'projects.close', 'projects.create',
    'projects.newFile', 'connections.enter', 'connections.leave',
    'settings.saveModel', 'settings.testModel', 'memory.list',
  ];
  const badge = () => {
    if (!document.body) return;
    let node = document.getElementById('pocket-ci-evidence-identity');
    if (!node) {
      node = document.createElement('div'); node.id = 'pocket-ci-evidence-identity';
      node.setAttribute('aria-hidden', 'true');
      Object.assign(node.style, { position: 'fixed', inset: 'auto 0 0', zIndex: '2147483647', pointerEvents: 'none',
        background: '#ededed', color: '#555', font: '10px/14px sans-serif', padding: '2px 4px', textAlign: 'center',
        borderTop: '1px solid #d5d5d5' });
      document.body.append(node);
    }
    node.textContent = 'Web界面预览 · 测试数据 · 非实机';
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
              if (!snapshot.state.tasks.some(task => task.id === args.id)) throw new Error('Unknown fixture task');
              focusedTaskId = args.id;
              // App-owned opens explicitly focus silently. Other native entries
              // retain the bridge's default external-navigation request.
              if (args.requestNavigation !== false) {
                snapshot.state.requestedTaskId = args.id;
                snapshot.state.requestedTaskRevision++;
                publish();
              }
              break;
            case 'tasks.close': focusedTaskId = null; break;
            case 'connections.leave': break;
            case 'connections.enter': snapshot.view.section = args.section; break;
            case 'memory.list':
              if (snapshot.view.section !== 'memory') throw new Error('Memory read outside its original section');
              publish(); break;
            case 'tasks.review': {
              const task = snapshot.state.tasks.find(row => row.id === args.id);
              if (!task) throw new Error('Unknown fixture review target');
              if (focusedTaskId !== args.id) throw new Error('Review preceded opening its original task window');
              if (task.raw.taskProjection !== 'detail_window' || !task.raw.resultReviewDigest) throw new Error('Review tried to use a list summary');
              if (nextReviewRejection) {
                failure = nextReviewRejection; nextReviewRejection = null;
                expectedRejections.push({ action: request.action, taskId: task.id, message: failure });
                break;
              }
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
                fileContent: '# 新产品需求\n\n先把核心目标做清楚，再逐步补充功能。\n\n## 待确认\n\n- 第一版面向的使用者\n- 最常完成的一件事\n- 上线前必须验证的结果\n',
                fileBaseline: '# 新产品需求\n\n先把核心目标做清楚，再逐步补充功能。\n\n## 待确认\n\n- 第一版面向的使用者\n- 最常完成的一件事\n- 上线前必须验证的结果\n' }); publish(); break;
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
      requests.push({ action: request.action, id: request.id, documentId: request.documentId,
        taskId: request.action.startsWith('tasks.') ? request.args.id || focusedTaskId : undefined,
        requestNavigation: request.action === 'tasks.open' ? request.args.requestNavigation : undefined, ok: !failure, revision });
      receive(response);
    });
  } };
  Object.defineProperty(window, '__POCKET_VISUAL_FIXTURE__', { value: {
    setScenario(next) {
      if (!['empty', 'populated', 'emptyResult'].includes(next)) throw new Error('Unknown UI fixture scenario');
      scenario = next; snapshot = copy(options[next]); focusedTaskId = null;
      snapshot.composer.epoch = ++epoch; snapshot.phoneDraft.epoch = epoch;
      publish(); badge();
    },
    label(next) { label = next; badge(); },
    rejectNextReview(message) {
      if (typeof message !== 'string' || !message.trim() || nextReviewRejection) throw new Error('Invalid controlled review rejection');
      nextReviewRejection = message;
    },
    reviseTaskScope() {
      const task = snapshot.state.tasks.find(row => row.id === focusedTaskId);
      if (!task) throw new Error('Scope fixture requires an open task');
      task.updatedAt++; task.raw.revision++;
      task.raw.resultReviewDigest = 'c'.repeat(64);
      task.raw.toolLimit = 42; task.raw.modelCallLimit = 24;
      task.raw.executionScope = { ...task.raw.executionScope, toolLimit: 42, modelCallLimit: 24 };
      publish();
    },
    summary() { return { scenario, label, revision, documentId, requests: copy(requests), violations: [...violations],
      expectedRejections: copy(expectedRejections), persistence: 'none', tasks: snapshot.state.tasks.map(task => task.id) }; },
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
  manifest.fixture.schemaChecks = verifyFixtureInputs();
  manifest.fixture.inputSha256 = sha256(JSON.stringify({ empty: emptySnapshot(), populated: populatedSnapshot(), emptyResult: emptyResultSnapshot() }));
  manifest.acceptance = { structure: 'checked by this script', visualDesign: 'requires reviewing the actual images and video',
    sourceGroups: 'Only mounted selectors are recorded; no count target or all-32/all-42 claim',
    android: 'not exercised by this fixture' };
  manifest.sourceFiles = [];
  for (const file of ['web-ui/src/App.tsx', 'web-ui/src/Secondary.tsx', 'web-ui/src/app.css', 'web-ui/src/secondary.css',
    'web-ui/src/product.css', 'web-ui/src/task-presentation.ts', 'web-ui/src/bridge.ts',
    'web-ui/src/TaskOutcome.tsx', 'web-ui/src/task-outcome.ts', 'web-ui/src/secondary-refactor.css', 'web-ui/src/workspace-refactor.css', 'web-ui/src/main.tsx', 'web-ui/src/shell-library/DecisionInbox.tsx',
    'app/src/main/kotlin/dev/pocketengine/ui/web/PocketWebState.kt']) {
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

// Serialized into Chromium by locator.evaluate. Do not cancel/finish the source
// animations: the raw video must retain them. Vaul's finite entry/exit is 500ms.
// The independent deadline also bounds a background tab that stops producing RAF.
function waitForStableGeometry(node, { timeoutMs = 1600 } = {}) {
  return new Promise((resolve, reject) => {
    const started = performance.now();
    const ancestors = [];
    for (let parent = node; parent; parent = parent.parentElement) ancestors.push(parent);
    let frame = 0, frameCount = 0, stableFrames = 0, previous = null, bounds = null, active = [];
    const observed = new Set();
    const cleanup = () => { clearTimeout(deadline); if (frame) cancelAnimationFrame(frame); };
    const fail = error => { cleanup(); reject(error); };
    const deadline = setTimeout(() => fail(new Error(`Layout did not settle within ${timeoutMs}ms: ${JSON.stringify({ bounds, active })}`)), timeoutMs);
    const tick = () => {
      try {
        if (!node.isConnected) throw new Error('Layout target detached while waiting for its source animation');
        frameCount++;
        const rect = node.getBoundingClientRect();
        bounds = { x: rect.x, y: rect.y, width: rect.width, height: rect.height };
        active = [];
        for (const element of ancestors) for (const animation of element.getAnimations({ subtree: false })) {
          const endTime = animation.effect?.getComputedTiming().endTime;
          if (!Number.isFinite(endTime) || ['finished', 'idle'].includes(animation.playState)) continue;
          observed.add(animation);
          active.push({ name: animation.animationName || animation.transitionProperty || 'finite animation',
            playState: animation.playState, pending: animation.pending, endTime });
        }
        const unchanged = previous && ['x', 'y', 'width', 'height'].every(key => Math.abs(bounds[key] - previous[key]) <= 0.1);
        stableFrames = !active.length && unchanged ? stableFrames + 1 : 0;
        previous = bounds;
        if (stableFrames >= 2) {
          cleanup();
          resolve({ frames: frameCount, stableFrames, waitedMs: Math.round(performance.now() - started), finiteAnimationsObserved: observed.size });
        } else if (performance.now() - started >= timeoutMs) {
          fail(new Error(`Layout did not settle within ${timeoutMs}ms: ${JSON.stringify({ bounds, active })}`));
        } else frame = requestAnimationFrame(tick);
      } catch (error) { fail(error); }
    };
    // The first style/rect read above the next two stable samples observes a new
    // CSS entry animation even when Playwright's visible condition passed early.
    node.getBoundingClientRect();
    frame = requestAnimationFrame(tick);
  });
}

async function settle(page, milliseconds = 0, { motion = false } = {}) {
  await page.evaluate(async () => { await document.fonts.ready; await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))); });
  if (!motion) {
    // Scope this wait to the actual Vaul surfaces. Grok's intentional infinite
    // animation and unrelated notifications must not hold a still capture open.
    for (const surface of await page.locator('[data-vaul-drawer], [data-vaul-overlay]').all()) {
      if (await surface.isVisible()) await surface.evaluate(waitForStableGeometry);
    }
  }
  if (milliseconds) await page.waitForTimeout(milliseconds);
}

async function reachable(locator, { minSize = 24 } = {}) {
  await locator.waitFor({ state: 'visible' });
  assert.equal(await locator.count(), 1, 'The main target is ambiguous');
  await locator.evaluate(waitForStableGeometry);
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
  // A newly mounted source surface can request another font face after the
  // previous FontFaceSet.ready resolved. Load the declared bundled faces for
  // this capture, then measure the resulting layout; never accept fallback text.
  await page.evaluate(async () => {
    const requirements = [['Inter', 'PocketWorld'], ['Noto Sans SC', '工作任务项目'], ['JetBrains Mono', '0123456789']];
    let timer;
    try {
      await Promise.race([
        Promise.all(requirements.map(async ([family, sample]) => {
          const faces = await document.fonts.load(`400 16px "${family}"`, sample);
          if (!faces.length || faces.some(face => face.status !== 'loaded')) throw new Error(`Bundled font unavailable: ${family}`);
        })),
        new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('Bundled font loading exceeded 10 seconds')), 10000); }),
      ]);
      await document.fonts.ready;
    } finally { clearTimeout(timer); }
  });
  await settle(page, 0, { motion: Boolean(options.motion) });
  const mounted = [];
  for (const selector of selectors) {
    const locator = page.locator(selector).first();
    await locator.waitFor({ state: 'visible' });
    const bounds = await locator.boundingBox();
    assert(bounds && bounds.width > 0 && bounds.height > 0, `Mounted component has no layout: ${selector}`);
    mounted.push({ selector, count: await page.locator(selector).count(), bounds });
  }
  const layout = await page.evaluate(() => ({
    innerWidth, innerHeight, documentWidth: document.documentElement.scrollWidth, bodyWidth: document.body.scrollWidth,
    fonts: ['Inter', 'Noto Sans SC', 'JetBrains Mono'].map(family => ({ family, loaded: document.fonts.check(`400 16px "${family}"`) })),
    reducedMotion: matchMedia('(prefers-reduced-motion: reduce)').matches,
    scale: visualViewport?.scale ?? 1,
  }));
  assert(layout.documentWidth <= viewport.width + 1 && layout.bodyWidth <= viewport.width + 1, `Horizontal overflow at ${stage}: ${JSON.stringify(layout)}`);
  assert(layout.fonts.every(font => font.loaded), `Bundled font not ready: ${JSON.stringify(layout.fonts)}`);
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
    capturedAt: new Date().toISOString(), ...options.metadata });
}

async function navigate(page, name) {
  const roots = { '继续当前工作': '工作', '全部任务': '任务', '项目与文件': '项目' };
  if (roots[name]) {
    const nav = page.getByRole('tablist', { name: '主导航', exact: true });
    const target = nav.getByRole('tab', { name: roots[name], exact: true });
    await reachable(target, { minSize: 48 }); await target.click();
    assert.equal(await target.getAttribute('aria-selected'), 'true', 'Root navigation lost its selected page');
    const controlled = await target.getAttribute('aria-controls');
    assert(controlled && await page.locator(`[id="${controlled}"][role="tabpanel"]`).count() === 1, 'Root navigation has no actual controlled page');
    await settle(page); return;
  }
  const open = page.getByRole('button', { name: '全部工作', exact: true });
  await reachable(open, { minSize: 44 }); await open.click();
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
  await context.addInitScript(installFixture, { empty: emptySnapshot(), populated: populatedSnapshot(), emptyResult: emptyResultSnapshot(), commit });
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
    await page.getByRole('heading', { name: '先连接你的模型。', exact: true }).waitFor();
    const fonts = await page.evaluate(async () => {
      const faces = await Promise.all(['Inter', 'Noto Sans SC', 'JetBrains Mono'].map(family => document.fonts.load(`400 16px "${family}"`, '工作PocketWorld')));
      await document.fonts.ready;
      return faces.map(values => values.map(font => ({ family: font.family, status: font.status })));
    });
    assert(fonts.every(faces => faces.length > 0 && faces.every(face => face.status === 'loaded')), 'Actual production font faces not loaded');
    record.fontFaces = fonts;
    record.userAgent = await page.evaluate(() => navigator.userAgent);
    const allWork = page.getByTestId('all_work');
    record.checks.push({ stage: 'empty', allWork: await reachable(allWork, { minSize: 44 }),
      composer: await reachable(page.getByRole('textbox', { name: '工作输入', exact: true }), { minSize: 30 }) });
    await capture(page, viewport, '01-empty', ['.pw-start .pw-agent-avatar', '.afa-composer', '[data-testid="all_work"]']);
    // Keep the actual avatar breathing/blink in the raw video. No task advances.
    await page.waitForTimeout(1400);
    const editor = page.getByRole('textbox', { name: '工作输入', exact: true });
    const emptyDraft = '帮我整理项目资料，先给分类建议。';
    await editor.fill(emptyDraft);
    stage = `${viewport.width}:unconfigured-draft`;
    const unavailableSend = page.getByRole('button', { name: '请先连接模型', exact: true });
    await unavailableSend.waitFor({ state: 'visible' });
    assert(await unavailableSend.isDisabled(), 'An unconfigured model must not enable send');
    await editor.press('Enter');
    assert.equal(await editor.inputValue(), emptyDraft, 'Unconfigured Enter lost the unsent draft');
    const emptyEvidence = await page.evaluate(() => window.__POCKET_VISUAL_FIXTURE__.summary());
    assert.equal(emptyEvidence.scenario, 'empty');
    assert(!emptyEvidence.requests.some(request => request.action === 'composer.send'), 'Unconfigured Enter dispatched a send');
    record.checks.push({ stage: 'unconfigured-draft', sendLabel: '请先连接模型', sendDisabled: true,
      draftPreservedAfterEnter: true, sendDispatched: false });

    // This is an explicit change of isolated test input, not a connection/save
    // journey or an assertion that the original draft crossed a host session.
    await page.evaluate(() => window.__POCKET_VISUAL_FIXTURE__.setScenario('populated'));
    const send = page.getByRole('button', { name: '发送', exact: true });
    await send.waitFor({ state: 'visible' });
    const populatedEvidence = await page.evaluate(() => window.__POCKET_VISUAL_FIXTURE__.summary());
    assert.equal(populatedEvidence.scenario, 'populated');
    assert(populatedEvidence.revision > emptyEvidence.revision, 'Fixture transition did not publish a new state');
    const fixtureTransition = { from: emptyEvidence.scenario, to: populatedEvidence.scenario,
      fromRevision: emptyEvidence.revision, toRevision: populatedEvidence.revision,
      kind: 'explicit-isolated-fixture-injection', connectedOrSavedByUi: false };
    record.checks.push({ stage: 'fixture-scenario-change', ...fixtureTransition });
    await editor.fill(emptyDraft);
    // Explicit host configuration exercises layout only; the Android gate uses real IME insets.
    await page.evaluate(() => window.PocketWeb.hostConfiguration({ keyboardVisible: true }));
    await page.getByRole('tablist', { name: '主导航', exact: true }).waitFor({ state: 'hidden' });
    await reachable(editor, { minSize: 30 }); await reachable(send, { minSize: 44 });
    await page.evaluate(() => window.PocketWeb.hostConfiguration({ fontScale: 1 }));
    assert.equal(await page.locator('html').getAttribute('data-pocket-keyboard'), 'true', 'Unrelated host config cleared IME state');
    await page.evaluate(() => window.PocketWeb.hostConfiguration({ keyboardVisible: false }));
    await page.getByRole('tablist', { name: '主导航', exact: true }).waitFor({ state: 'visible' });
    record.checks.push({ stage: 'host-keyboard-layout', nativeSource: false, hiddenWhileReportedVisible: true, restored: true, inputAndSendReachable: true });
    const geometry = await reachable(send, { minSize: 44 });
    await page.mouse.move(geometry.x + geometry.width / 2, geometry.y + geometry.height / 2);
    await page.mouse.down();
    await page.waitForTimeout(110);
    assert.equal(await send.getAttribute('data-state'), 'pressed', 'Actual PressButton press state did not mount');
    await capture(page, viewport, '02-input-pressed', ['.afa-composer', '.pw-press[data-state="pressed"]'], { motion: true,
      metadata: { gesture: 'pointer down, held, then dragged out/cancelled; no send', fixtureTransition } });
    await page.mouse.move(Math.max(2, geometry.x - 20), Math.max(2, geometry.y - 40));
    await page.mouse.up();
    await settle(page, 300);
    assert.equal(await editor.inputValue(), emptyDraft, 'Cancelled press lost the input');

    const conversation = page.locator('.pw-conversation-scroll');
    const linkedTask = page.locator('.pw-linked-task').filter({ hasText: '整理项目资料' });
    await linkedTask.scrollIntoViewIfNeeded();
    const conversationDraft = '会议记录仍按日期保留。';
    await editor.fill(conversationDraft);
    await settle(page, 250);
    const reading = async () => conversation.evaluate(node => {
      const view = node.getBoundingClientRect();
      const first = [...node.querySelectorAll('[data-message-index]')].find(row => row.getBoundingClientRect().bottom > view.top + 1);
      return { top: node.scrollTop, height: node.clientHeight, scrollHeight: node.scrollHeight,
        anchor: first?.getAttribute('data-message-index') ?? null };
    });
    const sourceReading = await reading();
    await capture(page, viewport, '03-current-work', ['.pw-message-stream', '.pw-linked-task', '.afa-composer']);
    await allWork.click();
    await capture(page, viewport, '04-work-menu', ['.pw-work-drawer', '.pw-work-sidebar']);
    const workMenu = page.locator('.pw-work-drawer');
    assert.equal(await workMenu.getByRole('link', { name: /^(继续当前工作|全部任务|项目与文件)$/ }).count(), 0, 'The work drawer duplicates persistent root navigation');
    await workMenu.getByRole('button', { name: '关闭全部工作', exact: true }).click();
    await workMenu.waitFor({ state: 'hidden' });
    await navigate(page, '全部任务');
    const taskDecision = page.locator('.pw-priority-decisions [data-task-id="visual-fixture-task-review"]');
    const taskRow = taskDecision.getByRole('button', { name: '查看任务', exact: true });
    await reachable(taskDecision.getByRole('button', { name: '核对结果', exact: true }), { minSize: 48 });
    assert.equal(await page.locator('.pw-priority-decisions .ui-dic__row').count(), 2, 'Tasks waiting on the user are not grouped together');
    const rowBefore = await reachable(taskRow, { minSize: 44 });
    await capture(page, viewport, '05-task-list', ['.pw-priority-decisions', '.pw-workspace [role="tablist"][aria-label="主导航"]']);
    await taskRow.click();
    const drawer = page.locator('.pw-task-drawer');
    const primary = page.getByTestId('task-primary-action');
    const outcome = drawer.locator('.pw-task-outcome[aria-label="任务结果依据"]');
    const assertReadableResult = async (hasFeedback = false) => {
      await outcome.waitFor({ state: 'visible' });
      assert.equal(await outcome.getAttribute('data-result-source'), 'evidence', 'Review did not prefer the original model evidence');
      const document = outcome.locator('.pw-outcome-document');
      assert((await document.textContent()).includes('需求草稿保留为独立文件'), 'The task outcome omitted the original evidence body');
      assert.equal(await document.locator('textarea,input').count(), 0, 'Task evidence was replaced by a feedback form');
      const related = outcome.getByRole('button', { name: /^对话中的相关答复/ });
      assert.equal(await related.getAttribute('aria-expanded'), 'false', 'Related conversation competes with the result by default');
      await related.click();
      await outcome.locator('.pw-outcome-conversation').waitFor({ state: 'visible' });
      assert((await outcome.locator('.pw-outcome-conversation').textContent()).includes('需求草稿不会合并'), 'Disclosure lost the original linked answer');
      await related.click();
      await outcome.locator('.pw-outcome-conversation').waitFor({ state: 'hidden' });
      await drawer.locator('.pw-task-body').evaluate(node => { node.scrollTop = 0; });
      await settle(page);
      if (hasFeedback) {
        const precedesFeedback = await drawer.getByRole('textbox', { name: '本次结果反馈', exact: true }).evaluate(field => {
          const result = field.closest('.pw-task-review')?.querySelector('.pw-task-outcome');
          return !!result && !!(result.compareDocumentPosition(field) & Node.DOCUMENT_POSITION_FOLLOWING);
        });
        assert(precedesFeedback, 'The decision form precedes the actual result being reviewed');
        const resultBounds = await outcome.boundingBox();
        const feedbackBounds = await drawer.getByRole('textbox', { name: '本次结果反馈', exact: true }).boundingBox();
        assert(resultBounds && feedbackBounds && resultBounds.y + resultBounds.height <= feedbackBounds.y + 1,
          'The result body is not visually laid out before the feedback field');
      }
    };
    const assertOverview = async () => {
      await page.locator('.pw-task-drawer[data-task-layer="overview"]').waitFor({ state: 'visible' });
      assert.equal(await drawer.locator('.acc-review-queue').count(), 0, 'A single task contains the global review queue');
      assert.equal(await drawer.locator('.pw-scope-editor').count(), 0, 'Scope form is dumped into the task overview');
      assert.equal(await drawer.locator('[role="tree"][aria-label="Agent协作关系"],.afa-handoffagents').count(), 0, 'Ordinary TaskStore fixture unexpectedly mounts synthetic collaboration');
      await reachable(primary, { minSize: 44 });
    };
    await assertOverview();
    await assertReadableResult();
    await capture(page, viewport, '06-task-overview', ['.pw-task-drawer[data-task-layer="overview"]', '.pw-task-overview', '[data-testid="task-primary-action"]']);
    await drawer.getByRole('button', { name: '查看执行过程', exact: true }).click();
    await page.locator('.pw-task-drawer[data-task-layer="process"]').waitFor({ state: 'visible' });
    const expectedEvents = populatedSnapshot().state.tasks[0].raw.historyEventPage.entries;
    const eventSection = drawer.locator('.pw-task-events[aria-label="任务记录"]');
    await eventSection.waitFor({ state: 'visible' });
    const eventRows = eventSection.locator('.pw-task-event[data-event-position]');
    assert.equal(await eventRows.count(), expectedEvents.length, 'An empty operation window hid the readable task events');
    const eventPositions = await eventRows.evaluateAll(nodes => nodes.map(node => node.getAttribute('data-event-position')));
    assert.deepEqual(eventPositions, expectedEvents.map(row => String(row.position)), 'The process changed the original bounded event-window order');
    assert.equal(await drawer.locator('.pw-task-tools .ui-tcc').count(), 0, 'An empty operation window fabricated tool cards');
    for (const row of await eventRows.all()) await reachable(row.getByRole('button'), { minSize: 44 });
    const firstEvent = expectedEvents[0];
    const eventTrigger = eventSection.locator(`.pw-task-event[data-event-position="${firstEvent.position}"]`).getByRole('button');
    await eventTrigger.click();
    assert.equal(await eventTrigger.getAttribute('aria-expanded'), 'true', 'The original event Accordion did not expand');
    const eventCopy = eventSection.locator(`.pw-task-event[data-event-position="${firstEvent.position}"] .pw-task-event-copy`);
    await eventCopy.waitFor({ state: 'visible' });
    await eventCopy.evaluate(waitForStableGeometry);
    assert((await eventCopy.textContent()).includes(firstEvent.event.message), 'The expanded event lost its original readable message');
    const eventWindowCheck = { taskId: 'visual-fixture-task-review', section: 'events', positions: eventPositions,
      readableRecords: expectedEvents.length, expandedPosition: firstEvent.position, originalMessageVisible: true,
      operations: 0, source: 'isolated fixture TaskStore historyEventPage, not execution proof' };
    record.checks.push({ stage: 'task-process-event-window', ...eventWindowCheck });
    await capture(page, viewport, '07-task-process', ['.pw-task-drawer[data-task-layer="process"]', '.pw-task-events',
      `.pw-task-event[data-event-position="${firstEvent.position}"] .pw-task-event-copy`], { metadata: { eventWindowCheck } });
    await drawer.getByRole('button', { name: '返回任务概览', exact: true }).click();
    await assertOverview();
    const reviewFailure = '这次任务暂时无法核对，请重试。';
    await page.evaluate(message => window.__POCKET_VISUAL_FIXTURE__.rejectNextReview(message), reviewFailure);
    await primary.click();
    await page.locator('.pw-task-drawer[data-task-layer="review"]').waitFor({ state: 'visible' });
    const taskError = drawer.locator('.pw-task-local-error[data-task-id="visual-fixture-task-review"][role="alert"]');
    await taskError.filter({ hasText: reviewFailure }).waitFor({ state: 'visible' });
    await drawer.getByRole('button', { name: '返回任务概览', exact: true }).click();
    await drawer.getByRole('button', { name: '查看执行过程', exact: true }).click();
    await page.locator('.pw-task-drawer[data-task-layer="process"]').waitFor({ state: 'visible' });
    assert.equal((await taskError.textContent()).trim(), reviewFailure, 'Changing a task layer lost its unresolved command error');
    const errorGeometry = await taskError.boundingBox();
    assert(errorGeometry && errorGeometry.y >= 0 && errorGeometry.y + errorGeometry.height <= viewport.height,
      'The task error is outside the visible drawer');
    await drawer.getByRole('button', { name: '返回任务概览', exact: true }).click();
    await primary.click();
    await page.locator('.pw-task-drawer[data-task-layer="review"]').waitFor({ state: 'visible' });
    const feedback = drawer.getByRole('textbox', { name: '本次结果反馈', exact: true });
    await feedback.waitFor({ state: 'visible' });
    await assertReadableResult(true);
    assert.equal(await taskError.count(), 0, 'A successful explicit review retry left its old local error mounted');
    record.checks.push({ stage: 'controlled-review-rejection', expectedMessage: reviewFailure, taskId: 'visual-fixture-task-review',
      reviewErrorVisible: true, processErrorVisible: true, explicitRetryRecovered: true });
    await feedback.fill('分类方向合适，会议记录仍按日期保留。');
    assert.equal(await drawer.locator('.pw-scope-editor').count(), 0, 'Review opens the scope form before its explicit entry');
    const reviewFooter = {
      notReached: await reachable(drawer.getByRole('button', { name: '目标尚未达到', exact: true }), { minSize: 44 }),
      reached: await reachable(drawer.getByRole('button', { name: '确认达到目标', exact: true }), { minSize: 44 }),
    };
    record.checks.push({ stage: 'review-footer-after-retry', ...reviewFooter, submitted: false });
    record.checks.push({ stage: 'review-original-result', source: 'raw.evidence.modelSummary', bodyVisible: true,
      linkedAnswerVisible: true, resultBeforeFeedback: true, identity: 'isolated fixture, not real execution evidence' });
    await drawer.locator('.pw-task-body').evaluate(node => { node.scrollTop = 0; });
    await capture(page, viewport, '08-task-review', ['.pw-task-drawer[data-task-layer="review"]', '.pw-task-outcome', '.pw-protected-actions']);
    await drawer.getByRole('button', { name: '修订本次范围', exact: true }).click();
    await page.locator('.pw-task-drawer[data-task-layer="scope"]').waitFor({ state: 'visible' });
    const tools = drawer.getByRole('textbox', { name: '工具调用总上限', exact: true });
    const models = drawer.getByRole('textbox', { name: '模型调用总上限', exact: true });
    const confirmScope = drawer.getByRole('button', { name: '确认本次范围修订', exact: true });
    for (const field of [tools, models]) {
      assert.equal(await field.getAttribute('inputmode'), 'numeric');
      assert.equal(await field.getAttribute('pattern'), '[0-9]*');
    }
    assert.equal(await tools.inputValue(), '30'); assert.equal(await models.inputValue(), '16');
    await tools.fill('401'); assert(await confirmScope.isDisabled(), 'Out-of-range tool limit must block submission');
    await tools.fill('36'); await models.fill('513');
    assert(await confirmScope.isDisabled(), 'Out-of-range model limit must block submission');
    await models.fill('20'); assert(await confirmScope.isEnabled(), 'Valid reviewed draft should enable its control');
    record.checks.push({ stage: 'scope-deep-view', tools: '36', models: '20', invalidLimitsBlocked: true, submitted: false });
    await drawer.getByRole('button', { name: '返回当前处理', exact: true }).click();
    assert.equal(await feedback.inputValue(), '分类方向合适，会议记录仍按日期保留。', 'Deeper scope view lost the review draft');
    await drawer.getByRole('button', { name: '修订本次范围', exact: true }).click();
    assert.equal(await tools.inputValue(), '36', 'Returning to the scope discarded its ordinary draft');
    assert.equal(await models.inputValue(), '20', 'Returning to the scope discarded its ordinary draft');
    await page.evaluate(() => window.__POCKET_VISUAL_FIXTURE__.reviseTaskScope());
    const staleScope = drawer.locator('.pw-error').filter({ hasText: '任务已有新进展，请重新核对后再操作。' });
    await staleScope.waitFor({ state: 'visible' });
    assert.equal(await drawer.locator('.pw-scope-editor').count(), 0, 'A stale result still exposes editable scope controls');
    assert.equal(await confirmScope.count(), 0, 'A stale result still exposes its old authority action');
    await capture(page, viewport, '15-scope-stale', ['.pw-task-drawer[data-task-layer="scope"]', '.pw-error']);
    await drawer.getByRole('button', { name: '重新核对', exact: true }).click();
    await tools.waitFor({ state: 'visible' });
    assert.equal(await tools.inputValue(), '42', 'Re-reading did not use the current tool limit');
    assert.equal(await models.inputValue(), '24', 'Re-reading did not use the current model limit');
    assert(await confirmScope.isEnabled(), 'Fresh scope review did not restore its permitted action');
    assert.equal(await staleScope.count(), 0, 'Fresh scope review retained a stale refusal');
    record.checks.push({ stage: 'scope-stale-refusal', staleMessageVisible: true, staleControlsRemoved: true,
      freshTools: '42', freshModels: '24', rereadRecovered: true, submitted: false });
    await drawer.getByRole('button', { name: '返回当前处理', exact: true }).click();
    assert.equal(await feedback.inputValue(), '分类方向合适，会议记录仍按日期保留。', 'Re-reading scope lost the result feedback draft');
    await drawer.getByRole('button', { name: '返回任务概览', exact: true }).click();
    await assertOverview();
    await drawer.getByRole('button', { name: '返回原工作', exact: true }).click();
    await drawer.waitFor({ state: 'hidden' });
    const rowAfter = await reachable(taskRow, { minSize: 44 });
    assert(Math.abs(rowAfter.y - rowBefore.y) <= 2, 'Closing a task lost its task-list position');
    record.checks.push({ stage: 'return-to-list', rowBefore, rowAfter });
    await taskDecision.getByRole('button', { name: '核对结果', exact: true }).click();
    await page.locator('.pw-task-drawer[data-task-layer="review"]').waitFor({ state: 'visible' });
    await feedback.waitFor({ state: 'visible' });
    await assertReadableResult(true);
    await drawer.getByRole('button', { name: '返回原工作', exact: true }).click();
    await drawer.waitFor({ state: 'hidden' });
    await reachable(taskRow, { minSize: 48 });
    record.checks.push({ stage: 'task-priority-direct-review', directEntry: true, returnToOriginalList: true, submitted: false });

    await navigate(page, '继续当前工作');
    const returnedReading = await reading();
    assert.equal(returnedReading.anchor, sourceReading.anchor, 'Tasks navigation lost the source message anchor');
    assert(Math.abs(returnedReading.top - sourceReading.top) <= 2, 'Tasks navigation lost the source reading position');
    assert.equal(await editor.inputValue(), conversationDraft, 'Tasks navigation lost the composer draft');
    const beforeDirectReview = await page.evaluate(() => window.__POCKET_VISUAL_FIXTURE__.summary().requests.length);
    await reachable(linkedTask, { minSize: 44 }); await linkedTask.click();
    await page.locator('.pw-task-drawer[data-task-layer="review"]').waitFor({ state: 'visible' });
    await feedback.waitFor({ state: 'visible' });
    await assertReadableResult(true);
    const directRequests = await page.evaluate(start => window.__POCKET_VISUAL_FIXTURE__.summary().requests.slice(start)
      .filter(request => ['tasks.open', 'tasks.review'].includes(request.action)), beforeDirectReview);
    assert.deepEqual(directRequests.map(request => request.action), ['tasks.open', 'tasks.review'], 'Direct review must first open its original host task');
    assert(directRequests.every(request => request.taskId === 'visual-fixture-task-review' && request.ok), 'Direct review used a different task or failed');
    assert.equal(directRequests[0].requestNavigation, false, 'The App-local task open must not echo another navigation request');
    assert.equal(await drawer.getByRole('button', { name: '返回任务概览', exact: true }).count(), 0, 'Direct review invented an intermediate overview');
    await drawer.getByRole('button', { name: '返回原工作', exact: true }).click();
    await drawer.waitFor({ state: 'hidden' });
    const afterTaskReading = await reading();
    assert.equal(afterTaskReading.anchor, sourceReading.anchor, 'Task overlay lost the source message anchor');
    assert(Math.abs(afterTaskReading.top - sourceReading.top) <= 2, 'Task overlay lost the source reading position');
    assert.equal(await editor.inputValue(), conversationDraft);
    record.checks.push({ stage: 'return-to-current-work', sourceReading, returnedReading, afterTaskReading, draftPreserved: true,
      directReviewEntry: true, openBeforeReview: true, silentTaskFocus: true, closeInOneBack: true });
    await capture(page, viewport, '09-back-to-current-work', ['.pw-message-stream', '.afa-composer']);

    // A separate isolated missing-result input must stay visibly empty. The
    // controlled host refusal proves the real UI recovery path, not native policy.
    await page.evaluate(() => window.__POCKET_VISUAL_FIXTURE__.setScenario('emptyResult'));
    await navigate(page, '全部任务');
    await taskRow.click();
    await assertOverview();
    assert.equal(await outcome.getAttribute('data-result-source'), 'none');
    await outcome.getByRole('heading', { name: '还没有可读取的结果正文', exact: true }).waitFor();
    assert.equal(await outcome.locator('.pw-outcome-document,.pw-outcome-conversation').count(), 0, 'The missing result fabricated a body or linked answer');
    const missingResultFailure = '任务结果尚未载入，暂时不能核对结果。';
    await page.evaluate(message => window.__POCKET_VISUAL_FIXTURE__.rejectNextReview(message), missingResultFailure);
    await primary.click();
    await page.locator('.pw-task-drawer[data-task-layer="review"]').waitFor({ state: 'visible' });
    await taskError.filter({ hasText: missingResultFailure }).waitFor({ state: 'visible' });
    assert.equal(await outcome.getAttribute('data-result-source'), 'none');
    assert.equal(await feedback.count(), 0, 'A refused missing-result review exposed the decision form');
    assert.equal(await drawer.locator('.pw-task-footer').count(), 0, 'A refused missing-result review exposed an authority action');
    await capture(page, viewport, '16-task-without-result', ['.pw-task-drawer[data-task-layer="review"]', '.pw-outcome-empty', '.pw-task-local-error']);
    record.checks.push({ stage: 'missing-result-refusal', resultSource: 'none', emptyMessageVisible: true,
      expectedMessage: missingResultFailure, decisionFormAbsent: true, submitted: false,
      identity: 'controlled isolated fixture rejection; not Android execution evidence' });
    await drawer.getByRole('button', { name: '返回任务概览', exact: true }).click();
    await drawer.getByRole('button', { name: '返回原工作', exact: true }).click();
    await drawer.waitFor({ state: 'hidden' });
    await page.evaluate(() => window.__POCKET_VISUAL_FIXTURE__.setScenario('populated'));

    await navigate(page, '项目与文件');
    const firstFile = page.locator('.pw-file-list .pw-file-object').first();
    const fileGeometry = await reachable(firstFile, { minSize: 44 });
    assert(fileGeometry.y <= 240, `First project file starts too low: ${fileGeometry.y}px`);
    assert.equal(await page.locator('.pw-projects-page h1,.pw-projects-page h2').filter({ hasText: '新产品计划' }).count(), 0, 'Project repeats its app-bar title in page content');
    record.checks.push({ stage: 'project-first-object', firstFile: fileGeometry, limit: 240 });
    await capture(page, viewport, '10-project-files', ['.pw-projects-page', '.pw-file-list .pw-file-object']);
    await page.getByRole('button', { name: '文件显示方式', exact: true }).click();
    const treeViewOption = page.locator('label.pw-radio-option').filter({ has: page.getByRole('radio', { name: '目录层级', exact: true }) });
    await reachable(treeViewOption, { minSize: 44 });
    await treeViewOption.click();
    await page.getByRole('dialog', { name: '文件显示方式', exact: true, includeHidden: true }).waitFor({ state: 'hidden' });
    await capture(page, viewport, '11-project-tree', ['.pw-file-tree', '[role="tree"]']);

    await navigate(page, '设置与连接');
    const settingsSearch = page.getByRole('searchbox', { name: '搜索设置', exact: true });
    record.checks.push({ stage: 'settings-search-dom-semantics', role: 'searchbox', accessibleName: '搜索设置',
      geometry: await reachable(settingsSearch),
      limitation: 'Chromium DOM accessible-name evidence; native uiautomator hint export and TalkBack behavior are separate evidence' });
    await capture(page, viewport, '17-settings', ['.pw-secondary-header', '.pw-settings-frequent', '.pw-settings-groups']);
    await page.getByRole('button', { name: /^模型与连接/ }).click();
    const sourceModelEntry = page.locator('.pw-settings-groups').getByRole('button', { name: /^选择模型/ });
    await reachable(sourceModelEntry, { minSize: 44 });
    await sourceModelEntry.click();
    const modelCatalogue = page.locator('.pw-model-list[data-model-layer="list"]');
    const connectionDirectory = page.locator('.pw-model-connections[data-model-layer="connections"]');
    await modelCatalogue.waitFor({ state: 'visible' });
    assert.equal(await page.getByLabel('API 密钥', { exact: true }).count(), 0, 'Model catalogue renders the secret editor before selection');
    assert.equal(await modelCatalogue.locator('.pw-model-current strong').filter({ hasText: /^gpt-4\.1-mini$/ }).count(), 1, 'The current model identity is not singular');
    assert(!(await modelCatalogue.textContent()).includes('https://api.example.invalid/v1'), 'The model catalogue is mixed with connection endpoint editing');
    const modelSearch = page.getByRole('textbox', { name: '搜索模型', exact: true });
    const modelOptions = page.getByRole('listbox', { name: '选择工作模型', exact: true });
    await modelSearch.fill('reasoning');
    assert.equal(await modelOptions.getByRole('option').count(), 1, 'Model search did not narrow the actual catalogue');
    await modelSearch.fill('');
    await capture(page, viewport, '12-model-catalogue', ['.pw-model-list', '.pw-model-current', '.cb-model2-options']);
    await modelSearch.fill('gpt');
    await page.getByRole('button', { name: '管理连接', exact: true }).click();
    await connectionDirectory.waitFor({ state: 'visible' });
    await modelCatalogue.waitFor({ state: 'hidden' });
    await capture(page, viewport, '18-connection-management', ['.pw-model-connections[data-model-layer="connections"]']);
    const editConnection = page.getByRole('button', { name: '编辑当前 API 连接', exact: true });
    await reachable(editConnection, { minSize: 44 }); await editConnection.click();
    const url = page.getByRole('textbox', { name: '接口地址', exact: true });
    await url.waitFor({ state: 'visible' });
    assert.equal(await url.inputValue(), 'https://api.example.invalid/v1');
    const key = page.getByLabel('API 密钥', { exact: true });
    assert.equal(await key.inputValue(), '', 'Saved credential leaked into the editor');
    const advanced = page.getByRole('button', { name: /^高级设置/ });
    assert.equal(await advanced.getAttribute('aria-expanded'), 'false', 'Advanced settings are initially expanded');
    await capture(page, viewport, '13-model-editor', ['.pw-model-editor', '.pw-form-grid', '.pw-model-advanced']);
    const provider = page.locator('.pw-selection-field').filter({ has: page.getByText('接口协议', { exact: true }) }).getByRole('button');
    await provider.click();
    await page.getByRole('option', { name: 'OpenAI Responses', exact: true }).click();
    const modelDraftUrl = 'https://draft.example.invalid/v1';
    await url.fill(modelDraftUrl);
    await advanced.click();
    const outputLimit = page.getByRole('textbox', { name: '每次回复的输出上限', exact: true });
    assert(await outputLimit.isEnabled(), 'Responses output limit must be editable');
    await outputLimit.fill('8192');
    const toggle = page.getByRole('switch', { name: '允许按需发送截图' });
    await toggle.scrollIntoViewIfNeeded(); await reachable(toggle, { minSize: 44 });
    await toggle.click();
    await capture(page, viewport, '14-model-advanced', ['.pw-model-advanced', '[role="switch"]']);
    const modelDraftScreenshots = await toggle.getAttribute('aria-checked');
    await key.fill('fixture-unsaved-secret');
    const backConnections = page.getByRole('button', { name: '返回连接管理', exact: true });
    await backConnections.scrollIntoViewIfNeeded(); await backConnections.click();
    await connectionDirectory.waitFor({ state: 'visible' });
    await editConnection.click();
    assert.equal(await key.inputValue(), '', 'Cancelled credential draft survived a fresh editor');
    assert.equal(await url.inputValue(), modelDraftUrl, 'Page back lost the ordinary model URL draft');
    assert((await provider.textContent()).includes('OpenAI Responses'), 'Page back lost the provider draft');
    assert.equal(await advanced.getAttribute('aria-expanded'), 'false', 'Re-entering an editor opens the advanced panel automatically');
    await advanced.click();
    assert.equal(await outputLimit.inputValue(), '8192', 'Page back lost the advanced output draft');
    assert.equal(await toggle.getAttribute('aria-checked'), modelDraftScreenshots, 'Page back lost the screenshot preference draft');
    await key.fill('fixture-unsaved-hardware-back-secret');
    const nativeBack = async () => {
      const handled = await page.evaluate(() => window.PocketWeb.handleBack());
      assert.equal(handled, true, 'The real PocketWeb back handler did not consume its subpage');
      await settle(page, 80);
    };
    await nativeBack();
    await connectionDirectory.waitFor({ state: 'visible' });
    await editConnection.click();
    assert.equal(await key.inputValue(), '', 'Host back kept a secret in the next editor mount');
    assert.equal(await url.inputValue(), modelDraftUrl, 'Host back lost the ordinary model URL draft');
    await advanced.click();
    assert.equal(await outputLimit.inputValue(), '8192', 'Host back lost the advanced output draft');
    await nativeBack();
    await connectionDirectory.waitFor({ state: 'visible' });
    await nativeBack();
    await modelCatalogue.waitFor({ state: 'visible' });
    assert.equal(await modelSearch.inputValue(), 'gpt', 'Returning from connection management lost the model search');
    await nativeBack();
    await settingsSearch.waitFor({ state: 'visible' });
    await sourceModelEntry.waitFor({ state: 'visible' });
    record.checks.push({ stage: 'model-draft-and-host-back', urlDraftPreserved: true, providerDraftPreserved: true,
      advancedOutput: '8192', screenshotDraftPreserved: true, modelSearchPreserved: true,
      secretClearedOnPageBack: true, secretClearedOnHostBack: true,
      hostBack: 'window.PocketWeb.handleBack: edit → connections → catalogue → original settings root', saved: false });
    await sourceModelEntry.click();
    await modelCatalogue.waitFor({ state: 'visible' });
    await page.keyboard.press('Tab');
    await page.locator('.pw-secondary-header').getByRole('button', { name: '返回', exact: true }).focus();
    const focus = await page.locator('.pw-secondary-header').getByRole('button', { name: '返回', exact: true }).evaluate(node => {
      const css = getComputedStyle(node);
      return { visible: node.matches(':focus-visible'), outlineStyle: css.outlineStyle, outlineWidth: css.outlineWidth, boxShadow: css.boxShadow };
    });
    assert(focus.visible && (parseFloat(focus.outlineWidth) >= 1 && focus.outlineStyle !== 'none' || focus.boxShadow !== 'none'), 'Keyboard focus has no visible indicator');
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await settle(page);
    assert(await page.evaluate(() => matchMedia('(prefers-reduced-motion: reduce)').matches));
    await reachable(page.getByRole('button', { name: '管理连接', exact: true }), { minSize: 44 });
    record.checks.push({ stage: 'keyboard-and-reduced-motion', focus, reducedMotion: true,
      limitation: 'Browser preference and reachable focus only; Android accessibility and subjective motion review remain separate' });
    await nativeBack();
    await settingsSearch.waitFor({ state: 'visible' });
    const directModelEntry = page.getByRole('button', { name: /^当前模型/ });
    await reachable(directModelEntry, { minSize: 44 }); await directModelEntry.click();
    await modelCatalogue.waitFor({ state: 'visible' });
    await nativeBack();
    await settingsSearch.waitFor({ state: 'visible' });
    assert.equal(await modelCatalogue.isVisible(), false, 'The direct model entry did not return to its original settings root');
    await page.locator('.pw-secondary-header').getByRole('heading', { name: '设置', exact: true }).waitFor();
    record.checks.push({ stage: 'settings-model-shortcut', directEntry: true, returnToOriginalRoot: true,
      hostBack: 'window.PocketWeb.handleBack: root → catalogue → root', saved: false });
    await nativeBack();
    await page.getByRole('tablist', { name: '主导航', exact: true }).waitFor();
    stage = `${viewport.width}:team-workspace`;
    await navigate(page, '团队协作');
    const teamList = page.locator('.pw-team-workspace[data-team-layer="list"]');
    const teamDetail = page.locator('.pw-team-workspace[data-team-layer="detail"]');
    await teamList.waitFor({ state: 'visible' });
    const teamSearch = page.getByRole('searchbox', { name: '搜索共同工作', exact: true });
    await teamSearch.fill('发布');
    const sharedWork = teamList.locator('.pw-object-row').filter({ hasText: '整理发布前资料' });
    await reachable(sharedWork, { minSize: 48 });
    await capture(page, viewport, '19-team-workspace', ['.pw-team-tabs', '.pw-object-list']);
    await sharedWork.click();
    await teamDetail.waitFor({ state: 'visible' });
    await teamDetail.getByText('测试记录：已列出待核对资料，尚未提交任何修改。', { exact: true }).waitFor();
    await nativeBack();
    assert.equal(await teamSearch.inputValue(), '发布', 'Team detail lost its original search');
    await teamList.getByRole('tab', { name: '团队', exact: true }).click();
    await teamList.locator('.pw-object-row').filter({ hasText: '项目伙伴' }).click();
    await teamDetail.getByRole('region', { name: '当前已读取成员' }).waitFor();
    await capture(page, viewport, '20-team-detail', ['.pw-team-reading', '.pw-team-members']);
    await nativeBack();
    assert.equal(await teamList.getByRole('tab', { name: '团队', exact: true }).getAttribute('aria-selected'), 'true', 'Team detail lost its list category');
    await nativeBack();
    stage = `${viewport.width}:memory-reading`;
    await navigate(page, '记忆');
    await page.locator('.pw-memory-record').first().waitFor();
    assert.equal(await page.locator('.pw-memory-record').count(), 2, 'Native-shaped memory list is not projected');
    const memoryDisclosure = page.getByRole('button', { name: /会议记录按日期保留/ });
    assert.equal(await memoryDisclosure.getAttribute('aria-expanded'), 'false', 'Memory should start as a readable list');
    await memoryDisclosure.click();
    await page.locator('.pw-memory-reading').getByText('需求草稿先保留独立文件，核对后再决定是否合并。', { exact: false }).waitFor();
    await page.locator('.pw-memory-reading').getByText('测试对话：项目资料整理', { exact: true }).waitFor();
    await capture(page, viewport, '21-memory-reading', ['.pw-memory-category', '.pw-memory-reading']);
    await page.getByRole('button', { name: '刷新当前状态', exact: true }).click();
    await page.locator('.pw-memory-record').first().waitFor();
    record.checks.push({ stage: 'team-and-memory-reading', teamSearchRestored: true, teamCategoryRestored: true,
      memorySource: "moduleResults['memory.list'].records; layer/source/ownerId/spaceId", memoryRecords: 2,
      synthetic: true, mutations: false });
    record.fixture = await page.evaluate(() => window.__POCKET_VISUAL_FIXTURE__.summary());
    assert(record.fixture.requests.some(request => request.action === 'app.ready'), 'No actual bridge handshake');
    assert(!record.fixture.requests.some(request => ['composer.send', 'tasks.confirm', 'tasks.scope', 'settings.saveModel', 'settings.testModel', 'projects.create', 'projects.newFile'].includes(request.action)), 'A business action was dispatched');
    assert.equal(record.fixture.violations.length, 0);
    assert(record.fixture.requests.filter(request => request.action === 'tasks.open').every(request => request.requestNavigation === false), 'An App-owned task open requested external navigation');
    assert.equal(record.fixture.expectedRejections.length, 2, 'Both controlled host rejections must be exercised exactly once');
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
