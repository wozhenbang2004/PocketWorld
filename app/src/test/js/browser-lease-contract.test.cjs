// Exercises shipped compatibility handlers. No DOM rendering or real WebView claim.
const fs=require('node:fs');
const path=require('node:path');
const assert=require('node:assert/strict');
const html=fs.readFileSync(path.join(__dirname,'../../main/assets/index.html'),'utf8');
function between(start,end){const at=html.indexOf(start),stop=html.indexOf(end,at+start.length);assert.ok(at>=0&&stop>at);return html.slice(at,stop);}
const helpers=between('function browserIdentity(lease){','function renderBrowser(host){');
const grant=(overrides={})=>({active:true,taskId:'task-1',runId:'run-1',leaseId:'lease-1',expiresAt:Date.now()+600000,...overrides});
function page(snapshot,respond,cache={}){
 const state={browser:snapshot},calls=[];
 const handler=async(feature,command,payload)=>{calls.push({feature,command,payload:{...payload}});return respond(command,payload);};
 const api=new Function('data','cache','featureState','request','notice','refresh','renderCenter',helpers+';return {read:readBrowserPage,identity:browserIdentity};')(()=>state.browser,cache,state,handler,()=>{},()=>{},()=>{});
 return {...api,cache,state,calls};
}
async function checks(){
 const fresh=page({open:true},async command=>command==='acquire'?grant({hostTaskId:'task-1'}):command==='observe'?{text:'real returned page'}:grant({active:false,status:'release'}));
 await fresh.read();assert.deepEqual(fresh.calls.map(x=>x.command),['acquire','observe','release']);assert.deepEqual(fresh.calls[1].payload,{taskId:'task-1',runId:'run-1',leaseId:'lease-1'});assert.deepEqual(fresh.calls[2].payload,fresh.calls[1].payload);assert.equal(fresh.cache.browserObservation.text,'real returned page');assert.equal(fresh.cache.browserReading,false);assert.equal(fresh.state.browser.lease.active,false);
 const owned=page({open:true,lease:grant()},async command=>{assert.equal(command,'observe');return {text:'owner inspection'};});await owned.read();assert.deepEqual(owned.calls.map(x=>x.command),['observe'],'Reading must not release an executor lease acquired by another flow.');
 const resumed=page({open:true,lease:grant({expiresAt:Date.now()-1})},async(command,payload)=>{if(command==='acquire'){assert.equal(payload.taskId,'task-1');return grant({runId:'run-2',leaseId:'lease-2'});}if(command==='observe'){assert.equal(payload.runId,'run-2');assert.equal(payload.leaseId,'lease-2');return {text:'new observation'};}return grant({active:false});},{browserTaskId:'task-1'});await resumed.read();assert.deepEqual(resumed.calls.map(x=>x.command),['acquire','observe','release']);
 const broken=page({open:true},async command=>{if(command==='acquire')return grant();if(command==='observe')throw new Error('page changed');return grant({active:false});});await assert.rejects(broken.read(),/page changed/);assert.deepEqual(broken.calls.map(x=>x.command),['acquire','observe','release']);assert.equal(broken.cache.browserReading,false);
 const unreleased=page({open:true},async command=>{if(command==='acquire')return grant();if(command==='observe')return {text:'saved evidence'};throw new Error('release missing');});await assert.rejects(unreleased.read(),/释放尚未确认/);assert.equal(unreleased.cache.browserObservation.text,'saved evidence');assert.equal(unreleased.cache.browserReading,false);
 const invalid=page({open:true},async()=>grant({active:false}));await assert.rejects(invalid.read(),/没有返回有效执行授权/);assert.equal(invalid.calls.length,1,'An invalid admission must never dispatch observe with empty or fabricated identity.');
 let admitted;const queued=page({open:true},command=>command==='acquire'?new Promise(resolve=>{admitted=resolve;}):Promise.resolve(command==='observe'?{text:'one read'}:grant({active:false})));const first=queued.read();await queued.read();assert.equal(queued.calls.length,1);admitted(grant());await first;assert.equal(queued.calls.filter(x=>x.command==='acquire').length,1);
 // Read the actual history callback with a canonical conversationId and no legacy id.
 const render=between('function renderChatConversation(){','function sendChat(){');const clicked=[],requests=[];const node=()=>({append(){},replaceChildren(){}});const history={showConversations:true,conversations:[{conversationId:'canonical-conversation',title:'saved chat'}]};
 const chat=new Function('cache','$','el','btn','listRow','request','native','valueText',"const chatMode=true,chatBusy=false;let conversationId='',chatMessages=[];"+render+';return {render:renderChatConversation,id:()=>conversationId};')(history,()=>node(),()=>node(),()=>node(),(title,copy,callback)=>{clicked.push(callback);return node();},(feature,command,payload)=>{requests.push({feature,command,payload});return Promise.resolve({messages:[]});},()=>{},message=>message.content||'');
 chat.render();assert.equal(clicked.length,1);clicked[0]();await new Promise(resolve=>setImmediate(resolve));assert.equal(requests[0].payload.conversationId,'canonical-conversation');assert.equal(chat.id(),'canonical-conversation','A get response without another id must retain the selected canonical conversation.');
 console.log('Compatibility browser lease and canonical chat history checks passed. No rendered UI claim.');
}
checks().catch(error=>{console.error(error);process.exitCode=1;});
