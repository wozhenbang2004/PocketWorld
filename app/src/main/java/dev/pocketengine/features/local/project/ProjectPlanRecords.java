package dev.pocketengine.features.local.project;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Immutable project proposals on the original TaskStore connection. A reference is never a grant. */
public final class ProjectPlanRecords {
    public static final int MAX_PLAN_BYTES=1024*1024,MAX_TASK_BYTES=16*1024*1024,MAX_STORE_BYTES=64*1024*1024,MAX_TASK_PLANS=64;
    private static final long UNREFERENCED_TTL_MILLIS=7L*24*60*60*1000;
    private ProjectPlanRecords() {}
    public static void createSchema(SQLiteDatabase db){
        db.execSQL("CREATE TABLE project_plans(plan_ref TEXT PRIMARY KEY,task_id TEXT NOT NULL,run_id TEXT NOT NULL,epoch INTEGER NOT NULL,project_id TEXT NOT NULL,checkout_id TEXT NOT NULL,plan_digest TEXT NOT NULL,body_sha256 TEXT NOT NULL,body TEXT NOT NULL,bytes INTEGER NOT NULL,created_at INTEGER NOT NULL,UNIQUE(task_id,run_id,epoch,plan_digest))");
        db.execSQL("CREATE INDEX project_plans_task ON project_plans(task_id,run_id)");
        db.execSQL("CREATE TABLE project_plan_uses(operation_id TEXT PRIMARY KEY,plan_ref TEXT NOT NULL)");
    }
    /** Only unreferenced proposals expire; operation evidence is retained until its task is removed. */
    public static void prune(SQLiteDatabase db,long now){
        db.execSQL("DELETE FROM project_plan_uses WHERE NOT EXISTS(SELECT 1 FROM operations o WHERE o.operation_id=project_plan_uses.operation_id)");
        db.execSQL("DELETE FROM project_plans WHERE NOT EXISTS(SELECT 1 FROM tasks t WHERE t.task_id=project_plans.task_id) OR (created_at<? AND NOT EXISTS(SELECT 1 FROM project_plan_uses u WHERE u.plan_ref=project_plans.plan_ref))",new Object[]{now-UNREFERENCED_TTL_MILLIS});
        db.execSQL("DELETE FROM project_plan_uses WHERE NOT EXISTS(SELECT 1 FROM project_plans p WHERE p.plan_ref=project_plan_uses.plan_ref)");
    }
    public static JSONObject store(SQLiteDatabase db,JSONObject binding,JSONObject input)throws Exception {
        JSONObject plan=ProjectBuildPlan.verified(input);String body=plan.toString();byte[] bytes=body.getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_PLAN_BYTES)throw new IllegalArgumentException("原生完整项目方案超过1MiB；请减少本次补丁范围，不能截断方案");
        String task=binding.getString("taskId"),run=binding.getString("runId");int epoch=binding.getInt("coordinatorEpoch");long now=System.currentTimeMillis();prune(db,now);
        try(Cursor row=db.rawQuery("SELECT plan_ref FROM project_plans WHERE task_id=? AND run_id=? AND epoch=? AND plan_digest=?",new String[]{task,run,String.valueOf(epoch),plan.getString("planDigest")})){if(row.moveToFirst())return reference(row.getString(0),plan);}
        try(Cursor usage=db.rawQuery("SELECT count(*),coalesce(sum(bytes),0) FROM project_plans WHERE task_id=?",new String[]{task})){usage.moveToFirst();if(usage.getLong(0)>=MAX_TASK_PLANS||usage.getLong(1)+bytes.length>MAX_TASK_BYTES)throw new IllegalStateException("原任务完整方案保留已达64份/16MiB；原已用方案不删除、不截断，也不会借ref恢复授权");}
        try(Cursor usage=db.rawQuery("SELECT coalesce(sum(bytes),0) FROM project_plans",null)){usage.moveToFirst();if(usage.getLong(0)+bytes.length>MAX_STORE_BYTES)throw new IllegalStateException("完整方案原件保留已达64MiB；保留已用操作证据，未记录新方案");}
        String ref=UUID.randomUUID().toString();ContentValues row=new ContentValues();row.put("plan_ref",ref);row.put("task_id",task);row.put("run_id",run);row.put("epoch",epoch);row.put("project_id",plan.getString("projectId"));row.put("checkout_id",plan.getString("checkoutId"));row.put("plan_digest",plan.getString("planDigest"));row.put("body_sha256",ProjectSources.sha256(bytes));row.put("body",body);row.put("bytes",bytes.length);row.put("created_at",now);db.insertOrThrow("project_plans",null,row);return reference(ref,plan);
    }
    public static JSONObject resolve(SQLiteDatabase db,JSONObject binding,JSONObject args)throws Exception {
        if(args.has("plan")&&!args.has("planRef")){requireInline(args);return new JSONObject(args.toString()).put("plan",ProjectBuildPlan.verified(args.getJSONObject("plan")));}
        if(!args.optString("planRef").matches("[a-f0-9-]{36}")||!args.optString("planDigest").matches("[a-f0-9]{64}"))throw new SecurityException("需要原生完整方案引用与摘要");
        try(Cursor row=db.rawQuery("SELECT task_id,run_id,epoch,project_id,checkout_id,plan_digest,body_sha256,bytes,body FROM project_plans WHERE plan_ref=?",new String[]{args.getString("planRef")})){
            if(!row.moveToFirst()||!binding.getString("taskId").equals(row.getString(0))||!binding.getString("runId").equals(row.getString(1))||binding.getInt("coordinatorEpoch")!=row.getInt(2)||!args.getString("planDigest").equals(row.getString(5))||row.getLong(7)>MAX_PLAN_BYTES)throw new SecurityException("方案引用不属于此原task/run/epoch，或原件已不在保留范围");
            String body=row.getString(8);byte[] bytes=body.getBytes(StandardCharsets.UTF_8);if(bytes.length!=row.getLong(7)||!ProjectSources.sha256(bytes).equals(row.getString(6)))throw new SecurityException("原完整方案字节已变化，未使用旧摘要替代原件");JSONObject plan=ProjectBuildPlan.verified(new JSONObject(body));
            if(!row.getString(3).equals(plan.getString("projectId"))||!row.getString(4).equals(plan.getString("checkoutId"))||!row.getString(5).equals(plan.getString("planDigest")))throw new SecurityException("原完整方案身份已变化");if(args.has("stepId"))ProjectBuildPlan.step(plan,args.getString("stepId"));return new JSONObject(args.toString()).put("plan",plan);
        }
    }
    public static void pin(SQLiteDatabase db,String operation,JSONObject args){if(!args.has("planRef"))return;ContentValues row=new ContentValues();row.put("operation_id",operation);row.put("plan_ref",args.optString("planRef"));db.insertOrThrow("project_plan_uses",null,row);}
    public static void requireInline(JSONObject args)throws Exception {
        if(args.has("planRef")||args.has("planDigest"))throw new IllegalArgumentException("inline plan与原生planRef不可混用");if(!args.has("plan"))throw new IllegalArgumentException("需要原完整plan或planRef");
        if(args.toString().length()>30000||!lossless(args))throw new IllegalArgumentException("旧inline方案超过原journal可无损记录的30k参数/12k字符串/200项数组范围；请用project_plan返回的原生planRef，不会裁剪后执行");
        ProjectBuildPlan.verified(args.getJSONObject("plan"));
    }
    private static boolean lossless(Object value)throws Exception {
        if(value instanceof String)return ((String)value).length()<=12000;
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;var keys=object.keys();while(keys.hasNext()){String key=keys.next();if(key.matches("(?i)apiKey|authorization|token|secret")||key.equals("imageBase64")||key.equals("imageDataUrl")||!lossless(object.get(key)))return false;}}
        if(value instanceof JSONArray){JSONArray array=(JSONArray)value;if(array.length()>200)return false;for(int i=0;i<array.length();i++)if(!lossless(array.get(i)))return false;}return true;
    }
    public static JSONObject reference(String ref,JSONObject plan)throws Exception {
        JSONArray steps=new JSONArray();JSONArray original=plan.getJSONArray("steps");for(int i=0;i<original.length();i++){JSONObject step=reviewStep(original.getJSONObject(i)),request=step.getJSONObject("request");if(request.has("content")){String text=request.getString("content");request.remove("content");request.put("contentBytes",text.getBytes(StandardCharsets.UTF_8).length).put("contentSha256",ProjectSources.sha256(text.getBytes(StandardCharsets.UTF_8)));}java.util.Set<String> allowed=java.util.Set.of("checkoutId","expectedSourceDigest","path","expectedHash","executable","argv","runMode","timeoutMillis","devWatch","liveEditServiceExecutionId","revertsOperationId","reversalScope","files","contentBytes","contentSha256","git","generatedFile");JSONArray omitted=new JSONArray();java.util.List<String> remove=new java.util.ArrayList<>();request.keys().forEachRemaining(key->{if(!allowed.contains(key))remove.add(key);});for(String key:remove){request.remove(key);omitted.put(key);}if(omitted.length()>0)step.put("requestFieldsOmitted",omitted);step.put("requestDigest",ProjectSources.digest(original.getJSONObject(i).getJSONObject("payload")));
            JSONArray files=request.optJSONArray("files");if(files!=null)for(int f=0;f<files.length();f++){JSONObject file=files.getJSONObject(f);JSONArray changes=file.optJSONArray("changes");if(changes!=null){file.remove("changes");file.put("changeCount",changes.length()).put("changesDigest",ProjectSources.digest(new JSONObject().put("changes",changes)));}}steps.put(step);}
        JSONObject reference=new JSONObject().put("format","pocketworld.project-plan-reference.v1").put("planRef",ref).put("planDigest",plan.getString("planDigest")).put("projectId",plan.getString("projectId")).put("checkoutId",plan.getString("checkoutId")).put("sourceDigest",plan.getString("sourceDigest")).put("sourceFileCount",plan.getJSONArray("sourceManifest").length()).put("intent",plan.getString("intent")).put("runtimeId",plan.optString("runtimeId","termux")).put("steps",steps).put("available",plan.optBoolean("available")).put("warnings",plan.getJSONArray("warnings")).put("fullPlanStoredInOriginalTaskStore",true).put("sourceManifestOmitted",true).put("executionAuthorized",false).put("retention","referenced_until_original_task_removed;unreferenced_7_days;bounded_refusal_not_eviction");
        if("freeze_package".equals(plan.optString("intent")))reference.put("packageSelection",ProjectPackageDelivery.selectionSummary(plan));return reference;
    }
    public static JSONObject reviewStep(JSONObject step)throws Exception {
        JSONObject result=new JSONObject().put("id",step.getString("id")).put("label",step.getString("label")).put("localCommand",step.getString("localCommand")).put("effects",step.getJSONObject("effects"));JSONObject payload=new JSONObject(step.getJSONObject("payload").toString());
        if(ProjectManagedOutput.selection(payload.optJSONObject("artifactSelection"))){JSONObject selected=payload.getJSONObject("artifactSelection");payload.remove("artifactSelection");payload.put("generatedFile",ProjectManagedOutput.summary(selected));}
        if(Integer.valueOf(1).equals(payload.opt("gitProtocolVersion"))){JSONObject git=ProjectGit.semantic(payload);payload.remove("gitRequest");payload.put("git",git);}
        for(String key:new String[]{"beforeContent","afterContent"})if(payload.has(key)){Object text=payload.remove(key);if(text instanceof String)result.put(key+"Bytes",((String)text).getBytes(StandardCharsets.UTF_8).length);}
        JSONArray files=payload.optJSONArray("files");if(files!=null){JSONArray review=new JSONArray();for(int i=0;i<files.length();i++){JSONObject file=new JSONObject(files.getJSONObject(i).toString());file.remove("content");file.remove("beforeContent");review.put(file);}payload.put("files",review);}
        return result.put("request",payload).put("fullContentOmitted",true);
    }
}
