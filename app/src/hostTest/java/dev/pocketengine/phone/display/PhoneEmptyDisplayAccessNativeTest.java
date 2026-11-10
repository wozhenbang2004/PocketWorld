package dev.pocketengine.phone.display;

import android.app.Application;
import android.graphics.Rect;
import android.util.SparseArray;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.phone.PhoneAccessibilityService;
import dev.pocketengine.phone.access.AppCopyKey;
import dev.pocketengine.phone.access.GuardedPhonePort;
import dev.pocketengine.phone.access.PhoneAccessPolicy;
import dev.pocketengine.phone.access.PhoneOwnerPackages;
import dev.pocketengine.phone.access.PhoneWindowPackages;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Original owner probe and policy gate; Android windows/helper transport and the content consumer are fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,application=Application.class,
        shadows={PhoneEmptyDisplayAccessNativeTest.Screen.class,PhoneEmptyDisplayAccessNativeTest.Windows.class,PhoneEmptyDisplayAccessNativeTest.Helper.class})
@LooperMode(LooperMode.Mode.PAUSED)
public final class PhoneEmptyDisplayAccessNativeTest {
    private static final int DISPLAY=9;
    private static final String SESSION="original-empty-display";
    private static final AppCopyKey BLOCKED=new AppCopyKey("fixture.private",10);
    private static JSONObject facts;
    private static String currentSession,replySession;
    private static int currentDisplay,replyDisplay,windowDisplay,windowReads,ownershipReads;
    private static boolean nullWindows,omitDisplay,unreadableRoot,appWindow,replyIdentity,replyOk;
    private static Runnable duringWindows,afterOwnership;
    private PhoneAccessibilityService service;
    private RunControl run;
    private BackgroundPhonePort port;

