package dev.pocketengine.runtime.context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure request projection. It neither changes durable history nor authorizes tool dispatch. */
public final class ContextWindow {
    private ContextWindow() { }

    public enum Eviction { STALE_OBSERVATION, COMPLETED_TOOL_BATCH, OLD_ASSISTANT_TEXT }

    /** All values come from the selected host route, never from model output or a page. */
    public static final class Budget {
        public final long contextTokens, outputReserve, safetyReserve, requestOverheadTokens;
        public final long maxCanonicalBytes, tokensPerImage;
        public final boolean tokenWindowKnown;

        public Budget(long contextTokens, long outputReserve, long safetyReserve,
                      long maxCanonicalBytes, long requestOverheadTokens, long tokensPerImage) {
            if (contextTokens < 1 || outputReserve < 1 || safetyReserve < 0
                    || outputReserve >= contextTokens || safetyReserve >= contextTokens - outputReserve
                    || maxCanonicalBytes < 1 || requestOverheadTokens < 0 || tokensPerImage < 0) {
                throw new IllegalArgumentException("Invalid host context budget");
            }
            this.tokenWindowKnown = true;
            this.contextTokens = contextTokens;
            this.outputReserve = outputReserve;
            this.safetyReserve = safetyReserve;
            this.maxCanonicalBytes = maxCanonicalBytes;
            this.requestOverheadTokens = requestOverheadTokens;
            this.tokensPerImage = tokensPerImage;
        }

        private Budget(long maxCanonicalBytes) {
            if (maxCanonicalBytes < 1) throw new IllegalArgumentException("Invalid local byte limit");
            this.tokenWindowKnown = false;
            this.contextTokens = this.outputReserve = this.safetyReserve = 0;
            this.requestOverheadTokens = this.tokensPerImage = 0;
            this.maxCanonicalBytes = maxCanonicalBytes;
        }

        /** A local allocation/serialization limit, never a guessed model token window. */
        public static Budget localBytes(long maxCanonicalBytes) { return new Budget(maxCanonicalBytes); }

        /** Zero explicitly means unknown in byte-only mode, not an unlimited token window. */
        public long inputLimit() { return tokenWindowKnown ? contextTokens - outputReserve - safetyReserve : 0; }

        public Budget withByteCeiling(long ceiling) {
            return tokenWindowKnown ? new Budget(contextTokens, outputReserve, safetyReserve,
                    Math.min(maxCanonicalBytes, ceiling), requestOverheadTokens, tokensPerImage)
                    : localBytes(Math.min(maxCanonicalBytes, ceiling));
        }
    }

    /** Indexes refer to this exact request array, after reference refresh/insertion. */
    public static final class Retention {
        private final Map<Integer, Eviction> evictable;
        private final Set<Integer> pinned;

        public Retention(Map<Integer, Eviction> evictable, Set<Integer> pinned) {
            this.evictable = Map.copyOf(Objects.requireNonNull(evictable));
            this.pinned = Set.copyOf(Objects.requireNonNull(pinned));
            for (Integer index : this.evictable.keySet()) requireIndex(index);
            for (Integer index : this.pinned) requireIndex(index);
        }

        public static Retention preserveAll() { return new Retention(Map.of(), Set.of()); }

        private static void requireIndex(Integer value) {
            if (value == null || value < 0) throw new IllegalArgumentException("Invalid retention index");
        }
    }

    public record Estimate(long canonicalUtf8Bytes, long estimatedInputTokens, int imageCount) { }
    public record Removed(int index, Eviction reason) { }
    public record Protected(int startIndex, String reason) { }

    public static final class Plan {
        public final String status;
        public final Estimate before, after;
        public final List<Removed> removed;
        public final List<Protected> protectedUnits;
        private final String messages;
        private final Budget budget;

        private Plan(String status, Estimate before, Estimate after, List<Removed> removed,
                     List<Protected> protectedUnits, String messages, Budget budget) {
            this.status = status;
            this.before = before;
            this.after = after;
            this.removed = List.copyOf(removed);
            this.protectedUnits = List.copyOf(protectedUnits);
            this.messages = messages;
            this.budget = budget;
        }

        public boolean canSend() { return status.equals("ready"); }

