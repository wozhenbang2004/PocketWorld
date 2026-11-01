package dev.pocketengine.features;

import android.app.Application;
import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.features.teams.TeamsModule;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.SecretVault;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import dev.pocketengine.store.TaskStore;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class TeamOwnerContinuationHostTest {
    private JSONObject snapshot(String id,String status,int generation,long revision,long at)throws Exception{return new JSONObject().put("id",id).put("operationId",id).put("status",status).put("generation",generation).put("revision",revision).put("updatedAt",at);}
    private String task(TaskStore tasks,String status)throws Exception{String id=UUID.randomUUID().toString();tasks.beginExternal("teams","start",id,new JSONObject().put("goal","原团队任务"));tasks.updateExternal(id,snapshot(id,status,3,12,100));return id;}
    private JSONObject request(FeatureHost host,CopyOnWriteArrayList<JSONObject> events,String command,JSONObject input)throws Exception{
        String id=UUID.randomUUID().toString();host.request(new JSONObject().put("feature","teams").put("command",command).put("requestId",id).put("payload",input));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(System.nanoTime()<until){for(JSONObject event:events)if(event.optString("type").equals("feature_result")&&id.equals(event.optString("requestId")))return event;Thread.sleep(5);}throw new AssertionError("missing native receipt");
    }
    private JSONObject reviewed(String run)throws Exception{return new JSONObject().put("runId",run).put("expectedGeneration",3).put("expectedRevision",12L).put("memberId","a").put("expectedMemberGeneration",6);}
    private EngineModule teams(AtomicInteger calls){return new EngineModule(){public String id(){return "teams";}public JSONObject snapshot(){return new JSONObject();}public JSONObject handle(String command,JSONObject input)throws Exception{calls.incrementAndGet();return TeamOwnerContinuationHostTest.this.snapshot(input.getString("runId"),"running",3,13,101);}};}

    @Test public void explicitReviewedContinuationReopensOnlyTheSameAnalysisTask()throws Exception{
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());String id=task(tasks,"partial");assertEquals("needs_confirmation",tasks.get(id,false).getString("status"));CopyOnWriteArrayList<JSONObject> events=new CopyOnWriteArrayList<>();AtomicInteger calls=new AtomicInteger();
        try(FeatureHost host=new FeatureHost(events::add,tasks,()->false)){host.register(teams(calls));JSONObject reply=request(host,events,"continueMessages",reviewed(id));assertTrue(reply.toString(),reply.getBoolean("ok"));JSONObject actual=tasks.get(id,false);assertEquals("running",actual.getString("status"));assertEquals(id,actual.getString("runId"));assertEquals(13L,actual.getJSONObject("evidence").getLong("revision"));assertFalse(actual.getJSONObject("evidence").getBoolean("automaticallyVerified"));assertEquals(1,tasks.list().length());assertEquals(1,calls.get());}
    }
    @Test public void lowerRevisionCannotReplaceSameGenerationEvenWithNewerWallClock()throws Exception{
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());String id=task(tasks,"paused");tasks.updateExternal(id,snapshot(id,"paused",3,20,200));tasks.updateExternal(id,snapshot(id,"running",3,19,300));assertEquals("paused",tasks.get(id,false).getString("status"));assertEquals(20L,tasks.get(id,false).getJSONObject("evidence").getLong("revision"));tasks.updateExternal(id,snapshot(id,"running",3,21,190));assertEquals("running",tasks.get(id,false).getString("status"));assertEquals(21L,tasks.get(id,false).getJSONObject("evidence").getLong("revision"));
    }
    @Test public void ordinaryLateAdmissionCannotReopenFinishedAnalysis()throws Exception{
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());String id=task(tasks,"completed");tasks.updateExternal(id,snapshot(id,"running",3,13,101));assertEquals("needs_confirmation",tasks.get(id,false).getString("status"));assertFalse(tasks.get(id,false).getJSONObject("evidence").getBoolean("automaticallyVerified"));
    }
    @Test public void cancelledTaskRejectsBeforeAnyDomainDispatch()throws Exception{
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());String id=task(tasks,"cancelled");CopyOnWriteArrayList<JSONObject> events=new CopyOnWriteArrayList<>();AtomicInteger calls=new AtomicInteger();try(FeatureHost host=new FeatureHost(events::add,tasks,()->false)){host.register(teams(calls));assertFalse(request(host,events,"continueMessages",reviewed(id)).getBoolean("ok"));assertEquals(0,calls.get());assertEquals("cancelled",tasks.get(id,false).getString("status"));}
    }
    @Test public void ownerConfirmedAndUnknownJournalTasksCannotDispatchAContinuation()throws Exception{
        for(boolean unknown:new boolean[]{false,true}){
            TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());String id=task(tasks,"running");
            if(unknown){tasks.beginCapabilityOperation(id,id,"teams","fixture_operation",new JSONObject());tasks.status(id,"reconciling","已有动作结果待核对");}else tasks.status(id,"completed","主人已确认");
            CopyOnWriteArrayList<JSONObject> events=new CopyOnWriteArrayList<>();AtomicInteger calls=new AtomicInteger();try(FeatureHost host=new FeatureHost(events::add,tasks,()->false)){host.register(teams(calls));assertFalse(request(host,events,"continueMessages",reviewed(id)).getBoolean("ok"));assertEquals(0,calls.get());assertEquals(unknown?"reconciling":"completed",tasks.get(id,false).getString("status"));}
        }
    }
    @Test public void generationFencePrecedesLegacyClockAndRevisionFallback()throws Exception{
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());String id=task(tasks,"paused");tasks.updateExternal(id,snapshot(id,"running",2,99,300));assertEquals("paused",tasks.get(id,false).getString("status"));JSONObject legacy=snapshot(id,"running",4,1,50);legacy.remove("revision");tasks.updateExternal(id,legacy);assertEquals("running",tasks.get(id,false).getString("status"));assertEquals(4,tasks.get(id,false).getJSONObject("evidence").getInt("generation"));
    }
    @Test public void actualModuleEventsAndReviewedResumeUseTheOriginalTaskWithoutRejectingTheirOwnNewRevision()throws Exception{
        HttpServer model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);CountDownLatch gate=new CountDownLatch(1);java.util.concurrent.atomic.AtomicBoolean hold=new java.util.concurrent.atomic.AtomicBoolean(false);
        model.createContext("/v1/chat/completions",exchange->{try{exchange.getRequestBody().readAllBytes();if(hold.get())assertTrue(gate.await(3,TimeUnit.SECONDS));byte[] body="{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"本地HTTP真实fixture产出，需主人核对\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);}catch(Exception failure){throw new RuntimeException(failure);}finally{exchange.close();}});model.start();
        RuntimeEnvironment.getApplication().getSharedPreferences("private_settings",0).edit().remove("selectedAccountModel").putString("baseUrl","http://127.0.0.1:"+model.getAddress().getPort()+"/v1").putString("model","fixture-model").putString("provider","openai_chat").commit();
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());CopyOnWriteArrayList<JSONObject> events=new CopyOnWriteArrayList<>();AtomicReference<FeatureHost> active=new AtomicReference<>();
        dev.pocketengine.core.EventSink sink=event->{events.add(event);FeatureHost host=active.get();if(host!=null)host.recordEvent(event);};
        try(FeatureHost host=new FeatureHost(sink,tasks,()->false);TeamsModule module=new TeamsModule(new FeatureContext(RuntimeEnvironment.getApplication(),new SettingsStore(RuntimeEnvironment.getApplication()),new SecretVault(RuntimeEnvironment.getApplication()),sink))){
            active.set(host);host.register(module);JSONObject definition=module.handle("save",new JSONObject().put("name","实际host协作").put("mode","collaborate").put("members",new JSONArray().put(new JSONObject().put("id","a").put("name","甲").put("role","研究")).put(new JSONObject().put("id","b").put("name","乙").put("role","核验"))));
            JSONObject started=request(host,events,"start",new JSONObject().put("teamId",definition.getString("id")).put("goal","保持同一个实际团队任务").put("rounds",1));assertTrue(started.toString(),started.getBoolean("ok"));String run=started.getJSONObject("result").getString("id"),task=started.getJSONObject("result").getString("hostTaskId");JSONObject done=awaitModule(module,run,"completed");assertEquals("needs_confirmation",tasks.get(task,false).getString("status"));
            JSONObject feedback=reviewedSnapshot(done,"a").put("text","核对并修订本轮结果");JSONObject paused=request(host,events,"feedback",feedback);assertTrue(paused.toString(),paused.getBoolean("ok"));hold.set(true);
            JSONObject resumed=request(host,events,"resume",reviewedSnapshot(paused.getJSONObject("result"),"a"));assertTrue(resumed.toString(),resumed.getBoolean("ok"));assertEquals(run,resumed.getJSONObject("result").getString("id"));assertTrue(tasks.get(task,false).optString("status").matches("queued|running"));assertEquals(1,tasks.list().length());gate.countDown();JSONObject fresh=awaitModule(module,run,"completed");assertTrue(fresh.getLong("revision")>done.getLong("revision"));assertEquals("needs_confirmation",tasks.get(task,false).getString("status"));assertFalse(tasks.get(task,false).getJSONObject("evidence").getBoolean("automaticallyVerified"));
        }finally{gate.countDown();model.stop(0);}
    }
    private JSONObject reviewedSnapshot(JSONObject run,String member)throws Exception{
        JSONObject input=new JSONObject().put("runId",run.getString("id")).put("expectedRevision",run.getLong("revision")).put("expectedGeneration",run.getInt("generation")).put("memberId",member);for(int n=0;n<run.getJSONArray("members").length();n++){JSONObject slot=run.getJSONArray("members").getJSONObject(n);if(member.equals(slot.getString("id")))input.put("expectedMemberGeneration",slot.getInt("generation"));}return input;
    }
    private JSONObject awaitModule(TeamsModule module,String run,String status)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(System.nanoTime()<until){JSONObject value=module.handle("get",new JSONObject().put("runId",run));if(status.equals(value.optString("status")))return value;Thread.sleep(5);}throw new AssertionError(module.handle("get",new JSONObject().put("runId",run)).toString());}

}
