// Independent local production-bundle regression. All records below are synthetic;
// the host refuses every send, approval, control and persistence action.
// Run: node web-ui/tests/team-continuity-ui.mjs /absolute/qa-output
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { chromium, expect } from '@playwright/test';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const bundle = path.join(repo, 'app/src/main/assets/pocket-ui');
const output = path.resolve(process.argv[2] || path.join(repo, '../qa-team'));
const mainId = 'fixture-run-18', discussionId = 'fixture-discussion';
const longName = '跨设备资料与引用真实性复核伙伴';

function snapshot(dark) {
  return {
    state: {
      modelName: 'GLOBAL_MODEL_MUST_NOT_FILL_MEMBER', baseUrl: '', hasApiKey: false, screenshots: false,
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
      appearance: { mode: dark ? 'dark' : 'light', dark },
    },
    composer: { ready: true, key: 'team-local-fixture.empty', epoch: 1, revision: 1, text: '', mode: 'queue',
      conversationId: '', saving: false, scrollIndex: 0, scrollOffset: 0,
      anchorMessage: '', observedMessage: '', newReplies: false, sourceRefs: [],
      voiceText: '', voiceInputId: '', pending: [] },
    phoneDraft: { text: '', revision: 0, epoch: 1, phone: false, mode: 'background' },
    view: { section: null },
  };
}

function records() {
  const members = [
    { id: 'member-a', name: '李青', role: '来源核对', assignment: '旧分工：整理全部参考目录', status: 'running',
      model: 'CONFIGURED_MODEL_MUST_NOT_OVERRIDE_FROZEN',
      memberModelSelection: { memberRoute: { requestedAlias: '研究模型 · 冻结配置' }, memberTarget: { kind: 'exact', modelId: 'source-model' } } },
    { id: 'member-b', name: '李青', role: '结论复核', assignment: '按原文逐条核对结论', status: 'waiting_dependency' },
    { id: 'member-c', name: longName, role: '跨设备来源核对', assignment: '旧分工：检查附件', status: 'running',
      model: 'CONFIGURED_MODEL_MUST_NOT_OVERRIDE_GROUP',
      memberModelSelection: { memberTarget: { kind: 'group', groupId: 'frozen-group' },
        orderedCandidates: [{ modelId: 'CANDIDATE_IS_NOT_EXECUTED_MODEL' }] } },
    { id: 'member-d', name: '旧版资料复核', role: '历史资料复核', assignment: '已交回旧版复核结果', status: 'completed', exited: true,
      memberModelSelection: { memberTarget: { kind: 'exact', modelId: 'review-model-v1' } } },
  ];
  const items = [
    { workItemId: 'item-old', memberId: 'member-a', title: '整理旧版来源索引', status: 'completed',
      instruction: '保留旧版引用作为核对依据。', acceptance: '每条旧引用可定位。', dependsOn: [] },
    { workItemId: 'item-active-a', memberId: 'member-a', title: '核对最新发布来源', status: 'running',
      instruction: '逐条核对最新发布资料中的原始链接，记录原文位置。', acceptance: '每条结论都关联原始链接和原文段落。', dependsOn: ['item-old'] },
    { workItemId: 'item-wait-b', memberId: 'member-b', title: '等待来源完成再核对结论', status: 'waiting_dependency',
      instruction: '只在前置来源核对完成后对照总结结论。', acceptance: '指出结论与原文之间的差异。', dependsOn: ['item-active-a'] },
    { workItemId: 'item-active-c', memberId: 'member-c', title: '复核跨设备长文件名与附件来源的连续对应关系', status: 'running',
      instruction: '核对桌面和手机上的附件名称与原始来源，避免同名内容串线。', acceptance: '每份附件都可回到其原始来源。', dependsOn: [] },
  ];
  const main = { id: mainId, teamName: '资料核对小组', goal: '资料核对 · 发布协作', status: 'running', generation: 1, revision: 1,
    leaderId: 'member-a', members, workPlan: { items },
    summary: '已整理第一批来源。\n\n当前正在核对最新发布资料与跨设备附件，结论复核等待前置工作完成。\n\n这段内容只用于 Web 测试，未执行任何真实任务。', summaryStale: false };
  const discussion = { id: discussionId, teamName: '讨论小组', goal: '资料方向讨论', status: 'paused', generation: 1, revision: 1,
    leaderId: 'member-a', members: members.slice(0, 2).map(member => ({ ...member, status: 'paused' })), summary: '普通讨论记录，没有工作项计划。' };
  const runs = Array.from({ length: 30 }, (_, index) => ({ id: `fixture-run-${String(index + 1).padStart(2, '0')}`,
    goal: `资料核对 ${String(index + 1).padStart(2, '0')} · 发布前原文与引用整理`, status: 'paused', generation: 1, revision: 1 }));
  runs[17] = { id: main.id, goal: main.goal, status: main.status, generation: 1, revision: 1 };
  runs.push({ id: discussion.id, goal: discussion.goal, status: discussion.status, generation: 1, revision: 1 });
  return { catalog: { teams: [], runs }, details: { [mainId]: main, [discussionId]: discussion },
    messages: { [mainId]: [{ messageId: 'message-b', memberId: 'member-b', role: 'assistant',
      kind: 'discussion', content: '请逐条核对第二组来源；同名成员各有独立收件目标。' }], [discussionId]: [] } };
}

