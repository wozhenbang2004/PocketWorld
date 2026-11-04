package dev.pocketengine.runtime.models;

import dev.pocketengine.runtime.usage.ModelPrice;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.pocketengine.runtime.models.ModelFacts.*;

/** Explicit provider schemas. A standard OpenAI list is never treated as a capability catalog. */
public final class CatalogParser {
    private CatalogParser() { }
    public enum Pagination { NONE, AFTER_ID, GEMINI_TOKEN, SAME_ENDPOINT_LINK }
    public record Page(Map<String, ModelFacts> models, Pagination pagination, String cursor,Map<String,ModelPrice> prices,Map<String,ModelEndpointSupport> endpointSupport) {
        public Page(Map<String,ModelFacts> models,Pagination pagination,String cursor){this(models,pagination,cursor,Map.of(),Map.of());}
        public Page(Map<String,ModelFacts> models,Pagination pagination,String cursor,Map<String,ModelPrice> prices){this(models,pagination,cursor,prices,Map.of());}
    }

    public static Page parse(ModelBinding binding, String body, long observedAtMillis, int pageNumber) throws CatalogException {
        if (binding.format() == ModelBinding.Format.NONE || binding.protocol() == ModelBinding.Protocol.BEDROCK_ANTHROPIC) throw new IllegalArgumentException("bedrock_catalog_unavailable");
        if (observedAtMillis < 1 || pageNumber < 1) throw new IllegalArgumentException("invalid_observation_time");
        try {
            JSONTokener tokener = new JSONTokener(body);
            Object root = tokener.nextValue();
            if (!(root instanceof JSONObject object) || tokener.nextClean() != 0 || object.has("error")) throw new IllegalArgumentException();
            String rowsKey = binding.format() == ModelBinding.Format.GEMINI ? "models" : "data";
            JSONArray rows = object.getJSONArray(rowsKey);
            if (rows.length() > 10000) throw new CatalogException(CatalogException.Code.TOO_LARGE);
            Map<String, ModelFacts> models = new LinkedHashMap<>();Map<String,ModelPrice> prices=new LinkedHashMap<>();Map<String,ModelEndpointSupport> endpointSupport=new LinkedHashMap<>();
            for (int n = 0; n < rows.length(); n++) {
                JSONObject row = rows.getJSONObject(n);
                Object rawId = row.get(binding.format() == ModelBinding.Format.GEMINI ? "name" : "id");
                if (!(rawId instanceof String)) throw new IllegalArgumentException();
                String id = ModelBinding.modelId((String) rawId);
                String ref = binding.modelsEndpoint().toASCIIString() + "#page=" + pageNumber + "/" + rowsKey + "/" + n;
                if (models.put(id, facts(binding, row, ref, observedAtMillis)) != null) throw new IllegalArgumentException();
                ModelPrice price=ModelPrice.provider(binding,id,row,ref,observedAtMillis);if(price!=null)prices.put(id,price);
                if(binding.format()==ModelBinding.Format.OPENAI||binding.format()==ModelBinding.Format.ANTHROPIC){ModelEndpointSupport declared=ModelEndpointSupport.parse(row.opt("supported_endpoints"),ref+"/supported_endpoints",observedAtMillis);if(declared!=null)endpointSupport.put(id,declared);}
            }
            Pagination pagination = Pagination.NONE;
            String cursor = "";
            if (binding.format() == ModelBinding.Format.GEMINI) {
                cursor = optionalCursor(object, "nextPageToken");
                if (!cursor.isEmpty()) pagination = Pagination.GEMINI_TOKEN;
            } else {
                if (binding.format() == ModelBinding.Format.ANTHROPIC && !object.has("has_more")) throw new IllegalArgumentException();
                if (object.has("has_more")) {
                    Object more = object.get("has_more");
                    if (!(more instanceof Boolean)) throw new IllegalArgumentException();
                    if ((Boolean) more) { cursor = optionalCursor(object, "last_id"); pagination = Pagination.AFTER_ID; }
                }
                if (binding.format() == ModelBinding.Format.OPENROUTER && object.has("links") && !object.isNull("links")) {
                    String link = optionalCursor(object.getJSONObject("links"), "next");
                    if (!link.isEmpty()) {
                        if (pagination != Pagination.NONE) throw new IllegalArgumentException();
                        cursor = link; pagination = Pagination.SAME_ENDPOINT_LINK;
                    }
                }
            }
            if (pagination != Pagination.NONE && (cursor.isEmpty() || models.isEmpty())) throw new CatalogException(CatalogException.Code.PAGINATION_INCOMPLETE);
            return new Page(Collections.unmodifiableMap(models), pagination, cursor,Collections.unmodifiableMap(prices),Collections.unmodifiableMap(endpointSupport));
        } catch (CatalogException e) { throw e; }
        catch (Exception invalid) { throw new CatalogException(CatalogException.Code.INVALID_CATALOG); }
    }

