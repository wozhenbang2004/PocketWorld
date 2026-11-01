package dev.pocketengine.features.teams;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Regression from independent W05 review: an old settled receipt cannot settle a newly prepared operation. */
public class TeamExternalReconciliationTest {
    private JSONObject await(TeamCoordinator c,String id)throws Exception{long until=System.nanoTime()+4_000_000_000L;while(System.nanoTime()<until){JSONObject run=c.get(id);if(!run.optString("status").matches("running|queued|stopping"))return run;Thread.sleep(5);}throw new AssertionError(c.get(id).toString());}
    @Test public void settledAThenDispatchedBExceptionKeepsBQueryOnlyAfterMoreOwnerFeedback()throws Exception {
        AtomicInteger probes=new AtomicInteger();List<String> operations=new ArrayList<>();
        ExternalMemberDriver driver=new ExternalMemberDriver(){public JSONObject probe(JSONObject s,RunControl t)throws Exception{probes.incrementAndGet();return new JSONObject().put("available",true);}public JSONObject execute(JSONObject request,RunControl t,java.util.function.Consumer<JSONObject> e)throws Exception{
            operations.add(request.getString("operationId"));int call=operations.size();
            if(call==1){assertFalse(request.has("previousOperationId"));return new JSONObject().put("status","completed").put("content","A已完成");}
            if(call==2){assertFalse(request.has("previousOperationId"));assertNotEquals(operations.get(0),operations.get(1));throw new java.io.IOException("B dispatched; acknowledgement lost");}
            assertEquals(operations.get(1),request.getString("operationId"));assertEquals(operations.get(1),request.getString("previousOperationId"));
            return call==3?new JSONObject().put("status","unknown"):new JSONObject().put("status","completed").put("content","B的原操作回执");
        }};
        JSONObject team=new JSONObject().put("id","team").put("name","核对组").put("members",new JSONArray().put(new JSONObject().put("id","a").put("name","甲").put("role","只读分析").put("runner","codex_app_server").put("device",new JSONObject().put("profileId","computer").put("environmentId","project"))).put(new JSONObject().put("id","b").put("name","乙").put("role","核验")));
        ModelConfig config=new ModelConfig("https://example.invalid/v1","","fixture",false,20,10);
        try(TeamCoordinator c=new TeamCoordinator(Files.createTempDirectory("team-new-operation-").resolve("runs.json").toFile(),(m,p,t)->new JSONObject().put("content","实际模型产出"),e->{})){
            String id=c.start(team,"核对不同操作的结算位",1,config,UUID.randomUUID().toString(),null,s->driver).getString("id");assertEquals("completed",await(c,id).getString("status"));
            c.feedback(id,"a","开始明确的新分析B");c.resume(id,"a",config,null,s->driver);JSONObject uncertain=await(c,id);assertEquals("paused",uncertain.getString("status"));JSONObject state=uncertain.getJSONArray("members").getJSONObject(0).getJSONArray("roundStates").getJSONObject(0);assertEquals("blocked_unknown",state.getString("status"));assertFalse(state.getBoolean("externalSettled"));assertEquals(operations.get(1),state.getString("externalOperationId"));
            c.resume(id,"a",config,null,s->driver);assertEquals("paused",await(c,id).getString("status"));
            c.feedback(id,"a","B派发后新增加的要求");c.resume(id,"a",config,null,s->driver);JSONObject reconciled=await(c,id);assertEquals("paused",reconciled.getString("status"));assertEquals(4,operations.size());assertEquals(2,probes.get());assertEquals("pending",reconciled.getJSONArray("inbox").getJSONObject(1).getString("status"));
        }
    }
}
