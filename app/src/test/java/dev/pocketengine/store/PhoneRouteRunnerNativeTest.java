package dev.pocketengine.store;

import android.app.Application;
import dev.pocketengine.core.*;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.routes.PhoneRouteSession;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Original Runner / HTTP / SQLite / native confirmation. Device and provider are explicit fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class PhoneRouteRunnerNativeTest {
    private static final String GOAL="打开搜索";
    private static final class Fixture implements AutoCloseable {
        final PhoneRouteSourceNativeTest.Fixture source=new PhoneRouteSourceNativeTest.Fixture();
        final JSONObject task;final RunControl original;final RunJournal journal;final List<JSONObject> events=new java.util.concurrent.CopyOnWriteArrayList<>(),requests=new java.util.concurrent.CopyOnWriteArrayList<>();
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);final AtomicReference<Throwable> serverError=new AtomicReference<>();
        JSONObject cached;int reads,effects,approvals;String lastObservation="";boolean unknown,sensitiveSecond,changePage,forgetAfterFirst,withdrawDuringResponse,denyChild,alias,learn,cancelAfterFirst,scroll;
        Fixture(boolean withSource)throws Exception {this(withSource,false);}
        Fixture(boolean withSource,boolean scrolling)throws Exception {
            scroll=scrolling;if(withSource)cached=source.source(true,2,scroll?"phone_scroll_node":"phone_click_node").getJSONObject("candidate");
            task=source.start(GOAL);original=source.control(task);if(scroll)source.tasks.authorizeTools(task.getString("id"),task.getString("runId"),new JSONArray().put("phone_scroll_node"));journal=source.tasks.journal(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),original);
            PhoneRouteSession.installFactory((id,live)->source.repository(source.tasks.get(id,false),live));
            server.createContext("/",exchange->{try{
                JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));int turn=requests.size();requests.add(request);
                JSONObject message;
                if(turn==0){
                    String name=learn?"phone_click_node":PhoneRouteSession.REPLAY;
                    JSONObject args=learn?new JSONObject().put("observationId",lastObservation).put("nodeId","search-current"):new JSONObject().put("routeId",cached.getString("routeId")).put("digest",cached.getString("digest"));
                    message=new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(new JSONObject().put("id","original-route-call").put("type","function").put("function",new JSONObject().put("name",name).put("arguments",args.toString()))));
                }else message=new JSONObject().put("role","assistant").put("content","原逐步回执已保留，请主人核对业务结果。");
                if(withdrawDuringResponse&&turn==0)source.tasks.forgetMemorySourceSnapshot(sourceRef());
                byte[] response=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",message.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
            }catch(Throwable failure){serverError.set(failure);try{exchange.sendResponseHeaders(500,0);}catch(Exception ignored){}}finally{exchange.close();}});server.start();
        }
        SourceRef sourceRef()throws Exception{return SourceRef.fromJson(source.tasks.phoneRouteSourceMetadata(cached.getString("sourceTaskId")).getJSONObject("sourceRef"));}
        JSONObject scene()throws Exception {
            reads++;lastObservation="current-"+reads;
            return new JSONObject().put("ok",true).put("observationId",lastObservation).put("packageName","app.example").put("treeSchemaVersion",1)
                    .put("observationBinding",new JSONObject().put("taskId",task.getString("id")).put("runId",task.getString("runId")).put("epoch",task.getInt("coordinatorEpoch")).put("sessionId","original-session").put("displayId",0))
                    .put("observationSources",new JSONObject().put("treeAvailable",true)).put("windows",new JSONArray().put(new JSONObject().put("rootAvailable",true)))
                    .put("nodes",new JSONArray().put(new JSONObject().put("id","search-current").put("resourceId","app.example:id/search").put("role","Button").put("text",changePage&&effects>0?"不同页面":"搜索").put("visible",true).put("enabled",true).put("clickable",true)));
        }
        final PhonePort phone=new PhonePort(){
            public boolean isConnected(){return true;}public void clearReferences(){}
            public JSONObject observe(boolean image,RunControl live)throws Exception{assertSame(original,live);assertFalse(image);return scene();}
            public JSONObject execute(String tool,JSONObject args,RunControl live)throws Exception{
                assertSame(original,live);assertEquals(scroll?"phone_scroll_node":"phone_click_node",tool);if(scroll)assertEquals("forward",args.getString("direction"));assertEquals(lastObservation,args.getString("observationId"));assertEquals("search-current",args.getString("nodeId"));
                JSONArray operations=source.tasks.get(task.getString("id"),false).getJSONArray("operations");assertEquals(effects+1,operations.length());JSONObject prepared=operations.getJSONObject(operations.length()-1);assertEquals("prepared",prepared.getString("state"));assertEquals(task.getString("runId"),prepared.getString("runId"));assertEquals(tool,prepared.getString("tool"));assertEquals(TaskStore.capabilityFingerprint(args),TaskStore.capabilityFingerprint(prepared.getJSONObject("arguments")));
                if(sensitiveSecond&&effects==1){var line=new dev.pocketengine.vision.ocr.OcrPage.Line(1,"SMS verification code",0,0,100,20);var page=new dev.pocketengine.vision.ocr.OcrPage(100,100,100,100,List.of(line));var targetConstructor=dev.pocketengine.phone.observation.OcrTextTarget.class.getDeclaredConstructor(String.class,long.class,RunControl.class,dev.pocketengine.vision.ocr.OcrPage.class,dev.pocketengine.vision.ocr.OcrPage.Line.class);targetConstructor.setAccessible(true);targetConstructor.newInstance(args.getString("observationId"),live.pauseRevision(),live,page,line).requireNonSensitive(live,live.pauseRevision());}
                effects++;if(cancelAfterFirst&&effects==1)original.cancel();if(forgetAfterFirst&&effects==1)source.tasks.forgetMemorySourceSnapshot(sourceRef());
                JSONObject actual=new JSONObject().put("ok",!unknown).put("accepted",unknown?JSONObject.NULL:true).put("code",unknown?"action_unknown":"native_fixture_ack").put("verified",false);if(scroll)actual.put("observation",scene());return actual;
            }
        };
        final CapabilityPort gates=new CapabilityPort(){
            public JSONArray tools()throws Exception{if(scroll)return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","phone_scroll_node").put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject().put("observationId",new JSONObject().put("type","string")).put("nodeId",new JSONObject().put("type","string")).put("direction",new JSONObject().put("type","string").put("enum",new JSONArray().put("forward").put("backward")))).put("required",new JSONArray().put("observationId").put("nodeId").put("direction")))));return alias?new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name",PhoneRouteSession.REPLAY).put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject())))):new JSONArray();}
            public boolean supports(String name){return scroll&&name.equals("phone_scroll_node");}public boolean sideEffecting(String name){return supports(name);}
            public JSONObject execute(String n,JSONObject a,RunControl c)throws Exception{if(supports(n)){JSONObject originalArguments=new JSONObject(a.toString());assertTrue(originalArguments.remove("_hostOperationId") instanceof String);return phone.execute(n,originalArguments,c);}throw new AssertionError("Route parent is not an alternative capability executor");}
            public JSONObject beforeTool(String name,JSONObject args,RunControl live)throws Exception{assertSame(original,live);if(name.equals("phone_click_node")||name.equals("phone_scroll_node")){approvals++;if(denyChild)return new JSONObject().put("ok",false).put("accepted",false).put("code","approval_required").put("message","原手机步骤尚未获本次批准");}return null;}
        };
        RunOutcome run()throws Exception {
            String endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
            ModelConfig config=new ModelConfig(endpoint,"fixture-only-private-key","route-native-fixture",false,20,10,"openai_chat",512).withCleartextConsent(ModelConfig.cleartextConsentFor("openai_chat",endpoint));
            RunOutcome result=new AgentRunner(phone,gates,selected->null).run(task.getString("id"),GOAL,config,original,events::add,journal);
            if(serverError.get()!=null)throw new AssertionError(serverError.get());return result;
        }
        JSONArray operations()throws Exception{return source.tasks.get(task.getString("id"),false).getJSONArray("operations");}
        public void close()throws Exception{PhoneRouteSession.installFactory((task,control)->null);server.stop(0);source.close();}
    }
    @Test public void actualRunnerLearnsOnlyAnUnverifiedCandidateUntilTheOriginalOwnerReviewConfirmsIt()throws Exception {
        try(Fixture fixture=new Fixture(false)){
            fixture.learn=true;RunOutcome outcome=fixture.run();assertEquals(outcome.summary,"needs_confirmation",outcome.status);assertEquals(1,fixture.effects);assertEquals(2,fixture.requests.size());JSONObject route=outcome.evidence.getJSONObject("nativePhoneRoute");assertFalse(route.getBoolean("businessVerified"));assertTrue(route.getBoolean("requiresOwnerSuccess"));
            assertNull(fixture.source.tasks.phoneRouteSourceMetadata(fixture.task.getString("id")));
            fixture.source.tasks.endAttempt(fixture.task.getString("id"),fixture.task.getString("runId"),outcome.status,outcome.summary,outcome.evidence);JSONObject current=fixture.source.tasks.get(fixture.task.getString("id"),false);fixture.source.tasks.confirmReviewedResult(fixture.task.getString("id"),current.getString("resultReviewDigest"),true,"");assertNotNull(fixture.source.tasks.phoneRouteSourceMetadata(fixture.task.getString("id")));
        }
    }
    @Test public void twoCachedChildrenStillUseFreshTreesOriginalApprovalAndOriginalPreparedReceipts()throws Exception {
        try(Fixture fixture=new Fixture(true)){
            RunOutcome outcome=fixture.run();assertEquals(outcome.summary,"needs_confirmation",outcome.status);assertEquals(2,fixture.effects);assertEquals(2,fixture.approvals);assertEquals("No model call between deterministic children",2,fixture.requests.size());assertEquals(2,fixture.operations().length());assertEquals(1,fixture.source.tasks.phoneRouteDependencies(fixture.task.getString("id")).length());
            for(int i=0;i<2;i++){JSONObject operation=fixture.operations().getJSONObject(i);assertEquals("receipt",operation.getString("state"));assertTrue(operation.getJSONObject("receipt").getBoolean("accepted"));assertEquals(fixture.task.getString("runId"),operation.getString("runId"));}
            String history=fixture.journal.restore().getJSONArray("messages").toString();assertTrue(history.contains("original-route-call"));assertTrue(history.contains("native_"));assertFalse(outcome.evidence.has("nativePhoneRoute"));
        }
    }
    @Test public void originalScrollContractReplaysThroughTheOriginalCapabilityGrantAndPreparedJournal()throws Exception {
        try(Fixture fixture=new Fixture(true,true)){RunOutcome outcome=fixture.run();assertEquals(outcome.summary,"needs_confirmation",outcome.status);assertEquals(2,fixture.effects);assertEquals(2,fixture.approvals);assertEquals(2,fixture.requests.size());assertEquals(2,fixture.operations().length());for(int i=0;i<2;i++){JSONObject operation=fixture.operations().getJSONObject(i);assertEquals("phone_scroll_node",operation.getString("tool"));assertEquals("forward",operation.getJSONObject("arguments").getString("direction"));assertEquals("receipt",operation.getString("state"));}}
    }
    @Test public void routeSuggestionsCannotBypassTheOriginalChildApprovalVeto()throws Exception {
        try(Fixture fixture=new Fixture(true)){fixture.denyChild=true;RunOutcome outcome=fixture.run();assertEquals(outcome.summary,"needs_confirmation",outcome.status);assertEquals(1,fixture.approvals);assertEquals(0,fixture.effects);assertEquals(0,fixture.operations().length());assertEquals("Original gate returns a known refusal; the original next model turn may only explain it",2,fixture.requests.size());assertTrue(fixture.journal.restore().toString().contains("approval_required"));}
    }
    @Test public void unknownFirstReceiptStopsBeforePreparingAnyOtherCachedChild()throws Exception {
        try(Fixture fixture=new Fixture(true)){fixture.unknown=true;RunOutcome outcome=fixture.run();assertEquals("awaiting_user",outcome.status);assertTrue(outcome.evidence.getBoolean("requiresReconciliation"));assertEquals(1,fixture.effects);assertEquals(1,fixture.operations().length());assertEquals("unknown",fixture.operations().getJSONObject(0).getString("state"));assertEquals(1,fixture.requests.size());}
    }
    @Test public void nativeSensitiveSecondRefusalRetainsTheRealPrefixAndNeverDispatchesTheRefusedChild()throws Exception {
        try(Fixture fixture=new Fixture(true)){fixture.sensitiveSecond=true;RunOutcome outcome=fixture.run();assertEquals("awaiting_user",outcome.status);assertEquals(1,fixture.effects);assertEquals(2,fixture.operations().length());assertTrue(fixture.operations().getJSONObject(0).getJSONObject("receipt").getBoolean("accepted"));JSONObject blocked=fixture.operations().getJSONObject(1).getJSONObject("receipt");assertFalse(blocked.getBoolean("accepted"));assertEquals("native_sensitive_owner_required",blocked.getString("code"));assertEquals(1,fixture.requests.size());}
    }
    @Test public void postconditionDivergenceRetainsTheFirstEffectAndReturnsToTheOriginalModelWithoutReplaying()throws Exception {
        try(Fixture fixture=new Fixture(true)){fixture.changePage=true;RunOutcome outcome=fixture.run();assertEquals(outcome.summary,"needs_confirmation",outcome.status);assertEquals(1,fixture.effects);assertEquals(1,fixture.operations().length());assertEquals(2,fixture.requests.size());assertTrue(fixture.events.toString().contains("diverged"));assertTrue(fixture.events.toString().contains("remainingNotDispatched"));}
    }
    @Test public void retiredConfirmedSourceAfterOneEffectPausesWithoutPreparingTheSecondOrErasingTheReceipt()throws Exception {
        try(Fixture fixture=new Fixture(true)){fixture.forgetAfterFirst=true;RunOutcome outcome=fixture.run();assertEquals(outcome.summary,"awaiting_user",outcome.status);assertEquals(1,fixture.effects);assertEquals(1,fixture.operations().length());assertEquals("receipt",fixture.operations().getJSONObject(0).getString("state"));assertEquals(1,fixture.requests.size());assertEquals("source_current_unavailable",outcome.evidence.getJSONObject("phoneRouteSource").getString("status"));}
    }
    @Test public void temporaryUnavailableStickySourceStopsBeforeAnyNewMainRequestOrDeviceRead()throws Exception {
        try(Fixture fixture=new Fixture(true)){assertEquals(1,fixture.source.repository(fixture.task,fixture.original).routes(GOAL,fixture.original).length());fixture.source.sourceUnavailable=true;RunOutcome outcome=fixture.run();assertEquals("awaiting_user",outcome.status);assertEquals(0,fixture.requests.size());assertEquals(0,fixture.reads);assertEquals(0,fixture.effects);fixture.source.sourceUnavailable=false;fixture.source.repository(fixture.task,fixture.original).requireCurrentSources(fixture.original);assertNotNull(fixture.source.tasks.phoneRouteSourceMetadata(fixture.cached.getString("sourceTaskId")));}
    }
    @Test public void sourceRetiredDuringTheRealModelRequestBlocksItsDecisionBeforeAnyChild()throws Exception {
        try(Fixture fixture=new Fixture(true)){assertEquals(1,fixture.source.repository(fixture.task,fixture.original).routes(GOAL,fixture.original).length());fixture.withdrawDuringResponse=true;RunOutcome outcome=fixture.run();assertEquals(outcome.summary,"awaiting_user",outcome.status);assertEquals(1,fixture.requests.size());assertEquals(0,fixture.effects);assertEquals(0,fixture.operations().length());}
    }
    @Test public void ownerCancellationAfterOneKnownReceiptNeverReadsOrDispatchesTheNextCachedChild()throws Exception {
        try(Fixture fixture=new Fixture(true)){fixture.cancelAfterFirst=true;RunOutcome outcome=fixture.run();assertEquals("cancelled",outcome.status);assertEquals(1,fixture.effects);assertEquals(1,fixture.operations().length());assertEquals("receipt",fixture.operations().getJSONObject(0).getString("state"));assertEquals(1,fixture.requests.size());}
    }
    @Test public void aRestoredOriginalCheckpointDoesNotAdvertiseOrAutomaticallyReplayTheOldRoute()throws Exception {
        try(Fixture fixture=new Fixture(true)){
            fixture.source.repository(fixture.task,fixture.original).routes(GOAL,fixture.original);
            fixture.journal.checkpoint(new JSONArray().put(new JSONObject().put("role","user").put("content",GOAL)),0,new JSONObject());RunOutcome outcome=fixture.run();
            assertEquals("Unadvertised restored-route call is rejected by the original gateway", "failed",outcome.status);assertEquals(0,fixture.effects);assertEquals(0,fixture.operations().length());assertEquals(1,fixture.requests.size());
            JSONArray tools=fixture.requests.get(0).getJSONArray("tools");boolean list=false;for(int i=0;i<tools.length();i++){String name=tools.getJSONObject(i).getJSONObject("function").getString("name");assertNotEquals(PhoneRouteSession.REPLAY,name);list|=PhoneRouteSession.LIST.equals(name);}assertTrue(list);
        }
    }
    @Test public void pluginCannotClaimTheNativeRouteSchemaOrExecutor()throws Exception {
        try(Fixture fixture=new Fixture(true)){fixture.alias=true;RunOutcome outcome=fixture.run();assertEquals("failed",outcome.status);assertEquals(0,fixture.requests.size());assertEquals(0,fixture.effects);}
    }
}
