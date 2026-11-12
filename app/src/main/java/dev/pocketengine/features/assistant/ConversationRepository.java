package dev.pocketengine.features.assistant;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.CancellationSignal;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.memory.ConversationHistory;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.features.memory.TaskMemoryTrace;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.Callable;
import org.json.JSONArray;
import org.json.JSONObject;

/** Original TaskStore connection/monitor only. No second DB, body mirror, model command or grant. */
public final class ConversationRepository {
    public static final class ReadLimited extends Exception {public ReadLimited(String reason){super(reason);}}
    public static final int MAX_ROWS=100,MAX_BYTES=1_048_576;
    // A complete model answer shares the existing message window with source/receipt metadata.
    private static final int MAX_REPLY_BYTES=MAX_BYTES/2;
    static void requireReplyContent(String text){
        if(text==null||text.isBlank())throw new IllegalArgumentException("模型没有返回文字答复");
        if(!StandardCharsets.UTF_8.newEncoder().canEncode(text))throw new IllegalArgumentException("答复含不完整Unicode字符，未替换后保存");
        if(text.length()>MAX_REPLY_BYTES||text.getBytes(StandardCharsets.UTF_8).length>MAX_REPLY_BYTES)
            throw new IllegalArgumentException("完整答复超过单条消息的512KiB正文预算，原任务检查点仍保留");
    }
    static final java.util.concurrent.ScheduledThreadPoolExecutor DEADLINES=deadlines();
    private static java.util.concurrent.ScheduledThreadPoolExecutor deadlines(){
        var timer=new java.util.concurrent.ScheduledThreadPoolExecutor(1,action->{Thread thread=new Thread(action,"original-history-read-deadline");thread.setDaemon(true);return thread;});
        timer.setRemoveOnCancelPolicy(true);timer.setKeepAliveTime(1,java.util.concurrent.TimeUnit.SECONDS);timer.allowCoreThreadTimeOut(true);return timer;
    }
    /** Bounded original SQLite metadata and native dependency proof; no message body/cache.
     * This is deliberately callable while MemoryBook is held: no TaskStore/storage monitor,
     * callback or write is acquired here. Cancellation is best effort, not a hard I/O claim. */
    public static final class SourceMetadata {
        public final boolean databaseAuthority;public final SourceRef.State state;
        private SourceMetadata(boolean authority,SourceRef.State value){databaseAuthority=authority;state=value;}
    }
    /** Lock-free bounded original SQLite proof. An assistant message/document/summary cannot
     * become a user utterance by copying the same words or assigning itself a source label. */
    public void requireUserSaidSpan(SourceRef ref,int offset,int chars,String sha,String claimed)throws Exception {requireUserSaidSpan(ref,offset,chars,sha,claimed,null);}
    public void requireUserSaidSpan(SourceRef ref,int offset,int chars,String sha,String claimed,Long assertedAt)throws Exception {
        requireUserSaidSpan(ref,offset,chars,sha,claimed,assertedAt,null);
    }
    public void requireUserSaidSpan(SourceRef ref,int offset,int chars,String sha,String claimed,Long assertedAt,JSONObject assertion)throws Exception {
        if(!healthy||!active()||!"message".equals(ref.kind)||!"owner".equals(ref.ownerId)||!"private".equals(ref.spaceId)||offset<0||chars<1||chars>24000)throw new SecurityException("原主人发言证明不可用");
        CancellationSignal signal=new CancellationSignal();long started=System.nanoTime();var deadline=DEADLINES.schedule(signal::cancel,500,java.util.concurrent.TimeUnit.MILLISECONDS);
        try(Cursor row=db.rawQuery("SELECT s.role,s.revision,s.deleted,s.retired_through,s.context_retired,coalesce(c.deleted,1),m.body,m.body_bytes,m.body_sha,m.revision,m.at_ms FROM assistant_sources s JOIN meta a ON a.key='assistant_history_generation' AND a.value=s.generation LEFT JOIN assistant_conversations c ON c.generation=s.generation AND c.conversation_id=s.conversation_id LEFT JOIN assistant_messages m ON m.generation=s.generation AND m.source_id=s.source_id WHERE s.source_id=? AND m.body_bytes<=1048576 LIMIT 1",new String[]{ref.sourceId},signal)){
            if(!row.moveToFirst()||!"user".equals(row.getString(0))||row.getLong(1)!=ref.revision||row.getInt(2)!=0||row.getLong(3)>=ref.revision||row.getInt(4)!=0||row.getInt(5)!=0||row.isNull(6)||row.getLong(9)!=ref.revision)throw new SecurityException("不是确切仍有效的原主人发言");
            if(assertedAt!=null&&(row.isNull(10)||row.getLong(10)!=assertedAt))throw new SecurityException("陈述时间不是原消息实际时间，未知不能补为今天");
            String text=row.getString(6);long end=(long)offset+chars;if(end>text.length()||offset>0&&Character.isLowSurrogate(text.charAt(offset))&&Character.isHighSurrogate(text.charAt(offset-1))||end<text.length()&&Character.isLowSurrogate(text.charAt((int)end))&&Character.isHighSurrogate(text.charAt((int)end-1)))throw new SecurityException("主人原话范围不完整");
            byte[] bytes=text.getBytes(StandardCharsets.UTF_8);String span=text.substring(offset,(int)end);if(bytes.length!=row.getLong(7)||!("sha256:"+shaBytes(bytes)).equals(row.getString(8))||!shaBytes(span.getBytes(StandardCharsets.UTF_8)).equals(sha)||!span.equals(claimed))throw new SecurityException("主人原话、hash或声称文字不一致，不能把推断当明确原话");
            if(assertion!=null&&"owner".equals(assertion.optString("subjectId"))&&"owner".equals(assertion.optString("subjectType")))dev.pocketengine.features.memory.PersonalMemoryAttribution.requireOwnerClaim(text,offset,chars,assertion);
            if(!healthy||signal.isCanceled()||System.nanoTime()-started>500_000_000L)throw new SecurityException("原作者/原话证明超时，未采用模型自报标签");
        }finally{deadline.cancel(false);}
    }
    public SourceMetadata resolveMetadata(String sourceId)throws Exception{
        if(sourceId==null||sourceId.length()>240)throw new IllegalArgumentException("来源标识无效");
        if(!healthy)throw new IllegalStateException("原文权威状态不确定，未采用缓存来源");
        CancellationSignal signal=new CancellationSignal();long started=System.nanoTime();
        var deadline=DEADLINES.schedule(signal::cancel,500,java.util.concurrent.TimeUnit.MILLISECONDS);
        try(Cursor row=db.rawQuery("SELECT a.value,s.revision,s.deleted,coalesce(c.deleted,1) FROM (SELECT value FROM meta WHERE key='assistant_history_generation') a LEFT JOIN assistant_sources s ON s.generation=a.value AND s.source_id=? LEFT JOIN assistant_conversations c ON c.generation=s.generation AND c.conversation_id=s.conversation_id LIMIT 1",new String[]{sourceId},signal)){
            boolean found=row.moveToFirst();
            if(!healthy||signal.isCanceled()||System.nanoTime()-started>500_000_000L)throw new IllegalStateException("来源metadata读取超时或权威不确定，未授权旧来源");
            if(!found)return new SourceMetadata(false,null);if(row.isNull(1))return new SourceMetadata(true,null);long revision=row.getLong(1);if(revision<1)throw new IllegalStateException("原来源实际修订无效");
            boolean unavailable=row.getInt(2)!=0||row.getInt(3)!=0;
            if(!unavailable)try{requireDerivedMessageCurrent(sourceId,revision);}catch(SourceUnavailable withdrawn){unavailable=true;}
            return new SourceMetadata(true,new SourceRef.State(new SourceRef("message",sourceId,revision,"owner","private"),unavailable));
        }finally{deadline.cancel(false);}
    }
    private final SQLiteDatabase db;private final Object monitor;private final java.util.function.LongSupplier monotonicNanos;private volatile String generation;private volatile boolean healthy=true;
    private volatile dev.pocketengine.features.memory.ToolOutputStore.Sources outputSources;
    /** Only a direct, non-locking attachment eligibility reader is bound here. Never call
     * back into TaskStore or MemoryBook while resolving a formal-memory SourceRef. */
    public void bindOutputSources(dev.pocketengine.features.memory.ToolOutputStore.Sources original){
        if(original==null)throw new IllegalArgumentException("原输出来源检查器缺失");
        if(outputSources!=null&&outputSources!=original)throw new SecurityException("原输出来源检查器不能替换");outputSources=original;
    }
    private static final class DerivedInspection {
        final long deadline=System.nanoTime()+500_000_000L;
        final Set<String> visiting=new HashSet<>(),checked=new HashSet<>();
        void check()throws SourceUnavailable{if(System.nanoTime()>deadline||Thread.currentThread().isInterrupted()||checked.size()+visiting.size()>100)throw new SourceUnavailable("派生来源核对超过本次有界范围，未采用旧正文");}
    }
    private final ThreadLocal<DerivedInspection> derivedInspection=new ThreadLocal<>();

    /** A native saved reply retains its actual producer, not a model-supplied task label.
     * This joins the original reply commit; import/JSON restoration cannot invoke it. */
    public void bindNativeReplyContext(dev.pocketengine.store.TaskStore original,String conversation,String messageId,String task,String run,int epoch)throws Exception{
        if(monitor!=original||!Thread.holdsLock(monitor)||!db.inTransaction())throw new SecurityException("答复来源只能随原TaskStore保存事务绑定");
        JSONObject state=source(messageId),message=message(conversation,messageId);
        if(state==null||message==null||state.optBoolean("nativeProvenanceImported")||!"assistant".equals(state.optString("role"))||!conversation.equals(state.optString("conversationId"))
                ||!task.equals(message.optString("taskId"))||!run.equals(message.optString("nativeRunId"))||epoch!=message.optInt("nativeEpoch")
                ||!message.optString("nativeReplyId").equals(state.optString("nativeReplyId"))||message.optString("nativeReplyId").isEmpty())throw new SecurityException("答复没有同一原生保存身份");
        try(Cursor row=db.rawQuery("SELECT 1 FROM runs WHERE task_id=? AND run_id=? AND epoch=? LIMIT 1",new String[]{task,run,Integer.toString(epoch)})){if(!row.moveToFirst())throw new SecurityException("答复原执行已不存在");}
        String fingerprint="sha256:"+shaBytes(message.getString("content").getBytes(StandardCharsets.UTF_8));
        JSONObject context=new JSONObject().put("format",1).put("generation",activeGeneration()).put("taskId",task).put("runId",run).put("epoch",epoch)
                .put("sourceRevision",state.getLong("revision")).put("bodyFingerprint",fingerprint);
        JSONObject previous=state.optJSONObject("nativeContext");if(previous!=null&&!TaskMemoryTrace.rawFingerprint(previous).equals(TaskMemoryTrace.rawFingerprint(context)))throw new SecurityException("同一答复不能更换原执行来源");
        state.put("nativeTaskId",task).put("nativeRunId",run).put("nativeEpoch",epoch).put("nativeContext",context);
        message.put("nativeContext",new JSONObject(context.toString()));saveSource(state);saveMessage(conversation,message);
    }

    /** Lock-free, body-free dependency validation. The original run source facts may point
     * to older saved replies; bounded recursion follows that native DAG, never arbitrary JSON. */
    public void requireDerivedMessageCurrent(String sourceId,long revision)throws Exception{
        DerivedInspection inspection=derivedInspection.get();boolean outer=inspection==null;if(outer){inspection=new DerivedInspection();derivedInspection.set(inspection);}
        String key=sourceId+":"+revision;
        try{
            inspection.check();if(inspection.checked.contains(key))return;if(!inspection.visiting.add(key))throw new SourceUnavailable("派生来源形成循环，未采用正文");
            CancellationSignal signal=new CancellationSignal();var timeout=DEADLINES.schedule(signal::cancel,500,java.util.concurrent.TimeUnit.MILLISECONDS);
            JSONObject state,nativeMessage;String conversation,gen,fingerprint,role;
            try(Cursor row=db.rawQuery("SELECT a.value,s.conversation_id,s.revision,s.role,s.deleted,s.retired_through,s.context_retired,coalesce(c.deleted,1),m.revision,m.body_sha,m.body IS NULL,CASE WHEN s.role='user' THEN '{}' WHEN length(CAST(s.source_json AS BLOB))<=65536 THEN s.source_json END,CASE WHEN s.role='user' THEN '{}' WHEN length(CAST(m.meta_json AS BLOB))<=65536 THEN m.meta_json END FROM meta a LEFT JOIN assistant_sources s ON s.generation=a.value AND s.source_id=? LEFT JOIN assistant_conversations c ON c.generation=s.generation AND c.conversation_id=s.conversation_id LEFT JOIN assistant_messages m ON m.generation=s.generation AND m.source_id=s.source_id WHERE a.key='assistant_history_generation' LIMIT 1",new String[]{sourceId},signal)){
                if(!healthy||!row.moveToFirst()||row.isNull(2)||revision!=row.getLong(2)||row.getInt(4)!=0||row.getLong(5)>=revision||row.getInt(6)!=0||row.getInt(7)!=0||row.isNull(8)||row.getLong(8)!=revision||row.getInt(10)!=0||row.isNull(11)||row.isNull(12))throw new SourceUnavailable("原消息已删除、停用、换版或无法核对");
                gen=row.getString(0);conversation=row.getString(1);role=row.getString(3);fingerprint=row.getString(9);state=new JSONObject(row.getString(11));nativeMessage=new JSONObject(row.getString(12));
            }finally{timeout.cancel(false);}
            if("assistant".equals(role)){
                JSONObject context=state.optJSONObject("nativeContext");
                // Old unproved native replies stay visible to the owner, but cannot be
                // silently promoted to valid new model context or formal memory.
                if(context==null||state.optBoolean("nativeProvenanceImported"))throw new SourceUnavailable("历史助手答复缺少完整原生依赖证明");
                else{
                    if(context.length()!=7||context.optInt("format")!=1||!gen.equals(context.optString("generation"))||revision!=context.optLong("sourceRevision")||!fingerprint.equals(context.optString("bodyFingerprint"))
                            ||!context.optString("taskId").equals(state.optString("nativeTaskId"))||!context.optString("runId").equals(state.optString("nativeRunId"))||context.optInt("epoch")!=state.optInt("nativeEpoch"))throw new SourceUnavailable("助手答复原生依赖身份已变化");
                    try{
                        if("owner_work".equals(nativeMessage.optString("nativeDeliveryKind"))){
                            if(!state.optString("nativeReplyId").equals(nativeMessage.optString("nativeDeliveryId"))||!state.optString("nativeReplyId").equals(nativeMessage.optString("nativeReplyId"))
                                    ||!state.optString("nativeReplyDigest").equals(nativeMessage.optString("resultDigest"))||!context.getString("taskId").equals(nativeMessage.optString("taskId"))
                                    ||!context.getString("runId").equals(nativeMessage.optString("nativeRunId"))||context.getInt("epoch")!=nativeMessage.optInt("nativeEpoch"))throw new SourceUnavailable("原工作发布消息身份已变化");
                            dev.pocketengine.features.memory.OwnerSourceRunContext.requireOwnerWorkDeliverySources(db,context.getString("taskId"),context.getString("runId"),context.getInt("epoch"),conversation,gen,sourceId,state.getString("nativeReplyId"),state.getString("nativeReplyDigest"),fingerprint);
                        }
                        dev.pocketengine.features.memory.OwnerSourceRunContext.requireReplyDependencies(db,this,outputSources,context.getString("taskId"),context.getString("runId"),context.getInt("epoch"),conversation);
                    }catch(Exception unavailable){if(unavailable instanceof InterruptedException||Thread.currentThread().isInterrupted())throw unavailable;throw new SourceUnavailable("助手答复依赖的原消息、结果或附件已不可用");}
                }
            }
            inspection.check();inspection.checked.add(key);
        }finally{inspection.visiting.remove(key);if(outer)derivedInspection.remove();}
    }

