package dev.pocketengine.store;

import android.app.Application;
import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class TaskStoreTest {
    @Test public void successfulStepsDoNotFinishTheTaskAndRestartRecoversIt() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        TaskStore store=new TaskStore(context);
        String id=store.create("打开计算器并核对结果").getString("id");
        store.event(id,new JSONObject().put("type","run").put("status","running"));
        store.event(id,new JSONObject().put("type","observation").put("status","completed"));
        store.event(id,new JSONObject().put("type","tool").put("status","completed"));
        assertEquals("running",store.get(id,false).getString("status"));
        assertEquals(3,store.get(id,false).getJSONArray("events").length());
        assertEquals("interrupted",new TaskStore(context).get(id,false).getString("status"));
    }
    @Test public void nativeResourceAdmissionCannotSurviveDisableAndColdRestore()throws Exception {
        Context context=RuntimeEnvironment.getApplication();TaskStore store=new TaskStore(context);
        dev.pocketengine.core.RunControl original=new dev.pocketengine.core.RunControl();
        original.retainAuthorityCheck(store.admitExecutionControl(original,"","",0));original.check();
        store.setExecutionEnabledForOwner(1,false);
        assertThrows(InterruptedException.class,original::check);
        TaskStore restored=new TaskStore(context);assertFalse(restored.executionEnabled());
        assertThrows(SecurityException.class,()->restored.admitExecutionControl(new dev.pocketengine.core.RunControl(),"","",0));
        store.setExecutionEnabledForOwner(2,true);
        assertThrows(SecurityException.class,()->store.admitExecutionControl(original,"","",0));
        assertThrows(InterruptedException.class,original::check);
        dev.pocketengine.core.RunControl fresh=new dev.pocketengine.core.RunControl();fresh.retainAuthorityCheck(store.admitExecutionControl(fresh,"","",0));fresh.check();
    }
    @Test public void finalResultAndOwnerConfirmationRemainDistinct() throws Exception {
        TaskStore store=new TaskStore(RuntimeEnvironment.getApplication());
        String id=store.create("检查结果").getString("id");
        store.finish(id,"needs_confirmation","请核对结果",new JSONObject().put("automaticallyVerified",false));
        assertEquals("needs_confirmation",store.get(id,false).getString("status"));
        store.event(id,new JSONObject().put("type","user_verification").put("completed",true));
        store.status(id,"completed","主人已确认");
        JSONObject result=store.get(id,false);
        assertFalse(result.getJSONObject("evidence").getBoolean("automaticallyVerified"));
        assertTrue(result.getJSONArray("events").getJSONObject(0).getBoolean("completed"));
        assertEquals("completed",result.getString("status"));
    }
    @Test public void oldAssistantSlotCallbackCannotRewriteTheSuccessorRun()throws Exception {
        Context context=RuntimeEnvironment.getApplication();TaskStore first=new TaskStore(context);
        JSONObject original=first.startAttempt(first.createCapabilityTask("original conversation",java.util.UUID.randomUUID().toString()).getString("id"),"capabilities",12);
        String id=original.getString("id"),run=original.getString("runId");int epoch=original.getInt("coordinatorEpoch");dev.pocketengine.core.RunControl control=new dev.pocketengine.core.RunControl();control.bindHostRun(id,run,epoch);
        JSONObject binding=new JSONObject().put("taskId",id).put("runId",run).put("epoch",epoch);first.recordAssistantResourceState(binding,control,"queued");assertEquals("queued",first.get(id,false).getString("resourceState"));
        TaskStore restored=new TaskStore(context);JSONObject next=restored.startAttempt(id,"capabilities",12);dev.pocketengine.core.RunControl successor=new dev.pocketengine.core.RunControl();successor.bindHostRun(id,next.getString("runId"),next.getInt("coordinatorEpoch"));
        restored.recordAssistantResourceState(new JSONObject().put("taskId",id).put("runId",next.getString("runId")).put("epoch",next.getInt("coordinatorEpoch")),successor,"running");
        control.cancel();assertThrows(SecurityException.class,()->first.recordAssistantResourceState(binding,control,"stopping"));assertEquals("running",restored.get(id,false).getString("resourceState"));
    }
    @Test public void invalidRecordsDoNotPreventReadingValidTasks() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        String valid="a1234567-1234-1234-1234-123456789abc";
        File file=new File(context.getFilesDir(),"tasks.json");
        Files.write(file.toPath(),("[null,5,{\"id\":\"bad\"},{\"id\":\""+valid+"\",\"status\":\"running\"}]").getBytes(StandardCharsets.UTF_8));
        TaskStore store=new TaskStore(context);
        assertEquals(1,store.list().length());
        assertEquals("interrupted",store.get(valid,false).getString("status"));
        assertNotNull(store.get(valid,false).getJSONArray("events"));
    }
}
