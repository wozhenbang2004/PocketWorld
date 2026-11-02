package dev.pocketengine.features.teams;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class TeamMailboxTest {
    private interface Check {boolean test(JSONObject r)throws Exception;}
    private File file()throws Exception{return new File(Files.createTempDirectory("team-mail-").toFile(),"runs.json");}
    private ModelConfig config(){return new ModelConfig("https://example.invalid/v1","fixture-secret","default",false,20,10);}
    private JSONObject team(boolean dependent)throws Exception{return new JSONObject().put("id","team").put("name","通信组").put("mode","collaborate").put("members",new JSONArray().put(new JSONObject().put("id","a").put("name","甲").put("role","取证").put("model","a")).put(new JSONObject().put("id","b").put("name","乙").put("role","验证").put("model","b").put("dependsOn",dependent?new JSONArray().put("a"):new JSONArray())));}
    private JSONObject message(String target)throws Exception{return new JSONObject().put("tool_calls",new JSONArray().put(new JSONObject().put("id","send-1").put("type","function").put("function",new JSONObject().put("name","team_send_message").put("arguments",new JSONObject().put("targetMemberId",target).put("text","需要核对的同队消息").toString()))));}
    private boolean summary(JSONArray p)throws Exception{return p.getJSONObject(0).getString("content").contains("团队Leader");}
    private JSONObject await(TeamCoordinator c,String id,Check check)throws Exception{long until=System.nanoTime()+4_000_000_000L;while(System.nanoTime()<until){JSONObject r=c.get(id);if(check.test(r))return r;Thread.sleep(5);}throw new AssertionError(c.get(id).toString());}
    private JSONObject terminal(TeamCoordinator c,String id)throws Exception{return await(c,id,r->!r.optString("status").matches("queued|running|stopping"));}

    @Test public void pausedRecipientCannotConsumeMailWithALateAnswer()throws Exception {
        AtomicInteger aCalls=new AtomicInteger(),bCalls=new AtomicInteger();CountDownLatch reading=new CountDownLatch(1),late=new CountDownLatch(1);
        try(TeamCoordinator c=new TeamCoordinator(file(),(m,p,tools,t)->{
            if(summary(p))return new JSONObject().put("content","汇总");
            if(m.model.equals("a")&&aCalls.incrementAndGet()==1)return message("b");
            if(m.model.equals("b")&&bCalls.incrementAndGet()==1){reading.countDown();late.await(2,TimeUnit.SECONDS);return new JSONObject().put("content","迟到回复");}return new JSONObject().put("content","新轮实际回复");
        },e->{})){
            String id=c.start(team(true),"暂停中的消息",1,config()).getString("id");assertTrue(reading.await(1,TimeUnit.SECONDS));c.pause(id,"b");late.countDown();JSONObject paused=terminal(c,id);assertEquals("unread",paused.getJSONArray("mailbox").getJSONObject(0).getString("status"));assertFalse(paused.toString().contains("迟到回复"));
            c.resume(id,"b",config(),null,null);JSONObject done=await(c,id,r->r.optString("status").equals("completed"));JSONObject letter=done.getJSONArray("mailbox").getJSONObject(0);assertEquals("consumed",letter.getString("status"));assertEquals(2,letter.getJSONArray("deliveries").length());assertEquals("interrupted",letter.getJSONArray("deliveries").getJSONObject(0).getString("state"));assertEquals("consumed",letter.getJSONArray("deliveries").getJSONObject(1).getString("state"));
        }finally{late.countDown();}
    }

    @Test public void lateMailNeedsExactOwnerContinuationAndDoesNotWakeCompletedMember()throws Exception {
        AtomicInteger aCalls=new AtomicInteger(),bCalls=new AtomicInteger();CountDownLatch bDone=new CountDownLatch(1);
        try(TeamCoordinator c=new TeamCoordinator(file(),(m,p,tools,t)->{
            if(summary(p))return new JSONObject().put("content","汇总");
            if(m.model.equals("b")){bCalls.incrementAndGet();bDone.countDown();return new JSONObject().put("content","乙的真实回复");}
            if(aCalls.incrementAndGet()==1){assertTrue(bDone.await(1,TimeUnit.SECONDS));Thread.sleep(30);return message("b");}return new JSONObject().put("content","甲完成");
        },e->{})){
            String id=c.start(team(false),"稍后通信",1,config()).getString("id");JSONObject done=await(c,id,r->r.optString("status").equals("completed"));assertEquals(1,bCalls.get());assertEquals(1,done.getInt("unreadPeerMessages"));JSONObject b=done.getJSONArray("members").getJSONObject(1);
            assertThrows(IllegalStateException.class,()->c.continueMessages(id,"b",done.getLong("revision")-1,b.getInt("generation"),config(),null,null));assertEquals(1,bCalls.get());
            c.continueMessages(id,"b",done.getLong("revision"),b.getInt("generation"),config(),null,null);JSONObject consumed=await(c,id,r->r.optString("status").equals("completed")&&r.optInt("unreadPeerMessages")==0);assertEquals(id,consumed.getString("id"));assertEquals(2,bCalls.get());assertEquals(2,aCalls.get());
            c.cancel(id);assertThrows(IllegalStateException.class,()->c.resume(id,null,config(),null,null));
        }
    }

    @Test public void unknownExternalReceiptKeepsMailUnreadAndQueriesOnlyOriginalOperation()throws Exception {
        AtomicInteger aCalls=new AtomicInteger(),external=new AtomicInteger();AtomicReference<String> op=new AtomicReference<>();
        ExternalMemberDriver driver=new ExternalMemberDriver(){public JSONObject probe(JSONObject slot,RunControl t)throws Exception{return new JSONObject().put("available",true);}public JSONObject execute(JSONObject request,RunControl t,java.util.function.Consumer<JSONObject> events)throws Exception{
            assertTrue(request.getString("prompt").contains("需要核对的同队消息"));if(external.incrementAndGet()==1){op.set(request.getString("operationId"));assertFalse(request.has("previousOperationId"));return new JSONObject().put("status","unknown");}assertEquals(op.get(),request.getString("previousOperationId"));assertEquals(op.get(),request.getString("operationId"));return new JSONObject().put("status","completed").put("content","核对到原操作真实完成");
        }};
        try(TeamCoordinator c=new TeamCoordinator(file(),(m,p,tools,t)->summary(p)?new JSONObject().put("content","汇总"):aCalls.incrementAndGet()==1?message("b"):new JSONObject().put("content","甲完成"),e->{})){
            JSONObject definition=team(true);definition.getJSONArray("members").getJSONObject(1).put("runner","codex_app_server").put("device",new JSONObject().put("profileId","computer").put("environmentId","project"));String id=c.start(definition,"未知结果",1,config(),UUID.randomUUID().toString(),null,s->driver).getString("id");JSONObject unknown=terminal(c,id);assertEquals("paused",unknown.getString("status"));assertEquals("unread",unknown.getJSONArray("mailbox").getJSONObject(0).getString("status"));
            c.resume(id,"b",config(),null,s->driver);JSONObject done=await(c,id,r->r.optString("status").equals("completed"));assertEquals(2,external.get());assertEquals("consumed",done.getJSONArray("mailbox").getJSONObject(0).getString("status"));
        }
    }

    @Test public void unsupportedDependencyAndForeignRecipientCannotCreateFakeSuccess()throws Exception {
        AtomicInteger bCalls=new AtomicInteger();try(TeamCoordinator c=new TeamCoordinator(file(),(m,p,tools,t)->{if(m.model.equals("b"))bCalls.incrementAndGet();return new JSONObject().put("content","fixture");},e->{})){
            JSONObject definition=team(true);definition.getJSONArray("members").getJSONObject(0).put("runner","kimi");JSONObject failed=terminal(c,c.start(definition,"无可用前置",1,config()).getString("id"));assertEquals(0,bCalls.get());assertEquals("blocked_dependency",failed.getJSONArray("members").getJSONObject(1).getString("status"));assertNotEquals("completed",failed.getString("status"));
        }
        try(TeamCoordinator c=new TeamCoordinator(file(),(m,p,tools,t)->message("foreign-member"),e->{})){JSONObject failed=terminal(c,c.start(team(true),"外队身份",1,config()).getString("id"));assertEquals(0,failed.getJSONArray("mailbox").length());assertNotEquals("completed",failed.getString("status"));}
    }

    @Test public void overBudgetExternalPromptNeverDispatchesOrConsumesPeerMail()throws Exception {
        AtomicInteger aCalls=new AtomicInteger(),dispatch=new AtomicInteger();ExternalMemberDriver driver=new ExternalMemberDriver(){public JSONObject probe(JSONObject s,RunControl t)throws Exception{return new JSONObject().put("available",true);}public JSONObject execute(JSONObject r,RunControl t,java.util.function.Consumer<JSONObject> e)throws Exception{dispatch.incrementAndGet();return new JSONObject().put("status","completed").put("content","must not dispatch");}};
        try(TeamCoordinator c=new TeamCoordinator(file(),(m,p,tools,t)->summary(p)?new JSONObject().put("content","部分汇总"):aCalls.incrementAndGet()==1?message("b"):new JSONObject().put("content","x".repeat(8000)),e->{})){
            JSONObject definition=team(true);definition.getJSONArray("members").getJSONObject(1).put("runner","codex_app_server").put("device",new JSONObject().put("profileId","computer").put("environmentId","project"));JSONObject stopped=terminal(c,c.start(definition,"g".repeat(8000),1,config(),UUID.randomUUID().toString(),null,s->driver).getString("id"));assertEquals(0,dispatch.get());assertEquals("unread",stopped.getJSONArray("mailbox").getJSONObject(0).getString("status"));assertFalse(stopped.getJSONArray("members").getJSONObject(1).getJSONArray("roundStates").getJSONObject(0).has("externalOperationId"));
        }
    }
    @Test public void unreadMailAndLocalSessionSurviveReopenWithoutAnyAutomaticWork()throws Exception {
        File state=file();AtomicInteger aCalls=new AtomicInteger();CountDownLatch bDone=new CountDownLatch(1);String id,session,message;
        try(TeamCoordinator c=new TeamCoordinator(state,(m,p,tools,t)->{if(summary(p))return new JSONObject().put("content","汇总");if(m.model.equals("b")){bDone.countDown();return new JSONObject().put("content","乙完成");}if(aCalls.incrementAndGet()==1){bDone.await(1,TimeUnit.SECONDS);Thread.sleep(30);return message("b");}return new JSONObject().put("content","甲完成");},e->{})){
            id=c.start(team(false),"恢复未读",1,config()).getString("id");JSONObject done=await(c,id,r->r.optString("status").equals("completed"));session=done.getJSONArray("members").getJSONObject(1).getString("localSessionId");message=done.getJSONArray("mailbox").getJSONObject(0).getString("id");
        }
        AtomicInteger calls=new AtomicInteger();try(TeamCoordinator c=new TeamCoordinator(state,(m,p,t)->{calls.incrementAndGet();return new JSONObject().put("content","主人继续后的真实回复");},e->{})){
            JSONObject restored=c.get(id);assertEquals(0,calls.get());assertEquals("unread",restored.getJSONArray("mailbox").getJSONObject(0).getString("status"));assertEquals(session,restored.getJSONArray("members").getJSONObject(1).getString("localSessionId"));assertEquals(message,restored.getJSONArray("mailbox").getJSONObject(0).getString("id"));
            c.continueMessages(id,"b",restored.getLong("revision"),restored.getJSONArray("members").getJSONObject(1).getInt("generation"),config(),null,null);JSONObject done=await(c,id,r->r.optString("status").equals("completed")&&r.optInt("unreadPeerMessages")==0);assertEquals(2,calls.get());assertEquals(id,done.getString("operationId"));
        }
    }
    @Test public void moreThanTwoToolCallsCannotQueueAnyMessage()throws Exception {
        try(TeamCoordinator c=new TeamCoordinator(file(),(m,p,tools,t)->{JSONObject response=message("b");JSONArray calls=response.getJSONArray("tool_calls");JSONObject second=new JSONObject(calls.getJSONObject(0).toString()).put("id","send-2"),third=new JSONObject(calls.getJSONObject(0).toString()).put("id","send-3");calls.put(second).put(third);return response;},e->{})){
            JSONObject run=terminal(c,c.start(team(true),"预算",1,config()).getString("id"));assertEquals(0,run.getJSONArray("mailbox").length());assertEquals(0,run.getInt("toolCallsUsed"));assertNotEquals("completed",run.getString("status"));
        }
    }
}
