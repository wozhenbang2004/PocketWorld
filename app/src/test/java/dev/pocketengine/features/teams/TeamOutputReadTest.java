package dev.pocketengine.features.teams;

import dev.pocketengine.core.ModelConfig;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Uses the real bounded coordinator and persisted member output; gateway replies are explicit fixtures. */
public final class TeamOutputReadTest {
    private ModelConfig config(){return new ModelConfig("https://example.invalid/v1","fixture-secret","default",false,20,10);}
    private JSONObject team()throws Exception{return new JSONObject().put("id","team").put("name","原文核对").put("mode","collaborate").put("members",new JSONArray()
            .put(new JSONObject().put("id","a").put("name","甲").put("role","材料").put("model","a"))
            .put(new JSONObject().put("id","b").put("name","乙").put("role","核验").put("model","b")));}
    private JSONObject readCall(String turn,int offset)throws Exception{return new JSONObject().put("tool_calls",new JSONArray().put(new JSONObject().put("id","read-original").put("type","function")
            .put("function",new JSONObject().put("name","team_read_output").put("arguments",new JSONObject().put("turnId",turn).put("offset",offset).put("length",80).toString()))));}
    private JSONObject terminal(TeamCoordinator coordinator,String id)throws Exception {
        long until=System.nanoTime()+4_000_000_000L;while(System.nanoTime()<until){JSONObject run=coordinator.get(id);if(!run.optString("status").matches("queued|running|stopping"))return run;Thread.sleep(5);}throw new AssertionError("coordinator did not settle: "+coordinator.get(id));
    }
    @Test public void synthesisCanReadTheOriginalTailByItsStableTurnId()throws Exception {
        File file=new File(Files.createTempDirectory("team-output-read-").toFile(),"runs.json");AtomicInteger summaries=new AtomicInteger();String tail="原文末尾的反证必须进入整合";
        try(TeamCoordinator coordinator=new TeamCoordinator(file,(model,messages,tools,control)->{
            if(!messages.getJSONObject(0).getString("content").contains("团队Leader"))return new JSONObject().put("content",model.model.equals("a")?"甲".repeat(8000)+tail:"乙的独立核验");
            if(summaries.incrementAndGet()==1){String preview=messages.getJSONObject(1).getString("content");assertFalse(preview.contains(tail));JSONArray outputs=new JSONArray(preview.substring(preview.indexOf('[')));return readCall(outputs.getJSONObject(0).getString("turnId"),8000);}
            JSONObject read=new JSONObject(messages.getJSONObject(messages.length()-1).getString("content"));assertEquals(tail,read.getString("content"));assertEquals(8000,read.getInt("offset"));assertEquals("a",read.getString("memberId"));
            assertFalse(read.optBoolean("externalEffect"));assertEquals(0,read.getJSONArray("grantsAdded").length());return new JSONObject().put("content","共同结论已核对原文："+read.getString("content"));
        },event->{})){
            String id=coordinator.start(team(),"核对长产出再整合",1,config()).getString("id");JSONObject run=terminal(coordinator,id);
            assertEquals("The coordinator must support reading its actual member output", "completed",run.getString("status"));assertTrue(run.getString("summary").contains(tail));assertEquals(2,summaries.get());
            JSONObject receipt=run.getJSONArray("synthesisReads").getJSONObject(0);assertEquals("a",receipt.getString("memberId"));assertEquals(8000,receipt.getInt("offset"));assertEquals(tail.length(),receipt.getInt("characters"));assertFalse(receipt.has("content"));assertFalse(run.getBoolean("businessVerified"));
        }
        try(TeamCoordinator restored=new TeamCoordinator(file,(m,p,c)->{throw new AssertionError("Read receipts never authorize automatic work");},event->{})){
            JSONObject saved=restored.list().getJSONObject(0);assertEquals(1,saved.getJSONArray("synthesisReads").length());assertTrue(saved.getString("summary").contains(tail));
        }
    }
    @Test public void foreignTurnIdCannotReadAnyOutputOrProduceASuccessfulSummary()throws Exception {
        AtomicInteger summaries=new AtomicInteger();try(TeamCoordinator coordinator=new TeamCoordinator(new File(Files.createTempDirectory("team-output-foreign-").toFile(),"runs.json"),(model,messages,tools,control)->{
            if(messages.getJSONObject(0).getString("content").contains("团队Leader")){summaries.incrementAndGet();return readCall("00000000-0000-0000-0000-000000000000",0);}return new JSONObject().put("content","实际成员产出");
        },event->{})){
            JSONObject run=terminal(coordinator,coordinator.start(team(),"不能用其他任务的turnId",1,config()).getString("id"));assertEquals("failed",run.getString("status"));assertEquals(1,summaries.get());
            assertEquals(0,run.optJSONArray("synthesisReads")==null?0:run.getJSONArray("synthesisReads").length());assertFalse(run.getBoolean("businessVerified"));
        }
    }
    @Test public void aFailedFollowingModelCallKeepsTheReadPreparedRatherThanConsumed()throws Exception {
        AtomicInteger summaries=new AtomicInteger();try(TeamCoordinator coordinator=new TeamCoordinator(new File(Files.createTempDirectory("team-output-consumption-").toFile(),"runs.json"),(model,messages,tools,control)->{
            if(!messages.getJSONObject(0).getString("content").contains("团队Leader"))return new JSONObject().put("content","原始产出");
            if(summaries.incrementAndGet()==1){String preview=messages.getJSONObject(1).getString("content");JSONArray outputs=new JSONArray(preview.substring(preview.indexOf('[')));return readCall(outputs.getJSONObject(0).getString("turnId"),0);}
            throw new java.io.IOException("fixture model did not return a usable response");
        },event->{})){
            JSONObject run=terminal(coordinator,coordinator.start(team(),"失败不冒充已消费",1,config()).getString("id"));assertEquals("failed",run.getString("status"));assertEquals(2,summaries.get());
            JSONObject read=run.getJSONArray("synthesisReads").getJSONObject(0);assertEquals("prepared",read.getString("state"));assertFalse(read.has("consumedAt"));assertFalse(read.has("content"));
        }
    }
}
