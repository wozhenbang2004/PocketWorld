package dev.pocketengine.features.memory;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.MemorySourceBridge;
import dev.pocketengine.store.TaskStore;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Original conversation proposals use the existing operations journal and MemoryBook.
 * A model can stage a pending candidate; only an opaque owner review mutates formal memory. */
public final class NativeConversationMemoryDraftHost {
    private final TaskStore tasks;
    private final MemoryModule memory;
    private final MemorySourceBridge sources;
    public NativeConversationMemoryDraftHost(TaskStore tasks,MemoryModule memory,MemorySourceBridge sources){this.tasks=java.util.Objects.requireNonNull(tasks);this.memory=java.util.Objects.requireNonNull(memory);this.sources=java.util.Objects.requireNonNull(sources);}
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static JSONObject text(int maximum)throws Exception{return new JSONObject().put("type","string").put("maxLength",maximum);}
    private static JSONObject integer(long maximum)throws Exception{return new JSONObject().put("type","integer").put("minimum",1).put("maximum",maximum);}
    private static JSONObject object(JSONObject fields,String... required)throws Exception{return new JSONObject().put("type","object").put("properties",fields).put("required",new JSONArray(required)).put("additionalProperties",false);}
    public static JSONObject proposalProperties()throws Exception {
        JSONObject source=object(new JSONObject().put("sourceId",text(80)).put("revision",integer(Long.MAX_VALUE)),"sourceId","revision");
        return new JSONObject().put("action",new JSONObject().put("type","string").put("enum",new JSONArray().put("create").put("update").put("forget")))
                .put("text",text(4000)).put("layer",new JSONObject().put("type","string").put("enum",new JSONArray().put("fact").put("preference").put("episodic").put("procedural")))
                .put("applicability",text(1000)).put("confidence",new JSONObject().put("type","number").put("minimum",0).put("maximum",1)).put("reason",text(1000))
                .put("sources",new JSONObject().put("type","array").put("minItems",1).put("maxItems",20).put("items",source))
                .put("target",object(new JSONObject().put("id",text(80)).put("revision",integer(Long.MAX_VALUE)),"id","revision"));
    }
    /** Closed inert draft. In particular, assistant prose cannot provide user_said authority. */
    public static JSONObject checkedDraft(JSONObject supplied)throws Exception {
        if(supplied==null||supplied.toString().length()>32768)throw new IllegalArgumentException("记忆草稿超过本次原参数范围");
        JSONObject draft=new JSONObject(supplied.toString());String action=draft.getString("action");
        Set<String> allowed=switch(action){case "create"->Set.of("action","text","layer","applicability","confidence","reason","sources");case "update"->Set.of("action","text","reason","sources","target");case "forget"->Set.of("action","reason","sources","target");default->throw new IllegalArgumentException("请选择新增、纠正或遗忘记忆");};
        for(var fields=draft.keys();fields.hasNext();)if(!allowed.contains(fields.next()))throw new SecurityException("记忆草稿不能设置来源身份、权限、主体证明或保存状态");
        bounded(draft,"reason",1,1000);JSONArray selected=draft.getJSONArray("sources");if(selected.length()<1||selected.length()>20)throw new IllegalArgumentException("请选择1至20条真实主人消息");Set<String> ids=new HashSet<>();
        for(int i=0;i<selected.length();i++){JSONObject ref=selected.getJSONObject(i);if(ref.length()!=2||!identity(ref,"sourceId")||!ids.add(ref.getString("sourceId")))throw new SecurityException("来源只能是互不重复的原消息身份与版本");positive(ref,"revision");}
        if(action.equals("create")){bounded(draft,"text",1,4000);bounded(draft,"layer",1,20);if(draft.has("applicability"))bounded(draft,"applicability",0,1000);learningDraft(draft);}
        else {JSONObject target=draft.getJSONObject("target");if(target.length()!=2||!identity(target,"id"))throw new SecurityException("修改须选择确切旧记忆及其版本");positive(target,"revision");if(action.equals("update"))bounded(draft,"text",1,4000);}
        return draft;
    }
    private static boolean identity(JSONObject row,String field){return row.opt(field) instanceof String&&row.optString(field).matches("[a-f0-9-]{36}");}
    private static long positive(JSONObject row,String field)throws Exception{Object value=row.get(field);if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<1)throw new IllegalArgumentException("原修订必须为正整数");return ((Number)value).longValue();}
    private static String bounded(JSONObject row,String field,int minimum,int maximum)throws Exception{Object value=row.get(field);if(!(value instanceof String))throw new IllegalArgumentException("记忆内容须为文字");String text=(String)value;MemoryBook.validateUnicode(text);if(text.length()>maximum||text.trim().length()<minimum)throw new IllegalArgumentException("记忆内容长度超出审阅范围");return text.trim();}
    private static JSONObject learningDraft(JSONObject draft)throws Exception {JSONObject content=new JSONObject();for(String key:new String[]{"text","layer","reason","applicability","confidence"})if(draft.has(key))content.put(key,draft.get(key));return MemoryBook.checkedLearningDraft(content);}
    public static String proposalDigest(JSONObject draft)throws Exception{return TaskStore.capabilityFingerprint(checkedDraft(draft));}
    private static String digest(JSONObject value)throws Exception{return TaskStore.capabilityFingerprint(value);}
    private static String refsDigest(JSONArray refs)throws Exception{java.util.List<String> sorted=new java.util.ArrayList<>();for(int i=0;i<refs.length();i++)sorted.add(digest(refs.getJSONObject(i)));java.util.Collections.sort(sorted);return digest(new JSONObject().put("sources",new JSONArray(sorted)));}
    private static JSONObject origin(String task,String run,int epoch,String operation,String digest)throws Exception{return new JSONObject().put("taskId",task).put("runId",run).put("epoch",epoch).put("operationId",operation).put("proposalDigest",digest);}
    private JSONObject originalTask(String task,String run,int epoch)throws Exception{JSONObject row=tasks.get(task,false);if(row==null||!"assistant".equals(row.optString("kind"))||!"owner".equals(row.optString("actorId"))||!"private".equals(row.optString("spaceId"))||row.has("origin")||row.has("projectMemoryRead")||row.has("developmentContext")||!run.equals(row.optString("runId"))||epoch!=row.optInt("coordinatorEpoch")||row.optString("conversationId").isBlank())throw new SecurityException("记忆提案仅属于原普通私人会话，不替代项目记忆范围");return row;}
    private JSONObject record(JSONObject target)throws Exception{JSONObject row=memory.handle("get",new JSONObject().put("scope",scope()).put("id",target.getString("id"))).getJSONObject("record");if(positive(target,"revision")!=row.getLong("revision")||!"owner".equals(row.optString("ownerId"))||!"private".equals(row.optString("spaceId")))throw new SecurityException("原记忆已变化，不能套用旧修改");return row;}
    private static JSONObject targetPin(JSONObject record)throws Exception{return new JSONObject().put("id",record.getString("id")).put("revision",record.getLong("revision")).put("digest",digest(record));}
    private static JSONObject candidateContent(JSONObject candidate)throws Exception{JSONObject draft=new JSONObject();for(String key:new String[]{"text","layer","reason","applicability","confidence","assertion"})if(candidate.has(key))draft.put(key,candidate.get(key));return MemoryBook.checkedLearningDraft(draft);}
    private static JSONObject candidatePin(JSONObject candidate)throws Exception{return new JSONObject().put("id",candidate.getString("id")).put("revision",candidate.getLong("revision")).put("digest",digest(new JSONObject().put("draft",candidateContent(candidate)).put("sources",refsDigest(candidate.getJSONArray("sourceRefs")))));}
    private JSONObject pendingCandidate(JSONObject pin,JSONObject draft,JSONArray refs)throws Exception {
        JSONObject candidate=memory.handle("getCandidate",new JSONObject().put("scope",scope()).put("id",pin.getString("id"))).getJSONObject("candidate");
        if(!"pending".equals(candidate.optString("status"))||positive(pin,"revision")!=candidate.getLong("revision")||!pin.getString("digest").equals(candidatePin(candidate).getString("digest"))||!digest(learningDraft(draft)).equals(digest(candidateContent(candidate)))||!refsDigest(refs).equals(refsDigest(candidate.getJSONArray("sourceRefs"))))throw new SecurityException("原待审候选已修改、失效或审阅，不能用旧提案继续确认");return candidate;
    }
    public JSONObject sourcesForTask(String task,String run,int epoch,RunControl original)throws Exception {
        if(original==null||!original.matchesHostRun(task,run,epoch))throw new SecurityException("来源发现缺少原运行控制器");tasks.checkCapability(task,run,epoch,"memory_draft_sources",original);JSONObject current=originalTask(task,run,epoch);
        JSONArray refs=tasks.preparedConversationUserSources(task,run,epoch,current.getString("conversationId"));original.check();return new JSONObject().put("sources",refs).put("candidateCoverage","currently_verified_prepared_parts_only").put("completeConversation",false).put("bodyIncluded",false).put("modelConsumptionConfirmed",false).put("executionAuthorized",false);
    }
    public JSONObject proposeForTask(String task,String run,int epoch,String operation,JSONObject supplied,RunControl original)throws Exception {
        JSONObject draft=checkedDraft(supplied);tasks.requireMemoryProposalPrepared(task,run,epoch,operation,draft,original);String conversation=originalTask(task,run,epoch).getString("conversationId");JSONArray selected=draft.getJSONArray("sources"),refs=tasks.preparedConversationUserRefs(task,run,epoch,conversation,selected);String action=draft.getString("action");
        JSONObject projection=sources.withCurrentUserSources(refs,()->tasks.withMemoryProposalSourceCurrent(task,run,epoch,conversation,selected,()->{
            original.check();originalTask(task,run,epoch);tasks.requireMemoryProposalPrepared(task,run,epoch,operation,draft,original);
            if(action.equals("create")){SourceRef[] actual=new SourceRef[refs.length()];for(int i=0;i<refs.length();i++)actual[i]=SourceRef.fromJson(refs.getJSONObject(i));JSONObject candidate=memory.proposeLearning(scope(),learningDraft(draft),actual).getJSONObject("candidate");
                if(!"pending".equals(candidate.optString("status"))||!digest(learningDraft(draft)).equals(digest(candidateContent(candidate))))return new JSONObject().put("existingCandidate",new JSONObject().put("id",candidate.getString("id")).put("revision",candidate.getLong("revision")).put("status",candidate.getString("status")));
                return new JSONObject().put("candidate",candidatePin(candidate));
            }
            JSONObject target=record(draft.getJSONObject("target"));if(action.equals("update")){if(target.has("appKnowledge"))throw new SecurityException("App方法须沿原版本试用与修订入口更新");if(target.getString("text").equals(bounded(draft,"text",1,4000)))throw new IllegalArgumentException("新正文与原记忆相同");}return new JSONObject().put("target",targetPin(target));
        }));
        tasks.requireMemoryProposalPrepared(task,run,epoch,operation,draft,original);
        if(projection.has("existingCandidate"))return new JSONObject().put("ok",true).put("proposed",false).put("outcome","existing_candidate_review_barrier").put("candidate",projection.getJSONObject("existingCandidate")).put("formalMemorySaved",false).put("executionAuthorized",false);
        JSONObject proposal=origin(task,run,epoch,operation,proposalDigest(draft)).put("format","pocketworld.conversation-memory-draft.v1").put("conversationId",conversation).put("action",action).put("reason",bounded(draft,"reason",1,1000)).put("sourceRefs",refs)
                .put("sourceCoverage","final_main_prepared_full_messages").put("scope",scope()).put("status","pending_owner").put("contentNature","assistant_proposed_summary").put("formalMemorySaved",false).put("executionAuthorized",false);
        for(var keys=projection.keys();keys.hasNext();){String key=keys.next();proposal.put(key,projection.get(key));}
        return new JSONObject().put("ok",true).put("proposed",true).put("memoryDraft",proposal).put("reviewRequired",true).put("formalMemorySaved",false).put("executionAuthorized",false);
    }
    private JSONObject checkedOwnerRow(String task,String run,int epoch,String operation,String expectedDigest)throws Exception{JSONObject row=tasks.memoryProposalForOwner(task,run,epoch,operation);if(!expectedDigest.equals(row.getJSONObject("proposal").getString("proposalDigest"))||!expectedDigest.equals(proposalDigest(row.getJSONObject("arguments")))||!"pending_owner".equals(row.getJSONObject("apply").getString("status")))throw new SecurityException("原记忆草稿已变化、已取消或已提交");return row;}
    private JSONObject presentation(JSONObject proposal,JSONObject draft,JSONArray refs)throws Exception {
        String action=proposal.getString("action");JSONObject before=null,after=null;
        if(action.equals("create"))after=pendingCandidate(proposal.getJSONObject("candidate"),draft,refs);
        else {before=record(proposal.getJSONObject("target"));if(!proposal.getJSONObject("target").getString("digest").equals(targetPin(before).getString("digest")))throw new SecurityException("原记忆状态或正文已改变");if(action.equals("update")){after=new JSONObject().put("id",before.getString("id")).put("expectedPreviousRevision",before.getLong("revision")).put("layer",before.getString("layer")).put("text",bounded(draft,"text",1,4000)).put("sourceRefs",before.optJSONArray("sourceRefs")).put("existingSourceLineagePreserved",true).put("priorLearningRationaleRetired",before.has("learningRationale"));if(before.has("assertion")){JSONObject assertion=before.getJSONObject("assertion");after.put("assertionEffect",new JSONObject().put("kind","imagined".equals(assertion.optString("kind"))?"imagined":"inferred").put("object",bounded(draft,"text",1,4000)).put("oldEvidenceSpansRetired",true).put("oldObjectIdentityRemoved",!assertion.optString("object").equals(bounded(draft,"text",1,4000))).put("assertedAt","when_saved"));}}}
        return new JSONObject().put("proposal",proposal).put("scope",scope()).put("action",action).put("before",before==null?JSONObject.NULL:before).put("after",after==null?JSONObject.NULL:after).put("sourceRefs",refs).put("reason",bounded(draft,"reason",1,1000))
                .put("contentNature","assistant_proposed_summary").put("truthVerified",false).put("sourceRefsDescribeProposal",true).put("existingSourceLineagePreserved",!action.equals("create")).put("retireDerivedMemory",!action.equals("create")).put("forgetSameEvidenceMemory",action.equals("forget")).put("blocksOldSourceAutomaticReimport",action.equals("forget")).put("originalSourcesDeleted",false).put("requiresExplicitOwnerConfirm",true).put("executionAuthorized",false);
    }
    public Review reviewForOwner(String task,String run,int epoch,String operation,String expectedDigest)throws Exception {
        JSONObject row=checkedOwnerRow(task,run,epoch,operation,expectedDigest),proposal=row.getJSONObject("proposal"),draft=checkedDraft(row.getJSONObject("arguments"));String conversation=originalTask(task,run,epoch).getString("conversationId");JSONArray selected=draft.getJSONArray("sources"),refs=tasks.preparedConversationUserRefs(task,run,epoch,conversation,selected);
        if(!refsDigest(refs).equals(refsDigest(proposal.getJSONArray("sourceRefs"))))throw new SecurityException("原草稿消息来源已变化");JSONObject view=sources.withCurrentUserSources(refs,()->tasks.withMemoryProposalSourceCurrent(task,run,epoch,conversation,selected,()->presentation(proposal,draft,refs)));
        return new Review(task,run,epoch,operation,conversation,draft,proposal,view);
    }
    public final class Review implements AutoCloseable {
        private final String task,run,operation,conversation,draft,proposal,view,reviewDigest;
        private final int epoch;
        private boolean closed,submitted,provided;
        private Review(String task,String run,int epoch,String operation,String conversation,JSONObject draft,JSONObject proposal,JSONObject presentation)throws Exception {this.task=task;this.run=run;this.epoch=epoch;this.operation=operation;this.conversation=conversation;this.draft=draft.toString();this.proposal=proposal.toString();presentation.put("expiresAt",System.currentTimeMillis()+600_000L);reviewDigest=digest(presentation);view=presentation.put("reviewDigest",reviewDigest).toString();}
        public synchronized JSONObject view()throws Exception{provided=true;return new JSONObject(view).put("closed",closed).put("submitted",submitted);}
        private void live()throws Exception{if(closed||submitted||System.currentTimeMillis()>new JSONObject(view).getLong("expiresAt"))throw new SecurityException("原记忆审阅已关闭、过期或提交");originalTask(task,run,epoch);checkedOwnerRow(task,run,epoch,operation,new JSONObject(proposal).getString("proposalDigest"));}
        public synchronized JSONObject confirm(RunControl nativeOwner)throws Exception {
            live();java.util.Objects.requireNonNull(nativeOwner).check();if(!provided)throw new SecurityException("请先读取完整的新旧记忆正文和来源");JSONObject proposal=new JSONObject(this.proposal),draft=new JSONObject(this.draft);boolean[] entered={false};
            if(!tasks.claimMemoryProposalApplyForOwner(task,run,epoch,operation,proposal.getString("proposalDigest"),reviewDigest))throw new SecurityException("原确认已领取，只能查询已有回执");submitted=true;
            try {
                JSONObject result=sources.withCurrentUserSources(proposal.getJSONArray("sourceRefs"),()->tasks.withMemoryProposalSourceCurrent(task,run,epoch,conversation,draft.getJSONArray("sources"),()->{
                    nativeOwner.check();originalTask(task,run,epoch);presentation(proposal,draft,proposal.getJSONArray("sourceRefs"));String action=proposal.getString("action");entered[0]=true;
                    return switch(action){case "create"->{JSONObject candidate=proposal.getJSONObject("candidate");yield memory.acceptCandidate(scope(),candidate.getString("id"),candidate.getLong("revision"));}case "update"->{JSONObject target=proposal.getJSONObject("target");yield sources.correctReviewed(scope(),target.getString("id"),target.getLong("revision"),bounded(draft,"text",1,4000));}case "forget"->{JSONObject target=proposal.getJSONObject("target");yield sources.forgetReviewed(scope(),target.getString("id"),target.getLong("revision"));}default->throw new SecurityException("未知原记忆操作");};
                }));
                JSONObject saved=savedReceipt(proposal,reviewDigest,result);record(saved);return saved;
            }catch(Exception unavailable){JSONObject result=receipt(proposal,reviewDigest,entered[0]?"unknown":"rejected").put("accepted",entered[0]?JSONObject.NULL:false).put("mutationApplied",entered[0]?JSONObject.NULL:false).put("formalMemorySaved",entered[0]?JSONObject.NULL:false).put("message",entered[0]?"原记忆操作结果尚未完整确认，请查询原回执；不会再次应用":"原消息、记忆或候选已变化，本次未进入正式记忆修改");record(result);return result;}
            finally{closed=true;}
        }
        private void record(JSONObject result)throws Exception{try{tasks.recordMemoryProposalApplyForOwner(task,run,epoch,operation,reviewDigest,result);result.put("journalRecorded",true);}catch(Exception unavailable){result.put("journalRecorded",false).put("queryOriginalReceipt",true);}}
        public synchronized JSONObject cancel()throws Exception{if(closed||submitted)throw new SecurityException("原审阅已经关闭或提交");try{return cancelForOwner(task,run,epoch,operation,new JSONObject(proposal).getString("proposalDigest"));}finally{closed=true;}}
        @Override public synchronized void close(){closed=true;}
    }
    private static JSONObject receipt(JSONObject proposal,String reviewDigest,String status)throws Exception{return origin(proposal.getString("taskId"),proposal.getString("runId"),proposal.getInt("epoch"),proposal.getString("operationId"),proposal.getString("proposalDigest")).put("reviewDigest",reviewDigest).put("action",proposal.getString("action")).put("status",status).put("executionAuthorized",false).put("replayAllowed",false);}
    private static JSONObject savedReceipt(JSONObject proposal,String reviewDigest,JSONObject actual)throws Exception{boolean forget=proposal.getString("action").equals("forget");JSONObject result=receipt(proposal,reviewDigest,"saved").put("accepted",true).put("mutationApplied",true).put("formalMemorySaved",!forget);
        if(forget){if(actual.getInt("deleted")<1)throw new SecurityException("原遗忘没有确定删除回执");result.put("deleted",actual.getInt("deleted"));}
        else{JSONObject record=actual.getJSONObject("record");result.put("record",new JSONObject().put("id",record.getString("id")).put("revision",record.getLong("revision")).put("layer",record.getString("layer")));}
        for(String field:new String[]{"contextRetirement","contextInvalidated","futureRecallBlocked","revocationPending"})if(actual.has(field))result.put(field,actual.get(field));return result;
    }
    public JSONObject cancelForOwner(String task,String run,int epoch,String operation,String expectedDigest)throws Exception{
        JSONObject result=tasks.cancelMemoryProposalForOwner(task,run,epoch,operation,expectedDigest),proposal=result.getJSONObject("proposal");
        if(proposal.getString("action").equals("create")){JSONObject pin=proposal.getJSONObject("candidate");try{JSONObject current=memory.handle("getCandidate",new JSONObject().put("scope",scope()).put("id",pin.getString("id"))).getJSONObject("candidate");if("pending".equals(current.optString("status"))&&pin.getLong("revision")==current.getLong("revision")&&pin.getString("digest").equals(candidatePin(current).getString("digest")))current=memory.handle("rejectCandidate",new JSONObject().put("scope",scope()).put("id",pin.getString("id")).put("revision",pin.getLong("revision")).put("reason","主人取消原对话记忆提案")).getJSONObject("candidate");result.put("candidateReviewStatus",current.getString("status"));}catch(Exception unavailable){result.put("candidateReviewStatus","unconfirmed");}}
        return result;
    }
    /** An accepted original candidate is a native receipt. A coincidentally matching text or
     * absent target does not prove this particular correction/forget, so those stay unknown. */
    public JSONObject queryForOwner(String task,String run,int epoch,String operation)throws Exception {
        JSONObject row=tasks.memoryProposalForOwner(task,run,epoch,operation),proposal=row.getJSONObject("proposal"),apply=row.getJSONObject("apply");if(!Set.of("applying_unknown","unknown").contains(apply.getString("status")))return row;
        if(proposal.getString("action").equals("create")){
            JSONObject pin=proposal.getJSONObject("candidate"),candidate;try{candidate=memory.handle("getCandidate",new JSONObject().put("scope",scope()).put("id",pin.getString("id"))).getJSONObject("candidate");}catch(Exception noOriginal){return row.put("originalEffectUnconfirmed",true).put("replayAllowed",false);}
            if("accepted".equals(candidate.optString("status"))&&pin.getLong("revision")==candidate.optLong("acceptedFromRevision")&&pin.getLong("revision")+1==candidate.getLong("revision")&&pin.getString("digest").equals(candidatePin(candidate).getString("digest"))){
                JSONObject record;try{record=memory.handle("get",new JSONObject().put("scope",scope()).put("id",candidate.getString("acceptedRecordId"))).getJSONObject("record");}catch(Exception noCurrent){return row.put("originalEffectUnconfirmed",true).put("replayAllowed",false);}
                if(record.getLong("revision")==1&&pin.getString("id").equals(record.optString("learningCandidateId"))&&candidate.getString("text").equals(record.getString("text"))&&refsDigest(candidate.getJSONArray("sourceRefs")).equals(refsDigest(record.getJSONArray("sourceRefs")))){
                    JSONObject observed=savedReceipt(proposal,apply.getString("reviewDigest"),new JSONObject().put("record",record)).put("observedFromOriginalCandidate",true);tasks.recordMemoryProposalApplyForOwner(task,run,epoch,operation,apply.getString("reviewDigest"),observed);return tasks.memoryProposalForOwner(task,run,epoch,operation);
                }
            }
        }
        return row.put("originalEffectUnconfirmed",true).put("replayAllowed",false);
    }
}
