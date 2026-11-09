/** Production App bundle + synthetic native host; never Android or provider proof.
 * Build first. Use --serve for the shared browser when local Chromium is blocked.
 * /?scenario=fresh (default), running, failed, or skipped; &theme=dark.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {chromium} from '@playwright/test';

const root=path.resolve(import.meta.dirname,'..');
const argument=(name,fallback)=>{const i=process.argv.indexOf(name);return i<0?fallback:process.argv[i+1];};
const bundle=path.resolve(argument('--bundle',path.join(root,'../app/src/main/assets/pocket-ui')));
const output=path.resolve(argument('--output',path.join(root,'../build-artifacts/setup-renderer')));
const port=Number(argument('--port','46199'));
const source=await fs.readFile(path.join(root,'tests/model-connection-ui.mjs'),'utf8');
const empty=source.match(/function emptySnapshot\(\)\s*\{[\s\S]*?\n\}\n/)?.[0];
assert.ok(empty,'Reuse the existing production App empty-host schema');
const index=await fs.readFile(path.join(bundle,'index.html'),'utf8');
const asset=index.match(/assets\/(index-[^" ]+\.js)/)?.[1];
assert.ok(asset,'Build the production App before running setup renderer checks');
const record={scope:'Production React App and synthetic native host. No real AI inference, Android permission grant, installer, system back or native persistence is proven by this fixture.',
  startedAt:new Date().toISOString(),bundle:asset,bundleSha256:createHash('sha256').update(await fs.readFile(path.join(bundle,'assets',asset))).digest('hex'),checks:[],screenshots:[],errors:[]};

function installSetupFixture(seed){
 const query=new URLSearchParams(location.search),scenario=query.get('scenario')||'fresh',dark=query.get('theme')==='dark';
 const key='pocketworld.synthetic-setup.'+scenario,stored=JSON.parse(sessionStorage.getItem(key)||'null');
 const snapshot=structuredClone(seed),state=snapshot.state,requests=[],errors=[];
 const preferences=stored?.preferences||{step:['running','failed'].includes(scenario)?'capabilities':'welcome',capabilities:['running','failed'].includes(scenario)?['tools']:[],status:scenario==='skipped'?'skipped':'new'};
 let setup=stored?.setup||{version:1,revision:1,preferences,model:{configured:false,label:'',tested:false},
  permissions:{accessibility:false,notifications:false,microphone:false,batteryExempt:false,vendorBackgroundVisited:false},speech:{available:true,networkUse:'unknown'}};
 setup.preferences=preferences;
 let connections=stored?.connections||[],selected=stored?.selected||'',revision=0,section=null,busy=false,serial=0,review=null;
 const documentToken='setup-renderer-'+crypto.randomUUID();window.__pocketDocumentToken=documentToken;
 Object.assign(state,{appearance:{mode:dark?'dark':'light',dark},modelName:selected,modelRoute:'api_key',modelSelectionRevision:stored?.selectionRevision||1});
 snapshot.host={connected:true,insetsHandledByNative:true};snapshot.view.resumeRevision=0;
 const commands=['app.ready','app.refresh','composer.reading','composer.edit','connections.enter','connections.leave',
  'setup.read','setup.save','setup.model.test','setup.permission','setup.runtime.read','setup.runtime.review','setup.runtime.start','setup.runtime.cancel','setup.runtime.dismiss',
  'models.snapshot','models.connections.add','models.catalog.snapshot','models.catalog.review','models.catalog.select','settings.reviewModelTransport',
  'models.api.open','models.api.close','models.api.review','models.api.select','models.api.favorite'];
 const clone=value=>structuredClone(value);
 const modelSnapshot=()=>({connections:{connections:clone(connections),selectedConnectionId:selected?connections[0]?.id:null},nativeSelection:{model:selected,modelRoute:'api_key'},models:[]});
 const persist=()=>sessionStorage.setItem(key,JSON.stringify({setup,preferences:setup.preferences,connections,selected,selectionRevision:state.modelSelectionRevision}));
 const publish=()=>{state.modelConnections=modelSnapshot();snapshot.view.section=section;snapshot.capabilities={commands};window.PocketWeb.receive({v:1,kind:'state',revision:++revision,snapshot:clone(snapshot)});};
 const reply=(request,value,error)=>window.PocketWeb.receive({v:1,kind:'result',id:request.id,ok:!error,...(error?{error}:{value:clone(value)})});
 const log=text=>btoa(Array.from(new TextEncoder().encode(text),byte=>String.fromCharCode(byte)).join(''));
 let runtime={format:'pocketworld.runtime-setup-view.v1',status:'not_prepared',ready:false,canPrepare:true,canRetry:false,canCancel:false,
  message:'需要运行项目或本机技能时再准备工具；普通对话可以先使用。',runtime:{canExecute:false,preparationAvailable:true},preparation:{},operation:null,logs:null};
 const runtimeOperation=()=>({taskId:'synthetic-runtime-task-'+serial,runId:'synthetic-runtime-run-'+serial,epoch:serial,operationId:'synthetic-runtime-op-'+serial,
  status:'running',phase:'installing_system_tools',completedBytes:0,totalBytes:0,progressUnit:null,fraction:null,error:null,receipt:{synthetic:true,ownedWorkerLive:true}});
 const startRuntime=()=>{serial++;runtime={...runtime,status:'running',canPrepare:false,canRetry:false,canCancel:true,message:'正在准备本机工具；可以离开此页面，返回后继续查看同一次安装。',
  operation:runtimeOperation(),logs:{chunks:[{sequence:1,stream:'stdout',dataBase64:log('synthetic host: installing system tools\n'),bytes:44}],nextCursor:1,hasMore:false,logIncompleteTail:false,outputTruncated:false}};};
 const failRuntime=()=>{if(!runtime.operation)startRuntime();runtime={...runtime,status:'failed',canPrepare:true,canRetry:true,canCancel:false,message:'本次准备未完成。请查看具体错误和原日志，再明确重试。',
  operation:{...runtime.operation,status:'failed',phase:'failed',error:{code:'runtime_download_failed',message:'HTTP 503：工具下载服务暂时不可用',details:{source:'synthetic renderer fixture',httpStatus:503,stderr:'fixture.example.invalid returned 503'}}},
  logs:{chunks:[{sequence:2,stream:'stderr',dataBase64:log('fixture.example.invalid: HTTP 503\n下载未完成；未启用新的工具版本。\n'),bytes:80}],nextCursor:2,hasMore:false,logIncompleteTail:false,outputTruncated:false}};};
 if(scenario==='running')startRuntime();if(scenario==='failed')failRuntime();
 const apiModels=()=>connections.filter(row=>row.model).map(row=>({connectionId:row.id,connectionRevision:row.revision,connectionName:row.name,modelId:row.model,
  available:true,favorite:false,selected:selected===row.model,catalogFresh:true,lastUsedAt:0,imageInput:{known:false},toolCalls:{known:false}}));
 const apiProjection=()=>({windowId:'synthetic-model-window',format:'pocketworld.model-picker.v1',viewGeneration:1,selectionRevision:state.modelSelectionRevision,preferencesAvailable:true,models:apiModels()});
 const selectModel=model=>{selected=model;state.modelName=model;state.modelSelectionRevision++;setup.model={configured:true,label:model,tested:false};persist();publish();};
 const execute=request=>{
  const {action,args={}}=request;let value={};
  if(request.hostDocumentToken!==documentToken)throw Error('Wrong synthetic host document');
  if(action.startsWith('setup.')&&action!=='setup.read'&&section!=='setup')throw Error('Setup command outside its active surface');
  switch(action){
   case 'app.ready': publish();value={ready:true};break;
   case 'app.refresh': publish();break;
   case 'composer.reading':break;
   case 'composer.edit':snapshot.composer.text=args.text;snapshot.composer.revision++;publish();break;
   case 'connections.enter':section=args.section;publish();break;
   case 'connections.leave':if(section===args.section){section=null;review=null;}publish();break;
   case 'setup.read':value=setup;break;
   case 'setup.save':
    if(args.expectedRevision!==setup.revision)throw Error('Setup revision changed');
    if(args.status==='new'||args.status==='completed'&&args.step!=='finish')throw Error('Invalid native first-use progress');
    setup={...setup,revision:setup.revision+1,preferences:{step:args.step,capabilities:clone(args.capabilities),status:args.status}};persist();value=setup;break;
   case 'setup.model.test':
    if(!setup.model.configured)throw Error('No model was selected');
    setup.model={...setup.model,tested:true,message:'文本连接测试通过；工具与图像能力尚未验证。'};persist();value=setup;break;
   case 'setup.permission':{
    const permission={accessibility:'accessibility',notifications:'notifications',microphone:'microphone',battery:'batteryExempt',vendorBackground:'vendorBackgroundVisited'}[args.kind];
    if(!permission)throw Error('Unknown permission');value={opened:true};
    // Only the fixture simulates a later host resume; no OS permission is opened.
    setTimeout(()=>{setup.permissions[permission]=true;if(args.kind==='vendorBackground')setup.revision++;persist();snapshot.view.resumeRevision++;publish();},80);break;
   }
   case 'setup.runtime.read':
    if(args.taskId&&runtime.operation?.taskId!==args.taskId)throw Error('Changed runtime task');
    value=clone(runtime);if(value.logs)value.logs.chunks=value.logs.chunks.filter(chunk=>chunk.sequence>(args.afterSequence||0));break;
   case 'setup.runtime.review':
    review={reviewHandle:'synthetic-review-'+crypto.randomUUID(),review:{requestId:'synthetic-review-request',expiresAt:Date.now()+120000,mode:args.mode,title:'准备手机开发工具',
     summary:'下载并检查工具文件，再安装运行项目所需的依赖。',downloadBytes:83_886_080,minimumFreeBytes:805_306_368,additionalDownloads:true,existingGenerationRetained:true,executionAuthorized:false,
     effects:{environment:'app-private Linux tools',network:'downloads and package dependencies',synthetic:true},runtimeIdentity:'synthetic-runtime',catalogIdentity:'synthetic-catalog'}};value=review;break;
   case 'setup.runtime.dismiss':if(review?.reviewHandle===args.reviewHandle)review=null;break;
   case 'setup.runtime.start':
    if(!review||review.reviewHandle!==args.reviewHandle||Object.keys(args).length!==1)throw Error('Changed native review handle');
    review=null;startRuntime();value=runtime;break;
   case 'setup.runtime.cancel':
    if(!runtime.operation||args.taskId!==runtime.operation.taskId||args.runId!==runtime.operation.runId||args.epoch!==runtime.operation.epoch)throw Error('Changed original install identity');
    runtime={...runtime,status:'cancelled',canPrepare:true,canRetry:true,canCancel:false,message:'本次准备已停止；已有工具环境保留。',operation:{...runtime.operation,status:'cancelled',phase:'cancelled'}};value=runtime;break;
   case 'models.snapshot':value=modelSnapshot();break;
   case 'settings.reviewModelTransport':value={required:false,consentTarget:''};break;
   case 'models.connections.add':{
    const row={id:'synthetic-service',revision:1,name:args.name,provider:args.provider,baseUrl:args.url,model:args.model,enabled:true,hasApiKey:true,credentialStatus:'configured',catalogEnabled:false,
     configuration:{sendScreenshots:args.screenshots,supportsImageInput:false,maxOutputTokens:args.maxOutputTokens}};
    connections=[row];persist();publish();value={status:'saved',savedConnection:row,snapshot:modelSnapshot()};break;
   }
   case 'models.catalog.snapshot':{
    const row=connections.find(item=>item.id===args.connectionId&&item.revision===args.expectedRevision);if(!row)throw Error('Changed service');
    value={format:'pocketworld.connection-catalog.v1',connection:row,catalogAvailable:true,catalogFetchedAtMillis:Date.now(),models:row.model?[{modelId:row.model,catalogFresh:true}]:[]};break;
   }
   case 'models.catalog.review':{
    const row=connections.find(item=>item.id===args.connectionId&&item.revision===args.expectedRevision);if(!row)throw Error('Changed service review');
    value={reviewHandle:'synthetic-catalog-review',selectionRevision:state.modelSelectionRevision,expiresInMs:120000,connection:row,model:{modelId:args.modelId},options:{maxOutputTokens:4096,imageTokenReserve:0},facts:{}};break;
   }
   case 'models.catalog.select':if(args.reviewHandle!=='synthetic-catalog-review')throw Error('Changed model review');selectModel(connections[0].model);value={status:'saved'};break;
   case 'models.api.open':value=apiProjection();break;
   case 'models.api.close':break;
   case 'models.api.review':value={...apiProjection(),reviewHandle:'synthetic-picker-review',model:apiModels().find(row=>row.modelId===args.modelId&&row.connectionId===args.connectionId)};break;
   case 'models.api.select':if(args.reviewHandle!=='synthetic-picker-review')throw Error('Changed picker review');selectModel(connections[0].model);value={status:'saved'};break;
   case 'models.api.favorite':value={status:'saved'};break;
   default:throw Error('Unimplemented synthetic command: '+action);
  }
  return value;
 };
 window.PocketNative={postMessage(raw){const request=JSON.parse(raw);requests.push(clone(request));const setupCommand=request.action.startsWith('setup.');
  if(setupCommand&&busy){errors.push('Concurrent setup command: '+request.action);setTimeout(()=>reply(request,null,{code:'not_accepted',message:'准备操作仍在进行'}),5);return;}
  if(setupCommand)busy=true;
  setTimeout(()=>{try{const value=execute(request);if(setupCommand)busy=false;reply(request,value);}catch(error){if(setupCommand)busy=false;errors.push(error.message);reply(request,null,{code:'not_accepted',message:error.message});}},12);
 }};
 window.setupFixture={requests,errors,snapshot,publish,get setup(){return clone(setup);},get runtime(){return clone(runtime);},failRuntime(){failRuntime();publish();}};
}

await fs.mkdir(output,{recursive:true});
const fixtureScript=`(${installSetupFixture})((function(){${empty};return emptySnapshot();})());`;
const server=http.createServer(async(req,res)=>{try{
 const url=new URL(req.url,'http://fixture');
 if(url.pathname==='/__setup-fixture.js'){res.writeHead(200,{'content-type':'text/javascript; charset=utf-8','cache-control':'no-store'});res.end(fixtureScript);return;}
 const file=path.resolve(bundle,'.'+(url.pathname==='/'?'/index.html':url.pathname));if(!file.startsWith(bundle+path.sep))throw Error('Invalid asset path');
 const type=({'.html':'text/html','.js':'text/javascript','.css':'text/css','.ttf':'font/ttf','.otf':'font/otf','.woff2':'font/woff2','.svg':'image/svg+xml','.png':'image/png','.json':'application/json'})[path.extname(file)]||'application/octet-stream';
 let content=await fs.readFile(file);if(path.extname(file)==='.html')content=content.toString().replace('<head>','<head><script src="/__setup-fixture.js"></script>');
 res.writeHead(200,{'content-type':type,'cache-control':'no-store'});res.end(content);
}catch{res.writeHead(404);res.end();}});
await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(port,'127.0.0.1',resolve);});
const origin='http://127.0.0.1:'+server.address().port;
if(process.argv.includes('--serve')){
 console.log(JSON.stringify({origin,scope:record.scope,bundle:record.bundle,bundleSha256:record.bundleSha256,scenarios:['/?scenario=fresh','/?scenario=failed&theme=dark','/?scenario=running','/?scenario=skipped']}));
 await new Promise(resolve=>{process.once('SIGINT',resolve);process.once('SIGTERM',resolve);});await new Promise(resolve=>server.close(resolve));
}else{
 let browser;
 try{
  browser=await chromium.launch({headless:true,...(process.env.UIUX_CHROMIUM?{executablePath:process.env.UIUX_CHROMIUM}:{})});
  for(const scene of [{width:390,height:844,dark:false},{width:320,height:720,dark:true}]){
   const name=`${scene.width}-${scene.dark?'dark':'light'}`;
   const context=await browser.newContext({viewport:{width:scene.width,height:scene.height},hasTouch:true,isMobile:true,locale:'zh-CN',reducedMotion:'reduce',serviceWorkers:'block'});
   await context.route('**/*',route=>new URL(route.request().url()).origin===origin?route.continue():route.abort());
   const page=await context.newPage();page.setDefaultTimeout(12000);page.on('pageerror',error=>record.errors.push(`${name}: ${error.message}`));
   const shot=async(label)=>{await page.evaluate(()=>document.fonts.ready);const file=`${name}-${label}.png`;await page.screenshot({path:path.join(output,file),animations:'disabled'});record.screenshots.push(file);assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,`${label} horizontal overflow`);};
   const count=action=>page.evaluate(action=>window.setupFixture.requests.filter(request=>request.action===action).length,action);
   const navigateSetup=async()=>{await page.getByRole('button',{name:'打开导航',exact:true}).click();await page.getByRole('link',{name:'准备中心',exact:true}).click();await page.locator('[data-setup-step="capabilities"]').waitFor();};
   try{
    await page.goto(origin+`/?theme=${scene.dark?'dark':'light'}`,{waitUntil:'networkidle'});await page.locator('[data-setup-step="welcome"]').waitFor();await shot('welcome');
    assert.equal(await count('setup.runtime.read'),0);assert.equal(await count('setup.model.test'),0);
    await page.getByRole('button',{name:'开始准备',exact:true}).click();await page.locator('[data-setup-step="model"]').waitFor();
    await page.getByRole('button',{name:'连接模型',exact:true}).click();await page.getByRole('heading',{name:'AI 接入',exact:true}).waitFor();await shot('ai-center');
    await page.getByRole('button',{name:'添加服务',exact:true}).first().click();await page.getByRole('button',{name:/自定义服务/}).click();
    await page.getByRole('textbox',{name:'服务名称',exact:true}).fill('我的测试服务');await page.getByRole('textbox',{name:'接口地址',exact:true}).fill('https://fixture.example.invalid/v1');
    await page.getByLabel('API 密钥',{exact:true}).fill('synthetic-renderer-key');await page.getByRole('button',{name:/高级设置/}).click();
    await page.getByRole('textbox',{name:'手动模型 ID（可选）',exact:true}).fill('fixture-text-model');await page.getByRole('button',{name:'保存服务',exact:true}).click();
    await page.locator('.pw-ai-model-row').filter({hasText:'fixture-text-model'}).click();await page.getByRole('dialog',{name:'选择默认模型',exact:true}).getByRole('button',{name:'设为默认模型',exact:true}).click();
    await page.locator('.pw-secondary-header').getByRole('button',{name:'返回',exact:true}).click();await page.locator('[data-ai-view="main"]').waitFor();await page.locator('.pw-secondary-header').getByRole('button',{name:'返回',exact:true}).click();
    await page.locator('[data-setup-step="model"]').waitFor();await page.getByText('已选择 · 尚未测试',{exact:true}).waitFor();assert.equal(await count('setup.model.test'),0);
    await page.getByRole('button',{name:'测试文本连接',exact:true}).click();await page.getByText('文本连接测试通过',{exact:true}).waitFor();await shot('model-return');
    await page.getByRole('button',{name:'继续',exact:true}).click();await page.locator('[data-setup-step="capabilities"]').waitFor();assert.equal(await page.getByRole('checkbox',{checked:true}).count(),0);
    await page.getByText('运行开发工具',{exact:true}).click();await page.getByRole('button',{name:'准备开发工具',exact:true}).waitFor();assert.equal(await count('setup.runtime.start'),0);assert.equal(await count('setup.permission'),0);await shot('capabilities');
    await page.getByRole('button',{name:'准备开发工具',exact:true}).click();await page.getByRole('region',{name:'确认工具准备',exact:true}).scrollIntoViewIfNeeded();await shot('install-review');
    await page.getByRole('button',{name:'暂不安装',exact:true}).click();assert.equal(await count('setup.runtime.start'),0);
    await page.getByRole('button',{name:'查看准备状态',exact:true}).click();await page.locator('[data-setup-step="finish"]').waitFor();await shot('review');
    await page.setViewportSize({width:scene.width,height:420});const footer=await page.getByRole('button',{name:'进入应用',exact:true}).boundingBox();assert.ok(footer&&footer.y>=0&&footer.y+footer.height<=420);await shot('short-viewport');await page.setViewportSize({width:scene.width,height:scene.height});
    await page.getByRole('button',{name:'进入应用',exact:true}).click();await page.getByRole('textbox',{name:'工作输入',exact:true}).waitFor();assert.equal(await page.evaluate(()=>window.setupFixture.setup.preferences.status),'completed');
    await navigateSetup();await page.getByRole('button',{name:'查看运行开发工具准备',exact:true}).click();await page.getByRole('button',{name:'准备开发工具',exact:true}).click();await page.getByRole('button',{name:'开始准备',exact:true}).click();
    await page.getByRole('progressbar').waitFor();assert.equal(await page.getByRole('progressbar').getAttribute('aria-valuenow'),null,'Unknown native phase cannot claim a percent');await shot('running');
    await page.evaluate(()=>window.setupFixture.failRuntime());await page.locator('.pw-setup-runtime').getByRole('button',{name:'刷新',exact:true}).click();await page.getByText('HTTP 503：工具下载服务暂时不可用',{exact:true}).waitFor();
    await page.getByRole('button',{name:/运行详情与日志/}).click();await page.getByText(/fixture.example.invalid returned 503/).waitFor();await shot('failed-details');
    await page.getByRole('button',{name:'重新准备',exact:true}).click();await page.getByRole('button',{name:'开始准备',exact:true}).click();await page.getByRole('button',{name:'停止准备',exact:true}).click();await page.getByText('已停止',{exact:true}).first().waitFor();
    const controls=await page.locator('.pw-setup-workspace button:visible').evaluateAll(nodes=>nodes.map(node=>node.getBoundingClientRect().height));assert.ok(controls.every(height=>height>=48),JSON.stringify(controls));
    assert.deepEqual(await page.evaluate(()=>window.setupFixture.errors),[]);record.checks.push({scene:name,status:'passed',journey:'fresh → existing AI center → model selected/tested → optional tools → completion → center reentry → explicit install/failure/retry/cancel'});
   }catch(error){record.checks.push({scene:name,status:'failed',error:String(error.stack||error)});await page.screenshot({path:path.join(output,`${name}-failure.png`),animations:'disabled'}).catch(()=>{});}
   finally{await context.close();}
  }
 }catch(error){record.errors.push(String(error.stack||error));}
 finally{if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));await fs.writeFile(path.join(output,'results.json'),JSON.stringify(record,null,2)+'\n');}
 console.log(JSON.stringify(record));if(record.errors.length||record.checks.some(check=>check.status!=='passed'))process.exitCode=1;
}
