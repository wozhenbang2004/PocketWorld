package dev.pocketengine.features.semantic;

import android.app.Application;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import android.provider.AlarmClock;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.EventSink;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.phone.PhoneAccessibilityService;
import dev.pocketengine.phone.display.DisplayModule;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.testutil.HttpServer;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real TaskStore, original Runner, native owner approval and retained Module binding; PM/model are controlled fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,application=Application.class,shadows={SemanticTypedShareRunnerSqlTest.Safety.class,SemanticBackgroundDisplayFixture.class})
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class SemanticTypedShareRunnerSqlTest {
    @org.robolectric.annotation.Implements(PhoneAccessibilityService.class)
    public static final class Safety {static int checks;static RunControl cancel;@org.robolectric.annotation.Implementation public String observationSafetyFailure(){if(++checks==3&&cancel!=null)cancel.cancel();return null;}}

    @Test public void shareNeedsOriginalOwnerApprovalThenOneNativeIntentAndNoAutomaticSendOrFurtherModelTurn()throws Exception{journey(SemanticTypedShareRequestTest.share(),Intent.ACTION_SEND);}
    @Test public void httpsPackageLinkNeedsOriginalApprovalAndPausesForTheOwnerToVerifyTheActualTarget()throws Exception{journey(SemanticTypedShareRequestTest.link(),Intent.ACTION_VIEW);}
    @Test public void changedShareReceiverVersionWhileOwnerReviewsCannotConsumeOldApprovalOrLaunch()throws Exception{journey(SemanticTypedShareRequestTest.share(),Intent.ACTION_SEND,true);}
    @Test public void changedHttpsReceiverVersionWhileOwnerReviewsHasNoLaunchOrBrowserFallback()throws Exception{journey(SemanticTypedShareRequestTest.link(),Intent.ACTION_VIEW,true);}
    @Test public void backgroundShareUsesTheOriginalApprovalRunnerAndExactNativeDisplay()throws Exception{journey(SemanticTypedShareRequestTest.share(),Intent.ACTION_SEND,false,Lane.BACKGROUND);}
    @Test public void backgroundHttpsUsesTheOriginalAppLinksContractAndNativeDisplay()throws Exception{journey(SemanticTypedShareRequestTest.link(),Intent.ACTION_VIEW,false,Lane.BACKGROUND);}
    @Test public void backgroundTargetChangedDuringOwnerReviewNeverReachesNativeHelper()throws Exception{journey(SemanticTypedShareRequestTest.share(),Intent.ACTION_SEND,true,Lane.BACKGROUND);}
    @Test public void lostBackgroundNativeReplyLeavesOneUnresolvedOriginalOperationWithoutReplay()throws Exception{journey(SemanticTypedShareRequestTest.share(),Intent.ACTION_SEND,false,Lane.BACKGROUND_UNKNOWN);}
    @Test public void backgroundWrongUserLandingPausesWithoutClaimingTargetAccountOrDelivery()throws Exception{journey(SemanticTypedShareRequestTest.share(),Intent.ACTION_SEND,false,Lane.BACKGROUND_WRONG_USER);}
    private enum Lane {MAIN,BACKGROUND,BACKGROUND_UNKNOWN,BACKGROUND_WRONG_USER}
    private static JSONObject j(Object... pairs)throws Exception{JSONObject out=new JSONObject();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}
    private static void journey(JSONObject parameters,String expectedAction)throws Exception{journey(parameters,expectedAction,false);}
    private static void journey(JSONObject parameters,String expectedAction,boolean targetChanged)throws Exception{
        journey(parameters,expectedAction,targetChanged,Lane.MAIN);
    }
    private static void journey(JSONObject parameters,String expectedAction,boolean targetChanged,Lane lane)throws Exception{
        boolean background=lane!=Lane.MAIN;SemanticBackgroundDisplayFixture.reset(lane==Lane.BACKGROUND_UNKNOWN,lane==Lane.BACKGROUND_WRONG_USER);
        Application app=RuntimeEnvironment.getApplication();TaskStore tasks=new TaskStore(app);RunControl control=new RunControl();FeatureContext feature=new FeatureContext(app,null,new SecretVault(app),event->{});SemanticModule module=new SemanticModule(feature);DisplayModule display=null;HttpServer server=null;ExecutorService worker=Executors.newSingleThreadExecutor();
        Field instance=PhoneAccessibilityService.class.getDeclaredField("instance");instance.setAccessible(true);
        try{
            Safety.checks=0;Safety.cancel=null;
            SemanticRequest request=SemanticRequest.parse(parameters);AndroidTypedShareIntentTest.install(request,"share.native.fixture",true,true,true,1);PhoneAccessibilityService accessibility=Robolectric.buildService(PhoneAccessibilityService.class).create().get();instance.set(null,accessibility);
            String mode=background?"background":"main_screen";JSONObject task=tasks.startAttempt(tasks.create("打开主人逐次确认的准确App入口并核对结果",mode).getString("id"),mode,12);String id=task.getString("id"),run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");control.bindHostRun(id,run,epoch);tasks.authorizeTools(id,run,new JSONArray().put(SemanticCapabilityPort.DISCOVER).put(SemanticCapabilityPort.DISPATCH));tasks.status(id,"running","controlled original typed-entry journey");
            ModelCapabilityAdapters.Binding binding=new ModelCapabilityAdapters.Binding(id,run,epoch,control,true,"");RunJournal journal=tasks.journal(id,run,epoch,control);int[] cleared={0};if(background)display=new DisplayModule(feature);PhonePort selected=background?new dev.pocketengine.phone.display.BackgroundPhonePort(display):new PhonePort(){public boolean isConnected(){return true;}public void clearReferences(){cleared[0]++;}public JSONObject observe(boolean image,RunControl supplied){throw new AssertionError("no physical phone observation is claimed");}public JSONObject execute(String n,JSONObject a,RunControl supplied){throw new AssertionError("no physical tap is requested");}};
            AtomicReference<Throwable> failed=new AtomicReference<>();JSONObject[] exact={null};int[] requests={0};server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{try{
                JSONObject body=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));int call=++requests[0];String tool;JSONObject args;
                if(call==1){tool=SemanticCapabilityPort.DISCOVER;args=request.parameters();}
                else if(call==2){JSONArray messages=body.getJSONArray("messages");JSONObject found=null;for(int i=messages.length()-1;i>=0;i--)if(messages.getJSONObject(i).optString("role").equals("tool")){JSONObject r=new JSONObject(messages.getJSONObject(i).getString("content"));if(r.has("discoveryId")){found=r;break;}}assertNotNull(found);exact[0]=j("discoveryId",found.getString("discoveryId"),"candidateId",found.getJSONArray("candidates").getJSONObject(0).getString("candidateId"));tool=SemanticCapabilityPort.DISPATCH;args=exact[0];}
                else{assertEquals("typed entry launch must stop before another model decision",3,call);assertTrue(body.getJSONArray("messages").toString().contains(exact[0].getString("discoveryId")));tool=SemanticCapabilityPort.DISPATCH;args=exact[0];}
                JSONObject answer=j("role","assistant","tool_calls",new JSONArray().put(j("id","typed-call-"+call,"type","function","function",j("name",tool,"arguments",args.toString()))));byte[] bytes=j("choices",new JSONArray().put(j("message",answer,"finish_reason","tool_calls"))).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Throwable error){failed.set(error);try{exchange.sendResponseHeaders(500,0);}catch(Exception ignored){}}finally{exchange.close();}});server.start();
            ModelConfig config=new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort(),"fictional-entry-fixture-key","typed-native-runner",false,30,8);EventSink events=event->{try{tasks.event(id,run,epoch,event);}catch(Exception e){throw new IllegalStateException(e);}};
            CapabilityPort first=module.bind(null,tasks,binding,control,selected,events);RunOutcome pending=await(worker.submit(()->new AgentRunner(null,first).run(id,"请执行主人确认后的单次准确App入口请求",config,control,events,journal)));assertNull(String.valueOf(failed.get()),failed.get());assertEquals("awaiting_user",pending.status);assertEquals(2,requests[0]);assertEquals(0,cleared[0]);assertNull(Shadows.shadowOf(app).getNextStartedActivity());
            JSONObject approval=tasks.get(id,false).getJSONArray("approvals").getJSONObject(0);assertEquals("owner_review phase", "pending",approval.getString("state"));
            JSONObject capability=approval.optJSONObject("capability");assertNotNull("owner_review must retain actual structured capability",capability);
            assertTrue(capability.getBoolean("receiverMayActImmediately"));assertTrue(capability.getBoolean("requiresOwnerCompletion"));assertFalse(capability.getBoolean("automaticContinuation"));
            assertEquals(request.parameters().toString(),capability.getJSONObject("request").toString());String review=SemanticApprovalText.describe(capability);assertTrue(review,review.contains(request.kind.equals("share_text")?parameters.getString("text"):parameters.getString("url")));assertTrue(review.contains("可能立即")&&review.contains("主人核验"));
            assertEquals(background?"background":"main_screen",capability.getJSONObject("resource").getString("executionMode"));if(background){assertEquals(SemanticBackgroundDisplayFixture.DISPLAY,capability.getJSONObject("resource").getInt("displayId"));assertTrue(review.contains("原任务后台屏 7"));assertFalse(review.contains("位置：手机主屏"));}
            control.pause();tasks.decideCapabilityApproval(id,approval.getString("id"),true);control.resume();tasks.status(id,"running","owner approved exact original typed-entry request");
            if(targetChanged)AndroidTypedShareIntentTest.install(request,"share.native.fixture",true,true,true,2);
            CapabilityPort resumed=module.bind(null,tasks,binding,control,selected,events);RunOutcome accepted=await(worker.submit(()->new AgentRunner(null,resumed).run(id,"请执行主人确认后的单次准确App入口请求",config,control,events,journal)));assertNull(String.valueOf(failed.get()),failed.get());assertEquals("awaiting_user",accepted.status);assertEquals(3,requests[0]);
            if(targetChanged){assertEquals(0,cleared[0]);assertNull(Shadows.shadowOf(app).getNextStartedActivity());JSONObject unchanged=tasks.get(id,false);assertEquals("approved",unchanged.getJSONArray("approvals").getJSONObject(0).getString("state"));JSONArray ops=unchanged.getJSONArray("operations");assertEquals(2,ops.length());JSONObject refused=ops.getJSONObject(1).getJSONObject("receipt");assertFalse(refused.getBoolean("accepted"));assertEquals("needs_user",refused.getString("code"));assertFalse(tasks.hasUnresolved(id));return;}
            assertEquals(background?0:1,cleared[0]);if(background)assertEquals(1,SemanticBackgroundDisplayFixture.dispatches);
            var launched=Shadows.shadowOf(app).getNextStartedActivityForResult();assertNotNull(launched);Intent actual=launched.intent;assertEquals(background?SemanticBackgroundDisplayFixture.DISPLAY:0,launched.options.getInt("android.activity.launchDisplayId",-1));assertEquals(expectedAction,actual.getAction());assertEquals("share.native.fixture",actual.getPackage());assertEquals("share.native.fixture/share.native.fixture.Main",actual.getComponent().flattenToString());if(request.kind.equals("share_text")){dev.pocketengine.displayhelper.DisplayTypedIntentNativeTest.assertOnlyAuthorizedTextPayload(actual,parameters.getString("text"));}else{assertEquals(parameters.getString("url"),actual.getDataString());assertNull(actual.getExtras());}
            assertSame("two Robolectric recording queues describe the same single native launch",actual,Shadows.shadowOf(app).getNextStartedActivity());assertNull(Shadows.shadowOf(app).getNextStartedActivityForResult());assertNull(Shadows.shadowOf(app).getNextStartedActivity());JSONObject after=tasks.get(id,false);assertEquals(run,after.getString("runId"));assertEquals(epoch,after.getInt("coordinatorEpoch"));assertEquals("consumed",after.getJSONArray("approvals").getJSONObject(0).getString("state"));
            JSONArray operations=after.getJSONArray("operations");assertEquals(2,operations.length());JSONObject receipt=operations.getJSONObject(1).getJSONObject("receipt");
            if(lane==Lane.BACKGROUND_UNKNOWN){assertTrue(receipt.isNull("accepted"));assertTrue(tasks.hasUnresolved(id));assertTrue(receipt.getBoolean("requiresReconciliation"));assertTrue(receipt.getJSONObject("nativeResult").isNull("accepted"));JSONObject duplicate=resumed.execute(SemanticCapabilityPort.DISPATCH,new JSONObject(exact[0].toString()).put("_hostOperationId",receipt.getString("operationId")),control);assertTrue(duplicate.isNull("accepted"));assertEquals(1,SemanticBackgroundDisplayFixture.dispatches);return;}
            assertFalse(tasks.hasUnresolved(id));assertTrue(receipt.getBoolean("accepted"));assertFalse(receipt.getBoolean("externalDeliveryVerified"));assertFalse(receipt.getBoolean("automaticContinuation"));assertTrue(receipt.getBoolean("requiresOwnerCompletion"));assertFalse(receipt.getBoolean("verifiedBusinessSuccess"));assertTrue(receipt.getBoolean("requiresPause"));assertFalse(receipt.getBoolean("automaticFallback"));
            if(background){JSONObject nativeResult=receipt.getJSONObject("nativeResult");assertEquals(lane!=Lane.BACKGROUND_WRONG_USER,nativeResult.getBoolean("actualUserVerified"));assertEquals(lane!=Lane.BACKGROUND_WRONG_USER,nativeResult.getBoolean("targetLandingVerified"));assertFalse(nativeResult.getBoolean("verifiedBusinessSuccess"));if(lane==Lane.BACKGROUND_WRONG_USER){assertTrue(control.isPaused());assertTrue(nativeResult.getBoolean("requiresReconciliation"));}JSONObject duplicate=resumed.execute(SemanticCapabilityPort.DISPATCH,new JSONObject(exact[0].toString()).put("_hostOperationId",receipt.getString("operationId")),control);assertFalse(duplicate.getBoolean("accepted"));assertEquals(1,SemanticBackgroundDisplayFixture.dispatches);}
        }finally{control.cancel();module.close();if(display!=null)display.close();worker.shutdownNow();worker.awaitTermination(2,TimeUnit.SECONDS);if(server!=null)server.stop(0);instance.set(null,null);Field db=TaskStore.class.getDeclaredField("db");db.setAccessible(true);((SQLiteDatabase)db.get(tasks)).close();}
    }
    private static RunOutcome await(Future<RunOutcome> result)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);while(!result.isDone()&&System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5);}return result.get(1,TimeUnit.SECONDS);}
}
