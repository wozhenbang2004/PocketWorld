package dev.pocketengine.runtime;

import dev.pocketengine.features.memory.ToolOutputCapture;
import dev.pocketengine.features.proxy.accounts.GatewayFailure;
import dev.pocketengine.runtime.context.RunContextBudget;
import dev.pocketengine.runtime.models.ModelRequestPolicy;
import dev.pocketengine.runtime.routes.PhoneRouteSession;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Request-private bridge for native admission refusals across the account port's
 * checked GatewayFailure protocol. Keeps original exception identity, not a new
 * authority or retry rule. Unknown failures retain the existing generic mapping.
 *
 * <p>Repeated or concurrent callbacks retain the first refusal in each category.
 * Rethrowing then uses the fixed category precedence below, not callback arrival
 * order. Separate typed slots preserve both rules without casting stored failures.
 */
final class AccountAdmissionRefusals {
    private final AtomicReference<ToolOutputCapture.OutputSourceUnavailable> sourceUnavailable =
            new AtomicReference<>();
    private final AtomicReference<ModelRequestPolicy.Rejected> requestPolicyRejected =
            new AtomicReference<>();
    private final AtomicReference<RunContextBudget.Blocked> contextBudgetBlocked =
            new AtomicReference<>();
    private final AtomicReference<PhoneRouteSession.SourceUnavailable> routeUnavailable =
            new AtomicReference<>();
    private final AtomicReference<ModelGateway.RequestRefreshRequired> requestRefreshRequired =
            new AtomicReference<>();

    /** Preserve the original callback's catch-type order and first value per category. */
    GatewayFailure wrap(Exception failure) {
        if (failure instanceof ModelRequestPolicy.Rejected refused) {
            requestPolicyRejected.compareAndSet(null, refused);
        } else if (failure instanceof ToolOutputCapture.OutputSourceUnavailable refused) {
            sourceUnavailable.compareAndSet(null, refused);
        } else if (failure instanceof RunContextBudget.Blocked refused) {
            contextBudgetBlocked.compareAndSet(null, refused);
        } else if (failure instanceof PhoneRouteSession.SourceUnavailable refused) {
            routeUnavailable.compareAndSet(null, refused);
        } else if (failure instanceof ModelGateway.RequestRefreshRequired refused) {
            requestRefreshRequired.compareAndSet(null, refused);
        }
        return new GatewayFailure("INFERENCE_RECEIPT_STORE_UNAVAILABLE");
    }

    /**
     * Caller must retain its existing cancellation/currentness check before this.
     * Preserve the original category precedence even if callbacks are repeated:
     * output source, request policy, context budget, route, then request refresh.
     */
    void rethrowCaptured() throws Exception {
        ToolOutputCapture.OutputSourceUnavailable originalSource = sourceUnavailable.get();
        if (originalSource != null) {
            throw originalSource;
        }
        if (requestPolicyRejected.get() != null) {
            throw requestPolicyRejected.get();
        }
        if (contextBudgetBlocked.get() != null) {
            throw contextBudgetBlocked.get();
        }
        if (routeUnavailable.get() != null) {
            throw routeUnavailable.get();
        }
        if (requestRefreshRequired.get() != null) {
            throw requestRefreshRequired.get();
        }
    }
}
