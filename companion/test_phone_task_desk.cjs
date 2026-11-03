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
async function fixture(handler = async () => undefined, options = {}) {
  const html=fs.readFileSync(path.join(__dirname,"phone_task_desk.html"),"utf8");
  const elements=new Map([...html.matchAll(/<(\w+)[^>]*id="([^"]+)"[^>]*>/g)].map(match=>[match[2],element(match[1])]));
  const calls=[];let serial=0;
  const context=vm.createContext({location:{hash:"#fixture-session"},history:{replaceState(){}},Date,JSON,Map,Set,Number,Error,
    document:{getElementById(id){assert.ok(elements.has(id),id);return elements.get(id);},createElement:element},
    crypto:{randomUUID:options.randomUUID||(()=>"fixture-write-"+(++serial))},Event:class{constructor(type){this.type=type;}},confirm:()=>true,
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
function contractRequire(condition, code) {
  if (!condition) { const error=new Error(code);error.contractCode=code;throw error; }
}
function canonicalJson(value) {
  return JSON.stringify(value,function(_key,item){return item&&typeof item==="object"&&!Array.isArray(item)
    ?Object.fromEntries(Object.keys(item).sort().map(key=>[key,item[key]])):item;});
}
function readContractInput() {
  // Native fixture snapshots are bounded by the production bridge. Keep the
  // test process bounded too, without including input or authority in errors.
  const chunks=[];let length=0;
  for (;;) {
    const chunk=Buffer.alloc(16384),size=fs.readSync(0,chunk,0,chunk.length,null);
    if (!size) break;
    length+=size;contractRequire(length<=262144,"contract_input_too_large");chunks.push(chunk.subarray(0,size));
  }
  try { return JSON.parse(Buffer.concat(chunks).toString("utf8")); }
  catch (_error) { contractRequire(false,"contract_input_invalid_json"); }
}
async function nativeAnswerContract(input) {
  // No snapshot(), description(), or successful mutation fallback is used in
  // this mode. All authority comes from the real post-acquire native read.
  const doctor=input?.doctor,status=input?.status,answer=input?.answer;
  contractRequire(doctor&&status&&answer&&typeof answer==="object"&&!Array.isArray(answer),"contract_input_invalid");
  const expectedKeys=["peerDeviceId","operationId","controlOperationId","taskId","runId","executorEpoch",
    "allocationId","controlLeaseId","text","originalRequestId","waitRevision"].sort();
  contractRequire(canonicalJson(Object.keys(answer).sort())===canonicalJson(expectedKeys),"answer_shape_mismatch");
  const selected=doctor.description?.deviceId,task=status.task,lease=task?.result?.controlLease;
  contractRequire(typeof selected==="string"&&/^pw1_[a-f0-9]{64}$/.test(selected)
    &&status.peerDeviceId===selected&&task?.executorDeviceId===selected&&answer.peerDeviceId===selected,"selected_phone_mismatch");
  contractRequire(typeof answer.operationId==="string"&&!!answer.operationId&&status.operationId===answer.operationId
    &&typeof answer.controlOperationId==="string"&&!!answer.controlOperationId,"original_operation_mismatch");
  contractRequire(typeof answer.text==="string"&&!!answer.text.trim(),"answer_text_absent");
  contractRequire(typeof lease?.controlLeaseId==="string"&&!!lease.controlLeaseId
    &&Number.isSafeInteger(lease.expiresAt)&&lease.expiresAt>Date.now(),"native_control_lease_absent_or_expired");
  let captured=null,unexpected=false;
  const f=await fixture(async request=>{
    if (request.action==="selection") return {peerDeviceId:selected};
    if (request.action==="doctor"&&request.params.peerDeviceId===selected) return doctor;
    if (request.action==="status"&&request.params.peerDeviceId===selected&&request.params.operationId===answer.operationId) return status;
    if (request.action==="answer") {
      captured=request;
      // Stop before any native dispatch; a real successful answer is submitted
      // once by the surrounding native fixture through its original MCP path.
      throw new Error("Native contract capture only; no write dispatched.");
    }
    unexpected=true;throw new Error("Unexpected native contract route.");
  },{randomUUID:()=>answer.controlOperationId});
  try {
    await f.connected(answer.operationId);
    f.get("answer-text").value=answer.text;f.get("answer-text").dispatchEvent({type:"input"});
    contractRequire(f.get("answer").disabled===false,"answer_not_enabled_by_native_snapshot");
    f.get("answer").onclick();await f.flush();await f.flush();
    contractRequire(!unexpected,"unexpected_contract_route");
    contractRequire(f.calls.filter(call=>call.action==="answer").length===1,"answer_request_count_mismatch");
    contractRequire(canonicalJson(captured)===canonicalJson({action:"answer",params:answer}),"answer_native_scope_mismatch");
    contractRequire(f.calls.map(call=>call.action).join(",")==="selection,doctor,status,answer","unexpected_contract_dispatch");
    return {format:"pocketworld.task-desk-native-contract.v1",answerEnabled:true,requestMatchesNativeScope:true,
      nativeDispatchPerformed:false,capturedAnswerRequests:1};
  } finally { f.close(); }
}
async function cancelDuringOriginalWait(waitFinishesFirst) {
  let releaseWait,releaseCancel,pending;
  const f=await fixture(async request=>{
    if(request.action==="wait")return new Promise(resolve=>{releaseWait=resolve;});
    if(request.action==="cancel")return new Promise(resolve=>{releaseCancel=resolve;});
  });
  try {
    await f.connected();pending=f.get("wait").onclick();await f.flush();
    assert.equal(f.get("cancel").disabled,false,"A read-only wait must leave cancellation available");
    f.get("cancel").onclick();await f.flush();
    const cancel=f.calls.find(c=>c.action==="cancel");assert.ok(cancel);
    assert.equal(cancel.params.operationId,"goal-A");assert.equal(cancel.params.peerDeviceId,PEER);
    f.get("cancel").onclick();await f.flush();assert.equal(f.calls.filter(c=>c.action==="cancel").length,1);
    if(waitFinishesFirst){releaseWait(snapshot("goal-A"));await pending;assert.equal(f.get("cancel").disabled,true,"Wait completion must not unlock an in-flight cancellation");}
    releaseCancel({operationId:cancel.params.cancelOperationId,status:"unknown",effectState:"unknown"});await f.flush();
    assert.equal(f.get("cancel").disabled,false);
    f.get("cancel").onclick();await f.flush();
    assert.ok(f.calls.some(c=>c.action==="status"&&c.params.operationId===cancel.params.cancelOperationId));
    assert.equal(f.calls.filter(c=>c.action==="cancel").length,1,"Unknown cancellation is queried, never replayed");
    if(!waitFinishesFirst){releaseWait(snapshot("goal-A"));await pending;}
    assert.equal(f.run("selectedOperation"),"goal-A");assert.equal(f.get("operation").value,"goal-A");
    assert.equal(f.run("currentTask"),null,"An older wait cannot overwrite an unknown original cancellation");
    assert.equal(f.calls.filter(c=>c.action==="run").length,0);
  } finally {
    releaseWait?.(snapshot("goal-A"));releaseCancel?.({status:"unknown",effectState:"unknown"});
    if(pending)await pending;await f.flush();f.close();
  }
}
(async()=>{
  if (process.argv.length>2) {
    contractRequire(process.argv.length===3&&process.argv[2]==="--native-contract","unsupported_contract_arguments");
    process.stdout.write(JSON.stringify(await nativeAnswerContract(readContractInput()))+"\n");return;
  }
  await changedSelectionDuringWrite(true);await changedSelectionDuringWrite(false);
  process.stdout.write("Desk draft/unknown selection fixture: 2 cases passed\n");
  for(const test of [unknownSubmissionIsNeverResent,duplicateCancellationQueriesItsOriginalWrite,staleSourceAndScopeAreFenced,streamUsesCursorAndRejectsOtherPhone,resumeUsesPostLeaseRevisionAndOnlyRequestsPhoneReview,disconnectStopsWatchingWithoutMutation,submissionRechecksScopeAndPreservesCustomBudget])await test();
  process.stdout.write("Desk mutation, revision, event, cancellation and recovery fixtures: 7 cases passed\n");
  await cancelDuringOriginalWait(false);await cancelDuringOriginalWait(true);
  process.stdout.write("Desk cancellation during pending wait: 2 settlement orders passed\n");
})().catch(error=>{
  // Contract input contains real signed scope and an original answer. Never
  // include assertion diffs, stacks, leases, or input text in native CI logs.
  if (process.argv.length>2) process.stderr.write(JSON.stringify({format:"pocketworld.task-desk-native-contract.v1",
    error:error.contractCode||"contract_execution_failed"})+"\n");
  else process.stderr.write(error.stack+"\n");
  process.exitCode=1;
});
