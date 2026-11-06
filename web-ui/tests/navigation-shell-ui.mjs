/** Actual navigation mount, local Chromium; no Android/native lifecycle claim.
 * node tests/navigation-shell-ui.mjs starts a local Vite server and tears it down. */
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import {chromium} from '@playwright/test';
import {createServer} from 'vite';
const server=process.env.NAVIGATION_UI_URL?null:await createServer({root:path.resolve(import.meta.dirname,'..'),server:{host:'127.0.0.1',port:0}});
if(server)await server.listen();
const address=server?.httpServer?.address();
const base=process.env.NAVIGATION_UI_URL||`http://127.0.0.1:${address.port}`;
const browser=await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH?{executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH}:{})});
const output=process.env.NAVIGATION_UI_EVIDENCE;
if(output)await fs.mkdir(output,{recursive:true});
try{
 const page=await browser.newPage({viewport:{width:1280,height:900}});
 const errors=[];page.on('pageerror',error=>errors.push(error.message));
 await page.goto(`${base}/tests/navigation-shell-fixture.html`);
 const nav=page.getByRole('navigation',{name:'主导航'}),chat=nav.getByRole('link',{name:'对话',exact:true});
 await chat.waitFor();
 await page.evaluate(()=>document.fonts.ready);
 await page.evaluate(()=>{window.__workspace=document.querySelector('[data-testid="persistent-workspace"]');window.__draft=document.querySelector('textarea')});
 await page.getByRole('textbox',{name:'持续编辑的草稿'}).fill('改过的草稿会在同一输入节点保留。');
 const before=await chat.locator('svg').boundingBox();
 if(output)await page.screenshot({path:path.join(output,'desktop-expanded.png')});
 await page.getByRole('button',{name:'收起导航',exact:true}).click();
 await page.waitForFunction(()=>Math.abs(document.querySelector('aside').getBoundingClientRect().width-76)<.1);
 const after=await chat.locator('svg').boundingBox();
 assert(Math.abs(before.x-after.x)<.1&&Math.abs(before.y-after.y)<.1,'Primary icon anchor moved during rail collapse');
 const hit=await chat.boundingBox();assert(hit.width>=44&&hit.height>=44,'Compact target below 44px');
 assert.equal(await chat.getAttribute('title'),'对话');
 assert.equal(await page.getByRole('button',{name:'展开导航',exact:true}).getAttribute('aria-expanded'),'false');
 await chat.focus();
 await page.keyboard.press('Tab');
 await page.keyboard.press('Shift+Tab');
 assert.notEqual(await chat.evaluate(node=>getComputedStyle(node).outlineStyle),'none','Keyboard focus must remain visible');
 await nav.getByRole('link',{name:'任务',exact:true}).click();
 assert.equal(await nav.getByRole('link',{name:'任务',exact:true}).getAttribute('aria-current'),'page');
 await page.getByRole('button',{name:'更多工具',exact:true}).click();
 await page.waitForTimeout(350);
 const disabled=page.getByRole('link',{name:'暂不可用的工作',exact:true});
 assert.equal(await disabled.getAttribute('aria-disabled'),'true');
 await disabled.click({force:true});
 assert.equal(await nav.getByRole('link',{name:'任务',exact:true}).getAttribute('aria-current'),'page','Disabled row dispatched navigation');
 assert(await page.evaluate(()=>window.__workspace===document.querySelector('[data-testid="persistent-workspace"]')&&window.__draft===document.querySelector('textarea')),'Rail toggling remounted the workspace or editor');
 assert.equal(await page.getByRole('textbox',{name:'持续编辑的草稿'}).inputValue(),'改过的草稿会在同一输入节点保留。');
 if(output){await page.waitForTimeout(350);await page.screenshot({path:path.join(output,'desktop-compact.png')})}
 await page.evaluate(()=>document.documentElement.classList.add('dark'));
 if(output){await page.waitForTimeout(200);await page.screenshot({path:path.join(output,'desktop-dark.png')})}
 await page.emulateMedia({reducedMotion:'reduce'});
 assert((await page.locator('aside').evaluate(node=>getComputedStyle(node).transitionDuration.split(',').every(duration=>parseFloat(duration)<=.001))),'Reduced motion must remove visible rail transitions');
 await page.getByRole('button',{name:'展开导航',exact:true}).click();
 await page.waitForFunction(()=>Math.abs(document.querySelector('aside').getBoundingClientRect().width-248)<.1);
 await page.getByRole('button',{name:'收起导航',exact:true}).click();
 const completeTarget=async(locator,label)=>{
  await locator.scrollIntoViewIfNeeded();
  const clipped=await locator.evaluate(node=>{
   node.scrollIntoView({block:'center',inline:'nearest',behavior:'instant'});
   const target=node.getBoundingClientRect();let left=target.left,right=target.right,top=target.top,bottom=target.bottom;
   for(let ancestor=node.parentElement;ancestor;ancestor=ancestor.parentElement){
    const style=getComputedStyle(ancestor),bounds=ancestor.getBoundingClientRect();
    if(/auto|scroll|hidden|clip/.test(style.overflowX)){left=Math.max(left,bounds.left);right=Math.min(right,bounds.right)}
    if(/auto|scroll|hidden|clip/.test(style.overflowY)){top=Math.max(top,bounds.top);bottom=Math.min(bottom,bounds.bottom)}
   }
   return {width:Math.min(right,innerWidth)-Math.max(left,0),height:Math.min(bottom,innerHeight)-Math.max(top,0)};
  });
  assert(clipped.width>=44&&clipped.height>=47.9,`${label} must scroll to a complete 48px target: ${JSON.stringify(clipped)}`);
 };
 for(const height of [360,280]){
  await page.setViewportSize({width:1280,height});
  for(const label of ['对话','任务','项目和文件'])await completeTarget(nav.getByRole('link',{name:label,exact:true}),`${height}px primary ${label}`);
  await completeTarget(page.getByRole('link',{name:'工作历史',exact:true}),`${height}px footer tool`);
  await completeTarget(page.getByRole('button',{name:'文件与成果',exact:true}),`${height}px file action`);
  const settings=page.getByRole('link',{name:'设置',exact:true});
  await completeTarget(settings,`${height}px settings`);
  await settings.click();assert.equal(await settings.getAttribute('aria-current'),'page');
  if(height===280)assert.equal(await page.locator('aside').evaluate(node=>getComputedStyle(node).overflowY),'auto');
  if(output)await page.screenshot({path:path.join(output,`desktop-short-${height}.png`)});
 }
 await page.setViewportSize({width:390,height:844});
 assert.equal(await page.locator('.pw-nav-compact-toggle').isVisible(),false,'Phone must use full drawer without compact switch');
 assert.deepEqual(errors,[]);
 console.log('Navigation shell: pinned anchors, 44px targets, labels/focus, selection, disabled actions, persistent draft/workspace, dark rendering, reduced motion, 360/280px full target scrolling and phone switch visibility passed.');
 await page.close();
}finally{await browser.close();await server?.close()}
