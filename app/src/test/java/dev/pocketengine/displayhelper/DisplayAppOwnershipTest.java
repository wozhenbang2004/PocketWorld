package dev.pocketengine.displayhelper;

import android.app.Application;
import android.content.ComponentName;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual native-topology conversion and ownership join; public platform fields are controlled fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class DisplayAppOwnershipTest {
    public static final class Root {
        public int taskId,displayId,userId,numActivities=1;
        public Object token=new Object();
        public int[] childTaskIds=new int[0],childTaskUserIds=new int[0];
        public String[] childTaskNames=new String[0];
        public ComponentName baseActivity;
        Root(int task,int display,int user,String pkg){taskId=task;displayId=display;userId=user;baseActivity=new ComponentName(pkg,pkg+".Main");}
    }
    private static JSONObject j(Object... pairs)throws Exception{JSONObject out=new JSONObject();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}
    private static JSONObject visible()throws Exception{return j("status","observed","primary",new JSONArray().put(j("taskId",1,"displayId",0,"packageName","owner.home")),"owned",new JSONArray().put(j("taskId",2,"displayId",5,"packageName","app.example","userId",0)));}
    private static JSONObject topology(Root... roots){return new DisplayTaskTopology().fromRoots(List.of(roots));}
    @Test public void observedProfileComesFromTheCompleteNativeTaskIdentityNotTheVisibleGuess()throws Exception {
        JSONObject visible=visible(),nativeTasks=topology(new Root(1,0,0,"owner.home"),new Root(2,5,10,"app.example"),new Root(3,8,0,"another.app"));
        assertEquals("observed",nativeTasks.getString("status"));assertTrue(nativeTasks.getBoolean("complete"));JSONObject result=DisplayAppOwnership.enrich(visible,nativeTasks,5);
        assertEquals("observed",result.getString("status"));assertTrue(result.getBoolean("allDisplayIdentitiesKnown"));assertEquals(3,result.getJSONArray("all").length());
        assertEquals(0,result.getJSONArray("primary").getJSONObject(0).getInt("userId"));assertEquals(10,result.getJSONArray("owned").getJSONObject(0).getInt("userId"));
        assertEquals("input evidence is not mutated",0,visible.getJSONArray("owned").getJSONObject(0).getInt("userId"));assertFalse(nativeTasks.getJSONArray("tasks").getJSONObject(1).has("userId"));
    }
    @Test public void hiddenTaskInAnotherDisplayStillBlocksTheSameAppAndUser()throws Exception {
        JSONObject all=topology(new Root(1,0,0,"owner.home"),new Root(2,5,10,"app.example"),new Root(30,8,10,"app.example"));
        JSONObject visibleOnly=DisplayAppOwnership.enrich(visible(),all,5);assertEquals(1,visibleOnly.getJSONArray("owned").length());assertEquals(3,visibleOnly.getJSONArray("all").length());
        assertFalse(DisplayAppOwnership.free(all,"app.example",10,5));assertTrue(DisplayAppOwnership.free(all,"app.example",0,5));
        JSONObject differentProfile=topology(new Root(1,0,0,"app.example"),new Root(2,5,10,"app.example"));
        assertTrue(DisplayAppOwnership.free(differentProfile,"app.example",10,5));assertFalse(DisplayAppOwnership.free(differentProfile,"app.example",0,5));
    }
    @Test public void MissingOrInconsistentFullTopologyIsUnknownAndCannotProveAnAppFree()throws Exception {
        Root broken=new Root(2,5,10,"app.example");broken.childTaskIds=new int[]{3};broken.childTaskNames=new String[0];
        JSONObject unavailable=topology(new Root(1,0,0,"owner.home"),broken);assertEquals("unknown",unavailable.getString("status"));assertFalse(unavailable.getBoolean("complete"));
        assertFalse(DisplayAppOwnership.free(unavailable,"app.example",10,5));JSONObject result=DisplayAppOwnership.enrich(visible(),unavailable,5);assertEquals("unknown",result.getString("status"));assertFalse(result.getBoolean("allDisplayIdentitiesKnown"));
        JSONObject missingTasks=j("status","observed","complete",true);assertFalse(DisplayAppOwnership.free(missingTasks,"app.example",10,5));assertEquals("unknown",DisplayAppOwnership.enrich(visible(),missingTasks,5).getString("status"));
    }
    @Test public void ReplacedOrAmbiguousTasksNeverInheritTheOldVisibleIdentity()throws Exception {
        JSONObject replaced=topology(new Root(1,0,0,"owner.home"),new Root(2,5,10,"replacement.app"));assertEquals("unknown",DisplayAppOwnership.enrich(visible(),replaced,5).getString("status"));
        JSONObject moved=topology(new Root(1,0,0,"owner.home"),new Root(2,8,10,"app.example"));assertEquals("unknown",DisplayAppOwnership.enrich(visible(),moved,5).getString("status"));
        JSONObject duplicate=topology(new Root(1,0,0,"owner.home"),new Root(2,5,10,"app.example"),new Root(2,8,10,"app.example"));assertFalse(duplicate.getBoolean("complete"));assertFalse(DisplayAppOwnership.free(duplicate,"app.example",10,5));
    }
}
