package dev.pocketengine.store;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;

/** Same TaskStore SQLite connection/transaction. Control only: no prompt, credential or charge totals. */
final class InferenceRequestStore {
    private InferenceRequestStore() { }
    static void createSchema(SQLiteDatabase d){
        d.execSQL("CREATE TABLE IF NOT EXISTS inference_requests(logical_request_id TEXT PRIMARY KEY,client_binding_id TEXT NOT NULL,request_digest TEXT NOT NULL,task_id TEXT NOT NULL,run_id TEXT NOT NULL,epoch INTEGER NOT NULL,execution_kind TEXT NOT NULL,route_snapshot TEXT NOT NULL,budget_owner_ref TEXT NOT NULL,state TEXT NOT NULL,semantic_committed INTEGER NOT NULL DEFAULT 0,chosen_call_id TEXT,attempt_control TEXT NOT NULL,deadline_ms INTEGER NOT NULL,created_at_ms INTEGER NOT NULL,updated_at_ms INTEGER NOT NULL,UNIQUE(client_binding_id,logical_request_id))");
        d.execSQL("CREATE INDEX IF NOT EXISTS inference_requests_task ON inference_requests(task_id,created_at_ms)");
    }
    static JSONObject get(SQLiteDatabase d,String id)throws Exception {
        try(Cursor c=d.rawQuery("SELECT client_binding_id,request_digest,task_id,run_id,epoch,route_snapshot,budget_owner_ref,state,semantic_committed,chosen_call_id,attempt_control,deadline_ms,created_at_ms,updated_at_ms FROM inference_requests WHERE logical_request_id=?",new String[]{id})){
            if(!c.moveToFirst())return null;return new JSONObject().put("logicalRequestId",id).put("clientBindingId",c.getString(0)).put("requestDigest",c.getString(1)).put("taskId",c.getString(2)).put("runId",c.getString(3)).put("epoch",c.getInt(4)).put("routeSnapshot",new JSONObject(c.getString(5))).put("budgetOwnerRef",c.getString(6)).put("state",c.getString(7)).put("semanticCommitted",c.getInt(8)!=0).put("chosenCallId",c.isNull(9)?JSONObject.NULL:c.getString(9)).put("control",new JSONObject(c.getString(10))).put("deadlineAt",c.getLong(11)).put("createdAt",c.getLong(12)).put("updatedAt",c.getLong(13));
        }
    }
    static JSONObject begin(SQLiteDatabase d,String id,String client,String digest,dev.pocketengine.runtime.usage.InferenceReceipt.Context context,JSONObject plan,String budget,long deadline,int maxAttempts)throws Exception {
        if(!id.matches("[a-fA-F0-9-]{36}")||!digest.matches("[a-f0-9]{64}")||deadline<=System.currentTimeMillis())throw new SecurityException("invalid_logical_request");
        JSONObject prior=get(d,id);if(prior!=null){if(!client.equals(prior.getString("clientBindingId"))||!digest.equals(prior.getString("requestDigest")))throw new SecurityException("logical_request_digest_conflict");throw new SecurityException("logical_request_already_admitted_query_original");}
        plan=new JSONObject(plan.toString()).put("contextMemberId",context.memberId).put("contextPurpose",context.purpose.name());
        // A resumed original run cannot acquire a new default/group revision. This is an
        // identity pin, not authorization: every subsequent physical call rechecks revocation.
        try(Cursor c=d.rawQuery("SELECT route_snapshot FROM inference_requests WHERE task_id=? AND run_id=? AND epoch=? ORDER BY created_at_ms DESC",new String[]{context.taskId,context.runId,String.valueOf(context.epoch)})){
            while(c.moveToNext()){JSONObject frozen=new JSONObject(c.getString(0));if(plan.getString("purpose").equals(frozen.getString("purpose"))&&context.memberId.equals(frozen.optString("contextMemberId"))){if(!identity(plan).equals(identity(frozen)))throw new SecurityException("original_run_model_selection_changed");break;}}
        }
        long now=System.currentTimeMillis();ContentValues v=new ContentValues();v.put("logical_request_id",id);v.put("client_binding_id",client);v.put("request_digest",digest);v.put("task_id",context.taskId);v.put("run_id",context.runId);v.put("epoch",context.epoch);v.put("execution_kind","HTTP_INFERENCE");v.put("route_snapshot",plan.toString());v.put("budget_owner_ref",budget);v.put("state","PLANNED");v.put("attempt_control",new JSONObject().put("maxAttempts",maxAttempts).put("maxRouteSwitches",plan.getInt("maxRouteSwitches")).put("attempts",new JSONArray()).put("completionOutcome","unknown").put("streamCompleteness","none").toString());v.put("deadline_ms",deadline);v.put("created_at_ms",now);v.put("updated_at_ms",now);d.insertOrThrow("inference_requests",null,v);return get(d,id);
    }
    private static String identity(JSONObject plan)throws Exception {JSONObject copy=new JSONObject(plan.toString());copy.remove("snapshotId");copy.remove("quota");JSONArray rows=copy.getJSONArray("orderedCandidates");for(int i=0;i<rows.length();i++)rows.getJSONObject(i).remove("exclusion");return canonical(copy);}
    private static String canonical(Object value)throws Exception {if(value instanceof JSONObject j){java.util.List<String> keys=new java.util.ArrayList<>();j.keys().forEachRemaining(keys::add);java.util.Collections.sort(keys);StringBuilder s=new StringBuilder("{");for(String key:keys)s.append(JSONObject.quote(key)).append(':').append(canonical(j.get(key))).append(',');return s.append('}').toString();}if(value instanceof JSONArray a){StringBuilder s=new StringBuilder("[");for(int i=0;i<a.length();i++)s.append(canonical(a.get(i))).append(',');return s.append(']').toString();}return value instanceof String?JSONObject.quote((String)value):String.valueOf(value);}
    static void save(SQLiteDatabase d,JSONObject row)throws Exception {ContentValues v=new ContentValues();v.put("state",row.getString("state"));v.put("semantic_committed",row.getBoolean("semanticCommitted")?1:0);if(row.isNull("chosenCallId"))v.putNull("chosen_call_id");else v.put("chosen_call_id",row.getString("chosenCallId"));v.put("attempt_control",row.getJSONObject("control").toString());v.put("updated_at_ms",System.currentTimeMillis());if(d.update("inference_requests",v,"logical_request_id=?",new String[]{row.getString("logicalRequestId")})!=1)throw new IllegalStateException("logical_request_lost");}
    static JSONObject attempt(JSONObject row,String call)throws Exception {JSONArray a=row.getJSONObject("control").getJSONArray("attempts");for(int i=0;i<a.length();i++)if(call.equals(a.getJSONObject(i).getString("callId")))return a.getJSONObject(i);throw new SecurityException("attempt_not_reserved");}
    static void receipt(SQLiteDatabase d,JSONObject receipt,boolean starting)throws Exception {
        JSONObject route=receipt.getJSONObject("route");String id=route.optString("logicalRequestId");if(id.isEmpty())return;
        JSONObject row=get(d,id),context=receipt.getJSONObject("context");if(row==null||!row.getString("taskId").equals(context.getString("taskId"))||!row.getString("runId").equals(context.getString("runId"))||row.getInt("epoch")!=context.getInt("epoch"))throw new SecurityException("logical_request_context_mismatch");
        JSONObject nativeContext=row.getJSONObject("routeSnapshot");if(!nativeContext.getString("contextMemberId").equals(context.optString("memberId"))||!nativeContext.getString("contextPurpose").equals(context.getString("purpose")))throw new SecurityException("logical_request_member_or_purpose_mismatch");
        JSONObject attempt=attempt(row,receipt.getString("callId"));if(attempt.getInt("attemptNo")!=route.getInt("attemptNo")||!attempt.getString("candidateId").equals(route.getString("candidateId")))throw new SecurityException("logical_attempt_route_mismatch");
        if(starting){if(row.getBoolean("semanticCommitted")||row.getLong("deadlineAt")<=System.currentTimeMillis()||!"reserved".equals(attempt.getString("dispatchState")))throw new SecurityException("logical_attempt_not_dispatchable");attempt.put("dispatchState","may_have_occurred");row.put("state","DISPATCH_MAY_HAVE_OCCURRED");}
        else {attempt.put("receiptRef",receipt.getString("callId")).put("outcome",receipt.getString("outcome")).put("finishedAt",receipt.get("finishedAt"));}
        save(d,row);
    }
    /** Bind selected receipt identities to an already reserved physical attempt.
     * This is preparation evidence only, never proof that network I/O occurred. */
    static void bindOwnerWorkReceipt(SQLiteDatabase d,String task,String run,int epoch,String call,String work,String delivery)throws Exception {
        JSONObject original=null,selected=null;
        try(Cursor rows=d.rawQuery("SELECT logical_request_id FROM inference_requests WHERE task_id=? AND run_id=? AND epoch=?",new String[]{task,run,Integer.toString(epoch)})){
            while(rows.moveToNext()){
                JSONObject row=get(d,rows.getString(0));JSONArray attempts=row.getJSONObject("control").getJSONArray("attempts");
                for(int i=0;i<attempts.length();i++)if(call.equals(attempts.getJSONObject(i).getString("callId"))){
                    if(original!=null)throw new SecurityException("owner_receipt_call_identity_ambiguous");original=row;selected=attempts.getJSONObject(i);
                }
            }
        }
        if(original==null||!"ATTEMPT_RESERVED".equals(original.getString("state"))||!"reserved".equals(selected.getString("dispatchState")))throw new SecurityException("owner_receipt_call_not_original_reserved_attempt");
        JSONArray references=selected.optJSONArray("ownerWorkReceipts");if(references==null)references=new JSONArray();
        for(int i=0;i<references.length();i++){JSONObject ref=references.getJSONObject(i);if(work.equals(ref.getString("taskId"))&&delivery.equals(ref.getString("deliveryId")))return;}
        if(references.length()>=16)throw new SecurityException("owner_receipt_reference_limit");
        references.put(new JSONObject().put("taskId",work).put("deliveryId",delivery));selected.put("ownerWorkReceipts",references);save(d,original);
    }
    /** Only positive, original not_dispatched evidence releases a bound receipt.
     * Reserved, missing, partial, completed or potentially dispatched calls are not
     * evidence that the old consumer did nothing. Inspect every physical attempt. */
    static boolean ownerWorkReceiptNeverDispatched(SQLiteDatabase d,String task,String run,int epoch,String work,String delivery)throws Exception {
        boolean found=false;
        try(Cursor rows=d.rawQuery("SELECT attempt_control FROM inference_requests WHERE task_id=? AND run_id=? AND epoch=?",new String[]{task,run,Integer.toString(epoch)})){
            while(rows.moveToNext()){
                JSONArray attempts=new JSONObject(rows.getString(0)).getJSONArray("attempts");
                for(int i=0;i<attempts.length();i++){
                    JSONObject attempt=attempts.getJSONObject(i);JSONArray references=attempt.optJSONArray("ownerWorkReceipts");
                    for(int j=0;references!=null&&j<references.length();j++){
                        JSONObject ref=references.getJSONObject(j);if(!work.equals(ref.getString("taskId"))||!delivery.equals(ref.getString("deliveryId")))continue;
                        found=true;
                        if(!"not_dispatched".equals(attempt.optString("dispatchState"))||attempt.has("outcome")||TaskAuditStore.call(d,task,attempt.getString("callId"))!=null)return false;
                    }
                }
            }
        }
        return found;
    }
    static JSONArray list(SQLiteDatabase d,String task)throws Exception {JSONArray out=new JSONArray();try(Cursor c=d.rawQuery("SELECT logical_request_id FROM inference_requests WHERE task_id=? ORDER BY created_at_ms DESC LIMIT 16",new String[]{task})){int bytes=0;while(c.moveToNext()){JSONObject row=get(d,c.getString(0));int size=row.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;if(bytes+size>65536)break;out.put(row);bytes+=size;}}return out;}
    static void recover(SQLiteDatabase d)throws Exception {d.beginTransaction();try{java.util.List<String> ids=new java.util.ArrayList<>();try(Cursor c=d.rawQuery("SELECT logical_request_id FROM inference_requests WHERE state IN ('PLANNED','ATTEMPT_RESERVED','DISPATCH_MAY_HAVE_OCCURRED','SEMANTIC_COMMITTED')",null)){while(c.moveToNext())ids.add(c.getString(0));}for(String id:ids){JSONObject row=get(d,id);row.put("state",row.getBoolean("semanticCommitted")?"PARTIAL":"UNKNOWN");row.getJSONObject("control").put("completionOutcome","unknown").put("streamCompleteness",row.getBoolean("semanticCommitted")?"partial":"none");save(d,row);}d.setTransactionSuccessful();}finally{d.endTransaction();}}
}
