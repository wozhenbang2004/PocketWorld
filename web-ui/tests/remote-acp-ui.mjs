import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { chromium } from '@playwright/test';

// Actual production app and actual controls, with a deliberately synthetic native endpoint.
// This does not start Gemini, use an account, or prove Android/native ownership.
const argument = flag => { const at = process.argv.indexOf(flag), value = process.argv[at + 1]; if (at < 0 || !value || value.startsWith('--')) throw new Error('Required: --bundle <directory> --output <directory>'); return value; };
const bundle = path.resolve(argument('--bundle')), output = path.resolve(argument('--output'));
const index = await fs.readFile(path.join(bundle, 'index.html'), 'utf8');
const record = { scope: 'Mounted Vite production app at 360x800; isolated synthetic native observations and receipts. No Gemini process, credentials, provider, Android WebView or native authority assertion.', bundle: index.match(/assets\/(index-[^" ]+\.js)/)?.[1], viewport: { width: 360, height: 800 }, checks: [], screenshots: [], errors: [], startedAt: new Date().toISOString() };
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

function installFixture({ seed, scenario }) {
  const snapshot = structuredClone(seed), requests = [], errors = [], held = [], reviews = new Map(), original = new Map();
  let section = null, revision = 0, token = 'remote-acp-document-1', serial = 0, probeKind = 'available', observed = null, hold = false;
  const profile = { id: 'private-linux', name: '个人 Linux 工作区', baseUrl: 'https://companion.example.invalid', bindingHash: 'a'.repeat(64), hasToken: true };
  const environment = { id: 'linux-notes', label: '个人资料目录', kind: 'local', workspace: '/home/owner/notes', bindingHash: 'b'.repeat(64), writeAllowed: scenario !== 'write-disabled',
    workers: [{ id: 'codex_app_server', available: true }, { id: 'claude', available: true }, { id: 'pi', available: true }, { id: 'gemini_acp', available: true }] };
  const commands = ['app.ready', 'app.refresh', 'composer.reading', 'connections.enter', 'connections.leave', 'remote.environments', 'remote.probeWorker', 'remote.reviewTask', 'remote.confirmTask', 'remote.reconcileOperation', 'tasks.open'];
  snapshot.state.features.remote = { profiles: [profile], operations: [], jobs: [] };
  const copy = value => structuredClone(value);
  const publish = () => { snapshot.view = { ...snapshot.view, section }; snapshot.capabilities = { commands }; window.PocketWeb.receive({ v: 1, kind: 'state', revision: ++revision, snapshot: copy(snapshot) }); };
  const reply = (request, value, error) => window.PocketWeb.receive({ v: 1, kind: 'result', id: request.id, ok: !error, ...(error ? { error: { code: 'not_accepted', message: error } } : { value }) });
  const probe = args => {
    const available = probeKind === 'available', missing = probeKind === 'missing', noCredential = probeKind === 'credential-missing', mismatch = probeKind === 'mismatch';
    const observedAt = Date.now(), installation = missing ? 'missing' : 'observed';
    const body = {
      format: 'pocketworld.remote-backend-observation.v1', backendId: 'gemini_cli_acp_v1', worker: 'gemini_acp', backendVersion: '0.66.0', protocolVersion: 1,
      environmentId: environment.id, environmentBinding: environment.bindingHash, requestedModel: args.model || '', modeId: 'default', available, observedAt, expiresAt: observedAt + 300000,
      installObservation: { format: 'pocketworld.remote-install-observation.v1', state: installation, identity: missing ? '' : 'e'.repeat(64), kind: 'fixed_node_package_bytes_v1', packageVersion: missing ? '' : '0.66.0', nodeContentVerified: !missing, packageContentVerified: !missing, executionChainVerified: false, osSandboxVerified: false, reason: missing ? 'fixed_install_missing' : 'fixed_install_observed' },
      configurationRevision: missing ? '' : 'f'.repeat(64), profileRevision: missing ? '' : 'f'.repeat(64), serviceCapabilityRevision: available ? 'd'.repeat(64) : '',
      protocol: { state: available ? 'verified' : mismatch ? 'mismatch' : missing || noCredential ? 'not_checked' : 'unknown', agentName: available ? 'gemini-cli' : '', agentVersion: available ? '0.66.0' : '', wireVersion: available ? 1 : 0 },
      authentication: { state: missing ? 'unknown' : noCredential ? 'credential_missing' : 'credential_present', authenticated: false, source: 'owner_environment_presence_only' },
      model: { state: 'not_checked', requested: args.model || '', current: '', inferenceVerified: false }, mode: { state: 'not_checked', requested: 'default', current: '' },
      reason: { code: probeKind, message: available ? '已核对固定安装与初始化握手；认证和模型尚未验证。' : missing ? '未找到已批准的固定安装。' : noCredential ? '需要由本人在执行端预配置凭据；本页不会代为登录。' : mismatch ? '实际对端版本与固定合同不一致，未创建工作。' : '握手没有返回可核验结果；未据此判断安装或登录失败。' },
    };
    return { worker: 'gemini_acp', environmentId: environment.id, environmentBinding: environment.bindingHash, available, protocolVerified: available, accountConfigured: null, modelInferenceVerified: false, backendObservation: body,
      capabilityRevision: (++serial).toString(16).padStart(64, '0'), harnessObservation: { format: 'pocketworld.harness-capability-observation.v1', executionAuthorized: false } };
  };
  const execute = request => {
    const args = request.args; let value = {};
    try {
      if (request.hostDocumentToken !== token) throw new Error('Fixture stale original document');
      if (request.action.startsWith('remote.') && section !== 'remote') throw new Error('Fixture outside original remote section');
      switch (request.action) {
        case 'app.ready': publish(); value = { ready: true }; break;
        case 'app.refresh': publish(); break;
        case 'composer.reading': break;
        case 'connections.enter': section = args.section; publish(); break;
        case 'connections.leave': if (section === args.section) section = null; reviews.clear(); publish(); break;
        case 'remote.environments': value = { profileId: profile.id, profileBinding: profile.bindingHash, environments: [copy(environment)] }; break;
        case 'remote.probeWorker':
          if (args.profileId !== profile.id || args._profileBinding !== profile.bindingHash || args.environmentId !== environment.id || args.environmentBinding !== environment.bindingHash) throw new Error('Fixture source changed');
          if (args.worker !== 'gemini_acp') { value = { available: true }; break; }
          observed = probe(args); value = copy(observed); break;
        case 'remote.reviewTask': {
          if (args.worker !== 'gemini_acp' || args.backendId !== 'gemini_cli_acp_v1' || args.modeId !== 'default' || args.readOnly !== false) throw new Error('Fixture requires the exact private writable backend');
          if (!environment.writeAllowed || args.capabilityRevision !== observed?.capabilityRevision || observed?.available !== true || (args.model || '') !== observed.backendObservation.requestedModel) throw new Error('Fixture original writable environment/probe not current');
          if (Object.hasOwn(args, 'backendSelection') || Object.hasOwn(args, 'argv') || Object.hasOwn(args, 'effort') || Object.hasOwn(args, 'modelOptionsDigest')) throw new Error('Fixture accepts no synthesized native selection or different worker routing');
          const operationId = crypto.randomUUID(), reviewHandle = crypto.randomUUID();
          value = { ...copy(args), operationId, reviewHandle, operation: 'submitJob' };
          reviews.set(reviewHandle, { value: copy(value), token }); break;
        }
        case 'remote.confirmTask': {
          if (JSON.stringify(Object.keys(args)) !== '["reviewHandle"]') throw new Error('Fixture confirmation must carry only the original handle');
          const review = reviews.get(args.reviewHandle); reviews.delete(args.reviewHandle);
          if (!review || review.token !== token) throw new Error('Fixture original review closed or consumed');
          const id = review.value.operationId; original.set(id, review.value);
          snapshot.state.features.remote.operations = [{ profileId: profile.id, operationId: id, status: 'submitting' }];
          snapshot.state.tasks = [{ id, status: 'running', goal: review.value.prompt, raw: { id, feature: 'remote', command: 'submitJob', status: 'running' } }];
          value = { operationId: id, hostTaskId: id, status: 'running', message: '原任务已登记；等待执行端实际会话结果。' }; publish();
          if (hold) { held.push({ request, value }); return; } break;
        }
        case 'remote.reconcileOperation': {
          if (args.profileId !== profile.id || !original.has(args.operationId)) throw new Error('Fixture query not bound to original operation');
          const task = snapshot.state.tasks.find(task => task.id === args.operationId);
          value = { operationId: args.operationId, hostTaskId: args.operationId, status: task?.status === 'completed' ? 'completed' : 'unknown', message: task?.status === 'completed' ? '原任务已返回终态；请明确核对后再新建工作。' : '原操作仍待核对；此读取没有重新提交。' }; break;
        }
        case 'tasks.open': snapshot.view.taskId = args.id; publish(); value = { opened: true }; break;
        default: throw new Error('Unimplemented fixture command ' + request.action);
      }
      reply(request, value);
    } catch (failure) { errors.push(String(failure.message || failure)); reply(request, {}, String(failure.message || failure)); }
  };
  window.__pocketDocumentToken = token;
  window.PocketNative = { postMessage(raw) { const request = JSON.parse(raw); requests.push(copy(request)); queueMicrotask(() => execute(request)); } };
  window.__PW_ACP_TEST__ = {
    requests, probe(kind) { probeKind = kind; }, hold() { hold = true; },
    release() { hold = false; for (const item of held.splice(0)) reply(item.request, item.value); },
    rotate() { token = 'remote-acp-document-' + (++serial); reviews.clear(); window.PocketWeb.hostConfiguration({ documentToken: token }); },
    hidden(hidden) { Object.defineProperty(document, 'visibilityState', { configurable: true, value: hidden ? 'hidden' : 'visible' }); Object.defineProperty(document, 'hidden', { configurable: true, value: hidden }); document.dispatchEvent(new Event('visibilitychange')); },
    settle() { snapshot.state.tasks.forEach(task => { task.status = 'completed'; task.raw.status = 'completed'; }); snapshot.state.features.remote.operations.forEach(item => item.status = 'settled'); publish(); },
    summary() { return { requests: copy(requests), errors: copy(errors), held: held.length, section, operations: copy(snapshot.state.features.remote.operations) }; },
  };
}

let server, browser, context, page;
const check = name => record.checks.push({ name, passed: true });
const count = action => page.evaluate(action => window.__PW_ACP_TEST__.requests.filter(request => request.action === action).length, action);
const intent = () => page.getByRole('checkbox', { name: '我明确要在此环境开展可写工作，已知 Gemini 不提供只读隔离', exact: true });
const prepare = () => page.getByRole('button', { name: '审阅这次新工作', exact: true });
const probeButton = () => page.getByRole('button', { name: '核对执行器与模型能力', exact: true });
async function layoutReady() {
  await page.evaluate(async () => { await document.fonts.ready; await Promise.allSettled(document.getAnimations().filter(animation => animation instanceof CSSTransition && animation.transitionProperty === 'height').map(animation => animation.finished)); });
  await page.waitForFunction(() => {
    const nodes = [...document.querySelectorAll('.pw-secondary-scroll,.pw-settings-section')];
    const current = JSON.stringify(nodes.map(node => { const r = node.getBoundingClientRect(); return [r.x, r.y, r.width, r.height, node.scrollTop]; }));
    const previous = window.__acpLayout; window.__acpLayout = { current, stable: previous?.current === current ? previous.stable + 1 : 0 }; return window.__acpLayout.stable >= 3;
  }, null, { polling: 'raf' });
  await page.evaluate(async () => { await Promise.allSettled(document.getAnimations().filter(animation => animation instanceof CSSTransition).map(animation => animation.finished)); });
}
async function shot(name) { await layoutReady(); record.layouts ||= {}; record.layouts[name] = await page.evaluate(() => ({ scrollY: window.scrollY, documentScroll: document.scrollingElement.scrollTop, innerHeight, documentHeight: document.documentElement.scrollHeight, absoluteOverflow: [...document.querySelectorAll('body *')].filter(node => getComputedStyle(node).position === 'absolute' && node.getBoundingClientRect().bottom > innerHeight).slice(0, 12).map(node => ({ html: node.outerHTML.slice(0, 700), rect: node.getBoundingClientRect().toJSON(), offsetParent: node.offsetParent?.outerHTML.slice(0, 200) })), active: document.activeElement?.outerHTML.slice(0, 1000), regions: [...document.querySelectorAll('.pw-secondary,.pw-secondary-scroll,.pw-secondary-header')].map(node => ({ class: node.className, rect: node.getBoundingClientRect().toJSON(), scrollTop: node.scrollTop, scrollHeight: node.scrollHeight, overflow: getComputedStyle(node).overflow, height: getComputedStyle(node).height })) })); assert.equal(record.layouts[name].documentHeight, record.viewport.height, 'Original scroll container must contain the hidden form input; document must stay at viewport height'); assert.equal(record.layouts[name].scrollY, 0, 'Form focus must not scroll the whole application offscreen'); await page.screenshot({ path: path.join(output, name + '.png') }); record.screenshots.push(name + '.png'); }
async function select(label, option) {
  const trigger = page.locator('.pw-selection-field').filter({ has: page.getByText(label, { exact: true }) }).getByRole('button');
  await trigger.scrollIntoViewIfNeeded(); await layoutReady(); await trigger.click();
  await page.getByRole('listbox', { name: label, exact: true }).waitFor(); await page.getByRole('option', { name: option, exact: true }).click(); await layoutReady();
}
async function connect() {
  await page.getByRole('button', { name: '全部工作', exact: true }).click();
  await page.locator('.pw-work-drawer').getByRole('link', { name: '设置与连接', exact: true }).click();
  await page.getByRole('button', { name: /^设备与执行/ }).click(); await page.getByRole('button', { name: /^电脑与服务器/ }).click();
  await page.getByRole('button', { name: '使用此连接', exact: true }).click(); await page.getByRole('button', { name: '读取当前工作环境', exact: true }).click();
  await select('工作环境', '个人资料目录 · local');
}
async function chooseGemini() {
  assert.equal(await page.getByRole('checkbox', { name: '只读分析，不授权修改文件', exact: true }).isChecked(), true, 'Codex default stays readOnly');
  await select('执行器', 'Gemini CLI · ACP');
  assert.equal(await intent().isChecked(), false, 'Writable intent is never preselected');
  await page.getByLabel('这次要完成什么', { exact: true }).fill('只修改这次资料目录里的 README；保留所有原文件，完成后回报差异。');
}
async function probe(kind) {
  await page.evaluate(kind => window.__PW_ACP_TEST__.probe(kind), kind); const prior = await count('remote.probeWorker');
  await probeButton().click(); await page.waitForFunction(prior => window.__PW_ACP_TEST__.requests.filter(request => request.action === 'remote.probeWorker').length === prior + 1, prior);
  await page.getByLabel('Gemini 本次能力证据', { exact: true }).waitFor(); await layoutReady();
}
async function open(scenario) {
  if (context) await context.close();
  context = await browser.newContext({ viewport: record.viewport, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', serviceWorkers: 'block' });
  const origin = 'http://127.0.0.1:' + server.address().port;
  await context.route('**/*', route => new URL(route.request().url()).origin === origin ? route.continue() : route.abort());
  await context.addInitScript(installFixture, { seed: emptySnapshot(), scenario });
  page = await context.newPage(); page.setDefaultTimeout(8000); page.on('pageerror', error => record.errors.push(error.message));
  await page.goto(origin, { waitUntil: 'networkidle' }); await page.waitForFunction(() => document.documentElement.dataset.pocketUi === 'ready');
  await connect(); await chooseGemini();
}
try {
  await fs.mkdir(output, { recursive: true });
  server = http.createServer(async (req, res) => { try {
    const name = new URL(req.url, 'http://local').pathname, file = path.resolve(bundle, '.' + (name === '/' ? '/index.html' : name));
    if (!file.startsWith(bundle + '/')) throw new Error('invalid asset');
    const content = await fs.readFile(file); res.writeHead(200, { 'content-type': ({ '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.woff2': 'font/woff2', '.json': 'application/json', '.svg': 'image/svg+xml' })[path.extname(file)] || 'application/octet-stream' }); res.end(content);
  } catch { res.writeHead(404); res.end(); } });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  browser = await chromium.launch({ headless: true, args: ['--disable-background-networking'] });
  await open('evidence');
  for (const [kind, expected] of [['missing', '未找到固定安装'], ['credential-missing', '未检测到预配置凭据'], ['unknown', '握手结果未知'], ['mismatch', '协议或执行器版本不匹配']]) {
    await probe(kind); await page.getByText(expected, { exact: true }).waitFor(); assert.equal(await prepare().isEnabled(), false);
    if (kind !== 'missing') assert.equal(await page.getByText('未找到固定安装', { exact: true }).count(), 0);
    check('unavailable evidence is specific: ' + kind);
  }
  await shot('unavailable-handshake');
  await probe('available'); await page.getByText('检测到凭据配置；登录与认证尚未验证', { exact: true }).waitFor();
  assert.equal(await prepare().isEnabled(), false); assert.equal(await count('remote.confirmTask'), 0);
  await page.getByRole('button', { name: /模型、模式与本次证据范围/ }).click(); await page.getByText('尚未核实；首次原任务再核对实际模型', { exact: true }).waitFor();
  await shot('available-auth-unknown'); check('initialize availability is not presented as login or model success; mode and evidence limits visible');
  await page.getByLabel('请求模型 ID（可留空）', { exact: true }).fill('gemini-fixture-model');
  assert.equal(await page.getByLabel('Gemini 本次能力证据', { exact: true }).count(), 0); assert.equal(await prepare().isEnabled(), false); assert.equal(await intent().isChecked(), false);
  await probe('available'); await page.getByText('我明确要在此环境开展可写工作，已知 Gemini 不提供只读隔离', { exact: true }).click(); await prepare().click();
  const dialog = page.getByRole('dialog', { name: '确认在所选电脑创建工作', exact: true }); await dialog.waitFor();
  await dialog.getByText('只修改这次资料目录里的 README；保留所有原文件，完成后回报差异。', { exact: true }).waitFor(); await shot('exact-owner-review');
  const reviewed = await page.evaluate(() => window.__PW_ACP_TEST__.requests.find(request => request.action === 'remote.reviewTask').args);
  assert.equal(reviewed.backendId, 'gemini_cli_acp_v1'); assert.equal(reviewed.modeId, 'default'); assert.equal(reviewed.readOnly, false); assert.equal(reviewed.model, 'gemini-fixture-model'); assert.match(reviewed.capabilityRevision, /^[a-f0-9]{64}$/); assert.equal(Object.hasOwn(reviewed, 'backendSelection'), false);
  assert.equal(await page.evaluate(() => window.PocketWeb.handleBack()), true); await dialog.waitFor({ state: 'hidden' }); assert.equal((await page.evaluate(() => window.__PW_ACP_TEST__.summary())).section, 'remote');
  check('model change discards prior proof; native revision echoed without selection; original review displays full prompt and Back closes only the review');
  await prepare().click(); await dialog.waitFor(); await dialog.getByRole('button', { name: '确认创建一次', exact: true }).click();
  await page.getByText('原任务已登记；等待执行端实际会话结果。', { exact: true }).waitFor();
  assert.equal(await count('remote.confirmTask'), 1); assert.equal(await prepare().isEnabled(), false); check('one explicit confirmation starts one original writable job and locks new submissions');
  record.evidenceJourney = await page.evaluate(() => window.__PW_ACP_TEST__.summary()); assert.deepEqual(record.evidenceJourney.errors, []);

  await open('write-disabled'); await probe('available');
  assert.equal(await intent().isEnabled(), false); assert.equal(await prepare().isEnabled(), false); await page.getByText('所选环境尚未允许写入，不能创建 Gemini 工作。可核对能力，或选择支持只读的执行器。', { exact: true }).waitFor();
  assert.equal(await count('remote.reviewTask'), 0); await shot('environment-write-disabled'); check('an environment without explicit writeAllowed cannot review this backend');

  await open('hidden'); await probe('available'); await page.getByText('我明确要在此环境开展可写工作，已知 Gemini 不提供只读隔离', { exact: true }).click(); await prepare().click();
  await page.getByRole('dialog', { name: '确认在所选电脑创建工作', exact: true }).waitFor();
  await page.evaluate(() => window.__PW_ACP_TEST__.rotate());
  await page.getByRole('dialog', { name: '确认在所选电脑创建工作', exact: true }).waitFor({ state: 'hidden' });
  assert.equal(await intent().isChecked(), false); assert.equal(await prepare().isEnabled(), false); assert.equal(await count('remote.confirmTask'), 0);
  assert.equal(await page.getByLabel('这次要完成什么', { exact: true }).inputValue(), '只修改这次资料目录里的 README；保留所有原文件，完成后回报差异。');
  await page.getByRole('button', { name: '读取当前工作环境', exact: true }).click(); await probe('available'); await page.getByText('我明确要在此环境开展可写工作，已知 Gemini 不提供只读隔离', { exact: true }).click(); await prepare().click();
  await page.evaluate(() => window.__PW_ACP_TEST__.hold());
  await page.getByRole('dialog', { name: '确认在所选电脑创建工作', exact: true }).getByRole('button', { name: '确认创建一次', exact: true }).click();
  await page.waitForFunction(() => window.__PW_ACP_TEST__.summary().held === 1);
  const originalRequest = await page.evaluate(() => window.__PW_ACP_TEST__.summary().operations[0].operationId);
  await page.evaluate(() => { window.__PW_ACP_TEST__.hidden(true); window.__PW_ACP_TEST__.rotate(); window.__PW_ACP_TEST__.release(); window.__PW_ACP_TEST__.hidden(false); });
  const unknownText = '这次原提交的结果待核对。草稿与原操作已保留，不会再次确认或重发。';
  await page.getByText(unknownText, { exact: true }).waitFor(); assert.equal(await count('remote.confirmTask'), 1);
  assert.equal(await page.getByText('原任务已登记；等待执行端实际会话结果。', { exact: true }).count(), 0, 'Late old receipt must not replace current unknown');
  await page.getByRole('button', { name: '查询原操作', exact: true }).click(); await page.getByText('原操作仍待核对；此读取没有重新提交。', { exact: true }).waitFor();
  const read = await page.evaluate(() => window.__PW_ACP_TEST__.requests.find(request => request.action === 'remote.reconcileOperation').args); assert.equal(read.operationId, originalRequest);
  await page.getByText(unknownText, { exact: true }).waitFor(); assert.equal(await count('remote.confirmTask'), 1); await shot('pending-hidden-unknown');
  await page.getByRole('region', { name: '电脑与服务器', exact: true }).getByRole('button', { name: '返回', exact: true }).click();
  await page.getByRole('button', { name: /^电脑与服务器/ }).waitFor(); await page.getByRole('button', { name: /^电脑与服务器/ }).click(); await page.getByRole('button', { name: '使用此连接', exact: true }).click();
  await page.getByText(unknownText, { exact: true }).waitFor(); await page.getByRole('button', { name: /保留的原工作草稿/ }).click();
  await page.getByText('只修改这次资料目录里的 README；保留所有原文件，完成后回报差异。', { exact: true }).waitFor(); assert.equal(await count('remote.confirmTask'), 1);
  await shot('unknown-after-back'); check('document-only invalidation clears review; hidden pending keeps original ID and draft; late receipt and read do not unlock/replay; Back leaves and return restores original unknown');
  await page.evaluate(() => window.__PW_ACP_TEST__.settle()); await page.getByRole('button', { name: '查询原操作', exact: true }).click();
  await page.getByText('原任务已返回终态；请明确核对后再新建工作。', { exact: true }).waitFor(); await page.getByText(unknownText, { exact: true }).waitFor();
  await page.getByRole('button', { name: '已核对原任务，新建另一项工作', exact: true }).click();
  assert.equal(await page.getByText(unknownText, { exact: true }).count(), 0); assert.equal(await count('remote.confirmTask'), 1);
  check('only explicit acknowledgement of the settled original task opens preparation of another job; no automatic resend');
  record.hiddenJourney = await page.evaluate(() => window.__PW_ACP_TEST__.summary()); assert.deepEqual(record.hiddenJourney.errors, []);
  assert.deepEqual(record.errors, []); record.status = 'PASS';
} catch (failure) {
  record.status = 'FAIL'; record.failure = String(failure.stack || failure);
  if (page) { await page.screenshot({ path: path.join(output, 'failure.png') }).catch(() => {}); record.screenshots.push('failure.png'); record.lastState = await page.evaluate(() => window.__PW_ACP_TEST__?.summary()).catch(() => null); }
  process.exitCode = 1;
} finally {
  record.finishedAt = new Date().toISOString(); await fs.mkdir(output, { recursive: true }); await fs.writeFile(path.join(output, 'result.json'), JSON.stringify(record, null, 2) + '\n');
  console.log(JSON.stringify({ status: record.status, checks: record.checks.length, bundle: record.bundle, output, failure: record.failure }, null, 2));
  await context?.close(); await browser?.close(); if (server) await new Promise(resolve => server.close(resolve));
}
