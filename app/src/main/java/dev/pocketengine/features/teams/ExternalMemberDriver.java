package dev.pocketengine.features.teams;

import dev.pocketengine.core.RunControl;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Host-injected protocol adapter. Requests contain host-bound IDs, never identity parsed from model text. */
public interface ExternalMemberDriver {
    JSONObject probe(JSONObject slot,RunControl control)throws Exception;
    JSONObject execute(JSONObject request,RunControl control,Consumer<JSONObject> events)throws Exception;
}
