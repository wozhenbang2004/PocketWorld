package dev.pocketengine.runtime.models;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.context.ContextWindow;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONObject;

/** Request admission over current native facts. No network, route fallback, retries or tool dispatch. */
public final class ModelRequestPolicy {
    private ModelRequestPolicy() { }
    public interface Source { ModelCatalog.View resolve(ModelConfig selected) throws Exception; }
    private static volatile Source source;
    public static void installHostSource(Source nativeSource) { source = Objects.requireNonNull(nativeSource); }

    public static ModelCatalog.View resolve(ModelConfig config) throws Exception {
        OfficialImageModelPurpose.requireConversation(config);
        if (config.usesBedrock()) {
            if (config.capabilityBinding == null || config.catalogEnforced || config.awsSession == null)
                throw new Rejected("bedrock_saved_session_required", "请先保存原 Bedrock 连接与临时凭据，再从该连接发起请求。");
            try { config.awsSession.requireValidAt(System.currentTimeMillis()); }
            catch (IllegalArgumentException expired) { throw new Rejected("bedrock_session_expired", "原 Bedrock 临时凭据已过期或不可用，请在原连接更换整组凭据后重新审阅任务。", expired); }
        }
        if (config.capabilityBinding == null) return null;
        Source current = source;
        if (current == null) throw new Rejected("catalog_host_unavailable", "模型能力目录尚未连接，请重新打开应用。");
        ModelCatalog.View view;
        try { view = current.resolve(config); }
        catch (Rejected known) { throw known; }
        catch (InterruptedException stopped) { throw stopped; }
        catch (Exception unavailable) { throw new Rejected("catalog_state_unavailable", "当前精确模型能力状态无法读取，请回模型页核对；没有改用旧能力。", unavailable); }
        if (config.usesBedrock() && current != source) throw new Rejected("catalog_host_changed", "原模型宿主已变化，请回模型页核对当前连接。");
        if (view == null && !config.catalogEnforced && config.reasoning.mode() == ReasoningOptions.Mode.DEFAULT)
            return null; // Native host still checked this exact enabled connection.
        if (view == null || !view.key().binding().equals(config.capabilityBinding) || !view.key().modelId().equals(config.model))
            throw new Rejected("stale_model_binding", "模型连接、密钥或安装版本已变化，请回模型页核对；没有换号或换模型。");
        return view;
    }

    /** Called after native serialization and stream negotiation, so both entries use identical fields. */
    public static JSONObject apply(ModelConfig config, JSONArray messages, JSONArray tools, JSONObject wire) throws Exception {
        return applyNative(config,messages,tools,wire,false);
    }
    /** Same native facts/limits for protocol-preserving relay input. The flag describes validated
     * function history, not a declaration, execution grant or an added wire tool. */
    public static JSONObject applyNative(ModelConfig config,JSONArray messages,JSONArray tools,JSONObject wire,boolean nativeFunctionHistory)throws Exception {
        ModelCatalog.View view = resolve(config);
        if (view == null) return projectCompatibility(config,messages,tools,wire); // Identity-only profiles still enforce reviewed wire limits.
        ModelFacts facts = view.facts();
        boolean images = hasImages(messages), toolContext = nativeFunctionHistory || tools != null && tools.length() > 0 || hasToolHistory(messages);
        if (!config.catalogEnforced) {
            // Exact owner-reviewed reasoning on an identity-only API route must not turn unknown
            // tool/image/chat-token facts into a mandatory full-catalog prerequisite.
            validateOutput(config, facts);
            if (images && facts.imageInput().known && !facts.imageInput().value)
                throw new Rejected("image_capability_unsupported", "该模型已明确声明不支持图像输入，本次尚未发送。");
            if (toolContext && facts.toolCalls().known && !facts.toolCalls().value)
                throw new Rejected("tool_capability_unsupported", "该模型已明确声明不支持工具调用，本次尚未发送。");
            checkContext(config, messages, tools, facts, images);
            dev.pocketengine.runtime.providers.ProviderAdapters.projectReasoning(config, config.reasoning, wire);
            return projectCompatibility(config, messages, tools, wire);
        }
        if (images) requireTrue(facts.imageInput(), "image_capability", "当前精确模型的图像输入能力未知或不支持；请在模型页核对。");
        if (toolContext) requireTrue(facts.toolCalls(), "tool_capability", "当前精确模型的工具调用能力未知或不支持；请在模型页核对，历史工具结果不会被删除。");
        validateOutput(config, facts);
        checkContext(config, messages, tools, facts, images);
        return projectAdmittedOptions(config,messages,tools,facts,wire);
    }

