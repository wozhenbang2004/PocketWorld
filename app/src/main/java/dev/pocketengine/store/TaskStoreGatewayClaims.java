package dev.pocketengine.store;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.proxy.NativeHarnessGatewayBridge;
import java.util.Set;
import java.util.UUID;
import org.json.JSONObject;
import org.json.JSONArray;

/** Original Tasks DB control index only. No budget, model usage, task, runner or tool grant.
 * Inactive lease/claim tombstones remain with original audit retention, including after a task
 * is removed: deleting transport history must never make an old identity dispatchable again.
 * Every lease has at most 128 claims; no body, token or provider credential is stored. */
final class TaskStoreGatewayClaims {
    private TaskStoreGatewayClaims(){}
    static void createSchema(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS harness_gateway_leases(lease_id TEXT PRIMARY KEY,task_id TEXT NOT NULL,run_id TEXT NOT NULL,epoch INTEGER NOT NULL,identity_digest TEXT NOT NULL,descriptor TEXT NOT NULL,state TEXT NOT NULL,expires_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS harness_gateway_claims(lease_id TEXT NOT NULL,request_id TEXT NOT NULL,request_digest TEXT NOT NULL,claim_id TEXT NOT NULL UNIQUE,state TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,PRIMARY KEY(lease_id,request_id))");
    }
    static void register(SQLiteDatabase db,JSONObject descriptor)throws Exception {
        Set<String> keys=new java.util.HashSet<>(Set.of("leaseId","clientBindingId","generation","environmentId","environmentBinding","bindingId","profileDigest","taskId","runId","epoch","audience","purpose","expiresAt"));if(descriptor.has("configurationOwner"))keys.add("configurationOwner");
        if(descriptor.length()!=keys.size())throw new SecurityException("原网关租约字段不完整");for(var it=descriptor.keys();it.hasNext();)if(!keys.contains(it.next()))throw new SecurityException("租约不保存凭据或请求体");
        for(String key:new String[]{"leaseId","clientBindingId","generation","environmentId","bindingId","taskId","runId"})id(descriptor.getString(key));
        if(!"OWNER_PRIVATE".equals(descriptor.getString("audience"))||!"EXTERNAL_RELAY".equals(descriptor.getString("purpose"))||!descriptor.getString("profileDigest").matches("[a-f0-9]{64}")||descriptor.getString("environmentBinding").length()>256||descriptor.getString("environmentBinding").isEmpty())throw new SecurityException("只签发固定私人API-only桥接租约");
        TaskExecutionScope.exactInt(descriptor,"epoch",1,Integer.MAX_VALUE);TaskExecutionScope.exactLong(descriptor,"expiresAt",1,Long.MAX_VALUE);
        try(Cursor pending=db.rawQuery("SELECT 1 FROM harness_gateway_leases l WHERE l.task_id=? AND (l.state='REQUIRES_RECONCILE' OR EXISTS(SELECT 1 FROM harness_gateway_claims c WHERE c.lease_id=l.lease_id AND c.state IN ('UNKNOWN','MAY_HAVE_DISPATCHED'))) LIMIT 1",new String[]{descriptor.getString("taskId")})){if(pending.moveToFirst())throw new SecurityException("原任务还有未知桥接请求；不能换新lease重发，只能核对原请求或由主人结束旧范围");}
        String canonical=TaskStore.capabilityFingerprint(descriptor);
        ContentValues row=new ContentValues();row.put("lease_id",descriptor.getString("leaseId"));row.put("task_id",descriptor.getString("taskId"));row.put("run_id",descriptor.getString("runId"));row.put("epoch",descriptor.getInt("epoch"));row.put("identity_digest",canonical);row.put("descriptor",descriptor.toString());row.put("state","ACTIVE");row.put("expires_at",descriptor.getLong("expiresAt"));
        db.insertOrThrow("harness_gateway_leases",null,row); // Original identity is never rebound.
    }
    static void require(SQLiteDatabase db,String lease,String task,String run,int epoch)throws Exception {
        try(Cursor row=db.rawQuery("SELECT task_id,run_id,epoch,state,expires_at FROM harness_gateway_leases WHERE lease_id=?",new String[]{lease})){
            if(!row.moveToFirst()||!task.equals(row.getString(0))||!run.equals(row.getString(1))||epoch!=row.getInt(2)||!"ACTIVE".equals(row.getString(3))||row.getLong(4)<=System.currentTimeMillis())throw new SecurityException("原网关租约已失效或等待核对；旧请求不重新推理");
        }
    }
    static NativeHarnessGatewayBridge.Claim claim(SQLiteDatabase db,String lease,String request,String digest)throws Exception {
        id(request);if(!digest.matches("[a-f0-9]{64}"))throw new SecurityException("原wire请求摘要必需");
        try(Cursor row=db.rawQuery("SELECT request_digest,claim_id FROM harness_gateway_claims WHERE lease_id=? AND request_id=?",new String[]{lease,request})){
            if(row.moveToFirst()){if(!digest.equals(row.getString(0)))throw new SecurityException("相同原请求身份但digest不同");return new NativeHarnessGatewayBridge.Claim(row.getString(1),false);}
        }
        try(Cursor count=db.rawQuery("SELECT count(*) FROM harness_gateway_claims WHERE lease_id=?",new String[]{lease})){if(!count.moveToFirst()||count.getInt(0)>=128)throw new SecurityException("原租约claim观察上限已到");}
        String claim=UUID.randomUUID().toString();long now=System.currentTimeMillis();ContentValues row=new ContentValues();row.put("lease_id",lease);row.put("request_id",request);row.put("request_digest",digest);row.put("claim_id",claim);row.put("state","MAY_HAVE_DISPATCHED");row.put("created_at",now);row.put("updated_at",now);db.insertOrThrow("harness_gateway_claims",null,row);
        return new NativeHarnessGatewayBridge.Claim(claim,true);
    }
    static void outcome(SQLiteDatabase db,String lease,String request,String claim,boolean complete)throws Exception {
        ContentValues row=new ContentValues();row.put("state",complete?"COMPLETE":"UNKNOWN");row.put("updated_at",System.currentTimeMillis());
        if(db.update("harness_gateway_claims",row,"lease_id=? AND request_id=? AND claim_id=? AND state='MAY_HAVE_DISPATCHED'",new String[]{lease,request,claim})!=1)throw new SecurityException("不是原唯一推理claim，终态不倒写");
        if(!complete)revoke(db,lease,"REQUIRES_RECONCILE");
    }
    static void revoke(SQLiteDatabase db,String lease,String state){ContentValues row=new ContentValues();row.put("state",state);db.update("harness_gateway_leases",row,"lease_id=? AND state='ACTIVE'",new String[]{lease});}
    static void recover(SQLiteDatabase db){db.execSQL("UPDATE harness_gateway_claims SET state='UNKNOWN' WHERE state='MAY_HAVE_DISPATCHED'");db.execSQL("UPDATE harness_gateway_leases SET state='REQUIRES_RECONCILE' WHERE state='ACTIVE'");}
    static void requireParentHistorySettled(SQLiteDatabase db,String task)throws Exception {try(Cursor row=db.rawQuery("SELECT 1 FROM harness_gateway_leases l WHERE l.task_id=? AND (l.state IN ('ACTIVE','REQUIRES_RECONCILE') OR EXISTS(SELECT 1 FROM harness_gateway_claims c WHERE c.lease_id=l.lease_id AND c.state IN ('UNKNOWN','MAY_HAVE_DISPATCHED'))) LIMIT 1",new String[]{task})){if(row.moveToFirst())throw new SecurityException("原父任务历史网关仍活动或有未知请求；只查旧scope，不借新run洗账");}}
    static void requireNoUnknown(SQLiteDatabase db,String task,String run,int epoch)throws Exception {try(Cursor pending=db.rawQuery("SELECT 1 FROM harness_gateway_leases l WHERE l.task_id=? AND (l.state='REQUIRES_RECONCILE' OR EXISTS(SELECT 1 FROM harness_gateway_claims c WHERE c.lease_id=l.lease_id AND c.state='UNKNOWN')) LIMIT 1",new String[]{task})){if(pending.moveToFirst())throw new SecurityException("原任务有未知桥接请求；只查原编号，不发行替代租约");}}
    static JSONObject descriptor(SQLiteDatabase db,String lease)throws Exception {id(lease);try(Cursor row=db.rawQuery("SELECT descriptor FROM harness_gateway_leases WHERE lease_id=?",new String[]{lease})){if(!row.moveToFirst())throw new SecurityException("原lease不存在");return new JSONObject(row.getString(0));}}
    static JSONArray records(SQLiteDatabase db,String task,String run)throws Exception {
        JSONArray result=new JSONArray();
        try(Cursor leases=db.rawQuery("SELECT lease_id,descriptor,state FROM harness_gateway_leases WHERE task_id=? AND run_id=? ORDER BY rowid DESC LIMIT 16",new String[]{task,run})){
            while(leases.moveToNext()){
                JSONObject row=new JSONObject().put("descriptor",new JSONObject(leases.getString(1))).put("state",leases.getString(2));JSONArray requests=new JSONArray();
                try(Cursor claims=db.rawQuery("SELECT request_id,request_digest,claim_id,state,updated_at FROM harness_gateway_claims WHERE lease_id=? ORDER BY created_at LIMIT 128",new String[]{leases.getString(0)})){while(claims.moveToNext())requests.put(new JSONObject().put("requestId",claims.getString(0)).put("requestDigest",claims.getString(1)).put("claimId",claims.getString(2)).put("state",claims.getString(3)).put("updatedAt",claims.getLong(4)));}
                result.put(row.put("requests",requests).put("readOnly",true).put("resubmitted",false));
            }
        }
        return result;
    }
    private static void id(String value){if(value==null||!value.matches("[A-Za-z0-9_-]{1,80}"))throw new SecurityException("准确网关原生身份必需");}
}
