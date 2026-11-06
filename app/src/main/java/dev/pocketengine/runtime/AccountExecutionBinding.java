package dev.pocketengine.runtime;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.features.proxy.accounts.GatewayFailure;

/**
 * Ephemeral version continuity for one native account/model selection.
 * The gateway owns weak run association, host identity and authorization checks.
 * This object holds no RunControl, credentials, callback, I/O or dispatch authority.
 * Its monitor protects only its version set and current reference; it never calls
 * back into the gateway or acquires the gateway's binding-map monitor.
 */
final class AccountExecutionBinding {
    final ModelConfig.AccountModelRef original;private ModelConfig.AccountModelRef current;
    private final java.util.Set<Long> versions=new java.util.HashSet<>();
    AccountExecutionBinding(ModelConfig.AccountModelRef ref){original=ref;current=ref;versions.add(ref.accountVersion);}
    // Membership proves continuity inside this binding, not current authorization.
    // The gateway must still consult the installed host before admitting work.
    synchronized boolean accepts(ModelConfig.AccountModelRef ref){return original.sameModel(ref)&&versions.contains(ref.accountVersion);}
    synchronized ModelConfig.AccountModelRef current(){return current;}
    // Request history gets a snapshot; exposing the live set would let a caller
    // manufacture accepted versions or observe mutation outside this monitor.
    synchronized java.util.Set<Long> versions(){return new java.util.HashSet<>(versions);}
    // Selection identity deliberately ignores version renewal. This is not an
    // authorization check and must never replace accepts/host checks at dispatch.
    synchronized boolean selectionContinues(ModelConfig.AccountModelRef captured,ModelConfig.AccountModelRef selected){return original.sameModel(captured)&&original.sameModel(selected);}
    /**
     * Advance from a version already recorded in this lineage. An overlapping
     * invocation may finish renewal behind current: keep its accepted version for
     * that invocation, but never move current backwards. Requiring actual to be
     * at least current would incorrectly reject that existing overlap behavior.
     * No host callbacks here: callers may already hold the binding-map monitor,
     * so acquiring it from this monitor would introduce a reversed lock order.
     */
    synchronized ModelConfig.AccountModelRef advance(ModelConfig.AccountModelRef expected,long actual)throws GatewayFailure {
        if(!current.sameModel(expected)||actual<expected.accountVersion||!versions.contains(expected.accountVersion))throw new GatewayFailure("STALE_ACCOUNT_VERSION");
        ModelConfig.AccountModelRef accepted=expected.withVersion(actual);versions.add(actual);
        if(actual>=current.accountVersion)current=accepted;return accepted;
    }
}
