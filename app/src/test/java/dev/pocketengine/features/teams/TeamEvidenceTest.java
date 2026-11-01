package dev.pocketengine.features.teams;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import java.io.File;
import java.nio.file.Files;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class TeamEvidenceTest {
    private JSONObject team()throws Exception{return new JSONObject().put("id","team").put("name","研究组").put("members",new JSONArray().put(new JSONObject().put("name","研究").put("role","取证").put("model","research-model")).put(new JSONObject().put("name","审核").put("role","查证").put("model","review-model")));}
    private ModelConfig config(){return new ModelConfig("https://example.invalid/v1","fixture-secret","default-model",false,20,10);}
    private JSONObject waitDone(TeamCoordinator coordinator,String id)throws Exception {long until=System.nanoTime()+3_000_000_000L;while(System.nanoTime()<until){JSONObject run=coordinator.get(id);if(!run.optString("status").matches("running|queued|stopping"))return run;Thread.sleep(10);}throw new AssertionError("team timeout");}
    private static JSONObject call(String name)throws Exception{return new JSONObject().put("tool_calls",new JSONArray().put(new JSONObject().put("id",UUID.randomUUID().toString()).put("type","function").put("function",new JSONObject().put("name",name).put("arguments","{\"query\":\"actual fixture query\"}"))));}
    private CapabilityPort port(AtomicInteger reads)throws Exception{return new CapabilityPort(){
        public JSONArray tools()throws Exception {return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","evidence_read").put("parameters",new JSONObject().put("type","object")))).put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","unsafe_write").put("parameters",new JSONObject().put("type","object"))));}
        public boolean supports(String name){return name.equals("evidence_read")||name.equals("unsafe_write");}public boolean sideEffecting(String name){return !name.equals("evidence_read");}
        public JSONObject execute(String name,JSONObject arguments,RunControl control)throws Exception {assertEquals("evidence_read",name);assertEquals("actual fixture query",arguments.getString("query"));control.check();reads.incrementAndGet();return new JSONObject().put("ok",true).put("source","https://example.invalid/actual-fixture").put("text","observed fixture evidence fixture-secret").put("token","must not persist");}
    };}
    @Test public void membersUseTheirConfiguredModelsAndHostBoundReadOnlyEvidence()throws Exception {
        File state=new File(Files.createTempDirectory("pocket-team-evidence").toFile(),"runs.json");AtomicInteger reads=new AtomicInteger();Set<String> models=ConcurrentHashMap.newKeySet(),identities=ConcurrentHashMap.newKeySet();String operation=UUID.randomUUID().toString();
        try(TeamCoordinator coordinator=new TeamCoordinator(state,(model,messages,tools,control)->{
            models.add(model.model);if(messages.getJSONObject(0).getString("content").contains("你是团队协调者"))return new JSONObject().put("content","根据实际证据汇总");
            if(messages.getJSONObject(messages.length()-1).optString("role").equals("tool"))return new JSONObject().put("content","依据 https://example.invalid/actual-fixture 的真实fixture分析");
            assertEquals(2,tools.length());assertEquals("evidence_read",tools.getJSONObject(0).getJSONObject("function").getString("name"));assertEquals("team_send_message",tools.getJSONObject(1).getJSONObject("function").getString("name"));return call("evidence_read");
        },event->{})){
            String id=coordinator.start(team(),"比较证据",1,config(),operation,(nativeCall)->{JSONObject who=nativeCall.view();assertEquals(operation,who.getString("teamRunId"));identities.add(who.getString("memberId"));return port(reads);}).getString("id");JSONObject run=waitDone(coordinator,id);
            assertEquals("completed",run.getString("status"));assertEquals(2,reads.get());assertEquals(2,run.getInt("toolCallsUsed"));assertEquals(5,run.getInt("callsUsed"));assertEquals(2,identities.size());assertTrue(models.contains("research-model"));assertTrue(models.contains("review-model"));assertTrue(models.contains("default-model"));assertEquals(1,run.getJSONArray("members").getJSONObject(0).getJSONArray("evidence").length());assertFalse(run.toString().contains("fixture-secret"));assertFalse(run.toString().contains("must not persist"));
        }
    }
    @Test public void modelCannotInvokeToolsThatTheHostMarksAsSideEffecting()throws Exception {
        File state=new File(Files.createTempDirectory("pocket-team-write-denied").toFile(),"runs.json");AtomicInteger reads=new AtomicInteger();
        try(TeamCoordinator coordinator=new TeamCoordinator(state,(model,messages,tools,control)->call("unsafe_write"),event->{})){
            String id=coordinator.start(team(),"研究",1,config(),UUID.randomUUID().toString(),nativeCall->port(reads)).getString("id");JSONObject run=waitDone(coordinator,id);assertEquals("failed",run.getString("status"));assertEquals(0,reads.get());assertEquals(0,run.getInt("toolCallsUsed"));
        }
    }
}
