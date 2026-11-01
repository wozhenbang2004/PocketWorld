package dev.pocketengine.features.teams;

import dev.pocketengine.core.ModelConfig;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real in-process coordinator fixtures; no provider, device or external business calls. */
public class TeamProgressionTest {
    private ModelConfig config(){return new ModelConfig("https://example.invalid/v1","fixture-secret","default",false,20,10);}
    private File file()throws Exception{return new File(Files.createTempDirectory("team-progression-").toFile(),"runs.json");}
    private JSONObject slot(String id,String depends)throws Exception{return new JSONObject().put("id",id).put("name",id).put("role","职责 "+id).put("assignment","交付 "+id).put("acceptance","可核对依据 "+id).put("model",id).put("dependsOn",depends.isEmpty()?new JSONArray():new JSONArray().put(depends));}
    private JSONObject team()throws Exception{return new JSONObject().put("id","team").put("name","分工协作").put("mode","collaborate").put("members",new JSONArray().put(slot("a","")).put(slot("b","a")));}
    private boolean synthesis(JSONArray messages)throws Exception{return messages.getJSONObject(0).getString("content").contains("团队Leader");}
    private JSONObject await(TeamCoordinator c,String id)throws Exception{long until=System.nanoTime()+4_000_000_000L;while(System.nanoTime()<until){JSONObject run=c.get(id);if(!run.optString("status").matches("queued|running|stopping"))return run;Thread.sleep(5);}throw new AssertionError(c.get(id).toString());}
    private JSONObject call(String tool,JSONObject args)throws Exception{return new JSONObject().put("tool_calls",new JSONArray().put(new JSONObject().put("id","peer-call").put("type","function").put("function",new JSONObject().put("name",tool).put("arguments",args.toString()))));}

    @Test public void ownerAssignmentAndDependenciesSurviveNormalization()throws Exception {
        JSONObject normalized=AgentSlot.normalize(slot("b","a"),1);
        assertEquals("交付 b",normalized.getString("assignment"));
        assertEquals("可核对依据 b",normalized.getString("acceptance"));
        assertEquals("a",normalized.getJSONArray("dependsOn").getString(0));
    }

    @Test public void cyclicAndUnknownDependenciesAreRejectedBeforeAnyModelCall()throws Exception {
        AtomicInteger calls=new AtomicInteger();try(TeamCoordinator c=new TeamCoordinator(file(),(m,p,t)->{calls.incrementAndGet();return new JSONObject().put("content","fixture");},e->{})){
            JSONObject cycle=team();cycle.getJSONArray("members").getJSONObject(0).put("dependsOn",new JSONArray().put("b"));
            assertThrows(IllegalArgumentException.class,()->c.start(cycle,"循环",1,config()));
            JSONObject missing=team();missing.getJSONArray("members").getJSONObject(1).put("dependsOn",new JSONArray().put("outside-run"));
            assertThrows(IllegalArgumentException.class,()->c.start(missing,"未知依赖",1,config()));
            assertEquals(0,calls.get());assertEquals(0,c.list().length());
        }
    }

    @Test public void dependentSeesOnlyActualCompletedPrerequisiteOutput()throws Exception {
        AtomicInteger produced=new AtomicInteger();try(TeamCoordinator c=new TeamCoordinator(file(),(m,p,t)->{
            if(synthesis(p))return new JSONObject().put("content","真实汇总");
            if(m.model.equals("a")){Thread.sleep(40);produced.incrementAndGet();return new JSONObject().put("content","确实产生的前置成果");}
            assertEquals(1,produced.get());assertTrue(p.toString().contains("确实产生的前置成果"));assertTrue(p.toString().contains("交付 b"));return new JSONObject().put("content","基于前置完成的成果");
        },e->{})){
            JSONObject run=await(c,c.start(team(),"有依赖地推进",1,config()).getString("id"));assertEquals("completed",run.getString("status"));
            assertEquals("a",run.getJSONObject("board").getJSONArray("cards").getJSONObject(1).getJSONArray("dependsOn").getString(0));
        }
    }

