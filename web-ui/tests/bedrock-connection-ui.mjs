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
const baseline = false;
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
  let revision=0, section=null, selected='connection-a', holdSave=false, documentToken='bedrock-fixture-document';
  window.__pocketDocumentToken=documentToken;
  const connections=[{id:'connection-a',revision:7,name:'原 Chat 连接',provider:'openai_chat',baseUrl:'https://chat.example.invalid/v1',model:'fixture-model',enabled:true,hasApiKey:true,
    configuration:{sendScreenshots:false,supportsImageInput:false,maxOutputTokens:4096,compatibility:null}}];
  const commands=['app.ready','app.refresh','composer.reading','connections.enter','connections.leave','models.snapshot','settings.reviewModelTransport','models.connection.add','models.connection.edit','models.connection.test','models.connection.select','tasks.open'];
  const models=()=>({connections:{connections:structuredClone(connections),selectedConnectionId:selected},models:[],catalogAvailable:connections.find(row=>row.id===selected)?.provider!=='bedrock_anthropic'});
  const publish=()=>{const current=connections.find(row=>row.id===selected);Object.assign(snapshot.state,{modelName:current.model,modelProvider:current.provider,baseUrl:current.baseUrl,hasApiKey:current.hasApiKey,modelConnections:models()});snapshot.view.section=section;snapshot.capabilities={commands};window.PocketWeb.receive({v:1,kind:'state',revision:++revision,snapshot:structuredClone(snapshot)});};
  const reply=(request,value,error)=>window.PocketWeb.receive({v:1,kind:'result',id:request.id,ok:!error,...(error?{error}:{value})});
  const execute=request=>{
    const args=request.args;let value={},error=null;
    try {
      if(request.hostDocumentToken!==documentToken)throw new Error('Fixture original document changed');
      if(request.action.startsWith('models.')&&section!=='models')throw new Error('Fixture original section changed');
      switch(request.action){
        case 'app.ready':publish();value={ready:true};break;
        case 'app.refresh':case 'models.snapshot':publish();value=models();break;
        case 'composer.reading':break;
        case 'connections.enter':section=args.section;publish();break;
        case 'connections.leave':if(section===args.section)section=null;publish();break;
        case 'settings.reviewModelTransport':value={required:false,consentTarget:''};break;
        case 'models.connection.add':case 'models.connection.edit':{
          const adding=request.action.endsWith('.add');let original=connections.find(row=>row.id===args.id);
          if(!adding&&(!original||original.revision!==args.expectedRevision)){error={code:'not_accepted',message:'原连接修订已变化'};break;}
          if(args.provider!=='bedrock_anthropic'||args.url!==`https://bedrock-runtime.${args.bedrockRegion}.amazonaws.com`)throw new Error('Unexpected Bedrock target');
          if(!original){original={id:'connection-bedrock',revision:0,enabled:true};connections.push(original);selected=original.id;}
          const replacement=args.awsSession;
          Object.assign(original,{name:'AWS 原连接',revision:original.revision+1,provider:args.provider,baseUrl:args.url,model:args.model,bedrockRegion:args.bedrockRegion,hasApiKey:false,hasAwsSession:true,credentialKind:'aws_sts_session',credentialStatus:'saved_unverified',
            credentialExpiresAt:replacement?.expiresAt||original.credentialExpiresAt,credentialRevision:(original.credentialRevision||0)+(replacement?1:0)});
          original.configuration={sendScreenshots:args.screenshots,supportsImageInput:args.supportsImageInput,maxOutputTokens:args.maxOutputTokens,bedrockRegion:args.bedrockRegion,compatibility:null};
          value={status:'saved',snapshot:models()};
          if(holdSave){held.push({request,value});return;}
          publish();break;
        }
        case 'models.connection.test':{
          const original=connections.find(row=>row.id===args.id);
          if(!original||original.revision!==args.expectedRevision||args.awsSession)throw new Error('Unsaved fixture connection test');
          const reference={taskId:crypto.randomUUID(),runId:crypto.randomUUID(),epoch:1,purpose:'CONNECTION_TEST',accountingOnly:true,executionAuthorized:false};
          value={status:'tested',snapshot:{ok:true,message:'文字连接正常；区域可用性仅来自本次原连接测试。图片和工具能力尚未验证。',visionVerified:false,toolsVerified:false,inferenceReference:reference,
            connectionTest:{format:'pocketworld.connection-test.v1',testId:crypto.randomUUID(),connectionId:args.id,connectionRevision:args.expectedRevision,editorId:args.editorId,draftRevision:args.draftRevision,draftFingerprint:'c'.repeat(64),presentationCurrent:true,outcome:'completed',inferenceReference:reference},
            inferenceFeedback:{available:true,source:'original_task_inference_receipts',outcomeUnknown:false,calls:[{callId:'bedrock-fixture-call',outcome:'COMPLETED',protocol:args.provider,requestedModel:args.model,responseModel:null,providerProjection:{hintPrepared:false,dispatchAttempted:true,cacheHitKnown:false,reason:'cache_disabled'},cacheCounters:{CACHE_READ:{value:null,source:'unknown'},CACHE_WRITE:{value:null,source:'unknown'}},actualCost:null,actualCostSource:'unknown'}]}}};break;
        }
        case 'models.connection.select':selected=args.id;publish();break;
        case 'tasks.open':value={opened:true};break;
        default:throw new Error('Unimplemented fixture action '+request.action);
      }
    } catch(failure){error={code:'outcome_unknown',message:String(failure.message||failure)};errors.push(error.message);}
    reply(request,value,error);
  };
  window.PocketNative={postMessage(raw){const request=JSON.parse(raw);requests.push(structuredClone(request));queueMicrotask(()=>execute(request));}};
  window.__PW_CONNECTION_TEST__={requests,hold(){holdSave=true;},changeDocument(){documentToken+='-next';window.PocketWeb.hostConfiguration({documentToken});},release(){holdSave=false;publish();for(const item of held.splice(0))reply(item.request,item.value,null);},expire(){const row=connections.find(row=>row.id==='connection-bedrock');row.credentialExpiresAt='2020-01-01T00:00:00Z';row.credentialStatus='expired';publish();},summary(){return {section,pending:held.length,errors,connections:structuredClone(connections),requests:requests.map(request=>({action:request.action,args:{...structuredClone(request.args),...(Object.hasOwn(request.args,'apiKey')?{apiKey:request.args.apiKey?'[fixture omitted]':''}:{}),...(request.args.awsSession?{awsSession:{...request.args.awsSession,accessKeyId:'[fixture omitted]',secretAccessKey:'[fixture omitted]',sessionToken:'[fixture omitted]'}}:{})}}))};}};
}

