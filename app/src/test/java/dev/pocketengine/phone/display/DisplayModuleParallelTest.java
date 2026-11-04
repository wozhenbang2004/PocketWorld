package dev.pocketengine.phone.display;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.phone.display.adb.AdbBootstrap;
import dev.pocketengine.phone.display.adb.AdbBootstrapClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Per-run application routing with controlled authenticated replies, not a physical-display test. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class DisplayModuleParallelTest {
    private static final class Helper implements DisplayModule.Exchange {
        final Map<String,JSONObject> sessions=new java.util.concurrent.ConcurrentHashMap<>();
        final List<JSONObject> requests=new java.util.concurrent.CopyOnWriteArrayList<>();
        int next=10;
        volatile String lostRelease="",slowFrame="";
        volatile CountDownLatch entered,proceed;
        public JSONObject call(String command,JSONObject payload,RunControl original,long revision,boolean paused,dev.pocketengine.phone.observation.NativePhoneEntry.Admission admission)throws Exception {
            if(original!=null){original.check();if(original.isPaused()!=paused||original.pauseRevision()!=revision)throw new SecurityException("changed");}
            if(admission!=null)admission.check();
            requests.add(new JSONObject(payload.toString()).put("command",command));
            String key=payload.optString("ownerRunId");JSONObject state=sessions.get(key);
            if(command.equals("status"))return state==null?owner(payload,status(false,"",-1)):new JSONObject(state.toString());
            if(command.equals("create")){
                synchronized(this){assertNull(state);state=owner(payload,status(true,"native-"+next,next++));sessions.put(key,state);}
                return new JSONObject(state.toString());
            }
            if(state==null)throw new IllegalStateException("unknown original display");
            assertEquals(state.getString("sessionId"),payload.getString("sessionId"));assertEquals(state.getInt("displayId"),payload.getInt("displayId"));
            if(command.equals("frame")&&key.equals(slowFrame)){entered.countDown();assertTrue(proceed.await(3,TimeUnit.SECONDS));}
            if(command.equals("release")){
                if(key.equals(lostRelease))throw new java.io.IOException("release reply unknown");
                sessions.remove(key);return new JSONObject(state.toString()).put("released",true);
            }
            if(command.equals("heartbeat"))return new JSONObject(state.toString()).put("renewed",true);
            return new JSONObject(state.toString()).put("accepted",true).put("frameGeneration",1).put("empty",false);
        }
        private static JSONObject status(boolean active,String session,int display)throws Exception{return new JSONObject().put("ok",true).put("protocol",1).put("uid",2000).put("sdk",35)
                .put("sessionRouting",2).put("sessionActive",active).put("sessionId",active?session:JSONObject.NULL).put("displayId",active?display:JSONObject.NULL)
                .put("backgroundOnly",true).put("focusIsolationRequested",active).put("displayFlagsConfirmed",active).put("imeIsolation","available").put("frameMetadataOnly",true);}
        private static JSONObject owner(JSONObject input,JSONObject answer)throws Exception{return answer.put("ownerTaskId",input.getString("ownerTaskId")).put("ownerRunId",input.getString("ownerRunId")).put("ownerEpoch",input.getInt("ownerEpoch"));}
    }
    private static AdbBootstrapClient adb(){return new AdbBootstrapClient(){
        public JSONObject snapshot(){return new JSONObject();}
        public JSONObject discover(){throw new AssertionError("no discovery");}
        public JSONObject pair(JSONObject input){throw new AssertionError("no pairing");}
        public JSONObject connect(JSONObject input,JSONObject bootstrap,AdbBootstrap.AuthenticatedStatus status){throw new AssertionError("no bootstrap");}
        public JSONObject cancel(){return new JSONObject();}
        public JSONObject forgetIdentity(){throw new AssertionError("no identity change");}
        public void close() { }
    };}
    private DisplayModule module(Helper helper){
        Context android=RuntimeEnvironment.getApplication();android.getSharedPreferences("display_backend",0).edit().putString("backend","adb").commit();
        return new DisplayModule(new FeatureContext(android,null,null,event->{}),changed->adb(),null,null,helper);
    }
    private static RunControl run(String task,String id,int epoch){RunControl result=new RunControl();result.bindHostRun(task,id,epoch);return result;}

    @Test public void fourRunsUseOnlyTheirOwnPreparedSessionsAndNoImplicitDefault()throws Exception {
        Helper helper=new Helper();DisplayModule module=module(helper);
        try{
            List<RunControl> runs=new ArrayList<>();
            for(int i=0;i<4;i++){RunControl run=run("task-"+i,"run-"+i,1);runs.add(run);module.pinOwner(run);module.prepare(run);}
            assertEquals(4,module.snapshot().getInt("activeSessionCount"));assertEquals(4,helper.sessions.size());
            for(RunControl run:runs){
                BackgroundPhonePort port=new BackgroundPhonePort(module,run);
                assertEquals(module.session(run).getString("sessionId"),port.executionIdentity().getString("sessionId"));
                assertTrue(module.hasSession(run));
                JSONObject frame=module.request("frame",new JSONObject(),run);assertEquals(module.session(run).getInt("displayId"),frame.getInt("displayId"));
            }
            assertThrows(SecurityException.class,module::session);assertFalse(module.hasSession());
            assertThrows(SecurityException.class,()->new BackgroundPhonePort(module));
            assertThrows(SecurityException.class,()->module.request("frame",new JSONObject(),null));
            assertThrows(IllegalStateException.class,()->module.pinOwner(run("fifth","run-5",1)));
            assertEquals(4,helper.sessions.size());
        }finally{module.close();}
    }
    @Test public void equalLabelsAndForeignSessionFieldsCannotReplaceOriginalControl()throws Exception {
        Helper helper=new Helper();DisplayModule module=module(helper);
        try{
            RunControl a=run("a","a-run",1),b=run("b","b-run",1);module.prepare(a);module.prepare(b);
            RunControl forged=run("a","a-run",1);
            assertThrows(SecurityException.class,()->module.pinOwner(forged));
            assertThrows(SecurityException.class,()->module.session(forged));
            JSONObject target=module.session(b);int before=helper.requests.size();
            assertThrows(SecurityException.class,()->module.request("tap",target,a));assertEquals(before,helper.requests.size());
            module.unpinOwner(forged);assertTrue(module.hasSession(a));
        }finally{module.close();}
    }
    @Test public void delayedReleaseAndUnpinFromOldRunCannotStopSameTaskReplacement()throws Exception {
        Helper helper=new Helper();DisplayModule module=module(helper);
        try{
            RunControl old=run("task","old",1);module.prepare(old);JSONObject previous=module.session(old);
            BackgroundPhonePort oldPort=new BackgroundPhonePort(module,old);
            module.releaseSession(previous.getString("sessionId"),previous.getInt("displayId"));module.unpinOwner(old);
            RunControl next=run("task","next",2);module.prepare(next);JSONObject replacement=module.session(next);
            oldPort.noteReleasePending();module.unpinOwner(old);
            assertTrue(module.releaseSession(previous.getString("sessionId"),previous.getInt("displayId")).getBoolean("releaseConfirmed"));
            assertTrue(module.hasSession(next));assertEquals(replacement.getString("sessionId"),module.session(next).getString("sessionId"));
            assertFalse(module.snapshot().getBoolean("releasePending"));assertEquals(1,helper.sessions.size());
        }finally{module.close();}
    }
    @Test public void unknownCleanupBlocksOnlyItsOriginalRunAndRetainsTheResourceRecord()throws Exception {
        Helper helper=new Helper();DisplayModule module=module(helper);
        try{
            RunControl a=run("a","a-run",1),b=run("b","b-run",1);module.prepare(a);module.prepare(b);JSONObject previous=module.session(a);
            helper.lostRelease="a-run";
            assertThrows(java.io.IOException.class,()->module.releaseSession(previous.getString("sessionId"),previous.getInt("displayId")));
            assertFalse(module.hasSession(a));assertTrue(module.hasSession(b));
            int dispatched=helper.requests.size();assertThrows(SecurityException.class,()->module.request("frame",new JSONObject(),a));assertEquals(dispatched,helper.requests.size());
            assertEquals(module.session(b).getInt("displayId"),module.request("frame",new JSONObject(),b).getInt("displayId"));
            module.unpinOwner(a);assertThrows(IllegalStateException.class,()->module.pinOwner(run("a","replacement",2)));
            helper.lostRelease="";assertTrue(module.releaseSession(previous.getString("sessionId"),previous.getInt("displayId")).getBoolean("releaseConfirmed"));
            module.prepare(run("a","replacement",2));assertTrue(module.hasSession(b));
        }finally{helper.lostRelease="";module.close();}
    }
    @Test public void slowOriginalFrameAndPausedOwnerRefreshDoNotSelectOrBlockAnotherRun()throws Exception {
        Helper helper=new Helper();DisplayModule module=module(helper);
        try{
            RunControl a=run("a","a-run",1),b=run("b","b-run",1);module.prepare(a);module.prepare(b);
            helper.slowFrame="a-run";helper.entered=new CountDownLatch(1);helper.proceed=new CountDownLatch(1);
            FutureTask<JSONObject> first=new FutureTask<>(()->module.request("frame",new JSONObject(),a));Thread worker=new Thread(first);worker.start();
            try{
                assertTrue(helper.entered.await(1,TimeUnit.SECONDS));
                FutureTask<JSONObject> second=new FutureTask<>(()->module.request("frame",new JSONObject(),b));Thread other=new Thread(second);other.start();
                assertEquals(module.session(b).getInt("displayId"),second.get(1,TimeUnit.SECONDS).getInt("displayId"));other.join(1000);
            }finally{helper.proceed.countDown();first.get(1,TimeUnit.SECONDS);worker.join(1000);}
            a.pause();JSONObject owned=module.session(a);
            module.refreshSession(owned.getString("sessionId"),owned.getInt("displayId"),a,a.pauseRevision(),true);
            JSONObject request=helper.requests.get(helper.requests.size()-1);assertEquals("a-run",request.getString("ownerRunId"));assertEquals(owned.getInt("displayId"),request.getInt("displayId"));
            assertTrue(module.hasSession(b));
        }finally{module.close();}
    }
    private static JSONObject profile(int task,int user)throws Exception {
        JSONObject visible=new JSONObject().put("packageName","example.app").put("taskId",task).put("displayId",7).put("userId",user);
        JSONObject actual=new JSONObject().put("packageName","example.app").put("taskId",task).put("displayId",7).put("androidUserId",user);
        return new JSONObject().put("status","observed").put("owned",new org.json.JSONArray().put(visible))
                .put("taskTopology",new JSONObject().put("status","observed").put("complete",true).put("tasks",new org.json.JSONArray().put(actual)));
    }
    @Test public void observationProfileRequiresMatchingNativeUserAndTaskOnBothSides()throws Exception {
        JSONObject observation=new JSONObject().put("packageName","example.app");
        BackgroundPhonePort.attachObservedProfile(observation,profile(51,10),profile(51,10),7);
        assertTrue(observation.getBoolean("actualUserVerified"));assertEquals(10,observation.getInt("androidUserId"));
        BackgroundPhonePort.attachObservedProfile(observation,profile(51,0),profile(51,10),7);
        assertFalse(observation.getBoolean("actualUserVerified"));assertFalse(observation.has("androidUserId"));
        BackgroundPhonePort.attachObservedProfile(observation,profile(51,10),profile(52,10),7);
        assertFalse(observation.getBoolean("actualUserVerified"));assertFalse(observation.has("androidUserId"));
    }
    @Test public void missingOrAmbiguousNativeUserNeverFallsBackToMainProfile()throws Exception {
        JSONObject observation=new JSONObject().put("packageName","example.app").put("androidUserId",0);
        JSONObject before=profile(51,10),incomplete=profile(51,10);incomplete.getJSONObject("taskTopology").put("complete",false);
        BackgroundPhonePort.attachObservedProfile(observation,before,incomplete,7);assertFalse(observation.has("androidUserId"));
        JSONObject ambiguous=profile(51,10),other=profile(52,0);
        ambiguous.getJSONArray("owned").put(other.getJSONArray("owned").getJSONObject(0));
        ambiguous.getJSONObject("taskTopology").getJSONArray("tasks").put(other.getJSONObject("taskTopology").getJSONArray("tasks").getJSONObject(0));
        BackgroundPhonePort.attachObservedProfile(observation,ambiguous,ambiguous,7);assertFalse(observation.getBoolean("actualUserVerified"));assertFalse(observation.has("androidUserId"));
        BackgroundPhonePort.attachObservedProfile(observation,before,before,9);assertFalse(observation.has("androidUserId"));
    }
}
