// Real Chromium + local HTTP fixture. This is not an Android WebView/provider/device test.
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'/opt/codex/cua_node/lib/node_modules/playwright');
const http=require('node:http'),fs=require('node:fs'),assert=require('node:assert/strict');
const input=JSON.parse(fs.readFileSync(process.argv[2],'utf8')),destination=process.argv[3];
const samples=[],requests=[];let browser,server;
const html=`<!doctype html><meta charset="utf-8"><title>Dynamic ledger fixture</title>
<main><section aria-label="Ledger area" aria-busy="true"><table id="ledger" aria-label="Ledger"><thead><tr><th>Name</th><th>Amount</th></tr></thead><tbody></tbody></table>
<nav><a rel="prev" href="?page=0" aria-disabled="true">Previous</a><span aria-current="page">1</span><button id="next" disabled>Next</button></nav><iframe src="http://localhost:PORT/child"></iframe></section></main><aside id="ticker"></aside>
<script>let page=1;const area=document.querySelector('section'),body=document.querySelector('tbody'),next=document.querySelector('#next');
async function load(){area.setAttribute('aria-busy','true');next.disabled=true;const data=await(await fetch('/rows?page='+page)).json();body.innerHTML=data.map(x=>'<tr><td>'+x[0]+'</td><td>'+x[1]+'</td></tr>').join('');area.setAttribute('aria-busy','false');document.querySelector('[aria-current]').textContent=page;next.disabled=page===2;}
next.onclick=()=>{page++;load()};load();setInterval(()=>document.querySelector('#ticker').textContent=Date.now(),80);
window.transient=()=>{const cell=body.querySelector('td');const old=cell.textContent;setTimeout(()=>cell.textContent='TRANSIENT',30);setTimeout(()=>cell.textContent=old,80)};
</script>`;
(async()=>{try{
 server=http.createServer((req,res)=>{requests.push(req.url);if(req.url.startsWith('/rows')){const page=new URL(req.url,'http://fixture').searchParams.get('page');setTimeout(()=>{res.setHeader('Content-Type','application/json');res.end(JSON.stringify(page==='1'?[['Alpha',42],['Beta',73]]:[['Gamma',91],['Delta',18]]))},page==='1'?450:650)}else if(req.url==='/child')res.end('<p>IFRAME_SECRET_NOT_READ</p>');else{res.setHeader('Content-Type','text/html');res.end(html.replaceAll('PORT',server.address().port))}});
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 browser=await chromium.launch({executablePath:process.env.CHROMIUM_EXECUTABLE_PATH||undefined,headless:true,args:['--no-sandbox','--disable-dev-shm-usage']});const page=await browser.newPage();await page.goto(`http://127.0.0.1:${server.address().port}/`,{waitUntil:'domcontentloaded'});
 const start=Date.now();async function sample(phase,script=input.table){const raw=JSON.parse(await page.evaluate(script));samples.push({phase,elapsedMs:Date.now()-start,raw});assert(!JSON.stringify(raw).includes('IFRAME_SECRET_NOT_READ'));return raw;}
 const first=await sample('initial');assert.equal(first.read.dynamic.busy,true);assert.equal(first.read.dynamic.contentItems,0);
 await page.waitForFunction(()=>document.querySelector('section').getAttribute('aria-busy')==='false');let loaded=await sample('loaded');assert.equal(loaded.read.dynamic.contentItems,2);assert.equal(loaded.read.dynamic.pagination.find(x=>x.kind==='next').disabled,false);assert.equal(loaded.read.dynamic.pagination.find(x=>x.kind==='current').label,'1');
 await page.evaluate(()=>transient());await page.waitForTimeout(140);const restored=await sample('transient_restored');assert.equal(restored.read.facts.projectionVersion,loaded.read.facts.projectionVersion);assert(restored.read.dynamic.mutationVersion>loaded.read.dynamic.mutationVersion);
 for(let i=0;i<4;i++){await page.waitForTimeout(200);loaded=await sample('page1_quiet')}
 const before=loaded;await page.locator('#next').click();const old=await sample('page2_pending',input.page2);assert.equal(old.read.dynamic.busy,true);assert.equal(old.read.data[1].cells[0].text,'Alpha');
 await page.waitForFunction(()=>document.querySelector('[aria-current]').textContent==='2');for(let i=0;i<5;i++){await page.waitForTimeout(200);await sample('page2_quiet',input.page2)}
 const final=samples.at(-1).raw;assert.equal(final.read.data[1].cells[0].text,'Gamma');assert.equal(final.read.dynamic.pagination.find(x=>x.kind==='next').disabled,true);assert.notEqual(final.read.facts.projectionVersion,before.read.facts.projectionVersion);
 // No page program issued any request: only initial fixture, child, favicon, and each explicit page fetch.
 assert.equal(requests.filter(x=>x==='/rows?page=1').length,1);assert.equal(requests.filter(x=>x==='/rows?page=2').length,1);
 await page.screenshot({path:destination.replace(/\.json$/,'.png')});fs.writeFileSync(destination,JSON.stringify({browser:await browser.version(),platform:'local_desktop_chromium_not_android_webview',requests,samples},null,2));console.log(JSON.stringify({status:'PASS',samples:samples.length,requests,finalRows:final.read.data,androidProviderVerified:false}));
}finally{if(browser)await browser.close();if(server)await new Promise(resolve=>server.close(resolve));}})().catch(e=>{console.error(e);process.exitCode=1});
