package dev.pocketengine.browser;

import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.features.capabilities.TaskStoreCapabilityAuthority;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public final class BrowserTaskPersistenceTest {
    static JSONObject obj(Object... values)throws Exception {JSONObject out=new JSONObject();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out;}
    static void closeDatabase(TaskStore tasks)throws Exception {Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(tasks)).close();}
    static final class Fixture implements AutoCloseable {
        final TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());final RunControl control=new RunControl();final ModelCapabilityAdapters.Binding binding;final TaskStoreCapabilityAuthority authority;final RunJournal journal;
        final AtomicBoolean resource=new AtomicBoolean(true);final LinkedBlockingQueue<JSONObject> events=new LinkedBlockingQueue<>();final BrowserTaskSourcePort sources;final BrowserTaskApprovalPort approvals;
        Fixture()throws Exception {this(false);}
        Fixture(boolean publicHttp)throws Exception {JSONObject task=tasks.createCapabilityTask("带来源的浏览调研","");task=tasks.startAttempt(task.getString("id"),"capabilities",40);control.bindHostRun(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"));binding=new ModelCapabilityAdapters.Binding(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),control,true,"");JSONArray tools=new JSONArray().put("browser_task_open").put("browser_task_retain_source");if(publicHttp)tools.put(PublicHttpReader.TOOL);tasks.authorizeTools(binding.taskId,binding.runId,tools);tasks.status(binding.taskId,"running","fixture admitted original run");authority=new TaskStoreCapabilityAuthority(tasks,binding,events::add);journal=tasks.journal(binding.taskId,binding.runId,binding.epoch,control);
            ModelCapabilityAdapters.ResourceCheck admitted=()->{if(!resource.get())throw new SecurityException("original reservation revoked");};sources=new BrowserTaskSourcePort(tasks,binding,control,authority,admitted,events::add);approvals=new BrowserTaskApprovalPort(tasks,binding,control,authority,admitted,events::add);}
        JSONObject source()throws Exception {var profile=BrowserProfileCapabilityBindingTest.namedSession();return profile.identity().put("taskId",binding.taskId).put("runId",binding.runId).put("epoch",binding.epoch).put("browserSpaceId",UUID.randomUUID().toString()).put("tabId",UUID.randomUUID().toString()).put("tabGeneration",1L).put("openedBy","task").put("url","https://www.youtube.com/watch").put("privateUrl","https://www.youtube.com/watch?v=original-video&t=30#detail").put("urlScope","owner_private_profile").put("urlQueryOmitted",true).put("title","原网页标题").put("excerpt","有来源的实际观察摘录").put("observedAt",System.currentTimeMillis()).put("observationId",UUID.randomUUID().toString()).put("navigation",1).put("frameId","main").put("acquisitionMethod","rendered_dom").put("dataScope","owner_private_profile").put("kind","browser_observation").put("untrustedPageContent",true).put("verified",false).put("sourceId",UUID.randomUUID().toString());}
        JSONObject arguments(JSONObject source)throws Exception{return obj("tabId",source.getString("tabId"),"observationId",source.getString("observationId"),"excerpt",source.getString("excerpt"));}
        JSONObject pin()throws Exception{return obj("taskId",binding.taskId,"runId",binding.runId,"epoch",binding.epoch,"browserSpaceId",UUID.randomUUID().toString());}
        public void close()throws Exception {approvals.close();control.cancel();closeDatabase(tasks);}
    }
    @Test public void sourcesCommitToOriginalSQLiteAndSurviveReopeningWithoutDuplicateWrites()throws Exception {try(Fixture f=new Fixture()){
        JSONObject source=f.source();String id=source.getString("sourceId"),operation=f.journal.beginOperation("browser_task_retain_source",f.arguments(source));JSONObject receipt=f.sources.retain(f.binding,f.control,operation,id,source);assertEquals("retained",receipt.getString("status"));f.journal.finishOperation(operation,receipt);
        assertEquals(receipt.toString(),f.sources.lookup(f.binding,id).toString());assertTrue(receipt.getBoolean("exactUrlRetainedPrivately"));assertFalse(receipt.toString().contains("original-video"));assertEquals(1,BrowserTaskSourcePort.sourcesForOwner(f.tasks,f.binding.taskId).length());
        JSONObject scholar=f.source().put("sourceId",UUID.randomUUID().toString()).put("url","https://scholar.google.com/scholar").put("privateUrl","https://scholar.google.com/scholar?cluster=original-cluster&hl=zh-CN#result");String second=f.journal.beginOperation("browser_task_retain_source",f.arguments(scholar));f.journal.finishOperation(second,f.sources.retain(f.binding,f.control,second,scholar.getString("sourceId"),scholar));
        // A second TaskStore instance reads committed bytes from the same database, not a port cache.
        TaskStore reopened=new TaskStore(RuntimeEnvironment.getApplication());try{JSONArray saved=BrowserTaskSourcePort.sourcesForOwner(reopened,f.binding.taskId);assertEquals(2,saved.length());assertEquals(source.getString("excerpt"),saved.getJSONObject(0).getString("excerpt"));assertEquals(source.getString("privateUrl"),saved.getJSONObject(0).getString("privateUrl"));assertEquals(scholar.getString("sourceId"),saved.getJSONObject(1).getString("sourceId"));assertEquals(scholar.getString("privateUrl"),saved.getJSONObject(1).getString("privateUrl"));assertFalse(saved.getJSONObject(0).getBoolean("verified"));assertTrue(saved.getJSONObject(0).getBoolean("untrustedPageContent"));}finally{closeDatabase(reopened);}
        JSONObject reference=BrowserTaskSourcePort.projectReferenceForOwner(f.tasks,f.binding.taskId,id);assertEquals(source.getString("sourceId"),reference.getString("sourceId"));assertEquals("https://www.youtube.com/watch",reference.getString("redactedUrl"));assertTrue(reference.getBoolean("untrusted"));assertFalse(reference.getBoolean("privateUrlIncluded"));assertFalse(reference.toString().contains("original-video"));assertTrue(reference.getString("sourceFingerprint").matches("[a-f0-9]{64}"));
        f.control.cancel();assertEquals("retained",f.sources.lookup(f.binding,id).getString("status"));assertEquals(2,BrowserTaskSourcePort.sourcesForOwner(f.tasks,f.binding.taskId).length());
    }}
    @Test public void originalPreparedArgumentsAndResourceAreRequiredAndSourceIdCannotOverwriteContent()throws Exception {try(Fixture f=new Fixture()){
        JSONObject source=f.source();String id=source.getString("sourceId");assertThrows(SecurityException.class,()->f.sources.retain(f.binding,f.control,UUID.randomUUID().toString(),id,source));assertEquals(0,BrowserTaskSourcePort.sourcesForOwner(f.tasks,f.binding.taskId).length());
        String operation=f.journal.beginOperation("browser_task_retain_source",f.arguments(source));f.resource.set(false);assertThrows(SecurityException.class,()->f.sources.retain(f.binding,f.control,operation,id,source));f.resource.set(true);JSONObject receipt=f.sources.retain(f.binding,f.control,operation,id,source);f.journal.finishOperation(operation,receipt);
        JSONObject changed=new JSONObject(source.toString()).put("excerpt","不能替换原来源");assertThrows(SecurityException.class,()->f.sources.retain(f.binding,f.control,operation,id,changed));assertEquals(source.getString("excerpt"),BrowserTaskSourcePort.sourcesForOwner(f.tasks,f.binding.taskId).getJSONObject(0).getString("excerpt"));
        RunControl replacement=new RunControl();replacement.bindHostRun(f.binding.taskId,f.binding.runId,f.binding.epoch);assertThrows(SecurityException.class,()->f.sources.retain(f.binding,replacement,operation,id,source));
    }}
    @Test public void nativeDecisionConsumesExactApprovalWithoutReplacingRunControlOrPreparedOperation()throws Exception {try(Fixture f=new Fixture()){
        JSONObject args=obj("url","https://example.com/research"),pin=f.pin();String operation=f.journal.beginOperation("browser_task_open",args);var worker=Executors.newSingleThreadExecutor();try{
            var future=worker.submit(()->f.approvals.approve(f.binding,f.control,operation,"browser_task_open",args,pin));JSONObject event=f.events.poll(2,TimeUnit.SECONDS);assertNotNull(event);String id=event.getString("approvalId");assertEquals(operation,event.getString("operationId"));assertTrue(f.approvals.isWaiting(f.binding.taskId,id,f.control));
            RunControl replacement=new RunControl();replacement.bindHostRun(f.binding.taskId,f.binding.runId,f.binding.epoch);assertFalse(f.approvals.isWaiting(f.binding.taskId,id,replacement));f.tasks.decideCapabilityApproval(f.binding.taskId,id,true);f.approvals.nativeDecisionChanged(f.binding.taskId,id,f.control);JSONObject approval=future.get(2,TimeUnit.SECONDS);assertTrue(approval.getBoolean("approved"));assertEquals(id,approval.getString("approvalId"));
            JSONObject task=f.tasks.get(f.binding.taskId,false);assertEquals(f.binding.runId,task.getString("runId"));assertEquals(f.binding.epoch,task.getInt("coordinatorEpoch"));assertEquals("prepared",task.getJSONArray("operations").getJSONObject(0).getString("state"));assertEquals("consumed",task.getJSONArray("approvals").getJSONObject(0).getString("state"));f.journal.finishOperation(operation,obj("accepted",false,"verified",false,"message","fixture made no native effect"));
        }finally{worker.shutdownNow();worker.awaitTermination(2,TimeUnit.SECONDS);}
    }}
    @Test public void denialAndCancellationEndWaitingWithoutCreatingAnotherAttempt()throws Exception {try(Fixture f=new Fixture()){
        var worker=Executors.newSingleThreadExecutor();try{JSONObject args=obj("url","https://example.com/denied");String operation=f.journal.beginOperation("browser_task_open",args);var future=worker.submit(()->f.approvals.approve(f.binding,f.control,operation,"browser_task_open",args,f.pin()));JSONObject event=f.events.poll(2,TimeUnit.SECONDS);assertNotNull(event);f.tasks.decideCapabilityApproval(f.binding.taskId,event.getString("approvalId"),false);f.approvals.nativeDecisionChanged(f.binding.taskId,event.getString("approvalId"),f.control);assertFalse(future.get(2,TimeUnit.SECONDS).getBoolean("approved"));f.journal.finishOperation(operation,obj("accepted",false,"code","approval_required"));
            JSONObject next=obj("url","https://example.com/cancelled");String second=f.journal.beginOperation("browser_task_open",next);var cancelled=worker.submit(()->f.approvals.approve(f.binding,f.control,second,"browser_task_open",next,f.pin()));assertNotNull(f.events.poll(2,TimeUnit.SECONDS));f.control.cancel();assertThrows(ExecutionException.class,()->cancelled.get(2,TimeUnit.SECONDS));assertEquals(f.binding.runId,f.tasks.get(f.binding.taskId,false).getString("runId"));
        }finally{worker.shutdownNow();worker.awaitTermination(2,TimeUnit.SECONDS);}
    }}
}
