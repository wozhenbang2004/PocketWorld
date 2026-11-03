package dev.pocketengine;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.controlplane.NativeWorkProfileHost;
import dev.pocketengine.features.controlplane.WorkProfile;
import dev.pocketengine.runtime.models.ModelTargetRef;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual original Application/native owner/SQL/Runner/model HTTP, not a replacement controller. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class WorkProfileApplicationJourneyTest {
    private EngineApplication app; private ExecutorService worker; private HttpServer server;
    private final List<JSONObject> requests=new CopyOnWriteArrayList<>(); private JSONObject a,b,c;
    @Before public void open()throws Exception {
        app=(EngineApplication)RuntimeEnvironment.getApplication();worker=Executors.newSingleThreadExecutor();server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));requests.add(request);JSONObject message=new JSONObject().put("role","assistant").put("content","Original profile main response");byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason","stop"))).put("usage",new JSONObject().put("prompt_tokens",1).put("completion_tokens",2).put("total_tokens",3)).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
        worker.submit(()->{try {a=connection("Main","profile-main");b=connection("Summary","profile-summary");c=connection("Other default","must-not-be-used");app.settings.selectApiConnection(c.getString("id"),c.getLong("revision"));}catch(Exception failed){throw new IllegalStateException(failed);}}).get(20,TimeUnit.SECONDS);
    }
    private JSONObject connection(String name,String model)throws Exception {return app.settings.saveApiConnection("",0,name,new JSONObject().put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()+"/v1").put("provider","openai_chat").put("model",model).put("maxSteps",4).put("sendScreenshots",false));}
    private JSONObject definition()throws Exception {JSONObject value=WorkProfile.definition("draft-profile",1,"Original task coding");value.getJSONObject("modelPolicies").put("MAIN",ModelTargetRef.exact(a.getString("id"),"profile-main").toJson()).put("SUMMARY",ModelTargetRef.exact(b.getString("id"),"profile-summary").toJson());return value;}
    private JSONObject instruction(String body)throws Exception {
        var skills=app.features.module("skills");JSONObject source=skills.handle("import",new JSONObject().put("source","fixture:native-owner").put("manifest","---\nname: owner-profile-guidance\ndescription: Exact owner task guidance, not script authority\n---\n"+body)).getJSONObject("skill");
        skills.handle("setEnabled",new JSONObject().put("id",source.getString("id")).put("enabled",true).put("confirmed",true).put("acceptedDigest",source.getString("digest")));
        source=skills.handle("get",new JSONObject().put("id",source.getString("id"))).getJSONObject("skill");
        return new JSONObject().put("id",source.getString("id")).put("revision",source.getInt("revision")).put("digest",source.getString("digest")).put("optional",false);
    }
    private NativeWorkProfileHost.Selection select(JSONObject definition,RunControl original)throws Exception {var page=app.nativeWorkProfiles().newOwnerPage();page.setForeground(true);try{JSONObject saved=page.save("",0,"Original task coding",definition);var review=page.review(saved.getString("profileId"),1,new RunControl());assertTrue(review.view().getBoolean("ready"));assertFalse(review.view().getBoolean("configurationApplied"));return page.select(review,review.digest,original);}finally{page.close();}}
    @After public void close()throws Exception {server.stop(0);worker.shutdownNow();worker.awaitTermination(5,TimeUnit.SECONDS);app.onTerminate();app.features.close();}
    @Test public void nativeOwnerStartKeepsExactMainSummaryDespiteGlobalSelectionChange()throws Exception {
        JSONObject result=worker.submit(()->{RunControl original=new RunControl();var selected=select(definition(),original);app.settings.selectModelPurpose("SUMMARY",ModelTargetRef.exact(c.getString("id"),"must-not-be-used"));
            var method=EngineApplication.class.getDeclaredMethod("nativeWorkProfileModel",NativeWorkProfileHost.Selection.class,RunControl.class);method.setAccessible(true);ModelConfig captured=(ModelConfig)method.invoke(app,selected,original);assertEquals("profile-main",captured.model);assertEquals("profile-summary",captured.forPurpose("SUMMARY").model);
            return app.runOwnerWorkProfile("Return the original fixture reply","",null,null,selected,original);}).get(30,TimeUnit.SECONDS);
        assertEquals(result.toString(),1,requests.size());assertEquals("profile-main",requests.get(0).getString("model"));JSONObject task=app.tasks.get(result.getString("taskId"),false);assertTrue(task.has("workProfileRef"));assertEquals("explicit",task.getJSONObject("extensionSelection").getString("mode"));assertEquals(1,task.getJSONArray("operations").length());JSONObject reference=task.getJSONArray("operations").getJSONObject(0);assertEquals("native_memory_reference",reference.getString("tool"));assertEquals("reference_provided",reference.getString("state"));assertFalse(reference.getJSONObject("receipt").getBoolean("executionAuthorized"));assertFalse(reference.getJSONObject("receipt").getBoolean("modelUseVerified"));JSONObject frozen=app.tasks.workProfileRunSnapshot(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"));assertEquals("profile-main",frozen.getJSONObject("modelPolicies").getJSONObject("MAIN").getString("model"));assertEquals("profile-summary",frozen.getJSONObject("modelPolicies").getJSONObject("SUMMARY").getString("model"));assertFalse(frozen.toString().contains("apiKey"));
    }
    @Test public void revokedCapturedRouteCannotFallbackToTheGlobalDefaultOrCreateATask()throws Exception {
        worker.submit(()->{RunControl original=new RunControl();var selected=select(definition(),original);int before=app.tasks.list().length();app.settings.setApiConnectionEnabled(a.getString("id"),1,false);assertThrows(Exception.class,()->app.runOwnerWorkProfile("No fallback is permitted","",null,null,selected,original));assertEquals(before,app.tasks.list().length());return null;}).get(20,TimeUnit.SECONDS);assertEquals(0,requests.size());
    }
    @Test public void actualNativeOwnerAndSameTaskRecoveryProvideFrozenGuidanceWithoutSystemOrSkillAuthority()throws Exception {
        final String marker="EXACT_OWNER_PROFILE_GUIDANCE_ONLY";
        JSONObject result=worker.submit(()->{
            JSONObject ref=instruction("Use this selected task guidance as reference: "+marker),value=definition();value.getJSONArray("instructionRefs").put(ref);
            RunControl first=new RunControl();var selected=select(value,first);JSONObject started=app.runOwnerWorkProfile("Return the original fixture reply","",null,null,selected,first);
            String id=started.getString("taskId");JSONObject before=app.tasks.get(id,false);app.settings.removeWorkProfile(selected.profile().id,1);
            var page=app.nativeWorkProfiles().newOwnerPage();page.setForeground(true);
            try{
                var review=page.reviewForTask(app.tasks,id,before.getString("runId"),before.getInt("coordinatorEpoch"),new RunControl());RunControl next=new RunControl();var recovered=page.select(review,review.digest,next);
                JSONObject continued=app.continueOwnerWorkProfile(id,null,recovered,next),after=app.tasks.get(id,false);
                assertEquals(id,continued.getString("taskId"));assertEquals(1,app.tasks.list().length());assertEquals(before.getInt("coordinatorEpoch")+1,after.getInt("coordinatorEpoch"));
                for(String field:new String[]{"revision","deadlineAt","toolLimit","modelCallLimit"})assertEquals(before.getJSONObject("executionScope").get(field),after.getJSONObject("executionScope").get(field));
                JSONObject frozen=app.tasks.workProfileRunSnapshot(id,after.getString("runId"),after.getInt("coordinatorEpoch"));assertEquals(ref.getString("digest"),frozen.getJSONObject("definition").getJSONArray("instructionRefs").getJSONObject(0).getString("digest"));assertFalse(frozen.toString().contains(marker));
                var field=dev.pocketengine.store.TaskStore.class.getDeclaredField("db");field.setAccessible(true);var sql=(android.database.sqlite.SQLiteDatabase)field.get(app.tasks);
                try(var row=sql.rawQuery("SELECT checkpoint FROM runs WHERE task_id=?",new String[]{id})){while(row.moveToNext())assertFalse("Request-only guidance must not be checkpointed as an owner/system/history copy",row.getString(0).contains(marker));}
                return continued;
            }finally{page.close();}
        }).get(40,TimeUnit.SECONDS);
        assertEquals(result.toString(),2,requests.size());
        for(JSONObject request:requests){
            boolean reference=false;JSONArray messages=request.getJSONArray("messages");for(int i=0;i<messages.length();i++){
                JSONObject message=messages.getJSONObject(i);String content=message.optString("content");
                if(content.contains(marker)){assertEquals("user",message.getString("role"));assertTrue(content.contains("<host_reference_data>"));assertTrue(content.contains("主人为本任务明确选择的指导参考"));reference=true;}
            }
            assertTrue("Actual original model HTTP must contain the selected instruction body",reference);
            JSONArray tools=request.optJSONArray("tools");for(int i=0;tools!=null&&i<tools.length();i++)assertFalse("Guidance alone cannot select executable/read Skill tools",tools.getJSONObject(i).getJSONObject("function").getString("name").startsWith("skills_"));
        }
    }
    @Test public void oversizedGuidanceSurfacesAnActionableOriginalTaskFailureWithoutAnyInferenceDispatch()throws Exception {
        JSONObject result=worker.submit(()->{
            JSONObject value=definition();value.getJSONArray("instructionRefs").put(instruction("complete-guidance ".repeat(1600)+"DO_NOT_DROP_THE_TAIL"));RunControl original=new RunControl();var selected=select(value,original);
            JSONObject output=app.runOwnerWorkProfile("Do not silently omit selected guidance","",null,null,selected,original);JSONObject usage=app.tasks.inferenceUsage(output.getString("taskId"));
            assertEquals(0,usage.getJSONArray("receipts").length());assertEquals(0,usage.getJSONArray("logicalRequests").length());assertEquals(0,app.tasks.get(output.getString("taskId"),false).getJSONObject("executionScope").getInt("modelCallsUsed"));return output;
        }).get(30,TimeUnit.SECONDS);
        assertEquals("failed",result.getString("status"));assertTrue(result.getString("content").contains("全文超过原参考窗口"));assertTrue(result.getString("content").contains("请减少指导引用"));assertEquals(0,requests.size());
    }
    @Test public void explicitRelayIsFrozenForTheOriginalTaskWithoutAutoApplyingATargetOrUsingItForMain()throws Exception {
        JSONObject output=worker.submit(()->{
            JSONObject value=definition();value.getJSONObject("modelPolicies").put("RELAY",ModelTargetRef.exact(c.getString("id"),"must-not-be-used").toJson());RunControl original=new RunControl();var selected=select(value,original);
            JSONObject result=app.runOwnerWorkProfile("Keep Relay separate from Main","",null,null,selected,original);JSONObject task=app.tasks.get(result.getString("taskId"),false),frozen=app.tasks.workProfileRunSnapshot(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"));
            assertEquals("must-not-be-used",frozen.getJSONObject("modelPolicies").getJSONObject("RELAY").getString("model"));assertFalse(frozen.getBoolean("configurationApplied"));assertEquals(0,app.tasks.originalHarnessGatewayRecords(task.getString("id"),task.getString("runId")).length());return result;
        }).get(30,TimeUnit.SECONDS);
        assertNotNull(output.getString("taskId"));assertEquals(1,requests.size());assertEquals("profile-main",requests.get(0).getString("model"));
    }
    @Test public void asyncOwnerStartUsesTheOriginalApplicationWorkerAfterTheSelectingPageAndUiWorkerClose()throws Exception {
        RunControl original=new RunControl();var selected=worker.submit(()->select(definition(),original)).get(20,TimeUnit.SECONDS);worker.shutdown();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));Thread caller=Thread.currentThread();var completed=new java.util.concurrent.CompletableFuture<JSONObject>();
        app.runOwnerWorkProfileAsync("Return the original fixture reply","",null,null,selected,original,(value,failure)->{if(Thread.currentThread()==caller)completed.completeExceptionally(new AssertionError("Original App worker required"));else if(failure!=null)completed.completeExceptionally(failure);else completed.complete(value);});
        JSONObject result=completed.get(30,TimeUnit.SECONDS);assertEquals(1,requests.size());assertEquals("profile-main",requests.get(0).getString("model"));JSONObject task=app.tasks.get(result.getString("taskId"),false);assertTrue(task.has("workProfileRef"));assertEquals(1,app.tasks.list().length());
    }
    @Test public void asyncCanceledSelectionCannotCreateAReplacementTaskOrFallbackRequest()throws Exception {
        RunControl original=new RunControl();var selected=worker.submit(()->select(definition(),original)).get(20,TimeUnit.SECONDS);original.cancel();var completed=new java.util.concurrent.CompletableFuture<Exception>();app.runOwnerWorkProfileAsync("Do not start after cancellation","",null,null,selected,original,(value,failure)->{if(value!=null||failure==null)completed.completeExceptionally(new AssertionError("Cancellation was not preserved"));else completed.complete(failure);});assertTrue(completed.get(20,TimeUnit.SECONDS) instanceof InterruptedException);assertEquals(0,requests.size());assertEquals(0,app.tasks.list().length());
    }
    @Test public void sourceReadyUnsupportedMemberAssignmentIsRejectedRatherThanSilentlyIgnored()throws Exception {
        worker.submit(()->{JSONObject value=definition();value.getJSONObject("modelPolicies").put("MEMBER.review",ModelTargetRef.exact(b.getString("id"),"profile-summary").toJson());RunControl original=new RunControl();var selected=select(value,original);int before=app.tasks.list().length();SecurityException blocked=assertThrows(SecurityException.class,()->app.runOwnerWorkProfile("Do not ignore member binding","",null,null,selected,original));assertTrue(blocked.getMessage().contains("role_not_applied"));assertEquals(before,app.tasks.list().length());return null;}).get(20,TimeUnit.SECONDS);assertEquals(0,requests.size());
    }
    @Test public void frozenResourcePreferenceIsConsumedByTheOriginalControlAndRetainedAcrossSameTaskRecovery()throws Exception {
        worker.submit(()->{JSONObject value=definition();value.getJSONObject("resourcePreferences").put("maxConcurrentModelCalls",1);RunControl original=new RunControl();var selected=select(value,original);JSONObject result=app.runOwnerWorkProfile("Consume the exact concurrency preference","",null,null,selected,original);assertEquals(1,original.modelConcurrencyLimit());JSONObject task=app.tasks.get(result.getString("taskId"),false);assertEquals(1,app.tasks.workProfileRunSnapshot(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch")).getJSONObject("resourcePreferences").getInt("maxConcurrentModelCalls"));var page=app.nativeWorkProfiles().newOwnerPage();page.setForeground(true);try{var review=page.reviewForTask(app.tasks,task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),new RunControl());RunControl next=new RunControl();var recovered=page.select(review,review.digest,next);app.continueOwnerWorkProfile(task.getString("id"),null,recovered,next);assertEquals(1,next.modelConcurrencyLimit());assertEquals(1,app.tasks.list().length());}finally{page.close();}return null;}).get(40,TimeUnit.SECONDS);assertTrue(requests.size()>=2);for(JSONObject request:requests)assertEquals("profile-main",request.getString("model"));
    }
    @Test public void explicitOriginalTaskRecoveryWorksAfterReusableDefinitionRemovalWithoutBudgetReset()throws Exception {
        JSONObject result=worker.submit(()->{RunControl first=new RunControl();var selected=select(definition(),first);JSONObject output=app.runOwnerWorkProfile("Return the original fixture reply","",null,null,selected,first);String id=output.getString("taskId");JSONObject before=app.tasks.get(id,false);long used=before.getJSONObject("executionScope").getLong("modelCallsUsed");app.settings.removeWorkProfile(selected.profile().id,1);
            var page=app.nativeWorkProfiles().newOwnerPage();page.setForeground(true);try{var review=page.reviewForTask(app.tasks,id,before.getString("runId"),before.getInt("coordinatorEpoch"),new RunControl());RunControl next=new RunControl();var recovered=page.select(review,review.digest,next);JSONObject continued=app.continueOwnerWorkProfile(id,null,recovered,next);JSONObject after=app.tasks.get(id,false);assertEquals(before.getInt("coordinatorEpoch")+1,after.getInt("coordinatorEpoch"));assertTrue(after.getJSONObject("executionScope").getLong("modelCallsUsed")>used);for(String unchanged:new String[]{"revision","deadlineAt","toolLimit","modelCallLimit"})assertEquals(before.getJSONObject("executionScope").get(unchanged),after.getJSONObject("executionScope").get(unchanged));assertEquals(0,app.settings.workProfilesSnapshot().getJSONArray("profiles").length());return continued;}finally{page.close();}}).get(40,TimeUnit.SECONDS);
        assertTrue(requests.size()>=2);for(JSONObject request:requests)assertEquals("profile-main",request.getString("model"));assertNotNull(result.getString("taskId"));
    }
    @Test public void asyncRecoveryAfterDefinitionRemovalContinuesOnlyTheSameRunLineageWithSpentBudgetRetained()throws Exception {
        JSONObject first=worker.submit(()->{RunControl control=new RunControl();var selected=select(definition(),control);JSONObject output=app.runOwnerWorkProfile("Return the original fixture reply","",null,null,selected,control);app.settings.removeWorkProfile(selected.profile().id,1);return app.tasks.get(output.getString("taskId"),false);}).get(30,TimeUnit.SECONDS);String id=first.getString("id");RunControl next=new RunControl();var selected=worker.submit(()->{var page=app.nativeWorkProfiles().newOwnerPage();page.setForeground(true);try{var review=page.reviewForTask(app.tasks,id,first.getString("runId"),first.getInt("coordinatorEpoch"),new RunControl());return page.select(review,review.digest,next);}finally{page.close();}}).get(20,TimeUnit.SECONDS);var completed=new java.util.concurrent.CompletableFuture<JSONObject>();app.continueOwnerWorkProfileAsync(id,selected,next,(value,failure)->{if(failure!=null)completed.completeExceptionally(failure);else completed.complete(value);});assertEquals(id,completed.get(30,TimeUnit.SECONDS).getString("taskId"));JSONObject after=app.tasks.get(id,false);assertEquals(1,app.tasks.list().length());assertEquals(first.getInt("coordinatorEpoch")+1,after.getInt("coordinatorEpoch"));assertTrue(after.getJSONObject("executionScope").getLong("modelCallsUsed")>first.getJSONObject("executionScope").getLong("modelCallsUsed"));for(String unchanged:new String[]{"revision","deadlineAt","toolLimit","modelCallLimit"})assertEquals(first.getJSONObject("executionScope").get(unchanged),after.getJSONObject("executionScope").get(unchanged));assertEquals(0,app.settings.workProfilesSnapshot().getJSONArray("profiles").length());assertTrue(requests.size()>=2);for(JSONObject request:requests)assertEquals("profile-main",request.getString("model"));
    }
    @Test public void asyncPendingIntentNeedsTheExplicitOriginalScopeBeforeItCanStartTheSameTask()throws Exception {
        JSONObject pending=worker.submit(()->{RunControl original=new RunControl();var selected=select(definition(),original);assertThrows(SecurityException.class,()->app.runOwnerWorkProfile("Recover only this pending goal","",new JSONObject().put("approveAll",true),null,selected,original));return app.tasks.get(app.tasks.list().getJSONObject(0).getString("id"),false);}).get(20,TimeUnit.SECONDS);String id=pending.getString("id");RunControl rejected=new RunControl();var first=worker.submit(()->{var page=app.nativeWorkProfiles().newOwnerPage();page.setForeground(true);try{var review=page.reviewForTask(app.tasks,id,"",pending.optInt("coordinatorEpoch"),new RunControl());return page.select(review,review.digest,rejected);}finally{page.close();}}).get(20,TimeUnit.SECONDS);var failed=new java.util.concurrent.CompletableFuture<Exception>();app.continueOwnerWorkProfileAsync(id,first,rejected,(value,failure)->failed.complete(failure));assertTrue(failed.get(20,TimeUnit.SECONDS) instanceof SecurityException);assertFalse(app.tasks.get(id,false).has("executionScope"));assertEquals(0,requests.size());
        JSONObject scope=app.tasks.configureExecutionScope(id,0,0,4,4).getJSONObject("executionScope");RunControl next=new RunControl();var selected=worker.submit(()->{var page=app.nativeWorkProfiles().newOwnerPage();page.setForeground(true);try{var review=page.reviewForTask(app.tasks,id,"",pending.optInt("coordinatorEpoch"),new RunControl());return page.select(review,review.digest,next);}finally{page.close();}}).get(20,TimeUnit.SECONDS);var completed=new java.util.concurrent.CompletableFuture<JSONObject>();app.continueOwnerWorkProfileAsync(id,selected,next,(value,failure)->{if(failure!=null)completed.completeExceptionally(failure);else completed.complete(value);});assertEquals(id,completed.get(30,TimeUnit.SECONDS).getString("taskId"));JSONObject after=app.tasks.get(id,false);assertEquals(1,app.tasks.list().length());assertFalse(after.has("workProfileIntent"));assertEquals(scope.getLong("revision"),after.getJSONObject("executionScope").getLong("revision"));assertEquals(4,after.getJSONObject("executionScope").getInt("modelCallLimit"));assertEquals(1,requests.size());assertEquals("profile-main",requests.get(0).getString("model"));
    }
    @Test public void failedOwnerScopeLeavesAnExplicitPendingProfileWithNoDefaultContinuation()throws Exception {
        worker.submit(()->{RunControl original=new RunControl();var selected=select(definition(),original);assertThrows(SecurityException.class,()->app.runOwnerWorkProfile("Review this same pending goal","",new JSONObject().put("approveAll",true),null,selected,original));assertEquals(1,app.tasks.list().length());JSONObject pending=app.tasks.list().getJSONObject(0);String id=pending.getString("id");pending=app.tasks.get(id,false);assertEquals("needs_confirmation",pending.getString("status"));assertTrue(pending.has("workProfileIntent"));assertFalse(pending.has("executionScope"));assertEquals("",pending.optString("runId"));assertThrows(SecurityException.class,()->app.tasks.startAttempt(id,"capabilities",4));
            int count=app.tasks.list().length();assertThrows(SecurityException.class,()->app.runOwnerWorkProfile("No duplicate goal","",null,null,selected,original));assertEquals(count,app.tasks.list().length());var page=app.nativeWorkProfiles().newOwnerPage();page.setForeground(true);try{var review=page.reviewForTask(app.tasks,id,"",pending.optInt("coordinatorEpoch"),new RunControl());RunControl next=new RunControl();var recovered=page.select(review,review.digest,next);JSONObject scope=new JSONObject().put("deadlineAt",System.currentTimeMillis()+300000).put("toolLimit",4).put("modelCallLimit",4);JSONObject output=app.continueOwnerWorkProfile(id,scope,null,recovered,next);assertEquals(id,output.getString("taskId"));assertFalse(app.tasks.get(id,false).has("workProfileIntent"));assertEquals(count,app.tasks.list().length());}finally{page.close();}return null;}).get(40,TimeUnit.SECONDS);assertEquals(1,requests.size());assertEquals("profile-main",requests.get(0).getString("model"));
    }
    @Test public void foreignNativeHostSelectionCannotCreateATaskOrSendARequest()throws Exception {
        worker.submit(()->{var foreign=new NativeWorkProfileHost(app.settings,new dev.pocketengine.features.controlplane.NativeWorkProfileSources(app.settings,app.features,app.pluginHost,java.util.Set.of(),new dev.pocketengine.features.controlplane.NativeWorkProfileTargetSources(app.features)));var page=foreign.newOwnerPage();page.setForeground(true);try{JSONObject saved=page.save("",0,"Foreign source",definition());var review=page.review(saved.getString("profileId"),1,new RunControl());RunControl original=new RunControl();var selected=page.select(review,review.digest,original);assertThrows(SecurityException.class,()->app.runOwnerWorkProfile("No foreign issuer","",null,null,selected,original));assertEquals(0,app.tasks.list().length());}finally{page.close();}return null;}).get(20,TimeUnit.SECONDS);assertEquals(0,requests.size());
    }

    @Test public void sourceOnlyOrPartialModelProfilesCannotSilentlyInheritUnreviewedGlobalDestinations()throws Exception {
        worker.submit(()->{for(String missing:new String[]{"MAIN","SUMMARY"}){JSONObject value=definition();value.getJSONObject("modelPolicies").remove(missing);RunControl original=new RunControl();var selected=select(value,original);SecurityException blocked=assertThrows(SecurityException.class,()->app.runOwnerWorkProfile("No unreviewed model inheritance","",null,null,selected,original));assertEquals("work_profile_precise_MAIN_SUMMARY_required",blocked.getMessage());assertEquals(0,app.tasks.list().length());}return null;}).get(20,TimeUnit.SECONDS);assertEquals(0,requests.size());
    }

}
