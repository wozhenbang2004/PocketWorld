package dev.pocketengine.features.teams;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** The actual coordinator/mailbox runs; only external model execution is substituted. */
public class TeamCoordinatorHarnessBridgeTest {
    private JSONObject slot(String id,String runner)throws Exception{return new JSONObject().put("id",id).put("name",id).put("role","Review the same bounded goal").put("runner",runner).put("externalExecutionRiskAcknowledged",true).put("device",new JSONObject().put("profileId","computer").put("environmentId","project"));}
    private JSONObject team()throws Exception{return new JSONObject().put("id","mixed-harness-team").put("name","Mixed harness team").put("members",new JSONArray().put(slot("author","acp_fixture")).put(slot("reviewer","muse_msp")));}
    private ModelConfig model(){return new ModelConfig("https://example.invalid/v1","fixture-key","fixture-model",false,20,10);}
    private JSONObject probe(String worker)throws Exception{return new JSONObject().put("worker",worker).put("available",true).put("protocolVerified",true).put("protocol",worker.equals("muse_msp")?"muse_msp_v1":"acp_v1").put("executionPolicy","owner_managed").put("configurationRevision","a".repeat(64)).put("capabilityRevision","b".repeat(64));}
    private JSONObject done(TeamCoordinator coordinator,String id)throws Exception {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(System.nanoTime()<deadline){JSONObject run=coordinator.get(id);if(!run.optString("status").matches("queued|running|stopping"))return run;Thread.sleep(10);}throw new AssertionError(coordinator.get(id).toString());}
    @Test public void mixedHarnessesShareOriginalMailboxAndDuplicateSendConsumesOneBudget()throws Exception {
        CountDownLatch sent=new CountDownLatch(1);AtomicReference<ExternalMemberDriver.TeamBridge> originalBridge=new AtomicReference<>();
        ExternalMemberFactory factory=member->new ExternalMemberDriver(){
            public JSONObject probe(JSONObject slot,RunControl control)throws Exception{return TeamCoordinatorHarnessBridgeTest.this.probe(slot.getString("runner"));}
            public JSONObject execute(JSONObject request,RunControl control,Consumer<JSONObject> events){throw new AssertionError("The original Turn bridge must be supplied");}
            public JSONObject execute(JSONObject request,RunControl control,Consumer<JSONObject> events,TeamBridge bridge)throws Exception{
                JSONObject context=request.getJSONObject("teamContext");assertEquals(request.getString("turnId"),context.getString("turnId"));assertEquals(request.getString("runId"),context.getString("runId"));assertTrue(context.has("runGeneration"));assertEquals(2,context.getJSONArray("roster").length());
                if(member.getString("id").equals("author")){
                    originalBridge.set(bridge);assertEquals(2,bridge.call("roster-1","team_list_members",new JSONObject()).getJSONArray("members").length());
                    JSONObject args=new JSONObject().put("targetMemberId","reviewer").put("text","Original author check is ready");
                    JSONObject first=bridge.call("message-1","team_send_message",args),duplicate=bridge.call("message-1","team_send_message",args);assertEquals(first.getString("messageId"),duplicate.getString("messageId"));
                    assertThrows(SecurityException.class,()->bridge.call("spoof-1","team_send_message",new JSONObject(args.toString()).put("fromMemberId","reviewer")));sent.countDown();
                }else{
                    assertTrue(sent.await(1,TimeUnit.SECONDS));JSONObject inbox=bridge.call("read-1","team_read_messages",new JSONObject());assertFalse(inbox.getBoolean("consumed"));assertEquals("author",inbox.getJSONArray("messages").getJSONObject(0).getString("fromMemberId"));
                }
                return new JSONObject().put("status","completed").put("content","Actual fixture output from "+member.getString("id"));
            }
        };
        File state=new File(Files.createTempDirectory("team-harness-bridge").toFile(),"runs.json");
        try(TeamCoordinator coordinator=new TeamCoordinator(state,(config,messages,control)->new JSONObject().put("content","Synthesis of the two actual fixture results"),event->{})){
            String id=coordinator.start(team(),"Review one goal",1,model(),UUID.randomUUID().toString(),null,factory).getString("id");JSONObject run=done(coordinator,id);assertEquals(run.toString(),"completed",run.getString("status"));assertEquals(2,run.getInt("externalTurnsUsed"));assertEquals(3,run.getInt("toolCallsUsed"));assertEquals(1,run.getJSONArray("mailbox").length());assertEquals("author",run.getJSONArray("mailbox").getJSONObject(0).getString("fromMemberId"));
            assertThrows(Exception.class,()->originalBridge.get().call("late-1","team_send_message",new JSONObject().put("targetMemberId","reviewer").put("text","Late send")));assertEquals(1,coordinator.get(id).getJSONArray("mailbox").length());
        }
    }
    @Test public void pausedOriginalTurnCannotUseCapturedTeamBridge()throws Exception {
        CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);AtomicReference<ExternalMemberDriver.TeamBridge> captured=new AtomicReference<>();
        ExternalMemberFactory factory=member->new ExternalMemberDriver(){
            public JSONObject probe(JSONObject slot,RunControl control)throws Exception{return TeamCoordinatorHarnessBridgeTest.this.probe(slot.getString("runner"));}
            public JSONObject execute(JSONObject request,RunControl control,Consumer<JSONObject> events){throw new AssertionError();}
            public JSONObject execute(JSONObject request,RunControl control,Consumer<JSONObject> events,TeamBridge bridge)throws Exception{captured.compareAndSet(null,bridge);entered.countDown();release.await(2,TimeUnit.SECONDS);return new JSONObject().put("status","completed").put("content","Late output");}
        };
        File state=new File(Files.createTempDirectory("team-harness-pause").toFile(),"runs.json");
        try(TeamCoordinator coordinator=new TeamCoordinator(state,(config,messages,control)->new JSONObject().put("content","Summary"),event->{})){
            String id=coordinator.start(team(),"Pause original turn",1,model(),UUID.randomUUID().toString(),null,factory).getString("id");assertTrue(entered.await(1,TimeUnit.SECONDS));coordinator.pause(id,"");assertThrows(Exception.class,()->captured.get().call("late-read","team_read_messages",new JSONObject()));assertEquals(0,coordinator.get(id).getInt("toolCallsUsed"));
        }finally{release.countDown();}
    }
}
