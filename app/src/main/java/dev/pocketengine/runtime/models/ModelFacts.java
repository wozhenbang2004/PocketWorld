package dev.pocketengine.runtime.models;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Capability metadata, not inference success or permission to send private data. */
public final class ModelFacts {
    public enum Field { CONTEXT_WINDOW, OUTPUT_TOKENS, IMAGE_INPUT, TOOL_CALLS, REASONING, REASONING_LEVELS, CHAT_OUTPUT_PARAMETER }
    public enum Source { PROVIDER_CATALOG, OWNER_OVERRIDE, BUILTIN_PROFILE, UNOBSERVED }
    public enum WindowKind { INPUT_AND_OUTPUT, INPUT_ONLY }
    public enum ChatOutputParameter { MAX_TOKENS, MAX_COMPLETION_TOKENS, NOT_APPLICABLE }
    public record TokenWindow(long tokens, WindowKind kind) {
        public TokenWindow { if (tokens < 1 || kind == null) throw new IllegalArgumentException("invalid_token_window"); }
    }

    public static final class Fact<T> {
        public final boolean known;
        public final T value;
        public final Source source;
        public final String sourceRef, reason;
        /** 0 means never observed, not a fabricated fetch time. */
        public final long observedAtMillis;

        private Fact(boolean known, T value, Source source, String sourceRef, long at, String reason) {
            if (at < 0 || sourceRef == null || sourceRef.length() > 3000 || reason == null || reason.length() > 200
                    || known && value == null || !known && value != null) throw new IllegalArgumentException("invalid_fact");
            this.known = known;
            this.value = value;
            this.source = Objects.requireNonNull(source);
            this.sourceRef = sourceRef;
            this.observedAtMillis = at;
            this.reason = reason;
        }

        static <T> Fact<T> known(T value, Source source, String ref, long at) { return new Fact<>(true, value, source, ref, at, ""); }
        static <T> Fact<T> unknown(Source source, String ref, long at, String reason) { return new Fact<>(false, null, source, ref, at, reason); }

        public JSONObject toJson() throws JSONException {
            return new JSONObject().put("known", known).put("value", known ? valueJson(value) : JSONObject.NULL)
                    .put("source", source.name()).put("sourceRef", sourceRef)
                    .put("observedAtMillis", observedAtMillis == 0 ? JSONObject.NULL : observedAtMillis).put("reason", reason);
        }
    }

    /** An explicit owner unknown is an override too; remove it separately to resume catalog values. */
    public static final class OwnerValue {
        final boolean known;
        final Object value;
        final String reason;
        private OwnerValue(boolean known, Object value, String reason) { this.known = known; this.value = value; this.reason = reason; }
        public static OwnerValue known(Object value) { return new OwnerValue(true, Objects.requireNonNull(value), ""); }
        public static OwnerValue unknown() { return new OwnerValue(false, null, "owner_marked_unknown"); }
    }

    private final Map<Field, Fact<?>> fields;
    ModelFacts(Map<Field, Fact<?>> values) {
        EnumMap<Field, Fact<?>> copy = new EnumMap<>(Field.class);
        for (Field field : Field.values()) {
            Fact<?> fact = Objects.requireNonNull(values.get(field), "missing_capability_fact");
            if (fact.known) validate(field, fact.value);
            copy.put(field, fact);
        }
        fields = Collections.unmodifiableMap(copy);
    }

    public Fact<TokenWindow> contextWindow() { return typed(Field.CONTEXT_WINDOW); }
    public Fact<Long> outputTokens() { return typed(Field.OUTPUT_TOKENS); }
    public Fact<Boolean> imageInput() { return typed(Field.IMAGE_INPUT); }
    public Fact<Boolean> toolCalls() { return typed(Field.TOOL_CALLS); }
    public Fact<Boolean> reasoning() { return typed(Field.REASONING); }
    public Fact<List<String>> reasoningLevels() { return typed(Field.REASONING_LEVELS); }
    public Fact<ChatOutputParameter> chatOutputParameter() { return typed(Field.CHAT_OUTPUT_PARAMETER); }
    public Fact<?> fact(Field field) { return fields.get(Objects.requireNonNull(field)); }
    @SuppressWarnings("unchecked") private <T> Fact<T> typed(Field field) { return (Fact<T>) fields.get(field); }

    static ModelFacts unknown(ModelBinding binding) {
        EnumMap<Field, Fact<?>> values = new EnumMap<>(Field.class);
        for (Field field : Field.values()) values.put(field, Fact.unknown(Source.UNOBSERVED,
                binding.modelsEndpoint().toASCIIString(), 0, "not_observed"));
        return new ModelFacts(values);
    }

    ModelFacts withOverrides(Map<Field, Fact<?>> overrides) {
        EnumMap<Field, Fact<?>> copy = new EnumMap<>(Field.class); copy.putAll(fields); copy.putAll(overrides);
        return new ModelFacts(copy);
    }

