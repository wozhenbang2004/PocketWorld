package dev.pocketengine.features.teams;

import dev.pocketengine.core.*;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Controlled adapters exercise real Coordinator controls/finally, with no provider/SQL claims. */
public final class TeamResearchOriginalGenerationTest {
    private static void await(BooleanSupplier ready)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!ready.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);assertTrue(ready.getAsBoolean());}
    private static void budget(TeamCoordinator c){c.setBudgetHost(new TeamCoordinator.BudgetHost(){public void initialize(TeamCoordinator.InitialBudgetPlan p){}public JSONObject reserveInference(TeamCoordinator.InferenceCall call){throw new AssertionError();}public void releaseUnstarted(TeamCoordinator.InferenceCall call){}public JSONObject snapshot(String run)throws Exception{return new JSONObject().put("remainingModelRequests",16).put("modelRequestsReservedOrUsed",0).put("toolOperationsReservedOrUsed",0);}public JSONObject beginRead(TeamCoordinator.InferenceCall call,String tool,JSONObject args){throw new AssertionError();}public void finishRead(JSONObject original,JSONObject receipt,boolean unknown){throw new AssertionError();}});}
    @Test public void lateOriginalTicketNeverRetargetsNewControlAndOldDynamicResourceRemainsVisible()throws Exception {
        var entered=new LinkedBlockingQueue<RunControl>();var oldRelease=new CountDownLatch(1);var newRelease=new CountDownLatch(1);var worker=new java.util.concurrent.atomic.AtomicInteger();
        ModelConfig model=new ModelConfig("https://example.invalid/v1","","fixture",false,16,10);String id=UUID.randomUUID().toString();
        try(TeamCoordinator c=new TeamCoordinator(new File(Files.createTempDirectory("original-research-generation").toFile(),"runs.json"),(cfg,messages,control)->{throw new AssertionError("no model call");},event->{})){
            budget(c);
            c.setResearchFactory((call,cfg,control,progress)->{int order=worker.incrementAndGet();entered.add(control);assertTrue((order==1?oldRelease:newRelease).await(10,TimeUnit.SECONDS));return new JSONObject().put("taskId",call.researchTaskId).put("memberId",call.memberId).put("teamRunId",call.teamRunId).put("status","awaiting_user");});
            var team=new JSONObject().put("id","original").put("name","original").put("mode","collaborate").put("members",new JSONArray().put(new JSONObject().put("id","a").put("name","A").put("role","research")));
            var original=c.startOriginalResearch(team,"original generation",1,model,id,null,null);RunControl first=entered.poll(5,TimeUnit.SECONDS);assertNotNull(first);assertFalse(first.isCancelled());c.pause(id,null);assertTrue("original owner owns a child created after the start capture",first.isCancelled());c.resume(id,null,model,null,null);RunControl second=entered.poll(5,TimeUnit.SECONDS);assertNotNull(second);assertNotSame(first,second);
            JSONObject before=c.get(id),receipt=original.requestStop();assertFalse(receipt.getBoolean("appliedToCurrentGeneration"));assertEquals("not_asserted",receipt.getString("resourceSettlement"));assertFalse(receipt.has("resourcesReleased"));assertFalse(second.isCancelled());assertEquals(before.getInt("generation"),c.get(id).getInt("generation"));assertEquals(before.getString("status"),c.get(id).getString("status"));
            newRelease.countDown();await(()->{try{return !c.get(id).optString("status").matches("queued|running");}catch(Exception failure){throw new IllegalStateException(failure);}});assertTrue(c.parentExecutorState(id).getBoolean("activeNativeExecutors"));assertFalse("a replaced map entry cannot erase the old physical worker",c.parentExecutorState(id).getBoolean("resourcesReleased"));
            oldRelease.countDown();await(()->{try{return c.parentExecutorState(id).getBoolean("resourcesReleased");}catch(Exception failure){throw new IllegalStateException(failure);}});assertFalse(second.isCancelled());
        }finally{oldRelease.countDown();newRelease.countDown();}
    }
    @Test public void synchronousOriginalCancelCallbackCanReplaceGenerationBeforeStopMark()throws Exception {
        var entered=new LinkedBlockingQueue<RunControl>();var release=new CountDownLatch(1);var callbackFailure=new java.util.concurrent.atomic.AtomicReference<Throwable>();var replacementSnapshot=new java.util.concurrent.atomic.AtomicReference<JSONObject>();
        ModelConfig model=new ModelConfig("https://example.invalid/v1","","fixture",false,16,10);String id=UUID.randomUUID().toString();
        try(TeamCoordinator c=new TeamCoordinator(new File(Files.createTempDirectory("original-research-reentrant").toFile(),"runs.json"),(cfg,messages,control)->{throw new AssertionError("no model call");},event->{})){
            budget(c);c.setResearchFactory((call,cfg,control,progress)->{entered.add(control);assertTrue(release.await(10,TimeUnit.SECONDS));return new JSONObject().put("taskId",call.researchTaskId).put("memberId",call.memberId).put("teamRunId",call.teamRunId).put("status","awaiting_user");});
            var team=new JSONObject().put("id","original").put("name","original").put("mode","collaborate").put("members",new JSONArray().put(new JSONObject().put("id","a").put("name","A").put("role","research")));
            var original=c.startOriginalResearch(team,"synchronous cancellation callback",1,model,id,null,null);RunControl first=entered.poll(5,TimeUnit.SECONDS);assertNotNull(first);
            // Attach to the actual captured root, not a fabricated control or manual parent callback.
            var rootField=original.getClass().getDeclaredField("control");rootField.setAccessible(true);RunControl root=(RunControl)rootField.get(original);
            var replacement=new java.util.concurrent.atomic.AtomicReference<RunControl>();root.onCancel(()->{try{c.pause(id,null);c.resume(id,null,model,null,null);replacementSnapshot.set(c.get(id));var runField=original.getClass().getDeclaredField("run");runField.setAccessible(true);Object run=runField.get(original);var live=run.getClass().getDeclaredField("control");live.setAccessible(true);replacement.set((RunControl)live.get(run));}catch(Throwable failure){callbackFailure.set(failure);}});
            synchronized(c){JSONObject receipt=original.requestStop();assertNull(callbackFailure.get());assertTrue(first.isCancelled());assertNotNull(replacement.get());assertNotSame(root,replacement.get());assertFalse(receipt.getBoolean("appliedToCurrentGeneration"));assertFalse(replacement.get().isCancelled());assertEquals("no write after a synchronous native generation replacement",replacementSnapshot.get().toString(),c.get(id).toString());}
            RunControl child=entered.poll(5,TimeUnit.SECONDS);assertNotNull(child);assertFalse(child.isCancelled());assertFalse(replacement.get().isCancelled());release.countDown();await(()->{try{return c.parentExecutorState(id).getBoolean("resourcesReleased");}catch(Exception failure){throw new IllegalStateException(failure);}});
        }finally{release.countDown();}
    }
    @Test public void nativeInitializationReentryCannotMakeStartTicketCaptureReplacementGeneration()throws Exception {
        var entered=new LinkedBlockingQueue<RunControl>();var release=new CountDownLatch(1);var initialGeneration=new java.util.concurrent.atomic.AtomicInteger(-1);String id=UUID.randomUUID().toString();ModelConfig model=new ModelConfig("https://example.invalid/v1","","fixture",false,16,10);
        try(TeamCoordinator c=new TeamCoordinator(new File(Files.createTempDirectory("original-research-initialize").toFile(),"runs.json"),(cfg,messages,control)->{throw new AssertionError("no model call");},event->{})){
            c.setBudgetHost(new TeamCoordinator.BudgetHost(){public void initialize(TeamCoordinator.InitialBudgetPlan p)throws Exception{initialGeneration.set(p.view().getInt("generation"));c.pause(id,null);c.resume(id,null,model,null,null);}public JSONObject reserveInference(TeamCoordinator.InferenceCall call){throw new AssertionError();}public void releaseUnstarted(TeamCoordinator.InferenceCall call){}public JSONObject snapshot(String run)throws Exception{return new JSONObject().put("remainingModelRequests",16).put("modelRequestsReservedOrUsed",0).put("toolOperationsReservedOrUsed",0);}public JSONObject beginRead(TeamCoordinator.InferenceCall call,String tool,JSONObject args){throw new AssertionError();}public void finishRead(JSONObject original,JSONObject receipt,boolean unknown){throw new AssertionError();}});
            c.setResearchFactory((call,cfg,control,progress)->{entered.add(control);assertTrue(release.await(10,TimeUnit.SECONDS));return new JSONObject().put("taskId",call.researchTaskId).put("memberId",call.memberId).put("teamRunId",call.teamRunId).put("status","awaiting_user");});
            var team=new JSONObject().put("id","original").put("name","original").put("mode","collaborate").put("members",new JSONArray().put(new JSONObject().put("id","a").put("name","A").put("role","research")));
            // The real native initialize hook re-enters before accepted/scheduling returns.
            synchronized(c){var original=c.startOriginalResearch(team,"native initialization reentry",1,model,id,null,null);JSONObject before=c.get(id);assertTrue(before.getInt("generation")>initialGeneration.get());JSONObject stop=original.requestStop();assertEquals(initialGeneration.get(),stop.getInt("originalGeneration"));assertFalse(stop.getBoolean("appliedToCurrentGeneration"));assertEquals(before.toString(),c.get(id).toString());}
            RunControl replacement=entered.poll(5,TimeUnit.SECONDS);assertNotNull(replacement);assertFalse(replacement.isCancelled());release.countDown();await(()->{try{return c.parentExecutorState(id).getBoolean("resourcesReleased");}catch(Exception failure){throw new IllegalStateException(failure);}});
        }finally{release.countDown();}
    }
    @Test public void stoppingOriginalResearchPreservesUnknownExternalOperationAndUsedBudgetAcrossColdRead()throws Exception {
        File saved=Files.createTempDirectory("original-research-unknown").resolve("runs.json").toFile();String id=UUID.randomUUID().toString();var calls=new java.util.concurrent.atomic.AtomicInteger();
        ModelConfig model=new ModelConfig("https://example.invalid/v1","","fixture",false,16,10);String operation;int used;
        try(TeamCoordinator c=new TeamCoordinator(saved,(cfg,messages,control)->{throw new AssertionError("no model request");},event->{})){
            c.setResearchFactory((call,cfg,control,progress)->{throw new AssertionError("external slot cannot run as model research");});
            ExternalMemberDriver driver=new ExternalMemberDriver(){public JSONObject probe(JSONObject slot,RunControl control)throws Exception{return new JSONObject().put("available",true);}public JSONObject execute(JSONObject request,RunControl control,java.util.function.Consumer<JSONObject> events)throws Exception{calls.incrementAndGet();throw new java.io.IOException("original external acknowledgement lost");}};
            var team=new JSONObject().put("id","original").put("name","original").put("mode","collaborate").put("members",new JSONArray().put(new JSONObject().put("id","a").put("name","A").put("role","readonly research").put("runner","codex_app_server").put("device",new JSONObject().put("profileId","original-computer").put("environmentId","original-project"))));
            var started=c.startOriginalResearch(team,"preserve uncertain original",1,model,id,null,slot->driver);await(()->{try{return !c.get(id).optString("status").matches("queued|running");}catch(Exception failure){throw new IllegalStateException(failure);}});
            JSONObject before=c.get(id),state=before.getJSONArray("members").getJSONObject(0).getJSONArray("roundStates").getJSONObject(0);operation=state.getString("externalOperationId");used=before.getInt("externalTurnsUsed");assertFalse(state.getBoolean("externalSettled"));assertEquals("blocked_unknown",state.getString("status"));
            JSONObject stop=started.requestStop();assertTrue(stop.getBoolean("appliedToCurrentGeneration"));assertFalse(stop.has("resourcesReleased"));JSONObject after=c.get(id);state=after.getJSONArray("members").getJSONObject(0).getJSONArray("roundStates").getJSONObject(0);assertEquals(operation,state.getString("externalOperationId"));assertFalse(state.getBoolean("externalSettled"));assertEquals(used,after.getInt("externalTurnsUsed"));assertEquals(1,calls.get());
        }
        try(TeamCoordinator cold=new TeamCoordinator(saved,(cfg,messages,control)->{throw new AssertionError("cold read cannot dispatch");},event->{})){
            JSONObject state=cold.get(id),original=state.getJSONArray("members").getJSONObject(0).getJSONArray("roundStates").getJSONObject(0);assertEquals(operation,original.getString("externalOperationId"));assertFalse(original.getBoolean("externalSettled"));assertEquals(used,state.getInt("externalTurnsUsed"));assertEquals(1,calls.get());
        }
    }
}