    /** The returned metadata is issued from the real saved assistant row, not its caller. */
    public JSONObject nativeAssistantSource(String sourceId)throws Exception{
        JSONObject state=metadata(sourceId);if(state==null||!"assistant".equals(state.optString("role")))throw new SourceUnavailable("原助手来源不存在");
        return nativeMessageSource(new SourceRef("message",sourceId,state.getLong("revision"),"owner","private"));
    }
    /** Exact message returned by the native history reader; imports have no SourceRef. */
    public JSONObject nativeMessageSource(SourceRef ref)throws Exception{
        if(ref==null||!"message".equals(ref.kind)||!"owner".equals(ref.ownerId)||!"private".equals(ref.spaceId))throw new SourceUnavailable("历史来源身份无效");
        String sourceId=ref.sourceId;JSONObject state=metadata(sourceId);if(state==null||state.getLong("revision")!=ref.revision)throw new SourceUnavailable("原历史消息已变化");
        requireDerivedMessageCurrent(sourceId,ref.revision);
        try(Cursor row=db.rawQuery("SELECT m.generation,m.body_sha FROM assistant_messages m JOIN meta a ON a.key='assistant_history_generation' AND a.value=m.generation WHERE m.source_id=? AND m.revision=? AND m.body IS NOT NULL LIMIT 1",new String[]{sourceId,Long.toString(state.getLong("revision"))})){
            if(!row.moveToFirst())throw new SourceUnavailable("原助手正文身份已变化");
            return new JSONObject().put("generation",row.getString(0)).put("sourceConversationId",state.getString("conversationId"))
                    .put("sourceRef",new SourceRef("message",sourceId,state.getLong("revision"),"owner","private").toJson()).put("bodyFingerprint",row.getString(1));
        }
    }
    /** Metadata captured at actual MAIN admission. Initial image/document attachments
     * need the same generation dependency as document tool outputs. */
    public JSONArray nativeMessageAttachmentSources(SourceRef ref)throws Exception{
        requireDerivedMessageCurrent(ref.sourceId,ref.revision);JSONObject state=metadata(ref.sourceId);
        if(state==null||!"user".equals(state.optString("role")))throw new SourceUnavailable("附件来源必须是准确原主人消息");
        JSONObject meta=nativeMessageMetadata(ref);
        JSONArray attachments=meta.optJSONArray("attachments"),result=new JSONArray();if(attachments==null)return result;if(attachments.length()>4)throw new SourceUnavailable("原消息附件数量不可核对");
        for(int i=0;i<attachments.length();i++){
            JSONObject attachment=attachments.getJSONObject(i);String id=attachment.getString("id"),sha=attachment.getString("sha256");
            String sourceGeneration=outputSources==null?null:outputSources.attachmentGeneration(id,sha);
            if(sourceGeneration==null)throw new SourceUnavailable("原消息附件已停用，未保存可恢复正文依赖");
            result.put(new JSONObject().put("kind","attachment").put("id",id).put("sha256",sha).put("generation",sourceGeneration));
        }return dev.pocketengine.features.memory.ToolOutputStore.checkedDependencies(result);
    }
    public boolean nativeMessageHasPreviewSources(SourceRef ref)throws Exception{
        JSONArray refs=nativeMessageMetadata(ref).optJSONArray("ownerPreviewRefs");return refs!=null&&refs.length()>0;
    }
    private JSONObject nativeMessageMetadata(SourceRef ref)throws Exception{
        try(Cursor row=db.rawQuery("SELECT CASE WHEN length(CAST(meta_json AS BLOB))<=1048576 THEN meta_json END FROM assistant_messages WHERE generation=? AND source_id=? AND revision=? LIMIT 1",new String[]{activeGeneration(),ref.sourceId,Long.toString(ref.revision)})){
            if(!row.moveToFirst()||row.isNull(0))throw new SourceUnavailable("原消息附件元数据不可用");return new JSONObject(row.getString(0));
        }
    }
    public JSONObject projectModelHistoryMessage(String conversation,JSONObject saved)throws Exception{
        JSONObject result=new JSONObject(saved.toString());if(!"assistant".equals(result.optString("role")))return result;
        try{
            JSONObject state=source(saved.getString("id"));if(state==null)throw new SourceUnavailable("历史助手来源已不存在");
            if(monitor instanceof dev.pocketengine.store.TaskStore&&state.optJSONObject("nativeContext")==null)
                transaction(()->{recoverNativeReplyContext((dev.pocketengine.store.TaskStore)monitor,conversation,saved.getString("id"),new HashSet<>());return null;});
            JSONObject pin=nativeAssistantSource(saved.getString("id"));
            if(!conversation.equals(pin.getString("sourceConversationId"))||saved.getLong("revision")!=pin.getJSONObject("sourceRef").getLong("revision")
                    ||!("sha256:"+shaBytes(saved.getString("content").getBytes(StandardCharsets.UTF_8))).equals(pin.getString("bodyFingerprint")))throw new SourceUnavailable("历史助手正文与原版本不一致");
            result.put("nativeAssistantSource",pin);
        }catch(Exception unavailable){if(unavailable instanceof InterruptedException||Thread.currentThread().isInterrupted())throw unavailable;result.put("content",dev.pocketengine.features.memory.TaskMemoryHistory.RETIRED).put("memoryContextRetired",true);result.remove("nativeAssistantSource");result.remove("provider_data");result.remove("reasoning_content");}
        return result;
    }
    /** Upgrade only exact original SQLite answers with a completed physical call. Older
     * incomplete source paths remain readable by the owner; no guessed task/reply binding. */
    private void recoverNativeReplyContext(dev.pocketengine.store.TaskStore original,String conversation,String messageId,Set<String> visiting)throws Exception{
        if(visiting.size()>=30||!visiting.add(messageId))throw new SourceUnavailable("历史助手依赖无法在本次范围恢复");
        try{
            JSONObject state=source(messageId),message=message(conversation,messageId);
            if(state==null||message==null||state.optBoolean("nativeProvenanceImported")||!"assistant".equals(state.optString("role")))throw new SourceUnavailable("历史助手来源未保留原生证明");
            if(state.optJSONObject("nativeContext")!=null)return;
            String task=message.optString("taskId"),run=message.optString("nativeRunId"),reply=message.optString("nativeReplyId");int epoch=message.optInt("nativeEpoch");
            if(task.isEmpty()||run.isEmpty()||epoch<1||reply.isEmpty()||!reply.equals(state.optString("nativeReplyId"))||!run.equals(state.optString("nativeRunId")))throw new SourceUnavailable("历史助手原任务身份不完整");
            JSONObject checkpoint;long sequence;
            try(Cursor row=db.rawQuery("SELECT CASE WHEN length(CAST(r.checkpoint AS BLOB))<=1048576 THEN r.checkpoint END,m.sequence FROM runs r JOIN assistant_messages m ON m.generation=? AND m.source_id=? WHERE r.task_id=? AND r.run_id=? AND r.epoch=? LIMIT 1",new String[]{activeGeneration(),messageId,task,run,Integer.toString(epoch)})){
                if(!row.moveToFirst()||row.isNull(0))throw new SourceUnavailable("历史助手原执行检查点不可核对");checkpoint=new JSONObject(row.getString(0));sequence=row.getLong(1);
            }
            // The pre-hub receipt envelope did not preserve its upstream result messages.
            // Only the exact native owner-work publication/claim may upgrade that path.
            try(Cursor rows=db.rawQuery("SELECT CASE WHEN length(CAST(attempt_control AS BLOB))<=1048576 THEN attempt_control END FROM inference_requests WHERE task_id=? AND run_id=? LIMIT 101",new String[]{task,run})){
                int seen=0;while(rows.moveToNext()){
                    if(++seen>100||rows.isNull(0))throw new SourceUnavailable("历史请求证明超过恢复范围");JSONObject ledger=new JSONObject(rows.getString(0));
                    JSONArray attempts=ledger.optJSONArray("attempts");for(int i=0;attempts!=null&&i<attempts.length();i++){JSONArray results=attempts.getJSONObject(i).optJSONArray("ownerWorkReceipts");if(results!=null&&results.length()>0)throw new SourceUnavailable("旧结果汇报尚缺原生上游关系");}
                }
            }
            JSONArray rows=checkpoint.optJSONArray("messages"),dependencies=new JSONArray();boolean proved=false;
            for(int i=0;rows!=null&&i<rows.length();i++){
                JSONObject row=rows.getJSONObject(i);
                for(String field:List.of("ownerPreviewRefs","ownerAttachmentRefs")){JSONArray selected=row.optJSONArray(field);if(selected!=null&&selected.length()>0)throw new SourceUnavailable("旧答复未保留所选附件或预览的完整原生依赖");}
                if(!"assistant".equals(row.optString("role")))continue;
                JSONArray calls=row.optJSONArray("tool_calls");for(int j=0;calls!=null&&j<calls.length();j++){
                    String tool=calls.getJSONObject(j).getJSONObject("function").optString("name");
                    if(Set.of("memory_history_search","memory_history_read","owner_work_get").contains(tool))throw new SourceUnavailable("历史助手曾读取未完整继承的旧正文来源");
                }
                String callId=row.optString("nativeTraceCallId");
                if(callId.isEmpty()&&row.opt("content") instanceof String&&!row.getString("content").isEmpty()){
                    String prior=null,digest="sha256:"+shaBytes(row.getString("content").getBytes(StandardCharsets.UTF_8));
                    try(Cursor candidates=db.rawQuery("SELECT m.source_id FROM assistant_messages m JOIN assistant_sources s ON s.generation=m.generation AND s.source_id=m.source_id WHERE m.generation=? AND m.conversation_id=? AND m.sequence<? AND m.body_sha=? AND s.role='assistant' ORDER BY m.sequence DESC LIMIT 2",new String[]{activeGeneration(),conversation,Long.toString(sequence),digest})){
                        while(candidates.moveToNext()){if(prior!=null)throw new SourceUnavailable("历史助手原文归属不唯一");prior=candidates.getString(0);}
                    }
                    if(prior==null)throw new SourceUnavailable("历史助手上下文没有准确原文来源");recoverNativeReplyContext(original,conversation,prior,visiting);dependencies.put(nativeAssistantSource(prior));
                }
                if(!callId.isEmpty()&&calls==null&&message.getString("content").equals(row.optString("content"))){
                    JSONObject evidence=dev.pocketengine.store.TaskAuditStore.call(db,task,callId),context=evidence==null?null:evidence.optJSONObject("context"),reference=row.optJSONObject("memoryReference");
                    if(evidence!=null&&"COMPLETED".equals(evidence.optString("outcome"))&&context!=null&&run.equals(context.optString("runId"))&&epoch==context.optInt("epoch")
                            &&reference!=null&&reply.equals(reference.optString("answerId")))proved=true;
                }
            }
            if(!proved)throw new SourceUnavailable("历史助手缺少原实际模型请求与答复身份证明");
            dev.pocketengine.features.memory.OwnerSourceRunContext.assistantsPrepared(db,this,task,run,dependencies);
            bindNativeReplyContext(original,conversation,messageId,task,run,epoch);
        }finally{visiting.remove(messageId);}
    }
    public ConversationRepository(SQLiteDatabase original,Object originalMonitor){this(original,originalMonitor,System::nanoTime);}
    /** Test-only native clock seam; production always uses the platform monotonic clock. */
    ConversationRepository(SQLiteDatabase original,Object originalMonitor,java.util.function.LongSupplier nanos){db=Objects.requireNonNull(original);monitor=Objects.requireNonNull(originalMonitor);monotonicNanos=Objects.requireNonNull(nanos);synchronized(monitor){reloadAuthority();}}
    public static void createSchema(SQLiteDatabase d){
        d.execSQL("CREATE TABLE IF NOT EXISTS assistant_conversations(generation TEXT NOT NULL,conversation_id TEXT NOT NULL,header_json TEXT NOT NULL,updated_at INTEGER NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,delete_reason TEXT NOT NULL DEFAULT '',PRIMARY KEY(generation,conversation_id))");
        d.execSQL("CREATE INDEX IF NOT EXISTS assistant_conversations_page ON assistant_conversations(generation,deleted,updated_at,conversation_id)");
        d.execSQL("CREATE TABLE IF NOT EXISTS assistant_sources(generation TEXT NOT NULL,source_id TEXT NOT NULL,conversation_id TEXT NOT NULL,revision INTEGER NOT NULL,role TEXT NOT NULL,deleted INTEGER NOT NULL,unavailable_reason TEXT NOT NULL,retired_through INTEGER NOT NULL DEFAULT 0,retirement_reason TEXT NOT NULL DEFAULT '',context_retired INTEGER NOT NULL DEFAULT 0,source_json TEXT NOT NULL,client_message_id TEXT,native_reply_id TEXT,PRIMARY KEY(generation,source_id))");
        d.execSQL("CREATE INDEX IF NOT EXISTS assistant_sources_conversation ON assistant_sources(generation,conversation_id,deleted)");
        d.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS assistant_sources_client ON assistant_sources(generation,client_message_id) WHERE client_message_id IS NOT NULL");
        d.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS assistant_sources_reply ON assistant_sources(generation,native_reply_id) WHERE native_reply_id IS NOT NULL");
        d.execSQL("CREATE TABLE IF NOT EXISTS assistant_messages(generation TEXT NOT NULL,source_id TEXT NOT NULL,conversation_id TEXT NOT NULL,sequence INTEGER NOT NULL,revision INTEGER NOT NULL,body TEXT,body_bytes INTEGER NOT NULL,body_sha TEXT NOT NULL,meta_json TEXT NOT NULL,at_ms INTEGER,PRIMARY KEY(generation,source_id),UNIQUE(generation,conversation_id,sequence))");
        d.execSQL("CREATE INDEX IF NOT EXISTS assistant_messages_page ON assistant_messages(generation,conversation_id,sequence)");
        d.execSQL("CREATE TABLE IF NOT EXISTS assistant_import_documents(generation TEXT NOT NULL,source_sha TEXT NOT NULL,source_bytes INTEGER NOT NULL,source_format TEXT NOT NULL,redacted INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(generation,source_sha))");
        d.execSQL("CREATE TABLE IF NOT EXISTS assistant_import_fragments(generation TEXT NOT NULL,source_sha TEXT NOT NULL,byte_offset INTEGER NOT NULL,byte_length INTEGER NOT NULL,conversation_id TEXT,record_id TEXT,data BLOB,PRIMARY KEY(generation,source_sha,byte_offset))");
        d.execSQL("CREATE TABLE IF NOT EXISTS assistant_import_records(generation TEXT NOT NULL,record_id TEXT NOT NULL,conversation_id TEXT NOT NULL,sequence INTEGER NOT NULL,record_version INTEGER NOT NULL,historical_revision INTEGER,at_ms INTEGER,role TEXT NOT NULL,source_sha TEXT NOT NULL,byte_offset INTEGER NOT NULL,byte_length INTEGER NOT NULL,fragment_sha TEXT NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,unavailable_reason TEXT NOT NULL DEFAULT '',PRIMARY KEY(generation,record_id),UNIQUE(generation,conversation_id,sequence))");
        d.execSQL("CREATE INDEX IF NOT EXISTS assistant_import_records_page ON assistant_import_records(generation,conversation_id,sequence)");
        createOrganizationSchema(d);
    }
    /** Explicit TaskStore schema migration only. Never runs an ALTER in a repository constructor. */
    public static void createOrganizationSchema(SQLiteDatabase original){ConversationOrganization.createSchema(original);}
    public JSONObject ownerOrganization(String command,JSONObject input)throws Exception{
        if(input==null)throw new IllegalArgumentException("会话操作参数缺失");
        switch(command){
            case "list":return read(()->ConversationOrganization.list(db,activeGeneration(),input));
            case "getOrganization":ConversationOrganization.fields(input,"conversationId");return read(()->ConversationOrganization.get(db,activeGeneration(),input.getString("conversationId")));
            case "updateOrganization":return transaction(()->ConversationOrganization.update(db,activeGeneration(),input));
            case "listGroups":return read(()->ConversationOrganization.groups(db,activeGeneration(),input));
            case "createGroup":return transaction(()->ConversationOrganization.createGroup(db,activeGeneration(),input));
            case "updateGroup":return transaction(()->ConversationOrganization.updateGroup(db,activeGeneration(),input));
            default:throw new IllegalArgumentException("不支持的主人会话组织操作");
        }
    }
    /** Freeze one bounded content snapshot under the original TaskStore/SQLite transaction. */
    public JSONObject exportConversation(JSONObject input)throws Exception{return exportConversation(input,false);}
    /** Native maintenance uses only its verified, private read-only mirror. Never starts a write transaction. */
    public JSONObject exportVerifiedReadOnlyCopy(JSONObject input)throws Exception{
        if(!db.isReadOnly())throw new SecurityException("维护导出必须来自只读副本，不能在原可写数据库上运行");
        return exportConversation(input,true);
    }
    private JSONObject exportConversation(JSONObject input,boolean readOnlyCopy)throws Exception{
        ConversationOrganization.fields(input,"conversationId","format");String conversation=input.getString("conversationId"),format=input.optString("format","json");
        java.util.concurrent.Callable<JSONObject> capture=()->{
            JSONObject header=header(conversation);String gen=activeGeneration();long started=System.nanoTime(),upper;
            try(Cursor max=db.rawQuery("SELECT coalesce(max(n),0) FROM (SELECT sequence n FROM assistant_messages WHERE generation=? AND conversation_id=? UNION ALL SELECT sequence n FROM assistant_import_records WHERE generation=? AND conversation_id=?)",new String[]{gen,conversation,gen,conversation})){max.moveToFirst();upper=max.getLong(0);}
            JSONArray messages=new JSONArray(),omitted=new JSONArray(),attachments=new JSONArray();long bytes=0;int scanned=0;
            try(Cursor rows=db.rawQuery("SELECT id,n,kind,bytes,fingerprint FROM (SELECT m.source_id id,m.sequence n,'native' kind,m.body_bytes bytes,m.body_sha fingerprint FROM assistant_messages m WHERE m.generation=? AND m.conversation_id=? UNION ALL SELECT record_id id,sequence n,'imported' kind,byte_length bytes,fragment_sha fingerprint FROM assistant_import_records WHERE generation=? AND conversation_id=?) WHERE n<=? ORDER BY n LIMIT ?",new String[]{gen,conversation,gen,conversation,Long.toString(upper),Integer.toString(ConversationTransfer.MAX_MESSAGES+1)})){
                while(rows.moveToNext()){
                    if(++scanned>ConversationTransfer.MAX_MESSAGES||System.nanoTime()-started>5_000_000_000L)throw new ReadLimited("conversation_export_row_or_time_budget");String id=rows.getString(0),kind=rows.getString(2),reason="";JSONObject message=null;long version=0;
                    if(kind.equals("native")){JSONObject state=metadata(id);if(state==null||state.optBoolean("deleted"))reason="deleted_or_unavailable";else if(state.optLong("memorySourceRetiredThrough")>=state.getLong("revision")||state.optBoolean("memoryContextRetired"))reason="retired_for_derived_context";else if(rows.getLong(3)>MAX_BYTES)reason="message_read_limited";else{message=message(conversation,id);version=state.getLong("revision");if(message!=null&&(message.getLong("revision")!=version||!state.getString("role").equals(message.optString("role")))){message=null;reason="source_revision_or_role_unavailable";}}}
                    else{if(rows.getLong(3)>MAX_BYTES)reason="message_read_limited";else{message=importedMessage(conversation,id);if(message!=null)version=message.getLong("recordVersion");}}
                    if(message==null){omitted.put(new JSONObject().put("sourceRecordId",id).put("reason",reason.isEmpty()?"deleted_or_unavailable":reason));continue;}
                    if(!Set.of("user","assistant").contains(message.optString("role"))){omitted.put(new JSONObject().put("sourceRecordId",id).put("reason","unsupported_historical_record_role"));continue;}
                    String body=message.getString("content");if(kind.equals("native")&&!rows.getString(4).equals("sha256:"+ConversationTransfer.sha(ConversationTransfer.utf8(body)))){omitted.put(new JSONObject().put("sourceRecordId",id).put("reason","source_integrity_unavailable"));continue;}bytes=Math.addExact(bytes,ConversationTransfer.utf8(body).length);if(bytes>ConversationTransfer.MAX_BYTES-524288)throw new ReadLimited("conversation_export_byte_budget");
                    messages.put(new JSONObject().put("sourceRecordId",id).put("sourceRecordVersion",version).put("sequence",rows.getLong(1)).put("role",message.getString("role")).put("content",body).put("contentSha256",ConversationTransfer.sha(body.getBytes(StandardCharsets.UTF_8))).put("sourceKind",kind.equals("native")?"native_original":"imported_history"));
                    if(actualInteger(message.opt("at"))&&message.getLong("at")>=0)messages.getJSONObject(messages.length()-1).put("at",message.getLong("at"));
                    if(message.optJSONArray("attachments")!=null&&message.getJSONArray("attachments").length()>0){omitted.put(new JSONObject().put("sourceRecordId",id).put("reason","attachment_bytes_and_grants_not_exported"));JSONArray refs=message.getJSONArray("attachments");for(int i=0;i<refs.length();i++){JSONObject ref=refs.getJSONObject(i);if(ref.optString("id").isEmpty()||!ref.optString("sha256").matches("[a-f0-9]{64}"))continue;attachments.put(new JSONObject().put("messageId",id).put("attachmentId",ref.getString("id")).put("sha256",ref.getString("sha256")).put("availability","reference_only_unverified_not_restored"));}}
                    if(message.optJSONArray("ownerSourceRefs")!=null&&message.getJSONArray("ownerSourceRefs").length()>0)omitted.put(new JSONObject().put("sourceRecordId",id).put("reason","external_source_grants_not_exported"));
                    if(message.optJSONArray("ownerObjectRefs")!=null&&message.getJSONArray("ownerObjectRefs").length()>0)omitted.put(new JSONObject().put("sourceRecordId",id).put("reason","native_object_selections_not_exported"));
                    if(message.optJSONArray("ownerPreviewRefs")!=null&&message.getJSONArray("ownerPreviewRefs").length()>0)omitted.put(new JSONObject().put("sourceRecordId",id).put("reason","native_preview_selections_not_exported"));
                }
            }
            return new JSONObject().put("schema","pocketworld_conversation_export").put("schemaVersion",1).put("exportId",UUID.randomUUID().toString()).put("sourceConversationId",conversation).put("sourceGeneration",gen).put("historyWatermark",upper).put("exportedAt",System.currentTimeMillis()).put("title",header.optString("title")).put("messages",messages).put("omitted",omitted).put("attachmentReferences",attachments);
        };
        JSONObject payload=readOnlyCopy?read(capture):transaction(capture);
        JSONObject rendered=ConversationTransfer.render(payload,format);
        if(readOnlyCopy)rendered.put("snapshotSemantics","verified_private_read_only_database_copy_no_new_tail");
        return rendered;
    }
    /** Import only display/reference content with new IDs. Old ACKs and source grants are not accepted. */
    public JSONObject importConversation(JSONObject input)throws Exception{
        ConversationOrganization.fields(input,"actionId","exportJson","sha256");String action=input.getString("actionId");if(!action.matches("[a-fA-F0-9-]{36}"))throw new IllegalArgumentException("导入需要固定actionId");
        Object supplied=input.opt("exportJson");if(!(supplied instanceof String))throw new IllegalArgumentException("导入需要UTF-8 JSON正文");byte[] bytes=ConversationTransfer.utf8((String)supplied);String sha=ConversationTransfer.sha(bytes);if(!sha.equals(input.getString("sha256")))throw new SecurityException("所选导出字节SHA已变化");JSONObject payload=ConversationTransfer.checked(bytes);
        return transaction(()->{
            String gen=activeGeneration(),conversation=UUID.nameUUIDFromBytes((gen+":conversation-import:"+action).getBytes(StandardCharsets.UTF_8)).toString();
            try(Cursor prior=db.rawQuery("SELECT deleted,header_json FROM assistant_conversations WHERE generation=? AND conversation_id=?",new String[]{gen,conversation})){if(prior.moveToFirst()){
                JSONObject header=new JSONObject(prior.getString(1));if(prior.getInt(0)!=0)throw new IllegalStateException("此导入结果已删除，未恢复旧内容");if(!sha.equals(header.optString("sourceExportSha256")))throw new SecurityException("同actionId不能导入不同内容");return new JSONObject().put("conversationId",conversation).put("duplicate",true).put("sourceExportId",payload.getString("exportId")).put("executionAuthorized",false);
            }}
            String title="导入 "+payload.getString("title");if(title.length()>120)title=title.substring(0,119);if(!StandardCharsets.UTF_8.newEncoder().canEncode(title))title=title.substring(0,title.length()-1);
            JSONObject header=new JSONObject().put("conversationId",conversation).put("title",title).put("createdAt",System.currentTimeMillis()).put("updatedAt",System.currentTimeMillis()).put("sourceExportId",payload.getString("exportId")).put("sourceExportSha256",sha).put("sourceExportConversationId",payload.getString("sourceConversationId")).put("importPolicy","content_only_requires_explicit_reference_selection").put("historicalIdentityVerified",false);
            JSONArray imported=new JSONArray(),original=payload.getJSONArray("messages");Set<String> retainedIds=new HashSet<>();for(int i=0;i<original.length();i++)retainedIds.add(original.getJSONObject(i).getString("sourceRecordId"));
            Map<String,JSONArray> passiveAttachments=new HashMap<>(),passiveOmissions=new HashMap<>();int unmatchedAttachments=0,unmatchedOmissions=0;
            JSONArray referenceRows=payload.optJSONArray("attachmentReferences");for(int i=0;referenceRows!=null&&i<referenceRows.length();i++){JSONObject ref=referenceRows.getJSONObject(i);String id=ref.getString("messageId");if(retainedIds.contains(id))passiveAttachments.computeIfAbsent(id,key->new JSONArray()).put(ref);else unmatchedAttachments++;}
            JSONArray omittedRows=payload.getJSONArray("omitted");for(int i=0;i<omittedRows.length();i++){JSONObject omitted=omittedRows.getJSONObject(i);String id=omitted.getString("sourceRecordId");if(retainedIds.contains(id))passiveOmissions.computeIfAbsent(id,key->new JSONArray()).put(omitted);else unmatchedOmissions++;}
            for(int i=0;i<original.length();i++){
                JSONObject row=original.getJSONObject(i),origin=new JSONObject(row.toString());origin.remove("content");String id=row.getString("sourceRecordId");
                // These are passive claims from the selected, checked export. They never use
                // attachments/ownerSourceRefs/ownerPreviewRefs keys and cannot restore bytes or source grants.
                JSONObject evidence=new JSONObject().put("exportId",payload.getString("exportId")).put("exportSha256",sha).put("attachmentReferences",passiveAttachments.getOrDefault(id,new JSONArray())).put("omitted",passiveOmissions.getOrDefault(id,new JSONArray())).put("unmatchedAttachmentReferences",unmatchedAttachments).put("unmatchedOmissions",unmatchedOmissions);
                JSONObject content=new JSONObject().put("role",row.getString("role")).put("content",row.getString("content")).put("originExportRecord",origin).put("originExportEvidence",evidence);if(row.has("at"))content.put("at",row.getLong("at"));imported.put(content);
            }
            byte[] normalized=ConversationTransfer.utf8(new JSONArray().put(new JSONObject(header.toString()).put("messages",imported)).toString());String normalizedSha=shaBytes(normalized);LegacyConversationImport.Conversation indexed=LegacyConversationImport.index(normalized,normalizedSha).get(0);
            saveConversation(gen,header);ContentValues doc=new ContentValues();doc.put("generation",gen);doc.put("source_sha",normalizedSha);doc.put("source_bytes",normalized.length);doc.put("source_format","conversation_export_v1");db.insertOrThrow("assistant_import_documents",null,doc);
            int offset=0,index=0;for(LegacyConversationImport.Range range:indexed.messages){String record="import-message:"+UUID.randomUUID();fragment(gen,normalizedSha,conversation,null,normalized,offset,range.start);fragment(gen,normalizedSha,conversation,record,normalized,range.start,range.end);ContentValues row=new ContentValues();row.put("generation",gen);row.put("record_id",record);row.put("conversation_id",conversation);row.put("sequence",++index);row.put("record_version",1);row.putNull("historical_revision");if(imported.getJSONObject(index-1).has("at"))row.put("at_ms",imported.getJSONObject(index-1).getLong("at"));else row.putNull("at_ms");row.put("role",imported.getJSONObject(index-1).getString("role"));row.put("source_sha",normalizedSha);row.put("byte_offset",range.start);row.put("byte_length",range.length());row.put("fragment_sha",shaBytes(LegacyConversationImport.bytes(normalized,range)));db.insertOrThrow("assistant_import_records",null,row);offset=range.end;}
            fragment(gen,normalizedSha,conversation,null,normalized,offset,normalized.length);
            return new JSONObject().put("conversationId",conversation).put("duplicate",false).put("sourceExportId",payload.getString("exportId")).put("sourceExportSha256",sha).put("importedMessages",imported.length()).put("attachmentsRestored",false).put("importPolicy","content_only_requires_explicit_reference_selection").put("executionAuthorized",false);
        });
    }
    /** Stateless history derivation only. Stateful/remote backend forks retain their existing owner route. */
    public JSONObject forkConversation(JSONObject input,java.util.function.BooleanSupplier nativeParentBusy)throws Exception{
        ConversationOrganization.fields(input,"actionId","parentConversationId","messageId","messageRevision","historyWatermark","mode","workspacePolicy");
        String mode=input.getString("mode"),workspace=input.getString("workspacePolicy");
        if(!"history_derived".equals(mode)||!"no_project".equals(workspace))return new JSONObject().put("status","unsupported").put("reason","use_existing_backend_fork_or_explicit_project_copy_workflow").put("supportedMode","history_derived").put("supportedWorkspacePolicy","no_project").put("executionAuthorized",false);
        String action=input.getString("actionId"),parent=input.getString("parentConversationId"),anchor=input.getString("messageId");if(!action.matches("[a-fA-F0-9-]{36}"))throw new IllegalArgumentException("分支需要固定actionId");long version=ConversationOrganization.integer(input,"messageRevision",1,Long.MAX_VALUE-1),watermark=ConversationOrganization.integer(input,"historyWatermark",1,Long.MAX_VALUE-1);
        String fingerprint=ConversationTransfer.sha((parent+"\0"+anchor+"\0"+version+"\0"+watermark+"\0"+mode+"\0"+workspace).getBytes(StandardCharsets.UTF_8));
        return transaction(()->{
            String gen=activeGeneration(),child=UUID.nameUUIDFromBytes((gen+":conversation-import:"+action).getBytes(StandardCharsets.UTF_8)).toString();
            try(Cursor prior=db.rawQuery("SELECT deleted,header_json FROM assistant_conversations WHERE generation=? AND conversation_id=?",new String[]{gen,child})){if(prior.moveToFirst()){JSONObject old=new JSONObject(prior.getString(1));if(prior.getInt(0)!=0)throw new IllegalStateException("此分支结果已删除");if(!fingerprint.equals(old.optString("forkActionFingerprint")))throw new SecurityException("同actionId不能改变分支范围");return forkResult(child,old).put("duplicate",true);}}
            if(nativeParentBusy.getAsBoolean())throw new IllegalStateException("原会话仍在回复，请等待后重新选择分支锚点");
            // A cold-resumed original task may run without AssistantModule.activeTurn.
            // Inspect the original live-run rows while holding its same database monitor.
            int activeRows=0;try(Cursor running=dev.pocketengine.store.TaskDocumentCursor.tasks(db,"SELECT t.document FROM tasks t JOIN runs r ON r.task_id=t.task_id WHERE r.status='running' LIMIT 101","SELECT t.task_id AS document FROM tasks t JOIN runs r ON r.task_id=t.task_id WHERE r.status='running' LIMIT 101",null,0)){
                while(running.moveToNext()){if(++activeRows>100)throw new ReadLimited("conversation_fork_live_run_budget");String raw=running.getString(0);if(raw.length()>MAX_BYTES)throw new ReadLimited("conversation_fork_live_task_metadata_budget");if(parent.equals(new JSONObject(raw).optString("conversationId")))throw new IllegalStateException("原会话仍有执行中的任务，不能选择变化中的分支锚点");}
            }
            JSONObject state=metadata(anchor);if(state==null||state.optBoolean("deleted")||!parent.equals(state.optString("conversationId"))||state.getLong("revision")!=version||state.optLong("memorySourceRetiredThrough")>=version||state.optBoolean("memoryContextRetired"))throw new SecurityException("分支锚点已变化、停用或不属于原会话");
            JSONObject exported=exportConversation(new JSONObject().put("conversationId",parent).put("format","json"));JSONObject payload=ConversationTransfer.checked(exported.getString("content").getBytes(StandardCharsets.UTF_8));if(payload.getLong("historyWatermark")!=watermark)throw new IllegalStateException("分支历史水位已变化，请重新选择");
            long boundary=-1;String anchorSha="";JSONArray original=payload.getJSONArray("messages");for(int i=0;i<original.length();i++){JSONObject row=original.getJSONObject(i);if(anchor.equals(row.getString("sourceRecordId"))&&version==row.getLong("sourceRecordVersion")){boundary=row.getLong("sequence");anchorSha=row.getString("contentSha256");}}if(boundary<0)throw new SecurityException("分支锚点没有可核原文");
            JSONArray selected=new JSONArray();for(int i=0;i<original.length();i++)if(original.getJSONObject(i).getLong("sequence")<=boundary)selected.put(original.getJSONObject(i));
            Set<String> prefix=new HashSet<>();try(Cursor rows=db.rawQuery("SELECT source_id FROM assistant_messages WHERE generation=? AND conversation_id=? AND sequence<=? UNION ALL SELECT record_id FROM assistant_import_records WHERE generation=? AND conversation_id=? AND sequence<=?",new String[]{gen,parent,Long.toString(boundary),gen,parent,Long.toString(boundary)})){while(rows.moveToNext())prefix.add(rows.getString(0));}
            JSONArray omissions=new JSONArray(),attachmentRefs=new JSONArray();for(int i=0;i<payload.getJSONArray("omitted").length();i++){JSONObject row=payload.getJSONArray("omitted").getJSONObject(i);if(prefix.contains(row.getString("sourceRecordId")))omissions.put(row);}for(int i=0;i<payload.getJSONArray("attachmentReferences").length();i++){JSONObject row=payload.getJSONArray("attachmentReferences").getJSONObject(i);if(prefix.contains(row.getString("messageId")))attachmentRefs.put(row);}
            payload.put("messages",selected).put("omitted",omissions).put("attachmentReferences",attachmentRefs).put("historyWatermark",boundary);String json=payload.toString();JSONObject imported=importConversation(new JSONObject().put("actionId",action).put("exportJson",json).put("sha256",ConversationTransfer.sha(ConversationTransfer.utf8(json))));
            JSONObject provenance=new JSONObject().put("mode","history_derived").put("parentConversationId",parent).put("parentGeneration",gen).put("messageId",anchor).put("messageRevision",version).put("bodyFingerprint","sha256:"+anchorSha).put("sourceHistoryWatermark",watermark).put("derivedThroughSequence",boundary).put("workspacePolicy","no_project").put("backendForkPerformed",false);
            JSONArray references=new JSONArray();int chars=0;boolean supported=true;for(int i=0;i<selected.length();i++){JSONObject row=selected.getJSONObject(i);int length=row.getString("content").length();if(length==0)continue;if(!"native_original".equals(row.getString("sourceKind"))||references.length()>=OwnerSourceReferences.MAX_REFERENCES||length>OwnerSourceReferences.MAX_RANGE_CHARS||chars+length>OwnerSourceReferences.MAX_TOTAL_CHARS){supported=false;break;}chars+=length;references.put(new JSONObject().put("generation",gen).put("sourceConversationId",parent).put("sourceRef",new SourceRef("message",row.getString("sourceRecordId"),row.getLong("sourceRecordVersion"),"owner","private").toJson()).put("bodyFingerprint","sha256:"+row.getString("contentSha256")).put("offset",0).put("chars",length).put("unit","UTF-16"));}
            JSONObject header=header(child);header.put("forkProvenance",provenance).put("forkActionFingerprint",fingerprint).put("forkReferenceStatus",supported?"available_for_original_composer_selection":"requires_explicit_range_selection").put("forkOwnerSourceRefs",supported?OwnerSourceReferences.checked(references):new JSONArray());saveConversation(header);
            return forkResult(child,header).put("duplicate",false).put("importedMessages",imported.getInt("importedMessages"));
        });
    }
    private static JSONObject forkResult(String conversation,JSONObject header)throws Exception{return new JSONObject().put("status","created").put("conversationId",conversation).put("forkProvenance",header.getJSONObject("forkProvenance")).put("contextReferenceStatus",header.getString("forkReferenceStatus")).put("ownerSourceRefs",header.getJSONArray("forkOwnerSourceRefs")).put("contextAppliedToModel",false).put("referencesRequireCurrentness",true).put("executionAuthorized",false);}
    private void reloadAuthority(){try(Cursor row=db.rawQuery("SELECT (SELECT value FROM meta WHERE key='assistant_history_authority'),(SELECT value FROM meta WHERE key='assistant_history_generation')",null)){
        row.moveToFirst();String actual=row.isNull(0)?null:new JSONObject(row.getString(0)).getString("generation"),typed=row.isNull(1)?null:row.getString(1);
        if(!Objects.equals(actual,typed)||actual!=null&&actual.isEmpty())throw new IllegalStateException("原文权威的typed generation不一致");generation=actual;
    }catch(Exception bad){healthy=false;throw new IllegalStateException("原始历史权威状态无法恢复，未回退旧文件",bad);}}
    public boolean active(){if(!healthy)throw new IllegalStateException("原始历史存储结果不确定，已停止读取/写入");return generation!=null;}
    private String activeGeneration(){if(!active())throw new IllegalStateException("旧对话仍待主人明确迁移；未切换原文权威");return generation;}
    public <T>T read(Callable<T> action)throws Exception{synchronized(monitor){activeGeneration();return action.call();}}
    public <T>T transaction(Callable<T> action)throws Exception{synchronized(monitor){activeGeneration();db.beginTransaction();try{T value=action.call();db.setTransactionSuccessful();return value;}finally{try{db.endTransaction();}catch(Exception uncertain){healthy=false;throw uncertain;}}}}
    public void noteLegacyFilePresent(){synchronized(monitor){ContentValues value=new ContentValues();value.put("value","0");db.update("meta",value,"key='assistant_history_new_install'",null);}}
    public void recordImportProvenance(JSONObject facts)throws Exception{
        if(!Thread.holdsLock(monitor)||!db.inTransaction())throw new SecurityException("导入来源条件只能随原激活事务保存");JSONObject marker=authority().put("originalImportCondition",facts.getString("sourceCondition")).put("originalImportSourceBytes",facts.getLong("sourceBytes")).put("legacyImportPerformed",!"absent".equals(facts.getString("sourceCondition"))).put("preVersion2ProvenanceKnown",false);ContentValues value=new ContentValues();value.put("value",marker.toString());db.update("meta",value,"key='assistant_history_authority'",null);
    }
    public boolean newInstallation(){synchronized(monitor){try(Cursor row=db.rawQuery("SELECT 1 FROM meta WHERE key='assistant_history_new_install' AND value='1' LIMIT 1",null)){return row.moveToFirst();}}}
    public void initializeEmpty()throws Exception{synchronized(monitor){if(active())return;if(!newInstallation())throw new IllegalStateException("已有安装缺原文件，不能静默当作空新安装");String next=UUID.randomUUID().toString();db.beginTransaction();boolean committing=false;try{publishAuthority(next,"",0,0);db.setTransactionSuccessful();committing=true;}finally{try{db.endTransaction();}catch(Exception uncertain){healthy=false;throw uncertain;}}if(committing)generation=next;}}
    private void publishAuthority(String next,String sourceSha,int messages,int sources)throws Exception{ContentValues value=new ContentValues();value.put("key","assistant_history_authority");value.put("value",new JSONObject().put("generation",next).put("legacySourceSha256",sourceSha).put("importedMessages",messages).put("importedSources",sources).put("legacyLossNotRecovered",!sourceSha.isEmpty()).put("legacyCleanupPending",!sourceSha.isEmpty()).toString());db.insertWithOnConflict("meta",null,value,SQLiteDatabase.CONFLICT_REPLACE);ContentValues typed=new ContentValues();typed.put("key","assistant_history_generation");typed.put("value",next);db.insertWithOnConflict("meta",null,typed,SQLiteDatabase.CONFLICT_REPLACE);}
    private static String historyScopeKey(String generation,String conversation){return "assistant.history_read_scope."+generation+":"+conversation;}
    private final class NativeHistoryReadScope implements ConversationHistory.ReadScope {
        final ConversationRepository issuer=ConversationRepository.this;final String generation,conversation;final ConversationHistory.ScopeMode mode;final long revision;
        NativeHistoryReadScope(String gen,String cid,ConversationHistory.ScopeMode mode,long revision){generation=gen;conversation=cid;this.mode=mode;this.revision=revision;}
        public String conversationId(){return conversation;}public ConversationHistory.ScopeMode mode(){return mode;}public long revision(){return revision;}
        public void checkCurrent()throws Exception{
            if(!healthy||!generation.equals(activeGeneration()))throw new SecurityException("原历史读取权威已变化");
            NativeHistoryReadScope current=historyReadScope(conversation);
            if(current.revision!=revision||current.mode!=mode||!current.generation.equals(generation))throw new SecurityException("主人已修改或关闭本轮跨会话读取范围");
        }
    }
    /** Metadata SQL only. A missing setting means CURRENT revision zero, never owner-wide consent. */
    private NativeHistoryReadScope historyReadScope(String conversation)throws Exception{
        if(conversation==null||!dev.pocketengine.features.memory.TaskStoreMemoryQuery.validTaskId(conversation))throw new SecurityException("历史范围缺当前原会话");
        String gen=activeGeneration();JSONObject stored=null;
        try(Cursor row=db.rawQuery("SELECT m.value FROM assistant_conversations c LEFT JOIN meta m ON m.key=? WHERE c.generation=? AND c.conversation_id=? AND c.deleted=0 LIMIT 1",new String[]{historyScopeKey(gen,conversation),gen,conversation})){
            if(!row.moveToFirst())throw new SecurityException("原会话已不存在");if(!row.isNull(0)){String raw=row.getString(0);if(raw.length()>1024)throw new SecurityException("原历史范围配置不可读");stored=new JSONObject(raw);}
        }
        if(stored==null)return new NativeHistoryReadScope(gen,conversation,ConversationHistory.ScopeMode.CURRENT,0);
        if(stored.length()!=5||!"owner".equals(stored.optString("ownerId"))||!"private".equals(stored.optString("spaceId"))||!conversation.equals(stored.optString("conversationId")))throw new SecurityException("原历史范围主体不符");
        long revision=integer(stored,"revision",1);ConversationHistory.ScopeMode mode;
        try{mode=ConversationHistory.ScopeMode.valueOf(stored.getString("mode"));}catch(Exception unknown){throw new SecurityException("原历史范围模式未知");}
        return new NativeHistoryReadScope(gen,conversation,mode,revision);
    }
    public ConversationHistory.ReadScope captureHistoryReadScope(String conversation)throws Exception{return historyReadScope(conversation);}
    public JSONObject historyReadScopeSnapshot(String conversation)throws Exception{
        NativeHistoryReadScope value=historyReadScope(conversation);return new JSONObject().put("conversationId",conversation).put("ownerId","owner").put("spaceId","private")
                .put("mode",value.mode.name()).put("revision",value.revision).put("canEnableOwnerPrivate",true).put("bodyIncluded",false).put("executionAuthorized",false);
    }
    public JSONObject setHistoryReadScope(String conversation,long expected,ConversationHistory.ScopeMode mode)throws Exception{
        if(mode==null||expected<0)throw new SecurityException("历史范围配置参数无效");
        return transaction(()->{NativeHistoryReadScope old=historyReadScope(conversation);if(old.revision!=expected)throw new SecurityException("历史范围已变化，请刷新后确认");long revision=Math.addExact(old.revision,1);
            JSONObject value=new JSONObject().put("conversationId",conversation).put("ownerId","owner").put("spaceId","private").put("mode",mode.name()).put("revision",revision);
            ContentValues row=new ContentValues();row.put("key",historyScopeKey(old.generation,conversation));row.put("value",value.toString());db.insertWithOnConflict("meta",null,row,SQLiteDatabase.CONFLICT_REPLACE);
            return historyReadScopeSnapshot(conversation);
        });
    }
    public JSONObject queryHistoryConversations(ConversationHistory.ReadScope supplied,JSONObject options,RunControl control)throws Exception{
        if(!(supplied instanceof ConversationRepository.NativeHistoryReadScope))throw new SecurityException("不是原主人历史范围");NativeHistoryReadScope scope=(NativeHistoryReadScope)supplied;
        if(scope.issuer!=this)throw new SecurityException("历史范围来自其他原文权威");scope.checkCurrent();control.check();
        int limit=options.getInt("limit");if(limit<1||limit>20||!options.optString("query").isEmpty())throw new IllegalArgumentException("会话目录只接受有界分页，不把标题或正文作为目录");
        String cursor=options.optString("cursor");if(cursor.length()>4000)throw new IllegalArgumentException("原会话目录游标过大");JSONObject at=decode(cursor);
        String signature=TaskMemoryTrace.rawFingerprint(new JSONObject().put("callerConversationId",scope.conversation).put("scopeRevision",scope.revision).put("mode",scope.mode.name()).put("generation",scope.generation));
        if(!cursor.isEmpty()&&!signature.equals(at.optString("signature")))throw new SecurityException("原会话目录游标范围已变化");
        String where="generation=? AND deleted=0";List<String> args=new ArrayList<>();args.add(scope.generation);
        if(scope.mode==ConversationHistory.ScopeMode.CURRENT){where+=" AND conversation_id=?";args.add(scope.conversation);}
        if(at.has("at")){where+=" AND (updated_at<? OR (updated_at=? AND conversation_id<?))";args.add(at.get("at").toString());args.add(at.get("at").toString());args.add(at.getString("id"));}
        JSONArray entries=new JSONArray();String last="";long lastAt=0;boolean more=false;
        try(Cursor rows=db.rawQuery("SELECT conversation_id,updated_at FROM assistant_conversations WHERE "+where+" ORDER BY updated_at DESC,conversation_id DESC LIMIT ?",append(args,Integer.toString(limit+1)))){
            while(rows.moveToNext()){control.check();if(entries.length()>=limit){more=true;break;}String id=rows.getString(0);long time=rows.getLong(1);if(!dev.pocketengine.features.memory.TaskStoreMemoryQuery.validTaskId(id)||time<0)throw new SecurityException("原会话目录身份不可读");
                entries.put(new JSONObject().put("sourceKind","assistant_conversation").put("sourceConversationId",id).put("updatedAt",time).put("metadataOnly",true).put("originalBodyIncluded",false));last=id;lastAt=time;}
        }
        scope.checkCurrent();control.check();String next=more?encode(new JSONObject().put("signature",signature).put("at",lastAt).put("id",last)):"";
        return ConversationHistory.proof(new JSONObject().put("status","current").put("entries",entries).put("gaps",new JSONArray()).put("hasMore",more).put("nextCursor",next)
                .put("bodyIncluded",false).put("historyScopeMode",scope.mode.name()).put("historyScopeRevision",scope.revision).put("scopeFilteredBeforeLimit",true));
    }
    public JSONObject authority()throws Exception{synchronized(monitor){try(Cursor row=db.rawQuery("SELECT value FROM meta WHERE key='assistant_history_authority' LIMIT 1",null)){return row.moveToFirst()?new JSONObject(row.getString(0)):new JSONObject().put("status","legacy_pending_explicit_migration");}}}
    /** Typed per-ID native metadata; no source_json or message body is loaded. */
    public JSONObject metadata(String id)throws Exception{
        try(Cursor row=db.rawQuery("SELECT s.revision,s.conversation_id,s.role,s.deleted,s.unavailable_reason,s.retired_through,s.retirement_reason,s.context_retired,coalesce(c.deleted,1),coalesce(c.delete_reason,'unknown') FROM assistant_sources s LEFT JOIN assistant_conversations c ON c.generation=s.generation AND c.conversation_id=s.conversation_id WHERE s.generation=? AND s.source_id=? LIMIT 1",new String[]{activeGeneration(),id})){
            if(!row.moveToFirst())return null;return new JSONObject().put("sourceId",id).put("revision",row.getLong(0)).put("conversationId",row.getString(1)).put("role",row.getString(2)).put("deleted",row.getInt(3)!=0||row.getInt(8)!=0).put("unavailableReason",row.getInt(3)!=0?row.getString(4):row.getInt(8)!=0?row.getString(9):"").put("memorySourceRetiredThrough",row.getLong(5)).put("retirementReason",row.getString(6)).put("memoryContextRetired",row.getInt(7)!=0);
        }
    }
    /** Explicit owner archive page. Native sources and import-assigned original fragments
     * share this bounded capture, but imported records never become SourceRefs or author proof.
     * Both original-table rowid high bounds and this generation are pinned before pagination. */
    public JSONObject ownerExperiencePage(String cursor,dev.pocketengine.store.OwnerExperienceProjection.Gate gate,RunControl control)throws Exception {
        return read(()->{
            control.checkpoint();String gen=activeGeneration();JSONObject position=decode(cursor);String phase=position.optString("phase","native");
            if(!Set.of("native","imported").contains(phase)||!cursor.isEmpty()&&!gen.equals(position.optString("generation")))throw new SecurityException("原聊天归档generation或分页类型已变化");
            long importedUpper;if(cursor.isEmpty())try(Cursor row=db.rawQuery("SELECT coalesce(max(rowid),0) FROM assistant_import_records WHERE generation=?",new String[]{gen})){row.moveToFirst();importedUpper=row.getLong(0);}else importedUpper=integer(position,"importedUpperRow",0);
            if(phase.equals("imported"))return ownerImportedExperiencePage(gen,position.optString("lastImported"),importedUpper,control);
            String last=position.optString("last"),upper=position.optString("upper");
            if(cursor.isEmpty())try(Cursor row=db.rawQuery("SELECT coalesce(max(source_id),'') FROM assistant_sources WHERE generation=?",new String[]{gen})){row.moveToFirst();upper=row.getString(0);}
            JSONArray entries=new JSONArray(),gaps=new JSONArray();boolean nativeMore=false;String next=last;long upperRow=position.has("upperRow")?position.getLong("upperRow"):0;if(cursor.isEmpty())try(Cursor row=db.rawQuery("SELECT coalesce(max(rowid),0) FROM assistant_sources WHERE generation=?",new String[]{gen})){row.moveToFirst();upperRow=row.getLong(0);}
            try(Cursor rows=db.rawQuery("SELECT s.source_id,s.revision,s.conversation_id,s.role,s.deleted,s.retired_through,s.context_retired,coalesce(c.deleted,1),m.body_sha,m.body_bytes,length(CAST(m.meta_json AS BLOB)) FROM assistant_sources s LEFT JOIN assistant_conversations c ON c.generation=s.generation AND c.conversation_id=s.conversation_id LEFT JOIN assistant_messages m ON m.generation=s.generation AND m.source_id=s.source_id WHERE s.generation=? AND s.source_id>? AND s.source_id<=? AND s.rowid<=CAST(? AS INTEGER) ORDER BY s.source_id LIMIT 21",new String[]{gen,last,upper,Long.toString(upperRow)})){
                while(rows.moveToNext()){
                    control.checkpoint();if(entries.length()+gaps.length()>=20){nativeMore=true;break;}String id=rows.getString(0);next=id;SourceRef ref=new SourceRef("message",id,rows.getLong(1),"owner","private");
                    if(rows.getInt(4)!=0||rows.getLong(5)>=ref.revision||rows.getInt(6)!=0||rows.getInt(7)!=0||gate.blocked(ref)||rows.isNull(8)||rows.getLong(9)+rows.getLong(10)>MAX_BYTES){gaps.put(new JSONObject().put("sourceRef",ref.toJson()).put("reason","deleted_retired_forgotten_missing_or_read_limited"));continue;}
                    JSONObject actual=message(rows.getString(2),id);if(actual==null)throw new SecurityException("原消息归档期间正文不可读");String text=actual.getString("content"),hash=shaBytes(text.getBytes(StandardCharsets.UTF_8));if(!("sha256:"+hash).equals(rows.getString(8))||text.getBytes(StandardCharsets.UTF_8).length!=rows.getLong(9))throw new SecurityException("原消息hash或字节数损坏");
                    JSONObject archived=new JSONObject().put("kind","message").put("ownerId","owner").put("spaceId","private").put("sourceRef",ref.toJson()).put("conversationId",rows.getString(2)).put("role",rows.getString(3)).put("text",text).put("sha256",hash).put("bytes",rows.getLong(9));
                    for(String key:List.of("at","editedAt","attachments"))if(actual.has(key))archived.put(key,actual.get(key));entries.put(archived);
                }
            }
            if(!nativeMore&&entries.length()+gaps.length()==0&&importedUpper>0)return ownerImportedExperiencePage(gen,"",importedUpper,control);
            boolean more=nativeMore||importedUpper>0;JSONObject nextPosition=nativeMore?new JSONObject().put("phase","native").put("generation",gen).put("upper",upper).put("upperRow",upperRow).put("last",next).put("importedUpperRow",importedUpper):new JSONObject().put("phase","imported").put("generation",gen).put("lastImported","").put("importedUpperRow",importedUpper);
            control.checkpoint();return new JSONObject().put("entries",entries).put("gaps",gaps).put("hasMore",more).put("nextCursor",more?encode(nextPosition):"").put("generation",gen).put("snapshotHighSourceId",upper).put("importedSnapshotUpperRow",importedUpper);
        });
    }
    private JSONObject ownerImportedExperiencePage(String gen,String last,long upper,RunControl control)throws Exception {
        JSONArray entries=new JSONArray(),gaps=new JSONArray();String next=last;boolean more=false;long used=0;
        try(Cursor rows=db.rawQuery("SELECT r.record_id,r.record_version,r.conversation_id,r.historical_revision,r.at_ms,r.role,r.source_sha,r.byte_offset,r.byte_length,r.fragment_sha,r.deleted,coalesce(c.deleted,1),f.record_id,f.conversation_id,f.byte_length,length(f.data),d.source_bytes,d.source_format FROM assistant_import_records r LEFT JOIN assistant_conversations c ON c.generation=r.generation AND c.conversation_id=r.conversation_id LEFT JOIN assistant_import_fragments f ON f.generation=r.generation AND f.source_sha=r.source_sha AND f.byte_offset=r.byte_offset LEFT JOIN assistant_import_documents d ON d.generation=r.generation AND d.source_sha=r.source_sha WHERE r.generation=? AND r.record_id>? AND r.rowid<=CAST(? AS INTEGER) ORDER BY r.record_id LIMIT 21",new String[]{gen,last,Long.toString(upper)})){
            while(rows.moveToNext()){
                control.checkpoint();if(entries.length()+gaps.length()>=20){more=true;break;}
                String id=rows.getString(0);long length=rows.getLong(8);JSONObject locator=new JSONObject().put("recordId",id).put("recordVersion",rows.getLong(1)).put("conversationId",rows.getString(2)).put("sourceSha256",rows.getString(6)).put("offset",rows.getLong(7)).put("length",length).put("fragmentSha256",rows.getString(9));
                String sourceFormat=rows.getString(17);if("conversation_export_v1".equals(sourceFormat))locator.put("sourceFormat",sourceFormat);else if(!"legacy_array".equals(sourceFormat))throw new SecurityException("导入原件格式未知或缺失，未生成错误来源的档案");
                ImportedExperienceEvidence.checkedLocator(locator);
                if(rows.getInt(10)!=0||rows.getInt(11)!=0){next=id;gaps.put(new JSONObject().put("importedLocator",locator).put("reason","imported_record_deleted").put("originalBodyIncluded",false));continue;}
                if(length>MAX_BYTES){next=id;gaps.put(new JSONObject().put("importedLocator",locator).put("reason","imported_original_exceeds_archive_page_budget").put("originalBodyIncluded",false));continue;}
                if(rows.isNull(12)||rows.isNull(15)||!id.equals(rows.getString(12))||!rows.getString(2).equals(rows.getString(13))||length!=rows.getLong(14)||length!=rows.getLong(15)||rows.isNull(16)||rows.getLong(7)>rows.getLong(16)-length)throw new SecurityException("旧导入原件段缺失或原记录定位不符，未生成空正文的成功归档");
                if(length>MAX_BYTES-used){more=true;break;}byte[] bytes;try(Cursor fragment=db.rawQuery("SELECT data FROM assistant_import_fragments WHERE generation=? AND source_sha=? AND byte_offset=? AND record_id=? AND data IS NOT NULL LIMIT 1",new String[]{gen,rows.getString(6),Long.toString(rows.getLong(7)),id})){if(!fragment.moveToFirst())throw new SecurityException("旧导入原件段在读取期间缺失");bytes=fragment.getBlob(0);}
                next=id;used+=bytes.length;
                try{entries.put(ImportedExperienceEvidence.passiveRow(locator,bytes,rows.isNull(3)?null:rows.getLong(3),rows.isNull(4)?null:rows.getLong(4),rows.getString(5)));}
                catch(ImportedExperienceEvidence.ProtectedFields protectedFields){gaps.put(new JSONObject().put("importedLocator",locator).put("reason","protected_imported_fields_excluded").put("originalBodyIncluded",false));}
            }
        }
        control.checkpoint();return new JSONObject().put("entries",entries).put("gaps",gaps).put("hasMore",more).put("nextCursor",more?encode(new JSONObject().put("phase","imported").put("generation",gen).put("lastImported",next).put("importedUpperRow",upper)):"").put("generation",gen).put("importedSnapshotUpperRow",upper).put("importedIdentitiesAreNotSourceRefs",true).put("materializedOriginalFragmentBytes",used);
    }
    /** Any known original import deletion/version/hash change wins over an older ZIP. Unknown
     * on a new instance remains explicitly archive-only, never a restored source permission. */
    public boolean ownerImportedExperienceBlocked(JSONObject input)throws Exception {
        if(!healthy)throw new SecurityException("原导入删除状态不确定，未回读旧档案正文");JSONObject pin=ImportedExperienceEvidence.checkedLocator(input);if(!active())return false;String gen=activeGeneration();
        try(Cursor rows=db.rawQuery("SELECT r.record_version,r.conversation_id,r.source_sha,r.byte_offset,r.byte_length,r.fragment_sha,r.deleted,coalesce(c.deleted,1),length(f.data),CASE WHEN length(f.data)<=1048576 THEN f.data END,d.source_format FROM assistant_import_records r LEFT JOIN assistant_conversations c ON c.generation=r.generation AND c.conversation_id=r.conversation_id LEFT JOIN assistant_import_fragments f ON f.generation=r.generation AND f.source_sha=r.source_sha AND f.byte_offset=r.byte_offset LEFT JOIN assistant_import_documents d ON d.generation=r.generation AND d.source_sha=r.source_sha WHERE r.generation=? AND r.record_id=? LIMIT 1",new String[]{gen,pin.getString("recordId")})){
            while(rows.moveToNext()){if(rows.getLong(0)!=pin.getLong("recordVersion")||!rows.getString(1).equals(pin.getString("conversationId"))||!rows.getString(2).equals(pin.getString("sourceSha256"))||rows.getLong(3)!=pin.getLong("offset")||rows.getLong(4)!=pin.getLong("length")||!rows.getString(5).equals(pin.getString("fragmentSha256"))||rows.getInt(6)!=0||rows.getInt(7)!=0||rows.isNull(8)||rows.getLong(8)!=pin.getLong("length")||rows.isNull(9)||!shaBytes(rows.getBlob(9)).equals(pin.getString("fragmentSha256"))||!pin.optString("sourceFormat","legacy_array").equals(rows.getString(10)))return true;}
        }return false;
    }
    /** Local deletion/retirement is negative evidence even when an old ZIP still has the bytes. */
    public boolean ownerExperienceBlocked(SourceRef ref)throws Exception {
        if(!active())return false;JSONObject state=metadata(ref.sourceId);if(state==null)return false;
        if(state.getBoolean("deleted")||state.optLong("memorySourceRetiredThrough")>=ref.revision||state.optBoolean("memoryContextRetired"))return true;
        try{requireDerivedMessageCurrent(ref.sourceId,ref.revision);return false;}catch(SourceUnavailable unavailable){return true;}
    }
    public JSONObject source(String id)throws Exception{return source(activeGeneration(),id);}
    private JSONObject source(String gen,String id)throws Exception{
        try(Cursor size=db.rawQuery("SELECT length(CAST(source_json AS BLOB)) FROM assistant_sources WHERE generation=? AND source_id=? LIMIT 1",new String[]{gen,id})){if(size.moveToFirst()&&size.getLong(0)>MAX_BYTES)throw new IllegalStateException("单条来源metadata超过本次读取预算");}
        try(Cursor row=db.rawQuery("SELECT s.source_json,s.revision,s.deleted,s.unavailable_reason,s.retired_through,s.retirement_reason,s.context_retired,coalesce(c.deleted,1),coalesce(c.delete_reason,'unknown') FROM assistant_sources s LEFT JOIN assistant_conversations c ON c.generation=s.generation AND c.conversation_id=s.conversation_id WHERE s.generation=? AND s.source_id=? LIMIT 1",new String[]{gen,id})){
            if(!row.moveToFirst())return null;JSONObject value=new JSONObject(row.getString(0));boolean deleted=row.getInt(2)!=0||row.getInt(7)!=0;
            value.put("revision",row.getLong(1)).put("deleted",deleted).put("unavailableReason",row.getInt(2)!=0?row.getString(3):row.getInt(7)!=0?row.getString(8):"").put("memorySourceRetiredThrough",row.getLong(4)).put("retirementReason",row.getString(5)).put("memoryContextRetired",row.getInt(6)!=0);return value;
        }
    }
    public JSONObject sourceByClient(String client)throws Exception{return sourceByKey("client_message_id",client);}
    public JSONObject sourceByReply(String reply)throws Exception{return sourceByKey("native_reply_id",reply);}
    private JSONObject sourceByKey(String field,String key)throws Exception{try(Cursor row=db.rawQuery("SELECT source_id FROM assistant_sources WHERE generation=? AND "+field+"=? LIMIT 1",new String[]{activeGeneration(),key})){return row.moveToFirst()?source(row.getString(0)):null;}}

