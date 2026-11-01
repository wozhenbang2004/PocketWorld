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
    public MemorySourceBridge(FeatureHost host,TaskStore tasks){this.host=host;this.tasks=tasks;}
    /** Bind the already-registered singleton before reading a Book; no module lookup while Book is held. */
    public void bindAssistant(AssistantModule value){
        if(value==null||host.module("assistant")!=value||assistant!=null&&assistant!=value)throw new SecurityException("来源读取必须绑定唯一原生对话模块");assistant=value;
    }
    public SourceRef.State resolve(MemoryBook.Scope scope,String kind,String sourceId)throws Exception{
        if(kind.equals("task_evidence"))return tasks.resolveMemorySource(scope,kind,sourceId);
        AssistantModule module=assistant;
        return kind.equals("message")&&module!=null?module.resolveSource(scope,sourceId):null;
    }
    public KnowledgeLearningWorkflow.SourceDocument read(SourceRef ref)throws Exception{
        if("task_evidence".equals(ref.kind))return tasks.readMemorySource(ref);
        AssistantModule module=assistant;return "message".equals(ref.kind)&&module!=null?module.readMemorySource(ref):null;
    }
    public synchronized void synchronize(JSONObject scope)throws Exception{
        if(assistant==null){EngineModule registered=host.module("assistant");if(registered instanceof AssistantModule)bindAssistant((AssistantModule)registered);}
        EngineModule module=host.module("memory");if(!(module instanceof MemoryModule))return;MemoryModule memory=(MemoryModule)module;
        reconcilePending(scope,memory);
        JSONArray sources=memory.sourceRefsForSynchronization(scope);Map<String,SourceRef> refs=new LinkedHashMap<>();
        for(int i=0;i<sources.length();i++){
            SourceRef ref=SourceRef.fromJson(sources.getJSONObject(i));String key=new JSONArray().put(ref.ownerId).put(ref.spaceId).put(ref.kind).put(ref.sourceId).toString();SourceRef old=refs.get(key);if(old==null||ref.revision<old.revision)refs.put(key,ref);
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
    public synchronized JSONObject correctReviewed(JSONObject scope,String id,long revision,String text)throws Exception{
        EngineModule module=host.module("memory");if(!(module instanceof MemoryModule))throw new IllegalStateException("记忆模块暂不可用");MemoryModule memory=(MemoryModule)module;
        reconcilePending(scope,memory);return mutate(scope,memory,false,"owner_correction",receipt->memory.correctReviewed(scope,id,revision,text,receipt));
    }
    public synchronized JSONObject supersedeReviewed(JSONObject scope,String keptId,long keptRevision,String replacedId,long replacedRevision)throws Exception{
        EngineModule module=host.module("memory");if(!(module instanceof MemoryModule))throw new IllegalStateException("记忆模块暂不可用");MemoryModule memory=(MemoryModule)module;
        reconcilePending(scope,memory);return mutate(scope,memory,true,receipt->memory.supersedeReviewed(scope,keptId,keptRevision,replacedId,replacedRevision,receipt));
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
            return result;
        }catch(Exception failure){
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
            JSONObject ref=removed.getJSONObject(i);requireScope(scope,ref);
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
