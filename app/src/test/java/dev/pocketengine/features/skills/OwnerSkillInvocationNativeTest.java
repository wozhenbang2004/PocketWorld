package dev.pocketengine.features.skills;

import android.app.Application;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.HostToolSupervisor;
import dev.pocketengine.runtime.AgentRunner;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Original SQL/Runner/Supervisor/native review with the explicit Companion protocol fixture.
 * Fixture markers are transport proof; real script execution is in OwnerSkillManagedJourneyTest. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class OwnerSkillInvocationNativeTest {
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static final class Rig implements AutoCloseable {
        final NativeSkillExecutionHostTest.Fixture fixture=new NativeSkillExecutionHostTest.Fixture("$(owner literal)","中文 参数");
        final String id=fixture.task.getString("id");
        final RunControl original=new RunControl();
        final NativeSkillExecutionHost host;
        final NativeSkillExecutionHost.OwnerWindow window;
        final FeatureHost features;
        final JSONObject input;
        final java.util.concurrent.atomic.AtomicInteger injectedFailures=new java.util.concurrent.atomic.AtomicInteger();
        JSONObject started;
        Rig()throws Exception{this(true);}
        Rig(boolean settled)throws Exception {
            fixture.window.close();fixture.host.close();
            if(settled)fixture.tasks.journal(id,fixture.task.getString("runId"),fixture.task.getInt("coordinatorEpoch"),fixture.control).finishOperation(fixture.input.getString("_hostOperationId"),new JSONObject().put("ok",false).put("accepted",false).put("status","rejected").put("code","fixture_not_dispatched"));
            fixture.tasks.endAttempt(id,fixture.task.getString("runId"),"awaiting_user","保留原任务，等待主人明确选择",new JSONObject());
            host=new NativeSkillExecutionHost(fixture.android,fixture.skills,fixture.wire,fixture.tasks,(task,run,epoch,control)->{if(control!=original||!control.matchesHostRun(task,run,epoch))throw new SecurityException("not the original owner run");control.check();},task->{});
            window=host.openOwnerWindow();window.setForeground(true);features=new FeatureHost(event->{},fixture.tasks,()->false);features.register(fixture.skills);features.register(fixture.wire);features.setSkillExecutionHost(host);
            input=new JSONObject(fixture.input.toString());input.remove("_hostOperationId");input.put("timeoutSeconds",10).put("maxOutputBytes",8192);
            JSONObject row=row();fixture.skills.configureSourceForOwner(scope(),row.getString("id"),row.getInt("revision"),row.getString("digest"),row.getLong("executionGeneration"),SkillSourcePolicy.Layer.USER,SkillSourcePolicy.Invocation.OWNER_ONLY);enable();
        }
        JSONObject row()throws Exception{return fixture.skills.handle("get",new JSONObject().put("id",fixture.installed.getString("id"))).getJSONObject("skill");}
        void enable()throws Exception{JSONObject row=row();fixture.skills.handle("setEnabled",new JSONObject().put("id",row.getString("id")).put("enabled",true).put("confirmed",true).put("acceptedDigest",row.getString("digest")));}
        JSONObject snapshot()throws Exception{return host.taskSnapshot(id,Set.of("skills_execute"),new JSONObject());}
        NativeSkillExecutionHost.OwnerInvocation select()throws Exception {JSONObject row=row();return host.selectForOwner(window,id,snapshot().getJSONObject("ownerInvocation").getString("taskPin"),input,row.getLong("executionGeneration"),Set.of("skills_execute"));}
        void start(NativeSkillExecutionHost.OwnerInvocation selected)throws Exception {selected.checkBeforeStart(host);started=fixture.tasks.startOwnerSkillAttempt(selected,20,original);fixture.tasks.authorizeTools(id,started.getString("runId"),new JSONArray().put("skills_execute"));}
        CompletableFuture<RunOutcome> run(NativeSkillExecutionHost.OwnerInvocation selected)throws Exception {
            return run(selected,"");
        }
        CompletableFuture<RunOutcome> run(NativeSkillExecutionHost.OwnerInvocation selected,String failAt)throws Exception {
            HostToolSupervisor nativePort=new HostToolSupervisor(features,fixture.tasks,id,started.getString("runId"),started.getInt("coordinatorEpoch"),true);
            CapabilityPort port=new CapabilityPort(){
                public JSONArray tools()throws Exception{return nativePort.tools();}
                public boolean supports(String name){return nativePort.supports(name);}
                public boolean sideEffecting(String name){return nativePort.sideEffecting(name);}
                public void validate(String name,JSONObject input)throws Exception{nativePort.validate(name,input);}
                public void beginRun(String goal,RunControl control)throws Exception{nativePort.beginRun(goal,control);}
                public JSONObject prepareTurn(String query,int round,int steps,RunControl control)throws Exception{return nativePort.prepareTurn(query,round,steps,control);}
                public JSONObject beforeTool(String name,JSONObject input,RunControl control)throws Exception{return nativePort.beforeTool(name,input,control);}
                public JSONObject execute(String name,JSONObject input,RunControl control)throws Exception{return nativePort.execute(name,input,control);}
                public void afterTool(String name,JSONObject input,JSONObject receipt,RunControl control)throws Exception{nativePort.afterTool(name,input,receipt,control);if("afterTool".equals(failAt)){injectedFailures.incrementAndGet();throw new java.io.IOException("fixture: afterTool failed after real receipt");}}
                public void endRun(RunControl control)throws Exception{nativePort.endRun(control);}
                public void close(){nativePort.close();}
            };
            var nativeJournal=fixture.tasks.journal(id,started.getString("runId"),started.getInt("coordinatorEpoch"),original);
            RunJournal journal=new RunJournal(){
                boolean receiptSaved;
                public JSONObject restore()throws Exception{return nativeJournal.restore();}
                public void checkpoint(JSONArray history,int steps,JSONObject evidence)throws Exception{nativeJournal.checkpoint(history,steps,evidence);if(receiptSaved&&"checkpoint".equals(failAt)){injectedFailures.incrementAndGet();throw new java.io.IOException("fixture: checkpoint ack lost after real receipt");}}
                public String beginOperation(String tool,JSONObject args)throws Exception{return nativeJournal.beginOperation(tool,args);}
                public void finishOperation(String operation,JSONObject receipt)throws Exception{nativeJournal.finishOperation(operation,receipt);receiptSaved=true;}
                public int executionStepCeiling(int captured)throws Exception{return nativeJournal.executionStepCeiling(captured);}
            };
            return CompletableFuture.supplyAsync(()->new AgentRunner(null,port).runOwnerSkill(id,started.optString("goal"),new ModelConfig("","","no-model-call",false,20,20),original,event->{},journal,selected));
        }
        NativeSkillExecutionHost.Review review(CompletableFuture<?> running)throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(System.nanoTime()<until){var found=host.pendingReview(id);if(found!=null)return found;if(running.isDone())throw new AssertionError("Owner run finished before native review: "+running.get());Thread.sleep(20);}throw new AssertionError("Original native review was not opened");}
        public void close()throws Exception{original.cancel();window.close();host.close();features.close();fixture.close();}
    }
    @Test public void ownerOnlySelectionUsesOriginalRunnerAndStillRequiresTheExactNativeReview()throws Exception {
        try(Rig r=new Rig()){
            assertEquals(0,r.fixture.skills.catalogueForHost(scope()).getJSONArray("skills").length());
            assertThrows(SecurityException.class,()->r.fixture.skills.readForHost(scope(),r.input.getString("id"),"scripts/main.py",0,1000,r.input.getInt("revision"),r.input.getString("digest"),Set.of("skills_execute")));
            var selected=r.select();JSONObject forged=selected.view();forged.getJSONObject("input").put("arguments",new JSONArray().put("forged"));
            r.start(selected);assertThrows(SecurityException.class,()->r.fixture.tasks.startOwnerSkillAttempt(selected,20,new RunControl()));
            var running=r.run(selected);var review=r.review(running);assertEquals(0,r.fixture.posts.get());assertEquals(0,r.fixture.markers());
            assertEquals("owner_selection",review.plan.review().getJSONObject("package").getString("invocationOrigin"));
            assertEquals("$(owner literal)",review.plan.review().getJSONObject("request").getJSONArray("arguments").getString(0));
            assertTrue(r.host.readScript(review).getString("content").contains("NEVER EXECUTE"));
            r.host.decide(r.window,review,review.plan.digest,true);RunOutcome outcome=running.get(15,TimeUnit.SECONDS);
            assertEquals(outcome.summary+" "+outcome.evidence,"needs_confirmation",outcome.status);assertTrue(outcome.evidence.getBoolean("ownerSelectedSkill"));assertFalse(outcome.evidence.getBoolean("businessVerified"));assertEquals(0,r.injectedFailures.get());
            assertEquals("completed",r.fixture.tasks.readSkillExecution(r.id,outcome.evidence.getString("operationId")).getJSONObject("receipt").getString("status"));
            assertEquals(1,r.fixture.posts.get());assertEquals(1,r.fixture.markers());assertEquals(1,r.fixture.tasks.list().length());assertEquals(2,r.fixture.tasks.get(r.id,false).getJSONArray("operations").length());
            assertThrows(SecurityException.class,()->selected.checkBeforeStart(r.host));assertThrows(SecurityException.class,()->r.host.decide(r.window,review,review.plan.digest,true));
        }
    }
    @Test public void sourceTaskAndForegroundChangesRejectOldSelectionsBeforeASecondRun()throws Exception {
        try(Rig r=new Rig()){
            var withdrawn=r.select();r.fixture.skills.handle("setEnabled",new JSONObject().put("id",r.input.getString("id")).put("enabled",false));
            assertThrows(SecurityException.class,()->withdrawn.checkBeforeStart(r.host));r.enable();
            var oldTask=r.select();r.fixture.tasks.feedback(r.id,"主人已更新目标，旧选择不得沿用");
            assertThrows(SecurityException.class,()->r.fixture.tasks.startOwnerSkillAttempt(oldTask,20,r.original));
            var background=r.select();r.window.setForeground(false);assertThrows(SecurityException.class,()->background.checkBeforeStart(r.host));
            assertThrows(SecurityException.class,()->r.fixture.tasks.startOwnerSkillAttempt(background,20,r.original));
            assertEquals(r.fixture.task.getString("runId"),r.fixture.tasks.get(r.id,false).getString("runId"));assertEquals(0,r.fixture.posts.get());assertEquals(1,r.fixture.tasks.get(r.id,false).getJSONArray("operations").length());
        }
    }
    @Test public void nativeChoiceCannotOverrideManifestOrAnUnresolvedOriginalOperation()throws Exception {
        try(Rig r=new Rig()){
            JSONObject row=r.row();JSONObject changed=r.fixture.skills.handle("import",new JSONObject().put("id",row.getString("id")).put("revision",row.getInt("revision")).put("source","owner:non-invocable").put("manifest","---\nname: native-review\ndescription: 主人不得直接调用\nuser-invocable: false\n---\n仅供参考。\n").put("resources",new JSONObject().put("scripts/main.py","print('must not start')\n"))).getJSONObject("skill");r.enable();
            r.input.put("revision",changed.getInt("revision")).put("digest",changed.getString("digest"));
            assertEquals(0,r.snapshot().getJSONObject("ownerInvocation").getJSONArray("skills").length());assertThrows(SecurityException.class,r::select);assertEquals(0,r.fixture.posts.get());
        }
        try(Rig r=new Rig(false)){
            assertTrue(r.fixture.tasks.hasUnresolved(r.id));assertFalse(r.snapshot().getJSONObject("ownerInvocation").getBoolean("available"));
            assertThrows(SecurityException.class,r::select);assertEquals(0,r.fixture.posts.get());assertEquals(1,r.fixture.tasks.get(r.id,false).getJSONArray("operations").length());
        }
    }
    @Test public void withdrawalOrCancellationAfterReservationNeverReplaysTheOwnerChoice()throws Exception {
        try(Rig r=new Rig()){
            var selected=r.select();r.start(selected);r.fixture.skills.handle("setEnabled",new JSONObject().put("id",r.input.getString("id")).put("enabled",false));
            RunOutcome result=r.run(selected).get(5,TimeUnit.SECONDS);assertEquals("awaiting_user",result.status);assertEquals(0,r.fixture.posts.get());assertEquals(1,r.fixture.tasks.get(r.id,false).getJSONArray("operations").length());
            assertThrows(SecurityException.class,()->selected.requireRun(r.id,r.original));
        }
        try(Rig r=new Rig()){
            var selected=r.select();r.start(selected);r.original.cancel();RunOutcome result=r.run(selected).get(5,TimeUnit.SECONDS);
            assertEquals("cancelled",result.status);assertEquals(0,r.fixture.posts.get());assertEquals(1,r.fixture.tasks.get(r.id,false).getJSONArray("operations").length());
        }
    }
    @Test public void postReceiptCheckpointAndAfterToolFailuresKeepTheOriginalKnownEffect()throws Exception {
        for(String failure:new String[]{"checkpoint","afterTool"})try(Rig r=new Rig()){
            var selected=r.select();r.start(selected);var running=r.run(selected,failure);var review=r.review(running);
            r.host.decide(r.window,review,review.plan.digest,true);RunOutcome outcome=running.get(15,TimeUnit.SECONDS);
            assertEquals(outcome.summary+" "+outcome.evidence,"awaiting_user",outcome.status);assertEquals(failure+" must fail only after the real receipt",1,r.injectedFailures.get());assertTrue(outcome.evidence.getBoolean("receiptRecorded"));assertTrue(outcome.evidence.getBoolean("postProcessingIncomplete"));assertFalse(outcome.summary.contains("尚未派发"));
            String operation=outcome.evidence.getString("operationId");assertEquals(outcome.evidence.toString(),"completed",r.fixture.tasks.readSkillExecution(r.id,operation).getJSONObject("receipt").getString("status"));
            assertEquals(1,r.fixture.posts.get());assertEquals(1,r.fixture.markers());assertFalse(outcome.evidence.getBoolean("automaticReplayAllowed"));
            assertThrows(SecurityException.class,()->selected.checkBeforeStart(r.host));
            r.fixture.tasks.finishCapabilityAttempt(r.id,r.started.getString("runId"),r.started.getInt("coordinatorEpoch"),r.original,outcome);
            assertEquals("completed",r.host.reconcile(r.id,operation,false).getJSONObject("receipt").getString("status"));assertEquals(1,r.fixture.posts.get());
        }
    }
}
