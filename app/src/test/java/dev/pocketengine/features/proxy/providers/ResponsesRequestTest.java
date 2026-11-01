package dev.pocketengine.features.proxy.providers;

import dev.pocketengine.features.proxy.accounts.GatewayFailure;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static dev.pocketengine.features.proxy.providers.ResponsesFixtures.*;

public class ResponsesRequestTest {
    static JSONArray input()throws Exception{return new JSONArray().put(new JSONObject().put("role","user").put("content","Hello"));}
    static void reject(JSONArray input,JSONArray tools,String code)throws Exception{try{new ResponsesRequest("fixture-model","",input,tools);fail();}catch(GatewayFailure failure){assertEquals(code,failure.code);}}
    @Test public void functionSchemasArePreservedIntactAndSnapshotIsImmutable()throws Exception {
        JSONArray tools=tools();JSONObject function=tools.getJSONObject(0).getJSONArray("tools").getJSONObject(0);JSONObject schema=new JSONObject("{\"type\":\"object\",\"properties\":{\"value\":{\"$ref\":\"#/$defs/City\"}},\"$defs\":{\"City\":{\"type\":\"string\",\"enum\":[\"杭州\",\"Paris\"]}},\"required\":[\"value\"],\"additionalProperties\":false}");
        function.put("parameters",schema).put("output_schema",new JSONObject().put("type","object")).put("strict",true);JSONArray input=input();ResponsesRequest request=new ResponsesRequest("fixture-model","",input,tools);schema.put("required",new JSONArray());input.getJSONObject(0).put("content","changed");tools.getJSONObject(0).put("name","changed");
        JSONObject body=new JSONObject(new String(request.body(),StandardCharsets.UTF_8));JSONObject stored=body.getJSONArray("tools").getJSONObject(0).getJSONArray("tools").getJSONObject(0);assertTrue(stored.getBoolean("strict"));assertEquals(1,stored.getJSONObject("parameters").getJSONArray("required").length());assertEquals("杭州",stored.getJSONObject("parameters").getJSONObject("$defs").getJSONObject("City").getJSONArray("enum").getString(0));assertEquals("Hello",body.getJSONArray("input").getJSONObject(0).getString("content"));assertFalse(request.toString().contains("Hello"));
    }
    @Test public void callResultIsLinkedByCallIdNotItemIdAndAllCallsNeedOneResult()throws Exception {
        JSONObject call=function("item_1","call_1","observe","{}",true);JSONArray valid=input().put(call).put(new JSONObject().put("type","function_call_output").put("call_id","call_1").put("output","{\"screen\":\"home\"}"));new ResponsesRequest("fixture-model","",valid,tools());
        reject(input().put(call),tools(),"INVALID_FUNCTION_RESULT_LINK");
        reject(input().put(call).put(new JSONObject().put("type","function_call_output").put("call_id","item_1").put("output","x")),tools(),"INVALID_FUNCTION_RESULT_LINK");
        reject(new JSONArray().put(valid.getJSONObject(2)).put(call),tools(),"INVALID_FUNCTION_RESULT_LINK");
        valid.put(valid.getJSONObject(2));reject(valid,tools(),"INVALID_FUNCTION_RESULT_LINK");
    }
    @Test public void unknownToolAndFieldsAreNotSilentlyDropped()throws Exception {
        for(String type:new String[]{"function","web_search","custom","tool_search"})reject(input(),new JSONArray().put(new JSONObject().put("type",type)),"UNSUPPORTED_TOOL_TYPE");
        JSONArray tools=tools();tools.getJSONObject(0).getJSONArray("tools").getJSONObject(0).put("defer_loading",true);reject(input(),tools,"UNSUPPORTED_PROTOCOL_FIELD");
        reject(new JSONArray().put(new JSONObject().put("type","compaction")),new JSONArray(),"UNSUPPORTED_INPUT_TYPE");
    }
    @Test public void objectSchemaDepthCountSizeAndUtf8BudgetFailBeforeNetwork()throws Exception {
        JSONArray tools=tools();JSONObject deep=new JSONObject(),node=deep;for(int i=0;i<35;i++){JSONObject next=new JSONObject();node.put("properties",next);node=next;}tools.getJSONObject(0).getJSONArray("tools").getJSONObject(0).put("parameters",deep);reject(input(),tools,"UNSUPPORTED_OR_OVERSIZED_INPUT");
        reject(new JSONArray().put(new JSONObject().put("role","user").put("content","界".repeat(100000))),new JSONArray(),"UNSUPPORTED_OR_OVERSIZED_INPUT");
        JSONArray many=new JSONArray();for(int i=0;i<129;i++)many.put(new JSONObject().put("role","user").put("content","x"));reject(many,new JSONArray(),"UNSUPPORTED_OR_OVERSIZED_INPUT");
    }
    @Test public void trailingArgumentJsonAndInvalidUtf16AreRejected()throws Exception {
        reject(input().put(function("f","c","observe","{} trailing",true)).put(new JSONObject().put("type","function_call_output").put("call_id","c").put("output","ok")),new JSONArray(),"UNSUPPORTED_OR_OVERSIZED_INPUT");
        reject(new JSONArray().put(new JSONObject().put("role","user").put("content","\ud83d")),new JSONArray(),"UNSUPPORTED_OR_OVERSIZED_INPUT");
    }
    @Test public void duplicateFunctionsAndNonStringResultsAreExplicitlyRejected()throws Exception {
        JSONArray tools=tools();tools.getJSONObject(0).getJSONArray("tools").put(declaration("observe"));reject(input(),tools,"UNSUPPORTED_OR_OVERSIZED_INPUT");
        reject(input().put(function("f","c","observe","{}",true)).put(new JSONObject().put("type","function_call_output").put("call_id","c").put("output",new JSONArray())),new JSONArray(),"UNSUPPORTED_OR_OVERSIZED_INPUT");
    }
}
