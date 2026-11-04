package dev.pocketengine.runtime.providers.bedrock;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.io.UnsupportedEncodingException;

/** Fixed commercial AWS route for the explicitly supported Claude Messages models. */
public final class BedrockEndpoint {
    private BedrockEndpoint() {}

    public static String baseUrl(String region) {
        validateRegion(region);
        return "https://bedrock-runtime." + region + ".amazonaws.com";
    }

    public static void validate(String region, String model) {
        validateRegion(region);
        if (model == null || model.length() > 200
            || !model.matches("anthropic\\.claude-[a-z0-9][a-z0-9.:-]*")
            || !("anthropic.claude-sonnet-4-6".equals(model)
                || "anthropic.claude-opus-4-6-v1".equals(model))) {
            throw new IllegalArgumentException("bedrock_model_not_supported");
        }
    }

    public static URL endpoint(String region, String model, boolean stream) {
        validate(region, model);
        String path = "/model/" + encodeLabel(model)
            + (stream ? "/invoke-with-response-stream" : "/invoke");
        try {
            return URI.create(baseUrl(region) + path).toURL();
        } catch (MalformedURLException invalid) {
            throw new IllegalArgumentException("bedrock_endpoint_invalid");
        }
    }

    private static void validateRegion(String region) {
        // This validates a commercial region's syntax, not account/model availability.
        // gov, cn, iso and sovereign partitions cannot match this closed prefix set.
        if (region == null || region.length() > 32
            || !region.matches("(af|ap|ca|eu|il|me|mx|sa|us)-(central|east|north|northeast|northwest|south|southeast|southwest|west)-[1-9][0-9]?")) {
            throw new IllegalArgumentException("bedrock_region_invalid");
        }
    }

    /** SDK v2 PathMarshaller.NON_GREEDY + SdkHttpUtils.urlEncode, without SDK dependencies.
     * Source/version/license correspondence is recorded in UPSTREAM.md. */
    static String encodeLabel(String value) {
        if (value == null) throw new IllegalArgumentException("bedrock_path_invalid");
        try {
            // The String-charset overload is available on the app's Android API 30 floor.
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
                .replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException("bedrock_utf8_unavailable");
        }
    }
}

