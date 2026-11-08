/** Current production React UI with isolated native fixtures, never device proof. */
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {chromium} from '@playwright/test';

const root=path.resolve(import.meta.dirname,'..');
const argument=(name,fallback)=>{const at=process.argv.indexOf(name);return at<0?fallback:path.resolve(process.argv[at+1]);};
const bundle=argument('--bundle',path.resolve(root,'../app/src/main/assets/pocket-ui'));
const output=argument('--output',path.resolve(root,'../build-artifacts/mobile-uiux'));
await fs.mkdir(output,{recursive:true});
const source=await fs.readFile(path.join(root,'tests/private-butler-ui.mjs'),'utf8');
const fixture=source.match(/function fixture\(\)\s*\{[\s\S]*?\n\}\n/)?.[0];
const primary=await fs.readFile(path.join(root,'tests/screen-depth-primary-ui.mjs'),'utf8');
const sample=primary.match(/function sample\(\)\s*\{[\s\S]*?\n\}\n/)?.[0];
assert.ok(fixture&&sample,'Existing authoritative journey fixtures are available');
const index=await fs.readFile(path.join(bundle,'index.html'),'utf8');
const asset=index.match(/assets\/(index-[^" ]+\.js)/)?.[1];
assert.ok(asset,'A production bundle must be built before this check');
const record={scope:'Production Web bundle and synthetic native receipts. Android IME, system back, TalkBack and durable host execution are not exercised.',bundle:asset,bundleSha256:createHash('sha256').update(await fs.readFile(path.join(bundle,'assets',asset))).digest('hex'),checks:[],screenshots:[],errors:[]};
const server=http.createServer(async(req,res)=>{try{
 const pathname=new URL(req.url,'http://fixture').pathname,file=path.resolve(bundle,'.'+(pathname==='/'?'/index.html':pathname));
 if(!file.startsWith(bundle+path.sep))throw Error('Invalid asset path');
 const content=await fs.readFile(file);
 res.writeHead(200,{'content-type':({'.html':'text/html','.js':'text/javascript','.css':'text/css','.ttf':'font/ttf','.otf':'font/otf','.woff2':'font/woff2','.svg':'image/svg+xml','.json':'application/json'})[path.extname(file)]||'application/octet-stream'});res.end(content);
}catch{res.writeHead(404);res.end();}});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const origin='http://127.0.0.1:'+server.address().port;
const browser=await chromium.launch({headless:true,...(process.env.UIUX_CHROMIUM?{executablePath:process.env.UIUX_CHROMIUM}:{})});
try{
 for(const scenario of [{width:390,height:844,dark:false},{width:320,height:720,dark:false},{width:390,height:844,dark:true},{width:1280,height:900,dark:false}]){
  const {width,height,dark}=scenario,touch=width<1024,name=`${width}-${dark?'dark':'light'}`;
  const context=await browser.newContext({viewport:{width,height},hasTouch:touch,isMobile:touch,locale:'zh-CN',timezoneId:'Asia/Shanghai',reducedMotion:'reduce',serviceWorkers:'block'});
  await context.route('**/*',route=>new URL(route.request().url()).origin===origin?route.continue():route.abort());
  await context.addInitScript({content:`(${fixture})();(${sample})();window.butlerFixture.snapshot.state.appearance.dark=${dark};window.butlerFixture.snapshot.state.appearance.mode='${dark?'dark':'light'}';`});
  const page=await context.newPage();page.setDefaultTimeout(10000);page.on('pageerror',error=>record.errors.push(`${name}: ${error.message}`));
  const navigate=async(label)=>{if(touch)await page.getByRole('button',{name:'打开导航',exact:true}).click();await page.getByRole('link',{name:label,exact:true}).click();};
  const screenshot=async(label)=>{await page.mouse.move(0,0);await page.evaluate(async()=>{await document.fonts.ready;await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));});const file=`${name}-${label}.png`;await page.screenshot({path:path.join(output,file),animations:'disabled'});record.screenshots.push(file);assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,`${name}/${label} must not scroll horizontally`);};
  try{
   await page.goto(origin,{waitUntil:'networkidle'});await page.waitForFunction(()=>document.documentElement.dataset.pocketUi==='ready');
   const input=page.getByRole('textbox',{name:'工作输入',exact:true});await input.waitFor();
   await screenshot('conversation');
   assert.equal(await page.locator('.pw-work-row').count(),2);
   const draft='补充第一行';await input.fill(draft);
   if(touch){await input.press('End');await input.press('Enter');await input.pressSequentially('第二行');assert.equal(await input.inputValue(),draft+'\n第二行');assert.equal(await page.evaluate(()=>window.butlerFixture.requests.filter(x=>x.action==='composer.send').length),0,'Touch Enter must not send a draft');}
   await input.dispatchEvent('keydown',{key:'Enter',code:'Enter',isComposing:true});
   assert.equal(await page.evaluate(()=>window.butlerFixture.requests.filter(x=>x.action==='composer.send').length),0,'IME composition must not send');
   const retained=await input.inputValue();
   if(touch){await page.getByRole('button',{name:'打开导航',exact:true}).click();await screenshot('navigation');await page.getByRole('link',{name:'任务',exact:true}).click();}else await navigate('任务');
   const filters=page.getByRole('group',{name:'任务筛选',exact:true});await filters.waitFor();
   assert.equal(await filters.getByRole('button').count(),4);
   assert.equal(await filters.getByRole('button').evaluateAll(rows=>rows.every(row=>row.getBoundingClientRect().height>=48)),true,'Visible task filters need complete touch targets');
   await screenshot('tasks');
   if(width===390&&!dark){await navigate('设置');await page.locator('[data-secondary-page="settings"]').waitFor();assert.equal(await page.locator('[data-settings-group]').count(),6);await screenshot('settings');await navigate('任务');}
   await filters.getByRole('button',{name:'已结束',exact:true}).click();assert.equal(await page.locator('.pw-task-row').count(),1);
   await page.locator('[data-task-id="completed-work"]').click();await page.getByRole('button',{name:'返回原工作',exact:true}).click();
   assert.equal(await filters.getByRole('button',{name:'已结束',exact:true}).getAttribute('aria-pressed'),'true','Return keeps collection filter');
   await filters.getByRole('button',{name:'全部',exact:true}).click();
   await page.getByRole('button',{name:'搜索与筛选任务',exact:true}).click();
   await page.getByRole('searchbox',{name:'搜索已加载任务',exact:true}).fill('账单');assert.equal(await page.locator('.pw-task-row').count(),1);
   await page.getByRole('button',{name:'搜索与筛选任务',exact:true}).click();
   await page.getByRole('button',{name:'清除任务筛选',exact:true}).click();assert.equal(await page.locator('.pw-task-row').count(),3);
   await navigate('项目');await page.getByRole('searchbox',{name:'搜索当前项目文件',exact:true}).waitFor();await screenshot('project-files');
   await navigate('对话');assert.equal(await input.inputValue(),retained,'Navigation keeps the original conversation draft');
   if(touch){await page.setViewportSize({width,height:420});await input.focus();assert.equal(await input.inputValue(),retained);assert.equal(await input.evaluate(node=>document.activeElement===node),true);const send=page.getByRole('button',{name:'发送',exact:true});const box=await send.boundingBox();assert.ok(box&&box.y>=0&&box.y+box.height<=420,'Send remains reachable in a short viewport');await screenshot('short-viewport');await page.setViewportSize({width,height});}
   const before=await page.evaluate(()=>window.butlerFixture.requests.filter(x=>x.action==='composer.send').length);
   await page.getByRole('button',{name:'发送',exact:true}).click();await page.waitForFunction(count=>window.butlerFixture.requests.filter(x=>x.action==='composer.send').length===count+1,before);
   const sent=await page.evaluate(()=>window.butlerFixture.requests.filter(x=>x.action==='composer.send').at(-1).args);
   assert.deepEqual(Object.keys(sent).sort(),['epoch','key','text']);assert.equal(sent.text,retained);
   record.checks.push({scenario:name,status:'passed',touchEnter:touch?'newline':'desktop unchanged',draftAndFilterReturn:true,originalSendIdentity:true,horizontalOverflow:false});
  }catch(error){record.checks.push({scenario:name,status:'failed',error:String(error.stack||error)});await page.screenshot({path:path.join(output,`${name}-failure.png`),animations:'disabled'}).catch(()=>{});}
  finally{await context.close();}
 }
 const fresh=await browser.newContext({viewport:{width:390,height:844},hasTouch:true,isMobile:true,locale:'zh-CN',reducedMotion:'reduce',serviceWorkers:'block'});
 await fresh.route('**/*',route=>new URL(route.request().url()).origin===origin?route.continue():route.abort());
 await fresh.addInitScript({content:`(${fixture})();Object.assign(window.butlerFixture.snapshot.state,{messages:[],tasks:[],ownerWork:[],projects:[],files:[],modelName:'',appearance:{mode:'light',dark:false}});window.butlerFixture.snapshot.composer.text='';`});
 const firstPage=await fresh.newPage();firstPage.on('pageerror',error=>record.errors.push(`first-use: ${error.message}`));
 try{
  await firstPage.goto(origin,{waitUntil:'networkidle'});await firstPage.waitForFunction(()=>document.documentElement.dataset.pocketUi==='ready');
  await firstPage.getByRole('button',{name:'连接模型',exact:true}).waitFor();
  assert.equal(await firstPage.getByRole('textbox',{name:'工作输入',exact:true}).inputValue(),'','First use must not fabricate a draft');
  assert.equal(await firstPage.locator('.pw-work-row').count(),0,'First use must not fabricate running tasks');
  await firstPage.evaluate(()=>document.fonts.ready);await firstPage.screenshot({path:path.join(output,'390-first-use.png'),animations:'disabled'});record.screenshots.push('390-first-use.png');
  await firstPage.getByRole('button',{name:'连接模型',exact:true}).click();await firstPage.locator('[data-secondary-page="models"]').waitFor();
  record.checks.push({scenario:'first-use',status:'passed',emptyDraft:true,emptyWork:true,modelConnectionEntry:true});
 }catch(error){record.checks.push({scenario:'first-use',status:'failed',error:String(error.stack||error)});}
 finally{await fresh.close();}
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));await fs.writeFile(path.join(output,'results.json'),JSON.stringify(record,null,2)+'\n');}
console.log(JSON.stringify(record));if(record.errors.length||record.checks.some(check=>check.status==='failed'))process.exitCode=1;
