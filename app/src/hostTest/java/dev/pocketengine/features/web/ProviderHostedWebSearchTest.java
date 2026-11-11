package dev.pocketengine.features.web;

import dev.pocketengine.core.ModelConfig;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ProviderHostedWebSearchTest {
    @Test public void onlyOfficialApiRoutesMayUseHostedSearch()throws Exception {
        ModelConfig official=new ModelConfig("https://api.openai.com/v1","native-key","gpt-6",false,12,30,"openai_responses",2048);
        ModelConfig relay=new ModelConfig("https://example.org/v1","native-key","gpt-6",false,12,30,"openai_responses",2048);
        assertTrue(ProviderHostedWebSearch.supports(official));
        assertFalse(ProviderHostedWebSearch.supports(relay));
        assertFalse(ProviderHostedWebSearch.supports(new ModelConfig("https://api.openai.com/v1","native-key","gpt-6",false,12,30,"openai_chat",2048)));
        JSONObject request=ProviderHostedWebSearch.body(official,"News today",2);
        assertEquals("web_search",request.getJSONArray("tools").getJSONObject(0).getString("type"));
        assertEquals(false,request.getBoolean("store"));
    }

    @Test public void openAiResponsesRequireCompletedSearchAndRealCitations()throws Exception {
        JSONObject call=new JSONObject().put("type","web_search_call").put("id","ws_1").put("status","completed")
                .put("action",new JSONObject().put("type","search").put("query","news"));
        JSONObject part=new JSONObject().put("type","output_text").put("text","According to a source...")
                .put("annotations",new JSONArray().put(new JSONObject().put("type","url_citation")
                        .put("url","https://example.org/current").put("title","Current")));
        JSONObject answer=new JSONObject().put("type","message").put("id","msg_1").put("content",new JSONArray().put(part));
        JSONObject raw=new JSONObject().put("status","completed").put("output",new JSONArray().put(call).put(answer))
                .put("usage",new JSONObject().put("input_tokens",123).put("output_tokens",45));
        JSONObject result=ProviderHostedWebSearch.normalize("openai_responses",raw);
        assertTrue(result.getBoolean("ok"));
        assertEquals(1,result.getJSONArray("results").length());
        assertEquals("https://example.org/current",result.getJSONArray("results").getJSONObject(0).getString("url"));
        assertEquals(123,result.getJSONObject("usage").getInt("inputTokens"));
        assertFalse(ProviderHostedWebSearch.normalize("openai_responses",
                new JSONObject().put("status","completed").put("output",new JSONArray())).getBoolean("ok"));
    }

    @Test public void claudeKeepsServerSearchCountAndSource()throws Exception {
        JSONObject cited=new JSONObject().put("type","text").put("text","Found it.")
                .put("citations",new JSONArray().put(new JSONObject().put("type","web_search_result_location")
                        .put("url","https://example.com/article").put("title","Article")));
        JSONObject raw=new JSONObject().put("stop_reason","end_turn")
                .put("content",new JSONArray().put(new JSONObject().put("type","server_tool_use")
                        .put("name","web_search")).put(cited))
                .put("usage",new JSONObject().put("input_tokens",40).put("output_tokens",12)
                        .put("server_tool_use",new JSONObject().put("web_search_requests",1)));
        JSONObject result=ProviderHostedWebSearch.normalize("anthropic",raw);
        assertTrue(result.getBoolean("ok"));
        assertEquals(1,result.getInt("providerSearchRequests"));
        assertEquals(12,result.getJSONObject("usage").getInt("outputTokens"));
    }

    @Test public void geminiUsesActualGroundingChunksNotInventedUrls()throws Exception {
        JSONObject web=new JSONObject().put("uri","https://example.net/news").put("title","News");
        JSONObject grounding=new JSONObject().put("webSearchQueries",new JSONArray().put("today news"))
                .put("groundingChunks",new JSONArray().put(new JSONObject().put("web",web)));
        JSONObject candidate=new JSONObject().put("finishReason","STOP")
                .put("content",new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text","Latest."))))
                .put("groundingMetadata",grounding);
        JSONObject raw=new JSONObject().put("candidates",new JSONArray().put(candidate))
                .put("usageMetadata",new JSONObject().put("promptTokenCount",101).put("candidatesTokenCount",32));
        JSONObject result=ProviderHostedWebSearch.normalize("gemini",raw);
        assertTrue(result.getBoolean("ok"));
        assertEquals(1,result.getJSONArray("results").length());
        assertEquals("Latest.",result.getString("text"));
    }
}
