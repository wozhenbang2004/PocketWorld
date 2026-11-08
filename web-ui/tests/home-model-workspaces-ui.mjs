/** The production app, isolated native fixtures, and real browser interactions. */
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {chromium} from '@playwright/test';

const root=path.resolve(import.meta.dirname,'..');
const arg=(name,fallback)=>{const i=process.argv.indexOf(name);return i<0?fallback:path.resolve(process.argv[i+1]);};
const bundle=arg('--bundle',path.resolve(root,'../app/src/main/assets/pocket-ui'));
const output=arg('--output',path.resolve(root,'../build-artifacts/home-model-workspaces'));
await fs.mkdir(output,{recursive:true});
const original=await fs.readFile(path.join(root,'tests/private-butler-ui.mjs'),'utf8');
const fixture=original.match(/function fixture\(\)\s*\{[\s\S]*?\n\}\n/)?.[0].replace('const commands = [',"const commands = ['models.snapshot','models.review','models.select','models.api.open','models.api.close','models.api.review','models.api.select','models.api.favorite','conversation.new','voice.start','attachments.openPicker','phone.prepare',");
const primary=await fs.readFile(path.join(root,'tests/screen-depth-primary-ui.mjs'),'utf8');
const sample=primary.match(/function sample\(\)\s*\{[\s\S]*?\n\}\n/)?.[0];
assert.ok(fixture&&sample);

function modelFixture(){
 const f=window.butlerFixture,state=f.snapshot.state;
 Object.assign(state,{modelName:'GPT-5.1',modelRoute:'api_key',modelProvider:'openai_responses',modelSelectionRevision:8});
 const binding={connectionId:'personal-api',connectionRevision:3,catalogRevision:2};
 const facts={REASONING:{known:true,value:true},REASONING_LEVELS:{known:true,value:['low','medium','high']}};
 const snapshot={activeRoute:'api_key',catalogEnabled:true,binding,selected:{modelId:'GPT-5.1',binding,facts},
  api:{provider:'openai_responses',model:'GPT-5.1',capabilityBinding:binding,reasoning:{mode:'OPENAI_EFFORT',level:'medium',budgetTokens:0},maxOutputTokens:4096,imageTokenReserve:0},
  nativeSelection:{provider:'openai_responses',model:'GPT-5.1',modelRoute:'api_key',catalogEnforced:true,capabilityBinding:binding,reasoning:{mode:'OPENAI_EFFORT',level:'medium',budgetTokens:0},maxOutputTokens:4096,imageTokenReserve:0}};
 const rows=['GPT-5.1','GPT-5.1 mini','o4-mini'].map((modelId,index)=>({connectionId:'personal-api',connectionRevision:3,connectionName:'我的 API',modelId,available:true,favorite:index===0,selected:index===0,lastUsedAt:index===0?1794133200000:0,catalogFresh:true,imageInput:{known:true,value:true},toolCalls:{known:true,value:true}}));
 const projection=()=>({windowId:'home-model-window',viewGeneration:1,format:'pocketworld.model-picker.v1',selectionRevision:state.modelSelectionRevision,preferencesAvailable:true,models:rows});
 let reviewed;
 const post=window.PocketNative.postMessage;
 window.PocketNative.postMessage=raw=>{
  const request=JSON.parse(raw),{action,args}=request;
  if(!action.startsWith('models.'))return post(raw);
  f.requests.push(request);let value={};
  if(action==='models.snapshot')value=structuredClone(snapshot);
  if(action==='models.review')value={reviewHandle:'home-reasoning-original',modelId:args.modelId,reasoning:structuredClone(snapshot.nativeSelection.reasoning),outputTokens:4096,imageTokenReserve:0,expiresInMs:120000};
  if(action==='models.select'){
   if(args.reviewHandle!=='home-reasoning-original'||Object.keys(args).sort().join(',')!=='reasoning,reviewHandle')throw Error('Changed original reasoning review');
   snapshot.nativeSelection.reasoning=structuredClone(args.reasoning);snapshot.api.reasoning=structuredClone(args.reasoning);state.modelSelectionRevision++;
   value={accepted:true,modelId:snapshot.nativeSelection.model,snapshot:structuredClone(snapshot)};
  }
  if(action==='models.api.open')value=projection();
  if(action==='models.api.review'){
   reviewed=rows.find(row=>row.modelId===args.modelId&&row.connectionId===args.connectionId&&row.connectionRevision===args.expectedRevision);
   if(!reviewed||args.windowId!=='home-model-window')throw Error('Changed original model row');
   value={...projection(),reviewHandle:'home-model-original',model:reviewed};
  }
  if(action==='models.api.select'){
   if(args.windowId!=='home-model-window'||args.reviewHandle!=='home-model-original'||!reviewed)throw Error('Changed original model review');
   rows.forEach(row=>{row.selected=row===reviewed;});state.modelName=reviewed.modelId;state.modelSelectionRevision++;
   snapshot.nativeSelection.model=reviewed.modelId;snapshot.api.model=reviewed.modelId;snapshot.selected.modelId=reviewed.modelId;
   value={status:'saved'};
  }
  f.publish();setTimeout(()=>window.PocketWeb.receive({v:1,kind:'result',id:request.id,ok:true,value}),10);
 };
 f.modelSnapshot=snapshot;
}

