package dev.pocketengine.store;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual root TaskStore device-v8 and organization-v9 SQLite migration/recovery, not a temporary version overlay.
 * The legacy layout is the original retained v7 schema, with all new device tables removed and
 * user_version=7. Seeds are controlled native fixtures, not real provider/device receipts. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class DeviceV8MigrationNativeTest {
    private SQLiteDatabase sql;
    private final List<SQLiteDatabase> opened=new ArrayList<>();
    @Before public void open(){RuntimeEnvironment.getApplication().deleteDatabase("pocket-world.db");}
    @After public void close(){for(SQLiteDatabase db:opened)if(db.isOpen())db.close();}
    private SQLiteDatabase database(TaskStore tasks)throws Exception{var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)field.get(tasks);opened.add(db);return db;}
    private long count(String table){try(Cursor row=sql.rawQuery("SELECT COUNT(*) FROM "+table,null)){row.moveToFirst();return row.getLong(0);}}
    private String scalar(String query,String... args){try(Cursor row=sql.rawQuery(query,args)){assertTrue(query,row.moveToFirst());return row.getString(0);}}
    private void asVersion7(){
        List<String> added=new ArrayList<>();try(Cursor rows=sql.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'device_%'",null)){while(rows.moveToNext())added.add(rows.getString(0));}
        assertEquals("current Trust7+Tasks3; includes native pairing candidates",10,added.size());for(String table:added)sql.execSQL("DROP TABLE "+table);
        sql.setVersion(7);assertEquals(7,sql.getVersion());sql.close();
    }
    @Test public void version7To8RetainsOriginalTaskRunOperationGatewayAndInferenceIdentities()throws Exception{
        TaskStore before=new TaskStore(RuntimeEnvironment.getApplication());sql=database(before);assertEquals(9,sql.getVersion());
        String id=before.createCapabilityTask("legacy durable original task","").getString("id");long deadline=System.currentTimeMillis()+300000;
        before.configureExecutionScope(id,0,deadline,8,8);JSONObject task=before.startAttempt(id,"capabilities",8);String run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");RunControl original=new RunControl();original.bindHostRun(id,run,epoch);
        before.authorizeTools(id,run,new JSONArray().put("fixture_effect"));before.status(id,"running","legacy native fixture");var journal=before.journal(id,run,epoch,original);
        String settled=journal.beginOperation("fixture_effect",new JSONObject().put("marker","original-arguments"));journal.finishOperation(settled,new JSONObject().put("ok",true).put("nativeFixtureReceipt","preserve-byte-value"));
        String pending=journal.beginOperation("fixture_effect",new JSONObject().put("marker","pending-original-no-replay"));
        String lease=UUID.randomUUID().toString(),request=UUID.randomUUID().toString();JSONObject descriptor=new JSONObject().put("leaseId",lease).put("clientBindingId","legacy-client").put("generation","legacy-generation").put("environmentId","legacy-environment").put("environmentBinding","legacy-env-binding").put("bindingId","legacy-binding").put("profileDigest","a".repeat(64)).put("taskId",id).put("runId",run).put("epoch",epoch).put("audience","OWNER_PRIVATE").put("purpose","EXTERNAL_RELAY").put("expiresAt",deadline);
        TaskStoreGatewayClaims.register(sql,descriptor);var claim=TaskStoreGatewayClaims.claim(sql,lease,request,"b".repeat(64));assertTrue(claim.dispatchAllowed());
        String inference=UUID.randomUUID().toString();JSONObject route=new JSONObject().put("purpose","CHAT").put("maxRouteSwitches",0).put("orderedCandidates",new JSONArray());
        InferenceRequestStore.begin(sql,inference,id+":"+run,"c".repeat(64),new InferenceReceipt.Context(id,run,epoch,InferenceReceipt.Purpose.MAIN,""),route,id,deadline,1);
        String grant=scalar("SELECT grant_json FROM runs WHERE run_id=?",run),receipt=scalar("SELECT receipt FROM operations WHERE operation_id=?",settled),arguments=scalar("SELECT arguments FROM operations WHERE operation_id=?",pending);
        asVersion7();TaskStore migrated=new TaskStore(RuntimeEnvironment.getApplication());sql=database(migrated);assertEquals(9,sql.getVersion());
        JSONObject recovered=migrated.get(id,false);assertEquals(run,recovered.getString("runId"));assertEquals(epoch,recovered.getInt("coordinatorEpoch"));assertEquals(2,recovered.getJSONObject("executionScope").getInt("toolsUsed"));assertFalse("pending effect cannot become success",recovered.optString("status").equals("completed"));
        assertEquals(grant,scalar("SELECT grant_json FROM runs WHERE run_id=?",run));assertEquals(receipt,scalar("SELECT receipt FROM operations WHERE operation_id=?",settled));assertEquals(arguments,scalar("SELECT arguments FROM operations WHERE operation_id=?",pending));assertEquals("unknown",scalar("SELECT state FROM operations WHERE operation_id=?",pending));assertEquals(2,count("operations"));
        assertEquals("UNKNOWN",scalar("SELECT state FROM harness_gateway_claims WHERE lease_id=? AND request_id=?",lease,request));assertEquals("b".repeat(64),scalar("SELECT request_digest FROM harness_gateway_claims WHERE lease_id=? AND request_id=?",lease,request));assertEquals("REQUIRES_RECONCILE",scalar("SELECT state FROM harness_gateway_leases WHERE lease_id=?",lease));
        assertEquals("UNKNOWN",scalar("SELECT state FROM inference_requests WHERE logical_request_id=?",inference));assertEquals("c".repeat(64),scalar("SELECT request_digest FROM inference_requests WHERE logical_request_id=?",inference));assertEquals(id,scalar("SELECT budget_owner_ref FROM inference_requests WHERE logical_request_id=?",inference));
        for(String table:new String[]{"device_peers","device_grants","device_pairing_invites","device_revocation_acks","device_outgoing_grants","device_identity_rotations","device_pairing_candidates","device_inbound","device_child_budget","device_child_links"})assertEquals(table,0,count(table));
        assertTrue(migrated.hasUnresolved(id));assertThrows(SecurityException.class,()->TaskStoreGatewayClaims.requireNoUnknown(sql,id,run,epoch));
        sql.close();TaskStore again=new TaskStore(RuntimeEnvironment.getApplication());sql=database(again);assertEquals(2,count("operations"));assertEquals("unknown",scalar("SELECT state FROM operations WHERE operation_id=?",pending));assertEquals(receipt,scalar("SELECT receipt FROM operations WHERE operation_id=?",settled));assertEquals(1,count("inference_requests"));assertEquals(1,count("harness_gateway_claims"));
    }
    @Test public void unsupportedDowngradeFailsClosedWithoutDeletingOriginalRows()throws Exception{
        TaskStore before=new TaskStore(RuntimeEnvironment.getApplication());sql=database(before);String id=before.createCapabilityTask("retain unsupported future database","").getString("id");sql.setVersion(10);sql.close();
        assertThrows(android.database.sqlite.SQLiteException.class,()->new TaskStore(RuntimeEnvironment.getApplication()));
        sql=SQLiteDatabase.openDatabase(RuntimeEnvironment.getApplication().getDatabasePath("pocket-world.db").getAbsolutePath(),null,SQLiteDatabase.OPEN_READWRITE);opened.add(sql);assertEquals(10,sql.getVersion());assertEquals(1,count("tasks"));assertEquals(id,scalar("SELECT task_id FROM tasks"));
    }
}