        /** A failed projection never exposes a sendable oversized/partial request. */
        public JSONArray messages() throws JSONException {
            if (!canSend()) throw new IllegalStateException(status);
            return new JSONArray(messages);
        }

        /** Body-free diagnostics for the existing host log; no new usage or task authority. */
        public JSONObject diagnostics() throws JSONException {
            JSONArray drops = new JSONArray(), pins = new JSONArray();
            for (Removed row : removed) drops.put(new JSONObject().put("index", row.index())
                    .put("reason", row.reason().name().toLowerCase(Locale.ROOT)));
            for (Protected row : protectedUnits) pins.put(new JSONObject().put("startIndex", row.startIndex())
                    .put("reason", row.reason()));
            return new JSONObject().put("status", status).put("before", estimateJson(before))
                    .put("after", estimateJson(after)).put("removed", drops).put("protected", pins)
                    .put("budgetKind", budget.tokenWindowKnown ? "host_token_and_byte" : "local_byte_safety_only")
                    .put("modelContextTokens", budget.tokenWindowKnown ? budget.contextTokens : JSONObject.NULL)
                    .put("inputLimitTokens", budget.tokenWindowKnown ? budget.inputLimit() : JSONObject.NULL)
                    .put("outputReserveTokens", budget.tokenWindowKnown ? budget.outputReserve : JSONObject.NULL)
                    .put("maxCanonicalBytes", budget.maxCanonicalBytes)
                    .put("modelWindowKnown", budget.tokenWindowKnown)
                    .put("estimateMethod", "serialized_utf8_byte_charge_plus_host_image_reserve")
                    .put("providerReportedInputTokens", JSONObject.NULL).put("providerUsageUsed", false)
                    .put("tokenizerExact", false).put("automaticReplay", false);
        }

        private Object estimateJson(Estimate estimate) throws JSONException {
            return estimate == null ? JSONObject.NULL : new JSONObject()
                    .put("canonicalUtf8Bytes", estimate.canonicalUtf8Bytes())
                    .put("estimatedInputTokens", budget.tokenWindowKnown && (estimate.imageCount() == 0 || budget.tokensPerImage > 0)
                            ? estimate.estimatedInputTokens() : JSONObject.NULL)
                    .put("serializedTextByteCharge", !budget.tokenWindowKnown ? estimate.estimatedInputTokens() : JSONObject.NULL)
                    .put("imageCount", estimate.imageCount());
        }
    }

    /**
     * Remove only explicitly dispensable units, oldest first. Calls and all their replies
     * form one unit. No message body, call arguments, receipt or native provider data is rewritten.
     */
    public static Plan plan(JSONArray history, JSONArray tools, Budget budget, Retention retention) {
        Objects.requireNonNull(budget);
        Objects.requireNonNull(retention);
        final List<Unit> units;
        try {
            units = units(history);
            validateRetention(history, units, retention);
        } catch (InvalidHistory invalid) {
            return failed("invalid_history", budget);
        } catch (InvalidRetention invalid) {
            return failed("invalid_retention", budget);
        }
        Totals totals = new Totals(history, tools, budget);
        Estimate before = totals.estimate();
        List<Removed> removed = new ArrayList<>();
        List<Protected> protectedUnits = new ArrayList<>();
        Set<Integer> omitted = new HashSet<>();
        int latestObservation = -1;
        for (Unit unit : units) {
            JSONObject row = (JSONObject) history.opt(unit.start);
            if (row.optString("role").equals("user") && row.opt("content") instanceof JSONArray) latestObservation = unit.start;
        }
        for (int n = 0; n < units.size(); n++) {
            Unit unit = units.get(n);
            String protection = protection(history, unit, n == units.size() - 1, latestObservation, retention);
            if (protection != null) protectedUnits.add(new Protected(unit.start, protection));
            Eviction reason = retention.evictable.get(unit.start);
            if (fits(totals.estimate(), budget) || protection != null || reason == null) continue;
            for (int index = unit.start; index < unit.end; index++) {
                totals.remove((JSONObject) history.opt(index));
                omitted.add(index);
                removed.add(new Removed(index, reason));
            }
        }
        Estimate after = totals.estimate();
        if (budget.tokenWindowKnown && after.imageCount() > 0 && budget.tokensPerImage == 0) {
            return new Plan("image_budget_unknown", before, after, removed, protectedUnits, null, budget);
        }
        if (!fits(after, budget)) {
            return new Plan("budget_exceeded", before, after, removed, protectedUnits, null, budget);
        }
        JSONArray selected = new JSONArray();
        for (int index = 0; index < history.length(); index++) {
            if (!omitted.contains(index)) selected.put(history.opt(index));
        }
        return new Plan("ready", before, after, removed, protectedUnits, selected.toString(), budget);
    }

