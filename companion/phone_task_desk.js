"use strict";
const token = location.hash.slice(1);
history.replaceState(null, "", "/");
let peer = null, busy = false, cancelId = null, scopes = [];
let currentTask = null, selectedOperation = "", lockedControl = null;
const inputDrafts = new Map();
const byId = id => document.getElementById(id);
const show = value => { byId("result").textContent = JSON.stringify(value, null, 2); };
function recordInputResult(action, params, result) {
  const selected = params.operationId === byId("operation").value.trim();
  const draft = selected ? {answer:byId("answer-text").value, steer:byId("steer-text").value, control:byId("control-operation").value, locked:lockedControl}
    : inputDrafts.get(params.operationId);
  if (!draft) return;
  if (result.status === "unknown" || result.effectState === "unknown") draft.locked = params.controlOperationId;
  else if (draft.locked === params.controlOperationId) draft.locked = null;
  const field = action === "answer" ? "answer" : action === "steer" ? "steer" : null;
  if (result.inputSaved === true && field && draft[field] === params.text) draft[field] = "";
  inputDrafts.set(params.operationId, draft);
  if (selected) { lockedControl = draft.locked; byId("answer-text").value = draft.answer; byId("steer-text").value = draft.steer; }
}
function recordControlRead(operation, result) {
  if (result.effectState === "unknown" || result.status === "unknown") return;
  if (operation === lockedControl) lockedControl = null;
  for (const draft of inputDrafts.values()) if (draft.locked === operation) draft.locked = null;
}
async function call(action, params) {
  const response = await fetch("/api", {method:"POST", cache:"no-store", credentials:"omit", redirect:"error", headers:{"Authorization":"Bearer " + token,"Content-Type":"application/json"},body:JSON.stringify({action,params})});
  const value = await response.json();
  if (!response.ok || value.ok !== true) throw new Error("Original action unconfirmed. Read the same operation ID. No request was retried.");
  return value.result;
}
async function perform(action, params) {
  if (busy || !peer) return;
  busy = true;
  document.querySelectorAll("button").forEach(button => button.disabled = true);
  byId("notice").textContent = "Reading the selected phone…";
  try {
    const result = await call(action, {peerDeviceId:peer,...params});
    if (action === "doctor") updateScopes(result.description);
    if (result.task && (params.operationId === byId("operation").value.trim() || result.targetOperationId === byId("operation").value.trim())) currentTask = result.task;
    if (["acquire_control", "answer", "steer"].includes(action)) {
      recordInputResult(action, params, result);
      try {
        const latest = await call("status", {peerDeviceId:peer, operationId:params.operationId});
        if (params.operationId === byId("operation").value.trim() && latest.task) currentTask = latest.task;
      } catch (readUnavailable) { if (params.operationId === byId("operation").value.trim()) currentTask = null; }
    }
    if (action === "status") recordControlRead(params.operationId, result);
    show(result); byId("notice").textContent = result.status === "unknown" || result.effectState === "unknown" ? "Outcome unknown. Keep this operation ID and read its status." : result.inputSaved === true ? "Input saved in the original task. Reading its state does not prove model consumption." : "Original phone response received.";
  }
  catch(error) { byId("notice").textContent = error.message; }
  finally { busy = false; document.querySelectorAll("button").forEach(button => button.disabled = false); refreshInputs(); }
}
function currentInput() {
  const input = currentTask?.result?.ownerInput;
  return input?.format === "pocketworld.device-owner-input.v1" && input.nativePortPresent === true && input.originalSessionPresent === true ? input : null;
}
function signedMethod(method) {
  const grantId = currentTask?.result?.origin?.grantId;
  return scopes.some(item => item.grant.grantId === grantId && item.grant.expiresAt > Date.now() && item.grant.capabilities?.includes(method));
}
function refreshInputs() {
  const input = currentInput(), question = input?.ordinaryQuestion, lease = currentTask?.result?.controlLease;
  const ordinary = question && question.approval === false && question.takeover === false;
  const leaseValid = !!lease?.controlLeaseId && lease.expiresAt > Date.now();
  byId("input-scope").textContent = input ? "Original task · " + input.taskId + " · run " + input.runId + " · allocation " + input.allocationId : "Read the original task and check its current signed phone scope first.";
  byId("ordinary-question").hidden = !ordinary;
  byId("question-text").textContent = ordinary ? question.question : "";
  byId("running-steer").hidden = input?.runningInputState !== "requires_current_write_grant_and_control_lease";
  byId("acquire-control").disabled = busy || !input || !signedMethod("task.acquireControl") || !!lockedControl;
  byId("acquire-control").textContent = leaseValid ? "Renew original task control" : "Acquire original task control";
  byId("answer").disabled = busy || !ordinary || !leaseValid || !signedMethod("task.answerQuestion") || !!lockedControl || !byId("answer-text").value.trim();
  byId("steer").disabled = busy || !input || byId("running-steer").hidden || !leaseValid || !signedMethod("task.steer") || !!lockedControl || !byId("steer-text").value.trim();
  byId("control-status").disabled = busy || !byId("control-operation").value;
}
function writeInput(action) {
  const input = currentInput(); if (!input || busy || lockedControl) return;
  const controlOperationId = crypto.randomUUID();
  byId("control-operation").value = controlOperationId; // Visible before the possible write.
  const params = {operationId:byId("operation").value.trim(), controlOperationId, taskId:input.taskId, runId:input.runId, executorEpoch:input.executorEpoch, allocationId:input.allocationId};
  const lease = currentTask.result.controlLease;
  if (action === "acquire_control") { params.ttlMs = 30000; if (lease?.controlLeaseId) params.previousLeaseId = lease.controlLeaseId; }
  else {
    if (!lease?.controlLeaseId) return;
    params.controlLeaseId = lease.controlLeaseId; params.text = byId(action === "answer" ? "answer-text" : "steer-text").value;
    if (action === "answer") { params.originalRequestId = input.ordinaryQuestion.requestId; params.waitRevision = input.ordinaryQuestion.waitRevision; }
  }
  lockedControl = controlOperationId;
  void perform(action, params);
}
function updateScopes(description) {
  scopes = [];
  const select = byId("scope-choice"); select.replaceChildren();
  for (const receipt of description?.grantReceipts || []) {
    const grant = receipt.body?.grant;
    if (!grant || grant.modelBudget < 1 || !grant.capabilities?.includes("task.submitGoal")) continue;
    for (const binding of grant.resources || []) {
      if (!binding || !["main_screen", "background", "capabilities"].includes(binding.screenMode)) continue;
      scopes.push({grant,binding});
      const option = document.createElement("option"); option.value = String(scopes.length - 1);
      const label = binding.screenMode === "main_screen" ? "Main screen" : binding.screenMode === "background" ? "Private display" : "Phone capabilities";
      option.textContent = label + (binding.packageNames?.length ? " · " + binding.packageNames.join(", ") : "") + " · " + grant.grantId;
      select.append(option);
    }
  }
  if (!scopes.length) { const option = document.createElement("option"); option.value = ""; option.textContent = "No task scope available. Review pairing on the phone."; select.append(option); }
  selectScope();
  refreshInputs();
}
function selectScope() {
  const selected = scopes[Number(byId("scope-choice").value)];
  byId("grant").value = selected?.grant.grantId || "";
  byId("binding").value = selected ? JSON.stringify(selected.binding, null, 2) : "";
  if (selected) { byId("tools").value = Math.min(10, selected.grant.toolBudget); byId("models").value = Math.min(10, selected.grant.modelBudget); }
}
byId("scope-choice").onchange = selectScope;
byId("operation").addEventListener("input", () => {
  inputDrafts.set(selectedOperation, {answer:byId("answer-text").value, steer:byId("steer-text").value, control:byId("control-operation").value, locked:lockedControl});
  selectedOperation = byId("operation").value.trim(); const prior = inputDrafts.get(selectedOperation);
  byId("answer-text").value = prior?.answer || ""; byId("steer-text").value = prior?.steer || "";
  byId("control-operation").value = prior?.control || ""; lockedControl = prior?.locked || null;
  cancelId = null; currentTask = null; refreshInputs();
});
for (const id of ["answer-text", "steer-text"]) byId(id).addEventListener("input", refreshInputs);
byId("acquire-control").onclick = () => writeInput("acquire_control");
byId("answer").onclick = () => writeInput("answer");
byId("steer").onclick = () => writeInput("steer");
byId("control-status").onclick = () => perform("status", {operationId:byId("control-operation").value});
byId("list").onclick = () => perform("list", {});
byId("doctor").onclick = () => perform("doctor", {});
for (const action of ["status","wait","events"]) byId(action).onclick = () => perform(action, {operationId:byId("operation").value.trim(),...(action === "wait" ? {timeoutMs:30000} : {})});
byId("cancel").onclick = () => { if (!byId("operation").value.trim()) return; if (!confirm("Request cancellation of this original phone operation? A request is not proof that it stopped.")) return; cancelId ||= crypto.randomUUID(); perform("cancel", {operationId:byId("operation").value.trim(),cancelOperationId:cancelId}); };
byId("submit").onsubmit = event => {
  event.preventDefault();
  if (busy || !peer) return;
  try {
    if (!byId("grant").value) { byId("notice").textContent = "Check the selected phone’s connection and choose its current scope first."; return; }
    const binding = JSON.parse(byId("binding").value);
    if (!binding || typeof binding !== "object" || Array.isArray(binding)) throw new Error();
    const operationId = crypto.randomUUID(); // Assigned visibly before the first possible network write.
    byId("operation").value = operationId; byId("operation").dispatchEvent(new Event("input")); cancelId = null;
    perform("run", {operationId,grantId:byId("grant").value.trim(),resourceBinding:binding,goal:byId("goal").value,toolCalls:Number(byId("tools").value),modelCalls:Number(byId("models").value),ttlMs:600000,capabilityVersion:byId("version").value.trim()});
  } catch(error) { byId("notice").textContent = "Enter the exact current resource binding as a JSON object."; }
};
call("selection", {}).then(value => { peer = value.peerDeviceId; byId("selection").textContent = "Selected phone · " + peer; }).catch(() => { byId("selection").textContent = "This local session cannot be unlocked. Reopen the exact Desk URL from the CLI."; document.querySelectorAll("button").forEach(button => button.disabled = true); });
