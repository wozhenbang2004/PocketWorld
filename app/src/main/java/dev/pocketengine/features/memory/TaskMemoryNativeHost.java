package dev.pocketengine.features.memory;

import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.MemorySourceBridge;
import dev.pocketengine.store.TaskStore;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native owner-only task/answer workflow. No EngineModule, model tool or generic bridge route. */
public final class TaskMemoryNativeHost {
    private final FeatureHost host;private final TaskStore tasks;private final MemorySourceBridge sources;
    private final TaskStoreMemoryReader reader;
    public TaskMemoryNativeHost(FeatureHost host,TaskStore tasks,MemorySourceBridge sources){
        this.host=java.util.Objects.requireNonNull(host);this.tasks=java.util.Objects.requireNonNull(tasks);this.sources=java.util.Objects.requireNonNull(sources);
        reader=new TaskStoreMemoryReader(tasks,null,tasks::queryMemoryTasks);
    }
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private MemoryModule memory(){EngineModule module=host.module("memory");if(!(module instanceof MemoryModule))throw new IllegalStateException("唯一记忆模块暂不可用");return (MemoryModule)module;}
    private dev.pocketengine.features.assistant.AssistantModule assistant(){EngineModule original=host.module("assistant");if(!(original instanceof dev.pocketengine.features.assistant.AssistantModule))throw new IllegalStateException("原私人对话宿主暂不可用");return (dev.pocketengine.features.assistant.AssistantModule)original;}
    private TaskMemoryUse use(){return memory().taskMemoryUse(reader,ref->{
        var document=sources.read(ref);return document==null?null:new TaskMemoryUse.Evidence(document.ref,document.title,document.text);
    });}
    public JSONObject handle(String command,JSONObject payload)throws Exception{return handle(command,payload,null);}
    public JSONObject handle(String command,JSONObject payload,dev.pocketengine.core.RunControl ownerReadControl)throws Exception{
        if(payload==null)throw new IllegalArgumentException("缺少原生审核参数");
        Set<String> binding=Set.of("taskId","runId","requestId","answerId"),allowed=new java.util.HashSet<>(binding);
        switch(command){
            case "readOriginalConversationHistoryState":case "reviewOriginalConversationHistoryMigration":allowed=Set.of();break;
            case "activateOriginalConversationHistoryMigration":allowed=Set.of("reviewTaskId","sourceSha256","confirmOriginalScope");break;
            case "cleanupOriginalConversationHistoryMigration":allowed=Set.of("sourceSha256");break;
            case "listOriginalConversations":allowed=Set.of("cursor","limit");break;
            case "searchOwnerOriginals":allowed=Set.of("query","cursor","limit","maxChars");break;
            case "readOwnerOriginalSearchHit":allowed=Set.of("ownerLocator","offset","maxChars");break;
            case "selectOwnerOriginalReference":allowed=Set.of("ownerLocator","offset","chars");break;
            case "readOriginalConversation":allowed=Set.of("conversationId","cursor","limit");break;
            case "deleteOriginalConversation":allowed=Set.of("conversationId","confirmDelete");break;
            case "deleteOriginalMessage":allowed=Set.of("conversationId","messageId","revision","confirmDelete");break;
            case "deleteOriginalImportedRecord":allowed=Set.of("conversationId","recordId","recordVersion","confirmDelete");break;
            case "readLearnedMethodApplications":allowed=Set.of("recordId","revision","cursor","limit");break;
            case "listSavedMemorySources":allowed=Set.of("taskId","beforeEventOrder","limit");break;
            case "selectSavedMemorySource":allowed=Set.of("taskId","runId","eventSeq","offset","chars");break;
            case "getConversationHistoryReadScope":allowed=Set.of("conversationId");break;
            case "setConversationHistoryReadScope":allowed=Set.of("conversationId","revision","mode","ownerConfirmed");break;
            case "searchTaskMemoryTasks":allowed.addAll(Set.of("query","cursor","limit"));break;
            case "readTaskMemoryTrace":allowed=Set.of("taskId","runId","section","query","sourceId","cursor","limit","maxChars","expectedSnapshot");break;
            case "readTaskMemoryTraceOperation":allowed=Set.of("taskId","runId","sourceId","revision","offset","maxChars");break;
            case "readTaskNativeOutputAudit":allowed=Set.of("taskId","runId","sourceId","revision","offset","maxChars");break;
            case "readConversationHistoryCitation":allowed=Set.of("callerTaskId","callerRunId","conversationId","sourceJump","taskSource");break;
            case "readTaskMemoryReceipt":break;
            case "readTaskMemorySource":allowed.addAll(Set.of("id","expectedRevision","sourceRef","receiptId"));break;
            case "listTaskMemoryReplacements":allowed.addAll(Set.of("id","expectedRevision","receiptId"));break;
            case "correctTaskMemoryReviewed":allowed.addAll(Set.of("id","expectedRevision","receiptId","text"));break;
            case "forgetTaskMemoryReviewed":allowed.addAll(Set.of("id","expectedRevision","receiptId"));break;
            case "supersedeTaskMemoryReviewed":allowed.addAll(Set.of("id","expectedRevision","receiptId","keptId","keptRevision","replacedId","replacedRevision"));break;
            default:throw new IllegalArgumentException("此操作不属于原生任务记忆审核");
        }
        for(var keys=payload.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new SecurityException("任务记忆参数不能覆盖原生身份或证明");
        if(command.equals("readOriginalConversationHistoryState"))return assistant().originalHistoryState();
        if(command.equals("reviewOriginalConversationHistoryMigration"))return assistant().reviewOriginalHistoryMigration();
        if(command.equals("activateOriginalConversationHistoryMigration")){
            if(!Boolean.TRUE.equals(payload.opt("confirmOriginalScope")))throw new SecurityException("必须由主人确认这份原生审核范围");
            return assistant().activateOriginalHistoryMigration(payload.getString("reviewTaskId"),payload.getString("sourceSha256"),true);
        }
        if(command.equals("cleanupOriginalConversationHistoryMigration"))return assistant().cleanupOriginalHistoryMigration(payload.getString("sourceSha256"));
        if(command.equals("searchOwnerOriginals"))return assistant().searchOwnerOriginals(payload,ownerReadControl);
        if(command.equals("readOwnerOriginalSearchHit"))return assistant().readOwnerSearchHit(payload,ownerReadControl);
        if(command.equals("selectOwnerOriginalReference"))return assistant().selectOwnerOriginalReference(payload,ownerReadControl);
        if(command.equals("listOriginalConversations"))return assistant().listOriginalConversations(payload.optString("cursor"),Math.toIntExact(SourceRef.integer(payload,"limit",1)));
        if(command.equals("readOriginalConversation"))return assistant().readOriginalConversation(payload.getString("conversationId"),payload.optString("cursor"),Math.toIntExact(SourceRef.integer(payload,"limit",1)));
        if(command.equals("deleteOriginalConversation")||command.equals("deleteOriginalMessage")){
            if(!Boolean.TRUE.equals(payload.opt("confirmDelete")))throw new SecurityException("必须由主人确认删除原文范围");
            JSONObject input=new JSONObject().put("conversationId",payload.getString("conversationId"));if(command.equals("deleteOriginalMessage"))input.put("messageId",payload.getString("messageId")).put("revision",SourceRef.integer(payload,"revision",1));
            return assistant().handle(command.equals("deleteOriginalMessage")?"deleteMessage":"delete",input);
        }
        if(command.equals("deleteOriginalImportedRecord")){
            if(!Boolean.TRUE.equals(payload.opt("confirmDelete")))throw new SecurityException("必须由主人确认删除这份内部记录版本");
            return assistant().deleteOriginalImportedRecord(payload.getString("conversationId"),payload.getString("recordId"),SourceRef.integer(payload,"recordVersion",1));
        }
        JSONObject authenticated=scope();
        if("readConversationHistoryCitation".equals(command))return readHistoryCitation(payload,ownerReadControl);
        if("getConversationHistoryReadScope".equals(command)){if(ownerReadControl!=null)ownerReadControl.check();return assistant().historyReadScopeSnapshot(payload.getString("conversationId"));}
        if("setConversationHistoryReadScope".equals(command)){if(ownerReadControl!=null)ownerReadControl.check();return assistant().setHistoryReadScope(payload.getString("conversationId"),SourceRef.integer(payload,"revision",0),payload.getString("mode"),Boolean.TRUE.equals(payload.opt("ownerConfirmed")));}
        if("readLearnedMethodApplications".equals(command)){if(ownerReadControl!=null)ownerReadControl.check();return tasks.learnedMethodApplications(payload.getString("recordId"),SourceRef.integer(payload,"revision",1),payload.optString("cursor"),Math.toIntExact(SourceRef.integer(payload,"limit",1)));}
        if("listSavedMemorySources".equals(command)){if(ownerReadControl!=null)ownerReadControl.check();return tasks.savedMemorySources(payload.getString("taskId"),payload.has("beforeEventOrder")?SourceRef.integer(payload,"beforeEventOrder",0):0,Math.toIntExact(SourceRef.integer(payload,"limit",1)));}
        if("selectSavedMemorySource".equals(command)){if(ownerReadControl!=null)ownerReadControl.check();var selected=tasks.selectSavedMemorySource(payload.getString("taskId"),payload.getString("runId"),SourceRef.integer(payload,"eventSeq",1),Math.toIntExact(SourceRef.integer(payload,"offset",0)),Math.toIntExact(SourceRef.integer(payload,"chars",1)));return new JSONObject().put("sourceRef",selected.toJson()).put("modelCalled",false).put("executionAuthorized",false);}
        if("searchTaskMemoryTasks".equals(command))return reader.searchTasks(authenticated,payload.getString("query"),payload.optString("cursor"),Math.toIntExact(SourceRef.integer(payload,"limit",1)));
        if("readTaskMemoryTrace".equals(command)){
            JSONObject options=new JSONObject(payload.toString());options.remove("taskId");options.remove("runId");
            return reader.readTaskTrace(authenticated,payload.getString("taskId"),payload.optString("runId"),options);
        }
        if("readTaskMemoryTraceOperation".equals(command))return reader.readTraceOperation(authenticated,payload);
        if("readTaskNativeOutputAudit".equals(command))return tasks.readNativeOutputAudit(MemoryBook.Scope.from(authenticated),payload,ownerReadControl);
        synchronized(sources){
            sources.synchronize(authenticated);synchronized(tasks){String taskId=payload.getString("taskId"),run=payload.getString("runId"),request=payload.getString("requestId"),answer=payload.getString("answerId");
            TaskMemoryUse.ReceiptRef receipt=reader.readReceiptRef(authenticated,taskId,run,request,answer);
            if(receipt==null){
                if(!"readTaskMemoryReceipt".equals(command))throw new IllegalStateException("没有这次请求的原生提供回执，请重新核对");
                return authenticated.put("taskId",taskId).put("runId",run).put("requestId",request).put("answerId",answer).put("phase","unavailable").put("status","unavailable")
                        .put("reason","native_proof_unavailable").put("records",new JSONArray()).put("modelUseVerified",false).put("executionAuthorized",false);
            }
            MemoryHostFence fence=new MemoryHostFence(){
                public void check()throws Exception{JSONObject task=tasks.get(taskId,false);if(task==null||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId")))throw new SecurityException("原任务不属于当前私人空间");}
                public JSONObject admit(Admission action)throws Exception{synchronized(tasks){check();return action.run();}}
            };
            TaskMemoryUse use=use();
            if("readTaskMemoryReceipt".equals(command))return use.readTaskMemoryReceipt(authenticated,receipt,fence);
            String id=payload.getString("id");long revision=SourceRef.integer(payload,"expectedRevision",1);
            if("readTaskMemorySource".equals(command))return use.readTaskMemorySource(authenticated,receipt,id,revision,SourceRef.fromJson(payload.getJSONObject("sourceRef")),fence);
            JSONObject current=use.readTaskMemoryReceipt(authenticated,receipt,fence);requireReviewed(current,payload,id,revision);
            if("listTaskMemoryReplacements".equals(command))return use.listTaskMemoryReplacements(authenticated,receipt,id,revision,fence);
            // Source synchronization and the review-to-write interval share the native source fence.
            JSONObject result;
            if("correctTaskMemoryReviewed".equals(command))result=sources.correctReviewed(authenticated,id,revision,payload.getString("text"));
            else if("forgetTaskMemoryReviewed".equals(command))result=sources.forgetReviewed(authenticated,id,revision);
            else{
                if(!id.equals(payload.getString("replacedId"))||revision!=SourceRef.integer(payload,"replacedRevision",1))throw new SecurityException("替代目标不是本次审阅版本");
                result=sources.supersedeReviewed(authenticated,payload.getString("keptId"),SourceRef.integer(payload,"keptRevision",1),id,revision);
            }
            for(String key:new String[]{"taskId","runId","requestId","answerId","receiptId"})result.put(key,current.get(key));return result;
        }}
    }
    /** A native owner read, admitted twice against the original retained tool receipt. This never
     * lends the active caller's control to a historical task or enters Assistant's admission lock. */
    private JSONObject readHistoryCitation(JSONObject payload,dev.pocketengine.core.RunControl ownerReadControl)throws Exception{
        JSONObject citation=tasks.conversationHistoryCitation(payload),jump=citation.getJSONObject("sourceJump"),result;
        dev.pocketengine.features.memory.ConversationHistory.Gate gate=null;
        if("task_raw_source".equals(jump.getString("kind"))){
            JSONObject input=new JSONObject().put("taskId",jump.getString("taskId")).put("runId",jump.getString("runId")).put("sourceId",jump.getString("sourceId")).put("revision",jump.getString("revision")).put("offset",jump.getInt("offset")).put("maxChars",Math.max(64,jump.getInt("chars")));
            var selected=ConversationHistory.SourceLocator.from(citation.getJSONObject("taskSource"));
            result=selected.kind==ConversationHistory.Kind.OUTPUT?tasks.readNativeOutputAudit(MemoryBook.Scope.from(scope()),input,ownerReadControl):reader.readTraceOperation(scope(),input);
        }else{
            EngineModule selected=host.module("assistant");if(!(selected instanceof dev.pocketengine.features.assistant.AssistantModule))throw new IllegalStateException("原私人对话来源暂不可用");
            var assistant=(dev.pocketengine.features.assistant.AssistantModule)selected;gate=assistant.openHistoryConversation(jump.getString("conversationId"));
            result=assistant.readHistoryMessage(gate,SourceRef.fromJson(jump.getJSONObject("sourceRef")),jump.getInt("offset"),Math.max(64,jump.getInt("chars")),null);
            if("current".equals(result.optString("status"))&&!jump.getString("bodyFingerprint").equals(result.optString("bodyFingerprint")))result=ConversationHistory.proof(new JSONObject().put("status","source_changed").put("reason","cited_message_body_changed").put("sourceRef",jump.getJSONObject("sourceRef")));
        }
        tasks.conversationHistoryCitation(payload);
        if(gate!=null){
            EngineModule selected=host.module("assistant");if(!(selected instanceof dev.pocketengine.features.assistant.AssistantModule)||!gate.current())throw new IllegalStateException("原消息读取期间来源已变化，请重新查询；未显示替代正文");
            var assistant=(dev.pocketengine.features.assistant.AssistantModule)selected;
            JSONObject state=assistant.inspectHistorySources(gate,java.util.List.of(SourceRef.fromJson(jump.getJSONObject("sourceRef")))).getJSONObject(0);
            if(!"current".equals(state.optString("status")))result=ConversationHistory.proof(state);
        }
        for(String key:java.util.List.of("callerTaskId","callerRunId","conversationId"))result.put(key,citation.get(key));
        return ConversationHistory.checkedCitationPage(ConversationHistory.proof(result),citation);
    }
    private static void requireReviewed(JSONObject receipt,JSONObject payload,String id,long revision)throws Exception{
        if(!"provided_to_request".equals(receipt.optString("phase"))||!Set.of("current","invalidated").contains(receipt.optString("status"))||!receipt.getString("receiptId").equals(payload.optString("receiptId")))throw new SecurityException("这次原生来源回执已失效");
        JSONArray rows=receipt.getJSONArray("records");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(id.equals(row.optString("id"))&&revision==row.optLong("revision")&&"current".equals(row.optString("status")))return;}
        throw new IllegalStateException("审阅记忆版本或来源已变化，请刷新后重试");
    }
}
