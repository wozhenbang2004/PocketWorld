package dev.pocketengine.runtime.models;

import dev.pocketengine.core.ModelConfig;
import java.net.URI;
import java.util.Set;

/** Official image-only identities, not inferred capabilities for arbitrary compatible services. */
public final class OfficialImageModelPurpose {
    public static final String REASON = "image_model_requires_media";
    public static final String MESSAGE = "这是 OpenAI 生图专用模型，不能作为对话主模型。请在工作区 → 媒体 → 服务配置中填写图片模型；对话仍选择支持对话与工具的模型。原配置未修改，本次未发送。";
    // Official Images API model IDs, read 2026-11-11. This is purpose evidence, not an access/availability promise.
    // https://developers.openai.com/api/reference/resources/images/methods/generate
    private static final Set<String> IMAGE_MODELS = Set.of(
            "gpt-image-1", "gpt-image-1-mini", "gpt-image-1.5", "gpt-image-1.5-2025-12-16",
            "gpt-image-2", "gpt-image-2-2026-04-21", "gpt-image-2.5-sunburst",
            "gpt-image-2.5-sunburst-2026-09-08", "gpt-image-2.5-flare", "gpt-image-2.5-flare-2026-09-08",
            "gpt-image-2.6-sunburst", "gpt-image-2.6-flare", "chatgpt-image-latest", "dall-e-2", "dall-e-3");

    private OfficialImageModelPurpose() { }

    public static boolean imageOnly(String provider, String baseUrl, String model) {
        if (!("openai_chat".equals(provider) || "openai_responses".equals(provider))
                || model == null || !IMAGE_MODELS.contains(model)) return false;
        try {
            URI uri = new URI(baseUrl);
            return "https".equalsIgnoreCase(uri.getScheme()) && "api.openai.com".equalsIgnoreCase(uri.getHost())
                    && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getUserInfo() == null;
        } catch (Exception invalid) { return false; } // Endpoint validation owns malformed configuration.
    }

    public static void requireConversation(ModelConfig config) throws ModelRequestPolicy.Rejected {
        if (!config.usesAccount() && imageOnly(config.provider, config.baseUrl, config.model))
            throw new ModelRequestPolicy.Rejected(REASON, MESSAGE);
    }
}
