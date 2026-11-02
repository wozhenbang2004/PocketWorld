package dev.pocketengine.runtime;

import dev.pocketengine.core.*;
import dev.pocketengine.runtime.history.RecentScreenHistory;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real loopback request bodies from the original Runner, with an explicit native pixel fixture. */
public final class KernelHistoryRunnerTest {
    private static final String PNG="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aQZkAAAAASUVORK5CYII=";
    private static JSONObject response(String name,JSONObject args)throws Exception {
        JSONObject message=new JSONObject().put("role","assistant");if(name==null)message.put("content","已对照实际提供的历史画面；没有执行点击");else message.put("tool_calls",new JSONArray().put(new JSONObject().put("id","c-"+java.util.UUID.randomUUID()).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",args.toString()))));return new JSONObject().put("model","fixture-model").put("choices",new JSONArray().put(new JSONObject().put("index",0).put("finish_reason",name==null?"stop":"tool_calls").put("message",message)));
    }
    private static JSONObject lastTool(JSONObject request)throws Exception {JSONArray rows=request.getJSONArray("messages");for(int i=rows.length()-1;i>=0;i--)if("tool".equals(rows.getJSONObject(i).optString("role")))return new JSONObject(rows.getJSONObject(i).getString("content"));throw new AssertionError("No actual tool receipt");}
    @Test public void recalledPixelsActuallyReachTheRequestAndHistoricalIdsCannotClick()throws Exception {
        RunControl original=new RunControl();original.bindHostRun("task","run",1);int[] writes={0};String[] recalled={null};
        PhonePort phone=new PhonePort(){public boolean isConnected(){return true;}public JSONObject observe(boolean pixels,RunControl current)throws Exception{assertSame(original,current);current.check();assertTrue(pixels);return new JSONObject().put("ok",true).put("observationId","current-live-reference").put("packageName","app.example").put("imageBase64",PNG).put("imageMimeType","image/png").put("nodes",new JSONArray().put(new JSONObject().put("id","n").put("text","搜索").put("clickable",true)));}public JSONObject execute(String name,JSONObject args,RunControl current){writes[0]++;throw new AssertionError("Historical id must never reach a physical driver");}public void clearReferences(){}};
        KernelRunnerContractTest.Journal journal=new KernelRunnerContractTest.Journal();
        try(KernelRunnerContractTest.Wire wire=new KernelRunnerContractTest.Wire((request,index)->{
            if(index==0)return response(RecentScreenHistory.LIST,new JSONObject());
            if(index==1){JSONObject listing=lastTool(request);recalled[0]=listing.getJSONArray("screens").getJSONObject(0).getString("screenId");return response(RecentScreenHistory.VIEW,new JSONObject().put("screenId",recalled[0]));}
            if(index==2){assertFalse(lastTool(request).getBoolean("actualImageAttached"));boolean attached=false;JSONArray rows=request.getJSONArray("messages");for(int i=0;i<rows.length();i++){JSONArray content=rows.getJSONObject(i).optJSONArray("content");if(content==null||content.length()<2||!content.getJSONObject(0).optString("text").startsWith(RecentScreenHistory.PREFIX))continue;String url=content.getJSONObject(1).getJSONObject("image_url").getString("url");assertTrue(url.startsWith("data:image/png;base64,"));assertArrayEquals(Base64.getDecoder().decode(PNG),Base64.getDecoder().decode(url.substring(url.indexOf(',')+1)));attached=true;}assertTrue("An id-only receipt is not image delivery",attached);return response("phone_click_node",new JSONObject().put("observationId",recalled[0]).put("nodeId","n"));}
            assertEquals("stale_observation",lastTool(request).getString("code"));return response(null,null);
        })){
            ModelConfig base=wire.config();ModelConfig visual=new ModelConfig(base.baseUrl,"",base.model,true,8,10,base.provider,base.maxOutputTokens).withCleartextConsent(base.cleartextConsent).withOwnerImageInput(true);
            RunOutcome outcome=new AgentRunner(phone,null,selected->null).run("task","帮我对照画面，再点击搜索",visual,original,event->{},journal);assertEquals("needs_confirmation",outcome.status);assertEquals(4,wire.sent.size());assertEquals(0,writes[0]);assertEquals(0,journal.begins);assertTrue(recalled[0].startsWith("screen-"));
        }
    }
}