    @Test public void failedPrerequisiteNeverRunsOrCompletesDependent()throws Exception {
        AtomicInteger dependentCalls=new AtomicInteger();try(TeamCoordinator c=new TeamCoordinator(file(),(m,p,t)->{
            if(synthesis(p))return new JSONObject().put("content","未完成项明确保留");
            if(m.model.equals("a"))throw new java.io.IOException("fixture unavailable");dependentCalls.incrementAndGet();return new JSONObject().put("content","不应调用");
        },e->{})){
            JSONObject run=await(c,c.start(team(),"前置失败",1,config()).getString("id"));assertEquals(0,dependentCalls.get());assertNotEquals("completed",run.getString("status"));
            assertEquals("blocked_dependency",run.getJSONArray("members").getJSONObject(1).getString("status"));
        }
    }

    @Test public void peerMessageHasHostSenderAndIsConsumedOnlyBySuccessfulRecipient()throws Exception {
        AtomicInteger aCalls=new AtomicInteger();try(TeamCoordinator c=new TeamCoordinator(file(),(m,p,tools,t)->{
            if(synthesis(p))return new JSONObject().put("content","汇总");
            if(m.model.equals("a")&&aCalls.incrementAndGet()==1)return call("team_send_message",new JSONObject().put("targetMemberId","b").put("text","请核对真实来源"));
            if(m.model.equals("b"))assertTrue(p.toString().contains("请核对真实来源"));return new JSONObject().put("content","真实成员结果");
        },e->{})){
            JSONObject run=await(c,c.start(team(),"成员通信",1,config(),UUID.randomUUID().toString()).getString("id"));
            assertEquals("completed",run.getString("status"));JSONObject letter=run.getJSONArray("mailbox").getJSONObject(0);
            assertEquals("a",letter.getString("fromMemberId"));assertEquals("b",letter.getString("targetMemberId"));assertEquals("consumed",letter.getString("status"));assertFalse(letter.getString("senderTurnId").isEmpty());
            assertEquals(1,letter.getJSONArray("deliveries").length());assertEquals("consumed",letter.getJSONArray("deliveries").getJSONObject(0).getString("state"));
        }
    }

    @Test public void modelCannotForgeMessageSenderOrForeignRecipient()throws Exception {
        try(TeamCoordinator c=new TeamCoordinator(file(),(m,p,tools,t)->call("team_send_message",new JSONObject().put("targetMemberId","b").put("text","payload").put("fromMemberId","owner")),e->{})){
            JSONObject run=await(c,c.start(team(),"拒绝身份注入",1,config()).getString("id"));assertNotEquals("completed",run.getString("status"));assertEquals(0,run.getJSONArray("mailbox").length());
        }
    }
    @Test public void revisingAPrerequisiteInvalidatesAlreadyDerivedOutputAndExactReview()throws Exception {
        AtomicInteger aCalls=new AtomicInteger(),bCalls=new AtomicInteger();try(TeamCoordinator c=new TeamCoordinator(file(),(m,p,t)->{
            if(synthesis(p))return new JSONObject().put("content","汇总");if(m.model.equals("a"))return new JSONObject().put("content","前置版本 "+aCalls.incrementAndGet());bCalls.incrementAndGet();return new JSONObject().put("content","派生成果");
        },e->{})){
            String id=c.start(team(),"修订依赖",1,config()).getString("id");JSONObject first=await(c,id);assertEquals("completed",first.getString("status"));JSONObject paused=c.feedback(id,"a","请修订前置事实");assertTrue(paused.getJSONArray("members").getJSONObject(1).getBoolean("paused"));assertTrue(paused.getBoolean("summaryStale"));
            JSONObject stale=new JSONObject().put("runId",id).put("expectedRevision",first.getLong("revision")).put("expectedGeneration",first.getInt("generation")).put("memberId","b").put("expectedMemberGeneration",first.getJSONArray("members").getJSONObject(1).getInt("generation"));assertThrows(IllegalStateException.class,()->c.reviewedControl("resume",stale,config(),null,null));
            c.resume(id,null,config(),null,null);JSONObject revised=await(c,id);assertEquals("completed",revised.getString("status"));assertEquals(2,aCalls.get());assertEquals(2,bCalls.get());JSONObject dependency=revised.getJSONArray("members").getJSONObject(1).getJSONArray("roundStates").getJSONObject(0).getJSONArray("dependencyOutputs").getJSONObject(0);assertEquals("前置版本 2",dependency.getString("content"));assertTrue(dependency.getInt("generation")>0);
        }
    }
}