    /** Native review metadata only. The caller holds the same original TaskStore
     * transaction used by source edits/deletion; no assistant Run must still be live. */
    JSONObject preparePhoneHandoffSource(String conversation)throws Exception{
        requirePhoneHandoffTransaction();
        if(conversation==null||conversation.isBlank()||conversation.length()>240||!StandardCharsets.UTF_8.newEncoder().canEncode(conversation))throw new IllegalArgumentException("原私人会话身份无效");
        String id;
        // Select the last native user row before checking availability. A withdrawn or
        // deleted latest intention must not silently fall back to an older instruction.
        try(Cursor row=db.rawQuery("SELECT s.source_id FROM assistant_sources s JOIN assistant_messages m ON m.generation=s.generation AND m.source_id=s.source_id AND m.conversation_id=s.conversation_id WHERE s.generation=? AND s.conversation_id=? AND s.role='user' ORDER BY m.sequence DESC LIMIT 1",new String[]{activeGeneration(),conversation})){
            if(!row.moveToFirst())throw new SecurityException("此原会话没有可固定的主人消息，请先保存本次要求");id=row.getString(0);
        }
        return phoneHandoffSourceFacts(conversation,id);
    }
    /** Exact native proposal source, never a fallback to a later or older intention. */
    public JSONObject preparePhoneHandoffSource(String conversation,String sourceMessageId)throws Exception{
        requirePhoneHandoffTransaction();
        if(conversation==null||conversation.isBlank()||conversation.length()>240||sourceMessageId==null||sourceMessageId.isBlank()||sourceMessageId.length()>240
                ||!StandardCharsets.UTF_8.newEncoder().canEncode(conversation)||!StandardCharsets.UTF_8.newEncoder().canEncode(sourceMessageId))throw new IllegalArgumentException("原私人会话消息身份无效");
        return phoneHandoffSourceFacts(conversation,sourceMessageId);
    }
    /** Source currentness for prepare -> explicit native launch only. Already-recorded
     * phone outcomes do not need this old user message to remain editable/current. */
    public void requirePhoneHandoffSource(JSONObject snapshot)throws Exception{
        requirePhoneHandoffTransaction();
        Set<String> fields=Set.of("format","generation","conversationId","conversationTitle","sourceMessageId","sourceMessageRevision","bodyFingerprint","sourceSequence");
        if(snapshot==null||snapshot.length()!=fields.size())throw new SecurityException("原会话手机任务来源快照无效");
        for(var keys=snapshot.keys();keys.hasNext();)if(!fields.contains(keys.next()))throw new SecurityException("来源快照不能夹带执行权限或正文");
        if(!"pocketworld.phone-handoff-source.v1".equals(snapshot.getString("format"))||!(snapshot.opt("conversationTitle") instanceof String)
                ||snapshot.getString("conversationTitle").length()>200||!snapshot.getString("bodyFingerprint").matches("sha256:[a-f0-9]{64}"))throw new SecurityException("原会话手机任务来源格式无效");
        long revision=integer(snapshot,"sourceMessageRevision",1),sequence=integer(snapshot,"sourceSequence",1);
        JSONObject actual=phoneHandoffSourceFacts(snapshot.getString("conversationId"),snapshot.getString("sourceMessageId"));
        for(String key:List.of("generation","conversationId","sourceMessageId","bodyFingerprint"))if(!actual.getString(key).equals(snapshot.getString(key)))throw new SecurityException("审阅后的原主人来源已经改变，请重新审阅");
        if(actual.getLong("sourceMessageRevision")!=revision||actual.getLong("sourceSequence")!=sequence)throw new SecurityException("审阅后的原主人消息版本已经改变，请重新审阅");
    }
    private void requirePhoneHandoffTransaction(){
        if(!Thread.holdsLock(monitor)||!db.inTransaction())throw new SecurityException("原会话手机任务核对必须在同一TaskStore事务内");
    }
    private JSONObject phoneHandoffSourceFacts(String conversation,String id)throws Exception{
        String gen=activeGeneration();JSONObject header=header(conversation),state=source(id),metadata=metadata(id);
        if(state==null||metadata==null||metadata.optBoolean("deleted")||!conversation.equals(metadata.optString("conversationId"))||!"user".equals(metadata.optString("role"))
                ||!conversation.equals(state.optString("conversationId"))||!"user".equals(state.optString("role")))throw new SecurityException("原主人来源已删除或不属于此私人会话");
        SourceRef ref=SourceRef.fromJson(state);
        if(!"message".equals(ref.kind)||!"owner".equals(ref.ownerId)||!"private".equals(ref.spaceId)||!id.equals(ref.sourceId)
                ||metadata.getLong("revision")!=ref.revision||metadata.optLong("memorySourceRetiredThrough")>=ref.revision||metadata.optBoolean("memoryContextRetired"))throw new SecurityException("原主人来源已撤回或停用");
        JSONObject message=message(conversation,id);
        if(message==null||!id.equals(message.optString("id"))||!"user".equals(message.optString("role"))||integer(message,"revision",1)!=ref.revision)throw new SecurityException("原主人消息正文和来源版本不一致");
        String text=message.getString("content");if(!StandardCharsets.UTF_8.newEncoder().canEncode(text))throw new SecurityException("原主人消息编码不可核对");
        byte[] body=text.getBytes(StandardCharsets.UTF_8);String fingerprint="sha256:"+shaBytes(body);long sequence;
        try(Cursor row=db.rawQuery("SELECT sequence,revision,body_sha,body_bytes FROM assistant_messages WHERE generation=? AND conversation_id=? AND source_id=? AND body IS NOT NULL LIMIT 1",new String[]{gen,conversation,id})){
            if(!row.moveToFirst()||row.getLong(1)!=ref.revision||!fingerprint.equals(row.getString(2))||body.length!=row.getLong(3))throw new SecurityException("原主人消息完整性不可核对");
            sequence=row.getLong(0);if(sequence<1)throw new SecurityException("原主人消息顺序不可核对");
        }
        String title=header.optString("title","");if(title.length()>200){int end=200;if(Character.isHighSurrogate(title.charAt(end-1)))end--;title=title.substring(0,end);}
        return new JSONObject().put("format","pocketworld.phone-handoff-source.v1").put("generation",gen).put("conversationId",conversation).put("conversationTitle",title)
                .put("sourceMessageId",id).put("sourceMessageRevision",ref.revision).put("bodyFingerprint",fingerprint).put("sourceSequence",sequence);
    }

