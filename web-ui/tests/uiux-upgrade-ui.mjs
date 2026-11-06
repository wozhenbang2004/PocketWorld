/** Production React bundle + synthetic native receipts. Native preference contract tests use an in-memory SharedPreferences fake; device disk/restart not verified. */
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import http from 'node:http';
import {chromium} from '@playwright/test';
import {installProjectPreviewHost} from './project-preview-host-fixture.mjs';
const arg=key=>path.resolve(process.argv[process.argv.indexOf(key)+1]);
const bundle=arg('--bundle'),out=arg('--output');
await fs.mkdir(out,{recursive:true});
const server=http.createServer(async(req,res)=>{try{const pathname=new URL(req.url,'http://fixture').pathname;const target=path.resolve(bundle,'.'+(pathname==='/'?'/index.html':pathname));if(!target.startsWith(bundle+path.sep))throw Error();const content=await fs.readFile(target);res.writeHead(200,{'content-type':({'.html':'text/html','.css':'text/css','.js':'text/javascript','.ttf':'font/ttf','.otf':'font/otf','.woff2':'font/woff2','.json':'application/json'})[path.extname(target)]||'application/octet-stream'});res.end(content)}catch{res.writeHead(404);res.end()}});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const origin='http://127.0.0.1:'+server.address().port;
const browser=await chromium.launch({headless:true,...(process.env.UIUX_CHROMIUM?{executablePath:process.env.UIUX_CHROMIUM}:{})});
const result={scope:'Production Web UI with synthetic native state; Native preference contract tested with an in-memory SharedPreferences fake; Android disk, process restart, touch and renderer not verified.',checks:[],errors:[],screenshots:[]};
async function settings(page){
 if(await page.getByRole('button',{name:'打开导航',exact:true}).count())await page.getByRole('button',{name:'打开导航',exact:true}).click();
 await page.getByRole('link',{name:'设置',exact:true}).click();
 await page.getByRole('button',{name:/手机能力/}).first().click();
 await page.getByRole('button',{name:/外观与字体/}).click();
 await page.getByRole('region',{name:'字体设置',exact:true}).waitFor();
 await page.locator('.pw-typography-specimens:not([hidden])').waitFor();
}
try{
 for(const width of [360,1440]){
  const context=await browser.newContext({viewport:{width,height:960},locale:'zh-CN',reducedMotion:'reduce'});
  await context.addInitScript(installProjectPreviewHost,{typography:true});
  await context.route('**/*',route=>new URL(route.request().url()).origin===origin?route.continue():route.abort());
  const page=await context.newPage();page.setDefaultTimeout(10000);page.on('pageerror',e=>result.errors.push(e.message));
  try{
   await page.goto(origin,{waitUntil:'networkidle'});await settings(page);
   const select=page.locator('.pw-typography-fields .pw-selection-field').nth(0).getByRole('button');await select.click();
   assert.equal(await page.getByRole('option').count(),6);
   await page.getByRole('option',{name:/霞鹜文楷/}).click();
   await page.locator('.pw-typography-specimens:not([hidden])').waitFor();
   assert.equal(await page.evaluate(()=>document.documentElement.dataset.fontChinese),'notosanssc','preview must not change saved UI');
   await page.locator('.pw-typography-fields .pw-selection-field').nth(1).getByRole('button').click();assert.equal(await page.getByRole('option').count(),12);await page.getByRole('option',{name:/Geist/}).click();
   await page.locator('.pw-typography-fields .pw-selection-field').nth(2).getByRole('button').click();assert.equal(await page.getByRole('option').count(),2);await page.getByRole('option',{name:/IBM Plex Mono/}).click();
   await page.locator('.pw-typography-specimens:not([hidden])').waitFor();
   await page.getByRole('button',{name:'保存字体',exact:true}).click();
   await page.waitForFunction(()=>document.documentElement.dataset.fontChinese==='lxgwwenkai'&&document.documentElement.dataset.fontLatin==='geist'&&document.documentElement.dataset.fontCode==='ibmplexmono');
   assert.equal(await page.evaluate(()=>window.previewFixture.calls.filter(x=>x.action==='settings.typography').length),1,'exactly one explicit font save');
   assert.equal(await page.evaluate(()=>window.previewFixture.calls.filter(x=>x.action==='composer.send').length),0);
   await page.getByText('字体已保存并应用。',{exact:true}).waitFor();
   assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
   await page.screenshot({path:path.join(out,`fonts-${width}.png`)});result.screenshots.push(`fonts-${width}.png`);
   await page.reload({waitUntil:'networkidle'});await page.waitForFunction(()=>document.documentElement.dataset.fontChinese==='lxgwwenkai');await settings(page);
   await page.getByRole('button',{name:'恢复默认组合',exact:true}).click();await page.locator('.pw-typography-specimens:not([hidden])').waitFor();await page.getByRole('button',{name:'保存字体',exact:true}).click();await page.waitForFunction(()=>document.documentElement.dataset.fontChinese==='notosanssc');
   result.checks.push({width,name:'roles-preview-save-reload-reset-no-overflow',passed:true});
   if(width<1024)await page.getByRole('button',{name:'打开导航',exact:true}).click();
   await page.getByRole('link',{name:'项目',exact:true}).click();
   if(width<1024)await page.getByRole('tab',{name:'预览',exact:true}).click();
   else await page.getByRole('button',{name:'打开预览',exact:true}).click();
   await page.locator('.pw-preview-pane').waitFor();
   await page.waitForFunction(()=>!!window.previewFixture.snapshot().state.projectPreview);
   await page.evaluate(()=>window.previewFixture.ready());
   await page.waitForFunction(()=>window.previewFixture.calls.some(x=>x.action==='projects.preview.viewport'&&x.args.visible));
   assert.equal(await page.locator('.pw-preview-pane').count(),1);
   assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
   if(width>=1024){
    const geometry=await page.evaluate(()=>{const f=document.querySelector('.pw-project-files-pane').getBoundingClientRect(),p=document.querySelector('.pw-project-preview-panel').getBoundingClientRect();return{fileWidth:f.width,previewWidth:p.width,separate:p.left>=f.right}});
    assert.ok(geometry.fileWidth>=230&&geometry.previewWidth>=410&&geometry.separate);
   }
   await page.screenshot({path:path.join(out,`project-${width}.png`)});result.screenshots.push(`project-${width}.png`);
   assert.equal(await page.evaluate(()=>window.previewFixture.calls.filter(x=>x.action==='composer.send').length),0);
   result.checks.push({width,name:'project-one-preview-original-draft-and-bounded-layout',passed:true});
  }catch(e){result.checks.push({width,passed:false,error:String(e.stack||e)});await page.screenshot({path:path.join(out,`failure-${width}.png`)});}finally{await context.close()}
 }
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));await fs.writeFile(path.join(out,'result.json'),JSON.stringify(result,null,2));}
console.log(JSON.stringify(result));if(result.errors.length||result.checks.some(x=>!x.passed))process.exitCode=1;
