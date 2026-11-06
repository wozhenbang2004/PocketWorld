/** Before/after of mounted production components, with explicitly synthetic
 * native receipts. No Android, pairing, paid execution or delivery assertion. */
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';import fs from 'node:fs/promises';import path from 'node:path';
import {createServer} from 'vite';import {chromium} from '@playwright/test';
const phase=process.env.COLLABORATION_PHASE||'after';
const output=process.env.COLLABORATION_EVIDENCE||path.resolve(import.meta.dirname,'../../build-artifacts/collaboration-depth',phase);
await fs.mkdir(output,{recursive:true});const temporary=await fs.mkdtemp('/dev/shm/pw-collaboration-');process.env.TMPDIR=temporary;const server=await createServer({plugins:phase==='before'?[{name:'exact-before-collaboration',enforce:'pre',load(id){const match=id.match(/\/src\/(TeamsWorkspace|GroupsWorkspace|ChannelsWorkspace|DevicesWorkspace|TeamEvidenceWorkspace|RemoteTaskWorkspace|RemoteTaskArtifacts|RemoteTaskInputs|RemoteTaskPreview|RemoteProjectBaseline|TeamProductionFields|RemoteTaskHarness)\.tsx$/);return match?execFileSync('git',['show',`379911a:web-ui/src/${match[1]}.tsx`],{cwd:path.resolve(import.meta.dirname,'..'),encoding:'utf8'}):null}}]:[],root:path.resolve(import.meta.dirname,'..'),cacheDir:path.join(temporary,'vite'),optimizeDeps:{entries:['tests/collaboration-depth-fixture.html']},server:{host:'127.0.0.1',port:0},logLevel:'error'});await server.listen();
const browser=await chromium.launch({headless:true,executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH||'/workspace/scratch/af478521f69c/pocketworld-browser-cache/chromium_headless_shell-1194/chrome-linux/headless_shell'});
const evidence=[],errors=[],allCalls=[];
const widths=(process.env.COLLABORATION_WIDTHS||'360,1280').split(',').map(Number);
try{for(const width of widths){
 let page=await browser.newPage({viewport:{width,height:900},reducedMotion:'reduce'});page.on('pageerror',error=>errors.push(error.message));
 const collect=async()=>{const calls=await page.evaluate(()=>window.collaborationFixture?.calls||[]);allCalls.push(...calls);assert.deepEqual(calls.filter(call=>/confirm|approve|send|startReceiving|freezeMedia/.test(call.action)),[])};
 const open=async(kind)=>{await collect();await page.close();page=await browser.newPage({viewport:{width,height:900},reducedMotion:'reduce'});page.on('pageerror',error=>errors.push(error.message));await page.goto(server.resolvedUrls.local[0]+'tests/collaboration-depth-fixture.html?workspace='+kind);await page.getByText('合成原生回执样本 · 不连接账号、设备或模型',{exact:true}).waitFor();await page.evaluate(()=>document.fonts.ready);await page.waitForTimeout(100)};
 const click=async(name)=>{try{await page.getByRole('button',{name,exact:true}).click();}catch(error){await page.screenshot({path:path.join(output,`click-failure-${width}.png`),fullPage:true});await fs.writeFile(path.join(output,`click-failure-${width}.html`),await page.content());await fs.writeFile(path.join(output,`click-failure-${width}.json`),JSON.stringify(await page.evaluate(()=>({calls:window.collaborationFixture.calls,text:document.body.innerText})),null,2));throw error;}await page.waitForTimeout(50)};
 const tab=async(name)=>{await page.getByRole('tab',{name,exact:true}).click();await page.waitForTimeout(30)};
 const back=async()=>{await page.evaluate(()=>window.collaborationFixture.back());await page.waitForTimeout(350)};
 const heading=name=>page.getByRole('heading',{name,exact:true});
 const field=name=>page.getByRole('textbox',{name,exact:true});
 const dialog=name=>page.locator('.pw-settings-layer:not([inert])').getByRole('dialog',{name,exact:true});
 const button=name=>page.getByRole('button',{name,exact:true});
 const expectedScenes={
  'teams-directory':()=>page.locator('[data-team-layer="list"] [data-team-list-id="run-a"]'),
  'teams-run':()=>heading('把周末出行资料整理清楚'),
  'teams-message-compose':()=>field('回答这条原问题'),
  'teams-member':()=>page.locator('[data-team-member-id="research"] .pw-team-expanded-copy').getByText('阅读本周新增资料，留下引用位置。',{exact:true}),
  'teams-evidence':()=>button('重新读取原目录与范围'),
  'teams-evidence-review':()=>dialog('核对本成员读取范围').getByRole('heading',{name:'资料查询',exact:true}),
  'teams-definition':()=>heading('周末出行资料组'),
  'teams-editor':()=>dialog('编辑下次工作的团队').getByRole('textbox',{name:'团队名称',exact:true}),
  'teams-start-review':()=>dialog('开始这次原团队协作').getByText('核对周末交通与开放时间',{exact:true}),
  'groups-directory':()=>button('打开群空间：周末出行讨论'),
  'groups-requests':()=>button('读取群请求：整理周末活动与出行安排'),
  'groups-request-detail':()=>page.getByRole('article',{name:'群请求详情'}).getByRole('heading',{name:'整理周末活动与出行安排',exact:true}),
  'groups-members':()=>button('编辑群成员：小林'),
  'groups-member-editor':()=>field('成员显示名称'),
  'groups-member-review':()=>dialog('保存这份群设置').locator('.pw-group-review'),
  'groups-policy':()=>heading('群里可以做什么'),
  'groups-policy-editor':()=>page.getByRole('form',{name:'编辑能力与额度'}),
  'channels-inbox':()=>page.getByRole('tab',{name:'最近消息',exact:true}),
  'channels-message':()=>page.locator('.pw-channels-body:visible').filter({hasText:'周末想去附近走走，帮我看看这份出行安排。'}),
  'channels-media-review':()=>dialog('核对这份本人私聊原件').locator('dd').filter({hasText:/^出行想法\.wav$/}),
  'channels-task':()=>button('核对原宿主回执'),
  'channels-delivery':()=>page.locator('[role="tabpanel"]:visible').getByRole('button',{name:/已经整理好现有资料/}),
  'channels-connections':()=>dialog('连接与接收').getByRole('button',{name:/出行机器人/}),
  'channels-account':()=>dialog('出行机器人').getByRole('button',{name:'核对主人私聊',exact:true}),
  'channels-permissions':()=>dialog('出行机器人').getByText('先核对原主人私聊，再单独允许创建任务。',{exact:true}),
  'channels-profile':()=>dialog('连接设置').getByRole('textbox',{name:'连接名称',exact:true}),
  'channels-profile-originals':()=>dialog('连接设置').getByRole('checkbox',{name:'允许逐项保存本人私聊文件、语音和视频',exact:true}),
  'channels-owner':()=>dialog('核对主人私聊').getByText('出行机器人',{exact:true}),
  'channels-history':()=>dialog('这次问答可读的历史'),
  'channels-login':()=>dialog('连接微信私聊'),
  'devices-directory':()=>page.locator('[data-device-view="list"] .pw-device-row').filter({hasText:'书房电脑'}),
  'devices-detail':()=>page.locator('[data-device-view="detail"]').getByRole('heading',{name:'书房电脑',exact:true}),
  'devices-permissions':()=>heading('允许这台设备在本机使用'),
  'devices-grant-review':()=>dialog('原设备权限修改').getByText('合成只读宿主没有可提交的授权选项。',{exact:true}),
  'devices-action-review':()=>dialog('核对原设备操作').getByText('书房电脑 · peer-a',{exact:true}),
  'devices-identity':()=>page.getByText('完整证书指纹：'+Array(8).fill('aaaaaaaa').join(' '),{exact:true}),
  'devices-connection-editor':()=>dialog('更新原设备连接地址'),
  'devices-pair-editor':()=>dialog('配对你的另一台设备').getByRole('textbox',{name:'原设备短时邀请',exact:true}),
  'remote-session':()=>heading('读取项目中的资料文件'),
  'remote-configuration':()=>heading('本次任务模型网关'),
  'remote-question-and-draft':()=>heading('行程偏好'),
  'remote-local-draft':()=>field('草稿内容'),
  'nested-computer-member':()=>button('核对这名成员的执行器'),
  'nested-production-limits':()=>field('该任务最长运行秒数'),
  'nested-harness-evidence':()=>page.getByText('执行器协议已核对，尚未发送工作任务。',{exact:true}),
  'nested-remote-output':()=>page.getByText('出行资料已读取；还需要人工核对开放时间。',{exact:true}),
  'nested-remote-frozen':()=>page.getByRole('heading',{name:/^冻结版本 · a/}),
  'nested-project-history':()=>button('读取这项原工作前后版本'),
  'nested-gateway-configuration':()=>heading('config.toml'),
  'nested-configuration-review':()=>dialog('确认应用准确配置计划'),
  'nested-project-baseline':()=>dialog('先保留项目基线，再开始工作').getByText('原工作区：/fixture/notes',{exact:true}),
  'nested-project-review':()=>dialog('确认项目范围与新工作'),
  'nested-private-inputs':()=>page.getByText('合成原件范围已读取，尚未传输。',{exact:true}),
  'nested-private-input-review':()=>dialog('仅向这个准确目标发送这些原件'),
  'nested-preview-target':()=>dialog('原项目网页预览'),
  'nested-preview-review':()=>dialog('明确打开这个预览隧道'),
  'nested-desktop-idle':()=>page.locator('.pw-desktop-viewer[data-phase="idle"]'),
  'nested-desktop-tools':()=>page.locator('.pw-desktop-more[aria-label="桌面工具"]'),
 };
 const shot=async(scene)=>{
  assert(expectedScenes[scene],'Each named scene requires an explicit expected object');const target=expectedScenes[scene]();try{await target.waitFor({state:'visible'});}catch(error){await page.screenshot({path:path.join(output,scene+'-'+width+'-unconfirmed.png'),fullPage:true});await fs.writeFile(path.join(output,scene+'-'+width+'-unconfirmed.html'),await page.content());throw error;}assert.equal(await target.count(),1,scene+' identifies one actual object');
  assert(await target.evaluate(el=>!el.closest('[inert],[hidden],[aria-hidden="true"]')),scene+' expected object is in an active layer');await target.scrollIntoViewIfNeeded();
  await target.evaluate(async el=>{const layer=el.closest('.pw-settings-layer');for(let i=0;layer&&layer.dataset.visible!=='true'&&i<24;i++)await new Promise(requestAnimationFrame);if(layer&&layer.dataset.visible!=='true')throw new Error('Expected dialog has not entered its visible phase');const layers=Array.from(document.querySelectorAll('.pw-settings-layer:not([inert])[data-visible="true"]'));if(layer&&layers.at(-1)!==layer)throw new Error('Expected object is behind another active dialog');const rect=()=>{const r=el.getBoundingClientRect();return [r.x,r.y,r.width,r.height]};let last=rect();for(let i=0;i<12;i++){await new Promise(requestAnimationFrame);const now=rect();if(now.every((value,j)=>Math.abs(value-last[j])<.5)){await new Promise(requestAnimationFrame);return;}last=now;}throw new Error('Expected scene object did not reach stable layout')});
  if(scene==='teams-message-compose')assert.equal(await target.inputValue(),'优先轻松游览，保留休息时间。');if(scene==='remote-local-draft')assert.equal(await target.inputValue(),'优先保留交通与休息时间。');
  assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),scene+' has no horizontal viewport overflow');const file=`${scene}-${width}.png`;await page.screenshot({path:path.join(output,file),fullPage:true,animations:'disabled'});evidence.push({scene,width,file,phase,expectedObject:'unique visible scene-specific heading, field, receipt or original object; active layer; stable animation frames'});
 };
 if(!process.env.COLLABORATION_NESTED){const focus=process.env.COLLABORATION_FOCUS?.split(',');if(!focus||focus.includes('teams')){await open('teams');await shot('teams-directory');await page.locator('[data-team-list-id]').first().click();await shot('teams-run');await click('回答原问题');await page.getByRole('textbox',{name:'回答这条原问题',exact:true}).fill('优先轻松游览，保留休息时间。');await page.getByRole('textbox',{name:'回答这条原问题',exact:true}).scrollIntoViewIfNeeded();await shot('teams-message-compose');await click('取消引用');
 await page.locator('[data-team-member-id="research"] [slot="trigger"]').click();await shot('teams-member');
 await click('成员能读哪些资料');await click('读取原成员资料目录');await shot('teams-evidence');await click('核对本成员的准确范围');await shot('teams-evidence-review');await back();await back();await back();
 await tab('团队配置');await page.locator('[data-team-list-id]').first().click();await shot('teams-definition');await click('编辑下次工作的团队');await shot('teams-editor');await back();
 await page.getByRole('textbox',{name:'这次共同目标',exact:true}).fill('核对周末交通与开放时间');await click('核对并开始协作');assert(await page.getByRole('button',{name:'确认这一次操作',exact:true}).isDisabled());await shot('teams-start-review');
 }if(!focus||focus.includes('groups')){await open('groups');await shot('groups-directory');await click('打开群空间：周末出行讨论');await shot('groups-requests');await click('读取群请求：整理周末活动与出行安排');await shot('groups-request-detail');await back();
 await tab('成员');await shot('groups-members');await click('编辑群成员：小林');await shot('groups-member-editor');await click('核对成员变更');assert(await page.getByRole('button',{name:'确认保存',exact:true}).isDisabled());await shot('groups-member-review');await back();await back();await tab('能力与额度');await shot('groups-policy');await click('编辑能力与额度');await shot('groups-policy-editor');
 }if(!focus||focus.includes('channels')){await open('channels');await shot('channels-inbox');await page.locator('.pw-channels-records [slot="trigger"]:visible').last().click();await shot('channels-message');await page.getByRole('button',{name:/^核对原件/}).click();await shot('channels-media-review');await back();await tab('原任务');await page.locator('.pw-channels-records [slot="trigger"]:visible').first().click();await shot('channels-task');await tab('回复投递');await page.locator('.pw-channels-records [slot="trigger"]:visible').first().click();await shot('channels-delivery');
 await click('连接与接收');await shot('channels-connections');await page.getByRole('button',{name:/出行机器人/}).click();await shot('channels-account');await page.getByRole('button',{name:/^主人权限与原件/}).click();await shot('channels-permissions');
 await page.getByRole('button',{name:/^连接设置与核对/}).click();await click('连接设置');await shot('channels-profile');if(phase==='after')await page.getByRole('button',{name:/^本人私聊原件/}).click();else await page.getByText('允许逐项保存本人私聊文件、语音和视频',{exact:true}).scrollIntoViewIfNeeded();await shot('channels-profile-originals');await back();await click('核对主人私聊');await shot('channels-owner');await back();await click('已读历史范围');await shot('channels-history');await back();await back();await back();await click('连接与接收');await click('连接微信私聊');await shot('channels-login');await back();
 }if(!focus||focus.includes('devices')){await open('devices');await shot('devices-directory');await page.locator('.pw-device-row').first().click();await shot('devices-detail');await page.getByRole('button',{name:/^能力与两端权限/}).click();await shot('devices-permissions');await click('调整本机授予的权限');await page.getByRole('dialog').waitFor();await shot('devices-grant-review');await back();await click('停止主动连接');assert(await page.getByRole('button',{name:'确认本次操作',exact:true}).isDisabled());await shot('devices-action-review');await back();await page.getByRole('button',{name:/^身份与连接设置/}).click();await shot('devices-identity');await click('更新连接地址');await shot('devices-connection-editor');await back();await back();await click('配对设备');await shot('devices-pair-editor');
 }if(!focus||focus.includes('remote')){await open('remote');await shot('remote-session');assert(await page.getByRole('button',{name:'只允许这一次',exact:true}).isDisabled());await page.getByRole('button',{name:/^CLI 配置与任务模型网关/}).click();await shot('remote-configuration');await open('remote&variant=drafts');await shot('remote-question-and-draft');await page.getByRole('textbox',{name:'草稿内容',exact:true}).fill('优先保留交通与休息时间。');await page.getByRole('textbox',{name:'草稿内容',exact:true}).scrollIntoViewIfNeeded();await shot('remote-local-draft');await page.evaluate(()=>window.collaborationFixture.hide());await page.waitForTimeout(50);assert(await page.getByRole('button',{name:'仅保存草稿',exact:true}).count()===0||await page.getByRole('button',{name:'仅保存草稿',exact:true}).isDisabled(),'Hidden session cannot save or send');await page.evaluate(()=>window.collaborationFixture.show());
 }}else{
 await open('computer');await shot('nested-computer-member');await shot('nested-production-limits');await click('核对这名成员的执行器');await page.getByRole('button',{name:/^这次核对了哪些能力/}).click();await shot('nested-harness-evidence');
 await open('remote&variant=artifacts');await page.getByRole('button',{name:'执行输出与当前文件',exact:true}).click();await shot('nested-remote-output');await page.getByRole('button',{name:'成果文件与冻结版本',exact:true}).click();await shot('nested-remote-frozen');await page.getByRole('button',{name:'项目版本与对话分支',exact:true}).click();await shot('nested-project-history');
 await open('remote&variant=configured');await shot('nested-gateway-configuration');await click('核对并确认这份准确计划');assert(await page.getByRole('button',{name:'确认这一次',exact:true}).isDisabled());await shot('nested-configuration-review');
 await open('baseline');await shot('nested-project-baseline');await click('核对基线与这次新工作');await page.getByRole('dialog',{name:'确认项目范围与新工作',exact:true}).waitFor();assert(await page.getByRole('button',{name:'只提交这一次新工作',exact:true}).isDisabled());await shot('nested-project-review');
 await open('inputs');await shot('nested-private-inputs');await click('核对目标与外传范围');assert(await page.getByRole('button',{name:'确认发送本次原件',exact:true}).isDisabled());await shot('nested-private-input-review');
 await open('preview');await click('预览这个项目已运行的网页');await shot('nested-preview-target');await page.locator('.pw-selection-field').filter({has:page.getByText('原 SSH 连接',{exact:true})}).locator('button').first().click();await page.getByRole('option',{name:/书房电脑/}).click();await click('核对准确转发范围');assert(await page.getByRole('button',{name:'打开这一个原隧道',exact:true}).isDisabled());await shot('nested-preview-review');
 await open('desktop');await shot('nested-desktop-idle');await click('更多桌面操作');await shot('nested-desktop-tools');assert(await page.getByRole('button',{name:'我来操作',exact:true}).isDisabled());
 }
 await collect();const unsafe=await page.evaluate(()=>window.collaborationFixture.calls.filter(call=>/confirm|approve|send|startReceiving|freezeMedia/.test(call.action)));assert.deepEqual(unsafe,[],'Browsing never executes, approves, sends or starts receiving');
 await page.close();
}assert.deepEqual(errors,[]);console.log(JSON.stringify({phase,screenshots:evidence.length,viewports:widths,errors,nativeCalls:allCalls.length,boundary:'Synthetic native receipts; actual production React components; no device/account/model execution'}));}
finally{await browser.close();await server.close();await fs.rm(temporary,{recursive:true,force:true});await fs.writeFile(path.join(output,'scenes.json'),JSON.stringify({phase,evidence,errors},null,2)+'\n')}
