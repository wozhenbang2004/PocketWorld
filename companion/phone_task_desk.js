"use strict";
const token = location.hash.slice(1);
history.replaceState(null, "", "/");
let peer = null, busy = false, cancelId = null;
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
  try { const result = await call(action, {peerDeviceId:peer,...params}); show(result); byId("notice").textContent = result.status === "unknown" ? "Outcome unknown. Keep this operation ID and read its status." : "Original phone response received."; }
  catch(error) { byId("notice").textContent = error.message; }
  finally { busy = false; document.querySelectorAll("button").forEach(button => button.disabled = false); }
}
byId("operation").addEventListener("input", () => { cancelId = null; });
byId("list").onclick = () => perform("list", {});
byId("doctor").onclick = () => perform("doctor", {});
for (const action of ["status","wait","events"]) byId(action).onclick = () => perform(action, {operationId:byId("operation").value.trim(),...(action === "wait" ? {timeoutMs:30000} : {})});
byId("cancel").onclick = () => { if (!byId("operation").value.trim()) return; if (!confirm("Request cancellation of this original phone operation? A request is not proof that it stopped.")) return; cancelId ||= crypto.randomUUID(); perform("cancel", {operationId:byId("operation").value.trim(),cancelOperationId:cancelId}); };
byId("submit").onsubmit = event => {
  event.preventDefault();
  if (busy || !peer) return;
  try {
    const binding = JSON.parse(byId("binding").value);
    if (!binding || typeof binding !== "object" || Array.isArray(binding)) throw new Error();
    const operationId = crypto.randomUUID(); // Assigned visibly before the first possible network write.
    byId("operation").value = operationId; cancelId = null;
    perform("run", {operationId,grantId:byId("grant").value.trim(),resourceBinding:binding,goal:byId("goal").value,toolCalls:Number(byId("tools").value),modelCalls:Number(byId("models").value),ttlMs:600000,capabilityVersion:byId("version").value.trim()});
  } catch(error) { byId("notice").textContent = "Enter the exact current resource binding as a JSON object."; }
};
call("selection", {}).then(value => { peer = value.peerDeviceId; byId("selection").textContent = "Selected phone · " + peer; }).catch(() => { byId("selection").textContent = "This local session cannot be unlocked. Reopen the exact Desk URL from the CLI."; document.querySelectorAll("button").forEach(button => button.disabled = true); });
