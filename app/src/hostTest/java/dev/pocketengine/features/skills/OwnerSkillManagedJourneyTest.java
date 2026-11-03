package dev.pocketengine.features.skills;

import android.app.Application;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.HostToolSupervisor;
import dev.pocketengine.features.local.project.TaskStoreProjectHost;
import dev.pocketengine.runtime.AgentRunner;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import static org.junit.Assert.*;

/** Actual JNI/host Python with the existing Android-native managed path, SQL and sole Runner.
 * Host runtime fixture only: no physical device or OS sandbox claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class OwnerSkillManagedJourneyTest {
    @Test public void ownerOnlySelectionRunsRealManagedScriptAndColdRecoveryOnlyReadsItsOriginalArtifact()throws Exception {
        String script="from pathlib import Path\nimport sys\nvalue=float(Path('input/data.txt').read_text())+5\nPath('output').mkdir(exist_ok=True)\nPath('output/result.bin').write_bytes((format(value,'.2f')+'|'+sys.argv[1]).encode('utf-8'))\nprint('OWNER_NATIVE_SCRIPT_EXECUTED')\n";
        RunControl original=new RunControl();
        try(ManagedSkillNativeJourneyTest.Fixture f=new ManagedSkillNativeJourneyTest.Fixture("python3",script,true,"$(owner literal) 中文")){
            // Preserve an earlier definite unstarted operation in this same fixture task.
            f.tasks.journal(f.id(),f.task.getString("runId"),f.task.getInt("coordinatorEpoch"),f.control).finishOperation(f.operation(),new JSONObject().put("ok",false).put("accepted",false).put("status","rejected").put("code","fixture_not_dispatched"));
            f.tasks.endAttempt(f.id(),f.task.getString("runId"),"awaiting_user","等待主人选择当前技能",new JSONObject());
            f.window.close();f.host.close();
            var projectHost=new TaskStoreProjectHost(f.tasks,f.local,id->original,event->{});f.local.setProjectHost(projectHost);
            f.local.setProjectReceiptSink(actual->{try{projectHost.recordBackendReceipt(actual);f.callbacks.incrementAndGet();}catch(Exception failure){f.callbackFailure.compareAndSet(null,failure);}});
            f.host=new NativeSkillExecutionHost(f.android,f.skills,f.remote,f.tasks,(task,run,epoch,control)->{if(control!=original||!control.matchesHostRun(task,run,epoch))throw new SecurityException("not original owner control");control.check();},id->{},f.local);
            f.window=f.host.openOwnerWindow();f.window.setForeground(true);
            JSONObject scope=new JSONObject().put("ownerId","owner").put("spaceId","private");JSONObject row=f.skills.handle("get",new JSONObject().put("id",f.installed.getString("id"))).getJSONObject("skill");
            JSONObject projectChoice=f.skills.projectSourceChoicesForOwner().getJSONObject(0);assertEquals(f.project,projectChoice.getString("projectId"));
            f.skills.configureSourceForOwner(scope,row.getString("id"),row.getInt("revision"),row.getString("digest"),row.getLong("executionGeneration"),SkillSourcePolicy.Layer.PROJECT,SkillSourcePolicy.Invocation.OWNER_ONLY,projectChoice);
            f.skills.handle("setEnabled",new JSONObject().put("id",row.getString("id")).put("enabled",true).put("confirmed",true).put("acceptedDigest",row.getString("digest")));
            row=f.skills.handle("get",new JSONObject().put("id",row.getString("id"))).getJSONObject("skill");
            assertEquals(0,f.skills.catalogueForHost(scope).getJSONArray("skills").length());
            assertEquals(0,f.skills.catalogueForHost(f.skills.taskScopeForHost(f.tasks.get(f.id(),false))).getJSONArray("skills").length());
            JSONObject input=new JSONObject(f.input.toString());input.remove("_hostOperationId");
            // A previously reviewed global phone configuration is not authority to read a
            // different project's inputs in this original project task.
            String otherProject=f.workspace.handle("create",new JSONObject().put("name","Other private project")).getString("id");
            f.workspace.handle("write",new JSONObject().put("projectId",otherProject).put("path","input/data.txt").put("expectedVersion",0).put("content","900.00"));
            String otherCheckout=f.local.handle("prepare",new JSONObject().put("projectId",otherProject)).getString("id");
            JSONObject different=f.configuration("python3").put("checkoutId",otherCheckout);
            var originalConfiguration=new SkillExecutionConfiguration(new java.io.File(((android.content.Context)f.android).getFilesDir(),"skill-execution.json"));
            var prior=originalConfiguration.inspectPhone(different);originalConfiguration.install(prior,prior.digest);
            JSONObject blocked=f.host.taskSnapshot(f.id(),Set.of("skills_execute"),new JSONObject());assertFalse(blocked.getJSONObject("ownerInvocation").getBoolean("available"));assertFalse(blocked.getJSONObject("ownerInvocation").getBoolean("projectConfigurationCompatible"));
            JSONArray displayed=blocked.getJSONObject("phoneExecution").getJSONArray("checkouts");assertEquals(1,displayed.length());assertEquals(f.checkout,displayed.getJSONObject(0).getString("checkoutId"));
            final long generation=row.getLong("executionGeneration");
            assertThrows(SecurityException.class,()->f.host.selectForOwner(f.window,f.id(),blocked.getJSONObject("ownerInvocation").getString("taskPin"),input,generation,Set.of("skills_execute")));
            assertThrows(SecurityException.class,()->f.host.inspectPhoneConfiguration(f.id(),different));
            JSONObject empty=f.configuration("python3").put("checkoutId","").put("readPaths",new JSONArray());
            assertEquals("",f.host.inspectPhoneConfiguration(f.id(),empty).view().getJSONObject("configuration").getString("checkoutId"));
            var correct=f.host.inspectPhoneConfiguration(f.id(),f.configuration("python3"));f.host.installConfiguration(f.window,correct,correct.digest);
            JSONObject preparation=f.host.taskSnapshot(f.id(),Set.of("skills_execute"),new JSONObject());assertTrue(preparation.getJSONObject("ownerInvocation").getBoolean("available"));assertEquals(f.project,preparation.getJSONObject("ownerInvocation").getString("projectId"));
            var selected=f.host.selectForOwner(f.window,f.id(),preparation.getJSONObject("ownerInvocation").getString("taskPin"),input,row.getLong("executionGeneration"),Set.of("skills_execute"));
            selected.checkBeforeStart(f.host);JSONObject attempt=f.tasks.startOwnerSkillAttempt(selected,30,original);f.tasks.authorizeTools(f.id(),attempt.getString("runId"),new JSONArray().put("skills_execute"));
            try(FeatureHost features=new FeatureHost(event->{},f.tasks,()->false)){
                features.register(f.skills);features.register(f.remote);features.setSkillExecutionHost(f.host);
                var port=new HostToolSupervisor(features,f.tasks,f.id(),attempt.getString("runId"),attempt.getInt("coordinatorEpoch"),true);
                var journal=f.tasks.journal(f.id(),attempt.getString("runId"),attempt.getInt("coordinatorEpoch"),original);
                f.work=CompletableFuture.supplyAsync(()->{
                    try{RunOutcome result=new AgentRunner(null,port).runOwnerSkill(f.id(),attempt.getString("goal"),new ModelConfig("","","no-provider-request",false,30,30),original,event->{},journal,selected);
                        f.tasks.finishCapabilityAttempt(f.id(),attempt.getString("runId"),attempt.getInt("coordinatorEpoch"),original,result);return new JSONObject().put("status",result.status).put("summary",result.summary).put("evidence",result.evidence);
                    }catch(Exception failure){throw new CompletionException(failure);}
                });
                var review=f.review();assertEquals("owner_selection",review.plan.review().getJSONObject("package").getString("invocationOrigin"));assertEquals(f.project,review.plan.review().getJSONObject("run").getString("projectId"));assertEquals(f.checkout,review.plan.review().getJSONObject("run").getString("checkoutId"));
                assertTrue(f.host.readScript(review).getString("content").contains("OWNER_NATIVE_SCRIPT_EXECUTED"));
                JSONArray operations=f.tasks.get(f.id(),false).getJSONArray("operations");String operation=null;
                for(int i=0;i<operations.length();i++){JSONObject candidate=operations.getJSONObject(i),nativeSkill=candidate.getJSONObject("arguments").optJSONObject("nativeSkill");if(nativeSkill!=null&&review.plan.digest.equals(nativeSkill.getJSONObject("review").getString("planDigest")))operation=candidate.getString("operationId");}
                assertNotNull(operation);f.input.put("_hostOperationId",operation);assertFalse(java.nio.file.Files.exists(f.copy()));
                f.approve(review);JSONObject result=f.work.get(20,TimeUnit.SECONDS);
                assertEquals(result.toString(),"needs_confirmation",result.getString("status"));assertTrue(result.getJSONObject("evidence").getBoolean("ownerSelectedSkill"));assertFalse(result.getJSONObject("evidence").getBoolean("businessVerified"));
                assertArrayEquals("45.00|$(owner literal) 中文".getBytes(StandardCharsets.UTF_8),f.saved());assertEquals("40.00",f.originalInput());
                assertEquals(1,f.tasks.list().length());assertEquals(2,f.tasks.get(f.id(),false).getJSONArray("operations").length());assertTrue(f.record().getJSONObject("nativeManagedObservation").getBoolean("resourcesReleased"));
                assertThrows(SecurityException.class,()->selected.checkBeforeStart(f.host));assertThrows(SecurityException.class,()->f.host.decide(f.window,review,review.plan.digest,true));
                f.skills.handle("delete",new JSONObject().put("id",f.installed.getString("id")));f.reopenForReceiptOnly();
                JSONObject cold=f.host.reconcile(f.id(),operation,false);assertEquals("completed",cold.getJSONObject("receipt").getString("status"));assertFalse(cold.getJSONObject("receipt").getBoolean("replayAllowed"));
                assertArrayEquals("45.00|$(owner literal) 中文".getBytes(StandardCharsets.UTF_8),f.saved());assertEquals(2,f.tasks.get(f.id(),false).getJSONArray("operations").length());assertNull(f.callbackFailure.get());
            }
        }finally{original.cancel();}
    }
}