    private static JSONObject projectCompatibility(ModelConfig config,JSONArray messages,JSONArray tools,JSONObject wire)throws Exception {
        try{return dev.pocketengine.runtime.providers.ProviderCompatibility.project(config,messages,tools,wire);}
        catch(dev.pocketengine.runtime.providers.ProviderCompatibility.Rejected rejected){throw new Rejected(rejected.code,rejected.getMessage(),rejected);}
    }

    /** Native accounting-only diagnostics ask about unknown capabilities without changing facts.
     * Ordinary apply/applyNative remain strict; a JSON field cannot issue this native purpose. */
    public static JSONObject applyCapabilityProbe(ModelConfig config,JSONArray messages,JSONArray tools,JSONObject wire,
            dev.pocketengine.runtime.usage.InferenceCapture.Target target,dev.pocketengine.core.RunControl original)throws Exception {
        dev.pocketengine.runtime.usage.InferenceCapture.requireOriginalContext(original,target);
        if(target.context.purpose!=dev.pocketengine.runtime.usage.InferenceReceipt.Purpose.MODEL_CAPABILITY_PROBE
                ||target.context.bindingKind!=dev.pocketengine.runtime.usage.InferenceReceipt.BindingKind.HOST_RUN
                ||target.context.taskId.isEmpty()||target.logical==null||config.usesAccount()||config.capabilityBinding==null||config.routePlan!=null)
            throw new SecurityException("native_model_capability_probe_session_required");
        original.check();ModelCapabilityProbe.requireSyntheticRequest(messages,tools);
        ModelCatalog.View view = resolve(config);
        if (view == null) return projectCompatibility(config,messages,tools,wire); // Explicit pre-catalog API configuration keeps its existing contract.
        ModelFacts facts = view.facts();
        boolean images = hasImages(messages), toolContext = tools != null && tools.length() > 0 || hasToolHistory(messages);
        if (images && facts.imageInput().known) requireTrue(facts.imageInput(), "image_capability", "当前精确模型的图像输入能力未知或不支持；请在模型页核对。");
        if (toolContext && facts.toolCalls().known) requireTrue(facts.toolCalls(), "tool_capability", "当前精确模型的工具调用能力未知或不支持；请在模型页核对，历史工具结果不会被删除。");
        validateOutput(config, facts);
        checkContext(config, messages, tools, facts, images);
        return projectAdmittedOptions(config,messages,tools,facts,wire);
    }

    /** Common projection only after each entry has checked its own native authority,
     * capability facts and context budget. It does not resolve or cache a new grant. */
    private static JSONObject projectAdmittedOptions(ModelConfig config,JSONArray messages,JSONArray tools,
            ModelFacts facts,JSONObject wire)throws Exception {
        if (config.provider.equals("openai_chat")) {
            wire.remove("max_tokens"); wire.remove("max_completion_tokens");
            ModelFacts.Fact<ModelFacts.ChatOutputParameter> field = facts.chatOutputParameter();
            if (!field.known || field.value == ModelFacts.ChatOutputParameter.NOT_APPLICABLE)
                throw new Rejected("chat_output_parameter_unknown", "Chat 输出参数字段未知，请在模型页明确选择 max_tokens 或 max_completion_tokens；本次尚未发送。");
            wire.put(field.value == ModelFacts.ChatOutputParameter.MAX_TOKENS ? "max_tokens" : "max_completion_tokens", config.maxOutputTokens);
        }
        validateReasoning(config, facts);
        dev.pocketengine.runtime.providers.ProviderAdapters.projectReasoning(config,config.reasoning,wire);
        return projectCompatibility(config,messages,tools,wire);
    }

    /** Owner selection is checked before persisting, and again on each actual request boundary. */
    public static void validateOutput(ModelConfig config, ModelFacts facts) throws Rejected {
        if (facts.outputTokens().known && config.maxOutputTokens > facts.outputTokens().value)
            throw new Rejected("output_limit_exceeded", "输出上限超过当前模型的已知容量，请降低设置；不会静默截小。");
        if (facts.contextWindow().known && facts.contextWindow().value.kind() == ModelFacts.WindowKind.INPUT_AND_OUTPUT
                && config.maxOutputTokens >= facts.contextWindow().value.tokens())
            throw new Rejected("output_exhausts_context", "输出预留已占满已知上下文，请降低输出上限。");
        validateReasoning(config, facts);
    }