    private static String optionalCursor(JSONObject object, String key) throws Exception {
        if (!object.has(key) || object.isNull(key)) return "";
        Object raw = object.get(key);
        if (!(raw instanceof String value) || value.length() > 4096 || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        return value;
    }

    private static ModelFacts facts(ModelBinding binding, JSONObject row, String ref, long at) {
        EnumMap<Field, Fact<?>> out = new EnumMap<>(Field.class);
        for (Field field : Field.values()) out.put(field, Fact.unknown(Source.PROVIDER_CATALOG, ref, at, "not_returned_by_catalog"));
        switch (binding.format()) {
            case OPENAI -> { /* id/created/object/owned_by do not establish any of these capabilities. */ }
            case GEMINI -> {
                window(out, row.opt("inputTokenLimit"), WindowKind.INPUT_ONLY, ref + "/inputTokenLimit", at);
                output(out, row.opt("outputTokenLimit"), ref + "/outputTokenLimit", at);
                bool(out, Field.REASONING, row.opt("thinking"), ref + "/thinking", at);
            }
            case ANTHROPIC -> {
                window(out, row.opt("max_input_tokens"), WindowKind.INPUT_ONLY, ref + "/max_input_tokens", at);
                output(out, row.opt("max_tokens"), ref + "/max_tokens", at);
                JSONObject caps = row.optJSONObject("capabilities");
                if (caps != null) {
                    bool(out, Field.IMAGE_INPUT, supported(caps, "image_input"), ref + "/capabilities/image_input/supported", at);
                    bool(out, Field.REASONING, supported(caps, "thinking"), ref + "/capabilities/thinking/supported", at);
                    // server_tools is not client function/tool-call support.
                    JSONObject effort = caps.optJSONObject("effort");
                    List<String> names = effortNames(effort);
                    if (names != null) out.put(Field.REASONING_LEVELS, Fact.known(names, Source.PROVIDER_CATALOG, ref + "/capabilities/effort", at));
                }
            }
            case OPENROUTER -> {
                List<String> parameters = strings(row.optJSONArray("supported_parameters"), 128);
                if ((parameters != null && parameters.isEmpty()) || negativePrice(row)) {
                    for (Field field : Field.values()) out.put(field, Fact.unknown(Source.PROVIDER_CATALOG, ref, at, "catalog_capabilities_unavailable"));
                    break;
                }
                JSONObject top = row.optJSONObject("top_provider");
                if (top != null && top.has("context_length") && !top.isNull("context_length")) {
                    window(out, top.opt("context_length"), WindowKind.INPUT_AND_OUTPUT, ref + "/top_provider/context_length", at);
                } else window(out, row.opt("context_length"), WindowKind.INPUT_AND_OUTPUT, ref + "/context_length", at);
                if (top != null) output(out, top.opt("max_completion_tokens"), ref + "/top_provider/max_completion_tokens", at);
                JSONObject architecture = row.optJSONObject("architecture");
                List<String> inputs = architecture == null ? null : strings(architecture.optJSONArray("input_modalities"), 64);
                if (inputs != null) out.put(Field.IMAGE_INPUT, Fact.known(inputs.contains("image"), Source.PROVIDER_CATALOG, ref + "/architecture/input_modalities", at));
                if (parameters != null) {
                    if (parameters.contains("tools") || !parameters.contains("tool_choice")) out.put(Field.TOOL_CALLS,
                            Fact.known(parameters.contains("tools"), Source.PROVIDER_CATALOG, ref + "/supported_parameters", at));
                    if (parameters.contains("reasoning") || parameters.contains("reasoning_effort")) out.put(Field.REASONING,
                            Fact.known(true, Source.PROVIDER_CATALOG, ref + "/supported_parameters", at));
                    ChatOutputParameter parameter = parameters.contains("max_completion_tokens") ? ChatOutputParameter.MAX_COMPLETION_TOKENS
                            : parameters.contains("max_tokens") ? ChatOutputParameter.MAX_TOKENS : null;
                    if (parameter != null) out.put(Field.CHAT_OUTPUT_PARAMETER, Fact.known(parameter, Source.PROVIDER_CATALOG, ref + "/supported_parameters", at));
                }
                JSONObject reasoning = row.optJSONObject("reasoning");
                List<String> levels = reasoning == null ? null : strings(reasoning.optJSONArray("supported_efforts"), 32);
                if (levels != null) out.put(Field.REASONING_LEVELS, Fact.known(levels, Source.PROVIDER_CATALOG, ref + "/reasoning/supported_efforts", at));
            }
        }
        if (binding.protocol() != ModelBinding.Protocol.OPENAI_CHAT) out.put(Field.CHAT_OUTPUT_PARAMETER,
                Fact.known(ChatOutputParameter.NOT_APPLICABLE, Source.PROVIDER_CATALOG, ref + "/protocol", at));
        return new ModelFacts(out);
    }

    private static Object supported(JSONObject object, String key) {
        JSONObject value = object.optJSONObject(key);
        return value == null ? null : value.opt("supported");
    }

    private static boolean negativePrice(JSONObject row) {
        JSONObject pricing = row.optJSONObject("pricing");
        if (pricing == null) return false;
        for (String key : List.of("prompt", "completion")) {
            Object raw = pricing.opt(key);
            if (raw instanceof String) try { if (Double.parseDouble((String) raw) < 0) return true; } catch (NumberFormatException ignored) { }
        }
        return false;
    }

    private static List<String> effortNames(JSONObject effort) {
        if (effort == null || !(effort.opt("supported") instanceof Boolean supported)) return null;
        if (!supported) return List.of();
        java.util.TreeSet<String> names = new java.util.TreeSet<>();
        java.util.Iterator<String> keys = effort.keys();
        while (keys.hasNext()) {
            String level = keys.next();
            if (level.equals("supported")) continue;
            Object raw = supported(effort, level);
            if (!level.matches("[A-Za-z0-9_-]{1,64}") || !(raw instanceof Boolean)) return null;
            if ((Boolean) raw) names.add(level);
        }
        return names.size() > 32 ? null : List.copyOf(names);
    }

    private static List<String> strings(JSONArray array, int limit) {
        if (array == null || array.length() > limit) return null;
        List<String> names = new ArrayList<>(); Set<String> unique = new HashSet<>();
        for (int n = 0; n < array.length(); n++) {
            Object raw = array.opt(n);
            if (!(raw instanceof String name) || !name.matches("[A-Za-z0-9_-]{1,64}") || !unique.add(name)) return null;
            names.add(name);
        }
        return List.copyOf(names);
    }

    private static void window(Map<Field, Fact<?>> out, Object value, WindowKind kind, String ref, long at) {
        try { out.put(Field.CONTEXT_WINDOW, Fact.known(new TokenWindow(positiveInteger(value), kind), Source.PROVIDER_CATALOG, ref, at)); }
        catch (IllegalArgumentException absent) { invalid(out, Field.CONTEXT_WINDOW, value, ref, at); }
    }

    private static void output(Map<Field, Fact<?>> out, Object value, String ref, long at) {
        try { out.put(Field.OUTPUT_TOKENS, Fact.known(positiveInteger(value), Source.PROVIDER_CATALOG, ref, at)); }
        catch (IllegalArgumentException absent) { invalid(out, Field.OUTPUT_TOKENS, value, ref, at); }
    }

    private static void bool(Map<Field, Fact<?>> out, Field field, Object value, String ref, long at) {
        if (value instanceof Boolean) out.put(field, Fact.known(value, Source.PROVIDER_CATALOG, ref, at));
        else invalid(out, field, value, ref, at);
    }

    private static void invalid(Map<Field, Fact<?>> out, Field field, Object value, String ref, long at) {
        out.put(field, Fact.unknown(Source.PROVIDER_CATALOG, ref, at,
                value == null || value == JSONObject.NULL ? "not_returned_by_catalog" : "invalid_catalog_value"));
    }
}
