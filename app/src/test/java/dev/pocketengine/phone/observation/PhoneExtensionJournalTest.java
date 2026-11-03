package dev.pocketengine.phone.observation;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.*;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real original TaskStore/prepared operation; the physical field is an explicit interface fixture. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class PhoneExtensionJournalTest {
    @Test public void authorityLossAfterClearEntryCannotBecomeAReadOnlyNonDispatchReceipt()throws Exception {
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());RunControl original=new RunControl();AtomicInteger effects=new AtomicInteger();CapabilityPort capabilities=null;
        try{
            JSONObject task=tasks.startAttempt(tasks.create("清空原任务中的普通字段","main_screen").getString("id"),"main_screen",10);
            String id=task.getString("id"),run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");original.bindHostRun(id,run,epoch);
            PhonePort physical=new PhonePort(){
                public boolean isConnected(){return true;}public void clearReferences(){}
                public JSONObject observe(boolean includeImage,RunControl control)throws Exception {assertSame(original,control);assertFalse(includeImage);if(effects.get()>0)throw new SecurityException("permission_revoked_after_native_entry");return PhoneTreeSequenceTest.actual("fresh");}
                public JSONObject execute(String tool,JSONObject args,RunControl control)throws Exception {assertSame(original,control);assertEquals("phone_type",tool);assertEquals("",args.getString("text"));control.dispatchAtPauseRevision(control.pauseRevision(),effects::incrementAndGet);return new JSONObject().put("ok",true).put("accepted",true);}
            };
            AdaptivePhonePort phone=new AdaptivePhonePort(physical,new AdaptivePhonePort.Scope(id,run,epoch,"s",5),false,false,()->1000L);
            CapabilityPort base=new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String tool){return false;}public boolean sideEffecting(String tool){return false;}public JSONObject execute(String tool,JSONObject args,RunControl control){throw new AssertionError("no alternate executor");}};
            capabilities=PhoneTreeCapabilityPort.bindIfAvailable(base,tasks,new ModelCapabilityAdapters.Binding(id,run,epoch,original,true,""),original,phone);
            JSONArray granted=new JSONArray(),definitions=capabilities.tools();for(int i=0;i<definitions.length();i++)granted.put(definitions.getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(id,run,granted);tasks.status(id,"running","original extension fixture");
            JSONObject observed=phone.observe(false,original),arguments=new JSONObject().put("observationId",observed.getString("observationId")).put("nodeId","n2");
            RunJournal journal=tasks.journal(id,run,epoch,original);String operation=journal.beginOperation("phone_clear_text",arguments);
            JSONObject dispatched=new JSONObject(arguments.toString()).put("_hostOperationId",operation);CapabilityPort exact=capabilities;
            assertThrows(SecurityException.class,()->exact.execute("phone_clear_text",dispatched,original));
            assertEquals(1,effects.get());JSONArray rows=tasks.get(id,false).getJSONArray("operations");assertEquals(1,rows.length());assertEquals(operation,rows.getJSONObject(0).getString("operationId"));assertEquals("prepared",rows.getJSONObject(0).getString("state"));assertTrue(tasks.hasUnresolved(id));
            exact.execute("phone_clear_text",dispatched,original);assertEquals("same prepared operation is never entered again",1,effects.get());
        }finally{if(capabilities!=null)capabilities.close();original.cancel();Field db=TaskStore.class.getDeclaredField("db");db.setAccessible(true);((SQLiteDatabase)db.get(tasks)).close();}
    }
}