    /** Exact frozen TaskStore outcome only. No model/source callback, admission lock,
     * network I/O or event emission is performed under the original SQL transaction. */
    JSONObject appendPhoneHandoffResult(dev.pocketengine.store.TaskStore original,dev.pocketengine.store.TaskStore.ConversationPhoneDelivery delivery,boolean conversationBusy)throws Exception{
        requirePhoneHandoffTransaction();if(monitor!=original)throw new SecurityException("不能替换原手机结果数据库宿主");
        JSONObject result=delivery.snapshot();String conversation=result.getString("conversationId"),task=result.getString("taskId"),run=result.getString("runId");
        long epoch=integer(result,"epoch",1);String key=phoneHandoffDeliveryId(task,run,epoch),digest=result.getString("resultDigest"),status=result.getString("status");
        if(!"pocketworld.conversation-phone-delivery.v1".equals(result.optString("format"))||!key.equals(result.getString("deliveryId"))||!task.equals(result.getString("requestId"))
                ||!digest.matches("[a-f0-9]{64}")||!Set.of("completed","needs_confirmation","awaiting_user","failed","cancelled","interrupted","reconciling","paused").contains(status))throw new SecurityException("原手机结果身份或终态不可核对");
        if(!activeGeneration().equals(result.getString("generation")))throw new SecurityException("原结果的会话留存代次已变化，未重建或冒充已删除");
        JSONObject prior=sourceByReply(key);
        if(prior!=null){
            requirePhoneResultIndex(prior,conversation,task,run,epoch,key);
            if(!digest.equals(prior.optString("nativeReplyDigest"))||!status.equals(prior.optString("nativeStatus")))throw new SecurityException("同一手机结果编号不能更换原结果");
            boolean available=phoneResultAvailable(prior);
            if(available)requirePhoneResultMessage(prior,conversation,task,run,epoch,key);
            return phoneResultAck(conversation,task,run,epoch,key).put("accepted",true).put("duplicate",true).put("messageId",prior.getString("sourceId")).put("available",available);
        }
        if(!exists(conversation)){
            try(Cursor tombstone=db.rawQuery("SELECT deleted FROM assistant_conversations WHERE generation=? AND conversation_id=? LIMIT 1",new String[]{activeGeneration(),conversation})){
                if(tombstone.moveToFirst()&&tombstone.getInt(0)!=0)return phoneResultAck(conversation,task,run,epoch,key).put("accepted",false).put("available",false).put("conversationDeleted",true);
            }
            throw new SecurityException("原结果会话无法核对，未创建替代会话");
        }
        if(conversationBusy)return phoneResultAck(conversation,task,run,epoch,key).put("accepted",false).put("available",false).put("waitingForOriginalConversation",true);
        String text=result.getString("content");if(text.isBlank()||text.length()>24000||!StandardCharsets.UTF_8.newEncoder().canEncode(text))throw new IllegalArgumentException("原手机结果超过对话保存范围，原任务结果仍保留");
        JSONObject evidence=result.getJSONObject("evidence");if(evidence.toString().length()>4096)throw new SecurityException("手机结果只能携带固定任务证据引用");
        // The typed original callback has checked the frozen summary, its exact ended
        // run and original destination. An incomplete producer graph still permits
        // visible audit history, but never a new reusable assistant source.
        boolean reusable=false;
        try{dev.pocketengine.features.memory.OwnerSourceRunContext.requireReplyDependencies(db,this,outputSources,task,run,Math.toIntExact(epoch),conversation);reusable=true;}
        catch(dev.pocketengine.features.memory.TaskStoreMemoryReader.RawMessageUnavailable|SourceUnavailable unavailable){if(Thread.currentThread().isInterrupted())throw unavailable;}
        long endedAt=integer(result,"endedAt",0),now=System.currentTimeMillis();String messageId;do{messageId=UUID.randomUUID().toString();}while(source(messageId)!=null);
        JSONObject message=new JSONObject().put("id",messageId).put("revision",1L).put("role","assistant").put("content",text).put("at",now).put("conversationId",conversation)
                .put("taskId",task).put("nativeRunId",run).put("nativeEpoch",epoch).put("nativeReplyId",key).put("nativeDeliveryId",key).put("nativeDeliveryKind","conversation_phone").put("status",status).put("nativeStatus",status)
                .put("resultDigest",digest).put("taskEndedAt",endedAt).put("taskResultEvidence",new JSONObject(evidence.toString()))
                .put("summaryTruncated",result.getBoolean("summaryTruncated")).put("summaryChars",integer(result,"summaryChars",0)).put("summaryDigest",result.getString("summaryDigest"))
                .put("taskReference",new JSONObject().put("taskId",task).put("runId",run).put("epoch",epoch)).put("resultVerified",false).put("executionAuthorized",false);
        JSONObject state=new SourceRef("message",messageId,1,"owner","private").toJson().put("conversationId",conversation).put("role","assistant").put("deleted",false).put("updatedAt",now)
                .put("nativeReplyId",key).put("nativeReplyDigest",digest).put("nativeTaskId",task).put("nativeRunId",run).put("nativeEpoch",epoch).put("nativeStatus",status);
        saveSource(state);saveMessage(conversation,message);if(reusable)bindNativeReplyContext(original,conversation,messageId,task,run,Math.toIntExact(epoch));JSONObject header=header(conversation);header.put("updatedAt",now);saveConversation(header);
        return phoneResultAck(conversation,task,run,epoch,key).put("accepted",true).put("duplicate",false).put("messageId",messageId).put("available",true);
    }
    JSONObject queryPhoneHandoffResult(String conversation,String task,String run,int epoch)throws Exception{
        String key=phoneHandoffDeliveryId(task,run,epoch);JSONObject prior=sourceByReply(key),ack=phoneResultAck(conversation,task,run,epoch,key).put("bodyIncluded",false);
        if(prior==null)return ack.put("found",false).put("available",false);
        requirePhoneResultIndex(prior,conversation,task,run,epoch,key);boolean available=phoneResultAvailable(prior);
        return ack.put("found",true).put("messageId",prior.getString("sourceId")).put("available",available);
    }
    JSONObject appendOwnerWorkResult(dev.pocketengine.store.TaskStore original,dev.pocketengine.store.TaskStore.OwnerWorkDelivery delivery,boolean busy)throws Exception{
        delivery.requireMutationScope(original);if(monitor!=original)throw new SecurityException("私人工作必须投递到同一原数据库");
        JSONObject result=delivery.snapshot();String conversation=result.getString("conversationId"),task=result.getString("taskId"),run=result.getString("runId"),key=result.getString("deliveryId"),digest=result.getString("resultDigest"),status=result.getString("status");int epoch=result.getInt("epoch");
        if(!digest.matches("[a-f0-9]{64}")||!Set.of("completed","needs_confirmation","awaiting_user","failed","cancelled","interrupted","reconciling","paused").contains(status))throw new SecurityException("原私人工作结果不可核对");
        String noticeKind=result.optString("noticeKind");boolean recoveryNotice=dev.pocketengine.store.OwnerWorkResultProtocol.isCapacityNoticeKind(noticeKind);
        if(recoveryNotice&&(result.has("nativeFinalAnswerProof")||result.optJSONArray("consumableInputs")!=null&&result.getJSONArray("consumableInputs").length()!=0))throw new SecurityException("容量通知不能冒充模型成果或消费主人输入");
        JSONObject ack=new JSONObject().put("taskId",task).put("runId",run).put("epoch",epoch).put("conversationId",conversation).put("deliveryId",key).put("executionAuthorized",false),prior=sourceByReply(key);
        if(recoveryNotice)ack.put("noticeKind",noticeKind);
        if(!activeGeneration().equals(result.getString("generation")))return ack.put("accepted",false).put("available",false).put("conversationDeleted",true);
        if(prior!=null){
            requirePhoneResultIndex(prior,conversation,task,run,epoch,key);if(!digest.equals(prior.optString("nativeReplyDigest"))||!status.equals(prior.optString("nativeStatus")))throw new SecurityException("同一私人工作结果不能更换内容");
            if(phoneResultAvailable(prior)&&prior.optJSONObject("nativeContext")==null)bindNativeReplyContext(original,conversation,prior.getString("sourceId"),task,run,epoch);
            return ack.put("accepted",true).put("duplicate",true).put("messageId",prior.getString("sourceId")).put("available",phoneResultAvailable(prior));
        }
        if(!exists(conversation))return ack.put("accepted",false).put("conversationDeleted",true).put("available",false);
        if(busy)return ack.put("accepted",false).put("waitingForOriginalConversation",true).put("available",false);
        String text=result.getString("content");if(text.isBlank()||text.length()>24000||!StandardCharsets.UTF_8.newEncoder().canEncode(text))throw new SecurityException("私人工作结果正文无效");
        long now=System.currentTimeMillis();String messageId;do{messageId=UUID.randomUUID().toString();}while(source(messageId)!=null);
        JSONObject message=new JSONObject().put("id",messageId).put("revision",1L).put("role","assistant").put("content",text).put("at",now).put("conversationId",conversation)
                .put("taskId",task).put("nativeReplyId",key).put("nativeDeliveryId",key).put("nativeDeliveryKind","owner_work").put("nativeRunId",run).put("nativeEpoch",epoch)
                .put("nativeStatus",status).put("status",status).put("taskEndedAt",result.getLong("endedAt")).put("resultDigest",digest)
                .put("summaryTruncated",result.optBoolean("summaryTruncated")).put("summaryChars",result.optInt("summaryChars",text.length()))
                .put("taskReference",new JSONObject().put("taskId",task).put("runId",run).put("epoch",epoch))
                .put("replyToMessageIds",new JSONArray(result.getJSONArray("replyToMessageIds").toString())).put("replyToSourceRefs",new JSONArray(result.getJSONArray("sourceMessageRefs").toString()))
                .put("executionAuthorized",false).put("resultVerified",false);
        if(recoveryNotice)message.put("nativeNoticeKind",noticeKind);
        JSONObject answerProof=result.optJSONObject("nativeFinalAnswerProof");
        if(answerProof!=null)for(String field:new String[]{"memoryReference","memoryContext"})if(answerProof.has(field))message.put(field,new JSONObject(answerProof.getJSONObject(field).toString()));
        JSONObject state=new SourceRef("message",messageId,1,"owner","private").toJson().put("conversationId",conversation).put("role","assistant").put("deleted",false).put("updatedAt",now)
                .put("nativeReplyId",key).put("nativeReplyDigest",digest).put("nativeTaskId",task).put("nativeRunId",run).put("nativeEpoch",epoch).put("nativeStatus",status);
        if(recoveryNotice)state.put("nativeNoticeKind",noticeKind);
        saveSource(state);saveMessage(conversation,message);bindNativeReplyContext(original,conversation,messageId,task,run,epoch);JSONObject header=header(conversation);header.put("updatedAt",now);saveConversation(header);
        JSONArray consumed=new JSONArray(),inputs=result.optJSONArray("consumableInputs");
        for(int i=0;inputs!=null&&i<inputs.length();i++){
            JSONObject row=inputs.getJSONObject(i);
            if(result.optJSONObject("nativeFinalAnswerProof")==null||row.has("ownerWorkRoute")||!task.equals(row.optString("acceptedTaskId"))||!run.equals(row.optString("materializedRunId"))||epoch!=row.optInt("materializedEpoch"))throw new SecurityException("私人工作不能消费其他回合或未被答复采用的主人输入");
            requireQueuedOwnerMessageCurrent(conversation,row);String inputId=row.getString("messageId");JSONObject inputSource=source(inputId),inputMessage=message(conversation,inputId),inputAck=new JSONObject(inputSource.getJSONObject("submission").toString());
            if("consumed".equals(inputAck.optString("status")))continue;
            boolean missingAck="unconfirmed".equals(inputAck.optString("status"))&&!inputAck.has("taskId")&&!inputAck.has("runId")&&!inputAck.has("epoch");
            if(missingAck)inputAck.put("taskId",task).put("runId",row.getString("acceptedRunId")).put("epoch",integer(row,"acceptedEpoch",1));
            if(!task.equals(inputAck.optString("taskId"))||!row.getString("acceptedRunId").equals(inputAck.optString("runId"))||integer(row,"acceptedEpoch",1)!=integer(inputAck,"epoch",1))throw new SecurityException("补充输入的原接收身份不能被工作续接替换");
            inputAck.put("status","consumed").put("queueState","materialized").put("queueRevision",integer(row,"queueRevision",1))
                    .put("ownerMessageRevision",integer(row,"ownerMessageRevision",1)).put("mode",row.getString("mode"))
                    .put("consumedRunId",run).put("consumedEpoch",epoch).put("replyMessageId",messageId).put("consumedAt",now).put("reconciliationRequired",false);
            inputSource.put("submission",new JSONObject(inputAck.toString())).put("updatedAt",now);inputMessage.put("submission",new JSONObject(inputAck.toString()));saveSource(inputSource);saveMessage(conversation,inputMessage);consumed.put(inputAck);
        }
        return ack.put("accepted",true).put("duplicate",false).put("messageId",messageId).put("available",true).put("consumed",consumed);
    }
    private static String phoneHandoffDeliveryId(String task,String run,long epoch){
        if(task==null||run==null||!task.matches("[a-fA-F0-9-]{36}")||!run.matches("[a-fA-F0-9-]{36}")||epoch<1||epoch>Integer.MAX_VALUE)throw new IllegalArgumentException("原手机任务执行身份无效");
        return "phone_handoff:"+task+":"+run+":"+epoch;
    }
    private static JSONObject phoneResultAck(String conversation,String task,String run,long epoch,String key)throws Exception{return new JSONObject().put("conversationId",conversation).put("taskId",task).put("runId",run).put("epoch",epoch).put("deliveryId",key).put("executionAuthorized",false).put("resultVerified",false);}
    private static boolean phoneResultAvailable(JSONObject source){return !source.optBoolean("deleted")&&!source.optBoolean("memoryContextRetired")&&source.optLong("memorySourceRetiredThrough")<source.optLong("revision");}
    private static void requirePhoneResultIndex(JSONObject source,String conversation,String task,String run,long epoch,String key)throws Exception{
        SourceRef ref=SourceRef.fromJson(source);
        if(!"message".equals(ref.kind)||!"owner".equals(ref.ownerId)||!"private".equals(ref.spaceId)||!"assistant".equals(source.optString("role"))||!conversation.equals(source.optString("conversationId"))
                ||!task.equals(source.optString("nativeTaskId"))||!run.equals(source.optString("nativeRunId"))||epoch!=integer(source,"nativeEpoch",1)||!key.equals(source.optString("nativeReplyId")))throw new SecurityException("原手机投递索引不属于这个任务、执行或会话");
    }
    private void requirePhoneResultMessage(JSONObject source,String conversation,String task,String run,long epoch,String key)throws Exception{
        JSONObject actual=message(conversation,source.getString("sourceId"));
        if(actual==null||!"assistant".equals(actual.optString("role"))||!task.equals(actual.optString("taskId"))||!run.equals(actual.optString("nativeRunId"))||epoch!=integer(actual,"nativeEpoch",1)||!key.equals(actual.optString("nativeDeliveryId")))throw new SecurityException("原手机投递消息与唯一索引不一致");
    }
    /** Owner UI only: no arbitrary source body can be selected through an unrelated reply. */
    public JSONObject readOriginalReplySource(String conversation,String replyId,String sourceId,int offset,int maxChars)throws Exception{
        if(offset<0||maxChars<1||maxChars>12000)throw new IllegalArgumentException("原文分页范围无效");
        JSONObject reply=message(conversation,replyId),result=new JSONObject().put("conversationId",conversation).put("replyMessageId",replyId).put("sourceMessageId",sourceId).put("available",false);
        if(reply==null||!"assistant".equals(reply.optString("role")))return result.put("message","原答复已不可用");
        JSONArray refs=reply.optJSONArray("replyToMessageIds");boolean bound=false;for(int i=0;refs!=null&&i<refs.length();i++)if(sourceId.equals(refs.optString(i)))bound=true;
        if(!bound)throw new SecurityException("这条消息不是原答复对应的主人请求");
        JSONObject state=source(sourceId),original=message(conversation,sourceId);
        if(state==null||original==null||!conversation.equals(state.optString("conversationId"))||!"user".equals(state.optString("role"))||!phoneResultAvailable(state))return result.put("message","原请求已删除或不再可用");
        String text=original.getString("content");if(offset>text.length()||offset>0&&offset<text.length()&&Character.isLowSurrogate(text.charAt(offset))&&Character.isHighSurrogate(text.charAt(offset-1)))throw new IllegalArgumentException("原文分页起点无效");
        int end=Math.min(text.length(),offset+maxChars);if(end<text.length()&&end>offset&&Character.isHighSurrogate(text.charAt(end-1)))end--;
        return result.put("available",true).put("text",text.substring(offset,end)).put("offset",offset).put("nextOffset",end).put("totalChars",text.length()).put("hasMore",end<text.length());
    }
    public void saveSource(JSONObject source)throws Exception{saveSource(activeGeneration(),source);}
    private void saveSource(String gen,JSONObject source)throws Exception{
        SourceRef ref=SourceRef.fromJson(source);if(!"message".equals(ref.kind)||!"owner".equals(ref.ownerId)||!"private".equals(ref.spaceId))throw new SecurityException("原始消息来源不能更换私人身份");
        String cid=source.getString("conversationId"),role=source.optString("role");if(!Set.of("user","assistant").contains(role)&&!(source.getBoolean("deleted")&&role.isEmpty()))throw new IllegalArgumentException("原始发言角色未知");
        ContentValues v=new ContentValues();v.put("generation",gen);v.put("source_id",ref.sourceId);v.put("conversation_id",cid);v.put("revision",ref.revision);v.put("role",role);v.put("deleted",source.getBoolean("deleted")?1:0);v.put("unavailable_reason",knownReason(source.optString("unavailableReason",source.getBoolean("deleted")?"unknown":"")));v.put("retired_through",source.optLong("memorySourceRetiredThrough"));v.put("retirement_reason",knownReason(source.optString("retirementReason","unknown")));v.put("context_retired",source.optBoolean("memoryContextRetired")?1:0);v.put("source_json",source.toString());
        if(source.has("clientMessageId"))v.put("client_message_id",source.getString("clientMessageId"));else v.putNull("client_message_id");if(source.has("nativeReplyId"))v.put("native_reply_id",source.getString("nativeReplyId"));else v.putNull("native_reply_id");
        db.insertWithOnConflict("assistant_sources",null,v,SQLiteDatabase.CONFLICT_REPLACE);
        if(source.getBoolean("deleted")){ContentValues erased=new ContentValues();erased.putNull("body");erased.put("body_bytes",0);erased.put("body_sha","");db.update("assistant_messages",erased,"generation=? AND source_id=?",new String[]{gen,ref.sourceId});}
    }
    public void saveConversation(JSONObject conversation)throws Exception{synchronized(monitor){saveConversation(activeGeneration(),conversation);}}
    private void saveConversation(String gen,JSONObject conversation)throws Exception{synchronized(monitor){JSONObject header=new JSONObject(conversation.toString());header.remove("messages");header.remove("messagePage");header.remove("nativeUserTitle");
        // Same original monitor/transaction as owner metadata writes; an old reply/ACK header cannot overwrite a newer manual title.
        try(Cursor size=db.rawQuery("SELECT length(CAST(header_json AS BLOB)) FROM assistant_conversations WHERE generation=? AND conversation_id=? AND deleted=0 LIMIT 1",new String[]{gen,header.getString("conversationId")})){
            if(size.moveToFirst()&&size.getLong(0)>MAX_BYTES)throw new ReadLimited("original_conversation_header_exceeds_inline_budget");
        }
        try(Cursor prior=db.rawQuery("SELECT header_json FROM assistant_conversations WHERE generation=? AND conversation_id=? AND deleted=0 LIMIT 1",new String[]{gen,header.getString("conversationId")})){
            if(prior.moveToFirst()){
                JSONObject current=new JSONObject(prior.getString(0));Object owned=current.opt("nativeUserTitle");
                if(owned!=null&&owned!=JSONObject.NULL){
                    if(!(owned instanceof String)||((String)owned).isBlank()||((String)owned).length()>120||!StandardCharsets.UTF_8.newEncoder().canEncode((String)owned))throw new IllegalStateException("原手工会话标题状态无法核对，未用旧回复覆盖");
                    header.put("title",owned).put("nativeUserTitle",owned);
                }
            }
        }
        ContentValues v=new ContentValues();v.put("header_json",header.toString());v.put("updated_at",header.optLong("updatedAt",header.optLong("createdAt")));
        // REPLACE would reset independent owner metadata during an ordinary reply/ACK save.
        if(db.update("assistant_conversations",v,"generation=? AND conversation_id=? AND deleted=0",new String[]{gen,header.getString("conversationId")})==0){
            v.put("generation",gen);v.put("conversation_id",header.getString("conversationId"));v.put("deleted",0);v.put("delete_reason","");db.insertOrThrow("assistant_conversations",null,v);
        }
        ConversationOrganization.changed(db,gen);
    }}
    public void saveMessage(String conversation,JSONObject message)throws Exception{saveMessage(activeGeneration(),conversation,message,-1);}
    private void saveMessage(String gen,String conversation,JSONObject message,long importedSequence)throws Exception{
        String id=message.getString("id");JSONObject state=source(gen,id);if(state==null||state.getBoolean("deleted")||!conversation.equals(state.getString("conversationId"))||integer(message,"revision",1)!=state.getLong("revision")||!message.getString("role").equals(state.getString("role")))throw new SecurityException("原消息/来源修订不一致，未保存副本");
        JSONObject meta=new JSONObject(message.toString());String body=meta.getString("content");if(!StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).canEncode(body))throw new IllegalArgumentException("原文含不完整Unicode字符，未隐式替换后保存");byte[] bodyBytes=body.getBytes(StandardCharsets.UTF_8);meta.remove("content");meta.remove("ownerOriginalLocator");long sequence=importedSequence;
        boolean existing=false;
        try(Cursor row=db.rawQuery("SELECT sequence,meta_json FROM assistant_messages WHERE generation=? AND source_id=? LIMIT 1",new String[]{gen,id})){if(row.moveToFirst()){existing=true;sequence=row.getLong(0);if(new JSONObject(row.getString(1)).optBoolean("personalMemoryExcluded"))meta.put("personalMemoryExcluded",true);}}
        if(!existing&&importedSequence<0&&"user".equals(message.getString("role"))&&gen.equals(activeGeneration())&&!personalMemoryControls(header(conversation)).getBoolean("enabled"))meta.put("personalMemoryExcluded",true);
        if(sequence<0)try(Cursor row=db.rawQuery("SELECT max(n)+1 FROM (SELECT coalesce(max(sequence),0) n FROM assistant_messages WHERE generation=? AND conversation_id=? UNION ALL SELECT coalesce(max(sequence),0) n FROM assistant_import_records WHERE generation=? AND conversation_id=?)",new String[]{gen,conversation,gen,conversation})){row.moveToFirst();sequence=row.getLong(0);}
        ContentValues v=new ContentValues();v.put("generation",gen);v.put("source_id",id);v.put("conversation_id",conversation);v.put("sequence",sequence);v.put("revision",state.getLong("revision"));v.put("body",body);v.put("body_bytes",bodyBytes.length);v.put("body_sha","sha256:"+shaBytes(bodyBytes));v.put("meta_json",meta.toString());Object at=message.opt("at");if(at instanceof Byte||at instanceof Short||at instanceof Integer||at instanceof Long)v.put("at_ms",((Number)at).longValue());else v.putNull("at_ms");db.insertWithOnConflict("assistant_messages",null,v,SQLiteDatabase.CONFLICT_REPLACE);ConversationOrganization.changed(db,gen);
    }
    public boolean exists(String conversation)throws Exception{try(Cursor row=db.rawQuery("SELECT 1 FROM assistant_conversations WHERE generation=? AND conversation_id=? AND deleted=0 LIMIT 1",new String[]{activeGeneration(),conversation})){return row.moveToFirst();}}
    /** Owner controls live in the existing conversation header, not a second preference store. */
    public JSONObject personalMemoryControls(String conversation)throws Exception{return read(()->personalMemoryControls(header(conversation)));}
    private static JSONObject personalMemoryControls(JSONObject header)throws Exception{
        Object enabled=header.opt("personalMemoryEnabled"),revision=header.opt("personalMemoryRevision");
        if(enabled!=null&&!(enabled instanceof Boolean)||revision!=null&&(!(revision instanceof Integer||revision instanceof Long)||((Number)revision).longValue()<0))throw new IllegalStateException("此对话的个人记忆设置不可核对");
        return new JSONObject().put("conversationId",header.getString("conversationId")).put("enabled",enabled==null||Boolean.TRUE.equals(enabled)).put("revision",revision==null?0:((Number)revision).longValue());
    }
    public JSONObject setPersonalMemoryForOwner(String conversation,long revision,boolean enabled)throws Exception{
        return transaction(()->{JSONObject header=header(conversation),before=personalMemoryControls(header);
            if(revision<0||revision!=before.getLong("revision"))throw new IllegalStateException("此对话的个人记忆设置已变化，请刷新后保存");
            header.put("personalMemoryEnabled",enabled).put("personalMemoryRevision",Math.addExact(revision,1));saveConversation(header);
            return personalMemoryControls(header).put("saved",true).put("persisted",true);
        });
    }
    public JSONObject header(String conversation)throws Exception{try(Cursor size=db.rawQuery("SELECT length(CAST(header_json AS BLOB)) FROM assistant_conversations WHERE generation=? AND conversation_id=? AND deleted=0 LIMIT 1",new String[]{activeGeneration(),conversation})){if(size.moveToFirst()&&size.getLong(0)>MAX_BYTES)throw new ReadLimited("original_conversation_header_exceeds_inline_budget");}try(Cursor row=db.rawQuery("SELECT header_json FROM assistant_conversations WHERE generation=? AND conversation_id=? AND deleted=0 LIMIT 1",new String[]{activeGeneration(),conversation})){if(!row.moveToFirst())throw new IllegalArgumentException("对话不存在");return new JSONObject(row.getString(0));}}
    public JSONObject list(String cursor,int limit)throws Exception{
        if(limit<1||limit>20)throw new IllegalArgumentException("会话分页最多20条");JSONObject pos=decode(cursor);List<String> args=new ArrayList<>();args.add(activeGeneration());String where="generation=? AND deleted=0";
        if(pos.has("at")){where+=" AND (updated_at<? OR (updated_at=? AND conversation_id<?))";args.add(pos.get("at").toString());args.add(pos.get("at").toString());args.add(pos.getString("id"));}
        JSONArray rows=new JSONArray(),gaps=new JSONArray();String next="",lastId="";long lastAt=0,bytes=0;int scanned=0;boolean more=false;
        try(Cursor row=db.rawQuery("SELECT conversation_id,length(CAST(header_json AS BLOB)),updated_at FROM assistant_conversations WHERE "+where+" ORDER BY updated_at DESC,conversation_id DESC LIMIT ?",append(args,Integer.toString(limit+1)))){
            while(row.moveToNext()){
                if(scanned>=limit){more=true;break;}long size=row.getLong(1);if(size>MAX_BYTES){gaps.put(new JSONObject().put("conversationId",row.getString(0)).put("status","read_limited"));lastId=row.getString(0);lastAt=row.getLong(2);scanned++;continue;}if(size>MAX_BYTES-bytes){more=true;break;}
                JSONObject header=header(row.getString(0)),item=new JSONObject().put("conversationId",row.getString(0)).put("title",header.optString("title"));Object actual=header.opt("updatedAt");boolean known=actualInteger(actual);item.put("updatedAt",known?actual:JSONObject.NULL).put("updatedAtKnown",known);rows.put(item);bytes+=size;lastId=row.getString(0);lastAt=row.getLong(2);scanned++;
            }
        }
        if(more&&!lastId.isEmpty())next=encode(new JSONObject().put("at",lastAt).put("id",lastId));return new JSONObject().put("conversations",rows).put("gaps",gaps).put("scannedRows",scanned).put("materializedHeaderUtf8Bytes",bytes).put("nextCursor",next).put("hasMore",more).put("retentionLimitApplied",false).put("paginationSemantics","mutable_updated_at_keyset_not_snapshot");
    }
    private JSONObject messageFromRow(Cursor row)throws Exception{JSONObject message=new JSONObject(row.getString(1));message.put("content",row.getString(0)).put("revision",row.getLong(2));JSONObject state=source(message.getString("id"));if(state==null||state.getBoolean("deleted"))return null;for(String key:List.of("submission","memorySourceRetiredThrough","memoryContextRetired","retirementReason"))if(state.has(key))message.put(key,state.get(key));return message;}
    public JSONObject message(String conversation,String id)throws Exception{
        JSONObject state=metadata(id);if(state==null||state.getBoolean("deleted")||!conversation.equals(state.getString("conversationId")))return null;
        try(Cursor size=db.rawQuery("SELECT length(CAST(m.body AS BLOB))+length(CAST(m.meta_json AS BLOB))+length(CAST(s.source_json AS BLOB)) FROM assistant_messages m JOIN assistant_sources s ON s.generation=m.generation AND s.source_id=m.source_id WHERE m.generation=? AND m.conversation_id=? AND m.source_id=? LIMIT 1",new String[]{activeGeneration(),conversation,id})){if(!size.moveToFirst())return null;if(size.getLong(0)>MAX_BYTES)throw new ReadLimited("original_message_exceeds_inline_budget");}
        try(Cursor row=db.rawQuery("SELECT body,meta_json,revision FROM assistant_messages WHERE generation=? AND conversation_id=? AND source_id=? AND body IS NOT NULL LIMIT 1",new String[]{activeGeneration(),conversation,id})){return row.moveToFirst()?messageFromRow(row):null;}
    }
    /** Original queue claim/mutation only, in the same TaskStore monitor and SQLite transaction.
     * This validates the real message/source, not a JSON capability or a second queue authority. */
    public void requireQueuedOwnerMessageCurrent(String conversation,JSONObject row)throws Exception{
        if(!Thread.holdsLock(monitor)||!db.inTransaction())throw new SecurityException("排队输入核对必须在原TaskStore事务内");
        String id=row.getString("messageId"),client=row.getString("clientMessageId");long revision=integer(row,"ownerMessageRevision",1);
        JSONObject state=source(id),message=message(conversation,id);
        if(state==null||message==null||state.getBoolean("deleted")||!conversation.equals(state.getString("conversationId"))
                ||!"user".equals(state.optString("role"))||!"user".equals(message.optString("role"))
                ||!id.equals(message.getString("id"))||revision!=state.getLong("revision")||revision!=integer(message,"revision",1)
                ||!client.equals(state.optString("clientMessageId"))||!client.equals(message.optString("clientMessageId"))
                ||!row.getString("content").equals(message.getString("content"))||!row.getString("mode").equals(message.optString("inputMode"))
                ||state.optString("requestFingerprint").isEmpty()||!state.getString("requestFingerprint").equals(message.optString("requestFingerprint")))
            throw new SecurityException("原生排队消息或来源版本已变化，未接手旧正文");
        if(row.has("ownerRequestFingerprint")){
            Object pin=row.opt("ownerRequestFingerprint");
            if(!(pin instanceof String)||!((String)pin).matches("[0-9a-f]{64}")||!pin.equals(state.optString("requestFingerprint"))||!pin.equals(message.optString("requestFingerprint")))
                throw new SecurityException("原接收时请求指纹与当前消息来源不一致");
        }
        JSONObject ack=state.optJSONObject("submission");
        if(ack==null||!client.equals(ack.optString("clientMessageId"))||!id.equals(ack.optString("messageId"))||!conversation.equals(ack.optString("conversationId")))
            throw new SecurityException("原接收回执与消息身份不一致，未接手副本");
        // appendOwnerInput is already the native acceptance authority. Its real pending row can
        // be claimed before sendNative commits its ACK; this is not a consumption/grant claim.
        boolean pendingAck="unconfirmed".equals(ack.optString("status"))&&!ack.has("taskId")&&!ack.has("runId")&&!ack.has("epoch");
        if(!pendingAck&&(!row.getString("acceptedRunId").equals(ack.optString("runId"))||integer(row,"acceptedEpoch",1)!=integer(ack,"epoch",1)
                ||row.has("acceptedTaskId")&&!row.getString("acceptedTaskId").equals(ack.optString("taskId"))))
            throw new SecurityException("原接收代次与消息回执不一致，未接手副本");
        for(String key:List.of("attachments","ownerSourceRefs","ownerPreviewRefs","ownerObjectRefs")){
            JSONArray expected=row.optJSONArray(key),actual=message.optJSONArray(key);
            if(row.has(key)&&expected==null||message.has(key)&&actual==null
                    ||!TaskMemoryTrace.rawFingerprint(new JSONObject().put("refs",expected==null?new JSONArray():expected))
                    .equals(TaskMemoryTrace.rawFingerprint(new JSONObject().put("refs",actual==null?new JSONArray():actual))))
                throw new SecurityException("原排队输入的附件或原文引用已变化");
        }
    }
    /** Native opaque selection only; no independent transaction, source grant or model command. */
    JSONObject mutateQueuedOwnerMessage(dev.pocketengine.store.TaskStore original,dev.pocketengine.store.TaskStore.QueuedOwnerMessage selected,
            String content,String mode,boolean withdrawn)throws Exception{
        selected.requireMutationScope(original);if(monitor!=original)throw new SecurityException("不能替换原消息数据库宿主");
        JSONObject expected=new JSONObject().put("messageId",selected.messageId).put("clientMessageId",selected.clientMessageId)
                .put("ownerMessageRevision",selected.currentMessageRevision).put("acceptedRunId",selected.acceptedRunId).put("acceptedEpoch",selected.acceptedEpoch)
                .put("ownerRequestFingerprint",selected.requestFingerprint).put("acceptedTaskId",selected.taskId)
                .put("content",selected.content)
                .put("mode",selected.mode).put("attachments",selected.attachments()).put("ownerSourceRefs",selected.sourceRefs()).put("ownerPreviewRefs",selected.previewRefs()).put("ownerObjectRefs",selected.objectRefs());
        requireQueuedOwnerMessageCurrent(selected.conversationId,expected);
        JSONObject state=source(selected.messageId),message=message(selected.conversationId,selected.messageId),ack=new JSONObject(state.getJSONObject("submission").toString());
        boolean missingAck="unconfirmed".equals(ack.optString("status"))&&!ack.has("taskId")&&!ack.has("runId")&&!ack.has("epoch");
        if(missingAck)ack.put("taskId",selected.taskId).put("runId",selected.acceptedRunId).put("epoch",selected.acceptedEpoch).put("status","queued").put("reconciliationRequired",false);
        if(!selected.taskId.equals(ack.optString("taskId"))||!mode.matches("queue|steer")||content==null||content.length()>12000
                ||withdrawn&&(!content.equals(message.getString("content"))||!mode.equals(selected.mode)))
            throw new SecurityException("修改范围不属于同一原生待处理输入");
        long next=Math.addExact(selected.currentMessageRevision,1L),queue=Math.addExact(selected.queueRevision,1L),now=System.currentTimeMillis();
        ack.put("queueRevision",queue).put("ownerMessageRevision",next).put("queueState",withdrawn?"withdrawn":"pending").put("mode",mode);
        if(withdrawn){
            ack.put("status","withdrawn").put("withdrawnAt",now);
            // A pending intention was never consumed. Retain owner audit bytes, but do not make
            // the incremented revision a fresh model/history/learning source after withdrawal.
            state.put("memorySourceRetiredThrough",next).put("memoryContextRetired",true).put("retirementReason","owner_correction");
            message.put("memorySourceRetiredThrough",next).put("memoryContextRetired",true).put("retirementReason","owner_correction");
        }
        // Preserve original client/fingerprint/task/run/epoch and references. Withdrawal is not forget.
        message.put("content",content).put("revision",next).put("inputMode",mode).put("submission",new JSONObject(ack.toString())).put(withdrawn?"withdrawnAt":"editedAt",now);
        state.put("revision",next).put("updatedAt",now).put("submission",ack);saveSource(state);saveMessage(selected.conversationId,message);
        JSONObject header=header(selected.conversationId);header.put("updatedAt",now);saveConversation(header);return new JSONObject(message.toString());
    }
    /** Already-checkpointed private native reply proof. Same original SQL transaction only;
     * neither this callback nor a historical message ID can start a task or grant new input. */
    JSONObject acceptQueuedOwnerReply(dev.pocketengine.store.TaskStore original,dev.pocketengine.store.TaskStore.QueuedOwnerReplySelection selected,
            String text,String digest)throws Exception{
        selected.requireMutationScope(original);if(monitor!=original)throw new SecurityException("不能替换原完成消息数据库宿主");
        requireReplyContent(text);
        if(digest==null||digest.isEmpty())throw new IllegalArgumentException("原答复摘要缺失");
        JSONObject indexed=sourceByReply(selected.completed.id);
        if(indexed!=null){
            JSONObject prior=message(selected.conversationId,indexed.getString("sourceId"));
            if(prior==null||!selected.conversationId.equals(indexed.optString("conversationId"))
                    ||!digest.equals(indexed.optString("nativeReplyDigest"))||!selected.taskId.equals(prior.optString("taskId"))
                    ||!selected.runId.equals(prior.optString("nativeRunId"))||selected.epoch!=integer(prior,"nativeEpoch",1))
                throw new SecurityException("原答复身份不能更换内容、任务或执行代次");
            return new JSONObject().put("duplicate",true).put("message",prior).put("consumed",new JSONArray());
        }
        JSONArray inputs=selected.inputs(),consumedRows=new JSONArray(),replyTo=new JSONArray();long now=System.currentTimeMillis();
        String replyId;do{replyId=java.util.UUID.randomUUID().toString();}while(source(replyId)!=null);
        for(int i=0;i<inputs.length();i++){
            JSONObject row=inputs.getJSONObject(i);requireQueuedOwnerMessageCurrent(selected.conversationId,row);
            String id=row.getString("messageId");JSONObject state=source(id),message=message(selected.conversationId,id),ack=new JSONObject(state.getJSONObject("submission").toString());
            if("consumed".equals(ack.optString("status")))throw new SecurityException("原输入已记录采用，未重复写成本次新采用");
            boolean missingAck="unconfirmed".equals(ack.optString("status"))&&!ack.has("taskId")&&!ack.has("runId")&&!ack.has("epoch");
            if(missingAck)ack.put("taskId",selected.taskId).put("runId",row.getString("acceptedRunId")).put("epoch",integer(row,"acceptedEpoch",1));
            if(!selected.taskId.equals(ack.optString("taskId"))||!row.getString("acceptedRunId").equals(ack.optString("runId"))||integer(row,"acceptedEpoch",1)!=integer(ack,"epoch",1))
                throw new SecurityException("输入原接收身份不能被恢复答复替换");
            ack.put("status","consumed").put("queueState","materialized").put("queueRevision",integer(row,"queueRevision",1))
                    .put("ownerMessageRevision",integer(row,"ownerMessageRevision",1)).put("mode",row.getString("mode"))
                    .put("consumedRunId",selected.runId).put("consumedEpoch",selected.epoch).put("replyMessageId",replyId).put("consumedAt",now).put("reconciliationRequired",false);
            state.put("submission",new JSONObject(ack.toString())).put("updatedAt",now);message.put("submission",new JSONObject(ack.toString()));
            saveSource(state);saveMessage(selected.conversationId,message);consumedRows.put(ack);replyTo.put(id);
        }
        JSONObject reply=new JSONObject().put("id",replyId).put("revision",1L).put("role","assistant").put("content",text).put("at",now)
                .put("conversationId",selected.conversationId).put("taskId",selected.taskId).put("nativeReplyId",selected.completed.id).put("nativeRunId",selected.runId).put("nativeEpoch",selected.epoch).put("replyToMessageIds",replyTo);
        if(selected.completed.memoryReference()!=null)reply.put("memoryReference",selected.completed.memoryReference());
        if(selected.completed.memoryContext()!=null)reply.put("memoryContext",selected.completed.memoryContext());
        JSONObject state=new JSONObject().put("kind","message").put("sourceId",replyId).put("revision",1L).put("ownerId","owner").put("spaceId","private")
                .put("conversationId",selected.conversationId).put("role","assistant").put("deleted",false).put("updatedAt",now)
                .put("nativeReplyId",selected.completed.id).put("nativeReplyDigest",digest).put("nativeRunId",selected.runId).put("nativeEpoch",selected.epoch).put("nativeTaskId",selected.taskId);
        saveSource(state);saveMessage(selected.conversationId,reply);bindNativeReplyContext(original,selected.conversationId,replyId,selected.taskId,selected.runId,selected.epoch);JSONObject header=header(selected.conversationId);header.put("updatedAt",now);saveConversation(header);
        return new JSONObject().put("duplicate",false).put("message",reply).put("consumed",consumedRows);
    }
