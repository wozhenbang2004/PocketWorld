package dev.pocketengine.features.teams;

import dev.pocketengine.core.RunControl;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Host-injected protocol adapter. Requests contain host-bound IDs, never identity parsed from model text. */
public interface ExternalMemberDriver {
    /** The callback is closed over the original live coordinator Turn, never model-supplied sender IDs. */
    interface TeamBridge { JSONObject call(String requestId,String name,JSONObject arguments)throws Exception; }
    JSONObject probe(JSONObject slot,RunControl control)throws Exception;
    JSONObject execute(JSONObject request,RunControl control,Consumer<JSONObject> events)throws Exception;
    default JSONObject execute(JSONObject request,RunControl control,Consumer<JSONObject> events,TeamBridge bridge)throws Exception {
        return execute(request,control,events);
    }
}