const index=await fs.readFile(path.join(bundle,'index.html'),'utf8');
const asset=index.match(/assets\/(index-[^" ]+\.js)/)?.[1];assert.ok(asset);
const record={scope:'Production React app and synthetic native receipts; not Android device or real model-provider validation.',bundle:asset,bundleSha256:createHash('sha256').update(await fs.readFile(path.join(bundle,'assets',asset))).digest('hex'),checks:[],screenshots:[],errors:[]};
const server=http.createServer(async(req,res)=>{try{
 const url=new URL(req.url,'http://fixture'),file=path.resolve(bundle,'.'+(url.pathname==='/'?'/index.html':url.pathname));
 if(!file.startsWith(bundle+path.sep))throw Error('Invalid path');
 res.writeHead(200,{'content-type':({'.html':'text/html','.js':'text/javascript','.css':'text/css','.ttf':'font/ttf','.otf':'font/otf','.woff2':'font/woff2','.svg':'image/svg+xml'})[path.extname(file)]||'application/octet-stream'});res.end(await fs.readFile(file));
}catch{res.writeHead(404);res.end();}});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const origin='http://127.0.0.1:'+server.address().port;
const browser=await chromium.launch({headless:true});
try{
 for(const scene of [{width:390,dark:false,empty:true},{width:390,dark:false,empty:false},{width:320,dark:false,empty:true},{width:390,dark:true,empty:true},{width:1280,dark:false,empty:true}]){
  const name=`${scene.width}-${scene.dark?'dark':'light'}-${scene.empty?'home':'conversation'}`;
  const context=await browser.newContext({viewport:{width:scene.width,height:844},hasTouch:scene.width<1024,isMobile:scene.width<1024,locale:'zh-CN',reducedMotion:'reduce',serviceWorkers:'block'});
  await context.route('**/*',route=>new URL(route.request().url()).origin===origin?route.continue():route.abort());
  await context.addInitScript({content:`(${fixture})();(${sample})();(${modelFixture})();Object.assign(window.butlerFixture.snapshot.state.appearance,{dark:${scene.dark},mode:'${scene.dark?'dark':'light'}'});${scene.empty?"Object.assign(window.butlerFixture.snapshot.state,{messages:[],tasks:[],ownerWork:[]});window.butlerFixture.snapshot.composer.text='';":''}`});
  const page=await context.newPage();page.setDefaultTimeout(12000);page.on('pageerror',error=>record.errors.push(`${name}: ${error.message}`));
  const shot=async(label)=>{await page.mouse.move(0,0);await page.evaluate(()=>document.fonts.ready);const file=`${name}-${label}.png`;await page.screenshot({path:path.join(output,file),animations:'disabled'});record.screenshots.push(file);assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,`${file} overflow`);};
  const navigate=async(label)=>{if(scene.width<1024)await page.getByRole('button',{name:'打开导航',exact:true}).click();await page.getByRole('link',{name:label,exact:true}).click();};
  try{
   await page.goto(origin,{waitUntil:'networkidle'});await page.getByRole('button',{name:'推理强度：中',exact:true}).waitFor();
   const input=page.getByRole('textbox',{name:'工作输入',exact:true});const draft=await input.inputValue();
   await shot('screen');
   assert.ok(await page.locator('.pw-conversation-model-chip').isVisible());assert.ok(await page.locator('.pw-conversation-reasoning-chip').isVisible());
   if(scene.width===390&&!scene.dark&&scene.empty){
    await input.fill('保留这段文字');
    await page.getByRole('button',{name:'输入方式与选项',exact:true}).click();
    await page.getByRole('button',{name:'语音输入',exact:true}).click();
    assert.deepEqual(await page.evaluate(()=>window.butlerFixture.requests.filter(request=>request.action==='voice.start').at(-1)?.args),{phone:false});
    assert.equal(await input.inputValue(),'保留这段文字','Voice entry preserves a non-empty draft');
    await input.fill(draft);
    await page.getByRole('button',{name:'推理强度：中',exact:true}).click();await page.getByRole('radiogroup',{name:'可用推理强度',exact:true}).waitFor();await shot('reasoning');
    await page.locator('.pw-conversation-reasoning-option').filter({has:page.getByRole('radio',{name:'高 high',exact:true})}).click();await page.getByRole('button',{name:'应用',exact:true}).click();await page.getByRole('button',{name:'推理强度：高',exact:true}).waitFor();
    const sent=await page.evaluate(()=>window.butlerFixture.requests.filter(request=>request.action==='models.select'));
    assert.equal(sent.length,1);assert.deepEqual(sent[0].args,{reviewHandle:'home-reasoning-original',reasoning:{mode:'OPENAI_EFFORT',level:'high',budgetTokens:0}});assert.equal(await input.inputValue(),draft);
    await page.locator('.pw-conversation-model-chip').click();await page.getByRole('listbox',{name:'API 连接与模型',exact:true}).waitFor();await shot('model-picker');
    await page.getByRole('option',{name:/GPT-5.1 mini/}).click();await page.getByRole('button',{name:'确认切换主模型',exact:true}).waitFor();await shot('model-review');
    await page.getByRole('button',{name:'确认切换主模型',exact:true}).click();await page.getByRole('button',{name:'切换模型：GPT-5.1 mini',exact:true}).waitFor();assert.equal(await input.inputValue(),draft);
    await page.getByRole('button',{name:'打开导航',exact:true}).click();await shot('navigation');
    for(const label of ['记忆','自动化','团队','技能与工具','设备','媒体'])assert.equal(await page.getByRole('link',{name:label,exact:true}).count(),1);
    await page.getByRole('link',{name:'设置',exact:true}).click();await page.locator('[data-secondary-page="settings"]').waitFor();await shot('settings');
    assert.equal(await page.locator('[data-secondary-page="settings"]').getByText('技能与工具',{exact:true}).count(),0);
    for(const [label,key] of [['记忆','memory'],['自动化','automation'],['团队','teams'],['技能与工具','tools'],['设备','devices'],['媒体','media']]){
     await navigate(label);await page.locator(`[data-workspace="${key}"]`).waitFor();
     await page.getByRole('button',{name:'打开导航',exact:true}).click();
     assert.equal(await page.getByRole('link',{name:label,exact:true}).getAttribute('aria-current'),'page');
     assert.equal(await page.getByRole('link',{name:'设置',exact:true}).getAttribute('aria-current'),null);
     await page.getByRole('button',{name:'关闭导航',exact:true}).click();
    }
    await navigate('对话');assert.equal(await input.inputValue(),draft);
   }
   const controls=await page.locator('.pw-conversation-model-controls button').evaluateAll(nodes=>nodes.map(node=>({width:node.getBoundingClientRect().width,height:node.getBoundingClientRect().height})));
   assert.equal(controls.every(box=>box.width>=48&&box.height>=48),true,'Controls retain touch targets: '+JSON.stringify(controls));
   record.checks.push({scene:name,passed:true});
  }catch(error){record.checks.push({scene:name,passed:false,error:String(error.stack||error)});await page.screenshot({path:path.join(output,`${name}-failure.png`),animations:'disabled'}).catch(()=>{});}
  finally{await context.close();}
 }
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));await fs.writeFile(path.join(output,'results.json'),JSON.stringify(record,null,2)+'\n');}
console.log(JSON.stringify(record));if(record.errors.length||record.checks.some(check=>!check.passed))process.exitCode=1;
