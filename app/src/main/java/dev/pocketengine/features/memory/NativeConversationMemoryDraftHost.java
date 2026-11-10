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
                .put("expiresAt",new JSONObject().put("type",new JSONArray().put("integer").put("null")).put("minimum",1).put("description","仅新增记忆：有效期截止的Unix毫秒，null表示无期限；不是审阅窗口期限"))
                .put("sources",new JSONObject().put("type","array").put("maxItems",20).put("items",source))
                .put("assertion",LearningAssertionDraft.proposalSchema())
                .put("target",object(new JSONObject().put("id",text(80)).put("revision",integer(Long.MAX_VALUE)),"id","revision"));
    }
    /** Closed inert draft. In particular, assistant prose cannot provide user_said authority. */
    public static JSONObject checkedDraft(JSONObject supplied)throws Exception {
        if(supplied==null||supplied.toString().length()>32768)throw new IllegalArgumentException("记忆草稿超过本次原参数范围");
        JSONObject draft=new JSONObject(supplied.toString());String action=draft.getString("action");
        Set<String> allowed=switch(action){case "create"->Set.of("action","text","layer","applicability","confidence","reason","sources","assertion","expiresAt");case "update"->Set.of("action","text","reason","sources","target","assertion");case "forget"->Set.of("action","reason","sources","target");default->throw new IllegalArgumentException("请选择新增、纠正或遗忘记忆");};
        for(var fields=draft.keys();fields.hasNext();)if(!allowed.contains(fields.next()))throw new SecurityException("记忆草稿不能设置来源身份、权限、主体证明或保存状态");
        bounded(draft,"reason",1,1000);JSONArray selected=draft.getJSONArray("sources");if(selected.length()<1||selected.length()>20)throw new IllegalArgumentException("请选择1至20条真实主人消息");Set<String> ids=new HashSet<>();
        for(int i=0;i<selected.length();i++){JSONObject ref=selected.getJSONObject(i);if(ref.length()!=2||!identity(ref,"sourceId")||!ids.add(ref.getString("sourceId")))throw new SecurityException("来源只能是互不重复的原消息身份与版本");positive(ref,"revision");}
        if(draft.has("assertion"))LearningAssertionDraft.checkIndexedProposal(draft.getJSONObject("assertion"),selected.length());
        if(action.equals("create")){bounded(draft,"text",1,4000);bounded(draft,"layer",1,20);if(draft.has("applicability"))bounded(draft,"applicability",0,1000);learningDraft(draft);}
        else {JSONObject target=draft.getJSONObject("target");if(target.length()!=2||!identity(target,"id"))throw new SecurityException("修改须选择确切旧记忆及其版本");positive(target,"revision");if(action.equals("update"))bounded(draft,"text",1,4000);}
        return draft;
    }
    private static boolean identity(JSONObject row,String field){return row.opt(field) instanceof String&&row.optString(field).matches("[a-f0-9-]{36}");}
    private static long positive(JSONObject row,String field)throws Exception{Object value=row.get(field);if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<1)throw new IllegalArgumentException("原修订必须为正整数");return ((Number)value).longValue();}
    private static String bounded(JSONObject row,String field,int minimum,int maximum)throws Exception{Object value=row.get(field);if(!(value instanceof String))throw new IllegalArgumentException("记忆内容须为文字");String text=(String)value;MemoryBook.validateUnicode(text);if(text.length()>maximum||text.trim().length()<minimum)throw new IllegalArgumentException("记忆内容长度超出审阅范围");return text.trim();}
    private static JSONObject learningDraft(JSONObject draft)throws Exception {JSONObject content=new JSONObject();for(String key:new String[]{"text","layer","reason","applicability","confidence","expiresAt"})if(draft.has(key))content.put(key,draft.get(key));return MemoryBook.checkedLearningDraft(content);}
    private JSONObject learningDraft(JSONObject draft,JSONArray refs)throws Exception {
        JSONObject content=learningDraft(draft);if(!draft.has("assertion"))return content;
        JSONObject assertion=assertion(draft,refs,content.getString("text"));if(assertion!=null)content.put("assertion",assertion);return content;
    }
    private JSONObject assertion(JSONObject draft,JSONArray refs,String text)throws Exception {
        JSONObject claim=new JSONObject(draft.getJSONObject("assertion").toString()),chosen=draft.getJSONArray("sources").getJSONObject(claim.getInt("sourceIndex"));claim.remove("sourceIndex");
        SourceRef expected=null;for(int i=0;i<refs.length();i++){SourceRef ref=SourceRef.fromJson(refs.getJSONObject(i));if(ref.sourceId.equals(chosen.getString("sourceId"))&&ref.revision==chosen.getLong("revision"))expected=ref;}
        if(expected==null||!"message".equals(expected.kind)||!"owner".equals(expected.ownerId)||!"private".equals(expected.spaceId)||memory.isSourceForgotten(scope(),expected))throw new SecurityException("人本草稿不是当前已准备的主人原消息");
        sources.requireLearningReference(expected);var original=sources.read(expected);
        if(original==null||!expected.equals(original.ref))throw new SecurityException("人本草稿原句不可读或版本已变化");
        JSONObject checked=LearningAssertionDraft.checkedAssertion(claim,text,expected,original.text);
        sources.requireLearningReference(expected);if(memory.isSourceForgotten(scope(),expected))throw new SecurityException("人本草稿原句已被遗忘");return checked;
    }
    private JSONObject updateDraft(JSONObject draft,JSONArray refs,JSONObject before)throws Exception {
        JSONObject value=new JSONObject().put("text",bounded(draft,"text",1,4000));
        if(draft.has("assertion")){JSONObject assertion=assertion(draft,refs,value.getString("text"));if(assertion==null)throw new SecurityException("纠正主体须为明确主人；其他主体请走原生身份审阅");value.put("assertion",assertion);}return value;
    }
    public static String proposalDigest(JSONObject draft)throws Exception{return TaskStore.capabilityFingerprint(checkedDraft(draft));}
    private static String digest(JSONObject value)throws Exception{return TaskStore.capabilityFingerprint(value);}
    private static String refsDigest(JSONArray refs)throws Exception{java.util.List<String> sorted=new java.util.ArrayList<>();for(int i=0;i<refs.length();i++)sorted.add(digest(refs.getJSONObject(i)));java.util.Collections.sort(sorted);return digest(new JSONObject().put("sources",new JSONArray(sorted)));}
    private static JSONObject origin(String task,String run,int epoch,String operation,String digest)throws Exception{return new JSONObject().put("taskId",task).put("runId",run).put("epoch",epoch).put("operationId",operation).put("proposalDigest",digest);}
    private JSONObject originalTask(String task,String run,int epoch)throws Exception{JSONObject row=tasks.get(task,false);if(row==null||!"assistant".equals(row.optString("kind"))||!"owner".equals(row.optString("actorId"))||!"private".equals(row.optString("spaceId"))||row.has("origin")||row.has("projectMemoryRead")||row.has("developmentContext")||!run.equals(row.optString("runId"))||epoch!=row.optInt("coordinatorEpoch")||row.optString("conversationId").isBlank())throw new SecurityException("记忆提案仅属于原普通私人会话，不替代项目记忆范围");if(!tasks.conversationRepository().personalMemoryControls(row.getString("conversationId")).getBoolean("enabled"))throw new SecurityException("此对话已关闭个人记忆，不能提交新的记忆或偏好");return row;}
    private JSONObject record(JSONObject target)throws Exception{JSONObject row=memory.handle("get",new JSONObject().put("scope",scope()).put("id",target.getString("id"))).getJSONObject("record");if(positive(target,"revision")!=row.getLong("revision")||!"owner".equals(row.optString("ownerId"))||!"private".equals(row.optString("spaceId")))throw new SecurityException("原记忆已变化，不能套用旧修改");return row;}
    private static JSONObject targetPin(JSONObject record)throws Exception{return new JSONObject().put("id",record.getString("id")).put("revision",record.getLong("revision")).put("digest",digest(record));}
    private static JSONObject candidateContent(JSONObject candidate)throws Exception{JSONObject draft=new JSONObject();for(String key:new String[]{"text","layer","reason","applicability","confidence","assertion","expiresAt"})if(candidate.has(key))draft.put(key,candidate.get(key));return MemoryBook.checkedLearningDraft(draft);}
    private static JSONObject candidatePin(JSONObject candidate)throws Exception{return new JSONObject().put("id",candidate.getString("id")).put("revision",candidate.getLong("revision")).put("digest",digest(new JSONObject().put("draft",candidateContent(candidate)).put("sources",refsDigest(candidate.getJSONArray("sourceRefs")))));}
    private JSONObject pendingCandidate(JSONObject pin,JSONObject draft,JSONArray refs)throws Exception {
        JSONObject candidate=memory.handle("getCandidate",new JSONObject().put("scope",scope()).put("id",pin.getString("id"))).getJSONObject("candidate");
        if(!"pending".equals(candidate.optString("status"))||positive(pin,"revision")!=candidate.getLong("revision")||!pin.getString("digest").equals(candidatePin(candidate).getString("digest"))||!digest(learningDraft(draft,refs)).equals(digest(candidateContent(candidate)))||!refsDigest(refs).equals(refsDigest(candidate.getJSONArray("sourceRefs"))))throw new SecurityException("原待审候选已修改、失效或审阅，不能用旧提案继续确认");return candidate;
    }
    public JSONObject sourcesForTask(String task,String run,int epoch,RunControl original)throws Exception {
        if(original==null||!original.matchesHostRun(task,run,epoch))throw new SecurityException("来源发现缺少原运行控制器");tasks.checkCapability(task,run,epoch,"memory_draft_sources",original);JSONObject current=originalTask(task,run,epoch);
        JSONArray refs=tasks.preparedConversationUserSources(task,run,epoch,current.getString("conversationId"));original.check();return new JSONObject().put("sources",refs).put("candidateCoverage","currently_verified_prepared_parts_only").put("completeConversation",false).put("bodyIncluded",false).put("modelConsumptionConfirmed",false).put("executionAuthorized",false);
    }
    public JSONObject proposeForTask(String task,String run,int epoch,String operation,JSONObject supplied,RunControl original)throws Exception {
        JSONObject draft=checkedDraft(supplied);tasks.requireMemoryProposalPrepared(task,run,epoch,operation,draft,original);String conversation=originalTask(task,run,epoch).getString("conversationId");JSONArray selected=draft.getJSONArray("sources"),refs=tasks.preparedConversationUserRefs(task,run,epoch,conversation,selected);String action=draft.getString("action");
        JSONObject projection=sources.withCurrentUserSources(refs,()->tasks.withMemoryProposalSourceCurrent(task,run,epoch,conversation,selected,()->{
            original.check();originalTask(task,run,epoch);tasks.requireMemoryProposalPrepared(task,run,epoch,operation,draft,original);
            if(action.equals("create")){SourceRef[] actual=new SourceRef[refs.length()];for(int i=0;i<refs.length();i++)actual[i]=SourceRef.fromJson(refs.getJSONObject(i));JSONObject content=learningDraft(draft,refs),candidate=memory.proposeLearning(scope(),content,actual).getJSONObject("candidate");
                if(!"pending".equals(candidate.optString("status"))||!digest(content).equals(digest(candidateContent(candidate)))||!refsDigest(refs).equals(refsDigest(candidate.getJSONArray("sourceRefs"))))return new JSONObject().put("existingCandidate",new JSONObject().put("id",candidate.getString("id")).put("revision",candidate.getLong("revision")).put("status",candidate.getString("status")));
                return new JSONObject().put("candidate",candidatePin(candidate));
            }
            JSONObject target=record(draft.getJSONObject("target"));if(action.equals("update")){if(target.has("appKnowledge"))throw new SecurityException("App方法须沿原版本试用与修订入口更新");JSONObject content=updateDraft(draft,refs,target);if(target.getString("text").equals(content.getString("text"))&&(!content.has("assertion")||MemoryArchive.canonical(content.getJSONObject("assertion")).equals(MemoryArchive.canonical(target.optJSONObject("assertion")))))throw new IllegalArgumentException("新正文及结构化认知与原记忆相同");}return new JSONObject().put("target",targetPin(target));
        }));
        tasks.requireMemoryProposalPrepared(task,run,epoch,operation,draft,original);
        if(projection.has("existingCandidate"))return new JSONObject().put("ok",true).put("proposed",false).put("outcome","existing_candidate_review_barrier").put("candidate",projection.getJSONObject("existingCandidate")).put("formalMemorySaved",false).put("executionAuthorized",false);
        JSONObject proposal=origin(task,run,epoch,operation,proposalDigest(draft)).put("format","pocketworld.conversation-memory-draft.v1").put("conversationId",conversation).put("action",action).put("reason",bounded(draft,"reason",1,1000)).put("sourceRefs",refs)
                .put("sourceCoverage","final_main_prepared_full_messages").put("scope",scope()).put("status","pending_owner").put("contentNature","assistant_proposed_summary").put("formalMemorySaved",false).put("executionAuthorized",false);
        for(var keys=projection.keys();keys.hasNext();){String key=keys.next();proposal.put(key,projection.get(key));}
        return new JSONObject().put("ok",true).put("proposed",true).put("memoryDraft",proposal).put("reviewRequired",true).put("formalMemorySaved",false).put("executionAuthorized",false);
    }
    private JSONObject checkedOwnerRow(String task,String run,int epoch,String operation,String expectedDigest)throws Exception{JSONObject row=tasks.memoryProposalForOwner(task,run,epoch,operation);if(!expectedDigest.equals(row.getJSONObject("proposal").getString("proposalDigest"))||!expectedDigest.equals(proposalDigest(row.getJSONObject("arguments")))||!"pending_owner".equals(row.getJSONObject("apply").getString("status")))throw new SecurityException("原记忆草稿已变化、已取消或已提交");return row;}
    private static JSONArray retainedSources(JSONArray before,JSONArray added)throws Exception {
        java.util.LinkedHashMap<SourceRef,JSONObject> refs=new java.util.LinkedHashMap<>();
        for(JSONArray selected:new JSONArray[]{before,added})for(int i=0;selected!=null&&i<selected.length();i++){SourceRef ref=SourceRef.fromJson(selected.getJSONObject(i));refs.put(ref,ref.toJson());}
        if(refs.size()>20)throw new IllegalArgumentException("原记忆与新增原句合计超过20条来源，请改为新增独立记忆并审阅关联");
        return new JSONArray(refs.values());
    }
    /** Owner-only review bytes are a transient projection beside the formal assertion. The
     * evidence schema and the durable candidate digest never acquire an extra quote field. */
    private JSONObject assertionEvidence(JSONObject row,boolean required)throws Exception {
        if(row==null||!row.has("assertion"))return row;JSONArray evidence=new JSONArray(),spans=row.getJSONObject("assertion").getJSONArray("evidenceSpans");
        for(int i=0;i<spans.length();i++){JSONObject span=spans.getJSONObject(i);
            try{evidence.put(sources.readAssertionEvidence(scope(),SourceRef.fromJson(span.getJSONObject("sourceRef")),span.getInt("offset"),span.getInt("chars"),span.getString("sha256")));}
            catch(Exception unavailable){if(required)throw unavailable;evidence.put(new JSONObject(span.toString()).put("verifiedOriginalSpan",false).put("assertionTruthVerified",false).put("status","source_unavailable"));}
        }return row.put("assertionEvidence",evidence);
    }
    private JSONObject presentation(JSONObject proposal,JSONObject draft,JSONArray refs)throws Exception {
        String action=proposal.getString("action");JSONObject before=null,after=null;
        if(action.equals("create"))after=pendingCandidate(proposal.getJSONObject("candidate"),draft,refs);
        else {
            before=record(proposal.getJSONObject("target"));if(!proposal.getJSONObject("target").getString("digest").equals(targetPin(before).getString("digest")))throw new SecurityException("原记忆状态或正文已改变");
            if(action.equals("update")){
                JSONObject content=updateDraft(draft,refs,before);
                after=new JSONObject().put("id",before.getString("id")).put("expectedPreviousRevision",before.getLong("revision")).put("layer",before.getString("layer")).put("text",content.getString("text"))
                        .put("sourceRefs",content.has("assertion")?retainedSources(before.optJSONArray("sourceRefs"),refs):before.optJSONArray("sourceRefs"))
                        .put("existingSourceLineagePreserved",true).put("priorLearningRationaleRetired",before.has("learningRationale"));
                if(content.has("assertion"))after.put("assertion",content.getJSONObject("assertion")).put("assertionEffect",new JSONObject().put("structuredCorrection",true).put("oldEvidenceSpansRetired",before.has("assertion")).put("newEvidenceVerified",true));
                else if(before.has("assertion")){JSONObject assertion=before.getJSONObject("assertion");after.put("assertionEffect",new JSONObject().put("kind","imagined".equals(assertion.optString("kind"))?"imagined":"inferred").put("object",content.getString("text")).put("oldEvidenceSpansRetired",true).put("oldObjectIdentityRemoved",!assertion.optString("object").equals(content.getString("text"))).put("assertedAt","when_saved"));}
            }
        }
        before=assertionEvidence(before,false);after=assertionEvidence(after,true);
        return new JSONObject().put("proposal",proposal).put("scope",scope()).put("action",action).put("before",before==null?JSONObject.NULL:before).put("after",after==null?JSONObject.NULL:after).put("sourceRefs",refs).put("reason",bounded(draft,"reason",1,1000))
                .put("contentNature","assistant_proposed_summary").put("truthVerified",false).put("sourceRefsDescribeProposal",!draft.has("assertion")).put("existingSourceLineagePreserved",!action.equals("create")).put("retireDerivedMemory",!action.equals("create")).put("forgetSameEvidenceMemory",action.equals("forget")).put("blocksOldSourceAutomaticReimport",action.equals("forget")).put("originalSourcesDeleted",false).put("requiresExplicitOwnerConfirm",true).put("executionAuthorized",false);
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
                    return switch(action){case "create"->{JSONObject candidate=proposal.getJSONObject("candidate");yield memory.acceptCandidate(scope(),candidate.getString("id"),candidate.getLong("revision"));}case "update"->{JSONObject target=proposal.getJSONObject("target");if(draft.has("assertion")){JSONObject content=updateDraft(draft,proposal.getJSONArray("sourceRefs"),NativeConversationMemoryDraftHost.this.record(target));yield sources.correctAssertionReviewed(scope(),target.getString("id"),target.getLong("revision"),content.getString("text"),content.getJSONObject("assertion"),proposal.getJSONArray("sourceRefs"));}yield sources.correctReviewed(scope(),target.getString("id"),target.getLong("revision"),bounded(draft,"text",1,4000));}case "forget"->{JSONObject target=proposal.getJSONObject("target");yield sources.forgetReviewed(scope(),target.getString("id"),target.getLong("revision"));}default->throw new SecurityException("未知原记忆操作");};
                }));
                JSONObject saved=savedReceipt(proposal,reviewDigest,result);record(saved);return saved.put("undoAvailable",saved.optBoolean("journalRecorded")&&undoAvailable(proposal,saved));
            }catch(PersonalMemorySemantics.PreferenceSettingRequiredException preference){
                JSONObject result=receipt(proposal,reviewDigest,"rejected").put("accepted",false).put("mutationApplied",false).put("formalMemorySaved",false).put("undoAvailable",false)
                        .put("outcome","preference_setting_required").put("preferenceKey",preference.preferenceKey()).put("settingsSection",preference.settingsSection()).put("message",preference.getMessage());
                record(result);return result;
            }catch(MemoryBook.PersonalMemoryDuplicateException duplicate){
                JSONObject result=receipt(proposal,reviewDigest,"rejected").put("accepted",false).put("mutationApplied",false).put("formalMemorySaved",false).put("undoAvailable",false)
                        .put("outcome","existing_personal_memory").put("message","已有相同的个人记忆或候选，本次没有再保存一条");
                if(duplicate.existingRecord()!=null)result.put("existingRecord",duplicate.existingRecord());if(duplicate.existingCandidate()!=null)result.put("existingCandidate",duplicate.existingCandidate());record(result);
                return ownerProjection(new JSONObject().put("proposal",proposal).put("apply",result).put("undo",new JSONObject().put("status","not_requested")));
            }catch(Exception unavailable){JSONObject result=receipt(proposal,reviewDigest,entered[0]?"unknown":"rejected").put("accepted",entered[0]?JSONObject.NULL:false).put("mutationApplied",entered[0]?JSONObject.NULL:false).put("formalMemorySaved",entered[0]?JSONObject.NULL:false).put("undoAvailable",false).put("message",entered[0]?"原记忆操作结果尚未完整确认，请查询原回执；不会再次应用":"原消息、记忆或候选已变化，本次未进入正式记忆修改");record(result);return result;}
            finally{closed=true;}
        }
        private void record(JSONObject result)throws Exception{try{tasks.recordMemoryProposalApplyForOwner(task,run,epoch,operation,reviewDigest,result);result.put("journalRecorded",true);}catch(Exception unavailable){result.put("journalRecorded",false).put("queryOriginalReceipt",true);}}
        public synchronized JSONObject cancel()throws Exception{if(closed||submitted)throw new SecurityException("原审阅已经关闭或提交");try{return cancelForOwner(task,run,epoch,operation,new JSONObject(proposal).getString("proposalDigest"));}finally{closed=true;}}
        @Override public synchronized void close(){closed=true;}
    }
    private static JSONObject receipt(JSONObject proposal,String reviewDigest,String status)throws Exception{return origin(proposal.getString("taskId"),proposal.getString("runId"),proposal.getInt("epoch"),proposal.getString("operationId"),proposal.getString("proposalDigest")).put("reviewDigest",reviewDigest).put("action",proposal.getString("action")).put("status",status).put("executionAuthorized",false).put("replayAllowed",false);}
    private static JSONObject savedReceipt(JSONObject proposal,String reviewDigest,JSONObject actual)throws Exception{boolean forget=proposal.getString("action").equals("forget");JSONObject result=receipt(proposal,reviewDigest,"saved").put("accepted",true).put("mutationApplied",true).put("formalMemorySaved",!forget);
        if(forget){if(actual.getInt("deleted")<1)throw new SecurityException("原遗忘没有确定删除回执");result.put("deleted",actual.getInt("deleted"));}
        else result.put("record",new JSONObject(actual.getJSONObject("record").toString()));
        for(String field:new String[]{"contextRetirement","contextInvalidated","futureRecallBlocked","revocationPending"})if(actual.has(field))result.put(field,actual.get(field));ConversationMemoryReceipt.require(proposal,result);return result;
    }
    public JSONObject cancelForOwner(String task,String run,int epoch,String operation,String expectedDigest)throws Exception{
        JSONObject result=tasks.cancelMemoryProposalForOwner(task,run,epoch,operation,expectedDigest),proposal=result.getJSONObject("proposal");
        if(proposal.getString("action").equals("create")){JSONObject pin=proposal.getJSONObject("candidate");try{JSONObject current=memory.handle("getCandidate",new JSONObject().put("scope",scope()).put("id",pin.getString("id"))).getJSONObject("candidate");if("pending".equals(current.optString("status"))&&pin.getLong("revision")==current.getLong("revision")&&pin.getString("digest").equals(candidatePin(current).getString("digest")))current=memory.handle("rejectCandidate",new JSONObject().put("scope",scope()).put("id",pin.getString("id")).put("revision",pin.getLong("revision")).put("reason","主人取消原对话记忆提案")).getJSONObject("candidate");result.put("candidateReviewStatus",current.getString("status"));}catch(Exception unavailable){result.put("candidateReviewStatus","unconfirmed");}}
        return result;
    }
    private JSONObject currentCreatedRecord(JSONObject proposal,JSONObject applied)throws Exception {
        JSONObject target=ConversationMemoryReceipt.createdRecordForUndo(proposal,applied);
        JSONObject current=memory.handle("get",new JSONObject().put("scope",scope()).put("id",target.getString("id"))).getJSONObject("record");
        requireCurrentCreatedRecord(proposal,applied,current);return current;
    }
    private static void requireCurrentCreatedRecord(JSONObject proposal,JSONObject applied,JSONObject current)throws Exception {
        JSONObject target=ConversationMemoryReceipt.createdRecordForUndo(proposal,applied);
        if(!target.getString("id").equals(current.getString("id"))||current.getLong("revision")!=1||!target.getString("learningCandidateId").equals(current.optString("learningCandidateId"))
                ||!"owner".equals(current.optString("ownerId"))||!"private".equals(current.optString("spaceId"))||!"reviewed_learning".equals(current.optString("formation"))
                ||current.optBoolean("archiveOnly")||current.has("appKnowledge")||current.has("supersedes"))throw new SecurityException("原新增记忆后来已改变或不再属于本次候选，不能撤销");
    }
    private boolean undoAvailable(JSONObject proposal,JSONObject applied){try{currentCreatedRecord(proposal,applied);return true;}catch(Exception unavailable){return false;}}
    /** A current projection beside the immutable save receipt. Missing records do not prove undo. */
    private JSONObject ownerProjection(JSONObject row)throws Exception {
        JSONObject proposal=row.getJSONObject("proposal"),applied=row.getJSONObject("apply"),undo=row.getJSONObject("undo"),saved=new JSONObject(applied.toString());
        String undoStatus=undo.getString("status"),status=applied.getString("status"),unavailableReason="";boolean available=false;
        JSONObject current=null;if("saved".equals(status)&&applied.optJSONObject("record")!=null)try{current=memory.handle("get",new JSONObject().put("scope",scope()).put("id",applied.getJSONObject("record").getString("id"))).getJSONObject("record");}catch(Exception unavailable){/* An absent or unreadable record cannot identify this undo's outcome. */}
        if("saved".equals(status)&&"create".equals(proposal.getString("action"))){
            if("not_requested".equals(undoStatus)&&current!=null)try{requireCurrentCreatedRecord(proposal,applied,current);available=true;}catch(Exception unavailable){unavailableReason="这条记忆后来已变化，不能再撤销这次保存；可以查看当前记忆。";}
            else if("not_requested".equals(undoStatus))unavailableReason="原记忆目前不可读取，暂不能核对或撤销这次保存。";
            else if("rejected".equals(undoStatus))unavailableReason=undo.optString("message","这次撤销没有执行，原保存结果保留。");
        }
        if("rejected".equals(status)&&"existing_personal_memory".equals(applied.optString("outcome"))){
            status="already_saved";row.put("duplicate",true);
            JSONObject existing=applied.optJSONObject("existingRecord");if(existing!=null)try{current=memory.handle("get",new JSONObject().put("scope",scope()).put("id",existing.getString("id"))).getJSONObject("record");}catch(Exception unavailable){/* The duplicate receipt remains true, but its target may no longer be current. */}
            JSONObject candidate=applied.optJSONObject("existingCandidate");if(candidate!=null)try{row.put("existingCandidate",memory.handle("getCandidate",new JSONObject().put("scope",scope()).put("id",candidate.getString("id"))).getJSONObject("candidate"));}catch(Exception unavailable){/* No stale candidate is offered as a current target. */}
        }
        if("reverted".equals(undoStatus))status="reverted";else if(Set.of("undoing_unknown","unknown").contains(undoStatus))status="unknown";
        if(undo.has("message"))row.put("message",undo.get("message"));else if(applied.has("message"))row.put("message",applied.get("message"));
        if(!unavailableReason.isEmpty())row.put("undoUnavailableReason",unavailableReason);
        if(applied.optBoolean("queryOriginalReceipt"))row.put("queryOriginalReceipt",true);
        return row.put("apply",new JSONObject(applied.toString()).put("receipt",saved).put("undoAvailable",available)).put("receipt",saved)
                .put("record",current==null?JSONObject.NULL:current).put("recordCurrent",current!=null).put("status",status).put("undoAvailable",available).put("replayAllowed",false);
    }
    private static JSONObject undoReceipt(JSONObject proposal,JSONObject target,String status)throws Exception {
        return origin(proposal.getString("taskId"),proposal.getString("runId"),proposal.getInt("epoch"),proposal.getString("operationId"),proposal.getString("proposalDigest"))
                .put("action","create").put("status",status).put("record",new JSONObject(target.toString())).put("undoAvailable",false).put("executionAuthorized",false).put("replayAllowed",false);
    }
    private void recordUndo(JSONObject proposal,JSONObject result)throws Exception {
        try{tasks.recordMemoryProposalUndoForOwner(proposal.getString("taskId"),proposal.getString("runId"),proposal.getInt("epoch"),proposal.getString("operationId"),proposal.getString("proposalDigest"),result);result.put("journalRecorded",true);}
        catch(Exception unavailable){result.put("journalRecorded",false).put("queryOriginalReceipt",true);}
    }
    /** One native undo claim on the original operation. Unknown mutations are never replayed. */
    public JSONObject undoForOwner(String task,String run,int epoch,String operation,String expectedDigest,RunControl nativeOwner)throws Exception {
        java.util.Objects.requireNonNull(nativeOwner).check();JSONObject row=queryForOwner(task,run,epoch,operation),proposal=row.getJSONObject("proposal");
        if(!expectedDigest.equals(proposal.getString("proposalDigest")))throw new SecurityException("原记忆提案身份已变化");
        if(!row.getBoolean("undoAvailable"))return row.put("message","原记忆已改变、已撤销或结果待核对，未重复撤销");
        JSONObject target=ConversationMemoryReceipt.createdRecordForUndo(proposal,row.getJSONObject("apply"));
        if(!tasks.claimMemoryProposalUndoForOwner(task,run,epoch,operation,expectedDigest))return queryForOwner(task,run,epoch,operation);
        boolean entered=false;JSONObject result;
        try{
            nativeOwner.check();currentCreatedRecord(proposal,row.getJSONObject("apply"));entered=true;
            JSONObject actual=sources.retractCreatedReviewed(scope(),target.getString("id"),1);
            result=undoReceipt(proposal,target,"reverted").put("undone",true).put("mutationApplied",true).put("deleted",actual.getInt("deleted")).put("removedRefs",actual.getJSONArray("removedRefs"));
            for(String field:new String[]{"contextRetirement","contextInvalidated","futureRecallBlocked","revocationPending","originalSourcesRetained","sameSourceMemoriesRetained"})if(actual.has(field))result.put(field,actual.get(field));
            ConversationMemoryReceipt.requireUndo(proposal,row.getJSONObject("apply"),result);
        }catch(Exception unavailable){result=undoReceipt(proposal,target,entered?"unknown":"rejected").put("undone",entered?JSONObject.NULL:false).put("mutationApplied",entered?JSONObject.NULL:false)
                .put("message",entered?"原撤销结果尚未完整确认，请查询原操作；不会再次删除":"原记忆或主人窗口已变化，本次未进入撤销");}
        recordUndo(proposal,result);
        if(result.optBoolean("journalRecorded"))return queryForOwner(task,run,epoch,operation);
        row.getJSONObject("apply").put("undoAvailable",false);row.put("undo",result).put("record",JSONObject.NULL).put("recordCurrent",false)
                .put("status","rejected".equals(result.getString("status"))?"saved":result.getString("status")).put("undoAvailable",false).put("queryOriginalReceipt",true).put("replayAllowed",false);
        if("rejected".equals(result.getString("status")))row.put("undoUnavailableReason",result.getString("message"));return row;
    }
    /** An accepted original candidate is a native receipt. A coincidentally matching text or
     * absent target does not prove this particular correction/forget, so those stay unknown. */
    public JSONObject queryForOwner(String task,String run,int epoch,String operation)throws Exception {
        JSONObject row=tasks.memoryProposalForOwner(task,run,epoch,operation),proposal=row.getJSONObject("proposal"),apply=row.getJSONObject("apply");if(!Set.of("applying_unknown","unknown").contains(apply.getString("status")))return ownerProjection(row);
        if(proposal.getString("action").equals("create")){
            JSONObject pin=proposal.getJSONObject("candidate"),candidate;try{candidate=memory.handle("getCandidate",new JSONObject().put("scope",scope()).put("id",pin.getString("id"))).getJSONObject("candidate");}catch(Exception noOriginal){return ownerProjection(row.put("originalEffectUnconfirmed",true));}
            if("accepted".equals(candidate.optString("status"))&&pin.getLong("revision")==candidate.optLong("acceptedFromRevision")&&pin.getLong("revision")+1==candidate.getLong("revision")&&pin.getString("digest").equals(candidatePin(candidate).getString("digest"))){
                JSONObject record;try{record=memory.handle("get",new JSONObject().put("scope",scope()).put("id",candidate.getString("acceptedRecordId"))).getJSONObject("record");}catch(Exception noCurrent){return ownerProjection(row.put("originalEffectUnconfirmed",true));}
                if(record.getLong("revision")==1&&pin.getString("id").equals(record.optString("learningCandidateId"))&&candidate.getString("text").equals(record.getString("text"))&&refsDigest(candidate.getJSONArray("sourceRefs")).equals(refsDigest(record.getJSONArray("sourceRefs")))
                        &&MemoryArchive.canonical(candidate.has("expiresAt")?candidate.get("expiresAt"):JSONObject.NULL).equals(MemoryArchive.canonical(record.opt("expiresAt")))){
                    JSONObject observed=savedReceipt(proposal,apply.getString("reviewDigest"),new JSONObject().put("record",record)).put("observedFromOriginalCandidate",true);tasks.recordMemoryProposalApplyForOwner(task,run,epoch,operation,apply.getString("reviewDigest"),observed);return ownerProjection(tasks.memoryProposalForOwner(task,run,epoch,operation));
                }
            }
        }
        return ownerProjection(row.put("originalEffectUnconfirmed",true));
    }
}
