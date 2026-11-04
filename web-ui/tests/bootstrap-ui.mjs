import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
const repo = path.resolve(import.meta.dirname, '../..');
const require = createRequire(path.join(repo, 'web-ui/package.json'));
const { chromium } = require('playwright');
const bundle = process.env.PW_UI_BUNDLE_DIR || path.join(repo, 'app/src/main/assets/pocket-ui');
const output = process.env.PW_UI_OUTPUT_DIR || path.join(repo, 'web-ui/test-results/bootstrap');
const index = await fs.readFile(path.join(bundle, 'index.html'), 'utf8');
const record = { scope: 'Mounted production Web bundle with isolated in-memory native protocol fixture. Web rendering and receipt lifecycle evidence only; no Android WebView, real SQLite cutover or native authority claim.', bundle: index.match(/assets\/(index-[^" ]+\.js)/)?.[1], viewport: { width: 360, height: 800 }, checks: [], screenshots: [], errors: [], consoleErrors: [], startedAt: new Date().toISOString() };
record.bundleSha256 = createHash('sha256').update(await fs.readFile(path.join(bundle, 'assets', record.bundle))).digest('hex');

function installFixture(scenario) {
  let serial = 0, revision = 0, token = 'bootstrap-fixture-document-1', holdAction = null, heldDispatchAction = null, operation = null, queryId = null;
  const requests = [], failures = [], expectedFailures = [], held = [], heldCommands = [];
  const state = { surface: 'bootstrap', revision: 2, generation: 7, state: 'failed', stage: 'configured', recovery: 'not_started', reason: { code: 'storage_full', message: '本机存储空间不足，尚未打开工作记录。', nextStep: '请释放本机存储空间，再明确重试本次启动。' }, installedVersion: 'fixture-0.4.0', requiredSchema: 10, observedSchema: null, retryAvailable: true, optionalUnavailable: [], maintenance: null };
  const commands = ['app.ready', 'app.refresh', 'bootstrap.read', 'bootstrap.retry', 'repair.read', 'repair.preserve', 'repair.review', 'repair.confirm', 'repair.discard', 'bootstrap.received', 'bootstrap.acknowledge'];
  const copy = value => structuredClone(value);
  if (!['retry', 'fonts', 'late-ack'].includes(scenario)) {
    state.state = 'maintenance'; state.stage = 'data_opened'; state.observedSchema = 10; state.retryAvailable = false;
    state.reason = { code: 'suspected_corruption', message: '本机数据库未能通过读取核对，工作记录暂时不可用。', nextStep: '先保全原件，逐项核对本次保全副本后再选择维修方式。' };
    state.maintenance = { repairId: 'fixture-original-repair-04', phase: 'detected', sourceFingerprint: '', backupFingerprint: '', preservationVerified: false, consistency: '原数据库尚未替换', files: [], review: null, allowedCommands: ['repair.read', 'repair.preserve'], reason: { code: 'suspected_corruption', message: '原件仍保留在本机，尚未建立可核验的保全副本。', nextStep: '先保全原件。此操作不会清空历史。' } };
    if (scenario === 'original-retry') {
      state.state = 'failed'; state.reason = { code: 'database_locked', message: '原数据库暂时被占用，未按损坏处理。', nextStep: '可保全原件，待占用解除后明确重新核对同一数据库。' };
      state.maintenance.repairId = null; state.maintenance.phase = 'not_started';
    }
  }
  const publish = () => window.PocketWeb.receive({ v: 1, kind: 'state', revision: ++revision, snapshot: { surface: 'bootstrap', bootstrap: copy(state), state: {}, composer: null, capabilities: { commands }, host: { surface: 'bootstrap', bridgeAvailable: scenario !== 'unavailable', appearance: { mode: 'light', dark: false } }, ...(operation ? { bootstrapOperation: copy(operation) } : {}), ...(queryId ? { bootstrapQueryId: queryId } : {}) } });
  const refresh = () => { state.revision++; publish(); };
  const isMutation = action => ['bootstrap.retry', 'repair.preserve', 'repair.confirm', 'repair.discard'].includes(action);
  const exact = args => {
    if (args.expectedGeneration !== state.generation || args.repairId !== state.maintenance?.repairId) throw new Error('Fixture original generation or repair changed');
    if (args.sourceFingerprint !== state.maintenance.sourceFingerprint || args.backupFingerprint !== state.maintenance.backupFingerprint) throw new Error('Fixture original file set changed');
  };
  const execute = request => {
    const { action, args } = request;
    if (request.hostDocumentToken !== token) throw new Error('Fixture stale native document');
    if (action === 'app.ready') { publish(); return { accepted: true, surface: 'bootstrap' }; }
    if (isMutation(action)) {
      if (operation && ['pending', 'unknown'].includes(operation.status)) throw new Error('Fixture unresolved original operation');
      operation = { requestId: args.requestId, command: action, generation: args.expectedGeneration, repairId: args.repairId || '', status: 'pending' }; queryId = null; publish();
    }
    switch (action) {
      case 'bootstrap.read': case 'app.refresh': case 'repair.read':
        if (action === 'repair.read' && args.repairId !== state.maintenance?.repairId) throw new Error('Fixture read must bind original repair');
        if (operation && operation.status !== 'pending' && (!operation.repairId || action === 'repair.read' && args.repairId === operation.repairId)) queryId = 'fixture-query-' + (++serial);
        publish(); break;
      case 'bootstrap.retry':
        if (args.expectedGeneration !== state.generation || !state.retryAvailable) throw new Error('Fixture retry generation already consumed');
        if (state.maintenance?.originalRetryAvailable) { exact(args); state.maintenance.phase = 'original_retry_prepared'; state.maintenance.originalRetryAvailable = false; }
        state.generation++; state.state = 'initializing'; state.stage = 'configured'; state.recovery = 'not_started'; state.retryAvailable = false;
        state.reason = { code: 'initializing', message: '正在核对本机安装、数据与原任务。', nextStep: '请等待当前初始化结束。' }; break;
      case 'repair.preserve': {
        if (args.expectedGeneration !== state.generation || args.repairId !== state.maintenance.repairId || !state.maintenance.allowedCommands.includes(action)) throw new Error('Fixture preserve unavailable or wrong repair');
        Object.assign(state.maintenance, { phase: 'preserved', sourceFingerprint: 'a'.repeat(64), backupFingerprint: 'a'.repeat(64), preservationVerified: true, consistency: { quiescence: 'original_business_handles_closed_before_capture', sqliteLogicalIntegrity: 'not_claimed', sourceBefore: 'a'.repeat(64), sourceAfter: 'a'.repeat(64), preCorruptionCallbackBytes: 'not_claimed', copiesSha256AndLengthVerified: true }, files: [{ name: 'pocket-world.db', exists: true, length: 524288, sha256: 'c'.repeat(64), copiedLength: 524288, copiedSha256: 'c'.repeat(64), verified: true }, { name: 'pocket-world.db-wal', exists: true, length: 32768, sha256: 'd'.repeat(64), copiedLength: 32768, copiedSha256: 'd'.repeat(64), verified: true }, { name: 'pocket-world.db-shm', exists: false, length: 0, verified: true }], originalDirectoryReferences: ['files/artifacts', 'files/tasks.json', 'files/assistant', 'files/workspace', 'files/memory'], reviewModes: ['empty_database', 'verified_copy'], allowedCommands: ['repair.read', 'repair.review', 'repair.discard'], reason: { code: 'preserved', message: '原件副本已经逐项核对。保全本身不代表原历史已恢复。', nextStep: '核对维修身份及影响后，明确选择本次审阅方案。' } });
        if (scenario === 'original-retry') { state.maintenance.repairId = 'fixture-original-repair-04'; state.maintenance.originalRetryAvailable = true; state.maintenance.reviewModes = []; state.maintenance.allowedCommands = ['repair.read']; state.retryAvailable = true; state.maintenance.reason = { code: 'original_retry_available', message: '原件已经保全，本次没有替换数据库。', nextStep: '明确重新核对同一原数据库；不会自动继续旧操作。' }; }
        break;
      }
      case 'repair.review':
        exact(args); if (!state.maintenance.reviewModes.includes(args.mode) || !state.maintenance.allowedCommands.includes(action)) throw new Error('Fixture unsupported review mode');
        if (args.mode === 'verified_copy' && (args.includeConversationContent !== true || JSON.stringify(args.sections) !== '["conversations"]')) throw new Error('Fixture requires the explicit conversation content section');
        if (scenario === 'copy-failure') {
          state.maintenance.phase = 'review_failed'; state.maintenance.review = null;
          state.maintenance.reason = { code: 'verified_copy_original_record_unconfirmed', message: '这份已核副本没有可核验的对话文字，未建立可确认的恢复暂存。', nextStep: '保留原件与副本，核对该记录；本次不会自动改为建立空库。' };
          refresh(); throw new Error('原记录尚未通过读取核验，未自动改为空库。');
        }
        state.maintenance.review = { reviewId: 'fixture-original-review-' + (++serial), mode: args.mode, summary: args.mode === 'verified_copy' ? '保全原件后，仅以新身份恢复已核副本的对话文字。原任务历史、附件原件与授权、密钥和配对不迁入，执行保持停用。' : '将保留当前原件与已核保全副本，建立不含旧历史的新空库。执行保持停用。' };
        if (args.mode === 'verified_copy') state.maintenance.review.restoration = { section: 'conversations', sourceConversationCount: 4, conversationCount: 3, messageCount: 18, omittedCount: 2, unreadableConversationCount: 1, partial: true, contentUtf8Bytes: 2400,
          sourceGeneration: 'fixture-original-history-generation', manifestSha256: 'c'.repeat(64), manifestBytes: 1500,
          conversations: [1, 2, 3].map(index => ({ sourceConversationId: 'source-' + index, restoredConversationId: 'restored-' + index, messageCount: 6, omittedCount: index < 3 ? 1 : 0, exportSha256: String(index).repeat(64), exportBytes: 800 })),
          gaps: [{ sourceConversationId: 'source-1', reason: 'message_deleted', recordCount: 1 }, { sourceConversationId: 'source-2', reason: 'role_not_exportable', recordCount: 1 }, { sourceConversationId: 'source-4', reason: 'source_conversation_unreadable', recordCount: null }], sourceFingerprint: state.maintenance.backupFingerprint, sourceReadOnly: true, preservedSourceOpenedInSQLite: false, restoresExistingIds: false, restoresActiveGrants: false, restoresTaskHistory: false, restoresAttachmentBytes: false, restoresCredentialsOrPairings: false, omissionMeaning: '已删除、撤回或非对话文字记录不会恢复；无法读取的对话单独列出。原件保留，未自动改为空库。' };
        state.maintenance.phase = 'staged'; state.maintenance.allowedCommands = ['repair.read', 'repair.review', 'repair.confirm', 'repair.discard']; break;
      case 'repair.confirm': {
        exact(args); if (!args.confirmed || args.reviewId !== state.maintenance.review?.reviewId || !state.maintenance.allowedCommands.includes(action)) throw new Error('Fixture review is not current');
        const reviewed = state.maintenance.review;
        state.maintenance.review = null;
        if (scenario === 'unknown') {
          state.maintenance.phase = 'unknown'; state.maintenance.allowedCommands = ['repair.read'];
          state.maintenance.reason = { code: 'cutover_unknown', message: '本次替换的结果尚未确认，保全原件与隔离副本仍保留。', nextStep: '只读取同一维修记录；不要再次确认，也不能重新启动。' };
        } else {
          state.maintenance.phase = 'completed'; state.maintenance.allowedCommands = ['repair.read']; state.retryAvailable = true;
          state.maintenance.result = { mode: reviewed.mode, summary: reviewed.mode === 'verified_copy' ? '已保全原件并以新身份恢复所选对话文字；原任务历史与执行授权未恢复' : '已保全原件并建立空库；原历史未恢复', executionEnabled: false, ...(reviewed.restoration ? { restoration: copy(reviewed.restoration) } : {}) };
          state.maintenance.reason = { code: 'completed', message: state.maintenance.result.summary, nextStep: '明确重试启动；执行保持停用。' };
          state.reason = { code: 'maintenance_completed', message: '本次维修记录已完成，等待明确重试启动。', nextStep: '请先核对结果，再重新核对并启动。' };
        }
        state.maintenance.reviewModes = [];
        break;
      }
      case 'repair.discard':
        if (args.expectedGeneration !== state.generation || args.repairId !== state.maintenance.repairId || !state.maintenance.allowedCommands.includes(action)) throw new Error('Fixture discard unavailable');
        state.maintenance.review = null; state.maintenance.allowedCommands = state.maintenance.allowedCommands.filter(item => item !== 'repair.confirm'); break;
      case 'bootstrap.acknowledge':
        if (!operation || operation.status === 'pending' || args.requestId !== operation.requestId || !queryId || args.queryId !== queryId) throw new Error('Fixture requires exact read proof');
        operation = null; queryId = null; break;
      case 'bootstrap.received':
        if (!operation || operation.status !== 'completed' || args.requestId !== operation.requestId || args.command !== operation.command || args.outcome !== operation.receipt) throw new Error('Fixture receipt is not from this original operation');
        operation = null; queryId = null; break;
      default: throw new Error('Unimplemented fixture action ' + action);
    }
    if (isMutation(action) && operation) {
      operation.repairId ||= state.maintenance?.repairId || '';
      operation.receipt = 'success';
      if (holdAction !== action) operation.status = 'completed';
    }
    refresh(); return copy(state);
  };
  const deliver = request => {
    let result;
    try { result = { v: 1, kind: 'result', id: request.id, ok: true, value: execute(request) }; }
    catch (failure) { (scenario === 'copy-failure' && request.action === 'repair.review' || scenario === 'late-ack' && request.action === 'bootstrap.received' ? expectedFailures : failures).push(String(failure.message || failure)); result = { v: 1, kind: 'result', id: request.id, ok: false, error: { code: 'outcome_unknown', message: String(failure.message || failure) } }; }
    if (request.action === holdAction) held.push(result); else window.PocketWeb.receive(result);
  };
  if (scenario !== 'unavailable') {
    window.__pocketDocumentToken = token;
    window.PocketNative = { postMessage(raw) {
      const request = JSON.parse(raw); requests.push(copy(request));
      queueMicrotask(() => {
        if (request.action === heldDispatchAction) heldCommands.push(request); else deliver(request);
      });
    } };
  } else {
    const unavailable = () => { if (!window.PocketWeb) { setTimeout(unavailable, 10); return; } publish(); window.PocketWeb.hostConfiguration({ surface: 'bootstrap', bridgeAvailable: false, bridgeUnavailableReason: '当前系统 WebView 不支持安全通信。请更新 Android System WebView 后重新打开；本页不能重试或维修数据。' }); };
    setTimeout(unavailable, 10);
  }
  window.__PW_BOOTSTRAP_TEST__ = {
    hold(action) { holdAction = action; },
    holdDispatch(action) { heldDispatchAction = action; },
    releaseDispatch() { heldDispatchAction = null; heldCommands.splice(0).forEach(deliver); },
    release() { holdAction = null; held.splice(0).forEach(result => window.PocketWeb.receive(result)); },
    setHidden(hidden) {
      if (hidden) { if (operation && ['pending', 'completed'].includes(operation.status)) operation.status = 'unknown'; if (state.maintenance) { state.maintenance.review = null; state.maintenance.allowedCommands = state.maintenance.allowedCommands.filter(action => action !== 'repair.confirm'); } queryId = null; publish(); }
      Object.defineProperty(document, 'visibilityState', { configurable: true, value: hidden ? 'hidden' : 'visible' }); Object.defineProperty(document, 'hidden', { configurable: true, value: hidden }); document.dispatchEvent(new Event('visibilitychange'));
    },
    rotateDocument() { if (operation && ['pending', 'completed'].includes(operation.status)) operation.status = 'unknown'; if (state.maintenance) { state.maintenance.review = null; state.maintenance.allowedCommands = state.maintenance.allowedCommands.filter(action => action !== 'repair.confirm'); } queryId = null; token = 'bootstrap-fixture-document-' + (++serial); publish(); window.PocketWeb.hostConfiguration({ documentToken: token }); },
    makeReady() { state.state = 'ready'; state.recovery = 'completed'; state.stage = 'features_ready'; state.reason = { code: 'ready', message: '原任务核对与数据库真实读取已完成。', nextStep: '核对当前回执后进入工作区。' }; if (state.maintenance?.phase === 'original_retry_prepared') state.maintenance.phase = 'original_retry_completed'; refresh(); },
    summary() { return { state: copy(state), operation: copy(operation), pending: held.length, pendingDispatch: heldCommands.length, readyReleased: state.state === 'ready' && operation === null, requests: requests.map(request => ({ action: request.action, args: copy(request.args) })), failures: [...failures], expectedFailures: [...expectedFailures] }; },
  };
}

let server, browser, context, page, scenario;
const check = name => record.checks.push({ name, passed: true });
const activeDialog = () => page.locator('.pw-settings-layer[data-visible="true"]:not([inert])').getByRole('dialog');
const count = action => page.evaluate(action => window.__PW_BOOTSTRAP_TEST__.summary().requests.filter(row => row.action === action).length, action);
async function shot(name) { await page.evaluate(() => document.fonts.ready); await page.waitForTimeout(350); await page.screenshot({ path: path.join(output, name + '.png') }); record.screenshots.push(name + '.png'); }
async function noOverflow() { const widths = await page.evaluate(() => ({ page: document.documentElement.clientWidth, scroll: document.documentElement.scrollWidth, panels: [...document.querySelectorAll('.pw-bootstrap,.pw-settings-dialog-content')].map(item => ({ width: item.clientWidth, scroll: item.scrollWidth })) })); assert.ok(widths.scroll <= widths.page + 1, JSON.stringify(widths)); assert.ok(widths.panels.every(item => item.scroll <= item.width + 1), JSON.stringify(widths)); }
async function begin(name, origin) {
  if (context) await context.close(); scenario = name;
  context = await browser.newContext({ viewport: record.viewport, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', serviceWorkers: 'block' });
  await context.route('**/*', route => new URL(route.request().url()).origin !== origin || name === 'fonts' && /\.woff2(?:\?|$)/.test(route.request().url()) ? route.abort() : route.continue());
  await context.addInitScript(installFixture, name); page = await context.newPage(); page.setDefaultTimeout(10000);
  page.on('pageerror', error => record.errors.push({ scenario: name, message: error.message })); page.on('console', message => { if (message.type() === 'error') record.consoleErrors.push({ scenario: name, message: message.text() }); });
  await page.goto(origin, { waitUntil: 'networkidle' });
  if (name !== 'unavailable') await page.waitForFunction(() => document.documentElement.dataset.pocketUi === 'maintenance');
  else await page.getByRole('heading', { name: '当前界面无法安全通信', exact: true }).waitFor();
}
async function preserveAndReview() {
  await page.getByRole('button', { name: '先保全原件', exact: true }).click();
  if (scenario === 'empty') {
    await page.getByRole('button', { name: '核对本次维修身份与文件', exact: true }).click();
    await page.getByText(/未确认原库逻辑完整性/).waitFor(); await page.locator('.pw-object-table').scrollIntoViewIfNeeded(); await noOverflow(); await shot('bootstrap-preservation-files');
    await page.getByRole('button', { name: '核对本次维修身份与文件', exact: true }).click();
  }
  await page.getByRole('button', { name: '审阅建立空库', exact: true }).click();
  await activeDialog().getByRole('heading', { name: '核对原件保全后，建立空库？', exact: true }).waitFor();
  assert.equal(await activeDialog().getByRole('checkbox').isChecked(), false);
  assert.equal(await activeDialog().getByRole('button', { name: '确认本次建立空库', exact: true }).isEnabled(), false);
}
try {
  await fs.mkdir(output, { recursive: true });
  server = http.createServer(async (req, res) => { try { const pathname = new URL(req.url, 'http://local').pathname; const file = path.resolve(bundle, '.' + (pathname === '/' ? '/index.html' : pathname)); if (!file.startsWith(bundle + '/')) throw new Error('invalid path'); const data = await fs.readFile(file); res.writeHead(200, { 'content-type': ({ '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.woff2': 'font/woff2', '.json': 'application/json', '.svg': 'image/svg+xml' })[path.extname(file)] || 'application/octet-stream' }); res.end(data); } catch { res.writeHead(404); res.end(); } });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); const origin = 'http://127.0.0.1:' + server.address().port;
  browser = await chromium.launch({ headless: true, args: ['--disable-background-networking'] });
  await begin('retry', origin);
  await page.getByRole('heading', { name: '需要处理启动问题', exact: true }).waitFor(); await noOverflow(); await shot('bootstrap-failure');
  assert.equal(await page.getByRole('button', { name: '全部工作', exact: true }).count(), 0);
  check('failed startup shows the original reason, completed stage and actionable next step without mounting normal workspace');
  await page.evaluate(() => window.__PW_BOOTSTRAP_TEST__.hold('bootstrap.retry'));
  await page.getByRole('button', { name: '重新核对并启动', exact: true }).click();
  await page.getByRole('heading', { name: '正在准备 PocketWorld', exact: true }).waitFor();
  assert.equal(await count('bootstrap.retry'), 1); assert.equal(await page.getByRole('button', { name: '重新核对并启动', exact: true }).count(), 0);
  await page.evaluate(() => { window.__PW_BOOTSTRAP_TEST__.setHidden(true); window.__PW_BOOTSTRAP_TEST__.rotateDocument(); window.__PW_BOOTSTRAP_TEST__.release(); window.__PW_BOOTSTRAP_TEST__.setHidden(false); });
  await page.getByRole('heading', { name: '原操作回执尚未确认', exact: true }).waitFor();
  await page.getByRole('button', { name: '只读核对原操作', exact: true }).click();
  await page.getByRole('heading', { name: '原操作回执尚未确认', exact: true }).waitFor();
  await page.getByRole('button', { name: '我已核对现状，继续审阅', exact: true }).click();
  await page.waitForFunction(() => !document.querySelector('[aria-label="原维修操作回执待核对"]'));
  await page.evaluate(() => window.__PW_BOOTSTRAP_TEST__.makeReady());
  await page.getByRole('heading', { name: '本机数据已就绪', exact: true }).waitFor();
  assert.equal(await page.getByRole('button', { name: '全部工作', exact: true }).count(), 0); assert.equal(await count('bootstrap.retry'), 1);
  check('retry stays single flight; token change retains unknown; read alone does not clear it; bootstrap-ready catalogue does not mount business UI');

  await begin('late-ack', origin);
  await page.evaluate(() => window.__PW_BOOTSTRAP_TEST__.holdDispatch('bootstrap.received'));
  await page.getByRole('button', { name: '重新核对并启动', exact: true }).click();
  await page.waitForFunction(() => window.__PW_BOOTSTRAP_TEST__.summary().pendingDispatch === 1);
  const completedDelivery = await page.evaluate(() => window.__PW_BOOTSTRAP_TEST__.summary().operation);
  assert.equal(completedDelivery.status, 'completed');
  await page.evaluate(() => { window.__PW_BOOTSTRAP_TEST__.makeReady(); window.__PW_BOOTSTRAP_TEST__.rotateDocument(); window.__PW_BOOTSTRAP_TEST__.releaseDispatch(); });
  await page.getByRole('heading', { name: '原操作回执尚未确认', exact: true }).waitFor(); await shot('bootstrap-delivery-unknown');
  let deliveryState = await page.evaluate(() => window.__PW_BOOTSTRAP_TEST__.summary());
  assert.equal(deliveryState.operation.status, 'unknown'); assert.equal(deliveryState.operation.requestId, completedDelivery.requestId); assert.equal(deliveryState.readyReleased, false); assert.equal(deliveryState.expectedFailures.length, 1); assert.equal(await count('bootstrap.retry'), 1);
  await page.getByRole('button', { name: '只读核对原操作', exact: true }).click();
  assert.equal((await page.evaluate(() => window.__PW_BOOTSTRAP_TEST__.summary())).readyReleased, false);
  await page.getByRole('button', { name: '我已核对现状，继续审阅', exact: true }).click();
  await page.waitForFunction(() => window.__PW_BOOTSTRAP_TEST__.summary().readyReleased);
  assert.equal(await count('bootstrap.received'), 1); assert.deepEqual(deliveryState.failures, []);
  check('worker completion precedes token rotation and late old delivery ACK: exact original result remains unknown, old ACK cannot release ready, no mutation replays');

  await begin('empty', origin); await preserveAndReview(); await noOverflow(); await shot('bootstrap-empty-review');
  assert.equal(await count('repair.confirm'), 0);
  assert.equal(await page.evaluate(() => window.PocketWeb.handleBack()), true);
  await page.waitForTimeout(400); assert.equal(await page.evaluate(() => window.PocketWeb.handleBack()), false);
  await page.getByRole('button', { name: '继续核对本次建立空库', exact: true }).click();
  assert.equal(await activeDialog().getByRole('checkbox').isChecked(), false);
  await activeDialog().getByText('我已核对本次原件与保全身份，理解建立空库不代表历史恢复。', { exact: true }).click(); await activeDialog().getByRole('button', { name: '确认本次建立空库', exact: true }).click();
  await page.getByRole('heading', { name: '原件已保全，新空库已建立', exact: true }).waitFor(); await noOverflow(); await shot('bootstrap-empty-result');
  let summary = await page.evaluate(() => window.__PW_BOOTSTRAP_TEST__.summary());
  assert.equal(summary.requests.filter(row => row.action === 'repair.confirm').length, 1); assert.equal(summary.requests.filter(row => row.action === 'bootstrap.retry').length, 0);
  const confirmation = summary.requests.find(row => row.action === 'repair.confirm').args;
  assert.equal(confirmation.repairId, 'fixture-original-repair-04'); assert.equal(confirmation.sourceFingerprint, 'a'.repeat(64)); assert.equal(confirmation.backupFingerprint, 'a'.repeat(64)); assert.equal(confirmation.expectedGeneration, 7); assert.equal(confirmation.confirmed, true); assert.match(confirmation.reviewId, /^fixture-original-review-/);
  assert.equal(summary.state.maintenance.result.executionEnabled, false); assert.deepEqual(summary.failures, []);
  check('preservation and empty-database consent bind exact original identity, default unchecked, one confirmation, no auto retry or claim of historical recovery');
  check('Back closes the review; the next Back is unhandled; reopening the same current review requires explicit fresh consent');

  await begin('review-cancel', origin); await preserveAndReview();
  const retained = await page.evaluate(() => window.__PW_BOOTSTRAP_TEST__.summary().state.maintenance);
  await activeDialog().getByRole('button', { name: '保留现状', exact: true }).click();
  await page.getByRole('button', { name: '核对本次维修身份与文件', exact: true }).click();
  await page.getByRole('button', { name: '取消本次审阅（保全原件保留）', exact: true }).click();
  await page.waitForFunction(() => window.__PW_BOOTSTRAP_TEST__.summary().state.maintenance.review === null);
  const cancelled = await page.evaluate(() => window.__PW_BOOTSTRAP_TEST__.summary().state.maintenance);
  assert.equal(cancelled.repairId, retained.repairId); assert.equal(cancelled.phase, retained.phase); assert.deepEqual(cancelled.files, retained.files); assert.equal(cancelled.review, null); assert.equal(await count('repair.confirm'), 0); assert.equal(await count('bootstrap.retry'), 0);
  check('cancelling review retains the same repair, phase and all preservation file proofs without clearing data or reopening business');

  await begin('copy', origin);
  await page.getByRole('button', { name: '先保全原件', exact: true }).click();
  assert.equal(await page.getByRole('button', { name: '审阅恢复对话文字', exact: true }).isEnabled(), false);
  await page.getByText('包含原对话文字，在本机读取并准备恢复审阅', { exact: true }).click();
  await page.getByRole('button', { name: '审阅恢复对话文字', exact: true }).click();
  await activeDialog().getByRole('heading', { name: '核对本次对话文字恢复', exact: true }).waitFor();
  assert.equal(await activeDialog().getByRole('checkbox').isChecked(), false);
  await activeDialog().getByText('3 段 / 18 条', { exact: true }).waitFor(); await activeDialog().getByText('2 条原记录', { exact: true }).waitFor();
  await activeDialog().getByText('1 段对话', { exact: true }).waitFor();
  await activeDialog().getByRole('button', { name: '查看 3 项遗漏说明', exact: true }).click();
  await activeDialog().getByText('原对话无法完整读取', { exact: true }).waitFor();
  await noOverflow(); await shot('bootstrap-copy-gaps');
  await activeDialog().getByRole('button', { name: '查看 3 项遗漏说明', exact: true }).click();
  await noOverflow(); await shot('bootstrap-copy-review');
  await activeDialog().getByText('我已核对所选对话文字、明确省略和原件保全身份，理解这不会恢复原任务历史或执行授权。', { exact: true }).click();
  await activeDialog().getByRole('button', { name: '确认本次恢复对话文字', exact: true }).scrollIntoViewIfNeeded();
  await shot('bootstrap-copy-consent');
  await activeDialog().getByRole('button', { name: '确认本次恢复对话文字', exact: true }).click();
  await page.getByRole('heading', { name: '原件已保全，对话文字已恢复', exact: true }).waitFor(); await noOverflow(); await shot('bootstrap-copy-result');
  summary = await page.evaluate(() => window.__PW_BOOTSTRAP_TEST__.summary());
  assert.equal(summary.requests.filter(row => row.action === 'repair.confirm').length, 1); assert.equal(summary.requests.filter(row => row.action === 'bootstrap.retry').length, 0);
  const selected = summary.requests.find(row => row.action === 'repair.review').args;
  assert.equal(selected.mode, 'verified_copy'); assert.equal(selected.includeConversationContent, true); assert.deepEqual(selected.sections, ['conversations']);
  assert.equal(summary.state.maintenance.result.restoration.restoresActiveGrants, false); assert.equal(summary.state.maintenance.result.restoration.restoresExistingIds, false); assert.deepEqual(summary.failures, []);
  check('verified copy explicitly selects conversation text, displays real counts and omissions, binds one unchecked review and confirms once without old IDs or execution grants');

  await begin('copy-failure', origin);
  await page.getByRole('button', { name: '先保全原件', exact: true }).click();
  assert.equal(await page.getByRole('button', { name: '审阅恢复对话文字', exact: true }).isEnabled(), false);
  await page.getByText('包含原对话文字，在本机读取并准备恢复审阅', { exact: true }).click();
  await page.getByRole('button', { name: '审阅恢复对话文字', exact: true }).click();
  await page.getByText('所选方案尚未通过核验', { exact: true }).waitFor(); await noOverflow(); await shot('bootstrap-copy-unavailable');
  summary = await page.evaluate(() => window.__PW_BOOTSTRAP_TEST__.summary());
  assert.equal(summary.state.maintenance.phase, 'review_failed'); assert.equal(summary.state.maintenance.preservationVerified, true);
  assert.deepEqual(summary.requests.filter(row => row.action === 'repair.review').map(row => row.args.mode), ['verified_copy']);
  assert.equal(await count('repair.confirm'), 0); assert.equal(await count('bootstrap.retry'), 0); assert.equal(summary.expectedFailures.length, 1); assert.deepEqual(summary.failures, []);
  check('unreadable verified copy keeps its original files and explicit failure; it never silently falls back to empty-database review or confirmation');

  await begin('unknown', origin); await preserveAndReview();
  await page.evaluate(() => window.__PW_BOOTSTRAP_TEST__.rotateDocument());
  await page.waitForTimeout(400); assert.equal(await activeDialog().count(), 0); assert.equal(await count('repair.confirm'), 0);
  await page.getByRole('button', { name: '审阅建立空库', exact: true }).click(); await activeDialog().getByText('我已核对本次原件与保全身份，理解建立空库不代表历史恢复。', { exact: true }).click();
  await page.evaluate(() => window.__PW_BOOTSTRAP_TEST__.hold('repair.confirm'));
  await activeDialog().getByRole('button', { name: '确认本次建立空库', exact: true }).click();
  await page.waitForFunction(() => window.__PW_BOOTSTRAP_TEST__.summary().pending === 1);
  await page.evaluate(() => { window.__PW_BOOTSTRAP_TEST__.setHidden(true); window.__PW_BOOTSTRAP_TEST__.release(); window.__PW_BOOTSTRAP_TEST__.setHidden(false); });
  await page.getByRole('heading', { name: '原操作回执尚未确认', exact: true }).waitFor();
  assert.equal(await page.getByRole('button', { name: '我已核对现状，继续审阅', exact: true }).isEnabled(), false);
  await noOverflow(); await shot('bootstrap-confirm-unknown');
  await page.getByRole('button', { name: '只读核对原操作', exact: true }).click();
  await page.getByRole('heading', { name: '原操作回执尚未确认', exact: true }).waitFor();
  await page.getByRole('button', { name: '我已核对现状，继续审阅', exact: true }).click();
  await page.waitForFunction(() => !document.querySelector('[aria-label="原维修操作回执待核对"]'));
  assert.equal(await page.getByRole('button', { name: '审阅建立空库', exact: true }).count(), 0); assert.equal(await page.getByRole('button', { name: '重新核对并启动', exact: true }).count(), 0);
  summary = await page.evaluate(() => window.__PW_BOOTSTRAP_TEST__.summary());
  assert.equal(summary.requests.filter(row => row.action === 'repair.confirm').length, 1);
  assert.equal(summary.requests.find(row => row.action === 'repair.read').args.repairId, 'fixture-original-repair-04'); assert.deepEqual(summary.failures, []);
  check('token-only change expires review; hidden pending confirm retains exact unknown identity; read plus acknowledgement cannot override native read-only cutover');

  await begin('original-retry', origin);
  await page.getByRole('button', { name: '先保全原件', exact: true }).click();
  await page.getByRole('button', { name: '重新核对原数据库', exact: true }).waitFor();
  assert.equal(await page.getByRole('button', { name: '审阅建立空库', exact: true }).count(), 0); await shot('bootstrap-original-database-retry');
  await page.getByRole('button', { name: '重新核对原数据库', exact: true }).click();
  await page.getByRole('heading', { name: '正在准备 PocketWorld', exact: true }).waitFor();
  summary = await page.evaluate(() => window.__PW_BOOTSTRAP_TEST__.summary());
  const originalRetry = summary.requests.find(row => row.action === 'bootstrap.retry').args;
  assert.equal(originalRetry.repairId, 'fixture-original-repair-04'); assert.equal(originalRetry.sourceFingerprint, 'a'.repeat(64)); assert.equal(originalRetry.backupFingerprint, 'a'.repeat(64)); assert.equal(originalRetry.expectedGeneration, 7);
  assert.equal(await count('repair.review'), 0); assert.equal(await count('repair.confirm'), 0); assert.equal(summary.state.maintenance.phase, 'original_retry_prepared'); assert.deepEqual(summary.failures, []);
  check('non-corruption preserved original retry uses both native gates and exact repair/file-set identities without exposing empty or copy replacement');

  await begin('unavailable', origin); await noOverflow(); await shot('bootstrap-unavailable');
  assert.equal(await page.getByRole('button', { name: '先保全原件', exact: true }).count(), 0); assert.equal(await count('app.ready'), 0);
  check('unsupported secure WebView communication remains explanatory and read-only without inventing a native repair fallback');
  await begin('fonts', origin); await page.getByRole('heading', { name: '需要处理启动问题', exact: true }).waitFor(); assert.equal(await count('app.ready'), 1);
  check('failed bundled-font loading does not block the native maintenance handshake or actionable failure state');
  assert.deepEqual(record.errors, []); assert.deepEqual(record.consoleErrors.filter(item => item.scenario !== 'fonts'), []);
  record.status = 'PASS';
} catch (error) {
  record.status = 'FAIL'; record.failure = { scenario, message: String(error.message || error), stack: error.stack };
  if (page) { record.visibleText = await page.locator('body').innerText().catch(() => ''); await page.screenshot({ path: path.join(output, 'failure.png') }).catch(() => {}); record.fixture = await page.evaluate(() => window.__PW_BOOTSTRAP_TEST__?.summary()).catch(() => null); }
  process.exitCode = 1;
} finally {
  record.finishedAt = new Date().toISOString(); await fs.mkdir(output, { recursive: true }); await fs.writeFile(path.join(output, 'result.json'), JSON.stringify(record, null, 2));
  if (context) await context.close(); if (browser) await browser.close(); if (server) await new Promise(resolve => server.close(resolve));
  console.log(JSON.stringify({ status: record.status, bundle: record.bundle, checks: record.checks.length, output, failure: record.failure?.message }, null, 2));
}
