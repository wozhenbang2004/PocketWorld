package dev.pocketengine.features.memory;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.CapabilitySnapshot;
import dev.pocketengine.core.RunControl;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only original history. Native owner scope is separate from the current caller/control. */
public final class ConversationHistory {
    public static final String SEARCH="memory_history_search",READ="memory_history_read";
    private ConversationHistory(){}
    public interface Gate {boolean current();}
    public enum ScopeMode {CURRENT,OWNER_PRIVATE}
    /** Issued by the original native owner configuration, never a model JSON field. */
    public interface ReadScope {
        String conversationId();
        ScopeMode mode();
        long revision();
        void checkCurrent()throws Exception;
    }
    /** Implemented only by the original Assistant authority, never by a model/feature JSON route. */
    public interface Source {
        Gate open(String nativeConversationId)throws Exception;
        default ReadScope captureReadScope(String callerConversationId)throws Exception{return null;}
        default String conversationFor(SourceRef original)throws Exception{return null;}
        default JSONObject conversations(ReadScope original,JSONObject options,RunControl control)throws Exception{
            throw new SecurityException("原宿主未提供主人已授权的跨会话范围");
        }
        JSONObject query(Gate gate,JSONObject options,RunControl original)throws Exception;
        JSONArray inspect(Gate gate,List<SourceRef> expected)throws Exception;
        JSONObject read(Gate gate,SourceRef expected,int offset,int maxChars,RunControl original)throws Exception;
        /** Sequence adjacency only, never ancestry. Old providers must not invent a neighborhood. */
        default JSONObject neighbors(Gate gate,SourceRef anchor,String fingerprint,String direction,JSONObject budgets,RunControl original)throws Exception{
            return proof(new JSONObject().put("status","unavailable").put("reason","native_history_neighbors_unavailable").put("entries",new JSONArray()).put("gaps",new JSONArray()).put("nextCursor","").put("hasMore",false));
        }
    }
    public static final class HistoryBinding {
        public final TaskStoreMemoryReader.RawBinding caller;public final String conversationId,sourceConversationId;public final Source source;
        public final ReadScope readScope;
        public HistoryBinding(TaskStoreMemoryReader.RawBinding caller,String nativeConversationId,Source source)throws Exception{
            this(caller,nativeConversationId,nativeConversationId,source,source.captureReadScope(nativeConversationId));
        }
        private HistoryBinding(TaskStoreMemoryReader.RawBinding caller,String nativeConversationId,String sourceConversation,Source source,ReadScope scope)throws Exception{
            this.caller=Objects.requireNonNull(caller);this.source=Objects.requireNonNull(source);readScope=scope;
            if(!TaskStoreMemoryQuery.validTaskId(nativeConversationId)||!TaskStoreMemoryQuery.validTaskId(sourceConversation))throw new SecurityException("没有真实原私人会话，不能猜测历史范围");
            conversationId=nativeConversationId;sourceConversationId=sourceConversation;requireScopeCurrent();caller.check();source.open(conversationId);source.open(sourceConversationId);
        }
        public void requireScopeCurrent()throws Exception{
            if(readScope!=null){if(!conversationId.equals(readScope.conversationId())||readScope.revision()<0)throw new SecurityException("历史范围不属于原主人当前会话");readScope.checkCurrent();}
            if(!conversationId.equals(sourceConversationId)&&(readScope==null||readScope.mode()!=ScopeMode.OWNER_PRIVATE))throw new SecurityException("主人尚未开启这个会话的跨会话只读范围");
        }
        public boolean permitsConversation(String id)throws Exception{requireScopeCurrent();return conversationId.equals(id)||readScope!=null&&readScope.mode()==ScopeMode.OWNER_PRIVATE;}
        HistoryBinding forSource(String id)throws Exception{if(!permitsConversation(id))throw new SecurityException("历史来源超出本轮主人范围");return sourceConversationId.equals(id)?this:new HistoryBinding(caller,conversationId,id,source,readScope);}
        Gate messageGate(SourceRef ref)throws Exception{return messageGateAt(ref,sourceConversationId);}
        Gate messageGateAt(SourceRef ref,String expectedConversation)throws Exception{String actual=source.conversationFor(ref);if(actual==null)actual=expectedConversation;if(!expectedConversation.equals(actual)||!permitsConversation(actual))throw new SecurityException("消息来源的原会话已变化或不在主人范围内");return source.open(actual);}
        public void check()throws Exception{requireScopeCurrent();caller.check();source.open(conversationId);source.open(sourceConversationId);}
        void checkRetiredLifecycle()throws Exception{requireScopeCurrent();caller.checkNativeLifecycle();source.open(conversationId);source.open(sourceConversationId);}
    }
    public enum Kind {CONVERSATION,MESSAGE,TASK,RUN,OPERATION,OUTPUT}
    /** Native-discovered source identity; it never owns a live control or grants historical execution. */
    public static final class SourceLocator {
        public final Kind kind;public final String taskId,runId,sourceId,revision,membership,messageFingerprint,conversationId;public final int epoch;public final SourceRef message;
        private SourceLocator(Kind kind,String task,String run,int epoch,String source,String revision,String membership,SourceRef message,String fingerprint,String conversation){this.kind=kind;taskId=task;runId=run;this.epoch=epoch;sourceId=source;this.revision=revision;this.membership=membership;this.message=message;messageFingerprint=fingerprint;conversationId=conversation;}
        public JSONObject taskReference()throws Exception{
            if(kind==Kind.MESSAGE||kind==Kind.CONVERSATION)throw new IllegalArgumentException("会话/消息不是任务指纹");
            JSONObject ref=new JSONObject().put("sourceKind",kind==Kind.TASK?"task_record":kind==Kind.RUN?"task_run":kind==Kind.OUTPUT?"task_output":"task_operation").put("sourceTaskId",taskId).put("membershipFingerprint",membership);
            if(!conversationId.isEmpty())ref.put("sourceConversationId",conversationId);
            if(kind!=Kind.TASK)ref.put("sourceRunId",runId).put("sourceEpoch",epoch);
            if(kind==Kind.OPERATION||kind==Kind.OUTPUT)ref.put("sourceId",sourceId).put("revision",revision);
            return ref;
        }
        String referenceKey(){return kind+"\n"+conversationId+"\n"+taskId+"\n"+runId+"\n"+sourceId;}
        static SourceLocator from(JSONObject row)throws Exception{
            String type=row.getString("sourceKind"),conversation=row.optString("sourceConversationId");if(!conversation.isEmpty()&&!TaskStoreMemoryQuery.validTaskId(conversation))throw new SecurityException("原历史来源会话身份无效");
            if(type.equals("assistant_conversation")){if(conversation.isEmpty()||!Boolean.TRUE.equals(row.opt("metadataOnly"))||!Boolean.FALSE.equals(row.opt("originalBodyIncluded")))throw new SecurityException("历史会话目录不是原生无正文位置");return new SourceLocator(Kind.CONVERSATION,"","",0,"","","",null,"",conversation);}
            if(type.equals("assistant_message")){SourceRef ref=SourceRef.fromJson(row.getJSONObject("sourceRef"));if(!"message".equals(ref.kind)||!"owner".equals(ref.ownerId)||!"private".equals(ref.spaceId))throw new SecurityException("历史消息来源不能跨私人空间");String fingerprint=row.getString("bodyFingerprint");if(!fingerprint.matches("sha256:[0-9a-f]{64}"))throw new SecurityException("历史消息缺少原生正文指纹");return new SourceLocator(Kind.MESSAGE,"","",0,ref.sourceId,"","",ref,fingerprint,conversation);}
            String task=row.getString("sourceTaskId"),run=row.optString("sourceRunId");if(!TaskStoreMemoryQuery.validTaskId(task)||!run.isEmpty()&&!TaskStoreMemoryQuery.validTaskId(run))throw new SecurityException("历史定位不是原任务标识");
            Kind kind=switch(type){case "task_record"->Kind.TASK;case "task_run"->Kind.RUN;case "task_operation"->Kind.OPERATION;case "task_output"->Kind.OUTPUT;default->throw new SecurityException("未知历史来源种类");};
            String revision=row.optString("revision"),source=row.optString("sourceId");int epoch=kind==Kind.TASK?0:Math.toIntExact(SourceRef.integer(row,"sourceEpoch",1));
            if(kind==Kind.OPERATION)TaskMemoryTrace.rawCitation(task,run,new JSONObject().put("sourceId",source).put("revision",revision));
            if(kind==Kind.OUTPUT){ToolOutputStore.outputId(task,run,source);if(!revision.matches("sha256:[0-9a-f]{64}")||!row.getString("membershipFingerprint").matches("sha256:[0-9a-f]{64}"))throw new SecurityException("历史原输出缺精确hash或原归属版本");}
            return new SourceLocator(kind,task,run,epoch,source,revision,row.getString("membershipFingerprint"),null,"",conversation);
        }
    }
    public interface Tasks {
        JSONObject query(HistoryBinding binding,SourceLocator selected,String kind,JSONObject options)throws Exception;
        JSONObject read(HistoryBinding binding,SourceLocator selected,int offset,int maxChars)throws Exception;
        /** Bounded native metadata/hash checks; no control.check recursion or source execution. */
        void requireCurrent(HistoryBinding binding,List<SourceLocator> provided)throws Exception;
    }
    public static final class Request {
        public final String kind,query,cursor,selection,direction;public final int limit,maxChars;public final long after,before;
        public Request(JSONObject input)throws Exception{
            if(input==null)throw new IllegalArgumentException("缺少历史查询");Set<String> allowed=Set.of("kind","query","cursor","selectionId","direction","limit","maxChars","createdAfter","createdBefore");for(Iterator<String> it=input.keys();it.hasNext();)if(!allowed.contains(it.next()))throw new IllegalArgumentException("历史查询不能指定会话/任务/轮次");
            kind=text(input,"kind","messages",20);if(!Set.of("conversations","messages","neighbors","runs","operations","outputs").contains(kind))throw new IllegalArgumentException("未知历史查询范围");query=text(input,"query","",500);cursor=text(input,"cursor","",4000);selection=text(input,"selectionId","",36);direction=text(input,"direction","",6);
            limit=integer(input,"limit",10,1,20);maxChars=integer(input,"maxChars",4000,64,16000);after=input.has("createdAfter")?SourceRef.integer(input,"createdAfter",0):-1;before=input.has("createdBefore")?SourceRef.integer(input,"createdBefore",0):-1;if(after>=0&&before>=0&&after>before)throw new IllegalArgumentException("历史日期范围颠倒");
            if(kind.equals("neighbors")){if(selection.isEmpty()||!Set.of("before","after").contains(direction)||!query.isEmpty()||input.has("createdAfter")||input.has("createdBefore")||!cursor.isEmpty())throw new IllegalArgumentException("消息邻域只接受本轮消息selectionId、before/after和预算；不是关键词/日期或全历史续页");}
            else if(input.has("direction"))throw new IllegalArgumentException("只有消息邻域接受direction");
            if(kind.equals("conversations")&&(!selection.isEmpty()||!query.isEmpty()||after>=0||before>=0))throw new IllegalArgumentException("会话目录仅允许无正文有界分页；不能传原始会话ID");
        }
        public JSONObject options(String nativeCursor)throws Exception{return new JSONObject().put("query",query).put("cursor",nativeCursor).put("limit",limit).put("maxChars",maxChars).put("createdAfter",after).put("createdBefore",before);}
        static String text(JSONObject obj,String key,String fallback,int max){Object value=obj.opt(key);if(value==null)return fallback;if(!(value instanceof String)||((String)value).length()>max)throw new IllegalArgumentException("历史参数超过范围");MemoryBook.validateUnicode((String)value);return (String)value;}
        static int integer(JSONObject obj,String key,int fallback,int min,int max){if(!obj.has(key))return fallback;long value=SourceRef.integer(obj,key,min);if(value>max)throw new IllegalArgumentException("历史读取范围过大");return (int)value;}
    }
    public static JSONObject envelope(HistoryBinding binding)throws Exception{JSONObject value=new JSONObject().put("callerTaskId",binding.caller.taskId).put("callerRunId",binding.caller.runId).put("conversationId",binding.conversationId).put("ownerId","owner").put("spaceId","private");if(binding.readScope!=null)value.put("historyScopeMode",binding.readScope.mode().name()).put("historyScopeRevision",binding.readScope.revision());return proof(value);}
    /** Pure native configuration check for restored tool bodies. Receipt JSON never grants scope. */
    public static void requireReceiptScope(ReadScope nativeScope,String caller,JSONObject receipt,String sourceConversation)throws Exception{
        if(!TaskStoreMemoryQuery.validTaskId(caller)||!caller.equals(receipt.optString("conversationId"))||!TaskStoreMemoryQuery.validTaskId(sourceConversation))throw new SecurityException("原历史回执会话不符");
        if(nativeScope!=null){if(!caller.equals(nativeScope.conversationId()))throw new SecurityException("原历史范围主体变化");nativeScope.checkCurrent();}
        if(!caller.equals(sourceConversation)&&(nativeScope==null||nativeScope.mode()!=ScopeMode.OWNER_PRIVATE||!"OWNER_PRIVATE".equals(receipt.optString("historyScopeMode"))||!receipt.has("historyScopeRevision")||SourceRef.integer(receipt,"historyScopeRevision",0)!=nativeScope.revision()))throw new SecurityException("跨会话原文不再具有主人本次明确范围");
    }
    public static JSONObject proof(JSONObject result)throws Exception{return result.put("executionAuthorized",false).put("executionIndependentlyVerified",false).put("formalMemorySaved",false).put("completeOriginalTrace",false);}
    /** Restore only canonical native locator metadata after captured-key text cleaning. Bodies,
     * titles, excerpts, arbitrary reasons and diagnostics always remain the cleaned values. This
     * is structural preservation, not source admission: all original SQL/source gates still run. */
    public static JSONObject restoreSafeReferenceMetadata(String tool,JSONObject nativeReceipt,JSONObject cleanedReceipt)throws Exception{
        JSONObject result=new JSONObject(cleanedReceipt.toString());if(!SEARCH.equals(tool)&&!READ.equals(tool))return result;
        if(nativeReceipt==null)throw new SecurityException("历史工具缺原生回执");
        for(String key:List.of("callerTaskId","callerRunId","conversationId"))if(!TaskStoreMemoryQuery.validTaskId(nativeReceipt.getString(key)))throw new SecurityException("原历史回执身份无效");
        if(!"owner".equals(nativeReceipt.opt("ownerId"))||!"private".equals(nativeReceipt.opt("spaceId")))throw new SecurityException("历史回执不能更换认证空间");
        for(String key:List.of("executionAuthorized","executionIndependentlyVerified","formalMemorySaved","completeOriginalTrace"))if(!Boolean.FALSE.equals(nativeReceipt.opt(key)))throw new SecurityException("历史回执不能恢复任意授权字段");
        for(String key:List.of("callerTaskId","callerRunId","conversationId","ownerId","spaceId"))result.put(key,nativeReceipt.get(key));proof(result);
        if(nativeReceipt.has("historyScopeMode")){String mode=nativeReceipt.getString("historyScopeMode");if(!Set.of("CURRENT","OWNER_PRIVATE").contains(mode))throw new SecurityException("原历史范围模式未知");result.put("historyScopeMode",mode).put("historyScopeRevision",SourceRef.integer(nativeReceipt,"historyScopeRevision",0));}
        String status=nativeReceipt.getString("status");if(!Set.of("current","partial","unavailable","read_limited","source_changed","deleted","retired","provenance_unavailable").contains(status))throw new SecurityException("未知原历史回执状态");result.put("status",status);
        if(nativeReceipt.has("nextCursor")){String cursor=nativeReceipt.getString("nextCursor");if(!cursor.isEmpty()&&!nativeOpaque(cursor))throw new SecurityException("历史游标不是原生opaque身份");result.put("nextCursor",cursor);}
        if(nativeReceipt.has("messageSourceRefs")){
            JSONArray originals=nativeReceipt.getJSONArray("messageSourceRefs"),refs=new JSONArray();if(originals.length()>100)throw new SecurityException("原历史消息引用过多");
            for(int i=0;i<originals.length();i++){SourceRef ref=SourceRef.fromJson(originals.getJSONObject(i));if(!"message".equals(ref.kind)||!"owner".equals(ref.ownerId)||!"private".equals(ref.spaceId))throw new SecurityException("原历史消息引用越界");refs.put(ref.toJson());}result.put("messageSourceRefs",refs);
        }
        if(nativeReceipt.has("taskSourceRefs")){
            JSONArray originals=nativeReceipt.getJSONArray("taskSourceRefs"),refs=new JSONArray();if(originals.length()>100)throw new SecurityException("原历史任务引用过多");
            for(int i=0;i<originals.length();i++)refs.put(checkedTaskLocator(originals.getJSONObject(i)).taskReference());result.put("taskSourceRefs",refs);
        }
        if(SEARCH.equals(tool)){
            JSONArray originals=nativeReceipt.getJSONArray("entries"),clean=result.getJSONArray("entries");if(originals.length()>20||clean.length()!=originals.length())throw new SecurityException("历史行结构在清理后已变化");
            for(int i=0;i<originals.length();i++)restoreReferenceRow(nativeReceipt,originals.getJSONObject(i),clean.getJSONObject(i));
        }else if(nativeReceipt.has("sourceKind"))restoreReferenceRow(nativeReceipt,nativeReceipt,result);
        return result;
    }
    private static boolean nativeOpaque(String value){try{return value!=null&&UUID.fromString(value).toString().equals(value);}catch(IllegalArgumentException invalid){return false;}}
    private static SourceLocator checkedTaskLocator(JSONObject supplied)throws Exception{
        SourceLocator ref=SourceLocator.from(supplied);if(ref.kind==Kind.MESSAGE||ref.kind==Kind.CONVERSATION||!ref.membership.matches("sha256:[0-9a-f]{64}"))throw new SecurityException("任务来源缺原生归属指纹");return ref;
    }
    private static void restoreReferenceRow(JSONObject envelope,JSONObject original,JSONObject clean)throws Exception{
        SourceLocator ref=null;
        if(original==envelope&&Set.of("task_operation","task_output").contains(original.optString("sourceKind"))){
            JSONArray refs=envelope.getJSONArray("taskSourceRefs");for(int i=0;i<refs.length();i++){
                SourceLocator candidate=checkedTaskLocator(refs.getJSONObject(i));
                if(candidate.sourceId.equals(original.optString("sourceId"))&&candidate.revision.equals(original.optString("revision"))){
                    if(ref!=null)throw new SecurityException("历史读取重复了原定位");ref=candidate;
                }
            }
            if(ref==null)throw new SecurityException("历史读取缺少原任务精确引用");
        }else ref=SourceLocator.from(original);
        if(ref.kind==Kind.CONVERSATION){clean.put("sourceKind","assistant_conversation").put("sourceConversationId",ref.conversationId).put("metadataOnly",true).put("originalBodyIncluded",false);}
        else if(ref.kind==Kind.MESSAGE){clean.put("sourceKind","assistant_message").put("sourceRef",ref.message.toJson()).put("bodyFingerprint",ref.messageFingerprint);if(!ref.conversationId.isEmpty())clean.put("sourceConversationId",ref.conversationId);}
        else{
            if(!ref.membership.matches("sha256:[0-9a-f]{64}"))throw new SecurityException("历史原定位缺归属指纹");
            JSONObject metadata=ref.taskReference();for(Iterator<String> keys=metadata.keys();keys.hasNext();){String key=keys.next();clean.put(key,metadata.get(key));}
            if(original.has("taskId")){if(!ref.taskId.equals(original.getString("taskId")))throw new SecurityException("历史读取的原任务身份冲突");clean.put("taskId",ref.taskId);}
            if(original.has("runId")){if(!ref.runId.equals(original.getString("runId")))throw new SecurityException("历史读取的原执行身份冲突");clean.put("runId",ref.runId);}
        }
        if(original.has("selectionId")){String selection=original.getString("selectionId");if(!nativeOpaque(selection))throw new SecurityException("历史选择不是原生opaque身份");clean.put("selectionId",selection);}
        if(original.has("sourceJump")){
            JSONObject citation=new JSONObject().put("callerTaskId",envelope.getString("callerTaskId")).put("callerRunId",envelope.getString("callerRunId"))
                    .put("conversationId",envelope.getString("conversationId")).put("sourceJump",original.getJSONObject("sourceJump"));
            if(ref.kind!=Kind.MESSAGE)citation.put("taskSource",ref.taskReference());
            JSONObject range=original.getJSONObject("readRange");long offset=SourceRef.integer(range,"offset",0),chars=SourceRef.integer(range,"chars",0),total=SourceRef.integer(range,"totalChars",0);
            if(!"UTF-16".equals(range.opt("unit"))||offset+chars>total||total>Integer.MAX_VALUE||chars>16000
                    ||!(original.opt("body") instanceof String)||original.getString("body").length()!=chars)throw new SecurityException("历史引用范围不是原始返回范围");
            JSONObject jump;
            if(chars>0)jump=checkedCitation(citation).getJSONObject("sourceJump");
            else if(ref.kind==Kind.MESSAGE)jump=new JSONObject().put("kind","conversation_history_message").put("conversationId",ref.conversationId.isEmpty()?envelope.getString("conversationId"):ref.conversationId)
                    .put("sourceRef",ref.message.toJson()).put("bodyFingerprint",ref.messageFingerprint).put("offset",offset).put("chars",0).put("unit","UTF-16");
            else jump=TaskMemoryTrace.rawJump(ref.taskId,ref.runId,new JSONObject().put("sourceId",ref.sourceId).put("revision",ref.revision),(int)offset,0);
            if(offset!=jump.getInt("offset")||chars!=jump.getInt("chars")||!TaskMemoryTrace.rawFingerprint(jump).equals(TaskMemoryTrace.rawFingerprint(original.getJSONObject("sourceJump"))))throw new SecurityException("历史定位范围不一致");
            clean.put("sourceJump",jump).put("readRange",TaskMemoryTrace.rawRange((int)offset,(int)(offset+chars),(int)total));
        }
    }
    public static JSONObject messagePage(String conversation,JSONObject message,int offset,int maxChars)throws Exception{
        SourceRef ref=SourceRef.fromJson(new JSONObject().put("kind","message").put("sourceId",message.getString("id")).put("revision",message.get("revision")).put("ownerId","owner").put("spaceId","private"));
        String body=message.getString("content");int end=TaskMemoryTrace.safeEnd(body,offset,maxChars);String fingerprint="sha256:"+TaskMemoryUse.digest(body);
        JSONObject jump=new JSONObject().put("kind","conversation_history_message").put("conversationId",conversation).put("sourceRef",ref.toJson()).put("bodyFingerprint",fingerprint).put("offset",offset).put("chars",end-offset).put("unit","UTF-16");
        return proof(new JSONObject().put("status","current").put("reason","").put("sourceKind","assistant_message").put("sourceConversationId",conversation).put("sourceRef",ref.toJson()).put("bodyFingerprint",fingerprint).put("revisionKind","native_source_revision")
                .put("body",body.substring(offset,end)).put("readRange",TaskMemoryTrace.rawRange(offset,end,body.length())).put("nextOffset",end<body.length()?end:-1).put("sourceJump",jump)
                .put("at",message.opt("at") instanceof Number?message.get("at"):JSONObject.NULL).put("timeStatus",message.opt("at") instanceof Number?"stored_message_time":"unknown"));
    }
    /** UI/native consistency only. Actual admission must match an original stored tool event. */
    public static JSONObject checkedCitation(JSONObject supplied)throws Exception{
        if(supplied==null)throw new IllegalArgumentException("缺少历史原件引用");
        Set<String> allowed=Set.of("callerTaskId","callerRunId","conversationId","sourceJump","taskSource");
        for(Iterator<String> keys=supplied.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new SecurityException("历史回看不能覆盖原生身份");
        for(String key:List.of("callerTaskId","callerRunId","conversationId"))if(!TaskStoreMemoryQuery.validTaskId(supplied.getString(key)))throw new SecurityException("原生历史回看身份无效");
        JSONObject jump=supplied.getJSONObject("sourceJump");int offset=Math.toIntExact(SourceRef.integer(jump,"offset",0)),chars=Math.toIntExact(SourceRef.integer(jump,"chars",1));
        if(chars>16000||!"UTF-16".equals(jump.optString("unit")))throw new IllegalArgumentException("历史引用范围无效");
        JSONObject expected;
        if("task_raw_source".equals(jump.optString("kind"))){
            SourceLocator ref=SourceLocator.from(supplied.getJSONObject("taskSource"));if(ref.kind!=Kind.OPERATION&&ref.kind!=Kind.OUTPUT)throw new SecurityException("历史原件必须是已发现的原操作或留存输出");
            if(!ref.taskId.equals(jump.optString("taskId"))||!ref.runId.equals(jump.optString("runId"))||!ref.sourceId.equals(jump.optString("sourceId"))||!ref.revision.equals(jump.optString("revision")))throw new SecurityException("历史回看定位与原操作不符");
            expected=TaskMemoryTrace.rawJump(ref.taskId,ref.runId,new JSONObject().put("sourceId",ref.sourceId).put("revision",ref.revision),offset,chars);
        }else if("conversation_history_message".equals(jump.optString("kind"))){
            SourceRef ref=SourceRef.fromJson(jump.getJSONObject("sourceRef"));if(!"message".equals(ref.kind)||!"owner".equals(ref.ownerId)||!"private".equals(ref.spaceId)||!TaskStoreMemoryQuery.validTaskId(jump.optString("conversationId"))||supplied.has("taskSource")||!jump.optString("bodyFingerprint").matches("sha256:[0-9a-f]{64}"))throw new SecurityException("历史原消息引用无效");
            expected=new JSONObject().put("kind","conversation_history_message").put("conversationId",jump.getString("conversationId")).put("sourceRef",ref.toJson()).put("bodyFingerprint",jump.getString("bodyFingerprint")).put("offset",offset).put("chars",chars).put("unit","UTF-16");
        }else throw new IllegalArgumentException("未知历史原件种类");
        if(!TaskMemoryTrace.rawFingerprint(expected).equals(TaskMemoryTrace.rawFingerprint(jump)))throw new SecurityException("历史来源范围不是精确原定位");
        return new JSONObject(supplied.toString());
    }
    public static JSONObject checkedCitationPage(JSONObject supplied,JSONObject citation)throws Exception{
        JSONObject wanted=checkedCitation(citation),jump=wanted.getJSONObject("sourceJump");
        for(String key:List.of("callerTaskId","callerRunId","conversationId"))if(!wanted.getString(key).equals(supplied.optString(key)))throw new SecurityException("历史回看回复不属于原调用者");
        for(String key:List.of("executionAuthorized","executionIndependentlyVerified","formalMemorySaved","completeOriginalTrace"))if(!Boolean.FALSE.equals(supplied.opt(key)))throw new SecurityException("历史回看不能自证授权或执行");
        String status=supplied.optString("status");if(!Set.of("current","deleted","retired","source_changed","provenance_unavailable","read_limited","unavailable").contains(status))throw new SecurityException("原历史来源状态未知");
        if(!"current".equals(status)){JSONObject result=new JSONObject(supplied.toString());result.remove("body");result.remove("readRange");return result;}
        if("task_raw_source".equals(jump.getString("kind"))){
            JSONObject input=new JSONObject().put("sourceId",jump.getString("sourceId")).put("revision",jump.getString("revision")).put("offset",jump.getInt("offset")).put("maxChars",Math.max(64,jump.getInt("chars")));
            JSONObject nativeWanted=TaskMemoryTrace.rawCitation(jump.getString("taskId"),jump.getString("runId"),input);SourceLocator selected=SourceLocator.from(wanted.getJSONObject("taskSource"));JSONObject checked=selected.kind==Kind.OUTPUT?ToolOutputStore.checkedPage(supplied,nativeWanted):TaskMemoryTrace.checkedOperationPage(supplied,nativeWanted);if(!"current".equals(checked.optString("status")))throw new SecurityException("原操作内容指纹已变化");
        }else{
            if(!TaskMemoryTrace.rawFingerprint(SourceRef.fromJson(jump.getJSONObject("sourceRef")).toJson()).equals(TaskMemoryTrace.rawFingerprint(SourceRef.fromJson(supplied.getJSONObject("sourceRef")).toJson()))||!jump.getString("bodyFingerprint").equals(supplied.optString("bodyFingerprint")))throw new SecurityException("原消息修订或内容指纹已变化");
        }
        JSONObject range=supplied.getJSONObject("readRange");int chars=jump.getInt("chars"),offset=jump.getInt("offset");String body=supplied.getString("body");MemoryBook.validateUnicode(body);
        if(!"UTF-16".equals(range.optString("unit"))||range.getInt("offset")!=offset||range.getInt("chars")!=body.length()||body.length()<chars||body.length()>Math.max(64,chars)||(long)offset+body.length()>SourceRef.integer(range,"totalChars",0))throw new SecurityException("没有读到这个历史引用的精确范围");
        int end=TaskMemoryTrace.safeEnd(body,0,chars);if(end!=chars)throw new SecurityException("历史引用字符边界变化");
        return new JSONObject(supplied.toString()).put("body",body.substring(0,chars)).put("readRange",TaskMemoryTrace.rawRange(offset,offset+chars,range.getInt("totalChars"))).put("nextOffset",(long)offset+chars<range.getInt("totalChars")?offset+chars:-1).put("sourceJump",new JSONObject(jump.toString()));
    }
    public interface ProjectionBatch extends AutoCloseable {
        JSONObject callerTask()throws Exception;
        JSONArray project(JSONArray messages)throws Exception;
        JSONObject readBudget()throws Exception;
        default long sourceBytesRemaining()throws Exception{return 0;}
        default void chargeSourceUtf8Bytes(long bytes)throws Exception{if(bytes!=0)throw new TaskStoreMemoryReader.RawMessageUnavailable("extra_source_projection_budget_unavailable");}
        @Override void close()throws Exception;
    }
    public interface SourceBytes {long remaining()throws Exception;void charge(long bytes)throws Exception;}
    public interface ProjectionBatches {ProjectionBatch open(long sourceBytesRemaining)throws Exception;}
    public interface MessageProjection {
        JSONArray project(JSONArray messages,Set<String> delegatedMessageIds)throws Exception;
        default JSONArray project(JSONArray messages,Set<String> delegatedMessageIds,SourceBytes bytes)throws Exception{for(int i=0;i<messages.length();i++){JSONArray refs=messages.getJSONObject(i).optJSONArray("ownerSourceRefs");if(refs!=null&&refs.length()>0)throw new TaskStoreMemoryReader.RawMessageUnavailable("owner_source_projection_budget_unavailable");}return project(messages,delegatedMessageIds);}
    }
    /** Native composition shared by the real App mount and focused original-store fixtures. */
    public static TaskStoreMemoryReader.RawMessageCurrentness currentness(HistoryBinding binding,ProjectionBatches batches,MessageProjection assistant){
        return (originals,alreadyRead)->{
            ProjectionBatch batch=batches.open(TaskStoreMemoryQuery.RAW_MAX_BYTES-alreadyRead);
            try{JSONObject current=batch.callerTask();Set<String> delegated=new HashSet<>();JSONArray inputs=current.optJSONArray("ownerInputs");for(int i=0;inputs!=null&&i<inputs.length();i++)delegated.add(inputs.getJSONObject(i).getString("messageId"));
                SourceBytes bytes=new SourceBytes(){public long remaining()throws Exception{return batch.sourceBytesRemaining();}public void charge(long count)throws Exception{batch.chargeSourceUtf8Bytes(count);}};
                return currentnessSession(originals,alreadyRead,messages->batch.project(assistant.project(messages,delegated,bytes)),batch::readBudget,batch);
            }catch(Exception failed){try{batch.close();}catch(Exception cleanup){failed.addSuppressed(cleanup);}throw failed;}
        };
    }
    public interface SafeProjection {JSONArray project(JSONArray original)throws Exception;}
    /** One bounded native projection used by both raw and H4. It only accepts identical original
     * bytes at an original index; a retired projection is never returned as a replacement source. */
    public static TaskStoreMemoryReader.RawMessageCheck currentnessSession(JSONArray supplied,long sourceBytesAlreadyMaterialized,SafeProjection projection,java.util.concurrent.Callable<JSONObject> nativeBudget,AutoCloseable nativeBatch)throws Exception{
        final long started=System.nanoTime();
        try{
            final JSONArray original=new JSONArray(supplied.toString());if(original.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>TaskStoreMemoryQuery.RAW_MAX_BYTES)throw new TaskStoreMemoryReader.RawMessageUnavailable("currentness_checkpoint_exceeds_inline_limit");
            final JSONArray first=projection.project(original);if(first.length()!=original.length())throw new TaskStoreMemoryReader.RawMessageUnavailable("safe_projection_not_index_preserving");
            final Map<Integer,String> suppliedIndexes=new LinkedHashMap<>();
            return new TaskStoreMemoryReader.RawMessageCheck(){
                boolean closed;int metadataRows=original.length();
                void deadline()throws Exception{if(closed||System.nanoTime()-started>500_000_000L)throw new TaskStoreMemoryReader.RawMessageUnavailable("message_currentness_deadline");}
                public void requireCurrentOriginalMessage(int index,JSONObject message)throws Exception{
                    deadline();if(index<0||index>=original.length())throw new TaskStoreMemoryReader.RawMessageUnavailable("original_message_index_unavailable");String fingerprint=TaskMemoryTrace.rawFingerprint(message);
                    if(!fingerprint.equals(TaskMemoryTrace.rawFingerprint(original.getJSONObject(index)))||!fingerprint.equals(TaskMemoryTrace.rawFingerprint(first.getJSONObject(index))))throw new TaskStoreMemoryReader.RawMessageUnavailable("original_message_unavailable_in_safe_projection");
                    if(suppliedIndexes.size()>=100&&!suppliedIndexes.containsKey(index))throw new TaskStoreMemoryReader.RawMessageUnavailable("message_currentness_source_row_budget");suppliedIndexes.put(index,fingerprint);
                }
                public JSONObject readBudget()throws Exception{return new JSONObject(nativeBudget.call().toString()).put("nativeHistoryProjection",true).put("sourceBytesAlreadyMaterialized",sourceBytesAlreadyMaterialized).put("projectionMessageMetadataRows",metadataRows).put("checkedOriginalSourceRows",suppliedIndexes.size()).put("metadataRowsAreNotSourceBodyScanRows",true).put("sourcePayloadBudget",TaskStoreMemoryQuery.RAW_MAX_BYTES);}
                public void close()throws Exception{
                    if(closed)return;try{deadline();JSONArray current=projection.project(original);metadataRows+=original.length();if(current.length()!=original.length())throw new TaskStoreMemoryReader.RawMessageUnavailable("safe_projection_not_index_preserving");
                        for(Map.Entry<Integer,String> entry:suppliedIndexes.entrySet())if(!entry.getValue().equals(TaskMemoryTrace.rawFingerprint(current.getJSONObject(entry.getKey()))))throw new TaskStoreMemoryReader.RawMessageUnavailable("related_source_retired_during_read");deadline();
                    }finally{closed=true;nativeBatch.close();}
                }
            };
        }catch(Exception failed){try{nativeBatch.close();}catch(Exception cleanup){failed.addSuppressed(cleanup);}throw failed;}
    }
    /** One normal Runner tool call per discovery/expansion, sharing the original steps budget. */
    public static final class Capability implements CapabilityPort {
        private final CapabilityPort existing;private final HistoryBinding binding;private final Tasks tasks;
        private final LinkedHashMap<String,SourceLocator> selections=new LinkedHashMap<>();private final LinkedHashMap<String,JSONObject> cursors=new LinkedHashMap<>();
        private final Map<String,SourceRef> providedMessages=new ConcurrentHashMap<>();private final Map<String,String> providedMessageConversations=new ConcurrentHashMap<>();private final Map<String,SourceLocator> providedTasks=new ConcurrentHashMap<>();private final AutoCloseable sourceCheck;
        private volatile boolean closed;private long admittedPause;
        public Capability(CapabilityPort existing,HistoryBinding binding,Tasks tasks)throws Exception{
            this.existing=Objects.requireNonNull(existing);this.binding=Objects.requireNonNull(binding);this.tasks=Objects.requireNonNull(tasks);admittedPause=binding.caller.pauseRevision;
            if(existing.supports(SEARCH)||existing.supports(READ))throw new IllegalArgumentException("会话历史工具重复");sourceCheck=binding.caller.control.retainAuthorityCheck(this::checkProvidedSources);
        }
        private void checkProvidedSources()throws Exception{
            if(closed)return;binding.requireScopeCurrent();
            if(!providedMessages.isEmpty()){
                if(providedMessages.size()>100)throw new SecurityException("本轮历史来源需缩小范围");
                for(SourceRef ref:providedMessages.values()){
                    String conversation=providedMessageConversations.getOrDefault(ref.sourceId,binding.conversationId);
                    JSONArray states=binding.source.inspect(binding.messageGateAt(ref,conversation),List.of(ref));
                    if(states.length()!=1||!"current".equals(states.getJSONObject(0).optString("status")))throw new SecurityException("已提供的历史来源已变化或停用，旧正文不能继续自动参考");
                }
            }
        }
        private void checkProvidedTasks()throws Exception{if(!closed&&!providedTasks.isEmpty())tasks.requireCurrent(binding,Collections.unmodifiableList(new ArrayList<>(providedTasks.values())));}
        private boolean own(String name){return SEARCH.equals(name)||READ.equals(name);}
        private void lifecycle(RunControl original)throws Exception{if(closed||original!=binding.caller.control)throw new SecurityException("历史调用不是原控制器");if(binding.caller.readerRetired())binding.checkRetiredLifecycle();else binding.check();}
        private void check(RunControl original)throws Exception{if(closed||original!=binding.caller.control)throw new SecurityException("历史调用不是原控制器");binding.check();}
        private SourceLocator selected(String id){SourceLocator value=selections.get(id);if(value==null)throw new SecurityException("请先从原会话发现历史来源，旧选择不能重用");return value;}
        private String cursor(Request request,String internal)throws Exception{if(internal.isEmpty())return "";String token=UUID.randomUUID().toString();cursors.put(token,new JSONObject().put("kind",request.kind).put("selection",request.selection).put("query",request.query).put("after",request.after).put("before",request.before).put("cursor",internal));while(cursors.size()>128)cursors.remove(cursors.keySet().iterator().next());return token;}
        private String unseal(Request request)throws Exception{
            if(request.cursor.isEmpty())return "";JSONObject value=cursors.get(request.cursor);if(value==null)throw new SecurityException("历史游标不是本轮native签发或已失效");if(!request.kind.equals(value.optString("kind"))||!request.selection.equals(value.optString("selection"))||!request.query.equals(value.optString("query"))||request.after!=value.getLong("after")||request.before!=value.getLong("before"))throw new SecurityException("历史游标不能更换查询范围");return value.getString("cursor");
        }
        private String register(JSONObject row)throws Exception{SourceLocator locator=SourceLocator.from(row);String token=UUID.randomUUID().toString();selections.put(token,locator);while(selections.size()>100)selections.remove(selections.keySet().iterator().next());if(locator.message!=null){if(providedMessages.size()>=100&&!providedMessages.containsKey(locator.message.sourceId))throw new SecurityException("本轮保留历史来源过多");providedMessages.put(locator.message.sourceId,locator.message);providedMessageConversations.put(locator.message.sourceId,locator.conversationId.isEmpty()?binding.conversationId:locator.conversationId);}
            if(locator.kind!=Kind.CONVERSATION&&locator.kind!=Kind.MESSAGE&&locator.kind!=Kind.OUTPUT){String key=locator.referenceKey();if(providedTasks.size()>=100&&!providedTasks.containsKey(key))throw new SecurityException("本轮保留历史来源过多");providedTasks.put(key,locator);}
            return token;}
        @Override public JSONObject execute(String name,JSONObject args,RunControl original)throws Exception{
            if(!own(name))return existing.execute(name,args,original);return executeHistory(name,args,original);
        }
        private synchronized JSONObject executeHistory(String name,JSONObject args,RunControl original)throws Exception{
            long pause=original.pauseRevision();check(original);validate(name,args);JSONObject result;SourceLocator readSource=null;
            if(SEARCH.equals(name)){
                Request request=new Request(args);JSONObject options=request.options(unseal(request));SourceLocator locator=request.selection.isEmpty()?null:selected(request.selection);
                HistoryBinding target=binding;
                if(locator!=null)target=binding.forSource(locator.conversationId.isEmpty()?binding.conversationId:locator.conversationId);
                if(request.kind.equals("conversations")){if(binding.readScope==null)throw new SecurityException("当前宿主没有可核对的原会话目录");result=binding.source.conversations(binding.readScope,options,original);}
                else if(request.kind.equals("messages")){if(locator!=null&&locator.kind!=Kind.CONVERSATION)throw new IllegalArgumentException("消息查询只接受本轮原会话位置");Gate gate=target.source.open(target.sourceConversationId);result=target.source.query(gate,options,original);}
                else if(request.kind.equals("neighbors")){if(locator==null||locator.kind!=Kind.MESSAGE)throw new IllegalArgumentException("邻域锚点必须是本轮已发现的原消息，不是任务/轮次/操作");result=target.source.neighbors(target.messageGate(locator.message),locator.message,locator.messageFingerprint,request.direction,options,original);}
                else result=tasks.query(target,locator!=null&&locator.kind==Kind.CONVERSATION?null:locator,request.kind,options);
                JSONArray rows=result.getJSONArray("entries");for(int i=0;i<rows.length();i++)if(!rows.getJSONObject(i).has("sourceConversationId"))rows.getJSONObject(i).put("sourceConversationId",target.sourceConversationId);if(rows.length()>request.limit)throw new SecurityException("历史返回条数超过范围");int chars=0;for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);chars+=row.optString("body").length();row.put("selectionId",register(row));}if(chars>request.maxChars)throw new SecurityException("历史返回正文超过范围");result.put("nextCursor",cursor(request,result.optString("nextCursor")));
            }else{
                SourceLocator locator=selected(args.getString("selectionId"));readSource=locator;int offset=Request.integer(args,"offset",0,0,Integer.MAX_VALUE),max=Request.integer(args,"maxChars",4000,64,16000);
                HistoryBinding target=binding.forSource(locator.conversationId.isEmpty()?binding.conversationId:locator.conversationId);
                if(locator.kind==Kind.MESSAGE)result=target.source.read(target.messageGate(locator.message),locator.message,offset,max,original);
                else if(locator.kind==Kind.OPERATION||locator.kind==Kind.OUTPUT)result=tasks.read(target,locator,offset,max);else throw new IllegalArgumentException("这是原任务/轮次位置，请先查询其操作或留存输出来源");
            }
            check(original);if(original.pauseRevision()!=pause)throw new SecurityException("暂停前历史读取的迟回已丢弃");
            JSONArray refs=new JSONArray(),taskRefs=new JSONArray();
            if(SEARCH.equals(name)){
                JSONArray rows=result.optJSONArray("entries");for(int i=0;rows!=null&&i<rows.length();i++){
                    JSONObject row=rows.getJSONObject(i);if(row.optJSONObject("sourceRef")!=null)refs.put(row.getJSONObject("sourceRef"));
                    else if(!Set.of("task_output","assistant_conversation").contains(row.optString("sourceKind")))taskRefs.put(SourceLocator.from(row).taskReference());
                }
            }else if("current".equals(result.optString("status"))&&result.has("body")){
                if(result.optJSONObject("sourceRef")!=null)refs.put(result.getJSONObject("sourceRef"));
                else if(readSource!=null&&(readSource.kind==Kind.OPERATION||readSource.kind==Kind.OUTPUT)){
                    taskRefs.put(readSource.taskReference());if(readSource.kind==Kind.OUTPUT){String key=readSource.referenceKey();if(providedTasks.size()>=100&&!providedTasks.containsKey(key))throw new SecurityException("本轮保留历史来源过多");providedTasks.put(key,readSource);}
                }
            }
            boolean originalText=result.optString("body").length()>0;JSONArray exposed=result.optJSONArray("entries");
            for(int i=0;exposed!=null&&i<exposed.length();i++)if(!Set.of("task_output","assistant_conversation").contains(exposed.getJSONObject(i).optString("sourceKind")))originalText=true;
            // Output discovery is body-free. Only its actual exact-range read exposes original text.
            if(originalText)binding.caller.modelEvidencePrepared();check(original);
            result.put("messageSourceRefs",refs).put("taskSourceRefs",taskRefs);
            JSONObject reply=envelope(binding);for(Iterator<String> it=result.keys();it.hasNext();){String key=it.next();if(!Set.of("callerTaskId","callerRunId","conversationId","ownerId","spaceId").contains(key))reply.put(key,result.get(key));}
            return proof(reply).put("ok",Set.of("current","partial").contains(reply.optString("status"))).put("message","原会话保留证据读取回执；不是完整历史，也不证明模型采用或授权执行。");
        }
        @Override public void validate(String name,JSONObject args)throws Exception{if(!own(name)){existing.validate(name,args);return;}if(SEARCH.equals(name))new Request(args);else{Set<String> keys=Set.of("selectionId","offset","maxChars");for(Iterator<String> it=args.keys();it.hasNext();)if(!keys.contains(it.next()))throw new IllegalArgumentException("历史展开不能指定其他任务或会话");selected(Request.text(args,"selectionId","",36));Request.integer(args,"offset",0,0,Integer.MAX_VALUE);Request.integer(args,"maxChars",4000,64,16000);}}
        private JSONArray append(JSONArray base)throws Exception{
            JSONArray result=new JSONArray(base.toString());if(binding.caller.readerRetired())return result;JSONObject props=new JSONObject().put("kind",new JSONObject().put("type","string").put("enum",new JSONArray().put("conversations").put("messages").put("neighbors").put("runs").put("operations").put("outputs"))).put("query",string(500)).put("cursor",string(4000)).put("selectionId",string(36)).put("direction",new JSONObject().put("type","string").put("enum",new JSONArray().put("before").put("after"))).put("limit",number(1,20)).put("maxChars",number(64,16000)).put("createdAfter",number(0,Long.MAX_VALUE)).put("createdBefore",number(0,Long.MAX_VALUE));
            result.put(tool(SEARCH,"默认只查当前私人会话；仅主人原生开启后可查其私人旧会话。conversations列无正文会话位置，选择该selectionId后messages/runs只查该原会话。不能传会话ID或扩大scope。messages按query查消息；neighbors用本轮message selectionId+direction=before/after查顺序相邻原文，最近优先，不是父子调用谱系；邻域query须空，不接受cursor/日期，可选边界消息再查。runs无selection先列同会话任务，选task再列runs；operations与outputs须选run；outputs只列该轮保留原输出的metadata，query只筛工具名/ID而不搜索正文，精确范围用memory_history_read。memory_history_read展开native selectionId。任务列表query只筛状态/编号，不查摘要；必须读覆盖/缺口。旧请求不是本次授权。",props,new JSONArray().put("kind")));
            result.put(tool(READ,"只读本轮历史查询已发现的selectionId原件版本/范围，不接受task/run/会话身份。只能展开message、operation或已保留output，不恢复旧批准、不保存正式记忆。",new JSONObject().put("selectionId",string(36)).put("offset",number(0,Integer.MAX_VALUE)).put("maxChars",number(64,16000)),new JSONArray().put("selectionId")));return result;
        }
        private static JSONObject string(int n)throws Exception{return new JSONObject().put("type","string").put("maxLength",n);}private static JSONObject number(long min,long max)throws Exception{return new JSONObject().put("type","integer").put("minimum",min).put("maximum",max);}private static JSONObject tool(String name,String description,JSONObject props,JSONArray required)throws Exception{return new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("description",description).put("parameters",new JSONObject().put("type","object").put("properties",props).put("required",required).put("additionalProperties",false)));}
        @Override public JSONArray tools()throws Exception{return append(existing.tools());}@Override public boolean supports(String name){return !binding.caller.readerRetired()&&own(name)||existing.supports(name);}@Override public boolean sideEffecting(String name){return !own(name)&&existing.sideEffecting(name);}
        @Override public JSONObject contextSnapshot(String query)throws Exception{return existing.contextSnapshot(query);}@Override public void beginRun(String goal,RunControl original)throws Exception{lifecycle(original);existing.beginRun(goal,original);}
        @Override public synchronized void prepareTurnSurface(RunControl original)throws Exception{if(closed||original!=binding.caller.control)throw new SecurityException("历史调用不是原控制器");existing.prepareTurnSurface(original);if(binding.caller.readerRetired()){if(!providedMessages.isEmpty()||!providedTasks.isEmpty())throw new SecurityException("已提供的历史原文仍须保持严格来源检查");selections.clear();cursors.clear();}}
        @Override public synchronized void admitCapabilities(CapabilitySnapshot snapshot,RunControl original)throws Exception{existing.admitCapabilities(snapshot,original);lifecycle(original);if(admittedPause!=original.pauseRevision()){selections.clear();cursors.clear();admittedPause=original.pauseRevision();}}
        @Override public JSONObject prepareTurn(String query,int round,int steps,RunControl original)throws Exception{lifecycle(original);JSONObject turn=existing.prepareTurn(query,round,steps,original);return turn.put("tools",append(turn.getJSONArray("tools")));}
        @Override public boolean responseCurrent(RunControl original)throws Exception{try{lifecycle(original);checkProvidedSources();checkProvidedTasks();return existing.responseCurrent(original);}catch(SecurityException stale){try{binding.requireScopeCurrent();}catch(Exception revoked){return false;}if(!binding.caller.sourceBarrierCurrent())return false;throw stale;}}
        @Override public dev.pocketengine.core.NativeToolObservation.Requirement observationRequirement(String name,JSONObject args,RunControl original)throws Exception{lifecycle(original);return own(name)?dev.pocketengine.core.NativeToolObservation.Requirement.NONE:existing.observationRequirement(name,args,original);}
        @Override public dev.pocketengine.core.NativeToolObservation takeToolObservation(String name,String operation,RunControl original)throws Exception{lifecycle(original);return own(name)?null:existing.takeToolObservation(name,operation,original);}
        @Override public dev.pocketengine.core.NativeToolPlan takeToolPlan(String name,String operation,RunControl original)throws Exception{lifecycle(original);return own(name)?null:existing.takeToolPlan(name,operation,original);}
        @Override public dev.pocketengine.core.NativeReadContextRepair nativeReadContextRepair(JSONArray messages,RunControl original)throws Exception{lifecycle(original);return existing.nativeReadContextRepair(messages,original);}
        @Override public JSONObject beforeTool(String name,JSONObject args,RunControl original)throws Exception{if(own(name))check(original);return existing.beforeTool(name,args,original);}@Override public void afterTool(String name,JSONObject args,JSONObject receipt,RunControl original)throws Exception{existing.afterTool(name,args,receipt,original);}
        @Override public void beforeModelRequest(String requestId,JSONArray messages,RunControl original)throws Exception{lifecycle(original);checkProvidedSources();checkProvidedTasks();existing.beforeModelRequest(requestId,messages,original);}@Override public JSONObject afterModelResponse(String requestId,String answerId,RunControl original)throws Exception{lifecycle(original);checkProvidedTasks();return existing.afterModelResponse(requestId,answerId,original);}@Override public void modelRequestAbandoned(String requestId)throws Exception{existing.modelRequestAbandoned(requestId);}@Override public void endRun(RunControl original)throws Exception{existing.endRun(original);}
        @Override public synchronized void close(){if(closed)return;closed=true;try{sourceCheck.close();}catch(Exception ignored){}providedMessages.clear();providedMessageConversations.clear();providedTasks.clear();selections.clear();cursors.clear();existing.close();}
    }
}