    @Implements(PhoneAccessibilityService.class) public static final class Screen extends Windows {
        @Implementation public String observationSafetyFailure(){return null;}
        @Implementation protected Rect controlsBounds(){return new Rect();}
    }
    @Implements(android.accessibilityservice.AccessibilityService.class) public static class Windows extends org.robolectric.shadows.ShadowAccessibilityService {
        @Implementation protected SparseArray<List<AccessibilityWindowInfo>> getWindowsOnAllDisplays(){
            windowReads++;duringWindows.run();
            if(nullWindows)return null;
            SparseArray<List<AccessibilityWindowInfo>> all=new SparseArray<>();
            if(omitDisplay)return all;
            List<AccessibilityWindowInfo> windows=new ArrayList<>();
            if(unreadableRoot||appWindow){
                AccessibilityWindowInfo window=AccessibilityWindowInfo.obtain();var shadow=Shadows.shadowOf(window);
                shadow.setId(7);shadow.setDisplayId(windowDisplay);shadow.setType(AccessibilityWindowInfo.TYPE_APPLICATION);
                if(!unreadableRoot){AccessibilityNodeInfo root=AccessibilityNodeInfo.obtain(new View(RuntimeEnvironment.getApplication()));root.setPackageName(BLOCKED.packageName());shadow.setRoot(root);}
                windows.add(window);
            }
            all.put(DISPLAY,windows);return all;
        }
    }
    @Implements(DisplayModule.class) public static final class Helper {
        @Implementation protected void __constructor__(FeatureContext ignored){}
        @Implementation protected void __constructor__(FeatureContext ignored,DisplayModule.AdbFactory factory,DisplayModule.Configuration configuration,DisplayModule.StatusProbe probe){}
        @Implementation protected void __constructor__(FeatureContext ignored,DisplayModule.AdbFactory factory,DisplayModule.Configuration configuration,DisplayModule.StatusProbe probe,DisplayModule.Exchange exchange){}
        @Implementation protected boolean hasSession(String id,int display){return currentSession.equals(id)&&currentDisplay==display;}
        @Implementation protected JSONObject session()throws Exception{return state();}
        @Implementation protected JSONObject session(RunControl original)throws Exception{return state();}
        @Implementation protected JSONObject session(String id,int display)throws Exception{return state();}
        @Implementation protected JSONObject request(String command,JSONObject args,RunControl control,long revision,boolean owner)throws Exception {
            assertEquals("ownership",command);assertEquals(SESSION,args.getString("sessionId"));assertEquals(DISPLAY,args.getInt("displayId"));
            control.check();assertEquals(control.pauseRevision(),revision);assertFalse(owner);ownershipReads++;
            JSONObject reply=new JSONObject().put("ok",replyOk).put("ownership",new JSONObject(facts.toString()));
            if(replyIdentity)reply.put("sessionId",replySession).put("displayId",replyDisplay);
            afterOwnership.run();return reply;
        }
        private static JSONObject state()throws Exception {
            return new JSONObject().put("sessionId",currentSession).put("displayId",currentDisplay).put("width",320).put("height",470)
                    .put("sessionActive",true).put("backgroundOnly",true).put("focusIsolationRequested",true).put("displayFlagsConfirmed",true);
        }
    }
    @Before public void start()throws Exception {
        currentSession=replySession=SESSION;currentDisplay=replyDisplay=windowDisplay=DISPLAY;windowReads=ownershipReads=0;
        nullWindows=omitDisplay=unreadableRoot=appWindow=false;replyIdentity=replyOk=true;duringWindows=afterOwnership=()->{};
        facts=emptyFacts();service=Robolectric.buildService(PhoneAccessibilityService.class).create().get();install(service);
        run=new RunControl();run.bindHostRun("empty-task","original-run",4);port=new BackgroundPhonePort(new DisplayModule(null),run);
    }
    @After public void stop()throws Exception {install(null);}
    private static void install(PhoneAccessibilityService value){
        try{Field field=PhoneAccessibilityService.class.getDeclaredField("instance");field.setAccessible(true);field.set(null,value);}
        catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    private static JSONObject task(int id,int display,String pkg,int user)throws Exception {
        return new JSONObject().put("taskId",id).put("rootTaskId",id).put("displayId",display).put("androidUserId",user)
                .put("packageName",pkg).put("component",pkg+"/.Main").put("rootIdentity","root-"+id);
    }
    private static JSONObject resumed(int id,int display,String pkg,int user)throws Exception {
        return new JSONObject().put("taskId",id).put("displayId",display).put("packageName",pkg).put("userId",user);
    }
    private static JSONObject emptyFacts()throws Exception {
        return new JSONObject().put("status","unknown").put("reason","native_task_identity_unavailable").put("allDisplayIdentitiesKnown",true)
                .put("primary",new JSONArray().put(resumed(1,0,"fixture.launcher",0))).put("owned",new JSONArray())
                .put("taskTopology",new JSONObject().put("schemaVersion",1).put("status","observed").put("complete",true).put("readOnly",true)
                        .put("tasks",new JSONArray().put(task(1,0,"fixture.launcher",0))));
    }
    private static JSONObject occupiedFacts()throws Exception {
        JSONObject value=emptyFacts().put("status","observed");
        value.getJSONArray("owned").put(resumed(2,DISPLAY,BLOCKED.packageName(),BLOCKED.userId()));
        value.getJSONObject("taskTopology").getJSONArray("tasks").put(task(2,DISPLAY,BLOCKED.packageName(),BLOCKED.userId()));return value;
    }
    private static final class Content implements PhonePort {
        int reads,writes,lists;
        public boolean isConnected(){return true;}
        public void clearReferences(){}
        public JSONObject observe(boolean image,RunControl control)throws Exception {control.check();reads++;return new JSONObject().put("ok",true).put("nodes",new JSONArray());}
        public JSONObject execute(String tool,JSONObject args,RunControl control)throws Exception {control.check();if("phone_list_apps".equals(tool))lists++;else writes++;return new JSONObject().put("ok",true).put("accepted",true);}
    }
    private GuardedPhonePort guarded(Content content){
        return new GuardedPhonePort(content,()->new PhoneAccessPolicy.State(1,true,Map.of(BLOCKED,new PhoneAccessPolicy.Rule(false,false))),port::ownerPackages,10);
    }
    @Test public void authenticatedUnknownStatusWithCompleteEmptyProofAllowsReadAndBootstrap()throws Exception {
        PhoneOwnerPackages owners=port.ownerPackages(run);assertTrue("complete empty owned display must be known",owners.known());assertTrue(owners.owners().isEmpty());
        assertTrue(ownershipReads>=2);assertTrue(windowReads>=1);
        Content content=new Content();GuardedPhonePort guarded=guarded(content);
        assertTrue(guarded.observe(false,run).getBoolean("ok"));assertEquals(1,content.reads);
        assertTrue(guarded.execute("phone_list_apps",new JSONObject(),run).getBoolean("ok"));assertEquals(1,content.lists);
        JSONObject refused=guarded.execute("phone_launch_app",new JSONObject().put("packageName",BLOCKED.packageName()),run);
        assertEquals("app_access_denied",refused.getString("error"));assertEquals(0,content.writes);
    }
    @Test public void absentTargetWindowEntryCanBeEmptyButMissingEntireWindowSnapshotCannot()throws Exception {
        omitDisplay=true;assertTrue(port.ownerPackages(run).known());assertEquals(1,windowReads);
        nullWindows=true;assertFalse(port.ownerPackages(run).known());
    }
    @Test public void missingIncompleteOrMalformedTopologyCannotAuthorizeEmptyWindows()throws Exception {
        for(int kind=0;kind<13;kind++){
            facts=emptyFacts();JSONObject topology=facts.getJSONObject("taskTopology");
            switch(kind){
                case 0 -> facts.remove("taskTopology");
                case 1 -> topology.put("complete",false);
                case 2 -> topology.put("status","unknown");
                case 3 -> topology.remove("tasks");
                case 4 -> facts.remove("owned");
                case 5 -> facts.remove("allDisplayIdentitiesKnown");
                case 6 -> facts.put("allDisplayIdentitiesKnown",false);
                case 7 -> topology.put("complete","true");
                case 8 -> topology.put("readOnly",false);
                case 9 -> topology.getJSONArray("tasks").getJSONObject(0).remove("androidUserId");
                case 10 -> facts.remove("status");
                case 11 -> facts.put("status","failed");
                case 12 -> facts.put("allDisplayIdentitiesKnown","true");
            }
            assertFalse("unproven empty kind="+kind,port.ownerPackages(run).known());
        }
    }
    @Test public void residualTaskOnTargetAndOwnedRowsForAnotherDisplayCannotProveEmpty()throws Exception {
        facts.getJSONObject("taskTopology").getJSONArray("tasks").put(task(2,DISPLAY,BLOCKED.packageName(),10));
        assertFalse(port.ownerPackages(run).known());
        facts=emptyFacts();facts.getJSONArray("owned").put(resumed(2,DISPLAY+1,BLOCKED.packageName(),10));
        facts.getJSONObject("taskTopology").getJSONArray("tasks").put(task(2,DISPLAY+1,BLOCKED.packageName(),10));
        assertFalse(port.ownerPackages(run).known());
        facts=occupiedFacts();assertFalse(port.ownerPackages(run).known());
    }
    @Test public void ownershipReplyMustNameOriginalSessionAndDisplay()throws Exception {
        replyDisplay=DISPLAY+1;assertFalse(port.ownerPackages(run).known());
        replyDisplay=DISPLAY;replySession="another-session";assertFalse(port.ownerPackages(run).known());
        replySession=SESSION;replyIdentity=false;assertFalse(port.ownerPackages(run).known());
        replyIdentity=true;replyOk=false;assertFalse(port.ownerPackages(run).known());
        assertEquals(0,windowReads);
    }
    @Test public void unreadableOrWrongDisplayWindowCannotBecomeEmpty()throws Exception {
        unreadableRoot=true;assertFalse(port.ownerPackages(run).known());
        unreadableRoot=false;appWindow=true;windowDisplay=DISPLAY+1;assertFalse(port.ownerPackages(run).known());
        windowDisplay=DISPLAY;assertFalse(port.ownerPackages(run).known());
    }
    @Test public void replacingAccessibilityServiceDuringReadRefusesEmptySnapshot()throws Exception {
        PhoneAccessibilityService replacement=Robolectric.buildService(PhoneAccessibilityService.class).create().get();
        duringWindows=()->install(replacement);assertFalse(port.ownerPackages(run).known());assertEquals(1,windowReads);
    }
    @Test public void originalControlAndSessionAreCheckedAfterEmptyWindowRead()throws Exception {
        duringWindows=()->currentSession="replacement-session";
        assertThrows(Exception.class,()->port.ownerPackages(run));assertEquals(1,windowReads);
        currentSession=SESSION;duringWindows=run::pause;assertThrows(Exception.class,()->port.ownerPackages(run));
    }
    @Test public void differentRunCannotBorrowOriginalEmptyDisplay()throws Exception {
        RunControl other=new RunControl();other.bindHostRun("empty-task","successor-run",5);
        assertThrows(SecurityException.class,()->port.ownerPackages(other));assertEquals(0,windowReads);
    }
    @Test public void topologyAndServiceAreRecheckedAfterEmptySnapshot()throws Exception {
        JSONObject occupied=occupiedFacts();duringWindows=()->facts=occupied;
        assertFalse(port.ownerPackages(run).known());assertTrue(ownershipReads>=2);
        facts=emptyFacts();duringWindows=()->{};ownershipReads=0;
        afterOwnership=()->{if(ownershipReads==1)replyDisplay=DISPLAY+1;};
        assertFalse(port.ownerPackages(run).known());assertTrue(ownershipReads>=2);replyDisplay=DISPLAY;
        facts=emptyFacts();duringWindows=()->{};ownershipReads=0;
        PhoneAccessibilityService replacement=Robolectric.buildService(PhoneAccessibilityService.class).create().get();
        afterOwnership=()->{if(ownershipReads==2)install(replacement);};
        assertFalse(port.ownerPackages(run).known());
    }
    @Test public void emptyReadDoesNotAuthorizeLaterDeniedAppReadOrInput()throws Exception {
        Content content=new Content();GuardedPhonePort guarded=guarded(content);assertTrue(guarded.observe(false,run).getBoolean("ok"));
        facts=occupiedFacts();appWindow=true;
        PhoneOwnerPackages current=port.ownerPackages(run);assertTrue(current.known());assertEquals(Set.of(BLOCKED),current.owners());
        JSONObject action=guarded.execute("phone_tap",new JSONObject(),run);assertEquals("app_access_denied",action.getString("error"));assertEquals(0,content.writes);
        assertEquals("app_access_denied",guarded.observe(true,run).getString("error"));assertEquals(1,content.reads);
    }
    @Test public void emptyIdentityAloneDoesNotChangeOrdinaryWindowProbeSemantics()throws Exception {
        assertFalse(PhoneWindowPackages.read(service,DISPLAY,new PhoneOwnerPackages(Set.of(),true),run::check).known());
    }
}
