package dev.pocketengine.store;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.*;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.models.*;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Real SettingsStore/catalog, SQL TaskStore, Runner, Gateway and physical loopback requests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class StepModelRoutingNativeJourneyTest {
    private static final class Secrets implements SettingsStore.ApiSecrets {
        final Map<String,String> values=new HashMap<>();
        public String get(String slot){return values.getOrDefault(slot,"");}
        public void put(String slot,String value){values.put(slot,value);}
    }
    private Application app; private SettingsStore settings; private ModelConnectionService models;
    private TaskStore tasks; private JSONObject task; private RunControl control; private RunJournal journal;
    private HttpServer server; private ModelConfig selected; private final List<String> wireModels=new CopyOnWriteArrayList<>();
    private final List<JSONObject> events=new CopyOnWriteArrayList<>();
    private final AtomicInteger reads=new AtomicInteger(),writes=new AtomicInteger();
    private volatile boolean changedSource,disableOnLight,partialLight,invalidLightRead,staleReadDuringLight,withPhone,readSourceCurrent=true;
    private volatile int lightStatus=200;

    @Before public void open()throws Exception {
        app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");
        var prefs=app.getSharedPreferences("private_settings",0);assertTrue(prefs.edit().clear().commit());
        settings=new SettingsStore(prefs,new Secrets());
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{try{
            JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            String model=request.getString("model");wireModels.add(model);int number=wireModels.size();boolean light="light".equals(model);
            if(light&&staleReadDuringLight)readSourceCurrent=false;
            if(light&&disableOnLight){JSONObject old=settings.stepRoutingPreferences();settings.saveStepRoutingPreference(false,old.getLong("revision"),"","");}
            int status=light?lightStatus:200;byte[] bytes;
            if(status!=200)bytes="{\"error\":{\"code\":\"invalid_request\"}}".getBytes(StandardCharsets.UTF_8);
            else if(light&&partialLight){
                bytes="data: {\"id\":\"partial\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"unfinished\"},\"finish_reason\":null}]}\n\n".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","text/event-stream");
            }else{
                JSONObject message=new JSONObject().put("role","assistant");
                if(number<=2||light)message.put("tool_calls",new JSONArray().put(new JSONObject().put("id","fixture-call-"+number).put("type","function")
                        .put("function",new JSONObject().put("name",light&&!invalidLightRead?"dangerous_write":"browser_task_observe").put("arguments",light&&invalidLightRead?"{\"invalid\":true}":"{}"))));
                else message.put("content","根据原始读取结果结束本次合成核查。");
                bytes=new JSONObject().put("id","reply-"+number).put("object","chat.completion").put("model",model)
                        .put("choices",new JSONArray().put(new JSONObject().put("index",0).put("message",message).put("finish_reason",message.has("tool_calls")?"tool_calls":"stop")))
                        .put("usage",new JSONObject().put("prompt_tokens",10).put("completion_tokens",5).put("total_tokens",15)).toString().getBytes(StandardCharsets.UTF_8);
            }
            if(!(light&&partialLight))exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
        }finally{exchange.close();}});server.start();
        models=new ModelConnectionService(settings,Files.createTempDirectory("step-routing-catalog").resolve("catalog.json"),Clock.systemUTC());models.install();models.connections();
        JSONObject profile=settings.saveApiConnection("",0,"Synthetic route",new JSONObject().put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()+"/v1")
                .put("provider","openai_chat").put("model","main").put("apiKey","fixture-private-key").put("sendScreenshots",false).put("maxSteps",8));
        settings.selectApiConnection(profile.getString("id"),profile.getLong("revision"));
        ModelBinding binding=ModelBinding.fromJson(models.configure(null,ModelBinding.Format.OPENAI,java.net.URI.create(settings.apiConfigForCatalog().baseUrl+"/models")).getJSONObject("binding"));
        for(String model:List.of("main","light")){
            models.editField(binding,model,0,ModelFacts.Field.TOOL_CALLS,ModelFacts.OwnerValue.known(true),false);
            models.editField(binding,model,1,ModelFacts.Field.CHAT_OUTPUT_PARAMETER,ModelFacts.OwnerValue.known(ModelFacts.ChatOutputParameter.MAX_TOKENS),false);
        }
        JSONArray candidates=new JSONArray();
        for(String name:List.of("main","light"))candidates.put(new JSONObject().put("candidateId",name).put("target",ModelTargetRef.exact(profile.getString("id"),name).toJson())
                .put("enabled",true).put("allowedPurposes",new JSONArray().put("MAIN")).put("allowedAudiences",new JSONArray().put("APP_OWNER")));
        JSONObject group=settings.saveModelGroup("",0,"Synthetic main and light",new JSONObject().put("allowedAccounts",new JSONObject().put("mode","EXPLICIT").put("refs",new JSONArray().put(profile.getString("id"))))
                .put("candidates",candidates).put("attemptPolicy",new JSONObject().put("maxAttempts",1).put("maxRouteSwitches",0)));
        settings.selectModelPurpose("MAIN",ModelTargetRef.group(group.getString("groupId"),group.getLong("revision")));
        JSONObject options=models.stepRoutingSnapshot();assertFalse(options.getBoolean("enabled"));
        assertEquals(1,options.getJSONArray("candidates").length());assertTrue(options.getJSONArray("candidates").getJSONObject(0).getBoolean("capabilitiesKnown"));
        models.saveStepRouting(true,options.getLong("revision"),options.getString("currentPlanFingerprint"),"light");selected=settings.get();
        tasks=new TaskStore(app);task=tasks.startAttempt(tasks.createCapabilityTask("读取合成资料并核对结果","conversation").getString("id"),"capabilities",8);
        control=new RunControl();control.bindHostRun(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"));
        journal=tasks.journal(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),control);
    }
    @After public void close()throws Exception {
        if(control!=null)control.cancel();if(server!=null)server.stop(0);
        if(tasks!=null){var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(tasks)).close();}
    }
    private RunOutcome run()throws Exception {
        CapabilityPort port=new CapabilityPort(){
            public JSONArray tools()throws Exception {
                JSONArray declarations=new JSONArray();for(String name:List.of("browser_task_observe","dangerous_write"))declarations.put(new JSONObject().put("type","function").put("function",new JSONObject()
                        .put("name",name).put("description","Explicit synthetic fixture capability").put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject()))));return declarations;
            }
            public boolean supports(String name){return Set.of("browser_task_observe","dangerous_write").contains(name);}
            public boolean sideEffecting(String name){return !"browser_task_observe".equals(name);}
            public boolean stepReadSourceCurrent(String name,JSONObject receipt,RunControl original){assertSame(control,original);return readSourceCurrent;}
            public void validate(String name,JSONObject args){if(args.optBoolean("invalid"))throw new IllegalArgumentException("fixture_invalid_read");}
            public JSONObject execute(String name,JSONObject args,RunControl original)throws Exception {
                assertSame(control,original);if(!"browser_task_observe".equals(name)){writes.incrementAndGet();throw new AssertionError("Rejected light proposal must never dispatch");}
                int n=reads.incrementAndGet();return new JSONObject().put("ok",true).put("status","observed").put("browserSessionId","original-session").put("tabId","original-tab")
                        .put("tabGeneration",1).put("navigation",changedSource?n:1).put("pageIdentityDigest","source-identity").put("documentId","document-"+(changedSource?n:1));
            }
        };
        PhonePort phone=withPhone?new PhonePort(){
            public boolean isConnected(){return true;}
            public void clearReferences(){}
            public JSONObject observe(boolean screenshot,RunControl original)throws Exception {
                assertSame(control,original);original.check();return new JSONObject().put("ok",true).put("packageName","dev.fixture.routing")
                        .put("sessionId","phone-fixture").put("displayId",0).put("pauseRevision",original.pauseRevision())
                        .put("treeFingerprint","phone-tree").put("text","Original phone fixture").put("nodes",new JSONArray());
            }
            public JSONObject execute(String name,JSONObject args,RunControl original){throw new AssertionError("browser reads must not dispatch a phone action");}
        }:null;
        return new AgentRunner(phone,port).run(task.getString("id"),"读取合成资料并核对结果",selected,control,events::add,journal);
    }
    private void checkReceipts(int count)throws Exception {
        JSONObject usage=tasks.inferenceUsage(task.getString("id"));assertEquals(count,usage.getInt("attempts"));
        Set<String> ids=new HashSet<>();JSONArray receipts=usage.getJSONArray("receipts");
        for(int i=0;i<receipts.length();i++){JSONObject receipt=receipts.getJSONObject(i);assertTrue(ids.add(receipt.getString("callId")));
            JSONObject context=receipt.getJSONObject("context");assertEquals(task.getString("id"),context.getString("taskId"));assertEquals(task.getString("runId"),context.getString("runId"));
            assertFalse(receipt.toString().contains("fixture-private-key"));}
        assertEquals(count,usage.getJSONArray("logicalRequests").length());
    }
    @Test public void unsafeLightProposalFallsBackBeforeAnyEffectAndRetainsEveryPhysicalReceipt()throws Exception {
        RunOutcome outcome=run();assertEquals(outcome.summary,"needs_confirmation",outcome.status);
        assertEquals(List.of("main","main","light","main"),wireModels);assertEquals(2,reads.get());assertEquals(0,writes.get());checkReceipts(4);
        assertFalse(journal.restore().getJSONArray("messages").toString().contains("dangerous_write"));
        assertTrue(events.stream().anyMatch(e->"model_route".equals(e.optString("type"))&&"light_proposal_not_known_read".equals(e.optJSONObject("receipt")==null?"":e.optJSONObject("receipt").optString("reason"))));
    }
    @Test public void changedSourceRevisionBreaksSuccessChainAndNeverUsesLight()throws Exception {
        changedSource=true;assertEquals("needs_confirmation",run().status);assertEquals(List.of("main","main","main"),wireModels);checkReceipts(3);
    }
    @Test public void disabledPreferenceDuringPhysicalRequestRejectsOldCallback()throws Exception {
        disableOnLight=true;assertEquals("needs_confirmation",run().status);assertEquals(List.of("main","main","light","main"),wireModels);
        assertEquals(0,writes.get());checkReceipts(4);assertFalse(settings.stepRoutingPreferences().getBoolean("enabled"));
    }
    @Test public void definiteLightFailureReturnsToMainWithoutRefundOrToolReplay()throws Exception {
        lightStatus=400;assertEquals("needs_confirmation",run().status);assertEquals(List.of("main","main","light","main"),wireModels);
        assertEquals(2,reads.get());checkReceipts(4);assertEquals(1,tasks.inferenceUsage(task.getString("id")).getInt("failed"));
    }
    @Test public void interruptedPartialLightStreamNeverStartsAnotherModelRequest()throws Exception {
        partialLight=true;RunOutcome outcome=run();assertNotEquals("needs_confirmation",outcome.status);
        assertEquals(List.of("main","main","light"),wireModels);assertEquals(0,writes.get());checkReceipts(3);
    }
    @Test public void duplicateOwnerOptInUsesRevisionFenceAndCancelledRunCannotChooseLight()throws Exception {
        JSONObject before=models.stepRoutingSnapshot();models.saveStepRouting(false,before.getLong("revision"),"","");
        try{models.saveStepRouting(true,before.getLong("revision"),before.getString("currentPlanFingerprint"),"light");fail("stale preference accepted");}catch(SecurityException expected){}
        control.cancel();RunOutcome outcome=run();assertEquals("cancelled",outcome.status);assertTrue(wireModels.isEmpty());checkReceipts(0);
    }
    @Test public void rejectedReadArgumentsReturnToMainWithoutDispatch()throws Exception {
        invalidLightRead=true;assertEquals("needs_confirmation",run().status);
        assertEquals(List.of("main","main","light","main"),wireModels);assertEquals(2,reads.get());checkReceipts(4);
    }
    @Test public void navigationDuringLightRequestDiscardsOldResponse()throws Exception {
        staleReadDuringLight=true;assertEquals("needs_confirmation",run().status);
        assertEquals(List.of("main","main","light","main"),wireModels);assertEquals(0,writes.get());checkReceipts(4);
    }
    @Test public void originalPhoneObservationDoesNotInvalidateIndependentBrowserReadIdentity()throws Exception {
        withPhone=true;RunOutcome outcome=run();assertTrue(outcome.summary,outcome.status.matches("completed|needs_confirmation"));
        assertEquals(List.of("main","main","light","main"),wireModels);assertEquals(2,reads.get());checkReceipts(4);
    }
    @Test public void narrowedStepSurvivesTheGatewaysMainPurposeProjection()throws Exception {
        StepModelRouting.Session session=StepModelRouting.open(selected,control,true);
        CapabilityPort original=new CapabilityPort(){
            public JSONArray tools(){return new JSONArray();}
            public boolean supports(String name){return "browser_task_observe".equals(name);}
            public boolean sideEffecting(String name){return false;}
            public boolean stepReadSourceCurrent(String name,JSONObject receipt,RunControl actual){assertSame(control,actual);return true;}
            public JSONObject execute(String name,JSONObject args,RunControl actual){throw new AssertionError("selection does not execute tools");}
        };
        assertEquals("main",session.choose(new JSONArray(),null,original).forPurpose("MAIN").model);
        JSONObject read=new JSONObject().put("ok",true).put("status","observed").put("browserSessionId","original-session").put("tabId","original-tab")
                .put("tabGeneration",1).put("navigation",1).put("pageIdentityDigest","source-identity").put("documentId","document-1");
        session.completedRead("browser_task_observe",read,null,true);session.completedRead("browser_task_observe",read,null,true);
        ModelConfig light=session.choose(new JSONArray(),null,original);assertTrue(session.selectedLight());assertEquals("light",light.model);
        ModelConfig admitted=light.forPurpose("MAIN");assertEquals("light",admitted.model);assertEquals(selected.routePlan.candidates.size(),admitted.routePlan.candidates.size());
        assertEquals(1,admitted.routePlan.dispatchMaxAttempts());
        assertTrue(admitted.routePlan.dispatches(admitted.routePlan.candidates.get(1)));assertFalse(admitted.routePlan.dispatches(admitted.routePlan.candidates.get(0)));
        assertEquals(StepModelRouting.planFingerprint(selected.routePlan),StepModelRouting.planFingerprint(admitted.routePlan));
        assertFalse(admitted.routePlan.toJson().has("nativeStep"));
        ModelRoutePlan restored=new ModelRoutePlan(admitted.routePlan.requestedAlias,admitted.routePlan.groupRevision,admitted.routePlan.purpose,admitted.routePlan.audience,
                admitted.routePlan.candidates,admitted.routePlan.maxAttempts,admitted.routePlan.maxRouteSwitches);
        assertEquals("main",restored.primary().model); // Original serialized identity cannot revive the transient preference.
        try{ModelConfig.fromJson(new JSONObject().put("model","light").put("routePlan",admitted.routePlan.toJson()));fail("model JSON installed a native route");}catch(IllegalArgumentException expected){}
        assertEquals(selected.routePlan.candidates.size(),selected.forPurpose("MAIN").routePlan.candidates.size());
        assertEquals("main",selected.forPurpose("MAIN").model);assertTrue(wireModels.isEmpty());checkReceipts(0);
    }

    @Test public void nativeDispatchCapKeepsFullSqlIdentityAndRejectsExpansionOrRunChanges()throws Exception {
        JSONObject old=settings.modelGroupsSnapshot().getJSONArray("groups").getJSONObject(0);
        old.getJSONObject("attemptPolicy").put("maxAttempts",3);
        JSONObject saved=settings.saveModelGroup(old.getString("groupId"),old.getLong("revision"),old.getString("name"),old);
        settings.selectModelPurpose("MAIN",ModelTargetRef.group(saved.getString("groupId"),saved.getLong("revision")));selected=settings.get();
        JSONObject complete=selected.routePlan.toJson();assertEquals(2,complete.getJSONArray("orderedCandidates").length());assertEquals(3,complete.getInt("maxAttempts"));
        var authority=journal.inferenceTarget(dev.pocketengine.runtime.usage.InferenceReceipt.Purpose.MAIN,"").logical;
        long deadline=System.currentTimeMillis()+60000;String digest="0".repeat(64);
        JSONObject light=authority.begin(UUID.randomUUID().toString(),digest,complete,deadline,1);assertEquals(1,light.getJSONObject("control").getInt("maxAttempts"));
        JSONObject main=authority.begin(UUID.randomUUID().toString(),digest,complete,deadline,3);assertEquals(3,main.getJSONObject("control").getInt("maxAttempts"));
        assertEquals(complete.getJSONArray("orderedCandidates").toString(),light.getJSONObject("routeSnapshot").getJSONArray("orderedCandidates").toString());
        assertEquals(complete.getLong("groupRevision"),light.getJSONObject("routeSnapshot").getLong("groupRevision"));
        for(String field:List.of("requestedAlias","groupRevision","configurationDigest","credentialRevision")){
            JSONObject changed=new JSONObject(complete.toString());
            if(field.equals("requestedAlias"))changed.put(field,"other-original-group");
            else if(field.equals("groupRevision"))changed.put(field,changed.getLong(field)+1);
            else if(field.equals("configurationDigest"))changed.getJSONArray("orderedCandidates").getJSONObject(0).put(field,"1".repeat(64));
            else {JSONObject binding=changed.getJSONArray("orderedCandidates").getJSONObject(0).getJSONObject("binding");binding.put(field,binding.getLong(field)+1);}
            try{authority.begin(UUID.randomUUID().toString(),digest,changed,deadline,1);fail("changed complete run identity accepted: "+field);}catch(SecurityException expected){assertEquals("original_run_model_selection_changed",expected.getMessage());}
        }
        for(int cap:new int[]{0,4})try{authority.begin(UUID.randomUUID().toString(),digest,complete,deadline,cap);fail("native cap expanded or invalid");}catch(SecurityException expected){assertEquals("native_dispatch_cap_cannot_expand_original_plan",expected.getMessage());}
        var summary=journal.inferenceTarget(dev.pocketengine.runtime.usage.InferenceReceipt.Purpose.SUMMARY,"").logical;
        JSONObject other=ModelRoutePlan.exact(selected,"SUMMARY","APP_OWNER").toJson();
        assertEquals(1,summary.begin(UUID.randomUUID().toString(),digest,other,deadline,1).getJSONObject("control").getInt("maxAttempts"));
        assertEquals(0,tasks.inferenceUsage(task.getString("id")).getInt("attempts"));assertTrue(wireModels.isEmpty());
        control.cancel();try{authority.begin(UUID.randomUUID().toString(),digest,complete,deadline,1);fail("cancelled original run admitted");}catch(InterruptedException expected){}
    }

}
