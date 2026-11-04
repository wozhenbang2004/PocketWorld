import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { chromium } from '@playwright/test';
// Build the production app separately, then pass its bundle and an evidence directory.
// Example: node tests/model-connection-ui.mjs --bundle /tmp/model-bundle --output /tmp/model-ui
const argument = flag => {
  const at = process.argv.indexOf(flag), value = process.argv[at + 1];
  if (at < 0 || !value || value.startsWith('--')) throw new Error('Required: --bundle <directory> --output <directory>');
  return value;
};
const baseline = process.argv.includes('--baseline');
const output = path.resolve(argument('--output'));
const bundle = path.resolve(argument('--bundle'));
const seed = emptySnapshot();
const index = await fs.readFile(path.join(bundle, 'index.html'), 'utf8');
const record = { scope: 'Actual Vite production bundle at 360x800 with an isolated synthetic native host. Tests Web presentation and commands; does not prove Android/native CAS, HTTP, vendor cache or billing.',
  baseline, bundle: index.match(/assets\/(index-[^" ]+\.js)/)?.[1], viewport: { width: 360, height: 800 }, checks: [], screenshots: [], errors: [], startedAt: new Date().toISOString() };
record.bundleSha256 = createHash('sha256').update(await fs.readFile(path.join(bundle, 'assets', record.bundle))).digest('hex');

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


function installFixture(seed) {
  const snapshot = structuredClone(seed), requests = [], held = [], errors = [];
  let revision = 0, section = null, holdTest = false, unknownNext = false, conflictNextSave = false, malformedNext = false, serial = 0;
  const documentToken = 'connection-policy-fixture-document'; window.__pocketDocumentToken = documentToken;
  const connections = [
    { id: 'connection-a', revision: 7, name: '原 Anthropic 连接', provider: 'anthropic', baseUrl: 'https://fixture.example.invalid/v1', model: 'fixture-model-a', enabled: true, hasApiKey: true,
      configuration: { sendScreenshots: false, supportsImageInput: false, maxOutputTokens: 4096, compatibility: null } },
    { id: 'connection-b', revision: 4, name: '另一个 Chat 连接', provider: 'openai_chat', baseUrl: 'https://chat.example.invalid/v1', model: 'fixture-model-b', enabled: true, hasApiKey: true,
      configuration: { sendScreenshots: false, supportsImageInput: false, maxOutputTokens: 4096, compatibility: null } },
  ];
  const commands = ['app.ready', 'app.refresh', 'composer.reading', 'connections.enter', 'connections.leave', 'models.snapshot',
    'settings.reviewModelTransport', 'models.connection.add', 'models.connection.edit', 'models.connection.test', 'models.connection.select', 'tasks.open'];
  const models = () => ({ connections: { connections: structuredClone(connections), selectedConnectionId: 'connection-a' }, models: [] });
  Object.assign(snapshot.state, { modelName: connections[0].model, modelProvider: connections[0].provider, baseUrl: connections[0].baseUrl, hasApiKey: true,
    modelConnections: models(), modelTest: '旧连接遗留成功标记', modelTestTaskId: '56ef393e-5be2-4b2a-934f-bbf4f227d9a4' });
  const publish = () => { snapshot.state.modelConnections = models(); snapshot.view.section = section; snapshot.capabilities = { commands };
    window.PocketWeb.receive({ v: 1, kind: 'state', revision: ++revision, snapshot: structuredClone(snapshot) }); };
  const reply = (request, value, error) => window.PocketWeb.receive({ v: 1, kind: 'result', id: request.id, ok: !error, ...(error ? { error } : { value }) });
  const execute = request => {
    const args = request.args; let value = {}, error = null;
    try {
      if (request.hostDocumentToken !== documentToken) throw new Error('Wrong fixture document');
      if (request.action.startsWith('models.') && section !== 'models') throw new Error('Fixture outside original models section');
      switch (request.action) {
        case 'app.ready': publish(); value = { ready: true }; break;
        case 'app.refresh': publish(); break;
        case 'composer.reading': break;
        case 'connections.enter': section = args.section; publish(); break;
        case 'connections.leave': if (section === args.section) section = null; publish(); break;
        case 'models.snapshot': publish(); value = models(); break;
        case 'settings.reviewModelTransport': value = { required: false, consentTarget: '' }; break;
        case 'models.connection.edit': {
          const original = connections.find(item => item.id === args.id); if (conflictNextSave) { conflictNextSave = false; original.revision++; }
          if (!original || original.revision !== args.expectedRevision) { publish(); error = { code: 'not_accepted', message: '原连接修订已变化，请保留草稿并重新核对。' }; break; }
          const previous = original.configuration.compatibility;
          Object.assign(original, { revision: original.revision + 1, provider: args.provider, baseUrl: args.url, model: args.model });
          original.configuration = { ...original.configuration, sendScreenshots: args.screenshots, supportsImageInput: args.supportsImageInput, maxOutputTokens: args.maxOutputTokens,
            compatibility: args.compatibility ? { ...structuredClone(args.compatibility), format: 'pocketworld.provider-compat.v1', id: previous?.id || 'compat-fixture-' + original.id,
              revision: (previous?.revision || 0) + 1, apiMode: args.provider, model: args.model } : null };
          publish(); value = { status: 'saved', snapshot: models() }; break;
        }
        case 'models.connection.test': {
          const original = connections.find(item => item.id === args.id); if (original && original.revision !== args.expectedRevision) throw new Error('Original fixture revision changed');
          const unknown = unknownNext; unknownNext = false; const reference = { taskId: crypto.randomUUID(), runId: crypto.randomUUID(), epoch: 1, purpose: 'CONNECTION_TEST', accountingOnly: true, executionAuthorized: false };
          const profile = original?.configuration.compatibility;
          const proof = { format: 'pocketworld.connection-test.v1', testId: crypto.randomUUID(), connectionId: args.id, connectionRevision: args.expectedRevision,
            editorId: args.editorId, draftRevision: args.draftRevision, draftFingerprint: 'c'.repeat(64), presentationCurrent: true, outcome: unknown ? 'unknown' : 'completed', inferenceReference: reference };
          value = { status: 'tested', snapshot: { ok: !unknown, message: unknown ? '原调用结果待核对。' : '文字连接正常；图片理解和手机工具能力尚未验证。', visionVerified: false, toolsVerified: false,
            connectionTest: proof, inferenceReference: reference, inferenceFeedback: { available: true, source: 'original_task_inference_receipts', outcomeUnknown: unknown,
              calls: [{ callId: 'fixture-call-' + (++serial), outcome: unknown ? 'STARTED' : 'COMPLETED', protocol: args.provider, requestedModel: args.model, responseModel: null,
                providerProjection: { compatProfileRevision: profile?.revision, hintPrepared: false, dispatchAttempted: true, cacheHitKnown: false,
                  reason: profile?.cacheMode === 'EPHEMERAL' ? 'no_stable_system_or_tools' : 'cache_disabled' },
                cacheCounters: { CACHE_READ: { value: null, source: 'unknown' }, CACHE_WRITE: { value: null, source: 'unknown' } }, actualCost: null, actualCostSource: 'unknown' }] } } };
          if (malformedNext) { malformedNext = false; proof.draftFingerprint = ''; }
          if (holdTest) { held.push({ request, value }); return; }
          break;
        }
        case 'tasks.open': value = { opened: true }; break;
        default: throw new Error('Unimplemented fixture action ' + request.action);
      }
    } catch (failure) { error = { code: 'outcome_unknown', message: String(failure.message || failure) }; errors.push(error.message); }
    reply(request, value, error);
  };
  window.PocketNative = { postMessage(raw) { const request = JSON.parse(raw); requests.push(structuredClone(request)); queueMicrotask(() => execute(request)); } };
  window.__PW_CONNECTION_TEST__ = {
    requests, malformed() { malformedNext = true; }, hold() { holdTest = true; }, unknown() { unknownNext = true; }, conflict() { conflictNextSave = true; },
    bump(id) { connections.find(item => item.id === id).revision++; publish(); },
    release() { holdTest = false; for (const item of held.splice(0)) { const row = connections.find(row => row.id === item.value.snapshot.connectionTest.connectionId);
      item.value.snapshot.connectionTest.presentationCurrent = section === 'models' && row?.revision === item.value.snapshot.connectionTest.connectionRevision;
      reply(item.request, item.value, null); } },
    summary() { return { section, pending: held.length, errors, connections: structuredClone(connections),
      requests: requests.map(request => ({ action: request.action, args: { ...structuredClone(request.args), ...(Object.hasOwn(request.args, 'apiKey') ? { apiKey: request.args.apiKey ? '[fixture secret omitted]' : '' } : {}) } })) }; },
  };
}

let server, browser, context, page;
const check = name => record.checks.push({ name, passed: true });
const count = action => page.evaluate(action => window.__PW_CONNECTION_TEST__.requests.filter(request => request.action === action).length, action);
const editor = () => page.getByRole('region', { name: '当前连接配置', exact: true });
async function shot(name) { await page.evaluate(() => document.fonts.ready); await page.waitForTimeout(200); await page.screenshot({ path: path.join(output, name + '.png') }); record.screenshots.push(name + '.png'); }
async function select(label, option) {
  const trigger = editor().locator('.pw-selection-field').filter({ has: page.getByText(label, { exact: true }) }).getByRole('button');
  const formScroll = () => page.locator('.pw-model-editor').evaluate(node => node.closest('.pw-secondary-scroll').scrollTop);
  const menu = page.getByRole('listbox', { name: label, exact: true });
  const open = async () => {
    await trigger.scrollIntoViewIfNeeded();
    // Capture the actual pointer dispatch after the browser has made it visible.
    await trigger.evaluate(node => node.addEventListener('pointerdown', () => {
      window.__modelSelectScroll = node.closest('.pw-secondary-scroll').scrollTop;
    }, { capture: true, once: true }));
    await trigger.click();
    const atPointer = await page.evaluate(() => window.__modelSelectScroll);
    await menu.waitFor();
    assert.equal(await formScroll(), atPointer, 'Opening a select must preserve the current form reading position');
    return atPointer;
  };
  let before = await open();
  if (label === '缓存支持状态') {
    await page.mouse.move(4, 100); await page.mouse.wheel(0, -80); await page.waitForTimeout(100);
    assert.equal(await menu.isVisible(), true, 'The modal select must remain available during a background wheel gesture');
    assert.equal(await formScroll(), before, 'The background form must remain fixed while the modal select is open');
  }
  await page.getByRole('option', { name: option, exact: true }).click();
  record.selectChecks ||= []; record.selectChecks.push({ label, option, formScroll: before, passed: true });
}
async function expandPolicy() { await editor().getByRole('button', { name: /兼容与缓存 请求格式/ }).click(); }
async function reopen() { await page.getByRole('region', { name: '连接详情', exact: true }).getByRole('button', { name: '编辑这项连接', exact: true }).click(); await editor().waitFor(); }
try {
  await fs.mkdir(output, { recursive: true });
  server = http.createServer(async (req, res) => { try { const name = new URL(req.url, 'http://local').pathname; const file = path.resolve(bundle, '.' + (name === '/' ? '/index.html' : name));
    if (!file.startsWith(bundle + '/')) throw new Error('invalid asset'); const content = await fs.readFile(file);
    res.writeHead(200, { 'content-type': ({ '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.woff2': 'font/woff2', '.json': 'application/json', '.svg': 'image/svg+xml' })[path.extname(file)] || 'application/octet-stream' }); res.end(content);
  } catch { res.writeHead(404); res.end(); } });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); const origin = 'http://127.0.0.1:' + server.address().port;
  browser = await chromium.launch({ headless: true, args: ['--disable-background-networking'] });
  context = await browser.newContext({ viewport: record.viewport, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', serviceWorkers: 'block' });
  await context.route('**/*', route => new URL(route.request().url()).origin === origin ? route.continue() : route.abort());
  await context.addInitScript(installFixture, seed); page = await context.newPage(); page.setDefaultTimeout(8000);
  page.on('pageerror', error => record.errors.push(error.message)); await page.goto(origin, { waitUntil: 'networkidle' });
  await page.waitForFunction(() => document.documentElement.dataset.pocketUi === 'ready');
  await page.getByRole('button', { name: '全部工作', exact: true }).click();
  await page.locator('.pw-work-drawer').getByRole('link', { name: '设置与连接', exact: true }).click();
  await page.getByRole('button', { name: /^当前模型/ }).click(); await page.getByRole('button', { name: '管理连接', exact: true }).click();
  await page.getByRole('button', { name: '编辑当前 API 连接', exact: true }).click(); await editor().waitFor();
  assert.equal(await editor().getByText('旧连接遗留成功标记', { exact: true }).count(), 0, 'Old global success must not appear as this connection draft result');
  assert.equal(await count('models.connection.test'), 0); check('opening the original editor ignores the prior global connection success and sends no test');
  await shot('editor-original');
  await select('接口协议', 'Anthropic Messages');
  assert.equal(await count('models.connection.test'), 0); check('the original protocol picker remains usable by pointer without changing scroll position or issuing a test');
  await expandPolicy(); await editor().getByText('使用连接兼容档案', { exact: true }).click();
  assert.equal(await editor().getByRole('switch', { name: '请求临时前缀缓存', exact: true }).isEnabled(), false);
  assert.equal(await editor().getByRole('switch', { name: '请求临时前缀缓存', exact: true }).getAttribute('aria-checked'), 'false');
  assert.equal(await editor().getByRole('button', { name: '测试连接', exact: true }).isEnabled(), false);
  await select('缓存支持状态', '已核对服务商支持'); await editor().getByRole('switch', { name: '请求临时前缀缓存', exact: true }).click();
  await editor().getByLabel('每次最多携带的工具数', { exact: true }).fill('24');
  assert.equal(await count('models.connection.test'), 0); await shot('cache-review-before-save');
  check('cache starts unknown and off, only owner changes enable it, and unsaved policy changes never trigger HTTP tests');
  await editor().getByRole('button', { name: '保存原连接', exact: true }).click(); await page.getByRole('region', { name: '连接详情', exact: true }).waitFor();
  let state = await page.evaluate(() => window.__PW_CONNECTION_TEST__.summary()); const save = state.requests.find(request => request.action === 'models.connection.edit');
  assert.equal(save.args.id, 'connection-a'); assert.equal(save.args.expectedRevision, 7); assert.equal(save.args.compatibility.cacheMode, 'EPHEMERAL');
  assert.equal(save.args.compatibility.cacheSupport, 'SUPPORTED'); assert.equal(save.args.compatibility.maxTools, 24);
  assert.deepEqual(Object.keys(save.args.compatibility).sort(), ['cacheMode', 'cacheSupport', 'maxRequestBytes', 'maxTools', 'schemaSubset', 'streamUsage', 'tokenField', 'toolWireShape'].sort());
  await reopen(); await expandPolicy(); assert.equal(await editor().getByRole('switch', { name: '请求临时前缀缓存', exact: true }).getAttribute('aria-checked'), 'true');
  assert.equal(await editor().getByRole('button', { name: '测试连接', exact: true }).isEnabled(), true); check('saved CAS definition reopens on the same connection with native profile revision and no secret fields');
  await editor().getByRole('button', { name: '测试连接', exact: true }).click(); await editor().getByRole('region', { name: '本次连接测试结果', exact: true }).waitFor();
  assert.equal(await count('models.connection.test'), 1); assert.equal(await editor().getByText('未知（未报告，不按 0 计算）', { exact: true }).count(), 2);
  await editor().getByText('本次没有稳定系统或工具前缀，未添加提示', { exact: true }).waitFor(); await shot('actual-test-unknown-cache');
  check('one explicit text test shows only its original receipt, absent cache usage and money remain unknown, and no prefix means no cache hint');

  await editor().getByLabel('模型名称', { exact: true }).fill('changed-model'); assert.equal(await editor().getByRole('region', { name: '本次连接测试结果', exact: true }).count(), 0);
  await page.evaluate(() => window.__PW_CONNECTION_TEST__.hold()); await editor().getByRole('button', { name: '测试连接', exact: true }).click();
  await page.waitForFunction(() => window.__PW_CONNECTION_TEST__.summary().pending === 1);
  await editor().getByLabel('模型名称', { exact: true }).fill('changed-again'); await page.evaluate(() => window.__PW_CONNECTION_TEST__.release());
  await page.waitForTimeout(150); assert.equal(await editor().getByRole('region', { name: '本次连接测试结果', exact: true }).count(), 0); assert.equal(await count('models.connection.test'), 2);
  check('changing a draft clears its result and a late completion cannot reattach to the edited draft');

  await page.evaluate(() => window.__PW_CONNECTION_TEST__.hold()); await editor().getByRole('button', { name: '测试连接', exact: true }).click();
  await page.waitForFunction(() => window.__PW_CONNECTION_TEST__.summary().pending === 1); await page.evaluate(() => window.PocketWeb.handleBack());
  await page.getByRole('region', { name: '连接详情', exact: true }).waitFor();
  await page.getByRole('button', { name: '返回连接管理', exact: true }).click();
  await page.getByRole('region', { name: '连接列表', exact: true }).waitFor(); await page.getByRole('button', { name: /^另一个 Chat 连接/ }).click(); await reopen();
  await page.evaluate(() => window.__PW_CONNECTION_TEST__.release()); await page.waitForTimeout(150);
  assert.equal(await editor().getByRole('region', { name: '本次连接测试结果', exact: true }).count(), 0); assert.equal(await editor().getByLabel('模型名称', { exact: true }).inputValue(), 'fixture-model-b');
  await expandPolicy(); await editor().getByText('使用连接兼容档案', { exact: true }).click();
  await editor().getByText('当前协议尚未接入显式缓存提示，保持关闭。服务商若返回缓存用量，仍按原调用回执显示。', { exact: true }).waitFor();
  await select('输出上限字段', 'max_completion_tokens'); await select('流式用量请求', '省略 stream_options');
  assert.equal(await count('models.connection.test'), 3); assert.equal(await editor().getByRole('button', { name: '测试连接', exact: true }).isEnabled(), false);
  check('switching to a different editor cannot inherit the pending result and unsupported protocols keep explicit cache hints off');
  await editor().getByRole('button', { name: '保存原连接', exact: true }).click(); await page.getByRole('region', { name: '连接详情', exact: true }).waitFor(); await reopen();
  await page.evaluate(() => window.__PW_CONNECTION_TEST__.unknown()); await editor().getByRole('button', { name: '测试连接', exact: true }).click();
  await editor().getByText('原测试结果待核对；本表单不会自动重试或再次发送。请先核对原调用与连接，再明确开始新的测试。', { exact: true }).waitFor();
  assert.equal(await editor().getByRole('button', { name: '测试连接', exact: true }).isEnabled(), false);
  await editor().getByLabel('模型名称', { exact: true }).fill('changed-after-unknown'); assert.equal(await editor().getByRole('button', { name: '测试连接', exact: true }).isEnabled(), false);
  assert.equal(await count('models.connection.test'), 4); await shot('unknown-no-retry'); check('unknown original outcome locks this editor against replay even after draft changes');

  await editor().getByRole('button', { name: '返回核对连接', exact: true }).click(); await reopen();
  await page.evaluate(() => window.__PW_CONNECTION_TEST__.hold()); await editor().getByRole('button', { name: '测试连接', exact: true }).click();
  await page.waitForFunction(() => window.__PW_CONNECTION_TEST__.summary().pending === 1);
  await page.evaluate(() => { window.__PW_CONNECTION_TEST__.bump('connection-b'); window.__PW_CONNECTION_TEST__.release(); });
  await editor().getByText('这项原连接已被更新；旧草稿没有套用新修订。请返回详情核对后重新编辑。', { exact: true }).waitFor();
  assert.equal(await editor().getByRole('region', { name: '本次连接测试结果', exact: true }).count(), 0);
  assert.equal(await editor().getByRole('button', { name: '保存原连接', exact: true }).isEnabled(), false); check('a changed saved revision rejects the late result and blocks stale save/test commands');
  await editor().getByRole('button', { name: '返回核对连接', exact: true }).click(); await reopen();
  await editor().getByLabel('模型名称', { exact: true }).fill('cas-draft-kept'); await page.evaluate(() => window.__PW_CONNECTION_TEST__.conflict());
  const editsBefore = await count('models.connection.edit'); await editor().getByRole('button', { name: '保存原连接', exact: true }).click();
  await editor().getByText('这项原连接已被更新；旧草稿没有套用新修订。请返回详情核对后重新编辑。', { exact: true }).waitFor();
  assert.equal(await editor().getByLabel('模型名称', { exact: true }).inputValue(), 'cas-draft-kept'); assert.equal(await count('models.connection.edit'), editsBefore + 1);
  await shot('cas-conflict-draft-kept'); check('a rejected original CAS save retains the draft and is never silently retried');
  await editor().getByRole('button', { name: '返回核对连接', exact: true }).click(); await reopen();
  const testsBeforeMalformed = await count('models.connection.test');
  await page.evaluate(() => window.__PW_CONNECTION_TEST__.malformed());
  await editor().getByRole('button', { name: '测试连接', exact: true }).click();
  await editor().getByText('未能核对本次测试与当前草稿的对应关系，请返回核对连接。', { exact: true }).waitFor();
  assert.equal(await editor().getByRole('region', { name: '本次连接测试结果', exact: true }).count(), 0);
  assert.equal(await editor().getByRole('button', { name: '测试连接', exact: true }).isEnabled(), false, 'An unbound response cannot permit replay from the same editor');
  assert.equal(await count('models.connection.test'), testsBeforeMalformed + 1);
  check('a malformed original result stays unbound and prevents replay until the owner reviews the connection');
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth), true);
  record.widths = await page.evaluate(() => [...document.querySelectorAll('.pw-model-editor,.pw-form-grid,.pw-form-actions')].filter(node => node.getBoundingClientRect().width > 0).map(node => ({ class: node.className, width: node.clientWidth, scrollWidth: node.scrollWidth })));
  assert.ok(record.widths.every(item => item.scrollWidth <= item.width + 1));
  record.fixture = await page.evaluate(() => window.__PW_CONNECTION_TEST__.summary()); assert.deepEqual(record.fixture.errors, []); assert.deepEqual(record.errors, []);
  record.passed = true;
} catch (failure) {
  record.passed = false; record.failure = String(failure.stack || failure);
  if (page) { record.aria = await page.locator('body').ariaSnapshot().catch(() => ''); record.fixture = await page.evaluate(() => window.__PW_CONNECTION_TEST__?.summary()).catch(() => undefined); await shot('failure').catch(() => {}); }
  process.exitCode = 1;
} finally {
  record.finishedAt = new Date().toISOString(); await fs.writeFile(path.join(output, 'result.json'), JSON.stringify(record, null, 2));
  await context?.close(); await browser?.close(); if (server) await new Promise(resolve => server.close(resolve));
  console.log(JSON.stringify({ passed: record.passed, baseline, checks: record.checks, failure: record.failure, output }, null, 2));
}