    /** Estimate only; the caller must still use plan() to validate protocol and retention. */
    public static Estimate estimate(JSONArray history, JSONArray tools, Budget budget) {
        return new Totals(Objects.requireNonNull(history), tools, Objects.requireNonNull(budget)).estimate();
    }

    private static boolean fits(Estimate estimate, Budget budget) {
        return estimate.canonicalUtf8Bytes() <= budget.maxCanonicalBytes
                && (!budget.tokenWindowKnown || estimate.estimatedInputTokens() <= budget.inputLimit())
                && (!budget.tokenWindowKnown || estimate.imageCount() == 0 || budget.tokensPerImage > 0);
    }

    private static Plan failed(String status, Budget budget) {
        return new Plan(status, null, null, List.of(), List.of(), null, budget);
    }

    private static List<Unit> units(JSONArray history) throws InvalidHistory {
        if (history == null || history.length() == 0) throw new InvalidHistory();
        List<Unit> result = new ArrayList<>();
        Set<String> usedIds = new HashSet<>();
        for (int index = 0; index < history.length();) {
            JSONObject message = history.optJSONObject(index);
            if (message == null) throw new InvalidHistory();
            String role = message.optString("role");
            if (!Set.of("system", "developer", "user", "assistant").contains(role)) throw new InvalidHistory();
            JSONArray calls = message.optJSONArray("tool_calls");
            if (message.has("tool_calls") && (!role.equals("assistant") || calls == null || calls.length() == 0)) {
                throw new InvalidHistory();
            }
            if (calls == null) {
                if (!(message.opt("content") instanceof String) && !(message.opt("content") instanceof JSONArray)) {
                    throw new InvalidHistory();
                }
                result.add(new Unit(index, index + 1, false));
                index++;
                continue;
            }
            Set<String> pending = new HashSet<>();
            for (int c = 0; c < calls.length(); c++) {
                JSONObject call = calls.optJSONObject(c);
                JSONObject function = call == null ? null : call.optJSONObject("function");
                String id = call == null ? "" : call.optString("id");
                if (id.isEmpty() || !usedIds.add(id) || function == null || function.optString("name").isEmpty()
                        || !(function.opt("arguments") instanceof String)) throw new InvalidHistory();
                pending.add(id);
            }
            int start = index++;
            boolean uncertain = false;
            while (!pending.isEmpty()) {
                JSONObject receipt = history.optJSONObject(index++);
                if (receipt == null || !receipt.optString("role").equals("tool")
                        || !pending.remove(receipt.optString("tool_call_id"))
                        || !(receipt.opt("content") instanceof String)) throw new InvalidHistory();
                uncertain |= uncertain(receipt.optString("content"));
            }
            result.add(new Unit(start, index, uncertain));
        }
        return result;
    }

    private static void validateRetention(JSONArray history, List<Unit> units, Retention retention)
            throws InvalidRetention {
        Map<Integer, Unit> starts = new LinkedHashMap<>();
        for (Unit unit : units) starts.put(unit.start, unit);
        for (Integer pinned : retention.pinned) if (pinned >= history.length()) throw new InvalidRetention();
        for (Map.Entry<Integer, Eviction> entry : retention.evictable.entrySet()) {
            Unit unit = starts.get(entry.getKey());
            if (unit == null) throw new InvalidRetention();
            JSONObject row = (JSONObject) history.opt(unit.start);
            boolean allowed = switch (entry.getValue()) {
                case STALE_OBSERVATION -> row.optString("role").equals("user")
                        && row.opt("content") instanceof JSONArray && unit.end == unit.start + 1;
                case COMPLETED_TOOL_BATCH -> row.optString("role").equals("assistant")
                        && row.optJSONArray("tool_calls") != null;
                case OLD_ASSISTANT_TEXT -> row.optString("role").equals("assistant")
                        && !row.has("tool_calls");
            };
            if (!allowed) throw new InvalidRetention();
        }
    }

