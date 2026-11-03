/* Exercise the shipped Desk controller with bounded DOM/HTTP fixtures; no visual PASS claim. */
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const PEER = "pw1_" + "a".repeat(64);
const OTHER = "pw1_" + "b".repeat(64);
function element(tag = "div") {
  return {tagName:tag.toUpperCase(), value:"", textContent:"", disabled:false, hidden:false, open:false, dataset:{}, children:[], listeners:{}, attributes:{},
    addEventListener(name, callback) { this.listeners[name] = callback; },
    dispatchEvent(event) { this.listeners[event.type]?.(event); },
    setAttribute(name,value) { this.attributes[name]=value; }, focus(){},
    replaceChildren() { this.children=[]; if(this.tagName==="SELECT")this.value=""; },
    append(...items) { for(const item of items){this.children.push(item);if(this.tagName==="SELECT"&&this.children.length===1)this.value=item.value;} }};
}
function snapshot(operation="goal-A", revision=4, updates={}) {
  const task = {taskId:"task-"+operation, runId:"run-"+operation, executorEpoch:2, executorDeviceId:PEER, instanceId:"boot-A", revision,
    state:"running", eventCursor:"0", result:{summary:"An original phone result", origin:{grantId:"grant-A"},
      controlLease:{controlLeaseId:"lease-A",expiresAt:Date.now()+60000},
      ownerInput:{format:"pocketworld.device-owner-input.v1",nativePortPresent:true,originalSessionPresent:true,
        taskId:"task-"+operation,runId:"run-"+operation,executorEpoch:2,allocationId:"allocation-A",
        runningInputState:"requires_current_write_grant_and_control_lease",
        ordinaryQuestion:{requestId:"question-A",waitRevision:4,question:"Which section?",approval:false,takeover:false}}}};
  Object.assign(task,updates);
  return {operationId:operation,peerDeviceId:PEER,status:task.state,effectState:"reported",task};
}
function description() { return {deviceId:PEER,instanceId:"boot-A",capturedAt:Date.now()-10,validUntil:Date.now()+60000,
  capabilities:[{id:"agent.goal",version:"1.0",implemented:true,installed:true}],grantReceipts:[{body:{targetInstanceId:"boot-A",grant:{grantId:"grant-A",expiresAt:1893456000000,
    toolBudget:10,modelBudget:10,capabilities:["task.submitGoal","task.acquireControl","task.answerQuestion","task.steer","task.requestResume"],resources:[{screenMode:"main_screen",packageNames:["fixture.app"]}]}}}]}; }
