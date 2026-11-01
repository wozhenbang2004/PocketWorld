package dev.pocketengine.browser;

import android.app.Application;
import dev.pocketengine.core.*;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.store.TaskStore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;
import static dev.pocketengine.browser.BrowserPageDialogActivityTest.j;

/** Real native Activity/Host/SQLite, controlled native executor calls original startAttempt.
 * Rendered DOM is the existing explicit JS callback fixture; no provider pixels or remote model claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class BrowserResearchRecoveryActivityTest {
    static CapabilityPort base(){return new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String n){return false;}public boolean sideEffecting(String n){return false;}public JSONObject execute(String n,JSONObject a,RunControl c){throw new AssertionError("no replacement executor");}};}
    @Test public void threePageManifestReopensInSameTaskWithNewOriginalRunAndNoRestoredGrantOrOldRefs()throws Exception {
        try(BrowserPageDialogActivityTest.F x=new BrowserPageDialogActivityTest.F()){
            String first=x.open();JSONObject old=x.observe(first);x.tool("open",j("url","https://example.com/second?private=not-recovered"));x.tool("open",j("url","https://example.com/third"));String task=x.taskId,oldRun=x.binding.runId;int oldEpoch=x.binding.epoch;
            x.tool("finish",new JSONObject());JSONObject persisted=BrowserResearchPages.fromTask(x.tasks.get(task,false));assertNotNull(persisted);assertEquals(3,persisted.getJSONArray("pages").length());assertFalse(persisted.toString().contains("private=not-recovered"));assertEquals(1,x.browser.tabsForOwner().getJSONArray("tabs").length());
            x.control.cancel();x.control=null;x.tasks.status(task,"paused","explicit native fixture executor settled; owner may review recovery");
            AtomicInteger launches=new AtomicInteger();AtomicReference<RunControl> live=new AtomicReference<>();AtomicReference<JSONObject> attempt=new AtomicReference<>();
            BrowserTaskHost recovered=new BrowserTaskHost(x.module,x.tasks,new ExecutionResources(),new BrowserTaskHost.OriginalTasks(){public void checkModelConfigured(){}public RunControl currentControl(String id){return task.equals(id)?live.get():null;}public void launch(String id)throws Exception{assertEquals(task,id);JSONObject started=x.tasks.startAttempt(id,"capabilities",40);RunControl control=new RunControl();control.bindHostRun(id,started.getString("runId"),started.getInt("coordinatorEpoch"));x.tasks.status(id,"running","native recovery executor fixture admitted");attempt.set(started);live.set(control);launches.incrementAndGet();}},x.events::add);
            JSONObject profile=x.browser.snapshot();String fingerprint=TaskStore.capabilityFingerprint(persisted);
            JSONObject requested=x.pump(x.worker.submit(()->recovered.recoverResearchForOwner(task,fingerprint,profile,true)),false);assertEquals("recovery_requested",requested.getString("status"));assertEquals(1,launches.get());assertEquals(task,attempt.get().getString("id"));assertNotEquals(oldRun,attempt.get().getString("runId"));assertTrue(attempt.get().getInt("coordinatorEpoch")>oldEpoch);
            RunControl current=live.get();ModelCapabilityAdapters.Binding binding=new ModelCapabilityAdapters.Binding(task,attempt.get().getString("runId"),attempt.get().getInt("coordinatorEpoch"),current,true,"");
            BrowserTaskCapabilityPort port=(BrowserTaskCapabilityPort)x.pump(x.worker.submit(()->recovered.wrapIfRequested(binding,current,base())),false);
            JSONObject snapshot=x.pump(x.worker.submit(()->port.contextSnapshot("recovery")),false);assertFalse(snapshot.toString().contains("native_read_scope"));assertTrue(snapshot.toString().contains("automaticReplayAllowed"));assertEquals(0,x.browser.tabsForOwner().getJSONObject("taskSpace").getJSONArray("tabs").length());
            JSONObject taskRow=x.tasks.get(task,false);JSONArray events=taskRow.getJSONArray("events");boolean proof=false;for(int i=0;i<events.length();i++){JSONObject event=events.getJSONObject(i);if(event.optString("type").equals("browser_recovery_requested")){proof=true;assertFalse(event.getBoolean("restoredGrant"));assertFalse(event.getBoolean("restoredActionRefs"));}}assertTrue(proof);
            JSONObject staleArgs=j("tabId",first,"observationId",old.getString("observationId"),"ref","n0");java.lang.reflect.Field spaceField=dev.pocketengine.BrowserActivity.class.getDeclaredField("taskSpace");spaceField.setAccessible(true);BrowserTaskSpace freshSpace=(BrowserTaskSpace)spaceField.get(x.browser);assertThrows(Exception.class,()->freshSpace.pin(staleArgs));
            assertFalse("Old physical tab must not be re-created by restoration",x.browser.tabsForOwner().toString().contains(first));
            port.close();current.cancel();x.pump(x.worker.submit(()->{recovered.endForOwner();return true;}),false);
            TaskStore reopened=new TaskStore(RuntimeEnvironment.getApplication());try{assertEquals(task,reopened.get(task,false).getString("id"));assertEquals(0,BrowserTaskSourcePort.sourcesForOwner(reopened,task).length());}finally{BrowserTaskPersistenceTest.closeDatabase(reopened);}
        }
    }
    @Test public void explicitConsentAndUnresolvedOriginalOperationBlockRecoveryBeforeNewAttempt()throws Exception {
        try(BrowserPageDialogActivityTest.F x=new BrowserPageDialogActivityTest.F()){
            x.open();x.tool("finish",new JSONObject());JSONObject m=BrowserResearchPages.fromTask(x.tasks.get(x.taskId,false));assertNotNull(m);String task=x.taskId,run=x.binding.runId;JSONObject profile=x.browser.snapshot();String fingerprint=TaskStore.capabilityFingerprint(m);
            x.control.cancel();x.control=null;x.tasks.status(task,"paused","fixture executor stopped");AtomicInteger launches=new AtomicInteger();BrowserTaskHost recovered=new BrowserTaskHost(x.module,x.tasks,new ExecutionResources(),new BrowserTaskHost.OriginalTasks(){public void checkModelConfigured(){}public RunControl currentControl(String id){return null;}public void launch(String id){launches.incrementAndGet();}},x.events::add);
            assertThrows(Exception.class,()->x.pump(x.worker.submit(()->recovered.recoverResearchForOwner(task,fingerprint,profile,false)),false));
            RunControl audit=new RunControl();audit.bindHostRun(task,run,x.binding.epoch);RunJournal original=x.tasks.journal(task,run,x.binding.epoch,audit);String pending=original.beginOperation("browser_task_open",j("url","https://example.com/unknown"));
            assertTrue(x.tasks.hasUnresolved(task));assertThrows(Exception.class,()->x.pump(x.worker.submit(()->recovered.recoverResearchForOwner(task,fingerprint,profile,true)),false));assertEquals(0,launches.get());assertEquals(run,x.tasks.get(task,false).getString("runId"));original.finishOperation(pending,j("accepted",false,"verified",false));
        }
    }
    private static java.util.Set<String> logicalIds(JSONObject manifest)throws Exception {java.util.Set<String> ids=new java.util.LinkedHashSet<>();JSONArray pages=manifest.getJSONArray("pages");for(int i=0;i<pages.length();i++)ids.add(pages.getJSONObject(i).getString("logicalPageId"));return ids;}
    private static JSONObject initialThreePages(BrowserPageDialogActivityTest.F x)throws Exception {
        x.open();x.tool("open",j("url","https://example.com/recover-second"));x.tool("open",j("url","https://example.com/recover-third"));x.tool("finish",new JSONObject());JSONObject saved=BrowserResearchPages.fromTask(x.tasks.get(x.taskId,false));assertEquals(3,saved.getJSONArray("pages").length());x.control.cancel();x.tick(false);x.control=null;x.tasks.status(x.taskId,"paused","original fixture executor settled for visible recovery");return saved;
    }
    /** Same production Host/startAttempt/approval/journal as the original test, repeated twice. */
    private static final class Recovery implements AutoCloseable {
        final BrowserPageDialogActivityTest.F x;final BrowserTaskHost host;final AtomicReference<RunControl> live=new AtomicReference<>();final ModelCapabilityAdapters.Binding binding;final BrowserTaskCapabilityPort port;final RunJournal journal;
        Recovery(BrowserPageDialogActivityTest.F x)throws Exception {this.x=x;AtomicReference<JSONObject> attempt=new AtomicReference<>();host=new BrowserTaskHost(x.module,x.tasks,new ExecutionResources(),new BrowserTaskHost.OriginalTasks(){public void checkModelConfigured(){}public RunControl currentControl(String id){return x.taskId.equals(id)?live.get():null;}public void launch(String id)throws Exception {assertEquals(x.taskId,id);JSONObject started=x.tasks.startAttempt(id,"capabilities",40);RunControl c=new RunControl();c.bindHostRun(id,started.getString("runId"),started.getInt("coordinatorEpoch"));x.tasks.status(id,"running","native recovery fixture admitted original attempt");attempt.set(started);live.set(c);}},x.events::add);
            JSONObject manifest=BrowserResearchPages.fromTask(x.tasks.get(x.taskId,false)),profile=x.browser.snapshot();String expected=TaskStore.capabilityFingerprint(manifest);x.pump(x.worker.submit(()->host.recoverResearchForOwner(x.taskId,expected,profile,true)),false);JSONObject started=attempt.get();binding=new ModelCapabilityAdapters.Binding(x.taskId,started.getString("runId"),started.getInt("coordinatorEpoch"),live.get(),true,"");journal=x.tasks.journal(x.taskId,binding.runId,binding.epoch,live.get());port=(BrowserTaskCapabilityPort)x.pump(x.worker.submit(()->host.wrapIfRequested(binding,live.get(),base())),false);
        }
        private <T>T pump(java.util.concurrent.Future<T> future)throws Exception {long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);while(!future.isDone()&&System.nanoTime()<until){x.tick(false);JSONArray approvals=x.tasks.get(x.taskId,false).getJSONArray("approvals");for(int i=0;i<approvals.length();i++){JSONObject a=approvals.getJSONObject(i);if(binding.runId.equals(a.optString("runId"))&&a.optString("state").equals("pending"))host.decideIfWaiting(x.taskId,a.getString("id"),true);}Thread.sleep(3);}x.tick(false);return future.get(2,java.util.concurrent.TimeUnit.SECONDS);}
        JSONObject open(JSONObject page)throws Exception {JSONObject args=j("url",page.getString("url"),"pageRecordId",page.getString("logicalPageId"));String op=journal.beginOperation("browser_task_open",args);return pump(x.worker.submit(()->{JSONObject receipt=port.execute("browser_task_open",new JSONObject(args.toString()).put("_hostOperationId",op),live.get());journal.finishOperation(op,receipt);port.afterTool("browser_task_open",args,receipt,live.get());return receipt;}));}
        JSONObject snapshot()throws Exception {return x.pump(x.worker.submit(()->port.contextSnapshot("repeated recovery")),false).getJSONObject("taskBrowser");}
        public void close()throws Exception {try{x.pump(x.worker.submit(host::endForOwner),false);}finally{RunControl c=live.getAndSet(null);if(c!=null)c.cancel();x.tick(false);x.tasks.status(x.taskId,"paused","native recovery fixture executor settled");}}
    }
    @Test public void repeatedRecoveryRetainsAllThreeLogicalPagesAfterOnlyOneIsReopened()throws Exception {try(BrowserPageDialogActivityTest.F x=new BrowserPageDialogActivityTest.F()){
        JSONObject saved=initialThreePages(x);java.util.Set<String> ids=logicalIds(saved);String reopenedId=saved.getJSONArray("pages").getJSONObject(0).getString("logicalPageId");String firstRun;
        try(Recovery one=new Recovery(x)){firstRun=one.binding.runId;JSONObject receipt=one.open(saved.getJSONArray("pages").getJSONObject(0));assertTrue(receipt.toString(),receipt.getBoolean("accepted"));assertEquals(reopenedId,receipt.getString("logicalPageId"));assertEquals(1,one.snapshot().getJSONArray("tabs").length());}
        try(Recovery two=new Recovery(x)){assertNotEquals(firstRun,two.binding.runId);JSONObject fresh=two.snapshot();assertEquals(0,fresh.getJSONArray("tabs").length());assertEquals(ids,logicalIds(fresh.getJSONObject("recovery")));assertFalse(fresh.getJSONObject("readScope").optString("state").equals("active"));JSONObject persisted=BrowserResearchPages.fromTask(x.tasks.get(x.taskId,false));assertEquals(ids,logicalIds(persisted));for(int i=0;i<persisted.getJSONArray("pages").length();i++){JSONObject page=persisted.getJSONArray("pages").getJSONObject(i);assertTrue(page.getBoolean("needsFreshObservation"));if(!reopenedId.equals(page.getString("logicalPageId")))assertEquals("needs_reload",page.getString("state"));}assertFalse(persisted.toString().contains("tabGeneration"));}
    }}
    @Test public void endingRecoveryWithoutOpeningAPageDoesNotPublishAnEmptyReplacementManifest()throws Exception {try(BrowserPageDialogActivityTest.F x=new BrowserPageDialogActivityTest.F()){
        JSONObject saved=initialThreePages(x);java.util.Set<String> ids=logicalIds(saved);try(Recovery unopened=new Recovery(x)){assertEquals(0,unopened.snapshot().getJSONArray("tabs").length());}
        JSONObject latest=BrowserResearchPages.fromTask(x.tasks.get(x.taskId,false));assertEquals(ids,logicalIds(latest));for(int i=0;i<latest.getJSONArray("pages").length();i++){JSONObject page=latest.getJSONArray("pages").getJSONObject(i);assertEquals("needs_reload",page.getString("state"));assertTrue(page.getBoolean("needsFreshObservation"));}try(Recovery again=new Recovery(x)){assertEquals(ids,logicalIds(again.snapshot().getJSONObject("recovery")));assertEquals(0,again.snapshot().getJSONArray("tabs").length());}
    }}

}
