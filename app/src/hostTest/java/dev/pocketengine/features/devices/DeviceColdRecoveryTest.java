package dev.pocketengine.features.devices;

import android.app.Application;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real original SQLite close/reopen at journal/hold/bind/claim boundaries; no business
 * retransmission, provider request or physical-device execution is simulated as success. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class DeviceColdRecoveryTest {
    @Test public void pristineOriginalPreparedClosesOnRestartWithoutRefundOrReplay()throws Exception{
        DeviceIngressNativeTest fixture=new DeviceIngressNativeTest();fixture.open();SQLiteDatabase reopenedDb=null;
        try{TaskStore tasks=(TaskStore)get(fixture,"tasks");SQLiteDatabase db=(SQLiteDatabase)get(fixture,"sql");Object original=call(fixture,"outbound");String id=text(original,"id"),run=text(original,"run"),operation=text(original,"operation");JSONObject before=tasks.get(id,false).getJSONObject("executionScope");String grant=scalar(db,"SELECT grant_json FROM runs WHERE run_id=?",run);assertEquals("prepared",state(db,operation));assertNull(scalar(db,"SELECT receipt FROM operations WHERE operation_id=?",operation));assertEquals(0,count(db,"device_child_links"));assertEquals(0,count(db,"device_child_budget"));((RunControl)call(original,"control")).cancel();db.close();
            TaskStore reopened=new TaskStore(RuntimeEnvironment.getApplication());reopenedDb=(SQLiteDatabase)get(reopened,"db");assertEquals("receipt",state(reopenedDb,operation));JSONObject receipt=new JSONObject(scalar(reopenedDb,"SELECT receipt FROM operations WHERE operation_id=?",operation));assertEquals("device_not_dispatched_recovered",receipt.getString("code"));assertTrue(receipt.getBoolean("notDispatchedConfirmed"));assertFalse(receipt.getBoolean("quotaRefunded"));assertFalse(receipt.getBoolean("retrySubmitted"));assertFalse(reopened.hasUnresolved(id));assertEquals("interrupted",reopened.get(id,false).getString("status"));assertEquals(before.getInt("toolsUsed"),reopened.get(id,false).getJSONObject("executionScope").getInt("toolsUsed"));assertEquals(1,before.getInt("toolsUsed"));assertEquals(before.getInt("modelCallsUsed"),reopened.get(id,false).getJSONObject("executionScope").getInt("modelCallsUsed"));assertEquals(grant,scalar(reopenedDb,"SELECT grant_json FROM runs WHERE run_id=?",run));assertEquals(1,count(reopenedDb,"runs"));assertEquals(1,count(reopenedDb,"operations"));assertEquals(0,count(reopenedDb,"device_child_links"));
            String frozen=receipt.toString();reopenedDb.close();TaskStore again=new TaskStore(RuntimeEnvironment.getApplication());reopenedDb=(SQLiteDatabase)get(again,"db");assertEquals(frozen,scalar(reopenedDb,"SELECT receipt FROM operations WHERE operation_id=?",operation));assertEquals(1,count(reopenedDb,"operations"));assertFalse(again.hasUnresolved(id));
        }finally{if(reopenedDb!=null&&reopenedDb.isOpen())reopenedDb.close();fixture.close();}
    }
    @Test public void reserveOnlyIntentClosesButBoundClaimedAndUnknownNeverBecomeUnsent()throws Exception{
        DeviceIngressNativeTest fixture=new DeviceIngressNativeTest();fixture.open();SQLiteDatabase reopenedDb=null;
        try{TaskStore tasks=(TaskStore)get(fixture,"tasks");SQLiteDatabase db=(SQLiteDatabase)get(fixture,"sql");Object held=call(fixture,"outbound"),bound=call(fixture,"outbound"),claimed=call(fixture,"outbound"),unknown=call(fixture,"outbound"),evidence=call(fixture,"outbound");
            reserve(tasks,held);var boundReservation=reserve(tasks,bound);tasks.deviceTasks().bindReservedWire(boundReservation,wire(bound),(PairingCoordinator.OutgoingGrant)call(bound,"grant"));var claimedReservation=reserve(tasks,claimed);var ticket=tasks.deviceTasks().bindReservedWire(claimedReservation,wire(claimed),(PairingCoordinator.OutgoingGrant)call(claimed,"grant"));ticket.claimForWrite((DeviceIdentity.AuthenticatedPeer)get(fixture,"caller"));
            db.execSQL("UPDATE operations SET state='unknown' WHERE operation_id=?",new Object[]{text(unknown,"operation")});ContentValues marker=new ContentValues();marker.put("receipt",new JSONObject().put("unrecognizedDispatchEvidence",true).toString());db.update("operations",marker,"operation_id=?",new String[]{text(evidence,"operation")});db.close();TaskStore reopened=new TaskStore(RuntimeEnvironment.getApplication());reopenedDb=(SQLiteDatabase)get(reopened,"db");
            assertEquals("receipt",state(reopenedDb,text(held,"operation")));assertFalse(reopened.hasUnresolved(text(held,"id")));assertFalse(reopened.deviceTasks().hasUnstoppedChildOperations(text(held,"id"),text(held,"run")));JSONObject recoveredHeld=new JSONObject(scalar(reopenedDb,"SELECT receipt FROM operations WHERE operation_id=?",text(held,"operation")));assertTrue(recoveredHeld.getBoolean("allocationAuthorizationRetained"));assertTrue(recoveredHeld.getJSONObject("nativeRecoveryProof").getJSONObject("allocationEvidence").getBoolean("unboundIntentLinkPresent"));for(Object item:new Object[]{bound,claimed,unknown,evidence}){assertEquals("unknown",state(reopenedDb,text(item,"operation")));assertTrue(reopened.hasUnresolved(text(item,"id")));String receipt=scalar(reopenedDb,"SELECT receipt FROM operations WHERE operation_id=?",text(item,"operation"));assertTrue(receipt==null||!receipt.contains("device_not_dispatched_recovered"));}
            for(Object item:new Object[]{held,bound,claimed}){assertEquals(4,reopened.deviceTasks().heldBudget(text(item,"id"),text(item,"run"),false));assertEquals(4,reopened.deviceTasks().heldBudget(text(item,"id"),text(item,"run"),true));}assertEquals(5,count(reopenedDb,"operations"));assertEquals(3,count(reopenedDb,"device_child_links"));
        }finally{if(reopenedDb!=null&&reopenedDb.isOpen())reopenedDb.close();fixture.close();}
    }
    @Test public void localOwnerCancelsColdAcceptedNeverStartedGoalWithoutAdmissionOrPeerAuthority()throws Exception{
        DeviceIngressNativeTest fixture=new DeviceIngressNativeTest();fixture.open();SQLiteDatabase reopenedDb=null;
        try{TaskStore tasks=(TaskStore)get(fixture,"tasks");SQLiteDatabase db=(SQLiteDatabase)get(fixture,"sql");JSONObject request=(JSONObject)call(fixture,"goalWire",new Class<?>[]{String.class,int.class},"cold-pending-goal",4);DeviceExecutionAdmission admission=(DeviceExecutionAdmission)call(fixture,"admission",new Class<?>[]{JSONObject.class},request);String id=tasks.deviceTasks().accept(admission).getString("localTaskId");tasks.status(id,"awaiting_user","B has not chosen a model");JSONObject before=tasks.get(id,false).getJSONObject("deviceExecutionScope");ContentValues revoked=new ContentValues();revoked.put("revoked",1);db.update("device_peers",revoked,null,null);db.update("device_grants",revoked,null,null);db.close();TaskStore reopened=new TaskStore(RuntimeEnvironment.getApplication());reopenedDb=(SQLiteDatabase)get(reopened,"db");assertTrue(reopened.deviceTasks().hasPendingGoalCancellation(id));var review=reopened.deviceTasks().reviewPendingGoalCancellation(id);assertFalse(review.view().getBoolean("runStarted"));assertTrue(review.view().getBoolean("localOnly"));JSONObject result=reopened.deviceTasks().cancelPendingGoal(review);assertEquals("cancelled",result.getString("status"));assertTrue(result.getBoolean("stopped"));assertTrue(result.getBoolean("notDispatched"));assertFalse(result.getBoolean("newRun"));assertEquals(0,count(reopenedDb,"runs"));assertEquals(0,count(reopenedDb,"operations"));assertEquals(DeviceProtocol.canonical(before),DeviceProtocol.canonical(reopened.get(id,false).getJSONObject("deviceExecutionScope")));assertFalse(reopened.deviceTasks().hasPendingGoalCancellation(id));assertThrows(SecurityException.class,()->reopened.deviceTasks().cancelPendingGoal(review));
            reopenedDb.close();TaskStore again=new TaskStore(RuntimeEnvironment.getApplication());reopenedDb=(SQLiteDatabase)get(again,"db");assertEquals("cancelled",again.get(id,false).getString("status"));assertTrue(again.get(id,false).getBoolean("cancelRequested"));assertEquals(0,count(reopenedDb,"runs"));
        }finally{if(reopenedDb!=null&&reopenedDb.isOpen())reopenedDb.close();fixture.close();}
    }
    @Test public void oldLocalPendingReviewCannotCancelAConcurrentRealGoalRun()throws Exception{
        DeviceIngressNativeTest fixture=new DeviceIngressNativeTest();fixture.open();
        try{TaskStore tasks=(TaskStore)get(fixture,"tasks");SQLiteDatabase db=(SQLiteDatabase)get(fixture,"sql");JSONObject request=(JSONObject)call(fixture,"goalWire",new Class<?>[]{String.class,int.class},"start-races-local-cancel",4);DeviceExecutionAdmission admission=(DeviceExecutionAdmission)call(fixture,"admission",new Class<?>[]{JSONObject.class},request);String id=tasks.deviceTasks().accept(admission).getString("localTaskId");var review=tasks.deviceTasks().reviewPendingGoalCancellation(id);JSONObject current=tasks.get(id,false);ModelConfig fixtureApi=new ModelConfig("http://127.0.0.1:1","synthetic-key-no-request","fixture-model",false,4,5);var modelReview=tasks.deviceTasks().reviewGoalModel(id,current.getLong("deviceStateRevision"),admission,fixtureApi);var approval=tasks.deviceTasks().confirmGoalModel(modelReview);RunControl original=new RunControl();JSONObject started=tasks.deviceTasks().startGoalAttempt(id,admission,original,approval);assertThrows(SecurityException.class,()->tasks.deviceTasks().cancelPendingGoal(review));assertEquals(started.getString("runId"),tasks.get(id,false).getString("runId"));assertFalse(tasks.get(id,false).getBoolean("cancelRequested"));assertFalse(original.isCancelled());assertEquals(1,count(db,"runs"));original.cancel();
        }finally{fixture.close();}
    }
    private static dev.pocketengine.store.TaskStoreDeviceTasks.ChildReservation reserve(TaskStore tasks,Object value)throws Exception{return tasks.deviceTasks().reserveChildNative(text(value,"id"),text(value,"run"),((Number)call(value,"epoch")).intValue(),(RunControl)call(value,"control"),text(value,"operation"),(JSONObject)call(value,"draft"));}
    private static DeviceTaskEnvelope wire(Object value)throws Exception{return DeviceProtocol.parseRequest(DeviceProtocol.canonicalBytes((JSONObject)call(value,"draft")));}
    private static String state(SQLiteDatabase db,String operation){return scalar(db,"SELECT state FROM operations WHERE operation_id=?",operation);}
    private static long count(SQLiteDatabase db,String table){try(Cursor row=db.rawQuery("SELECT COUNT(*) FROM "+table,null)){assertTrue(row.moveToFirst());return row.getLong(0);}}
    private static String scalar(SQLiteDatabase db,String query,String argument){try(Cursor row=db.rawQuery(query,new String[]{argument})){assertTrue(row.moveToFirst());return row.isNull(0)?null:row.getString(0);}}
    private static String text(Object source,String method)throws Exception{return (String)call(source,method);}
    private static Object get(Object source,String field)throws Exception{Field f=source.getClass().getDeclaredField(field);f.setAccessible(true);return f.get(source);}
    private static Object call(Object source,String method)throws Exception{return call(source,method,new Class<?>[0]);}
    private static Object call(Object source,String method,Class<?>[] types,Object... args)throws Exception{Method m=source.getClass().getDeclaredMethod(method,types);m.setAccessible(true);try{return m.invoke(source,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception)throw (Exception)e.getCause();throw e;}}
}
