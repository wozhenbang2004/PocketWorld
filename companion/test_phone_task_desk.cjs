/* Actual Desk controller + a bounded DOM/HTTP fixture. This is not browser visual QA. */
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

function element() {
  return {value:"", textContent:"", disabled:false, hidden:false, listeners:{},
    addEventListener(name, callback) { this.listeners[name] = callback; },
    dispatchEvent(event) { this.listeners[event.type]?.(event); },
    replaceChildren() {}, append() {}};
}
async function fixture() {
  const html = fs.readFileSync(path.join(__dirname, "phone_task_desk.html"), "utf8");
  const elements = new Map([...html.matchAll(/id="([^"]+)"/g)].map(match => [match[1], element()]));
  const calls = []; let release;
  const context = vm.createContext({location:{hash:"#fixture-session"}, history:{replaceState(){}},
    document:{getElementById(id){assert.ok(elements.has(id), id);return elements.get(id);}, querySelectorAll(){return [...elements.values()];}, createElement:element},
    crypto:{randomUUID:()=>"fixture-control-id"}, Event:class {constructor(type){this.type=type;}}, confirm:()=>true,
    fetch:async (url, options)=>{
      const request=JSON.parse(options.body);calls.push(request);
      if(request.action === "selection")return {ok:true,json:async()=>({ok:true,result:{peerDeviceId:"fixture-peer"}})};
      if(request.action === "answer")return new Promise(resolve=>{release=result=>resolve({ok:true,json:async()=>({ok:true,result})});});
      assert.equal(request.action,"status");return {ok:true,json:async()=>({ok:true,result:{operationId:request.params.operationId,status:"running",effectState:"reported",targetOperationId:"goal-A"}})};
    }});
  vm.runInContext(fs.readFileSync(path.join(__dirname,"phone_task_desk.js"),"utf8"), context);
  await new Promise(resolve=>setImmediate(resolve));
  const get=id=>elements.get(id), run=source=>vm.runInContext(source,context);
  function select(operation) {get("operation").value=operation;get("operation").dispatchEvent({type:"input"});}
  return {calls,get,run,select,release:result=>release(result)};
}
async function changedSelectionDuringWrite(unknown) {
  const f=await fixture();f.select("goal-A");f.get("answer-text").value="SAME-DRAFT-TEXT";
  f.get("steer-text").value="original guidance";f.get("goal").value="ORIGINAL NEW GOAL DRAFT";f.get("control-operation").value="answer-A";
  f.run('lockedControl="answer-A"');
  const pending=f.run('perform("answer", {operationId:"goal-A",controlOperationId:"answer-A",text:"SAME-DRAFT-TEXT"})');
  f.select("goal-B");f.get("answer-text").value="SAME-DRAFT-TEXT";f.get("steer-text").value="different task guidance";
  f.release(unknown?{operationId:"answer-A",status:"unknown",effectState:"unknown"}:{operationId:"answer-A",status:"running",effectState:"reported",inputSaved:true});
  await pending;
  assert.equal(f.get("answer-text").value,"SAME-DRAFT-TEXT");assert.equal(f.get("steer-text").value,"different task guidance");
  assert.equal(f.get("goal").value,"ORIGINAL NEW GOAL DRAFT");assert.equal(f.run("lockedControl"),null);
  assert.equal(f.get("control-operation").value,"");assert.equal(f.calls.filter(call=>call.action==="answer").length,1);
  f.select("goal-A");assert.equal(f.get("answer-text").value,unknown?"SAME-DRAFT-TEXT":"");
  assert.equal(f.get("steer-text").value,"original guidance");assert.equal(f.get("control-operation").value,"answer-A");
  assert.equal(f.run("lockedControl"),unknown?"answer-A":null);
  if(unknown){await f.run('perform("status",{operationId:"answer-A"})');assert.equal(f.run("lockedControl"),null);assert.equal(f.get("answer-text").value,"SAME-DRAFT-TEXT");}
  assert.equal(f.calls.filter(call=>call.action==="answer").length,1);
}
(async()=>{await changedSelectionDuringWrite(true);await changedSelectionDuringWrite(false);process.stdout.write("Desk draft/unknown selection fixture: 2 cases passed\n");})().catch(error=>{process.stderr.write(error.stack+"\n");process.exitCode=1;});