    ModelFacts expired() {
        EnumMap<Field, Fact<?>> copy = new EnumMap<>(Field.class);
        for (Field field : Field.values()) {
            Fact<?> f = fields.get(field);
            copy.put(field, Fact.unknown(f.source, f.sourceRef, f.observedAtMillis, "catalog_expired"));
        }
        return new ModelFacts(copy);
    }

    static Fact<?> ownerFact(Field field, OwnerValue owner, long revision, long at) {
        if (!owner.known) return Fact.unknown(Source.OWNER_OVERRIDE, "native-owner/revision/" + revision, at, owner.reason);
        Object value = normalize(field, owner.value);
        return Fact.known(value, Source.OWNER_OVERRIDE, "native-owner/revision/" + revision, at);
    }

    private static Object normalize(Field field, Object value) {
        if (field == Field.OUTPUT_TOKENS) value = positiveInteger(value);
        if (field == Field.REASONING_LEVELS && value instanceof List<?>) value = List.copyOf((List<?>) value);
        validate(field, value);
        return value;
    }

    private static void validate(Field field, Object value) {
        boolean valid = switch (field) {
            case CONTEXT_WINDOW -> value instanceof TokenWindow;
            case OUTPUT_TOKENS -> value instanceof Long && (Long) value > 0;
            case IMAGE_INPUT, TOOL_CALLS, REASONING -> value instanceof Boolean;
            case CHAT_OUTPUT_PARAMETER -> value instanceof ChatOutputParameter;
            case REASONING_LEVELS -> validNames(value);
        };
        if (!valid) throw new IllegalArgumentException("invalid_capability_value_" + field.name());
    }

    private static boolean validNames(Object value) {
        if (!(value instanceof List<?> list) || list.size() > 32) return false;
        HashSet<Object> unique = new HashSet<>();
        for (Object item : list) if (!(item instanceof String) || !((String) item).matches("[A-Za-z0-9_-]{1,64}") || !unique.add(item)) return false;
        return true;
    }

    static long positiveInteger(Object value) {
        if (!(value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte)
                || ((Number) value).longValue() < 1) throw new IllegalArgumentException("positive_integer_required");
        return ((Number) value).longValue();
    }

    public JSONObject toJson() throws JSONException { return fieldsJson(fields); }

    static JSONObject fieldsJson(Map<Field, Fact<?>> fields) throws JSONException {
        JSONObject j = new JSONObject();
        for (Map.Entry<Field, Fact<?>> row : fields.entrySet()) {
            Fact<?> f = row.getValue();
            j.put(row.getKey().name(), f.toJson());
        }
        return j;
    }

    private static Object valueJson(Object value) throws JSONException {
        if (value instanceof TokenWindow w) return new JSONObject().put("tokens", w.tokens()).put("kind", w.kind().name());
        if (value instanceof Enum<?> e) return e.name();
        if (value instanceof List<?> list) { JSONArray out = new JSONArray(); for (Object item : list) out.put(item); return out; }
        return value;
    }

    static Map<Field, Fact<?>> readFields(JSONObject j, Source requiredSource) throws JSONException {
        EnumMap<Field, Fact<?>> values = new EnumMap<>(Field.class);
        java.util.Iterator<String> it = j.keys();
        while (it.hasNext()) {
            Field field = Field.valueOf(it.next());
            JSONObject f = j.getJSONObject(field.name());
            Object known = f.get("known");
            if (!(known instanceof Boolean)) throw new IllegalArgumentException("invalid_fact_cache");
            Source source = Source.valueOf(f.getString("source"));
            if (source != requiredSource) throw new IllegalArgumentException("invalid_fact_source");
            long at = f.isNull("observedAtMillis") ? 0 : positiveInteger(f.get("observedAtMillis"));
            Object value = null;
            if ((Boolean) known) {
                Object raw = f.get("value");
                value = switch (field) {
                    case CONTEXT_WINDOW -> new TokenWindow(positiveInteger(((JSONObject) raw).get("tokens")), WindowKind.valueOf(((JSONObject) raw).getString("kind")));
                    case OUTPUT_TOKENS -> positiveInteger(raw);
                    case CHAT_OUTPUT_PARAMETER -> ChatOutputParameter.valueOf((String) raw);
                    case REASONING_LEVELS -> { List<String> names = new ArrayList<>(); JSONArray a = (JSONArray) raw; for (int n = 0; n < a.length(); n++) names.add(a.getString(n)); yield List.copyOf(names); }
                    default -> raw;
                };
                validate(field, value);
            } else if (!f.isNull("value")) throw new IllegalArgumentException("unknown_fact_has_value");
            values.put(field, new Fact<>((Boolean) known, value, source, f.getString("sourceRef"), at, f.getString("reason")));
        }
        return values;
    }
}
