package dev.pocketengine.store;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;

/** Native SQL-issued stop authority. Serialized feature input cannot construct a ticket. */
public final class OriginalRemoteStopClaim {
    interface CurrentTask {JSONObject read()throws Exception;}
    interface ReceiptRecorder {void record(JSONObject actual)throws Exception;}
    private final Object owner;
    private final SQLiteDatabase db;
    private final CurrentTask current;
    private final ReceiptRecorder recorder;
    private final String snapshot,operation;
    private final boolean dispatch;
    private boolean entered,postAttempted;

    private OriginalRemoteStopClaim(Object owner,SQLiteDatabase db,CurrentTask current,ReceiptRecorder recorder,JSONObject target,String operation,boolean dispatch){
        this.owner=owner;this.db=db;this.current=current;this.recorder=recorder;snapshot=target.toString();this.operation=operation;this.dispatch=dispatch;
    }
    static OriginalRemoteStopClaim issue(Object owner,SQLiteDatabase db,CurrentTask current,ReceiptRecorder recorder,String task,String run,int epoch,String profileBinding)throws Exception {
        synchronized(owner){
            if(profileBinding==null||!profileBinding.matches("[a-f0-9]{64}"))throw new SecurityException("没有原电脑配置代次");
            JSONObject original=target(db,current.read());
            if(!task.equals(original.getString("taskId"))||!run.equals(original.getString("runId"))||epoch!=original.getInt("coordinatorEpoch"))throw new SecurityException("原停止任务或执行代次已变化");
            if(original.has("backendSelection")&&!profileBinding.equals(original.getString("profileBinding")))throw new SecurityException("后端停止不能换绑原电脑配置代次");
            original.put("_profileBinding",profileBinding);
            String operation=UUID.nameUUIDFromBytes(("pocketworld/original-remote-stop/v1/"+task+"/"+run+"/"+epoch+"/"+original.getString("jobId")+"/"+original.getString("jobOperationId")).getBytes(StandardCharsets.UTF_8)).toString();
            boolean dispatch;
            db.beginTransaction();try{
                String state=null;
                try(Cursor row=db.rawQuery("SELECT task_id,run_id,tool,arguments,state FROM operations WHERE operation_id=?",new String[]{operation})){
                    if(row.moveToFirst()){
                        if(!task.equals(row.getString(0))||!run.equals(row.getString(1))||!"remote_original_stop".equals(row.getString(2))||!same(original,new JSONObject(row.getString(3))))throw new SecurityException("原停止记录已绑定另一身份或配置代次");state=row.getString(4);
                    }
                }
                if(state==null){ContentValues row=new ContentValues();row.put("operation_id",operation);row.put("task_id",task);row.put("run_id",run);row.put("tool","remote_original_stop");row.put("arguments",original.toString());row.put("state","prepared");row.put("created_at",System.currentTimeMillis());db.insertOrThrow("operations",null,row);state="prepared";}
                JSONObject taskNow=current.read(),evidence=taskNow.getJSONObject("evidence");boolean released=taskNow.optString("executionStatus").matches("completed|failed|cancelled")&&Boolean.TRUE.equals(evidence.opt("dispatchFenceFinal"))&&Boolean.FALSE.equals(evidence.opt("resourcesHeld"));
                if(released&&state.equals("prepared")){ContentValues settled=new ContentValues();settled.put("state","receipt");settled.put("receipt",metadata(new JSONObject(evidence.toString()).put("status",taskNow.getString("executionStatus"))).toString());db.update("operations",settled,"operation_id=? AND state='prepared'",new String[]{operation});state="receipt";}
                dispatch=state.equals("prepared")&&!released;db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            return new OriginalRemoteStopClaim(owner,db,current,recorder,original,operation,dispatch);
        }
    }
    public JSONObject target()throws Exception{return new JSONObject(snapshot);}
    public boolean canDispatch(){return dispatch;}
    /** An object can enter only once; another holder must still win the SQL prepared -> unknown CAS. */
    public void beginDispatch()throws Exception {synchronized(owner){if(entered)throw new SecurityException("原停止票不可重复派发");entered=true;checkCurrent();}}
    public void beforeDispatch()throws Exception {synchronized(owner){if(!entered)throw new SecurityException("原停止票尚未入场");checkCurrent();}}
    /** Durable unknown BEFORE the only POST. A failed/uncertain write never grants a retry. */
    public void beginPost()throws Exception {synchronized(owner){
        if(!entered||postAttempted||!dispatch)throw new SecurityException("原停止只能派发一次；现在只查询");postAttempted=true;
        db.beginTransaction();try{checkCurrent();ContentValues value=new ContentValues();value.put("state","unknown");
            if(db.update("operations",value,"operation_id=? AND tool='remote_original_stop' AND state='prepared'",new String[]{operation})!=1)throw new SecurityException("原停止派发已被消费，只能查询原任务");db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }}
    public void record(JSONObject actual)throws Exception {synchronized(owner){
        db.beginTransaction();try{
            checkCurrent();JSONObject original=target();
            for(String key:new String[]{"profileId","serviceBase"})if(!original.getString(key).equals(actual.optString(key)))throw new SecurityException("停止回执改变原电脑连接");
            if(!original.getString("jobId").equals(actual.optString("id"))||!original.getString("jobOperationId").equals(actual.optString("operationId")))throw new SecurityException("停止回执不是原job/operation");
            requireBackendReceipt(original,actual);
            boolean settled=actual.optString("status").matches("completed|failed|cancelled")&&Boolean.TRUE.equals(actual.opt("dispatchFenceFinal"))&&Boolean.FALSE.equals(actual.opt("resourcesHeld"));
            ContentValues value=new ContentValues();value.put("receipt",metadata(actual).toString());if(settled)value.put("state","receipt");db.update("operations",value,"operation_id=? AND tool='remote_original_stop' AND state IN ('prepared','unknown')",new String[]{operation});
            recorder.record(new JSONObject(actual.toString()));db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }}
    /** Ordinary original-job polling can close a pending stop only with actual final release facts. */
    static void observe(SQLiteDatabase db,JSONObject task,JSONObject actual)throws Exception {
        if(!actual.optString("status").matches("completed|failed|cancelled")||!Boolean.TRUE.equals(actual.opt("dispatchFenceFinal"))||!Boolean.FALSE.equals(actual.opt("resourcesHeld")))return;
        try(Cursor rows=db.rawQuery("SELECT operation_id,arguments FROM operations WHERE task_id=? AND run_id=? AND tool='remote_original_stop' AND state IN ('prepared','unknown')",new String[]{task.getString("id"),task.getString("runId")})){
            while(rows.moveToNext()){
                JSONObject original=new JSONObject(rows.getString(1)),current;try{current=target(db,task);}catch(SecurityException unavailable){continue;}current.put("_profileBinding",original.getString("_profileBinding"));
                if(!same(original,current)||!original.getString("jobId").equals(actual.optString("id"))||!original.getString("jobOperationId").equals(actual.optString("operationId"))||!original.getString("profileId").equals(actual.optString("profileId"))||!original.getString("serviceBase").equals(actual.optString("serviceBase")))continue;
                requireBackendReceipt(original,actual);
                ContentValues value=new ContentValues();value.put("state","receipt");value.put("receipt",metadata(actual).toString());db.update("operations",value,"operation_id=? AND tool='remote_original_stop' AND state IN ('prepared','unknown')",new String[]{rows.getString(0)});
            }
        }
    }
    private static JSONObject metadata(JSONObject actual)throws Exception {JSONObject result=new JSONObject();for(String key:new String[]{"id","operationId","profileId","serviceBase","status","message","originalCancellation","dispatchFenceFinal","resourcesHeld","processCleanupProof","updatedAt","cancellationDispatched","queryOnly","effectsReversed","backendSelection","environmentId","environmentBinding","worker"})if(actual.has(key))result.put(key,actual.get(key));return result;}
    private static void requireBackendReceipt(JSONObject original,JSONObject actual)throws Exception {
        if(!original.has("backendSelection"))return;
        dev.pocketengine.features.remote.HarnessCapabilityObservation.requireSameBackendSelection(original.getJSONObject("backendSelection"),actual.optJSONObject("backendSelection"));
        for(String key:new String[]{"environmentId","environmentBinding","worker"})if(!original.get(key).equals(actual.opt(key)))throw new SecurityException("停止回执改变原后端来源");
    }
    private void checkCurrent()throws Exception {
        JSONObject original=target(),task=current.read(),actual=target(db,task);actual.put("_profileBinding",original.getString("_profileBinding"));
        if(!same(original,actual)||!task.optBoolean("cancelRequested"))throw new SecurityException("原停止任务、代次或目标已变化");
        try(Cursor row=db.rawQuery("SELECT arguments,state FROM operations WHERE operation_id=? AND task_id=? AND run_id=? AND tool='remote_original_stop'",new String[]{operation,original.getString("taskId"),original.getString("runId")})){
            if(!row.moveToFirst()||!same(original,new JSONObject(row.getString(0)))||!row.getString(1).matches("prepared|unknown|receipt"))throw new SecurityException("原停止票已失效");
        }
    }
    private static JSONObject target(SQLiteDatabase db,JSONObject task)throws Exception {
        String id=identifier(task,"id"),run=identifier(task,"runId"),operation=identifier(task,"operationId");int epoch=task.getInt("coordinatorEpoch");
        JSONObject refs=task.getJSONObject("externalRefs"),evidence=task.getJSONObject("evidence");String profile=identifier(refs,"profileId"),job=identifier(task,"externalId"),service=evidence.getString("serviceBase");
        if(!"remote".equals(task.optString("feature"))||!run.equals(operation)||!operation.equals(evidence.optString("operationId"))||!profile.equals(evidence.optString("profileId"))||service.isEmpty())throw new SecurityException("原电脑执行身份不完整");
        if(task.has("remoteInputJobId")&&!job.equals(task.getString("remoteInputJobId")))throw new SecurityException("原输入job关联已变化");
        try(Cursor row=db.rawQuery("SELECT epoch FROM runs WHERE task_id=? AND run_id=?",new String[]{id,run})){if(!row.moveToFirst()||epoch!=row.getInt(0))throw new SecurityException("原SQL执行代次已变化");}
        JSONObject backendTarget=null,backendSelection=null;
        try(Cursor row=db.rawQuery("SELECT run_id,arguments,receipt FROM operations WHERE operation_id=? AND task_id=? AND tool='remote_job'",new String[]{operation,id})){
            if(!row.moveToFirst()||!run.equals(row.getString(0))||row.isNull(2))throw new SecurityException("缺少原SQL电脑job回执");JSONObject args=new JSONObject(row.getString(1)),receipt=new JSONObject(row.getString(2));
            if(!run.equals(args.optString("runId"))||epoch!=args.optInt("coordinatorEpoch",-1)||!job.equals(receipt.optString("id"))||!operation.equals(receipt.optString("operationId"))||!profile.equals(receipt.optString("profileId"))||!service.equals(receipt.optString("serviceBase")))throw new SecurityException("原SQL job/operation/连接不匹配");
            if(args.has("backendSelection")||"gemini_acp".equals(refs.optString("worker"))){
                backendTarget=args.getJSONObject("target");backendSelection=dev.pocketengine.features.remote.HarnessCapabilityObservation.checkedBackendSelection(args.getJSONObject("backendSelection"));
                for(JSONObject value:new JSONObject[]{refs,receipt,evidence})dev.pocketengine.features.remote.HarnessCapabilityObservation.requireSameBackendSelection(backendSelection,value.getJSONObject("backendSelection"));
                for(String key:new String[]{"profileId","profileBinding","serviceBase","environmentId","environmentBinding","worker"})if(!backendTarget.get(key).equals(refs.opt(key)))throw new SecurityException("原停止后端绑定与首创记录不同");
                for(String key:new String[]{"profileId","serviceBase","environmentId","environmentBinding","worker"})if(!backendTarget.get(key).equals(receipt.opt(key))||!backendTarget.get(key).equals(evidence.opt(key)))throw new SecurityException("原停止回执与后端首创来源不同");
            }
        }
        JSONObject result=new JSONObject().put("taskId",id).put("runId",run).put("coordinatorEpoch",epoch).put("profileId",profile).put("serviceBase",service).put("jobId",job).put("jobOperationId",operation)
                .put("actorId",task.getString("actorId")).put("spaceId",task.getString("spaceId")).put("origin",task.optJSONObject("origin")==null?new JSONObject():new JSONObject(task.getJSONObject("origin").toString()));
        if(backendSelection!=null){result.put("backendSelection",new JSONObject(backendSelection.toString()));for(String key:new String[]{"profileBinding","environmentId","environmentBinding","worker"})result.put(key,backendTarget.get(key));}
        if(task.has("parentTaskId")){
            String parent=identifier(task,"parentTaskId"),parentRun=identifier(task,"parentRunId");JSONObject originalParent;
            try(Cursor row=db.rawQuery("SELECT document FROM tasks WHERE task_id=?",new String[]{parent})){if(!row.moveToFirst())throw new SecurityException("原父任务已缺失");originalParent=new JSONObject(row.getString(0));}
            if(!parentRun.equals(originalParent.optString("runId")))throw new SecurityException("原父任务已换执行");
            result.put("parentTaskId",parent).put("parentRunId",parentRun).put("parentEpoch",originalParent.getInt("coordinatorEpoch"));
            if(task.has("parentRequestHash"))for(String key:new String[]{"parentOperationId","parentRequestHash","remoteRequestHash"})result.put(key,task.getString(key));
            try(Cursor row=db.rawQuery("SELECT epoch FROM runs WHERE task_id=? AND run_id=?",new String[]{parent,parentRun})){if(!row.moveToFirst()||row.getInt(0)!=originalParent.getInt("coordinatorEpoch"))throw new SecurityException("原父SQL执行代次已变化");}
            boolean linked=false;org.json.JSONArray links=originalParent.optJSONArray("relatedTasks");if(links!=null&&links.length()>100)throw new SecurityException("原子任务索引超限");
            for(int i=0;links!=null&&i<links.length();i++){JSONObject link=links.getJSONObject(i);boolean originalRun=task.has("parentRequestHash")?id.equals(link.optString("runId"))&&task.getString("parentOperationId").equals(link.optString("parentOperationId")):run.equals(link.optString("runId"));if(id.equals(link.optString("taskId"))&&originalRun&&"remote".equals(link.optString("feature")))linked=true;}if(!linked)throw new SecurityException("原父任务不再关联此执行");
        }
        return result;
    }
    private static String identifier(JSONObject value,String key)throws Exception {String id=value.getString(key);if(!id.matches("[A-Za-z0-9_-]{1,80}"))throw new SecurityException("原停止编号无效");return id;}
    private static boolean same(JSONObject a,JSONObject b)throws Exception {if(a.length()!=b.length())return false;for(java.util.Iterator<String> keys=a.keys();keys.hasNext();){String key=keys.next();if(!sameValue(a.get(key),b.opt(key)))return false;}return true;}
    private static boolean sameValue(Object left,Object right)throws Exception {
        if(left instanceof JSONObject)return right instanceof JSONObject&&same((JSONObject)left,(JSONObject)right);
        if(left instanceof org.json.JSONArray){if(!(right instanceof org.json.JSONArray))return false;org.json.JSONArray a=(org.json.JSONArray)left,b=(org.json.JSONArray)right;if(a.length()!=b.length())return false;for(int i=0;i<a.length();i++)if(!sameValue(a.get(i),b.get(i)))return false;return true;}
        return left.equals(right);
    }
}