    private static void checkContext(ModelConfig config, JSONArray messages, JSONArray tools, ModelFacts facts, boolean images) throws Exception {
        if (!facts.contextWindow().known) return; // Existing local byte ceiling remains active, never an unlimited token claim.
        ModelFacts.TokenWindow window = facts.contextWindow().value;
        long input = window.tokens() - (window.kind() == ModelFacts.WindowKind.INPUT_AND_OUTPUT ? config.maxOutputTokens : 0);
        if (images && config.imageTokenReserve == 0) throw new Rejected("image_token_budget_unknown", "该模型上下文已知，但图片 token 预留未配置；请在模型页明确填写本地预留，或改用文字。预留不是提供方实际 tokenizer 结果。");
        long total = window.kind() == ModelFacts.WindowKind.INPUT_ONLY ? Math.addExact(window.tokens(), config.maxOutputTokens) : window.tokens();
        ContextWindow.Budget budget = new ContextWindow.Budget(total, config.maxOutputTokens, 0, 20L * 1024 * 1024, 0, config.imageTokenReserve);
        ContextWindow.Plan plan = ContextWindow.plan(messages, tools, budget, ContextWindow.Retention.preserveAll());
        if (!plan.canSend()) throw new Rejected("context_" + plan.status, "当前请求超过已知上下文的保守文字字节预算，或历史结构无法核对；未删改历史、未发送。输入预算 " + input + " tokens，估算不是实际 tokenizer 结果。");
    }

    private static void validateReasoning(ModelConfig config, ModelFacts facts) throws Rejected {
        ReasoningOptions options = config.reasoning;
        if (options.mode() == ReasoningOptions.Mode.DEFAULT) return;
        requireTrue(facts.reasoning(), "reasoning_capability", "该模型推理能力未知或不支持，不能发送额外推理设置。");
        boolean matchingProtocol = switch (options.mode()) {
            case OPENAI_EFFORT -> config.provider.equals("openai_chat") || config.provider.equals("openai_responses");
            case OPENAI_SWITCH -> config.provider.equals("openai_chat") && !ModelReasoningProfiles.switchWire(config.model).isEmpty();
            case ANTHROPIC_SWITCH -> config.provider.equals("anthropic") && !ModelReasoningProfiles.switchWire(config.model).isEmpty();
            case ANTHROPIC_ADAPTIVE, ANTHROPIC_BUDGET -> config.provider.equals("anthropic") || config.usesBedrock();
            case GEMINI_LEVEL, GEMINI_BUDGET -> config.provider.equals("gemini");
            default -> false;
        };
        if (!matchingProtocol) throw new Rejected("reasoning_protocol_mismatch", "推理设置与所选原生协议不一致。");
        if (options.mode() == ReasoningOptions.Mode.GEMINI_LEVEL && ModelReasoningProfiles.usesTokenBudget(config.model))
            throw new Rejected("gemini25_requires_budget", "Gemini 2.5 使用 thinkingBudget，不接受 thinkingLevel；请重新选择推理强度。");
        if (!options.level().isEmpty() && (!facts.reasoningLevels().known || !facts.reasoningLevels().value.contains(options.level())))
            throw new Rejected("reasoning_level_unknown", "该精确模型没有声明此推理级别，请从已知级别中选择或保留服务默认。");
        if (options.mode() == ReasoningOptions.Mode.ANTHROPIC_BUDGET && options.budgetTokens() >= config.maxOutputTokens
                || options.mode() == ReasoningOptions.Mode.GEMINI_BUDGET && options.budgetTokens() > config.maxOutputTokens)
            throw new Rejected("reasoning_budget_exceeds_output", "推理预算与输出上限不一致，请调整；不会自动提高费用预算。");
    }

    private static void requireTrue(ModelFacts.Fact<Boolean> fact, String code, String text) throws Rejected {
        if (!fact.known || !fact.value) throw new Rejected(code + (fact.known ? "_unsupported" : "_unknown"), text);
    }
    private static boolean hasImages(JSONArray messages) {
        for (int i = 0; i < messages.length(); i++) {
            JSONObject row = messages.optJSONObject(i);
            JSONArray parts = row == null ? null : row.optJSONArray("content");
            if (parts != null) for (int j = 0; j < parts.length(); j++) {
                JSONObject part = parts.optJSONObject(j);
                if (part != null && "image_url".equals(part.optString("type"))) return true;
            }
        }
        return false;
    }
    private static boolean hasToolHistory(JSONArray messages) {
        for (int i = 0; i < messages.length(); i++) {
            JSONObject row = messages.optJSONObject(i);
            if (row == null) continue;
            if ("tool".equals(row.optString("role"))) return true;
            if (row.has("tool_calls") && !row.isNull("tool_calls")) {
                JSONArray calls = row.optJSONArray("tool_calls");
                // Malformed non-null values must not bypass the tool capability gate.
                if (calls == null || calls.length() > 0) return true;
            }
        }
        return false;
    }
    public static final class Rejected extends Exception {
        public final String code;
        public Rejected(String code, String message) { super(message); this.code = code; }
        public Rejected(String code, String message, Throwable cause) { super(message, cause); this.code = code; }
    }
}
