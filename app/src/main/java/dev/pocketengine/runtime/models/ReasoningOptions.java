package dev.pocketengine.runtime.models;

import java.util.Objects;
import org.json.JSONObject;

/** Native owner request settings. DEFAULT omits the field; it does not claim thinking is off. */
public record ReasoningOptions(Mode mode, String level, int budgetTokens) {
    public enum Mode { DEFAULT, OPENAI_EFFORT, OPENAI_SWITCH, ANTHROPIC_ADAPTIVE, ANTHROPIC_SWITCH, ANTHROPIC_BUDGET, GEMINI_LEVEL, GEMINI_BUDGET }
    public static final ReasoningOptions DEFAULT = new ReasoningOptions(Mode.DEFAULT, "", 0);

    public ReasoningOptions {
        Objects.requireNonNull(mode); Objects.requireNonNull(level);
        boolean named = mode == Mode.OPENAI_EFFORT || mode == Mode.ANTHROPIC_ADAPTIVE || mode == Mode.GEMINI_LEVEL
                || mode == Mode.OPENAI_SWITCH || mode == Mode.ANTHROPIC_SWITCH;
        boolean budget = mode == Mode.ANTHROPIC_BUDGET || mode == Mode.GEMINI_BUDGET;
        if (named ? !level.matches("[A-Za-z0-9_-]{1,64}") || budgetTokens != 0
                : !level.isEmpty() || !budget && budgetTokens != 0) throw new IllegalArgumentException("invalid_reasoning_settings");
        if ((mode == Mode.OPENAI_SWITCH || mode == Mode.ANTHROPIC_SWITCH)
                && !level.equals("enabled") && !level.equals("disabled")) throw new IllegalArgumentException("invalid_thinking_switch");
        if (mode == Mode.ANTHROPIC_BUDGET && budgetTokens < 1024
                || mode == Mode.GEMINI_BUDGET && budgetTokens < -1) throw new IllegalArgumentException("invalid_reasoning_budget");
    }

    public JSONObject toJson() throws org.json.JSONException {
        return new JSONObject().put("mode", mode.name()).put("level", level).put("budgetTokens", budgetTokens);
    }

    /** Storage decoding only. Generic feature/model settings must not accept native selections. */
    public static ReasoningOptions fromJson(JSONObject value) throws org.json.JSONException {
        Object budget = value.get("budgetTokens");
        if (!(budget instanceof Integer || budget instanceof Long) || ((Number) budget).longValue() < Integer.MIN_VALUE
                || ((Number) budget).longValue() > Integer.MAX_VALUE) throw new IllegalArgumentException("invalid_reasoning_budget");
        return new ReasoningOptions(Mode.valueOf(value.getString("mode")), value.getString("level"), ((Number) budget).intValue());
    }
}
