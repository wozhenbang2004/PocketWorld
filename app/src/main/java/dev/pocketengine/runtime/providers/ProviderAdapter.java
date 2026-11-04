package dev.pocketengine.runtime.providers;

import dev.pocketengine.core.ModelConfig;
import java.net.URL;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Wire conversion only. Tool dispatch, retries and durable task state stay with the host. */
public interface ProviderAdapter {
    URL endpoint(ModelConfig config)throws Exception;
    JSONObject request(ModelConfig config,JSONArray canonicalMessages,JSONArray canonicalTools)throws Exception;
    /** Wire codec only. Production decisions must enter through ApiTurnStream's shared
     * terminal/role/tool validation, with the actual request's declared tools. */
    JSONObject response(JSONObject wireResponse)throws Exception;
    Map<String,String> headers(ModelConfig config);
    /** Authentication over the exact final body and physical target, after native projection.
     * Ordinary API adapters retain their original header contract. */
    default Map<String,String> headers(ModelConfig config,URL endpoint,byte[] payload)throws Exception {
        return headers(config);
    }
}