// The v1 handshake, document token and read-lease shapes follow the native bridge.
// This synthetic host is installed into this isolated document only.
function installFixture(options) {
  const token = 'team-local-read-only-document'; window.__pocketDocumentToken = token;
  const copy = value => JSON.parse(JSON.stringify(value));
  const state = copy(options.snapshot), data = copy(options.records);
  const requests = [], violations = [], handles = new Map(), receipts = new Map();
  let documentId = null, revision = 0, serial = 0;
  const safe = ['app.ready', 'app.refresh', 'connections.enter', 'connections.leave', 'teams.snapshot',
    'teams.readRun', 'teams.readDefinition', 'teams.closeReading', 'composer.reading', 'tasks.refresh', 'projects.refresh'];
  const mutations = ['teams.sendMessage', 'teams.answerAssignment', 'teams.control', 'teams.reviewStart',
    'teams.start', 'teams.save', 'teams.reviewRemove', 'teams.remove', 'teams.probeMember'];
  const receive = value => window.PocketWeb.receive(value);
  const publish = () => { state.capabilities = { commands: [...safe, ...mutations] }; receive({ v: 1, kind: 'state', revision: ++revision, snapshot: copy(state) }); };
  const badge = () => {
    const node = document.createElement('div'); node.id = 'team-web-fixture-identity'; node.setAttribute('aria-hidden', 'true');
    node.textContent = 'Web 测试数据 · 非原生实机';
    Object.assign(node.style, { position: 'fixed', inset: 'auto 0 0', zIndex: '2147483647', pointerEvents: 'none',
      background: options.snapshot.state.appearance.dark ? '#292929' : '#ededed',
      color: options.snapshot.state.appearance.dark ? '#ddd' : '#555', font: '10px/14px sans-serif', textAlign: 'center', padding: '2px 4px' });
    document.body.append(node);
  };
  document.addEventListener('DOMContentLoaded', badge, { once: true });
  window.PocketNative = { postMessage(raw) { queueMicrotask(() => {
    let request, value = {}, failure = null;
    try {
      request = JSON.parse(raw);
      if (request.v !== 1 || typeof request.id !== 'string' || !request.id || request.hostDocumentToken !== token || !/^[a-f0-9]{32}$/.test(request.documentId || '')) throw new Error('Invalid fixture document/request identity');
      const prior = receipts.get(request.id);
      if (prior) { if (prior.request !== raw) throw new Error('Request ID reused with different arguments'); receive(prior.response); return; }
      if (!request.args || typeof request.args !== 'object' || Array.isArray(request.args)) throw new Error('Invalid args');
      if (request.action === 'app.ready') {
        if (request.args.documentId !== request.documentId || documentId && documentId !== request.documentId) throw new Error('Handshake document mismatch');
        documentId = request.documentId; value = { ready: true, source: 'library-web' }; publish();
      } else {
        if (!documentId || request.documentId !== documentId) throw new Error('Stale fixture document');
        if (!safe.includes(request.action)) throw new Error(`Forbidden synthetic business action: ${request.action}`);
        switch (request.action) {
          case 'app.refresh': case 'tasks.refresh': case 'projects.refresh': publish(); break;
          case 'composer.reading': if (request.args.key !== state.composer.key) throw new Error('Stale composer reading'); break;
          case 'connections.enter': if (request.args.section !== 'teams') throw new Error('Unexpected section'); state.view.section = 'teams'; publish(); break;
          case 'connections.leave': state.view.section = null; break;
          case 'teams.snapshot': if (state.view.section !== 'teams') throw new Error('Team catalog outside original section'); value = copy(data.catalog); break;
          case 'teams.readRun': {
            if (state.view.section !== 'teams' || !data.details[request.args.runId]) throw new Error('Unknown original run');
            const viewHandle = `synthetic-run-${++serial}`; handles.set(viewHandle, request.args.runId);
            value = { reading: { kind: 'run', viewHandle, run: copy(data.details[request.args.runId]), messages: copy(data.messages[request.args.runId]) } }; break;
          }
          case 'teams.closeReading': value = { closed: handles.delete(request.args.viewHandle || request.args.reviewHandle) }; break;
          default: throw new Error(`Unexpected read action: ${request.action}`);
        }
      }
    } catch (error) { failure = String(error.message || error); violations.push(failure); }
    if (!request?.id) return;
    const response = { v: 1, kind: 'result', id: request.id, ok: !failure,
      ...(failure ? { error: { code: 'fixture_not_accepted', message: failure } } : { value }) };
    receipts.set(request.id, { request: raw, response }); requests.push({ action: request.action, args: request.args, ok: !failure }); receive(response);
  }); } };
  window.__TEAM_FIXTURE__ = {
    reorder() { const run = data.details[options.mainId]; run.members = [run.members[2], run.members[1], run.members[3], run.members[0]]; run.workPlan.items.reverse(); run.revision++; },
    // PocketWebBridge.restoreTaskWorkspace -> enterConnection -> leaveConnection
    // clears teamPages before publishing the new view.resumeRevision.
    resume() { handles.clear(); state.view.resumeRevision = (state.view.resumeRevision || 0) + 1; publish(); },
    summary() { return { requests: copy(requests), violations: [...violations], remainingLeases: handles.size, persistence: 'none' }; },
  };
}

