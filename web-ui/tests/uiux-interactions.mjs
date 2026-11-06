/* Starts Vite for the fixture. Tests isolated callbacks,
 * not native task execution. UIUX_CHROMIUM may select an installed browser. */
import assert from 'node:assert/strict';
import {mkdir} from 'node:fs/promises';
import {chromium} from '@playwright/test';
import {createServer} from 'vite';
const server=process.env.UIUX_BASE_URL?null:await createServer({server:{host:'127.0.0.1',port:4188,strictPort:true}});
await server?.listen();
const browser=await chromium.launch({headless:true,...(process.env.UIUX_CHROMIUM?{executablePath:process.env.UIUX_CHROMIUM}:{}),args:['--no-sandbox']});
const errors=[];const page=await browser.newPage({viewport:{width:390,height:844},reducedMotion:'reduce'});page.on('pageerror',error=>errors.push(error.message));
try{
 await page.goto(`${process.env.UIUX_BASE_URL||'http://127.0.0.1:4188'}/tests/fixtures/interactions.html`);
 await page.evaluate(()=>document.fonts.ready);
 const search=page.getByRole('combobox',{name:'搜索已加载工作与功能'});await search.waitFor();
 await search.fill('项目文档');assert.equal(await page.getByRole('option').count(),1);await search.press('Enter');assert.equal(await page.getByTestId('picked').textContent(),'b');
 await search.fill('不存在');await search.press('Enter');assert.equal(await page.getByTestId('picked').textContent(),'b');assert.equal(await page.getByRole('option').count(),0);
 await search.fill('');await search.evaluate(el=>el.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true,isComposing:true})));assert.equal(await page.getByTestId('picked').textContent(),'b');
 await search.press('ArrowDown');await search.press('Enter');assert.equal(await page.getByTestId('picked').textContent(),'b');
 const firstHandle=page.getByRole('button',{name:'调整目录顺序：核对机票价格'});await firstHandle.focus();await firstHandle.press('End');
 assert.deepEqual(await page.locator('.pw-review-select strong').allTextContents(),['整理项目文档','提醒查看更新','核对机票价格']);assert.equal(await page.getByTestId('selected').textContent(),'a');
 await page.getByRole('button',{name:'恢复目录顺序'}).click();assert.deepEqual(await page.locator('.pw-review-select strong').allTextContents(),['核对机票价格','整理项目文档','提醒查看更新']);
 const row=page.locator('.pw-review-swipe-surface').nth(2);await row.scrollIntoViewIfNeeded();const rect=await row.boundingBox();assert.ok(rect);
 await page.mouse.move(rect.x+rect.width-30,rect.y+rect.height/2);await page.mouse.down();await page.mouse.move(rect.x+rect.width-155,rect.y+rect.height/2,{steps:12});await page.mouse.up();
 await page.waitForFunction(()=>document.querySelectorAll('.pw-review-swipe-surface')[2].style.transform.includes('-80'));
 assert.equal(await page.getByTestId('selected').textContent(),'a');assert.equal(await page.getByTestId('approved').textContent(),'0');await page.getByRole('button',{name:'查看详情',exact:true}).click();assert.equal(await page.getByTestId('selected').textContent(),'c');
 const more=page.getByRole('button',{name:'整理项目文档的更多操作'});await more.focus();await more.press('Enter');await page.getByRole('button',{name:'查看详情',exact:true}).click();assert.equal(await page.getByTestId('selected').textContent(),'b');assert.equal(await page.getByTestId('approved').textContent(),'0');
 await page.getByRole('button',{name:'确认并开始',exact:true}).click();assert.equal(await page.getByTestId('approved').textContent(),'1');
 const time=page.getByRole('textbox',{name:'当地时间 HH:mm'});await time.fill('12:47');assert.equal(await page.getByRole('spinbutton',{name:'提醒小时'}).getAttribute('aria-valuetext'),'12');assert.equal(await page.getByRole('spinbutton',{name:'提醒分钟'}).getAttribute('aria-valuetext'),'47');
 await time.fill('09:30');await page.evaluate(()=>{for(const name of ['提醒小时','提醒分钟'])document.querySelector(`[role="spinbutton"][aria-label="${name}"]`).dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowDown',bubbles:true}));});assert.equal(await page.getByTestId('time').textContent(),'10:31');
 const slider=page.getByRole('slider',{name:'本次预算'});await slider.focus();await slider.press('ArrowRight');assert.equal(await page.getByTestId('budget').textContent(),'35');await slider.press('End');assert.equal(await page.getByTestId('budget').textContent(),'100');await slider.press('ArrowRight');assert.equal(await page.getByTestId('budget').textContent(),'100');
 const hour=page.getByRole('spinbutton',{name:'提醒小时'});await hour.focus();const transform=await hour.locator('[data-rwp-options]').evaluate(el=>el.style.transform);await page.getByTestId('pending').click();
 assert.equal(await page.getByRole('button',{name:'正在提交…'}).isDisabled(),true);assert.equal(await time.isDisabled(),true);assert.equal(await slider.isDisabled(),true);
 await hour.evaluate(el=>el.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowDown',bubbles:true})));assert.equal(await page.getByTestId('time').textContent(),'10:31');assert.equal(await hour.locator('[data-rwp-options]').evaluate(el=>el.style.transform),transform);
 assert.equal(await page.getByTestId('approved').textContent(),'1');assert.equal(await page.getByRole('alert').textContent(),'请求处理中，保留原错误状态。');
 const overflow=await page.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth,elements:[...document.querySelectorAll('main *')].filter(el=>el.getBoundingClientRect().right>innerWidth).slice(0,12).map(el=>({tag:el.tagName,cls:el.className,right:el.getBoundingClientRect().right,width:el.getBoundingClientRect().width}))}));
 assert.equal(overflow.scroll>overflow.width,false);
 await mkdir('test-results/uiux-interactions',{recursive:true});await page.screenshot({path:'test-results/uiux-interactions/mobile.png',fullPage:true});
 await page.getByTestId('pending').click();await time.fill('25:99');assert.equal(await page.getByRole('spinbutton').count(),0);assert.equal(await page.getByTestId('time').textContent(),'25:99');
 await time.fill('09:30');await page.emulateMedia({reducedMotion:'no-preference'});await page.setViewportSize({width:1000,height:860});await page.getByRole('spinbutton',{name:'提醒分钟'}).press('ArrowDown');await page.waitForFunction(()=>document.querySelector('[data-testid="time"]').textContent==='09:31');
 await page.screenshot({path:'test-results/uiux-interactions/desktop.png',fullPage:true});
 assert.deepEqual(errors,[]);console.log('PASS: search/IME, view-only sort+undo, pointer and keyboard swipe detail, approval pending, exact time, concurrent wheels, reduced motion, numeric bounds, narrow layout; 0 browser errors.');
}finally{await browser.close();await server?.close()}
