package dev.pocketengine.features.memory;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.CancellationSignal;
import dev.pocketengine.features.assistant.ConversationRepository;
import dev.pocketengine.features.assistant.OwnerSourceReferences;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Sticky native source dependencies of an existing run. No execution state, permission or body. */
public final class OwnerSourceRunContext {
    private OwnerSourceRunContext(){}
    public static final String UNAVAILABLE="context_provenance_unavailable";
    public static final String NEW_TASK_MESSAGE="这项旧任务的派生来源已撤回或无法完整核对，已停止后续模型与工具步骤。原任务审计和主人原话仍保留；请回到私聊的会话列表选择‘新对话’，重新说明新目标并明确选择仍允许使用的原文。不会自动复制旧派生内容、重放操作或沿用旧预算。";
    /** Native continuation barrier, not an execution failure or a retry recommendation. */
    public static final class ContinuationUnavailable extends Exception {
        public ContinuationUnavailable(Throwable cause){super(NEW_TASK_MESSAGE,cause);}
    }

    /** Exactly the original private owner request-projection domain, not authorization to read.
     * Channel/group/other-space runs never inherit this domain's unknown-source restriction. */
    public static boolean acceptsPrivateOwnerProjection(JSONObject task){
        if(task==null||!"assistant".equals(task.optString("kind"))||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId")))return false;
        String conversation=task.optString("conversationId");boolean blank=true;for(int at=0;at<conversation.length();){int point=conversation.codePointAt(at);if(!Character.isWhitespace(point)){blank=false;break;}at+=Character.charCount(point);}if(blank)return false;
        JSONObject origin=task.optJSONObject("origin");return origin==null||!java.util.Set.of("channel","group").contains(origin.optString("kind"));
    }
    private static final String PREFIX="run.owner_source_context.";
    private static final int MAX_BYTES=1_048_576,MAX_CARRIERS=100;
    private static String key(String run){if(!TaskStoreMemoryQuery.validTaskId(run))throw new IllegalArgumentException("原run标识无效");return PREFIX+run;}
    private static long integer(JSONObject row,String name)throws Exception{Object v=row.opt(name);if(!(v instanceof Integer||v instanceof Long)||((Number)v).longValue()<1)throw new IllegalArgumentException("原run来源整数无效");return ((Number)v).longValue();}
    private static JSONArray carriers(JSONArray supplied)throws Exception{
        if(supplied==null||supplied.length()>MAX_CARRIERS)throw new IllegalArgumentException("原run来源记录超过有界范围");JSONArray result=new JSONArray();
        for(int i=0;i<supplied.length();i++){
            JSONObject row=supplied.getJSONObject(i);String message=row.getString("ownerMessageId");if(!"user".equals(row.optString("role"))||!TaskStoreMemoryQuery.validTaskId(message))throw new SecurityException("原run来源不是native主人消息");
            JSONArray refs=OwnerSourceReferences.checked(row.getJSONArray("ownerSourceRefs"));if(refs.length()==0)continue;
            result.put(new JSONObject().put("role","user").put("ownerMessageId",message).put("ownerMessageRevision",integer(row,"ownerMessageRevision")).put("ownerSourceRefs",refs));
        }return result;
    }
    private static JSONObject loaded(SQLiteDatabase db,String task,String run,ConversationHistory.SourceBytes bytes,CancellationSignal signal)throws Exception{
        long limit=bytes==null?MAX_BYTES:Math.min(MAX_BYTES,bytes.remaining());long size;
        try(Cursor row=db.rawQuery("SELECT length(CAST(value AS BLOB)) FROM meta WHERE key=? LIMIT 1",new String[]{key(run)},signal)){
            if(!row.moveToFirst()||row.isNull(0))throw new TaskStoreMemoryReader.RawMessageUnavailable(UNAVAILABLE);size=row.getLong(0);if(size<0||size>limit)throw new TaskStoreMemoryReader.RawMessageUnavailable(UNAVAILABLE);
        }
        JSONObject fact;try(Cursor row=db.rawQuery("SELECT value FROM meta WHERE key=? AND length(CAST(value AS BLOB))<=CAST(? AS INTEGER) LIMIT 1",new String[]{key(run),Long.toString(limit)},signal)){
            if(!row.moveToFirst())throw new TaskStoreMemoryReader.RawMessageUnavailable(UNAVAILABLE);String text=row.getString(0);long actual=text.getBytes(StandardCharsets.UTF_8).length;if(actual>limit)throw new TaskStoreMemoryReader.RawMessageUnavailable(UNAVAILABLE);if(bytes!=null)bytes.charge(actual);fact=new JSONObject(text);
        }
        if(fact.optInt("format")!=1||!task.equals(fact.optString("taskId"))||!run.equals(fact.optString("runId"))||!(fact.opt("trackingComplete") instanceof Boolean)||!"prepared_before_model_dispatch".equals(fact.optString("exposureStage")))throw new TaskStoreMemoryReader.RawMessageUnavailable(UNAVAILABLE);
        long epoch=integer(fact,"epoch");try(Cursor row=db.rawQuery("SELECT 1 FROM runs WHERE task_id=? AND run_id=? AND epoch=? LIMIT 1",new String[]{task,run,Long.toString(epoch)},signal)){if(!row.moveToFirst())throw new TaskStoreMemoryReader.RawMessageUnavailable(UNAVAILABLE);}
        for(String flag:new String[]{"outputTrackingComplete","nativeOutputReturned","outputSourcesWithdrawn","formalMemoryTrackingComplete"})if(fact.has(flag)&&!(fact.opt(flag) instanceof Boolean))throw new TaskStoreMemoryReader.RawMessageUnavailable(UNAVAILABLE);
        fact.put("carriers",carriers(fact.getJSONArray("carriers")));return fact;
    }
    // Small body-free sibling of this same run's original meta facts. Fixed values let marking
    // avoid re-materializing a potentially 1MiB source map inside a bounded raw read.
    private static final String RAW_EMPTY="{\"trackingComplete\":true,\"exposed\":false}";
    private static final String RAW_YES="{\"trackingComplete\":true,\"exposed\":true}";
    private static final String RAW_UNKNOWN="{\"trackingComplete\":false,\"exposed\":null}";
    private static final String RAW_UNKNOWN_YES="{\"trackingComplete\":false,\"exposed\":true}";
    private static String rawExposureKey(String run){if(!TaskStoreMemoryQuery.validTaskId(run))throw new SecurityException("原run身份无效");return "run.raw_history_exposure."+run;}
    private static String rawExposure(SQLiteDatabase db,String task,String run)throws Exception{
        try(Cursor row=db.rawQuery("SELECT m.value FROM meta m JOIN runs r ON r.run_id=? AND r.task_id=? WHERE m.key=? AND length(CAST(m.value AS BLOB))<=128 LIMIT 1",new String[]{run,task,rawExposureKey(run)})){
            if(!row.moveToFirst())return RAW_UNKNOWN;String value=row.getString(0);
            return RAW_EMPTY.equals(value)||RAW_YES.equals(value)||RAW_UNKNOWN_YES.equals(value)?value:RAW_UNKNOWN;
        }
    }
    private static void initializeRawExposure(SQLiteDatabase db,String task,String run,String previousRun)throws Exception{
        String value=RAW_UNKNOWN;
        if(previousRun!=null&&!previousRun.isEmpty())value=rawExposure(db,task,previousRun);
        else try(Cursor prior=db.rawQuery("SELECT 1 FROM runs WHERE task_id=? AND run_id<>? LIMIT 1",new String[]{task,run})){if(!prior.moveToFirst())value=RAW_EMPTY;}
        ContentValues row=new ContentValues();row.put("key",rawExposureKey(run));row.put("value",value);db.insertOrThrow("meta",null,row);
    }
    /** Prepared before returning any raw/history text (or an H4 original-message session), not a
     * claim of model consumption. Missing/unknown history can become exposed, never known-empty. */
    public static void rawEvidencePrepared(SQLiteDatabase db,String task,String run)throws Exception{
        try(Cursor nativeRun=db.rawQuery("SELECT 1 FROM runs WHERE task_id=? AND run_id=? LIMIT 1",new String[]{task,run})){if(!nativeRun.moveToFirst())throw new SecurityException("原run已不存在");}
        ContentValues missing=new ContentValues();missing.put("key",rawExposureKey(run));missing.put("value",RAW_UNKNOWN_YES);db.insertWithOnConflict("meta",null,missing,SQLiteDatabase.CONFLICT_IGNORE);
        try(android.database.sqlite.SQLiteStatement update=db.compileStatement("UPDATE meta SET value=CASE WHEN value=? OR value=? THEN ? ELSE ? END WHERE key=?")){
            update.bindString(1,RAW_EMPTY);update.bindString(2,RAW_YES);update.bindString(3,RAW_YES);update.bindString(4,RAW_UNKNOWN_YES);update.bindString(5,rawExposureKey(run));if(update.executeUpdateDelete()!=1)throw new IllegalStateException("原run曝露准备记录未提交，未交付正文");
        }
    }
    /** Pure native admission only; neither a missing marker nor an empty live reader is proof. */
    public static boolean mayRetireUnexposedReader(SQLiteDatabase db,ToolOutputStore.Sources sources,String task,String run)throws Exception{
        if(!RAW_EMPTY.equals(rawExposure(db,task,run)))return false;JSONObject fact=loaded(db,task,run,null,null);
        if(!fact.getBoolean("trackingComplete")||fact.getJSONArray("carriers").length()!=0||!fact.optBoolean("formalMemoryTrackingComplete")||formalDependencies(fact.optJSONArray("formalMemoryDependencies")).length()!=0)return false;
        verifiedOutputDependencies(db,sources,task,run,null,null);return true;
    }
    private static void write(SQLiteDatabase db,JSONObject fact)throws Exception{
        String text=fact.toString();if(text.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new IllegalStateException("原run来源事实超出1MiB，未宣称依赖完整");
        ContentValues value=new ContentValues();value.put("key",key(fact.getString("runId")));value.put("value",text);if(db.insertWithOnConflict("meta",null,value,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IllegalStateException("原run来源事实未提交，尚未发送原文");
    }
    /** Called in the original run-creation transaction, before any request can be dispatched. */
    public static void initialize(SQLiteDatabase db,String task,String run,int epoch,String previousRun,boolean ownerSourceCapable)throws Exception{
        JSONArray inherited=new JSONArray();boolean complete=true;JSONObject prior=null;
        if(previousRun!=null&&!previousRun.isEmpty())try{prior=loaded(db,task,previousRun,null,null);}catch(Exception unavailable){if(ownerSourceCapable)complete=false;}
        if(ownerSourceCapable&&prior!=null){inherited=prior.getJSONArray("carriers");complete=prior.getBoolean("trackingComplete");}
        JSONObject fact=new JSONObject().put("format",1).put("taskId",task).put("runId",run).put("epoch",epoch).put("trackingComplete",complete).put("exposureStage","prepared_before_model_dispatch").put("carriers",inherited);
        // These are source dependencies only, never native execution states or consumption claims.
        boolean fresh=previousRun==null||previousRun.isEmpty();boolean hadOutput=!fresh&&ToolOutputStore.hasCapturedOutput(db,task,previousRun);
        fact.put("outputAttachmentSources",prior==null?new JSONArray():outputDependencies(prior))
            .put("outputPendingSources",prior==null?new JSONArray():pending(prior))
            .put("outputTrackingComplete",fresh||prior!=null&&prior.optBoolean("outputTrackingComplete",!hadOutput))
            .put("outputSourcesWithdrawn",prior!=null&&prior.optBoolean("outputSourcesWithdrawn"))
            .put("nativeOutputReturned",hadOutput||prior!=null&&prior.optBoolean("nativeOutputReturned"))
            .put("formalMemoryDependencies",prior==null?new JSONArray():formalDependencies(prior.optJSONArray("formalMemoryDependencies")))
            .put("formalMemoryTrackingComplete",fresh||prior!=null&&prior.optBoolean("formalMemoryTrackingComplete"));
        // These are exact final-request parts of this run. A previous summary, imported
        // checkpoint or read of a different task never backfills full user-message proof.
        fact.put("nativeUserParts",new JSONArray()).put("nativeUserTrackingComplete",fresh)
            .put("nativeUserExposureStage","final_main_request_prepared").put("nativeUserCollectionCoverage","prepared_parts_only");
        write(db,fact);initializeRawExposure(db,task,run,previousRun);
    }
    /** The original host calls after preparing current source parts, before actual model dispatch. */
    public static void prepared(SQLiteDatabase db,ConversationRepository originals,String task,String run,String conversation,JSONArray supplied)throws Exception{
        JSONArray accepted=carriers(supplied);try(var check=originals.inspectOwnerSources()){
            for(int i=0;i<accepted.length();i++){JSONObject row=accepted.getJSONObject(i);check.requireCarrier(row,conversation);JSONArray refs=row.getJSONArray("ownerSourceRefs");for(int j=0;j<refs.length();j++)check.requireSource(refs.getJSONObject(j));}
        }
        merge(db,loaded(db,task,run,null,null),accepted);
    }
    /** Same original TaskStore transaction as carrier preparation. Body-free facts are
     * captured only by the final physical MAIN admission, never by model-nominated IDs. */
    public static void nativeUsersPrepared(SQLiteDatabase db,ConversationRepository originals,String task,String run,String conversation,JSONArray supplied)throws Exception {
        if(supplied==null||supplied.length()>MAX_CARRIERS)throw new IllegalArgumentException("原请求主人来源超过100条");
        JSONObject fact=loaded(db,task,run,null,null);JSONArray old=fact.optJSONArray("nativeUserParts");if(old==null)old=new JSONArray();if(old.length()>MAX_CARRIERS)throw new SecurityException("原主人来源累计事实不可读");
        LinkedHashMap<String,JSONObject> union=new LinkedHashMap<>();for(int i=0;i<old.length();i++){JSONObject part=old.getJSONObject(i);union.put(nativePartKey(part),part);}
        try(var check=originals.inspectOwnerSources()){
            for(int i=0;i<supplied.length();i++){JSONObject part=new JSONObject(supplied.getJSONObject(i).toString());check.requireNativeUserPart(part,conversation);String key=nativePartKey(part);JSONObject previous=union.get(key);
                if(previous!=null&&!TaskMemoryTrace.rawFingerprint(previous).equals(TaskMemoryTrace.rawFingerprint(part)))throw new SecurityException("同一原请求来源范围不能改写正文指纹");union.put(key,part);}
        }
        if(union.size()>MAX_CARRIERS)throw new IllegalStateException("原主人来源累计达到100条上限，未截断当作完整");JSONArray parts=new JSONArray();for(JSONObject part:union.values())parts.put(part);
        fact.put("nativeUserParts",parts).put("nativeUserTrackingComplete",fact.optBoolean("nativeUserTrackingComplete",false))
            .put("nativeUserExposureStage","final_main_request_prepared").put("nativeUserCollectionCoverage","prepared_parts_only");write(db,fact);
    }
    private static String nativePartKey(JSONObject part)throws Exception {
        SourceRef ref=SourceRef.fromJson(part.getJSONObject("sourceRef"));return new JSONArray().put(part.getString("generation")).put(ref.sourceId).put(ref.revision).put(part.getString("coverage")).put(part.get("offset")).put(part.get("chars")).toString();
    }
    /** Exact selected source identities, not a complete-history assertion. Partial, absent,
     * legacy, retired, edited or summary-only evidence cannot become a full-message source. */
    public static JSONArray preparedUserRefs(SQLiteDatabase db,ConversationRepository originals,String task,String run,String conversation,JSONArray selected)throws Exception {
        if(selected==null||selected.length()<1||selected.length()>20)throw new IllegalArgumentException("请选择1到20条已实际准备的主人消息");JSONObject fact=loaded(db,task,run,null,null);
        if(!"final_main_request_prepared".equals(fact.optString("nativeUserExposureStage"))||!"prepared_parts_only".equals(fact.optString("nativeUserCollectionCoverage")))throw new SecurityException("原run缺少最终请求来源证明，不能从会话成员身份补全");
        JSONArray parts=fact.optJSONArray("nativeUserParts");if(parts==null||parts.length()>MAX_CARRIERS)throw new SecurityException("原run主人来源证明不可读");JSONArray result=new JSONArray();java.util.Set<String> seen=new java.util.HashSet<>();
        try(var check=originals.inspectOwnerSources()){
            for(int i=0;i<selected.length();i++){
                JSONObject selection=selected.getJSONObject(i);if(selection.length()!=2||!(selection.opt("sourceId") instanceof String)||!selection.has("revision"))throw new SecurityException("来源选择只允许sourceId和revision");String id=selection.getString("sourceId");long revision=integer(selection,"revision");if(!seen.add(id+":"+revision))throw new IllegalArgumentException("来源选择重复");JSONObject matched=null;
                for(int j=0;j<parts.length();j++){JSONObject part=parts.getJSONObject(j);SourceRef ref=SourceRef.fromJson(part.getJSONObject("sourceRef"));if("full".equals(part.optString("coverage"))&&id.equals(ref.sourceId)&&revision==ref.revision&&conversation.equals(part.optString("sourceConversationId"))){matched=part;break;}}
                if(matched==null)throw new SecurityException("所选原主人消息没有最终请求全文准备证明；范围、摘要或未知不能补作全文");check.requireNativeUserPart(matched,conversation);result.put(new JSONObject(matched.getJSONObject("sourceRef").toString()));
            }
        }return result;
    }
    /** Body-free discovery over this exact existing proof set; absence is unknown, not
     * evidence that the source did not occur elsewhere in the conversation. */
    public static JSONArray preparedUserSources(SQLiteDatabase db,ConversationRepository originals,String task,String run,String conversation)throws Exception {
        JSONObject fact=loaded(db,task,run,null,null);JSONArray result=new JSONArray(),parts=fact.optJSONArray("nativeUserParts");
        if(!"final_main_request_prepared".equals(fact.optString("nativeUserExposureStage"))||parts==null)return result;if(parts.length()>MAX_CARRIERS)throw new SecurityException("原run主人来源证明不可读");
        java.util.Set<String> seen=new java.util.HashSet<>();try(var check=originals.inspectOwnerSources()){
            for(int i=0;i<parts.length();i++){JSONObject part=parts.getJSONObject(i);if(!"full".equals(part.optString("coverage"))||!conversation.equals(part.optString("sourceConversationId")))continue;
                try{check.requireNativeUserPart(part,conversation);}catch(ConversationRepository.SourceUnavailable unavailable){continue;}
                SourceRef ref=SourceRef.fromJson(part.getJSONObject("sourceRef"));if(seen.add(ref.sourceId+":"+ref.revision))result.put(new JSONObject().put("sourceRef",ref.toJson()).put("coverage","full").put("exposureStage","final_main_request_prepared").put("modelConsumptionConfirmed",false));
            }
        }return result;
    }
    /** Native history body preparation propagates the same original dependencies to its caller.
     * No new source permission or model-consumption receipt is created. Both run identities came
     * from the original bound query, after same-conversation ACL and currentness checks. */
    static void inheritPrepared(SQLiteDatabase db,String callerTask,String callerRun,String sourceTask,String sourceRun,ConversationHistory.SourceBytes bytes,CancellationSignal signal)throws Exception{
        if(callerTask.equals(sourceTask)&&callerRun.equals(sourceRun))return;
        JSONObject source=loaded(db,sourceTask,sourceRun,bytes,signal);if(!source.getBoolean("trackingComplete"))throw new TaskStoreMemoryReader.RawMessageUnavailable(UNAVAILABLE);
        JSONArray selected=source.getJSONArray("carriers");JSONObject destination=loaded(db,callerTask,callerRun,bytes,signal);
        // Even a source without an ownerSourceRefs carrier can carry native attachment/formal
        // memory dependencies. Never infer absence from its latest compacted checkpoint.
        mergeOutputs(destination,source);write(db,destination);merge(db,destination,selected);
    }
    private static void merge(SQLiteDatabase db,JSONObject fact,JSONArray accepted)throws Exception{
        LinkedHashMap<String,JSONObject> union=new LinkedHashMap<>();
        for(JSONArray rows:new JSONArray[]{fact.getJSONArray("carriers"),accepted})for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);String id=row.getString("ownerMessageId")+":"+row.getLong("ownerMessageRevision");JSONObject old=union.get(id);
            if(old!=null&&!TaskMemoryTrace.rawFingerprint(old).equals(TaskMemoryTrace.rawFingerprint(row)))throw new SecurityException("相同native消息修订不能改写原选择");union.put(id,row);
        }
        if(union.size()>MAX_CARRIERS){fact.put("trackingComplete",false);write(db,fact);throw new IllegalStateException("原run来源跟踪达到100条上限，尚未发送本轮原文");}
        JSONArray next=new JSONArray();for(JSONObject row:union.values())next.put(row);JSONObject candidate=new JSONObject(fact.toString()).put("carriers",next);
        if(candidate.toString().getBytes(StandardCharsets.UTF_8).length>MAX_BYTES){fact.put("trackingComplete",false);write(db,fact);throw new IllegalStateException("原run来源事实达到字节上限，尚未发送本轮原文");}write(db,candidate);
    }
    public static void require(SQLiteDatabase db,ConversationRepository originals,String task,String run,String conversation,ConversationHistory.SourceBytes bytes,CancellationSignal signal)throws Exception{
        try{
            JSONObject fact=loaded(db,task,run,bytes,signal);if(!fact.getBoolean("trackingComplete"))throw new TaskStoreMemoryReader.RawMessageUnavailable(UNAVAILABLE);
            JSONArray selected=fact.getJSONArray("carriers");if(selected.length()==0)return;if(originals==null||conversation==null||conversation.isEmpty())throw new TaskStoreMemoryReader.RawMessageUnavailable(UNAVAILABLE);
            try(var check=originals.inspectOwnerSources(bytes)){
                for(int i=0;i<selected.length();i++){JSONObject row=selected.getJSONObject(i);check.requireCarrier(row,conversation);JSONArray refs=row.getJSONArray("ownerSourceRefs");for(int j=0;j<refs.length();j++)check.requireSource(refs.getJSONObject(j));}
            }
        }catch(Exception unavailable){if(unavailable instanceof TaskStoreMemoryReader.RawMessageUnavailable&&UNAVAILABLE.equals(unavailable.getMessage()))throw unavailable;var refused=new TaskStoreMemoryReader.RawMessageUnavailable(UNAVAILABLE);refused.initCause(unavailable);throw refused;}
    }
    private static JSONArray outputDependencies(JSONObject fact)throws Exception{
        JSONArray rows=fact.optJSONArray("outputAttachmentSources");return ToolOutputStore.checkedDependencies(rows==null?new JSONArray():rows);
    }
    private static JSONArray pending(JSONObject fact)throws Exception{
        JSONArray rows=fact.optJSONArray("outputPendingSources");if(rows==null)return new JSONArray();if(rows.length()>ToolOutputStore.MAX_DEPENDENCIES)throw new IllegalStateException("原输出待核来源超过64条");JSONArray result=new JSONArray();java.util.Set<String> seen=new java.util.HashSet<>();
        for(int i=0;i<rows.length();i++){Object id=rows.get(i);if(!(id instanceof String)||!TaskStoreMemoryQuery.validTaskId((String)id)||!seen.add((String)id))throw new SecurityException("原输出待核来源不是native claim");result.put(id);}return result;
    }
    private static JSONArray formalDependencies(JSONArray supplied)throws Exception{
        if(supplied==null)return new JSONArray();if(supplied.length()>64)throw new IllegalStateException("原正式记忆prepare版本超过64条，未宣称完整");JSONArray result=new JSONArray();java.util.Set<String> seen=new java.util.HashSet<>();
        for(int i=0;i<supplied.length();i++){JSONObject row=supplied.getJSONObject(i);String id=row.getString("id");long revision=integer(row,"revision");if(row.length()!=2||!TaskStoreMemoryQuery.validTaskId(id)||!seen.add(id+":"+revision))throw new SecurityException("原正式记忆版本不完整");result.put(new JSONObject().put("id",id).put("revision",revision));}return result;
    }
    private static JSONArray union(JSONArray first,JSONArray second,int limit,String identity)throws Exception{
        LinkedHashMap<String,JSONObject> rows=new LinkedHashMap<>();for(JSONArray source:new JSONArray[]{first,second})for(int i=0;i<source.length();i++){JSONObject row=source.getJSONObject(i);String key=identity.equals("formal")?row.getString("id")+":"+row.getLong("revision"):row.getString("id")+":"+row.getString("sha256")+":"+row.get("generation");rows.put(key,row);}
        if(rows.size()>limit)throw new IllegalStateException("原run依赖超过有界上限，未截断当完整");JSONArray result=new JSONArray();for(JSONObject row:rows.values())result.put(row);return result;
    }
    private static void mergeOutputs(JSONObject destination,JSONObject source)throws Exception{
        destination.put("outputAttachmentSources",union(outputDependencies(destination),outputDependencies(source),64,"attachment"))
            .put("outputTrackingComplete",destination.optBoolean("outputTrackingComplete",!destination.optBoolean("nativeOutputReturned"))&&source.optBoolean("outputTrackingComplete",!source.optBoolean("nativeOutputReturned")))
            .put("outputSourcesWithdrawn",destination.optBoolean("outputSourcesWithdrawn")||source.optBoolean("outputSourcesWithdrawn"))
            .put("nativeOutputReturned",destination.optBoolean("nativeOutputReturned")||source.optBoolean("nativeOutputReturned"))
            .put("formalMemoryDependencies",union(formalDependencies(destination.optJSONArray("formalMemoryDependencies")),formalDependencies(source.optJSONArray("formalMemoryDependencies")),64,"formal"))
            .put("formalMemoryTrackingComplete",destination.optBoolean("formalMemoryTrackingComplete")&&source.optBoolean("formalMemoryTrackingComplete"));
        JSONArray waiting=pending(destination),other=pending(source);java.util.Set<String> ids=new java.util.LinkedHashSet<>();for(JSONArray a:new JSONArray[]{waiting,other})for(int i=0;i<a.length();i++)ids.add(a.getString(i));if(ids.size()>64)throw new IllegalStateException("原run待核来源超过64条");JSONArray combined=new JSONArray();for(String id:ids)combined.put(id);destination.put("outputPendingSources",combined);
    }
    /** Reserve before entering this one actual native attachment read. A timeout cannot clear it. */
    public static void reserveOutput(SQLiteDatabase db,String task,String run,String nativeClaim)throws Exception{
        JSONObject fact=loaded(db,task,run,null,null);JSONArray waiting=pending(fact);if(waiting.length()>=64)throw new IllegalStateException("原输出待核来源达到64条");for(int i=0;i<waiting.length();i++)if(nativeClaim.equals(waiting.getString(i)))throw new SecurityException("原输出claim重复");if(!TaskStoreMemoryQuery.validTaskId(nativeClaim))throw new SecurityException("原输出claim不是native身份");waiting.put(nativeClaim);fact.put("outputPendingSources",waiting);write(db,fact);
    }
    private static void removePending(JSONObject fact,String nativeClaim,boolean required)throws Exception{
        JSONArray waiting=pending(fact),next=new JSONArray();boolean found=false;for(int i=0;i<waiting.length();i++){String id=waiting.getString(i);if(id.equals(nativeClaim))found=true;else next.put(id);}if(required&&!found)throw new SecurityException("原附件读取claim已结束或不存在");fact.put("outputPendingSources",next);
    }
    /** Only the original caller's definite pre-native non-dispatch can release this reservation. */
    public static void outputNotDispatched(SQLiteDatabase db,String task,String run,String claim)throws Exception{JSONObject fact=loaded(db,task,run,null,null);removePending(fact,claim,true);write(db,fact);}
    /** Reliable source dependency is durable BEFORE body encoding/archiving. Body failure does not
     * undo an already proved dependency and never rewrites a known native effect as unknown. */
    public static void outputReturned(SQLiteDatabase db,String task,String run,String claim,JSONArray dependencies,boolean reserved)throws Exception{
        JSONObject fact=loaded(db,task,run,null,null);JSONArray checked=ToolOutputStore.checkedDependencies(dependencies);if(reserved)removePending(fact,claim,true);
        fact.put("outputAttachmentSources",union(outputDependencies(fact),checked,64,"attachment")).put("nativeOutputReturned",true);write(db,fact);
    }
    /** Native PREPARE dependencies, not evidence that a model used these records. */
    public static void formalPrepared(SQLiteDatabase db,String task,String run,JSONArray selected)throws Exception{
        JSONArray exact=new JSONArray();for(int i=0;i<selected.length();i++){JSONObject row=selected.getJSONObject(i);exact.put(new JSONObject().put("id",row.getString("id")).put("revision",integer(row,"revision")));}exact=formalDependencies(exact);JSONObject fact=loaded(db,task,run,null,null);
        fact.put("formalMemoryDependencies",union(formalDependencies(fact.optJSONArray("formalMemoryDependencies")),exact,64,"formal"));write(db,fact);
    }
    /** One original bounded metadata read; Book implementation is a non-locking eligibility check. */
    public static void requireOutputs(SQLiteDatabase db,ToolOutputStore.Sources sources,String task,String run,ConversationHistory.SourceBytes bytes,CancellationSignal signal)throws Exception{verifiedOutputDependencies(db,sources,task,run,bytes,signal);}
    static JSONArray verifiedOutputDependencies(SQLiteDatabase db,ToolOutputStore.Sources sources,String task,String run,ConversationHistory.SourceBytes bytes,CancellationSignal signal)throws Exception{
        JSONObject fact=loaded(db,task,run,bytes,signal);boolean output=fact.has("nativeOutputReturned")?fact.getBoolean("nativeOutputReturned"):ToolOutputStore.hasCapturedOutput(db,task,run);
        if(fact.optBoolean("outputSourcesWithdrawn")||pending(fact).length()>0||!fact.optBoolean("outputTrackingComplete",!output))throw new TaskStoreMemoryReader.RawMessageUnavailable("native_output_provenance_unavailable");JSONArray dependencies=outputDependencies(fact);ToolOutputStore.requireDependencies(dependencies,sources);return dependencies;
    }
    /** Does not return bodies or grant permission. Used for explicit cold native byte revalidation. */
    public static JSONArray outputRunDependencies(SQLiteDatabase db,String task,String run)throws Exception{return outputRunDependencies(db,task,run,null,null);}
    static JSONArray outputRunDependencies(SQLiteDatabase db,String task,String run,ConversationHistory.SourceBytes bytes,CancellationSignal signal)throws Exception{JSONObject fact=loaded(db,task,run,bytes,signal);if(pending(fact).length()!=0||fact.optBoolean("outputSourcesWithdrawn")||!fact.optBoolean("outputTrackingComplete",!fact.optBoolean("nativeOutputReturned")))throw new TaskStoreMemoryReader.RawMessageUnavailable("native_output_provenance_unavailable");return outputDependencies(fact);}
    /** Forget invalidates only this run's actual prepared formal versions; known empty/unrelated
     * pure tool runs are untouched. Legacy unknown is restricted only when native output exists. */
    public static boolean withdrawFormalOutputs(SQLiteDatabase db,String task,String run,JSONArray removed)throws Exception{
        JSONObject fact;try{fact=loaded(db,task,run,null,null);}catch(TaskStoreMemoryReader.RawMessageUnavailable absent){return ToolOutputStore.hasCapturedOutput(db,task,run);}
        boolean retained=fact.has("nativeOutputReturned")?fact.getBoolean("nativeOutputReturned"):ToolOutputStore.hasCapturedOutput(db,task,run);boolean affected=false;JSONArray dependencies=formalDependencies(fact.optJSONArray("formalMemoryDependencies"));
        if(removed==null&&dependencies.length()>0)affected=true;
        for(int i=0;removed!=null&&i<removed.length()&&!affected;i++){JSONObject gone=removed.getJSONObject(i);String id=gone.getString("recordId");long revision=integer(gone,"revision");for(int j=0;j<dependencies.length();j++){JSONObject used=dependencies.getJSONObject(j);if(id.equals(used.getString("id"))&&revision==used.getLong("revision")){affected=true;break;}}}
        if(!fact.optBoolean("formalMemoryTrackingComplete")&&(retained||fact.optBoolean("nativeOutputReturned")))affected=true;
        if(affected){fact.put("outputSourcesWithdrawn",true);write(db,fact);}return affected;
    }
    /** Request-only fallback. Original owner instructions, effect records and evidence are never overwritten. */
    public static JSONArray retireDerived(JSONArray supplied)throws Exception{
        JSONArray result=new JSONArray(supplied.toString());TaskMemoryHistory.retire(result,null,false);for(int i=0;i<result.length();i++){
            JSONObject row=result.getJSONObject(i);String role=row.optString("role");
            if("assistant".equals(role)){row.put("content",TaskMemoryHistory.RETIRED).put("memoryContextRetired",true);row.remove("provider_data");row.remove("reasoning_content");JSONArray calls=row.optJSONArray("tool_calls");for(int j=0;calls!=null&&j<calls.length();j++)calls.getJSONObject(j).getJSONObject("function").put("arguments","{}");}
            else if("tool".equals(role)){row.remove("toolOutputCapture");row.put("content",new JSONObject().put("ok",false).put("code",UNAVAILABLE).put("message","这段派生上下文的原来源已失效或无法证明；原操作审计仍保留，请主人核对，不能据此重放操作。").toString());}
        }return result;
    }
}
