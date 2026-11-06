/** Production App screenshots with synthetic native state; not Android/device proof. */
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import {chromium} from '@playwright/test';
const arg=(key,fallback)=>{const at=process.argv.indexOf(key);return at<0?fallback:path.resolve(process.argv[at+1])};
const root=path.resolve(import.meta.dirname,'..'),bundle=arg('--bundle',path.resolve(root,'../app/src/main/assets/pocket-ui')),out=arg('--output',path.resolve(root,'../build-artifacts/screen-depth-primary'));
await fs.mkdir(out,{recursive:true});
const previous=await fs.readFile(path.join(root,'tests/private-butler-ui.mjs'),'utf8');
const fixture=previous.match(/function fixture\(\)\s*\{[\s\S]*?\n\}\n/)[0].replace('const commands = [',"const commands = ['projects.create','projects.select',");
function sample(){
 const f=window.butlerFixture,s=f.snapshot;
 s.state.appearance={mode:'light',dark:false,typography:{chinese:'notosanssc',latin:'inter',code:'jetbrainsmono'}};
 s.state.messages=[{id:'request-a',role:'user',content:'帮我核对本周账单和周末出行安排。有需要确认的地方，先问我。'},
  {id:'answer-a',role:'assistant',taskId:'foreground',content:'已分别开始核对账单和出行安排。\n\n账单会先整理差异；出行会比较时间与路线。涉及付款或预订时，我会把依据和选项交给你确认。'}];
 s.state.conversations=[{id:s.state.conversationId,title:'账单与出行安排'}];
 s.state.projects=[{id:'weekend-notes',name:'周末出行资料'},{id:'meeting-notes',name:'会议记录'}];s.state.projectId='weekend-notes';
 s.state.files=[{name:'行程安排.md',path:'notes/行程安排.md',size:1320},{name:'交通比较.md',path:'notes/交通比较.md',size:980},{name:'README.md',path:'README.md',size:420}];
 s.state.tasks[0].summary='正在比较出发时间与换乘路线，整理需要你确认的选项。';s.state.tasks[1].summary='正在核对账单中的支出项目，保留原始记录。';
 s.state.tasks.push({id:'completed-work',goal:'整理会议记录与待办',summary:'会议记录已整理，三项待办保留在结果中。',status:'completed',updatedAt:3,raw:{id:'completed-work',goal:'整理会议记录与待办',kind:'assistant',actorId:'owner',spaceId:'private',conversationId:s.state.conversationId,executionRole:'owner_work',runId:'completed-run',coordinatorEpoch:1,controls:{canStop:false,canInput:false,canContinue:false}}});
 const post=window.PocketNative.postMessage;window.PocketNative.postMessage=raw=>{const request=JSON.parse(raw);post(raw);if(request.action==='app.ready'||request.action==='app.refresh')queueMicrotask(()=>f.publish());};
}
const nativeSource=`(${fixture})();(${sample.toString()})();`;
const server=http.createServer(async(req,res)=>{try{const pathname=new URL(req.url,'http://fixture').pathname,target=path.resolve(bundle,'.'+(pathname==='/'?'/index.html':pathname));if(!target.startsWith(bundle+path.sep))throw Error();const data=await fs.readFile(target);res.writeHead(200,{'content-type':({'.html':'text/html','.css':'text/css','.js':'text/javascript','.ttf':'font/ttf','.otf':'font/otf','.woff2':'font/woff2','.json':'application/json'})[path.extname(target)]||'application/octet-stream'});res.end(data)}catch{res.writeHead(404);res.end()}});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const origin='http://127.0.0.1:'+server.address().port;
const browser=await chromium.launch({headless:true,...(process.env.UIUX_CHROMIUM?{executablePath:process.env.UIUX_CHROMIUM}:{})});
const record={scope:'Actual production React App with synthetic native fixture records. Not Android screenshots or device execution.',checks:[],screenshots:[],errors:[]};
try{for(const width of [360,1280]){
 const context=await browser.newContext({viewport:{width,height:900},locale:'zh-CN',reducedMotion:'reduce'});await context.addInitScript({content:nativeSource});
 await context.route('**/*',r=>new URL(r.request().url()).origin===origin?r.continue():r.abort());
 const page=await context.newPage();page.setDefaultTimeout(10000);page.on('pageerror',e=>record.errors.push(e.message));
 async function shot(name){await page.mouse.move(0,0);await page.evaluate(async()=>{await document.fonts.ready;await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)))});await page.screenshot({path:path.join(out,`${name}-${width}.png`),fullPage:true,animations:'disabled'});record.screenshots.push(`${name}-${width}.png`);assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);}
 async function navigate(name){if(width<1024)await page.getByRole('button',{name:'打开导航',exact:true}).click();await page.getByRole('link',{name,exact:true}).click();}
 try{await page.goto(origin,{waitUntil:'networkidle'});await page.waitForFunction(()=>document.documentElement.dataset.pocketUi==='ready');await page.getByText('已分别开始核对账单和出行安排。',{exact:false}).waitFor();await shot('chat');if(process.argv.includes('--assert-depth'))assert.equal((await page.locator('.pw-conversation-work').textContent()).includes('执行中 · 正在执行'),false);
  await navigate('项目');await page.getByRole('region',{name:'当前项目工作区',exact:true}).waitFor();await page.getByRole('searchbox',{name:'搜索当前项目文件',exact:true}).waitFor();await page.getByText('交通比较.md',{exact:true}).waitFor();await shot('project-files');await page.getByRole('button',{name:'切换项目',exact:true}).click();await page.getByRole('dialog',{name:'切换项目',exact:true}).waitFor();await page.getByRole('button',{name:'会议记录',exact:true}).waitFor();await shot('project-picker');await page.getByRole('button',{name:'新项目',exact:true}).click();await page.getByRole('dialog',{name:'新项目',exact:true}).waitFor();await page.getByRole('textbox',{name:'项目名称',exact:true}).waitFor();await shot('project-create');await page.getByRole('button',{name:'关闭创建',exact:true}).click();
  await navigate('任务');await page.getByRole('region',{name:'正在进行',exact:true}).waitFor();await shot('tasks');if(process.argv.includes('--assert-depth'))assert.equal(await page.locator('.pw-task-row').evaluateAll(rows=>rows.every(row=>{const a=row.querySelector(':scope > span > svg')?.getBoundingClientRect(),r=row.getBoundingClientRect();return a&&r.right-a.right>=0&&r.right-a.right<=24})),true);
  await page.locator('[data-task-id="work-a"]').first().click();await page.locator('.pw-task-drawer').waitFor();await shot('task-overview');if(process.argv.includes('--assert-depth'))assert.equal(await page.evaluate(()=>!!(document.querySelector('.pw-task-identity').compareDocumentPosition(document.querySelector('.pw-owner-work-actions'))&Node.DOCUMENT_POSITION_FOLLOWING)),true);
  await page.getByRole('button',{name:'过程与范围',exact:true}).click();await page.getByRole('button',{name:'查看执行轨迹',exact:true}).click();await shot('task-process');
  assert.equal(await page.evaluate(()=>window.butlerFixture.requests.filter(x=>x.action==='composer.send').length),0);record.checks.push({width,passed:true});
 }catch(e){record.checks.push({width,passed:false,error:String(e.stack||e)});await page.screenshot({path:path.join(out,`failure-${width}.png`)});}finally{await context.close();}
 }}finally{await browser.close();await new Promise(resolve=>server.close(resolve));await fs.writeFile(path.join(out,'result.json'),JSON.stringify(record,null,2));}
console.log(JSON.stringify(record));if(record.errors.length||record.checks.some(x=>!x.passed))process.exitCode=1;
