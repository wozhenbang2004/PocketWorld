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
            .put("formalMemoryTrackingComplete",fresh||prior!=null&&prior.optBoolean("formalMemoryTrackingComplete"))
            .put("formalMessageTrackingComplete",fresh||prior!=null&&prior.optBoolean("formalMessageTrackingComplete",formalDependencies(prior.optJSONArray("formalMemoryDependencies")).length()==0))
            .put("nativeAttachmentTrackingComplete",fresh||prior!=null&&prior.optBoolean("nativeAttachmentTrackingComplete"))
            .put("replyPreviewDependenciesUntracked",prior!=null&&prior.optBoolean("replyPreviewDependenciesUntracked"))
            .put("assistantMessageSources",prior==null?new JSONArray():assistantSources(prior.optJSONArray("assistantMessageSources")))
            .put("relatedResultSources",prior==null?new JSONArray():relatedSources(prior.optJSONArray("relatedResultSources")));
        // These are exact final-request parts of this run. A previous summary, imported
        // checkpoint or read of a different task never backfills full user-message proof.
        fact.put("nativeUserParts",new JSONArray()).put("nativeUserTrackingComplete",fresh)
            .put("nativeUserExposureStage","final_main_request_prepared").put("nativeUserCollectionCoverage","prepared_parts_only");
        write(db,fact);initializeRawExposure(db,task,run,previousRun);
    }
    /** The original host calls after preparing current source parts, before actual model dispatch. */
    public static void prepared(SQLiteDatabase db,ConversationRepository originals,String task,String run,String conversation,JSONArray supplied)throws Exception{
        JSONArray accepted=carriers(supplied);JSONObject fact=loaded(db,task,run,null,null);try(var check=originals.inspectOwnerSources()){
            for(int i=0;i<accepted.length();i++){JSONObject row=accepted.getJSONObject(i);check.requireCarrier(row,conversation);JSONArray refs=row.getJSONArray("ownerSourceRefs");for(int j=0;j<refs.length();j++)check.requireSource(refs.getJSONObject(j));}
            for(int i=0;i<supplied.length();i++){JSONObject row=supplied.getJSONObject(i);JSONArray refs=row.optJSONArray("ownerPreviewRefs");if(refs!=null&&refs.length()>0){check.requirePreviewCarrier(row,conversation);fact.put("replyPreviewDependenciesUntracked",true);}}
        }
        merge(db,fact,accepted);
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
        JSONArray attachments=outputDependencies(fact);
        for(int i=0;i<supplied.length();i++)attachments=union(attachments,originals.nativeMessageAttachmentSources(SourceRef.fromJson(supplied.getJSONObject(i).getJSONObject("sourceRef"))),ToolOutputStore.MAX_DEPENDENCIES,"attachment");
        fact.put("outputAttachmentSources",attachments).put("nativeUserParts",parts).put("nativeUserTrackingComplete",fact.optBoolean("nativeUserTrackingComplete",false))
            .put("nativeUserExposureStage","final_main_request_prepared").put("nativeUserCollectionCoverage","prepared_parts_only");write(db,fact);
    }
    /** The original host has already checked each routed input against its native route.
     * Foreign input becomes a dependency, never local owner-input consumption evidence. */
    public static void routedOwnerInputsPrepared(SQLiteDatabase db,ConversationRepository originals,String task,String run,String sourceConversation,JSONArray suppliedCarriers,JSONArray suppliedParts)throws Exception{
        if(!db.inTransaction()||originals==null||sourceConversation==null||sourceConversation.isEmpty())throw new SecurityException("跨话题来源须在原路由事务中保存");
        if(suppliedCarriers==null||suppliedParts==null||suppliedCarriers.length()>MAX_CARRIERS||suppliedParts.length()>MAX_CARRIERS)throw new SecurityException("跨话题来源超过有界范围");
        JSONObject fact=loaded(db,task,run,null,null);JSONArray pins=assistantSources(fact.optJSONArray("assistantMessageSources")),attachments=outputDependencies(fact);
        LinkedHashMap<String,SourceRef> ownerMessages=new LinkedHashMap<>();boolean untrackedPreview=fact.optBoolean("replyPreviewDependenciesUntracked");
        try(var check=originals.inspectOwnerSources()){
            for(int i=0;i<suppliedCarriers.length();i++){
                JSONObject row=suppliedCarriers.getJSONObject(i);if(!check.nativeOwnerInput(row,sourceConversation))throw new SecurityException("跨话题载体不是原主人消息");
                SourceRef own=new SourceRef("message",row.getString("ownerMessageId"),integer(row,"ownerMessageRevision"),"owner","private");ownerMessages.put(own.sourceId+":"+own.revision,own);
                JSONArray refs=OwnerSourceReferences.checked(row.optJSONArray("ownerSourceRefs")==null?new JSONArray():row.getJSONArray("ownerSourceRefs"));
                if(refs.length()>0){check.requireCarrier(row,sourceConversation);for(int j=0;j<refs.length();j++){
                    JSONObject selected=refs.getJSONObject(j);check.requireSource(selected);pins=mergeAssistantSources(pins,new JSONArray().put(originals.nativeMessageSource(SourceRef.fromJson(selected.getJSONObject("sourceRef")))));
                }}
                JSONArray previews=row.optJSONArray("ownerPreviewRefs");if(previews!=null&&previews.length()>0){check.requirePreviewCarrier(row,sourceConversation);untrackedPreview=true;}
            }
            for(int i=0;i<suppliedParts.length();i++){
                JSONObject part=suppliedParts.getJSONObject(i);check.requireNativeUserPart(part,sourceConversation);SourceRef ref=SourceRef.fromJson(part.getJSONObject("sourceRef"));ownerMessages.put(ref.sourceId+":"+ref.revision,ref);
            }
        }
        if(ownerMessages.size()>MAX_CARRIERS)throw new SecurityException("跨话题主人来源累计超过100条");
        for(SourceRef ref:ownerMessages.values()){
            pins=mergeAssistantSources(pins,new JSONArray().put(originals.nativeMessageSource(ref)));
            attachments=union(attachments,originals.nativeMessageAttachmentSources(ref),ToolOutputStore.MAX_DEPENDENCIES,"attachment");
            untrackedPreview|=originals.nativeMessageHasPreviewSources(ref);
        }
        fact.put("assistantMessageSources",pins).put("outputAttachmentSources",attachments).put("replyPreviewDependenciesUntracked",untrackedPreview);write(db,fact);
    }
    /** Native saved assistant history is pinned before projection. This is a source
     * dependency, not a new user instruction or evidence of semantic consumption. */
    public static void assistantsPrepared(SQLiteDatabase db,ConversationRepository originals,String task,String run,JSONArray supplied)throws Exception{
        JSONArray accepted=assistantSources(supplied);JSONObject fact=loaded(db,task,run,null,null);
        try(var check=originals.inspectOwnerSources()){for(int i=0;i<accepted.length();i++)check.requireNativeAssistantSource(accepted.getJSONObject(i));}
        fact.put("assistantMessageSources",mergeAssistantSources(assistantSources(fact.optJSONArray("assistantMessageSources")),accepted));write(db,fact);
    }
    private static JSONArray assistantSources(JSONArray supplied)throws Exception{
        if(supplied==null)return new JSONArray();if(supplied.length()>MAX_CARRIERS)throw new SecurityException("助手来源超过100条有界范围");
        JSONArray result=new JSONArray();java.util.Set<String> seen=new java.util.HashSet<>();
        for(int i=0;i<supplied.length();i++){
            JSONObject row=supplied.getJSONObject(i);SourceRef ref=SourceRef.fromJson(row.getJSONObject("sourceRef"));
            if(row.length()!=4||row.getJSONObject("sourceRef").length()!=5||!"message".equals(ref.kind)||!"owner".equals(ref.ownerId)||!"private".equals(ref.spaceId)
                    ||row.optString("generation").isEmpty()||row.optString("generation").length()>80||!TaskStoreMemoryQuery.validTaskId(row.optString("sourceConversationId"))||!row.optString("bodyFingerprint").matches("sha256:[a-f0-9]{64}")||!seen.add(ref.sourceId+":"+ref.revision))throw new SecurityException("助手来源不是准确原生消息身份");
            result.put(new JSONObject(row.toString()));
        }return result;
    }
    private static JSONArray mergeAssistantSources(JSONArray first,JSONArray second)throws Exception{
        LinkedHashMap<String,JSONObject> merged=new LinkedHashMap<>();for(JSONArray rows:new JSONArray[]{first,second})for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);SourceRef ref=SourceRef.fromJson(row.getJSONObject("sourceRef"));String key=ref.sourceId+":"+ref.revision;JSONObject previous=merged.get(key);
            if(previous!=null&&!TaskMemoryTrace.rawFingerprint(previous).equals(TaskMemoryTrace.rawFingerprint(row)))throw new SecurityException("同一助手来源版本不能改写");merged.put(key,row);
        }return assistantSources(new JSONArray(merged.values()));
    }
    /** Original accepted external results, not a scheduler, an imported task link or an
     * executor-success claim. A receipt change invalidates its previous derived text. */
    public static JSONObject nativeRelatedResultSource(SQLiteDatabase db,String childTaskId,String parentTaskId,String parentRunId)throws Exception{
        if(!db.inTransaction())throw new SecurityException("关联结果来源必须在原回执事务中冻结");
        return relatedSource(db,childTaskId,parentTaskId,parentRunId,relatedReadBudget(null),null,new LinkedHashMap<>());
    }
    /** Prepared in the same original transaction before any result body is delivered. */
    public static void relatedResultsPrepared(SQLiteDatabase db,String targetTask,String targetRun,JSONArray supplied)throws Exception{
        if(!db.inTransaction())throw new SecurityException("关联结果依赖必须在原执行事务中保存");
        JSONArray accepted=relatedSources(supplied);requireRelatedResultSources(db,accepted);
        JSONObject fact=loaded(db,targetTask,targetRun,null,null);
        fact.put("relatedResultSources",mergeRelatedSources(relatedSources(fact.optJSONArray("relatedResultSources")),accepted));write(db,fact);
    }
    /** Direct original SQLite only: safe under source resolution without a TaskStore,
     * coordinator, remote watcher or MemoryBook monitor callback. */
    public static void requireRelatedResultSources(SQLiteDatabase db,JSONArray supplied)throws Exception{
        requireRelatedResultSources(db,supplied,null,null);
    }
    /** A raw work publication has its own immutable result version. Do not attach every
     * later child version to the producer run, or upgrade it from copied message fields. */
    public static void requireOwnerWorkDeliverySources(SQLiteDatabase db,String task,String run,int epoch,String conversation,String generation,String messageId,String deliveryId,String resultDigest,String bodyFingerprint)throws Exception{
        if(!TaskStoreMemoryQuery.validTaskId(run)||!TaskStoreMemoryQuery.validTaskId(messageId)||!TaskStoreMemoryQuery.validTaskId(deliveryId)||epoch<1
                ||!resultDigest.matches("[a-f0-9]{64}")||!bodyFingerprint.matches("sha256:[a-f0-9]{64}"))throw new SecurityException("原工作发布来源身份无效");
        ConversationHistory.SourceBytes bytes=relatedReadBudget(null);JSONObject original=relatedTask(db,task,bytes,null,new LinkedHashMap<>()),work=original.optJSONObject("ownerWork");
        if(!dev.pocketengine.store.OwnerWorkTask.isOwnerWork(original)||!"assistant".equals(original.optString("kind"))||!"capabilities".equals(original.optString("executionMode"))
                ||original.optJSONObject("origin")!=null||original.has("parentTaskId")||!conversation.equals(original.optString("conversationId"))||work==null
                ||!task.equals(work.optString("workTaskId"))||!conversation.equals(work.optString("conversationId"))||!generation.equals(work.optString("generation")))throw new SecurityException("原工作发布不属于该来源");
        JSONArray entries=work.optJSONArray("deliveries");if(entries==null||entries.length()>dev.pocketengine.store.OwnerWorkResultProtocol.ORDINARY_RESULT_LIMIT+2)throw new SecurityException("原工作发布版本不可核对");JSONObject selected=null;
        for(int i=0;i<entries.length();i++){JSONObject entry=entries.getJSONObject(i);if(deliveryId.equals(entry.optString("deliveryId"))){if(selected!=null)throw new SecurityException("原工作发布版本不唯一");selected=entry;}}
        if(selected==null||!dev.pocketengine.store.OwnerWorkTask.DELIVERY_FORMAT.equals(selected.optString("format"))||!task.equals(selected.optString("taskId"))||!task.equals(selected.optString("workTaskId"))
                ||!run.equals(selected.optString("runId"))||epoch!=integer(selected,"epoch")||!conversation.equals(selected.optString("conversationId"))||!generation.equals(selected.optString("generation"))
                ||!"delivered".equals(selected.optString("deliveryState"))||!messageId.equals(selected.optString("messageId"))||!resultDigest.equals(selected.optString("resultDigest")))throw new SecurityException("原工作发布已变化或尚未完成保存");
        JSONObject frozen=new JSONObject(selected.toString());
        for(String field:new String[]{"resultDigest","deliveryState","receiptState","messageId","deliveredAt","butlerClaim","consumedByMessageId","consumedAt","unavailableReason"})frozen.remove(field);
        if(!("sha256:"+resultDigest).equals(TaskMemoryTrace.rawFingerprint(frozen)))throw new SecurityException("原工作冻结结果内容已变化");
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(selected.getString("content").getBytes(StandardCharsets.UTF_8));StringBuilder hex=new StringBuilder("sha256:");
        for(byte value:digest){int unsigned=value&255;hex.append("0123456789abcdef".charAt(unsigned>>>4)).append("0123456789abcdef".charAt(unsigned&15));}
        if(!bodyFingerprint.equals(hex.toString()))throw new SecurityException("原工作发布正文与冻结结果不一致");
        requireRelatedResultSources(db,selected.optJSONArray("relatedResultSources"),bytes,null);
    }
    private static JSONArray relatedSources(JSONArray supplied)throws Exception{
        JSONArray result=new JSONArray();if(supplied==null)return result;if(supplied.length()>32)throw new SecurityException("关联结果来源超过32项有界范围");
        java.util.Set<String> seen=new java.util.HashSet<>();
        for(int i=0;i<supplied.length();i++){
            JSONObject pin=supplied.getJSONObject(i);Object epoch=pin.opt("childEpoch");
            if(pin.length()!=7||!(epoch instanceof Integer||epoch instanceof Long)||((Number)epoch).longValue()<0||((Number)epoch).longValue()>Integer.MAX_VALUE
                    ||!java.util.Set.of("remote","teams").contains(pin.optString("feature"))||!pin.optString("sourceDigest").matches("sha256:[a-f0-9]{64}"))throw new SecurityException("关联结果缺少准确原生版本");
            for(String name:new String[]{"childTaskId","childRunId","parentTaskId","parentRunId"})if(!(pin.opt(name) instanceof String)||!TaskStoreMemoryQuery.validTaskId(pin.getString(name)))throw new SecurityException("关联结果身份无效");
            if(!seen.add(relatedIdentity(pin)))throw new SecurityException("关联结果来源重复");result.put(new JSONObject(pin.toString()));
        }return result;
    }
    private static String relatedIdentity(JSONObject pin)throws Exception{
        return new JSONArray().put(pin.getString("childTaskId")).put(pin.getString("childRunId")).put(pin.getLong("childEpoch"))
                .put(pin.getString("parentTaskId")).put(pin.getString("parentRunId")).put(pin.getString("feature")).toString();
    }
    private static JSONArray mergeRelatedSources(JSONArray first,JSONArray second)throws Exception{
        LinkedHashMap<String,JSONObject> merged=new LinkedHashMap<>();
        for(JSONArray rows:new JSONArray[]{first,second})for(int i=0;i<rows.length();i++){
            JSONObject pin=rows.getJSONObject(i);String identity=relatedIdentity(pin);JSONObject old=merged.get(identity);
            if(old!=null&&!old.getString("sourceDigest").equals(pin.getString("sourceDigest")))throw new SecurityException("原执行已依赖另一版关联结果，不能静默换版");merged.put(identity,pin);
        }return relatedSources(new JSONArray(merged.values()));
    }
    private static ConversationHistory.SourceBytes relatedReadBudget(ConversationHistory.SourceBytes outer){
        return new ConversationHistory.SourceBytes(){
            private long remaining=4L*MAX_BYTES;private final long deadline=System.nanoTime()+500_000_000L;
            public long remaining()throws Exception{if(System.nanoTime()>deadline)throw new TaskStoreMemoryReader.RawMessageUnavailable(UNAVAILABLE);return outer==null?remaining:Math.min(remaining,outer.remaining());}
            public void charge(long bytes)throws Exception{if(bytes<0||bytes>remaining())throw new TaskStoreMemoryReader.RawMessageUnavailable(UNAVAILABLE);remaining-=bytes;if(outer!=null)outer.charge(bytes);}
        };
    }
    private static JSONObject relatedTask(SQLiteDatabase db,String id,ConversationHistory.SourceBytes bytes,CancellationSignal signal,java.util.Map<String,JSONObject> cache)throws Exception{
        if(!TaskStoreMemoryQuery.validTaskId(id))throw new SecurityException("关联原任务标识无效");JSONObject cached=cache.get(id);if(cached!=null)return cached;
        long limit=Math.min(MAX_BYTES,bytes.remaining());JSONObject task;
        try(Cursor row=db.rawQuery("SELECT document FROM tasks WHERE task_id=? AND length(CAST(document AS BLOB))<=CAST(? AS INTEGER) LIMIT 1",new String[]{id,Long.toString(limit)},signal)){
            if(!row.moveToFirst())throw new TaskStoreMemoryReader.RawMessageUnavailable(UNAVAILABLE);String raw=row.getString(0);bytes.charge(raw.getBytes(StandardCharsets.UTF_8).length);task=new JSONObject(raw);
        }
        JSONObject origin=task.optJSONObject("origin");
        if(!id.equals(task.optString("id"))||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||origin!=null&&java.util.Set.of("channel","group").contains(origin.optString("kind")))throw new SecurityException("关联来源不是原私人任务");
        cache.put(id,task);return task;
    }
    private static JSONObject relatedSource(SQLiteDatabase db,String childId,String parentId,String parentRun,ConversationHistory.SourceBytes bytes,CancellationSignal signal,java.util.Map<String,JSONObject> cache)throws Exception{
        if(!TaskStoreMemoryQuery.validTaskId(parentRun)||childId.equals(parentId))throw new SecurityException("关联原父执行无效");
        JSONObject child=relatedTask(db,childId,bytes,signal,cache),parent=relatedTask(db,parentId,bytes,signal,cache);String feature=child.optString("feature"),run=child.optString("runId");Object epoch=child.opt("coordinatorEpoch");
        if(!java.util.Set.of("remote","teams").contains(feature)||!feature.equals(child.optString("kind"))||!TaskStoreMemoryQuery.validTaskId(run)
                ||!(epoch instanceof Integer||epoch instanceof Long)||((Number)epoch).longValue()<0||((Number)epoch).longValue()>Integer.MAX_VALUE
                ||!parentId.equals(child.optString("parentTaskId"))||!parentRun.equals(child.optString("parentRunId"))||!run.equals(child.optString("operationId")))throw new SecurityException("关联结果不是原准确子执行");
        JSONArray links=parent.optJSONArray("relatedTasks");if(links==null||links.length()>100)throw new SecurityException("原父任务关联不可核对");boolean linked=false;
        for(int i=0;i<links.length();i++){
            JSONObject link=links.getJSONObject(i);if(!childId.equals(link.optString("taskId")))continue;
            if(linked||!run.equals(link.optString("runId"))||!feature.equals(link.optString("feature",link.optString("kind"))))throw new SecurityException("原父子关联不一致");linked=true;
        }
        if(!linked)throw new SecurityException("原父任务没有此子执行");
        // External coordinator epoch zero is real; it is never promoted into a HostRun.
        if(!("teams".equals(parent.optString("feature"))&&"teams".equals(parent.optString("kind"))&&parentRun.equals(parent.optString("runId"))&&parentRun.equals(parent.optString("operationId"))))
            try(Cursor row=db.rawQuery("SELECT 1 FROM runs WHERE task_id=? AND run_id=? LIMIT 1",new String[]{parentId,parentRun},signal)){if(!row.moveToFirst())throw new SecurityException("原父执行记录已不存在");}
        JSONObject evidence=child.optJSONObject("evidence");String status=child.optString("executionStatus");
        if(evidence==null||status.isEmpty()||!status.equals(evidence.optString("executorStatus")))throw new SecurityException("关联结果没有原执行器回执");
        JSONObject facts=new JSONObject();for(String name:new String[]{"id","runId","coordinatorEpoch","kind","feature","actorId","spaceId","conversationId","origin","parentTaskId","parentRunId","parentOperationId","operationId","externalId","externalOperationId","externalRefs","status","executionStatus","summary","evidence","cancelRequested","memberId","generation","round","teamRunId"})if(child.has(name))facts.put(name,child.get(name));
        if("remote".equals(feature)){
            JSONObject receipt;String state,operationRun;long limit=Math.min(MAX_BYTES,bytes.remaining());
            try(Cursor row=db.rawQuery("SELECT run_id,state,receipt FROM operations WHERE operation_id=? AND task_id=? AND tool='remote_job' AND receipt IS NOT NULL AND length(CAST(receipt AS BLOB))<=CAST(? AS INTEGER) LIMIT 1",new String[]{run,childId,Long.toString(limit)},signal)){
                if(!row.moveToFirst())throw new SecurityException("原电脑回执记录已不存在");operationRun=row.getString(0);state=row.getString(1);String raw=row.getString(2);bytes.charge(raw.getBytes(StandardCharsets.UTF_8).length);receipt=new JSONObject(raw);
            }
            if(!run.equals(operationRun)||!run.equals(receipt.optString("operationId"))||!status.equals(receipt.optString("status"))||!state.matches("remote_job_active|remote_job_receipt|remote_job_unknown"))throw new SecurityException("电脑结果与原操作回执不同版");
            try(Cursor row=db.rawQuery("SELECT 1 FROM runs WHERE task_id=? AND run_id=? AND epoch=? LIMIT 1",new String[]{childId,run,Long.toString(((Number)epoch).longValue())},signal)){if(!row.moveToFirst())throw new SecurityException("原电脑执行代次已不存在");}
            for(String name:new String[]{"id","operationId","profileId","serviceBase","environmentId","worker"})if(evidence.has(name)&&!evidence.get(name).equals(receipt.opt(name)))throw new SecurityException("电脑回执的准确来源已变化");
            receipt.remove("updatedAt");receipt.remove("createdAt");facts.put("nativeReceiptState",state).put("nativeReceipt",receipt);
        }else{
            if(!run.equals(evidence.optString("id"))||!run.equals(child.optString("externalId"))||integer(evidence,"generation")>Integer.MAX_VALUE)throw new SecurityException("团队回执不属于原团队代次");integer(evidence,"revision");
        }
        bytes.remaining();return new JSONObject().put("childTaskId",childId).put("childRunId",run).put("childEpoch",((Number)epoch).longValue())
                .put("parentTaskId",parentId).put("parentRunId",parentRun).put("feature",feature).put("sourceDigest",TaskMemoryTrace.rawFingerprint(facts));
    }
    private static void requireRelatedResultSources(SQLiteDatabase db,JSONArray supplied,ConversationHistory.SourceBytes outer,CancellationSignal signal)throws Exception{
        JSONArray pins=relatedSources(supplied);ConversationHistory.SourceBytes bytes=relatedReadBudget(outer);java.util.Map<String,JSONObject> cache=new LinkedHashMap<>();
        for(int i=0;i<pins.length();i++){
            JSONObject pin=pins.getJSONObject(i),current=relatedSource(db,pin.getString("childTaskId"),pin.getString("parentTaskId"),pin.getString("parentRunId"),bytes,signal,cache);
            if(!TaskMemoryTrace.rawFingerprint(pin).equals(TaskMemoryTrace.rawFingerprint(current)))throw new SecurityException("原关联结果已变化或失效");
        }
    }
    /** Direct SQLite/attachment metadata only; safe under a MemoryBook source resolver.
     * All original source versions, native run identity and output dependencies survive. */
    public static void requireReplyDependencies(SQLiteDatabase db,ConversationRepository originals,ToolOutputStore.Sources outputs,String task,String run,int epoch,String conversation)throws Exception{
        JSONObject fact=loaded(db,task,run,null,null);if(epoch!=fact.getInt("epoch")||!fact.optBoolean("nativeUserTrackingComplete")||!fact.optBoolean("formalMemoryTrackingComplete")
                ||fact.optBoolean("replyPreviewDependenciesUntracked")||!fact.optBoolean("formalMessageTrackingComplete",formalDependencies(fact.optJSONArray("formalMemoryDependencies")).length()==0))throw new TaskStoreMemoryReader.RawMessageUnavailable(UNAVAILABLE);
        require(db,originals,task,run,conversation,null,null);
        JSONArray parts=fact.optJSONArray("nativeUserParts");if(parts==null||parts.length()>MAX_CARRIERS)throw new TaskStoreMemoryReader.RawMessageUnavailable(UNAVAILABLE);
        try(var check=originals.inspectOwnerSources()){for(int i=0;i<parts.length();i++){
            JSONObject part=parts.getJSONObject(i);check.requireNativeUserPart(part,part.getString("sourceConversationId"));SourceRef ref=SourceRef.fromJson(part.getJSONObject("sourceRef"));
            if(originals.nativeMessageHasPreviewSources(ref)||!fact.optBoolean("nativeAttachmentTrackingComplete")&&originals.nativeMessageAttachmentSources(ref).length()>0)throw new TaskStoreMemoryReader.RawMessageUnavailable(UNAVAILABLE);
        }}
        requireOutputs(db,outputs,task,run,null,null);
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
    /** Body-free dependencies for an owner trace read. No source is filtered out merely because
     * it is now retired: the canonical forget fence must see the original exact references. */
    public static JSONArray traceUserSources(SQLiteDatabase db,String task,String run)throws Exception {
        JSONObject fact=loaded(db,task,run,null,null);if(!fact.getBoolean("trackingComplete"))throw new TaskStoreMemoryReader.RawMessageUnavailable(UNAVAILABLE);
        java.util.LinkedHashMap<String,JSONObject> refs=new java.util.LinkedHashMap<>();
        JSONArray carriers=fact.getJSONArray("carriers");
        for(int i=0;i<carriers.length();i++){JSONObject carrier=carriers.getJSONObject(i);
            SourceRef own=new SourceRef("message",carrier.getString("ownerMessageId"),carrier.getLong("ownerMessageRevision"),"owner","private");refs.put(own.sourceId+":"+own.revision,own.toJson());
            JSONArray sources=carrier.getJSONArray("ownerSourceRefs");for(int j=0;j<sources.length();j++){SourceRef ref=SourceRef.fromJson(sources.getJSONObject(j).getJSONObject("sourceRef"));refs.put(ref.sourceId+":"+ref.revision,ref.toJson());}}
        JSONArray parts=fact.optJSONArray("nativeUserParts");
        for(int i=0;parts!=null&&i<parts.length();i++){SourceRef ref=SourceRef.fromJson(parts.getJSONObject(i).getJSONObject("sourceRef"));refs.put(ref.sourceId+":"+ref.revision,ref.toJson());}
        JSONArray assistants=assistantSources(fact.optJSONArray("assistantMessageSources"));
        for(int i=0;i<assistants.length();i++){SourceRef ref=SourceRef.fromJson(assistants.getJSONObject(i).getJSONObject("sourceRef"));refs.put(ref.sourceId+":"+ref.revision,ref.toJson());}
        return new JSONArray(refs.values());
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
    /** Only a native reviewed handoff may call inside its original task/run transaction.
     * These are conservative data dependencies, never transferred input-consumption proof,
     * execution authority, a new owner utterance or permission to read a different topic. */
    public static void inheritNativeRunSources(SQLiteDatabase db,ConversationRepository originals,ToolOutputStore.Sources outputs,
            String targetTask,String targetRun,String sourceTask,String sourceRun,int sourceEpoch,String sourceConversation)throws Exception{
        if(!db.inTransaction()||originals==null||sourceConversation==null||sourceConversation.isEmpty())throw new SecurityException("原生来源继承必须随准确任务绑定事务保存");
        if(targetTask.equals(sourceTask)&&targetRun.equals(sourceRun))throw new SecurityException("来源继承不能指向本次执行自身");
        requireReplyDependencies(db,originals,outputs,sourceTask,sourceRun,sourceEpoch,sourceConversation);
        JSONObject source=loaded(db,sourceTask,sourceRun,null,null),destination=loaded(db,targetTask,targetRun,null,null);
        JSONArray pins=assistantSources(source.optJSONArray("assistantMessageSources"));
        for(int i=0;i<source.getJSONArray("carriers").length();i++){
            JSONObject carrier=source.getJSONArray("carriers").getJSONObject(i);
            pins=mergeAssistantSources(pins,new JSONArray().put(originals.nativeMessageSource(new SourceRef("message",carrier.getString("ownerMessageId"),carrier.getLong("ownerMessageRevision"),"owner","private"))));
            JSONArray refs=carrier.getJSONArray("ownerSourceRefs");for(int j=0;j<refs.length();j++)
                pins=mergeAssistantSources(pins,new JSONArray().put(originals.nativeMessageSource(SourceRef.fromJson(refs.getJSONObject(j).getJSONObject("sourceRef")))));
        }
        JSONArray users=source.getJSONArray("nativeUserParts");for(int i=0;i<users.length();i++)
            pins=mergeAssistantSources(pins,new JSONArray().put(originals.nativeMessageSource(SourceRef.fromJson(users.getJSONObject(i).getJSONObject("sourceRef")))));
        // Raw carriers belong to their original conversation. Exact message pins preserve
        // that source identity without pretending they are USER input in the target topic.
        mergeOutputs(destination,source);destination.put("assistantMessageSources",mergeAssistantSources(assistantSources(destination.optJSONArray("assistantMessageSources")),pins));
        write(db,destination);
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
            requireRelatedResultSources(db,fact.optJSONArray("relatedResultSources"),bytes,signal);
            JSONArray selected=fact.getJSONArray("carriers"),assistants=assistantSources(fact.optJSONArray("assistantMessageSources"));if(selected.length()==0&&assistants.length()==0)return;if(originals==null||conversation==null||conversation.isEmpty())throw new TaskStoreMemoryReader.RawMessageUnavailable(UNAVAILABLE);
            try(var check=originals.inspectOwnerSources(bytes)){
                for(int i=0;i<selected.length();i++){JSONObject row=selected.getJSONObject(i);check.requireCarrier(row,conversation);JSONArray refs=row.getJSONArray("ownerSourceRefs");for(int j=0;j<refs.length();j++)check.requireSource(refs.getJSONObject(j));}
                for(int i=0;i<assistants.length();i++)check.requireNativeAssistantSource(assistants.getJSONObject(i));
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
            .put("formalMemoryTrackingComplete",destination.optBoolean("formalMemoryTrackingComplete")&&source.optBoolean("formalMemoryTrackingComplete"))
            .put("formalMessageTrackingComplete",destination.optBoolean("formalMessageTrackingComplete",formalDependencies(destination.optJSONArray("formalMemoryDependencies")).length()==0)&&source.optBoolean("formalMessageTrackingComplete",formalDependencies(source.optJSONArray("formalMemoryDependencies")).length()==0))
            .put("nativeAttachmentTrackingComplete",destination.optBoolean("nativeAttachmentTrackingComplete")&&source.optBoolean("nativeAttachmentTrackingComplete"))
            .put("replyPreviewDependenciesUntracked",destination.optBoolean("replyPreviewDependenciesUntracked")||source.optBoolean("replyPreviewDependenciesUntracked"))
            .put("assistantMessageSources",mergeAssistantSources(assistantSources(destination.optJSONArray("assistantMessageSources")),assistantSources(source.optJSONArray("assistantMessageSources"))))
            .put("relatedResultSources",mergeRelatedSources(relatedSources(destination.optJSONArray("relatedResultSources")),relatedSources(source.optJSONArray("relatedResultSources"))));
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
        JSONArray previous=formalDependencies(fact.optJSONArray("formalMemoryDependencies")),messages=assistantSources(fact.optJSONArray("assistantMessageSources"));boolean complete=fact.optBoolean("formalMessageTrackingComplete",previous.length()==0);
        for(int i=0;i<selected.length();i++){
            JSONObject record=selected.getJSONObject(i);JSONArray refs=record.optJSONArray("sourceRefs");
            if(refs==null){boolean known=false;for(int p=0;p<previous.length();p++)if(record.getString("id").equals(previous.getJSONObject(p).getString("id"))&&record.getLong("revision")==previous.getJSONObject(p).getLong("revision"))known=true;if(!known)complete=false;continue;}
            if(refs.length()>100)throw new SecurityException("正式记忆原文来源超过有界范围");
            for(int j=0;j<refs.length();j++){
                SourceRef ref=SourceRef.fromJson(refs.getJSONObject(j));if(!"message".equals(ref.kind))continue;
                if(!"owner".equals(ref.ownerId)||!"private".equals(ref.spaceId)){complete=false;continue;}
                JSONObject pin;
                try(Cursor row=db.rawQuery("SELECT s.generation,s.conversation_id,m.body_sha FROM meta a JOIN assistant_sources s ON s.generation=a.value JOIN assistant_messages m ON m.generation=s.generation AND m.source_id=s.source_id AND m.conversation_id=s.conversation_id JOIN assistant_conversations c ON c.generation=s.generation AND c.conversation_id=s.conversation_id WHERE a.key='assistant_history_generation' AND s.source_id=? AND s.revision=? AND m.revision=s.revision AND s.deleted=0 AND s.retired_through<s.revision AND s.context_retired=0 AND c.deleted=0 AND m.body IS NOT NULL LIMIT 1",new String[]{ref.sourceId,Long.toString(ref.revision)})){
                    if(!row.moveToFirst())throw new TaskStoreMemoryReader.RawMessageUnavailable("formal_message_source_unavailable");
                    pin=new JSONObject().put("generation",row.getString(0)).put("sourceConversationId",row.getString(1)).put("sourceRef",ref.toJson()).put("bodyFingerprint",row.getString(2));
                }
                messages=mergeAssistantSources(messages,new JSONArray().put(pin));
            }
        }
        fact.put("formalMemoryDependencies",union(previous,exact,64,"formal")).put("formalMessageTrackingComplete",complete).put("assistantMessageSources",messages);write(db,fact);
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
