// Exact production read programs + local Chromium DOM. Not Android WebView/device evidence.
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'/opt/codex/cua_node/lib/node_modules/playwright');
const fs=require('node:fs'),assert=require('node:assert/strict'),{randomUUID}=require('node:crypto');
const scripts=JSON.parse(fs.readFileSync(process.argv[2],'utf8')),destination=process.argv[3];
const baseline=process.argv.includes('--expect-owner-failure'),samples=[],checks=[];
let browser,page;
async function scene(html,setup){
 await page.goto('about:blank');await page.setContent('<!doctype html><title>Owned semantic rows</title>'+html);
 if(setup)await page.evaluate(setup);
}
async function sample(kind,cursor=null,region=null){
 let code=scripts[kind].replaceAll('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',randomUUID());
 if(cursor)code=code.replaceAll('cccccccc-cccc-4ccc-8ccc-cccccccccccc:c0',cursor);
 if(region)code=code.replaceAll('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb:r1',region);
 const raw=JSON.parse(await page.evaluate(code));samples.push({kind,raw});
 if(raw.read){const f=raw.read.facts,w=f.captureWork;assert.equal(raw.read.data.length,f.returnedItems);assert(w.nodesVisited<=6000&&w.textCharsVisited<=180000&&w.layoutChecks<=6000&&w.queuedPeak<=6000);assert.equal(f.wholePageComplete,false)}
 return raw;
}
async function selected(mode){
 const outline=await sample('outline');assert.equal(outline.status,'observed');
 const ref=outline.read.data.find(x=>x.name==='Selected')?.regionRef;assert(ref,'fixture target was not discovered');
 return sample('region_'+mode,null,ref);
}
function complete(raw){assert.equal(raw.status,'observed');assert.equal(raw.read.cursor,null);assert.equal(raw.read.facts.partial,false,JSON.stringify(raw.read.facts))}
function record(name){checks.push(name);console.log('PASS '+name)}
function rowTexts(raw){return raw.read.data.map(row=>row.cells.map(cell=>cell.text).join(' | '))}
(async()=>{try{
 browser=await chromium.launch({executablePath:process.env.CHROMIUM_EXECUTABLE_PATH||undefined,headless:true,args:['--no-sandbox','--disable-dev-shm-usage']});page=await browser.newPage();
 await scene('<pw-grid role="grid" aria-label="Selected" style="display:block"></pw-grid>',()=>{
  document.querySelector('pw-grid').attachShadow({mode:'open'}).innerHTML='<div role="row"><span role="gridcell">Shadow row 0</span></div><div role="row"><span role="gridcell">Shadow row 1</span></div>';
 });
 let raw=await selected('table');complete(raw);
 if(baseline)assert.deepEqual(raw.read.data,[],'baseline no longer reproduces direct-shadow row loss');
 else{assert.deepEqual(rowTexts(raw),['Shadow row 0','Shadow row 1']);record('direct open-shadow grid rows retain their selected owner')}
 await scene('<pw-list role="list" aria-label="Selected" style="display:block"></pw-list>',()=>{
  document.querySelector('pw-list').attachShadow({mode:'open'}).innerHTML='<div role="listitem">Shadow item 0</div><div role="listitem">Shadow item 1</div>';
 });
 raw=await selected('list');complete(raw);
 if(baseline){
  assert.deepEqual(raw.read.data,[],'baseline no longer reproduces direct-shadow listitem loss');
  fs.writeFileSync(destination,JSON.stringify({status:'EXPECTED_SHADOW_OWNER_FAILURE',browser:await browser.version(),platform:'local_chromium_not_android_webview',missingRowsWithPartialFalse:true,samples},null,2));
  console.error('EXPECTED_SHADOW_OWNER_FAILURE: direct shadow rows/items disappeared with partial=false');process.exitCode=3;return;
 }
 assert.deepEqual(raw.read.data.map(x=>x.text),['Shadow item 0','Shadow item 1']);record('direct open-shadow listitems retain their selected owner');
 await scene('<pw-grid role="grid" aria-label="Selected" style="display:block"><div role="row" slot="rows"><span role="gridcell">Slotted row</span></div></pw-grid>',()=>{
  document.querySelector('pw-grid').attachShadow({mode:'open'}).innerHTML='<div role="rowgroup"><slot name="rows"></slot></div>';
 });
 raw=await selected('table');complete(raw);assert.deepEqual(rowTexts(raw),['Slotted row']);record('slot and shadow rowgroup ancestry reaches the original grid');
 await scene('<pw-grid role="grid" aria-label="Selected" style="display:block"></pw-grid>',()=>{
  const root=document.querySelector('pw-grid').attachShadow({mode:'open'});
  root.innerHTML='<div role="row"><span role="gridcell">Outer row</span></div><pw-inner role="grid" aria-label="Nested" style="display:block"></pw-inner>';
  root.querySelector('pw-inner').attachShadow({mode:'open'}).innerHTML='<div role="row"><span role="gridcell">Nested row</span></div>';
 });
 raw=await selected('table');complete(raw);assert.deepEqual(rowTexts(raw),['Outer row']);record('nested shadow grids cannot donate their own rows to an outer grid');
 await scene('<pw-grid role="grid" aria-label="Selected" style="display:block"><div role="row" slot="own"><span role="gridcell">Outer slot row</span></div><div role="row" slot="nested"><span role="gridcell">Nested slot row</span></div></pw-grid>',()=>{
  document.querySelector('pw-grid').attachShadow({mode:'open'}).innerHTML='<slot name="own"></slot><div role="grid" aria-label="Nested"><slot name="nested"></slot></div>';
 });
 raw=await selected('table');complete(raw);assert.deepEqual(rowTexts(raw),['Outer slot row']);record('slot redistribution respects the nearest composed grid instead of the light-DOM parent');
 await scene('<pw-list role="list" aria-label="Selected" style="display:block"></pw-list>',()=>{
  document.querySelector('pw-list').attachShadow({mode:'open'}).innerHTML='<div role="listitem">Outer item</div><div role="list" aria-label="Nested"><div role="listitem">Nested item</div></div>';
 });
 raw=await selected('list');complete(raw);assert.deepEqual(raw.read.data.map(x=>x.text),['Outer item']);record('nested lists remain separate from their outer selected list');
 await scene('<table aria-label="Selected"><tbody><tr><td>Outer 0</td><td><table aria-label="Nested"><tbody><tr><td>Nested row</td></tr></tbody></table></td></tr><tr><td>Outer 1</td></tr></tbody></table>');
 raw=await selected('table');complete(raw);assert.equal(raw.read.data.length,2);assert.deepEqual(raw.read.data.map(x=>x.cells[0].text),['Outer 0','Outer 1']);record('native light-DOM table rowgroups and nested tables keep their existing ownership');
 await scene('<pw-grid role="grid" aria-label="Selected" style="display:block"></pw-grid>',()=>{
  document.querySelector('pw-grid').attachShadow({mode:'open'}).innerHTML='<div hidden><div role="row"><span role="gridcell">Hidden row</span></div></div><div role="row"><span role="gridcell">Visible row</span></div>';
 });
 raw=await selected('table');complete(raw);assert.deepEqual(rowTexts(raw),['Visible row']);record('hidden composed ancestors do not become structured rows');
 await page.evaluate(()=>document.querySelector('pw-grid').setAttribute('aria-rowcount','3'));
 raw=await selected('table');assert.deepEqual(rowTexts(raw),['Visible row']);assert(raw.read.facts.partial);assert(raw.read.facts.reasons.includes('virtual_rows_not_in_dom'));record('missing virtual rows still require an explicit partial reason');
 await scene('<pw-grid role="grid" aria-label="Selected" style="display:block"></pw-grid>',()=>{
  let parent=document.querySelector('pw-grid').attachShadow({mode:'open'});
  for(let i=0;i<140;i++){const wrapper=document.createElement('div');parent.append(wrapper);parent=wrapper}
  parent.innerHTML='<div role="row"><span role="gridcell">Beyond depth budget</span></div>';
 });
 raw=await selected('table');assert.deepEqual(raw.read.data,[]);assert(raw.read.facts.partial);assert(raw.read.facts.reasons.some(x=>['layout_budget','depth_budget'].includes(x)));record('depth-limited composed coverage remains partial instead of complete empty data');
 await scene('<pw-grid role="grid" aria-label="Selected" style="display:block"></pw-grid>',()=>{
  document.querySelector('pw-grid').attachShadow({mode:'open'}).innerHTML=Array.from({length:55},(_,i)=>'<div role="row"><span role="gridcell">Row '+i+'</span></div>').join('');
 });
 raw=await selected('table');assert(raw.read.cursor);const rows=[...raw.read.data];let pages=1;
 while(raw.read.cursor){assert(raw.read.facts.partial);assert(raw.read.facts.reasons.includes('structured_row_range_continuation'));raw=await sample('cursor_table',raw.read.cursor);assert.equal(raw.status,'observed');rows.push(...raw.read.data);assert(++pages<10,'structured cursor did not progress')}
 assert.deepEqual(rows.map(x=>x.index),Array.from({length:55},(_,i)=>i));assert.equal(rows.at(-1).cells[0].text,'Row 54');record('owned shadow rows continue without duplication or index loss');
 raw=await selected('table');assert(raw.read.cursor);const cursor=raw.read.cursor;
 await page.evaluate(()=>document.querySelector('pw-grid').shadowRoot.querySelector('[role=gridcell]').textContent='Changed row');
 raw=await sample('cursor_table',cursor);assert.equal(raw.status,'unavailable');assert.equal(raw.code,'read_region_changed');record('shadow mutation still invalidates an existing structured cursor');
 fs.writeFileSync(destination,JSON.stringify({status:'PASS',browser:await browser.version(),platform:'local_chromium_not_android_webview',checks,samples,androidProviderVerified:false},null,2));
 console.log(JSON.stringify({status:'PASS',checks:checks.length,samples:samples.length,androidProviderVerified:false}));
}finally{if(browser)await browser.close()}})().catch(error=>{console.error(error);process.exitCode=1});
