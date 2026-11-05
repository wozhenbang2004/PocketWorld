package dev.pocketengine.runtime.providers;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.context.ContextWindow;
import dev.pocketengine.runtime.models.*;
import java.net.URI;
import java.nio.file.Files;
import java.time.Clock;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static dev.pocketengine.runtime.models.ModelFacts.*;

/** Actual cross-protocol preparation and catalog admission, without network or execution. */
public final class ProtocolHistoryBudgetTest {
    private static JSONObject j(Object... pairs)throws Exception{JSONObject row=new JSONObject();for(int i=0;i<pairs.length;i+=2)row.put((String)pairs[i],pairs[i+1]);return row;}
    private static JSONObject request(String ingress)throws Exception {
        JSONArray rows=new JSONArray().put(j("role","user","content","Read both records."));
        if(ingress.equals("openai_chat")){
            JSONArray calls=new JSONArray();for(int i=0;i<2;i++)calls.put(j("id","call_"+i,"type","function","function",j("name","read_record","arguments",j("record",i).toString())));
            rows.put(j("role","assistant","content","I will read both.","tool_calls",calls));
            for(int i=0;i<2;i++)rows.put(j("role","tool","tool_call_id","call_"+i,"content","record-"+i));
        }else{
            JSONArray parts=new JSONArray().put(j("type","text","text","I will read both."));
            for(int i=0;i<2;i++)parts.put(j("type","tool_use","id","call_"+i,"name","read_record","input",j("record",i)));
            rows.put(j("role","assistant","content",parts));JSONArray results=new JSONArray();
            for(int i=0;i<2;i++)results.put(j("type","tool_result","tool_use_id","call_"+i,"content","record-"+i));
            results.put(j("type","text","text","Please compare the results."));rows.put(j("role","user","content",results));
        }
        return j("model","fixture-model","max_tokens",256,"messages",rows);
    }
    private static ModelConfig configured(String egress,long window,boolean tools)throws Exception {
        ModelCatalog catalog=new ModelCatalog(Files.createTempDirectory("protocol-history-").resolve("catalog.json"),Clock.systemUTC());
        ModelBinding binding=new ModelBinding("primary","fixture",ModelCatalogRoutes.protocol(egress),ModelCatalogRoutes.defaultFormat(egress),URI.create("https://example.invalid/v1/models"),"fixture-credential",1,"fixture-installation",1,false);catalog.bind(binding);
        ModelCatalog.ModelKey key=new ModelCatalog.ModelKey(binding,"fixture-model");
        catalog.updateOwner(key,catalog.get(key,3600000).ownerRevision(),Map.of(Field.TOOL_CALLS,OwnerValue.known(tools),Field.CONTEXT_WINDOW,OwnerValue.known(new TokenWindow(window,WindowKind.INPUT_AND_OUTPUT))),Set.of());
        ModelRequestPolicy.installHostSource(selected->catalog.get(new ModelCatalog.ModelKey(selected.capabilityBinding,selected.model),3600000));
        return new ModelConfig("https://example.invalid/v1","fixture-key","fixture-model",false,4,10,egress,512).withNativeCatalog(binding,ReasoningOptions.DEFAULT);
    }
    private static void validRound(String ingress)throws Exception {
        String egress=ingress.equals("openai_chat")?"anthropic":"openai_responses";JSONObject original=request(ingress);String exact=original.toString();
        GatewayRequest admitted=ProtocolCodec.prepare(configured(egress,64000,true),ingress,original);
        assertEquals(exact,original.toString());admitted.revalidate();
        assertTrue(ContextWindow.plan(admitted.messages,admitted.tools,ContextWindow.Budget.localBytes(1048576),ContextWindow.Retention.preserveAll()).canSend());
        assertEquals(2,admitted.messages.getJSONObject(1).getJSONArray("tool_calls").length());
        JSONObject wire=admitted.wire;
        if(egress.equals("anthropic")){
            JSONArray messages=wire.getJSONArray("messages"),parts=messages.getJSONObject(1).getJSONArray("content");assertEquals(3,parts.length());assertEquals("I will read both.",parts.getJSONObject(0).getString("text"));
            for(int i=0;i<2;i++){assertEquals("call_"+i,parts.getJSONObject(i+1).getString("id"));assertEquals("call_"+i,messages.getJSONObject(i+2).getJSONArray("content").getJSONObject(0).getString("tool_use_id"));}
        }else{
            JSONArray input=wire.getJSONArray("input");assertEquals(7,input.length());
            for(int i=0;i<2;i++){assertEquals("function_call",input.getJSONObject(i+2).getString("type"));assertEquals("call_"+i,input.getJSONObject(i+2).getString("call_id"));assertEquals("function_call_output",input.getJSONObject(i+4).getString("type"));assertEquals("record-"+i,input.getJSONObject(i+4).getString("output"));}
            assertEquals("Please compare the results.",input.getJSONObject(6).getJSONArray("content").getJSONObject(0).getString("text"));
        }
    }
    @Test public void chatParallelCallHistorySurvivesKnownContextAdmission()throws Exception{validRound("openai_chat");}
    @Test public void messagesParallelCallHistoryAndTrailingTextSurviveKnownContextAdmission()throws Exception{validRound("anthropic");}
    @Test public void knownContextAndToolCapabilityStillRejectWithoutDroppingHistory()throws Exception{
        for(String ingress:List.of("openai_chat","anthropic")){
            String egress=ingress.equals("openai_chat")?"anthropic":"openai_responses";JSONObject original=request(ingress);String exact=original.toString();
            ModelRequestPolicy.Rejected budget=assertThrows(ModelRequestPolicy.Rejected.class,()->ProtocolCodec.prepare(configured(egress,700,true),ingress,original));assertEquals("context_budget_exceeded",budget.code);
            ModelRequestPolicy.Rejected tools=assertThrows(ModelRequestPolicy.Rejected.class,()->ProtocolCodec.prepare(configured(egress,64000,false),ingress,original));assertEquals("tool_capability_unsupported",tools.code);assertEquals(exact,original.toString());
        }
    }
    @Test public void duplicateOrUnansweredCallsRemainRejectedBeforeBudgetProjection()throws Exception{
        for(String ingress:List.of("openai_chat","anthropic")){
            JSONObject missing=request(ingress);JSONArray rows=missing.getJSONArray("messages");
            if(ingress.equals("openai_chat"))rows.remove(rows.length()-1);else rows.getJSONObject(2).getJSONArray("content").remove(1);
            assertEquals("unanswered_tool_call",assertThrows(ProtocolCodec.Rejected.class,()->ProtocolCodec.decode(ingress,missing)).code);
            JSONObject duplicate=request(ingress);JSONObject assistant=duplicate.getJSONArray("messages").getJSONObject(1);
            if(ingress.equals("openai_chat"))assistant.getJSONArray("tool_calls").getJSONObject(1).put("id","call_0");else assistant.getJSONArray("content").getJSONObject(2).put("id","call_0");
            assertEquals("duplicate_or_misplaced_tool_call",assertThrows(ProtocolCodec.Rejected.class,()->ProtocolCodec.decode(ingress,duplicate)).code);
        }
    }

}
