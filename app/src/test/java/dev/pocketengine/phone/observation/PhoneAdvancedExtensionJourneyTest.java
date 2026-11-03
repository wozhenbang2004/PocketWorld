package dev.pocketengine.phone.observation;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.phone.PhoneUiSignals;
import dev.pocketengine.phone.access.AppCopyKey;
import dev.pocketengine.phone.access.GuardedPhonePort;
import dev.pocketengine.phone.access.PhoneAccessPolicy;
import dev.pocketengine.phone.access.PhoneOwnerPackages;
import dev.pocketengine.phone.diagnostics.PhonePracticeActivity;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;
import static dev.pocketengine.phone.observation.PhoneCollectionFixtures.*;

/** Real original TaskStore/admission/adapter/signals; physical trees and input are explicit fixtures, not device proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class PhoneAdvancedExtensionJourneyTest {
    private static final class Physical implements PhonePort,NativePhoneExtensions,NativeTreeScrollPort,NativePhoneSignals {
        final RunControl original;final AtomicInteger observations=new AtomicInteger(),effects=new AtomicInteger();
        volatile PhoneUiSignals signals=new PhoneUiSignals("accessibility-session",()->1000L);
        volatile boolean connected=true,unknownPaste,dialog,practice,replaceWheel;
        volatile int wheel=1;String afterWheelResource;
        Physical(RunControl original){this.original=original;}
        public boolean isConnected(){return connected;}
        public void clearReferences() { }
        public PhoneUiSignals uiSignals(RunControl control){assertSame(original,control);return signals;}
        public JSONObject observe(boolean image,RunControl control)throws Exception {
            assertSame(original,control);assertFalse("host fixture never returns an image",image);control.check();
            JSONObject actual=tree("fixture-"+observations.incrementAndGet(),"Action "+wheel,"Input "+wheel);
            actual.put("pauseRevision",control.pauseRevision());JSONArray nodes=actual.getJSONArray("nodes");
            nodes.getJSONObject(0).put("text",Integer.toString(wheel));
            if(replaceWheel&&wheel>1)nodes.getJSONObject(0).put("resourceId","app:id/replacement");
            nodes.getJSONObject(2).put("clickable",true);nodes.getJSONObject(4).put("editable",true);
            if(dialog){nodes.getJSONObject(0).put("role","dialog");nodes.getJSONObject(4).put("clickable",true).put("visible",false);}
            if(practice){
                nodes.getJSONObject(0).put("description","PW_DIAG_LIST");
                nodes.getJSONObject(2).put("text",PhonePracticeActivity.MARKER+"\nPW_DIAG_SESSION:fixture-practice");
                nodes.getJSONObject(4).put("text","PW_DIAG_ROW_"+wheel);
            }
            return actual;
        }
        public JSONObject execute(String tool,JSONObject arguments,RunControl control)throws Exception {
            assertSame(original,control);assertEquals("phone_type",tool);assertEquals("",arguments.getString("text"));
            control.dispatchAtPauseRevision(control.pauseRevision(),effects::incrementAndGet);
            return j("ok",true,"accepted",true);
        }
        public JSONObject extension(String tool,JSONObject arguments,RunControl control,NativePhoneEntry.Admission admission)throws Exception {
            assertSame(original,control);
            return NativePhoneEntry.call(control,control.pauseRevision(),admission,()->{
                effects.incrementAndGet();
                if(tool.equals("phone_practice_target")){practice=true;return j("ok",true,"accepted",true,"practiceSessionId","fixture-practice");}
                if(tool.equals("phone_paste_text")&&unknownPaste)return j("ok",false,"accepted",JSONObject.NULL,"error","clipboard_input_unknown","requiresReconciliation",true);
                throw new AssertionError("unexpected physical fixture extension "+tool);
            });
        }
        public JSONObject scrollNode(JSONObject arguments,RunControl control,NativePhoneEntry.Admission admission)throws Exception {
            assertSame(original,control);
            return NativePhoneEntry.call(control,control.pauseRevision(),admission,()->{effects.incrementAndGet();wheel++;return j("ok",true,"accepted",true);});
        }
    }
    private static final class Host implements AutoCloseable {
        final TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());final RunControl control=new RunControl();
        final Physical physical=new Physical(control);final AtomicReference<PhoneAccessPolicy.State> policy=new AtomicReference<>(new PhoneAccessPolicy.State(0,true,Map.of()));
        final AdaptivePhonePort phone;final CapabilityPort capabilities;final RunJournal journal;final String task,run;final int epoch;
        Host()throws Exception{this(false);}
        Host(boolean guard)throws Exception {
            JSONObject attempt=tasks.startAttempt(tasks.create("phone extension host journey","main_screen").getString("id"),"main_screen",20);
            task=attempt.getString("id");run=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");control.bindHostRun(task,run,epoch);
            PhonePort source=guard?new GuardedPhonePort(physical,policy::get,c->new PhoneOwnerPackages(Set.of(new AppCopyKey("app.example",0)),true),0):physical;
            phone=new AdaptivePhonePort(source,new AdaptivePhonePort.Scope(task,run,epoch,"s",5),false,false,()->1000L);
            CapabilityPort base=new CapabilityPort(){
                public JSONArray tools(){return new JSONArray();}public boolean supports(String name){return false;}public boolean sideEffecting(String name){return false;}
                public JSONObject execute(String name,JSONObject args,RunControl control){throw new AssertionError("no second executor");}
            };
            capabilities=PhoneTreeCapabilityPort.bindIfAvailable(base,tasks,new ModelCapabilityAdapters.Binding(task,run,epoch,control,true,""),control,phone);
            assertTrue(capabilities instanceof PhoneTreeCapabilityPort);JSONArray granted=new JSONArray(),definitions=capabilities.tools();
            for(int i=0;i<definitions.length();i++)granted.put(definitions.getJSONObject(i).getJSONObject("function").getString("name"));
            tasks.authorizeTools(task,run,granted);tasks.status(task,"running","original native extension fixture");journal=tasks.journal(task,run,epoch,control);
        }
        JSONObject observe()throws Exception{JSONObject result=phone.observe(false,control);assertTrue(result.toString(),result.optBoolean("ok"));return result;}
        JSONObject prepared(String tool,JSONObject args)throws Exception{return new JSONObject(args.toString()).put("_hostOperationId",journal.beginOperation(tool,args));}
        JSONObject execute(String tool,JSONObject args)throws Exception{return capabilities.execute(tool,args,control);}
        public void close()throws Exception {
            capabilities.close();control.cancel();physical.signals.close();Field db=TaskStore.class.getDeclaredField("db");db.setAccessible(true);((SQLiteDatabase)db.get(tasks)).close();
        }
    }
    @Test public void clearConsumesOneOriginalPreparedOperationAndDuplicateCannotReenter()throws Exception {
        try(Host host=new Host()){
            JSONObject observation=host.observe(),args=j("observationId",observation.getString("observationId"),"nodeId","n4");
            assertEquals("original_prepared_operation_required",host.execute("phone_clear_text",args).getString("error"));assertEquals(0,host.physical.effects.get());
            JSONObject original=host.prepared("phone_clear_text",args),receipt=host.execute("phone_clear_text",original);
            assertTrue(receipt.getBoolean("accepted"));assertTrue(receipt.getBoolean("freshObservationAvailable"));assertEquals(1,host.physical.effects.get());
            assertEquals("original_phone_action_already_attempted",host.execute("phone_clear_text",original).getString("error"));assertEquals(1,host.physical.effects.get());
            host.journal.finishOperation(original.getString("_hostOperationId"),receipt);
            JSONArray operations=host.tasks.get(host.task,false).getJSONArray("operations");assertEquals(1,operations.length());assertEquals("receipt",operations.getJSONObject(0).getString("state"));
            assertFalse("OS acceptance is not business proof",receipt.getBoolean("verifiedBusinessSuccess"));
        }
    }
    @Test public void wheelMakesOneNativeStepAndReportsActualPartialOrUnrelatedReplacement()throws Exception {
        for(boolean replacement:new boolean[]{false,true})try(Host host=new Host()){
            host.physical.replaceWheel=replacement;JSONObject observation=host.observe();
            JSONObject args=j("observationId",observation.getString("observationId"),"nodeId","n0","direction","forward","value","3");
            JSONObject receipt=host.execute("phone_set_wheel",host.prepared("phone_set_wheel",args));
            assertEquals(1,host.physical.effects.get());assertEquals(1,receipt.getInt("stepsPerformed"));assertFalse(receipt.getBoolean("reached"));assertTrue(receipt.getBoolean("freshObservationAvailable"));
            if(replacement){assertEquals("unreadable",receipt.getString("status"));assertTrue(receipt.isNull("actualValue"));}
            else{assertEquals("partial",receipt.getString("status"));assertEquals("2",receipt.getString("actualValue"));}
            assertFalse(receipt.getBoolean("automaticReplayAllowed"));
        }
    }
    @Test public void unknownPasteRemainsInOriginalJournalAndNeitherRefreshNorDuplicateReplaysIt()throws Exception {
        try(Host host=new Host()){
            host.physical.unknownPaste=true;JSONObject before=host.observe();JSONObject args=j("observationId",before.getString("observationId"),"nodeId","n4","text","requested value","allowClipboardFallback",true);
            JSONObject original=host.prepared("phone_paste_text",args),receipt=host.execute("phone_paste_text",original);
            assertTrue(receipt.isNull("accepted"));assertTrue(receipt.getBoolean("requiresReconciliation"));assertEquals(1,host.physical.observations.get());assertEquals(1,host.physical.effects.get());
            assertEquals("original_phone_action_already_attempted",host.execute("phone_paste_text",original).getString("error"));
            assertEquals("reconciliation_required",host.phone.observe(false,host.control).getString("error"));assertEquals(1,host.physical.effects.get());
            assertTrue(host.tasks.hasUnresolved(host.task));JSONArray operations=host.tasks.get(host.task,false).getJSONArray("operations");assertEquals(1,operations.length());assertEquals(original.getString("_hostOperationId"),operations.getJSONObject(0).getString("operationId"));
        }
    }
    @Test public void dialogReturnsOnlyCurrentVisibleReferencesWithoutConfirmingAnything()throws Exception {
        try(Host host=new Host()){
            host.physical.dialog=true;JSONObject first=host.observe();JSONObject result=host.execute("phone_dialog",j("observationId",first.getString("observationId")));
            assertTrue(result.getBoolean("found"));assertEquals(1,result.getJSONArray("buttons").length());assertEquals("n2",result.getJSONArray("buttons").getJSONObject(0).getString("nodeId"));
            assertEquals("untrusted_screen_content",result.getString("contentTrust"));assertFalse(result.getBoolean("actionPerformed"));assertFalse(result.getBoolean("automaticConfirmation"));
            host.observe();assertEquals("stale_observation",host.execute("phone_dialog",j("observationId",first.getString("observationId"))).getString("error"));assertEquals(0,host.physical.effects.get());
        }
    }
    private static final class WaitCall {
        final AtomicReference<JSONObject> result=new AtomicReference<>();final AtomicReference<Throwable> error=new AtomicReference<>();final Thread thread;
        WaitCall(Host host,JSONObject observation)throws Exception {
            JSONObject args=j("observationId",observation.getString("observationId"),"timeoutMs",3000);
            thread=new Thread(()->{try{result.set(host.execute("phone_event",args));}catch(Throwable failed){error.set(failed);}},"original-phone-event-fixture");thread.start();
        }
        void awaiting()throws Exception{long until=System.nanoTime()+2_000_000_000L;while(thread.isAlive()&&System.nanoTime()<until){for(StackTraceElement at:thread.getStackTrace())if(at.getClassName().equals(PhoneUiSignals.class.getName())&&at.getMethodName().equals("awaitChange"))return;Thread.sleep(2);}fail("original event wait was not reached: "+error.get()+" / "+result.get());}
        void done()throws Exception{thread.join(2000);assertFalse("event wait must terminate",thread.isAlive());}
        void stop()throws Exception{if(thread.isAlive()){thread.interrupt();thread.join(1000);}}
    }
    @Test public void eventHintIgnoresAnotherWindowAndReadsTheOriginalDisplayOnceAfterItsEvent()throws Exception {
        try(Host host=new Host()){
            WaitCall call=new WaitCall(host,host.observe());try{
                call.awaiting();host.physical.signals.record(10);call.thread.join(60);assertTrue(call.thread.isAlive());assertEquals(1,host.physical.observations.get());
                host.physical.signals.record(9);call.done();assertNull(call.error.get());JSONObject result=call.result.get();assertEquals("event_hint",result.getString("status"));assertTrue(result.getBoolean("eventObserved"));assertEquals(2,host.physical.observations.get());assertEquals(5,result.getInt("displayId"));
                assertFalse(result.getBoolean("containsNotificationText"));assertFalse(result.getBoolean("eventProvesBusinessOutcome"));assertEquals(0,host.physical.effects.get());
            }finally{call.stop();}
        }
    }
    @Test public void replacedSignalSourceCannotDeliverAnOldCallbackAsCurrentEvidence()throws Exception {
        try(Host host=new Host()){
            WaitCall call=new WaitCall(host,host.observe());try{
                call.awaiting();PhoneUiSignals old=host.physical.signals;host.physical.signals=new PhoneUiSignals("replacement-accessibility-session",()->1000L);old.record(9);call.done();
                assertNull(call.error.get());assertFalse(call.result.get().getBoolean("ok"));assertEquals("original_tree_or_scroll_authority_changed",call.result.get().getString("error"));assertEquals(1,host.physical.observations.get());old.close();
            }finally{call.stop();}
        }
    }
    @Test public void cancellationAndPauseResumeFenceEventsBeforeAFreshRead()throws Exception {
        for(boolean cancel:new boolean[]{false,true})try(Host host=new Host()){
            WaitCall call=new WaitCall(host,host.observe());try{
                call.awaiting();if(cancel)host.control.cancel();else{host.control.pause();host.control.resume();}
                host.physical.signals.record(9);call.done();assertEquals(1,host.physical.observations.get());assertEquals(0,host.physical.effects.get());
                if(cancel)assertTrue(String.valueOf(call.error.get()),call.error.get() instanceof InterruptedException);else{assertNull(call.error.get());assertFalse(call.result.get().getBoolean("ok"));}
            }finally{call.stop();}
        }
    }
    @Test public void appPolicyRevisionRevokesHistoricalImagesAndOldModelResponseWithoutNativeRead()throws Exception {
        try(Host host=new Host(true)){
            host.observe();host.phone.requireHistoryCurrent(host.control);assertTrue(host.capabilities.responseCurrent(host.control));
            host.policy.set(new PhoneAccessPolicy.State(1,true,Map.of()));assertThrows(SecurityException.class,()->host.phone.requireHistoryCurrent(host.control));
            assertFalse(host.capabilities.responseCurrent(host.control));assertEquals(1,host.physical.observations.get());assertEquals(0,host.physical.effects.get());
            host.phone.requireHistoryCurrent(host.control);assertNotNull(host.observe());
        }
    }
    @Test public void practiceScrollPassRequiresTheOriginalActionAndDifferentFreshRows()throws Exception {
        try(Host host=new Host()){
            JSONObject launch=j("packageName","app.example","userId",0);JSONObject started=host.execute("phone_practice_target",host.prepared("phone_practice_target",launch));assertTrue(started.getBoolean("accepted"));
            JSONObject before=host.observe();assertEquals("unknown",check(host.phone.ownerPracticeResult(host.task,host.run,host.epoch),"scroll"));
            JSONObject wheel=j("observationId",before.getString("observationId"),"nodeId","n0","value","2","direction","forward");
            JSONObject receipt=host.execute("phone_set_wheel",host.prepared("phone_set_wheel",wheel));assertTrue(receipt.getBoolean("accepted"));assertEquals("2",receipt.getString("actualValue"));
            assertEquals("passed",check(host.phone.ownerPracticeResult(host.task,host.run,host.epoch),"scroll"));assertEquals(2,host.physical.effects.get());
        }
    }
    private static String check(JSONObject report,String id)throws Exception{JSONArray checks=report.getJSONArray("checks");for(int i=0;i<checks.length();i++)if(id.equals(checks.getJSONObject(i).getString("id")))return checks.getJSONObject(i).getString("state");throw new AssertionError(id);}
}