// Original TaskStore transaction commits the accepted answer and real USER source together.
    public void recordOwnerQuestionInput(dev.pocketengine.store.TaskStore original,OwnerQuestionInputs.Reply accepted)throws Exception{
        if(monitor!=original||!Thread.holdsLock(monitor)||!db.inTransaction()||accepted.duplicate())throw new SecurityException("问题原文只能随原TaskStore首次接收事务保存");
        JSONObject supplied=accepted.row(),task=original.get(accepted.result().getString("taskId"),false);OwnerQuestionInputs.requirePrivateTask(task);
        String conversation=task.getString("conversationId");if(!exists(conversation))throw new SecurityException("原私人对话已删除或不可用，未复活旧会话");
        JSONObject row=null;JSONArray rows=task.getJSONArray("ownerInputs");
        for(int i=0;i<rows.length();i++)if(supplied.getString("messageId").equals(rows.getJSONObject(i).optString("messageId"))){if(row!=null)throw new SecurityException("原问题输入身份重复");row=rows.getJSONObject(i);}
        if(row==null)throw new SecurityException("问题答复尚未在同一原生任务接收");
        for(String key:List.of("messageId","clientMessageId","content","mode","acceptedTaskId","acceptedRunId","acceptedEpoch","ownerMessageRevision","ownerRequestFingerprint"))
            if(!Objects.equals(supplied.get(key),row.get(key)))throw new SecurityException("问题输入不等于原生已接收记录");
        if(!"pending".equals(row.optString("queueState"))||!Integer.valueOf(1).equals(row.opt("queueFormat"))
                ||!task.getString("id").equals(row.getString("acceptedTaskId"))||!task.getString("runId").equals(row.getString("acceptedRunId"))||task.getInt("coordinatorEpoch")!=row.getInt("acceptedEpoch")
                ||!row.getString("messageId").equals(task.getJSONObject("evidence").getJSONObject("ownerQuestion").optString("replyMessageId")))throw new SecurityException("原问题接收身份已改变");
        String id=row.getString("messageId"),client=row.getString("clientMessageId");if(source(id)!=null||sourceByClient(client)!=null)throw new SecurityException("问题输入不能覆盖旧主人来源");
        long now=row.getLong("acceptedAt");JSONObject ack=new JSONObject().put("status","accepted").put("messageId",id).put("clientMessageId",client).put("conversationId",conversation)
                .put("taskId",task.getString("id")).put("runId",row.getString("acceptedRunId")).put("epoch",row.getInt("acceptedEpoch")).put("acceptedAt",now)
                .put("queueRevision",row.getLong("queueRevision")).put("queueState","pending").put("ownerMessageRevision",1L).put("mode","queue").put("reconciliationRequired",false).put("executionAuthorized",false);
        JSONObject message=new JSONObject().put("id",id).put("revision",1L).put("role","user").put("content",row.getString("content")).put("at",now).put("conversationId",conversation)
                .put("clientMessageId",client).put("inputMode","queue").put("attachments",new JSONArray()).put("requestFingerprint",row.getString("ownerRequestFingerprint")).put("submission",new JSONObject(ack.toString()));
        JSONObject state=new JSONObject().put("kind","message").put("sourceId",id).put("revision",1L).put("ownerId","owner").put("spaceId","private").put("role","user")
                .put("conversationId",conversation).put("deleted",false).put("updatedAt",now).put("clientMessageId",client).put("requestFingerprint",row.getString("ownerRequestFingerprint")).put("submission",ack);
        saveSource(state);saveMessage(conversation,message);JSONObject header=header(conversation);header.put("updatedAt",now);saveConversation(header);
        requireQueuedOwnerMessageCurrent(conversation,row);
    }
    /** Native owner/UI window, not a retention operation. Import records never become SourceRefs. */
    public JSONObject window(String conversation,String cursor,int limit)throws Exception{
        if(limit<1||limit>80)throw new IllegalArgumentException("消息窗口最多80条");JSONObject result=header(conversation),pos=decode(cursor);long upper=pos.optLong("before",Long.MAX_VALUE);JSONArray reverse=new JSONArray();long headerBytes=result.toString().getBytes(StandardCharsets.UTF_8).length,bytes=headerBytes,last=-1;boolean more=false;int scanned=0;JSONArray gaps=new JSONArray();
        // A row that cannot fit even on an otherwise empty page must advance as a gap.
        // Temporary remaining-budget exhaustion below still leaves the row for the next page.
        String gen=activeGeneration();
        try(Cursor rows=db.rawQuery("SELECT id,n,bytes,kind FROM (SELECT m.source_id id,m.sequence n,length(CAST(m.body AS BLOB))+length(CAST(m.meta_json AS BLOB))+length(CAST(s.source_json AS BLOB)) bytes,'native' kind FROM assistant_messages m JOIN assistant_sources s ON s.generation=m.generation AND s.source_id=m.source_id WHERE m.generation=? AND m.conversation_id=? AND m.body IS NOT NULL UNION ALL SELECT record_id id,sequence n,byte_length bytes,'legacy' kind FROM assistant_import_records WHERE generation=? AND conversation_id=? AND deleted=0) WHERE n<? ORDER BY n DESC LIMIT ?",new String[]{gen,conversation,gen,conversation,Long.toString(upper),Integer.toString(limit+1)})){
            while(rows.moveToNext()){
                if(scanned>=limit){more=true;break;}long size=rows.getLong(2);if(size>MAX_BYTES-headerBytes){gaps.put(new JSONObject().put("recordId",rows.getString(0)).put("status","read_limited"));last=rows.getLong(1);scanned++;continue;}
                if(size>MAX_BYTES-bytes){more=true;break;}
                boolean imported="legacy".equals(rows.getString(3));JSONObject message=imported?importedMessage(conversation,rows.getString(0)):message(conversation,rows.getString(0));last=rows.getLong(1);scanned++;if(message!=null){
                    // Owner page metadata only. Selection still reads this exact original again;
                    // an imported identity never becomes a model-source reference.
                    message.remove("ownerOriginalLocator");
                    if(!imported)message.put("ownerOriginalLocator",new JSONObject().put("kind","owner_native_original").put("generation",gen)
                            .put("conversationId",conversation).put("recordId",message.getString("id")).put("recordVersion",message.getLong("revision"))
                            .put("fingerprint","sha256:"+shaBytes(message.getString("content").getBytes(StandardCharsets.UTF_8))));
                    reverse.put(message);bytes+=size;
                }
            }
        }
        JSONArray messages=new JSONArray();for(int i=reverse.length()-1;i>=0;i--)messages.put(reverse.getJSONObject(i));String next=more&&last>=0?encode(new JSONObject().put("before",last)):"";
        return result.put("messages",messages).put("messagePage",new JSONObject().put("hasMore",more).put("nextCursor",next).put("scannedRows",scanned).put("materializedOriginalRecordUtf8Bytes",bytes).put("gaps",gaps).put("retentionLimitApplied",false));
    }
    public JSONObject importedMessage(String conversation,String record)throws Exception{
        String gen=activeGeneration();try(Cursor row=db.rawQuery("SELECT r.record_version,r.historical_revision,r.at_ms,r.role,r.source_sha,r.byte_offset,r.byte_length,r.fragment_sha,length(f.data) FROM assistant_import_records r JOIN assistant_conversations c ON c.generation=r.generation AND c.conversation_id=r.conversation_id JOIN assistant_import_fragments f ON f.generation=r.generation AND f.source_sha=r.source_sha AND f.byte_offset=r.byte_offset WHERE r.generation=? AND r.conversation_id=? AND r.record_id=? AND r.deleted=0 AND c.deleted=0 LIMIT 1",new String[]{gen,conversation,record})){
            if(!row.moveToFirst()||row.isNull(8))return null;if(row.getLong(6)>MAX_BYTES||row.getLong(8)!=row.getLong(6))throw new IllegalStateException("原件段超过读取预算或长度已变化");
            byte[] original;try(Cursor fragment=db.rawQuery("SELECT data FROM assistant_import_fragments WHERE generation=? AND source_sha=? AND byte_offset=? AND data IS NOT NULL LIMIT 1",new String[]{gen,row.getString(4),Long.toString(row.getLong(5))})){if(!fragment.moveToFirst())return null;original=fragment.getBlob(0);}
            if(!row.getString(7).equals(shaBytes(original)))throw new SecurityException("原件段指纹不符，未展示替代正文");JSONObject raw=new JSONObject(new String(original,StandardCharsets.UTF_8));Object content=raw.opt("content");String body=content instanceof String?(String)content:content==null?"":content.toString();
            JSONObject value=LegacyConversationImport.ownerView(raw).put("id",record).put("recordId",record).put("recordVersion",row.getLong(0)).put("revision",JSONObject.NULL).put("historicalRevision",row.isNull(1)?JSONObject.NULL:row.getLong(1)).put("historicalRevisionKnown",!row.isNull(1)).put("importAssignedIdentity",true).put("formalSourceRefAvailable",false).put("identityProvenance","import_assigned_not_historical_source").put("role",row.getString(3)).put("content",body).put("originalIdentity",new JSONObject().put("id",raw.has("id")?raw.get("id"):JSONObject.NULL).put("revision",raw.has("revision")?raw.get("revision"):JSONObject.NULL)).put("originalByteReference",new JSONObject().put("sourceSha256",row.getString(4)).put("offset",row.getLong(5)).put("length",row.getLong(6)).put("unit","UTF-8 bytes").put("fragmentSha256",row.getString(7)));
            String importKind="legacy_unverified";try(Cursor document=db.rawQuery("SELECT source_format FROM assistant_import_documents WHERE generation=? AND source_sha=? LIMIT 1",new String[]{gen,row.getString(4)})){if(document.moveToFirst()&&"conversation_export_v1".equals(document.getString(0)))importKind="conversation_export_content";}
            value.put("importKind",importKind);if(row.isNull(2))value.put("at",JSONObject.NULL);else value.put("at",row.getLong(2));return value;
        }
    }
    public void deleteImportedMessage(String conversation,String record,long version)throws Exception{
        String gen=activeGeneration();ContentValues tombstone=new ContentValues();tombstone.put("deleted",1);tombstone.put("unavailable_reason","user_message_delete");tombstone.put("record_version",version+1);
        if(db.update("assistant_import_records",tombstone,"generation=? AND conversation_id=? AND record_id=? AND record_version=? AND deleted=0",new String[]{gen,conversation,record,Long.toString(version)})!=1)throw new IllegalStateException("原导入记录已变化，请刷新后重试");
        ContentValues erased=new ContentValues();erased.putNull("data");db.update("assistant_import_fragments",erased,"generation=? AND record_id=?",new String[]{gen,record});db.execSQL("UPDATE assistant_import_documents SET redacted=1 WHERE generation=?",new Object[]{gen});
    }
    public void deleteConversation(String conversation,String reason)throws Exception{ContentValues c=new ContentValues();c.put("deleted",1);c.put("delete_reason",reason);c.put("header_json",new JSONObject().put("conversationId",conversation).put("deleted",true).toString());if(db.update("assistant_conversations",c,"generation=? AND conversation_id=? AND deleted=0",new String[]{activeGeneration(),conversation})!=1)throw new IllegalArgumentException("对话不存在");db.execSQL("UPDATE assistant_sources SET revision=revision+1,deleted=1,unavailable_reason=? WHERE generation=? AND conversation_id=? AND deleted=0",new Object[]{reason,activeGeneration(),conversation});ContentValues erased=new ContentValues();erased.putNull("body");erased.put("body_bytes",0);erased.put("body_sha","");db.update("assistant_messages",erased,"generation=? AND conversation_id=?",new String[]{activeGeneration(),conversation});
        db.execSQL("UPDATE assistant_import_records SET deleted=1,record_version=record_version+1,unavailable_reason=? WHERE generation=? AND conversation_id=? AND deleted=0",new Object[]{reason,activeGeneration(),conversation});ContentValues fragments=new ContentValues();fragments.putNull("data");db.update("assistant_import_fragments",fragments,"generation=? AND conversation_id=?",new String[]{activeGeneration(),conversation});db.execSQL("UPDATE assistant_import_documents SET redacted=1 WHERE generation=?",new Object[]{activeGeneration()});ConversationOrganization.changed(db,activeGeneration());}
    public int retire(JSONArray removed,String cause)throws Exception{
        int affected=0;String gen=activeGeneration();for(int i=0;i<removed.length();i++){JSONArray refs=removed.getJSONObject(i).optJSONArray("sourceRefs");for(int j=0;refs!=null&&j<refs.length();j++){SourceRef ref=SourceRef.fromJson(refs.getJSONObject(j));if(!"message".equals(ref.kind)||!"owner".equals(ref.ownerId)||!"private".equals(ref.spaceId))continue;
            try(var update=db.compileStatement("UPDATE assistant_sources SET retired_through=?,retirement_reason=? WHERE generation=? AND source_id=? AND retired_through<?")){update.bindLong(1,ref.revision);update.bindString(2,cause);update.bindString(3,gen);update.bindString(4,ref.sourceId);update.bindLong(5,ref.revision);affected=Math.addExact(affected,update.executeUpdateDelete());}
        }}
        try(var update=db.compileStatement("UPDATE assistant_sources SET retirement_reason=?,context_retired=1 WHERE generation=? AND role='assistant' AND context_retired=0")){update.bindString(1,cause);update.bindString(2,gen);affected=Math.addExact(affected,update.executeUpdateDelete());}return affected;
    }
    public JSONObject state(String conversation,SourceRef expected)throws Exception{
        JSONObject state=metadata(expected.sourceId);String status="provenance_unavailable",reason="unknown";
        if(state!=null&&conversation.equals(state.optString("conversationId"))){
            if(state.getBoolean("deleted")){status="deleted";reason=state.optString("unavailableReason","unknown");}
            else if(state.getLong("revision")!=expected.revision){status="source_changed";reason="message_revision_changed";}
            else if(state.optLong("memorySourceRetiredThrough")>=expected.revision||state.optBoolean("memoryContextRetired")){status="retired";reason=state.optString("retirementReason","unknown");}
            else{try{requireDerivedMessageCurrent(expected.sourceId,expected.revision);status="current";reason="";}catch(SourceUnavailable unavailable){status="retired";reason="derived_source_unavailable";}}
        }
        return new JSONObject().put("sourceRef",expected.toJson()).put("status",status).put("reason",reason);
    }
    /** Minimal original model evidence; owner/UI metadata is not materialized on this path. */
    public JSONObject historyMessage(String conversation,String id,CancellationSignal signal)throws Exception{return historyMessage(conversation,id,signal,MAX_BYTES);}
    JSONObject historyMessage(String conversation,String id,CancellationSignal signal,long maximumBytes)throws Exception{
        JSONObject state=metadata(id);if(state==null||state.getBoolean("deleted")||!conversation.equals(state.getString("conversationId")))return null;
        try{requireDerivedMessageCurrent(id,state.getLong("revision"));}catch(SourceUnavailable unavailable){return null;}
        try(Cursor size=db.rawQuery("SELECT length(CAST(body AS BLOB)) FROM assistant_messages WHERE generation=? AND conversation_id=? AND source_id=? AND body IS NOT NULL LIMIT 1",new String[]{activeGeneration(),conversation,id},signal)){if(!size.moveToFirst())return null;if(size.getLong(0)>Math.min(MAX_BYTES,maximumBytes))throw new ReadLimited("original_message_exceeds_inline_budget");}
        try(Cursor row=db.rawQuery("SELECT body,revision,at_ms FROM assistant_messages WHERE generation=? AND conversation_id=? AND source_id=? AND body IS NOT NULL LIMIT 1",new String[]{activeGeneration(),conversation,id},signal)){
            if(!row.moveToFirst())return null;if(row.getLong(1)!=state.getLong("revision"))throw new SecurityException("原消息修订与native来源不一致");JSONObject value=new JSONObject().put("id",id).put("revision",row.getLong(1)).put("role",state.getString("role")).put("content",row.getString(0));if(!row.isNull(2))value.put("at",row.getLong(2));return value;
        }
    }
    /** Native owner page only; never consumed by ConversationHistory model providers. */
    private OriginalHistorySearch ownerOriginalSearch;
    private OriginalHistorySearch ownerOriginalSearch(){synchronized(monitor){activeGeneration();if(ownerOriginalSearch==null)ownerOriginalSearch=new OriginalHistorySearch(db,monitor,this,this::activeGeneration,monotonicNanos);return ownerOriginalSearch;}}
    public JSONObject searchOwnerOriginals(JSONObject options,RunControl original)throws Exception{return ownerOriginalSearch().search(options,original);}
    public JSONObject readOwnerSearchHit(JSONObject request,RunControl original)throws Exception{return ownerOriginalSearch().read(request,original);}
    /** One bounded, body-free currentness batch. No TaskStore/Book monitor or callback is acquired. */
    public static final class SourceUnavailable extends Exception {SourceUnavailable(String reason){super(reason);}}
    public OwnerSourceInspection inspectOwnerSources(){return new OwnerSourceInspection(null);}
    public OwnerSourceInspection inspectOwnerSources(ConversationHistory.SourceBytes bytes){return new OwnerSourceInspection(bytes);}
    public final class OwnerSourceInspection implements AutoCloseable {
        private final ConversationHistory.SourceBytes externalBytes;
        private OwnerSourceInspection(ConversationHistory.SourceBytes bytes){externalBytes=bytes;}
        private final CancellationSignal signal=new CancellationSignal();
        private final long deadlineNanos=System.nanoTime()+500_000_000L;
        private final java.util.concurrent.ScheduledFuture<?> deadline=DEADLINES.schedule(signal::cancel,500,java.util.concurrent.TimeUnit.MILLISECONDS);
        private final Map<String,JSONObject> checkedSources=new LinkedHashMap<>(),checkedCarriers=new LinkedHashMap<>(),checkedNativeUsers=new LinkedHashMap<>(),checkedAssistants=new LinkedHashMap<>();
        private long materializedBytes;private int statements;private boolean closed;
        private void current(){if(closed||!healthy||signal.isCanceled()||System.nanoTime()>deadlineNanos||Thread.currentThread().isInterrupted())throw new IllegalStateException("原文引用批次超时、停止或权威不确定");}
        private void statement(){current();if(++statements>500)throw new IllegalStateException("原文引用metadata批次超过语句预算");}
        private long remainingBytes()throws Exception{return Math.min(MAX_BYTES-materializedBytes,externalBytes==null?MAX_BYTES:externalBytes.remaining());}
        private Exception limited(String reason){return externalBytes==null?new ReadLimited(reason):new dev.pocketengine.features.memory.TaskStoreMemoryReader.RawMessageUnavailable(reason);}
        private void materialized(long bytes)throws Exception{if(bytes<0||bytes>remainingBytes())throw limited("owner_source_batch_bytes");if(externalBytes!=null)externalBytes.charge(bytes);materializedBytes=Math.addExact(materializedBytes,bytes);}
        public void requireSource(JSONObject supplied)throws Exception {
            current();JSONObject ref=OwnerSourceReferences.checked(new JSONArray().put(supplied)).getJSONObject(0),source=ref.getJSONObject("sourceRef");statement();
            // Active generation, source, body version/hash and conversation tombstone are
            // constrained by the same read statement. Normal unrelated tail writes do not revoke it.
            String sql="SELECT a.value,s.conversation_id,s.revision,s.deleted,s.retired_through,s.context_retired,coalesce(c.deleted,1),m.revision,m.body_sha,m.body IS NULL FROM meta a LEFT JOIN assistant_sources s ON s.generation=a.value AND s.source_id=? LEFT JOIN assistant_conversations c ON c.generation=s.generation AND c.conversation_id=s.conversation_id LEFT JOIN assistant_messages m ON m.generation=s.generation AND m.source_id=s.source_id AND m.conversation_id=s.conversation_id WHERE a.key='assistant_history_generation' LIMIT 1";
            try(Cursor row=db.rawQuery(sql,new String[]{source.getString("sourceId")},signal)){
                if(!row.moveToFirst()||!ref.getString("generation").equals(row.getString(0))||row.isNull(2)
                        ||!ref.getString("sourceConversationId").equals(row.getString(1))||source.getLong("revision")!=row.getLong(2)
                        ||row.getInt(3)!=0||row.getLong(4)>=row.getLong(2)||row.getInt(5)!=0||row.getInt(6)!=0
                        ||row.isNull(7)||row.getLong(7)!=row.getLong(2)||!ref.getString("bodyFingerprint").equals(row.getString(8))||row.getInt(9)!=0)
                    throw new SourceUnavailable("所选原文已删除、停用、换版或不再属于这份原来源");
            }
            requireDerivedMessageCurrent(source.getString("sourceId"),source.getLong("revision"));
            current();String key=TaskMemoryTrace.rawFingerprint(ref);if(!checkedSources.containsKey(key)&&checkedSources.size()>=100)throw new IllegalStateException("原文引用currentness最多100个精确范围");checkedSources.put(key,ref);
        }
        public void requireNativeAssistantSource(JSONObject pin)throws Exception{
            current();if(pin==null||pin.length()!=4||pin.optJSONObject("sourceRef")==null||pin.getJSONObject("sourceRef").length()!=5)throw new SourceUnavailable("助手历史来源格式不可核对");
            SourceRef ref=SourceRef.fromJson(pin.getJSONObject("sourceRef"));String gen=pin.getString("generation"),conversation=pin.getString("sourceConversationId"),fingerprint=pin.getString("bodyFingerprint");
            if(!"message".equals(ref.kind)||!"owner".equals(ref.ownerId)||!"private".equals(ref.spaceId)||gen.isEmpty()||gen.length()>80||!fingerprint.matches("sha256:[a-f0-9]{64}"))throw new SourceUnavailable("助手历史来源身份无效");statement();
            try(Cursor row=db.rawQuery("SELECT 1 FROM meta a JOIN assistant_sources s ON s.generation=a.value JOIN assistant_messages m ON m.generation=s.generation AND m.source_id=s.source_id AND m.conversation_id=s.conversation_id JOIN assistant_conversations c ON c.generation=s.generation AND c.conversation_id=s.conversation_id WHERE a.key='assistant_history_generation' AND a.value=? AND s.source_id=? AND s.revision=? AND s.role IN ('user','assistant') AND s.conversation_id=? AND m.revision=s.revision AND m.body_sha=? AND m.body IS NOT NULL AND s.deleted=0 AND s.retired_through<s.revision AND s.context_retired=0 AND c.deleted=0 LIMIT 1",new String[]{gen,ref.sourceId,Long.toString(ref.revision),conversation,fingerprint},signal)){
                if(!row.moveToFirst())throw new SourceUnavailable("助手历史来源已删除、停用、换版或换代");
            }
            requireDerivedMessageCurrent(ref.sourceId,ref.revision);current();String key=ref.sourceId+":"+ref.revision;
            if(!checkedAssistants.containsKey(key)&&checkedAssistants.size()>=100)throw limited("native_assistant_source_budget");checkedAssistants.put(key,new JSONObject(pin.toString()));
        }
        public boolean nativeOwnerInput(JSONObject row,String conversation)throws Exception{
            if(conversation==null||conversation.isEmpty()||!"user".equals(row.optString("role"))||!(row.opt("ownerMessageId") instanceof String))return false;
            Object revision=row.opt("ownerMessageRevision");if(!(revision instanceof Integer||revision instanceof Long)||((Number)revision).longValue()<1)return false;statement();
            String sql="SELECT 1 FROM meta a JOIN assistant_sources s ON s.generation=a.value JOIN assistant_messages m ON m.generation=s.generation AND m.source_id=s.source_id AND m.conversation_id=s.conversation_id JOIN assistant_conversations c ON c.generation=s.generation AND c.conversation_id=s.conversation_id WHERE a.key='assistant_history_generation' AND s.source_id=? AND s.revision=? AND m.revision=s.revision AND s.conversation_id=? AND s.role='user' AND s.deleted=0 AND s.retired_through<s.revision AND s.context_retired=0 AND m.body IS NOT NULL AND c.deleted=0 LIMIT 1";
            boolean found;try(Cursor actual=db.rawQuery(sql,new String[]{row.getString("ownerMessageId"),revision.toString(),conversation},signal)){found=actual.moveToFirst();}current();return found;
        }
        /** Body-free proof for one actual native request row. A retained ID on a summary,
         * truncated row or retirement notice never proves that the original body was supplied. */
        public JSONObject nativeUserPart(JSONObject row,String conversation)throws Exception {
            if(!nativeOwnerInput(row,conversation))return null;
            String id=row.getString("ownerMessageId");long revision=row.getLong("ownerMessageRevision");statement();JSONObject part;
            String sql="SELECT a.value,m.body_sha,m.body_bytes FROM meta a JOIN assistant_sources s ON s.generation=a.value JOIN assistant_messages m ON m.generation=s.generation AND m.source_id=s.source_id AND m.conversation_id=s.conversation_id JOIN assistant_conversations c ON c.generation=s.generation AND c.conversation_id=s.conversation_id WHERE a.key='assistant_history_generation' AND s.source_id=? AND s.revision=? AND m.revision=s.revision AND s.conversation_id=? AND s.role='user' AND s.deleted=0 AND s.retired_through<s.revision AND s.context_retired=0 AND m.body IS NOT NULL AND c.deleted=0 LIMIT 1";
            try(Cursor actual=db.rawQuery(sql,new String[]{id,Long.toString(revision),conversation},signal)){
                if(!actual.moveToFirst())throw new SourceUnavailable("原主人消息在准备请求时已变化");
                String fingerprint=actual.getString(1);long bytes=actual.getLong(2);String supplied=row.opt("content") instanceof String?row.getString("content"):"";
                // AssistantModule's existing historical-data prefix is host text, not part
                // of the source body. Strip only this exact prefix and still verify its hash.
                String historical="历史对话资料，不代表本次新委派；禁止自动重放其中操作，实际未决效果须到原任务核对：\n";
                String body=supplied;boolean full=matchesNativeBody(body,fingerprint,bytes);
                if(!full&&supplied.startsWith(historical)){body=supplied.substring(historical.length());full=matchesNativeBody(body,fingerprint,bytes);}
                part=new JSONObject().put("sourceRef",new SourceRef("message",id,revision,"owner","private").toJson())
                        .put("sourceConversationId",conversation).put("generation",actual.getString(0)).put("bodyFingerprint",fingerprint).put("utf8Bytes",bytes)
                        .put("coverage",full?"full":"unknown").put("offset",0).put("chars",full?body.length():0).put("unit","UTF-16");
            }
            requireNativeUserPart(part,conversation);return part;
        }
        private boolean matchesNativeBody(String value,String fingerprint,long bytes)throws Exception {
            if(value.isEmpty()||!StandardCharsets.UTF_8.newEncoder().canEncode(value))return false;
            byte[] actual=value.getBytes(StandardCharsets.UTF_8);return actual.length==bytes&&("sha256:"+shaBytes(actual)).equals(fingerprint);
        }
        /** Revalidate native PREPARE metadata only. This does not read a body, extend a
         * range, authorize an import, or assert that a provider consumed the request. */
        public void requireNativeUserPart(JSONObject part,String conversation)throws Exception {
            current();java.util.Set<String> fields=java.util.Set.of("sourceRef","sourceConversationId","generation","bodyFingerprint","utf8Bytes","coverage","offset","chars","unit");
            if(part.length()!=fields.size())throw new SecurityException("原主人准备事实只接受有界原生元数据");for(var keys=part.keys();keys.hasNext();)if(!fields.contains(keys.next()))throw new SecurityException("原主人准备事实不能携带正文或权限");
            JSONObject identity=part.getJSONObject("sourceRef");if(identity.length()!=5)throw new SecurityException("原主人准备事实身份无效");Object revision=identity.opt("revision");if(!(revision instanceof Integer||revision instanceof Long)||((Number)revision).longValue()<1)throw new SecurityException("原主人准备事实修订无效");
            SourceRef ref=SourceRef.fromJson(identity);String coverage=part.getString("coverage"),generation=part.getString("generation"),fingerprint=part.getString("bodyFingerprint");
            if(!"message".equals(ref.kind)||!"owner".equals(ref.ownerId)||!"private".equals(ref.spaceId)||!conversation.equals(part.getString("sourceConversationId"))||generation.isEmpty()||generation.length()>80||!fingerprint.matches("sha256:[a-f0-9]{64}")||!"UTF-16".equals(part.opt("unit"))||!java.util.Set.of("full","range","unknown").contains(coverage))throw new SecurityException("原主人准备事实不属于原私人消息");
            long bytes=integer(part,"utf8Bytes",0),offset=integer(part,"offset",0),chars=integer(part,"chars",0);
            if(bytes>MAX_BYTES||offset>Integer.MAX_VALUE||chars>Integer.MAX_VALUE||(long)offset+chars>Integer.MAX_VALUE
                    ||"full".equals(coverage)&&(offset!=0||chars<1||bytes<1)||"range".equals(coverage)&&(chars<1||chars>OwnerSourceReferences.MAX_RANGE_CHARS)
                    ||"unknown".equals(coverage)&&(offset!=0||chars!=0))throw new SecurityException("原主人准备事实范围不完整");
            statement();String sql="SELECT 1 FROM meta a JOIN assistant_sources s ON s.generation=a.value JOIN assistant_messages m ON m.generation=s.generation AND m.source_id=s.source_id AND m.conversation_id=s.conversation_id JOIN assistant_conversations c ON c.generation=s.generation AND c.conversation_id=s.conversation_id WHERE a.key='assistant_history_generation' AND a.value=? AND s.source_id=? AND s.revision=? AND m.revision=s.revision AND s.conversation_id=? AND s.role='user' AND s.deleted=0 AND s.retired_through<s.revision AND s.context_retired=0 AND m.body IS NOT NULL AND m.body_sha=? AND m.body_bytes=? AND c.deleted=0 LIMIT 1";
            try(Cursor actual=db.rawQuery(sql,new String[]{generation,ref.sourceId,Long.toString(ref.revision),conversation,fingerprint,Long.toString(bytes)},signal)){if(!actual.moveToFirst())throw new SourceUnavailable("原主人准备来源已删除、停用、换代或换版");}
            current();String key=TaskMemoryTrace.rawFingerprint(part);if(!checkedNativeUsers.containsKey(key)&&checkedNativeUsers.size()>=100)throw limited("native_user_part_budget");checkedNativeUsers.put(key,new JSONObject(part.toString()));
        }
        /** A model/checkpoint cannot invent a selected source on an arbitrary user row. */
        public void requireCarrier(JSONObject row,String conversation)throws Exception {requireReferenceCarrier(row,conversation,"ownerSourceRefs");}
        /** The DOM observation must be the exact selection saved on this native message, not checkpoint JSON. */
        public void requirePreviewCarrier(JSONObject row,String conversation)throws Exception {requireReferenceCarrier(row,conversation,"ownerPreviewRefs");}
        public void requireObjectCarrier(JSONObject row,String conversation)throws Exception {requireReferenceCarrier(row,conversation,"ownerObjectRefs");}
        private static JSONArray selectedReferences(JSONObject row,String field)throws Exception {
            return "ownerObjectRefs".equals(field)?OwnerObjectReferences.checked(row.getJSONArray(field)):"ownerPreviewRefs".equals(field)?dev.pocketengine.features.local.preview.PreviewElementRef.checked(row.getJSONArray(field)):OwnerSourceReferences.checked(row.getJSONArray(field));
        }
        private void requireReferenceCarrier(JSONObject row,String conversation,String field)throws Exception {
            current();if(!nativeOwnerInput(row,conversation))throw new SecurityException("原文引用缺少属于当前原会话的原生主人消息");
            String id=row.getString("ownerMessageId");Object revision=row.opt("ownerMessageRevision");if(!(revision instanceof Integer||revision instanceof Long)||((Number)revision).longValue()<1)throw new SecurityException("原文引用缺少真实主人消息修订");
            JSONArray expected=selectedReferences(row,field);String where=" FROM meta a JOIN assistant_messages m ON m.generation=a.value JOIN assistant_sources s ON s.generation=m.generation AND s.source_id=m.source_id JOIN assistant_conversations c ON c.generation=s.generation AND c.conversation_id=s.conversation_id WHERE a.key='assistant_history_generation' AND m.source_id=? AND m.revision=? AND s.revision=m.revision AND s.role='user' AND s.deleted=0 AND s.retired_through<s.revision AND s.context_retired=0 AND m.body IS NOT NULL AND c.deleted=0 LIMIT 1";
            String[] args={id,Long.toString(((Number)revision).longValue())};long bytes;statement();
            try(Cursor size=db.rawQuery("SELECT length(CAST(m.meta_json AS BLOB))"+where,args,signal)){if(!size.moveToFirst()||size.isNull(0))throw new SourceUnavailable("原主人消息不可核对");bytes=size.getLong(0);if(bytes>remainingBytes())throw limited("owner_source_carrier_metadata_exceeds_batch_bytes");}
            statement();JSONObject nativeMessage;String bounded=where.replace(" LIMIT 1"," AND length(CAST(m.meta_json AS BLOB))<=? LIMIT 1");
            try(Cursor actual=db.rawQuery("SELECT m.meta_json"+bounded,new String[]{args[0],args[1],Long.toString(remainingBytes())},signal)){if(!actual.moveToFirst())throw limited("owner_source_carrier_changed_or_byte_budget");String text=actual.getString(0);materialized(text.getBytes(StandardCharsets.UTF_8).length);nativeMessage=new JSONObject(text);}
            JSONArray actual=nativeMessage.optJSONArray(field);if(actual==null||!TaskMemoryTrace.rawFingerprint(new JSONObject().put("refs",selectedReferences(nativeMessage,field))).equals(TaskMemoryTrace.rawFingerprint(new JSONObject().put("refs",expected))))throw new SecurityException("引用不等于这条原生主人消息的实际选择");
            current();String key=id+"/"+field;if(!checkedCarriers.containsKey(key)&&checkedCarriers.size()>=100)throw limited("owner_source_carrier_row_budget");checkedCarriers.put(key,new JSONObject().put("role","user").put("ownerMessageId",id).put("ownerMessageRevision",revision).put(field,expected).put("_nativeConversationId",conversation));
        }
        public JSONObject readRange(JSONObject reference,RunControl original)throws Exception {
            requireSource(reference);long remaining=remainingBytes();if(remaining<1)throw limited("owner_source_batch_bytes");
            JSONObject page=ownerOriginalSearch().readSelected(reference,original,remaining,deadlineNanos);long read=page.has("applicationMaterializedSqlBlockUtf8Bytes")?page.getLong("applicationMaterializedSqlBlockUtf8Bytes"):page.getLong("applicationMaterializedOriginalUtf8Bytes");
            materialized(read);current();requireSource(reference);return page;
        }
        public long materializedUtf8Bytes(){return materializedBytes;}
        @Override public void close()throws Exception {
            if(closed)return;
            try{
                current();for(JSONObject ref:new ArrayList<>(checkedSources.values()))requireSource(ref);
                for(JSONObject row:new ArrayList<>(checkedCarriers.values()))requireReferenceCarrier(row,row.getString("_nativeConversationId"),row.has("ownerObjectRefs")?"ownerObjectRefs":row.has("ownerPreviewRefs")?"ownerPreviewRefs":"ownerSourceRefs");
                for(JSONObject row:new ArrayList<>(checkedNativeUsers.values()))requireNativeUserPart(row,row.getString("sourceConversationId"));
                for(JSONObject row:new ArrayList<>(checkedAssistants.values()))requireNativeAssistantSource(row);
            }finally{closed=true;deadline.cancel(false);}
        }
    }
    public JSONObject queryHistory(String conversation,JSONObject options,RunControl control)throws Exception{
        String query=options.getString("query"),cursor=options.optString("cursor");int limit=options.getInt("limit"),maxChars=options.getInt("maxChars");long after=options.optLong("createdAfter",-1),before=options.optLong("createdBefore",-1);
        if(query.length()>500||cursor.length()>4000||limit<1||limit>20||maxChars<64||maxChars>16000)throw new IllegalArgumentException("原始历史查询超出预算");
        control.check();synchronized(monitor){if(!exists(conversation))throw new IllegalArgumentException("原私人会话不存在");long started=monotonicNanos.getAsLong(),bytes=0,last=0,upper=0;int rowsRead=0,chars=0;String reason="";JSONArray entries=new JSONArray(),gaps=new JSONArray();boolean more=false;CancellationSignal signal=new CancellationSignal();Runnable cancel=signal::cancel;control.onCancel(cancel);var deadline=DEADLINES.schedule(signal::cancel,500,java.util.concurrent.TimeUnit.MILLISECONDS);
            try{
                String signature=TaskMemoryTrace.rawFingerprint(new JSONObject().put("conversationId",conversation).put("query",query).put("after",after).put("before",before));JSONObject position=decode(cursor);
                if(!cursor.isEmpty()){if(!signature.equals(position.optString("signature")))throw new SecurityException("历史游标不属于此原会话查询");last=position.getLong("last");upper=position.getLong("upper");}
                else try(Cursor row=db.rawQuery("SELECT coalesce(max(sequence),0) FROM assistant_messages WHERE generation=? AND conversation_id=?",new String[]{activeGeneration(),conversation},signal)){row.moveToFirst();upper=row.getLong(0);}
                try(Cursor rows=db.rawQuery("SELECT m.source_id,m.sequence,length(CAST(m.body AS BLOB)),m.at_ms,s.revision FROM assistant_messages m JOIN assistant_sources s ON s.generation=m.generation AND s.source_id=m.source_id WHERE m.generation=? AND m.conversation_id=? AND m.sequence>? AND m.sequence<=? ORDER BY m.sequence LIMIT 101",new String[]{activeGeneration(),conversation,Long.toString(last),Long.toString(upper)},signal)){
                    while(rows.moveToNext()){
                        if(rowsRead>=MAX_ROWS||entries.length()>=limit||chars>=maxChars||monotonicNanos.getAsLong()-started>500_000_000L){more=true;reason=rowsRead>=MAX_ROWS?"source_row_budget":monotonicNanos.getAsLong()-started>500_000_000L?"read_deadline":entries.length()>=limit?"result_limit":"character_limit";break;}
                        if(control.isCancelled()||control.isPaused()||Thread.currentThread().isInterrupted())throw new InterruptedException("原始历史读取已停止");String id=rows.getString(0);long sequence=rows.getLong(1),size=rows.getLong(2);SourceRef ref=new SourceRef("message",id,rows.getLong(4),"owner","private");JSONObject state=state(conversation,ref);rowsRead++;
                        if(!"current".equals(state.optString("status"))){gaps.put(state);last=sequence;continue;}
                        if(size>MAX_BYTES){gaps.put(state.put("status","read_limited").put("reason","message_exceeds_inline_limit"));last=sequence;reason="source_read_limited";continue;}
                        if(size>MAX_BYTES-bytes){more=true;reason="source_byte_budget";break;}
                        if((after>=0&&(rows.isNull(3)||rows.getLong(3)<after))||(before>=0&&(rows.isNull(3)||rows.getLong(3)>before))){last=sequence;continue;}
                        JSONObject message=historyMessage(conversation,id,signal);if(message==null){last=sequence;gaps.put(state.put("status","unavailable").put("reason","source_body_unavailable"));continue;}bytes+=size;
                        if(matches(message.getString("content"),query)){JSONObject row=ConversationHistory.messagePage(conversation,message,0,Math.min(4000,maxChars-chars)).put("role",message.getString("role"));entries.put(row);chars+=row.getString("body").length();}last=sequence;
                    }
                }
                // A late deadline stops further reads; it must not erase already verified hits while
                // publishing a cursor beyond them. These bounded hits are actually delivered below.
                if(monotonicNanos.getAsLong()-started>500_000_000L){more=true;reason="read_deadline";}
                if(control.isCancelled()||control.isPaused()||Thread.currentThread().isInterrupted())throw new InterruptedException("原始历史读取已停止，未返回迟到正文");
                String next=more&&last>0?encode(new JSONObject().put("signature",signature).put("last",last).put("upper",upper)):"";
                return ConversationHistory.proof(new JSONObject().put("status",reason.isEmpty()?"current":"partial").put("reason",reason).put("entries",entries).put("gaps",gaps).put("nextCursor",next).put("hasMore",more).put("charsReturned",chars).put("pageCoverage",new JSONObject().put("sourceKinds",new JSONArray().put("durable_original_message")).put("scannedRows",rowsRead).put("scannedUtf8Bytes",bytes).put("partialReason",reason).put("upperSequence",upper).put("newTailIncluded",false).put("retentionLimitApplied",false).put("originalMessageTotalKnown",false).put("legacyLossRecovered",false).put("importAssignedRecordsNotPromotedToFormalSourceRefs",true).put("byteAccounting","materialized_original_SQLite_body_UTF8_not_physical_io").put("elapsedMillis",(monotonicNanos.getAsLong()-started)/1_000_000L)));
            }finally{deadline.cancel(false);control.removeCancelAction(cancel);}
        }
    }
    private static boolean matches(String text,String query){String value=Normalizer.normalize(text,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);for(String term:Normalizer.normalize(query,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim().split("\\s+"))if(!value.contains(term))return false;return true;}
    /** Bounded sequence adjacency around an already discovered native source. Not a parent/child
     * graph, a new search scope or an all-history cursor. Exact bodies remain in this same DB. */
    public JSONObject neighbors(String conversation,SourceRef anchor,String fingerprint,String direction,JSONObject budgets,RunControl control)throws Exception{
        if(anchor==null||!"message".equals(anchor.kind)||!"owner".equals(anchor.ownerId)||!"private".equals(anchor.spaceId)||fingerprint==null||!fingerprint.matches("sha256:[0-9a-f]{64}")||!Set.of("before","after").contains(direction))throw new SecurityException("邻域缺少当前私人原消息锚点");
        int limit=budgets.getInt("limit"),maxChars=budgets.getInt("maxChars");if(limit<1||limit>20||maxChars<64||maxChars>16000)throw new IllegalArgumentException("邻域读取超过原文预算");
        control.check();long entryPause=control.pauseRevision();
        synchronized(monitor){
            long started=monotonicNanos.getAsLong(),bytes=0;int scanned=0,chars=0,checked=0;boolean more=false;String reason="";JSONArray entries=new JSONArray(),gaps=new JSONArray();
            JSONObject state=state(conversation,anchor),adjacency=new JSONObject().put("kind","same_conversation_message_sequence").put("anchorSourceRef",anchor.toJson()).put("anchorBodyFingerprint",fingerprint).put("direction",direction).put("order","nearest_first").put("parentChildAncestry",false);
            if(!"current".equals(state.optString("status")))return neighborUnavailable(state.getString("status"),state.getString("reason"),adjacency);
            CancellationSignal signal=new CancellationSignal();Runnable cancel=signal::cancel;control.onCancel(cancel);var deadline=DEADLINES.schedule(signal::cancel,500,java.util.concurrent.TimeUnit.MILLISECONDS);
            try{
                long anchorSequence=historyPosition(conversation,anchor,fingerprint,signal);checked++;
                if(anchorSequence<0)return neighborUnavailable("source_changed","anchor_version_or_fingerprint_unavailable",adjacency);
                String gen=activeGeneration(),comparison=direction.equals("before")?"<":">",order=direction.equals("before")?"DESC":"ASC";
                // The UNION includes import-assigned/deleted gaps without reading their bodies.
                // Neither an unknown historical revision nor the internal import version is a SourceRef.
                String sql="SELECT id,n,bytes,revision,kind,deleted,reason FROM (SELECT * FROM (SELECT m.source_id id,m.sequence n,length(CAST(m.body AS BLOB)) bytes,s.revision revision,'native' kind,s.deleted deleted,s.unavailable_reason reason FROM assistant_messages m JOIN assistant_sources s ON s.generation=m.generation AND s.source_id=m.source_id WHERE m.generation=? AND m.conversation_id=? AND m.sequence"+comparison+"? ORDER BY m.sequence "+order+" LIMIT 101) UNION ALL SELECT * FROM (SELECT record_id id,sequence n,0 bytes,0 revision,'import_assigned' kind,deleted,unavailable_reason reason FROM assistant_import_records WHERE generation=? AND conversation_id=? AND sequence"+comparison+"? ORDER BY sequence "+order+" LIMIT 101)) ORDER BY n "+order+" LIMIT 101";
                try(Cursor rows=db.rawQuery(sql,new String[]{gen,conversation,Long.toString(anchorSequence),gen,conversation,Long.toString(anchorSequence)},signal)){
                    while(rows.moveToNext()){
                        if(scanned>=MAX_ROWS||entries.length()>=limit||chars>=maxChars||monotonicNanos.getAsLong()-started>500_000_000L){more=true;reason=scanned>=MAX_ROWS?"source_row_budget":monotonicNanos.getAsLong()-started>500_000_000L?"read_deadline":entries.length()>=limit?"result_limit":"character_limit";break;}
                        requireHistoryReadControl(control,entryPause);scanned++;
                        if(!"native".equals(rows.getString(4))){gaps.put(new JSONObject().put("recordId",rows.getString(0)).put("status",rows.getInt(5)!=0?"deleted":"provenance_unavailable").put("reason",rows.getInt(5)!=0?rows.getString(6):"import_assigned_identity_not_a_historical_source_ref"));if(reason.isEmpty())reason="neighbor_provenance_gap";continue;}
                        SourceRef ref=new SourceRef("message",rows.getString(0),rows.getLong(3),"owner","private");JSONObject current=state(conversation,ref);checked++;
                        if(!"current".equals(current.optString("status"))){gaps.put(current);if(reason.isEmpty())reason="neighbor_source_unavailable";continue;}
                        long size=rows.getLong(2);if(rows.isNull(2)){gaps.put(current.put("status","unavailable").put("reason","source_body_unavailable"));continue;}
                        if(size>MAX_BYTES){gaps.put(current.put("status","read_limited").put("reason","message_exceeds_inline_limit"));reason="source_read_limited";continue;}
                        if(size>MAX_BYTES-bytes){more=true;reason="source_byte_budget";break;}
                        JSONObject message=historyMessage(conversation,ref.sourceId,signal);if(message==null){gaps.put(current.put("status","unavailable").put("reason","source_body_unavailable"));continue;}bytes+=size;
                        JSONObject row=ConversationHistory.messagePage(conversation,message,0,Math.min(4000,maxChars-chars)).put("role",message.getString("role"));if(row.getString("body").isEmpty()){if(!message.getString("content").isEmpty()){more=true;reason="character_boundary_budget";break;}gaps.put(current.put("status","unavailable").put("reason","empty_original_body"));continue;}entries.put(row);chars+=row.getString("body").length();
                    }
                }
                // A normal appended tail does not revoke any selected old row. Only the exact
                // relevant sources and this anchor are rechecked; no whole-conversation generation.
                if(historyPosition(conversation,anchor,fingerprint,signal)!=anchorSequence)throw new SecurityException("邻域锚点在读取时已改变，未返回旧正文");checked++;
                for(int i=0;i<entries.length();i++){JSONObject row=entries.getJSONObject(i);if(historyPosition(conversation,SourceRef.fromJson(row.getJSONObject("sourceRef")),row.getString("bodyFingerprint"),signal)<0)throw new SecurityException("邻域来源在读取时已撤回或改变，未返回旧正文");checked++;}
                if(monotonicNanos.getAsLong()-started>500_000_000L){more=true;reason="read_deadline";}
                requireHistoryReadControl(control,entryPause);
                return ConversationHistory.proof(new JSONObject().put("status",reason.isEmpty()?"current":"partial").put("reason",reason).put("entries",entries).put("gaps",gaps).put("nextCursor","").put("hasMore",more).put("charsReturned",chars).put("adjacency",adjacency)
                        .put("pageCoverage",new JSONObject().put("sourceKinds",new JSONArray().put("durable_original_message").put("import_metadata_gap_only")).put("scannedRows",scanned).put("sourceMetadataChecks",checked).put("metadataCandidateSqlLimitPerBranch",101).put("scannedUtf8Bytes",bytes).put("byteAccounting","materialized_original_SQLite_body_UTF8_not_physical_io").put("physicalIoMeasured",false).put("partialReason",reason).put("retentionLimitApplied",false).put("originalMessageTotalKnown",false).put("legacyLossRecovered",false).put("adjacentSourceScanExhausted",!more).put("allAdjacentBodiesRead",false).put("continuation","select_a_returned_boundary_message_and_query_neighbors_again").put("elapsedMillis",(monotonicNanos.getAsLong()-started)/1_000_000L)));
            }finally{deadline.cancel(false);control.removeCancelAction(cancel);}
        }
    }
    private static void requireHistoryReadControl(RunControl control,long entryPause)throws InterruptedException{
        if(control.isCancelled()||control.isPaused()||control.pauseRevision()!=entryPause||Thread.currentThread().isInterrupted())throw new InterruptedException("邻域读取已停止或跨过暂停，未返回迟到正文");
    }
    private static JSONObject neighborUnavailable(String status,String reason,JSONObject adjacency)throws Exception{return ConversationHistory.proof(new JSONObject().put("status",status).put("reason",reason).put("entries",new JSONArray()).put("gaps",new JSONArray()).put("nextCursor","").put("hasMore",false).put("charsReturned",0).put("adjacency",adjacency));}
    /** One typed metadata-only identity check. Never materializes source_json, meta_json or body. */
    private long historyPosition(String conversation,SourceRef ref,String fingerprint,CancellationSignal signal)throws Exception{
        try(Cursor row=db.rawQuery("SELECT m.sequence FROM meta a JOIN assistant_messages m ON m.generation=a.value JOIN assistant_sources s ON s.generation=m.generation AND s.source_id=m.source_id JOIN assistant_conversations c ON c.generation=m.generation AND c.conversation_id=m.conversation_id WHERE a.key='assistant_history_generation' AND m.generation=? AND m.conversation_id=? AND s.conversation_id=m.conversation_id AND m.source_id=? AND m.revision=? AND s.revision=m.revision AND m.body_sha=? AND m.body IS NOT NULL AND s.deleted=0 AND s.retired_through<s.revision AND s.context_retired=0 AND c.deleted=0 LIMIT 1",new String[]{activeGeneration(),conversation,ref.sourceId,Long.toString(ref.revision),fingerprint},signal)){return row.moveToFirst()?row.getLong(0):-1;}
    }
    public JSONObject stageLegacyArray(String stage,byte[] original,String sourceSha,Callable<Void> beforeBatch)throws Exception{
        if(active())throw new IllegalStateException("已有DB原文权威，不能再次导入旧数组");if(!sourceSha.equals(shaBytes(original)))throw new SecurityException("原始字节SHA与native review不符");
        List<LegacyConversationImport.Conversation> conversations=LegacyConversationImport.index(original,sourceSha);stageTransaction(()->{beforeBatch.call();ContentValues doc=new ContentValues();doc.put("generation",stage);doc.put("source_sha",sourceSha);doc.put("source_bytes",original.length);doc.put("source_format","legacy_array");db.insertOrThrow("assistant_import_documents",null,doc);return null;});
        int previous=0,total=0;for(LegacyConversationImport.Conversation conversation:conversations){final int gap=previous;stageTransaction(()->{beforeBatch.call();fragment(stage,sourceSha,null,null,original,gap,conversation.bytes.start);saveConversation(stage,conversation.header);return null;});int at=conversation.bytes.start,number=0;
            for(LegacyConversationImport.Range range:conversation.messages){final int start=at,index=number;stageTransaction(()->{beforeBatch.call();String record="import-message:"+sourceSha+":"+conversation.bytes.start+":"+index;fragment(stage,sourceSha,conversation.id,null,original,start,range.start);fragment(stage,sourceSha,conversation.id,record,original,range.start,range.end);JSONObject raw=new JSONObject(LegacyConversationImport.text(original,range));ContentValues row=new ContentValues();row.put("generation",stage);row.put("record_id",record);row.put("conversation_id",conversation.id);row.put("sequence",index+1);row.put("record_version",1);Object revision=raw.opt("revision"),time=raw.opt("at");if(actualInteger(revision)&&((Number)revision).longValue()>0)row.put("historical_revision",((Number)revision).longValue());else row.putNull("historical_revision");if(actualInteger(time))row.put("at_ms",((Number)time).longValue());else row.putNull("at_ms");row.put("role",raw.opt("role") instanceof String?raw.getString("role"):"");row.put("source_sha",sourceSha);row.put("byte_offset",range.start);row.put("byte_length",range.length());row.put("fragment_sha",shaBytes(LegacyConversationImport.bytes(original,range)));db.insertOrThrow("assistant_import_records",null,row);return null;});at=range.end;number++;total++;}
            final int tail=at;stageTransaction(()->{beforeBatch.call();fragment(stage,sourceSha,conversation.id,null,original,tail,conversation.bytes.end);return null;});previous=conversation.bytes.end;
        }
        final int tail=previous;stageTransaction(()->{beforeBatch.call();fragment(stage,sourceSha,null,null,original,tail,original.length);return null;});return new JSONObject().put("messages",0).put("importedRecords",total).put("sources",0).put("conversations",conversations.size());
    }
    private void fragment(String stage,String sha,String conversation,String record,byte[] original,int start,int end){if(start==end)return;ContentValues row=new ContentValues();row.put("generation",stage);row.put("source_sha",sha);row.put("byte_offset",start);row.put("byte_length",end-start);if(conversation==null)row.putNull("conversation_id");else row.put("conversation_id",conversation);if(record==null)row.putNull("record_id");else row.put("record_id",record);row.put("data",Arrays.copyOfRange(original,start,end));db.insertOrThrow("assistant_import_fragments",null,row);}
    static String shaBytes(byte[] bytes)throws Exception{byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder hex=new StringBuilder();for(byte value:hash)hex.append(String.format(Locale.ROOT,"%02x",value&255));return hex.toString();}
    private static String knownReason(String reason){return Set.of("","unknown","user_message_delete","user_conversation_delete","capacity_message_limit","capacity_conversation_limit","owner_forget","source_change","owner_correction").contains(reason)?reason:"unknown";}
    private static boolean actualInteger(Object value){return value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long;}
    /** Staging is invisible and lives only in this original DB. Bodies/versions/times are not invented. */
    public JSONObject stageLegacy(String stage,JSONObject legacy,Callable<Void> beforeBatch)throws Exception{
        if(active())throw new IllegalStateException("已有DB原文权威，不能重导旧副本");JSONArray convs=legacy.getJSONArray("conversations"),sources=legacy.getJSONArray("sourceStates");if(integer(legacy,"version",1)!=2)throw new IllegalArgumentException("旧原文需已知原生version2来源，未补造旧修订");
        Set<String> ids=new HashSet<>();for(int start=0;start<sources.length();start+=100){final int a=start,b=Math.min(start+100,sources.length());stageTransaction(()->{beforeBatch.call();for(int i=a;i<b;i++){JSONObject source=new JSONObject(sources.getJSONObject(i).toString());source.remove("nativeContext");if("assistant".equals(source.optString("role")))source.put("nativeProvenanceImported",true);if(!ids.add(source.getString("sourceId")))throw new IllegalArgumentException("旧来源编号重复");saveSource(stage,source);}return null;});}
        int count=0;Set<String> conversationIds=new HashSet<>(),messageIds=new HashSet<>();for(int i=0;i<convs.length();i++){JSONObject conversation=convs.getJSONObject(i);if(!conversationIds.add(conversation.getString("conversationId")))throw new IllegalArgumentException("旧会话编号重复");final JSONObject current=conversation;stageTransaction(()->{beforeBatch.call();saveConversation(stage,current);return null;});JSONArray messages=conversation.getJSONArray("messages");for(int start=0;start<messages.length();start+=40){final int a=start,b=Math.min(start+40,messages.length());stageTransaction(()->{beforeBatch.call();for(int j=a;j<b;j++){JSONObject message=new JSONObject(messages.getJSONObject(j).toString());message.remove("nativeContext");message.remove("nativeAssistantSource");if(!messageIds.add(message.getString("id")))throw new IllegalArgumentException("旧消息编号重复");saveMessage(stage,current.getString("conversationId"),message,j+1);}return null;});}count+=messages.length();}
        try(Cursor missing=db.rawQuery("SELECT 1 FROM assistant_sources s LEFT JOIN assistant_messages m ON m.generation=s.generation AND m.source_id=s.source_id LEFT JOIN assistant_conversations c ON c.generation=s.generation AND c.conversation_id=s.conversation_id WHERE s.generation=? AND s.deleted=0 AND (m.source_id IS NULL OR c.conversation_id IS NULL OR m.conversation_id<>s.conversation_id OR m.revision<>s.revision) LIMIT 1",new String[]{stage})){if(missing.moveToFirst())throw new IllegalArgumentException("旧活动来源缺真实原文/容器，未激活不完整副本");}
        // Tombstones whose old container was already trimmed/deleted still keep their true source
        // identity and revision. Missing header is treated as unavailable, never a live conversation.
        return new JSONObject().put("messages",count).put("importedRecords",0).put("sources",sources.length()).put("conversations",convs.length());
    }
    private <T>T stageTransaction(Callable<T> action)throws Exception{synchronized(monitor){if(active())throw new IllegalStateException("权威已切换，旧stage不能继续");db.beginTransaction();try{T result=action.call();db.setTransactionSuccessful();return result;}finally{db.endTransaction();}}}
    public void activateStaged(String stage,String sourceSha,JSONObject counts)throws Exception{
        if(!Thread.holdsLock(monitor)||active()||!db.inTransaction())throw new SecurityException("迁移激活必须在原TaskStore单事务内，不能覆盖已有权威");
        for(String pair:List.of("assistant_messages:messages","assistant_sources:sources","assistant_conversations:conversations","assistant_import_records:importedRecords")){String[] parts=pair.split(":");try(Cursor row=db.rawQuery("SELECT count(*) FROM "+parts[0]+" WHERE generation=?",new String[]{stage})){row.moveToFirst();if(row.getLong(0)!=integer(counts,parts[1],0))throw new SecurityException("原stage实际记录数不符，未激活");}}
        publishAuthority(stage,sourceSha,counts.getInt("messages"),counts.getInt("sources"));JSONObject marker=authority().put("importedUnverifiedRecords",counts.optInt("importedRecords")).put("historicalRevisionOfImportedRecordsMayBeUnknown",true);ContentValues value=new ContentValues();value.put("value",marker.toString());db.update("meta",value,"key='assistant_history_authority'",null);
    }
    public void recordLegacyCleanup(boolean pending)throws Exception{synchronized(monitor){JSONObject current=authority().put("legacyCleanupPending",pending);ContentValues value=new ContentValues();value.put("value",current.toString());if(db.update("meta",value,"key='assistant_history_authority'",null)!=1)throw new IllegalStateException("原文权威marker已缺失");}}
    /** Called only after the original outer SQLite transaction has positively committed. */
    public void activatedAfterCommit(){synchronized(monitor){reloadAuthority();}}
    public void activationUncertain(){healthy=false;}
    public void discardStage(String stage){synchronized(monitor){if(generation!=null&&generation.equals(stage))throw new SecurityException("不能清理当前原文权威");for(String table:List.of("assistant_import_records","assistant_import_fragments","assistant_import_documents","assistant_messages","assistant_sources","assistant_conversations"))db.delete(table,"generation=?",new String[]{stage});}}
    private static long integer(JSONObject value,String key,long minimum){Object number=value.opt(key);if(!(number instanceof Byte||number instanceof Short||number instanceof Integer||number instanceof Long)||((Number)number).longValue()<minimum)throw new IllegalArgumentException(key+"必须为实际整数");return ((Number)number).longValue();}
    private static String[] append(List<String> list,String value){ArrayList<String> result=new ArrayList<>(list);result.add(value);return result.toArray(new String[0]);}
    private static String encode(JSONObject value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toString().getBytes(StandardCharsets.UTF_8));}
    private static JSONObject decode(String cursor)throws Exception{if(cursor==null||cursor.isEmpty())return new JSONObject();if(cursor.length()>4000)throw new IllegalArgumentException("原文游标过长");return new JSONObject(new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8));}
}
