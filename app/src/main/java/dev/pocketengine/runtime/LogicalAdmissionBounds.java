package dev.pocketengine.runtime;

import org.json.JSONObject;

/**
 * Typed projection of the original logical authority's admission receipt.
 * This class cannot acquire admission or relax the caller's deadline/attempt cap.
 * It keeps resource-owner identity and its concurrency limit together so a
 * caller cannot silently accept half of an invalid native resource scope.
 */
record LogicalAdmissionBounds(long deadlineAt, int maxAttempts,
                              String resourceOwner, int maxConcurrentCalls) {
    static LogicalAdmissionBounds from(JSONObject admitted, long originalDeadline, int originalMaxAttempts)
            throws Exception {
        long deadline = Math.min(originalDeadline, admitted.getLong("deadlineAt"));
        int maximum = Math.min(originalMaxAttempts, admitted.getJSONObject("control").getInt("maxAttempts"));
        String owner = null;
        int concurrent = Integer.MAX_VALUE;
        // Absence preserves the original no-inherited-limit path. Presence,
        // including JSON null, must carry the exact native object shape; never
        // treat malformed scope as if no resource restriction was supplied.
        if (admitted.has("modelResourceScope")) {
            if (!(admitted.opt("modelResourceScope") instanceof JSONObject)) {
                throw invalidScope();
            }
            JSONObject resources = admitted.getJSONObject("modelResourceScope");
            owner = resources.getString("ownerTaskId");
            Object raw = resources.get("maxConcurrentModelCalls");
            // These checks intentionally retain the original wire types and
            // bounds: no string coercion, fractional number or foreign owner.
            if (!owner.equals(admitted.getString("budgetOwnerRef"))
                    || !owner.matches("[a-f0-9-]{36}")
                    || !(raw instanceof Integer || raw instanceof Long)
                    || ((Number) raw).longValue() < 1 || ((Number) raw).longValue() > 2) {
                throw invalidScope();
            }
            concurrent = ((Number) raw).intValue();
        }
        return new LogicalAdmissionBounds(deadline, maximum, owner, concurrent);
    }

    private static SecurityException invalidScope() {
        return new SecurityException("original_native_model_resource_scope_required");
    }
}