async function fixture(handler = async () => undefined) {
  const html=fs.readFileSync(path.join(__dirname,"phone_task_desk.html"),"utf8");
  const elements=new Map([...html.matchAll(/<(\w+)[^>]*id="([^"]+)"[^>]*>/g)].map(match=>[match[2],element(match[1])]));
  const calls=[];let serial=0;
  const context=vm.createContext({location:{hash:"#fixture-session"},history:{replaceState(){}},Date,JSON,Map,Set,Number,Error,
    document:{getElementById(id){assert.ok(elements.has(id),id);return elements.get(id);},createElement:element},
    crypto:{randomUUID:()=>"fixture-write-"+(++serial)},Event:class{constructor(type){this.type=type;}},confirm:()=>true,
    AbortController,setTimeout,clearTimeout,
    fetch:async (_url,options)=>{
      const request=JSON.parse(options.body);calls.push(request);
      assert.equal(options.credentials,"omit");assert.equal(options.redirect,"error");
      let result=await handler(request);
      if(result===undefined){
        if(request.action==="selection")result={peerDeviceId:PEER};
        else if(request.action==="doctor")result={description:description()};
        else if(request.action==="status")result=snapshot(request.params.operationId);
        else if(request.action==="list")result={operations:[]};
        else if(request.action==="events")result={events:[],nextCursor:"0",hasMore:false,snapshotRequired:false};
        else result={operationId:request.params.controlOperationId||request.params.cancelOperationId||request.params.operationId,status:"running",effectState:"reported",accepted:true};
      }
      return {ok:true,status:200,text:async()=>JSON.stringify({ok:true,result})};
    }});
  vm.runInContext(fs.readFileSync(path.join(__dirname,"phone_task_desk.js"),"utf8"),context);
  const flush=()=>new Promise(resolve=>setImmediate(resolve)); await flush();
  const get=id=>elements.get(id),run=source=>vm.runInContext(source,context);
  function select(operation){get("operation").value=operation;get("operation").dispatchEvent({type:"input"});}
  async function connected(operation="goal-A") {await run('perform("doctor",{})');select(operation);await run(`perform("status",{operationId:${JSON.stringify(operation)}})`);}
  return {calls,get,run,select,connected,flush,close:()=>run("stopWatching()")};
}
async function changedSelectionDuringWrite(isUnknown) {
  let release;
  const f=await fixture(async request=>request.action==="answer"?new Promise(resolve=>{release=resolve;}):undefined);
  f.select("goal-A");f.get("answer-text").value="SAME-DRAFT-TEXT";f.get("steer-text").value="original guidance";
  f.get("goal").value="ORIGINAL NEW GOAL DRAFT";f.get("control-operation").value="answer-A";f.run('lockedControl="answer-A"');
  const pending=f.run('perform("answer",{operationId:"goal-A",controlOperationId:"answer-A",text:"SAME-DRAFT-TEXT"})');
  await f.flush();f.select("goal-B");f.get("answer-text").value="SAME-DRAFT-TEXT";f.get("steer-text").value="different task guidance";
  release(isUnknown?{operationId:"answer-A",status:"unknown",effectState:"unknown"}:{operationId:"answer-A",status:"running",effectState:"reported",inputSaved:true});await pending;
  assert.equal(f.get("answer-text").value,"SAME-DRAFT-TEXT");assert.equal(f.get("steer-text").value,"different task guidance");
  assert.equal(f.get("goal").value,"ORIGINAL NEW GOAL DRAFT");assert.equal(f.run("lockedControl"),null);assert.equal(f.get("control-operation").value,"");
  assert.equal(f.get("result").textContent,"No current receipt read.");
  f.select("goal-A");assert.equal(f.get("answer-text").value,isUnknown?"SAME-DRAFT-TEXT":"");
  assert.equal(f.run("lockedControl"),isUnknown?"answer-A":null);
  if(isUnknown){await f.run('perform("status",{operationId:"answer-A"})');assert.equal(f.run("lockedControl"),null);}
  assert.equal(f.calls.filter(call=>call.action==="answer").length,1);f.close();
}
async function unknownSubmissionIsNeverResent() {
  const f=await fixture(async request=>{if(request.action==="run")throw new Error("fixture disconnect");});
  await f.run('perform("doctor",{})');f.get("goal").value="fixture goal";
  f.get("submit").onsubmit({preventDefault(){}});await f.flush();
  const id=f.get("operation").value;
  f.get("submit").onsubmit({preventDefault(){}});f.get("new-task").onclick();await f.flush();
  assert.equal(f.calls.filter(c=>c.action==="run").length,1);assert.equal(f.get("operation").value,id);
  assert.equal(f.get("start-task").disabled,true);assert.equal(f.get("goal").value,"fixture goal");
  await f.run(`perform("status",{operationId:${JSON.stringify(id)}})`);assert.equal(f.run("submission.confirmed"),true);f.close();
}
async function duplicateCancellationQueriesItsOriginalWrite() {
  const f=await fixture(async request=>request.action==="cancel"?{operationId:request.params.cancelOperationId,status:"unknown",effectState:"unknown"}:undefined);
  await f.connected();f.get("cancel").onclick();await f.flush();f.get("cancel").onclick();await f.flush();
  const cancel=f.calls.find(c=>c.action==="cancel");
  assert.equal(f.calls.filter(c=>c.action==="cancel").length,1);
  assert.ok(f.calls.some(c=>c.action==="status"&&c.params.operationId===cancel.params.cancelOperationId));
  assert.equal(f.get("answer").disabled,true);assert.equal(f.get("steer").disabled,true);assert.equal(f.get("resume").disabled,true);
  assert.equal(f.calls.filter(c=>c.action==="run").length,0);f.close();
}
async function staleSourceAndScopeAreFenced() {
  const f=await fixture();await f.connected();
  f.run(`acceptSnapshot(${JSON.stringify(snapshot("goal-A",2))},"goal-A",selectionVersion)`);
  assert.equal(f.run("currentTask.revision"),4);
  f.run("for(const item of scopes)item.grant.expiresAt=0;refreshInputs()");assert.equal(f.get("answer").disabled,true);
  await f.run('perform("doctor",{})');
  f.run(`acceptSnapshot(${JSON.stringify(snapshot("goal-A",5,{instanceId:"boot-B"}))},"goal-A",selectionVersion)`);
  assert.equal(f.get("answer").disabled,true);assert.equal(f.get("resume").disabled,true);f.close();
}
async function streamUsesCursorAndRejectsOtherPhone() {
  let reads=0;
  const f=await fixture(async request=>{
    if(request.action!=="events")return;
    reads++;
    return {events:[{executorDeviceId:reads===3?OTHER:PEER,instanceId:"boot-A",taskId:"task-goal-A",sequence:reads===2?1:reads,
      cursor:String(reads),kind:"progress",createdAt:Date.now(),data:{summary:"fixture event"}}],nextCursor:String(reads),hasMore:false,snapshotRequired:false};
  });
  await f.connected();await f.run('readEvents("goal-A",selectionVersion)');await f.run('readEvents("goal-A",selectionVersion)');
  assert.equal(f.get("events-list").children.length,1);
  const pages=f.calls.filter(c=>c.action==="events");assert.equal(pages[0].params.limit,32);assert.equal(pages[1].params.cursor,"1");
  await assert.rejects(f.run('readEvents("goal-A",selectionVersion)'),/original phone task/);assert.equal(f.get("events-list").children.length,1);f.close();
}
async function resumeUsesPostLeaseRevisionAndOnlyRequestsPhoneReview() {
  let revision=4;
  const f=await fixture(async request=>{
    if(request.action==="status")return snapshot(request.params.operationId,revision);
    if(request.action==="acquire_control"){revision=5;return {operationId:request.params.controlOperationId,status:"succeeded",effectState:"reported",accepted:true};}
    if(request.action==="resume")return {operationId:request.params.controlOperationId,status:"succeeded",effectState:"reported",ownerActionRequired:true};
  });
  await f.connected();f.get("acquire-control").onclick();await f.flush();await f.flush();
  assert.equal(f.run("currentTask.revision"),5);f.get("resume").onclick();await f.flush();await f.flush();
  const request=f.calls.find(c=>c.action==="resume");assert.equal(request.params.expectedRevision,5);assert.equal(request.params.operationId,"goal-A");
  assert.equal(request.params.controlLeaseId,"lease-A");assert.equal(f.calls.filter(c=>c.action==="resume").length,1);
  assert.match(f.get("notice").textContent,/Execution has not restarted/);assert.equal(f.calls.filter(c=>c.action==="run").length,0);f.close();
}
async function disconnectStopsWatchingWithoutMutation() {
  const f=await fixture(async request=>request.action==="status"?{operationId:request.params.operationId,status:"running",phoneStateUnconfirmed:true,currentRead:"unavailable"}:undefined);
  f.select("goal-A");f.get("watch").onclick();await f.flush();await f.flush();
  assert.equal(f.run("watching"),false);assert.equal(f.get("reconnect").hidden,false);
  assert.equal(f.calls.filter(c=>["run","cancel","resume"].includes(c.action)).length,0);f.close();
}
async function submissionRechecksScopeAndPreservesCustomBudget() {
  const f=await fixture();await f.run('perform("doctor",{})');
  f.get("goal").value="fixture scoped goal";f.get("tools").value="3";f.get("models").value="2";
  f.run("description.validUntil=0");
  await f.get("submit").onsubmit({preventDefault(){}});await f.flush();
  const run=f.calls.find(c=>c.action==="run");assert.ok(run);assert.equal(run.params.toolCalls,3);assert.equal(run.params.modelCalls,2);
  assert.equal(f.calls.filter(c=>c.action==="doctor").length,2);f.close();
  let doctors=0;
  const changed=await fixture(async request=>{if(request.action!=="doctor")return;const value=description();if(++doctors>1)value.grantReceipts[0].body.grant.authorizationEpoch=2;return {description:value};});
  await changed.run('perform("doctor",{})');changed.get("goal").value="must not be sent under changed scope";
  await changed.get("submit").onsubmit({preventDefault(){}});await changed.flush();
  assert.equal(changed.calls.filter(c=>c.action==="run").length,0);assert.match(changed.get("notice").textContent,/scope or this draft changed/);changed.close();
}
(async()=>{
  await changedSelectionDuringWrite(true);await changedSelectionDuringWrite(false);
  process.stdout.write("Desk draft/unknown selection fixture: 2 cases passed\n");
  for(const test of [unknownSubmissionIsNeverResent,duplicateCancellationQueriesItsOriginalWrite,staleSourceAndScopeAreFenced,streamUsesCursorAndRejectsOtherPhone,resumeUsesPostLeaseRevisionAndOnlyRequestsPhoneReview,disconnectStopsWatchingWithoutMutation,submissionRechecksScopeAndPreservesCustomBudget])await test();
  process.stdout.write("Desk mutation, revision, event, cancellation and recovery fixtures: 7 cases passed\n");
})().catch(error=>{process.stderr.write(error.stack+"\n");process.exitCode=1;});
