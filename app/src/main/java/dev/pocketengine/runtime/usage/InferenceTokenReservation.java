package dev.pocketengine.runtime.usage;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.context.ContextWindow;
import org.json.JSONArray;
import org.json.JSONObject;

/** Content-free local admission estimate. Provider usage remains the charge evidence.
 * Reuses the context window's conservative serialized-text charge; this is neither
 * a tokenizer nor a provider-side token/money cap. */
public final class InferenceTokenReservation {
    public static final long MAX_TOKENS = 1_000_000_000_000L;
    private InferenceTokenReservation() { }

    public static JSONObject prepare(ModelConfig selected, JSONArray messages, JSONArray tools) throws Exception {
        long output = selected.maxOutputTokens;
        if (messages == null || output < 1) return unavailable("request_or_output_reserve_unknown");
        // Reuse canonical UTF-8 accounting in byte-only mode: no invented model
        // context window or tokenizer. Image/output reserves remain explicit.
        ContextWindow.Estimate estimate = ContextWindow.estimate(messages, tools,
                ContextWindow.Budget.localBytes(24L * 1024 * 1024));
        if (estimate.imageCount() > 0 && selected.imageTokenReserve < 1)
            return unavailable("image_token_reserve_unknown");
        long input = Math.addExact(Math.addExact(estimate.estimatedInputTokens(), 1024),
                Math.multiplyExact((long) estimate.imageCount(), selected.imageTokenReserve));
        long reserved = Math.addExact(input, output);
        if (reserved < 1 || reserved > MAX_TOKENS) return unavailable("reservation_out_of_range");
        return new JSONObject().put("format", "pocketworld.inference-token-reserve.v1")
                .put("available", true).put("reservedTokens", reserved)
                .put("inputEstimate", input).put("outputReserve", output).put("framingReserve", 1024)
                .put("imageCount", estimate.imageCount()).put("tokensPerImage", selected.imageTokenReserve)
                .put("source", "native_context_utf8_charge_plus_output_reserve")
                .put("providerHardCap", false).put("actualUsageKnown", false);
    }

    public static JSONObject unavailable(String reason) throws Exception {
        return new JSONObject().put("format", "pocketworld.inference-token-reserve.v1")
                .put("available", false).put("reason", reason).put("reservedTokens", JSONObject.NULL)
                .put("providerHardCap", false).put("actualUsageKnown", false);
    }

    /** Private native binding, captured before the receipt's display fields are redacted.
     * There is deliberately no JSON decoder/encoder: display metadata cannot supply it. */
    public static final class NativeIdentity {
        private final String key;
        private NativeIdentity(String key) { this.key = key; }
        public boolean known() { return !key.isEmpty(); }
        /** Original TaskStore admission/settlement only; never put this value in a public receipt. */
        public String routeKey() { return key; }
        @Override public String toString() { return "NativeBudgetIdentity[private]"; }
    }
    static NativeIdentity unknownIdentity() { return new NativeIdentity(""); }
    public static NativeIdentity captureIdentity(ModelConfig selected) {
        java.util.Objects.requireNonNull(selected);
        String id = selected.usesAccount() ? selected.accountModel.accountId
                : selected.capabilityBinding == null ? "" : selected.capabilityBinding.connectionId();
        return new NativeIdentity(id.matches("[A-Za-z0-9_.:-]{1,200}")
                ? (selected.usesAccount() ? "account:" : "connection:") + id : "");
    }

    /** Historical/display identity only. Redaction of a different field does not erase
     * an intact saved ID. New admission uses NativeIdentity, never this JSON projection. */
    public static String routeKey(JSONObject route) {
        if (route == null) return "";
        String kind = route.optString("kind"); Object value = "account".equals(kind)
                ? route.opt("accountId") : "api_key".equals(kind) ? route.opt("connectionId") : null;
        if (!(value instanceof String id)) return "";
        return id.matches("[A-Za-z0-9_.:-]{1,200}") ? ("account".equals(kind) ? "account:" : "connection:") + id : "";
    }

    /** Final, non-additive counters only. Missing cache/other components are not zero. */
    public static Long reportedTotal(JSONObject usage) throws Exception {
        if (usage == null || usage.optJSONArray("invalidFields") != null && usage.getJSONArray("invalidFields").length() != 0) return null;
        JSONObject counters = usage.optJSONObject("counters"); if (counters == null) return null;
        Long total = count(counters.optJSONObject("TOTAL"));
        if (total != null) return total;
        if (!"anthropic".equals(usage.optString("protocol"))) return null;
        long sum = 0;
        for (String name : new String[]{"INPUT", "OUTPUT", "CACHE_READ", "CACHE_WRITE"}) {
            Long value = count(counters.optJSONObject(name)); if (value == null) return null;
            sum = Math.addExact(sum, value);
        }
        return sum;
    }

    private static Long count(JSONObject value) throws Exception {
        if (value == null || !"provider_reported".equals(value.optString("source")) || !"FINAL".equals(value.optString("stage"))) return null;
        Object raw = value.opt("value");
        if (!(raw instanceof Integer || raw instanceof Long) || ((Number) raw).longValue() < 0) return null;
        return ((Number) raw).longValue();
    }
}