async function serve() {
  const root = await fs.realpath(bundle);
  const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
    '.json': 'application/json', '.ttf': 'font/ttf', '.woff2': 'font/woff2', '.svg': 'image/svg+xml' };
  const server = http.createServer(async (request, response) => {
    try {
      assert(['GET', 'HEAD'].includes(request.method));
      const relative = decodeURIComponent(new URL(request.url, 'http://127.0.0.1').pathname).replace(/^\/+/, '') || 'index.html';
      assert(!relative.includes('\\') && !relative.split('/').includes('..'));
      const file = await fs.realpath(path.join(root, relative)); assert(file.startsWith(root + path.sep));
      const content = await fs.readFile(file); response.writeHead(200, { 'Content-Type': mime[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store' }); response.end(request.method === 'HEAD' ? undefined : content);
    } catch { response.writeHead(404); response.end('Asset unavailable'); }
  });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  return { server, origin: `http://127.0.0.1:${server.address().port}` };
}

async function settle(page) {
  await page.evaluate(async () => { await document.fonts.ready; await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))); });
  await expect(page.locator('.pw-team-loading [role="progressbar"]')).toHaveCount(0);
}

async function reachable(locator, minimum = 44) {
  await locator.scrollIntoViewIfNeeded();
  await expect(locator).toBeEnabled();
  const value = await locator.evaluate(node => {
    const b = node.getBoundingClientRect(), hit = document.elementFromPoint(b.x + b.width / 2, b.y + b.height / 2);
    return { x: b.x, y: b.y, width: b.width, height: b.height, innerWidth, innerHeight, hit: node === hit || node.contains(hit) };
  });
  assert(value.width >= minimum && value.height >= minimum, `Touch target smaller than ${minimum}px: ${JSON.stringify(value)}`);
  assert(value.x >= -1 && value.y >= -1 && value.x + value.width <= value.innerWidth + 1 && value.y + value.height <= value.innerHeight + 1, `Control clipped: ${JSON.stringify(value)}`);
  assert(value.hit, `Control obstructed: ${JSON.stringify(value)}`);
  return value;
}

