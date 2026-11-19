"use strict";
// The browser receives only this ephemeral Desk session, never a phone or Companion token.
const token = location.hash.slice(1);
history.replaceState(null, "", "/");
const byId = id => document.getElementById(id);
const mutations = new Set(["run", "cancel", "acquire_control", "answer", "steer", "resume"]);
const controls = new Set(["acquire_control", "answer", "steer", "resume"]);
const settled = new Set(["succeeded", "failed", "cancelled", "interrupted", "expired", "rejected"]);
let peer = null, busy = false, waitPending = false, scopes = [], description = null, currentTask = null;
let selectedOperation = "", selectionVersion = 0, lockedControl = null, submission = null, draftRevision = 0;
let watchVersion = 0, watchTimer = null, watching = false, scopeKind = "phone";
const inputDrafts = new Map(), cancelRecords = new Map(), eventViews = new Map();
const show = value => { byId("result").textContent = JSON.stringify(value, null, 2); };
const unknown = value => value?.status === "unknown" || value?.effectState === "unknown" || value?.phoneStateUnconfirmed === true || value?.currentRead === "unavailable";
const confirmed = value => value && !unknown(value) && (typeof value.status === "string" || value.inputSaved === true);
const writeId = (action, params) => action === "cancel" ? params.cancelOperationId : controls.has(action) ? params.controlOperationId : params.operationId;
const isCurrent = (operation, revision) => operation === byId("operation").value.trim() && revision === selectionVersion;
const scopeFresh = () => description && description.deviceId === peer && description.capturedAt <= Date.now() && description.validUntil > Date.now();
const scopePresent = () => description && description.deviceId === peer && description.capturedAt <= Date.now();
const canonical = value => JSON.stringify(value, function(_key, item) { return item && typeof item === "object" && !Array.isArray(item) ? Object.fromEntries(Object.keys(item).sort().map(key => [key,item[key]])) : item; });
const taskKey = task => JSON.stringify([task.executorDeviceId, task.instanceId, task.taskId, task.runId, task.executorEpoch]);
function notice(text) { byId("notice").textContent = text; }
function markConnection(connected) {
  byId("connection-indicator").dataset.state = connected ? "connected" : "disconnected";
  byId("connection-status").textContent = connected ? "Selected phone connected" : "Selected phone unavailable";
  byId("reconnect").hidden = connected || !selectedOperation;
}
function unknownWrite(action, params) {
  return {operationId:writeId(action, params), targetOperationId:controls.has(action) || action === "cancel" ? params.operationId : undefined,
    status:"unknown", effectState:"unknown", nextAction:"query_original_operation", automaticRetry:false};
}
async function call(action, params) {
  const controller = new AbortController(), timer = setTimeout(() => controller.abort(), 60000);
  try {
    const response = await fetch("/api", {method:"POST", cache:"no-store", credentials:"omit", redirect:"error", signal:controller.signal,
      headers:{"Authorization":"Bearer " + token,"Content-Type":"application/json"}, body:JSON.stringify({action,params})});
    if (!response.ok) {
      const error = new Error(response.status >= 400 && response.status < 500 ? "The phone refused this request. Check its current connection and scope." : "The response is unconfirmed. Read the same operation; no write was retried.");
      error.refused = response.status >= 400 && response.status < 500;
      throw error;
    }
    const raw = await response.text();
    if (raw.length > 65536) throw new Error("The bounded phone response could not be confirmed.");
    const value = JSON.parse(raw);
    if (value.ok !== true || !value.result || typeof value.result !== "object" || Array.isArray(value.result)) throw new Error("The original phone response could not be confirmed.");
    if (value.result.peerDeviceId && value.result.peerDeviceId !== peer && action !== "selection") throw new Error("The response changed the selected phone. No other device was selected.");
    return value.result;
  } finally { clearTimeout(timer); }
}
function rememberDraft() {
  if (selectedOperation) inputDrafts.set(selectedOperation, {answer:byId("answer-text").value, steer:byId("steer-text").value,
    control:byId("control-operation").value, locked:lockedControl});
}
function recordInputResult(action, params, result) {
  const selected = params.operationId === byId("operation").value.trim();
  const draft = selected ? {answer:byId("answer-text").value, steer:byId("steer-text").value, control:byId("control-operation").value, locked:lockedControl} : inputDrafts.get(params.operationId);
  if (!draft) return;
  if (unknown(result)) draft.locked = params.controlOperationId;
  else if (confirmed(result) && draft.locked === params.controlOperationId) draft.locked = null;
  const field = action === "answer" ? "answer" : action === "steer" ? "steer" : null;
  if (result.inputSaved === true && field && draft[field] === params.text) draft[field] = "";
  inputDrafts.set(params.operationId, draft);
  if (selected) { lockedControl = draft.locked; byId("answer-text").value = draft.answer; byId("steer-text").value = draft.steer; }
}
function recordControlRead(operation, result) {
  if (!confirmed(result)) return;
  if (operation === lockedControl) lockedControl = null;
  for (const draft of inputDrafts.values()) if (draft.locked === operation) draft.locked = null;
  if (submission?.operationId === operation && !unknown(result)) {
    submission.confirmed = true;
    byId("submission-state").textContent = result.accepted === false ? "The phone rejected this request. Review its scope before starting a new task." : "Original submission confirmed. Select New task to prepare another goal.";
  }
  for (const record of cancelRecords.values()) if (record.id === operation) record.unknown = false;
}
function acceptSnapshot(result, operation, revision) {
  if (!isCurrent(operation, revision)) return false;
  const task = result.task, outcome = result.phoneGoalOutcome;
  const bound = !!task && !!outcome && result.operationId === operation && outcome.operationId === operation &&
    result.peerDeviceId === peer && outcome.peerDeviceId === peer && task.executorDeviceId === peer &&
    outcome.instanceId === task.instanceId && outcome.taskId === task.taskId && outcome.runId === task.runId &&
    Number.isSafeInteger(task.executorEpoch) && outcome.executorEpoch === task.executorEpoch;
  if ((unknown(result) && !bound) || (!!outcome && !bound)) {
    byId("task-state").textContent = result.currentOperationRead === "confirmed" ? "Dispatch read; task not confirmed" : "Phone result not confirmed";
    byId("current-title").textContent = "Original phone task";
    byId("result-summary").textContent = "No current matching result. Read this original operation again; no input was resent.";
    byId("approval-hint").hidden = true;
    currentTask = null; markConnection(false); refreshInputs();
    return false;
  }
  if (!task || typeof task !== "object") return false;
  if (task.executorDeviceId !== peer || !task.taskId || !task.instanceId || !Number.isSafeInteger(task.revision)) {
    currentTask = null; refreshInputs(); throw new Error("The task snapshot does not belong to the selected phone.");
  }
  const changed = currentTask && taskKey(currentTask) !== taskKey(task);
  if (currentTask && !changed && task.revision < currentTask.revision) return false;
  if (changed) {
    const view = eventViews.get(operation);
    if (view) { view.cursor = task.eventCursor; view.key = taskKey(task); view.sequence = 0; view.entries = []; }
    notice("The original task source changed. Its current state was re-read; no work was replayed.");
  }
  currentTask = task;
  markConnection(true);
  byId("task-state").textContent = task.state.replaceAll("_", " ");
  byId("current-title").textContent = task.result?.title || "Phone task";
  const summary = task.result?.summary ?? task.result?.text ?? task.result?.answer;
  if (bound) {
    const labels = {pending:"The receiving phone must still review this result.", none:"The phone has no reviewed result for this run.",
      not_confirmed:"The result was not confirmed on the receiving phone.", unverified:"The phone could not verify the original result.",
      unavailable:"The receiving phone did not return a verifiable goal result."};
    byId("result-summary").textContent = outcome.review === "confirmed" && task.state === "succeeded" && typeof outcome.summary === "string"
      ? (outcome.summaryWithheld === true ? "The owner confirmed completion and kept the text on the phone." : outcome.summary || "The phone confirmed completion without a text summary.") + (outcome.truncated === true ? " (Partial summary from the phone.)" : "")
      : (labels[outcome.review] || "No verified goal result was returned.");
  } else {
    byId("result-summary").textContent = typeof summary === "string" && summary ? "Task status: " + summary : task.state === "waiting_approval" ? "Your phone is waiting for your review." : "The original task is " + task.state.replaceAll("_", " ") + ".";
  }
  byId("approval-hint").hidden = task.state !== "waiting_approval";
  refreshInputs(); return true;
}
function currentInput() {
  const input = currentTask?.result?.ownerInput;
  return input?.format === "pocketworld.device-owner-input.v1" && input.nativePortPresent === true && input.originalSessionPresent === true
    && input.taskId === currentTask.taskId && input.runId === currentTask.runId && input.executorEpoch === currentTask.executorEpoch ? input : null;
}
function signedMethod(method) {
  const grantId = currentTask?.result?.origin?.grantId;
  // A five-second health description is not the signed grant's lifetime. The
  // phone rechecks its current grant on every write; this is only UI projection.
  return scopePresent() && description.instanceId === currentTask?.instanceId && scopes.some(item => item.grant.grantId === grantId
    && item.grant.expiresAt > Date.now() && item.grant.capabilities?.includes(method));
}
function refreshInputs() {
  const blocked = busy || waitPending;
  const input = currentInput(), question = input?.ordinaryQuestion, lease = currentTask?.result?.controlLease;
  const ordinary = question && question.approval === false && question.takeover === false;
  const leaseValid = !!lease?.controlLeaseId && lease.expiresAt > Date.now();
  const cancel = cancelRecords.get(selectedOperation), fenced = !!cancel;
  byId("input-scope").textContent = input ? "Original task " + input.taskId + " · run " + input.runId : "Read the task and its current phone-approved scope first.";
  byId("ordinary-question").hidden = !ordinary;
  byId("question-text").textContent = ordinary ? question.question : "";
  byId("running-steer").hidden = input?.runningInputState !== "requires_current_write_grant_and_control_lease";
  byId("acquire-control").disabled = blocked || !input || !signedMethod("task.acquireControl") || !!lockedControl || fenced;
  byId("acquire-control").textContent = leaseValid ? "Renew task control" : "Acquire task control";
  byId("answer").disabled = blocked || !ordinary || !leaseValid || !signedMethod("task.answerQuestion") || !!lockedControl || fenced || !byId("answer-text").value.trim();
  byId("steer").disabled = blocked || !input || byId("running-steer").hidden || !leaseValid || !signedMethod("task.steer") || !!lockedControl || fenced || !byId("steer-text").value.trim();
  byId("resume-panel").hidden = !input || !signedMethod("task.requestResume");
  byId("resume").disabled = blocked || !input || !leaseValid || !signedMethod("task.requestResume") || !!lockedControl || fenced || !Number.isSafeInteger(currentTask?.revision);
  byId("control-status").disabled = blocked || !byId("control-operation").value;
  for (const id of ["doctor", "list", "status", "wait", "events", "reconnect"]) byId(id).disabled = blocked || !peer;
  byId("watch").disabled = blocked || !peer || !selectedOperation || watching;
  byId("stop-watch").hidden = !watching; byId("progress").hidden = !watching;
  byId("cancel").disabled = busy || !peer || !selectedOperation || (!cancel && settled.has(currentTask?.state));
  byId("cancel").textContent = cancel ? "Read cancellation" : "Cancel task";
  byId("new-task").disabled = blocked || !peer || !!submission && !submission.confirmed;
  byId("start-task").disabled = blocked || !peer || !scopePresent() || !byId("grant").value || !byId("version").value || !!submission;
}
function goalProfile() {
  return description?.capabilities?.find(item => item.id === "agent.goal" && item.implemented === true && item.installed === true);
}
function submissionScope(grant, binding) {
  const goal = goalProfile(), mode = binding?.screenMode;
  // Pairing and an installed feature prove neither current admission nor access to a specific Android app.
  if (!(grant.modelBudget >= 1 && grant.capabilities.includes("task.submitGoal")) || !goal?.version || goal.busy === true || goal.blocked === true ||
      !Array.isArray(goal.supportedExecutionModes) || !goal.supportedExecutionModes.includes(mode)) return null;
  const admission = goal.ready === true ? "ready" : goal.canSubmitForLocalReview === true ? "owner_review" : null;
  if (!admission) return null;
  const direct = ["main_screen", "background"].includes(mode) && Number.isSafeInteger(binding.androidUserId) && binding.androidUserId >= 0 &&
    Array.isArray(binding.packageNames) && binding.packageNames.length > 0 && binding.packageNames.every(name => typeof name === "string" && !!name);
  return direct || mode === "capabilities" ? {kind:direct ? "phone" : "capability", admission, version:goal.version} : null;
}
function currentChoice() {
  const index = byId("scope-choice").value;
  return index === "" ? null : scopes[Number(index)] || null;
}
function updateScopes(value) {
  const prior = currentChoice(), previousTools = byId("tools").value, previousModels = byId("models").value;
  description = value; scopes = [];
  const select = byId("scope-choice"); select.replaceChildren();
  const pick = document.createElement("option"); pick.value = ""; pick.textContent = scopeKind === "phone" ? "Select an approved phone app scope" : "Select a separate capability scope"; select.append(pick);
  if (scopeFresh()) for (const receipt of description.grantReceipts || []) {
    const grant = receipt.body?.grant;
    if (receipt.body?.targetInstanceId !== description.instanceId || !grant || grant.expiresAt <= Date.now() || !Array.isArray(grant.capabilities)) continue;
    for (const binding of grant.resources || []) {
      if (!binding || !["main_screen", "background", "capabilities"].includes(binding.screenMode)) continue;
      const scope = submissionScope(grant,binding);
      scopes.push({grant,binding,scope}); // Also keep read/control-only grants for the existing task, never as submit options.
      if (!scope || scope.kind !== scopeKind) continue;
      const option = document.createElement("option"); option.value = String(scopes.length - 1);
      const label = binding.screenMode === "main_screen" ? "Main screen" : binding.screenMode === "background" ? "Private display" : "Granted capability";
      option.textContent = scope.kind === "phone" ? `${label} · Android user ${binding.androidUserId} · ${binding.packageNames.join(", ")}` :
        [label, binding.projectId, binding.checkoutId, binding.runtimeId].filter(Boolean).join(" · ");
      select.append(option);
      if (prior && canonical(scopes[scopes.length-1]) === canonical(prior)) select.value = option.value;
    }
  }
  // Never default to a different grant, screen, Android profile or set of apps after refresh.
  selectScope();
  if (currentChoice()) { byId("tools").value = previousTools; byId("models").value = previousModels; }
}
function selectScope() {
  const selected = currentChoice(), scope = selected?.scope, goal = goalProfile();
  byId("grant").value = scope ? selected.grant.grantId : "";
  byId("binding").value = scope ? JSON.stringify(selected.binding, null, 2) : "";
  byId("version").value = scope?.version || "";
  const guidance = !scopePresent() ? "Check the selected phone. Being paired does not authorize a task." :
    !scopeFresh() ? "The last phone capability report has expired. Check the connection again to see current scopes." :
    goal?.busy === true ? "The phone’s executor is busy. No new work can be submitted now." :
    (!goal || goal.blocked === true || (goal.ready !== true && goal.canSubmitForLocalReview !== true)) ? "Connected, but this phone has not reported that it can accept a goal. Review the receiver or model on the phone." :
    !scope ? (byId("scope-choice").children.length > 1 ? "Choose the exact scope before sending." : scopeKind === "phone" ? "No currently granted Android app scope. Review the selected phone; pairing and capability-only access do not authorize an app task." : "No separate capability scope was granted.") :
    scope.kind === "capability" ? "Separate capability work, not an Android app task. Only the original grant applies; this does not share a Windows project." :
    scope.admission === "owner_review" ? "The phone can receive this goal for local owner review; execution is not ready." : "The phone reports it can accept this goal. Acceptance is not execution or a result.";
  byId("scope-guidance").textContent = guidance;
  byId("start-task").textContent = scope?.admission === "owner_review" ? "Send for phone owner review" : "Send to selected phone";
  if (scope) { byId("tools").value = Math.min(10, selected.grant.toolBudget); byId("models").value = Math.min(10, selected.grant.modelBudget); }
  refreshInputs();
}
function renderRecent(value) {
  const list = byId("recent-list"); list.replaceChildren();
  const rows = Array.isArray(value.operations) ? value.operations.filter(row => !row.targetOperationId) : [];
  for (const row of rows.slice(0, 30)) {
    if (row.peerDeviceId !== peer || typeof row.operationId !== "string") continue;
    const button = document.createElement("button"), title = document.createElement("strong"), subtitle = document.createElement("span");
    button.className = "recent-item"; button.setAttribute("aria-current", String(row.operationId === selectedOperation));
    title.textContent = row.status?.replaceAll("_", " ") || "Unconfirmed"; subtitle.textContent = row.operationId;
    button.append(title, subtitle); button.onclick = () => { selectOperation(row.operationId); void perform("status", {operationId:row.operationId}); }; list.append(button);
  }
  if (!list.children.length) { const empty = document.createElement("p"); empty.className = "muted"; empty.textContent = "No phone tasks yet."; list.append(empty); }
}
function selectOperation(operation) {
  rememberDraft(); stopWatching(); selectionVersion++;
  byId("operation").value = operation; selectedOperation = operation; currentTask = null;
  const prior = inputDrafts.get(operation);
  byId("answer-text").value = prior?.answer || ""; byId("steer-text").value = prior?.steer || "";
  byId("control-operation").value = prior?.control || ""; lockedControl = prior?.locked || null;
  byId("task-state").textContent = operation ? "Original operation selected" : "No task selected";
  byId("current-title").textContent = operation ? "Read the phone’s current state." : "Choose a task to follow.";
  byId("result-summary").textContent = operation ? "Only this original operation will be queried." : "Start a task or select an original operation.";
  byId("approval-hint").hidden = true; byId("result").textContent = "No current receipt read.";
  renderEvents(operation); refreshInputs();
}
function viewFor(operation) {
  if (!eventViews.has(operation)) eventViews.set(operation, {cursor:null, key:null, sequence:0, entries:[]});
  return eventViews.get(operation);
}
function renderEvents(operation) {
  const list = byId("events-list"); list.replaceChildren();
  for (const event of eventViews.get(operation)?.entries || []) {
    const item = document.createElement("li"), time = document.createElement("time"), label = document.createElement("p");
    time.textContent = new Date(event.createdAt).toLocaleTimeString();
    const text = event.data?.summary ?? event.data?.message ?? event.data?.text;
    label.textContent = event.kind.replaceAll("_", " ") + (typeof text === "string" ? " · " + text.slice(0, 1500) : "");
    item.append(time, label); list.append(item);
  }
}
async function readEvents(operation, revision) {
  if (!isCurrent(operation, revision) || !currentTask) return;
  const task = currentTask, key = taskKey(task), view = viewFor(operation);
  if (view.key !== null && view.key !== key) { view.cursor = task.eventCursor; view.sequence = 0; view.entries = []; }
  view.key = key;
  const params = {peerDeviceId:peer, operationId:operation, limit:32}; if (view.cursor !== null) params.cursor = view.cursor;
  const page = await call("events", params);
  if (!isCurrent(operation, revision) || !currentTask || taskKey(currentTask) !== key) return;
  if (page.snapshotRequired === true) { view.cursor = task.eventCursor; notice("Older progress was compacted. The current phone snapshot remains available."); return; }
  if (!Array.isArray(page.events) || page.events.length > 32 || typeof page.nextCursor !== "string") throw new Error("The original event page could not be confirmed.");
  for (const event of page.events) {
    if (event.executorDeviceId !== peer || event.instanceId !== task.instanceId || event.taskId !== task.taskId || !Number.isSafeInteger(event.sequence) || event.sequence < 1) throw new Error("A progress event changed its original phone task.");
    if (event.sequence > view.sequence) { view.entries.push(event); view.sequence = event.sequence; }
  }
  view.entries = view.entries.slice(-120);
  if (page.hasMore && page.nextCursor === view.cursor) throw new Error("The original progress cursor did not advance.");
  view.cursor = page.nextCursor; renderEvents(operation); return page;
}
async function perform(action, params) {
  const cancellation = action === "cancel" || action === "status" && [...cancelRecords.values()].some(record => record.id === params.operationId);
  if (busy || waitPending && !cancellation || !peer) return;
  // An observational wait owns only its local read slot. Cancellation and its
  // original receipt read remain available without replaying either operation.
  const waiting = action === "wait";
  if (waiting) waitPending = true; else busy = true;
  const revision = selectionVersion, operation = params.operationId ?? selectedOperation;
  const originalCancellation = cancelRecords.get(operation);
  if (mutations.has(action)) stopWatching();
  refreshInputs(); notice(mutations.has(action) ? "Sending one request to the selected phone…" : "Reading the selected phone…");
  let result;
  try {
    result = await call(action, {peerDeviceId:peer,...params});
    if (waiting && cancelRecords.get(operation) !== originalCancellation) return result;
    if (action === "doctor") { updateScopes(result.description); markConnection(!!scopeFresh()); byId("connection-help").open = !scopeFresh(); }
    if (action === "list") renderRecent(result);
    if (controls.has(action)) recordInputResult(action, params, result);
    if (action === "status") recordControlRead(params.operationId, result);
    if (action === "run" && submission?.operationId === params.operationId) {
      submission.confirmed = confirmed(result);
      byId("submission-state").textContent = submission.confirmed ? "Submitted once. Select New task to prepare another goal." : "Submission unconfirmed. Read operation " + params.operationId + "; this draft cannot be submitted again.";
      if (submission.confirmed) byId("composer").hidden = true;
    }
    if (action === "cancel") cancelRecords.get(params.operationId).unknown = unknown(result);
    const relevant = isCurrent(operation, revision) || revision === selectionVersion && result.targetOperationId === selectedOperation;
    if (relevant || action === "doctor" || action === "list") {
      if (action !== "doctor" && action !== "list") { acceptSnapshot(result, relevant ? selectedOperation : operation, revision); show(result); }
      notice(unknown(result) ? "Outcome unknown. Keep this operation ID and read its status. No write will be resent."
        : result.ownerActionRequired === true ? "Resume requested. Open this original task on the phone and review Resume there. Execution has not restarted."
        : result.inputSaved === true ? "Input saved in the original task. Model consumption is not yet confirmed." : "Original phone response received.");
    }
    if (controls.has(action) && confirmed(result) && isCurrent(operation, revision)) {
      try {
        const latest = await call("status", {peerDeviceId:peer, operationId:operation}); acceptSnapshot(latest, operation, revision);
      } catch (readUnavailable) {
        if (isCurrent(operation, revision)) { currentTask = null; markConnection(false); notice("The control receipt was received; the latest phone state is unavailable. Read the original task without resending input."); }
      }
    }
  } catch (error) {
    if (mutations.has(action)) {
      result = error.refused ? {operationId:writeId(action,params),status:"rejected",effectState:"not_dispatched",accepted:false} : unknownWrite(action,params);
      if (controls.has(action)) recordInputResult(action, params, result);
      if (action === "run" && submission?.operationId === params.operationId) submission.confirmed = error.refused === true;
      if (action === "cancel") cancelRecords.get(params.operationId).unknown = error.refused !== true;
      if (isCurrent(operation, revision)) show(result);
    }
    if (action === "doctor") { updateScopes(null); markConnection(false); byId("connection-help").open = true; }
    if (isCurrent(operation, revision) || action === "doctor" || action === "list") notice(error.message || "The original response is unconfirmed. Read the same operation.");
  } finally { if (waiting) waitPending = false; else busy = false; refreshInputs(); }
  return result;
}
function writeInput(action) {
  const input = currentInput(); if (!input || busy || waitPending || lockedControl || cancelRecords.has(selectedOperation) || byId(action === "acquire_control" ? "acquire-control" : action).disabled) return;
  const controlOperationId = crypto.randomUUID(); byId("control-operation").value = controlOperationId;
  const params = {operationId:selectedOperation, controlOperationId, taskId:input.taskId, runId:input.runId, executorEpoch:input.executorEpoch, allocationId:input.allocationId};
  const lease = currentTask.result.controlLease;
  if (action === "acquire_control") { params.ttlMs = 30000; if (lease?.controlLeaseId) params.previousLeaseId = lease.controlLeaseId; }
  else {
    if (!lease?.controlLeaseId) return;
    params.controlLeaseId = lease.controlLeaseId;
    if (action === "resume") params.expectedRevision = currentTask.revision;
    else params.text = byId(action === "answer" ? "answer-text" : "steer-text").value;
    if (action === "answer") { params.originalRequestId = input.ordinaryQuestion.requestId; params.waitRevision = input.ordinaryQuestion.waitRevision; }
  }
  lockedControl = controlOperationId; void perform(action, params);
}
function stopWatching() {
  watching = false; watchVersion++; if (watchTimer !== null) clearTimeout(watchTimer); watchTimer = null; refreshInputs();
}
function startWatching() {
  if (!peer || !selectedOperation || busy || waitPending) return;
  stopWatching(); watching = true; const version = watchVersion, operation = selectedOperation, revision = selectionVersion;
  refreshInputs();
  async function tick() {
    if (!watching || version !== watchVersion || !isCurrent(operation,revision)) return;
    try {
      const result = await call("status", {peerDeviceId:peer, operationId:operation});
      if (!watching || version !== watchVersion || !isCurrent(operation,revision)) return;
      if (unknown(result)) throw new Error("Connection interrupted. Reconnect will only read this original operation.");
      recordControlRead(operation,result); acceptSnapshot(result,operation,revision); show(result);
      const page = await readEvents(operation,revision);
      if (!watching || version !== watchVersion || !isCurrent(operation,revision)) return;
      if (settled.has(currentTask?.state) && !page?.hasMore) { stopWatching(); notice("Original task is " + currentTask.state + "."); return; }
      notice("Following the original phone task. Pause view stops reading only.");
      watchTimer = setTimeout(tick, page?.hasMore ? 50 : 1000);
    } catch (error) {
      if (version !== watchVersion || !isCurrent(operation,revision)) return;
      stopWatching(); currentTask = null; markConnection(false); notice(error.message); refreshInputs();
    }
  }
  void tick();
}
byId("switch-scope").onclick = () => {
  draftRevision++; scopeKind = scopeKind === "phone" ? "capability" : "phone";
  byId("switch-scope").textContent = scopeKind === "phone" ? "Other granted work" : "Return to phone app work";
  updateScopes(description);
};
byId("scope-choice").onchange = () => { draftRevision++; selectScope(); };
for (const id of ["goal","tools","models"]) byId(id).addEventListener("input", () => { draftRevision++; });
byId("operation").addEventListener("input", () => selectOperation(byId("operation").value.trim()));
for (const id of ["answer-text","steer-text"]) byId(id).addEventListener("input", refreshInputs);
for (const action of ["answer","steer","resume"]) byId(action).onclick = () => writeInput(action);
byId("acquire-control").onclick = () => writeInput("acquire_control");
byId("control-status").onclick = () => perform("status", {operationId:byId("control-operation").value});
byId("list").onclick = () => perform("list", {});
byId("doctor").onclick = () => perform("doctor", {});
for (const action of ["status","wait"]) byId(action).onclick = () => perform(action, {operationId:selectedOperation,...(action === "wait" ? {timeoutMs:30000} : {})});
byId("events").onclick = async () => { try { if (!busy && !waitPending) await readEvents(selectedOperation, selectionVersion); } catch(error) { notice(error.message); } };
byId("watch").onclick = startWatching; byId("stop-watch").onclick = stopWatching;
byId("reconnect").onclick = async () => { const operation=selectedOperation, revision=selectionVersion; await perform("doctor",{}); if (isCurrent(operation,revision) && scopeFresh()) startWatching(); };
byId("cancel").onclick = () => {
  if (busy || !selectedOperation) return;
  const record = cancelRecords.get(selectedOperation);
  if (record) { void perform("status",{operationId:record.id}); return; }
  if (!confirm("Cancel this original phone task? A request does not prove that it has stopped.")) return;
  const id = crypto.randomUUID(); cancelRecords.set(selectedOperation,{id,unknown:true});
  void perform("cancel", {operationId:selectedOperation,cancelOperationId:id});
};
byId("new-task").onclick = () => {
  if (busy || waitPending || !peer || submission && !submission.confirmed) return;
  submission = null; byId("goal").value = ""; byId("submission-state").textContent = ""; byId("composer").hidden = false; refreshInputs(); byId("goal").focus();
};
byId("dismiss-draft").onclick = () => { byId("composer").hidden = true; };
byId("submit").onsubmit = async event => {
  event.preventDefault(); if (busy || waitPending || !peer || submission) return;
  try {
    if (!scopePresent() || !byId("grant").value || !byId("version").value || !byId("goal").value.trim()) { notice("Check the phone’s current scope and enter a goal first."); return; }
    const index = byId("scope-choice").value, selected = index === "" ? null : scopes[Number(index)];
    const tools = Number(byId("tools").value), models = Number(byId("models").value);
    if (!selected || !Number.isSafeInteger(tools) || !Number.isSafeInteger(models) || tools < 0 || models < 1 || tools > selected.grant.toolBudget || models > selected.grant.modelBudget) throw new Error("Use a budget within the phone-approved scope.");
    const goal = byId("goal").value, instance = description.instanceId, version = byId("version").value, revision = draftRevision;
    const selectedScope = canonical(selected);
    busy = true; refreshInputs(); notice("Checking the same phone-approved scope…");
    try {
      const fresh = await call("doctor", {peerDeviceId:peer});
      // User edits invalidate this Start even when the values were changed back.
      if (revision !== draftRevision) { notice("The phone scope or this draft changed. Review the current selection before starting a task."); return; }
      updateScopes(fresh.description);
      const replacement = scopes.findIndex(item => item.scope?.kind === scopeKind && canonical(item) === selectedScope);
      if (!scopeFresh() || description.instanceId !== instance || replacement < 0 || currentChoice() !== scopes[replacement] || version !== byId("version").value
          || selected.grant.expiresAt-Date.now() < 1000 || goal !== byId("goal").value || tools !== Number(byId("tools").value) || models !== Number(byId("models").value)) {
        notice("The phone scope or this draft changed. Review the current selection before starting a task."); return;
      }
      byId("scope-choice").value = String(replacement); selectScope(); byId("tools").value=tools; byId("models").value=models;
    } finally { busy = false; refreshInputs(); }
    const operationId = crypto.randomUUID();
    submission = {operationId,confirmed:false}; selectOperation(operationId);
    byId("submission-state").textContent = "Original operation: " + operationId;
    void perform("run", {operationId,grantId:selected.grant.grantId,resourceBinding:selected.binding,goal,toolCalls:tools,modelCalls:models,ttlMs:Math.min(600000,selected.grant.expiresAt-Date.now()),capabilityVersion:byId("version").value});
  } catch(error) { notice(error.message); }
};
if (!token) {
  byId("connection-status").textContent = "Reopen the private Desk link from the CLI."; byId("connection-help").open = true; refreshInputs();
} else call("selection", {}).then(value => {
  if (!/^pw1_[a-f0-9]{64}$/.test(value.peerDeviceId)) throw new Error("Invalid selected phone");
  peer=value.peerDeviceId; byId("selection").textContent="Fixed phone · …" + peer.slice(-12); byId("phone-identity").textContent=peer; refreshInputs();
}).catch(() => { byId("connection-status").textContent="This local session is unavailable. Reopen the exact Desk URL from the CLI."; byId("connection-help").open=true; refreshInputs(); });
