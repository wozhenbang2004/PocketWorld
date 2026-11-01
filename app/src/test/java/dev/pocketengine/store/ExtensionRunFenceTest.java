package dev.pocketengine.store;
import android.app.Application;
import dev.pocketengine.core.RunControl;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class ExtensionRunFenceTest {
 private RunControl control(JSONObject task){RunControl c=new RunControl();c.bindHostRun(task.optString("id"),task.optString("runId"),task.optInt("coordinatorEpoch"));return c;}
 @Test public void currentRunAdmissionIsReadOnlyAndPausedCancelledOrOtherControlFails()throws Exception{
  TaskStore store=new TaskStore(RuntimeEnvironment.getApplication());JSONObject task=store.createCapabilityTask("原生扩展运行围栏","");task=store.startAttempt(task.getString("id"),"capabilities",10);String id=task.getString("id"),run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");RunControl c=control(task);String before=store.get(id,false).toString();store.checkExtensionRun(id,run,epoch,c);assertEquals(before,store.get(id,false).toString());assertThrows(SecurityException.class,()->store.checkExtensionRun(id,run,epoch,new RunControl()));c.pause();assertThrows(IllegalStateException.class,()->store.checkExtensionRun(id,run,epoch,c));c.resume();store.requestCancel(id,run);assertThrows(SecurityException.class,()->store.checkExtensionRun(id,run,epoch,c));
 }
 @Test public void preparedUnknownAndOldEpochNeverPass()throws Exception{
  TaskStore store=new TaskStore(RuntimeEnvironment.getApplication());JSONObject task=store.createCapabilityTask("未知动作不能越过","");task=store.startAttempt(task.getString("id"),"capabilities",10);String id=task.getString("id"),run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");RunControl c=control(task);store.status(id,"running","实际测试执行");store.authorizeTools(id,run,new org.json.JSONArray().put("fixture"));store.journal(id,run,epoch,c).beginOperation("fixture",new JSONObject());assertThrows(IllegalStateException.class,()->store.checkExtensionRun(id,run,epoch,c));assertThrows(SecurityException.class,()->store.checkExtensionRun(id,run,epoch+1,c));
 }
}
