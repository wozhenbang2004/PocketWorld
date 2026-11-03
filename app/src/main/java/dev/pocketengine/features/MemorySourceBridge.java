package dev.pocketengine.features;

import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.features.memory.MemoryBook;
import dev.pocketengine.features.memory.MemoryModule;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.features.skills.ExperienceOrigin;
import dev.pocketengine.features.skills.SkillsModule;
import dev.pocketengine.store.TaskStore;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Reconciles authoritative sources and existing TaskStore cleanup receipts; never a model scheduler. */
public final class MemorySourceBridge {
    private final FeatureHost host;private final TaskStore tasks;
    private volatile AssistantModule assistant;
    private volatile dev.pocketengine.features.workspace.WorkspaceModule workspace;
    private volatile dev.pocketengine.features.attachments.AttachmentsModule attachments;
    private volatile dev.pocketengine.features.attachments.AttachmentBook attachmentBook;
    public MemorySourceBridge(FeatureHost host,TaskStore tasks){this.host=host;this.tasks=tasks;}
    /** Bind the already-registered singleton before reading a Book; no module lookup while Book is held. */
    public void bindAssistant(AssistantModule value){
        if(value==null||host.module("assistant")!=value||assistant!=null&&assistant!=value)throw new SecurityException("来源读取必须绑定唯一原生对话模块");assistant=value;
    }
    /** Bind a singleton only; disk bootstrap is a separate IO step, never a Book resolver. */
    public void bindWorkspace(dev.pocketengine.features.workspace.WorkspaceModule value){
        if(value==null||host.module("workspace")!=value||workspace!=null&&workspace!=value)throw new SecurityException("工作区来源必须绑定唯一原VWS");workspace=value;
    }
    public void bindAttachments(dev.pocketengine.features.attachments.AttachmentsModule value,dev.pocketengine.features.attachments.AttachmentBook book){
        if(value==null||book==null||host.module("attachments")!=value||attachments!=null&&attachments!=value||attachmentBook!=null&&attachmentBook!=book)throw new SecurityException("已保存文档来源必须绑定唯一原附件模块与Book");attachments=value;attachmentBook=book;
    }
    private dev.pocketengine.features.workspace.WorkspaceModule workspace(){
        var value=workspace;if(value==null)throw new IllegalStateException("原工作区来源宿主尚未绑定");return value;
    }
    public SourceRef.AssertionResolver assertionResolver(){return new SourceRef.AssertionResolver(){
        public SourceRef.State resolve(MemoryBook.Scope scope,String kind,String id)throws Exception{return MemorySourceBridge.this.resolve(scope,kind,id);}
        public void requireUserSaid(JSONObject assertion,String exactText)throws Exception {
            if(!exactText.equals(assertion.getString("object")))throw new SecurityException("user_said只能保留确切原句，摘要/推断必须另标类型");JSONArray spans=assertion.getJSONArray("evidenceSpans");if(spans.length()!=1)throw new SecurityException("主人原话须有唯一确切原消息span");JSONObject span=spans.getJSONObject(0);SourceRef ref=SourceRef.fromJson(span.getJSONObject("sourceRef"));if(!"message".equals(ref.kind))throw new SecurityException("文件、网页与任务摘要不是主人原发言");
            tasks.conversationRepository().requireUserSaidSpan(ref,span.getInt("offset"),span.getInt("chars"),span.getString("sha256"),assertion.getString("object"),assertion.isNull("assertedAt")?null:assertion.getLong("assertedAt"));
        }
    };}
    public void prepareSourceHead(String kind,String sourceId,dev.pocketengine.core.RunControl control)throws Exception{
        if("task_evidence".equals(kind)&&dev.pocketengine.features.memory.TaskSavedEvidence.matches(sourceId)){
            if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new IllegalStateException("原已保存文档须在原生IO流程核对字节");
            var address=dev.pocketengine.features.memory.TaskSavedEvidence.address(sourceId);SourceRef expected=new SourceRef(kind,sourceId,address.eventSeq,"owner","private");JSONObject event=tasks.savedMemorySourceEvent(expected);
            if("attachment_document_read".equals(event.optString("type"))){var book=attachmentBook;if(book==null)throw new IllegalStateException("原附件Book尚未绑定");var readControl=control==null?new dev.pocketengine.core.RunControl():control;
                book.readVerifiedMetadata(event.getString("attachmentId"),event.getString("sha256"),readControl);readControl.check();
                if(!book.outputSourceCurrent(event.getString("attachmentId"),event.getString("sha256"),event.getString("attachmentGeneration")))throw new SecurityException("原件字节、代次或私人来源资格已变化");
            }
            tasks.selectSavedMemorySource(address.taskId,address.runId,address.eventSeq,address.offset,address.chars);return;
        }
        if(!"workspace_file".equals(kind))return;
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new IllegalStateException("请在原生IO读取流程准备工作区来源，不在主线程扫描文件");
        SourceRef.WorkspaceAddress address=SourceRef.workspaceAddress(sourceId);if(control!=null)control.check();
        var head=workspace().refreshFileHeadForHost(address.projectId,address.path);if(control!=null)control.check();
        if(!head.authorityKnown||head.version<1||head.deleted)throw new IllegalStateException("所选原工作区文件不存在、已删除或权威未知");
    }
    /** A published VWS observation is the only currentness input here. No lookup, monitor, IO,
     * TaskStore callback or opportunistic warm read is allowed while MemoryBook is held. */
    private SourceRef.State workspaceState(MemoryBook.Scope scope,String sourceId){
        if(!"owner".equals(scope.ownerId)||!"private".equals(scope.spaceId))return null;
        SourceRef.WorkspaceAddress address=SourceRef.workspaceAddress(sourceId);var module=workspace;if(module==null)return null;
        var head=module.currentFileHeadForHost(address.projectId,address.path);
        if(!head.authorityKnown||head.version<1)return null;
        SourceRef current=SourceRef.workspaceFile(address.projectId,address.path,head.version,head.revisionId,head.sha256,address.offset,address.chars,scope.ownerId,scope.spaceId);
        return new SourceRef.State(current,head.deleted);
    }
    private KnowledgeLearningWorkflow.SourceDocument readWorkspace(SourceRef expected)throws Exception{
        if(expected.workspace==null||!"owner".equals(expected.ownerId)||!"private".equals(expected.spaceId))throw new SecurityException("工作区来源不属于原主人学习受众");
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new IllegalStateException("原工作区正文须由已选择的原生IO流程读取");
        MemoryBook.Scope scope=MemoryBook.Scope.from(new JSONObject().put("ownerId",expected.ownerId).put("spaceId",expected.spaceId));
        SourceRef.State before=workspaceState(scope,expected.sourceId);if(before==null||before.deleted||!expected.equals(before.ref))throw new IllegalStateException("原文件版本、revisionId、hash或来源范围已变化");
        var address=expected.workspace;var head=workspace().currentFileHeadForHost(address.projectId,address.path);
        if(head.sizeBytes<0||head.sizeBytes>1_048_576)throw new IllegalStateException("原工作区文件超过本次有界来源读取范围");
        var snapshot=workspace().fileSnapshotForHost(address.projectId,address.path,Math.toIntExact(expected.revision));
        if(snapshot.tombstone||snapshot.version!=expected.revision||!address.revisionId.equals(snapshot.revisionId)||!address.sha256.equals(snapshot.sha256)||snapshot.sizeBytes!=head.sizeBytes)throw new IllegalStateException("原工作区实际字节版本与选择不一致");
        byte[] bytes=snapshot.bytes();String text=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        if(address.offset>text.length()||address.offset>0&&address.offset<text.length()&&Character.isLowSurrogate(text.charAt(address.offset))&&Character.isHighSurrogate(text.charAt(address.offset-1)))throw new IllegalArgumentException("所选UTF-16来源起点不在有效字符边界");
        int end=(int)Math.min((long)text.length(),(long)address.offset+address.chars);if(end<text.length()&&end>address.offset&&Character.isHighSurrogate(text.charAt(end-1)))end--;
        if(end<=address.offset)throw new IllegalStateException("所选原文件范围没有可提炼的文字");String excerpt=text.substring(address.offset,end);
        SourceRef.State after=workspaceState(scope,expected.sourceId);if(after==null||after.deleted||!expected.equals(after.ref))throw new IllegalStateException("原文件在读取期间变化，未交付旧正文");
        int pathEnd=Math.min(60,address.path.length());if(pathEnd<address.path.length()&&pathEnd>0&&Character.isHighSurrogate(address.path.charAt(pathEnd-1)))pathEnd--;String path=address.path.substring(0,pathEnd)+(pathEnd<address.path.length()?"…":"");
        return new KnowledgeLearningWorkflow.SourceDocument(expected,"文件范围："+path+" · UTF-16 "+address.offset+"–"+end+"（非全文声明）",excerpt);
    }
    public SourceRef.State resolve(MemoryBook.Scope scope,String kind,String sourceId)throws Exception{
        if(kind.equals("workspace_file"))return workspaceState(scope,sourceId);
        if(kind.equals("task_evidence")&&dev.pocketengine.features.memory.TaskSavedEvidence.matches(sourceId)){
            SourceRef.State state=tasks.resolveMemorySource(scope,kind,sourceId);if(state==null||state.deleted)return state;JSONObject event=tasks.savedMemorySourceEvent(state.ref);
            if("attachment_document_read".equals(event.optString("type"))){var book=attachmentBook;if(book==null||!book.outputSourceCurrent(event.getString("attachmentId"),event.getString("sha256"),event.getString("attachmentGeneration")))return null;}
            return state;
        }
        if(kind.equals("task_evidence"))return tasks.resolveMemorySource(scope,kind,sourceId);
        AssistantModule module=assistant;
        return kind.equals("message")&&module!=null?module.resolveSource(scope,sourceId):null;
    }
    /** Native metadata use in this SAME canonical forget/correct domain. No user body is read.
     * The supplied Tasks transaction must separately recheck native repo versions/retirement;
     * this monitor alone does not serialize direct repository deletion or context retirement. */
    public synchronized <T>T withCurrentUserSources(JSONArray refs,java.util.concurrent.Callable<T> action)throws Exception {
        EngineModule selected=host.module("memory");if(!(selected instanceof MemoryModule memory)||refs==null||refs.length()<1||refs.length()>64)throw new dev.pocketengine.features.automation.ScheduleSourceState.Unavailable();
        JSONObject scope=new JSONObject().put("ownerId","owner").put("spaceId","private");
        for(int i=0;i<refs.length();i++){SourceRef ref=SourceRef.fromJson(refs.getJSONObject(i));if(!"message".equals(ref.kind)||!"owner".equals(ref.ownerId)||!"private".equals(ref.spaceId))throw new dev.pocketengine.features.automation.ScheduleSourceState.Unavailable();if(memory.isSourceForgotten(scope,ref))throw new dev.pocketengine.features.automation.ScheduleSourceState.Invalidated(dev.pocketengine.features.automation.ScheduleSourceState.Reason.FORGOTTEN);}
        return action.call();
    }
    /** Pure original source currentness, not read authority. Used after a native exact source
     * selection was already read and pinned; it must not warm bytes inside model dispatch. */
    public void requireCurrentReference(SourceRef expected)throws Exception{
        MemoryBook.Scope scope=MemoryBook.Scope.from(new JSONObject().put("ownerId",expected.ownerId).put("spaceId",expected.spaceId));
        SourceRef.State current=resolve(scope,expected.kind,expected.sourceId);
        if(current==null||current.deleted||!expected.equals(current.ref))throw new SecurityException("原来源身份、版本或范围已变化");
    }
    /** A pinned learning parent also requires source read eligibility, not only matching revision. */
    public void requireLearningReference(SourceRef expected)throws Exception {
        requireCurrentReference(expected);
        if("message".equals(expected.kind)&&read(expected)==null)throw new SecurityException("原消息来源不可读"); // Also rejects retired/context-retired original messages.
    }
    public KnowledgeLearningWorkflow.SourceDocument read(SourceRef ref)throws Exception{
        if("workspace_file".equals(ref.kind))return readWorkspace(ref);
        if("task_evidence".equals(ref.kind)&&dev.pocketengine.features.memory.TaskSavedEvidence.matches(ref.sourceId))return readSavedEvidence(ref);
        if("task_evidence".equals(ref.kind))return tasks.readMemorySource(ref);
        AssistantModule module=assistant;return "message".equals(ref.kind)&&module!=null?module.readMemorySource(ref):null;
    }
    private KnowledgeLearningWorkflow.SourceDocument readSavedEvidence(SourceRef expected)throws Exception{
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new IllegalStateException("原保存证据须在原生IO流程读取");requireCurrentReference(expected);JSONObject event=tasks.savedMemorySourceEvent(expected);String text,title;
        if("attachment_document_read".equals(event.optString("type"))){
            var module=attachments;if(module==null)throw new IllegalStateException("原文档解析宿主尚未绑定");var control=new dev.pocketengine.core.RunControl();
            JSONObject result=module.readDocument(event.getString("attachmentId"),event.getString("sha256"),dev.pocketengine.features.memory.TaskSavedEvidence.documentRange(event),control);
            if(!event.getString("documentFingerprint").equals(TaskStore.capabilityFingerprint(result))||!event.getString("parserVersion").equals(result.optString("parserVersion"))||!event.getString("engineVersion").equals(result.optString("engineVersion")))throw new SecurityException("原解析版本或准确读取结果已变化，未用当前文档替代旧回执");
            JSONArray chunks=result.getJSONArray("chunks");if(chunks.length()>256)throw new SecurityException("原文档块超出准确读取范围");StringBuilder joined=new StringBuilder();for(int i=0;i<chunks.length();i++){Object value=chunks.getJSONObject(i).get("text");if(!(value instanceof String))throw new SecurityException("原文档块正文不是文本");if(i>0)joined.append('\n');joined.append((String)value);if(joined.length()>66000)throw new SecurityException("原文档读取超过已批准范围");}
            text=joined.toString();title="原已读取文档范围 · "+result.optString("coverage")+"（不是全文或独立成功证明）";
        }else if("browser_source_retained".equals(event.optString("type"))){text=dev.pocketengine.features.memory.TaskSavedEvidence.browserText(event);title="原已批准保存的网页安全证据范围（非当前网页或整页）";}
        else if("app_learning_evidence".equals(event.optString("type"))){text=dev.pocketengine.features.memory.TaskSavedEvidence.appLearningText(event);title="原任务 App 动作经过（未独立核实成功，仅待审候选）";}
        else throw new IllegalStateException("这份保存来源尚无准确安全正文路由");
        String excerpt=dev.pocketengine.features.memory.TaskSavedEvidence.excerpt(expected,text);requireCurrentReference(expected);tasks.savedMemorySourceEvent(expected);
        return new KnowledgeLearningWorkflow.SourceDocument(expected,title,excerpt,"app_learning_evidence".equals(event.optString("type"))?event.getJSONObject("context"):null);
    }
    public synchronized void synchronize(JSONObject scope)throws Exception{
        if(assistant==null){EngineModule registered=host.module("assistant");if(registered instanceof AssistantModule)bindAssistant((AssistantModule)registered);}
        EngineModule module=host.module("memory");if(!(module instanceof MemoryModule))return;MemoryModule memory=(MemoryModule)module;
        reconcilePending(scope,memory);
        JSONArray sources=memory.sourceRefsForSynchronization(scope);Map<String,SourceRef> refs=new LinkedHashMap<>();
        for(int i=0;i<sources.length();i++){
            SourceRef ref=SourceRef.fromJson(sources.getJSONObject(i));String key=new JSONArray().put(ref.ownerId).put(ref.spaceId).put(ref.kind).put(ref.sourceId).toString();SourceRef old=refs.get(key);if(old==null||ref.revision<old.revision)refs.put(key,ref);
        }
        // Only original indexed/selected paths are bootstrapped, outside the Book monitor. A
        // partial bootstrap refuses to claim all sources ready; the next native IO turn can continue.
        int refreshed=0;java.util.Set<String> paths=new java.util.HashSet<>();
        for(SourceRef ref:refs.values())if("workspace_file".equals(ref.kind)){
            var address=SourceRef.workspaceAddress(ref.sourceId);String path=address.projectId+"\n"+address.path;
            var workspaceModule=workspace();if(!workspaceModule.currentFileHeadForHost(address.projectId,address.path).authorityKnown&&paths.add(path)){
                if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new IllegalStateException("工作区原来源尚待IO核对，未在主线程全盘扫描");
                if(refreshed++>=64)throw new IllegalStateException("原工作区来源超过本次64路径准备窗口，尚未全部就绪");workspaceModule.refreshFileHeadForHost(address.projectId,address.path);
            }
        }
        int savedPrepared=0;for(SourceRef ref:refs.values())if("task_evidence".equals(ref.kind)&&dev.pocketengine.features.memory.TaskSavedEvidence.matches(ref.sourceId)){
            // SourceRef is not a new read grant. Warm only sources already indexed by this Book;
            // original native owner selections use prepareLearningSource on their separate IO path.
            JSONObject event;try{event=tasks.savedMemorySourceEvent(ref);}catch(SecurityException deleted){continue;}
            if("attachment_document_read".equals(event.optString("type"))){var book=attachmentBook;if(book==null)throw new IllegalStateException("原附件Book尚未绑定");
                if(!book.outputSourceCurrent(event.getString("attachmentId"),event.getString("sha256"),event.getString("attachmentGeneration"))){
                    // Count only actual byte warmups. Already checked documents, safe browser
                    // projections and deleted history must not permanently exhaust every pass.
                    if(savedPrepared++>=64)throw new IllegalStateException("已保存文档超过本次64原件准备窗口，尚未全部就绪；可从原IO流程继续核对");
                    prepareSourceHead(ref.kind,ref.sourceId,null);
                }
            }
        }
        MemoryBook.Scope identity=MemoryBook.Scope.from(scope);
        for(SourceRef ref:refs.values()){
            SourceRef.State actual=resolve(identity,ref.kind,ref.sourceId);
            if(actual==null||actual.ref.revision<ref.revision)throw new IllegalStateException("记忆来源尚未完成对账，未发送可能过时的上下文");
            if(actual.deleted)mutate(scope,memory,false,receipt->memory.sourceDeleted(scope,actual.ref,receipt));
            else if(actual.ref.revision>ref.revision)mutate(scope,memory,false,receipt->memory.sourceUpdated(scope,actual.ref,receipt));
        }
        SkillsModule skills=skills();if(skills!=null)skills.reconcileExperiences(scope);
    }
    public synchronized JSONObject forgetReviewed(JSONObject scope,String id,long revision)throws Exception{
        EngineModule module=host.module("memory");if(!(module instanceof MemoryModule))throw new IllegalStateException("记忆模块暂不可用");MemoryModule memory=(MemoryModule)module;
        reconcilePending(scope,memory);return mutate(scope,memory,true,receipt->memory.forgetReviewed(scope,id,revision,receipt));
    }
    /** Native owner namespace use only; private task identity and global source key are unchanged. */
    public synchronized JSONObject forgetProjectReviewed(dev.pocketengine.features.memory.ProjectMemoryNamespaces nativeScope,dev.pocketengine.features.memory.ProjectMemoryNamespaces.Selection selected,String id,long revision)throws Exception{
        EngineModule original=host.module("memory");if(!Thread.holdsLock(nativeScope)||!(original instanceof MemoryModule)||!((MemoryModule)original).ownsProjectNamespacesForHost(nativeScope))throw new SecurityException("项目遗忘不是原Book/Bridge的native准入");final JSONObject[] retired={null};
        JSONObject result;synchronized(tasks){result=nativeScope.forgetWithRetirementForHost(selected,id,revision,removed->{retired[0]=tasks.invalidateProjectMemoryContexts(selected,removed);JSONArray ids=retired[0].getJSONArray("taskIds");for(int i=0;i<ids.length();i++)host.clearTaskWorkingMemory(ids.getString(i));});}
        return result.put("contextRetirement",retired[0]==null?new JSONObject():retired[0]).put("executionAuthorized",false);
    }
    public synchronized JSONObject correctReviewed(JSONObject scope,String id,long revision,String text)throws Exception{
        EngineModule module=host.module("memory");if(!(module instanceof MemoryModule))throw new IllegalStateException("记忆模块暂不可用");MemoryModule memory=(MemoryModule)module;
        reconcilePending(scope,memory);return mutate(scope,memory,false,"owner_correction",receipt->memory.correctReviewed(scope,id,revision,text,receipt));
    }
    public synchronized JSONObject supersedeReviewed(JSONObject scope,String keptId,long keptRevision,String replacedId,long replacedRevision)throws Exception{
        EngineModule module=host.module("memory");if(!(module instanceof MemoryModule))throw new IllegalStateException("记忆模块暂不可用");MemoryModule memory=(MemoryModule)module;
        reconcilePending(scope,memory);return mutate(scope,memory,true,receipt->memory.supersedeReviewed(scope,keptId,keptRevision,replacedId,replacedRevision,receipt));
    }
    public synchronized JSONObject resolveAssertionConflictReviewed(JSONObject scope,JSONArray refs,String decision,String selected,String reason)throws Exception {
        EngineModule module=host.module("memory");if(!(module instanceof MemoryModule))throw new IllegalStateException("记忆模块暂不可用");MemoryModule memory=(MemoryModule)module;
        reconcilePending(scope,memory);return mutate(scope,memory,false,"owner_correction",receipt->memory.resolveAssertionConflictReviewed(scope,refs,decision,selected,reason,receipt));
    }
    /** Actual original-span read; hashes/labels in an assertion are not proof of an utterance. */
    public JSONObject readAssertionEvidence(JSONObject scope,SourceRef source,int offset,int chars,String sha)throws Exception {
        MemoryBook.Scope identity=MemoryBook.Scope.from(scope);if(!source.ownerId.equals(identity.ownerId)||!source.spaceId.equals(identity.spaceId)||offset<0||chars<1||chars>24000||sha==null||!sha.matches("[a-f0-9]{64}"))throw new SecurityException("原证据范围无效");
        EngineModule module=host.module("memory");if(!(module instanceof MemoryModule)||((MemoryModule)module).isSourceForgotten(scope,source))throw new SecurityException("原证据已遗忘，不能从旧alias重新回读");
        prepareSourceHead(source.kind,source.sourceId,null);requireLearningReference(source);KnowledgeLearningWorkflow.SourceDocument original=read(source);
        if(original==null||!source.equals(original.ref))throw new SecurityException("确切原来源不可读");String text=original.text;long end=(long)offset+chars;
        if(end>text.length()||offset>0&&Character.isLowSurrogate(text.charAt(offset))&&Character.isHighSurrogate(text.charAt(offset-1))||end<text.length()&&Character.isLowSurrogate(text.charAt((int)end))&&Character.isHighSurrogate(text.charAt((int)end-1)))throw new SecurityException("原证据范围不在完整UTF-16字符边界");
        String span=text.substring(offset,(int)end);String actual=dev.pocketengine.features.local.project.ProjectSources.sha256(span.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(!actual.equals(sha))throw new SecurityException("原证据hash不同，未用新正文代替旧原话");requireLearningReference(source);if(((MemoryModule)module).isSourceForgotten(scope,source))throw new SecurityException("原证据读取期间已遗忘");
        return new JSONObject().put("sourceRef",source.toJson()).put("offset",offset).put("chars",chars).put("sha256",actual).put("text",span).put("verifiedOriginalSpan",true).put("assertionTruthVerified",false).put("authority","lower_priority_reference_data").put("executionAuthorized",false);
    }
    private interface Mutation{JSONObject apply(MemoryBook.BeforeForget receipt)throws Exception;}
    private JSONObject mutate(JSONObject scope,MemoryModule memory,boolean eraseSnapshots,Mutation mutation)throws Exception{
        return mutate(scope,memory,eraseSnapshots,eraseSnapshots?"owner_forget":"source_change",mutation);
    }
    private JSONObject mutate(JSONObject scope,MemoryModule memory,boolean eraseSnapshots,String cause,Mutation mutation)throws Exception{
        final java.util.List<String> cleanups=new java.util.ArrayList<>();
        final JSONObject[] contextRetirement={null};
        try{
            JSONObject result;synchronized(tasks){result=mutation.apply(removed->{
                if(removed.length()==0)return;
                host.clearWorkingMemory(scope);
                contextRetirement[0]=tasks.invalidateMemoryContexts(MemoryBook.Scope.from(scope),removed);
                AssistantModule conversation=assistant;
                if(conversation!=null&&"owner".equals(scope.optString("ownerId"))&&"private".equals(scope.optString("spaceId")))contextRetirement[0].put("conversationReferencesRetired",conversation.retireMemoryContexts(removed,cause));
                JSONArray bounded=boundedCleanupRefs(scope,removed,eraseSnapshots);
                // TaskStore deliberately bounds journal arrays. Every batch must be
                // durable before returning to MemoryBook's single atomic mutation.
                for(int start=0;start<bounded.length();start+=100){
                    JSONArray batch=new JSONArray();for(int i=start;i<Math.min(start+100,bounded.length());i++)batch.put(bounded.getJSONObject(i));
                    String id=java.util.UUID.randomUUID().toString();tasks.beginExternal("memory","sourceCleanup",id,new JSONObject().put("goal","核对记忆来源并清理旧派生经验"));cleanups.add(id);
                    tasks.status(id,"running","已保存精确清理意图；尚未确认原记忆变更");
                    tasks.beginCapabilityOperation(id,id,"memory","sourceCleanup",new JSONObject().put("nativeReceipt","memory_source_cleanup")
                            .put("scope",new JSONObject(scope.toString())).put("removedRefs",batch).put("cause",cause));
                }
            });}
            for(String id:cleanups)reconcileOne(scope,memory,tasks.get(id,false));
            if(contextRetirement[0]!=null){
                boolean blocked=retiredVersionsAbsent(scope,memory,result.getJSONArray("removedRefs"));
                result.put("contextRetirement",contextRetirement[0]).put("contextInvalidated",contextRetirement[0].getBoolean("contextInvalidated"))
                    .put("futureRecallBlocked",blocked).put("revocationPending",!blocked);
            }
            EngineModule automation=host.module("automation");if(automation instanceof dev.pocketengine.features.automation.AutomationModule schedules)result.put("scheduleSourceRevocation",schedules.reconcileNativeScheduleSources());
            return result;
        }catch(Exception failure){
            try{EngineModule automation=host.module("automation");if(automation instanceof dev.pocketengine.features.automation.AutomationModule schedules)schedules.reconcileNativeScheduleSources();}catch(Exception pending){failure.addSuppressed(pending);}
            for(String id:cleanups)try{JSONObject state=tasks.get(id,false);if(state!=null&&!java.util.Set.of("completed","failed").contains(state.optString("status")))tasks.status(id,"reconciling","原记忆变更或派生清理尚未核对；未重放原操作");}catch(Exception ignored){}
            throw failure;
        }
    }
    private static boolean retiredVersionsAbsent(JSONObject scope,MemoryModule memory,JSONArray removed)throws Exception{
        if(removed.length()==0)return false;JSONArray rows=memory.handle("list",new JSONObject().put("scope",scope)).getJSONArray("records");
        for(int i=0;i<removed.length();i++){JSONObject ref=removed.getJSONObject(i);for(int j=0;j<rows.length();j++){JSONObject row=rows.getJSONObject(j);
            if(ref.getString("recordId").equals(row.getString("id"))&&row.getLong("revision")<=ref.getLong("revision"))return false;
        }}return true;
    }
    static JSONArray boundedCleanupRefs(JSONObject scope,JSONArray removed,boolean eraseSnapshots)throws Exception{
        JSONArray bounded=new JSONArray();
        for(int i=0;i<removed.length();i++){
            JSONObject ref=removed.getJSONObject(i);
            // These are exact native Book removal rows, never a JSON authority input. Project
            // skills are unsupported, so only retire their original Task context above; do not
            // reinterpret their namespace as a private Skill cleanup or remint SourceRef keys.
            if("owner".equals(scope.optString("ownerId"))&&"private".equals(scope.optString("spaceId"))&&"owner".equals(ref.optString("ownerId"))&&ref.optString("spaceId").matches("project:[a-f0-9-]{36}")){
                JSONArray attached=ref.optJSONArray("sourceRefs");for(int j=0;attached!=null&&j<attached.length();j++){SourceRef source=SourceRef.fromJson(attached.getJSONObject(j));if(!"owner".equals(source.ownerId)||!"private".equals(source.spaceId)||source.workspace==null||!ref.getString("spaceId").equals("project:"+source.workspace.projectId))throw new SecurityException("项目退休行不是原同项目来源");}continue;
            }
            requireScope(scope,ref);
            JSONArray evidence=eraseSnapshots?ref.optJSONArray("sourceRefs"):null;
            int size=evidence==null?0:evidence.length();
            for(int offset=0;offset<Math.max(1,size);offset+=100){
                JSONObject part=new JSONObject().put("recordId",ref.getString("recordId")).put("revision",ref.getLong("revision"))
                .put("ownerId",ref.getString("ownerId")).put("spaceId",ref.getString("spaceId"));
                JSONArray chunk=new JSONArray();for(int j=offset;j<Math.min(offset+100,size);j++)chunk.put(evidence.getJSONObject(j));
                part.put("sourceRefs",chunk);bounded.put(part);
            }
        }
        return bounded;
    }
    private void reconcilePending(JSONObject scope,MemoryModule memory)throws Exception{
        JSONArray pending=tasks.memoryCleanupTasks();
        for(int i=0;i<pending.length();i++){
            JSONObject task=pending.getJSONObject(i);JSONArray operations=task.optJSONArray("operations");
            if(operations!=null&&operations.length()>0){
                JSONObject arguments=operations.getJSONObject(0).optJSONObject("arguments");JSONObject recorded=arguments==null?null:arguments.optJSONObject("scope");
                if(recorded!=null&&(!scope.getString("ownerId").equals(recorded.optString("ownerId"))||!scope.getString("spaceId").equals(recorded.optString("spaceId"))))continue;
            }
            reconcileOne(scope,memory,task);
        }
    }
    private void reconcileOne(JSONObject scope,MemoryModule memory,JSONObject task)throws Exception{
        if(task==null||!"memory".equals(task.optString("feature"))||!"sourceCleanup".equals(task.optString("command")))throw new SecurityException("不是原生记忆清理回执");
        String id=task.getString("id");JSONArray operations=task.optJSONArray("operations");
        if(operations==null||operations.length()==0){tasks.finish(id,"failed","原记忆变更没有取得完整清理意图，未执行派生删除",new JSONObject().put("memoryMutation","not_established"));return;}
        for(int i=0;i<operations.length();i++){
            JSONObject operation=operations.getJSONObject(i),args=operation.getJSONObject("arguments"),recorded=args.getJSONObject("scope");
            if(!"memory_sourceCleanup".equals(operation.optString("tool"))||!"memory_source_cleanup".equals(args.optString("nativeReceipt"))
                    ||!scope.getString("ownerId").equals(recorded.optString("ownerId"))||!scope.getString("spaceId").equals(recorded.optString("spaceId")))throw new SecurityException("清理回执空间或来源无效");
            String cause=args.getString("cause");if(!cause.equals("owner_forget")&&!cause.equals("source_change")&&!cause.equals("owner_correction"))throw new SecurityException("清理回执缺少可信原因");
            JSONArray removed=args.getJSONArray("removedRefs"),current=memory.handle("list",new JSONObject().put("scope",scope)).getJSONArray("records");
            // This is an authoritative complete list, not resolveExperience(null), the UI's
            // recent projection or an assumption that a durable pre-intent already committed.
            for(int j=0;j<removed.length();j++){
                JSONObject ref=removed.getJSONObject(j);requireScope(scope,ref);
                for(int k=0;k<current.length();k++){JSONObject row=current.getJSONObject(k);
                    if(ref.getString("recordId").equals(row.optString("id"))&&(!cause.equals("owner_correction")||row.getLong("revision")<=ref.getLong("revision"))){
                        tasks.finishCapabilityOperation(id,operation.getString("operationId"),new JSONObject().put("status","not_committed").put("memoryMutation","original_record_still_present"));
                        tasks.finish(id,"failed","原记忆变更未提交，未清理派生技能；需主人重新核对",new JSONObject().put("memoryMutation","not_committed"));return;
                    }
                }
            }
            SkillsModule skills=skills();if(skills==null)throw new IllegalStateException("派生技能模块暂不可用，清理回执保留待核对");
            int pending=0,newer=0,erased=0;
            for(int j=0;j<removed.length();j++){
                JSONObject ref=removed.getJSONObject(j);JSONObject answer=skills.removeExperienceSource(scope,new ExperienceOrigin.Ref(ref.getString("recordId"),ref.getLong("revision"),ref.getString("ownerId"),ref.getString("spaceId")));
                pending+=answer.optInt("pendingCleanup");newer+=answer.optInt("newerSources");
                JSONArray evidence=ref.optJSONArray("sourceRefs");
                for(int k=0;cause.equals("owner_forget")&&evidence!=null&&k<evidence.length();k++){
                    SourceRef source=SourceRef.fromJson(evidence.getJSONObject(k));
                    if("task_evidence".equals(source.kind)&&memory.isSourceForgotten(scope,source))erased+=tasks.forgetMemorySourceSnapshot(source).optInt("removedSnapshots");
                }
            }
            if(pending>0)throw new IllegalStateException("旧技能已停用，但部分私有文件尚未清理；回执保留等待重试");
            tasks.finishCapabilityOperation(id,operation.getString("operationId"),new JSONObject().put("status","complete").put("memoryMutation","verified_removed").put("preservedNewerSources",newer).put("removedSnapshots",erased));
        }
        tasks.finish(id,"completed","已核对原记忆版本移除并完成对应派生清理",new JSONObject().put("nativeCleanupVerified",true));
    }
    private static void requireScope(JSONObject scope,JSONObject ref)throws Exception{
        if(!scope.getString("ownerId").equals(ref.optString("ownerId"))||!scope.getString("spaceId").equals(ref.optString("spaceId"))||ref.getLong("revision")<1)throw new SecurityException("派生清理来源不能跨空间或使用无效版本");
    }
    private SkillsModule skills(){EngineModule module=host.module("skills");return module instanceof SkillsModule?(SkillsModule)module:null;}
}
