package dev.pocketengine.features.teams;

import dev.pocketengine.core.CapabilityPort;
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

public class TeamCollaborationTest {
    private interface Condition {boolean accepts(JSONObject run)throws Exception;}
    private ModelConfig config(){return new ModelConfig("https://example.invalid/v1","fixture-secret","default",false,20,10);}
    private JSONObject team()throws Exception{return new JSONObject().put("id","team").put("name","协作组").put("members",new JSONArray().put(new JSONObject().put("id","a").put("name","甲").put("role","分析").put("model","model-a")).put(new JSONObject().put("id","b").put("name","乙").put("role","核验").put("model","model-b")));}
    private File state()throws Exception{return new File(Files.createTempDirectory("team-collaboration").toFile(),"runs.json");}
    private JSONObject await(TeamCoordinator coordinator,String id,Condition condition)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(System.nanoTime()<until){JSONObject run=coordinator.get(id);if(condition.accepts(run))return run;Thread.sleep(5);}throw new AssertionError("team state timeout: "+coordinator.get(id));}
    private JSONObject member(JSONObject run,int i)throws Exception{return run.getJSONArray("members").getJSONObject(i);}
    private boolean synthesis(JSONArray messages)throws Exception{return messages.getJSONObject(0).getString("content").contains("团队协调者")||messages.getJSONObject(0).getString("content").contains("团队Leader");}

    @Test public void twoRoundTranscriptKeepsEveryAnswerAndEvidenceAfterRestart()throws Exception {
        File file=state();AtomicInteger reads=new AtomicInteger();String id;
        CapabilityPort evidence=new CapabilityPort(){public JSONArray tools()throws Exception{return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","evidence_read").put("parameters",new JSONObject().put("type","object")))) ;}public boolean supports(String name){return name.equals("evidence_read");}public boolean sideEffecting(String name){return false;}public JSONObject execute(String name,JSONObject args,RunControl control)throws Exception{control.check();return new JSONObject().put("source","fixture://observed/"+reads.incrementAndGet()).put("text","observed evidence");}};
        try(TeamCoordinator coordinator=new TeamCoordinator(file,(model,messages,tools,control)->{
            if(synthesis(messages))return new JSONObject().put("content","共同成果");
            if(messages.getJSONObject(messages.length()-1).optString("role").equals("tool"))return new JSONObject().put("content",model.model+" "+messages.getJSONObject(messages.length()-1).getString("content"));
            assertTrue(messages.getJSONObject(0).getString("content").contains("共同成果"));
            return new JSONObject().put("tool_calls",new JSONArray().put(new JSONObject().put("id",UUID.randomUUID().toString()).put("type","function").put("function",new JSONObject().put("name","evidence_read").put("arguments","{}"))));
        },event->{})){
            JSONObject definition=team().put("mode","collaborate").put("leaderId","b");id=coordinator.start(definition,"形成共同结论",2,config(),UUID.randomUUID().toString(),(task,run,member)->evidence).getString("id");
            JSONObject run=await(coordinator,id,r->r.optString("status").equals("completed"));assertEquals("collaborate",run.getString("mode"));assertEquals("b",run.getString("leaderId"));assertEquals(4,reads.get());
            int answers=0,observations=0;java.util.Set<String> messages=new java.util.HashSet<>();JSONArray transcript=run.getJSONArray("transcript");for(int i=0;i<transcript.length();i++){JSONObject message=transcript.getJSONObject(i);assertTrue(messages.add(message.getString("messageId")));if(message.optString("kind").equals("answer"))answers++;if(message.optString("kind").equals("evidence")){observations++;assertEquals(1,message.getJSONArray("evidenceIds").length());}}
            assertEquals(4,answers);assertEquals(4,observations);assertEquals(4,run.getJSONObject("board").getJSONArray("cards").length());for(int i=0;i<2;i++){assertEquals(2,member(run,i).getJSONArray("evidence").length());assertEquals(1,member(run,i).getJSONArray("evidence").getJSONObject(0).getInt("round"));assertEquals(2,member(run,i).getJSONArray("evidence").getJSONObject(1).getInt("round"));assertEquals("completed",member(run,i).getJSONArray("roundStates").getJSONObject(0).getString("status"));}
        }
        try(TeamCoordinator restored=new TeamCoordinator(file,(m,p,c)->{throw new AssertionError("no replay");},e->{})){assertEquals(10,restored.get(id).getJSONArray("transcript").length());assertEquals("completed",restored.get(id).getString("status"));}
    }

    @Test public void directedFeedbackPausesOnlyItsMemberAndConsumesFifoOnlyAfterFreshAnswer()throws Exception {
        CountDownLatch started=new CountDownLatch(1),late=new CountDownLatch(1);AtomicInteger aCalls=new AtomicInteger(),bCalls=new AtomicInteger();AtomicReference<RunControl> bControl=new AtomicReference<>();AtomicReference<String> delivered=new AtomicReference<>();
        try(TeamCoordinator coordinator=new TeamCoordinator(state(),(model,messages,control)->{
            if(synthesis(messages))return new JSONObject().put("content","汇总");
            if(model.model.equals("model-a")){if(aCalls.incrementAndGet()==1){started.countDown();assertTrue(late.await(2,TimeUnit.SECONDS));return new JSONObject().put("content","stale answer");}delivered.set(messages.toString());return new JSONObject().put("content","新回复");}
            bCalls.incrementAndGet();bControl.set(control);return new JSONObject().put("content","乙已完成");
        },event->{})){
            String id=coordinator.start(team(),"检查独立控制",1,config()).getString("id");assertTrue(started.await(1,TimeUnit.SECONDS));await(coordinator,id,r->member(r,1).optString("status").equals("completed"));
            coordinator.feedback(id,"a","第一条要求");JSONObject queued=coordinator.feedback(id,"a","第二条要求");assertFalse(bControl.get().isCancelled());assertEquals("pending",queued.getJSONArray("inbox").getJSONObject(0).getString("status"));assertEquals("completed",member(queued,1).getString("status"));
            coordinator.resume(id,"a",config(),null,null);late.countDown();JSONObject done=await(coordinator,id,r->r.optString("status").equals("completed"));assertEquals(id,done.getString("id"));assertEquals(1,bCalls.get());assertEquals(2,aCalls.get());assertFalse(done.toString().contains("stale answer"));assertTrue(delivered.get().indexOf("第一条要求")<delivered.get().indexOf("第二条要求"));
            for(int i=0;i<2;i++){JSONObject letter=done.getJSONArray("inbox").getJSONObject(i);assertEquals("consumed",letter.getString("status"));assertEquals("a",letter.getJSONArray("consumedBy").getString(0));}
        }finally{late.countDown();}
    }

    @Test public void pausedSynthesisCannotPublishLateSummaryAndResumeKeepsCompletedMembers()throws Exception {
        CountDownLatch synthesizing=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger answers=new AtomicInteger(),summaries=new AtomicInteger();
        try(TeamCoordinator coordinator=new TeamCoordinator(state(),(model,messages,control)->{if(synthesis(messages)){if(summaries.incrementAndGet()==1){synthesizing.countDown();assertTrue(release.await(2,TimeUnit.SECONDS));return new JSONObject().put("content","late summary");}return new JSONObject().put("content","新汇总");}answers.incrementAndGet();return new JSONObject().put("content","实际成员结果");},e->{})){
            String id=coordinator.start(team(),"暂停汇总",1,config()).getString("id");assertTrue(synthesizing.await(1,TimeUnit.SECONDS));coordinator.pause(id,null);coordinator.resume(id,null,config(),null,null);release.countDown();JSONObject done=await(coordinator,id,r->r.optString("status").equals("completed"));assertEquals(2,answers.get());assertEquals(2,summaries.get());assertEquals("新汇总",done.getString("summary"));assertFalse(done.toString().contains("late summary"));
        }finally{release.countDown();}
    }

    @Test public void restartPreservesPausedRunAndBoardButDispatchesOnlyOnExplicitResume()throws Exception {
        File file=state();CountDownLatch started=new CountDownLatch(2);String id;
        try(TeamCoordinator coordinator=new TeamCoordinator(file,(m,p,c)->{started.countDown();while(true){c.check();Thread.sleep(5);}},e->{})){id=coordinator.start(team(),"重启继续",1,config()).getString("id");assertTrue(started.await(1,TimeUnit.SECONDS));coordinator.pause(id,null);}
        AtomicInteger calls=new AtomicInteger();try(TeamCoordinator restored=new TeamCoordinator(file,(m,p,c)->{calls.incrementAndGet();return new JSONObject().put("content","恢复后实际结果");},e->{})){JSONObject interrupted=restored.get(id);assertEquals("interrupted",interrupted.getString("status"));assertEquals(0,calls.get());assertEquals("interrupted",interrupted.getJSONObject("board").getJSONArray("cards").getJSONObject(0).getString("status"));restored.resume(id,null,config(),null,null);assertEquals(id,await(restored,id,r->r.optString("status").equals("completed")).getString("id"));assertEquals(3,calls.get());}
    }

    @Test public void unknownExternalOperationResumesByQueryEvenWhenFreshProbeWouldFail()throws Exception {
        AtomicInteger probes=new AtomicInteger(),executions=new AtomicInteger();AtomicReference<String> operation=new AtomicReference<>();
        ExternalMemberDriver driver=new ExternalMemberDriver(){public JSONObject probe(JSONObject slot,RunControl control)throws Exception{return new JSONObject().put("available",probes.incrementAndGet()==1);}public JSONObject execute(JSONObject request,RunControl control,java.util.function.Consumer<JSONObject> events)throws Exception{assertEquals("codex_app_server",request.getJSONObject("slot").getString("runner"));if(executions.incrementAndGet()==1){operation.set(request.getString("operationId"));assertFalse(request.has("previousOperationId"));return new JSONObject().put("status","unknown");}assertEquals(operation.get(),request.getString("operationId"));assertEquals(operation.get(),request.getString("previousOperationId"));return new JSONObject().put("status","completed").put("content","原操作实际终态");}};
        try(TeamCoordinator coordinator=new TeamCoordinator(state(),(m,p,c)->new JSONObject().put("content","本地真实结果"),e->{})){JSONObject definition=team();definition.getJSONArray("members").getJSONObject(0).put("runner","codex_app_server").put("device",new JSONObject().put("profileId","computer").put("environmentId","project"));String id=coordinator.start(definition,"核对旧操作",1,config(),UUID.randomUUID().toString(),null,slot->driver).getString("id");await(coordinator,id,r->r.optString("status").equals("paused"));coordinator.resume(id,"a",config(),null,slot->driver);JSONObject done=await(coordinator,id,r->r.optString("status").equals("completed"));assertEquals(1,probes.get());assertEquals(2,executions.get());assertEquals("原操作实际终态",member(done,0).getString("text"));}
    }

    @Test public void unavailableRunnerIsNeverImpersonatedByAModel()throws Exception {
        AtomicInteger calls=new AtomicInteger();try(TeamCoordinator coordinator=new TeamCoordinator(state(),(m,p,c)->{calls.incrementAndGet();assertNotEquals("model-a",m.model);return new JSONObject().put("content","实际模型结果");},e->{})){JSONObject definition=team();definition.getJSONArray("members").getJSONObject(0).put("runner","claude");String id=coordinator.start(definition,"真实能力",1,config()).getString("id");JSONObject done=await(coordinator,id,r->r.optString("status").equals("partial"));assertFalse(done.getBoolean("businessVerified"));assertEquals(1,done.getInt("completedWorkItems"));assertEquals(2,done.getInt("totalWorkItems"));assertEquals("unsupported",member(done,0).getString("status"));assertEquals(2,calls.get());assertEquals("unsupported",done.getJSONObject("board").getJSONArray("cards").getJSONObject(0).getString("status"));}
    }

    @Test public void queryConsumesOnlyTheInstructionsOriginallySentAndLeavesNewFeedbackPending()throws Exception {
        AtomicInteger probes=new AtomicInteger(),executions=new AtomicInteger();AtomicReference<String> uncertain=new AtomicReference<>();
        ExternalMemberDriver driver=new ExternalMemberDriver(){public JSONObject probe(JSONObject slot,RunControl control)throws Exception{probes.incrementAndGet();return new JSONObject().put("available",true);}public JSONObject execute(JSONObject request,RunControl control,java.util.function.Consumer<JSONObject> events)throws Exception{int count=executions.incrementAndGet();if(count==2||count==4){uncertain.set(request.getString("operationId"));return new JSONObject().put("status","unknown");}if(count==3||count==5)assertEquals(uncertain.get(),request.getString("previousOperationId"));return new JSONObject().put("status","completed").put("content","actual receipt "+count);}};
        try(TeamCoordinator coordinator=new TeamCoordinator(state(),(m,p,c)->new JSONObject().put("content","实际汇总"),e->{})){
            JSONObject definition=team();definition.getJSONArray("members").getJSONObject(0).put("runner","codex_app_server").put("device",new JSONObject().put("profileId","computer").put("environmentId","project"));String id=coordinator.start(definition,"补充核对",1,config(),UUID.randomUUID().toString(),null,slot->driver).getString("id");await(coordinator,id,r->r.optString("status").equals("completed"));
            coordinator.feedback(id,"a","已实际发送的要求");coordinator.resume(id,"a",config(),null,slot->driver);await(coordinator,id,r->r.optString("status").equals("paused"));coordinator.resume(id,"a",config(),null,slot->driver);JSONObject completed=await(coordinator,id,r->r.optString("status").equals("completed"));assertEquals("consumed",completed.getJSONArray("inbox").getJSONObject(0).getString("status"));assertEquals(2,probes.get());
            coordinator.feedback(id,"a","本次实际发送");coordinator.resume(id,"a",config(),null,slot->driver);await(coordinator,id,r->r.optString("status").equals("paused"));coordinator.feedback(id,"a","旧操作派发后的新要求");coordinator.resume(id,"a",config(),null,slot->driver);JSONObject waiting=await(coordinator,id,r->r.optString("phase").equals("waiting_member"));assertEquals("paused",member(waiting,0).getString("status"));assertEquals("pending",waiting.getJSONArray("inbox").getJSONObject(2).getString("status"));assertEquals(0,waiting.getJSONArray("inbox").getJSONObject(2).getJSONArray("consumedBy").length());assertEquals(3,probes.get());assertEquals(5,executions.get());
            coordinator.resume(id,"a",config(),null,slot->driver);JSONObject fresh=await(coordinator,id,r->r.optString("status").equals("completed"));assertEquals("consumed",fresh.getJSONArray("inbox").getJSONObject(2).getString("status"));assertEquals(6,executions.get());assertEquals(4,probes.get());
        }
    }

    @Test public void externalExceptionAfterOperationPreparationCannotBecomeAnOrdinaryFailedMember()throws Exception {
        AtomicInteger executions=new AtomicInteger();AtomicReference<String> operation=new AtomicReference<>();ExternalMemberDriver driver=new ExternalMemberDriver(){public JSONObject probe(JSONObject slot,RunControl control)throws Exception{return new JSONObject().put("available",true);}public JSONObject execute(JSONObject request,RunControl control,java.util.function.Consumer<JSONObject> events)throws Exception{if(executions.incrementAndGet()==1){operation.set(request.getString("operationId"));throw new java.io.IOException("receipt persistence unavailable after dispatch");}assertEquals(operation.get(),request.getString("previousOperationId"));return new JSONObject().put("status","completed").put("content","已核对的真实回执");}};
        try(TeamCoordinator coordinator=new TeamCoordinator(state(),(m,p,c)->new JSONObject().put("content","实际汇总"),e->{})){JSONObject definition=team();definition.getJSONArray("members").getJSONObject(0).put("runner","codex_app_server").put("device",new JSONObject().put("profileId","computer").put("environmentId","project"));String id=coordinator.start(definition,"回执异常",1,config(),UUID.randomUUID().toString(),null,slot->driver).getString("id");JSONObject waiting=await(coordinator,id,r->!r.optString("status").matches("running|queued"));assertEquals("paused",waiting.getString("status"));assertEquals("blocked_unknown",member(waiting,0).getString("status"));coordinator.resume(id,"a",config(),null,slot->driver);assertEquals("completed",await(coordinator,id,r->r.optString("status").equals("completed")).getString("status"));assertEquals(2,executions.get());}
    }
}
