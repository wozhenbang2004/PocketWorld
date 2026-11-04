package dev.pocketengine.runtime.context;

import dev.pocketengine.core.ModelConfig;
import org.json.JSONObject;

import java.util.Objects;

/** Native route metadata seam. No catalog, model-name heuristic, persistence or model-side setter. */
public final class ContextBudgetPolicy {
    // These are application safety limits, not vendor/model context-window claims.
    public static final long REQUEST_BYTES = 20L * 1024 * 1024;
    public static final long CHECKPOINT_BYTES = 2L * 1024 * 1024;

    private ContextBudgetPolicy() { }

    public enum SourceKind { OWNER_OVERRIDE, PROVIDER_METADATA, VERIFIED_SUPPLEMENT }

    /** Read a current local immutable snapshot without network I/O; refresh separately in the host.
     * Return null when the selected route's window is genuinely unknown. This is not an auth grant.
     */
    public interface Source { RouteBudget resolve(ModelConfig selected) throws Exception; }

    private static volatile Source hostSource = selected -> null;

    /** Application-only injection. It neither selects a route nor authorizes an account or tool. */
    public static void installHostSource(Source source) { hostSource = Objects.requireNonNull(source); }

    /** Resolve afresh on every boundary, including runners constructed before host installation. */
    public static Source currentHostSource() { return selected -> hostSource.resolve(selected); }

    /** Keep the immutable captured route anchor while resolving only this run's accepted renewal facts. */
    public static Source forRun(Source source,dev.pocketengine.core.RunControl original){
        Objects.requireNonNull(source);
        return captured->{
            if(!captured.usesAccount())return source.resolve(captured);
            ModelConfig accepted=dev.pocketengine.runtime.ModelGateway.accountBudgetConfig(captured,original);
            RouteBudget actual=source.resolve(accepted);
            if(actual==null)return null;
            if(!actual.matches(accepted))throw new Unavailable("stale_context_budget_route");
            return RouteBudget.forRoute(captured,actual.limits,actual.source,actual.revision,actual.observedAtMillis);
        };
    }
    /** A window belongs to this exact route/model/credential generation, not just a model id. */
    public static final class RouteBudget {
        private final String routeIdentity;
        public final ContextWindow.Budget limits;
        public final SourceKind source;
        public final long revision, observedAtMillis;

        private RouteBudget(ModelConfig selected, ContextWindow.Budget limits, SourceKind source,
                            long revision, long observedAtMillis) {
            if (!limits.tokenWindowKnown || limits.outputReserve < selected.maxOutputTokens
                    || revision < 1 || observedAtMillis < 1) {
                throw new IllegalArgumentException("Host budget needs known limits and the configured output reserve");
            }
            this.routeIdentity = identity(selected);
            this.limits = limits;
            this.source = Objects.requireNonNull(source);
            this.revision = revision;
            this.observedAtMillis = observedAtMillis;
        }

        public static RouteBudget forRoute(ModelConfig selected, ContextWindow.Budget limits,
                                           SourceKind source, long revision, long observedAtMillis) {
            return new RouteBudget(Objects.requireNonNull(selected), Objects.requireNonNull(limits),
                    source, revision, observedAtMillis);
        }

        boolean matches(ModelConfig selected) { return routeIdentity.equals(identity(selected)); }
    }

    static final class Resolved {
        final ContextWindow.Budget limits;
        final RouteBudget metadata;
        Resolved(ContextWindow.Budget limits, RouteBudget metadata) { this.limits = limits; this.metadata = metadata; }
        JSONObject diagnostics() throws Exception {
            return new JSONObject().put("metadataSource", metadata == null ? "unknown" : metadata.source.name().toLowerCase(java.util.Locale.ROOT))
                    .put("metadataRevision", metadata == null ? JSONObject.NULL : metadata.revision)
                    .put("metadataObservedAtMillis", metadata == null ? JSONObject.NULL : metadata.observedAtMillis)
                    .put("automaticCatalog", false).put("automaticModelSwitch", false);
        }
    }

    static Resolved resolve(Source source, ModelConfig config, long byteCeiling) throws Exception {
        RouteBudget selected = source.resolve(config);
        if (selected == null) return new Resolved(ContextWindow.Budget.localBytes(byteCeiling), null);
        if (!selected.matches(config) || selected.limits.outputReserve < config.maxOutputTokens) {
            throw new Unavailable("stale_context_budget_route");
        }
        return new Resolved(selected.limits.withByteCeiling(byteCeiling), selected);
    }

    static final class Unavailable extends Exception {
        final String code;
        Unavailable(String code) { super(code); this.code = code; }
    }

    private static String identity(ModelConfig config) {
        // No credential is returned or logged; the digest only prevents metadata crossing credentials.
        try {
            JSONObject route = new JSONObject().put("provider", config.provider).put("model", config.model)
                    .put("baseUrl", config.baseUrl).put("credential", config.credentialIdentityDigest());
            if (config.usesAccount()) route.put("account", config.accountModel.toJson());
            if (config.usesBedrock()) route.put("bedrockRegion",config.bedrockRegion).put("binding",config.capabilityBinding==null?JSONObject.NULL:config.capabilityBinding.toJson());
            return route.toString();
        } catch (Exception impossible) { throw new IllegalStateException("Cannot bind context metadata", impossible); }
    }


}
