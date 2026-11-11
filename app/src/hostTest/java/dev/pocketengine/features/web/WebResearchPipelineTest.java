package dev.pocketengine.features.web;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class WebResearchPipelineTest {
    @Test public void requireExplicitBoundedUniqueQueries()throws Exception {
        WebResearchPipeline.validate(new JSONObject().put("queries",new JSONArray().put("Java 17 security").put("Android agent"))
                .put("maxPages",2).put("maxResults",8));
        try{
            WebResearchPipeline.validate(new JSONObject().put("queries",new JSONArray().put("same").put("SAME")));
            fail("repeated query must be rejected");
        }catch(IllegalArgumentException expected){}
        try{
            WebResearchPipeline.validate(new JSONObject().put("queries",new JSONArray().put("public"))
                    .put("maxPages",6));
            fail("unbounded page reads must be rejected");
        }catch(IllegalArgumentException expected){}
        try{
            WebResearchPipeline.validate(new JSONObject().put("queries",new JSONArray().put("public"))
                    .put("credentials",new JSONObject().put("apiKey","secret")));
            fail("search cannot select provider credentials");
        }catch(SecurityException expected){}
    }
    @Test public void schemaMatchesOriginalToolLimits()throws Exception {
        JSONObject schema=WebResearchPipeline.schema();
        assertFalse(schema.getBoolean("additionalProperties"));
        JSONObject properties=schema.getJSONObject("properties");
        assertEquals(4,properties.getJSONObject("queries").getInt("maxItems"));
        assertEquals(4,properties.getJSONObject("maxPages").getInt("maximum"));
    }
}
