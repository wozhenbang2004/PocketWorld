package dev.pocketengine.runtime.models;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.providers.ProviderAdapters;
import java.net.URI;

/** Catalog endpoints stay on the exact inference origin; keys are never forwarded to another host. */
public final class ModelCatalogRoutes {
    private ModelCatalogRoutes() { }
    public static ModelBinding.Protocol protocol(String provider) {
        return switch (provider) {
            case "openai_chat" -> ModelBinding.Protocol.OPENAI_CHAT;
            case "openai_responses" -> ModelBinding.Protocol.OPENAI_RESPONSES;
            case "anthropic" -> ModelBinding.Protocol.ANTHROPIC;
            case "gemini" -> ModelBinding.Protocol.GEMINI;
            default -> throw new IllegalArgumentException("unsupported_catalog_protocol");
        };
    }
    public static ModelBinding.Format defaultFormat(String provider) {
        return switch (provider) {
            case "openai_chat", "openai_responses" -> ModelBinding.Format.OPENAI;
            case "anthropic" -> ModelBinding.Format.ANTHROPIC;
            case "gemini" -> ModelBinding.Format.GEMINI;
            default -> throw new IllegalArgumentException("unsupported_catalog_protocol");
        };
    }
    public static URI modelsEndpoint(ModelConfig config) throws Exception {
        URI inference = ProviderAdapters.selected(config.provider).endpoint(config).toURI();
        String path = inference.getPath();
        if (config.provider.equals("gemini")) path = path.substring(0, path.lastIndexOf("/models/")) + "/models";
        else path = path.substring(0, path.lastIndexOf('/')) + "/models";
        return new URI(inference.getScheme(), null, inference.getHost(), inference.getPort(), path, null, null);
    }
    public static void requireSameOrigin(ModelConfig config, URI models) throws Exception {
        URI inference = ProviderAdapters.selected(config.provider).endpoint(config).toURI();
        if (models.getHost() == null || !inference.getScheme().equalsIgnoreCase(models.getScheme())
                || !inference.getHost().equalsIgnoreCase(models.getHost()) || port(inference) != port(models))
            throw new IllegalArgumentException("模型目录必须与此推理连接处于同一个 origin，不能把其凭据发送给另一地址");
        config.requireTransportConsent(models.toURL());
    }
    private static int port(URI uri) { return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80; }
}
