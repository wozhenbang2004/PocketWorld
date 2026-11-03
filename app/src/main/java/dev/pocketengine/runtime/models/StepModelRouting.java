package dev.pocketengine.runtime.models;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.context.CompactionPlan;
import dev.pocketengine.runtime.usage.InferenceFailure;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Optional native scheduling hint over an already owner-selected model group.
 * No task, grant, inference ledger, model discovery, tool execution or retry owner lives here. */
public final class StepModelRouting {
    private StepModelRouting() { }
    private static final Set<String> READS = Set.of("phone_observe", "phone_query", "phone_extract",
            "phone_condition_wait", "browser_task_observe", "browser_task_inspect", "browser_task_wait");
    public record Preference(long revision, String planFingerprint, String candidateId, int minimumSuccesses) {
        public Preference {
            if (revision < 1 || planFingerprint == null || !planFingerprint.matches("[a-f0-9]{64}")
                    || candidateId == null || candidateId.isEmpty() || minimumSuccesses < 2 || minimumSuccesses > 5)
                throw new IllegalArgumentException("invalid_step_routing_preference");
        }
    }
    public interface Source {
        /** Null is disabled; only the native settings owner may issue a preference. */
        Preference preference(ModelConfig original) throws Exception;
        void requireCurrent(Preference expected, ModelConfig original) throws Exception;
        boolean capabilitiesKnown(ModelConfig candidate, boolean images) throws Exception;
    }
    private static volatile Source nativeSource;
    public static void installHostSource(Source source) { nativeSource = java.util.Objects.requireNonNull(source); }

    /** Does not include snapshotId (per-run UUID), credentials, prompt or history. */
    public static String planFingerprint(ModelRoutePlan plan) throws Exception {
        if (plan == null) return "";
        JSONObject value = plan.toJson(); value.remove("snapshotId");
        return CompactionPlan.fingerprint(value);
    }
    public static ModelRoutePlan.Candidate primary(ModelRoutePlan plan) {
        if (plan != null) for (var candidate : plan.candidates)
            if (candidate.config() != null && candidate.exclusion().isEmpty()) return candidate;
        return null;
    }
    public static Session open(ModelConfig original, RunControl control, boolean originalJournal) {
        Source source = nativeSource; Preference preference = null;
        try {
            if (source != null && originalJournal && control != null && control.hostRunIdentity() != null
                    && original != null && original.routePlan != null && original.routePlan.groupRevision > 0
                    && "MAIN".equals(original.routePlan.purpose) && "APP_OWNER".equals(original.routePlan.audience))
                preference = source.preference(original);
        } catch (Exception unavailable) { /* Optional optimization cannot break ordinary model selection. */ }
        return new Session(source, preference, original, control);
    }
    public static boolean knownRead(String tool, CapabilityPort port) {
        if (!READS.contains(tool)) return false;
        if (tool.startsWith("browser_")) return port != null && port.supports(tool) && !port.sideEffecting(tool);
        return port == null || !port.supports(tool) || !port.sideEffecting(tool);
    }
    public static boolean hasOpaqueHistory(JSONArray history) {
        for (int i = 0; i < history.length(); i++) {
            JSONObject row = history.optJSONObject(i);
            if (row != null && row.has("provider_data")) return true;
        }
        return false;
    }
    public static boolean hasImages(JSONArray history) {
        for (int i = 0; i < history.length(); i++) {
            JSONObject row = history.optJSONObject(i); JSONArray content = row == null ? null : row.optJSONArray("content");
            for (int n = 0; content != null && n < content.length(); n++) {
                JSONObject part = content.optJSONObject(n);
                if (part != null && "image_url".equals(part.optString("type"))) return true;
            }
        }
        return false;
    }

    public static final class Session {
        private final Source source;
        private final Preference preference;
        private final ModelConfig original;
        private final RunControl control;
        private final RunControl.HostRunIdentity run;
        private final ModelRoutePlan.Candidate main, light;
        private int successes;
        private String sourceKey = "", reason = "disabled";
        private String sourceTool = "";
        private JSONObject sourceReceipt;
        private CapabilityPort sourcePort;
        private long pause;
        private boolean disabledForRun, selectedLight;

