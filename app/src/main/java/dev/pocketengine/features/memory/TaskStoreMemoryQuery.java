package dev.pocketengine.features.memory;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only keyset query on the original TaskStore tasks table. No JSON1/FTS/index is required. */
public final class TaskStoreMemoryQuery {
    private TaskStoreMemoryQuery(){}
    static final String TRACE_AUTHORITY_SIZE="SELECT length(CAST(document AS BLOB)) FROM tasks WHERE task_id=? LIMIT 1";
    static final String TRACE_AUTHORITY_ROW="SELECT document FROM tasks WHERE task_id=? LIMIT 1";
    static final String TRACE_OPERATION_SIZE="SELECT length(CAST(tool AS BLOB))+length(CAST(arguments AS BLOB))+length(CAST(state AS BLOB))+coalesce(length(CAST(receipt AS BLOB)),0) FROM operations WHERE operation_id=? AND task_id=? AND run_id=? LIMIT 1";
    static final String TRACE_OPERATION_ROW="SELECT operation_id,tool,arguments,state,receipt,run_id,created_at FROM operations WHERE operation_id=? AND task_id=? AND run_id=? LIMIT 1";

    public static final int RAW_MAX_ROWS=100,RAW_MAX_BYTES=1_048_576,RAW_DEADLINE_MILLIS=500;
    private static final String RAW_OPERATION_BYTES="length(CAST(operation_id AS BLOB))+length(CAST(tool AS BLOB))+length(CAST(arguments AS BLOB))+length(CAST(state AS BLOB))+coalesce(length(CAST(receipt AS BLOB)),0)+length(CAST(run_id AS BLOB))";
    private static final String RAW_REFERENCE_TOOLS="'memory_raw_query','memory_raw_read','memory_history_search','memory_history_read','memory_search','memory_recall','skills_list','skills_read','native_memory_reference','native_memory_learning_batch'";
    private static final java.util.concurrent.ScheduledThreadPoolExecutor RAW_DEADLINES=deadlines();
    private static java.util.concurrent.ScheduledThreadPoolExecutor deadlines(){
        var executor=new java.util.concurrent.ScheduledThreadPoolExecutor(1,action->{Thread thread=new Thread(action,"raw-source-deadline");thread.setDaemon(true);return thread;});
        executor.setRemoveOnCancelPolicy(true);executor.setKeepAliveTime(1,java.util.concurrent.TimeUnit.SECONDS);executor.allowCoreThreadTimeOut(true);return executor;
    }
    private static void requireOperationContext(SQLiteDatabase db,dev.pocketengine.features.assistant.ConversationRepository originals,String task,String run,String conversation,ConversationHistory.SourceBytes bytes,CancellationSignal signal)throws Exception{
        // Absence in the latest checkpoint cannot prove that a run never saw an original.
        // Unknown/legacy providers must supply the original native run fact or deny derived reads.
        OwnerSourceRunContext.require(db,originals,task,run,conversation,bytes,signal);
    }
    private static JSONArray projectOwnerSourceContext(SQLiteDatabase db,HistoryInspection window,String task,String run,String conversation,JSONArray messages)throws Exception{
        try{window.operationContext(db,task,run,conversation);return messages;}
        catch(Exception unavailable){return OwnerSourceRunContext.retireDerived(messages);}
    }
    /** Actual model dispatch must stop rather than repeatedly stripping even newly produced results. */
    public static void requireOwnerSourceContinuation(SQLiteDatabase db,dev.pocketengine.features.assistant.ConversationRepository originals,String task,String run,String conversation)throws Exception{
        try(HistoryInspection window=new HistoryInspection(RAW_MAX_BYTES,originals,true)){window.operationContext(db,task,run,conversation);window.recheckOperationContexts(db);}
        catch(Exception unavailable){if(unavailable instanceof InterruptedException||Thread.currentThread().isInterrupted())throw new InterruptedException("来源核对已停止，未继续派发");throw new OwnerSourceRunContext.ContinuationUnavailable(unavailable);}
    }
    public static JSONArray projectOwnerSourceContext(SQLiteDatabase db,dev.pocketengine.features.assistant.ConversationRepository originals,String task,String run,String conversation,JSONArray messages)throws Exception{
        requireOwnerSourceContinuation(db,originals,task,run,conversation);return new JSONArray(messages.toString());
    }
    public static JSONArray outputRunDependencies(SQLiteDatabase db,String task,String run)throws Exception{
        try(HistoryInspection window=new HistoryInspection(RAW_MAX_BYTES,null,false)){JSONArray rows=OwnerSourceRunContext.outputRunDependencies(db,task,run,window.contextBytes(),window.signal);window.check();return rows;}
    }
    public static void requireOutputContext(SQLiteDatabase db,ToolOutputStore.Sources sources,String task,String run)throws Exception{
        try(HistoryInspection window=new HistoryInspection(RAW_MAX_BYTES,null,false)){
            OwnerSourceRunContext.requireOutputs(db,sources,task,run,window.contextBytes(),window.signal);window.check();
        }
    }
    /** Model options only. Scope is exclusively the caller's RawBinding. Dates are real operation time. */
    public static final class RawRequest {
        public final String section,query,cursor;public final int limit,maxChars;public final long after,before;
        public RawRequest(JSONObject input){
            Set<String> allowed=Set.of("section","query","cursor","limit","maxChars","createdAfter","createdBefore");
            if(input==null)throw new IllegalArgumentException("缺少原证据查询");for(Iterator<String> keys=input.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new IllegalArgumentException("原证据查询不能更换任务或空间");
            section=string(input,"section","messages",20);query=string(input,"query","",500);cursor=string(input,"cursor","",4000);
            if(!Set.of("messages","operations","outputs").contains(section))throw new IllegalArgumentException("此入口只查保留消息或原操作");
            limit=bounded(input,"limit",10,1,20);maxChars=bounded(input,"maxChars",4000,64,16000);
            after=input.has("createdAfter")?SourceRef.integer(input,"createdAfter",0):-1;before=input.has("createdBefore")?SourceRef.integer(input,"createdBefore",0):-1;
            if(after>=0&&before>=0&&after>before)throw new IllegalArgumentException("原时间范围颠倒");
        }
        private static String string(JSONObject input,String key,String fallback,int max){Object value=input.opt(key);if(value==null)return fallback;if(!(value instanceof String)||((String)value).length()>max)throw new IllegalArgumentException("原证据文字或游标超过范围");MemoryBook.validateUnicode((String)value);return (String)value;}
        private static int bounded(JSONObject input,String key,int fallback,int min,int max){if(!input.has(key))return fallback;long value=SourceRef.integer(input,key,min);if(value>max)throw new IllegalArgumentException("原证据页范围过大");return (int)value;}
        String signature()throws Exception{return TaskMemoryUse.digest(new JSONObject().put("section",section).put("query",query).put("createdAfter",after).put("createdBefore",before).toString());}
    }
    private static final class RawLimit extends Exception {final String reason;RawLimit(String reason){this.reason=reason;}}
    /** SQL cancellation is best effort; actual duration is reported, never a physical I/O claim. */
    private static final class RawWindow implements AutoCloseable {
        final TaskStoreMemoryReader.RawBinding binding;final CancellationSignal signal=new CancellationSignal();final long started=System.nanoTime();
        final Runnable cancel=signal::cancel;final java.util.concurrent.ScheduledFuture<?> deadline;
        int rows;long bytes,authorityBytes,sourceAuthorityBytes,checkpointBytes,metadataBytes,operationContextBytes;final dev.pocketengine.features.assistant.ConversationRepository originals;final Map<String,String[]> operationContexts=new LinkedHashMap<>();JSONObject currentnessBudget=new JSONObject();volatile boolean timedOut;
        RawWindow(TaskStoreMemoryReader.RawBinding binding){this(binding,null);}
        RawWindow(TaskStoreMemoryReader.RawBinding binding,dev.pocketengine.features.assistant.ConversationRepository originals){this.originals=originals;this.binding=binding;binding.control.onCancel(cancel);deadline=RAW_DEADLINES.schedule(()->{timedOut=true;signal.cancel();},RAW_DEADLINE_MILLIS,java.util.concurrent.TimeUnit.MILLISECONDS);}
        void check()throws Exception{binding.check();if(timedOut||System.nanoTime()-started>=RAW_DEADLINE_MILLIS*1_000_000L)throw new RawLimit("read_deadline");}
        void reserve(long amount)throws RawLimit{if(amount<0||amount>RAW_MAX_BYTES-bytes)throw new RawLimit("source_byte_budget");}
        Cursor query(SQLiteDatabase db,String sql,String[] args)throws Exception{check();return db.rawQuery(sql,args,signal);}
        ConversationHistory.SourceBytes contextBytes(){return new ConversationHistory.SourceBytes(){public long remaining()throws Exception{check();return RAW_MAX_BYTES-bytes;}public void charge(long count)throws Exception{reserve(count);bytes+=count;operationContextBytes+=count;check();}};}
        void operationContext(SQLiteDatabase db,String task,String run,String conversation)throws Exception{
            String key=task+"\n"+run;if(operationContexts.containsKey(key))return;requireOperationContext(db,originals,task,run,conversation,contextBytes(),signal);outputDependencies.add(OwnerSourceRunContext.verifiedOutputDependencies(db,binding.outputSources(),task,run,contextBytes(),signal));operationContexts.put(key,new String[]{task,run,conversation});
        }
        void recheckOperationContexts(SQLiteDatabase db)throws Exception{for(String[] context:operationContexts.values()) {requireOperationContext(db,originals,context[0],context[1],context[2],contextBytes(),signal);outputSourcesCurrent();}}
        final List<JSONArray> outputDependencies=new ArrayList<>();int outputChunks;long outputHeaderBytes,outputBodyBytes,outputChunkMetadataBytes;
        void outputSourcesCurrent()throws Exception{for(JSONArray rows:outputDependencies)ToolOutputStore.requireDependencies(rows,binding.outputSources());}
        ToolOutputStore.ReadBudget outputBudget(){return new ToolOutputStore.ReadBudget(){
            public long remaining()throws Exception{check();return RAW_MAX_BYTES-bytes;}
            public void check()throws Exception{RawWindow.this.check();}
            public void charge(long count,String category)throws Exception{reserve(count);bytes+=count;if(category.equals("body"))outputBodyBytes+=count;else if(category.equals("header"))outputHeaderBytes+=count;else outputChunkMetadataBytes+=count;RawWindow.this.check();}
            public void chunks(int count){outputChunks+=count;}
        };}
        JSONObject coverage(String section,String first,String last,String reason,boolean totalKnown,int retainedCount)throws Exception{
            return new JSONObject().put("sourceKinds",new JSONArray().put(section.equals("messages")?"checkpoint_message":section.equals("outputs")?"retained_safe_native_result_json":"operation_receipt"))
                    .put("firstReadKey",first).put("lastReadKey",last).put("scannedRows",rows).put("scannedUtf8Bytes",bytes)
                    .put("outputHeaderUtf8Bytes",outputHeaderBytes).put("outputBodyUtf8Bytes",outputBodyBytes).put("outputChunkMetadataUtf8Bytes",outputChunkMetadataBytes).put("outputChunksRead",outputChunks).put("currentnessBudget",currentnessBudget).put("authorityUtf8Bytes",authorityBytes).put("sourceAuthorityUtf8Bytes",sourceAuthorityBytes).put("checkpointUtf8Bytes",checkpointBytes).put("metadataUtf8Bytes",metadataBytes).put("operationContextUtf8Bytes",operationContextBytes).put("operationContextScope","whole_source_run_without_exact_per_operation_dependency").put("byteAccounting","materialized_sqlite_text_not_physical_io")
                    .put("byteAccountingScope","producer SQLite source text plus currentness guard source text; excludes separately reported authority reads, native cursor-window/page IO and derived JSON allocations")
                    .put("checkpointMaterialization",section.equals("messages")?"whole_bounded_checkpoint_row":"not_read").put("nativeCursorWindowBytesKnown",false)
                    .put("totalKnown",totalKnown).put("retainedCount",retainedCount<0?JSONObject.NULL:retainedCount).put("originalMessageTotalKnown",false)
                    .put("partialReason",reason).put("deadlineMillis",RAW_DEADLINE_MILLIS).put("elapsedMillis",(System.nanoTime()-started)/1_000_000L)
                    .put("storedBodiesMayAlreadyBeTruncated",true).put("excludedDerivedReferenceReceipts",!section.equals("messages"));
        }
        void currentness(TaskStoreMemoryReader.RawBinding.RawMessageSession session)throws Exception{
            currentnessBudget=session.readBudget();long additional=currentnessBudget.optLong("guardSourceUtf8Bytes");reserve(additional);bytes+=additional;
        }
        void metadata(String text)throws RawLimit{long count=text.getBytes(StandardCharsets.UTF_8).length;reserve(count);bytes+=count;metadataBytes+=count;}
        @Override public void close(){deadline.cancel(false);binding.control.removeCancelAction(cancel);}
    }
    private static final class RawPosition {
        int index;long lastAt=-1,upperAt=-1;String lastId="",upperId="",anchor="";
    }
    private static String rawCursor(TaskStoreMemoryReader.RawBinding binding,RawRequest request,RawPosition position)throws Exception{
        // The private per-binding seal already binds task/run/epoch/barrier. Do not duplicate
        // arbitrarily long native IDs here and accidentally exceed the model cursor's 4000 cap.
        JSONObject payload=new JSONObject().put("version",1).put("signature",request.signature()).put("index",position.index).put("lastAt",position.lastAt).put("lastId",position.lastId).put("upperAt",position.upperAt).put("upperId",position.upperId).put("anchor",position.anchor);
        String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));return encoded+"."+binding.cursorSeal(encoded);
    }
    private static RawPosition rawPosition(TaskStoreMemoryReader.RawBinding binding,RawRequest request)throws Exception{
        RawPosition result=new RawPosition();if(request.cursor.isEmpty())return result;
        int split=request.cursor.lastIndexOf('.');if(split<0||!binding.cursorSeal(request.cursor.substring(0,split)).equals(request.cursor.substring(split+1)))throw new SecurityException("原证据游标不是本轮宿主签发");
        JSONObject value;try{value=new JSONObject(new String(Base64.getUrlDecoder().decode(request.cursor.substring(0,split)),StandardCharsets.UTF_8));}catch(Exception invalid){throw new IllegalArgumentException("原证据游标不可读",invalid);}
        if(value.length()!=8||SourceRef.integer(value,"version",1)!=1||!request.signature().equals(value.optString("signature")))throw new SecurityException("原证据游标属于另一查询或来源代次");
        result.index=RawRequest.bounded(value,"index",0,0,Integer.MAX_VALUE);result.lastAt=value.getLong("lastAt");result.upperAt=value.getLong("upperAt");result.lastId=value.getString("lastId");result.upperId=value.getString("upperId");result.anchor=value.getString("anchor");return result;
    }
    private static JSONObject rawTask(SQLiteDatabase db,TaskStoreMemoryReader.RawBinding binding,RawWindow window)throws Exception{
        String[] args={binding.taskId};long size;
        try(Cursor row=window.query(db,TRACE_AUTHORITY_SIZE,args)){if(!row.moveToFirst())return null;size=row.getLong(0);if(row.isNull(0)||size>RAW_MAX_BYTES)throw new RawLimit("task_authority_exceeds_inline_limit");}
        JSONObject task;
        try(Cursor row=window.query(db,TRACE_AUTHORITY_ROW,args)){if(!row.moveToFirst())return null;String text=row.getString(0);window.authorityBytes=text.getBytes(StandardCharsets.UTF_8).length;if(window.authorityBytes>RAW_MAX_BYTES)throw new RawLimit("task_authority_exceeds_inline_limit");task=new JSONObject(text);}
        JSONObject origin=task.optJSONObject("origin");
        if(!binding.taskId.equals(task.optString("id"))||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||origin!=null&&Set.of("group","channel").contains(origin.optString("kind")))throw new SecurityException("原证据不属于本机私人任务");
        if(!binding.runId.equals(task.optString("runId"))||binding.epoch!=SourceRef.integer(task,"coordinatorEpoch",1)||!Boolean.FALSE.equals(task.opt("cancelRequested")))throw new SecurityException("原证据活动任务轮次已变化或停止");return task;
    }
    /** Null means the real runs row is absent. No checkpoint or message is synthesized. */
    private static JSONObject rawRun(SQLiteDatabase db,TaskStoreMemoryReader.RawBinding binding,RawWindow window,boolean checkpoint)throws Exception{
        String[] args={binding.taskId,binding.runId,Integer.toString(binding.epoch)};long size;
        try(Cursor row=window.query(db,"SELECT length(CAST(checkpoint AS BLOB)) FROM runs WHERE task_id=? AND run_id=? AND epoch=? LIMIT 1",args)){
            if(!row.moveToFirst())return null;size=row.getLong(0);if(checkpoint&&(row.isNull(0)||size>RAW_MAX_BYTES))throw new RawLimit("checkpoint_exceeds_inline_limit");
        }
        if(!checkpoint)return new JSONObject();window.reserve(size);
        try(Cursor row=window.query(db,"SELECT checkpoint FROM runs WHERE task_id=? AND run_id=? AND epoch=? LIMIT 1",args)){
            if(!row.moveToFirst())return null;String text=row.getString(0);long actual=text.getBytes(StandardCharsets.UTF_8).length;window.reserve(actual);window.bytes+=actual;window.checkpointBytes+=actual;return new JSONObject(text);
        }
    }
    private static JSONObject rawEnvelope(TaskStoreMemoryReader.RawBinding binding,RawRequest request)throws Exception{
        return new JSONObject().put("taskId",binding.taskId).put("runId",binding.runId).put("ownerId","owner").put("spaceId","private").put("sourceRevision",binding.sourceRevision)
                .put("section",request.section).put("query",request.query).put("entries",new JSONArray()).put("gaps",new JSONArray()).put("nextCursor","").put("charsReturned",0)
                .put("executionAuthorized",false).put("executionIndependentlyVerified",false).put("completeOriginalTrace",false).put("formalMemorySaved",false);
    }
    /** Bounded discovery on original rows. Response length and materialized SQLite text are separate. */
    public static JSONObject queryRaw(SQLiteDatabase db,TaskStoreMemoryReader.RawBinding binding,JSONObject options)throws Exception{return queryRaw(db,binding,options,null);}
    public static JSONObject queryRaw(SQLiteDatabase db,TaskStoreMemoryReader.RawBinding binding,JSONObject options,dev.pocketengine.features.assistant.ConversationRepository originals)throws Exception{
        if(db==null||binding==null)throw new IllegalArgumentException("原证据权威不可用");RawRequest request=new RawRequest(options);RawPosition position=rawPosition(binding,request);
        return binding.admit(()->{
            JSONObject result=rawEnvelope(binding,request);JSONArray entries=result.getJSONArray("entries"),gaps=result.getJSONArray("gaps");
            String reason="",first="",last="";int chars=0,retainedCount=-1;boolean hasMore=false,totalKnown=false;OutputScan outputScan=new OutputScan();
            try(RawWindow window=new RawWindow(binding,originals)){
                db.beginTransaction();try{
                    JSONObject task=rawTask(db,binding,window);
                    if(task==null)reason="original_task_unavailable";
                    else if(request.section.equals("messages")&&(request.after>=0||request.before>=0))reason="message_time_not_recorded";
                    else{
                        window.operationContext(db,binding.taskId,binding.runId,task.optString("conversationId"));
                        JSONObject checkpoint=rawRun(db,binding,window,request.section.equals("messages"));
                        if(checkpoint==null)reason="run_not_found";
                        else if(request.section.equals("messages")){
                            JSONArray messages=checkpoint.optJSONArray("messages");
                            if(messages==null)reason="retained_messages_unavailable";
                            else{
                                retainedCount=messages.length();totalKnown=true;
                                if(position.index>messages.length()||position.index>0&&(position.index-1>=messages.length()||!position.anchor.equals(TaskMemoryTrace.rawFingerprint(messages.getJSONObject(position.index-1))))){reason="cursor_message_changed";}
                                else{
                                    var currentness=binding.openMessageCurrentness(messages,window.bytes);try(currentness){
                                    java.util.Map<String,JSONObject> parents=new java.util.HashMap<>();
                                    for(int i=position.index;i<messages.length();i++){
                                        if(window.rows>=RAW_MAX_ROWS){reason="source_row_budget";hasMore=true;break;}
                                        if(entries.length()>=request.limit||chars>=request.maxChars){hasMore=true;break;}window.check();
                                        JSONObject message=messages.getJSONObject(i);String source=TaskMemoryTrace.rawMessageId(binding.taskId,binding.runId,i),revision=TaskMemoryTrace.rawFingerprint(message);window.rows++;if(first.isEmpty())first=source;last=source;
                                        String role=message.optString("role");JSONObject parent=null;
                                        if("assistant".equals(role)){parents.clear();JSONArray calls=message.optJSONArray("tool_calls");for(int j=0;calls!=null&&j<calls.length();j++){String id=calls.getJSONObject(j).optString("id");if(!id.isEmpty()){if(parents.containsKey(id))parents.put(id,null);else parents.put(id,new JSONObject().put("sourceId",source).put("revision",revision).put("toolCallId",rawPreview(id,200)).put("toolCallIdTruncated",id.length()>200).put("relation","tool_call_id"));}}}
                                        else if("tool".equals(role))parent=parents.remove(message.optString("tool_call_id"));else parents.clear();
                                        try{binding.requireCurrentOriginalMessage(i,message);}catch(TaskStoreMemoryReader.RawMessageUnavailable retired){gaps.put(new JSONObject().put("sourceId",source).put("status","unavailable").put("reason",retired.getMessage()));reason="some_sources_unavailable";position.index=i+1;position.anchor=revision;continue;}
                                        String body=message.toString(2);if(rawMatches(body,request.query)){
                                            JSONObject entry=rawEntry(binding,"checkpoint_message",source,revision,body,request.maxChars-chars).put("index",i).put("role",rawPreview(role,40)).put("timeStatus","unknown").put("createdAt",JSONObject.NULL).put("causalState",parent==null?"parent_not_expanded":"matched_tool_call");
                                            if(parent!=null)entry.put("parent",parent);if("tool".equals(role)&&!message.optString("tool_call_id").isEmpty())entry.put("toolCallId",rawPreview(message.getString("tool_call_id"),200)).put("toolCallIdTruncated",message.getString("tool_call_id").length()>200);
                                            window.check();entries.put(entry);chars+=entry.getString("body").length();
                                        }
                                        position.index=i+1;position.anchor=revision;
                                    }
                                    }finally{window.currentness(currentness);}
                                }
                            }
                        }else if(request.section.equals("outputs")){
                            scanOutputs(db,binding,window,request,position,entries,gaps,outputScan);hasMore=outputScan.more;reason=outputScan.reason;
                        }else{
                            window.operationContext(db,binding.taskId,binding.runId,task.optString("conversationId"));
                            String filter="task_id=? AND run_id=? AND tool NOT IN ("+RAW_REFERENCE_TOOLS+")";List<String> args=new ArrayList<>();args.add(binding.taskId);args.add(binding.runId);
                            if(request.after>=0){filter+=" AND created_at>=?";args.add(Long.toString(request.after));}if(request.before>=0){filter+=" AND created_at<=?";args.add(Long.toString(request.before));}
                            if(position.upperId.isEmpty())try(Cursor upper=window.query(db,"SELECT created_at,operation_id FROM operations WHERE "+filter+" ORDER BY created_at DESC,operation_id DESC LIMIT 1",args.toArray(new String[0]))){if(upper.moveToFirst()){position.upperAt=upper.getLong(0);position.upperId=upper.getString(1);window.metadata(position.upperId);}}
                            if(!position.upperId.isEmpty()){
                                filter+=" AND (created_at<? OR (created_at=? AND operation_id<=?)) AND (created_at>? OR (created_at=? AND operation_id>?))";
                                args.add(Long.toString(position.upperAt));args.add(Long.toString(position.upperAt));args.add(position.upperId);args.add(Long.toString(position.lastAt));args.add(Long.toString(position.lastAt));args.add(position.lastId);
                                try(Cursor rows=window.query(db,"SELECT operation_id,created_at,"+RAW_OPERATION_BYTES+" FROM operations WHERE "+filter+" ORDER BY created_at,operation_id LIMIT "+(RAW_MAX_ROWS+1),args.toArray(new String[0]))){
                                    while(rows.moveToNext()){
                                        if(window.rows>=RAW_MAX_ROWS){reason="source_row_budget";hasMore=true;break;}if(entries.length()>=request.limit||chars>=request.maxChars){hasMore=true;break;}window.check();
                                        String id=rows.getString(0);if(!validTaskId(id))throw new IllegalStateException("原操作标识不可用");window.metadata(id);long at=rows.getLong(1),size=rows.getLong(2);String source="task:"+binding.taskId+":operation:"+id;
                                        if(size<0||rows.isNull(2))throw new IllegalStateException("原操作长度不可读");
                                        if(size>RAW_MAX_BYTES||window.rows==0&&size>RAW_MAX_BYTES-window.bytes){window.rows++;gaps.put(new JSONObject().put("sourceId",source).put("status","read_limited").put("reason",size>RAW_MAX_BYTES?"original_row_exceeds_inline_limit":"single_row_exceeds_discovery_byte_budget"));if(reason.isEmpty())reason="source_read_limited";}
                                        else{
                                            try{window.reserve(size);}catch(RawLimit limit){reason=limit.reason;hasMore=true;break;}
                                            JSONObject operation=rawOperation(db,binding,window,id);if(operation==null){reason="source_changed_during_read";hasMore=true;break;}window.rows++;
                                            String body=operation.toString(2);if(rawMatches(body,request.query)){
                                                JSONObject entry=rawEntry(binding,"operation_receipt",source,TaskMemoryTrace.rawFingerprint(operation),body,request.maxChars-chars).put("operationId",id).put("state",rawPreview(operation.optString("state"),80)).put("createdAt",at).put("timeStatus","stored_operation_time").put("causalState","not_recorded");window.check();entries.put(entry);chars+=entry.getString("body").length();
                                            }
                                        }
                                        if(first.isEmpty())first=source;last=source;position.lastAt=at;position.lastId=id;
                                    }
                                }
                            }
                        }
                    }
                    window.recheckOperationContexts(db);db.setTransactionSuccessful();
                }catch(TaskStoreMemoryReader.RawMessageUnavailable unavailable){
                    if("native_output_read_limited".equals(unavailable.getMessage())){reason="source_byte_budget";hasMore=true;}
                    else{reason=OwnerSourceRunContext.UNAVAILABLE.equals(unavailable.getMessage())||unavailable.getMessage().startsWith("native_output_")?unavailable.getMessage():"native_message_currentness_unavailable";result.put("nextCursor","");entries=new JSONArray();result.put("entries",entries);chars=0;hasMore=false;}
                }
                catch(RawLimit limit){reason=limit.reason;hasMore=true;}
                catch(OperationCanceledException cancelled){binding.check();reason="read_deadline";hasMore=true;}
                finally{db.endTransaction();}
                binding.check();
                try{window.outputSourcesCurrent();}catch(TaskStoreMemoryReader.RawMessageUnavailable withdrawn){entries=new JSONArray();result.put("entries",entries);chars=0;hasMore=false;reason=withdrawn.getMessage();}
                if(request.section.equals("outputs")){first=outputScan.first;last=outputScan.last;chars=0;for(int i=0;i<entries.length();i++)chars+=entries.getJSONObject(i).getString("body").length();}
                String status=reason.isEmpty()?"current":reason.equals("cursor_message_changed")?"source_changed":reason.matches("original_task_unavailable|run_not_found|retained_messages_unavailable|message_time_not_recorded|native_message_currentness_unavailable|context_provenance_unavailable|native_output_source_unavailable|native_output_provenance_unavailable")?"unavailable":entries.length()==0&&reason.matches("task_authority_exceeds_inline_limit|checkpoint_exceeds_inline_limit")?"read_limited":"partial";
                if(!Set.of("current","partial").contains(status)){entries=new JSONArray();result.put("entries",entries);chars=0;hasMore=false;}
                if(hasMore)result.put("nextCursor",rawCursor(binding,request,position));
                return result.put("status",status).put("reason",reason).put("charsReturned",chars).put("hasMore",hasMore).put("matchMethod","nfkc_literal_all_query_terms")
                        .put("emptyResultMeaning",OwnerSourceRunContext.UNAVAILABLE.equals(reason)?"source_context_not_admitted":entries.length()==0?"no_match_in_scanned_retained_sources_not_proof_of_absence":"matches_in_scanned_sources")
                        .put("pageCoverage",window.coverage(request.section,first,last,reason,totalKnown,retainedCount)).put("pageFingerprint",TaskMemoryTrace.rawFingerprint(new JSONObject().put("entries",entries).put("gaps",gaps)));
            }
        });
    }
    /** Owner audit is not model provenance admission. A retired source can still be inspected by
     * its native owner while task ACL/retained bytes/hash/range are rechecked on every read. */
    public static JSONObject readOutputAudit(SQLiteDatabase db,MemoryBook.Scope scope,JSONObject supplied,dev.pocketengine.core.RunControl ownerControl)throws Exception{
        if(scope==null||!"owner".equals(scope.ownerId)||!"private".equals(scope.spaceId))throw new SecurityException("只能由原私人主人查看输出审计");
        String task=supplied.getString("taskId"),run=supplied.getString("runId");JSONObject input=new JSONObject(supplied.toString());input.remove("taskId");input.remove("runId");JSONObject wanted=TaskMemoryTrace.rawCitation(task,run,input);ToolOutputStore.outputId(task,run,wanted.getString("sourceId"));
        try(HistoryInspection window=new HistoryInspection()){
            Runnable cancelled=window.signal::cancel;if(ownerControl!=null){ownerControl.check();ownerControl.onCancel(cancelled);}
            try{
                JSONObject result;db.beginTransaction();try{
                    JSONObject authority=window.task(db,task,false),origin=authority==null?null:authority.optJSONObject("origin");
                    if(authority==null||!scope.ownerId.equals(authority.optString("actorId"))||!scope.spaceId.equals(authority.optString("spaceId"))||origin!=null&&Set.of("channel","group").contains(origin.optString("kind")))throw new SecurityException("原输出不属于当前私人任务审计");
                    try(Cursor actual=window.query(db,"SELECT 1 FROM runs WHERE task_id=? AND run_id=? LIMIT 1",new String[]{task,run})){if(!actual.moveToFirst())throw new SecurityException("原输出run不存在");}
                    ToolOutputStore.ReadBudget budget=new ToolOutputStore.ReadBudget(){
                        public long remaining(){window.check();return RAW_MAX_BYTES-window.sourceBytes;}
                        public void check(){window.check();}
                        public void charge(long bytes,String category)throws Exception{if(bytes<0||bytes>RAW_MAX_BYTES-window.sourceBytes)throw new TaskStoreMemoryReader.RawMessageUnavailable("native_output_read_limited");window.sourceBytes+=bytes;window.check();}
                    };
                    ToolOutputStore.Header header=ToolOutputStore.header(db,task,run,wanted.getString("sourceId"),budget,window.signal);
                    if(header==null)result=ToolOutputStore.status(task,run,wanted,"unavailable","native_output_not_retained");
                    else if(!wanted.getString("revision").equals(header.reference().getString("revision")))result=ToolOutputStore.status(task,run,wanted,"source_changed","native_output_fingerprint_changed");
                    else result=ToolOutputStore.page(task,run,wanted,header,ToolOutputStore.range(db,header,wanted.getInt("offset"),wanted.getInt("maxChars"),budget,window.signal),wanted.getInt("offset"));
                    window.check();db.setTransactionSuccessful();
                }catch(TaskStoreMemoryReader.RawMessageUnavailable limited){result=ToolOutputStore.status(task,run,wanted,"read_limited",limited.getMessage());}
                catch(OperationCanceledException deadline){if(ownerControl!=null)ownerControl.check();result=ToolOutputStore.status(task,run,wanted,"read_limited","read_deadline");}
                finally{db.endTransaction();}
                if(ownerControl!=null)ownerControl.check();return result.put("auditOnly",true).put("modelSourceAdmission",false).put("sourceRetirementChangesModelUseNotOwnerAudit",true)
                    .put("readBudget",new JSONObject().put("sourceUtf8Bytes",window.sourceBytes).put("authorityUtf8Bytes",window.authorityBytes).put("sourceLimit",RAW_MAX_BYTES).put("deadlineMillis",RAW_DEADLINE_MILLIS).put("byteAccounting","materialized_sqlite_text_not_physical_io"));
            }finally{if(ownerControl!=null)ownerControl.removeCancelAction(cancelled);}
        }
    }
    private static final class OutputScan {String first="",last="",reason="";boolean more;}
    /** Keyset state advances only past a fully checked/delivered source or an explicit bounded gap.
     * A deadline never discards surviving verified hits or advances past an undelivered body. */
    private static void scanOutputs(SQLiteDatabase db,TaskStoreMemoryReader.RawBinding binding,RawWindow window,RawRequest request,RawPosition position,JSONArray entries,JSONArray gaps,OutputScan progress)throws Exception{
        String filter="task_id=? AND run_id=?";List<String> args=new ArrayList<>();args.add(binding.taskId);args.add(binding.runId);
        if(request.after>=0){filter+=" AND created_at>=?";args.add(Long.toString(request.after));}if(request.before>=0){filter+=" AND created_at<=?";args.add(Long.toString(request.before));}
        if(position.upperId.isEmpty())try(Cursor upper=window.query(db,"SELECT created_at,output_id FROM task_output_sources WHERE "+filter+" ORDER BY created_at DESC,output_id DESC LIMIT 1",args.toArray(new String[0]))){if(upper.moveToFirst()){position.upperAt=upper.getLong(0);position.upperId=upper.getString(1);window.metadata(position.upperId);}}
        if(position.upperId.isEmpty())return;
        filter+=" AND (created_at<? OR (created_at=? AND output_id<=?)) AND (created_at>? OR (created_at=? AND output_id>?))";args.add(Long.toString(position.upperAt));args.add(Long.toString(position.upperAt));args.add(position.upperId);args.add(Long.toString(position.lastAt));args.add(Long.toString(position.lastAt));args.add(position.lastId);
        int returned=0;for(int i=0;i<entries.length();i++)returned+=entries.getJSONObject(i).getString("body").length();
        try(Cursor rows=window.query(db,"SELECT output_id,created_at FROM task_output_sources WHERE "+filter+" ORDER BY created_at,output_id LIMIT 101",args.toArray(new String[0]))){while(rows.moveToNext()){
            if(window.rows>=RAW_MAX_ROWS||entries.length()>=request.limit||returned>=request.maxChars){progress.more=true;progress.reason=window.rows>=RAW_MAX_ROWS?"source_row_budget":entries.length()>=request.limit?"result_limit":"character_limit";break;}
            window.check();String id=rows.getString(0);if(!validTaskId(id))throw new SecurityException("原输出标识不可读");long at=rows.getLong(1);window.metadata(id);String source=ToolOutputStore.sourceId(binding.taskId,binding.runId,id);
            ToolOutputStore.Header header=ToolOutputStore.header(db,binding.taskId,binding.runId,source,window.outputBudget(),window.signal);if(header==null){progress.reason="source_changed_during_read";progress.more=true;break;}
            JSONArray dependencies=header.dependencies();ToolOutputStore.requireDependencies(dependencies,binding.outputSources());window.outputDependencies.add(dependencies);
            boolean gap=!request.query.isEmpty()&&ToolOutputStore.inlineReadBytes(db,header,window.signal)>RAW_MAX_BYTES-window.bytes;
            if(gap){gaps.put(new JSONObject().put("sourceId",source).put("status","read_limited").put("reason","output_exceeds_discovery_byte_budget"));progress.reason="source_read_limited";}
            else{
                String body=request.query.isEmpty()?ToolOutputStore.range(db,header,0,Math.min(4000,request.maxChars-returned),window.outputBudget(),window.signal):ToolOutputStore.retainedText(db,header,window.outputBudget(),window.signal);
                if(rawMatches(body,request.query)){
                    int end=TaskMemoryTrace.safeEnd(body,0,Math.min(4000,request.maxChars-returned));JSONObject ref=header.reference(),wanted=new JSONObject().put("sourceId",source).put("revision",ref.getString("revision"));
                    JSONObject entry=ToolOutputStore.page(binding.taskId,binding.runId,wanted,header,body.substring(0,end),0).put("createdAt",at).put("timeStatus","stored_capture_time").put("causalState","native_prepared_operation_or_read_call_only");
                    window.check();window.outputSourcesCurrent();entries.put(entry);returned+=end;
                }
            }
            window.rows++;if(progress.first.isEmpty())progress.first=source;progress.last=source;position.lastAt=at;position.lastId=id;
        }}
    }
    private static JSONObject readOutputRaw(SQLiteDatabase db,TaskStoreMemoryReader.RawBinding binding,JSONObject input,dev.pocketengine.features.assistant.ConversationRepository originals)throws Exception{
        JSONObject wanted=TaskMemoryTrace.rawCitation(binding.taskId,binding.runId,input);
        return binding.admit(()->{JSONObject result;try(RawWindow window=new RawWindow(binding,originals)){db.beginTransaction();try{
            JSONObject task=rawTask(db,binding,window);
            if(task==null||rawRun(db,binding,window,false)==null)result=ToolOutputStore.status(binding.taskId,binding.runId,wanted,"unavailable","original_task_or_run_unavailable");
            else{
                window.operationContext(db,binding.taskId,binding.runId,task.optString("conversationId"));ToolOutputStore.Header header=ToolOutputStore.header(db,binding.taskId,binding.runId,wanted.getString("sourceId"),window.outputBudget(),window.signal);
                if(header==null)result=ToolOutputStore.status(binding.taskId,binding.runId,wanted,"unavailable","native_output_not_retained");
                else if(!wanted.getString("revision").equals(header.reference().getString("revision")))result=ToolOutputStore.status(binding.taskId,binding.runId,wanted,"source_changed","native_output_fingerprint_changed");
                else{JSONArray dependencies=header.dependencies();ToolOutputStore.requireDependencies(dependencies,binding.outputSources());window.outputDependencies.add(dependencies);String body=ToolOutputStore.range(db,header,wanted.getInt("offset"),wanted.getInt("maxChars"),window.outputBudget(),window.signal);window.rows++;result=ToolOutputStore.page(binding.taskId,binding.runId,wanted,header,body,wanted.getInt("offset"));}
            }
            window.check();window.recheckOperationContexts(db);window.outputSourcesCurrent();db.setTransactionSuccessful();
        }catch(TaskStoreMemoryReader.RawMessageUnavailable unavailable){result=ToolOutputStore.status(binding.taskId,binding.runId,wanted,unavailable.getMessage().equals("native_output_read_limited")?"read_limited":"unavailable",unavailable.getMessage());}
        catch(RawLimit limited){result=ToolOutputStore.status(binding.taskId,binding.runId,wanted,"read_limited",limited.reason);}
        catch(OperationCanceledException deadline){binding.check();result=ToolOutputStore.status(binding.taskId,binding.runId,wanted,"read_limited","read_deadline");}
        finally{db.endTransaction();}
        binding.check();window.outputSourcesCurrent();return result.put("sourceRevision",binding.sourceRevision).put("readBudget",window.coverage("outputs",wanted.getString("sourceId"),wanted.getString("sourceId"),result.optString("reason"),false,-1));}});
    }
    private static JSONObject rawOperation(SQLiteDatabase db,TaskStoreMemoryReader.RawBinding binding,RawWindow window,String id)throws Exception{
        try(Cursor row=window.query(db,TRACE_OPERATION_ROW,new String[]{id,binding.taskId,binding.runId})){
            if(!row.moveToFirst())return null;String operation=row.getString(0),tool=row.getString(1),args=row.getString(2),state=row.getString(3),receipt=row.isNull(4)?null:row.getString(4),run=row.getString(5);
            long actual=0;for(String value:new String[]{operation,tool,args,state,receipt,run})if(value!=null)actual+=value.getBytes(StandardCharsets.UTF_8).length;
            window.reserve(actual);window.bytes+=actual;window.check();return TaskMemoryTrace.storedOperation(binding.taskId,run,operation,tool,args,state,receipt,row.getLong(6));
        }
    }
    private static boolean rawMatches(String body,String query){String haystack=normalize(body),needle=normalize(query);if(needle.isEmpty())return true;for(String term:needle.split("\\s+"))if(!haystack.contains(term))return false;return true;}
    private static String rawPreview(String value,int max){return value.substring(0,TaskMemoryTrace.safeEnd(value,0,max));}
    private static JSONObject rawEntry(TaskStoreMemoryReader.RawBinding binding,String kind,String source,String revision,String body,int available)throws Exception{
        int end=TaskMemoryTrace.safeEnd(body,0,Math.min(4000,available));JSONObject citation=new JSONObject().put("sourceId",source).put("revision",revision);
        return new JSONObject().put("kind",kind).put("sourceId",source).put("revision",revision).put("revisionKind","sha256_content_fingerprint").put("nativeRevisionStatus","unversioned")
                .put("body",body.substring(0,end)).put("readRange",TaskMemoryTrace.rawRange(0,end,body.length())).put("sourceJump",TaskMemoryTrace.rawJump(binding.taskId,binding.runId,citation,0,end));
    }
    /** Exact native read used by both model expansion and H4's actual prefix-source freeze. */
    public static JSONObject readRaw(SQLiteDatabase db,TaskStoreMemoryReader.RawBinding binding,JSONObject citation)throws Exception{return readRaw(db,binding,citation,null);}
    public static JSONObject readRaw(SQLiteDatabase db,TaskStoreMemoryReader.RawBinding binding,JSONObject citation,dev.pocketengine.features.assistant.ConversationRepository originals)throws Exception{
        if(db==null||binding==null)throw new IllegalArgumentException("原证据权威不可用");JSONObject wanted=TaskMemoryTrace.rawCitation(binding.taskId,binding.runId,citation);
        if(wanted.getString("sourceId").startsWith("task:"+binding.taskId+":run:"+binding.runId+":output:"))return readOutputRaw(db,binding,citation,originals);
        return binding.admit(()->{
            JSONObject result;try(RawWindow window=new RawWindow(binding,originals)){
                db.beginTransaction();try{
                    JSONObject task=rawTask(db,binding,window);boolean operation=wanted.getString("sourceId").startsWith("task:"+binding.taskId+":operation:");
                    if(task==null)result=rawReadStatus(binding,citation,operation,"unavailable","original_task_unavailable");
                    else{
                        window.operationContext(db,binding.taskId,binding.runId,task.optString("conversationId"));
                        JSONObject checkpoint=rawRun(db,binding,window,!operation);
                        if(checkpoint==null)result=rawReadStatus(binding,citation,operation,"unavailable","run_not_found");
                        else if(operation){
                            window.operationContext(db,binding.taskId,binding.runId,task.optString("conversationId"));
                            String id=TaskMemoryTrace.operationId(wanted);long size=-1;try(Cursor row=window.query(db,"SELECT "+RAW_OPERATION_BYTES+" FROM operations WHERE operation_id=? AND task_id=? AND run_id=? LIMIT 1",new String[]{id,binding.taskId,binding.runId})){if(row.moveToFirst()&&!row.isNull(0))size=row.getLong(0);}
                            if(size<0)result=rawReadStatus(binding,citation,true,"unavailable","original_operation_unavailable");
                            else if(size>RAW_MAX_BYTES)result=rawReadStatus(binding,citation,true,"read_limited","original_row_exceeds_inline_limit");
                            else{window.reserve(size);JSONObject row=rawOperation(db,binding,window,id);window.rows++;result=TaskMemoryTrace.operationPage(new JSONObject().put("ownerId","owner").put("spaceId","private"),task,row,wanted);if("current".equals(result.optString("status"))){JSONObject range=result.getJSONObject("readRange");result.put("sourceJump",TaskMemoryTrace.rawJump(binding.taskId,binding.runId,wanted,range.getInt("offset"),range.getInt("chars")));}}
                        }else{
                            String prefix="task:"+binding.taskId+":run:"+binding.runId+":checkpoint-message:";int index=Integer.parseInt(wanted.getString("sourceId").substring(prefix.length()));JSONArray messages=checkpoint.optJSONArray("messages");JSONObject row=messages==null||index>=messages.length()?null:messages.optJSONObject(index);if(row!=null)window.rows++;
                            if(row==null)result=TaskMemoryTrace.rawMessagePage(binding.taskId,binding.runId,null,citation);
                            else{var currentness=binding.openMessageCurrentness(messages,window.bytes);try(currentness){binding.requireCurrentOriginalMessage(index,row);result=TaskMemoryTrace.rawMessagePage(binding.taskId,binding.runId,row,citation);}finally{window.currentness(currentness);}}

                        }
                    }
                    window.check();window.recheckOperationContexts(db);db.setTransactionSuccessful();
                }catch(TaskStoreMemoryReader.RawMessageUnavailable unavailable){result=rawReadStatus(binding,citation,wanted.getString("sourceId").startsWith("task:"+binding.taskId+":operation:"),"unavailable",unavailable.getMessage());}
                catch(RawLimit limit){result=rawReadStatus(binding,citation,wanted.getString("sourceId").startsWith("task:"+binding.taskId+":operation:"),"read_limited",limit.reason);}
                catch(OperationCanceledException cancelled){binding.check();result=rawReadStatus(binding,citation,wanted.getString("sourceId").startsWith("task:"+binding.taskId+":operation:"),"read_limited","read_deadline");}
                finally{db.endTransaction();}
                binding.check();return result.put("sourceRevision",binding.sourceRevision).put("readBudget",window.coverage(wanted.getString("sourceId").startsWith("task:"+binding.taskId+":operation:")?"operations":"messages",wanted.getString("sourceId"),wanted.getString("sourceId"),result.optString("reason"),false,-1));
            }
        });
    }
    private static JSONObject rawReadStatus(TaskStoreMemoryReader.RawBinding binding,JSONObject citation,boolean operation,String status,String reason)throws Exception{
        return operation?TaskMemoryTrace.operationStatus(new JSONObject().put("ownerId","owner").put("spaceId","private"),TaskMemoryTrace.rawCitation(binding.taskId,binding.runId,citation),status,reason):TaskMemoryTrace.rawSourceStatus(binding.taskId,binding.runId,citation,status,reason);
    }

    private static String historyEncode(JSONObject value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toString().getBytes(StandardCharsets.UTF_8));}
    private static JSONObject historyPosition(String cursor)throws Exception{return cursor.isEmpty()?new JSONObject():new JSONObject(new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8));}
    private static String historyMembership(JSONObject task)throws Exception{return TaskMemoryTrace.rawFingerprint(new JSONObject().put("taskId",task.getString("id")).put("actorId",task.optString("actorId")).put("spaceId",task.optString("spaceId")).put("conversationId",task.optString("conversationId")).put("originKind",task.optJSONObject("origin")==null?"":task.getJSONObject("origin").optString("kind")));}
    private static boolean historyVisible(ConversationHistory.HistoryBinding binding,JSONObject task){JSONObject origin=task.optJSONObject("origin");return "owner".equals(task.optString("actorId"))&&"private".equals(task.optString("spaceId"))&&"assistant".equals(task.optString("kind"))&&binding.conversationId.equals(task.optString("conversationId"))&&(origin==null||!Set.of("group","channel").contains(origin.optString("kind")));}
    private static void historyCaller(SQLiteDatabase db,ConversationHistory.HistoryBinding binding,RawWindow window)throws Exception{JSONObject task=rawTask(db,binding.caller,window);if(task==null||!historyVisible(binding,task))throw new SecurityException("历史范围不是原调用任务的私人会话");}
    private static JSONObject historySourceTask(SQLiteDatabase db,ConversationHistory.HistoryBinding binding,ConversationHistory.SourceLocator selected,RawWindow window)throws Exception{
        try(Cursor row=window.query(db,TRACE_AUTHORITY_SIZE,new String[]{selected.taskId})){if(!row.moveToFirst())return null;if(row.isNull(0)||row.getLong(0)>RAW_MAX_BYTES)throw new RawLimit("source_task_authority_exceeds_inline_limit");}
        try(Cursor row=window.query(db,TRACE_AUTHORITY_ROW,new String[]{selected.taskId})){if(!row.moveToFirst())return null;String text=row.getString(0);window.sourceAuthorityBytes=text.getBytes(StandardCharsets.UTF_8).length;if(window.sourceAuthorityBytes>RAW_MAX_BYTES)throw new RawLimit("source_task_authority_exceeds_inline_limit");JSONObject task=new JSONObject(text);if(!historyVisible(binding,task)||!selected.taskId.equals(task.optString("id"))||!selected.membership.equals(historyMembership(task)))throw new SecurityException("历史来源的原会话或私人归属已变化");return task;}
    }
    /** Conversation-scoped metadata discovery. Never uses the legacy unbounded queryMemoryTasks. */
    public static JSONObject queryConversationHistory(SQLiteDatabase db,ConversationHistory.HistoryBinding binding,ConversationHistory.SourceLocator selected,String kind,JSONObject options)throws Exception{return queryConversationHistory(db,binding,selected,kind,options,null);}
    public static JSONObject queryConversationHistory(SQLiteDatabase db,ConversationHistory.HistoryBinding binding,ConversationHistory.SourceLocator selected,String kind,JSONObject options,dev.pocketengine.features.assistant.ConversationRepository originals)throws Exception{
        return binding.caller.admit(()->{
            JSONObject result=new JSONObject().put("entries",new JSONArray()).put("gaps",new JSONArray()).put("nextCursor","").put("charsReturned",0);JSONArray entries=result.getJSONArray("entries"),gaps=result.getJSONArray("gaps");JSONObject position=historyPosition(options.optString("cursor"));String reason="",first="",last="";boolean more=false;int limit=options.getInt("limit"),chars=0;String query=options.getString("query");long after=options.optLong("createdAfter",-1),before=options.optLong("createdBefore",-1);
            try(RawWindow window=new RawWindow(binding.caller,originals)){db.beginTransaction();try{
                historyCaller(db,binding,window);
                if(selected==null){
                    if(!kind.equals("runs"))throw new IllegalArgumentException("请先选择原任务/轮次");
                    String sql="SELECT task_id,updated_at,length(CAST(document AS BLOB)) FROM tasks";List<String> args=new ArrayList<>();
                    if(position.has("lastAt")){sql+=" WHERE (updated_at<? OR (updated_at=? AND task_id<=?)) AND (updated_at<? OR (updated_at=? AND task_id<?))";for(String key:new String[]{"upperAt","upperAt","upperId","lastAt","lastAt","lastId"})args.add(position.get(key).toString());}
                    sql+=" ORDER BY updated_at DESC,task_id DESC LIMIT 101";
                    try(Cursor rows=window.query(db,sql,args.toArray(new String[0]))){while(rows.moveToNext()){
                        if(window.rows>=RAW_MAX_ROWS||entries.length()>=limit){more=true;reason=window.rows>=RAW_MAX_ROWS?"candidate_row_budget":"result_limit";break;}window.check();String id=rows.getString(0);long at=rows.getLong(1),size=rows.getLong(2);if(!validTaskId(id)||at<0)throw new IllegalStateException("原历史候选索引不可读");window.metadata(id);
                        if(!position.has("upperAt"))position.put("upperAt",at).put("upperId",id);
                        if(size<0||rows.isNull(2))throw new IllegalStateException("原历史候选长度未知");
                        if(size>RAW_MAX_BYTES||window.rows==0&&size>RAW_MAX_BYTES-window.bytes){reason="candidate_task_read_limited";window.rows++;}
                        else{try{window.reserve(size);}catch(RawLimit limited){reason=limited.reason;more=true;break;}
                            try(Cursor row=window.query(db,TRACE_AUTHORITY_ROW,new String[]{id})){if(row.moveToFirst()){String text=row.getString(0);long actual=text.getBytes(StandardCharsets.UTF_8).length;window.reserve(actual);window.bytes+=actual;JSONObject task=new JSONObject(text);window.rows++;
                                if(historyVisible(binding,task)){long created=task.optLong("createdAt",-1);String state=task.optString("status");if((after<0||created>=after)&&(before<0||created>=0&&created<=before)&&historyStatusMatches(state,id,query))entries.put(new JSONObject().put("sourceKind","task_record").put("sourceTaskId",id).put("sourceStatus",state).put("createdAt",created>=0?created:JSONObject.NULL).put("timeStatus",created>=0?"stored_task_creation_time":"unknown").put("membershipFingerprint",historyMembership(task)));}
                            }}
                        }
                        if(first.isEmpty())first="candidate_window";last="candidate_window";position.put("lastAt",at).put("lastId",id);
                    }}
                    if(more&&position.has("lastAt"))result.put("nextCursor",historyEncode(position));
                }else{
                    JSONObject task=historySourceTask(db,binding,selected,window);if(task==null)reason="source_task_unavailable";
                    else if(selected.kind==ConversationHistory.Kind.TASK){
                        if(!kind.equals("runs"))throw new IllegalArgumentException("此选择是任务，请先读取它真实保留的执行轮次");String sql="SELECT run_id,epoch,status,created_at,length(CAST(checkpoint AS BLOB)) FROM runs WHERE task_id=?";List<String> args=new ArrayList<>();args.add(selected.taskId);if(position.has("epoch")){sql+=" AND epoch<?";args.add(position.get("epoch").toString());}sql+=" ORDER BY epoch DESC LIMIT 101";
                        try(Cursor rows=window.query(db,sql,args.toArray(new String[0]))){while(rows.moveToNext()){
                            if(window.rows>=RAW_MAX_ROWS||entries.length()>=limit){more=true;reason=window.rows>=RAW_MAX_ROWS?"source_row_budget":entries.length()>=limit?"result_limit":"character_limit";break;}window.check();String run=rows.getString(0);window.metadata(run);window.metadata(rows.getString(2));int epoch=rows.getInt(1);long created=rows.getLong(3);if(!validTaskId(run)||epoch<1)throw new IllegalStateException("原执行轮次身份无效");window.rows++;
                            if((after<0||created>=after)&&(before<0||created<=before)&&historyStatusMatches(rows.getString(2),run,query))entries.put(new JSONObject().put("sourceKind","task_run").put("sourceTaskId",selected.taskId).put("sourceRunId",run).put("sourceEpoch",epoch).put("sourceStatus",rows.getString(2)).put("createdAt",created).put("timeStatus","stored_run_creation_time").put("checkpointWithinInlineLimit",!rows.isNull(4)&&rows.getLong(4)<=RAW_MAX_BYTES).put("membershipFingerprint",selected.membership));
                            if(first.isEmpty())first="epoch:"+epoch;last="epoch:"+epoch;position.put("epoch",epoch);
                        }}
                        if(more&&position.has("epoch"))result.put("nextCursor",historyEncode(position));if(window.rows==0&&entries.length()==0)reason="run_not_collected_or_unavailable";
                    }else if(selected.kind==ConversationHistory.Kind.RUN){
                        if(!kind.equals("operations"))throw new IllegalArgumentException("此选择是执行轮次，请查该轮原操作");if(!historyRunExists(db,selected,window))reason="source_run_unavailable";
                        else{
                            window.operationContext(db,selected.taskId,selected.runId,task.optString("conversationId"));
                            String filter="task_id=? AND run_id=? AND tool NOT IN ("+RAW_REFERENCE_TOOLS+")";List<String> args=new ArrayList<>(List.of(selected.taskId,selected.runId));if(after>=0){filter+=" AND created_at>=?";args.add(Long.toString(after));}if(before>=0){filter+=" AND created_at<=?";args.add(Long.toString(before));}
                            if(!position.has("upperId"))try(Cursor upper=window.query(db,"SELECT created_at,operation_id FROM operations WHERE "+filter+" ORDER BY created_at DESC,operation_id DESC LIMIT 1",args.toArray(new String[0]))){if(upper.moveToFirst()){position.put("upperAt",upper.getLong(0)).put("upperId",upper.getString(1));window.metadata(upper.getString(1));}}
                            if(position.has("upperId")){
                                filter+=" AND (created_at<? OR (created_at=? AND operation_id<=?)) AND (created_at>? OR (created_at=? AND operation_id>?))";args.add(position.get("upperAt").toString());args.add(position.get("upperAt").toString());args.add(position.getString("upperId"));long lastAt=position.optLong("lastAt",-1);args.add(Long.toString(lastAt));args.add(Long.toString(lastAt));args.add(position.optString("lastId"));
                                try(Cursor rows=window.query(db,"SELECT operation_id,created_at,"+RAW_OPERATION_BYTES+" FROM operations WHERE "+filter+" ORDER BY created_at,operation_id LIMIT 101",args.toArray(new String[0]))){while(rows.moveToNext()){
                                    if(window.rows>=RAW_MAX_ROWS||entries.length()>=limit||chars>=options.getInt("maxChars")){more=true;reason=window.rows>=RAW_MAX_ROWS?"source_row_budget":entries.length()>=limit?"result_limit":"character_limit";break;}window.check();String id=rows.getString(0);window.metadata(id);long at=rows.getLong(1),size=rows.getLong(2);String source="task:"+selected.taskId+":operation:"+id;if(!validTaskId(id)||size<0||rows.isNull(2))throw new IllegalStateException("原历史操作metadata不可读");
                                    if(size>RAW_MAX_BYTES||window.rows==0&&size>RAW_MAX_BYTES-window.bytes){window.rows++;gaps.put(new JSONObject().put("status","read_limited").put("sourceId",source).put("reason","original_row_exceeds_discovery_limit"));reason="source_read_limited";}
                                    else{try{window.reserve(size);}catch(RawLimit limited){reason=limited.reason;more=true;break;}JSONObject operation=historyOperation(db,selected,window,id);window.rows++;if(operation==null){reason="source_operation_unavailable";break;}String body=operation.toString(2);if(rawMatches(body,query)){
                                        int end=TaskMemoryTrace.safeEnd(body,0,Math.min(4000,options.getInt("maxChars")-chars));String revision=TaskMemoryTrace.rawFingerprint(operation);window.check();JSONObject citation=new JSONObject().put("sourceId",source).put("revision",revision);
                                        entries.put(new JSONObject().put("sourceKind","task_operation").put("sourceTaskId",selected.taskId).put("sourceRunId",selected.runId).put("sourceEpoch",selected.epoch).put("sourceId",source).put("revision",revision).put("revisionKind","sha256_content_fingerprint").put("membershipFingerprint",selected.membership).put("createdAt",at).put("timeStatus","stored_operation_time").put("body",body.substring(0,end)).put("readRange",TaskMemoryTrace.rawRange(0,end,body.length())).put("sourceJump",TaskMemoryTrace.rawJump(selected.taskId,selected.runId,citation,0,end)));chars+=end;
                                    }}
                                    if(first.isEmpty())first=source;last=source;position.put("lastAt",at).put("lastId",id);
                                }}
                            }
                            if(more&&position.has("lastAt"))result.put("nextCursor",historyEncode(position));
                        }
                    }else throw new IllegalArgumentException("请使用任务或轮次选择来发现操作");
                }
                window.check();if(selected!=null&&selected.kind==ConversationHistory.Kind.RUN&&entries.length()>0)OwnerSourceRunContext.inheritPrepared(db,binding.caller.taskId,binding.caller.runId,selected.taskId,selected.runId,window.contextBytes(),window.signal);window.recheckOperationContexts(db);db.setTransactionSuccessful();
            }catch(TaskStoreMemoryReader.RawMessageUnavailable unavailable){reason=OwnerSourceRunContext.UNAVAILABLE;entries=new JSONArray();result.put("entries",entries).put("nextCursor","");chars=0;more=false;}
            catch(RawLimit limited){reason=limited.reason;more=true;if(position.has("lastAt")||position.has("epoch"))result.put("nextCursor",historyEncode(position));}
            catch(OperationCanceledException deadline){binding.caller.check();reason="read_deadline";more=true;if(position.has("lastAt")||position.has("epoch"))result.put("nextCursor",historyEncode(position));}
            finally{db.endTransaction();}
            binding.check();return ConversationHistory.proof(result.put("status",reason.matches("source_task_unavailable|source_run_unavailable|run_not_collected_or_unavailable|context_provenance_unavailable")?"unavailable":reason.isEmpty()?"current":"partial").put("reason",reason).put("charsReturned",chars).put("hasMore",more).put("pageCoverage",window.coverage(kind.equals("operations")?"operations":"task_run_metadata",first,last,reason,false,-1).put("sourceKinds",new JSONArray().put(selected==null?"task_record":selected.kind==ConversationHistory.Kind.TASK?"task_run":"operation_receipt")).put("logicalScope","bound_private_conversation_only").put("candidateTaskRowsMayBeOutsideScope",selected==null).put("foreignCandidateIdentitiesReturned",false).put("originalHistoryTotalKnown",false).put("watermarkKind",selected==null?"updated_at_upper_bound_not_snapshot":selected.kind==ConversationHistory.Kind.TASK?"descending_epoch_keyset":"operation_created_at_upper_bound").put("paginationCanMissConcurrentTaskUpdates",selected==null).put("upperCreatedOrUpdatedAt",position.has("upperAt")?position.get("upperAt"):JSONObject.NULL)));
            }
        });
    }
    private static boolean historyStatusMatches(String state,String id,String query){String labels=state+" "+id+" "+switch(state){case "failed"->"失败";case "completed"->"完成";case "cancelled"->"取消";case "paused"->"暂停";default->"";};return rawMatches(labels,query);}
    private static boolean historyRunExists(SQLiteDatabase db,ConversationHistory.SourceLocator selected,RawWindow window)throws Exception{try(Cursor row=window.query(db,"SELECT 1 FROM runs WHERE task_id=? AND run_id=? AND epoch=? LIMIT 1",new String[]{selected.taskId,selected.runId,Integer.toString(selected.epoch)})){return row.moveToFirst();}}
    private static JSONObject historyOperation(SQLiteDatabase db,ConversationHistory.SourceLocator selected,RawWindow window,String id)throws Exception{
        try(Cursor row=window.query(db,TRACE_OPERATION_ROW,new String[]{id,selected.taskId,selected.runId})){if(!row.moveToFirst())return null;String op=row.getString(0),tool=row.getString(1),args=row.getString(2),state=row.getString(3),receipt=row.isNull(4)?null:row.getString(4),run=row.getString(5);long bytes=0;for(String value:new String[]{op,tool,args,state,receipt,run})if(value!=null)bytes+=value.getBytes(StandardCharsets.UTF_8).length;window.reserve(bytes);window.bytes+=bytes;return TaskMemoryTrace.storedOperation(selected.taskId,run,op,tool,args,state,receipt,row.getLong(6));}
    }
    public static JSONObject readConversationHistoryOperation(SQLiteDatabase db,ConversationHistory.HistoryBinding binding,ConversationHistory.SourceLocator selected,int offset,int maxChars)throws Exception{return readConversationHistoryOperation(db,binding,selected,offset,maxChars,null);}
    public static JSONObject readConversationHistoryOperation(SQLiteDatabase db,ConversationHistory.HistoryBinding binding,ConversationHistory.SourceLocator selected,int offset,int maxChars,dev.pocketengine.features.assistant.ConversationRepository originals)throws Exception{
        if(selected.kind!=ConversationHistory.Kind.OPERATION)throw new IllegalArgumentException("请选择原历史操作");JSONObject input=new JSONObject().put("sourceId",selected.sourceId).put("revision",selected.revision).put("offset",offset).put("maxChars",maxChars),wanted=TaskMemoryTrace.rawCitation(selected.taskId,selected.runId,input);
        return binding.caller.admit(()->{JSONObject result;try(RawWindow window=new RawWindow(binding.caller,originals)){db.beginTransaction();try{
            historyCaller(db,binding,window);JSONObject task=historySourceTask(db,binding,selected,window);if(task==null||!historyRunExists(db,selected,window))result=TaskMemoryTrace.operationStatus(new JSONObject().put("ownerId","owner").put("spaceId","private"),wanted,"unavailable","source_task_or_run_unavailable");
            else{window.operationContext(db,selected.taskId,selected.runId,task.optString("conversationId"));String id=TaskMemoryTrace.operationId(wanted);long size=-1;try(Cursor row=window.query(db,"SELECT "+RAW_OPERATION_BYTES+" FROM operations WHERE operation_id=? AND task_id=? AND run_id=? LIMIT 1",new String[]{id,selected.taskId,selected.runId})){if(row.moveToFirst()&&!row.isNull(0))size=row.getLong(0);}
                if(size<0||size>RAW_MAX_BYTES)result=TaskMemoryTrace.operationStatus(new JSONObject().put("ownerId","owner").put("spaceId","private"),wanted,size<0?"unavailable":"read_limited",size<0?"source_operation_unavailable":"original_row_exceeds_inline_limit");
                else{window.reserve(size);JSONObject row=historyOperation(db,selected,window,id);window.rows++;result=TaskMemoryTrace.operationPage(new JSONObject().put("ownerId","owner").put("spaceId","private"),task,row,wanted);if("current".equals(result.optString("status"))){JSONObject range=result.getJSONObject("readRange");result.put("sourceJump",TaskMemoryTrace.rawJump(selected.taskId,selected.runId,wanted,range.getInt("offset"),range.getInt("chars")));}}
            }window.check();if("current".equals(result.optString("status"))&&result.has("body"))OwnerSourceRunContext.inheritPrepared(db,binding.caller.taskId,binding.caller.runId,selected.taskId,selected.runId,window.contextBytes(),window.signal);window.recheckOperationContexts(db);db.setTransactionSuccessful();
        }catch(TaskStoreMemoryReader.RawMessageUnavailable unavailable){result=TaskMemoryTrace.operationStatus(new JSONObject().put("ownerId","owner").put("spaceId","private"),wanted,"unavailable",OwnerSourceRunContext.UNAVAILABLE);}
        catch(RawLimit limited){result=TaskMemoryTrace.operationStatus(new JSONObject().put("ownerId","owner").put("spaceId","private"),wanted,"read_limited",limited.reason);}
        catch(OperationCanceledException deadline){binding.caller.check();result=TaskMemoryTrace.operationStatus(new JSONObject().put("ownerId","owner").put("spaceId","private"),wanted,"read_limited","read_deadline");}finally{db.endTransaction();}
        binding.check();return result.put("sourceKind","task_operation").put("sourceTaskId",selected.taskId).put("sourceRunId",selected.runId).put("sourceEpoch",selected.epoch).put("readBudget",window.coverage("operations",selected.sourceId,selected.sourceId,result.optString("reason"),false,-1));}});
    }

    /** Metadata/source-currentness only. No RunControl callbacks here: this is itself a retained
     * RunControl check and may execute under TaskStore's original monitor. */
    private static final class HistoryInspection implements AutoCloseable {
        final CancellationSignal signal=new CancellationSignal();final long started=System.nanoTime();
        final java.util.concurrent.ScheduledFuture<?> deadline=RAW_DEADLINES.schedule(signal::cancel,RAW_DEADLINE_MILLIS,java.util.concurrent.TimeUnit.MILLISECONDS);
        long sourceBytes,authorityBytes,operationContextBytes;final dev.pocketengine.features.assistant.ConversationRepository originals;final boolean derivedRead;final Map<String,String[]> operationContexts=new LinkedHashMap<>();String unavailableReason="";final long sourceLimit;final java.util.Set<String> verifiedReferences=new java.util.HashSet<>();final Map<String,JSONObject> authorities=new java.util.HashMap<>();
        final ToolOutputStore.Sources outputSources;final boolean checkOutputSources;final List<JSONArray> nativeOutputDependencies=new ArrayList<>();
        HistoryInspection(){this(RAW_MAX_BYTES,null,false);}
        HistoryInspection(long sourceLimit){this(sourceLimit,null,true);}
        HistoryInspection(long sourceLimit,dev.pocketengine.features.assistant.ConversationRepository originals,boolean derivedRead){this(sourceLimit,originals,derivedRead,null,false);}
        HistoryInspection(long sourceLimit,dev.pocketengine.features.assistant.ConversationRepository originals,boolean derivedRead,ToolOutputStore.Sources sources){this(sourceLimit,originals,derivedRead,sources,true);}
        HistoryInspection(long sourceLimit,dev.pocketengine.features.assistant.ConversationRepository originals,boolean derivedRead,ToolOutputStore.Sources sources,boolean outputCheck){this.outputSources=sources;checkOutputSources=outputCheck;this.originals=originals;this.derivedRead=derivedRead;if(sourceLimit<0||sourceLimit>RAW_MAX_BYTES)throw new IllegalArgumentException("历史来源预算无效");this.sourceLimit=sourceLimit;}
        void check(){if(signal.isCanceled()||Thread.currentThread().isInterrupted()||System.nanoTime()-started>=RAW_DEADLINE_MILLIS*1_000_000L)throw new SecurityException("历史来源metadata检查超时，旧正文未继续提供");}
        Cursor query(SQLiteDatabase db,String sql,String[] args){check();return db.rawQuery(sql,args,signal);}
        JSONObject task(SQLiteDatabase db,String id,boolean caller)throws Exception{
            if(authorities.containsKey(id))return authorities.get(id);long size=-1;
            try(Cursor row=query(db,TRACE_AUTHORITY_SIZE,new String[]{id})){if(row.moveToFirst()&&!row.isNull(0))size=row.getLong(0);}
            if(size<0)return null;if(size>RAW_MAX_BYTES-authorityBytes)throw new SecurityException("历史来源metadata超过本次1MiB门限");
            try(Cursor row=query(db,TRACE_AUTHORITY_ROW,new String[]{id})){if(!row.moveToFirst())return null;String text=row.getString(0);long actual=text.getBytes(StandardCharsets.UTF_8).length;if(actual>RAW_MAX_BYTES-authorityBytes)throw new SecurityException("历史来源metadata超过本次门限");authorityBytes+=actual;JSONObject task=new JSONObject(text);if(!id.equals(task.optString("id")))throw new SecurityException("原任务身份变化");authorities.put(id,task);check();return task;}
        }
        ConversationHistory.SourceBytes contextBytes(){return new ConversationHistory.SourceBytes(){public long remaining(){check();return sourceLimit-sourceBytes;}public void charge(long bytes)throws Exception{if(bytes<0||bytes>sourceLimit-sourceBytes)throw new TaskStoreMemoryReader.RawMessageUnavailable(OwnerSourceRunContext.UNAVAILABLE);sourceBytes+=bytes;operationContextBytes+=bytes;check();}};}
        void operationContext(SQLiteDatabase db,String task,String run,String conversation)throws Exception{if(!derivedRead)return;String key=task+"\n"+run;if(operationContexts.containsKey(key))return;requireOperationContext(db,originals,task,run,conversation,contextBytes(),signal);if(checkOutputSources)nativeOutputDependencies.add(OwnerSourceRunContext.verifiedOutputDependencies(db,outputSources,task,run,contextBytes(),signal));operationContexts.put(key,new String[]{task,run,conversation});}
        void recheckOperationContexts(SQLiteDatabase db)throws Exception{for(String[] context:operationContexts.values()){requireOperationContext(db,originals,context[0],context[1],context[2],contextBytes(),signal);if(checkOutputSources)for(JSONArray rows:nativeOutputDependencies)ToolOutputStore.requireDependencies(rows,outputSources);}}
        @Override public void close(){deadline.cancel(false);}
    }
    private static void requireHistoryScope(JSONObject task,String conversation)throws Exception{
        JSONObject origin=task==null?null:task.optJSONObject("origin");
        if(task==null||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||!"assistant".equals(task.optString("kind"))||!conversation.equals(task.optString("conversationId"))||origin!=null&&Set.of("group","channel").contains(origin.optString("kind")))throw new SecurityException("历史来源不再属于原私人会话");
    }
    private static void requireHistorySources(SQLiteDatabase db,String conversation,List<ConversationHistory.SourceLocator> provided,HistoryInspection window)throws Exception{
        if(provided==null||provided.size()>100)throw new SecurityException("历史来源检查条数超过100");
        for(ConversationHistory.SourceLocator ref:provided){
            String referenceKey=TaskMemoryTrace.rawFingerprint(ref.taskReference());if(window.verifiedReferences.contains(referenceKey))continue;if(window.verifiedReferences.size()>=100)throw new SecurityException("历史来源批次超过100个原引用");
            if(ref.kind==ConversationHistory.Kind.MESSAGE)throw new SecurityException("消息必须回到原Assistant来源");JSONObject task=window.task(db,ref.taskId,false);requireHistoryScope(task,conversation);if(!ref.membership.equals(historyMembership(task)))throw new SecurityException("历史来源成员身份已变化");if(ref.kind==ConversationHistory.Kind.TASK){window.verifiedReferences.add(referenceKey);continue;}
            try(Cursor row=window.query(db,"SELECT 1 FROM runs WHERE task_id=? AND run_id=? AND epoch=? LIMIT 1",new String[]{ref.taskId,ref.runId,Integer.toString(ref.epoch)})){if(!row.moveToFirst())throw new SecurityException("历史执行轮次已不存在");}if(ref.kind==ConversationHistory.Kind.RUN){window.verifiedReferences.add(referenceKey);continue;}
            window.operationContext(db,ref.taskId,ref.runId,conversation);
            String id=TaskMemoryTrace.operationId(TaskMemoryTrace.rawCitation(ref.taskId,ref.runId,new JSONObject().put("sourceId",ref.sourceId).put("revision",ref.revision)));long size=-1;
            try(Cursor row=window.query(db,"SELECT "+RAW_OPERATION_BYTES+" FROM operations WHERE operation_id=? AND task_id=? AND run_id=? LIMIT 1",new String[]{id,ref.taskId,ref.runId})){if(row.moveToFirst()&&!row.isNull(0))size=row.getLong(0);}
            if(size<0||size>window.sourceLimit-window.sourceBytes){window.unavailableReason=size<0?"related_source_unavailable":"related_source_byte_budget";throw new SecurityException("原历史操作缺失或超过本次metadata核对预算");}
            try(Cursor row=window.query(db,TRACE_OPERATION_ROW,new String[]{id,ref.taskId,ref.runId})){if(!row.moveToFirst())throw new SecurityException("原历史操作已不存在");String[] values={row.getString(0),row.getString(1),row.getString(2),row.getString(3),row.isNull(4)?null:row.getString(4),row.getString(5)};long actual=0;for(String value:values)if(value!=null)actual+=value.getBytes(StandardCharsets.UTF_8).length;if(actual>window.sourceLimit-window.sourceBytes)throw new SecurityException("原历史操作超过本次核对预算");window.sourceBytes+=actual;JSONObject op=TaskMemoryTrace.storedOperation(ref.taskId,values[5],values[0],values[1],values[2],values[3],values[4],row.getLong(6));window.check();if(!ref.revision.equals(TaskMemoryTrace.rawFingerprint(op)))throw new SecurityException("原历史操作内容已变化，旧引用停用");window.verifiedReferences.add(referenceKey);}
        }window.check();
    }
    public static void requireConversationHistorySources(SQLiteDatabase db,ConversationHistory.HistoryBinding binding,List<ConversationHistory.SourceLocator> provided)throws Exception{requireConversationHistorySources(db,binding,provided,null);}
    public static void requireConversationHistorySources(SQLiteDatabase db,ConversationHistory.HistoryBinding binding,List<ConversationHistory.SourceLocator> provided,dev.pocketengine.features.assistant.ConversationRepository originals)throws Exception{
        try(HistoryInspection window=new HistoryInspection(RAW_MAX_BYTES,originals,true,binding.caller.outputSources())){
            JSONObject caller=window.task(db,binding.caller.taskId,true);requireHistoryScope(caller,binding.conversationId);
            if(!binding.caller.runId.equals(caller.optString("runId"))||binding.caller.epoch!=SourceRef.integer(caller,"coordinatorEpoch",1)||!Boolean.FALSE.equals(caller.opt("cancelRequested")))throw new SecurityException("原历史调用轮次已停止");
            requireHistorySources(db,binding.conversationId,provided,window);window.recheckOperationContexts(db);
        }
    }
    /** Native owner viewer admission from the exact retained original tool event, not model JSON. */
    public static JSONObject conversationHistoryCitation(SQLiteDatabase db,JSONObject input)throws Exception{
        JSONObject wanted=ConversationHistory.checkedCitation(input);String callerId=wanted.getString("callerTaskId"),run=wanted.getString("callerRunId"),conversation=wanted.getString("conversationId");
        try(HistoryInspection window=new HistoryInspection()){
            JSONObject caller=window.task(db,callerId,true);requireHistoryScope(caller,conversation);if(!run.equals(caller.optString("runId")))throw new SecurityException("这次原生历史读取回执已不在原轮次");
            JSONArray citations=TaskMemoryTrace.historyCitationJumps(caller);boolean found=false;for(int i=0;i<citations.length();i++)if(TaskMemoryTrace.rawFingerprint(wanted).equals(TaskMemoryTrace.rawFingerprint(citations.getJSONObject(i)))){found=true;break;}
            if(!found)throw new SecurityException("此精确来源范围不在原任务保留的native历史读取回执内");
            if(wanted.has("taskSource"))requireHistorySources(db,conversation,List.of(ConversationHistory.SourceLocator.from(wanted.getJSONObject("taskSource"))),window);
            window.check();return wanted;
        }
    }
    private static JSONArray projectHistoryReferences(SQLiteDatabase db,JSONArray supplied,String conversation,HistoryInspection window)throws Exception{
        JSONArray projected=new JSONArray(supplied.toString());Map<String,String> calls=new java.util.HashMap<>();boolean stale=false;
        for(int i=0;i<projected.length();i++){window.check();JSONObject row=projected.getJSONObject(i);JSONArray declared=row.optJSONArray("tool_calls");for(int j=0;declared!=null&&j<declared.length();j++){JSONObject call=declared.getJSONObject(j);calls.put(call.getString("id"),call.getJSONObject("function").optString("name"));}
            String name=calls.get(row.optString("tool_call_id"));if(!"tool".equals(row.optString("role"))||!Set.of(ConversationHistory.SEARCH,ConversationHistory.READ).contains(name==null?"":name))continue;
            try{JSONObject receipt=new JSONObject(row.getString("content"));if(conversation.isEmpty()||!conversation.equals(receipt.optString("conversationId")))throw new SecurityException("原历史工具会话身份不符");JSONArray refs=receipt.getJSONArray("taskSourceRefs");if(refs.length()>100)throw new SecurityException("原历史来源数过多");List<ConversationHistory.SourceLocator> selected=new ArrayList<>();for(int j=0;j<refs.length();j++)selected.add(ConversationHistory.SourceLocator.from(refs.getJSONObject(j)));requireHistorySources(db,conversation,selected,window);}catch(Exception unavailable){stale=true;break;}
        }
        if(stale)return OwnerSourceRunContext.retireDerived(projected);return projected;
    }
    /** Request-only safe projection. Original audit remains untouched. */
    public static JSONArray projectConversationHistoryReferences(SQLiteDatabase db,JSONArray supplied,String conversation)throws Exception{return projectConversationHistoryReferences(db,supplied,conversation,null);}
    public static JSONArray projectConversationHistoryReferences(SQLiteDatabase db,JSONArray supplied,String conversation,dev.pocketengine.features.assistant.ConversationRepository originals)throws Exception{return projectConversationHistoryReferences(db,supplied,conversation,originals,null);}
    public static JSONArray projectConversationHistoryReferences(SQLiteDatabase db,JSONArray supplied,String conversation,dev.pocketengine.features.assistant.ConversationRepository originals,ToolOutputStore.Sources sources)throws Exception{
        try(HistoryInspection window=new HistoryInspection(RAW_MAX_BYTES,originals,true,sources)){JSONArray result=projectHistoryReferences(db,supplied,conversation,window);window.recheckOperationContexts(db);return result;}
        catch(Exception unavailable){return OwnerSourceRunContext.retireDerived(supplied);}
    }
    /** A short original-TaskStore-monitor read batch, not a persistent cache or an authority grant.
     * Verified operation bytes are reused only while that same monitor remains continuously held. */
    public static final class HistoryProjectionSession implements ConversationHistory.ProjectionBatch {
        private final SQLiteDatabase db;private final Object monitor;private final Thread thread;private final String conversation;private final HistoryInspection window;private final JSONObject caller;private final String callerId,callerRun;private boolean closed;private long extraProjectionSourceBytes;
        private HistoryProjectionSession(SQLiteDatabase db,Object monitor,ConversationHistory.HistoryBinding binding,long sourceLimit,dev.pocketengine.features.assistant.ConversationRepository originals)throws Exception{
            this.db=db;this.monitor=monitor;thread=Thread.currentThread();conversation=binding.conversationId;callerId=binding.caller.taskId;callerRun=binding.caller.runId;window=new HistoryInspection(sourceLimit,originals,true,binding.caller.outputSources());
            try{current();caller=window.task(db,binding.caller.taskId,true);requireHistoryScope(caller,conversation);if(!binding.caller.runId.equals(caller.optString("runId"))||binding.caller.epoch!=SourceRef.integer(caller,"coordinatorEpoch",1)||!Boolean.FALSE.equals(caller.opt("cancelRequested")))throw new SecurityException("原消息当前性门的调用轮次已停止");}
            catch(Exception failed){window.close();throw failed;}
        }
        private void current(){if(closed||Thread.currentThread()!=thread||!Thread.holdsLock(monitor))throw new SecurityException("消息投影必须仍在唯一原TaskStore短事务内");window.check();}
        public JSONObject callerTask()throws Exception{current();return new JSONObject(caller.toString());}
        public JSONArray project(JSONArray messages)throws Exception{current();JSONArray projected=projectHistoryReferences(db,messages,conversation,window);return projectOwnerSourceContext(db,window,callerId,callerRun,conversation,projected);}
        public long sourceBytesRemaining(){current();return window.sourceLimit-window.sourceBytes;}
        public void chargeSourceUtf8Bytes(long bytes)throws Exception{current();if(bytes<0||bytes>window.sourceLimit-window.sourceBytes){window.unavailableReason="extra_projection_source_byte_budget";throw new TaskStoreMemoryReader.RawMessageUnavailable("extra_projection_source_byte_budget");}window.sourceBytes+=bytes;extraProjectionSourceBytes+=bytes;}
        public JSONObject readBudget()throws Exception{return new JSONObject().put("guardSourceUtf8Bytes",window.sourceBytes).put("guardExtraProjectionSourceUtf8Bytes",extraProjectionSourceBytes).put("guardOperationContextUtf8Bytes",window.operationContextBytes).put("guardAuthorityUtf8Bytes",window.authorityBytes).put("guardUniqueSourceRefs",window.verifiedReferences.size()).put("guardSourceLimit",window.sourceLimit).put("guardUnavailableReason",window.unavailableReason).put("continuousOriginalAuthorityMonitor",true).put("guardByteAccounting","materialized_SQLite_text_not_physical_io");}
        @Override public void close()throws Exception{if(closed)return;try{current();window.recheckOperationContexts(db);}finally{closed=true;window.close();}}
    }
    public static HistoryProjectionSession openConversationHistoryProjection(SQLiteDatabase db,Object originalMonitor,ConversationHistory.HistoryBinding binding,long sourceLimit)throws Exception{return openConversationHistoryProjection(db,originalMonitor,binding,sourceLimit,null);}
    public static HistoryProjectionSession openConversationHistoryProjection(SQLiteDatabase db,Object originalMonitor,ConversationHistory.HistoryBinding binding,long sourceLimit,dev.pocketengine.features.assistant.ConversationRepository originals)throws Exception{return new HistoryProjectionSession(db,originalMonitor,binding,sourceLimit,originals);}

    /** Exact primary-key source read under the original TaskStore monitor, never a second database.
     * At most one task and operation are materialized. SQLite's size probes are not a physical I/O guarantee.
     */
    public static JSONObject readOperation(SQLiteDatabase db,MemoryBook.Scope scope,JSONObject request)throws Exception{
        if(db==null||scope==null)throw new IllegalArgumentException("原任务来源查询不可用");
        String operationId=TaskMemoryTrace.operationId(request);
        if(!"owner".equals(scope.ownerId)||!"private".equals(scope.spaceId))throw new SecurityException("此入口只读原私人操作来源");
        JSONObject authenticated=new JSONObject().put("ownerId",scope.ownerId).put("spaceId",scope.spaceId);
        String[] taskArgs={request.getString("taskId")},operationArgs={operationId,request.getString("taskId"),request.getString("runId")};
        db.beginTransaction();try{
            try(Cursor size=db.rawQuery(TRACE_AUTHORITY_SIZE,taskArgs)){
                if(!size.moveToFirst()||size.isNull(0)||size.getLong(0)>TaskMemoryTrace.MAX_OPERATION_AUTHORITY_BYTES)
                    return TaskMemoryTrace.operationStatus(authenticated,request,"unavailable","authority_outside_inline_read_limit");
            }
            JSONObject task;
            try(Cursor row=db.rawQuery(TRACE_AUTHORITY_ROW,taskArgs)){
                if(!row.moveToFirst())return TaskMemoryTrace.operationStatus(authenticated,request,"unavailable","original_task_unavailable");
                task=new JSONObject(row.getString(0));
            }
            TaskMemoryTrace.operationPage(authenticated,task,null,request); // Authenticate the original task before any operation payload is read.
            try(Cursor size=db.rawQuery(TRACE_OPERATION_SIZE,operationArgs)){
                if(!size.moveToFirst())return TaskMemoryTrace.operationPage(authenticated,task,null,request);
                if(size.isNull(0)||size.getLong(0)>TaskMemoryTrace.MAX_OPERATION_SOURCE_BYTES)
                    return TaskMemoryTrace.operationStatus(authenticated,request,"read_limited","original_row_exceeds_inline_limit");
            }
            JSONObject operation=null;
            try(Cursor row=db.rawQuery(TRACE_OPERATION_ROW,operationArgs)){
                if(row.moveToFirst())operation=TaskMemoryTrace.storedOperation(request.getString("taskId"),row.getString(5),row.getString(0),row.getString(1),row.getString(2),row.getString(3),row.isNull(4)?null:row.getString(4),row.getLong(6));
            }
            JSONObject result=TaskMemoryTrace.operationPage(authenticated,task,operation,request);db.setTransactionSuccessful();return result;
        }finally{db.endTransaction();}
    }
    /** Invoke under the existing TaskStore monitor; db is its current authority, never a copied DB. */
    public static TaskStoreMemoryReader.TaskPage query(SQLiteDatabase db,MemoryBook.Scope scope,String query,String cursor,int limit)throws Exception{
        if(db==null||scope==null||query==null||query.length()>500||cursor==null||cursor.length()>4000||limit<1||limit>100)
            throw new IllegalArgumentException("任务检索参数无效");
        String normalized=normalize(query);Position position=cursor.isEmpty()?null:decode(scope,normalized,cursor);
        String sql="SELECT task_id,document,updated_at FROM tasks";String[] args=null;
        if(position!=null){
            sql+=" WHERE (updated_at<? OR (updated_at=? AND task_id<=?)) AND (updated_at<? OR (updated_at=? AND task_id<?))";
            args=new String[]{Long.toString(position.upperAt),Long.toString(position.upperAt),position.upperId,
                    Long.toString(position.lastAt),Long.toString(position.lastAt),position.lastId};
        }
        sql+=" ORDER BY updated_at DESC, task_id DESC";
        List<String> ids=new ArrayList<>();long upperAt=position==null?0:position.upperAt,lastAt=0;String upperId=position==null?null:position.upperId,lastId=null;
        try(Cursor rows=db.rawQuery(sql,args)){
            while(rows.moveToNext()){
                String id=rows.getString(0);JSONObject task=new JSONObject(rows.getString(1));long at=rows.getLong(2);
                if(!scope.ownerId.equals(task.optString("actorId"))||!scope.spaceId.equals(task.optString("spaceId")))continue;
                if(!id.equals(task.optString("id"))||at<0||!validTaskId(id))throw new IllegalStateException("原任务索引身份无效，未返回推测结果");
                if(upperId==null){upperAt=at;upperId=id;} // Scope-filter first; cursor never leaks another space's IDs.
                if(!matches(dev.pocketengine.store.TaskAuditStore.searchProjection(db,task),normalized))continue;
                if(ids.size()==limit)return new TaskStoreMemoryReader.TaskPage(ids,encode(scope,normalized,new Position(upperAt,upperId,lastAt,lastId)));
                ids.add(id);lastAt=at;lastId=id;
            }
        }
        return new TaskStoreMemoryReader.TaskPage(ids,"");
    }
    static String normalize(String value){MemoryBook.validateUnicode(value);return Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").trim();}
    static boolean matches(JSONObject task,String normalized)throws Exception{
        StringBuilder text=new StringBuilder(task.optString("id")).append('\n').append(task.optString("goal")).append('\n')
                .append(task.optString("summary")).append('\n').append(task.optString("feedback"));
        JSONArray events=task.optJSONArray("events");
        for(int i=0;events!=null&&i<events.length();i++){
            JSONObject event=events.optJSONObject(i);if(event==null||!"user_verification".equals(event.optString("type")))continue;
            text.append('\n').append(event.optString("feedback"));JSONObject snapshot=event.optJSONObject("memorySourceSnapshot");
            if(snapshot!=null&&!event.optBoolean("memorySourceSnapshotForgotten"))text.append('\n').append(snapshot.optString("goal")).append('\n').append(snapshot.optString("summary")).append('\n').append(snapshot.optString("ownerFeedback"));
        }
        String haystack=normalize(text.toString());for(String term:normalized.split("\\s+"))if(!haystack.contains(term))return false;return true;
    }
    private static final class Position {
        final long upperAt,lastAt;final String upperId,lastId;
        Position(long upperAt,String upperId,long lastAt,String lastId){this.upperAt=upperAt;this.upperId=upperId;this.lastAt=lastAt;this.lastId=lastId;}
    }
    private static String encode(MemoryBook.Scope scope,String query,Position position)throws Exception{
        JSONObject value=new JSONObject().put("version",1).put("ownerId",scope.ownerId).put("spaceId",scope.spaceId).put("queryDigest",TaskMemoryUse.digest(query))
                .put("upperAt",position.upperAt).put("upperId",position.upperId).put("lastAt",position.lastAt).put("lastId",position.lastId);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static Position decode(MemoryBook.Scope scope,String query,String cursor)throws Exception{
        JSONObject value;try{value=new JSONObject(new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8));}
        catch(Exception invalid){throw new IllegalArgumentException("任务游标不可读，请重新搜索",invalid);}
        Set<String> allowed=Set.of("version","ownerId","spaceId","queryDigest","upperAt","upperId","lastAt","lastId");
        if(value.length()!=allowed.size())throw new IllegalArgumentException("任务游标字段无效");
        for(Iterator<String> keys=value.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new IllegalArgumentException("任务游标字段无效");
        if(SourceRef.integer(value,"version",1)!=1||!scope.ownerId.equals(value.opt("ownerId"))||!scope.spaceId.equals(value.opt("spaceId"))||!TaskMemoryUse.digest(query).equals(value.opt("queryDigest")))
            throw new SecurityException("任务游标不属于当前空间或查询");
        long upper=SourceRef.integer(value,"upperAt",0),last=SourceRef.integer(value,"lastAt",0);String upperId=value.getString("upperId"),lastId=value.getString("lastId");
        if(!validTaskId(upperId)||!validTaskId(lastId)||last>upper||last==upper&&binaryCompare(lastId,upperId)>0)
            throw new IllegalArgumentException("任务游标位置无效");
        return new Position(upper,upperId,last,lastId);
    }
    public static boolean validTaskId(String id){
        if(id==null||id.isEmpty()||id.length()>240)return false;
        try{MemoryBook.validateUnicode(id);}catch(IllegalArgumentException invalid){return false;}
        for(int i=0;i<id.length();i++)if(Character.isISOControl(id.charAt(i)))return false;return true;
    }
    private static int binaryCompare(String left,String right){
        byte[] a=left.getBytes(StandardCharsets.UTF_8),b=right.getBytes(StandardCharsets.UTF_8);
        for(int i=0;i<Math.min(a.length,b.length);i++){int difference=(a[i]&255)-(b[i]&255);if(difference!=0)return difference;}return a.length-b.length;
    }
}
