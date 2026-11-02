"use strict";
const token = location.hash.slice(1);
history.replaceState(null, "", "/");
let peer = null, busy = false, cancelId = null, scopes = [];
const byId = id => document.getElementById(id);
const show = value => { byId("result").textContent = JSON.stringify(value, null, 2); };
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
  try { const result = await call(action, {peerDeviceId:peer,...params}); if (action === "doctor") updateScopes(result.description); show(result); byId("notice").textContent = result.status === "unknown" ? "Outcome unknown. Keep this operation ID and read its status." : "Original phone response received."; }
  catch(error) { byId("notice").textContent = error.message; }
  finally { busy = false; document.querySelectorAll("button").forEach(button => button.disabled = false); }
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
}
function selectScope() {
  const selected = scopes[Number(byId("scope-choice").value)];
  byId("grant").value = selected?.grant.grantId || "";
  byId("binding").value = selected ? JSON.stringify(selected.binding, null, 2) : "";
  if (selected) { byId("tools").value = Math.min(10, selected.grant.toolBudget); byId("models").value = Math.min(10, selected.grant.modelBudget); }
}
byId("scope-choice").onchange = selectScope;
byId("operation").addEventListener("input", () => { cancelId = null; });
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
    byId("operation").value = operationId; cancelId = null;
    perform("run", {operationId,grantId:byId("grant").value.trim(),resourceBinding:binding,goal:byId("goal").value,toolCalls:Number(byId("tools").value),modelCalls:Number(byId("models").value),ttlMs:600000,capabilityVersion:byId("version").value.trim()});
  } catch(error) { byId("notice").textContent = "Enter the exact current resource binding as a JSON object."; }
};
call("selection", {}).then(value => { peer = value.peerDeviceId; byId("selection").textContent = "Selected phone · " + peer; }).catch(() => { byId("selection").textContent = "This local session cannot be unlocked. Reopen the exact Desk URL from the CLI."; document.querySelectorAll("button").forEach(button => button.disabled = true); });
