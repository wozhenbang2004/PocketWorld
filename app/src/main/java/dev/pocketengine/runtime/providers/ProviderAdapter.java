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
    JSONObject response(JSONObject wireResponse)throws Exception;
    Map<String,String> headers(ModelConfig config);
}