        private Session(Source source, Preference preference, ModelConfig original, RunControl control) {
            this.source = source; this.preference = preference; this.original = original; this.control = control;
            run = control == null ? null : control.hostRunIdentity(); pause = control == null ? 0 : control.pauseRevision();
            main = original == null ? null : primary(original.routePlan);
            ModelRoutePlan.Candidate found = null;
            if (preference != null && main != null) for (var candidate : original.routePlan.candidates)
                if (candidate.candidateId().equals(preference.candidateId()) && candidate != main
                        && candidate.config() != null && candidate.exclusion().isEmpty()) found = candidate;
            light = found;
        }
        public boolean enabled() { return preference != null && main != null && light != null; }
        public boolean selectedLight() { return selectedLight; }
        public void reset(String why) { successes = 0; sourceKey = ""; sourceTool = ""; sourceReceipt = null; reason = why; selectedLight = false; }
        /** New owner input, restoration, pause and failed tool results never inherit successful steps. */
        public void ownerBoundary() { reset("owner_or_source_boundary"); }
        private void current() throws Exception {
            if (nativeSource != source || control == null || run == null || !control.matchesHostRun(run.taskId(), run.runId(), run.epoch()))
                throw new SecurityException("step_routing_original_run_changed");
            control.check();
            if (control.isPaused() || control.pauseRevision() != pause) throw new SecurityException("step_routing_pause_changed");
            source.requireCurrent(preference, original);
            if (sourceKey.startsWith("browser|") && (sourcePort == null || sourceReceipt == null
                    || !sourcePort.stepReadSourceCurrent(sourceTool, new JSONObject(sourceReceipt.toString()), control)))
                throw new SecurityException("step_routing_read_source_changed");
        }
        /** Called again at the existing physical-call admission and before accepting the response. */
        public void requireLightCurrent() throws Exception {
            if (!selectedLight) return;
            try { current(); }
            catch (InterruptedException stopped) { throw stopped; }
            catch (Exception changed) { reset("configuration_changed"); throw new ModelGateway.RequestRefreshRequired(); }
        }
        public ModelConfig choose(JSONArray messages, JSONObject currentPhone, CapabilityPort currentPort) {
            selectedLight = false;
            sourcePort = currentPort;
            if (!enabled()) return original;
            ModelConfig primary = narrowed(main, original.routePlan.maxAttempts);
            if (control.pauseRevision() != pause) { pause = control.pauseRevision(); reset("pause_or_resume"); }
            try { current(); }
            catch (Exception changed) { reset("configuration_or_read_source_changed"); return primary; }
            // The original primary remains exact for risk/unknown steps while this policy is enabled.
            if (disabledForRun) { reason = "light_rejected_for_run"; return primary; }
            if (hasOpaqueHistory(messages)) { reset("opaque_history_preserved_on_main"); return primary; }
            String fresh = phoneSource(currentPhone);
            if (sourceKey.startsWith("phone|") && !fresh.equals(sourceKey)) reset("source_revision_changed");
            if (successes < preference.minimumSuccesses() || sourceKey.isEmpty()) { reason = "insufficient_known_safe_steps"; return primary; }
            try {
                if (!source.capabilitiesKnown(light.config(), hasImages(messages))) { reset("light_capabilities_unknown"); return primary; }
            } catch (Exception unavailable) { reset("light_capabilities_unavailable"); return primary; }
            selectedLight = true; reason = "consecutive_native_read_success";
            // One light physical attempt. Normal main-model recovery remains owned by ModelGateway.
            return narrowed(light, 1);
        }
        private ModelConfig narrowed(ModelRoutePlan.Candidate candidate, int attempts) {
            ModelRoutePlan plan = original.routePlan;
            ModelRoutePlan selected = plan.withNativeStepDispatch(candidate,attempts);
            java.util.Map<String,ModelRoutePlan> purposes=new java.util.LinkedHashMap<>(original.purposePlans);
            purposes.put(plan.purpose,selected);
            return candidate.config().withRoutePlan(selected).withPurposePlans(purposes);
        }
        public ModelConfig useMain(String why) { reset(why); return enabled() ? narrowed(main, original.routePlan.maxAttempts) : original; }
        public void completedRead(String tool, JSONObject receipt, JSONObject phone, boolean nativeReadOnly) {
            if (!enabled()) return;
            if (!nativeReadOnly || !READS.contains(tool) || !successful(receipt)) { reset("risk_failure_or_unknown"); return; }
            String key = tool.startsWith("browser_") ? browserSource(receipt) : phoneSource(receipt);
            if (key.isEmpty() && tool.startsWith("phone_")) key = phoneSource(phone);
            if (key.isEmpty()) { reset("source_identity_unknown"); return; }
            try { sourceReceipt = new JSONObject(receipt.toString()); sourceTool = tool; }
            catch (Exception invalid) { reset("source_receipt_unavailable"); return; }
            if (!key.equals(sourceKey)) { sourceKey = key; successes = 1; }
            else successes = Math.min(5, successes + 1);
        }
        /** A light model may only propose an existing native read. This is a narrowing check, never authorization. */
        public boolean acceptLightResponse(JSONObject response, CapabilityPort port) {
            if (!selectedLight) return true;
            JSONArray calls = response.optJSONArray("tool_calls");
            if (response.has("provider_data") || calls == null || calls.length() == 0 || calls.length() > 3) return false;
            for (int i = 0; i < calls.length(); i++) {
                JSONObject call = calls.optJSONObject(i), function = call == null ? null : call.optJSONObject("function");
                if (function == null || !knownRead(function.optString("name"), port)) return false;
                try { if (call.getString("id").isEmpty()) return false; new JSONObject(function.getString("arguments")); }
                catch (Exception malformed) { return false; }
            }
            return true;
        }
        public void rejectLight() { disabledForRun = true; reset("light_proposal_not_known_read"); }
        /** Unknown/partial/cancelled calls stay on the existing recovery path, never silently replayed. */
        public boolean safeMainAfterFailure(Exception failure) {
            if (!selectedLight || disabledForRun || control.isCancelled()) return false;
            if (!(failure instanceof ModelGateway.GatewayException gateway)) return false;
            InferenceFailure fact = gateway.observation();
            if (fact == null || fact.partialTextReceived) return false;
            boolean safe = fact.kind == InferenceFailure.Kind.LOCAL_REFUSAL || fact.kind == InferenceFailure.Kind.CONTEXT_LIMIT
                    || fact.kind == InferenceFailure.Kind.HTTP && Set.of(400, 404, 422, 500, 502, 503, 504).contains(fact.httpStatus);
            if (safe) { disabledForRun = true; reset("light_known_failure_main_next"); }
            return safe;
        }
        public JSONObject diagnostics() throws Exception {
            return new JSONObject().put("format", "pocketworld.step-model-routing.v1").put("enabled", enabled())
                    .put("selected", selectedLight ? "light" : "main").put("reason", reason)
                    .put("continuousSafeReads", successes).put("preferenceRevision", preference == null ? 0 : preference.revision())
                    .put("executionAuthority", false).put("usageRefunded", false).put("toolReplay", false);
        }
    }
    private static boolean successful(JSONObject receipt) {
        if (receipt == null || !Boolean.TRUE.equals(receipt.opt("ok"))
                || receipt.has("accepted") && !Boolean.TRUE.equals(receipt.opt("accepted"))) return false;
        for (String key : List.of("requiresPause", "requiresReconciliation", "reconciliationRequired", "requiresOwnerReturn"))
            if (receipt.optBoolean(key)) return false;
        if (receipt.has("error") && !receipt.isNull("error") && !receipt.optString("error").isEmpty()) return false;
        String state = receipt.optString("status", receipt.optString("state"));
        return state.matches("|completed|success|ok|observed|read|receipt") && !receipt.optString("code").contains("unknown");
    }
    private static JSONObject observation(JSONObject value) {
        if (value == null) return null;
        JSONObject nested = value.optJSONObject("observation"); return nested == null ? value : nested;
    }
    private static String phoneSource(JSONObject value) {
        JSONObject row = observation(value);
        if (row == null || row.optString("packageName").isEmpty() || row.optString("sessionId").isEmpty()
                || row.optString("treeFingerprint").isEmpty() || row.optInt("displayId", -1) < 0 || row.optLong("pauseRevision", -1) < 0) return "";
        return "phone|" + row.optString("sessionId") + "|" + row.optInt("displayId") + "|" + row.optLong("pauseRevision")
                + "|" + row.optString("packageName") + "|" + row.optString("treeFingerprint");
    }
    private static String browserSource(JSONObject value) {
        JSONObject row = observation(value);
        if (row == null || row.optString("browserSessionId").isEmpty() || row.optString("tabId").isEmpty()
                || row.optString("pageIdentityDigest").isEmpty() || row.optLong("tabGeneration", -1) < 0
                || row.optLong("navigation", -1) < 0 || row.optString("documentId").isEmpty()) return "";
        return "browser|" + row.optString("browserSessionId") + "|" + row.optString("tabId") + "|" + row.optLong("tabGeneration")
                + "|" + row.optLong("navigation") + "|" + row.optString("pageIdentityDigest") + "|" + row.optString("documentId");
    }
}