    private static String protection(JSONArray history, Unit unit, boolean last, int latestObservation, Retention retention) {
        String role = ((JSONObject) history.opt(unit.start)).optString("role");
        if (role.equals("system") || role.equals("developer")) return "instruction";
        for (int index = unit.start; index < unit.end; index++) {
            if (retention.pinned.contains(index)) return "host_pin";
        }
        if (unit.uncertain) return "unresolved_or_unclassified_receipt";
        if (unit.start == latestObservation) return "latest_observation";
        if (last) return "latest_unit";
        if (role.equals("user") && retention.evictable.get(unit.start) != Eviction.STALE_OBSERVATION) {
            return "user_or_recovery_requirement";
        }
        return null;
    }

    private static boolean uncertain(String body) {
        try {
            JSONObject receipt = new JSONObject(body);
            String code = receipt.optString("code", receipt.optString("error")).toLowerCase(Locale.ROOT);
            String state = receipt.optString("state").toLowerCase(Locale.ROOT);
            String status = receipt.optString("status").toLowerCase(Locale.ROOT);
            return code.contains("unknown") || code.equals("action_timeout") || code.equals("not_replayed")
                    || state.contains("unknown") || state.equals("prepared") || status.contains("unknown")
                    || status.equals("prepared") || receipt.has("accepted") && receipt.isNull("accepted")
                    || receipt.optBoolean("requiresReconciliation") || receipt.has("unresolvedOperationId");
        } catch (Exception unclassified) {
            return true;
        }
    }

    private record Unit(int start, int end, boolean uncertain) { }
    private static final class InvalidHistory extends Exception { }
    private static final class InvalidRetention extends Exception { }

    /** Charge serialized text at one token per UTF-8 byte, rather than claiming chars/4 is exact. */
    private static final class Totals {
        private final Budget budget;
        private final long toolBytes;
        private long messageBytes, textCharge;
        private int count, images;

        Totals(JSONArray history, JSONArray tools, Budget budget) {
            this.budget = budget;
            this.toolBytes = utf8(tools == null ? "[]" : tools.toString());
            for (int index = 0; index < history.length(); index++) {
                JSONObject row = history.optJSONObject(index);
                if (row == null) throw new IllegalArgumentException("Canonical message must be an object");
                add(row, 1);
            }
        }

        void remove(JSONObject row) { add(row, -1); }

        private void add(JSONObject row, int sign) {
            long bytes = utf8(row.toString());
            ImagePrice price = imagePrice(row);
            messageBytes += sign * bytes;
            textCharge += sign * (bytes - price.urlBytes);
            images += sign * price.count;
            count += sign;
        }

        Estimate estimate() {
            // Canonical {messages:[...],tools:[...]}, not a claim about the native provider wire size.
            long framing = utf8("{\"messages\":[],\"tools\":}") + Math.max(0, count - 1);
            long canonical = framing + toolBytes + messageBytes;
            long estimate = saturatedAdd(framing + toolBytes + textCharge, budget.requestOverheadTokens);
            estimate = saturatedAdd(estimate, saturatedMultiply(images, budget.tokensPerImage));
            return new Estimate(canonical, estimate, images);
        }
    }

    private record ImagePrice(int count, long urlBytes) { }

    private static ImagePrice imagePrice(JSONObject row) {
        JSONArray parts = row.optJSONArray("content");
        int count = 0;
        long urlBytes = 0;
        if (parts != null) for (int p = 0; p < parts.length(); p++) {
            JSONObject part = parts.optJSONObject(p);
            JSONObject image = part == null ? null : part.optJSONObject("image_url");
            if (part != null && part.optString("type").equals("image_url") && image != null
                    && image.opt("url") instanceof String) {
                count++;
                // Keep the empty JSON string's two quote bytes in text framing.
                urlBytes += utf8(JSONObject.quote(image.optString("url"))) - 2;
            }
        }
        return new ImagePrice(count, urlBytes);
    }

    private static long utf8(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }

    private static long saturatedAdd(long a, long b) { return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b; }

    private static long saturatedMultiply(long a, long b) {
        return a != 0 && b > Long.MAX_VALUE / a ? Long.MAX_VALUE : a * b;
    }
}
