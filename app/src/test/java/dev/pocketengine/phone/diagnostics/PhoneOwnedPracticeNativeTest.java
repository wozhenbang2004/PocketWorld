package dev.pocketengine.phone.diagnostics;

import android.content.ComponentName;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.features.capabilities.TaskStoreCapabilityAuthority;
import dev.pocketengine.phone.observation.NativePhoneEntry;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real TaskStore/native dispatch/private Activity lifecycle. Unrelated App startup is omitted;
 * no physical display, accessibility input, model run or device rehearsal success is asserted. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=EngineApplication.class,shadows=PhoneOwnedPracticeNativeTest.NoStartup.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public final class PhoneOwnedPracticeNativeTest {
    private static final String SESSION="original-native-main-session";
    private static final String TOOL="phone_practice_target";
    @Implements(EngineApplication.class)
    public static final class NoStartup extends org.robolectric.shadows.ShadowApplication {
        @Implementation protected void onCreate(){}
    }
    private EngineApplication app;
    private String previousActivityContexts;
    private final List<RunControl> controls=new ArrayList<>();
    private final List<PhonePracticeTarget> targets=new ArrayList<>();
    private final List<ActivityController<PhonePracticeActivity>> activities=new ArrayList<>();
    @Before public void setup(){
        // Robolectric 4.16.1 otherwise reuses the Application Context on display 0. The actual
        // Activity needs its own UI/display Context, exactly as Android supplies before onCreate.
        previousActivityContexts=System.getProperty("robolectric.createActivityContexts");System.setProperty("robolectric.createActivityContexts","true");
        app=(EngineApplication)RuntimeEnvironment.getApplication();app.tasks=new TaskStore(app);
    }
    @After public void close()throws Exception {
        try{
        for(RunControl control:controls)control.cancel();
        for(PhonePracticeTarget target:targets)target.close();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        for(ActivityController<PhonePracticeActivity> activity:List.copyOf(activities))destroy(activity);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        field("activeTaskId",null);field("activeControl",null);
        Field database=TaskStore.class.getDeclaredField("db");database.setAccessible(true);((SQLiteDatabase)database.get(app.tasks)).close();
        }finally{if(previousActivityContexts==null)System.clearProperty("robolectric.createActivityContexts");else System.setProperty("robolectric.createActivityContexts",previousActivityContexts);}
    }
    private void field(String name,Object value)throws Exception {Field field=EngineApplication.class.getDeclaredField(name);field.setAccessible(true);field.set(app,value);}
    private JSONObject task(boolean owner)throws Exception {
        String goal=PhonePracticeRequests.goal(app.getPackageName(),android.os.Process.myUid()/100000);
        JSONObject task=owner?app.tasks.createOwnerPhonePractice(goal,"main_screen"):app.tasks.create(goal,"main_screen");
        return app.tasks.startAttempt(task.getString("id"),"main_screen",8);
    }
    private RunControl bind(JSONObject task)throws Exception {
        RunControl control=new RunControl();control.bindHostRun(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"));controls.add(control);
        app.tasks.authorizeTools(task.getString("id"),task.getString("runId"),new JSONArray().put(TOOL));
        field("activeTaskId",task.getString("id"));field("activeControl",control);field("activeMode","main_screen");return control;
    }
    private RunJournal journal(JSONObject task,RunControl original)throws Exception{return app.tasks.journal(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),original);}
    private TaskStoreCapabilityAuthority authority(JSONObject task,RunControl original)throws Exception{return new TaskStoreCapabilityAuthority(app.tasks,new ModelCapabilityAdapters.Binding(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),original,true,""),event->{throw new AssertionError("this native target does not issue a new approval");});}
    private JSONObject targetArgs()throws Exception{return new JSONObject().put("packageName",app.getPackageName()).put("userId",android.os.Process.myUid()/100000);}
    private PhonePracticeTarget target(){PhonePracticeTarget target=new PhonePracticeTarget(app,0,SESSION);targets.add(target);return target;}
    private ActivityController<PhonePracticeActivity> mount(Intent intent){
        ActivityController<PhonePracticeActivity> controller=Robolectric.buildActivity(PhonePracticeActivity.class,intent).create();activities.add(controller);return controller;
    }
    private void destroy(ActivityController<PhonePracticeActivity> activity){activity.destroy();activities.remove(activity);}
    private static View description(View root,String description){
        if(root==null)return null;
        if(description.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;
        if(root instanceof ViewGroup group)for(int n=0;n<group.getChildCount();n++){View found=description(group.getChildAt(n),description);if(found!=null)return found;}return null;
    }
    private static boolean text(View root,String expected){
        if(root==null)return false;
        if(root instanceof TextView value&&value.getText().toString().contains(expected))return true;
        if(root instanceof ViewGroup group)for(int n=0;n<group.getChildCount();n++)if(text(group.getChildAt(n),expected))return true;return false;
    }
    private static View content(PhonePracticeActivity activity){return activity.findViewById(android.R.id.content);}
    private void rejectedActivity(Intent supplied){
        ActivityController<PhonePracticeActivity> created=mount(supplied);
        try{assertTrue("unclaimed, altered or stale native launch must finish without exposing the target",created.get().isFinishing());assertFalse(text(content(created.get()),PhonePracticeActivity.MARKER));}
        finally{destroy(created);}
    }

    @Test public void ordinaryPhoneGoalCannotAcquireTheOwnerPracticeFlagOrLaunchThePrivateTarget()throws Exception {
        JSONObject ordinary=task(false);RunControl original=bind(ordinary);String id=ordinary.getString("id"),run=ordinary.getString("runId");int epoch=ordinary.getInt("coordinatorEpoch");
        // A caller can edit its returned JSON; only the existing SQL task is authoritative.
        ordinary.put("ownerPhonePractice",true);
        assertFalse(app.tasks.get(id,false).optBoolean("ownerPhonePractice"));
        assertThrows(SecurityException.class,()->app.tasks.requireOwnerPhonePractice(id,run,epoch,original));
        assertThrows(SecurityException.class,()->target().launch(original,original::check));
        assertNull(Shadows.shadowOf(app).getNextStartedActivity());assertEquals(1,app.tasks.list().length());
    }

    @Test public void originalOwnerTargetEntersItsPrivateActivityAndCancellationClearsOnlyThatActivity()throws Exception {
        JSONObject task=task(true);RunControl original=bind(task);PhonePracticeTarget target=target();RunJournal journal=journal(task,original);
        JSONObject args=targetArgs();String operation=journal.beginOperation(TOOL,args);TaskStoreCapabilityAuthority authority=authority(task,original);
        assertTrue("the launch must remain admissible inside its own real prepared operation",app.tasks.hasUnresolved(task.getString("id")));
        JSONObject receipt=target.launch(original,()->{app.tasks.checkCapabilityCurrent(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),TOOL);authority.requirePrepared(operation,TOOL,args);});
        assertTrue(receipt.getBoolean("accepted"));assertTrue(receipt.getBoolean("osAccepted"));assertFalse(receipt.getBoolean("verifiedBusinessSuccess"));
        journal.finishOperation(operation,receipt);assertFalse(app.tasks.hasUnresolved(task.getString("id")));
        assertEquals("receipt",app.tasks.get(task.getString("id"),false).getJSONArray("operations").getJSONObject(0).getString("state"));
        var started=Shadows.shadowOf(app).getNextStartedActivityForResult();assertNotNull(started);Intent launch=started.intent;
        assertSame("independent Robolectric intent/options queues record the same single launch",launch,Shadows.shadowOf(app).getNextStartedActivity());assertNull(Shadows.shadowOf(app).getNextStartedActivityForResult());
        assertEquals(new ComponentName(app,PhonePracticeActivity.class),launch.getComponent());
        assertFalse("actual merged manifest keeps the target private",app.getPackageManager().getActivityInfo(launch.getComponent(),0).exported);
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK,launch.getFlags());assertEquals(0,started.options.getInt("android.activity.launchDisplayId",-1));
        assertEquals(task.getString("id"),launch.getStringExtra("taskId"));assertEquals(task.getString("runId"),launch.getStringExtra("runId"));assertEquals(task.getInt("coordinatorEpoch"),launch.getIntExtra("epoch",-1));
        assertEquals(SESSION,launch.getStringExtra("executionSessionId"));assertEquals(receipt.getString("practiceSessionId"),launch.getStringExtra("practiceSessionId"));assertEquals(0,receipt.getInt("displayId"));
        ActivityController<PhonePracticeActivity> mounted=mount(launch);PhonePracticeActivity activity=mounted.get();
        assertFalse(activity.refusalReason(),activity.isFinishing());assertEquals(0,activity.getDisplay().getDisplayId());assertTrue(text(content(activity),"PW_DIAG_SESSION:"+receipt.getString("practiceSessionId")));
        Button click=(Button)description(content(activity),"PW_DIAG_CLICK");assertNotNull(click);assertTrue(click.performClick());assertTrue(text(content(activity),"PW_DIAG_CLICK_COUNT:1"));
        EditText input=(EditText)description(content(activity),"PW_DIAG_INPUT");assertNotNull(input);input.setText(PhonePracticeActivity.CHINESE);assertEquals(PhonePracticeActivity.CHINESE,input.getText().toString());
        PhonePracticeActivity.requestCloseOriginal(new RunControl());Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(activity.isFinishing());
        original.cancel();Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(activity.isFinishing());
        JSONObject pending=PhonePracticeActivity.closeOriginal(original);assertTrue(pending.getBoolean("requested"));assertFalse("finish request alone is not destruction acknowledgement",pending.getBoolean("confirmed"));assertFalse(pending.getBoolean("otherAppsTouched"));
        destroy(mounted);assertTrue(PhonePracticeActivity.closeOriginal(original).getBoolean("confirmed"));assertNull(Shadows.shadowOf(app).getNextStartedActivity());
    }

    @Test public void duplicateLaunchAndAnEndedOriginalRunCannotCreateAnotherTarget()throws Exception {
        JSONObject first=task(true);RunControl original=bind(first);PhonePracticeTarget target=target();assertTrue(target.launch(original,original::check).getBoolean("accepted"));
        Intent old=Shadows.shadowOf(app).getNextStartedActivity();assertNotNull(old);
        JSONObject duplicate=target.launch(original,original::check);assertFalse(duplicate.getBoolean("accepted"));assertFalse(duplicate.getBoolean("automaticReplayAllowed"));assertNull(Shadows.shadowOf(app).getNextStartedActivity());
        String id=first.getString("id"),run=first.getString("runId");int epoch=first.getInt("coordinatorEpoch");
        app.tasks.endAttempt(id,run,"interrupted","explicit old attempt ended by host fixture",new JSONObject());JSONObject next=app.tasks.startAttempt(id,"main_screen",8);RunControl current=bind(next);
        app.tasks.requireOwnerPhonePractice(id,next.getString("runId"),next.getInt("coordinatorEpoch"),current);
        assertThrows(SecurityException.class,()->app.tasks.requireOwnerPhonePractice(id,run,epoch,original));
        assertThrows(SecurityException.class,()->target.launch(original,original::check));rejectedActivity(old);assertNull(Shadows.shadowOf(app).getNextStartedActivity());
    }

    @Test public void privateActivityRequiresTheExactNativeNonceSessionDisplayAndOneClaim()throws Exception {
        JSONObject task=task(true);RunControl original=bind(task);assertTrue(target().launch(original,original::check).getBoolean("accepted"));Intent originalIntent=Shadows.shadowOf(app).getNextStartedActivity();assertNotNull(originalIntent);
        rejectedActivity(new Intent(originalIntent).putExtra("practiceSessionId",UUID.randomUUID().toString()));
        rejectedActivity(new Intent(originalIntent).putExtra("executionSessionId","foreign-execution-session"));
        rejectedActivity(new Intent(originalIntent).putExtra("displayId",7));
        ActivityController<PhonePracticeActivity> exact=mount(originalIntent);assertFalse("invalid claims must not consume the exact native launch: "+exact.get().refusalReason(),exact.get().isFinishing());assertTrue(text(content(exact.get()),PhonePracticeActivity.MARKER));destroy(exact);
        rejectedActivity(new Intent(originalIntent));assertNull(Shadows.shadowOf(app).getNextStartedActivity());
        // The owner flag and native callback claim never waive the original prepared/unknown gate.
        RunJournal journal=journal(task,original);JSONObject args=targetArgs();String other=journal.beginOperation(TOOL,args),missing=UUID.randomUUID().toString();TaskStoreCapabilityAuthority authority=authority(task,original);PhonePracticeTarget blocked=target();
        assertThrows(NativePhoneEntry.Refused.class,()->blocked.launch(original,()->authority.requirePrepared(missing,TOOL,args)));
        journal.finishOperation(other,new JSONObject().put("accepted",JSONObject.NULL).put("error","original_practice_unknown"));
        assertEquals("unknown",app.tasks.get(task.getString("id"),false).getJSONArray("operations").getJSONObject(0).getString("state"));
        assertThrows(NativePhoneEntry.Refused.class,()->blocked.launch(original,()->authority.requirePrepared(missing,TOOL,args)));
        assertTrue(app.tasks.hasUnresolved(task.getString("id")));assertNull(Shadows.shadowOf(app).getNextStartedActivity());
    }
}
