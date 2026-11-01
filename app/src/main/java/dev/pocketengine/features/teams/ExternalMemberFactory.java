package dev.pocketengine.features.teams;

import org.json.JSONObject;

/** Actual adapters only; absent adapters must fail explicitly rather than masquerade as model members. */
public interface ExternalMemberFactory {
    ExternalMemberDriver forSlot(JSONObject slot)throws Exception;
}
