package dev.pocketengine.features.proxy.providers;

import dev.pocketengine.features.proxy.accounts.GatewayFailure;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ResponsesFunctionLimitTest {
    private JSONArray tools(int count)throws Exception {
        JSONArray functions=new JSONArray();for(int i=0;i<count;i++)functions.put(ResponsesFixtures.declaration("host_tool_"+i));
        return new JSONArray().put(new JSONObject().put("type","namespace").put("name","pocketworld").put("description","Native host tool fixture").put("tools",functions));
    }
    @Test public void actualHostSizeAndLocalLimitPreserveEveryDeclaration()throws Exception {
        for(int count:new int[]{36,64}){
            ResponsesRequest request=new ResponsesRequest("fixture-model","",new JSONArray().put(new JSONObject().put("role","user").put("content","x")),tools(count));
            JSONObject body=new JSONObject(new String(request.body(),java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(count,body.getJSONArray("tools").getJSONObject(0).getJSONArray("tools").length());
        }
    }
    @Test public void excessFunctionsAndOversizedSchemasFailInsteadOfTruncating()throws Exception {
        try{new ResponsesRequest("fixture-model","",new JSONArray().put(new JSONObject().put("role","user").put("content","x")),tools(65));fail();}catch(GatewayFailure expected){assertEquals("UNSUPPORTED_OR_OVERSIZED_INPUT",expected.code);}
        JSONArray large=tools(1);large.getJSONObject(0).getJSONArray("tools").getJSONObject(0).getJSONObject("parameters").put("description","x".repeat(ResponsesRequest.MAX_REQUEST_BYTES));
        try{new ResponsesRequest("fixture-model","",new JSONArray().put(new JSONObject().put("role","user").put("content","x")),large);fail();}catch(GatewayFailure expected){assertEquals("UNSUPPORTED_OR_OVERSIZED_INPUT",expected.code);}
        assertEquals(128,ResponsesRequest.MAX_ITEMS);assertEquals(256*1024,ResponsesRequest.MAX_REQUEST_BYTES);
    }
}
