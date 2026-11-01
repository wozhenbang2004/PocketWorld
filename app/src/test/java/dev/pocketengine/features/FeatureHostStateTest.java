package dev.pocketengine.features;

import android.app.Application;
import dev.pocketengine.core.ExecutionResources;
import dev.pocketengine.store.TaskStore;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class FeatureHostStateTest {
    @Test public void sshTerminalInputIsAuditedWithoutPersistingKeyboardBytes()throws Exception {
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());String id="ssh-terminal";tasks.beginExternal("ssh","openPty",id,new JSONObject().put("_resourceId","account"));tasks.updateExternal(id,new JSONObject().put("status","running"));
        CountDownLatch reply=new CountDownLatch(1);java.util.concurrent.atomic.AtomicReference<JSONObject> seen=new java.util.concurrent.atomic.AtomicReference<>();
        try(FeatureHost host=new FeatureHost(event->{if(event.optString("type").equals("feature_result"))reply.countDown();},tasks,()->false)){
            host.register(new EngineModule(){public String id(){return "ssh";}public JSONObject snapshot(){return new JSONObject();}public JSONObject handle(String command,JSONObject input)throws Exception{seen.set(input);return new JSONObject().put("status","running").put("accepted",true);}});
            String privateBytes=java.util.Base64.getEncoder().encodeToString("secret-password\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            host.request(new JSONObject().put("feature","ssh").put("command","writePty").put("payload",new JSONObject().put("hostTaskId",id).put("runId",id).put("terminalId",id).put("dataBase64",privateBytes)));
            assertTrue(reply.await(2,TimeUnit.SECONDS));assertEquals(privateBytes,seen.get().getString("dataBase64"));
            String persisted=tasks.get(id,false).toString();assertFalse(persisted.contains(privateBytes));assertFalse(persisted.contains("secret-password"));assertFalse(persisted.contains("dataBase64"));assertTrue(persisted.contains("inputSubmitted"));
        }
    }
    @Test public void nestedTeamResultCommitsBeforeQueuedAdmissionAndPublishesTaskEvents()throws Exception {
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());CopyOnWriteArrayList<JSONObject> events=new CopyOnWriteArrayList<>();CountDownLatch reply=new CountDownLatch(1);FeatureHost[] owner=new FeatureHost[1];
        try(FeatureHost host=new FeatureHost(event->{events.add(event);if(event.optString("type").equals("feature_result"))reply.countDown();},tasks,()->false)){
            owner[0]=host;host.register(new EngineModule(){public String id(){return "teams";}public JSONObject snapshot(){return new JSONObject();}public JSONObject handle(String command,JSONObject input)throws Exception {
                String id=input.getString("_operationId");owner[0].recordEvent(new JSONObject().put("type","feature_event").put("feature","teams").put("payload",new JSONObject().put("run",new JSONObject().put("id",id).put("operationId",id).put("status","completed").put("summary","已产生分析").put("updatedAt",200))));
                return new JSONObject().put("id",id).put("operationId",id).put("status","queued");}});
            host.request(new JSONObject().put("feature","teams").put("command","start").put("payload",new JSONObject().put("goal","检查")));
            assertTrue(reply.await(2,TimeUnit.SECONDS));JSONObject task=tasks.list().getJSONObject(0);assertEquals("needs_confirmation",task.getString("status"));assertEquals("已产生分析",task.getString("summary"));
            assertTrue(events.stream().anyMatch(event->event.optString("type").equals("task")&&event.optJSONObject("task")!=null&&event.optJSONObject("task").optString("status").equals("needs_confirmation")));
        }
    }
    @Test public void remoteOperationEnvelopeAndPluginCancelUseTheCorrectOperationTask()throws Exception {
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());String remote=UUID.randomUUID().toString(),plugin=UUID.randomUUID().toString();tasks.beginExternal("remote","submitJob",remote,new JSONObject());tasks.beginExternal("plugins","invoke",plugin,new JSONObject());CountDownLatch replied=new CountDownLatch(1);
        try(FeatureHost host=new FeatureHost(event->{if(event.optString("type").equals("feature_result"))replied.countDown();},tasks,()->false)){
            host.recordEvent(new JSONObject().put("type","feature_event").put("feature","remote").put("payload",new JSONObject().put("operation",new JSONObject().put("operationId",remote).put("status","not_found"))));
            assertEquals("reconciling",tasks.get(remote,false).getString("status"));
            host.register(new EngineModule(){public String id(){return "plugins";}public JSONObject snapshot(){return new JSONObject();}public JSONObject handle(String command,JSONObject input)throws Exception{return new JSONObject().put("operationId",input.getString("operationId")).put("status","stopping");}});
            host.request(new JSONObject().put("feature","plugins").put("command","cancel").put("payload",new JSONObject().put("operationId",plugin)));assertTrue(replied.await(2,TimeUnit.SECONDS));assertEquals("stopping",tasks.get(plugin,false).getString("status"));
        }
    }
    @Test public void browserTakeoverPublishesCanonicalTaskAndReleasesSharedResource()throws Exception {
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());String id=UUID.randomUUID().toString();tasks.beginExternal("browser","acquire",id,new JSONObject());ExecutionResources resources=new ExecutionResources();assertTrue(resources.acquireBrowser(id,System.currentTimeMillis()+600_000));AtomicInteger updates=new AtomicInteger();
        try(FeatureHost host=new FeatureHost(event->{if(event.optString("type").equals("task"))updates.incrementAndGet();},tasks,()->false,resources)){
            host.recordEvent(new JSONObject().put("type","feature_event").put("feature","browser").put("payload",new JSONObject().put("taskId",id).put("runId",id).put("status","taken_over")));
            assertEquals("cancelled",tasks.get(id,false).getString("status"));assertEquals(1,updates.get());assertTrue(resources.acquirePhone("next-phone"));resources.releasePhone("next-phone");
        }
    }
    @Test public void displayStatusCannotReconnectTheHelperDuringPhoneExecution()throws Exception {
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());AtomicInteger handled=new AtomicInteger();CountDownLatch reply=new CountDownLatch(1);
        try(FeatureHost host=new FeatureHost(event->{if(event.optString("type").equals("feature_result")){assertFalse(event.optBoolean("ok"));reply.countDown();}},tasks,()->true)){
            host.register(new EngineModule(){public String id(){return "display";}public JSONObject snapshot(){return new JSONObject();}public JSONObject handle(String command,JSONObject input){handled.incrementAndGet();return new JSONObject();}});
            host.request(new JSONObject().put("feature","display").put("command","status"));assertTrue(reply.await(2,TimeUnit.SECONDS));assertEquals(0,handled.get());
        }
    }
    @Test public void acquireCanResumeSameBrowserTaskAndOldTakeoverCannotReleaseNewRun()throws Exception {
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());String id=UUID.randomUUID().toString();tasks.beginExternal("browser","acquire",id,new JSONObject().put("goal","继续查资料"));tasks.feedback(id,"保留登录后的页面");tasks.updateExternal(id,new JSONObject().put("status","taken_over").put("runId",id));
        ExecutionResources resources=new ExecutionResources();CountDownLatch reply=new CountDownLatch(1);CopyOnWriteArrayList<JSONObject> replies=new CopyOnWriteArrayList<>();
        try(FeatureHost host=new FeatureHost(event->{if(event.optString("type").equals("feature_result")){replies.add(event);reply.countDown();}},tasks,()->false,resources)){
            host.register(new EngineModule(){public String id(){return "browser";}public JSONObject snapshot(){return new JSONObject();}public JSONObject handle(String command,JSONObject input)throws Exception {
                return new JSONObject().put("active",true).put("taskId",input.getString("taskId")).put("runId",input.getString("runId")).put("leaseId",UUID.randomUUID().toString()).put("expiresAt",System.currentTimeMillis()+600_000L).put("status","leased");}});
            host.request(new JSONObject().put("feature","browser").put("command","acquire").put("payload",new JSONObject().put("taskId",id)));
            assertTrue(reply.await(2,TimeUnit.SECONDS));assertTrue(replies.get(0).getBoolean("ok"));assertEquals(id,replies.get(0).getJSONObject("result").getString("hostTaskId"));
            JSONObject task=tasks.get(id,false);String fresh=task.getString("runId");assertNotEquals(id,fresh);assertEquals("running",task.getString("status"));assertEquals("继续查资料",task.getString("goal"));assertEquals("保留登录后的页面",task.getString("feedback"));assertEquals(1,tasks.list().length());
            host.recordEvent(new JSONObject().put("type","feature_event").put("feature","browser").put("payload",new JSONObject().put("taskId",id).put("runId",id).put("status","taken_over")));
            assertEquals("running",tasks.get(id,false).getString("status"));assertFalse(resources.acquirePhone("must-stay-blocked"));
            host.recordEvent(new JSONObject().put("type","feature_event").put("feature","browser").put("payload",new JSONObject().put("taskId",id).put("runId",fresh).put("status","expired")));
            assertEquals("awaiting_user",tasks.get(id,false).getString("status"));assertTrue(resources.acquirePhone("next-phone"));resources.releasePhone("next-phone");
        }
    }
}
