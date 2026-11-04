package dev.pocketengine.runtime.models;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/** Public identity supplied by the native host. Never contains a key or chooses a model. */
public record ModelBinding(String connectionId, String providerId, Protocol protocol, Format format,
                           URI modelsEndpoint, String credentialId, long credentialRevision,
                           String installationId, long installationRevision, boolean cleartextApproved) {
    public enum Protocol { OPENAI_CHAT, OPENAI_RESPONSES, ANTHROPIC, GEMINI, BEDROCK_ANTHROPIC }
    public enum Format { OPENAI, ANTHROPIC, GEMINI, OPENROUTER, NONE }

    public ModelBinding {
        connectionId = identity(connectionId);
        providerId = identity(providerId);
        credentialId = identity(credentialId);
        installationId = identity(installationId);
        Objects.requireNonNull(protocol);
        Objects.requireNonNull(format);
        if (credentialRevision < 1 || installationRevision < 1) throw new IllegalArgumentException("binding_revision_required");
        boolean compatible = switch (format) {
            case OPENAI -> protocol == Protocol.OPENAI_CHAT || protocol == Protocol.OPENAI_RESPONSES;
            case ANTHROPIC -> protocol == Protocol.ANTHROPIC;
            case GEMINI -> protocol == Protocol.GEMINI;
            case OPENROUTER -> protocol == Protocol.OPENAI_CHAT;
            case NONE -> protocol == Protocol.BEDROCK_ANTHROPIC;
        };
        if (!compatible) throw new IllegalArgumentException("catalog_protocol_mismatch");
        if (format == Format.NONE) {
            if (modelsEndpoint == null || !modelsEndpoint.toString().isEmpty() || cleartextApproved)
                throw new IllegalArgumentException("bedrock_identity_has_no_catalog_endpoint");
        } else modelsEndpoint = endpoint(modelsEndpoint, cleartextApproved);
    }

    private static String identity(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,200}")) throw new IllegalArgumentException("invalid_public_identity");
        return value;
    }

    static String modelId(String value) {
        if (value == null || value.isEmpty() || value.length() > 300 || !value.equals(value.trim())
                || value.chars().anyMatch(c -> Character.isISOControl(c))) throw new IllegalArgumentException("invalid_model_id");
        return value;
    }

    private static URI endpoint(URI uri, boolean cleartextApproved) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.getPort() > 65535 || !uri.normalize().equals(uri)) {
            throw new IllegalArgumentException("invalid_models_endpoint");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT), host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1");
        if (!scheme.equals("https") && !(scheme.equals("http") && (loopback || cleartextApproved))) {
            throw new IllegalArgumentException("catalog_transport_consent_required");
        }
        if (uri.getRawPath() == null || uri.getRawPath().isEmpty()) throw new IllegalArgumentException("explicit_models_path_required");
        return uri;
    }

    JSONObject json() throws JSONException {
        return new JSONObject().put("connectionId", connectionId).put("providerId", providerId)
                .put("protocol", protocol.name()).put("format", format.name()).put("modelsEndpoint", modelsEndpoint.toASCIIString())
                .put("credentialId", credentialId).put("credentialRevision", credentialRevision)
                .put("installationId", installationId).put("installationRevision", installationRevision)
                .put("cleartextApproved", cleartextApproved);
    }
    public JSONObject toJson() throws JSONException { return json(); }
    /** Native storage decoding. This reference never authorizes a credential or model-side selection. */
    public static ModelBinding fromJson(JSONObject value) throws JSONException { return read(value); }

    static ModelBinding read(JSONObject j) throws JSONException {
        Object approved = j.get("cleartextApproved");
        if (!(approved instanceof Boolean)) throw new IllegalArgumentException("invalid_binding_cache");
        return new ModelBinding(j.getString("connectionId"), j.getString("providerId"), Protocol.valueOf(j.getString("protocol")),
                Format.valueOf(j.getString("format")), URI.create(j.getString("modelsEndpoint")), j.getString("credentialId"),
                ModelFacts.positiveInteger(j.get("credentialRevision")), j.getString("installationId"),
                ModelFacts.positiveInteger(j.get("installationRevision")), (Boolean) approved);
    }

    @Override public String toString() { return "ModelBinding[public route identity]"; }
}
