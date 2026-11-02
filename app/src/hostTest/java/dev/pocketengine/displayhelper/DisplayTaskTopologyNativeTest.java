package dev.pocketengine.displayhelper;

import android.app.Application;
import android.content.ComponentName;
import android.os.Binder;
import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual SDK 35 RootTaskInfo/WindowContainerToken fields; no real shell Binder service/device claim. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,application=Application.class)
public final class DisplayTaskTopologyNativeTest {
    private final DisplayTaskTopology reader=new DisplayTaskTopology();
    private static Object root(int rootId,int display,int[] children,String[] names,int[] users)throws Exception {
        Class<?> type=Class.forName("android.app.ActivityTaskManager$RootTaskInfo");Object value=type.getConstructor().newInstance();
        type.getField("taskId").setInt(value,rootId);type.getField("displayId").setInt(value,display);type.getField("userId").setInt(value,0);type.getField("numActivities").setInt(value,1);
        type.getField("baseActivity").set(value,new ComponentName("agent.worker","agent.worker.Main"));
        Class<?> api=Class.forName("android.window.IWindowContainerToken");Binder binder=new Binder();Object remote=Proxy.newProxyInstance(api.getClassLoader(),new Class<?>[]{api},(proxy,method,args)->method.getName().equals("asBinder")?binder:null);
        Object token=Class.forName("android.window.WindowContainerToken").getConstructor(api).newInstance(remote);type.getField("token").set(value,token);
        type.getField("childTaskIds").set(value,children);type.getField("childTaskNames").set(value,names);type.getField("childTaskUserIds").set(value,users);return value;
    }
    @Test public void actualRootAndChildFieldsProduceBoundedVersionedFactsWithoutBinderToken()throws Exception {
        Object leaf=root(22,9,new int[0],new String[0],new int[0]);Object organizer=root(99,0,new int[]{11,12},new String[]{"owner.reader/.Main","owner.browser/.Main"},new int[]{0,10});
        var result=reader.fromRoots(List.of(leaf,organizer));assertEquals("observed",result.getString("status"));assertTrue(result.getBoolean("complete"));assertEquals(1,result.getInt("schemaVersion"));var tasks=result.getJSONArray("tasks");assertEquals(3,tasks.length());assertEquals(9,tasks.getJSONObject(0).getInt("displayId"));assertEquals(22,tasks.getJSONObject(0).getInt("taskId"));assertEquals(99,tasks.getJSONObject(2).getInt("rootTaskId"));assertEquals(10,tasks.getJSONObject(2).getInt("androidUserId"));assertEquals("owner.browser",tasks.getJSONObject(2).getString("packageName"));assertFalse(result.toString().contains("android.os.Binder"));assertFalse(tasks.getJSONObject(0).has("token"));
        assertEquals(tasks.getJSONObject(0).getString("rootIdentity"),reader.fromRoots(List.of(leaf)).getJSONArray("tasks").getJSONObject(0).getString("rootIdentity"));
    }
    @Test public void tokenEqualityLabelsRemainStableAndReplacementOrLeaseClearCannotReuseThem()throws Exception {
        Object original=root(22,9,new int[0],new String[0],new int[0]);String first=reader.fromRoots(List.of(original)).getJSONArray("tasks").getJSONObject(0).getString("rootIdentity");
        assertEquals(first,reader.fromRoots(List.of(original)).getJSONArray("tasks").getJSONObject(0).getString("rootIdentity"));
        // A newly parcelled wrapper for the same Binder must keep the label; an
        // unrelated Java wrapper/hash lifetime must not manufacture a task change.
        Object originalToken=original.getClass().getField("token").get(original),binder=originalToken.getClass().getMethod("asBinder").invoke(originalToken);
        Class<?> api=Class.forName("android.window.IWindowContainerToken");Object remote=Proxy.newProxyInstance(api.getClassLoader(),new Class<?>[]{api},(proxy,method,args)->method.getName().equals("asBinder")?binder:null);
        Object rewrapped=root(22,9,new int[0],new String[0],new int[0]);rewrapped.getClass().getField("token").set(rewrapped,Class.forName("android.window.WindowContainerToken").getConstructor(api).newInstance(remote));
        assertEquals(first,reader.fromRoots(List.of(rewrapped)).getJSONArray("tasks").getJSONObject(0).getString("rootIdentity"));
        Object replacement=root(22,9,new int[0],new String[0],new int[0]);String next=reader.fromRoots(List.of(replacement)).getJSONArray("tasks").getJSONObject(0).getString("rootIdentity");assertNotEquals(first,next);
        reader.clear();assertNotEquals(next,reader.fromRoots(List.of(replacement)).getJSONArray("tasks").getJSONObject(0).getString("rootIdentity"));
    }
    @Test public void unsupportedMissingAmbiguousAndOverBudgetFactsRemainExplicitlyUnknown()throws Exception {
        for(Object value:List.of(new Object(),root(22,9,new int[]{23},new String[0],new int[]{0}),root(22,9,new int[]{23},new String[]{"affinity-not-component"},new int[]{0}),root(22,9,new int[]{23,23},new String[]{"agent.worker/.Main","agent.worker/.Main"},new int[]{0,0}))) {
            var result=reader.fromRoots(List.of(value));assertEquals("unknown",result.getString("status"));assertFalse(result.getBoolean("complete"));assertEquals(0,result.getJSONArray("tasks").length());
        }
        Object valid=root(22,9,new int[0],new String[0],new int[0]);assertEquals("unknown",reader.fromRoots(java.util.Collections.nCopies(129,valid)).getString("status"));
    }
}
