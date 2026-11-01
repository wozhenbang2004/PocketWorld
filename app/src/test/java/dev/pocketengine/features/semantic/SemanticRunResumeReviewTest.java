package dev.pocketengine.features.semantic;

import static org.junit.Assert.*;
import android.app.Application;
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
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.phone.PhoneAccessibilityService;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
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

/** Actual TaskStore + SemanticModule.bind + close/rebind on the same run, not a copied approval. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,application=Application.class,shadows=SemanticRunResumeReviewTest.Safety.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SemanticRunResumeReviewTest {
    @Implements(PhoneAccessibilityService.class)
    public static final class Safety {@Implementation public String observationSafetyFailure(){return null;}}
    private static Field serviceField() throws Exception {Field f=PhoneAccessibilityService.class.getDeclaredField("instance");f.setAccessible(true);return f;}
    @After public void clear() throws Exception {serviceField().set(null,null);}
    private static void install(Context context,SemanticRequest request) throws Exception {
        ApplicationInfo app=new ApplicationInfo();app.packageName="review.editor";app.enabled=true;app.uid=10455;
        ActivityInfo activity=new ActivityInfo();activity.packageName=app.packageName;activity.name="review.editor.Main";activity.exported=true;activity.enabled=true;activity.applicationInfo=app;
        PackageInfo pkg=new PackageInfo();pkg.packageName=app.packageName;pkg.applicationInfo=app;pkg.activities=new ActivityInfo[]{activity};pkg.setLongVersionCode(1);pkg.lastUpdateTime=1000;
        pkg.signingInfo=new SigningInfo();Shadows.shadowOf(pkg.signingInfo).setSignatures(new Signature[]{new Signature(new byte[]{1,2,3})});
        Shadows.shadowOf(context.getPackageManager()).installPackage(pkg);
        IntentFilter filter=new IntentFilter(Intent.ACTION_INSERT);filter.addCategory(Intent.CATEGORY_DEFAULT);filter.addDataType("vnd.android.cursor.dir/event");
        ResolveInfo match=new ResolveInfo();match.activityInfo=activity;match.filter=filter;match.nonLocalizedLabel="Review editor";match.isDefault=true;
        Shadows.shadowOf(context.getPackageManager()).setResolveInfosForIntent(AndroidSemanticPlatform.intent(request),List.of(match));
    }
    private static JSONObject invoke(CapabilityPort port,JSONObject args,RunControl control) throws Exception {
        FutureTask<JSONObject> result=new FutureTask<>(()->port.execute(SemanticCapabilityPort.DISPATCH,args,control));Thread worker=new Thread(result,"semantic-resume-review");worker.start();
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(!result.isDone()&&System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5);}
        JSONObject receipt=result.get(1,TimeUnit.SECONDS);worker.join(1000);return receipt;
    }
    @Test public void approvedDiscoverySurvivesRunnerPortCloseAndSameRunRebind() throws Exception {
        Context context=RuntimeEnvironment.getApplication();TaskStore tasks=new TaskStore(context);
        RunControl control=new RunControl();String task=tasks.create("准备日历草稿并在原任务批准").getString("id");JSONObject attempt=tasks.startAttempt(task,"main_screen",10);
        String run=attempt.getString("runId");int epoch=attempt.getInt("coordinatorEpoch");control.bindHostRun(task,run,epoch);
        ModelCapabilityAdapters.Binding binding=new ModelCapabilityAdapters.Binding(task,run,epoch,control,true,"");
        PhoneAccessibilityService service=Robolectric.buildService(PhoneAccessibilityService.class).create().get();serviceField().set(null,service);
        PhonePort phone=new PhonePort(){public boolean isConnected(){return true;}public JSONObject observe(boolean image,RunControl c){return new JSONObject();}public JSONObject execute(String n,JSONObject a,RunControl c){return new JSONObject();}public void clearReferences(){}};
        SemanticModule module=new SemanticModule(new FeatureContext(context,null,null,event->{}));SemanticRequest request=SemanticRequest.parse(SemanticCalendarRequestTest.draft());install(context,request);
        CapabilityPort first=module.bind(null,tasks,binding,control,phone,event->{});tasks.authorizeTools(task,run,new JSONArray().put(SemanticCapabilityPort.DISCOVER).put(SemanticCapabilityPort.DISPATCH));
        JSONObject found=first.execute(SemanticCapabilityPort.DISCOVER,request.parameters(),control);
        JSONObject args=new JSONObject().put("discoveryId",found.getString("discoveryId")).put("candidateId",found.getJSONArray("candidates").getJSONObject(0).getString("candidateId"));
        RunJournal journal=tasks.journal(task,run,epoch,control);String initial=journal.beginOperation(SemanticCapabilityPort.DISPATCH,args);
        JSONObject pending=invoke(first,new JSONObject(args.toString()).put("_hostOperationId",initial),control);journal.finishOperation(initial,pending);
        assertEquals("approval_required",pending.getString("code"));assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
        // AgentRunner.finally closes its port before PhoneRunService holds only the physical port.
        first.close();control.pause();tasks.finish(task,"awaiting_user","等待原任务具体参数批准",pending);
        tasks.decideCapabilityApproval(task,pending.getString("approvalId"),true);control.resume();tasks.status(task,"running","按原Run重新观察");
        CapabilityPort resumed=module.bind(null,tasks,binding,control,phone,event->{});
        String next=journal.beginOperation(SemanticCapabilityPort.DISPATCH,args);JSONObject result=invoke(resumed,new JSONObject(args.toString()).put("_hostOperationId",next),control);journal.finishOperation(next,result);
        Intent launched=Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
        System.out.println("RESUME_REVIEW sameRun="+run.equals(tasks.get(task,false).getString("runId"))+" controlSame=true code="+result.optString("code")+" accepted="+result.opt("accepted")+" launchRecorded="+(launched!=null));
        assertTrue(result.toString(),result.optBoolean("accepted"));assertNotNull(launched);assertEquals(run,tasks.get(task,false).getString("runId"));
        assertEquals("consumed",tasks.get(task,false).getJSONArray("approvals").getJSONObject(0).getString("state"));
        resumed.close();control.cancel();
    }
}