let server, browser, context, page;
const check = name => record.checks.push({ name, passed: true });
const count = action => page.evaluate(action => window.__PW_CONNECTION_TEST__.requests.filter(request => request.action === action).length, action);
const editor = () => page.locator('.pw-model-editor');
async function layoutReady() {
  await page.evaluate(async () => {
    await document.fonts.ready;
    const transitions = document.getAnimations().filter(animation => animation instanceof CSSTransition && animation.transitionProperty === 'height');
    await Promise.allSettled(transitions.map(animation => animation.finished));
  });
  await page.waitForFunction(() => {
    const nodes = [...document.querySelectorAll('.pw-secondary-scroll,.pw-model-editor,[aria-label="本次连接测试结果"]')];
    const current = JSON.stringify(nodes.map(node => { const rect = node.getBoundingClientRect(); return [rect.x, rect.y, rect.width, rect.height, node.scrollTop]; }));
    const previous = window.__modelFixtureLayout;
    window.__modelFixtureLayout = { current, stable: previous?.current === current ? previous.stable + 1 : 0 };
    return window.__modelFixtureLayout.stable >= 3;
  }, null, { polling: 'raf' });
}
async function shot(name) { await layoutReady(); await page.screenshot({ path: path.join(output, name + '.png') }); record.screenshots.push(name + '.png'); }
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
  if (label === '流式用量请求') {
    await page.mouse.move(4, 100); await page.mouse.wheel(0, -80); await layoutReady();
    assert.equal(await menu.isVisible(), true, 'The modal select must remain available during a background wheel gesture');
    assert.equal(await formScroll(), before, 'The background form must remain fixed while the modal select is open');
  }
  await page.getByRole('option', { name: option, exact: true }).click();
  record.selectChecks ||= []; record.selectChecks.push({ label, option, formScroll: before, passed: true });
}
async function expandPolicy() { await editor().getByRole('button', { name: /请求兼容 输出字段/ }).click(); await layoutReady(); }
async function expandCache() { await editor().getByRole('button', { name: /前缀缓存 服务商支持/ }).click(); await layoutReady(); }
async function reopen() { await page.getByRole('region', { name: '连接详情', exact: true }).getByRole('button', { name: '配置与测试', exact: true }).click(); await editor().waitFor(); }
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
  await page.getByRole('button', { name: '打开导航', exact: true }).click();
  await page.locator('.pw-work-drawer').getByRole('link', { name: '设置', exact: true }).click();
  await page.getByRole('button', { name: /^当前模型/ }).click(); await page.getByRole('button', { name: '管理连接', exact: true }).click();
  await page.getByRole('button',{name:'添加 API 连接',exact:true}).click();await editor().waitFor();
  await select('接口协议','AWS Bedrock · Claude Messages');
  assert.equal(await editor().getByLabel('AWS 区域',{exact:true}).inputValue(),'');assert.equal(await editor().getByLabel('模型名称',{exact:true}).inputValue(),'');
  assert.equal(await editor().getByLabel('区域端点（自动生成）',{exact:true}).getAttribute('readonly'),'');
  assert.equal(await editor().getByRole('button',{name:'测试连接',exact:true}).isEnabled(),false);check('new provider has no automatic region/model and an unsaved connection cannot test');
  await editor().getByLabel('AWS 区域',{exact:true}).fill('us-east-1');await editor().getByLabel('模型名称',{exact:true}).fill('anthropic.claude-sonnet-4-6');
  assert.equal(await editor().getByLabel('区域端点（自动生成）',{exact:true}).inputValue(),'https://bedrock-runtime.us-east-1.amazonaws.com');
  async function credentials(suffix='') {await editor().getByLabel('临时 Access Key ID',{exact:true}).fill('ASIAABCDEFGHIJKLMNOP');await editor().getByLabel('临时 Secret Access Key',{exact:true}).fill('FixtureSecretAccessKey0123456789+/='+suffix);await editor().getByLabel('Session Token',{exact:true}).fill('FixtureSessionToken0123456789+/='+suffix);await editor().getByLabel('UTC 到期时间',{exact:true}).fill('2035-01-02T03:04:05Z');}
  await credentials();await shot('bedrock-new-session');await editor().getByRole('button',{name:'添加并用于后续任务',exact:true}).click();await page.getByRole('region',{name:'连接详情',exact:true}).waitFor();
  let state=await page.evaluate(()=>window.__PW_CONNECTION_TEST__.summary());let add=state.requests.find(row=>row.action==='models.connection.add');
  assert.deepEqual(Object.keys(add.args.awsSession).sort(),['accessKeyId','secretAccessKey','sessionToken','expiresAt'].sort());assert.equal(add.args.apiKey,'');assert.equal(add.args.bedrockRegion,'us-east-1');assert.equal(Object.hasOwn(add.args,'credentialRevision'),false);assert.equal(await count('models.connection.test'),0);check('explicit save sends one typed whole session and no caller credential/native revision');
  await reopen();assert.equal(await editor().getByLabel('临时 Access Key ID',{exact:true}).isVisible(),false);assert.equal(await editor().getByRole('button',{name:'测试连接',exact:true}).isEnabled(),true);await shot('bedrock-saved-collapsed');
  check('saved original credentials reopen as public expiry and progressive replacement without secret values');
  await editor().getByRole('button',{name:'测试连接',exact:true}).click();await editor().getByRole('region',{name:'本次连接测试结果',exact:true}).waitFor();await shot('bedrock-test-original');assert.equal(await count('models.connection.test'),1);
  state=await page.evaluate(()=>window.__PW_CONNECTION_TEST__.summary());const test=state.requests.find(row=>row.action==='models.connection.test');assert.equal(test.args.id,'connection-bedrock');assert.equal(test.args.expectedRevision,1);assert.equal(Object.hasOwn(test.args,'awsSession'),false);check('one explicit test retains exact saved connection/revision and no secret draft');
  await editor().getByRole('button',{name:/替换短期凭据 整组替换/}).click();await credentials('ROTATED');assert.equal(await editor().getByRole('button',{name:'测试连接',exact:true}).isEnabled(),false);await editor().getByRole('button',{name:'保存原连接',exact:true}).click();await page.getByRole('region',{name:'连接详情',exact:true}).waitFor();
  state=await page.evaluate(()=>window.__PW_CONNECTION_TEST__.summary());const rotated=state.connections.find(row=>row.id==='connection-bedrock');assert.equal(rotated.revision,2);assert.equal(rotated.credentialRevision,2);check('replacement is a whole-session save on the original native row, then a new original revision');
  await reopen();await editor().getByRole('button',{name:/替换短期凭据 整组替换/}).click();await credentials('DRAFT');await page.evaluate(()=>window.dispatchEvent(new Event('pagehide')));
  assert.equal(await editor().getByLabel('临时 Access Key ID',{exact:true}).inputValue(),'');assert.equal(await editor().getByLabel('临时 Secret Access Key',{exact:true}).inputValue(),'');assert.equal(await editor().getByLabel('Session Token',{exact:true}).inputValue(),'');assert.equal(await editor().getByLabel('AWS 区域',{exact:true}).inputValue(),'us-east-1');assert.equal(await editor().getByLabel('UTC 到期时间',{exact:true}).inputValue(),'2035-01-02T03:04:05Z');check('pagehide clears all three secret drafts while preserving public target and expiry draft');
  await credentials('PENDING');await editor().getByLabel('模型名称',{exact:true}).fill('anthropic.claude-opus-4-6-v1');await page.evaluate(()=>window.__PW_CONNECTION_TEST__.hold());const before=await count('models.connection.edit');await editor().getByRole('button',{name:'保存原连接',exact:true}).click();await page.waitForFunction(()=>window.__PW_CONNECTION_TEST__.summary().pending===1);
  await page.evaluate(()=>window.__PW_CONNECTION_TEST__.changeDocument());await editor().getByText('原连接的保存结果待核对；本表单不会再次保存。请读取连接列表，核对原记录与修订。',{exact:true}).waitFor();
  assert.equal(await editor().getByLabel('Session Token',{exact:true}).inputValue(),'');assert.equal(await editor().getByLabel('模型名称',{exact:true}).inputValue(),'anthropic.claude-opus-4-6-v1');assert.equal(await editor().getByRole('button',{name:'保存原连接',exact:true}).isEnabled(),false);await layoutReady();const notice=await editor().getByText('原连接的保存结果待核对；本表单不会再次保存。请读取连接列表，核对原记录与修订。',{exact:true}).boundingBox();assert.ok(notice&&notice.y>=0&&notice.y+notice.height<record.viewport.height-100,'The unknown notice is visible above the fixed actions');await shot('bedrock-save-unknown');
  await page.evaluate(()=>window.__PW_CONNECTION_TEST__.release());await layoutReady();assert.equal(await count('models.connection.edit'),before+1);assert.equal(await editor().getByRole('button',{name:'保存原连接',exact:true}).isEnabled(),false);await editor().getByText('原连接的保存结果待核对；本表单不会再次保存。请读取连接列表，核对原记录与修订。',{exact:true}).waitFor();check('native document replacement makes pending save unknown, preserves draft, and late success cannot unlock or replay it');
  await editor().getByRole('button',{name:'返回核对连接',exact:true}).click();await page.getByRole('region',{name:'连接详情',exact:true}).waitFor();await page.evaluate(()=>window.__PW_CONNECTION_TEST__.expire());await reopen();
  await editor().getByText('已过期 · 请替换后保存',{exact:true}).waitFor();assert.equal(await editor().getByRole('button',{name:'测试连接',exact:true}).isEnabled(),false);await editor().getByRole('button',{name:/替换短期凭据 整组替换/}).scrollIntoViewIfNeeded();await shot('bedrock-expired');check('expired original identity remains readable with an explicit replacement path and no test');
  assert.equal(await editor().getByLabel('临时 Access Key ID',{exact:true}).isVisible(),true,'Expired sessions expose replacement fields immediately');await credentials('AFTEREXPIRY');await editor().getByRole('button',{name:'保存原连接',exact:true}).click();await page.getByRole('region',{name:'连接详情',exact:true}).waitFor();await reopen();assert.equal(await editor().getByRole('button',{name:'测试连接',exact:true}).isEnabled(),true);assert.equal(await count('models.connection.test'),1);check('explicit whole-session replacement resolves expiry without automatic testing or replay');
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=document.documentElement.clientWidth),true);
  const widths=await page.locator('.pw-model-editor,.pw-form-grid,.pw-model-save-actions').evaluateAll(nodes=>nodes.filter(node=>node.getBoundingClientRect().width>0).map(node=>({width:node.clientWidth,scrollWidth:node.scrollWidth})));assert.ok(widths.every(row=>row.scrollWidth<=row.width+1));record.widths=widths;
  record.fixture=await page.evaluate(()=>window.__PW_CONNECTION_TEST__.summary());assert.deepEqual(record.fixture.errors,[]);assert.deepEqual(record.errors,[]);record.passed=true;
} catch (failure) {
  record.passed = false; record.failure = String(failure.stack || failure);
  if (page) { record.aria = await page.locator('body').ariaSnapshot().catch(() => ''); record.fixture = await page.evaluate(() => window.__PW_CONNECTION_TEST__?.summary()).catch(() => undefined); await shot('failure').catch(() => {}); }
  process.exitCode = 1;
} finally {
  record.finishedAt = new Date().toISOString(); await fs.writeFile(path.join(output, 'result.json'), JSON.stringify(record, null, 2));
  await context?.close(); await browser?.close(); if (server) await new Promise(resolve => server.close(resolve));
  console.log(JSON.stringify({ passed: record.passed, baseline, checks: record.checks, failure: record.failure, output }, null, 2));
}
