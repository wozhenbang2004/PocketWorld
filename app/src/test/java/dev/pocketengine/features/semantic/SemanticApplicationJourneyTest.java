package dev.pocketengine.features.semantic;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Looper;
import android.os.SystemClock;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.PhoneRunService;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.PhoneAccessibilityService;
import dev.pocketengine.phone.observation.ObservationReport;
import dev.pocketengine.testutil.HttpServer;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;

/** Actual Application -> service -> original Runner/journal -> HTTP -> owner approve/resume -> observe.
 * The physical phone/PM are explicit Android fixtures; this is not a device/editor acceptance claim. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,application=EngineApplication.class,shadows=SemanticApplicationJourneyTest.Safety.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SemanticApplicationJourneyTest {
    @Implements(PhoneAccessibilityService.class)
    public static final class Safety {@Implementation public String observationSafetyFailure(){return null;}}
    private EngineApplication app;private HttpServer server;private PhoneRunService service;
    private static JSONObject j(Object... values)throws Exception {JSONObject out=new JSONObject();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out;}
    private static void set(Object target,String name,Object value)throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private static Object get(Object target,String name)throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void await(BooleanSupplier condition)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(!condition.getAsBoolean()&&System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5);}
        assertTrue("actual host did not reach expected state",condition.getAsBoolean());
    }
    private static boolean pending(EngineApplication app,String task) {try{JSONObject current=app.tasks.get(task,false);return "awaiting_user".equals(current.optString("status"))&&current.getJSONArray("approvals").length()>0&&app.control(task).isPaused();}catch(Exception e){return false;}}
    private static void installBrowser(Context context,SemanticRequest request)throws Exception {
        ApplicationInfo info=new ApplicationInfo();info.packageName="journey.browser";info.enabled=true;info.uid=10456;
        ActivityInfo activity=new ActivityInfo();activity.packageName=info.packageName;activity.name="journey.browser.Main";activity.exported=true;activity.enabled=true;activity.applicationInfo=info;
        PackageInfo pkg=new PackageInfo();pkg.packageName=info.packageName;pkg.applicationInfo=info;pkg.activities=new ActivityInfo[]{activity};pkg.setLongVersionCode(1);pkg.lastUpdateTime=1000;
        pkg.signingInfo=new SigningInfo();Shadows.shadowOf(pkg.signingInfo).setSignatures(new Signature[]{new Signature(new byte[]{4,5,6})});Shadows.shadowOf(context.getPackageManager()).installPackage(pkg);
        IntentFilter filter=new IntentFilter(Intent.ACTION_VIEW);filter.addCategory(Intent.CATEGORY_DEFAULT);filter.addCategory(Intent.CATEGORY_BROWSABLE);filter.addDataScheme("https");
        ResolveInfo match=new ResolveInfo();match.activityInfo=activity;match.filter=filter;match.nonLocalizedLabel="Journey browser";match.isDefault=true;
        Shadows.shadowOf(context.getPackageManager()).setResolveInfosForIntent(AndroidSemanticPlatform.intent(request),List.of(match));
    }
    private final class PhoneFixture implements PhonePort {
        final String session;final RunControl original;final AtomicInteger launches=new AtomicInteger(),postObservations=new AtomicInteger();volatile boolean targetVisible;
        PhoneFixture(String session,RunControl original){this.session=session;this.original=original;}
        public boolean isConnected(){return true;}
        public void clearReferences(){ }
        public JSONObject execute(String name,JSONObject args,RunControl control){throw new AssertionError("unexpected physical action "+name);}
        public JSONObject observe(boolean image,RunControl control)throws Exception {
            assertSame(original,control);Intent intent=Shadows.shadowOf(app).getNextStartedActivity();
            if(intent!=null){assertEquals("journey.browser",intent.getComponent().getPackageName());launches.incrementAndGet();targetVisible=true;}
            if(targetVisible)postObservations.incrementAndGet();
            return ObservationReport.attach(j("ok",true,"observationId",UUID.randomUUID().toString(),"displayId",0,"sessionId",session,
                    "observedAtUptimeMs",SystemClock.uptimeMillis(),"pauseRevision",control.pauseRevision(),"screenWidth",720,"screenHeight",1280,
                    "packageName",targetVisible?"journey.browser":"journey.launcher","nodes",new JSONArray().put(j("id","visible-node","text",targetVisible?"目标页面已显示":"初始页面","enabled",true)),"truncated",false),true,"accessibility_tree",false,"not_requested");
        }
    }
    @After public void close()throws Exception {
        if(app!=null&&app.activeId()!=null)app.stop(app.activeId());
        if(service!=null)service.onDestroy();if(server!=null)server.stop(0);
        Field f=PhoneAccessibilityService.class.getDeclaredField("instance");f.setAccessible(true);f.set(null,null);
        if(app!=null&&app.features!=null)app.features.close();
    }
    @Test public void originalPhoneServiceApprovalContinuesSameRunAndObservesTheAcceptedIntent()throws Exception {
        app=(EngineApplication)RuntimeEnvironment.getApplication();assertTrue(app.features.module("semantic") instanceof SemanticModule);
        SemanticRequest request=SemanticRequest.parse(j("kind","web","url","https://example.org/target"));installBrowser(app,request);
        PhoneAccessibilityService accessibility=Robolectric.buildService(PhoneAccessibilityService.class).create().get();Field field=PhoneAccessibilityService.class.getDeclaredField("instance");field.setAccessible(true);field.set(null,accessibility);
        AtomicInteger requests=new AtomicInteger();AtomicReference<JSONObject> exactArgs=new AtomicReference<>();AtomicReference<Throwable> serverFailure=new AtomicReference<>();AtomicReference<String> resumedHistory=new AtomicReference<>();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{try{
            JSONObject body=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));int call=requests.incrementAndGet();String name;JSONObject args;
            assertEquals("journey-model",body.getString("model"));
            if(call==1){assertTrue(body.getJSONArray("tools").toString().contains("semantic_dispatch"));name="semantic_discover";args=request.parameters();}
            else if(call==2){JSONObject found=null;JSONArray messages=body.getJSONArray("messages");for(int i=0;i<messages.length();i++){JSONObject m=messages.getJSONObject(i);if("tool".equals(m.optString("role")))try{JSONObject result=new JSONObject(m.getString("content"));if(result.has("discoveryId"))found=result;}catch(Exception unrelated){}}
                assertNotNull("discovery receipt absent from real model request",found);exactArgs.set(j("discoveryId",found.getString("discoveryId"),"candidateId",found.getJSONArray("candidates").getJSONObject(0).getString("candidateId")));name="semantic_dispatch";args=exactArgs.get();}
            else if(call==3){resumedHistory.set(body.getJSONArray("messages").toString());assertTrue(resumedHistory.get().contains(exactArgs.get().getString("discoveryId")));name="semantic_dispatch";args=exactArgs.get();}
            else {assertEquals(4,call);assertTrue(body.getJSONArray("messages").toString().contains("journey.browser"));name="phone_request_user";args=j("reason","目标入口已经打开，请主人核对页面结果");}
            JSONObject response=j("choices",new JSONArray().put(j("message",j("role","assistant","tool_calls",new JSONArray().put(j("id","call-"+call,"type","function","function",j("name",name,"arguments",args.toString())))),"finish_reason","tool_calls")));
            byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        }catch(Throwable failure){serverFailure.set(failure);exchange.sendResponseHeaders(500,0);exchange.close();}});server.start();
        JSONObject task=app.tasks.startAttempt(app.tasks.create("打开已批准的目标入口并观察核对","main_screen").getString("id"),"main_screen",12);String id=task.getString("id"),run=task.getString("runId");
        RunControl original=new RunControl();original.bindHostRun(id,run,task.getInt("coordinatorEpoch"));ModelConfig config=new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","fixture-key","journey-model",false,12,5);
        set(app,"activeTaskId",id);set(app,"activeControl",original);set(app,"activeMode","main_screen");set(app,"activeConfig",config);
        PhoneFixture phone=new PhoneFixture(accessibility.observationSessionId(),original);
        service=Robolectric.buildService(PhoneRunService.class).create().get();set(service,"heldPort",phone);
        service.onStartCommand(new Intent(app,PhoneRunService.class).setAction(PhoneRunService.START).putExtra("taskId",id),0,1);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(750,TimeUnit.MILLISECONDS);await(()->pending(app,id));assertNull(String.valueOf(serverFailure.get()),serverFailure.get());assertEquals(2,requests.get());assertEquals(0,phone.launches.get());
        JSONObject pending=app.tasks.get(id,false).getJSONArray("approvals").getJSONObject(0);assertEquals("pending",pending.getString("state"));assertTrue((Boolean)get(service,"waitingForUser"));
        app.dispatchOwnerAction("approveCapability",j("taskId",id,"approvalId",pending.getString("id"),"approved",true));assertSame(original,app.control(id));assertFalse(original.isPaused());
        service.onStartCommand(new Intent(app,PhoneRunService.class).setAction(PhoneRunService.CONTINUE).putExtra("taskId",id),0,2);
        await(()->requests.get()==4&&original.isPaused());assertNull(String.valueOf(serverFailure.get()),serverFailure.get());
        JSONObject result=app.tasks.get(id,false);assertEquals(run,result.getString("runId"));assertEquals(task.getInt("coordinatorEpoch"),result.getInt("coordinatorEpoch"));assertEquals("consumed",result.getJSONArray("approvals").getJSONObject(0).getString("state"));
        assertEquals(1,phone.launches.get());assertTrue(phone.postObservations.get()>0);assertFalse(app.tasks.hasUnresolved(id));assertEquals(2,result.getJSONArray("operations").length());
        JSONObject receipt=result.getJSONArray("operations").getJSONObject(1).getJSONObject("receipt");assertTrue(receipt.getBoolean("accepted"));assertFalse(receipt.getBoolean("verifiedBusinessSuccess"));
        System.out.println("V13_SEMANTIC_JOURNEY sameRun=true sameControl=true requests="+requests.get()+" launches="+phone.launches.get()+" postObservations="+phone.postObservations.get()+" approval=consumed verifiedBusinessSuccess=false");
    }
    @Test public void actualCombinedPortKeepsNonPhoneCallersDiscoveryOnlyAndRejectsReplacementControl()throws Exception {
        app=(EngineApplication)RuntimeEnvironment.getApplication();JSONObject task=app.tasks.startAttempt(app.tasks.create("原主屏任务","main_screen").getString("id"),"main_screen",5);RunControl original=new RunControl();original.bindHostRun(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"));set(app,"activeTaskId",task.getString("id"));set(app,"activeControl",original);
        CapabilityPort port=app.capabilitiesForTask(task.getString("id"),original);assertTrue(port.supports("semantic_discover"));assertFalse(port.supports("semantic_dispatch"));assertEquals("dev.pocketengine.runtime.extensions.ExtensionCapabilityPort",port.getClass().getName());port.close();
        RunControl foreign=new RunControl();foreign.bindHostRun(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"));assertThrows(SecurityException.class,()->app.capabilitiesForTask(task.getString("id"),foreign));
    }
}
