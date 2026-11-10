package dev.pocketengine.runtime.models;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static dev.pocketengine.runtime.models.ModelFacts.*;

/**
 * Built-in, versioned model-family hints for the API protocols PocketWorld actually
 * serializes. These are not /models observations, route probes, or evidence that an
 * arbitrary compatible gateway accepts the same parameters.
 *
 * Priority: exact owner override > explicit provider catalog > local family hint
 * > unknown. A new model name must not inherit capabilities from a loose prefix.
 */
final class ModelReasoningProfiles {
    private ModelReasoningProfiles() { }

    private static final List<String> EXTENDED = List.of("low", "medium", "high", "xhigh", "max");
    private static final List<String> WITH_NONE = List.of("none", "low", "medium", "high", "xhigh", "max");
    private static final List<String> CLAUDE_STANDARD = List.of("low", "medium", "high", "max");
    private static final List<String> GEMINI_LEVELS = List.of("minimal", "low", "medium", "high");
    private static final List<String> THREE_LEVELS = List.of("low", "medium", "high");
    private static final Map<String, List<String>> OPENAI = Map.of(
            "gpt-6-astra", EXTENDED,
            "gpt-6.1-sol", EXTENDED,
            "gpt-6.1-terra", WITH_NONE,
            "gpt-6-sol", WITH_NONE,
            "gpt-6-luna", WITH_NONE,
            "gpt-5.5", List.of("none", "low", "medium", "high", "xhigh"));
    private static final Map<String, List<String>> ANTHROPIC = Map.ofEntries(
            Map.entry("claude-sonnet-4-6", CLAUDE_STANDARD),
            Map.entry("claude-opus-4-6", CLAUDE_STANDARD),
            Map.entry("claude-opus-4-7", EXTENDED),
            Map.entry("claude-opus-4-8", EXTENDED),
            Map.entry("claude-sonnet-5", EXTENDED),
            Map.entry("claude-opus-5", EXTENDED),
            Map.entry("claude-sonnet-5-5", EXTENDED),
            Map.entry("claude-opus-5-5", EXTENDED),
            Map.entry("claude-haiku-5-5", EXTENDED));
    private static final Map<String, List<String>> GEMINI = Map.ofEntries(
            Map.entry("gemini-2.5-pro", THREE_LEVELS),
            Map.entry("gemini-2.5-flash", THREE_LEVELS),
            Map.entry("gemini-2.5-flash-lite", THREE_LEVELS),
            Map.entry("gemini-3-pro-preview", List.of("low", "high")),
            Map.entry("gemini-3-flash-preview", GEMINI_LEVELS),
            Map.entry("gemini-3.1-pro-preview", THREE_LEVELS),
            Map.entry("gemini-3.1-flash-lite-image", List.of("minimal", "high")),
            Map.entry("gemini-3.5-flash", GEMINI_LEVELS),
            Map.entry("gemini-3.5-flash-lite", GEMINI_LEVELS),
            Map.entry("gemini-3.6-flash", GEMINI_LEVELS),
            Map.entry("gemini-3.7-flash", THREE_LEVELS),
            Map.entry("gemini-3.8-flash", THREE_LEVELS));

    private static String modelId(String id) {
        return id.startsWith("models/") ? id.substring("models/".length()) : id;
    }

    /** Exact IDs or explicitly dated snapshots only. No `startsWith("gpt")` guessing. */
    private static List<String> levels(Map<String, List<String>> catalog, String rawId) {
        String id = modelId(rawId);
        List<String> direct = catalog.get(id);
        if (direct != null) return direct;
        for (Map.Entry<String, List<String>> entry : catalog.entrySet()) {
            String prefix = entry.getKey();
            if (id.startsWith(prefix + "-")) {
                String date = id.substring(prefix.length() + 1);
                if (date.matches("(?:[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{8})"))
                    return entry.getValue();
            }
        }
        return List.of();
    }

    static boolean usesTokenBudget(String model) {
        return !levels(Map.of("gemini-2.5-pro", THREE_LEVELS,
                "gemini-2.5-flash", THREE_LEVELS,
                "gemini-2.5-flash-lite", THREE_LEVELS), model).isEmpty();
    }

    static ModelFacts withFamilyDefaults(ModelBinding binding, String id, ModelFacts observed) {
        List<String> choices = switch (binding.protocol()) {
            case OPENAI_CHAT, OPENAI_RESPONSES -> levels(OPENAI, id);
            case ANTHROPIC -> levels(ANTHROPIC, id);
            case GEMINI -> levels(GEMINI, id);
            default -> List.of();
        };
        if (choices.isEmpty() || observed.reasoning().known && !observed.reasoning().value) return observed;
        EnumMap<Field, Fact<?>> inferred = new EnumMap<>(Field.class);
        String source = "builtin:model-reasoning:v1/" + binding.protocol().name() + "/" + modelId(id);
        if (!observed.reasoning().known)
            inferred.put(Field.REASONING, Fact.known(true, Source.BUILTIN_PROFILE, source, 0));
        if (!observed.reasoningLevels().known)
            inferred.put(Field.REASONING_LEVELS, Fact.known(choices, Source.BUILTIN_PROFILE, source, 0));
        return inferred.isEmpty() ? observed : observed.withOverrides(inferred);
    }
}
