package dev.pocketengine.features.remote;

import org.json.JSONObject;

/** Implemented by the native host using its single TaskStore; this adapter owns no task database. */
public interface ExternalChildJournal {
    /**
     * Persist and fence the child before dispatch. request contains hostTaskId, runId, memberId,
     * round, generation, operationId, slot and optional previousOperationId. binding contains
     * profileId, environmentId, worker, optional model and either readOnly=true (Codex) or teamReadOnlyTools=true (Claude, not an OS sandbox).
     * A sessionSource must be checked against the same original member child in TaskStore.
     * Return {taskId, operationId, knownOperation:boolean}; existing operations are query-only.
     */
    JSONObject prepare(JSONObject request,JSONObject binding)throws Exception;

    /** Store actual executor receipts, including unknown outcomes, before exposing progress. */
    void record(JSONObject childLink,JSONObject receipt)throws Exception;
}