await fs.mkdir(output, { recursive: true });
const bundleIndex = await fs.readFile(path.join(bundle, 'index.html'), 'utf8');
const evidence = { kind: 'team-continuity-production-web', identity: 'Web 测试数据；无原生执行、真实消息、持久化或外部网络。',
  startedAt: new Date().toISOString(), bundle, bundleIndexSha256: createHash('sha256').update(bundleIndex).digest('hex'),
  assets: [...bundleIndex.matchAll(/(?:src|href)="\.\/(assets\/[^\"]+)"/g)].map(match => match[1]), checks: [], captures: [], errors: [] };
const { server, origin } = await serve();
const browser = await chromium.launch({ headless: true, args: ['--no-sandbox', '--disable-background-networking'] });
let page, context, phase = 'initializing';
const watchdog = setTimeout(() => { console.error(`Bounded team test timed out in ${phase}`); process.exit(2); }, 150_000);
const note = (name, detail = {}) => evidence.checks.push({ name, passed: true, ...detail });
try {
  for (const variant of [{ width: 360, height: 800, dark: false }, { width: 360, height: 800, dark: true }, { width: 800, height: 360, dark: false }]) {
    const prefix = `${variant.width}x${variant.height}-${variant.dark ? 'dark' : 'light'}`;
    phase = `${prefix} start`;
    context = await browser.newContext({ viewport: { width: variant.width, height: variant.height }, deviceScaleFactor: 1, locale: 'zh-CN', colorScheme: variant.dark ? 'dark' : 'light', reducedMotion: 'reduce', serviceWorkers: 'block', hasTouch: true });
    await context.route('**/*', route => new URL(route.request().url()).origin === origin ? route.continue() : route.abort());
    await context.addInitScript(installFixture, { snapshot: snapshot(variant.dark), records: records(), mainId });
    page = await context.newPage(); page.setDefaultTimeout(6500);
    page.on('pageerror', error => evidence.errors.push({ viewport: prefix, source: 'page', message: error.message }));
    page.on('console', message => { if (message.type() === 'error') evidence.errors.push({ viewport: prefix, source: 'console', message: message.text() }); });
    const workspace = page.locator('.pw-team-workspace'), scroll = page.locator('.pw-secondary-scroll');
    const member = id => page.locator(`[data-team-member-id="${id}"]`);
    const item = id => page.locator(`[data-work-item-id="${id}"]`);
    const trigger = row => row.locator(':scope > h3 > button');
    const search = page.getByRole('searchbox', { name: '搜索当前团队记录', exact: true });
    const top = () => scroll.evaluate(node => node.scrollTop);
    const position = async value => { await scroll.evaluate((node, y) => { node.scrollTop = y; }, value); await settle(page); return top(); };
    const select = label => page.locator('.pw-selection-field').filter({ has: page.locator('.pw-field-label').filter({ hasText: new RegExp(`^${label}$`) }) });
    const selected = label => select(label).locator('select').inputValue();
    const shot = async name => {
      await settle(page);
      await page.mouse.move(variant.width - 2, 60);
      const layout = await page.evaluate(() => ({ width: innerWidth, htmlWidth: document.documentElement.scrollWidth, bodyWidth: document.body.scrollWidth,
        scrollWidth: document.querySelector('.pw-secondary-scroll').scrollWidth, clientWidth: document.querySelector('.pw-secondary-scroll').clientWidth,
        scrollTop: document.querySelector('.pw-secondary-scroll').scrollTop, theme: document.documentElement.dataset.theme }));
      assert(layout.htmlWidth <= variant.width + 1 && layout.bodyWidth <= variant.width + 1 && layout.scrollWidth <= layout.clientWidth + 1, `Horizontal overflow in ${prefix}/${name}: ${JSON.stringify(layout)}`);
      const file = `${prefix}-${name}.png`; await page.screenshot({ path: path.join(output, file), animations: 'disabled' }); evidence.captures.push({ file, layout, synthetic: true });
    };
    await page.goto(origin); await page.waitForFunction(() => document.documentElement.dataset.pocketUi === 'ready');
    await page.getByTestId('all_work').click();
    await page.locator('.pw-work-drawer').getByRole('link', { name: '团队', exact: true }).click();
    await page.locator('.pw-work-drawer').waitFor({ state: 'hidden' }); await settle(page);
    await expect(workspace).toHaveAttribute('data-team-view', 'list:runs');
    await search.fill('资料'); await settle(page);
    const listRow = page.locator(`[data-team-list-id="${mainId}"]`);
    await reachable(listRow); await listRow.focus();
    const listBefore = await top(); assert(listBefore > 500, `Test must enter from the middle of a real scrollable list: ${listBefore}`);
    phase = `${prefix} open detail`;
    await listRow.click(); await expect(workspace).toHaveAttribute('data-team-view', `run:${mainId}`); await settle(page);
    assert.equal(await top(), 0, 'First detail entry must start at its own top');
    assert.equal(await page.locator('[data-team-member-id]').count(), 4);
    assert.equal(await page.locator('[data-work-item-id]').count(), 4);
    for (const id of ['member-a', 'member-b', 'member-c', 'member-d']) await expect(trigger(member(id))).toHaveAttribute('aria-expanded', 'false');
    await expect(member('member-a').locator('.pw-team-member-task')).toHaveText('正在做：核对最新发布来源');
    await expect(member('member-b').locator('.pw-team-member-task')).toHaveText('按原文逐条核对结论');
    await expect(member('member-c').locator('.pw-team-member-task')).toHaveText('正在做：复核跨设备长文件名与附件来源的连续对应关系');
    await expect(member('member-a').locator('.pw-team-member-meta')).toHaveText('研究模型 · 冻结配置 · 工作中');
    await expect(member('member-b').locator('.pw-team-member-meta')).toHaveText('等待前置分工');
    await expect(member('member-c').locator('.pw-team-member-meta')).toHaveText('模型组 · 工作中');
    await expect(member('member-d').locator('.pw-team-member-meta')).toHaveText('review-model-v1 · 已退出');
    assert(!(await workspace.textContent()).includes('MUST_NOT') && !(await workspace.textContent()).includes('CANDIDATE_IS_NOT'), 'Unexecuted/global model must never masquerade as member model');
    for (const [id, title, assigned] of [['item-old', '整理旧版来源索引', '李青'], ['item-active-a', '核对最新发布来源', '李青'], ['item-wait-b', '等待来源完成再核对结论', '李青'], ['item-active-c', '复核跨设备长文件名与附件来源的连续对应关系', longName]]) {
      await expect(item(id).locator('.pw-team-item-heading > span')).toHaveText(title);
      await expect(item(id).locator('.pw-team-member-meta')).toHaveText(assigned);
    }
    assert(await page.evaluate(() => {
      const roster = document.querySelector('[aria-label="成员与当前工作"]'), work = document.querySelector('[aria-label="这次协作的工作项"]'), summary = document.querySelector('[aria-label="团队当前结果"]'), messages = document.querySelector('[aria-label="原团队消息"]');
      return !!(roster.compareDocumentPosition(work) & Node.DOCUMENT_POSITION_FOLLOWING) && !!(work.compareDocumentPosition(summary) & Node.DOCUMENT_POSITION_FOLLOWING) && !!(summary.compareDocumentPosition(messages) & Node.DOCUMENT_POSITION_FOLLOWING);
    }), 'Member/work-item/current-result/message reading order');
    await shot('default-members');
    note('compact member identities, frozen model fields, exact work-item mapping and reading order', { viewport: prefix, listBefore });

    phase = `${prefix} disclosures`;
    await reachable(trigger(member('member-b'))); await trigger(member('member-b')).click();
    await expect(trigger(member('member-a'))).toHaveAttribute('aria-expanded', 'false');
    await expect(member('member-b').locator('.pw-team-expanded-copy')).toContainText('按原文逐条核对结论');
    await reachable(trigger(item('item-active-a'))); await trigger(item('item-active-a')).click();
    await expect(item('item-active-a').locator('.pw-team-expanded-copy')).toContainText('前置：整理旧版来源索引');
    await expect(item('item-active-a').locator('.pw-team-expanded-copy')).toContainText('每条结论都关联原始链接和原文段落。');
    await shot('expanded-work-item');
    await trigger(member('member-d')).click();
    await expect(member('member-d').getByRole('button', { name: '暂停该成员', exact: true })).toBeDisabled();
    await expect(member('member-d').getByRole('button', { name: '继续该成员', exact: true })).toBeDisabled();
    await trigger(member('member-d')).click();

    phase = `${prefix} drafts and refresh`;
    await page.getByRole('button', { name: '引用回复', exact: true }).click();
    assert.equal(await selected('收件成员'), 'member-b', 'Reply selects the same-name member by exact ID');
    await reachable(select('补充方式').getByRole('button')); await select('补充方式').getByRole('button').click();
    await page.getByRole('option', { name: '排入下一安全工作点', exact: true }).click();
    assert.equal(await selected('补充方式'), 'queue');
    const draft = `${prefix} 原问题回复草稿：按第二位李青的来源继续核对。`;
    const textarea = page.getByRole('textbox', { name: '发给所选成员', exact: true });
    await textarea.fill(draft);
    // The synthetic resume revision follows the production return-from-task path
    // and permits a fresh same-ID read while reading below the toolbar.
    const detailBefore = await position(740); assert(detailBefore > 0);
    const readsBefore = await page.evaluate(() => window.__TEAM_FIXTURE__.summary().requests.filter(row => row.action === 'teams.readRun').length);
    await page.evaluate(() => window.__TEAM_FIXTURE__.resume());
    await page.waitForFunction(count => window.__TEAM_FIXTURE__.summary().requests.filter(row => row.action === 'teams.readRun').length === count + 1, readsBefore);
    await settle(page);
    const detailAfter = await top(); assert(Math.abs(detailAfter - detailBefore) <= 1, `Same-ID refresh shifted detail ${detailBefore} -> ${detailAfter}`);
    await expect(textarea).toHaveValue(draft); assert.equal(await selected('收件成员'), 'member-b'); assert.equal(await selected('补充方式'), 'queue');
    await expect(page.getByRole('button', { name: '取消引用', exact: true })).toHaveCount(1);
    const refresh = page.getByRole('button', { name: '刷新团队记录', exact: true });
    await reachable(refresh); await page.evaluate(() => window.__TEAM_FIXTURE__.reorder()); await refresh.click(); await settle(page);
    await expect(trigger(member('member-b'))).toHaveAttribute('aria-expanded', 'true');
    await expect(trigger(member('member-a'))).toHaveAttribute('aria-expanded', 'false');
    await expect(trigger(item('item-active-a'))).toHaveAttribute('aria-expanded', 'true');
    for (const id of ['item-old', 'item-wait-b', 'item-active-c']) await expect(trigger(item(id))).toHaveAttribute('aria-expanded', 'false');
    assert.deepEqual(await page.locator('[data-team-member-id]').evaluateAll(nodes => nodes.map(node => node.dataset.teamMemberId)), ['member-c', 'member-b', 'member-d', 'member-a']);
    await expect(textarea).toHaveValue(draft); assert.equal(await selected('收件成员'), 'member-b');
    note('same-ID refresh preserves scroll/draft/reply/mode; reorder keeps member and item disclosure bound to IDs', { viewport: prefix, detailBefore, detailAfter });

    phase = `${prefix} back and reopen`;
    const detailSaved = await position(620);
    assert.equal(await page.evaluate(() => window.PocketWeb.handleBack()), true);
    await expect(workspace).toHaveAttribute('data-team-view', 'list:runs'); await settle(page);
    const listAfter = await top(); assert(Math.abs(listAfter - listBefore) <= 1, `Return shifted list ${listBefore} -> ${listAfter}`);
    await expect(search).toHaveValue('资料'); await expect(listRow).toBeFocused();
    await listRow.click(); await expect(workspace).toHaveAttribute('data-team-view', `run:${mainId}`); await settle(page);
    const reopenedTop = await top(); assert(Math.abs(reopenedTop - detailSaved) <= 1, `Reopen shifted detail ${detailSaved} -> ${reopenedTop}`);
    await expect(textarea).toHaveValue(draft); assert.equal(await selected('收件成员'), 'member-b'); assert.equal(await selected('补充方式'), 'queue');
    await expect(page.getByRole('button', { name: '取消引用', exact: true })).toHaveCount(1);
    await reachable(textarea); await reachable(page.getByRole('button', { name: '发送补充', exact: true }));
    note('back restores precise list scroll/focus/query; reopen restores detail scroll and recipient/reply/mode/text without sending', { viewport: prefix, listBefore, listAfter, detailSaved, reopenedTop });

    phase = `${prefix} ordinary discussion`;
    await page.locator('.pw-secondary-header').getByRole('button', { name: '返回', exact: true }).click();
    await expect(workspace).toHaveAttribute('data-team-view', 'list:runs'); await search.fill('资料方向讨论');
    await page.locator(`[data-team-list-id="${discussionId}"]`).click(); await settle(page);
    await expect(workspace).toHaveAttribute('data-team-view', `run:${discussionId}`);
    await expect(page.locator('[aria-label="这次协作的工作项"]')).toHaveCount(0);
    await expect(page.locator('[aria-label="团队当前结果"]')).toContainText('普通讨论记录，没有工作项计划。');
    await shot('ordinary-discussion');
    await page.locator('.pw-secondary-header').getByRole('button', { name: '返回', exact: true }).click(); await settle(page);
    const host = await page.evaluate(() => window.__TEAM_FIXTURE__.summary());
    assert.deepEqual(host.violations, []); assert.equal(host.remainingLeases, 0);
    assert(!host.requests.some(row => ['teams.sendMessage', 'teams.answerAssignment', 'teams.control', 'teams.start'].includes(row.action)), 'No business action may be attempted');
    note('ordinary discussion does not invent a work plan; all leases close; zero business actions or fixture violations', { viewport: prefix, host });
    await context.close(); context = null; page = null;
  }
  assert.deepEqual(evidence.errors, [], 'No page or console errors');
  evidence.passed = true;
} catch (error) {
  evidence.passed = false; evidence.failure = { phase, message: String(error.message || error), stack: error.stack };
  if (page) {
    await page.screenshot({ path: path.join(output, 'failure.png'), animations: 'disabled' }).catch(() => {});
    evidence.hostAtFailure = await page.evaluate(() => window.__TEAM_FIXTURE__?.summary()).catch(() => null);
    evidence.domAtFailure = await page.locator('body').innerText().catch(() => '');
  }
  process.exitCode = 1;
} finally {
  clearTimeout(watchdog); evidence.finishedAt = new Date().toISOString();
  await fs.writeFile(path.join(output, 'team-continuity-results.json'), JSON.stringify(evidence, null, 2));
  if (context) await context.close(); await browser.close(); await new Promise(resolve => server.close(resolve));
  console.log(JSON.stringify({ passed: evidence.passed, checks: evidence.checks.length, screenshots: evidence.captures.length,
    failure: evidence.failure && { phase: evidence.failure.phase, message: evidence.failure.message }, report: path.join(output, 'team-continuity-results.json') }, null, 2));
}
